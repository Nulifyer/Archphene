#![deny(unsafe_code)]

use std::ffi::{OsStr, OsString};
use std::fmt;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::os::fd::AsRawFd;
use std::os::unix::fs::{MetadataExt, OpenOptionsExt};
use std::os::unix::net::UnixStream;
use std::os::unix::process::{CommandExt, ExitStatusExt};
use std::path::{Component, Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::thread;
use std::time::{Duration, Instant};

use archphene_terminal::{
    MAX_COLUMNS, MAX_DAMAGE_BYTES, MAX_ROWS, MIN_COLUMNS, MIN_ROWS, Terminal,
};

pub const MAX_COMMAND_NAME_BYTES: usize = 128;
pub const MAX_COMMAND_ARGUMENTS: usize = 32;
pub const MAX_COMMAND_ARGUMENT_BYTES: usize = 4 * 1024;
pub const MAX_COMMAND_REQUEST_BYTES: usize = 16 * 1024;
pub const MAX_COMMAND_OUTPUT_BYTES: usize = 15 * 1024;
pub const COMMAND_TIMEOUT: Duration = Duration::from_secs(30);
pub const MAX_PTY_TRANSFER_BYTES: usize = 16 * 1024;
pub const MAX_PTY_ROWS: u16 = MAX_ROWS;
pub const MAX_PTY_COLUMNS: u16 = MAX_COLUMNS;
pub const MAX_PTY_SESSIONS: usize = 4;
pub const MAX_TERMINAL_DAMAGE_BYTES: usize = MAX_DAMAGE_BYTES;

const MAX_SYMLINKS: usize = 16;
const MAX_SHEBANG_BYTES: usize = 256;
static OUTPUT_ID: AtomicU64 = AtomicU64::new(1);

#[derive(Debug)]
pub enum ProcessError {
    InvalidEnvironment,
    InvalidCommand,
    InvalidArgument,
    MissingCommand,
    UnsupportedProgram,
    InvalidInterpreter,
    InvalidPtySize,
    InvalidPtyHandle,
    PtyLimit,
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
            Self::UnsupportedProgram => {
                formatter.write_str("Linux command is neither ELF nor a supported script")
            }
            Self::InvalidInterpreter => {
                formatter.write_str("Linux script has an invalid or unavailable interpreter")
            }
            Self::InvalidPtySize => formatter.write_str("invalid terminal dimensions"),
            Self::InvalidPtyHandle => formatter.write_str("invalid or closed terminal handle"),
            Self::PtyLimit => formatter.write_str("terminal session limit reached"),
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
}

impl CommandEnvironment {
    pub fn new(
        arch_root: &Path,
        loader: &Path,
        library_path: &OsStr,
        path_bridge: &Path,
        command_directory: &Path,
    ) -> Result<Self, ProcessError> {
        for path in [arch_root, loader, path_bridge, command_directory] {
            if !valid_absolute_path(path) {
                return Err(ProcessError::InvalidEnvironment);
            }
        }
        if library_path.is_empty() || library_path.as_encoded_bytes().contains(&0) {
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
        })
    }

    pub fn run(&self, command: &str, arguments: &[&str]) -> Result<CommandOutput, ProcessError> {
        validate_request(command, arguments)?;
        let command_path = resolve_installed_command(&self.arch_root, command)?;
        let launch = prepare_launch(&self.arch_root, command, command_path)?;
        let output_path = self.output_path();
        let output_file = OpenOptions::new()
            .create_new(true)
            .write(true)
            .mode(0o600)
            .open(&output_path)?;
        let _output_guard = TemporaryOutput(output_path.clone());
        let error_file = output_file.try_clone()?;
        let child = self
            .build_command(&launch, arguments, "dumb")
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

    pub fn command_available(&self, command: &str) -> Result<bool, ProcessError> {
        validate_request(command, &[])?;
        let command_path = match resolve_installed_command(&self.arch_root, command) {
            Ok(path) => path,
            Err(ProcessError::MissingCommand) => return Ok(false),
            Err(error) => return Err(error),
        };
        prepare_launch(&self.arch_root, command, command_path)?;
        Ok(true)
    }

    pub fn open_pty(
        &self,
        command: &str,
        arguments: &[&str],
        rows: u16,
        columns: u16,
    ) -> Result<PtySession, ProcessError> {
        validate_request(command, arguments)?;
        validate_pty_size(rows, columns)?;
        let command_path = resolve_installed_command(&self.arch_root, command)?;
        let launch = prepare_launch(&self.arch_root, command, command_path)?;
        let terminal = Terminal::new(rows, columns).map_err(|_| ProcessError::InvalidPtySize)?;
        let (master, slave) = system::open_pty(rows, columns)?;
        let waiter = PtyWaiter::new(&master)?;
        let input = slave.try_clone()?;
        let output = slave.try_clone()?;
        let mut command_builder = self.build_command(&launch, arguments, "xterm-256color");
        system::configure_controlling_terminal(&mut command_builder);
        let child = command_builder
            .stdin(Stdio::from(input))
            .stdout(Stdio::from(output))
            .stderr(Stdio::from(slave))
            .spawn()?;
        Ok(PtySession {
            master,
            waiter,
            child: Some(child),
            exit_status: None,
            rows,
            columns,
            terminal,
        })
    }

    fn build_command(&self, launch: &LaunchPlan, arguments: &[&str], terminal: &str) -> Command {
        let mut command = Command::new(&self.loader);
        command
            .arg("--library-path")
            .arg(&self.library_path)
            .arg("--argv0")
            .arg(&launch.argv0)
            .arg(&launch.program);
        if let Some(interpreter_argument) = &launch.interpreter_argument {
            command.arg(interpreter_argument);
        }
        if let Some(script) = &launch.script {
            command.arg(script);
        }
        command
            .args(arguments)
            .current_dir(self.arch_root.join("home/archphene"))
            .env_clear()
            .env("HOME", "/home/archphene")
            .env("TMPDIR", "/tmp")
            .env("PATH", "/usr/local/sbin:/usr/local/bin:/usr/bin")
            .env("LANG", "C.UTF-8")
            .env("LC_ALL", "C.UTF-8")
            .env("LOCPATH", self.arch_root.join("usr/lib/locale"))
            .env("USER", "archphene")
            .env("LOGNAME", "archphene")
            .env("TERM", terminal)
            .env(
                "COLORTERM",
                if terminal == "dumb" { "" } else { "truecolor" },
            )
            .env("XDG_CONFIG_HOME", "/home/archphene/.config")
            .env("XDG_CACHE_HOME", "/home/archphene/.cache")
            .env("XDG_DATA_HOME", "/home/archphene/.local/share")
            .env("XDG_RUNTIME_DIR", "/run")
            .env("GLIBC_TUNABLES", "glibc.pthread.rseq=0")
            .env("LD_PRELOAD", &self.path_bridge)
            .env("ARCHPHENE_RUNTIME_LOADER", &self.loader)
            .env("ARCHPHENE_RUNTIME_LIB", &self.library_path)
            .env("ARCHPHENE_RUNTIME_COMMAND_DIR", &self.command_directory)
            .env("ARCHPHENE_RUNTIME_ROOT", &self.arch_root)
            .env("ARCHPHENE_FAKE_CHROOT", "1");
        command
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
        let exit_code = exit_code(status);
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

#[derive(Debug)]
pub struct PtySession {
    master: File,
    waiter: PtyWaiter,
    child: Option<std::process::Child>,
    exit_status: Option<i32>,
    rows: u16,
    columns: u16,
    terminal: Terminal,
}

impl PtySession {
    pub fn read(&mut self, output: &mut [u8]) -> Result<usize, ProcessError> {
        if output.is_empty() || output.len() > MAX_PTY_TRANSFER_BYTES {
            return Err(ProcessError::InvalidArgument);
        }
        match self.master.read(output) {
            Ok(0) => {
                self.finish();
                Ok(0)
            }
            Ok(length) => {
                self.terminal.feed(&output[..length]);
                Ok(length)
            }
            Err(error) if error.kind() == io::ErrorKind::WouldBlock => Ok(0),
            Err(error) if error.raw_os_error() == Some(5) => {
                self.finish();
                Ok(0)
            }
            Err(error) => Err(ProcessError::Io(error)),
        }
    }

    pub fn write(&mut self, input: &[u8]) -> Result<usize, ProcessError> {
        if input.is_empty() || input.len() > MAX_PTY_TRANSFER_BYTES {
            return Err(ProcessError::InvalidArgument);
        }
        match self.master.write(input) {
            Ok(length) => Ok(length),
            Err(error) if error.kind() == io::ErrorKind::WouldBlock => Ok(0),
            Err(error) => Err(ProcessError::Io(error)),
        }
    }

    pub fn resize(&mut self, rows: u16, columns: u16) -> Result<(), ProcessError> {
        validate_pty_size(rows, columns)?;
        system::resize_pty(self.master.as_raw_fd(), rows, columns)?;
        self.terminal
            .resize(rows, columns)
            .map_err(|_| ProcessError::InvalidPtySize)?;
        self.rows = rows;
        self.columns = columns;
        Ok(())
    }

    pub const fn exit_status(&self) -> Option<i32> {
        self.exit_status
    }

    pub const fn size(&self) -> (u16, u16) {
        (self.rows, self.columns)
    }

    pub fn waiter(&self) -> PtyWaiter {
        self.waiter.clone()
    }

    pub fn write_terminal_damage(
        &mut self,
        output: &mut [u8],
        full_snapshot: bool,
    ) -> Result<usize, ProcessError> {
        if full_snapshot {
            self.terminal.write_full_damage(output)
        } else {
            self.terminal.write_damage(output)
        }
        .map_err(|_| ProcessError::InvalidArgument)
    }

    pub fn close(&mut self) {
        let _ = self.waiter.signal();
        self.finish();
    }

    fn finish(&mut self) {
        let Some(mut child) = self.child.take() else {
            return;
        };
        // Signal the group before reaping its leader. This keeps the leader
        // pid reserved while addressing descendants that may still be alive.
        terminate_process_group(&mut child);
        self.exit_status = child.wait().ok().map(exit_code);
    }
}

impl Drop for PtySession {
    fn drop(&mut self) {
        self.close();
    }
}

#[derive(Clone, Debug)]
pub struct PtyWaiter {
    inner: Arc<PtyWaiterInner>,
}

#[derive(Debug)]
struct PtyWaiterInner {
    master: File,
    wake_reader: UnixStream,
    wake_writer: UnixStream,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct PtyWaitEvent {
    pub readable: bool,
    pub writable: bool,
    pub hangup: bool,
    pub woken: bool,
}

impl PtyWaiter {
    fn new(master: &File) -> Result<Self, ProcessError> {
        let (wake_reader, wake_writer) = UnixStream::pair()?;
        wake_reader.set_nonblocking(true)?;
        wake_writer.set_nonblocking(true)?;
        Ok(Self {
            inner: Arc::new(PtyWaiterInner {
                master: master.try_clone()?,
                wake_reader,
                wake_writer,
            }),
        })
    }

    pub fn wait(
        &self,
        timeout: Option<Duration>,
        write_pending: bool,
    ) -> Result<PtyWaitEvent, ProcessError> {
        let event = system::wait_for_pty(
            self.inner.master.as_raw_fd(),
            self.inner.wake_reader.as_raw_fd(),
            timeout,
            write_pending,
        )
        .map_err(ProcessError::from)?;
        if event.woken {
            let mut buffer = [0_u8; 64];
            let mut reader = &self.inner.wake_reader;
            loop {
                match reader.read(&mut buffer) {
                    Ok(0) => break,
                    Ok(_) => {}
                    Err(error) if error.kind() == io::ErrorKind::WouldBlock => break,
                    Err(error) => return Err(ProcessError::Io(error)),
                }
            }
        }
        Ok(event)
    }

    pub fn signal(&self) -> Result<(), ProcessError> {
        let mut writer = &self.inner.wake_writer;
        match writer.write(&[1]) {
            Ok(_) => Ok(()),
            Err(error) if error.kind() == io::ErrorKind::WouldBlock => Ok(()),
            Err(error) => Err(ProcessError::Io(error)),
        }
    }
}

pub struct PtyRegistry {
    slots: [PtySlot; MAX_PTY_SESSIONS],
}

struct PtySlot {
    generation: u32,
    session: Option<PtySession>,
}

impl PtyRegistry {
    pub fn new() -> Self {
        Self {
            slots: std::array::from_fn(|_| PtySlot {
                generation: 0,
                session: None,
            }),
        }
    }

    pub fn open(
        &mut self,
        environment: &CommandEnvironment,
        command: &str,
        arguments: &[&str],
        rows: u16,
        columns: u16,
    ) -> Result<u64, ProcessError> {
        let (index, slot) = self
            .slots
            .iter_mut()
            .enumerate()
            .find(|(_, slot)| slot.session.is_none())
            .ok_or(ProcessError::PtyLimit)?;
        let session = environment.open_pty(command, arguments, rows, columns)?;
        slot.generation = slot.generation.wrapping_add(1).max(1);
        let handle = encode_pty_handle(index, slot.generation)?;
        slot.session = Some(session);
        Ok(handle)
    }

    pub fn read(&mut self, handle: u64, output: &mut [u8]) -> Result<usize, ProcessError> {
        self.session_mut(handle)?.read(output)
    }

    pub fn write(&mut self, handle: u64, input: &[u8]) -> Result<usize, ProcessError> {
        self.session_mut(handle)?.write(input)
    }

    pub fn resize(&mut self, handle: u64, rows: u16, columns: u16) -> Result<(), ProcessError> {
        self.session_mut(handle)?.resize(rows, columns)
    }

    pub fn write_terminal_damage(
        &mut self,
        handle: u64,
        output: &mut [u8],
        full_snapshot: bool,
    ) -> Result<usize, ProcessError> {
        self.session_mut(handle)?
            .write_terminal_damage(output, full_snapshot)
    }

    pub fn exit_status(&mut self, handle: u64) -> Result<Option<i32>, ProcessError> {
        Ok(self.session_mut(handle)?.exit_status())
    }

    pub fn waiter(&self, handle: u64) -> Result<PtyWaiter, ProcessError> {
        Ok(self.session(handle)?.waiter())
    }

    pub fn is_empty(&self) -> bool {
        self.slots.iter().all(|slot| slot.session.is_none())
    }

    pub fn close(&mut self, handle: u64) -> Result<(), ProcessError> {
        let (index, generation) =
            decode_pty_handle(handle).ok_or(ProcessError::InvalidPtyHandle)?;
        let slot = self
            .slots
            .get_mut(index)
            .filter(|slot| slot.generation == generation)
            .ok_or(ProcessError::InvalidPtyHandle)?;
        let mut session = slot.session.take().ok_or(ProcessError::InvalidPtyHandle)?;
        session.close();
        Ok(())
    }

    fn session_mut(&mut self, handle: u64) -> Result<&mut PtySession, ProcessError> {
        let (index, generation) =
            decode_pty_handle(handle).ok_or(ProcessError::InvalidPtyHandle)?;
        self.slots
            .get_mut(index)
            .filter(|slot| slot.generation == generation)
            .and_then(|slot| slot.session.as_mut())
            .ok_or(ProcessError::InvalidPtyHandle)
    }

    fn session(&self, handle: u64) -> Result<&PtySession, ProcessError> {
        let (index, generation) =
            decode_pty_handle(handle).ok_or(ProcessError::InvalidPtyHandle)?;
        self.slots
            .get(index)
            .filter(|slot| slot.generation == generation)
            .and_then(|slot| slot.session.as_ref())
            .ok_or(ProcessError::InvalidPtyHandle)
    }
}

impl Default for PtyRegistry {
    fn default() -> Self {
        Self::new()
    }
}

fn encode_pty_handle(index: usize, generation: u32) -> Result<u64, ProcessError> {
    let index = u32::try_from(index)
        .ok()
        .and_then(|index| index.checked_add(1))
        .ok_or(ProcessError::PtyLimit)?;
    Ok((u64::from(generation) << 32) | u64::from(index))
}

fn decode_pty_handle(handle: u64) -> Option<(usize, u32)> {
    let encoded_index = u32::try_from(handle & u64::from(u32::MAX)).ok()?;
    let generation = u32::try_from(handle >> 32).ok()?;
    if encoded_index == 0 || generation == 0 {
        return None;
    }
    Some((usize::try_from(encoded_index - 1).ok()?, generation))
}

fn validate_pty_size(rows: u16, columns: u16) -> Result<(), ProcessError> {
    if !(MIN_ROWS..=MAX_ROWS).contains(&rows) || !(MIN_COLUMNS..=MAX_COLUMNS).contains(&columns) {
        return Err(ProcessError::InvalidPtySize);
    }
    Ok(())
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

#[derive(Debug, Eq, PartialEq)]
struct LaunchPlan {
    program: PathBuf,
    argv0: String,
    interpreter_argument: Option<String>,
    script: Option<PathBuf>,
}

fn prepare_launch(
    root: &Path,
    command: &str,
    command_path: PathBuf,
) -> Result<LaunchPlan, ProcessError> {
    let mut file = File::open(&command_path)?;
    let mut header = [0_u8; MAX_SHEBANG_BYTES];
    let length = file.read(&mut header)?;
    if header[..length].starts_with(b"\x7fELF") {
        return Ok(LaunchPlan {
            program: command_path,
            argv0: command.to_owned(),
            interpreter_argument: None,
            script: None,
        });
    }
    if !header[..length].starts_with(b"#!") {
        return Err(ProcessError::UnsupportedProgram);
    }
    let line_end = match header[..length].iter().position(|byte| *byte == b'\n') {
        Some(line_end) => line_end,
        None if length < header.len() => length,
        None => return Err(ProcessError::InvalidInterpreter),
    };
    let shebang = std::str::from_utf8(&header[2..line_end])
        .map_err(|_| ProcessError::InvalidInterpreter)?
        .trim_end_matches('\r')
        .trim();
    let (interpreter_path, interpreter_argument) = match shebang.split_once(char::is_whitespace) {
        Some((path, argument)) => {
            let argument = argument.trim();
            if argument.is_empty() {
                (path, None)
            } else {
                if argument.len() > MAX_COMMAND_ARGUMENT_BYTES || argument.as_bytes().contains(&0) {
                    return Err(ProcessError::InvalidInterpreter);
                }
                (path, Some(argument.to_owned()))
            }
        }
        None => (shebang, None),
    };
    let interpreter_name =
        conventional_command_name(interpreter_path).ok_or(ProcessError::InvalidInterpreter)?;
    let interpreter = resolve_installed_command(root, interpreter_name)
        .map_err(|_| ProcessError::InvalidInterpreter)?;
    let mut interpreter_file = File::open(&interpreter)?;
    let mut magic = [0_u8; 4];
    if interpreter_file.read_exact(&mut magic).is_err() || magic != *b"\x7fELF" {
        return Err(ProcessError::InvalidInterpreter);
    }
    Ok(LaunchPlan {
        program: interpreter,
        argv0: interpreter_name.to_owned(),
        interpreter_argument,
        script: Some(command_path),
    })
}

fn conventional_command_name(path: &str) -> Option<&str> {
    let name = path
        .strip_prefix("/usr/bin/")
        .or_else(|| path.strip_prefix("/bin/"))?;
    if name.is_empty() || name.contains('/') {
        return None;
    }
    validate_request(name, &[]).ok()?;
    Some(name)
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

fn exit_code(status: std::process::ExitStatus) -> i32 {
    status
        .code()
        .or_else(|| status.signal().map(|signal| -signal))
        .unwrap_or(-1)
}

#[allow(unsafe_code)]
mod system {
    use super::PtyWaitEvent;
    use std::ffi::CStr;
    use std::fs::{File, OpenOptions};
    use std::io;
    use std::os::fd::AsRawFd;
    use std::os::raw::{c_char, c_int, c_ulong};
    use std::os::unix::fs::OpenOptionsExt;
    use std::os::unix::process::CommandExt;
    use std::process::Command;
    use std::time::Duration;

    const O_NOCTTY: c_int = 0x100;
    const O_NONBLOCK: c_int = 0x800;
    const O_CLOEXEC: c_int = 0x80000;
    const TIOCSCTTY: c_ulong = 0x540e;
    const TIOCSWINSZ: c_ulong = 0x5414;
    const POLLIN: i16 = 0x0001;
    const POLLOUT: i16 = 0x0004;
    const POLLERR: i16 = 0x0008;
    const POLLHUP: i16 = 0x0010;
    const POLLNVAL: i16 = 0x0020;

    #[repr(C)]
    struct PollDescriptor {
        descriptor: c_int,
        events: i16,
        returned_events: i16,
    }

    #[repr(C)]
    struct WindowSize {
        rows: u16,
        columns: u16,
        pixel_width: u16,
        pixel_height: u16,
    }

    unsafe extern "C" {
        fn kill(process: i32, signal: i32) -> i32;
        fn grantpt(descriptor: c_int) -> c_int;
        fn unlockpt(descriptor: c_int) -> c_int;
        fn ptsname_r(descriptor: c_int, output: *mut c_char, length: usize) -> c_int;
        fn setsid() -> c_int;
        fn ioctl(descriptor: c_int, request: c_ulong, ...) -> c_int;
        fn poll(descriptors: *mut PollDescriptor, count: c_ulong, timeout: c_int) -> c_int;
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

    pub fn open_pty(rows: u16, columns: u16) -> io::Result<(File, File)> {
        let master = OpenOptions::new()
            .read(true)
            .write(true)
            .custom_flags(O_NOCTTY | O_NONBLOCK | O_CLOEXEC)
            .open("/dev/ptmx")?;
        let descriptor = master.as_raw_fd();
        // SAFETY: `descriptor` owns an open PTY master. Both calls take only
        // that integer descriptor and do not retain any Rust-owned memory.
        if unsafe { grantpt(descriptor) } != 0 || unsafe { unlockpt(descriptor) } != 0 {
            return Err(io::Error::last_os_error());
        }
        let mut name = [0 as c_char; 4096];
        // SAFETY: `name` is writable for its complete reported length and the
        // descriptor remains open for the duration of the call.
        if unsafe { ptsname_r(descriptor, name.as_mut_ptr(), name.len()) } != 0 {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: successful `ptsname_r` writes a NUL-terminated string into
        // the fixed buffer.
        let name = unsafe { CStr::from_ptr(name.as_ptr()) };
        let name = name
            .to_str()
            .map_err(|_| io::Error::from(io::ErrorKind::InvalidData))?;
        if !name.starts_with("/dev/pts/")
            || name.len() > 64
            || !name[9..].bytes().all(|byte| byte.is_ascii_digit())
        {
            return Err(io::Error::from(io::ErrorKind::InvalidData));
        }
        let slave = OpenOptions::new()
            .read(true)
            .write(true)
            .custom_flags(O_NOCTTY | O_CLOEXEC)
            .open(name)?;
        resize_pty(descriptor, rows, columns)?;
        Ok((master, slave))
    }

    pub fn resize_pty(descriptor: c_int, rows: u16, columns: u16) -> io::Result<()> {
        let size = WindowSize {
            rows,
            columns,
            pixel_width: 0,
            pixel_height: 0,
        };
        // SAFETY: `size` has the kernel `winsize` layout and remains valid for
        // this non-retaining ioctl call.
        if unsafe { ioctl(descriptor, TIOCSWINSZ, &size) } == 0 {
            Ok(())
        } else {
            Err(io::Error::last_os_error())
        }
    }

    pub fn wait_for_pty(
        master: c_int,
        wake_reader: c_int,
        timeout: Option<Duration>,
        write_pending: bool,
    ) -> io::Result<PtyWaitEvent> {
        let timeout_millis = timeout.map_or(-1, |duration| {
            let milliseconds = duration.as_millis().max(1);
            i32::try_from(milliseconds).unwrap_or(i32::MAX)
        });
        let mut descriptors = [
            PollDescriptor {
                descriptor: master,
                events: POLLIN | if write_pending { POLLOUT } else { 0 },
                returned_events: 0,
            },
            PollDescriptor {
                descriptor: wake_reader,
                events: POLLIN,
                returned_events: 0,
            },
        ];
        loop {
            // SAFETY: `descriptors` is a writable two-element pollfd-compatible
            // array that remains alive for the complete non-retaining call.
            let result = unsafe { poll(descriptors.as_mut_ptr(), 2, timeout_millis) };
            if result >= 0 {
                break;
            }
            let error = io::Error::last_os_error();
            if error.kind() != io::ErrorKind::Interrupted {
                return Err(error);
            }
        }
        let terminal_events = descriptors[0].returned_events;
        let wake_events = descriptors[1].returned_events;
        Ok(PtyWaitEvent {
            readable: terminal_events & POLLIN != 0,
            writable: terminal_events & POLLOUT != 0,
            hangup: terminal_events & (POLLERR | POLLHUP | POLLNVAL) != 0,
            woken: wake_events & (POLLIN | POLLERR | POLLHUP | POLLNVAL) != 0,
        })
    }

    pub fn configure_controlling_terminal(command: &mut Command) {
        // SAFETY: the closure uses only async-signal-safe syscalls after fork,
        // does not allocate, and references no captured Rust state.
        unsafe {
            command.pre_exec(|| {
                if setsid() < 0 {
                    return Err(io::Error::last_os_error());
                }
                if ioctl(0, TIOCSCTTY, 0) < 0 {
                    return Err(io::Error::last_os_error());
                }
                Ok(())
            });
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

        fn program(&self, name: &str, content: &[u8]) {
            let path = self.0.join("usr/bin").join(name);
            let mut file = File::create(&path).expect("program");
            file.write_all(content).expect("write program");
            file.set_permissions(fs::Permissions::from_mode(0o755))
                .expect("program mode");
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
        )
        .expect("command environment");
        assert_eq!(environment.path_bridge, bridge);
        let launch = LaunchPlan {
            program: root.0.join("usr/bin/loader"),
            argv0: "loader".to_owned(),
            interpreter_argument: None,
            script: None,
        };
        let command = environment.build_command(&launch, &[], "xterm-256color");
        let value = |name: &str| {
            command
                .get_envs()
                .find_map(|(key, value)| (key == name).then_some(value).flatten())
        };
        assert_eq!(value("HOME"), Some(OsStr::new("/home/archphene")));
        assert_eq!(
            value("PATH"),
            Some(OsStr::new("/usr/local/sbin:/usr/local/bin:/usr/bin")),
        );
        assert_eq!(value("LANG"), Some(OsStr::new("C.UTF-8")));
        assert_eq!(value("LC_ALL"), Some(OsStr::new("C.UTF-8")));
        let expected_locale_path = root.0.join("usr/lib/locale");
        assert_eq!(value("LOCPATH"), Some(expected_locale_path.as_os_str()),);
    }

    #[test]
    fn launch_plans_use_only_installed_elf_interpreters() {
        let root = TestRoot::new();
        root.program("bash", b"\x7fELF interpreter");
        root.program("env", b"\x7fELF env");
        root.program("direct", b"\x7fELF direct");
        root.program("script", b"#!/usr/bin/bash -e\nprintf ok\n");
        root.program("env-script", b"#!/usr/bin/env bash\nprintf ok\n");

        assert_eq!(
            prepare_launch(&root.0, "direct", root.0.join("usr/bin/direct"),).expect("direct plan"),
            LaunchPlan {
                program: root.0.join("usr/bin/direct"),
                argv0: "direct".to_owned(),
                interpreter_argument: None,
                script: None,
            }
        );
        assert_eq!(
            prepare_launch(&root.0, "script", root.0.join("usr/bin/script"),).expect("script plan"),
            LaunchPlan {
                program: root.0.join("usr/bin/bash"),
                argv0: "bash".to_owned(),
                interpreter_argument: Some("-e".to_owned()),
                script: Some(root.0.join("usr/bin/script")),
            }
        );
        assert_eq!(
            prepare_launch(&root.0, "env-script", root.0.join("usr/bin/env-script"),)
                .expect("env script plan")
                .program,
            root.0.join("usr/bin/env")
        );
    }

    #[test]
    fn launch_plans_reject_android_and_recursive_script_interpreters() {
        let root = TestRoot::new();
        root.program("android", b"#!/system/bin/sh\nexit 0\n");
        root.program("recursive", b"#!/usr/bin/other\nexit 0\n");
        root.program("other", b"#!/usr/bin/recursive\nexit 0\n");
        assert!(matches!(
            prepare_launch(&root.0, "android", root.0.join("usr/bin/android"),),
            Err(ProcessError::InvalidInterpreter)
        ));
        assert!(matches!(
            prepare_launch(&root.0, "recursive", root.0.join("usr/bin/recursive"),),
            Err(ProcessError::InvalidInterpreter)
        ));
    }

    #[test]
    fn pty_allocation_and_resize_are_bounded() {
        assert!(validate_pty_size(24, 80).is_ok());
        assert!(matches!(
            validate_pty_size(0, 80),
            Err(ProcessError::InvalidPtySize)
        ));
        assert!(matches!(
            validate_pty_size(24, MAX_PTY_COLUMNS + 1),
            Err(ProcessError::InvalidPtySize)
        ));
        assert!(matches!(
            validate_pty_size(MAX_PTY_ROWS + 1, 80),
            Err(ProcessError::InvalidPtySize)
        ));
        let (master, _slave) = system::open_pty(24, 80).expect("PTY pair");
        system::resize_pty(master.as_raw_fd(), 40, 120).expect("PTY resize");
    }

    #[test]
    fn pty_waiter_reports_output_and_explicit_wakes() {
        let (master, mut slave) = system::open_pty(24, 80).expect("PTY pair");
        let waiter = PtyWaiter::new(&master).expect("PTY waiter");
        let blocking_waiter = waiter.clone();
        let wait_thread = thread::spawn(move || {
            blocking_waiter
                .wait(Some(Duration::from_secs(1)), false)
                .expect("blocking wake event")
        });
        thread::sleep(Duration::from_millis(20));
        waiter.signal().expect("signal waiter");
        let wake = wait_thread.join().expect("wait thread");
        assert!(wake.woken);
        assert!(!wake.writable);

        let writable = waiter
            .wait(Some(Duration::from_secs(1)), true)
            .expect("write-readiness event");
        assert!(writable.writable);

        slave.write_all(b"ready").expect("PTY output");
        let output = waiter
            .wait(Some(Duration::from_secs(1)), false)
            .expect("output event");
        assert!(output.readable);
    }

    #[test]
    fn pty_preserves_exit_status_after_terminal_eof() {
        let (master, slave) = system::open_pty(24, 80).expect("PTY pair");
        let waiter = PtyWaiter::new(&master).expect("PTY waiter");
        let input = slave.try_clone().expect("PTY input");
        let output = slave.try_clone().expect("PTY output");
        let mut command = Command::new("/bin/sh");
        system::configure_controlling_terminal(&mut command);
        let child = command
            .arg("-c")
            .arg("exit 7")
            .stdin(Stdio::from(input))
            .stdout(Stdio::from(output))
            .stderr(Stdio::from(slave))
            .spawn()
            .expect("PTY child");
        drop(command);
        let mut session = PtySession {
            master,
            waiter,
            child: Some(child),
            exit_status: None,
            rows: 24,
            columns: 80,
            terminal: Terminal::new(24, 80).expect("terminal state"),
        };
        let deadline = Instant::now() + Duration::from_secs(2);
        let mut output = [0_u8; 64];
        while session.exit_status().is_none() && Instant::now() < deadline {
            session.read(&mut output).expect("PTY read");
            thread::sleep(Duration::from_millis(10));
        }
        assert_eq!(session.exit_status(), Some(7));
    }

    #[test]
    fn exact_pty_reads_feed_versioned_terminal_damage() {
        let (master, mut slave) = system::open_pty(2, 4).expect("PTY pair");
        let waiter = PtyWaiter::new(&master).expect("PTY waiter");
        let mut session = PtySession {
            master,
            waiter,
            child: None,
            exit_status: None,
            rows: 2,
            columns: 4,
            terminal: Terminal::new(2, 4).expect("terminal state"),
        };
        let mut damage = vec![0_u8; MAX_TERMINAL_DAMAGE_BYTES];
        session
            .write_terminal_damage(&mut damage, false)
            .expect("initial damage");
        slave.write_all(b"\x1b[32;1mOK").expect("terminal output");
        let mut output = [0_u8; 64];
        let deadline = Instant::now() + Duration::from_secs(1);
        while Instant::now() < deadline {
            if session.read(&mut output).expect("PTY read") != 0 {
                break;
            }
            thread::sleep(Duration::from_millis(5));
        }
        let length = session
            .write_terminal_damage(&mut damage, false)
            .expect("terminal damage");
        assert_eq!(&damage[..4], b"ATRM");
        assert_eq!(
            length,
            archphene_terminal::DAMAGE_HEADER_SIZE + 4 * archphene_terminal::DAMAGE_CELL_SIZE
        );
        assert_eq!(
            u32::from_le_bytes(damage[32..36].try_into().expect("cell codepoint")),
            u32::from(b'O')
        );
        assert_eq!(
            u32::from_le_bytes(damage[96..100].try_into().expect("foreground")),
            2
        );
        assert_eq!(damage[104] & 1, 1);
    }
}
