#![forbid(unsafe_code)]

use std::collections::BTreeSet;
use std::fmt;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};

use archphene_core::{Lifecycle, Runtime, RuntimeError};
use archphene_jobs::{JobError, JobOperation, JobState, PackageJob, PackageJobStore};
use archphene_launcher::{LauncherRegistry, LauncherRegistryError, ReconcileReport, WrapperStatus};
use archphene_packages::{
    CatalogDownload, InstalledPackageCatalog, PackagePayloadDownload, PackageResolution,
    PackageRuntime, PackageRuntimeError, PackageTool, Repository, RepositoryArchitecture,
    ToolOutput, VerifiedPackageClosure,
    aur::{AurReview, AurSourceDownload, MAX_AUR_SOURCE_BYTES},
    desktop::{DesktopCatalog, ExecArgument},
};
use archphene_process::{GuiRegistry, MAX_COMMAND_ARGUMENTS, ProcessError, PtyRegistry, PtyWaiter};
use archphene_root::{ArchRoot, BootstrapReport, RootError};
use archphene_storage::{MirrorCancellation, MirrorImport, MirrorImportReport, StorageError};

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
    installed_packages: Option<InstalledPackageCatalog>,
    desktop_entries: Option<DesktopCatalog>,
    launcher_registry: Option<LauncherRegistry>,
    catalog_download: Option<CatalogDownload>,
    package_download: Option<PackagePayloadDownload>,
    aur_review: Option<AurReview>,
    aur_build_resolution: Option<PackageResolution>,
    aur_build_closure: Option<VerifiedPackageClosure>,
    aur_source_download: Option<AurSourceDownload>,
    pty_sessions: PtyRegistry,
    gui_sessions: GuiRegistry,
    session_marker: Option<PathBuf>,
    mirror_import: Option<MirrorImport>,
    mirror_cancellation: Option<MirrorCancellation>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct RuntimeBootstrapReport {
    pub root: BootstrapReport,
    pub recovered_jobs: u32,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct LauncherRegistrySummary {
    pub generation: u64,
    pub total: u16,
    pub needs_publish: u16,
    pub current: u16,
    pub needs_removal: u16,
    pub active: u16,
    pub failed: u16,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LauncherPublishWork {
    pub android_package: String,
    pub descriptor_id_hex: [u8; 64],
    pub generation: u64,
    pub label: String,
    pub icon_path: Option<String>,
    pub icon_sha256: Option<[u8; 32]>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LauncherRemovalWork {
    pub android_package: String,
    pub generation: u64,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LauncherAuthorization {
    pub label: String,
    pub terminal: bool,
}

#[derive(Debug)]
pub enum LauncherProcessError {
    Unauthorized,
    InvalidDescriptor,
    Package(PackageRuntimeError),
    Process(ProcessError),
}

impl fmt::Display for LauncherProcessError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Unauthorized => formatter.write_str("launcher descriptor is not current"),
            Self::InvalidDescriptor => formatter.write_str("launcher descriptor cannot be run"),
            Self::Package(error) => error.fmt(formatter),
            Self::Process(error) => error.fmt(formatter),
        }
    }
}

impl std::error::Error for LauncherProcessError {}

impl From<PackageRuntimeError> for LauncherProcessError {
    fn from(error: PackageRuntimeError) -> Self {
        Self::Package(error)
    }
}

impl From<ProcessError> for LauncherProcessError {
    fn from(error: ProcessError) -> Self {
        Self::Process(error)
    }
}

#[derive(Debug)]
pub enum RuntimeBootstrapError {
    Root(RootError),
    Jobs(JobError),
    Io(io::Error),
}

#[derive(Debug)]
pub enum DesktopRefreshError {
    Package(PackageRuntimeError),
    Launcher(LauncherRegistryError),
}

impl fmt::Display for DesktopRefreshError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Package(error) => error.fmt(formatter),
            Self::Launcher(error) => error.fmt(formatter),
        }
    }
}

impl std::error::Error for DesktopRefreshError {}

impl From<PackageRuntimeError> for DesktopRefreshError {
    fn from(error: PackageRuntimeError) -> Self {
        Self::Package(error)
    }
}

impl From<LauncherRegistryError> for DesktopRefreshError {
    fn from(error: LauncherRegistryError) -> Self {
        Self::Launcher(error)
    }
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
            installed_packages: None,
            desktop_entries: None,
            launcher_registry: None,
            catalog_download: None,
            package_download: None,
            aur_review: None,
            aur_build_resolution: None,
            aur_build_closure: None,
            aur_source_download: None,
            pty_sessions: PtyRegistry::new(),
            gui_sessions: GuiRegistry::new(),
            session_marker: None,
            mirror_import: None,
            mirror_cancellation: None,
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

    pub fn begin_mirror_import(&mut self, project_name: &str) -> Result<(), StorageError> {
        if self.mirror_import.is_some() || self.mirror_cancellation.is_some() {
            return Err(StorageError::MirrorBusy);
        }
        let home = self
            .arch_root
            .as_ref()
            .ok_or(StorageError::InvalidRoot)?
            .path()
            .join("home/archphene");
        let mirror = MirrorImport::begin(&home, project_name)?;
        self.mirror_cancellation = Some(mirror.cancellation());
        self.mirror_import = Some(mirror);
        Ok(())
    }

    pub fn add_mirror_directory(&mut self, relative_path: &str) -> Result<(), StorageError> {
        self.mirror_import
            .as_mut()
            .ok_or(StorageError::MirrorBusy)?
            .add_directory(relative_path)
    }

    pub fn add_mirror_file(
        &mut self,
        relative_path: &str,
        source_descriptor: i32,
        expected_bytes: Option<u64>,
    ) -> Result<u64, StorageError> {
        self.mirror_import
            .as_mut()
            .ok_or(StorageError::MirrorBusy)?
            .add_file_from_fd(relative_path, source_descriptor, expected_bytes)
    }

    pub fn take_mirror_import(&mut self) -> Result<MirrorImport, StorageError> {
        self.mirror_import.take().ok_or(StorageError::MirrorBusy)
    }

    pub fn restore_mirror_import(&mut self, mirror: MirrorImport) -> Result<(), StorageError> {
        if self.mirror_import.is_some() {
            return Err(StorageError::MirrorBusy);
        }
        self.mirror_import = Some(mirror);
        Ok(())
    }

    pub fn finish_mirror_import(&mut self) -> Result<MirrorImportReport, StorageError> {
        let result = self
            .mirror_import
            .take()
            .ok_or(StorageError::MirrorBusy)?
            .finish();
        self.mirror_cancellation = None;
        result
    }

    pub fn abort_mirror_import(&mut self) -> bool {
        if let Some(cancellation) = self.mirror_cancellation.take() {
            cancellation.cancel();
        }
        self.mirror_import.take().is_some()
    }

    pub fn cancel_mirror_import(&self) -> bool {
        self.mirror_cancellation
            .as_ref()
            .is_some_and(|cancellation| {
                cancellation.cancel();
                true
            })
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
        self.installed_packages = None;
        self.desktop_entries = None;
        self.launcher_registry = None;
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

    pub fn discover_shells(&self) -> Result<ToolOutput, PackageRuntimeError> {
        self.package_runtime
            .as_ref()
            .ok_or(PackageRuntimeError::InvalidPath)?
            .discover_shells()
    }

    pub fn refresh_installed_packages(&mut self) -> Result<(), PackageRuntimeError> {
        self.installed_packages = Some(
            self.package_runtime
                .as_ref()
                .ok_or(PackageRuntimeError::InvalidPath)?
                .installed_package_catalog()?,
        );
        Ok(())
    }

    pub fn installed_package_page(&self, offset: usize) -> Result<ToolOutput, PackageRuntimeError> {
        self.installed_packages
            .as_ref()
            .ok_or(PackageRuntimeError::InvalidPath)?
            .page(offset)
    }

    pub fn refresh_desktop_entries(
        &mut self,
    ) -> Result<Option<ReconcileReport>, DesktopRefreshError> {
        let catalog = self
            .package_runtime
            .as_ref()
            .ok_or(PackageRuntimeError::InvalidPath)?
            .desktop_catalog()?;
        let report = if catalog.truncated {
            None
        } else {
            let arch_root = self
                .arch_root
                .as_ref()
                .ok_or(PackageRuntimeError::InvalidPath)?;
            let (registry, report) = LauncherRegistry::reconcile(arch_root.path(), &catalog)?;
            self.launcher_registry = Some(registry);
            Some(report)
        };
        self.desktop_entries = Some(catalog);
        Ok(report)
    }

    pub fn desktop_entry_page(&self, offset: usize) -> Result<ToolOutput, PackageRuntimeError> {
        self.desktop_entries
            .as_ref()
            .ok_or(PackageRuntimeError::InvalidPath)?
            .page(offset)
    }

    pub fn launcher_registry(&self) -> Option<&LauncherRegistry> {
        self.launcher_registry.as_ref()
    }

    pub fn launcher_registry_summary(&self) -> Option<LauncherRegistrySummary> {
        let registry = self.launcher_registry.as_ref()?;
        let mut summary = LauncherRegistrySummary {
            generation: registry.generation(),
            total: u16::try_from(registry.descriptors().len()).ok()?,
            needs_publish: 0,
            current: 0,
            needs_removal: 0,
            active: 0,
            failed: 0,
        };
        for descriptor in registry.descriptors() {
            match descriptor.status {
                WrapperStatus::NeedsPublish => {
                    summary.needs_publish = summary.needs_publish.saturating_add(1);
                }
                WrapperStatus::Current => {
                    summary.current = summary.current.saturating_add(1);
                }
                WrapperStatus::NeedsRemoval => {
                    summary.needs_removal = summary.needs_removal.saturating_add(1);
                }
                WrapperStatus::Building
                | WrapperStatus::AwaitingInstall
                | WrapperStatus::AwaitingRemoval => {
                    summary.active = summary.active.saturating_add(1);
                }
                WrapperStatus::Failed => {
                    summary.failed = summary.failed.saturating_add(1);
                }
            }
        }
        Some(summary)
    }

    pub fn authorize_launcher(
        &self,
        android_package: &str,
        descriptor_id_hex: &str,
        generation: u64,
    ) -> Option<LauncherAuthorization> {
        let descriptor = self.launcher_registry.as_ref()?.authorize_published(
            android_package,
            descriptor_id_hex,
            generation,
        )?;
        Some(LauncherAuthorization {
            label: descriptor.name.clone(),
            terminal: descriptor.terminal,
        })
    }

    pub fn open_launcher_process(
        &mut self,
        android_package: &str,
        descriptor_id_hex: &str,
        generation: u64,
        wayland_display: &str,
    ) -> Result<u64, LauncherProcessError> {
        let (command, arguments) = {
            let descriptor = self
                .launcher_registry
                .as_ref()
                .and_then(|registry| {
                    registry.authorize_published(android_package, descriptor_id_hex, generation)
                })
                .ok_or(LauncherProcessError::Unauthorized)?;
            if descriptor.terminal {
                return Err(LauncherProcessError::InvalidDescriptor);
            }
            let command = descriptor
                .executable
                .strip_prefix("/usr/bin/")
                .filter(|command| !command.is_empty() && !command.contains('/'))
                .ok_or(LauncherProcessError::InvalidDescriptor)?
                .to_owned();
            let mut arguments = Vec::with_capacity(descriptor.arguments.len().saturating_add(1));
            for argument in &descriptor.arguments {
                match argument {
                    ExecArgument::Literal(value) => arguments.push(value.clone()),
                    ExecArgument::Icon => {
                        if let Some(icon) = descriptor.icon.as_ref() {
                            arguments.push("--icon".to_owned());
                            arguments.push(icon.clone());
                        }
                    }
                    ExecArgument::DisplayName => arguments.push(descriptor.name.clone()),
                    ExecArgument::DesktopFile => arguments
                        .push(format!("/usr/share/applications/{}", descriptor.desktop_id,)),
                    ExecArgument::SingleFile
                    | ExecArgument::MultipleFiles
                    | ExecArgument::SingleUrl
                    | ExecArgument::MultipleUrls => {}
                }
            }
            (command, arguments)
        };
        let environment = self
            .package_runtime
            .as_ref()
            .ok_or(PackageRuntimeError::InvalidPath)?
            .command_environment()?;
        if arguments.len() > MAX_COMMAND_ARGUMENTS {
            return Err(LauncherProcessError::Process(ProcessError::InvalidArgument));
        }
        let mut argument_refs = [""; MAX_COMMAND_ARGUMENTS];
        for (destination, source) in argument_refs.iter_mut().zip(&arguments) {
            *destination = source;
        }
        self.gui_sessions
            .open(
                &environment,
                &command,
                &argument_refs[..arguments.len()],
                wayland_display,
            )
            .map_err(LauncherProcessError::from)
    }

    pub fn launcher_process_exit_status(
        &mut self,
        handle: u64,
    ) -> Result<Option<i32>, LauncherProcessError> {
        self.gui_sessions
            .exit_status(handle)
            .map_err(LauncherProcessError::from)
    }

    pub fn launcher_process_logs(
        &mut self,
        handle: u64,
        output: &mut [u8],
    ) -> Result<usize, LauncherProcessError> {
        self.gui_sessions
            .read_logs(handle, output)
            .map_err(LauncherProcessError::from)
    }

    pub fn close_launcher_process(&mut self, handle: u64) -> Result<(), LauncherProcessError> {
        self.gui_sessions
            .close(handle)
            .map_err(LauncherProcessError::from)
    }

    pub fn claim_launcher_publish(
        &mut self,
    ) -> Result<Option<LauncherPublishWork>, LauncherRegistryError> {
        let registry = self
            .launcher_registry
            .as_mut()
            .ok_or(LauncherRegistryError::InvalidTransition)?;
        let Some(descriptor) = registry
            .descriptors()
            .iter()
            .find(|descriptor| descriptor.status == WrapperStatus::NeedsPublish)
        else {
            return Ok(None);
        };
        let icon_sha256 = descriptor.icon_digest();
        let icon_path = icon_sha256.and_then(|_| {
            descriptor.icon.as_deref().and_then(|icon| {
                self.arch_root.as_ref().and_then(|root| {
                    archphene_packages::desktop::resolve_desktop_icon(root.path(), icon)
                })
            })
        });
        let work = LauncherPublishWork {
            android_package: descriptor.android_package.clone(),
            descriptor_id_hex: descriptor.descriptor_id_hex(),
            generation: descriptor.desired_generation,
            label: descriptor.name.clone(),
            icon_path,
            icon_sha256,
        };
        let arch_root = self
            .arch_root
            .as_ref()
            .ok_or(LauncherRegistryError::InvalidRoot)?;
        registry.mark_building(arch_root.path(), &work.android_package, work.generation)?;
        Ok(Some(work))
    }

    pub fn claim_launcher_removal(
        &mut self,
    ) -> Result<Option<LauncherRemovalWork>, LauncherRegistryError> {
        let registry = self
            .launcher_registry
            .as_mut()
            .ok_or(LauncherRegistryError::InvalidTransition)?;
        let Some(descriptor) = registry
            .descriptors()
            .iter()
            .find(|descriptor| descriptor.status == WrapperStatus::NeedsRemoval)
        else {
            return Ok(None);
        };
        let work = LauncherRemovalWork {
            android_package: descriptor.android_package.clone(),
            generation: descriptor.desired_generation,
        };
        let arch_root = self
            .arch_root
            .as_ref()
            .ok_or(LauncherRegistryError::InvalidRoot)?;
        registry.mark_awaiting_removal(arch_root.path(), &work.android_package)?;
        Ok(Some(work))
    }

    pub fn launcher_awaiting_install(
        &mut self,
        android_package: &str,
        generation: u64,
    ) -> Result<(), LauncherRegistryError> {
        let arch_root = self
            .arch_root
            .as_ref()
            .ok_or(LauncherRegistryError::InvalidRoot)?;
        self.launcher_registry
            .as_mut()
            .ok_or(LauncherRegistryError::InvalidTransition)?
            .mark_awaiting_install(arch_root.path(), android_package, generation)
    }

    pub fn launcher_confirm_installed(
        &mut self,
        android_package: &str,
        generation: u64,
    ) -> Result<(), LauncherRegistryError> {
        let arch_root = self
            .arch_root
            .as_ref()
            .ok_or(LauncherRegistryError::InvalidRoot)?;
        self.launcher_registry
            .as_mut()
            .ok_or(LauncherRegistryError::InvalidTransition)?
            .confirm_installed(arch_root.path(), android_package, generation)
    }

    pub fn launcher_publish_failed(
        &mut self,
        android_package: &str,
        generation: u64,
    ) -> Result<(), LauncherRegistryError> {
        let arch_root = self
            .arch_root
            .as_ref()
            .ok_or(LauncherRegistryError::InvalidRoot)?;
        self.launcher_registry
            .as_mut()
            .ok_or(LauncherRegistryError::InvalidTransition)?
            .mark_failed(arch_root.path(), android_package, generation)
    }

    pub fn launcher_template_stale(
        &mut self,
        android_package: &str,
        generation: u64,
    ) -> Result<(), LauncherRegistryError> {
        let arch_root = self
            .arch_root
            .as_ref()
            .ok_or(LauncherRegistryError::InvalidRoot)?;
        self.launcher_registry
            .as_mut()
            .ok_or(LauncherRegistryError::InvalidTransition)?
            .mark_template_stale(arch_root.path(), android_package, generation)
    }

    pub fn launcher_confirm_removed(
        &mut self,
        android_package: &str,
    ) -> Result<(), LauncherRegistryError> {
        let arch_root = self
            .arch_root
            .as_ref()
            .ok_or(LauncherRegistryError::InvalidRoot)?;
        self.launcher_registry
            .as_mut()
            .ok_or(LauncherRegistryError::InvalidTransition)?
            .confirm_removed(arch_root.path(), android_package)
    }

    pub fn launcher_quarantine(
        &mut self,
        android_package: &str,
    ) -> Result<(), LauncherRegistryError> {
        let arch_root = self
            .arch_root
            .as_ref()
            .ok_or(LauncherRegistryError::InvalidRoot)?;
        self.launcher_registry
            .as_mut()
            .ok_or(LauncherRegistryError::InvalidTransition)?
            .quarantine_android_package(arch_root.path(), android_package)
    }

    pub fn reconcile_android_launcher(
        &mut self,
        android_package: &str,
        installed_generation: Option<u64>,
    ) -> Result<(), LauncherRegistryError> {
        let arch_root = self
            .arch_root
            .as_ref()
            .ok_or(LauncherRegistryError::InvalidRoot)?;
        self.launcher_registry
            .as_mut()
            .ok_or(LauncherRegistryError::InvalidTransition)?
            .reconcile_android_package(arch_root.path(), android_package, installed_generation)
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

    pub fn retain_aur_review(&mut self, review: AurReview) {
        self.aur_source_download = None;
        self.aur_build_resolution = None;
        self.aur_build_closure = None;
        self.aur_review = Some(review);
    }

    pub fn begin_aur_source_download(
        &mut self,
        source_index: usize,
        maximum_size: u64,
    ) -> Result<(File, String, String), PackageRuntimeError> {
        if self.aur_source_download.is_some() {
            return Err(PackageRuntimeError::Busy);
        }
        if maximum_size == 0 || maximum_size > MAX_AUR_SOURCE_BYTES {
            return Err(PackageRuntimeError::InvalidPayload);
        }
        let source = self
            .aur_review
            .as_ref()
            .and_then(|review| review.sources.get(source_index))
            .ok_or(PackageRuntimeError::InvalidPayload)?;
        if source.local || source.insecure_transport {
            return Err(PackageRuntimeError::InvalidPayload);
        }
        let endpoint = source
            .remote_url
            .as_ref()
            .ok_or(PackageRuntimeError::InvalidPayload)?
            .clone();
        let expected_sha256 = source.sha256.ok_or(PackageRuntimeError::InvalidPayload)?;
        let filename = source.filename.clone();
        let download = self
            .package_runtime
            .as_ref()
            .ok_or(PackageRuntimeError::InvalidPath)?
            .begin_aur_source_download(&filename, expected_sha256, maximum_size)?;
        let file = download.duplicate_file()?;
        self.aur_source_download = Some(download);
        Ok((file, endpoint, filename))
    }

    pub fn aur_source_cache_candidate(
        &self,
        source_index: usize,
    ) -> Result<(PackageRuntime, String, [u8; 32]), PackageRuntimeError> {
        let source = self
            .aur_review
            .as_ref()
            .and_then(|review| review.sources.get(source_index))
            .ok_or(PackageRuntimeError::InvalidPayload)?;
        if source.local || source.insecure_transport || source.remote_url.is_none() {
            return Err(PackageRuntimeError::InvalidPayload);
        }
        let expected_sha256 = source.sha256.ok_or(PackageRuntimeError::InvalidPayload)?;
        let package_runtime = self
            .package_runtime
            .as_ref()
            .ok_or(PackageRuntimeError::InvalidPath)?
            .clone();
        Ok((package_runtime, source.filename.clone(), expected_sha256))
    }

    pub fn open_verified_aur_source(
        &self,
        source_index: usize,
    ) -> Result<File, PackageRuntimeError> {
        let (package_runtime, filename, expected_sha256) =
            self.aur_source_cache_candidate(source_index)?;
        package_runtime.open_verified_aur_source(&filename, expected_sha256)
    }

    pub fn open_reviewed_aur_snapshot(&self) -> Result<File, PackageRuntimeError> {
        let review = self
            .aur_review
            .as_ref()
            .ok_or(PackageRuntimeError::InvalidPayload)?;
        let expected_sha256 = review
            .snapshot_sha256
            .ok_or(PackageRuntimeError::InvalidPayload)?;
        self.package_runtime
            .as_ref()
            .ok_or(PackageRuntimeError::InvalidPath)?
            .open_reviewed_aur_snapshot(&review.package_base, expected_sha256)
    }

    pub fn resolve_aur_build_environment(
        &mut self,
    ) -> Result<PackageResolution, PackageRuntimeError> {
        let review = self
            .aur_review
            .as_ref()
            .ok_or(PackageRuntimeError::InvalidPayload)?;
        let mut targets = BTreeSet::from(["base-devel".to_owned()]);
        for dependency in review
            .dependencies
            .iter()
            .chain(review.make_dependencies.iter())
            .chain(review.check_dependencies.iter())
        {
            let name = aur_dependency_name(dependency)?;
            targets.insert(name.to_owned());
            if targets.len() > 256 {
                return Err(PackageRuntimeError::OutputLimit);
            }
        }
        let borrowed: Vec<&str> = targets.iter().map(String::as_str).collect();
        let resolution = self
            .package_runtime
            .as_ref()
            .ok_or(PackageRuntimeError::InvalidPath)?
            .resolve_targets_for_fresh_root(&borrowed)?;
        self.aur_build_resolution = Some(resolution.clone());
        self.aur_build_closure = None;
        Ok(resolution)
    }

    pub fn verify_aur_build_environment(
        &mut self,
    ) -> Result<PackageResolution, PackageRuntimeError> {
        let resolution = self
            .aur_build_resolution
            .as_ref()
            .cloned()
            .ok_or(PackageRuntimeError::InvalidPayload)?;
        let closure = self
            .package_runtime
            .as_ref()
            .ok_or(PackageRuntimeError::InvalidPath)?
            .verify_resolution(&resolution)?;
        self.aur_build_closure = Some(closure);
        Ok(resolution)
    }

    pub fn verified_aur_build_closure(
        &self,
    ) -> Result<VerifiedPackageClosure, PackageRuntimeError> {
        self.aur_build_closure
            .clone()
            .ok_or(PackageRuntimeError::InvalidPayload)
    }

    pub fn verified_aur_runtime_dependencies(
        &self,
        package_name: &str,
        version: &str,
    ) -> Result<Vec<String>, PackageRuntimeError> {
        if self.aur_build_closure.is_none() {
            return Err(PackageRuntimeError::InvalidPayload);
        }
        let review = self
            .aur_review
            .as_ref()
            .filter(|review| review.package_name == package_name && review.version == version)
            .ok_or(PackageRuntimeError::InvalidPayload)?;
        let mut dependencies = Vec::with_capacity(review.dependencies.len());
        for dependency in &review.dependencies {
            let name = aur_dependency_name(dependency)?;
            if dependencies.iter().any(|existing| existing == name) {
                continue;
            }
            dependencies.push(name.to_owned());
        }
        Ok(dependencies)
    }

    pub fn open_verified_aur_build_package(
        &self,
        index: usize,
        signature: bool,
    ) -> Result<File, PackageRuntimeError> {
        if self.aur_build_closure.is_none() {
            return Err(PackageRuntimeError::InvalidPayload);
        }
        self.package_runtime
            .as_ref()
            .ok_or(PackageRuntimeError::InvalidPath)?
            .open_verified_resolution_file(
                self.aur_build_resolution
                    .as_ref()
                    .ok_or(PackageRuntimeError::InvalidPayload)?,
                index,
                signature,
            )
    }

    pub fn take_aur_source_download(&mut self) -> Result<AurSourceDownload, PackageRuntimeError> {
        self.aur_source_download
            .take()
            .ok_or(PackageRuntimeError::InvalidPayload)
    }

    pub fn cancel_aur_source_download(&mut self) {
        self.aur_source_download = None;
    }

    pub fn clear_package_cache(&self) -> Result<u64, PackageRuntimeError> {
        if self.package_download.is_some() {
            return Err(PackageRuntimeError::Busy);
        }
        self.package_runtime
            .as_ref()
            .ok_or(PackageRuntimeError::InvalidPath)?
            .clear_package_cache()
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

    pub fn write_terminal_damage(
        &mut self,
        handle: u64,
        output: &mut [u8],
        full_snapshot: bool,
        viewport_offset: u32,
    ) -> Result<usize, PackageRuntimeError> {
        self.pty_sessions
            .write_terminal_damage(handle, output, full_snapshot, viewport_offset)
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

fn aur_dependency_name(value: &str) -> Result<&str, PackageRuntimeError> {
    let end = value
        .bytes()
        .position(|byte| matches!(byte, b'<' | b'=' | b'>'))
        .unwrap_or(value.len());
    let name = &value[..end];
    if name.is_empty()
        || name.len() > 128
        || !name.bytes().all(|byte| {
            byte.is_ascii_alphanumeric() || matches!(byte, b'@' | b'+' | b'.' | b'_' | b'-')
        })
    {
        return Err(PackageRuntimeError::InvalidQuery);
    }
    Ok(name)
}

#[cfg(test)]
mod tests {
    use super::*;
    use archphene_core::SNAPSHOT_SIZE;
    use std::fs;
    use std::sync::atomic::{AtomicU64, Ordering};

    static TEST_ID: AtomicU64 = AtomicU64::new(1);

    #[test]
    fn aur_dependency_names_are_bounded_and_drop_version_constraints() {
        assert_eq!(
            aur_dependency_name("cmake>=4.0").expect("versioned dependency"),
            "cmake"
        );
        assert_eq!(
            aur_dependency_name("python-build").expect("plain dependency"),
            "python-build"
        );
        assert!(matches!(
            aur_dependency_name("../escape"),
            Err(PackageRuntimeError::InvalidQuery)
        ));
    }

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

    #[test]
    fn mirror_cancellation_reaches_an_import_outside_the_registry_lock() {
        let id = TEST_ID.fetch_add(1, Ordering::Relaxed);
        let path = std::env::temp_dir().join(format!(
            "archphene-runtime-mirror-test-{}-{id}",
            std::process::id()
        ));
        let mut runtime = RuntimeHost::new(1);
        runtime
            .bootstrap_arch_root(&path, 1)
            .expect("root bootstrap");
        runtime
            .begin_mirror_import("Cancelled")
            .expect("begin mirror");
        let mut mirror = runtime.take_mirror_import().expect("take mirror");
        assert!(runtime.cancel_mirror_import());
        assert!(matches!(
            mirror.add_directory("directory"),
            Err(StorageError::MirrorCancelled),
        ));
        runtime
            .restore_mirror_import(mirror)
            .expect("restore mirror");
        assert!(runtime.abort_mirror_import());
        assert!(
            !path
                .join("home/archphene/Projects/.archphene-mirror-pending")
                .exists()
        );
        fs::remove_dir_all(path).expect("test cleanup");
    }
}
