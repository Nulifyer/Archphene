#![forbid(unsafe_code)]

use std::fmt;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::net::IpAddr;
use std::os::unix::fs::PermissionsExt;
use std::path::{Component, Path, PathBuf};

use rustix::fd::OwnedFd;
use rustix::fs::{
    AtFlags, CWD, FileType, Mode, OFlags, fchmod, fstat, fsync, openat, renameat, statat, unlinkat,
};
use rustix::io::Errno;

pub const ROOT_LAYOUT_VERSION: u32 = 1;
pub const MAX_ANDROID_DNS_REQUEST_BYTES: usize = 512;
pub const MAX_ANDROID_DNS_SERVERS: usize = 4;

const VERSION_CONTENT: &[u8] = b"archphene-root-v1\n";
const VERSION_FILE: &str = ".archphene-root-version";
const VERSION_TEMP_FILE: &str = ".archphene-root-version.tmp";
const RESOLV_CONF_FILE: &str = "resolv.conf";
const RESOLV_CONF_TEMP_FILE: &str = ".resolv.conf.tmp";
const DNS_REQUEST_HEADER: &str = "D1";
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
const ANDROID_FONTCONFIG: &[u8] = br#"<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "urn:fontconfig:fonts.dtd">
<fontconfig>
  <description>Archphene device-font bridge</description>
  <include ignore_missing="yes">/etc/fonts/fonts.conf</include>
  <dir>/system/fonts</dir>
  <alias>
    <family>sans-serif</family>
    <prefer><family>Roboto</family><family>Noto Sans</family></prefer>
  </alias>
  <alias>
    <family>serif</family>
    <prefer><family>Noto Serif</family></prefer>
  </alias>
  <alias>
    <family>monospace</family>
    <prefer><family>Droid Sans Mono</family><family>Cutive Mono</family></prefer>
  </alias>
</fontconfig>
"#;

const DIRECTORIES: &[(&str, u32)] = &[
    ("usr", 0o755),
    ("etc", 0o755),
    ("var", 0o755),
    ("var/lib", 0o755),
    ("var/lib/archphene", 0o700),
    ("var/lib/archphene/fontconfig", 0o700),
    ("var/lib/archphene/storage", 0o700),
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
    ("home/archphene/Projects", 0o700),
    ("tmp", 0o1777),
    ("run", 0o700),
    ("mnt", 0o755),
    ("mnt/android", 0o755),
];

#[derive(Debug)]
pub enum RootError {
    InvalidPath,
    InvalidEntry(PathBuf),
    InvalidDnsConfiguration,
    VersionMismatch,
    Io(io::Error),
    Syscall(Errno),
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
            Self::InvalidDnsConfiguration => {
                formatter.write_str("invalid Android DNS configuration")
            }
            Self::VersionMismatch => formatter.write_str("unsupported Arch root layout version"),
            Self::Io(error) => write!(formatter, "Arch root I/O error: {error}"),
            Self::Syscall(error) => write!(formatter, "Arch root system call failed: {error}"),
        }
    }
}

impl std::error::Error for RootError {}

impl From<io::Error> for RootError {
    fn from(error: io::Error) -> Self {
        Self::Io(error)
    }
}

impl From<Errno> for RootError {
    fn from(error: Errno) -> Self {
        Self::Syscall(error)
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
        ensure_managed_file(
            &path.join("var/lib/archphene/fontconfig/fonts.conf"),
            ANDROID_FONTCONFIG,
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

    pub fn configure_android_dns(&self, request: &[u8]) -> Result<usize, RootError> {
        let servers = parse_android_dns_request(request)?;
        let mut content = String::with_capacity(192);
        content.push_str("# Managed by Archphene from Android LinkProperties.\n");
        content.push_str("options timeout:2 attempts:2\n");
        for server in &servers {
            content.push_str("nameserver ");
            content.push_str(server);
            content.push('\n');
        }
        write_managed_resolver(&self.path, content.as_bytes())?;
        Ok(servers.len())
    }
}

fn parse_android_dns_request(request: &[u8]) -> Result<Vec<String>, RootError> {
    if request.is_empty()
        || request.len() > MAX_ANDROID_DNS_REQUEST_BYTES
        || !request.ends_with(b"\n")
    {
        return Err(RootError::InvalidDnsConfiguration);
    }
    let request = std::str::from_utf8(request).map_err(|_| RootError::InvalidDnsConfiguration)?;
    if !request.is_ascii() {
        return Err(RootError::InvalidDnsConfiguration);
    }
    let mut lines = request.lines();
    if lines.next() != Some(DNS_REQUEST_HEADER) {
        return Err(RootError::InvalidDnsConfiguration);
    }
    let mut servers = Vec::with_capacity(MAX_ANDROID_DNS_SERVERS);
    for line in lines {
        if line.is_empty() || line.len() > 80 || servers.len() == MAX_ANDROID_DNS_SERVERS {
            return Err(RootError::InvalidDnsConfiguration);
        }
        let (address_text, scope) = match line.split_once('%') {
            Some((address, scope))
                if !address.is_empty() && valid_ipv6_scope(scope) && !scope.contains('%') =>
            {
                (address, Some(scope))
            }
            Some(_) => return Err(RootError::InvalidDnsConfiguration),
            None => (line, None),
        };
        let address = address_text
            .parse::<IpAddr>()
            .map_err(|_| RootError::InvalidDnsConfiguration)?;
        if scope.is_some() && !matches!(address, IpAddr::V6(_)) {
            return Err(RootError::InvalidDnsConfiguration);
        }
        let invalid = match address {
            IpAddr::V4(address) => {
                address.is_unspecified() || address.is_multicast() || address.is_broadcast()
            }
            IpAddr::V6(address) => address.is_unspecified() || address.is_multicast(),
        };
        if invalid {
            return Err(RootError::InvalidDnsConfiguration);
        }
        let mut canonical = address.to_string();
        if let Some(scope) = scope {
            canonical.push('%');
            canonical.push_str(scope);
        }
        if !servers.contains(&canonical) {
            servers.push(canonical);
        }
    }
    if servers.is_empty() {
        return Err(RootError::InvalidDnsConfiguration);
    }
    Ok(servers)
}

fn valid_ipv6_scope(scope: &str) -> bool {
    if scope.is_empty() {
        return false;
    }
    if scope.bytes().all(|byte| byte.is_ascii_digit()) {
        return scope.parse::<u32>().is_ok_and(|scope| scope != 0);
    }
    scope.len() <= 15
        && scope
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-' | b'.'))
}

fn write_managed_resolver(root: &Path, content: &[u8]) -> Result<(), RootError> {
    let root_directory = openat(
        CWD,
        root,
        OFlags::RDONLY | OFlags::DIRECTORY | OFlags::NOFOLLOW | OFlags::CLOEXEC,
        Mode::empty(),
    )?;
    let etc_directory = openat(
        &root_directory,
        "etc",
        OFlags::RDONLY | OFlags::DIRECTORY | OFlags::NOFOLLOW | OFlags::CLOEXEC,
        Mode::empty(),
    )
    .map_err(|error| match error {
        Errno::LOOP | Errno::NOTDIR => RootError::InvalidEntry(root.join("etc")),
        _ => error.into(),
    })?;
    if managed_resolver_matches(&etc_directory, content, root)? {
        return Ok(());
    }
    remove_regular_if_present(&etc_directory, RESOLV_CONF_TEMP_FILE, root)?;
    let descriptor = openat(
        &etc_directory,
        RESOLV_CONF_TEMP_FILE,
        OFlags::WRONLY | OFlags::CREATE | OFlags::EXCL | OFlags::NOFOLLOW | OFlags::CLOEXEC,
        Mode::from_raw_mode(0o600),
    )?;
    fchmod(&descriptor, Mode::from_raw_mode(0o600))?;
    let mut file = File::from(descriptor);
    file.write_all(content)?;
    file.sync_all()?;
    drop(file);
    renameat(
        &etc_directory,
        RESOLV_CONF_TEMP_FILE,
        &etc_directory,
        RESOLV_CONF_FILE,
    )?;
    fsync(&etc_directory)?;
    Ok(())
}

fn managed_resolver_matches(
    directory: &OwnedFd,
    expected: &[u8],
    root: &Path,
) -> Result<bool, RootError> {
    let descriptor = match openat(
        directory,
        RESOLV_CONF_FILE,
        OFlags::RDONLY | OFlags::NOFOLLOW | OFlags::CLOEXEC,
        Mode::empty(),
    ) {
        Ok(descriptor) => descriptor,
        Err(Errno::NOENT) => return Ok(false),
        Err(Errno::LOOP) => {
            return Err(RootError::InvalidEntry(
                root.join("etc").join(RESOLV_CONF_FILE),
            ));
        }
        Err(error) => return Err(error.into()),
    };
    let metadata = fstat(&descriptor)?;
    if FileType::from_raw_mode(metadata.st_mode) != FileType::RegularFile {
        return Err(RootError::InvalidEntry(
            root.join("etc").join(RESOLV_CONF_FILE),
        ));
    }
    let expected_size =
        i64::try_from(expected.len()).map_err(|_| RootError::InvalidDnsConfiguration)?;
    if metadata.st_mode & 0o7777 != 0o600 || metadata.st_size != expected_size {
        return Ok(false);
    }
    let mut current = Vec::with_capacity(expected.len());
    File::from(descriptor)
        .take(expected.len() as u64 + 1)
        .read_to_end(&mut current)?;
    Ok(current == expected)
}

fn remove_regular_if_present(
    directory: &OwnedFd,
    name: &str,
    root: &Path,
) -> Result<(), RootError> {
    match statat(directory, name, AtFlags::SYMLINK_NOFOLLOW) {
        Ok(metadata) if FileType::from_raw_mode(metadata.st_mode) == FileType::RegularFile => {
            unlinkat(directory, name, AtFlags::empty())?;
            Ok(())
        }
        Ok(_) => Err(RootError::InvalidEntry(root.join("etc").join(name))),
        Err(Errno::NOENT) => Ok(()),
        Err(error) => Err(error.into()),
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

fn ensure_managed_file(path: &Path, content: &[u8]) -> Result<(), RootError> {
    match fs::symlink_metadata(path) {
        Ok(metadata) => {
            if metadata.file_type().is_symlink() || !metadata.is_file() {
                return Err(RootError::InvalidEntry(path.to_path_buf()));
            }
            if metadata.len() == content.len() as u64 {
                let mut current = Vec::with_capacity(content.len());
                File::open(path)?.read_to_end(&mut current)?;
                if current == content {
                    if metadata.permissions().mode() & 0o7777 != 0o600 {
                        fs::set_permissions(path, fs::Permissions::from_mode(0o600))?;
                    }
                    return Ok(());
                }
            }
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => {}
        Err(error) => return Err(RootError::Io(error)),
    }
    let temporary = path.with_extension("conf.tmp");
    match fs::symlink_metadata(&temporary) {
        Ok(metadata) => {
            if metadata.file_type().is_symlink() || !metadata.is_file() {
                return Err(RootError::InvalidEntry(temporary));
            }
            fs::remove_file(&temporary)?;
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => {}
        Err(error) => return Err(RootError::Io(error)),
    }
    let mut file = OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(&temporary)?;
    file.set_permissions(fs::Permissions::from_mode(0o600))?;
    file.write_all(content)?;
    file.sync_all()?;
    drop(file);
    fs::rename(temporary, path)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::MetadataExt;
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
        assert!(temporary.0.join("var/lib/archphene/storage").is_dir());
        assert!(temporary.0.join("var/lib/archphene/fontconfig").is_dir());
        assert!(temporary.0.join("home/archphene").is_dir());
        assert!(temporary.0.join("home/archphene/Documents").is_dir());
        assert!(temporary.0.join("home/archphene/Downloads").is_dir());
        assert!(temporary.0.join("home/archphene/Projects").is_dir());
        assert!(temporary.0.join("mnt/android").is_dir());
        assert_eq!(
            fs::read(temporary.0.join("home/archphene/.bashrc")).expect("default bashrc"),
            DEFAULT_BASHRC,
        );
        assert_eq!(
            fs::read(temporary.0.join("var/lib/archphene/fontconfig/fonts.conf"))
                .expect("managed fontconfig"),
            ANDROID_FONTCONFIG,
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
        fs::write(
            temporary.0.join("var/lib/archphene/fontconfig/fonts.conf"),
            b"damaged\n",
        )
        .expect("damage managed fontconfig");
        ArchRoot::bootstrap(&temporary.0).expect("repair managed fontconfig");
        assert_eq!(
            fs::read(temporary.0.join("var/lib/archphene/fontconfig/fonts.conf"))
                .expect("repaired fontconfig"),
            ANDROID_FONTCONFIG,
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

    #[test]
    fn android_dns_is_validated_canonicalized_and_atomically_replaced() {
        let temporary = TestDirectory::new();
        let (root, _) = ArchRoot::bootstrap(&temporary.0).expect("bootstrap");
        assert_eq!(
            root.configure_android_dns(b"D1\n10.0.2.3\n2001:0db8::0001\n10.0.2.3\n")
                .expect("configure DNS"),
            2
        );
        let resolver = temporary.0.join("etc/resolv.conf");
        assert_eq!(
            fs::read_to_string(&resolver).expect("resolver"),
            "# Managed by Archphene from Android LinkProperties.\n\
options timeout:2 attempts:2\n\
nameserver 10.0.2.3\n\
nameserver 2001:db8::1\n",
        );
        assert_eq!(
            fs::metadata(&resolver)
                .expect("resolver metadata")
                .permissions()
                .mode()
                & 0o7777,
            0o600
        );
        assert!(!temporary.0.join("etc/.resolv.conf.tmp").exists());
        let first_inode = fs::metadata(&resolver).expect("resolver metadata").ino();
        root.configure_android_dns(b"D1\n10.0.2.3\n2001:db8::1\n")
            .expect("unchanged DNS");
        assert_eq!(
            fs::metadata(&resolver).expect("resolver metadata").ino(),
            first_inode,
            "unchanged DNS should not churn the managed file"
        );
        fs::set_permissions(&resolver, fs::Permissions::from_mode(0o644))
            .expect("weaken resolver mode");
        root.configure_android_dns(b"D1\n10.0.2.3\n2001:db8::1\n")
            .expect("repair DNS mode");
        assert_eq!(
            fs::metadata(&resolver)
                .expect("resolver metadata")
                .permissions()
                .mode()
                & 0o7777,
            0o600
        );

        root.configure_android_dns(b"D1\n192.0.2.53\n")
            .expect("replace DNS");
        assert_eq!(
            fs::read_to_string(resolver).expect("replacement resolver"),
            "# Managed by Archphene from Android LinkProperties.\n\
options timeout:2 attempts:2\n\
nameserver 192.0.2.53\n",
        );
    }

    #[test]
    fn android_dns_rejects_malformed_and_unusable_addresses() {
        let temporary = TestDirectory::new();
        let (root, _) = ArchRoot::bootstrap(&temporary.0).expect("bootstrap");
        for request in [
            b"".as_slice(),
            b"D1".as_slice(),
            b"D2\n10.0.2.3\n".as_slice(),
            b"D1\n".as_slice(),
            b"D1\n0.0.0.0\n".as_slice(),
            b"D1\n224.0.0.1\n".as_slice(),
            b"D1\nfe80::1%\n".as_slice(),
            b"D1\nfe80::1%0\n".as_slice(),
            b"D1\nfe80::1%bad/zone\n".as_slice(),
            b"D1\nfe80::1%interface-name-too-long\n".as_slice(),
            b"D1\n1.1.1.1%wlan0\n".as_slice(),
            b"D1\nnot-an-address\n".as_slice(),
            b"D1\n1.1.1.1\n2.2.2.2\n3.3.3.3\n4.4.4.4\n5.5.5.5\n".as_slice(),
        ] {
            assert!(
                matches!(
                    root.configure_android_dns(request),
                    Err(RootError::InvalidDnsConfiguration)
                ),
                "accepted {request:?}"
            );
        }
        let oversized = vec![b'x'; MAX_ANDROID_DNS_REQUEST_BYTES + 1];
        assert!(matches!(
            root.configure_android_dns(&oversized),
            Err(RootError::InvalidDnsConfiguration)
        ));
        assert!(!temporary.0.join("etc/resolv.conf").exists());
    }

    #[test]
    fn android_dns_preserves_a_bounded_ipv6_interface_scope() {
        let temporary = TestDirectory::new();
        let (root, _) = ArchRoot::bootstrap(&temporary.0).expect("bootstrap");
        root.configure_android_dns(b"D1\nfe80:0::53%wlan0\n")
            .expect("scoped DNS");
        assert!(
            fs::read_to_string(temporary.0.join("etc/resolv.conf"))
                .expect("resolver")
                .contains("nameserver fe80::53%wlan0\n")
        );
    }

    #[test]
    fn android_dns_refuses_hostile_resolver_entries() {
        let temporary = TestDirectory::new();
        let (root, _) = ArchRoot::bootstrap(&temporary.0).expect("bootstrap");
        let outside = temporary.0.with_extension("outside");
        fs::write(&outside, b"outside\n").expect("outside file");
        std::os::unix::fs::symlink(&outside, temporary.0.join("etc/resolv.conf"))
            .expect("resolver symlink");
        assert!(matches!(
            root.configure_android_dns(b"D1\n10.0.2.3\n"),
            Err(RootError::InvalidEntry(_))
        ));
        assert_eq!(fs::read(&outside).expect("outside unchanged"), b"outside\n");
        fs::remove_file(temporary.0.join("etc/resolv.conf")).expect("remove resolver symlink");
        std::os::unix::fs::symlink(&outside, temporary.0.join("etc/.resolv.conf.tmp"))
            .expect("temporary symlink");
        assert!(matches!(
            root.configure_android_dns(b"D1\n10.0.2.3\n"),
            Err(RootError::InvalidEntry(_))
        ));
        assert_eq!(fs::read(&outside).expect("outside unchanged"), b"outside\n");
        fs::remove_file(&outside).expect("outside cleanup");
    }
}
