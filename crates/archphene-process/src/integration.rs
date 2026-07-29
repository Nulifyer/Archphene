use std::fs::{self, File, OpenOptions};
use std::io::{self, Read};
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::MetadataExt;
use std::os::unix::fs::OpenOptionsExt;
use std::path::Path;

const O_CLOEXEC: i32 = 0o2000000;
const O_NOFOLLOW: i32 = 0o400000;
const MAX_PROCESSES_SCANNED: usize = 8192;
const MAX_GROUP_PROCESSES: usize = 512;
const MAX_PROCESS_STAT_BYTES: u64 = 4096;
const MAX_PROCESS_CMDLINE_BYTES: usize = 16 * 1024;
const MAX_MAP_LINE_BYTES: usize = 8192;
const MAX_MAP_LINES: usize = 65_536;
const MAX_MAP_BYTES_PER_PROCESS: usize = 8 * 1024 * 1024;
const MAX_ROOT_PATH_BYTES: usize = 4096;

pub const TOPOLOGY_QT5: u16 = 1 << 0;
pub const TOPOLOGY_QT6: u16 = 1 << 1;
pub const TOPOLOGY_GTK3: u16 = 1 << 2;
pub const TOPOLOGY_GTK4: u16 = 1 << 3;
pub const TOPOLOGY_SDL2: u16 = 1 << 4;
pub const TOPOLOGY_SDL3: u16 = 1 << 5;
pub const TOPOLOGY_CHROMIUM: u16 = 1 << 6;
pub const TOPOLOGY_WAYLAND: u16 = 1 << 8;
pub const TOPOLOGY_X11: u16 = 1 << 9;
pub const TOPOLOGY_OPENGL: u16 = 1 << 10;
pub const TOPOLOGY_VULKAN: u16 = 1 << 11;

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct IntegrationObservation {
    pub topology: u16,
    pub observed: bool,
    pub complete: bool,
}

struct RootAliases<'a> {
    primary: &'a [u8],
    alternate: [u8; MAX_ROOT_PATH_BYTES],
    alternate_length: usize,
}

impl<'a> RootAliases<'a> {
    fn new(arch_root: &'a Path) -> io::Result<Self> {
        let primary = arch_root.as_os_str().as_bytes();
        if primary.is_empty() || primary.len() > MAX_ROOT_PATH_BYTES {
            return Err(io::Error::from(io::ErrorKind::InvalidInput));
        }
        let mut aliases = Self {
            primary,
            alternate: [0; MAX_ROOT_PATH_BYTES],
            alternate_length: 0,
        };
        const USER_ZERO: &[u8] = b"/data/user/0/";
        const DATA_ALIAS: &[u8] = b"/data/data/";
        if let Some(suffix) = primary.strip_prefix(USER_ZERO) {
            let length = DATA_ALIAS.len().saturating_add(suffix.len());
            if length <= aliases.alternate.len() {
                aliases.alternate[..DATA_ALIAS.len()].copy_from_slice(DATA_ALIAS);
                aliases.alternate[DATA_ALIAS.len()..length].copy_from_slice(suffix);
                let alternate =
                    Path::new(std::ffi::OsStr::from_bytes(&aliases.alternate[..length]));
                let primary_metadata = fs::metadata(arch_root)?;
                if fs::metadata(alternate).is_ok_and(|metadata| {
                    metadata.dev() == primary_metadata.dev()
                        && metadata.ino() == primary_metadata.ino()
                }) {
                    aliases.alternate_length = length;
                }
            }
        }
        Ok(aliases)
    }

    fn contains(&self, path: &[u8]) -> bool {
        root_contains(self.primary, path)
            || self.alternate_length != 0
                && root_contains(&self.alternate[..self.alternate_length], path)
    }
}

pub fn classify_library(name: &str) -> u16 {
    let mut topology = 0_u16;
    if soname_family(name, "libQt5") {
        topology |= TOPOLOGY_QT5;
    }
    if soname_family(name, "libQt6") {
        topology |= TOPOLOGY_QT6;
    }
    if soname_family(name, "libgtk-3") {
        topology |= TOPOLOGY_GTK3;
    }
    if soname_family(name, "libgtk-4") {
        topology |= TOPOLOGY_GTK4;
    }
    if soname_family(name, "libSDL2") {
        topology |= TOPOLOGY_SDL2;
    }
    if soname_family(name, "libSDL3") {
        topology |= TOPOLOGY_SDL3;
    }
    if soname_family(name, "libcef") || soname_family(name, "libchromiumcontent") {
        topology |= TOPOLOGY_CHROMIUM;
    }
    if soname_family(name, "libwayland-client") {
        topology |= TOPOLOGY_WAYLAND;
    }
    if soname_family(name, "libX11") || soname_family(name, "libxcb") {
        topology |= TOPOLOGY_X11;
    }
    if soname_family(name, "libGL")
        || soname_family(name, "libOpenGL")
        || soname_family(name, "libEGL")
        || soname_family(name, "libGLES")
    {
        topology |= TOPOLOGY_OPENGL;
    }
    if soname_family(name, "libvulkan") {
        topology |= TOPOLOGY_VULKAN;
    }
    topology
}

pub fn observe_process_group(
    process_group: u32,
    arch_root: &Path,
) -> io::Result<IntegrationObservation> {
    if process_group == 0 || !arch_root.is_absolute() {
        return Err(io::Error::from(io::ErrorKind::InvalidInput));
    }
    let roots = RootAliases::new(arch_root)?;
    let mut members = [0_u32; MAX_GROUP_PROCESSES];
    let mut member_count = 0_usize;
    let mut complete = true;
    let mut scanned = 0_usize;
    for entry in fs::read_dir("/proc")? {
        scanned = scanned
            .checked_add(1)
            .ok_or_else(|| io::Error::from(io::ErrorKind::InvalidData))?;
        if scanned > MAX_PROCESSES_SCANNED {
            return Err(io::Error::from(io::ErrorKind::InvalidData));
        }
        let entry = entry?;
        let Some(process) = entry
            .file_name()
            .to_str()
            .and_then(|name| name.parse::<u32>().ok())
            .filter(|process| *process != 0)
        else {
            continue;
        };
        let stat = entry.path().join("stat");
        let group = match read_process_group(&stat) {
            Ok(group) => group,
            Err(error)
                if matches!(
                    error.kind(),
                    io::ErrorKind::NotFound | io::ErrorKind::PermissionDenied
                ) =>
            {
                continue;
            }
            Err(_) => {
                complete = false;
                continue;
            }
        };
        if group == process_group {
            if member_count == members.len() {
                complete = false;
                continue;
            }
            members[member_count] = process;
            member_count += 1;
        }
    }
    if member_count == 0 {
        return Err(io::Error::from(io::ErrorKind::NotFound));
    }

    let mut topology = 0_u16;
    let mut map_lines = 0_usize;
    for process in &members[..member_count] {
        let process_root = Path::new("/proc").join(process.to_string());
        match observe_cmdline(&process_root.join("cmdline")) {
            Ok(true) => topology |= TOPOLOGY_CHROMIUM,
            Ok(false) => {}
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(_) => complete = false,
        }
        match observe_maps(
            &process_root.join("maps"),
            &roots,
            &mut topology,
            &mut map_lines,
        ) {
            Ok(scan_complete) => complete &= scan_complete,
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(_) => complete = false,
        }
    }
    Ok(IntegrationObservation {
        topology,
        observed: true,
        complete,
    })
}

fn read_process_group(path: &Path) -> io::Result<u32> {
    let metadata = fs::symlink_metadata(path)?;
    if !metadata.is_file() || metadata.len() > MAX_PROCESS_STAT_BYTES {
        return Err(io::Error::from(io::ErrorKind::InvalidData));
    }
    let mut file = open_proc_file(path)?;
    let mut bytes = [0_u8; MAX_PROCESS_STAT_BYTES as usize];
    let length = read_bounded(&mut file, &mut bytes)?;
    process_group_from_stat(&bytes[..length])
        .ok_or_else(|| io::Error::from(io::ErrorKind::InvalidData))
}

fn process_group_from_stat(stat: &[u8]) -> Option<u32> {
    let close = stat.windows(2).rposition(|bytes| bytes == b") ")?;
    let mut fields = stat
        .get(close + 2..)?
        .split(|byte| byte.is_ascii_whitespace());
    let state = fields.find(|field| !field.is_empty())?;
    if state.len() != 1 {
        return None;
    }
    let _parent = fields.find(|field| !field.is_empty())?;
    parse_u32(fields.find(|field| !field.is_empty())?)
}

fn observe_cmdline(path: &Path) -> io::Result<bool> {
    let mut file = open_proc_file(path)?;
    let mut bytes = [0_u8; MAX_PROCESS_CMDLINE_BYTES];
    let length = read_bounded(&mut file, &mut bytes)?;
    Ok(bytes[..length].split(|byte| *byte == 0).any(|argument| {
        matches!(
            argument,
            b"--type=renderer" | b"--type=gpu-process" | b"--type=utility" | b"--type=zygote"
        )
    }))
}

fn observe_maps(
    path: &Path,
    roots: &RootAliases<'_>,
    topology: &mut u16,
    total_lines: &mut usize,
) -> io::Result<bool> {
    let mut file = open_proc_file(path)?;
    let mut input = [0_u8; 8192];
    let mut line = [0_u8; MAX_MAP_LINE_BYTES];
    let mut line_length = 0_usize;
    let mut overflowed = false;
    let mut complete = true;
    let mut total_bytes = 0_usize;
    loop {
        let read = file.read(&mut input)?;
        if read == 0 {
            break;
        }
        total_bytes = total_bytes
            .checked_add(read)
            .ok_or_else(|| io::Error::from(io::ErrorKind::InvalidData))?;
        if total_bytes > MAX_MAP_BYTES_PER_PROCESS {
            return Ok(false);
        }
        for byte in &input[..read] {
            if *byte == b'\n' {
                *total_lines = total_lines
                    .checked_add(1)
                    .ok_or_else(|| io::Error::from(io::ErrorKind::InvalidData))?;
                if *total_lines > MAX_MAP_LINES {
                    return Ok(false);
                }
                if overflowed {
                    complete = false;
                } else {
                    *topology |= classify_map_line(&line[..line_length], roots);
                }
                line_length = 0;
                overflowed = false;
            } else if line_length < line.len() {
                line[line_length] = *byte;
                line_length += 1;
            } else {
                overflowed = true;
            }
        }
    }
    if line_length != 0 || overflowed {
        complete = false;
    }
    Ok(complete)
}

fn classify_map_line(line: &[u8], roots: &RootAliases<'_>) -> u16 {
    let Some(path) = line
        .split(|byte| byte.is_ascii_whitespace())
        .filter(|field| !field.is_empty())
        .nth(5)
    else {
        return 0;
    };
    if !roots.contains(path) {
        return 0;
    }
    let Some(name) = path.rsplit(|byte| *byte == b'/').next() else {
        return 0;
    };
    std::str::from_utf8(name).map_or(0, classify_library)
}

fn root_contains(root: &[u8], path: &[u8]) -> bool {
    path.starts_with(root) && (root == b"/" || path.get(root.len()) == Some(&b'/'))
}

fn open_proc_file(path: &Path) -> io::Result<File> {
    OpenOptions::new()
        .read(true)
        .custom_flags(O_NOFOLLOW | O_CLOEXEC)
        .open(path)
}

fn read_bounded(file: &mut File, output: &mut [u8]) -> io::Result<usize> {
    let mut length = 0_usize;
    while length < output.len() {
        let read = file.read(&mut output[length..])?;
        if read == 0 {
            return Ok(length);
        }
        length += read;
    }
    let mut extra = [0_u8; 1];
    if file.read(&mut extra)? != 0 {
        return Err(io::Error::from(io::ErrorKind::InvalidData));
    }
    Ok(length)
}

fn soname_family(name: &str, family: &str) -> bool {
    name.strip_prefix(family)
        .and_then(|suffix| suffix.find(".so").map(|index| &suffix[index + 3..]))
        .is_some_and(|version| {
            version.is_empty()
                || version.strip_prefix('.').is_some_and(|components| {
                    !components.is_empty()
                        && components.split('.').all(|component| {
                            !component.is_empty()
                                && component.bytes().all(|byte| byte.is_ascii_digit())
                        })
                })
        })
}

fn parse_u32(bytes: &[u8]) -> Option<u32> {
    if bytes.is_empty() {
        return None;
    }
    bytes.iter().try_fold(0_u32, |value, byte| {
        value
            .checked_mul(10)?
            .checked_add(u32::from(byte.checked_sub(b'0')?))
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::process::CommandExt;
    use std::process::Command;

    #[test]
    fn classifies_exact_library_families() {
        assert_eq!(classify_library("libQt6Core.so.6"), TOPOLOGY_QT6);
        assert_eq!(classify_library("libgtk-3.so.0"), TOPOLOGY_GTK3);
        assert_eq!(classify_library("libSDL2-2.0.so.0"), TOPOLOGY_SDL2);
        assert_eq!(classify_library("libSDL3.so.0"), TOPOLOGY_SDL3);
        assert_eq!(classify_library("libcef.so"), TOPOLOGY_CHROMIUM);
        assert_eq!(classify_library("libwayland-client.so.0"), TOPOLOGY_WAYLAND);
        assert_eq!(classify_library("libwayland-client.soevil"), 0);
        assert_eq!(classify_library("libQt6Core.so.6 is unavailable"), 0);
        assert_eq!(classify_library("libEGL.so.1.debug"), 0);
    }

    #[test]
    fn parses_process_groups_with_hostile_process_names() {
        assert_eq!(
            process_group_from_stat(b"123 (name ) with spaces) S 4 55 6 7\n"),
            Some(55)
        );
        assert_eq!(process_group_from_stat(b"123 malformed"), None);
    }

    #[test]
    fn map_classification_requires_the_exact_managed_root() {
        let root = RootAliases {
            primary: b"/data/root",
            alternate: [0; MAX_ROOT_PATH_BYTES],
            alternate_length: 0,
        };
        let line = b"1-2 r-xp 0 00:00 1 /data/root/usr/lib/libgtk-4.so.1";
        assert_eq!(classify_map_line(line, &root), TOPOLOGY_GTK4);
        let shorter = RootAliases {
            primary: b"/data/roo",
            alternate: [0; MAX_ROOT_PATH_BYTES],
            alternate_length: 0,
        };
        assert_eq!(classify_map_line(line, &shorter), 0);
        assert_eq!(
            classify_map_line(b"1-2 r-xp 0 00:00 1 /outside/libwayland-client.so.0", &root),
            0
        );
    }

    #[test]
    fn observes_the_current_test_process_without_heap_sized_map_reads() {
        let process = std::process::id();
        let stat = fs::read(format!("/proc/{process}/stat")).expect("process stat");
        let group = process_group_from_stat(&stat).expect("process group");
        let observation =
            observe_process_group(group, Path::new("/")).expect("process observation");
        assert!(observation.observed);
    }

    #[test]
    fn observes_chromium_process_roles_from_the_exact_command_line() {
        let mut child = Command::new("/bin/sleep")
            .arg0("--type=renderer")
            .arg("2")
            .process_group(0)
            .spawn()
            .expect("renderer fixture");
        let observation =
            observe_process_group(child.id(), Path::new("/")).expect("renderer observation");
        let _ = child.kill();
        let _ = child.wait();
        assert!(observation.observed);
        assert_ne!(observation.topology & TOPOLOGY_CHROMIUM, 0);
    }
}
