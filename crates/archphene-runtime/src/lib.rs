#![forbid(unsafe_code)]

use std::fmt;
use std::fs::File;
use std::path::Path;

use archphene_core::{Lifecycle, Runtime, RuntimeError};
use archphene_jobs::{JobError, JobOperation, JobState, PackageJob, PackageJobStore};
use archphene_packages::{
    CatalogDownload, PackagePayloadDownload, PackageRuntime, PackageRuntimeError, PackageTool,
    Repository, RepositoryArchitecture, ToolOutput,
};
use archphene_root::{ArchRoot, BootstrapReport, RootError};

pub const STATUS_ARCH_ROOT_READY: u32 = 1 << 0;
pub const STATUS_JOB_STORE_READY: u32 = 1 << 1;
pub const STATUS_PACKAGE_RUNTIME_READY: u32 = 1 << 2;
pub const STATUS_PACKAGE_CATALOG_READY: u32 = 1 << 3;

pub struct RuntimeHost {
    core: Runtime,
    arch_root: Option<ArchRoot>,
    package_jobs: Option<PackageJobStore>,
    package_runtime: Option<PackageRuntime>,
    catalog_download: Option<CatalogDownload>,
    package_download: Option<PackagePayloadDownload>,
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
}

impl fmt::Display for RuntimeBootstrapError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Root(error) => error.fmt(formatter),
            Self::Jobs(error) => error.fmt(formatter),
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

impl RuntimeHost {
    pub fn new(generation: u64) -> Self {
        Self {
            core: Runtime::new(generation),
            arch_root: None,
            package_jobs: None,
            package_runtime: None,
            catalog_download: None,
            package_download: None,
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
        self.arch_root = Some(root);
        self.package_jobs = Some(package_jobs);
        self.core
            .set_status_flags(STATUS_ARCH_ROOT_READY | STATUS_JOB_STORE_READY);
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
        self.core.set_status_flags(status_flags);
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
}
