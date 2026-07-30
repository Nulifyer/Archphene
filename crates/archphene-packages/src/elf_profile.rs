use crate::desktop;
use archphene_process::integration::classify_library;
pub use archphene_process::integration::{
    TOPOLOGY_CHROMIUM, TOPOLOGY_GTK3, TOPOLOGY_GTK4, TOPOLOGY_OPENGL, TOPOLOGY_QT5, TOPOLOGY_QT6,
    TOPOLOGY_SDL2, TOPOLOGY_SDL3, TOPOLOGY_VULKAN, TOPOLOGY_WAYLAND, TOPOLOGY_X11,
};
use std::collections::{BTreeMap, BTreeSet, VecDeque};
use std::fs::{self, OpenOptions};
use std::io::{self, Read, Seek, SeekFrom};
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};

const O_CLOEXEC: i32 = 0o2000000;
const O_NOFOLLOW: i32 = 0o400000;
const ELF_HEADER_BYTES: usize = 64;
const PROGRAM_HEADER_BYTES: usize = 56;
const MAX_PROGRAM_HEADERS: usize = 256;
const MAX_DYNAMIC_BYTES: u64 = 1024 * 1024;
const MAX_DYNAMIC_ENTRIES: usize = MAX_DYNAMIC_BYTES as usize / 16;
const MAX_NEEDED_PER_OBJECT: usize = 256;
const MAX_NEEDED_NAME_BYTES: usize = 255;
const MAX_PROFILE_OBJECTS: usize = 256;
const MAX_PROFILE_EDGES: usize = 4096;
const MAX_SCRIPT_PROFILE_BYTES: u64 = 256 * 1024;
const SCRIPT_PROFILE_CHUNK_BYTES: usize = 8192;
const MAX_SCRIPT_PROFILE_LINE_BYTES: usize = 4096;
const MAX_SCRIPT_DELEGATES: usize = 8;
const MAX_RUNTIME_HINT_BYTES: u64 = 8 * 1024 * 1024;
const MAX_RUNTIME_HINT_TOTAL_BYTES: u64 = 32 * 1024 * 1024;
const RUNTIME_HINT_CHUNK_BYTES: usize = 8192;
const ELECTRON_NODE_MARKER: &[u8] = b"ELECTRON_RUN_AS_NODE=1";
const CAMERA_PORTAL_INTERFACE_MARKER: &[u8] = b"org.freedesktop.portal.Camera";
const CAMERA_PIPEWIRE_REMOTE_MARKER: &[u8] = b"OpenPipeWireRemote";

pub const BRIDGE_AUDIO_OUTPUT: u8 = 1 << 0;
pub const BRIDGE_PRINTING: u8 = 1 << 1;
pub const BRIDGE_CAMERA: u8 = 1 << 2;
pub const BRIDGE_SECRETS: u8 = 1 << 3;
pub const BRIDGE_AUDIO_INPUT: u8 = 1 << 4;
pub const BRIDGE_CAPABILITY_MASK: u8 =
    BRIDGE_AUDIO_OUTPUT | BRIDGE_PRINTING | BRIDGE_CAMERA | BRIDGE_SECRETS | BRIDGE_AUDIO_INPUT;

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct IntegrationProfile {
    pub topology: u16,
    pub bridge_capabilities: u8,
    pub profiled: bool,
    pub complete: bool,
}

#[derive(Clone, Debug)]
enum ObjectProfile {
    Elf(Vec<String>),
    Script(ScriptProfile),
    NotElf,
    Invalid,
}

#[derive(Clone, Debug, Default)]
struct ScriptProfile {
    topology: u16,
    delegates: Vec<String>,
}

#[derive(Clone, Copy, Debug)]
struct LoadSegment {
    offset: u64,
    virtual_address: u64,
    file_bytes: u64,
}

pub struct IntegrationProfiler<'a> {
    root: &'a Path,
    objects: BTreeMap<String, ObjectProfile>,
    runtime_hints: BTreeMap<String, (u16, u8)>,
    runtime_hint_bytes_remaining: u64,
}

impl<'a> IntegrationProfiler<'a> {
    pub fn new(root: &'a Path) -> Self {
        Self {
            root,
            objects: BTreeMap::new(),
            runtime_hints: BTreeMap::new(),
            runtime_hint_bytes_remaining: MAX_RUNTIME_HINT_TOTAL_BYTES,
        }
    }

    pub fn profile(&mut self, executable: &str) -> IntegrationProfile {
        let Some(executable) =
            desktop::resolve_root_regular_file(self.root, Path::new(executable), true)
        else {
            return IntegrationProfile::default();
        };
        let mut queue = VecDeque::from([executable]);
        let mut visited = BTreeSet::new();
        let mut topology = 0_u16;
        let mut bridge_capabilities = 0_u8;
        let mut complete = true;
        let mut edges = 0_usize;
        let mut root_profiled = false;
        let mut root_script = false;

        while let Some(logical_path) = queue.pop_front() {
            if !visited.insert(logical_path.clone()) {
                continue;
            }
            if visited.len() > MAX_PROFILE_OBJECTS {
                complete = false;
                break;
            }
            let profile = self.object_profile(&logical_path);
            let needed = match profile {
                ObjectProfile::Elf(needed) => {
                    if visited.len() == 1 {
                        root_profiled = true;
                        match self.runtime_hint_profile(&logical_path) {
                            Ok((topology_hints, bridge_hints)) => {
                                topology |= topology_hints;
                                bridge_capabilities |= bridge_hints;
                            }
                            Err(_) => complete = false,
                        }
                    }
                    needed
                }
                ObjectProfile::Script(script) => {
                    if visited.len() == 1 {
                        root_profiled = true;
                        root_script = true;
                    }
                    topology |= script.topology;
                    complete = false;
                    for delegate in script.delegates {
                        let Some(path) = desktop::resolve_root_regular_file(
                            self.root,
                            Path::new(&delegate),
                            true,
                        ) else {
                            continue;
                        };
                        queue.push_back(path);
                    }
                    continue;
                }
                ObjectProfile::NotElf if visited.len() == 1 => {
                    return IntegrationProfile::default();
                }
                ObjectProfile::NotElf | ObjectProfile::Invalid => {
                    complete = false;
                    continue;
                }
            };
            edges = match edges.checked_add(needed.len()) {
                Some(value) if value <= MAX_PROFILE_EDGES => value,
                _ => {
                    complete = false;
                    break;
                }
            };
            for name in needed {
                topology |= classify_library(&name);
                bridge_capabilities |= classify_bridge_library(&name);
                match self.resolve_library(&logical_path, &name) {
                    Some(path) => queue.push_back(path),
                    None => complete = false,
                }
            }
        }
        IntegrationProfile {
            topology,
            bridge_capabilities,
            profiled: root_profiled && (!root_script || topology != 0),
            complete: root_profiled && complete,
        }
    }

    fn object_profile(&mut self, logical_path: &str) -> ObjectProfile {
        if let Some(profile) = self.objects.get(logical_path) {
            return profile.clone();
        }
        let path = self.root.join(logical_path.trim_start_matches('/'));
        let profile = match parse_dynamic_dependencies(&path) {
            Ok(Some(needed)) => ObjectProfile::Elf(needed),
            Ok(None) => match profile_script(&path) {
                Ok(script) if script.topology != 0 || !script.delegates.is_empty() => {
                    ObjectProfile::Script(script)
                }
                Ok(_) => ObjectProfile::NotElf,
                Err(_) => ObjectProfile::Invalid,
            },
            Err(_) => ObjectProfile::Invalid,
        };
        self.objects
            .insert(logical_path.to_owned(), profile.clone());
        profile
    }

    fn resolve_library(&self, source: &str, name: &str) -> Option<String> {
        if !valid_library_name(name) {
            return None;
        }
        let source_directory = Path::new(source).parent()?;
        [
            source_directory.join(name),
            PathBuf::from("/usr/lib").join(name),
            PathBuf::from("/lib").join(name),
        ]
        .into_iter()
        .find_map(|candidate| desktop::resolve_root_regular_file(self.root, &candidate, false))
    }

    fn runtime_hint_profile(&mut self, logical_path: &str) -> io::Result<(u16, u8)> {
        if let Some(profile) = self.runtime_hints.get(logical_path) {
            return Ok(*profile);
        }
        let path = self.root.join(logical_path.trim_start_matches('/'));
        let (topology, bridge_capabilities, bytes_scanned) =
            scan_runtime_library_hints(&path, self.runtime_hint_bytes_remaining)?;
        self.runtime_hint_bytes_remaining = self
            .runtime_hint_bytes_remaining
            .checked_sub(bytes_scanned)
            .ok_or(io::ErrorKind::InvalidData)?;
        let profile = (topology, bridge_capabilities);
        self.runtime_hints.insert(logical_path.to_owned(), profile);
        Ok(profile)
    }
}

fn classify_bridge_library(name: &str) -> u8 {
    match name {
        "libpulse.so" | "libpulse.so.0" => BRIDGE_AUDIO_OUTPUT | BRIDGE_AUDIO_INPUT,
        "libcups.so" | "libcups.so.2" => BRIDGE_PRINTING,
        "libpipewire-0.3.so" | "libpipewire-0.3.so.0" => BRIDGE_CAMERA,
        "libsecret-1.so" | "libsecret-1.so.0" | "libKF6Wallet.so" | "libKF6Wallet.so.6"
        | "libKF5Wallet.so" | "libKF5Wallet.so.5" => BRIDGE_SECRETS,
        _ => 0,
    }
}

fn profile_script(path: &Path) -> io::Result<ScriptProfile> {
    let metadata = fs::symlink_metadata(path)?;
    if !metadata.is_file()
        || metadata.file_type().is_symlink()
        || metadata.len() > MAX_SCRIPT_PROFILE_BYTES
    {
        return Ok(ScriptProfile::default());
    }
    let mut file = OpenOptions::new()
        .read(true)
        .custom_flags(O_NOFOLLOW | O_CLOEXEC)
        .open(path)?;
    let opened = file.metadata()?;
    if !opened.is_file() || opened.len() != metadata.len() {
        return Err(io::Error::from(io::ErrorKind::InvalidData));
    }
    let mut bytes = [0_u8; SCRIPT_PROFILE_CHUNK_BYTES + MAX_SCRIPT_PROFILE_LINE_BYTES];
    let mut carry = 0_usize;
    let mut total = 0_u64;
    let mut shebang = false;
    let mut profile = ScriptProfile::default();
    loop {
        let read = file.read(&mut bytes[carry..carry + SCRIPT_PROFILE_CHUNK_BYTES])?;
        if read == 0 {
            break;
        }
        total = total
            .checked_add(u64::try_from(read).map_err(|_| io::ErrorKind::InvalidData)?)
            .ok_or(io::ErrorKind::InvalidData)?;
        if total > metadata.len() || total > MAX_SCRIPT_PROFILE_BYTES {
            return Err(io::Error::from(io::ErrorKind::InvalidData));
        }
        let available = carry + read;
        if total == u64::try_from(read).map_err(|_| io::ErrorKind::InvalidData)? {
            shebang = bytes[..available].starts_with(b"#!");
            if !shebang {
                return Ok(ScriptProfile::default());
            }
        }
        let mut line_start = 0_usize;
        while let Some(line_end) = bytes[line_start..available]
            .iter()
            .position(|byte| *byte == b'\n')
            .map(|offset| line_start + offset)
        {
            profile_script_line(&bytes[line_start..line_end], &mut profile);
            line_start = line_end + 1;
        }
        carry = available - line_start;
        if carry > MAX_SCRIPT_PROFILE_LINE_BYTES {
            return Ok(ScriptProfile::default());
        }
        bytes.copy_within(line_start..available, 0);
    }
    if total != metadata.len() || !shebang {
        return Err(io::Error::from(io::ErrorKind::InvalidData));
    }
    profile_script_line(&bytes[..carry], &mut profile);
    Ok(profile)
}

fn profile_script_line(line: &[u8], profile: &mut ScriptProfile) {
    if explicit_electron_node_line(line) {
        profile.topology |= TOPOLOGY_CHROMIUM;
    }
    if profile.delegates.len() >= MAX_SCRIPT_DELEGATES {
        return;
    }
    if let Some(delegate) = explicit_absolute_exec_delegate(line) {
        profile.delegates.push(delegate);
    }
}

fn explicit_electron_node_line(line: &[u8]) -> bool {
    let line = line.strip_suffix(b"\r").unwrap_or(line).trim_ascii_start();
    if line.starts_with(b"#") {
        return false;
    }
    let Some(mut rest) = line.strip_prefix(ELECTRON_NODE_MARKER) else {
        return false;
    };
    if !rest.first().is_some_and(u8::is_ascii_whitespace) {
        return false;
    }
    rest = rest.trim_ascii_start();
    if let Some(after_exec) = rest.strip_prefix(b"exec")
        && after_exec.first().is_some_and(u8::is_ascii_whitespace)
    {
        rest = after_exec.trim_ascii_start();
    }
    let command = rest
        .trim_ascii_start()
        .split(|byte| byte.is_ascii_whitespace())
        .next()
        .unwrap_or_default();
    command
        .windows(b"electron".len())
        .any(|window| window.eq_ignore_ascii_case(b"electron"))
}

fn explicit_absolute_exec_delegate(line: &[u8]) -> Option<String> {
    let line = line.strip_suffix(b"\r").unwrap_or(line).trim_ascii_start();
    if line.starts_with(b"#") {
        return None;
    }
    let rest = line.strip_prefix(b"exec")?;
    if !rest.first().is_some_and(u8::is_ascii_whitespace) {
        return None;
    }
    let command = rest
        .trim_ascii_start()
        .split(|byte| byte.is_ascii_whitespace())
        .next()
        .unwrap_or_default();
    if !command.starts_with(b"/")
        || command.len() > MAX_SCRIPT_PROFILE_LINE_BYTES
        || command
            .iter()
            .any(|byte| !byte.is_ascii_alphanumeric() && !b"/._+-".contains(byte))
    {
        return None;
    }
    String::from_utf8(command.to_vec()).ok()
}

fn valid_library_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= MAX_NEEDED_NAME_BYTES
        && !name.contains('/')
        && !name
            .bytes()
            .any(|byte| byte == 0 || byte.is_ascii_control())
}

fn scan_runtime_library_hints(path: &Path, available_bytes: u64) -> io::Result<(u16, u8, u64)> {
    let metadata = fs::symlink_metadata(path)?;
    if metadata.file_type().is_symlink()
        || !metadata.is_file()
        || metadata.len() > MAX_RUNTIME_HINT_BYTES
        || metadata.len() > available_bytes
    {
        return Ok((0, 0, 0));
    }
    let mut file = OpenOptions::new()
        .read(true)
        .custom_flags(O_NOFOLLOW | O_CLOEXEC)
        .open(path)?;
    let opened = file.metadata()?;
    if !opened.is_file() || opened.len() != metadata.len() {
        return Err(io::Error::from(io::ErrorKind::InvalidData));
    }

    let mut topology = 0_u16;
    let mut bridge_capabilities = 0_u8;
    let mut camera_portal_interface = false;
    let mut camera_pipewire_remote = false;
    let mut camera_portal_interface_match = 0_usize;
    let mut camera_pipewire_remote_match = 0_usize;
    let mut chunk = [0_u8; RUNTIME_HINT_CHUNK_BYTES];
    let mut token = [0_u8; MAX_NEEDED_NAME_BYTES];
    let mut token_length = 0_usize;
    let mut token_oversized = false;
    let mut total = 0_u64;
    loop {
        let length = file.read(&mut chunk)?;
        if length == 0 {
            break;
        }
        total = total
            .checked_add(u64::try_from(length).map_err(|_| io::ErrorKind::InvalidData)?)
            .ok_or(io::ErrorKind::InvalidData)?;
        if total > metadata.len() || total > MAX_RUNTIME_HINT_BYTES {
            return Err(io::Error::from(io::ErrorKind::InvalidData));
        }
        for byte in &chunk[..length] {
            camera_portal_interface |= advance_exact_marker(
                CAMERA_PORTAL_INTERFACE_MARKER,
                &mut camera_portal_interface_match,
                *byte,
            );
            camera_pipewire_remote |= advance_exact_marker(
                CAMERA_PIPEWIRE_REMOTE_MARKER,
                &mut camera_pipewire_remote_match,
                *byte,
            );
            if *byte == 0 {
                if !token_oversized
                    && let Ok(name) = std::str::from_utf8(&token[..token_length])
                    && valid_library_name(name)
                {
                    topology |= classify_library(name);
                    bridge_capabilities |= classify_bridge_library(name);
                }
                token_length = 0;
                token_oversized = false;
            } else if token_length == token.len() {
                token_oversized = true;
            } else if !token_oversized {
                token[token_length] = *byte;
                token_length += 1;
            }
        }
    }
    if total != metadata.len() {
        return Err(io::Error::from(io::ErrorKind::InvalidData));
    }
    if camera_portal_interface && camera_pipewire_remote {
        bridge_capabilities |= BRIDGE_CAMERA;
    }
    Ok((topology, bridge_capabilities, total))
}

fn advance_exact_marker(marker: &[u8], matched: &mut usize, byte: u8) -> bool {
    if byte == marker[*matched] {
        *matched += 1;
        if *matched == marker.len() {
            *matched = 0;
            return true;
        }
    } else {
        *matched = usize::from(byte == marker[0]);
    }
    false
}

fn parse_dynamic_dependencies(path: &Path) -> io::Result<Option<Vec<String>>> {
    let metadata = fs::symlink_metadata(path)?;
    if metadata.file_type().is_symlink() || !metadata.is_file() {
        return Err(io::Error::from(io::ErrorKind::InvalidData));
    }
    let mut file = OpenOptions::new()
        .read(true)
        .custom_flags(O_NOFOLLOW | O_CLOEXEC)
        .open(path)?;
    let opened = file.metadata()?;
    if !opened.is_file() || opened.len() != metadata.len() {
        return Err(io::Error::from(io::ErrorKind::InvalidData));
    }
    let mut header = [0_u8; ELF_HEADER_BYTES];
    let read = file.read(&mut header)?;
    if read < 4 || &header[..4] != b"\x7fELF" {
        return Ok(None);
    }
    if read != header.len()
        || header[4] != 2
        || header[5] != 1
        || header[6] != 1
        || u16_le(&header, 52)? as usize != ELF_HEADER_BYTES
    {
        return Err(io::Error::from(io::ErrorKind::InvalidData));
    }
    let program_offset = u64_le(&header, 32)?;
    let program_entry_bytes = usize::from(u16_le(&header, 54)?);
    let program_count = usize::from(u16_le(&header, 56)?);
    if !(PROGRAM_HEADER_BYTES..=256).contains(&program_entry_bytes)
        || program_count == 0
        || program_count > MAX_PROGRAM_HEADERS
    {
        return Err(io::Error::from(io::ErrorKind::InvalidData));
    }
    let table_bytes = program_entry_bytes
        .checked_mul(program_count)
        .and_then(|value| u64::try_from(value).ok())
        .ok_or_else(|| io::Error::from(io::ErrorKind::InvalidData))?;
    if program_offset
        .checked_add(table_bytes)
        .is_none_or(|end| end > metadata.len())
    {
        return Err(io::Error::from(io::ErrorKind::InvalidData));
    }

    let mut loads = Vec::new();
    let mut dynamic = None;
    let mut program = [0_u8; PROGRAM_HEADER_BYTES];
    for index in 0..program_count {
        let offset = program_offset
            .checked_add((index * program_entry_bytes) as u64)
            .ok_or_else(|| io::Error::from(io::ErrorKind::InvalidData))?;
        file.seek(SeekFrom::Start(offset))?;
        file.read_exact(&mut program)?;
        match u32_le(&program, 0)? {
            1 => {
                let segment = LoadSegment {
                    offset: u64_le(&program, 8)?,
                    virtual_address: u64_le(&program, 16)?,
                    file_bytes: u64_le(&program, 32)?,
                };
                if segment
                    .offset
                    .checked_add(segment.file_bytes)
                    .is_none_or(|end| end > metadata.len())
                {
                    return Err(io::Error::from(io::ErrorKind::InvalidData));
                }
                loads.push(segment);
            }
            2 => {
                if dynamic.is_some() {
                    return Err(io::Error::from(io::ErrorKind::InvalidData));
                }
                let offset = u64_le(&program, 8)?;
                let bytes = u64_le(&program, 32)?;
                if bytes > MAX_DYNAMIC_BYTES
                    || bytes % 16 != 0
                    || offset
                        .checked_add(bytes)
                        .is_none_or(|end| end > metadata.len())
                {
                    return Err(io::Error::from(io::ErrorKind::InvalidData));
                }
                dynamic = Some((offset, bytes));
            }
            _ => {}
        }
    }
    let Some((dynamic_offset, dynamic_bytes)) = dynamic else {
        return Ok(Some(Vec::new()));
    };
    let mut string_table_address = None;
    let mut string_table_bytes = None;
    let mut needed_offsets = Vec::new();
    let entries = usize::try_from(dynamic_bytes / 16)
        .ok()
        .filter(|count| *count <= MAX_DYNAMIC_ENTRIES)
        .ok_or_else(|| io::Error::from(io::ErrorKind::InvalidData))?;
    let mut dynamic_entry = [0_u8; 16];
    let mut terminated = false;
    for index in 0..entries {
        file.seek(SeekFrom::Start(dynamic_offset + (index * 16) as u64))?;
        file.read_exact(&mut dynamic_entry)?;
        let tag = u64_le(&dynamic_entry, 0)?;
        let value = u64_le(&dynamic_entry, 8)?;
        match tag {
            0 => {
                terminated = true;
                break;
            }
            1 => {
                if needed_offsets.len() >= MAX_NEEDED_PER_OBJECT {
                    return Err(io::Error::from(io::ErrorKind::InvalidData));
                }
                needed_offsets.push(value);
            }
            5 => string_table_address = Some(value),
            10 => string_table_bytes = Some(value),
            _ => {}
        }
    }
    if !terminated {
        return Err(io::Error::from(io::ErrorKind::InvalidData));
    }
    if needed_offsets.is_empty() {
        return Ok(Some(Vec::new()));
    }
    let string_table_address =
        string_table_address.ok_or_else(|| io::Error::from(io::ErrorKind::InvalidData))?;
    let string_table_bytes =
        string_table_bytes.ok_or_else(|| io::Error::from(io::ErrorKind::InvalidData))?;
    let (string_table_offset, string_table_available) =
        virtual_to_file_region(&loads, string_table_address)?;
    if string_table_bytes > string_table_available {
        return Err(io::Error::from(io::ErrorKind::InvalidData));
    }
    let mut needed = Vec::with_capacity(needed_offsets.len());
    for offset in needed_offsets {
        if offset >= string_table_bytes {
            return Err(io::Error::from(io::ErrorKind::InvalidData));
        }
        let remaining = string_table_bytes - offset;
        let limit = remaining.min((MAX_NEEDED_NAME_BYTES + 1) as u64);
        let string_offset = string_table_offset
            .checked_add(offset)
            .filter(|value| *value < metadata.len())
            .ok_or_else(|| io::Error::from(io::ErrorKind::InvalidData))?;
        file.seek(SeekFrom::Start(string_offset))?;
        let mut bytes = Vec::with_capacity(limit as usize);
        for _ in 0..limit {
            let mut byte = [0_u8; 1];
            file.read_exact(&mut byte)?;
            if byte[0] == 0 {
                break;
            }
            bytes.push(byte[0]);
        }
        if bytes.is_empty() || bytes.len() > MAX_NEEDED_NAME_BYTES || bytes.len() as u64 == limit {
            return Err(io::Error::from(io::ErrorKind::InvalidData));
        }
        let name =
            String::from_utf8(bytes).map_err(|_| io::Error::from(io::ErrorKind::InvalidData))?;
        if !valid_library_name(&name) {
            return Err(io::Error::from(io::ErrorKind::InvalidData));
        }
        needed.push(name);
    }
    Ok(Some(needed))
}

fn virtual_to_file_region(loads: &[LoadSegment], address: u64) -> io::Result<(u64, u64)> {
    loads
        .iter()
        .find_map(|segment| {
            let delta = address.checked_sub(segment.virtual_address)?;
            (delta < segment.file_bytes).then(|| {
                Some((
                    segment.offset.checked_add(delta)?,
                    segment.file_bytes.checked_sub(delta)?,
                ))
            })?
        })
        .ok_or_else(|| io::Error::from(io::ErrorKind::InvalidData))
}

fn u16_le(bytes: &[u8], offset: usize) -> io::Result<u16> {
    bytes
        .get(offset..offset + 2)
        .and_then(|value| value.try_into().ok())
        .map(u16::from_le_bytes)
        .ok_or_else(|| io::Error::from(io::ErrorKind::InvalidData))
}

fn u32_le(bytes: &[u8], offset: usize) -> io::Result<u32> {
    bytes
        .get(offset..offset + 4)
        .and_then(|value| value.try_into().ok())
        .map(u32::from_le_bytes)
        .ok_or_else(|| io::Error::from(io::ErrorKind::InvalidData))
}

fn u64_le(bytes: &[u8], offset: usize) -> io::Result<u64> {
    bytes
        .get(offset..offset + 8)
        .and_then(|value| value.try_into().ok())
        .map(u64::from_le_bytes)
        .ok_or_else(|| io::Error::from(io::ErrorKind::InvalidData))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::File;
    use std::io::Write;
    use std::os::unix::fs::PermissionsExt;
    use std::sync::atomic::{AtomicU64, Ordering};

    static TEST_ID: AtomicU64 = AtomicU64::new(1);

    #[test]
    fn profiles_the_current_test_binary_without_unbounded_reads() {
        let executable = std::env::current_exe().expect("current executable");
        let result = parse_dynamic_dependencies(&executable).expect("ELF profile");
        assert!(result.is_some());
    }

    #[test]
    fn non_elf_and_truncated_elf_are_distinct() {
        let root = test_root();
        let script = root.join("script");
        File::create(&script)
            .expect("script")
            .write_all(b"#!/bin/sh\n")
            .expect("script bytes");
        assert_eq!(
            parse_dynamic_dependencies(&script).expect("script profile"),
            None
        );
        let malformed = root.join("malformed");
        File::create(&malformed)
            .expect("malformed")
            .write_all(b"\x7fELF\x02\x01\x01")
            .expect("malformed bytes");
        assert!(parse_dynamic_dependencies(&malformed).is_err());
        fs::remove_dir_all(root).expect("cleanup");
    }

    #[test]
    fn library_classification_is_stack_generic() {
        assert_eq!(classify_library("libQt6Core.so.6"), TOPOLOGY_QT6);
        assert_eq!(classify_library("libgtk-3.so.0"), TOPOLOGY_GTK3);
        assert_eq!(classify_library("libSDL2-2.0.so.0"), TOPOLOGY_SDL2);
        assert_eq!(classify_library("libSDL3.so.0"), TOPOLOGY_SDL3);
        assert_eq!(classify_library("libwayland-client.so.0"), TOPOLOGY_WAYLAND);
        assert_eq!(classify_library("libunrelated.so.1"), 0);
        assert_eq!(classify_library("libgtk-3.something"), 0);
        assert_eq!(classify_library("libwayland-client.soevil"), 0);
        assert_eq!(
            classify_bridge_library("libpulse.so.0"),
            BRIDGE_AUDIO_OUTPUT | BRIDGE_AUDIO_INPUT
        );
        assert_eq!(classify_bridge_library("libcups.so.2"), BRIDGE_PRINTING);
        assert_eq!(
            classify_bridge_library("libpipewire-0.3.so.0"),
            BRIDGE_CAMERA,
        );
        assert_eq!(classify_bridge_library("libsecret-1.so.0"), BRIDGE_SECRETS);
        assert_eq!(classify_bridge_library("libpulse-mainloop-glib.so.0"), 0);
        assert_eq!(classify_bridge_library("libcups.so.evil"), 0);
    }

    #[test]
    fn parses_and_profiles_an_exact_bounded_dependency_graph() {
        let root = test_root();
        let executable = root.join("usr/bin/app");
        let library_root = root.join("usr/lib");
        fs::create_dir_all(executable.parent().expect("binary parent")).expect("binary directory");
        fs::create_dir_all(&library_root).expect("library directory");
        fs::write(
            &executable,
            elf_fixture(&["libQt6Core.so.6", "libwayland-client.so.0"]),
        )
        .expect("application ELF");
        fs::set_permissions(&executable, fs::Permissions::from_mode(0o755))
            .expect("executable permissions");
        fs::write(library_root.join("libQt6Core.so.6"), elf_fixture(&[])).expect("Qt library");
        fs::write(
            library_root.join("libwayland-client.so.0"),
            elf_fixture(&[]),
        )
        .expect("Wayland library");

        assert_eq!(
            parse_dynamic_dependencies(&executable).expect("dependencies"),
            Some(vec![
                "libQt6Core.so.6".to_owned(),
                "libwayland-client.so.0".to_owned(),
            ])
        );
        let profile = IntegrationProfiler::new(&root).profile("/usr/bin/app");
        assert!(profile.profiled);
        assert!(profile.complete);
        assert_eq!(profile.topology, TOPOLOGY_QT6 | TOPOLOGY_WAYLAND);
        assert_eq!(profile.bridge_capabilities, 0);
        fs::remove_dir_all(root).expect("cleanup");
    }

    #[test]
    fn derives_optional_bridge_needs_from_the_exact_verified_closure() {
        let root = test_root();
        let executable = root.join("usr/bin/app");
        let library_root = root.join("usr/lib");
        fs::create_dir_all(executable.parent().expect("binary parent")).expect("binary directory");
        fs::create_dir_all(&library_root).expect("library directory");
        fs::write(
            &executable,
            elf_fixture(&["libpulse.so.0", "libcups.so.2", "libunrelated.so.1"]),
        )
        .expect("application ELF");
        fs::set_permissions(&executable, fs::Permissions::from_mode(0o755))
            .expect("executable permissions");
        for library in ["libpulse.so.0", "libcups.so.2", "libunrelated.so.1"] {
            fs::write(library_root.join(library), elf_fixture(&[])).expect("dependency ELF");
        }

        let profile = IntegrationProfiler::new(&root).profile("/usr/bin/app");
        assert!(profile.profiled);
        assert!(profile.complete);
        assert_eq!(
            profile.bridge_capabilities,
            BRIDGE_AUDIO_OUTPUT | BRIDGE_AUDIO_INPUT | BRIDGE_PRINTING,
        );
        assert_eq!(profile.bridge_capabilities & !BRIDGE_CAPABILITY_MASK, 0);
        fs::remove_dir_all(root).expect("cleanup");
    }

    #[test]
    fn profiles_exact_null_terminated_runtime_library_hints() {
        let root = test_root();
        let executable = root.join("usr/bin/runtime-loader");
        fs::create_dir_all(executable.parent().expect("binary parent")).expect("binary directory");
        let mut image = elf_fixture(&[]);
        image.extend_from_slice(
            b"\0libEGL.so.1\0libGLESv2.so.2\0prefixlibvulkan.so.1\0\
              libQt6Core.so.6 is unavailable\0",
        );
        fs::write(&executable, image).expect("runtime-loading ELF");
        fs::set_permissions(&executable, fs::Permissions::from_mode(0o755))
            .expect("executable permissions");

        let profile = IntegrationProfiler::new(&root).profile("/usr/bin/runtime-loader");
        assert!(profile.profiled);
        assert!(profile.complete);
        assert_eq!(profile.topology, TOPOLOGY_OPENGL);
        fs::remove_dir_all(root).expect("cleanup");
    }

    #[test]
    fn profiles_exact_dynamic_camera_portal_contract() {
        let root = test_root();
        let executable = root.join("usr/bin/camera");
        fs::create_dir_all(executable.parent().expect("binary parent")).expect("binary directory");
        let mut image = elf_fixture(&[]);
        image.extend_from_slice(
            b"\0/org.freedesktop.portal.Camera:IsCameraPresent\0\
              AccessCameraOpenPipeWireRemoteorg.freedesktop.portal.Request\0\
              org.freedesktop.portal.Camera documentation\0",
        );
        fs::write(&executable, image).expect("portal camera ELF");
        fs::set_permissions(&executable, fs::Permissions::from_mode(0o755))
            .expect("executable permissions");

        let profile = IntegrationProfiler::new(&root).profile("/usr/bin/camera");
        assert!(profile.profiled);
        assert!(profile.complete);
        assert_eq!(profile.bridge_capabilities, BRIDGE_CAMERA);

        let unrelated = root.join("usr/bin/unrelated");
        let mut image = elf_fixture(&[]);
        image.extend_from_slice(b"\0org.freedesktop.portal.Camera\0");
        fs::write(&unrelated, image).expect("incomplete portal ELF");
        fs::set_permissions(&unrelated, fs::Permissions::from_mode(0o755))
            .expect("unrelated permissions");
        assert_eq!(
            IntegrationProfiler::new(&root)
                .profile("/usr/bin/unrelated")
                .bridge_capabilities,
            0,
        );
        fs::remove_dir_all(root).expect("cleanup");
    }

    #[test]
    fn runtime_hint_scan_carries_a_token_across_fixed_chunks() {
        let root = test_root();
        let executable = root.join("runtime-loader");
        let mut image = vec![b'x'; RUNTIME_HINT_CHUNK_BYTES - 1];
        image.push(0);
        image.extend_from_slice(b"libvulkan.so.1\0");
        fs::write(&executable, image).expect("runtime-loading image");
        assert_eq!(
            scan_runtime_library_hints(&executable, MAX_RUNTIME_HINT_TOTAL_BYTES)
                .expect("runtime hints")
                .0,
            TOPOLOGY_VULKAN,
        );
        assert_eq!(
            scan_runtime_library_hints(
                &executable,
                u64::try_from(RUNTIME_HINT_CHUNK_BYTES).expect("chunk size"),
            )
            .expect("bounded runtime hints"),
            (0, 0, 0),
        );
        let camera = root.join("camera-runtime-loader");
        let mut camera_image = vec![b'x'; RUNTIME_HINT_CHUNK_BYTES - 5];
        camera_image.extend_from_slice(CAMERA_PORTAL_INTERFACE_MARKER);
        camera_image.push(0);
        camera_image.extend_from_slice(CAMERA_PIPEWIRE_REMOTE_MARKER);
        fs::write(&camera, camera_image).expect("camera runtime-loading image");
        assert_eq!(
            scan_runtime_library_hints(&camera, MAX_RUNTIME_HINT_TOTAL_BYTES)
                .expect("camera runtime hints")
                .1,
            BRIDGE_CAMERA,
        );
        fs::remove_dir_all(root).expect("cleanup");
    }

    #[test]
    fn profiles_only_an_explicit_electron_shell_contract() {
        let root = test_root();
        let executable = root.join("usr/bin/electron-app-real");
        fs::create_dir_all(executable.parent().expect("script parent")).expect("script directory");
        let mut script = b"#!/bin/sh\n".to_vec();
        while script.len() < SCRIPT_PROFILE_CHUNK_BYTES - 8 {
            script.extend_from_slice(b"#\n");
        }
        assert_eq!(script.len(), SCRIPT_PROFILE_CHUNK_BYTES - 8);
        script
            .extend_from_slice(b"ELECTRON_RUN_AS_NODE=1 exec /usr/lib/electron/electron \"$@\"\n");
        fs::write(&executable, script).expect("Electron script");
        fs::set_permissions(&executable, fs::Permissions::from_mode(0o755))
            .expect("script permissions");
        std::os::unix::fs::symlink("electron-app-real", root.join("usr/bin/electron-app"))
            .expect("contained launcher symlink");

        let profile = IntegrationProfiler::new(&root).profile("/usr/bin/electron-app");
        assert_eq!(profile.topology, TOPOLOGY_CHROMIUM);
        assert!(profile.profiled);
        assert!(!profile.complete);

        for unrelated_script in [
            b"#!/bin/sh\n# ELECTRON_RUN_AS_NODE=1 exec /usr/lib/electron/electron\nexec app \"$@\"\n"
                .as_slice(),
            b"#!/bin/sh\nELECTRON_RUN_AS_NODE=1 exec /usr/bin/unrelated \"$@\"\n".as_slice(),
            b"#!/bin/sh\n# Saying Electron is not an execution contract.\nexec app \"$@\"\n"
                .as_slice(),
        ] {
            fs::write(&executable, unrelated_script).expect("unrelated script");
            let unrelated = IntegrationProfiler::new(&root).profile("/usr/bin/electron-app");
            assert_eq!(unrelated, IntegrationProfile::default());
        }
        fs::remove_dir_all(root).expect("cleanup");
    }

    #[test]
    fn follows_a_bounded_literal_script_delegate_to_an_electron_contract() {
        let root = test_root();
        let launcher = root.join("usr/bin/editor");
        let delegated = root.join("usr/share/editor/bin/editor");
        fs::create_dir_all(launcher.parent().expect("launcher parent"))
            .expect("launcher directory");
        fs::create_dir_all(delegated.parent().expect("delegate parent"))
            .expect("delegate directory");
        fs::write(
            &launcher,
            b"#!/bin/bash\nexec /usr/share/editor/bin/editor \"$@\"\n",
        )
        .expect("launcher script");
        fs::write(
            &delegated,
            b"#!/bin/sh\nELECTRON=\"$APP_ROOT/editor\"\nCLI=\"$APP_ROOT/cli.js\"\nELECTRON_RUN_AS_NODE=1 \"$ELECTRON\" \"$CLI\" \"$@\"\n",
        )
        .expect("delegated script");
        fs::set_permissions(&launcher, fs::Permissions::from_mode(0o755))
            .expect("launcher permissions");
        fs::set_permissions(&delegated, fs::Permissions::from_mode(0o755))
            .expect("delegate permissions");

        let profile = IntegrationProfiler::new(&root).profile("/usr/bin/editor");
        assert_eq!(profile.topology, TOPOLOGY_CHROMIUM);
        assert!(profile.profiled);
        assert!(!profile.complete);

        fs::write(
            &delegated,
            b"#!/bin/sh\nELECTRON_RUN_AS_NODE=1 /usr/bin/unrelated \"$@\"\n",
        )
        .expect("non-Electron delegated script");
        let unrelated = IntegrationProfiler::new(&root).profile("/usr/bin/editor");
        assert_eq!(unrelated, IntegrationProfile::default());
        fs::remove_dir_all(root).expect("cleanup");
    }

    fn elf_fixture(needed: &[&str]) -> Vec<u8> {
        let mut string_table = vec![0_u8];
        let mut string_offsets = Vec::new();
        for name in needed {
            string_offsets.push(string_table.len() as u64);
            string_table.extend_from_slice(name.as_bytes());
            string_table.push(0);
        }
        let dynamic_entries = needed.len() + 3;
        let dynamic_offset = ELF_HEADER_BYTES + 2 * PROGRAM_HEADER_BYTES;
        let dynamic_bytes = dynamic_entries * 16;
        let string_offset = dynamic_offset + dynamic_bytes;
        let total_bytes = string_offset + string_table.len();
        let virtual_base = 0x40_0000_u64;
        let mut bytes = vec![0_u8; total_bytes];
        bytes[..7].copy_from_slice(b"\x7fELF\x02\x01\x01");
        bytes[16..18].copy_from_slice(&3_u16.to_le_bytes());
        bytes[18..20].copy_from_slice(&62_u16.to_le_bytes());
        bytes[20..24].copy_from_slice(&1_u32.to_le_bytes());
        bytes[32..40].copy_from_slice(&(ELF_HEADER_BYTES as u64).to_le_bytes());
        bytes[52..54].copy_from_slice(&(ELF_HEADER_BYTES as u16).to_le_bytes());
        bytes[54..56].copy_from_slice(&(PROGRAM_HEADER_BYTES as u16).to_le_bytes());
        bytes[56..58].copy_from_slice(&2_u16.to_le_bytes());

        let load = ELF_HEADER_BYTES;
        bytes[load..load + 4].copy_from_slice(&1_u32.to_le_bytes());
        bytes[load + 16..load + 24].copy_from_slice(&virtual_base.to_le_bytes());
        bytes[load + 32..load + 40].copy_from_slice(&(total_bytes as u64).to_le_bytes());
        let dynamic = load + PROGRAM_HEADER_BYTES;
        bytes[dynamic..dynamic + 4].copy_from_slice(&2_u32.to_le_bytes());
        bytes[dynamic + 8..dynamic + 16].copy_from_slice(&(dynamic_offset as u64).to_le_bytes());
        bytes[dynamic + 16..dynamic + 24]
            .copy_from_slice(&(virtual_base + dynamic_offset as u64).to_le_bytes());
        bytes[dynamic + 32..dynamic + 40].copy_from_slice(&(dynamic_bytes as u64).to_le_bytes());

        let mut cursor = dynamic_offset;
        for offset in string_offsets {
            bytes[cursor..cursor + 8].copy_from_slice(&1_u64.to_le_bytes());
            bytes[cursor + 8..cursor + 16].copy_from_slice(&offset.to_le_bytes());
            cursor += 16;
        }
        bytes[cursor..cursor + 8].copy_from_slice(&5_u64.to_le_bytes());
        bytes[cursor + 8..cursor + 16]
            .copy_from_slice(&(virtual_base + string_offset as u64).to_le_bytes());
        cursor += 16;
        bytes[cursor..cursor + 8].copy_from_slice(&10_u64.to_le_bytes());
        bytes[cursor + 8..cursor + 16].copy_from_slice(&(string_table.len() as u64).to_le_bytes());
        bytes[string_offset..].copy_from_slice(&string_table);
        bytes
    }

    fn test_root() -> PathBuf {
        let id = TEST_ID.fetch_add(1, Ordering::Relaxed);
        let path = std::env::temp_dir().join(format!(
            "archphene-elf-profile-test-{}-{id}",
            std::process::id()
        ));
        fs::create_dir_all(&path).expect("test root");
        fs::set_permissions(&path, fs::Permissions::from_mode(0o700)).expect("permissions");
        path
    }
}
