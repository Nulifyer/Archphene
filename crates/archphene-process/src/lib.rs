#![deny(unsafe_code)]

use std::ffi::{OsStr, OsString};
use std::fmt;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read};
use std::os::unix::fs::{MetadataExt, OpenOptionsExt};
use std::os::unix::process::{CommandExt, ExitStatusExt};
use std::path::{Component, Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::thread;
use std::time::{Duration, Instant};

pub const MAX_COMMAND_NAME_BYTES: usize = 128;
pub const MAX_COMMAND_ARGUMENTS: usize = 32;
pub const MAX_COMMAND_ARGUMENT_BYTES: usize = 4 * 1024;
pub const MAX_COMMAND_REQUEST_BYTES: usize = 16 * 1024;
pub const MAX_COMMAND_OUTPUT_BYTES: usize = 15 * 1024;
pub const COMMAND_TIMEOUT: Duration = Duration::from_secs(30);

const MAX_SYMLINKS: usize = 16;
static OUTPUT_ID: AtomicU64 = AtomicU64::new(1);

#[derive(Debug)]
pub enum ProcessError {
    InvalidEnvironment,
    InvalidCommand,
    InvalidArgument,
    MissingCommand,
    UnsafeCommand(PathBuf),
    OutputLimit,
    Timeout,
    Io(io::Error),
}

impl fmt::Display for ProcessError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidEnvironment => formatter.write_str("invalid Linux command environment"),
            Self::InvalidCommand => formatter.write_str("invalid Linux command name"),
            Self::InvalidArgument => formatter.write_str("invalid Linux command arguments"),
            Self::MissingCommand => formatter.write_str("Linux command is not installed"),
            Self::UnsafeCommand(path) => {
                write!(formatter, "unsafe Linux command: {}", path.display())
            }
            Self::OutputLimit => formatter.write_str("Linux command output exceeded its limit"),
            Self::Timeout => formatter.write_str("Linux command timed out"),
            Self::Io(error) => write!(formatter, "Linux command I/O error: {error}"),
        }
    }
}

impl std::error::Error for ProcessError {}

impl From<io::Error> for ProcessError {
    fn from(error: io::Error) -> Self {
        Self::Io(error)
    }
}

#[derive(Debug)]
pub struct CommandOutput {
    bytes: [u8; MAX_COMMAND_OUTPUT_BYTES],
    length: usize,
    exit_code: i32,
}

impl CommandOutput {
    pub fn as_bytes(&self) -> &[u8] {
        &self.bytes[..self.length]
    }

    pub const fn exit_code(&self) -> i32 {
        self.exit_code
    }
}

#[derive(Clone, Debug)]
pub struct CommandEnvironment {
    arch_root: PathBuf,
    loader: PathBuf,
    library_path: OsString,
    path_bridge: PathBuf,
    command_directory: PathBuf,
    executable_path: OsString,
}

impl CommandEnvironment {
    pub fn new(
        arch_root: &Path,
        loader: &Path,
        library_path: &OsStr,
        path_bridge: &Path,
        command_directory: &Path,
        executable_path: &OsStr,
    ) -> Result<Self, ProcessError> {
        for path in [arch_root, loader, path_bridge, command_directory] {
            if !valid_absolute_path(path) {
                return Err(ProcessError::InvalidEnvironment);
            }
        }
        if library_path.is_empty()
            || executable_path.is_empty()
            || library_path.as_encoded_bytes().contains(&0)
            || executable_path.as_encoded_bytes().contains(&0)
        {
            return Err(ProcessError::InvalidEnvironment);
        }
        let root_metadata = fs::symlink_metadata(arch_root)?;
        let loader_metadata = fs::symlink_metadata(loader)?;
        let resolved_bridge = path_bridge.canonicalize()?;
        let bridge_metadata = fs::symlink_metadata(&resolved_bridge)?;
        let command_metadata = fs::symlink_metadata(command_directory)?;
        if root_metadata.file_type().is_symlink()
            || !root_metadata.is_dir()
            || loader_metadata.file_type().is_symlink()
            || !safe_regular_file(&loader_metadata)
            || bridge_metadata.file_type().is_symlink()
            || !safe_regular_file(&bridge_metadata)
            || command_metadata.file_type().is_symlink()
            || !command_metadata.is_dir()
        {
            return Err(ProcessError::InvalidEnvironment);
        }
        Ok(Self {
            arch_root: arch_root.to_path_buf(),
            loader: loader.to_path_buf(),
            library_path: library_path.to_os_string(),
            path_bridge: resolved_bridge,
            command_directory: command_directory.to_path_buf(),
            executable_path: executable_path.to_os_string(),
        })
    }

    pub fn run(&self, command: &str, arguments: &[&str]) -> Result<CommandOutput, ProcessError> {
        validate_request(command, arguments)?;
        let program = resolve_installed_command(&self.arch_root, command)?;
        let output_path = self.output_path();
        let output_file = OpenOptions::new()
            .create_new(true)
            .write(true)
            .mode(0o600)
            .open(&output_path)?;
        let _output_guard = TemporaryOutput(output_path.clone());
        let error_file = output_file.try_clone()?;
        let child = Command::new(&self.loader)
            .arg("--library-path")
            .arg(&self.library_path)
            .arg("--argv0")
            .arg(command)
            .arg(program)
            .args(arguments)
            .current_dir(self.arch_root.join("home/archphene"))
            .env_clear()
            .env("HOME", self.arch_root.join("home/archphene"))
            .env("TMPDIR", self.arch_root.join("tmp"))
            .env("PATH", &self.executable_path)
            .env("LANG", "C.UTF-8")
            .env("LC_ALL", "C.UTF-8")
            .env("TERM", "dumb")
            .env("COLORTERM", "")
            .env(
                "XDG_CONFIG_HOME",
                self.arch_root.join("home/archphene/.config"),
            )
            .env(
                "XDG_CACHE_HOME",
                self.arch_root.join("home/archphene/.cache"),
            )
            .env(
                "XDG_DATA_HOME",
                self.arch_root.join("home/archphene/.local/share"),
            )
            .env("XDG_RUNTIME_DIR", self.arch_root.join("run"))
            .env("GLIBC_TUNABLES", "glibc.pthread.rseq=0")
            .env("LD_PRELOAD", &self.path_bridge)
            .env("ARCHPHENE_RUNTIME_LOADER", &self.loader)
            .env("ARCHPHENE_RUNTIME_LIB", &self.library_path)
            .env("ARCHPHENE_RUNTIME_COMMAND_DIR", &self.command_directory)
            .env("ARCHPHENE_RUNTIME_ROOT", &self.arch_root)
            .env("ARCHPHENE_FAKE_CHROOT", "1")
            .stdin(Stdio::null())
            .stdout(Stdio::from(output_file))
            .stderr(Stdio::from(error_file))
            .process_group(0)
            .spawn();
        let result = match child {
            Ok(mut child) => self.wait_for_output(&mut child, &output_path),
            Err(error) => Err(ProcessError::Io(error)),
        };
        result
    }

    fn wait_for_output(
        &self,
        child: &mut std::process::Child,
        output_path: &Path,
    ) -> Result<CommandOutput, ProcessError> {
        let deadline = Instant::now() + COMMAND_TIMEOUT;
        let status = loop {
            if fs::symlink_metadata(output_path)?.len() > MAX_COMMAND_OUTPUT_BYTES as u64 {
                terminate_process_group(child);
                let _ = child.wait();
                return Err(ProcessError::OutputLimit);
            }
            if let Some(status) = child.try_wait()? {
                break status;
            }
            if Instant::now() >= deadline {
                terminate_process_group(child);
                let _ = child.wait();
                return Err(ProcessError::Timeout);
            }
            thread::sleep(Duration::from_millis(20));
        };
        let mut bytes = [0_u8; MAX_COMMAND_OUTPUT_BYTES];
        let mut file = File::open(output_path)?;
        let mut length = 0_usize;
        while length < bytes.len() {
            let count = file.read(&mut bytes[length..])?;
            if count == 0 {
                break;
            }
            length += count;
        }
        let mut extra = [0_u8; 1];
        if file.read(&mut extra)? != 0 {
            return Err(ProcessError::OutputLimit);
        }
        let exit_code = status
            .code()
            .or_else(|| status.signal().map(|signal| -signal))
            .unwrap_or(-1);
        Ok(CommandOutput {
            bytes,
            length,
            exit_code,
        })
    }

    fn output_path(&self) -> PathBuf {
        self.arch_root.join("run").join(format!(
            "command-output-{}-{}.tmp",
            std::process::id(),
            OUTPUT_ID.fetch_add(1, Ordering::Relaxed),
        ))
    }
}

fn validate_request(command: &str, arguments: &[&str]) -> Result<(), ProcessError> {
    if command.is_empty()
        || command.len() > MAX_COMMAND_NAME_BYTES
        || !command
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'+' | b'-'))
    {
        return Err(ProcessError::InvalidCommand);
    }
    if arguments.len() > MAX_COMMAND_ARGUMENTS {
        return Err(ProcessError::InvalidArgument);
    }
    let mut total = command.len();
    for argument in arguments {
        if argument.len() > MAX_COMMAND_ARGUMENT_BYTES || argument.as_bytes().contains(&0) {
            return Err(ProcessError::InvalidArgument);
        }
        total = total
            .checked_add(argument.len() + 1)
            .ok_or(ProcessError::InvalidArgument)?;
    }
    if total > MAX_COMMAND_REQUEST_BYTES {
        return Err(ProcessError::InvalidArgument);
    }
    Ok(())
}

fn resolve_installed_command(root: &Path, command: &str) -> Result<PathBuf, ProcessError> {
    let mut path = root.join("usr/bin").join(command);
    for _ in 0..=MAX_SYMLINKS {
        let metadata = match fs::symlink_metadata(&path) {
            Ok(metadata) => metadata,
            Err(error) if error.kind() == io::ErrorKind::NotFound => {
                return Err(ProcessError::MissingCommand);
            }
            Err(error) => return Err(ProcessError::Io(error)),
        };
        if metadata.file_type().is_symlink() {
            let target = fs::read_link(&path)?;
            let candidate = if target.is_absolute() {
                root.join(
                    target
                        .strip_prefix("/")
                        .map_err(|_| ProcessError::UnsafeCommand(path.clone()))?,
                )
            } else {
                path.parent()
                    .ok_or_else(|| ProcessError::UnsafeCommand(path.clone()))?
                    .join(target)
            };
            path = normalize_under_root(root, &candidate)?;
            continue;
        }
        if !safe_regular_file(&metadata) || metadata.mode() & 0o111 == 0 {
            return Err(ProcessError::UnsafeCommand(path));
        }
        return Ok(path);
    }
    Err(ProcessError::UnsafeCommand(path))
}

fn normalize_under_root(root: &Path, path: &Path) -> Result<PathBuf, ProcessError> {
    let root_depth = root.components().count();
    let mut normalized = PathBuf::new();
    for component in path.components() {
        match component {
            Component::Prefix(_) | Component::CurDir => {}
            Component::RootDir => normalized.push("/"),
            Component::Normal(value) => normalized.push(value),
            Component::ParentDir => {
                if normalized.components().count() <= root_depth {
                    return Err(ProcessError::UnsafeCommand(path.to_path_buf()));
                }
                normalized.pop();
            }
        }
    }
    if !normalized.starts_with(root) {
        return Err(ProcessError::UnsafeCommand(path.to_path_buf()));
    }
    Ok(normalized)
}

fn valid_absolute_path(path: &Path) -> bool {
    path.is_absolute()
        && !path.as_os_str().is_empty()
        && path.as_os_str().as_encoded_bytes().len() <= 4096
        && !path
            .components()
            .any(|component| matches!(component, Component::ParentDir | Component::CurDir))
}

fn safe_regular_file(metadata: &fs::Metadata) -> bool {
    metadata.is_file() && metadata.mode() & 0o022 == 0
}

struct TemporaryOutput(PathBuf);

impl Drop for TemporaryOutput {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.0);
    }
}

fn terminate_process_group(child: &mut std::process::Child) {
    if system::kill_process_group(child.id()).is_err() {
        let _ = child.kill();
    }
}

#[allow(unsafe_code)]
mod system {
    use std::io;

    unsafe extern "C" {
        fn kill(process: i32, signal: i32) -> i32;
    }

    pub fn kill_process_group(group: u32) -> io::Result<()> {
        let group = i32::try_from(group)
            .ok()
            .filter(|group| *group > 0)
            .ok_or_else(|| io::Error::from(io::ErrorKind::InvalidInput))?;
        // SAFETY: `group` is the positive pid of a child started in its own
        // process group. Negating it addresses only that group, and SIGKILL
        // has no pointer, buffer, ownership, or lifetime requirements.
        if unsafe { kill(-group, 9) } == 0 {
            Ok(())
        } else {
            Err(io::Error::last_os_error())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use std::os::unix::fs::{PermissionsExt, symlink};
    use std::sync::atomic::{AtomicU64, Ordering};

    static TEST_ID: AtomicU64 = AtomicU64::new(1);

    struct TestRoot(PathBuf);

    impl TestRoot {
        fn new() -> Self {
            let id = TEST_ID.fetch_add(1, Ordering::Relaxed);
            let path = std::env::temp_dir().join(format!(
                "archphene-process-test-{}-{id}",
                std::process::id()
            ));
            fs::create_dir(&path).expect("root");
            fs::create_dir_all(path.join("usr/bin")).expect("bin");
            Self(path)
        }

        fn command(&self, name: &str) {
            let path = self.0.join("usr/bin").join(name);
            let mut file = File::create(&path).expect("command");
            file.write_all(b"ELF placeholder").expect("write command");
            file.set_permissions(fs::Permissions::from_mode(0o755))
                .expect("command mode");
        }
    }

    impl Drop for TestRoot {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn request_limits_reject_shell_syntax_and_unbounded_arguments() {
        assert!(validate_request("btop", &["--version"]).is_ok());
        assert!(matches!(
            validate_request("../btop", &[]),
            Err(ProcessError::InvalidCommand)
        ));
        assert!(matches!(
            validate_request("btop", &["x"; MAX_COMMAND_ARGUMENTS + 1]),
            Err(ProcessError::InvalidArgument)
        ));
        assert!(matches!(
            validate_request("btop", &[&"x".repeat(MAX_COMMAND_ARGUMENT_BYTES + 1)]),
            Err(ProcessError::InvalidArgument)
        ));
    }

    #[test]
    fn command_resolution_accepts_safe_relative_and_absolute_root_symlinks() {
        let root = TestRoot::new();
        root.command("real-command");
        symlink("real-command", root.0.join("usr/bin/relative")).expect("relative link");
        symlink("/usr/bin/real-command", root.0.join("usr/bin/absolute")).expect("absolute link");
        assert_eq!(
            resolve_installed_command(&root.0, "relative").expect("relative resolution"),
            root.0.join("usr/bin/real-command")
        );
        assert_eq!(
            resolve_installed_command(&root.0, "absolute").expect("absolute resolution"),
            root.0.join("usr/bin/real-command")
        );
    }

    #[test]
    fn command_resolution_rejects_escape_and_writable_programs() {
        let root = TestRoot::new();
        symlink("../../../../system/bin/sh", root.0.join("usr/bin/escape")).expect("escape link");
        assert!(matches!(
            resolve_installed_command(&root.0, "escape"),
            Err(ProcessError::UnsafeCommand(_))
        ));
        root.command("writable");
        fs::set_permissions(
            root.0.join("usr/bin/writable"),
            fs::Permissions::from_mode(0o777),
        )
        .expect("writable mode");
        assert!(matches!(
            resolve_installed_command(&root.0, "writable"),
            Err(ProcessError::UnsafeCommand(_))
        ));
    }

    #[test]
    fn environment_accepts_a_verified_bridge_alias_and_keeps_its_final_path() {
        let root = TestRoot::new();
        root.command("loader");
        let native = root.0.join("native");
        let aliases = root.0.join("run/aliases");
        fs::create_dir(&native).expect("native directory");
        fs::create_dir_all(&aliases).expect("alias directory");
        let bridge = native.join("libbridge.so");
        let mut bridge_file = File::create(&bridge).expect("bridge");
        bridge_file.write_all(b"ELF bridge").expect("write bridge");
        bridge_file
            .set_permissions(fs::Permissions::from_mode(0o755))
            .expect("bridge mode");
        let bridge_alias = aliases.join("libbridge.so");
        symlink(&bridge, &bridge_alias).expect("bridge alias");
        let environment = CommandEnvironment::new(
            &root.0,
            &root.0.join("usr/bin/loader"),
            aliases.as_os_str(),
            &bridge_alias,
            &aliases,
            root.0.join("usr/bin").as_os_str(),
        )
        .expect("command environment");
        assert_eq!(environment.path_bridge, bridge);
    }
}
