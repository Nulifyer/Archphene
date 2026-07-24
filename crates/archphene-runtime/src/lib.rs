#![forbid(unsafe_code)]

use std::fmt;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};

use archphene_core::{Lifecycle, Runtime, RuntimeError};
use archphene_jobs::{JobError, JobOperation, JobState, PackageJob, PackageJobStore};
use archphene_packages::{
    CatalogDownload, PackagePayloadDownload, PackageRuntime, PackageRuntimeError, PackageTool,
    Repository, RepositoryArchitecture, ToolOutput,
};
use archphene_process::{PtyRegistry, PtyWaiter};
use archphene_root::{ArchRoot, BootstrapReport, RootError};

pub const STATUS_ARCH_ROOT_READY: u32 = 1 << 0;
pub const STATUS_JOB_STORE_READY: u32 = 1 << 1;
pub const STATUS_PACKAGE_RUNTIME_READY: u32 = 1 << 2;
pub const STATUS_PACKAGE_CATALOG_READY: u32 = 1 << 3;
pub const STATUS_SESSION_INTERRUPTED: u32 = 1 << 4;
const SESSION_MARKER: &str = "var/lib/archphene/session-active-v1";
const SESSION_MARKER_TEMP: &str = "var/lib/archphene/.session-active-v1.tmp";
const SESSION_MARKER_CONTENT: &[u8] = b"active\n";

pub struct RuntimeHost {
    core: Runtime,
    arch_root: Option<ArchRoot>,
    package_jobs: Option<PackageJobStore>,
    package_runtime: Option<PackageRuntime>,
    catalog_download: Option<CatalogDownload>,
    package_download: Option<PackagePayloadDownload>,
    pty_sessions: PtyRegistry,
    session_marker: Option<PathBuf>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct RuntimeBootstrapReport {
    pub root: BootstrapReport,
    pub recovered_jobs: u32,
}

#[derive(Debug)]
pub enum RuntimeBootstrapError {
    Root(RootError),
    Jobs(JobError),
    Io(io::Error),
}

impl fmt::Display for RuntimeBootstrapError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Root(error) => error.fmt(formatter),
            Self::Jobs(error) => error.fmt(formatter),
            Self::Io(error) => write!(formatter, "session-state I/O error: {error}"),
        }
    }
}

impl std::error::Error for RuntimeBootstrapError {}

impl From<RootError> for RuntimeBootstrapError {
    fn from(error: RootError) -> Self {
        Self::Root(error)
    }
}

impl From<JobError> for RuntimeBootstrapError {
    fn from(error: JobError) -> Self {
        Self::Jobs(error)
    }
}

impl From<io::Error> for RuntimeBootstrapError {
    fn from(error: io::Error) -> Self {
        Self::Io(error)
    }
}

impl RuntimeHost {
    pub fn new(generation: u64) -> Self {
        Self {
            core: Runtime::new(generation),
            arch_root: None,
            package_jobs: None,
            package_runtime: None,
            catalog_download: None,
            package_download: None,
            pty_sessions: PtyRegistry::new(),
            session_marker: None,
        }
    }

    pub fn transition(&mut self, lifecycle: Lifecycle) -> Result<(), RuntimeError> {
        self.core.transition(lifecycle)
    }

    pub fn submit_encoded_events(&mut self, bytes: &[u8]) -> Result<usize, RuntimeError> {
        self.core.submit_encoded_events(bytes)
    }

    pub fn drain_input(&mut self, maximum: usize) -> usize {
        self.core.drain_input(maximum)
    }

    pub fn write_snapshot(&self, output: &mut [u8]) -> Result<usize, RuntimeError> {
        self.core.write_snapshot(output)
    }

    pub fn bootstrap_arch_root(
        &mut self,
        path: &Path,
        now_millis: u64,
    ) -> Result<RuntimeBootstrapReport, RuntimeBootstrapError> {
        let (root, root_report) = ArchRoot::bootstrap(path)?;
        let (package_jobs, recovered_jobs) = PackageJobStore::open(root.path(), now_millis)?;
        let session_marker = root.path().join(SESSION_MARKER);
        let interrupted_session = session_marker_active(&session_marker)?;
        self.arch_root = Some(root);
        self.package_jobs = Some(package_jobs);
        self.session_marker = Some(session_marker);
        let mut status_flags = STATUS_ARCH_ROOT_READY | STATUS_JOB_STORE_READY;
        if interrupted_session {
            status_flags |= STATUS_SESSION_INTERRUPTED;
        }
        self.core.set_status_flags(status_flags);
        Ok(RuntimeBootstrapReport {
            root: root_report,
            recovered_jobs,
        })
    }

    pub fn arch_root(&self) -> Option<&Path> {
        self.arch_root.as_ref().map(ArchRoot::path)
    }

    pub fn prepare_package_runtime(
        &mut self,
        native_root: &Path,
        manifest: &[u8],
        architecture: RepositoryArchitecture,
    ) -> Result<ToolOutput, PackageRuntimeError> {
        let arch_root = self
            .arch_root
            .as_ref()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let mut package_runtime =
            PackageRuntime::prepare(arch_root.path(), native_root, manifest, architecture)?;
        package_runtime.prepare_verification_keyring()?;
        let version = package_runtime.run(PackageTool::Pacman, &["--version"])?;
        let catalogs_ready = package_runtime.catalogs_ready();
        self.package_runtime = Some(package_runtime);
        let mut status_flags =
            STATUS_ARCH_ROOT_READY | STATUS_JOB_STORE_READY | STATUS_PACKAGE_RUNTIME_READY;
        if catalogs_ready {
            status_flags |= STATUS_PACKAGE_CATALOG_READY;
        }
        self.core.add_status_flags(status_flags);
        Ok(version)
    }

    pub fn begin_catalog_download(
        &mut self,
        repository: Repository,
    ) -> Result<(File, &'static str), PackageRuntimeError> {
        if self.catalog_download.is_some() {
            return Err(PackageRuntimeError::Busy);
        }
        let (download, url) = self
            .package_runtime
            .as_ref()
            .ok_or(PackageRuntimeError::InvalidPath)?
            .begin_catalog_download(repository)?;
        let file = download.duplicate_file()?;
        self.catalog_download = Some(download);
        Ok((file, url))
    }

    pub fn finish_catalog_download(
        &mut self,
        repository: Repository,
        success: bool,
    ) -> Result<u64, PackageRuntimeError> {
        let download = self
            .catalog_download
            .take()
            .ok_or(PackageRuntimeError::InvalidCatalog)?;
        if download.repository() != repository || !success {
            return Err(PackageRuntimeError::InvalidCatalog);
        }
        let length = download.finish()?;
        if self
            .package_runtime
            .as_ref()
            .is_some_and(PackageRuntime::catalogs_ready)
        {
            self.core.add_status_flags(STATUS_PACKAGE_CATALOG_READY);
        }
        Ok(length)
    }

    pub fn cancel_catalog_download(&mut self) {
        self.catalog_download = None;
    }

    pub fn package_runtime(&self) -> Option<&PackageRuntime> {
        self.package_runtime.as_ref()
    }

    pub fn begin_package_job(
        &mut self,
        operation: JobOperation,
        repository: &str,
        package: &str,
        now_millis: u64,
    ) -> Result<PackageJob, JobError> {
        self.package_jobs
            .as_mut()
            .ok_or(JobError::CorruptStore)?
            .begin(operation, repository, package, now_millis)
    }

    pub fn update_package_job(
        &mut self,
        id: u64,
        state: JobState,
        phase: u8,
        progress: u8,
        message: &str,
        now_millis: u64,
    ) -> Result<PackageJob, JobError> {
        self.package_jobs
            .as_mut()
            .ok_or(JobError::CorruptStore)?
            .update(id, state, phase, progress, message, now_millis)
    }

    pub fn latest_package_job(&self) -> Option<PackageJob> {
        self.package_jobs
            .as_ref()
            .and_then(|store| store.jobs().latest())
    }

    pub fn begin_package_download(
        &mut self,
        filename: &str,
        expected_size: u64,
        signature: bool,
    ) -> Result<File, PackageRuntimeError> {
        if self.package_download.is_some() {
            return Err(PackageRuntimeError::Busy);
        }
        let download = self
            .package_runtime
            .as_ref()
            .ok_or(PackageRuntimeError::InvalidPath)?
            .begin_package_download(filename, expected_size, signature)?;
        let file = download.duplicate_file()?;
        self.package_download = Some(download);
        Ok(file)
    }

    pub fn finish_package_download(&mut self, success: bool) -> Result<u64, PackageRuntimeError> {
        let download = self
            .package_download
            .take()
            .ok_or(PackageRuntimeError::InvalidPayload)?;
        if !success {
            return Err(PackageRuntimeError::InvalidPayload);
        }
        download.finish()
    }

    pub fn cancel_package_download(&mut self) {
        self.package_download = None;
    }

    pub fn open_pty(
        &mut self,
        command: &str,
        arguments: &[&str],
        rows: u16,
        columns: u16,
    ) -> Result<u64, PackageRuntimeError> {
        let environment = self
            .package_runtime
            .as_ref()
            .ok_or(PackageRuntimeError::InvalidPath)?
            .command_environment()?;
        let handle = self
            .pty_sessions
            .open(&environment, command, arguments, rows, columns)
            .map_err(PackageRuntimeError::from)?;
        let Some(marker) = self.session_marker.as_ref() else {
            let _ = self.pty_sessions.close(handle);
            return Err(PackageRuntimeError::InvalidPath);
        };
        if let Err(error) = write_session_marker(marker) {
            let _ = self.pty_sessions.close(handle);
            return Err(PackageRuntimeError::Io(error));
        }
        self.core.remove_status_flags(STATUS_SESSION_INTERRUPTED);
        Ok(handle)
    }

    pub fn read_pty(
        &mut self,
        handle: u64,
        output: &mut [u8],
    ) -> Result<usize, PackageRuntimeError> {
        self.pty_sessions
            .read(handle, output)
            .map_err(PackageRuntimeError::from)
    }

    pub fn write_pty(&mut self, handle: u64, input: &[u8]) -> Result<usize, PackageRuntimeError> {
        self.pty_sessions
            .write(handle, input)
            .map_err(PackageRuntimeError::from)
    }

    pub fn resize_pty(
        &mut self,
        handle: u64,
        rows: u16,
        columns: u16,
    ) -> Result<(), PackageRuntimeError> {
        self.pty_sessions
            .resize(handle, rows, columns)
            .map_err(PackageRuntimeError::from)
    }

    pub fn pty_exit_status(&mut self, handle: u64) -> Result<Option<i32>, PackageRuntimeError> {
        self.pty_sessions
            .exit_status(handle)
            .map_err(PackageRuntimeError::from)
    }

    pub fn pty_waiter(&self, handle: u64) -> Result<PtyWaiter, PackageRuntimeError> {
        self.pty_sessions
            .waiter(handle)
            .map_err(PackageRuntimeError::from)
    }

    pub fn close_pty(&mut self, handle: u64) -> Result<(), PackageRuntimeError> {
        self.pty_sessions
            .close(handle)
            .map_err(PackageRuntimeError::from)?;
        if self.pty_sessions.is_empty() {
            if let Some(marker) = self.session_marker.as_ref() {
                remove_session_marker(marker)?;
            }
        }
        Ok(())
    }
}

fn session_marker_active(path: &Path) -> io::Result<bool> {
    let metadata = match fs::symlink_metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(false),
        Err(error) => return Err(error),
    };
    if metadata.file_type().is_symlink() || !metadata.is_file() || metadata.len() > 16 {
        return Err(io::Error::from(io::ErrorKind::InvalidData));
    }
    let mut content = [0_u8; 16];
    let length = File::open(path)?.read(&mut content)?;
    if &content[..length] != SESSION_MARKER_CONTENT {
        return Err(io::Error::from(io::ErrorKind::InvalidData));
    }
    Ok(true)
}

fn write_session_marker(path: &Path) -> io::Result<()> {
    let root = path
        .parent()
        .ok_or_else(|| io::Error::from(io::ErrorKind::InvalidInput))?;
    let temporary = root.join(
        Path::new(SESSION_MARKER_TEMP)
            .file_name()
            .ok_or_else(|| io::Error::from(io::ErrorKind::InvalidInput))?,
    );
    match fs::symlink_metadata(&temporary) {
        Ok(metadata) if metadata.is_file() && !metadata.file_type().is_symlink() => {
            fs::remove_file(&temporary)?;
        }
        Ok(_) => return Err(io::Error::from(io::ErrorKind::InvalidData)),
        Err(error) if error.kind() == io::ErrorKind::NotFound => {}
        Err(error) => return Err(error),
    }
    let mut file = OpenOptions::new()
        .create_new(true)
        .write(true)
        .mode(0o600)
        .open(&temporary)?;
    file.write_all(SESSION_MARKER_CONTENT)?;
    file.sync_all()?;
    fs::rename(temporary, path)
}

fn remove_session_marker(path: &Path) -> io::Result<()> {
    match fs::symlink_metadata(path) {
        Ok(metadata) if metadata.is_file() && !metadata.file_type().is_symlink() => {
            fs::remove_file(path)
        }
        Ok(_) => Err(io::Error::from(io::ErrorKind::InvalidData)),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use archphene_core::SNAPSHOT_SIZE;
    use std::fs;
    use std::sync::atomic::{AtomicU64, Ordering};

    static TEST_ID: AtomicU64 = AtomicU64::new(1);

    #[test]
    fn successful_bootstrap_sets_the_snapshot_status_flag() {
        let id = TEST_ID.fetch_add(1, Ordering::Relaxed);
        let path = std::env::temp_dir().join(format!(
            "archphene-runtime-test-{}-{id}",
            std::process::id()
        ));
        let mut runtime = RuntimeHost::new(1);
        let report = runtime
            .bootstrap_arch_root(&path, 1)
            .expect("root bootstrap");
        assert_eq!(report.recovered_jobs, 0);
        let mut snapshot = [0_u8; SNAPSHOT_SIZE];
        runtime.write_snapshot(&mut snapshot).expect("snapshot");
        assert_eq!(
            u32::from_le_bytes(snapshot[48..52].try_into().expect("status bytes")),
            STATUS_ARCH_ROOT_READY | STATUS_JOB_STORE_READY
        );
        fs::remove_dir_all(path).expect("test cleanup");
    }

    #[test]
    fn session_marker_distinguishes_clean_and_interrupted_state() {
        let id = TEST_ID.fetch_add(1, Ordering::Relaxed);
        let path = std::env::temp_dir().join(format!(
            "archphene-session-test-{}-{id}",
            std::process::id()
        ));
        let mut clean = RuntimeHost::new(1);
        clean
            .bootstrap_arch_root(&path, 1)
            .expect("clean bootstrap");
        let marker = path.join(SESSION_MARKER);
        assert!(!session_marker_active(&marker).expect("clean state"));

        write_session_marker(&marker).expect("active marker");
        assert!(session_marker_active(&marker).expect("active state"));
        let mut interrupted = RuntimeHost::new(2);
        interrupted
            .bootstrap_arch_root(&path, 2)
            .expect("interrupted bootstrap");
        let mut snapshot = [0_u8; SNAPSHOT_SIZE];
        interrupted
            .write_snapshot(&mut snapshot)
            .expect("interrupted snapshot");
        let flags = u32::from_le_bytes(snapshot[48..52].try_into().expect("status bytes"));
        assert_ne!(flags & STATUS_SESSION_INTERRUPTED, 0);

        remove_session_marker(&marker).expect("remove marker");
        assert!(!session_marker_active(&marker).expect("removed state"));

        fs::write(&marker, b"corrupt\n").expect("corrupt marker");
        let error = session_marker_active(&marker).expect_err("corrupt marker must fail closed");
        assert_eq!(error.kind(), io::ErrorKind::InvalidData);

        fs::remove_dir_all(path).expect("test cleanup");
    }
}
