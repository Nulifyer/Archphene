#![deny(unsafe_code)]

use std::ffi::{CString, OsStr};
use std::fmt;
use std::fs::{self, File};
use std::io::{self, Read, Write};
use std::os::fd::{AsRawFd, RawFd};
use std::os::unix::ffi::OsStrExt;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

mod sys {
    #![allow(unsafe_code)]

    use std::ffi::CStr;
    use std::fs::File;
    use std::io;
    use std::os::fd::{FromRawFd, RawFd};
    use std::os::raw::{c_char, c_int, c_long, c_uint};

    pub const O_RDONLY: c_int = 0;
    pub const O_WRONLY: c_int = 1;
    pub const O_RDWR: c_int = 2;
    pub const O_CREAT: c_int = 0o100;
    pub const O_EXCL: c_int = 0o200;
    pub const O_TRUNC: c_int = 0o1000;
    pub const O_APPEND: c_int = 0o2000;
    pub const O_NONBLOCK: c_int = 0o4000;
    pub const O_CLOEXEC: c_int = 0o2000000;
    pub const O_DIRECTORY: c_int = 0o200000;
    pub const O_NOFOLLOW: c_int = 0o400000;
    pub const AT_REMOVEDIR: c_int = 0x200;
    const F_DUPFD_CLOEXEC: c_int = 1030;
    const RENAME_NOREPLACE: c_uint = 1;

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
pub const MAX_DOCUMENT_ID_BYTES: usize = 1024;
pub const MAX_DOCUMENT_DEPTH: usize = 32;
pub const MAX_DOCUMENT_NAME_BYTES: usize = 255;
pub const MAX_IMPORT_BYTES: u64 = 16 * 1024 * 1024 * 1024;
pub const MAX_MIRROR_ENTRIES: u32 = 10_000;
pub const MAX_MIRROR_DEPTH: usize = 64;
pub const MAX_MIRROR_PATH_BYTES: usize = 4 * 1024;
pub const MAX_MIRROR_FILE_BYTES: u64 = 2 * 1024 * 1024 * 1024;
pub const MAX_MIRROR_TOTAL_BYTES: u64 = 16 * 1024 * 1024 * 1024;
pub const MAX_SYNC_MANIFEST_BYTES: usize = 4 * 1024 * 1024;

const IMPORT_STAGING_DIRECTORY: &str = ".archphene-import";
const IMPORT_STAGING_FILE: &str = "pending";
const MAX_IMPORT_COLLISIONS: u32 = 999;
const MIRROR_STAGING_DIRECTORY: &str = ".archphene-mirror-pending";
const SYNC_MANIFEST_MAGIC: &[u8; 8] = b"ARCSYNC1";
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

#[derive(Debug)]
pub enum StorageError {
    InvalidRoot,
    InvalidDocument,
    HiddenDocument,
    RootMutation,
    ImportTooLarge,
    ImportCollision,
    MirrorBusy,
    MirrorExists,
    MirrorTooLarge,
    MirrorCancelled,
    InvalidManifest,
    ManifestTooLarge,
    Io(io::Error),
}

impl fmt::Display for StorageError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidRoot => formatter.write_str("invalid Archphene home root"),
            Self::InvalidDocument => formatter.write_str("invalid Archphene document"),
            Self::HiddenDocument => formatter.write_str("private Archphene document"),
            Self::RootMutation => formatter.write_str("cannot mutate Archphene home"),
            Self::ImportTooLarge => formatter.write_str("Android document exceeds 16 GiB"),
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
            Self::Io(error) => write!(formatter, "Archphene document I/O error: {error}"),
        }
    }
}

pub struct MirrorImport {
    staging_path: PathBuf,
    projects: File,
    staging: File,
    target_name: CString,
    entries: u32,
    bytes: u64,
    published: bool,
    cancellation: MirrorCancellation,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct MirrorImportReport {
    pub entries: u32,
    pub bytes: u64,
}

#[derive(Clone)]
pub struct MirrorCancellation(Arc<AtomicBool>);

impl MirrorCancellation {
    pub fn cancel(&self) {
        self.0.store(true, Ordering::Release);
    }

    fn is_cancelled(&self) -> bool {
        self.0.load(Ordering::Acquire)
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
    let file = match sys::open_at(
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
    let mut encoded = Vec::with_capacity(length);
    file.take((MAX_SYNC_MANIFEST_BYTES + 1) as u64)
        .read_to_end(&mut encoded)?;
    if encoded.len() != length {
        return Err(StorageError::InvalidManifest);
    }
    let manifest = SyncManifest::decode(&encoded)?;
    if manifest.mapping_id != mapping_id {
        return Err(StorageError::InvalidManifest);
    }
    Ok(Some(manifest))
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
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut identifier = String::with_capacity(32);
    for byte in mapping_id {
        identifier.push(HEX[(byte >> 4) as usize] as char);
        identifier.push(HEX[(byte & 0x0f) as usize] as char);
    }
    let final_name =
        CString::new(format!("{identifier}.v1")).map_err(|_| StorageError::InvalidManifest)?;
    let temporary_name =
        CString::new(format!(".{identifier}.tmp")).map_err(|_| StorageError::InvalidManifest)?;
    Ok((final_name, temporary_name))
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
        self.reserve_entry()?;
        let segments = parse_mirror_path(relative_path)?;
        let (parent, leaf) = open_mirror_parent(&self.staging, &segments)?;
        let mut destination = sys::open_at(
            parent.as_raw_fd(),
            &leaf,
            sys::O_WRONLY | sys::O_CREAT | sys::O_EXCL | sys::O_CLOEXEC | sys::O_NOFOLLOW,
            0o600,
        )?;
        let mut source = sys::duplicate(source_descriptor)?;
        let result = (|| {
            let mut copied = 0_u64;
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
            Ok(copied)
        })();
        drop(destination);
        if result.is_err() {
            let _ = sys::unlink_at(parent.as_raw_fd(), &leaf, false);
            let _ = parent.sync_all();
        } else {
            parent.sync_all()?;
        }
        result
    }

    pub fn finish(mut self) -> Result<MirrorImportReport, StorageError> {
        self.check_cancelled()?;
        self.staging.sync_all()?;
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
    let name = match startup_id {
        BASHRC_STARTUP_ID => c_string(OsStr::new(".bashrc"))?,
        BASH_PROFILE_STARTUP_ID => c_string(OsStr::new(".bash_profile"))?,
        _ => return Err(StorageError::InvalidDocument),
    };
    let home = open_directory(root, &[])?;
    open_regular_file(&home, &name, mode)
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
    if source_descriptor < 0 {
        return Err(StorageError::InvalidDocument);
    }
    let mut source = sys::duplicate(source_descriptor)?;
    import_document(root, parent_id, display_name, &mut source)
}

pub fn import_document<R: Read>(
    root: &Path,
    parent_id: &str,
    display_name: &str,
    source: &mut R,
) -> Result<ImportReport, StorageError> {
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
        let mut bytes = 0_u64;
        let mut buffer = [0_u8; 32 * 1024];
        loop {
            let count = source.read(&mut buffer)?;
            if count == 0 {
                break;
            }
            bytes = bytes
                .checked_add(count as u64)
                .ok_or(StorageError::ImportTooLarge)?;
            if bytes > MAX_IMPORT_BYTES {
                return Err(StorageError::ImportTooLarge);
            }
            pending.write_all(&buffer[..count])?;
        }
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
    let segments: Vec<&str> = relative.split('/').collect();
    if segments.is_empty() || segments.len() > MAX_DOCUMENT_DEPTH {
        return Err(StorageError::InvalidDocument);
    }
    for segment in &segments {
        validate_visible_name(segment)?;
    }
    Ok(segments)
}

fn parse_mirror_path(relative_path: &str) -> Result<Vec<&str>, StorageError> {
    if relative_path.is_empty() || relative_path.len() > MAX_MIRROR_PATH_BYTES {
        return Err(StorageError::InvalidDocument);
    }
    let segments: Vec<&str> = relative_path.split('/').collect();
    if segments.is_empty() || segments.len() > MAX_MIRROR_DEPTH {
        return Err(StorageError::InvalidDocument);
    }
    for segment in &segments {
        validate_project_name(segment)?;
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::io::{Read, Seek, Write};
    use std::os::unix::fs::symlink;
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
        assert!(open_shell_startup_document(&home.0, "secret", read_write_mode()).is_err());
        assert!(open_document(&home.0, "home/.secret", read_write_mode()).is_err());

        fs::remove_file(home.0.join(".bashrc")).expect("remove bashrc");
        symlink("/tmp/host-bashrc", home.0.join(".bashrc")).expect("bashrc symlink");
        assert!(
            open_shell_startup_document(&home.0, BASHRC_STARTUP_ID, read_write_mode()).is_err()
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
