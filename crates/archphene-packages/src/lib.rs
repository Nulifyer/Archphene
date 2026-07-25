#![forbid(unsafe_code)]

use std::ffi::OsString;
use std::fmt;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt, symlink};
use std::os::unix::process::ExitStatusExt;
use std::path::{Component, Path, PathBuf};
use std::process::{Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};

use archphene_process::{CommandEnvironment, ProcessError};

pub const MAX_MANIFEST_BYTES: usize = 32 * 1024;
pub const MAX_MANIFEST_ENTRIES: usize = 128;
pub const MAX_TOOL_OUTPUT_BYTES: usize = 16 * 1024;
pub const INSTALLED_PACKAGE_PAGE_SIZE: usize = 60;

const MANIFEST_HEADER: &str = "# org.archphene.package-runtime.v1";
const COMMAND_TIMEOUT: Duration = Duration::from_secs(10);
const TRANSACTION_TIMEOUT: Duration = Duration::from_secs(5 * 60);
const ALIAS_DIRECTORY: &str = "run/package-runtime-v1";
const OUTPUT_FILE: &str = "run/package-command-output.tmp";
const INSTALL_REASON_INTENT_FILE: &str = "run/package-install-reasons-v1";
const INSTALL_REASON_INTENT_TEMP_FILE: &str = "run/package-install-reasons-v1.tmp";
const INSTALL_REASON_INTENT_HEADER: &str = "org.archphene.package-install-reasons.v1";
const INSTALL_REASON_INTENT_LIMIT: u64 = 64 * 1024;
const PACMAN_CONFIG_FILE: &str = "etc/pacman.conf";
const PACMAN_CONFIG_TEMP_FILE: &str = "etc/pacman.conf.tmp";
const CATALOG_DIRECTORY: &str = "var/lib/pacman/sync";
const CORE_CATALOG_LIMIT: u64 = 8 * 1024 * 1024;
const EXTRA_CATALOG_LIMIT: u64 = 64 * 1024 * 1024;
const PACKAGE_ARCHIVE_LIMIT: u64 = 4 * 1024 * 1024 * 1024;
const PACKAGE_SIGNATURE_LIMIT: u64 = 1024 * 1024;
const PACKAGE_CACHE_DIRECTORY: &str = "var/cache/pacman/pkg";
const PACKAGE_TRUST_DIRECTORY: &str = "run/package-trust-v1";
const PACKAGE_TRUST_STATE: &str = "source-v1";
const PACKAGE_TRUST_STATE_LIMIT: u64 = 512;
const PACKAGE_KEYBOX_LIMIT: u64 = 8 * 1024 * 1024;
const PACKAGE_TRUSTDB_LIMIT: u64 = 1024 * 1024;
const SHELLS_FILE: &str = "etc/shells";
const SHELLS_FILE_LIMIT: u64 = 4 * 1024;
const LOCAL_DATABASE_ENTRY_LIMIT: usize = 4096;
const LOCAL_DESCRIPTION_LIMIT: u64 = 64 * 1024;
const PATH_BRIDGE_NAME: &str = "libarchphene_path_bridge.so";
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
    InvalidQuery,
    InvalidResolution,
    MissingTarget,
    NotInstalled,
    InvalidPayload,
    InvalidSignature,
    ToolFailed(i32, ToolOutput),
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
            Self::InvalidQuery => formatter.write_str("invalid package search query"),
            Self::InvalidResolution => formatter.write_str("invalid package dependency resolution"),
            Self::MissingTarget => {
                formatter.write_str("resolved packages omit the requested target")
            }
            Self::NotInstalled => formatter.write_str("package is not installed"),
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
    path_bridge: PathBuf,
    tools: [Option<PathBuf>; 5],
    library_path: OsString,
    executable_path: OsString,
}

#[derive(Debug)]
pub struct ToolOutput {
    bytes: [u8; MAX_TOOL_OUTPUT_BYTES],
    length: usize,
}

pub struct InstalledPackageCatalog {
    packages: Vec<(String, String, bool)>,
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

impl InstalledPackageCatalog {
    pub fn page(&self, offset: usize) -> Result<ToolOutput, PackageRuntimeError> {
        if offset > self.packages.len() {
            return Err(PackageRuntimeError::InvalidQuery);
        }
        let mut output = empty_tool_output();
        for (name, version, explicitly_installed) in self
            .packages
            .iter()
            .skip(offset)
            .take(INSTALLED_PACKAGE_PAGE_SIZE)
        {
            output.push(name.as_bytes())?;
            output.push(b"\t")?;
            output.push(version.as_bytes())?;
            output.push(if *explicitly_installed {
                b"\t1\n"
            } else {
                b"\t0\n"
            })?;
        }
        Ok(output)
    }
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
        if !has_path_bridge {
            return Err(PackageRuntimeError::MissingEntry(PATH_BRIDGE_NAME));
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

        let pacman_config = arch_root.join(PACMAN_CONFIG_FILE);
        publish_regular_file(
            &pacman_config,
            &arch_root.join(PACMAN_CONFIG_TEMP_FILE),
            architecture.pacman_config(),
        )?;
        let mut library_path = alias_root.as_os_str().to_os_string();
        library_path.push(":");
        library_path.push(native_root.as_os_str());
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
            path_bridge,
            tools,
            library_path,
            executable_path,
        };
        runtime.recover_pending_install_reasons()?;
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
        parse_search_output(raw.as_str()?)
    }

    pub fn resolve(&self, package: &str) -> Result<ToolOutput, PackageRuntimeError> {
        if !safe_logical_name(package) || !self.catalogs_ready() {
            return Err(if self.catalogs_ready() {
                PackageRuntimeError::InvalidQuery
            } else {
                PackageRuntimeError::InvalidCatalog
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
        let raw = self.run(
            PackageTool::Pacman,
            &[
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
                package,
            ],
        )?;
        parse_resolution_output(raw.as_str()?, package, self.architecture)
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
            packages.push((name.to_owned(), version.to_owned(), explicitly_installed));
        }
        packages.sort_unstable_by(|left, right| left.0.cmp(&right.0));
        if packages.windows(2).any(|pair| pair[0].0 == pair[1].0) {
            return Err(PackageRuntimeError::InvalidResolution);
        }
        Ok(InstalledPackageCatalog { packages })
    }

    pub fn install(&self, package: &str) -> Result<ToolOutput, PackageRuntimeError> {
        let resolution = self.resolve(package)?;
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
                explicitly_installed: payload.name == package,
            });
        }
        if archives.is_empty() || archives.len() > 256 {
            return Err(PackageRuntimeError::InvalidResolution);
        }
        self.preserve_explicit_install_reasons(&mut archives)?;

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

        self.publish_install_reason_intent(&archives)?;
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
            "--needed",
            "--asdeps",
            "-U",
        ];
        transaction_arguments.extend(archives.iter().map(|archive| archive.path.as_str()));
        if let Err(error) = self.run_with_timeout(
            PackageTool::Pacman,
            &transaction_arguments,
            TRANSACTION_TIMEOUT,
        ) {
            self.recover_database_lock()?;
            self.recover_pending_install_reasons()?;
            return Err(error);
        }
        self.recover_pending_install_reasons()?;
        let installed = self.installed_version(package)?;
        let expected = archives
            .iter()
            .find(|archive| archive.name == package)
            .ok_or(PackageRuntimeError::MissingTarget)?;
        if installed.as_str()? != expected.version {
            return Err(PackageRuntimeError::InvalidResolution);
        }
        Ok(installed)
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
        let result = self.run_with_timeout(
            PackageTool::Pacman,
            &[
                "--config",
                config,
                "--root",
                root,
                "--dbpath",
                database,
                "--noconfirm",
                "--noprogressbar",
                "--noscriptlet",
                "-R",
                package,
            ],
            TRANSACTION_TIMEOUT,
        );
        if let Err(error) = result {
            self.recover_database_lock()?;
            return Err(error);
        }
        self.validate_local_database()?;
        if !self.installed_version(package)?.as_bytes().is_empty() {
            return Err(PackageRuntimeError::InvalidResolution);
        }
        Ok(empty_tool_output())
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
        let path = self.arch_root.join(INSTALL_REASON_INTENT_FILE);
        let metadata = match fs::symlink_metadata(&path) {
            Ok(metadata) => metadata,
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(()),
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
        let packages = parse_install_reason_intent(content)?;
        self.recover_database_lock()?;
        for package in packages {
            if !self.installed_version(package)?.as_bytes().is_empty() {
                self.mark_explicitly_installed(package)?;
            }
        }
        self.validate_local_database()?;
        fs::remove_file(&path)?;
        File::open(path.parent().ok_or(PackageRuntimeError::InvalidPath)?)?.sync_all()?;
        Ok(())
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

    pub fn clear_package_cache(&self) -> Result<u64, PackageRuntimeError> {
        let directory = self.arch_root.join(PACKAGE_CACHE_DIRECTORY);
        let metadata = fs::symlink_metadata(&directory)?;
        if metadata.file_type().is_symlink() || !metadata.is_dir() {
            return Err(PackageRuntimeError::UnsafeEntry(directory));
        }

        let mut entry_count = 0_usize;
        let mut reclaimed_bytes = 0_u64;
        for entry in fs::read_dir(&directory)? {
            let entry = entry?;
            entry_count = entry_count.saturating_add(1);
            if entry_count > LOCAL_DATABASE_ENTRY_LIMIT {
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
            reclaimed_bytes = reclaimed_bytes
                .checked_add(metadata.len())
                .ok_or(PackageRuntimeError::OutputLimit)?;
        }

        for entry in fs::read_dir(&directory)? {
            let entry = entry?;
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
            fs::remove_file(path)?;
        }
        File::open(&directory)?.sync_all()?;
        Ok(reclaimed_bytes)
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
        validate_signature_status(output.as_str()?, self.architecture)?;
        let package_info = self.run(PackageTool::Bsdtar, &["-xOf", package, ".PKGINFO"])?;
        validate_package_info(
            package_info.as_str()?,
            expected_name,
            expected_version,
            self.architecture,
        )?;
        Ok(output)
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
        let keyring = immutable_source_identity(&self.native_root, &self.keyring)?;
        let ownertrust = immutable_source_identity(&self.native_root, &self.ownertrust)?;
        Ok(format!(
            "org.archphene.package-trust.v1\n{keyring}\n{ownertrust}\n"
        ))
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

        let result = read_output(&output_path);
        let _ = fs::remove_file(&output_path);
        let output = result?;
        let code = status
            .code()
            .or_else(|| status.signal().map(|signal| -signal))
            .unwrap_or(-1);
        if !status.success() {
            return Err(PackageRuntimeError::ToolFailed(code, output));
        }
        Ok(output)
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
        )
        .map_err(PackageRuntimeError::from)
    }
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

fn read_output(path: &Path) -> Result<ToolOutput, PackageRuntimeError> {
    let metadata = fs::metadata(path)?;
    if metadata.len() > MAX_TOOL_OUTPUT_BYTES as u64 {
        return Err(PackageRuntimeError::OutputLimit);
    }
    let mut output = ToolOutput {
        bytes: [0; MAX_TOOL_OUTPUT_BYTES],
        length: usize::try_from(metadata.len()).map_err(|_| PackageRuntimeError::OutputLimit)?,
    };
    File::open(path)?.read_exact(&mut output.bytes[..output.length])?;
    Ok(output)
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

fn parse_search_output(input: &str) -> Result<ToolOutput, PackageRuntimeError> {
    let mut output = ToolOutput {
        bytes: [0; MAX_TOOL_OUTPUT_BYTES],
        length: 0,
    };
    let mut pending: Option<(&str, &str, &str)> = None;
    let mut count = 0_usize;
    for line in input.lines() {
        if line.starts_with(char::is_whitespace) {
            if let Some((repository, name, version)) = pending.take() {
                append_search_result(&mut output, repository, name, version, line.trim())?;
                count += 1;
                if count >= 100 {
                    break;
                }
            }
            continue;
        }
        if let Some((repository, name, version)) = pending.take() {
            append_search_result(&mut output, repository, name, version, "")?;
            count += 1;
            if count >= 100 {
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
            pending = Some((repository, name, version));
        }
    }
    if count < 100 {
        if let Some((repository, name, version)) = pending {
            append_search_result(&mut output, repository, name, version, "")?;
        }
    }
    Ok(output)
}

fn empty_tool_output() -> ToolOutput {
    ToolOutput {
        bytes: [0; MAX_TOOL_OUTPUT_BYTES],
        length: 0,
    }
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
) -> Result<(), PackageRuntimeError> {
    if description.len() > 512
        || description
            .bytes()
            .any(|byte| matches!(byte, b'\t' | b'\r' | b'\n' | 0))
    {
        return Err(PackageRuntimeError::InvalidManifest);
    }
    for (index, field) in [repository, name, version, description]
        .into_iter()
        .enumerate()
    {
        output.push(field.as_bytes())?;
        output.push(if index == 3 { b"\n" } else { b"\t" })?;
    }
    Ok(())
}

fn parse_resolution_output(
    input: &str,
    target: &str,
    architecture: RepositoryArchitecture,
) -> Result<ToolOutput, PackageRuntimeError> {
    let mut output = ToolOutput {
        bytes: [0; MAX_TOOL_OUTPUT_BYTES],
        length: 0,
    };
    let mut contains_target = false;
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
        if count > 256 {
            return Err(PackageRuntimeError::OutputLimit);
        }
        contains_target |= name == target;
        for (index, field) in [repository, name, version, filename, url, size]
            .into_iter()
            .enumerate()
        {
            output.push(field.as_bytes())?;
            output.push(if index == 5 { b"\n" } else { b"\t" })?;
        }
    }
    if count == 0 || !contains_target {
        return Err(PackageRuntimeError::MissingTarget);
    }
    Ok(output)
}

struct ResolvedPayload<'a> {
    name: &'a str,
    version: &'a str,
    filename: &'a str,
    size: u64,
}

struct InstallArchive {
    path: String,
    name: String,
    version: String,
    explicitly_installed: bool,
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
        name,
        version,
        filename,
        size,
    })
}

fn resolution_contains(output: &ToolOutput, package: &str) -> Result<bool, PackageRuntimeError> {
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

fn validate_signature_status(
    output: &str,
    architecture: RepositoryArchitecture,
) -> Result<(), PackageRuntimeError> {
    if output.lines().any(|line| {
        [
            "[GNUPG:] BADSIG",
            "[GNUPG:] ERRSIG",
            "[GNUPG:] REVKEYSIG",
            "[GNUPG:] EXPKEYSIG",
            "[GNUPG:] KEYEXPIRED",
            "[GNUPG:] SIGEXPIRED",
        ]
        .into_iter()
        .any(|status| line.contains(status))
    }) {
        return Err(PackageRuntimeError::InvalidSignature);
    }
    let signer = output.lines().find_map(|line| {
        line.split_once("[GNUPG:] VALIDSIG ")
            .and_then(|(_, fields)| fields.split_ascii_whitespace().next())
    });
    let Some(signer) = signer else {
        return Err(PackageRuntimeError::InvalidSignature);
    };
    if !matches!(signer.len(), 40 | 64)
        || !signer
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_lowercase())
        || architecture == RepositoryArchitecture::Aarch64 && signer != AARCH64_BUILD_KEY
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
    use std::sync::atomic::{AtomicU64, Ordering};

    static TEST_ID: AtomicU64 = AtomicU64::new(1);

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
    }

    impl Drop for TestTree {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.root);
        }
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
        )
        .expect("search output");
        assert_eq!(
            parsed.as_str().expect("utf-8"),
            "extra\tdotnet-sdk\t10.0.10.sdk110-1\tThe .NET Core SDK\n\
extra\tdotnet-sdk-8.0\t8.0.29.sdk129-1\tThe .NET Core SDK\n"
        );
        assert!(valid_search_query("dotnet-sdk"));
        assert!(!valid_search_query("a"));
        assert!(!valid_search_query("../dotnet"));
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
        assert!(first.starts_with("fixture-000\t1.0.0-1\t1\n"));
        assert!(first.ends_with("fixture-059\t1.0.59-1\t0\n"));
        let second = catalog
            .page(INSTALLED_PACKAGE_PAGE_SIZE)
            .expect("second page");
        assert_eq!(
            second.as_str().expect("second page UTF-8"),
            "fixture-060\t1.0.60-1\t1\nfixture-061\t1.0.61-1\t0\n\
fixture-062\t1.0.62-1\t1\nfixture-063\t1.0.63-1\t0\n\
fixture-064\t1.0.64-1\t1\nfixture-065\t1.0.65-1\t0\n",
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
    fn package_resolution_output_is_strict_and_contains_target() {
        let input = "core\tglibc\t2.42+r33+gde5fe48316ed-1\tglibc-2.42+r33+gde5fe48316ed-1-x86_64.pkg.tar.zst\thttps://geo.mirror.pkgbuild.com/core/os/x86_64/glibc-2.42+r33+gde5fe48316ed-1-x86_64.pkg.tar.zst\t10158024\n\
extra\tdotnet-sdk\t10.0.10.sdk110-1\tdotnet-sdk-10.0.10.sdk110-1-x86_64.pkg.tar.zst\thttps://geo.mirror.pkgbuild.com/extra/os/x86_64/dotnet-sdk-10.0.10.sdk110-1-x86_64.pkg.tar.zst\t123456789\n";
        let parsed = parse_resolution_output(input, "dotnet-sdk", RepositoryArchitecture::X86_64)
            .expect("valid resolution");
        assert_eq!(parsed.as_str().expect("utf-8"), input);

        assert!(matches!(
            parse_resolution_output(input, "btop", RepositoryArchitecture::X86_64,),
            Err(PackageRuntimeError::MissingTarget)
        ));
        assert!(matches!(
            parse_resolution_output(
                "extra\tbtop\t1.4.4-1\tbtop-1.4.4-1-aarch64.pkg.tar.xz\thttps://example.com/btop-1.4.4-1-aarch64.pkg.tar.xz\t123456\n",
                "btop",
                RepositoryArchitecture::Aarch64,
            ),
            Err(PackageRuntimeError::InvalidResolution)
        ));
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
    fn package_signature_status_requires_a_valid_allowed_signer() {
        let x86_signer = "0123456789ABCDEF0123456789ABCDEF01234567";
        assert!(
            validate_signature_status(
                &format!("[GNUPG:] VALIDSIG {x86_signer} 2026 0 0 0 0 0 0 0\n"),
                RepositoryArchitecture::X86_64,
            )
            .is_ok()
        );
        assert!(matches!(
            validate_signature_status(
                "[GNUPG:] BADSIG 0123456789ABCDEF bad\n",
                RepositoryArchitecture::X86_64,
            ),
            Err(PackageRuntimeError::InvalidSignature)
        ));
        assert!(matches!(
            validate_signature_status(
                &format!("[GNUPG:] VALIDSIG {x86_signer} 2026 0 0 0 0 0 0 0\n"),
                RepositoryArchitecture::Aarch64,
            ),
            Err(PackageRuntimeError::InvalidSignature)
        ));
        assert!(
            validate_signature_status(
                &format!("[GNUPG:] VALIDSIG {AARCH64_BUILD_KEY} 2026 0 0 0 0 0 0 0\n"),
                RepositoryArchitecture::Aarch64,
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
