#![forbid(unsafe_code)]

use std::fmt;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};

pub const MAX_JOBS: usize = 32;
pub const MAX_REPOSITORY_BYTES: usize = 16;
pub const MAX_PACKAGE_BYTES: usize = 128;
pub const MAX_MESSAGE_BYTES: usize = 192;
pub const JOB_FILE_SIZE: usize = HEADER_SIZE + MAX_JOBS * SLOT_SIZE;

const MAGIC: &[u8; 8] = b"ARPJOB1\0";
const FORMAT_VERSION: u32 = 1;
const HEADER_SIZE: usize = 32;
const SLOT_SIZE: usize = 368;
const JOB_FILE: &str = "var/lib/archphene/package-jobs.v1";
const JOB_TEMP_FILE: &str = "var/lib/archphene/package-jobs.v1.tmp";

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum JobOperation {
    Install = 1,
    Update = 2,
    Remove = 3,
    Prepare = 4,
}

impl JobOperation {
    fn from_raw(value: u8) -> Option<Self> {
        match value {
            1 => Some(Self::Install),
            2 => Some(Self::Update),
            3 => Some(Self::Remove),
            4 => Some(Self::Prepare),
            _ => None,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum JobState {
    Queued = 1,
    Resolving = 2,
    Downloading = 3,
    Verifying = 4,
    Installing = 5,
    Complete = 6,
    Failed = 7,
    Cancelled = 8,
}

impl JobState {
    fn from_raw(value: u8) -> Option<Self> {
        match value {
            1 => Some(Self::Queued),
            2 => Some(Self::Resolving),
            3 => Some(Self::Downloading),
            4 => Some(Self::Verifying),
            5 => Some(Self::Installing),
            6 => Some(Self::Complete),
            7 => Some(Self::Failed),
            8 => Some(Self::Cancelled),
            _ => None,
        }
    }

    pub fn is_active(self) -> bool {
        matches!(
            self,
            Self::Queued | Self::Resolving | Self::Downloading | Self::Verifying | Self::Installing
        )
    }

    fn can_transition_to(self, next: Self) -> bool {
        if self == next {
            return true;
        }
        match self {
            Self::Queued => matches!(next, Self::Resolving | Self::Cancelled | Self::Failed),
            Self::Resolving => matches!(
                next,
                Self::Downloading | Self::Verifying | Self::Cancelled | Self::Failed
            ),
            Self::Downloading => {
                matches!(next, Self::Verifying | Self::Cancelled | Self::Failed)
            }
            Self::Verifying => {
                matches!(next, Self::Installing | Self::Cancelled | Self::Failed)
            }
            Self::Installing => matches!(next, Self::Complete | Self::Failed),
            Self::Complete | Self::Failed | Self::Cancelled => false,
        }
    }
}

#[derive(Clone, Copy, Eq, PartialEq)]
pub struct BoundedText<const N: usize> {
    bytes: [u8; N],
    length: u16,
}

impl<const N: usize> BoundedText<N> {
    pub const fn empty() -> Self {
        Self {
            bytes: [0; N],
            length: 0,
        }
    }

    pub fn new(value: &str) -> Result<Self, JobError> {
        if value.len() > N || value.len() > usize::from(u16::MAX) {
            return Err(JobError::TextTooLong);
        }
        let mut result = Self::empty();
        result.bytes[..value.len()].copy_from_slice(value.as_bytes());
        result.length = u16::try_from(value.len()).map_err(|_| JobError::TextTooLong)?;
        Ok(result)
    }

    pub fn as_str(&self) -> &str {
        std::str::from_utf8(&self.bytes[..usize::from(self.length)])
            .expect("BoundedText is only constructed from valid UTF-8")
    }

    fn decode(bytes: &[u8], length: u16) -> Result<Self, JobError> {
        let length = usize::from(length);
        if bytes.len() != N || length > N || bytes[length..].iter().any(|byte| *byte != 0) {
            return Err(JobError::CorruptStore);
        }
        let value = std::str::from_utf8(&bytes[..length]).map_err(|_| JobError::CorruptStore)?;
        Self::new(value).map_err(|_| JobError::CorruptStore)
    }
}

impl<const N: usize> fmt::Debug for BoundedText<N> {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        self.as_str().fmt(formatter)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct PackageJob {
    pub id: u64,
    pub operation: JobOperation,
    pub state: JobState,
    pub phase: u8,
    pub progress: u8,
    pub updated_millis: u64,
    pub repository: BoundedText<MAX_REPOSITORY_BYTES>,
    pub package: BoundedText<MAX_PACKAGE_BYTES>,
    pub message: BoundedText<MAX_MESSAGE_BYTES>,
}

#[derive(Clone, Copy)]
struct JobSlot {
    occupied: bool,
    job: PackageJob,
}

impl JobSlot {
    const fn empty() -> Self {
        Self {
            occupied: false,
            job: PackageJob {
                id: 0,
                operation: JobOperation::Install,
                state: JobState::Queued,
                phase: 0,
                progress: 0,
                updated_millis: 0,
                repository: BoundedText::empty(),
                package: BoundedText::empty(),
                message: BoundedText::empty(),
            },
        }
    }
}

#[derive(Debug)]
pub enum JobError {
    InvalidPackage,
    InvalidRepository,
    InvalidProgress,
    InvalidMessage,
    InvalidTransition,
    DuplicateActiveJob,
    StoreFull,
    UnknownJob,
    TextTooLong,
    CorruptStore,
    Io(io::Error),
}

impl fmt::Display for JobError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidPackage => formatter.write_str("invalid package name"),
            Self::InvalidRepository => formatter.write_str("invalid package repository"),
            Self::InvalidProgress => formatter.write_str("invalid package progress"),
            Self::InvalidMessage => formatter.write_str("invalid package job message"),
            Self::InvalidTransition => formatter.write_str("invalid package job transition"),
            Self::DuplicateActiveJob => formatter.write_str("package operation is already active"),
            Self::StoreFull => formatter.write_str("package job store is full"),
            Self::UnknownJob => formatter.write_str("unknown package job"),
            Self::TextTooLong => formatter.write_str("package job text exceeds its limit"),
            Self::CorruptStore => formatter.write_str("package job store is corrupt"),
            Self::Io(error) => write!(formatter, "package job I/O error: {error}"),
        }
    }
}

impl std::error::Error for JobError {}

impl From<io::Error> for JobError {
    fn from(error: io::Error) -> Self {
        Self::Io(error)
    }
}

#[derive(Clone, Copy)]
pub struct PackageJobs {
    slots: [JobSlot; MAX_JOBS],
    next_id: u64,
}

impl PackageJobs {
    pub const fn new() -> Self {
        Self {
            slots: [JobSlot::empty(); MAX_JOBS],
            next_id: 1,
        }
    }

    pub fn begin(
        &mut self,
        operation: JobOperation,
        repository: &str,
        package: &str,
        now_millis: u64,
    ) -> Result<PackageJob, JobError> {
        validate_repository(repository)?;
        validate_package(package)?;
        if self.slots.iter().any(|slot| {
            slot.occupied
                && slot.job.state.is_active()
                && slot.job.operation == operation
                && slot.job.repository.as_str() == repository
                && slot.job.package.as_str() == package
        }) {
            return Err(JobError::DuplicateActiveJob);
        }

        let index = self
            .slots
            .iter()
            .position(|slot| !slot.occupied)
            .or_else(|| {
                self.slots
                    .iter()
                    .enumerate()
                    .filter(|(_, slot)| !slot.job.state.is_active())
                    .min_by_key(|(_, slot)| slot.job.updated_millis)
                    .map(|(index, _)| index)
            })
            .ok_or(JobError::StoreFull)?;
        let job = PackageJob {
            id: self.next_id,
            operation,
            state: JobState::Queued,
            phase: 0,
            progress: 0,
            updated_millis: now_millis,
            repository: BoundedText::new(repository)?,
            package: BoundedText::new(package)?,
            message: BoundedText::new("Queued")?,
        };
        self.next_id = self.next_id.checked_add(1).ok_or(JobError::StoreFull)?;
        self.slots[index] = JobSlot {
            occupied: true,
            job,
        };
        Ok(job)
    }

    pub fn update(
        &mut self,
        id: u64,
        state: JobState,
        phase: u8,
        progress: u8,
        message: &str,
        now_millis: u64,
    ) -> Result<PackageJob, JobError> {
        if progress > 100 {
            return Err(JobError::InvalidProgress);
        }
        if message.is_empty()
            || message.len() > MAX_MESSAGE_BYTES
            || message
                .bytes()
                .any(|byte| matches!(byte, b'\0' | b'\t' | b'\r' | b'\n'))
        {
            return Err(JobError::InvalidMessage);
        }
        let slot = self
            .slots
            .iter_mut()
            .find(|slot| slot.occupied && slot.job.id == id)
            .ok_or(JobError::UnknownJob)?;
        if !slot.job.state.can_transition_to(state) {
            return Err(JobError::InvalidTransition);
        }
        slot.job.state = state;
        slot.job.phase = phase;
        slot.job.progress = progress;
        slot.job.updated_millis = now_millis;
        slot.job.message = BoundedText::new(message)?;
        Ok(slot.job)
    }

    pub fn get(&self, id: u64) -> Option<PackageJob> {
        self.slots
            .iter()
            .find(|slot| slot.occupied && slot.job.id == id)
            .map(|slot| slot.job)
    }

    pub fn active_count(&self) -> usize {
        self.slots
            .iter()
            .filter(|slot| slot.occupied && slot.job.state.is_active())
            .count()
    }

    pub fn latest(&self) -> Option<PackageJob> {
        self.slots
            .iter()
            .filter(|slot| slot.occupied)
            .max_by_key(|slot| slot.job.id)
            .map(|slot| slot.job)
    }

    fn recover_interrupted(&mut self, now_millis: u64) -> Result<u32, JobError> {
        let mut recovered = 0_u32;
        for slot in &mut self.slots {
            if slot.occupied && slot.job.state.is_active() {
                slot.job.state = JobState::Failed;
                slot.job.progress = slot.job.progress.min(99);
                slot.job.updated_millis = now_millis;
                slot.job.message = BoundedText::new("Interrupted; retry is required")?;
                recovered = recovered.saturating_add(1);
            }
        }
        Ok(recovered)
    }
}

impl Default for PackageJobs {
    fn default() -> Self {
        Self::new()
    }
}

pub struct PackageJobStore {
    jobs: PackageJobs,
    path: PathBuf,
    temporary_path: PathBuf,
}

impl PackageJobStore {
    pub fn open(arch_root: &Path, now_millis: u64) -> Result<(Self, u32), JobError> {
        let path = arch_root.join(JOB_FILE);
        let temporary_path = arch_root.join(JOB_TEMP_FILE);
        let exists = path.try_exists()?;
        let jobs = if exists {
            load_jobs(&path)?
        } else {
            PackageJobs::new()
        };
        let mut store = Self {
            jobs,
            path,
            temporary_path,
        };
        let recovered = store.jobs.recover_interrupted(now_millis)?;
        if !exists || recovered != 0 {
            store.persist()?;
        }
        Ok((store, recovered))
    }

    pub fn jobs(&self) -> &PackageJobs {
        &self.jobs
    }

    pub fn begin(
        &mut self,
        operation: JobOperation,
        repository: &str,
        package: &str,
        now_millis: u64,
    ) -> Result<PackageJob, JobError> {
        let previous = self.jobs;
        let job = self
            .jobs
            .begin(operation, repository, package, now_millis)?;
        if let Err(error) = self.persist() {
            self.jobs = previous;
            return Err(error);
        }
        Ok(job)
    }

    pub fn update(
        &mut self,
        id: u64,
        state: JobState,
        phase: u8,
        progress: u8,
        message: &str,
        now_millis: u64,
    ) -> Result<PackageJob, JobError> {
        let previous = self.jobs.get(id).ok_or(JobError::UnknownJob)?;
        let job = self
            .jobs
            .update(id, state, phase, progress, message, now_millis)?;
        if let Err(error) = self.persist() {
            let slot = self
                .jobs
                .slots
                .iter_mut()
                .find(|slot| slot.occupied && slot.job.id == id)
                .expect("updated job remains present");
            slot.job = previous;
            return Err(error);
        }
        Ok(job)
    }

    pub fn persist(&self) -> Result<(), JobError> {
        let mut bytes = [0_u8; JOB_FILE_SIZE];
        encode_jobs(&self.jobs, &mut bytes);
        match fs::symlink_metadata(&self.temporary_path) {
            Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_file() => {
                return Err(JobError::CorruptStore);
            }
            Ok(_) => fs::remove_file(&self.temporary_path)?,
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => return Err(JobError::Io(error)),
        }
        let mut file = OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(&self.temporary_path)?;
        file.set_permissions(fs::Permissions::from_mode(0o600))?;
        file.write_all(&bytes)?;
        file.sync_all()?;
        drop(file);
        fs::rename(&self.temporary_path, &self.path)?;
        // The rename is the commit point. Directory fsync improves crash durability, but a
        // platform that rejects directory fsync must not make memory diverge from the file that
        // was already atomically published.
        if let Some(parent) = self.path.parent() {
            let _ = File::open(parent).and_then(|directory| directory.sync_all());
        }
        Ok(())
    }
}

fn validate_repository(value: &str) -> Result<(), JobError> {
    if value.is_empty()
        || value.len() > MAX_REPOSITORY_BYTES
        || !value
            .bytes()
            .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-')
    {
        return Err(JobError::InvalidRepository);
    }
    Ok(())
}

fn validate_package(value: &str) -> Result<(), JobError> {
    if value.is_empty()
        || value.len() > MAX_PACKAGE_BYTES
        || !value.bytes().all(|byte| {
            byte.is_ascii_alphanumeric() || matches!(byte, b'@' | b'.' | b'_' | b'+' | b':' | b'-')
        })
    {
        return Err(JobError::InvalidPackage);
    }
    Ok(())
}

fn encode_jobs(jobs: &PackageJobs, output: &mut [u8; JOB_FILE_SIZE]) {
    output[..8].copy_from_slice(MAGIC);
    output[8..12].copy_from_slice(&FORMAT_VERSION.to_le_bytes());
    output[12..20].copy_from_slice(&jobs.next_id.to_le_bytes());
    output[20..24].copy_from_slice(&(MAX_JOBS as u32).to_le_bytes());
    for (index, slot) in jobs.slots.iter().enumerate() {
        encode_slot(
            slot,
            &mut output[HEADER_SIZE + index * SLOT_SIZE..][..SLOT_SIZE],
        );
    }
    let checksum = checksum(output);
    output[24..28].copy_from_slice(&checksum.to_le_bytes());
}

fn encode_slot(slot: &JobSlot, output: &mut [u8]) {
    if !slot.occupied {
        return;
    }
    output[0] = 1;
    output[1] = slot.job.operation as u8;
    output[2] = slot.job.state as u8;
    output[3] = slot.job.phase;
    output[4] = slot.job.progress;
    output[8..16].copy_from_slice(&slot.job.id.to_le_bytes());
    output[16..24].copy_from_slice(&slot.job.updated_millis.to_le_bytes());
    output[24..26].copy_from_slice(&slot.job.repository.length.to_le_bytes());
    output[26..28].copy_from_slice(&slot.job.package.length.to_le_bytes());
    output[28..30].copy_from_slice(&slot.job.message.length.to_le_bytes());
    output[32..48].copy_from_slice(&slot.job.repository.bytes);
    output[48..176].copy_from_slice(&slot.job.package.bytes);
    output[176..368].copy_from_slice(&slot.job.message.bytes);
}

fn load_jobs(path: &Path) -> Result<PackageJobs, JobError> {
    let metadata = fs::symlink_metadata(path)?;
    if metadata.file_type().is_symlink()
        || !metadata.is_file()
        || metadata.len() != JOB_FILE_SIZE as u64
    {
        return Err(JobError::CorruptStore);
    }
    let mut bytes = [0_u8; JOB_FILE_SIZE];
    File::open(path)?.read_exact(&mut bytes)?;
    if metadata.permissions().mode() & 0o7777 != 0o600 {
        fs::set_permissions(path, fs::Permissions::from_mode(0o600))?;
    }
    if &bytes[..8] != MAGIC
        || u32::from_le_bytes(bytes[8..12].try_into().expect("version bytes")) != FORMAT_VERSION
        || u32::from_le_bytes(bytes[20..24].try_into().expect("slot count bytes"))
            != MAX_JOBS as u32
        || bytes[28..32].iter().any(|byte| *byte != 0)
        || u32::from_le_bytes(bytes[24..28].try_into().expect("checksum bytes")) != checksum(&bytes)
    {
        return Err(JobError::CorruptStore);
    }
    let next_id = u64::from_le_bytes(bytes[12..20].try_into().expect("next id bytes"));
    if next_id == 0 {
        return Err(JobError::CorruptStore);
    }
    let mut jobs = PackageJobs::new();
    jobs.next_id = next_id;
    for index in 0..MAX_JOBS {
        jobs.slots[index] = decode_slot(&bytes[HEADER_SIZE + index * SLOT_SIZE..][..SLOT_SIZE])?;
    }
    for (index, slot) in jobs.slots.iter().enumerate() {
        if !slot.occupied {
            continue;
        }
        if slot.job.id >= next_id
            || jobs.slots[..index]
                .iter()
                .any(|previous| previous.occupied && previous.job.id == slot.job.id)
            || jobs.slots[..index].iter().any(|previous| {
                previous.occupied
                    && previous.job.state.is_active()
                    && slot.job.state.is_active()
                    && previous.job.operation == slot.job.operation
                    && previous.job.repository == slot.job.repository
                    && previous.job.package == slot.job.package
            })
        {
            return Err(JobError::CorruptStore);
        }
    }
    Ok(jobs)
}

fn decode_slot(bytes: &[u8]) -> Result<JobSlot, JobError> {
    if bytes[0] == 0 {
        if bytes.iter().any(|byte| *byte != 0) {
            return Err(JobError::CorruptStore);
        }
        return Ok(JobSlot::empty());
    }
    if bytes[0] != 1
        || bytes[5..8].iter().any(|byte| *byte != 0)
        || bytes[30..32].iter().any(|byte| *byte != 0)
    {
        return Err(JobError::CorruptStore);
    }
    let operation = JobOperation::from_raw(bytes[1]).ok_or(JobError::CorruptStore)?;
    let state = JobState::from_raw(bytes[2]).ok_or(JobError::CorruptStore)?;
    let progress = bytes[4];
    let message_length = u16::from_le_bytes(bytes[28..30].try_into().expect("message length"));
    let message = BoundedText::decode(&bytes[176..368], message_length)?;
    if progress > 100
        || message.as_str().is_empty()
        || message
            .as_str()
            .bytes()
            .any(|byte| matches!(byte, b'\0' | b'\t' | b'\r' | b'\n'))
    {
        return Err(JobError::CorruptStore);
    }
    let id = u64::from_le_bytes(bytes[8..16].try_into().expect("job id bytes"));
    if id == 0 {
        return Err(JobError::CorruptStore);
    }
    Ok(JobSlot {
        occupied: true,
        job: PackageJob {
            id,
            operation,
            state,
            phase: bytes[3],
            progress,
            updated_millis: u64::from_le_bytes(bytes[16..24].try_into().expect("updated bytes")),
            repository: BoundedText::decode(
                &bytes[32..48],
                u16::from_le_bytes(bytes[24..26].try_into().expect("repository length")),
            )?,
            package: BoundedText::decode(
                &bytes[48..176],
                u16::from_le_bytes(bytes[26..28].try_into().expect("package length")),
            )?,
            message,
        },
    })
}

fn checksum(bytes: &[u8; JOB_FILE_SIZE]) -> u32 {
    let mut value = 0xffff_ffff_u32;
    for byte in bytes[..24].iter().chain(bytes[28..].iter()) {
        value ^= u32::from(*byte);
        for _ in 0..8 {
            value = (value >> 1) ^ (0xedb8_8320_u32 & (0_u32.wrapping_sub(value & 1)));
        }
    }
    !value
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};

    static TEST_ID: AtomicU64 = AtomicU64::new(1);

    struct TestRoot(PathBuf);

    impl TestRoot {
        fn new() -> Self {
            let id = TEST_ID.fetch_add(1, Ordering::Relaxed);
            let path = std::env::temp_dir()
                .join(format!("archphene-jobs-test-{}-{id}", std::process::id()));
            fs::create_dir_all(path.join("var/lib/archphene")).expect("test job root");
            Self(path)
        }
    }

    impl Drop for TestRoot {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn jobs_round_trip_and_interrupted_work_becomes_retryable() {
        let root = TestRoot::new();
        let (mut store, recovered) = PackageJobStore::open(&root.0, 1).expect("new store");
        assert_eq!(recovered, 0);
        assert_eq!(
            fs::metadata(root.0.join(JOB_FILE))
                .expect("job metadata")
                .permissions()
                .mode()
                & 0o7777,
            0o600
        );
        let queued = store
            .begin(JobOperation::Install, "extra", "dotnet-sdk", 2)
            .expect("queue job");
        store
            .update(
                queued.id,
                JobState::Resolving,
                1,
                10,
                "Resolving dependencies",
                3,
            )
            .expect("update job");
        drop(store);

        fs::set_permissions(root.0.join(JOB_FILE), fs::Permissions::from_mode(0o644))
            .expect("weaken job mode");
        let (store, recovered) = PackageJobStore::open(&root.0, 4).expect("recover store");
        assert_eq!(recovered, 1);
        assert_eq!(
            fs::metadata(root.0.join(JOB_FILE))
                .expect("repaired job metadata")
                .permissions()
                .mode()
                & 0o7777,
            0o600
        );
        let job = store.jobs().get(queued.id).expect("recovered job");
        assert_eq!(job.state, JobState::Failed);
        assert_eq!(job.progress, 10);
        assert_eq!(job.message.as_str(), "Interrupted; retry is required");
    }

    #[test]
    fn duplicate_active_jobs_and_invalid_transitions_are_rejected() {
        let mut jobs = PackageJobs::new();
        let job = jobs
            .begin(JobOperation::Install, "extra", "kate", 1)
            .expect("first job");
        assert!(matches!(
            jobs.begin(JobOperation::Install, "extra", "kate", 2),
            Err(JobError::DuplicateActiveJob)
        ));
        assert!(matches!(
            jobs.update(job.id, JobState::Complete, 0, 100, "Done", 3),
            Err(JobError::InvalidTransition)
        ));
    }

    #[test]
    fn active_jobs_are_bounded_and_terminal_slots_are_recycled() {
        let mut jobs = PackageJobs::new();
        for index in 0..MAX_JOBS {
            let mut name = [0_u8; 16];
            let value = format!("package-{index}");
            name[..value.len()].copy_from_slice(value.as_bytes());
            jobs.begin(
                JobOperation::Install,
                "extra",
                std::str::from_utf8(&name[..value.len()]).expect("package name"),
                index as u64,
            )
            .expect("bounded active job");
        }
        assert!(matches!(
            jobs.begin(JobOperation::Install, "extra", "one-more", 100),
            Err(JobError::StoreFull)
        ));
        let first_id = jobs.slots[0].job.id;
        jobs.update(first_id, JobState::Failed, 1, 10, "failed", 101)
            .expect("fail first job");
        assert!(
            jobs.begin(JobOperation::Install, "extra", "replacement", 102)
                .is_ok()
        );
    }

    #[test]
    fn corrupt_or_symlinked_stores_are_rejected() {
        let root = TestRoot::new();
        PackageJobStore::open(&root.0, 1).expect("new store");
        let path = root.0.join(JOB_FILE);
        let mut bytes = fs::read(&path).expect("job bytes");
        bytes[100] ^= 0x80;
        fs::write(&path, bytes).expect("corrupt job bytes");
        assert!(matches!(
            PackageJobStore::open(&root.0, 2),
            Err(JobError::CorruptStore)
        ));

        fs::remove_file(&path).expect("remove corrupt store");
        std::os::unix::fs::symlink("/tmp", &path).expect("job symlink");
        assert!(matches!(
            PackageJobStore::open(&root.0, 3),
            Err(JobError::CorruptStore)
        ));
    }

    #[test]
    fn package_and_repository_names_are_strictly_bounded() {
        let mut jobs = PackageJobs::new();
        assert!(matches!(
            jobs.begin(JobOperation::Install, "../extra", "kate", 1),
            Err(JobError::InvalidRepository)
        ));
        assert!(matches!(
            jobs.begin(JobOperation::Install, "extra", "../kate", 1),
            Err(JobError::InvalidPackage)
        ));
        let job = jobs
            .begin(JobOperation::Install, "extra", "kate", 2)
            .expect("valid job");
        assert_eq!(jobs.latest().expect("latest job").id, job.id);
        assert!(matches!(
            jobs.update(job.id, JobState::Resolving, 1, 1, "unsafe\nmessage", 3,),
            Err(JobError::InvalidMessage)
        ));
    }
}
