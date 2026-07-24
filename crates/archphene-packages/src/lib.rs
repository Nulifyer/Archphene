#![forbid(unsafe_code)]

use std::ffi::OsString;
use std::fmt;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::os::unix::fs::{PermissionsExt, symlink};
use std::path::{Component, Path, PathBuf};
use std::process::{Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};

pub const MAX_MANIFEST_BYTES: usize = 32 * 1024;
pub const MAX_MANIFEST_ENTRIES: usize = 128;
pub const MAX_TOOL_OUTPUT_BYTES: usize = 16 * 1024;

const MANIFEST_HEADER: &str = "# org.archphene.package-runtime.v1";
const COMMAND_TIMEOUT: Duration = Duration::from_secs(10);
const ALIAS_DIRECTORY: &str = "run/package-runtime-v1";
const OUTPUT_FILE: &str = "run/package-command-output.tmp";
const PACMAN_CONFIG_FILE: &str = "etc/pacman.conf";
const PACMAN_CONFIG_TEMP_FILE: &str = "etc/pacman.conf.tmp";
const CATALOG_DIRECTORY: &str = "var/lib/pacman/sync";
const CORE_CATALOG_LIMIT: u64 = 8 * 1024 * 1024;
const EXTRA_CATALOG_LIMIT: u64 = 64 * 1024 * 1024;

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
}

impl PackageTool {
    const fn index(self) -> usize {
        match self {
            Self::Pacman => 0,
            Self::Bsdtar => 1,
            Self::Gpg => 2,
            Self::Gpgv => 3,
        }
    }

    const fn logical_name(self) -> &'static str {
        match self {
            Self::Pacman => "@pacman",
            Self::Bsdtar => "@bsdtar",
            Self::Gpg => "@gpg",
            Self::Gpgv => "@gpgv",
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
    ToolFailed(i32, ToolOutput),
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

#[derive(Clone)]
pub struct PackageRuntime {
    arch_root: PathBuf,
    native_root: PathBuf,
    alias_root: PathBuf,
    pacman_config: PathBuf,
    architecture: RepositoryArchitecture,
    loader: PathBuf,
    tools: [Option<PathBuf>; 4],
    library_path: OsString,
}

#[derive(Debug)]
pub struct ToolOutput {
    bytes: [u8; MAX_TOOL_OUTPUT_BYTES],
    length: usize,
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
        let mut tools: [Option<PathBuf>; 4] = std::array::from_fn(|_| None);
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
                "tool" => {
                    let tool = tool_from_logical(entry.logical)
                        .ok_or(PackageRuntimeError::InvalidManifest)?;
                    tools[tool.index()] = Some(source);
                }
                "library" if !entry.logical.starts_with('@') => {}
                _ => return Err(PackageRuntimeError::InvalidManifest),
            }
        }
        if entry_count == 0 {
            return Err(PackageRuntimeError::InvalidManifest);
        }
        let loader = loader.ok_or(PackageRuntimeError::MissingEntry("@loader"))?;
        if tools[PackageTool::Pacman.index()].is_none() {
            return Err(PackageRuntimeError::MissingEntry("@pacman"));
        }

        prepare_alias_directory(&alias_root)?;
        for line in manifest.lines().skip(1) {
            let entry = parse_entry(line)?;
            if entry.role == "library" {
                let source = verified_source(&native_root, entry.packaged, entry.size)?;
                symlink(&source, alias_root.join(entry.logical))?;
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
        Ok(Self {
            arch_root: arch_root.to_path_buf(),
            native_root,
            alias_root,
            pacman_config,
            architecture,
            loader,
            tools,
            library_path,
        })
    }

    pub fn run(
        &self,
        tool: PackageTool,
        arguments: &[&str],
    ) -> Result<ToolOutput, PackageRuntimeError> {
        self.run_with_timeout(tool, arguments, COMMAND_TIMEOUT)
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
            .env("PATH", self.arch_root.join("usr/bin"))
            .env("LANG", "C")
            .env("LC_ALL", "C")
            .env("GLIBC_TUNABLES", "glibc.pthread.rseq=0")
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
        let code = status.code().unwrap_or(-1);
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
}

#[derive(Debug)]
pub struct CatalogDownload {
    repository: Repository,
    file: File,
    temporary: PathBuf,
    destination: PathBuf,
    active: bool,
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
        || !matches!(role, "loader" | "tool" | "library")
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
            fs::create_dir(&native).expect("native directory");
            Self { root, native }
        }

        fn file(&self, name: &str, bytes: &[u8]) {
            fs::write(self.native.join(name), bytes).expect("native fixture");
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
        let manifest = b"# org.archphene.package-runtime.v1\n\
loader\t@loader\tlibarchphene_pkg_111111111111111111111111.so\t6\n\
tool\t@pacman\tlibarchphene_pkg_222222222222222222222222.so\t6\n\
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
        let manifest = b"# org.archphene.package-runtime.v1\n\
loader\t@loader\tlibarchphene_pkg_111111111111111111111111.so\t6\n\
tool\t@pacman\tlibarchphene_pkg_222222222222222222222222.so\t6\n";
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
        let manifest = b"# org.archphene.package-runtime.v1\n\
loader\t@loader\tlibarchphene_pkg_111111111111111111111111.so\t6\n\
tool\t@pacman\tlibarchphene_pkg_222222222222222222222222.so\t6\n";
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
}
