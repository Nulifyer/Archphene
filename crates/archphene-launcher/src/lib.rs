#![forbid(unsafe_code)]

use std::fmt;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};

use archphene_packages::desktop::{
    DesktopCatalog, DesktopEntry, ExecArgument, resolve_desktop_icon,
};
use archphene_packages::elf_profile::BRIDGE_CAPABILITY_MASK;

pub const MAX_LAUNCHER_DESCRIPTORS: usize = 256;
pub const MAX_LAUNCHER_REGISTRY_BYTES: usize = 4 * 1024 * 1024;
pub const LAUNCHER_CAPABILITIES_V4: &str =
    "wayland,input,ime,clipboard,documents,open-uri,notifications";
pub const LAUNCHER_CAPABILITIES_PRINTING_V5: &str =
    "wayland,input,ime,clipboard,documents,open-uri,notifications,printing";

pub fn launcher_capabilities(bridge_capabilities: u8) -> &'static str {
    if bridge_capabilities & archphene_packages::elf_profile::BRIDGE_PRINTING != 0 {
        LAUNCHER_CAPABILITIES_PRINTING_V5
    } else {
        LAUNCHER_CAPABILITIES_V4
    }
}

const REGISTRY_DIRECTORY: &str = "var/lib/archphene";
const REGISTRY_FILE: &str = "launcher-registry-v1";
const REGISTRY_TEMP_FILE: &str = ".launcher-registry-v1.tmp";
const REGISTRY_MAGIC: &[u8; 8] = b"ARCHLREG";
const REGISTRY_VERSION: u32 = 6;
const OBSERVATION_REGISTRY_VERSION: u32 = 5;
const EXECUTABLE_PACKAGE_REGISTRY_VERSION: u32 = 4;
const PREVIOUS_REGISTRY_VERSION: u32 = 3;
const ICON_REGISTRY_VERSION: u32 = 2;
const LEGACY_REGISTRY_VERSION: u32 = 1;
const REGISTRY_HEADER_BYTES: usize = 8 + 4 + 8 + 4 + 32;
const MAX_LAUNCHER_ICON_BYTES: u64 = 1024 * 1024;
const MAX_LAUNCHER_ICON_DIMENSION: u32 = 2048;
const MAX_LAUNCHER_ICON_PIXELS: u64 = 4 * 1024 * 1024;
const PACKAGE_PREFIX: &str = "org.archphene.linux.p";
const O_NOFOLLOW: i32 = 0o400000;
const O_CLOEXEC: i32 = 0o2000000;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum WrapperStatus {
    NeedsPublish = 1,
    Building = 2,
    AwaitingInstall = 3,
    Current = 4,
    NeedsRemoval = 5,
    AwaitingRemoval = 6,
    Failed = 7,
    Cancelled = 8,
    Dismissed = 9,
    NeedsReview = 10,
}

impl WrapperStatus {
    fn from_raw(raw: u8) -> Option<Self> {
        Some(match raw {
            1 => Self::NeedsPublish,
            2 => Self::Building,
            3 => Self::AwaitingInstall,
            4 => Self::Current,
            5 => Self::NeedsRemoval,
            6 => Self::AwaitingRemoval,
            7 => Self::Failed,
            8 => Self::Cancelled,
            9 => Self::Dismissed,
            10 => Self::NeedsReview,
            _ => return None,
        })
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LauncherDescriptor {
    pub descriptor_id: [u8; 32],
    pub android_package: String,
    pub desktop_id: String,
    pub source_package: Option<String>,
    pub executable_package: Option<String>,
    pub name: String,
    pub executable: String,
    pub arguments: Vec<ExecArgument>,
    pub try_exec: Option<String>,
    pub icon: Option<String>,
    icon_digest: Option<[u8; 32]>,
    pub mime_types: Vec<String>,
    pub terminal: bool,
    pub bridge_capabilities: u8,
    pub observed_topology: u16,
    pub integration_observed: bool,
    pub integration_observation_complete: bool,
    pub desired_present: bool,
    pub desired_generation: u64,
    pub published_generation: u64,
    pub pending_generation: u64,
    pub status: WrapperStatus,
    content_digest: [u8; 32],
}

impl LauncherDescriptor {
    pub fn descriptor_id_hex(&self) -> [u8; 64] {
        let mut output = [0_u8; 64];
        const HEX: &[u8; 16] = b"0123456789abcdef";
        for (index, byte) in self.descriptor_id.iter().copied().enumerate() {
            output[index * 2] = HEX[usize::from(byte >> 4)];
            output[index * 2 + 1] = HEX[usize::from(byte & 0x0f)];
        }
        output
    }

    pub fn icon_digest(&self) -> Option<[u8; 32]> {
        self.icon_digest
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LauncherRegistry {
    schema_version: u32,
    generation: u64,
    descriptors: Vec<LauncherDescriptor>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ReconcileReport {
    pub generation: u64,
    pub added: u16,
    pub changed: u16,
    pub removed: u16,
    pub unchanged: u16,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LauncherReviewDecision {
    pub android_package: String,
    pub desired_generation: u64,
    pub publish: bool,
}

#[derive(Debug)]
pub enum LauncherRegistryError {
    InvalidRoot,
    UnsafePath(PathBuf),
    Corrupt,
    LimitExceeded,
    IncompleteCatalog,
    DuplicateDesktopId,
    IdentityCollision,
    InvalidTransition,
    Io(io::Error),
}

impl fmt::Display for LauncherRegistryError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidRoot => formatter.write_str("invalid launcher-registry root"),
            Self::UnsafePath(path) => {
                write!(
                    formatter,
                    "unsafe launcher-registry path: {}",
                    path.display()
                )
            }
            Self::Corrupt => formatter.write_str("launcher registry is corrupt"),
            Self::LimitExceeded => formatter.write_str("launcher registry exceeds its limit"),
            Self::IncompleteCatalog => {
                formatter.write_str("launcher catalog is incomplete; registry was not changed")
            }
            Self::DuplicateDesktopId => {
                formatter.write_str("launcher catalog repeats a desktop identity")
            }
            Self::IdentityCollision => {
                formatter.write_str("launcher Android package identity collision")
            }
            Self::InvalidTransition => formatter.write_str("invalid launcher state transition"),
            Self::Io(error) => write!(formatter, "launcher-registry I/O error: {error}"),
        }
    }
}

impl std::error::Error for LauncherRegistryError {}

impl From<io::Error> for LauncherRegistryError {
    fn from(error: io::Error) -> Self {
        Self::Io(error)
    }
}

impl LauncherRegistry {
    pub fn empty() -> Self {
        Self {
            schema_version: REGISTRY_VERSION,
            generation: 0,
            descriptors: Vec::new(),
        }
    }

    pub fn load(arch_root: &Path) -> Result<Self, LauncherRegistryError> {
        let directory = registry_directory(arch_root)?;
        recover_stale_temp(&directory)?;
        let path = directory.join(REGISTRY_FILE);
        let metadata = match fs::symlink_metadata(&path) {
            Ok(metadata) => metadata,
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(Self::empty()),
            Err(error) => return Err(error.into()),
        };
        if metadata.file_type().is_symlink()
            || !metadata.is_file()
            || metadata.permissions().mode() & 0o777 != 0o600
            || metadata.len() < u64::try_from(REGISTRY_HEADER_BYTES).expect("header size")
            || metadata.len() > u64::try_from(MAX_LAUNCHER_REGISTRY_BYTES).expect("registry limit")
        {
            return Err(LauncherRegistryError::UnsafePath(path));
        }
        let file = OpenOptions::new()
            .read(true)
            .custom_flags(O_NOFOLLOW | O_CLOEXEC)
            .open(&path)?;
        let opened = file.metadata()?;
        if !opened.is_file() || opened.len() != metadata.len() {
            return Err(LauncherRegistryError::Corrupt);
        }
        let mut bytes = Vec::with_capacity(
            usize::try_from(metadata.len()).map_err(|_| LauncherRegistryError::LimitExceeded)?,
        );
        file.take(
            u64::try_from(MAX_LAUNCHER_REGISTRY_BYTES + 1).expect("launcher registry read limit"),
        )
        .read_to_end(&mut bytes)?;
        if u64::try_from(bytes.len()).map_err(|_| LauncherRegistryError::LimitExceeded)?
            != metadata.len()
        {
            return Err(LauncherRegistryError::Corrupt);
        }
        decode_registry(&bytes)
    }

    pub fn reconcile(
        arch_root: &Path,
        catalog: &DesktopCatalog,
    ) -> Result<(Self, ReconcileReport), LauncherRegistryError> {
        if catalog.entries.len() > MAX_LAUNCHER_DESCRIPTORS {
            return Err(LauncherRegistryError::LimitExceeded);
        }
        if catalog.truncated {
            return Err(LauncherRegistryError::IncompleteCatalog);
        }
        let mut registry = Self::load(arch_root)?;
        let schema_upgrade = registry.schema_version != REGISTRY_VERSION;
        // Terminal=true asks a desktop environment to start the command inside a
        // terminal emulator. The generated Android wrapper owns a Wayland surface,
        // not a terminal renderer, so publishing such an entry would create an app
        // drawer item which can never start successfully. These commands remain
        // available through Archphene's shared Terminal.
        let mut desired: Vec<&DesktopEntry> = catalog
            .entries
            .iter()
            .filter(|entry| !entry.terminal)
            .collect();
        desired.sort_unstable_by(|left, right| left.desktop_id.cmp(&right.desktop_id));
        if desired
            .windows(2)
            .any(|entries| entries[0].desktop_id == entries[1].desktop_id)
        {
            return Err(LauncherRegistryError::DuplicateDesktopId);
        }

        let mut next = Vec::with_capacity(
            desired
                .len()
                .saturating_add(registry.descriptors.len())
                .min(MAX_LAUNCHER_DESCRIPTORS),
        );
        let mut added = 0_u16;
        let mut changed = 0_u16;
        let mut removed = 0_u16;
        let mut unchanged = 0_u16;
        let mut old_index = 0_usize;
        let mut desired_index = 0_usize;
        let mut added_indices = Vec::new();
        while old_index < registry.descriptors.len() || desired_index < desired.len() {
            let old = registry.descriptors.get(old_index);
            let target = desired.get(desired_index).copied();
            match (old, target) {
                (Some(old), Some(target)) if old.desktop_id < target.desktop_id => {
                    retain_removed(old, &mut next, &mut removed, &mut unchanged);
                    old_index += 1;
                }
                (Some(old), Some(target)) if old.desktop_id == target.desktop_id => {
                    let icon_digest = launcher_icon_digest(arch_root, target.icon.as_deref());
                    let content_digest = descriptor_content_digest(target, icon_digest.as_ref());
                    let mut descriptor = old.clone();
                    if descriptor.content_digest == content_digest {
                        let revived = !descriptor.desired_present;
                        descriptor.desired_present = true;
                        descriptor.status = if descriptor.published_generation
                            == descriptor.desired_generation
                            && descriptor.published_generation != 0
                        {
                            match descriptor.status {
                                WrapperStatus::Failed
                                | WrapperStatus::NeedsRemoval
                                | WrapperStatus::AwaitingRemoval => descriptor.status,
                                _ => WrapperStatus::Current,
                            }
                        } else {
                            match descriptor.status {
                                WrapperStatus::AwaitingInstall => descriptor.status,
                                WrapperStatus::Building
                                | WrapperStatus::Failed
                                | WrapperStatus::Cancelled
                                | WrapperStatus::Dismissed
                                | WrapperStatus::NeedsReview
                                    if !revived =>
                                {
                                    descriptor.status
                                }
                                _ => WrapperStatus::NeedsPublish,
                            }
                        };
                        if revived {
                            changed = changed.saturating_add(1);
                        } else {
                            unchanged = unchanged.saturating_add(1);
                        }
                    } else {
                        let desired_generation = descriptor
                            .desired_generation
                            .checked_add(1)
                            .ok_or(LauncherRegistryError::LimitExceeded)?;
                        let mut replacement = descriptor_from_entry(
                            arch_root,
                            target,
                            desired_generation,
                            old.published_generation,
                        );
                        match old.status {
                            WrapperStatus::AwaitingInstall => {
                                replacement.pending_generation = old.pending_generation;
                                replacement.status = WrapperStatus::AwaitingInstall;
                            }
                            WrapperStatus::NeedsRemoval | WrapperStatus::AwaitingRemoval => {
                                replacement.status = old.status;
                            }
                            WrapperStatus::Failed
                            | WrapperStatus::Cancelled
                            | WrapperStatus::Dismissed
                            | WrapperStatus::NeedsReview => {
                                replacement.status = old.status;
                            }
                            _ => {}
                        }
                        descriptor = replacement;
                        changed = changed.saturating_add(1);
                    }
                    next.push(descriptor);
                    old_index += 1;
                    desired_index += 1;
                }
                (Some(old), Some(target)) if old.desktop_id > target.desktop_id => {
                    next.push(descriptor_from_entry(arch_root, target, 1, 0));
                    added_indices.push(next.len() - 1);
                    added = added.saturating_add(1);
                    desired_index += 1;
                }
                (Some(old), None) => {
                    retain_removed(old, &mut next, &mut removed, &mut unchanged);
                    old_index += 1;
                }
                (None, Some(target)) => {
                    next.push(descriptor_from_entry(arch_root, target, 1, 0));
                    added_indices.push(next.len() - 1);
                    added = added.saturating_add(1);
                    desired_index += 1;
                }
                (None, None) => break,
                _ => return Err(LauncherRegistryError::Corrupt),
            }
        }
        if next.len() > MAX_LAUNCHER_DESCRIPTORS {
            return Err(LauncherRegistryError::LimitExceeded);
        }
        if added_indices.len() > 1 {
            for index in added_indices {
                next[index].status = WrapperStatus::NeedsReview;
            }
        }
        next.sort_unstable_by(|left, right| left.desktop_id.cmp(&right.desktop_id));
        validate_identities(&next)?;
        let mutated = added != 0 || changed != 0 || removed != 0;
        if mutated {
            registry.generation = registry
                .generation
                .checked_add(1)
                .ok_or(LauncherRegistryError::LimitExceeded)?;
        }
        registry.descriptors = next;
        registry.schema_version = REGISTRY_VERSION;
        if mutated || schema_upgrade || !registry_file_exists(arch_root)? {
            registry.store(arch_root)?;
        }
        let generation = registry.generation;
        Ok((
            registry,
            ReconcileReport {
                generation,
                added,
                changed,
                removed,
                unchanged,
            },
        ))
    }

    pub fn generation(&self) -> u64 {
        self.generation
    }

    pub fn descriptors(&self) -> &[LauncherDescriptor] {
        &self.descriptors
    }

    pub fn descriptor_for_package(&self, android_package: &str) -> Option<&LauncherDescriptor> {
        self.descriptors
            .iter()
            .find(|descriptor| descriptor.android_package == android_package)
    }

    pub fn authorize_published(
        &self,
        android_package: &str,
        descriptor_id_hex: &str,
        generation: u64,
    ) -> Option<&LauncherDescriptor> {
        let descriptor = self.descriptor_for_package(android_package)?;
        let expected_id = descriptor.descriptor_id_hex();
        if descriptor.status != WrapperStatus::Current
            || !descriptor.desired_present
            || descriptor.desired_generation != generation
            || descriptor.published_generation != generation
            || descriptor.pending_generation != 0
            || descriptor_id_hex.as_bytes() != expected_id
        {
            return None;
        }
        Some(descriptor)
    }

    pub fn record_integration_observation(
        &mut self,
        arch_root: &Path,
        descriptor_id: &[u8; 32],
        generation: u64,
        topology: u16,
        complete: bool,
    ) -> Result<bool, LauncherRegistryError> {
        let index = self
            .descriptors
            .iter()
            .position(|descriptor| {
                descriptor.descriptor_id == *descriptor_id
                    && descriptor.status == WrapperStatus::Current
                    && descriptor.desired_present
                    && descriptor.desired_generation == generation
                    && descriptor.published_generation == generation
                    && descriptor.pending_generation == 0
            })
            .ok_or(LauncherRegistryError::InvalidTransition)?;
        let descriptor = &self.descriptors[index];
        let merged_topology = descriptor.observed_topology | topology;
        let merged_complete = if descriptor.integration_observed {
            descriptor.integration_observation_complete && complete
        } else {
            complete
        };
        if descriptor.integration_observed
            && descriptor.observed_topology == merged_topology
            && descriptor.integration_observation_complete == merged_complete
        {
            return Ok(false);
        }
        let previous_generation = self.generation;
        let previous = (
            descriptor.observed_topology,
            descriptor.integration_observed,
            descriptor.integration_observation_complete,
        );
        let descriptor = &mut self.descriptors[index];
        descriptor.observed_topology = merged_topology;
        descriptor.integration_observed = true;
        descriptor.integration_observation_complete = merged_complete;
        self.generation = self
            .generation
            .checked_add(1)
            .ok_or(LauncherRegistryError::LimitExceeded)?;
        if let Err(error) = self.store(arch_root) {
            self.generation = previous_generation;
            let descriptor = &mut self.descriptors[index];
            descriptor.observed_topology = previous.0;
            descriptor.integration_observed = previous.1;
            descriptor.integration_observation_complete = previous.2;
            return Err(error);
        }
        Ok(true)
    }

    pub fn mark_building(
        &mut self,
        arch_root: &Path,
        android_package: &str,
        desired_generation: u64,
    ) -> Result<(), LauncherRegistryError> {
        let descriptor = self
            .descriptors
            .iter_mut()
            .find(|descriptor| descriptor.android_package == android_package)
            .ok_or(LauncherRegistryError::InvalidTransition)?;
        if descriptor.desired_generation != desired_generation
            || !descriptor.desired_present
            || descriptor.status != WrapperStatus::NeedsPublish
            || descriptor.pending_generation != 0
        {
            return Err(LauncherRegistryError::InvalidTransition);
        }
        descriptor.pending_generation = desired_generation;
        descriptor.status = WrapperStatus::Building;
        self.advance_and_store(arch_root)
    }

    pub fn mark_awaiting_install(
        &mut self,
        arch_root: &Path,
        android_package: &str,
        desired_generation: u64,
    ) -> Result<(), LauncherRegistryError> {
        self.transition(
            arch_root,
            android_package,
            desired_generation,
            WrapperStatus::Building,
            WrapperStatus::AwaitingInstall,
        )
    }

    pub fn confirm_installed(
        &mut self,
        arch_root: &Path,
        android_package: &str,
        desired_generation: u64,
    ) -> Result<(), LauncherRegistryError> {
        let descriptor = self
            .descriptors
            .iter_mut()
            .find(|descriptor| descriptor.android_package == android_package)
            .ok_or(LauncherRegistryError::InvalidTransition)?;
        if descriptor.pending_generation != desired_generation
            || descriptor.status != WrapperStatus::AwaitingInstall
        {
            return Err(LauncherRegistryError::InvalidTransition);
        }
        descriptor.published_generation = desired_generation;
        descriptor.pending_generation = 0;
        descriptor.status =
            if descriptor.desired_present && descriptor.desired_generation == desired_generation {
                WrapperStatus::Current
            } else if descriptor.desired_present {
                WrapperStatus::NeedsPublish
            } else {
                WrapperStatus::NeedsRemoval
            };
        self.advance_and_store(arch_root)
    }

    pub fn mark_awaiting_removal(
        &mut self,
        arch_root: &Path,
        android_package: &str,
    ) -> Result<(), LauncherRegistryError> {
        let descriptor = self
            .descriptors
            .iter_mut()
            .find(|descriptor| descriptor.android_package == android_package)
            .ok_or(LauncherRegistryError::InvalidTransition)?;
        if descriptor.status != WrapperStatus::NeedsRemoval {
            return Err(LauncherRegistryError::InvalidTransition);
        }
        descriptor.status = WrapperStatus::AwaitingRemoval;
        self.advance_and_store(arch_root)
    }

    pub fn confirm_removed(
        &mut self,
        arch_root: &Path,
        android_package: &str,
    ) -> Result<(), LauncherRegistryError> {
        let index = self
            .descriptors
            .iter()
            .position(|descriptor| descriptor.android_package == android_package)
            .ok_or(LauncherRegistryError::InvalidTransition)?;
        if self.descriptors[index].status != WrapperStatus::AwaitingRemoval {
            return Err(LauncherRegistryError::InvalidTransition);
        }
        if self.descriptors[index].desired_present {
            self.descriptors[index].published_generation = 0;
            self.descriptors[index].pending_generation = 0;
            self.descriptors[index].status = WrapperStatus::NeedsPublish;
        } else {
            self.descriptors.remove(index);
        }
        self.advance_and_store(arch_root)
    }

    pub fn mark_failed(
        &mut self,
        arch_root: &Path,
        android_package: &str,
        operation_generation: u64,
    ) -> Result<(), LauncherRegistryError> {
        let index = self
            .descriptors
            .iter()
            .position(|descriptor| descriptor.android_package == android_package)
            .ok_or(LauncherRegistryError::InvalidTransition)?;
        let descriptor = &self.descriptors[index];
        let matching_publish = matches!(
            descriptor.status,
            WrapperStatus::Building | WrapperStatus::AwaitingInstall
        ) && descriptor.pending_generation == operation_generation;
        let matching_removal = descriptor.status == WrapperStatus::AwaitingRemoval
            && descriptor.desired_generation == operation_generation;
        if !matching_publish && !matching_removal {
            return Err(LauncherRegistryError::InvalidTransition);
        }
        if !descriptor.desired_present && descriptor.published_generation == 0 {
            self.descriptors.remove(index);
        } else {
            self.descriptors[index].pending_generation = 0;
            self.descriptors[index].status = WrapperStatus::Failed;
        }
        self.advance_and_store(arch_root)
    }

    pub fn mark_cancelled(
        &mut self,
        arch_root: &Path,
        android_package: &str,
        operation_generation: u64,
    ) -> Result<(), LauncherRegistryError> {
        let index = self
            .descriptors
            .iter()
            .position(|descriptor| descriptor.android_package == android_package)
            .ok_or(LauncherRegistryError::InvalidTransition)?;
        let descriptor = &self.descriptors[index];
        if descriptor.status != WrapperStatus::AwaitingInstall
            || descriptor.pending_generation != operation_generation
        {
            return Err(LauncherRegistryError::InvalidTransition);
        }
        if !descriptor.desired_present && descriptor.published_generation == 0 {
            self.descriptors.remove(index);
        } else {
            let descriptor = &mut self.descriptors[index];
            descriptor.pending_generation = 0;
            descriptor.status = if descriptor.desired_present {
                WrapperStatus::Cancelled
            } else {
                WrapperStatus::NeedsRemoval
            };
        }
        self.advance_and_store(arch_root)
    }

    pub fn mark_template_stale(
        &mut self,
        arch_root: &Path,
        android_package: &str,
        installed_generation: u64,
    ) -> Result<(), LauncherRegistryError> {
        if installed_generation == 0 || installed_generation > i32::MAX as u64 {
            return Err(LauncherRegistryError::InvalidTransition);
        }
        let index = self
            .descriptors
            .iter()
            .position(|descriptor| descriptor.android_package == android_package)
            .ok_or(LauncherRegistryError::InvalidTransition)?;
        if self.descriptors[index].desired_present
            && matches!(
                self.descriptors[index].status,
                WrapperStatus::NeedsRemoval | WrapperStatus::AwaitingRemoval
            )
        {
            return Err(LauncherRegistryError::InvalidTransition);
        }
        let recovered_generation = self.generation.max(installed_generation);
        let replacement_generation = recovered_generation
            .checked_add(1)
            .filter(|generation| *generation <= i32::MAX as u64)
            .ok_or(LauncherRegistryError::LimitExceeded)?;
        let descriptor = &mut self.descriptors[index];
        descriptor.published_generation = installed_generation;
        descriptor.pending_generation = 0;
        if descriptor.desired_present {
            descriptor.desired_generation = replacement_generation;
            descriptor.status = WrapperStatus::NeedsPublish;
        } else {
            descriptor.desired_generation = recovered_generation;
            descriptor.status = WrapperStatus::NeedsRemoval;
        }
        self.generation = recovered_generation;
        self.advance_and_store(arch_root)
    }

    pub fn retry_terminal(
        &mut self,
        arch_root: &Path,
        android_package: &str,
        desired_generation: u64,
    ) -> Result<(), LauncherRegistryError> {
        let descriptor = self
            .descriptors
            .iter_mut()
            .find(|descriptor| descriptor.android_package == android_package)
            .ok_or(LauncherRegistryError::InvalidTransition)?;
        if !matches!(
            descriptor.status,
            WrapperStatus::Failed | WrapperStatus::Cancelled | WrapperStatus::Dismissed
        ) || descriptor.pending_generation != 0
            || descriptor.desired_generation != desired_generation
        {
            return Err(LauncherRegistryError::InvalidTransition);
        }
        descriptor.status = if descriptor.desired_present {
            WrapperStatus::NeedsPublish
        } else if descriptor.published_generation != 0 {
            WrapperStatus::NeedsRemoval
        } else {
            return Err(LauncherRegistryError::InvalidTransition);
        };
        self.advance_and_store(arch_root)
    }

    pub fn mark_untrusted_replacement_removal(
        &mut self,
        arch_root: &Path,
        android_package: &str,
        desired_generation: u64,
    ) -> Result<(), LauncherRegistryError> {
        let descriptor = self
            .descriptors
            .iter_mut()
            .find(|descriptor| descriptor.android_package == android_package)
            .ok_or(LauncherRegistryError::InvalidTransition)?;
        if !descriptor.desired_present
            || descriptor.desired_generation != desired_generation
            || descriptor.published_generation != 0
            || descriptor.pending_generation != 0
            || descriptor.status != WrapperStatus::NeedsPublish
        {
            return Err(LauncherRegistryError::InvalidTransition);
        }
        descriptor.published_generation = desired_generation;
        descriptor.status = WrapperStatus::NeedsRemoval;
        self.advance_and_store(arch_root)
    }

    pub fn dismiss_cancelled(
        &mut self,
        arch_root: &Path,
        android_package: &str,
        desired_generation: u64,
    ) -> Result<(), LauncherRegistryError> {
        let descriptor = self
            .descriptors
            .iter_mut()
            .find(|descriptor| descriptor.android_package == android_package)
            .ok_or(LauncherRegistryError::InvalidTransition)?;
        if descriptor.status != WrapperStatus::Cancelled
            || descriptor.pending_generation != 0
            || descriptor.desired_generation != desired_generation
            || !descriptor.desired_present
        {
            return Err(LauncherRegistryError::InvalidTransition);
        }
        descriptor.status = WrapperStatus::Dismissed;
        self.advance_and_store(arch_root)
    }

    pub fn review_batch(
        &mut self,
        arch_root: &Path,
        decisions: &[LauncherReviewDecision],
    ) -> Result<(), LauncherRegistryError> {
        if decisions.is_empty() || decisions.len() > MAX_LAUNCHER_DESCRIPTORS {
            return Err(LauncherRegistryError::InvalidTransition);
        }
        let needs_review = self
            .descriptors
            .iter()
            .filter(|descriptor| descriptor.status == WrapperStatus::NeedsReview)
            .count();
        let covered_review = decisions
            .iter()
            .filter(|decision| {
                self.descriptors.iter().any(|descriptor| {
                    descriptor.android_package == decision.android_package
                        && descriptor.status == WrapperStatus::NeedsReview
                })
            })
            .count();
        if covered_review != needs_review {
            return Err(LauncherRegistryError::InvalidTransition);
        }

        let mut next = self.clone();
        for (decision_index, decision) in decisions.iter().enumerate() {
            if decisions[..decision_index]
                .iter()
                .any(|known| known.android_package == decision.android_package)
            {
                return Err(LauncherRegistryError::InvalidTransition);
            }
            let descriptor = next
                .descriptors
                .iter_mut()
                .find(|descriptor| descriptor.android_package == decision.android_package)
                .ok_or(LauncherRegistryError::InvalidTransition)?;
            let previous_status = descriptor.status;
            if descriptor.desired_generation != decision.desired_generation
                || !descriptor.desired_present
                || descriptor.pending_generation != 0
                || !matches!(
                    descriptor.status,
                    WrapperStatus::Failed | WrapperStatus::NeedsReview | WrapperStatus::Dismissed
                )
            {
                return Err(LauncherRegistryError::InvalidTransition);
            }
            descriptor.status = if decision.publish {
                if previous_status == WrapperStatus::Failed && descriptor.published_generation != 0
                {
                    WrapperStatus::NeedsRemoval
                } else {
                    WrapperStatus::NeedsPublish
                }
            } else if previous_status == WrapperStatus::NeedsReview {
                WrapperStatus::Dismissed
            } else {
                previous_status
            };
        }
        next.advance_and_store(arch_root)?;
        *self = next;
        Ok(())
    }

    /// Reconciles one descriptor with a wrapper whose signing certificate and
    /// Archphene generation metadata were verified by the Android caller.
    /// `None` means that PackageManager found no matching package.
    pub fn reconcile_android_package(
        &mut self,
        arch_root: &Path,
        android_package: &str,
        installed_generation: Option<u64>,
    ) -> Result<(), LauncherRegistryError> {
        let index = self
            .descriptors
            .iter()
            .position(|descriptor| descriptor.android_package == android_package)
            .ok_or(LauncherRegistryError::InvalidTransition)?;
        if installed_generation.is_some_and(|generation| {
            generation == 0 || generation > self.descriptors[index].desired_generation
        }) {
            return Err(LauncherRegistryError::InvalidTransition);
        }

        let previous = self.descriptors[index].clone();
        match installed_generation {
            Some(generation) => {
                let descriptor = &mut self.descriptors[index];
                descriptor.published_generation = generation;
                descriptor.pending_generation = 0;
                descriptor.status = if !descriptor.desired_present {
                    WrapperStatus::NeedsRemoval
                } else if generation == descriptor.desired_generation {
                    WrapperStatus::Current
                } else if matches!(
                    previous.status,
                    WrapperStatus::Failed | WrapperStatus::Cancelled | WrapperStatus::Dismissed
                ) {
                    previous.status
                } else {
                    WrapperStatus::NeedsPublish
                };
            }
            None if self.descriptors[index].desired_present => {
                let descriptor = &mut self.descriptors[index];
                descriptor.published_generation = 0;
                descriptor.pending_generation = 0;
                descriptor.status = match previous.status {
                    WrapperStatus::Failed | WrapperStatus::Cancelled | WrapperStatus::Dismissed => {
                        previous.status
                    }
                    WrapperStatus::NeedsReview => previous.status,
                    _ => WrapperStatus::NeedsPublish,
                };
            }
            None => {
                self.descriptors.remove(index);
                return self.advance_and_store(arch_root);
            }
        }
        if self.descriptors[index] == previous {
            Ok(())
        } else {
            self.advance_and_store(arch_root)
        }
    }

    /// Stops automatic publication for a deterministic package identity when
    /// Android reports an installed package whose signer or Archphene metadata
    /// cannot be trusted. The package is deliberately not adopted or removed.
    pub fn quarantine_android_package(
        &mut self,
        arch_root: &Path,
        android_package: &str,
    ) -> Result<(), LauncherRegistryError> {
        let descriptor = self
            .descriptors
            .iter_mut()
            .find(|descriptor| descriptor.android_package == android_package)
            .ok_or(LauncherRegistryError::InvalidTransition)?;
        if !descriptor.desired_present && descriptor.published_generation == 0 {
            return Err(LauncherRegistryError::InvalidTransition);
        }
        if descriptor.status == WrapperStatus::Failed && descriptor.pending_generation == 0 {
            return Ok(());
        }
        descriptor.pending_generation = 0;
        descriptor.status = WrapperStatus::Failed;
        self.advance_and_store(arch_root)
    }

    fn transition(
        &mut self,
        arch_root: &Path,
        android_package: &str,
        desired_generation: u64,
        from: WrapperStatus,
        to: WrapperStatus,
    ) -> Result<(), LauncherRegistryError> {
        let descriptor = self
            .descriptors
            .iter_mut()
            .find(|descriptor| descriptor.android_package == android_package)
            .ok_or(LauncherRegistryError::InvalidTransition)?;
        if descriptor.desired_generation != desired_generation
            || !descriptor.desired_present
            || descriptor.status != from
            || descriptor.pending_generation != desired_generation
        {
            return Err(LauncherRegistryError::InvalidTransition);
        }
        descriptor.status = to;
        self.advance_and_store(arch_root)
    }

    fn advance_and_store(&mut self, arch_root: &Path) -> Result<(), LauncherRegistryError> {
        self.generation = self
            .generation
            .checked_add(1)
            .ok_or(LauncherRegistryError::LimitExceeded)?;
        self.store(arch_root)
    }

    fn store(&self, arch_root: &Path) -> Result<(), LauncherRegistryError> {
        validate_registry(self)?;
        let directory = registry_directory(arch_root)?;
        recover_stale_temp(&directory)?;
        let bytes = encode_registry(self)?;
        let temporary = directory.join(REGISTRY_TEMP_FILE);
        let destination = directory.join(REGISTRY_FILE);
        let mut file = OpenOptions::new()
            .create_new(true)
            .write(true)
            .mode(0o600)
            .custom_flags(O_CLOEXEC | O_NOFOLLOW)
            .open(&temporary)?;
        let result = (|| -> Result<(), LauncherRegistryError> {
            file.write_all(&bytes)?;
            file.sync_all()?;
            fs::rename(&temporary, &destination)?;
            File::open(&directory)?.sync_all()?;
            Ok(())
        })();
        if result.is_err() {
            let _ = fs::remove_file(&temporary);
        }
        result
    }
}

fn retain_removed(
    old: &LauncherDescriptor,
    output: &mut Vec<LauncherDescriptor>,
    removed: &mut u16,
    unchanged: &mut u16,
) {
    if old.desired_present {
        *removed = removed.saturating_add(1);
    } else {
        *unchanged = unchanged.saturating_add(1);
    }
    if old.status == WrapperStatus::AwaitingInstall || old.published_generation != 0 {
        let mut descriptor = old.clone();
        if descriptor.desired_present {
            descriptor.desired_present = false;
            if descriptor.status != WrapperStatus::AwaitingInstall {
                descriptor.pending_generation = 0;
                descriptor.status = WrapperStatus::NeedsRemoval;
            }
        }
        output.push(descriptor);
    }
}

fn launcher_icon_digest(arch_root: &Path, icon: Option<&str>) -> Option<[u8; 32]> {
    let logical = resolve_desktop_icon(arch_root, icon?)?;
    if !logical
        .rsplit_once('.')
        .is_some_and(|(_, extension)| extension.eq_ignore_ascii_case("png"))
    {
        return None;
    }
    let path = arch_root.join(logical.strip_prefix('/')?);
    let metadata = fs::symlink_metadata(&path).ok()?;
    if metadata.file_type().is_symlink()
        || !metadata.is_file()
        || metadata.permissions().mode() & 0o022 != 0
        || metadata.len() < 33
        || metadata.len() > MAX_LAUNCHER_ICON_BYTES
    {
        return None;
    }
    let mut file = OpenOptions::new()
        .read(true)
        .custom_flags(O_NOFOLLOW | O_CLOEXEC)
        .open(path)
        .ok()?;
    let opened = file.metadata().ok()?;
    if !opened.is_file() || opened.len() != metadata.len() {
        return None;
    }
    let mut header = [0_u8; 24];
    file.read_exact(&mut header).ok()?;
    if !valid_png_header(&header) {
        return None;
    }
    let mut hash = Sha256::new();
    hash.update_streaming(&header);
    let mut total = u64::try_from(header.len()).ok()?;
    let mut buffer = [0_u8; 16 * 1024];
    loop {
        let read = file.read(&mut buffer).ok()?;
        if read == 0 {
            break;
        }
        total = total.checked_add(u64::try_from(read).ok()?)?;
        if total > MAX_LAUNCHER_ICON_BYTES {
            return None;
        }
        hash.update_streaming(&buffer[..read]);
    }
    (total == metadata.len()).then(|| hash.finalize())
}

fn valid_png_header(header: &[u8; 24]) -> bool {
    const SIGNATURE: &[u8; 8] = b"\x89PNG\r\n\x1a\n";
    let width = u32::from_be_bytes(header[16..20].try_into().expect("PNG width"));
    let height = u32::from_be_bytes(header[20..24].try_into().expect("PNG height"));
    &header[..8] == SIGNATURE
        && header[8..12] == 13_u32.to_be_bytes()
        && &header[12..16] == b"IHDR"
        && (1..=MAX_LAUNCHER_ICON_DIMENSION).contains(&width)
        && (1..=MAX_LAUNCHER_ICON_DIMENSION).contains(&height)
        && u64::from(width) * u64::from(height) <= MAX_LAUNCHER_ICON_PIXELS
}

fn descriptor_from_entry(
    arch_root: &Path,
    entry: &DesktopEntry,
    desired_generation: u64,
    published_generation: u64,
) -> LauncherDescriptor {
    let descriptor_id = descriptor_identity(&entry.desktop_id);
    let icon_digest = launcher_icon_digest(arch_root, entry.icon.as_deref());
    LauncherDescriptor {
        descriptor_id,
        android_package: android_package(&descriptor_id),
        desktop_id: entry.desktop_id.clone(),
        source_package: entry.source_package.clone(),
        executable_package: entry.executable_package.clone(),
        name: entry.name.clone(),
        executable: entry.executable.clone(),
        arguments: entry.arguments.clone(),
        try_exec: entry.try_exec.clone(),
        icon: entry.icon.clone(),
        icon_digest,
        mime_types: entry.mime_types.clone(),
        terminal: entry.terminal,
        bridge_capabilities: entry.bridge_capabilities,
        observed_topology: 0,
        integration_observed: false,
        integration_observation_complete: false,
        desired_present: true,
        desired_generation,
        published_generation,
        pending_generation: 0,
        status: WrapperStatus::NeedsPublish,
        content_digest: descriptor_content_digest(entry, icon_digest.as_ref()),
    }
}

fn descriptor_identity(desktop_id: &str) -> [u8; 32] {
    // Android package identities are persistent. Keep the original v1
    // framing exactly; mutable descriptor content uses the corrected,
    // independently versioned v2 digest below.
    let mut hash = Sha256::new();
    hash.update(b"org.archphene.launcher-id.v1\0");
    hash_field(&mut hash, desktop_id.as_bytes());
    hash.finalize()
}

fn descriptor_content_digest(entry: &DesktopEntry, icon_digest: Option<&[u8; 32]>) -> [u8; 32] {
    descriptor_content_digest_fields(
        &entry.desktop_id,
        entry.source_package.as_deref(),
        entry.executable_package.as_deref(),
        &entry.name,
        &entry.executable,
        &entry.arguments,
        entry.try_exec.as_deref(),
        entry.icon.as_deref(),
        icon_digest,
        &entry.mime_types,
        entry.terminal,
        entry.bridge_capabilities,
    )
}

fn descriptor_digest(descriptor: &LauncherDescriptor) -> [u8; 32] {
    descriptor_content_digest_fields(
        &descriptor.desktop_id,
        descriptor.source_package.as_deref(),
        descriptor.executable_package.as_deref(),
        &descriptor.name,
        &descriptor.executable,
        &descriptor.arguments,
        descriptor.try_exec.as_deref(),
        descriptor.icon.as_deref(),
        descriptor.icon_digest.as_ref(),
        &descriptor.mime_types,
        descriptor.terminal,
        descriptor.bridge_capabilities,
    )
}

#[allow(clippy::too_many_arguments)]
fn descriptor_content_digest_fields(
    desktop_id: &str,
    source_package: Option<&str>,
    executable_package: Option<&str>,
    name: &str,
    executable: &str,
    arguments: &[ExecArgument],
    try_exec: Option<&str>,
    icon: Option<&str>,
    icon_digest: Option<&[u8; 32]>,
    mime_types: &[String],
    terminal: bool,
    bridge_capabilities: u8,
) -> [u8; 32] {
    descriptor_content_digest_fields_version(
        desktop_id,
        source_package,
        executable_package,
        name,
        executable,
        arguments,
        try_exec,
        icon,
        icon_digest,
        mime_types,
        terminal,
        Some(bridge_capabilities),
    )
}

fn observation_descriptor_digest(descriptor: &LauncherDescriptor) -> [u8; 32] {
    descriptor_content_digest_fields_version(
        &descriptor.desktop_id,
        descriptor.source_package.as_deref(),
        descriptor.executable_package.as_deref(),
        &descriptor.name,
        &descriptor.executable,
        &descriptor.arguments,
        descriptor.try_exec.as_deref(),
        descriptor.icon.as_deref(),
        descriptor.icon_digest.as_ref(),
        &descriptor.mime_types,
        descriptor.terminal,
        None,
    )
}

#[allow(clippy::too_many_arguments)]
fn descriptor_content_digest_fields_version(
    desktop_id: &str,
    source_package: Option<&str>,
    executable_package: Option<&str>,
    name: &str,
    executable: &str,
    arguments: &[ExecArgument],
    try_exec: Option<&str>,
    icon: Option<&str>,
    icon_digest: Option<&[u8; 32]>,
    mime_types: &[String],
    terminal: bool,
    bridge_capabilities: Option<u8>,
) -> [u8; 32] {
    let mut bytes = Vec::with_capacity(2048);
    bytes.extend_from_slice(if bridge_capabilities.is_some() {
        b"org.archphene.launcher-content.v4\0"
    } else {
        b"org.archphene.launcher-content.v3\0"
    });
    push_digest_field(&mut bytes, desktop_id.as_bytes());
    push_digest_optional(&mut bytes, source_package);
    push_digest_optional(&mut bytes, executable_package);
    push_digest_field(&mut bytes, name.as_bytes());
    push_digest_field(&mut bytes, executable.as_bytes());
    bytes.push(u8::from(terminal));
    if let Some(bridge_capabilities) = bridge_capabilities {
        bytes.push(bridge_capabilities);
    }
    push_digest_optional(&mut bytes, try_exec);
    push_digest_optional(&mut bytes, icon);
    match icon_digest {
        Some(icon_digest) => {
            bytes.push(1);
            bytes.extend_from_slice(icon_digest);
        }
        None => bytes.push(0),
    }
    bytes.push(u8::try_from(arguments.len()).unwrap_or(u8::MAX));
    for argument in arguments {
        match argument {
            ExecArgument::Literal(value) => {
                bytes.push(0);
                push_digest_field(&mut bytes, value.as_bytes());
            }
            ExecArgument::SingleFile => bytes.push(1),
            ExecArgument::MultipleFiles => bytes.push(2),
            ExecArgument::SingleUrl => bytes.push(3),
            ExecArgument::MultipleUrls => bytes.push(4),
            ExecArgument::Icon => bytes.push(5),
            ExecArgument::DisplayName => bytes.push(6),
            ExecArgument::DesktopFile => bytes.push(7),
        }
    }
    bytes.push(u8::try_from(mime_types.len()).unwrap_or(u8::MAX));
    for mime_type in mime_types {
        push_digest_field(&mut bytes, mime_type.as_bytes());
    }
    sha256(&bytes)
}

fn previous_descriptor_digest(descriptor: &LauncherDescriptor) -> [u8; 32] {
    let mut bytes = Vec::with_capacity(2048);
    bytes.extend_from_slice(b"org.archphene.launcher-content.v2\0");
    push_digest_field(&mut bytes, descriptor.desktop_id.as_bytes());
    push_digest_optional(&mut bytes, descriptor.source_package.as_deref());
    push_digest_field(&mut bytes, descriptor.name.as_bytes());
    push_digest_field(&mut bytes, descriptor.executable.as_bytes());
    bytes.push(u8::from(descriptor.terminal));
    push_digest_optional(&mut bytes, descriptor.try_exec.as_deref());
    push_digest_optional(&mut bytes, descriptor.icon.as_deref());
    match descriptor.icon_digest {
        Some(icon_digest) => {
            bytes.push(1);
            bytes.extend_from_slice(&icon_digest);
        }
        None => bytes.push(0),
    }
    bytes.push(u8::try_from(descriptor.arguments.len()).unwrap_or(u8::MAX));
    for argument in &descriptor.arguments {
        match argument {
            ExecArgument::Literal(value) => {
                bytes.push(0);
                push_digest_field(&mut bytes, value.as_bytes());
            }
            ExecArgument::SingleFile => bytes.push(1),
            ExecArgument::MultipleFiles => bytes.push(2),
            ExecArgument::SingleUrl => bytes.push(3),
            ExecArgument::MultipleUrls => bytes.push(4),
            ExecArgument::Icon => bytes.push(5),
            ExecArgument::DisplayName => bytes.push(6),
            ExecArgument::DesktopFile => bytes.push(7),
        }
    }
    bytes.push(u8::try_from(descriptor.mime_types.len()).unwrap_or(u8::MAX));
    for mime_type in &descriptor.mime_types {
        push_digest_field(&mut bytes, mime_type.as_bytes());
    }
    sha256(&bytes)
}

fn legacy_descriptor_digest(descriptor: &LauncherDescriptor) -> [u8; 32] {
    let mut hash = Sha256::new();
    hash.update(b"org.archphene.launcher-content.v1\0");
    hash_field(&mut hash, descriptor.desktop_id.as_bytes());
    hash_optional(&mut hash, descriptor.source_package.as_deref());
    hash_field(&mut hash, descriptor.name.as_bytes());
    hash_field(&mut hash, descriptor.executable.as_bytes());
    hash.update(&[u8::from(descriptor.terminal)]);
    hash_optional(&mut hash, descriptor.try_exec.as_deref());
    hash_optional(&mut hash, descriptor.icon.as_deref());
    hash.update(&[u8::try_from(descriptor.arguments.len()).unwrap_or(u8::MAX)]);
    for argument in &descriptor.arguments {
        match argument {
            ExecArgument::Literal(value) => {
                hash.update(&[0]);
                hash_field(&mut hash, value.as_bytes());
            }
            ExecArgument::SingleFile => hash.update(&[1]),
            ExecArgument::MultipleFiles => hash.update(&[2]),
            ExecArgument::SingleUrl => hash.update(&[3]),
            ExecArgument::MultipleUrls => hash.update(&[4]),
            ExecArgument::Icon => hash.update(&[5]),
            ExecArgument::DisplayName => hash.update(&[6]),
            ExecArgument::DesktopFile => hash.update(&[7]),
        }
    }
    hash.update(&[u8::try_from(descriptor.mime_types.len()).unwrap_or(u8::MAX)]);
    for mime_type in &descriptor.mime_types {
        hash_field(&mut hash, mime_type.as_bytes());
    }
    hash.finalize()
}

fn push_digest_field(output: &mut Vec<u8>, bytes: &[u8]) {
    output.extend_from_slice(&u32::try_from(bytes.len()).unwrap_or(u32::MAX).to_le_bytes());
    output.extend_from_slice(bytes);
}

fn push_digest_optional(output: &mut Vec<u8>, value: Option<&str>) {
    match value {
        Some(value) => {
            output.push(1);
            push_digest_field(output, value.as_bytes());
        }
        None => output.push(0),
    }
}

fn android_package(descriptor_id: &[u8; 32]) -> String {
    let mut package = String::with_capacity(PACKAGE_PREFIX.len() + 32);
    package.push_str(PACKAGE_PREFIX);
    const HEX: &[u8; 16] = b"0123456789abcdef";
    for byte in &descriptor_id[..16] {
        package.push(char::from(HEX[usize::from(byte >> 4)]));
        package.push(char::from(HEX[usize::from(byte & 0x0f)]));
    }
    package
}

fn hash_field(hash: &mut Sha256, bytes: &[u8]) {
    hash.update(&u32::try_from(bytes.len()).unwrap_or(u32::MAX).to_le_bytes());
    hash.update(bytes);
}

fn hash_optional(hash: &mut Sha256, value: Option<&str>) {
    match value {
        Some(value) => {
            hash.update(&[1]);
            hash_field(hash, value.as_bytes());
        }
        None => hash.update(&[0]),
    }
}

fn registry_directory(arch_root: &Path) -> Result<PathBuf, LauncherRegistryError> {
    if !arch_root.is_absolute() {
        return Err(LauncherRegistryError::InvalidRoot);
    }
    let root_metadata =
        fs::symlink_metadata(arch_root).map_err(|_| LauncherRegistryError::InvalidRoot)?;
    if root_metadata.file_type().is_symlink() || !root_metadata.is_dir() {
        return Err(LauncherRegistryError::InvalidRoot);
    }
    let root = arch_root
        .canonicalize()
        .map_err(|_| LauncherRegistryError::InvalidRoot)?;
    let directory = root.join(REGISTRY_DIRECTORY);
    let metadata = fs::symlink_metadata(&directory)?;
    if metadata.file_type().is_symlink() || !metadata.is_dir() {
        return Err(LauncherRegistryError::UnsafePath(directory));
    }
    let canonical = directory.canonicalize()?;
    if canonical == root || !canonical.starts_with(&root) {
        return Err(LauncherRegistryError::UnsafePath(directory));
    }
    Ok(canonical)
}

fn recover_stale_temp(directory: &Path) -> Result<(), LauncherRegistryError> {
    let temporary = directory.join(REGISTRY_TEMP_FILE);
    let metadata = match fs::symlink_metadata(&temporary) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error.into()),
    };
    if metadata.file_type().is_symlink() || !metadata.is_file() {
        return Err(LauncherRegistryError::UnsafePath(temporary));
    }
    fs::remove_file(temporary)?;
    File::open(directory)?.sync_all()?;
    Ok(())
}

fn registry_file_exists(arch_root: &Path) -> Result<bool, LauncherRegistryError> {
    let path = registry_directory(arch_root)?.join(REGISTRY_FILE);
    match fs::symlink_metadata(&path) {
        Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_file() => {
            Err(LauncherRegistryError::UnsafePath(path))
        }
        Ok(_) => Ok(true),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(false),
        Err(error) => Err(error.into()),
    }
}

fn encode_registry(registry: &LauncherRegistry) -> Result<Vec<u8>, LauncherRegistryError> {
    encode_registry_version(registry, REGISTRY_VERSION)
}

fn encode_registry_version(
    registry: &LauncherRegistry,
    version: u32,
) -> Result<Vec<u8>, LauncherRegistryError> {
    if !matches!(
        version,
        LEGACY_REGISTRY_VERSION
            | ICON_REGISTRY_VERSION
            | PREVIOUS_REGISTRY_VERSION
            | EXECUTABLE_PACKAGE_REGISTRY_VERSION
            | OBSERVATION_REGISTRY_VERSION
            | REGISTRY_VERSION
    ) {
        return Err(LauncherRegistryError::Corrupt);
    }
    let mut body = Vec::with_capacity(registry.descriptors.len().saturating_mul(1024));
    for descriptor in &registry.descriptors {
        body.extend_from_slice(&descriptor.descriptor_id);
        let content_digest = match version {
            LEGACY_REGISTRY_VERSION => legacy_descriptor_digest(descriptor),
            ICON_REGISTRY_VERSION | PREVIOUS_REGISTRY_VERSION => {
                previous_descriptor_digest(descriptor)
            }
            EXECUTABLE_PACKAGE_REGISTRY_VERSION | OBSERVATION_REGISTRY_VERSION => {
                observation_descriptor_digest(descriptor)
            }
            REGISTRY_VERSION => descriptor.content_digest,
            _ => return Err(LauncherRegistryError::Corrupt),
        };
        body.extend_from_slice(&content_digest);
        body.extend_from_slice(&descriptor.desired_generation.to_le_bytes());
        body.extend_from_slice(&descriptor.published_generation.to_le_bytes());
        body.extend_from_slice(&descriptor.pending_generation.to_le_bytes());
        body.push(descriptor.status as u8);
        body.push(u8::from(descriptor.desired_present));
        body.push(u8::from(descriptor.terminal));
        push_string(&mut body, &descriptor.android_package)?;
        push_string(&mut body, &descriptor.desktop_id)?;
        push_optional_string(&mut body, descriptor.source_package.as_deref())?;
        if version >= EXECUTABLE_PACKAGE_REGISTRY_VERSION {
            push_optional_string(&mut body, descriptor.executable_package.as_deref())?;
        }
        if version >= REGISTRY_VERSION {
            body.push(descriptor.bridge_capabilities);
        }
        if version >= OBSERVATION_REGISTRY_VERSION {
            body.extend_from_slice(&descriptor.observed_topology.to_le_bytes());
            body.push(u8::from(descriptor.integration_observed));
            body.push(u8::from(descriptor.integration_observation_complete));
        }
        push_string(&mut body, &descriptor.name)?;
        push_string(&mut body, &descriptor.executable)?;
        push_optional_string(&mut body, descriptor.try_exec.as_deref())?;
        push_optional_string(&mut body, descriptor.icon.as_deref())?;
        if version >= ICON_REGISTRY_VERSION {
            match descriptor.icon_digest {
                Some(digest) => {
                    body.push(1);
                    body.extend_from_slice(&digest);
                }
                None => body.push(0),
            }
        }
        body.push(
            u8::try_from(descriptor.arguments.len())
                .map_err(|_| LauncherRegistryError::LimitExceeded)?,
        );
        for argument in &descriptor.arguments {
            match argument {
                ExecArgument::Literal(value) => {
                    body.push(0);
                    push_string(&mut body, value)?;
                }
                ExecArgument::SingleFile => body.push(1),
                ExecArgument::MultipleFiles => body.push(2),
                ExecArgument::SingleUrl => body.push(3),
                ExecArgument::MultipleUrls => body.push(4),
                ExecArgument::Icon => body.push(5),
                ExecArgument::DisplayName => body.push(6),
                ExecArgument::DesktopFile => body.push(7),
            }
        }
        body.push(
            u8::try_from(descriptor.mime_types.len())
                .map_err(|_| LauncherRegistryError::LimitExceeded)?,
        );
        for mime_type in &descriptor.mime_types {
            push_string(&mut body, mime_type)?;
        }
        if REGISTRY_HEADER_BYTES
            .checked_add(body.len())
            .is_none_or(|length| length > MAX_LAUNCHER_REGISTRY_BYTES)
        {
            return Err(LauncherRegistryError::LimitExceeded);
        }
    }
    let checksum = sha256(&body);
    let mut output = Vec::with_capacity(REGISTRY_HEADER_BYTES + body.len());
    output.extend_from_slice(REGISTRY_MAGIC);
    output.extend_from_slice(&version.to_le_bytes());
    output.extend_from_slice(&registry.generation.to_le_bytes());
    output.extend_from_slice(
        &u32::try_from(registry.descriptors.len())
            .map_err(|_| LauncherRegistryError::LimitExceeded)?
            .to_le_bytes(),
    );
    output.extend_from_slice(&checksum);
    output.extend_from_slice(&body);
    Ok(output)
}

fn decode_registry(bytes: &[u8]) -> Result<LauncherRegistry, LauncherRegistryError> {
    if bytes.len() < REGISTRY_HEADER_BYTES || bytes.len() > MAX_LAUNCHER_REGISTRY_BYTES {
        return Err(LauncherRegistryError::Corrupt);
    }
    let mut cursor = Cursor::new(bytes);
    if cursor.take(8)? != REGISTRY_MAGIC {
        return Err(LauncherRegistryError::Corrupt);
    }
    let version = cursor.u32()?;
    if !matches!(
        version,
        LEGACY_REGISTRY_VERSION
            | ICON_REGISTRY_VERSION
            | PREVIOUS_REGISTRY_VERSION
            | EXECUTABLE_PACKAGE_REGISTRY_VERSION
            | OBSERVATION_REGISTRY_VERSION
            | REGISTRY_VERSION
    ) {
        return Err(LauncherRegistryError::Corrupt);
    }
    let generation = cursor.u64()?;
    let count = usize::try_from(cursor.u32()?).map_err(|_| LauncherRegistryError::LimitExceeded)?;
    if count > MAX_LAUNCHER_DESCRIPTORS {
        return Err(LauncherRegistryError::LimitExceeded);
    }
    let expected_checksum = cursor.array_32()?;
    let body_start = cursor.position;
    if sha256(&bytes[body_start..]) != expected_checksum {
        return Err(LauncherRegistryError::Corrupt);
    }
    let mut descriptors = Vec::with_capacity(count);
    for _ in 0..count {
        let descriptor_id = cursor.array_32()?;
        let content_digest = cursor.array_32()?;
        let desired_generation = cursor.u64()?;
        let published_generation = cursor.u64()?;
        let pending_generation = cursor.u64()?;
        let status =
            WrapperStatus::from_raw(cursor.byte()?).ok_or(LauncherRegistryError::Corrupt)?;
        let desired_present = cursor.boolean()?;
        let terminal = cursor.boolean()?;
        let android_package = cursor.string()?;
        let desktop_id = cursor.string()?;
        let source_package = cursor.optional_string()?;
        let executable_package = if version >= EXECUTABLE_PACKAGE_REGISTRY_VERSION {
            cursor.optional_string()?
        } else {
            None
        };
        let bridge_capabilities = if version >= REGISTRY_VERSION {
            cursor.byte()?
        } else {
            0
        };
        let (observed_topology, integration_observed, integration_observation_complete) =
            if version >= OBSERVATION_REGISTRY_VERSION {
                (cursor.u16()?, cursor.boolean()?, cursor.boolean()?)
            } else {
                (0, false, false)
            };
        let name = cursor.string()?;
        let executable = cursor.string()?;
        let try_exec = cursor.optional_string()?;
        let icon = cursor.optional_string()?;
        let icon_digest = if version >= ICON_REGISTRY_VERSION {
            match cursor.byte()? {
                0 => None,
                1 => Some(cursor.array_32()?),
                _ => return Err(LauncherRegistryError::Corrupt),
            }
        } else {
            None
        };
        let argument_count = usize::from(cursor.byte()?);
        if argument_count > 32 {
            return Err(LauncherRegistryError::LimitExceeded);
        }
        let mut arguments = Vec::with_capacity(argument_count);
        for _ in 0..argument_count {
            arguments.push(match cursor.byte()? {
                0 => ExecArgument::Literal(cursor.string()?),
                1 => ExecArgument::SingleFile,
                2 => ExecArgument::MultipleFiles,
                3 => ExecArgument::SingleUrl,
                4 => ExecArgument::MultipleUrls,
                5 => ExecArgument::Icon,
                6 => ExecArgument::DisplayName,
                7 => ExecArgument::DesktopFile,
                _ => return Err(LauncherRegistryError::Corrupt),
            });
        }
        let mime_count = usize::from(cursor.byte()?);
        if mime_count > 16 {
            return Err(LauncherRegistryError::LimitExceeded);
        }
        let mut mime_types = Vec::with_capacity(mime_count);
        for _ in 0..mime_count {
            mime_types.push(cursor.string()?);
        }
        let mut descriptor = LauncherDescriptor {
            descriptor_id,
            android_package,
            desktop_id,
            source_package,
            executable_package,
            name,
            executable,
            arguments,
            try_exec,
            icon,
            icon_digest,
            mime_types,
            terminal,
            bridge_capabilities,
            observed_topology,
            integration_observed,
            integration_observation_complete,
            desired_present,
            desired_generation,
            published_generation,
            pending_generation,
            status,
            content_digest,
        };
        match version {
            LEGACY_REGISTRY_VERSION => {
                if descriptor.content_digest != legacy_descriptor_digest(&descriptor) {
                    return Err(LauncherRegistryError::Corrupt);
                }
                descriptor.content_digest = descriptor_digest(&descriptor);
            }
            ICON_REGISTRY_VERSION | PREVIOUS_REGISTRY_VERSION => {
                if descriptor.content_digest != previous_descriptor_digest(&descriptor) {
                    return Err(LauncherRegistryError::Corrupt);
                }
                descriptor.content_digest = descriptor_digest(&descriptor);
            }
            EXECUTABLE_PACKAGE_REGISTRY_VERSION | OBSERVATION_REGISTRY_VERSION => {
                if descriptor.content_digest != observation_descriptor_digest(&descriptor) {
                    return Err(LauncherRegistryError::Corrupt);
                }
                descriptor.content_digest = descriptor_digest(&descriptor);
            }
            REGISTRY_VERSION => {}
            _ => return Err(LauncherRegistryError::Corrupt),
        }
        descriptors.push(descriptor);
    }
    if cursor.position != bytes.len() {
        return Err(LauncherRegistryError::Corrupt);
    }
    let registry = LauncherRegistry {
        schema_version: version,
        generation,
        descriptors,
    };
    validate_registry(&registry)?;
    Ok(registry)
}

fn validate_registry(registry: &LauncherRegistry) -> Result<(), LauncherRegistryError> {
    if registry.descriptors.len() > MAX_LAUNCHER_DESCRIPTORS {
        return Err(LauncherRegistryError::LimitExceeded);
    }
    for descriptor in &registry.descriptors {
        if descriptor.desired_generation == 0
            || descriptor.published_generation > descriptor.desired_generation
            || descriptor.pending_generation > descriptor.desired_generation
            || descriptor.descriptor_id != descriptor_identity(&descriptor.desktop_id)
            || descriptor.android_package != android_package(&descriptor.descriptor_id)
            || descriptor.content_digest != descriptor_digest(descriptor)
            || descriptor.bridge_capabilities & !BRIDGE_CAPABILITY_MASK != 0
            || !valid_descriptor_strings(descriptor)
            || !descriptor.integration_observed
                && (descriptor.observed_topology != 0
                    || descriptor.integration_observation_complete)
        {
            return Err(LauncherRegistryError::Corrupt);
        }
        let valid_state = if descriptor.desired_present {
            match descriptor.status {
                WrapperStatus::Current => {
                    descriptor.published_generation == descriptor.desired_generation
                        && descriptor.published_generation != 0
                        && descriptor.pending_generation == 0
                }
                WrapperStatus::NeedsPublish => {
                    descriptor.published_generation != descriptor.desired_generation
                        && descriptor.pending_generation == 0
                }
                WrapperStatus::Building | WrapperStatus::AwaitingInstall => {
                    descriptor.published_generation != descriptor.desired_generation
                        && descriptor.pending_generation != 0
                }
                WrapperStatus::Failed | WrapperStatus::Cancelled | WrapperStatus::Dismissed => {
                    descriptor.pending_generation == 0
                }
                WrapperStatus::NeedsReview => {
                    descriptor.published_generation == 0 && descriptor.pending_generation == 0
                }
                WrapperStatus::NeedsRemoval | WrapperStatus::AwaitingRemoval => {
                    descriptor.published_generation != 0 && descriptor.pending_generation == 0
                }
            }
        } else {
            match descriptor.status {
                WrapperStatus::AwaitingInstall => descriptor.pending_generation != 0,
                WrapperStatus::NeedsRemoval
                | WrapperStatus::AwaitingRemoval
                | WrapperStatus::Failed => {
                    descriptor.published_generation != 0 && descriptor.pending_generation == 0
                }
                _ => false,
            }
        };
        if !valid_state {
            return Err(LauncherRegistryError::Corrupt);
        }
    }
    if registry
        .descriptors
        .windows(2)
        .any(|descriptors| descriptors[0].desktop_id >= descriptors[1].desktop_id)
    {
        return Err(LauncherRegistryError::Corrupt);
    }
    validate_identities(&registry.descriptors)
}

fn validate_identities(descriptors: &[LauncherDescriptor]) -> Result<(), LauncherRegistryError> {
    for (index, descriptor) in descriptors.iter().enumerate() {
        if descriptors[..index]
            .iter()
            .any(|known| known.android_package == descriptor.android_package)
        {
            return Err(LauncherRegistryError::IdentityCollision);
        }
    }
    Ok(())
}

fn valid_descriptor_strings(descriptor: &LauncherDescriptor) -> bool {
    valid_text(&descriptor.desktop_id, 240)
        && descriptor.desktop_id.ends_with(".desktop")
        && !descriptor.desktop_id.starts_with('.')
        && !descriptor.desktop_id.contains(['/', '\\'])
        && valid_text(&descriptor.name, 256)
        && valid_path(&descriptor.executable)
        && descriptor
            .source_package
            .as_deref()
            .is_none_or(valid_package_name)
        && descriptor
            .executable_package
            .as_deref()
            .is_none_or(valid_package_name)
        && descriptor.try_exec.as_deref().is_none_or(valid_path)
        && descriptor
            .icon
            .as_deref()
            .is_none_or(|value| valid_text(value, 240))
        && descriptor.arguments.len() <= 32
        && descriptor.arguments.iter().all(|argument| match argument {
            ExecArgument::Literal(value) => valid_text(value, 512),
            _ => true,
        })
        && descriptor.mime_types.len() <= 16
        && descriptor
            .mime_types
            .iter()
            .all(|mime_type| valid_text(mime_type, 129) && mime_type.contains('/'))
}

fn valid_text(value: &str, maximum: usize) -> bool {
    !value.is_empty()
        && value.len() <= maximum
        && !value.chars().any(|character| {
            character.is_control()
                || character == '\0'
                || matches!(
                    character,
                    '\u{061c}'
                        | '\u{200e}'
                        | '\u{200f}'
                        | '\u{202a}'..='\u{202e}'
                        | '\u{2066}'..='\u{2069}'
                )
        })
}

fn valid_path(value: &str) -> bool {
    valid_text(value, 512)
        && value.starts_with('/')
        && value.strip_prefix('/').is_some_and(|relative| {
            !relative.is_empty()
                && relative
                    .split('/')
                    .all(|part| !part.is_empty() && part != "." && part != "..")
        })
}

fn valid_package_name(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 128
        && value != "."
        && value != ".."
        && value.bytes().all(|byte| {
            byte.is_ascii_alphanumeric() || matches!(byte, b'@' | b'.' | b'_' | b'+' | b'-')
        })
}

fn push_string(output: &mut Vec<u8>, value: &str) -> Result<(), LauncherRegistryError> {
    let length = u16::try_from(value.len()).map_err(|_| LauncherRegistryError::LimitExceeded)?;
    output.extend_from_slice(&length.to_le_bytes());
    output.extend_from_slice(value.as_bytes());
    Ok(())
}

fn push_optional_string(
    output: &mut Vec<u8>,
    value: Option<&str>,
) -> Result<(), LauncherRegistryError> {
    match value {
        Some(value) => {
            output.push(1);
            push_string(output, value)
        }
        None => {
            output.push(0);
            Ok(())
        }
    }
}

struct Cursor<'a> {
    bytes: &'a [u8],
    position: usize,
}

impl<'a> Cursor<'a> {
    const fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, position: 0 }
    }

    fn take(&mut self, length: usize) -> Result<&'a [u8], LauncherRegistryError> {
        let end = self
            .position
            .checked_add(length)
            .ok_or(LauncherRegistryError::Corrupt)?;
        let value = self
            .bytes
            .get(self.position..end)
            .ok_or(LauncherRegistryError::Corrupt)?;
        self.position = end;
        Ok(value)
    }

    fn byte(&mut self) -> Result<u8, LauncherRegistryError> {
        Ok(self.take(1)?[0])
    }

    fn boolean(&mut self) -> Result<bool, LauncherRegistryError> {
        match self.byte()? {
            0 => Ok(false),
            1 => Ok(true),
            _ => Err(LauncherRegistryError::Corrupt),
        }
    }

    fn u16(&mut self) -> Result<u16, LauncherRegistryError> {
        let mut bytes = [0_u8; 2];
        bytes.copy_from_slice(self.take(2)?);
        Ok(u16::from_le_bytes(bytes))
    }

    fn u32(&mut self) -> Result<u32, LauncherRegistryError> {
        let mut bytes = [0_u8; 4];
        bytes.copy_from_slice(self.take(4)?);
        Ok(u32::from_le_bytes(bytes))
    }

    fn u64(&mut self) -> Result<u64, LauncherRegistryError> {
        let mut bytes = [0_u8; 8];
        bytes.copy_from_slice(self.take(8)?);
        Ok(u64::from_le_bytes(bytes))
    }

    fn array_32(&mut self) -> Result<[u8; 32], LauncherRegistryError> {
        let mut bytes = [0_u8; 32];
        bytes.copy_from_slice(self.take(32)?);
        Ok(bytes)
    }

    fn string(&mut self) -> Result<String, LauncherRegistryError> {
        let length = usize::from({
            let mut bytes = [0_u8; 2];
            bytes.copy_from_slice(self.take(2)?);
            u16::from_le_bytes(bytes)
        });
        let bytes = self.take(length)?;
        std::str::from_utf8(bytes)
            .map(str::to_owned)
            .map_err(|_| LauncherRegistryError::Corrupt)
    }

    fn optional_string(&mut self) -> Result<Option<String>, LauncherRegistryError> {
        match self.byte()? {
            0 => Ok(None),
            1 => Ok(Some(self.string()?)),
            _ => Err(LauncherRegistryError::Corrupt),
        }
    }
}

fn sha256(bytes: &[u8]) -> [u8; 32] {
    let mut hash = Sha256::new();
    hash.update(bytes);
    hash.finalize()
}

struct Sha256 {
    state: [u32; 8],
    buffer: [u8; 64],
    buffer_length: usize,
    total_bytes: u64,
}

impl Sha256 {
    const fn new() -> Self {
        Self {
            state: [
                0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab,
                0x5be0cd19,
            ],
            buffer: [0; 64],
            buffer_length: 0,
            total_bytes: 0,
        }
    }

    fn update(&mut self, mut input: &[u8]) {
        self.total_bytes = self.total_bytes.saturating_add(input.len() as u64);
        if self.buffer_length != 0 {
            let copy = (64 - self.buffer_length).min(input.len());
            self.buffer[self.buffer_length..self.buffer_length + copy]
                .copy_from_slice(&input[..copy]);
            self.buffer_length += copy;
            input = &input[copy..];
            if self.buffer_length == 64 {
                let block = self.buffer;
                self.compress(&block);
                self.buffer_length = 0;
            }
        }
        while input.len() >= 64 {
            let mut block = [0_u8; 64];
            block.copy_from_slice(&input[..64]);
            self.compress(&block);
            input = &input[64..];
        }
        self.buffer[..input.len()].copy_from_slice(input);
        self.buffer_length = input.len();
    }

    fn update_streaming(&mut self, mut input: &[u8]) {
        self.total_bytes = self.total_bytes.saturating_add(input.len() as u64);
        if self.buffer_length != 0 {
            let copy = (64 - self.buffer_length).min(input.len());
            self.buffer[self.buffer_length..self.buffer_length + copy]
                .copy_from_slice(&input[..copy]);
            self.buffer_length += copy;
            input = &input[copy..];
            if self.buffer_length < 64 {
                return;
            }
            let block = self.buffer;
            self.compress(&block);
            self.buffer_length = 0;
        }
        while input.len() >= 64 {
            let mut block = [0_u8; 64];
            block.copy_from_slice(&input[..64]);
            self.compress(&block);
            input = &input[64..];
        }
        self.buffer[..input.len()].copy_from_slice(input);
        self.buffer_length = input.len();
    }

    fn finalize(mut self) -> [u8; 32] {
        let bit_length = self.total_bytes.saturating_mul(8);
        self.buffer[self.buffer_length] = 0x80;
        self.buffer_length += 1;
        if self.buffer_length > 56 {
            self.buffer[self.buffer_length..].fill(0);
            let block = self.buffer;
            self.compress(&block);
            self.buffer = [0; 64];
        } else {
            self.buffer[self.buffer_length..56].fill(0);
        }
        self.buffer[56..64].copy_from_slice(&bit_length.to_be_bytes());
        let block = self.buffer;
        self.compress(&block);
        let mut output = [0_u8; 32];
        for (index, word) in self.state.iter().enumerate() {
            output[index * 4..index * 4 + 4].copy_from_slice(&word.to_be_bytes());
        }
        output
    }

    fn compress(&mut self, block: &[u8; 64]) {
        const K: [u32; 64] = [
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4,
            0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe,
            0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f,
            0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
            0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc,
            0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
            0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116,
            0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7,
            0xc67178f2,
        ];
        let mut words = [0_u32; 64];
        for (index, chunk) in block.chunks_exact(4).take(16).enumerate() {
            words[index] = u32::from_be_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]);
        }
        for index in 16..64 {
            let s0 = words[index - 15].rotate_right(7)
                ^ words[index - 15].rotate_right(18)
                ^ (words[index - 15] >> 3);
            let s1 = words[index - 2].rotate_right(17)
                ^ words[index - 2].rotate_right(19)
                ^ (words[index - 2] >> 10);
            words[index] = words[index - 16]
                .wrapping_add(s0)
                .wrapping_add(words[index - 7])
                .wrapping_add(s1);
        }
        let [mut a, mut b, mut c, mut d, mut e, mut f, mut g, mut h] = self.state;
        for index in 0..64 {
            let sum1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
            let choose = (e & f) ^ ((!e) & g);
            let temporary1 = h
                .wrapping_add(sum1)
                .wrapping_add(choose)
                .wrapping_add(K[index])
                .wrapping_add(words[index]);
            let sum0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
            let majority = (a & b) ^ (a & c) ^ (b & c);
            let temporary2 = sum0.wrapping_add(majority);
            h = g;
            g = f;
            f = e;
            e = d.wrapping_add(temporary1);
            d = c;
            c = b;
            b = a;
            a = temporary1.wrapping_add(temporary2);
        }
        for (state, value) in self.state.iter_mut().zip([a, b, c, d, e, f, g, h]) {
            *state = state.wrapping_add(value);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;
    use std::sync::atomic::{AtomicU64, Ordering};

    static TEST_ID: AtomicU64 = AtomicU64::new(1);

    #[test]
    fn launcher_contract_exposes_only_the_implemented_printing_broker() {
        use archphene_packages::elf_profile::{
            BRIDGE_AUDIO_OUTPUT, BRIDGE_CAMERA, BRIDGE_PRINTING, BRIDGE_SECRETS,
        };

        assert_eq!(launcher_capabilities(0), LAUNCHER_CAPABILITIES_V4);
        assert_eq!(
            launcher_capabilities(BRIDGE_PRINTING),
            LAUNCHER_CAPABILITIES_PRINTING_V5,
        );
        assert_eq!(
            launcher_capabilities(BRIDGE_AUDIO_OUTPUT | BRIDGE_CAMERA | BRIDGE_SECRETS,),
            LAUNCHER_CAPABILITIES_V4,
        );
    }

    struct TestRoot {
        path: PathBuf,
    }

    impl TestRoot {
        fn new() -> Self {
            let id = TEST_ID.fetch_add(1, Ordering::Relaxed);
            let path = std::env::temp_dir().join(format!(
                "archphene-launcher-test-{}-{id}",
                std::process::id(),
            ));
            fs::create_dir_all(path.join(REGISTRY_DIRECTORY)).expect("registry directory");
            Self { path }
        }
    }

    impl Drop for TestRoot {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.path);
        }
    }

    fn entry(desktop_id: &str, name: &str) -> DesktopEntry {
        DesktopEntry {
            desktop_id: desktop_id.to_owned(),
            source_package: Some("kate".to_owned()),
            executable_package: Some("kate".to_owned()),
            integration_topology: 0,
            bridge_capabilities: 0,
            integration_profiled: false,
            integration_complete: false,
            name: name.to_owned(),
            executable: "/usr/bin/kate".to_owned(),
            arguments: vec![
                ExecArgument::Literal("--startanon".to_owned()),
                ExecArgument::MultipleUrls,
            ],
            try_exec: Some("/usr/bin/kate".to_owned()),
            icon: Some("kate".to_owned()),
            mime_types: vec!["text/plain".to_owned()],
            terminal: false,
        }
    }

    fn catalog(entries: Vec<DesktopEntry>) -> DesktopCatalog {
        let examined = entries.len();
        DesktopCatalog {
            entries,
            examined,
            rejected: 0,
            truncated: false,
        }
    }

    fn test_png(marker: u8) -> Vec<u8> {
        let mut bytes = Vec::from(&b"\x89PNG\r\n\x1a\n\0\0\0\rIHDR\0\0\0\x08\0\0\0\x08"[..]);
        bytes.extend_from_slice(&[marker; 32]);
        bytes
    }

    #[test]
    fn sha256_matches_published_vectors() {
        assert_eq!(
            sha256(b""),
            [
                0xe3, 0xb0, 0xc4, 0x42, 0x98, 0xfc, 0x1c, 0x14, 0x9a, 0xfb, 0xf4, 0xc8, 0x99, 0x6f,
                0xb9, 0x24, 0x27, 0xae, 0x41, 0xe4, 0x64, 0x9b, 0x93, 0x4c, 0xa4, 0x95, 0x99, 0x1b,
                0x78, 0x52, 0xb8, 0x55,
            ]
        );
        assert_eq!(
            sha256(b"abc"),
            [
                0xba, 0x78, 0x16, 0xbf, 0x8f, 0x01, 0xcf, 0xea, 0x41, 0x41, 0x40, 0xde, 0x5d, 0xae,
                0x22, 0x23, 0xb0, 0x03, 0x61, 0xa3, 0x96, 0x17, 0x7a, 0x9c, 0xb4, 0x10, 0xff, 0x61,
                0xf2, 0x00, 0x15, 0xad,
            ]
        );
    }

    #[test]
    fn streaming_sha256_matches_one_shot_across_icon_sized_chunks() {
        let bytes: Vec<u8> = (0..100_000).map(|index| (index % 251) as u8).collect();
        let mut streaming = Sha256::new();
        for chunk in bytes.chunks(16 * 1024) {
            streaming.update_streaming(chunk);
        }
        assert_eq!(streaming.finalize(), sha256(&bytes));
    }

    #[test]
    fn reconcile_is_stable_and_round_trips_atomically() {
        let root = TestRoot::new();
        let source = catalog(vec![entry("org.kde.kate.desktop", "Kate")]);
        let (registry, report) =
            LauncherRegistry::reconcile(&root.path, &source).expect("initial reconcile");
        assert_eq!(report.added, 1);
        assert_eq!(report.generation, 1);
        assert_eq!(registry.descriptors.len(), 1);
        let descriptor = &registry.descriptors[0];
        assert_eq!(descriptor.status, WrapperStatus::NeedsPublish);
        assert_eq!(descriptor.desired_generation, 1);
        assert_eq!(descriptor.published_generation, 0);
        assert!(descriptor.android_package.starts_with(PACKAGE_PREFIX));
        assert_eq!(descriptor.android_package.len(), PACKAGE_PREFIX.len() + 32);
        let descriptor_hex = descriptor.descriptor_id_hex();
        assert!(descriptor_hex.iter().all(u8::is_ascii_hexdigit));
        assert_eq!(
            &descriptor.android_package.as_bytes()[PACKAGE_PREFIX.len()..],
            &descriptor_hex[..32],
        );
        assert_eq!(
            fs::metadata(root.path.join(REGISTRY_DIRECTORY).join(REGISTRY_FILE))
                .expect("registry metadata")
                .permissions()
                .mode()
                & 0o777,
            0o600,
        );

        let loaded = LauncherRegistry::load(&root.path).expect("load registry");
        assert_eq!(loaded, registry);
        let bytes =
            fs::read(root.path.join(REGISTRY_DIRECTORY).join(REGISTRY_FILE)).expect("registry");
        let (stable, report) =
            LauncherRegistry::reconcile(&root.path, &source).expect("stable reconcile");
        assert_eq!(report.unchanged, 1);
        assert_eq!(report.generation, 1);
        assert_eq!(stable, registry);
        assert_eq!(
            fs::read(root.path.join(REGISTRY_DIRECTORY).join(REGISTRY_FILE)).expect("registry"),
            bytes,
        );
    }

    #[test]
    fn terminal_desktop_entries_do_not_publish_graphical_wrappers() {
        let root = TestRoot::new();
        let mut terminal = entry("btop.desktop", "btop++");
        terminal.source_package = Some("btop".to_owned());
        terminal.executable_package = Some("btop".to_owned());
        terminal.executable = "/usr/bin/btop".to_owned();
        terminal.try_exec = None;
        terminal.arguments.clear();
        terminal.terminal = true;

        let (registry, report) = LauncherRegistry::reconcile(&root.path, &catalog(vec![terminal]))
            .expect("terminal reconcile");

        assert!(registry.descriptors().is_empty());
        assert_eq!(report.added, 0);
        assert_eq!(report.changed, 0);
        assert_eq!(report.removed, 0);
    }

    #[test]
    fn package_icon_bytes_are_fingerprinted_and_trigger_wrapper_updates() {
        let root = TestRoot::new();
        let icon = root
            .path
            .join("usr/share/icons/hicolor/256x256/apps/kate.png");
        fs::create_dir_all(icon.parent().expect("icon parent")).expect("icon directory");
        let first_icon = test_png(1);
        fs::write(&icon, &first_icon).expect("first icon");
        let source = catalog(vec![entry("org.kde.kate.desktop", "Kate")]);

        let (registry, initial) =
            LauncherRegistry::reconcile(&root.path, &source).expect("initial icon reconcile");
        assert_eq!(initial.added, 1);
        assert_eq!(
            registry.descriptors[0].icon_digest(),
            Some(sha256(&first_icon)),
        );
        let package = registry.descriptors[0].android_package.clone();

        let replacement_icon = test_png(2);
        fs::write(&icon, &replacement_icon).expect("replacement icon");
        let replacement_digest = launcher_icon_digest(&root.path, Some("kate"));
        assert_eq!(replacement_digest, Some(sha256(&replacement_icon)),);
        assert_ne!(
            registry.descriptors[0].content_digest,
            descriptor_content_digest(&source.entries[0], replacement_digest.as_ref()),
        );
        let (updated, report) =
            LauncherRegistry::reconcile(&root.path, &source).expect("updated icon reconcile");
        assert_eq!(report.changed, 1);
        assert_eq!(updated.descriptors[0].android_package, package);
        assert_eq!(updated.descriptors[0].desired_generation, 2);
        assert_eq!(
            updated.descriptors[0].icon_digest(),
            Some(sha256(&replacement_icon)),
        );
        assert_eq!(
            LauncherRegistry::load(&root.path)
                .expect("updated registry")
                .descriptors[0]
                .icon_digest(),
            Some(sha256(&replacement_icon)),
        );
    }

    #[test]
    fn legacy_registry_migrates_without_changing_launcher_identity() {
        let root = TestRoot::new();
        let icon = root
            .path
            .join("usr/share/icons/hicolor/256x256/apps/kate.png");
        fs::create_dir_all(icon.parent().expect("icon parent")).expect("icon directory");
        let legacy_icon = test_png(3);
        fs::write(&icon, &legacy_icon).expect("legacy icon");
        let source = catalog(vec![entry("org.kde.kate.desktop", "Kate")]);
        let (registry, _) =
            LauncherRegistry::reconcile(&root.path, &source).expect("initial registry");
        let package = registry.descriptors[0].android_package.clone();
        let legacy =
            encode_registry_version(&registry, LEGACY_REGISTRY_VERSION).expect("legacy registry");
        fs::write(
            root.path.join(REGISTRY_DIRECTORY).join(REGISTRY_FILE),
            legacy,
        )
        .expect("replace with legacy registry");

        let loaded = LauncherRegistry::load(&root.path).expect("load legacy registry");
        assert_eq!(loaded.descriptors[0].android_package, package);
        assert_eq!(loaded.descriptors[0].icon_digest(), None);
        let (migrated, report) =
            LauncherRegistry::reconcile(&root.path, &source).expect("migrate registry");
        assert_eq!(report.changed, 1);
        assert_eq!(migrated.descriptors[0].android_package, package);
        assert_eq!(migrated.descriptors[0].desired_generation, 2);
        assert_eq!(
            migrated.descriptors[0].icon_digest(),
            Some(sha256(&legacy_icon)),
        );
        let stored = fs::read(root.path.join(REGISTRY_DIRECTORY).join(REGISTRY_FILE))
            .expect("current registry");
        assert_eq!(
            u32::from_le_bytes(stored[8..12].try_into().expect("registry version")),
            REGISTRY_VERSION,
        );
    }

    #[test]
    fn previous_registry_version_rebinds_executable_ownership() {
        let root = TestRoot::new();
        let source = catalog(vec![entry("org.kde.kate.desktop", "Kate")]);
        let (registry, _) =
            LauncherRegistry::reconcile(&root.path, &source).expect("current registry");
        let previous = encode_registry_version(&registry, PREVIOUS_REGISTRY_VERSION)
            .expect("previous registry");
        fs::write(
            root.path.join(REGISTRY_DIRECTORY).join(REGISTRY_FILE),
            previous,
        )
        .expect("replace with previous registry");
        let loaded = LauncherRegistry::load(&root.path).expect("load previous registry");
        assert_eq!(
            loaded.descriptors[0].android_package,
            registry.descriptors[0].android_package,
        );
        assert!(loaded.descriptors[0].executable_package.is_none());
        let (reconciled, report) =
            LauncherRegistry::reconcile(&root.path, &source).expect("rebind executable owner");
        assert_eq!(report.changed, 1);
        assert_eq!(reconciled.descriptors[0].desired_generation, 2);
        assert_eq!(
            reconciled.descriptors[0].executable_package.as_deref(),
            Some("kate"),
        );
    }

    #[test]
    fn executable_owner_registry_migrates_without_inventing_observations() {
        let root = TestRoot::new();
        let source = catalog(vec![entry("org.kde.kate.desktop", "Kate")]);
        let (registry, _) =
            LauncherRegistry::reconcile(&root.path, &source).expect("current registry");
        let previous = encode_registry_version(&registry, EXECUTABLE_PACKAGE_REGISTRY_VERSION)
            .expect("executable-owner registry");
        fs::write(
            root.path.join(REGISTRY_DIRECTORY).join(REGISTRY_FILE),
            previous,
        )
        .expect("replace with executable-owner registry");
        let loaded = LauncherRegistry::load(&root.path).expect("load previous registry");
        assert_eq!(
            loaded.descriptors[0].executable_package.as_deref(),
            Some("kate")
        );
        assert!(!loaded.descriptors[0].integration_observed);
        assert_eq!(loaded.descriptors[0].observed_topology, 0);
        assert!(!loaded.descriptors[0].integration_observation_complete);
    }

    #[test]
    fn observation_registry_rebinds_verified_bridge_capabilities() {
        let root = TestRoot::new();
        let mut application = entry("org.kde.kate.desktop", "Kate");
        application.bridge_capabilities = archphene_packages::elf_profile::BRIDGE_AUDIO_OUTPUT;
        let source = catalog(vec![application]);
        let (registry, _) =
            LauncherRegistry::reconcile(&root.path, &source).expect("current registry");
        assert_eq!(
            registry.descriptors[0].bridge_capabilities,
            archphene_packages::elf_profile::BRIDGE_AUDIO_OUTPUT,
        );
        let previous = encode_registry_version(&registry, OBSERVATION_REGISTRY_VERSION)
            .expect("observation registry");
        fs::write(
            root.path.join(REGISTRY_DIRECTORY).join(REGISTRY_FILE),
            previous,
        )
        .expect("replace with observation registry");

        let loaded = LauncherRegistry::load(&root.path).expect("load observation registry");
        assert_eq!(loaded.descriptors[0].bridge_capabilities, 0);
        let (reconciled, report) =
            LauncherRegistry::reconcile(&root.path, &source).expect("bind bridge capabilities");
        assert_eq!(report.changed, 1);
        assert_eq!(reconciled.descriptors[0].desired_generation, 2);
        assert_eq!(
            reconciled.descriptors[0].bridge_capabilities,
            archphene_packages::elf_profile::BRIDGE_AUDIO_OUTPUT,
        );
    }

    #[test]
    fn observation_registry_schema_upgrade_does_not_republish_unchanged_launcher() {
        let root = TestRoot::new();
        let source = catalog(vec![entry("org.kde.kate.desktop", "Kate")]);
        let (registry, _) =
            LauncherRegistry::reconcile(&root.path, &source).expect("current registry");
        let previous = encode_registry_version(&registry, OBSERVATION_REGISTRY_VERSION)
            .expect("observation registry");
        fs::write(
            root.path.join(REGISTRY_DIRECTORY).join(REGISTRY_FILE),
            previous,
        )
        .expect("replace with observation registry");

        let (reconciled, report) =
            LauncherRegistry::reconcile(&root.path, &source).expect("upgrade registry");
        assert_eq!(report.changed, 0);
        assert_eq!(report.unchanged, 1);
        assert_eq!(reconciled.generation(), 1);
        assert_eq!(reconciled.descriptors[0].desired_generation, 1);
        let stored = fs::read(root.path.join(REGISTRY_DIRECTORY).join(REGISTRY_FILE))
            .expect("upgraded registry");
        assert_eq!(
            u32::from_le_bytes(stored[8..12].try_into().expect("registry version")),
            REGISTRY_VERSION,
        );
    }

    #[test]
    fn observed_topology_is_exactly_bound_persisted_and_reset_on_change() {
        let root = TestRoot::new();
        let source = catalog(vec![entry("org.kde.kate.desktop", "Kate")]);
        let (mut registry, _) =
            LauncherRegistry::reconcile(&root.path, &source).expect("initial registry");
        let package = registry.descriptors[0].android_package.clone();
        registry
            .mark_building(&root.path, &package, 1)
            .expect("building");
        registry
            .mark_awaiting_install(&root.path, &package, 1)
            .expect("awaiting install");
        registry
            .confirm_installed(&root.path, &package, 1)
            .expect("installed");
        let descriptor_id = registry.descriptors[0].descriptor_id;
        assert!(
            registry
                .record_integration_observation(&root.path, &descriptor_id, 1, 0x100, true)
                .expect("first observation")
        );
        let generation = registry.generation();
        assert!(
            !registry
                .record_integration_observation(&root.path, &descriptor_id, 1, 0x100, true)
                .expect("unchanged observation")
        );
        assert_eq!(registry.generation(), generation);
        assert!(
            registry
                .record_integration_observation(&root.path, &descriptor_id, 1, 0x400, false)
                .expect("expanded observation")
        );
        let loaded = LauncherRegistry::load(&root.path).expect("persisted observation");
        assert_eq!(loaded.descriptors[0].observed_topology, 0x500);
        assert!(loaded.descriptors[0].integration_observed);
        assert!(!loaded.descriptors[0].integration_observation_complete);
        assert!(matches!(
            registry.record_integration_observation(&root.path, &descriptor_id, 2, 0x2, true),
            Err(LauncherRegistryError::InvalidTransition)
        ));

        let changed = catalog(vec![entry("org.kde.kate.desktop", "Kate Editor")]);
        let (changed, report) =
            LauncherRegistry::reconcile(&root.path, &changed).expect("changed registry");
        assert_eq!(report.changed, 1);
        assert!(!changed.descriptors[0].integration_observed);
        assert_eq!(changed.descriptors[0].observed_topology, 0);
    }

    #[test]
    fn wrapper_lifecycle_retains_stable_identity_across_updates_and_removal() {
        let root = TestRoot::new();
        let source = catalog(vec![entry("org.kde.kate.desktop", "Kate")]);
        let (mut registry, _) =
            LauncherRegistry::reconcile(&root.path, &source).expect("initial reconcile");
        let package = registry.descriptors[0].android_package.clone();
        registry
            .mark_building(&root.path, &package, 1)
            .expect("building");
        registry
            .mark_awaiting_install(&root.path, &package, 1)
            .expect("awaiting install");
        registry
            .confirm_installed(&root.path, &package, 1)
            .expect("installed");
        assert_eq!(registry.descriptors[0].status, WrapperStatus::Current);
        assert_eq!(registry.descriptors[0].published_generation, 1);
        let descriptor_id = registry.descriptors[0].descriptor_id_hex();
        let descriptor_id = std::str::from_utf8(&descriptor_id).expect("descriptor hex");
        assert_eq!(
            registry
                .authorize_published(&package, descriptor_id, 1)
                .map(|descriptor| descriptor.name.as_str()),
            Some("Kate"),
        );
        assert!(
            registry
                .authorize_published(&package, descriptor_id, 2)
                .is_none()
        );
        assert!(
            registry
                .authorize_published(&package, &"0".repeat(64), 1)
                .is_none()
        );
        let changed = catalog(vec![entry("org.kde.kate.desktop", "Kate Editor")]);
        let (mut registry, report) =
            LauncherRegistry::reconcile(&root.path, &changed).expect("changed reconcile");
        assert_eq!(report.changed, 1);
        assert_eq!(registry.descriptors[0].android_package, package);
        assert_eq!(registry.descriptors[0].desired_generation, 2);
        assert_eq!(registry.descriptors[0].published_generation, 1);
        assert_eq!(registry.descriptors[0].status, WrapperStatus::NeedsPublish);
        assert!(
            registry
                .authorize_published(&package, descriptor_id, 1)
                .is_none()
        );
        registry
            .mark_building(&root.path, &package, 2)
            .expect("updated build");
        registry
            .mark_awaiting_install(&root.path, &package, 2)
            .expect("updated confirmation");
        registry
            .confirm_installed(&root.path, &package, 2)
            .expect("updated install");

        let (mut registry, report) = LauncherRegistry::reconcile(&root.path, &catalog(Vec::new()))
            .expect("removed reconcile");
        assert_eq!(report.removed, 1);
        assert!(!registry.descriptors[0].desired_present);
        assert_eq!(registry.descriptors[0].status, WrapperStatus::NeedsRemoval);
        registry
            .mark_awaiting_removal(&root.path, &package)
            .expect("awaiting removal");
        registry
            .confirm_removed(&root.path, &package)
            .expect("removed");
        assert!(registry.descriptors.is_empty());
        assert!(
            LauncherRegistry::load(&root.path)
                .expect("load empty registry")
                .descriptors
                .is_empty()
        );
    }

    #[test]
    fn stale_wrapper_template_is_republished_at_a_higher_android_version() {
        let root = TestRoot::new();
        let source = catalog(vec![entry("org.kde.kate.desktop", "Kate")]);
        let (mut registry, _) =
            LauncherRegistry::reconcile(&root.path, &source).expect("initial reconcile");
        let package = registry.descriptors[0].android_package.clone();
        registry
            .mark_building(&root.path, &package, 1)
            .expect("building");
        registry
            .mark_awaiting_install(&root.path, &package, 1)
            .expect("awaiting install");
        registry
            .confirm_installed(&root.path, &package, 1)
            .expect("installed");
        let descriptor_id = registry.descriptors[0].descriptor_id_hex();
        let descriptor_id = std::str::from_utf8(&descriptor_id).expect("descriptor hex");

        registry
            .mark_template_stale(&root.path, &package, 1)
            .expect("stale launcher template");
        let replacement_generation = registry.descriptors[0].desired_generation;
        assert!(replacement_generation > 1);
        assert_eq!(registry.descriptors[0].published_generation, 1);
        assert_eq!(registry.descriptors[0].status, WrapperStatus::NeedsPublish);
        assert!(
            registry
                .authorize_published(&package, descriptor_id, 1)
                .is_none()
        );
        registry
            .mark_building(&root.path, &package, replacement_generation)
            .expect("replacement template build");
        registry
            .mark_awaiting_install(&root.path, &package, replacement_generation)
            .expect("replacement template install");
        registry
            .confirm_installed(&root.path, &package, replacement_generation)
            .expect("replacement template current");
        assert!(
            registry
                .authorize_published(&package, descriptor_id, replacement_generation)
                .is_some()
        );
    }

    #[test]
    fn trusted_wrapper_from_a_reset_registry_is_republished_without_a_downgrade() {
        let root = TestRoot::new();
        let source = catalog(vec![entry("org.kde.kate.desktop", "Kate")]);
        let (mut registry, _) =
            LauncherRegistry::reconcile(&root.path, &source).expect("reset registry");
        let package = registry.descriptors[0].android_package.clone();
        let descriptor_id = registry.descriptors[0].descriptor_id_hex();
        let descriptor_id = std::str::from_utf8(&descriptor_id).expect("descriptor hex");

        registry
            .mark_template_stale(&root.path, &package, 18)
            .expect("adopt trusted stale wrapper");
        let replacement_generation = registry.descriptors[0].desired_generation;
        assert!(replacement_generation > 18);
        assert_eq!(registry.descriptors[0].published_generation, 18);
        assert_eq!(registry.descriptors[0].status, WrapperStatus::NeedsPublish);
        assert!(
            registry
                .authorize_published(&package, descriptor_id, 18)
                .is_none()
        );

        registry
            .mark_building(&root.path, &package, replacement_generation)
            .expect("replacement build");
        registry
            .mark_awaiting_install(&root.path, &package, replacement_generation)
            .expect("replacement install");
        registry
            .confirm_installed(&root.path, &package, replacement_generation)
            .expect("replacement current");
        assert!(
            registry
                .authorize_published(&package, descriptor_id, replacement_generation)
                .is_some()
        );
    }

    #[test]
    fn catalog_changes_cannot_orphan_an_awaiting_android_install() {
        let root = TestRoot::new();
        let initial = catalog(vec![entry("org.kde.kate.desktop", "Kate")]);
        let (mut registry, _) =
            LauncherRegistry::reconcile(&root.path, &initial).expect("initial reconcile");
        let package = registry.descriptors[0].android_package.clone();
        registry
            .mark_building(&root.path, &package, 1)
            .expect("building");
        registry
            .mark_awaiting_install(&root.path, &package, 1)
            .expect("awaiting install");

        let changed = catalog(vec![entry("org.kde.kate.desktop", "Kate Editor")]);
        let (mut registry, _) =
            LauncherRegistry::reconcile(&root.path, &changed).expect("changed reconcile");
        assert_eq!(registry.descriptors[0].desired_generation, 2);
        assert_eq!(registry.descriptors[0].pending_generation, 1);
        assert_eq!(
            registry.descriptors[0].status,
            WrapperStatus::AwaitingInstall
        );
        registry
            .confirm_installed(&root.path, &package, 1)
            .expect("older install confirmation");
        assert_eq!(registry.descriptors[0].published_generation, 1);
        assert_eq!(registry.descriptors[0].pending_generation, 0);
        assert_eq!(registry.descriptors[0].status, WrapperStatus::NeedsPublish);

        registry
            .mark_building(&root.path, &package, 2)
            .expect("replacement building");
        registry
            .mark_awaiting_install(&root.path, &package, 2)
            .expect("replacement awaiting");
        let (mut registry, _) = LauncherRegistry::reconcile(&root.path, &catalog(Vec::new()))
            .expect("removed while awaiting");
        assert!(!registry.descriptors[0].desired_present);
        assert_eq!(
            registry.descriptors[0].status,
            WrapperStatus::AwaitingInstall
        );
        registry
            .confirm_installed(&root.path, &package, 2)
            .expect("install confirmed after removal");
        assert_eq!(registry.descriptors[0].status, WrapperStatus::NeedsRemoval);
        assert_eq!(registry.descriptors[0].published_generation, 2);
    }

    #[test]
    fn android_package_reconciliation_recovers_interrupted_operations() {
        let root = TestRoot::new();
        let source = catalog(vec![entry("org.kde.kate.desktop", "Kate")]);
        let (mut registry, _) =
            LauncherRegistry::reconcile(&root.path, &source).expect("initial reconcile");
        let package = registry.descriptors[0].android_package.clone();
        registry
            .mark_building(&root.path, &package, 1)
            .expect("interrupted build");

        let mut restarted = LauncherRegistry::load(&root.path).expect("restart load");
        restarted
            .reconcile_android_package(&root.path, &package, None)
            .expect("no installed wrapper");
        assert_eq!(restarted.descriptors[0].status, WrapperStatus::NeedsPublish);
        assert_eq!(restarted.descriptors[0].pending_generation, 0);

        restarted
            .mark_building(&root.path, &package, 1)
            .expect("replacement build");
        restarted
            .mark_awaiting_install(&root.path, &package, 1)
            .expect("interrupted install");
        let mut restarted = LauncherRegistry::load(&root.path).expect("second restart load");
        restarted
            .reconcile_android_package(&root.path, &package, Some(1))
            .expect("installed while manager stopped");
        assert_eq!(restarted.descriptors[0].status, WrapperStatus::Current);
        assert_eq!(restarted.descriptors[0].published_generation, 1);
        assert_eq!(restarted.descriptors[0].pending_generation, 0);

        let changed = catalog(vec![entry("org.kde.kate.desktop", "Kate Editor")]);
        let (mut restarted, _) =
            LauncherRegistry::reconcile(&root.path, &changed).expect("changed desktop entry");
        restarted
            .mark_building(&root.path, &package, 2)
            .expect("update build");
        restarted
            .mark_awaiting_install(&root.path, &package, 2)
            .expect("update install");
        restarted
            .reconcile_android_package(&root.path, &package, Some(1))
            .expect("old wrapper remains");
        assert_eq!(restarted.descriptors[0].status, WrapperStatus::NeedsPublish);
        assert_eq!(restarted.descriptors[0].published_generation, 1);

        let (mut restarted, _) =
            LauncherRegistry::reconcile(&root.path, &catalog(Vec::new())).expect("entry removed");
        restarted
            .mark_awaiting_removal(&root.path, &package)
            .expect("removal submitted");
        restarted
            .reconcile_android_package(&root.path, &package, None)
            .expect("removed while manager stopped");
        assert!(restarted.descriptors.is_empty());
    }

    #[test]
    fn failed_operations_require_an_explicit_retry() {
        let root = TestRoot::new();
        let source = catalog(vec![entry("org.kde.kate.desktop", "Kate")]);
        let (mut registry, _) =
            LauncherRegistry::reconcile(&root.path, &source).expect("initial reconcile");
        let package = registry.descriptors[0].android_package.clone();
        registry
            .mark_building(&root.path, &package, 1)
            .expect("building");
        registry
            .mark_failed(&root.path, &package, 1)
            .expect("build failure");
        assert_eq!(registry.descriptors[0].status, WrapperStatus::Failed);
        assert!(registry.mark_building(&root.path, &package, 1).is_err());
        registry
            .retry_terminal(&root.path, &package, 1)
            .expect("retry build");
        assert_eq!(registry.descriptors[0].status, WrapperStatus::NeedsPublish);

        registry
            .mark_building(&root.path, &package, 1)
            .expect("replacement building");
        registry
            .mark_awaiting_install(&root.path, &package, 1)
            .expect("replacement awaiting install");
        registry
            .confirm_installed(&root.path, &package, 1)
            .expect("installed");
        let (mut registry, _) =
            LauncherRegistry::reconcile(&root.path, &catalog(Vec::new())).expect("entry removed");
        assert!(registry.mark_failed(&root.path, &package, 1).is_err());
        registry
            .mark_awaiting_removal(&root.path, &package)
            .expect("removing");
        registry
            .mark_failed(&root.path, &package, 1)
            .expect("removal failure");
        assert_eq!(registry.descriptors[0].status, WrapperStatus::Failed);
        registry
            .retry_terminal(&root.path, &package, 1)
            .expect("retry removal");
        assert_eq!(registry.descriptors[0].status, WrapperStatus::NeedsRemoval);
    }

    #[test]
    fn cancelled_install_survives_restart_until_retry_or_dismiss() {
        let root = TestRoot::new();
        let source = catalog(vec![entry("org.kde.kate.desktop", "Kate")]);
        let (mut registry, _) =
            LauncherRegistry::reconcile(&root.path, &source).expect("initial reconcile");
        let package = registry.descriptors[0].android_package.clone();
        registry
            .mark_building(&root.path, &package, 1)
            .expect("building");
        registry
            .mark_awaiting_install(&root.path, &package, 1)
            .expect("awaiting install");
        registry
            .mark_cancelled(&root.path, &package, 1)
            .expect("cancelled");

        let mut restarted = LauncherRegistry::load(&root.path).expect("restart load");
        restarted
            .reconcile_android_package(&root.path, &package, None)
            .expect("wrapper remains absent");
        assert_eq!(restarted.descriptors[0].status, WrapperStatus::Cancelled);
        assert!(restarted.mark_building(&root.path, &package, 1).is_err());

        restarted
            .dismiss_cancelled(&root.path, &package, 1)
            .expect("dismiss");
        let (mut restarted, _) =
            LauncherRegistry::reconcile(&root.path, &source).expect("catalog refresh");
        assert_eq!(restarted.descriptors[0].status, WrapperStatus::Dismissed);
        restarted
            .retry_terminal(&root.path, &package, 1)
            .expect("explicit retry");
        assert_eq!(restarted.descriptors[0].status, WrapperStatus::NeedsPublish);
    }

    #[test]
    fn multiple_new_launchers_require_one_atomic_review_batch() {
        let root = TestRoot::new();
        let source = catalog(vec![
            entry("org.kde.kate.desktop", "Kate"),
            entry("org.kde.kwrite.desktop", "KWrite"),
        ]);
        let (mut registry, report) =
            LauncherRegistry::reconcile(&root.path, &source).expect("initial reconcile");
        assert_eq!(report.added, 2);
        assert!(
            registry
                .descriptors
                .iter()
                .all(|descriptor| descriptor.status == WrapperStatus::NeedsReview),
        );
        let first_package = registry.descriptors[0].android_package.clone();
        assert!(
            registry
                .mark_building(&root.path, &first_package, 1)
                .is_err(),
        );

        let incomplete = vec![LauncherReviewDecision {
            android_package: registry.descriptors[0].android_package.clone(),
            desired_generation: 1,
            publish: true,
        }];
        assert!(registry.review_batch(&root.path, &incomplete).is_err());

        let decisions = vec![
            LauncherReviewDecision {
                android_package: registry.descriptors[0].android_package.clone(),
                desired_generation: 1,
                publish: true,
            },
            LauncherReviewDecision {
                android_package: registry.descriptors[1].android_package.clone(),
                desired_generation: 1,
                publish: false,
            },
        ];
        registry
            .review_batch(&root.path, &decisions)
            .expect("review batch");
        assert_eq!(registry.descriptors[0].status, WrapperStatus::NeedsPublish);
        assert_eq!(registry.descriptors[1].status, WrapperStatus::Dismissed);

        let mut restarted = LauncherRegistry::load(&root.path).expect("restart");
        restarted
            .review_batch(
                &root.path,
                &[LauncherReviewDecision {
                    android_package: restarted.descriptors[1].android_package.clone(),
                    desired_generation: 1,
                    publish: true,
                }],
            )
            .expect("revisit dismissed launcher");
        assert_eq!(restarted.descriptors[1].status, WrapperStatus::NeedsPublish);
    }

    #[test]
    fn untrusted_android_package_is_quarantined_without_being_adopted() {
        let root = TestRoot::new();
        let source = catalog(vec![entry("org.kde.kate.desktop", "Kate")]);
        let (mut registry, _) =
            LauncherRegistry::reconcile(&root.path, &source).expect("initial reconcile");
        let package = registry.descriptors[0].android_package.clone();
        registry
            .quarantine_android_package(&root.path, &package)
            .expect("quarantine");
        assert_eq!(registry.descriptors[0].status, WrapperStatus::Failed);
        assert_eq!(registry.descriptors[0].published_generation, 0);
        assert_eq!(registry.descriptors[0].pending_generation, 0);
        assert!(
            registry
                .reconcile_android_package(&root.path, &package, Some(1))
                .is_ok()
        );
        assert_eq!(registry.descriptors[0].status, WrapperStatus::Current);
        registry
            .quarantine_android_package(&root.path, &package)
            .expect("quarantine installed blocker");
        let (mut registry, _) =
            LauncherRegistry::reconcile(&root.path, &source).expect("catalog refresh");
        assert_eq!(registry.descriptors[0].status, WrapperStatus::Failed);
        registry
            .review_batch(
                &root.path,
                &[LauncherReviewDecision {
                    android_package: package,
                    desired_generation: 1,
                    publish: true,
                }],
            )
            .expect("replace quarantined blocker");
        assert_eq!(registry.descriptors[0].status, WrapperStatus::NeedsRemoval);
    }

    #[test]
    fn failed_launcher_can_be_explicitly_retried_from_review() {
        let root = TestRoot::new();
        let source = catalog(vec![entry("org.kde.kate.desktop", "Kate")]);
        let (mut registry, _) =
            LauncherRegistry::reconcile(&root.path, &source).expect("initial reconcile");
        let package = registry.descriptors[0].android_package.clone();
        registry
            .quarantine_android_package(&root.path, &package)
            .expect("quarantine");

        registry
            .review_batch(
                &root.path,
                &[LauncherReviewDecision {
                    android_package: package,
                    desired_generation: 1,
                    publish: true,
                }],
            )
            .expect("explicit retry");
        assert_eq!(registry.descriptors[0].status, WrapperStatus::NeedsPublish);
    }

    #[test]
    fn untrusted_retry_removes_the_blocker_before_republishing() {
        let root = TestRoot::new();
        let source = catalog(vec![entry("org.kde.kate.desktop", "Kate")]);
        let (mut registry, _) =
            LauncherRegistry::reconcile(&root.path, &source).expect("initial reconcile");
        let package = registry.descriptors[0].android_package.clone();
        registry
            .quarantine_android_package(&root.path, &package)
            .expect("quarantine");
        registry
            .review_batch(
                &root.path,
                &[LauncherReviewDecision {
                    android_package: package.clone(),
                    desired_generation: 1,
                    publish: true,
                }],
            )
            .expect("retry");

        registry
            .mark_untrusted_replacement_removal(&root.path, &package, 1)
            .expect("stage blocker removal");
        assert_eq!(registry.descriptors[0].status, WrapperStatus::NeedsRemoval);
        let updated = catalog(vec![entry("org.kde.kate.desktop", "Updated Kate")]);
        let (mut registry, _) =
            LauncherRegistry::reconcile(&root.path, &updated).expect("changed catalog refresh");
        assert_eq!(registry.descriptors[0].status, WrapperStatus::NeedsRemoval);
        assert_eq!(registry.descriptors[0].desired_generation, 2);
        registry
            .mark_awaiting_removal(&root.path, &package)
            .expect("await removal");
        registry
            .confirm_removed(&root.path, &package)
            .expect("remove blocker");
        assert_eq!(registry.descriptors[0].status, WrapperStatus::NeedsPublish);
        assert_eq!(registry.descriptors[0].published_generation, 0);
        assert_eq!(registry.descriptors[0].desired_generation, 2);
    }

    #[test]
    fn unsafe_android_identity_inputs_are_rejected() {
        let root = TestRoot::new();
        assert!(matches!(
            LauncherRegistry::reconcile(
                &root.path,
                &catalog(vec![entry("../kate.desktop", "Kate")])
            ),
            Err(LauncherRegistryError::Corrupt)
        ));
        assert!(matches!(
            LauncherRegistry::reconcile(
                &root.path,
                &catalog(vec![entry("org.kde.kate.desktop", "Kate\u{202e} spoof")])
            ),
            Err(LauncherRegistryError::Corrupt)
        ));
    }

    #[test]
    fn corrupt_and_unsafe_registry_files_fail_closed() {
        let root = TestRoot::new();
        LauncherRegistry::reconcile(
            &root.path,
            &catalog(vec![entry("org.kde.kate.desktop", "Kate")]),
        )
        .expect("initial reconcile");
        let path = root.path.join(REGISTRY_DIRECTORY).join(REGISTRY_FILE);
        let mut bytes = fs::read(&path).expect("registry");
        let last = bytes.len() - 1;
        bytes[last] ^= 0x40;
        fs::write(&path, bytes).expect("tampered registry");
        assert!(matches!(
            LauncherRegistry::load(&root.path),
            Err(LauncherRegistryError::Corrupt)
        ));

        fs::remove_file(&path).expect("remove corrupt registry");
        std::os::unix::fs::symlink("/etc/passwd", &path).expect("unsafe registry");
        assert!(matches!(
            LauncherRegistry::load(&root.path),
            Err(LauncherRegistryError::UnsafePath(_))
        ));
    }

    #[test]
    fn stale_regular_temp_is_recovered_but_unsafe_temp_is_rejected() {
        let root = TestRoot::new();
        let temporary = root.path.join(REGISTRY_DIRECTORY).join(REGISTRY_TEMP_FILE);
        fs::write(&temporary, b"interrupted").expect("stale temporary");
        assert_eq!(
            LauncherRegistry::load(&root.path).expect("recover temporary"),
            LauncherRegistry::empty(),
        );
        assert!(!temporary.exists());
        std::os::unix::fs::symlink("/etc/passwd", &temporary).expect("unsafe temporary");
        assert!(matches!(
            LauncherRegistry::load(&root.path),
            Err(LauncherRegistryError::UnsafePath(_))
        ));
    }

    #[test]
    fn duplicate_catalog_identity_is_rejected_without_replacing_registry() {
        let root = TestRoot::new();
        let duplicate = catalog(vec![
            entry("org.kde.kate.desktop", "Kate"),
            entry("org.kde.kate.desktop", "Kate duplicate"),
        ]);
        assert!(matches!(
            LauncherRegistry::reconcile(&root.path, &duplicate),
            Err(LauncherRegistryError::DuplicateDesktopId)
        ));
        assert!(
            LauncherRegistry::load(&root.path)
                .expect("unchanged empty registry")
                .descriptors
                .is_empty()
        );

        let mut incomplete = catalog(vec![entry("org.kde.kate.desktop", "Kate")]);
        incomplete.truncated = true;
        assert!(matches!(
            LauncherRegistry::reconcile(&root.path, &incomplete),
            Err(LauncherRegistryError::IncompleteCatalog)
        ));
        assert!(
            LauncherRegistry::load(&root.path)
                .expect("still empty registry")
                .descriptors
                .is_empty()
        );
    }
}
