#![deny(unsafe_code)]
#![deny(unsafe_op_in_unsafe_fn)]

#[cfg(any(target_os = "android", test))]
use archphene_runtime::RuntimeHost;

#[cfg(any(target_os = "android", test))]
const MAX_RUNTIME_HANDLES: usize = 4;

#[cfg(any(target_os = "android", test))]
#[derive(Default)]
struct RuntimeSlot {
    generation: u32,
    runtime: Option<RuntimeHost>,
}

#[cfg(any(target_os = "android", test))]
struct RuntimeRegistry {
    slots: [RuntimeSlot; MAX_RUNTIME_HANDLES],
}

#[cfg(any(target_os = "android", test))]
impl RuntimeRegistry {
    fn new() -> Self {
        Self {
            slots: std::array::from_fn(|_| RuntimeSlot::default()),
        }
    }

    fn create(&mut self) -> Option<u64> {
        let (index, slot) = self
            .slots
            .iter_mut()
            .enumerate()
            .find(|(_, slot)| slot.runtime.is_none())?;
        slot.generation = slot.generation.wrapping_add(1).max(1);
        let handle = encode_handle(index, slot.generation)?;
        slot.runtime = Some(RuntimeHost::new(handle));
        Some(handle)
    }

    fn runtime_mut(&mut self, handle: u64) -> Option<&mut RuntimeHost> {
        let (index, generation) = decode_handle(handle)?;
        let slot = self.slots.get_mut(index)?;
        if slot.generation != generation {
            return None;
        }
        slot.runtime.as_mut()
    }

    fn destroy(&mut self, handle: u64) -> bool {
        let Some((index, generation)) = decode_handle(handle) else {
            return false;
        };
        let Some(slot) = self.slots.get_mut(index) else {
            return false;
        };
        if slot.generation != generation {
            return false;
        }
        slot.runtime.take().is_some()
    }
}

#[cfg(any(target_os = "android", test))]
fn encode_handle(index: usize, generation: u32) -> Option<u64> {
    let encoded_index = u32::try_from(index).ok()?.checked_add(1)?;
    Some((u64::from(generation) << 32) | u64::from(encoded_index))
}

#[cfg(any(target_os = "android", test))]
fn decode_handle(handle: u64) -> Option<(usize, u32)> {
    let encoded_index = u32::try_from(handle & u64::from(u32::MAX)).ok()?;
    let generation = u32::try_from(handle >> 32).ok()?;
    if encoded_index == 0 || generation == 0 {
        return None;
    }
    Some((usize::try_from(encoded_index - 1).ok()?, generation))
}

#[cfg(target_os = "android")]
mod android {
    #![allow(unsafe_code)]

    use std::fs::File;
    use std::os::fd::{BorrowedFd, IntoRawFd};
    use std::path::Path;
    use std::slice;
    use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
    use std::sync::{Mutex, OnceLock};
    use std::time::Duration;
    use std::{io::Write as _, str};

    use archphene_core::{Lifecycle, PROTOCOL_VERSION, RuntimeError, SNAPSHOT_SIZE};
    use archphene_jobs::{JobError, JobOperation, JobState};
    use archphene_launcher::{LauncherReviewDecision, MAX_LAUNCHER_DESCRIPTORS};
    use archphene_packages::{
        MAX_MANIFEST_BYTES, MAX_PACKAGE_RESOLUTION_BYTES, MAX_TOOL_OUTPUT_BYTES,
        MAX_VERIFIED_PACKAGE_CLOSURE_BYTES, PackageCompatibilityCancellation, PackageResolution,
        PackageRuntimeError, Repository, RepositoryArchitecture, ToolOutput, VerifiedAurArchive,
        VerifiedPackageClosure,
        aur::{
            MAX_AUR_REVIEW_BYTES, MAX_AUR_RPC_BYTES, MAX_AUR_SNAPSHOT_BYTES, MAX_AUR_SOURCE_BYTES,
            aur_snapshot_path, review_aur_snapshot,
        },
    };
    use archphene_process::{
        GuiAppearance, MAX_COMMAND_ARGUMENTS, MAX_COMMAND_OUTPUT_BYTES, MAX_COMMAND_REQUEST_BYTES,
        MAX_PTY_TRANSFER_BYTES, MAX_TERMINAL_CLIPBOARD_BYTES, MAX_TERMINAL_DAMAGE_BYTES,
        MAX_TERMINAL_SELECTION_BYTES, ProcessError,
    };
    use archphene_storage::{
        MirrorCancellation, OpenMode, StorageError, SyncAction, SyncEntryKind, SyncFingerprint,
        fingerprint_file_from_fd,
    };
    use jni::JNIEnv;
    use jni::objects::{JByteBuffer, JClass, JIntArray, JString};
    use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean, jint, jlong};

    use super::{MAX_RUNTIME_HANDLES, RuntimeRegistry};

    const ERROR_INVALID_HANDLE: jint = -1;
    const ERROR_INVALID_ARGUMENT: jint = -2;
    const ERROR_INVALID_STATE: jint = -3;
    const ERROR_QUEUE_FULL: jint = -4;
    const ERROR_INTERNAL: jint = -5;
    const ERROR_BOOTSTRAP: jint = -6;
    const ERROR_PACKAGE_RUNTIME: jint = -7;
    const ERROR_PROCESS: jint = -8;
    const ERROR_STORAGE: jint = -9;
    const ERROR_LAUNCHER: jint = -10;
    const MAX_ANDROID_DNS_REQUEST_BYTES: usize = 512;
    const MAX_LAUNCHER_REVIEW_REQUEST_BYTES: usize = 32 * 1024;
    const MAX_PACKAGE_CACHE_SELECTION_BYTES: usize = 32 * 1024;
    const MAX_STORAGE_REQUEST_BYTES: usize = 8 * 1024;
    const BUILT_PACKAGE_REPORT_BYTES: usize = 64;
    const BUILT_PACKAGE_REPORT_MAGIC: &[u8; 8] = b"ABMV0001";
    const AUR_INSTALL_FILENAME_MAGIC: &[u8; 8] = b"AIFN0001";
    const MAX_AUR_INSTALL_FILENAME_BYTES: usize = 64 * 1024;
    const PTY_EVENT_READABLE: jint = 1;
    const PTY_EVENT_WRITABLE: jint = 1 << 1;
    const PTY_EVENT_HANGUP: jint = 1 << 2;
    const PTY_EVENT_WOKEN: jint = 1 << 3;

    static REGISTRY: OnceLock<Mutex<RuntimeRegistry>> = OnceLock::new();
    static PROJECT_SYNC_CANCELLATIONS: OnceLock<
        Mutex<[Option<(u64, MirrorCancellation)>; MAX_RUNTIME_HANDLES]>,
    > = OnceLock::new();
    static PACKAGE_COMPATIBILITY_CANCELLATIONS: OnceLock<
        Mutex<[Option<(u64, PackageCompatibilityCancellation)>; MAX_RUNTIME_HANDLES]>,
    > = OnceLock::new();
    static DOCUMENT_EXPORT_ACTIVE: AtomicBool = AtomicBool::new(false);
    static DOCUMENT_EXPORT_CANCELLED: AtomicBool = AtomicBool::new(false);
    static DOCUMENT_EXPORT_BYTES: AtomicU64 = AtomicU64::new(0);
    static DOCUMENT_IMPORT_ACTIVE: AtomicBool = AtomicBool::new(false);
    static DOCUMENT_IMPORT_CANCELLED: AtomicBool = AtomicBool::new(false);
    static DOCUMENT_IMPORT_BYTES: AtomicU64 = AtomicU64::new(0);
    static PACKAGE_COMPATIBILITY_TEST_HOLD_MILLIS: AtomicU64 = AtomicU64::new(0);

    struct DocumentExportGuard;
    struct DocumentImportGuard;
    struct PackageCompatibilityCancellationGuard(u64);

    impl Drop for DocumentExportGuard {
        fn drop(&mut self) {
            DOCUMENT_EXPORT_ACTIVE.store(false, Ordering::Release);
        }
    }

    impl Drop for DocumentImportGuard {
        fn drop(&mut self) {
            DOCUMENT_IMPORT_ACTIVE.store(false, Ordering::Release);
        }
    }

    impl Drop for PackageCompatibilityCancellationGuard {
        fn drop(&mut self) {
            remove_package_compatibility_cancellation(self.0);
        }
    }

    fn registry() -> &'static Mutex<RuntimeRegistry> {
        REGISTRY.get_or_init(|| Mutex::new(RuntimeRegistry::new()))
    }

    fn project_sync_cancellations()
    -> &'static Mutex<[Option<(u64, MirrorCancellation)>; MAX_RUNTIME_HANDLES]> {
        PROJECT_SYNC_CANCELLATIONS.get_or_init(|| Mutex::new(std::array::from_fn(|_| None)))
    }

    fn package_compatibility_cancellations()
    -> &'static Mutex<[Option<(u64, PackageCompatibilityCancellation)>; MAX_RUNTIME_HANDLES]> {
        PACKAGE_COMPATIBILITY_CANCELLATIONS
            .get_or_init(|| Mutex::new(std::array::from_fn(|_| None)))
    }

    fn set_package_compatibility_cancellation(
        handle: u64,
        cancellation: PackageCompatibilityCancellation,
    ) -> Result<(), ()> {
        let (index, _) = super::decode_handle(handle).ok_or(())?;
        let mut slots = package_compatibility_cancellations()
            .lock()
            .map_err(|_| ())?;
        let slot = slots.get_mut(index).ok_or(())?;
        if slot.is_some() {
            return Err(());
        }
        *slot = Some((handle, cancellation));
        Ok(())
    }

    fn package_compatibility_cancellation(handle: u64) -> Option<PackageCompatibilityCancellation> {
        let (index, _) = super::decode_handle(handle)?;
        let slots = package_compatibility_cancellations().lock().ok()?;
        slots
            .get(index)
            .and_then(Option::as_ref)
            .filter(|(active, _)| *active == handle)
            .map(|(_, cancellation)| cancellation.clone())
    }

    fn remove_package_compatibility_cancellation(handle: u64) -> bool {
        let Some((index, _)) = super::decode_handle(handle) else {
            return false;
        };
        let Ok(mut slots) = package_compatibility_cancellations().lock() else {
            return false;
        };
        let Some(slot) = slots.get_mut(index) else {
            return false;
        };
        if slot.as_ref().is_some_and(|(active, _)| *active == handle) {
            *slot = None;
            true
        } else {
            false
        }
    }

    fn cancel_package_compatibility_without_runtime_lock(handle: u64) -> bool {
        let Some((index, _)) = super::decode_handle(handle) else {
            return false;
        };
        let Ok(slots) = package_compatibility_cancellations().lock() else {
            return false;
        };
        slots
            .get(index)
            .and_then(Option::as_ref)
            .filter(|(active, _)| *active == handle)
            .is_some_and(|(_, cancellation)| {
                cancellation.cancel();
                true
            })
    }

    fn hold_debug_package_compatibility_review(
        cancellation: &PackageCompatibilityCancellation,
    ) -> Result<(), PackageRuntimeError> {
        let mut remaining = PACKAGE_COMPATIBILITY_TEST_HOLD_MILLIS.swap(0, Ordering::AcqRel);
        while remaining != 0 {
            if cancellation.is_cancelled() {
                return Err(PackageRuntimeError::Cancelled);
            }
            let slice = remaining.min(10);
            std::thread::sleep(Duration::from_millis(slice));
            remaining -= slice;
        }
        if cancellation.is_cancelled() {
            Err(PackageRuntimeError::Cancelled)
        } else {
            Ok(())
        }
    }

    fn set_project_sync_cancellation(
        handle: u64,
        cancellation: MirrorCancellation,
    ) -> Result<(), ()> {
        let (index, _) = super::decode_handle(handle).ok_or(())?;
        let mut slots = project_sync_cancellations().lock().map_err(|_| ())?;
        let slot = slots.get_mut(index).ok_or(())?;
        if slot.is_some() {
            return Err(());
        }
        *slot = Some((handle, cancellation));
        Ok(())
    }

    fn remove_project_sync_cancellation(handle: u64) -> bool {
        let Some((index, _)) = super::decode_handle(handle) else {
            return false;
        };
        let Ok(mut slots) = project_sync_cancellations().lock() else {
            return false;
        };
        let Some(slot) = slots.get_mut(index) else {
            return false;
        };
        if slot.as_ref().is_some_and(|(active, _)| *active == handle) {
            *slot = None;
            true
        } else {
            false
        }
    }

    fn cancel_project_sync_without_runtime_lock(handle: u64) -> bool {
        let Some((index, _)) = super::decode_handle(handle) else {
            return false;
        };
        let Ok(slots) = project_sync_cancellations().lock() else {
            return false;
        };
        slots
            .get(index)
            .and_then(Option::as_ref)
            .filter(|(active, _)| *active == handle)
            .is_some_and(|(_, cancellation)| {
                cancellation.cancel();
                true
            })
    }

    fn java_string(environment: &mut JNIEnv, value: &JString) -> Result<String, jint> {
        environment
            .get_string(value)
            .map(Into::into)
            .map_err(|_| ERROR_INVALID_ARGUMENT)
    }

    fn parse_sha256(value: &str) -> Result<[u8; 32], jint> {
        if value.len() != 64 {
            return Err(ERROR_INVALID_ARGUMENT);
        }
        let mut digest = [0_u8; 32];
        for (index, pair) in value.as_bytes().chunks_exact(2).enumerate() {
            let high = hex_value(pair[0]).ok_or(ERROR_INVALID_ARGUMENT)?;
            let low = hex_value(pair[1]).ok_or(ERROR_INVALID_ARGUMENT)?;
            digest[index] = (high << 4) | low;
        }
        Ok(digest)
    }

    fn parse_rgb_hex(value: &str) -> Option<[u8; 3]> {
        if value.len() != 6 {
            return None;
        }
        let mut color = [0_u8; 3];
        for (index, pair) in value.as_bytes().chunks_exact(2).enumerate() {
            color[index] = (hex_value(pair[0])? << 4) | hex_value(pair[1])?;
        }
        Some(color)
    }

    fn parse_aur_install_filenames(bytes: &[u8]) -> Result<Vec<String>, jint> {
        if bytes.len() < 12
            || bytes.len() > MAX_AUR_INSTALL_FILENAME_BYTES
            || &bytes[..8] != AUR_INSTALL_FILENAME_MAGIC
        {
            return Err(ERROR_INVALID_ARGUMENT);
        }
        let count = u32::from_le_bytes(
            bytes[8..12]
                .try_into()
                .map_err(|_| ERROR_INVALID_ARGUMENT)?,
        ) as usize;
        if count == 0 || count > 256 {
            return Err(ERROR_INVALID_ARGUMENT);
        }
        let mut offset = 12_usize;
        let mut filenames = Vec::with_capacity(count);
        for _ in 0..count {
            let end = offset.checked_add(4).ok_or(ERROR_INVALID_ARGUMENT)?;
            let length = u32::from_le_bytes(
                bytes
                    .get(offset..end)
                    .ok_or(ERROR_INVALID_ARGUMENT)?
                    .try_into()
                    .map_err(|_| ERROR_INVALID_ARGUMENT)?,
            ) as usize;
            offset = end;
            if length == 0 || length > 240 {
                return Err(ERROR_INVALID_ARGUMENT);
            }
            let end = offset.checked_add(length).ok_or(ERROR_INVALID_ARGUMENT)?;
            let filename = str::from_utf8(bytes.get(offset..end).ok_or(ERROR_INVALID_ARGUMENT)?)
                .map_err(|_| ERROR_INVALID_ARGUMENT)?;
            if filename.bytes().any(|byte| !(0x21..=0x7e).contains(&byte)) {
                return Err(ERROR_INVALID_ARGUMENT);
            }
            filenames.push(filename.to_owned());
            offset = end;
        }
        if offset != bytes.len() {
            return Err(ERROR_INVALID_ARGUMENT);
        }
        Ok(filenames)
    }

    const fn hex_value(value: u8) -> Option<u8> {
        match value {
            b'0'..=b'9' => Some(value - b'0'),
            b'a'..=b'f' => Some(value - b'a' + 10),
            _ => None,
        }
    }

    fn ranges_overlap(
        first: usize,
        first_length: usize,
        second: usize,
        second_length: usize,
    ) -> bool {
        let Some(first_end) = first.checked_add(first_length) else {
            return true;
        };
        let Some(second_end) = second.checked_add(second_length) else {
            return true;
        };
        first < second_end && second < first_end
    }

    fn runtime_error(error: RuntimeError) -> jint {
        match error {
            RuntimeError::InvalidLifecycle => ERROR_INVALID_STATE,
            RuntimeError::QueueFull => ERROR_QUEUE_FULL,
            RuntimeError::InvalidEventBatch | RuntimeError::SnapshotTooSmall => {
                ERROR_INVALID_ARGUMENT
            }
        }
    }

    fn copy_tool_result(
        result: Result<ToolOutput, PackageRuntimeError>,
        destination: &mut [u8],
    ) -> jint {
        let output = match result {
            Ok(output) => output,
            Err(error) => {
                let diagnostic = error.to_string();
                let length = diagnostic.len().min(destination.len().saturating_sub(1));
                destination[..length].copy_from_slice(&diagnostic.as_bytes()[..length]);
                destination[length] = 0;
                return ERROR_PACKAGE_RUNTIME;
            }
        };
        let bytes = output.as_bytes();
        if bytes.len() > destination.len() {
            return ERROR_INTERNAL;
        }
        destination[..bytes.len()].copy_from_slice(bytes);
        i32::try_from(bytes.len()).unwrap_or(i32::MAX)
    }

    fn copy_package_resolution_result(
        result: Result<PackageResolution, PackageRuntimeError>,
        destination: &mut [u8],
    ) -> jint {
        let resolution = match result {
            Ok(resolution) => resolution,
            Err(error) => return copy_package_error(&error, destination),
        };
        let bytes = resolution.as_bytes();
        if bytes.len() > destination.len() {
            return ERROR_INTERNAL;
        }
        destination[..bytes.len()].copy_from_slice(bytes);
        i32::try_from(bytes.len()).unwrap_or(i32::MAX)
    }

    fn copy_verified_package_closure_result(
        result: Result<VerifiedPackageClosure, PackageRuntimeError>,
        destination: &mut [u8],
    ) -> jint {
        let closure = match result {
            Ok(closure) => closure,
            Err(error) => return copy_package_error(&error, destination),
        };
        let bytes = closure.as_bytes();
        if bytes.len() > destination.len() {
            return ERROR_INTERNAL;
        }
        destination[..bytes.len()].copy_from_slice(bytes);
        i32::try_from(bytes.len()).unwrap_or(i32::MAX)
    }

    fn copy_package_error(error: &impl std::fmt::Display, destination: &mut [u8]) -> jint {
        let diagnostic = error.to_string();
        let length = diagnostic.len().min(destination.len().saturating_sub(1));
        destination[..length].copy_from_slice(&diagnostic.as_bytes()[..length]);
        destination[length] = 0;
        ERROR_PACKAGE_RUNTIME
    }

    fn copy_job_error(error: &JobError, destination: &mut [u8]) -> jint {
        let diagnostic = error.to_string();
        let length = diagnostic.len().min(destination.len().saturating_sub(1));
        destination[..length].copy_from_slice(&diagnostic.as_bytes()[..length]);
        destination[length] = 0;
        ERROR_PACKAGE_RUNTIME
    }

    fn copy_process_error(error: &ProcessError, destination: &mut [u8]) -> jint {
        let diagnostic = error.to_string();
        let length = diagnostic.len().min(destination.len().saturating_sub(1));
        destination[..length].copy_from_slice(&diagnostic.as_bytes()[..length]);
        destination[length] = 0;
        ERROR_PROCESS
    }

    fn copy_storage_error(error: &StorageError, destination: &mut [u8]) -> jint {
        let diagnostic = error.to_string();
        let length = diagnostic.len().min(destination.len().saturating_sub(1));
        destination[..length].copy_from_slice(&diagnostic.as_bytes()[..length]);
        destination[length] = 0;
        ERROR_STORAGE
    }

    fn copy_storage_value(value: &str, destination: &mut [u8]) -> jint {
        if value.len() >= destination.len() {
            return ERROR_STORAGE;
        }
        destination[..value.len()].copy_from_slice(value.as_bytes());
        destination[value.len()] = 0;
        jint::try_from(value.len()).unwrap_or(ERROR_STORAGE)
    }

    fn storage_request(
        environment: &JNIEnv,
        request_buffer: &JByteBuffer,
        request_length: jint,
        field_count: usize,
    ) -> Result<Vec<String>, jint> {
        let request_length = usize::try_from(request_length).map_err(|_| ERROR_INVALID_ARGUMENT)?;
        let request_capacity = environment
            .get_direct_buffer_capacity(request_buffer)
            .map_err(|_| ERROR_INVALID_ARGUMENT)?;
        if request_length == 0
            || request_length > request_capacity
            || request_length > MAX_STORAGE_REQUEST_BYTES
        {
            return Err(ERROR_INVALID_ARGUMENT);
        }
        let request_address = environment
            .get_direct_buffer_address(request_buffer)
            .map_err(|_| ERROR_INVALID_ARGUMENT)?;
        if request_address.is_null() {
            return Err(ERROR_INVALID_ARGUMENT);
        }
        let request =
            // SAFETY: JNI validated the direct-buffer capacity above, and the
            // Java buffer remains live for this native call.
            unsafe { slice::from_raw_parts(request_address.cast_const(), request_length) };
        let request = str::from_utf8(request).map_err(|_| ERROR_INVALID_ARGUMENT)?;
        let fields: Vec<String> = request.split('\t').map(str::to_owned).collect();
        if fields.len() != field_count || fields.iter().any(String::is_empty) {
            return Err(ERROR_INVALID_ARGUMENT);
        }
        Ok(fields)
    }

    fn parse_mapping_id(value: &str) -> Result<[u8; 16], jint> {
        if value.len() != 32 {
            return Err(ERROR_INVALID_ARGUMENT);
        }
        let mut mapping_id = [0_u8; 16];
        for (index, pair) in value.as_bytes().chunks_exact(2).enumerate() {
            let high = match pair[0] {
                b'0'..=b'9' => pair[0] - b'0',
                b'a'..=b'f' => pair[0] - b'a' + 10,
                _ => return Err(ERROR_INVALID_ARGUMENT),
            };
            let low = match pair[1] {
                b'0'..=b'9' => pair[1] - b'0',
                b'a'..=b'f' => pair[1] - b'a' + 10,
                _ => return Err(ERROR_INVALID_ARGUMENT),
            };
            mapping_id[index] = (high << 4) | low;
        }
        if mapping_id == [0; 16] {
            return Err(ERROR_INVALID_ARGUMENT);
        }
        Ok(mapping_id)
    }

    fn parse_sync_fingerprint(value: &str) -> Result<Option<SyncFingerprint>, jint> {
        if value == "n" {
            return Ok(None);
        }
        if value == "d" {
            return Ok(Some(SyncFingerprint::directory()));
        }
        let mut fields = value.split(':');
        if fields.next() != Some("f") {
            return Err(ERROR_INVALID_ARGUMENT);
        }
        let bytes = fields
            .next()
            .and_then(|field| field.parse::<u64>().ok())
            .ok_or(ERROR_INVALID_ARGUMENT)?;
        let digest = fields.next().ok_or(ERROR_INVALID_ARGUMENT)?;
        if fields.next().is_some() || digest.len() != 64 {
            return Err(ERROR_INVALID_ARGUMENT);
        }
        let mut sha256 = [0_u8; 32];
        for (index, pair) in digest.as_bytes().chunks_exact(2).enumerate() {
            sha256[index] = (hex_value(pair[0]).ok_or(ERROR_INVALID_ARGUMENT)? << 4)
                | hex_value(pair[1]).ok_or(ERROR_INVALID_ARGUMENT)?;
        }
        Ok(Some(SyncFingerprint::file(bytes, sha256)))
    }

    fn format_sync_fingerprint(fingerprint: SyncFingerprint) -> String {
        match fingerprint.kind {
            SyncEntryKind::Directory => "d".to_owned(),
            SyncEntryKind::File => {
                const HEX: &[u8; 16] = b"0123456789abcdef";
                let mut value = format!("f:{}:", fingerprint.bytes);
                value.reserve(64);
                for byte in fingerprint.sha256 {
                    value.push(HEX[(byte >> 4) as usize] as char);
                    value.push(HEX[(byte & 0x0f) as usize] as char);
                }
                value
            }
        }
    }

    fn encode_sync_plan_entry(
        entry: &archphene_storage::SyncPlanEntry,
        output: &mut [u8],
    ) -> Result<usize, jint> {
        const HEADER_BYTES: usize = 136;
        let path = entry.path.as_bytes();
        let length = HEADER_BYTES
            .checked_add(path.len())
            .ok_or(ERROR_INVALID_ARGUMENT)?;
        if output.len() < length {
            return Err(ERROR_INVALID_ARGUMENT);
        }
        output[..length].fill(0);
        output[..8].copy_from_slice(b"ASPE0001");
        output[8] = match entry.action {
            SyncAction::Converged => 0,
            SyncAction::PushToAndroid => 1,
            SyncAction::PullToLinux => 2,
            SyncAction::DeleteFromAndroid => 3,
            SyncAction::DeleteFromLinux => 4,
            SyncAction::PreserveConflict => 5,
        };
        output[9] = sync_fingerprint_kind(entry.baseline);
        output[10] = sync_fingerprint_kind(entry.linux);
        output[11] = sync_fingerprint_kind(entry.android);
        let path_length = u32::try_from(path.len()).map_err(|_| ERROR_INVALID_ARGUMENT)?;
        output[12..16].copy_from_slice(&path_length.to_le_bytes());
        write_sync_fingerprint_payload(entry.baseline, &mut output[16..56]);
        write_sync_fingerprint_payload(entry.linux, &mut output[56..96]);
        write_sync_fingerprint_payload(entry.android, &mut output[96..136]);
        output[136..length].copy_from_slice(path);
        Ok(length)
    }

    fn sync_fingerprint_kind(fingerprint: Option<SyncFingerprint>) -> u8 {
        match fingerprint.map(|value| value.kind) {
            None => 0,
            Some(SyncEntryKind::Directory) => 1,
            Some(SyncEntryKind::File) => 2,
        }
    }

    fn write_sync_fingerprint_payload(fingerprint: Option<SyncFingerprint>, output: &mut [u8]) {
        if let Some(fingerprint) = fingerprint {
            output[..8].copy_from_slice(&fingerprint.bytes.to_le_bytes());
            output[8..40].copy_from_slice(&fingerprint.sha256);
        }
    }

    fn storage_output<'local>(
        environment: &JNIEnv<'local>,
        output_buffer: &JByteBuffer<'local>,
    ) -> Result<&'local mut [u8], jint> {
        let output_capacity = environment
            .get_direct_buffer_capacity(output_buffer)
            .map_err(|_| ERROR_INVALID_ARGUMENT)?;
        if output_capacity < 1024 {
            return Err(ERROR_INVALID_ARGUMENT);
        }
        let output_address = environment
            .get_direct_buffer_address(output_buffer)
            .map_err(|_| ERROR_INVALID_ARGUMENT)?;
        if output_address.is_null() {
            return Err(ERROR_INVALID_ARGUMENT);
        }
        // SAFETY: JNI validated the direct-buffer capacity above, and the Java
        // buffer remains live for this native call.
        Ok(unsafe { slice::from_raw_parts_mut(output_address, output_capacity) })
    }

    fn decode_job_operation(value: jint) -> Option<JobOperation> {
        match value {
            1 => Some(JobOperation::Install),
            2 => Some(JobOperation::Update),
            3 => Some(JobOperation::Remove),
            _ => None,
        }
    }

    fn decode_job_state(value: jint) -> Option<JobState> {
        match value {
            1 => Some(JobState::Queued),
            2 => Some(JobState::Resolving),
            3 => Some(JobState::Downloading),
            4 => Some(JobState::Verifying),
            5 => Some(JobState::Installing),
            6 => Some(JobState::Complete),
            7 => Some(JobState::Failed),
            8 => Some(JobState::Cancelled),
            9 => Some(JobState::Publishing),
            10 => Some(JobState::Building),
            11 => Some(JobState::AwaitingConfirmation),
            _ => None,
        }
    }

    fn decode_repository(value: jint) -> Option<Repository> {
        match value {
            1 => Some(Repository::Core),
            2 => Some(Repository::Extra),
            _ => None,
        }
    }

    fn decode_command_request<'a>(
        request: &'a [u8],
        arguments: &mut [&'a str; MAX_COMMAND_ARGUMENTS],
    ) -> Result<(&'a str, usize), ()> {
        let mut fields = request.split(|byte| *byte == 0);
        let command = fields
            .next()
            .and_then(|field| str::from_utf8(field).ok())
            .ok_or(())?;
        let mut argument_count = 0_usize;
        for field in fields {
            if argument_count == arguments.len() {
                return Err(());
            }
            arguments[argument_count] = str::from_utf8(field).map_err(|_| ())?;
            argument_count += 1;
        }
        Ok((command, argument_count))
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeProtocolVersion(
        _environment: JNIEnv,
        _class: JClass,
    ) -> jint {
        i32::try_from(PROTOCOL_VERSION).unwrap_or(i32::MAX)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeOpenHomeDocument(
        environment: JNIEnv,
        _class: JClass,
        request_buffer: JByteBuffer,
        request_length: jint,
        mode: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        if mode <= 0 || mode & !0x0f != 0 {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(output) = storage_output(&environment, &output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(request) = storage_request(&environment, &request_buffer, request_length, 2) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let open_mode = OpenMode {
            read: mode & 1 != 0,
            write: mode & 2 != 0,
            truncate: mode & 4 != 0,
            append: mode & 8 != 0,
        };
        match archphene_storage::open_document(Path::new(&request[0]), &request[1], open_mode) {
            Ok(file) => file.into_raw_fd(),
            Err(error) => copy_storage_error(&error, output),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeOpenShellStartupDocument(
        environment: JNIEnv,
        _class: JClass,
        request_buffer: JByteBuffer,
        request_length: jint,
        mode: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        if mode <= 0 || mode & !0x0f != 0 {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(output) = storage_output(&environment, &output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(request) = storage_request(&environment, &request_buffer, request_length, 2) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let open_mode = OpenMode {
            read: mode & 1 != 0,
            write: mode & 2 != 0,
            truncate: mode & 4 != 0,
            append: mode & 8 != 0,
        };
        match archphene_storage::open_shell_startup_document(
            Path::new(&request[0]),
            &request[1],
            open_mode,
        ) {
            Ok(file) => file.into_raw_fd(),
            Err(error) => copy_storage_error(&error, output),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeCreateHomeDocument(
        environment: JNIEnv,
        _class: JClass,
        request_buffer: JByteBuffer,
        request_length: jint,
        directory: jboolean,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(output) = storage_output(&environment, &output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(request) = storage_request(&environment, &request_buffer, request_length, 3) else {
            return ERROR_INVALID_ARGUMENT;
        };
        match archphene_storage::create_document(
            Path::new(&request[0]),
            &request[1],
            &request[2],
            directory != JNI_FALSE,
        ) {
            Ok(()) => 0,
            Err(error) => copy_storage_error(&error, output),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeDeleteHomeDocument(
        environment: JNIEnv,
        _class: JClass,
        request_buffer: JByteBuffer,
        request_length: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(output) = storage_output(&environment, &output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(request) = storage_request(&environment, &request_buffer, request_length, 2) else {
            return ERROR_INVALID_ARGUMENT;
        };
        match archphene_storage::delete_document(Path::new(&request[0]), &request[1]) {
            Ok(()) => 0,
            Err(error) => copy_storage_error(&error, output),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeRenameHomeDocument(
        environment: JNIEnv,
        _class: JClass,
        request_buffer: JByteBuffer,
        request_length: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(output) = storage_output(&environment, &output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(request) = storage_request(&environment, &request_buffer, request_length, 3) else {
            return ERROR_INVALID_ARGUMENT;
        };
        match archphene_storage::rename_document(Path::new(&request[0]), &request[1], &request[2]) {
            Ok(()) => 0,
            Err(error) => copy_storage_error(&error, output),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeImportHomeDocument(
        environment: JNIEnv,
        _class: JClass,
        request_buffer: JByteBuffer,
        request_length: jint,
        source_descriptor: jint,
        debug_chunk_delay_millis: jint,
        provider_idle_timeout_millis: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        if source_descriptor < 0
            || !(0..=100).contains(&debug_chunk_delay_millis)
            || !(1..=30_000).contains(&provider_idle_timeout_millis)
        {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(output) = storage_output(&environment, &output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(request) = storage_request(&environment, &request_buffer, request_length, 3) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if DOCUMENT_IMPORT_ACTIVE
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .is_err()
        {
            return ERROR_INVALID_STATE;
        }
        let _guard = DocumentImportGuard;
        let delay = Duration::from_millis(debug_chunk_delay_millis as u64);
        match archphene_storage::import_document_from_fd_with_progress_and_timeout(
            Path::new(&request[0]),
            &request[1],
            &request[2],
            source_descriptor,
            Duration::from_millis(provider_idle_timeout_millis as u64),
            |bytes| {
                DOCUMENT_IMPORT_BYTES.store(bytes, Ordering::Release);
                if !delay.is_zero() {
                    std::thread::sleep(delay);
                }
                !DOCUMENT_IMPORT_CANCELLED.load(Ordering::Acquire)
            },
        ) {
            Ok(report) => copy_storage_value(
                &format!("{}\t{}", report.display_name, report.bytes),
                output,
            ),
            Err(error) => copy_storage_error(&error, output),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativePrepareDocumentImport(
        _environment: JNIEnv,
        _class: JClass,
    ) -> jboolean {
        if DOCUMENT_IMPORT_ACTIVE.load(Ordering::Acquire) {
            return JNI_FALSE;
        }
        DOCUMENT_IMPORT_CANCELLED.store(false, Ordering::Release);
        DOCUMENT_IMPORT_BYTES.store(0, Ordering::Release);
        JNI_TRUE
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeDocumentImportProgress(
        _environment: JNIEnv,
        _class: JClass,
    ) -> jlong {
        let bytes = DOCUMENT_IMPORT_BYTES.load(Ordering::Acquire);
        jlong::try_from(bytes).unwrap_or(jlong::MAX)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeCancelDocumentImport(
        _environment: JNIEnv,
        _class: JClass,
    ) -> jboolean {
        DOCUMENT_IMPORT_CANCELLED.store(true, Ordering::Release);
        if DOCUMENT_IMPORT_ACTIVE.load(Ordering::Acquire) {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeExportHomeDocument(
        environment: JNIEnv,
        _class: JClass,
        source_descriptor: jint,
        destination_descriptor: jint,
        debug_chunk_delay_millis: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        if source_descriptor < 0
            || destination_descriptor < 0
            || !(0..=100).contains(&debug_chunk_delay_millis)
        {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(output) = storage_output(&environment, &output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if DOCUMENT_EXPORT_ACTIVE
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .is_err()
        {
            return ERROR_INVALID_STATE;
        }
        let _guard = DocumentExportGuard;
        DOCUMENT_EXPORT_CANCELLED.store(false, Ordering::Release);
        DOCUMENT_EXPORT_BYTES.store(0, Ordering::Release);
        let delay = Duration::from_millis(debug_chunk_delay_millis as u64);
        match archphene_storage::copy_document_between_fds_with_progress(
            source_descriptor,
            destination_descriptor,
            |bytes| {
                DOCUMENT_EXPORT_BYTES.store(bytes, Ordering::Release);
                if !delay.is_zero() {
                    std::thread::sleep(delay);
                }
                !DOCUMENT_EXPORT_CANCELLED.load(Ordering::Acquire)
            },
        ) {
            Ok(bytes) => copy_storage_value(&bytes.to_string(), output),
            Err(error) => copy_storage_error(&error, output),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeDocumentExportProgress(
        _environment: JNIEnv,
        _class: JClass,
    ) -> jlong {
        let bytes = DOCUMENT_EXPORT_BYTES.load(Ordering::Acquire);
        jlong::try_from(bytes).unwrap_or(jlong::MAX)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeCancelDocumentExport(
        _environment: JNIEnv,
        _class: JClass,
    ) -> jboolean {
        if !DOCUMENT_EXPORT_ACTIVE.load(Ordering::Acquire) {
            return JNI_FALSE;
        }
        DOCUMENT_EXPORT_CANCELLED.store(true, Ordering::Release);
        JNI_TRUE
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeBeginProjectMirror(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        request_buffer: JByteBuffer,
        request_length: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(handle) = u64::try_from(handle) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(output) = storage_output(&environment, &output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(request) = storage_request(&environment, &request_buffer, request_length, 2) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(mapping_id) = parse_mapping_id(&request[1]) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        runtime
            .begin_mirror_import(&request[0], mapping_id)
            .map_or_else(|error| copy_storage_error(&error, output), |_| 0)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeImportPortalFolder(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        request_buffer: JByteBuffer,
        request_length: jint,
        source_descriptor: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(handle) = u64::try_from(handle) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if source_descriptor < 0 {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(output) = storage_output(&environment, &output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(request) = storage_request(&environment, &request_buffer, request_length, 1) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let (display_name, mut mirror) = {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return ERROR_INVALID_HANDLE;
            };
            let display_name = match runtime.begin_portal_folder_import(&request[0]) {
                Ok(value) => value,
                Err(error) => return copy_storage_error(&error, output),
            };
            let mirror = match runtime.take_mirror_import() {
                Ok(value) => value,
                Err(error) => {
                    runtime.abort_mirror_import();
                    return copy_storage_error(&error, output);
                }
            };
            (display_name, mirror)
        };
        if let Err(error) = mirror.add_portal_folder_from_fd(source_descriptor) {
            drop(mirror);
            if let Ok(mut registry) = registry().lock()
                && let Some(runtime) = registry.runtime_mut(handle)
            {
                runtime.abort_mirror_import();
            }
            return copy_storage_error(&error, output);
        }
        let result = {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return ERROR_INVALID_HANDLE;
            };
            if let Err(error) = runtime.restore_mirror_import(mirror) {
                runtime.abort_mirror_import();
                Err(error)
            } else {
                runtime.finish_mirror_import()
            }
        };
        match result {
            Ok(report) => copy_storage_value(
                &format!("{}\t{}\t{}", display_name, report.entries, report.bytes),
                output,
            ),
            Err(error) => copy_storage_error(&error, output),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeAddProjectMirrorDirectory(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        request_buffer: JByteBuffer,
        request_length: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(handle) = u64::try_from(handle) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(output) = storage_output(&environment, &output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(request) = storage_request(&environment, &request_buffer, request_length, 1) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        runtime
            .add_mirror_directory(&request[0])
            .map_or_else(|error| copy_storage_error(&error, output), |_| 0)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeAddProjectMirrorFile(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        request_buffer: JByteBuffer,
        request_length: jint,
        source_descriptor: jint,
        expected_bytes: jlong,
        output_buffer: JByteBuffer,
    ) -> jlong {
        let (Ok(handle), expected_bytes) = (
            u64::try_from(handle),
            if expected_bytes < 0 {
                None
            } else {
                u64::try_from(expected_bytes).ok()
            },
        ) else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        if source_descriptor < 0 {
            return i64::from(ERROR_INVALID_ARGUMENT);
        }
        let Ok(output) = storage_output(&environment, &output_buffer) else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        let Ok(request) = storage_request(&environment, &request_buffer, request_length, 1) else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        let mut mirror = {
            let Ok(mut registry) = registry().lock() else {
                return i64::from(ERROR_INTERNAL);
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return i64::from(ERROR_INVALID_HANDLE);
            };
            let Ok(mirror) = runtime.take_mirror_import() else {
                return i64::from(ERROR_INVALID_STATE);
            };
            mirror
        };
        let result = mirror.add_file_from_fd(&request[0], source_descriptor, expected_bytes);
        let restored = {
            let Ok(mut registry) = registry().lock() else {
                return i64::from(ERROR_INTERNAL);
            };
            registry
                .runtime_mut(handle)
                .is_some_and(|runtime| runtime.restore_mirror_import(mirror).is_ok())
        };
        if !restored {
            return i64::from(ERROR_INVALID_HANDLE);
        }
        result.map_or_else(
            |error| i64::from(copy_storage_error(&error, output)),
            |bytes| i64::try_from(bytes).unwrap_or(i64::from(ERROR_STORAGE)),
        )
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeFinishProjectMirror(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(handle) = u64::try_from(handle) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(output) = storage_output(&environment, &output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        match runtime.finish_mirror_import() {
            Ok(report) => {
                copy_storage_value(&format!("{}\t{}", report.entries, report.bytes), output)
            }
            Err(error) => copy_storage_error(&error, output),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeAbortProjectMirror(
        _environment: JNIEnv,
        _class: JClass,
        handle: jlong,
    ) -> jboolean {
        let Ok(handle) = u64::try_from(handle) else {
            return JNI_FALSE;
        };
        let Ok(mut registry) = registry().lock() else {
            return JNI_FALSE;
        };
        if registry
            .runtime_mut(handle)
            .is_some_and(|runtime| runtime.abort_mirror_import())
        {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeCancelProjectMirror(
        _environment: JNIEnv,
        _class: JClass,
        handle: jlong,
    ) -> jboolean {
        let Ok(handle) = u64::try_from(handle) else {
            return JNI_FALSE;
        };
        let Ok(mut registry) = registry().lock() else {
            return JNI_FALSE;
        };
        if registry
            .runtime_mut(handle)
            .is_some_and(|runtime| runtime.cancel_mirror_import())
        {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeBeginProjectSync(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        request_buffer: JByteBuffer,
        request_length: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(handle) = u64::try_from(handle) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(output) = storage_output(&environment, &output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(request) = storage_request(&environment, &request_buffer, request_length, 1) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(mapping_id) = parse_mapping_id(&request[0]) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        let cancellation = MirrorCancellation::new();
        if set_project_sync_cancellation(handle, cancellation.clone()).is_err() {
            return ERROR_INTERNAL;
        }
        match runtime.begin_project_sync(mapping_id, cancellation) {
            Ok(()) => 0,
            Err(error) => {
                remove_project_sync_cancellation(handle);
                copy_storage_error(&error, output)
            }
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeFingerprintProjectSyncFile(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        source_descriptor: jint,
        expected_bytes: jlong,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(handle) = u64::try_from(handle) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let expected_bytes = if expected_bytes < 0 {
            None
        } else {
            let Ok(bytes) = u64::try_from(expected_bytes) else {
                return ERROR_INVALID_ARGUMENT;
            };
            Some(bytes)
        };
        if source_descriptor < 0 {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(output) = storage_output(&environment, &output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        match runtime.fingerprint_project_sync_file(source_descriptor, expected_bytes) {
            Ok(fingerprint) => copy_storage_value(&format_sync_fingerprint(fingerprint), output),
            Err(error) => copy_storage_error(&error, output),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeFingerprintFile(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        source_descriptor: jint,
        expected_bytes: jlong,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(handle) = u64::try_from(handle) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let expected_bytes = if expected_bytes < 0 {
            None
        } else {
            let Ok(bytes) = u64::try_from(expected_bytes) else {
                return ERROR_INVALID_ARGUMENT;
            };
            Some(bytes)
        };
        if source_descriptor < 0 {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(output) = storage_output(&environment, &output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            if registry.runtime_mut(handle).is_none() {
                return ERROR_INVALID_HANDLE;
            }
        }
        match fingerprint_file_from_fd(source_descriptor, expected_bytes) {
            Ok(fingerprint) => copy_storage_value(&format_sync_fingerprint(fingerprint), output),
            Err(error) => copy_storage_error(&error, output),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeAddProjectSyncAndroidDirectory(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        request_buffer: JByteBuffer,
        request_length: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(handle) = u64::try_from(handle) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(output) = storage_output(&environment, &output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(request) = storage_request(&environment, &request_buffer, request_length, 1) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        registry
            .runtime_mut(handle)
            .map_or(ERROR_INVALID_HANDLE, |runtime| {
                runtime
                    .add_project_sync_android_directory(&request[0])
                    .map_or_else(|error| copy_storage_error(&error, output), |_| 0)
            })
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeAddProjectSyncAndroidFile(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        request_buffer: JByteBuffer,
        request_length: jint,
        source_descriptor: jint,
        expected_bytes: jlong,
        output_buffer: JByteBuffer,
    ) -> jint {
        let (Ok(handle), expected_bytes) = (
            u64::try_from(handle),
            if expected_bytes < 0 {
                None
            } else {
                u64::try_from(expected_bytes).ok()
            },
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if source_descriptor < 0 {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(output) = storage_output(&environment, &output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(request) = storage_request(&environment, &request_buffer, request_length, 1) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        registry
            .runtime_mut(handle)
            .map_or(ERROR_INVALID_HANDLE, |runtime| {
                runtime
                    .add_project_sync_android_file(&request[0], source_descriptor, expected_bytes)
                    .map_or_else(|error| copy_storage_error(&error, output), |_| 0)
            })
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeFinishProjectSyncScan(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(handle) = u64::try_from(handle) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(output) = storage_output(&environment, &output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        match runtime.finish_project_sync_scan() {
            Ok(summary) => {
                let Ok(entries) = runtime.project_sync_plan_entries() else {
                    return ERROR_INTERNAL;
                };
                copy_storage_value(
                    &format!(
                        "{entries}\t{}\t{}\t{}\t{}\t{}\t{}",
                        summary.converged,
                        summary.push_to_android,
                        summary.pull_to_linux,
                        summary.delete_from_android,
                        summary.delete_from_linux,
                        summary.conflicts,
                    ),
                    output,
                )
            }
            Err(error) => copy_storage_error(&error, output),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeProjectSyncPlanEntry(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        index: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        let (Ok(handle), Ok(index)) = (u64::try_from(handle), usize::try_from(index)) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(output) = storage_output(&environment, &output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        match runtime.project_sync_plan_entry(index) {
            Ok(entry) => encode_sync_plan_entry(entry, output)
                .ok()
                .and_then(|length| jint::try_from(length).ok())
                .unwrap_or(ERROR_INVALID_ARGUMENT),
            Err(error) => copy_storage_error(&error, output),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeExecuteProjectSyncLocal(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        operation: jint,
        request_buffer: JByteBuffer,
        request_length: jint,
        source_descriptor: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(handle) = u64::try_from(handle) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(output) = storage_output(&environment, &output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let field_count = match operation {
            1 | 3 => 2,
            2 => 3,
            4 => 1,
            5 => 2,
            6 => 2,
            _ => return ERROR_INVALID_ARGUMENT,
        };
        let Ok(request) =
            storage_request(&environment, &request_buffer, request_length, field_count)
        else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        match operation {
            1 => {
                let Ok(Some(expected)) = parse_sync_fingerprint(&request[1]) else {
                    return ERROR_INVALID_ARGUMENT;
                };
                runtime
                    .open_project_sync_linux_file(&request[0], expected)
                    .map_or_else(
                        |error| copy_storage_error(&error, output),
                        |file| file.into_raw_fd(),
                    )
            }
            2 => {
                if source_descriptor < 0 {
                    return ERROR_INVALID_ARGUMENT;
                }
                let (Ok(Some(android)), Ok(linux)) = (
                    parse_sync_fingerprint(&request[1]),
                    parse_sync_fingerprint(&request[2]),
                ) else {
                    return ERROR_INVALID_ARGUMENT;
                };
                runtime
                    .pull_project_sync_linux_file(&request[0], source_descriptor, android, linux)
                    .map_or_else(|error| copy_storage_error(&error, output), |_| 0)
            }
            3 => {
                let Ok(Some(expected)) = parse_sync_fingerprint(&request[1]) else {
                    return ERROR_INVALID_ARGUMENT;
                };
                runtime
                    .delete_project_sync_linux_entry(&request[0], expected)
                    .map_or_else(|error| copy_storage_error(&error, output), |_| 0)
            }
            4 => runtime
                .create_project_sync_linux_directory(&request[0])
                .map_or_else(|error| copy_storage_error(&error, output), |_| 0),
            5 => {
                if source_descriptor < 0 {
                    return ERROR_INVALID_ARGUMENT;
                }
                let Ok(Some(android)) = parse_sync_fingerprint(&request[1]) else {
                    return ERROR_INVALID_ARGUMENT;
                };
                match runtime.preserve_project_sync_android_conflict(
                    &request[0],
                    source_descriptor,
                    android,
                ) {
                    Ok(path) => copy_storage_value(&path, output),
                    Err(error) => copy_storage_error(&error, output),
                }
            }
            6 => ERROR_INVALID_STATE,
            _ => ERROR_INVALID_ARGUMENT,
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeBeginProjectSyncCommitScan(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(handle) = u64::try_from(handle) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(output) = storage_output(&environment, &output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        registry
            .runtime_mut(handle)
            .map_or(ERROR_INVALID_HANDLE, |runtime| {
                runtime
                    .begin_project_sync_commit_scan()
                    .map_or_else(|error| copy_storage_error(&error, output), |_| 0)
            })
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeCommitProjectSync(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(handle) = u64::try_from(handle) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(output) = storage_output(&environment, &output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        match runtime.commit_project_sync() {
            Ok(()) => {
                remove_project_sync_cancellation(handle);
                0
            }
            Err(error) => copy_storage_error(&error, output),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeAbortProjectSync(
        _environment: JNIEnv,
        _class: JClass,
        handle: jlong,
    ) -> jboolean {
        let Ok(handle) = u64::try_from(handle) else {
            return JNI_FALSE;
        };
        let Ok(mut registry) = registry().lock() else {
            return JNI_FALSE;
        };
        let aborted = registry
            .runtime_mut(handle)
            .is_some_and(|runtime| runtime.abort_project_sync());
        remove_project_sync_cancellation(handle);
        if aborted { JNI_TRUE } else { JNI_FALSE }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeCancelProjectSync(
        _environment: JNIEnv,
        _class: JClass,
        handle: jlong,
    ) -> jboolean {
        let Ok(handle) = u64::try_from(handle) else {
            return JNI_FALSE;
        };
        if cancel_project_sync_without_runtime_lock(handle) {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeCreate(
        _environment: JNIEnv,
        _class: JClass,
    ) -> jlong {
        let Ok(mut registry) = registry().lock() else {
            return 0;
        };
        registry
            .create()
            .and_then(|handle| i64::try_from(handle).ok())
            .unwrap_or(0)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeDestroy(
        _environment: JNIEnv,
        _class: JClass,
        handle: jlong,
    ) -> jboolean {
        let Ok(handle) = u64::try_from(handle) else {
            return JNI_FALSE;
        };
        let Ok(mut registry) = registry().lock() else {
            return JNI_FALSE;
        };
        let destroyed = registry.destroy(handle);
        remove_project_sync_cancellation(handle);
        cancel_package_compatibility_without_runtime_lock(handle);
        remove_package_compatibility_cancellation(handle);
        if destroyed { JNI_TRUE } else { JNI_FALSE }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeTransition(
        _environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        lifecycle: jint,
    ) -> jint {
        let (Ok(handle), Ok(lifecycle)) = (u64::try_from(handle), u32::try_from(lifecycle)) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Some(lifecycle) = Lifecycle::from_raw(lifecycle) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        runtime
            .transition(lifecycle)
            .map_or_else(runtime_error, |_| 0)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeBootstrapArchRoot(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        buffer: JByteBuffer,
        byte_count: jint,
        now_millis: jlong,
    ) -> jint {
        let (Ok(handle), Ok(byte_count), Ok(now_millis)) = (
            u64::try_from(handle),
            usize::try_from(byte_count),
            u64::try_from(now_millis),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(capacity) = environment.get_direct_buffer_capacity(&buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if byte_count == 0 || byte_count > capacity || byte_count > 1024 {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(address) = environment.get_direct_buffer_address(&buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let bytes = unsafe { slice::from_raw_parts(address.cast_const(), byte_count) };
        let Ok(path) = std::str::from_utf8(bytes) else {
            return ERROR_INVALID_ARGUMENT;
        };

        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        runtime
            .bootstrap_arch_root(Path::new(path), now_millis)
            .map_or(ERROR_BOOTSTRAP, |report| {
                i32::try_from(report.root.created_directories).unwrap_or(i32::MAX)
            })
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeConfigureDns(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        request_buffer: JByteBuffer,
        request_length: jint,
    ) -> jint {
        let (Ok(handle), Ok(request_length)) =
            (u64::try_from(handle), usize::try_from(request_length))
        else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(request_capacity) = environment.get_direct_buffer_capacity(&request_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if request_length == 0
            || request_length > request_capacity
            || request_length > MAX_ANDROID_DNS_REQUEST_BYTES
        {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(request_address) = environment.get_direct_buffer_address(&request_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if request_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let request =
            unsafe { slice::from_raw_parts(request_address.cast_const(), request_length) };
        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        runtime
            .configure_android_dns(request)
            .map_or(ERROR_BOOTSTRAP, |servers| {
                i32::try_from(servers).unwrap_or(i32::MAX)
            })
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativePreparePackageRuntime(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        architecture: jint,
        native_path_buffer: JByteBuffer,
        native_path_length: jint,
        manifest_buffer: JByteBuffer,
        manifest_length: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        let (Ok(handle), Ok(native_path_length), Ok(manifest_length)) = (
            u64::try_from(handle),
            usize::try_from(native_path_length),
            usize::try_from(manifest_length),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let architecture = match architecture {
            1 => RepositoryArchitecture::X86_64,
            2 => RepositoryArchitecture::Aarch64,
            _ => return ERROR_INVALID_ARGUMENT,
        };
        let (Ok(native_capacity), Ok(manifest_capacity), Ok(output_capacity)) = (
            environment.get_direct_buffer_capacity(&native_path_buffer),
            environment.get_direct_buffer_capacity(&manifest_buffer),
            environment.get_direct_buffer_capacity(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if native_path_length == 0
            || native_path_length > native_capacity
            || native_path_length > 1024
            || manifest_length == 0
            || manifest_length > manifest_capacity
            || manifest_length > MAX_MANIFEST_BYTES
            || output_capacity < MAX_TOOL_OUTPUT_BYTES
        {
            return ERROR_INVALID_ARGUMENT;
        }
        let (Ok(native_address), Ok(manifest_address), Ok(output_address)) = (
            environment.get_direct_buffer_address(&native_path_buffer),
            environment.get_direct_buffer_address(&manifest_buffer),
            environment.get_direct_buffer_address(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if native_address.is_null() || manifest_address.is_null() || output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let native_bytes =
            unsafe { slice::from_raw_parts(native_address.cast_const(), native_path_length) };
        let manifest =
            unsafe { slice::from_raw_parts(manifest_address.cast_const(), manifest_length) };
        let Ok(native_path) = std::str::from_utf8(native_bytes) else {
            return ERROR_INVALID_ARGUMENT;
        };

        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        let result =
            runtime.prepare_package_runtime(Path::new(native_path), manifest, architecture);
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        copy_tool_result(result, destination)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeBeginPackageCatalogDownload(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        repository: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(handle) = u64::try_from(handle) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Some(repository) = decode_repository(repository) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(output_capacity) = environment.get_direct_buffer_capacity(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_capacity < 512 {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(output_address) = environment.get_direct_buffer_address(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        let (file, url) = match runtime.begin_catalog_download(repository) {
            Ok(result) => result,
            Err(error) => return copy_package_error(&error, destination),
        };
        if url.len() >= destination.len() {
            runtime.cancel_catalog_download();
            return ERROR_INTERNAL;
        }
        destination[..url.len()].copy_from_slice(url.as_bytes());
        destination[url.len()] = 0;
        file.into_raw_fd()
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeFinishPackageCatalogDownload(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        repository: jint,
        success: jboolean,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(handle) = u64::try_from(handle) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Some(repository) = decode_repository(repository) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(output_capacity) = environment.get_direct_buffer_capacity(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_capacity < 512 {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(output_address) = environment.get_direct_buffer_address(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        if success == JNI_FALSE {
            runtime.cancel_catalog_download();
            return 0;
        }
        match runtime.finish_catalog_download(repository, true) {
            Ok(length) => i32::try_from(length).unwrap_or(i32::MAX),
            Err(error) => copy_package_error(&error, destination),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeSearchPackages(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        query_buffer: JByteBuffer,
        query_length: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        let (Ok(handle), Ok(query_length)) = (u64::try_from(handle), usize::try_from(query_length))
        else {
            return ERROR_INVALID_ARGUMENT;
        };
        let (Ok(query_capacity), Ok(output_capacity)) = (
            environment.get_direct_buffer_capacity(&query_buffer),
            environment.get_direct_buffer_capacity(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if query_length == 0
            || query_length > query_capacity
            || query_length > 128
            || output_capacity < MAX_TOOL_OUTPUT_BYTES
        {
            return ERROR_INVALID_ARGUMENT;
        }
        let (Ok(query_address), Ok(output_address)) = (
            environment.get_direct_buffer_address(&query_buffer),
            environment.get_direct_buffer_address(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if query_address.is_null() || output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let query_bytes =
            unsafe { slice::from_raw_parts(query_address.cast_const(), query_length) };
        let Ok(query) = std::str::from_utf8(query_bytes) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let package_runtime = {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return ERROR_INVALID_HANDLE;
            };
            let Some(package_runtime) = runtime.package_runtime() else {
                return ERROR_INVALID_STATE;
            };
            package_runtime.clone()
        };
        let result = package_runtime.search(query);
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        copy_tool_result(result, destination)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeDiscoverShells(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(handle) = u64::try_from(handle) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(output_capacity) = environment.get_direct_buffer_capacity(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_capacity < MAX_TOOL_OUTPUT_BYTES {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(output_address) = environment.get_direct_buffer_address(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        let result = {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return ERROR_INVALID_HANDLE;
            };
            runtime.discover_shells()
        };
        copy_tool_result(result, destination)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeResolvePackage(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        package_buffer: JByteBuffer,
        package_length: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        let (Ok(handle), Ok(package_length)) =
            (u64::try_from(handle), usize::try_from(package_length))
        else {
            return ERROR_INVALID_ARGUMENT;
        };
        let (Ok(package_capacity), Ok(output_capacity)) = (
            environment.get_direct_buffer_capacity(&package_buffer),
            environment.get_direct_buffer_capacity(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if package_length == 0
            || package_length > package_capacity
            || package_length > 128
            || output_capacity < MAX_PACKAGE_RESOLUTION_BYTES
        {
            return ERROR_INVALID_ARGUMENT;
        }
        let (Ok(package_address), Ok(output_address)) = (
            environment.get_direct_buffer_address(&package_buffer),
            environment.get_direct_buffer_address(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if package_address.is_null() || output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let package_bytes =
            unsafe { slice::from_raw_parts(package_address.cast_const(), package_length) };
        let Ok(package) = std::str::from_utf8(package_bytes) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let package_runtime = {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return ERROR_INVALID_HANDLE;
            };
            let Some(package_runtime) = runtime.package_runtime() else {
                return ERROR_INVALID_STATE;
            };
            package_runtime.clone()
        };
        let result = package_runtime.resolve(package);
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        copy_package_resolution_result(result, destination)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeResolveAurBuildEnvironment(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(handle) = u64::try_from(handle) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(output_capacity) = environment.get_direct_buffer_capacity(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_capacity < MAX_PACKAGE_RESOLUTION_BYTES {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(output_address) = environment.get_direct_buffer_address(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let result = {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return ERROR_INVALID_HANDLE;
            };
            runtime.resolve_aur_build_environment()
        };
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        copy_package_resolution_result(result, destination)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeVerifyAurBuildEnvironment(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(handle) = u64::try_from(handle) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(output_capacity) = environment.get_direct_buffer_capacity(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_capacity < MAX_PACKAGE_RESOLUTION_BYTES {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(output_address) = environment.get_direct_buffer_address(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let result = {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return ERROR_INVALID_HANDLE;
            };
            runtime.verify_aur_build_environment()
        };
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        copy_package_resolution_result(result, destination)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeReadVerifiedAurBuildClosure(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(handle) = u64::try_from(handle) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(output_capacity) = environment.get_direct_buffer_capacity(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_capacity < MAX_VERIFIED_PACKAGE_CLOSURE_BYTES {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(output_address) = environment.get_direct_buffer_address(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let result = {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return ERROR_INVALID_HANDLE;
            };
            runtime.verified_aur_build_closure()
        };
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        copy_verified_package_closure_result(result, destination)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeOpenVerifiedAurBuildPackage(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        package_index: jint,
        signature: jboolean,
        output_buffer: JByteBuffer,
    ) -> jint {
        let (Ok(handle), Ok(package_index)) =
            (u64::try_from(handle), usize::try_from(package_index))
        else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(output_capacity) = environment.get_direct_buffer_capacity(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if package_index >= 512 || output_capacity < MAX_TOOL_OUTPUT_BYTES {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(output_address) = environment.get_direct_buffer_address(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        let result = {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return ERROR_INVALID_HANDLE;
            };
            runtime.open_verified_aur_build_package(package_index, signature != JNI_FALSE)
        };
        match result {
            Ok(file) => file.into_raw_fd(),
            Err(error) => copy_package_error(&error, destination),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeVerifyAurBuiltPackage(
        mut environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        descriptor: jint,
        filename: JString,
        package_base: JString,
        package_name: JString,
        version: JString,
        architecture: JString,
        closure_sha256: JString,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(handle) = u64::try_from(handle) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if descriptor < 0 {
            return ERROR_INVALID_ARGUMENT;
        }
        let (
            Ok(filename),
            Ok(package_base),
            Ok(package_name),
            Ok(version),
            Ok(architecture),
            Ok(closure_sha256),
        ) = (
            java_string(&mut environment, &filename),
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
        let (Ok(output_capacity), Ok(output_address)) = (
            environment.get_direct_buffer_capacity(&output_buffer),
            environment.get_direct_buffer_address(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_capacity < BUILT_PACKAGE_REPORT_BYTES || output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        // SAFETY: JNI verified this direct buffer has the complete fixed report capacity.
        let output =
            unsafe { slice::from_raw_parts_mut(output_address, BUILT_PACKAGE_REPORT_BYTES) };
        output.fill(0);
        let closure = {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return ERROR_INVALID_HANDLE;
            };
            match runtime.verified_aur_build_closure() {
                Ok(closure) => closure,
                Err(error) => return copy_package_error(&error, output),
            }
        };
        // SAFETY: The descriptor is borrowed only for dup. Kotlin retains the
        // original ParcelFileDescriptor for the duration of this call.
        let borrowed = unsafe { BorrowedFd::borrow_raw(descriptor) };
        let Ok(owned) = rustix::io::dup(borrowed) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let mut archive = File::from(owned);
        let report = match archphene_builder::verify_copied_built_package(
            &mut archive,
            &filename,
            &package_base,
            &package_name,
            &version,
            &architecture,
            closure.as_bytes(),
            closure_sha256,
        ) {
            Ok(report) => report,
            Err(error) => return copy_package_error(&error, output),
        };
        let Ok(build_package_count) = u32::try_from(report.build_package_count) else {
            return ERROR_INTERNAL;
        };
        output[..8].copy_from_slice(BUILT_PACKAGE_REPORT_MAGIC);
        output[8..16].copy_from_slice(&report.archive_bytes.to_le_bytes());
        output[16..24].copy_from_slice(&report.installed_bytes.to_le_bytes());
        output[24..28].copy_from_slice(&build_package_count.to_le_bytes());
        output[32..64].copy_from_slice(&report.sha256);
        BUILT_PACKAGE_REPORT_BYTES as jint
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeInstallAurBuiltPackage(
        mut environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        descriptor: jint,
        filename: JString,
        package_base: JString,
        package_name: JString,
        version: JString,
        architecture: JString,
        closure_sha256: JString,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(handle) = u64::try_from(handle) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if descriptor < 0 {
            return ERROR_INVALID_ARGUMENT;
        }
        let (
            Ok(filename),
            Ok(package_base),
            Ok(package_name),
            Ok(version),
            Ok(architecture),
            Ok(closure_sha256),
        ) = (
            java_string(&mut environment, &filename),
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
        let (Ok(output_capacity), Ok(output_address)) = (
            environment.get_direct_buffer_capacity(&output_buffer),
            environment.get_direct_buffer_address(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_capacity < MAX_TOOL_OUTPUT_BYTES || output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        // SAFETY: JNI verified the direct output-buffer capacity.
        let output = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        output.fill(0);
        let (closure, dependencies, package_runtime) = {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return ERROR_INVALID_HANDLE;
            };
            let closure = match runtime.verified_aur_build_closure() {
                Ok(closure) => closure,
                Err(error) => return copy_package_error(&error, output),
            };
            let dependencies =
                match runtime.verified_aur_runtime_dependencies(&package_name, &version) {
                    Ok(dependencies) => dependencies,
                    Err(error) => return copy_package_error(&error, output),
                };
            let Some(package_runtime) = runtime.package_runtime().cloned() else {
                return ERROR_INVALID_STATE;
            };
            (closure, dependencies, package_runtime)
        };
        // SAFETY: The descriptor is borrowed only for dup. Kotlin retains the
        // original ParcelFileDescriptor for the duration of this call.
        let borrowed = unsafe { BorrowedFd::borrow_raw(descriptor) };
        let Ok(owned) = rustix::io::dup(borrowed) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let mut archive = File::from(owned);
        let report = match archphene_builder::verify_copied_built_package(
            &mut archive,
            &filename,
            &package_base,
            &package_name,
            &version,
            &architecture,
            closure.as_bytes(),
            closure_sha256,
        ) {
            Ok(report) => report,
            Err(error) => return copy_package_error(&error, output),
        };
        let dependency_names: Vec<&str> = dependencies.iter().map(String::as_str).collect();
        if let Err(error) = package_runtime.install_dependencies(&dependency_names) {
            return copy_package_error(&error, output);
        }
        copy_tool_result(
            package_runtime.install_verified_aur_archive(
                &mut archive,
                &report.filename,
                &package_name,
                &version,
                report.archive_bytes,
                report.sha256,
            ),
            output,
        )
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeInstallAurBuiltPackages(
        mut environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        descriptors: JIntArray,
        filename_manifest: JByteBuffer,
        filename_manifest_length: jint,
        package_base: JString,
        package_name: JString,
        version: JString,
        architecture: JString,
        closure_sha256: JString,
        output_buffer: JByteBuffer,
    ) -> jint {
        let (Ok(handle), Ok(filename_manifest_length)) = (
            u64::try_from(handle),
            usize::try_from(filename_manifest_length),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let (Ok(package_base), Ok(package_name), Ok(version), Ok(architecture), Ok(closure_sha256)) = (
            java_string(&mut environment, &package_base),
            java_string(&mut environment, &package_name),
            java_string(&mut environment, &version),
            java_string(&mut environment, &architecture),
            java_string(&mut environment, &closure_sha256),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(closure_sha256) = parse_sha256(&closure_sha256) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let (Ok(manifest_capacity), Ok(manifest_address)) = (
            environment.get_direct_buffer_capacity(&filename_manifest),
            environment.get_direct_buffer_address(&filename_manifest),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if filename_manifest_length > manifest_capacity
            || filename_manifest_length > MAX_AUR_INSTALL_FILENAME_BYTES
            || manifest_address.is_null()
        {
            return ERROR_INVALID_ARGUMENT;
        }
        // SAFETY: JNI verified the direct manifest buffer and bounded length.
        let manifest = unsafe { slice::from_raw_parts(manifest_address, filename_manifest_length) };
        let Ok(filenames) = parse_aur_install_filenames(manifest) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(descriptor_count) = environment.get_array_length(&descriptors) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(descriptor_count) = usize::try_from(descriptor_count) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if descriptor_count != filenames.len() {
            return ERROR_INVALID_ARGUMENT;
        }
        let mut descriptor_values = vec![0_i32; descriptor_count];
        if environment
            .get_int_array_region(&descriptors, 0, &mut descriptor_values)
            .is_err()
            || descriptor_values.iter().any(|descriptor| *descriptor < 0)
        {
            return ERROR_INVALID_ARGUMENT;
        }
        let (Ok(output_capacity), Ok(output_address)) = (
            environment.get_direct_buffer_capacity(&output_buffer),
            environment.get_direct_buffer_address(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_capacity < MAX_TOOL_OUTPUT_BYTES || output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        // SAFETY: JNI verified the direct output-buffer capacity.
        let output = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        output.fill(0);
        let (closure, dependencies, required_packages, package_runtime) = {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return ERROR_INVALID_HANDLE;
            };
            let closure = match runtime.verified_aur_build_closure() {
                Ok(closure) => closure,
                Err(error) => return copy_package_error(&error, output),
            };
            let dependencies =
                match runtime.verified_aur_runtime_dependencies(&package_name, &version) {
                    Ok(dependencies) => dependencies,
                    Err(error) => return copy_package_error(&error, output),
                };
            let required_packages =
                match runtime.verified_aur_required_packages(&package_name, &version) {
                    Ok(packages) => packages,
                    Err(error) => return copy_package_error(&error, output),
                };
            let Some(package_runtime) = runtime.package_runtime().cloned() else {
                return ERROR_INVALID_STATE;
            };
            (closure, dependencies, required_packages, package_runtime)
        };
        if required_packages.len() != filenames.len() {
            return ERROR_INVALID_ARGUMENT;
        }
        let mut verified = Vec::with_capacity(required_packages.len());
        for (((descriptor, filename), required_package), index) in descriptor_values
            .iter()
            .zip(&filenames)
            .zip(&required_packages)
            .zip(0_usize..)
        {
            if index >= 256 {
                return ERROR_INVALID_ARGUMENT;
            }
            // SAFETY: Each descriptor is borrowed only for dup. Kotlin retains
            // all original ParcelFileDescriptors for this complete call.
            let borrowed = unsafe { BorrowedFd::borrow_raw(*descriptor) };
            let Ok(owned) = rustix::io::dup(borrowed) else {
                return ERROR_INVALID_ARGUMENT;
            };
            let mut archive = File::from(owned);
            let report = match archphene_builder::verify_copied_built_package(
                &mut archive,
                filename,
                &package_base,
                required_package,
                &version,
                &architecture,
                closure.as_bytes(),
                closure_sha256,
            ) {
                Ok(report) => report,
                Err(error) => return copy_package_error(&error, output),
            };
            verified.push((archive, report));
        }
        let dependency_names: Vec<&str> = dependencies.iter().map(String::as_str).collect();
        if let Err(error) = package_runtime.install_dependencies(&dependency_names) {
            return copy_package_error(&error, output);
        }
        let mut install_inputs: Vec<VerifiedAurArchive<'_>> = verified
            .iter_mut()
            .zip(&required_packages)
            .map(|((archive, report), package)| VerifiedAurArchive {
                source: archive,
                filename: &report.filename,
                package,
                version: &version,
                expected_bytes: report.archive_bytes,
                expected_sha256: report.sha256,
            })
            .collect();
        copy_tool_result(
            package_runtime.install_verified_aur_archives(&mut install_inputs, &package_name),
            output,
        )
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeReviewAur(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        architecture: jint,
        package_buffer: JByteBuffer,
        package_length: jint,
        rpc_buffer: JByteBuffer,
        rpc_length: jint,
        snapshot_buffer: JByteBuffer,
        snapshot_length: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        let (Ok(handle), Ok(package_length), Ok(rpc_length), Ok(snapshot_length)) = (
            u64::try_from(handle),
            usize::try_from(package_length),
            usize::try_from(rpc_length),
            usize::try_from(snapshot_length),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let architecture = match architecture {
            1 => RepositoryArchitecture::X86_64,
            2 => RepositoryArchitecture::Aarch64,
            _ => return ERROR_INVALID_ARGUMENT,
        };
        let (Ok(package_capacity), Ok(rpc_capacity), Ok(snapshot_capacity), Ok(output_capacity)) = (
            environment.get_direct_buffer_capacity(&package_buffer),
            environment.get_direct_buffer_capacity(&rpc_buffer),
            environment.get_direct_buffer_capacity(&snapshot_buffer),
            environment.get_direct_buffer_capacity(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if package_length == 0
            || package_length > package_capacity
            || package_length > 128
            || rpc_length == 0
            || rpc_length > rpc_capacity
            || rpc_length > MAX_AUR_RPC_BYTES
            || snapshot_length == 0
            || snapshot_length > snapshot_capacity
            || snapshot_length > MAX_AUR_SNAPSHOT_BYTES
            || output_capacity < MAX_AUR_REVIEW_BYTES
        {
            return ERROR_INVALID_ARGUMENT;
        }
        let (Ok(package_address), Ok(rpc_address), Ok(snapshot_address), Ok(output_address)) = (
            environment.get_direct_buffer_address(&package_buffer),
            environment.get_direct_buffer_address(&rpc_buffer),
            environment.get_direct_buffer_address(&snapshot_buffer),
            environment.get_direct_buffer_address(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if package_address.is_null()
            || rpc_address.is_null()
            || snapshot_address.is_null()
            || output_address.is_null()
            || ranges_overlap(
                output_address as usize,
                output_capacity,
                package_address as usize,
                package_length,
            )
            || ranges_overlap(
                output_address as usize,
                output_capacity,
                rpc_address as usize,
                rpc_length,
            )
            || ranges_overlap(
                output_address as usize,
                output_capacity,
                snapshot_address as usize,
                snapshot_length,
            )
        {
            return ERROR_INVALID_ARGUMENT;
        }
        {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return ERROR_INVALID_HANDLE;
            };
            if runtime.package_runtime().is_none() {
                return ERROR_INVALID_STATE;
            }
        }
        let package_bytes =
            unsafe { slice::from_raw_parts(package_address.cast_const(), package_length) };
        let Ok(package) = str::from_utf8(package_bytes) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let rpc = unsafe { slice::from_raw_parts(rpc_address.cast_const(), rpc_length) };
        let snapshot =
            unsafe { slice::from_raw_parts(snapshot_address.cast_const(), snapshot_length) };
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        let review = match review_aur_snapshot(rpc, snapshot, package, architecture) {
            Ok(review) => review,
            Err(error) => return copy_package_error(&error, destination),
        };
        let length = match review.write_wire(destination) {
            Ok(length) => length,
            Err(error) => return copy_package_error(&error, destination),
        };
        let Some(snapshot_sha256) = review.snapshot_sha256 else {
            return copy_package_error(&PackageRuntimeError::InvalidPayload, destination);
        };
        let package_runtime = {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return ERROR_INVALID_HANDLE;
            };
            let Some(package_runtime) = runtime.package_runtime() else {
                return ERROR_INVALID_STATE;
            };
            package_runtime.clone()
        };
        if let Err(error) = package_runtime.retain_reviewed_aur_snapshot(
            &review.package_base,
            snapshot_sha256,
            snapshot,
        ) {
            return copy_package_error(&error, destination);
        }
        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        runtime.retain_aur_review(review);
        i32::try_from(length).unwrap_or(ERROR_INTERNAL)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeOpenReviewedAurSnapshot(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        output_buffer: JByteBuffer,
    ) -> jint {
        open_reviewed_aur_input(environment, handle, None, output_buffer)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeOpenVerifiedAurSource(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        source_index: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(source_index) = usize::try_from(source_index) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if source_index >= 64 {
            return ERROR_INVALID_ARGUMENT;
        }
        open_reviewed_aur_input(environment, handle, Some(source_index), output_buffer)
    }

    fn open_reviewed_aur_input(
        environment: JNIEnv,
        handle: jlong,
        source_index: Option<usize>,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(handle) = u64::try_from(handle) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(output_capacity) = environment.get_direct_buffer_capacity(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_capacity < MAX_TOOL_OUTPUT_BYTES {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(output_address) = environment.get_direct_buffer_address(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        let result = {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return ERROR_INVALID_HANDLE;
            };
            match source_index {
                Some(index) => runtime.open_verified_aur_source(index),
                None => runtime.open_reviewed_aur_snapshot(),
            }
        };
        match result {
            Ok(file) => file.into_raw_fd(),
            Err(error) => copy_package_error(&error, destination),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeVerifiedCachedAurSourceSize(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        source_index: jint,
        output_buffer: JByteBuffer,
    ) -> jlong {
        let (Ok(handle), Ok(source_index)) = (u64::try_from(handle), usize::try_from(source_index))
        else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        let Ok(output_capacity) = environment.get_direct_buffer_capacity(&output_buffer) else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        if source_index >= 64 || output_capacity < MAX_TOOL_OUTPUT_BYTES {
            return i64::from(ERROR_INVALID_ARGUMENT);
        }
        let Ok(output_address) = environment.get_direct_buffer_address(&output_buffer) else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        if output_address.is_null() {
            return i64::from(ERROR_INVALID_ARGUMENT);
        }
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        let (package_runtime, filename, expected_checksum) = {
            let Ok(mut registry) = registry().lock() else {
                return i64::from(ERROR_INTERNAL);
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return i64::from(ERROR_INVALID_HANDLE);
            };
            match runtime.aur_source_cache_candidate(source_index) {
                Ok(candidate) => candidate,
                Err(error) => return i64::from(copy_package_error(&error, destination)),
            }
        };
        match package_runtime.verified_aur_source_size(
            &filename,
            expected_checksum,
            MAX_AUR_SOURCE_BYTES,
        ) {
            Ok(Some(length)) => i64::try_from(length).unwrap_or(i64::from(ERROR_INTERNAL)),
            Ok(None) => 0,
            Err(error) => i64::from(copy_package_error(&error, destination)),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeBeginAurSourceDownload(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        source_index: jint,
        maximum_size: jlong,
        output_buffer: JByteBuffer,
    ) -> jint {
        let (Ok(handle), Ok(source_index), Ok(maximum_size)) = (
            u64::try_from(handle),
            usize::try_from(source_index),
            u64::try_from(maximum_size),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(output_capacity) = environment.get_direct_buffer_capacity(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if source_index >= 64
            || maximum_size == 0
            || maximum_size > MAX_AUR_SOURCE_BYTES
            || output_capacity < MAX_TOOL_OUTPUT_BYTES
        {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(output_address) = environment.get_direct_buffer_address(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        let (file, endpoint, filename) =
            match runtime.begin_aur_source_download(source_index, maximum_size) {
                Ok(result) => result,
                Err(error) => return copy_package_error(&error, destination),
            };
        let Some(length) = 8_usize
            .checked_add(endpoint.len())
            .and_then(|length| length.checked_add(filename.len()))
        else {
            runtime.cancel_aur_source_download();
            return ERROR_INTERNAL;
        };
        if length > destination.len() {
            runtime.cancel_aur_source_download();
            return ERROR_INTERNAL;
        }
        let Ok(endpoint_length) = u32::try_from(endpoint.len()) else {
            runtime.cancel_aur_source_download();
            return ERROR_INTERNAL;
        };
        let Ok(filename_length) = u32::try_from(filename.len()) else {
            runtime.cancel_aur_source_download();
            return ERROR_INTERNAL;
        };
        destination[..4].copy_from_slice(&endpoint_length.to_le_bytes());
        destination[4..8].copy_from_slice(&filename_length.to_le_bytes());
        destination[8..8 + endpoint.len()].copy_from_slice(endpoint.as_bytes());
        destination[8 + endpoint.len()..length].copy_from_slice(filename.as_bytes());
        file.into_raw_fd()
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeFinishAurSourceDownload(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        success: jboolean,
        output_buffer: JByteBuffer,
    ) -> jlong {
        let Ok(handle) = u64::try_from(handle) else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        let Ok(output_capacity) = environment.get_direct_buffer_capacity(&output_buffer) else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        if output_capacity < MAX_TOOL_OUTPUT_BYTES {
            return i64::from(ERROR_INVALID_ARGUMENT);
        }
        let Ok(output_address) = environment.get_direct_buffer_address(&output_buffer) else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        if output_address.is_null() {
            return i64::from(ERROR_INVALID_ARGUMENT);
        }
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        if success == JNI_FALSE {
            let Ok(mut registry) = registry().lock() else {
                return i64::from(ERROR_INTERNAL);
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return i64::from(ERROR_INVALID_HANDLE);
            };
            runtime.cancel_aur_source_download();
            return 0;
        }
        let download = {
            let Ok(mut registry) = registry().lock() else {
                return i64::from(ERROR_INTERNAL);
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return i64::from(ERROR_INVALID_HANDLE);
            };
            match runtime.take_aur_source_download() {
                Ok(download) => download,
                Err(error) => return i64::from(copy_package_error(&error, destination)),
            }
        };
        match download.finish() {
            Ok(length) => i64::try_from(length).unwrap_or(i64::from(ERROR_INTERNAL)),
            Err(error) => i64::from(copy_package_error(&error, destination)),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeResolveAurSnapshotPath(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        package_buffer: JByteBuffer,
        package_length: jint,
        rpc_buffer: JByteBuffer,
        rpc_length: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        let (Ok(handle), Ok(package_length), Ok(rpc_length)) = (
            u64::try_from(handle),
            usize::try_from(package_length),
            usize::try_from(rpc_length),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let (Ok(package_capacity), Ok(rpc_capacity), Ok(output_capacity)) = (
            environment.get_direct_buffer_capacity(&package_buffer),
            environment.get_direct_buffer_capacity(&rpc_buffer),
            environment.get_direct_buffer_capacity(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if package_length == 0
            || package_length > package_capacity
            || package_length > 128
            || rpc_length == 0
            || rpc_length > rpc_capacity
            || rpc_length > MAX_AUR_RPC_BYTES
            || output_capacity < 4096
        {
            return ERROR_INVALID_ARGUMENT;
        }
        let (Ok(package_address), Ok(rpc_address), Ok(output_address)) = (
            environment.get_direct_buffer_address(&package_buffer),
            environment.get_direct_buffer_address(&rpc_buffer),
            environment.get_direct_buffer_address(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if package_address.is_null()
            || rpc_address.is_null()
            || output_address.is_null()
            || ranges_overlap(
                output_address as usize,
                output_capacity,
                package_address as usize,
                package_length,
            )
            || ranges_overlap(
                output_address as usize,
                output_capacity,
                rpc_address as usize,
                rpc_length,
            )
        {
            return ERROR_INVALID_ARGUMENT;
        }
        {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return ERROR_INVALID_HANDLE;
            };
            if runtime.package_runtime().is_none() {
                return ERROR_INVALID_STATE;
            }
        }
        let package_bytes =
            unsafe { slice::from_raw_parts(package_address.cast_const(), package_length) };
        let Ok(package) = str::from_utf8(package_bytes) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let rpc = unsafe { slice::from_raw_parts(rpc_address.cast_const(), rpc_length) };
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        match aur_snapshot_path(rpc, package) {
            Ok(path) if path.len() <= destination.len() => {
                destination[..path.len()].copy_from_slice(path.as_bytes());
                i32::try_from(path.len()).unwrap_or(ERROR_INTERNAL)
            }
            Ok(_) => ERROR_INTERNAL,
            Err(error) => copy_package_error(&error, destination),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativePackageCommand(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        action: jint,
        package_buffer: JByteBuffer,
        package_length: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        let (Ok(handle), Ok(package_length)) =
            (u64::try_from(handle), usize::try_from(package_length))
        else {
            return ERROR_INVALID_ARGUMENT;
        };
        let (Ok(package_capacity), Ok(output_capacity)) = (
            environment.get_direct_buffer_capacity(&package_buffer),
            environment.get_direct_buffer_capacity(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if package_length == 0
            || package_length > package_capacity
            || package_length > 128
            || output_capacity < MAX_TOOL_OUTPUT_BYTES
        {
            return ERROR_INVALID_ARGUMENT;
        }
        let (Ok(package_address), Ok(output_address)) = (
            environment.get_direct_buffer_address(&package_buffer),
            environment.get_direct_buffer_address(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if package_address.is_null() || output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let package_bytes =
            unsafe { slice::from_raw_parts(package_address.cast_const(), package_length) };
        let Ok(package) = std::str::from_utf8(package_bytes) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let package_runtime = {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return ERROR_INVALID_HANDLE;
            };
            let Some(package_runtime) = runtime.package_runtime() else {
                return ERROR_INVALID_STATE;
            };
            package_runtime.clone()
        };
        let result = match action {
            1 => package_runtime.installed_version(package),
            2 => package_runtime.install(package),
            3 => package_runtime.remove(package),
            4 => package_runtime.installed_origin(package),
            5 => package_runtime.installation_bytes(package),
            6 => package_runtime.pending_mutation(package),
            7 => package_runtime.repair_pending_mutation(package),
            8 => package_runtime.update(package),
            9 => package_runtime.available_version_state(package),
            _ => return ERROR_INVALID_ARGUMENT,
        };
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        copy_tool_result(result, destination)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeListInstalledPackages(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        offset: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        let (Ok(handle), Ok(offset)) = (u64::try_from(handle), usize::try_from(offset)) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(output_capacity) = environment.get_direct_buffer_capacity(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_capacity < MAX_TOOL_OUTPUT_BYTES {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(output_address) = environment.get_direct_buffer_address(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let result = {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return ERROR_INVALID_HANDLE;
            };
            if offset == 0 {
                if let Err(error) = runtime.refresh_installed_packages() {
                    let destination =
                        unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
                    return copy_package_error(&error, destination);
                }
            }
            runtime.installed_package_page(offset)
        };
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        copy_tool_result(result, destination)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeListDesktopEntries(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        offset: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        let (Ok(handle), Ok(offset)) = (u64::try_from(handle), usize::try_from(offset)) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(output_capacity) = environment.get_direct_buffer_capacity(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_capacity < MAX_TOOL_OUTPUT_BYTES {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(output_address) = environment.get_direct_buffer_address(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let result = {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return ERROR_INVALID_HANDLE;
            };
            if offset == 0 {
                if let Err(error) = runtime.refresh_desktop_entries() {
                    let destination =
                        unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
                    return copy_package_error(&error, destination);
                }
            }
            runtime.desktop_entry_page(offset)
        };
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        copy_tool_result(result, destination)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeLauncherRegistryStatus(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(handle) = u64::try_from(handle) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(output_capacity) = environment.get_direct_buffer_capacity(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_capacity < 128 {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(output_address) = environment.get_direct_buffer_address(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let summary = {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return ERROR_INVALID_HANDLE;
            };
            let Some(summary) = runtime.launcher_registry_summary() else {
                return ERROR_INVALID_STATE;
            };
            summary
        };
        let encoded = format!(
            "L3\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\n",
            summary.generation,
            summary.total,
            summary.needs_publish,
            summary.current,
            summary.needs_removal,
            summary.active,
            summary.failed,
            summary.cancelled,
            summary.dismissed,
            summary.needs_review,
        );
        if encoded.len() > output_capacity {
            return ERROR_INTERNAL;
        }
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        destination[..encoded.len()].copy_from_slice(encoded.as_bytes());
        i32::try_from(encoded.len()).unwrap_or(i32::MAX)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeLauncherRegistryPage(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        offset: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        let (Ok(handle), Ok(offset)) = (u64::try_from(handle), usize::try_from(offset)) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(output_capacity) = environment.get_direct_buffer_capacity(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_capacity < 1024 {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(output_address) = environment.get_direct_buffer_address(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let encoded = {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return ERROR_INVALID_HANDLE;
            };
            let Some(launchers) = runtime.launcher_registry() else {
                return ERROR_INVALID_STATE;
            };
            if offset > launchers.descriptors().len() {
                return ERROR_INVALID_ARGUMENT;
            }
            let end = offset.saturating_add(8).min(launchers.descriptors().len());
            let mut encoded = format!("P2\t{}\t{}\n", end, launchers.descriptors().len());
            for descriptor in &launchers.descriptors()[offset..end] {
                let descriptor_id = descriptor.descriptor_id_hex();
                let descriptor_id =
                    str::from_utf8(&descriptor_id).expect("hex launcher descriptor");
                encoded.push_str(&format!(
                    "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\n",
                    descriptor.android_package,
                    descriptor_id,
                    descriptor.desired_generation,
                    descriptor.published_generation,
                    descriptor.pending_generation,
                    descriptor.status as u8,
                    descriptor.name,
                    descriptor.source_package.as_deref().unwrap_or(""),
                ));
            }
            encoded
        };
        if encoded.len() > output_capacity {
            return ERROR_INTERNAL;
        }
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        destination[..encoded.len()].copy_from_slice(encoded.as_bytes());
        i32::try_from(encoded.len()).unwrap_or(i32::MAX)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeAuthorizeLauncher(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        request_buffer: JByteBuffer,
        request_length: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        let (Ok(handle), Ok(request_length)) =
            (u64::try_from(handle), usize::try_from(request_length))
        else {
            return ERROR_INVALID_ARGUMENT;
        };
        let (Ok(request_capacity), Ok(output_capacity)) = (
            environment.get_direct_buffer_capacity(&request_buffer),
            environment.get_direct_buffer_capacity(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if request_length == 0
            || request_length > request_capacity
            || request_length > 192
            || output_capacity < 512
        {
            return ERROR_INVALID_ARGUMENT;
        }
        let (Ok(request_address), Ok(output_address)) = (
            environment.get_direct_buffer_address(&request_buffer),
            environment.get_direct_buffer_address(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if request_address.is_null() || output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let request =
            unsafe { slice::from_raw_parts(request_address.cast_const(), request_length) };
        let Ok(request) = str::from_utf8(request) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Some(request) = request.strip_suffix('\n') else {
            return ERROR_INVALID_ARGUMENT;
        };
        let mut fields = request.split('\t');
        let (Some("A1"), Some(android_package), Some(descriptor_id), Some(generation), None) = (
            fields.next(),
            fields.next(),
            fields.next(),
            fields.next(),
            fields.next(),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if android_package.len() != 53
            || !android_package.starts_with("org.archphene.linux.p")
            || !android_package
                .bytes()
                .skip(21)
                .all(|byte| byte.is_ascii_digit() || matches!(byte, b'a'..=b'f'))
            || descriptor_id.len() != 64
            || !descriptor_id
                .bytes()
                .all(|byte| byte.is_ascii_digit() || matches!(byte, b'a'..=b'f'))
        {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(generation) = generation.parse::<u64>() else {
            return ERROR_INVALID_ARGUMENT;
        };
        let authorization = {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return ERROR_INVALID_HANDLE;
            };
            runtime.authorize_launcher(android_package, descriptor_id, generation)
        };
        let Some(authorization) = authorization else {
            return ERROR_LAUNCHER;
        };
        if authorization.label.contains(['\t', '\n', '\r', '\0']) {
            return ERROR_INTERNAL;
        }
        let encoded = format!(
            "A1\t{}\t{}\n",
            u8::from(authorization.terminal),
            authorization.label,
        );
        if encoded.len() > output_capacity {
            return ERROR_INTERNAL;
        }
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        destination[..encoded.len()].copy_from_slice(encoded.as_bytes());
        i32::try_from(encoded.len()).unwrap_or(i32::MAX)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeOpenLauncherProcess(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        request_buffer: JByteBuffer,
        request_length: jint,
        output_buffer: JByteBuffer,
    ) -> jlong {
        let (Ok(handle), Ok(request_length)) =
            (u64::try_from(handle), usize::try_from(request_length))
        else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        let (Ok(request_capacity), Ok(output_capacity)) = (
            environment.get_direct_buffer_capacity(&request_buffer),
            environment.get_direct_buffer_capacity(&output_buffer),
        ) else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        if request_length == 0
            || request_length > request_capacity
            || request_length > 1024
            || output_capacity < 512
        {
            return i64::from(ERROR_INVALID_ARGUMENT);
        }
        let (Ok(request_address), Ok(output_address)) = (
            environment.get_direct_buffer_address(&request_buffer),
            environment.get_direct_buffer_address(&output_buffer),
        ) else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        if request_address.is_null() || output_address.is_null() {
            return i64::from(ERROR_INVALID_ARGUMENT);
        }
        let request =
            unsafe { slice::from_raw_parts(request_address.cast_const(), request_length) };
        let Ok(request) = str::from_utf8(request) else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        let Some(request) = request.strip_suffix('\n') else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        let mut fields = request.split('\t');
        let Some(version) = fields.next() else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        let (Some(android_package), Some(descriptor_id), Some(generation), Some(wayland_display)) =
            (fields.next(), fields.next(), fields.next(), fields.next())
        else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        let (appearance, portal_bus_address) = if version == "G1" {
            if fields.next().is_some() {
                return i64::from(ERROR_INVALID_ARGUMENT);
            }
            (GuiAppearance::default(), None)
        } else if version == "G2" || version == "G3" {
            let (
                Some(dark),
                Some(font_percent),
                Some(control_visual_dp),
                Some(control_target_dp),
                Some(accent),
                Some(background),
                Some(foreground),
            ) = (
                fields.next(),
                fields.next(),
                fields.next(),
                fields.next(),
                fields.next(),
                fields.next(),
                fields.next(),
            )
            else {
                return i64::from(ERROR_INVALID_ARGUMENT);
            };
            let portal_bus_address = if version == "G3" {
                let Some(address) = fields.next() else {
                    return i64::from(ERROR_INVALID_ARGUMENT);
                };
                if address.len() > 256
                    || !address.starts_with("unix:path=/data/")
                    || address
                        .bytes()
                        .any(|byte| matches!(byte, 0 | b'\t' | b'\n' | b'\r'))
                {
                    return i64::from(ERROR_INVALID_ARGUMENT);
                }
                Some(address)
            } else {
                None
            };
            if fields.next().is_some() {
                return i64::from(ERROR_INVALID_ARGUMENT);
            }
            let dark = match dark {
                "0" => false,
                "1" => true,
                _ => return i64::from(ERROR_INVALID_ARGUMENT),
            };
            let (Ok(font_percent), Ok(control_visual_dp), Ok(control_target_dp)) = (
                font_percent.parse::<u16>(),
                control_visual_dp.parse::<u16>(),
                control_target_dp.parse::<u16>(),
            ) else {
                return i64::from(ERROR_INVALID_ARGUMENT);
            };
            let (Some(accent), Some(background), Some(foreground)) = (
                parse_rgb_hex(accent),
                parse_rgb_hex(background),
                parse_rgb_hex(foreground),
            ) else {
                return i64::from(ERROR_INVALID_ARGUMENT);
            };
            match GuiAppearance::new(
                dark,
                font_percent,
                control_visual_dp,
                control_target_dp,
                accent,
                background,
                foreground,
            ) {
                Ok(appearance) => (appearance, portal_bus_address),
                Err(_) => return i64::from(ERROR_INVALID_ARGUMENT),
            }
        } else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        if android_package.len() != 53
            || !android_package.starts_with("org.archphene.linux.p")
            || !android_package
                .bytes()
                .skip(21)
                .all(|byte| byte.is_ascii_digit() || matches!(byte, b'a'..=b'f'))
            || descriptor_id.len() != 64
            || !descriptor_id
                .bytes()
                .all(|byte| byte.is_ascii_digit() || matches!(byte, b'a'..=b'f'))
            || wayland_display.is_empty()
            || wayland_display.len() > 64
            || !wayland_display
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
        {
            return i64::from(ERROR_INVALID_ARGUMENT);
        }
        let Ok(generation) = generation.parse::<u64>() else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        let result = {
            let Ok(mut registry) = registry().lock() else {
                return i64::from(ERROR_INTERNAL);
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return i64::from(ERROR_INVALID_HANDLE);
            };
            runtime.open_launcher_process(
                android_package,
                descriptor_id,
                generation,
                wayland_display,
                appearance,
                portal_bus_address,
            )
        };
        match result {
            Ok(launcher_handle) => {
                i64::try_from(launcher_handle).unwrap_or(i64::from(ERROR_INTERNAL))
            }
            Err(error) => {
                let destination =
                    unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
                let diagnostic = error.to_string();
                let length = diagnostic.len().min(destination.len().saturating_sub(1));
                destination[..length].copy_from_slice(&diagnostic.as_bytes()[..length]);
                destination[length] = 0;
                i64::from(ERROR_LAUNCHER)
            }
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeUpdateGuiColors(
        _environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        dark: jboolean,
        accent: jint,
        background: jint,
        foreground: jint,
    ) -> jint {
        let Ok(handle) = u64::try_from(handle) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let dark = match dark {
            JNI_FALSE => false,
            JNI_TRUE => true,
            _ => return ERROR_INVALID_ARGUMENT,
        };
        let rgb = |color: jint| {
            let color = color as u32;
            [
                ((color >> 16) & 0xff) as u8,
                ((color >> 8) & 0xff) as u8,
                (color & 0xff) as u8,
            ]
        };
        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        match runtime.update_gui_colors(dark, rgb(accent), rgb(background), rgb(foreground)) {
            Ok(()) => 0,
            Err(_) => ERROR_PACKAGE_RUNTIME,
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeCloseLauncherProcess(
        _environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        launcher_handle: jlong,
    ) -> jint {
        let (Ok(handle), Ok(launcher_handle)) =
            (u64::try_from(handle), u64::try_from(launcher_handle))
        else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        match runtime.close_launcher_process(launcher_handle) {
            Ok(()) => 0,
            Err(_) => ERROR_LAUNCHER,
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeLauncherProcessExitStatus(
        _environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        launcher_handle: jlong,
    ) -> jlong {
        let (Ok(handle), Ok(launcher_handle)) =
            (u64::try_from(handle), u64::try_from(launcher_handle))
        else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        let Ok(mut registry) = registry().lock() else {
            return i64::from(ERROR_INTERNAL);
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return i64::from(ERROR_INVALID_HANDLE);
        };
        match runtime.launcher_process_exit_status(launcher_handle) {
            Ok(None) => 0,
            Ok(Some(status)) => (i64::from(u32::from_ne_bytes(status.to_ne_bytes())) << 1) | 1,
            Err(_) => i64::from(ERROR_LAUNCHER),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeReadLauncherProcessLog(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        launcher_handle: jlong,
        output_buffer: JByteBuffer,
    ) -> jint {
        let (Ok(handle), Ok(launcher_handle), Ok(output_capacity)) = (
            u64::try_from(handle),
            u64::try_from(launcher_handle),
            environment.get_direct_buffer_capacity(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_capacity == 0 || output_capacity > archphene_process::MAX_GUI_LOG_BYTES {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(output_address) = environment.get_direct_buffer_address(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let output = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        match runtime.launcher_process_logs(launcher_handle, output) {
            Ok(length) => i32::try_from(length).unwrap_or(i32::MAX),
            Err(_) => ERROR_LAUNCHER,
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeClaimLauncherPublish(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(handle) = u64::try_from(handle) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(output_capacity) = environment.get_direct_buffer_capacity(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_capacity < 1024 {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(output_address) = environment.get_direct_buffer_address(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let work = {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return ERROR_INVALID_HANDLE;
            };
            match runtime.claim_launcher_publish() {
                Ok(work) => work,
                Err(_) => return ERROR_LAUNCHER,
            }
        };
        let Some(work) = work else {
            return 0;
        };
        let descriptor_id = str::from_utf8(&work.descriptor_id_hex).expect("hex descriptor");
        let icon_sha256 = work.icon_sha256.map(|digest| {
            const HEX: &[u8; 16] = b"0123456789abcdef";
            let mut encoded = String::with_capacity(64);
            for byte in digest {
                encoded.push(char::from(HEX[usize::from(byte >> 4)]));
                encoded.push(char::from(HEX[usize::from(byte & 0x0f)]));
            }
            encoded
        });
        let encoded = format!(
            "W3\t{}\t{}\t{}\t{}\t{}\t{}\t{}\n",
            work.android_package,
            descriptor_id,
            work.generation,
            work.label,
            work.capabilities,
            work.icon_path.as_deref().unwrap_or(""),
            icon_sha256.as_deref().unwrap_or(""),
        );
        if encoded.len() > output_capacity {
            return ERROR_INTERNAL;
        }
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        destination[..encoded.len()].copy_from_slice(encoded.as_bytes());
        i32::try_from(encoded.len()).unwrap_or(i32::MAX)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeClaimLauncherRemoval(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(handle) = u64::try_from(handle) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(output_capacity) = environment.get_direct_buffer_capacity(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_capacity < 128 {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(output_address) = environment.get_direct_buffer_address(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let work = {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return ERROR_INVALID_HANDLE;
            };
            match runtime.claim_launcher_removal() {
                Ok(work) => work,
                Err(_) => return ERROR_LAUNCHER,
            }
        };
        let Some(work) = work else {
            return 0;
        };
        let encoded = format!("R1\t{}\t{}\n", work.android_package, work.generation);
        if encoded.len() > output_capacity {
            return ERROR_INTERNAL;
        }
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        destination[..encoded.len()].copy_from_slice(encoded.as_bytes());
        i32::try_from(encoded.len()).unwrap_or(i32::MAX)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeLauncherTransition(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        request_buffer: JByteBuffer,
        request_length: jint,
    ) -> jint {
        let (Ok(handle), Ok(request_length)) =
            (u64::try_from(handle), usize::try_from(request_length))
        else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(request_capacity) = environment.get_direct_buffer_capacity(&request_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if request_length == 0 || request_length > request_capacity || request_length > 160 {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(request_address) = environment.get_direct_buffer_address(&request_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if request_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let request =
            unsafe { slice::from_raw_parts(request_address.cast_const(), request_length) };
        let Ok(request) = str::from_utf8(request) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Some(request) = request.strip_suffix('\n') else {
            return ERROR_INVALID_ARGUMENT;
        };
        let mut fields = request.split('\t');
        if fields.next() != Some("T1") {
            return ERROR_INVALID_ARGUMENT;
        }
        let (Some(action), Some(android_package), Some(generation), None) =
            (fields.next(), fields.next(), fields.next(), fields.next())
        else {
            return ERROR_INVALID_ARGUMENT;
        };
        if android_package.len() != 53
            || !android_package.starts_with("org.archphene.linux.p")
            || !android_package
                .bytes()
                .skip(21)
                .all(|byte| byte.is_ascii_digit() || matches!(byte, b'a'..=b'f'))
        {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(generation) = generation.parse::<u64>() else {
            return ERROR_INVALID_ARGUMENT;
        };
        let result = {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return ERROR_INVALID_HANDLE;
            };
            match action {
                "awaiting-install" => {
                    runtime.launcher_awaiting_install(android_package, generation)
                }
                "installed" => runtime.launcher_confirm_installed(android_package, generation),
                "failed" => runtime.launcher_publish_failed(android_package, generation),
                "cancelled" => runtime.launcher_publish_cancelled(android_package, generation),
                "retry" => runtime.launcher_retry(android_package, generation),
                "dismiss" => runtime.launcher_dismiss_cancelled(android_package, generation),
                "template-stale" if generation != 0 => {
                    runtime.launcher_template_stale(android_package, generation)
                }
                "replace-untrusted" if generation != 0 => {
                    runtime.launcher_untrusted_replacement_removal(android_package, generation)
                }
                "removed" => runtime.launcher_confirm_removed(android_package),
                "quarantined" if generation == 0 => runtime.launcher_quarantine(android_package),
                "absent" if generation == 0 => {
                    runtime.reconcile_android_launcher(android_package, None)
                }
                "present" if generation != 0 => {
                    runtime.reconcile_android_launcher(android_package, Some(generation))
                }
                _ => return ERROR_INVALID_ARGUMENT,
            }
        };
        match result {
            Ok(()) => 0,
            Err(_) => ERROR_LAUNCHER,
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeReviewLaunchers(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        request_buffer: JByteBuffer,
        request_length: jint,
    ) -> jint {
        let (Ok(handle), Ok(request_length)) =
            (u64::try_from(handle), usize::try_from(request_length))
        else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(request_capacity) = environment.get_direct_buffer_capacity(&request_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if request_length == 0
            || request_length > request_capacity
            || request_length > MAX_LAUNCHER_REVIEW_REQUEST_BYTES
        {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(request_address) = environment.get_direct_buffer_address(&request_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if request_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let request =
            unsafe { slice::from_raw_parts(request_address.cast_const(), request_length) };
        let Ok(request) = str::from_utf8(request) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Some(request) = request.strip_suffix('\n') else {
            return ERROR_INVALID_ARGUMENT;
        };
        let mut lines = request.split('\n');
        let Some(header) = lines.next() else {
            return ERROR_INVALID_ARGUMENT;
        };
        let mut header_fields = header.split('\t');
        let (Some("B1"), Some(count), None) = (
            header_fields.next(),
            header_fields.next(),
            header_fields.next(),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(count) = count.parse::<usize>() else {
            return ERROR_INVALID_ARGUMENT;
        };
        if count == 0 || count > MAX_LAUNCHER_DESCRIPTORS {
            return ERROR_INVALID_ARGUMENT;
        }
        let mut decisions = Vec::with_capacity(count);
        for line in lines {
            let mut fields = line.split('\t');
            let (Some(android_package), Some(generation), Some(publish), None) =
                (fields.next(), fields.next(), fields.next(), fields.next())
            else {
                return ERROR_INVALID_ARGUMENT;
            };
            if android_package.len() != 53
                || !android_package.starts_with("org.archphene.linux.p")
                || !android_package
                    .bytes()
                    .skip(21)
                    .all(|byte| byte.is_ascii_digit() || matches!(byte, b'a'..=b'f'))
            {
                return ERROR_INVALID_ARGUMENT;
            }
            let Ok(desired_generation) = generation.parse::<u64>() else {
                return ERROR_INVALID_ARGUMENT;
            };
            if desired_generation == 0 || desired_generation > i32::MAX as u64 {
                return ERROR_INVALID_ARGUMENT;
            }
            let publish = match publish {
                "0" => false,
                "1" => true,
                _ => return ERROR_INVALID_ARGUMENT,
            };
            decisions.push(LauncherReviewDecision {
                android_package: android_package.to_owned(),
                desired_generation,
                publish,
            });
        }
        if decisions.len() != count {
            return ERROR_INVALID_ARGUMENT;
        }
        let result = {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return ERROR_INVALID_HANDLE;
            };
            runtime.review_launchers(&decisions)
        };
        match result {
            Ok(()) => 0,
            Err(_) => ERROR_LAUNCHER,
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeRunCommand(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        request_buffer: JByteBuffer,
        request_length: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        let (Ok(handle), Ok(request_length)) =
            (u64::try_from(handle), usize::try_from(request_length))
        else {
            return ERROR_INVALID_ARGUMENT;
        };
        let (Ok(request_capacity), Ok(output_capacity)) = (
            environment.get_direct_buffer_capacity(&request_buffer),
            environment.get_direct_buffer_capacity(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if request_length == 0
            || request_length > request_capacity
            || request_length > MAX_COMMAND_REQUEST_BYTES
            || output_capacity < MAX_TOOL_OUTPUT_BYTES
        {
            return ERROR_INVALID_ARGUMENT;
        }
        let (Ok(request_address), Ok(output_address)) = (
            environment.get_direct_buffer_address(&request_buffer),
            environment.get_direct_buffer_address(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if request_address.is_null() || output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let request_bytes =
            unsafe { slice::from_raw_parts(request_address.cast_const(), request_length) };
        let mut arguments = [""; MAX_COMMAND_ARGUMENTS];
        let Ok((command, argument_count)) = decode_command_request(request_bytes, &mut arguments)
        else {
            return ERROR_INVALID_ARGUMENT;
        };
        let command_environment = {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return ERROR_INVALID_HANDLE;
            };
            let Some(package_runtime) = runtime.package_runtime() else {
                return ERROR_INVALID_STATE;
            };
            match package_runtime.command_environment() {
                Ok(command_environment) => command_environment,
                Err(error) => {
                    let destination =
                        unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
                    return copy_package_error(&error, destination);
                }
            }
        };
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        let output = match command_environment.run(command, &arguments[..argument_count]) {
            Ok(output) => output,
            Err(error) => return copy_process_error(&error, destination),
        };
        let mut writer = &mut destination[..MAX_TOOL_OUTPUT_BYTES];
        if write!(writer, "{}\n", output.exit_code()).is_err()
            || output.as_bytes().len() > MAX_COMMAND_OUTPUT_BYTES
            || writer.write_all(output.as_bytes()).is_err()
        {
            return ERROR_INTERNAL;
        }
        i32::try_from(MAX_TOOL_OUTPUT_BYTES - writer.len()).unwrap_or(i32::MAX)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeOpenPty(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        request_buffer: JByteBuffer,
        request_length: jint,
        rows: jint,
        columns: jint,
        output_buffer: JByteBuffer,
    ) -> jlong {
        let (Ok(handle), Ok(request_length), Ok(rows), Ok(columns)) = (
            u64::try_from(handle),
            usize::try_from(request_length),
            u16::try_from(rows),
            u16::try_from(columns),
        ) else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        let (Ok(request_capacity), Ok(output_capacity)) = (
            environment.get_direct_buffer_capacity(&request_buffer),
            environment.get_direct_buffer_capacity(&output_buffer),
        ) else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        if request_length == 0
            || request_length > request_capacity
            || request_length > MAX_COMMAND_REQUEST_BYTES
            || output_capacity < MAX_TOOL_OUTPUT_BYTES
        {
            return i64::from(ERROR_INVALID_ARGUMENT);
        }
        let (Ok(request_address), Ok(output_address)) = (
            environment.get_direct_buffer_address(&request_buffer),
            environment.get_direct_buffer_address(&output_buffer),
        ) else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        if request_address.is_null() || output_address.is_null() {
            return i64::from(ERROR_INVALID_ARGUMENT);
        }
        let request_bytes =
            unsafe { slice::from_raw_parts(request_address.cast_const(), request_length) };
        let mut arguments = [""; MAX_COMMAND_ARGUMENTS];
        let Ok((command, argument_count)) = decode_command_request(request_bytes, &mut arguments)
        else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        let Ok(mut registry) = registry().lock() else {
            return i64::from(ERROR_INTERNAL);
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return i64::from(ERROR_INVALID_HANDLE);
        };
        match runtime.open_pty(command, &arguments[..argument_count], rows, columns) {
            Ok(pty_handle) => i64::try_from(pty_handle).unwrap_or(i64::from(ERROR_INTERNAL)),
            Err(error) => i64::from(copy_package_error(&error, destination)),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativePtyIo(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        pty_handle: jlong,
        write_operation: jboolean,
        buffer: JByteBuffer,
        byte_count: jint,
    ) -> jint {
        let (Ok(handle), Ok(pty_handle), Ok(byte_count)) = (
            u64::try_from(handle),
            u64::try_from(pty_handle),
            usize::try_from(byte_count),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(capacity) = environment.get_direct_buffer_capacity(&buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if byte_count == 0 || byte_count > capacity || byte_count > MAX_PTY_TRANSFER_BYTES {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(address) = environment.get_direct_buffer_address(&buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        let result = if write_operation != JNI_FALSE {
            let input = unsafe { slice::from_raw_parts(address.cast_const(), byte_count) };
            runtime.write_pty(pty_handle, input)
        } else {
            let output = unsafe { slice::from_raw_parts_mut(address, byte_count) };
            runtime.read_pty(pty_handle, output)
        };
        match result {
            Ok(length) => i32::try_from(length).unwrap_or(i32::MAX),
            Err(_) => ERROR_PROCESS,
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeReadTerminalDamage(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        pty_handle: jlong,
        full_snapshot: jboolean,
        viewport_offset: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        let (Ok(handle), Ok(pty_handle), Ok(viewport_offset)) = (
            u64::try_from(handle),
            u64::try_from(pty_handle),
            u32::try_from(viewport_offset),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(output_capacity) = environment.get_direct_buffer_capacity(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_capacity < MAX_TERMINAL_DAMAGE_BYTES {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(output_address) = environment.get_direct_buffer_address(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let destination =
            unsafe { slice::from_raw_parts_mut(output_address, MAX_TERMINAL_DAMAGE_BYTES) };
        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        match runtime.write_terminal_damage(
            pty_handle,
            destination,
            full_snapshot != JNI_FALSE,
            viewport_offset,
        ) {
            Ok(length) => i32::try_from(length).unwrap_or(i32::MAX),
            Err(_) => ERROR_PROCESS,
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeCopyTerminalSelection(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        pty_handle: jlong,
        origin_epoch: jlong,
        start_row: jint,
        start_column: jint,
        end_row: jint,
        end_column: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        let (
            Ok(handle),
            Ok(pty_handle),
            Ok(origin_epoch),
            Ok(start_row),
            Ok(start_column),
            Ok(end_row),
            Ok(end_column),
        ) = (
            u64::try_from(handle),
            u64::try_from(pty_handle),
            u64::try_from(origin_epoch),
            u32::try_from(start_row),
            u16::try_from(start_column),
            u32::try_from(end_row),
            u16::try_from(end_column),
        )
        else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(output_capacity) = environment.get_direct_buffer_capacity(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_capacity < MAX_TERMINAL_SELECTION_BYTES {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(output_address) = environment.get_direct_buffer_address(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let destination =
            unsafe { slice::from_raw_parts_mut(output_address, MAX_TERMINAL_SELECTION_BYTES) };
        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        match runtime.write_terminal_selection(
            pty_handle,
            destination,
            origin_epoch,
            start_row,
            start_column,
            end_row,
            end_column,
        ) {
            Ok(length) => i32::try_from(length).unwrap_or(i32::MAX),
            Err(_) => ERROR_PROCESS,
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeReadTerminalClipboard(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        pty_handle: jlong,
        output_buffer: JByteBuffer,
    ) -> jint {
        let (Ok(handle), Ok(pty_handle)) = (u64::try_from(handle), u64::try_from(pty_handle))
        else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(output_capacity) = environment.get_direct_buffer_capacity(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_capacity < MAX_TERMINAL_CLIPBOARD_BYTES {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(output_address) = environment.get_direct_buffer_address(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let destination =
            unsafe { slice::from_raw_parts_mut(output_address, MAX_TERMINAL_CLIPBOARD_BYTES) };
        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        match runtime.write_terminal_clipboard(pty_handle, destination) {
            Ok(None) => 0,
            // Reserve zero for "no event"; this also preserves an explicit
            // empty clipboard write as the positive value one.
            Ok(Some(length)) => i32::try_from(length.saturating_add(1)).unwrap_or(i32::MAX),
            Err(_) => ERROR_PROCESS,
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeWaitPty(
        _environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        pty_handle: jlong,
        write_pending: jboolean,
    ) -> jint {
        let (Ok(handle), Ok(pty_handle)) = (u64::try_from(handle), u64::try_from(pty_handle))
        else {
            return ERROR_INVALID_ARGUMENT;
        };
        let waiter = {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return ERROR_INVALID_HANDLE;
            };
            match runtime.pty_waiter(pty_handle) {
                Ok(waiter) => waiter,
                Err(_) => return ERROR_PROCESS,
            }
        };
        match waiter.wait(None, write_pending != JNI_FALSE) {
            Ok(event) => {
                (if event.readable {
                    PTY_EVENT_READABLE
                } else {
                    0
                }) | (if event.writable {
                    PTY_EVENT_WRITABLE
                } else {
                    0
                }) | (if event.hangup { PTY_EVENT_HANGUP } else { 0 })
                    | (if event.woken { PTY_EVENT_WOKEN } else { 0 })
            }
            Err(_) => ERROR_PROCESS,
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeWakePty(
        _environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        pty_handle: jlong,
    ) -> jint {
        let (Ok(handle), Ok(pty_handle)) = (u64::try_from(handle), u64::try_from(pty_handle))
        else {
            return ERROR_INVALID_ARGUMENT;
        };
        let waiter = {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return ERROR_INVALID_HANDLE;
            };
            match runtime.pty_waiter(pty_handle) {
                Ok(waiter) => waiter,
                Err(_) => return ERROR_PROCESS,
            }
        };
        waiter.signal().map_or(ERROR_PROCESS, |_| 0)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeResizePty(
        _environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        pty_handle: jlong,
        rows: jint,
        columns: jint,
    ) -> jint {
        let (Ok(handle), Ok(pty_handle), Ok(rows), Ok(columns)) = (
            u64::try_from(handle),
            u64::try_from(pty_handle),
            u16::try_from(rows),
            u16::try_from(columns),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        match runtime.resize_pty(pty_handle, rows, columns) {
            Ok(()) => 0,
            Err(_) => ERROR_PROCESS,
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativePtyExitStatus(
        _environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        pty_handle: jlong,
    ) -> jlong {
        let (Ok(handle), Ok(pty_handle)) = (u64::try_from(handle), u64::try_from(pty_handle))
        else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        let Ok(mut registry) = registry().lock() else {
            return i64::from(ERROR_INTERNAL);
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return i64::from(ERROR_INVALID_HANDLE);
        };
        match runtime.pty_exit_status(pty_handle) {
            Ok(None) => 0,
            Ok(Some(status)) => (i64::from(u32::from_ne_bytes(status.to_ne_bytes())) << 1) | 1,
            Err(_) => i64::from(ERROR_PROCESS),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeClosePty(
        _environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        pty_handle: jlong,
    ) -> jint {
        let (Ok(handle), Ok(pty_handle)) = (u64::try_from(handle), u64::try_from(pty_handle))
        else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        match runtime.close_pty(pty_handle) {
            Ok(()) => 0,
            Err(_) => ERROR_PROCESS,
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeQueuePackageJob(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        operation: jint,
        request_buffer: JByteBuffer,
        request_length: jint,
        now_millis: jlong,
        output_buffer: JByteBuffer,
    ) -> jlong {
        let (Ok(handle), Some(operation), Ok(request_length), Ok(now_millis)) = (
            u64::try_from(handle),
            decode_job_operation(operation),
            usize::try_from(request_length),
            u64::try_from(now_millis),
        ) else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        let (Ok(request_capacity), Ok(output_capacity)) = (
            environment.get_direct_buffer_capacity(&request_buffer),
            environment.get_direct_buffer_capacity(&output_buffer),
        ) else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        if request_length == 0
            || request_length > request_capacity
            || request_length > 160
            || output_capacity < MAX_TOOL_OUTPUT_BYTES
        {
            return i64::from(ERROR_INVALID_ARGUMENT);
        }
        let (Ok(request_address), Ok(output_address)) = (
            environment.get_direct_buffer_address(&request_buffer),
            environment.get_direct_buffer_address(&output_buffer),
        ) else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        if request_address.is_null() || output_address.is_null() {
            return i64::from(ERROR_INVALID_ARGUMENT);
        }
        let request_bytes =
            unsafe { slice::from_raw_parts(request_address.cast_const(), request_length) };
        let Ok(request) = str::from_utf8(request_bytes) else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        let Some((repository, package)) = request.split_once('\t') else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        if package.contains('\t') {
            return i64::from(ERROR_INVALID_ARGUMENT);
        }
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        let Ok(mut registry) = registry().lock() else {
            return i64::from(ERROR_INTERNAL);
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return i64::from(ERROR_INVALID_HANDLE);
        };
        match runtime.begin_package_job(operation, repository, package, now_millis) {
            Ok(job) => i64::try_from(job.id).unwrap_or(i64::from(ERROR_INTERNAL)),
            Err(error) => i64::from(copy_job_error(&error, destination)),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeUpdatePackageJob(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        job_id: jlong,
        state: jint,
        phase: jint,
        progress: jint,
        message_buffer: JByteBuffer,
        message_length: jint,
        now_millis: jlong,
        output_buffer: JByteBuffer,
    ) -> jint {
        let (
            Ok(handle),
            Ok(job_id),
            Some(state),
            Ok(phase),
            Ok(progress),
            Ok(message_length),
            Ok(now_millis),
        ) = (
            u64::try_from(handle),
            u64::try_from(job_id),
            decode_job_state(state),
            u8::try_from(phase),
            u8::try_from(progress),
            usize::try_from(message_length),
            u64::try_from(now_millis),
        )
        else {
            return ERROR_INVALID_ARGUMENT;
        };
        let (Ok(message_capacity), Ok(output_capacity)) = (
            environment.get_direct_buffer_capacity(&message_buffer),
            environment.get_direct_buffer_capacity(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if message_length == 0
            || message_length > message_capacity
            || message_length > 192
            || output_capacity < MAX_TOOL_OUTPUT_BYTES
        {
            return ERROR_INVALID_ARGUMENT;
        }
        let (Ok(message_address), Ok(output_address)) = (
            environment.get_direct_buffer_address(&message_buffer),
            environment.get_direct_buffer_address(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if message_address.is_null() || output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let message_bytes =
            unsafe { slice::from_raw_parts(message_address.cast_const(), message_length) };
        let Ok(message) = str::from_utf8(message_bytes) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        match runtime.update_package_job(job_id, state, phase, progress, message, now_millis) {
            Ok(_) => 0,
            Err(error) => copy_job_error(&error, destination),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeReadLatestPackageJob(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(handle) = u64::try_from(handle) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(output_capacity) = environment.get_direct_buffer_capacity(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_capacity < MAX_TOOL_OUTPUT_BYTES {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(output_address) = environment.get_direct_buffer_address(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        let job = {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return ERROR_INVALID_HANDLE;
            };
            runtime.latest_package_job()
        };
        let Some(job) = job else {
            return 0;
        };
        let mut writer = &mut destination[..MAX_TOOL_OUTPUT_BYTES];
        if write!(
            writer,
            "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\n",
            job.id,
            job.operation as u8,
            job.state as u8,
            job.phase,
            job.progress,
            job.updated_millis,
            job.repository.as_str(),
            job.package.as_str(),
            job.message.as_str(),
        )
        .is_err()
        {
            return ERROR_INTERNAL;
        }
        i32::try_from(MAX_TOOL_OUTPUT_BYTES - writer.len()).unwrap_or(i32::MAX)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeRefreshPackageCache(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        output_buffer: JByteBuffer,
    ) -> jint {
        let Ok(handle) = u64::try_from(handle) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(output_capacity) = environment.get_direct_buffer_capacity(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_capacity < MAX_TOOL_OUTPUT_BYTES {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(output_address) = environment.get_direct_buffer_address(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        let (entries, bytes) = match runtime.refresh_package_cache() {
            Ok(summary) => summary,
            Err(error) => return copy_package_error(&error, destination),
        };
        let mut writer = std::io::Cursor::new(destination);
        if write!(&mut writer, "C1\t{entries}\t{bytes}\n").is_err() {
            return ERROR_INTERNAL;
        }
        i32::try_from(writer.position()).unwrap_or(ERROR_INTERNAL)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeReadPackageCachePage(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        offset: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        let (Ok(handle), Ok(offset)) = (u64::try_from(handle), usize::try_from(offset)) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(output_capacity) = environment.get_direct_buffer_capacity(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_capacity < MAX_TOOL_OUTPUT_BYTES {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(output_address) = environment.get_direct_buffer_address(&output_buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        copy_tool_result(runtime.package_cache_page(offset), destination)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeClearSelectedPackageCache(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        request_buffer: JByteBuffer,
        request_length: jint,
        output_buffer: JByteBuffer,
    ) -> jlong {
        let (Ok(handle), Ok(request_length)) =
            (u64::try_from(handle), usize::try_from(request_length))
        else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        let (Ok(request_capacity), Ok(output_capacity)) = (
            environment.get_direct_buffer_capacity(&request_buffer),
            environment.get_direct_buffer_capacity(&output_buffer),
        ) else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        if request_length == 0
            || request_length > request_capacity
            || request_length > MAX_PACKAGE_CACHE_SELECTION_BYTES
            || output_capacity < MAX_TOOL_OUTPUT_BYTES
        {
            return i64::from(ERROR_INVALID_ARGUMENT);
        }
        let (Ok(request_address), Ok(output_address)) = (
            environment.get_direct_buffer_address(&request_buffer),
            environment.get_direct_buffer_address(&output_buffer),
        ) else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        if request_address.is_null() || output_address.is_null() {
            return i64::from(ERROR_INVALID_ARGUMENT);
        }
        let request = unsafe { slice::from_raw_parts(request_address, request_length) };
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        let Ok(request) = str::from_utf8(request) else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        let packages: Vec<&str> = request.split('\n').collect();
        if packages.is_empty()
            || packages.len() > 256
            || packages.iter().any(|item| item.is_empty())
        {
            return i64::from(ERROR_INVALID_ARGUMENT);
        }
        let Ok(mut registry) = registry().lock() else {
            return i64::from(ERROR_INTERNAL);
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return i64::from(ERROR_INVALID_HANDLE);
        };
        match runtime.clear_package_cache_packages(&packages) {
            Ok(bytes) => i64::try_from(bytes).unwrap_or(i64::from(ERROR_INTERNAL)),
            Err(error) => i64::from(copy_package_error(&error, destination)),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeClearPackageCache(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        output_buffer: JByteBuffer,
    ) -> jlong {
        let Ok(handle) = u64::try_from(handle) else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        let Ok(output_capacity) = environment.get_direct_buffer_capacity(&output_buffer) else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        if output_capacity < MAX_TOOL_OUTPUT_BYTES {
            return i64::from(ERROR_INVALID_ARGUMENT);
        }
        let Ok(output_address) = environment.get_direct_buffer_address(&output_buffer) else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        if output_address.is_null() {
            return i64::from(ERROR_INVALID_ARGUMENT);
        }
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        let Ok(mut registry) = registry().lock() else {
            return i64::from(ERROR_INTERNAL);
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return i64::from(ERROR_INVALID_HANDLE);
        };
        match runtime.clear_package_cache() {
            Ok(bytes) => i64::try_from(bytes).unwrap_or(i64::from(ERROR_INTERNAL)),
            Err(error) => i64::from(copy_package_error(&error, destination)),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeBeginPackageDownload(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        filename_buffer: JByteBuffer,
        filename_length: jint,
        expected_size: jlong,
        signature: jboolean,
        output_buffer: JByteBuffer,
    ) -> jint {
        let (Ok(handle), Ok(filename_length), Ok(expected_size)) = (
            u64::try_from(handle),
            usize::try_from(filename_length),
            u64::try_from(expected_size),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let (Ok(filename_capacity), Ok(output_capacity)) = (
            environment.get_direct_buffer_capacity(&filename_buffer),
            environment.get_direct_buffer_capacity(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if filename_length == 0
            || filename_length > filename_capacity
            || filename_length > 240
            || output_capacity < MAX_TOOL_OUTPUT_BYTES
        {
            return ERROR_INVALID_ARGUMENT;
        }
        let (Ok(filename_address), Ok(output_address)) = (
            environment.get_direct_buffer_address(&filename_buffer),
            environment.get_direct_buffer_address(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if filename_address.is_null() || output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let filename_bytes =
            unsafe { slice::from_raw_parts(filename_address.cast_const(), filename_length) };
        let Ok(filename) = str::from_utf8(filename_bytes) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        match runtime.begin_package_download(filename, expected_size, signature != JNI_FALSE) {
            Ok(file) => file.into_raw_fd(),
            Err(error) => copy_package_error(&error, destination),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeFinishPackageDownload(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        success: jboolean,
        output_buffer: JByteBuffer,
    ) -> jlong {
        let Ok(handle) = u64::try_from(handle) else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        let Ok(output_capacity) = environment.get_direct_buffer_capacity(&output_buffer) else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        if output_capacity < MAX_TOOL_OUTPUT_BYTES {
            return i64::from(ERROR_INVALID_ARGUMENT);
        }
        let Ok(output_address) = environment.get_direct_buffer_address(&output_buffer) else {
            return i64::from(ERROR_INVALID_ARGUMENT);
        };
        if output_address.is_null() {
            return i64::from(ERROR_INVALID_ARGUMENT);
        }
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        let Ok(mut registry) = registry().lock() else {
            return i64::from(ERROR_INTERNAL);
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return i64::from(ERROR_INVALID_HANDLE);
        };
        if success == JNI_FALSE {
            runtime.cancel_package_download();
            return 0;
        }
        match runtime.finish_package_download(true) {
            Ok(length) => i64::try_from(length).unwrap_or(i64::from(ERROR_INTERNAL)),
            Err(error) => i64::from(copy_package_error(&error, destination)),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeVerifyPackage(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        request_buffer: JByteBuffer,
        request_length: jint,
        expected_size: jlong,
        output_buffer: JByteBuffer,
    ) -> jint {
        let (Ok(handle), Ok(request_length), Ok(expected_size)) = (
            u64::try_from(handle),
            usize::try_from(request_length),
            u64::try_from(expected_size),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let (Ok(request_capacity), Ok(output_capacity)) = (
            environment.get_direct_buffer_capacity(&request_buffer),
            environment.get_direct_buffer_capacity(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if request_length == 0
            || request_length > request_capacity
            || request_length > 512
            || output_capacity < MAX_TOOL_OUTPUT_BYTES
        {
            return ERROR_INVALID_ARGUMENT;
        }
        let (Ok(request_address), Ok(output_address)) = (
            environment.get_direct_buffer_address(&request_buffer),
            environment.get_direct_buffer_address(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if request_address.is_null() || output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let request_bytes =
            unsafe { slice::from_raw_parts(request_address.cast_const(), request_length) };
        let Ok(request) = str::from_utf8(request_bytes) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let mut fields = request.split('\t');
        let (Some(filename), Some(name), Some(version), None) =
            (fields.next(), fields.next(), fields.next(), fields.next())
        else {
            return ERROR_INVALID_ARGUMENT;
        };
        let package_runtime = {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return ERROR_INVALID_HANDLE;
            };
            let Some(package_runtime) = runtime.package_runtime() else {
                return ERROR_INVALID_STATE;
            };
            package_runtime.clone()
        };
        let result = package_runtime.verify_package(filename, name, version, expected_size);
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        copy_tool_result(result, destination)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeAnalyzeCachedPackage(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        request_buffer: JByteBuffer,
        request_length: jint,
        output_buffer: JByteBuffer,
    ) -> jint {
        let (Ok(handle), Ok(request_length)) =
            (u64::try_from(handle), usize::try_from(request_length))
        else {
            return ERROR_INVALID_ARGUMENT;
        };
        let (Ok(request_capacity), Ok(output_capacity)) = (
            environment.get_direct_buffer_capacity(&request_buffer),
            environment.get_direct_buffer_capacity(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if request_length == 0
            || request_length > request_capacity
            || request_length > 128
            || output_capacity < MAX_TOOL_OUTPUT_BYTES
        {
            return ERROR_INVALID_ARGUMENT;
        }
        let (Ok(request_address), Ok(output_address)) = (
            environment.get_direct_buffer_address(&request_buffer),
            environment.get_direct_buffer_address(&output_buffer),
        ) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if request_address.is_null() || output_address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let request_bytes =
            unsafe { slice::from_raw_parts(request_address.cast_const(), request_length) };
        let Ok(package) = str::from_utf8(request_bytes) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Some(cancellation) = package_compatibility_cancellation(handle) else {
            return ERROR_INVALID_STATE;
        };
        let _cancellation_guard = PackageCompatibilityCancellationGuard(handle);
        let package_runtime = {
            let Ok(mut registry) = registry().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(runtime) = registry.runtime_mut(handle) else {
                return ERROR_INVALID_HANDLE;
            };
            let Some(package_runtime) = runtime.package_runtime() else {
                return ERROR_INVALID_STATE;
            };
            package_runtime.clone()
        };
        let result = hold_debug_package_compatibility_review(&cancellation).and_then(|()| {
            package_runtime.cached_package_compatibility_cancellable(package, &cancellation)
        });
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        copy_tool_result(result, destination)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativePreparePackageCompatibilityReview(
        _environment: JNIEnv,
        _class: JClass,
        handle: jlong,
    ) -> jboolean {
        let Ok(handle) = u64::try_from(handle) else {
            return JNI_FALSE;
        };
        let Ok(mut registry) = registry().lock() else {
            return JNI_FALSE;
        };
        if registry.runtime_mut(handle).is_none() {
            return JNI_FALSE;
        }
        let prepared =
            set_package_compatibility_cancellation(handle, PackageCompatibilityCancellation::new())
                .is_ok();
        drop(registry);
        if prepared { JNI_TRUE } else { JNI_FALSE }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeArmPackageCompatibilityReviewTestHold(
        _environment: JNIEnv,
        _class: JClass,
        hold_millis: jlong,
    ) -> jboolean {
        let Ok(hold_millis) = u64::try_from(hold_millis) else {
            return JNI_FALSE;
        };
        if !(750..=30_000).contains(&hold_millis) {
            return JNI_FALSE;
        }
        if PACKAGE_COMPATIBILITY_TEST_HOLD_MILLIS
            .compare_exchange(0, hold_millis, Ordering::AcqRel, Ordering::Acquire)
            .is_ok()
        {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeCancelPackageCompatibilityReview(
        _environment: JNIEnv,
        _class: JClass,
        handle: jlong,
    ) -> jboolean {
        let Ok(handle) = u64::try_from(handle) else {
            return JNI_FALSE;
        };
        if cancel_package_compatibility_without_runtime_lock(handle) {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeSubmitEvents(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        buffer: JByteBuffer,
        byte_count: jint,
    ) -> jint {
        let (Ok(handle), Ok(byte_count)) = (u64::try_from(handle), usize::try_from(byte_count))
        else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(capacity) = environment.get_direct_buffer_capacity(&buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if byte_count == 0 || byte_count > capacity {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(address) = environment.get_direct_buffer_address(&buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let bytes = unsafe { slice::from_raw_parts(address.cast_const(), byte_count) };

        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        runtime
            .submit_encoded_events(bytes)
            .map_or_else(runtime_error, |count| {
                i32::try_from(count).unwrap_or(i32::MAX)
            })
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeDrainInput(
        _environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        maximum: jint,
    ) -> jint {
        let (Ok(handle), Ok(maximum)) = (u64::try_from(handle), usize::try_from(maximum)) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        i32::try_from(runtime.drain_input(maximum)).unwrap_or(i32::MAX)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_archphene_app_runtime_NativeRuntime_nativeWriteSnapshot(
        environment: JNIEnv,
        _class: JClass,
        handle: jlong,
        buffer: JByteBuffer,
    ) -> jint {
        let Ok(handle) = u64::try_from(handle) else {
            return ERROR_INVALID_ARGUMENT;
        };
        let Ok(capacity) = environment.get_direct_buffer_capacity(&buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if capacity < SNAPSHOT_SIZE {
            return ERROR_INVALID_ARGUMENT;
        }
        let Ok(address) = environment.get_direct_buffer_address(&buffer) else {
            return ERROR_INVALID_ARGUMENT;
        };
        if address.is_null() {
            return ERROR_INVALID_ARGUMENT;
        }
        let output = unsafe { slice::from_raw_parts_mut(address, SNAPSHOT_SIZE) };

        let Ok(mut registry) = registry().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(runtime) = registry.runtime_mut(handle) else {
            return ERROR_INVALID_HANDLE;
        };
        runtime
            .write_snapshot(output)
            .map_or_else(runtime_error, |count| {
                i32::try_from(count).unwrap_or(i32::MAX)
            })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stale_handles_are_rejected_after_slot_reuse() {
        let mut registry = RuntimeRegistry::new();
        let first = registry.create().expect("first handle");
        assert!(registry.destroy(first));
        assert!(registry.runtime_mut(first).is_none());

        let second = registry.create().expect("second handle");
        assert_ne!(first, second);
        assert!(registry.runtime_mut(first).is_none());
        assert!(registry.runtime_mut(second).is_some());
    }

    #[test]
    fn registry_is_bounded() {
        let mut registry = RuntimeRegistry::new();
        for _ in 0..MAX_RUNTIME_HANDLES {
            assert!(registry.create().is_some());
        }
        assert!(registry.create().is_none());
    }
}
