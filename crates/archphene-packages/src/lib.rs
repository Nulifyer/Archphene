#![forbid(unsafe_code)]

pub mod aur;
pub mod desktop;
pub mod elf_profile;

use std::collections::BTreeSet;
use std::ffi::OsString;
use std::fmt;
use std::fmt::Write as FmtWrite;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt, symlink};
use std::os::unix::process::ExitStatusExt;
use std::path::{Component, Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use archphene_process::{CommandEnvironment, GuiAppearance, ProcessError, publish_gui_appearance};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tar::{Archive, EntryType};
use xz2::read::XzDecoder;

pub const MAX_MANIFEST_BYTES: usize = 32 * 1024;
pub const MAX_MANIFEST_ENTRIES: usize = 128;
pub const MAX_TOOL_OUTPUT_BYTES: usize = 16 * 1024;
pub const MAX_PACKAGE_RESOLUTION_BYTES: usize = 256 * 1024;
pub const MAX_VERIFIED_PACKAGE_CLOSURE_BYTES: usize = 512 * 1024;
pub const INSTALLED_PACKAGE_PAGE_SIZE: usize = 60;
pub const PACKAGE_CACHE_PAGE_SIZE: usize = 32;

const MANIFEST_HEADER: &str = "# org.archphene.package-runtime.v1";
const COMMAND_TIMEOUT: Duration = Duration::from_secs(10);
const TRANSACTION_TIMEOUT: Duration = Duration::from_secs(5 * 60);
const MAX_PACKAGE_TRANSACTION_OUTPUT_BYTES: usize = 4 * 1024 * 1024;
const ALIAS_DIRECTORY: &str = "run/package-runtime-v1";
const GDK_PIXBUF_MODULE_FILE: &str = "run/gdk-pixbuf-loaders-v1.cache";
const GDK_PIXBUF_MODULE_TEMP_FILE: &str = "run/.gdk-pixbuf-loaders-v1.tmp";
const GDK_PIXBUF_LIBRARY: &str = "libgdk_pixbuf-2.0.so.0";
const GDK_PIXBUF_SVG_LIBRARY: &str = "librsvg-2.so.2";
const GDK_PIXBUF_SVG_LOADER: &str = "libarchphene_pixbufloader_svg.so";
const GTK_SETTINGS_LIBRARY: &str = "libarchphene_gtk3_settings.so";
const QT_PLATFORM_THEME_LIBRARY: &str = "libarchphene_qt_platform_theme.so";
const QT_STYLE_LIBRARY: &str = "libarchphene_qt_style.so";
const QT_KDE_CONFIG_LIBRARY: &str = "libarchphene_kde_config.so";
const TOOLKIT_PLUGIN_DIRECTORY: &str = "run/toolkit-plugins-v1";
const OUTPUT_FILE: &str = "run/package-command-output.tmp";
const INSTALL_REASON_INTENT_FILE: &str = "run/package-install-reasons-v1";
const INSTALL_REASON_INTENT_TEMP_FILE: &str = "run/package-install-reasons-v1.tmp";
const INSTALL_REASON_INTENT_HEADER: &str = "org.archphene.package-install-reasons.v1";
const INSTALL_REASON_INTENT_LIMIT: u64 = 64 * 1024;
const PACKAGE_MUTATION_INTENT_FILE: &str = "run/package-mutation-v1";
const PACKAGE_MUTATION_INTENT_TEMP_FILE: &str = "run/package-mutation-v1.tmp";
const PACKAGE_MUTATION_INTENT_HEADER: &str = "org.archphene.package-mutation.v1";
const PACKAGE_MUTATION_INTENT_LIMIT: u64 = 384 * 1024;
const PACKAGE_DATABASE_REPAIR_DIRECTORY: &str = "run/package-database-repair-v1";
const PACKAGE_REMOVAL_REPAIR_DIRECTORY: &str = "run/package-removal-repair-v1";
const PACKAGE_REMOVAL_REPAIR_TEMP_DIRECTORY: &str = "run/package-removal-repair-v1.tmp";
const PACKAGE_REMOVAL_LOCAL_TEMP_DIRECTORY: &str =
    "var/lib/pacman/local/.archphene-removal-repair.tmp";
const PACMAN_CONFIG_FILE: &str = "etc/pacman.conf";
const PACMAN_CONFIG_TEMP_FILE: &str = "etc/pacman.conf.tmp";
const AUR_PACMAN_CONFIG_FILE: &str = "etc/pacman-aur.conf";
const AUR_PACMAN_CONFIG_TEMP_FILE: &str = "etc/pacman-aur.conf.tmp";
const CATALOG_DIRECTORY: &str = "var/lib/pacman/sync";
const AUR_BUILD_DATABASE_DIRECTORY: &str = "run/aur-build-database-v1";
const CORE_CATALOG_LIMIT: u64 = 8 * 1024 * 1024;
const EXTRA_CATALOG_LIMIT: u64 = 64 * 1024 * 1024;
const PACKAGE_ARCHIVE_LIMIT: u64 = 4 * 1024 * 1024 * 1024;
const PACKAGE_SIGNATURE_LIMIT: u64 = 1024 * 1024;
const PACKAGE_CACHE_DIRECTORY: &str = "var/cache/pacman/pkg";
const AUR_PACKAGE_CACHE_DIRECTORY: &str = "var/cache/archphene/aur-packages";
const AUR_BUILT_CAPABILITY_FILE: &str = ".built-capability-v1.json";
const AUR_BUILT_CAPABILITY_TEMP_FILE: &str = ".built-capability-v1.tmp";
const AUR_BUILT_CAPABILITY_LIMIT: u64 = 256 * 1024;
const AUR_LIFECYCLE_CAPABILITY_FILE: &str = "aur-lifecycle-capabilities-v1";
const AUR_LIFECYCLE_CAPABILITY_TEMP_FILE: &str = "aur-lifecycle-capabilities-v1.tmp";
const AUR_LIFECYCLE_CAPABILITY_HEADER: &str = "org.archphene.aur-lifecycle-capabilities.v1";
const AUR_LIFECYCLE_CAPABILITY_LIMIT: u64 = 2 * 1024 * 1024;
const AUR_LIFECYCLE_CAPABILITY_ENTRIES: usize = 8192;
const PACKAGE_COMPATIBILITY_CACHE_DIRECTORY: &str = "var/cache/archphene/package-compatibility-v1";
const PACKAGE_COMPATIBILITY_CACHE_DOMAIN: &[u8] = b"org.archphene.package-compatibility-cache.v2\0";
const PACKAGE_COMPATIBILITY_CACHE_RECORD_LIMIT: u64 = 1024;
const PACKAGE_COMPATIBILITY_CACHE_ENTRY_LIMIT: usize = 1024;
const PACKAGE_TRUST_DIRECTORY: &str = "run/package-trust-v1";
const PACKAGE_TRUST_STATE: &str = "source-v1";
const PACKAGE_TRUST_STATE_LIMIT: u64 = 512;
const PACKAGE_KEYBOX_LIMIT: u64 = 8 * 1024 * 1024;
const PACKAGE_TRUSTDB_LIMIT: u64 = 1024 * 1024;
const SYSTEM_TRUST_BUNDLE: &str = "etc/ssl/certs/ca-certificates.crt";
const SYSTEM_TRUST_SOURCE: &str = "usr/share/ca-certificates/trust-source/mozilla.trust.p11-kit";
const SYSTEM_TRUST_BUNDLE_LIMIT: u64 = 8 * 1024 * 1024;
const SHELLS_FILE: &str = "etc/shells";
const SHELLS_FILE_LIMIT: u64 = 4 * 1024;
const LOCAL_DATABASE_ENTRY_LIMIT: usize = 4096;
const LOCAL_DATABASE_DIRECTORY_ENTRY_LIMIT: usize = 8192;
const LOCAL_DATABASE_PACKAGE_FILE_LIMIT: u64 = 16 * 1024 * 1024;
const LOCAL_DATABASE_PACKAGE_FILE_COUNT: usize = 5;
const LOCAL_DESCRIPTION_LIMIT: u64 = 64 * 1024;
const LOCAL_FILES_LIMIT: u64 = 8 * 1024 * 1024;
const LOCAL_FILES_TOTAL_LIMIT: u64 = 128 * 1024 * 1024;
const LOCAL_FILE_PATH_LIMIT: usize = 4 * 1024;
const PACKAGE_COMPATIBILITY_MAX_ENTRIES: u64 = 262_144;
const PACKAGE_COMPATIBILITY_MAX_EXPANDED_BYTES: u64 = 16 * 1024 * 1024 * 1024;
const PACKAGE_COMPATIBILITY_MAX_ENTRY_BYTES: u64 = 4 * 1024 * 1024 * 1024;
const PACKAGE_COMPATIBILITY_HEADER_BYTES: usize = 16 * 1024;
const PACKAGE_CAPABILITY_GRAPHICAL: u8 = 1 << 0;
const PACKAGE_CAPABILITY_COMMAND_LINE: u8 = 1 << 1;
const PACKAGE_CAPABILITY_LIBRARY: u8 = 1 << 2;
const PACKAGE_CAPABILITY_SYSTEM: u8 = 1 << 3;
const O_NOFOLLOW: i32 = 0o400000;
const O_CLOEXEC: i32 = 0o2000000;
const PATH_BRIDGE_NAME: &str = "libarchphene_path_bridge.so";
const BASE_PACKAGE: &str = "base";
const AARCH64_BUILD_KEY: &str = "68B3537F39A313B3E574D06777193F152BDBE6A6";

const X86_64_PACMAN_CONFIG: &[u8] = b"[options]\n\
Architecture = x86_64\n\
SigLevel = Required DatabaseOptional\n\
LocalFileSigLevel = Required\n\
ParallelDownloads = 1\n\n\
[core]\n\
Server = https://geo.mirror.pkgbuild.com/core/os/x86_64\n\n\
[extra]\n\
Server = https://geo.mirror.pkgbuild.com/extra/os/x86_64\n";

const AARCH64_PACMAN_CONFIG: &[u8] = b"[options]\n\
Architecture = aarch64\n\
SigLevel = Required DatabaseOptional\n\
LocalFileSigLevel = Required\n\
ParallelDownloads = 1\n\n\
[core]\n\
Server = https://ca.us.mirror.archlinuxarm.org/aarch64/core\n\n\
[extra]\n\
Server = https://ca.us.mirror.archlinuxarm.org/aarch64/extra\n";

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum RepositoryArchitecture {
    X86_64,
    Aarch64,
}

impl RepositoryArchitecture {
    const fn pacman_config(self) -> &'static [u8] {
        match self {
            Self::X86_64 => X86_64_PACMAN_CONFIG,
            Self::Aarch64 => AARCH64_PACMAN_CONFIG,
        }
    }

    fn aur_pacman_config(self) -> Vec<u8> {
        let source = self.pacman_config();
        let required = b"LocalFileSigLevel = Required";
        let optional = b"LocalFileSigLevel = Optional";
        let Some(offset) = source
            .windows(required.len())
            .position(|window| window == required)
        else {
            return Vec::new();
        };
        let mut output = Vec::with_capacity(source.len());
        output.extend_from_slice(&source[..offset]);
        output.extend_from_slice(optional);
        output.extend_from_slice(&source[offset + required.len()..]);
        output
    }

    const fn catalog_url(self, repository: Repository) -> &'static str {
        match (self, repository) {
            (Self::X86_64, Repository::Core) => {
                "https://geo.mirror.pkgbuild.com/core/os/x86_64/core.db"
            }
            (Self::X86_64, Repository::Extra) => {
                "https://geo.mirror.pkgbuild.com/extra/os/x86_64/extra.db"
            }
            (Self::Aarch64, Repository::Core) => {
                "https://ca.us.mirror.archlinuxarm.org/aarch64/core/core.db"
            }
            (Self::Aarch64, Repository::Extra) => {
                "https://ca.us.mirror.archlinuxarm.org/aarch64/extra/extra.db"
            }
        }
    }

    fn package_url_prefix(self, repository: &str) -> Option<&'static str> {
        match (self, repository) {
            (Self::X86_64, "core") => Some("https://geo.mirror.pkgbuild.com/core/os/x86_64/"),
            (Self::X86_64, "extra") => Some("https://geo.mirror.pkgbuild.com/extra/os/x86_64/"),
            (Self::Aarch64, "core") => Some("https://ca.us.mirror.archlinuxarm.org/aarch64/core/"),
            (Self::Aarch64, "extra") => {
                Some("https://ca.us.mirror.archlinuxarm.org/aarch64/extra/")
            }
            _ => None,
        }
    }

    const fn package_architecture(self) -> &'static str {
        match self {
            Self::X86_64 => "x86_64",
            Self::Aarch64 => "aarch64",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Repository {
    Core,
    Extra,
}

impl Repository {
    const fn file_name(self) -> &'static str {
        match self {
            Self::Core => "core.db",
            Self::Extra => "extra.db",
        }
    }

    const fn temporary_file_name(self) -> &'static str {
        match self {
            Self::Core => ".core.db.download",
            Self::Extra => ".extra.db.download",
        }
    }

    const fn size_limit(self) -> u64 {
        match self {
            Self::Core => CORE_CATALOG_LIMIT,
            Self::Extra => EXTRA_CATALOG_LIMIT,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PackageTool {
    Pacman,
    Bsdtar,
    Gpg,
    Gpgv,
    Gpgconf,
}

impl PackageTool {
    const fn index(self) -> usize {
        match self {
            Self::Pacman => 0,
            Self::Bsdtar => 1,
            Self::Gpg => 2,
            Self::Gpgv => 3,
            Self::Gpgconf => 4,
        }
    }

    const fn logical_name(self) -> &'static str {
        match self {
            Self::Pacman => "@pacman",
            Self::Bsdtar => "@bsdtar",
            Self::Gpg => "@gpg",
            Self::Gpgv => "@gpgv",
            Self::Gpgconf => "@gpgconf",
        }
    }
}

#[derive(Debug)]
pub enum PackageRuntimeError {
    InvalidPath,
    InvalidManifest,
    DuplicateEntry,
    MissingEntry(&'static str),
    UnsafeEntry(PathBuf),
    SizeMismatch,
    OutputLimit,
    Timeout,
    Busy,
    InvalidCatalog,
    Desktop(desktop::DesktopEntryError),
    InvalidQuery,
    InvalidResolution,
    Cancelled,
    CompatibilityReviewRequired,
    CompatibilityReview(String, Box<PackageRuntimeError>),
    MissingTarget,
    NotInstalled,
    UnreviewedInstallScript,
    InvalidPayload,
    InvalidSignature,
    ToolFailed(i32, Box<ToolOutput>),
    Process(ProcessError),
    Io(io::Error),
}

impl fmt::Display for PackageRuntimeError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidPath => formatter.write_str("invalid package-runtime path"),
            Self::InvalidManifest => formatter.write_str("invalid package-runtime manifest"),
            Self::DuplicateEntry => formatter.write_str("duplicate package-runtime entry"),
            Self::MissingEntry(entry) => {
                write!(formatter, "missing package-runtime entry: {entry}")
            }
            Self::UnsafeEntry(path) => {
                write!(
                    formatter,
                    "unsafe package-runtime entry: {}",
                    path.display()
                )
            }
            Self::SizeMismatch => formatter.write_str("package-runtime file size mismatch"),
            Self::OutputLimit => formatter.write_str("package command output exceeds its limit"),
            Self::Timeout => formatter.write_str("package command timed out"),
            Self::Busy => formatter.write_str("another package catalog transfer is active"),
            Self::InvalidCatalog => formatter.write_str("invalid package repository catalog"),
            Self::Desktop(error) => error.fmt(formatter),
            Self::InvalidQuery => formatter.write_str("invalid package search query"),
            Self::InvalidResolution => formatter.write_str("invalid package dependency resolution"),
            Self::Cancelled => formatter.write_str("package compatibility review was cancelled"),
            Self::CompatibilityReviewRequired => {
                formatter.write_str("verified package compatibility review is missing or stale")
            }
            Self::CompatibilityReview(package, error) => {
                write!(
                    formatter,
                    "package compatibility review failed for {package}: {error}"
                )
            }
            Self::MissingTarget => {
                formatter.write_str("resolved packages omit the requested target")
            }
            Self::NotInstalled => formatter.write_str("package is not installed"),
            Self::UnreviewedInstallScript => formatter.write_str(
                "the installed AUR lifecycle script is missing exact review authorization",
            ),
            Self::InvalidPayload => formatter.write_str("invalid package payload"),
            Self::InvalidSignature => formatter.write_str("invalid package signature"),
            Self::ToolFailed(code, output) => {
                write!(formatter, "package command failed with status {code}")?;
                if let Ok(text) = output.as_str() {
                    let text = text.trim();
                    if !text.is_empty() {
                        write!(formatter, ": {text}")?;
                    }
                }
                Ok(())
            }
            Self::Process(error) => error.fmt(formatter),
            Self::Io(error) => write!(formatter, "package-runtime I/O error: {error}"),
        }
    }
}

impl std::error::Error for PackageRuntimeError {}

impl From<io::Error> for PackageRuntimeError {
    fn from(error: io::Error) -> Self {
        Self::Io(error)
    }
}

impl From<ProcessError> for PackageRuntimeError {
    fn from(error: ProcessError) -> Self {
        Self::Process(error)
    }
}

impl From<desktop::DesktopEntryError> for PackageRuntimeError {
    fn from(error: desktop::DesktopEntryError) -> Self {
        Self::Desktop(error)
    }
}

#[derive(Clone, Debug, Default)]
pub struct PackageCompatibilityCancellation(Arc<AtomicBool>);

impl PackageCompatibilityCancellation {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn cancel(&self) {
        self.0.store(true, Ordering::Release);
    }

    pub fn is_cancelled(&self) -> bool {
        self.0.load(Ordering::Acquire)
    }

    fn check(&self) -> Result<(), PackageRuntimeError> {
        if self.is_cancelled() {
            Err(PackageRuntimeError::Cancelled)
        } else {
            Ok(())
        }
    }
}

#[derive(Clone)]
pub struct PackageRuntime {
    arch_root: PathBuf,
    native_root: PathBuf,
    alias_root: PathBuf,
    pacman_config: PathBuf,
    architecture: RepositoryArchitecture,
    loader: PathBuf,
    keyring: PathBuf,
    ownertrust: PathBuf,
    verification_source_state: String,
    path_bridge: PathBuf,
    tools: [Option<PathBuf>; 5],
    library_path: OsString,
    executable_path: OsString,
    gdk_pixbuf_module_file: Option<PathBuf>,
    gtk_settings_module: Option<PathBuf>,
    qt_plugin_root: Option<PathBuf>,
    compatibility_analysis: Arc<Mutex<()>>,
    compatibility_review: Arc<Mutex<Option<PackageCompatibilityReview>>>,
}

#[derive(Debug)]
pub struct ToolOutput {
    bytes: [u8; MAX_TOOL_OUTPUT_BYTES],
    length: usize,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PackageResolution {
    bytes: Vec<u8>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RepositoryTargetPartition {
    official_targets: Vec<String>,
    unresolved_targets: Vec<String>,
    resolution: Option<PackageResolution>,
}

impl RepositoryTargetPartition {
    pub fn official_targets(&self) -> &[String] {
        &self.official_targets
    }

    pub fn unresolved_targets(&self) -> &[String] {
        &self.unresolved_targets
    }

    pub fn resolution(&self) -> Option<&PackageResolution> {
        self.resolution.as_ref()
    }
}

#[derive(Clone, Debug)]
pub struct VerifiedPackageClosure {
    bytes: Vec<u8>,
}

pub struct VerifiedAurArchive<'a> {
    pub source: &'a mut File,
    pub filename: &'a str,
    pub package: &'a str,
    pub version: &'a str,
    pub expected_bytes: u64,
    pub expected_sha256: [u8; 32],
    pub install_script_sha256: Option<[u8; 32]>,
}

pub struct VerifiedAurCapabilityArchive<'a> {
    pub source: &'a mut File,
    pub filename: &'a str,
    pub package: &'a str,
    pub archive_bytes: u64,
    pub installed_bytes: u64,
    pub build_package_count: usize,
    pub sha256: [u8; 32],
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PersistedAurCapabilityArchive {
    pub package: String,
    pub filename: String,
    pub archive_bytes: u64,
    pub installed_bytes: u64,
    pub build_package_count: usize,
    pub sha256: [u8; 32],
    pub path: PathBuf,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct AurBuiltCapabilityRecord {
    format: u32,
    package_base: String,
    package_name: String,
    version: String,
    architecture: String,
    review_sha256: [u8; 32],
    closure_sha256: [u8; 32],
    outputs: Vec<AurBuiltCapabilityOutputRecord>,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct AurBuiltCapabilityOutputRecord {
    package: String,
    filename: String,
    archive_bytes: u64,
    installed_bytes: u64,
    build_package_count: usize,
    sha256: [u8; 32],
}

#[derive(Clone, Debug, Eq, Ord, PartialEq, PartialOrd)]
struct AurLifecycleCapability {
    package: String,
    version: String,
    archive_sha256: [u8; 32],
    install_script_sha256: Option<[u8; 32]>,
}

pub struct InstalledPackageCatalog {
    packages: Vec<InstalledPackage>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum PackageCompatibilityStatus {
    NotAnalyzed,
    BridgeEligible,
    ManagedOnly,
    Unsupported,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum PackageCompatibilityDiagnostic {
    None,
    NotCached,
    ForeignElf,
    NativeInAnyPackage,
    MalformedElf,
    IncompatiblePageSize,
    UnsupportedCommand,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
struct PackageArchiveAnalysis {
    capabilities: u8,
    elf_count: u32,
    command_count: u32,
    diagnostic: Option<PackageCompatibilityDiagnostic>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct PackageCompatibilityReview {
    package: String,
    resolution_sha256: [u8; 32],
}

struct InstalledPackage {
    name: String,
    version: String,
    explicitly_installed: bool,
    capabilities: u8,
    capabilities_analyzed: bool,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PackageCacheEntry {
    pub package: String,
    pub version: String,
    pub architecture: String,
    pub bytes: u64,
    pub artifacts: u32,
}

pub struct PackageCacheCatalog {
    entries: Vec<PackageCacheEntry>,
    total_bytes: u64,
}

struct PackageCacheArtifact {
    path: PathBuf,
    package: String,
    version: String,
    architecture: String,
    bytes: u64,
}

impl ToolOutput {
    pub fn as_bytes(&self) -> &[u8] {
        &self.bytes[..self.length]
    }

    pub fn as_str(&self) -> Result<&str, PackageRuntimeError> {
        std::str::from_utf8(self.as_bytes()).map_err(|_| PackageRuntimeError::InvalidManifest)
    }

    fn push(&mut self, bytes: &[u8]) -> Result<(), PackageRuntimeError> {
        let end = self
            .length
            .checked_add(bytes.len())
            .ok_or(PackageRuntimeError::OutputLimit)?;
        if end > self.bytes.len() {
            return Err(PackageRuntimeError::OutputLimit);
        }
        self.bytes[self.length..end].copy_from_slice(bytes);
        self.length = end;
        Ok(())
    }
}

impl fmt::Write for ToolOutput {
    fn write_str(&mut self, value: &str) -> fmt::Result {
        self.push(value.as_bytes()).map_err(|_| fmt::Error)
    }
}

impl PackageResolution {
    pub fn as_bytes(&self) -> &[u8] {
        &self.bytes
    }

    pub fn as_str(&self) -> Result<&str, PackageRuntimeError> {
        std::str::from_utf8(self.as_bytes()).map_err(|_| PackageRuntimeError::InvalidResolution)
    }
}

impl VerifiedPackageClosure {
    pub fn as_bytes(&self) -> &[u8] {
        &self.bytes
    }

    pub fn as_str(&self) -> Result<&str, PackageRuntimeError> {
        std::str::from_utf8(self.as_bytes()).map_err(|_| PackageRuntimeError::InvalidResolution)
    }
}

impl InstalledPackageCatalog {
    pub fn capabilities(&self, package: &str) -> Option<(u8, bool)> {
        self.packages
            .binary_search_by(|candidate| candidate.name.as_str().cmp(package))
            .ok()
            .map(|index| {
                let package = &self.packages[index];
                (package.capabilities, package.capabilities_analyzed)
            })
    }

    pub fn page(&self, offset: usize) -> Result<ToolOutput, PackageRuntimeError> {
        if offset > self.packages.len() {
            return Err(PackageRuntimeError::InvalidQuery);
        }
        let mut output = empty_tool_output();
        for package in self
            .packages
            .iter()
            .skip(offset)
            .take(INSTALLED_PACKAGE_PAGE_SIZE)
        {
            output.push(package.name.as_bytes())?;
            output.push(b"\t")?;
            output.push(package.version.as_bytes())?;
            output.push(if package.explicitly_installed {
                b"\t1\t"
            } else {
                b"\t0\t"
            })?;
            output.push(&[hex_nibble(package.capabilities)])?;
            output.push(if package.capabilities_analyzed {
                b"\t1\n"
            } else {
                b"\t0\n"
            })?;
        }
        Ok(output)
    }
}

impl PackageCacheCatalog {
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    pub const fn total_bytes(&self) -> u64 {
        self.total_bytes
    }

    pub fn entries(&self) -> &[PackageCacheEntry] {
        &self.entries
    }

    pub fn page(&self, offset: usize) -> Result<ToolOutput, PackageRuntimeError> {
        if offset > self.entries.len() {
            return Err(PackageRuntimeError::InvalidQuery);
        }
        let mut output = empty_tool_output();
        for entry in self
            .entries
            .iter()
            .skip(offset)
            .take(PACKAGE_CACHE_PAGE_SIZE)
        {
            writeln!(
                &mut output,
                "{}\t{}\t{}\t{}\t{}",
                entry.package, entry.version, entry.architecture, entry.bytes, entry.artifacts,
            )
            .map_err(|_| PackageRuntimeError::OutputLimit)?;
        }
        Ok(output)
    }
}

impl desktop::DesktopCatalog {
    pub fn page(&self, offset: usize) -> Result<ToolOutput, PackageRuntimeError> {
        const HEADER_BUDGET: usize = 64;
        if offset > self.entries.len() {
            return Err(PackageRuntimeError::InvalidQuery);
        }
        let mut next = offset;
        let mut body_bytes = 0_usize;
        for entry in self.entries.iter().skip(offset) {
            let record_bytes = desktop_record_bytes(entry)?;
            if body_bytes
                .checked_add(record_bytes)
                .is_none_or(|length| length > MAX_TOOL_OUTPUT_BYTES - HEADER_BUDGET)
            {
                break;
            }
            body_bytes += record_bytes;
            next += 1;
        }
        if next == offset && offset < self.entries.len() {
            return Err(PackageRuntimeError::OutputLimit);
        }

        let mut output = empty_tool_output();
        let header = format!(
            "D3\t{next}\t{}\t{}\t{}\t{}\n",
            self.entries.len(),
            self.examined,
            self.rejected,
            u8::from(self.truncated),
        );
        output.push(header.as_bytes())?;
        for entry in self.entries.iter().take(next).skip(offset) {
            push_desktop_record(&mut output, entry)?;
        }
        Ok(output)
    }
}

fn desktop_record_bytes(entry: &desktop::DesktopEntry) -> Result<usize, PackageRuntimeError> {
    let mut length = entry
        .desktop_id
        .len()
        .checked_add(entry.name.len())
        .and_then(|value| value.checked_add(entry.executable.len()))
        .and_then(|value| value.checked_add(entry.icon.as_ref().map_or(0, String::len)))
        .and_then(|value| value.checked_add(entry.try_exec.as_ref().map_or(0, String::len)))
        .and_then(|value| value.checked_add(entry.source_package.as_ref().map_or(0, String::len)))
        .and_then(|value| {
            value.checked_add(entry.executable_package.as_ref().map_or(0, String::len))
        })
        .and_then(|value| value.checked_add(11))
        .ok_or(PackageRuntimeError::OutputLimit)?;
    for (index, argument) in entry.arguments.iter().enumerate() {
        length = length
            .checked_add(match argument {
                desktop::ExecArgument::Literal(value) => value.len() + 2,
                _ => 1,
            })
            .and_then(|value| value.checked_add(usize::from(index != 0)))
            .ok_or(PackageRuntimeError::OutputLimit)?;
    }
    for mime_type in &entry.mime_types {
        length = length
            .checked_add(mime_type.len() + 1)
            .ok_or(PackageRuntimeError::OutputLimit)?;
    }
    Ok(length)
}

fn push_desktop_record(
    output: &mut ToolOutput,
    entry: &desktop::DesktopEntry,
) -> Result<(), PackageRuntimeError> {
    output.push(entry.desktop_id.as_bytes())?;
    output.push(b"\t")?;
    output.push(entry.name.as_bytes())?;
    output.push(b"\t")?;
    output.push(entry.executable.as_bytes())?;
    output.push(if entry.terminal { b"\t1\t" } else { b"\t0\t" })?;
    if let Some(icon) = &entry.icon {
        output.push(icon.as_bytes())?;
    }
    output.push(b"\t")?;
    if let Some(try_exec) = &entry.try_exec {
        output.push(try_exec.as_bytes())?;
    }
    output.push(b"\t")?;
    for (index, argument) in entry.arguments.iter().enumerate() {
        if index != 0 {
            output.push(b"\x1f")?;
        }
        match argument {
            desktop::ExecArgument::Literal(value) => {
                output.push(b"L:")?;
                output.push(value.as_bytes())?;
            }
            desktop::ExecArgument::SingleFile => output.push(b"f")?,
            desktop::ExecArgument::MultipleFiles => output.push(b"F")?,
            desktop::ExecArgument::SingleUrl => output.push(b"u")?,
            desktop::ExecArgument::MultipleUrls => output.push(b"U")?,
            desktop::ExecArgument::Icon => output.push(b"i")?,
            desktop::ExecArgument::DisplayName => output.push(b"c")?,
            desktop::ExecArgument::DesktopFile => output.push(b"k")?,
        }
    }
    output.push(b"\t")?;
    for mime_type in &entry.mime_types {
        output.push(mime_type.as_bytes())?;
        output.push(b";")?;
    }
    output.push(b"\t")?;
    if let Some(source_package) = &entry.source_package {
        output.push(source_package.as_bytes())?;
    }
    output.push(b"\t")?;
    if let Some(executable_package) = &entry.executable_package {
        output.push(executable_package.as_bytes())?;
    }
    output.push(b"\n")
}

impl PackageRuntime {
    pub fn prepare(
        arch_root: &Path,
        native_root: &Path,
        manifest: &[u8],
        architecture: RepositoryArchitecture,
    ) -> Result<Self, PackageRuntimeError> {
        validate_absolute_path(arch_root)?;
        validate_absolute_path(native_root)?;
        if manifest.is_empty() || manifest.len() > MAX_MANIFEST_BYTES {
            return Err(PackageRuntimeError::InvalidManifest);
        }
        let manifest =
            std::str::from_utf8(manifest).map_err(|_| PackageRuntimeError::InvalidManifest)?;
        let mut lines = manifest.lines();
        if lines.next() != Some(MANIFEST_HEADER) {
            return Err(PackageRuntimeError::InvalidManifest);
        }

        let native_root = native_root.canonicalize()?;
        let metadata = fs::symlink_metadata(&native_root)?;
        if metadata.file_type().is_symlink() || !metadata.is_dir() {
            return Err(PackageRuntimeError::UnsafeEntry(native_root));
        }
        let alias_root = arch_root.join(ALIAS_DIRECTORY);

        let mut loader = None;
        let mut keyring = None;
        let mut ownertrust = None;
        let mut has_path_bridge = false;
        let mut has_gdk_pixbuf_library = false;
        let mut has_gdk_pixbuf_svg_library = false;
        let mut has_gdk_pixbuf_svg_loader = false;
        let mut has_gtk_settings = false;
        let mut has_qt_platform_theme = false;
        let mut has_qt_style = false;
        let mut has_qt_kde_config = false;
        let mut tools: [Option<PathBuf>; 5] = std::array::from_fn(|_| None);
        let mut entry_count = 0_usize;
        for (index, line) in manifest.lines().skip(1).enumerate() {
            if line.is_empty() {
                return Err(PackageRuntimeError::InvalidManifest);
            }
            entry_count = entry_count.saturating_add(1);
            if entry_count > MAX_MANIFEST_ENTRIES {
                return Err(PackageRuntimeError::InvalidManifest);
            }
            let entry = parse_entry(line)?;
            for previous in manifest.lines().skip(1).take(index) {
                if parse_entry(previous)?.logical == entry.logical {
                    return Err(PackageRuntimeError::DuplicateEntry);
                }
            }
            let source = verified_source(&native_root, entry.packaged, entry.size)?;
            match entry.role {
                "loader" if entry.logical == "@loader" => loader = Some(source),
                "keyring" if entry.logical == "@keyring" => keyring = Some(source),
                "ownertrust" if entry.logical == "@ownertrust" => ownertrust = Some(source),
                "tool" => {
                    let tool = tool_from_logical(entry.logical)
                        .ok_or(PackageRuntimeError::InvalidManifest)?;
                    tools[tool.index()] = Some(source);
                }
                "library" if !entry.logical.starts_with('@') => {
                    has_path_bridge |= entry.logical == PATH_BRIDGE_NAME;
                    has_gdk_pixbuf_library |= entry.logical == GDK_PIXBUF_LIBRARY;
                    has_gdk_pixbuf_svg_library |= entry.logical == GDK_PIXBUF_SVG_LIBRARY;
                    has_gdk_pixbuf_svg_loader |= entry.logical == GDK_PIXBUF_SVG_LOADER;
                    has_gtk_settings |= entry.logical == GTK_SETTINGS_LIBRARY;
                    has_qt_platform_theme |= entry.logical == QT_PLATFORM_THEME_LIBRARY;
                    has_qt_style |= entry.logical == QT_STYLE_LIBRARY;
                    has_qt_kde_config |= entry.logical == QT_KDE_CONFIG_LIBRARY;
                }
                _ => return Err(PackageRuntimeError::InvalidManifest),
            }
        }
        if entry_count == 0 {
            return Err(PackageRuntimeError::InvalidManifest);
        }
        let loader = loader.ok_or(PackageRuntimeError::MissingEntry("@loader"))?;
        let keyring = keyring.ok_or(PackageRuntimeError::MissingEntry("@keyring"))?;
        let ownertrust = ownertrust.ok_or(PackageRuntimeError::MissingEntry("@ownertrust"))?;
        let verification_source_state =
            verification_source_state(&native_root, &keyring, &ownertrust)?;
        if !has_path_bridge {
            return Err(PackageRuntimeError::MissingEntry(PATH_BRIDGE_NAME));
        }
        let gdk_pixbuf_compatibility = [
            has_gdk_pixbuf_library,
            has_gdk_pixbuf_svg_library,
            has_gdk_pixbuf_svg_loader,
        ];
        if gdk_pixbuf_compatibility.iter().any(|present| *present)
            && !gdk_pixbuf_compatibility.iter().all(|present| *present)
        {
            return Err(PackageRuntimeError::InvalidManifest);
        }
        let qt_compatibility = [has_qt_platform_theme, has_qt_style, has_qt_kde_config];
        if qt_compatibility.iter().any(|present| *present)
            && !qt_compatibility.iter().all(|present| *present)
        {
            return Err(PackageRuntimeError::InvalidManifest);
        }
        if tools[PackageTool::Pacman.index()].is_none() {
            return Err(PackageRuntimeError::MissingEntry("@pacman"));
        }

        prepare_alias_directory(&alias_root)?;
        for line in manifest.lines().skip(1) {
            let entry = parse_entry(line)?;
            if entry.role == "library" {
                let source = verified_source(&native_root, entry.packaged, entry.size)?;
                symlink(&source, alias_root.join(entry.logical))?;
            } else if entry.role == "tool" && matches!(entry.logical, "@gpg" | "@gpgconf") {
                let source = verified_source(&native_root, entry.packaged, entry.size)?;
                symlink(
                    &source,
                    alias_root.join(
                        entry
                            .logical
                            .strip_prefix('@')
                            .ok_or(PackageRuntimeError::InvalidManifest)?,
                    ),
                )?;
            }
        }
        let gdk_pixbuf_module_file = if has_gdk_pixbuf_svg_loader {
            Some(prepare_gdk_pixbuf_module_file(arch_root, &alias_root)?)
        } else {
            None
        };
        let gtk_settings_module = has_gtk_settings.then(|| alias_root.join(GTK_SETTINGS_LIBRARY));
        let qt_plugin_root = if qt_compatibility.iter().all(|present| *present) {
            Some(prepare_toolkit_plugin_directory(arch_root, &alias_root)?)
        } else {
            None
        };

        let pacman_config = arch_root.join(PACMAN_CONFIG_FILE);
        publish_regular_file(
            &pacman_config,
            &arch_root.join(PACMAN_CONFIG_TEMP_FILE),
            architecture.pacman_config(),
        )?;
        let aur_pacman_config = architecture.aur_pacman_config();
        if aur_pacman_config.is_empty() {
            return Err(PackageRuntimeError::InvalidManifest);
        }
        publish_regular_file(
            &arch_root.join(AUR_PACMAN_CONFIG_FILE),
            &arch_root.join(AUR_PACMAN_CONFIG_TEMP_FILE),
            &aur_pacman_config,
        )?;
        let mut library_path = alias_root.as_os_str().to_os_string();
        library_path.push(":");
        library_path.push(native_root.as_os_str());
        library_path.push(":");
        library_path.push(arch_root.join("usr/lib").as_os_str());
        // Arch's libpulse uses an absolute /usr/lib/pulseaudio RUNPATH for
        // libpulsecommon. The Android host cannot resolve that root-absolute
        // path, so expose the standard Arch system-library directory through
        // the verified loader just like /usr/lib itself.
        library_path.push(":");
        library_path.push(arch_root.join("usr/lib/pulseaudio").as_os_str());
        let mut executable_path = alias_root.as_os_str().to_os_string();
        executable_path.push(":");
        executable_path.push(arch_root.join("usr/bin").as_os_str());
        let path_bridge = alias_root.join(PATH_BRIDGE_NAME);
        let runtime = Self {
            arch_root: arch_root.to_path_buf(),
            native_root,
            alias_root,
            pacman_config,
            architecture,
            loader,
            keyring,
            ownertrust,
            verification_source_state,
            path_bridge,
            tools,
            library_path,
            executable_path,
            gdk_pixbuf_module_file,
            gtk_settings_module,
            qt_plugin_root,
            compatibility_analysis: Arc::new(Mutex::new(())),
            compatibility_review: Arc::new(Mutex::new(None)),
        };
        if runtime.read_pending_mutation()?.is_none() {
            runtime.clear_orphaned_removal_repair()?;
            runtime.recover_pending_install_reasons()?;
        }
        Ok(runtime)
    }

    pub fn run(
        &self,
        tool: PackageTool,
        arguments: &[&str],
    ) -> Result<ToolOutput, PackageRuntimeError> {
        self.run_with_timeout(tool, arguments, COMMAND_TIMEOUT)
    }

    pub fn discover_shells(&self) -> Result<ToolOutput, PackageRuntimeError> {
        let path = self.arch_root.join(SHELLS_FILE);
        let metadata = fs::symlink_metadata(&path)?;
        if metadata.file_type().is_symlink()
            || !metadata.is_file()
            || metadata.permissions().mode() & 0o022 != 0
            || metadata.len() == 0
            || metadata.len() > SHELLS_FILE_LIMIT
        {
            return Err(PackageRuntimeError::UnsafeEntry(path));
        }
        let source = fs::read_to_string(&path)?;
        if source.len() as u64 != metadata.len() {
            return Err(PackageRuntimeError::InvalidManifest);
        }
        let environment = self.command_environment()?;
        let mut output = ToolOutput {
            bytes: [0; MAX_TOOL_OUTPUT_BYTES],
            length: 0,
        };
        for (id, label, command, arguments, paths) in [
            (
                "bash",
                "Bash",
                "bash",
                "--noprofile\t--noediting",
                ["/bin/bash", "/usr/bin/bash"],
            ),
            (
                "sh",
                "POSIX shell",
                "sh",
                "--noprofile\t--noediting",
                ["/bin/sh", "/usr/bin/sh"],
            ),
        ] {
            let declared = source.lines().any(|line| {
                let line = line.trim();
                !line.starts_with('#') && paths.contains(&line)
            });
            if declared && environment.command_available(command)? {
                output.push(format!("{id}\t{label}\t{command}\t{arguments}\n").as_bytes())?;
            }
        }
        Ok(output)
    }

    pub fn ensure_system_trust(&self) -> Result<bool, PackageRuntimeError> {
        self.materialize_system_trust(false)
    }

    fn refresh_system_trust(&self) -> Result<bool, PackageRuntimeError> {
        self.materialize_system_trust(true)
    }

    fn materialize_system_trust(&self, force: bool) -> Result<bool, PackageRuntimeError> {
        if !force && system_trust_bundle_ready(&self.arch_root) {
            return Ok(false);
        }
        let source = self.arch_root.join(SYSTEM_TRUST_SOURCE);
        let Ok(source_metadata) = fs::symlink_metadata(&source) else {
            return Ok(false);
        };
        if source_metadata.file_type().is_symlink()
            || !source_metadata.is_file()
            || source_metadata.permissions().mode() & 0o022 != 0
            || source_metadata.len() == 0
            || source_metadata.len() > SYSTEM_TRUST_BUNDLE_LIMIT
        {
            return Err(PackageRuntimeError::UnsafeEntry(source));
        }
        let environment = self.command_environment()?;
        // `update-ca-trust` is a Bash script rather than a self-contained
        // executable.  Treat the adapter as unavailable unless every external
        // command used by its normal extraction path is installed.  A minimal
        // Archphene root can legitimately have ca-certificates and p11-kit
        // before coreutils/findutils; starting the script in that state would
        // fail after the package transaction had already committed and falsely
        // report the package install itself as failed.
        for command in ["update-ca-trust", "trust", "mkdir", "ln", "find"] {
            if !environment.command_available(command)? {
                return Ok(false);
            }
        }
        let output = environment.run_as_root("update-ca-trust", &[])?;
        if output.exit_code() != 0 {
            let mut diagnostic = empty_tool_output();
            diagnostic.push(output.as_bytes())?;
            return Err(PackageRuntimeError::ToolFailed(
                output.exit_code(),
                Box::new(diagnostic),
            ));
        }
        if !system_trust_bundle_ready(&self.arch_root) {
            return Err(PackageRuntimeError::InvalidPayload);
        }
        Ok(true)
    }

    pub fn begin_catalog_download(
        &self,
        repository: Repository,
    ) -> Result<(CatalogDownload, &'static str), PackageRuntimeError> {
        let directory = self.arch_root.join(CATALOG_DIRECTORY);
        let metadata = fs::symlink_metadata(&directory)?;
        if metadata.file_type().is_symlink() || !metadata.is_dir() {
            return Err(PackageRuntimeError::UnsafeEntry(directory));
        }
        let temporary = directory.join(repository.temporary_file_name());
        prepare_output_path(&temporary)?;
        let file = OpenOptions::new()
            .create_new(true)
            .read(true)
            .write(true)
            .open(&temporary)?;
        file.set_permissions(fs::Permissions::from_mode(0o600))?;
        Ok((
            CatalogDownload {
                repository,
                file,
                temporary,
                destination: directory.join(repository.file_name()),
                active: true,
            },
            self.architecture.catalog_url(repository),
        ))
    }

    pub fn catalogs_ready(&self) -> bool {
        [Repository::Core, Repository::Extra]
            .into_iter()
            .all(|repository| {
                catalog_file_ready(
                    &self
                        .arch_root
                        .join(CATALOG_DIRECTORY)
                        .join(repository.file_name()),
                    repository.size_limit(),
                )
            })
    }

    pub fn search(&self, query: &str) -> Result<ToolOutput, PackageRuntimeError> {
        if !valid_search_query(query) || !self.catalogs_ready() {
            return Err(if valid_search_query(query) {
                PackageRuntimeError::InvalidCatalog
            } else {
                PackageRuntimeError::InvalidQuery
            });
        }
        let config = self
            .pacman_config
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let root = self
            .arch_root
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let database_path = self.arch_root.join("var/lib/pacman");
        let database = database_path
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let exact_pattern = exact_search_pattern(query);
        let exact_raw = match self.run(
            PackageTool::Pacman,
            &[
                "--config",
                config,
                "--root",
                root,
                "--dbpath",
                database,
                "-Ss",
                &exact_pattern,
            ],
        ) {
            Ok(output) => Some(output),
            Err(PackageRuntimeError::ToolFailed(1, output)) if output.as_bytes().is_empty() => None,
            Err(error) => return Err(error),
        };
        if let Some(raw) = exact_raw {
            let mut output = empty_tool_output();
            let mut count = 0_usize;
            append_search_output_pass(raw.as_str()?, query, true, &mut output, &mut count)?;
            if count != 0 {
                return self.annotate_search_updates(output, config, root, database);
            }
        }
        let raw = match self.run(
            PackageTool::Pacman,
            &[
                "--config", config, "--root", root, "--dbpath", database, "-Ss", query,
            ],
        ) {
            Ok(output) => output,
            Err(PackageRuntimeError::ToolFailed(1, output)) if output.as_bytes().is_empty() => {
                return Ok(ToolOutput {
                    bytes: [0; MAX_TOOL_OUTPUT_BYTES],
                    length: 0,
                });
            }
            Err(error) => return Err(error),
        };
        self.annotate_search_updates(
            parse_search_output(raw.as_str()?, query)?,
            config,
            root,
            database,
        )
    }

    fn annotate_search_updates(
        &self,
        output: ToolOutput,
        config: &str,
        root: &str,
        database: &str,
    ) -> Result<ToolOutput, PackageRuntimeError> {
        let differing = differing_search_packages(&output)?;
        if differing.is_empty() {
            return Ok(output);
        }
        let mut arguments = Vec::with_capacity(7 + differing.len());
        arguments.extend([
            "--config", config, "--root", root, "--dbpath", database, "-Quq",
        ]);
        arguments.extend(differing.iter().map(String::as_str));
        let raw_updates = match self.run(PackageTool::Pacman, &arguments) {
            Ok(output) => output,
            Err(PackageRuntimeError::ToolFailed(1, diagnostic))
                if diagnostic.as_bytes().is_empty() =>
            {
                return Ok(output);
            }
            Err(error) => return Err(error),
        };
        let updates = parse_quiet_update_names(raw_updates.as_str()?, &differing)?;
        annotate_search_update_names(output, &updates)
    }

    pub fn resolve(&self, package: &str) -> Result<PackageResolution, PackageRuntimeError> {
        if package == BASE_PACKAGE {
            self.resolve_targets(&[BASE_PACKAGE])
        } else {
            self.resolve_targets(&[BASE_PACKAGE, package])
        }
    }

    pub fn cached_package_compatibility(
        &self,
        package: &str,
    ) -> Result<ToolOutput, PackageRuntimeError> {
        self.cached_package_compatibility_cancellable(
            package,
            &PackageCompatibilityCancellation::new(),
        )
    }

    pub fn cached_package_compatibility_cancellable(
        &self,
        package: &str,
        cancellation: &PackageCompatibilityCancellation,
    ) -> Result<ToolOutput, PackageRuntimeError> {
        if !safe_logical_name(package) {
            return Err(PackageRuntimeError::InvalidQuery);
        }
        cancellation.check()?;
        let _analysis = self
            .compatibility_analysis
            .lock()
            .map_err(|_| PackageRuntimeError::InvalidPayload)?;
        cancellation.check()?;
        self.clear_package_compatibility_review()?;
        let resolution = self.resolve(package)?;
        cancellation.check()?;
        let package_count = resolution.as_str()?.lines().count();
        if package_count == 0 || package_count > 512 {
            return Err(PackageRuntimeError::InvalidResolution);
        }
        for line in resolution.as_str()?.lines() {
            cancellation.check()?;
            let payload = parse_resolved_payload(line)?;
            if !self.cached_package_artifacts_present(&payload)? {
                return package_compatibility_output(
                    PackageCompatibilityStatus::NotAnalyzed,
                    0,
                    package_count,
                    0,
                    0,
                    PackageCompatibilityDiagnostic::NotCached,
                    None,
                );
            }
        }
        let page_size = rustix::param::page_size();
        let content_digest =
            self.package_compatibility_content_digest(&resolution, page_size, cancellation)?;
        cancellation.check()?;
        if let Some(output) = self.load_package_compatibility_cache(&content_digest)? {
            cancellation.check()?;
            if cached_compatibility_allows_mutation(&output)? {
                self.publish_package_compatibility_review(package, &resolution)?;
            }
            return Ok(output);
        }

        let mut target_found = false;
        let mut target_capabilities = 0_u8;
        let mut target_commands = 0_u32;
        let mut closure_elfs = 0_u32;
        let mut diagnostic = None;
        let mut diagnostic_package = None;
        for line in resolution.as_str()?.lines() {
            cancellation.check()?;
            let payload = parse_resolved_payload(line)?;
            let target = payload.name == package;
            let analysis = (|| {
                cancellation.check()?;
                self.verify_package(
                    payload.filename,
                    payload.name,
                    payload.version,
                    payload.size,
                )?;
                cancellation.check()?;
                let archive = self
                    .arch_root
                    .join(PACKAGE_CACHE_DIRECTORY)
                    .join(payload.filename);
                let mut file = OpenOptions::new()
                    .read(true)
                    .custom_flags(O_NOFOLLOW | O_CLOEXEC)
                    .open(&archive)?;
                let metadata = file.metadata()?;
                if !metadata.is_file() || metadata.len() != payload.size {
                    return Err(PackageRuntimeError::InvalidPayload);
                }
                inspect_package_archive_cancellable(
                    &mut file,
                    payload.filename,
                    self.architecture,
                    page_size,
                    target,
                    cancellation,
                )
            })()
            .map_err(|error| {
                PackageRuntimeError::CompatibilityReview(payload.name.to_owned(), Box::new(error))
            })?;
            closure_elfs = closure_elfs
                .checked_add(analysis.elf_count)
                .ok_or(PackageRuntimeError::OutputLimit)?;
            if diagnostic.is_none() {
                diagnostic = analysis.diagnostic;
                if diagnostic.is_some() {
                    diagnostic_package = Some(payload.name);
                }
            }
            if target {
                if target_found {
                    return Err(PackageRuntimeError::InvalidResolution);
                }
                target_found = true;
                target_capabilities = analysis.capabilities;
                target_commands = analysis.command_count;
            }
        }
        if !target_found {
            return Err(PackageRuntimeError::MissingTarget);
        }
        let status = if diagnostic.is_some() {
            PackageCompatibilityStatus::Unsupported
        } else if target_capabilities
            & (PACKAGE_CAPABILITY_GRAPHICAL | PACKAGE_CAPABILITY_COMMAND_LINE)
            != 0
        {
            PackageCompatibilityStatus::BridgeEligible
        } else {
            PackageCompatibilityStatus::ManagedOnly
        };
        let output = package_compatibility_output(
            status,
            target_capabilities,
            package_count,
            closure_elfs,
            target_commands,
            diagnostic.unwrap_or(PackageCompatibilityDiagnostic::None),
            diagnostic_package,
        )?;
        cancellation.check()?;
        self.publish_package_compatibility_cache(&content_digest, &output)?;
        cancellation.check()?;
        if matches!(
            status,
            PackageCompatibilityStatus::BridgeEligible | PackageCompatibilityStatus::ManagedOnly
        ) {
            self.publish_package_compatibility_review(package, &resolution)?;
        }
        Ok(output)
    }

    fn package_compatibility_content_digest(
        &self,
        resolution: &PackageResolution,
        page_size: usize,
        cancellation: &PackageCompatibilityCancellation,
    ) -> Result<[u8; 32], PackageRuntimeError> {
        if !page_size.is_power_of_two() || !(4096..=64 * 1024).contains(&page_size) {
            return Err(PackageRuntimeError::InvalidPayload);
        }
        let mut hasher = Sha256::new();
        hasher.update(PACKAGE_COMPATIBILITY_CACHE_DOMAIN);
        hasher.update(self.architecture.package_architecture().as_bytes());
        hasher.update([0]);
        hasher.update((self.verification_source_state.len() as u64).to_le_bytes());
        hasher.update(self.verification_source_state.as_bytes());
        hasher.update((page_size as u64).to_le_bytes());
        hasher.update((resolution.as_bytes().len() as u64).to_le_bytes());
        hasher.update(resolution.as_bytes());
        for line in resolution.as_str()?.lines() {
            cancellation.check()?;
            let payload = parse_resolved_payload(line)?;
            let package = self
                .arch_root
                .join(PACKAGE_CACHE_DIRECTORY)
                .join(payload.filename);
            hash_package_compatibility_file(
                &package,
                payload.size,
                PACKAGE_ARCHIVE_LIMIT,
                b'P',
                &mut hasher,
                cancellation,
            )?;
            let signature = self
                .arch_root
                .join(PACKAGE_CACHE_DIRECTORY)
                .join(format!("{}.sig", payload.filename));
            hash_package_compatibility_file(
                &signature,
                0,
                PACKAGE_SIGNATURE_LIMIT,
                b'S',
                &mut hasher,
                cancellation,
            )?;
        }
        cancellation.check()?;
        Ok(hasher.finalize().into())
    }

    fn load_package_compatibility_cache(
        &self,
        content_digest: &[u8; 32],
    ) -> Result<Option<ToolOutput>, PackageRuntimeError> {
        let directory = self.arch_root.join(PACKAGE_COMPATIBILITY_CACHE_DIRECTORY);
        let metadata = match fs::symlink_metadata(&directory) {
            Ok(metadata) => metadata,
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
            Err(error) => return Err(PackageRuntimeError::Io(error)),
        };
        if metadata.file_type().is_symlink() || !metadata.is_dir() {
            return Err(PackageRuntimeError::UnsafeEntry(directory));
        }
        let path = directory.join(hex_sha256(content_digest));
        let metadata = match fs::symlink_metadata(&path) {
            Ok(metadata) => metadata,
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
            Err(error) => return Err(PackageRuntimeError::Io(error)),
        };
        if metadata.file_type().is_symlink() || !metadata.is_file() {
            return Err(PackageRuntimeError::UnsafeEntry(path));
        }
        if metadata.len() == 0 || metadata.len() > PACKAGE_COMPATIBILITY_CACHE_RECORD_LIMIT {
            fs::remove_file(path)?;
            return Ok(None);
        }
        let mut bytes = Vec::with_capacity(metadata.len() as usize);
        let file = OpenOptions::new()
            .read(true)
            .custom_flags(O_NOFOLLOW | O_CLOEXEC)
            .open(&path)?;
        let opened = file.metadata()?;
        if !opened.is_file() || opened.len() != metadata.len() {
            return Err(PackageRuntimeError::SizeMismatch);
        }
        file.take(PACKAGE_COMPATIBILITY_CACHE_RECORD_LIMIT + 1)
            .read_to_end(&mut bytes)?;
        if bytes.len() as u64 != metadata.len() {
            return Err(PackageRuntimeError::SizeMismatch);
        }
        match decode_package_compatibility_cache_record(content_digest, &bytes) {
            Ok(output) => Ok(Some(output)),
            Err(PackageRuntimeError::InvalidPayload) => {
                fs::remove_file(path)?;
                Ok(None)
            }
            Err(error) => Err(error),
        }
    }

    fn publish_package_compatibility_cache(
        &self,
        content_digest: &[u8; 32],
        output: &ToolOutput,
    ) -> Result<(), PackageRuntimeError> {
        canonical_cached_compatibility(output.as_bytes())?;
        let directory = self.arch_root.join(PACKAGE_COMPATIBILITY_CACHE_DIRECTORY);
        prepare_private_directory(&directory)?;
        prune_package_compatibility_cache(&directory, content_digest)?;
        let name = hex_sha256(content_digest);
        let destination = directory.join(&name);
        let temporary = directory.join(format!(".{name}.tmp"));
        let record = encode_package_compatibility_cache_record(content_digest, output)?;
        publish_regular_file(&destination, &temporary, &record)?;
        File::open(directory)?.sync_all()?;
        Ok(())
    }

    pub fn resolve_targets(
        &self,
        packages: &[&str],
    ) -> Result<PackageResolution, PackageRuntimeError> {
        let database_path = self.arch_root.join("var/lib/pacman");
        self.resolve_targets_with_database(packages, &database_path)
    }

    pub fn resolve_targets_for_fresh_root(
        &self,
        packages: &[&str],
    ) -> Result<PackageResolution, PackageRuntimeError> {
        let database_path = self.prepare_fresh_resolution_database()?;
        let result = self.resolve_targets_with_database(packages, &database_path);
        let cleanup = fs::remove_dir_all(&database_path);
        match (result, cleanup) {
            (Ok(resolution), Ok(())) => Ok(resolution),
            (Err(error), _) => Err(error),
            (Ok(_), Err(error)) => Err(PackageRuntimeError::Io(error)),
        }
    }

    pub fn partition_targets_for_fresh_root(
        &self,
        packages: &[&str],
    ) -> Result<RepositoryTargetPartition, PackageRuntimeError> {
        validate_resolution_targets(packages, self.catalogs_ready())?;
        let database_path = self.prepare_fresh_resolution_database()?;
        let result = partition_repository_targets(packages, |targets| {
            self.resolve_targets_with_database(targets, &database_path)
        });
        let cleanup = fs::remove_dir_all(&database_path);
        match (result, cleanup) {
            (Ok(partition), Ok(())) => Ok(partition),
            (Err(error), _) => Err(error),
            (Ok(_), Err(error)) => Err(PackageRuntimeError::Io(error)),
        }
    }

    fn resolve_targets_with_database(
        &self,
        packages: &[&str],
        database_path: &Path,
    ) -> Result<PackageResolution, PackageRuntimeError> {
        validate_resolution_targets(packages, self.catalogs_ready())?;
        let config = self
            .pacman_config
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let root = self
            .arch_root
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let database = database_path
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let mut arguments = Vec::with_capacity(12 + packages.len());
        arguments.extend_from_slice(&[
            "--config",
            config,
            "--root",
            root,
            "--dbpath",
            database,
            "-S",
            "--print",
            "--print-format",
            "%r\t%n\t%v\t%f\t%l\t%s",
        ]);
        arguments.extend_from_slice(packages);
        let raw = self.run_bytes_with_timeout(
            PackageTool::Pacman,
            &arguments,
            COMMAND_TIMEOUT,
            MAX_PACKAGE_RESOLUTION_BYTES,
            true,
        )?;
        let raw = std::str::from_utf8(&raw).map_err(|_| PackageRuntimeError::InvalidResolution)?;
        parse_resolution_output(raw, packages, self.architecture)
    }

    fn cached_package_artifacts_present(
        &self,
        payload: &ResolvedPayload<'_>,
    ) -> Result<bool, PackageRuntimeError> {
        let package = self
            .arch_root
            .join(PACKAGE_CACHE_DIRECTORY)
            .join(payload.filename);
        let signature = self
            .arch_root
            .join(PACKAGE_CACHE_DIRECTORY)
            .join(format!("{}.sig", payload.filename));
        for (path, package_payload) in [(&package, true), (&signature, false)] {
            let metadata = match fs::symlink_metadata(path) {
                Ok(metadata) => metadata,
                Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(false),
                Err(error) => return Err(PackageRuntimeError::Io(error)),
            };
            if metadata.file_type().is_symlink()
                || !metadata.is_file()
                || metadata.len() == 0
                || package_payload && metadata.len() != payload.size
                || !package_payload && metadata.len() > PACKAGE_SIGNATURE_LIMIT
            {
                return Err(PackageRuntimeError::InvalidPayload);
            }
        }
        Ok(true)
    }

    fn clear_package_compatibility_review(&self) -> Result<(), PackageRuntimeError> {
        self.compatibility_review
            .lock()
            .map_err(|_| PackageRuntimeError::InvalidPayload)?
            .take();
        Ok(())
    }

    fn publish_package_compatibility_review(
        &self,
        package: &str,
        resolution: &PackageResolution,
    ) -> Result<(), PackageRuntimeError> {
        let review = PackageCompatibilityReview {
            package: package.to_owned(),
            resolution_sha256: Sha256::digest(resolution.as_bytes()).into(),
        };
        self.compatibility_review
            .lock()
            .map_err(|_| PackageRuntimeError::InvalidPayload)?
            .replace(review);
        Ok(())
    }

    fn consume_package_compatibility_review(
        &self,
        package: &str,
        resolution: &PackageResolution,
    ) -> Result<(), PackageRuntimeError> {
        let expected = PackageCompatibilityReview {
            package: package.to_owned(),
            resolution_sha256: Sha256::digest(resolution.as_bytes()).into(),
        };
        let review = self
            .compatibility_review
            .lock()
            .map_err(|_| PackageRuntimeError::InvalidPayload)?
            .take();
        if review.as_ref() != Some(&expected) {
            return Err(PackageRuntimeError::CompatibilityReviewRequired);
        }
        Ok(())
    }

    fn prepare_fresh_resolution_database(&self) -> Result<PathBuf, PackageRuntimeError> {
        let database = self.arch_root.join(AUR_BUILD_DATABASE_DIRECTORY);
        match fs::symlink_metadata(&database) {
            Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_dir() => {
                return Err(PackageRuntimeError::UnsafeEntry(database));
            }
            Ok(_) => fs::remove_dir_all(&database)?,
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => return Err(PackageRuntimeError::Io(error)),
        }
        let sync = database.join("sync");
        let result = (|| {
            fs::create_dir(&database)?;
            fs::set_permissions(&database, fs::Permissions::from_mode(0o700))?;
            fs::create_dir(&sync)?;
            fs::set_permissions(&sync, fs::Permissions::from_mode(0o700))?;
            for repository in [Repository::Core, Repository::Extra] {
                let source = self
                    .arch_root
                    .join(CATALOG_DIRECTORY)
                    .join(repository.file_name());
                let source_metadata = fs::symlink_metadata(&source)?;
                if source_metadata.file_type().is_symlink()
                    || !source_metadata.is_file()
                    || source_metadata.len() == 0
                    || source_metadata.len() > repository.size_limit()
                {
                    return Err(PackageRuntimeError::InvalidCatalog);
                }
                let mut input = OpenOptions::new()
                    .read(true)
                    .custom_flags(O_NOFOLLOW | O_CLOEXEC)
                    .open(&source)?;
                let opened = input.metadata()?;
                if !opened.is_file() || opened.len() != source_metadata.len() {
                    return Err(PackageRuntimeError::InvalidCatalog);
                }
                let destination = sync.join(repository.file_name());
                let mut output = OpenOptions::new()
                    .create_new(true)
                    .write(true)
                    .mode(0o600)
                    .open(&destination)?;
                let copied = io::copy(&mut input, &mut output)?;
                if copied != opened.len() {
                    return Err(PackageRuntimeError::InvalidCatalog);
                }
                output.sync_all()?;
            }
            File::open(&sync)?.sync_all()?;
            File::open(&database)?.sync_all()?;
            Ok(())
        })();
        if result.is_err() {
            let _ = fs::remove_dir_all(&database);
        }
        result.map(|()| database)
    }

    pub fn installed_version(&self, package: &str) -> Result<ToolOutput, PackageRuntimeError> {
        if !safe_logical_name(package) {
            return Err(PackageRuntimeError::InvalidQuery);
        }
        let config = self
            .pacman_config
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let root = self
            .arch_root
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let database_path = self.arch_root.join("var/lib/pacman");
        let database = database_path
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        match self.run_with_timeout(
            PackageTool::Pacman,
            &[
                "--config", config, "--root", root, "--dbpath", database, "-Q", package,
            ],
            COMMAND_TIMEOUT,
        ) {
            Ok(output) => parse_installed_version(output.as_str()?, package),
            Err(PackageRuntimeError::ToolFailed(1, output))
                if missing_package_query(output.as_str()?, package) =>
            {
                Ok(empty_tool_output())
            }
            Err(error) => Err(error),
        }
    }

    pub fn available_version_state(
        &self,
        package: &str,
    ) -> Result<ToolOutput, PackageRuntimeError> {
        if !safe_logical_name(package) || !self.catalogs_ready() {
            return Err(if safe_logical_name(package) {
                PackageRuntimeError::InvalidCatalog
            } else {
                PackageRuntimeError::InvalidQuery
            });
        }
        let config = self
            .pacman_config
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let root = self
            .arch_root
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let database_path = self.arch_root.join("var/lib/pacman");
        let database = database_path
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let state = match self.run(
            PackageTool::Pacman,
            &[
                "--config", config, "--root", root, "--dbpath", database, "-Quq", package,
            ],
        ) {
            Ok(output) => parse_exact_quiet_update(output.as_str()?, package)?,
            Err(PackageRuntimeError::ToolFailed(1, output)) if output.as_bytes().is_empty() => {
                false
            }
            Err(error) => return Err(error),
        };
        let mut output = empty_tool_output();
        output.push(if state { b"update" } else { b"different" })?;
        Ok(output)
    }

    pub fn aur_candidate_state(
        &self,
        package: &str,
        candidate_version: &str,
    ) -> Result<ToolOutput, PackageRuntimeError> {
        if !safe_logical_name(package) || !safe_package_version(candidate_version) {
            return Err(PackageRuntimeError::InvalidQuery);
        }
        let installed = self.installed_version(package)?;
        if installed.as_bytes().is_empty() {
            let mut output = empty_tool_output();
            output.push(b"available\t")?;
            return Ok(output);
        }
        let installed_version = installed.as_str()?;
        let state = if installed_version == candidate_version {
            "installed"
        } else {
            let config = self
                .pacman_config
                .to_str()
                .ok_or(PackageRuntimeError::InvalidPath)?;
            let root = self
                .arch_root
                .to_str()
                .ok_or(PackageRuntimeError::InvalidPath)?;
            let database_path = self.arch_root.join("var/lib/pacman");
            let database = database_path
                .to_str()
                .ok_or(PackageRuntimeError::InvalidPath)?;
            let requirement = format!("{package}>{candidate_version}");
            match self.run_with_timeout(
                PackageTool::Pacman,
                &[
                    "--config",
                    config,
                    "--root",
                    root,
                    "--dbpath",
                    database,
                    "-T",
                    &requirement,
                ],
                COMMAND_TIMEOUT,
            ) {
                Ok(output) if output.as_bytes().is_empty() => "different",
                Err(PackageRuntimeError::ToolFailed(127, output))
                    if exact_missing_dependency(output.as_str()?, &requirement) =>
                {
                    "update"
                }
                Ok(_) | Err(PackageRuntimeError::ToolFailed(127, _)) => {
                    return Err(PackageRuntimeError::InvalidResolution);
                }
                Err(error) => return Err(error),
            }
        };
        let mut output = empty_tool_output();
        output.push(state.as_bytes())?;
        output.push(b"\t")?;
        output.push(installed.as_bytes())?;
        Ok(output)
    }

    pub fn installed_origin(&self, package: &str) -> Result<ToolOutput, PackageRuntimeError> {
        if !safe_logical_name(package) {
            return Err(PackageRuntimeError::InvalidQuery);
        }
        let local = self.arch_root.join("var/lib/pacman/local");
        let metadata = fs::symlink_metadata(&local)?;
        if metadata.file_type().is_symlink() || !metadata.is_dir() {
            return Err(PackageRuntimeError::UnsafeEntry(local));
        }
        let mut count = 0_usize;
        let mut matched = None;
        for entry in fs::read_dir(&local)? {
            count = count.saturating_add(1);
            if count > LOCAL_DATABASE_ENTRY_LIMIT {
                return Err(PackageRuntimeError::OutputLimit);
            }
            let entry = entry?;
            let path = entry.path();
            let metadata = fs::symlink_metadata(&path)?;
            if entry.file_name() == "ALPM_DB_VERSION"
                && metadata.is_file()
                && !metadata.file_type().is_symlink()
            {
                continue;
            }
            if metadata.file_type().is_symlink() || !metadata.is_dir() {
                return Err(PackageRuntimeError::UnsafeEntry(path));
            }
            let description = path.join("desc");
            let metadata = fs::symlink_metadata(&description)?;
            if metadata.file_type().is_symlink()
                || !metadata.is_file()
                || metadata.len() == 0
                || metadata.len() > LOCAL_DESCRIPTION_LIMIT
            {
                return Err(PackageRuntimeError::UnsafeEntry(description));
            }
            let file = OpenOptions::new()
                .read(true)
                .custom_flags(O_NOFOLLOW | O_CLOEXEC)
                .open(&description)?;
            let opened = file.metadata()?;
            if !opened.is_file() || opened.len() != metadata.len() {
                return Err(PackageRuntimeError::UnsafeEntry(description));
            }
            let mut contents =
                String::with_capacity(usize::try_from(metadata.len()).unwrap_or(4096).min(4096));
            file.take(LOCAL_DESCRIPTION_LIMIT + 1)
                .read_to_string(&mut contents)?;
            if u64::try_from(contents.len()).map_err(|_| PackageRuntimeError::OutputLimit)?
                != metadata.len()
            {
                return Err(PackageRuntimeError::SizeMismatch);
            }
            if local_description_field(&contents, "%NAME%")? != Some(package) {
                continue;
            }
            if matched.is_some() {
                return Err(PackageRuntimeError::InvalidResolution);
            }
            matched = Some(match local_description_field(&contents, "%VALIDATION%")? {
                Some("none") => "aur",
                Some("pgp") => "official",
                _ => return Err(PackageRuntimeError::InvalidResolution),
            });
        }
        let origin = matched.ok_or(PackageRuntimeError::NotInstalled)?;
        let mut output = empty_tool_output();
        output.push(origin.as_bytes())?;
        Ok(output)
    }

    pub fn installed_package_catalog(
        &self,
    ) -> Result<InstalledPackageCatalog, PackageRuntimeError> {
        let local = self.arch_root.join("var/lib/pacman/local");
        let metadata = match fs::symlink_metadata(&local) {
            Ok(metadata) => metadata,
            Err(error) if error.kind() == io::ErrorKind::NotFound => {
                return Ok(InstalledPackageCatalog {
                    packages: Vec::new(),
                });
            }
            Err(error) => return Err(PackageRuntimeError::Io(error)),
        };
        if metadata.file_type().is_symlink() || !metadata.is_dir() {
            return Err(PackageRuntimeError::UnsafeEntry(local));
        }

        let mut packages = Vec::new();
        let mut contents = String::with_capacity(4096);
        let mut files_total_bytes = 0_u64;
        for entry in fs::read_dir(&local)? {
            if packages.len() >= LOCAL_DATABASE_ENTRY_LIMIT {
                return Err(PackageRuntimeError::OutputLimit);
            }
            let entry = entry?;
            let entry_path = entry.path();
            let metadata = fs::symlink_metadata(&entry_path)?;
            if entry.file_name() == "ALPM_DB_VERSION"
                && metadata.is_file()
                && !metadata.file_type().is_symlink()
                && metadata.len() > 0
                && metadata.len() <= 64
            {
                continue;
            }
            if metadata.file_type().is_symlink() || !metadata.is_dir() {
                return Err(PackageRuntimeError::UnsafeEntry(entry_path));
            }
            let description = entry_path.join("desc");
            let metadata = fs::symlink_metadata(&description)?;
            if metadata.file_type().is_symlink()
                || !metadata.is_file()
                || metadata.len() == 0
                || metadata.len() > LOCAL_DESCRIPTION_LIMIT
            {
                return Err(PackageRuntimeError::UnsafeEntry(description));
            }
            contents.clear();
            File::open(&description)?
                .take(LOCAL_DESCRIPTION_LIMIT + 1)
                .read_to_string(&mut contents)?;
            if u64::try_from(contents.len()).map_err(|_| PackageRuntimeError::OutputLimit)?
                != metadata.len()
            {
                return Err(PackageRuntimeError::SizeMismatch);
            }
            let name = local_description_field(&contents, "%NAME%")?
                .filter(|name| safe_logical_name(name))
                .ok_or(PackageRuntimeError::InvalidResolution)?;
            let version = local_description_field(&contents, "%VERSION%")?
                .filter(|version| {
                    !version.is_empty()
                        && version.len() <= 128
                        && version
                            .bytes()
                            .all(|byte| !byte.is_ascii_whitespace() && !byte.is_ascii_control())
                })
                .ok_or(PackageRuntimeError::InvalidResolution)?;
            let explicitly_installed = match local_description_field(&contents, "%REASON%")? {
                None | Some("0") => true,
                Some("1") => false,
                Some(_) => return Err(PackageRuntimeError::InvalidResolution),
            };
            let capabilities = installed_package_capabilities(&entry_path, &mut files_total_bytes)?;
            packages.push(InstalledPackage {
                name: name.to_owned(),
                version: version.to_owned(),
                explicitly_installed,
                capabilities: capabilities.unwrap_or(0),
                capabilities_analyzed: capabilities.is_some(),
            });
        }
        packages.sort_unstable_by(|left, right| left.name.cmp(&right.name));
        if packages.windows(2).any(|pair| pair[0].name == pair[1].name) {
            return Err(PackageRuntimeError::InvalidResolution);
        }
        Ok(InstalledPackageCatalog { packages })
    }

    pub fn desktop_catalog(&self) -> Result<desktop::DesktopCatalog, PackageRuntimeError> {
        let mut catalog = desktop::discover_desktop_entries(&self.arch_root)?;
        self.attach_desktop_owners(&mut catalog)?;
        let installed = self.installed_package_catalog()?;
        catalog.entries.retain(|entry| {
            let Some(source_package) = entry.source_package.as_deref() else {
                return false;
            };
            installed
                .packages
                .binary_search_by(|package| package.name.as_str().cmp(source_package))
                .ok()
                .is_some_and(|index| installed.packages[index].explicitly_installed)
        });
        let mut profiler = elf_profile::IntegrationProfiler::new(&self.arch_root);
        for entry in &mut catalog.entries {
            let profile = profiler.profile(&entry.executable);
            entry.integration_topology = profile.topology;
            entry.integration_profiled = profile.profiled;
            entry.integration_complete = profile.complete;
        }
        Ok(catalog)
    }

    fn attach_desktop_owners(
        &self,
        catalog: &mut desktop::DesktopCatalog,
    ) -> Result<(), PackageRuntimeError> {
        if catalog.entries.is_empty() {
            return Ok(());
        }
        let local = self.arch_root.join("var/lib/pacman/local");
        let metadata = match fs::symlink_metadata(&local) {
            Ok(metadata) => metadata,
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(()),
            Err(error) => return Err(PackageRuntimeError::Io(error)),
        };
        if metadata.file_type().is_symlink() || !metadata.is_dir() {
            return Err(PackageRuntimeError::UnsafeEntry(local));
        }

        let mut targets = Vec::with_capacity(catalog.entries.len().saturating_mul(2));
        for (index, entry) in catalog.entries.iter().enumerate() {
            targets.push((
                format!("usr/share/applications/{}", entry.desktop_id).into_bytes(),
                index,
                false,
            ));
            let executable = entry
                .executable
                .strip_prefix('/')
                .ok_or(PackageRuntimeError::InvalidResolution)?;
            targets.push((executable.as_bytes().to_vec(), index, true));
        }
        targets.sort_unstable_by(|left, right| {
            left.0
                .cmp(&right.0)
                .then_with(|| left.1.cmp(&right.1))
                .then_with(|| left.2.cmp(&right.2))
        });
        let mut source_ambiguous = vec![false; catalog.entries.len()];
        let mut executable_ambiguous = vec![false; catalog.entries.len()];
        let mut directory_entries = 0_usize;
        let mut database_entries = 0_usize;
        let mut total_bytes = 0_u64;
        let directory = match fs::read_dir(&local) {
            Ok(directory) => directory,
            Err(error) => return Err(PackageRuntimeError::Io(error)),
        };
        for item in directory {
            directory_entries = directory_entries.saturating_add(1);
            if directory_entries > LOCAL_DATABASE_DIRECTORY_ENTRY_LIMIT {
                catalog.truncated = true;
                break;
            }
            let Ok(item) = item else {
                catalog.truncated = true;
                continue;
            };
            let path = item.path();
            let Ok(metadata) = fs::symlink_metadata(&path) else {
                catalog.truncated = true;
                continue;
            };
            if item.file_name() == "ALPM_DB_VERSION" && metadata.is_file() {
                continue;
            }
            if metadata.file_type().is_symlink() || !metadata.is_dir() {
                catalog.truncated = true;
                continue;
            }
            database_entries = database_entries.saturating_add(1);
            if database_entries > LOCAL_DATABASE_ENTRY_LIMIT {
                catalog.truncated = true;
                break;
            }
            let Some(package) = read_local_package_name(&path) else {
                catalog.truncated = true;
                continue;
            };
            let files_path = path.join("files");
            let files_metadata = match fs::symlink_metadata(&files_path) {
                Ok(metadata) => metadata,
                Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
                Err(_) => {
                    catalog.truncated = true;
                    continue;
                }
            };
            if files_metadata.file_type().is_symlink()
                || !files_metadata.is_file()
                || files_metadata.len() > LOCAL_FILES_LIMIT
            {
                catalog.truncated = true;
                continue;
            }
            let Some(next_total) = total_bytes.checked_add(files_metadata.len()) else {
                catalog.truncated = true;
                break;
            };
            if next_total > LOCAL_FILES_TOTAL_LIMIT {
                catalog.truncated = true;
                break;
            }
            total_bytes = next_total;
            let Ok(file) = OpenOptions::new()
                .read(true)
                .custom_flags(O_NOFOLLOW | O_CLOEXEC)
                .open(&files_path)
            else {
                catalog.truncated = true;
                continue;
            };
            let Ok(opened) = file.metadata() else {
                catalog.truncated = true;
                continue;
            };
            if !opened.is_file() || opened.len() != files_metadata.len() {
                catalog.truncated = true;
                continue;
            }
            if !scan_desktop_owners(
                file,
                files_metadata.len(),
                &package,
                &targets,
                &mut catalog.entries,
                &mut source_ambiguous,
                &mut executable_ambiguous,
            ) {
                catalog.truncated = true;
            }
        }
        if source_ambiguous.iter().any(|value| *value)
            || executable_ambiguous.iter().any(|value| *value)
        {
            catalog.truncated = true;
        }
        Ok(())
    }

    pub fn install(&self, package: &str) -> Result<ToolOutput, PackageRuntimeError> {
        let resolution = self.resolve(package)?;
        self.consume_package_compatibility_review(package, &resolution)?;
        if package == BASE_PACKAGE {
            self.install_resolution(
                &resolution,
                &[BASE_PACKAGE],
                package,
                InstallResolutionMode::Normal,
            )?;
        } else {
            self.install_resolution(
                &resolution,
                &[BASE_PACKAGE, package],
                package,
                InstallResolutionMode::Normal,
            )?;
        }
        let installed = self.installed_version(package)?;
        let expected = resolution
            .as_str()?
            .lines()
            .map(parse_resolved_payload)
            .find_map(|payload| match payload {
                Ok(payload) if payload.name == package => Some(Ok(payload.version)),
                Ok(_) => None,
                Err(error) => Some(Err(error)),
            })
            .transpose()?
            .ok_or(PackageRuntimeError::MissingTarget)?;
        if installed.as_str()? != expected {
            return Err(PackageRuntimeError::InvalidResolution);
        }
        Ok(installed)
    }

    pub fn update(&self, package: &str) -> Result<ToolOutput, PackageRuntimeError> {
        if self.installed_version(package)?.as_bytes().is_empty() {
            return Err(PackageRuntimeError::NotInstalled);
        }
        let resolution = self.resolve(package)?;
        self.consume_package_compatibility_review(package, &resolution)?;
        // An update must retain the local database's existing install reasons.
        // install_resolution starts every archive as a dependency, keeps base
        // explicit, then promotes only packages already recorded as explicit.
        // In particular, selecting an installed dependency in the manager must
        // not silently turn it into a user-owned package.
        self.install_resolution(
            &resolution,
            &[BASE_PACKAGE],
            package,
            InstallResolutionMode::Normal,
        )?;
        let installed = self.installed_version(package)?;
        let expected = resolved_version(&resolution, package)?;
        if installed.as_str()? != expected {
            return Err(PackageRuntimeError::InvalidResolution);
        }
        Ok(installed)
    }

    pub fn installation_bytes(&self, package: &str) -> Result<ToolOutput, PackageRuntimeError> {
        let resolution = self.resolve(package)?;
        let mut total = 0_u64;
        for line in resolution.as_str()?.lines() {
            let payload = parse_resolved_payload(line)?;
            let archive = self
                .arch_root
                .join(PACKAGE_CACHE_DIRECTORY)
                .join(payload.filename);
            let archive = archive.to_str().ok_or(PackageRuntimeError::InvalidPath)?;
            let package_info = self.run(PackageTool::Bsdtar, &["-xOf", archive, ".PKGINFO"])?;
            total = total
                .checked_add(parse_package_info_size(package_info.as_str()?)?)
                .ok_or(PackageRuntimeError::OutputLimit)?;
        }
        if total == 0 {
            return Err(PackageRuntimeError::InvalidResolution);
        }
        let mut output = empty_tool_output();
        output.push(total.to_string().as_bytes())?;
        Ok(output)
    }

    pub fn install_dependencies(&self, packages: &[&str]) -> Result<(), PackageRuntimeError> {
        if packages.len() >= 256 {
            return Err(PackageRuntimeError::OutputLimit);
        }
        let mut targets = Vec::with_capacity(packages.len().saturating_add(1));
        targets.push(BASE_PACKAGE);
        for package in packages {
            if *package != BASE_PACKAGE {
                targets.push(*package);
            }
        }
        let resolution = self.resolve_targets(&targets)?;
        let recovery_target = packages.first().copied().unwrap_or(BASE_PACKAGE);
        self.install_resolution(
            &resolution,
            &[BASE_PACKAGE],
            recovery_target,
            InstallResolutionMode::Normal,
        )
    }

    #[allow(clippy::too_many_arguments)]
    pub fn persist_aur_built_capability(
        &self,
        package_base: &str,
        package_name: &str,
        version: &str,
        architecture: &str,
        review_sha256: [u8; 32],
        closure_sha256: [u8; 32],
        required_packages: &[String],
        outputs: &mut [VerifiedAurCapabilityArchive<'_>],
    ) -> Result<Vec<PersistedAurCapabilityArchive>, PackageRuntimeError> {
        validate_aur_capability_identity(
            package_base,
            package_name,
            version,
            architecture,
            review_sha256,
            closure_sha256,
            required_packages,
        )?;
        if outputs.len() != required_packages.len() {
            return Err(PackageRuntimeError::InvalidPayload);
        }
        let directory = self.arch_root.join(AUR_PACKAGE_CACHE_DIRECTORY);
        prepare_private_directory(&directory)?;
        let mut records = Vec::with_capacity(outputs.len());
        let mut persisted = Vec::with_capacity(outputs.len());
        for (output, required_package) in outputs.iter_mut().zip(required_packages) {
            if output.package != required_package
                || !safe_package_filename(output.filename)
                || output.archive_bytes == 0
                || output.archive_bytes > PACKAGE_ARCHIVE_LIMIT
                || output.installed_bytes == 0
                || output.build_package_count == 0
                || output.build_package_count > 256
                || output.sha256 == [0; 32]
            {
                return Err(PackageRuntimeError::InvalidPayload);
            }
            let path = stage_verified_aur_file(
                &directory,
                output.source,
                output.filename,
                output.archive_bytes,
                output.sha256,
            )?;
            records.push(AurBuiltCapabilityOutputRecord {
                package: output.package.to_owned(),
                filename: output.filename.to_owned(),
                archive_bytes: output.archive_bytes,
                installed_bytes: output.installed_bytes,
                build_package_count: output.build_package_count,
                sha256: output.sha256,
            });
            persisted.push(PersistedAurCapabilityArchive {
                package: output.package.to_owned(),
                filename: output.filename.to_owned(),
                archive_bytes: output.archive_bytes,
                installed_bytes: output.installed_bytes,
                build_package_count: output.build_package_count,
                sha256: output.sha256,
                path,
            });
        }
        let record = AurBuiltCapabilityRecord {
            format: 1,
            package_base: package_base.to_owned(),
            package_name: package_name.to_owned(),
            version: version.to_owned(),
            architecture: architecture.to_owned(),
            review_sha256,
            closure_sha256,
            outputs: records,
        };
        let bytes =
            serde_json::to_vec(&record).map_err(|_| PackageRuntimeError::InvalidManifest)?;
        if bytes.is_empty()
            || u64::try_from(bytes.len()).map_err(|_| PackageRuntimeError::OutputLimit)?
                > AUR_BUILT_CAPABILITY_LIMIT
        {
            return Err(PackageRuntimeError::OutputLimit);
        }
        publish_aur_built_capability(&directory, &bytes)?;
        Ok(persisted)
    }

    #[allow(clippy::too_many_arguments)]
    pub fn restore_aur_built_capability(
        &self,
        package_base: &str,
        package_name: &str,
        version: &str,
        architecture: &str,
        review_sha256: [u8; 32],
        closure_sha256: [u8; 32],
        required_packages: &[String],
    ) -> Result<Option<Vec<PersistedAurCapabilityArchive>>, PackageRuntimeError> {
        validate_aur_capability_identity(
            package_base,
            package_name,
            version,
            architecture,
            review_sha256,
            closure_sha256,
            required_packages,
        )?;
        let directory = self.arch_root.join(AUR_PACKAGE_CACHE_DIRECTORY);
        let Some(bytes) = read_aur_built_capability(&directory)? else {
            return Ok(None);
        };
        let record: AurBuiltCapabilityRecord =
            serde_json::from_slice(&bytes).map_err(|_| PackageRuntimeError::InvalidManifest)?;
        if record.format != 1
            || record.package_base != package_base
            || record.package_name != package_name
            || record.version != version
            || record.architecture != architecture
            || record.review_sha256 != review_sha256
            || record.closure_sha256 != closure_sha256
            || record.outputs.len() != required_packages.len()
        {
            return Ok(None);
        }
        let mut restored = Vec::with_capacity(record.outputs.len());
        for (output, required_package) in record.outputs.into_iter().zip(required_packages) {
            if output.package != *required_package
                || !safe_package_filename(&output.filename)
                || output.archive_bytes == 0
                || output.archive_bytes > PACKAGE_ARCHIVE_LIMIT
                || output.installed_bytes == 0
                || output.build_package_count == 0
                || output.build_package_count > 256
                || output.sha256 == [0; 32]
            {
                return Err(PackageRuntimeError::InvalidManifest);
            }
            let path = directory.join(format!(
                "{}-{}",
                hex_sha256(&output.sha256),
                output.filename
            ));
            verify_persisted_aur_file(&path, output.archive_bytes, output.sha256)?;
            restored.push(PersistedAurCapabilityArchive {
                package: output.package,
                filename: output.filename,
                archive_bytes: output.archive_bytes,
                installed_bytes: output.installed_bytes,
                build_package_count: output.build_package_count,
                sha256: output.sha256,
                path,
            });
        }
        Ok(Some(restored))
    }

    pub fn clear_aur_built_capability(&self) -> Result<(), PackageRuntimeError> {
        let directory = self.arch_root.join(AUR_PACKAGE_CACHE_DIRECTORY);
        let metadata = match fs::symlink_metadata(&directory) {
            Ok(metadata) => metadata,
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(()),
            Err(error) => return Err(PackageRuntimeError::Io(error)),
        };
        if metadata.file_type().is_symlink() || !metadata.is_dir() {
            return Err(PackageRuntimeError::UnsafeEntry(directory));
        }
        let path = directory.join(AUR_BUILT_CAPABILITY_FILE);
        match fs::symlink_metadata(&path) {
            Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_file() => {
                return Err(PackageRuntimeError::UnsafeEntry(path));
            }
            Ok(_) => fs::remove_file(path)?,
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(()),
            Err(error) => return Err(PackageRuntimeError::Io(error)),
        }
        File::open(directory)?.sync_all()?;
        Ok(())
    }

    fn publish_aur_lifecycle_capabilities(
        &self,
        pending: &[AurLifecycleCapability],
    ) -> Result<(), PackageRuntimeError> {
        if pending.is_empty()
            || pending.len() > aur::MAX_AUR_DEPENDENCIES
            || pending.iter().any(|capability| {
                !safe_logical_name(&capability.package)
                    || !safe_package_version(&capability.version)
                    || capability.archive_sha256 == [0; 32]
            })
        {
            return Err(PackageRuntimeError::InvalidPayload);
        }
        let state_root = aur_lifecycle_state_root(&self.arch_root)?;
        let installed = self.installed_package_catalog()?;
        let mut capabilities = read_aur_lifecycle_capabilities(&state_root)?;
        capabilities.retain(|capability| {
            installed.packages.iter().any(|package| {
                package.name == capability.package && package.version == capability.version
            })
        });
        for capability in pending {
            capabilities.retain(|existing| {
                existing.package != capability.package || existing.version != capability.version
            });
            capabilities.push(capability.clone());
        }
        capabilities.sort();
        capabilities.dedup();
        if capabilities.len() > AUR_LIFECYCLE_CAPABILITY_ENTRIES {
            return Err(PackageRuntimeError::OutputLimit);
        }
        publish_aur_lifecycle_capability_file(&state_root, &capabilities)
    }

    fn reconcile_aur_lifecycle_capabilities(&self) -> Result<(), PackageRuntimeError> {
        let state_root = aur_lifecycle_state_root(&self.arch_root)?;
        let installed = self.installed_package_catalog()?;
        let mut capabilities = read_aur_lifecycle_capabilities(&state_root)?;
        capabilities.retain(|capability| {
            installed.packages.iter().any(|package| {
                package.name == capability.package && package.version == capability.version
            })
        });
        publish_aur_lifecycle_capability_file(&state_root, &capabilities)
    }

    fn aur_removal_scriptlets_authorized(
        &self,
        package: &str,
        version: &str,
    ) -> Result<bool, PackageRuntimeError> {
        let local_entry = find_local_database_entry(&self.arch_root, package, version)?;
        let install_script = local_entry.join("install");
        let metadata = match fs::symlink_metadata(&install_script) {
            Ok(metadata) => metadata,
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(true),
            Err(error) => return Err(PackageRuntimeError::Io(error)),
        };
        if metadata.file_type().is_symlink()
            || !metadata.is_file()
            || metadata.len() == 0
            || metadata.len() > LOCAL_DATABASE_PACKAGE_FILE_LIMIT
        {
            return Err(PackageRuntimeError::UnsafeEntry(install_script));
        }
        let actual_sha256 = hash_regular_file(
            &install_script,
            metadata.len(),
            LOCAL_DATABASE_PACKAGE_FILE_LIMIT,
        )?;
        let state_root = aur_lifecycle_state_root(&self.arch_root)?;
        let capabilities = read_aur_lifecycle_capabilities(&state_root)?;
        Ok(capabilities.iter().any(|capability| {
            capability.package == package
                && capability.version == version
                && capability.install_script_sha256 == Some(actual_sha256)
        }))
    }

    pub fn install_verified_aur_archive(
        &self,
        input: &mut VerifiedAurArchive<'_>,
    ) -> Result<ToolOutput, PackageRuntimeError> {
        let source = &mut *input.source;
        let filename = input.filename;
        let package = input.package;
        let version = input.version;
        let expected_bytes = input.expected_bytes;
        let expected_sha256 = input.expected_sha256;
        let install_script_sha256 = input.install_script_sha256;
        if !safe_package_filename(filename)
            || !safe_logical_name(package)
            || version.is_empty()
            || version.len() > 128
            || version
                .bytes()
                .any(|byte| byte.is_ascii_whitespace() || byte == 0)
            || expected_bytes == 0
            || expected_bytes > PACKAGE_ARCHIVE_LIMIT
            || install_script_sha256 == Some([0; 32])
        {
            return Err(PackageRuntimeError::InvalidPayload);
        }
        let source_metadata = source.metadata()?;
        if !source_metadata.is_file() || source_metadata.len() != expected_bytes {
            return Err(PackageRuntimeError::InvalidPayload);
        }
        let directory = self.arch_root.join(AUR_PACKAGE_CACHE_DIRECTORY);
        prepare_private_directory(&directory)?;
        let digest = hex_sha256(&expected_sha256);
        let destination = directory.join(format!("{digest}-{filename}"));
        let temporary = directory.join(format!(".{digest}.part"));
        prepare_output_path(&temporary)?;
        match fs::symlink_metadata(&destination) {
            Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_file() => {
                return Err(PackageRuntimeError::UnsafeEntry(destination));
            }
            Ok(_) => {}
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => return Err(PackageRuntimeError::Io(error)),
        }
        let result = (|| {
            source.seek(SeekFrom::Start(0))?;
            let mut output = OpenOptions::new()
                .create_new(true)
                .write(true)
                .mode(0o600)
                .open(&temporary)?;
            let mut hasher = Sha256::new();
            let mut buffer = [0_u8; 64 * 1024];
            let mut copied = 0_u64;
            loop {
                let count = source.read(&mut buffer)?;
                if count == 0 {
                    break;
                }
                copied = copied
                    .checked_add(count as u64)
                    .ok_or(PackageRuntimeError::OutputLimit)?;
                if copied > expected_bytes {
                    return Err(PackageRuntimeError::InvalidPayload);
                }
                hasher.update(&buffer[..count]);
                output.write_all(&buffer[..count])?;
            }
            if copied != expected_bytes || <[u8; 32]>::from(hasher.finalize()) != expected_sha256 {
                return Err(PackageRuntimeError::InvalidPayload);
            }
            output.sync_all()?;
            drop(output);
            fs::rename(&temporary, &destination)?;
            File::open(&directory)?.sync_all()?;
            Ok(())
        })();
        if let Err(error) = result {
            let _ = fs::remove_file(&temporary);
            return Err(error);
        }

        let config_path = self.arch_root.join(AUR_PACMAN_CONFIG_FILE);
        let config = config_path
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let root = self
            .arch_root
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let database_path = self.arch_root.join("var/lib/pacman");
        let database = database_path
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let cache_path = self.arch_root.join(PACKAGE_CACHE_DIRECTORY);
        let cache = cache_path
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let archive_path = destination
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let archives = [InstallArchive {
            path: archive_path.to_owned(),
            name: package.to_owned(),
            version: version.to_owned(),
            explicitly_installed: true,
        }];
        let plan = self.run_with_timeout(
            PackageTool::Pacman,
            &[
                "--config",
                config,
                "--root",
                root,
                "--dbpath",
                database,
                "--cachedir",
                cache,
                "--noconfirm",
                "--noprogressbar",
                "-U",
                "--print",
                "--print-format",
                "%n\t%v",
                archive_path,
            ],
            TRANSACTION_TIMEOUT,
        )?;
        validate_install_plan(plan.as_str()?, &archives)?;
        self.publish_aur_lifecycle_capabilities(&[AurLifecycleCapability {
            package: package.to_owned(),
            version: version.to_owned(),
            archive_sha256: expected_sha256,
            install_script_sha256,
        }])?;
        self.publish_install_reason_intent(&archives)?;
        if let Err(error) = self.run_bytes_with_timeout(
            PackageTool::Pacman,
            &[
                "--config",
                config,
                "--root",
                root,
                "--dbpath",
                database,
                "--cachedir",
                cache,
                "--noconfirm",
                "--noprogressbar",
                "--asdeps",
                "-U",
                archive_path,
            ],
            TRANSACTION_TIMEOUT,
            MAX_PACKAGE_TRANSACTION_OUTPUT_BYTES,
            false,
        ) {
            let _ = self.recover_database_lock();
            let _ = self.recover_pending_install_reasons();
            return Err(error);
        }
        self.recover_pending_install_reasons()?;
        let installed = self.installed_version(package)?;
        if installed.as_str()? != version {
            return Err(PackageRuntimeError::InvalidResolution);
        }
        self.reconcile_aur_lifecycle_capabilities()?;
        Ok(installed)
    }

    pub fn install_verified_aur_archives(
        &self,
        inputs: &mut [VerifiedAurArchive<'_>],
        selected_package: &str,
    ) -> Result<ToolOutput, PackageRuntimeError> {
        if inputs.is_empty()
            || inputs.len() > aur::MAX_AUR_DEPENDENCIES
            || !safe_logical_name(selected_package)
        {
            return Err(PackageRuntimeError::InvalidPayload);
        }
        let directory = self.arch_root.join(AUR_PACKAGE_CACHE_DIRECTORY);
        prepare_private_directory(&directory)?;
        let mut archives = Vec::with_capacity(inputs.len());
        let mut lifecycle_capabilities = Vec::with_capacity(inputs.len());
        for input in inputs {
            if !safe_package_filename(input.filename)
                || !safe_logical_name(input.package)
                || input.version.is_empty()
                || input.version.len() > 128
                || input
                    .version
                    .bytes()
                    .any(|byte| byte.is_ascii_whitespace() || byte == 0)
                || input.expected_bytes == 0
                || input.expected_bytes > PACKAGE_ARCHIVE_LIMIT
                || input.expected_sha256 == [0; 32]
                || input.install_script_sha256 == Some([0; 32])
                || archives
                    .iter()
                    .any(|archive: &InstallArchive| archive.name == input.package)
            {
                return Err(PackageRuntimeError::InvalidPayload);
            }
            let source_metadata = input.source.metadata()?;
            if !source_metadata.is_file() || source_metadata.len() != input.expected_bytes {
                return Err(PackageRuntimeError::InvalidPayload);
            }
            let digest = hex_sha256(&input.expected_sha256);
            let destination = directory.join(format!("{digest}-{}", input.filename));
            let temporary = directory.join(format!(".{digest}.part"));
            prepare_output_path(&temporary)?;
            match fs::symlink_metadata(&destination) {
                Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_file() => {
                    return Err(PackageRuntimeError::UnsafeEntry(destination));
                }
                Ok(_) => {}
                Err(error) if error.kind() == io::ErrorKind::NotFound => {}
                Err(error) => return Err(PackageRuntimeError::Io(error)),
            }
            let staged = (|| {
                input.source.seek(SeekFrom::Start(0))?;
                let mut output = OpenOptions::new()
                    .create_new(true)
                    .write(true)
                    .mode(0o600)
                    .open(&temporary)?;
                let mut hasher = Sha256::new();
                let mut buffer = [0_u8; 64 * 1024];
                let mut copied = 0_u64;
                loop {
                    let count = input.source.read(&mut buffer)?;
                    if count == 0 {
                        break;
                    }
                    copied = copied
                        .checked_add(count as u64)
                        .ok_or(PackageRuntimeError::OutputLimit)?;
                    if copied > input.expected_bytes {
                        return Err(PackageRuntimeError::InvalidPayload);
                    }
                    hasher.update(&buffer[..count]);
                    output.write_all(&buffer[..count])?;
                }
                if copied != input.expected_bytes
                    || <[u8; 32]>::from(hasher.finalize()) != input.expected_sha256
                {
                    return Err(PackageRuntimeError::InvalidPayload);
                }
                output.sync_all()?;
                drop(output);
                fs::rename(&temporary, &destination)?;
                File::open(&directory)?.sync_all()?;
                Ok(())
            })();
            if let Err(error) = staged {
                let _ = fs::remove_file(&temporary);
                return Err(error);
            }
            archives.push(InstallArchive {
                path: destination
                    .to_str()
                    .ok_or(PackageRuntimeError::InvalidPath)?
                    .to_owned(),
                name: input.package.to_owned(),
                version: input.version.to_owned(),
                explicitly_installed: input.package == selected_package,
            });
            lifecycle_capabilities.push(AurLifecycleCapability {
                package: input.package.to_owned(),
                version: input.version.to_owned(),
                archive_sha256: input.expected_sha256,
                install_script_sha256: input.install_script_sha256,
            });
        }
        if !archives.iter().any(|archive| archive.explicitly_installed) {
            return Err(PackageRuntimeError::InvalidPayload);
        }

        let config_path = self.arch_root.join(AUR_PACMAN_CONFIG_FILE);
        let config = config_path
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let root = self
            .arch_root
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let database_path = self.arch_root.join("var/lib/pacman");
        let database = database_path
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let cache_path = self.arch_root.join(PACKAGE_CACHE_DIRECTORY);
        let cache = cache_path
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let mut plan_arguments = vec![
            "--config",
            config,
            "--root",
            root,
            "--dbpath",
            database,
            "--cachedir",
            cache,
            "--noconfirm",
            "--noprogressbar",
            "-U",
            "--print",
            "--print-format",
            "%n\t%v",
        ];
        plan_arguments.extend(archives.iter().map(|archive| archive.path.as_str()));
        let plan =
            self.run_with_timeout(PackageTool::Pacman, &plan_arguments, TRANSACTION_TIMEOUT)?;
        validate_install_plan(plan.as_str()?, &archives)?;
        self.publish_aur_lifecycle_capabilities(&lifecycle_capabilities)?;
        self.publish_install_reason_intent(&archives)?;
        let mut transaction_arguments = vec![
            "--config",
            config,
            "--root",
            root,
            "--dbpath",
            database,
            "--cachedir",
            cache,
            "--noconfirm",
            "--noprogressbar",
            "--asdeps",
            "-U",
        ];
        transaction_arguments.extend(archives.iter().map(|archive| archive.path.as_str()));
        if let Err(error) = self.run_bytes_with_timeout(
            PackageTool::Pacman,
            &transaction_arguments,
            TRANSACTION_TIMEOUT,
            MAX_PACKAGE_TRANSACTION_OUTPUT_BYTES,
            false,
        ) {
            let _ = self.recover_database_lock();
            let _ = self.recover_pending_install_reasons();
            return Err(error);
        }
        self.recover_pending_install_reasons()?;
        for archive in &archives {
            if self.installed_version(&archive.name)?.as_str()? != archive.version {
                return Err(PackageRuntimeError::InvalidResolution);
            }
        }
        self.reconcile_aur_lifecycle_capabilities()?;
        self.installed_version(selected_package)
    }

    fn install_resolution(
        &self,
        resolution: &PackageResolution,
        explicit_targets: &[&str],
        recovery_target: &str,
        mode: InstallResolutionMode,
    ) -> Result<(), PackageRuntimeError> {
        let package_count = resolution.as_str()?.lines().count();
        let mut archives = Vec::with_capacity(package_count);
        for line in resolution.as_str()?.lines() {
            let payload = parse_resolved_payload(line)?;
            self.verify_package(
                payload.filename,
                payload.name,
                payload.version,
                payload.size,
            )?;
            let archive = self
                .arch_root
                .join(PACKAGE_CACHE_DIRECTORY)
                .join(payload.filename);
            archives.push(InstallArchive {
                path: archive
                    .to_str()
                    .ok_or(PackageRuntimeError::InvalidPath)?
                    .to_owned(),
                name: payload.name.to_owned(),
                version: payload.version.to_owned(),
                explicitly_installed: explicit_targets.contains(&payload.name),
            });
        }
        if archives.is_empty() || archives.len() > 256 {
            return Err(PackageRuntimeError::InvalidResolution);
        }
        self.preserve_explicit_install_reasons(&mut archives)?;
        self.preserve_pending_install_reasons(&mut archives)?;
        if mode == InstallResolutionMode::Repair {
            self.prepare_database_repair(&archives)?;
        }

        let config = self
            .pacman_config
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let root = self
            .arch_root
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let database_path = self.arch_root.join("var/lib/pacman");
        let database = database_path
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let trust_directory = self
            .keyring
            .parent()
            .and_then(Path::to_str)
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let cache_path = self.arch_root.join(PACKAGE_CACHE_DIRECTORY);
        let cache = cache_path
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        if mode == InstallResolutionMode::Repair {
            self.restore_database_repair_records(
                &archives,
                config,
                root,
                database,
                trust_directory,
                cache,
            )?;
        }
        let mut plan_arguments = vec![
            "--config",
            config,
            "--root",
            root,
            "--dbpath",
            database,
            "--gpgdir",
            trust_directory,
            "--cachedir",
            cache,
            "--noconfirm",
            "--noprogressbar",
            "-U",
            "--print",
            "--print-format",
            "%n\t%v",
        ];
        plan_arguments.extend(archives.iter().map(|archive| archive.path.as_str()));
        let plan =
            self.run_with_timeout(PackageTool::Pacman, &plan_arguments, TRANSACTION_TIMEOUT)?;
        validate_install_plan(plan.as_str()?, &archives)?;

        self.publish_install_mutation_intent(recovery_target, explicit_targets, resolution)?;
        let has_explicit = archives.iter().any(|archive| archive.explicitly_installed);
        if has_explicit {
            self.publish_install_reason_intent(&archives)?;
        }
        let mut transaction_arguments = vec![
            "--config",
            config,
            "--root",
            root,
            "--dbpath",
            database,
            "--gpgdir",
            trust_directory,
            "--cachedir",
            cache,
            "--noconfirm",
            "--noprogressbar",
            "--noscriptlet",
        ];
        append_install_transaction_mode(&mut transaction_arguments, mode);
        transaction_arguments.extend(archives.iter().map(|archive| archive.path.as_str()));
        if let Err(error) = self.run_bytes_with_timeout(
            PackageTool::Pacman,
            &transaction_arguments,
            TRANSACTION_TIMEOUT,
            MAX_PACKAGE_TRANSACTION_OUTPUT_BYTES,
            false,
        ) {
            // Never hide the transaction diagnostic behind a secondary
            // cleanup failure on a damaged or full filesystem. The retained
            // reason intent is recovered idempotently at the next startup.
            let _ = self.recover_database_lock();
            if has_explicit {
                let _ = self.recover_pending_install_reasons();
            }
            return Err(error);
        }
        if has_explicit {
            self.recover_pending_install_reasons()?;
        }
        self.validate_local_database()?;
        let expected = resolved_version(resolution, recovery_target)?;
        if self.installed_version(recovery_target)?.as_str()? != expected {
            return Err(PackageRuntimeError::InvalidResolution);
        }
        if mode == InstallResolutionMode::Repair {
            self.clear_database_repair(&archives)?;
        }
        self.clear_pending_mutation()?;
        self.refresh_system_trust()?;
        Ok(())
    }

    pub fn remove(&self, package: &str) -> Result<ToolOutput, PackageRuntimeError> {
        if self.installed_version(package)?.as_bytes().is_empty() {
            return Err(PackageRuntimeError::NotInstalled);
        }
        let config = self
            .pacman_config
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let root = self
            .arch_root
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let database_path = self.arch_root.join("var/lib/pacman");
        let database = database_path
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let plan = self.run_with_timeout(
            PackageTool::Pacman,
            &[
                "--config",
                config,
                "--root",
                root,
                "--dbpath",
                database,
                "--print",
                "--print-format",
                "%n",
                "-R",
                package,
            ],
            COMMAND_TIMEOUT,
        )?;
        if plan.as_str()?.trim() != package {
            return Err(PackageRuntimeError::InvalidResolution);
        }
        let installed_version = self.installed_version(package)?;
        let aur_package = self.installed_origin(package)?.as_str()? == "aur";
        let run_scriptlets = aur_package
            && self.aur_removal_scriptlets_authorized(package, installed_version.as_str()?)?;
        if aur_package && !run_scriptlets {
            return Err(PackageRuntimeError::UnreviewedInstallScript);
        }
        let removal_database_sha256 =
            self.prepare_removal_repair(package, installed_version.as_str()?)?;
        self.publish_remove_mutation_intent(
            package,
            installed_version.as_str()?,
            Some(removal_database_sha256),
        )?;
        let mut arguments = vec![
            "--config",
            config,
            "--root",
            root,
            "--dbpath",
            database,
            "--noconfirm",
            "--noprogressbar",
        ];
        if !run_scriptlets {
            arguments.push("--noscriptlet");
        }
        arguments.extend(["-R", package]);
        let result = self.run_with_timeout(PackageTool::Pacman, &arguments, TRANSACTION_TIMEOUT);
        if let Err(error) = result {
            self.recover_database_lock()?;
            return Err(error);
        }
        self.validate_local_database()?;
        if !self.installed_version(package)?.as_bytes().is_empty() {
            return Err(PackageRuntimeError::InvalidResolution);
        }
        self.clear_removal_repair()?;
        self.clear_pending_mutation()?;
        self.reconcile_aur_lifecycle_capabilities()?;
        Ok(empty_tool_output())
    }

    pub fn pending_mutation(&self, package: &str) -> Result<ToolOutput, PackageRuntimeError> {
        if !safe_logical_name(package) {
            return Err(PackageRuntimeError::InvalidQuery);
        }
        let mut output = empty_tool_output();
        let Some(intent) = self.read_pending_mutation()? else {
            return Ok(output);
        };
        match intent {
            PackageMutationIntent::Install {
                request,
                resolution,
                ..
            } if request == package => {
                let version = resolved_version(&resolution, package)?;
                output.push(b"install\t")?;
                output.push(version.as_bytes())?;
            }
            PackageMutationIntent::Remove {
                package: target,
                version,
                ..
            } if target == package => {
                output.push(b"remove\t")?;
                output.push(version.as_bytes())?;
            }
            _ => return Err(PackageRuntimeError::Busy),
        }
        Ok(output)
    }

    pub fn repair_pending_mutation(
        &self,
        package: &str,
    ) -> Result<ToolOutput, PackageRuntimeError> {
        if !safe_logical_name(package) {
            return Err(PackageRuntimeError::InvalidQuery);
        }
        let intent = self
            .read_pending_mutation()?
            .ok_or(PackageRuntimeError::InvalidResolution)?;
        self.recover_database_lock()?;
        match intent {
            PackageMutationIntent::Install {
                request,
                explicit_targets,
                resolution,
            } => {
                if request != package {
                    return Err(PackageRuntimeError::Busy);
                }
                let explicit = explicit_targets
                    .iter()
                    .map(String::as_str)
                    .collect::<Vec<_>>();
                self.install_resolution(
                    &resolution,
                    &explicit,
                    &request,
                    InstallResolutionMode::Repair,
                )?;
                let expected = resolved_version(&resolution, &request)?;
                let installed = self.installed_version(&request)?;
                if installed.as_str()? != expected {
                    return Err(PackageRuntimeError::InvalidResolution);
                }
                Ok(installed)
            }
            PackageMutationIntent::Remove {
                package: target,
                version,
                database_sha256,
            } => {
                if target != package {
                    return Err(PackageRuntimeError::Busy);
                }
                if let Some(expected_sha256) = database_sha256.as_deref() {
                    if self.removal_repair_exists()? {
                        self.restore_removal_repair(&target, &version, expected_sha256)?;
                    } else {
                        let installed = self.installed_version(&target)?;
                        if installed.as_bytes().is_empty() {
                            self.validate_local_database()?;
                            self.clear_removal_repair()?;
                            self.clear_pending_mutation()?;
                            return Ok(empty_tool_output());
                        }
                        if installed.as_str()? != version {
                            return Err(PackageRuntimeError::InvalidResolution);
                        }
                        let actual_sha256 = self.prepare_removal_repair(&target, &version)?;
                        if actual_sha256 != expected_sha256 {
                            return Err(PackageRuntimeError::InvalidResolution);
                        }
                    }
                }
                let installed = self.installed_version(&target)?;
                if installed.as_bytes().is_empty() {
                    self.validate_local_database()?;
                    self.clear_removal_repair()?;
                    self.clear_pending_mutation()?;
                    return Ok(empty_tool_output());
                }
                if installed.as_str()? != version {
                    return Err(PackageRuntimeError::InvalidResolution);
                }
                if database_sha256.is_none() {
                    let sha256 = self.prepare_removal_repair(&target, &version)?;
                    self.clear_pending_mutation()?;
                    self.publish_remove_mutation_intent(&target, &version, Some(sha256))?;
                }
                self.remove(&target)
            }
        }
    }

    fn publish_install_mutation_intent(
        &self,
        request: &str,
        explicit_targets: &[&str],
        resolution: &PackageResolution,
    ) -> Result<(), PackageRuntimeError> {
        let intent = PackageMutationIntent::Install {
            request: request.to_owned(),
            explicit_targets: explicit_targets
                .iter()
                .map(|target| (*target).to_owned())
                .collect(),
            resolution: resolution.clone(),
        };
        self.publish_mutation_intent(&intent)
    }

    fn publish_remove_mutation_intent(
        &self,
        package: &str,
        version: &str,
        database_sha256: Option<String>,
    ) -> Result<(), PackageRuntimeError> {
        self.publish_mutation_intent(&PackageMutationIntent::Remove {
            package: package.to_owned(),
            version: version.to_owned(),
            database_sha256,
        })
    }

    fn publish_mutation_intent(
        &self,
        intent: &PackageMutationIntent,
    ) -> Result<(), PackageRuntimeError> {
        let content = serialize_package_mutation_intent(intent, self.architecture)?;
        if let Some(existing) = self.read_pending_mutation()? {
            let existing = serialize_package_mutation_intent(&existing, self.architecture)?;
            if existing != content {
                return Err(PackageRuntimeError::Busy);
            }
        }
        publish_regular_file(
            &self.arch_root.join(PACKAGE_MUTATION_INTENT_FILE),
            &self.arch_root.join(PACKAGE_MUTATION_INTENT_TEMP_FILE),
            content.as_bytes(),
        )?;
        File::open(
            self.arch_root
                .join(PACKAGE_MUTATION_INTENT_FILE)
                .parent()
                .ok_or(PackageRuntimeError::InvalidPath)?,
        )?
        .sync_all()?;
        Ok(())
    }

    fn read_pending_mutation(&self) -> Result<Option<PackageMutationIntent>, PackageRuntimeError> {
        let path = self.arch_root.join(PACKAGE_MUTATION_INTENT_FILE);
        let metadata = match fs::symlink_metadata(&path) {
            Ok(metadata) => metadata,
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
            Err(error) => return Err(PackageRuntimeError::Io(error)),
        };
        if metadata.file_type().is_symlink()
            || !metadata.is_file()
            || metadata.permissions().mode() & 0o077 != 0
            || metadata.len() == 0
            || metadata.len() > PACKAGE_MUTATION_INTENT_LIMIT
        {
            return Err(PackageRuntimeError::UnsafeEntry(path));
        }
        let content = fs::read(&path)?;
        if content.len() as u64 != metadata.len() {
            return Err(PackageRuntimeError::SizeMismatch);
        }
        let content =
            std::str::from_utf8(&content).map_err(|_| PackageRuntimeError::InvalidResolution)?;
        parse_package_mutation_intent(content, self.architecture).map(Some)
    }

    fn clear_pending_mutation(&self) -> Result<(), PackageRuntimeError> {
        let path = self.arch_root.join(PACKAGE_MUTATION_INTENT_FILE);
        match fs::remove_file(&path) {
            Ok(()) => {
                File::open(path.parent().ok_or(PackageRuntimeError::InvalidPath)?)?.sync_all()?;
                Ok(())
            }
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
            Err(error) => Err(PackageRuntimeError::Io(error)),
        }
    }

    fn validate_local_database(&self) -> Result<(), PackageRuntimeError> {
        let config = self
            .pacman_config
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let root = self
            .arch_root
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let database_path = self.arch_root.join("var/lib/pacman");
        let database = database_path
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        self.run_with_timeout(
            PackageTool::Pacman,
            &[
                "--config", config, "--root", root, "--dbpath", database, "-Dk",
            ],
            COMMAND_TIMEOUT,
        )?;
        Ok(())
    }

    fn mark_explicitly_installed(&self, package: &str) -> Result<(), PackageRuntimeError> {
        let config = self
            .pacman_config
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let root = self
            .arch_root
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let database_path = self.arch_root.join("var/lib/pacman");
        let database = database_path
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        self.run_with_timeout(
            PackageTool::Pacman,
            &[
                "--config",
                config,
                "--root",
                root,
                "--dbpath",
                database,
                "-D",
                "--asexplicit",
                package,
            ],
            COMMAND_TIMEOUT,
        )?;
        Ok(())
    }

    fn publish_install_reason_intent(
        &self,
        archives: &[InstallArchive],
    ) -> Result<(), PackageRuntimeError> {
        let mut content = String::with_capacity(archives.len().saturating_mul(32));
        content.push_str(INSTALL_REASON_INTENT_HEADER);
        content.push('\n');
        let mut explicit_count = 0_usize;
        for archive in archives {
            if archive.explicitly_installed {
                explicit_count = explicit_count.saturating_add(1);
                content.push_str(&archive.name);
                content.push('\n');
            }
        }
        parse_install_reason_intent(&content)?;
        if explicit_count == 0 || content.len() as u64 > INSTALL_REASON_INTENT_LIMIT {
            return Err(PackageRuntimeError::InvalidResolution);
        }
        let destination = self.arch_root.join(INSTALL_REASON_INTENT_FILE);
        publish_regular_file(
            &destination,
            &self.arch_root.join(INSTALL_REASON_INTENT_TEMP_FILE),
            content.as_bytes(),
        )?;
        File::open(
            destination
                .parent()
                .ok_or(PackageRuntimeError::InvalidPath)?,
        )?
        .sync_all()?;
        Ok(())
    }

    fn recover_pending_install_reasons(&self) -> Result<(), PackageRuntimeError> {
        let packages = self.read_pending_install_reasons()?;
        if packages.is_empty() {
            return Ok(());
        }
        let path = self.arch_root.join(INSTALL_REASON_INTENT_FILE);
        self.recover_database_lock()?;
        for package in &packages {
            if !self.installed_version(package)?.as_bytes().is_empty() {
                self.mark_explicitly_installed(package)?;
            }
        }
        self.validate_local_database()?;
        fs::remove_file(&path)?;
        File::open(path.parent().ok_or(PackageRuntimeError::InvalidPath)?)?.sync_all()?;
        Ok(())
    }

    fn read_pending_install_reasons(&self) -> Result<Vec<String>, PackageRuntimeError> {
        let path = self.arch_root.join(INSTALL_REASON_INTENT_FILE);
        let metadata = match fs::symlink_metadata(&path) {
            Ok(metadata) => metadata,
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(Vec::new()),
            Err(error) => return Err(PackageRuntimeError::Io(error)),
        };
        if metadata.file_type().is_symlink()
            || !metadata.is_file()
            || metadata.permissions().mode() & 0o077 != 0
            || metadata.len() == 0
            || metadata.len() > INSTALL_REASON_INTENT_LIMIT
        {
            return Err(PackageRuntimeError::UnsafeEntry(path));
        }
        let content = fs::read(&path)?;
        if content.len() as u64 != metadata.len() {
            return Err(PackageRuntimeError::SizeMismatch);
        }
        let content =
            std::str::from_utf8(&content).map_err(|_| PackageRuntimeError::InvalidResolution)?;
        Ok(parse_install_reason_intent(content)?
            .into_iter()
            .map(str::to_owned)
            .collect())
    }

    fn preserve_pending_install_reasons(
        &self,
        archives: &mut [InstallArchive],
    ) -> Result<(), PackageRuntimeError> {
        for package in self.read_pending_install_reasons()? {
            let archive = archives
                .iter_mut()
                .find(|archive| archive.name == package)
                .ok_or(PackageRuntimeError::InvalidResolution)?;
            archive.explicitly_installed = true;
        }
        Ok(())
    }

    fn prepare_database_repair(
        &self,
        archives: &[InstallArchive],
    ) -> Result<(), PackageRuntimeError> {
        let local = self.arch_root.join("var/lib/pacman/local");
        let quarantine = self.arch_root.join(PACKAGE_DATABASE_REPAIR_DIRECTORY);
        prepare_private_directory(&quarantine)?;
        for archive in archives {
            let entry_name = format!("{}-{}", archive.name, archive.version);
            let entry = local.join(&entry_name);
            let retained = quarantine.join(&entry_name);
            validate_database_repair_entry_if_present(&retained)?;
            let metadata = match fs::symlink_metadata(&entry) {
                Ok(metadata) => metadata,
                Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
                Err(error) => return Err(PackageRuntimeError::Io(error)),
            };
            if metadata.file_type().is_symlink() || !metadata.is_dir() {
                return Err(PackageRuntimeError::UnsafeEntry(entry));
            }
            if local_database_entry_matches(&entry, &archive.name, &archive.version)? {
                continue;
            }
            if retained.exists() {
                remove_database_repair_entry(&entry)?;
            } else {
                validate_database_repair_entry(&entry)?;
                fs::rename(&entry, &retained)?;
                fs::set_permissions(&retained, fs::Permissions::from_mode(0o700))?;
                File::open(&local)?.sync_all()?;
                File::open(&quarantine)?.sync_all()?;
            }
        }
        Ok(())
    }

    fn restore_database_repair_records(
        &self,
        archives: &[InstallArchive],
        config: &str,
        root: &str,
        database: &str,
        trust_directory: &str,
        cache: &str,
    ) -> Result<(), PackageRuntimeError> {
        let local = self.arch_root.join("var/lib/pacman/local");
        let quarantine = self.arch_root.join(PACKAGE_DATABASE_REPAIR_DIRECTORY);
        let mut damaged = Vec::with_capacity(archives.len());
        for archive in archives {
            let entry_name = format!("{}-{}", archive.name, archive.version);
            let retained = quarantine.join(&entry_name);
            if !retained.exists() {
                continue;
            }
            validate_database_repair_entry(&retained)?;
            let entry = local.join(entry_name);
            if !local_database_entry_matches(&entry, &archive.name, &archive.version)? {
                damaged.push(archive);
            }
        }
        if damaged.is_empty() {
            return Ok(());
        }
        let mut arguments = vec![
            "--config",
            config,
            "--root",
            root,
            "--dbpath",
            database,
            "--gpgdir",
            trust_directory,
            "--cachedir",
            cache,
            "--noconfirm",
            "--noprogressbar",
            "--noscriptlet",
            "--dbonly",
            "--asdeps",
            "-U",
        ];
        arguments.extend(damaged.iter().map(|archive| archive.path.as_str()));
        if let Err(error) = self.run_bytes_with_timeout(
            PackageTool::Pacman,
            &arguments,
            TRANSACTION_TIMEOUT,
            MAX_PACKAGE_TRANSACTION_OUTPUT_BYTES,
            false,
        ) {
            let _ = self.recover_database_lock();
            return Err(error);
        }
        for archive in damaged {
            if !local_database_entry_matches(
                &local.join(format!("{}-{}", archive.name, archive.version)),
                &archive.name,
                &archive.version,
            )? {
                return Err(PackageRuntimeError::InvalidResolution);
            }
        }
        Ok(())
    }

    fn clear_database_repair(
        &self,
        archives: &[InstallArchive],
    ) -> Result<(), PackageRuntimeError> {
        let quarantine = self.arch_root.join(PACKAGE_DATABASE_REPAIR_DIRECTORY);
        let metadata = match fs::symlink_metadata(&quarantine) {
            Ok(metadata) => metadata,
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(()),
            Err(error) => return Err(PackageRuntimeError::Io(error)),
        };
        if metadata.file_type().is_symlink() || !metadata.is_dir() {
            return Err(PackageRuntimeError::UnsafeEntry(quarantine));
        }
        for archive in archives {
            remove_database_repair_entry_if_present(
                &quarantine.join(format!("{}-{}", archive.name, archive.version)),
            )?;
        }
        if fs::read_dir(&quarantine)?.next().is_some() {
            return Err(PackageRuntimeError::InvalidResolution);
        }
        fs::remove_dir(&quarantine)?;
        File::open(
            quarantine
                .parent()
                .ok_or(PackageRuntimeError::InvalidPath)?,
        )?
        .sync_all()?;
        Ok(())
    }

    fn prepare_removal_repair(
        &self,
        package: &str,
        version: &str,
    ) -> Result<String, PackageRuntimeError> {
        let snapshot = self.arch_root.join(PACKAGE_REMOVAL_REPAIR_DIRECTORY);
        if self.removal_repair_exists()? {
            return removal_repair_sha256(&snapshot, package, version);
        }
        let temporary = self.arch_root.join(PACKAGE_REMOVAL_REPAIR_TEMP_DIRECTORY);
        remove_database_repair_entry_if_present(&temporary)?;
        let source = self
            .arch_root
            .join("var/lib/pacman/local")
            .join(format!("{package}-{version}"));
        if !local_database_entry_matches(&source, package, version)? {
            return Err(PackageRuntimeError::InvalidResolution);
        }
        copy_database_repair_entry(&source, &temporary)?;
        let digest = removal_repair_sha256(&temporary, package, version)?;
        fs::rename(&temporary, &snapshot)?;
        File::open(snapshot.parent().ok_or(PackageRuntimeError::InvalidPath)?)?.sync_all()?;
        Ok(digest)
    }

    fn restore_removal_repair(
        &self,
        package: &str,
        version: &str,
        expected_sha256: &str,
    ) -> Result<(), PackageRuntimeError> {
        if !valid_sha256_hex(expected_sha256) {
            return Err(PackageRuntimeError::InvalidResolution);
        }
        let snapshot = self.arch_root.join(PACKAGE_REMOVAL_REPAIR_DIRECTORY);
        let actual_sha256 = removal_repair_sha256(&snapshot, package, version)?;
        if actual_sha256 != expected_sha256 {
            return Err(PackageRuntimeError::InvalidResolution);
        }
        let local = self.arch_root.join("var/lib/pacman/local");
        let destination = local.join(format!("{package}-{version}"));
        if local_database_entry_matches(&destination, package, version)? {
            return Ok(());
        }
        remove_database_repair_entry_if_present(&destination)?;
        let temporary = self.arch_root.join(PACKAGE_REMOVAL_LOCAL_TEMP_DIRECTORY);
        remove_database_repair_entry_if_present(&temporary)?;
        copy_database_repair_entry(&snapshot, &temporary)?;
        if removal_repair_sha256(&temporary, package, version)? != expected_sha256 {
            return Err(PackageRuntimeError::InvalidResolution);
        }
        fs::rename(&temporary, &destination)?;
        File::open(&local)?.sync_all()?;
        Ok(())
    }

    fn removal_repair_exists(&self) -> Result<bool, PackageRuntimeError> {
        let snapshot = self.arch_root.join(PACKAGE_REMOVAL_REPAIR_DIRECTORY);
        match fs::symlink_metadata(&snapshot) {
            Ok(_) => {
                validate_database_repair_entry(&snapshot)?;
                Ok(true)
            }
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(false),
            Err(error) => Err(PackageRuntimeError::Io(error)),
        }
    }

    fn clear_removal_repair(&self) -> Result<(), PackageRuntimeError> {
        let snapshot = self.arch_root.join(PACKAGE_REMOVAL_REPAIR_DIRECTORY);
        let temporary = self.arch_root.join(PACKAGE_REMOVAL_REPAIR_TEMP_DIRECTORY);
        remove_database_repair_entry_if_present(&temporary)?;
        match fs::symlink_metadata(&snapshot) {
            Ok(_) => {
                validate_database_repair_entry(&snapshot)?;
                fs::rename(&snapshot, &temporary)?;
                File::open(snapshot.parent().ok_or(PackageRuntimeError::InvalidPath)?)?
                    .sync_all()?;
            }
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => return Err(PackageRuntimeError::Io(error)),
        }
        remove_database_repair_entry_if_present(&temporary)?;
        remove_database_repair_entry_if_present(
            &self.arch_root.join(PACKAGE_REMOVAL_LOCAL_TEMP_DIRECTORY),
        )
    }

    fn clear_orphaned_removal_repair(&self) -> Result<(), PackageRuntimeError> {
        self.clear_removal_repair()
    }

    fn recover_database_lock(&self) -> Result<(), PackageRuntimeError> {
        let lock = self.arch_root.join("var/lib/pacman/db.lck");
        match fs::remove_file(lock) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
            Err(error) => Err(PackageRuntimeError::Io(error)),
        }
    }

    fn preserve_explicit_install_reasons(
        &self,
        archives: &mut [InstallArchive],
    ) -> Result<(), PackageRuntimeError> {
        let local = self.arch_root.join("var/lib/pacman/local");
        let metadata = match fs::symlink_metadata(&local) {
            Ok(metadata) => metadata,
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(()),
            Err(error) => return Err(PackageRuntimeError::Io(error)),
        };
        if metadata.file_type().is_symlink() || !metadata.is_dir() {
            return Err(PackageRuntimeError::UnsafeEntry(local));
        }
        let mut count = 0_usize;
        let mut contents = String::with_capacity(4096);
        for entry in fs::read_dir(&local)? {
            count = count.saturating_add(1);
            if count > LOCAL_DATABASE_ENTRY_LIMIT {
                return Err(PackageRuntimeError::OutputLimit);
            }
            let entry = entry?;
            let entry_path = entry.path();
            let metadata = fs::symlink_metadata(&entry_path)?;
            if entry.file_name() == "ALPM_DB_VERSION"
                && metadata.is_file()
                && !metadata.file_type().is_symlink()
                && metadata.len() > 0
                && metadata.len() <= 64
            {
                continue;
            }
            if metadata.file_type().is_symlink() || !metadata.is_dir() {
                return Err(PackageRuntimeError::UnsafeEntry(entry_path));
            }
            let description = entry_path.join("desc");
            let metadata = match fs::symlink_metadata(&description) {
                Ok(metadata) => metadata,
                Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
                Err(error) => return Err(PackageRuntimeError::Io(error)),
            };
            if metadata.file_type().is_symlink()
                || !metadata.is_file()
                || metadata.len() == 0
                || metadata.len() > LOCAL_DESCRIPTION_LIMIT
            {
                return Err(PackageRuntimeError::UnsafeEntry(description));
            }
            contents.clear();
            File::open(&description)?
                .take(LOCAL_DESCRIPTION_LIMIT + 1)
                .read_to_string(&mut contents)?;
            if u64::try_from(contents.len()).map_err(|_| PackageRuntimeError::OutputLimit)?
                != metadata.len()
            {
                return Err(PackageRuntimeError::SizeMismatch);
            }
            let Some(name) = local_description_field(&contents, "%NAME%")? else {
                return Err(PackageRuntimeError::InvalidResolution);
            };
            let Some(archive) = archives.iter_mut().find(|archive| archive.name == name) else {
                continue;
            };
            match local_description_field(&contents, "%REASON%")? {
                None | Some("0") => archive.explicitly_installed = true,
                Some("1") => {}
                Some(_) => return Err(PackageRuntimeError::InvalidResolution),
            }
        }
        Ok(())
    }

    pub fn begin_package_download(
        &self,
        filename: &str,
        expected_size: u64,
        signature: bool,
    ) -> Result<PackagePayloadDownload, PackageRuntimeError> {
        if !safe_package_filename(filename)
            || expected_size == 0
            || expected_size > PACKAGE_ARCHIVE_LIMIT
        {
            return Err(PackageRuntimeError::InvalidPayload);
        }
        let directory = self.arch_root.join(PACKAGE_CACHE_DIRECTORY);
        let metadata = fs::symlink_metadata(&directory)?;
        if metadata.file_type().is_symlink() || !metadata.is_dir() {
            return Err(PackageRuntimeError::UnsafeEntry(directory));
        }
        let destination_name = if signature {
            format!("{filename}.sig")
        } else {
            filename.to_owned()
        };
        let temporary_name = format!("{destination_name}.part");
        let destination = directory.join(destination_name);
        let temporary = directory.join(temporary_name);
        prepare_output_path(&temporary)?;
        let file = OpenOptions::new()
            .create_new(true)
            .read(true)
            .write(true)
            .open(&temporary)?;
        file.set_permissions(fs::Permissions::from_mode(0o600))?;
        Ok(PackagePayloadDownload {
            file,
            temporary,
            destination,
            expected_size,
            signature,
            active: true,
        })
    }

    pub fn begin_aur_source_download(
        &self,
        filename: &str,
        expected_checksum: aur::AurSourceChecksum,
        maximum_size: u64,
    ) -> Result<aur::AurSourceDownload, PackageRuntimeError> {
        aur::AurSourceDownload::begin(&self.arch_root, filename, expected_checksum, maximum_size)
    }

    pub fn verified_aur_source_size(
        &self,
        filename: &str,
        expected_checksum: aur::AurSourceChecksum,
        maximum_size: u64,
    ) -> Result<Option<u64>, PackageRuntimeError> {
        aur::AurSourceDownload::verified_cache_size(
            &self.arch_root,
            filename,
            expected_checksum,
            maximum_size,
        )
    }

    pub fn open_verified_aur_source(
        &self,
        filename: &str,
        expected_checksum: aur::AurSourceChecksum,
    ) -> Result<File, PackageRuntimeError> {
        aur::AurSourceDownload::open_verified_cache(
            &self.arch_root,
            filename,
            expected_checksum,
            aur::MAX_AUR_SOURCE_BYTES,
        )
    }

    pub fn retain_reviewed_aur_snapshot(
        &self,
        package_base: &str,
        expected_sha256: [u8; 32],
        bytes: &[u8],
    ) -> Result<u64, PackageRuntimeError> {
        aur::retain_reviewed_snapshot(&self.arch_root, package_base, expected_sha256, bytes)
    }

    pub fn open_reviewed_aur_snapshot(
        &self,
        package_base: &str,
        expected_sha256: [u8; 32],
    ) -> Result<File, PackageRuntimeError> {
        aur::open_reviewed_snapshot(&self.arch_root, package_base, expected_sha256)
    }

    pub fn package_cache_catalog(&self) -> Result<PackageCacheCatalog, PackageRuntimeError> {
        let (_, artifacts) = self.scan_package_cache()?;
        let mut entries: Vec<PackageCacheEntry> = Vec::new();
        let mut total_bytes = 0_u64;
        for artifact in artifacts {
            total_bytes = total_bytes
                .checked_add(artifact.bytes)
                .ok_or(PackageRuntimeError::OutputLimit)?;
            if let Some(entry) = entries.last_mut()
                && entry.package == artifact.package
                && entry.version == artifact.version
                && entry.architecture == artifact.architecture
            {
                entry.bytes = entry
                    .bytes
                    .checked_add(artifact.bytes)
                    .ok_or(PackageRuntimeError::OutputLimit)?;
                entry.artifacts = entry
                    .artifacts
                    .checked_add(1)
                    .ok_or(PackageRuntimeError::OutputLimit)?;
                continue;
            }
            entries.push(PackageCacheEntry {
                package: artifact.package,
                version: artifact.version,
                architecture: artifact.architecture,
                bytes: artifact.bytes,
                artifacts: 1,
            });
        }
        Ok(PackageCacheCatalog {
            entries,
            total_bytes,
        })
    }

    pub fn clear_package_cache_packages(
        &self,
        packages: &[&str],
    ) -> Result<u64, PackageRuntimeError> {
        if self.read_pending_mutation()?.is_some() {
            return Err(PackageRuntimeError::Busy);
        }
        if packages.is_empty() || packages.len() > 256 {
            return Err(PackageRuntimeError::InvalidQuery);
        }
        let mut selected = BTreeSet::new();
        for package in packages {
            if !safe_logical_name(package) || !selected.insert(*package) {
                return Err(PackageRuntimeError::InvalidQuery);
            }
        }
        let (directory, artifacts) = self.scan_package_cache()?;
        let mut reclaimed_bytes = 0_u64;
        for artifact in artifacts {
            if selected.contains(artifact.package.as_str()) {
                reclaimed_bytes = reclaimed_bytes
                    .checked_add(artifact.bytes)
                    .ok_or(PackageRuntimeError::OutputLimit)?;
                fs::remove_file(artifact.path)?;
            }
        }
        File::open(directory)?.sync_all()?;
        Ok(reclaimed_bytes)
    }

    pub fn clear_package_cache(&self) -> Result<u64, PackageRuntimeError> {
        if self.read_pending_mutation()?.is_some() {
            return Err(PackageRuntimeError::Busy);
        }
        let (directory, artifacts) = self.scan_package_cache()?;
        let mut reclaimed_bytes = 0_u64;
        for artifact in artifacts {
            reclaimed_bytes = reclaimed_bytes
                .checked_add(artifact.bytes)
                .ok_or(PackageRuntimeError::OutputLimit)?;
            fs::remove_file(artifact.path)?;
        }
        File::open(directory)?.sync_all()?;
        Ok(reclaimed_bytes)
    }

    pub fn clear_aur_build_cache(&self) -> Result<u64, PackageRuntimeError> {
        if self.read_pending_mutation()?.is_some() {
            return Err(PackageRuntimeError::Busy);
        }
        let mut directories = Vec::new();
        let mut artifacts = Vec::new();
        let mut reclaimed_bytes = 0_u64;
        for relative in [
            AUR_PACKAGE_CACHE_DIRECTORY,
            aur::AUR_SNAPSHOT_CACHE_DIRECTORY,
            aur::AUR_SOURCE_CACHE_DIRECTORY,
        ] {
            let directory = self.arch_root.join(relative);
            let metadata = match fs::symlink_metadata(&directory) {
                Ok(metadata) => metadata,
                Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
                Err(error) => return Err(PackageRuntimeError::Io(error)),
            };
            if metadata.file_type().is_symlink() || !metadata.is_dir() {
                return Err(PackageRuntimeError::UnsafeEntry(directory));
            }
            for entry in fs::read_dir(&directory)? {
                if artifacts.len() >= LOCAL_DATABASE_ENTRY_LIMIT {
                    return Err(PackageRuntimeError::OutputLimit);
                }
                let entry = entry?;
                let path = entry.path();
                let name = entry
                    .file_name()
                    .into_string()
                    .map_err(|_| PackageRuntimeError::UnsafeEntry(path.clone()))?;
                let metadata = fs::symlink_metadata(&path)?;
                if name.is_empty()
                    || name.len() > 255
                    || name == "."
                    || name == ".."
                    || name
                        .bytes()
                        .any(|byte| byte.is_ascii_control() || byte == b'/')
                    || metadata.file_type().is_symlink()
                    || !metadata.is_file()
                {
                    return Err(PackageRuntimeError::UnsafeEntry(path));
                }
                reclaimed_bytes = reclaimed_bytes
                    .checked_add(metadata.len())
                    .ok_or(PackageRuntimeError::OutputLimit)?;
                artifacts.push(path);
            }
            directories.push(directory);
        }
        for artifact in artifacts {
            fs::remove_file(artifact)?;
        }
        for directory in directories {
            File::open(directory)?.sync_all()?;
        }
        Ok(reclaimed_bytes)
    }

    fn scan_package_cache(
        &self,
    ) -> Result<(PathBuf, Vec<PackageCacheArtifact>), PackageRuntimeError> {
        let directory = self.arch_root.join(PACKAGE_CACHE_DIRECTORY);
        let metadata = fs::symlink_metadata(&directory)?;
        if metadata.file_type().is_symlink() || !metadata.is_dir() {
            return Err(PackageRuntimeError::UnsafeEntry(directory));
        }

        let mut artifacts = Vec::new();
        for entry in fs::read_dir(&directory)? {
            let entry = entry?;
            if artifacts.len() >= LOCAL_DATABASE_ENTRY_LIMIT {
                return Err(PackageRuntimeError::OutputLimit);
            }
            let path = entry.path();
            let name = entry
                .file_name()
                .into_string()
                .map_err(|_| PackageRuntimeError::UnsafeEntry(path.clone()))?;
            let metadata = fs::symlink_metadata(&path)?;
            if !safe_package_cache_filename(&name)
                || metadata.file_type().is_symlink()
                || !metadata.is_file()
            {
                return Err(PackageRuntimeError::UnsafeEntry(path));
            }
            let Some((package, version, release, architecture)) =
                parse_package_cache_filename(&name)
            else {
                return Err(PackageRuntimeError::UnsafeEntry(path));
            };
            artifacts.push(PackageCacheArtifact {
                path,
                package: package.to_owned(),
                version: format!("{version}-{release}"),
                architecture: architecture.to_owned(),
                bytes: metadata.len(),
            });
        }
        artifacts.sort_unstable_by(|left, right| {
            (
                left.package.as_str(),
                left.version.as_str(),
                left.architecture.as_str(),
                left.path.as_os_str(),
            )
                .cmp(&(
                    right.package.as_str(),
                    right.version.as_str(),
                    right.architecture.as_str(),
                    right.path.as_os_str(),
                ))
        });
        Ok((directory, artifacts))
    }

    pub fn verify_package(
        &self,
        filename: &str,
        expected_name: &str,
        expected_version: &str,
        expected_size: u64,
    ) -> Result<ToolOutput, PackageRuntimeError> {
        if !safe_package_filename(filename)
            || !safe_logical_name(expected_name)
            || expected_version.is_empty()
            || expected_version.len() > 128
            || expected_version
                .bytes()
                .any(|byte| byte.is_ascii_whitespace() || byte == 0)
            || expected_size == 0
            || expected_size > PACKAGE_ARCHIVE_LIMIT
        {
            return Err(PackageRuntimeError::InvalidPayload);
        }
        let package = self.arch_root.join(PACKAGE_CACHE_DIRECTORY).join(filename);
        let signature = self
            .arch_root
            .join(PACKAGE_CACHE_DIRECTORY)
            .join(format!("{filename}.sig"));
        for (path, maximum) in [
            (&package, expected_size),
            (&signature, PACKAGE_SIGNATURE_LIMIT),
        ] {
            let metadata = fs::symlink_metadata(path)?;
            if metadata.file_type().is_symlink()
                || !metadata.is_file()
                || metadata.len() == 0
                || metadata.len() > maximum
                || path == &package && metadata.len() != expected_size
            {
                return Err(PackageRuntimeError::InvalidPayload);
            }
        }
        let keyring = self
            .keyring
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let package = package.to_str().ok_or(PackageRuntimeError::InvalidPath)?;
        let signature = signature.to_str().ok_or(PackageRuntimeError::InvalidPath)?;
        let output = self.run(
            PackageTool::Gpgv,
            &["--keyring", keyring, "--status-fd", "1", signature, package],
        )?;
        validate_signature_status(output.as_bytes(), self.architecture)?;
        let package_info = self.run(PackageTool::Bsdtar, &["-xOf", package, ".PKGINFO"])?;
        validate_package_info(
            package_info.as_str()?,
            expected_name,
            expected_version,
            self.architecture,
        )?;
        Ok(output)
    }

    pub fn verify_resolution(
        &self,
        resolution: &PackageResolution,
    ) -> Result<VerifiedPackageClosure, PackageRuntimeError> {
        let mut package_count = 0_usize;
        let mut archive_bytes = 0_u64;
        let mut manifest = String::with_capacity(
            resolution
                .as_bytes()
                .len()
                .min(MAX_VERIFIED_PACKAGE_CLOSURE_BYTES),
        );
        manifest.push_str("ABPC0001\n");
        for line in resolution.as_str()?.lines() {
            let payload = parse_resolved_payload(line)?;
            self.verify_package(
                payload.filename,
                payload.name,
                payload.version,
                payload.size,
            )?;
            package_count = package_count
                .checked_add(1)
                .ok_or(PackageRuntimeError::OutputLimit)?;
            archive_bytes = archive_bytes
                .checked_add(payload.size)
                .ok_or(PackageRuntimeError::OutputLimit)?;
            if package_count > 512 {
                return Err(PackageRuntimeError::OutputLimit);
            }
            let archive = self
                .arch_root
                .join(PACKAGE_CACHE_DIRECTORY)
                .join(payload.filename);
            let signature = self
                .arch_root
                .join(PACKAGE_CACHE_DIRECTORY)
                .join(format!("{}.sig", payload.filename));
            let archive_sha256 = hash_regular_file(&archive, payload.size, payload.size)?;
            let signature_metadata = fs::symlink_metadata(&signature)?;
            if signature_metadata.file_type().is_symlink()
                || !signature_metadata.is_file()
                || signature_metadata.len() == 0
                || signature_metadata.len() > PACKAGE_SIGNATURE_LIMIT
            {
                return Err(PackageRuntimeError::InvalidPayload);
            }
            let signature_sha256 = hash_regular_file(
                &signature,
                signature_metadata.len(),
                PACKAGE_SIGNATURE_LIMIT,
            )?;
            writeln!(
                manifest,
                "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}",
                payload.repository,
                payload.name,
                payload.version,
                payload.filename,
                payload.url,
                payload.size,
                hex_sha256(&archive_sha256),
                signature_metadata.len(),
                hex_sha256(&signature_sha256),
            )
            .map_err(|_| PackageRuntimeError::OutputLimit)?;
            if manifest.len() > MAX_VERIFIED_PACKAGE_CLOSURE_BYTES {
                return Err(PackageRuntimeError::OutputLimit);
            }
        }
        if package_count == 0 {
            return Err(PackageRuntimeError::InvalidResolution);
        }
        writeln!(manifest, "summary\t{package_count}\t{archive_bytes}")
            .map_err(|_| PackageRuntimeError::OutputLimit)?;
        if manifest.len() > MAX_VERIFIED_PACKAGE_CLOSURE_BYTES {
            return Err(PackageRuntimeError::OutputLimit);
        }
        Ok(VerifiedPackageClosure {
            bytes: manifest.into_bytes(),
        })
    }

    pub fn open_verified_resolution_file(
        &self,
        resolution: &PackageResolution,
        index: usize,
        signature: bool,
    ) -> Result<File, PackageRuntimeError> {
        let line = resolution
            .as_str()?
            .lines()
            .nth(index)
            .ok_or(PackageRuntimeError::InvalidPayload)?;
        let payload = parse_resolved_payload(line)?;
        self.verify_package(
            payload.filename,
            payload.name,
            payload.version,
            payload.size,
        )?;
        let filename = if signature {
            format!("{}.sig", payload.filename)
        } else {
            payload.filename.to_owned()
        };
        let path = self.arch_root.join(PACKAGE_CACHE_DIRECTORY).join(filename);
        let file = OpenOptions::new()
            .read(true)
            .custom_flags(O_NOFOLLOW | O_CLOEXEC)
            .open(&path)?;
        let metadata = file.metadata()?;
        let valid_size = if signature {
            metadata.len() > 0 && metadata.len() <= PACKAGE_SIGNATURE_LIMIT
        } else {
            metadata.len() == payload.size
        };
        if !metadata.is_file() || !valid_size {
            return Err(PackageRuntimeError::InvalidPayload);
        }
        Ok(file)
    }

    pub fn prepare_verification_keyring(&mut self) -> Result<(), PackageRuntimeError> {
        let trust_directory = self.arch_root.join(PACKAGE_TRUST_DIRECTORY);
        let source_state = self.verification_keyring_source_state()?;
        match fs::symlink_metadata(&trust_directory) {
            Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_dir() => {
                return Err(PackageRuntimeError::UnsafeEntry(trust_directory));
            }
            Ok(_) if self.reuse_verification_keyring(&trust_directory, &source_state)? => {
                return Ok(());
            }
            Ok(_) => fs::remove_dir_all(&trust_directory)?,
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => return Err(PackageRuntimeError::Io(error)),
        }
        fs::create_dir(&trust_directory)?;
        fs::set_permissions(&trust_directory, fs::Permissions::from_mode(0o700))?;
        let trust = trust_directory
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let anchor = self
            .keyring
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        let ownertrust = self
            .ownertrust
            .to_str()
            .ok_or(PackageRuntimeError::InvalidPath)?;
        self.run_with_timeout(
            PackageTool::Gpg,
            &[
                "--homedir",
                trust,
                "--batch",
                "--quiet",
                "--no-autostart",
                "--no-auto-check-trustdb",
                "--import",
                anchor,
            ],
            Duration::from_secs(60),
        )?;
        self.run_with_timeout(
            PackageTool::Gpg,
            &[
                "--homedir",
                trust,
                "--batch",
                "--quiet",
                "--no-autostart",
                "--import-ownertrust",
                ownertrust,
            ],
            Duration::from_secs(60),
        )?;
        self.run_with_timeout(
            PackageTool::Gpg,
            &[
                "--homedir",
                trust,
                "--batch",
                "--quiet",
                "--no-autostart",
                "--check-trustdb",
            ],
            Duration::from_secs(60),
        )?;
        let keybox = trust_directory.join("pubring.kbx");
        let metadata = fs::symlink_metadata(&keybox)?;
        if metadata.file_type().is_symlink()
            || !metadata.is_file()
            || metadata.len() == 0
            || metadata.len() > PACKAGE_KEYBOX_LIMIT
        {
            return Err(PackageRuntimeError::InvalidSignature);
        }
        let trustdb = trust_directory.join("trustdb.gpg");
        let metadata = fs::symlink_metadata(&trustdb)?;
        if metadata.file_type().is_symlink() || !metadata.is_file() || metadata.len() == 0 {
            return Err(PackageRuntimeError::InvalidSignature);
        }
        if metadata.len() > PACKAGE_TRUSTDB_LIMIT {
            return Err(PackageRuntimeError::InvalidSignature);
        }
        let state_path = trust_directory.join(PACKAGE_TRUST_STATE);
        let mut state_file = OpenOptions::new()
            .create_new(true)
            .write(true)
            .mode(0o600)
            .open(state_path)?;
        state_file.write_all(source_state.as_bytes())?;
        state_file.sync_all()?;
        File::open(&trust_directory)?.sync_all()?;
        self.keyring = keybox;
        Ok(())
    }

    fn verification_keyring_source_state(&self) -> Result<String, PackageRuntimeError> {
        Ok(self.verification_source_state.clone())
    }

    fn reuse_verification_keyring(
        &mut self,
        trust_directory: &Path,
        expected_state: &str,
    ) -> Result<bool, PackageRuntimeError> {
        validate_trust_cache_directory(trust_directory)?;
        let state_path = trust_directory.join(PACKAGE_TRUST_STATE);
        let state = match read_bounded_regular_file(&state_path, PACKAGE_TRUST_STATE_LIMIT)? {
            Some(state) => state,
            None => return Ok(false),
        };
        if state != expected_state.as_bytes() {
            return Ok(false);
        }
        let keybox = trust_directory.join("pubring.kbx");
        let trustdb = trust_directory.join("trustdb.gpg");
        if !bounded_regular_file(&keybox, PACKAGE_KEYBOX_LIMIT)?
            || !bounded_regular_file(&trustdb, PACKAGE_TRUSTDB_LIMIT)?
        {
            return Ok(false);
        }
        self.keyring = keybox;
        Ok(true)
    }

    fn run_with_timeout(
        &self,
        tool: PackageTool,
        arguments: &[&str],
        timeout: Duration,
    ) -> Result<ToolOutput, PackageRuntimeError> {
        let bytes =
            self.run_bytes_with_timeout(tool, arguments, timeout, MAX_TOOL_OUTPUT_BYTES, true)?;
        let mut output = empty_tool_output();
        output.push(&bytes)?;
        Ok(output)
    }

    fn run_bytes_with_timeout(
        &self,
        tool: PackageTool,
        arguments: &[&str],
        timeout: Duration,
        success_limit: usize,
        capture_success: bool,
    ) -> Result<Vec<u8>, PackageRuntimeError> {
        let tool_path = self.tools[tool.index()]
            .as_ref()
            .ok_or(PackageRuntimeError::MissingEntry(tool.logical_name()))?;
        let output_path = self.arch_root.join(OUTPUT_FILE);
        prepare_output_path(&output_path)?;
        let output_file = OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(&output_path)?;
        output_file.set_permissions(fs::Permissions::from_mode(0o600))?;
        let error_file = output_file.try_clone()?;

        let mut child = Command::new(&self.loader)
            .arg("--library-path")
            .arg(&self.library_path)
            .arg(tool_path)
            .args(arguments)
            .current_dir(&self.arch_root)
            .env_clear()
            .env("HOME", self.arch_root.join("home/archphene"))
            .env("TMPDIR", self.arch_root.join("tmp"))
            .env("PATH", &self.executable_path)
            .env("LANG", "C")
            .env("LC_ALL", "C")
            .env("GLIBC_TUNABLES", "glibc.pthread.rseq=0")
            .env("LD_PRELOAD", &self.path_bridge)
            .env("ARCHPHENE_RUNTIME_LOADER", &self.loader)
            .env("ARCHPHENE_RUNTIME_LIB", &self.library_path)
            .env("ARCHPHENE_RUNTIME_COMMAND_DIR", &self.alias_root)
            .env("ARCHPHENE_RUNTIME_ROOT", &self.arch_root)
            .env("ARCHPHENE_RUNTIME_PROGRAM_PATH", tool_path)
            .env("ARCHPHENE_ROOT_IDENTITY", "1")
            .stdin(Stdio::null())
            .stdout(Stdio::from(output_file))
            .stderr(Stdio::from(error_file))
            .spawn()?;
        let deadline = Instant::now() + timeout;
        let status = loop {
            if let Some(status) = child.try_wait()? {
                break status;
            }
            if Instant::now() >= deadline {
                let _ = child.kill();
                let _ = child.wait();
                let _ = fs::remove_file(&output_path);
                return Err(PackageRuntimeError::Timeout);
            }
            thread::sleep(Duration::from_millis(20));
        };

        let result = if status.success() && !capture_success {
            validate_output_size(&output_path, success_limit).map(|_| Vec::new())
        } else {
            let output_limit = if status.success() {
                success_limit
            } else {
                MAX_TOOL_OUTPUT_BYTES
            };
            read_output_bytes(&output_path, output_limit)
        };
        let _ = fs::remove_file(&output_path);
        let bytes = result?;
        let code = status
            .code()
            .or_else(|| status.signal().map(|signal| -signal))
            .unwrap_or(-1);
        if !status.success() {
            let mut output = empty_tool_output();
            output.push(&bytes)?;
            return Err(PackageRuntimeError::ToolFailed(code, Box::new(output)));
        }
        Ok(bytes)
    }

    pub fn alias_root(&self) -> &Path {
        &self.alias_root
    }

    pub fn native_root(&self) -> &Path {
        &self.native_root
    }

    pub fn command_environment(&self) -> Result<CommandEnvironment, PackageRuntimeError> {
        CommandEnvironment::new(
            &self.arch_root,
            &self.loader,
            &self.library_path,
            &self.path_bridge,
            &self.alias_root,
            self.gdk_pixbuf_module_file.as_deref(),
        )
        .map_err(PackageRuntimeError::from)
    }

    pub fn command_environment_with_gui(
        &self,
        appearance: GuiAppearance,
    ) -> Result<CommandEnvironment, PackageRuntimeError> {
        self.command_environment()?
            .with_gui_support(
                self.gtk_settings_module.as_deref(),
                self.qt_plugin_root.as_deref(),
                appearance,
            )
            .map_err(PackageRuntimeError::from)
    }

    pub fn publish_gui_appearance(
        &self,
        appearance: GuiAppearance,
    ) -> Result<(), PackageRuntimeError> {
        publish_gui_appearance(&self.arch_root, appearance).map_err(PackageRuntimeError::from)
    }

    pub fn command_environment_with_gui_and_portal(
        &self,
        appearance: GuiAppearance,
        portal_bus_address: &str,
    ) -> Result<CommandEnvironment, PackageRuntimeError> {
        self.command_environment_with_gui(appearance)?
            .with_portal_bus_address(portal_bus_address)
            .map_err(PackageRuntimeError::from)
    }
}

fn system_trust_bundle_ready(arch_root: &Path) -> bool {
    let bundle = arch_root.join(SYSTEM_TRUST_BUNDLE);
    let Ok(canonical_root) = arch_root.canonicalize() else {
        return false;
    };
    let Ok(resolved) = bundle.canonicalize() else {
        return false;
    };
    if resolved == canonical_root || !resolved.starts_with(&canonical_root) {
        return false;
    }
    let Ok(metadata) = fs::symlink_metadata(resolved) else {
        return false;
    };
    !metadata.file_type().is_symlink()
        && metadata.is_file()
        && metadata.permissions().mode() & 0o022 == 0
        && metadata.len() > 0
        && metadata.len() <= SYSTEM_TRUST_BUNDLE_LIMIT
}

#[derive(Debug)]
pub struct CatalogDownload {
    repository: Repository,
    file: File,
    temporary: PathBuf,
    destination: PathBuf,
    active: bool,
}

#[derive(Debug)]
pub struct PackagePayloadDownload {
    file: File,
    temporary: PathBuf,
    destination: PathBuf,
    expected_size: u64,
    signature: bool,
    active: bool,
}

impl PackagePayloadDownload {
    pub fn duplicate_file(&self) -> Result<File, PackageRuntimeError> {
        Ok(self.file.try_clone()?)
    }

    pub fn finish(mut self) -> Result<u64, PackageRuntimeError> {
        let metadata = self.file.metadata()?;
        let length = metadata.len();
        let valid_length = if self.signature {
            length > 0 && length <= PACKAGE_SIGNATURE_LIMIT
        } else {
            length == self.expected_size
        };
        if !metadata.is_file() || !valid_length {
            return Err(PackageRuntimeError::InvalidPayload);
        }
        self.file.sync_all()?;
        match fs::symlink_metadata(&self.destination) {
            Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_file() => {
                return Err(PackageRuntimeError::UnsafeEntry(self.destination.clone()));
            }
            Ok(_) => {}
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => return Err(PackageRuntimeError::Io(error)),
        }
        fs::rename(&self.temporary, &self.destination)?;
        self.active = false;
        File::open(
            self.destination
                .parent()
                .ok_or(PackageRuntimeError::InvalidPath)?,
        )?
        .sync_all()?;
        Ok(length)
    }
}

impl Drop for PackagePayloadDownload {
    fn drop(&mut self) {
        if self.active {
            let _ = fs::remove_file(&self.temporary);
        }
    }
}

impl CatalogDownload {
    pub fn repository(&self) -> Repository {
        self.repository
    }

    pub fn duplicate_file(&self) -> Result<File, PackageRuntimeError> {
        Ok(self.file.try_clone()?)
    }

    pub fn finish(mut self) -> Result<u64, PackageRuntimeError> {
        let metadata = self.file.metadata()?;
        let length = metadata.len();
        if !metadata.is_file() || length == 0 || length > self.repository.size_limit() {
            return Err(PackageRuntimeError::InvalidCatalog);
        }
        self.file.sync_all()?;
        match fs::symlink_metadata(&self.destination) {
            Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_file() => {
                return Err(PackageRuntimeError::UnsafeEntry(self.destination.clone()));
            }
            Ok(_) => {}
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => return Err(PackageRuntimeError::Io(error)),
        }
        fs::rename(&self.temporary, &self.destination)?;
        self.active = false;
        File::open(
            self.destination
                .parent()
                .ok_or(PackageRuntimeError::InvalidPath)?,
        )?
        .sync_all()?;
        Ok(length)
    }
}

impl Drop for CatalogDownload {
    fn drop(&mut self) {
        if self.active {
            let _ = fs::remove_file(&self.temporary);
        }
    }
}

fn publish_regular_file(
    destination: &Path,
    temporary: &Path,
    content: &[u8],
) -> Result<(), PackageRuntimeError> {
    for path in [destination, temporary] {
        match fs::symlink_metadata(path) {
            Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_file() => {
                return Err(PackageRuntimeError::UnsafeEntry(path.to_path_buf()));
            }
            Ok(_) if path == temporary => fs::remove_file(path)?,
            Ok(_) => {}
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => return Err(PackageRuntimeError::Io(error)),
        }
    }
    let mut file = OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(temporary)?;
    file.set_permissions(fs::Permissions::from_mode(0o600))?;
    file.write_all(content)?;
    file.sync_all()?;
    drop(file);
    fs::rename(temporary, destination)?;
    Ok(())
}

fn prepare_gdk_pixbuf_module_file(
    arch_root: &Path,
    alias_root: &Path,
) -> Result<PathBuf, PackageRuntimeError> {
    let loader = alias_root.join(GDK_PIXBUF_SVG_LOADER);
    let loader_metadata = fs::symlink_metadata(&loader)?;
    if !loader_metadata.file_type().is_symlink() {
        return Err(PackageRuntimeError::UnsafeEntry(loader));
    }
    let resolved_loader = loader.canonicalize()?;
    let resolved_loader_metadata = fs::symlink_metadata(&resolved_loader)?;
    let resolved_alias_root = alias_root.canonicalize()?;
    let resolved_arch_root = arch_root.canonicalize()?;
    if resolved_alias_root == resolved_arch_root
        || !resolved_alias_root.starts_with(&resolved_arch_root)
        || resolved_loader == resolved_arch_root
        || resolved_loader_metadata.file_type().is_symlink()
        || !resolved_loader_metadata.is_file()
    {
        return Err(PackageRuntimeError::UnsafeEntry(resolved_loader));
    }
    let loader_path = loader.to_str().ok_or(PackageRuntimeError::InvalidPath)?;
    if loader_path.contains(['\n', '\r', '"']) {
        return Err(PackageRuntimeError::InvalidPath);
    }
    let content = format!(
        "# GdkPixbuf Image Loader Modules file\n\
\"{loader_path}\"\n\
\"svg\" 6 \"gdk-pixbuf\" \"Scalable Vector Graphics\" \"LGPL\"\n\
\"image/svg+xml\" \"image/svg\" \"image/svg-xml\" \"image/vnd.adobe.svg+xml\" \
\"text/xml-svg\" \"image/svg+xml-compressed\" \"\"\n\
\"svg\" \"svgz\" \"svg.gz\" \"\"\n\
\" <svg\" \"*    \" 100\n\
\" <!DOCTYPE svg\" \"*             \" 100\n"
    );
    let destination = arch_root.join(GDK_PIXBUF_MODULE_FILE);
    let temporary = arch_root.join(GDK_PIXBUF_MODULE_TEMP_FILE);
    publish_regular_file(&destination, &temporary, content.as_bytes())?;
    Ok(destination)
}

fn prepare_toolkit_plugin_directory(
    arch_root: &Path,
    alias_root: &Path,
) -> Result<PathBuf, PackageRuntimeError> {
    let root = arch_root.join(TOOLKIT_PLUGIN_DIRECTORY);
    prepare_known_directory(&root, &["platformthemes", "styles"])?;
    let platformthemes = root.join("platformthemes");
    let styles = root.join("styles");
    prepare_known_directory(&platformthemes, &[])?;
    prepare_known_directory(&styles, &[])?;
    symlink(
        alias_root.join(QT_PLATFORM_THEME_LIBRARY),
        platformthemes.join(QT_PLATFORM_THEME_LIBRARY),
    )?;
    symlink(
        alias_root.join(QT_STYLE_LIBRARY),
        styles.join(QT_STYLE_LIBRARY),
    )?;
    // Keep the helper next to the plugin root as well as in the verified
    // runtime library path. The platform theme can resolve it without adding
    // another loader search path.
    symlink(
        alias_root.join(QT_KDE_CONFIG_LIBRARY),
        root.join(QT_KDE_CONFIG_LIBRARY),
    )?;
    Ok(root)
}

fn prepare_known_directory(
    path: &Path,
    allowed_directories: &[&str],
) -> Result<(), PackageRuntimeError> {
    match fs::symlink_metadata(path) {
        Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_dir() => {
            return Err(PackageRuntimeError::UnsafeEntry(path.to_path_buf()));
        }
        Ok(_) => {}
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            fs::create_dir(path)?;
        }
        Err(error) => return Err(PackageRuntimeError::Io(error)),
    }
    fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
    let mut count = 0_usize;
    for entry in fs::read_dir(path)? {
        count = count.saturating_add(1);
        if count > 16 {
            return Err(PackageRuntimeError::InvalidManifest);
        }
        let entry = entry?;
        let name = entry.file_name();
        let name = name.to_str().ok_or(PackageRuntimeError::InvalidPath)?;
        let metadata = fs::symlink_metadata(entry.path())?;
        if allowed_directories.contains(&name) {
            if metadata.file_type().is_symlink() || !metadata.is_dir() {
                return Err(PackageRuntimeError::UnsafeEntry(entry.path()));
            }
        } else if metadata.file_type().is_symlink() {
            fs::remove_file(entry.path())?;
        } else {
            return Err(PackageRuntimeError::UnsafeEntry(entry.path()));
        }
    }
    Ok(())
}

struct ManifestEntry<'a> {
    role: &'a str,
    logical: &'a str,
    packaged: &'a str,
    size: u64,
}

fn parse_entry(line: &str) -> Result<ManifestEntry<'_>, PackageRuntimeError> {
    let mut fields = line.split('\t');
    let role = fields.next().ok_or(PackageRuntimeError::InvalidManifest)?;
    let logical = fields.next().ok_or(PackageRuntimeError::InvalidManifest)?;
    let packaged = fields.next().ok_or(PackageRuntimeError::InvalidManifest)?;
    let size = fields
        .next()
        .ok_or(PackageRuntimeError::InvalidManifest)?
        .parse::<u64>()
        .map_err(|_| PackageRuntimeError::InvalidManifest)?;
    if fields.next().is_some()
        || !matches!(
            role,
            "loader" | "tool" | "library" | "keyring" | "ownertrust"
        )
        || !safe_logical_name(logical)
        || !safe_packaged_name(packaged)
        || size == 0
        || size > 256 * 1024 * 1024
    {
        return Err(PackageRuntimeError::InvalidManifest);
    }
    Ok(ManifestEntry {
        role,
        logical,
        packaged,
        size,
    })
}

fn safe_logical_name(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 128
        && value != "."
        && value != ".."
        && value.bytes().all(|byte| {
            byte.is_ascii_alphanumeric() || matches!(byte, b'@' | b'.' | b'_' | b'+' | b'-')
        })
}

fn safe_packaged_name(value: &str) -> bool {
    let Some(hash) = value
        .strip_prefix("libarchphene_pkg_")
        .and_then(|value| value.strip_suffix(".so"))
    else {
        return false;
    };
    hash.len() == 24
        && hash
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
}

fn tool_from_logical(value: &str) -> Option<PackageTool> {
    match value {
        "@pacman" => Some(PackageTool::Pacman),
        "@bsdtar" => Some(PackageTool::Bsdtar),
        "@gpg" => Some(PackageTool::Gpg),
        "@gpgv" => Some(PackageTool::Gpgv),
        "@gpgconf" => Some(PackageTool::Gpgconf),
        _ => None,
    }
}

fn validate_absolute_path(path: &Path) -> Result<(), PackageRuntimeError> {
    if !path.is_absolute() || path.as_os_str().as_encoded_bytes().len() > 1024 {
        return Err(PackageRuntimeError::InvalidPath);
    }
    if path
        .components()
        .any(|component| matches!(component, Component::ParentDir | Component::CurDir))
    {
        return Err(PackageRuntimeError::InvalidPath);
    }
    Ok(())
}

fn prepare_alias_directory(path: &Path) -> Result<(), PackageRuntimeError> {
    match fs::symlink_metadata(path) {
        Ok(metadata) => {
            if metadata.file_type().is_symlink() || !metadata.is_dir() {
                return Err(PackageRuntimeError::UnsafeEntry(path.to_path_buf()));
            }
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            fs::create_dir(path)?;
        }
        Err(error) => return Err(PackageRuntimeError::Io(error)),
    }
    fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
    let mut count = 0_usize;
    for entry in fs::read_dir(path)? {
        count = count.saturating_add(1);
        if count > MAX_MANIFEST_ENTRIES {
            return Err(PackageRuntimeError::InvalidManifest);
        }
        let entry = entry?;
        let metadata = fs::symlink_metadata(entry.path())?;
        if !metadata.file_type().is_symlink() {
            return Err(PackageRuntimeError::UnsafeEntry(entry.path()));
        }
        fs::remove_file(entry.path())?;
    }
    Ok(())
}

fn prepare_private_directory(path: &Path) -> Result<(), PackageRuntimeError> {
    let parent = path.parent().ok_or(PackageRuntimeError::InvalidPath)?;
    match fs::symlink_metadata(parent) {
        Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_dir() => {
            return Err(PackageRuntimeError::UnsafeEntry(parent.to_path_buf()));
        }
        Ok(_) => {}
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            let grandparent = parent.parent().ok_or(PackageRuntimeError::InvalidPath)?;
            let metadata = fs::symlink_metadata(grandparent)?;
            if metadata.file_type().is_symlink() || !metadata.is_dir() {
                return Err(PackageRuntimeError::UnsafeEntry(grandparent.to_path_buf()));
            }
            fs::create_dir(parent)?;
            fs::set_permissions(parent, fs::Permissions::from_mode(0o700))?;
        }
        Err(error) => return Err(PackageRuntimeError::Io(error)),
    }
    match fs::symlink_metadata(path) {
        Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_dir() => {
            Err(PackageRuntimeError::UnsafeEntry(path.to_path_buf()))
        }
        Ok(_) => {
            fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
            Ok(())
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            fs::create_dir(path)?;
            fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
            Ok(())
        }
        Err(error) => Err(PackageRuntimeError::Io(error)),
    }
}

fn verified_source(
    native_root: &Path,
    packaged: &str,
    expected_size: u64,
) -> Result<PathBuf, PackageRuntimeError> {
    let candidate = native_root.join(packaged);
    let metadata = fs::symlink_metadata(&candidate)?;
    if metadata.file_type().is_symlink() || !metadata.is_file() {
        return Err(PackageRuntimeError::UnsafeEntry(candidate));
    }
    if metadata.len() != expected_size {
        return Err(PackageRuntimeError::SizeMismatch);
    }
    let canonical = candidate.canonicalize()?;
    if canonical.parent() != Some(native_root) {
        return Err(PackageRuntimeError::UnsafeEntry(canonical));
    }
    Ok(canonical)
}

fn immutable_source_identity(
    native_root: &Path,
    path: &Path,
) -> Result<String, PackageRuntimeError> {
    let metadata = fs::symlink_metadata(path)?;
    if metadata.file_type().is_symlink() || !metadata.is_file() || metadata.len() == 0 {
        return Err(PackageRuntimeError::UnsafeEntry(path.to_path_buf()));
    }
    let canonical = path.canonicalize()?;
    if canonical.parent() != Some(native_root) {
        return Err(PackageRuntimeError::UnsafeEntry(canonical));
    }
    let name = canonical
        .file_name()
        .and_then(|name| name.to_str())
        .ok_or(PackageRuntimeError::InvalidManifest)?;
    if name.is_empty()
        || name.len() > 128
        || !name
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
    {
        return Err(PackageRuntimeError::InvalidManifest);
    }
    Ok(format!("{name}\t{}", metadata.len()))
}

fn verification_source_state(
    native_root: &Path,
    keyring: &Path,
    ownertrust: &Path,
) -> Result<String, PackageRuntimeError> {
    let keyring = immutable_source_identity(native_root, keyring)?;
    let ownertrust = immutable_source_identity(native_root, ownertrust)?;
    Ok(format!(
        "org.archphene.package-trust.v1\n{keyring}\n{ownertrust}\n"
    ))
}

fn read_bounded_regular_file(
    path: &Path,
    limit: u64,
) -> Result<Option<Vec<u8>>, PackageRuntimeError> {
    let metadata = match fs::symlink_metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(PackageRuntimeError::Io(error)),
    };
    if metadata.file_type().is_symlink() || !metadata.is_file() {
        return Err(PackageRuntimeError::UnsafeEntry(path.to_path_buf()));
    }
    if metadata.len() == 0 || metadata.len() > limit {
        return Ok(None);
    }
    let mut content = Vec::with_capacity(metadata.len() as usize);
    File::open(path)?.read_to_end(&mut content)?;
    if content.len() as u64 != metadata.len() {
        return Ok(None);
    }
    Ok(Some(content))
}

fn bounded_regular_file(path: &Path, limit: u64) -> Result<bool, PackageRuntimeError> {
    let metadata = match fs::symlink_metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(false),
        Err(error) => return Err(PackageRuntimeError::Io(error)),
    };
    if metadata.file_type().is_symlink() || !metadata.is_file() {
        return Err(PackageRuntimeError::UnsafeEntry(path.to_path_buf()));
    }
    Ok(metadata.len() != 0 && metadata.len() <= limit)
}

fn validate_trust_cache_directory(path: &Path) -> Result<(), PackageRuntimeError> {
    let mut count = 0_usize;
    for entry in fs::read_dir(path)? {
        count = count.saturating_add(1);
        if count > 4 {
            return Err(PackageRuntimeError::InvalidSignature);
        }
        let entry = entry?;
        let entry_path = entry.path();
        let name = entry
            .file_name()
            .to_str()
            .ok_or(PackageRuntimeError::InvalidSignature)?
            .to_owned();
        let limit = match name.as_str() {
            PACKAGE_TRUST_STATE => PACKAGE_TRUST_STATE_LIMIT,
            "pubring.kbx" | "pubring.kbx~" => PACKAGE_KEYBOX_LIMIT,
            "trustdb.gpg" => PACKAGE_TRUSTDB_LIMIT,
            _ => return Err(PackageRuntimeError::UnsafeEntry(entry_path)),
        };
        let metadata = fs::symlink_metadata(&entry_path)?;
        if metadata.file_type().is_symlink()
            || !metadata.is_file()
            || metadata.len() == 0
            || metadata.len() > limit
        {
            return Err(PackageRuntimeError::UnsafeEntry(entry_path));
        }
    }
    Ok(())
}

fn prepare_output_path(path: &Path) -> Result<(), PackageRuntimeError> {
    match fs::symlink_metadata(path) {
        Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_file() => {
            Err(PackageRuntimeError::UnsafeEntry(path.to_path_buf()))
        }
        Ok(_) => {
            fs::remove_file(path)?;
            Ok(())
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(PackageRuntimeError::Io(error)),
    }
}

fn hash_regular_file(
    path: &Path,
    expected_size: u64,
    maximum_size: u64,
) -> Result<[u8; 32], PackageRuntimeError> {
    if expected_size == 0 || expected_size > maximum_size {
        return Err(PackageRuntimeError::InvalidPayload);
    }
    let metadata = fs::symlink_metadata(path)?;
    if metadata.file_type().is_symlink() || !metadata.is_file() || metadata.len() != expected_size {
        return Err(PackageRuntimeError::InvalidPayload);
    }
    let mut file = OpenOptions::new()
        .read(true)
        .custom_flags(O_NOFOLLOW | O_CLOEXEC)
        .open(path)?;
    let opened = file.metadata()?;
    if !opened.is_file() || opened.len() != expected_size {
        return Err(PackageRuntimeError::InvalidPayload);
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
            .ok_or(PackageRuntimeError::OutputLimit)?;
        if total > expected_size {
            return Err(PackageRuntimeError::InvalidPayload);
        }
        digest.update(&buffer[..count]);
    }
    if total != expected_size {
        return Err(PackageRuntimeError::InvalidPayload);
    }
    Ok(digest.finalize().into())
}

fn hex_sha256(value: &[u8; 32]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut output = String::with_capacity(64);
    for byte in value {
        output.push(char::from(HEX[usize::from(*byte >> 4)]));
        output.push(char::from(HEX[usize::from(*byte & 0x0f)]));
    }
    output
}

fn validate_aur_capability_identity(
    package_base: &str,
    package_name: &str,
    version: &str,
    architecture: &str,
    review_sha256: [u8; 32],
    closure_sha256: [u8; 32],
    required_packages: &[String],
) -> Result<(), PackageRuntimeError> {
    let unique_packages: BTreeSet<&str> = required_packages.iter().map(String::as_str).collect();
    if !safe_logical_name(package_base)
        || !safe_logical_name(package_name)
        || version.is_empty()
        || version.len() > 128
        || version
            .bytes()
            .any(|byte| byte.is_ascii_whitespace() || byte == 0)
        || (architecture != "x86_64" && architecture != "aarch64")
        || review_sha256 == [0; 32]
        || closure_sha256 == [0; 32]
        || required_packages.is_empty()
        || required_packages.len() > 256
        || !required_packages
            .iter()
            .all(|package| safe_logical_name(package))
        || unique_packages.len() != required_packages.len()
        || !required_packages
            .iter()
            .any(|package| package == package_name)
    {
        return Err(PackageRuntimeError::InvalidPayload);
    }
    Ok(())
}

fn stage_verified_aur_file(
    directory: &Path,
    source: &mut File,
    filename: &str,
    expected_bytes: u64,
    expected_sha256: [u8; 32],
) -> Result<PathBuf, PackageRuntimeError> {
    let metadata = source.metadata()?;
    if !metadata.is_file() || metadata.len() != expected_bytes {
        return Err(PackageRuntimeError::InvalidPayload);
    }
    let digest = hex_sha256(&expected_sha256);
    let destination = directory.join(format!("{digest}-{filename}"));
    let temporary = directory.join(format!(".{digest}.part"));
    prepare_output_path(&temporary)?;
    match fs::symlink_metadata(&destination) {
        Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_file() => {
            return Err(PackageRuntimeError::UnsafeEntry(destination));
        }
        Ok(_) => {}
        Err(error) if error.kind() == io::ErrorKind::NotFound => {}
        Err(error) => return Err(PackageRuntimeError::Io(error)),
    }
    let result = (|| {
        source.seek(SeekFrom::Start(0))?;
        let mut output = OpenOptions::new()
            .create_new(true)
            .write(true)
            .mode(0o600)
            .open(&temporary)?;
        let mut hasher = Sha256::new();
        let mut buffer = [0_u8; 64 * 1024];
        let mut copied = 0_u64;
        loop {
            let count = source.read(&mut buffer)?;
            if count == 0 {
                break;
            }
            copied = copied
                .checked_add(count as u64)
                .ok_or(PackageRuntimeError::OutputLimit)?;
            if copied > expected_bytes {
                return Err(PackageRuntimeError::InvalidPayload);
            }
            hasher.update(&buffer[..count]);
            output.write_all(&buffer[..count])?;
        }
        if copied != expected_bytes || <[u8; 32]>::from(hasher.finalize()) != expected_sha256 {
            return Err(PackageRuntimeError::InvalidPayload);
        }
        output.sync_all()?;
        drop(output);
        fs::rename(&temporary, &destination)?;
        File::open(directory)?.sync_all()?;
        Ok(destination.clone())
    })();
    if result.is_err() {
        let _ = fs::remove_file(temporary);
    }
    result
}

fn publish_aur_built_capability(directory: &Path, bytes: &[u8]) -> Result<(), PackageRuntimeError> {
    let destination = directory.join(AUR_BUILT_CAPABILITY_FILE);
    let temporary = directory.join(AUR_BUILT_CAPABILITY_TEMP_FILE);
    prepare_output_path(&temporary)?;
    match fs::symlink_metadata(&destination) {
        Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_file() => {
            return Err(PackageRuntimeError::UnsafeEntry(destination));
        }
        Ok(_) => {}
        Err(error) if error.kind() == io::ErrorKind::NotFound => {}
        Err(error) => return Err(PackageRuntimeError::Io(error)),
    }
    let result = (|| {
        let mut file = OpenOptions::new()
            .create_new(true)
            .write(true)
            .mode(0o600)
            .open(&temporary)?;
        file.write_all(bytes)?;
        file.sync_all()?;
        drop(file);
        fs::rename(&temporary, &destination)?;
        File::open(directory)?.sync_all()?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(temporary);
    }
    result
}

fn read_aur_built_capability(directory: &Path) -> Result<Option<Vec<u8>>, PackageRuntimeError> {
    let directory_metadata = match fs::symlink_metadata(directory) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(PackageRuntimeError::Io(error)),
    };
    if directory_metadata.file_type().is_symlink() || !directory_metadata.is_dir() {
        return Err(PackageRuntimeError::UnsafeEntry(directory.to_path_buf()));
    }
    let path = directory.join(AUR_BUILT_CAPABILITY_FILE);
    let metadata = match fs::symlink_metadata(&path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(PackageRuntimeError::Io(error)),
    };
    if metadata.file_type().is_symlink()
        || !metadata.is_file()
        || metadata.permissions().mode() & 0o777 != 0o600
        || metadata.len() == 0
        || metadata.len() > AUR_BUILT_CAPABILITY_LIMIT
    {
        return Err(PackageRuntimeError::UnsafeEntry(path));
    }
    let file = OpenOptions::new()
        .read(true)
        .custom_flags(O_NOFOLLOW | O_CLOEXEC)
        .open(&path)?;
    let opened = file.metadata()?;
    if !opened.is_file() || opened.len() != metadata.len() {
        return Err(PackageRuntimeError::InvalidManifest);
    }
    let mut bytes = Vec::with_capacity(
        usize::try_from(metadata.len()).map_err(|_| PackageRuntimeError::OutputLimit)?,
    );
    file.take(AUR_BUILT_CAPABILITY_LIMIT + 1)
        .read_to_end(&mut bytes)?;
    if u64::try_from(bytes.len()).map_err(|_| PackageRuntimeError::OutputLimit)? != metadata.len() {
        return Err(PackageRuntimeError::InvalidManifest);
    }
    Ok(Some(bytes))
}

fn verify_persisted_aur_file(
    path: &Path,
    expected_bytes: u64,
    expected_sha256: [u8; 32],
) -> Result<(), PackageRuntimeError> {
    let metadata = fs::symlink_metadata(path)?;
    if metadata.file_type().is_symlink()
        || !metadata.is_file()
        || metadata.permissions().mode() & 0o777 != 0o600
        || metadata.len() != expected_bytes
    {
        return Err(PackageRuntimeError::UnsafeEntry(path.to_path_buf()));
    }
    let mut file = OpenOptions::new()
        .read(true)
        .custom_flags(O_NOFOLLOW | O_CLOEXEC)
        .open(path)?;
    let opened = file.metadata()?;
    if !opened.is_file() || opened.len() != expected_bytes {
        return Err(PackageRuntimeError::InvalidPayload);
    }
    let mut hasher = Sha256::new();
    let mut buffer = [0_u8; 64 * 1024];
    let mut read_bytes = 0_u64;
    loop {
        let count = file.read(&mut buffer)?;
        if count == 0 {
            break;
        }
        read_bytes = read_bytes
            .checked_add(count as u64)
            .ok_or(PackageRuntimeError::OutputLimit)?;
        if read_bytes > expected_bytes {
            return Err(PackageRuntimeError::InvalidPayload);
        }
        hasher.update(&buffer[..count]);
    }
    if read_bytes != expected_bytes || <[u8; 32]>::from(hasher.finalize()) != expected_sha256 {
        return Err(PackageRuntimeError::InvalidPayload);
    }
    Ok(())
}

fn aur_lifecycle_state_root(arch_root: &Path) -> Result<PathBuf, PackageRuntimeError> {
    let parent = arch_root.parent().ok_or(PackageRuntimeError::InvalidPath)?;
    let metadata = fs::symlink_metadata(parent)?;
    if metadata.file_type().is_symlink() || !metadata.is_dir() {
        return Err(PackageRuntimeError::UnsafeEntry(parent.to_path_buf()));
    }
    let root_name = arch_root
        .file_name()
        .and_then(|name| name.to_str())
        .filter(|name| {
            !name.is_empty()
                && name.len() <= 128
                && name
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
        })
        .ok_or(PackageRuntimeError::InvalidPath)?;
    let state_root = parent.join(format!(".{root_name}-manager-state-v1"));
    prepare_private_directory(&state_root)?;
    Ok(state_root)
}

fn read_aur_lifecycle_capabilities(
    state_root: &Path,
) -> Result<Vec<AurLifecycleCapability>, PackageRuntimeError> {
    let path = state_root.join(AUR_LIFECYCLE_CAPABILITY_FILE);
    let metadata = match fs::symlink_metadata(&path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(error) => return Err(PackageRuntimeError::Io(error)),
    };
    if metadata.file_type().is_symlink()
        || !metadata.is_file()
        || metadata.permissions().mode() & 0o777 != 0o600
        || metadata.len() == 0
        || metadata.len() > AUR_LIFECYCLE_CAPABILITY_LIMIT
    {
        return Err(PackageRuntimeError::UnsafeEntry(path));
    }
    let file = OpenOptions::new()
        .read(true)
        .custom_flags(O_NOFOLLOW | O_CLOEXEC)
        .open(&path)?;
    let opened = file.metadata()?;
    if !opened.is_file() || opened.len() != metadata.len() {
        return Err(PackageRuntimeError::InvalidManifest);
    }
    let mut bytes = Vec::with_capacity(
        usize::try_from(metadata.len()).map_err(|_| PackageRuntimeError::OutputLimit)?,
    );
    file.take(AUR_LIFECYCLE_CAPABILITY_LIMIT + 1)
        .read_to_end(&mut bytes)?;
    if u64::try_from(bytes.len()).map_err(|_| PackageRuntimeError::OutputLimit)? != metadata.len()
        || !bytes.ends_with(b"\n")
    {
        return Err(PackageRuntimeError::InvalidManifest);
    }
    let text = std::str::from_utf8(&bytes).map_err(|_| PackageRuntimeError::InvalidManifest)?;
    let mut lines = text.lines();
    if lines.next() != Some(AUR_LIFECYCLE_CAPABILITY_HEADER) {
        return Err(PackageRuntimeError::InvalidManifest);
    }
    let mut capabilities = Vec::new();
    for line in lines {
        if line.is_empty() || capabilities.len() >= AUR_LIFECYCLE_CAPABILITY_ENTRIES {
            return Err(PackageRuntimeError::InvalidManifest);
        }
        let mut fields = line.split('\t');
        let package = fields.next().ok_or(PackageRuntimeError::InvalidManifest)?;
        let version = fields.next().ok_or(PackageRuntimeError::InvalidManifest)?;
        let archive_sha256 = fields.next().ok_or(PackageRuntimeError::InvalidManifest)?;
        let install_script_sha256 = fields.next().ok_or(PackageRuntimeError::InvalidManifest)?;
        if fields.next().is_some()
            || !safe_logical_name(package)
            || !safe_package_version(version)
            || capabilities
                .iter()
                .any(|existing: &AurLifecycleCapability| {
                    existing.package == package && existing.version == version
                })
        {
            return Err(PackageRuntimeError::InvalidManifest);
        }
        capabilities.push(AurLifecycleCapability {
            package: package.to_owned(),
            version: version.to_owned(),
            archive_sha256: parse_lower_sha256(archive_sha256)?,
            install_script_sha256: if install_script_sha256 == "-" {
                None
            } else {
                Some(parse_lower_sha256(install_script_sha256)?)
            },
        });
    }
    Ok(capabilities)
}

fn publish_aur_lifecycle_capability_file(
    state_root: &Path,
    capabilities: &[AurLifecycleCapability],
) -> Result<(), PackageRuntimeError> {
    if capabilities.len() > AUR_LIFECYCLE_CAPABILITY_ENTRIES {
        return Err(PackageRuntimeError::OutputLimit);
    }
    let destination = state_root.join(AUR_LIFECYCLE_CAPABILITY_FILE);
    let temporary = state_root.join(AUR_LIFECYCLE_CAPABILITY_TEMP_FILE);
    prepare_output_path(&temporary)?;
    match fs::symlink_metadata(&destination) {
        Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_file() => {
            return Err(PackageRuntimeError::UnsafeEntry(destination));
        }
        Ok(_) => {}
        Err(error) if error.kind() == io::ErrorKind::NotFound => {}
        Err(error) => return Err(PackageRuntimeError::Io(error)),
    }
    if capabilities.is_empty() {
        match fs::remove_file(&destination) {
            Ok(()) => File::open(state_root)?.sync_all()?,
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => return Err(PackageRuntimeError::Io(error)),
        }
        return Ok(());
    }
    let mut bytes = String::with_capacity(128 + capabilities.len() * 240);
    bytes.push_str(AUR_LIFECYCLE_CAPABILITY_HEADER);
    bytes.push('\n');
    for capability in capabilities {
        bytes.push_str(&capability.package);
        bytes.push('\t');
        bytes.push_str(&capability.version);
        bytes.push('\t');
        bytes.push_str(&hex_sha256(&capability.archive_sha256));
        bytes.push('\t');
        match capability.install_script_sha256 {
            Some(sha256) => bytes.push_str(&hex_sha256(&sha256)),
            None => bytes.push('-'),
        }
        bytes.push('\n');
        if bytes.len() as u64 > AUR_LIFECYCLE_CAPABILITY_LIMIT {
            return Err(PackageRuntimeError::OutputLimit);
        }
    }
    let result = (|| {
        let mut output = OpenOptions::new()
            .create_new(true)
            .write(true)
            .mode(0o600)
            .open(&temporary)?;
        output.write_all(bytes.as_bytes())?;
        output.sync_all()?;
        drop(output);
        fs::rename(&temporary, &destination)?;
        File::open(state_root)?.sync_all()?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(temporary);
    }
    result
}

fn parse_lower_sha256(value: &str) -> Result<[u8; 32], PackageRuntimeError> {
    if !is_lower_hex_sha256(value) {
        return Err(PackageRuntimeError::InvalidManifest);
    }
    let bytes = value.as_bytes();
    let mut output = [0_u8; 32];
    for (index, pair) in bytes.chunks_exact(2).enumerate() {
        let high = match pair[0] {
            b'0'..=b'9' => pair[0] - b'0',
            b'a'..=b'f' => pair[0] - b'a' + 10,
            _ => return Err(PackageRuntimeError::InvalidManifest),
        };
        let low = match pair[1] {
            b'0'..=b'9' => pair[1] - b'0',
            b'a'..=b'f' => pair[1] - b'a' + 10,
            _ => return Err(PackageRuntimeError::InvalidManifest),
        };
        output[index] = high << 4 | low;
    }
    if output == [0; 32] {
        return Err(PackageRuntimeError::InvalidManifest);
    }
    Ok(output)
}

fn find_local_database_entry(
    arch_root: &Path,
    package: &str,
    version: &str,
) -> Result<PathBuf, PackageRuntimeError> {
    let local = arch_root.join("var/lib/pacman/local");
    let metadata = fs::symlink_metadata(&local)?;
    if metadata.file_type().is_symlink() || !metadata.is_dir() {
        return Err(PackageRuntimeError::UnsafeEntry(local));
    }
    let mut matched = None;
    let mut count = 0_usize;
    for entry in fs::read_dir(&local)? {
        count = count.saturating_add(1);
        if count > LOCAL_DATABASE_ENTRY_LIMIT {
            return Err(PackageRuntimeError::OutputLimit);
        }
        let entry = entry?;
        let path = entry.path();
        let metadata = fs::symlink_metadata(&path)?;
        if entry.file_name() == "ALPM_DB_VERSION"
            && metadata.is_file()
            && !metadata.file_type().is_symlink()
        {
            continue;
        }
        if metadata.file_type().is_symlink() || !metadata.is_dir() {
            return Err(PackageRuntimeError::UnsafeEntry(path));
        }
        if !local_database_entry_matches(&path, package, version)? {
            continue;
        }
        if matched.replace(path).is_some() {
            return Err(PackageRuntimeError::InvalidResolution);
        }
    }
    matched.ok_or(PackageRuntimeError::NotInstalled)
}

fn read_output_bytes(path: &Path, maximum: usize) -> Result<Vec<u8>, PackageRuntimeError> {
    let metadata = validate_output_size(path, maximum)?;
    let length = usize::try_from(metadata.len()).map_err(|_| PackageRuntimeError::OutputLimit)?;
    let mut output = vec![0_u8; length];
    File::open(path)?.read_exact(&mut output)?;
    Ok(output)
}

fn validate_output_size(path: &Path, maximum: usize) -> Result<fs::Metadata, PackageRuntimeError> {
    let metadata = fs::metadata(path)?;
    if metadata.len() > maximum as u64 {
        return Err(PackageRuntimeError::OutputLimit);
    }
    Ok(metadata)
}

fn catalog_file_ready(path: &Path, maximum: u64) -> bool {
    match fs::symlink_metadata(path) {
        Ok(metadata) => {
            !metadata.file_type().is_symlink()
                && metadata.is_file()
                && metadata.len() > 0
                && metadata.len() <= maximum
        }
        Err(_) => false,
    }
}

fn valid_search_query(query: &str) -> bool {
    (2..=128).contains(&query.len())
        && query.bytes().all(|byte| {
            byte.is_ascii_alphanumeric() || matches!(byte, b'@' | b'.' | b'_' | b'+' | b':' | b'-')
        })
}

fn exact_search_pattern(query: &str) -> String {
    let mut pattern = String::with_capacity(query.len().saturating_mul(2).saturating_add(2));
    pattern.push('^');
    for byte in query.bytes() {
        if matches!(
            byte,
            b'.' | b'^'
                | b'$'
                | b'*'
                | b'+'
                | b'?'
                | b'('
                | b')'
                | b'['
                | b']'
                | b'{'
                | b'}'
                | b'|'
                | b'\\'
        ) {
            pattern.push('\\');
        }
        pattern.push(char::from(byte));
    }
    pattern.push('$');
    pattern
}

fn parse_search_output(
    input: &str,
    preferred_name: &str,
) -> Result<ToolOutput, PackageRuntimeError> {
    let mut output = ToolOutput {
        bytes: [0; MAX_TOOL_OUTPUT_BYTES],
        length: 0,
    };
    let mut count = 0_usize;
    append_search_output_pass(input, preferred_name, true, &mut output, &mut count)?;
    if count < 100 {
        append_search_output_pass(input, preferred_name, false, &mut output, &mut count)?;
    }
    Ok(output)
}

fn differing_search_packages(output: &ToolOutput) -> Result<Vec<String>, PackageRuntimeError> {
    let mut packages = Vec::new();
    for line in output.as_str()?.lines() {
        let mut fields = line.split('\t');
        let repository = fields.next();
        let name = fields.next();
        let version = fields.next();
        let description = fields.next();
        let state = fields.next();
        let installed_version = fields.next();
        if fields.next().is_some()
            || !matches!(repository, Some("core" | "extra"))
            || !name.is_some_and(safe_logical_name)
            || version.is_none()
            || description.is_none()
            || !matches!(state, Some("available" | "installed" | "different"))
            || installed_version.is_none()
        {
            return Err(PackageRuntimeError::InvalidResolution);
        }
        if state == Some("different") {
            packages.push(name.expect("validated package name").to_owned());
        }
    }
    Ok(packages)
}

fn parse_quiet_update_names(
    input: &str,
    candidates: &[String],
) -> Result<BTreeSet<String>, PackageRuntimeError> {
    let mut updates = BTreeSet::new();
    for name in input.lines() {
        if !safe_logical_name(name)
            || !candidates.iter().any(|candidate| candidate == name)
            || !updates.insert(name.to_owned())
        {
            return Err(PackageRuntimeError::InvalidResolution);
        }
    }
    Ok(updates)
}

fn parse_exact_quiet_update(
    input: &str,
    expected_package: &str,
) -> Result<bool, PackageRuntimeError> {
    if input.is_empty() {
        return Ok(false);
    }
    let mut lines = input.lines();
    let update = lines.next().ok_or(PackageRuntimeError::InvalidResolution)?;
    if update != expected_package || lines.next().is_some() {
        return Err(PackageRuntimeError::InvalidResolution);
    }
    Ok(true)
}

fn annotate_search_update_names(
    output: ToolOutput,
    updates: &BTreeSet<String>,
) -> Result<ToolOutput, PackageRuntimeError> {
    let mut annotated = empty_tool_output();
    for line in output.as_str()?.lines() {
        let mut fields = line.split('\t');
        let (
            Some(repository),
            Some(name),
            Some(version),
            Some(description),
            Some(state),
            Some(installed_version),
        ) = (
            fields.next(),
            fields.next(),
            fields.next(),
            fields.next(),
            fields.next(),
            fields.next(),
        )
        else {
            return Err(PackageRuntimeError::InvalidResolution);
        };
        if fields.next().is_some() {
            return Err(PackageRuntimeError::InvalidResolution);
        }
        append_search_result(
            &mut annotated,
            repository,
            name,
            version,
            description,
            if state == "different" && updates.contains(name) {
                "update"
            } else {
                state
            },
            installed_version,
        )?;
    }
    Ok(annotated)
}

fn append_search_output_pass(
    input: &str,
    preferred_name: &str,
    exact_match: bool,
    output: &mut ToolOutput,
    count: &mut usize,
) -> Result<(), PackageRuntimeError> {
    let mut pending: Option<(&str, &str, &str, &'static str, &str)> = None;
    for line in input.lines() {
        if line.starts_with(char::is_whitespace) {
            if let Some((repository, name, version, state, installed_version)) = pending.take() {
                if (name == preferred_name) == exact_match {
                    append_search_result(
                        output,
                        repository,
                        name,
                        version,
                        line.trim(),
                        state,
                        installed_version,
                    )?;
                    *count += 1;
                }
                if *count >= 100 {
                    break;
                }
            }
            continue;
        }
        if let Some((repository, name, version, state, installed_version)) = pending.take() {
            if (name == preferred_name) == exact_match {
                append_search_result(
                    output,
                    repository,
                    name,
                    version,
                    "",
                    state,
                    installed_version,
                )?;
                *count += 1;
            }
            if *count >= 100 {
                break;
            }
        }
        let mut fields = line.split_ascii_whitespace();
        let Some(identity) = fields.next() else {
            continue;
        };
        let Some(version) = fields.next() else {
            continue;
        };
        let Some((repository, name)) = identity.split_once('/') else {
            continue;
        };
        if matches!(repository, "core" | "extra")
            && safe_logical_name(name)
            && !version.is_empty()
            && version.len() <= 128
            && version.bytes().all(|byte| !byte.is_ascii_whitespace())
        {
            let (state, installed_version) = search_install_state(line, version)?;
            pending = Some((repository, name, version, state, installed_version));
        }
    }
    if *count < 100 {
        if let Some((repository, name, version, state, installed_version)) = pending {
            if (name == preferred_name) == exact_match {
                append_search_result(
                    output,
                    repository,
                    name,
                    version,
                    "",
                    state,
                    installed_version,
                )?;
                *count += 1;
            }
        }
    }
    Ok(())
}

fn search_install_state<'a>(
    line: &'a str,
    available_version: &'a str,
) -> Result<(&'static str, &'a str), PackageRuntimeError> {
    if line.ends_with(" [installed]") {
        return Ok(("installed", available_version));
    }
    let Some((_, suffix)) = line.rsplit_once(" [installed: ") else {
        return Ok(("available", ""));
    };
    let installed_version = suffix
        .strip_suffix(']')
        .ok_or(PackageRuntimeError::InvalidResolution)?;
    if installed_version.is_empty()
        || installed_version.len() > 128
        || installed_version
            .bytes()
            .any(|byte| byte.is_ascii_whitespace() || matches!(byte, b'\t' | b'\r' | b'\n' | 0))
    {
        return Err(PackageRuntimeError::InvalidResolution);
    }
    Ok(("different", installed_version))
}

fn safe_package_version(version: &str) -> bool {
    !version.is_empty()
        && version.len() <= 128
        && version
            .bytes()
            .all(|byte| !byte.is_ascii_whitespace() && !byte.is_ascii_control())
}

fn empty_tool_output() -> ToolOutput {
    ToolOutput {
        bytes: [0; MAX_TOOL_OUTPUT_BYTES],
        length: 0,
    }
}

fn exact_missing_dependency(output: &str, requirement: &str) -> bool {
    let mut lines = output.lines();
    lines.next() == Some(requirement) && lines.next().is_none() && output.ends_with('\n')
}

fn missing_package_query(output: &str, package: &str) -> bool {
    let mut lines = output.lines();
    let Some(line) = lines.next() else {
        return false;
    };
    lines.next().is_none() && line == format!("error: package '{package}' was not found")
}

fn parse_installed_version(
    input: &str,
    expected_package: &str,
) -> Result<ToolOutput, PackageRuntimeError> {
    let mut lines = input.lines();
    let line = lines.next().ok_or(PackageRuntimeError::InvalidResolution)?;
    if lines.next().is_some() {
        return Err(PackageRuntimeError::InvalidResolution);
    }
    let (package, version) = line
        .split_once(' ')
        .ok_or(PackageRuntimeError::InvalidResolution)?;
    if package != expected_package
        || version.is_empty()
        || version.len() > 128
        || version.bytes().any(|byte| byte.is_ascii_whitespace())
    {
        return Err(PackageRuntimeError::InvalidResolution);
    }
    let mut output = empty_tool_output();
    output.push(version.as_bytes())?;
    Ok(output)
}

fn read_local_package_name(path: &Path) -> Option<String> {
    let description = path.join("desc");
    let metadata = fs::symlink_metadata(&description).ok()?;
    if metadata.file_type().is_symlink()
        || !metadata.is_file()
        || metadata.len() == 0
        || metadata.len() > LOCAL_DESCRIPTION_LIMIT
    {
        return None;
    }
    let file = OpenOptions::new()
        .read(true)
        .custom_flags(O_NOFOLLOW | O_CLOEXEC)
        .open(description)
        .ok()?;
    let opened = file.metadata().ok()?;
    if !opened.is_file() || opened.len() != metadata.len() {
        return None;
    }
    let mut contents = String::with_capacity(usize::try_from(metadata.len()).ok()?.min(4 * 1024));
    file.take(LOCAL_DESCRIPTION_LIMIT + 1)
        .read_to_string(&mut contents)
        .ok()?;
    if u64::try_from(contents.len()).ok()? != metadata.len() {
        return None;
    }
    local_description_field(&contents, "%NAME%")
        .ok()
        .flatten()
        .filter(|name| safe_logical_name(name))
        .map(str::to_owned)
}

fn installed_package_capabilities(
    package_entry: &Path,
    total_bytes: &mut u64,
) -> Result<Option<u8>, PackageRuntimeError> {
    let files_path = package_entry.join("files");
    let metadata = match fs::symlink_metadata(&files_path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(PackageRuntimeError::Io(error)),
    };
    if metadata.file_type().is_symlink()
        || !metadata.is_file()
        || metadata.len() > LOCAL_FILES_LIMIT
    {
        return Err(PackageRuntimeError::UnsafeEntry(files_path));
    }
    if metadata.len() == 0 {
        return Ok(None);
    }
    *total_bytes = total_bytes
        .checked_add(metadata.len())
        .filter(|bytes| *bytes <= LOCAL_FILES_TOTAL_LIMIT)
        .ok_or(PackageRuntimeError::OutputLimit)?;
    let mut file = OpenOptions::new()
        .read(true)
        .custom_flags(O_NOFOLLOW | O_CLOEXEC)
        .open(&files_path)?;
    let opened = file.metadata()?;
    if !opened.is_file() || opened.len() != metadata.len() {
        return Err(PackageRuntimeError::SizeMismatch);
    }

    let mut read_bytes = 0_u64;
    let mut chunk = [0_u8; 8 * 1024];
    let mut line = [0_u8; LOCAL_FILE_PATH_LIMIT];
    let mut line_length = 0_usize;
    let mut in_files = false;
    let mut found_files_header = false;
    let mut capabilities = 0_u8;
    loop {
        let read = file.read(&mut chunk)?;
        if read == 0 {
            break;
        }
        read_bytes = read_bytes
            .checked_add(u64::try_from(read).expect("read size"))
            .filter(|bytes| *bytes <= metadata.len())
            .ok_or(PackageRuntimeError::SizeMismatch)?;
        for byte in &chunk[..read] {
            if *byte == b'\n' {
                process_package_capability_line(
                    &line[..line_length],
                    &mut in_files,
                    &mut found_files_header,
                    &mut capabilities,
                )?;
                line_length = 0;
            } else {
                if line_length >= line.len() {
                    return Err(PackageRuntimeError::OutputLimit);
                }
                line[line_length] = *byte;
                line_length += 1;
            }
        }
    }
    if line_length != 0 {
        process_package_capability_line(
            &line[..line_length],
            &mut in_files,
            &mut found_files_header,
            &mut capabilities,
        )?;
    }
    if read_bytes != metadata.len() {
        return Err(PackageRuntimeError::SizeMismatch);
    }
    if !found_files_header {
        return Err(PackageRuntimeError::InvalidResolution);
    }
    Ok(Some(capabilities))
}

fn process_package_capability_line(
    raw_line: &[u8],
    in_files: &mut bool,
    found_files_header: &mut bool,
    capabilities: &mut u8,
) -> Result<(), PackageRuntimeError> {
    let line = raw_line.strip_suffix(b"\r").unwrap_or(raw_line);
    if line == b"%FILES%" {
        if *found_files_header {
            return Err(PackageRuntimeError::InvalidResolution);
        }
        *found_files_header = true;
        *in_files = true;
        return Ok(());
    }
    if line.len() >= 2 && line.first() == Some(&b'%') && line.last() == Some(&b'%') {
        *in_files = false;
        return Ok(());
    }
    if !*in_files || line.is_empty() {
        return Ok(());
    }
    let directory = line.ends_with(b"/");
    let path = line.strip_suffix(b"/").unwrap_or(line);
    if path.is_empty()
        || path.starts_with(b"/")
        || path.contains(&0)
        || path
            .split(|byte| *byte == b'/')
            .any(|part| part.is_empty() || part == b"." || part == b"..")
    {
        return Err(PackageRuntimeError::InvalidResolution);
    }
    if directory {
        return Ok(());
    }

    if path.starts_with(b"usr/share/applications/") && path.ends_with(b".desktop") {
        *capabilities |= PACKAGE_CAPABILITY_GRAPHICAL;
    }
    if direct_command_path(path) {
        *capabilities |= PACKAGE_CAPABILITY_COMMAND_LINE;
    }
    if path.starts_with(b"usr/include/")
        || (path.starts_with(b"usr/lib/") && is_library_metadata_path(path))
        || (path.starts_with(b"usr/share/pkgconfig/") && path.ends_with(b".pc"))
    {
        *capabilities |= PACKAGE_CAPABILITY_LIBRARY;
    }
    if path.starts_with(b"usr/lib/systemd/")
        || path.starts_with(b"usr/lib/udev/")
        || path.starts_with(b"usr/lib/sysusers.d/")
        || path.starts_with(b"usr/lib/tmpfiles.d/")
    {
        *capabilities |= PACKAGE_CAPABILITY_SYSTEM;
    }
    Ok(())
}

fn is_library_metadata_path(path: &[u8]) -> bool {
    let name = path.rsplit(|byte| *byte == b'/').next().unwrap_or(path);
    name.ends_with(b".a")
        || name.ends_with(b".so")
        || name.windows(4).any(|window| window == b".so.")
        || (path.starts_with(b"usr/lib/pkgconfig/") && name.ends_with(b".pc"))
}

fn is_static_library_path(path: &[u8]) -> bool {
    path.starts_with(b"usr/lib/") && path.ends_with(b".a")
}

fn hex_nibble(value: u8) -> u8 {
    debug_assert!(value <= 0x0f);
    match value {
        0..=9 => b'0' + value,
        _ => b'a' + (value - 10),
    }
}

fn package_compatibility_output(
    status: PackageCompatibilityStatus,
    capabilities: u8,
    package_count: usize,
    elf_count: u32,
    command_count: u32,
    diagnostic: PackageCompatibilityDiagnostic,
    diagnostic_package: Option<&str>,
) -> Result<ToolOutput, PackageRuntimeError> {
    let status = match status {
        PackageCompatibilityStatus::NotAnalyzed => "not-analyzed",
        PackageCompatibilityStatus::BridgeEligible => "bridge-eligible",
        PackageCompatibilityStatus::ManagedOnly => "managed-only",
        PackageCompatibilityStatus::Unsupported => "unsupported",
    };
    let diagnostic = match diagnostic {
        PackageCompatibilityDiagnostic::None => "none",
        PackageCompatibilityDiagnostic::NotCached => "not-cached",
        PackageCompatibilityDiagnostic::ForeignElf => "foreign-elf",
        PackageCompatibilityDiagnostic::NativeInAnyPackage => "native-in-any-package",
        PackageCompatibilityDiagnostic::MalformedElf => "malformed-elf",
        PackageCompatibilityDiagnostic::IncompatiblePageSize => "incompatible-page-size",
        PackageCompatibilityDiagnostic::UnsupportedCommand => "unsupported-command",
    };
    let mut output = empty_tool_output();
    let diagnostic_package = diagnostic_package.unwrap_or("-");
    if diagnostic_package != "-" && !safe_logical_name(diagnostic_package) {
        return Err(PackageRuntimeError::InvalidResolution);
    }
    writeln!(
        output,
        "{status}\t{}\t{package_count}\t{elf_count}\t{command_count}\t{diagnostic}\t{diagnostic_package}",
        char::from(hex_nibble(capabilities)),
    )
    .map_err(|_| PackageRuntimeError::OutputLimit)?;
    Ok(output)
}

fn canonical_cached_compatibility(
    bytes: &[u8],
) -> Result<PackageCompatibilityStatus, PackageRuntimeError> {
    let text = std::str::from_utf8(bytes).map_err(|_| PackageRuntimeError::InvalidPayload)?;
    if !text.ends_with('\n') || text.bytes().filter(|byte| *byte == b'\n').count() != 1 {
        return Err(PackageRuntimeError::InvalidPayload);
    }
    let mut fields = text[..text.len() - 1].split('\t');
    let status = match fields.next() {
        Some("bridge-eligible") => PackageCompatibilityStatus::BridgeEligible,
        Some("managed-only") => PackageCompatibilityStatus::ManagedOnly,
        Some("unsupported") => PackageCompatibilityStatus::Unsupported,
        _ => return Err(PackageRuntimeError::InvalidPayload),
    };
    let capabilities = fields
        .next()
        .filter(|value| value.len() == 1)
        .and_then(|value| char::from(value.as_bytes()[0]).to_digit(16))
        .and_then(|value| u8::try_from(value).ok())
        .filter(|value| *value <= 0x0f)
        .ok_or(PackageRuntimeError::InvalidPayload)?;
    let package_count = canonical_compatibility_number(fields.next(), 1, 512)? as usize;
    let elf_count = canonical_compatibility_number(fields.next(), 0, 1_000_000)?;
    let command_count = canonical_compatibility_number(fields.next(), 0, 262_144)?;
    let diagnostic = match fields.next() {
        Some("none") => PackageCompatibilityDiagnostic::None,
        Some("foreign-elf") => PackageCompatibilityDiagnostic::ForeignElf,
        Some("native-in-any-package") => PackageCompatibilityDiagnostic::NativeInAnyPackage,
        Some("malformed-elf") => PackageCompatibilityDiagnostic::MalformedElf,
        Some("incompatible-page-size") => PackageCompatibilityDiagnostic::IncompatiblePageSize,
        Some("unsupported-command") => PackageCompatibilityDiagnostic::UnsupportedCommand,
        _ => return Err(PackageRuntimeError::InvalidPayload),
    };
    let diagnostic_package = match fields.next() {
        Some("-") => None,
        Some(package) if safe_logical_name(package) => Some(package),
        _ => return Err(PackageRuntimeError::InvalidPayload),
    };
    if fields.next().is_some()
        || matches!(status, PackageCompatibilityStatus::Unsupported)
            != !matches!(diagnostic, PackageCompatibilityDiagnostic::None)
        || matches!(status, PackageCompatibilityStatus::Unsupported) != diagnostic_package.is_some()
    {
        return Err(PackageRuntimeError::InvalidPayload);
    }
    let canonical = package_compatibility_output(
        status,
        capabilities,
        package_count,
        elf_count,
        command_count,
        diagnostic,
        diagnostic_package,
    )?;
    if canonical.as_bytes() != bytes {
        return Err(PackageRuntimeError::InvalidPayload);
    }
    Ok(status)
}

fn canonical_compatibility_number(
    value: Option<&str>,
    minimum: u32,
    maximum: u32,
) -> Result<u32, PackageRuntimeError> {
    let value = value.ok_or(PackageRuntimeError::InvalidPayload)?;
    let parsed = value
        .parse::<u32>()
        .map_err(|_| PackageRuntimeError::InvalidPayload)?;
    if !(minimum..=maximum).contains(&parsed) || parsed.to_string() != value {
        return Err(PackageRuntimeError::InvalidPayload);
    }
    Ok(parsed)
}

fn cached_compatibility_allows_mutation(output: &ToolOutput) -> Result<bool, PackageRuntimeError> {
    Ok(matches!(
        canonical_cached_compatibility(output.as_bytes())?,
        PackageCompatibilityStatus::BridgeEligible | PackageCompatibilityStatus::ManagedOnly
    ))
}

fn encode_package_compatibility_cache_record(
    content_digest: &[u8; 32],
    output: &ToolOutput,
) -> Result<Vec<u8>, PackageRuntimeError> {
    canonical_cached_compatibility(output.as_bytes())?;
    let mut checksum = Sha256::new();
    checksum.update(PACKAGE_COMPATIBILITY_CACHE_DOMAIN);
    checksum.update(content_digest);
    checksum.update(output.as_bytes());
    let checksum: [u8; 32] = checksum.finalize().into();
    let mut record = Vec::with_capacity(output.as_bytes().len() + 65);
    record.extend_from_slice(output.as_bytes());
    record.extend_from_slice(hex_sha256(&checksum).as_bytes());
    record.push(b'\n');
    if record.len() as u64 > PACKAGE_COMPATIBILITY_CACHE_RECORD_LIMIT {
        return Err(PackageRuntimeError::OutputLimit);
    }
    Ok(record)
}

fn decode_package_compatibility_cache_record(
    content_digest: &[u8; 32],
    record: &[u8],
) -> Result<ToolOutput, PackageRuntimeError> {
    if !record.ends_with(b"\n") || record.iter().filter(|byte| **byte == b'\n').count() != 2 {
        return Err(PackageRuntimeError::InvalidPayload);
    }
    let output_end = record
        .iter()
        .position(|byte| *byte == b'\n')
        .ok_or(PackageRuntimeError::InvalidPayload)?
        + 1;
    let checksum = &record[output_end..record.len() - 1];
    if checksum.len() != 64
        || checksum
            .iter()
            .any(|byte| !matches!(byte, b'0'..=b'9' | b'a'..=b'f'))
    {
        return Err(PackageRuntimeError::InvalidPayload);
    }
    let mut expected = Sha256::new();
    expected.update(PACKAGE_COMPATIBILITY_CACHE_DOMAIN);
    expected.update(content_digest);
    expected.update(&record[..output_end]);
    let expected: [u8; 32] = expected.finalize().into();
    if checksum != hex_sha256(&expected).as_bytes() {
        return Err(PackageRuntimeError::InvalidPayload);
    }
    canonical_cached_compatibility(&record[..output_end])?;
    let mut output = empty_tool_output();
    output.push(&record[..output_end])?;
    Ok(output)
}

fn hash_package_compatibility_file(
    path: &Path,
    exact_size: u64,
    maximum_size: u64,
    role: u8,
    digest: &mut Sha256,
    cancellation: &PackageCompatibilityCancellation,
) -> Result<(), PackageRuntimeError> {
    cancellation.check()?;
    let metadata = fs::symlink_metadata(path)?;
    if metadata.file_type().is_symlink()
        || !metadata.is_file()
        || metadata.len() == 0
        || metadata.len() > maximum_size
        || exact_size != 0 && metadata.len() != exact_size
    {
        return Err(PackageRuntimeError::InvalidPayload);
    }
    let mut file = OpenOptions::new()
        .read(true)
        .custom_flags(O_NOFOLLOW | O_CLOEXEC)
        .open(path)?;
    let opened = file.metadata()?;
    if !opened.is_file() || opened.len() != metadata.len() {
        return Err(PackageRuntimeError::SizeMismatch);
    }
    digest.update([role]);
    digest.update(metadata.len().to_le_bytes());
    let mut buffer = [0_u8; 64 * 1024];
    let mut total = 0_u64;
    loop {
        cancellation.check()?;
        let count = file.read(&mut buffer)?;
        if count == 0 {
            break;
        }
        total = total
            .checked_add(count as u64)
            .filter(|total| *total <= metadata.len())
            .ok_or(PackageRuntimeError::SizeMismatch)?;
        digest.update(&buffer[..count]);
    }
    cancellation.check()?;
    if total != metadata.len() {
        return Err(PackageRuntimeError::SizeMismatch);
    }
    Ok(())
}

fn prune_package_compatibility_cache(
    directory: &Path,
    retained_digest: &[u8; 32],
) -> Result<(), PackageRuntimeError> {
    let retained = hex_sha256(retained_digest);
    let mut records = Vec::new();
    for entry in fs::read_dir(directory)? {
        let entry = entry?;
        let path = entry.path();
        let metadata = fs::symlink_metadata(&path)?;
        if metadata.file_type().is_symlink() || !metadata.is_file() {
            return Err(PackageRuntimeError::UnsafeEntry(path));
        }
        let name = entry
            .file_name()
            .into_string()
            .map_err(|_| PackageRuntimeError::InvalidPayload)?;
        if name.starts_with('.') && name.ends_with(".tmp") {
            fs::remove_file(path)?;
            continue;
        }
        if !is_lower_hex_sha256(&name) {
            return Err(PackageRuntimeError::UnsafeEntry(path));
        }
        records.push(name);
        if records.len() > PACKAGE_COMPATIBILITY_CACHE_ENTRY_LIMIT {
            return Err(PackageRuntimeError::OutputLimit);
        }
    }
    if records.len() >= PACKAGE_COMPATIBILITY_CACHE_ENTRY_LIMIT
        && !records.iter().any(|name| name == &retained)
    {
        records.sort_unstable();
        let victim = records
            .into_iter()
            .find(|name| name != &retained)
            .ok_or(PackageRuntimeError::OutputLimit)?;
        fs::remove_file(directory.join(victim))?;
    }
    Ok(())
}

fn is_lower_hex_sha256(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| matches!(byte, b'0'..=b'9' | b'a'..=b'f'))
}

#[cfg(test)]
fn inspect_package_archive(
    archive: &mut File,
    filename: &str,
    architecture: RepositoryArchitecture,
    page_size: usize,
    classify_target: bool,
) -> Result<PackageArchiveAnalysis, PackageRuntimeError> {
    inspect_package_archive_cancellable(
        archive,
        filename,
        architecture,
        page_size,
        classify_target,
        &PackageCompatibilityCancellation::new(),
    )
}

fn inspect_package_archive_cancellable(
    archive: &mut File,
    filename: &str,
    architecture: RepositoryArchitecture,
    page_size: usize,
    classify_target: bool,
    cancellation: &PackageCompatibilityCancellation,
) -> Result<PackageArchiveAnalysis, PackageRuntimeError> {
    cancellation.check()?;
    archive.seek(SeekFrom::Start(0))?;
    let (_, _, _, package_architecture) =
        parse_package_cache_filename(filename).ok_or(PackageRuntimeError::InvalidPayload)?;
    let architecture_any = package_architecture == "any";
    if !architecture_any && package_architecture != architecture.package_architecture() {
        return Err(PackageRuntimeError::InvalidPayload);
    }
    let result = if filename.ends_with(".pkg.tar.zst") {
        let decoder = zstd::stream::read::Decoder::new(archive)?;
        inspect_package_tar_cancellable(
            CancellableReader::new(decoder, cancellation),
            architecture,
            architecture_any,
            page_size,
            classify_target,
            cancellation,
        )
    } else if filename.ends_with(".pkg.tar.xz") {
        inspect_package_tar_cancellable(
            CancellableReader::new(XzDecoder::new(archive), cancellation),
            architecture,
            architecture_any,
            page_size,
            classify_target,
            cancellation,
        )
    } else {
        Err(PackageRuntimeError::InvalidPayload)
    };
    if cancellation.is_cancelled() {
        Err(PackageRuntimeError::Cancelled)
    } else {
        result
    }
}

#[cfg(test)]
fn inspect_package_tar(
    reader: impl Read,
    architecture: RepositoryArchitecture,
    architecture_any: bool,
    page_size: usize,
    classify_target: bool,
) -> Result<PackageArchiveAnalysis, PackageRuntimeError> {
    inspect_package_tar_cancellable(
        reader,
        architecture,
        architecture_any,
        page_size,
        classify_target,
        &PackageCompatibilityCancellation::new(),
    )
}

struct CancellableReader<'a, R> {
    inner: R,
    cancellation: &'a PackageCompatibilityCancellation,
}

impl<'a, R> CancellableReader<'a, R> {
    fn new(inner: R, cancellation: &'a PackageCompatibilityCancellation) -> Self {
        Self {
            inner,
            cancellation,
        }
    }
}

impl<R: Read> Read for CancellableReader<'_, R> {
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        self.cancellation.check().map_err(io::Error::other)?;
        let count = self.inner.read(buffer)?;
        self.cancellation.check().map_err(io::Error::other)?;
        Ok(count)
    }
}

fn inspect_package_tar_cancellable(
    reader: impl Read,
    architecture: RepositoryArchitecture,
    architecture_any: bool,
    page_size: usize,
    classify_target: bool,
    cancellation: &PackageCompatibilityCancellation,
) -> Result<PackageArchiveAnalysis, PackageRuntimeError> {
    cancellation.check()?;
    if !page_size.is_power_of_two() || !(4096..=64 * 1024).contains(&page_size) {
        return Err(PackageRuntimeError::InvalidPayload);
    }
    let mut archive = Archive::new(reader);
    let mut analysis = PackageArchiveAnalysis::default();
    let mut entry_count = 0_u64;
    let mut expanded_bytes = 0_u64;
    let mut header = [0_u8; PACKAGE_COMPATIBILITY_HEADER_BYTES];
    for entry in archive.entries()? {
        cancellation.check()?;
        let mut entry = entry?;
        let path = entry.path()?;
        let entry_type = entry.header().entry_type();
        validate_compatibility_archive_path(&path, entry_type.is_dir())?;
        let logical_path = normalized_archive_path(path.as_os_str().as_bytes())
            .map(|path| path.strip_suffix(b"/").unwrap_or(path))
            .ok_or(PackageRuntimeError::InvalidPayload)?;
        validate_compatibility_archive_entry_type(entry_type)?;
        if entry_type.is_symlink() || entry_type.is_hard_link() {
            let target = entry
                .link_name()?
                .ok_or(PackageRuntimeError::InvalidPayload)?;
            validate_compatibility_archive_link(&target, entry_type.is_hard_link())?;
        }
        entry_count = entry_count
            .checked_add(1)
            .ok_or(PackageRuntimeError::OutputLimit)?;
        if entry_count > PACKAGE_COMPATIBILITY_MAX_ENTRIES {
            return Err(PackageRuntimeError::OutputLimit);
        }
        let is_file = entry_type.is_file();
        if is_file {
            let bytes = entry.header().size()?;
            if bytes > PACKAGE_COMPATIBILITY_MAX_ENTRY_BYTES {
                return Err(PackageRuntimeError::OutputLimit);
            }
            expanded_bytes = expanded_bytes
                .checked_add(bytes)
                .ok_or(PackageRuntimeError::OutputLimit)?;
            if expanded_bytes > PACKAGE_COMPATIBILITY_MAX_EXPANDED_BYTES {
                return Err(PackageRuntimeError::OutputLimit);
            }
        }

        let command_path = direct_command_path(logical_path);
        let library_metadata_path = is_library_metadata_path(logical_path);
        let static_library_path = is_static_library_path(logical_path);
        let desktop_path = classify_target
            && logical_path.starts_with(b"usr/share/applications/")
            && logical_path.ends_with(b".desktop");
        let desktop_id = if desktop_path {
            logical_path
                .rsplit(|byte| *byte == b'/')
                .next()
                .and_then(|name| str::from_utf8(name).ok())
                .map(str::to_owned)
        } else {
            None
        };
        if classify_target && !entry_type.is_dir() {
            analysis.capabilities |= archive_path_capabilities(logical_path);
            if command_path {
                analysis.command_count = analysis
                    .command_count
                    .checked_add(1)
                    .ok_or(PackageRuntimeError::OutputLimit)?;
            }
        }
        if !is_file {
            continue;
        }
        let header_bytes = usize::try_from(
            entry
                .header()
                .size()?
                .min(PACKAGE_COMPATIBILITY_HEADER_BYTES as u64),
        )
        .map_err(|_| PackageRuntimeError::OutputLimit)?;
        entry.read_exact(&mut header[..header_bytes])?;
        cancellation.check()?;
        if desktop_path {
            let desktop_size = usize::try_from(entry.header().size()?)
                .map_err(|_| PackageRuntimeError::OutputLimit)?;
            if desktop_size <= desktop::MAX_DESKTOP_ENTRY_BYTES {
                let mut contents = Vec::with_capacity(desktop_size);
                contents.extend_from_slice(&header[..header_bytes]);
                entry.read_to_end(&mut contents)?;
                cancellation.check()?;
                if contents.len() != desktop_size {
                    return Err(PackageRuntimeError::SizeMismatch);
                }
                if let Some(entry) = desktop_id.as_deref().and_then(|desktop_id| {
                    desktop::parse_desktop_entry(desktop_id, &contents, |program| {
                        if program.starts_with('/') {
                            Some(program.to_owned())
                        } else if !program.is_empty() && !program.contains('/') {
                            Some(format!("/usr/bin/{program}"))
                        } else {
                            None
                        }
                    })
                    .ok()
                    .flatten()
                }) {
                    analysis.capabilities |= if entry.terminal {
                        PACKAGE_CAPABILITY_COMMAND_LINE
                    } else {
                        PACKAGE_CAPABILITY_GRAPHICAL
                    };
                }
            }
            continue;
        }
        let mode = entry.header().mode()?;
        let executable = mode & 0o111 != 0;
        let elf = header[..header_bytes].starts_with(b"\x7fELF");
        if elf {
            analysis.elf_count = analysis
                .elf_count
                .checked_add(1)
                .ok_or(PackageRuntimeError::OutputLimit)?;
            let runtime_elf = executable || library_metadata_path;
            let relocatable_library = static_library_path && !executable;
            let invalid = validate_elf_compatibility(
                &header[..header_bytes],
                architecture,
                architecture_any,
                page_size,
                runtime_elf,
                relocatable_library,
            );
            analysis.diagnostic = analysis.diagnostic.or(invalid);
        } else if command_path && executable && !supported_package_shebang(&header[..header_bytes])
        {
            analysis.diagnostic = analysis
                .diagnostic
                .or(Some(PackageCompatibilityDiagnostic::UnsupportedCommand));
        }
    }
    if entry_count == 0 {
        return Err(PackageRuntimeError::InvalidPayload);
    }
    cancellation.check()?;
    Ok(analysis)
}

fn validate_compatibility_archive_path(
    path: &Path,
    directory: bool,
) -> Result<(), PackageRuntimeError> {
    let bytes = path.as_os_str().as_bytes();
    let logical = normalized_archive_path(bytes)
        .map(|path| {
            if directory {
                path.strip_suffix(b"/").unwrap_or(path)
            } else {
                path
            }
        })
        .filter(|path| !path.is_empty())
        .ok_or(PackageRuntimeError::InvalidPayload)?;
    if !directory && logical.ends_with(b"/") {
        return Err(PackageRuntimeError::InvalidPayload);
    }
    if logical.len() > LOCAL_FILE_PATH_LIMIT
        || logical.starts_with(b"/")
        || logical.contains(&0)
        || logical
            .split(|byte| *byte == b'/')
            .any(|part| part.is_empty() || part == b"." || part == b"..")
        || bytes.is_empty()
        || bytes.len() > LOCAL_FILE_PATH_LIMIT
    {
        return Err(PackageRuntimeError::InvalidPayload);
    }
    Ok(())
}

fn validate_compatibility_archive_link(
    path: &Path,
    hard_link: bool,
) -> Result<(), PackageRuntimeError> {
    let bytes = path.as_os_str().as_bytes();
    if bytes.is_empty() || bytes.len() > LOCAL_FILE_PATH_LIMIT || bytes.contains(&0) {
        return Err(PackageRuntimeError::InvalidPayload);
    }
    if hard_link
        && (bytes.starts_with(b"/")
            || bytes
                .split(|byte| *byte == b'/')
                .any(|part| part.is_empty() || part == b"." || part == b".."))
    {
        return Err(PackageRuntimeError::InvalidPayload);
    }
    Ok(())
}

fn validate_compatibility_archive_entry_type(
    entry_type: EntryType,
) -> Result<(), PackageRuntimeError> {
    if entry_type.is_file()
        || entry_type.is_dir()
        || entry_type.is_symlink()
        || entry_type.is_hard_link()
    {
        Ok(())
    } else {
        Err(PackageRuntimeError::InvalidPayload)
    }
}

fn normalized_archive_path(mut path: &[u8]) -> Option<&[u8]> {
    while let Some(stripped) = path.strip_prefix(b"./") {
        path = stripped;
    }
    (!path.is_empty()).then_some(path)
}

fn archive_path_capabilities(path: &[u8]) -> u8 {
    let mut capabilities = 0_u8;
    if direct_command_path(path) {
        capabilities |= PACKAGE_CAPABILITY_COMMAND_LINE;
    }
    if path.starts_with(b"usr/include/")
        || (path.starts_with(b"usr/lib/") && is_library_metadata_path(path))
        || (path.starts_with(b"usr/share/pkgconfig/") && path.ends_with(b".pc"))
    {
        capabilities |= PACKAGE_CAPABILITY_LIBRARY;
    }
    if path.starts_with(b"usr/lib/systemd/")
        || path.starts_with(b"usr/lib/udev/")
        || path.starts_with(b"usr/lib/sysusers.d/")
        || path.starts_with(b"usr/lib/tmpfiles.d/")
    {
        capabilities |= PACKAGE_CAPABILITY_SYSTEM;
    }
    capabilities
}

fn direct_command_path(path: &[u8]) -> bool {
    [b"usr/bin/".as_slice(), b"usr/sbin/", b"bin/", b"sbin/"]
        .into_iter()
        .find_map(|prefix| path.strip_prefix(prefix))
        .is_some_and(|name| !name.is_empty() && !name.contains(&b'/'))
}

fn validate_elf_compatibility(
    header: &[u8],
    architecture: RepositoryArchitecture,
    architecture_any: bool,
    page_size: usize,
    runtime_elf: bool,
    relocatable_library: bool,
) -> Option<PackageCompatibilityDiagnostic> {
    if header.len() < 20
        || header.get(4) != Some(&2)
        || header.get(5) != Some(&1)
        || header.get(6) != Some(&1)
    {
        return runtime_elf.then_some(PackageCompatibilityDiagnostic::MalformedElf);
    }
    if architecture_any {
        return Some(PackageCompatibilityDiagnostic::NativeInAnyPackage);
    }
    if header.len() < 64 {
        return runtime_elf.then_some(PackageCompatibilityDiagnostic::MalformedElf);
    }
    let elf_type = u16::from_le_bytes([header[16], header[17]]);
    let elf_version = u32::from_le_bytes([header[20], header[21], header[22], header[23]]);
    let header_bytes = u16::from_le_bytes([header[52], header[53]]);
    let valid_type = matches!(elf_type, 2 | 3) || relocatable_library && matches!(elf_type, 1);
    if runtime_elf && (!valid_type || elf_version != 1 || header_bytes < 64) {
        return Some(PackageCompatibilityDiagnostic::MalformedElf);
    }
    let machine = u16::from_le_bytes([header[18], header[19]]);
    let expected = match architecture {
        RepositoryArchitecture::X86_64 => 62,
        RepositoryArchitecture::Aarch64 => 183,
    };
    if runtime_elf && machine != expected {
        return Some(PackageCompatibilityDiagnostic::ForeignElf);
    }
    if runtime_elf
        && !relocatable_library
        && page_size > 4096
        && !elf_supports_page_size(header, page_size)
    {
        return Some(PackageCompatibilityDiagnostic::IncompatiblePageSize);
    }
    None
}

fn elf_supports_page_size(header: &[u8], page_size: usize) -> bool {
    if header.len() < 64 {
        return false;
    }
    let program_offset = u64::from_le_bytes(header[32..40].try_into().unwrap_or([0; 8]));
    let program_entry_bytes = u16::from_le_bytes([header[54], header[55]]) as usize;
    let program_count = u16::from_le_bytes([header[56], header[57]]) as usize;
    let Ok(program_offset) = usize::try_from(program_offset) else {
        return false;
    };
    let Some(program_bytes) = program_entry_bytes.checked_mul(program_count) else {
        return false;
    };
    let Some(program_end) = program_offset.checked_add(program_bytes) else {
        return false;
    };
    if program_entry_bytes < 56
        || program_count == 0
        || program_end > header.len()
        || page_size == 0
    {
        return false;
    }
    let page_size = page_size as u64;
    let mut load_segments = 0_u16;
    for index in 0..program_count {
        let offset = program_offset + index * program_entry_bytes;
        let entry = &header[offset..offset + program_entry_bytes];
        if u32::from_le_bytes(entry[..4].try_into().unwrap_or([0; 4])) != 1 {
            continue;
        }
        load_segments = load_segments.saturating_add(1);
        let file_offset = u64::from_le_bytes(entry[8..16].try_into().unwrap_or([0; 8]));
        let virtual_address = u64::from_le_bytes(entry[16..24].try_into().unwrap_or([0; 8]));
        let alignment = u64::from_le_bytes(entry[48..56].try_into().unwrap_or([0; 8]));
        if alignment < page_size || file_offset % page_size != virtual_address % page_size {
            return false;
        }
    }
    load_segments > 0
}

fn supported_package_shebang(header: &[u8]) -> bool {
    if !header.starts_with(b"#!") {
        return false;
    }
    let end = header
        .iter()
        .position(|byte| *byte == b'\n')
        .unwrap_or(header.len());
    let Ok(line) = std::str::from_utf8(&header[2..end]) else {
        return false;
    };
    let interpreter = line.trim().split_ascii_whitespace().next().unwrap_or("");
    let Some(name) = interpreter
        .strip_prefix("/usr/bin/")
        .or_else(|| interpreter.strip_prefix("/bin/"))
    else {
        return false;
    };
    !name.is_empty()
        && !name.contains('/')
        && name
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'+' | b'-'))
}

fn scan_desktop_owners(
    mut file: File,
    expected_bytes: u64,
    package: &str,
    targets: &[(Vec<u8>, usize, bool)],
    entries: &mut [desktop::DesktopEntry],
    source_ambiguous: &mut [bool],
    executable_ambiguous: &mut [bool],
) -> bool {
    let mut read_bytes = 0_u64;
    let mut chunk = [0_u8; 8 * 1024];
    let mut line = Vec::with_capacity(256);
    let mut overlong = false;
    let mut in_files = false;
    let mut valid = true;
    loop {
        let read = match file.read(&mut chunk) {
            Ok(read) => read,
            Err(_) => return false,
        };
        if read == 0 {
            break;
        }
        read_bytes = match read_bytes.checked_add(u64::try_from(read).expect("read size")) {
            Some(bytes) if bytes <= expected_bytes => bytes,
            _ => return false,
        };
        for byte in &chunk[..read] {
            if *byte == b'\n' {
                if !overlong {
                    process_local_files_line(
                        &line,
                        &mut in_files,
                        package,
                        targets,
                        entries,
                        source_ambiguous,
                        executable_ambiguous,
                    );
                }
                line.clear();
                overlong = false;
            } else if !overlong {
                if line.len() >= LOCAL_FILE_PATH_LIMIT {
                    line.clear();
                    overlong = true;
                    valid = false;
                } else {
                    line.push(*byte);
                }
            }
        }
    }
    if !line.is_empty() && !overlong {
        process_local_files_line(
            &line,
            &mut in_files,
            package,
            targets,
            entries,
            source_ambiguous,
            executable_ambiguous,
        );
    }
    valid && read_bytes == expected_bytes
}

fn process_local_files_line(
    raw_line: &[u8],
    in_files: &mut bool,
    package: &str,
    targets: &[(Vec<u8>, usize, bool)],
    entries: &mut [desktop::DesktopEntry],
    source_ambiguous: &mut [bool],
    executable_ambiguous: &mut [bool],
) {
    let line = raw_line.strip_suffix(b"\r").unwrap_or(raw_line);
    if line == b"%FILES%" {
        *in_files = true;
        return;
    }
    if line.len() >= 2 && line.first() == Some(&b'%') && line.last() == Some(&b'%') {
        *in_files = false;
        return;
    }
    if !*in_files {
        return;
    }
    let start = targets.partition_point(|candidate| candidate.0.as_slice() < line);
    for (_, index, executable) in targets[start..]
        .iter()
        .take_while(|candidate| candidate.0.as_slice() == line)
    {
        let ambiguous = if *executable {
            &mut executable_ambiguous[*index]
        } else {
            &mut source_ambiguous[*index]
        };
        if *ambiguous {
            continue;
        }
        let owner = if *executable {
            &mut entries[*index].executable_package
        } else {
            &mut entries[*index].source_package
        };
        match owner.as_deref() {
            None => *owner = Some(package.to_owned()),
            Some(current) if current == package => {}
            Some(_) => {
                *owner = None;
                *ambiguous = true;
            }
        }
    }
}

fn local_database_entry_matches(
    entry: &Path,
    expected_name: &str,
    expected_version: &str,
) -> Result<bool, PackageRuntimeError> {
    let description = entry.join("desc");
    let metadata = match fs::symlink_metadata(&description) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(false),
        Err(error) => return Err(PackageRuntimeError::Io(error)),
    };
    if metadata.file_type().is_symlink()
        || !metadata.is_file()
        || metadata.len() == 0
        || metadata.len() > LOCAL_DESCRIPTION_LIMIT
    {
        return Ok(false);
    }
    let mut file = OpenOptions::new()
        .read(true)
        .custom_flags(O_NOFOLLOW | O_CLOEXEC)
        .open(&description)?;
    let opened = file.metadata()?;
    if !opened.is_file() || opened.len() != metadata.len() {
        return Err(PackageRuntimeError::SizeMismatch);
    }
    let mut content = String::with_capacity(
        usize::try_from(opened.len()).map_err(|_| PackageRuntimeError::OutputLimit)?,
    );
    if file.read_to_string(&mut content).is_err() {
        return Ok(false);
    }
    let name = local_description_field(&content, "%NAME%");
    let version = local_description_field(&content, "%VERSION%");
    Ok(matches!(
        (name, version),
        (Ok(Some(name)), Ok(Some(version)))
            if name == expected_name && version == expected_version
    ))
}

fn validate_database_repair_entry_if_present(path: &Path) -> Result<(), PackageRuntimeError> {
    match fs::symlink_metadata(path) {
        Ok(_) => validate_database_repair_entry(path),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(PackageRuntimeError::Io(error)),
    }
}

fn validate_database_repair_entry(path: &Path) -> Result<(), PackageRuntimeError> {
    let metadata = fs::symlink_metadata(path)?;
    if metadata.file_type().is_symlink() || !metadata.is_dir() {
        return Err(PackageRuntimeError::UnsafeEntry(path.to_path_buf()));
    }
    let mut count = 0_usize;
    for entry in fs::read_dir(path)? {
        count = count.saturating_add(1);
        if count > LOCAL_DATABASE_PACKAGE_FILE_COUNT {
            return Err(PackageRuntimeError::OutputLimit);
        }
        let entry = entry?;
        let entry_path = entry.path();
        let name = entry
            .file_name()
            .into_string()
            .map_err(|_| PackageRuntimeError::UnsafeEntry(entry_path.clone()))?;
        if !matches!(
            name.as_str(),
            "desc" | "files" | "mtree" | "install" | "changelog"
        ) {
            return Err(PackageRuntimeError::UnsafeEntry(entry_path));
        }
        let metadata = fs::symlink_metadata(&entry_path)?;
        if metadata.file_type().is_symlink()
            || !metadata.is_file()
            || metadata.len() > LOCAL_DATABASE_PACKAGE_FILE_LIMIT
        {
            return Err(PackageRuntimeError::UnsafeEntry(entry_path));
        }
    }
    Ok(())
}

fn remove_database_repair_entry_if_present(path: &Path) -> Result<(), PackageRuntimeError> {
    match fs::symlink_metadata(path) {
        Ok(_) => remove_database_repair_entry(path),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(PackageRuntimeError::Io(error)),
    }
}

fn remove_database_repair_entry(path: &Path) -> Result<(), PackageRuntimeError> {
    validate_database_repair_entry(path)?;
    let directory = File::open(path)?;
    let mut files = Vec::with_capacity(LOCAL_DATABASE_PACKAGE_FILE_COUNT);
    for entry in fs::read_dir(path)? {
        files.push(entry?.path());
    }
    for file in files {
        fs::remove_file(file)?;
    }
    directory.sync_all()?;
    drop(directory);
    fs::remove_dir(path)?;
    File::open(path.parent().ok_or(PackageRuntimeError::InvalidPath)?)?.sync_all()?;
    Ok(())
}

fn copy_database_repair_entry(
    source: &Path,
    destination: &Path,
) -> Result<(), PackageRuntimeError> {
    validate_database_repair_entry(source)?;
    match fs::symlink_metadata(destination) {
        Ok(_) => return Err(PackageRuntimeError::Busy),
        Err(error) if error.kind() == io::ErrorKind::NotFound => {}
        Err(error) => return Err(PackageRuntimeError::Io(error)),
    }
    fs::create_dir(destination)?;
    fs::set_permissions(destination, fs::Permissions::from_mode(0o700))?;
    let result = (|| {
        for name in ["changelog", "desc", "files", "install", "mtree"] {
            let source_file = source.join(name);
            let metadata = match fs::symlink_metadata(&source_file) {
                Ok(metadata) => metadata,
                Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
                Err(error) => return Err(PackageRuntimeError::Io(error)),
            };
            if metadata.file_type().is_symlink()
                || !metadata.is_file()
                || metadata.len() > LOCAL_DATABASE_PACKAGE_FILE_LIMIT
            {
                return Err(PackageRuntimeError::UnsafeEntry(source_file));
            }
            let mut input = OpenOptions::new()
                .read(true)
                .custom_flags(O_NOFOLLOW | O_CLOEXEC)
                .open(&source_file)?;
            if input.metadata()?.len() != metadata.len() {
                return Err(PackageRuntimeError::SizeMismatch);
            }
            let destination_file = destination.join(name);
            let mut output = OpenOptions::new()
                .create_new(true)
                .write(true)
                .mode(0o600)
                .custom_flags(O_NOFOLLOW | O_CLOEXEC)
                .open(&destination_file)?;
            let copied = io::copy(&mut input, &mut output)?;
            if copied != metadata.len() {
                return Err(PackageRuntimeError::SizeMismatch);
            }
            output.sync_all()?;
        }
        File::open(destination)?.sync_all()?;
        validate_database_repair_entry(destination)
    })();
    if result.is_err() {
        let _ = remove_database_repair_entry_if_present(destination);
    }
    result
}

fn removal_repair_sha256(
    path: &Path,
    expected_name: &str,
    expected_version: &str,
) -> Result<String, PackageRuntimeError> {
    validate_database_repair_entry(path)?;
    if !local_database_entry_matches(path, expected_name, expected_version)? {
        return Err(PackageRuntimeError::InvalidResolution);
    }
    let mut digest = Sha256::new();
    digest.update(b"org.archphene.package-removal-repair.v1\0");
    let mut buffer = [0_u8; 64 * 1024];
    for name in ["changelog", "desc", "files", "install", "mtree"] {
        let file_path = path.join(name);
        let metadata = match fs::symlink_metadata(&file_path) {
            Ok(metadata) => metadata,
            Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
            Err(error) => return Err(PackageRuntimeError::Io(error)),
        };
        if metadata.file_type().is_symlink()
            || !metadata.is_file()
            || metadata.len() > LOCAL_DATABASE_PACKAGE_FILE_LIMIT
        {
            return Err(PackageRuntimeError::UnsafeEntry(file_path));
        }
        digest.update((name.len() as u64).to_le_bytes());
        digest.update(name.as_bytes());
        digest.update(metadata.len().to_le_bytes());
        let mut file = OpenOptions::new()
            .read(true)
            .custom_flags(O_NOFOLLOW | O_CLOEXEC)
            .open(&file_path)?;
        let mut remaining = metadata.len();
        while remaining != 0 {
            let limit = usize::try_from(remaining.min(buffer.len() as u64))
                .map_err(|_| PackageRuntimeError::OutputLimit)?;
            let read = file.read(&mut buffer[..limit])?;
            if read == 0 {
                return Err(PackageRuntimeError::SizeMismatch);
            }
            digest.update(&buffer[..read]);
            remaining -= read as u64;
        }
        if file.read(&mut buffer[..1])? != 0 {
            return Err(PackageRuntimeError::SizeMismatch);
        }
    }
    let digest: [u8; 32] = digest.finalize().into();
    Ok(hex_sha256(&digest))
}

fn valid_sha256_hex(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn local_description_field<'a>(
    input: &'a str,
    field: &str,
) -> Result<Option<&'a str>, PackageRuntimeError> {
    let mut lines = input.lines();
    let mut result = None;
    while let Some(line) = lines.next() {
        if line != field {
            continue;
        }
        let value = lines
            .next()
            .filter(|value| !value.is_empty())
            .ok_or(PackageRuntimeError::InvalidResolution)?;
        if result.replace(value).is_some() {
            return Err(PackageRuntimeError::InvalidResolution);
        }
    }
    Ok(result)
}

fn append_search_result(
    output: &mut ToolOutput,
    repository: &str,
    name: &str,
    version: &str,
    description: &str,
    state: &str,
    installed_version: &str,
) -> Result<(), PackageRuntimeError> {
    if description.len() > 512
        || description
            .bytes()
            .any(|byte| matches!(byte, b'\t' | b'\r' | b'\n' | 0))
    {
        return Err(PackageRuntimeError::InvalidManifest);
    }
    for (index, field) in [
        repository,
        name,
        version,
        description,
        state,
        installed_version,
    ]
    .into_iter()
    .enumerate()
    {
        output.push(field.as_bytes())?;
        output.push(if index == 5 { b"\n" } else { b"\t" })?;
    }
    Ok(())
}

fn validate_resolution_targets(
    targets: &[&str],
    catalogs_ready: bool,
) -> Result<(), PackageRuntimeError> {
    if !catalogs_ready {
        return Err(PackageRuntimeError::InvalidCatalog);
    }
    if targets.is_empty()
        || targets.len() > 256
        || targets.iter().any(|target| !safe_logical_name(target))
        || targets
            .iter()
            .enumerate()
            .any(|(index, target)| targets[..index].contains(target))
    {
        return Err(PackageRuntimeError::InvalidQuery);
    }
    Ok(())
}

fn exact_missing_repository_target(error: &PackageRuntimeError, target: &str) -> bool {
    let PackageRuntimeError::ToolFailed(1, output) = error else {
        return false;
    };
    let Ok(diagnostic) = output.as_str() else {
        return false;
    };
    diagnostic == format!("error: target not found: {target}\n")
        || diagnostic == format!("error: target not found: {target}")
}

fn partition_repository_targets<F>(
    targets: &[&str],
    mut resolve: F,
) -> Result<RepositoryTargetPartition, PackageRuntimeError>
where
    F: FnMut(&[&str]) -> Result<PackageResolution, PackageRuntimeError>,
{
    if targets.is_empty()
        || targets.len() > 256
        || targets.iter().any(|target| !safe_logical_name(target))
        || targets
            .iter()
            .enumerate()
            .any(|(index, target)| targets[..index].contains(target))
    {
        return Err(PackageRuntimeError::InvalidQuery);
    }

    match resolve(targets) {
        Ok(resolution) => {
            return Ok(RepositoryTargetPartition {
                official_targets: targets.iter().map(|target| (*target).to_owned()).collect(),
                unresolved_targets: Vec::new(),
                resolution: Some(resolution),
            });
        }
        Err(error) if targets.len() == 1 => {
            if exact_missing_repository_target(&error, targets[0]) {
                return Ok(RepositoryTargetPartition {
                    official_targets: Vec::new(),
                    unresolved_targets: vec![targets[0].to_owned()],
                    resolution: None,
                });
            }
            return Err(error);
        }
        Err(_) => {}
    }

    let mut official = vec![false; targets.len()];
    let mut stack = vec![(0_usize, targets.len())];
    while let Some((start, end)) = stack.pop() {
        let subset = &targets[start..end];
        match resolve(subset) {
            Ok(_) => official[start..end].fill(true),
            Err(error) if subset.len() == 1 => {
                if !exact_missing_repository_target(&error, subset[0]) {
                    return Err(error);
                }
            }
            Err(_) => {
                let middle = start + subset.len() / 2;
                stack.push((middle, end));
                stack.push((start, middle));
            }
        }
    }

    let official_targets: Vec<String> = targets
        .iter()
        .zip(&official)
        .filter(|(_, is_official)| **is_official)
        .map(|(target, _)| (*target).to_owned())
        .collect();
    let unresolved_targets: Vec<String> = targets
        .iter()
        .zip(&official)
        .filter(|(_, is_official)| !**is_official)
        .map(|(target, _)| (*target).to_owned())
        .collect();
    let resolution = if official_targets.is_empty() {
        None
    } else {
        let borrowed: Vec<&str> = official_targets.iter().map(String::as_str).collect();
        Some(resolve(&borrowed)?)
    };
    Ok(RepositoryTargetPartition {
        official_targets,
        unresolved_targets,
        resolution,
    })
}

fn parse_resolution_output(
    input: &str,
    targets: &[&str],
    architecture: RepositoryArchitecture,
) -> Result<PackageResolution, PackageRuntimeError> {
    let mut output = PackageResolution {
        bytes: Vec::with_capacity(input.len().min(MAX_PACKAGE_RESOLUTION_BYTES)),
    };
    if targets.is_empty()
        || targets.len() > 256
        || targets.iter().any(|target| !safe_logical_name(target))
    {
        return Err(PackageRuntimeError::InvalidQuery);
    }
    let mut contains_targets = vec![false; targets.len()];
    let mut count = 0_usize;
    for line in input.lines() {
        if line.is_empty() {
            continue;
        }
        let mut fields = line.split('\t');
        let repository = fields
            .next()
            .ok_or(PackageRuntimeError::InvalidResolution)?;
        let name = fields
            .next()
            .ok_or(PackageRuntimeError::InvalidResolution)?;
        let version = fields
            .next()
            .ok_or(PackageRuntimeError::InvalidResolution)?;
        let filename = fields
            .next()
            .ok_or(PackageRuntimeError::InvalidResolution)?;
        let url = fields
            .next()
            .ok_or(PackageRuntimeError::InvalidResolution)?;
        let size = fields
            .next()
            .ok_or(PackageRuntimeError::InvalidResolution)?;
        let Ok(size_bytes) = size.parse::<u64>() else {
            return Err(PackageRuntimeError::InvalidResolution);
        };
        if fields.next().is_some()
            || !matches!(repository, "core" | "extra")
            || !safe_logical_name(name)
            || version.is_empty()
            || version.len() > 128
            || version
                .bytes()
                .any(|byte| byte.is_ascii_whitespace() || byte == 0)
            || !safe_package_filename(filename)
            || architecture
                .package_url_prefix(repository)
                .is_none_or(|prefix| url.strip_prefix(prefix) != Some(filename))
            || size_bytes == 0
            || size_bytes > 4 * 1024 * 1024 * 1024
            || resolution_contains(&output, name)?
        {
            return Err(PackageRuntimeError::InvalidResolution);
        }
        count += 1;
        if count > 512 {
            return Err(PackageRuntimeError::OutputLimit);
        }
        for (index, target) in targets.iter().enumerate() {
            contains_targets[index] |= name == *target;
        }
        for (index, field) in [repository, name, version, filename, url, size]
            .into_iter()
            .enumerate()
        {
            resolution_push(&mut output, field.as_bytes())?;
            resolution_push(&mut output, if index == 5 { b"\n" } else { b"\t" })?;
        }
    }
    if count == 0 || contains_targets.iter().any(|contains| !contains) {
        return Err(PackageRuntimeError::MissingTarget);
    }
    Ok(output)
}

struct ResolvedPayload<'a> {
    repository: &'a str,
    name: &'a str,
    version: &'a str,
    filename: &'a str,
    url: &'a str,
    size: u64,
}

struct InstallArchive {
    path: String,
    name: String,
    version: String,
    explicitly_installed: bool,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum InstallResolutionMode {
    Normal,
    Repair,
}

fn append_install_transaction_mode(arguments: &mut Vec<&str>, mode: InstallResolutionMode) {
    if mode == InstallResolutionMode::Normal {
        arguments.push("--needed");
    }
    arguments.extend_from_slice(&["--asdeps", "-U"]);
}

#[derive(Clone, Debug, Eq, PartialEq)]
enum PackageMutationIntent {
    Install {
        request: String,
        explicit_targets: Vec<String>,
        resolution: PackageResolution,
    },
    Remove {
        package: String,
        version: String,
        database_sha256: Option<String>,
    },
}

fn serialize_package_mutation_intent(
    intent: &PackageMutationIntent,
    architecture: RepositoryArchitecture,
) -> Result<String, PackageRuntimeError> {
    let mut content = String::with_capacity(4096);
    content.push_str(PACKAGE_MUTATION_INTENT_HEADER);
    content.push('\n');
    match intent {
        PackageMutationIntent::Install {
            request,
            explicit_targets,
            resolution,
        } => {
            content.push_str("install\t");
            content.push_str(request);
            content.push('\n');
            for target in explicit_targets {
                content.push_str("explicit\t");
                content.push_str(target);
                content.push('\n');
            }
            for line in resolution.as_str()?.lines() {
                content.push_str("archive\t");
                content.push_str(line);
                content.push('\n');
            }
        }
        PackageMutationIntent::Remove {
            package,
            version,
            database_sha256,
        } => {
            content.push_str("remove\t");
            content.push_str(package);
            content.push('\t');
            content.push_str(version);
            if let Some(database_sha256) = database_sha256 {
                content.push('\t');
                content.push_str(database_sha256);
            }
            content.push('\n');
        }
    }
    if content.len() as u64 > PACKAGE_MUTATION_INTENT_LIMIT {
        return Err(PackageRuntimeError::OutputLimit);
    }
    parse_package_mutation_intent(&content, architecture)?;
    Ok(content)
}

fn parse_package_mutation_intent(
    input: &str,
    architecture: RepositoryArchitecture,
) -> Result<PackageMutationIntent, PackageRuntimeError> {
    if input.is_empty()
        || input.len() as u64 > PACKAGE_MUTATION_INTENT_LIMIT
        || !input.ends_with('\n')
    {
        return Err(PackageRuntimeError::InvalidResolution);
    }
    let mut lines = input.lines();
    if lines.next() != Some(PACKAGE_MUTATION_INTENT_HEADER) {
        return Err(PackageRuntimeError::InvalidResolution);
    }
    let operation = lines.next().ok_or(PackageRuntimeError::InvalidResolution)?;
    if let Some(request) = operation.strip_prefix("install\t") {
        if !safe_logical_name(request) {
            return Err(PackageRuntimeError::InvalidResolution);
        }
        let mut explicit_targets = Vec::with_capacity(2);
        let mut resolution_text = String::with_capacity(input.len());
        let mut reading_archives = false;
        for line in lines {
            if let Some(target) = line.strip_prefix("explicit\t") {
                if reading_archives
                    || !safe_logical_name(target)
                    || explicit_targets.len() >= 256
                    || explicit_targets.iter().any(|entry| entry == target)
                {
                    return Err(PackageRuntimeError::InvalidResolution);
                }
                explicit_targets.push(target.to_owned());
            } else if let Some(archive) = line.strip_prefix("archive\t") {
                reading_archives = true;
                resolution_text.push_str(archive);
                resolution_text.push('\n');
            } else {
                return Err(PackageRuntimeError::InvalidResolution);
            }
        }
        if explicit_targets.is_empty() || resolution_text.is_empty() {
            return Err(PackageRuntimeError::InvalidResolution);
        }
        let target_refs = explicit_targets
            .iter()
            .map(String::as_str)
            .collect::<Vec<_>>();
        let resolution = parse_resolution_output(&resolution_text, &target_refs, architecture)?;
        resolved_version(&resolution, request)?;
        Ok(PackageMutationIntent::Install {
            request: request.to_owned(),
            explicit_targets,
            resolution,
        })
    } else if let Some(removal) = operation.strip_prefix("remove\t") {
        if lines.next().is_some() {
            return Err(PackageRuntimeError::InvalidResolution);
        }
        let mut fields = removal.split('\t');
        let package = fields
            .next()
            .ok_or(PackageRuntimeError::InvalidResolution)?;
        let version = fields
            .next()
            .ok_or(PackageRuntimeError::InvalidResolution)?;
        let database_sha256 = fields.next();
        if fields.next().is_some()
            || !safe_logical_name(package)
            || version.is_empty()
            || version.len() > 128
            || version
                .bytes()
                .any(|byte| byte.is_ascii_whitespace() || byte == 0)
            || database_sha256.is_some_and(|value| !valid_sha256_hex(value))
        {
            return Err(PackageRuntimeError::InvalidResolution);
        }
        Ok(PackageMutationIntent::Remove {
            package: package.to_owned(),
            version: version.to_owned(),
            database_sha256: database_sha256.map(str::to_owned),
        })
    } else {
        Err(PackageRuntimeError::InvalidResolution)
    }
}

fn resolved_version<'a>(
    resolution: &'a PackageResolution,
    package: &str,
) -> Result<&'a str, PackageRuntimeError> {
    resolution
        .as_str()?
        .lines()
        .map(parse_resolved_payload)
        .find_map(|payload| match payload {
            Ok(payload) if payload.name == package => Some(Ok(payload.version)),
            Ok(_) => None,
            Err(error) => Some(Err(error)),
        })
        .transpose()?
        .ok_or(PackageRuntimeError::MissingTarget)
}

fn parse_install_reason_intent(input: &str) -> Result<Vec<&str>, PackageRuntimeError> {
    if input.len() as u64 > INSTALL_REASON_INTENT_LIMIT {
        return Err(PackageRuntimeError::InvalidResolution);
    }
    let mut lines = input.lines();
    if lines.next() != Some(INSTALL_REASON_INTENT_HEADER) {
        return Err(PackageRuntimeError::InvalidResolution);
    }
    let mut packages = Vec::with_capacity(8);
    for package in lines {
        if !safe_logical_name(package) || packages.len() >= 256 || packages.contains(&package) {
            return Err(PackageRuntimeError::InvalidResolution);
        }
        packages.push(package);
    }
    if packages.is_empty() || !input.ends_with('\n') {
        return Err(PackageRuntimeError::InvalidResolution);
    }
    Ok(packages)
}

fn validate_install_plan(
    input: &str,
    archives: &[InstallArchive],
) -> Result<(), PackageRuntimeError> {
    if archives.is_empty() || archives.len() > 256 {
        return Err(PackageRuntimeError::InvalidResolution);
    }
    let mut planned = [false; 256];
    let mut count = 0_usize;
    for line in input.lines() {
        // Pacman writes stable C-locale reinstall notices to stderr. The bounded
        // command runner deliberately captures both streams so failures retain
        // their diagnostics; ignore only that narrowly identified diagnostic
        // class while still requiring every resolved archive below.
        if line.starts_with("warning: ") {
            continue;
        }
        let (name, version) = line
            .split_once('\t')
            .ok_or(PackageRuntimeError::InvalidResolution)?;
        if name.is_empty()
            || version.is_empty()
            || version.bytes().any(|byte| byte.is_ascii_whitespace())
        {
            return Err(PackageRuntimeError::InvalidResolution);
        }
        let index = archives
            .iter()
            .position(|archive| archive.name == name && archive.version == version)
            .ok_or(PackageRuntimeError::InvalidResolution)?;
        if planned[index] {
            return Err(PackageRuntimeError::InvalidResolution);
        }
        planned[index] = true;
        count = count.saturating_add(1);
        if count > archives.len() {
            return Err(PackageRuntimeError::InvalidResolution);
        }
    }
    if count == archives.len() {
        Ok(())
    } else {
        Err(PackageRuntimeError::InvalidResolution)
    }
}

fn parse_resolved_payload(line: &str) -> Result<ResolvedPayload<'_>, PackageRuntimeError> {
    let mut fields = line.split('\t');
    let repository = fields
        .next()
        .ok_or(PackageRuntimeError::InvalidResolution)?;
    let name = fields
        .next()
        .ok_or(PackageRuntimeError::InvalidResolution)?;
    let version = fields
        .next()
        .ok_or(PackageRuntimeError::InvalidResolution)?;
    let filename = fields
        .next()
        .ok_or(PackageRuntimeError::InvalidResolution)?;
    let url = fields
        .next()
        .ok_or(PackageRuntimeError::InvalidResolution)?;
    let size = fields
        .next()
        .ok_or(PackageRuntimeError::InvalidResolution)?
        .parse::<u64>()
        .map_err(|_| PackageRuntimeError::InvalidResolution)?;
    if fields.next().is_some()
        || !matches!(repository, "core" | "extra")
        || !safe_logical_name(name)
        || version.is_empty()
        || !safe_package_filename(filename)
        || url.is_empty()
        || size == 0
        || size > PACKAGE_ARCHIVE_LIMIT
    {
        return Err(PackageRuntimeError::InvalidResolution);
    }
    Ok(ResolvedPayload {
        repository,
        name,
        version,
        filename,
        url,
        size,
    })
}

fn parse_package_info_size(input: &str) -> Result<u64, PackageRuntimeError> {
    let mut size = None;
    for line in input.lines() {
        let Some(value) = line.strip_prefix("size = ") else {
            continue;
        };
        if size.is_some() {
            return Err(PackageRuntimeError::InvalidPayload);
        }
        let parsed = value
            .parse::<u64>()
            .map_err(|_| PackageRuntimeError::InvalidPayload)?;
        if parsed > PACKAGE_ARCHIVE_LIMIT * 16 {
            return Err(PackageRuntimeError::InvalidPayload);
        }
        size = Some(parsed);
    }
    size.ok_or(PackageRuntimeError::InvalidPayload)
}

fn resolution_push(
    output: &mut PackageResolution,
    bytes: &[u8],
) -> Result<(), PackageRuntimeError> {
    let end = output
        .bytes
        .len()
        .checked_add(bytes.len())
        .ok_or(PackageRuntimeError::OutputLimit)?;
    if end > MAX_PACKAGE_RESOLUTION_BYTES {
        return Err(PackageRuntimeError::OutputLimit);
    }
    output.bytes.extend_from_slice(bytes);
    Ok(())
}

fn resolution_contains(
    output: &PackageResolution,
    package: &str,
) -> Result<bool, PackageRuntimeError> {
    Ok(output.as_str()?.lines().any(|line| {
        let mut fields = line.split('\t');
        fields.next().is_some() && fields.next() == Some(package)
    }))
}

fn safe_package_filename(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 240
        && (value.ends_with(".pkg.tar.zst") || value.ends_with(".pkg.tar.xz"))
        && value.bytes().all(|byte| {
            byte.is_ascii_alphanumeric() || matches!(byte, b'@' | b'.' | b'_' | b'+' | b':' | b'-')
        })
}

fn safe_package_cache_filename(value: &str) -> bool {
    let without_part = value.strip_suffix(".part").unwrap_or(value);
    let without_signature = without_part.strip_suffix(".sig").unwrap_or(without_part);
    safe_package_filename(without_signature)
}

fn parse_package_cache_filename(value: &str) -> Option<(&str, &str, &str, &str)> {
    let without_part = value.strip_suffix(".part").unwrap_or(value);
    let without_signature = without_part.strip_suffix(".sig").unwrap_or(without_part);
    let stem = without_signature
        .strip_suffix(".pkg.tar.zst")
        .or_else(|| without_signature.strip_suffix(".pkg.tar.xz"))?;
    let mut fields = stem.rsplitn(4, '-');
    let architecture = fields.next()?;
    let release = fields.next()?;
    let version = fields.next()?;
    let package = fields.next()?;
    if !safe_logical_name(package)
        || architecture.is_empty()
        || release.is_empty()
        || version.is_empty()
        || architecture.len() > 32
        || release.len() > 64
        || version.len() > 128
    {
        return None;
    }
    Some((package, version, release, architecture))
}

fn validate_signature_status(
    output: &[u8],
    architecture: RepositoryArchitecture,
) -> Result<(), PackageRuntimeError> {
    let status_lines = || {
        output
            .split(|byte| *byte == b'\n')
            .filter_map(|line| line.strip_prefix(b"[GNUPG:] "))
    };
    if status_lines().any(|line| {
        let token = line
            .split(|byte| byte.is_ascii_whitespace())
            .next()
            .unwrap_or_default();
        matches!(
            token,
            b"BADSIG" | b"ERRSIG" | b"REVKEYSIG" | b"EXPKEYSIG" | b"KEYEXPIRED" | b"SIGEXPIRED"
        )
    }) {
        return Err(PackageRuntimeError::InvalidSignature);
    }
    let signer = status_lines().find_map(|line| {
        line.strip_prefix(b"VALIDSIG ")
            .and_then(|fields| fields.split(|byte| byte.is_ascii_whitespace()).next())
    });
    let Some(signer) = signer else {
        return Err(PackageRuntimeError::InvalidSignature);
    };
    if !matches!(signer.len(), 40 | 64)
        || !signer
            .iter()
            .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_lowercase())
        || architecture == RepositoryArchitecture::Aarch64 && signer != AARCH64_BUILD_KEY.as_bytes()
    {
        return Err(PackageRuntimeError::InvalidSignature);
    }
    Ok(())
}

fn validate_package_info(
    input: &str,
    expected_name: &str,
    expected_version: &str,
    architecture: RepositoryArchitecture,
) -> Result<(), PackageRuntimeError> {
    let mut name = None;
    let mut version = None;
    let mut package_architecture = None;
    for line in input.lines() {
        let Some((key, value)) = line.split_once(" = ") else {
            continue;
        };
        match key {
            "pkgname" if name.replace(value).is_none() => {}
            "pkgver" if version.replace(value).is_none() => {}
            "arch" if package_architecture.replace(value).is_none() => {}
            "pkgname" | "pkgver" | "arch" => {
                return Err(PackageRuntimeError::InvalidPayload);
            }
            _ => {}
        }
    }
    let valid_architecture = package_architecture == Some("any")
        || package_architecture == Some(architecture.package_architecture());
    if name != Some(expected_name) || version != Some(expected_version) || !valid_architecture {
        return Err(PackageRuntimeError::InvalidPayload);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;
    use std::sync::atomic::{AtomicU64, Ordering};

    static TEST_ID: AtomicU64 = AtomicU64::new(1);

    fn test_resolution(label: &str) -> PackageResolution {
        PackageResolution {
            bytes: label.as_bytes().to_vec(),
        }
    }

    fn missing_repository_target(target: &str) -> PackageRuntimeError {
        test_tool_failure(1, format!("error: target not found: {target}\n").as_bytes())
    }

    fn test_tool_failure(code: i32, diagnostic: &[u8]) -> PackageRuntimeError {
        let mut output = empty_tool_output();
        output
            .push(diagnostic)
            .expect("package tool failure diagnostic");
        PackageRuntimeError::ToolFailed(code, Box::new(output))
    }

    fn package_tar(entries: &[(&str, u32, &[u8])]) -> Vec<u8> {
        let mut output = Vec::new();
        {
            let mut builder = tar::Builder::new(&mut output);
            builder.mode(tar::HeaderMode::Deterministic);
            for (path, mode, content) in entries {
                let mut header = tar::Header::new_gnu();
                header.set_size(content.len() as u64);
                header.set_mode(*mode);
                header.set_entry_type(EntryType::Regular);
                header.set_cksum();
                builder
                    .append_data(&mut header, path, Cursor::new(*content))
                    .expect("package archive entry");
            }
            builder.finish().expect("package archive");
        }
        output
    }

    struct CancelOnFirstRead<R> {
        inner: R,
        cancellation: PackageCompatibilityCancellation,
        first: bool,
    }

    impl<R: Read> Read for CancelOnFirstRead<R> {
        fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
            let count = self.inner.read(buffer)?;
            if self.first {
                self.first = false;
                self.cancellation.cancel();
            }
            Ok(count)
        }
    }

    fn elf_header(machine: u16) -> [u8; 64] {
        let mut header = [0_u8; 64];
        header[..4].copy_from_slice(b"\x7fELF");
        header[4] = 2;
        header[5] = 1;
        header[6] = 1;
        header[16..18].copy_from_slice(&3_u16.to_le_bytes());
        header[18..20].copy_from_slice(&machine.to_le_bytes());
        header[20..24].copy_from_slice(&1_u32.to_le_bytes());
        header[52..54].copy_from_slice(&64_u16.to_le_bytes());
        header
    }

    fn elf_with_load_segment(machine: u16, alignment: u64) -> Vec<u8> {
        let mut elf = vec![0_u8; 64 + 56];
        elf[..64].copy_from_slice(&elf_header(machine));
        elf[32..40].copy_from_slice(&64_u64.to_le_bytes());
        elf[54..56].copy_from_slice(&56_u16.to_le_bytes());
        elf[56..58].copy_from_slice(&1_u16.to_le_bytes());
        elf[64..68].copy_from_slice(&1_u32.to_le_bytes());
        elf[64 + 48..64 + 56].copy_from_slice(&alignment.to_le_bytes());
        elf
    }

    struct TestTree {
        root: PathBuf,
        native: PathBuf,
    }

    impl TestTree {
        fn new() -> Self {
            let id = TEST_ID.fetch_add(1, Ordering::Relaxed);
            let root = std::env::temp_dir().join(format!(
                "archphene-packages-test-{}-{id}",
                std::process::id()
            ));
            let native = root.join("native");
            fs::create_dir_all(root.join("run")).expect("run directory");
            fs::create_dir(root.join("etc")).expect("etc directory");
            fs::create_dir_all(root.join(CATALOG_DIRECTORY)).expect("catalog directory");
            fs::create_dir_all(root.join(PACKAGE_CACHE_DIRECTORY)).expect("package cache");
            fs::create_dir(&native).expect("native directory");
            Self { root, native }
        }

        fn file(&self, name: &str, bytes: &[u8]) {
            fs::write(self.native.join(name), bytes).expect("native fixture");
        }

        fn command(&self, name: &str) {
            let directory = self.root.join("usr/bin");
            fs::create_dir_all(&directory).expect("command directory");
            let path = directory.join(name);
            fs::write(&path, b"\x7fELF test command").expect("command fixture");
            fs::set_permissions(path, fs::Permissions::from_mode(0o755))
                .expect("command permissions");
        }

        fn package_runtime(&self) -> PackageRuntime {
            self.file("libarchphene_pkg_111111111111111111111111.so", b"loader");
            self.file("libarchphene_pkg_222222222222222222222222.so", b"pacman");
            self.file("libarchphene_pkg_444444444444444444444444.so", b"keyring");
            self.file("libarchphene_pkg_555555555555555555555555.so", b"bridge");
            self.file("libarchphene_pkg_666666666666666666666666.so", b"trust");
            let manifest = b"# org.archphene.package-runtime.v1\n\
loader\t@loader\tlibarchphene_pkg_111111111111111111111111.so\t6\n\
tool\t@pacman\tlibarchphene_pkg_222222222222222222222222.so\t6\n\
keyring\t@keyring\tlibarchphene_pkg_444444444444444444444444.so\t7\n\
ownertrust\t@ownertrust\tlibarchphene_pkg_666666666666666666666666.so\t5\n\
library\tlibarchphene_path_bridge.so\tlibarchphene_pkg_555555555555555555555555.so\t6\n";
            PackageRuntime::prepare(
                &self.root,
                &self.native,
                manifest,
                RepositoryArchitecture::X86_64,
            )
            .expect("package runtime")
        }

        fn local_package(&self, directory: &str, name: &str, files: &[u8]) {
            let local = self.root.join("var/lib/pacman/local").join(directory);
            fs::create_dir_all(&local).expect("local package directory");
            fs::write(
                local.join("desc"),
                format!("%NAME%\n{name}\n\n%VERSION%\n1.0-1\n"),
            )
            .expect("local package description");
            fs::write(local.join("files"), files).expect("local package files");
        }

        fn local_dependency_package(&self, directory: &str, name: &str, files: &[u8]) {
            self.local_package(directory, name, files);
            let description = self
                .root
                .join("var/lib/pacman/local")
                .join(directory)
                .join("desc");
            fs::write(
                description,
                format!("%NAME%\n{name}\n\n%VERSION%\n1.0-1\n\n%REASON%\n1\n"),
            )
            .expect("local dependency description");
        }
    }

    impl Drop for TestTree {
        fn drop(&mut self) {
            if let (Some(parent), Some(name)) = (self.root.parent(), self.root.file_name()) {
                let state = parent.join(format!(".{}-manager-state-v1", name.to_string_lossy()));
                let _ = fs::remove_dir_all(state);
            }
            let _ = fs::remove_dir_all(&self.root);
        }
    }

    #[test]
    fn system_trust_bundle_must_resolve_to_a_safe_bounded_root_file() {
        let tree = TestTree::new();
        let extracted = tree.root.join("etc/ca-certificates/extracted");
        let certificates = tree.root.join("etc/ssl/certs");
        fs::create_dir_all(&extracted).expect("extracted trust directory");
        fs::create_dir_all(&certificates).expect("certificate directory");
        let bundle = extracted.join("tls-ca-bundle.pem");
        fs::write(&bundle, b"certificate\n").expect("trust bundle");
        fs::set_permissions(&bundle, fs::Permissions::from_mode(0o444)).expect("trust bundle mode");
        symlink(
            "../../ca-certificates/extracted/tls-ca-bundle.pem",
            certificates.join("ca-certificates.crt"),
        )
        .expect("trust bundle link");
        assert!(system_trust_bundle_ready(&tree.root));

        fs::set_permissions(&bundle, fs::Permissions::from_mode(0o666))
            .expect("unsafe trust bundle mode");
        assert!(!system_trust_bundle_ready(&tree.root));
        fs::set_permissions(&bundle, fs::Permissions::from_mode(0o444))
            .expect("restore trust bundle mode");
        fs::remove_file(certificates.join("ca-certificates.crt"))
            .expect("remove trust bundle link");
        symlink("/etc/passwd", certificates.join("ca-certificates.crt"))
            .expect("escaping trust bundle link");
        assert!(!system_trust_bundle_ready(&tree.root));
    }

    #[test]
    fn valid_manifest_prepares_exact_library_aliases() {
        let tree = TestTree::new();
        tree.file("libarchphene_pkg_111111111111111111111111.so", b"loader");
        tree.file("libarchphene_pkg_222222222222222222222222.so", b"pacman");
        tree.file("libarchphene_pkg_333333333333333333333333.so", b"library");
        tree.file("libarchphene_pkg_444444444444444444444444.so", b"keyring");
        tree.file("libarchphene_pkg_555555555555555555555555.so", b"bridge");
        tree.file("libarchphene_pkg_666666666666666666666666.so", b"trust");
        let manifest = b"# org.archphene.package-runtime.v1\n\
loader\t@loader\tlibarchphene_pkg_111111111111111111111111.so\t6\n\
tool\t@pacman\tlibarchphene_pkg_222222222222222222222222.so\t6\n\
keyring\t@keyring\tlibarchphene_pkg_444444444444444444444444.so\t7\n\
ownertrust\t@ownertrust\tlibarchphene_pkg_666666666666666666666666.so\t5\n\
library\tlibarchphene_path_bridge.so\tlibarchphene_pkg_555555555555555555555555.so\t6\n\
library\tlibalpm.so.16\tlibarchphene_pkg_333333333333333333333333.so\t7\n";
        let runtime = PackageRuntime::prepare(
            &tree.root,
            &tree.native,
            manifest,
            RepositoryArchitecture::X86_64,
        )
        .expect("package runtime");
        let alias = runtime.alias_root().join("libalpm.so.16");
        assert!(
            fs::symlink_metadata(&alias)
                .expect("alias metadata")
                .file_type()
                .is_symlink()
        );
        assert_eq!(
            alias.canonicalize().expect("alias target"),
            tree.native
                .join("libarchphene_pkg_333333333333333333333333.so")
                .canonicalize()
                .expect("source target")
        );
        assert_eq!(
            fs::read(tree.root.join(PACMAN_CONFIG_FILE)).expect("pacman config"),
            X86_64_PACMAN_CONFIG
        );
        assert_eq!(
            fs::metadata(tree.root.join(PACMAN_CONFIG_FILE))
                .expect("pacman config metadata")
                .permissions()
                .mode()
                & 0o7777,
            0o600
        );
        assert!(
            runtime.library_path.as_encoded_bytes().ends_with(
                tree.root
                    .join("usr/lib/pulseaudio")
                    .as_os_str()
                    .as_encoded_bytes()
            )
        );
    }

    #[test]
    fn gdk_pixbuf_compatibility_is_complete_bounded_and_published() {
        let tree = TestTree::new();
        tree.file("libarchphene_pkg_111111111111111111111111.so", b"loader");
        tree.file("libarchphene_pkg_222222222222222222222222.so", b"pacman");
        tree.file("libarchphene_pkg_333333333333333333333333.so", b"pixbuf");
        tree.file("libarchphene_pkg_444444444444444444444444.so", b"keyring");
        tree.file("libarchphene_pkg_555555555555555555555555.so", b"bridge");
        tree.file("libarchphene_pkg_666666666666666666666666.so", b"trust");
        tree.file("libarchphene_pkg_777777777777777777777777.so", b"rsvg");
        tree.file(
            "libarchphene_pkg_888888888888888888888888.so",
            b"svg loader",
        );
        let manifest = b"# org.archphene.package-runtime.v1\n\
loader\t@loader\tlibarchphene_pkg_111111111111111111111111.so\t6\n\
tool\t@pacman\tlibarchphene_pkg_222222222222222222222222.so\t6\n\
keyring\t@keyring\tlibarchphene_pkg_444444444444444444444444.so\t7\n\
ownertrust\t@ownertrust\tlibarchphene_pkg_666666666666666666666666.so\t5\n\
library\tlibarchphene_path_bridge.so\tlibarchphene_pkg_555555555555555555555555.so\t6\n\
library\tlibgdk_pixbuf-2.0.so.0\tlibarchphene_pkg_333333333333333333333333.so\t6\n\
library\tlibrsvg-2.so.2\tlibarchphene_pkg_777777777777777777777777.so\t4\n\
library\tlibarchphene_pixbufloader_svg.so\tlibarchphene_pkg_888888888888888888888888.so\t10\n";
        let runtime = PackageRuntime::prepare(
            &tree.root,
            &tree.native,
            manifest,
            RepositoryArchitecture::X86_64,
        )
        .expect("GTK compatibility runtime");
        let module_file = runtime
            .gdk_pixbuf_module_file
            .as_ref()
            .expect("module file");
        let content = fs::read_to_string(module_file).expect("module cache");
        assert!(content.contains("libarchphene_pixbufloader_svg.so"));
        assert!(content.contains("\"image/svg+xml\""));
        assert_eq!(
            fs::metadata(module_file)
                .expect("module metadata")
                .permissions()
                .mode()
                & 0o7777,
            0o600
        );

        let partial = b"# org.archphene.package-runtime.v1\n\
loader\t@loader\tlibarchphene_pkg_111111111111111111111111.so\t6\n\
tool\t@pacman\tlibarchphene_pkg_222222222222222222222222.so\t6\n\
keyring\t@keyring\tlibarchphene_pkg_444444444444444444444444.so\t7\n\
ownertrust\t@ownertrust\tlibarchphene_pkg_666666666666666666666666.so\t5\n\
library\tlibarchphene_path_bridge.so\tlibarchphene_pkg_555555555555555555555555.so\t6\n\
library\tlibgdk_pixbuf-2.0.so.0\tlibarchphene_pkg_333333333333333333333333.so\t6\n";
        assert!(matches!(
            PackageRuntime::prepare(
                &tree.root,
                &tree.native,
                partial,
                RepositoryArchitecture::X86_64,
            ),
            Err(PackageRuntimeError::InvalidManifest)
        ));
    }

    #[test]
    fn toolkit_modules_publish_complete_verified_plugin_topology() {
        let tree = TestTree::new();
        fs::create_dir_all(tree.root.join("home/archphene")).expect("home");
        for (name, content) in [
            (
                "libarchphene_pkg_111111111111111111111111.so",
                b"loader".as_slice(),
            ),
            (
                "libarchphene_pkg_222222222222222222222222.so",
                b"pacman".as_slice(),
            ),
            (
                "libarchphene_pkg_333333333333333333333333.so",
                b"keyring".as_slice(),
            ),
            (
                "libarchphene_pkg_444444444444444444444444.so",
                b"bridge".as_slice(),
            ),
            (
                "libarchphene_pkg_555555555555555555555555.so",
                b"trust".as_slice(),
            ),
            (
                "libarchphene_pkg_666666666666666666666666.so",
                b"gtk".as_slice(),
            ),
            (
                "libarchphene_pkg_777777777777777777777777.so",
                b"platform".as_slice(),
            ),
            (
                "libarchphene_pkg_888888888888888888888888.so",
                b"style".as_slice(),
            ),
            (
                "libarchphene_pkg_999999999999999999999999.so",
                b"kconfig".as_slice(),
            ),
        ] {
            tree.file(name, content);
        }
        let manifest = b"# org.archphene.package-runtime.v1\n\
loader\t@loader\tlibarchphene_pkg_111111111111111111111111.so\t6\n\
tool\t@pacman\tlibarchphene_pkg_222222222222222222222222.so\t6\n\
keyring\t@keyring\tlibarchphene_pkg_333333333333333333333333.so\t7\n\
library\tlibarchphene_path_bridge.so\tlibarchphene_pkg_444444444444444444444444.so\t6\n\
ownertrust\t@ownertrust\tlibarchphene_pkg_555555555555555555555555.so\t5\n\
library\tlibarchphene_gtk3_settings.so\tlibarchphene_pkg_666666666666666666666666.so\t3\n\
library\tlibarchphene_qt_platform_theme.so\tlibarchphene_pkg_777777777777777777777777.so\t8\n\
library\tlibarchphene_qt_style.so\tlibarchphene_pkg_888888888888888888888888.so\t5\n\
library\tlibarchphene_kde_config.so\tlibarchphene_pkg_999999999999999999999999.so\t7\n";
        let runtime = PackageRuntime::prepare(
            &tree.root,
            &tree.native,
            manifest,
            RepositoryArchitecture::X86_64,
        )
        .expect("toolkit runtime");
        let plugin_root = runtime.qt_plugin_root.as_ref().expect("Qt plugin root");
        assert!(
            fs::symlink_metadata(
                plugin_root
                    .join("platformthemes")
                    .join(QT_PLATFORM_THEME_LIBRARY),
            )
            .expect("platform theme")
            .file_type()
            .is_symlink(),
        );
        assert!(
            fs::symlink_metadata(plugin_root.join("styles").join(QT_STYLE_LIBRARY))
                .expect("style")
                .file_type()
                .is_symlink(),
        );
        let appearance = GuiAppearance::new(true, 100, 20, 32, [1, 2, 3], [4, 5, 6], [7, 8, 9])
            .expect("appearance");
        runtime
            .command_environment_with_gui(appearance)
            .expect("GUI environment");

        let partial = manifest
            .strip_suffix(
                b"library\tlibarchphene_kde_config.so\tlibarchphene_pkg_999999999999999999999999.so\t7\n",
            )
            .expect("partial manifest");
        assert!(matches!(
            PackageRuntime::prepare(
                &tree.root,
                &tree.native,
                partial,
                RepositoryArchitecture::X86_64,
            ),
            Err(PackageRuntimeError::InvalidManifest),
        ));
    }

    #[test]
    fn desktop_catalog_derives_bounded_package_ownership_and_rejects_ambiguity() {
        let tree = TestTree::new();
        tree.command("editor");
        tree.command("dependency-editor");
        let applications = tree.root.join("usr/share/applications");
        fs::create_dir_all(&applications).expect("applications directory");
        fs::write(
            applications.join("editor.desktop"),
            b"[Desktop Entry]\nType=Application\nName=Editor\nExec=editor\n",
        )
        .expect("desktop entry");
        fs::write(
            applications.join("dependency-editor.desktop"),
            b"[Desktop Entry]\nType=Application\nName=Dependency editor\nExec=dependency-editor\n",
        )
        .expect("dependency desktop entry");
        tree.local_package(
            "editor-1.0-1",
            "editor",
            b"%FILES%\nusr/share/applications/editor.desktop\n\n",
        );
        tree.local_dependency_package(
            "editor-runtime-1.0-1",
            "editor-runtime",
            b"%FILES%\nusr/bin/editor\n\n",
        );
        tree.local_dependency_package(
            "dependency-editor-1.0-1",
            "dependency-editor",
            b"%FILES%\nusr/bin/dependency-editor\nusr/share/applications/dependency-editor.desktop\n\n",
        );
        tree.local_package(
            "backup-only-1.0-1",
            "backup-only",
            b"%FILES%\nusr/bin/backup-only\n\n%BACKUP%\nusr/share/applications/editor.desktop\n",
        );
        tree.local_package("metadata-only-1.0-1", "metadata-only", b"");
        let runtime = tree.package_runtime();
        let catalog = runtime.desktop_catalog().expect("owned desktop catalog");
        assert_eq!(catalog.entries.len(), 1);
        assert_eq!(catalog.entries[0].source_package.as_deref(), Some("editor"));
        assert_eq!(
            catalog.entries[0].executable_package.as_deref(),
            Some("editor-runtime")
        );
        assert!(!catalog.truncated);
        assert!(
            catalog
                .page(0)
                .expect("owned desktop page")
                .as_str()
                .expect("UTF-8 desktop page")
                .ends_with("\teditor\teditor-runtime\n")
        );

        tree.local_package(
            "other-runtime-1.0-1",
            "other-runtime",
            b"%FILES%\nusr/bin/editor\n",
        );
        let catalog = runtime
            .desktop_catalog()
            .expect("ambiguous executable owner catalog");
        assert_eq!(catalog.entries.len(), 1);
        assert!(catalog.entries[0].executable_package.is_none());
        assert!(catalog.truncated);

        tree.local_package(
            "other-editor-1.0-1",
            "other-editor",
            b"%FILES%\nusr/share/applications/editor.desktop\n",
        );
        let catalog = runtime
            .desktop_catalog()
            .expect("ambiguous desktop catalog");
        assert!(catalog.entries.is_empty());
        assert!(catalog.truncated);
    }

    #[test]
    fn verification_keyring_cache_is_source_keyed_and_bounded() {
        let tree = TestTree::new();
        tree.file("libarchphene_pkg_111111111111111111111111.so", b"loader");
        tree.file("libarchphene_pkg_222222222222222222222222.so", b"pacman");
        tree.file("libarchphene_pkg_444444444444444444444444.so", b"keyring");
        tree.file("libarchphene_pkg_555555555555555555555555.so", b"bridge");
        tree.file("libarchphene_pkg_666666666666666666666666.so", b"trust");
        let manifest = b"# org.archphene.package-runtime.v1\n\
loader\t@loader\tlibarchphene_pkg_111111111111111111111111.so\t6\n\
tool\t@pacman\tlibarchphene_pkg_222222222222222222222222.so\t6\n\
keyring\t@keyring\tlibarchphene_pkg_444444444444444444444444.so\t7\n\
ownertrust\t@ownertrust\tlibarchphene_pkg_666666666666666666666666.so\t5\n\
library\tlibarchphene_path_bridge.so\tlibarchphene_pkg_555555555555555555555555.so\t6\n";
        let mut runtime = PackageRuntime::prepare(
            &tree.root,
            &tree.native,
            manifest,
            RepositoryArchitecture::X86_64,
        )
        .expect("package runtime");
        let expected = runtime
            .verification_keyring_source_state()
            .expect("source state");
        let trust = tree.root.join(PACKAGE_TRUST_DIRECTORY);
        fs::create_dir(&trust).expect("trust directory");
        fs::write(trust.join("pubring.kbx"), b"keybox").expect("keybox");
        fs::write(trust.join("trustdb.gpg"), b"trustdb").expect("trustdb");
        fs::write(trust.join(PACKAGE_TRUST_STATE), &expected).expect("state");
        assert!(
            runtime
                .reuse_verification_keyring(&trust, &expected)
                .expect("reuse")
        );
        assert_eq!(runtime.keyring, trust.join("pubring.kbx"));

        let mut changed = PackageRuntime::prepare(
            &tree.root,
            &tree.native,
            manifest,
            RepositoryArchitecture::X86_64,
        )
        .expect("changed runtime");
        assert!(
            !changed
                .reuse_verification_keyring(&trust, "different\n")
                .expect("changed source")
        );

        fs::remove_file(trust.join("pubring.kbx")).expect("remove keybox");
        symlink("/system/build.prop", trust.join("pubring.kbx")).expect("unsafe keybox");
        assert!(matches!(
            changed.reuse_verification_keyring(&trust, &expected),
            Err(PackageRuntimeError::UnsafeEntry(_))
        ));
    }

    #[test]
    fn shell_discovery_uses_declared_safe_installed_adapters() {
        let tree = TestTree::new();
        tree.file("libarchphene_pkg_111111111111111111111111.so", b"loader");
        tree.file("libarchphene_pkg_222222222222222222222222.so", b"pacman");
        tree.file("libarchphene_pkg_444444444444444444444444.so", b"keyring");
        tree.file("libarchphene_pkg_555555555555555555555555.so", b"bridge");
        tree.file("libarchphene_pkg_666666666666666666666666.so", b"trust");
        tree.command("bash");
        std::os::unix::fs::symlink("bash", tree.root.join("usr/bin/sh")).expect("sh alias");
        fs::write(
            tree.root.join(SHELLS_FILE),
            b"# valid shells\n/bin/sh\n/usr/bin/bash\n/usr/bin/zsh\n",
        )
        .expect("shells file");
        let manifest = b"# org.archphene.package-runtime.v1\n\
loader\t@loader\tlibarchphene_pkg_111111111111111111111111.so\t6\n\
tool\t@pacman\tlibarchphene_pkg_222222222222222222222222.so\t6\n\
keyring\t@keyring\tlibarchphene_pkg_444444444444444444444444.so\t7\n\
ownertrust\t@ownertrust\tlibarchphene_pkg_666666666666666666666666.so\t5\n\
library\tlibarchphene_path_bridge.so\tlibarchphene_pkg_555555555555555555555555.so\t6\n";
        let runtime = PackageRuntime::prepare(
            &tree.root,
            &tree.native,
            manifest,
            RepositoryArchitecture::X86_64,
        )
        .expect("package runtime");
        assert_eq!(
            runtime
                .discover_shells()
                .expect("shell catalog")
                .as_str()
                .expect("utf-8"),
            "bash\tBash\tbash\t--noprofile\t--noediting\n\
sh\tPOSIX shell\tsh\t--noprofile\t--noediting\n"
        );

        fs::set_permissions(
            tree.root.join(SHELLS_FILE),
            fs::Permissions::from_mode(0o666),
        )
        .expect("unsafe shells mode");
        assert!(matches!(
            runtime.discover_shells(),
            Err(PackageRuntimeError::UnsafeEntry(_))
        ));
    }

    #[test]
    fn duplicate_logical_names_and_size_mismatches_fail_closed() {
        let tree = TestTree::new();
        tree.file("libarchphene_pkg_111111111111111111111111.so", b"loader");
        let duplicate = b"# org.archphene.package-runtime.v1\n\
loader\t@loader\tlibarchphene_pkg_111111111111111111111111.so\t6\n\
loader\t@loader\tlibarchphene_pkg_111111111111111111111111.so\t6\n";
        assert!(matches!(
            PackageRuntime::prepare(
                &tree.root,
                &tree.native,
                duplicate,
                RepositoryArchitecture::X86_64,
            ),
            Err(PackageRuntimeError::DuplicateEntry)
        ));
        let wrong_size = b"# org.archphene.package-runtime.v1\n\
loader\t@loader\tlibarchphene_pkg_111111111111111111111111.so\t5\n";
        assert!(matches!(
            PackageRuntime::prepare(
                &tree.root,
                &tree.native,
                wrong_size,
                RepositoryArchitecture::X86_64,
            ),
            Err(PackageRuntimeError::SizeMismatch)
        ));
    }

    #[test]
    fn non_symlink_content_in_the_alias_directory_is_rejected() {
        let tree = TestTree::new();
        let alias_root = tree.root.join(ALIAS_DIRECTORY);
        fs::create_dir_all(&alias_root).expect("alias directory");
        fs::write(alias_root.join("untrusted"), b"content").expect("unsafe alias content");
        tree.file("libarchphene_pkg_111111111111111111111111.so", b"loader");
        tree.file("libarchphene_pkg_222222222222222222222222.so", b"pacman");
        tree.file("libarchphene_pkg_444444444444444444444444.so", b"keyring");
        tree.file("libarchphene_pkg_555555555555555555555555.so", b"bridge");
        tree.file("libarchphene_pkg_666666666666666666666666.so", b"trust");
        let manifest = b"# org.archphene.package-runtime.v1\n\
loader\t@loader\tlibarchphene_pkg_111111111111111111111111.so\t6\n\
tool\t@pacman\tlibarchphene_pkg_222222222222222222222222.so\t6\n\
keyring\t@keyring\tlibarchphene_pkg_444444444444444444444444.so\t7\n\
ownertrust\t@ownertrust\tlibarchphene_pkg_666666666666666666666666.so\t5\n\
library\tlibarchphene_path_bridge.so\tlibarchphene_pkg_555555555555555555555555.so\t6\n";
        assert!(matches!(
            PackageRuntime::prepare(
                &tree.root,
                &tree.native,
                manifest,
                RepositoryArchitecture::X86_64,
            ),
            Err(PackageRuntimeError::UnsafeEntry(_))
        ));
    }

    #[test]
    fn catalog_downloads_are_bounded_and_atomically_published() {
        let tree = TestTree::new();
        tree.file("libarchphene_pkg_111111111111111111111111.so", b"loader");
        tree.file("libarchphene_pkg_222222222222222222222222.so", b"pacman");
        tree.file("libarchphene_pkg_444444444444444444444444.so", b"keyring");
        tree.file("libarchphene_pkg_555555555555555555555555.so", b"bridge");
        tree.file("libarchphene_pkg_666666666666666666666666.so", b"trust");
        let manifest = b"# org.archphene.package-runtime.v1\n\
loader\t@loader\tlibarchphene_pkg_111111111111111111111111.so\t6\n\
tool\t@pacman\tlibarchphene_pkg_222222222222222222222222.so\t6\n\
keyring\t@keyring\tlibarchphene_pkg_444444444444444444444444.so\t7\n\
ownertrust\t@ownertrust\tlibarchphene_pkg_666666666666666666666666.so\t5\n\
library\tlibarchphene_path_bridge.so\tlibarchphene_pkg_555555555555555555555555.so\t6\n";
        let runtime = PackageRuntime::prepare(
            &tree.root,
            &tree.native,
            manifest,
            RepositoryArchitecture::Aarch64,
        )
        .expect("package runtime");
        let (download, url) = runtime
            .begin_catalog_download(Repository::Core)
            .expect("catalog download");
        assert_eq!(
            url,
            "https://ca.us.mirror.archlinuxarm.org/aarch64/core/core.db"
        );
        let mut writer = download.duplicate_file().expect("catalog descriptor");
        writer.write_all(b"catalog").expect("catalog content");
        drop(writer);
        assert_eq!(download.finish().expect("publish catalog"), 7);
        assert_eq!(
            fs::read(tree.root.join(CATALOG_DIRECTORY).join("core.db")).expect("published catalog"),
            b"catalog"
        );

        let (empty, _) = runtime
            .begin_catalog_download(Repository::Extra)
            .expect("empty catalog");
        assert!(matches!(
            empty.finish(),
            Err(PackageRuntimeError::InvalidCatalog)
        ));
        assert!(
            !tree
                .root
                .join(CATALOG_DIRECTORY)
                .join(".extra.db.download")
                .exists()
        );
    }

    #[test]
    fn package_search_output_is_strict_and_bounded() {
        let parsed = parse_search_output(
            "extra/dotnet-sdk 10.0.10.sdk110-1\n    The .NET Core SDK\n\
extra/dotnet-sdk-8.0 8.0.29.sdk129-1 [installed]\n    The .NET Core SDK\n",
            "dotnet-sdk",
        )
        .expect("search output");
        assert_eq!(
            parsed.as_str().expect("utf-8"),
            "extra\tdotnet-sdk\t10.0.10.sdk110-1\tThe .NET Core SDK\tavailable\t\n\
extra\tdotnet-sdk-8.0\t8.0.29.sdk129-1\tThe .NET Core SDK\tinstalled\t8.0.29.sdk129-1\n"
        );
        assert!(valid_search_query("dotnet-sdk"));
        assert!(!valid_search_query("a"));
        assert!(!valid_search_query("../dotnet"));
        assert_eq!(exact_search_pattern("dotnet-sdk"), "^dotnet-sdk$");
        assert_eq!(exact_search_pattern("libc++"), "^libc\\+\\+$");
        assert_eq!(exact_search_pattern("foo.bar"), "^foo\\.bar$");
    }

    #[test]
    fn package_search_ranks_an_exact_name_before_substring_matches() {
        let parsed = parse_search_output(
            "extra/gst-plugin-rstracers 1.26.4-1\n    GStreamer tracing plugins\n\
extra/strace 6.16-1\n    A diagnostic tracing utility\n\
extra/strace-analyzer 1.0-1\n    Analyze strace output\n",
            "strace",
        )
        .expect("search output");
        assert_eq!(
            parsed.as_str().expect("utf-8"),
            "extra\tstrace\t6.16-1\tA diagnostic tracing utility\tavailable\t\n\
extra\tgst-plugin-rstracers\t1.26.4-1\tGStreamer tracing plugins\tavailable\t\n\
extra\tstrace-analyzer\t1.0-1\tAnalyze strace output\tavailable\t\n",
        );
    }

    #[test]
    fn package_search_preserves_pacman_install_state() {
        let parsed = parse_search_output(
            "extra/current 2.0-1 (tools) [installed]\n    Current package\n\
extra/changed 3.0-1 [installed: 2.5-1]\n    Different package\n\
extra/available 1.0-1\n    Available package\n",
            "package",
        )
        .expect("search output");
        assert_eq!(
            parsed.as_str().expect("utf-8"),
            "extra\tcurrent\t2.0-1\tCurrent package\tinstalled\t2.0-1\n\
extra\tchanged\t3.0-1\tDifferent package\tdifferent\t2.5-1\n\
extra\tavailable\t1.0-1\tAvailable package\tavailable\t\n",
        );
        let differing = differing_search_packages(&parsed).expect("differing packages");
        assert_eq!(differing, ["changed"]);
        let updates =
            parse_quiet_update_names("changed\n", &differing).expect("version-safe update names");
        assert!(parse_exact_quiet_update("changed\n", "changed").expect("exact update"));
        assert!(!parse_exact_quiet_update("", "changed").expect("no update"));
        assert!(matches!(
            parse_exact_quiet_update("other\n", "changed"),
            Err(PackageRuntimeError::InvalidResolution)
        ));
        assert_eq!(
            annotate_search_update_names(parsed, &updates)
                .expect("annotated search")
                .as_str()
                .expect("UTF-8"),
            "extra\tcurrent\t2.0-1\tCurrent package\tinstalled\t2.0-1\n\
extra\tchanged\t3.0-1\tDifferent package\tupdate\t2.5-1\n\
extra\tavailable\t1.0-1\tAvailable package\tavailable\t\n",
        );
        assert!(matches!(
            parse_quiet_update_names("unrelated\n", &differing),
            Err(PackageRuntimeError::InvalidResolution)
        ));
        assert!(matches!(
            parse_search_output(
                "extra/broken 1.0-1 [installed: invalid version]\n    Broken\n",
                "broken",
            ),
            Err(PackageRuntimeError::InvalidResolution)
        ));
    }

    #[test]
    fn installed_package_queries_are_exact_and_bounded() {
        let version = parse_installed_version("btop 1.4.7-1\n", "btop").expect("installed version");
        assert_eq!(version.as_str().expect("UTF-8"), "1.4.7-1");
        assert!(missing_package_query(
            "error: package 'btop' was not found\n",
            "btop",
        ));
        assert!(!missing_package_query(
            "error: failed to read the package database\n",
            "btop",
        ));
        assert!(matches!(
            parse_installed_version("other 1.4.7-1\n", "btop"),
            Err(PackageRuntimeError::InvalidResolution)
        ));
        assert!(matches!(
            parse_installed_version("btop 1.4.7-1\nextra output\n", "btop"),
            Err(PackageRuntimeError::InvalidResolution)
        ));
        assert!(safe_package_version("1:10.0.0-2"));
        assert!(!safe_package_version("10.0 release"));
        assert!(exact_missing_dependency(
            "visual-studio-code-bin>1.2.3-1\n",
            "visual-studio-code-bin>1.2.3-1",
        ));
        assert!(!exact_missing_dependency(
            "other>1.2.3-1\n",
            "visual-studio-code-bin>1.2.3-1",
        ));
        let explicit = "%NAME%\nbtop\n\n%VERSION%\n1.4.7-1\n";
        assert_eq!(
            local_description_field(explicit, "%NAME%").expect("name"),
            Some("btop"),
        );
        assert_eq!(
            local_description_field(explicit, "%REASON%").expect("explicit reason"),
            None,
        );
        let dependency = "%NAME%\nglibc\n\n%REASON%\n1\n";
        assert_eq!(
            local_description_field(dependency, "%REASON%").expect("dependency reason"),
            Some("1"),
        );
        assert!(matches!(
            local_description_field("%REASON%\n1\n%REASON%\n0\n", "%REASON%"),
            Err(PackageRuntimeError::InvalidResolution)
        ));
    }

    #[test]
    fn installed_package_origin_distinguishes_repository_and_local_archives() {
        let tree = TestTree::new();
        let runtime = tree.package_runtime();
        tree.local_package("signed-tool-1.0-1", "signed-tool", b"%FILES%\n");
        tree.local_package("aur-tool-1.0-1", "aur-tool", b"%FILES%\n");
        fs::write(
            tree.root
                .join("var/lib/pacman/local/signed-tool-1.0-1/desc"),
            b"%NAME%\nsigned-tool\n\n%VERSION%\n1.0-1\n\n%VALIDATION%\npgp\n",
        )
        .expect("signed description");
        fs::write(
            tree.root.join("var/lib/pacman/local/aur-tool-1.0-1/desc"),
            b"%NAME%\naur-tool\n\n%VERSION%\n1.0-1\n\n%VALIDATION%\nnone\n",
        )
        .expect("AUR description");

        assert_eq!(
            runtime
                .installed_origin("signed-tool")
                .expect("official origin")
                .as_str()
                .expect("official UTF-8"),
            "official",
        );
        assert_eq!(
            runtime
                .installed_origin("aur-tool")
                .expect("AUR origin")
                .as_str()
                .expect("AUR UTF-8"),
            "aur",
        );
        assert!(matches!(
            runtime.installed_origin("missing-tool"),
            Err(PackageRuntimeError::NotInstalled)
        ));
    }

    #[test]
    fn installed_package_pages_are_sorted_bounded_and_safe() {
        let tree = TestTree::new();
        tree.file("libarchphene_pkg_111111111111111111111111.so", b"loader");
        tree.file("libarchphene_pkg_222222222222222222222222.so", b"pacman");
        tree.file("libarchphene_pkg_444444444444444444444444.so", b"keyring");
        tree.file("libarchphene_pkg_555555555555555555555555.so", b"bridge");
        tree.file("libarchphene_pkg_666666666666666666666666.so", b"trust");
        let manifest = b"# org.archphene.package-runtime.v1\n\
loader\t@loader\tlibarchphene_pkg_111111111111111111111111.so\t6\n\
tool\t@pacman\tlibarchphene_pkg_222222222222222222222222.so\t6\n\
keyring\t@keyring\tlibarchphene_pkg_444444444444444444444444.so\t7\n\
ownertrust\t@ownertrust\tlibarchphene_pkg_666666666666666666666666.so\t5\n\
library\tlibarchphene_path_bridge.so\tlibarchphene_pkg_555555555555555555555555.so\t6\n";
        let runtime = PackageRuntime::prepare(
            &tree.root,
            &tree.native,
            manifest,
            RepositoryArchitecture::X86_64,
        )
        .expect("package runtime");
        let local = tree.root.join("var/lib/pacman/local");
        fs::create_dir_all(&local).expect("local database");
        fs::write(local.join("ALPM_DB_VERSION"), b"9\n").expect("database version");
        for index in (0..66).rev() {
            let name = format!("fixture-{index:03}");
            let directory = local.join(format!("{name}-1.0.0-1"));
            fs::create_dir(&directory).expect("package database entry");
            fs::write(
                directory.join("desc"),
                format!(
                    "%NAME%\n{name}\n\n%VERSION%\n1.0.{index}-1\n\n%REASON%\n{}\n",
                    index % 2
                ),
            )
            .expect("package description");
        }

        let catalog = runtime
            .installed_package_catalog()
            .expect("installed package catalog");
        let first = catalog.page(0).expect("first page");
        let first = first.as_str().expect("first page UTF-8");
        assert_eq!(first.lines().count(), INSTALLED_PACKAGE_PAGE_SIZE);
        assert!(first.starts_with("fixture-000\t1.0.0-1\t1\t0\t0\n"));
        assert!(first.ends_with("fixture-059\t1.0.59-1\t0\t0\t0\n"));
        let second = catalog
            .page(INSTALLED_PACKAGE_PAGE_SIZE)
            .expect("second page");
        assert_eq!(
            second.as_str().expect("second page UTF-8"),
            "fixture-060\t1.0.60-1\t1\t0\t0\nfixture-061\t1.0.61-1\t0\t0\t0\n\
fixture-062\t1.0.62-1\t1\t0\t0\nfixture-063\t1.0.63-1\t0\t0\t0\n\
fixture-064\t1.0.64-1\t1\t0\t0\nfixture-065\t1.0.65-1\t0\t0\t0\n",
        );
        assert!(catalog.page(66).expect("empty page").as_bytes().is_empty());

        let duplicate = local.join("duplicate-entry");
        fs::create_dir(&duplicate).expect("duplicate directory");
        fs::write(
            duplicate.join("desc"),
            b"%NAME%\nfixture-000\n\n%VERSION%\n2.0-1\n\n%REASON%\n0\n",
        )
        .expect("duplicate description");
        assert!(matches!(
            runtime.installed_package_catalog(),
            Err(PackageRuntimeError::InvalidResolution)
        ));
        fs::remove_dir_all(&duplicate).expect("remove duplicate");

        let unsafe_description = local.join("fixture-000-1.0.0-1/desc");
        fs::remove_file(&unsafe_description).expect("remove description");
        symlink("/system/build.prop", &unsafe_description).expect("unsafe description");
        assert!(matches!(
            runtime.installed_package_catalog(),
            Err(PackageRuntimeError::UnsafeEntry(_))
        ));
    }

    #[test]
    fn installed_package_capabilities_come_only_from_owned_files() {
        let tree = TestTree::new();
        let runtime = tree.package_runtime();
        tree.local_package(
            "desktop-tool-1.0-1",
            "desktop-tool",
            b"%FILES%\nusr/\nusr/bin/\nusr/bin/desktop-tool\nusr/share/\n\
usr/share/applications/\nusr/share/applications/desktop-tool.desktop\n\n",
        );
        tree.local_package(
            "development-kit-1.0-1",
            "development-kit",
            b"%FILES%\nusr/include/tool/api.h\nusr/lib/libtool.so.2\nusr/lib/pkgconfig/tool.pc\n\n",
        );
        tree.local_package(
            "system-data-1.0-1",
            "system-data",
            b"%FILES%\nusr/lib/systemd/system/tool.service\nusr/share/tool/data.bin\n\n",
        );
        let unknown = tree
            .root
            .join("var/lib/pacman/local/unknown-metadata-1.0-1");
        fs::create_dir_all(&unknown).expect("unknown package database entry");
        fs::write(
            unknown.join("desc"),
            b"%NAME%\nunknown-metadata\n\n%VERSION%\n1.0-1\n",
        )
        .expect("unknown package description");

        let catalog = runtime
            .installed_package_catalog()
            .expect("installed package catalog");
        assert_eq!(
            catalog
                .page(0)
                .expect("catalog page")
                .as_str()
                .expect("UTF-8"),
            "desktop-tool\t1.0-1\t1\t3\t1\n\
development-kit\t1.0-1\t1\t4\t1\n\
system-data\t1.0-1\t1\t8\t1\n\
unknown-metadata\t1.0-1\t1\t0\t0\n",
        );

        fs::write(
            unknown.join("files"),
            b"%FILES%\n../../data/com.android.shell/escape\n",
        )
        .expect("unsafe package file list");
        assert!(matches!(
            runtime.installed_package_catalog(),
            Err(PackageRuntimeError::InvalidResolution)
        ));

        fs::write(
            unknown.join("files"),
            b"%FILES%\n../../data/com.android.shell/escape/\n",
        )
        .expect("unsafe package directory list");
        assert!(matches!(
            runtime.installed_package_catalog(),
            Err(PackageRuntimeError::InvalidResolution)
        ));
    }

    #[test]
    fn verified_archive_analysis_derives_target_class_and_matching_elf_abi() {
        let command = elf_header(62);
        let library = elf_header(62);
        let archive = package_tar(&[
            (
                "usr/share/applications/tool.desktop",
                0o644,
                b"[Desktop Entry]\nType=Application\nName=Tool\nExec=tool\n",
            ),
            ("usr/bin/tool", 0o755, &command),
            ("usr/lib/libtool.so.1", 0o644, &library),
            (
                "usr/lib/systemd/user/tool.service",
                0o644,
                b"[Service]\nExecStart=tool\n",
            ),
        ]);
        let analysis = inspect_package_tar(
            Cursor::new(archive),
            RepositoryArchitecture::X86_64,
            false,
            4096,
            true,
        )
        .expect("package compatibility");
        assert_eq!(
            analysis,
            PackageArchiveAnalysis {
                capabilities: PACKAGE_CAPABILITY_GRAPHICAL
                    | PACKAGE_CAPABILITY_COMMAND_LINE
                    | PACKAGE_CAPABILITY_LIBRARY
                    | PACKAGE_CAPABILITY_SYSTEM,
                elf_count: 2,
                command_count: 1,
                diagnostic: None,
            },
        );
    }

    #[test]
    fn verified_archive_analysis_classifies_terminal_desktop_entries_as_cli() {
        let archive = package_tar(&[
            (
                "usr/share/applications/btop.desktop",
                0o644,
                b"[Desktop Entry]\nType=Application\nName=btop++\nExec=btop\nTerminal=true\n",
            ),
            ("usr/bin/btop", 0o755, b"#!/bin/sh\n"),
        ]);
        let analysis = inspect_package_tar(
            Cursor::new(archive),
            RepositoryArchitecture::X86_64,
            false,
            4096,
            true,
        )
        .expect("terminal package compatibility");

        assert_eq!(analysis.capabilities, PACKAGE_CAPABILITY_COMMAND_LINE);
        assert_eq!(analysis.command_count, 1);
        assert_eq!(analysis.diagnostic, None);
    }

    #[test]
    fn verified_archive_analysis_checks_cancellation_during_stream_reads() {
        let archive = package_tar(&[("usr/bin/tool", 0o755, b"#!/bin/sh\n")]);
        let cancellation = PackageCompatibilityCancellation::new();
        let reader = CancelOnFirstRead {
            inner: Cursor::new(archive),
            cancellation: cancellation.clone(),
            first: true,
        };
        let result = inspect_package_tar_cancellable(
            CancellableReader::new(reader, &cancellation),
            RepositoryArchitecture::X86_64,
            false,
            4096,
            true,
            &cancellation,
        );
        assert!(matches!(result, Err(PackageRuntimeError::Cancelled)));
    }

    #[test]
    fn verified_archive_analysis_reports_only_explicit_runtime_blockers() {
        let foreign = elf_header(183);
        let cross_compiler_data = elf_header(183);
        let archive = package_tar(&[
            ("usr/bin/foreign", 0o755, &foreign),
            (
                "usr/share/cross/sysroot/foreign",
                0o644,
                &cross_compiler_data,
            ),
        ]);
        let analysis = inspect_package_tar(
            Cursor::new(archive),
            RepositoryArchitecture::X86_64,
            false,
            4096,
            true,
        )
        .expect("foreign package compatibility");
        assert_eq!(
            analysis.diagnostic,
            Some(PackageCompatibilityDiagnostic::ForeignElf),
        );
        assert_eq!(analysis.elf_count, 2);

        let data_archive = package_tar(&[(
            "usr/share/cross/sysroot/foreign",
            0o644,
            &cross_compiler_data,
        )]);
        let data_analysis = inspect_package_tar(
            Cursor::new(data_archive),
            RepositoryArchitecture::X86_64,
            false,
            4096,
            true,
        )
        .expect("cross compiler data");
        assert_eq!(data_analysis.diagnostic, None);

        let any_archive =
            package_tar(&[("usr/share/package/bundled-elf", 0o644, &cross_compiler_data)]);
        let any_analysis = inspect_package_tar(
            Cursor::new(any_archive),
            RepositoryArchitecture::X86_64,
            true,
            4096,
            true,
        )
        .expect("architecture-any package");
        assert_eq!(
            any_analysis.diagnostic,
            Some(PackageCompatibilityDiagnostic::NativeInAnyPackage),
        );

        let mut relocatable = elf_header(62);
        relocatable[16..18].copy_from_slice(&1_u16.to_le_bytes());
        let static_library = package_tar(&[("usr/lib/libmcheck.a", 0o644, &relocatable)]);
        let static_library_analysis = inspect_package_tar(
            Cursor::new(static_library),
            RepositoryArchitecture::X86_64,
            false,
            16 * 1024,
            true,
        )
        .expect("relocatable static-library metadata");
        assert_eq!(static_library_analysis.diagnostic, None);

        let relocatable_command = package_tar(&[("usr/bin/invalid", 0o755, &relocatable)]);
        let relocatable_command_analysis = inspect_package_tar(
            Cursor::new(relocatable_command),
            RepositoryArchitecture::X86_64,
            false,
            4096,
            true,
        )
        .expect("relocatable command");
        assert_eq!(
            relocatable_command_analysis.diagnostic,
            Some(PackageCompatibilityDiagnostic::MalformedElf),
        );
    }

    #[test]
    fn verified_archive_analysis_accepts_bridge_shebangs_and_rejects_unknown_commands() {
        let supported = package_tar(&[("usr/bin/script", 0o755, b"#!/usr/bin/bash\nexit 0\n")]);
        let supported_analysis = inspect_package_tar(
            Cursor::new(supported),
            RepositoryArchitecture::X86_64,
            false,
            4096,
            true,
        )
        .expect("supported script");
        assert_eq!(supported_analysis.diagnostic, None);
        assert_eq!(supported_analysis.command_count, 1);

        let unsupported = package_tar(&[("usr/bin/blob", 0o755, b"not an executable format")]);
        let unsupported_analysis = inspect_package_tar(
            Cursor::new(unsupported),
            RepositoryArchitecture::X86_64,
            false,
            4096,
            true,
        )
        .expect("unsupported command");
        assert_eq!(
            unsupported_analysis.diagnostic,
            Some(PackageCompatibilityDiagnostic::UnsupportedCommand),
        );

        let malformed = package_tar(&[("usr/bin/elf", 0o755, b"\x7fELF\x02\x01\x01")]);
        let malformed_analysis = inspect_package_tar(
            Cursor::new(malformed),
            RepositoryArchitecture::X86_64,
            false,
            4096,
            true,
        )
        .expect("malformed ELF");
        assert_eq!(
            malformed_analysis.diagnostic,
            Some(PackageCompatibilityDiagnostic::MalformedElf),
        );
    }

    #[test]
    fn verified_archive_analysis_rejects_ambiguous_or_escaping_paths() {
        assert!(validate_compatibility_archive_path(Path::new("./usr/bin/tool"), false).is_ok());
        assert!(validate_compatibility_archive_path(Path::new("usr/bin/"), true).is_ok());
        for path in [
            "/usr/bin/tool",
            "../usr/bin/tool",
            "usr/../bin/tool",
            "usr/./bin/tool",
            "usr//bin/tool",
        ] {
            assert!(matches!(
                validate_compatibility_archive_path(Path::new(path), false),
                Err(PackageRuntimeError::InvalidPayload)
            ));
        }
        assert!(
            validate_compatibility_archive_link(Path::new("/usr/lib/libtool.so"), false).is_ok()
        );
        assert!(matches!(
            validate_compatibility_archive_link(Path::new("../usr/lib/libtool.so"), true),
            Err(PackageRuntimeError::InvalidPayload)
        ));
    }

    #[test]
    fn verified_archive_analysis_enforces_the_actual_android_page_size() {
        let four_kib = elf_with_load_segment(62, 4096);
        let four_kib_archive = package_tar(&[("usr/bin/tool", 0o755, &four_kib)]);
        let incompatible = inspect_package_tar(
            Cursor::new(four_kib_archive),
            RepositoryArchitecture::X86_64,
            false,
            16 * 1024,
            true,
        )
        .expect("16 KiB compatibility");
        assert_eq!(
            incompatible.diagnostic,
            Some(PackageCompatibilityDiagnostic::IncompatiblePageSize),
        );

        let sixteen_kib = elf_with_load_segment(62, 16 * 1024);
        let sixteen_kib_archive = package_tar(&[("usr/bin/tool", 0o755, &sixteen_kib)]);
        let compatible = inspect_package_tar(
            Cursor::new(sixteen_kib_archive),
            RepositoryArchitecture::X86_64,
            false,
            16 * 1024,
            true,
        )
        .expect("aligned 16 KiB compatibility");
        assert_eq!(compatible.diagnostic, None);
    }

    #[test]
    fn verified_archive_analysis_streams_both_official_package_compressions() {
        let tree = TestTree::new();
        let elf = elf_with_load_segment(62, 4096);
        let tar = package_tar(&[("usr/bin/tool", 0o755, &elf)]);
        let zstd_bytes =
            zstd::stream::encode_all(Cursor::new(&tar), 1).expect("zstd package fixture");
        let zstd_path = tree.root.join("fixture-1.0-1-x86_64.pkg.tar.zst");
        fs::write(&zstd_path, zstd_bytes).expect("zstd package");
        let mut zstd_file = File::open(zstd_path).expect("open zstd package");
        assert_eq!(
            inspect_package_archive(
                &mut zstd_file,
                "fixture-1.0-1-x86_64.pkg.tar.zst",
                RepositoryArchitecture::X86_64,
                4096,
                true,
            )
            .expect("inspect zstd package")
            .command_count,
            1,
        );

        let mut xz_encoder = xz2::write::XzEncoder::new(Vec::new(), 1);
        xz_encoder.write_all(&tar).expect("xz package fixture");
        let xz_bytes = xz_encoder.finish().expect("finish xz package");
        let xz_path = tree.root.join("fixture-1.0-1-x86_64.pkg.tar.xz");
        fs::write(&xz_path, xz_bytes).expect("xz package");
        let mut xz_file = File::open(xz_path).expect("open xz package");
        assert_eq!(
            inspect_package_archive(
                &mut xz_file,
                "fixture-1.0-1-x86_64.pkg.tar.xz",
                RepositoryArchitecture::X86_64,
                4096,
                true,
            )
            .expect("inspect xz package")
            .command_count,
            1,
        );
    }

    #[test]
    fn compatibility_cache_is_content_addressed_canonical_and_corruption_safe() {
        let tree = TestTree::new();
        let runtime = tree.package_runtime();
        let digest = [0x5a; 32];
        let output = package_compatibility_output(
            PackageCompatibilityStatus::BridgeEligible,
            PACKAGE_CAPABILITY_COMMAND_LINE,
            3,
            12,
            1,
            PackageCompatibilityDiagnostic::None,
            None,
        )
        .expect("compatibility output");
        runtime
            .publish_package_compatibility_cache(&digest, &output)
            .expect("publish compatibility cache");
        assert_eq!(
            runtime
                .load_package_compatibility_cache(&digest)
                .expect("load compatibility cache")
                .expect("cached compatibility")
                .as_bytes(),
            output.as_bytes(),
        );

        let cache = tree
            .root
            .join(PACKAGE_COMPATIBILITY_CACHE_DIRECTORY)
            .join(hex_sha256(&digest));
        fs::write(&cache, b"bridge-eligible\t2\t3\t12\t1\tnone\t-\ninvalid\n")
            .expect("corrupt cache");
        assert!(
            runtime
                .load_package_compatibility_cache(&digest)
                .expect("discard corrupt cache")
                .is_none()
        );
        assert!(!cache.exists());

        let unsupported = package_compatibility_output(
            PackageCompatibilityStatus::Unsupported,
            0,
            2,
            1,
            0,
            PackageCompatibilityDiagnostic::ForeignElf,
            Some("foreign-runtime"),
        )
        .expect("unsupported output");
        runtime
            .publish_package_compatibility_cache(&digest, &unsupported)
            .expect("publish unsupported cache");
        let cached = runtime
            .load_package_compatibility_cache(&digest)
            .expect("load unsupported cache")
            .expect("unsupported cache");
        assert_eq!(cached.as_bytes(), unsupported.as_bytes());
        assert!(!cached_compatibility_allows_mutation(&cached).expect("cached status"));

        assert!(matches!(
            canonical_cached_compatibility(b"unsupported\t0\t2\t1\t0\tforeign-elf\t-\n"),
            Err(PackageRuntimeError::InvalidPayload)
        ));
    }

    #[test]
    fn compatibility_cache_digest_changes_with_every_review_input() {
        let tree = TestTree::new();
        let runtime = tree.package_runtime();
        let filename = "tool-1.0-1-x86_64.pkg.tar.zst";
        let package_path = tree.root.join(PACKAGE_CACHE_DIRECTORY).join(filename);
        let signature_path = tree
            .root
            .join(PACKAGE_CACHE_DIRECTORY)
            .join(format!("{filename}.sig"));
        fs::write(&package_path, b"package").expect("package payload");
        fs::write(&signature_path, b"signature").expect("package signature");
        let resolution = PackageResolution {
            bytes: format!(
                "core\ttool\t1.0-1\t{filename}\t\
https://geo.mirror.pkgbuild.com/core/os/x86_64/{filename}\t7\n"
            )
            .into_bytes(),
        };
        let original = runtime
            .package_compatibility_content_digest(
                &resolution,
                4096,
                &PackageCompatibilityCancellation::new(),
            )
            .expect("original digest");
        assert_eq!(
            original,
            runtime
                .package_compatibility_content_digest(
                    &resolution,
                    4096,
                    &PackageCompatibilityCancellation::new(),
                )
                .expect("stable digest"),
        );
        assert_ne!(
            original,
            runtime
                .package_compatibility_content_digest(
                    &resolution,
                    16 * 1024,
                    &PackageCompatibilityCancellation::new(),
                )
                .expect("page-size digest"),
        );
        let mut changed_trust = runtime.clone();
        changed_trust
            .verification_source_state
            .push_str("replacement\n");
        assert_ne!(
            original,
            changed_trust
                .package_compatibility_content_digest(
                    &resolution,
                    4096,
                    &PackageCompatibilityCancellation::new(),
                )
                .expect("verification-source digest"),
        );
        fs::write(&signature_path, b"Signature").expect("changed signature");
        assert_ne!(
            original,
            runtime
                .package_compatibility_content_digest(
                    &resolution,
                    4096,
                    &PackageCompatibilityCancellation::new(),
                )
                .expect("signature digest"),
        );
        fs::write(&signature_path, b"signature").expect("restore signature");
        fs::write(&package_path, b"Package").expect("changed package");
        assert_ne!(
            original,
            runtime
                .package_compatibility_content_digest(
                    &resolution,
                    4096,
                    &PackageCompatibilityCancellation::new(),
                )
                .expect("package digest"),
        );
    }

    #[test]
    fn compatibility_cache_prunes_deterministically_and_rejects_unknown_entries() {
        let tree = TestTree::new();
        let runtime = tree.package_runtime();
        let directory = tree.root.join(PACKAGE_COMPATIBILITY_CACHE_DIRECTORY);
        prepare_private_directory(&directory).expect("compatibility cache directory");
        for index in 0..PACKAGE_COMPATIBILITY_CACHE_ENTRY_LIMIT {
            fs::write(directory.join(format!("{index:064x}")), b"derived")
                .expect("compatibility cache fixture");
        }
        let digest = [0x5a; 32];
        let output = package_compatibility_output(
            PackageCompatibilityStatus::ManagedOnly,
            PACKAGE_CAPABILITY_LIBRARY,
            1,
            1,
            0,
            PackageCompatibilityDiagnostic::None,
            None,
        )
        .expect("compatibility output");
        runtime
            .publish_package_compatibility_cache(&digest, &output)
            .expect("bounded cache publication");
        assert_eq!(
            fs::read_dir(&directory)
                .expect("compatibility cache")
                .count(),
            PACKAGE_COMPATIBILITY_CACHE_ENTRY_LIMIT,
        );
        assert!(directory.join(hex_sha256(&digest)).is_file());
        assert!(!directory.join(format!("{:064x}", 0)).exists());

        fs::write(directory.join("unknown"), b"hostile").expect("unknown cache entry");
        assert!(matches!(
            runtime.publish_package_compatibility_cache(&[0x6b; 32], &output),
            Err(PackageRuntimeError::UnsafeEntry(_))
        ));
    }

    #[test]
    fn package_resolution_output_is_strict_and_contains_target() {
        let input = "core\tglibc\t2.42+r33+gde5fe48316ed-1\tglibc-2.42+r33+gde5fe48316ed-1-x86_64.pkg.tar.zst\thttps://geo.mirror.pkgbuild.com/core/os/x86_64/glibc-2.42+r33+gde5fe48316ed-1-x86_64.pkg.tar.zst\t10158024\n\
extra\tdotnet-sdk\t10.0.10.sdk110-1\tdotnet-sdk-10.0.10.sdk110-1-x86_64.pkg.tar.zst\thttps://geo.mirror.pkgbuild.com/extra/os/x86_64/dotnet-sdk-10.0.10.sdk110-1-x86_64.pkg.tar.zst\t123456789\n";
        let parsed =
            parse_resolution_output(input, &["dotnet-sdk"], RepositoryArchitecture::X86_64)
                .expect("valid resolution");
        assert_eq!(parsed.as_str().expect("utf-8"), input);
        parse_resolution_output(
            input,
            &["glibc", "dotnet-sdk"],
            RepositoryArchitecture::X86_64,
        )
        .expect("multi-target resolution");

        assert!(matches!(
            parse_resolution_output(input, &["btop"], RepositoryArchitecture::X86_64,),
            Err(PackageRuntimeError::MissingTarget)
        ));
        assert!(matches!(
            parse_resolution_output(
                "extra\tbtop\t1.4.4-1\tbtop-1.4.4-1-aarch64.pkg.tar.xz\thttps://example.com/btop-1.4.4-1-aarch64.pkg.tar.xz\t123456\n",
                &["btop"],
                RepositoryArchitecture::Aarch64,
            ),
            Err(PackageRuntimeError::InvalidResolution)
        ));
    }

    #[test]
    fn repository_partition_uses_one_resolution_when_every_target_is_official() {
        let mut calls = 0;
        let partition = partition_repository_targets(&["base-devel", "glibc"], |targets| {
            calls += 1;
            assert_eq!(targets, ["base-devel", "glibc"]);
            Ok(test_resolution("complete"))
        })
        .expect("official partition");
        assert_eq!(calls, 1);
        assert_eq!(partition.official_targets(), ["base-devel", "glibc"]);
        assert!(partition.unresolved_targets().is_empty());
        assert_eq!(
            partition.resolution().expect("official resolution"),
            &test_resolution("complete")
        );
    }

    #[test]
    fn repository_partition_bisects_only_exact_missing_targets() {
        let official = ["base-devel", "glibc"];
        let partition = partition_repository_targets(
            &["base-devel", "aur-runtime", "glibc", "aur-build-tool"],
            |targets| {
                if let Some(missing) = targets.iter().find(|target| !official.contains(target)) {
                    Err(missing_repository_target(missing))
                } else {
                    Ok(test_resolution(&targets.join(",")))
                }
            },
        )
        .expect("mixed repository partition");
        assert_eq!(partition.official_targets(), ["base-devel", "glibc"]);
        assert_eq!(
            partition.unresolved_targets(),
            ["aur-runtime", "aur-build-tool"]
        );
        assert_eq!(
            partition
                .resolution()
                .expect("combined official resolution"),
            &test_resolution("base-devel,glibc")
        );
    }

    #[test]
    fn repository_partition_does_not_reclassify_other_pacman_failures() {
        assert!(matches!(
            partition_repository_targets(&["aur-runtime"], |_| {
                Err(test_tool_failure(
                    1,
                    b"error: target not found: aur-runtime\nwarning: changed catalog\n",
                ))
            }),
            Err(PackageRuntimeError::ToolFailed(1, _))
        ));

        assert!(matches!(
            partition_repository_targets(&["aur-runtime"], |_| {
                Err(test_tool_failure(
                    2,
                    b"error: target not found: aur-runtime\n",
                ))
            }),
            Err(PackageRuntimeError::ToolFailed(2, _))
        ));
    }

    #[test]
    fn repository_partition_rechecks_the_combined_official_set() {
        let mut calls = 0;
        let result =
            partition_repository_targets(&["official-a", "aur-tool", "official-b"], |targets| {
                calls += 1;
                if targets.contains(&"aur-tool") {
                    return Err(missing_repository_target("aur-tool"));
                }
                if targets.len() == 2 && targets.contains(&"official-a") {
                    let mut output = empty_tool_output();
                    output
                        .push(b"error: unresolvable package conflicts detected\n")
                        .expect("conflict");
                    return Err(PackageRuntimeError::ToolFailed(1, Box::new(output)));
                }
                Ok(test_resolution("subset"))
            });
        assert!(calls > 1);
        assert!(matches!(
            result,
            Err(PackageRuntimeError::ToolFailed(1, output))
                if output.as_bytes() == b"error: unresolvable package conflicts detected\n"
        ));
    }

    #[test]
    fn fresh_build_resolution_database_copies_catalogs_without_local_state() {
        let tree = TestTree::new();
        let runtime = tree.package_runtime();
        fs::write(
            tree.root.join(CATALOG_DIRECTORY).join("core.db"),
            b"core catalog",
        )
        .expect("core catalog");
        fs::write(
            tree.root.join(CATALOG_DIRECTORY).join("extra.db"),
            b"extra catalog",
        )
        .expect("extra catalog");
        let database = runtime
            .prepare_fresh_resolution_database()
            .expect("fresh resolution database");
        assert_eq!(
            fs::read(database.join("sync/core.db")).expect("copied core catalog"),
            b"core catalog"
        );
        assert_eq!(
            fs::read(database.join("sync/extra.db")).expect("copied extra catalog"),
            b"extra catalog"
        );
        assert!(!database.join("local").exists());
        fs::remove_dir_all(database).expect("fresh database cleanup");
    }

    #[test]
    fn compatibility_review_capability_is_memory_only_exact_and_single_use() {
        let tree = TestTree::new();
        let runtime = tree.package_runtime();
        let resolution = PackageResolution {
            bytes: b"extra\ttool\t1.0-1\ttool-1.0-1-x86_64.pkg.tar.zst\thttps://geo.mirror.pkgbuild.com/extra/os/x86_64/tool-1.0-1-x86_64.pkg.tar.zst\t1024\n".to_vec(),
        };
        runtime
            .publish_package_compatibility_review("tool", &resolution)
            .expect("publish review");
        runtime
            .clone()
            .consume_package_compatibility_review("tool", &resolution)
            .expect("consume exact review through runtime clone");
        assert!(matches!(
            runtime.consume_package_compatibility_review("tool", &resolution),
            Err(PackageRuntimeError::CompatibilityReviewRequired)
        ));

        runtime
            .publish_package_compatibility_review("tool", &resolution)
            .expect("republish review");
        let changed = PackageResolution {
            bytes: b"extra\ttool\t1.0-2\ttool-1.0-2-x86_64.pkg.tar.zst\thttps://geo.mirror.pkgbuild.com/extra/os/x86_64/tool-1.0-2-x86_64.pkg.tar.zst\t1024\n".to_vec(),
        };
        assert!(matches!(
            runtime.consume_package_compatibility_review("tool", &changed),
            Err(PackageRuntimeError::CompatibilityReviewRequired)
        ));
    }

    #[test]
    fn package_resolution_supports_large_bounded_closures() {
        let mut input = String::new();
        for index in 0..200 {
            let name = if index == 199 {
                "code".to_owned()
            } else {
                format!("dependency-{index:03}")
            };
            let filename = format!("{name}-1.0.{index}-1-x86_64.pkg.tar.zst");
            input.push_str(&format!(
                "extra\t{name}\t1.0.{index}-1\t{filename}\t\
https://geo.mirror.pkgbuild.com/extra/os/x86_64/{filename}\t{}\n",
                1024 + index,
            ));
        }
        assert!(input.len() > MAX_TOOL_OUTPUT_BYTES);
        let parsed = parse_resolution_output(&input, &["code"], RepositoryArchitecture::X86_64)
            .expect("large bounded resolution");
        assert_eq!(parsed.as_str().expect("UTF-8"), input);
        assert!(parsed.as_bytes().len() < MAX_PACKAGE_RESOLUTION_BYTES);
    }

    #[test]
    fn install_preflight_accepts_only_resolved_name_version_pairs() {
        let archives = [
            InstallArchive {
                path: "/cache/glibc.pkg.tar.zst".to_owned(),
                name: "glibc".to_owned(),
                version: "2.42-1".to_owned(),
                explicitly_installed: false,
            },
            InstallArchive {
                path: "/cache/btop.pkg.tar.zst".to_owned(),
                name: "btop".to_owned(),
                version: "1.4.7-1".to_owned(),
                explicitly_installed: true,
            },
        ];
        assert!(validate_install_plan("glibc\t2.42-1\nbtop\t1.4.7-1\n", &archives).is_ok());
        assert!(
            validate_install_plan(
                "warning: btop-1.4.7-1 is up to date -- reinstalling\n\
                 glibc\t2.42-1\nbtop\t1.4.7-1\n",
                &archives,
            )
            .is_ok()
        );
        assert!(matches!(
            validate_install_plan("", &archives),
            Err(PackageRuntimeError::InvalidResolution)
        ));
        assert!(matches!(
            validate_install_plan("btop\t1.4.7-1\n", &archives),
            Err(PackageRuntimeError::InvalidResolution)
        ));
        assert!(matches!(
            validate_install_plan("other\t1.0-1\n", &archives),
            Err(PackageRuntimeError::InvalidResolution)
        ));
        assert!(matches!(
            validate_install_plan("btop\t1.4.6-1\n", &archives),
            Err(PackageRuntimeError::InvalidResolution)
        ));
        assert!(matches!(
            validate_install_plan("btop\t1.4.7-1\nbtop\t1.4.7-1\n", &archives),
            Err(PackageRuntimeError::InvalidResolution)
        ));
        assert!(matches!(
            validate_install_plan("btop 1.4.7-1\n", &archives),
            Err(PackageRuntimeError::InvalidResolution)
        ));
    }

    #[test]
    fn package_info_size_is_exact_bounded_and_unique() {
        assert_eq!(
            parse_package_info_size(
                "pkgname = dotnet-sdk\npkgver = 10.0.10.sdk110-1\nsize = 506314752\n",
            )
            .expect("installed size"),
            506_314_752,
        );
        assert!(matches!(
            parse_package_info_size("pkgname = dotnet-sdk\n"),
            Err(PackageRuntimeError::InvalidPayload),
        ));
        assert!(matches!(
            parse_package_info_size("size = 1\nsize = 2\n"),
            Err(PackageRuntimeError::InvalidPayload),
        ));
        assert_eq!(
            parse_package_info_size("pkgname = base\nsize = 0\n").expect("metadata-only package"),
            0,
        );
    }

    #[test]
    fn install_reason_intents_are_bounded_and_exact() {
        assert_eq!(
            parse_install_reason_intent("org.archphene.package-install-reasons.v1\nbtop\nbash\n",)
                .expect("valid install-reason intent"),
            ["btop", "bash"],
        );
        for invalid in [
            "",
            "org.archphene.package-install-reasons.v1\n",
            "org.archphene.package-install-reasons.v1\nbtop",
            "org.archphene.package-install-reasons.v1\nbtop\nbtop\n",
            "org.archphene.package-install-reasons.v1\n../btop\n",
            "different\nbtop\n",
        ] {
            assert!(matches!(
                parse_install_reason_intent(invalid),
                Err(PackageRuntimeError::InvalidResolution)
            ));
        }
    }

    #[test]
    fn update_reason_preparation_preserves_explicit_and_dependency_packages() {
        let tree = TestTree::new();
        let runtime = tree.package_runtime();
        tree.local_package("explicit-tool-1.0-1", "explicit-tool", b"%FILES%\n");
        tree.local_dependency_package("dependency-tool-1.0-1", "dependency-tool", b"%FILES%\n");
        let mut archives = [
            InstallArchive {
                path: "/cache/base.pkg.tar.zst".to_owned(),
                name: BASE_PACKAGE.to_owned(),
                version: "3-2".to_owned(),
                explicitly_installed: true,
            },
            InstallArchive {
                path: "/cache/explicit-tool.pkg.tar.zst".to_owned(),
                name: "explicit-tool".to_owned(),
                version: "2.0-1".to_owned(),
                explicitly_installed: false,
            },
            InstallArchive {
                path: "/cache/dependency-tool.pkg.tar.zst".to_owned(),
                name: "dependency-tool".to_owned(),
                version: "2.0-1".to_owned(),
                explicitly_installed: false,
            },
            InstallArchive {
                path: "/cache/new-dependency.pkg.tar.zst".to_owned(),
                name: "new-dependency".to_owned(),
                version: "1.0-1".to_owned(),
                explicitly_installed: false,
            },
        ];

        runtime
            .preserve_explicit_install_reasons(&mut archives)
            .expect("preserve existing reasons");

        assert!(archives[0].explicitly_installed);
        assert!(archives[1].explicitly_installed);
        assert!(!archives[2].explicitly_installed);
        assert!(!archives[3].explicitly_installed);
    }

    #[test]
    fn repair_reinstalls_retained_archives_even_when_the_database_is_current() {
        let mut normal = vec!["--noscriptlet"];
        append_install_transaction_mode(&mut normal, InstallResolutionMode::Normal);
        assert_eq!(normal, ["--noscriptlet", "--needed", "--asdeps", "-U"],);

        let mut repair = vec!["--noscriptlet"];
        append_install_transaction_mode(&mut repair, InstallResolutionMode::Repair);
        assert_eq!(repair, ["--noscriptlet", "--asdeps", "-U"]);
    }

    #[test]
    fn repair_quarantines_only_the_exact_corrupt_local_database_entry() {
        let tree = TestTree::new();
        let runtime = tree.package_runtime();
        tree.local_package("foot-1.0-1", "foot", b"%FILES%\nusr/bin/foot\n");
        let local_entry = tree.root.join("var/lib/pacman/local/foot-1.0-1");
        fs::remove_file(local_entry.join("desc")).expect("damage local description");
        let archives = vec![InstallArchive {
            path: tree
                .root
                .join("var/cache/pacman/pkg/foot-1.0-1-x86_64.pkg.tar.zst")
                .to_string_lossy()
                .into_owned(),
            name: "foot".to_owned(),
            version: "1.0-1".to_owned(),
            explicitly_installed: true,
        }];

        runtime
            .prepare_database_repair(&archives)
            .expect("quarantine damaged entry");
        let retained = tree
            .root
            .join(PACKAGE_DATABASE_REPAIR_DIRECTORY)
            .join("foot-1.0-1");
        assert!(!local_entry.exists());
        assert!(retained.join("files").is_file());

        fs::create_dir(&local_entry).expect("second partial entry");
        fs::write(local_entry.join("desc"), b"").expect("empty second description");
        runtime
            .prepare_database_repair(&archives)
            .expect("discard bounded second partial entry");
        assert!(!local_entry.exists());
        assert!(retained.is_dir());

        tree.local_package("foot-1.0-1", "foot", b"%FILES%\nusr/bin/foot\n");
        runtime
            .prepare_database_repair(&archives)
            .expect("retain complete replacement");
        assert!(local_entry.is_dir());
        runtime
            .clear_database_repair(&archives)
            .expect("clear retained damaged entry");
        assert!(local_entry.is_dir());
        assert!(!retained.exists());
    }

    #[test]
    fn removal_repair_restores_the_exact_bounded_local_database_record() {
        let tree = TestTree::new();
        let runtime = tree.package_runtime();
        tree.local_package("foot-1.0-1", "foot", b"%FILES%\nusr/bin/foot\n");
        let local = tree.root.join("var/lib/pacman/local/foot-1.0-1");
        fs::write(local.join("mtree"), b"#mtree\n").expect("mtree");
        let digest = runtime
            .prepare_removal_repair("foot", "1.0-1")
            .expect("prepare removal repair");
        assert!(valid_sha256_hex(&digest));
        let snapshot = tree.root.join(PACKAGE_REMOVAL_REPAIR_DIRECTORY);
        assert_eq!(
            fs::metadata(&snapshot)
                .expect("snapshot metadata")
                .permissions()
                .mode()
                & 0o777,
            0o700,
        );
        assert_eq!(
            fs::metadata(snapshot.join("desc"))
                .expect("description metadata")
                .permissions()
                .mode()
                & 0o777,
            0o600,
        );

        fs::remove_file(local.join("desc")).expect("damage local record");
        runtime
            .restore_removal_repair("foot", "1.0-1", &digest)
            .expect("restore local record");
        assert!(
            local_database_entry_matches(&local, "foot", "1.0-1")
                .expect("validate restored record")
        );
        assert_eq!(
            fs::read(local.join("files")).expect("restored file list"),
            b"%FILES%\nusr/bin/foot\n",
        );
        assert_eq!(
            fs::read(local.join("mtree")).expect("restored mtree"),
            b"#mtree\n",
        );

        runtime
            .clear_removal_repair()
            .expect("clear removal repair");
        assert!(!snapshot.exists());
    }

    #[test]
    fn removal_repair_rejects_tampering_and_cleans_only_bounded_orphans() {
        let tree = TestTree::new();
        let runtime = tree.package_runtime();
        tree.local_package("foot-1.0-1", "foot", b"%FILES%\nusr/bin/foot\n");
        let digest = runtime
            .prepare_removal_repair("foot", "1.0-1")
            .expect("prepare removal repair");
        let snapshot = tree.root.join(PACKAGE_REMOVAL_REPAIR_DIRECTORY);
        fs::write(snapshot.join("files"), b"%FILES%\nusr/bin/other\n").expect("tamper snapshot");
        assert!(matches!(
            runtime.restore_removal_repair("foot", "1.0-1", &digest),
            Err(PackageRuntimeError::InvalidResolution)
        ));

        fs::write(snapshot.join("files"), b"%FILES%\nusr/bin/foot\n")
            .expect("restore snapshot fixture");
        let cleanup = tree.root.join(PACKAGE_REMOVAL_REPAIR_TEMP_DIRECTORY);
        fs::rename(&snapshot, &cleanup).expect("publish cleanup generation");
        fs::remove_file(cleanup.join("desc")).expect("interrupt cleanup");
        runtime
            .clear_orphaned_removal_repair()
            .expect("clear bounded interrupted cleanup");
        assert!(!snapshot.exists());
        assert!(!cleanup.exists());

        fs::create_dir(&snapshot).expect("unsafe snapshot");
        fs::write(snapshot.join("unexpected"), b"do not remove").expect("unsafe content");
        assert!(matches!(
            runtime.clear_orphaned_removal_repair(),
            Err(PackageRuntimeError::UnsafeEntry(path)) if path == snapshot.join("unexpected")
        ));
        assert_eq!(
            fs::read(snapshot.join("unexpected")).expect("unsafe content retained"),
            b"do not remove",
        );
    }

    #[test]
    fn repair_preserves_explicit_reasons_from_the_original_intent() {
        let tree = TestTree::new();
        let runtime = tree.package_runtime();
        let intent = tree.root.join(INSTALL_REASON_INTENT_FILE);
        fs::write(&intent, format!("{INSTALL_REASON_INTENT_HEADER}\nfoot\n"))
            .expect("reason intent");
        fs::set_permissions(&intent, fs::Permissions::from_mode(0o600))
            .expect("reason intent mode");
        let mut archives = vec![InstallArchive {
            path: "foot.pkg.tar.zst".to_owned(),
            name: "foot".to_owned(),
            version: "1.0-1".to_owned(),
            explicitly_installed: false,
        }];
        runtime
            .preserve_pending_install_reasons(&mut archives)
            .expect("preserve retained reason");
        assert!(archives[0].explicitly_installed);
    }

    #[test]
    fn package_mutation_intents_are_exact_bounded_and_round_trip() {
        let resolution = parse_resolution_output(
            "core\tbase\t3-2\tbase-3-2-any.pkg.tar.zst\t\
https://geo.mirror.pkgbuild.com/core/os/x86_64/base-3-2-any.pkg.tar.zst\t1024\n\
extra\tbtop\t1.4.7-1\tbtop-1.4.7-1-x86_64.pkg.tar.zst\t\
https://geo.mirror.pkgbuild.com/extra/os/x86_64/btop-1.4.7-1-x86_64.pkg.tar.zst\t2048\n",
            &["base", "btop"],
            RepositoryArchitecture::X86_64,
        )
        .expect("resolution");
        let install = PackageMutationIntent::Install {
            request: "btop".to_owned(),
            explicit_targets: vec!["base".to_owned(), "btop".to_owned()],
            resolution,
        };
        let encoded = serialize_package_mutation_intent(&install, RepositoryArchitecture::X86_64)
            .expect("serialize install");
        assert_eq!(
            parse_package_mutation_intent(&encoded, RepositoryArchitecture::X86_64)
                .expect("parse install"),
            install,
        );

        let removal = PackageMutationIntent::Remove {
            package: "btop".to_owned(),
            version: "1.4.7-1".to_owned(),
            database_sha256: Some("a".repeat(64)),
        };
        let encoded = serialize_package_mutation_intent(&removal, RepositoryArchitecture::X86_64)
            .expect("serialize removal");
        assert_eq!(
            parse_package_mutation_intent(&encoded, RepositoryArchitecture::X86_64)
                .expect("parse removal"),
            removal,
        );
        assert_eq!(
            parse_package_mutation_intent(
                "org.archphene.package-mutation.v1\nremove\tbtop\t1.4.7-1\n",
                RepositoryArchitecture::X86_64,
            )
            .expect("parse legacy removal"),
            PackageMutationIntent::Remove {
                package: "btop".to_owned(),
                version: "1.4.7-1".to_owned(),
                database_sha256: None,
            },
        );

        for invalid in [
            "",
            "org.archphene.package-mutation.v1\nremove\tbtop\t1.4.7-1",
            "org.archphene.package-mutation.v1\nremove\t../btop\t1.4.7-1\n",
            "org.archphene.package-mutation.v1\nremove\tbtop\t1.4.7-1\txyz\n",
            "org.archphene.package-mutation.v1\ninstall\tbtop\narchive\tbad\n",
            "org.archphene.package-mutation.v1\nremove\tbtop\t1.4.7-1\nextra\n",
        ] {
            assert!(matches!(
                parse_package_mutation_intent(invalid, RepositoryArchitecture::X86_64),
                Err(PackageRuntimeError::InvalidResolution)
            ));
        }
    }

    #[test]
    fn package_payload_downloads_are_exact_and_atomic() {
        let tree = TestTree::new();
        tree.file("libarchphene_pkg_111111111111111111111111.so", b"loader");
        tree.file("libarchphene_pkg_222222222222222222222222.so", b"pacman");
        tree.file("libarchphene_pkg_444444444444444444444444.so", b"keyring");
        tree.file("libarchphene_pkg_555555555555555555555555.so", b"bridge");
        tree.file("libarchphene_pkg_666666666666666666666666.so", b"trust");
        let manifest = b"# org.archphene.package-runtime.v1\n\
loader\t@loader\tlibarchphene_pkg_111111111111111111111111.so\t6\n\
tool\t@pacman\tlibarchphene_pkg_222222222222222222222222.so\t6\n\
keyring\t@keyring\tlibarchphene_pkg_444444444444444444444444.so\t7\n\
ownertrust\t@ownertrust\tlibarchphene_pkg_666666666666666666666666.so\t5\n\
library\tlibarchphene_path_bridge.so\tlibarchphene_pkg_555555555555555555555555.so\t6\n";
        let runtime = PackageRuntime::prepare(
            &tree.root,
            &tree.native,
            manifest,
            RepositoryArchitecture::X86_64,
        )
        .expect("package runtime");
        let filename = "btop-1.4.7-1-x86_64.pkg.tar.zst";
        let download = runtime
            .begin_package_download(filename, 7, false)
            .expect("package download");
        let mut writer = download.duplicate_file().expect("package descriptor");
        writer.write_all(b"package").expect("package bytes");
        drop(writer);
        assert_eq!(download.finish().expect("publish package"), 7);
        assert_eq!(
            fs::read(tree.root.join(PACKAGE_CACHE_DIRECTORY).join(filename))
                .expect("published package"),
            b"package"
        );

        let short = runtime
            .begin_package_download(filename, 8, false)
            .expect("short package");
        let mut writer = short.duplicate_file().expect("short descriptor");
        writer.write_all(b"short").expect("short bytes");
        drop(writer);
        assert!(matches!(
            short.finish(),
            Err(PackageRuntimeError::InvalidPayload)
        ));
        assert!(
            !tree
                .root
                .join(PACKAGE_CACHE_DIRECTORY)
                .join(format!("{filename}.part"))
                .exists()
        );
    }

    #[test]
    fn package_cache_cleanup_is_bounded_fail_closed_and_download_only() {
        let tree = TestTree::new();
        tree.file("libarchphene_pkg_111111111111111111111111.so", b"loader");
        tree.file("libarchphene_pkg_222222222222222222222222.so", b"pacman");
        tree.file("libarchphene_pkg_444444444444444444444444.so", b"keyring");
        tree.file("libarchphene_pkg_555555555555555555555555.so", b"bridge");
        tree.file("libarchphene_pkg_666666666666666666666666.so", b"trust");
        let manifest = b"# org.archphene.package-runtime.v1\n\
loader\t@loader\tlibarchphene_pkg_111111111111111111111111.so\t6\n\
tool\t@pacman\tlibarchphene_pkg_222222222222222222222222.so\t6\n\
keyring\t@keyring\tlibarchphene_pkg_444444444444444444444444.so\t7\n\
ownertrust\t@ownertrust\tlibarchphene_pkg_666666666666666666666666.so\t5\n\
library\tlibarchphene_path_bridge.so\tlibarchphene_pkg_555555555555555555555555.so\t6\n";
        let runtime = PackageRuntime::prepare(
            &tree.root,
            &tree.native,
            manifest,
            RepositoryArchitecture::X86_64,
        )
        .expect("package runtime");
        let cache = tree.root.join(PACKAGE_CACHE_DIRECTORY);
        let archive = cache.join("btop-1.4.7-1-x86_64.pkg.tar.zst");
        let signature = cache.join("btop-1.4.7-1-x86_64.pkg.tar.zst.sig");
        let partial = cache.join("glibc-2.42-1-x86_64.pkg.tar.zst.part");
        fs::write(&archive, b"archive").expect("archive fixture");
        fs::write(&signature, b"signature").expect("signature fixture");
        fs::write(&partial, b"partial").expect("partial fixture");
        let unexpected = cache.join("do-not-delete");
        fs::write(&unexpected, b"owned data").expect("unexpected fixture");

        assert!(matches!(
            runtime.clear_package_cache(),
            Err(PackageRuntimeError::UnsafeEntry(path)) if path == unexpected
        ));
        assert!(archive.exists());
        assert!(signature.exists());
        assert!(partial.exists());
        assert_eq!(
            fs::read(&unexpected).expect("unexpected data retained"),
            b"owned data"
        );

        fs::remove_file(unexpected).expect("remove unexpected fixture");
        assert_eq!(
            runtime.clear_package_cache().expect("clear package cache"),
            23
        );
        assert!(
            fs::read_dir(&cache)
                .expect("empty cache directory")
                .next()
                .is_none()
        );
        assert_eq!(runtime.clear_package_cache().expect("clear empty cache"), 0);
    }

    #[test]
    fn aur_build_cache_cleanup_is_separate_and_fails_closed() {
        let tree = TestTree::new();
        tree.file("libarchphene_pkg_111111111111111111111111.so", b"loader");
        tree.file("libarchphene_pkg_222222222222222222222222.so", b"pacman");
        tree.file("libarchphene_pkg_444444444444444444444444.so", b"keyring");
        tree.file("libarchphene_pkg_555555555555555555555555.so", b"bridge");
        tree.file("libarchphene_pkg_666666666666666666666666.so", b"trust");
        let manifest = b"# org.archphene.package-runtime.v1\n\
loader\t@loader\tlibarchphene_pkg_111111111111111111111111.so\t6\n\
tool\t@pacman\tlibarchphene_pkg_222222222222222222222222.so\t6\n\
keyring\t@keyring\tlibarchphene_pkg_444444444444444444444444.so\t7\n\
ownertrust\t@ownertrust\tlibarchphene_pkg_666666666666666666666666.so\t5\n\
library\tlibarchphene_path_bridge.so\tlibarchphene_pkg_555555555555555555555555.so\t6\n";
        let runtime = PackageRuntime::prepare(
            &tree.root,
            &tree.native,
            manifest,
            RepositoryArchitecture::X86_64,
        )
        .expect("package runtime");
        let downloads = tree.root.join(PACKAGE_CACHE_DIRECTORY);
        let download = downloads.join("btop-1.4.7-1-x86_64.pkg.tar.zst");
        fs::write(&download, b"download").expect("package download");
        let package_cache = tree.root.join(AUR_PACKAGE_CACHE_DIRECTORY);
        let snapshot_cache = tree.root.join(aur::AUR_SNAPSHOT_CACHE_DIRECTORY);
        let source_cache = tree.root.join(aur::AUR_SOURCE_CACHE_DIRECTORY);
        for directory in [&package_cache, &snapshot_cache, &source_cache] {
            fs::create_dir_all(directory).expect("AUR cache");
        }
        fs::write(package_cache.join("built.pkg.tar.zst"), b"built").expect("built package");
        fs::write(snapshot_cache.join("snapshot.tar.gz"), b"snapshot").expect("snapshot");
        fs::write(source_cache.join("source"), b"source").expect("source");
        let unsafe_entry = source_cache.join("directory");
        fs::create_dir(&unsafe_entry).expect("unsafe cache directory");

        assert!(matches!(
            runtime.clear_aur_build_cache(),
            Err(PackageRuntimeError::UnsafeEntry(path)) if path == unsafe_entry
        ));
        assert!(package_cache.join("built.pkg.tar.zst").exists());
        assert!(snapshot_cache.join("snapshot.tar.gz").exists());
        fs::remove_dir(&unsafe_entry).expect("remove unsafe entry");

        assert_eq!(
            runtime.clear_aur_build_cache().expect("clear AUR cache"),
            19,
        );
        assert!(download.exists());
        for directory in [&package_cache, &snapshot_cache, &source_cache] {
            assert!(
                fs::read_dir(directory)
                    .expect("empty AUR cache")
                    .next()
                    .is_none()
            );
        }
    }

    #[test]
    fn aur_built_capability_survives_runtime_recreation_and_rejects_tampering() {
        let tree = TestTree::new();
        let runtime = tree.package_runtime();
        let archive = b"independently verified AUR package";
        let source_path = tree.root.join("verified-aur-output");
        fs::write(&source_path, archive).expect("verified output");
        let digest = <[u8; 32]>::from(Sha256::digest(archive));
        let review_digest = [7_u8; 32];
        let closure_digest = [8_u8; 32];
        let required = vec!["example-bin".to_owned()];
        let mut source = File::open(&source_path).expect("open verified output");
        let mut outputs = [VerifiedAurCapabilityArchive {
            source: &mut source,
            filename: "example-bin-1.2.3-1-x86_64.pkg.tar.zst",
            package: "example-bin",
            archive_bytes: archive.len() as u64,
            installed_bytes: 4096,
            build_package_count: 12,
            sha256: digest,
        }];
        let persisted = runtime
            .persist_aur_built_capability(
                "example-bin",
                "example-bin",
                "1.2.3-1",
                "x86_64",
                review_digest,
                closure_digest,
                &required,
                &mut outputs,
            )
            .expect("persist capability");
        assert_eq!(persisted.len(), 1);
        assert_eq!(
            fs::read(&persisted[0].path).expect("persisted archive"),
            archive,
        );
        drop(runtime);

        let restored_runtime = tree.package_runtime();
        assert!(
            restored_runtime
                .restore_aur_built_capability(
                    "example-bin",
                    "example-bin",
                    "1.2.3-1",
                    "x86_64",
                    [9_u8; 32],
                    closure_digest,
                    &required,
                )
                .expect("mismatched review")
                .is_none(),
        );
        let restored = restored_runtime
            .restore_aur_built_capability(
                "example-bin",
                "example-bin",
                "1.2.3-1",
                "x86_64",
                review_digest,
                closure_digest,
                &required,
            )
            .expect("restore capability")
            .expect("matching capability");
        assert_eq!(restored, persisted);

        fs::write(&restored[0].path, b"tampered output").expect("tamper output");
        assert!(matches!(
            restored_runtime.restore_aur_built_capability(
                "example-bin",
                "example-bin",
                "1.2.3-1",
                "x86_64",
                review_digest,
                closure_digest,
                &required,
            ),
            Err(PackageRuntimeError::UnsafeEntry(_)) | Err(PackageRuntimeError::InvalidPayload)
        ));
        restored_runtime
            .clear_aur_built_capability()
            .expect("clear capability");
        assert!(
            restored_runtime
                .restore_aur_built_capability(
                    "example-bin",
                    "example-bin",
                    "1.2.3-1",
                    "x86_64",
                    review_digest,
                    closure_digest,
                    &required,
                )
                .expect("cleared capability")
                .is_none(),
        );
    }

    #[test]
    fn aur_lifecycle_capability_is_private_exact_and_reconciled() {
        let tree = TestTree::new();
        tree.local_package(
            "aur-tool-1.0-1",
            "aur-tool",
            b"%FILES%\nusr/bin/aur-tool\n\n",
        );
        let local = tree.root.join("var/lib/pacman/local/aur-tool-1.0-1");
        fs::write(
            local.join("desc"),
            b"%NAME%\naur-tool\n\n%VERSION%\n1.0-1\n\n%VALIDATION%\nnone\n",
        )
        .expect("AUR local description");
        let script = b"post_remove() { true; }\n";
        fs::write(local.join("install"), script).expect("installed lifecycle script");
        let runtime = tree.package_runtime();
        let capability = AurLifecycleCapability {
            package: "aur-tool".to_owned(),
            version: "1.0-1".to_owned(),
            archive_sha256: [7_u8; 32],
            install_script_sha256: Some(Sha256::digest(script).into()),
        };
        runtime
            .publish_aur_lifecycle_capabilities(std::slice::from_ref(&capability))
            .expect("publish lifecycle capability");
        let state_root = aur_lifecycle_state_root(&tree.root).expect("manager state");
        assert!(!state_root.starts_with(&tree.root));
        let state = state_root.join(AUR_LIFECYCLE_CAPABILITY_FILE);
        assert_eq!(
            fs::metadata(&state)
                .expect("capability metadata")
                .permissions()
                .mode()
                & 0o777,
            0o600,
        );
        assert_eq!(
            read_aur_lifecycle_capabilities(&state_root).expect("read capability"),
            vec![capability],
        );
        assert!(
            runtime
                .aur_removal_scriptlets_authorized("aur-tool", "1.0-1")
                .expect("authorized exact script"),
        );
        fs::set_permissions(&state, fs::Permissions::from_mode(0o644))
            .expect("weaken capability mode");
        assert!(matches!(
            runtime.aur_removal_scriptlets_authorized("aur-tool", "1.0-1"),
            Err(PackageRuntimeError::UnsafeEntry(path)) if path == state,
        ));
        fs::set_permissions(&state, fs::Permissions::from_mode(0o600))
            .expect("restore capability mode");

        fs::write(local.join("install"), b"post_remove() { false; }\n")
            .expect("tampered lifecycle script");
        assert!(
            !runtime
                .aur_removal_scriptlets_authorized("aur-tool", "1.0-1")
                .expect("reject changed script"),
        );
        fs::remove_dir_all(local).expect("remove installed package");
        runtime
            .reconcile_aur_lifecycle_capabilities()
            .expect("reconcile removed package");
        assert!(!state.exists());
    }

    #[test]
    fn package_cache_inventory_groups_artifacts_and_removes_only_selected_packages() {
        let tree = TestTree::new();
        tree.file("libarchphene_pkg_111111111111111111111111.so", b"loader");
        tree.file("libarchphene_pkg_222222222222222222222222.so", b"pacman");
        tree.file("libarchphene_pkg_444444444444444444444444.so", b"keyring");
        tree.file("libarchphene_pkg_555555555555555555555555.so", b"bridge");
        tree.file("libarchphene_pkg_666666666666666666666666.so", b"trust");
        let manifest = b"# org.archphene.package-runtime.v1\n\
loader\t@loader\tlibarchphene_pkg_111111111111111111111111.so\t6\n\
tool\t@pacman\tlibarchphene_pkg_222222222222222222222222.so\t6\n\
keyring\t@keyring\tlibarchphene_pkg_444444444444444444444444.so\t7\n\
ownertrust\t@ownertrust\tlibarchphene_pkg_666666666666666666666666.so\t5\n\
library\tlibarchphene_path_bridge.so\tlibarchphene_pkg_555555555555555555555555.so\t6\n";
        let runtime = PackageRuntime::prepare(
            &tree.root,
            &tree.native,
            manifest,
            RepositoryArchitecture::X86_64,
        )
        .expect("package runtime");
        let cache = tree.root.join(PACKAGE_CACHE_DIRECTORY);
        let btop_archive = cache.join("btop-1.4.7-1-x86_64.pkg.tar.zst");
        let btop_signature = cache.join("btop-1.4.7-1-x86_64.pkg.tar.zst.sig");
        let glibc_partial = cache.join("glibc-2.42-1-x86_64.pkg.tar.zst.part");
        let old_btop = cache.join("btop-1.4.6-2-x86_64.pkg.tar.xz");
        fs::write(&btop_archive, b"archive").expect("btop archive");
        fs::write(&btop_signature, b"signature").expect("btop signature");
        fs::write(&glibc_partial, b"partial").expect("glibc partial");
        fs::write(&old_btop, b"old").expect("old btop archive");

        let intent_path = tree.root.join(PACKAGE_MUTATION_INTENT_FILE);
        fs::write(
            &intent_path,
            b"org.archphene.package-mutation.v1\nremove\tbtop\t1.4.7-1\n",
        )
        .expect("mutation intent");
        fs::set_permissions(&intent_path, fs::Permissions::from_mode(0o600))
            .expect("private mutation intent");
        assert!(matches!(
            runtime.clear_package_cache_packages(&["btop"]),
            Err(PackageRuntimeError::Busy)
        ));
        assert!(matches!(
            runtime.clear_package_cache(),
            Err(PackageRuntimeError::Busy)
        ));
        assert!(btop_archive.exists());
        fs::remove_file(&intent_path).expect("remove mutation intent");

        let inventory = runtime.package_cache_catalog().expect("cache inventory");
        assert_eq!(inventory.total_bytes(), 26);
        assert_eq!(
            inventory.entries(),
            &[
                PackageCacheEntry {
                    package: "btop".to_owned(),
                    version: "1.4.6-2".to_owned(),
                    architecture: "x86_64".to_owned(),
                    bytes: 3,
                    artifacts: 1,
                },
                PackageCacheEntry {
                    package: "btop".to_owned(),
                    version: "1.4.7-1".to_owned(),
                    architecture: "x86_64".to_owned(),
                    bytes: 16,
                    artifacts: 2,
                },
                PackageCacheEntry {
                    package: "glibc".to_owned(),
                    version: "2.42-1".to_owned(),
                    architecture: "x86_64".to_owned(),
                    bytes: 7,
                    artifacts: 1,
                },
            ],
        );
        assert_eq!(
            inventory.page(0).expect("cache page").as_str().unwrap(),
            "btop\t1.4.6-2\tx86_64\t3\t1\n\
btop\t1.4.7-1\tx86_64\t16\t2\n\
glibc\t2.42-1\tx86_64\t7\t1\n",
        );
        assert_eq!(
            runtime
                .clear_package_cache_packages(&["btop"])
                .expect("selected cleanup"),
            19
        );
        assert!(!btop_archive.exists());
        assert!(!btop_signature.exists());
        assert!(!old_btop.exists());
        assert!(glibc_partial.exists());
        let retained = runtime.package_cache_catalog().expect("retained inventory");
        assert_eq!(
            retained.entries(),
            &[PackageCacheEntry {
                package: "glibc".to_owned(),
                version: "2.42-1".to_owned(),
                architecture: "x86_64".to_owned(),
                bytes: 7,
                artifacts: 1,
            }],
        );
        assert!(matches!(
            runtime.clear_package_cache_packages(&["glibc", "glibc"]),
            Err(PackageRuntimeError::InvalidQuery)
        ));
        assert!(glibc_partial.exists());
    }

    #[test]
    fn package_signature_status_requires_a_valid_allowed_signer() {
        let x86_signer = "0123456789ABCDEF0123456789ABCDEF01234567";
        assert!(
            validate_signature_status(
                format!("[GNUPG:] VALIDSIG {x86_signer} 2026 0 0 0 0 0 0 0\n").as_bytes(),
                RepositoryArchitecture::X86_64,
            )
            .is_ok()
        );
        assert!(matches!(
            validate_signature_status(
                b"[GNUPG:] BADSIG 0123456789ABCDEF bad\n",
                RepositoryArchitecture::X86_64,
            ),
            Err(PackageRuntimeError::InvalidSignature)
        ));
        assert!(matches!(
            validate_signature_status(
                format!("[GNUPG:] VALIDSIG {x86_signer} 2026 0 0 0 0 0 0 0\n").as_bytes(),
                RepositoryArchitecture::Aarch64,
            ),
            Err(PackageRuntimeError::InvalidSignature)
        ));
        assert!(
            validate_signature_status(
                format!("[GNUPG:] VALIDSIG {AARCH64_BUILD_KEY} 2026 0 0 0 0 0 0 0\n").as_bytes(),
                RepositoryArchitecture::Aarch64,
            )
            .is_ok()
        );

        let mut non_utf8_diagnostic = b"gpgv: Good signature from \"Packager ".to_vec();
        non_utf8_diagnostic.extend_from_slice(&[0xc3, 0x28]);
        non_utf8_diagnostic.extend_from_slice(
            format!("\"\n[GNUPG:] VALIDSIG {x86_signer} 2026 0 0 0 0 0 0 0\n").as_bytes(),
        );
        assert!(
            validate_signature_status(&non_utf8_diagnostic, RepositoryArchitecture::X86_64,)
                .is_ok()
        );
        assert!(
            validate_signature_status(
                format!(
                    "[GNUPG:] BADSIGNOT ignored\n[GNUPG:] VALIDSIG {x86_signer} 2026 0 0 0 0 0 0 0\n"
                )
                .as_bytes(),
                RepositoryArchitecture::X86_64,
            )
            .is_ok()
        );
    }

    #[test]
    fn signed_package_metadata_must_match_the_resolved_identity() {
        let package_info = "pkgname = btop\npkgbase = btop\npkgver = 1.4.7-1\narch = x86_64\n";
        assert!(
            validate_package_info(
                package_info,
                "btop",
                "1.4.7-1",
                RepositoryArchitecture::X86_64,
            )
            .is_ok()
        );
        assert!(matches!(
            validate_package_info(
                package_info,
                "htop",
                "1.4.7-1",
                RepositoryArchitecture::X86_64,
            ),
            Err(PackageRuntimeError::InvalidPayload)
        ));
        assert!(matches!(
            validate_package_info(
                package_info,
                "btop",
                "1.4.7-1",
                RepositoryArchitecture::Aarch64,
            ),
            Err(PackageRuntimeError::InvalidPayload)
        ));
        assert!(
            validate_package_info(
                "pkgname = filesystem\npkgver = 2026.06.20-1\narch = any\n",
                "filesystem",
                "2026.06.20-1",
                RepositoryArchitecture::Aarch64,
            )
            .is_ok()
        );
    }
}
