use std::collections::{BinaryHeap, VecDeque};
use std::ffi::OsString;
use std::fmt;
use std::fs::{self, OpenOptions};
use std::io::Read;
use std::os::unix::fs::{MetadataExt, OpenOptionsExt, PermissionsExt};
use std::path::{Component, Path, PathBuf};

pub const MAX_DESKTOP_ENTRY_BYTES: usize = 512 * 1024;
pub const MAX_DESKTOP_ID_BYTES: usize = 240;
pub const MAX_DISPLAY_NAME_BYTES: usize = 256;
pub const MAX_EXEC_ARGUMENTS: usize = 32;
pub const MAX_EXEC_ARGUMENT_BYTES: usize = 512;
pub const MAX_MIME_TYPES: usize = 16;
pub const MAX_DESKTOP_ENTRIES: usize = 256;
pub const MAX_DESKTOP_FILES_EXAMINED: usize = 1024;
pub const MAX_DESKTOP_DIRECTORY_ENTRIES: usize = 4096;
pub const MAX_DESKTOP_TOTAL_BYTES: usize = 4 * 1024 * 1024;

const APPLICATION_DIRECTORY: &str = "usr/share/applications";
const MAX_ROOT_SYMLINKS: usize = 16;
const O_NOFOLLOW: i32 = 0o400000;
const O_CLOEXEC: i32 = 0o2000000;

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ExecArgument {
    Literal(String),
    SingleFile,
    MultipleFiles,
    SingleUrl,
    MultipleUrls,
    Icon,
    DisplayName,
    DesktopFile,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DesktopEntry {
    pub desktop_id: String,
    pub source_package: Option<String>,
    pub executable_package: Option<String>,
    pub integration_topology: u16,
    pub integration_profiled: bool,
    pub integration_complete: bool,
    pub name: String,
    pub executable: String,
    pub arguments: Vec<ExecArgument>,
    pub try_exec: Option<String>,
    pub icon: Option<String>,
    pub mime_types: Vec<String>,
    pub terminal: bool,
}

#[derive(Debug, Eq, PartialEq)]
pub struct DesktopCatalog {
    pub entries: Vec<DesktopEntry>,
    pub examined: usize,
    pub rejected: usize,
    pub truncated: bool,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum DesktopEntryError {
    TooLarge,
    InvalidUtf8,
    InvalidDesktopId,
    DuplicateField,
    InvalidField,
    InvalidExec,
    LimitExceeded,
}

impl fmt::Display for DesktopEntryError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::TooLarge => formatter.write_str("desktop entry exceeds its size limit"),
            Self::InvalidUtf8 => formatter.write_str("desktop entry is not valid UTF-8"),
            Self::InvalidDesktopId => formatter.write_str("desktop entry identity is invalid"),
            Self::DuplicateField => formatter.write_str("desktop entry repeats a required field"),
            Self::InvalidField => formatter.write_str("desktop entry field is invalid"),
            Self::InvalidExec => formatter.write_str("desktop entry Exec is invalid"),
            Self::LimitExceeded => formatter.write_str("desktop entry exceeds a field limit"),
        }
    }
}

impl std::error::Error for DesktopEntryError {}

pub fn discover_desktop_entries(arch_root: &Path) -> Result<DesktopCatalog, DesktopEntryError> {
    let root = canonical_directory(arch_root)?;
    let applications = root.join(APPLICATION_DIRECTORY);
    let metadata = match fs::symlink_metadata(&applications) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            return Ok(DesktopCatalog {
                entries: Vec::new(),
                examined: 0,
                rejected: 0,
                truncated: false,
            });
        }
        Err(_) => return Err(DesktopEntryError::InvalidField),
    };
    if metadata.file_type().is_symlink() || !metadata.is_dir() {
        return Err(DesktopEntryError::InvalidField);
    }
    let canonical_applications = applications
        .canonicalize()
        .map_err(|_| DesktopEntryError::InvalidField)?;
    if !inside(&root, &canonical_applications) {
        return Err(DesktopEntryError::InvalidField);
    }

    let mut candidates = BinaryHeap::with_capacity(MAX_DESKTOP_FILES_EXAMINED);
    let mut scan_truncated = false;
    let mut directory_entries = 0_usize;
    for item in fs::read_dir(&applications).map_err(|_| DesktopEntryError::InvalidField)? {
        directory_entries = directory_entries.saturating_add(1);
        if directory_entries > MAX_DESKTOP_DIRECTORY_ENTRIES {
            scan_truncated = true;
            break;
        }
        let item = item.map_err(|_| DesktopEntryError::InvalidField)?;
        let file_name = item.file_name();
        let Some(desktop_id) = file_name.to_str() else {
            continue;
        };
        if desktop_id.ends_with(".desktop") {
            let candidate = (desktop_id.to_owned(), item.path());
            if candidates.len() < MAX_DESKTOP_FILES_EXAMINED {
                candidates.push(candidate);
            } else {
                scan_truncated = true;
                if candidates
                    .peek()
                    .is_some_and(|largest| candidate.0 < largest.0)
                {
                    candidates.pop();
                    candidates.push(candidate);
                }
            }
        }
    }
    let mut candidates = candidates.into_vec();
    candidates.sort_unstable_by(|left, right| left.0.cmp(&right.0));

    let mut entries = Vec::with_capacity(candidates.len().min(MAX_DESKTOP_ENTRIES));
    let mut examined = 0_usize;
    let mut rejected = 0_usize;
    let mut total_bytes = 0_usize;
    let mut truncated = scan_truncated;
    for (desktop_id, path) in candidates {
        if examined >= MAX_DESKTOP_FILES_EXAMINED || entries.len() >= MAX_DESKTOP_ENTRIES {
            truncated = true;
            break;
        }
        examined = examined.saturating_add(1);
        let Some(bytes) = read_desktop_file(&path) else {
            rejected = rejected.saturating_add(1);
            continue;
        };
        let Some(next_total) = total_bytes.checked_add(bytes.len()) else {
            truncated = true;
            break;
        };
        if next_total > MAX_DESKTOP_TOTAL_BYTES {
            truncated = true;
            break;
        }
        total_bytes = next_total;
        match parse_desktop_entry(&desktop_id, &bytes, |program| {
            resolve_executable(&root, program)
        }) {
            Ok(Some(entry)) => entries.push(entry),
            Ok(None) => {}
            Err(_) => rejected = rejected.saturating_add(1),
        }
    }
    entries.sort_unstable_by(|left, right| {
        left.name
            .cmp(&right.name)
            .then_with(|| left.desktop_id.cmp(&right.desktop_id))
    });
    Ok(DesktopCatalog {
        entries,
        examined,
        rejected,
        truncated,
    })
}

pub fn resolve_desktop_icon(arch_root: &Path, icon: &str) -> Option<String> {
    let root = canonical_directory(arch_root).ok()?;
    if icon.starts_with('/') {
        return resolve_root_regular_file(&root, Path::new(icon), false);
    }
    if icon.is_empty()
        || icon.len() > 240
        || icon.contains(['/', '\\'])
        || icon.chars().any(char::is_control)
    {
        return None;
    }

    let has_extension = [".png", ".svg", ".xpm"]
        .iter()
        .any(|extension| icon.ends_with(extension));
    let extensions: &[&str] = if has_extension {
        &[""]
    } else {
        &[".png", ".svg", ".xpm"]
    };
    let icon_directories = [
        "512x512", "256x256", "192x192", "128x128", "96x96", "64x64", "48x48", "32x32", "24x24",
        "22x22", "16x16", "scalable", "symbolic",
    ];
    for extension in extensions {
        let file_name = format!("{icon}{extension}");
        for directory in icon_directories {
            let candidate = Path::new("/usr/share/icons/hicolor")
                .join(directory)
                .join("apps")
                .join(&file_name);
            if let Some(resolved) = resolve_root_regular_file(&root, &candidate, false) {
                return Some(resolved);
            }
        }
        let candidate = Path::new("/usr/share/pixmaps").join(file_name);
        if let Some(resolved) = resolve_root_regular_file(&root, &candidate, false) {
            return Some(resolved);
        }
    }
    None
}

pub fn parse_desktop_entry<F>(
    desktop_id: &str,
    bytes: &[u8],
    mut resolve_executable: F,
) -> Result<Option<DesktopEntry>, DesktopEntryError>
where
    F: FnMut(&str) -> Option<String>,
{
    if bytes.is_empty() || bytes.len() > MAX_DESKTOP_ENTRY_BYTES {
        return Err(DesktopEntryError::TooLarge);
    }
    if !valid_desktop_id(desktop_id) {
        return Err(DesktopEntryError::InvalidDesktopId);
    }
    let input = std::str::from_utf8(bytes).map_err(|_| DesktopEntryError::InvalidUtf8)?;
    let mut in_desktop_group = false;
    let mut found_desktop_group = false;
    let mut entry_type = None;
    let mut name = None;
    let mut exec = None;
    let mut try_exec = None;
    let mut icon = None;
    let mut mime_types = None;
    let mut terminal = None;
    let mut hidden = None;
    let mut no_display = None;
    let mut only_show_in = None;
    let mut not_show_in = None;
    let mut lines = 0_usize;

    for raw_line in input.lines() {
        lines = lines.saturating_add(1);
        if lines > 4096 {
            return Err(DesktopEntryError::LimitExceeded);
        }
        let line = raw_line.strip_suffix('\r').unwrap_or(raw_line);
        if line.starts_with('[') {
            if in_desktop_group {
                break;
            }
            in_desktop_group = line == "[Desktop Entry]";
            found_desktop_group |= in_desktop_group;
            continue;
        }
        if !in_desktop_group || line.is_empty() || line.starts_with('#') {
            continue;
        }
        let Some((key, value)) = line.split_once('=') else {
            continue;
        };
        if !valid_key(key) {
            continue;
        }
        match key {
            "Type" => set_once(&mut entry_type, value)?,
            "Name" => set_once(&mut name, value)?,
            "Exec" => set_once(&mut exec, value)?,
            "TryExec" => set_once(&mut try_exec, value)?,
            "Icon" => set_once(&mut icon, value)?,
            "MimeType" => set_once(&mut mime_types, value)?,
            "Terminal" => set_once(&mut terminal, value)?,
            "Hidden" => set_once(&mut hidden, value)?,
            "NoDisplay" => set_once(&mut no_display, value)?,
            "OnlyShowIn" => set_once(&mut only_show_in, value)?,
            "NotShowIn" => set_once(&mut not_show_in, value)?,
            _ => {}
        }
    }

    if !found_desktop_group || entry_type != Some("Application") {
        return Ok(None);
    }
    if parse_boolean(hidden)?.unwrap_or(false)
        || parse_boolean(no_display)?.unwrap_or(false)
        || !desktop_visible(only_show_in, not_show_in)
    {
        return Ok(None);
    }
    let name = unescape_string(name.ok_or(DesktopEntryError::InvalidField)?)?;
    if name.is_empty() || name.len() > MAX_DISPLAY_NAME_BYTES || name.chars().any(char::is_control)
    {
        return Err(DesktopEntryError::InvalidField);
    }
    let (program, arguments) = parse_exec(exec.ok_or(DesktopEntryError::InvalidExec)?)?;
    let executable = resolve_executable(&program).ok_or(DesktopEntryError::InvalidExec)?;
    let try_exec = if let Some(value) = try_exec {
        let (program, arguments) = parse_exec(value)?;
        if !arguments.is_empty() {
            return Err(DesktopEntryError::InvalidExec);
        }
        Some(resolve_executable(&program).ok_or(DesktopEntryError::InvalidExec)?)
    } else {
        None
    };
    let icon = if let Some(value) = icon {
        let value = unescape_string(value)?;
        parse_icon(&value)?
    } else {
        None
    };
    let mime_types = parse_mime_types(mime_types)?;
    Ok(Some(DesktopEntry {
        desktop_id: desktop_id.to_owned(),
        source_package: None,
        executable_package: None,
        integration_topology: 0,
        integration_profiled: false,
        integration_complete: false,
        name,
        executable,
        arguments,
        try_exec,
        icon,
        mime_types,
        terminal: parse_boolean(terminal)?.unwrap_or(false),
    }))
}

pub fn parse_exec(value: &str) -> Result<(String, Vec<ExecArgument>), DesktopEntryError> {
    if value.is_empty() || value.len() > 4096 {
        return Err(DesktopEntryError::InvalidExec);
    }
    let tokens = tokenize_exec(value)?;
    let mut tokens = tokens.into_iter();
    let program = tokens.next().ok_or(DesktopEntryError::InvalidExec)?;
    if program.contains('%')
        || program.contains('=')
        || program.rsplit('/').next() == Some("env")
        || !valid_exec_token(&program)
    {
        return Err(DesktopEntryError::InvalidExec);
    }
    let mut arguments = Vec::with_capacity(tokens.size_hint().0.min(MAX_EXEC_ARGUMENTS));
    for token in tokens {
        if arguments.len() >= MAX_EXEC_ARGUMENTS {
            return Err(DesktopEntryError::LimitExceeded);
        }
        let argument = match token.as_str() {
            "%f" => ExecArgument::SingleFile,
            "%F" => ExecArgument::MultipleFiles,
            "%u" => ExecArgument::SingleUrl,
            "%U" => ExecArgument::MultipleUrls,
            "%i" => ExecArgument::Icon,
            "%c" => ExecArgument::DisplayName,
            "%k" => ExecArgument::DesktopFile,
            _ => {
                let literal = replace_literal_percent(&token)?;
                if !valid_exec_token(&literal) {
                    return Err(DesktopEntryError::InvalidExec);
                }
                ExecArgument::Literal(literal)
            }
        };
        arguments.push(argument);
    }
    Ok((program, arguments))
}

fn tokenize_exec(value: &str) -> Result<Vec<String>, DesktopEntryError> {
    let mut result = Vec::with_capacity(8);
    let mut token = String::with_capacity(32);
    let mut quoted = false;
    let mut escaped = false;
    for character in value.chars() {
        if character == '\0' || character == '\n' || character == '\r' {
            return Err(DesktopEntryError::InvalidExec);
        }
        if escaped {
            token.push(character);
            escaped = false;
        } else if character == '\\' {
            escaped = true;
        } else if character == '"' {
            quoted = !quoted;
        } else if character.is_whitespace() && !quoted {
            if !token.is_empty() {
                if token.len() > MAX_EXEC_ARGUMENT_BYTES || result.len() > MAX_EXEC_ARGUMENTS {
                    return Err(DesktopEntryError::LimitExceeded);
                }
                result.push(std::mem::take(&mut token));
            }
        } else {
            token.push(character);
            if token.len() > MAX_EXEC_ARGUMENT_BYTES {
                return Err(DesktopEntryError::LimitExceeded);
            }
        }
    }
    if quoted || escaped {
        return Err(DesktopEntryError::InvalidExec);
    }
    if !token.is_empty() {
        result.push(token);
    }
    if result.is_empty() || result.len() > MAX_EXEC_ARGUMENTS + 1 {
        return Err(DesktopEntryError::InvalidExec);
    }
    Ok(result)
}

fn replace_literal_percent(value: &str) -> Result<String, DesktopEntryError> {
    if !value.contains('%') {
        return Ok(value.to_owned());
    }
    let mut result = String::with_capacity(value.len());
    let mut characters = value.chars();
    while let Some(character) = characters.next() {
        if character != '%' {
            result.push(character);
            continue;
        }
        if characters.next() != Some('%') {
            return Err(DesktopEntryError::InvalidExec);
        }
        result.push('%');
    }
    Ok(result)
}

fn unescape_string(value: &str) -> Result<String, DesktopEntryError> {
    let mut result = String::with_capacity(value.len());
    let mut characters = value.chars();
    while let Some(character) = characters.next() {
        if character != '\\' {
            result.push(character);
            continue;
        }
        result.push(match characters.next() {
            Some('s') => ' ',
            Some('n') => '\n',
            Some('t') => '\t',
            Some('r') => '\r',
            Some('\\') => '\\',
            _ => return Err(DesktopEntryError::InvalidField),
        });
    }
    Ok(result)
}

fn parse_icon(value: &str) -> Result<Option<String>, DesktopEntryError> {
    if value.is_empty() {
        return Ok(None);
    }
    let relative = value.strip_prefix('/').unwrap_or(value);
    if value.len() > 240
        || relative.is_empty()
        || relative
            .split('/')
            .any(|part| part.is_empty() || part == "." || part == "..")
        || value.chars().any(char::is_control)
    {
        return Err(DesktopEntryError::InvalidField);
    }
    Ok(Some(value.to_owned()))
}

fn parse_mime_types(value: Option<&str>) -> Result<Vec<String>, DesktopEntryError> {
    let Some(value) = value else {
        return Ok(Vec::new());
    };
    let mut result = Vec::with_capacity(4);
    for candidate in value.split(';').filter(|candidate| !candidate.is_empty()) {
        if result.len() >= MAX_MIME_TYPES || !valid_mime_type(candidate) {
            return Err(DesktopEntryError::InvalidField);
        }
        if !result.iter().any(|known| known == candidate) {
            result.push(candidate.to_owned());
        }
    }
    Ok(result)
}

fn valid_mime_type(value: &str) -> bool {
    let Some((major, minor)) = value.split_once('/') else {
        return false;
    };
    !major.is_empty()
        && !minor.is_empty()
        && major.len() <= 64
        && minor.len() <= 64
        && major
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"!#$&^_.+-".contains(&byte))
        && minor
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"!#$&^_.+*-".contains(&byte))
}

fn desktop_visible(only_show_in: Option<&str>, not_show_in: Option<&str>) -> bool {
    let includes_archphene = |value: &str| value.split(';').any(|desktop| desktop == "Archphene");
    only_show_in.is_none_or(includes_archphene)
        && not_show_in.is_none_or(|value| !includes_archphene(value))
}

fn parse_boolean(value: Option<&str>) -> Result<Option<bool>, DesktopEntryError> {
    match value {
        None => Ok(None),
        Some("true" | "1") => Ok(Some(true)),
        Some("false" | "0") => Ok(Some(false)),
        Some(_) => Err(DesktopEntryError::InvalidField),
    }
}

fn set_once<'a>(
    destination: &mut Option<&'a str>,
    value: &'a str,
) -> Result<(), DesktopEntryError> {
    if destination.replace(value).is_some() {
        Err(DesktopEntryError::DuplicateField)
    } else {
        Ok(())
    }
}

fn valid_desktop_id(value: &str) -> bool {
    value.ends_with(".desktop")
        && value.len() <= MAX_DESKTOP_ID_BYTES
        && !value.starts_with('.')
        && !value.contains('/')
        && !value.chars().any(char::is_control)
}

fn valid_key(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
}

fn valid_exec_token(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= MAX_EXEC_ARGUMENT_BYTES
        && !value
            .chars()
            .any(|character| character.is_control() || character == '\0')
}

fn canonical_directory(path: &Path) -> Result<PathBuf, DesktopEntryError> {
    if !path.is_absolute() {
        return Err(DesktopEntryError::InvalidField);
    }
    let metadata = fs::symlink_metadata(path).map_err(|_| DesktopEntryError::InvalidField)?;
    if metadata.file_type().is_symlink() || !metadata.is_dir() {
        return Err(DesktopEntryError::InvalidField);
    }
    path.canonicalize()
        .map_err(|_| DesktopEntryError::InvalidField)
}

fn read_desktop_file(path: &Path) -> Option<Vec<u8>> {
    let metadata = fs::symlink_metadata(path).ok()?;
    if metadata.file_type().is_symlink() || !metadata.is_file() {
        return None;
    }
    let size = usize::try_from(metadata.len()).ok()?;
    if size == 0 || size > MAX_DESKTOP_ENTRY_BYTES {
        return None;
    }
    let file = OpenOptions::new()
        .read(true)
        .custom_flags(O_NOFOLLOW | O_CLOEXEC)
        .open(path)
        .ok()?;
    let opened = file.metadata().ok()?;
    if !opened.is_file() || opened.len() != metadata.len() {
        return None;
    }
    let mut bytes = Vec::with_capacity(size);
    file.take(u64::try_from(MAX_DESKTOP_ENTRY_BYTES + 1).expect("desktop limit"))
        .read_to_end(&mut bytes)
        .ok()?;
    if bytes.len() != size || bytes.len() > MAX_DESKTOP_ENTRY_BYTES {
        return None;
    }
    Some(bytes)
}

fn resolve_executable(root: &Path, program: &str) -> Option<String> {
    let mut candidates = Vec::with_capacity(3);
    if let Some(relative) = program.strip_prefix('/') {
        if relative.is_empty() {
            return None;
        }
        candidates.push(root.join(relative));
    } else {
        if program.contains('/') || program.is_empty() {
            return None;
        }
        for directory in ["/usr/local/bin", "/usr/bin", "/bin"] {
            candidates.push(root.join(directory.trim_start_matches('/')).join(program));
        }
    }
    for candidate in candidates {
        if let Some(resolved) = resolve_root_regular_file(root, &candidate, true) {
            return Some(resolved);
        }
    }
    None
}

pub(crate) fn resolve_root_regular_file(
    root: &Path,
    path: &Path,
    executable: bool,
) -> Option<String> {
    let relative = path
        .strip_prefix(root)
        .ok()
        .or_else(|| path.strip_prefix("/").ok())?;
    let mut remaining = normalized_components(relative)?;
    let mut resolved = PathBuf::new();
    let mut followed = 0_usize;
    while let Some(component) = remaining.pop_front() {
        let candidate = root.join(&resolved).join(&component);
        let metadata = fs::symlink_metadata(&candidate).ok()?;
        if metadata.file_type().is_symlink() {
            followed = followed.checked_add(1)?;
            if followed > MAX_ROOT_SYMLINKS {
                return None;
            }
            let target = fs::read_link(&candidate).ok()?;
            let mut replacement = if target.is_absolute() {
                physical_target_under_root(root, &target)?.unwrap_or_else(|| {
                    target
                        .strip_prefix("/")
                        .expect("absolute target")
                        .to_path_buf()
                })
            } else {
                resolved.join(target)
            };
            replacement.extend(remaining.drain(..));
            remaining = normalized_components(&replacement)?;
            resolved.clear();
            continue;
        }
        if !remaining.is_empty() && !metadata.is_dir() {
            return None;
        }
        resolved.push(component);
    }
    let metadata = fs::symlink_metadata(root.join(&resolved)).ok()?;
    if !metadata.is_file()
        || metadata.file_type().is_symlink()
        || metadata.permissions().mode() & 0o022 != 0
        || (executable && metadata.permissions().mode() & 0o111 == 0)
    {
        return None;
    }
    Some(format!("/{}", resolved.to_str()?))
}

fn physical_target_under_root(root: &Path, target: &Path) -> Option<Option<PathBuf>> {
    let root_metadata = fs::metadata(root).ok()?;
    let mut ancestor = Some(target);
    while let Some(path) = ancestor {
        match fs::metadata(path) {
            Ok(metadata)
                if metadata.dev() == root_metadata.dev()
                    && metadata.ino() == root_metadata.ino() =>
            {
                let relative = target.strip_prefix(path).ok()?;
                return Some(Some(relative.to_path_buf()));
            }
            Ok(_) => {}
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(_) => return None,
        }
        ancestor = path.parent();
    }
    Some(None)
}

fn normalized_components(path: &Path) -> Option<VecDeque<OsString>> {
    let mut normalized = Vec::<OsString>::with_capacity(path.components().count());
    for component in path.components() {
        match component {
            Component::Normal(value) => normalized.push(value.to_os_string()),
            Component::CurDir => {}
            Component::ParentDir => {
                normalized.pop()?;
            }
            Component::RootDir | Component::Prefix(_) => return None,
        }
    }
    if normalized.is_empty() {
        return None;
    }
    Some(normalized.into())
}

fn inside(root: &Path, candidate: &Path) -> bool {
    candidate != root && candidate.starts_with(root)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{MAX_TOOL_OUTPUT_BYTES, PackageRuntimeError};
    use std::sync::atomic::{AtomicU64, Ordering};

    static TEST_ID: AtomicU64 = AtomicU64::new(1);

    struct TestRoot {
        path: PathBuf,
    }

    impl TestRoot {
        fn new() -> Self {
            let id = TEST_ID.fetch_add(1, Ordering::Relaxed);
            let path = std::env::temp_dir().join(format!(
                "archphene-desktop-test-{}-{id}",
                std::process::id(),
            ));
            fs::create_dir_all(path.join(APPLICATION_DIRECTORY)).expect("application directory");
            fs::create_dir_all(path.join("usr/bin")).expect("binary directory");
            Self { path }
        }

        fn executable(&self, name: &str) {
            let path = self.path.join("usr/bin").join(name);
            fs::write(&path, b"\x7fELF fixture").expect("executable");
            fs::set_permissions(&path, fs::Permissions::from_mode(0o755)).expect("executable mode");
        }

        fn regular_file(&self, relative: &str, contents: &[u8]) {
            let path = self.path.join(relative);
            fs::create_dir_all(path.parent().expect("file parent")).expect("file directory");
            fs::write(path, contents).expect("regular file");
        }

        fn desktop(&self, name: &str, contents: &str) {
            fs::write(self.path.join(APPLICATION_DIRECTORY).join(name), contents)
                .expect("desktop entry");
        }
    }

    impl Drop for TestRoot {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.path);
        }
    }

    fn resolve(program: &str) -> Option<String> {
        match program {
            "kate" | "/usr/bin/kate" => Some("/usr/bin/kate".to_owned()),
            "env" => Some("/usr/bin/env".to_owned()),
            _ => None,
        }
    }

    #[test]
    fn parses_graphical_entry_without_treating_exec_as_a_shell() {
        let entry = parse_desktop_entry(
            "org.kde.kate.desktop",
            b"[Desktop Entry]\n\
Type=Application\n\
Name=Kate\n\
Exec=/usr/bin/kate --startanon %U\n\
TryExec=kate\n\
Icon=kate\n\
MimeType=text/plain;application/json;\n\
Terminal=false\n",
            resolve,
        )
        .expect("valid desktop entry")
        .expect("visible application");
        assert_eq!(entry.desktop_id, "org.kde.kate.desktop");
        assert_eq!(entry.name, "Kate");
        assert_eq!(entry.executable, "/usr/bin/kate");
        assert_eq!(
            entry.arguments,
            vec![
                ExecArgument::Literal("--startanon".to_owned()),
                ExecArgument::MultipleUrls,
            ]
        );
        assert_eq!(entry.try_exec.as_deref(), Some("/usr/bin/kate"));
        assert_eq!(entry.icon.as_deref(), Some("kate"));
        assert_eq!(entry.mime_types, ["text/plain", "application/json"]);
        assert!(!entry.terminal);
    }

    #[test]
    fn preserves_quoted_arguments_and_literal_percent_only() {
        let (program, arguments) =
            parse_exec(r#"kate "--new window" --caption=100%% %F"#).expect("safe Exec");
        assert_eq!(program, "kate");
        assert_eq!(
            arguments,
            vec![
                ExecArgument::Literal("--new window".to_owned()),
                ExecArgument::Literal("--caption=100%".to_owned()),
                ExecArgument::MultipleFiles,
            ]
        );
        assert!(parse_exec("env FOO=bar kate").is_err());
        assert!(parse_exec("kate --output=%F").is_err());
        assert!(parse_exec(r#"kate "unterminated"#).is_err());
        assert!(parse_exec("kate trailing\\").is_err());
    }

    #[test]
    fn accepts_root_relative_and_escaped_icon_paths_without_host_resolution() {
        let entry = parse_desktop_entry(
            "icon.desktop",
            b"[Desktop Entry]\n\
Type=Application\n\
Name=Icon path\n\
Exec=kate\n\
Icon=/usr/share/pixmaps/My\\sEditor.png\n",
            resolve,
        )
        .expect("valid icon path")
        .expect("visible entry");
        assert_eq!(
            entry.icon.as_deref(),
            Some("/usr/share/pixmaps/My Editor.png")
        );
        assert!(parse_icon("/../../system/icon.png").is_err());
        assert!(parse_icon("relative//icon").is_err());
    }

    #[test]
    fn filters_non_launchable_and_desktop_specific_entries() {
        for body in [
            "[Desktop Entry]\nType=Link\nName=Docs\nURL=https://example.com\n",
            "[Desktop Entry]\nType=Application\nName=Hidden\nExec=kate\nHidden=true\n",
            "[Desktop Entry]\nType=Application\nName=Hidden\nExec=kate\nNoDisplay=1\n",
            "[Desktop Entry]\nType=Application\nName=GNOME only\nExec=kate\nOnlyShowIn=GNOME;\n",
            "[Desktop Entry]\nType=Application\nName=Not here\nExec=kate\nNotShowIn=Archphene;\n",
        ] {
            assert_eq!(
                parse_desktop_entry("fixture.desktop", body.as_bytes(), resolve)
                    .expect("valid filtered entry"),
                None
            );
        }
        assert!(
            parse_desktop_entry(
                "fixture.desktop",
                b"[Desktop Entry]\nType=Application\nName=Here\nExec=kate\nOnlyShowIn=Archphene;\n",
                resolve,
            )
            .expect("Archphene entry")
            .is_some()
        );
    }

    #[test]
    fn rejects_unsafe_duplicates_bounds_and_unavailable_programs() {
        assert!(matches!(
            parse_desktop_entry(
                "../escape.desktop",
                b"[Desktop Entry]\nType=Application\nName=Bad\nExec=kate\n",
                resolve,
            ),
            Err(DesktopEntryError::InvalidDesktopId)
        ));
        assert!(matches!(
            parse_desktop_entry(
                "duplicate.desktop",
                b"[Desktop Entry]\nType=Application\nName=One\nName=Two\nExec=kate\n",
                resolve,
            ),
            Err(DesktopEntryError::DuplicateField)
        ));
        assert!(matches!(
            parse_desktop_entry(
                "missing.desktop",
                b"[Desktop Entry]\nType=Application\nName=Missing\nExec=missing\n",
                resolve,
            ),
            Err(DesktopEntryError::InvalidExec)
        ));
        let oversized = vec![b'x'; MAX_DESKTOP_ENTRY_BYTES + 1];
        assert!(matches!(
            parse_desktop_entry("large.desktop", &oversized, resolve),
            Err(DesktopEntryError::TooLarge)
        ));
    }

    #[test]
    fn discovers_sorted_shared_root_entries_with_bounded_rejections() {
        let root = TestRoot::new();
        root.executable("kate");
        root.executable("foot");
        root.desktop(
            "org.kde.kate.desktop",
            "[Desktop Entry]\nType=Application\nName=Kate\nExec=kate %U\nIcon=kate\n",
        );
        root.desktop(
            "foot.desktop",
            "[Desktop Entry]\nType=Application\nName=Foot\nExec=foot\nTerminal=true\n",
        );
        root.desktop(
            "hidden.desktop",
            "[Desktop Entry]\nType=Application\nName=Hidden\nExec=kate\nHidden=true\n",
        );
        root.desktop(
            "invalid.desktop",
            "[Desktop Entry]\nType=Application\nName=Invalid\nExec=outside\n",
        );
        std::os::unix::fs::symlink(
            "/etc/passwd",
            root.path.join(APPLICATION_DIRECTORY).join("escape.desktop"),
        )
        .expect("desktop symlink");

        let catalog = discover_desktop_entries(&root.path).expect("desktop catalog");
        assert_eq!(catalog.entries.len(), 2);
        assert_eq!(catalog.entries[0].name, "Foot");
        assert_eq!(catalog.entries[0].executable, "/usr/bin/foot");
        assert!(catalog.entries[0].terminal);
        assert_eq!(catalog.entries[1].name, "Kate");
        assert_eq!(catalog.entries[1].executable, "/usr/bin/kate");
        assert_eq!(catalog.examined, 5);
        assert_eq!(catalog.rejected, 2);
        assert!(!catalog.truncated);
    }

    #[test]
    fn executable_resolution_cannot_escape_the_shared_root() {
        let root = TestRoot::new();
        std::os::unix::fs::symlink("/bin/sh", root.path.join("usr/bin/escape"))
            .expect("escaping executable");
        root.desktop(
            "escape.desktop",
            "[Desktop Entry]\nType=Application\nName=Escape\nExec=escape\n",
        );
        let catalog = discover_desktop_entries(&root.path).expect("desktop catalog");
        assert!(catalog.entries.is_empty());
        assert_eq!(catalog.rejected, 1);
    }

    #[test]
    fn executable_resolution_publishes_a_normalized_root_path() {
        let root = TestRoot::new();
        root.executable("real-editor");
        fs::create_dir_all(root.path.join("usr/local/bin")).expect("local binary directory");
        std::os::unix::fs::symlink(
            "../../bin/real-editor",
            root.path.join("usr/local/bin/editor"),
        )
        .expect("contained executable link");
        root.desktop(
            "editor.desktop",
            "[Desktop Entry]\nType=Application\nName=Editor\nExec=editor\n",
        );
        let catalog = discover_desktop_entries(&root.path).expect("desktop catalog");
        assert_eq!(catalog.entries.len(), 1);
        assert_eq!(catalog.entries[0].executable, "/usr/bin/real-editor");
    }

    #[test]
    fn executable_resolution_follows_root_absolute_package_links() {
        let root = TestRoot::new();
        root.regular_file("usr/lib/editor/editor", b"\x7fELF fixture");
        fs::set_permissions(
            root.path.join("usr/lib/editor/editor"),
            fs::Permissions::from_mode(0o755),
        )
        .expect("executable mode");
        std::os::unix::fs::symlink("/usr/lib/editor/editor", root.path.join("usr/bin/editor"))
            .expect("root-absolute executable link");
        root.desktop(
            "editor.desktop",
            "[Desktop Entry]\nType=Application\nName=Editor\nExec=editor\n",
        );
        let catalog = discover_desktop_entries(&root.path).expect("desktop catalog");
        assert_eq!(catalog.entries.len(), 1);
        assert_eq!(catalog.entries[0].executable, "/usr/lib/editor/editor");

        std::os::unix::fs::symlink(
            root.path.join("usr/lib/editor/editor"),
            root.path.join("usr/bin/physical-editor"),
        )
        .expect("physical-root executable link");
        root.desktop(
            "physical-editor.desktop",
            "[Desktop Entry]\nType=Application\nName=Physical Editor\nExec=physical-editor\n",
        );
        let catalog = discover_desktop_entries(&root.path).expect("desktop catalog");
        assert_eq!(
            catalog
                .entries
                .iter()
                .find(|entry| entry.name == "Physical Editor")
                .expect("physical editor")
                .executable,
            "/usr/lib/editor/editor"
        );
    }

    #[test]
    fn icon_resolution_prefers_hicolor_png_and_normalizes_package_links() {
        let root = TestRoot::new();
        root.regular_file("usr/lib/editor/resources/editor.png", b"png fixture");
        root.regular_file(
            "usr/share/icons/hicolor/scalable/apps/editor.svg",
            b"svg fixture",
        );
        fs::create_dir_all(root.path.join("usr/share/icons/hicolor/256x256/apps"))
            .expect("icon directory");
        std::os::unix::fs::symlink(
            "/usr/lib/editor/resources/editor.png",
            root.path
                .join("usr/share/icons/hicolor/256x256/apps/editor.png"),
        )
        .expect("root-absolute icon link");

        assert_eq!(
            resolve_desktop_icon(&root.path, "editor").as_deref(),
            Some("/usr/lib/editor/resources/editor.png"),
        );
        assert_eq!(
            resolve_desktop_icon(
                &root.path,
                "/usr/share/icons/hicolor/256x256/apps/editor.png",
            )
            .as_deref(),
            Some("/usr/lib/editor/resources/editor.png"),
        );
    }

    #[test]
    fn icon_resolution_rejects_escapes_loops_and_writable_files() {
        let root = TestRoot::new();
        fs::create_dir_all(root.path.join("usr/share/pixmaps")).expect("pixmap directory");
        std::os::unix::fs::symlink(
            "../../../../../../system/icon.png",
            root.path.join("usr/share/pixmaps/escape.png"),
        )
        .expect("escaping icon link");
        std::os::unix::fs::symlink(
            "/usr/share/pixmaps/loop-b.png",
            root.path.join("usr/share/pixmaps/loop-a.png"),
        )
        .expect("first icon loop");
        std::os::unix::fs::symlink(
            "/usr/share/pixmaps/loop-a.png",
            root.path.join("usr/share/pixmaps/loop-b.png"),
        )
        .expect("second icon loop");
        root.regular_file("usr/share/pixmaps/writable.png", b"writable");
        fs::set_permissions(
            root.path.join("usr/share/pixmaps/writable.png"),
            fs::Permissions::from_mode(0o666),
        )
        .expect("writable icon mode");

        assert_eq!(resolve_desktop_icon(&root.path, "escape"), None);
        assert_eq!(resolve_desktop_icon(&root.path, "loop-a"), None);
        assert_eq!(resolve_desktop_icon(&root.path, "writable"), None);
        assert_eq!(resolve_desktop_icon(&root.path, "../escape"), None);
    }

    #[test]
    fn application_directory_cannot_escape_the_shared_root() {
        let root = TestRoot::new();
        fs::remove_dir(root.path.join(APPLICATION_DIRECTORY)).expect("remove fixture directory");
        std::os::unix::fs::symlink(
            "/usr/share/applications",
            root.path.join(APPLICATION_DIRECTORY),
        )
        .expect("escaping application directory");
        assert!(matches!(
            discover_desktop_entries(&root.path),
            Err(DesktopEntryError::InvalidField)
        ));
    }

    #[test]
    fn discovery_bounds_candidate_count_and_keeps_lexicographic_prefix() {
        let root = TestRoot::new();
        root.executable("kate");
        for index in (0..=MAX_DESKTOP_FILES_EXAMINED).rev() {
            root.desktop(
                &format!("{index:04}.desktop"),
                if index == 0 {
                    "[Desktop Entry]\nType=Application\nName=First\nExec=kate\n"
                } else {
                    "[Desktop Entry]\nType=Application\nName=Hidden\nExec=kate\nHidden=true\n"
                },
            );
        }
        let catalog = discover_desktop_entries(&root.path).expect("bounded desktop catalog");
        assert_eq!(catalog.examined, MAX_DESKTOP_FILES_EXAMINED);
        assert_eq!(catalog.entries.len(), 1);
        assert_eq!(catalog.entries[0].desktop_id, "0000.desktop");
        assert!(catalog.truncated);
    }

    #[test]
    fn discovery_bounds_total_directory_enumeration() {
        let root = TestRoot::new();
        for index in 0..=MAX_DESKTOP_DIRECTORY_ENTRIES {
            fs::write(
                root.path
                    .join(APPLICATION_DIRECTORY)
                    .join(format!("fixture-{index:04}.txt")),
                b"not a desktop entry",
            )
            .expect("non-desktop fixture");
        }
        let catalog = discover_desktop_entries(&root.path).expect("bounded directory scan");
        assert!(catalog.entries.is_empty());
        assert_eq!(catalog.examined, 0);
        assert!(catalog.truncated);
    }

    #[test]
    fn catalog_page_preserves_launch_arguments_and_scan_diagnostics() {
        let entry = parse_desktop_entry(
            "org.kde.kate.desktop",
            b"[Desktop Entry]\n\
Type=Application\n\
Name=Kate\n\
Exec=kate --startanon %U\n\
TryExec=kate\n\
Icon=kate\n\
MimeType=text/plain;\n",
            resolve,
        )
        .expect("valid entry")
        .expect("visible entry");
        let catalog = DesktopCatalog {
            entries: vec![entry],
            examined: 1,
            rejected: 0,
            truncated: false,
        };
        assert_eq!(
            catalog.page(0).expect("catalog page").as_bytes(),
            b"D3\t1\t1\t1\t0\t0\n\
org.kde.kate.desktop\tKate\t/usr/bin/kate\t0\tkate\t/usr/bin/kate\tL:--startanon\x1fU\ttext/plain;\t\t\n",
        );
        assert_eq!(
            catalog.page(1).expect("terminal page").as_bytes(),
            b"D3\t1\t1\t1\t0\t0\n",
        );
        assert!(matches!(
            catalog.page(2),
            Err(PackageRuntimeError::InvalidQuery)
        ));
    }

    #[test]
    fn catalog_pages_make_progress_with_large_bounded_entries() {
        let entries = (0..MAX_DESKTOP_ENTRIES)
            .map(|index| DesktopEntry {
                desktop_id: format!("fixture-{index:03}.desktop"),
                source_package: None,
                executable_package: None,
                integration_topology: 0,
                integration_profiled: false,
                integration_complete: false,
                name: format!("Fixture {index:03}"),
                executable: "/usr/bin/fixture".to_owned(),
                arguments: vec![ExecArgument::Literal("x".repeat(500))],
                try_exec: None,
                icon: None,
                mime_types: Vec::new(),
                terminal: false,
            })
            .collect();
        let catalog = DesktopCatalog {
            entries,
            examined: MAX_DESKTOP_ENTRIES,
            rejected: 0,
            truncated: false,
        };
        let mut offset = 0_usize;
        let mut pages = 0_usize;
        while offset < MAX_DESKTOP_ENTRIES {
            let page = catalog.page(offset).expect("bounded catalog page");
            assert!(page.as_bytes().len() <= MAX_TOOL_OUTPUT_BYTES);
            let header_end = page
                .as_bytes()
                .iter()
                .position(|byte| *byte == b'\n')
                .expect("page header");
            let header = std::str::from_utf8(&page.as_bytes()[..header_end]).expect("UTF-8 header");
            let next = header
                .split('\t')
                .nth(1)
                .expect("next offset")
                .parse::<usize>()
                .expect("numeric next offset");
            assert!(next > offset);
            assert!(next <= MAX_DESKTOP_ENTRIES);
            offset = next;
            pages += 1;
        }
        assert!(pages > 1);
    }
}
