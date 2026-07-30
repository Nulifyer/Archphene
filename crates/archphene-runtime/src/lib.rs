#![forbid(unsafe_code)]

use std::collections::BTreeSet;
use std::fmt;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::os::fd::RawFd;
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};

use archphene_core::{Lifecycle, Runtime, RuntimeError};
use archphene_jobs::{JobError, JobOperation, JobState, PackageJob, PackageJobStore};
use archphene_launcher::{
    LauncherDescriptor, LauncherRegistry, LauncherRegistryError, LauncherReviewDecision,
    ReconcileReport, WrapperStatus, launcher_capabilities,
};
use archphene_packages::{
    CatalogDownload, InstalledPackageCatalog, PackageCacheCatalog, PackagePayloadDownload,
    PackageResolution, PackageRuntime, PackageRuntimeError, PackageTool, Repository,
    RepositoryArchitecture, RepositoryTargetPartition, ToolOutput, VerifiedPackageClosure,
    aur::{
        AurBuildGraph, AurBuildGraphError, AurReview, AurSourceDownload, MAX_AUR_GRAPH_BASES,
        MAX_AUR_SOURCE_BYTES, plan_reviewed_aur_graph,
    },
    desktop::{DesktopCatalog, ExecArgument},
};
use archphene_process::{
    GuiAppearance, GuiRegistry, MAX_COMMAND_ARGUMENTS, MAX_GUI_SESSIONS, ProcessError, PtyRegistry,
    PtyWaiter,
    integration::{IntegrationObservation, TOPOLOGY_CHROMIUM, TOPOLOGY_OPENGL},
};
use archphene_root::{ArchRoot, BootstrapReport, RootError};
use archphene_storage::{
    MAX_MIRROR_ENTRIES, MirrorCancellation, MirrorImport, MirrorImportReport, StorageError,
    SyncFingerprint, SyncManifest, SyncManifestEntry, SyncPlan, SyncPlanEntry, SyncPlanSummary,
    create_linux_project_directory, create_sync_plan, delete_linux_project_entry,
    fingerprint_file_from_fd_cancellable, load_sync_manifest, open_linux_project_file_cancellable,
    persist_sync_manifest, preserve_android_conflict_from_fd_cancellable,
    pull_linux_project_file_from_fd_cancellable, reconcile_sync_baseline,
    snapshot_linux_project_cancellable,
};

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
    package_cache: Option<PackageCacheCatalog>,
    desktop_entries: Option<DesktopCatalog>,
    launcher_registry: Option<LauncherRegistry>,
    catalog_download: Option<CatalogDownload>,
    package_download: Option<PackagePayloadDownload>,
    aur_review: Option<AurReview>,
    aur_dependency_reviews: Vec<AurReview>,
    aur_build_graph: Option<AurBuildGraph>,
    aur_build_resolution: Option<PackageResolution>,
    aur_build_closure: Option<VerifiedPackageClosure>,
    aur_source_download: Option<AurSourceDownload>,
    pty_sessions: PtyRegistry,
    gui_sessions: GuiRegistry,
    launcher_observations: [Option<ActiveLauncherObservation>; MAX_GUI_SESSIONS],
    gui_appearance: Option<GuiAppearance>,
    session_marker: Option<PathBuf>,
    mirror_import: Option<MirrorImport>,
    mirror_cancellation: Option<MirrorCancellation>,
    project_sync: Option<ProjectSyncSession>,
}

#[derive(Clone, Copy)]
struct ActiveLauncherObservation {
    handle: u64,
    descriptor_id: [u8; 32],
    generation: u64,
    recorded: IntegrationObservation,
}

struct ProjectSyncSession {
    cancellation: MirrorCancellation,
    baseline: SyncManifest,
    linux: SyncManifest,
    android_entries: Vec<SyncManifestEntry>,
    android: Option<SyncManifest>,
    plan: Option<SyncPlan>,
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
    pub cancelled: u16,
    pub dismissed: u16,
    pub needs_review: u16,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct LauncherProcessOptions<'a> {
    pub portal_bus_address: Option<&'a str>,
    pub reduced_isolation_electron: bool,
    pub virgl_socket_path: Option<&'a Path>,
    pub launch_document_path: Option<&'a str>,
    pub pulse_server_address: Option<&'a str>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PackageLauncherReviewStatus {
    NotInstalled,
    NoLauncher,
    Ready,
    Pending,
    Attention,
    Failed,
    Unavailable,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct PackageLauncherReview {
    pub status: PackageLauncherReviewStatus,
    pub capabilities: u8,
    pub capabilities_analyzed: bool,
    pub launchers: u16,
    pub verified_executables: u16,
    pub current: u16,
    pub pending: u16,
    pub attention: u16,
    pub failed: u16,
    pub integration_topology: u16,
    pub profiled_executables: u16,
    pub incomplete_profiles: u16,
    pub observed_topology: u16,
    pub observed_launchers: u16,
    pub incomplete_observations: u16,
    pub bridge_capabilities: u8,
    pub unavailable_bridge_capabilities: u8,
}

// Only optional helpers with complete Kotlin broker endpoints belong here.
// Never infer Android authority from a linked Linux library alone.
pub const AVAILABLE_LAUNCHER_BRIDGE_CAPABILITIES: u8 =
    archphene_packages::elf_profile::BRIDGE_AUDIO_OUTPUT
        | archphene_packages::elf_profile::BRIDGE_PRINTING;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LauncherPublishWork {
    pub android_package: String,
    pub descriptor_id_hex: [u8; 64],
    pub generation: u64,
    pub label: String,
    pub capabilities: &'static str,
    pub mime_types: Vec<String>,
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
    pub integration_topology: u16,
    pub bridge_capabilities: u8,
    pub mime_types: Vec<String>,
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

fn launcher_mime_types(descriptor: &LauncherDescriptor) -> &[String] {
    if descriptor.arguments.iter().any(|argument| {
        matches!(
            argument,
            ExecArgument::SingleFile
                | ExecArgument::MultipleFiles
                | ExecArgument::SingleUrl
                | ExecArgument::MultipleUrls
        )
    }) {
        &descriptor.mime_types
    } else {
        &[]
    }
}

fn validate_launch_document_path(path: &str) -> Result<&str, LauncherProcessError> {
    const PREFIX: &str = "/home/archphene/Documents/Android/";
    let name = path
        .strip_prefix(PREFIX)
        .filter(|name| {
            !name.is_empty()
                && name.len() <= 255
                && *name != "."
                && *name != ".."
                && !name.chars().any(|character| {
                    character.is_control() || matches!(character, '/' | '\\' | '\0')
                })
        })
        .ok_or(LauncherProcessError::InvalidDescriptor)?;
    debug_assert!(!name.is_empty());
    Ok(path)
}

fn file_uri(path: &str) -> String {
    const HEX: &[u8; 16] = b"0123456789ABCDEF";
    let mut uri = String::with_capacity(path.len().saturating_add(7));
    uri.push_str("file://");
    for byte in path.bytes() {
        if byte.is_ascii_alphanumeric() || matches!(byte, b'/' | b'-' | b'_' | b'.' | b'~') {
            uri.push(char::from(byte));
        } else {
            uri.push('%');
            uri.push(char::from(HEX[usize::from(byte >> 4)]));
            uri.push(char::from(HEX[usize::from(byte & 0x0f)]));
        }
    }
    uri
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

fn apply_electron_compatibility_arguments(
    arguments: &mut Vec<String>,
    integration_topology: u16,
    reduced_isolation_allowed: bool,
) {
    if !reduced_isolation_allowed || integration_topology & TOPOLOGY_CHROMIUM == 0 {
        return;
    }
    for required in ["--no-sandbox", "--disable-dev-shm-usage"] {
        if !arguments.iter().any(|argument| argument == required) {
            arguments.push(required.to_owned());
        }
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
            package_cache: None,
            desktop_entries: None,
            launcher_registry: None,
            catalog_download: None,
            package_download: None,
            aur_review: None,
            aur_dependency_reviews: Vec::new(),
            aur_build_graph: None,
            aur_build_resolution: None,
            aur_build_closure: None,
            aur_source_download: None,
            pty_sessions: PtyRegistry::new(),
            gui_sessions: GuiRegistry::new(),
            launcher_observations: [None; MAX_GUI_SESSIONS],
            gui_appearance: None,
            session_marker: None,
            mirror_import: None,
            mirror_cancellation: None,
            project_sync: None,
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

    pub fn configure_android_dns(&self, request: &[u8]) -> Result<usize, RootError> {
        self.arch_root
            .as_ref()
            .ok_or(RootError::InvalidPath)?
            .configure_android_dns(request)
    }

    pub fn begin_mirror_import(
        &mut self,
        project_name: &str,
        mapping_id: [u8; 16],
    ) -> Result<(), StorageError> {
        if self.mirror_import.is_some() || self.mirror_cancellation.is_some() {
            return Err(StorageError::MirrorBusy);
        }
        let arch_root = self
            .arch_root
            .as_ref()
            .ok_or(StorageError::InvalidRoot)?
            .path();
        let mirror = MirrorImport::begin_with_sync_baseline(arch_root, project_name, mapping_id)?;
        self.mirror_cancellation = Some(mirror.cancellation());
        self.mirror_import = Some(mirror);
        Ok(())
    }

    pub fn begin_portal_folder_import(
        &mut self,
        requested_name: &str,
    ) -> Result<String, StorageError> {
        if self.mirror_import.is_some() || self.mirror_cancellation.is_some() {
            return Err(StorageError::MirrorBusy);
        }
        let home = self
            .arch_root
            .as_ref()
            .ok_or(StorageError::InvalidRoot)?
            .path()
            .join("home/archphene");
        let (mirror, display_name) = MirrorImport::begin_numbered(&home, requested_name)?;
        self.mirror_cancellation = Some(mirror.cancellation());
        self.mirror_import = Some(mirror);
        Ok(display_name)
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

    pub fn begin_project_sync(
        &mut self,
        mapping_id: [u8; 16],
        cancellation: MirrorCancellation,
    ) -> Result<(), StorageError> {
        if self.project_sync.is_some() {
            return Err(StorageError::MirrorBusy);
        }
        cancellation.check()?;
        let arch_root = self
            .arch_root
            .as_ref()
            .ok_or(StorageError::InvalidRoot)?
            .path();
        let baseline =
            load_sync_manifest(arch_root, mapping_id)?.ok_or(StorageError::InvalidManifest)?;
        let linux = snapshot_linux_project_cancellable(arch_root, mapping_id, &cancellation)?;
        let capacity = baseline.entries().len().max(linux.entries().len()).max(256);
        self.project_sync = Some(ProjectSyncSession {
            cancellation,
            baseline,
            linux,
            android_entries: Vec::with_capacity(capacity),
            android: None,
            plan: None,
        });
        Ok(())
    }

    pub fn add_project_sync_android_directory(
        &mut self,
        relative_path: &str,
    ) -> Result<(), StorageError> {
        let session = self.project_sync.as_mut().ok_or(StorageError::MirrorBusy)?;
        session.cancellation.check()?;
        if session.android.is_some() || session.android_entries.len() >= MAX_MIRROR_ENTRIES as usize
        {
            return Err(StorageError::MirrorTooLarge);
        }
        session.android_entries.push(SyncManifestEntry {
            path: relative_path.to_owned(),
            fingerprint: SyncFingerprint::directory(),
        });
        Ok(())
    }

    pub fn add_project_sync_android_file(
        &mut self,
        relative_path: &str,
        source_descriptor: RawFd,
        expected_bytes: Option<u64>,
    ) -> Result<SyncFingerprint, StorageError> {
        let cancellation = self
            .project_sync
            .as_ref()
            .ok_or(StorageError::MirrorBusy)?
            .cancellation
            .clone();
        let fingerprint =
            fingerprint_file_from_fd_cancellable(source_descriptor, expected_bytes, &cancellation)?;
        self.add_project_sync_android_fingerprint(relative_path, fingerprint)?;
        Ok(fingerprint)
    }

    pub fn fingerprint_project_sync_file(
        &self,
        source_descriptor: RawFd,
        expected_bytes: Option<u64>,
    ) -> Result<SyncFingerprint, StorageError> {
        let session = self.project_sync.as_ref().ok_or(StorageError::MirrorBusy)?;
        fingerprint_file_from_fd_cancellable(
            source_descriptor,
            expected_bytes,
            &session.cancellation,
        )
    }

    pub fn add_project_sync_android_fingerprint(
        &mut self,
        relative_path: &str,
        fingerprint: SyncFingerprint,
    ) -> Result<(), StorageError> {
        let session = self.project_sync.as_mut().ok_or(StorageError::MirrorBusy)?;
        session.cancellation.check()?;
        if session.android.is_some() || session.android_entries.len() >= MAX_MIRROR_ENTRIES as usize
        {
            return Err(StorageError::MirrorTooLarge);
        }
        session.android_entries.push(SyncManifestEntry {
            path: relative_path.to_owned(),
            fingerprint,
        });
        Ok(())
    }

    pub fn finish_project_sync_scan(&mut self) -> Result<SyncPlanSummary, StorageError> {
        let session = self.project_sync.as_mut().ok_or(StorageError::MirrorBusy)?;
        session.cancellation.check()?;
        if session.android.is_some() {
            return Err(StorageError::MirrorBusy);
        }
        let android = SyncManifest::new(
            session.baseline.mapping_id(),
            session.baseline.project_name().to_owned(),
            std::mem::take(&mut session.android_entries),
        )?;
        let plan = create_sync_plan(&session.baseline, &session.linux, &android)?;
        let summary = plan.summary();
        session.android = Some(android);
        session.plan = Some(plan);
        Ok(summary)
    }

    pub fn project_sync_plan_entries(&self) -> Result<usize, StorageError> {
        Ok(self
            .project_sync
            .as_ref()
            .and_then(|session| session.plan.as_ref())
            .ok_or(StorageError::MirrorBusy)?
            .entries()
            .len())
    }

    pub fn project_sync_plan_entry(&self, index: usize) -> Result<&SyncPlanEntry, StorageError> {
        self.project_sync
            .as_ref()
            .and_then(|session| session.plan.as_ref())
            .and_then(|plan| plan.entries().get(index))
            .ok_or(StorageError::InvalidDocument)
    }

    pub fn open_project_sync_linux_file(
        &self,
        relative_path: &str,
        expected: SyncFingerprint,
    ) -> Result<File, StorageError> {
        let session = self.project_sync.as_ref().ok_or(StorageError::MirrorBusy)?;
        session.cancellation.check()?;
        let arch_root = self
            .arch_root
            .as_ref()
            .ok_or(StorageError::InvalidRoot)?
            .path();
        open_linux_project_file_cancellable(
            arch_root,
            session.baseline.mapping_id(),
            relative_path,
            expected,
            &session.cancellation,
        )
    }

    pub fn pull_project_sync_linux_file(
        &self,
        relative_path: &str,
        source_descriptor: RawFd,
        expected_android: SyncFingerprint,
        expected_linux: Option<SyncFingerprint>,
    ) -> Result<(), StorageError> {
        let session = self.project_sync.as_ref().ok_or(StorageError::MirrorBusy)?;
        session.cancellation.check()?;
        let arch_root = self
            .arch_root
            .as_ref()
            .ok_or(StorageError::InvalidRoot)?
            .path();
        pull_linux_project_file_from_fd_cancellable(
            arch_root,
            session.baseline.mapping_id(),
            relative_path,
            source_descriptor,
            expected_android,
            expected_linux,
            &session.cancellation,
        )
    }

    pub fn create_project_sync_linux_directory(
        &self,
        relative_path: &str,
    ) -> Result<(), StorageError> {
        let session = self.project_sync.as_ref().ok_or(StorageError::MirrorBusy)?;
        session.cancellation.check()?;
        let arch_root = self
            .arch_root
            .as_ref()
            .ok_or(StorageError::InvalidRoot)?
            .path();
        create_linux_project_directory(arch_root, session.baseline.mapping_id(), relative_path)
    }

    pub fn delete_project_sync_linux_entry(
        &self,
        relative_path: &str,
        expected: SyncFingerprint,
    ) -> Result<(), StorageError> {
        let session = self.project_sync.as_ref().ok_or(StorageError::MirrorBusy)?;
        session.cancellation.check()?;
        let arch_root = self
            .arch_root
            .as_ref()
            .ok_or(StorageError::InvalidRoot)?
            .path();
        delete_linux_project_entry(
            arch_root,
            session.baseline.mapping_id(),
            relative_path,
            expected,
        )
    }

    pub fn preserve_project_sync_android_conflict(
        &self,
        relative_path: &str,
        source_descriptor: RawFd,
        expected_android: SyncFingerprint,
    ) -> Result<String, StorageError> {
        let session = self.project_sync.as_ref().ok_or(StorageError::MirrorBusy)?;
        session.cancellation.check()?;
        let arch_root = self
            .arch_root
            .as_ref()
            .ok_or(StorageError::InvalidRoot)?
            .path();
        preserve_android_conflict_from_fd_cancellable(
            arch_root,
            session.baseline.mapping_id(),
            relative_path,
            source_descriptor,
            expected_android,
            &session.cancellation,
        )
    }

    pub fn begin_project_sync_commit_scan(&mut self) -> Result<(), StorageError> {
        let session = self.project_sync.as_mut().ok_or(StorageError::MirrorBusy)?;
        session.cancellation.check()?;
        let arch_root = self
            .arch_root
            .as_ref()
            .ok_or(StorageError::InvalidRoot)?
            .path();
        session.linux = snapshot_linux_project_cancellable(
            arch_root,
            session.baseline.mapping_id(),
            &session.cancellation,
        )?;
        session.android_entries.clear();
        session.android = None;
        session.plan = None;
        Ok(())
    }

    pub fn commit_project_sync(&mut self) -> Result<(), StorageError> {
        let session = self.project_sync.as_mut().ok_or(StorageError::MirrorBusy)?;
        session.cancellation.check()?;
        let android = SyncManifest::new(
            session.baseline.mapping_id(),
            session.baseline.project_name().to_owned(),
            session.android_entries.clone(),
        )?;
        let reconciled = reconcile_sync_baseline(&session.baseline, &session.linux, &android)?;
        let arch_root = self
            .arch_root
            .as_ref()
            .ok_or(StorageError::InvalidRoot)?
            .path();
        persist_sync_manifest(arch_root, &reconciled)?;
        self.project_sync = None;
        Ok(())
    }

    pub fn abort_project_sync(&mut self) -> bool {
        self.project_sync.take().is_some_and(|session| {
            session.cancellation.cancel();
            true
        })
    }

    pub fn cancel_project_sync(&self) -> bool {
        self.project_sync.as_ref().is_some_and(|session| {
            session.cancellation.cancel();
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
        package_runtime.ensure_system_trust()?;
        let version = package_runtime.run(PackageTool::Pacman, &["--version"])?;
        let catalogs_ready = package_runtime.catalogs_ready();
        self.package_runtime = Some(package_runtime);
        self.installed_packages = None;
        self.package_cache = None;
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

    pub fn refresh_package_cache(&mut self) -> Result<(usize, u64), PackageRuntimeError> {
        let catalog = self
            .package_runtime
            .as_ref()
            .ok_or(PackageRuntimeError::InvalidPath)?
            .package_cache_catalog()?;
        let summary = (catalog.len(), catalog.total_bytes());
        self.package_cache = Some(catalog);
        Ok(summary)
    }

    pub fn package_cache_page(&self, offset: usize) -> Result<ToolOutput, PackageRuntimeError> {
        self.package_cache
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
            cancelled: 0,
            dismissed: 0,
            needs_review: 0,
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
                WrapperStatus::Cancelled => {
                    summary.cancelled = summary.cancelled.saturating_add(1);
                }
                WrapperStatus::Dismissed => {
                    summary.dismissed = summary.dismissed.saturating_add(1);
                }
                WrapperStatus::NeedsReview => {
                    summary.needs_review = summary.needs_review.saturating_add(1);
                }
            }
        }
        Some(summary)
    }

    pub fn package_launcher_review(&self, package: &str) -> Option<PackageLauncherReview> {
        let installed = self.installed_packages.as_ref()?;
        let (capabilities, capabilities_analyzed) = match installed.capabilities(package) {
            Some(value) => value,
            None => {
                return Some(PackageLauncherReview {
                    status: PackageLauncherReviewStatus::NotInstalled,
                    capabilities: 0,
                    capabilities_analyzed: false,
                    launchers: 0,
                    verified_executables: 0,
                    current: 0,
                    pending: 0,
                    attention: 0,
                    failed: 0,
                    integration_topology: 0,
                    profiled_executables: 0,
                    incomplete_profiles: 0,
                    observed_topology: 0,
                    observed_launchers: 0,
                    incomplete_observations: 0,
                    bridge_capabilities: 0,
                    unavailable_bridge_capabilities: 0,
                });
            }
        };
        let catalog = self.desktop_entries.as_ref()?;
        let registry = self.launcher_registry.as_ref();
        let mut review = PackageLauncherReview {
            status: if catalog.truncated {
                PackageLauncherReviewStatus::Unavailable
            } else {
                PackageLauncherReviewStatus::NoLauncher
            },
            capabilities,
            capabilities_analyzed,
            launchers: 0,
            verified_executables: 0,
            current: 0,
            pending: 0,
            attention: 0,
            failed: 0,
            integration_topology: 0,
            profiled_executables: 0,
            incomplete_profiles: 0,
            observed_topology: 0,
            observed_launchers: 0,
            incomplete_observations: 0,
            bridge_capabilities: 0,
            unavailable_bridge_capabilities: 0,
        };
        for entry in catalog
            .entries
            .iter()
            .filter(|entry| entry.source_package.as_deref() == Some(package) && !entry.terminal)
        {
            review.launchers = review.launchers.saturating_add(1);
            if entry.executable_package.is_some() {
                review.verified_executables = review.verified_executables.saturating_add(1);
            }
            review.integration_topology |= entry.integration_topology;
            review.bridge_capabilities |= entry.bridge_capabilities;
            if entry.integration_profiled {
                review.profiled_executables = review.profiled_executables.saturating_add(1);
                if !entry.integration_complete {
                    review.incomplete_profiles = review.incomplete_profiles.saturating_add(1);
                }
            }
            let descriptor = registry.and_then(|registry| {
                registry
                    .descriptors()
                    .iter()
                    .find(|descriptor| descriptor.desktop_id == entry.desktop_id)
            });
            let Some(descriptor) = descriptor else {
                review.status = PackageLauncherReviewStatus::Unavailable;
                continue;
            };
            if descriptor.source_package != entry.source_package
                || descriptor.executable_package != entry.executable_package
                || descriptor.bridge_capabilities != entry.bridge_capabilities
                || !descriptor.desired_present
            {
                review.status = PackageLauncherReviewStatus::Unavailable;
                continue;
            }
            if descriptor.integration_observed {
                review.observed_topology |= descriptor.observed_topology;
                review.observed_launchers = review.observed_launchers.saturating_add(1);
                if !descriptor.integration_observation_complete {
                    review.incomplete_observations =
                        review.incomplete_observations.saturating_add(1);
                }
            }
            match descriptor.status {
                WrapperStatus::Current => review.current = review.current.saturating_add(1),
                WrapperStatus::Failed | WrapperStatus::Cancelled => {
                    review.failed = review.failed.saturating_add(1);
                }
                WrapperStatus::NeedsReview | WrapperStatus::Dismissed => {
                    review.attention = review.attention.saturating_add(1);
                }
                WrapperStatus::NeedsPublish
                | WrapperStatus::Building
                | WrapperStatus::AwaitingInstall
                | WrapperStatus::NeedsRemoval
                | WrapperStatus::AwaitingRemoval => {
                    review.pending = review.pending.saturating_add(1);
                }
            }
        }
        if review.status == PackageLauncherReviewStatus::Unavailable || review.launchers == 0 {
            review.unavailable_bridge_capabilities =
                review.bridge_capabilities & !AVAILABLE_LAUNCHER_BRIDGE_CAPABILITIES;
            return Some(review);
        }
        review.unavailable_bridge_capabilities =
            review.bridge_capabilities & !AVAILABLE_LAUNCHER_BRIDGE_CAPABILITIES;
        review.status = if review.verified_executables != review.launchers {
            PackageLauncherReviewStatus::Unavailable
        } else if review.failed != 0 {
            PackageLauncherReviewStatus::Failed
        } else if review.attention != 0 {
            PackageLauncherReviewStatus::Attention
        } else if review.current == review.launchers {
            PackageLauncherReviewStatus::Ready
        } else {
            PackageLauncherReviewStatus::Pending
        };
        Some(review)
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
            integration_topology: self.launcher_integration_topology(descriptor),
            bridge_capabilities: descriptor.bridge_capabilities,
            mime_types: launcher_mime_types(descriptor).to_vec(),
        })
    }

    fn launcher_integration_topology(&self, descriptor: &LauncherDescriptor) -> u16 {
        let static_topology = self
            .desktop_entries
            .as_ref()
            .and_then(|catalog| {
                catalog
                    .entries
                    .iter()
                    .find(|entry| entry.desktop_id == descriptor.desktop_id)
            })
            .filter(|entry| {
                entry.source_package == descriptor.source_package
                    && entry.executable_package == descriptor.executable_package
            })
            .map_or(0, |entry| entry.integration_topology);
        let observed_topology = if descriptor.integration_observed {
            descriptor.observed_topology
        } else {
            0
        };
        static_topology | observed_topology
    }

    pub fn open_launcher_process(
        &mut self,
        android_package: &str,
        descriptor_id_hex: &str,
        generation: u64,
        wayland_display: &str,
        appearance: GuiAppearance,
        options: LauncherProcessOptions<'_>,
    ) -> Result<u64, LauncherProcessError> {
        let (command, mut arguments, descriptor_id, integration_topology, bridge_capabilities) = {
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
            let mut arguments = Vec::with_capacity(descriptor.arguments.len().saturating_add(3));
            let launch_document_path = options
                .launch_document_path
                .map(validate_launch_document_path)
                .transpose()?;
            if launch_document_path.is_some() && launcher_mime_types(descriptor).is_empty() {
                return Err(LauncherProcessError::InvalidDescriptor);
            }
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
                    ExecArgument::SingleFile | ExecArgument::MultipleFiles => {
                        if let Some(path) = launch_document_path {
                            arguments.push(path.to_owned());
                        }
                    }
                    ExecArgument::SingleUrl | ExecArgument::MultipleUrls => {
                        if let Some(path) = launch_document_path {
                            arguments.push(file_uri(path));
                        }
                    }
                }
            }
            (
                command,
                arguments,
                descriptor.descriptor_id,
                self.launcher_integration_topology(descriptor),
                descriptor.bridge_capabilities,
            )
        };
        if options.pulse_server_address.is_some()
            && bridge_capabilities & archphene_packages::elf_profile::BRIDGE_AUDIO_OUTPUT == 0
        {
            return Err(LauncherProcessError::InvalidDescriptor);
        }
        apply_electron_compatibility_arguments(
            &mut arguments,
            integration_topology,
            options.reduced_isolation_electron,
        );
        let package_runtime = self
            .package_runtime
            .as_ref()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let environment = match options.portal_bus_address {
            Some(address) => {
                package_runtime.command_environment_with_gui_and_portal(appearance, address)?
            }
            None => package_runtime.command_environment_with_gui(appearance)?,
        };
        let environment = if integration_topology & TOPOLOGY_OPENGL != 0 {
            environment.with_opengl_bridge(options.virgl_socket_path)?
        } else if options.virgl_socket_path.is_some() {
            return Err(LauncherProcessError::Process(
                ProcessError::InvalidEnvironment,
            ));
        } else {
            environment
        };
        let environment = environment.with_pulse_server_address(options.pulse_server_address)?;
        if arguments.len() > MAX_COMMAND_ARGUMENTS {
            return Err(LauncherProcessError::Process(ProcessError::InvalidArgument));
        }
        let mut argument_refs = [""; MAX_COMMAND_ARGUMENTS];
        for (destination, source) in argument_refs.iter_mut().zip(&arguments) {
            *destination = source;
        }
        let handle = self
            .gui_sessions
            .open(
                &environment,
                &command,
                &argument_refs[..arguments.len()],
                wayland_display,
            )
            .map_err(LauncherProcessError::from)?;
        let Some(binding) = self
            .launcher_observations
            .iter_mut()
            .find(|binding| binding.is_none())
        else {
            let _ = self.gui_sessions.close(handle);
            return Err(LauncherProcessError::Process(ProcessError::GuiLimit));
        };
        *binding = Some(ActiveLauncherObservation {
            handle,
            descriptor_id,
            generation,
            recorded: IntegrationObservation::default(),
        });
        self.gui_appearance = Some(appearance);
        Ok(handle)
    }

    pub fn update_gui_colors(
        &mut self,
        dark: bool,
        accent: [u8; 3],
        background: [u8; 3],
        foreground: [u8; 3],
    ) -> Result<(), PackageRuntimeError> {
        let appearance = self
            .gui_appearance
            .unwrap_or_default()
            .with_colors(dark, accent, background, foreground);
        if self.gui_appearance == Some(appearance) {
            return Ok(());
        }
        self.package_runtime
            .as_ref()
            .ok_or(PackageRuntimeError::InvalidPath)?
            .publish_gui_appearance(appearance)?;
        self.gui_appearance = Some(appearance);
        Ok(())
    }

    pub fn launcher_process_exit_status(
        &mut self,
        handle: u64,
    ) -> Result<Option<i32>, LauncherProcessError> {
        let status = self
            .gui_sessions
            .exit_status(handle)
            .map_err(LauncherProcessError::from)?;
        self.record_launcher_observation(handle);
        Ok(status)
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
        self.record_launcher_observation(handle);
        let result = self
            .gui_sessions
            .close(handle)
            .map_err(LauncherProcessError::from);
        if let Some(binding) = self
            .launcher_observations
            .iter_mut()
            .find(|binding| binding.is_some_and(|binding| binding.handle == handle))
        {
            *binding = None;
        }
        result
    }

    fn record_launcher_observation(&mut self, handle: u64) {
        let Some(index) = self
            .launcher_observations
            .iter()
            .position(|binding| binding.is_some_and(|binding| binding.handle == handle))
        else {
            return;
        };
        let Some(root) = self.arch_root.as_ref() else {
            return;
        };
        let Ok(observation) = self
            .gui_sessions
            .integration_observation(handle, root.path())
        else {
            return;
        };
        let binding = self.launcher_observations[index].expect("observed launcher binding");
        if !observation.observed
            || binding.recorded.observed
                && binding.recorded.topology == observation.topology
                && binding.recorded.complete == observation.complete
        {
            return;
        }
        let Some(registry) = self.launcher_registry.as_mut() else {
            return;
        };
        if registry
            .record_integration_observation(
                root.path(),
                &binding.descriptor_id,
                binding.generation,
                observation.topology,
                observation.complete,
            )
            .is_ok()
        {
            self.launcher_observations[index] = Some(ActiveLauncherObservation {
                recorded: observation,
                ..binding
            });
        }
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
            capabilities: launcher_capabilities(descriptor.bridge_capabilities),
            mime_types: launcher_mime_types(descriptor).to_vec(),
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

    pub fn launcher_publish_cancelled(
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
            .mark_cancelled(arch_root.path(), android_package, generation)
    }

    pub fn launcher_retry(
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
            .retry_terminal(arch_root.path(), android_package, generation)
    }

    pub fn launcher_dismiss_cancelled(
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
            .dismiss_cancelled(arch_root.path(), android_package, generation)
    }

    pub fn review_launchers(
        &mut self,
        decisions: &[LauncherReviewDecision],
    ) -> Result<(), LauncherRegistryError> {
        let arch_root = self
            .arch_root
            .as_ref()
            .ok_or(LauncherRegistryError::InvalidRoot)?;
        self.launcher_registry
            .as_mut()
            .ok_or(LauncherRegistryError::InvalidTransition)?
            .review_batch(arch_root.path(), decisions)
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

    pub fn launcher_untrusted_replacement_removal(
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
            .mark_untrusted_replacement_removal(arch_root.path(), android_package, generation)
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
        self.aur_build_graph = None;
        self.aur_build_resolution = None;
        self.aur_build_closure = None;
        self.aur_dependency_reviews.clear();
        self.aur_review = Some(review);
    }

    pub fn retain_aur_dependency_review(
        &mut self,
        review: AurReview,
    ) -> Result<(), PackageRuntimeError> {
        let root = self
            .aur_review
            .as_ref()
            .ok_or(PackageRuntimeError::InvalidPayload)?;
        if self.aur_dependency_reviews.len() >= MAX_AUR_GRAPH_BASES - 1
            || review.package_base == root.package_base
            || review.package_name == root.package_name
            || self.aur_dependency_reviews.iter().any(|current| {
                current.package_base == review.package_base
                    || current.package_name == review.package_name
            })
        {
            return Err(PackageRuntimeError::InvalidPayload);
        }
        self.aur_source_download = None;
        self.aur_build_graph = None;
        self.aur_build_resolution = None;
        self.aur_build_closure = None;
        self.aur_dependency_reviews.push(review);
        Ok(())
    }

    pub fn begin_aur_source_download(
        &mut self,
        package_base: &str,
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
            .aur_review_by_base(package_base)
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
        let expected_checksum = source.checksum.ok_or(PackageRuntimeError::InvalidPayload)?;
        let filename = source.filename.clone();
        let download = self
            .package_runtime
            .as_ref()
            .ok_or(PackageRuntimeError::InvalidPath)?
            .begin_aur_source_download(&filename, expected_checksum, maximum_size)?;
        let file = download.duplicate_file()?;
        self.aur_source_download = Some(download);
        Ok((file, endpoint, filename))
    }

    pub fn aur_source_cache_candidate(
        &self,
        package_base: &str,
        source_index: usize,
    ) -> Result<
        (
            PackageRuntime,
            String,
            archphene_packages::aur::AurSourceChecksum,
        ),
        PackageRuntimeError,
    > {
        let source = self
            .aur_review_by_base(package_base)
            .and_then(|review| review.sources.get(source_index))
            .ok_or(PackageRuntimeError::InvalidPayload)?;
        if source.local || source.insecure_transport || source.remote_url.is_none() {
            return Err(PackageRuntimeError::InvalidPayload);
        }
        let expected_checksum = source.checksum.ok_or(PackageRuntimeError::InvalidPayload)?;
        let package_runtime = self
            .package_runtime
            .as_ref()
            .ok_or(PackageRuntimeError::InvalidPath)?
            .clone();
        Ok((package_runtime, source.filename.clone(), expected_checksum))
    }

    pub fn open_verified_aur_source(
        &self,
        package_base: &str,
        source_index: usize,
    ) -> Result<File, PackageRuntimeError> {
        let (package_runtime, filename, expected_checksum) =
            self.aur_source_cache_candidate(package_base, source_index)?;
        package_runtime.open_verified_aur_source(&filename, expected_checksum)
    }

    pub fn open_reviewed_aur_snapshot(
        &self,
        package_base: &str,
    ) -> Result<File, PackageRuntimeError> {
        let review = self
            .aur_review_by_base(package_base)
            .ok_or(PackageRuntimeError::InvalidPayload)?;
        let expected_sha256 = review
            .snapshot_sha256
            .ok_or(PackageRuntimeError::InvalidPayload)?;
        self.package_runtime
            .as_ref()
            .ok_or(PackageRuntimeError::InvalidPath)?
            .open_reviewed_aur_snapshot(&review.package_base, expected_sha256)
    }

    fn aur_review_by_base(&self, package_base: &str) -> Option<&AurReview> {
        self.aur_review
            .as_ref()
            .filter(|review| review.package_base == package_base)
            .or_else(|| {
                self.aur_dependency_reviews
                    .iter()
                    .find(|review| review.package_base == package_base)
            })
    }

    fn aur_review_by_package(&self, package_name: &str, version: &str) -> Option<&AurReview> {
        let mut matches = self
            .aur_review
            .iter()
            .chain(self.aur_dependency_reviews.iter())
            .filter(|review| {
                review.version == version
                    && review
                        .required_packages
                        .iter()
                        .any(|required| required == package_name)
            });
        let review = matches.next()?;
        if matches.next().is_some() {
            return None;
        }
        Some(review)
    }

    pub fn resolve_aur_build_environment(
        &mut self,
    ) -> Result<PackageResolution, PackageRuntimeError> {
        let partition = self.partition_aur_build_environment()?;
        if !partition.unresolved_targets().is_empty() {
            return Err(PackageRuntimeError::MissingTarget);
        }
        partition
            .resolution()
            .cloned()
            .ok_or(PackageRuntimeError::MissingTarget)
    }

    pub fn partition_aur_build_environment(
        &mut self,
    ) -> Result<RepositoryTargetPartition, PackageRuntimeError> {
        self.aur_build_graph = None;
        let root = self
            .aur_review
            .as_ref()
            .ok_or(PackageRuntimeError::InvalidPayload)?;
        let mut reviews = Vec::with_capacity(1 + self.aur_dependency_reviews.len());
        reviews.push(root.clone());
        reviews.extend(self.aur_dependency_reviews.iter().cloned());
        let targets = aur_build_environment_targets(&reviews)?;
        let borrowed: Vec<&str> = targets.iter().map(String::as_str).collect();
        let package_runtime = self
            .package_runtime
            .as_ref()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let initial = package_runtime.partition_targets_for_fresh_root(&borrowed)?;
        let official: BTreeSet<String> = initial.official_targets().iter().cloned().collect();
        let graph = match plan_reviewed_aur_graph(&reviews, &root.package_name, &official) {
            Ok(graph) => graph,
            Err(AurBuildGraphError::MissingProvider(_))
                if !initial.unresolved_targets().is_empty() =>
            {
                self.aur_build_graph = None;
                self.aur_build_resolution = None;
                self.aur_build_closure = None;
                return Ok(initial);
            }
            Err(_) => return Err(PackageRuntimeError::InvalidPayload),
        };
        let aur_dependencies: BTreeSet<&str> = graph
            .edges
            .iter()
            .map(|edge| edge.dependency.as_str())
            .collect();
        let final_targets: Vec<&str> = targets
            .iter()
            .map(String::as_str)
            .filter(|target| !aur_dependencies.contains(target))
            .collect();
        let partition = package_runtime.partition_targets_for_fresh_root(&final_targets)?;
        if !partition.unresolved_targets().is_empty() {
            return Err(PackageRuntimeError::InvalidPayload);
        }
        self.aur_build_graph = Some(graph);
        self.aur_build_resolution = partition.resolution().cloned();
        self.aur_build_closure = None;
        Ok(partition)
    }

    pub fn write_aur_build_graph(
        &self,
        destination: &mut [u8],
    ) -> Result<usize, PackageRuntimeError> {
        self.aur_build_graph
            .as_ref()
            .ok_or(PackageRuntimeError::InvalidPayload)?
            .write_wire(destination)
            .map_err(|_| PackageRuntimeError::InvalidPayload)
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

    pub fn verified_aur_capability_context(
        &self,
        package_name: &str,
        version: &str,
    ) -> Result<(AurReview, VerifiedPackageClosure, PackageRuntime), PackageRuntimeError> {
        let review = self
            .aur_review_by_package(package_name, version)
            .cloned()
            .ok_or(PackageRuntimeError::InvalidPayload)?;
        let closure = self
            .aur_build_closure
            .clone()
            .ok_or(PackageRuntimeError::InvalidPayload)?;
        let package_runtime = self
            .package_runtime
            .clone()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        Ok((review, closure, package_runtime))
    }

    pub fn verified_aur_graph_capability_context(
        &self,
    ) -> Result<
        (
            archphene_packages::aur::AurBuildGraph,
            Vec<AurReview>,
            VerifiedPackageClosure,
            PackageRuntime,
        ),
        PackageRuntimeError,
    > {
        let graph = self
            .aur_build_graph
            .clone()
            .ok_or(PackageRuntimeError::InvalidPayload)?;
        let mut reviews = Vec::with_capacity(graph.package_bases.len());
        for package_base in &graph.package_bases {
            reviews.push(
                self.aur_review_by_base(package_base)
                    .cloned()
                    .ok_or(PackageRuntimeError::InvalidPayload)?,
            );
        }
        let closure = self
            .aur_build_closure
            .clone()
            .ok_or(PackageRuntimeError::InvalidPayload)?;
        let package_runtime = self
            .package_runtime
            .clone()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        Ok((graph, reviews, closure, package_runtime))
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
            .aur_review_by_package(package_name, version)
            .ok_or(PackageRuntimeError::InvalidPayload)?;
        let mut dependencies = Vec::with_capacity(review.dependencies.len());
        for dependency in &review.dependencies {
            let name = aur_dependency_name(dependency)?;
            if review
                .required_packages
                .iter()
                .any(|package| package == name)
                || self.aur_build_graph.as_ref().is_some_and(|graph| {
                    graph.edges.iter().any(|edge| {
                        edge.package_base == review.package_base && edge.dependency == name
                    })
                })
            {
                continue;
            }
            if dependencies.iter().any(|existing| existing == name) {
                continue;
            }
            dependencies.push(name.to_owned());
        }
        Ok(dependencies)
    }

    pub fn verified_aur_required_packages(
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
        if review.required_packages.is_empty() || review.required_packages.len() > 256 {
            return Err(PackageRuntimeError::InvalidPayload);
        }
        Ok(review.required_packages.clone())
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

    pub fn clear_package_cache(&mut self) -> Result<u64, PackageRuntimeError> {
        if self.package_download.is_some() {
            return Err(PackageRuntimeError::Busy);
        }
        let reclaimed = self
            .package_runtime
            .as_ref()
            .ok_or(PackageRuntimeError::InvalidPath)?
            .clear_package_cache()?;
        self.package_cache = None;
        Ok(reclaimed)
    }

    pub fn clear_package_cache_packages(
        &mut self,
        packages: &[&str],
    ) -> Result<u64, PackageRuntimeError> {
        if self.package_download.is_some() {
            return Err(PackageRuntimeError::Busy);
        }
        let reclaimed = self
            .package_runtime
            .as_ref()
            .ok_or(PackageRuntimeError::InvalidPath)?
            .clear_package_cache_packages(packages)?;
        self.package_cache = None;
        Ok(reclaimed)
    }

    pub fn clear_aur_build_cache(&mut self) -> Result<u64, PackageRuntimeError> {
        if self.aur_source_download.is_some() || self.package_download.is_some() {
            return Err(PackageRuntimeError::Busy);
        }
        let reclaimed = self
            .package_runtime
            .as_ref()
            .ok_or(PackageRuntimeError::InvalidPath)?
            .clear_aur_build_cache()?;
        self.aur_review = None;
        self.aur_build_resolution = None;
        self.aur_build_closure = None;
        Ok(reclaimed)
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

    // Preserve primitive selection coordinates through the coarse runtime
    // boundary and write directly into the caller's reusable buffer.
    #[allow(clippy::too_many_arguments)]
    pub fn write_terminal_selection(
        &mut self,
        handle: u64,
        output: &mut [u8],
        origin_epoch: u64,
        start_row: u32,
        start_column: u16,
        end_row: u32,
        end_column: u16,
    ) -> Result<usize, PackageRuntimeError> {
        self.pty_sessions
            .write_terminal_selection(
                handle,
                output,
                origin_epoch,
                start_row,
                start_column,
                end_row,
                end_column,
            )
            .map_err(PackageRuntimeError::from)
    }

    pub fn write_terminal_clipboard(
        &mut self,
        handle: u64,
        output: &mut [u8],
    ) -> Result<Option<usize>, PackageRuntimeError> {
        self.pty_sessions
            .write_terminal_clipboard(handle, output)
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

fn aur_build_environment_targets(
    reviews: &[AurReview],
) -> Result<BTreeSet<String>, PackageRuntimeError> {
    if reviews.is_empty() || reviews.len() > 32 {
        return Err(PackageRuntimeError::InvalidPayload);
    }
    // Arch's base-devel meta-package deliberately assumes that a base system
    // already exists. Resolve both so the same verified closure can provision
    // the isolated builder and later install every runtime dependency into a
    // new shared Archphene root without a second, implicit download set.
    let mut targets = BTreeSet::from(["base".to_owned(), "base-devel".to_owned()]);
    for review in reviews {
        let required_packages: BTreeSet<&str> = review
            .required_packages
            .iter()
            .map(String::as_str)
            .collect();
        for dependency in review
            .dependencies
            .iter()
            .chain(review.make_dependencies.iter())
            .chain(review.check_dependencies.iter())
        {
            let name = aur_dependency_name(dependency)?;
            if required_packages.contains(name) {
                continue;
            }
            targets.insert(name.to_owned());
            if targets.len() > 256 {
                return Err(PackageRuntimeError::OutputLimit);
            }
        }
    }
    Ok(targets)
}

#[cfg(test)]
mod tests {
    use super::*;
    use archphene_core::SNAPSHOT_SIZE;
    use std::fs;
    use std::sync::atomic::{AtomicU64, Ordering};

    static TEST_ID: AtomicU64 = AtomicU64::new(1);

    #[test]
    fn android_launch_document_paths_are_bounded_and_uri_encoded() {
        let path = "/home/archphene/Documents/Android/report #1.txt";
        assert_eq!(
            validate_launch_document_path(path).expect("valid import path"),
            path,
        );
        assert_eq!(
            file_uri(path),
            "file:///home/archphene/Documents/Android/report%20%231.txt",
        );
        for invalid in [
            "/home/archphene/Documents/report.txt",
            "/home/archphene/Documents/Android/../report.txt",
            "/home/archphene/Documents/Android/a/b.txt",
            "/home/archphene/Documents/Android/\n",
        ] {
            assert!(validate_launch_document_path(invalid).is_err());
        }
    }

    #[test]
    fn electron_compatibility_requires_both_verified_topology_and_consent() {
        let mut unrelated = vec!["--existing".to_owned()];
        apply_electron_compatibility_arguments(&mut unrelated, TOPOLOGY_CHROMIUM, false);
        assert_eq!(unrelated, ["--existing"]);

        apply_electron_compatibility_arguments(&mut unrelated, 0, true);
        assert_eq!(unrelated, ["--existing"]);

        apply_electron_compatibility_arguments(
            &mut unrelated,
            TOPOLOGY_CHROMIUM | archphene_process::integration::TOPOLOGY_WAYLAND,
            true,
        );
        assert_eq!(
            unrelated,
            ["--existing", "--no-sandbox", "--disable-dev-shm-usage"],
        );
    }

    #[test]
    fn electron_compatibility_does_not_duplicate_desktop_flags() {
        let mut arguments = vec![
            "--no-sandbox".to_owned(),
            "--disable-dev-shm-usage".to_owned(),
        ];
        apply_electron_compatibility_arguments(&mut arguments, TOPOLOGY_CHROMIUM, true);
        assert_eq!(arguments, ["--no-sandbox", "--disable-dev-shm-usage"],);
    }

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

    fn test_aur_review(
        package_base: &str,
        package_name: &str,
        required_packages: &[&str],
        dependencies: &[&str],
    ) -> AurReview {
        AurReview {
            package_base: package_base.to_owned(),
            package_name: package_name.to_owned(),
            version: "1.0-1".to_owned(),
            description: "test".to_owned(),
            maintainer: None,
            project_url: None,
            snapshot_path: format!("/{package_base}.tar.gz"),
            last_modified: 1,
            out_of_date: false,
            licenses: vec!["MIT".to_owned()],
            dependencies: dependencies
                .iter()
                .map(|dependency| (*dependency).to_owned())
                .collect(),
            required_packages: required_packages
                .iter()
                .map(|package| (*package).to_owned())
                .collect(),
            provided_packages: required_packages
                .iter()
                .map(|package| (*package).to_owned())
                .collect(),
            make_dependencies: Vec::new(),
            check_dependencies: Vec::new(),
            sources: Vec::new(),
            valid_pgp_keys: Vec::new(),
            install_scripts: Vec::new(),
            build_steps: Vec::new(),
            unverified_source_count: 0,
            insecure_source_count: 0,
            review_sha256: [0; 32],
            snapshot_sha256: None,
            snapshot_commit: None,
            pkgbuild: b"pkgname=test\n".to_vec(),
        }
    }

    #[test]
    fn aur_build_targets_span_reviews_and_only_exclude_same_base_split_outputs() {
        let root = test_aur_review(
            "editor-bin",
            "editor-bin",
            &["editor-bin", "editor-cli"],
            &["glibc>=2.42", "editor-cli", "aur-helper"],
        );
        let helper = test_aur_review(
            "aur-helper",
            "aur-helper",
            &["aur-helper"],
            &["cmake", "glibc"],
        );
        assert_eq!(
            aur_build_environment_targets(&[root, helper])
                .expect("aggregate build targets")
                .into_iter()
                .collect::<Vec<_>>(),
            ["aur-helper", "base", "base-devel", "cmake", "glibc"]
        );
    }

    #[test]
    fn aur_split_outputs_reuse_only_their_unique_review_capability() {
        let mut host = RuntimeHost::new(1);
        host.aur_review = Some(test_aur_review(
            "toolchain-bin",
            "toolchain-sdk-bin",
            &[
                "toolchain-host-bin",
                "toolchain-runtime-bin",
                "toolchain-sdk-bin",
            ],
            &[],
        ));

        assert_eq!(
            host.aur_review_by_package("toolchain-host-bin", "1.0-1")
                .map(|review| review.package_base.as_str()),
            Some("toolchain-bin"),
        );
        assert!(
            host.aur_review_by_package("unreviewed-output", "1.0-1")
                .is_none()
        );

        host.aur_dependency_reviews.push(test_aur_review(
            "ambiguous-bin",
            "ambiguous-bin",
            &["toolchain-host-bin"],
            &[],
        ));
        assert!(
            host.aur_review_by_package("toolchain-host-bin", "1.0-1")
                .is_none()
        );
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
            .begin_mirror_import("Cancelled", [1; 16])
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
