#![deny(unsafe_code)]

use std::ffi::{CString, OsStr};
use std::fmt;
use std::fs::File;
use std::io;
use std::os::fd::AsRawFd;
use std::os::unix::ffi::OsStrExt;
use std::path::Path;

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

    pub fn rename_noreplace_at(
        directory: RawFd,
        old_name: &CStr,
        new_name: &CStr,
    ) -> io::Result<()> {
        // SAFETY: both names are NUL-terminated and remain live. The syscall
        // receives the same valid directory descriptor for source and target.
        let result = unsafe {
            syscall(
                SYS_RENAMEAT2,
                directory,
                old_name.as_ptr(),
                directory,
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
}

pub const HOME_DOCUMENT_ID: &str = "home";
pub const MAX_DOCUMENT_ID_BYTES: usize = 1024;
pub const MAX_DOCUMENT_DEPTH: usize = 32;
pub const MAX_DOCUMENT_NAME_BYTES: usize = 255;

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
    Io(io::Error),
}

impl fmt::Display for StorageError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidRoot => formatter.write_str("invalid Archphene home root"),
            Self::InvalidDocument => formatter.write_str("invalid Archphene document"),
            Self::HiddenDocument => formatter.write_str("private Archphene document"),
            Self::RootMutation => formatter.write_str("cannot mutate Archphene home"),
            Self::Io(error) => write!(formatter, "Archphene document I/O error: {error}"),
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
    if !mode.read && !mode.write || mode.truncate && !mode.write || mode.append && !mode.write {
        return Err(StorageError::InvalidDocument);
    }
    if mode.truncate && mode.append {
        return Err(StorageError::InvalidDocument);
    }
    let segments = parse_document_id(document_id)?;
    if segments.is_empty() {
        return Err(StorageError::InvalidDocument);
    }
    let (parent, leaf) = open_parent(root, &segments)?;
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
    let file = sys::open_at(parent.as_raw_fd(), &leaf, flags, 0)?;
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
    sys::rename_noreplace_at(parent.as_raw_fd(), &old_name, &new_name)?;
    parent.sync_all()?;
    Ok(())
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
    if document_id.as_bytes().len() > MAX_DOCUMENT_ID_BYTES {
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
}
