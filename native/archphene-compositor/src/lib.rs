use std::collections::VecDeque;
use std::fs::File;
use std::io;
use std::mem::{size_of, zeroed};
use std::ops::Range;
use std::os::fd::{AsFd, AsRawFd, FromRawFd, RawFd};
use std::os::unix::fs::FileExt;
use std::os::unix::net::UnixStream;
use std::ptr;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, Mutex};

use wayland_protocols::xdg::shell::server::xdg_popup::{self, XdgPopup};
use wayland_protocols::xdg::shell::server::xdg_positioner::{self, XdgPositioner};
use wayland_protocols::xdg::shell::server::xdg_surface::{self, XdgSurface};
use wayland_protocols::xdg::shell::server::xdg_toplevel::{self, XdgToplevel};
use wayland_protocols::xdg::shell::server::xdg_wm_base::{self, XdgWmBase};
use wayland_server::protocol::wl_buffer::{self, WlBuffer};
use wayland_server::protocol::wl_callback::{self, WlCallback};
use wayland_server::protocol::wl_compositor::{self, WlCompositor};
use wayland_server::protocol::wl_keyboard::{self, WlKeyboard};
use wayland_server::protocol::wl_output::{self, WlOutput};
use wayland_server::protocol::wl_pointer::{self, WlPointer};
use wayland_server::protocol::wl_region::{self, WlRegion};
use wayland_server::protocol::wl_seat::{self, WlSeat};
use wayland_server::protocol::wl_shm::{self, WlShm};
use wayland_server::protocol::wl_shm_pool::{self, WlShmPool};
use wayland_server::protocol::wl_subcompositor::{self, WlSubcompositor};
use wayland_server::protocol::wl_subsurface::{self, WlSubsurface};
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
    subcompositor_binds: u32,
    subsurface_count: u32,
    subsurfaces: Vec<WlSubsurface>,
    last_frame_width: u32,
    last_frame_height: u32,
    last_frame_checksum: u32,
    root_surface: Option<WlSurface>,
    root_frame: Option<Arc<CommittedFrame>>,
    last_frame: Option<Arc<CommittedFrame>>,
    xdg_wm_base_binds: u32,
    xdg_positioner_count: u32,
    xdg_positioner_request_count: u32,
    xdg_popup_count: u32,
    xdg_popup_done_count: u32,
    popups: Vec<XdgPopup>,
    popup_grab: Option<PopupGrabState>,
    popup_grab_serial: Option<PopupGrabSerial>,
    xdg_surface_count: u32,
    xdg_toplevel_count: u32,
    xdg_ack_count: u32,
    next_configure_serial: u32,
    output_binds: u32,
    output_event_count: u32,
    output_width: i32,
    output_height: i32,
    output_scale: i32,
    outputs: Vec<WlOutput>,
    seat_binds: u32,
    pointer_count: u32,
    pointer_event_count: u32,
    next_input_serial: u32,
    pointers: Vec<WlPointer>,
    pointer_focus_surface: Option<WlSurface>,
    pointer_inside: bool,
    pointer_pressed: bool,
    pointer_x: f64,
    pointer_y: f64,
    keyboard_count: u32,
    keyboard_event_count: u32,
    keyboards: Vec<WlKeyboard>,
    keyboard_focus_surface: Option<WlSurface>,
    pressed_keys: Vec<u32>,
}

const XKB_KEYMAP: &[u8] = concat!(include_str!("archphene-us.xkb"), "\0").as_bytes();

#[derive(Default)]
pub struct SurfaceData {
    inner: Mutex<SurfaceState>,
}

#[derive(Default)]
struct SurfaceState {
    pending_buffer: Option<Option<SurfaceBuffer>>,
    pending_damage: bool,
    pending_callbacks: Vec<WlCallback>,
    pending_input_region: Option<Option<RegionState>>,
    committed_input_region: Option<RegionState>,
    cached_input_region: Option<Option<RegionState>>,
    committed_frame: Option<Arc<CommittedFrame>>,
    cached_frame: Option<Option<Arc<CommittedFrame>>>,
    cached_callbacks: Vec<WlCallback>,
    has_xdg_surface: bool,
    role: Option<SurfaceRole>,
    xdg_surface: Option<XdgSurface>,
    xdg_toplevel: Option<XdgToplevel>,
    xdg_popup: Option<XdgPopup>,
    subsurface: Option<WlSubsurface>,
    children_below: Vec<WlSurface>,
    children_above: Vec<WlSurface>,
    pending_subsurface_stack: Vec<(WlSurface, WlSurface, bool)>,
    xdg_configured: bool,
}

#[derive(Clone, Copy, Eq, PartialEq)]
enum SurfaceRole {
    XdgToplevel,
    XdgPopup,
    Subsurface,
}

#[derive(Default)]
struct XdgWmBaseData {
    child_count: Arc<AtomicU32>,
}

#[derive(Default)]
struct XdgPositionerData {
    state: Mutex<XdgPositionerState>,
}

#[derive(Clone, Default)]
struct XdgPositionerState {
    size: Option<(i32, i32)>,
    anchor_rect: Option<(i32, i32, i32, i32)>,
    anchor: Option<xdg_positioner::Anchor>,
    gravity: Option<xdg_positioner::Gravity>,
    constraint_adjustment: Option<xdg_positioner::ConstraintAdjustment>,
    offset: (i32, i32),
    reactive: bool,
    parent_size: Option<(i32, i32)>,
    parent_configure: Option<u32>,
}

impl XdgPositionerState {
    fn has_constraint(&self, constraint: xdg_positioner::ConstraintAdjustment) -> bool {
        self.constraint_adjustment
            .is_some_and(|adjustments| adjustments.contains(constraint))
    }

    fn anchor_has_edge(&self, edge: xdg_positioner::Anchor) -> bool {
        self.anchor.is_some_and(|anchor| match edge {
            xdg_positioner::Anchor::Top => matches!(
                anchor,
                xdg_positioner::Anchor::Top
                    | xdg_positioner::Anchor::TopLeft
                    | xdg_positioner::Anchor::TopRight
            ),
            xdg_positioner::Anchor::Bottom => matches!(
                anchor,
                xdg_positioner::Anchor::Bottom
                    | xdg_positioner::Anchor::BottomLeft
                    | xdg_positioner::Anchor::BottomRight
            ),
            xdg_positioner::Anchor::Left => matches!(
                anchor,
                xdg_positioner::Anchor::Left
                    | xdg_positioner::Anchor::TopLeft
                    | xdg_positioner::Anchor::BottomLeft
            ),
            xdg_positioner::Anchor::Right => matches!(
                anchor,
                xdg_positioner::Anchor::Right
                    | xdg_positioner::Anchor::TopRight
                    | xdg_positioner::Anchor::BottomRight
            ),
            _ => false,
        })
    }

    fn gravity_has_edge(&self, edge: xdg_positioner::Gravity) -> bool {
        self.gravity.is_some_and(|gravity| match edge {
            xdg_positioner::Gravity::Top => matches!(
                gravity,
                xdg_positioner::Gravity::Top
                    | xdg_positioner::Gravity::TopLeft
                    | xdg_positioner::Gravity::TopRight
            ),
            xdg_positioner::Gravity::Bottom => matches!(
                gravity,
                xdg_positioner::Gravity::Bottom
                    | xdg_positioner::Gravity::BottomLeft
                    | xdg_positioner::Gravity::BottomRight
            ),
            xdg_positioner::Gravity::Left => matches!(
                gravity,
                xdg_positioner::Gravity::Left
                    | xdg_positioner::Gravity::TopLeft
                    | xdg_positioner::Gravity::BottomLeft
            ),
            xdg_positioner::Gravity::Right => matches!(
                gravity,
                xdg_positioner::Gravity::Right
                    | xdg_positioner::Gravity::TopRight
                    | xdg_positioner::Gravity::BottomRight
            ),
            _ => false,
        })
    }

    fn geometry(&self) -> Option<(i32, i32, i32, i32)> {
        let (width, height) = self.size?;
        let (anchor_x, anchor_y, anchor_width, anchor_height) = self.anchor_rect?;
        let mut x = anchor_x + self.offset.0;
        let mut y = anchor_y + self.offset.1;
        x += if self.anchor_has_edge(xdg_positioner::Anchor::Left) {
            0
        } else if self.anchor_has_edge(xdg_positioner::Anchor::Right) {
            anchor_width
        } else {
            anchor_width / 2
        };
        y += if self.anchor_has_edge(xdg_positioner::Anchor::Top) {
            0
        } else if self.anchor_has_edge(xdg_positioner::Anchor::Bottom) {
            anchor_height
        } else {
            anchor_height / 2
        };
        if self.gravity_has_edge(xdg_positioner::Gravity::Top) {
            y -= height;
        } else if !self.gravity_has_edge(xdg_positioner::Gravity::Bottom) {
            y -= height / 2;
        }
        if self.gravity_has_edge(xdg_positioner::Gravity::Left) {
            x -= width;
        } else if !self.gravity_has_edge(xdg_positioner::Gravity::Right) {
            x -= width / 2;
        }
        Some((x, y, width, height))
    }

    fn flipped_horizontal(&self) -> Self {
        let mut flipped = self.clone();
        flipped.anchor = self.anchor.map(|anchor| match anchor {
            xdg_positioner::Anchor::Left => xdg_positioner::Anchor::Right,
            xdg_positioner::Anchor::Right => xdg_positioner::Anchor::Left,
            xdg_positioner::Anchor::TopLeft => xdg_positioner::Anchor::TopRight,
            xdg_positioner::Anchor::TopRight => xdg_positioner::Anchor::TopLeft,
            xdg_positioner::Anchor::BottomLeft => xdg_positioner::Anchor::BottomRight,
            xdg_positioner::Anchor::BottomRight => xdg_positioner::Anchor::BottomLeft,
            _ => anchor,
        });
        flipped.gravity = self.gravity.map(|gravity| match gravity {
            xdg_positioner::Gravity::Left => xdg_positioner::Gravity::Right,
            xdg_positioner::Gravity::Right => xdg_positioner::Gravity::Left,
            xdg_positioner::Gravity::TopLeft => xdg_positioner::Gravity::TopRight,
            xdg_positioner::Gravity::TopRight => xdg_positioner::Gravity::TopLeft,
            xdg_positioner::Gravity::BottomLeft => xdg_positioner::Gravity::BottomRight,
            xdg_positioner::Gravity::BottomRight => xdg_positioner::Gravity::BottomLeft,
            _ => gravity,
        });
        flipped
    }

    fn flipped_vertical(&self) -> Self {
        let mut flipped = self.clone();
        flipped.anchor = self.anchor.map(|anchor| match anchor {
            xdg_positioner::Anchor::Top => xdg_positioner::Anchor::Bottom,
            xdg_positioner::Anchor::Bottom => xdg_positioner::Anchor::Top,
            xdg_positioner::Anchor::TopLeft => xdg_positioner::Anchor::BottomLeft,
            xdg_positioner::Anchor::TopRight => xdg_positioner::Anchor::BottomRight,
            xdg_positioner::Anchor::BottomLeft => xdg_positioner::Anchor::TopLeft,
            xdg_positioner::Anchor::BottomRight => xdg_positioner::Anchor::TopRight,
            _ => anchor,
        });
        flipped.gravity = self.gravity.map(|gravity| match gravity {
            xdg_positioner::Gravity::Top => xdg_positioner::Gravity::Bottom,
            xdg_positioner::Gravity::Bottom => xdg_positioner::Gravity::Top,
            xdg_positioner::Gravity::TopLeft => xdg_positioner::Gravity::BottomLeft,
            xdg_positioner::Gravity::TopRight => xdg_positioner::Gravity::BottomRight,
            xdg_positioner::Gravity::BottomLeft => xdg_positioner::Gravity::TopLeft,
            xdg_positioner::Gravity::BottomRight => xdg_positioner::Gravity::TopRight,
            _ => gravity,
        });
        flipped
    }

    fn constrained_geometry(&self, bounds: PopupBounds) -> Option<(i32, i32, i32, i32)> {
        let mut adjusted_positioner = self.clone();
        let mut geometry = adjusted_positioner.geometry()?;

        if self.has_constraint(xdg_positioner::ConstraintAdjustment::FlipX)
            && horizontal_overflow(geometry, bounds) > 0
        {
            let flipped = adjusted_positioner.flipped_horizontal();
            let flipped_geometry = flipped.geometry()?;
            if horizontal_overflow(flipped_geometry, bounds) < horizontal_overflow(geometry, bounds)
            {
                adjusted_positioner = flipped;
                geometry = flipped_geometry;
            }
        }
        if self.has_constraint(xdg_positioner::ConstraintAdjustment::FlipY)
            && vertical_overflow(geometry, bounds) > 0
        {
            let flipped = adjusted_positioner.flipped_vertical();
            let flipped_geometry = flipped.geometry()?;
            if vertical_overflow(flipped_geometry, bounds) < vertical_overflow(geometry, bounds) {
                geometry = flipped_geometry;
            }
        }

        if self.has_constraint(xdg_positioner::ConstraintAdjustment::SlideX) {
            geometry.0 = slide_axis(geometry.0, geometry.2, bounds.left, bounds.right);
        }
        if self.has_constraint(xdg_positioner::ConstraintAdjustment::SlideY) {
            geometry.1 = slide_axis(geometry.1, geometry.3, bounds.top, bounds.bottom);
        }
        if self.has_constraint(xdg_positioner::ConstraintAdjustment::ResizeX) {
            (geometry.0, geometry.2) =
                resize_axis(geometry.0, geometry.2, bounds.left, bounds.right);
        }
        if self.has_constraint(xdg_positioner::ConstraintAdjustment::ResizeY) {
            (geometry.1, geometry.3) =
                resize_axis(geometry.1, geometry.3, bounds.top, bounds.bottom);
        }
        Some(geometry)
    }
}

#[derive(Clone, Copy)]
struct PopupBounds {
    left: i32,
    top: i32,
    right: i32,
    bottom: i32,
}

fn axis_overflow(position: i32, size: i32, minimum: i32, maximum: i32) -> i64 {
    let start = i64::from(position);
    let end = start + i64::from(size);
    (i64::from(minimum) - start).max(0) + (end - i64::from(maximum)).max(0)
}

fn horizontal_overflow(geometry: (i32, i32, i32, i32), bounds: PopupBounds) -> i64 {
    axis_overflow(geometry.0, geometry.2, bounds.left, bounds.right)
}

fn vertical_overflow(geometry: (i32, i32, i32, i32), bounds: PopupBounds) -> i64 {
    axis_overflow(geometry.1, geometry.3, bounds.top, bounds.bottom)
}

fn slide_axis(position: i32, size: i32, minimum: i32, maximum: i32) -> i32 {
    let available = maximum.saturating_sub(minimum);
    if size >= available {
        minimum
    } else {
        position.clamp(minimum, maximum.saturating_sub(size))
    }
}

fn resize_axis(position: i32, size: i32, minimum: i32, maximum: i32) -> (i32, i32) {
    let available = maximum.saturating_sub(minimum).max(1);
    let start = position.max(minimum).min(maximum.saturating_sub(1));
    let end = position
        .saturating_add(size)
        .min(maximum)
        .max(start.saturating_add(1));
    (start, end.saturating_sub(start).min(available).max(1))
}

struct XdgPopupData {
    xdg_surface: XdgSurface,
    parent: XdgSurface,
    positioner: Mutex<XdgPositionerState>,
    applied_geometry: Mutex<Option<(i32, i32, i32, i32)>>,
    grabbed: AtomicBool,
    dismissed: AtomicBool,
}

struct PopupGrabSerial {
    serial: u32,
    surface: WlSurface,
}

struct PopupGrabState {
    seat: WlSeat,
    root: WlSurface,
    serial: u32,
    stack: Vec<XdgPopup>,
    active: bool,
}

struct SubsurfaceData {
    surface: WlSurface,
    parent: WlSurface,
    position: Mutex<(i32, i32)>,
    pending_position: Mutex<Option<(i32, i32)>>,
    synchronized: AtomicBool,
}

struct XdgSurfaceData {
    wl_surface: WlSurface,
    wm_base: XdgWmBase,
    wm_child_count: Arc<AtomicU32>,
    state: Mutex<XdgSurfaceState>,
}

#[derive(Default)]
struct XdgSurfaceState {
    role_active: bool,
    pending_configures: VecDeque<u32>,
    acknowledged_configure: Option<u32>,
}

struct XdgToplevelData {
    xdg_surface: XdgSurface,
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

#[derive(Clone, Copy)]
struct RegionRectangle {
    x: i32,
    y: i32,
    width: i32,
    height: i32,
}

impl RegionRectangle {
    fn contains(self, x: f64, y: f64) -> bool {
        x >= f64::from(self.x)
            && y >= f64::from(self.y)
            && x < f64::from(self.x.saturating_add(self.width))
            && y < f64::from(self.y.saturating_add(self.height))
    }
}

#[derive(Clone, Copy)]
enum RegionOperation {
    Add(RegionRectangle),
    Subtract(RegionRectangle),
}

#[derive(Clone, Default)]
struct RegionState {
    operations: Vec<RegionOperation>,
}

impl RegionState {
    fn contains(&self, x: f64, y: f64) -> bool {
        self.operations
            .iter()
            .fold(false, |inside, operation| match operation {
                RegionOperation::Add(rectangle) if rectangle.contains(x, y) => true,
                RegionOperation::Subtract(rectangle) if rectangle.contains(x, y) => false,
                _ => inside,
            })
    }
}

#[derive(Default)]
pub struct RegionData {
    inner: Mutex<RegionState>,
}

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

impl GlobalDispatch<XdgWmBase, ()> for CompositorState {
    fn bind(
        state: &mut Self,
        _handle: &DisplayHandle,
        _client: &Client,
        resource: New<XdgWmBase>,
        _global_data: &(),
        data_init: &mut DataInit<'_, Self>,
    ) {
        data_init.init(resource, XdgWmBaseData::default());
        state.xdg_wm_base_binds = state.xdg_wm_base_binds.saturating_add(1);
    }
}

impl Dispatch<XdgWmBase, XdgWmBaseData> for CompositorState {
    fn request(
        state: &mut Self,
        _client: &Client,
        resource: &XdgWmBase,
        request: xdg_wm_base::Request,
        data: &XdgWmBaseData,
        _handle: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            xdg_wm_base::Request::Destroy => {
                if data.child_count.load(Ordering::Acquire) != 0 {
                    resource.post_error(
                        xdg_wm_base::Error::DefunctSurfaces,
                        "xdg_wm_base destroyed before its xdg_surface children",
                    );
                }
            }
            xdg_wm_base::Request::CreatePositioner { id } => {
                data_init.init(id, XdgPositionerData::default());
                state.xdg_positioner_count = state.xdg_positioner_count.saturating_add(1);
            }
            xdg_wm_base::Request::GetXdgSurface { id, surface } => {
                let Some(surface_data) = surface.data::<SurfaceData>() else {
                    resource.post_error(xdg_wm_base::Error::Role, "unknown wl_surface");
                    return;
                };
                {
                    let mut surface_state = surface_data
                        .inner
                        .lock()
                        .unwrap_or_else(|error| error.into_inner());
                    if surface_state.has_xdg_surface {
                        resource.post_error(
                            xdg_wm_base::Error::Role,
                            "wl_surface already has an xdg_surface",
                        );
                        return;
                    }
                    surface_state.has_xdg_surface = true;
                }
                let xdg_surface = data_init.init(
                    id,
                    XdgSurfaceData {
                        wl_surface: surface.clone(),
                        wm_base: resource.clone(),
                        wm_child_count: Arc::clone(&data.child_count),
                        state: Mutex::new(XdgSurfaceState::default()),
                    },
                );
                surface_data
                    .inner
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                    .xdg_surface = Some(xdg_surface);
                data.child_count.fetch_add(1, Ordering::AcqRel);
                state.xdg_surface_count = state.xdg_surface_count.saturating_add(1);
            }
            xdg_wm_base::Request::Pong { .. } => {}
            _ => unreachable!("xdg_wm_base request added without an implementation"),
        }
    }
}

impl Dispatch<XdgPositioner, XdgPositionerData> for CompositorState {
    fn request(
        state: &mut Self,
        _client: &Client,
        resource: &XdgPositioner,
        request: xdg_positioner::Request,
        data: &XdgPositionerData,
        _handle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        let mut positioner = data.state.lock().unwrap_or_else(|error| error.into_inner());
        let accepted = match request {
            xdg_positioner::Request::Destroy => false,
            xdg_positioner::Request::SetSize { width, height } => {
                if width <= 0 || height <= 0 {
                    resource.post_error(
                        xdg_positioner::Error::InvalidInput,
                        "positioner size must be positive",
                    );
                    false
                } else {
                    positioner.size = Some((width, height));
                    true
                }
            }
            xdg_positioner::Request::SetAnchorRect {
                x,
                y,
                width,
                height,
            } => {
                if width <= 0 || height <= 0 {
                    resource.post_error(
                        xdg_positioner::Error::InvalidInput,
                        "positioner anchor rectangle must be positive",
                    );
                    false
                } else {
                    positioner.anchor_rect = Some((x, y, width, height));
                    true
                }
            }
            xdg_positioner::Request::SetAnchor { anchor } => {
                if let WEnum::Value(anchor) = anchor {
                    positioner.anchor = Some(anchor);
                    true
                } else {
                    resource.post_error(
                        xdg_positioner::Error::InvalidInput,
                        "unknown positioner anchor",
                    );
                    false
                }
            }
            xdg_positioner::Request::SetGravity { gravity } => {
                if let WEnum::Value(gravity) = gravity {
                    positioner.gravity = Some(gravity);
                    true
                } else {
                    resource.post_error(
                        xdg_positioner::Error::InvalidInput,
                        "unknown positioner gravity",
                    );
                    false
                }
            }
            xdg_positioner::Request::SetConstraintAdjustment {
                constraint_adjustment,
            } => {
                if let WEnum::Value(constraint_adjustment) = constraint_adjustment {
                    positioner.constraint_adjustment = Some(constraint_adjustment);
                    true
                } else {
                    resource.post_error(
                        xdg_positioner::Error::InvalidInput,
                        "unknown positioner constraint adjustment",
                    );
                    false
                }
            }
            xdg_positioner::Request::SetOffset { x, y } => {
                positioner.offset = (x, y);
                true
            }
            xdg_positioner::Request::SetReactive => {
                positioner.reactive = true;
                true
            }
            xdg_positioner::Request::SetParentSize {
                parent_width,
                parent_height,
            } => {
                if parent_width <= 0 || parent_height <= 0 {
                    resource.post_error(
                        xdg_positioner::Error::InvalidInput,
                        "positioner parent size must be positive",
                    );
                    false
                } else {
                    positioner.parent_size = Some((parent_width, parent_height));
                    true
                }
            }
            xdg_positioner::Request::SetParentConfigure { serial } => {
                positioner.parent_configure = Some(serial);
                true
            }
            _ => unreachable!("xdg_positioner request added without an implementation"),
        };
        if accepted {
            state.xdg_positioner_request_count =
                state.xdg_positioner_request_count.saturating_add(1);
        }
    }

    fn destroyed(
        state: &mut Self,
        _client: ClientId,
        _resource: &XdgPositioner,
        _data: &XdgPositionerData,
    ) {
        state.xdg_positioner_count = state.xdg_positioner_count.saturating_sub(1);
    }
}

impl Dispatch<XdgSurface, XdgSurfaceData> for CompositorState {
    fn request(
        state: &mut Self,
        _client: &Client,
        resource: &XdgSurface,
        request: xdg_surface::Request,
        data: &XdgSurfaceData,
        _handle: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            xdg_surface::Request::Destroy => {
                if data
                    .state
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                    .role_active
                {
                    data.wm_base.post_error(
                        xdg_wm_base::Error::Role,
                        "xdg_surface destroyed before its role object",
                    );
                }
            }
            xdg_surface::Request::GetToplevel { id } => {
                let Some(surface_data) = data.wl_surface.data::<SurfaceData>() else {
                    data.wm_base
                        .post_error(xdg_wm_base::Error::Role, "unknown wl_surface");
                    return;
                };
                let mut surface_state = surface_data
                    .inner
                    .lock()
                    .unwrap_or_else(|error| error.into_inner());
                let mut xdg_state = data.state.lock().unwrap_or_else(|error| error.into_inner());
                if xdg_state.role_active {
                    resource.post_error(
                        xdg_surface::Error::AlreadyConstructed,
                        "xdg_surface already has an active role",
                    );
                    return;
                }
                match surface_state.role {
                    None => surface_state.role = Some(SurfaceRole::XdgToplevel),
                    Some(_) => {
                        resource.post_error(
                            xdg_surface::Error::AlreadyConstructed,
                            "wl_surface already has an active role",
                        );
                        return;
                    }
                }
                xdg_state.role_active = true;
                let toplevel = data_init.init(
                    id,
                    XdgToplevelData {
                        xdg_surface: resource.clone(),
                    },
                );
                surface_state.xdg_toplevel = Some(toplevel);
                state.xdg_toplevel_count = state.xdg_toplevel_count.saturating_add(1);
            }
            xdg_surface::Request::GetPopup {
                id,
                parent,
                positioner,
            } => {
                let Some(parent) = parent else {
                    resource.post_error(
                        xdg_surface::Error::InvalidSize,
                        "xdg_popup requires a parent",
                    );
                    return;
                };
                let Some(parent_data) = parent.data::<XdgSurfaceData>() else {
                    resource.post_error(
                        xdg_surface::Error::NotConstructed,
                        "xdg_popup parent is unknown",
                    );
                    return;
                };
                let parent_mapped = parent_data
                    .wl_surface
                    .data::<SurfaceData>()
                    .and_then(|surface| {
                        surface
                            .inner
                            .lock()
                            .unwrap_or_else(|error| error.into_inner())
                            .committed_frame
                            .as_ref()
                            .map(|_| ())
                    })
                    .is_some();
                if !parent_mapped {
                    resource.post_error(
                        xdg_surface::Error::NotConstructed,
                        "xdg_popup parent is not mapped",
                    );
                    return;
                }
                let Some(positioner_data) = positioner.data::<XdgPositionerData>() else {
                    resource.post_error(
                        xdg_surface::Error::InvalidSize,
                        "xdg_popup positioner is unknown",
                    );
                    return;
                };
                let positioner_state = positioner_data
                    .state
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                    .clone();
                if positioner_state.geometry().is_none() {
                    resource.post_error(
                        xdg_surface::Error::InvalidSize,
                        "xdg_popup positioner is incomplete",
                    );
                    return;
                }

                let Some(surface_data) = data.wl_surface.data::<SurfaceData>() else {
                    resource.post_error(
                        xdg_surface::Error::NotConstructed,
                        "xdg_popup wl_surface is unknown",
                    );
                    return;
                };
                let mut surface_state = surface_data
                    .inner
                    .lock()
                    .unwrap_or_else(|error| error.into_inner());
                let mut xdg_state = data.state.lock().unwrap_or_else(|error| error.into_inner());
                if xdg_state.role_active || surface_state.role.is_some() {
                    resource.post_error(
                        xdg_surface::Error::AlreadyConstructed,
                        "xdg_surface already has an active role",
                    );
                    return;
                }
                surface_state.role = Some(SurfaceRole::XdgPopup);
                xdg_state.role_active = true;
                let popup = data_init.init(
                    id,
                    XdgPopupData {
                        xdg_surface: resource.clone(),
                        parent,
                        positioner: Mutex::new(positioner_state),
                        applied_geometry: Mutex::new(None),
                        grabbed: AtomicBool::new(false),
                        dismissed: AtomicBool::new(false),
                    },
                );
                state.popups.push(popup.clone());
                surface_state.xdg_popup = Some(popup);
                state.xdg_popup_count = state.xdg_popup_count.saturating_add(1);
            }
            xdg_surface::Request::SetWindowGeometry { width, height, .. } => {
                if width <= 0 || height <= 0 {
                    resource.post_error(
                        xdg_surface::Error::InvalidSize,
                        "window geometry must be positive",
                    );
                }
            }
            xdg_surface::Request::AckConfigure { serial } => {
                let mut xdg_state = data.state.lock().unwrap_or_else(|error| error.into_inner());
                let Some(position) = xdg_state
                    .pending_configures
                    .iter()
                    .position(|pending| *pending == serial)
                else {
                    resource.post_error(
                        xdg_surface::Error::InvalidSerial,
                        "ack_configure did not match a pending serial",
                    );
                    return;
                };
                xdg_state.pending_configures.drain(..=position);
                xdg_state.acknowledged_configure = Some(serial);
                if let Some(surface_data) = data.wl_surface.data::<SurfaceData>() {
                    surface_data
                        .inner
                        .lock()
                        .unwrap_or_else(|error| error.into_inner())
                        .xdg_configured = true;
                }
                state.xdg_ack_count = state.xdg_ack_count.saturating_add(1);
            }
            _ => unreachable!("xdg_surface request added without an implementation"),
        }
    }

    fn destroyed(
        state: &mut Self,
        _client: ClientId,
        _resource: &XdgSurface,
        data: &XdgSurfaceData,
    ) {
        data.wm_child_count.fetch_sub(1, Ordering::AcqRel);
        state.xdg_surface_count = state.xdg_surface_count.saturating_sub(1);
        if let Some(surface_data) = data.wl_surface.data::<SurfaceData>() {
            let mut surface = surface_data
                .inner
                .lock()
                .unwrap_or_else(|error| error.into_inner());
            surface.has_xdg_surface = false;
            surface.xdg_surface = None;
            surface.xdg_configured = false;
        }
    }
}

impl Dispatch<XdgPopup, XdgPopupData> for CompositorState {
    fn request(
        state: &mut Self,
        _client: &Client,
        resource: &XdgPopup,
        request: xdg_popup::Request,
        data: &XdgPopupData,
        _handle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            xdg_popup::Request::Destroy => {
                if data.grabbed.load(Ordering::Acquire)
                    && state
                        .popup_grab
                        .as_ref()
                        .and_then(|grab| grab.stack.last())
                        .is_some_and(|topmost| topmost.id() != resource.id())
                {
                    if let Some(xdg_data) = data.xdg_surface.data::<XdgSurfaceData>() {
                        xdg_data.wm_base.post_error(
                            xdg_wm_base::Error::NotTheTopmostPopup,
                            "nested xdg_popups must be destroyed topmost first",
                        );
                    }
                }
            }
            xdg_popup::Request::Grab { seat, serial } => {
                let already_mapped = data
                    .xdg_surface
                    .data::<XdgSurfaceData>()
                    .and_then(|xdg_data| xdg_data.wl_surface.data::<SurfaceData>())
                    .is_some_and(|surface_data| {
                        surface_data
                            .inner
                            .lock()
                            .unwrap_or_else(|error| error.into_inner())
                            .committed_frame
                            .is_some()
                    });
                let serial_matches_client =
                    state.popup_grab_serial.as_ref().is_some_and(|candidate| {
                        candidate.serial == serial
                            && candidate.surface.id().same_client_as(&data.parent.id())
                    });
                let serial_extends_grab = state.popup_grab.as_ref().is_some_and(|grab| {
                    grab.active
                        && grab.serial == serial
                        && grab.seat.id() == seat.id()
                        && grab.root.id().same_client_as(&data.parent.id())
                });
                let is_topmost_popup = state
                    .popups
                    .iter()
                    .rev()
                    .find(|popup| popup.id().same_client_as(&resource.id()))
                    .is_some_and(|popup| popup.id() == resource.id());
                if serial == 0
                    || already_mapped
                    || !seat.id().same_client_as(&data.parent.id())
                    || (!serial_matches_client && !serial_extends_grab)
                    || !is_topmost_popup
                    || data.grabbed.load(Ordering::Acquire)
                {
                    resource.post_error(
                        xdg_popup::Error::InvalidGrab,
                        "xdg_popup grab requires an unmapped topmost popup and a valid input serial",
                    );
                    return;
                }

                let Some(parent_data) = data.parent.data::<XdgSurfaceData>() else {
                    resource.post_error(xdg_popup::Error::InvalidGrab, "popup parent is unknown");
                    return;
                };
                let parent_role =
                    parent_data
                        .wl_surface
                        .data::<SurfaceData>()
                        .and_then(|surface_data| {
                            surface_data
                                .inner
                                .lock()
                                .unwrap_or_else(|error| error.into_inner())
                                .role
                        });
                match state.popup_grab.as_mut() {
                    None if parent_role == Some(SurfaceRole::XdgToplevel) => {
                        state.popup_grab = Some(PopupGrabState {
                            seat,
                            root: parent_data.wl_surface.clone(),
                            serial,
                            stack: vec![resource.clone()],
                            active: true,
                        });
                    }
                    Some(grab)
                        if grab.active
                            && grab.seat.id() == seat.id()
                            && parent_role == Some(SurfaceRole::XdgPopup)
                            && grab.stack.last().is_some_and(|parent_popup| {
                                parent_popup.data::<XdgPopupData>().is_some_and(
                                    |parent_popup_data| {
                                        parent_popup_data.xdg_surface.id() == data.parent.id()
                                            && parent_popup_data.grabbed.load(Ordering::Acquire)
                                    },
                                )
                            }) =>
                    {
                        grab.stack.push(resource.clone());
                    }
                    _ => {
                        resource.post_error(
                            xdg_popup::Error::InvalidGrab,
                            "popup parent is not the active grab root or topmost grabbed popup",
                        );
                        return;
                    }
                }
                state.popup_grab_serial = None;
                data.grabbed.store(true, Ordering::Release);
                if let Some(xdg_data) = data.xdg_surface.data::<XdgSurfaceData>() {
                    set_keyboard_focus(state, Some(xdg_data.wl_surface.clone()));
                }
            }
            xdg_popup::Request::Reposition { positioner, token } => {
                let Some(positioner_data) = positioner.data::<XdgPositionerData>() else {
                    resource.post_error(
                        xdg_popup::Error::InvalidGrab,
                        "xdg_popup repositioner is unknown",
                    );
                    return;
                };
                let positioner = positioner_data
                    .state
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                    .clone();
                let Some(geometry) = constrained_popup_geometry(state, data, &positioner) else {
                    resource.post_error(
                        xdg_popup::Error::InvalidGrab,
                        "xdg_popup repositioner is incomplete",
                    );
                    return;
                };
                let (x, y, width, height) = geometry;
                *data
                    .positioner
                    .lock()
                    .unwrap_or_else(|error| error.into_inner()) = positioner;
                *data
                    .applied_geometry
                    .lock()
                    .unwrap_or_else(|error| error.into_inner()) = Some(geometry);
                state.next_configure_serial = state.next_configure_serial.wrapping_add(1).max(1);
                let serial = state.next_configure_serial;
                if let Some(xdg_data) = data.xdg_surface.data::<XdgSurfaceData>() {
                    xdg_data
                        .state
                        .lock()
                        .unwrap_or_else(|error| error.into_inner())
                        .pending_configures
                        .push_back(serial);
                }
                resource.configure(x, y, width, height);
                resource.repositioned(token);
                data.xdg_surface.configure(serial);
            }
            _ => unreachable!("xdg_popup request added without an implementation"),
        }
    }

    fn destroyed(state: &mut Self, _client: ClientId, resource: &XdgPopup, data: &XdgPopupData) {
        let mut restored_focus = None;
        let mut clear_grab = false;
        if data.grabbed.load(Ordering::Acquire) {
            if let Some(grab) = state.popup_grab.as_mut() {
                if grab
                    .stack
                    .last()
                    .is_some_and(|popup| popup.id() == resource.id())
                {
                    grab.stack.pop();
                    restored_focus = if grab.active {
                        grab.stack
                            .last()
                            .and_then(|popup| popup.data::<XdgPopupData>())
                            .and_then(|popup_data| popup_data.xdg_surface.data::<XdgSurfaceData>())
                            .map(|xdg_data| xdg_data.wl_surface.clone())
                            .or_else(|| Some(grab.root.clone()))
                    } else {
                        Some(grab.root.clone())
                    };
                    clear_grab = grab.stack.is_empty();
                }
            }
        }
        if let Some(xdg_data) = data.xdg_surface.data::<XdgSurfaceData>() {
            xdg_data
                .state
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .role_active = false;
            if let Some(surface_data) = xdg_data.wl_surface.data::<SurfaceData>() {
                let mut surface = surface_data
                    .inner
                    .lock()
                    .unwrap_or_else(|error| error.into_inner());
                surface.role = None;
                surface.xdg_popup = None;
                surface.xdg_configured = false;
            }
        }
        state.popups.retain(|popup| popup.id() != resource.id());
        update_composited_frame(state);
        if clear_grab {
            state.popup_grab = None;
        }
        if let Some(surface) = restored_focus {
            set_keyboard_focus(state, Some(surface));
        }
        state.xdg_popup_count = state.xdg_popup_count.saturating_sub(1);
    }
}

impl Dispatch<XdgToplevel, XdgToplevelData> for CompositorState {
    fn request(
        _state: &mut Self,
        _client: &Client,
        resource: &XdgToplevel,
        request: xdg_toplevel::Request,
        _data: &XdgToplevelData,
        _handle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            xdg_toplevel::Request::Destroy
            | xdg_toplevel::Request::SetParent { .. }
            | xdg_toplevel::Request::SetTitle { .. }
            | xdg_toplevel::Request::SetAppId { .. }
            | xdg_toplevel::Request::ShowWindowMenu { .. }
            | xdg_toplevel::Request::Move { .. }
            | xdg_toplevel::Request::Resize { .. }
            | xdg_toplevel::Request::SetMaximized
            | xdg_toplevel::Request::UnsetMaximized
            | xdg_toplevel::Request::SetFullscreen { .. }
            | xdg_toplevel::Request::UnsetFullscreen
            | xdg_toplevel::Request::SetMinimized => {}
            xdg_toplevel::Request::SetMaxSize { width, height }
            | xdg_toplevel::Request::SetMinSize { width, height } => {
                if width < 0 || height < 0 {
                    resource.post_error(
                        xdg_toplevel::Error::InvalidSize,
                        "minimum and maximum sizes cannot be negative",
                    );
                }
            }
            _ => unreachable!("xdg_toplevel request added without an implementation"),
        }
    }

    fn destroyed(
        state: &mut Self,
        _client: ClientId,
        _resource: &XdgToplevel,
        data: &XdgToplevelData,
    ) {
        if let Some(surface_data) = data.xdg_surface.data::<XdgSurfaceData>() {
            {
                let mut xdg_state = surface_data
                    .state
                    .lock()
                    .unwrap_or_else(|error| error.into_inner());
                xdg_state.role_active = false;
                xdg_state.pending_configures.clear();
                xdg_state.acknowledged_configure = None;
            }
            if let Some(wl_surface_data) = surface_data.wl_surface.data::<SurfaceData>() {
                let mut surface = wl_surface_data
                    .inner
                    .lock()
                    .unwrap_or_else(|error| error.into_inner());
                surface.xdg_toplevel = None;
                surface.xdg_configured = false;
            }
        }
        state.xdg_toplevel_count = state.xdg_toplevel_count.saturating_sub(1);
    }
}

fn create_keymap_file() -> io::Result<File> {
    let name = b"archphene-keymap\0";
    let raw_fd = unsafe {
        libc::syscall(
            libc::SYS_memfd_create,
            name.as_ptr().cast::<libc::c_char>(),
            libc::MFD_CLOEXEC | libc::MFD_ALLOW_SEALING,
        ) as RawFd
    };
    if raw_fd < 0 {
        return Err(io::Error::last_os_error());
    }
    let file = unsafe { File::from_raw_fd(raw_fd) };
    file.set_len(XKB_KEYMAP.len() as u64)?;
    file.write_all_at(XKB_KEYMAP, 0)?;
    let seals = libc::F_SEAL_SHRINK | libc::F_SEAL_GROW | libc::F_SEAL_WRITE | libc::F_SEAL_SEAL;
    if unsafe { libc::fcntl(file.as_raw_fd(), libc::F_ADD_SEALS, seals) } < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(file)
}

fn set_keyboard_focus(state: &mut CompositorState, surface: Option<WlSurface>) {
    if state.keyboard_focus_surface.as_ref().map(Resource::id) == surface.as_ref().map(Resource::id)
    {
        return;
    }

    state.next_input_serial = state.next_input_serial.wrapping_add(1).max(1);
    let serial = state.next_input_serial;
    if let Some(previous) = state.keyboard_focus_surface.take() {
        for keyboard in state
            .keyboards
            .iter()
            .filter(|keyboard| keyboard.is_alive() && keyboard.id().same_client_as(&previous.id()))
        {
            keyboard.leave(serial, &previous);
            state.keyboard_event_count = state.keyboard_event_count.saturating_add(1);
        }
    }

    if let Some(surface) = surface {
        for keyboard in state
            .keyboards
            .iter()
            .filter(|keyboard| keyboard.is_alive() && keyboard.id().same_client_as(&surface.id()))
        {
            keyboard.enter(serial, &surface, Vec::new());
            keyboard.modifiers(serial, 0, 0, 0, 0);
            state.keyboard_event_count = state.keyboard_event_count.saturating_add(2);
        }
        state.keyboard_focus_surface = Some(surface);
    } else {
        state.pressed_keys.clear();
    }
}

impl GlobalDispatch<WlOutput, ()> for CompositorState {
    fn bind(
        state: &mut Self,
        _handle: &DisplayHandle,
        _client: &Client,
        resource: New<WlOutput>,
        _global_data: &(),
        data_init: &mut DataInit<'_, Self>,
    ) {
        let output = data_init.init(resource, ());
        output.geometry(
            0,
            0,
            70,
            140,
            wl_output::Subpixel::Unknown,
            "Archphene".into(),
            "Android display".into(),
            wl_output::Transform::Normal,
        );
        output.mode(
            wl_output::Mode::Current | wl_output::Mode::Preferred,
            state.output_width,
            state.output_height,
            60_000,
        );
        if output.version() >= 4 {
            output.name("Archphene-0".into());
            output.description("Archphene Android application viewport".into());
        }
        if output.version() >= 2 {
            output.scale(state.output_scale);
            output.done();
        }
        if let Some(surface) = state
            .keyboard_focus_surface
            .as_ref()
            .or(state.pointer_focus_surface.as_ref())
            .filter(|surface| output.id().same_client_as(&surface.id()))
        {
            surface.enter(&output);
            state.output_event_count = state.output_event_count.saturating_add(1);
        }
        state.output_binds = state.output_binds.saturating_add(1);
        state.output_event_count = state.output_event_count.saturating_add(6);
        state.outputs.push(output);
    }
}

impl Dispatch<WlOutput, ()> for CompositorState {
    fn request(
        _state: &mut Self,
        _client: &Client,
        _resource: &WlOutput,
        request: wl_output::Request,
        _data: &(),
        _handle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_output::Request::Release => {}
            _ => unreachable!("wl_output request added without an implementation"),
        }
    }

    fn destroyed(state: &mut Self, _client: ClientId, resource: &WlOutput, _data: &()) {
        state.outputs.retain(|output| output.id() != resource.id());
    }
}

impl GlobalDispatch<WlSeat, ()> for CompositorState {
    fn bind(
        state: &mut Self,
        _handle: &DisplayHandle,
        _client: &Client,
        resource: New<WlSeat>,
        _global_data: &(),
        data_init: &mut DataInit<'_, Self>,
    ) {
        let seat = data_init.init(resource, ());
        state.seat_binds = state.seat_binds.saturating_add(1);
        if seat.version() >= 2 {
            seat.name("Archphene".into());
        }
        seat.capabilities(wl_seat::Capability::Pointer | wl_seat::Capability::Keyboard);
    }
}

impl Dispatch<WlSeat, ()> for CompositorState {
    fn request(
        state: &mut Self,
        _client: &Client,
        resource: &WlSeat,
        request: wl_seat::Request,
        _data: &(),
        _handle: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_seat::Request::GetPointer { id } => {
                let pointer = data_init.init(id, ());
                state.pointer_count = state.pointer_count.saturating_add(1);
                state.pointers.push(pointer);
            }
            wl_seat::Request::GetKeyboard { id } => {
                let keyboard = data_init.init(id, ());
                match create_keymap_file() {
                    Ok(file) => {
                        keyboard.keymap(
                            wl_keyboard::KeymapFormat::XkbV1,
                            file.as_fd(),
                            XKB_KEYMAP.len() as u32,
                        );
                        state.keyboard_event_count = state.keyboard_event_count.saturating_add(1);
                    }
                    Err(error) => {
                        resource.post_error(
                            wl_seat::Error::MissingCapability,
                            format!("could not create keyboard keymap: {error}"),
                        );
                        return;
                    }
                }
                if keyboard.version() >= 4 {
                    keyboard.repeat_info(25, 400);
                    state.keyboard_event_count = state.keyboard_event_count.saturating_add(1);
                }
                if let Some(surface) = state
                    .keyboard_focus_surface
                    .as_ref()
                    .filter(|surface| keyboard.id().same_client_as(&surface.id()))
                {
                    state.next_input_serial = state.next_input_serial.wrapping_add(1).max(1);
                    let serial = state.next_input_serial;
                    keyboard.enter(serial, surface, Vec::new());
                    keyboard.modifiers(serial, 0, 0, 0, 0);
                    state.keyboard_event_count = state.keyboard_event_count.saturating_add(2);
                }
                state.keyboard_count = state.keyboard_count.saturating_add(1);
                state.keyboards.push(keyboard);
            }
            wl_seat::Request::GetTouch { .. } => resource.post_error(
                wl_seat::Error::MissingCapability,
                "touch capability is not advertised yet",
            ),
            wl_seat::Request::Release => {}
            _ => unreachable!("wl_seat request added without an implementation"),
        }
    }
}

impl Dispatch<WlPointer, ()> for CompositorState {
    fn request(
        _state: &mut Self,
        _client: &Client,
        _resource: &WlPointer,
        request: wl_pointer::Request,
        _data: &(),
        _handle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_pointer::Request::SetCursor { .. } | wl_pointer::Request::Release => {}
            _ => unreachable!("wl_pointer request added without an implementation"),
        }
    }

    fn destroyed(state: &mut Self, _client: ClientId, resource: &WlPointer, _data: &()) {
        state
            .pointers
            .retain(|pointer| pointer.id() != resource.id());
        state.pointer_count = state.pointer_count.saturating_sub(1);
    }
}
impl Dispatch<WlKeyboard, ()> for CompositorState {
    fn request(
        _state: &mut Self,
        _client: &Client,
        _resource: &WlKeyboard,
        request: wl_keyboard::Request,
        _data: &(),
        _handle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_keyboard::Request::Release => {}
            _ => unreachable!("wl_keyboard request added without an implementation"),
        }
    }

    fn destroyed(state: &mut Self, _client: ClientId, resource: &WlKeyboard, _data: &()) {
        state
            .keyboards
            .retain(|keyboard| keyboard.id() != resource.id());
        state.keyboard_count = state.keyboard_count.saturating_sub(1);
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

impl GlobalDispatch<WlSubcompositor, ()> for CompositorState {
    fn bind(
        state: &mut Self,
        _handle: &DisplayHandle,
        _client: &Client,
        resource: New<WlSubcompositor>,
        _global_data: &(),
        data_init: &mut DataInit<'_, Self>,
    ) {
        data_init.init(resource, ());
        state.subcompositor_binds = state.subcompositor_binds.saturating_add(1);
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

fn subsurface_parent(surface: &WlSurface) -> Option<WlSurface> {
    let data = surface.data::<SurfaceData>()?;
    let subsurface = data
        .inner
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .subsurface
        .clone()?;
    subsurface
        .data::<SubsurfaceData>()
        .map(|data| data.parent.clone())
}

fn subsurface_would_cycle(child: &WlSurface, parent: &WlSurface, surface_count: u32) -> bool {
    let mut candidate = Some(parent.clone());
    for _ in 0..=surface_count {
        let Some(surface) = candidate else {
            return false;
        };
        if surface.id() == child.id() {
            return true;
        }
        candidate = subsurface_parent(&surface);
    }
    true
}

fn subsurface_effectively_synchronized(surface: &WlSurface, surface_count: u32) -> bool {
    let mut candidate = Some(surface.clone());
    for _ in 0..=surface_count {
        let Some(surface) = candidate else {
            return false;
        };
        let Some(surface_data) = surface.data::<SurfaceData>() else {
            return false;
        };
        let subsurface = surface_data
            .inner
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .subsurface
            .clone();
        let Some(subsurface) = subsurface else {
            return false;
        };
        let Some(data) = subsurface.data::<SubsurfaceData>() else {
            return false;
        };
        if data.synchronized.load(Ordering::Acquire) {
            return true;
        }
        candidate = Some(data.parent.clone());
    }
    true
}

fn apply_cached_subsurface_tree(
    state: &CompositorState,
    surface: &WlSurface,
    depth: usize,
    apply_parent_state: bool,
    callbacks: &mut Vec<WlCallback>,
) {
    if depth > state.surface_count as usize {
        return;
    }
    let Some(surface_data) = surface.data::<SurfaceData>() else {
        return;
    };
    let subsurface = surface_data
        .inner
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .subsurface
        .clone();
    if apply_parent_state {
        if let Some(data) = subsurface
            .as_ref()
            .and_then(|subsurface| subsurface.data::<SubsurfaceData>())
        {
            if let Some(position) = data
                .pending_position
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .take()
            {
                *data
                    .position
                    .lock()
                    .unwrap_or_else(|error| error.into_inner()) = position;
            }
        }
    }
    let children = {
        let mut surface = surface_data
            .inner
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if let Some(input_region) = surface.cached_input_region.take() {
            surface.committed_input_region = input_region;
        }
        if let Some(frame) = surface.cached_frame.take() {
            surface.committed_frame = frame;
        }
        callbacks.append(&mut surface.cached_callbacks);
        surface
            .children_below
            .iter()
            .chain(surface.children_above.iter())
            .cloned()
            .collect::<Vec<_>>()
    };
    for child in children.iter().filter(|child| child.is_alive()) {
        apply_cached_subsurface_tree(
            state,
            child,
            depth.saturating_add(1),
            apply_parent_state,
            callbacks,
        );
    }
}

fn apply_cached_subsurface_children(
    state: &CompositorState,
    parent: &WlSurface,
) -> Vec<WlCallback> {
    let Some(parent_data) = parent.data::<SurfaceData>() else {
        return Vec::new();
    };
    let children = {
        let mut parent_state = parent_data
            .inner
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        let pending_stack = std::mem::take(&mut parent_state.pending_subsurface_stack);
        for (surface, sibling, above) in pending_stack {
            apply_subsurface_order(&mut parent_state, &surface, &sibling, parent, above);
        }
        parent_state
            .children_below
            .iter()
            .chain(parent_state.children_above.iter())
            .cloned()
            .collect::<Vec<_>>()
    };
    let mut callbacks = Vec::new();
    for child in children.iter().filter(|child| child.is_alive()) {
        apply_cached_subsurface_tree(state, child, 0, true, &mut callbacks);
    }
    callbacks
}

impl Dispatch<WlSubcompositor, ()> for CompositorState {
    fn request(
        state: &mut Self,
        _client: &Client,
        resource: &WlSubcompositor,
        request: wl_subcompositor::Request,
        _data: &(),
        _handle: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_subcompositor::Request::Destroy => {}
            wl_subcompositor::Request::GetSubsurface {
                id,
                surface,
                parent,
            } => {
                if surface.id() == parent.id()
                    || !surface.id().same_client_as(&parent.id())
                    || subsurface_would_cycle(&surface, &parent, state.surface_count)
                {
                    resource.post_error(
                        wl_subcompositor::Error::BadSurface,
                        "wl_subsurface parent must be an acyclic surface from the same client",
                    );
                    return;
                }
                let Some(surface_data) = surface.data::<SurfaceData>() else {
                    resource.post_error(
                        wl_subcompositor::Error::BadSurface,
                        "wl_subsurface child surface is unknown",
                    );
                    return;
                };
                let Some(parent_data) = parent.data::<SurfaceData>() else {
                    resource.post_error(
                        wl_subcompositor::Error::BadSurface,
                        "wl_subsurface parent surface is unknown",
                    );
                    return;
                };
                let mut child_state = surface_data
                    .inner
                    .lock()
                    .unwrap_or_else(|error| error.into_inner());
                if child_state.role.is_some() {
                    resource.post_error(
                        wl_subcompositor::Error::BadSurface,
                        "wl_surface already has a role",
                    );
                    return;
                }
                let subsurface = data_init.init(
                    id,
                    SubsurfaceData {
                        surface: surface.clone(),
                        parent: parent.clone(),
                        position: Mutex::new((0, 0)),
                        pending_position: Mutex::new(None),
                        synchronized: AtomicBool::new(true),
                    },
                );
                child_state.role = Some(SurfaceRole::Subsurface);
                child_state.subsurface = Some(subsurface.clone());
                drop(child_state);
                parent_data
                    .inner
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                    .children_above
                    .push(surface);
                state.subsurfaces.push(subsurface);
                state.subsurface_count = state.subsurface_count.saturating_add(1);
            }
            _ => unreachable!("wl_subcompositor request added without an implementation"),
        }
    }
}

fn subsurface_sibling_is_valid(data: &SubsurfaceData, sibling: &WlSurface) -> bool {
    sibling.id() != data.surface.id()
        && (sibling.id() == data.parent.id()
            || subsurface_parent(sibling).is_some_and(|parent| parent.id() == data.parent.id()))
}

fn apply_subsurface_order(
    parent: &mut SurfaceState,
    surface: &WlSurface,
    sibling: &WlSurface,
    parent_surface: &WlSurface,
    above: bool,
) -> bool {
    parent
        .children_below
        .retain(|candidate| candidate.id() != surface.id());
    parent
        .children_above
        .retain(|candidate| candidate.id() != surface.id());
    if sibling.id() == parent_surface.id() {
        if above {
            parent.children_above.insert(0, surface.clone());
        } else {
            parent.children_below.push(surface.clone());
        }
        return true;
    }
    let target = if let Some(index) = parent
        .children_below
        .iter()
        .position(|candidate| candidate.id() == sibling.id())
    {
        (&mut parent.children_below, index)
    } else if let Some(index) = parent
        .children_above
        .iter()
        .position(|candidate| candidate.id() == sibling.id())
    {
        (&mut parent.children_above, index)
    } else {
        return false;
    };
    let insertion = if above { target.1 + 1 } else { target.1 };
    target.0.insert(insertion, surface.clone());
    true
}
impl Dispatch<WlSubsurface, SubsurfaceData> for CompositorState {
    fn request(
        state: &mut Self,
        _client: &Client,
        resource: &WlSubsurface,
        request: wl_subsurface::Request,
        data: &SubsurfaceData,
        _handle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_subsurface::Request::Destroy => {}
            wl_subsurface::Request::SetPosition { x, y } => {
                *data
                    .pending_position
                    .lock()
                    .unwrap_or_else(|error| error.into_inner()) = Some((x, y));
            }
            wl_subsurface::Request::PlaceAbove { sibling } => {
                if !subsurface_sibling_is_valid(data, &sibling) {
                    resource.post_error(
                        wl_subsurface::Error::BadSurface,
                        "stacking sibling must be the parent or one of its subsurfaces",
                    );
                    return;
                }
                if let Some(parent_data) = data.parent.data::<SurfaceData>() {
                    parent_data
                        .inner
                        .lock()
                        .unwrap_or_else(|error| error.into_inner())
                        .pending_subsurface_stack
                        .push((data.surface.clone(), sibling, true));
                }
            }
            wl_subsurface::Request::PlaceBelow { sibling } => {
                if !subsurface_sibling_is_valid(data, &sibling) {
                    resource.post_error(
                        wl_subsurface::Error::BadSurface,
                        "stacking sibling must be the parent or one of its subsurfaces",
                    );
                    return;
                }
                if let Some(parent_data) = data.parent.data::<SurfaceData>() {
                    parent_data
                        .inner
                        .lock()
                        .unwrap_or_else(|error| error.into_inner())
                        .pending_subsurface_stack
                        .push((data.surface.clone(), sibling, false));
                }
            }
            wl_subsurface::Request::SetSync => {
                data.synchronized.store(true, Ordering::Release);
            }
            wl_subsurface::Request::SetDesync => {
                data.synchronized.store(false, Ordering::Release);
                if !subsurface_effectively_synchronized(&data.surface, state.surface_count) {
                    let mut callbacks = Vec::new();
                    apply_cached_subsurface_tree(state, &data.surface, 0, false, &mut callbacks);
                    update_composited_frame(state);
                    for callback in callbacks {
                        callback.done(0);
                    }
                }
            }
            _ => unreachable!("wl_subsurface request added without an implementation"),
        }
    }

    fn destroyed(
        state: &mut Self,
        _client: ClientId,
        resource: &WlSubsurface,
        data: &SubsurfaceData,
    ) {
        if let Some(parent_data) = data.parent.data::<SurfaceData>() {
            let mut parent = parent_data
                .inner
                .lock()
                .unwrap_or_else(|error| error.into_inner());
            parent
                .children_below
                .retain(|surface| surface.id() != data.surface.id());
            parent
                .children_above
                .retain(|surface| surface.id() != data.surface.id());
        }
        if let Some(surface_data) = data.surface.data::<SurfaceData>() {
            let mut surface = surface_data
                .inner
                .lock()
                .unwrap_or_else(|error| error.into_inner());
            if surface
                .subsurface
                .as_ref()
                .is_some_and(|subsurface| subsurface.id() == resource.id())
            {
                surface.subsurface = None;
                surface.role = None;
            }
        }
        state
            .subsurfaces
            .retain(|subsurface| subsurface.id() != resource.id());
        state.subsurface_count = state.subsurface_count.saturating_sub(1);
        update_composited_frame(state);
    }
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
                data_init.init(id, RegionData::default());
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
            wl_surface::Request::SetInputRegion { region } => {
                let region = match region {
                    Some(region) => {
                        let Some(region_data) = region.data::<RegionData>() else {
                            return;
                        };
                        Some(
                            region_data
                                .inner
                                .lock()
                                .unwrap_or_else(|error| error.into_inner())
                                .clone(),
                        )
                    }
                    None => None,
                };
                data.inner
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                    .pending_input_region = Some(region);
            }
            wl_surface::Request::SetOpaqueRegion { .. } | wl_surface::Request::Offset { .. } => {}
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
                let attaches_buffer = matches!(surface.pending_buffer, Some(Some(_)));
                if surface.has_xdg_surface && surface.role.is_none() {
                    if let Some(xdg_surface) = surface.xdg_surface.as_ref() {
                        xdg_surface.post_error(
                            xdg_surface::Error::NotConstructed,
                            "xdg_surface committed before creating a role",
                        );
                    }
                    return;
                }
                if surface.role == Some(SurfaceRole::XdgToplevel) && !surface.xdg_configured {
                    if attaches_buffer {
                        if let Some(xdg_surface) = surface.xdg_surface.as_ref() {
                            xdg_surface.post_error(
                                xdg_surface::Error::UnconfiguredBuffer,
                                "xdg_surface buffer committed before ack_configure",
                            );
                        }
                        return;
                    }
                    if let (Some(xdg_surface), Some(toplevel)) =
                        (surface.xdg_surface.as_ref(), surface.xdg_toplevel.as_ref())
                    {
                        let Some(xdg_data) = xdg_surface.data::<XdgSurfaceData>() else {
                            return;
                        };
                        let mut xdg_state = xdg_data
                            .state
                            .lock()
                            .unwrap_or_else(|error| error.into_inner());
                        if xdg_state.pending_configures.is_empty()
                            && xdg_state.acknowledged_configure.is_none()
                        {
                            state.next_configure_serial =
                                state.next_configure_serial.wrapping_add(1).max(1);
                            let serial = state.next_configure_serial;
                            xdg_state.pending_configures.push_back(serial);
                            toplevel.configure(0, 0, Vec::new());
                            xdg_surface.configure(serial);
                        }
                    } else if let Some(xdg_surface) = surface.xdg_surface.as_ref() {
                        xdg_surface.post_error(
                            xdg_surface::Error::NotConstructed,
                            "xdg_surface committed without an active role",
                        );
                        return;
                    }
                }
                if surface.role == Some(SurfaceRole::XdgPopup) && !surface.xdg_configured {
                    if attaches_buffer {
                        if let Some(xdg_surface) = surface.xdg_surface.as_ref() {
                            xdg_surface.post_error(
                                xdg_surface::Error::UnconfiguredBuffer,
                                "xdg_popup buffer committed before ack_configure",
                            );
                        }
                        return;
                    }
                    if let (Some(xdg_surface), Some(popup)) =
                        (surface.xdg_surface.as_ref(), surface.xdg_popup.as_ref())
                    {
                        let Some(xdg_data) = xdg_surface.data::<XdgSurfaceData>() else {
                            return;
                        };
                        let Some(popup_data) = popup.data::<XdgPopupData>() else {
                            return;
                        };
                        let mut xdg_state = xdg_data
                            .state
                            .lock()
                            .unwrap_or_else(|error| error.into_inner());
                        if xdg_state.pending_configures.is_empty()
                            && xdg_state.acknowledged_configure.is_none()
                        {
                            let positioner = popup_data
                                .positioner
                                .lock()
                                .unwrap_or_else(|error| error.into_inner())
                                .clone();
                            let Some(geometry) =
                                constrained_popup_geometry(state, popup_data, &positioner)
                            else {
                                popup.post_error(
                                    xdg_popup::Error::InvalidGrab,
                                    "xdg_popup positioner became incomplete",
                                );
                                return;
                            };
                            let (x, y, width, height) = geometry;
                            *popup_data
                                .applied_geometry
                                .lock()
                                .unwrap_or_else(|error| error.into_inner()) = Some(geometry);
                            state.next_configure_serial =
                                state.next_configure_serial.wrapping_add(1).max(1);
                            let serial = state.next_configure_serial;
                            xdg_state.pending_configures.push_back(serial);
                            popup.configure(x, y, width, height);
                            xdg_surface.configure(serial);
                        }
                    }
                }
                let input_region_update = surface.pending_input_region.take();
                let frame_update = if let Some(assignment) = surface.pending_buffer.take() {
                    Some(match assignment {
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
                    })
                } else {
                    None
                };
                let mut callbacks = std::mem::take(&mut surface.pending_callbacks);
                surface.pending_damage = false;
                let role = surface.role;
                drop(surface);

                if role == Some(SurfaceRole::Subsurface)
                    && subsurface_effectively_synchronized(resource, state.surface_count)
                {
                    let mut surface = data.inner.lock().unwrap_or_else(|error| error.into_inner());
                    if let Some(input_region) = input_region_update {
                        surface.cached_input_region = Some(input_region);
                    }
                    if let Some(frame) = frame_update {
                        surface.cached_frame = Some(frame);
                    }
                    surface.cached_callbacks.append(&mut callbacks);
                    state.surface_commit_count = state.surface_commit_count.saturating_add(1);
                    return;
                }

                let mut surface = data.inner.lock().unwrap_or_else(|error| error.into_inner());
                if let Some(input_region) = input_region_update {
                    surface.committed_input_region = input_region;
                }
                if let Some(frame) = frame_update {
                    surface.committed_frame = frame;
                }
                let latest_frame = surface.committed_frame.clone();
                let is_xdg_toplevel = role == Some(SurfaceRole::XdgToplevel);
                let publishes_root_frame = role != Some(SurfaceRole::Subsurface)
                    && (is_xdg_toplevel || !surface.has_xdg_surface);
                let has_frame = latest_frame.is_some();
                drop(surface);

                state.surface_commit_count = state.surface_commit_count.saturating_add(1);
                if publishes_root_frame {
                    state.root_surface = Some(resource.clone());
                    state.root_frame = latest_frame;
                }
                if is_xdg_toplevel {
                    if has_frame {
                        if state
                            .pointer_focus_surface
                            .as_ref()
                            .is_none_or(|focused| focused.id() != resource.id())
                        {
                            state.pointer_inside = false;
                            state.pointer_pressed = false;
                        }
                        state.pointer_focus_surface = Some(resource.clone());
                        set_keyboard_focus(state, Some(resource.clone()));
                    } else if state
                        .pointer_focus_surface
                        .as_ref()
                        .is_some_and(|focused| focused.id() == resource.id())
                    {
                        state.pointer_focus_surface = None;
                        state.pointer_inside = false;
                        state.pointer_pressed = false;
                        set_keyboard_focus(state, None);
                    }
                }
                callbacks.extend(apply_cached_subsurface_children(state, resource));
                update_composited_frame(state);
                for callback in callbacks {
                    callback.done(0);
                }
            }
            _ => unreachable!("wl_surface request added without an implementation"),
        }
    }

    fn destroyed(state: &mut Self, _client: ClientId, resource: &WlSurface, _data: &SurfaceData) {
        if state
            .root_surface
            .as_ref()
            .is_some_and(|root| root.id() == resource.id())
        {
            state.root_surface = None;
            state.root_frame = None;
            update_composited_frame(state);
        }
        if state
            .pointer_focus_surface
            .as_ref()
            .is_some_and(|focused| focused.id() == resource.id())
        {
            state.pointer_focus_surface = None;
            state.pointer_inside = false;
            state.pointer_pressed = false;
        }
        if state
            .keyboard_focus_surface
            .as_ref()
            .is_some_and(|focused| focused.id() == resource.id())
        {
            set_keyboard_focus(state, None);
        }
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
        _resource: &WlRegion,
        request: wl_region::Request,
        data: &RegionData,
        _handle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        let operation = match request {
            wl_region::Request::Destroy => return,
            wl_region::Request::Add {
                x,
                y,
                width,
                height,
            } if width > 0 && height > 0 => Some(RegionOperation::Add(RegionRectangle {
                x,
                y,
                width,
                height,
            })),
            wl_region::Request::Subtract {
                x,
                y,
                width,
                height,
            } if width > 0 && height > 0 => Some(RegionOperation::Subtract(RegionRectangle {
                x,
                y,
                width,
                height,
            })),
            wl_region::Request::Add { .. } | wl_region::Request::Subtract { .. } => None,
            _ => unreachable!("wl_region request added without an implementation"),
        };
        if let Some(operation) = operation {
            data.inner
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .operations
                .push(operation);
        }
    }
}

fn surface_origin_in_root(
    state: &CompositorState,
    surface: &WlSurface,
    depth: usize,
) -> Option<(i32, i32)> {
    if depth > state.surface_count as usize {
        return None;
    }
    if state
        .root_surface
        .as_ref()
        .is_some_and(|root| root.id() == surface.id())
    {
        return Some((0, 0));
    }
    let surface_data = surface.data::<SurfaceData>()?;
    let (role, xdg_surface, subsurface) = {
        let surface = surface_data
            .inner
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        (
            surface.role,
            surface.xdg_surface.clone(),
            surface.subsurface.clone(),
        )
    };
    match role {
        Some(SurfaceRole::XdgToplevel) => Some((0, 0)),
        Some(SurfaceRole::XdgPopup) => xdg_surface_origin(state, &xdg_surface?, 0),
        Some(SurfaceRole::Subsurface) => {
            let subsurface = subsurface?;
            let data = subsurface.data::<SubsurfaceData>()?;
            let (parent_x, parent_y) =
                surface_origin_in_root(state, &data.parent, depth.saturating_add(1))?;
            let (x, y) = *data
                .position
                .lock()
                .unwrap_or_else(|error| error.into_inner());
            Some((parent_x.saturating_add(x), parent_y.saturating_add(y)))
        }
        None => None,
    }
}

fn surface_tree_pointer_target(
    state: &CompositorState,
    surface: &WlSurface,
    origin_x: i32,
    origin_y: i32,
    width: i32,
    height: i32,
    pointer_x: f64,
    pointer_y: f64,
    depth: usize,
) -> Option<(WlSurface, f64, f64)> {
    if depth > state.surface_count as usize {
        return None;
    }
    let local_x = pointer_x - f64::from(origin_x);
    let local_y = pointer_y - f64::from(origin_y);
    let surface_data = surface.data::<SurfaceData>()?;
    let (accepts_parent, children_below, children_above) = {
        let surface = surface_data
            .inner
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        (
            surface.committed_frame.is_some()
                && surface_accepts_pointer(&surface, local_x, local_y, width, height),
            surface.children_below.clone(),
            surface.children_above.clone(),
        )
    };
    for child in children_above.iter().rev().filter(|child| child.is_alive()) {
        if let Some(target) = subsurface_tree_pointer_target(
            state,
            child,
            origin_x,
            origin_y,
            pointer_x,
            pointer_y,
            depth.saturating_add(1),
        ) {
            return Some(target);
        }
    }

    if accepts_parent {
        return Some((surface.clone(), local_x, local_y));
    }
    for child in children_below.iter().rev().filter(|child| child.is_alive()) {
        if let Some(target) = subsurface_tree_pointer_target(
            state,
            child,
            origin_x,
            origin_y,
            pointer_x,
            pointer_y,
            depth.saturating_add(1),
        ) {
            return Some(target);
        }
    }
    None
}

fn subsurface_tree_pointer_target(
    state: &CompositorState,
    surface: &WlSurface,
    parent_x: i32,
    parent_y: i32,
    pointer_x: f64,
    pointer_y: f64,
    depth: usize,
) -> Option<(WlSurface, f64, f64)> {
    let (x, y) = subsurface_position(surface)?;
    let surface_data = surface.data::<SurfaceData>()?;
    let dimensions = surface_data
        .inner
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .committed_frame
        .as_ref()
        .map(|frame| (frame.width as i32, frame.height as i32))?;
    surface_tree_pointer_target(
        state,
        surface,
        parent_x.saturating_add(x),
        parent_y.saturating_add(y),
        dimensions.0,
        dimensions.1,
        pointer_x,
        pointer_y,
        depth,
    )
}

fn surface_accepts_pointer(
    surface: &SurfaceState,
    x: f64,
    y: f64,
    width: i32,
    height: i32,
) -> bool {
    x >= 0.0
        && y >= 0.0
        && x < f64::from(width)
        && y < f64::from(height)
        && surface
            .committed_input_region
            .as_ref()
            .is_none_or(|region| region.contains(x, y))
}

fn popup_local_geometry(data: &XdgPopupData) -> Option<(i32, i32, i32, i32)> {
    data.applied_geometry
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .or_else(|| {
            data.positioner
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .geometry()
        })
}

fn popup_constraint_bounds(state: &CompositorState, data: &XdgPopupData) -> Option<PopupBounds> {
    let (parent_x, parent_y) = xdg_surface_origin(state, &data.parent, 0)?;
    Some(PopupBounds {
        left: parent_x.saturating_neg(),
        top: parent_y.saturating_neg(),
        right: state.output_width.saturating_sub(parent_x),
        bottom: state.output_height.saturating_sub(parent_y),
    })
}

fn constrained_popup_geometry(
    state: &CompositorState,
    data: &XdgPopupData,
    positioner: &XdgPositionerState,
) -> Option<(i32, i32, i32, i32)> {
    positioner.constrained_geometry(popup_constraint_bounds(state, data)?)
}
fn xdg_surface_origin(
    state: &CompositorState,
    xdg_surface: &XdgSurface,
    depth: usize,
) -> Option<(i32, i32)> {
    if depth > state.popups.len() {
        return None;
    }
    let xdg_data = xdg_surface.data::<XdgSurfaceData>()?;
    let surface_data = xdg_data.wl_surface.data::<SurfaceData>()?;
    let (role, popup) = {
        let surface = surface_data
            .inner
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        (surface.role, surface.xdg_popup.clone())
    };
    match role {
        Some(SurfaceRole::XdgToplevel) => Some((0, 0)),
        Some(SurfaceRole::XdgPopup) => {
            let popup = popup?;
            let popup_data = popup.data::<XdgPopupData>()?;
            let (parent_x, parent_y) =
                xdg_surface_origin(state, &popup_data.parent, depth.saturating_add(1))?;
            let (x, y, _, _) = popup_local_geometry(popup_data)?;
            Some((parent_x.saturating_add(x), parent_y.saturating_add(y)))
        }
        Some(SurfaceRole::Subsurface) | None => None,
    }
}

fn popup_geometry_in_root(
    state: &CompositorState,
    popup: &XdgPopup,
) -> Option<(i32, i32, i32, i32)> {
    let data = popup.data::<XdgPopupData>()?;
    let (parent_x, parent_y) = xdg_surface_origin(state, &data.parent, 0)?;
    let (x, y, width, height) = popup_local_geometry(data)?;
    Some((
        parent_x.saturating_add(x),
        parent_y.saturating_add(y),
        width,
        height,
    ))
}

fn blend_channel(source: u8, source_alpha: u32, destination: u8, destination_alpha: u32) -> u8 {
    let inverse_source_alpha = 255 - source_alpha;
    let output_alpha = source_alpha + (destination_alpha * inverse_source_alpha + 127) / 255;
    if output_alpha == 0 {
        return 0;
    }
    let numerator = u32::from(source) * source_alpha * 255
        + u32::from(destination) * destination_alpha * inverse_source_alpha;
    ((numerator + output_alpha * 127) / (output_alpha * 255)) as u8
}

fn blend_popup_frame(
    destination: &mut CommittedFrame,
    source: &CommittedFrame,
    x: i32,
    y: i32,
    configured_width: i32,
    configured_height: i32,
) {
    let source_width = source.width.min(configured_width.max(0) as u32);
    let source_height = source.height.min(configured_height.max(0) as u32);
    for source_y in 0..source_height {
        let destination_y = i64::from(y) + i64::from(source_y);
        if destination_y < 0 || destination_y >= i64::from(destination.height) {
            continue;
        }
        for source_x in 0..source_width {
            let destination_x = i64::from(x) + i64::from(source_x);
            if destination_x < 0 || destination_x >= i64::from(destination.width) {
                continue;
            }
            let source_index = ((source_y * source.width + source_x) * 4) as usize;
            let destination_index =
                ((destination_y as u32 * destination.width + destination_x as u32) * 4) as usize;
            let source_alpha = if source.format == wl_shm::Format::Argb8888 {
                u32::from(source.pixels[source_index + 3])
            } else {
                255
            };
            let destination_alpha = if destination.format == wl_shm::Format::Argb8888 {
                u32::from(destination.pixels[destination_index + 3])
            } else {
                255
            };
            for channel in 0..3 {
                destination.pixels[destination_index + channel] = blend_channel(
                    source.pixels[source_index + channel],
                    source_alpha,
                    destination.pixels[destination_index + channel],
                    destination_alpha,
                );
            }
            if destination.format == wl_shm::Format::Argb8888 {
                destination.pixels[destination_index + 3] =
                    (source_alpha + destination_alpha * (255 - source_alpha) / 255) as u8;
            }
        }
    }
}

fn subsurface_position(surface: &WlSurface) -> Option<(i32, i32)> {
    let data = surface.data::<SurfaceData>()?;
    let subsurface = data
        .inner
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .subsurface
        .clone()?;
    let subsurface = subsurface.data::<SubsurfaceData>()?;
    Some(
        *subsurface
            .position
            .lock()
            .unwrap_or_else(|error| error.into_inner()),
    )
}

fn blend_surface_tree(
    state: &CompositorState,
    destination: &mut CommittedFrame,
    surface: &WlSurface,
    x: i32,
    y: i32,
    configured_width: i32,
    configured_height: i32,
    depth: usize,
) {
    if depth > state.surface_count as usize {
        return;
    }
    let Some(surface_data) = surface.data::<SurfaceData>() else {
        return;
    };
    let (frame, children_below, children_above) = {
        let surface = surface_data
            .inner
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        (
            surface.committed_frame.clone(),
            surface.children_below.clone(),
            surface.children_above.clone(),
        )
    };
    for child in children_below.iter().filter(|child| child.is_alive()) {
        blend_subsurface_tree(state, destination, child, x, y, depth.saturating_add(1));
    }
    if let Some(frame) = frame {
        blend_popup_frame(
            destination,
            &frame,
            x,
            y,
            configured_width,
            configured_height,
        );
    }
    for child in children_above.iter().filter(|child| child.is_alive()) {
        blend_subsurface_tree(state, destination, child, x, y, depth.saturating_add(1));
    }
}

fn blend_subsurface_tree(
    state: &CompositorState,
    destination: &mut CommittedFrame,
    surface: &WlSurface,
    parent_x: i32,
    parent_y: i32,
    depth: usize,
) {
    let Some((x, y)) = subsurface_position(surface) else {
        return;
    };
    let Some(surface_data) = surface.data::<SurfaceData>() else {
        return;
    };
    let dimensions = surface_data
        .inner
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .committed_frame
        .as_ref()
        .map(|frame| (frame.width as i32, frame.height as i32));
    let Some((width, height)) = dimensions else {
        return;
    };
    blend_surface_tree(
        state,
        destination,
        surface,
        parent_x.saturating_add(x),
        parent_y.saturating_add(y),
        width,
        height,
        depth,
    );
}

fn update_composited_frame(state: &mut CompositorState) {
    let Some(root) = state.root_frame.as_ref() else {
        state.last_frame = None;
        state.last_frame_width = 0;
        state.last_frame_height = 0;
        state.last_frame_checksum = 0;
        return;
    };
    let mut composed = CommittedFrame {
        width: root.width,
        height: root.height,
        format: root.format,
        pixels: if root.format == wl_shm::Format::Argb8888 {
            vec![0; root.pixels.len()]
        } else {
            root.pixels.clone()
        },
    };
    if let Some(root_surface) = state.root_surface.as_ref() {
        blend_surface_tree(
            state,
            &mut composed,
            root_surface,
            0,
            0,
            root.width as i32,
            root.height as i32,
            0,
        );
    } else {
        composed.pixels.clone_from(&root.pixels);
    }
    for popup in state.popups.iter().filter(|popup| popup.is_alive()) {
        let Some(data) = popup.data::<XdgPopupData>() else {
            continue;
        };
        if data.dismissed.load(Ordering::Acquire) {
            continue;
        }
        let Some(xdg_data) = data.xdg_surface.data::<XdgSurfaceData>() else {
            continue;
        };

        let Some((x, y, width, height)) = popup_geometry_in_root(state, popup) else {
            continue;
        };
        blend_surface_tree(
            state,
            &mut composed,
            &xdg_data.wl_surface,
            x,
            y,
            width,
            height,
            0,
        );
    }
    state.last_frame_width = composed.width;
    state.last_frame_height = composed.height;
    state.last_frame_checksum = composed.pixels.iter().fold(0u32, |checksum, value| {
        checksum.wrapping_add(u32::from(*value))
    });
    state.last_frame = Some(Arc::new(composed));
}
impl CompositorCore {
    pub fn new() -> std::io::Result<Self> {
        let display = Display::new().map_err(|error| std::io::Error::other(error.to_string()))?;
        display
            .handle()
            .create_global::<CompositorState, WlCompositor, _>(6, ());
        display
            .handle()
            .create_global::<CompositorState, WlSubcompositor, _>(1, ());
        display
            .handle()
            .create_global::<CompositorState, WlShm, _>(1, ());
        display
            .handle()
            .create_global::<CompositorState, XdgWmBase, _>(6, ());
        display
            .handle()
            .create_global::<CompositorState, WlSeat, _>(7, ());
        display
            .handle()
            .create_global::<CompositorState, WlOutput, _>(4, ());
        let state = CompositorState {
            output_width: 320,
            output_height: 160,
            output_scale: 1,
            ..CompositorState::default()
        };
        Ok(Self {
            display,
            state,
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

    pub fn subcompositor_bind_count(&self) -> u32 {
        self.state.subcompositor_binds
    }

    pub fn subsurface_count(&self) -> u32 {
        self.state.subsurface_count
    }

    pub fn xdg_wm_base_bind_count(&self) -> u32 {
        self.state.xdg_wm_base_binds
    }

    pub fn xdg_positioner_count(&self) -> u32 {
        self.state.xdg_positioner_count
    }

    pub fn xdg_positioner_request_count(&self) -> u32 {
        self.state.xdg_positioner_request_count
    }

    pub fn xdg_popup_count(&self) -> u32 {
        self.state.xdg_popup_count
    }

    pub fn xdg_popup_done_count(&self) -> u32 {
        self.state.xdg_popup_done_count
    }

    pub fn xdg_popup_grab_depth(&self) -> u32 {
        self.state.popup_grab.as_ref().map_or(0, |grab| {
            u32::try_from(grab.stack.len()).unwrap_or(u32::MAX)
        })
    }

    pub fn dismiss_popups(&mut self) -> u32 {
        let mut dismissed = 0u32;
        for popup in self
            .state
            .popups
            .iter()
            .rev()
            .filter(|popup| popup.is_alive())
        {
            let Some(data) = popup.data::<XdgPopupData>() else {
                continue;
            };
            if !data.dismissed.swap(true, Ordering::AcqRel) {
                if let Some(surface_data) = data
                    .xdg_surface
                    .data::<XdgSurfaceData>()
                    .and_then(|xdg_data| xdg_data.wl_surface.data::<SurfaceData>())
                {
                    surface_data
                        .inner
                        .lock()
                        .unwrap_or_else(|error| error.into_inner())
                        .committed_frame = None;
                }
                popup.popup_done();
                dismissed = dismissed.saturating_add(1);
            }
        }
        self.state.xdg_popup_done_count = self.state.xdg_popup_done_count.saturating_add(dismissed);
        update_composited_frame(&mut self.state);
        let root = self.state.popup_grab.as_mut().and_then(|grab| {
            if grab.active {
                grab.active = false;
                Some(grab.root.clone())
            } else {
                None
            }
        });
        if let Some(root) = root {
            let previous = self.state.pointer_focus_surface.clone();
            if self.state.pointer_inside
                && previous
                    .as_ref()
                    .is_some_and(|surface| surface.id() != root.id())
            {
                if let Some(previous) = previous {
                    let serial = self.next_input_serial();
                    for pointer in self.pointer_resources_for_surface(&previous) {
                        pointer.leave(serial, &previous);
                        if pointer.version() >= 5 {
                            pointer.frame();
                        }
                    }
                }
                let pointers = self.pointer_resources_for_surface(&root);
                self.state.pointer_inside = !pointers.is_empty();
                if !pointers.is_empty() {
                    let serial = self.next_input_serial();
                    for pointer in pointers {
                        pointer.enter(serial, &root, self.state.pointer_x, self.state.pointer_y);
                        if pointer.version() >= 5 {
                            pointer.frame();
                        }
                    }
                }
            }
            self.state.pointer_focus_surface = Some(root.clone());
            self.state.pointer_pressed = false;
            set_keyboard_focus(&mut self.state, Some(root));
        }
        dismissed
    }

    pub fn xdg_surface_count(&self) -> u32 {
        self.state.xdg_surface_count
    }

    pub fn xdg_toplevel_count(&self) -> u32 {
        self.state.xdg_toplevel_count
    }

    pub fn xdg_ack_count(&self) -> u32 {
        self.state.xdg_ack_count
    }

    fn focused_xdg_resources(&self) -> Option<(XdgSurface, XdgToplevel)> {
        let surface = self
            .state
            .keyboard_focus_surface
            .as_ref()
            .or(self.state.pointer_focus_surface.as_ref())?;
        let surface_data = surface.data::<SurfaceData>()?;
        let surface = surface_data
            .inner
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        Some((surface.xdg_surface.clone()?, surface.xdg_toplevel.clone()?))
    }

    pub fn configure_focused_toplevel(&mut self, width: i32, height: i32) -> u32 {
        if width <= 0 || height <= 0 {
            return 0;
        }
        let Some((xdg_surface, toplevel)) = self.focused_xdg_resources() else {
            return 0;
        };
        let Some(xdg_data) = xdg_surface.data::<XdgSurfaceData>() else {
            return 0;
        };
        self.state.next_configure_serial = self.state.next_configure_serial.wrapping_add(1).max(1);
        let serial = self.state.next_configure_serial;
        xdg_data
            .state
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .pending_configures
            .push_back(serial);
        toplevel.configure(width, height, Vec::new());
        xdg_surface.configure(serial);
        serial
    }

    pub fn focused_pending_configure_count(&self) -> u32 {
        let Some((xdg_surface, _)) = self.focused_xdg_resources() else {
            return 0;
        };
        let Some(xdg_data) = xdg_surface.data::<XdgSurfaceData>() else {
            return 0;
        };
        let count = xdg_data
            .state
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .pending_configures
            .len();
        u32::try_from(count).unwrap_or(u32::MAX)
    }

    pub fn output_bind_count(&self) -> u32 {
        self.state.output_binds
    }

    pub fn output_event_count(&self) -> u32 {
        self.state.output_event_count
    }

    pub fn output_count(&self) -> u32 {
        u32::try_from(self.state.outputs.len()).unwrap_or(u32::MAX)
    }

    pub fn configure_output(&mut self, width: i32, height: i32, scale: i32) -> u32 {
        if width <= 0 || height <= 0 || scale <= 0 {
            return 0;
        }
        self.state.output_width = width;
        self.state.output_height = height;
        self.state.output_scale = scale;
        let mut updated = 0u32;
        for output in self.state.outputs.iter().filter(|output| output.is_alive()) {
            output.mode(
                wl_output::Mode::Current | wl_output::Mode::Preferred,
                width,
                height,
                60_000,
            );
            self.state.output_event_count = self.state.output_event_count.saturating_add(1);
            if output.version() >= 2 {
                output.scale(scale);
                output.done();
                self.state.output_event_count = self.state.output_event_count.saturating_add(2);
            }
            updated = updated.saturating_add(1);
        }
        self.reconfigure_reactive_popups();
        updated
    }

    fn reconfigure_reactive_popups(&mut self) {
        let popups = self.state.popups.clone();
        let mut geometry_changed = false;
        for popup in popups
            .iter()
            .filter(|popup| popup.is_alive())
            .filter(|popup| {
                popup.data::<XdgPopupData>().is_some_and(|data| {
                    !data.dismissed.load(Ordering::Acquire)
                        && data
                            .positioner
                            .lock()
                            .unwrap_or_else(|error| error.into_inner())
                            .reactive
                })
            })
        {
            let Some(data) = popup.data::<XdgPopupData>() else {
                continue;
            };
            let old_geometry = *data
                .applied_geometry
                .lock()
                .unwrap_or_else(|error| error.into_inner());
            if old_geometry.is_none() {
                continue;
            }
            let positioner = data
                .positioner
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .clone();
            let Some(geometry) = constrained_popup_geometry(&self.state, data, &positioner) else {
                continue;
            };
            if old_geometry == Some(geometry) {
                continue;
            }
            *data
                .applied_geometry
                .lock()
                .unwrap_or_else(|error| error.into_inner()) = Some(geometry);
            self.state.next_configure_serial =
                self.state.next_configure_serial.wrapping_add(1).max(1);
            let serial = self.state.next_configure_serial;
            if let Some(xdg_data) = data.xdg_surface.data::<XdgSurfaceData>() {
                xdg_data
                    .state
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                    .pending_configures
                    .push_back(serial);
            }
            popup.configure(geometry.0, geometry.1, geometry.2, geometry.3);
            data.xdg_surface.configure(serial);
            geometry_changed = true;
        }
        if geometry_changed {
            update_composited_frame(&mut self.state);
        }
    }

    pub fn seat_bind_count(&self) -> u32 {
        self.state.seat_binds
    }

    pub fn pointer_count(&self) -> u32 {
        self.state.pointer_count
    }

    pub fn pointer_event_count(&self) -> u32 {
        self.state.pointer_event_count
    }

    pub fn keyboard_count(&self) -> u32 {
        self.state.keyboard_count
    }

    pub fn keyboard_event_count(&self) -> u32 {
        self.state.keyboard_event_count
    }

    fn focused_keyboard_resources(&self) -> Vec<WlKeyboard> {
        let Some(surface) = self.state.keyboard_focus_surface.as_ref() else {
            return Vec::new();
        };
        self.state
            .keyboards
            .iter()
            .filter(|keyboard| keyboard.is_alive() && keyboard.id().same_client_as(&surface.id()))
            .cloned()
            .collect()
    }

    pub fn keyboard_key(&mut self, key: u32, pressed: bool, time: u32) -> u32 {
        if self.state.pressed_keys.contains(&key) == pressed {
            return 0;
        }
        let keyboards = self.focused_keyboard_resources();
        if keyboards.is_empty() {
            return 0;
        }
        let serial = self.next_input_serial();
        if pressed {
            if let Some(surface) = self.state.keyboard_focus_surface.clone() {
                self.state.popup_grab_serial = Some(PopupGrabSerial { serial, surface });
            }
        }
        let key_state = if pressed {
            wl_keyboard::KeyState::Pressed
        } else {
            wl_keyboard::KeyState::Released
        };
        for keyboard in keyboards {
            keyboard.key(serial, time, key, key_state.into());
        }
        if pressed {
            self.state.pressed_keys.push(key);
        } else {
            self.state
                .pressed_keys
                .retain(|pressed_key| *pressed_key != key);
        }
        self.state.keyboard_event_count = self.state.keyboard_event_count.saturating_add(1);
        1
    }

    fn pointer_resources_for_surface(&self, surface: &WlSurface) -> Vec<WlPointer> {
        self.state
            .pointers
            .iter()
            .filter(|pointer| pointer.is_alive() && pointer.id().same_client_as(&surface.id()))
            .cloned()
            .collect()
    }

    fn popup_geometry_in_root(&self, popup: &XdgPopup) -> Option<(i32, i32, i32, i32)> {
        popup_geometry_in_root(&self.state, popup)
    }
    fn popup_pointer_target(&self, x: f64, y: f64) -> Option<(WlSurface, f64, f64)> {
        let grab = self.state.popup_grab.as_ref().filter(|grab| grab.active)?;
        for popup in grab.stack.iter().rev().filter(|popup| popup.is_alive()) {
            let data = popup.data::<XdgPopupData>()?;
            if data.dismissed.load(Ordering::Acquire) {
                continue;
            }
            let xdg_data = data.xdg_surface.data::<XdgSurfaceData>()?;
            let (popup_x, popup_y, width, height) = self.popup_geometry_in_root(popup)?;
            if let Some(target) = surface_tree_pointer_target(
                &self.state,
                &xdg_data.wl_surface,
                popup_x,
                popup_y,
                width,
                height,
                x,
                y,
                0,
            ) {
                return Some(target);
            }
        }
        surface_tree_pointer_target(
            &self.state,
            &grab.root,
            0,
            0,
            self.state.output_width,
            self.state.output_height,
            x,
            y,
            0,
        )
    }
    fn pointer_local_coordinates(&self, surface: &WlSurface, x: f64, y: f64) -> (f64, f64) {
        surface_origin_in_root(&self.state, surface, 0)
            .map(|(origin_x, origin_y)| (x - f64::from(origin_x), y - f64::from(origin_y)))
            .unwrap_or((x, y))
    }
    fn focused_pointer_resources(&self) -> Option<(WlSurface, Vec<WlPointer>)> {
        let surface = self.state.pointer_focus_surface.clone()?;
        let pointers: Vec<_> = self
            .state
            .pointers
            .iter()
            .filter(|pointer| pointer.is_alive() && pointer.id().same_client_as(&surface.id()))
            .cloned()
            .collect();
        (!pointers.is_empty()).then_some((surface, pointers))
    }

    fn next_input_serial(&mut self) -> u32 {
        self.state.next_input_serial = self.state.next_input_serial.wrapping_add(1).max(1);
        self.state.next_input_serial
    }

    pub fn pointer_motion(&mut self, x: f64, y: f64, time: u32) -> u32 {
        let target = if self.state.pointer_pressed {
            self.state.pointer_focus_surface.clone().map(|surface| {
                let (local_x, local_y) = self.pointer_local_coordinates(&surface, x, y);
                (surface, local_x, local_y)
            })
        } else if self
            .state
            .popup_grab
            .as_ref()
            .is_some_and(|grab| grab.active)
        {
            self.popup_pointer_target(x, y)
        } else {
            self.state.root_surface.as_ref().and_then(|root| {
                surface_tree_pointer_target(
                    &self.state,
                    root,
                    0,
                    0,
                    self.state.output_width,
                    self.state.output_height,
                    x,
                    y,
                    0,
                )
            })
        };
        let Some((surface, local_x, local_y)) = target else {
            if self.state.pointer_pressed || !self.state.pointer_inside {
                return 0;
            }
            let Some(previous) = self.state.pointer_focus_surface.clone() else {
                return 0;
            };
            let serial = self.next_input_serial();
            for pointer in self.pointer_resources_for_surface(&previous) {
                pointer.leave(serial, &previous);
                if pointer.version() >= 5 {
                    pointer.frame();
                }
            }
            self.state.pointer_inside = false;
            self.state.pointer_x = x;
            self.state.pointer_y = y;
            self.state.pointer_event_count = self.state.pointer_event_count.saturating_add(1);
            return 1;
        };

        let focus_changed = self
            .state
            .pointer_focus_surface
            .as_ref()
            .is_some_and(|focused| focused.id() != surface.id());
        if focus_changed {
            if self.state.pointer_inside {
                let previous = self
                    .state
                    .pointer_focus_surface
                    .clone()
                    .expect("pointer focus checked above");
                let serial = self.next_input_serial();
                for pointer in self.pointer_resources_for_surface(&previous) {
                    pointer.leave(serial, &previous);
                    if pointer.version() >= 5 {
                        pointer.frame();
                    }
                }
            }
            self.state.pointer_focus_surface = Some(surface.clone());
            self.state.pointer_inside = false;
        }

        let pointers = self.pointer_resources_for_surface(&surface);
        if pointers.is_empty() {
            return 0;
        }
        if self.state.pointer_inside {
            for pointer in pointers {
                pointer.motion(time, local_x, local_y);
                if pointer.version() >= 5 {
                    pointer.frame();
                }
            }
        } else {
            let serial = self.next_input_serial();
            for pointer in pointers {
                pointer.enter(serial, &surface, local_x, local_y);
                if pointer.version() >= 5 {
                    pointer.frame();
                }
            }
        }
        self.state.pointer_inside = true;
        self.state.pointer_x = x;
        self.state.pointer_y = y;
        self.state.pointer_event_count = self.state.pointer_event_count.saturating_add(1);
        1
    }

    pub fn pointer_button(&mut self, pressed: bool, time: u32) -> u32 {
        if !self.state.pointer_inside || self.state.pointer_pressed == pressed {
            return 0;
        }
        let Some((surface, pointers)) = self.focused_pointer_resources() else {
            return 0;
        };
        let serial = self.next_input_serial();
        if pressed {
            self.state.popup_grab_serial = Some(PopupGrabSerial { serial, surface });
        }
        let button_state = if pressed {
            wl_pointer::ButtonState::Pressed
        } else {
            wl_pointer::ButtonState::Released
        };
        for pointer in pointers {
            pointer.button(serial, time, 272, button_state.into());
            if pointer.version() >= 5 {
                pointer.frame();
            }
        }
        self.state.pointer_pressed = pressed;
        self.state.pointer_event_count = self.state.pointer_event_count.saturating_add(1);
        1
    }

    pub fn pointer_leave(&mut self) -> u32 {
        if !self.state.pointer_inside || self.state.pointer_pressed {
            return 0;
        }
        let Some((surface, pointers)) = self.focused_pointer_resources() else {
            return 0;
        };
        let serial = self.next_input_serial();
        for pointer in pointers {
            pointer.leave(serial, &surface);
            if pointer.version() >= 5 {
                pointer.frame();
            }
        }
        self.state.pointer_inside = false;
        self.state.pointer_event_count = self.state.pointer_event_count.saturating_add(1);
        1
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

fn receive_probe_keymap(socket_fd: RawFd, keyboard_id: u32) -> io::Result<usize> {
    let mut bytes = [0u8; 16];
    let mut io_vector = libc::iovec {
        iov_base: bytes.as_mut_ptr().cast(),
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

    let received = loop {
        let result = unsafe { libc::recvmsg(socket_fd, &mut message, libc::MSG_WAITALL) };
        if result >= 0 {
            break result as usize;
        }
        let error = io::Error::last_os_error();
        if error.kind() != io::ErrorKind::Interrupted {
            return Err(error);
        }
    };
    if received != bytes.len() {
        return Err(io::Error::new(
            io::ErrorKind::UnexpectedEof,
            "incomplete wl_keyboard.keymap event",
        ));
    }
    if message.msg_flags & (libc::MSG_CTRUNC | libc::MSG_TRUNC) != 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "truncated wl_keyboard.keymap event",
        ));
    }

    let object = u32::from_ne_bytes(bytes[0..4].try_into().expect("fixed header"));
    let word = u32::from_ne_bytes(bytes[4..8].try_into().expect("fixed header"));
    let format = u32::from_ne_bytes(bytes[8..12].try_into().expect("fixed body"));
    let keymap_size = u32::from_ne_bytes(bytes[12..16].try_into().expect("fixed body"));
    if object != keyboard_id || word & 0xffff != 0 || word >> 16 != 16 || format != 1 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "invalid wl_keyboard.keymap wire event",
        ));
    }
    if keymap_size as usize != XKB_KEYMAP.len() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "unexpected wl_keyboard keymap size",
        ));
    }

    let control_message = unsafe { libc::CMSG_FIRSTHDR(&message) };
    if control_message.is_null()
        || unsafe { (*control_message).cmsg_level } != libc::SOL_SOCKET
        || unsafe { (*control_message).cmsg_type } != libc::SCM_RIGHTS
        || unsafe { (*control_message).cmsg_len }
            < unsafe { libc::CMSG_LEN(size_of::<RawFd>() as u32) } as usize
    {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "wl_keyboard.keymap event did not include an FD",
        ));
    }

    let received_fd =
        unsafe { ptr::read_unaligned(libc::CMSG_DATA(control_message).cast::<RawFd>()) };
    if received_fd < 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "wl_keyboard.keymap FD was invalid",
        ));
    }
    let file = unsafe { File::from_raw_fd(received_fd) };
    let mut keymap = vec![0u8; XKB_KEYMAP.len()];
    file.read_exact_at(&mut keymap, 0)?;
    if keymap != XKB_KEYMAP {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "wl_keyboard.keymap FD content mismatch",
        ));
    }
    Ok(keymap.len())
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
pub extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeReceiveKeyboardKeymap(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    socket_fd: i32,
    keyboard_id: i32,
) -> i32 {
    let Ok(keyboard_id) = u32::try_from(keyboard_id) else {
        return -1;
    };
    match receive_probe_keymap(socket_fd, keyboard_id) {
        Ok(size) => i32::try_from(size).unwrap_or(i32::MAX),
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
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeSubcompositorBindCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.subcompositor_bind_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeSubsurfaceCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.subsurface_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeXdgWmBaseBindCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.xdg_wm_base_bind_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeXdgPositionerCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.xdg_positioner_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeXdgPositionerRequestCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.xdg_positioner_request_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeXdgPopupCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.xdg_popup_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeXdgPopupDoneCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.xdg_popup_done_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeXdgPopupGrabDepth(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.xdg_popup_grab_depth()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeDismissPopups(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    i32::try_from(core.dismiss_popups()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeXdgSurfaceCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.xdg_surface_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeXdgToplevelCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.xdg_toplevel_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeXdgAckCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.xdg_ack_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeConfigureFocusedToplevel(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    width: i32,
    height: i32,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    i32::try_from(core.configure_focused_toplevel(width, height)).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativePendingConfigureCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.focused_pending_configure_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeOutputBindCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.output_bind_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeOutputCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.output_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeOutputEventCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.output_event_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeConfigureOutput(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    width: i32,
    height: i32,
    scale: i32,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    i32::try_from(core.configure_output(width, height, scale)).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeSeatBindCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.seat_bind_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativePointerCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.pointer_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeKeyboardCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.keyboard_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeKeyboardEventCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.keyboard_event_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeKeyboardKey(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    key: i32,
    pressed: u8,
    time: i32,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    let Ok(key) = u32::try_from(key) else {
        return -2;
    };
    i32::try_from(core.keyboard_key(key, pressed != 0, time as u32)).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativePointerEventCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.pointer_event_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativePointerMotion(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    x: i32,
    y: i32,
    time: i32,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    i32::try_from(core.pointer_motion(f64::from(x), f64::from(y), time as u32)).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativePointerButton(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    pressed: u8,
    time: i32,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    i32::try_from(core.pointer_button(pressed != 0, time as u32)).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativePointerLeave(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    i32::try_from(core.pointer_leave()).unwrap_or(i32::MAX)
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
    fn clips_and_alpha_blends_popup_frames() {
        let mut destination = CommittedFrame {
            width: 2,
            height: 1,
            format: wl_shm::Format::Xrgb8888,
            pixels: vec![10, 20, 30, 0, 40, 50, 60, 0],
        };
        let source = CommittedFrame {
            width: 2,
            height: 1,
            format: wl_shm::Format::Argb8888,
            pixels: vec![110, 120, 130, 128, 210, 220, 230, 255],
        };

        blend_popup_frame(&mut destination, &source, 1, 0, 2, 1);

        assert_eq!(destination.pixels, [10, 20, 30, 0, 75, 85, 95, 0]);
    }
    fn test_positioner(
        size: (i32, i32),
        anchor_rect: (i32, i32, i32, i32),
        adjustment: xdg_positioner::ConstraintAdjustment,
    ) -> XdgPositionerState {
        XdgPositionerState {
            size: Some(size),
            anchor_rect: Some(anchor_rect),
            anchor: Some(xdg_positioner::Anchor::TopLeft),
            gravity: Some(xdg_positioner::Gravity::BottomRight),
            constraint_adjustment: Some(adjustment),
            ..XdgPositionerState::default()
        }
    }

    fn test_bounds() -> PopupBounds {
        PopupBounds {
            left: 0,
            top: 0,
            right: 100,
            bottom: 80,
        }
    }

    #[test]
    fn flips_popup_geometry_before_other_constraint_adjustments() {
        let positioner = XdgPositionerState {
            anchor: Some(xdg_positioner::Anchor::BottomRight),
            gravity: Some(xdg_positioner::Gravity::BottomRight),
            ..test_positioner(
                (20, 10),
                (90, 70, 10, 10),
                xdg_positioner::ConstraintAdjustment::FlipX
                    | xdg_positioner::ConstraintAdjustment::FlipY,
            )
        };

        assert_eq!(
            positioner.constrained_geometry(test_bounds()),
            Some((70, 60, 20, 10))
        );
    }

    #[test]
    fn slides_popup_geometry_without_changing_its_size() {
        let positioner = test_positioner(
            (30, 20),
            (95, 75, 1, 1),
            xdg_positioner::ConstraintAdjustment::SlideX
                | xdg_positioner::ConstraintAdjustment::SlideY,
        );

        assert_eq!(
            positioner.constrained_geometry(test_bounds()),
            Some((70, 60, 30, 20))
        );
    }

    #[test]
    fn resizes_popup_geometry_to_the_output_bounds() {
        let positioner = test_positioner(
            (130, 100),
            (-10, -5, 1, 1),
            xdg_positioner::ConstraintAdjustment::ResizeX
                | xdg_positioner::ConstraintAdjustment::ResizeY,
        );

        assert_eq!(
            positioner.constrained_geometry(test_bounds()),
            Some((0, 0, 100, 80))
        );
    }

    #[test]
    fn applies_region_add_subtract_and_readd_in_order() {
        let mut region = RegionState::default();
        let left_half = RegionRectangle {
            x: 0,
            y: 0,
            width: 5,
            height: 10,
        };
        region
            .operations
            .push(RegionOperation::Add(RegionRectangle {
                x: 0,
                y: 0,
                width: 10,
                height: 10,
            }));
        region.operations.push(RegionOperation::Subtract(left_half));
        assert!(!region.contains(2.0, 2.0));
        assert!(region.contains(7.0, 2.0));

        region.operations.push(RegionOperation::Add(left_half));
        assert!(region.contains(2.0, 2.0));
        assert!(!region.contains(10.0, 2.0));
    }

    #[test]
    fn intersects_committed_input_region_with_surface_bounds() {
        let mut surface = SurfaceState::default();
        surface.committed_input_region = Some(RegionState {
            operations: vec![RegionOperation::Add(RegionRectangle {
                x: 1,
                y: 0,
                width: 2,
                height: 2,
            })],
        });

        assert!(!surface_accepts_pointer(&surface, 0.0, 1.0, 2, 2));
        assert!(surface_accepts_pointer(&surface, 1.0, 1.0, 2, 2));
        assert!(!surface_accepts_pointer(&surface, 2.0, 1.0, 2, 2));
    }

    #[test]
    fn creates_wayland_display_and_reports_protocol_version() {
        let core = CompositorCore::new().expect("Wayland display");
        assert!(!core.is_stopping());
        assert_eq!(core.compositor_bind_count(), 0);
        assert_eq!(core.subcompositor_bind_count(), 0);
        assert_eq!(core.subsurface_count(), 0);
        assert_eq!(core.xdg_wm_base_bind_count(), 0);
        assert_eq!(core.xdg_positioner_count(), 0);
        assert_eq!(core.xdg_positioner_request_count(), 0);
        assert_eq!(core.xdg_popup_count(), 0);
        assert_eq!(core.xdg_popup_done_count(), 0);
        assert_eq!(core.xdg_popup_grab_depth(), 0);
        assert_eq!(core.xdg_surface_count(), 0);
        assert_eq!(core.xdg_toplevel_count(), 0);
        assert_eq!(core.xdg_ack_count(), 0);
        assert_eq!(core.focused_pending_configure_count(), 0);
        assert_eq!(core.output_bind_count(), 0);
        assert_eq!(core.output_count(), 0);
        assert_eq!(core.output_event_count(), 0);
        assert_eq!(core.seat_bind_count(), 0);
        assert_eq!(core.pointer_count(), 0);
        assert_eq!(core.pointer_event_count(), 0);
        assert_eq!(core.keyboard_count(), 0);
        assert_eq!(core.keyboard_event_count(), 0);
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

    #[test]
    fn embeds_null_terminated_xkb_v1_keymap() {
        assert!(XKB_KEYMAP.starts_with(b"xkb_keymap {"));
        assert!(XKB_KEYMAP.ends_with(b"};\n\0"));
    }
}
