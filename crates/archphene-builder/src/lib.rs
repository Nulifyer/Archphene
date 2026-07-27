#![deny(unsafe_op_in_unsafe_fn)]

use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::fmt;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom, Write};
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::{MetadataExt, PermissionsExt};
use std::path::{Path, PathBuf};

use archphene_process::{BatchProcess, CommandEnvironment, ProcessError};
use archphene_root::{ArchRoot, RootError};
use flate2::read::GzDecoder;
use rustix::fd::OwnedFd;
use rustix::fs::{
    AtFlags, CWD, Dir, FileType, Mode, OFlags, chmodat, fchmod, fsync, mkdirat, openat, renameat,
    statat, symlinkat, syncfs, unlinkat,
};
use rustix::io::Errno;
use sha2::{Digest, Sha256};
use tar::{Archive, EntryType};
use xz2::read::XzDecoder;

pub const MAX_CLOSURE_MANIFEST_BYTES: usize = 512 * 1024;
pub const MAX_CLOSURE_PACKAGES: usize = 512;
const MAX_ARCHIVE_BYTES: u64 = 4 * 1024 * 1024 * 1024;
const MAX_SIGNATURE_BYTES: u64 = 1024 * 1024;
const MAX_CLOSURE_ARCHIVE_BYTES: u64 = 16 * 1024 * 1024 * 1024;
const MAX_CLOSURE_SIGNATURE_BYTES: u64 = 512 * 1024 * 1024;
const MAX_DIRECTORY_DEPTH: usize = 64;
const MAX_WORKSPACE_ENTRIES: usize = 2_000_000;
const MAX_EXPANDED_ENTRIES: u64 = 2_000_000;
const MAX_EXPANDED_BYTES: u64 = 32 * 1024 * 1024 * 1024;
const MAX_ARCHIVE_ENTRY_BYTES: u64 = 4 * 1024 * 1024 * 1024;
const MAX_ARCHIVE_PATH_BYTES: usize = 4 * 1024;
const LEGACY_WORKSPACE_NAME: &str = "aur-build-workspace";
const WORKSPACE_NAME: &str = "aur-build-workspace-v2";
const CLOSURE_NAME: &str = "package-closure";
const ARCHIVES_NAME: &str = "archives";
const BUILD_ROOT_NAME: &str = "build-root";
const BUILD_ROOT_MANIFEST_NAME: &str = "build-root-manifest";
const BUILDER_RUNTIME_ALIAS_NAME: &str = "builder-runtime-v1";
const BUILDER_RUNTIME_HEADER: &str = "# org.archphene.builder-runtime.v1";
const BUILDER_RUNTIME_PATH_BRIDGE: &str = "libarchphene_path_bridge.so";
const PACMAN_LOCAL_DATABASE_VERSION: &[u8] = b"9\n";
const MAX_BUILDER_RUNTIME_MANIFEST_BYTES: usize = 32 * 1024;
const MAX_BUILDER_RUNTIME_ENTRIES: usize = 32;
const REVIEWED_INPUTS_NAME: &str = "reviewed-inputs";
const REVIEWED_INPUT_MANIFEST_NAME: &str = "manifest";
const BUILD_SESSION_NAME: &str = "aur-build";
const MAX_REVIEWED_INPUTS: usize = 65;
const MAX_REVIEWED_INPUT_BYTES: u64 = 4 * 1024 * 1024 * 1024;
const MAX_REVIEWED_INPUT_TOTAL_BYTES: u64 = 8 * 1024 * 1024 * 1024;
const MAX_REVIEWED_INPUT_MANIFEST_BYTES: usize = 16 * 1024;
const MAX_RECIPE_ENTRIES: u64 = 128;
const MAX_RECIPE_BYTES: u64 = 8 * 1024 * 1024;
const MAX_PACKAGE_INFO_BYTES: usize = 64 * 1024;
const MAX_BUILD_INFO_BYTES: usize = 256 * 1024;
const MAX_BUILT_PACKAGES: usize = 32;
const EXPECTED_MANIFEST_NAME: &str = "expected-manifest";
const PUBLISHED_MANIFEST_NAME: &str = "manifest";
const SESSION_MANIFEST_NAME: &str = "session";

#[derive(Debug)]
pub enum BuilderError {
    InvalidManifest(&'static str),
    InvalidArgument,
    InvalidInput,
    UnsafeWorkspace,
    OutputLimit,
    InvalidArchive,
    InvalidRuntime,
    Io(std::io::Error),
    Syscall(Errno),
    Root(RootError),
    Process(ProcessError),
}

impl fmt::Display for BuilderError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidManifest(reason) => {
                write!(formatter, "invalid build-closure manifest: {reason}")
            }
            Self::InvalidArgument => formatter.write_str("invalid builder argument"),
            Self::InvalidInput => formatter.write_str("invalid Builder input"),
            Self::UnsafeWorkspace => formatter.write_str("unsafe builder workspace"),
            Self::OutputLimit => formatter.write_str("builder limit exceeded"),
            Self::InvalidArchive => formatter.write_str("invalid package archive"),
            Self::InvalidRuntime => formatter.write_str("invalid Builder execution runtime"),
            Self::Io(error) => error.fmt(formatter),
            Self::Syscall(error) => error.fmt(formatter),
            Self::Root(error) => error.fmt(formatter),
            Self::Process(error) => error.fmt(formatter),
        }
    }
}

impl std::error::Error for BuilderError {}

impl From<std::io::Error> for BuilderError {
    fn from(error: std::io::Error) -> Self {
        Self::Io(error)
    }
}

impl From<Errno> for BuilderError {
    fn from(error: Errno) -> Self {
        Self::Syscall(error)
    }
}

impl From<RootError> for BuilderError {
    fn from(error: RootError) -> Self {
        Self::Root(error)
    }
}

impl From<ProcessError> for BuilderError {
    fn from(error: ProcessError) -> Self {
        Self::Process(error)
    }
}

#[derive(Clone, Debug)]
struct ExpectedPackage {
    name: String,
    version: String,
    filename: String,
    archive_bytes: u64,
    archive_sha256: [u8; 32],
    signature_bytes: u64,
    signature_sha256: [u8; 32],
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ClosureReport {
    pub package_count: usize,
    pub archive_bytes: u64,
    pub signature_bytes: u64,
    pub manifest_sha256: [u8; 32],
}

pub struct ClosureSession {
    closure: OwnedFd,
    archives: OwnedFd,
    manifest: Vec<u8>,
    manifest_sha256: [u8; 32],
    packages: Vec<ExpectedPackage>,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct ExtractionReport {
    pub package_count: usize,
    pub entry_count: u64,
    pub expanded_bytes: u64,
}

pub struct ProvisionSession {
    root: std::path::PathBuf,
    workspace: OwnedFd,
    archives: OwnedFd,
    local_database: OwnedFd,
    packages: Vec<ExpectedPackage>,
    manifest_sha256: [u8; 32],
    next_package: usize,
    expected: ExtractionReport,
    extracted: ExtractionReport,
    package_info_buffer: Vec<u8>,
}

pub struct BuilderRuntime {
    environment: CommandEnvironment,
    root_report: ExtractionReport,
    closure_sha256: [u8; 32],
}

#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub enum ReviewedInputRole {
    Snapshot,
    Source,
}

#[derive(Clone, Debug)]
struct ReviewedInput {
    role: ReviewedInputRole,
    filename: String,
    bytes: u64,
    sha256: [u8; 32],
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ReviewedInputReport {
    pub input_count: usize,
    pub input_bytes: u64,
    pub manifest_sha256: [u8; 32],
}

pub struct ReviewedInputSession {
    directory: OwnedFd,
    workspace: OwnedFd,
    package_base: String,
    version: String,
    expected_inputs: usize,
    input_bytes: u64,
    inputs: Vec<ReviewedInput>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RecipeWorkspace {
    pub directory: PathBuf,
    pub recipe_entries: u64,
    pub recipe_bytes: u64,
    pub source_bytes: u64,
}

pub struct AurBuildSession {
    process: Box<BatchProcess>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct BuiltPackageReport {
    pub filename: String,
    pub archive_bytes: u64,
    pub installed_bytes: u64,
    pub sha256: [u8; 32],
    pub build_package_count: usize,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct AurBuildPoll {
    pub exit_status: Option<i32>,
    pub logs: Vec<u8>,
}

#[derive(Clone)]
struct BuilderRuntimeEntry {
    role: String,
    logical: String,
    packaged: String,
    bytes: u64,
    sha256: [u8; 32],
}

impl ReviewedInputSession {
    pub fn begin(
        files_directory: &Path,
        package_base: &str,
        version: &str,
        expected_inputs: usize,
    ) -> Result<Self, BuilderError> {
        terminate_stale_builder_processes()?;
        if !safe_name(package_base)
            || version.is_empty()
            || version.len() > 128
            || version.bytes().any(|byte| !(0x21..=0x7e).contains(&byte))
            || !(1..=MAX_REVIEWED_INPUTS).contains(&expected_inputs)
        {
            return Err(BuilderError::InvalidArgument);
        }
        let files = openat(
            CWD,
            files_directory,
            OFlags::RDONLY | OFlags::DIRECTORY | OFlags::NOFOLLOW | OFlags::CLOEXEC,
            Mode::empty(),
        )?;
        let mut visited = 0;
        remove_entry_if_present(&files, LEGACY_WORKSPACE_NAME, 0, &mut visited)?;
        let workspace = open_or_create_directory(&files, WORKSPACE_NAME)?;
        visited = 0;
        remove_entry_if_present(&workspace, REVIEWED_INPUTS_NAME, 0, &mut visited)?;
        mkdirat(&workspace, REVIEWED_INPUTS_NAME, Mode::from_raw_mode(0o700))?;
        let directory = open_directory(&workspace, REVIEWED_INPUTS_NAME)?;
        fsync(&workspace)?;
        Ok(Self {
            directory,
            workspace,
            package_base: package_base.to_owned(),
            version: version.to_owned(),
            expected_inputs,
            input_bytes: 0,
            inputs: Vec::with_capacity(expected_inputs),
        })
    }

    pub fn stage(
        &mut self,
        role: ReviewedInputRole,
        filename: &str,
        expected_bytes: u64,
        expected_sha256: [u8; 32],
        source: &mut File,
    ) -> Result<(), BuilderError> {
        if self.inputs.len() >= self.expected_inputs
            || !safe_reviewed_filename(filename)
            || expected_bytes == 0
            || expected_bytes > MAX_REVIEWED_INPUT_BYTES
            || self.inputs.iter().any(|input| input.filename == filename)
            || (role == ReviewedInputRole::Snapshot
                && self
                    .inputs
                    .iter()
                    .any(|input| input.role == ReviewedInputRole::Snapshot))
        {
            return Err(BuilderError::InvalidInput);
        }
        let input_bytes = self
            .input_bytes
            .checked_add(expected_bytes)
            .ok_or(BuilderError::OutputLimit)?;
        if input_bytes > MAX_REVIEWED_INPUT_TOTAL_BYTES {
            return Err(BuilderError::OutputLimit);
        }
        let prefix = match role {
            ReviewedInputRole::Snapshot => "snapshot",
            ReviewedInputRole::Source => "source",
        };
        let destination = format!("{prefix}-{}-{filename}", hex_sha256(&expected_sha256),);
        publish_descriptor(
            &self.directory,
            &destination,
            source,
            expected_bytes,
            expected_sha256,
        )?;
        fsync(&self.directory)?;
        self.input_bytes = input_bytes;
        self.inputs.push(ReviewedInput {
            role,
            filename: filename.to_owned(),
            bytes: expected_bytes,
            sha256: expected_sha256,
        });
        Ok(())
    }

    pub fn finish(mut self) -> Result<ReviewedInputReport, BuilderError> {
        if self.inputs.len() != self.expected_inputs
            || self
                .inputs
                .iter()
                .filter(|input| input.role == ReviewedInputRole::Snapshot)
                .count()
                != 1
        {
            return Err(BuilderError::InvalidInput);
        }
        self.inputs.sort_unstable_by(|left, right| {
            (left.role, &left.filename).cmp(&(right.role, &right.filename))
        });
        let mut manifest = String::with_capacity(1024 + self.inputs.len() * 192);
        manifest.push_str("ABIN0001\npackage=");
        manifest.push_str(&self.package_base);
        manifest.push_str("\nversion=");
        manifest.push_str(&self.version);
        manifest.push('\n');
        for input in &self.inputs {
            manifest.push_str(match input.role {
                ReviewedInputRole::Snapshot => "snapshot",
                ReviewedInputRole::Source => "source",
            });
            manifest.push('\t');
            manifest.push_str(&input.filename);
            manifest.push('\t');
            manifest.push_str(&input.bytes.to_string());
            manifest.push('\t');
            manifest.push_str(&hex_sha256(&input.sha256));
            manifest.push('\n');
            let prefix = match input.role {
                ReviewedInputRole::Snapshot => "snapshot",
                ReviewedInputRole::Source => "source",
            };
            verify_staged_file(
                &self.directory,
                &format!("{prefix}-{}-{}", hex_sha256(&input.sha256), input.filename),
                input.bytes,
                input.sha256,
            )?;
        }
        if manifest.len() > MAX_REVIEWED_INPUT_MANIFEST_BYTES {
            return Err(BuilderError::OutputLimit);
        }
        let manifest_sha256 = sha256_bytes(manifest.as_bytes());
        write_atomic(
            &self.directory,
            REVIEWED_INPUT_MANIFEST_NAME,
            manifest.as_bytes(),
        )?;
        fsync(&self.directory)?;
        fsync(&self.workspace)?;
        Ok(ReviewedInputReport {
            input_count: self.inputs.len(),
            input_bytes: self.input_bytes,
            manifest_sha256,
        })
    }
}

impl ClosureSession {
    pub fn begin(
        files_directory: &Path,
        package_base: &str,
        version: &str,
        manifest: &[u8],
        expected_manifest_sha256: [u8; 32],
    ) -> Result<Self, BuilderError> {
        if !safe_name(package_base)
            || version.is_empty()
            || version.len() > 128
            || version.bytes().any(|byte| !(0x21..=0x7e).contains(&byte))
            || manifest.is_empty()
            || manifest.len() > MAX_CLOSURE_MANIFEST_BYTES
            || sha256_bytes(manifest) != expected_manifest_sha256
        {
            return Err(BuilderError::InvalidArgument);
        }
        let packages = parse_manifest(manifest)?;
        let files = openat(
            CWD,
            files_directory,
            OFlags::RDONLY | OFlags::DIRECTORY | OFlags::NOFOLLOW | OFlags::CLOEXEC,
            Mode::empty(),
        )?;
        let workspace = open_or_create_directory(&files, WORKSPACE_NAME)?;
        let mut visited = 0;
        remove_entry_if_present(&workspace, CLOSURE_NAME, 0, &mut visited)?;
        mkdirat(&workspace, CLOSURE_NAME, Mode::from_raw_mode(0o700))?;
        let closure = open_directory(&workspace, CLOSURE_NAME)?;
        mkdirat(&closure, ARCHIVES_NAME, Mode::from_raw_mode(0o700))?;
        let archives = open_directory(&closure, ARCHIVES_NAME)?;
        write_atomic(&closure, EXPECTED_MANIFEST_NAME, manifest)?;
        let session_manifest = format!(
            "ABCS0001\npackage={package_base}\nversion={version}\nclosure={}\n",
            hex_sha256(&expected_manifest_sha256),
        );
        write_atomic(&closure, SESSION_MANIFEST_NAME, session_manifest.as_bytes())?;
        fsync(&archives)?;
        fsync(&closure)?;
        fsync(&workspace)?;
        Ok(Self {
            closure,
            archives,
            manifest: manifest.to_vec(),
            manifest_sha256: expected_manifest_sha256,
            packages,
        })
    }

    pub fn package_count(&self) -> usize {
        self.packages.len()
    }

    pub fn stage_package(
        &self,
        index: usize,
        archive: &mut File,
        signature: &mut File,
    ) -> Result<(), BuilderError> {
        let package = self
            .packages
            .get(index)
            .ok_or(BuilderError::InvalidArgument)?;
        let archive_name = staged_name(index, &package.filename, false);
        let signature_name = staged_name(index, &package.filename, true);
        publish_descriptor(
            &self.archives,
            &archive_name,
            archive,
            package.archive_bytes,
            package.archive_sha256,
        )?;
        publish_descriptor(
            &self.archives,
            &signature_name,
            signature,
            package.signature_bytes,
            package.signature_sha256,
        )?;
        fsync(&self.archives)?;
        Ok(())
    }

    pub fn finish(&self) -> Result<ClosureReport, BuilderError> {
        let mut archive_bytes = 0_u64;
        let mut signature_bytes = 0_u64;
        for (index, package) in self.packages.iter().enumerate() {
            verify_staged_file(
                &self.archives,
                &staged_name(index, &package.filename, false),
                package.archive_bytes,
                package.archive_sha256,
            )?;
            verify_staged_file(
                &self.archives,
                &staged_name(index, &package.filename, true),
                package.signature_bytes,
                package.signature_sha256,
            )?;
            archive_bytes = archive_bytes
                .checked_add(package.archive_bytes)
                .ok_or(BuilderError::OutputLimit)?;
            signature_bytes = signature_bytes
                .checked_add(package.signature_bytes)
                .ok_or(BuilderError::OutputLimit)?;
        }
        write_atomic(&self.closure, PUBLISHED_MANIFEST_NAME, &self.manifest)?;
        fsync(&self.archives)?;
        fsync(&self.closure)?;
        Ok(ClosureReport {
            package_count: self.packages.len(),
            archive_bytes,
            signature_bytes,
            manifest_sha256: self.manifest_sha256,
        })
    }
}

impl ProvisionSession {
    pub fn begin(
        files_directory: &Path,
        package_base: &str,
        version: &str,
        expected_manifest_sha256: [u8; 32],
    ) -> Result<Self, BuilderError> {
        if !safe_name(package_base)
            || version.is_empty()
            || version.len() > 128
            || version.bytes().any(|byte| !(0x21..=0x7e).contains(&byte))
        {
            return Err(BuilderError::InvalidArgument);
        }
        let files = openat(
            CWD,
            files_directory,
            OFlags::RDONLY | OFlags::DIRECTORY | OFlags::NOFOLLOW | OFlags::CLOEXEC,
            Mode::empty(),
        )?;
        let workspace = open_directory(&files, WORKSPACE_NAME)?;
        let closure = open_directory(&workspace, CLOSURE_NAME)?;
        let archives = open_directory(&closure, ARCHIVES_NAME)?;
        let manifest = read_bounded_regular_file(
            &closure,
            PUBLISHED_MANIFEST_NAME,
            MAX_CLOSURE_MANIFEST_BYTES,
        )?;
        if sha256_bytes(&manifest) != expected_manifest_sha256 {
            return Err(BuilderError::InvalidInput);
        }
        let packages = parse_manifest(&manifest)?;
        let expected_session = format!(
            "ABCS0001\npackage={package_base}\nversion={version}\nclosure={}\n",
            hex_sha256(&expected_manifest_sha256),
        );
        let session = read_bounded_regular_file(&closure, SESSION_MANIFEST_NAME, 1024)?;
        if session != expected_session.as_bytes() {
            return Err(BuilderError::InvalidInput);
        }

        let mut expected = ExtractionReport {
            package_count: packages.len(),
            ..ExtractionReport::default()
        };
        for (index, package) in packages.iter().enumerate() {
            let archive_name = staged_name(index, &package.filename, false);
            verify_staged_file(
                &archives,
                &archive_name,
                package.archive_bytes,
                package.archive_sha256,
            )?;
            verify_staged_file(
                &archives,
                &staged_name(index, &package.filename, true),
                package.signature_bytes,
                package.signature_sha256,
            )?;
            let archive = open_regular_file(&archives, &archive_name)?;
            let report = inspect_package_archive(archive, &package.filename, None)?;
            expected.entry_count = checked_entries(expected.entry_count, report.entry_count)?;
            expected.expanded_bytes =
                checked_expanded_bytes(expected.expanded_bytes, report.expanded_bytes)?;
        }

        let mut visited = 0;
        remove_entry_if_present(&workspace, BUILD_ROOT_MANIFEST_NAME, 0, &mut visited)?;
        remove_entry_if_present(&workspace, BUILD_ROOT_NAME, 0, &mut visited)?;
        mkdirat(&workspace, BUILD_ROOT_NAME, Mode::from_raw_mode(0o700))?;
        fsync(&workspace)?;
        let root = files_directory.join(WORKSPACE_NAME).join(BUILD_ROOT_NAME);
        ArchRoot::bootstrap(&root)?;
        let root_descriptor = openat(
            CWD,
            &root,
            OFlags::RDONLY | OFlags::DIRECTORY | OFlags::NOFOLLOW | OFlags::CLOEXEC,
            Mode::empty(),
        )?;
        let var = open_directory(&root_descriptor, "var")?;
        let lib = open_directory(&var, "lib")?;
        let pacman = open_directory(&lib, "pacman")?;
        mkdirat(&pacman, "local", Mode::from_raw_mode(0o755))?;
        let local_database = open_directory(&pacman, "local")?;
        write_atomic(
            &local_database,
            "ALPM_DB_VERSION",
            PACMAN_LOCAL_DATABASE_VERSION,
        )?;
        fsync(&local_database)?;
        Ok(Self {
            root,
            workspace,
            archives,
            local_database,
            packages,
            manifest_sha256: expected_manifest_sha256,
            next_package: 0,
            expected,
            extracted: ExtractionReport::default(),
            package_info_buffer: Vec::with_capacity(MAX_PACKAGE_INFO_BYTES),
        })
    }

    pub fn expected(&self) -> ExtractionReport {
        self.expected
    }

    pub fn extract_next(
        &mut self,
        maximum_packages: usize,
    ) -> Result<ExtractionReport, BuilderError> {
        if maximum_packages == 0 || maximum_packages > 8 {
            return Err(BuilderError::InvalidArgument);
        }
        let end = self
            .next_package
            .saturating_add(maximum_packages)
            .min(self.packages.len());
        while self.next_package < end {
            let index = self.next_package;
            let package = &self.packages[index];
            let archive_name = staged_name(index, &package.filename, false);
            verify_staged_file(
                &self.archives,
                &archive_name,
                package.archive_bytes,
                package.archive_sha256,
            )?;
            let metadata_archive = open_regular_file(&self.archives, &archive_name)?;
            self.package_info_buffer.clear();
            read_package_info(
                metadata_archive,
                &package.filename,
                &mut self.package_info_buffer,
            )?;
            let architecture =
                validate_package_info(package, &self.package_info_buffer)?.to_owned();
            let archive = open_regular_file(&self.archives, &archive_name)?;
            let report = inspect_package_archive(archive, &package.filename, Some(&self.root))?;
            publish_local_package_database(&self.local_database, package, &architecture)?;
            self.extracted.package_count = self
                .extracted
                .package_count
                .checked_add(1)
                .ok_or(BuilderError::OutputLimit)?;
            self.extracted.entry_count =
                checked_entries(self.extracted.entry_count, report.entry_count)?;
            self.extracted.expanded_bytes =
                checked_expanded_bytes(self.extracted.expanded_bytes, report.expanded_bytes)?;
            self.next_package += 1;
        }
        Ok(self.extracted)
    }

    pub fn finish(self) -> Result<ExtractionReport, BuilderError> {
        if self.next_package != self.packages.len() || self.extracted != self.expected {
            return Err(BuilderError::InvalidInput);
        }
        let root = File::open(&self.root)?;
        root.sync_all()?;
        syncfs(&root)?;
        let manifest = format!(
            "ABBR0001\nclosure={}\npackages={}\nentries={}\nbytes={}\n",
            hex_sha256(&self.manifest_sha256),
            self.extracted.package_count,
            self.extracted.entry_count,
            self.extracted.expanded_bytes,
        );
        write_atomic(
            &self.workspace,
            BUILD_ROOT_MANIFEST_NAME,
            manifest.as_bytes(),
        )?;
        fsync(&self.local_database)?;
        Ok(self.extracted)
    }

    pub fn root(&self) -> &Path {
        &self.root
    }
}

impl BuilderRuntime {
    pub fn prepare(
        files_directory: &Path,
        native_directory: &Path,
        manifest: &[u8],
    ) -> Result<Self, BuilderError> {
        let entries = parse_builder_runtime_manifest(manifest)?;
        let files = openat(
            CWD,
            files_directory,
            OFlags::RDONLY | OFlags::DIRECTORY | OFlags::NOFOLLOW | OFlags::CLOEXEC,
            Mode::empty(),
        )?;
        let workspace = open_directory(&files, WORKSPACE_NAME)?;
        let root_report_bytes =
            read_bounded_regular_file(&workspace, BUILD_ROOT_MANIFEST_NAME, 1024)?;
        let (closure_sha256, root_report) = parse_build_root_manifest(&root_report_bytes)?;
        let root = files_directory.join(WORKSPACE_NAME).join(BUILD_ROOT_NAME);
        let root_descriptor = openat(
            CWD,
            &root,
            OFlags::RDONLY | OFlags::DIRECTORY | OFlags::NOFOLLOW | OFlags::CLOEXEC,
            Mode::empty(),
        )?;
        let run = open_directory(&root_descriptor, "run")?;
        let mut visited = 0;
        remove_entry_if_present(&run, BUILDER_RUNTIME_ALIAS_NAME, 0, &mut visited)?;
        mkdirat(&run, BUILDER_RUNTIME_ALIAS_NAME, Mode::from_raw_mode(0o700))?;
        let aliases = open_directory(&run, BUILDER_RUNTIME_ALIAS_NAME)?;

        let native_directory = native_directory.canonicalize()?;
        let native_metadata = std::fs::symlink_metadata(&native_directory)?;
        if native_metadata.file_type().is_symlink() || !native_metadata.is_dir() {
            return Err(BuilderError::InvalidRuntime);
        }
        let mut loader = None;
        let mut has_path_bridge = false;
        for entry in &entries {
            let source = verified_runtime_source(&native_directory, entry)?;
            match entry.role.as_str() {
                "loader" if entry.logical == "@loader" && loader.is_none() => {
                    loader = Some(source);
                }
                "library" => {
                    if !safe_runtime_logical(&entry.logical) {
                        return Err(BuilderError::InvalidRuntime);
                    }
                    has_path_bridge |= entry.logical == BUILDER_RUNTIME_PATH_BRIDGE;
                    symlinkat(&source, &aliases, entry.logical.as_str())?;
                }
                _ => return Err(BuilderError::InvalidRuntime),
            }
        }
        let loader = loader.ok_or(BuilderError::InvalidRuntime)?;
        if !has_path_bridge {
            return Err(BuilderError::InvalidRuntime);
        }
        fsync(&aliases)?;
        fsync(&run)?;
        let alias_path = root.join("run").join(BUILDER_RUNTIME_ALIAS_NAME);
        let mut library_path = alias_path.as_os_str().to_os_string();
        library_path.push(":");
        library_path.push(native_directory.as_os_str());
        library_path.push(":");
        library_path.push(root.join("usr/lib").as_os_str());
        library_path.push(":");
        library_path.push(root.join("usr/lib/libfakeroot").as_os_str());
        let environment = CommandEnvironment::new(
            &root,
            &loader,
            &library_path,
            &alias_path.join(BUILDER_RUNTIME_PATH_BRIDGE),
            &alias_path,
            None,
        )?;
        Ok(Self {
            environment,
            root_report,
            closure_sha256,
        })
    }

    pub fn probe_makepkg(&self) -> Result<Vec<u8>, BuilderError> {
        let output = self.environment.run("makepkg", &["--version"])?;
        if output.exit_code() != 0
            || output.as_bytes().is_empty()
            || !output
                .as_bytes()
                .windows(b"makepkg".len())
                .any(|value| value.eq_ignore_ascii_case(b"makepkg"))
        {
            return Err(BuilderError::InvalidRuntime);
        }
        Ok(output.as_bytes().to_vec())
    }

    pub fn root_report(&self) -> ExtractionReport {
        self.root_report
    }

    pub fn closure_sha256(&self) -> [u8; 32] {
        self.closure_sha256
    }
}

impl AurBuildSession {
    pub fn start(
        files_directory: &Path,
        native_directory: &Path,
        runtime_manifest: &[u8],
        package_base: &str,
        version: &str,
        expected_input_manifest_sha256: [u8; 32],
        expected_closure_sha256: [u8; 32],
    ) -> Result<Self, BuilderError> {
        terminate_stale_builder_processes()?;
        let runtime = BuilderRuntime::prepare(files_directory, native_directory, runtime_manifest)?;
        if runtime.closure_sha256() != expected_closure_sha256 {
            return Err(BuilderError::InvalidInput);
        }
        let recipe = prepare_recipe_workspace(
            files_directory,
            package_base,
            version,
            expected_input_manifest_sha256,
            expected_closure_sha256,
        )?;
        let process = runtime.environment.open_batch(
            "makepkg",
            &[
                "--cleanbuild",
                "--clean",
                "--nodeps",
                "--noconfirm",
                "--noprogressbar",
                "--nosign",
            ],
            &recipe.directory,
        )?;
        Ok(Self { process })
    }

    pub fn poll(&mut self, maximum_log_bytes: usize) -> Result<AurBuildPoll, BuilderError> {
        if maximum_log_bytes == 0 || maximum_log_bytes > 64 * 1024 {
            return Err(BuilderError::InvalidArgument);
        }
        let exit_status = self.process.exit_status()?;
        if exit_status.is_some() {
            terminate_stale_builder_processes()?;
        }
        let mut logs = vec![0_u8; maximum_log_bytes];
        let length = self.process.read_logs(&mut logs)?;
        logs.truncate(length);
        Ok(AurBuildPoll { exit_status, logs })
    }

    pub fn cancel(&mut self) {
        self.process.close();
    }
}

#[derive(Debug)]
struct BuiltPackageMetadata {
    name: String,
    installed_bytes: u64,
    build_package_count: usize,
}

pub fn verify_and_copy_built_package(
    files_directory: &Path,
    package_base: &str,
    package_name: &str,
    version: &str,
    architecture: &str,
    expected_closure_sha256: [u8; 32],
    output: &mut File,
) -> Result<BuiltPackageReport, BuilderError> {
    terminate_stale_builder_processes()?;
    if !safe_name(package_base)
        || !safe_name(package_name)
        || version.is_empty()
        || version.len() > 128
        || version.bytes().any(|byte| !(0x21..=0x7e).contains(&byte))
        || !matches!(architecture, "aarch64" | "x86_64")
    {
        return Err(BuilderError::InvalidArgument);
    }
    let output_metadata = output.metadata()?;
    if !output_metadata.is_file() {
        return Err(BuilderError::InvalidInput);
    }
    output.set_len(0)?;
    output.seek(SeekFrom::Start(0))?;

    let files = openat(
        CWD,
        files_directory,
        OFlags::RDONLY | OFlags::DIRECTORY | OFlags::NOFOLLOW | OFlags::CLOEXEC,
        Mode::empty(),
    )?;
    let workspace = open_directory(&files, WORKSPACE_NAME)?;
    let closure = open_directory(&workspace, CLOSURE_NAME)?;
    let manifest = read_bounded_regular_file(
        &closure,
        PUBLISHED_MANIFEST_NAME,
        MAX_CLOSURE_MANIFEST_BYTES,
    )?;
    if sha256_bytes(&manifest) != expected_closure_sha256 {
        return Err(BuilderError::InvalidInput);
    }
    let expected_packages = parse_manifest(&manifest)?;
    let root = open_directory(&workspace, BUILD_ROOT_NAME)?;
    let home = open_directory(&root, "home")?;
    let archphene = open_directory(&home, "archphene")?;
    let build = open_directory(&archphene, BUILD_SESSION_NAME)?;
    let recipe = open_directory(&build, package_base)?;

    let mut package_names = Vec::<CString>::new();
    for entry in Dir::read_from(&recipe)? {
        let entry = entry?;
        let raw = entry.file_name().to_bytes();
        if raw == b"." || raw == b".." {
            continue;
        }
        if raw.is_empty() || raw.len() > 240 || raw.contains(&b'/') || raw.contains(&0) {
            return Err(BuilderError::UnsafeWorkspace);
        }
        let Ok(name) = std::str::from_utf8(raw) else {
            return Err(BuilderError::UnsafeWorkspace);
        };
        if !name.contains(".pkg.tar.") {
            continue;
        }
        if !safe_filename(name) || package_names.len() >= MAX_BUILT_PACKAGES {
            return Err(BuilderError::InvalidArchive);
        }
        package_names.push(CString::new(raw).map_err(|_| BuilderError::UnsafeWorkspace)?);
    }
    if package_names.is_empty() {
        return Err(BuilderError::InvalidArchive);
    }
    package_names.sort_by(|left, right| left.as_bytes().cmp(right.as_bytes()));

    let mut selected = None;
    let mut seen_output_names = Vec::<String>::with_capacity(package_names.len());
    for archive_name in package_names {
        let descriptor = openat(
            &recipe,
            &archive_name,
            OFlags::RDONLY | OFlags::NOFOLLOW | OFlags::CLOEXEC,
            Mode::empty(),
        )?;
        let mut archive = File::from(descriptor);
        let metadata = archive.metadata()?;
        if !metadata.is_file() || metadata.len() == 0 || metadata.len() > MAX_ARCHIVE_BYTES {
            return Err(BuilderError::InvalidArchive);
        }
        let archive_name = archive_name
            .to_str()
            .map_err(|_| BuilderError::InvalidArchive)?
            .to_owned();
        let built = inspect_built_package_archive(
            &mut archive,
            &archive_name,
            package_base,
            version,
            architecture,
            &expected_packages,
        )?;
        if seen_output_names.iter().any(|name| name == &built.name) {
            return Err(BuilderError::InvalidArchive);
        }
        seen_output_names.push(built.name.clone());
        if built.name == package_name {
            if selected.is_some() {
                return Err(BuilderError::InvalidArchive);
            }
            selected = Some((archive_name, archive, metadata.len(), built));
        }
    }
    let Some((filename, mut archive, archive_bytes, metadata)) = selected else {
        return Err(BuilderError::InvalidArchive);
    };

    let result = (|| {
        archive.seek(SeekFrom::Start(0))?;
        let mut digest = Sha256::new();
        let mut buffer = [0_u8; 64 * 1024];
        let mut total = 0_u64;
        loop {
            let count = archive.read(&mut buffer)?;
            if count == 0 {
                break;
            }
            total = total
                .checked_add(count as u64)
                .ok_or(BuilderError::OutputLimit)?;
            if total > archive_bytes {
                return Err(BuilderError::InvalidArchive);
            }
            digest.update(&buffer[..count]);
            output.write_all(&buffer[..count])?;
        }
        if total != archive_bytes {
            return Err(BuilderError::InvalidArchive);
        }
        output.sync_all()?;
        output.seek(SeekFrom::Start(0))?;
        Ok(BuiltPackageReport {
            filename,
            archive_bytes,
            installed_bytes: metadata.installed_bytes,
            sha256: digest.finalize().into(),
            build_package_count: metadata.build_package_count,
        })
    })();
    if result.is_err() {
        let _ = output.set_len(0);
        let _ = output.seek(SeekFrom::Start(0));
    }
    result
}

pub fn verify_copied_built_package(
    archive: &mut File,
    filename: &str,
    package_base: &str,
    package_name: &str,
    version: &str,
    architecture: &str,
    closure_manifest: &[u8],
    expected_closure_sha256: [u8; 32],
) -> Result<BuiltPackageReport, BuilderError> {
    if !safe_name(package_base)
        || !safe_name(package_name)
        || !safe_filename(filename)
        || version.is_empty()
        || version.len() > 128
        || version.bytes().any(|byte| !(0x21..=0x7e).contains(&byte))
        || !matches!(architecture, "aarch64" | "x86_64")
        || closure_manifest.is_empty()
        || closure_manifest.len() > MAX_CLOSURE_MANIFEST_BYTES
        || sha256_bytes(closure_manifest) != expected_closure_sha256
    {
        return Err(BuilderError::InvalidInput);
    }
    let metadata = archive.metadata()?;
    if !metadata.is_file() || metadata.len() == 0 || metadata.len() > MAX_ARCHIVE_BYTES {
        return Err(BuilderError::InvalidArchive);
    }
    let expected_packages = parse_manifest(closure_manifest)?;
    let built = inspect_built_package_archive(
        archive,
        filename,
        package_base,
        version,
        architecture,
        &expected_packages,
    )?;
    if built.name != package_name {
        return Err(BuilderError::InvalidArchive);
    }
    archive.seek(SeekFrom::Start(0))?;
    let mut digest = Sha256::new();
    let mut buffer = [0_u8; 64 * 1024];
    let mut total = 0_u64;
    loop {
        let count = archive.read(&mut buffer)?;
        if count == 0 {
            break;
        }
        total = total
            .checked_add(count as u64)
            .ok_or(BuilderError::OutputLimit)?;
        if total > metadata.len() {
            return Err(BuilderError::InvalidArchive);
        }
        digest.update(&buffer[..count]);
    }
    if total != metadata.len() {
        return Err(BuilderError::InvalidArchive);
    }
    archive.seek(SeekFrom::Start(0))?;
    Ok(BuiltPackageReport {
        filename: filename.to_owned(),
        archive_bytes: total,
        installed_bytes: built.installed_bytes,
        sha256: digest.finalize().into(),
        build_package_count: built.build_package_count,
    })
}

fn inspect_built_package_archive(
    archive: &mut File,
    filename: &str,
    expected_base: &str,
    expected_version: &str,
    expected_architecture: &str,
    expected_packages: &[ExpectedPackage],
) -> Result<BuiltPackageMetadata, BuilderError> {
    archive.seek(SeekFrom::Start(0))?;
    if filename.ends_with(".pkg.tar.xz") {
        inspect_built_package_tar(
            XzDecoder::new(archive),
            filename,
            expected_base,
            expected_version,
            expected_architecture,
            expected_packages,
        )
    } else if filename.ends_with(".pkg.tar.zst") {
        let decoder = zstd::stream::read::Decoder::new(archive)?;
        inspect_built_package_tar(
            decoder,
            filename,
            expected_base,
            expected_version,
            expected_architecture,
            expected_packages,
        )
    } else {
        Err(BuilderError::InvalidArchive)
    }
}

fn inspect_built_package_tar(
    reader: impl Read,
    filename: &str,
    expected_base: &str,
    expected_version: &str,
    expected_architecture: &str,
    expected_packages: &[ExpectedPackage],
) -> Result<BuiltPackageMetadata, BuilderError> {
    let mut archive = Archive::new(reader);
    let mut package_info = None;
    let mut build_info = None;
    let mut entry_count = 0_u64;
    let mut expanded_bytes = 0_u64;
    for entry in archive.entries()? {
        let entry = entry?;
        let path = entry.path()?.into_owned();
        validate_archive_path(&path)?;
        let entry_type = entry.header().entry_type();
        validate_archive_entry_type(entry_type)?;
        entry_count = checked_entries(entry_count, 1)?;
        if entry_type.is_file() {
            let bytes = entry.header().size()?;
            if bytes > MAX_ARCHIVE_ENTRY_BYTES {
                return Err(BuilderError::OutputLimit);
            }
            expanded_bytes = checked_expanded_bytes(expanded_bytes, bytes)?;
        } else if entry_type.is_symlink() || entry_type.is_hard_link() {
            let target = entry
                .link_name()?
                .ok_or(BuilderError::InvalidArchive)?
                .into_owned();
            validate_archive_link(&target, entry_type.is_hard_link())?;
        }
        let target = match path.as_os_str().as_bytes() {
            b".PKGINFO" => (&mut package_info, MAX_PACKAGE_INFO_BYTES),
            b".BUILDINFO" => (&mut build_info, MAX_BUILD_INFO_BYTES),
            _ => continue,
        };
        if target.0.is_some()
            || !entry_type.is_file()
            || entry.header().size()? == 0
            || entry.header().size()? > target.1 as u64
        {
            return Err(BuilderError::InvalidArchive);
        }
        let mut bytes = Vec::with_capacity(entry.header().size()? as usize);
        entry.take((target.1 + 1) as u64).read_to_end(&mut bytes)?;
        if bytes.is_empty() || bytes.len() > target.1 {
            return Err(BuilderError::InvalidArchive);
        }
        *target.0 = Some(bytes);
    }
    if entry_count == 0 || expanded_bytes == 0 {
        return Err(BuilderError::InvalidArchive);
    }
    let package_info = package_info.ok_or(BuilderError::InvalidArchive)?;
    let build_info = build_info.ok_or(BuilderError::InvalidArchive)?;
    let (name, installed_bytes) = validate_built_package_info(
        &package_info,
        filename,
        expected_base,
        expected_version,
        expected_architecture,
    )?;
    let build_package_count = validate_built_build_info(
        &build_info,
        &name,
        expected_base,
        expected_version,
        expected_architecture,
        expected_packages,
    )?;
    Ok(BuiltPackageMetadata {
        name,
        installed_bytes,
        build_package_count,
    })
}

fn validate_built_package_info(
    package_info: &[u8],
    filename: &str,
    expected_base: &str,
    expected_version: &str,
    expected_architecture: &str,
) -> Result<(String, u64), BuilderError> {
    let text = std::str::from_utf8(package_info).map_err(|_| BuilderError::InvalidArchive)?;
    let mut name = None;
    let mut base = None;
    let mut version = None;
    let mut architecture = None;
    let mut installed_bytes = None;
    for line in text.lines() {
        let Some((key, value)) = line.split_once(" = ") else {
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            return Err(BuilderError::InvalidArchive);
        };
        let destination = match key {
            "pkgname" => Some(&mut name),
            "pkgbase" => Some(&mut base),
            "pkgver" => Some(&mut version),
            "arch" => Some(&mut architecture),
            _ => None,
        };
        if let Some(destination) = destination
            && destination.replace(value).is_some()
        {
            return Err(BuilderError::InvalidArchive);
        }
        if key == "size"
            && installed_bytes
                .replace(
                    value
                        .parse::<u64>()
                        .map_err(|_| BuilderError::InvalidArchive)?,
                )
                .is_some()
        {
            return Err(BuilderError::InvalidArchive);
        }
    }
    let name = name.ok_or(BuilderError::InvalidArchive)?;
    let installed_bytes = installed_bytes.ok_or(BuilderError::InvalidArchive)?;
    if !safe_name(name)
        || base != Some(expected_base)
        || version != Some(expected_version)
        || architecture != Some(expected_architecture)
        || installed_bytes == 0
        || installed_bytes > MAX_EXPANDED_BYTES
    {
        return Err(BuilderError::InvalidArchive);
    }
    let suffix = if filename.ends_with(".pkg.tar.xz") {
        ".pkg.tar.xz"
    } else if filename.ends_with(".pkg.tar.zst") {
        ".pkg.tar.zst"
    } else {
        return Err(BuilderError::InvalidArchive);
    };
    if filename.strip_suffix(suffix)
        != Some(format!("{name}-{expected_version}-{expected_architecture}").as_str())
    {
        return Err(BuilderError::InvalidArchive);
    }
    Ok((name.to_owned(), installed_bytes))
}

fn validate_built_build_info(
    build_info: &[u8],
    expected_name: &str,
    expected_base: &str,
    expected_version: &str,
    expected_architecture: &str,
    expected_packages: &[ExpectedPackage],
) -> Result<usize, BuilderError> {
    let text = std::str::from_utf8(build_info).map_err(|_| BuilderError::InvalidArchive)?;
    let mut format = None;
    let mut name = None;
    let mut base = None;
    let mut version = None;
    let mut architecture = None;
    let mut installed = Vec::<String>::with_capacity(expected_packages.len());
    for line in text.lines() {
        let Some((key, value)) = line.split_once(" = ") else {
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            return Err(BuilderError::InvalidArchive);
        };
        let destination = match key {
            "format" => Some(&mut format),
            "pkgname" => Some(&mut name),
            "pkgbase" => Some(&mut base),
            "pkgver" => Some(&mut version),
            "pkgarch" => Some(&mut architecture),
            _ => None,
        };
        if let Some(destination) = destination
            && destination.replace(value).is_some()
        {
            return Err(BuilderError::InvalidArchive);
        }
        if key == "installed" {
            if value.is_empty()
                || value.len() > 384
                || value.bytes().any(|byte| {
                    !(byte.is_ascii_alphanumeric()
                        || matches!(byte, b'@' | b'+' | b':' | b'.' | b'_' | b'-'))
                })
                || installed.len() >= MAX_CLOSURE_PACKAGES
            {
                return Err(BuilderError::InvalidArchive);
            }
            installed.push(value.to_owned());
        }
    }
    if format != Some("2")
        || name != Some(expected_name)
        || base != Some(expected_base)
        || version != Some(expected_version)
        || architecture != Some(expected_architecture)
    {
        return Err(BuilderError::InvalidArchive);
    }
    let mut expected = Vec::<String>::with_capacity(expected_packages.len());
    for package in expected_packages {
        let package_architecture =
            package_filename_architecture(&package.filename).ok_or(BuilderError::InvalidArchive)?;
        expected.push(format!(
            "{}-{}-{package_architecture}",
            package.name, package.version,
        ));
    }
    installed.sort();
    expected.sort();
    if installed != expected {
        return Err(BuilderError::InvalidArchive);
    }
    Ok(installed.len())
}

fn package_filename_architecture(filename: &str) -> Option<&str> {
    let stem = filename
        .strip_suffix(".pkg.tar.xz")
        .or_else(|| filename.strip_suffix(".pkg.tar.zst"))?;
    let architecture = stem.rsplit_once('-')?.1;
    matches!(architecture, "any" | "aarch64" | "x86_64").then_some(architecture)
}

pub fn prepare_recipe_workspace(
    files_directory: &Path,
    package_base: &str,
    version: &str,
    expected_input_manifest_sha256: [u8; 32],
    expected_closure_sha256: [u8; 32],
) -> Result<RecipeWorkspace, BuilderError> {
    terminate_stale_builder_processes()?;
    if !safe_name(package_base)
        || version.is_empty()
        || version.len() > 128
        || version.bytes().any(|byte| !(0x21..=0x7e).contains(&byte))
    {
        return Err(BuilderError::InvalidArgument);
    }
    let files = openat(
        CWD,
        files_directory,
        OFlags::RDONLY | OFlags::DIRECTORY | OFlags::NOFOLLOW | OFlags::CLOEXEC,
        Mode::empty(),
    )?;
    let workspace = open_directory(&files, WORKSPACE_NAME)?;
    let root_manifest = read_bounded_regular_file(&workspace, BUILD_ROOT_MANIFEST_NAME, 1024)?;
    let (closure_sha256, _) = parse_build_root_manifest(&root_manifest)?;
    if closure_sha256 != expected_closure_sha256 {
        return Err(BuilderError::InvalidInput);
    }
    let inputs = open_directory(&workspace, REVIEWED_INPUTS_NAME)?;
    let manifest = read_bounded_regular_file(
        &inputs,
        REVIEWED_INPUT_MANIFEST_NAME,
        MAX_REVIEWED_INPUT_MANIFEST_BYTES,
    )?;
    if sha256_bytes(&manifest) != expected_input_manifest_sha256 {
        return Err(BuilderError::InvalidInput);
    }
    let reviewed = parse_reviewed_input_manifest(&manifest, package_base, version)?;

    let root = files_directory.join(WORKSPACE_NAME).join(BUILD_ROOT_NAME);
    let root_descriptor = openat(
        CWD,
        &root,
        OFlags::RDONLY | OFlags::DIRECTORY | OFlags::NOFOLLOW | OFlags::CLOEXEC,
        Mode::empty(),
    )?;
    let home = open_directory(&root_descriptor, "home")?;
    let archphene = open_directory(&home, "archphene")?;
    let mut visited = 0;
    remove_entry_if_present(&archphene, BUILD_SESSION_NAME, 0, &mut visited)?;
    mkdirat(&archphene, BUILD_SESSION_NAME, Mode::from_raw_mode(0o700))?;
    let build = open_directory(&archphene, BUILD_SESSION_NAME)?;
    let build_path = root.join("home/archphene").join(BUILD_SESSION_NAME);

    let snapshot = reviewed
        .iter()
        .find(|input| input.role == ReviewedInputRole::Snapshot)
        .ok_or(BuilderError::InvalidInput)?;
    let snapshot_name = reviewed_input_staged_name(snapshot);
    verify_staged_file(&inputs, &snapshot_name, snapshot.bytes, snapshot.sha256)?;
    let snapshot_file = open_regular_file(&inputs, &snapshot_name)?;
    let recipe_report = extract_reviewed_snapshot(snapshot_file, &build_path, package_base)?;
    let recipe_directory = build_path.join(package_base);
    let recipe_descriptor = openat(
        CWD,
        &recipe_directory,
        OFlags::RDONLY | OFlags::DIRECTORY | OFlags::NOFOLLOW | OFlags::CLOEXEC,
        Mode::empty(),
    )?;

    let mut source_bytes = 0_u64;
    for input in reviewed
        .iter()
        .filter(|input| input.role == ReviewedInputRole::Source)
    {
        match statat(
            &recipe_descriptor,
            input.filename.as_str(),
            AtFlags::SYMLINK_NOFOLLOW,
        ) {
            Err(Errno::NOENT) => {}
            Ok(_) => return Err(BuilderError::UnsafeWorkspace),
            Err(error) => return Err(error.into()),
        }
        let staged_name = reviewed_input_staged_name(input);
        verify_staged_file(&inputs, &staged_name, input.bytes, input.sha256)?;
        let mut source = open_regular_file(&inputs, &staged_name)?;
        publish_descriptor(
            &recipe_descriptor,
            &input.filename,
            &mut source,
            input.bytes,
            input.sha256,
        )?;
        source_bytes = source_bytes
            .checked_add(input.bytes)
            .ok_or(BuilderError::OutputLimit)?;
    }
    fsync(&recipe_descriptor)?;
    fsync(&build)?;
    fsync(&archphene)?;
    syncfs(&recipe_descriptor)?;
    Ok(RecipeWorkspace {
        directory: recipe_directory,
        recipe_entries: recipe_report.entry_count,
        recipe_bytes: recipe_report.expanded_bytes,
        source_bytes,
    })
}

fn parse_reviewed_input_manifest(
    manifest: &[u8],
    expected_package_base: &str,
    expected_version: &str,
) -> Result<Vec<ReviewedInput>, BuilderError> {
    let input = std::str::from_utf8(manifest).map_err(|_| BuilderError::InvalidInput)?;
    let mut lines = input.lines();
    if lines.next() != Some("ABIN0001")
        || lines.next().and_then(|line| line.strip_prefix("package="))
            != Some(expected_package_base)
        || lines.next().and_then(|line| line.strip_prefix("version=")) != Some(expected_version)
    {
        return Err(BuilderError::InvalidInput);
    }
    let mut inputs = Vec::<ReviewedInput>::new();
    let mut total_bytes = 0_u64;
    for line in lines {
        let mut fields = line.split('\t');
        let role = match fields.next() {
            Some("snapshot") => ReviewedInputRole::Snapshot,
            Some("source") => ReviewedInputRole::Source,
            _ => return Err(BuilderError::InvalidInput),
        };
        let filename = fields.next().ok_or(BuilderError::InvalidInput)?;
        let bytes = fields
            .next()
            .and_then(|value| value.parse::<u64>().ok())
            .ok_or(BuilderError::InvalidInput)?;
        let sha256 = fields
            .next()
            .ok_or(BuilderError::InvalidInput)
            .and_then(parse_sha256)?;
        if fields.next().is_some()
            || inputs.len() >= MAX_REVIEWED_INPUTS
            || !safe_reviewed_filename(filename)
            || bytes == 0
            || bytes > MAX_REVIEWED_INPUT_BYTES
            || inputs.iter().any(|input| input.filename == filename)
            || (role == ReviewedInputRole::Snapshot
                && inputs
                    .iter()
                    .any(|input| input.role == ReviewedInputRole::Snapshot))
        {
            return Err(BuilderError::InvalidInput);
        }
        total_bytes = total_bytes
            .checked_add(bytes)
            .ok_or(BuilderError::OutputLimit)?;
        if total_bytes > MAX_REVIEWED_INPUT_TOTAL_BYTES {
            return Err(BuilderError::OutputLimit);
        }
        inputs.push(ReviewedInput {
            role,
            filename: filename.to_owned(),
            bytes,
            sha256,
        });
    }
    if inputs.is_empty()
        || inputs
            .iter()
            .filter(|input| input.role == ReviewedInputRole::Snapshot)
            .count()
            != 1
    {
        return Err(BuilderError::InvalidInput);
    }
    Ok(inputs)
}

fn reviewed_input_staged_name(input: &ReviewedInput) -> String {
    let prefix = match input.role {
        ReviewedInputRole::Snapshot => "snapshot",
        ReviewedInputRole::Source => "source",
    };
    format!("{prefix}-{}-{}", hex_sha256(&input.sha256), input.filename,)
}

fn extract_reviewed_snapshot(
    snapshot: File,
    build_directory: &Path,
    package_base: &str,
) -> Result<ExtractionReport, BuilderError> {
    let mut archive = Archive::new(GzDecoder::new(snapshot));
    archive.set_overwrite(false);
    archive.set_preserve_permissions(false);
    archive.set_preserve_ownerships(false);
    archive.set_unpack_xattrs(false);
    let mut report = ExtractionReport::default();
    for entry in archive.entries()? {
        let mut entry = entry?;
        let entry_type = entry.header().entry_type();
        if entry_type.is_pax_global_extensions() {
            if entry.header().size()? > 1024 {
                return Err(BuilderError::OutputLimit);
            }
            let mut extensions = entry
                .pax_extensions()?
                .ok_or(BuilderError::InvalidArchive)?;
            let extension = extensions
                .next()
                .transpose()?
                .ok_or(BuilderError::InvalidArchive)?;
            let key = extension.key_bytes();
            let value = extension.value_bytes();
            if key != b"comment"
                || value.len() != 40
                || !value
                    .iter()
                    .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
                || extensions.next().is_some()
            {
                return Err(BuilderError::InvalidArchive);
            }
            continue;
        }
        let path = entry.path()?.into_owned();
        validate_archive_path(&path)?;
        let mut components = path.components();
        if !matches!(
            components.next(),
            Some(std::path::Component::Normal(component))
                if component.as_bytes() == package_base.as_bytes()
        ) {
            return Err(BuilderError::InvalidArchive);
        }
        if !(entry_type.is_file() || entry_type.is_dir()) {
            return Err(BuilderError::InvalidArchive);
        }
        report.entry_count = report
            .entry_count
            .checked_add(1)
            .ok_or(BuilderError::OutputLimit)?;
        if report.entry_count > MAX_RECIPE_ENTRIES {
            return Err(BuilderError::OutputLimit);
        }
        if entry_type.is_file() {
            let bytes = entry.header().size()?;
            report.expanded_bytes = report
                .expanded_bytes
                .checked_add(bytes)
                .ok_or(BuilderError::OutputLimit)?;
            if report.expanded_bytes > MAX_RECIPE_BYTES {
                return Err(BuilderError::OutputLimit);
            }
        }
        if !entry.unpack_in(build_directory)? {
            return Err(BuilderError::InvalidArchive);
        }
    }
    if report.entry_count == 0 {
        return Err(BuilderError::InvalidArchive);
    }
    Ok(report)
}

#[cfg(target_os = "android")]
fn terminate_stale_builder_processes() -> Result<(), BuilderError> {
    use std::thread;
    use std::time::Duration;

    use rustix::process::{Pid, Signal, getpid, getuid, kill_process};

    let current = getpid();
    let uid = getuid().as_raw();
    for _ in 0..8 {
        let mut stale = Vec::<(Pid, u64)>::new();
        for entry in std::fs::read_dir("/proc")? {
            let entry = entry?;
            let Some(raw_pid) = entry
                .file_name()
                .to_str()
                .and_then(|value| value.parse::<i32>().ok())
            else {
                continue;
            };
            let Some(pid) = Pid::from_raw(raw_pid) else {
                continue;
            };
            if pid == current {
                continue;
            }
            let process = entry.path();
            let Some(process_uid) = read_proc_uid(&process)? else {
                continue;
            };
            if process_uid != uid {
                continue;
            }
            let Some(start_time) = read_proc_start_time(&process)? else {
                continue;
            };
            stale.push((pid, start_time));
            if stale.len() > 4096 {
                return Err(BuilderError::OutputLimit);
            }
        }
        if stale.is_empty() {
            return Ok(());
        }
        for (pid, expected_start_time) in stale {
            let process = PathBuf::from("/proc").join(pid.as_raw_nonzero().to_string());
            if read_proc_start_time(&process)? != Some(expected_start_time) {
                continue;
            }
            match kill_process(pid, Signal::KILL) {
                Ok(()) | Err(Errno::SRCH) => {}
                Err(error) => return Err(error.into()),
            }
        }
        thread::sleep(Duration::from_millis(20));
    }
    Err(BuilderError::UnsafeWorkspace)
}

#[cfg(target_os = "android")]
fn read_proc_uid(process: &Path) -> Result<Option<u32>, BuilderError> {
    let Some(status) = read_bounded_proc_file(&process.join("status"))? else {
        return Ok(None);
    };
    Ok(status
        .lines()
        .find_map(|line| line.strip_prefix("Uid:"))
        .and_then(|value| value.split_ascii_whitespace().next())
        .and_then(|value| value.parse::<u32>().ok()))
}

#[cfg(target_os = "android")]
fn read_proc_start_time(process: &Path) -> Result<Option<u64>, BuilderError> {
    let Some(stat) = read_bounded_proc_file(&process.join("stat"))? else {
        return Ok(None);
    };
    let Some(command_end) = stat.rfind(')') else {
        return Err(BuilderError::UnsafeWorkspace);
    };
    Ok(stat[command_end + 1..]
        .split_ascii_whitespace()
        .nth(19)
        .and_then(|value| value.parse::<u64>().ok()))
}

#[cfg(target_os = "android")]
fn read_bounded_proc_file(path: &Path) -> Result<Option<String>, BuilderError> {
    let mut file = match File::open(path) {
        Ok(file) => file,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) if error.kind() == std::io::ErrorKind::PermissionDenied => return Ok(None),
        Err(error) => return Err(error.into()),
    };
    let mut bytes = Vec::with_capacity(4096);
    Read::by_ref(&mut file)
        .take(16 * 1024)
        .read_to_end(&mut bytes)?;
    let mut extra = [0_u8; 1];
    if file.read(&mut extra)? != 0 {
        return Err(BuilderError::OutputLimit);
    }
    String::from_utf8(bytes)
        .map(Some)
        .map_err(|_| BuilderError::UnsafeWorkspace)
}

#[cfg(not(target_os = "android"))]
fn terminate_stale_builder_processes() -> Result<(), BuilderError> {
    Ok(())
}

fn parse_build_root_manifest(
    manifest: &[u8],
) -> Result<([u8; 32], ExtractionReport), BuilderError> {
    let input = std::str::from_utf8(manifest).map_err(|_| BuilderError::InvalidRuntime)?;
    let mut lines = input.lines();
    if lines.next() != Some("ABBR0001") {
        return Err(BuilderError::InvalidRuntime);
    }
    let closure = lines
        .next()
        .and_then(|line| line.strip_prefix("closure="))
        .ok_or(BuilderError::InvalidRuntime)
        .and_then(parse_sha256)?;
    let package_count = parse_root_manifest_value(lines.next(), "packages=")?;
    let entry_count = parse_root_manifest_value(lines.next(), "entries=")?;
    let expanded_bytes = parse_root_manifest_value(lines.next(), "bytes=")?;
    if lines.next().is_some()
        || package_count == 0
        || package_count > MAX_CLOSURE_PACKAGES as u64
        || entry_count == 0
        || entry_count > MAX_EXPANDED_ENTRIES
        || expanded_bytes == 0
        || expanded_bytes > MAX_EXPANDED_BYTES
    {
        return Err(BuilderError::InvalidRuntime);
    }
    Ok((
        closure,
        ExtractionReport {
            package_count: usize::try_from(package_count)
                .map_err(|_| BuilderError::InvalidRuntime)?,
            entry_count,
            expanded_bytes,
        },
    ))
}

fn parse_root_manifest_value(line: Option<&str>, prefix: &str) -> Result<u64, BuilderError> {
    line.and_then(|value| value.strip_prefix(prefix))
        .and_then(|value| value.parse::<u64>().ok())
        .ok_or(BuilderError::InvalidRuntime)
}

fn parse_builder_runtime_manifest(
    manifest: &[u8],
) -> Result<Vec<BuilderRuntimeEntry>, BuilderError> {
    if manifest.is_empty() || manifest.len() > MAX_BUILDER_RUNTIME_MANIFEST_BYTES {
        return Err(BuilderError::InvalidRuntime);
    }
    let input = std::str::from_utf8(manifest).map_err(|_| BuilderError::InvalidRuntime)?;
    let mut lines = input.lines();
    if lines.next() != Some(BUILDER_RUNTIME_HEADER) {
        return Err(BuilderError::InvalidRuntime);
    }
    let mut entries = Vec::<BuilderRuntimeEntry>::new();
    for line in lines {
        if line.is_empty() || entries.len() >= MAX_BUILDER_RUNTIME_ENTRIES {
            return Err(BuilderError::InvalidRuntime);
        }
        let mut fields = line.split('\t');
        let role = fields.next().ok_or(BuilderError::InvalidRuntime)?;
        let logical = fields.next().ok_or(BuilderError::InvalidRuntime)?;
        let packaged = fields.next().ok_or(BuilderError::InvalidRuntime)?;
        let bytes = fields
            .next()
            .and_then(|value| value.parse::<u64>().ok())
            .ok_or(BuilderError::InvalidRuntime)?;
        let sha256 = fields
            .next()
            .ok_or(BuilderError::InvalidRuntime)
            .and_then(parse_sha256)?;
        if fields.next().is_some()
            || !matches!(role, "loader" | "library")
            || logical.is_empty()
            || logical.len() > 128
            || !safe_runtime_packaged(packaged, &sha256)
            || bytes == 0
            || bytes > 64 * 1024 * 1024
            || entries.iter().any(|entry| entry.logical == logical)
        {
            return Err(BuilderError::InvalidRuntime);
        }
        entries.push(BuilderRuntimeEntry {
            role: role.to_owned(),
            logical: logical.to_owned(),
            packaged: packaged.to_owned(),
            bytes,
            sha256,
        });
    }
    if entries.len() < 12 {
        return Err(BuilderError::InvalidRuntime);
    }
    Ok(entries)
}

fn safe_runtime_logical(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 128
        && !value.starts_with('.')
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'+' | b'.' | b'_' | b'-'))
}

fn safe_runtime_packaged(value: &str, sha256: &[u8; 32]) -> bool {
    let Some(identity) = value
        .strip_prefix("libarchphene_builder_")
        .and_then(|value| value.strip_suffix(".so"))
    else {
        return false;
    };
    identity.len() == 24
        && identity
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
        && hex_sha256(sha256).starts_with(identity)
}

fn verified_runtime_source(
    native_directory: &Path,
    entry: &BuilderRuntimeEntry,
) -> Result<PathBuf, BuilderError> {
    let source = native_directory.join(&entry.packaged);
    let metadata = std::fs::symlink_metadata(&source)?;
    if metadata.file_type().is_symlink()
        || !metadata.is_file()
        || metadata.len() != entry.bytes
        || metadata.mode() & 0o022 != 0
    {
        return Err(BuilderError::InvalidRuntime);
    }
    let canonical = source.canonicalize()?;
    if !canonical.starts_with(native_directory) {
        return Err(BuilderError::InvalidRuntime);
    }
    let mut file = File::open(&canonical)?;
    let mut digest = Sha256::new();
    let mut buffer = [0_u8; 64 * 1024];
    let mut total = 0_u64;
    loop {
        let count = file.read(&mut buffer)?;
        if count == 0 {
            break;
        }
        total = total
            .checked_add(count as u64)
            .ok_or(BuilderError::OutputLimit)?;
        if total > entry.bytes {
            return Err(BuilderError::InvalidRuntime);
        }
        digest.update(&buffer[..count]);
    }
    if total != entry.bytes || <[u8; 32]>::from(digest.finalize()) != entry.sha256 {
        return Err(BuilderError::InvalidRuntime);
    }
    Ok(canonical)
}

fn read_bounded_regular_file(
    directory: &OwnedFd,
    name: &str,
    maximum: usize,
) -> Result<Vec<u8>, BuilderError> {
    let descriptor = open_regular_file(directory, name)?;
    let metadata = descriptor.metadata()?;
    let length = usize::try_from(metadata.len()).map_err(|_| BuilderError::OutputLimit)?;
    if !metadata.is_file() || length == 0 || length > maximum {
        return Err(BuilderError::InvalidInput);
    }
    let mut output = vec![0_u8; length];
    let mut file = descriptor;
    file.read_exact(&mut output)?;
    let mut extra = [0_u8; 1];
    if file.read(&mut extra)? != 0 {
        return Err(BuilderError::InvalidInput);
    }
    Ok(output)
}

fn open_regular_file(directory: &OwnedFd, name: &str) -> Result<File, BuilderError> {
    let descriptor = openat(
        directory,
        name,
        OFlags::RDONLY | OFlags::NOFOLLOW | OFlags::CLOEXEC,
        Mode::empty(),
    )?;
    let file = File::from(descriptor);
    if !file.metadata()?.is_file() {
        return Err(BuilderError::InvalidInput);
    }
    Ok(file)
}

fn inspect_package_archive(
    archive: File,
    filename: &str,
    extraction_root: Option<&Path>,
) -> Result<ExtractionReport, BuilderError> {
    if filename.ends_with(".pkg.tar.xz") {
        inspect_tar_archive(XzDecoder::new(archive), extraction_root)
    } else if filename.ends_with(".pkg.tar.zst") {
        let decoder = zstd::stream::read::Decoder::new(archive)?;
        inspect_tar_archive(decoder, extraction_root)
    } else {
        Err(BuilderError::InvalidArchive)
    }
}

fn read_package_info(
    archive: File,
    filename: &str,
    output: &mut Vec<u8>,
) -> Result<(), BuilderError> {
    if filename.ends_with(".pkg.tar.xz") {
        read_package_info_tar(XzDecoder::new(archive), output)
    } else if filename.ends_with(".pkg.tar.zst") {
        let decoder = zstd::stream::read::Decoder::new(archive)?;
        read_package_info_tar(decoder, output)
    } else {
        Err(BuilderError::InvalidArchive)
    }
}

fn read_package_info_tar(reader: impl Read, output: &mut Vec<u8>) -> Result<(), BuilderError> {
    let mut archive = Archive::new(reader);
    for entry in archive.entries()? {
        let entry = entry?;
        if entry.path()?.as_os_str().as_bytes() != b".PKGINFO" {
            continue;
        }
        if !entry.header().entry_type().is_file()
            || entry.header().size()? == 0
            || entry.header().size()? > MAX_PACKAGE_INFO_BYTES as u64
        {
            return Err(BuilderError::InvalidArchive);
        }
        output.clear();
        entry
            .take((MAX_PACKAGE_INFO_BYTES + 1) as u64)
            .read_to_end(output)?;
        if output.is_empty() || output.len() > MAX_PACKAGE_INFO_BYTES {
            return Err(BuilderError::InvalidArchive);
        }
        return Ok(());
    }
    Err(BuilderError::InvalidArchive)
}

fn validate_package_info<'a>(
    package: &ExpectedPackage,
    package_info: &'a [u8],
) -> Result<&'a str, BuilderError> {
    let text = std::str::from_utf8(package_info).map_err(|_| BuilderError::InvalidArchive)?;
    let mut name = None;
    let mut version = None;
    let mut architecture = None;
    for line in text.lines() {
        let Some((key, value)) = line.split_once(" = ") else {
            continue;
        };
        match key {
            "pkgname" if name.replace(value).is_none() => {}
            "pkgver" if version.replace(value).is_none() => {}
            "arch" if architecture.replace(value).is_none() => {}
            "pkgname" | "pkgver" | "arch" => return Err(BuilderError::InvalidArchive),
            _ => {}
        }
    }
    let architecture = architecture.ok_or(BuilderError::InvalidArchive)?;
    if name != Some(package.name.as_str())
        || version != Some(package.version.as_str())
        || !matches!(architecture, "any" | "aarch64" | "x86_64")
        || !package
            .filename
            .contains(&format!("-{architecture}.pkg.tar."))
    {
        return Err(BuilderError::InvalidArchive);
    }
    Ok(architecture)
}

fn publish_local_package_database(
    local_database: &OwnedFd,
    package: &ExpectedPackage,
    architecture: &str,
) -> Result<(), BuilderError> {
    let directory_name = format!("{}-{}", package.name, package.version);
    if directory_name.len() > 240
        || directory_name.bytes().any(|byte| byte == 0 || byte == b'/')
        || matches!(directory_name.as_str(), "." | "..")
    {
        return Err(BuilderError::InvalidArchive);
    }
    mkdirat(
        local_database,
        directory_name.as_str(),
        Mode::from_raw_mode(0o755),
    )?;
    let directory = open_directory(local_database, directory_name.as_str())?;
    let description = format!(
        "%NAME%\n{}\n\n%VERSION%\n{}\n\n%ARCH%\n{}\n\n%REASON%\n1\n\n%VALIDATION%\npgp\n\n",
        package.name, package.version, architecture,
    );
    write_atomic(&directory, "desc", description.as_bytes())?;
    fsync(&directory)?;
    Ok(())
}

fn inspect_tar_archive(
    reader: impl Read,
    extraction_root: Option<&Path>,
) -> Result<ExtractionReport, BuilderError> {
    let mut archive = Archive::new(reader);
    archive.set_overwrite(true);
    archive.set_preserve_permissions(false);
    archive.set_preserve_ownerships(false);
    archive.set_unpack_xattrs(false);
    let mut report = ExtractionReport::default();
    let mut materialized_files = HashMap::<PathBuf, u64>::new();
    let entries = archive.entries()?;
    for entry in entries {
        let mut entry = entry?;
        let path = entry.path()?.into_owned();
        validate_archive_path(&path)?;
        let entry_type = entry.header().entry_type();
        validate_archive_entry_type(entry_type)?;
        let mut materialized_bytes = None;
        let mut hard_link_target = None;
        if entry_type.is_symlink() || entry_type.is_hard_link() {
            let target = entry
                .link_name()?
                .ok_or(BuilderError::InvalidArchive)?
                .into_owned();
            validate_archive_link(&target, entry_type.is_hard_link())?;
            if entry_type.is_hard_link() {
                let bytes = materialized_files
                    .get(&target)
                    .copied()
                    .ok_or(BuilderError::InvalidArchive)?;
                materialized_bytes = Some(bytes);
                hard_link_target = Some(target);
            }
        }
        report.entry_count = checked_entries(report.entry_count, 1)?;
        if entry_type.is_file() {
            let bytes = entry.header().size()?;
            if bytes > MAX_ARCHIVE_ENTRY_BYTES {
                return Err(BuilderError::OutputLimit);
            }
            report.expanded_bytes = checked_expanded_bytes(report.expanded_bytes, bytes)?;
            materialized_bytes = Some(bytes);
        } else if let Some(bytes) = materialized_bytes {
            report.expanded_bytes = checked_expanded_bytes(report.expanded_bytes, bytes)?;
        }
        if archive_metadata_path(&path) {
            materialized_files.remove(&path);
            continue;
        }
        if let Some(bytes) = materialized_bytes {
            materialized_files.insert(path.clone(), bytes);
        } else {
            materialized_files.remove(&path);
        }
        if let Some(root) = extraction_root {
            if let Some(target) = hard_link_target {
                copy_android_hard_link(root, &path, &target, materialized_bytes.unwrap_or(0))?;
            } else if !entry.unpack_in(root)? {
                return Err(BuilderError::InvalidArchive);
            }
        }
    }
    Ok(report)
}

fn copy_android_hard_link(
    root: &Path,
    destination_path: &Path,
    source_path: &Path,
    expected_bytes: u64,
) -> Result<(), BuilderError> {
    let canonical_root = root.canonicalize()?;
    let source = root.join(source_path);
    let source_metadata = std::fs::symlink_metadata(&source)?;
    if !source_metadata.is_file() || source_metadata.len() != expected_bytes {
        return Err(BuilderError::InvalidArchive);
    }
    let canonical_source = source.canonicalize()?;
    if !canonical_source.starts_with(&canonical_root) {
        return Err(BuilderError::InvalidArchive);
    }

    let destination = root.join(destination_path);
    let parent = destination.parent().ok_or(BuilderError::InvalidArchive)?;
    create_archive_parent(root, parent)?;
    let canonical_parent = parent.canonicalize()?;
    if !canonical_parent.starts_with(&canonical_root) {
        return Err(BuilderError::InvalidArchive);
    }
    match std::fs::symlink_metadata(&destination) {
        Ok(metadata) if metadata.is_file() || metadata.file_type().is_symlink() => {
            std::fs::remove_file(&destination)?;
        }
        Ok(_) => return Err(BuilderError::InvalidArchive),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => return Err(error.into()),
    }

    let source_descriptor = openat(
        CWD,
        &source,
        OFlags::RDONLY | OFlags::NOFOLLOW | OFlags::CLOEXEC,
        Mode::empty(),
    )?;
    let input = File::from(source_descriptor);
    if !input.metadata()?.is_file() {
        return Err(BuilderError::InvalidArchive);
    }
    let destination_descriptor = openat(
        CWD,
        &destination,
        OFlags::WRONLY | OFlags::CREATE | OFlags::EXCL | OFlags::NOFOLLOW | OFlags::CLOEXEC,
        Mode::from_raw_mode(0o600),
    )?;
    let copy_result = (|| {
        let mut output = File::from(destination_descriptor);
        let maximum = expected_bytes
            .checked_add(1)
            .ok_or(BuilderError::OutputLimit)?;
        let copied = std::io::copy(&mut input.take(maximum), &mut output)?;
        if copied != expected_bytes {
            return Err(BuilderError::InvalidArchive);
        }
        fchmod(
            &output,
            Mode::from_raw_mode(source_metadata.permissions().mode() & 0o777),
        )?;
        Ok(())
    })();
    if let Err(error) = copy_result {
        let _ = std::fs::remove_file(&destination);
        return Err(error);
    }
    Ok(())
}

fn create_archive_parent(root: &Path, parent: &Path) -> Result<(), BuilderError> {
    let relative = parent
        .strip_prefix(root)
        .map_err(|_| BuilderError::InvalidArchive)?;
    let root_descriptor = openat(
        CWD,
        root,
        OFlags::RDONLY | OFlags::DIRECTORY | OFlags::NOFOLLOW | OFlags::CLOEXEC,
        Mode::empty(),
    )?;
    let mut directory = root_descriptor;
    for component in relative.components() {
        let std::path::Component::Normal(name) = component else {
            return Err(BuilderError::InvalidArchive);
        };
        match mkdirat(&directory, name, Mode::from_raw_mode(0o755)) {
            Ok(()) | Err(Errno::EXIST) => {}
            Err(error) => return Err(error.into()),
        }
        directory = openat(
            &directory,
            name,
            OFlags::RDONLY | OFlags::DIRECTORY | OFlags::NOFOLLOW | OFlags::CLOEXEC,
            Mode::empty(),
        )?;
    }
    Ok(())
}

fn validate_archive_path(path: &Path) -> Result<(), BuilderError> {
    let bytes = path.as_os_str().as_bytes();
    if bytes.is_empty()
        || bytes.len() > MAX_ARCHIVE_PATH_BYTES
        || bytes.contains(&0)
        || path.is_absolute()
        || path.components().any(|component| {
            matches!(
                component,
                std::path::Component::ParentDir
                    | std::path::Component::RootDir
                    | std::path::Component::Prefix(_)
            )
        })
    {
        return Err(BuilderError::InvalidArchive);
    }
    Ok(())
}

fn validate_archive_link(path: &Path, hard_link: bool) -> Result<(), BuilderError> {
    let bytes = path.as_os_str().as_bytes();
    if bytes.is_empty() || bytes.len() > MAX_ARCHIVE_PATH_BYTES || bytes.contains(&0) {
        return Err(BuilderError::InvalidArchive);
    }
    if hard_link
        && (path.is_absolute()
            || path.components().any(|component| {
                matches!(
                    component,
                    std::path::Component::ParentDir
                        | std::path::Component::RootDir
                        | std::path::Component::Prefix(_)
                )
            }))
    {
        return Err(BuilderError::InvalidArchive);
    }
    Ok(())
}

fn validate_archive_entry_type(entry_type: EntryType) -> Result<(), BuilderError> {
    if entry_type.is_file()
        || entry_type.is_dir()
        || entry_type.is_symlink()
        || entry_type.is_hard_link()
    {
        Ok(())
    } else {
        Err(BuilderError::InvalidArchive)
    }
}

fn archive_metadata_path(path: &Path) -> bool {
    path.components().count() == 1
        && matches!(
            path.as_os_str().as_bytes(),
            b".BUILDINFO" | b".CHANGELOG" | b".INSTALL" | b".MTREE" | b".PKGINFO"
        )
}

fn checked_entries(current: u64, additional: u64) -> Result<u64, BuilderError> {
    let total = current
        .checked_add(additional)
        .ok_or(BuilderError::OutputLimit)?;
    if total > MAX_EXPANDED_ENTRIES {
        return Err(BuilderError::OutputLimit);
    }
    Ok(total)
}

fn checked_expanded_bytes(current: u64, additional: u64) -> Result<u64, BuilderError> {
    let total = current
        .checked_add(additional)
        .ok_or(BuilderError::OutputLimit)?;
    if total > MAX_EXPANDED_BYTES {
        return Err(BuilderError::OutputLimit);
    }
    Ok(total)
}

fn parse_manifest(manifest: &[u8]) -> Result<Vec<ExpectedPackage>, BuilderError> {
    let input = std::str::from_utf8(manifest)
        .map_err(|_| BuilderError::InvalidManifest("manifest is not UTF-8"))?;
    let mut lines = input.lines();
    if lines.next() != Some("ABPC0001") {
        return Err(BuilderError::InvalidManifest("wrong manifest version"));
    }
    let mut packages = Vec::new();
    let mut expected_summary = None;
    for line in lines {
        if line.is_empty() {
            continue;
        }
        if let Some(summary) = line.strip_prefix("summary\t") {
            if expected_summary.is_some() {
                return Err(BuilderError::InvalidManifest("duplicate summary"));
            }
            let mut fields = summary.split('\t');
            let count = fields
                .next()
                .and_then(|value| value.parse::<usize>().ok())
                .ok_or(BuilderError::InvalidManifest("invalid summary count"))?;
            let bytes = fields
                .next()
                .and_then(|value| value.parse::<u64>().ok())
                .ok_or(BuilderError::InvalidManifest("invalid summary size"))?;
            if fields.next().is_some() {
                return Err(BuilderError::InvalidManifest("extra summary fields"));
            }
            expected_summary = Some((count, bytes));
            continue;
        }
        if expected_summary.is_some() || packages.len() >= MAX_CLOSURE_PACKAGES {
            return Err(BuilderError::InvalidManifest(
                "package entry follows summary or exceeds limit",
            ));
        }
        let mut fields = line.split('\t');
        let repository = required_field(&mut fields)?;
        let name = required_field(&mut fields)?;
        let version = required_field(&mut fields)?;
        let filename = required_field(&mut fields)?;
        let url = required_field(&mut fields)?;
        let archive_bytes = parse_bounded_size(required_field(&mut fields)?, MAX_ARCHIVE_BYTES)?;
        let archive_sha256 = parse_sha256(required_field(&mut fields)?)?;
        let signature_bytes =
            parse_bounded_size(required_field(&mut fields)?, MAX_SIGNATURE_BYTES)?;
        let signature_sha256 = parse_sha256(required_field(&mut fields)?)?;
        if fields.next().is_some() {
            return Err(BuilderError::InvalidManifest("extra package fields"));
        }
        if !matches!(repository, "core" | "extra") {
            return Err(BuilderError::InvalidManifest("unsupported repository"));
        }
        if !safe_name(name) {
            return Err(BuilderError::InvalidManifest("unsafe package name"));
        }
        if version.is_empty()
            || version.len() > 128
            || version
                .bytes()
                .any(|byte| byte.is_ascii_whitespace() || byte == 0)
        {
            return Err(BuilderError::InvalidManifest("unsafe package version"));
        }
        if !safe_filename(filename) {
            return Err(BuilderError::InvalidManifest("unsafe package filename"));
        }
        if !url.starts_with("https://")
            || url.len() > 2048
            || url
                .bytes()
                .any(|byte| byte.is_ascii_control() || byte.is_ascii_whitespace())
        {
            return Err(BuilderError::InvalidManifest("unsafe package URL"));
        }
        if packages
            .iter()
            .any(|package: &ExpectedPackage| package.filename == filename)
        {
            return Err(BuilderError::InvalidManifest("duplicate package filename"));
        }
        packages.push(ExpectedPackage {
            name: name.to_owned(),
            version: version.to_owned(),
            filename: filename.to_owned(),
            archive_bytes,
            archive_sha256,
            signature_bytes,
            signature_sha256,
        });
    }
    let (expected_count, expected_bytes) =
        expected_summary.ok_or(BuilderError::InvalidManifest("missing summary"))?;
    let actual_bytes = packages.iter().try_fold(0_u64, |total, package| {
        total
            .checked_add(package.archive_bytes)
            .ok_or(BuilderError::OutputLimit)
    })?;
    let actual_signature_bytes = packages.iter().try_fold(0_u64, |total, package| {
        total
            .checked_add(package.signature_bytes)
            .ok_or(BuilderError::OutputLimit)
    })?;
    if packages.is_empty()
        || expected_count != packages.len()
        || expected_bytes != actual_bytes
        || actual_bytes > MAX_CLOSURE_ARCHIVE_BYTES
        || actual_signature_bytes > MAX_CLOSURE_SIGNATURE_BYTES
        || !packages.iter().any(|package| package.name == "base-devel")
    {
        return Err(BuilderError::InvalidManifest(
            "summary mismatch or base-devel missing",
        ));
    }
    Ok(packages)
}

fn required_field<'a>(fields: &mut impl Iterator<Item = &'a str>) -> Result<&'a str, BuilderError> {
    fields
        .next()
        .ok_or(BuilderError::InvalidManifest("missing package field"))
}

fn parse_bounded_size(value: &str, maximum: u64) -> Result<u64, BuilderError> {
    let size = value
        .parse::<u64>()
        .map_err(|_| BuilderError::InvalidManifest("invalid package size"))?;
    if size == 0 || size > maximum {
        return Err(BuilderError::InvalidManifest(
            "package size is zero or exceeds limit",
        ));
    }
    Ok(size)
}

fn parse_sha256(value: &str) -> Result<[u8; 32], BuilderError> {
    if value.len() != 64 {
        return Err(BuilderError::InvalidManifest("invalid SHA-256 length"));
    }
    let mut digest = [0_u8; 32];
    for (index, pair) in value.as_bytes().chunks_exact(2).enumerate() {
        digest[index] = (hex_value(pair[0])? << 4) | hex_value(pair[1])?;
    }
    Ok(digest)
}

fn hex_value(value: u8) -> Result<u8, BuilderError> {
    match value {
        b'0'..=b'9' => Ok(value - b'0'),
        b'a'..=b'f' => Ok(value - b'a' + 10),
        _ => Err(BuilderError::InvalidManifest("invalid SHA-256 encoding")),
    }
}

fn open_or_create_directory(parent: &OwnedFd, name: &str) -> Result<OwnedFd, BuilderError> {
    match mkdirat(parent, name, Mode::from_raw_mode(0o700)) {
        Ok(()) | Err(Errno::EXIST) => open_directory(parent, name),
        Err(error) => Err(error.into()),
    }
}

fn open_directory(parent: &OwnedFd, name: &str) -> Result<OwnedFd, BuilderError> {
    openat(
        parent,
        name,
        OFlags::RDONLY | OFlags::DIRECTORY | OFlags::NOFOLLOW | OFlags::CLOEXEC,
        Mode::empty(),
    )
    .map_err(BuilderError::from)
}

fn remove_entry_if_present(
    parent: &OwnedFd,
    name: &str,
    depth: usize,
    visited: &mut usize,
) -> Result<(), BuilderError> {
    if depth > MAX_DIRECTORY_DEPTH {
        return Err(BuilderError::OutputLimit);
    }
    let metadata = match statat(parent, name, AtFlags::SYMLINK_NOFOLLOW) {
        Ok(metadata) => metadata,
        Err(Errno::NOENT) => return Ok(()),
        Err(error) => return Err(error.into()),
    };
    *visited = visited.checked_add(1).ok_or(BuilderError::OutputLimit)?;
    if *visited > MAX_WORKSPACE_ENTRIES {
        return Err(BuilderError::OutputLimit);
    }
    if FileType::from_raw_mode(metadata.st_mode) != FileType::Directory {
        unlinkat(parent, name, AtFlags::empty())?;
        return Ok(());
    }
    chmodat(parent, name, Mode::from_raw_mode(0o700), AtFlags::empty())?;
    let directory = open_directory(parent, name)?;
    remove_directory_contents(&directory, depth, visited)?;
    unlinkat(parent, name, AtFlags::REMOVEDIR)?;
    Ok(())
}

fn remove_entry_if_present_cstr(
    parent: &OwnedFd,
    name: &CStr,
    depth: usize,
    visited: &mut usize,
) -> Result<(), BuilderError> {
    if depth > MAX_DIRECTORY_DEPTH {
        return Err(BuilderError::OutputLimit);
    }
    let metadata = statat(parent, name, AtFlags::SYMLINK_NOFOLLOW)?;
    *visited = visited.checked_add(1).ok_or(BuilderError::OutputLimit)?;
    if *visited > MAX_WORKSPACE_ENTRIES {
        return Err(BuilderError::OutputLimit);
    }
    if FileType::from_raw_mode(metadata.st_mode) != FileType::Directory {
        unlinkat(parent, name, AtFlags::empty())?;
        return Ok(());
    }
    chmodat(parent, name, Mode::from_raw_mode(0o700), AtFlags::empty())?;
    let directory = openat(
        parent,
        name,
        OFlags::RDONLY | OFlags::DIRECTORY | OFlags::NOFOLLOW | OFlags::CLOEXEC,
        Mode::empty(),
    )?;
    remove_directory_contents(&directory, depth, visited)?;
    unlinkat(parent, name, AtFlags::REMOVEDIR)?;
    Ok(())
}

fn remove_directory_contents(
    directory: &OwnedFd,
    depth: usize,
    visited: &mut usize,
) -> Result<(), BuilderError> {
    loop {
        let mut child = None;
        for entry in Dir::read_from(directory)? {
            let entry = entry?;
            let raw = entry.file_name().to_bytes();
            if raw == b"." || raw == b".." {
                continue;
            }
            if raw.is_empty() || raw.contains(&b'/') || raw.len() > 255 {
                return Err(BuilderError::UnsafeWorkspace);
            }
            child = Some(CString::new(raw).map_err(|_| BuilderError::UnsafeWorkspace)?);
            break;
        }
        let Some(child) = child else {
            return Ok(());
        };
        remove_entry_if_present_cstr(directory, &child, depth + 1, visited)?;
    }
}

fn write_atomic(directory: &OwnedFd, destination: &str, bytes: &[u8]) -> Result<(), BuilderError> {
    let temporary = format!("{destination}.part");
    remove_regular_if_present(directory, &temporary)?;
    let descriptor = openat(
        directory,
        temporary.as_str(),
        OFlags::WRONLY | OFlags::CREATE | OFlags::EXCL | OFlags::NOFOLLOW | OFlags::CLOEXEC,
        Mode::from_raw_mode(0o600),
    )?;
    let mut file = File::from(descriptor);
    file.write_all(bytes)?;
    file.sync_all()?;
    renameat(directory, temporary.as_str(), directory, destination)?;
    fsync(directory)?;
    Ok(())
}

fn remove_regular_if_present(directory: &OwnedFd, name: &str) -> Result<(), BuilderError> {
    match statat(directory, name, AtFlags::SYMLINK_NOFOLLOW) {
        Ok(metadata) if FileType::from_raw_mode(metadata.st_mode) == FileType::RegularFile => {
            unlinkat(directory, name, AtFlags::empty())?;
            Ok(())
        }
        Ok(_) => Err(BuilderError::UnsafeWorkspace),
        Err(Errno::NOENT) => Ok(()),
        Err(error) => Err(error.into()),
    }
}

fn staged_name(index: usize, filename: &str, signature: bool) -> String {
    format!(
        "{index:03}-{filename}{}",
        if signature { ".sig" } else { "" }
    )
}

fn publish_descriptor(
    directory: &OwnedFd,
    destination: &str,
    source: &mut File,
    expected_bytes: u64,
    expected_sha256: [u8; 32],
) -> Result<(), BuilderError> {
    if verify_staged_file(directory, destination, expected_bytes, expected_sha256).is_ok() {
        return Ok(());
    }
    match statat(directory, destination, AtFlags::SYMLINK_NOFOLLOW) {
        Ok(metadata) if FileType::from_raw_mode(metadata.st_mode) == FileType::RegularFile => {
            unlinkat(directory, destination, AtFlags::empty())?;
        }
        Ok(_) => return Err(BuilderError::UnsafeWorkspace),
        Err(Errno::NOENT) => {}
        Err(error) => return Err(error.into()),
    }
    let temporary = format!("{destination}.part");
    remove_regular_if_present(directory, &temporary)?;
    let descriptor = openat(
        directory,
        temporary.as_str(),
        OFlags::WRONLY | OFlags::CREATE | OFlags::EXCL | OFlags::NOFOLLOW | OFlags::CLOEXEC,
        Mode::from_raw_mode(0o600),
    )?;
    let mut output = File::from(descriptor);
    let copy_result = (|| {
        source.seek(SeekFrom::Start(0))?;
        let source_metadata = source.metadata()?;
        if !source_metadata.is_file() || source_metadata.len() != expected_bytes {
            return Err(BuilderError::InvalidInput);
        }
        let mut digest = Sha256::new();
        let mut buffer = [0_u8; 64 * 1024];
        let mut total = 0_u64;
        loop {
            let count = source.read(&mut buffer)?;
            if count == 0 {
                break;
            }
            total = total
                .checked_add(count as u64)
                .ok_or(BuilderError::OutputLimit)?;
            if total > expected_bytes {
                return Err(BuilderError::InvalidInput);
            }
            digest.update(&buffer[..count]);
            output.write_all(&buffer[..count])?;
        }
        if total != expected_bytes || <[u8; 32]>::from(digest.finalize()) != expected_sha256 {
            return Err(BuilderError::InvalidInput);
        }
        output.sync_all()?;
        Ok(())
    })();
    drop(output);
    if let Err(error) = copy_result {
        let _ = remove_regular_if_present(directory, &temporary);
        return Err(error);
    }
    renameat(directory, temporary.as_str(), directory, destination)?;
    Ok(())
}

fn verify_staged_file(
    directory: &OwnedFd,
    name: &str,
    expected_bytes: u64,
    expected_sha256: [u8; 32],
) -> Result<(), BuilderError> {
    let descriptor = openat(
        directory,
        name,
        OFlags::RDONLY | OFlags::NOFOLLOW | OFlags::CLOEXEC,
        Mode::empty(),
    )?;
    let mut file = File::from(descriptor);
    let metadata = file.metadata()?;
    if !metadata.is_file() || metadata.len() != expected_bytes {
        return Err(BuilderError::InvalidInput);
    }
    let mut digest = Sha256::new();
    let mut buffer = [0_u8; 64 * 1024];
    let mut total = 0_u64;
    loop {
        let count = file.read(&mut buffer)?;
        if count == 0 {
            break;
        }
        total = total
            .checked_add(count as u64)
            .ok_or(BuilderError::OutputLimit)?;
        if total > expected_bytes {
            return Err(BuilderError::InvalidInput);
        }
        digest.update(&buffer[..count]);
    }
    if total != expected_bytes || <[u8; 32]>::from(digest.finalize()) != expected_sha256 {
        return Err(BuilderError::InvalidInput);
    }
    Ok(())
}

fn safe_name(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 128
        && value.bytes().all(|byte| {
            byte.is_ascii_alphanumeric() || matches!(byte, b'@' | b'+' | b'.' | b'_' | b'-')
        })
}

fn safe_filename(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 240
        && (value.ends_with(".pkg.tar.zst") || value.ends_with(".pkg.tar.xz"))
        && value.bytes().all(|byte| {
            byte.is_ascii_alphanumeric() || matches!(byte, b'@' | b'+' | b':' | b'.' | b'_' | b'-')
        })
}

fn safe_reviewed_filename(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 240
        && value.bytes().all(|byte| {
            byte.is_ascii_alphanumeric() || matches!(byte, b'@' | b'+' | b',' | b'.' | b'_' | b'-')
        })
}

fn sha256_bytes(bytes: &[u8]) -> [u8; 32] {
    Sha256::digest(bytes).into()
}

fn hex_sha256(value: &[u8; 32]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut output = [0_u8; 64];
    for (index, byte) in value.iter().copied().enumerate() {
        output[index * 2] = HEX[usize::from(byte >> 4)];
        output[index * 2 + 1] = HEX[usize::from(byte & 0x0f)];
    }
    String::from_utf8(output.to_vec()).expect("hex is UTF-8")
}

#[cfg(target_os = "android")]
mod android {
    #![allow(unsafe_code)]

    use std::fs::File;
    use std::os::fd::BorrowedFd;
    use std::path::Path;
    use std::slice;
    use std::sync::{Mutex, OnceLock};

    use jni::JNIEnv;
    use jni::objects::{JByteBuffer, JClass, JString};
    use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean, jint};

    use super::{
        AurBuildSession, BuilderRuntime, ClosureSession, ExtractionReport, ProvisionSession,
        ReviewedInputRole, ReviewedInputSession, parse_sha256, prepare_recipe_workspace,
    };

    const ERROR_INVALID_ARGUMENT: jint = -1;
    const ERROR_INVALID_STATE: jint = -2;
    const ERROR_BUILDER: jint = -3;
    const ERROR_OUTPUT_BYTES: usize = 512;
    const CLOSURE_REPORT_BYTES: usize = 64;
    const CLOSURE_REPORT_MAGIC: &[u8; 8] = b"ABCR0001";
    const EXTRACTION_REPORT_BYTES: usize = 32;
    const EXTRACTION_REPORT_MAGIC: &[u8; 8] = b"ABPE0001";
    const REVIEWED_INPUT_REPORT_BYTES: usize = 56;
    const REVIEWED_INPUT_REPORT_MAGIC: &[u8; 8] = b"ABIR0001";
    const RECIPE_WORKSPACE_REPORT_BYTES: usize = 32;
    const RECIPE_WORKSPACE_REPORT_MAGIC: &[u8; 8] = b"ABRW0001";
    const BUILD_POLL_HEADER_BYTES: usize = 16;
    const BUILD_POLL_LOG_BYTES: usize = 64 * 1024;
    const BUILD_POLL_OUTPUT_BYTES: usize = BUILD_POLL_HEADER_BYTES + BUILD_POLL_LOG_BYTES;
    const BUILD_POLL_MAGIC: &[u8; 8] = b"ABBP0001";
    const BUILT_PACKAGE_REPORT_BYTES: usize = 304;
    const BUILT_PACKAGE_REPORT_MAGIC: &[u8; 8] = b"ABOP0001";
    const RUNTIME_OUTPUT_BYTES: usize = 16 * 1024;

    static REVIEWED_INPUTS: OnceLock<Mutex<Option<ReviewedInputSession>>> = OnceLock::new();
    static BUILD: OnceLock<Mutex<Option<AurBuildSession>>> = OnceLock::new();
    static SESSION: OnceLock<Mutex<Option<ClosureSession>>> = OnceLock::new();
    static PROVISION: OnceLock<Mutex<Option<ProvisionSession>>> = OnceLock::new();

    fn session() -> &'static Mutex<Option<ClosureSession>> {
        SESSION.get_or_init(|| Mutex::new(None))
    }

    fn reviewed_inputs() -> &'static Mutex<Option<ReviewedInputSession>> {
        REVIEWED_INPUTS.get_or_init(|| Mutex::new(None))
    }

    fn build() -> &'static Mutex<Option<AurBuildSession>> {
        BUILD.get_or_init(|| Mutex::new(None))
    }

    fn provision() -> &'static Mutex<Option<ProvisionSession>> {
        PROVISION.get_or_init(|| Mutex::new(None))
    }

    fn java_string(environment: &mut JNIEnv, value: &JString) -> Result<String, jint> {
        environment
            .get_string(value)
            .map(Into::into)
            .map_err(|_| ERROR_INVALID_ARGUMENT)
    }

    fn duplicate_file(raw_descriptor: jint) -> Result<File, jint> {
        if raw_descriptor < 0 {
            return Err(ERROR_INVALID_ARGUMENT);
        }
        // SAFETY: The descriptor is borrowed only for the duration of dup. Binder/Kotlin
        // retains ownership of the original descriptor and closes it after this call.
        let borrowed = unsafe { BorrowedFd::borrow_raw(raw_descriptor) };
        rustix::io::dup(borrowed)
            .map(File::from)
            .map_err(|_| ERROR_INVALID_ARGUMENT)
    }

    fn copy_builder_error(error: &impl std::fmt::Display, output: &mut [u8]) -> jint {
        output.fill(0);
        let message = error.to_string();
        let length = message.len().min(output.len().saturating_sub(1));
        output[..length].copy_from_slice(&message.as_bytes()[..length]);
        ERROR_BUILDER
    }

    fn write_extraction_report(output: &mut [u8], report: ExtractionReport) -> Result<jint, jint> {
        if output.len() < EXTRACTION_REPORT_BYTES {
            return Err(ERROR_INVALID_ARGUMENT);
        }
        let package_count = u32::try_from(report.package_count).map_err(|_| ERROR_BUILDER)?;
        output[..EXTRACTION_REPORT_BYTES].fill(0);
        output[..8].copy_from_slice(EXTRACTION_REPORT_MAGIC);
        output[8..12].copy_from_slice(&package_count.to_le_bytes());
        output[16..24].copy_from_slice(&report.entry_count.to_le_bytes());
        output[24..32].copy_from_slice(&report.expanded_bytes.to_le_bytes());
        Ok(EXTRACTION_REPORT_BYTES as jint)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_builder_NativeBuilder_nativeBeginReviewedInputs(
        mut environment: JNIEnv,
        _class: JClass,
        files_directory: JString,
        package_base: JString,
        version: JString,
        expected_inputs: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(expected_inputs) = usize::try_from(expected_inputs) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let (Ok(files_directory), Ok(package_base), Ok(version)) = (
            java_string(&mut environment, &files_directory),
            java_string(&mut environment, &package_base),
            java_string(&mut environment, &version),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let (Ok(capacity), Ok(address)) = (
            environment.get_direct_buffer_capacity(&output_buffer),
            environment.get_direct_buffer_address(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if capacity < ERROR_OUTPUT_BYTES || address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        // SAFETY: JNI verified the fixed direct diagnostic buffer.
        let output = unsafe { slice::from_raw_parts_mut(address, capacity) };
        let Ok(mut slot) = reviewed_inputs().lock() else {
            return ERROR_INVALID_STATE;
        };
        *slot = None;
        match ReviewedInputSession::begin(
            Path::new(&files_directory),
            &package_base,
            &version,
            expected_inputs,
        ) {
            Ok(value) => {
                *slot = Some(value);
                0
            }
            Err(error) => copy_builder_error(&error, output),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_builder_NativeBuilder_nativeStageReviewedInput(
        mut environment: JNIEnv,
        _class: JClass,
        role: jint,
        filename: JString,
        expected_bytes: jni::sys::jlong,
        expected_sha256: JString,
        descriptor: jint,
    ) -> jint {
        let role = match role {
            0 => ReviewedInputRole::Snapshot,
            1 => ReviewedInputRole::Source,
            _ => return ERROR_INVALID_ARGUMENT,
        };
        let Ok(expected_bytes) = u64::try_from(expected_bytes) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let (Ok(filename), Ok(expected_sha256)) = (
            java_string(&mut environment, &filename),
            java_string(&mut environment, &expected_sha256),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(expected_sha256) = parse_sha256(&expected_sha256) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(mut source) = duplicate_file(descriptor) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(mut slot) = reviewed_inputs().lock() else {
            return ERROR_INVALID_STATE;
        };
        let Some(value) = slot.as_mut() else {
            return ERROR_INVALID_STATE;
        };
        match value.stage(
            role,
            &filename,
            expected_bytes,
            expected_sha256,
            &mut source,
        ) {
            Ok(()) => 0,
            Err(_) => ERROR_BUILDER,
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_builder_NativeBuilder_nativeFinishReviewedInputs(
        environment: JNIEnv,
        _class: JClass,
        output_buffer: JByteBuffer,
    ) -> jint {
        let (Ok(capacity), Ok(address)) = (
            environment.get_direct_buffer_capacity(&output_buffer),
            environment.get_direct_buffer_address(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if capacity < REVIEWED_INPUT_REPORT_BYTES || address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(mut slot) = reviewed_inputs().lock() else {
            return ERROR_INVALID_STATE;
        };
        let Some(value) = slot.take() else {
            return ERROR_INVALID_STATE;
        };
        let Ok(report) = value.finish() else {
            return ERROR_BUILDER;
        };
        let Ok(input_count) = u32::try_from(report.input_count) else {
            return ERROR_BUILDER;
        };
        // SAFETY: JNI verified this direct buffer has at least the fixed report size.
        let output = unsafe { slice::from_raw_parts_mut(address, REVIEWED_INPUT_REPORT_BYTES) };
        output.fill(0);
        output[..8].copy_from_slice(REVIEWED_INPUT_REPORT_MAGIC);
        output[8..12].copy_from_slice(&input_count.to_le_bytes());
        output[16..24].copy_from_slice(&report.input_bytes.to_le_bytes());
        output[24..56].copy_from_slice(&report.manifest_sha256);
        REVIEWED_INPUT_REPORT_BYTES as jint
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_builder_NativeBuilder_nativeAbortReviewedInputs(
        _environment: JNIEnv,
        _class: JClass,
    ) -> jboolean {
        match reviewed_inputs().lock() {
            Ok(mut slot) => {
                let existed = slot.take().is_some();
                if existed { JNI_TRUE } else { JNI_FALSE }
            }
            Err(_) => JNI_FALSE,
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_builder_NativeBuilder_nativeBeginPackageClosure(
        mut environment: JNIEnv,
        _class: JClass,
        files_directory: JString,
        package_base: JString,
        version: JString,
        manifest_buffer: JByteBuffer,
        manifest_length: jint,
        manifest_sha256: JString,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(manifest_length) = usize::try_from(manifest_length) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let (Ok(files_directory), Ok(package_base), Ok(version), Ok(manifest_sha256)) = (
            java_string(&mut environment, &files_directory),
            java_string(&mut environment, &package_base),
            java_string(&mut environment, &version),
            java_string(&mut environment, &manifest_sha256),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(expected_sha256) = parse_sha256(&manifest_sha256) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let (Ok(capacity), Ok(address), Ok(output_capacity), Ok(output_address)) = (
            environment.get_direct_buffer_capacity(&manifest_buffer),
            environment.get_direct_buffer_address(&manifest_buffer),
            environment.get_direct_buffer_capacity(&output_buffer),
            environment.get_direct_buffer_address(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if manifest_length == 0
            || manifest_length > capacity
            || address.is_null()
            || output_capacity < ERROR_OUTPUT_BYTES
            || output_address.is_null()
        {
            return ERROR_INVALID_ARGUMENT;
        }
        // SAFETY: JNI verified that this is a direct buffer and the requested slice
        // remains within its capacity for the duration of this synchronous call.
        let manifest = unsafe { slice::from_raw_parts(address.cast_const(), manifest_length) };
        // SAFETY: JNI verified this separate direct buffer has the fixed diagnostic capacity.
        let output = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        let Ok(mut slot) = session().lock() else {
            return ERROR_INVALID_STATE;
        };
        *slot = None;
        match ClosureSession::begin(
            Path::new(&files_directory),
            &package_base,
            &version,
            manifest,
            expected_sha256,
        ) {
            Ok(value) => {
                let count = value.package_count();
                *slot = Some(value);
                i32::try_from(count).unwrap_or(ERROR_BUILDER)
            }
            Err(error) => copy_builder_error(&error, output),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_builder_NativeBuilder_nativeStagePackage(
        _environment: JNIEnv,
        _class: JClass,
        package_index: jint,
        archive_descriptor: jint,
        signature_descriptor: jint,
    ) -> jint {
        let Ok(package_index) = usize::try_from(package_index) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let (Ok(mut archive), Ok(mut signature)) = (
            duplicate_file(archive_descriptor),
            duplicate_file(signature_descriptor),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(slot) = session().lock() else {
            return ERROR_INVALID_STATE;
        };
        let Some(value) = slot.as_ref() else {
            return ERROR_INVALID_STATE;
        };
        match value.stage_package(package_index, &mut archive, &mut signature) {
            Ok(()) => 0,
            Err(_) => ERROR_BUILDER,
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_builder_NativeBuilder_nativeFinishPackageClosure(
        environment: JNIEnv,
        _class: JClass,
        output_buffer: JByteBuffer,
    ) -> jint {
        let (Ok(capacity), Ok(address)) = (
            environment.get_direct_buffer_capacity(&output_buffer),
            environment.get_direct_buffer_address(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if capacity < CLOSURE_REPORT_BYTES || address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(mut slot) = session().lock() else {
            return ERROR_INVALID_STATE;
        };
        let Some(value) = slot.as_ref() else {
            return ERROR_INVALID_STATE;
        };
        let Ok(report) = value.finish() else {
            return ERROR_BUILDER;
        };
        // SAFETY: JNI verified this direct buffer has at least the fixed report size.
        let output = unsafe { slice::from_raw_parts_mut(address, CLOSURE_REPORT_BYTES) };
        output.fill(0);
        output[..8].copy_from_slice(CLOSURE_REPORT_MAGIC);
        let Ok(package_count) = u32::try_from(report.package_count) else {
            return ERROR_BUILDER;
        };
        output[8..12].copy_from_slice(&package_count.to_le_bytes());
        output[16..24].copy_from_slice(&report.archive_bytes.to_le_bytes());
        output[24..32].copy_from_slice(&report.signature_bytes.to_le_bytes());
        output[32..64].copy_from_slice(&report.manifest_sha256);
        *slot = None;
        CLOSURE_REPORT_BYTES as jint
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_builder_NativeBuilder_nativeAbortPackageClosure(
        _environment: JNIEnv,
        _class: JClass,
    ) -> jboolean {
        match session().lock() {
            Ok(mut slot) => {
                let existed = slot.take().is_some();
                if existed { JNI_TRUE } else { JNI_FALSE }
            }
            Err(_) => JNI_FALSE,
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_builder_NativeBuilder_nativeBeginProvision(
        mut environment: JNIEnv,
        _class: JClass,
        files_directory: JString,
        package_base: JString,
        version: JString,
        manifest_sha256: JString,
        output_buffer: JByteBuffer,
    ) -> jint {
        let (Ok(files_directory), Ok(package_base), Ok(version), Ok(manifest_sha256)) = (
            java_string(&mut environment, &files_directory),
            java_string(&mut environment, &package_base),
            java_string(&mut environment, &version),
            java_string(&mut environment, &manifest_sha256),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(expected_sha256) = parse_sha256(&manifest_sha256) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let (Ok(capacity), Ok(address)) = (
            environment.get_direct_buffer_capacity(&output_buffer),
            environment.get_direct_buffer_address(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if capacity < ERROR_OUTPUT_BYTES || address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        // SAFETY: JNI verified the fixed direct diagnostic/report buffer.
        let output = unsafe { slice::from_raw_parts_mut(address, capacity) };
        let Ok(mut slot) = provision().lock() else {
            return ERROR_INVALID_STATE;
        };
        *slot = None;
        match ProvisionSession::begin(
            Path::new(&files_directory),
            &package_base,
            &version,
            expected_sha256,
        ) {
            Ok(value) => {
                let report = value.expected();
                *slot = Some(value);
                write_extraction_report(output, report).unwrap_or(ERROR_BUILDER)
            }
            Err(error) => copy_builder_error(&error, output),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_builder_NativeBuilder_nativeExtractProvisionBatch(
        environment: JNIEnv,
        _class: JClass,
        maximum_packages: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(maximum_packages) = usize::try_from(maximum_packages) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let (Ok(capacity), Ok(address)) = (
            environment.get_direct_buffer_capacity(&output_buffer),
            environment.get_direct_buffer_address(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if capacity < ERROR_OUTPUT_BYTES || address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        // SAFETY: JNI verified the fixed direct diagnostic/report buffer.
        let output = unsafe { slice::from_raw_parts_mut(address, capacity) };
        let Ok(mut slot) = provision().lock() else {
            return ERROR_INVALID_STATE;
        };
        let Some(value) = slot.as_mut() else {
            return ERROR_INVALID_STATE;
        };
        match value.extract_next(maximum_packages) {
            Ok(report) => write_extraction_report(output, report).unwrap_or(ERROR_BUILDER),
            Err(error) => copy_builder_error(&error, output),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_builder_NativeBuilder_nativeFinishProvision(
        environment: JNIEnv,
        _class: JClass,
        output_buffer: JByteBuffer,
    ) -> jint {
        let (Ok(capacity), Ok(address)) = (
            environment.get_direct_buffer_capacity(&output_buffer),
            environment.get_direct_buffer_address(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if capacity < ERROR_OUTPUT_BYTES || address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        // SAFETY: JNI verified the fixed direct diagnostic/report buffer.
        let output = unsafe { slice::from_raw_parts_mut(address, capacity) };
        let Ok(mut slot) = provision().lock() else {
            return ERROR_INVALID_STATE;
        };
        let Some(value) = slot.take() else {
            return ERROR_INVALID_STATE;
        };
        match value.finish() {
            Ok(report) => write_extraction_report(output, report).unwrap_or(ERROR_BUILDER),
            Err(error) => copy_builder_error(&error, output),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_builder_NativeBuilder_nativeAbortProvision(
        _environment: JNIEnv,
        _class: JClass,
    ) -> jboolean {
        match provision().lock() {
            Ok(mut slot) => {
                let existed = slot.take().is_some();
                if existed { JNI_TRUE } else { JNI_FALSE }
            }
            Err(_) => JNI_FALSE,
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_builder_NativeBuilder_nativeProbeRuntime(
        mut environment: JNIEnv,
        _class: JClass,
        files_directory: JString,
        native_directory: JString,
        manifest_buffer: JByteBuffer,
        manifest_length: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(manifest_length) = usize::try_from(manifest_length) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let (Ok(files_directory), Ok(native_directory)) = (
            java_string(&mut environment, &files_directory),
            java_string(&mut environment, &native_directory),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let (Ok(manifest_capacity), Ok(manifest_address), Ok(output_capacity), Ok(output_address)) = (
            environment.get_direct_buffer_capacity(&manifest_buffer),
            environment.get_direct_buffer_address(&manifest_buffer),
            environment.get_direct_buffer_capacity(&output_buffer),
            environment.get_direct_buffer_address(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if manifest_length == 0
            || manifest_length > manifest_capacity
            || manifest_address.is_null()
            || output_capacity < RUNTIME_OUTPUT_BYTES
            || output_address.is_null()
        {
            return ERROR_INVALID_ARGUMENT;
        }
        // SAFETY: JNI verified the direct input and output buffer bounds for this call.
        let manifest =
            unsafe { slice::from_raw_parts(manifest_address.cast_const(), manifest_length) };
        // SAFETY: JNI verified the direct output buffer capacity.
        let output = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        output.fill(0);
        let result = (|| {
            let runtime = BuilderRuntime::prepare(
                Path::new(&files_directory),
                Path::new(&native_directory),
                manifest,
            )?;
            runtime.probe_makepkg()
        })();
        match result {
            Ok(bytes) if !bytes.is_empty() && bytes.len() <= RUNTIME_OUTPUT_BYTES => {
                output[..bytes.len()].copy_from_slice(&bytes);
                i32::try_from(bytes.len()).unwrap_or(ERROR_BUILDER)
            }
            Ok(_) => ERROR_BUILDER,
            Err(error) => copy_builder_error(&error, output),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_builder_NativeBuilder_nativePrepareRecipeWorkspace(
        mut environment: JNIEnv,
        _class: JClass,
        files_directory: JString,
        package_base: JString,
        version: JString,
        input_manifest_sha256: JString,
        closure_sha256: JString,
        output_buffer: JByteBuffer,
    ) -> jint {
        let (
            Ok(files_directory),
            Ok(package_base),
            Ok(version),
            Ok(input_manifest_sha256),
            Ok(closure_sha256),
        ) = (
            java_string(&mut environment, &files_directory),
            java_string(&mut environment, &package_base),
            java_string(&mut environment, &version),
            java_string(&mut environment, &input_manifest_sha256),
            java_string(&mut environment, &closure_sha256),
        )
        else {
            return ERROR_INVALID_ARGUMENT;
        };
        let (Ok(input_manifest_sha256), Ok(closure_sha256)) = (
            parse_sha256(&input_manifest_sha256),
            parse_sha256(&closure_sha256),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let (Ok(capacity), Ok(address)) = (
            environment.get_direct_buffer_capacity(&output_buffer),
            environment.get_direct_buffer_address(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if capacity < ERROR_OUTPUT_BYTES || address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        // SAFETY: JNI verified the fixed direct diagnostic/report buffer.
        let output = unsafe { slice::from_raw_parts_mut(address, capacity) };
        match prepare_recipe_workspace(
            Path::new(&files_directory),
            &package_base,
            &version,
            input_manifest_sha256,
            closure_sha256,
        ) {
            Ok(report) => {
                output[..RECIPE_WORKSPACE_REPORT_BYTES].fill(0);
                output[..8].copy_from_slice(RECIPE_WORKSPACE_REPORT_MAGIC);
                output[8..16].copy_from_slice(&report.recipe_entries.to_le_bytes());
                output[16..24].copy_from_slice(&report.recipe_bytes.to_le_bytes());
                output[24..32].copy_from_slice(&report.source_bytes.to_le_bytes());
                RECIPE_WORKSPACE_REPORT_BYTES as jint
            }
            Err(error) => copy_builder_error(&error, output),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_builder_NativeBuilder_nativeStartBuild(
        mut environment: JNIEnv,
        _class: JClass,
        files_directory: JString,
        native_directory: JString,
        runtime_manifest_buffer: JByteBuffer,
        runtime_manifest_length: jint,
        package_base: JString,
        version: JString,
        input_manifest_sha256: JString,
        closure_sha256: JString,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(runtime_manifest_length) = usize::try_from(runtime_manifest_length) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let (
            Ok(files_directory),
            Ok(native_directory),
            Ok(package_base),
            Ok(version),
            Ok(input_manifest_sha256),
            Ok(closure_sha256),
        ) = (
            java_string(&mut environment, &files_directory),
            java_string(&mut environment, &native_directory),
            java_string(&mut environment, &package_base),
            java_string(&mut environment, &version),
            java_string(&mut environment, &input_manifest_sha256),
            java_string(&mut environment, &closure_sha256),
        )
        else {
            return ERROR_INVALID_ARGUMENT;
        };
        let (Ok(input_manifest_sha256), Ok(closure_sha256)) = (
            parse_sha256(&input_manifest_sha256),
            parse_sha256(&closure_sha256),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let (Ok(manifest_capacity), Ok(manifest_address), Ok(output_capacity), Ok(output_address)) = (
            environment.get_direct_buffer_capacity(&runtime_manifest_buffer),
            environment.get_direct_buffer_address(&runtime_manifest_buffer),
            environment.get_direct_buffer_capacity(&output_buffer),
            environment.get_direct_buffer_address(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if runtime_manifest_length == 0
            || runtime_manifest_length > manifest_capacity
            || manifest_address.is_null()
            || output_capacity < ERROR_OUTPUT_BYTES
            || output_address.is_null()
        {
            return ERROR_INVALID_ARGUMENT;
        }
        // SAFETY: JNI verified the direct runtime-manifest bounds.
        let runtime_manifest = unsafe {
            slice::from_raw_parts(manifest_address.cast_const(), runtime_manifest_length)
        };
        // SAFETY: JNI verified the fixed direct diagnostic buffer.
        let output = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        let Ok(mut slot) = build().lock() else {
            return ERROR_INVALID_STATE;
        };
        if slot.is_some() {
            return ERROR_INVALID_STATE;
        }
        match AurBuildSession::start(
            Path::new(&files_directory),
            Path::new(&native_directory),
            runtime_manifest,
            &package_base,
            &version,
            input_manifest_sha256,
            closure_sha256,
        ) {
            Ok(value) => {
                *slot = Some(value);
                0
            }
            Err(error) => copy_builder_error(&error, output),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_builder_NativeBuilder_nativePollBuild(
        environment: JNIEnv,
        _class: JClass,
        output_buffer: JByteBuffer,
    ) -> jint {
        let (Ok(capacity), Ok(address)) = (
            environment.get_direct_buffer_capacity(&output_buffer),
            environment.get_direct_buffer_address(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if capacity < BUILD_POLL_OUTPUT_BYTES || address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(mut slot) = build().lock() else {
            return ERROR_INVALID_STATE;
        };
        let Some(session) = slot.as_mut() else {
            return ERROR_INVALID_STATE;
        };
        let report = match session.poll(BUILD_POLL_LOG_BYTES) {
            Ok(report) => report,
            Err(error) => {
                // SAFETY: JNI verified the fixed direct output buffer.
                let output = unsafe { slice::from_raw_parts_mut(address, capacity) };
                return copy_builder_error(&error, output);
            }
        };
        let finished = report.exit_status.is_some();
        let Ok(log_length) = u32::try_from(report.logs.len()) else {
            return ERROR_BUILDER;
        };
        // SAFETY: JNI verified the complete fixed report/log capacity.
        let output = unsafe { slice::from_raw_parts_mut(address, BUILD_POLL_OUTPUT_BYTES) };
        output.fill(0);
        output[..8].copy_from_slice(BUILD_POLL_MAGIC);
        output[8..12].copy_from_slice(&report.exit_status.unwrap_or(-1).to_le_bytes());
        output[12..16].copy_from_slice(&log_length.to_le_bytes());
        output[16..16 + report.logs.len()].copy_from_slice(&report.logs);
        let result = BUILD_POLL_HEADER_BYTES
            .checked_add(report.logs.len())
            .and_then(|value| i32::try_from(value).ok())
            .unwrap_or(ERROR_BUILDER);
        if finished {
            slot.take();
        }
        result
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_builder_NativeBuilder_nativeCancelBuild(
        _environment: JNIEnv,
        _class: JClass,
    ) -> jboolean {
        match build().lock() {
            Ok(mut slot) => {
                let Some(mut session) = slot.take() else {
                    return JNI_FALSE;
                };
                session.cancel();
                JNI_TRUE
            }
            Err(_) => JNI_FALSE,
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_builder_NativeBuilder_nativeVerifyAndCopyBuiltPackage(
        mut environment: JNIEnv,
        _class: JClass,
        files_directory: JString,
        package_base: JString,
        package_name: JString,
        version: JString,
        architecture: JString,
        closure_sha256: JString,
        output_descriptor: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        let (
            Ok(files_directory),
            Ok(package_base),
            Ok(package_name),
            Ok(version),
            Ok(architecture),
            Ok(closure_sha256),
        ) = (
            java_string(&mut environment, &files_directory),
            java_string(&mut environment, &package_base),
            java_string(&mut environment, &package_name),
            java_string(&mut environment, &version),
            java_string(&mut environment, &architecture),
            java_string(&mut environment, &closure_sha256),
        )
        else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(closure_sha256) = parse_sha256(&closure_sha256) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let (Ok(capacity), Ok(address), Ok(mut output_file)) = (
            environment.get_direct_buffer_capacity(&output_buffer),
            environment.get_direct_buffer_address(&output_buffer),
            duplicate_file(output_descriptor),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if capacity < BUILT_PACKAGE_REPORT_BYTES || address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        // SAFETY: JNI verified the direct diagnostic/report buffer capacity.
        let output = unsafe { slice::from_raw_parts_mut(address, capacity) };
        output.fill(0);
        match super::verify_and_copy_built_package(
            Path::new(&files_directory),
            &package_base,
            &package_name,
            &version,
            &architecture,
            closure_sha256,
            &mut output_file,
        ) {
            Ok(report) => {
                let Ok(build_package_count) = u32::try_from(report.build_package_count) else {
                    return ERROR_BUILDER;
                };
                let filename = report.filename.as_bytes();
                let Ok(filename_length) = u32::try_from(filename.len()) else {
                    return ERROR_BUILDER;
                };
                if filename.is_empty() || filename.len() > BUILT_PACKAGE_REPORT_BYTES - 64 {
                    return ERROR_BUILDER;
                }
                output[..8].copy_from_slice(BUILT_PACKAGE_REPORT_MAGIC);
                output[8..16].copy_from_slice(&report.archive_bytes.to_le_bytes());
                output[16..24].copy_from_slice(&report.installed_bytes.to_le_bytes());
                output[24..28].copy_from_slice(&build_package_count.to_le_bytes());
                output[28..32].copy_from_slice(&filename_length.to_le_bytes());
                output[32..64].copy_from_slice(&report.sha256);
                output[64..64 + filename.len()].copy_from_slice(filename);
                i32::try_from(64 + filename.len()).unwrap_or(ERROR_BUILDER)
            }
            Err(error) => copy_builder_error(&error, output),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use flate2::Compression;
    use flate2::write::GzEncoder;
    use std::fs::{self, OpenOptions};
    use std::io::Cursor;
    use std::sync::atomic::{AtomicU64, Ordering};
    use tar::Header;
    use xz2::write::XzEncoder;

    static TEST_ID: AtomicU64 = AtomicU64::new(1);

    fn test_directory() -> std::path::PathBuf {
        let path = std::env::temp_dir().join(format!(
            "archphene-builder-test-{}-{}",
            std::process::id(),
            TEST_ID.fetch_add(1, Ordering::Relaxed),
        ));
        fs::create_dir(&path).expect("test directory");
        path
    }

    fn fixture_manifest_with_filename(filename: &str, archive: &[u8], signature: &[u8]) -> Vec<u8> {
        format!(
            "ABPC0001\n\
core\tbase-devel\t1-1\t{filename}\thttps://example.test/{filename}\t{}\t{}\t{}\t{}\n\
summary\t1\t{}\n",
            archive.len(),
            hex_sha256(&sha256_bytes(archive)),
            signature.len(),
            hex_sha256(&sha256_bytes(signature)),
            archive.len(),
        )
        .into_bytes()
    }

    fn fixture_manifest(archive: &[u8], signature: &[u8]) -> Vec<u8> {
        fixture_manifest_with_filename("base-devel-1-1-any.pkg.tar.zst", archive, signature)
    }

    fn append_file(
        builder: &mut tar::Builder<XzEncoder<Vec<u8>>>,
        path: &str,
        mode: u32,
        contents: &[u8],
    ) {
        let mut header = Header::new_gnu();
        header.set_entry_type(EntryType::Regular);
        header.set_mode(mode);
        header.set_size(contents.len() as u64);
        header.set_cksum();
        builder
            .append_data(&mut header, path, Cursor::new(contents))
            .expect("append package file");
    }

    fn append_symlink(builder: &mut tar::Builder<XzEncoder<Vec<u8>>>, path: &str, target: &Path) {
        let mut header = Header::new_gnu();
        header.set_entry_type(EntryType::Symlink);
        header.set_mode(0o777);
        header.set_size(0);
        header.set_link_name(target).expect("symlink target");
        header.set_cksum();
        builder
            .append_data(&mut header, path, Cursor::new([]))
            .expect("append package symlink");
    }

    fn append_hard_link(builder: &mut tar::Builder<XzEncoder<Vec<u8>>>, path: &str, target: &Path) {
        let mut header = Header::new_gnu();
        header.set_entry_type(EntryType::Link);
        header.set_mode(0o644);
        header.set_size(0);
        header.set_link_name(target).expect("hard-link target");
        header.set_cksum();
        builder
            .append_data(&mut header, path, Cursor::new([]))
            .expect("append package hard link");
    }

    fn package_archive(add_entries: impl FnOnce(&mut tar::Builder<XzEncoder<Vec<u8>>>)) -> Vec<u8> {
        let encoder = XzEncoder::new(Vec::new(), 6);
        let mut builder = tar::Builder::new(encoder);
        builder.mode(tar::HeaderMode::Deterministic);
        append_file(
            &mut builder,
            ".PKGINFO",
            0o644,
            b"pkgname = base-devel\npkgver = 1-1\narch = any\n",
        );
        add_entries(&mut builder);
        let encoder = builder.into_inner().expect("finish tar archive");
        encoder.finish().expect("finish xz archive")
    }

    fn stage_fixture_closure(directory: &Path, archive: &[u8], signature: &[u8]) -> [u8; 32] {
        let filename = "base-devel-1-1-any.pkg.tar.xz";
        let manifest = fixture_manifest_with_filename(filename, archive, signature);
        let digest = sha256_bytes(&manifest);
        let session = ClosureSession::begin(directory, "fixture", "1-1", &manifest, digest)
            .expect("closure session");
        let archive_path = directory.join("archive");
        let signature_path = directory.join("signature");
        fs::write(&archive_path, archive).expect("archive fixture");
        fs::write(&signature_path, signature).expect("signature fixture");
        session
            .stage_package(
                0,
                &mut File::open(archive_path).expect("archive"),
                &mut File::open(signature_path).expect("signature"),
            )
            .expect("stage package");
        session.finish().expect("publish closure");
        digest
    }

    fn built_package_archive(installed: &str) -> Vec<u8> {
        let encoder = XzEncoder::new(Vec::new(), 6);
        let mut builder = tar::Builder::new(encoder);
        builder.mode(tar::HeaderMode::Deterministic);
        append_file(
            &mut builder,
            ".BUILDINFO",
            0o644,
            format!(
                "format = 2\n\
                 pkgname = example-bin\n\
                 pkgbase = example-bin\n\
                 pkgver = 1.2.3-1\n\
                 pkgarch = aarch64\n\
                 installed = {installed}\n",
            )
            .as_bytes(),
        );
        append_file(
            &mut builder,
            ".PKGINFO",
            0o644,
            b"pkgname = example-bin\n\
              pkgbase = example-bin\n\
              pkgver = 1.2.3-1\n\
              size = 7\n\
              arch = aarch64\n",
        );
        append_file(&mut builder, "usr/bin/example", 0o755, b"example");
        let encoder = builder.into_inner().expect("finish tar archive");
        encoder.finish().expect("finish xz archive")
    }

    fn built_output_fixture(directory: &Path, archive: &[u8]) -> [u8; 32] {
        let closure_archive = package_archive(|builder| {
            append_file(builder, "usr/bin/build-tool", 0o755, b"tool\n");
        });
        let digest = stage_fixture_closure(directory, &closure_archive, b"signature");
        let root = directory.join(WORKSPACE_NAME).join(BUILD_ROOT_NAME);
        ArchRoot::bootstrap(&root).expect("build root");
        let recipe = root
            .join("home/archphene")
            .join(BUILD_SESSION_NAME)
            .join("example-bin");
        fs::create_dir_all(&recipe).expect("recipe directory");
        fs::write(
            recipe.join("example-bin-1.2.3-1-aarch64.pkg.tar.xz"),
            archive,
        )
        .expect("built package");
        digest
    }

    fn runtime_fixture(directory: &Path) -> (PathBuf, Vec<u8>, [u8; 32]) {
        let workspace = directory.join(WORKSPACE_NAME);
        fs::create_dir(&workspace).expect("runtime workspace");
        let root = workspace.join(BUILD_ROOT_NAME);
        ArchRoot::bootstrap(&root).expect("runtime root");
        let closure = [7_u8; 32];
        fs::write(
            workspace.join(BUILD_ROOT_MANIFEST_NAME),
            format!(
                "ABBR0001\nclosure={}\npackages=1\nentries=3\nbytes=4\n",
                hex_sha256(&closure),
            ),
        )
        .expect("root manifest");
        let native = directory.join("native");
        fs::create_dir(&native).expect("native directory");
        let mut manifest = format!("{BUILDER_RUNTIME_HEADER}\n");
        for index in 0..12 {
            let bytes = format!("verified-runtime-{index}").into_bytes();
            let digest = sha256_bytes(&bytes);
            let packaged = format!("libarchphene_builder_{}.so", &hex_sha256(&digest)[..24],);
            fs::write(native.join(&packaged), &bytes).expect("runtime source");
            let (role, logical) = match index {
                0 => ("loader", "@loader".to_owned()),
                1 => ("library", BUILDER_RUNTIME_PATH_BRIDGE.to_owned()),
                _ => ("library", format!("libfixture{index}.so.1")),
            };
            manifest.push_str(&format!(
                "{role}\t{logical}\t{packaged}\t{}\t{}\n",
                bytes.len(),
                hex_sha256(&digest),
            ));
        }
        (native, manifest.into_bytes(), closure)
    }

    fn reviewed_snapshot(package_base: &str) -> Vec<u8> {
        let output = Vec::<u8>::new();
        let encoder = GzEncoder::new(output, Compression::default());
        let mut archive = tar::Builder::new(encoder);
        archive
            .append_pax_extensions([(
                "comment",
                b"0123456789abcdef0123456789abcdef01234567".as_slice(),
            )])
            .expect("snapshot commit");
        let mut directory = tar::Header::new_gnu();
        directory.set_entry_type(EntryType::Directory);
        directory.set_mode(0o755);
        directory.set_size(0);
        directory.set_cksum();
        archive
            .append_data(&mut directory, format!("{package_base}/"), std::io::empty())
            .expect("snapshot directory");
        for (name, bytes) in [
            ("PKGBUILD", b"package() { :; }\n".as_slice()),
            ("local-source.sh", b"#!/bin/sh\n".as_slice()),
        ] {
            let mut header = tar::Header::new_gnu();
            header.set_entry_type(EntryType::Regular);
            header.set_mode(0o644);
            header.set_size(bytes.len() as u64);
            header.set_cksum();
            archive
                .append_data(&mut header, format!("{package_base}/{name}"), bytes)
                .expect("snapshot file");
        }
        archive
            .into_inner()
            .expect("snapshot archive")
            .finish()
            .expect("snapshot gzip")
    }

    #[test]
    fn stages_and_reverifies_one_bounded_closure() {
        let directory = test_directory();
        let archive = b"signed package archive";
        let signature = b"detached signature";
        let manifest = fixture_manifest(archive, signature);
        let session = ClosureSession::begin(
            &directory,
            "visual-studio-code-bin",
            "1.0-1",
            &manifest,
            sha256_bytes(&manifest),
        )
        .expect("closure session");
        let archive_path = directory.join("archive");
        let signature_path = directory.join("signature");
        fs::write(&archive_path, archive).expect("archive fixture");
        fs::write(&signature_path, signature).expect("signature fixture");
        session
            .stage_package(
                0,
                &mut File::open(archive_path).expect("archive"),
                &mut File::open(signature_path).expect("signature"),
            )
            .expect("stage package");
        let report = session.finish().expect("finish closure");
        assert_eq!(report.package_count, 1);
        assert_eq!(report.archive_bytes, archive.len() as u64);
        assert_eq!(report.signature_bytes, signature.len() as u64);
        assert_eq!(report.manifest_sha256, sha256_bytes(&manifest));
        drop(session);
        fs::remove_dir_all(directory).expect("cleanup");
    }

    #[test]
    fn begin_removes_hostile_links_without_following_them() {
        let directory = test_directory();
        let outside = test_directory();
        fs::write(outside.join("sentinel"), b"keep").expect("outside sentinel");
        let workspace = directory.join(WORKSPACE_NAME);
        fs::create_dir(&workspace).expect("workspace");
        let closure = workspace.join(CLOSURE_NAME);
        fs::create_dir(&closure).expect("closure");
        std::os::unix::fs::symlink(&outside, closure.join("escape")).expect("hostile link");
        let archive = b"archive";
        let signature = b"signature";
        let manifest = fixture_manifest(archive, signature);
        let session = ClosureSession::begin(
            &directory,
            "fixture",
            "1-1",
            &manifest,
            sha256_bytes(&manifest),
        )
        .expect("safe reset");
        drop(session);
        assert_eq!(
            fs::read(outside.join("sentinel")).expect("outside sentinel"),
            b"keep"
        );
        fs::remove_dir_all(directory).expect("cleanup");
        fs::remove_dir_all(outside).expect("outside cleanup");
    }

    #[test]
    fn accepts_epoch_qualified_pacman_filename() {
        let archive = b"archive";
        let signature = b"signature";
        let manifest = fixture_manifest_with_filename(
            "base-devel-1:1.0-1-aarch64.pkg.tar.xz",
            archive,
            signature,
        );
        assert_eq!(parse_manifest(&manifest).expect("valid manifest").len(), 1);
    }

    #[test]
    fn prepares_a_verified_builder_execution_runtime() {
        let directory = test_directory();
        let (native, manifest, closure) = runtime_fixture(&directory);
        let runtime = BuilderRuntime::prepare(&directory, &native, &manifest)
            .expect("verified Builder runtime");
        assert_eq!(runtime.closure_sha256(), closure);
        assert_eq!(runtime.root_report().package_count, 1);
        let bridge = directory
            .join(WORKSPACE_NAME)
            .join(BUILD_ROOT_NAME)
            .join("run")
            .join(BUILDER_RUNTIME_ALIAS_NAME)
            .join(BUILDER_RUNTIME_PATH_BRIDGE);
        assert!(
            fs::symlink_metadata(bridge)
                .expect("bridge alias")
                .file_type()
                .is_symlink(),
        );
        fs::remove_dir_all(directory).expect("cleanup");
    }

    #[test]
    fn rejects_a_tampered_builder_execution_runtime() {
        let directory = test_directory();
        let (native, manifest, _) = runtime_fixture(&directory);
        let packaged = std::str::from_utf8(&manifest)
            .expect("runtime manifest")
            .lines()
            .nth(1)
            .expect("loader entry")
            .split('\t')
            .nth(2)
            .expect("loader package");
        fs::write(native.join(packaged), b"tampered").expect("tamper runtime");
        assert!(BuilderRuntime::prepare(&directory, &native, &manifest).is_err());
        fs::remove_dir_all(directory).expect("cleanup");
    }

    #[test]
    fn stages_reviewed_inputs_with_nofollow_recovery_and_canonical_manifest() {
        let directory = test_directory();
        let legacy = directory.join(LEGACY_WORKSPACE_NAME);
        fs::create_dir(&legacy).expect("legacy workspace");
        fs::write(legacy.join("stale-source"), b"legacy").expect("legacy source");
        let workspace = directory.join(WORKSPACE_NAME);
        fs::create_dir(&workspace).expect("workspace");
        let outside = directory.join("outside");
        fs::create_dir(&outside).expect("outside");
        fs::write(outside.join("sentinel"), b"retained").expect("outside sentinel");
        std::os::unix::fs::symlink(&outside, workspace.join(REVIEWED_INPUTS_NAME))
            .expect("hostile prior inputs");

        let snapshot_bytes = b"reviewed snapshot";
        let source_bytes = b"verified source";
        let snapshot_path = directory.join("snapshot");
        let source_path = directory.join("source");
        fs::write(&snapshot_path, snapshot_bytes).expect("snapshot");
        fs::write(&source_path, source_bytes).expect("source");
        let snapshot_sha256 = sha256_bytes(snapshot_bytes);
        let source_sha256 = sha256_bytes(source_bytes);

        let mut session = ReviewedInputSession::begin(&directory, "example-bin", "1.2.3-1", 2)
            .expect("reviewed-input session");
        session
            .stage(
                ReviewedInputRole::Source,
                "example.tar.zst",
                source_bytes.len() as u64,
                source_sha256,
                &mut File::open(&source_path).expect("open source"),
            )
            .expect("stage source");
        session
            .stage(
                ReviewedInputRole::Snapshot,
                "example-bin.tar.gz",
                snapshot_bytes.len() as u64,
                snapshot_sha256,
                &mut File::open(&snapshot_path).expect("open snapshot"),
            )
            .expect("stage snapshot");
        let report = session.finish().expect("publish reviewed inputs");
        assert_eq!(report.input_count, 2);
        assert_eq!(
            report.input_bytes,
            (snapshot_bytes.len() + source_bytes.len()) as u64,
        );
        let manifest = fs::read(workspace.join(REVIEWED_INPUTS_NAME).join("manifest"))
            .expect("published input manifest");
        let expected = format!(
            "ABIN0001\npackage=example-bin\nversion=1.2.3-1\n\
             snapshot\texample-bin.tar.gz\t{}\t{}\n\
             source\texample.tar.zst\t{}\t{}\n",
            snapshot_bytes.len(),
            hex_sha256(&snapshot_sha256),
            source_bytes.len(),
            hex_sha256(&source_sha256),
        );
        assert_eq!(manifest, expected.as_bytes());
        assert_eq!(report.manifest_sha256, sha256_bytes(expected.as_bytes()));
        assert_eq!(
            fs::read(outside.join("sentinel")).expect("outside survived"),
            b"retained",
        );
        assert!(!legacy.exists());
        fs::remove_dir_all(directory).expect("cleanup");
    }

    #[test]
    fn reviewed_input_publication_rehashes_before_manifest() {
        let directory = test_directory();
        let snapshot_bytes = b"reviewed snapshot";
        let snapshot_path = directory.join("snapshot");
        fs::write(&snapshot_path, snapshot_bytes).expect("snapshot");
        let snapshot_sha256 = sha256_bytes(snapshot_bytes);
        let mut session = ReviewedInputSession::begin(&directory, "example-bin", "1.2.3-1", 1)
            .expect("reviewed-input session");
        session
            .stage(
                ReviewedInputRole::Snapshot,
                "example-bin.tar.gz",
                snapshot_bytes.len() as u64,
                snapshot_sha256,
                &mut File::open(&snapshot_path).expect("open snapshot"),
            )
            .expect("stage snapshot");
        let staged = directory
            .join(WORKSPACE_NAME)
            .join(REVIEWED_INPUTS_NAME)
            .join(format!(
                "snapshot-{}-example-bin.tar.gz",
                hex_sha256(&snapshot_sha256),
            ));
        fs::write(staged, b"tampered snapshot").expect("tamper staged input");
        assert!(session.finish().is_err());
        fs::remove_dir_all(directory).expect("cleanup");
    }

    #[test]
    fn prepares_disposable_recipe_from_exact_reviewed_inputs() {
        let directory = test_directory();
        let snapshot = reviewed_snapshot("example-bin");
        let source = b"verified remote source";
        let snapshot_path = directory.join("snapshot");
        let source_path = directory.join("source");
        fs::write(&snapshot_path, &snapshot).expect("snapshot");
        fs::write(&source_path, source).expect("source");
        let mut inputs = ReviewedInputSession::begin(&directory, "example-bin", "1.2.3-1", 2)
            .expect("reviewed inputs");
        inputs
            .stage(
                ReviewedInputRole::Snapshot,
                "example-bin.tar.gz",
                snapshot.len() as u64,
                sha256_bytes(&snapshot),
                &mut File::open(&snapshot_path).expect("open snapshot"),
            )
            .expect("stage snapshot");
        inputs
            .stage(
                ReviewedInputRole::Source,
                "remote-source.bin",
                source.len() as u64,
                sha256_bytes(source),
                &mut File::open(&source_path).expect("open source"),
            )
            .expect("stage source");
        let input_report = inputs.finish().expect("publish inputs");

        let workspace = directory.join(WORKSPACE_NAME);
        let root = workspace.join(BUILD_ROOT_NAME);
        ArchRoot::bootstrap(&root).expect("build root");
        let outside = directory.join("outside-build");
        fs::create_dir(&outside).expect("outside build");
        fs::write(outside.join("sentinel"), b"retained").expect("outside sentinel");
        std::os::unix::fs::symlink(
            &outside,
            root.join("home/archphene").join(BUILD_SESSION_NAME),
        )
        .expect("hostile build workspace");
        let closure = [9_u8; 32];
        fs::write(
            workspace.join(BUILD_ROOT_MANIFEST_NAME),
            format!(
                "ABBR0001\nclosure={}\npackages=1\nentries=3\nbytes=4\n",
                hex_sha256(&closure),
            ),
        )
        .expect("root manifest");
        let recipe = prepare_recipe_workspace(
            &directory,
            "example-bin",
            "1.2.3-1",
            input_report.manifest_sha256,
            closure,
        )
        .expect("recipe workspace");
        assert_eq!(recipe.recipe_entries, 3);
        assert_eq!(
            fs::read(recipe.directory.join("remote-source.bin")).expect("prepared source"),
            source,
        );
        assert_eq!(
            fs::read(recipe.directory.join("PKGBUILD")).expect("prepared recipe"),
            b"package() { :; }\n",
        );
        assert_eq!(
            fs::read(outside.join("sentinel")).expect("outside survived"),
            b"retained",
        );
        fs::remove_dir_all(directory).expect("cleanup");
    }

    #[test]
    fn finish_rejects_a_tampered_staged_archive() {
        let directory = test_directory();
        let archive = b"package archive";
        let signature = b"signature";
        let manifest = fixture_manifest(archive, signature);
        let session = ClosureSession::begin(
            &directory,
            "fixture",
            "1-1",
            &manifest,
            sha256_bytes(&manifest),
        )
        .expect("closure session");
        let archive_path = directory.join("archive");
        let signature_path = directory.join("signature");
        fs::write(&archive_path, archive).expect("archive fixture");
        fs::write(&signature_path, signature).expect("signature fixture");
        session
            .stage_package(
                0,
                &mut File::open(archive_path).expect("archive"),
                &mut File::open(signature_path).expect("signature"),
            )
            .expect("stage package");
        fs::write(
            directory
                .join(WORKSPACE_NAME)
                .join(CLOSURE_NAME)
                .join(ARCHIVES_NAME)
                .join("000-base-devel-1-1-any.pkg.tar.zst"),
            b"tampered bytes",
        )
        .expect("tamper staged archive");
        assert!(session.finish().is_err());
        fs::remove_dir_all(directory).expect("cleanup");
    }

    #[test]
    fn invalid_input_does_not_leave_a_partial_archive() {
        let directory = test_directory();
        let archive = b"package archive";
        let signature = b"signature";
        let manifest = fixture_manifest(archive, signature);
        let session = ClosureSession::begin(
            &directory,
            "fixture",
            "1-1",
            &manifest,
            sha256_bytes(&manifest),
        )
        .expect("closure session");
        let archive_path = directory.join("archive");
        let signature_path = directory.join("signature");
        fs::write(&archive_path, b"wrong archive").expect("bad archive fixture");
        fs::write(&signature_path, signature).expect("signature fixture");
        assert!(
            session
                .stage_package(
                    0,
                    &mut File::open(archive_path).expect("archive"),
                    &mut File::open(signature_path).expect("signature"),
                )
                .is_err(),
        );
        assert!(
            !directory
                .join(WORKSPACE_NAME)
                .join(CLOSURE_NAME)
                .join(ARCHIVES_NAME)
                .join("000-base-devel-1-1-any.pkg.tar.zst.part")
                .exists(),
        );
        fs::remove_dir_all(directory).expect("cleanup");
    }

    #[test]
    fn provisions_a_fresh_root_from_an_xz_package() {
        let directory = test_directory();
        let archive = package_archive(|builder| {
            append_file(builder, "usr/bin/build-tool", 0o755, b"tool\n");
            append_symlink(builder, "usr/bin/build-tool-link", Path::new("build-tool"));
        });
        let digest = stage_fixture_closure(&directory, &archive, b"signature");
        let mut provision = ProvisionSession::begin(&directory, "fixture", "1-1", digest)
            .expect("provision session");
        assert_eq!(provision.expected().package_count, 1);
        assert!(provision.expected().entry_count >= 3);
        let report = provision.extract_next(8).expect("extract package");
        assert_eq!(report.package_count, 1);
        let root = provision.root().to_path_buf();
        assert_eq!(provision.finish().expect("finish provision"), report);
        assert_eq!(
            fs::read(root.join("usr/bin/build-tool")).expect("extracted tool"),
            b"tool\n",
        );
        assert_eq!(
            fs::read_link(root.join("usr/bin/build-tool-link")).expect("extracted symlink"),
            Path::new("build-tool"),
        );
        assert!(!root.join(".PKGINFO").exists());
        assert_eq!(
            fs::read(root.join("var/lib/pacman/local/ALPM_DB_VERSION"))
                .expect("local database version"),
            PACMAN_LOCAL_DATABASE_VERSION,
        );
        let local_description =
            fs::read_to_string(root.join("var/lib/pacman/local/base-devel-1-1/desc"))
                .expect("local package description");
        assert!(local_description.contains("%NAME%\nbase-devel\n"));
        assert!(local_description.contains("%VERSION%\n1-1\n"));
        let root_manifest = fs::read_to_string(
            directory
                .join(WORKSPACE_NAME)
                .join(BUILD_ROOT_MANIFEST_NAME),
        )
        .expect("published root manifest");
        assert!(root_manifest.starts_with("ABBR0001\nclosure="));
        assert!(root_manifest.contains("\npackages=1\n"));
        fs::remove_dir_all(directory).expect("cleanup");
    }

    #[test]
    fn verifies_and_copies_only_exact_provenanced_build_output() {
        let directory = test_directory();
        let archive = built_package_archive("base-devel-1-1-any");
        let closure = built_output_fixture(&directory, &archive);
        let destination = directory.join("manager-output");
        let mut output = OpenOptions::new()
            .create_new(true)
            .read(true)
            .write(true)
            .open(&destination)
            .expect("manager output");
        let report = verify_and_copy_built_package(
            &directory,
            "example-bin",
            "example-bin",
            "1.2.3-1",
            "aarch64",
            closure,
            &mut output,
        )
        .expect("verified built output");
        assert_eq!(report.filename, "example-bin-1.2.3-1-aarch64.pkg.tar.xz");
        assert_eq!(report.archive_bytes, archive.len() as u64);
        assert_eq!(report.installed_bytes, 7);
        assert_eq!(report.sha256, sha256_bytes(&archive));
        assert_eq!(report.build_package_count, 1);
        assert_eq!(fs::read(destination).expect("copied output"), archive);
        let manifest = fs::read(
            directory
                .join(WORKSPACE_NAME)
                .join(CLOSURE_NAME)
                .join(PUBLISHED_MANIFEST_NAME),
        )
        .expect("retained manager closure");
        let manager_report = verify_copied_built_package(
            &mut output,
            &report.filename,
            "example-bin",
            "example-bin",
            "1.2.3-1",
            "aarch64",
            &manifest,
            closure,
        )
        .expect("manager independently verified output");
        assert_eq!(manager_report.archive_bytes, report.archive_bytes);
        assert_eq!(manager_report.installed_bytes, report.installed_bytes);
        assert_eq!(manager_report.sha256, report.sha256);
        assert_eq!(
            manager_report.build_package_count,
            report.build_package_count
        );
        fs::remove_dir_all(directory).expect("cleanup");
    }

    #[test]
    fn rejects_output_that_omits_the_exact_signed_build_closure() {
        let directory = test_directory();
        let archive = built_package_archive("substituted-9-9-aarch64");
        let closure = built_output_fixture(&directory, &archive);
        let destination = directory.join("manager-output");
        let mut output = File::create(&destination).expect("manager output");
        assert!(
            verify_and_copy_built_package(
                &directory,
                "example-bin",
                "example-bin",
                "1.2.3-1",
                "aarch64",
                closure,
                &mut output,
            )
            .is_err(),
        );
        assert_eq!(fs::metadata(destination).expect("empty output").len(), 0);
        fs::remove_dir_all(directory).expect("cleanup");
    }

    #[test]
    fn rejects_a_symlink_substituted_build_output() {
        let directory = test_directory();
        let archive = built_package_archive("base-devel-1-1-any");
        let closure = built_output_fixture(&directory, &archive);
        let recipe = directory
            .join(WORKSPACE_NAME)
            .join(BUILD_ROOT_NAME)
            .join("home/archphene")
            .join(BUILD_SESSION_NAME)
            .join("example-bin");
        let package = recipe.join("example-bin-1.2.3-1-aarch64.pkg.tar.xz");
        fs::remove_file(&package).expect("remove package");
        std::os::unix::fs::symlink("/outside", &package).expect("hostile package link");
        let mut output = File::create(directory.join("manager-output")).expect("manager output");
        assert!(
            verify_and_copy_built_package(
                &directory,
                "example-bin",
                "example-bin",
                "1.2.3-1",
                "aarch64",
                closure,
                &mut output,
            )
            .is_err(),
        );
        fs::remove_dir_all(directory).expect("cleanup");
    }

    #[test]
    fn materializes_hard_links_for_android_filesystems() {
        let directory = test_directory();
        let archive = package_archive(|builder| {
            append_file(builder, "usr/share/data/original", 0o644, b"shared bytes\n");
            append_hard_link(
                builder,
                "usr/share/data/alias",
                Path::new("usr/share/data/original"),
            );
        });
        let digest = stage_fixture_closure(&directory, &archive, b"signature");
        let mut provision = ProvisionSession::begin(&directory, "fixture", "1-1", digest)
            .expect("provision session");
        assert_eq!(
            provision.expected().expanded_bytes,
            (b"pkgname = base-devel\npkgver = 1-1\narch = any\n".len()
                + b"shared bytes\n".len() * 2) as u64,
        );
        provision.extract_next(1).expect("extract package");
        let root = provision.root().to_path_buf();
        provision.finish().expect("finish provision");
        assert_eq!(
            fs::read(root.join("usr/share/data/alias")).expect("materialized hard link"),
            b"shared bytes\n",
        );
        fs::remove_dir_all(directory).expect("cleanup");
    }

    #[test]
    fn provisioning_rejects_package_metadata_that_disagrees_with_the_signed_manifest() {
        let directory = test_directory();
        let encoder = XzEncoder::new(Vec::new(), 6);
        let mut builder = tar::Builder::new(encoder);
        builder.mode(tar::HeaderMode::Deterministic);
        append_file(
            &mut builder,
            ".PKGINFO",
            0o644,
            b"pkgname = substituted\npkgver = 1-1\narch = any\n",
        );
        append_file(&mut builder, "usr/bin/build-tool", 0o755, b"tool\n");
        let encoder = builder.into_inner().expect("finish tar archive");
        let archive = encoder.finish().expect("finish xz archive");
        let digest = stage_fixture_closure(&directory, &archive, b"signature");
        let mut provision = ProvisionSession::begin(&directory, "fixture", "1-1", digest)
            .expect("provision session");
        assert!(provision.extract_next(1).is_err());
        assert!(
            !provision
                .root()
                .join("var/lib/pacman/local/base-devel-1-1/desc")
                .exists(),
        );
        fs::remove_dir_all(directory).expect("cleanup");
    }

    #[test]
    fn reprovision_removes_more_than_legacy_workspace_limit() {
        let directory = test_directory();
        let archive = package_archive(|builder| {
            append_file(builder, "usr/bin/build-tool", 0o755, b"tool\n");
        });
        let digest = stage_fixture_closure(&directory, &archive, b"signature");
        let mut first =
            ProvisionSession::begin(&directory, "fixture", "1-1", digest).expect("first provision");
        first.extract_next(1).expect("first extraction");
        let root = first.root().to_path_buf();
        first.finish().expect("finish first provision");
        let churn = root.join("tmp/churn");
        fs::create_dir(&churn).expect("churn directory");
        for index in 0..4_200 {
            fs::write(churn.join(index.to_string()), b"x").expect("churn file");
        }
        fs::set_permissions(&churn, fs::Permissions::from_mode(0o111))
            .expect("hostile directory mode");

        let second = ProvisionSession::begin(&directory, "fixture", "1-1", digest)
            .expect("repeat provision");
        assert!(!second.root().join("tmp/churn").exists());
        assert!(
            !directory
                .join(WORKSPACE_NAME)
                .join(BUILD_ROOT_MANIFEST_NAME)
                .exists(),
        );
        fs::remove_dir_all(directory).expect("cleanup");
    }

    #[test]
    fn extraction_rejects_a_symlink_parent_escape() {
        let directory = test_directory();
        let outside = test_directory();
        let archive = package_archive(|builder| {
            append_symlink(builder, "usr/escape", &outside);
            append_file(builder, "usr/escape/pwn", 0o644, b"escaped\n");
        });
        let digest = stage_fixture_closure(&directory, &archive, b"signature");
        let mut provision = ProvisionSession::begin(&directory, "fixture", "1-1", digest)
            .expect("provision session");
        assert!(provision.extract_next(1).is_err());
        assert!(!outside.join("pwn").exists());
        fs::remove_dir_all(directory).expect("cleanup");
        fs::remove_dir_all(outside).expect("outside cleanup");
    }
}
