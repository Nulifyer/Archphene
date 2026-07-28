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

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct IntegrationProfile {
    pub topology: u16,
    pub profiled: bool,
    pub complete: bool,
}

#[derive(Clone, Debug)]
enum ObjectProfile {
    Elf(Vec<String>),
    NotElf,
    Invalid,
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
}

impl<'a> IntegrationProfiler<'a> {
    pub fn new(root: &'a Path) -> Self {
        Self {
            root,
            objects: BTreeMap::new(),
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
        let mut complete = true;
        let mut edges = 0_usize;
        let mut root_profiled = false;

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
                    }
                    needed
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
                match self.resolve_library(&logical_path, &name) {
                    Some(path) => queue.push_back(path),
                    None => complete = false,
                }
            }
        }
        IntegrationProfile {
            topology,
            profiled: root_profiled,
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
            Ok(None) => ObjectProfile::NotElf,
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
}

fn valid_library_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= MAX_NEEDED_NAME_BYTES
        && !name.contains('/')
        && !name
            .bytes()
            .any(|byte| byte == 0 || byte.is_ascii_control())
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
