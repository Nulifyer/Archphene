#![deny(unsafe_code)]

use std::ffi::{OsStr, OsString};
use std::fmt;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::os::fd::{AsRawFd, OwnedFd};
use std::os::unix::fs::{MetadataExt, OpenOptionsExt, PermissionsExt};
use std::os::unix::net::UnixStream;
use std::os::unix::process::{CommandExt, ExitStatusExt};
use std::path::{Component, Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::thread;
use std::time::{Duration, Instant};

use archphene_terminal::{
    MAX_COLUMNS, MAX_DAMAGE_BYTES, MAX_ROWS, MAX_SELECTION_BYTES, MIN_COLUMNS, MIN_ROWS, Terminal,
};

pub const MAX_COMMAND_NAME_BYTES: usize = 128;
pub const MAX_COMMAND_ARGUMENTS: usize = 512;
pub const MAX_COMMAND_ARGUMENT_BYTES: usize = 4 * 1024;
pub const MAX_COMMAND_REQUEST_BYTES: usize = 16 * 1024;
pub const MAX_COMMAND_OUTPUT_BYTES: usize = 15 * 1024;
pub const COMMAND_TIMEOUT: Duration = Duration::from_secs(30);
pub const MAX_PTY_TRANSFER_BYTES: usize = 16 * 1024;
pub const MAX_PTY_ROWS: u16 = MAX_ROWS;
pub const MAX_PTY_COLUMNS: u16 = MAX_COLUMNS;
pub const MAX_PTY_SESSIONS: usize = 4;
pub const MAX_GUI_SESSIONS: usize = 16;
pub const MAX_GUI_LOG_BYTES: usize = 16 * 1024;
pub const MAX_BATCH_LOG_BYTES: usize = 64 * 1024;
pub const BATCH_TIMEOUT: Duration = Duration::from_secs(30 * 60);
pub const MAX_TERMINAL_DAMAGE_BYTES: usize = MAX_DAMAGE_BYTES;
pub const MAX_TERMINAL_SELECTION_BYTES: usize = MAX_SELECTION_BYTES;
pub const MAX_WAYLAND_DISPLAY_BYTES: usize = 64;

const MAX_SYMLINKS: usize = 16;
const MAX_SHEBANG_BYTES: usize = 256;
const MAX_GUI_LOG_DRAIN_BYTES: usize = 64 * 1024;
const GUI_CLOSE_GRACE: Duration = Duration::from_millis(750);
const GUI_TERMINATE_GRACE: Duration = Duration::from_millis(750);
const MAX_GDK_PIXBUF_MODULE_FILE_BYTES: u64 = 16 * 1024;
const GTK_SETTINGS_LOGICAL_PATH: &str = "/home/archphene/.config/gtk-3.0/settings.ini";
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
    InvalidGuiHandle,
    GuiLimit,
    InvalidWaylandDisplay,
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
            Self::InvalidGuiHandle => {
                formatter.write_str("invalid or closed graphical session handle")
            }
            Self::GuiLimit => formatter.write_str("graphical session limit reached"),
            Self::InvalidWaylandDisplay => formatter.write_str("invalid private Wayland display"),
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

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct GuiAppearance {
    dark: bool,
    font_percent: u16,
    control_visual_dp: u16,
    control_target_dp: u16,
    accent: [u8; 3],
    background: [u8; 3],
    foreground: [u8; 3],
}

impl GuiAppearance {
    pub fn new(
        dark: bool,
        font_percent: u16,
        control_visual_dp: u16,
        control_target_dp: u16,
        accent: [u8; 3],
        background: [u8; 3],
        foreground: [u8; 3],
    ) -> Result<Self, ProcessError> {
        if !(100..=200).contains(&font_percent)
            || !(12..=48).contains(&control_visual_dp)
            || !(24..=64).contains(&control_target_dp)
            || control_target_dp < control_visual_dp
        {
            return Err(ProcessError::InvalidEnvironment);
        }
        Ok(Self {
            dark,
            font_percent,
            control_visual_dp,
            control_target_dp,
            accent,
            background,
            foreground,
        })
    }
}

impl Default for GuiAppearance {
    fn default() -> Self {
        Self {
            dark: false,
            font_percent: 100,
            control_visual_dp: 20,
            control_target_dp: 32,
            accent: [23, 147, 209],
            background: [239, 240, 241],
            foreground: [35, 38, 41],
        }
    }
}

#[derive(Clone, Debug)]
pub struct CommandEnvironment {
    arch_root: PathBuf,
    loader: PathBuf,
    library_path: OsString,
    path_bridge: PathBuf,
    command_directory: PathBuf,
    gdk_pixbuf_module_file: Option<PathBuf>,
    gtk_settings_module: Option<PathBuf>,
    qt_plugin_root: Option<PathBuf>,
    appearance: GuiAppearance,
}

impl CommandEnvironment {
    pub fn new(
        arch_root: &Path,
        loader: &Path,
        library_path: &OsStr,
        path_bridge: &Path,
        command_directory: &Path,
        gdk_pixbuf_module_file: Option<&Path>,
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
        let gdk_pixbuf_module_file = match gdk_pixbuf_module_file {
            Some(path) => {
                if !valid_absolute_path(path) {
                    return Err(ProcessError::InvalidEnvironment);
                }
                let metadata = fs::symlink_metadata(path)?;
                if metadata.file_type().is_symlink()
                    || !metadata.is_file()
                    || metadata.permissions().mode() & 0o022 != 0
                    || metadata.len() == 0
                    || metadata.len() > MAX_GDK_PIXBUF_MODULE_FILE_BYTES
                {
                    return Err(ProcessError::InvalidEnvironment);
                }
                let resolved = path.canonicalize()?;
                let resolved_root = arch_root.canonicalize()?;
                if resolved == resolved_root || !resolved.starts_with(&resolved_root) {
                    return Err(ProcessError::InvalidEnvironment);
                }
                Some(resolved)
            }
            None => None,
        };
        Ok(Self {
            arch_root: arch_root.to_path_buf(),
            loader: loader.to_path_buf(),
            library_path: library_path.to_os_string(),
            path_bridge: resolved_bridge,
            command_directory: command_directory.to_path_buf(),
            gdk_pixbuf_module_file,
            gtk_settings_module: None,
            qt_plugin_root: None,
            appearance: GuiAppearance::default(),
        })
    }

    pub fn with_gui_support(
        mut self,
        gtk_settings_module: Option<&Path>,
        qt_plugin_root: Option<&Path>,
        appearance: GuiAppearance,
    ) -> Result<Self, ProcessError> {
        self.gtk_settings_module =
            validate_optional_gui_file(&self.arch_root, gtk_settings_module)?;
        self.qt_plugin_root = validate_optional_gui_directory(&self.arch_root, qt_plugin_root)?;
        write_gui_appearance(&self.arch_root, appearance)?;
        self.appearance = appearance;
        Ok(self)
    }

    pub fn run(&self, command: &str, arguments: &[&str]) -> Result<CommandOutput, ProcessError> {
        self.run_with_identity(command, arguments, false)
    }

    pub fn run_as_root(
        &self,
        command: &str,
        arguments: &[&str],
    ) -> Result<CommandOutput, ProcessError> {
        self.run_with_identity(command, arguments, true)
    }

    fn run_with_identity(
        &self,
        command: &str,
        arguments: &[&str],
        root_identity: bool,
    ) -> Result<CommandOutput, ProcessError> {
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
        let mut command_builder = self.build_command(&launch, arguments, "dumb");
        if root_identity {
            command_builder
                .current_dir(&self.arch_root)
                .env("ARCHPHENE_ROOT_IDENTITY", "1")
                .env("HOME", "/root")
                .env("USER", "root")
                .env("LOGNAME", "root");
        }
        let child = command_builder
            .stdin(Stdio::null())
            .stdout(Stdio::from(output_file))
            .stderr(Stdio::from(error_file))
            .process_group(0)
            .spawn();
        match child {
            Ok(mut child) => self.wait_for_output(&mut child, &output_path),
            Err(error) => Err(ProcessError::Io(error)),
        }
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

    pub fn open_gui(
        &self,
        command: &str,
        arguments: &[&str],
        wayland_display: &str,
    ) -> Result<GuiProcess, ProcessError> {
        validate_request(command, arguments)?;
        validate_wayland_display(wayland_display)?;
        let command_path = resolve_installed_command(&self.arch_root, command)?;
        let launch = prepare_launch(&self.arch_root, command, command_path)?;
        let (log_reader, log_writer) = UnixStream::pair()?;
        log_reader.set_nonblocking(true)?;
        let error_writer = log_writer.try_clone()?;
        let log_writer: OwnedFd = log_writer.into();
        let error_writer: OwnedFd = error_writer.into();
        let child = self
            .build_gui_command(&launch, arguments, wayland_display)
            .stdin(Stdio::null())
            .stdout(Stdio::from(log_writer))
            .stderr(Stdio::from(error_writer))
            .process_group(0)
            .spawn()?;
        Ok(GuiProcess {
            process_group: child.id(),
            child: Some(child),
            leader_exit_status: None,
            exit_status: None,
            log_reader,
            log_bytes: [0; MAX_GUI_LOG_BYTES],
            log_start: 0,
            log_length: 0,
        })
    }

    pub fn open_batch(
        &self,
        command: &str,
        arguments: &[&str],
        working_directory: &Path,
    ) -> Result<Box<BatchProcess>, ProcessError> {
        validate_request(command, arguments)?;
        let working_directory = self.resolve_working_directory(working_directory)?;
        let command_path = resolve_installed_command(&self.arch_root, command)?;
        let launch = prepare_launch(&self.arch_root, command, command_path)?;
        let (log_reader, log_writer) = UnixStream::pair()?;
        log_reader.set_nonblocking(true)?;
        let error_writer = log_writer.try_clone()?;
        let log_writer: OwnedFd = log_writer.into();
        let error_writer: OwnedFd = error_writer.into();
        let child = self
            .build_command(&launch, arguments, "dumb")
            .current_dir(working_directory)
            .stdin(Stdio::null())
            .stdout(Stdio::from(log_writer))
            .stderr(Stdio::from(error_writer))
            .process_group(0)
            .spawn()?;
        Ok(Box::new(BatchProcess {
            process_group: child.id(),
            child: Some(child),
            exit_status: None,
            log_reader,
            log_bytes: [0; MAX_BATCH_LOG_BYTES],
            log_start: 0,
            log_length: 0,
            deadline: Instant::now() + BATCH_TIMEOUT,
        }))
    }

    fn resolve_working_directory(&self, path: &Path) -> Result<PathBuf, ProcessError> {
        if !path.is_absolute() {
            return Err(ProcessError::InvalidEnvironment);
        }
        let root = self.arch_root.canonicalize()?;
        let metadata = fs::symlink_metadata(path)?;
        if metadata.file_type().is_symlink() || !metadata.is_dir() {
            return Err(ProcessError::InvalidEnvironment);
        }
        let resolved = path.canonicalize()?;
        if resolved == root || !resolved.starts_with(&root) {
            return Err(ProcessError::InvalidEnvironment);
        }
        Ok(resolved)
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
            // Chromium/Electron issue some temporary-file syscalls inline instead
            // of through libc, so those paths cannot be translated by the bridge.
            // Keep the directory private while publishing its physical path.
            .env("TMPDIR", self.arch_root.join("tmp"))
            .env(
                "PATH",
                "/home/archphene/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/bin",
            )
            .env("LANG", "C.UTF-8")
            .env("LC_ALL", "C.UTF-8")
            .env("LOCPATH", self.arch_root.join("usr/lib/locale"))
            .env(
                "FONTCONFIG_FILE",
                "/var/lib/archphene/fontconfig/fonts.conf",
            )
            .env("FONTCONFIG_PATH", "/etc/fonts")
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
            .env("ARCHPHENE_RUNTIME_PROGRAM_PATH", &launch.program)
            .env("ARCHPHENE_FAKE_CHROOT", "1");
        if let Some(module_file) = self.gdk_pixbuf_module_file.as_ref() {
            command.env("GDK_PIXBUF_MODULE_FILE", module_file);
        }
        command
    }

    fn build_gui_command(
        &self,
        launch: &LaunchPlan,
        arguments: &[&str],
        wayland_display: &str,
    ) -> Command {
        let mut command = self.build_command(launch, arguments, "xterm-256color");
        command
            .env("WAYLAND_DISPLAY", wayland_display)
            .env("XDG_SESSION_TYPE", "wayland")
            .env("XDG_CURRENT_DESKTOP", "Archphene")
            .env("GDK_BACKEND", "wayland")
            .env(
                "GTK_THEME",
                if self.appearance.dark {
                    "Adwaita:dark"
                } else {
                    "Adwaita"
                },
            )
            .env("ARCHPHENE_GTK_SETTINGS_FILE", GTK_SETTINGS_LOGICAL_PATH)
            .env("GTK_USE_PORTAL", "0")
            .env("GIO_USE_VFS", "local")
            .env("XDG_DATA_DIRS", "/usr/local/share:/usr/share")
            .env("GSETTINGS_SCHEMA_DIR", "/usr/share/glib-2.0/schemas")
            .env("QT_QPA_PLATFORM", "wayland")
            .env("QT_QPA_PLATFORMTHEME", "archphene")
            .env("QT_STYLE_OVERRIDE", "archphene")
            .env("QT_FONT_DPI", "96")
            .env("QT_SCALE_FACTOR_ROUNDING_POLICY", "PassThrough")
            .env(
                "ARCHPHENE_FONT_POINT_SIZE",
                ((12_u32 * u32::from(self.appearance.font_percent) + 50) / 100).to_string(),
            )
            .env(
                "ARCHPHENE_QT_CONTROL_MIN_SIZE",
                self.appearance.control_target_dp.to_string(),
            )
            .env(
                "ARCHPHENE_QT_CONTROL_VISUAL_SIZE",
                self.appearance.control_visual_dp.to_string(),
            )
            .env(
                "ARCHPHENE_COLOR_SCHEME",
                if self.appearance.dark {
                    "dark"
                } else {
                    "light"
                },
            )
            .env("SDL_VIDEODRIVER", "wayland")
            .env("ARCHPHENE_SUPERVISED_PROCESS_GROUP", "1");
        if let Some(module) = self.gtk_settings_module.as_ref() {
            command.env("GTK_MODULES", module);
        }
        if let Some(root) = self.qt_plugin_root.as_ref() {
            command.env("QT_PLUGIN_PATH", root);
        }
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

pub struct BatchProcess {
    process_group: u32,
    child: Option<std::process::Child>,
    exit_status: Option<i32>,
    log_reader: UnixStream,
    log_bytes: [u8; MAX_BATCH_LOG_BYTES],
    log_start: usize,
    log_length: usize,
    deadline: Instant,
}

impl BatchProcess {
    pub fn exit_status(&mut self) -> Result<Option<i32>, ProcessError> {
        self.drain_logs()?;
        if self.exit_status.is_none() && Instant::now() >= self.deadline {
            self.close();
            return Err(ProcessError::Timeout);
        }
        if self.exit_status.is_none()
            && let Some(child) = self.child.as_mut()
            && let Some(status) = child.try_wait()?
        {
            // The build leader may leave helpers behind. Kill the group while
            // the leader PID is still reserved, then reap the direct child.
            let _ = system::kill_process_group(self.process_group);
            self.exit_status = Some(exit_code(status));
            self.child = None;
            self.drain_logs()?;
        }
        Ok(self.exit_status)
    }

    pub fn read_logs(&mut self, output: &mut [u8]) -> Result<usize, ProcessError> {
        self.drain_logs()?;
        let length = self.log_length.min(output.len());
        let skipped = self.log_length - length;
        for (index, destination) in output[..length].iter_mut().enumerate() {
            *destination = self.log_bytes[(self.log_start + skipped + index) % MAX_BATCH_LOG_BYTES];
        }
        Ok(length)
    }

    pub fn close(&mut self) {
        let Some(mut child) = self.child.take() else {
            return;
        };
        terminate_process_group(&mut child);
        self.exit_status = child.wait().ok().map(exit_code);
        let _ = self.drain_logs();
    }

    fn drain_logs(&mut self) -> Result<(), ProcessError> {
        let mut chunk = [0_u8; 4096];
        let mut drained = 0_usize;
        while drained < MAX_GUI_LOG_DRAIN_BYTES {
            let remaining = MAX_GUI_LOG_DRAIN_BYTES - drained;
            let read_length = remaining.min(chunk.len());
            match self.log_reader.read(&mut chunk[..read_length]) {
                Ok(0) => return Ok(()),
                Ok(length) => {
                    for byte in &chunk[..length] {
                        if self.log_length < MAX_BATCH_LOG_BYTES {
                            let index = (self.log_start + self.log_length) % MAX_BATCH_LOG_BYTES;
                            self.log_bytes[index] = *byte;
                            self.log_length += 1;
                        } else {
                            self.log_bytes[self.log_start] = *byte;
                            self.log_start = (self.log_start + 1) % MAX_BATCH_LOG_BYTES;
                        }
                    }
                    drained += length;
                }
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => return Ok(()),
                Err(error) => return Err(ProcessError::Io(error)),
            }
        }
        Ok(())
    }
}

impl Drop for BatchProcess {
    fn drop(&mut self) {
        self.close();
    }
}

pub struct GuiProcess {
    process_group: u32,
    child: Option<std::process::Child>,
    leader_exit_status: Option<i32>,
    exit_status: Option<i32>,
    log_reader: UnixStream,
    log_bytes: [u8; MAX_GUI_LOG_BYTES],
    log_start: usize,
    log_length: usize,
}

impl GuiProcess {
    pub fn exit_status(&mut self) -> Result<Option<i32>, ProcessError> {
        self.drain_logs()?;
        if self.leader_exit_status.is_none()
            && let Some(child) = self.child.as_mut()
            && let Some(status) = child.try_wait()?
        {
            self.leader_exit_status = Some(exit_code(status));
            self.child = None;
        }
        if self.exit_status.is_none()
            && let Some(status) = self.leader_exit_status
            && !system::process_group_exists(self.process_group)?
        {
            self.exit_status = Some(status);
            self.drain_logs()?;
        }
        Ok(self.exit_status)
    }

    pub fn read_logs(&mut self, output: &mut [u8]) -> Result<usize, ProcessError> {
        self.drain_logs()?;
        let length = self.log_length.min(output.len());
        let skipped = self.log_length - length;
        for (index, destination) in output[..length].iter_mut().enumerate() {
            *destination = self.log_bytes[(self.log_start + skipped + index) % MAX_GUI_LOG_BYTES];
        }
        Ok(length)
    }

    fn drain_logs(&mut self) -> Result<(), ProcessError> {
        let mut chunk = [0_u8; 4096];
        let mut drained = 0_usize;
        while drained < MAX_GUI_LOG_DRAIN_BYTES {
            let remaining = MAX_GUI_LOG_DRAIN_BYTES - drained;
            let read_length = remaining.min(chunk.len());
            match self.log_reader.read(&mut chunk[..read_length]) {
                Ok(0) => return Ok(()),
                Ok(length) => {
                    self.append_logs(&chunk[..length]);
                    drained += length;
                }
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => return Ok(()),
                Err(error) => return Err(ProcessError::Io(error)),
            }
        }
        Ok(())
    }

    fn append_logs(&mut self, bytes: &[u8]) {
        for byte in bytes {
            if self.log_length < MAX_GUI_LOG_BYTES {
                let index = (self.log_start + self.log_length) % MAX_GUI_LOG_BYTES;
                self.log_bytes[index] = *byte;
                self.log_length += 1;
            } else {
                self.log_bytes[self.log_start] = *byte;
                self.log_start = (self.log_start + 1) % MAX_GUI_LOG_BYTES;
            }
        }
    }

    fn wait_for_group_exit(&mut self, deadline: Instant) -> bool {
        loop {
            let _ = self.drain_logs();
            if self.leader_exit_status.is_none()
                && let Some(child) = self.child.as_mut()
                && let Ok(Some(status)) = child.try_wait()
            {
                self.leader_exit_status = Some(exit_code(status));
                self.child = None;
            }
            if self.process_group == 0
                || matches!(system::process_group_exists(self.process_group), Ok(false))
            {
                return true;
            }
            if Instant::now() >= deadline {
                return false;
            }
            thread::sleep(Duration::from_millis(20));
        }
    }

    pub fn close(&mut self) {
        // The compositor sends xdg_toplevel.close before reaching this point.
        // Give desktop clients a short opportunity to flush databases and
        // session state, then fall back through TERM to the hard lifecycle
        // boundary required when the Android launcher has gone away.
        if !self.wait_for_group_exit(Instant::now() + GUI_CLOSE_GRACE) {
            let _ = system::signal_process_group(self.process_group, 15);
            if !self.wait_for_group_exit(Instant::now() + GUI_TERMINATE_GRACE) {
                let _ = system::kill_process_group(self.process_group);
            }
        }
        if let Some(mut child) = self.child.take() {
            self.leader_exit_status = child.wait().ok().map(exit_code);
        }
        self.exit_status = self.leader_exit_status;
        let _ = self.drain_logs();
    }
}

impl Drop for GuiProcess {
    fn drop(&mut self) {
        self.close();
    }
}

pub struct GuiRegistry {
    slots: [GuiSlot; MAX_GUI_SESSIONS],
}

struct GuiSlot {
    generation: u32,
    process: Option<Box<GuiProcess>>,
}

impl GuiRegistry {
    pub fn new() -> Self {
        Self {
            slots: std::array::from_fn(|_| GuiSlot {
                generation: 0,
                process: None,
            }),
        }
    }

    pub fn open(
        &mut self,
        environment: &CommandEnvironment,
        command: &str,
        arguments: &[&str],
        wayland_display: &str,
    ) -> Result<u64, ProcessError> {
        let (index, slot) = self
            .slots
            .iter_mut()
            .enumerate()
            .find(|(_, slot)| slot.process.is_none())
            .ok_or(ProcessError::GuiLimit)?;
        let process = environment.open_gui(command, arguments, wayland_display)?;
        slot.generation = slot.generation.wrapping_add(1).max(1);
        let handle = encode_gui_handle(index, slot.generation)?;
        // Keep the fixed 16 KiB log ring with its active process rather than
        // inflating every empty registry slot or the runtime stack frame.
        slot.process = Some(Box::new(process));
        Ok(handle)
    }

    pub fn exit_status(&mut self, handle: u64) -> Result<Option<i32>, ProcessError> {
        self.process_mut(handle)?.exit_status()
    }

    pub fn read_logs(&mut self, handle: u64, output: &mut [u8]) -> Result<usize, ProcessError> {
        self.process_mut(handle)?.read_logs(output)
    }

    pub fn close(&mut self, handle: u64) -> Result<(), ProcessError> {
        let (index, generation) =
            decode_gui_handle(handle).ok_or(ProcessError::InvalidGuiHandle)?;
        let slot = self
            .slots
            .get_mut(index)
            .filter(|slot| slot.generation == generation)
            .ok_or(ProcessError::InvalidGuiHandle)?;
        let mut process = slot.process.take().ok_or(ProcessError::InvalidGuiHandle)?;
        process.close();
        Ok(())
    }

    fn process_mut(&mut self, handle: u64) -> Result<&mut GuiProcess, ProcessError> {
        let (index, generation) =
            decode_gui_handle(handle).ok_or(ProcessError::InvalidGuiHandle)?;
        self.slots
            .get_mut(index)
            .filter(|slot| slot.generation == generation)
            .and_then(|slot| slot.process.as_deref_mut())
            .ok_or(ProcessError::InvalidGuiHandle)
    }
}

impl Default for GuiRegistry {
    fn default() -> Self {
        Self::new()
    }
}

fn encode_gui_handle(index: usize, generation: u32) -> Result<u64, ProcessError> {
    let encoded_index = u32::try_from(index)
        .ok()
        .and_then(|value| value.checked_add(1))
        .ok_or(ProcessError::GuiLimit)?;
    Ok((u64::from(generation) << 32) | u64::from(encoded_index))
}

fn decode_gui_handle(handle: u64) -> Option<(usize, u32)> {
    let encoded_index = u32::try_from(handle & u64::from(u32::MAX)).ok()?;
    let generation = u32::try_from(handle >> 32).ok()?;
    if encoded_index == 0 || generation == 0 {
        return None;
    }
    Some((usize::try_from(encoded_index - 1).ok()?, generation))
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
                self.flush_terminal_replies()?;
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
        if !self.flush_terminal_replies()? {
            return Ok(0);
        }
        match self.master.write(input) {
            Ok(length) => Ok(length),
            Err(error) if error.kind() == io::ErrorKind::WouldBlock => Ok(0),
            Err(error) => Err(ProcessError::Io(error)),
        }
    }

    fn flush_terminal_replies(&mut self) -> Result<bool, ProcessError> {
        while !self.terminal.pending_reply().is_empty() {
            match self.master.write(self.terminal.pending_reply()) {
                Ok(0) => return Ok(false),
                Ok(length) => self.terminal.consume_reply(length),
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => return Ok(false),
                Err(error) => return Err(ProcessError::Io(error)),
            }
        }
        Ok(true)
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
        viewport_offset: u32,
    ) -> Result<usize, ProcessError> {
        if viewport_offset != 0 {
            self.terminal.write_view_damage(output, viewport_offset)
        } else if full_snapshot {
            self.terminal.write_full_damage(output)
        } else {
            self.terminal.write_damage(output)
        }
        .map_err(|_| ProcessError::InvalidArgument)
    }

    pub fn write_terminal_selection(
        &mut self,
        output: &mut [u8],
        origin_epoch: u64,
        start_row: u32,
        start_column: u16,
        end_row: u32,
        end_column: u16,
    ) -> Result<usize, ProcessError> {
        self.terminal
            .write_selection(
                output,
                origin_epoch,
                start_row,
                start_column,
                end_row,
                end_column,
            )
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
        viewport_offset: u32,
    ) -> Result<usize, ProcessError> {
        self.session_mut(handle)?
            .write_terminal_damage(output, full_snapshot, viewport_offset)
    }

    pub fn write_terminal_selection(
        &mut self,
        handle: u64,
        output: &mut [u8],
        origin_epoch: u64,
        start_row: u32,
        start_column: u16,
        end_row: u32,
        end_column: u16,
    ) -> Result<usize, ProcessError> {
        self.session_mut(handle)?.write_terminal_selection(
            output,
            origin_epoch,
            start_row,
            start_column,
            end_row,
            end_column,
        )
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

fn validate_wayland_display(display: &str) -> Result<(), ProcessError> {
    if display.is_empty()
        || display.len() > MAX_WAYLAND_DISPLAY_BYTES
        || !display
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
    {
        return Err(ProcessError::InvalidWaylandDisplay);
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
                physical_target_under_root(root, &target)?.unwrap_or_else(|| {
                    root.join(
                        target
                            .strip_prefix("/")
                            .expect("absolute paths always have a root prefix"),
                    )
                })
            } else {
                path.parent()
                    .ok_or_else(|| ProcessError::UnsafeCommand(path.clone()))?
                    .join(target)
            };
            path = normalize_under_root(root, &candidate)?;
            continue;
        }
        if !safe_installed_command(&metadata) || metadata.mode() & 0o111 == 0 {
            return Err(ProcessError::UnsafeCommand(path));
        }
        return Ok(path);
    }
    Err(ProcessError::UnsafeCommand(path))
}

fn physical_target_under_root(root: &Path, target: &Path) -> Result<Option<PathBuf>, ProcessError> {
    let root_metadata = fs::metadata(root)?;
    let mut ancestor = Some(target);
    while let Some(path) = ancestor {
        match fs::metadata(path) {
            Ok(metadata)
                if metadata.dev() == root_metadata.dev()
                    && metadata.ino() == root_metadata.ino() =>
            {
                let relative = target
                    .strip_prefix(path)
                    .map_err(|_| ProcessError::UnsafeCommand(target.to_path_buf()))?;
                return Ok(Some(root.join(relative)));
            }
            Ok(_) => {}
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => return Err(ProcessError::Io(error)),
        }
        ancestor = path.parent();
    }
    Ok(None)
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

fn validate_optional_gui_file(
    arch_root: &Path,
    path: Option<&Path>,
) -> Result<Option<PathBuf>, ProcessError> {
    let Some(path) = path else {
        return Ok(None);
    };
    if !valid_absolute_path(path) {
        return Err(ProcessError::InvalidEnvironment);
    }
    let metadata = fs::symlink_metadata(path)?;
    if !metadata.file_type().is_symlink() {
        return Err(ProcessError::InvalidEnvironment);
    }
    let resolved = path.canonicalize()?;
    let metadata = fs::symlink_metadata(&resolved)?;
    if !safe_regular_file(&metadata) || resolved == arch_root {
        return Err(ProcessError::InvalidEnvironment);
    }
    Ok(Some(path.to_path_buf()))
}

fn validate_optional_gui_directory(
    arch_root: &Path,
    path: Option<&Path>,
) -> Result<Option<PathBuf>, ProcessError> {
    let Some(path) = path else {
        return Ok(None);
    };
    if !valid_absolute_path(path) || path == arch_root {
        return Err(ProcessError::InvalidEnvironment);
    }
    let metadata = fs::symlink_metadata(path)?;
    if metadata.file_type().is_symlink() || !metadata.is_dir() || metadata.mode() & 0o022 != 0 {
        return Err(ProcessError::InvalidEnvironment);
    }
    let resolved_root = arch_root.canonicalize()?;
    let resolved = path.canonicalize()?;
    if resolved == resolved_root || !resolved.starts_with(&resolved_root) {
        return Err(ProcessError::InvalidEnvironment);
    }
    Ok(Some(path.to_path_buf()))
}

fn write_gui_appearance(root: &Path, appearance: GuiAppearance) -> Result<(), ProcessError> {
    let config = root.join("home/archphene/.config");
    let gtk3 = config.join("gtk-3.0");
    let gtk4 = config.join("gtk-4.0");
    for directory in [&config, &gtk3, &gtk4] {
        prepare_gui_config_directory(directory)?;
    }

    let point_size = (12_u32 * u32::from(appearance.font_percent) + 50) / 100;
    let font_pixels = (16_u32 * u32::from(appearance.font_percent) + 50) / 100;
    let theme = if appearance.dark {
        "Adwaita-dark"
    } else {
        "Adwaita"
    };
    let settings = format!(
        "[Settings]\n\
gtk-theme-name={theme}\n\
gtk-icon-theme-name=Adwaita\n\
gtk-font-name=Noto Sans {point_size}\n\
gtk-application-prefer-dark-theme={}\n\
gtk-menu-images=false\n\
gtk-button-images=false\n",
        appearance.dark,
    );
    let accent = css_color(appearance.accent);
    let background = css_color(appearance.background);
    let foreground = css_color(appearance.foreground);
    let visual = appearance.control_visual_dp;
    let target = appearance.control_target_dp;
    let padding = target.saturating_div(4).max(4);
    let css = format!(
        "@define-color accent_color {accent};\n\
@define-color accent_bg_color {accent};\n\
@define-color accent_fg_color {background};\n\
@define-color theme_selected_bg_color {accent};\n\
@define-color theme_selected_fg_color {background};\n\
window, dialog, popover, menu {{ font-size: {font_pixels}px; }}\n\
button, entry, combobox, menuitem, checkbutton, radiobutton {{ min-height: {target}px; }}\n\
checkbutton check, check, radiobutton radio, radio {{ min-width: {visual}px; min-height: {visual}px; }}\n\
menubar > menuitem, menu menuitem {{ min-height: {target}px; padding: 0 {padding}px; }}\n\
headerbar {{ min-height: {target}px; }}\n\
headerbar button.titlebutton, headerbar .titlebutton {{ min-width: {target}px; min-height: {target}px; padding: 2px; }}\n\
headerbar button.titlebutton image, headerbar .titlebutton image, .titlebar button.titlebutton image, windowcontrols image {{ min-width: {visual}px; min-height: {visual}px; }}\n\
scrollbar slider {{ min-width: {visual}px; min-height: {visual}px; }}\n\
checkbutton check:checked, check:checked, radiobutton radio:checked, radio:checked, switch:checked {{ background-image: none; background-color: @accent_bg_color; color: @accent_fg_color; border-color: @accent_bg_color; }}\n\
text selection, entry selection, label selection, treeview:selected, row:selected {{ background-color: @accent_bg_color; color: @accent_fg_color; }}\n\
tooltip {{ background-color: {background}; color: {foreground}; }}\n",
    );
    for directory in [&gtk3, &gtk4] {
        publish_gui_config(&directory.join("settings.ini"), settings.as_bytes())?;
        publish_gui_config(&directory.join("gtk.css"), css.as_bytes())?;
    }

    let kde = kde_globals(appearance, point_size);
    let kde_path = config.join("kdeglobals");
    if gui_managed_kde_file(&kde_path)? {
        publish_gui_config(&kde_path, kde.as_bytes())?;
    }
    Ok(())
}

fn prepare_gui_config_directory(path: &Path) -> Result<(), ProcessError> {
    match fs::symlink_metadata(path) {
        Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_dir() => {
            Err(ProcessError::InvalidEnvironment)
        }
        Ok(_) => Ok(()),
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            fs::create_dir(path)?;
            fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
            Ok(())
        }
        Err(error) => Err(ProcessError::Io(error)),
    }
}

fn publish_gui_config(path: &Path, content: &[u8]) -> Result<(), ProcessError> {
    if content.is_empty() || content.len() > 64 * 1024 {
        return Err(ProcessError::InvalidEnvironment);
    }
    let parent = path.parent().ok_or(ProcessError::InvalidEnvironment)?;
    let name = path
        .file_name()
        .and_then(OsStr::to_str)
        .ok_or(ProcessError::InvalidEnvironment)?;
    let temporary = parent.join(format!(".{name}.archphene-new"));
    match fs::symlink_metadata(&temporary) {
        Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_file() => {
            return Err(ProcessError::InvalidEnvironment);
        }
        Ok(_) => fs::remove_file(&temporary)?,
        Err(error) if error.kind() == io::ErrorKind::NotFound => {}
        Err(error) => return Err(ProcessError::Io(error)),
    }
    let mut file = OpenOptions::new()
        .create_new(true)
        .write(true)
        .mode(0o600)
        .open(&temporary)?;
    file.write_all(content)?;
    file.sync_all()?;
    drop(file);
    fs::rename(&temporary, path)?;
    Ok(())
}

fn gui_managed_kde_file(path: &Path) -> Result<bool, ProcessError> {
    let metadata = match fs::symlink_metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(true),
        Err(error) => return Err(ProcessError::Io(error)),
    };
    if metadata.file_type().is_symlink() || !metadata.is_file() || metadata.len() > 64 * 1024 {
        return Err(ProcessError::InvalidEnvironment);
    }
    let content = fs::read(path)?;
    Ok(
        content.starts_with(b"# Managed by Archphene appearance settings.\n")
            || (content
                .windows(b"ColorScheme=Archphene".len())
                .any(|value| value == b"ColorScheme=Archphene")
                && content
                    .windows(b"[Archphene]".len())
                    .any(|value| value == b"[Archphene]")),
    )
}

fn css_color(color: [u8; 3]) -> String {
    format!("#{:02x}{:02x}{:02x}", color[0], color[1], color[2])
}

fn kde_color(color: [u8; 3]) -> String {
    format!("{},{},{}", color[0], color[1], color[2])
}

fn kde_globals(appearance: GuiAppearance, point_size: u32) -> String {
    let background = kde_color(appearance.background);
    let foreground = kde_color(appearance.foreground);
    let accent = kde_color(appearance.accent);
    let alternate = if appearance.dark {
        "42,46,50"
    } else {
        "246,247,248"
    };
    let view = if appearance.dark {
        "27,30,32"
    } else {
        "255,255,255"
    };
    let button = if appearance.dark {
        "49,54,59"
    } else {
        "239,240,241"
    };
    let selection_foreground = background.as_str();
    format!(
        "# Managed by Archphene appearance settings.\n\
[General]\n\
Name=Archphene {}\n\
ColorScheme=Archphene{}\n\
font=Noto Sans,{point_size},-1,5,50,0,0,0,0,0\n\
fixed=Noto Sans Mono,{point_size},-1,5,50,0,0,0,0,0\n\n\
[Colors:Window]\nBackgroundNormal={background}\nBackgroundAlternate={alternate}\nForegroundNormal={foreground}\nForegroundInactive={foreground}\nForegroundActive={foreground}\nForegroundLink={accent}\nDecorationFocus={accent}\nDecorationHover={accent}\n\n\
[Colors:View]\nBackgroundNormal={view}\nBackgroundAlternate={alternate}\nForegroundNormal={foreground}\nForegroundInactive={foreground}\nForegroundActive={foreground}\nForegroundLink={accent}\nForegroundVisited={accent}\nDecorationFocus={accent}\nDecorationHover={accent}\n\n\
[Colors:Button]\nBackgroundNormal={button}\nBackgroundAlternate={alternate}\nForegroundNormal={foreground}\nForegroundInactive={foreground}\nForegroundActive={foreground}\nForegroundLink={accent}\nDecorationFocus={accent}\nDecorationHover={accent}\n\n\
[Colors:Selection]\nBackgroundNormal={accent}\nBackgroundAlternate={accent}\nForegroundNormal={selection_foreground}\nForegroundInactive={selection_foreground}\nForegroundActive={selection_foreground}\nDecorationFocus={accent}\nDecorationHover={accent}\n\n\
[Colors:Tooltip]\nBackgroundNormal={button}\nBackgroundAlternate={alternate}\nForegroundNormal={foreground}\nForegroundInactive={foreground}\nForegroundActive={foreground}\n\n\
[Archphene]\nControlDensity=automatic\nControlMinSize={}\nControlVisualSize={}\n",
        if appearance.dark { "Dark" } else { "Light" },
        if appearance.dark { "Dark" } else { "Light" },
        appearance.control_target_dp,
        appearance.control_visual_dp,
    )
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

fn safe_installed_command(metadata: &fs::Metadata) -> bool {
    metadata.is_file() && metadata.mode() & 0o002 == 0
}

struct TemporaryOutput(PathBuf);

impl Drop for TemporaryOutput {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.0);
    }
}

fn terminate_process_group(child: &mut std::process::Child) {
    let descendants = system::descendant_processes(child.id()).unwrap_or_default();
    // Interactive shells legitimately put foreground jobs in their own
    // process groups. Capture and terminate the bounded descendant tree before
    // reaping the shell leader so those jobs cannot become Android PID 1
    // orphans when the user closes the shared session.
    for process in descendants.into_iter().rev() {
        let _ = system::signal_process_if_same(process, 9);
    }
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
    use std::fs::{self, File, OpenOptions};
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
    const MAX_PROCESSES_SCANNED: usize = 8192;
    const MAX_DESCENDANT_PROCESSES: usize = 512;
    const MAX_PROCESS_STAT_BYTES: u64 = 4096;

    #[derive(Clone, Copy, Debug, Eq, PartialEq)]
    pub struct ProcessIdentity {
        pub(super) process: u32,
        pub(super) start_time: u64,
    }

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
        signal_process_group(group, 9)
    }

    pub fn signal_process(process: u32, signal: i32) -> io::Result<()> {
        let process = i32::try_from(process)
            .ok()
            .filter(|process| *process > 0)
            .ok_or_else(|| io::Error::from(io::ErrorKind::InvalidInput))?;
        if !(1..=64).contains(&signal) {
            return Err(io::Error::from(io::ErrorKind::InvalidInput));
        }
        // SAFETY: `process` is a positive PID discovered below the exact child
        // tree, and a valid signal has no pointer or lifetime requirements.
        if unsafe { kill(process, signal) } == 0 {
            Ok(())
        } else {
            Err(io::Error::last_os_error())
        }
    }

    pub fn signal_process_if_same(process: ProcessIdentity, signal: i32) -> io::Result<()> {
        let stat = match read_process_stat(process.process) {
            Ok(stat) => stat,
            Err(error)
                if matches!(
                    error.kind(),
                    io::ErrorKind::NotFound | io::ErrorKind::PermissionDenied
                ) =>
            {
                return Ok(());
            }
            Err(error) => return Err(error),
        };
        if process_fields(&stat).map(|(_, start_time)| start_time) != Some(process.start_time) {
            return Ok(());
        }
        signal_process(process.process, signal)
    }

    pub fn signal_process_group(group: u32, signal: i32) -> io::Result<()> {
        let group = i32::try_from(group)
            .ok()
            .filter(|group| *group > 0)
            .ok_or_else(|| io::Error::from(io::ErrorKind::InvalidInput))?;
        if !(1..=64).contains(&signal) {
            return Err(io::Error::from(io::ErrorKind::InvalidInput));
        }
        // SAFETY: `group` is the positive pid of a child started in its own
        // process group. Negating it addresses only that group, and a valid
        // signal has no pointer, buffer, ownership, or lifetime requirements.
        if unsafe { kill(-group, signal) } == 0 {
            Ok(())
        } else {
            Err(io::Error::last_os_error())
        }
    }

    pub fn process_group_exists(group: u32) -> io::Result<bool> {
        let group = i32::try_from(group)
            .ok()
            .filter(|group| *group > 0)
            .ok_or_else(|| io::Error::from(io::ErrorKind::InvalidInput))?;
        // SAFETY: signal zero performs an existence/permission check and
        // cannot mutate the process group.
        if unsafe { kill(-group, 0) } == 0 {
            return Ok(true);
        }
        let error = io::Error::last_os_error();
        match error.raw_os_error() {
            Some(3) => Ok(false),
            Some(1) => Ok(true),
            _ => Err(error),
        }
    }

    #[cfg(test)]
    pub fn process_exists(process: u32) -> io::Result<bool> {
        let process = i32::try_from(process)
            .ok()
            .filter(|process| *process > 0)
            .ok_or_else(|| io::Error::from(io::ErrorKind::InvalidInput))?;
        // SAFETY: signal zero only checks the exact positive PID.
        if unsafe { kill(process, 0) } == 0 {
            let stat = fs::read_to_string(format!("/proc/{process}/stat"))?;
            return Ok(process_state(&stat) != Some("Z"));
        }
        let error = io::Error::last_os_error();
        match error.raw_os_error() {
            Some(3) => Ok(false),
            Some(1) => Ok(true),
            _ => Err(error),
        }
    }

    pub fn descendant_processes(root: u32) -> io::Result<Vec<ProcessIdentity>> {
        if root == 0 {
            return Err(io::Error::from(io::ErrorKind::InvalidInput));
        }
        let mut relationships = Vec::with_capacity(256);
        let mut scanned = 0_usize;
        for entry in fs::read_dir("/proc")? {
            scanned = scanned
                .checked_add(1)
                .ok_or_else(|| io::Error::from(io::ErrorKind::InvalidData))?;
            if scanned > MAX_PROCESSES_SCANNED {
                return Err(io::Error::from(io::ErrorKind::InvalidData));
            }
            let entry = entry?;
            let Some(process) = entry
                .file_name()
                .to_str()
                .and_then(|name| name.parse::<u32>().ok())
                .filter(|process| *process > 0)
            else {
                continue;
            };
            let stat = match read_process_stat(process) {
                Ok(stat) => stat,
                Err(error)
                    if matches!(
                        error.kind(),
                        io::ErrorKind::NotFound | io::ErrorKind::PermissionDenied
                    ) =>
                {
                    continue;
                }
                Err(error) => return Err(error),
            };
            let Some((parent, start_time)) = process_fields(&stat) else {
                continue;
            };
            relationships.push((
                ProcessIdentity {
                    process,
                    start_time,
                },
                parent,
            ));
        }

        let mut tree = Vec::with_capacity(32);
        tree.push(root);
        let mut cursor = 0_usize;
        while cursor < tree.len() {
            let parent = tree[cursor];
            for (process, candidate_parent) in &relationships {
                if *candidate_parent == parent && !tree.contains(&process.process) {
                    if tree.len() > MAX_DESCENDANT_PROCESSES {
                        return Err(io::Error::from(io::ErrorKind::InvalidData));
                    }
                    tree.push(process.process);
                }
            }
            cursor += 1;
        }
        tree.remove(0);
        let descendants = tree
            .into_iter()
            .filter_map(|process| {
                relationships
                    .iter()
                    .find_map(|(identity, _)| (identity.process == process).then_some(*identity))
            })
            .collect();
        Ok(descendants)
    }

    #[cfg(test)]
    pub(super) fn process_parent(stat: &str) -> Option<u32> {
        process_fields(stat).map(|(parent, _)| parent)
    }

    fn process_fields(stat: &str) -> Option<(u32, u64)> {
        let (_, fields) = stat.rsplit_once(") ")?;
        let mut fields = fields.split_ascii_whitespace();
        let state = fields.next()?;
        if state.len() != 1 {
            return None;
        }
        let parent = fields.next()?.parse::<u32>().ok()?;
        let start_time = fields.nth(17)?.parse::<u64>().ok()?;
        Some((parent, start_time))
    }

    fn read_process_stat(process: u32) -> io::Result<String> {
        let stat_path = format!("/proc/{process}/stat");
        let metadata = fs::symlink_metadata(&stat_path)?;
        if !metadata.is_file() || metadata.len() > MAX_PROCESS_STAT_BYTES {
            return Err(io::Error::from(io::ErrorKind::InvalidData));
        }
        let stat = fs::read_to_string(stat_path)?;
        if stat.is_empty() || stat.len() as u64 > MAX_PROCESS_STAT_BYTES {
            return Err(io::Error::from(io::ErrorKind::InvalidData));
        }
        Ok(stat)
    }

    #[cfg(test)]
    fn process_state(stat: &str) -> Option<&str> {
        let (_, fields) = stat.rsplit_once(") ")?;
        fields.split_ascii_whitespace().next()
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
        symlink(
            root.0.join("usr/bin/real-command"),
            root.0.join("usr/bin/physical"),
        )
        .expect("physical root link");
        assert_eq!(
            resolve_installed_command(&root.0, "physical").expect("physical resolution"),
            root.0.join("usr/bin/real-command")
        );
    }

    #[test]
    fn command_resolution_accepts_private_group_write_and_rejects_world_write() {
        let root = TestRoot::new();
        symlink("../../../../system/bin/sh", root.0.join("usr/bin/escape")).expect("escape link");
        assert!(matches!(
            resolve_installed_command(&root.0, "escape"),
            Err(ProcessError::UnsafeCommand(_))
        ));
        root.command("group-writable");
        fs::set_permissions(
            root.0.join("usr/bin/group-writable"),
            fs::Permissions::from_mode(0o775),
        )
        .expect("group-writable mode");
        assert_eq!(
            resolve_installed_command(&root.0, "group-writable")
                .expect("private group-write is the same Android app identity"),
            root.0.join("usr/bin/group-writable")
        );
        root.command("world-writable");
        fs::set_permissions(
            root.0.join("usr/bin/world-writable"),
            fs::Permissions::from_mode(0o777),
        )
        .expect("writable mode");
        assert!(matches!(
            resolve_installed_command(&root.0, "world-writable"),
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
        let pixbuf_modules = root.0.join("run/gdk-pixbuf-loaders.cache");
        fs::write(&pixbuf_modules, b"verified module cache").expect("pixbuf module cache");
        let environment = CommandEnvironment::new(
            &root.0,
            &root.0.join("usr/bin/loader"),
            aliases.as_os_str(),
            &bridge_alias,
            &aliases,
            Some(&pixbuf_modules),
        )
        .expect("command environment");
        assert_eq!(environment.path_bridge, bridge);
        let build_directory = root.0.join("home/archphene/build");
        fs::create_dir_all(&build_directory).expect("build directory");
        assert_eq!(
            environment
                .resolve_working_directory(&build_directory)
                .expect("root-contained working directory"),
            build_directory,
        );
        let outside = std::env::temp_dir();
        symlink(&outside, root.0.join("home/archphene/build-link"))
            .expect("working-directory link");
        assert!(
            environment
                .resolve_working_directory(&root.0.join("home/archphene/build-link"))
                .is_err(),
        );
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
        let expected_tmpdir = root.0.join("tmp");
        assert_eq!(value("TMPDIR"), Some(expected_tmpdir.as_os_str()));
        assert_eq!(
            value("PATH"),
            Some(OsStr::new(
                "/home/archphene/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/bin",
            )),
        );
        assert_eq!(value("LANG"), Some(OsStr::new("C.UTF-8")));
        assert_eq!(value("LC_ALL"), Some(OsStr::new("C.UTF-8")));
        let expected_locale_path = root.0.join("usr/lib/locale");
        assert_eq!(value("LOCPATH"), Some(expected_locale_path.as_os_str()),);
        assert_eq!(
            value("FONTCONFIG_FILE"),
            Some(OsStr::new("/var/lib/archphene/fontconfig/fonts.conf")),
        );
        assert_eq!(value("FONTCONFIG_PATH"), Some(OsStr::new("/etc/fonts")));
        assert_eq!(
            value("ARCHPHENE_RUNTIME_PROGRAM_PATH"),
            Some(launch.program.as_os_str()),
        );
        assert_eq!(
            value("GDK_PIXBUF_MODULE_FILE"),
            Some(pixbuf_modules.as_os_str()),
        );
        assert_eq!(value("ARCHPHENE_ROOT_IDENTITY"), None);

        let mut root_command = environment.build_command(&launch, &[], "dumb");
        root_command
            .current_dir(&environment.arch_root)
            .env("ARCHPHENE_ROOT_IDENTITY", "1")
            .env("HOME", "/root")
            .env("USER", "root")
            .env("LOGNAME", "root");
        let root_value = |name: &str| {
            root_command
                .get_envs()
                .find_map(|(key, value)| (key == name).then_some(value).flatten())
        };
        assert_eq!(root_value("ARCHPHENE_ROOT_IDENTITY"), Some(OsStr::new("1")));
        assert_eq!(root_value("HOME"), Some(OsStr::new("/root")));
        assert_eq!(root_value("USER"), Some(OsStr::new("root")));
        assert_eq!(
            root_command.get_current_dir(),
            Some(environment.arch_root.as_path())
        );

        let gui = environment.build_gui_command(&launch, &["--new-window"], "launcher-7.sock");
        let gui_value = |name: &str| {
            gui.get_envs()
                .find_map(|(key, value)| (key == name).then_some(value).flatten())
        };
        assert_eq!(
            gui_value("WAYLAND_DISPLAY"),
            Some(OsStr::new("launcher-7.sock")),
        );
        assert_eq!(gui_value("GDK_BACKEND"), Some(OsStr::new("wayland")));
        assert_eq!(gui_value("QT_QPA_PLATFORM"), Some(OsStr::new("wayland")));
        assert_eq!(gui_value("SDL_VIDEODRIVER"), Some(OsStr::new("wayland")));
        assert!(validate_wayland_display("../escape").is_err());
        assert!(validate_wayland_display("/absolute").is_err());
    }

    #[test]
    fn gui_appearance_is_bounded_published_and_exported_without_geometry_scaling() {
        let root = TestRoot::new();
        root.command("loader");
        fs::create_dir_all(root.0.join("home/archphene")).expect("home");
        let native = root.0.join("native");
        let aliases = root.0.join("run/aliases");
        let plugins = root.0.join("run/toolkit-plugins");
        fs::create_dir(&native).expect("native");
        fs::create_dir_all(&aliases).expect("aliases");
        fs::create_dir_all(plugins.join("platformthemes")).expect("plugins");
        fs::create_dir(plugins.join("styles")).expect("styles");
        let bridge = native.join("libbridge.so");
        fs::write(&bridge, b"bridge").expect("bridge");
        fs::set_permissions(&bridge, fs::Permissions::from_mode(0o755)).expect("bridge mode");
        let bridge_alias = aliases.join("libbridge.so");
        symlink(&bridge, &bridge_alias).expect("bridge alias");
        let gtk = native.join("libgtksettings.so");
        fs::write(&gtk, b"settings").expect("settings module");
        fs::set_permissions(&gtk, fs::Permissions::from_mode(0o755)).expect("settings mode");
        let gtk_alias = aliases.join("libgtksettings.so");
        symlink(&gtk, &gtk_alias).expect("settings alias");
        let appearance = GuiAppearance::new(
            true,
            150,
            20,
            32,
            [0x86, 0x5a, 0xaa],
            [0x20, 0x21, 0x22],
            [0xee, 0xef, 0xf0],
        )
        .expect("appearance");
        let environment = CommandEnvironment::new(
            &root.0,
            &root.0.join("usr/bin/loader"),
            aliases.as_os_str(),
            &bridge_alias,
            &aliases,
            None,
        )
        .expect("base environment")
        .with_gui_support(Some(&gtk_alias), Some(&plugins), appearance)
        .expect("GUI environment");
        let launch = LaunchPlan {
            program: root.0.join("usr/bin/loader"),
            argv0: "loader".to_owned(),
            interpreter_argument: None,
            script: None,
        };
        let command = environment.build_gui_command(&launch, &[], "launcher.sock");
        let value = |name: &str| {
            command
                .get_envs()
                .find_map(|(key, value)| (key == name).then_some(value).flatten())
        };
        assert_eq!(value("GTK_MODULES"), Some(gtk_alias.as_os_str()));
        assert_eq!(value("GTK_THEME"), Some(OsStr::new("Adwaita:dark")));
        assert_eq!(value("GDK_SCALE"), None);
        assert_eq!(value("GDK_DPI_SCALE"), None);
        assert_eq!(value("QT_PLUGIN_PATH"), Some(plugins.as_os_str()));
        assert_eq!(value("ARCHPHENE_FONT_POINT_SIZE"), Some(OsStr::new("18")));
        assert_eq!(
            value("ARCHPHENE_QT_CONTROL_MIN_SIZE"),
            Some(OsStr::new("32"))
        );
        assert_eq!(
            value("ARCHPHENE_QT_CONTROL_VISUAL_SIZE"),
            Some(OsStr::new("20"))
        );
        assert_eq!(value("QT_SCALE_FACTOR"), None);
        let settings =
            fs::read_to_string(root.0.join("home/archphene/.config/gtk-3.0/settings.ini"))
                .expect("GTK settings");
        assert!(settings.contains("gtk-application-prefer-dark-theme=true"));
        assert!(settings.contains("gtk-font-name=Noto Sans 18"));
        let css = fs::read_to_string(root.0.join("home/archphene/.config/gtk-3.0/gtk.css"))
            .expect("GTK CSS");
        assert!(css.contains("min-width: 20px"));
        assert!(css.contains("min-height: 32px"));
        let kde = fs::read_to_string(root.0.join("home/archphene/.config/kdeglobals"))
            .expect("KDE settings");
        assert!(kde.contains("ColorScheme=ArchpheneDark"));
        assert!(kde.contains("ControlVisualSize=20"));
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
    fn gui_log_ring_retains_only_the_bounded_tail() {
        let (reader, mut writer) = UnixStream::pair().expect("log pair");
        reader.set_nonblocking(true).expect("nonblocking reader");
        writer.write_all(&[b'a'; 32]).expect("log prefix");
        writer
            .write_all(&[b'b'; MAX_GUI_LOG_BYTES])
            .expect("log tail");
        let mut process = GuiProcess {
            process_group: 0,
            child: None,
            leader_exit_status: Some(0),
            exit_status: Some(0),
            log_reader: reader,
            log_bytes: [0; MAX_GUI_LOG_BYTES],
            log_start: 0,
            log_length: 0,
        };
        let mut output = [0_u8; MAX_GUI_LOG_BYTES];
        assert_eq!(
            process.read_logs(&mut output).expect("bounded log"),
            MAX_GUI_LOG_BYTES
        );
        assert!(output.iter().all(|byte| *byte == b'b'));
    }

    #[test]
    fn gui_session_waits_for_remaining_process_group() {
        let marker = std::env::temp_dir().join(format!(
            "archphene-gui-descendant-{}-{}",
            std::process::id(),
            OUTPUT_ID.fetch_add(1, Ordering::Relaxed),
        ));
        let command = format!("(sleep 0.2; touch '{}') & exit 7", marker.display());
        let (reader, writer) = UnixStream::pair().expect("log pair");
        reader.set_nonblocking(true).expect("nonblocking reader");
        let error_writer = writer.try_clone().expect("error writer");
        let writer: OwnedFd = writer.into();
        let error_writer: OwnedFd = error_writer.into();
        let child = Command::new("/bin/sh")
            .arg("-c")
            .arg(command)
            .stdout(Stdio::from(writer))
            .stderr(Stdio::from(error_writer))
            .process_group(0)
            .spawn()
            .expect("group leader");
        let mut process = GuiProcess {
            process_group: child.id(),
            child: Some(child),
            leader_exit_status: None,
            exit_status: None,
            log_reader: reader,
            log_bytes: [0; MAX_GUI_LOG_BYTES],
            log_start: 0,
            log_length: 0,
        };
        let deadline = Instant::now() + Duration::from_secs(2);
        while process.exit_status().expect("exit status").is_none() && Instant::now() < deadline {
            thread::sleep(Duration::from_millis(10));
        }
        assert_eq!(process.exit_status().expect("final status"), Some(7));
        assert!(marker.exists(), "GUI session did not retain its descendant");
        let _ = fs::remove_file(marker);
    }

    #[test]
    fn process_tree_shutdown_reaps_a_descendant_in_another_session() {
        let process_file = std::env::temp_dir().join(format!(
            "archphene-detached-descendant-{}-{}",
            std::process::id(),
            OUTPUT_ID.fetch_add(1, Ordering::Relaxed),
        ));
        let command = format!(
            "/usr/bin/setsid /bin/sleep 30 & echo $! > '{}'; wait",
            process_file.display(),
        );
        let mut child = Command::new("/bin/sh")
            .arg("-c")
            .arg(command)
            .process_group(0)
            .spawn()
            .expect("process-tree leader");
        let deadline = Instant::now() + Duration::from_secs(2);
        while !process_file.exists() && Instant::now() < deadline {
            thread::sleep(Duration::from_millis(10));
        }
        let detached = fs::read_to_string(&process_file)
            .expect("detached process pid")
            .trim()
            .parse::<u32>()
            .expect("numeric detached process pid");
        assert!(
            system::process_exists(detached).expect("detached process status"),
            "detached process did not start",
        );
        let descendants =
            system::descendant_processes(child.id()).expect("bounded descendant scan");
        assert!(
            descendants
                .iter()
                .any(|process| process.process == detached),
            "detached process was absent from {descendants:?}",
        );

        terminate_process_group(&mut child);
        let _ = child.wait();
        let deadline = Instant::now() + Duration::from_secs(1);
        while system::process_exists(detached).unwrap_or(false) && Instant::now() < deadline {
            thread::sleep(Duration::from_millis(10));
        }
        let remained = system::process_exists(detached).unwrap_or(false);
        if remained {
            let _ = system::signal_process(detached, 9);
        }
        let _ = fs::remove_file(process_file);
        assert!(!remained, "detached process survived tree shutdown");
    }

    #[test]
    fn proc_stat_parent_parsing_handles_spaces_and_parentheses() {
        assert_eq!(
            system::process_parent(
                "123 (name with ) paren) S 42 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18\n",
            ),
            Some(42),
        );
        assert_eq!(system::process_parent("malformed"), None);
    }

    #[test]
    fn gui_close_preserves_a_natural_exit_during_the_grace_period() {
        let (reader, writer) = UnixStream::pair().expect("log pair");
        reader.set_nonblocking(true).expect("nonblocking reader");
        let error_writer = writer.try_clone().expect("error writer");
        let writer: OwnedFd = writer.into();
        let error_writer: OwnedFd = error_writer.into();
        let child = Command::new("/bin/sh")
            .arg("-c")
            .arg("sleep 0.05; exit 7")
            .stdout(Stdio::from(writer))
            .stderr(Stdio::from(error_writer))
            .process_group(0)
            .spawn()
            .expect("group leader");
        let mut process = GuiProcess {
            process_group: child.id(),
            child: Some(child),
            leader_exit_status: None,
            exit_status: None,
            log_reader: reader,
            log_bytes: [0; MAX_GUI_LOG_BYTES],
            log_start: 0,
            log_length: 0,
        };

        process.close();

        assert_eq!(process.exit_status, Some(7));
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
            .write_terminal_damage(&mut damage, false, 0)
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
            .write_terminal_damage(&mut damage, false, 0)
            .expect("terminal damage");
        assert_eq!(&damage[..4], b"ATRM");
        assert_eq!(
            length,
            archphene_terminal::DAMAGE_HEADER_SIZE + 4 * archphene_terminal::DAMAGE_CELL_SIZE
        );
        assert_eq!(
            u32::from_le_bytes(
                damage[archphene_terminal::DAMAGE_HEADER_SIZE
                    ..archphene_terminal::DAMAGE_HEADER_SIZE + 4]
                    .try_into()
                    .expect("cell codepoint")
            ),
            u32::from(b'O')
        );
        let first_cell = archphene_terminal::DAMAGE_HEADER_SIZE;
        assert_eq!(
            u32::from_le_bytes(
                damage[first_cell + 64..first_cell + 68]
                    .try_into()
                    .expect("foreground")
            ),
            2
        );
        assert_eq!(damage[first_cell + 72] & 1, 1);
    }

    #[test]
    fn terminal_queries_round_trip_through_the_real_pty() {
        let (master, mut slave) = system::open_pty(24, 80).expect("PTY pair");
        let status = Command::new("stty")
            .args(["raw", "-echo", "min", "0", "time", "10"])
            .stdin(Stdio::from(
                slave.try_clone().expect("PTY configuration input"),
            ))
            .status()
            .expect("configure raw PTY");
        assert!(status.success(), "stty could not configure the test PTY");
        let waiter = PtyWaiter::new(&master).expect("PTY waiter");
        let mut session = PtySession {
            master,
            waiter,
            child: None,
            exit_status: None,
            rows: 24,
            columns: 80,
            terminal: Terminal::new(24, 80).expect("terminal state"),
        };
        slave
            .write_all(b"\x1b[c\x1b[5n\x1b[4;9H\x1b[6n")
            .expect("terminal queries");
        let deadline = Instant::now() + Duration::from_secs(1);
        let mut output = [0_u8; 64];
        while Instant::now() < deadline {
            if session.read(&mut output).expect("PTY output") != 0 {
                break;
            }
            thread::sleep(Duration::from_millis(5));
        }
        let mut reply = [0_u8; 64];
        let reply_length = slave.read(&mut reply).expect("terminal reply");
        assert_eq!(&reply[..reply_length], b"\x1b[?1;2c\x1b[0n\x1b[4;9R");
    }
}
