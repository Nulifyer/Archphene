#![forbid(unsafe_code)]

use std::fmt;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::os::unix::fs::PermissionsExt;
use std::path::{Component, Path, PathBuf};

pub const ROOT_LAYOUT_VERSION: u32 = 1;

const VERSION_CONTENT: &[u8] = b"archphene-root-v1\n";
const VERSION_FILE: &str = ".archphene-root-version";
const VERSION_TEMP_FILE: &str = ".archphene-root-version.tmp";
const MAX_ROOT_PATH_BYTES: usize = 1024;
const DEFAULT_BASHRC: &[u8] = b"# Created once by Archphene; this file belongs to the user.\n\
case $- in\n\
  *i*) ;;\n\
  *) return ;;\n\
esac\n\
PS1='archphene:\\w$ '\n";
const DEFAULT_BASH_PROFILE: &[u8] =
    b"# Created once by Archphene; this file belongs to the user.\n\
if [[ -r ~/.bashrc ]]; then\n\
  . ~/.bashrc\n\
fi\n";

const DIRECTORIES: &[(&str, u32)] = &[
    ("usr", 0o755),
    ("etc", 0o755),
    ("var", 0o755),
    ("var/lib", 0o755),
    ("var/lib/archphene", 0o700),
    ("var/lib/pacman", 0o755),
    ("var/lib/pacman/sync", 0o755),
    ("var/cache", 0o755),
    ("var/cache/pacman", 0o755),
    ("var/cache/pacman/pkg", 0o755),
    ("var/log", 0o755),
    ("opt", 0o755),
    ("home", 0o755),
    ("home/archphene", 0o700),
    ("home/archphene/Documents", 0o700),
    ("home/archphene/Downloads", 0o700),
    ("tmp", 0o1777),
    ("run", 0o700),
    ("mnt", 0o755),
    ("mnt/android", 0o755),
];

#[derive(Debug)]
pub enum RootError {
    InvalidPath,
    InvalidEntry(PathBuf),
    VersionMismatch,
    Io(io::Error),
}

impl fmt::Display for RootError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidPath => formatter.write_str("invalid Arch root path"),
            Self::InvalidEntry(path) => {
                write!(
                    formatter,
                    "unsafe or non-directory root entry: {}",
                    path.display()
                )
            }
            Self::VersionMismatch => formatter.write_str("unsupported Arch root layout version"),
            Self::Io(error) => write!(formatter, "Arch root I/O error: {error}"),
        }
    }
}

impl std::error::Error for RootError {}

impl From<io::Error> for RootError {
    fn from(error: io::Error) -> Self {
        Self::Io(error)
    }
}

#[derive(Debug)]
pub struct ArchRoot {
    path: PathBuf,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct BootstrapReport {
    pub created_directories: u32,
    pub reused_existing_root: bool,
}

impl ArchRoot {
    pub fn bootstrap(path: &Path) -> Result<(Self, BootstrapReport), RootError> {
        validate_root_path(path)?;
        let existed = path.try_exists()?;
        let mut created_directories = 0_u32;
        ensure_directory(path, 0o700, &mut created_directories)?;
        for (relative, mode) in DIRECTORIES {
            ensure_directory(&path.join(relative), *mode, &mut created_directories)?;
        }
        ensure_user_file(&path.join("home/archphene/.bashrc"), DEFAULT_BASHRC)?;
        ensure_user_file(
            &path.join("home/archphene/.bash_profile"),
            DEFAULT_BASH_PROFILE,
        )?;
        ensure_version(path)?;
        Ok((
            Self {
                path: path.to_path_buf(),
            },
            BootstrapReport {
                created_directories,
                reused_existing_root: existed,
            },
        ))
    }

    pub fn path(&self) -> &Path {
        &self.path
    }
}

fn validate_root_path(path: &Path) -> Result<(), RootError> {
    let bytes = path.as_os_str().as_encoded_bytes();
    if !path.is_absolute() || bytes.is_empty() || bytes.len() > MAX_ROOT_PATH_BYTES {
        return Err(RootError::InvalidPath);
    }
    for component in path.components() {
        if matches!(component, Component::ParentDir | Component::CurDir) {
            return Err(RootError::InvalidPath);
        }
    }
    Ok(())
}

fn ensure_directory(
    path: &Path,
    mode: u32,
    created_directories: &mut u32,
) -> Result<(), RootError> {
    match fs::symlink_metadata(path) {
        Ok(metadata) => {
            if metadata.file_type().is_symlink() || !metadata.is_dir() {
                return Err(RootError::InvalidEntry(path.to_path_buf()));
            }
            if metadata.permissions().mode() & 0o7777 != mode {
                fs::set_permissions(path, fs::Permissions::from_mode(mode))?;
            }
            Ok(())
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            fs::create_dir(path)?;
            fs::set_permissions(path, fs::Permissions::from_mode(mode))?;
            *created_directories = created_directories.saturating_add(1);
            Ok(())
        }
        Err(error) => Err(RootError::Io(error)),
    }
}

fn ensure_version(root: &Path) -> Result<(), RootError> {
    let version_path = root.join(VERSION_FILE);
    match fs::symlink_metadata(&version_path) {
        Ok(metadata) => {
            if metadata.file_type().is_symlink() || !metadata.is_file() {
                return Err(RootError::InvalidEntry(version_path));
            }
            if metadata.permissions().mode() & 0o7777 != 0o600 {
                fs::set_permissions(&version_path, fs::Permissions::from_mode(0o600))?;
            }
            if metadata.len() != VERSION_CONTENT.len() as u64 {
                return Err(RootError::VersionMismatch);
            }
            let mut file = File::open(&version_path)?;
            let mut content = [0_u8; VERSION_CONTENT.len()];
            file.read_exact(&mut content)?;
            if content != VERSION_CONTENT {
                return Err(RootError::VersionMismatch);
            }
            return Ok(());
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => {}
        Err(error) => return Err(RootError::Io(error)),
    }

    let temporary_path = root.join(VERSION_TEMP_FILE);
    match fs::symlink_metadata(&temporary_path) {
        Ok(metadata) => {
            if metadata.file_type().is_symlink() || !metadata.is_file() {
                return Err(RootError::InvalidEntry(temporary_path));
            }
            fs::remove_file(&temporary_path)?;
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => {}
        Err(error) => return Err(RootError::Io(error)),
    }
    let mut file = OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(&temporary_path)?;
    file.set_permissions(fs::Permissions::from_mode(0o600))?;
    file.write_all(VERSION_CONTENT)?;
    file.sync_all()?;
    drop(file);
    fs::rename(temporary_path, version_path)?;
    Ok(())
}

fn ensure_user_file(path: &Path, content: &[u8]) -> Result<(), RootError> {
    match fs::symlink_metadata(path) {
        Ok(metadata) => {
            if metadata.file_type().is_symlink() || !metadata.is_file() {
                return Err(RootError::InvalidEntry(path.to_path_buf()));
            }
            return Ok(());
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => {}
        Err(error) => return Err(RootError::Io(error)),
    }
    let mut file = OpenOptions::new().create_new(true).write(true).open(path)?;
    file.set_permissions(fs::Permissions::from_mode(0o600))?;
    file.write_all(content)?;
    file.sync_all()?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};

    static TEST_ID: AtomicU64 = AtomicU64::new(1);

    struct TestDirectory(PathBuf);

    impl TestDirectory {
        fn new() -> Self {
            let id = TEST_ID.fetch_add(1, Ordering::Relaxed);
            let path = std::env::temp_dir()
                .join(format!("archphene-root-test-{}-{id}", std::process::id()));
            Self(path)
        }
    }

    impl Drop for TestDirectory {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn bootstrap_creates_and_reuses_the_bounded_layout() {
        let temporary = TestDirectory::new();
        let (root, first) = ArchRoot::bootstrap(&temporary.0).expect("first bootstrap");
        assert!(!first.reused_existing_root);
        assert!(first.created_directories > 0);
        assert_eq!(root.path(), temporary.0);
        assert!(temporary.0.join("var/lib/pacman").is_dir());
        assert!(temporary.0.join("var/lib/pacman/sync").is_dir());
        assert!(temporary.0.join("home/archphene").is_dir());
        assert!(temporary.0.join("home/archphene/Documents").is_dir());
        assert!(temporary.0.join("home/archphene/Downloads").is_dir());
        assert!(temporary.0.join("mnt/android").is_dir());
        assert_eq!(
            fs::read(temporary.0.join("home/archphene/.bashrc")).expect("default bashrc"),
            DEFAULT_BASHRC,
        );

        let (_, second) = ArchRoot::bootstrap(&temporary.0).expect("second bootstrap");
        assert!(second.reused_existing_root);
        assert_eq!(second.created_directories, 0);

        fs::set_permissions(
            temporary.0.join(VERSION_FILE),
            fs::Permissions::from_mode(0o644),
        )
        .expect("weaken marker mode");
        fs::set_permissions(
            temporary.0.join("home/archphene"),
            fs::Permissions::from_mode(0o755),
        )
        .expect("weaken test mode");
        ArchRoot::bootstrap(&temporary.0).expect("mode repair");
        assert_eq!(
            fs::metadata(temporary.0.join(VERSION_FILE))
                .expect("marker metadata")
                .permissions()
                .mode()
                & 0o7777,
            0o600
        );
        assert_eq!(
            fs::metadata(temporary.0.join("home/archphene"))
                .expect("home metadata")
                .permissions()
                .mode()
                & 0o7777,
            0o700
        );
        fs::write(
            temporary.0.join("home/archphene/.bashrc"),
            b"# user customization\n",
        )
        .expect("custom bashrc");
        ArchRoot::bootstrap(&temporary.0).expect("preserve user file");
        assert_eq!(
            fs::read(temporary.0.join("home/archphene/.bashrc")).expect("custom bashrc"),
            b"# user customization\n",
        );
    }

    #[test]
    fn bootstrap_rejects_a_symlinked_layout_entry() {
        let temporary = TestDirectory::new();
        fs::create_dir(&temporary.0).expect("test root");
        std::os::unix::fs::symlink("/tmp", temporary.0.join("usr")).expect("test symlink");
        assert!(matches!(
            ArchRoot::bootstrap(&temporary.0),
            Err(RootError::InvalidEntry(_))
        ));
    }

    #[test]
    fn bootstrap_rejects_a_symlinked_user_startup_file() {
        let temporary = TestDirectory::new();
        ArchRoot::bootstrap(&temporary.0).expect("initial bootstrap");
        fs::remove_file(temporary.0.join("home/archphene/.bashrc")).expect("remove bashrc");
        std::os::unix::fs::symlink(
            "/tmp/host-bashrc",
            temporary.0.join("home/archphene/.bashrc"),
        )
        .expect("bashrc symlink");
        assert!(matches!(
            ArchRoot::bootstrap(&temporary.0),
            Err(RootError::InvalidEntry(_))
        ));
    }

    #[test]
    fn bootstrap_rejects_relative_and_parent_paths() {
        assert!(matches!(
            ArchRoot::bootstrap(Path::new("relative")),
            Err(RootError::InvalidPath)
        ));
        assert!(matches!(
            ArchRoot::bootstrap(Path::new("/tmp/a/../b")),
            Err(RootError::InvalidPath)
        ));
    }

    #[test]
    fn bootstrap_rejects_an_unknown_layout_version() {
        let temporary = TestDirectory::new();
        ArchRoot::bootstrap(&temporary.0).expect("initial bootstrap");
        fs::write(temporary.0.join(VERSION_FILE), b"future\n").expect("replace version");
        assert!(matches!(
            ArchRoot::bootstrap(&temporary.0),
            Err(RootError::VersionMismatch)
        ));
    }
}
