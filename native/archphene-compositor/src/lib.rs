use std::fs::File;
use std::io;
use std::mem::{size_of, zeroed};
use std::ops::Range;
use std::os::fd::{AsRawFd, FromRawFd, RawFd};
use std::os::unix::fs::FileExt;
use std::os::unix::net::UnixStream;
use std::ptr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use wayland_server::protocol::wl_buffer::{self, WlBuffer};
use wayland_server::protocol::wl_callback::{self, WlCallback};
use wayland_server::protocol::wl_compositor::{self, WlCompositor};
use wayland_server::protocol::wl_region::{self, WlRegion};
use wayland_server::protocol::wl_shm::{self, WlShm};
use wayland_server::protocol::wl_shm_pool::{self, WlShmPool};
use wayland_server::protocol::wl_surface::{self, WlSurface};
use wayland_server::{
    Client, DataInit, Dispatch, Display, DisplayHandle, GlobalDispatch, New, Resource, WEnum,
    backend::ClientId,
};

/// Owns protocol dispatch independently from Android Activity and rendering state.
pub struct CompositorCore {
    display: Display<CompositorState>,
    state: CompositorState,
    stopping: AtomicBool,
}

#[derive(Default)]
pub struct CompositorState {
    compositor_binds: u32,
    shm_binds: u32,
    shm_pool_count: u32,
    shm_buffer_count: u32,
    last_buffer_checksum: u32,
    surface_count: u32,
    surface_commit_count: u32,
    last_frame_width: u32,
    last_frame_height: u32,
    last_frame_checksum: u32,
    last_frame: Option<Arc<CommittedFrame>>,
}

#[derive(Default)]
pub struct SurfaceData {
    inner: Mutex<SurfaceState>,
}

#[derive(Default)]
struct SurfaceState {
    pending_buffer: Option<Option<SurfaceBuffer>>,
    pending_damage: bool,
    pending_callbacks: Vec<WlCallback>,
    committed_frame: Option<Arc<CommittedFrame>>,
}

#[derive(Clone)]
struct SurfaceBuffer {
    resource: WlBuffer,
    inner: Arc<ShmBufferInner>,
}

struct CommittedFrame {
    width: u32,
    height: u32,
    #[allow(dead_code)]
    format: wl_shm::Format,
    #[allow(dead_code)]
    pixels: Vec<u8>,
}

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
fn copy_wayland_pixels_to_android(
    source: &[u8],
    format: wl_shm::Format,
    destination: &mut [u8],
) -> Result<(), ()> {
    if source.len() != destination.len() || source.len() % 4 != 0 {
        return Err(());
    }
    for (source, destination) in source.chunks_exact(4).zip(destination.chunks_exact_mut(4)) {
        destination[0] = source[2];
        destination[1] = source[1];
        destination[2] = source[0];
        destination[3] = if format == wl_shm::Format::Argb8888 {
            source[3]
        } else {
            u8::MAX
        };
    }
    Ok(())
}

#[cfg(target_os = "android")]
#[repr(C)]
struct AndroidBitmapInfo {
    width: u32,
    height: u32,
    stride: u32,
    format: i32,
    flags: u32,
}

#[cfg(target_os = "android")]
#[link(name = "jnigraphics")]
unsafe extern "C" {
    #[link_name = "AndroidBitmap_getInfo"]
    fn android_bitmap_get_info(
        environment: *mut std::ffi::c_void,
        bitmap: *mut std::ffi::c_void,
        info: *mut AndroidBitmapInfo,
    ) -> i32;
    #[link_name = "AndroidBitmap_lockPixels"]
    fn android_bitmap_lock_pixels(
        environment: *mut std::ffi::c_void,
        bitmap: *mut std::ffi::c_void,
        address: *mut *mut std::ffi::c_void,
    ) -> i32;
    #[link_name = "AndroidBitmap_unlockPixels"]
    fn android_bitmap_unlock_pixels(
        environment: *mut std::ffi::c_void,
        bitmap: *mut std::ffi::c_void,
    ) -> i32;
}

#[cfg(target_os = "android")]
fn copy_last_frame_to_bitmap(
    core: &CompositorCore,
    environment: *mut std::ffi::c_void,
    bitmap: *mut std::ffi::c_void,
) -> i32 {
    const ANDROID_BITMAP_FORMAT_RGBA_8888: i32 = 1;
    let Some(frame) = core.state.last_frame.as_ref() else {
        return -1;
    };
    let mut info: AndroidBitmapInfo = unsafe { zeroed() };
    if unsafe { android_bitmap_get_info(environment, bitmap, &mut info) } != 0 {
        return -2;
    }
    let row_bytes = match usize::try_from(frame.width)
        .ok()
        .and_then(|width| width.checked_mul(4))
    {
        Some(value) => value,
        None => return -3,
    };
    let Ok(bitmap_stride) = usize::try_from(info.stride) else {
        return -3;
    };
    if info.format != ANDROID_BITMAP_FORMAT_RGBA_8888
        || info.width != frame.width
        || info.height != frame.height
        || bitmap_stride < row_bytes
    {
        return -3;
    }
    let Some(bitmap_bytes) = bitmap_stride.checked_mul(info.height as usize) else {
        return -3;
    };
    let mut address = ptr::null_mut();
    if unsafe { android_bitmap_lock_pixels(environment, bitmap, &mut address) } != 0 {
        return -4;
    }
    if address.is_null() {
        let _ = unsafe { android_bitmap_unlock_pixels(environment, bitmap) };
        return -4;
    }

    let destination = unsafe { std::slice::from_raw_parts_mut(address.cast::<u8>(), bitmap_bytes) };
    for (source, destination) in frame
        .pixels
        .chunks_exact(row_bytes)
        .zip(destination.chunks_exact_mut(bitmap_stride))
    {
        if copy_wayland_pixels_to_android(source, frame.format, &mut destination[..row_bytes])
            .is_err()
        {
            let _ = unsafe { android_bitmap_unlock_pixels(environment, bitmap) };
            return -5;
        }
    }
    if unsafe { android_bitmap_unlock_pixels(environment, bitmap) } != 0 {
        return -6;
    }
    0
}

pub struct RegionData;

struct ShmPoolInner {
    file: File,
    size: usize,
}

pub struct ShmPoolData {
    inner: Arc<Mutex<ShmPoolInner>>,
}

struct ShmBufferInner {
    pool: Arc<Mutex<ShmPoolInner>>,
    offset: usize,
    width: usize,
    height: usize,
    stride: usize,
    format: wl_shm::Format,
}

pub struct ShmBufferData {
    inner: Arc<ShmBufferInner>,
}

impl ShmBufferInner {
    fn snapshot(&self) -> io::Result<CommittedFrame> {
        const MAX_FRAME_BYTES: usize = 128 * 1024 * 1024;
        let row_bytes = self
            .width
            .checked_mul(4)
            .ok_or_else(|| io::Error::other("SHM row size overflow"))?;
        let frame_bytes = row_bytes
            .checked_mul(self.height)
            .ok_or_else(|| io::Error::other("SHM frame size overflow"))?;
        if frame_bytes > MAX_FRAME_BYTES {
            return Err(io::Error::other("SHM frame exceeds the bridge limit"));
        }
        let mut pixels = vec![0u8; frame_bytes];
        let pool = self.pool.lock().unwrap_or_else(|error| error.into_inner());
        for (row, destination) in pixels.chunks_exact_mut(row_bytes).enumerate() {
            let source_offset = self
                .offset
                .checked_add(
                    row.checked_mul(self.stride)
                        .ok_or_else(|| io::Error::other("SHM source offset overflow"))?,
                )
                .ok_or_else(|| io::Error::other("SHM source offset overflow"))?;
            pool.file.read_exact_at(destination, source_offset as u64)?;
        }
        Ok(CommittedFrame {
            width: self.width as u32,
            height: self.height as u32,
            format: self.format,
            pixels,
        })
    }
}

impl GlobalDispatch<WlCompositor, ()> for CompositorState {
    fn bind(
        state: &mut Self,
        _handle: &DisplayHandle,
        _client: &Client,
        resource: New<WlCompositor>,
        _global_data: &(),
        data_init: &mut DataInit<'_, Self>,
    ) {
        data_init.init(resource, ());
        state.compositor_binds = state.compositor_binds.saturating_add(1);
    }
}

impl GlobalDispatch<WlShm, ()> for CompositorState {
    fn bind(
        state: &mut Self,
        _handle: &DisplayHandle,
        _client: &Client,
        resource: New<WlShm>,
        _global_data: &(),
        data_init: &mut DataInit<'_, Self>,
    ) {
        let shm = data_init.init(resource, ());
        shm.format(wl_shm::Format::Argb8888);
        shm.format(wl_shm::Format::Xrgb8888);
        state.shm_binds = state.shm_binds.saturating_add(1);
    }
}

impl Dispatch<WlShm, ()> for CompositorState {
    fn request(
        state: &mut Self,
        _client: &Client,
        resource: &WlShm,
        request: wl_shm::Request,
        _data: &(),
        _handle: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_shm::Request::CreatePool { id, fd, size } => {
                let Ok(pool_size) = usize::try_from(size) else {
                    resource.post_error(
                        wl_shm::Error::InvalidStride,
                        "SHM pool size must be positive",
                    );
                    return;
                };
                if pool_size == 0 {
                    resource.post_error(
                        wl_shm::Error::InvalidStride,
                        "SHM pool size must be positive",
                    );
                    return;
                }
                let file = File::from(fd);
                let Ok(metadata) = file.metadata() else {
                    resource.post_error(wl_shm::Error::InvalidFd, "could not inspect SHM pool FD");
                    return;
                };
                if metadata.len() < pool_size as u64 {
                    resource.post_error(
                        wl_shm::Error::InvalidFd,
                        "SHM pool FD is smaller than its declared size",
                    );
                    return;
                }
                data_init.init(
                    id,
                    ShmPoolData {
                        inner: Arc::new(Mutex::new(ShmPoolInner {
                            file,
                            size: pool_size,
                        })),
                    },
                );
                state.shm_pool_count = state.shm_pool_count.saturating_add(1);
            }
            _ => unreachable!("wl_shm request added without an implementation"),
        }
    }
}

impl Dispatch<WlShmPool, ShmPoolData> for CompositorState {
    fn request(
        state: &mut Self,
        _client: &Client,
        resource: &WlShmPool,
        request: wl_shm_pool::Request,
        data: &ShmPoolData,
        _handle: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_shm_pool::Request::CreateBuffer {
                id,
                offset,
                width,
                height,
                stride,
                format,
            } => {
                let format = match format {
                    WEnum::Value(value @ (wl_shm::Format::Argb8888 | wl_shm::Format::Xrgb8888)) => {
                        value
                    }
                    _ => {
                        resource.post_error(wl_shm::Error::InvalidFormat, "unsupported SHM format");
                        return;
                    }
                };
                let guard = data.inner.lock().unwrap_or_else(|error| error.into_inner());
                let Ok(range) = validate_buffer_geometry(guard.size, offset, width, height, stride)
                else {
                    resource
                        .post_error(wl_shm::Error::InvalidStride, "invalid SHM buffer geometry");
                    return;
                };
                let mut pixels = vec![0u8; range.len().min(4096)];
                if guard
                    .file
                    .read_exact_at(&mut pixels, range.start as u64)
                    .is_err()
                {
                    resource.post_error(wl_shm::Error::InvalidFd, "could not read SHM buffer");
                    return;
                }
                state.last_buffer_checksum = pixels.iter().fold(0u32, |checksum, value| {
                    checksum.wrapping_add(u32::from(*value))
                });
                drop(guard);
                let (Ok(width), Ok(height), Ok(stride)) = (
                    usize::try_from(width),
                    usize::try_from(height),
                    usize::try_from(stride),
                ) else {
                    unreachable!("validated SHM geometry became invalid");
                };
                data_init.init(
                    id,
                    ShmBufferData {
                        inner: Arc::new(ShmBufferInner {
                            pool: Arc::clone(&data.inner),
                            offset: range.start,
                            width,
                            height,
                            stride,
                            format,
                        }),
                    },
                );
                state.shm_buffer_count = state.shm_buffer_count.saturating_add(1);
            }
            wl_shm_pool::Request::Destroy => {}
            wl_shm_pool::Request::Resize { size } => {
                let Ok(new_size) = usize::try_from(size) else {
                    resource.post_error(wl_shm::Error::InvalidStride, "invalid SHM pool resize");
                    return;
                };
                let mut guard = data.inner.lock().unwrap_or_else(|error| error.into_inner());
                let valid_file_size = guard
                    .file
                    .metadata()
                    .map(|metadata| metadata.len() >= new_size as u64)
                    .unwrap_or(false);
                if new_size <= guard.size {
                    resource.post_error(wl_shm::Error::InvalidStride, "SHM pool resize must grow");
                    return;
                }
                if !valid_file_size {
                    resource.post_error(
                        wl_shm::Error::InvalidFd,
                        "SHM pool resize exceeds the backing file",
                    );
                    return;
                }
                guard.size = new_size;
            }
            _ => unreachable!("wl_shm_pool request added without an implementation"),
        }
    }

    fn destroyed(state: &mut Self, _client: ClientId, _resource: &WlShmPool, _data: &ShmPoolData) {
        state.shm_pool_count = state.shm_pool_count.saturating_sub(1);
    }
}

impl Dispatch<WlBuffer, ShmBufferData> for CompositorState {
    fn request(
        _state: &mut Self,
        _client: &Client,
        _resource: &WlBuffer,
        request: wl_buffer::Request,
        _data: &ShmBufferData,
        _handle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_buffer::Request::Destroy => {}
            _ => unreachable!("wl_buffer request added without an implementation"),
        }
    }

    fn destroyed(state: &mut Self, _client: ClientId, _resource: &WlBuffer, _data: &ShmBufferData) {
        state.shm_buffer_count = state.shm_buffer_count.saturating_sub(1);
    }
}

fn validate_buffer_geometry(
    pool_size: usize,
    offset: i32,
    width: i32,
    height: i32,
    stride: i32,
) -> Result<Range<usize>, ()> {
    let offset = usize::try_from(offset).map_err(|_| ())?;
    let width = usize::try_from(width).map_err(|_| ())?;
    let height = usize::try_from(height).map_err(|_| ())?;
    let stride = usize::try_from(stride).map_err(|_| ())?;
    if width == 0 || height == 0 || stride < width.checked_mul(4).ok_or(())? {
        return Err(());
    }
    let last_row = height
        .checked_sub(1)
        .ok_or(())?
        .checked_mul(stride)
        .ok_or(())?;
    let byte_count = last_row
        .checked_add(width.checked_mul(4).ok_or(())?)
        .ok_or(())?;
    let end = offset.checked_add(byte_count).ok_or(())?;
    if end > pool_size {
        return Err(());
    }
    Ok(offset..end)
}

impl Dispatch<WlCompositor, ()> for CompositorState {
    fn request(
        state: &mut Self,
        _client: &Client,
        _resource: &WlCompositor,
        request: wl_compositor::Request,
        _data: &(),
        _handle: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_compositor::Request::CreateSurface { id } => {
                data_init.init(id, SurfaceData::default());
                state.surface_count = state.surface_count.saturating_add(1);
            }
            wl_compositor::Request::CreateRegion { id } => {
                data_init.init(id, RegionData);
            }
            _ => unreachable!("wl_compositor request added without an implementation"),
        }
    }
}

impl Dispatch<WlSurface, SurfaceData> for CompositorState {
    fn request(
        state: &mut Self,
        _client: &Client,
        resource: &WlSurface,
        request: wl_surface::Request,
        data: &SurfaceData,
        _handle: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_surface::Request::Destroy => {}
            wl_surface::Request::Attach { buffer, x, y } => {
                if resource.version() >= 5 && (x != 0 || y != 0) {
                    resource.post_error(
                        wl_surface::Error::InvalidOffset,
                        "wl_surface.attach offset must be zero at version 5 or newer",
                    );
                    return;
                }
                let assignment = match buffer {
                    Some(buffer) => {
                        let Some(buffer_data) = buffer.data::<ShmBufferData>() else {
                            resource.post_error(
                                wl_surface::Error::NoBuffer,
                                "buffer was not created by a supported bridge allocator",
                            );
                            return;
                        };
                        let inner = Arc::clone(&buffer_data.inner);
                        Some(SurfaceBuffer {
                            resource: buffer,
                            inner,
                        })
                    }
                    None => None,
                };
                let mut surface = data.inner.lock().unwrap_or_else(|error| error.into_inner());
                surface.pending_buffer = Some(assignment);
            }
            wl_surface::Request::Damage { width, height, .. }
            | wl_surface::Request::DamageBuffer { width, height, .. } => {
                if width > 0 && height > 0 {
                    let mut surface = data.inner.lock().unwrap_or_else(|error| error.into_inner());
                    surface.pending_damage = true;
                }
            }
            wl_surface::Request::Frame { callback } => {
                let callback = data_init.init(callback, ());
                let mut surface = data.inner.lock().unwrap_or_else(|error| error.into_inner());
                surface.pending_callbacks.push(callback);
            }
            wl_surface::Request::SetOpaqueRegion { .. }
            | wl_surface::Request::SetInputRegion { .. }
            | wl_surface::Request::Offset { .. } => {}
            wl_surface::Request::SetBufferTransform { transform } => {
                if !matches!(transform, WEnum::Value(_)) {
                    resource.post_error(
                        wl_surface::Error::InvalidTransform,
                        "unknown buffer transform",
                    );
                }
            }
            wl_surface::Request::SetBufferScale { scale } => {
                if scale < 1 {
                    resource.post_error(
                        wl_surface::Error::InvalidScale,
                        "buffer scale must be positive",
                    );
                }
            }
            wl_surface::Request::Commit => {
                let mut surface = data.inner.lock().unwrap_or_else(|error| error.into_inner());
                if let Some(assignment) = surface.pending_buffer.take() {
                    surface.committed_frame = match assignment {
                        Some(buffer) => match buffer.inner.snapshot() {
                            Ok(frame) => {
                                if buffer.resource.is_alive() {
                                    buffer.resource.release();
                                }
                                Some(Arc::new(frame))
                            }
                            Err(error) => {
                                resource.post_error(
                                    wl_surface::Error::InvalidSize,
                                    format!("could not snapshot SHM frame: {error}"),
                                );
                                return;
                            }
                        },
                        None => None,
                    };
                }
                let callbacks = std::mem::take(&mut surface.pending_callbacks);
                surface.pending_damage = false;
                let latest_frame = surface.committed_frame.clone();
                let metrics = latest_frame.as_ref().map(|frame| {
                    let checksum = frame.pixels.iter().fold(0u32, |checksum, value| {
                        checksum.wrapping_add(u32::from(*value))
                    });
                    (frame.width, frame.height, checksum)
                });
                drop(surface);

                state.surface_commit_count = state.surface_commit_count.saturating_add(1);
                state.last_frame = latest_frame;
                if let Some((width, height, checksum)) = metrics {
                    state.last_frame_width = width;
                    state.last_frame_height = height;
                    state.last_frame_checksum = checksum;
                } else {
                    state.last_frame_width = 0;
                    state.last_frame_height = 0;
                    state.last_frame_checksum = 0;
                }
                for callback in callbacks {
                    callback.done(0);
                }
            }
            _ => unreachable!("wl_surface request added without an implementation"),
        }
    }

    fn destroyed(state: &mut Self, _client: ClientId, _resource: &WlSurface, _data: &SurfaceData) {
        state.surface_count = state.surface_count.saturating_sub(1);
        if state.surface_count == 0 {
            state.last_frame = None;
            state.last_frame_width = 0;
            state.last_frame_height = 0;
            state.last_frame_checksum = 0;
        }
    }
}

impl Dispatch<WlCallback, ()> for CompositorState {
    fn request(
        _state: &mut Self,
        _client: &Client,
        _resource: &WlCallback,
        request: wl_callback::Request,
        _data: &(),
        _handle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            _ => unreachable!("wl_callback has no client requests"),
        }
    }
}

impl Dispatch<WlRegion, RegionData> for CompositorState {
    fn request(
        _state: &mut Self,
        _client: &Client,
        resource: &WlRegion,
        request: wl_region::Request,
        _data: &RegionData,
        _handle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_region::Request::Destroy => {}
            _ => resource.post_error(0u32, format!("unsupported region request: {request:?}")),
        }
    }
}

impl CompositorCore {
    pub fn new() -> std::io::Result<Self> {
        let display = Display::new().map_err(|error| std::io::Error::other(error.to_string()))?;
        display
            .handle()
            .create_global::<CompositorState, WlCompositor, _>(6, ());
        display
            .handle()
            .create_global::<CompositorState, WlShm, _>(1, ());
        Ok(Self {
            display,
            state: CompositorState::default(),
            stopping: AtomicBool::new(false),
        })
    }

    pub fn adopt_client(&mut self, fd: RawFd) -> std::io::Result<()> {
        // Ownership crosses the JNI boundary only after ParcelFileDescriptor.detachFd().
        let stream = unsafe { UnixStream::from_raw_fd(fd) };
        stream.set_nonblocking(true)?;
        let mut handle = self.display.handle();
        handle
            .insert_client(stream, Arc::new(()))
            .map_err(|error| std::io::Error::other(error.to_string()))?;
        Ok(())
    }

    pub fn request_stop(&self) {
        self.stopping.store(true, Ordering::Release);
    }

    pub fn is_stopping(&self) -> bool {
        self.stopping.load(Ordering::Acquire)
    }

    pub fn dispatch_once(&mut self) -> std::io::Result<usize> {
        let dispatched = self.display.dispatch_clients(&mut self.state)?;
        self.display.flush_clients()?;
        Ok(dispatched)
    }

    pub fn compositor_bind_count(&self) -> u32 {
        self.state.compositor_binds
    }

    pub fn shm_bind_count(&self) -> u32 {
        self.state.shm_binds
    }

    pub fn shm_pool_count(&self) -> u32 {
        self.state.shm_pool_count
    }

    pub fn shm_buffer_count(&self) -> u32 {
        self.state.shm_buffer_count
    }

    pub fn last_buffer_checksum(&self) -> u32 {
        self.state.last_buffer_checksum
    }

    pub fn surface_count(&self) -> u32 {
        self.state.surface_count
    }

    pub fn surface_commit_count(&self) -> u32 {
        self.state.surface_commit_count
    }

    pub fn last_frame_width(&self) -> u32 {
        self.state.last_frame_width
    }

    pub fn last_frame_height(&self) -> u32 {
        self.state.last_frame_height
    }

    pub fn last_frame_checksum(&self) -> u32 {
        self.state.last_frame_checksum
    }
}

fn append_wayland_header(bytes: &mut Vec<u8>, object_id: u32, opcode: u16, size: u16) {
    bytes.extend_from_slice(&object_id.to_ne_bytes());
    bytes.extend_from_slice(&((u32::from(size) << 16) | u32::from(opcode)).to_ne_bytes());
}

fn send_fd(socket_fd: RawFd, bytes: &[u8], transferred_fd: RawFd) -> io::Result<()> {
    let mut io_vector = libc::iovec {
        iov_base: bytes.as_ptr().cast_mut().cast(),
        iov_len: bytes.len(),
    };
    let control_size = unsafe { libc::CMSG_SPACE(size_of::<RawFd>() as u32) } as usize;
    let control_words = control_size.div_ceil(size_of::<usize>());
    let mut control = vec![0usize; control_words];
    let mut message: libc::msghdr = unsafe { zeroed() };
    message.msg_iov = &mut io_vector;
    message.msg_iovlen = 1;
    message.msg_control = control.as_mut_ptr().cast();
    message.msg_controllen = control_size;

    let header = unsafe { libc::CMSG_FIRSTHDR(&message) };
    if header.is_null() {
        return Err(io::Error::other("could not allocate SCM_RIGHTS header"));
    }
    unsafe {
        (*header).cmsg_level = libc::SOL_SOCKET;
        (*header).cmsg_type = libc::SCM_RIGHTS;
        (*header).cmsg_len = libc::CMSG_LEN(size_of::<RawFd>() as u32) as usize;
        ptr::write(libc::CMSG_DATA(header).cast::<RawFd>(), transferred_fd);
    }

    let sent = unsafe { libc::sendmsg(socket_fd, &message, libc::MSG_NOSIGNAL) };
    if sent < 0 {
        return Err(io::Error::last_os_error());
    }
    if sent as usize != bytes.len() {
        return Err(io::Error::new(
            io::ErrorKind::WriteZero,
            "partial Wayland sendmsg",
        ));
    }
    Ok(())
}

fn send_probe_shm_pool_request(
    socket_fd: RawFd,
    pool_id: u32,
    pool_size: usize,
    callback_id: u32,
) -> io::Result<()> {
    if pool_size == 0 || pool_size > i32::MAX as usize {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "invalid probe pool size",
        ));
    }
    let name = b"archphene-probe\0";
    let raw_fd = unsafe {
        libc::syscall(
            libc::SYS_memfd_create,
            name.as_ptr().cast::<libc::c_char>(),
            libc::MFD_CLOEXEC,
        ) as RawFd
    };
    if raw_fd < 0 {
        return Err(io::Error::last_os_error());
    }
    let file = unsafe { File::from_raw_fd(raw_fd) };
    file.set_len(pool_size as u64)?;
    let pixels: Vec<u8> = (0..pool_size)
        .map(|index| (index % 251 + 1) as u8)
        .collect();
    file.write_all_at(&pixels, 0)?;

    let mut request = Vec::with_capacity(28);
    append_wayland_header(&mut request, 6, 0, 16);
    request.extend_from_slice(&pool_id.to_ne_bytes());
    request.extend_from_slice(&(pool_size as i32).to_ne_bytes());
    append_wayland_header(&mut request, 1, 0, 12);
    request.extend_from_slice(&callback_id.to_ne_bytes());
    send_fd(socket_fd, &request, file.as_raw_fd())
}

#[unsafe(no_mangle)]
pub extern "C" fn archphene_compositor_protocol_version() -> u32 {
    1
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeProtocolVersion(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
) -> i32 {
    archphene_compositor_protocol_version() as i32
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeCreateCore(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
) -> i64 {
    match CompositorCore::new() {
        Ok(core) => Box::into_raw(Box::new(core)) as i64,
        Err(_) => 0,
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeAdoptClient(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    fd: i32,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    match core.adopt_client(fd) {
        Ok(()) => 0,
        Err(_) => -2,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeSendShmPoolRequest(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    socket_fd: i32,
    pool_id: i32,
    pool_size: i32,
    callback_id: i32,
) -> i32 {
    let (Ok(pool_id), Ok(pool_size), Ok(callback_id)) = (
        u32::try_from(pool_id),
        usize::try_from(pool_size),
        u32::try_from(callback_id),
    ) else {
        return -1;
    };
    match send_probe_shm_pool_request(socket_fd, pool_id, pool_size, callback_id) {
        Ok(()) => 0,
        Err(_) => -2,
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeDispatchOnce(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    match core.dispatch_once() {
        Ok(count) => i32::try_from(count).unwrap_or(i32::MAX),
        Err(_) => -2,
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeCompositorBindCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.compositor_bind_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeShmBindCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.shm_bind_count()).unwrap_or(i32::MAX)
}
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeShmPoolCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.shm_pool_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeShmBufferCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.shm_buffer_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeLastBufferChecksum(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.last_buffer_checksum()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeSurfaceCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.surface_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeSurfaceCommitCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.surface_commit_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeLastFrameWidth(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.last_frame_width()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeLastFrameHeight(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.last_frame_height()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeLastFrameChecksum(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.last_frame_checksum()).unwrap_or(i32::MAX)
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeCopyLastFrameToBitmap(
    environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    bitmap: *mut std::ffi::c_void,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    copy_last_frame_to_bitmap(core, environment, bitmap)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeDestroyCore(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) {
    if !handle.is_positive() {
        return;
    }
    drop(unsafe { Box::from_raw(handle as *mut CompositorCore) });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn converts_wayland_argb_and_xrgb_to_android_rgba() {
        let source = [1, 2, 3, 4, 5, 6, 7, 8];
        let mut argb = [0; 8];
        copy_wayland_pixels_to_android(&source, wl_shm::Format::Argb8888, &mut argb)
            .expect("ARGB conversion");
        assert_eq!(argb, [3, 2, 1, 4, 7, 6, 5, 8]);

        let mut xrgb = [0; 8];
        copy_wayland_pixels_to_android(&source, wl_shm::Format::Xrgb8888, &mut xrgb)
            .expect("XRGB conversion");
        assert_eq!(xrgb, [3, 2, 1, 255, 7, 6, 5, 255]);
    }

    #[test]
    fn validates_shm_buffer_geometry() {
        assert_eq!(validate_buffer_geometry(32, 0, 4, 2, 16), Ok(0..32));
        assert_eq!(validate_buffer_geometry(40, 0, 4, 2, 24), Ok(0..40));
        assert!(validate_buffer_geometry(31, 0, 4, 2, 16).is_err());
        assert!(validate_buffer_geometry(32, -1, 4, 2, 16).is_err());
        assert!(validate_buffer_geometry(32, 0, 4, 2, 15).is_err());
        assert!(validate_buffer_geometry(32, 0, 0, 2, 16).is_err());
    }

    #[test]
    fn creates_wayland_display_and_reports_protocol_version() {
        let core = CompositorCore::new().expect("Wayland display");
        assert!(!core.is_stopping());
        assert_eq!(core.compositor_bind_count(), 0);
        assert_eq!(core.shm_bind_count(), 0);
        assert_eq!(core.shm_pool_count(), 0);
        assert_eq!(core.shm_buffer_count(), 0);
        assert_eq!(core.last_buffer_checksum(), 0);
        assert_eq!(core.surface_count(), 0);
        assert_eq!(core.surface_commit_count(), 0);
        assert_eq!(core.last_frame_width(), 0);
        assert_eq!(core.last_frame_height(), 0);
        assert_eq!(core.last_frame_checksum(), 0);
        assert_eq!(archphene_compositor_protocol_version(), 1);
    }
}
