#![deny(unsafe_code)]

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

    use std::os::fd::IntoRawFd;
    use std::path::Path;
    use std::slice;
    use std::sync::{Mutex, OnceLock};
    use std::{io::Write as _, str};

    use archphene_core::{Lifecycle, PROTOCOL_VERSION, RuntimeError, SNAPSHOT_SIZE};
    use archphene_jobs::{JobError, JobOperation, JobState};
    use archphene_packages::{
        MAX_MANIFEST_BYTES, MAX_TOOL_OUTPUT_BYTES, PackageRuntimeError, Repository,
        RepositoryArchitecture, ToolOutput,
    };
    use archphene_process::{
        MAX_COMMAND_ARGUMENTS, MAX_COMMAND_OUTPUT_BYTES, MAX_COMMAND_REQUEST_BYTES,
        MAX_PTY_TRANSFER_BYTES, MAX_TERMINAL_DAMAGE_BYTES, ProcessError,
    };
    use archphene_storage::{OpenMode, StorageError};
    use jni::JNIEnv;
    use jni::objects::{JByteBuffer, JClass};
    use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean, jint, jlong};

    use super::RuntimeRegistry;

    const ERROR_INVALID_HANDLE: jint = -1;
    const ERROR_INVALID_ARGUMENT: jint = -2;
    const ERROR_INVALID_STATE: jint = -3;
    const ERROR_QUEUE_FULL: jint = -4;
    const ERROR_INTERNAL: jint = -5;
    const ERROR_BOOTSTRAP: jint = -6;
    const ERROR_PACKAGE_RUNTIME: jint = -7;
    const ERROR_PROCESS: jint = -8;
    const ERROR_STORAGE: jint = -9;
    const MAX_STORAGE_REQUEST_BYTES: usize = 4 * 1024;
    const PTY_EVENT_READABLE: jint = 1;
    const PTY_EVENT_WRITABLE: jint = 1 << 1;
    const PTY_EVENT_HANGUP: jint = 1 << 2;
    const PTY_EVENT_WOKEN: jint = 1 << 3;

    static REGISTRY: OnceLock<Mutex<RuntimeRegistry>> = OnceLock::new();

    fn registry() -> &'static Mutex<RuntimeRegistry> {
        REGISTRY.get_or_init(|| Mutex::new(RuntimeRegistry::new()))
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

    fn copy_package_error(error: &PackageRuntimeError, destination: &mut [u8]) -> jint {
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
        if registry.destroy(handle) {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
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
        let result = package_runtime.resolve(package);
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        copy_tool_result(result, destination)
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
            _ => return ERROR_INVALID_ARGUMENT,
        };
        let destination = unsafe { slice::from_raw_parts_mut(output_address, output_capacity) };
        copy_tool_result(result, destination)
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
