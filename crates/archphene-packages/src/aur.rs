use std::collections::BTreeMap;
use std::fmt;
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Seek, SeekFrom};
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::{Component, Path};

use flate2::read::GzDecoder;
use serde::Deserialize;
use sha2::{Digest, Sha256, Sha512};
use tar::{Archive, EntryType};

use super::RepositoryArchitecture;

pub const MAX_AUR_RPC_BYTES: usize = 128 * 1024;
pub const MAX_AUR_SNAPSHOT_BYTES: usize = 4 * 1024 * 1024;
pub const MAX_AUR_SRCINFO_BYTES: usize = 256 * 1024;
pub const MAX_AUR_PKGBUILD_BYTES: usize = 256 * 1024;
pub const MAX_AUR_REVIEW_BYTES: usize = 1024 * 1024;
pub const MAX_AUR_SOURCES: usize = 64;
pub const MAX_AUR_DEPENDENCIES: usize = 256;
pub const MAX_AUR_GRAPH_BASES: usize = 32;
pub const MAX_AUR_GRAPH_EDGES: usize = 256;
pub const MAX_AUR_GRAPH_WIRE_BYTES: usize = 128 * 1024;
pub const MAX_AUR_SOURCE_BYTES: u64 = 4 * 1024 * 1024 * 1024;

const MAX_AUR_SNAPSHOT_ENTRIES: usize = 128;
const MAX_AUR_SNAPSHOT_EXPANDED_BYTES: usize = 8 * 1024 * 1024;
const MAX_AUR_SNAPSHOT_ENTRY_BYTES: u64 = 512 * 1024;
pub(super) const AUR_SOURCE_CACHE_DIRECTORY: &str = "var/cache/archphene/aur-sources";
pub(super) const AUR_SNAPSHOT_CACHE_DIRECTORY: &str = "var/cache/archphene/aur-snapshots";
const MAX_NAME_BYTES: usize = 128;
const MAX_VERSION_BYTES: usize = 128;
const MAX_DESCRIPTION_BYTES: usize = 2 * 1024;
const MAX_MAINTAINER_BYTES: usize = 128;
const MAX_FIELD_BYTES: usize = 4 * 1024;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum AurBuildStep {
    Prepare,
    Build,
    Check,
    Package,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct AurSource {
    pub expression: String,
    pub architecture: Option<String>,
    pub filename: String,
    pub remote_url: Option<String>,
    pub local: bool,
    pub checksum: Option<AurSourceChecksum>,
    pub insecure_transport: bool,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum AurSourceChecksum {
    Sha256([u8; 32]),
    Sha512([u8; 64]),
}

impl AurSourceChecksum {
    fn cache_key(self) -> String {
        match self {
            // Preserve the established SHA-256 cache path so an upgrade can
            // reverify and reuse already downloaded AUR sources.
            Self::Sha256(value) => hex_bytes(&value),
            Self::Sha512(value) => format!("sha512-{}", hex_bytes(&value)),
        }
    }

    fn is_zero(self) -> bool {
        match self {
            Self::Sha256(value) => value == [0; 32],
            Self::Sha512(value) => value == [0; 64],
        }
    }

    fn matches(self, bytes: &[u8]) -> bool {
        match self {
            Self::Sha256(expected) => <[u8; 32]>::from(Sha256::digest(bytes)) == expected,
            Self::Sha512(expected) => <[u8; 64]>::from(Sha512::digest(bytes)) == expected,
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct AurInstallScript {
    pub package_name: String,
    pub path: String,
    pub contents: Option<Vec<u8>>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ReviewedInstallScript<'a> {
    pub contents: &'a [u8],
    pub sha256: [u8; 32],
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct AurReview {
    pub package_base: String,
    pub package_name: String,
    pub version: String,
    pub description: String,
    pub maintainer: Option<String>,
    pub project_url: Option<String>,
    pub snapshot_path: String,
    pub last_modified: u64,
    pub out_of_date: bool,
    pub licenses: Vec<String>,
    pub dependencies: Vec<String>,
    pub required_packages: Vec<String>,
    pub provided_packages: Vec<String>,
    pub make_dependencies: Vec<String>,
    pub check_dependencies: Vec<String>,
    pub sources: Vec<AurSource>,
    pub valid_pgp_keys: Vec<String>,
    pub install_scripts: Vec<AurInstallScript>,
    pub build_steps: Vec<AurBuildStep>,
    pub unverified_source_count: usize,
    pub insecure_source_count: usize,
    pub review_sha256: [u8; 32],
    pub snapshot_sha256: Option<[u8; 32]>,
    pub snapshot_commit: Option<String>,
    pub pkgbuild: Vec<u8>,
}

#[derive(Debug)]
pub struct AurSourceDownload {
    file: File,
    temporary: std::path::PathBuf,
    destination: std::path::PathBuf,
    expected_checksum: AurSourceChecksum,
    maximum_size: u64,
    active: bool,
}

#[derive(Debug, Eq, PartialEq)]
pub enum AurReviewError {
    SizeLimit(&'static str),
    InvalidUtf8(&'static str),
    InvalidRpc,
    RpcMismatch,
    InvalidSnapshot(&'static str),
    InvalidSrcInfo,
    MetadataMismatch,
    UnsupportedArchitecture,
    MissingPackageFunction,
    Limit(&'static str),
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct AurBuildGraph {
    pub package_bases: Vec<String>,
    pub edges: Vec<AurBuildGraphEdge>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct AurBuildGraphEdge {
    pub package_base: String,
    pub dependency_base: String,
    pub dependency: String,
    pub requirement: String,
}

impl AurBuildGraph {
    pub fn write_wire(&self, destination: &mut [u8]) -> Result<usize, AurBuildGraphError> {
        if destination.len() < 8 || destination.len() > MAX_AUR_GRAPH_WIRE_BYTES {
            return Err(AurBuildGraphError::Limit("graph wire"));
        }
        let mut writer = GraphWireWriter {
            destination,
            position: 0,
        };
        writer.bytes(b"AUBG0001")?;
        writer.u32(
            u32::try_from(self.package_bases.len())
                .map_err(|_| AurBuildGraphError::Limit("package-base"))?,
        )?;
        if self.package_bases.is_empty() || self.package_bases.len() > MAX_AUR_GRAPH_BASES {
            return Err(AurBuildGraphError::Limit("package-base"));
        }
        let mut package_base_positions = BTreeMap::new();
        for (index, package_base) in self.package_bases.iter().enumerate() {
            validate_package_name(package_base).map_err(|_| AurBuildGraphError::InvalidReview)?;
            if package_base_positions
                .insert(package_base.as_str(), index)
                .is_some()
            {
                return Err(AurBuildGraphError::InvalidReview);
            }
            writer.string(package_base)?;
        }
        writer
            .u32(u32::try_from(self.edges.len()).map_err(|_| AurBuildGraphError::Limit("edge"))?)?;
        if self.edges.len() > MAX_AUR_GRAPH_EDGES {
            return Err(AurBuildGraphError::Limit("edge"));
        }
        if !self.edges.windows(2).all(|pair| {
            (
                &pair[0].package_base,
                &pair[0].dependency_base,
                &pair[0].dependency,
                &pair[0].requirement,
            ) < (
                &pair[1].package_base,
                &pair[1].dependency_base,
                &pair[1].dependency,
                &pair[1].requirement,
            )
        }) {
            return Err(AurBuildGraphError::InvalidReview);
        }
        for edge in &self.edges {
            for value in [
                edge.package_base.as_str(),
                edge.dependency_base.as_str(),
                edge.dependency.as_str(),
            ] {
                validate_package_name(value).map_err(|_| AurBuildGraphError::InvalidReview)?;
                writer.string(value)?;
            }
            if edge.requirement.is_empty()
                || edge.requirement.len() > MAX_FIELD_BYTES
                || !matches!(
                    dependency_name(&edge.requirement),
                    Ok(dependency) if dependency == edge.dependency
                )
                || package_base_positions
                    .get(edge.dependency_base.as_str())
                    .zip(package_base_positions.get(edge.package_base.as_str()))
                    .is_none_or(|(dependency, dependent)| dependency >= dependent)
                || edge.requirement.bytes().any(|byte| {
                    !(byte.is_ascii_alphanumeric()
                        || matches!(
                            byte,
                            b'@' | b'+' | b':' | b'.' | b'_' | b'-' | b'<' | b'=' | b'>'
                        ))
                })
            {
                return Err(AurBuildGraphError::InvalidReview);
            }
            writer.string(&edge.requirement)?;
        }
        Ok(writer.position)
    }
}

struct GraphWireWriter<'a> {
    destination: &'a mut [u8],
    position: usize,
}

impl GraphWireWriter<'_> {
    fn u32(&mut self, value: u32) -> Result<(), AurBuildGraphError> {
        self.bytes(&value.to_le_bytes())
    }

    fn string(&mut self, value: &str) -> Result<(), AurBuildGraphError> {
        let length =
            u32::try_from(value.len()).map_err(|_| AurBuildGraphError::Limit("graph wire"))?;
        self.u32(length)?;
        self.bytes(value.as_bytes())
    }

    fn bytes(&mut self, value: &[u8]) -> Result<(), AurBuildGraphError> {
        let end = self
            .position
            .checked_add(value.len())
            .ok_or(AurBuildGraphError::Limit("graph wire"))?;
        let target = self
            .destination
            .get_mut(self.position..end)
            .ok_or(AurBuildGraphError::Limit("graph wire"))?;
        target.copy_from_slice(value);
        self.position = end;
        Ok(())
    }
}

#[derive(Debug, Eq, PartialEq)]
pub enum AurBuildGraphError {
    InvalidReview,
    DuplicatePackageBase,
    AmbiguousProvider(String),
    MissingProvider(String),
    Cycle,
    DisconnectedReview,
    Limit(&'static str),
}

impl fmt::Display for AurBuildGraphError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidReview => formatter.write_str("invalid reviewed AUR dependency graph"),
            Self::DuplicatePackageBase => {
                formatter.write_str("duplicate package base in reviewed AUR dependency graph")
            }
            Self::AmbiguousProvider(dependency) => {
                write!(
                    formatter,
                    "multiple reviewed AUR bases provide {dependency}"
                )
            }
            Self::MissingProvider(dependency) => {
                write!(
                    formatter,
                    "no official or reviewed AUR package provides {dependency}"
                )
            }
            Self::Cycle => formatter.write_str("reviewed AUR dependency graph contains a cycle"),
            Self::DisconnectedReview => {
                formatter.write_str("reviewed AUR dependency graph contains an unused package base")
            }
            Self::Limit(field) => write!(formatter, "AUR dependency graph exceeds {field} limit"),
        }
    }
}

impl std::error::Error for AurBuildGraphError {}

impl fmt::Display for AurReviewError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::SizeLimit(part) => write!(formatter, "AUR {part} exceeds its size limit"),
            Self::InvalidUtf8(part) => write!(formatter, "AUR {part} is not valid UTF-8"),
            Self::InvalidRpc => formatter.write_str("invalid AUR RPC response"),
            Self::RpcMismatch => formatter.write_str("AUR RPC response does not match the request"),
            Self::InvalidSnapshot(reason) => {
                write!(formatter, "invalid AUR source snapshot: {reason}")
            }
            Self::InvalidSrcInfo => formatter.write_str("invalid AUR .SRCINFO metadata"),
            Self::MetadataMismatch => {
                formatter.write_str("AUR RPC and .SRCINFO metadata do not match")
            }
            Self::UnsupportedArchitecture => {
                formatter.write_str("AUR package does not support this architecture")
            }
            Self::MissingPackageFunction => {
                formatter.write_str("AUR PKGBUILD has no visible package function")
            }
            Self::Limit(field) => write!(formatter, "AUR {field} exceeds its item limit"),
        }
    }
}

impl std::error::Error for AurReviewError {}

impl AurSourceDownload {
    pub(super) fn begin(
        arch_root: &Path,
        filename: &str,
        expected_checksum: AurSourceChecksum,
        maximum_size: u64,
    ) -> Result<Self, super::PackageRuntimeError> {
        if !safe_source_filename(filename)
            || expected_checksum.is_zero()
            || maximum_size == 0
            || maximum_size > MAX_AUR_SOURCE_BYTES
        {
            return Err(super::PackageRuntimeError::InvalidPayload);
        }
        let cache_root = arch_root.join("var/cache");
        require_directory(&cache_root)?;
        let archphene_cache = cache_root.join("archphene");
        create_or_require_directory(&archphene_cache)?;
        let directory = arch_root.join(AUR_SOURCE_CACHE_DIRECTORY);
        create_or_require_directory(&directory)?;
        let digest = expected_checksum.cache_key();
        let destination_name = format!("{digest}-{filename}");
        let temporary_name = format!("{destination_name}.part");
        let destination = directory.join(destination_name);
        let temporary = directory.join(temporary_name);
        super::prepare_output_path(&temporary)?;
        let file = OpenOptions::new()
            .create_new(true)
            .read(true)
            .write(true)
            .mode(0o600)
            .open(&temporary)?;
        Ok(Self {
            file,
            temporary,
            destination,
            expected_checksum,
            maximum_size,
            active: true,
        })
    }

    pub fn duplicate_file(&self) -> Result<File, super::PackageRuntimeError> {
        Ok(self.file.try_clone()?)
    }

    pub fn finish(mut self) -> Result<u64, super::PackageRuntimeError> {
        let length = verify_source_file(&mut self.file, self.expected_checksum, self.maximum_size)?;
        self.file.sync_all()?;
        match fs::symlink_metadata(&self.destination) {
            Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_file() => {
                return Err(super::PackageRuntimeError::UnsafeEntry(
                    self.destination.clone(),
                ));
            }
            Ok(_) => fs::remove_file(&self.destination)?,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(super::PackageRuntimeError::Io(error)),
        }
        fs::rename(&self.temporary, &self.destination)?;
        self.active = false;
        File::open(
            self.destination
                .parent()
                .ok_or(super::PackageRuntimeError::InvalidPath)?,
        )?
        .sync_all()?;
        Ok(length)
    }

    pub(super) fn verified_cache_size(
        arch_root: &Path,
        filename: &str,
        expected_checksum: AurSourceChecksum,
        maximum_size: u64,
    ) -> Result<Option<u64>, super::PackageRuntimeError> {
        if !safe_source_filename(filename)
            || expected_checksum.is_zero()
            || maximum_size == 0
            || maximum_size > MAX_AUR_SOURCE_BYTES
        {
            return Err(super::PackageRuntimeError::InvalidPayload);
        }
        let destination = arch_root
            .join(AUR_SOURCE_CACHE_DIRECTORY)
            .join(format!("{}-{filename}", expected_checksum.cache_key()));
        let metadata = match fs::symlink_metadata(&destination) {
            Ok(metadata) => metadata,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(error) => return Err(super::PackageRuntimeError::Io(error)),
        };
        if metadata.file_type().is_symlink() || !metadata.is_file() {
            return Err(super::PackageRuntimeError::UnsafeEntry(destination));
        }
        let mut file = File::open(&destination)?;
        match verify_source_file(&mut file, expected_checksum, maximum_size) {
            Ok(length) => Ok(Some(length)),
            Err(super::PackageRuntimeError::InvalidPayload) => {
                drop(file);
                fs::remove_file(&destination)?;
                File::open(
                    destination
                        .parent()
                        .ok_or(super::PackageRuntimeError::InvalidPath)?,
                )?
                .sync_all()?;
                Ok(None)
            }
            Err(error) => Err(error),
        }
    }

    pub(super) fn open_verified_cache(
        arch_root: &Path,
        filename: &str,
        expected_checksum: AurSourceChecksum,
        maximum_size: u64,
    ) -> Result<File, super::PackageRuntimeError> {
        let length =
            Self::verified_cache_size(arch_root, filename, expected_checksum, maximum_size)?
                .ok_or(super::PackageRuntimeError::InvalidPayload)?;
        let path = arch_root
            .join(AUR_SOURCE_CACHE_DIRECTORY)
            .join(format!("{}-{filename}", expected_checksum.cache_key()));
        let mut file = File::open(path)?;
        if verify_source_file(&mut file, expected_checksum, maximum_size)? != length {
            return Err(super::PackageRuntimeError::InvalidPayload);
        }
        file.seek(SeekFrom::Start(0))?;
        Ok(file)
    }
}

impl Drop for AurSourceDownload {
    fn drop(&mut self) {
        if self.active {
            let _ = fs::remove_file(&self.temporary);
        }
    }
}

fn verify_source_file(
    file: &mut File,
    expected_checksum: AurSourceChecksum,
    maximum_size: u64,
) -> Result<u64, super::PackageRuntimeError> {
    let metadata = file.metadata()?;
    let length = metadata.len();
    if !metadata.is_file() || length == 0 || length > maximum_size {
        return Err(super::PackageRuntimeError::InvalidPayload);
    }
    file.seek(SeekFrom::Start(0))?;
    let mut sha256 = Sha256::new();
    let mut sha512 = Sha512::new();
    let mut buffer = [0_u8; 64 * 1024];
    let mut total = 0_u64;
    loop {
        let count = file.read(&mut buffer)?;
        if count == 0 {
            break;
        }
        total = total
            .checked_add(u64::try_from(count).map_err(|_| super::PackageRuntimeError::OutputLimit)?)
            .ok_or(super::PackageRuntimeError::OutputLimit)?;
        if total > maximum_size {
            return Err(super::PackageRuntimeError::InvalidPayload);
        }
        match expected_checksum {
            AurSourceChecksum::Sha256(_) => sha256.update(&buffer[..count]),
            AurSourceChecksum::Sha512(_) => sha512.update(&buffer[..count]),
        }
    }
    let matches = match expected_checksum {
        AurSourceChecksum::Sha256(expected) => <[u8; 32]>::from(sha256.finalize()) == expected,
        AurSourceChecksum::Sha512(expected) => <[u8; 64]>::from(sha512.finalize()) == expected,
    };
    if total != length || !matches {
        return Err(super::PackageRuntimeError::InvalidPayload);
    }
    Ok(length)
}

pub(super) fn retain_reviewed_snapshot(
    arch_root: &Path,
    package_base: &str,
    expected_sha256: [u8; 32],
    bytes: &[u8],
) -> Result<u64, super::PackageRuntimeError> {
    if !safe_source_filename(package_base)
        || expected_sha256 == [0; 32]
        || bytes.is_empty()
        || bytes.len() > MAX_AUR_SNAPSHOT_BYTES
        || <[u8; 32]>::from(Sha256::digest(bytes)) != expected_sha256
    {
        return Err(super::PackageRuntimeError::InvalidPayload);
    }
    let cache_root = arch_root.join("var/cache");
    require_directory(&cache_root)?;
    let archphene_cache = cache_root.join("archphene");
    create_or_require_directory(&archphene_cache)?;
    let directory = arch_root.join(AUR_SNAPSHOT_CACHE_DIRECTORY);
    create_or_require_directory(&directory)?;
    let name = format!("{}-{package_base}.tar.gz", hex_sha256(&expected_sha256));
    let destination = directory.join(&name);
    let temporary = directory.join(format!("{name}.part"));
    super::prepare_output_path(&temporary)?;
    let mut file = OpenOptions::new()
        .create_new(true)
        .read(true)
        .write(true)
        .mode(0o600)
        .open(&temporary)?;
    use std::io::Write;
    file.write_all(bytes)?;
    file.sync_all()?;
    match fs::symlink_metadata(&destination) {
        Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_file() => {
            return Err(super::PackageRuntimeError::UnsafeEntry(destination));
        }
        Ok(_) => fs::remove_file(&destination)?,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => return Err(super::PackageRuntimeError::Io(error)),
    }
    fs::rename(&temporary, &destination)?;
    File::open(&directory)?.sync_all()?;
    u64::try_from(bytes.len()).map_err(|_| super::PackageRuntimeError::OutputLimit)
}

pub(super) fn open_reviewed_snapshot(
    arch_root: &Path,
    package_base: &str,
    expected_sha256: [u8; 32],
) -> Result<File, super::PackageRuntimeError> {
    if !safe_source_filename(package_base) || expected_sha256 == [0; 32] {
        return Err(super::PackageRuntimeError::InvalidPayload);
    }
    let path = arch_root.join(AUR_SNAPSHOT_CACHE_DIRECTORY).join(format!(
        "{}-{package_base}.tar.gz",
        hex_sha256(&expected_sha256)
    ));
    let metadata = fs::symlink_metadata(&path)?;
    if metadata.file_type().is_symlink()
        || !metadata.is_file()
        || metadata.len() == 0
        || metadata.len() > u64::try_from(MAX_AUR_SNAPSHOT_BYTES).unwrap_or(u64::MAX)
    {
        return Err(super::PackageRuntimeError::UnsafeEntry(path));
    }
    let mut file = File::open(path)?;
    verify_source_file(
        &mut file,
        AurSourceChecksum::Sha256(expected_sha256),
        u64::try_from(MAX_AUR_SNAPSHOT_BYTES).unwrap_or(u64::MAX),
    )?;
    file.seek(SeekFrom::Start(0))?;
    Ok(file)
}

pub fn aur_snapshot_path(
    rpc_bytes: &[u8],
    requested_package: &str,
) -> Result<String, AurReviewError> {
    let rpc = parse_rpc(rpc_bytes, requested_package)?;
    Ok(canonical_snapshot_path(rpc.package_base))
}

pub fn aur_provider_candidates(
    rpc_bytes: &[u8],
    dependency: &str,
) -> Result<Vec<String>, AurReviewError> {
    validate_package_name(dependency)?;
    let text = checked_text(rpc_bytes, MAX_AUR_RPC_BYTES, "RPC response")?;
    validate_text(text, MAX_AUR_RPC_BYTES)?;
    let envelope: RpcEnvelope<'_> =
        serde_json::from_slice(rpc_bytes).map_err(|_| AurReviewError::InvalidRpc)?;
    if envelope.version != 5
        || envelope.response_type != "search"
        || envelope.resultcount != envelope.results.len()
        || envelope.results.len() > MAX_AUR_GRAPH_BASES
    {
        return Err(if envelope.results.len() > MAX_AUR_GRAPH_BASES {
            AurReviewError::Limit("provider candidates")
        } else {
            AurReviewError::InvalidRpc
        });
    }
    let mut candidates = Vec::with_capacity(envelope.results.len());
    for package in envelope.results {
        validate_rpc_package(&package)?;
        let expected_snapshot = format!("/cgit/aur.git/snapshot/{}.tar.gz", package.name);
        if package.snapshot_path != expected_snapshot
            || !(package.name == dependency
                || package
                    .provides
                    .as_deref()
                    .unwrap_or_default()
                    .iter()
                    .any(|provided| dependency_name(provided) == Ok(dependency)))
        {
            return Err(AurReviewError::RpcMismatch);
        }
        candidates.push(package.name.to_owned());
    }
    candidates.sort();
    candidates.dedup();
    Ok(candidates)
}

pub fn review_aur_snapshot(
    rpc_bytes: &[u8],
    snapshot_bytes: &[u8],
    requested_package: &str,
    architecture: RepositoryArchitecture,
) -> Result<AurReview, AurReviewError> {
    if snapshot_bytes.len() > MAX_AUR_SNAPSHOT_BYTES {
        return Err(AurReviewError::SizeLimit("snapshot"));
    }
    validate_package_name(requested_package)?;
    let rpc = parse_rpc(rpc_bytes, requested_package)?;
    let snapshot = read_snapshot(snapshot_bytes, rpc.package_base)?;
    let srcinfo = snapshot
        .files
        .get(Path::new(".SRCINFO"))
        .ok_or(AurReviewError::InvalidSnapshot("missing .SRCINFO"))?;
    let pkgbuild = snapshot
        .files
        .get(Path::new("PKGBUILD"))
        .ok_or(AurReviewError::InvalidSnapshot("missing PKGBUILD"))?;
    let mut review = review_aur_package(
        rpc_bytes,
        srcinfo,
        pkgbuild,
        requested_package,
        architecture,
    )?;
    verify_snapshot_files(&review, &snapshot.files)?;
    review.snapshot_sha256 = Some(Sha256::digest(snapshot_bytes).into());
    review.snapshot_commit = Some(snapshot.commit);
    for script in &mut review.install_scripts {
        script.contents = snapshot.files.get(Path::new(&script.path)).cloned();
    }
    Ok(review)
}

#[derive(Deserialize)]
struct RpcEnvelope<'a> {
    version: u32,
    #[serde(rename = "type")]
    response_type: &'a str,
    resultcount: usize,
    #[serde(borrow)]
    results: Vec<RpcPackage<'a>>,
}

#[derive(Deserialize)]
struct RpcPackage<'a> {
    #[serde(rename = "Name")]
    name: &'a str,
    #[serde(rename = "PackageBase")]
    package_base: &'a str,
    #[serde(rename = "Version")]
    version: &'a str,
    #[serde(rename = "Description")]
    description: Option<&'a str>,
    #[serde(rename = "Maintainer")]
    maintainer: Option<&'a str>,
    #[serde(rename = "URL")]
    project_url: Option<&'a str>,
    #[serde(rename = "URLPath")]
    snapshot_path: &'a str,
    #[serde(rename = "LastModified")]
    last_modified: u64,
    #[serde(rename = "OutOfDate")]
    out_of_date: Option<u64>,
    #[serde(rename = "Provides", default, borrow)]
    provides: Option<Vec<&'a str>>,
}

#[derive(Default)]
struct SrcInfo {
    package_base: Option<String>,
    package_seen: bool,
    pkgver: Option<String>,
    pkgrel: Option<String>,
    epoch: Option<String>,
    architectures: Vec<String>,
    licenses: Vec<String>,
    dependencies: Vec<String>,
    provides: Vec<String>,
    package_dependencies: BTreeMap<String, Vec<String>>,
    package_provides: BTreeMap<String, Vec<String>>,
    make_dependencies: Vec<String>,
    check_dependencies: Vec<String>,
    sources: Vec<String>,
    source_sha256: Vec<String>,
    source_sha512: Vec<String>,
    architecture_sources: Vec<String>,
    architecture_sha256: Vec<String>,
    architecture_sha512: Vec<String>,
    valid_pgp_keys: Vec<String>,
    install_script: Option<String>,
    package_install_scripts: BTreeMap<String, String>,
}

pub fn review_aur_package(
    rpc_bytes: &[u8],
    srcinfo_bytes: &[u8],
    pkgbuild_bytes: &[u8],
    requested_package: &str,
    architecture: RepositoryArchitecture,
) -> Result<AurReview, AurReviewError> {
    validate_package_name(requested_package)?;
    let rpc = parse_rpc(rpc_bytes, requested_package)?;
    let architecture_name = architecture.package_architecture();
    let srcinfo = parse_srcinfo(srcinfo_bytes, requested_package, architecture_name)?;
    let pkgbuild = checked_text(pkgbuild_bytes, MAX_AUR_PKGBUILD_BYTES, "PKGBUILD")?;
    validate_text(pkgbuild, MAX_AUR_PKGBUILD_BYTES)?;

    let package_base = srcinfo
        .package_base
        .as_deref()
        .ok_or(AurReviewError::InvalidSrcInfo)?;
    let version = srcinfo_version(&srcinfo)?;
    if rpc.package_base != package_base || rpc.version != version {
        return Err(AurReviewError::MetadataMismatch);
    }
    if !srcinfo.package_seen {
        return Err(AurReviewError::InvalidSrcInfo);
    }
    if !srcinfo
        .architectures
        .iter()
        .any(|value| value == "any" || value == architecture_name)
    {
        return Err(AurReviewError::UnsupportedArchitecture);
    }
    let required_packages = required_split_packages(&srcinfo, requested_package)?;
    let dependencies = required_runtime_dependencies(&srcinfo, &required_packages)?;
    let provided_packages = required_provided_packages(&srcinfo, &required_packages)?;
    let install_scripts = required_install_scripts(&srcinfo, &required_packages)?;

    let mut sources = Vec::with_capacity(
        srcinfo
            .sources
            .len()
            .saturating_add(srcinfo.architecture_sources.len()),
    );
    append_sources(
        &mut sources,
        &srcinfo.sources,
        &srcinfo.source_sha256,
        &srcinfo.source_sha512,
        None,
    )?;
    append_sources(
        &mut sources,
        &srcinfo.architecture_sources,
        &srcinfo.architecture_sha256,
        &srcinfo.architecture_sha512,
        Some(architecture_name),
    )?;
    if sources.len() > MAX_AUR_SOURCES {
        return Err(AurReviewError::Limit("sources"));
    }

    let build_steps = scan_build_steps(pkgbuild, requested_package);
    if !build_steps.contains(&AurBuildStep::Package) {
        return Err(AurReviewError::MissingPackageFunction);
    }
    let unverified_source_count = sources
        .iter()
        .filter(|source| source.checksum.is_none())
        .count();
    let insecure_source_count = sources
        .iter()
        .filter(|source| source.insecure_transport)
        .count();
    let review_sha256 = {
        let mut digest = Sha256::new();
        for bytes in [rpc_bytes, srcinfo_bytes, pkgbuild_bytes] {
            digest.update((bytes.len() as u64).to_le_bytes());
            digest.update(bytes);
        }
        digest.finalize().into()
    };

    Ok(AurReview {
        package_base: package_base.to_owned(),
        package_name: requested_package.to_owned(),
        version,
        description: rpc.description.unwrap_or_default().to_owned(),
        maintainer: rpc.maintainer.map(str::to_owned),
        project_url: rpc.project_url.map(str::to_owned),
        snapshot_path: canonical_snapshot_path(rpc.package_base),
        last_modified: rpc.last_modified,
        out_of_date: rpc.out_of_date.is_some(),
        licenses: srcinfo.licenses,
        dependencies,
        required_packages,
        provided_packages,
        make_dependencies: srcinfo.make_dependencies,
        check_dependencies: srcinfo.check_dependencies,
        sources,
        valid_pgp_keys: srcinfo.valid_pgp_keys,
        install_scripts,
        build_steps,
        unverified_source_count,
        insecure_source_count,
        review_sha256,
        snapshot_sha256: None,
        snapshot_commit: None,
        pkgbuild: pkgbuild_bytes.to_vec(),
    })
}

impl AurReview {
    pub fn reviewed_install_script(
        &self,
        package_name: &str,
    ) -> Result<Option<ReviewedInstallScript<'_>>, AurReviewError> {
        validate_package_name(package_name)?;
        let Some(script) = self
            .install_scripts
            .iter()
            .find(|script| script.package_name == package_name)
        else {
            return Ok(None);
        };
        match script.contents.as_deref() {
            Some(contents)
                if !contents.is_empty()
                    && contents.len() <= MAX_AUR_SNAPSHOT_ENTRY_BYTES as usize =>
            {
                Ok(Some(ReviewedInstallScript {
                    contents,
                    sha256: Sha256::digest(contents).into(),
                }))
            }
            _ => Err(AurReviewError::InvalidSnapshot(
                "incomplete install script evidence",
            )),
        }
    }

    pub fn write_wire(&self, destination: &mut [u8]) -> Result<usize, AurReviewError> {
        if destination.len() < MAX_AUR_REVIEW_BYTES {
            return Err(AurReviewError::SizeLimit("review output"));
        }
        let mut writer = WireWriter {
            destination,
            position: 0,
        };
        writer.bytes(b"ARVW0005")?;
        writer.bytes(&self.review_sha256)?;
        writer.bytes(&self.snapshot_sha256.unwrap_or([0; 32]))?;
        writer.u64(self.last_modified)?;
        writer.u32(
            u32::from(self.out_of_date)
                | (u32::from(self.maintainer.is_none()) << 1)
                | (u32::from(!self.install_scripts.is_empty()) << 2)
                | (u32::from(self.unverified_source_count > 0) << 3)
                | (u32::from(self.insecure_source_count > 0) << 4),
        )?;
        for count in [
            self.licenses.len(),
            self.dependencies.len(),
            self.required_packages.len(),
            self.make_dependencies.len(),
            self.check_dependencies.len(),
            self.sources.len(),
            self.valid_pgp_keys.len(),
            self.build_steps.len(),
            self.install_scripts.len(),
        ] {
            writer.u32(u32::try_from(count).map_err(|_| AurReviewError::Limit("wire count"))?)?;
        }
        writer.string(&self.package_base)?;
        writer.string(&self.package_name)?;
        writer.string(&self.version)?;
        writer.string(&self.description)?;
        writer.string(self.maintainer.as_deref().unwrap_or(""))?;
        writer.string(self.project_url.as_deref().unwrap_or(""))?;
        writer.string(&self.snapshot_path)?;
        writer.string(self.snapshot_commit.as_deref().unwrap_or(""))?;
        writer.blob(&self.pkgbuild)?;
        for value in &self.licenses {
            writer.string(value)?;
        }
        for value in &self.dependencies {
            writer.string(value)?;
        }
        for value in &self.required_packages {
            writer.string(value)?;
        }
        for value in &self.make_dependencies {
            writer.string(value)?;
        }
        for value in &self.check_dependencies {
            writer.string(value)?;
        }
        for source in &self.sources {
            writer.string(source.architecture.as_deref().unwrap_or(""))?;
            writer.string(&source.expression)?;
            writer.string(&source.filename)?;
            writer.u8(if source.local {
                1
            } else if source.remote_url.is_some() {
                2
            } else {
                3
            })?;
            writer.string(source.remote_url.as_deref().unwrap_or(""))?;
            match source.checksum {
                None => writer.u8(0)?,
                Some(AurSourceChecksum::Sha256(value)) => {
                    writer.u8(1)?;
                    writer.bytes(&value)?;
                }
                Some(AurSourceChecksum::Sha512(value)) => {
                    writer.u8(2)?;
                    writer.bytes(&value)?;
                }
            }
            writer.u8(u8::from(source.insecure_transport))?;
        }
        for value in &self.valid_pgp_keys {
            writer.string(value)?;
        }
        for step in &self.build_steps {
            writer.u8(match step {
                AurBuildStep::Prepare => 1,
                AurBuildStep::Build => 2,
                AurBuildStep::Check => 3,
                AurBuildStep::Package => 4,
            })?;
        }
        let mut previous_package = None;
        for script in &self.install_scripts {
            validate_package_name(&script.package_name)?;
            if !self
                .required_packages
                .iter()
                .any(|package| package == &script.package_name)
                || previous_package.is_some_and(|previous| previous >= script.package_name.as_str())
            {
                return Err(AurReviewError::InvalidSnapshot(
                    "invalid install script package",
                ));
            }
            safe_snapshot_path(&script.path)?;
            let contents = script
                .contents
                .as_deref()
                .ok_or(AurReviewError::InvalidSnapshot(
                    "incomplete install script evidence",
                ))?;
            if contents.is_empty() || contents.len() > MAX_AUR_SNAPSHOT_ENTRY_BYTES as usize {
                return Err(AurReviewError::InvalidSnapshot(
                    "invalid install script contents",
                ));
            }
            writer.string(&script.package_name)?;
            writer.string(&script.path)?;
            writer.blob(contents)?;
            previous_package = Some(script.package_name.as_str());
        }
        Ok(writer.position)
    }
}

pub fn plan_reviewed_aur_graph(
    reviews: &[AurReview],
    root_package: &str,
    official_providers: &std::collections::BTreeSet<String>,
) -> Result<AurBuildGraph, AurBuildGraphError> {
    if reviews.is_empty() || reviews.len() > MAX_AUR_GRAPH_BASES {
        return Err(AurBuildGraphError::Limit("package-base"));
    }
    validate_package_name(root_package).map_err(|_| AurBuildGraphError::InvalidReview)?;
    if official_providers.len() > MAX_AUR_DEPENDENCIES
        || official_providers
            .iter()
            .any(|provider| validate_package_name(provider).is_err())
    {
        return Err(AurBuildGraphError::InvalidReview);
    }

    let mut bases = BTreeMap::new();
    let mut providers: BTreeMap<String, Option<usize>> = BTreeMap::new();
    let mut root = None;
    for (index, review) in reviews.iter().enumerate() {
        if validate_package_name(&review.package_base).is_err()
            || validate_package_name(&review.package_name).is_err()
            || review.version.is_empty()
            || review.version.len() > MAX_VERSION_BYTES
            || review.required_packages.is_empty()
            || review.required_packages.len() > MAX_AUR_DEPENDENCIES
            || review.provided_packages.is_empty()
            || review.provided_packages.len() > MAX_AUR_DEPENDENCIES
            || !review
                .required_packages
                .windows(2)
                .all(|pair| pair[0] < pair[1])
            || !review
                .provided_packages
                .windows(2)
                .all(|pair| pair[0] < pair[1])
            || !review
                .required_packages
                .iter()
                .all(|package| validate_package_name(package).is_ok())
            || !review
                .provided_packages
                .iter()
                .all(|package| validate_package_name(package).is_ok())
            || !review
                .required_packages
                .iter()
                .all(|package| review.provided_packages.binary_search(package).is_ok())
            || !review
                .required_packages
                .iter()
                .any(|package| package == &review.package_name)
        {
            return Err(AurBuildGraphError::InvalidReview);
        }
        if bases.insert(review.package_base.clone(), index).is_some() {
            return Err(AurBuildGraphError::DuplicatePackageBase);
        }
        if review.package_name == root_package && root.replace(index).is_some() {
            return Err(AurBuildGraphError::InvalidReview);
        }
        for provider in &review.provided_packages {
            providers
                .entry(provider.clone())
                .and_modify(|current| {
                    if current.is_some_and(|existing| existing != index) {
                        *current = None;
                    }
                })
                .or_insert(Some(index));
        }
    }
    let root = root.ok_or(AurBuildGraphError::InvalidReview)?;

    let mut graph_edges = Vec::new();
    let mut dependency_pairs = std::collections::BTreeSet::new();
    for (index, review) in reviews.iter().enumerate() {
        let dependencies = review
            .dependencies
            .iter()
            .chain(review.make_dependencies.iter())
            .chain(review.check_dependencies.iter());
        for declaration in dependencies {
            let dependency =
                dependency_name(declaration).map_err(|_| AurBuildGraphError::InvalidReview)?;
            if official_providers.contains(dependency)
                || review
                    .provided_packages
                    .binary_search_by(|provider| provider.as_str().cmp(dependency))
                    .is_ok()
            {
                continue;
            }
            let provider = match providers.get(dependency) {
                Some(Some(provider)) => *provider,
                Some(None) => {
                    return Err(AurBuildGraphError::AmbiguousProvider(dependency.to_owned()));
                }
                None => {
                    return Err(AurBuildGraphError::MissingProvider(dependency.to_owned()));
                }
            };
            if provider == index {
                continue;
            }
            if graph_edges.len() >= MAX_AUR_GRAPH_EDGES {
                return Err(AurBuildGraphError::Limit("edge"));
            }
            graph_edges.push(AurBuildGraphEdge {
                package_base: review.package_base.clone(),
                dependency_base: reviews[provider].package_base.clone(),
                dependency: dependency.to_owned(),
                requirement: declaration.clone(),
            });
            dependency_pairs.insert((index, provider));
        }
    }
    graph_edges.sort_by(|left, right| {
        (
            &left.package_base,
            &left.dependency_base,
            &left.dependency,
            &left.requirement,
        )
            .cmp(&(
                &right.package_base,
                &right.dependency_base,
                &right.dependency,
                &right.requirement,
            ))
    });
    graph_edges.dedup();

    let mut reachable = std::collections::BTreeSet::from([root]);
    let mut pending = vec![root];
    while let Some(current) = pending.pop() {
        for &(dependent, dependency) in &dependency_pairs {
            if dependent == current && reachable.insert(dependency) {
                pending.push(dependency);
            }
        }
    }
    if reachable.len() != reviews.len() {
        return Err(AurBuildGraphError::DisconnectedReview);
    }

    let mut indegree = vec![0_usize; reviews.len()];
    for &(dependent, _) in &dependency_pairs {
        indegree[dependent] = indegree[dependent]
            .checked_add(1)
            .ok_or(AurBuildGraphError::Limit("edge"))?;
    }
    let mut ready = std::collections::BTreeSet::new();
    for (index, degree) in indegree.iter().enumerate() {
        if *degree == 0 {
            ready.insert((reviews[index].package_base.as_str(), index));
        }
    }
    let mut package_bases = Vec::with_capacity(reviews.len());
    while let Some((_, current)) = ready.pop_first() {
        package_bases.push(reviews[current].package_base.clone());
        for &(dependent, dependency) in &dependency_pairs {
            if dependency != current {
                continue;
            }
            indegree[dependent] = indegree[dependent]
                .checked_sub(1)
                .ok_or(AurBuildGraphError::InvalidReview)?;
            if indegree[dependent] == 0 {
                ready.insert((reviews[dependent].package_base.as_str(), dependent));
            }
        }
    }
    if package_bases.len() != reviews.len() {
        return Err(AurBuildGraphError::Cycle);
    }
    Ok(AurBuildGraph {
        package_bases,
        edges: graph_edges,
    })
}

struct WireWriter<'a> {
    destination: &'a mut [u8],
    position: usize,
}

impl WireWriter<'_> {
    fn u8(&mut self, value: u8) -> Result<(), AurReviewError> {
        self.bytes(&[value])
    }

    fn u32(&mut self, value: u32) -> Result<(), AurReviewError> {
        self.bytes(&value.to_le_bytes())
    }

    fn u64(&mut self, value: u64) -> Result<(), AurReviewError> {
        self.bytes(&value.to_le_bytes())
    }

    fn string(&mut self, value: &str) -> Result<(), AurReviewError> {
        self.blob(value.as_bytes())
    }

    fn blob(&mut self, value: &[u8]) -> Result<(), AurReviewError> {
        self.u32(u32::try_from(value.len()).map_err(|_| AurReviewError::SizeLimit("wire field"))?)?;
        self.bytes(value)
    }

    fn bytes(&mut self, value: &[u8]) -> Result<(), AurReviewError> {
        let end = self
            .position
            .checked_add(value.len())
            .ok_or(AurReviewError::SizeLimit("review output"))?;
        let target = self
            .destination
            .get_mut(self.position..end)
            .ok_or(AurReviewError::SizeLimit("review output"))?;
        target.copy_from_slice(value);
        self.position = end;
        Ok(())
    }
}

struct Snapshot {
    commit: String,
    files: BTreeMap<std::path::PathBuf, Vec<u8>>,
}

fn read_snapshot(snapshot_bytes: &[u8], expected_base: &str) -> Result<Snapshot, AurReviewError> {
    let decoder = GzDecoder::new(snapshot_bytes);
    let mut archive = Archive::new(decoder);
    let mut files = BTreeMap::new();
    let mut commit = None;
    let mut entry_count = 0_usize;
    let mut expanded_bytes = 0_usize;
    let entries = archive
        .entries()
        .map_err(|_| AurReviewError::InvalidSnapshot("invalid gzip or tar stream"))?;
    for entry in entries {
        entry_count = entry_count.saturating_add(1);
        if entry_count > MAX_AUR_SNAPSHOT_ENTRIES {
            return Err(AurReviewError::Limit("snapshot entries"));
        }
        let mut entry = entry.map_err(|_| AurReviewError::InvalidSnapshot("invalid tar entry"))?;
        let entry_type = entry.header().entry_type();
        let entry_size = entry
            .header()
            .size()
            .map_err(|_| AurReviewError::InvalidSnapshot("invalid entry size"))?;
        if entry_type == EntryType::XGlobalHeader {
            if commit.is_some() || !files.is_empty() || entry_size > 128 {
                return Err(AurReviewError::InvalidSnapshot(
                    "invalid global provenance header",
                ));
            }
            let mut bytes = Vec::with_capacity(entry_size as usize);
            entry
                .by_ref()
                .take(entry_size + 1)
                .read_to_end(&mut bytes)
                .map_err(|_| AurReviewError::InvalidSnapshot("truncated provenance header"))?;
            commit = Some(parse_snapshot_commit(&bytes)?);
            continue;
        }
        if entry_type.is_dir() {
            continue;
        }
        if !entry_type.is_file() {
            return Err(AurReviewError::InvalidSnapshot("unsupported entry type"));
        }
        if entry_size > MAX_AUR_SNAPSHOT_ENTRY_BYTES {
            return Err(AurReviewError::InvalidSnapshot("entry exceeds size limit"));
        }
        let path = entry
            .path()
            .map_err(|_| AurReviewError::InvalidSnapshot("invalid entry path"))?
            .into_owned();
        let mut components = path.components();
        if components.next() != Some(Component::Normal(expected_base.as_ref())) {
            return Err(AurReviewError::InvalidSnapshot(
                "entry is outside the package base",
            ));
        }
        let relative = components.as_path().to_path_buf();
        if relative.as_os_str().is_empty()
            || relative
                .components()
                .any(|component| !matches!(component, Component::Normal(_)))
        {
            return Err(AurReviewError::InvalidSnapshot("unsafe entry path"));
        }
        let size = entry_size as usize;
        expanded_bytes = expanded_bytes
            .checked_add(size)
            .ok_or(AurReviewError::InvalidSnapshot("expanded size overflow"))?;
        if expanded_bytes > MAX_AUR_SNAPSHOT_EXPANDED_BYTES {
            return Err(AurReviewError::SizeLimit("expanded snapshot"));
        }
        let mut bytes = Vec::with_capacity(size);
        entry
            .by_ref()
            .take(size as u64 + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| AurReviewError::InvalidSnapshot("truncated entry"))?;
        if bytes.len() != size || files.insert(relative, bytes).is_some() {
            return Err(AurReviewError::InvalidSnapshot(
                "entry size mismatch or duplicate path",
            ));
        }
    }
    Ok(Snapshot {
        commit: commit.ok_or(AurReviewError::InvalidSnapshot(
            "missing AUR commit provenance",
        ))?,
        files,
    })
}

fn parse_snapshot_commit(bytes: &[u8]) -> Result<String, AurReviewError> {
    let text = std::str::from_utf8(bytes)
        .map_err(|_| AurReviewError::InvalidSnapshot("invalid provenance header"))?;
    let (length, record) = text
        .split_once(' ')
        .ok_or(AurReviewError::InvalidSnapshot("invalid provenance header"))?;
    let declared_length = length
        .parse::<usize>()
        .map_err(|_| AurReviewError::InvalidSnapshot("invalid provenance header"))?;
    let commit = record
        .strip_prefix("comment=")
        .and_then(|value| value.strip_suffix('\n'))
        .ok_or(AurReviewError::InvalidSnapshot("invalid provenance header"))?;
    if declared_length != bytes.len()
        || commit.len() != 40
        || !commit.bytes().all(|byte| byte.is_ascii_hexdigit())
    {
        return Err(AurReviewError::InvalidSnapshot(
            "invalid AUR commit provenance",
        ));
    }
    Ok(commit.to_ascii_lowercase())
}

fn verify_snapshot_files(
    review: &AurReview,
    files: &BTreeMap<std::path::PathBuf, Vec<u8>>,
) -> Result<(), AurReviewError> {
    for install_script in &review.install_scripts {
        if !files.contains_key(safe_snapshot_path(&install_script.path)?) {
            return Err(AurReviewError::InvalidSnapshot("missing install script"));
        }
    }
    for source in &review.sources {
        if !source.local {
            continue;
        }
        let (_, location) = split_source_expression(&source.expression)?;
        let path = safe_snapshot_path(location)?;
        let bytes = files
            .get(path)
            .ok_or(AurReviewError::InvalidSnapshot("missing local source"))?;
        if let Some(expected) = source.checksum
            && !expected.matches(bytes)
        {
            return Err(AurReviewError::InvalidSnapshot(
                "local source checksum mismatch",
            ));
        }
    }
    Ok(())
}

fn safe_snapshot_path(value: &str) -> Result<&Path, AurReviewError> {
    let path = Path::new(value);
    if path.as_os_str().is_empty()
        || path.is_absolute()
        || path
            .components()
            .any(|component| !matches!(component, Component::Normal(_)))
    {
        return Err(AurReviewError::InvalidSnapshot("unsafe local path"));
    }
    Ok(path)
}

fn parse_rpc<'a>(
    bytes: &'a [u8],
    requested_package: &str,
) -> Result<RpcPackage<'a>, AurReviewError> {
    let text = checked_text(bytes, MAX_AUR_RPC_BYTES, "RPC response")?;
    validate_text(text, MAX_AUR_RPC_BYTES)?;
    let mut envelope: RpcEnvelope<'a> =
        serde_json::from_slice(bytes).map_err(|_| AurReviewError::InvalidRpc)?;
    if envelope.version != 5
        || envelope.response_type != "multiinfo"
        || envelope.resultcount != 1
        || envelope.results.len() != 1
    {
        return Err(AurReviewError::InvalidRpc);
    }
    let package = envelope.results.pop().ok_or(AurReviewError::InvalidRpc)?;
    validate_rpc_package(&package)?;
    // AUR's RPC URLPath follows the queried split-package name, while the
    // canonical package-base endpoint expands with a package-base directory.
    // Validate the server-provided identity here, then have the caller fetch
    // the canonical package-base snapshot used by the isolated builder.
    let expected_snapshot = format!("/cgit/aur.git/snapshot/{requested_package}.tar.gz");
    if package.name != requested_package || package.snapshot_path != expected_snapshot {
        return Err(AurReviewError::RpcMismatch);
    }
    Ok(package)
}

fn validate_rpc_package(package: &RpcPackage<'_>) -> Result<(), AurReviewError> {
    validate_package_name(package.name)?;
    validate_package_name(package.package_base)?;
    validate_bounded_value(package.version, MAX_VERSION_BYTES)?;
    validate_optional_value(package.description, MAX_DESCRIPTION_BYTES)?;
    validate_optional_value(package.maintainer, MAX_MAINTAINER_BYTES)?;
    validate_optional_value(package.project_url, MAX_FIELD_BYTES)?;
    validate_bounded_value(package.snapshot_path, MAX_FIELD_BYTES)?;
    if package
        .provides
        .as_deref()
        .is_some_and(|provided| provided.len() > MAX_AUR_DEPENDENCIES)
    {
        return Err(AurReviewError::Limit("provides"));
    }
    for provided in package.provides.as_deref().unwrap_or_default() {
        dependency_name(provided)?;
    }
    Ok(())
}

fn canonical_snapshot_path(package_base: &str) -> String {
    format!("/cgit/aur.git/snapshot/{package_base}.tar.gz")
}

fn parse_srcinfo(
    bytes: &[u8],
    requested_package: &str,
    architecture: &str,
) -> Result<SrcInfo, AurReviewError> {
    let text = checked_text(bytes, MAX_AUR_SRCINFO_BYTES, ".SRCINFO")?;
    validate_text(text, MAX_AUR_SRCINFO_BYTES)?;
    let mut info = SrcInfo::default();
    let mut current_package: Option<&str> = None;
    let depends_architecture = format!("depends_{architecture}");
    let provides_architecture = format!("provides_{architecture}");
    let make_dependencies_architecture = format!("makedepends_{architecture}");
    let check_dependencies_architecture = format!("checkdepends_{architecture}");
    let sources_architecture = format!("source_{architecture}");
    let source_sha256_architecture = format!("sha256sums_{architecture}");
    let source_sha512_architecture = format!("sha512sums_{architecture}");

    for raw_line in text.lines() {
        let line = raw_line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let (key, value) = line.split_once('=').ok_or(AurReviewError::InvalidSrcInfo)?;
        let key = key.trim();
        let value = value.trim();
        if key.is_empty()
            || key.len() > 64
            || !key
                .bytes()
                .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'_')
            || value.is_empty()
        {
            return Err(AurReviewError::InvalidSrcInfo);
        }
        validate_bounded_value(value, MAX_FIELD_BYTES)?;
        if key == "pkgname" {
            validate_package_name(value)?;
            current_package = Some(value);
            if info.package_dependencies.len() >= MAX_AUR_DEPENDENCIES
                && !info.package_dependencies.contains_key(value)
            {
                return Err(AurReviewError::Limit("split packages"));
            }
            info.package_dependencies
                .entry(value.to_owned())
                .or_default();
            info.package_provides.entry(value.to_owned()).or_default();
            if value == requested_package {
                info.package_seen = true;
            }
            continue;
        }
        if key == "depends" || key == depends_architecture {
            let dependencies = if let Some(package) = current_package {
                info.package_dependencies
                    .get_mut(package)
                    .ok_or(AurReviewError::InvalidSrcInfo)?
            } else {
                &mut info.dependencies
            };
            push_limited(dependencies, value, MAX_AUR_DEPENDENCIES, "dependencies")?;
            continue;
        }
        if key == "provides" || key == provides_architecture {
            let provides = if let Some(package) = current_package {
                info.package_provides
                    .get_mut(package)
                    .ok_or(AurReviewError::InvalidSrcInfo)?
            } else {
                &mut info.provides
            };
            push_limited(provides, value, MAX_AUR_DEPENDENCIES, "provides")?;
            continue;
        }
        if key == "install" {
            if let Some(package) = current_package {
                if info
                    .package_install_scripts
                    .insert(package.to_owned(), value.to_owned())
                    .is_some()
                {
                    return Err(AurReviewError::InvalidSrcInfo);
                }
            } else {
                set_once(&mut info.install_script, value)?;
            }
            continue;
        }
        let applies = current_package.is_none() || current_package == Some(requested_package);
        if !applies {
            continue;
        }
        match key {
            "pkgbase" if current_package.is_none() => {
                set_once(&mut info.package_base, value)?;
            }
            "pkgver" if current_package.is_none() => set_once(&mut info.pkgver, value)?,
            "pkgrel" if current_package.is_none() => set_once(&mut info.pkgrel, value)?,
            "epoch" if current_package.is_none() => set_once(&mut info.epoch, value)?,
            "arch" => push_limited(&mut info.architectures, value, 16, "architectures")?,
            "license" => push_limited(&mut info.licenses, value, 32, "licenses")?,
            "makedepends" => push_limited(
                &mut info.make_dependencies,
                value,
                MAX_AUR_DEPENDENCIES,
                "make dependencies",
            )?,
            "checkdepends" => push_limited(
                &mut info.check_dependencies,
                value,
                MAX_AUR_DEPENDENCIES,
                "check dependencies",
            )?,
            "source" => push_limited(&mut info.sources, value, MAX_AUR_SOURCES, "sources")?,
            "sha256sums" => push_ordered_limited(
                &mut info.source_sha256,
                value,
                MAX_AUR_SOURCES,
                "source SHA-256 hashes",
            )?,
            "sha512sums" => push_ordered_limited(
                &mut info.source_sha512,
                value,
                MAX_AUR_SOURCES,
                "source SHA-512 hashes",
            )?,
            "validpgpkeys" => push_limited(&mut info.valid_pgp_keys, value, 32, "PGP keys")?,
            _ if key == make_dependencies_architecture => push_limited(
                &mut info.make_dependencies,
                value,
                MAX_AUR_DEPENDENCIES,
                "make dependencies",
            )?,
            _ if key == check_dependencies_architecture => push_limited(
                &mut info.check_dependencies,
                value,
                MAX_AUR_DEPENDENCIES,
                "check dependencies",
            )?,
            _ if key == sources_architecture => push_limited(
                &mut info.architecture_sources,
                value,
                MAX_AUR_SOURCES,
                "architecture sources",
            )?,
            _ if key == source_sha256_architecture => push_ordered_limited(
                &mut info.architecture_sha256,
                value,
                MAX_AUR_SOURCES,
                "architecture source SHA-256 hashes",
            )?,
            _ if key == source_sha512_architecture => push_ordered_limited(
                &mut info.architecture_sha512,
                value,
                MAX_AUR_SOURCES,
                "architecture source SHA-512 hashes",
            )?,
            _ => {}
        }
    }

    if info.package_base.is_none()
        || info.pkgver.is_none()
        || info.pkgrel.is_none()
        || info.architectures.is_empty()
        || !info.package_seen
    {
        return Err(AurReviewError::InvalidSrcInfo);
    }
    Ok(info)
}

fn append_sources(
    output: &mut Vec<AurSource>,
    expressions: &[String],
    sha256_hashes: &[String],
    sha512_hashes: &[String],
    architecture: Option<&str>,
) -> Result<(), AurReviewError> {
    if !sha256_hashes.is_empty() && expressions.len() != sha256_hashes.len()
        || !sha512_hashes.is_empty() && expressions.len() != sha512_hashes.len()
        || !expressions.is_empty() && sha256_hashes.is_empty() && sha512_hashes.is_empty()
    {
        return Err(AurReviewError::InvalidSrcInfo);
    }
    for (index, expression) in expressions.iter().enumerate() {
        let checksum = if let Some(hash) = sha512_hashes.get(index).filter(|hash| *hash != "SKIP") {
            Some(AurSourceChecksum::Sha512(parse_sha512(hash)?))
        } else if let Some(hash) = sha256_hashes.get(index).filter(|hash| *hash != "SKIP") {
            Some(AurSourceChecksum::Sha256(parse_sha256(hash)?))
        } else {
            None
        };
        let (alias, location) = split_source_expression(expression)?;
        let local = !location.contains("://");
        let remote_url = (!local && valid_https_source_url(location)).then(|| location.to_owned());
        let filename = source_filename(alias, location, local)?;
        output.push(AurSource {
            expression: expression.clone(),
            architecture: architecture.map(str::to_owned),
            filename,
            remote_url,
            local,
            checksum,
            insecure_transport: source_uses_insecure_transport(expression),
        });
    }
    Ok(())
}

fn source_uses_insecure_transport(expression: &str) -> bool {
    let location = split_source_expression(expression).map_or(expression, |(_, location)| location);
    location.starts_with("http://")
        || location.starts_with("git://")
        || location.contains("://")
            && !location.starts_with("https://")
            && !location.starts_with("git+https://")
            && !location.starts_with("ssh://")
}

fn split_source_expression(expression: &str) -> Result<(Option<&str>, &str), AurReviewError> {
    validate_bounded_value(expression, MAX_FIELD_BYTES)?;
    let Some((possible_alias, location)) = expression.split_once("::") else {
        return Ok((None, expression));
    };
    if possible_alias.contains("://") {
        return Ok((None, expression));
    }
    if possible_alias.is_empty() || location.is_empty() || location.contains('\n') {
        return Err(AurReviewError::InvalidSrcInfo);
    }
    Ok((Some(possible_alias), location))
}

fn source_filename(
    alias: Option<&str>,
    location: &str,
    local: bool,
) -> Result<String, AurReviewError> {
    let candidate = if let Some(alias) = alias {
        alias
    } else if local {
        safe_snapshot_path(location)?
            .file_name()
            .and_then(|name| name.to_str())
            .ok_or(AurReviewError::InvalidSrcInfo)?
    } else {
        let path = location.split(['?', '#']).next().unwrap_or_default();
        path.rsplit('/')
            .next()
            .filter(|value| !value.is_empty())
            .ok_or(AurReviewError::InvalidSrcInfo)?
    };
    if !safe_source_filename(candidate) {
        return Err(AurReviewError::InvalidSrcInfo);
    }
    Ok(candidate.to_owned())
}

fn valid_https_source_url(location: &str) -> bool {
    let Some(authority_and_path) = location.strip_prefix("https://") else {
        return false;
    };
    if authority_and_path.is_empty()
        || location.len() > MAX_FIELD_BYTES
        || !location.is_ascii()
        || location
            .bytes()
            .any(|byte| byte.is_ascii_control() || byte.is_ascii_whitespace() || byte == b'\\')
        || location.contains('#')
    {
        return false;
    }
    let authority = authority_and_path
        .split(['/', '?'])
        .next()
        .unwrap_or_default();
    valid_https_authority(authority)
}

fn valid_https_authority(authority: &str) -> bool {
    if authority.is_empty() || authority.contains('@') {
        return false;
    }
    if let Some(ipv6) = authority.strip_prefix('[') {
        let Some((address, remainder)) = ipv6.split_once(']') else {
            return false;
        };
        return !address.is_empty() && (remainder.is_empty() || remainder == ":443");
    }
    match authority.rsplit_once(':') {
        Some((host, "443")) => !host.is_empty() && !host.contains(':'),
        Some(_) => false,
        None => true,
    }
}

fn safe_source_filename(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 240
        && value != "."
        && value != ".."
        && value.bytes().all(|byte| {
            byte.is_ascii_alphanumeric() || matches!(byte, b'@' | b'+' | b',' | b'.' | b'_' | b'-')
        })
}

fn require_directory(path: &Path) -> Result<(), super::PackageRuntimeError> {
    let metadata = fs::symlink_metadata(path)?;
    if metadata.file_type().is_symlink() || !metadata.is_dir() {
        return Err(super::PackageRuntimeError::UnsafeEntry(path.to_path_buf()));
    }
    Ok(())
}

fn create_or_require_directory(path: &Path) -> Result<(), super::PackageRuntimeError> {
    match fs::symlink_metadata(path) {
        Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_dir() => {
            return Err(super::PackageRuntimeError::UnsafeEntry(path.to_path_buf()));
        }
        Ok(_) => {}
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            fs::create_dir(path)?;
            fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
        }
        Err(error) => return Err(super::PackageRuntimeError::Io(error)),
    }
    Ok(())
}

fn hex_sha256(value: &[u8; 32]) -> String {
    hex_bytes(value)
}

fn hex_bytes(value: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut output = String::with_capacity(value.len() * 2);
    for byte in value {
        output.push(HEX[(byte >> 4) as usize] as char);
        output.push(HEX[(byte & 0x0f) as usize] as char);
    }
    output
}

fn parse_sha256(value: &str) -> Result<[u8; 32], AurReviewError> {
    parse_hex(value)
}

fn parse_sha512(value: &str) -> Result<[u8; 64], AurReviewError> {
    parse_hex(value)
}

fn parse_hex<const N: usize>(value: &str) -> Result<[u8; N], AurReviewError> {
    if value.len() != N * 2 {
        return Err(AurReviewError::InvalidSrcInfo);
    }
    let mut digest = [0_u8; N];
    for (index, pair) in value.as_bytes().chunks_exact(2).enumerate() {
        digest[index] = (hex_nibble(pair[0])? << 4) | hex_nibble(pair[1])?;
    }
    Ok(digest)
}

fn hex_nibble(value: u8) -> Result<u8, AurReviewError> {
    match value {
        b'0'..=b'9' => Ok(value - b'0'),
        b'a'..=b'f' => Ok(value - b'a' + 10),
        b'A'..=b'F' => Ok(value - b'A' + 10),
        _ => Err(AurReviewError::InvalidSrcInfo),
    }
}

fn scan_build_steps(pkgbuild: &str, package_name: &str) -> Vec<AurBuildStep> {
    let package_function = format!("package_{package_name}");
    let normalized_package_function = format!("package_{}", package_name.replace('-', "_"));
    let mut steps = Vec::with_capacity(4);
    for raw_line in pkgbuild.lines() {
        let line = raw_line.trim_start();
        if line.starts_with('#') {
            continue;
        }
        let function = line
            .strip_prefix("function ")
            .unwrap_or(line)
            .split_once('(')
            .and_then(|(name, remainder)| {
                let name = name.trim();
                let remainder = remainder.trim_start();
                (remainder.starts_with(')') && remainder.contains('{')).then_some(name)
            });
        let step = match function {
            Some("prepare") => Some(AurBuildStep::Prepare),
            Some("build") => Some(AurBuildStep::Build),
            Some("check") => Some(AurBuildStep::Check),
            Some("package") => Some(AurBuildStep::Package),
            Some(name) if name == package_function || name == normalized_package_function => {
                Some(AurBuildStep::Package)
            }
            _ => None,
        };
        if let Some(step) = step
            && !steps.contains(&step)
        {
            steps.push(step);
        }
    }
    steps
}

fn srcinfo_version(info: &SrcInfo) -> Result<String, AurReviewError> {
    let pkgver = info
        .pkgver
        .as_deref()
        .ok_or(AurReviewError::InvalidSrcInfo)?;
    let pkgrel = info
        .pkgrel
        .as_deref()
        .ok_or(AurReviewError::InvalidSrcInfo)?;
    let version = match info.epoch.as_deref() {
        Some(epoch) => format!("{epoch}:{pkgver}-{pkgrel}"),
        None => format!("{pkgver}-{pkgrel}"),
    };
    validate_bounded_value(&version, MAX_VERSION_BYTES)?;
    Ok(version)
}

fn required_split_packages(
    info: &SrcInfo,
    requested_package: &str,
) -> Result<Vec<String>, AurReviewError> {
    if !info.package_dependencies.contains_key(requested_package) {
        return Err(AurReviewError::InvalidSrcInfo);
    }
    let mut required = std::collections::BTreeSet::new();
    let mut pending = vec![requested_package];
    while let Some(package) = pending.pop() {
        if !required.insert(package.to_owned()) {
            continue;
        }
        if required.len() > MAX_AUR_DEPENDENCIES {
            return Err(AurReviewError::Limit("required split packages"));
        }
        let dependencies = info
            .package_dependencies
            .get(package)
            .ok_or(AurReviewError::InvalidSrcInfo)?;
        for dependency in dependencies {
            let name = dependency_name(dependency)?;
            if let Some(provider) = split_provider(info, name)?
                && !required.contains(provider)
            {
                pending.push(provider);
            }
        }
    }
    Ok(required.into_iter().collect())
}

fn required_install_scripts(
    info: &SrcInfo,
    required_packages: &[String],
) -> Result<Vec<AurInstallScript>, AurReviewError> {
    let mut scripts = Vec::new();
    for package_name in required_packages {
        let path = info
            .package_install_scripts
            .get(package_name)
            .or(info.install_script.as_ref());
        let Some(path) = path else {
            continue;
        };
        safe_snapshot_path(path)?;
        scripts.push(AurInstallScript {
            package_name: package_name.clone(),
            path: path.clone(),
            contents: None,
        });
    }
    Ok(scripts)
}

fn required_provided_packages(
    info: &SrcInfo,
    required_packages: &[String],
) -> Result<Vec<String>, AurReviewError> {
    let mut provided = std::collections::BTreeSet::new();
    for package_name in required_packages {
        provided.insert(package_name.clone());
        for declaration in info
            .package_provides
            .get(package_name)
            .ok_or(AurReviewError::InvalidSrcInfo)?
        {
            provided.insert(dependency_name(declaration)?.to_owned());
        }
    }
    if info.package_dependencies.len() == 1 && required_packages.len() == 1 {
        for declaration in &info.provides {
            provided.insert(dependency_name(declaration)?.to_owned());
        }
    }
    if provided.is_empty() || provided.len() > MAX_AUR_DEPENDENCIES {
        return Err(AurReviewError::Limit("provided packages"));
    }
    Ok(provided.into_iter().collect())
}

fn required_runtime_dependencies(
    info: &SrcInfo,
    required_packages: &[String],
) -> Result<Vec<String>, AurReviewError> {
    let mut dependencies = Vec::new();
    for dependency in &info.dependencies {
        if !dependency_is_internal(info, required_packages, dependency)? {
            push_limited(
                &mut dependencies,
                dependency,
                MAX_AUR_DEPENDENCIES,
                "dependencies",
            )?;
        }
    }
    for package in required_packages {
        for dependency in info
            .package_dependencies
            .get(package)
            .ok_or(AurReviewError::InvalidSrcInfo)?
        {
            if !dependency_is_internal(info, required_packages, dependency)? {
                push_limited(
                    &mut dependencies,
                    dependency,
                    MAX_AUR_DEPENDENCIES,
                    "dependencies",
                )?;
            }
        }
    }
    Ok(dependencies)
}

fn dependency_is_internal(
    info: &SrcInfo,
    required_packages: &[String],
    dependency: &str,
) -> Result<bool, AurReviewError> {
    let name = dependency_name(dependency)?;
    Ok(split_provider(info, name)?
        .is_some_and(|provider| required_packages.iter().any(|package| package == provider)))
}

fn split_provider<'a>(
    info: &'a SrcInfo,
    dependency: &str,
) -> Result<Option<&'a str>, AurReviewError> {
    let mut provider = info
        .package_dependencies
        .get_key_value(dependency)
        .map(|(package, _)| package.as_str());
    for (package, declarations) in &info.package_provides {
        for declaration in declarations {
            if dependency_name(declaration)? != dependency {
                continue;
            }
            if provider.is_some_and(|existing| existing != package) {
                return Err(AurReviewError::InvalidSrcInfo);
            }
            provider = Some(package.as_str());
        }
    }
    for declaration in &info.provides {
        if dependency_name(declaration)? != dependency {
            continue;
        }
        let mut packages = info.package_dependencies.keys();
        let package = packages.next().ok_or(AurReviewError::InvalidSrcInfo)?;
        if packages.next().is_some() {
            return Err(AurReviewError::InvalidSrcInfo);
        }
        if provider.is_some_and(|existing| existing != package) {
            return Err(AurReviewError::InvalidSrcInfo);
        }
        provider = Some(package.as_str());
    }
    Ok(provider)
}

fn dependency_name(value: &str) -> Result<&str, AurReviewError> {
    let end = value
        .bytes()
        .position(|byte| matches!(byte, b'<' | b'=' | b'>'))
        .unwrap_or(value.len());
    let name = &value[..end];
    validate_package_name(name)?;
    Ok(name)
}

fn checked_text<'a>(
    bytes: &'a [u8],
    limit: usize,
    part: &'static str,
) -> Result<&'a str, AurReviewError> {
    if bytes.len() > limit {
        return Err(AurReviewError::SizeLimit(part));
    }
    std::str::from_utf8(bytes).map_err(|_| AurReviewError::InvalidUtf8(part))
}

fn validate_text(value: &str, limit: usize) -> Result<(), AurReviewError> {
    if value.len() > limit
        || value
            .chars()
            .any(|character| character.is_control() && !matches!(character, '\n' | '\t'))
    {
        return Err(AurReviewError::InvalidSrcInfo);
    }
    Ok(())
}

fn validate_package_name(value: &str) -> Result<(), AurReviewError> {
    if value.is_empty()
        || value.len() > MAX_NAME_BYTES
        || !value.bytes().all(|byte| {
            byte.is_ascii_alphanumeric() || matches!(byte, b'@' | b'+' | b'.' | b'_' | b'-')
        })
    {
        return Err(AurReviewError::InvalidSrcInfo);
    }
    Ok(())
}

fn validate_optional_value(value: Option<&str>, limit: usize) -> Result<(), AurReviewError> {
    if let Some(value) = value {
        validate_bounded_value(value, limit)?;
    }
    Ok(())
}

fn validate_bounded_value(value: &str, limit: usize) -> Result<(), AurReviewError> {
    if value.is_empty() || value.len() > limit || value.chars().any(char::is_control) {
        return Err(AurReviewError::InvalidSrcInfo);
    }
    Ok(())
}

fn set_once(target: &mut Option<String>, value: &str) -> Result<(), AurReviewError> {
    if target.is_some() {
        return Err(AurReviewError::InvalidSrcInfo);
    }
    *target = Some(value.to_owned());
    Ok(())
}

fn push_limited(
    target: &mut Vec<String>,
    value: &str,
    limit: usize,
    field: &'static str,
) -> Result<(), AurReviewError> {
    if target.len() >= limit {
        return Err(AurReviewError::Limit(field));
    }
    if !target.iter().any(|current| current == value) {
        target.push(value.to_owned());
    }
    Ok(())
}

fn push_ordered_limited(
    target: &mut Vec<String>,
    value: &str,
    limit: usize,
    field: &'static str,
) -> Result<(), AurReviewError> {
    if target.len() >= limit {
        return Err(AurReviewError::Limit(field));
    }
    target.push(value.to_owned());
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use flate2::Compression;
    use flate2::write::GzEncoder;
    use std::io::Write;
    use std::sync::atomic::{AtomicU64, Ordering};
    use tar::{Builder, Header};

    static TEST_ROOT_SEQUENCE: AtomicU64 = AtomicU64::new(1);

    const RPC: &[u8] = br#"{
      "resultcount": 1,
      "results": [{
        "Description": "Visual Studio Code",
        "LastModified": 1784746314,
        "Maintainer": "dcelasun",
        "Name": "visual-studio-code-bin",
        "OutOfDate": null,
        "PackageBase": "visual-studio-code-bin",
        "URL": "https://code.visualstudio.com/",
        "URLPath": "/cgit/aur.git/snapshot/visual-studio-code-bin.tar.gz",
        "Version": "1.130.0-1"
      }],
      "type": "multiinfo",
      "version": 5
    }"#;

    const PROVIDER_RPC: &[u8] = br#"{
      "resultcount": 2,
      "results": [{
        "Description": "Virtual provider",
        "LastModified": 1784746314,
        "Maintainer": "builder",
        "Name": "z-provider-bin",
        "OutOfDate": null,
        "PackageBase": "z-provider-bin",
        "Provides": ["virtual-sdk=2.0"],
        "URL": "https://example.com/",
        "URLPath": "/cgit/aur.git/snapshot/z-provider-bin.tar.gz",
        "Version": "2.0-1"
      }, {
        "Description": "Exact provider",
        "LastModified": 1784746314,
        "Maintainer": "builder",
        "Name": "virtual-sdk",
        "OutOfDate": null,
        "PackageBase": "virtual-sdk",
        "Provides": null,
        "URL": "https://example.com/",
        "URLPath": "/cgit/aur.git/snapshot/virtual-sdk.tar.gz",
        "Version": "1.0-1"
      }],
      "type": "search",
      "version": 5
    }"#;

    const SRCINFO: &[u8] = br#"pkgbase = visual-studio-code-bin
	pkgdesc = Visual Studio Code
	pkgver = 1.130.0
	pkgrel = 1
	url = https://code.visualstudio.com/
	install = visual-studio-code-bin.install
	arch = x86_64
	arch = aarch64
	license = custom: commercial
	depends = gtk3
	makedepends = patchelf
	checkdepends = shellcheck
	provides = code
	provides = vscode
	source = visual-studio-code-bin.sh
	sha256sums = bd0d9edf69283ebdf4e73e0a7b168d2fcf50acbd01f63674cad93ed4fe42fdad
	source_x86_64 = code.deb::https://update.code.visualstudio.com/x64
	sha256sums_x86_64 = 63835d9a09ba93c88fb57d35fad0f4c28788221285c7120281ac53ff2deaf183
	source_aarch64 = code.deb::https://update.code.visualstudio.com/arm64
	sha256sums_aarch64 = 4b67f4e83154dfb281ed5e8ed7be03d9ce3c489bb00c8653c5207d61744d864b

pkgname = visual-studio-code-bin
"#;

    const PKGBUILD: &[u8] = br#"prepare() {
  true
}

package() {
  true
}
"#;

    const SPLIT_RPC: &[u8] = br#"{
      "resultcount": 1,
      "results": [{
        "Description": "The .NET SDK binary distribution",
        "LastModified": 1784708152,
        "Maintainer": "maintainer",
        "Name": "dotnet-sdk-bin",
        "OutOfDate": null,
        "PackageBase": "dotnet-core-bin",
        "URL": "https://dotnet.microsoft.com/",
        "URLPath": "/cgit/aur.git/snapshot/dotnet-sdk-bin.tar.gz",
        "Version": "10.0.10.sdk302-1"
      }],
      "type": "multiinfo",
      "version": 5
    }"#;

    const SPLIT_SRCINFO: &[u8] = br#"pkgbase = dotnet-core-bin
	pkgver = 10.0.10.sdk302
	pkgrel = 1
	arch = x86_64
	arch = aarch64
	license = MIT
	source_x86_64 = dotnet.tar.gz::https://example.org/x86_64.tar.gz
	sha512sums_x86_64 = aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
	source_aarch64 = dotnet.tar.gz::https://example.org/aarch64.tar.gz
	sha512sums_aarch64 = bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb

pkgname = dotnet-runtime-bin
	depends = runtime-only-dependency
	provides = dotnet-runtime
	install = runtime.install

pkgname = dotnet-sdk-bin
	depends = dotnet-runtime>=10.0
	install = sdk.install
"#;

    const SPLIT_PKGBUILD: &[u8] = br#"package_dotnet-runtime-bin() {
  true
}

package_dotnet-sdk-bin() {
  true
}
"#;

    fn snapshot(local_source: &[u8]) -> Vec<u8> {
        let srcinfo = String::from_utf8(SRCINFO.to_vec())
            .expect(".SRCINFO")
            .replace(
                "bd0d9edf69283ebdf4e73e0a7b168d2fcf50acbd01f63674cad93ed4fe42fdad",
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            );
        let encoder = GzEncoder::new(Vec::new(), Compression::default());
        let mut archive = Builder::new(encoder);
        let provenance = b"52 comment=0123456789abcdef0123456789abcdef01234567\n";
        let mut provenance_header = Header::new_gnu();
        provenance_header.set_entry_type(EntryType::XGlobalHeader);
        provenance_header.set_mode(0o644);
        provenance_header.set_size(provenance.len() as u64);
        provenance_header.set_cksum();
        archive
            .append_data(&mut provenance_header, "pax_global_header", &provenance[..])
            .expect("append provenance");
        for (path, bytes) in [
            ("visual-studio-code-bin/.SRCINFO", srcinfo.as_bytes()),
            ("visual-studio-code-bin/PKGBUILD", PKGBUILD),
            (
                "visual-studio-code-bin/visual-studio-code-bin.install",
                b"post_install() { true; }\n",
            ),
            (
                "visual-studio-code-bin/visual-studio-code-bin.sh",
                local_source,
            ),
        ] {
            let mut header = Header::new_gnu();
            header.set_mode(0o644);
            header.set_size(bytes.len() as u64);
            header.set_cksum();
            archive
                .append_data(&mut header, path, bytes)
                .expect("append snapshot file");
        }
        archive
            .into_inner()
            .expect("finish tar")
            .finish()
            .expect("finish gzip")
    }

    fn split_snapshot(include_sdk_install_script: bool) -> Vec<u8> {
        let encoder = GzEncoder::new(Vec::new(), Compression::default());
        let mut archive = Builder::new(encoder);
        let provenance = b"52 comment=0123456789abcdef0123456789abcdef01234567\n";
        let mut provenance_header = Header::new_gnu();
        provenance_header.set_entry_type(EntryType::XGlobalHeader);
        provenance_header.set_mode(0o644);
        provenance_header.set_size(provenance.len() as u64);
        provenance_header.set_cksum();
        archive
            .append_data(&mut provenance_header, "pax_global_header", &provenance[..])
            .expect("append provenance");
        {
            let mut append_file = |path: &str, bytes: &[u8]| {
                let mut header = Header::new_gnu();
                header.set_mode(0o644);
                header.set_size(bytes.len() as u64);
                header.set_cksum();
                archive
                    .append_data(&mut header, path, bytes)
                    .expect("append split snapshot file");
            };
            append_file("dotnet-core-bin/.SRCINFO", SPLIT_SRCINFO);
            append_file("dotnet-core-bin/PKGBUILD", SPLIT_PKGBUILD);
            append_file(
                "dotnet-core-bin/runtime.install",
                b"post_install() { echo runtime; }\n",
            );
            if include_sdk_install_script {
                append_file(
                    "dotnet-core-bin/sdk.install",
                    b"post_install() { echo sdk; }\n",
                );
            }
        }
        archive
            .into_inner()
            .expect("finish tar")
            .finish()
            .expect("finish gzip")
    }

    fn temporary_root(label: &str) -> std::path::PathBuf {
        let root = std::env::temp_dir().join(format!(
            "archphene-aur-{label}-{}-{}",
            std::process::id(),
            TEST_ROOT_SEQUENCE.fetch_add(1, Ordering::Relaxed),
        ));
        fs::create_dir_all(root.join("var/cache")).expect("cache root");
        root
    }

    fn graph_review(
        package_base: &str,
        dependencies: &[&str],
        provided_packages: &[&str],
    ) -> AurReview {
        let mut review = review_aur_package(
            RPC,
            SRCINFO,
            PKGBUILD,
            "visual-studio-code-bin",
            RepositoryArchitecture::Aarch64,
        )
        .expect("base graph review");
        review.package_base = package_base.to_owned();
        review.package_name = package_base.to_owned();
        review.required_packages = vec![package_base.to_owned()];
        review.provided_packages = provided_packages
            .iter()
            .copied()
            .chain(std::iter::once(package_base))
            .map(str::to_owned)
            .collect();
        review.provided_packages.sort();
        review.provided_packages.dedup();
        review.dependencies = dependencies
            .iter()
            .map(|value| (*value).to_owned())
            .collect();
        review.make_dependencies.clear();
        review.check_dependencies.clear();
        review.install_scripts.clear();
        review
    }

    #[test]
    fn provider_search_is_exact_bounded_and_preserves_ambiguity() {
        assert_eq!(
            aur_provider_candidates(PROVIDER_RPC, "virtual-sdk").expect("provider candidates"),
            ["virtual-sdk", "z-provider-bin"]
        );
        let mismatched = String::from_utf8(PROVIDER_RPC.to_vec())
            .expect("provider RPC")
            .replace(
                "\"Provides\": [\"virtual-sdk=2.0\"]",
                "\"Provides\": [\"different-sdk=2.0\"]",
            );
        assert!(matches!(
            aur_provider_candidates(mismatched.as_bytes(), "virtual-sdk"),
            Err(AurReviewError::RpcMismatch)
        ));
        assert!(matches!(
            aur_provider_candidates(PROVIDER_RPC, "../virtual-sdk"),
            Err(AurReviewError::InvalidSrcInfo)
        ));
    }

    #[test]
    fn reviews_visual_studio_code_for_aarch64() {
        assert_eq!(
            aur_snapshot_path(RPC, "visual-studio-code-bin").expect("snapshot path"),
            "/cgit/aur.git/snapshot/visual-studio-code-bin.tar.gz",
        );
        let review = review_aur_package(
            RPC,
            SRCINFO,
            PKGBUILD,
            "visual-studio-code-bin",
            RepositoryArchitecture::Aarch64,
        )
        .expect("review");
        assert_eq!(review.version, "1.130.0-1");
        assert_eq!(review.maintainer.as_deref(), Some("dcelasun"));
        assert_eq!(review.sources.len(), 2);
        assert_eq!(review.sources[1].architecture.as_deref(), Some("aarch64"));
        assert_eq!(review.sources[0].filename, "visual-studio-code-bin.sh");
        assert!(review.sources[0].local);
        assert_eq!(review.sources[0].remote_url, None);
        assert_eq!(review.sources[1].filename, "code.deb");
        assert_eq!(
            review.sources[1].remote_url.as_deref(),
            Some("https://update.code.visualstudio.com/arm64")
        );
        assert!(!review.sources[1].local);
        assert_eq!(review.unverified_source_count, 0);
        assert_eq!(review.insecure_source_count, 0);
        assert_eq!(
            review.build_steps,
            vec![AurBuildStep::Prepare, AurBuildStep::Package]
        );
        assert_eq!(
            review.provided_packages,
            vec!["code", "visual-studio-code-bin", "vscode"],
        );
        assert_eq!(
            review.install_scripts,
            vec![AurInstallScript {
                package_name: "visual-studio-code-bin".to_owned(),
                path: "visual-studio-code-bin.install".to_owned(),
                contents: None,
            }]
        );
        assert_ne!(review.review_sha256, [0; 32]);
    }

    #[test]
    fn common_provides_requires_one_unambiguous_output() {
        let info = parse_srcinfo(SRCINFO, "visual-studio-code-bin", "aarch64")
            .expect("single-output srcinfo");
        assert_eq!(
            split_provider(&info, "code").expect("common provider"),
            Some("visual-studio-code-bin"),
        );

        let mut ambiguous = SRCINFO.to_vec();
        ambiguous.extend_from_slice(b"\npkgname = visual-studio-code-insiders-bin\n");
        let info = parse_srcinfo(&ambiguous, "visual-studio-code-bin", "aarch64")
            .expect("multi-output srcinfo");
        assert_eq!(
            split_provider(&info, "code"),
            Err(AurReviewError::InvalidSrcInfo),
        );
    }

    #[test]
    fn plans_only_a_bounded_reviewed_aur_dependency_dag() {
        let root = graph_review(
            "editor-bin",
            &["official-runtime>=1", "language-server>=2"],
            &[],
        );
        let dependency = graph_review("language-server-bin", &[], &["language-server"]);
        let official = std::collections::BTreeSet::from(["official-runtime".to_owned()]);
        let graph =
            plan_reviewed_aur_graph(&[root.clone(), dependency.clone()], "editor-bin", &official)
                .expect("reviewed graph");
        assert_eq!(
            graph.package_bases,
            vec!["language-server-bin", "editor-bin"],
        );
        assert_eq!(
            graph.edges,
            vec![AurBuildGraphEdge {
                package_base: "editor-bin".to_owned(),
                dependency_base: "language-server-bin".to_owned(),
                dependency: "language-server".to_owned(),
                requirement: "language-server>=2".to_owned(),
            }],
        );
        let mut wire = [0_u8; 512];
        let wire_length = graph.write_wire(&mut wire).expect("bounded graph wire");
        let expected = [
            b"AUBG0001".as_slice(),
            &(2_u32.to_le_bytes()),
            &(19_u32.to_le_bytes()),
            b"language-server-bin",
            &(10_u32.to_le_bytes()),
            b"editor-bin",
            &(1_u32.to_le_bytes()),
            &(10_u32.to_le_bytes()),
            b"editor-bin",
            &(19_u32.to_le_bytes()),
            b"language-server-bin",
            &(15_u32.to_le_bytes()),
            b"language-server",
            &(18_u32.to_le_bytes()),
            b"language-server>=2",
        ]
        .concat();
        assert_eq!(&wire[..wire_length], expected);

        let official_preferred = std::collections::BTreeSet::from([
            "language-server".to_owned(),
            "official-runtime".to_owned(),
        ]);
        assert_eq!(
            plan_reviewed_aur_graph(&[root], "editor-bin", &official_preferred)
                .expect("official provider is preferred")
                .package_bases,
            vec!["editor-bin"],
        );
    }

    #[test]
    fn aur_dependency_graph_rejects_missing_ambiguous_cyclic_and_unused_reviews() {
        let root = graph_review("root-bin", &["virtual-runtime"], &[]);
        assert_eq!(
            plan_reviewed_aur_graph(
                std::slice::from_ref(&root),
                "root-bin",
                &std::collections::BTreeSet::new(),
            ),
            Err(AurBuildGraphError::MissingProvider(
                "virtual-runtime".to_owned(),
            )),
        );

        let first = graph_review("first-provider", &[], &["virtual-runtime"]);
        let second = graph_review("second-provider", &[], &["virtual-runtime"]);
        assert_eq!(
            plan_reviewed_aur_graph(
                &[root.clone(), first, second],
                "root-bin",
                &std::collections::BTreeSet::new(),
            ),
            Err(AurBuildGraphError::AmbiguousProvider(
                "virtual-runtime".to_owned(),
            )),
        );

        let cyclic_root = graph_review("cyclic-root", &["cyclic-dependency"], &[]);
        let cyclic_dependency = graph_review("cyclic-dependency", &["cyclic-root"], &[]);
        assert_eq!(
            plan_reviewed_aur_graph(
                &[cyclic_root, cyclic_dependency],
                "cyclic-root",
                &std::collections::BTreeSet::new(),
            ),
            Err(AurBuildGraphError::Cycle),
        );

        let unused = graph_review("unused", &[], &[]);
        let provider = graph_review("virtual-runtime", &[], &[]);
        assert_eq!(
            plan_reviewed_aur_graph(
                &[root, provider, unused],
                "root-bin",
                &std::collections::BTreeSet::new(),
            ),
            Err(AurBuildGraphError::DisconnectedReview),
        );
    }

    #[test]
    fn reviews_a_split_package_from_the_canonical_package_base_snapshot() {
        assert_eq!(
            aur_snapshot_path(SPLIT_RPC, "dotnet-sdk-bin").expect("canonical snapshot path"),
            "/cgit/aur.git/snapshot/dotnet-core-bin.tar.gz",
        );
        let review = review_aur_snapshot(
            SPLIT_RPC,
            &split_snapshot(true),
            "dotnet-sdk-bin",
            RepositoryArchitecture::Aarch64,
        )
        .expect("split package review");
        assert_eq!(review.package_base, "dotnet-core-bin");
        assert_eq!(review.package_name, "dotnet-sdk-bin");
        assert_eq!(review.version, "10.0.10.sdk302-1");
        assert_eq!(
            review.snapshot_path,
            "/cgit/aur.git/snapshot/dotnet-core-bin.tar.gz"
        );
        assert_eq!(review.dependencies, vec!["runtime-only-dependency"]);
        assert_eq!(
            review.required_packages,
            vec!["dotnet-runtime-bin", "dotnet-sdk-bin"]
        );
        assert_eq!(
            review.provided_packages,
            vec!["dotnet-runtime", "dotnet-runtime-bin", "dotnet-sdk-bin"],
        );
        assert_eq!(
            review
                .install_scripts
                .iter()
                .map(|script| (
                    script.package_name.as_str(),
                    script.path.as_str(),
                    script.contents.as_deref(),
                ))
                .collect::<Vec<_>>(),
            vec![
                (
                    "dotnet-runtime-bin",
                    "runtime.install",
                    Some(&b"post_install() { echo runtime; }\n"[..]),
                ),
                (
                    "dotnet-sdk-bin",
                    "sdk.install",
                    Some(&b"post_install() { echo sdk; }\n"[..]),
                ),
            ],
        );
        assert!(
            review
                .reviewed_install_script("dotnet-runtime-bin")
                .expect("runtime install script")
                .is_some()
        );
        assert!(
            review
                .reviewed_install_script("dotnet-sdk-bin")
                .expect("SDK install script")
                .is_some()
        );
        assert_eq!(review.sources.len(), 1);
        assert_eq!(review.sources[0].architecture.as_deref(), Some("aarch64"));
        assert!(matches!(
            review.sources[0].checksum,
            Some(AurSourceChecksum::Sha512(_))
        ));
        assert!(review.build_steps.contains(&AurBuildStep::Package));
        assert_eq!(
            review_aur_snapshot(
                SPLIT_RPC,
                &split_snapshot(false),
                "dotnet-sdk-bin",
                RepositoryArchitecture::Aarch64,
            ),
            Err(AurReviewError::InvalidSnapshot("missing install script")),
        );

        let mismatched_path = String::from_utf8(SPLIT_RPC.to_vec())
            .expect("split RPC")
            .replace("snapshot/dotnet-sdk-bin", "snapshot/dotnet-core-bin");
        assert_eq!(
            aur_snapshot_path(mismatched_path.as_bytes(), "dotnet-sdk-bin"),
            Err(AurReviewError::RpcMismatch)
        );
    }

    #[test]
    fn reviews_one_pinned_snapshot_and_verifies_local_files() {
        let snapshot_bytes = snapshot(b"abc");
        let review = review_aur_snapshot(
            RPC,
            &snapshot_bytes,
            "visual-studio-code-bin",
            RepositoryArchitecture::Aarch64,
        )
        .expect("snapshot review");
        assert!(review.snapshot_sha256.is_some());
        assert_eq!(
            review.snapshot_commit.as_deref(),
            Some("0123456789abcdef0123456789abcdef01234567")
        );
        assert_eq!(review.pkgbuild, PKGBUILD);
        assert_eq!(
            review
                .reviewed_install_script("visual-studio-code-bin")
                .expect("reviewed script")
                .map(|script| script.contents),
            Some(&b"post_install() { true; }\n"[..])
        );
        assert_eq!(review.sources.len(), 2);
        let mut wire = vec![0_u8; MAX_AUR_REVIEW_BYTES];
        let wire_length = review.write_wire(&mut wire).expect("review wire");
        assert!(wire_length < wire.len());
        assert_eq!(&wire[..8], b"ARVW0005");
        assert!(
            wire[..wire_length]
                .windows(PKGBUILD.len())
                .any(|window| window == PKGBUILD)
        );

        let tampered = snapshot(b"abd");
        assert_eq!(
            review_aur_snapshot(
                RPC,
                &tampered,
                "visual-studio-code-bin",
                RepositoryArchitecture::Aarch64,
            ),
            Err(AurReviewError::InvalidSnapshot(
                "local source checksum mismatch"
            ))
        );
    }

    #[test]
    fn stages_and_hashes_one_bounded_remote_source() {
        let root = temporary_root("source-download");
        let expected: [u8; 32] = Sha256::digest(b"verified source").into();
        let checksum = AurSourceChecksum::Sha256(expected);
        let download =
            AurSourceDownload::begin(&root, "code.deb", checksum, 1024).expect("begin source");
        let mut output = download.duplicate_file().expect("source descriptor");
        output.write_all(b"verified source").expect("write source");
        drop(output);
        assert_eq!(download.finish().expect("finish source"), 15);
        let digest = checksum.cache_key();
        assert_eq!(
            fs::read(
                root.join(AUR_SOURCE_CACHE_DIRECTORY)
                    .join(format!("{digest}-code.deb")),
            )
            .expect("verified source"),
            b"verified source",
        );
        assert_eq!(
            AurSourceDownload::verified_cache_size(
                &root,
                "code.deb",
                checksum,
                MAX_AUR_SOURCE_BYTES,
            )
            .expect("cached source"),
            Some(15),
        );
        let mut reopened = AurSourceDownload::open_verified_cache(
            &root,
            "code.deb",
            checksum,
            MAX_AUR_SOURCE_BYTES,
        )
        .expect("open verified source");
        let mut reopened_bytes = Vec::new();
        reopened
            .read_to_end(&mut reopened_bytes)
            .expect("read verified source");
        assert_eq!(reopened_bytes, b"verified source");
        fs::write(
            root.join(AUR_SOURCE_CACHE_DIRECTORY)
                .join(format!("{digest}-code.deb")),
            b"tampered",
        )
        .expect("tamper cache");
        assert_eq!(
            AurSourceDownload::verified_cache_size(
                &root,
                "code.deb",
                checksum,
                MAX_AUR_SOURCE_BYTES,
            )
            .expect("discard tampered source"),
            None,
        );
        fs::remove_dir_all(root).expect("remove test root");
    }

    #[test]
    fn stages_and_reopens_a_sha512_remote_source() {
        let root = temporary_root("sha512-source-download");
        let checksum = AurSourceChecksum::Sha512(Sha512::digest(b"verified SHA-512 source").into());
        let download = AurSourceDownload::begin(&root, "source.tar.gz", checksum, 1024)
            .expect("begin SHA-512 source");
        let mut output = download.duplicate_file().expect("source descriptor");
        output
            .write_all(b"verified SHA-512 source")
            .expect("write source");
        drop(output);
        assert_eq!(download.finish().expect("finish SHA-512 source"), 23);
        assert_eq!(
            AurSourceDownload::verified_cache_size(
                &root,
                "source.tar.gz",
                checksum,
                MAX_AUR_SOURCE_BYTES,
            )
            .expect("cached SHA-512 source"),
            Some(23),
        );
        let mut reopened = AurSourceDownload::open_verified_cache(
            &root,
            "source.tar.gz",
            checksum,
            MAX_AUR_SOURCE_BYTES,
        )
        .expect("open verified SHA-512 source");
        let mut bytes = Vec::new();
        reopened.read_to_end(&mut bytes).expect("read source");
        assert_eq!(bytes, b"verified SHA-512 source");
        fs::remove_dir_all(root).expect("remove test root");
    }

    #[test]
    fn retains_and_reopens_only_the_reviewed_snapshot() {
        let root = temporary_root("snapshot-cache");
        let bytes = snapshot(b"abc");
        let digest: [u8; 32] = Sha256::digest(&bytes).into();
        assert_eq!(
            retain_reviewed_snapshot(&root, "visual-studio-code-bin", digest, &bytes)
                .expect("retain snapshot"),
            u64::try_from(bytes.len()).expect("snapshot length"),
        );
        let mut reopened =
            open_reviewed_snapshot(&root, "visual-studio-code-bin", digest).expect("open snapshot");
        let mut reopened_bytes = Vec::new();
        reopened
            .read_to_end(&mut reopened_bytes)
            .expect("read snapshot");
        assert_eq!(reopened_bytes, bytes);
        assert!(matches!(
            retain_reviewed_snapshot(&root, "visual-studio-code-bin", [7; 32], &reopened_bytes,),
            Err(super::super::PackageRuntimeError::InvalidPayload),
        ));
        fs::remove_dir_all(root).expect("remove test root");
    }

    #[test]
    fn rejects_tampered_or_unsafe_remote_source_staging() {
        let root = temporary_root("source-rejection");
        let expected: [u8; 32] = Sha256::digest(b"expected").into();
        let checksum = AurSourceChecksum::Sha256(expected);
        let download = AurSourceDownload::begin(&root, "source.tar.zst", checksum, 1024)
            .expect("begin source");
        let mut output = download.duplicate_file().expect("source descriptor");
        output.write_all(b"tampered").expect("write source");
        drop(output);
        assert!(matches!(
            download.finish(),
            Err(super::super::PackageRuntimeError::InvalidPayload)
        ));
        assert!(matches!(
            AurSourceDownload::begin(&root, "../escape", checksum, 1024),
            Err(super::super::PackageRuntimeError::InvalidPayload)
        ));
        assert!(
            !root
                .join(AUR_SOURCE_CACHE_DIRECTORY)
                .join(format!("{}-source.tar.zst.part", checksum.cache_key()))
                .exists()
        );
        fs::remove_dir_all(root).expect("remove test root");
    }

    #[test]
    fn selects_only_the_requested_architecture_sources() {
        let review = review_aur_package(
            RPC,
            SRCINFO,
            PKGBUILD,
            "visual-studio-code-bin",
            RepositoryArchitecture::X86_64,
        )
        .expect("review");
        assert_eq!(review.sources.len(), 2);
        assert_eq!(review.sources[1].architecture.as_deref(), Some("x86_64"));
        assert!(review.sources[1].expression.ends_with("/x64"));
    }

    #[test]
    fn accepts_only_bounded_direct_https_source_locations() {
        assert!(valid_https_source_url(
            "https://update.code.visualstudio.com/1.130.0/linux-deb-arm64/stable"
        ));
        assert!(valid_https_source_url(
            "https://example.org:443/source.tar.zst"
        ));
        assert!(!valid_https_source_url("http://example.org/source.tar.zst"));
        assert!(!valid_https_source_url(
            "https://user@example.org/source.tar.zst"
        ));
        assert!(!valid_https_source_url(
            "https://example.org:8443/source.tar.zst"
        ));
        assert!(!valid_https_source_url(
            "https://example.org/source.tar.zst#fragment"
        ));
        assert!(!valid_https_source_url(
            "https://example.org\\source.tar.zst"
        ));
    }

    #[test]
    fn rejects_rpc_identity_and_srcinfo_version_mismatches() {
        let wrong_rpc = String::from_utf8(RPC.to_vec()).expect("RPC").replace(
            "\"Name\": \"visual-studio-code-bin\"",
            "\"Name\": \"different\"",
        );
        assert_eq!(
            review_aur_package(
                wrong_rpc.as_bytes(),
                SRCINFO,
                PKGBUILD,
                "visual-studio-code-bin",
                RepositoryArchitecture::Aarch64,
            ),
            Err(AurReviewError::RpcMismatch)
        );
        let wrong_srcinfo = String::from_utf8(SRCINFO.to_vec())
            .expect(".SRCINFO")
            .replace("pkgver = 1.130.0", "pkgver = 1.129.0");
        assert_eq!(
            review_aur_package(
                RPC,
                wrong_srcinfo.as_bytes(),
                PKGBUILD,
                "visual-studio-code-bin",
                RepositoryArchitecture::Aarch64,
            ),
            Err(AurReviewError::MetadataMismatch)
        );
    }

    #[test]
    fn rejects_missing_architecture_hash_and_package_step() {
        let missing_hash = String::from_utf8(SRCINFO.to_vec())
            .expect(".SRCINFO")
            .replace(
                "\tsha256sums_aarch64 = 4b67f4e83154dfb281ed5e8ed7be03d9ce3c489bb00c8653c5207d61744d864b\n",
                "",
            );
        assert_eq!(
            review_aur_package(
                RPC,
                missing_hash.as_bytes(),
                PKGBUILD,
                "visual-studio-code-bin",
                RepositoryArchitecture::Aarch64,
            ),
            Err(AurReviewError::InvalidSrcInfo)
        );
        assert_eq!(
            review_aur_package(
                RPC,
                SRCINFO,
                b"build() { true; }\n",
                "visual-studio-code-bin",
                RepositoryArchitecture::Aarch64,
            ),
            Err(AurReviewError::MissingPackageFunction)
        );
    }

    #[test]
    fn marks_skip_and_insecure_sources_for_review() {
        let srcinfo = String::from_utf8(SRCINFO.to_vec())
            .expect(".SRCINFO")
            .replace(
                "source = visual-studio-code-bin.sh",
                "source = http://example.invalid/source.tar",
            )
            .replace(
                "sha256sums = bd0d9edf69283ebdf4e73e0a7b168d2fcf50acbd01f63674cad93ed4fe42fdad",
                "sha256sums = SKIP",
            );
        let review = review_aur_package(
            RPC,
            srcinfo.as_bytes(),
            PKGBUILD,
            "visual-studio-code-bin",
            RepositoryArchitecture::Aarch64,
        )
        .expect("review");
        assert_eq!(review.unverified_source_count, 1);
        assert_eq!(review.insecure_source_count, 1);
    }

    #[test]
    fn rejects_oversized_or_malformed_inputs() {
        assert!(matches!(
            review_aur_package(
                &vec![b' '; MAX_AUR_RPC_BYTES + 1],
                SRCINFO,
                PKGBUILD,
                "visual-studio-code-bin",
                RepositoryArchitecture::Aarch64,
            ),
            Err(AurReviewError::SizeLimit("RPC response"))
        ));
        assert!(matches!(
            review_aur_package(
                RPC,
                b"pkgbase visual-studio-code-bin\n",
                PKGBUILD,
                "visual-studio-code-bin",
                RepositoryArchitecture::Aarch64,
            ),
            Err(AurReviewError::InvalidSrcInfo)
        ));
    }
}
