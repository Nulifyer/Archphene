#![deny(unsafe_code)]
#![deny(unsafe_op_in_unsafe_fn)]

use std::collections::BTreeSet;
use std::ffi::{CString, OsStr};
use std::fmt;
use std::fs::{self, File};
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::os::fd::{AsRawFd, RawFd};
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::MetadataExt;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use sha2::{Digest, Sha256};

mod sys {
    #![allow(unsafe_code)]

    use std::ffi::CStr;
    use std::fs::File;
    use std::io;
    use std::os::fd::{FromRawFd, RawFd};
    use std::os::raw::{c_char, c_int, c_long, c_short, c_uint, c_ulong};

    pub const O_RDONLY: c_int = 0;
    pub const O_WRONLY: c_int = 1;
    pub const O_RDWR: c_int = 2;
    pub const O_CREAT: c_int = 0o100;
    pub const O_EXCL: c_int = 0o200;
    pub const O_TRUNC: c_int = 0o1000;
    pub const O_APPEND: c_int = 0o2000;
    pub const O_NONBLOCK: c_int = 0o4000;
    pub const O_PATH: c_int = 0o10000000;
    pub const O_CLOEXEC: c_int = 0o2000000;
    pub const O_DIRECTORY: c_int = 0o200000;
    pub const O_NOFOLLOW: c_int = 0o400000;
    pub const AT_REMOVEDIR: c_int = 0x200;
    const F_DUPFD_CLOEXEC: c_int = 1030;
    const RENAME_NOREPLACE: c_uint = 1;
    const POLLIN: c_short = 0x0001;
    const POLLERR: c_short = 0x0008;
    const POLLHUP: c_short = 0x0010;
    const POLLNVAL: c_short = 0x0020;

    #[repr(C)]
    struct PollDescriptor {
        descriptor: c_int,
        events: c_short,
        returned_events: c_short,
    }

    #[cfg(target_arch = "x86_64")]
    const SYS_RENAMEAT2: c_long = 316;
    #[cfg(target_arch = "aarch64")]
    const SYS_RENAMEAT2: c_long = 276;

    unsafe extern "C" {
        fn open(path: *const c_char, flags: c_int, mode: c_uint) -> c_int;
        fn openat(directory: c_int, path: *const c_char, flags: c_int, mode: c_uint) -> c_int;
        fn mkdirat(directory: c_int, path: *const c_char, mode: c_uint) -> c_int;
        fn unlinkat(directory: c_int, path: *const c_char, flags: c_int) -> c_int;
        fn renameat(
            source_directory: c_int,
            old_name: *const c_char,
            destination_directory: c_int,
            new_name: *const c_char,
        ) -> c_int;
        fn fcntl(descriptor: c_int, command: c_int, ...) -> c_int;
        fn poll(descriptors: *mut PollDescriptor, count: c_ulong, timeout: c_int) -> c_int;
        fn syscall(number: c_long, ...) -> c_long;
    }

    fn descriptor(result: c_int) -> io::Result<RawFd> {
        if result < 0 {
            Err(io::Error::last_os_error())
        } else {
            Ok(result)
        }
    }

    pub fn open_root(path: &CStr) -> io::Result<File> {
        // SAFETY: `path` is NUL-terminated and remains live for the call.
        let descriptor = descriptor(unsafe {
            open(
                path.as_ptr(),
                O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW,
                0,
            )
        })?;
        // SAFETY: `open` returned a new owned descriptor.
        Ok(unsafe { File::from_raw_fd(descriptor) })
    }

    pub fn open_at(directory: RawFd, name: &CStr, flags: c_int, mode: c_uint) -> io::Result<File> {
        // SAFETY: `name` is NUL-terminated, and the borrowed directory
        // descriptor and string remain live for the call.
        let descriptor = descriptor(unsafe { openat(directory, name.as_ptr(), flags, mode) })?;
        // SAFETY: `openat` returned a new owned descriptor.
        Ok(unsafe { File::from_raw_fd(descriptor) })
    }

    pub fn mkdir_at(directory: RawFd, name: &CStr, mode: c_uint) -> io::Result<()> {
        // SAFETY: `name` is NUL-terminated, and both arguments remain live.
        if unsafe { mkdirat(directory, name.as_ptr(), mode) } == 0 {
            Ok(())
        } else {
            Err(io::Error::last_os_error())
        }
    }

    pub fn unlink_at(directory: RawFd, name: &CStr, directory_entry: bool) -> io::Result<()> {
        // SAFETY: `name` is NUL-terminated, and both arguments remain live.
        if unsafe {
            unlinkat(
                directory,
                name.as_ptr(),
                if directory_entry { AT_REMOVEDIR } else { 0 },
            )
        } == 0
        {
            Ok(())
        } else {
            Err(io::Error::last_os_error())
        }
    }

    pub fn duplicate(source_descriptor: RawFd) -> io::Result<File> {
        // SAFETY: `source_descriptor` is only borrowed. F_DUPFD_CLOEXEC returns a new
        // independently owned descriptor or a negative error result.
        let duplicate = descriptor(unsafe { fcntl(source_descriptor, F_DUPFD_CLOEXEC, 0) })?;
        // SAFETY: `fcntl` returned a new owned descriptor.
        Ok(unsafe { File::from_raw_fd(duplicate) })
    }

    pub fn wait_readable(descriptor: RawFd, timeout_millis: c_int) -> io::Result<bool> {
        let mut poll_descriptor = PollDescriptor {
            descriptor,
            events: POLLIN,
            returned_events: 0,
        };
        // SAFETY: `poll_descriptor` is a valid single-element writable array
        // for the duration of the call, and the descriptor is only borrowed.
        let result = unsafe { poll(&mut poll_descriptor, 1, timeout_millis) };
        if result < 0 {
            return Err(io::Error::last_os_error());
        }
        if result == 0 {
            return Ok(false);
        }
        if poll_descriptor.returned_events & POLLNVAL != 0 {
            return Err(io::Error::from_raw_os_error(9));
        }
        Ok(poll_descriptor.returned_events & (POLLIN | POLLERR | POLLHUP) != 0)
    }

    pub fn rename_noreplace_between(
        source_directory: RawFd,
        old_name: &CStr,
        destination_directory: RawFd,
        new_name: &CStr,
    ) -> io::Result<()> {
        // SAFETY: both names are NUL-terminated and remain live. The syscall
        // receives valid borrowed directory descriptors for source and target.
        let result = unsafe {
            syscall(
                SYS_RENAMEAT2,
                source_directory,
                old_name.as_ptr(),
                destination_directory,
                new_name.as_ptr(),
                RENAME_NOREPLACE,
            )
        };
        if result == 0 {
            Ok(())
        } else {
            Err(io::Error::last_os_error())
        }
    }

    pub fn rename_replace_between(
        source_directory: RawFd,
        old_name: &CStr,
        destination_directory: RawFd,
        new_name: &CStr,
    ) -> io::Result<()> {
        // SAFETY: both names are NUL-terminated and remain live. The call
        // receives valid borrowed directory descriptors for source and target.
        if unsafe {
            renameat(
                source_directory,
                old_name.as_ptr(),
                destination_directory,
                new_name.as_ptr(),
            )
        } == 0
        {
            Ok(())
        } else {
            Err(io::Error::last_os_error())
        }
    }
}

pub const HOME_DOCUMENT_ID: &str = "home";
pub const BASHRC_STARTUP_ID: &str = "bashrc";
pub const BASH_PROFILE_STARTUP_ID: &str = "bash-profile";
pub const ZSHRC_STARTUP_ID: &str = "zshrc";
pub const FISH_CONFIG_STARTUP_ID: &str = "fish-config";
pub const MAX_DOCUMENT_ID_BYTES: usize = 1024;
pub const MAX_DOCUMENT_DEPTH: usize = 32;
pub const MAX_DOCUMENT_NAME_BYTES: usize = 255;
pub const MAX_DOCUMENT_TRANSFER_BYTES: u64 = 16 * 1024 * 1024 * 1024;
pub const MAX_MIRROR_ENTRIES: u32 = 10_000;
pub const MAX_MIRROR_DEPTH: usize = 64;
pub const MAX_MIRROR_PATH_BYTES: usize = 4 * 1024;
pub const MAX_MIRROR_FILE_BYTES: u64 = 2 * 1024 * 1024 * 1024;
pub const MAX_MIRROR_TOTAL_BYTES: u64 = 16 * 1024 * 1024 * 1024;
pub const MAX_SYNC_MANIFEST_BYTES: usize = 4 * 1024 * 1024;
pub const MAX_STORAGE_USAGE_ENTRIES: u64 = 2_000_000;
pub const MAX_STORAGE_USAGE_DEPTH: usize = 64;

const IMPORT_STAGING_DIRECTORY: &str = ".archphene-import";
const IMPORT_STAGING_FILE: &str = "pending";
const MAX_IMPORT_COLLISIONS: u32 = 999;
const MIRROR_STAGING_DIRECTORY: &str = ".archphene-mirror-pending";
const SYNC_MANIFEST_MAGIC: &[u8; 8] = b"ARCSYNC1";
const PORTAL_FOLDER_MAGIC: &[u8; 8] = b"ARCFOLD1";
const PORTAL_FOLDER_END: u8 = 0;
const PORTAL_FOLDER_DIRECTORY: u8 = 1;
const PORTAL_FOLDER_FILE: u8 = 2;
const PORTAL_FOLDER_DATA: u8 = 3;
const PORTAL_FOLDER_FILE_END: u8 = 4;
const MAX_PORTAL_FOLDER_CHUNK_BYTES: usize = 64 * 1024;
const SYNC_MANIFEST_VERSION: u32 = 1;
const SYNC_MANIFEST_HEADER_BYTES: usize = 36;
const SYNC_MANIFEST_ENTRY_HEADER_BYTES: usize = 44;
const SYNC_STATE_DIRECTORY: &[&str] = &["var", "lib", "archphene", "storage"];

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct OpenMode {
    pub read: bool,
    pub write: bool,
    pub truncate: bool,
    pub append: bool,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct AllocatedStorageUsage {
    pub entries: u64,
    pub bytes: u64,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct ArchStorageUsage {
    pub package_downloads: AllocatedStorageUsage,
    pub shared_runtime: AllocatedStorageUsage,
    pub build_cache: AllocatedStorageUsage,
    pub user_files: AllocatedStorageUsage,
}

impl ArchStorageUsage {
    pub fn total_entries(self) -> Option<u64> {
        self.package_downloads
            .entries
            .checked_add(self.shared_runtime.entries)?
            .checked_add(self.build_cache.entries)?
            .checked_add(self.user_files.entries)
    }

    pub fn total_bytes(self) -> Option<u64> {
        self.package_downloads
            .bytes
            .checked_add(self.shared_runtime.bytes)?
            .checked_add(self.build_cache.bytes)?
            .checked_add(self.user_files.bytes)
    }
}

#[derive(Debug)]
pub enum StorageError {
    InvalidRoot,
    InvalidDocument,
    HiddenDocument,
    RootMutation,
    DocumentTooLarge,
    TransferCancelled,
    ProviderTimeout,
    ImportCollision,
    MirrorBusy,
    MirrorExists,
    MirrorTooLarge,
    MirrorCancelled,
    InvalidManifest,
    ManifestTooLarge,
    SyncChanged,
    SyncConflictLimit,
    UsageTooLarge,
    Io(io::Error),
}

impl fmt::Display for StorageError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidRoot => formatter.write_str("invalid Archphene home root"),
            Self::InvalidDocument => formatter.write_str("invalid Archphene document"),
            Self::HiddenDocument => formatter.write_str("private Archphene document"),
            Self::RootMutation => formatter.write_str("cannot mutate Archphene home"),
            Self::DocumentTooLarge => formatter.write_str("document exceeds 16 GiB"),
            Self::TransferCancelled => formatter.write_str("document transfer was cancelled"),
            Self::ProviderTimeout => {
                formatter.write_str("Android provider stopped sending document data")
            }
            Self::ImportCollision => {
                formatter.write_str("too many documents use this imported name")
            }
            Self::MirrorBusy => formatter.write_str("another project mirror is incomplete"),
            Self::MirrorExists => formatter.write_str("the Linux project path already exists"),
            Self::MirrorTooLarge => formatter.write_str("Android project mirror exceeds its limit"),
            Self::MirrorCancelled => formatter.write_str("Android project mirror was cancelled"),
            Self::InvalidManifest => formatter.write_str("project sync manifest is invalid"),
            Self::ManifestTooLarge => {
                formatter.write_str("project sync manifest exceeds its limit")
            }
            Self::SyncChanged => {
                formatter.write_str("project changed while synchronization was running")
            }
            Self::SyncConflictLimit => {
                formatter.write_str("could not allocate a project conflict copy")
            }
            Self::UsageTooLarge => formatter.write_str("storage inventory exceeds its limit"),
            Self::Io(error) => write!(formatter, "Archphene document I/O error: {error}"),
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum StorageUsageClass {
    SharedRuntime,
    Home,
    UserFiles,
    Var,
    VarCache,
    PacmanCache,
    PackageDownloads,
    ArchpheneCache,
    BuildCache,
}

impl StorageUsageClass {
    fn child(self, name: &[u8]) -> Self {
        match self {
            Self::SharedRuntime if name == b"home" => Self::Home,
            Self::SharedRuntime if name == b"var" => Self::Var,
            Self::Home if name == b"archphene" => Self::UserFiles,
            Self::Var if name == b"cache" => Self::VarCache,
            Self::VarCache if name == b"pacman" => Self::PacmanCache,
            Self::VarCache if name == b"archphene" => Self::ArchpheneCache,
            Self::PacmanCache if name == b"pkg" => Self::PackageDownloads,
            Self::ArchpheneCache
                if matches!(name, b"aur-packages" | b"aur-snapshots" | b"aur-sources") =>
            {
                Self::BuildCache
            }
            Self::UserFiles => Self::UserFiles,
            Self::PackageDownloads => Self::PackageDownloads,
            Self::BuildCache => Self::BuildCache,
            _ => Self::SharedRuntime,
        }
    }

    fn usage_mut(self, usage: &mut ArchStorageUsage) -> &mut AllocatedStorageUsage {
        match self {
            Self::UserFiles => &mut usage.user_files,
            Self::PackageDownloads => &mut usage.package_downloads,
            Self::BuildCache => &mut usage.build_cache,
            _ => &mut usage.shared_runtime,
        }
    }
}

pub fn arch_storage_usage(root: &Path) -> Result<ArchStorageUsage, StorageError> {
    let root = open_directory(root, &[])?;
    let mut usage = ArchStorageUsage::default();
    let mut hard_links = BTreeSet::new();
    inventory_allocated_directory(
        &root,
        StorageUsageClass::SharedRuntime,
        0,
        &mut usage,
        &mut hard_links,
    )?;
    usage
        .total_entries()
        .filter(|entries| *entries <= MAX_STORAGE_USAGE_ENTRIES)
        .ok_or(StorageError::UsageTooLarge)?;
    usage.total_bytes().ok_or(StorageError::UsageTooLarge)?;
    Ok(usage)
}

pub fn allocated_storage_usage(root: &Path) -> Result<AllocatedStorageUsage, StorageError> {
    let usage = arch_storage_usage(root)?;
    Ok(AllocatedStorageUsage {
        entries: usage.total_entries().ok_or(StorageError::UsageTooLarge)?,
        bytes: usage.total_bytes().ok_or(StorageError::UsageTooLarge)?,
    })
}

fn inventory_allocated_directory(
    directory: &File,
    class: StorageUsageClass,
    depth: usize,
    usage: &mut ArchStorageUsage,
    hard_links: &mut BTreeSet<(u64, u64)>,
) -> Result<(), StorageError> {
    if depth > MAX_STORAGE_USAGE_DEPTH {
        return Err(StorageError::UsageTooLarge);
    }
    let descriptor_path = format!("/proc/self/fd/{}", directory.as_raw_fd());
    for child in fs::read_dir(descriptor_path)? {
        let child = match child {
            Ok(child) => child,
            Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
            Err(error) => return Err(error.into()),
        };
        let name = child.file_name();
        let name_bytes = name.as_bytes();
        if name_bytes.is_empty() || name_bytes.len() > 255 || name_bytes.contains(&b'/') {
            return Err(StorageError::InvalidDocument);
        }
        let child_class = class.child(name_bytes);
        let entry_class = match child_class {
            StorageUsageClass::UserFiles
            | StorageUsageClass::PackageDownloads
            | StorageUsageClass::BuildCache
                if child_class != class =>
            {
                StorageUsageClass::SharedRuntime
            }
            _ => child_class,
        };
        let name = c_string(&name)?;
        let entry = match sys::open_at(
            directory.as_raw_fd(),
            &name,
            sys::O_PATH | sys::O_CLOEXEC | sys::O_NOFOLLOW,
            0,
        ) {
            Ok(entry) => entry,
            Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
            Err(error) => return Err(error.into()),
        };
        let metadata = entry.metadata()?;
        let allocated = metadata
            .blocks()
            .checked_mul(512)
            .ok_or(StorageError::UsageTooLarge)?;
        let count_bytes = metadata.is_dir()
            || metadata.nlink() <= 1
            || hard_links.insert((metadata.dev(), metadata.ino()));
        {
            let target = entry_class.usage_mut(usage);
            target.entries = target
                .entries
                .checked_add(1)
                .ok_or(StorageError::UsageTooLarge)?;
            if count_bytes {
                target.bytes = target
                    .bytes
                    .checked_add(allocated)
                    .ok_or(StorageError::UsageTooLarge)?;
            }
        }
        if usage
            .total_entries()
            .filter(|entries| *entries <= MAX_STORAGE_USAGE_ENTRIES)
            .is_none()
        {
            return Err(StorageError::UsageTooLarge);
        }
        if metadata.is_dir() {
            if depth == MAX_STORAGE_USAGE_DEPTH {
                return Err(StorageError::UsageTooLarge);
            }
            let child_directory = match sys::open_at(
                directory.as_raw_fd(),
                &name,
                sys::O_RDONLY | sys::O_DIRECTORY | sys::O_CLOEXEC | sys::O_NOFOLLOW,
                0,
            ) {
                Ok(directory) => directory,
                Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
                Err(error) => return Err(error.into()),
            };
            inventory_allocated_directory(
                &child_directory,
                child_class,
                depth + 1,
                usage,
                hard_links,
            )?;
        }
    }
    Ok(())
}

pub struct MirrorImport {
    staging_path: PathBuf,
    projects: File,
    staging: File,
    target_name: CString,
    sync_baseline: Option<MirrorSyncBaseline>,
    sync_entries: Vec<SyncManifestEntry>,
    entries: u32,
    bytes: u64,
    published: bool,
    cancellation: MirrorCancellation,
}

struct MirrorSyncBaseline {
    arch_root: PathBuf,
    mapping_id: [u8; 16],
    project_name: String,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct MirrorImportReport {
    pub entries: u32,
    pub bytes: u64,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PortalFolderImportReport {
    pub display_name: String,
    pub entries: u32,
    pub bytes: u64,
}

#[derive(Clone)]
pub struct MirrorCancellation(Arc<AtomicBool>);

impl MirrorCancellation {
    pub fn new() -> Self {
        Self(Arc::new(AtomicBool::new(false)))
    }

    pub fn cancel(&self) {
        self.0.store(true, Ordering::Release);
    }

    pub fn check(&self) -> Result<(), StorageError> {
        if self.0.load(Ordering::Acquire) {
            Err(StorageError::MirrorCancelled)
        } else {
            Ok(())
        }
    }

    fn is_cancelled(&self) -> bool {
        self.0.load(Ordering::Acquire)
    }
}

impl Default for MirrorCancellation {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum SyncEntryKind {
    Directory,
    File,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct SyncFingerprint {
    pub kind: SyncEntryKind,
    pub bytes: u64,
    pub sha256: [u8; 32],
}

impl SyncFingerprint {
    pub const fn directory() -> Self {
        Self {
            kind: SyncEntryKind::Directory,
            bytes: 0,
            sha256: [0; 32],
        }
    }

    pub const fn file(bytes: u64, sha256: [u8; 32]) -> Self {
        Self {
            kind: SyncEntryKind::File,
            bytes,
            sha256,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum SyncAction {
    Converged,
    PushToAndroid,
    PullToLinux,
    DeleteFromAndroid,
    DeleteFromLinux,
    PreserveConflict,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SyncPlanEntry {
    pub path: String,
    pub baseline: Option<SyncFingerprint>,
    pub linux: Option<SyncFingerprint>,
    pub android: Option<SyncFingerprint>,
    pub action: SyncAction,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct SyncPlanSummary {
    pub converged: u32,
    pub push_to_android: u32,
    pub pull_to_linux: u32,
    pub delete_from_android: u32,
    pub delete_from_linux: u32,
    pub conflicts: u32,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SyncPlan {
    mapping_id: [u8; 16],
    project_name: String,
    entries: Vec<SyncPlanEntry>,
    summary: SyncPlanSummary,
}

impl SyncPlan {
    pub fn mapping_id(&self) -> [u8; 16] {
        self.mapping_id
    }

    pub fn project_name(&self) -> &str {
        &self.project_name
    }

    pub fn entries(&self) -> &[SyncPlanEntry] {
        &self.entries
    }

    pub fn summary(&self) -> SyncPlanSummary {
        self.summary
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SyncManifestEntry {
    pub path: String,
    pub fingerprint: SyncFingerprint,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SyncManifest {
    mapping_id: [u8; 16],
    project_name: String,
    entries: Vec<SyncManifestEntry>,
}

impl SyncManifest {
    pub fn new(
        mapping_id: [u8; 16],
        project_name: String,
        mut entries: Vec<SyncManifestEntry>,
    ) -> Result<Self, StorageError> {
        if mapping_id == [0; 16] {
            return Err(StorageError::InvalidManifest);
        }
        validate_visible_name(&project_name).map_err(|_| StorageError::InvalidManifest)?;
        entries.sort_unstable_by(|left, right| left.path.as_bytes().cmp(right.path.as_bytes()));
        validate_manifest_entries(&entries)?;
        Ok(Self {
            mapping_id,
            project_name,
            entries,
        })
    }

    pub fn mapping_id(&self) -> [u8; 16] {
        self.mapping_id
    }

    pub fn project_name(&self) -> &str {
        &self.project_name
    }

    pub fn entries(&self) -> &[SyncManifestEntry] {
        &self.entries
    }

    pub fn encode(&self) -> Result<Vec<u8>, StorageError> {
        validate_visible_name(&self.project_name).map_err(|_| StorageError::InvalidManifest)?;
        validate_manifest_entries(&self.entries)?;
        let project_bytes = self.project_name.as_bytes();
        let project_length =
            u16::try_from(project_bytes.len()).map_err(|_| StorageError::InvalidManifest)?;
        let entry_count =
            u32::try_from(self.entries.len()).map_err(|_| StorageError::ManifestTooLarge)?;
        let mut length = SYNC_MANIFEST_HEADER_BYTES
            .checked_add(project_bytes.len())
            .ok_or(StorageError::ManifestTooLarge)?;
        for entry in &self.entries {
            length = length
                .checked_add(SYNC_MANIFEST_ENTRY_HEADER_BYTES)
                .and_then(|value| value.checked_add(entry.path.len()))
                .ok_or(StorageError::ManifestTooLarge)?;
        }
        if length > MAX_SYNC_MANIFEST_BYTES {
            return Err(StorageError::ManifestTooLarge);
        }

        let mut output = Vec::with_capacity(length);
        output.extend_from_slice(SYNC_MANIFEST_MAGIC);
        output.extend_from_slice(&SYNC_MANIFEST_VERSION.to_le_bytes());
        output.extend_from_slice(&entry_count.to_le_bytes());
        output.extend_from_slice(&self.mapping_id);
        output.extend_from_slice(&project_length.to_le_bytes());
        output.extend_from_slice(&0_u16.to_le_bytes());
        output.extend_from_slice(project_bytes);
        for entry in &self.entries {
            let path_length =
                u16::try_from(entry.path.len()).map_err(|_| StorageError::InvalidManifest)?;
            output.extend_from_slice(&path_length.to_le_bytes());
            output.push(match entry.fingerprint.kind {
                SyncEntryKind::Directory => 1,
                SyncEntryKind::File => 2,
            });
            output.push(0);
            output.extend_from_slice(&entry.fingerprint.bytes.to_le_bytes());
            output.extend_from_slice(&entry.fingerprint.sha256);
            output.extend_from_slice(entry.path.as_bytes());
        }
        debug_assert_eq!(output.len(), length);
        Ok(output)
    }

    pub fn decode(input: &[u8]) -> Result<Self, StorageError> {
        if input.len() < SYNC_MANIFEST_HEADER_BYTES || input.len() > MAX_SYNC_MANIFEST_BYTES {
            return Err(StorageError::InvalidManifest);
        }
        let mut cursor = 0;
        if take_manifest_bytes(input, &mut cursor, 8)? != SYNC_MANIFEST_MAGIC {
            return Err(StorageError::InvalidManifest);
        }
        if take_manifest_u32(input, &mut cursor)? != SYNC_MANIFEST_VERSION {
            return Err(StorageError::InvalidManifest);
        }
        let entry_count = take_manifest_u32(input, &mut cursor)? as usize;
        if entry_count > MAX_MIRROR_ENTRIES as usize {
            return Err(StorageError::ManifestTooLarge);
        }
        let mut mapping_id = [0_u8; 16];
        mapping_id.copy_from_slice(take_manifest_bytes(input, &mut cursor, 16)?);
        if mapping_id == [0; 16] {
            return Err(StorageError::InvalidManifest);
        }
        let project_length = take_manifest_u16(input, &mut cursor)? as usize;
        if take_manifest_u16(input, &mut cursor)? != 0 {
            return Err(StorageError::InvalidManifest);
        }
        let project_name =
            std::str::from_utf8(take_manifest_bytes(input, &mut cursor, project_length)?)
                .map_err(|_| StorageError::InvalidManifest)?
                .to_owned();
        validate_visible_name(&project_name).map_err(|_| StorageError::InvalidManifest)?;

        let mut entries = Vec::with_capacity(entry_count);
        for _ in 0..entry_count {
            let path_length = take_manifest_u16(input, &mut cursor)? as usize;
            let kind = match take_manifest_bytes(input, &mut cursor, 1)?[0] {
                1 => SyncEntryKind::Directory,
                2 => SyncEntryKind::File,
                _ => return Err(StorageError::InvalidManifest),
            };
            if take_manifest_bytes(input, &mut cursor, 1)?[0] != 0 {
                return Err(StorageError::InvalidManifest);
            }
            let bytes = take_manifest_u64(input, &mut cursor)?;
            let mut sha256 = [0_u8; 32];
            sha256.copy_from_slice(take_manifest_bytes(input, &mut cursor, 32)?);
            let path = std::str::from_utf8(take_manifest_bytes(input, &mut cursor, path_length)?)
                .map_err(|_| StorageError::InvalidManifest)?
                .to_owned();
            entries.push(SyncManifestEntry {
                path,
                fingerprint: SyncFingerprint {
                    kind,
                    bytes,
                    sha256,
                },
            });
        }
        if cursor != input.len() {
            return Err(StorageError::InvalidManifest);
        }
        validate_manifest_entries(&entries)?;
        Ok(Self {
            mapping_id,
            project_name,
            entries,
        })
    }
}

pub fn persist_sync_manifest(
    arch_root: &Path,
    manifest: &SyncManifest,
) -> Result<(), StorageError> {
    let encoded = manifest.encode()?;
    let directory = open_directory(arch_root, SYNC_STATE_DIRECTORY)?;
    let (final_name, temporary_name) = manifest_file_names(manifest.mapping_id)?;
    validate_manifest_state_entry(&directory, &final_name)?;
    if validate_manifest_state_entry(&directory, &temporary_name)? {
        sys::unlink_at(directory.as_raw_fd(), &temporary_name, false)?;
        directory.sync_all()?;
    }
    let mut temporary = sys::open_at(
        directory.as_raw_fd(),
        &temporary_name,
        sys::O_WRONLY | sys::O_CREAT | sys::O_EXCL | sys::O_CLOEXEC | sys::O_NOFOLLOW,
        0o600,
    )?;
    let result = (|| {
        temporary.write_all(&encoded)?;
        temporary.sync_all()?;
        Ok::<(), StorageError>(())
    })();
    drop(temporary);
    if let Err(error) = result {
        let _ = sys::unlink_at(directory.as_raw_fd(), &temporary_name, false);
        let _ = directory.sync_all();
        return Err(error);
    }
    sys::rename_replace_between(
        directory.as_raw_fd(),
        &temporary_name,
        directory.as_raw_fd(),
        &final_name,
    )?;
    // The rename is the commit point. Do not report a failed update after the
    // new canonical manifest is already visible.
    let _ = directory.sync_all();
    Ok(())
}

pub fn load_sync_manifest(
    arch_root: &Path,
    mapping_id: [u8; 16],
) -> Result<Option<SyncManifest>, StorageError> {
    if mapping_id == [0; 16] {
        return Err(StorageError::InvalidManifest);
    }
    let directory = open_directory(arch_root, SYNC_STATE_DIRECTORY)?;
    let (final_name, _) = manifest_file_names(mapping_id)?;
    let mut file = match sys::open_at(
        directory.as_raw_fd(),
        &final_name,
        sys::O_RDONLY | sys::O_CLOEXEC | sys::O_NOFOLLOW | sys::O_NONBLOCK,
        0,
    ) {
        Ok(file) => file,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(_) => return Err(StorageError::InvalidManifest),
    };
    let metadata = file.metadata()?;
    if !metadata.is_file()
        || metadata.len() < SYNC_MANIFEST_HEADER_BYTES as u64
        || metadata.len() > MAX_SYNC_MANIFEST_BYTES as u64
    {
        return Err(StorageError::InvalidManifest);
    }
    let length = usize::try_from(metadata.len()).map_err(|_| StorageError::ManifestTooLarge)?;
    let encoded = read_exact_manifest_bytes(&mut file, length)?;
    let manifest = SyncManifest::decode(&encoded)?;
    if manifest.mapping_id != mapping_id {
        return Err(StorageError::InvalidManifest);
    }
    Ok(Some(manifest))
}

fn read_exact_manifest_bytes(
    input: &mut impl Read,
    expected_length: usize,
) -> Result<Vec<u8>, StorageError> {
    let mut encoded = vec![0_u8; expected_length];
    if let Err(error) = input.read_exact(&mut encoded) {
        return if error.kind() == io::ErrorKind::UnexpectedEof {
            Err(StorageError::InvalidManifest)
        } else {
            Err(StorageError::Io(error))
        };
    }
    let mut trailing = [0_u8; 1];
    if input.read(&mut trailing)? != 0 {
        return Err(StorageError::InvalidManifest);
    }
    Ok(encoded)
}

pub fn decide_sync_action(
    baseline: Option<SyncFingerprint>,
    linux: Option<SyncFingerprint>,
    android: Option<SyncFingerprint>,
) -> SyncAction {
    if linux == android {
        return SyncAction::Converged;
    }
    let Some(baseline) = baseline else {
        return match (linux, android) {
            (Some(_), None) => SyncAction::PushToAndroid,
            (None, Some(_)) => SyncAction::PullToLinux,
            (Some(_), Some(_)) => SyncAction::PreserveConflict,
            (None, None) => SyncAction::Converged,
        };
    };
    if linux == Some(baseline) {
        return if android.is_some() {
            SyncAction::PullToLinux
        } else {
            SyncAction::DeleteFromLinux
        };
    }
    if android == Some(baseline) {
        return if linux.is_some() {
            SyncAction::PushToAndroid
        } else {
            SyncAction::DeleteFromAndroid
        };
    }
    SyncAction::PreserveConflict
}

fn manifest_file_names(mapping_id: [u8; 16]) -> Result<(CString, CString), StorageError> {
    if mapping_id == [0; 16] {
        return Err(StorageError::InvalidManifest);
    }
    let identifier = hex_mapping_id(mapping_id);
    let final_name =
        CString::new(format!("{identifier}.v1")).map_err(|_| StorageError::InvalidManifest)?;
    let temporary_name =
        CString::new(format!(".{identifier}.tmp")).map_err(|_| StorageError::InvalidManifest)?;
    Ok((final_name, temporary_name))
}

fn hex_mapping_id(mapping_id: [u8; 16]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut identifier = String::with_capacity(32);
    for byte in mapping_id {
        identifier.push(HEX[(byte >> 4) as usize] as char);
        identifier.push(HEX[(byte & 0x0f) as usize] as char);
    }
    identifier
}

fn validate_manifest_state_entry(directory: &File, name: &CString) -> Result<bool, StorageError> {
    match sys::open_at(
        directory.as_raw_fd(),
        name,
        sys::O_RDONLY | sys::O_CLOEXEC | sys::O_NOFOLLOW | sys::O_NONBLOCK,
        0,
    ) {
        Ok(file) => {
            let metadata = file.metadata()?;
            if !metadata.is_file() || metadata.len() > MAX_SYNC_MANIFEST_BYTES as u64 {
                return Err(StorageError::InvalidManifest);
            }
            Ok(true)
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(false),
        Err(_) => Err(StorageError::InvalidManifest),
    }
}

pub fn create_sync_plan(
    baseline: &SyncManifest,
    linux: &SyncManifest,
    android: &SyncManifest,
) -> Result<SyncPlan, StorageError> {
    if baseline.mapping_id != linux.mapping_id
        || baseline.mapping_id != android.mapping_id
        || baseline.project_name != linux.project_name
        || baseline.project_name != android.project_name
    {
        return Err(StorageError::InvalidManifest);
    }

    let mut baseline_index = 0;
    let mut linux_index = 0;
    let mut android_index = 0;
    let mut entries = Vec::with_capacity(
        baseline
            .entries
            .len()
            .max(linux.entries.len())
            .max(android.entries.len()),
    );
    let mut summary = SyncPlanSummary::default();
    while baseline_index < baseline.entries.len()
        || linux_index < linux.entries.len()
        || android_index < android.entries.len()
    {
        let path = [
            baseline.entries.get(baseline_index),
            linux.entries.get(linux_index),
            android.entries.get(android_index),
        ]
        .into_iter()
        .flatten()
        .map(|entry| entry.path.as_str())
        .min_by(|left, right| left.as_bytes().cmp(right.as_bytes()))
        .ok_or(StorageError::InvalidManifest)?;
        let baseline_fingerprint =
            manifest_fingerprint_at(&baseline.entries, &mut baseline_index, path);
        let linux_fingerprint = manifest_fingerprint_at(&linux.entries, &mut linux_index, path);
        let android_fingerprint =
            manifest_fingerprint_at(&android.entries, &mut android_index, path);
        let action =
            decide_sync_action(baseline_fingerprint, linux_fingerprint, android_fingerprint);
        match action {
            SyncAction::Converged => summary.converged += 1,
            SyncAction::PushToAndroid => summary.push_to_android += 1,
            SyncAction::PullToLinux => summary.pull_to_linux += 1,
            SyncAction::DeleteFromAndroid => summary.delete_from_android += 1,
            SyncAction::DeleteFromLinux => summary.delete_from_linux += 1,
            SyncAction::PreserveConflict => summary.conflicts += 1,
        }
        entries.push(SyncPlanEntry {
            path: path.to_owned(),
            baseline: baseline_fingerprint,
            linux: linux_fingerprint,
            android: android_fingerprint,
            action,
        });
        if entries.len() > MAX_MIRROR_ENTRIES as usize {
            return Err(StorageError::ManifestTooLarge);
        }
    }
    Ok(SyncPlan {
        mapping_id: baseline.mapping_id,
        project_name: baseline.project_name.clone(),
        entries,
        summary,
    })
}

pub fn reconcile_sync_baseline(
    previous: &SyncManifest,
    linux: &SyncManifest,
    android: &SyncManifest,
) -> Result<SyncManifest, StorageError> {
    let plan = create_sync_plan(previous, linux, android)?;
    let mut entries = Vec::with_capacity(plan.entries.len());
    for entry in plan.entries {
        if entry.linux == entry.android {
            if let Some(fingerprint) = entry.linux {
                entries.push(SyncManifestEntry {
                    path: entry.path,
                    fingerprint,
                });
            }
        } else if let Some(fingerprint) = entry.baseline {
            entries.push(SyncManifestEntry {
                path: entry.path,
                fingerprint,
            });
        }
    }
    SyncManifest::new(previous.mapping_id, previous.project_name.clone(), entries)
}

pub fn snapshot_linux_project(
    arch_root: &Path,
    mapping_id: [u8; 16],
) -> Result<SyncManifest, StorageError> {
    snapshot_linux_project_cancellable(arch_root, mapping_id, &MirrorCancellation::new())
}

pub fn snapshot_linux_project_cancellable(
    arch_root: &Path,
    mapping_id: [u8; 16],
    cancellation: &MirrorCancellation,
) -> Result<SyncManifest, StorageError> {
    cancellation.check()?;
    let baseline =
        load_sync_manifest(arch_root, mapping_id)?.ok_or(StorageError::InvalidManifest)?;
    let project = open_directory(
        arch_root,
        &["home", "archphene", "Projects", baseline.project_name()],
    )?;
    let mut entries = Vec::with_capacity(baseline.entries().len().max(256));
    let mut total_bytes = 0_u64;
    snapshot_linux_directory(
        &project,
        "",
        0,
        &mut entries,
        &mut total_bytes,
        Some(cancellation),
    )?;
    cancellation.check()?;
    SyncManifest::new(mapping_id, baseline.project_name().to_owned(), entries)
}

pub fn fingerprint_file_from_fd(
    source_descriptor: RawFd,
    expected_bytes: Option<u64>,
) -> Result<SyncFingerprint, StorageError> {
    fingerprint_file_from_fd_cancellable(
        source_descriptor,
        expected_bytes,
        &MirrorCancellation::new(),
    )
}

pub fn fingerprint_file_from_fd_cancellable(
    source_descriptor: RawFd,
    expected_bytes: Option<u64>,
    cancellation: &MirrorCancellation,
) -> Result<SyncFingerprint, StorageError> {
    if source_descriptor < 0 || expected_bytes.is_some_and(|size| size > MAX_MIRROR_FILE_BYTES) {
        return Err(StorageError::InvalidDocument);
    }
    let mut source = sys::duplicate(source_descriptor)?;
    let mut bytes = 0_u64;
    let mut digest = Sha256::new();
    let mut buffer = [0_u8; 32 * 1024];
    loop {
        cancellation.check()?;
        let count = source.read(&mut buffer)?;
        if count == 0 {
            break;
        }
        cancellation.check()?;
        bytes = bytes
            .checked_add(count as u64)
            .ok_or(StorageError::MirrorTooLarge)?;
        if bytes > MAX_MIRROR_FILE_BYTES {
            return Err(StorageError::MirrorTooLarge);
        }
        digest.update(&buffer[..count]);
    }
    if expected_bytes.is_some_and(|expected| expected != bytes) {
        return Err(StorageError::InvalidDocument);
    }
    cancellation.check()?;
    Ok(SyncFingerprint::file(bytes, digest.finalize().into()))
}

pub fn open_linux_project_file(
    arch_root: &Path,
    mapping_id: [u8; 16],
    relative_path: &str,
    expected: SyncFingerprint,
) -> Result<File, StorageError> {
    open_linux_project_file_cancellable(
        arch_root,
        mapping_id,
        relative_path,
        expected,
        &MirrorCancellation::new(),
    )
}

pub fn open_linux_project_file_cancellable(
    arch_root: &Path,
    mapping_id: [u8; 16],
    relative_path: &str,
    expected: SyncFingerprint,
    cancellation: &MirrorCancellation,
) -> Result<File, StorageError> {
    cancellation.check()?;
    if expected.kind != SyncEntryKind::File {
        return Err(StorageError::InvalidDocument);
    }
    let project = open_sync_project(arch_root, mapping_id)?;
    let segments = parse_mirror_path(relative_path)?;
    let (parent, leaf) = open_mirror_parent(&project, &segments)?;
    let mut file = sys::open_at(
        parent.as_raw_fd(),
        &leaf,
        sys::O_RDONLY | sys::O_CLOEXEC | sys::O_NOFOLLOW | sys::O_NONBLOCK,
        0,
    )
    .map_err(|error| {
        if error.kind() == io::ErrorKind::NotFound {
            StorageError::SyncChanged
        } else {
            StorageError::Io(error)
        }
    })?;
    let metadata = file.metadata()?;
    if !metadata.is_file()
        || fingerprint_open_linux_file_cancellable(
            file.try_clone()?,
            &metadata,
            Some(cancellation),
        )? != expected
    {
        return Err(StorageError::SyncChanged);
    }
    cancellation.check()?;
    file.seek(SeekFrom::Start(0))?;
    Ok(file)
}

pub fn pull_linux_project_file_from_fd(
    arch_root: &Path,
    mapping_id: [u8; 16],
    relative_path: &str,
    source_descriptor: RawFd,
    expected_android: SyncFingerprint,
    expected_linux: Option<SyncFingerprint>,
) -> Result<(), StorageError> {
    pull_linux_project_file_from_fd_cancellable(
        arch_root,
        mapping_id,
        relative_path,
        source_descriptor,
        expected_android,
        expected_linux,
        &MirrorCancellation::new(),
    )
}

pub fn pull_linux_project_file_from_fd_cancellable(
    arch_root: &Path,
    mapping_id: [u8; 16],
    relative_path: &str,
    source_descriptor: RawFd,
    expected_android: SyncFingerprint,
    expected_linux: Option<SyncFingerprint>,
    cancellation: &MirrorCancellation,
) -> Result<(), StorageError> {
    cancellation.check()?;
    if expected_android.kind != SyncEntryKind::File || source_descriptor < 0 {
        return Err(StorageError::InvalidDocument);
    }
    if expected_linux.is_some_and(|fingerprint| fingerprint.kind != SyncEntryKind::File) {
        return Err(StorageError::SyncChanged);
    }
    let project = open_sync_project(arch_root, mapping_id)?;
    let segments = parse_mirror_path(relative_path)?;
    let (parent, leaf) = open_mirror_parent(&project, &segments)?;
    validate_project_target(&parent, &leaf, expected_linux)?;

    let state = open_directory(arch_root, SYNC_STATE_DIRECTORY)?;
    let temporary_name = sync_transfer_name(mapping_id)?;
    remove_sync_transfer_if_present(&state, &temporary_name)?;
    let mut temporary = sys::open_at(
        state.as_raw_fd(),
        &temporary_name,
        sys::O_WRONLY | sys::O_CREAT | sys::O_EXCL | sys::O_CLOEXEC | sys::O_NOFOLLOW,
        0o600,
    )?;
    state.sync_all()?;
    let mut source = sys::duplicate(source_descriptor)?;
    let result = (|| {
        let mut bytes = 0_u64;
        let mut digest = Sha256::new();
        let mut buffer = [0_u8; 32 * 1024];
        loop {
            cancellation.check()?;
            let count = source.read(&mut buffer)?;
            if count == 0 {
                break;
            }
            cancellation.check()?;
            bytes = bytes
                .checked_add(count as u64)
                .ok_or(StorageError::MirrorTooLarge)?;
            if bytes > MAX_MIRROR_FILE_BYTES {
                return Err(StorageError::MirrorTooLarge);
            }
            digest.update(&buffer[..count]);
            temporary.write_all(&buffer[..count])?;
        }
        let observed = SyncFingerprint::file(bytes, digest.finalize().into());
        if observed != expected_android {
            return Err(StorageError::SyncChanged);
        }
        cancellation.check()?;
        temporary.sync_all()?;
        validate_project_target(&parent, &leaf, expected_linux)?;
        cancellation.check()?;
        if expected_linux.is_some() {
            sys::rename_replace_between(
                state.as_raw_fd(),
                &temporary_name,
                parent.as_raw_fd(),
                &leaf,
            )?;
        } else {
            sys::rename_noreplace_between(
                state.as_raw_fd(),
                &temporary_name,
                parent.as_raw_fd(),
                &leaf,
            )
            .map_err(|error| {
                if error.kind() == io::ErrorKind::AlreadyExists {
                    StorageError::SyncChanged
                } else {
                    StorageError::Io(error)
                }
            })?;
        }
        parent.sync_all()?;
        let _ = state.sync_all();
        Ok(())
    })();
    drop(temporary);
    if result.is_err() {
        let _ = sys::unlink_at(state.as_raw_fd(), &temporary_name, false);
        let _ = state.sync_all();
    }
    result
}

pub fn create_linux_project_directory(
    arch_root: &Path,
    mapping_id: [u8; 16],
    relative_path: &str,
) -> Result<(), StorageError> {
    let project = open_sync_project(arch_root, mapping_id)?;
    let segments = parse_mirror_path(relative_path)?;
    let (parent, leaf) = open_mirror_parent(&project, &segments)?;
    validate_project_target(&parent, &leaf, None)?;
    sys::mkdir_at(parent.as_raw_fd(), &leaf, 0o700).map_err(|error| {
        if error.kind() == io::ErrorKind::AlreadyExists {
            StorageError::SyncChanged
        } else {
            StorageError::Io(error)
        }
    })?;
    parent.sync_all()?;
    Ok(())
}

pub fn delete_linux_project_entry(
    arch_root: &Path,
    mapping_id: [u8; 16],
    relative_path: &str,
    expected: SyncFingerprint,
) -> Result<(), StorageError> {
    let project = open_sync_project(arch_root, mapping_id)?;
    let segments = parse_mirror_path(relative_path)?;
    let (parent, leaf) = open_mirror_parent(&project, &segments)?;
    validate_project_target(&parent, &leaf, Some(expected))?;
    sys::unlink_at(
        parent.as_raw_fd(),
        &leaf,
        expected.kind == SyncEntryKind::Directory,
    )
    .map_err(|error| {
        if matches!(
            error.kind(),
            io::ErrorKind::NotFound | io::ErrorKind::DirectoryNotEmpty
        ) {
            StorageError::SyncChanged
        } else {
            StorageError::Io(error)
        }
    })?;
    parent.sync_all()?;
    Ok(())
}

pub fn preserve_android_conflict_from_fd(
    arch_root: &Path,
    mapping_id: [u8; 16],
    relative_path: &str,
    source_descriptor: RawFd,
    expected_android: SyncFingerprint,
) -> Result<String, StorageError> {
    preserve_android_conflict_from_fd_cancellable(
        arch_root,
        mapping_id,
        relative_path,
        source_descriptor,
        expected_android,
        &MirrorCancellation::new(),
    )
}

pub fn preserve_android_conflict_from_fd_cancellable(
    arch_root: &Path,
    mapping_id: [u8; 16],
    relative_path: &str,
    source_descriptor: RawFd,
    expected_android: SyncFingerprint,
    cancellation: &MirrorCancellation,
) -> Result<String, StorageError> {
    cancellation.check()?;
    if expected_android.kind != SyncEntryKind::File {
        return Err(StorageError::InvalidDocument);
    }
    let (parent_path, name) = relative_path
        .rsplit_once('/')
        .map_or(("", relative_path), |(parent, name)| (parent, name));
    validate_project_name(name)?;
    let digest = hex_digest_prefix(expected_android.sha256, 12);
    for collision in 0..=999 {
        cancellation.check()?;
        let suffix = if collision == 0 {
            format!(".android-conflict-{digest}")
        } else {
            format!(".android-conflict-{digest}-{collision}")
        };
        let parent_bytes = if parent_path.is_empty() {
            0
        } else {
            parent_path.len() + 1
        };
        let available =
            MAX_DOCUMENT_NAME_BYTES.min(MAX_MIRROR_PATH_BYTES.saturating_sub(parent_bytes));
        if suffix.len() >= available {
            return Err(StorageError::SyncConflictLimit);
        }
        let mut prefix_bytes = (available - suffix.len()).min(name.len());
        while !name.is_char_boundary(prefix_bytes) {
            prefix_bytes -= 1;
        }
        let mut conflict_name = String::with_capacity(available);
        conflict_name.push_str(&name[..prefix_bytes]);
        conflict_name.push_str(&suffix);
        let candidate = if parent_path.is_empty() {
            conflict_name
        } else {
            format!("{parent_path}/{conflict_name}")
        };
        match linux_project_fingerprint(arch_root, mapping_id, &candidate)? {
            Some(fingerprint) if fingerprint == expected_android => return Ok(candidate),
            Some(_) => continue,
            None => {
                pull_linux_project_file_from_fd_cancellable(
                    arch_root,
                    mapping_id,
                    &candidate,
                    source_descriptor,
                    expected_android,
                    None,
                    cancellation,
                )?;
                return Ok(candidate);
            }
        }
    }
    Err(StorageError::SyncConflictLimit)
}

fn open_sync_project(arch_root: &Path, mapping_id: [u8; 16]) -> Result<File, StorageError> {
    let baseline =
        load_sync_manifest(arch_root, mapping_id)?.ok_or(StorageError::InvalidManifest)?;
    open_directory(
        arch_root,
        &["home", "archphene", "Projects", baseline.project_name()],
    )
}

fn linux_project_fingerprint(
    arch_root: &Path,
    mapping_id: [u8; 16],
    relative_path: &str,
) -> Result<Option<SyncFingerprint>, StorageError> {
    let project = open_sync_project(arch_root, mapping_id)?;
    let segments = parse_mirror_path(relative_path)?;
    let (parent, leaf) = open_mirror_parent(&project, &segments)?;
    project_target_fingerprint(&parent, &leaf)
}

fn validate_project_target(
    parent: &File,
    leaf: &CString,
    expected: Option<SyncFingerprint>,
) -> Result<(), StorageError> {
    if project_target_fingerprint(parent, leaf)? == expected {
        Ok(())
    } else {
        Err(StorageError::SyncChanged)
    }
}

fn project_target_fingerprint(
    parent: &File,
    leaf: &CString,
) -> Result<Option<SyncFingerprint>, StorageError> {
    let child = match sys::open_at(
        parent.as_raw_fd(),
        leaf,
        sys::O_RDONLY | sys::O_CLOEXEC | sys::O_NOFOLLOW | sys::O_NONBLOCK,
        0,
    ) {
        Ok(child) => child,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error.into()),
    };
    let metadata = child.metadata()?;
    if metadata.is_dir() {
        Ok(Some(SyncFingerprint::directory()))
    } else if metadata.is_file() {
        Ok(Some(fingerprint_open_linux_file(child, &metadata)?))
    } else {
        Err(StorageError::InvalidDocument)
    }
}

fn sync_transfer_name(mapping_id: [u8; 16]) -> Result<CString, StorageError> {
    c_string(OsStr::new(&format!(
        ".{}.transfer.tmp",
        hex_mapping_id(mapping_id)
    )))
}

fn remove_sync_transfer_if_present(
    state: &File,
    temporary_name: &CString,
) -> Result<(), StorageError> {
    match sys::open_at(
        state.as_raw_fd(),
        temporary_name,
        sys::O_RDONLY | sys::O_CLOEXEC | sys::O_NOFOLLOW | sys::O_NONBLOCK,
        0,
    ) {
        Ok(file) => {
            let metadata = file.metadata()?;
            if !metadata.is_file() || metadata.len() > MAX_MIRROR_FILE_BYTES {
                return Err(StorageError::InvalidDocument);
            }
            drop(file);
            sys::unlink_at(state.as_raw_fd(), temporary_name, false)?;
            state.sync_all()?;
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => {}
        Err(_) => return Err(StorageError::InvalidDocument),
    }
    Ok(())
}

fn hex_digest_prefix(digest: [u8; 32], digits: usize) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let digits = digits.min(64);
    let mut output = String::with_capacity(digits);
    for index in 0..digits {
        let byte = digest[index / 2];
        output.push(HEX[((byte >> (4 * (1 - index % 2))) & 0x0f) as usize] as char);
    }
    output
}

fn snapshot_linux_directory(
    directory: &File,
    prefix: &str,
    depth: usize,
    entries: &mut Vec<SyncManifestEntry>,
    total_bytes: &mut u64,
    cancellation: Option<&MirrorCancellation>,
) -> Result<(), StorageError> {
    if let Some(cancellation) = cancellation {
        cancellation.check()?;
    }
    if depth > MAX_MIRROR_DEPTH {
        return Err(StorageError::MirrorTooLarge);
    }
    let before = directory.metadata()?;
    let descriptor_path = format!("/proc/self/fd/{}", directory.as_raw_fd());
    for child in fs::read_dir(descriptor_path)? {
        if let Some(cancellation) = cancellation {
            cancellation.check()?;
        }
        if depth >= MAX_MIRROR_DEPTH {
            return Err(StorageError::MirrorTooLarge);
        }
        let child = child?;
        let name = child.file_name();
        let name = name.to_str().ok_or(StorageError::InvalidDocument)?;
        validate_project_name(name)?;
        if entries.len() >= MAX_MIRROR_ENTRIES as usize {
            return Err(StorageError::MirrorTooLarge);
        }
        let relative_path = if prefix.is_empty() {
            name.to_owned()
        } else {
            let mut path = String::with_capacity(prefix.len() + name.len() + 1);
            path.push_str(prefix);
            path.push('/');
            path.push_str(name);
            path
        };
        if relative_path.len() > MAX_MIRROR_PATH_BYTES {
            return Err(StorageError::MirrorTooLarge);
        }
        let child = sys::open_at(
            directory.as_raw_fd(),
            &c_string(OsStr::new(name))?,
            sys::O_RDONLY | sys::O_CLOEXEC | sys::O_NOFOLLOW | sys::O_NONBLOCK,
            0,
        )?;
        let metadata = child.metadata()?;
        if metadata.is_dir() {
            entries.push(SyncManifestEntry {
                path: relative_path.clone(),
                fingerprint: SyncFingerprint::directory(),
            });
            snapshot_linux_directory(
                &child,
                &relative_path,
                depth + 1,
                entries,
                total_bytes,
                cancellation,
            )?;
        } else if metadata.is_file() {
            if metadata.len() > MAX_MIRROR_FILE_BYTES {
                return Err(StorageError::MirrorTooLarge);
            }
            let fingerprint =
                fingerprint_open_linux_file_cancellable(child, &metadata, cancellation)?;
            *total_bytes = total_bytes
                .checked_add(fingerprint.bytes)
                .ok_or(StorageError::MirrorTooLarge)?;
            if *total_bytes > MAX_MIRROR_TOTAL_BYTES {
                return Err(StorageError::MirrorTooLarge);
            }
            entries.push(SyncManifestEntry {
                path: relative_path,
                fingerprint,
            });
        } else {
            return Err(StorageError::InvalidDocument);
        }
    }
    let after = directory.metadata()?;
    if metadata_changed(&before, &after) {
        return Err(StorageError::InvalidDocument);
    }
    Ok(())
}

fn fingerprint_open_linux_file(
    file: File,
    before: &fs::Metadata,
) -> Result<SyncFingerprint, StorageError> {
    fingerprint_open_linux_file_cancellable(file, before, None)
}

fn fingerprint_open_linux_file_cancellable(
    mut file: File,
    before: &fs::Metadata,
    cancellation: Option<&MirrorCancellation>,
) -> Result<SyncFingerprint, StorageError> {
    let mut bytes = 0_u64;
    let mut digest = Sha256::new();
    let mut buffer = [0_u8; 32 * 1024];
    loop {
        if let Some(cancellation) = cancellation {
            cancellation.check()?;
        }
        let count = file.read(&mut buffer)?;
        if count == 0 {
            break;
        }
        if let Some(cancellation) = cancellation {
            cancellation.check()?;
        }
        bytes = bytes
            .checked_add(count as u64)
            .ok_or(StorageError::MirrorTooLarge)?;
        if bytes > MAX_MIRROR_FILE_BYTES {
            return Err(StorageError::MirrorTooLarge);
        }
        digest.update(&buffer[..count]);
    }
    let after = file.metadata()?;
    if bytes != before.len() || metadata_changed(before, &after) {
        return Err(StorageError::InvalidDocument);
    }
    if let Some(cancellation) = cancellation {
        cancellation.check()?;
    }
    Ok(SyncFingerprint::file(bytes, digest.finalize().into()))
}

fn metadata_changed(before: &fs::Metadata, after: &fs::Metadata) -> bool {
    before.dev() != after.dev()
        || before.ino() != after.ino()
        || before.len() != after.len()
        || before.mtime() != after.mtime()
        || before.mtime_nsec() != after.mtime_nsec()
        || before.ctime() != after.ctime()
        || before.ctime_nsec() != after.ctime_nsec()
}

fn manifest_fingerprint_at(
    entries: &[SyncManifestEntry],
    index: &mut usize,
    path: &str,
) -> Option<SyncFingerprint> {
    let entry = entries.get(*index)?;
    if entry.path != path {
        return None;
    }
    *index += 1;
    Some(entry.fingerprint)
}

fn validate_manifest_entries(entries: &[SyncManifestEntry]) -> Result<(), StorageError> {
    if entries.len() > MAX_MIRROR_ENTRIES as usize {
        return Err(StorageError::ManifestTooLarge);
    }
    let mut previous: Option<&[u8]> = None;
    let mut encoded_bytes = SYNC_MANIFEST_HEADER_BYTES;
    let mut content_bytes = 0_u64;
    for entry in entries {
        parse_mirror_path(&entry.path).map_err(|_| StorageError::InvalidManifest)?;
        let path = entry.path.as_bytes();
        if previous.is_some_and(|value| value >= path) {
            return Err(StorageError::InvalidManifest);
        }
        if entry.fingerprint.kind == SyncEntryKind::Directory
            && (entry.fingerprint.bytes != 0 || entry.fingerprint.sha256 != [0; 32])
        {
            return Err(StorageError::InvalidManifest);
        }
        if entry.fingerprint.kind == SyncEntryKind::File {
            if entry.fingerprint.bytes > MAX_MIRROR_FILE_BYTES {
                return Err(StorageError::ManifestTooLarge);
            }
            content_bytes = content_bytes
                .checked_add(entry.fingerprint.bytes)
                .ok_or(StorageError::ManifestTooLarge)?;
            if content_bytes > MAX_MIRROR_TOTAL_BYTES {
                return Err(StorageError::ManifestTooLarge);
            }
        }
        encoded_bytes = encoded_bytes
            .checked_add(SYNC_MANIFEST_ENTRY_HEADER_BYTES)
            .and_then(|value| value.checked_add(path.len()))
            .ok_or(StorageError::ManifestTooLarge)?;
        if encoded_bytes > MAX_SYNC_MANIFEST_BYTES {
            return Err(StorageError::ManifestTooLarge);
        }
        previous = Some(path);
    }
    Ok(())
}

fn take_manifest_bytes<'a>(
    input: &'a [u8],
    cursor: &mut usize,
    count: usize,
) -> Result<&'a [u8], StorageError> {
    let end = cursor
        .checked_add(count)
        .ok_or(StorageError::InvalidManifest)?;
    let value = input
        .get(*cursor..end)
        .ok_or(StorageError::InvalidManifest)?;
    *cursor = end;
    Ok(value)
}

fn take_manifest_u16(input: &[u8], cursor: &mut usize) -> Result<u16, StorageError> {
    let mut bytes = [0_u8; 2];
    bytes.copy_from_slice(take_manifest_bytes(input, cursor, 2)?);
    Ok(u16::from_le_bytes(bytes))
}

fn take_manifest_u32(input: &[u8], cursor: &mut usize) -> Result<u32, StorageError> {
    let mut bytes = [0_u8; 4];
    bytes.copy_from_slice(take_manifest_bytes(input, cursor, 4)?);
    Ok(u32::from_le_bytes(bytes))
}

fn take_manifest_u64(input: &[u8], cursor: &mut usize) -> Result<u64, StorageError> {
    let mut bytes = [0_u8; 8];
    bytes.copy_from_slice(take_manifest_bytes(input, cursor, 8)?);
    Ok(u64::from_le_bytes(bytes))
}

impl MirrorImport {
    pub fn begin(home: &Path, project_name: &str) -> Result<Self, StorageError> {
        Self::begin_inner(home, project_name, None)
    }

    pub fn begin_numbered(
        home: &Path,
        requested_name: &str,
    ) -> Result<(Self, String), StorageError> {
        validate_visible_name(requested_name)?;
        for ordinal in 1..=MAX_IMPORT_COLLISIONS {
            let candidate = directory_collision_name(requested_name, ordinal)?;
            match Self::begin(home, &candidate) {
                Ok(import) => return Ok((import, candidate)),
                Err(StorageError::MirrorExists) => {}
                Err(error) => return Err(error),
            }
        }
        Err(StorageError::ImportCollision)
    }

    pub fn begin_with_sync_baseline(
        arch_root: &Path,
        project_name: &str,
        mapping_id: [u8; 16],
    ) -> Result<Self, StorageError> {
        if mapping_id == [0; 16] {
            return Err(StorageError::InvalidManifest);
        }
        let home = arch_root.join("home/archphene");
        Self::begin_inner(
            &home,
            project_name,
            Some(MirrorSyncBaseline {
                arch_root: arch_root.to_owned(),
                mapping_id,
                project_name: project_name.to_owned(),
            }),
        )
    }

    fn begin_inner(
        home: &Path,
        project_name: &str,
        sync_baseline: Option<MirrorSyncBaseline>,
    ) -> Result<Self, StorageError> {
        validate_visible_name(project_name)?;
        let projects = open_directory(home, &["Projects"])?;
        let target_name = c_string(OsStr::new(project_name))?;
        match sys::open_at(
            projects.as_raw_fd(),
            &target_name,
            sys::O_RDONLY | sys::O_CLOEXEC | sys::O_NOFOLLOW | sys::O_NONBLOCK,
            0,
        ) {
            Ok(_) => return Err(StorageError::MirrorExists),
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => return Err(error.into()),
        }

        let projects_path = home.join("Projects");
        let staging_path = projects_path.join(MIRROR_STAGING_DIRECTORY);
        recover_mirror_staging(&staging_path)?;
        let staging_name = c_string(OsStr::new(MIRROR_STAGING_DIRECTORY))?;
        match sys::mkdir_at(projects.as_raw_fd(), &staging_name, 0o700) {
            Ok(()) => {}
            Err(error) if error.kind() == io::ErrorKind::AlreadyExists => {
                return Err(StorageError::MirrorBusy);
            }
            Err(error) => return Err(error.into()),
        }
        projects.sync_all()?;
        let staging = sys::open_at(
            projects.as_raw_fd(),
            &staging_name,
            sys::O_RDONLY | sys::O_DIRECTORY | sys::O_CLOEXEC | sys::O_NOFOLLOW,
            0,
        )?;
        Ok(Self {
            staging_path,
            projects,
            staging,
            target_name,
            sync_baseline,
            sync_entries: Vec::with_capacity(256),
            entries: 0,
            bytes: 0,
            published: false,
            cancellation: MirrorCancellation(Arc::new(AtomicBool::new(false))),
        })
    }

    pub fn cancellation(&self) -> MirrorCancellation {
        self.cancellation.clone()
    }

    pub fn add_directory(&mut self, relative_path: &str) -> Result<(), StorageError> {
        self.check_cancelled()?;
        self.reserve_entry()?;
        let segments = parse_mirror_path(relative_path)?;
        let (parent, leaf) = open_mirror_parent(&self.staging, &segments)?;
        match sys::mkdir_at(parent.as_raw_fd(), &leaf, 0o700) {
            Ok(()) => {
                parent.sync_all()?;
                if self.sync_baseline.is_some() {
                    self.sync_entries.push(SyncManifestEntry {
                        path: relative_path.to_owned(),
                        fingerprint: SyncFingerprint::directory(),
                    });
                }
                Ok(())
            }
            Err(error) => Err(error.into()),
        }
    }

    pub fn add_file_from_fd(
        &mut self,
        relative_path: &str,
        source_descriptor: RawFd,
        expected_bytes: Option<u64>,
    ) -> Result<u64, StorageError> {
        self.check_cancelled()?;
        if source_descriptor < 0 || expected_bytes.is_some_and(|size| size > MAX_MIRROR_FILE_BYTES)
        {
            return Err(StorageError::InvalidDocument);
        }
        let mut source = sys::duplicate(source_descriptor)?;
        self.add_file_from_reader(relative_path, &mut source, expected_bytes)
    }

    fn add_file_from_reader<R: Read>(
        &mut self,
        relative_path: &str,
        source: &mut R,
        expected_bytes: Option<u64>,
    ) -> Result<u64, StorageError> {
        if expected_bytes.is_some_and(|size| size > MAX_MIRROR_FILE_BYTES) {
            return Err(StorageError::InvalidDocument);
        }
        self.reserve_entry()?;
        let segments = parse_mirror_path(relative_path)?;
        let (parent, leaf) = open_mirror_parent(&self.staging, &segments)?;
        let mut destination = sys::open_at(
            parent.as_raw_fd(),
            &leaf,
            sys::O_WRONLY | sys::O_CREAT | sys::O_EXCL | sys::O_CLOEXEC | sys::O_NOFOLLOW,
            0o600,
        )?;
        let result = (|| {
            let mut copied = 0_u64;
            let mut digest = Sha256::new();
            let mut buffer = [0_u8; 32 * 1024];
            loop {
                self.check_cancelled()?;
                let count = source.read(&mut buffer)?;
                if count == 0 {
                    break;
                }
                self.check_cancelled()?;
                copied = copied
                    .checked_add(count as u64)
                    .ok_or(StorageError::MirrorTooLarge)?;
                if copied > MAX_MIRROR_FILE_BYTES {
                    return Err(StorageError::MirrorTooLarge);
                }
                digest.update(&buffer[..count]);
                destination.write_all(&buffer[..count])?;
            }
            if expected_bytes.is_some_and(|expected| expected != copied) {
                return Err(StorageError::InvalidDocument);
            }
            let new_total = self
                .bytes
                .checked_add(copied)
                .ok_or(StorageError::MirrorTooLarge)?;
            if new_total > MAX_MIRROR_TOTAL_BYTES {
                return Err(StorageError::MirrorTooLarge);
            }
            destination.sync_all()?;
            self.bytes = new_total;
            let sha256: [u8; 32] = digest.finalize().into();
            Ok((copied, sha256))
        })();
        drop(destination);
        if result.is_err() {
            let _ = sys::unlink_at(parent.as_raw_fd(), &leaf, false);
            let _ = parent.sync_all();
        } else {
            parent.sync_all()?;
        }
        result.map(|(copied, sha256)| {
            if self.sync_baseline.is_some() {
                self.sync_entries.push(SyncManifestEntry {
                    path: relative_path.to_owned(),
                    fingerprint: SyncFingerprint::file(copied, sha256),
                });
            }
            copied
        })
    }

    pub fn add_portal_folder_from_fd(
        &mut self,
        source_descriptor: RawFd,
    ) -> Result<(), StorageError> {
        if source_descriptor < 0 {
            return Err(StorageError::InvalidDocument);
        }
        let mut source = sys::duplicate(source_descriptor)?;
        self.add_portal_folder_stream(&mut source)
    }

    fn add_portal_folder_stream<R: Read>(&mut self, source: &mut R) -> Result<(), StorageError> {
        let mut magic = [0_u8; PORTAL_FOLDER_MAGIC.len()];
        read_portal_folder_exact(source, &mut magic)?;
        if magic != *PORTAL_FOLDER_MAGIC {
            return Err(StorageError::InvalidDocument);
        }
        loop {
            let record = read_portal_folder_byte(source)?;
            match record {
                PORTAL_FOLDER_END => {
                    let mut trailing = [0_u8; 1];
                    if source.read(&mut trailing)? != 0 {
                        return Err(StorageError::InvalidDocument);
                    }
                    return Ok(());
                }
                PORTAL_FOLDER_DIRECTORY => {
                    let path = read_portal_folder_path(source)?;
                    self.add_directory(&path)?;
                }
                PORTAL_FOLDER_FILE => {
                    let path = read_portal_folder_path(source)?;
                    let mut file = PortalFolderFileReader::new(source);
                    self.add_file_from_reader(&path, &mut file, None)?;
                    if !file.finished {
                        return Err(StorageError::InvalidDocument);
                    }
                }
                _ => return Err(StorageError::InvalidDocument),
            }
        }
    }

    pub fn finish(mut self) -> Result<MirrorImportReport, StorageError> {
        self.check_cancelled()?;
        self.staging.sync_all()?;
        self.check_cancelled()?;
        if let Some(baseline) = self.sync_baseline.take() {
            let manifest = SyncManifest::new(
                baseline.mapping_id,
                baseline.project_name,
                std::mem::take(&mut self.sync_entries),
            )?;
            persist_sync_manifest(&baseline.arch_root, &manifest)?;
        }
        self.check_cancelled()?;
        let staging_name = c_string(OsStr::new(MIRROR_STAGING_DIRECTORY))?;
        sys::rename_noreplace_between(
            self.projects.as_raw_fd(),
            &staging_name,
            self.projects.as_raw_fd(),
            &self.target_name,
        )?;
        self.projects.sync_all()?;
        self.published = true;
        Ok(MirrorImportReport {
            entries: self.entries,
            bytes: self.bytes,
        })
    }

    fn reserve_entry(&mut self) -> Result<(), StorageError> {
        self.entries = self
            .entries
            .checked_add(1)
            .ok_or(StorageError::MirrorTooLarge)?;
        if self.entries > MAX_MIRROR_ENTRIES {
            return Err(StorageError::MirrorTooLarge);
        }
        Ok(())
    }

    fn check_cancelled(&self) -> Result<(), StorageError> {
        if self.cancellation.is_cancelled() {
            Err(StorageError::MirrorCancelled)
        } else {
            Ok(())
        }
    }
}

pub fn import_portal_folder_stream<R: Read>(
    home: &Path,
    requested_name: &str,
    source: &mut R,
) -> Result<PortalFolderImportReport, StorageError> {
    let (mut import, display_name) = MirrorImport::begin_numbered(home, requested_name)?;
    import.add_portal_folder_stream(source)?;
    let report = import.finish()?;
    Ok(PortalFolderImportReport {
        display_name,
        entries: report.entries,
        bytes: report.bytes,
    })
}

struct PortalFolderFileReader<'a, R> {
    source: &'a mut R,
    remaining: usize,
    finished: bool,
}

impl<'a, R: Read> PortalFolderFileReader<'a, R> {
    fn new(source: &'a mut R) -> Self {
        Self {
            source,
            remaining: 0,
            finished: false,
        }
    }
}

impl<R: Read> Read for PortalFolderFileReader<'_, R> {
    fn read(&mut self, destination: &mut [u8]) -> io::Result<usize> {
        if destination.is_empty() || self.finished {
            return Ok(0);
        }
        while self.remaining == 0 {
            match read_portal_folder_byte_io(self.source)? {
                PORTAL_FOLDER_DATA => {
                    let mut encoded = [0_u8; 4];
                    read_portal_folder_exact_io(self.source, &mut encoded)?;
                    self.remaining = usize::try_from(u32::from_be_bytes(encoded))
                        .map_err(|_| invalid_portal_folder_data())?;
                    if self.remaining == 0 || self.remaining > MAX_PORTAL_FOLDER_CHUNK_BYTES {
                        return Err(invalid_portal_folder_data());
                    }
                }
                PORTAL_FOLDER_FILE_END => {
                    self.finished = true;
                    return Ok(0);
                }
                _ => return Err(invalid_portal_folder_data()),
            }
        }
        let count = destination.len().min(self.remaining);
        read_portal_folder_exact_io(self.source, &mut destination[..count])?;
        self.remaining -= count;
        Ok(count)
    }
}

fn read_portal_folder_path<R: Read>(source: &mut R) -> Result<String, StorageError> {
    let mut encoded_length = [0_u8; 2];
    read_portal_folder_exact(source, &mut encoded_length)?;
    let length = usize::from(u16::from_be_bytes(encoded_length));
    if length == 0 || length > MAX_MIRROR_PATH_BYTES {
        return Err(StorageError::InvalidDocument);
    }
    let mut path = vec![0_u8; length];
    read_portal_folder_exact(source, &mut path)?;
    String::from_utf8(path).map_err(|_| StorageError::InvalidDocument)
}

fn read_portal_folder_byte<R: Read>(source: &mut R) -> Result<u8, StorageError> {
    let mut value = [0_u8; 1];
    read_portal_folder_exact(source, &mut value)?;
    Ok(value[0])
}

fn read_portal_folder_exact<R: Read>(
    source: &mut R,
    destination: &mut [u8],
) -> Result<(), StorageError> {
    read_portal_folder_exact_io(source, destination).map_err(|error| {
        if error.kind() == io::ErrorKind::UnexpectedEof {
            StorageError::InvalidDocument
        } else {
            StorageError::Io(error)
        }
    })
}

fn read_portal_folder_byte_io<R: Read>(source: &mut R) -> io::Result<u8> {
    let mut value = [0_u8; 1];
    read_portal_folder_exact_io(source, &mut value)?;
    Ok(value[0])
}

fn read_portal_folder_exact_io<R: Read>(source: &mut R, destination: &mut [u8]) -> io::Result<()> {
    source.read_exact(destination)
}

fn invalid_portal_folder_data() -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, "invalid portal folder stream")
}

impl Drop for MirrorImport {
    fn drop(&mut self) {
        if !self.published {
            let _ = fs::remove_dir_all(&self.staging_path);
            let _ = self.projects.sync_all();
        }
    }
}

impl std::error::Error for StorageError {}

impl From<io::Error> for StorageError {
    fn from(error: io::Error) -> Self {
        Self::Io(error)
    }
}

pub fn validate_visible_name(name: &str) -> Result<(), StorageError> {
    let bytes = name.as_bytes();
    if bytes.is_empty()
        || bytes.len() > MAX_DOCUMENT_NAME_BYTES
        || name == "."
        || name == ".."
        || name.starts_with('.')
        || name.contains('/')
        || name.contains('\\')
        || name.chars().any(|character| {
            character.is_control()
                || matches!(
                    character,
                    '\u{061c}'
                        | '\u{200e}'
                        | '\u{200f}'
                        | '\u{202a}'..='\u{202e}'
                        | '\u{2066}'..='\u{2069}'
                )
        })
    {
        return Err(StorageError::HiddenDocument);
    }
    Ok(())
}

pub fn open_document(root: &Path, document_id: &str, mode: OpenMode) -> Result<File, StorageError> {
    validate_open_mode(mode)?;
    let segments = parse_document_id(document_id)?;
    if segments.is_empty() {
        return Err(StorageError::InvalidDocument);
    }
    let (parent, leaf) = open_parent(root, &segments)?;
    open_regular_file(&parent, &leaf, mode)
}

pub fn open_shell_startup_document(
    root: &Path,
    startup_id: &str,
    mode: OpenMode,
) -> Result<File, StorageError> {
    validate_open_mode(mode)?;
    let segments: &[&str] = match startup_id {
        BASHRC_STARTUP_ID => &[".bashrc"],
        BASH_PROFILE_STARTUP_ID => &[".bash_profile"],
        ZSHRC_STARTUP_ID => &[".zshrc"],
        FISH_CONFIG_STARTUP_ID => &[".config", "fish", "config.fish"],
        _ => return Err(StorageError::InvalidDocument),
    };
    let (parent, leaf) = open_parent(root, segments)?;
    open_regular_file(&parent, &leaf, mode)
}

fn validate_open_mode(mode: OpenMode) -> Result<(), StorageError> {
    if (mode.append || mode.truncate || !mode.read) && !mode.write {
        return Err(StorageError::InvalidDocument);
    }
    if mode.truncate && mode.append {
        return Err(StorageError::InvalidDocument);
    }
    Ok(())
}

fn open_regular_file(parent: &File, leaf: &CString, mode: OpenMode) -> Result<File, StorageError> {
    let access = match (mode.read, mode.write) {
        (true, true) => sys::O_RDWR,
        (false, true) => sys::O_WRONLY,
        (true, false) => sys::O_RDONLY,
        (false, false) => return Err(StorageError::InvalidDocument),
    };
    let mut flags = access | sys::O_CLOEXEC | sys::O_NOFOLLOW | sys::O_NONBLOCK;
    if mode.truncate {
        flags |= sys::O_TRUNC;
    }
    if mode.append {
        flags |= sys::O_APPEND;
    }
    let file = sys::open_at(parent.as_raw_fd(), leaf, flags, 0)?;
    if !file.metadata()?.is_file() {
        return Err(StorageError::InvalidDocument);
    }
    Ok(file)
}

pub fn create_document(
    root: &Path,
    parent_id: &str,
    name: &str,
    directory: bool,
) -> Result<(), StorageError> {
    validate_visible_name(name)?;
    let parent = open_directory(root, &parse_document_id(parent_id)?)?;
    let name = c_string(OsStr::new(name))?;
    if directory {
        sys::mkdir_at(parent.as_raw_fd(), &name, 0o700)?;
    } else {
        let file = sys::open_at(
            parent.as_raw_fd(),
            &name,
            sys::O_WRONLY | sys::O_CREAT | sys::O_EXCL | sys::O_CLOEXEC | sys::O_NOFOLLOW,
            0o600,
        )?;
        drop(file);
    }
    parent.sync_all()?;
    Ok(())
}

pub fn rename_document(root: &Path, document_id: &str, new_name: &str) -> Result<(), StorageError> {
    validate_visible_name(new_name)?;
    let segments = parse_document_id(document_id)?;
    if segments.is_empty() {
        return Err(StorageError::RootMutation);
    }
    let (parent, old_name) = open_parent(root, &segments)?;
    let source = sys::open_at(
        parent.as_raw_fd(),
        &old_name,
        sys::O_RDONLY | sys::O_CLOEXEC | sys::O_NOFOLLOW | sys::O_NONBLOCK,
        0,
    )?;
    drop(source);
    let new_name = c_string(OsStr::new(new_name))?;
    sys::rename_noreplace_between(parent.as_raw_fd(), &old_name, parent.as_raw_fd(), &new_name)?;
    parent.sync_all()?;
    Ok(())
}

#[derive(Debug, Eq, PartialEq)]
pub struct ImportReport {
    pub display_name: String,
    pub bytes: u64,
}

pub fn import_document_from_fd(
    root: &Path,
    parent_id: &str,
    display_name: &str,
    source_descriptor: RawFd,
) -> Result<ImportReport, StorageError> {
    import_document_from_fd_with_progress(root, parent_id, display_name, source_descriptor, |_| {
        true
    })
}

pub fn import_document_from_fd_with_progress<F>(
    root: &Path,
    parent_id: &str,
    display_name: &str,
    source_descriptor: RawFd,
    mut continue_after_chunk: F,
) -> Result<ImportReport, StorageError>
where
    F: FnMut(u64) -> bool,
{
    if source_descriptor < 0 {
        return Err(StorageError::InvalidDocument);
    }
    let mut source = sys::duplicate(source_descriptor)?;
    import_document_with_progress(
        root,
        parent_id,
        display_name,
        &mut source,
        &mut continue_after_chunk,
    )
}

pub fn import_document_from_fd_with_progress_and_timeout<F>(
    root: &Path,
    parent_id: &str,
    display_name: &str,
    source_descriptor: RawFd,
    idle_timeout: Duration,
    mut continue_after_chunk: F,
) -> Result<ImportReport, StorageError>
where
    F: FnMut(u64) -> bool,
{
    if source_descriptor < 0 || idle_timeout.is_zero() {
        return Err(StorageError::InvalidDocument);
    }
    let mut source = sys::duplicate(source_descriptor)?;
    import_document_with_copy(root, parent_id, display_name, |pending| {
        copy_bounded_descriptor_with_progress(
            &mut source,
            pending,
            idle_timeout,
            &mut continue_after_chunk,
        )
    })
}

pub fn import_document<R: Read>(
    root: &Path,
    parent_id: &str,
    display_name: &str,
    source: &mut R,
) -> Result<ImportReport, StorageError> {
    import_document_with_progress(root, parent_id, display_name, source, &mut |_| true)
}

fn import_document_with_progress<R: Read, F: FnMut(u64) -> bool>(
    root: &Path,
    parent_id: &str,
    display_name: &str,
    source: &mut R,
    continue_after_chunk: &mut F,
) -> Result<ImportReport, StorageError> {
    import_document_with_copy(root, parent_id, display_name, |pending| {
        copy_bounded_document_with_progress(source, pending, continue_after_chunk)
    })
}

fn import_document_with_copy<F>(
    root: &Path,
    parent_id: &str,
    display_name: &str,
    copy: F,
) -> Result<ImportReport, StorageError>
where
    F: FnOnce(&mut File) -> Result<u64, StorageError>,
{
    validate_visible_name(display_name)?;
    let root_directory = open_directory(root, &[])?;
    let staging_name = c_string(OsStr::new(IMPORT_STAGING_DIRECTORY))?;
    match sys::mkdir_at(root_directory.as_raw_fd(), &staging_name, 0o700) {
        Ok(()) => root_directory.sync_all()?,
        Err(error) if error.kind() == io::ErrorKind::AlreadyExists => {}
        Err(error) => return Err(error.into()),
    }
    let staging = sys::open_at(
        root_directory.as_raw_fd(),
        &staging_name,
        sys::O_RDONLY | sys::O_DIRECTORY | sys::O_CLOEXEC | sys::O_NOFOLLOW,
        0,
    )?;
    let pending_name = c_string(OsStr::new(IMPORT_STAGING_FILE))?;
    remove_stale_import(&staging, &pending_name)?;

    let mut pending = sys::open_at(
        staging.as_raw_fd(),
        &pending_name,
        sys::O_WRONLY | sys::O_CREAT | sys::O_EXCL | sys::O_CLOEXEC | sys::O_NOFOLLOW,
        0o600,
    )?;
    let result = (|| {
        let bytes = copy(&mut pending)?;
        pending.sync_all()?;
        drop(pending);

        let destination = open_directory(root, &parse_document_id(parent_id)?)?;
        for ordinal in 1..=MAX_IMPORT_COLLISIONS {
            let candidate = collision_name(display_name, ordinal)?;
            let candidate_name = c_string(OsStr::new(&candidate))?;
            match sys::rename_noreplace_between(
                staging.as_raw_fd(),
                &pending_name,
                destination.as_raw_fd(),
                &candidate_name,
            ) {
                Ok(()) => {
                    destination.sync_all()?;
                    staging.sync_all()?;
                    return Ok(ImportReport {
                        display_name: candidate,
                        bytes,
                    });
                }
                Err(error) if error.kind() == io::ErrorKind::AlreadyExists => {}
                Err(error) => return Err(error.into()),
            }
        }
        Err(StorageError::ImportCollision)
    })();
    if result.is_err() {
        let _ = sys::unlink_at(staging.as_raw_fd(), &pending_name, false);
        let _ = staging.sync_all();
    }
    result
}

fn copy_bounded_descriptor_with_progress<W: Write, F: FnMut(u64) -> bool>(
    source: &mut File,
    destination: &mut W,
    idle_timeout: Duration,
    continue_after_chunk: &mut F,
) -> Result<u64, StorageError> {
    const CANCELLATION_POLL_INTERVAL: Duration = Duration::from_millis(100);

    let mut bytes = 0_u64;
    let mut buffer = [0_u8; 32 * 1024];
    loop {
        let idle_started = Instant::now();
        loop {
            let Some(remaining) = idle_timeout.checked_sub(idle_started.elapsed()) else {
                return Err(StorageError::ProviderTimeout);
            };
            let interval = remaining.min(CANCELLATION_POLL_INTERVAL);
            let timeout_millis = i32::try_from(interval.as_millis().max(1)).unwrap_or(i32::MAX);
            match sys::wait_readable(source.as_raw_fd(), timeout_millis) {
                Ok(true) => break,
                Ok(false) => {
                    if !continue_after_chunk(bytes) {
                        return Err(StorageError::TransferCancelled);
                    }
                }
                Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
                Err(error) => return Err(StorageError::Io(error)),
            }
        }

        let count = match source.read(&mut buffer) {
            Ok(count) => count,
            Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
            Err(error) => return Err(StorageError::Io(error)),
        };
        if count == 0 {
            break;
        }
        bytes = bytes
            .checked_add(count as u64)
            .ok_or(StorageError::DocumentTooLarge)?;
        if bytes > MAX_DOCUMENT_TRANSFER_BYTES {
            return Err(StorageError::DocumentTooLarge);
        }
        destination.write_all(&buffer[..count])?;
        if !continue_after_chunk(bytes) {
            return Err(StorageError::TransferCancelled);
        }
    }
    Ok(bytes)
}

pub fn copy_document_between_fds(
    source_descriptor: RawFd,
    destination_descriptor: RawFd,
) -> Result<u64, StorageError> {
    copy_document_between_fds_with_progress(source_descriptor, destination_descriptor, |_| true)
}

pub fn copy_document_between_fds_with_progress<F>(
    source_descriptor: RawFd,
    destination_descriptor: RawFd,
    mut continue_after_chunk: F,
) -> Result<u64, StorageError>
where
    F: FnMut(u64) -> bool,
{
    if source_descriptor < 0 || destination_descriptor < 0 {
        return Err(StorageError::InvalidDocument);
    }
    let mut source = sys::duplicate(source_descriptor)?;
    let mut destination = sys::duplicate(destination_descriptor)?;
    let bytes = copy_bounded_document_with_progress(
        &mut source,
        &mut destination,
        &mut continue_after_chunk,
    )?;
    destination.flush()?;
    Ok(bytes)
}

fn copy_bounded_document_with_progress<R: Read, W: Write, F: FnMut(u64) -> bool>(
    source: &mut R,
    destination: &mut W,
    continue_after_chunk: &mut F,
) -> Result<u64, StorageError> {
    let mut bytes = 0_u64;
    let mut buffer = [0_u8; 32 * 1024];
    loop {
        let count = source.read(&mut buffer)?;
        if count == 0 {
            break;
        }
        bytes = bytes
            .checked_add(count as u64)
            .ok_or(StorageError::DocumentTooLarge)?;
        if bytes > MAX_DOCUMENT_TRANSFER_BYTES {
            return Err(StorageError::DocumentTooLarge);
        }
        destination.write_all(&buffer[..count])?;
        if !continue_after_chunk(bytes) {
            return Err(StorageError::TransferCancelled);
        }
    }
    Ok(bytes)
}

pub fn delete_document(root: &Path, document_id: &str) -> Result<(), StorageError> {
    let segments = parse_document_id(document_id)?;
    if segments.is_empty() {
        return Err(StorageError::RootMutation);
    }
    let (parent, leaf) = open_parent(root, &segments)?;
    let entry = sys::open_at(
        parent.as_raw_fd(),
        &leaf,
        sys::O_RDONLY | sys::O_CLOEXEC | sys::O_NOFOLLOW | sys::O_NONBLOCK,
        0,
    )?;
    let metadata = entry.metadata()?;
    if !metadata.is_file() && !metadata.is_dir() {
        return Err(StorageError::InvalidDocument);
    }
    sys::unlink_at(parent.as_raw_fd(), &leaf, metadata.is_dir())?;
    parent.sync_all()?;
    Ok(())
}

fn parse_document_id(document_id: &str) -> Result<Vec<&str>, StorageError> {
    if document_id.len() > MAX_DOCUMENT_ID_BYTES {
        return Err(StorageError::InvalidDocument);
    }
    if document_id == HOME_DOCUMENT_ID {
        return Ok(Vec::new());
    }
    let Some(relative) = document_id.strip_prefix("home/") else {
        return Err(StorageError::InvalidDocument);
    };
    parse_bounded_segments(relative, MAX_DOCUMENT_DEPTH, validate_visible_name)
}

fn parse_mirror_path(relative_path: &str) -> Result<Vec<&str>, StorageError> {
    if relative_path.is_empty() || relative_path.len() > MAX_MIRROR_PATH_BYTES {
        return Err(StorageError::InvalidDocument);
    }
    parse_bounded_segments(relative_path, MAX_MIRROR_DEPTH, validate_project_name)
}

fn parse_bounded_segments(
    value: &str,
    maximum_depth: usize,
    validate: impl Fn(&str) -> Result<(), StorageError>,
) -> Result<Vec<&str>, StorageError> {
    let mut segments = Vec::with_capacity(maximum_depth.min(8));
    for segment in value.split('/') {
        if segments.len() == maximum_depth {
            return Err(StorageError::InvalidDocument);
        }
        validate(segment)?;
        segments.push(segment);
    }
    if segments.is_empty() {
        return Err(StorageError::InvalidDocument);
    }
    Ok(segments)
}

fn validate_project_name(name: &str) -> Result<(), StorageError> {
    let bytes = name.as_bytes();
    if bytes.is_empty()
        || bytes.len() > MAX_DOCUMENT_NAME_BYTES
        || name == "."
        || name == ".."
        || name.contains('/')
        || name.contains('\\')
        || name.contains('\0')
        || name.contains('\t')
        || name.chars().any(|character| {
            character.is_control()
                || matches!(
                    character,
                    '\u{061c}'
                    | '\u{200e}'
                    | '\u{200f}'
                    | '\u{202a}'..='\u{202e}'
                    | '\u{2066}'..='\u{2069}'
                )
        })
    {
        return Err(StorageError::InvalidDocument);
    }
    Ok(())
}

fn open_mirror_parent(staging: &File, segments: &[&str]) -> Result<(File, CString), StorageError> {
    let Some((leaf, parents)) = segments.split_last() else {
        return Err(StorageError::InvalidDocument);
    };
    let mut directory = staging.try_clone()?;
    for segment in parents {
        directory = sys::open_at(
            directory.as_raw_fd(),
            &c_string(OsStr::new(segment))?,
            sys::O_RDONLY | sys::O_DIRECTORY | sys::O_CLOEXEC | sys::O_NOFOLLOW,
            0,
        )?;
    }
    Ok((directory, c_string(OsStr::new(leaf))?))
}

fn recover_mirror_staging(staging_path: &Path) -> Result<(), StorageError> {
    match fs::symlink_metadata(staging_path) {
        Ok(metadata) if metadata.is_dir() => {
            fs::remove_dir_all(staging_path)?;
            Ok(())
        }
        Ok(metadata) if metadata.file_type().is_symlink() || metadata.is_file() => {
            fs::remove_file(staging_path)?;
            Ok(())
        }
        Ok(_) => Err(StorageError::InvalidDocument),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error.into()),
    }
}

fn open_parent(root: &Path, segments: &[&str]) -> Result<(File, CString), StorageError> {
    let Some((leaf, parents)) = segments.split_last() else {
        return Err(StorageError::RootMutation);
    };
    Ok((open_directory(root, parents)?, c_string(OsStr::new(leaf))?))
}

fn open_directory(root: &Path, segments: &[&str]) -> Result<File, StorageError> {
    if !root.is_absolute() || root.as_os_str().as_bytes().len() > MAX_DOCUMENT_ID_BYTES {
        return Err(StorageError::InvalidRoot);
    }
    let root = c_string(root.as_os_str()).map_err(|_| StorageError::InvalidRoot)?;
    let mut directory = sys::open_root(&root)?;
    for segment in segments {
        let segment = c_string(OsStr::new(segment))?;
        directory = sys::open_at(
            directory.as_raw_fd(),
            &segment,
            sys::O_RDONLY | sys::O_DIRECTORY | sys::O_CLOEXEC | sys::O_NOFOLLOW,
            0,
        )?;
    }
    Ok(directory)
}

fn c_string(value: &OsStr) -> Result<CString, StorageError> {
    CString::new(value.as_bytes()).map_err(|_| StorageError::InvalidDocument)
}

fn remove_stale_import(staging: &File, pending_name: &CString) -> Result<(), StorageError> {
    match sys::open_at(
        staging.as_raw_fd(),
        pending_name,
        sys::O_RDONLY | sys::O_CLOEXEC | sys::O_NOFOLLOW | sys::O_NONBLOCK,
        0,
    ) {
        Ok(entry) => {
            if !entry.metadata()?.is_file() {
                return Err(StorageError::InvalidDocument);
            }
            drop(entry);
            sys::unlink_at(staging.as_raw_fd(), pending_name, false)?;
            staging.sync_all()?;
            Ok(())
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error.into()),
    }
}

fn collision_name(display_name: &str, ordinal: u32) -> Result<String, StorageError> {
    if ordinal == 1 {
        return Ok(display_name.to_owned());
    }
    let suffix = format!(" ({ordinal})");
    let extension_start = display_name
        .rfind('.')
        .filter(|index| *index > 0 && display_name.len() - *index <= 33);
    let (base, extension) = extension_start
        .map(|index| display_name.split_at(index))
        .unwrap_or((display_name, ""));
    let reserved = suffix
        .len()
        .checked_add(extension.len())
        .ok_or(StorageError::InvalidDocument)?;
    if reserved >= MAX_DOCUMENT_NAME_BYTES {
        return Err(StorageError::InvalidDocument);
    }
    let maximum_base = MAX_DOCUMENT_NAME_BYTES - reserved;
    let mut boundary = base.len().min(maximum_base);
    while boundary > 0 && !base.is_char_boundary(boundary) {
        boundary -= 1;
    }
    if boundary == 0 {
        return Err(StorageError::InvalidDocument);
    }
    Ok(format!("{}{}{}", &base[..boundary], suffix, extension))
}

fn directory_collision_name(display_name: &str, ordinal: u32) -> Result<String, StorageError> {
    if ordinal == 1 {
        return Ok(display_name.to_owned());
    }
    let suffix = format!(" ({ordinal})");
    if suffix.len() >= MAX_DOCUMENT_NAME_BYTES {
        return Err(StorageError::InvalidDocument);
    }
    let maximum_base = MAX_DOCUMENT_NAME_BYTES - suffix.len();
    let mut boundary = display_name.len().min(maximum_base);
    while boundary > 0 && !display_name.is_char_boundary(boundary) {
        boundary -= 1;
    }
    if boundary == 0 {
        return Err(StorageError::InvalidDocument);
    }
    Ok(format!("{}{}", &display_name[..boundary], suffix))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::io::{Read, Seek, Write};
    use std::os::unix::fs::symlink;
    use std::os::unix::net::UnixStream;
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicU64, Ordering};

    static TEST_ID: AtomicU64 = AtomicU64::new(1);

    struct TestDirectory(PathBuf);

    impl TestDirectory {
        fn new() -> Self {
            let id = TEST_ID.fetch_add(1, Ordering::Relaxed);
            let path = std::env::temp_dir().join(format!(
                "archphene-storage-test-{}-{id}",
                std::process::id()
            ));
            fs::create_dir(&path).expect("create test home");
            Self(path)
        }
    }

    #[test]
    fn arch_storage_usage_separates_actionable_storage_without_following_links() {
        let root = TestDirectory::new();
        let outside = TestDirectory::new();
        fs::create_dir_all(root.0.join("home/archphene/Projects")).expect("home");
        fs::create_dir_all(root.0.join("var/cache/pacman/pkg")).expect("package cache");
        fs::create_dir_all(root.0.join("var/cache/archphene/aur-sources")).expect("build cache");
        fs::create_dir_all(root.0.join("usr/bin")).expect("runtime");
        fs::write(root.0.join("home/archphene/Projects/source.rs"), [1; 8192]).expect("user file");
        fs::hard_link(
            root.0.join("home/archphene/Projects/source.rs"),
            root.0.join("home/archphene/Projects/source-copy.rs"),
        )
        .expect("user hard link");
        fs::write(
            root.0
                .join("var/cache/pacman/pkg/example-1-1-x86_64.pkg.tar.zst"),
            [2; 12_288],
        )
        .expect("package archive");
        fs::write(
            root.0.join("var/cache/archphene/aur-sources/source"),
            [3; 16_384],
        )
        .expect("build source");
        fs::write(root.0.join("usr/bin/tool"), [4; 4096]).expect("runtime file");
        fs::write(outside.0.join("not-owned"), [5; 1024 * 1024]).expect("outside file");
        symlink(
            outside.0.join("not-owned"),
            root.0.join("home/archphene/Projects/external"),
        )
        .expect("external link");

        let usage = arch_storage_usage(&root.0).expect("storage usage");

        assert!(usage.package_downloads.bytes >= 12_288);
        assert!(usage.build_cache.bytes >= 16_384);
        assert!(usage.user_files.bytes >= 8192);
        assert!(usage.user_files.bytes < 1024 * 1024);
        assert!(usage.shared_runtime.bytes >= 4096);
        assert_eq!(
            usage.total_entries(),
            Some(
                usage.package_downloads.entries
                    + usage.shared_runtime.entries
                    + usage.build_cache.entries
                    + usage.user_files.entries
            ),
        );
        assert_eq!(
            usage.total_bytes(),
            Some(
                usage.package_downloads.bytes
                    + usage.shared_runtime.bytes
                    + usage.build_cache.bytes
                    + usage.user_files.bytes
            ),
        );

        fs::remove_file(
            root.0
                .join("var/cache/pacman/pkg/example-1-1-x86_64.pkg.tar.zst"),
        )
        .expect("remove package archive");
        fs::remove_file(root.0.join("var/cache/archphene/aur-sources/source"))
            .expect("remove build source");
        let empty_actionable = arch_storage_usage(&root.0).expect("empty actionable storage");
        assert_eq!(
            empty_actionable.package_downloads,
            AllocatedStorageUsage::default()
        );
        assert_eq!(
            empty_actionable.build_cache,
            AllocatedStorageUsage::default()
        );
    }

    impl Drop for TestDirectory {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    fn read_write_mode() -> OpenMode {
        OpenMode {
            read: true,
            write: true,
            truncate: true,
            append: false,
        }
    }

    #[test]
    fn creates_opens_renames_and_deletes_visible_documents() {
        let home = TestDirectory::new();
        create_document(&home.0, HOME_DOCUMENT_ID, "Projects", true).expect("directory");
        create_document(&home.0, "home/Projects", "notes.txt", false).expect("file");
        let mut document =
            open_document(&home.0, "home/Projects/notes.txt", read_write_mode()).expect("open");
        document.write_all(b"archphene\n").expect("write");
        document.rewind().expect("rewind");
        let mut content = String::new();
        document.read_to_string(&mut content).expect("read");
        assert_eq!(content, "archphene\n");
        drop(document);

        rename_document(&home.0, "home/Projects/notes.txt", "renamed.txt").expect("rename");
        assert!(home.0.join("Projects/renamed.txt").is_file());
        delete_document(&home.0, "home/Projects/renamed.txt").expect("delete file");
        delete_document(&home.0, "home/Projects").expect("delete directory");
        assert!(home.0.read_dir().expect("empty home").next().is_none());
    }

    #[test]
    fn rejects_hidden_traversal_and_root_mutation() {
        let home = TestDirectory::new();
        for name in [
            ".secret",
            "..",
            "a/b",
            "a\\b",
            "line\nbreak",
            "spoof\u{202e}txt",
        ] {
            assert!(validate_visible_name(name).is_err(), "{name:?}");
        }
        assert!(create_document(&home.0, "home/..", "escape", false).is_err());
        assert!(rename_document(&home.0, HOME_DOCUMENT_ID, "other").is_err());
        assert!(delete_document(&home.0, HOME_DOCUMENT_ID).is_err());
    }

    #[test]
    fn exposes_only_reviewed_shell_startup_files() {
        let home = TestDirectory::new();
        fs::write(home.0.join(".bashrc"), b"export EDITOR=vi\n").expect("bashrc");
        fs::write(
            home.0.join(".bash_profile"),
            b"if [[ -r ~/.bashrc ]]; then . ~/.bashrc; fi\n",
        )
        .expect("bash profile");
        fs::write(home.0.join(".zshrc"), b"# zsh\n").expect("zshrc");
        fs::create_dir_all(home.0.join(".config/fish")).expect("fish config directory");
        fs::write(home.0.join(".config/fish/config.fish"), b"# fish\n").expect("fish config");
        fs::write(home.0.join(".secret"), b"secret").expect("private file");

        let mut bashrc = open_shell_startup_document(&home.0, BASHRC_STARTUP_ID, read_write_mode())
            .expect("open bashrc");
        bashrc
            .write_all(b"export PATH=$PATH:$HOME/bin\n")
            .expect("write");
        bashrc.rewind().expect("rewind");
        let mut content = String::new();
        bashrc.read_to_string(&mut content).expect("read");
        assert_eq!(content, "export PATH=$PATH:$HOME/bin\n");
        assert!(
            open_shell_startup_document(
                &home.0,
                BASH_PROFILE_STARTUP_ID,
                OpenMode {
                    read: true,
                    write: false,
                    truncate: false,
                    append: false,
                },
            )
            .is_ok()
        );
        assert!(open_shell_startup_document(&home.0, ZSHRC_STARTUP_ID, read_write_mode()).is_ok());
        assert!(
            open_shell_startup_document(&home.0, FISH_CONFIG_STARTUP_ID, read_write_mode()).is_ok()
        );
        assert!(open_shell_startup_document(&home.0, "secret", read_write_mode()).is_err());
        assert!(open_document(&home.0, "home/.secret", read_write_mode()).is_err());

        fs::remove_file(home.0.join(".bashrc")).expect("remove bashrc");
        symlink("/tmp/host-bashrc", home.0.join(".bashrc")).expect("bashrc symlink");
        assert!(
            open_shell_startup_document(&home.0, BASHRC_STARTUP_ID, read_write_mode()).is_err()
        );

        fs::remove_file(home.0.join(".config/fish/config.fish")).expect("remove fish config");
        fs::remove_dir(home.0.join(".config/fish")).expect("remove fish directory");
        symlink("/tmp", home.0.join(".config/fish")).expect("fish directory symlink");
        assert!(
            open_shell_startup_document(&home.0, FISH_CONFIG_STARTUP_ID, read_write_mode())
                .is_err()
        );
    }

    #[test]
    fn rejects_symlink_files_and_directory_traversal() {
        let home = TestDirectory::new();
        let outside = TestDirectory::new();
        fs::write(outside.0.join("secret"), b"secret").expect("outside file");
        symlink(outside.0.join("secret"), home.0.join("link")).expect("file link");
        symlink(&outside.0, home.0.join("directory-link")).expect("directory link");

        assert!(
            open_document(
                &home.0,
                "home/link",
                OpenMode {
                    read: true,
                    write: false,
                    truncate: false,
                    append: false,
                },
            )
            .is_err()
        );
        assert!(create_document(&home.0, "home/directory-link", "escape", false).is_err());
        assert!(!outside.0.join("escape").exists());
    }

    #[test]
    fn rename_never_replaces_an_existing_document() {
        let home = TestDirectory::new();
        fs::write(home.0.join("source"), b"source").expect("source");
        fs::write(home.0.join("target"), b"target").expect("target");
        assert!(rename_document(&home.0, "home/source", "target").is_err());
        assert_eq!(
            fs::read(home.0.join("source")).expect("source remains"),
            b"source"
        );
        assert_eq!(
            fs::read(home.0.join("target")).expect("target remains"),
            b"target"
        );
    }

    #[test]
    fn imports_atomically_and_numbers_collisions() {
        let home = TestDirectory::new();
        create_document(&home.0, HOME_DOCUMENT_ID, "Downloads", true).expect("downloads");
        let first = import_document(&home.0, "home/Downloads", "project.txt", &mut &b"first"[..])
            .expect("first import");
        let second = import_document(
            &home.0,
            "home/Downloads",
            "project.txt",
            &mut &b"second"[..],
        )
        .expect("second import");
        assert_eq!(first.display_name, "project.txt");
        assert_eq!(first.bytes, 5);
        assert_eq!(second.display_name, "project (2).txt");
        assert_eq!(second.bytes, 6);
        assert_eq!(
            fs::read(home.0.join("Downloads/project.txt")).expect("first"),
            b"first"
        );
        assert_eq!(
            fs::read(home.0.join("Downloads/project (2).txt")).expect("second"),
            b"second"
        );
        assert!(
            home.0
                .join(IMPORT_STAGING_DIRECTORY)
                .read_dir()
                .expect("staging")
                .next()
                .is_none()
        );
    }

    #[test]
    fn document_import_reports_progress_and_cancels_without_publication() {
        let home = TestDirectory::new();
        create_document(&home.0, HOME_DOCUMENT_ID, "Downloads", true).expect("downloads");
        let content = vec![0x5a; 96 * 1024];
        let mut progress = Vec::new();
        let result = import_document_with_progress(
            &home.0,
            "home/Downloads",
            "cancelled.bin",
            &mut content.as_slice(),
            &mut |bytes| {
                progress.push(bytes);
                bytes < 64 * 1024
            },
        );
        assert!(matches!(result, Err(StorageError::TransferCancelled)));
        assert_eq!(progress, [32 * 1024, 64 * 1024]);
        assert!(!home.0.join("Downloads/cancelled.bin").exists());
        assert!(
            home.0
                .join(IMPORT_STAGING_DIRECTORY)
                .read_dir()
                .expect("staging")
                .next()
                .is_none()
        );
    }

    #[test]
    fn document_import_times_out_a_stalled_descriptor_without_publication() {
        let home = TestDirectory::new();
        create_document(&home.0, HOME_DOCUMENT_ID, "Downloads", true).expect("downloads");
        let (source, _held_writer) = UnixStream::pair().expect("descriptor pair");
        let result = import_document_from_fd_with_progress_and_timeout(
            &home.0,
            "home/Downloads",
            "stalled.bin",
            source.as_raw_fd(),
            Duration::from_millis(30),
            |_| true,
        );
        assert!(matches!(result, Err(StorageError::ProviderTimeout)));
        assert!(!home.0.join("Downloads/stalled.bin").exists());
        assert!(
            home.0
                .join(IMPORT_STAGING_DIRECTORY)
                .read_dir()
                .expect("staging")
                .next()
                .is_none()
        );
    }

    #[test]
    fn copies_documents_between_descriptors_without_changing_ownership() {
        let directory = TestDirectory::new();
        let source_path = directory.0.join("source");
        let destination_path = directory.0.join("destination");
        let content = vec![0x5a; 96 * 1024 + 17];
        fs::write(&source_path, &content).expect("source");
        let source = File::open(&source_path).expect("open source");
        let destination = File::create(&destination_path).expect("create destination");

        let bytes =
            copy_document_between_fds(source.as_raw_fd(), destination.as_raw_fd()).expect("copy");
        assert_eq!(bytes, content.len() as u64);
        assert_eq!(fs::read(&destination_path).expect("destination"), content);
        assert!(source.metadata().is_ok());
        assert!(destination.metadata().is_ok());
        assert!(copy_document_between_fds(-1, destination.as_raw_fd()).is_err());
        assert!(copy_document_between_fds(source.as_raw_fd(), -1).is_err());
    }

    #[test]
    fn descriptor_copy_reports_progress_and_stops_at_a_chunk_boundary() {
        let directory = TestDirectory::new();
        let source_path = directory.0.join("source-large.txt");
        let destination_path = directory.0.join("destination-partial.txt");
        fs::write(&source_path, vec![0x5a; 96 * 1024]).expect("write source");
        let source = File::open(&source_path).expect("open source");
        let destination = File::create(&destination_path).expect("create destination");
        let mut progress = Vec::new();

        let result = copy_document_between_fds_with_progress(
            source.as_raw_fd(),
            destination.as_raw_fd(),
            |bytes| {
                progress.push(bytes);
                bytes < 32 * 1024
            },
        );

        assert!(matches!(result, Err(StorageError::TransferCancelled)));
        assert_eq!(progress, vec![32 * 1024]);
        assert_eq!(
            fs::metadata(destination_path)
                .expect("partial metadata")
                .len(),
            32 * 1024,
        );
    }

    #[test]
    fn mirror_import_recursively_publishes_files_and_dot_directories() {
        let home = TestDirectory::new();
        let source = TestDirectory::new();
        fs::create_dir(home.0.join("Projects")).expect("projects");
        fs::write(source.0.join("config"), b"[core]\n").expect("source");
        fs::write(source.0.join("main.rs"), b"fn main() {}\n").expect("source");
        let config = File::open(source.0.join("config")).expect("config");
        let main = File::open(source.0.join("main.rs")).expect("main");

        let mut import = MirrorImport::begin(&home.0, "AndroidProject").expect("begin");
        import.add_directory(".git").expect("dot directory");
        assert_eq!(
            import
                .add_file_from_fd(".git/config", config.as_raw_fd(), Some(7))
                .expect("config"),
            7,
        );
        assert_eq!(
            import
                .add_file_from_fd("main.rs", main.as_raw_fd(), None)
                .expect("main"),
            13,
        );
        let report = import.finish().expect("finish");

        assert_eq!(
            report,
            MirrorImportReport {
                entries: 3,
                bytes: 20,
            },
        );
        assert_eq!(
            fs::read(home.0.join("Projects/AndroidProject/.git/config")).expect("config result"),
            b"[core]\n",
        );
        assert_eq!(
            fs::read(home.0.join("Projects/AndroidProject/main.rs")).expect("main result"),
            b"fn main() {}\n",
        );
        assert!(!home.0.join("Projects/.archphene-mirror-pending").exists());
    }

    #[test]
    fn portal_folder_stream_publishes_nested_files_and_numbers_collisions() {
        let home = TestDirectory::new();
        fs::create_dir(home.0.join("Projects")).expect("projects");
        let mut encoded = PORTAL_FOLDER_MAGIC.to_vec();
        append_portal_path(&mut encoded, PORTAL_FOLDER_DIRECTORY, ".git");
        append_portal_path(&mut encoded, PORTAL_FOLDER_FILE, ".git/config");
        append_portal_data(&mut encoded, b"[core]\n");
        append_portal_data(&mut encoded, b"\trepositoryformatversion = 0\n");
        encoded.push(PORTAL_FOLDER_FILE_END);
        append_portal_path(&mut encoded, PORTAL_FOLDER_FILE, "empty.txt");
        encoded.push(PORTAL_FOLDER_FILE_END);
        encoded.push(PORTAL_FOLDER_END);

        let report =
            import_portal_folder_stream(&home.0, "AndroidProject", &mut encoded.as_slice())
                .expect("first import");
        assert_eq!(
            report,
            PortalFolderImportReport {
                display_name: "AndroidProject".to_owned(),
                entries: 3,
                bytes: 36,
            }
        );
        assert_eq!(
            fs::read(home.0.join("Projects/AndroidProject/.git/config")).expect("config"),
            b"[core]\n\trepositoryformatversion = 0\n"
        );
        assert_eq!(
            fs::metadata(home.0.join("Projects/AndroidProject/empty.txt"))
                .expect("empty")
                .len(),
            0
        );

        let collision =
            import_portal_folder_stream(&home.0, "AndroidProject", &mut encoded.as_slice())
                .expect("collision import");
        assert_eq!(collision.display_name, "AndroidProject (2)");
        assert!(
            home.0
                .join("Projects/AndroidProject (2)/.git/config")
                .is_file()
        );

        import_portal_folder_stream(&home.0, "Android.Project", &mut encoded.as_slice())
            .expect("dotted import");
        let dotted_collision =
            import_portal_folder_stream(&home.0, "Android.Project", &mut encoded.as_slice())
                .expect("dotted collision import");
        assert_eq!(dotted_collision.display_name, "Android.Project (2)");
    }

    #[test]
    fn portal_folder_stream_rejects_malformed_records_and_rolls_back() {
        let home = TestDirectory::new();
        fs::create_dir(home.0.join("Projects")).expect("projects");

        let mut traversal = PORTAL_FOLDER_MAGIC.to_vec();
        append_portal_path(&mut traversal, PORTAL_FOLDER_DIRECTORY, "../escape");
        traversal.push(PORTAL_FOLDER_END);
        assert!(
            import_portal_folder_stream(&home.0, "Traversal", &mut traversal.as_slice()).is_err()
        );

        let mut oversized_chunk = PORTAL_FOLDER_MAGIC.to_vec();
        append_portal_path(&mut oversized_chunk, PORTAL_FOLDER_FILE, "payload.bin");
        oversized_chunk.push(PORTAL_FOLDER_DATA);
        oversized_chunk.extend_from_slice(
            &u32::try_from(MAX_PORTAL_FOLDER_CHUNK_BYTES + 1)
                .expect("bounded test chunk")
                .to_be_bytes(),
        );
        assert!(
            import_portal_folder_stream(&home.0, "Oversized", &mut oversized_chunk.as_slice())
                .is_err()
        );

        let mut zero_chunk = PORTAL_FOLDER_MAGIC.to_vec();
        append_portal_path(&mut zero_chunk, PORTAL_FOLDER_FILE, "payload.bin");
        zero_chunk.push(PORTAL_FOLDER_DATA);
        zero_chunk.extend_from_slice(&0_u32.to_be_bytes());
        assert!(
            import_portal_folder_stream(&home.0, "ZeroChunk", &mut zero_chunk.as_slice()).is_err()
        );

        let mut trailing = PORTAL_FOLDER_MAGIC.to_vec();
        trailing.push(PORTAL_FOLDER_END);
        trailing.push(0xff);
        assert!(
            import_portal_folder_stream(&home.0, "Trailing", &mut trailing.as_slice()).is_err()
        );

        let mut incomplete = PORTAL_FOLDER_MAGIC.to_vec();
        append_portal_path(&mut incomplete, PORTAL_FOLDER_FILE, "incomplete.txt");
        append_portal_data(&mut incomplete, b"partial");
        assert!(
            import_portal_folder_stream(&home.0, "Incomplete", &mut incomplete.as_slice()).is_err()
        );

        let projects = home.0.join("Projects");
        assert!(!projects.join("Traversal").exists());
        assert!(!projects.join("Oversized").exists());
        assert!(!projects.join("ZeroChunk").exists());
        assert!(!projects.join("Trailing").exists());
        assert!(!projects.join("Incomplete").exists());
        assert!(!projects.join(MIRROR_STAGING_DIRECTORY).exists());
        assert!(!home.0.parent().expect("parent").join("escape").exists());
    }

    fn append_portal_path(encoded: &mut Vec<u8>, record: u8, path: &str) {
        encoded.push(record);
        encoded.extend_from_slice(
            &u16::try_from(path.len())
                .expect("bounded test path")
                .to_be_bytes(),
        );
        encoded.extend_from_slice(path.as_bytes());
    }

    fn append_portal_data(encoded: &mut Vec<u8>, data: &[u8]) {
        encoded.push(PORTAL_FOLDER_DATA);
        encoded.extend_from_slice(
            &u32::try_from(data.len())
                .expect("bounded test data")
                .to_be_bytes(),
        );
        encoded.extend_from_slice(data);
    }

    #[test]
    fn mirror_import_persists_an_exact_sync_baseline_before_publication() {
        let root = TestDirectory::new();
        let source = TestDirectory::new();
        fs::create_dir_all(root.0.join("home/archphene/Projects")).expect("projects");
        fs::create_dir_all(root.0.join("var/lib/archphene/storage")).expect("storage");
        fs::write(source.0.join("main.rs"), b"fn main() {}\n").expect("source");
        fs::write(source.0.join("empty"), []).expect("empty source");
        let main = File::open(source.0.join("main.rs")).expect("main");
        let empty = File::open(source.0.join("empty")).expect("empty");

        let mut import = MirrorImport::begin_with_sync_baseline(&root.0, "AndroidProject", [9; 16])
            .expect("begin");
        import.add_directory(".git").expect("directory");
        import
            .add_file_from_fd("main.rs", main.as_raw_fd(), Some(13))
            .expect("main");
        import
            .add_file_from_fd("empty", empty.as_raw_fd(), Some(0))
            .expect("empty");
        import.finish().expect("finish");

        let manifest = load_sync_manifest(&root.0, [9; 16])
            .expect("load")
            .expect("manifest");
        assert_eq!(manifest.project_name(), "AndroidProject");
        assert_eq!(manifest.entries().len(), 3);
        assert_eq!(
            manifest.entries()[0],
            SyncManifestEntry {
                path: ".git".to_owned(),
                fingerprint: SyncFingerprint::directory(),
            },
        );
        assert_eq!(
            manifest.entries()[1],
            SyncManifestEntry {
                path: "empty".to_owned(),
                fingerprint: SyncFingerprint::file(0, Sha256::digest([]).into(),),
            },
        );
        assert_eq!(
            manifest.entries()[2],
            SyncManifestEntry {
                path: "main.rs".to_owned(),
                fingerprint: SyncFingerprint::file(13, Sha256::digest(b"fn main() {}\n").into(),),
            },
        );
        assert!(
            root.0
                .join("home/archphene/Projects/AndroidProject")
                .is_dir()
        );
    }

    #[test]
    fn linux_project_snapshot_hashes_regular_files_and_rejects_symlinks() {
        let root = TestDirectory::new();
        let outside = TestDirectory::new();
        let project = root.0.join("home/archphene/Projects/Project");
        fs::create_dir_all(project.join(".git")).expect("project");
        fs::create_dir_all(root.0.join("var/lib/archphene/storage")).expect("storage");
        fs::write(project.join(".git/config"), b"config\n").expect("config");
        fs::write(project.join("empty"), []).expect("empty");
        fs::write(outside.0.join("outside"), b"outside").expect("outside");
        let baseline =
            SyncManifest::new([8; 16], "Project".to_owned(), Vec::new()).expect("manifest");
        persist_sync_manifest(&root.0, &baseline).expect("persist");

        let snapshot = snapshot_linux_project(&root.0, [8; 16]).expect("snapshot");
        assert_eq!(
            snapshot.entries(),
            [
                SyncManifestEntry {
                    path: ".git".to_owned(),
                    fingerprint: SyncFingerprint::directory(),
                },
                SyncManifestEntry {
                    path: ".git/config".to_owned(),
                    fingerprint: SyncFingerprint::file(7, Sha256::digest(b"config\n").into(),),
                },
                SyncManifestEntry {
                    path: "empty".to_owned(),
                    fingerprint: SyncFingerprint::file(0, Sha256::digest([]).into()),
                },
            ],
        );

        symlink(outside.0.join("outside"), project.join("link")).expect("symlink");
        assert!(matches!(
            snapshot_linux_project(&root.0, [8; 16]),
            Err(StorageError::Io(error)) if error.raw_os_error() == Some(40)
        ));
    }

    #[test]
    fn descriptor_fingerprinting_is_exact_and_rejects_size_races() {
        let source = TestDirectory::new();
        fs::write(source.0.join("file"), b"fingerprint").expect("source");
        let file = File::open(source.0.join("file")).expect("open");
        assert_eq!(
            fingerprint_file_from_fd(file.as_raw_fd(), Some(11)).expect("fingerprint"),
            SyncFingerprint::file(11, Sha256::digest(b"fingerprint").into()),
        );
        assert!(matches!(
            fingerprint_file_from_fd(file.as_raw_fd(), Some(12)),
            Err(StorageError::InvalidDocument),
        ));

        let cancelled = MirrorCancellation::new();
        cancelled.cancel();
        assert!(matches!(
            fingerprint_file_from_fd_cancellable(file.as_raw_fd(), None, &cancelled),
            Err(StorageError::MirrorCancelled),
        ));
    }

    #[test]
    fn local_sync_transactions_revalidate_publish_and_preserve_conflicts() {
        let root = TestDirectory::new();
        let source = TestDirectory::new();
        let project = root.0.join("home/archphene/Projects/Project");
        fs::create_dir_all(&project).expect("project");
        fs::create_dir_all(root.0.join("var/lib/archphene/storage")).expect("storage");
        fs::write(project.join("main"), b"old").expect("old");
        fs::write(source.0.join("android"), b"android").expect("android");
        let baseline =
            SyncManifest::new([6; 16], "Project".to_owned(), Vec::new()).expect("manifest");
        persist_sync_manifest(&root.0, &baseline).expect("persist");
        let old = SyncFingerprint::file(3, Sha256::digest(b"old").into());
        let android = SyncFingerprint::file(7, Sha256::digest(b"android").into());

        let source_file = File::open(source.0.join("android")).expect("source");
        pull_linux_project_file_from_fd(
            &root.0,
            [6; 16],
            "main",
            source_file.as_raw_fd(),
            android,
            Some(old),
        )
        .expect("pull");
        assert_eq!(fs::read(project.join("main")).expect("main"), b"android");
        let mut opened =
            open_linux_project_file(&root.0, [6; 16], "main", android).expect("verified open");
        let mut content = Vec::new();
        opened.read_to_end(&mut content).expect("read");
        assert_eq!(content, b"android");

        fs::write(project.join("main"), b"concurrent").expect("concurrent");
        let fresh_source = File::open(source.0.join("android")).expect("source");
        assert!(matches!(
            pull_linux_project_file_from_fd(
                &root.0,
                [6; 16],
                "main",
                fresh_source.as_raw_fd(),
                android,
                Some(old),
            ),
            Err(StorageError::SyncChanged),
        ));
        assert_eq!(fs::read(project.join("main")).expect("main"), b"concurrent",);

        create_linux_project_directory(&root.0, [6; 16], "created").expect("create directory");
        delete_linux_project_entry(&root.0, [6; 16], "created", SyncFingerprint::directory())
            .expect("delete directory");

        let conflict_source = File::open(source.0.join("android")).expect("source");
        let conflict = preserve_android_conflict_from_fd(
            &root.0,
            [6; 16],
            "main",
            conflict_source.as_raw_fd(),
            android,
        )
        .expect("conflict");
        assert_eq!(
            conflict,
            format!(
                "main.android-conflict-{}",
                hex_digest_prefix(android.sha256, 12),
            ),
        );
        assert_eq!(
            fs::read(project.join(&conflict)).expect("conflict content"),
            b"android",
        );
        assert_eq!(
            preserve_android_conflict_from_fd(
                &root.0,
                [6; 16],
                "main",
                conflict_source.as_raw_fd(),
                android,
            )
            .expect("idempotent conflict"),
            conflict,
        );
        assert!(
            !root
                .0
                .join("var/lib/archphene/storage/.06060606060606060606060606060606.transfer.tmp")
                .exists(),
        );
    }

    #[test]
    fn mirror_paths_reject_traversal_controls_and_backslashes() {
        for path in [
            "",
            ".",
            "..",
            "src/../escape",
            "src//empty",
            "src\\misleading",
            "src/new\nline",
            "src/\u{202e}spoof",
        ] {
            assert!(parse_mirror_path(path).is_err(), "{path:?}");
        }
        assert!(parse_mirror_path(".git/objects").is_ok());
    }

    #[test]
    fn document_and_mirror_paths_bound_segments_while_parsing() {
        let document_limit = vec!["document"; MAX_DOCUMENT_DEPTH].join("/");
        assert_eq!(
            parse_document_id(&format!("home/{document_limit}"))
                .expect("exact document depth")
                .len(),
            MAX_DOCUMENT_DEPTH,
        );
        let document_overflow = format!("home/{document_limit}/overflow");
        assert!(parse_document_id(&document_overflow).is_err());

        let mirror_limit = vec!["project"; MAX_MIRROR_DEPTH].join("/");
        assert_eq!(
            parse_mirror_path(&mirror_limit)
                .expect("exact mirror depth")
                .len(),
            MAX_MIRROR_DEPTH,
        );
        assert!(parse_mirror_path(&format!("{mirror_limit}/overflow")).is_err());
    }

    #[test]
    fn sync_decisions_preserve_every_two_sided_change() {
        const ORIGINAL: SyncFingerprint = SyncFingerprint::file(1, [1; 32]);
        const LINUX_EDIT: SyncFingerprint = SyncFingerprint::file(2, [2; 32]);
        const ANDROID_EDIT: SyncFingerprint = SyncFingerprint::file(3, [3; 32]);

        let cases = [
            (
                Some(ORIGINAL),
                Some(ORIGINAL),
                Some(ORIGINAL),
                SyncAction::Converged,
            ),
            (
                Some(ORIGINAL),
                Some(LINUX_EDIT),
                Some(ORIGINAL),
                SyncAction::PushToAndroid,
            ),
            (
                Some(ORIGINAL),
                Some(ORIGINAL),
                Some(ANDROID_EDIT),
                SyncAction::PullToLinux,
            ),
            (
                Some(ORIGINAL),
                Some(LINUX_EDIT),
                Some(LINUX_EDIT),
                SyncAction::Converged,
            ),
            (
                Some(ORIGINAL),
                Some(LINUX_EDIT),
                Some(ANDROID_EDIT),
                SyncAction::PreserveConflict,
            ),
            (
                Some(ORIGINAL),
                None,
                Some(ORIGINAL),
                SyncAction::DeleteFromAndroid,
            ),
            (
                Some(ORIGINAL),
                Some(ORIGINAL),
                None,
                SyncAction::DeleteFromLinux,
            ),
            (
                Some(ORIGINAL),
                None,
                Some(ANDROID_EDIT),
                SyncAction::PreserveConflict,
            ),
            (
                Some(ORIGINAL),
                Some(LINUX_EDIT),
                None,
                SyncAction::PreserveConflict,
            ),
            (Some(ORIGINAL), None, None, SyncAction::Converged),
            (None, Some(LINUX_EDIT), None, SyncAction::PushToAndroid),
            (None, None, Some(ANDROID_EDIT), SyncAction::PullToLinux),
            (
                None,
                Some(LINUX_EDIT),
                Some(ANDROID_EDIT),
                SyncAction::PreserveConflict,
            ),
            (
                None,
                Some(LINUX_EDIT),
                Some(LINUX_EDIT),
                SyncAction::Converged,
            ),
        ];
        for (baseline, linux, android, expected) in cases {
            assert_eq!(
                decide_sync_action(baseline, linux, android),
                expected,
                "baseline={baseline:?} linux={linux:?} android={android:?}",
            );
        }
    }

    #[test]
    fn sync_decisions_treat_type_changes_as_content_changes() {
        const DIRECTORY: SyncFingerprint = SyncFingerprint::directory();
        const FILE: SyncFingerprint = SyncFingerprint::file(0, [0; 32]);

        assert_eq!(
            decide_sync_action(Some(DIRECTORY), Some(FILE), Some(DIRECTORY)),
            SyncAction::PushToAndroid,
        );
        assert_eq!(
            decide_sync_action(Some(DIRECTORY), Some(DIRECTORY), Some(FILE)),
            SyncAction::PullToLinux,
        );
        assert_eq!(
            decide_sync_action(Some(DIRECTORY), Some(FILE), None),
            SyncAction::PreserveConflict,
        );
    }

    #[test]
    fn sync_plan_merges_three_canonical_snapshots_and_counts_every_action() {
        const ORIGINAL: SyncFingerprint = SyncFingerprint::file(1, [1; 32]);
        const LINUX_EDIT: SyncFingerprint = SyncFingerprint::file(2, [2; 32]);
        const ANDROID_EDIT: SyncFingerprint = SyncFingerprint::file(3, [3; 32]);
        let manifest = |entries: &[(&str, SyncFingerprint)]| {
            SyncManifest::new(
                [4; 16],
                "Project".to_owned(),
                entries
                    .iter()
                    .map(|(path, fingerprint)| SyncManifestEntry {
                        path: (*path).to_owned(),
                        fingerprint: *fingerprint,
                    })
                    .collect(),
            )
            .expect("manifest")
        };
        let baseline = manifest(&[
            ("a", ORIGINAL),
            ("both-gone", ORIGINAL),
            ("conflict", ORIGINAL),
            ("delete-android", ORIGINAL),
            ("delete-linux", ORIGINAL),
            ("linux-edit", ORIGINAL),
            ("remote-edit", ORIGINAL),
        ]);
        let linux = manifest(&[
            ("a", ORIGINAL),
            ("conflict", LINUX_EDIT),
            ("delete-linux", ORIGINAL),
            ("linux-edit", LINUX_EDIT),
            ("new-linux", LINUX_EDIT),
            ("remote-edit", ORIGINAL),
        ]);
        let android = manifest(&[
            ("a", ORIGINAL),
            ("conflict", ANDROID_EDIT),
            ("delete-android", ORIGINAL),
            ("linux-edit", ORIGINAL),
            ("new-android", ANDROID_EDIT),
            ("remote-edit", ANDROID_EDIT),
        ]);

        let plan = create_sync_plan(&baseline, &linux, &android).expect("plan");
        assert_eq!(plan.mapping_id(), [4; 16]);
        assert_eq!(plan.project_name(), "Project");
        assert_eq!(
            plan.summary(),
            SyncPlanSummary {
                converged: 2,
                push_to_android: 2,
                pull_to_linux: 2,
                delete_from_android: 1,
                delete_from_linux: 1,
                conflicts: 1,
            },
        );
        assert_eq!(
            plan.entries()
                .iter()
                .map(|entry| (entry.path.as_str(), entry.action))
                .collect::<Vec<_>>(),
            [
                ("a", SyncAction::Converged),
                ("both-gone", SyncAction::Converged),
                ("conflict", SyncAction::PreserveConflict),
                ("delete-android", SyncAction::DeleteFromAndroid),
                ("delete-linux", SyncAction::DeleteFromLinux),
                ("linux-edit", SyncAction::PushToAndroid),
                ("new-android", SyncAction::PullToLinux),
                ("new-linux", SyncAction::PushToAndroid),
                ("remote-edit", SyncAction::PullToLinux),
            ],
        );
    }

    #[test]
    fn sync_plan_rejects_identity_substitution_and_an_oversized_union() {
        let baseline =
            SyncManifest::new([1; 16], "Project".to_owned(), Vec::new()).expect("baseline");
        let substituted =
            SyncManifest::new([2; 16], "Project".to_owned(), Vec::new()).expect("substituted");
        assert!(matches!(
            create_sync_plan(&baseline, &baseline, &substituted),
            Err(StorageError::InvalidManifest),
        ));

        let entries = |prefix: &str| {
            (0..6_000)
                .map(|index| SyncManifestEntry {
                    path: format!("{prefix}{index:04}"),
                    fingerprint: SyncFingerprint::file(1, [1; 32]),
                })
                .collect()
        };
        let linux = SyncManifest::new([1; 16], "Project".to_owned(), entries("l")).expect("linux");
        let android =
            SyncManifest::new([1; 16], "Project".to_owned(), entries("r")).expect("android");
        assert!(matches!(
            create_sync_plan(&baseline, &linux, &android),
            Err(StorageError::ManifestTooLarge),
        ));
    }

    #[test]
    fn reconciled_baseline_advances_only_converged_paths() {
        const OLD: SyncFingerprint = SyncFingerprint::file(1, [1; 32]);
        const NEW: SyncFingerprint = SyncFingerprint::file(2, [2; 32]);
        const REMOTE: SyncFingerprint = SyncFingerprint::file(3, [3; 32]);
        let manifest = |entries: &[(&str, SyncFingerprint)]| {
            SyncManifest::new(
                [3; 16],
                "Project".to_owned(),
                entries
                    .iter()
                    .map(|(path, fingerprint)| SyncManifestEntry {
                        path: (*path).to_owned(),
                        fingerprint: *fingerprint,
                    })
                    .collect(),
            )
            .expect("manifest")
        };
        let previous = manifest(&[("deleted", OLD), ("resolved", OLD), ("unresolved", OLD)]);
        let linux = manifest(&[
            ("conflict-copy", REMOTE),
            ("new-equal", NEW),
            ("resolved", NEW),
            ("unresolved", NEW),
        ]);
        let android = manifest(&[
            ("resolved", NEW),
            ("unresolved", REMOTE),
            ("new-equal", NEW),
        ]);

        let reconciled = reconcile_sync_baseline(&previous, &linux, &android).expect("reconcile");
        assert_eq!(
            reconciled.entries(),
            [
                SyncManifestEntry {
                    path: "new-equal".to_owned(),
                    fingerprint: NEW,
                },
                SyncManifestEntry {
                    path: "resolved".to_owned(),
                    fingerprint: NEW,
                },
                SyncManifestEntry {
                    path: "unresolved".to_owned(),
                    fingerprint: OLD,
                },
            ],
        );
    }

    #[test]
    fn sync_manifest_is_canonical_bounded_and_round_trips_dotfiles() {
        let manifest = SyncManifest::new(
            [7; 16],
            "AndroidProject".to_owned(),
            vec![
                SyncManifestEntry {
                    path: "src/main.rs".to_owned(),
                    fingerprint: SyncFingerprint::file(13, [3; 32]),
                },
                SyncManifestEntry {
                    path: ".git/config".to_owned(),
                    fingerprint: SyncFingerprint::file(7, [2; 32]),
                },
                SyncManifestEntry {
                    path: ".git".to_owned(),
                    fingerprint: SyncFingerprint::directory(),
                },
            ],
        )
        .expect("manifest");
        assert_eq!(manifest.mapping_id(), [7; 16]);
        assert_eq!(manifest.project_name(), "AndroidProject");
        assert_eq!(
            manifest
                .entries()
                .iter()
                .map(|entry| entry.path.as_str())
                .collect::<Vec<_>>(),
            [".git", ".git/config", "src/main.rs"],
        );

        let encoded = manifest.encode().expect("encode");
        assert!(encoded.len() < MAX_SYNC_MANIFEST_BYTES);
        assert_eq!(SyncManifest::decode(&encoded).expect("decode"), manifest);
    }

    #[test]
    fn sync_manifest_rejects_duplicates_invalid_metadata_and_corruption() {
        assert!(matches!(
            SyncManifest::new([0; 16], "Project".to_owned(), Vec::new()),
            Err(StorageError::InvalidManifest),
        ));
        let duplicate = vec![
            SyncManifestEntry {
                path: "same".to_owned(),
                fingerprint: SyncFingerprint::file(1, [1; 32]),
            },
            SyncManifestEntry {
                path: "same".to_owned(),
                fingerprint: SyncFingerprint::file(1, [1; 32]),
            },
        ];
        assert!(matches!(
            SyncManifest::new([1; 16], "Project".to_owned(), duplicate),
            Err(StorageError::InvalidManifest),
        ));
        assert!(matches!(
            SyncManifest::new(
                [1; 16],
                "Project".to_owned(),
                vec![SyncManifestEntry {
                    path: "directory".to_owned(),
                    fingerprint: SyncFingerprint {
                        kind: SyncEntryKind::Directory,
                        bytes: 1,
                        sha256: [0; 32],
                    },
                }],
            ),
            Err(StorageError::InvalidManifest),
        ));

        let manifest = SyncManifest::new(
            [2; 16],
            "Project".to_owned(),
            vec![SyncManifestEntry {
                path: "file".to_owned(),
                fingerprint: SyncFingerprint::file(4, [4; 32]),
            }],
        )
        .expect("manifest");
        let encoded = manifest.encode().expect("encode");
        for mutation in [
            (0, b'X'),
            (8, 2),
            (SYNC_MANIFEST_HEADER_BYTES + "Project".len() + 2, 9),
            (SYNC_MANIFEST_HEADER_BYTES + "Project".len() + 3, 1),
        ] {
            let mut corrupt = encoded.clone();
            corrupt[mutation.0] = mutation.1;
            assert!(
                SyncManifest::decode(&corrupt).is_err(),
                "mutation={mutation:?}",
            );
        }
        let mut trailing = encoded;
        trailing.push(0);
        assert!(matches!(
            SyncManifest::decode(&trailing),
            Err(StorageError::InvalidManifest),
        ));
    }

    #[test]
    fn sync_manifest_store_recovers_temp_and_atomically_replaces() {
        let root = TestDirectory::new();
        fs::create_dir_all(root.0.join("var/lib/archphene/storage")).expect("state directory");
        let first = SyncManifest::new(
            [9; 16],
            "Project".to_owned(),
            vec![SyncManifestEntry {
                path: "first".to_owned(),
                fingerprint: SyncFingerprint::file(5, [1; 32]),
            }],
        )
        .expect("first");
        persist_sync_manifest(&root.0, &first).expect("persist first");
        assert_eq!(
            load_sync_manifest(&root.0, [9; 16]).expect("load first"),
            Some(first),
        );

        let state = root.0.join("var/lib/archphene/storage");
        let identifier = "09".repeat(16);
        fs::write(state.join(format!(".{identifier}.tmp")), b"interrupted")
            .expect("stale temporary");
        let second = SyncManifest::new(
            [9; 16],
            "Project".to_owned(),
            vec![SyncManifestEntry {
                path: "second".to_owned(),
                fingerprint: SyncFingerprint::file(6, [2; 32]),
            }],
        )
        .expect("second");
        persist_sync_manifest(&root.0, &second).expect("replace");
        assert_eq!(
            load_sync_manifest(&root.0, [9; 16]).expect("load second"),
            Some(second),
        );
        assert!(!state.join(format!(".{identifier}.tmp")).exists());
    }

    #[test]
    fn sync_manifest_read_allocates_exactly_and_rejects_size_changes() {
        let expected = b"bounded manifest";
        assert_eq!(
            read_exact_manifest_bytes(&mut &expected[..], expected.len()).expect("exact manifest"),
            expected,
        );
        assert!(matches!(
            read_exact_manifest_bytes(&mut &expected[..expected.len() - 1], expected.len()),
            Err(StorageError::InvalidManifest),
        ));
        assert!(matches!(
            read_exact_manifest_bytes(&mut &expected[..], expected.len() - 1),
            Err(StorageError::InvalidManifest),
        ));
    }

    #[test]
    fn sync_manifest_store_rejects_symlinks_and_mapping_substitution() {
        let root = TestDirectory::new();
        let outside = TestDirectory::new();
        let state = root.0.join("var/lib/archphene/storage");
        fs::create_dir_all(&state).expect("state directory");
        fs::write(outside.0.join("sentinel"), b"outside").expect("sentinel");
        let first = SyncManifest::new([1; 16], "Project".to_owned(), Vec::new()).expect("manifest");
        persist_sync_manifest(&root.0, &first).expect("persist");

        let first_name = format!("{}.v1", "01".repeat(16));
        let second_name = format!("{}.v1", "02".repeat(16));
        fs::copy(state.join(&first_name), state.join(&second_name)).expect("substitute");
        assert!(matches!(
            load_sync_manifest(&root.0, [2; 16]),
            Err(StorageError::InvalidManifest),
        ));

        fs::remove_file(state.join(&first_name)).expect("remove manifest");
        symlink(&outside.0, state.join(&first_name)).expect("manifest symlink");
        assert!(persist_sync_manifest(&root.0, &first).is_err());
        assert_eq!(
            fs::read(outside.0.join("sentinel")).expect("outside remains"),
            b"outside",
        );
    }

    #[test]
    fn mirror_import_is_non_replacing_and_cleans_failed_transactions() {
        let home = TestDirectory::new();
        let source = TestDirectory::new();
        fs::create_dir(home.0.join("Projects")).expect("projects");
        fs::create_dir(home.0.join("Projects/Existing")).expect("existing");
        assert!(matches!(
            MirrorImport::begin(&home.0, "Existing"),
            Err(StorageError::MirrorExists),
        ));

        fs::write(source.0.join("changing"), b"short").expect("source");
        let changing = File::open(source.0.join("changing")).expect("changing");
        let mut import = MirrorImport::begin(&home.0, "Failed").expect("begin");
        assert!(
            import
                .add_file_from_fd("changing", changing.as_raw_fd(), Some(99))
                .is_err(),
        );
        drop(import);
        assert!(!home.0.join("Projects/Failed").exists());
        assert!(!home.0.join("Projects/.archphene-mirror-pending").exists());
        assert!(home.0.join("Projects/Existing").is_dir());
    }

    #[test]
    fn mirror_import_recovers_stale_staging_without_following_a_symlink() {
        let home = TestDirectory::new();
        let outside = TestDirectory::new();
        fs::create_dir(home.0.join("Projects")).expect("projects");
        fs::write(outside.0.join("sentinel"), b"outside").expect("sentinel");
        symlink(
            &outside.0,
            home.0.join("Projects/.archphene-mirror-pending"),
        )
        .expect("stale symlink");

        let import = MirrorImport::begin(&home.0, "Recovered").expect("recover");
        drop(import);
        assert_eq!(
            fs::read(outside.0.join("sentinel")).expect("outside remains"),
            b"outside",
        );
        assert!(!home.0.join("Projects/.archphene-mirror-pending").exists());
    }

    #[test]
    fn mirror_cancellation_stops_work_and_discards_staging() {
        let home = TestDirectory::new();
        let source = TestDirectory::new();
        fs::create_dir(home.0.join("Projects")).expect("projects");
        fs::write(source.0.join("file"), [7; 64 * 1024]).expect("source");
        let file = File::open(source.0.join("file")).expect("file");
        let mut import = MirrorImport::begin(&home.0, "Cancelled").expect("begin");
        let cancellation = import.cancellation();
        cancellation.cancel();
        assert!(matches!(
            import.add_file_from_fd("file", file.as_raw_fd(), None),
            Err(StorageError::MirrorCancelled),
        ));
        drop(import);
        assert!(!home.0.join("Projects/Cancelled").exists());
        assert!(!home.0.join("Projects/.archphene-mirror-pending").exists());
    }

    #[test]
    fn import_recovers_a_stale_regular_staging_file() {
        let home = TestDirectory::new();
        create_document(&home.0, HOME_DOCUMENT_ID, "Downloads", true).expect("downloads");
        fs::create_dir(home.0.join(IMPORT_STAGING_DIRECTORY)).expect("staging");
        fs::write(
            home.0
                .join(IMPORT_STAGING_DIRECTORY)
                .join(IMPORT_STAGING_FILE),
            b"partial",
        )
        .expect("stale pending");
        import_document(
            &home.0,
            "home/Downloads",
            "recovered.txt",
            &mut &b"complete"[..],
        )
        .expect("recover import");
        assert_eq!(
            fs::read(home.0.join("Downloads/recovered.txt")).expect("recovered"),
            b"complete"
        );
    }

    #[test]
    fn import_rejects_unsafe_names_and_staging_substitution() {
        let home = TestDirectory::new();
        create_document(&home.0, HOME_DOCUMENT_ID, "Downloads", true).expect("downloads");
        assert!(import_document(&home.0, "home/Downloads", "../escape", &mut &b"no"[..],).is_err());
        fs::create_dir(home.0.join(IMPORT_STAGING_DIRECTORY)).expect("staging");
        symlink(
            home.0.join("Downloads"),
            home.0
                .join(IMPORT_STAGING_DIRECTORY)
                .join(IMPORT_STAGING_FILE),
        )
        .expect("pending symlink");
        assert!(import_document(&home.0, "home/Downloads", "safe.txt", &mut &b"no"[..],).is_err());
        assert!(!home.0.join("Downloads/safe.txt").exists());
    }
}
