use std::collections::{HashMap, VecDeque};
use std::fs::File;
use std::io::{self, Write};
use std::mem::{size_of, zeroed};
use std::ops::Range;
use std::os::fd::{AsFd, AsRawFd, FromRawFd, IntoRawFd, RawFd};
use std::os::unix::fs::FileExt;
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::{Path, PathBuf};
use std::ptr;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, Mutex};

use jni::{JNIEnv, objects::JByteArray, sys::jbyteArray};
use wayland_protocols::wp::fractional_scale::v1::server::wp_fractional_scale_manager_v1::{
    self, WpFractionalScaleManagerV1,
};
use wayland_protocols::wp::fractional_scale::v1::server::wp_fractional_scale_v1::{
    self, WpFractionalScaleV1,
};
use wayland_protocols::wp::pointer_gestures::zv1::server::zwp_pointer_gesture_hold_v1::{
    self, ZwpPointerGestureHoldV1,
};
use wayland_protocols::wp::pointer_gestures::zv1::server::zwp_pointer_gesture_pinch_v1::{
    self, ZwpPointerGesturePinchV1,
};
use wayland_protocols::wp::pointer_gestures::zv1::server::zwp_pointer_gesture_swipe_v1::{
    self, ZwpPointerGestureSwipeV1,
};
use wayland_protocols::wp::pointer_gestures::zv1::server::zwp_pointer_gestures_v1::{
    self, ZwpPointerGesturesV1,
};
use wayland_protocols::wp::text_input::zv3::server::zwp_text_input_manager_v3::{
    self, ZwpTextInputManagerV3,
};
use wayland_protocols::wp::text_input::zv3::server::zwp_text_input_v3::{self, ZwpTextInputV3};
use wayland_protocols::wp::viewporter::server::wp_viewport::{self, WpViewport};
use wayland_protocols::wp::viewporter::server::wp_viewporter::{self, WpViewporter};
use wayland_protocols::xdg::shell::server::xdg_popup::{self, XdgPopup};
use wayland_protocols::xdg::shell::server::xdg_positioner::{self, XdgPositioner};
use wayland_protocols::xdg::shell::server::xdg_surface::{self, XdgSurface};
use wayland_protocols::xdg::shell::server::xdg_toplevel::{self, XdgToplevel};
use wayland_protocols::xdg::shell::server::xdg_wm_base::{self, XdgWmBase};
use wayland_server::protocol::wl_buffer::{self, WlBuffer};
use wayland_server::protocol::wl_callback::{self, WlCallback};
use wayland_server::protocol::wl_compositor::{self, WlCompositor};
use wayland_server::protocol::wl_data_device::{self, WlDataDevice};
use wayland_server::protocol::wl_data_device_manager::{self, WlDataDeviceManager};
use wayland_server::protocol::wl_data_offer::{self, WlDataOffer};
use wayland_server::protocol::wl_data_source::{self, WlDataSource};
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
use wayland_server::protocol::wl_touch::{self, WlTouch};
use wayland_server::{
    Client, DataInit, Dispatch, Display, DisplayHandle, GlobalDispatch, New, Resource, WEnum,
    backend::ClientId,
};

/// Owns protocol dispatch independently from Android Activity and rendering state.
pub struct CompositorCore {
    display: Display<CompositorState>,
    state: CompositorState,
    listener: Option<UnixListener>,
    socket_path: Option<PathBuf>,
    accepted_client_count: u32,
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
    surfaces: Vec<WlSurface>,
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
    popup_base_frame: Option<Arc<CommittedFrame>>,
    popup_base_armed: bool,
    xdg_wm_base_binds: u32,
    xdg_positioner_count: u32,
    xdg_positioner_request_count: u32,
    xdg_popup_count: u32,
    xdg_popup_done_count: u32,
    popups: Vec<XdgPopup>,
    popup_grab: Option<PopupGrabState>,
    popup_grab_serial: Option<PopupGrabSerial>,
    selection_serials: VecDeque<PopupGrabSerial>,
    xdg_surface_count: u32,
    xdg_toplevel_count: u32,
    toplevels: Vec<XdgToplevel>,
    primary_toplevel: Option<XdgToplevel>,
    active_toplevel: Option<XdgToplevel>,
    window_change_serial: u32,
    xdg_ack_count: u32,
    next_configure_serial: u32,
    output_binds: u32,
    output_event_count: u32,
    output_width: i32,
    output_height: i32,
    output_scale: i32,
    output_fractional_scale: u32,
    tile_toplevels: bool,
    outputs: Vec<WlOutput>,
    fractional_scales: Vec<WpFractionalScaleV1>,
    seat_binds: u32,
    pointer_count: u32,
    pointer_event_count: u32,
    presentation_callbacks: Vec<WlCallback>,
    presentation_damage: Vec<RegionRectangle>,
    next_input_serial: u32,
    pointers: Vec<WlPointer>,
    pointer_focus_surface: Option<WlSurface>,
    last_pointer_enter_serial: u32,
    cursor_surface: Option<WlSurface>,
    cursor_frame: Option<Arc<CommittedFrame>>,
    cursor_hotspot_x: i32,
    cursor_hotspot_y: i32,
    touches: Vec<WlTouch>,
    active_touches: Vec<ActiveTouch>,
    touch_event_count: u32,
    swipe_gestures: Vec<ZwpPointerGestureSwipeV1>,
    pinch_gestures: Vec<ZwpPointerGesturePinchV1>,
    hold_gestures: Vec<ZwpPointerGestureHoldV1>,
    gesture_event_count: u32,
    pointer_inside: bool,
    pointer_pressed: bool,
    pointer_x: f64,
    pointer_y: f64,
    keyboard_count: u32,
    keyboard_event_count: u32,
    keyboards: Vec<WlKeyboard>,
    keyboard_focus_surface: Option<WlSurface>,
    pressed_keys: Vec<u32>,
    data_device_manager_binds: u32,
    data_source_count: u32,
    data_device_count: u32,
    data_offer_count: u32,
    data_sources: Vec<WlDataSource>,
    data_devices: Vec<WlDataDevice>,
    data_offers: Vec<WlDataOffer>,
    selection_source: Option<WlDataSource>,
    clipboard_active: bool,
    android_clipboard_offered: bool,
    pending_android_paste_fds: VecDeque<File>,
    pending_linux_copy_fds: VecDeque<File>,
    pending_linux_drag_fds: VecDeque<File>,
    pending_linux_drag_mime_types: VecDeque<String>,
    linux_drag_source: Option<WlDataSource>,
    android_drag: Option<AndroidDragState>,
    text_input_manager_binds: u32,
    text_input_count: u32,
    text_inputs: Vec<ZwpTextInputV3>,
    ime_active: bool,
    ime_show_requests: u32,
    ime_hide_requests: u32,
}

const XKB_KEYMAP: &[u8] = concat!(include_str!("archphene-us.xkb"), "\0").as_bytes();

#[derive(Default)]
pub struct SurfaceData {
    inner: Mutex<SurfaceState>,
}

#[derive(Default)]
struct SurfaceState {
    pending_buffer: Option<Option<SurfaceBuffer>>,
    pending_offset: (i32, i32),
    pending_surface_damage: Vec<RegionRectangle>,
    pending_buffer_damage: Vec<RegionRectangle>,
    pending_callbacks: Vec<WlCallback>,
    pending_input_region: Option<Option<RegionState>>,
    committed_input_region: Option<RegionState>,
    cached_input_region: Option<Option<RegionState>>,
    pending_buffer_scale: Option<i32>,
    committed_buffer_scale: i32,
    cached_buffer_scale: Option<i32>,
    pending_buffer_transform: Option<BufferTransform>,
    committed_buffer_transform: BufferTransform,
    cached_buffer_transform: Option<BufferTransform>,
    viewport: Option<WpViewport>,
    pending_viewport_source: Option<Option<ViewportSource>>,
    committed_viewport_source: Option<ViewportSource>,
    cached_viewport_source: Option<Option<ViewportSource>>,
    pending_viewport_destination: Option<Option<(i32, i32)>>,
    committed_viewport_destination: Option<(i32, i32)>,
    cached_viewport_destination: Option<Option<(i32, i32)>>,
    fractional_scale: Option<WpFractionalScaleV1>,
    committed_frame: Option<Arc<CommittedFrame>>,
    cached_frame: Option<Option<Arc<CommittedFrame>>>,
    cached_callbacks: Vec<WlCallback>,
    cached_damage: Vec<RegionRectangle>,
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

#[derive(Clone, Copy, Debug, PartialEq)]
struct ViewportSource {
    x: f64,
    y: f64,
    width: f64,
    height: f64,
}

struct ViewportData {
    surface: WlSurface,
}

struct FractionalScaleData {
    surface: WlSurface,
}

#[derive(Clone, Copy, Eq, PartialEq)]
enum SurfaceRole {
    XdgToplevel,
    XdgPopup,
    Subsurface,
    Cursor,
}

#[derive(Clone, Copy, Default, Eq, PartialEq)]
enum BufferTransform {
    #[default]
    Normal,
    Rotate90,
    Rotate180,
    Rotate270,
    Flipped,
    Flipped90,
    Flipped180,
    Flipped270,
}

impl From<wl_output::Transform> for BufferTransform {
    fn from(transform: wl_output::Transform) -> Self {
        match transform {
            wl_output::Transform::Normal => Self::Normal,
            wl_output::Transform::_90 => Self::Rotate90,
            wl_output::Transform::_180 => Self::Rotate180,
            wl_output::Transform::_270 => Self::Rotate270,
            wl_output::Transform::Flipped => Self::Flipped,
            wl_output::Transform::Flipped90 => Self::Flipped90,
            wl_output::Transform::Flipped180 => Self::Flipped180,
            wl_output::Transform::Flipped270 => Self::Flipped270,
            _ => Self::Normal,
        }
    }
}

#[derive(Default)]
struct XdgWmBaseData {
    child_count: Arc<AtomicU32>,
}

#[derive(Default)]
struct DataSourceData {
    mime_types: Mutex<Vec<String>>,
    used: AtomicBool,
}

struct DataDeviceData {
    seat: WlSeat,
}

#[derive(Clone)]
enum ClipboardOfferSource {
    Wayland(WlDataSource),
    AndroidClipboard,
    AndroidDrag(Arc<Mutex<HashMap<String, Vec<u8>>>>),
}

struct AndroidDragState {
    device: WlDataDevice,
    offer: WlDataOffer,
    payloads: Arc<Mutex<HashMap<String, Vec<u8>>>>,
}

struct DataOfferData {
    source: ClipboardOfferSource,
    mime_types: Vec<String>,
}
struct TextInputData {
    seat: WlSeat,
    state: Mutex<TextInputState>,
}

#[derive(Default)]
struct TextInputState {
    focused_surface: Option<WlSurface>,
    pending_enabled: Option<bool>,
    enabled: bool,
    pending_surrounding_text: Option<SurroundingText>,
    surrounding_text: Option<SurroundingText>,
    pending_content_type: Option<(u32, u32)>,
    content_type: (u32, u32),
    pending_cursor_rectangle: Option<(i32, i32, i32, i32)>,
    cursor_rectangle: Option<(i32, i32, i32, i32)>,
    commit_count: u32,
}

struct SurroundingText {
    text: String,
    cursor: i32,
    anchor: i32,
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

struct ActiveTouch {
    id: i32,
    surface: WlSurface,
}

struct PointerGestureData {
    pointer: WlPointer,
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
    pending_configures: VecDeque<XdgConfigure>,
    acknowledged_configure: Option<XdgConfigure>,
    pending_window_geometry: Option<WindowGeometry>,
    committed_window_geometry: Option<WindowGeometry>,
}

#[derive(Clone, Copy)]
struct XdgConfigure {
    serial: u32,
    popup_geometry: Option<(i32, i32, i32, i32)>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct WindowGeometry {
    x: i32,
    y: i32,
    width: i32,
    height: i32,
}

impl XdgSurfaceState {
    fn commit_window_geometry(&mut self) -> bool {
        let Some(geometry) = self.pending_window_geometry.take() else {
            return false;
        };
        let changed = self.committed_window_geometry != Some(geometry);
        self.committed_window_geometry = Some(geometry);
        changed
    }
}

struct XdgToplevelData {
    xdg_surface: XdgSurface,
    parent: Mutex<Option<XdgToplevel>>,
    title: Mutex<String>,
    app_id: Mutex<String>,
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
    source: Option<Arc<CommittedFrame>>,
}

impl BufferTransform {
    fn surface_size(self, buffer_width: u32, buffer_height: u32) -> (u32, u32) {
        match self {
            Self::Rotate90 | Self::Rotate270 | Self::Flipped90 | Self::Flipped270 => {
                (buffer_height, buffer_width)
            }
            _ => (buffer_width, buffer_height),
        }
    }

    fn buffer_coordinates(
        self,
        surface_x: u32,
        surface_y: u32,
        surface_width: u32,
        surface_height: u32,
    ) -> (u32, u32) {
        match self {
            Self::Normal => (surface_x, surface_y),
            Self::Rotate90 => (surface_height - 1 - surface_y, surface_x),
            Self::Rotate180 => (
                surface_width - 1 - surface_x,
                surface_height - 1 - surface_y,
            ),
            Self::Rotate270 => (surface_y, surface_width - 1 - surface_x),
            Self::Flipped => (surface_width - 1 - surface_x, surface_y),
            Self::Flipped90 => (surface_y, surface_x),
            Self::Flipped180 => (surface_x, surface_height - 1 - surface_y),
            Self::Flipped270 => (
                surface_height - 1 - surface_y,
                surface_width - 1 - surface_x,
            ),
        }
    }

    fn surface_coordinates(
        self,
        buffer_x: u32,
        buffer_y: u32,
        buffer_width: u32,
        buffer_height: u32,
    ) -> (u32, u32) {
        match self {
            Self::Normal => (buffer_x, buffer_y),
            Self::Rotate90 => (buffer_y, buffer_width - 1 - buffer_x),
            Self::Rotate180 => (buffer_width - 1 - buffer_x, buffer_height - 1 - buffer_y),
            Self::Rotate270 => (buffer_height - 1 - buffer_y, buffer_x),
            Self::Flipped => (buffer_width - 1 - buffer_x, buffer_y),
            Self::Flipped90 => (buffer_y, buffer_x),
            Self::Flipped180 => (buffer_x, buffer_height - 1 - buffer_y),
            Self::Flipped270 => (buffer_height - 1 - buffer_y, buffer_width - 1 - buffer_x),
        }
    }

    fn buffer_damage_to_surface(
        self,
        damage: RegionRectangle,
        buffer_width: u32,
        buffer_height: u32,
        scale: u32,
    ) -> Option<RegionRectangle> {
        let damage = damage.clip(buffer_width, buffer_height)?;
        let left = damage.x as u32;
        let top = damage.y as u32;
        let right = damage.right() as u32 - 1;
        let bottom = damage.bottom() as u32 - 1;
        let corners = [
            self.surface_coordinates(left, top, buffer_width, buffer_height),
            self.surface_coordinates(right, top, buffer_width, buffer_height),
            self.surface_coordinates(left, bottom, buffer_width, buffer_height),
            self.surface_coordinates(right, bottom, buffer_width, buffer_height),
        ];
        let min_x = corners.iter().map(|point| point.0).min()?;
        let min_y = corners.iter().map(|point| point.1).min()?;
        let max_x = corners.iter().map(|point| point.0).max()?;
        let max_y = corners.iter().map(|point| point.1).max()?;
        let left = min_x / scale;
        let top = min_y / scale;
        let right = (max_x + 1).div_ceil(scale);
        let bottom = (max_y + 1).div_ceil(scale);
        RegionRectangle::new(
            left as i32,
            top as i32,
            (right - left) as i32,
            (bottom - top) as i32,
        )
    }
}

fn damage_for_commit(
    surface_damage: Vec<RegionRectangle>,
    buffer_damage: Vec<RegionRectangle>,
    frame: Option<&Arc<CommittedFrame>>,
    transform: BufferTransform,
    scale: i32,
    force_full: bool,
) -> Vec<RegionRectangle> {
    let Some(frame) = frame else {
        return Vec::new();
    };
    let scale = u32::try_from(scale).unwrap_or(1).max(1);
    let mut damage = surface_damage
        .into_iter()
        .filter_map(|rectangle| rectangle.clip(frame.width, frame.height))
        .collect::<Vec<_>>();
    let source = original_buffer_frame(frame);
    damage.extend(buffer_damage.into_iter().filter_map(|rectangle| {
        transform.buffer_damage_to_surface(rectangle, source.width, source.height, scale)
    }));
    if force_full {
        damage.clear();
        if let Some(full) = RegionRectangle::new(0, 0, frame.width as i32, frame.height as i32) {
            damage.push(full);
        }
    }
    damage
}

fn original_buffer_frame(frame: &Arc<CommittedFrame>) -> Arc<CommittedFrame> {
    let mut source = Arc::clone(frame);
    while let Some(parent) = source.source.as_ref() {
        source = Arc::clone(parent);
    }
    source
}

fn transform_buffer_frame(
    frame: Arc<CommittedFrame>,
    transform: BufferTransform,
    scale: i32,
) -> Result<Arc<CommittedFrame>, ()> {
    let source = original_buffer_frame(&frame);
    let scale = u32::try_from(scale).map_err(|_| ())?;
    if scale == 0 {
        return Err(());
    }
    let (physical_width, physical_height) = transform.surface_size(source.width, source.height);
    if physical_width % scale != 0 || physical_height % scale != 0 {
        return Err(());
    }
    if transform == BufferTransform::Normal && scale == 1 {
        return Ok(source);
    }

    let width = physical_width / scale;
    let height = physical_height / scale;
    let pixel_count = width
        .checked_mul(height)
        .and_then(|count| count.checked_mul(4))
        .and_then(|count| usize::try_from(count).ok())
        .ok_or(())?;
    let mut pixels = vec![0; pixel_count];
    for surface_y in 0..height {
        for surface_x in 0..width {
            let physical_x = surface_x * scale;
            let physical_y = surface_y * scale;
            let (buffer_x, buffer_y) = transform.buffer_coordinates(
                physical_x,
                physical_y,
                physical_width,
                physical_height,
            );
            let source_index = ((buffer_y * source.width + buffer_x) * 4) as usize;
            let destination_index = ((surface_y * width + surface_x) * 4) as usize;
            pixels[destination_index..destination_index + 4]
                .copy_from_slice(&source.pixels[source_index..source_index + 4]);
        }
    }
    Ok(Arc::new(CommittedFrame {
        width,
        height,
        format: source.format,
        pixels,
        source: Some(source),
    }))
}

#[derive(Clone, Copy, Debug)]
enum ViewportApplyError {
    BadSize,
    OutOfBuffer,
}

fn apply_viewport_to_frame(
    frame: Arc<CommittedFrame>,
    source: Option<ViewportSource>,
    destination: Option<(i32, i32)>,
) -> Result<Arc<CommittedFrame>, ViewportApplyError> {
    if source.is_none() && destination.is_none() {
        return Ok(frame);
    }
    let source = source.unwrap_or(ViewportSource {
        x: 0.0,
        y: 0.0,
        width: f64::from(frame.width),
        height: f64::from(frame.height),
    });
    let right = source.x + source.width;
    let bottom = source.y + source.height;
    if !source.x.is_finite()
        || !source.y.is_finite()
        || !source.width.is_finite()
        || !source.height.is_finite()
        || source.x < 0.0
        || source.y < 0.0
        || source.width <= 0.0
        || source.height <= 0.0
        || right > f64::from(frame.width)
        || bottom > f64::from(frame.height)
    {
        return Err(ViewportApplyError::OutOfBuffer);
    }
    let (width, height) = match destination {
        Some((width, height)) => (
            u32::try_from(width).map_err(|_| ViewportApplyError::BadSize)?,
            u32::try_from(height).map_err(|_| ViewportApplyError::BadSize)?,
        ),
        None => {
            if source.width.fract() != 0.0 || source.height.fract() != 0.0 {
                return Err(ViewportApplyError::BadSize);
            }
            (source.width as u32, source.height as u32)
        }
    };
    if width == 0 || height == 0 {
        return Err(ViewportApplyError::BadSize);
    }
    if source.x == 0.0
        && source.y == 0.0
        && source.width == f64::from(frame.width)
        && source.height == f64::from(frame.height)
        && width == frame.width
        && height == frame.height
    {
        return Ok(frame);
    }

    let byte_count = width
        .checked_mul(height)
        .and_then(|count| count.checked_mul(4))
        .and_then(|count| usize::try_from(count).ok())
        .ok_or(ViewportApplyError::BadSize)?;
    let mut pixels = vec![0; byte_count];
    for destination_y in 0..height {
        let source_y = (source.y
            + (f64::from(destination_y) + 0.5) * source.height / f64::from(height))
        .floor()
        .clamp(0.0, f64::from(frame.height.saturating_sub(1))) as u32;
        for destination_x in 0..width {
            let source_x = (source.x
                + (f64::from(destination_x) + 0.5) * source.width / f64::from(width))
            .floor()
            .clamp(0.0, f64::from(frame.width.saturating_sub(1))) as u32;
            let source_index = ((source_y * frame.width + source_x) * 4) as usize;
            let destination_index = ((destination_y * width + destination_x) * 4) as usize;
            pixels[destination_index..destination_index + 4]
                .copy_from_slice(&frame.pixels[source_index..source_index + 4]);
        }
    }
    Ok(Arc::new(CommittedFrame {
        width,
        height,
        format: frame.format,
        pixels,
        source: Some(frame),
    }))
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
    let Some(frame) = core.state.last_frame.as_ref() else {
        return -1;
    };
    copy_frame_to_bitmap(frame, environment, bitmap)
}

#[cfg(target_os = "android")]
fn copy_frame_to_bitmap(
    frame: &CommittedFrame,
    environment: *mut std::ffi::c_void,
    bitmap: *mut std::ffi::c_void,
) -> i32 {
    const ANDROID_BITMAP_FORMAT_RGBA_8888: i32 = 1;
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

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct RegionRectangle {
    x: i32,
    y: i32,
    width: i32,
    height: i32,
}

impl RegionRectangle {
    fn new(x: i32, y: i32, width: i32, height: i32) -> Option<Self> {
        (width > 0 && height > 0).then_some(Self {
            x,
            y,
            width,
            height,
        })
    }

    fn contains(self, x: f64, y: f64) -> bool {
        x >= f64::from(self.x)
            && y >= f64::from(self.y)
            && x < self.right() as f64
            && y < self.bottom() as f64
    }

    fn right(self) -> i64 {
        i64::from(self.x) + i64::from(self.width)
    }

    fn bottom(self) -> i64 {
        i64::from(self.y) + i64::from(self.height)
    }

    fn clip(self, width: u32, height: u32) -> Option<Self> {
        let left = i64::from(self.x).max(0).min(i64::from(width));
        let top = i64::from(self.y).max(0).min(i64::from(height));
        let right = self.right().max(0).min(i64::from(width));
        let bottom = self.bottom().max(0).min(i64::from(height));
        (right > left && bottom > top).then_some(Self {
            x: left as i32,
            y: top as i32,
            width: (right - left) as i32,
            height: (bottom - top) as i32,
        })
    }

    fn translated(self, x: i32, y: i32) -> Self {
        Self {
            x: self.x.saturating_add(x),
            y: self.y.saturating_add(y),
            ..self
        }
    }

    fn union(self, other: Self) -> Self {
        let left = i64::from(self.x).min(i64::from(other.x));
        let top = i64::from(self.y).min(i64::from(other.y));
        let right = self.right().max(other.right());
        let bottom = self.bottom().max(other.bottom());
        Self {
            x: left.clamp(i64::from(i32::MIN), i64::from(i32::MAX)) as i32,
            y: top.clamp(i64::from(i32::MIN), i64::from(i32::MAX)) as i32,
            width: (right - left).clamp(1, i64::from(i32::MAX)) as i32,
            height: (bottom - top).clamp(1, i64::from(i32::MAX)) as i32,
        }
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
            source: None,
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
                        parent: Mutex::new(None),
                        title: Mutex::new(String::new()),
                        app_id: Mutex::new(String::new()),
                    },
                );
                surface_state.xdg_toplevel = Some(toplevel.clone());
                state.toplevels.push(toplevel);
                state.window_change_serial = state.window_change_serial.wrapping_add(1).max(1);
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
                if !state.popups.iter().any(|candidate| {
                    candidate.is_alive()
                        && candidate
                            .data::<XdgPopupData>()
                            .is_some_and(|data| !data.dismissed.load(Ordering::Acquire))
                }) {
                    if state.popup_base_frame.is_none() {
                        state.popup_base_frame = state.last_frame.clone();
                    }
                    state.popup_base_armed = false;
                }
                state.popups.push(popup.clone());
                surface_state.xdg_popup = Some(popup);
                state.xdg_popup_count = state.xdg_popup_count.saturating_add(1);
            }
            xdg_surface::Request::SetWindowGeometry {
                x,
                y,
                width,
                height,
            } => {
                if width <= 0 || height <= 0 {
                    resource.post_error(
                        xdg_surface::Error::InvalidSize,
                        "window geometry must be positive",
                    );
                } else {
                    data.state
                        .lock()
                        .unwrap_or_else(|error| error.into_inner())
                        .pending_window_geometry = Some(WindowGeometry {
                        x,
                        y,
                        width,
                        height,
                    });
                }
            }
            xdg_surface::Request::AckConfigure { serial } => {
                let mut xdg_state = data.state.lock().unwrap_or_else(|error| error.into_inner());
                let Some(position) = xdg_state
                    .pending_configures
                    .iter()
                    .position(|pending| pending.serial == serial)
                else {
                    resource.post_error(
                        xdg_surface::Error::InvalidSerial,
                        "ack_configure did not match a pending serial",
                    );
                    return;
                };
                let acknowledged = xdg_state.pending_configures[position];
                xdg_state.pending_configures.drain(..=position);
                xdg_state.acknowledged_configure = Some(acknowledged);
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
                let pending_input_serial = state
                    .popup_grab_serial
                    .as_ref()
                    .filter(|candidate| candidate.surface.id().same_client_as(&data.parent.id()));
                let effective_serial = if serial == 0 {
                    pending_input_serial.map_or(0, |candidate| candidate.serial)
                } else {
                    serial
                };
                let serial_matches_client = pending_input_serial
                    .is_some_and(|candidate| candidate.serial == effective_serial);
                let serial_extends_grab = state.popup_grab.as_ref().is_some_and(|grab| {
                    grab.active
                        && grab.serial == effective_serial
                        && grab.seat.id() == seat.id()
                        && grab.root.id().same_client_as(&data.parent.id())
                });
                let is_topmost_popup = state
                    .popups
                    .iter()
                    .rev()
                    .find(|popup| popup.id().same_client_as(&resource.id()))
                    .is_some_and(|popup| popup.id() == resource.id());
                if effective_serial == 0
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
                            serial: effective_serial,
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
                state.next_configure_serial = state.next_configure_serial.wrapping_add(1).max(1);
                let serial = state.next_configure_serial;
                if let Some(xdg_data) = data.xdg_surface.data::<XdgSurfaceData>() {
                    xdg_data
                        .state
                        .lock()
                        .unwrap_or_else(|error| error.into_inner())
                        .pending_configures
                        .push_back(XdgConfigure {
                            serial,
                            popup_geometry: Some(geometry),
                        });
                }
                resource.repositioned(token);
                resource.configure(x, y, width, height);
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
        state: &mut Self,
        _client: &Client,
        resource: &XdgToplevel,
        request: xdg_toplevel::Request,
        data: &XdgToplevelData,
        _handle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            xdg_toplevel::Request::Destroy => {}
            xdg_toplevel::Request::SetParent { parent } => {
                if parent.as_ref().is_some_and(|candidate| {
                    candidate.id() == resource.id()
                        || !candidate.id().same_client_as(&resource.id())
                }) {
                    resource.post_error(
                        xdg_toplevel::Error::InvalidParent,
                        "xdg_toplevel parent must be a different toplevel from the same client",
                    );
                    return;
                }
                *data
                    .parent
                    .lock()
                    .unwrap_or_else(|error| error.into_inner()) = parent;
                state.window_change_serial = state.window_change_serial.wrapping_add(1).max(1);
            }
            xdg_toplevel::Request::SetTitle { title } => {
                *data.title.lock().unwrap_or_else(|error| error.into_inner()) = title;
                state.window_change_serial = state.window_change_serial.wrapping_add(1).max(1);
            }
            xdg_toplevel::Request::SetAppId { app_id } => {
                *data
                    .app_id
                    .lock()
                    .unwrap_or_else(|error| error.into_inner()) = app_id;
                state.window_change_serial = state.window_change_serial.wrapping_add(1).max(1);
            }
            xdg_toplevel::Request::ShowWindowMenu { .. }
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
        resource: &XdgToplevel,
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
        let was_primary = state
            .primary_toplevel
            .as_ref()
            .is_some_and(|primary| primary.id() == resource.id());
        let was_active = state
            .active_toplevel
            .as_ref()
            .is_some_and(|active| active.id() == resource.id());
        state
            .toplevels
            .retain(|toplevel| toplevel.id() != resource.id());
        if was_primary {
            state.primary_toplevel = state.toplevels.iter().find_map(|toplevel| {
                surface_frame(&toplevel_surface(toplevel)?).map(|_| toplevel.clone())
            });
        }
        if was_active {
            let replacement = state.toplevels.iter().rev().find_map(|toplevel| {
                let surface = toplevel_surface(toplevel)?;
                let frame = surface_frame(&surface)?;
                Some((toplevel.clone(), surface, frame))
            });
            if let Some((toplevel, surface, frame)) = replacement {
                state.active_toplevel = Some(toplevel);
                state.root_surface = Some(surface.clone());
                state.root_frame = Some(frame);
                state.pointer_focus_surface = Some(surface.clone());
                state.pointer_inside = false;
                state.pointer_pressed = false;
                set_keyboard_focus(state, Some(surface));
            } else {
                state.active_toplevel = None;
                state.root_surface = None;
                state.root_frame = None;
                state.pointer_focus_surface = None;
                set_keyboard_focus(state, None);
            }
            update_composited_frame(state);
        }
        state.window_change_serial = state.window_change_serial.wrapping_add(1).max(1);
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
        let mut disabled = 0u32;
        for text_input in state.text_inputs.iter().filter(|text_input| {
            text_input.is_alive() && text_input.id().same_client_as(&previous.id())
        }) {
            text_input.leave(&previous);
            if let Some(data) = text_input.data::<TextInputData>() {
                let mut text_state = data.state.lock().unwrap_or_else(|error| error.into_inner());
                disabled = disabled.saturating_add(u32::from(text_state.enabled));
                *text_state = TextInputState::default();
            }
        }
        if disabled > 0 {
            state.ime_active = false;
            state.ime_hide_requests = state.ime_hide_requests.saturating_add(1);
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
        for text_input in state.text_inputs.iter().filter(|text_input| {
            text_input.is_alive() && text_input.id().same_client_as(&surface.id())
        }) {
            text_input.enter(&surface);
            if let Some(data) = text_input.data::<TextInputData>() {
                data.state
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                    .focused_surface = Some(surface.clone());
            }
        }
        state.keyboard_focus_surface = Some(surface);
    } else {
        state.pressed_keys.clear();
    }
}
const TEXT_MIME_TYPES: [&str; 2] = ["text/plain;charset=utf-8", "text/plain"];
const URI_LIST_MIME_TYPE: &str = "text/uri-list";
const ANDROID_DRAG_MIME_TYPES: [&str; 3] =
    [TEXT_MIME_TYPES[0], TEXT_MIME_TYPES[1], URI_LIST_MIME_TYPE];

fn create_cloexec_pipe() -> io::Result<(File, File)> {
    let mut descriptors = [-1; 2];
    if unsafe { libc::pipe2(descriptors.as_mut_ptr(), libc::O_CLOEXEC) } != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(unsafe {
        (
            File::from_raw_fd(descriptors[0]),
            File::from_raw_fd(descriptors[1]),
        )
    })
}

fn publish_offer_to_device(
    state: &mut CompositorState,
    handle: &DisplayHandle,
    device: &WlDataDevice,
    source: ClipboardOfferSource,
    mime_types: Vec<String>,
) {
    if !device.is_alive() {
        return;
    }
    let Ok(client) = handle.get_client(device.id()) else {
        return;
    };
    let Ok(offer) = client.create_resource::<WlDataOffer, _, CompositorState>(
        handle,
        device.version().min(3),
        DataOfferData {
            source,
            mime_types: mime_types.clone(),
        },
    ) else {
        return;
    };
    device.data_offer(&offer);
    for mime_type in mime_types {
        offer.offer(mime_type);
    }
    device.selection(Some(&offer));
    state.data_offer_count = state.data_offer_count.saturating_add(1);
    state.data_offers.push(offer);
}

fn publish_wayland_selection(
    state: &mut CompositorState,
    handle: &DisplayHandle,
    source: Option<&WlDataSource>,
) {
    let Some(source) = source.filter(|source| source.is_alive()) else {
        for device in state.data_devices.iter().filter(|device| device.is_alive()) {
            device.selection(None);
        }
        return;
    };
    let Some(source_data) = source.data::<DataSourceData>() else {
        return;
    };
    let mime_types = source_data
        .mime_types
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .clone();
    let devices = state.data_devices.clone();
    for device in &devices {
        publish_offer_to_device(
            state,
            handle,
            device,
            ClipboardOfferSource::Wayland(source.clone()),
            mime_types.clone(),
        );
    }
}

fn publish_android_selection(state: &mut CompositorState, handle: &DisplayHandle) {
    let mime_types = TEXT_MIME_TYPES
        .iter()
        .map(|mime_type| (*mime_type).to_owned())
        .collect::<Vec<_>>();
    let devices = state.data_devices.clone();
    for device in &devices {
        publish_offer_to_device(
            state,
            handle,
            device,
            ClipboardOfferSource::AndroidClipboard,
            mime_types.clone(),
        );
    }
}

fn source_text_mime(source: &WlDataSource) -> Option<String> {
    let source_data = source.data::<DataSourceData>()?;
    let mime_types = source_data
        .mime_types
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    TEXT_MIME_TYPES
        .iter()
        .find(|candidate| mime_types.iter().any(|offered| offered == **candidate))
        .map(|mime_type| (*mime_type).to_owned())
}

fn queue_linux_copy(state: &mut CompositorState, source: &WlDataSource) {
    if !state.clipboard_active || !source.is_alive() {
        return;
    }
    let Some(mime_type) = source_text_mime(source) else {
        return;
    };
    let Ok((read_end, write_end)) = create_cloexec_pipe() else {
        return;
    };
    source.send(mime_type, write_end.as_fd());
    state.pending_linux_copy_fds.push_back(read_end);
}

fn source_drag_mime(source: &WlDataSource) -> Option<String> {
    let source_data = source.data::<DataSourceData>()?;
    let mime_types = source_data
        .mime_types
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    if mime_types
        .iter()
        .any(|offered| offered == URI_LIST_MIME_TYPE)
    {
        return Some(URI_LIST_MIME_TYPE.to_owned());
    }
    TEXT_MIME_TYPES
        .iter()
        .find(|candidate| mime_types.iter().any(|offered| offered == **candidate))
        .map(|mime_type| (*mime_type).to_owned())
}

fn queue_linux_drag(state: &mut CompositorState, source: &WlDataSource) -> bool {
    let Some(mime_type) = source_drag_mime(source) else {
        return false;
    };
    let Ok((read_end, write_end)) = create_cloexec_pipe() else {
        return false;
    };
    source.target(Some(mime_type.clone()));
    if source.version() >= 3 {
        source.action(wl_data_device_manager::DndAction::Copy.into());
    }
    source.send(mime_type.clone(), write_end.as_fd());
    state.pending_linux_drag_fds.push_back(read_end);
    state.pending_linux_drag_mime_types.push_back(mime_type);
    state.linux_drag_source = Some(source.clone());
    true
}
impl GlobalDispatch<ZwpPointerGesturesV1, ()> for CompositorState {
    fn bind(
        _state: &mut Self,
        _handle: &DisplayHandle,
        _client: &Client,
        resource: New<ZwpPointerGesturesV1>,
        _global_data: &(),
        data_init: &mut DataInit<'_, Self>,
    ) {
        data_init.init(resource, ());
    }
}

impl Dispatch<ZwpPointerGesturesV1, ()> for CompositorState {
    fn request(
        state: &mut Self,
        _client: &Client,
        _resource: &ZwpPointerGesturesV1,
        request: zwp_pointer_gestures_v1::Request,
        _data: &(),
        _handle: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            zwp_pointer_gestures_v1::Request::GetSwipeGesture { id, pointer } => {
                let gesture = data_init.init(id, PointerGestureData { pointer });
                state.swipe_gestures.push(gesture);
            }
            zwp_pointer_gestures_v1::Request::GetPinchGesture { id, pointer } => {
                let gesture = data_init.init(id, PointerGestureData { pointer });
                state.pinch_gestures.push(gesture);
            }
            zwp_pointer_gestures_v1::Request::GetHoldGesture { id, pointer } => {
                let gesture = data_init.init(id, PointerGestureData { pointer });
                state.hold_gestures.push(gesture);
            }
            zwp_pointer_gestures_v1::Request::Release => {}
            _ => unreachable!("pointer-gestures request added without an implementation"),
        }
    }
}

impl Dispatch<ZwpPointerGestureSwipeV1, PointerGestureData> for CompositorState {
    fn request(
        _state: &mut Self,
        _client: &Client,
        _resource: &ZwpPointerGestureSwipeV1,
        request: zwp_pointer_gesture_swipe_v1::Request,
        _data: &PointerGestureData,
        _handle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            zwp_pointer_gesture_swipe_v1::Request::Destroy => {}
            _ => unreachable!("swipe gesture request added without an implementation"),
        }
    }

    fn destroyed(
        state: &mut Self,
        _client: ClientId,
        resource: &ZwpPointerGestureSwipeV1,
        _data: &PointerGestureData,
    ) {
        state
            .swipe_gestures
            .retain(|gesture| gesture.id() != resource.id());
    }
}

impl Dispatch<ZwpPointerGesturePinchV1, PointerGestureData> for CompositorState {
    fn request(
        _state: &mut Self,
        _client: &Client,
        _resource: &ZwpPointerGesturePinchV1,
        request: zwp_pointer_gesture_pinch_v1::Request,
        _data: &PointerGestureData,
        _handle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            zwp_pointer_gesture_pinch_v1::Request::Destroy => {}
            _ => unreachable!("pinch gesture request added without an implementation"),
        }
    }

    fn destroyed(
        state: &mut Self,
        _client: ClientId,
        resource: &ZwpPointerGesturePinchV1,
        _data: &PointerGestureData,
    ) {
        state
            .pinch_gestures
            .retain(|gesture| gesture.id() != resource.id());
    }
}

impl Dispatch<ZwpPointerGestureHoldV1, PointerGestureData> for CompositorState {
    fn request(
        _state: &mut Self,
        _client: &Client,
        _resource: &ZwpPointerGestureHoldV1,
        request: zwp_pointer_gesture_hold_v1::Request,
        _data: &PointerGestureData,
        _handle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            zwp_pointer_gesture_hold_v1::Request::Destroy => {}
            _ => unreachable!("hold gesture request added without an implementation"),
        }
    }

    fn destroyed(
        state: &mut Self,
        _client: ClientId,
        resource: &ZwpPointerGestureHoldV1,
        _data: &PointerGestureData,
    ) {
        state
            .hold_gestures
            .retain(|gesture| gesture.id() != resource.id());
    }
}
impl GlobalDispatch<WpViewporter, ()> for CompositorState {
    fn bind(
        _state: &mut Self,
        _handle: &DisplayHandle,
        _client: &Client,
        resource: New<WpViewporter>,
        _global_data: &(),
        data_init: &mut DataInit<'_, Self>,
    ) {
        data_init.init(resource, ());
    }
}

impl Dispatch<WpViewporter, ()> for CompositorState {
    fn request(
        _state: &mut Self,
        _client: &Client,
        resource: &WpViewporter,
        request: wp_viewporter::Request,
        _data: &(),
        _handle: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wp_viewporter::Request::Destroy => {}
            wp_viewporter::Request::GetViewport { id, surface } => {
                let Some(surface_data) = surface.data::<SurfaceData>() else {
                    return;
                };
                let mut state = surface_data
                    .inner
                    .lock()
                    .unwrap_or_else(|error| error.into_inner());
                if state.viewport.as_ref().is_some_and(Resource::is_alive) {
                    resource.post_error(
                        wp_viewporter::Error::ViewportExists,
                        "wl_surface already has a wp_viewport",
                    );
                    return;
                }
                let viewport = data_init.init(
                    id,
                    ViewportData {
                        surface: surface.clone(),
                    },
                );
                state.viewport = Some(viewport);
            }
            _ => unreachable!("wp_viewporter request added without an implementation"),
        }
    }
}

impl Dispatch<WpViewport, ViewportData> for CompositorState {
    fn request(
        _state: &mut Self,
        _client: &Client,
        resource: &WpViewport,
        request: wp_viewport::Request,
        data: &ViewportData,
        _handle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        let Some(surface_data) = data.surface.data::<SurfaceData>() else {
            resource.post_error(wp_viewport::Error::NoSurface, "wl_surface no longer exists");
            return;
        };
        let mut surface = surface_data
            .inner
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        match request {
            wp_viewport::Request::Destroy => {
                surface.viewport = None;
                surface.pending_viewport_source = Some(None);
                surface.pending_viewport_destination = Some(None);
            }
            wp_viewport::Request::SetSource {
                x,
                y,
                width,
                height,
            } => {
                let unset = x == -1.0 && y == -1.0 && width == -1.0 && height == -1.0;
                if unset {
                    surface.pending_viewport_source = Some(None);
                } else if x.is_finite()
                    && y.is_finite()
                    && width.is_finite()
                    && height.is_finite()
                    && x >= 0.0
                    && y >= 0.0
                    && width > 0.0
                    && height > 0.0
                {
                    surface.pending_viewport_source = Some(Some(ViewportSource {
                        x,
                        y,
                        width,
                        height,
                    }));
                } else {
                    resource.post_error(
                        wp_viewport::Error::BadValue,
                        "viewport source must be unset or finite and positive",
                    );
                }
            }
            wp_viewport::Request::SetDestination { width, height } => {
                if width == -1 && height == -1 {
                    surface.pending_viewport_destination = Some(None);
                } else if width > 0 && height > 0 {
                    surface.pending_viewport_destination = Some(Some((width, height)));
                } else {
                    resource.post_error(
                        wp_viewport::Error::BadValue,
                        "viewport destination must be unset or positive",
                    );
                }
            }
            _ => unreachable!("wp_viewport request added without an implementation"),
        }
    }

    fn destroyed(_state: &mut Self, _client: ClientId, resource: &WpViewport, data: &ViewportData) {
        if let Some(surface_data) = data.surface.data::<SurfaceData>() {
            let mut surface = surface_data
                .inner
                .lock()
                .unwrap_or_else(|error| error.into_inner());
            if surface
                .viewport
                .as_ref()
                .is_some_and(|viewport| viewport.id() == resource.id())
            {
                surface.viewport = None;
            }
        }
    }
}

impl GlobalDispatch<WpFractionalScaleManagerV1, ()> for CompositorState {
    fn bind(
        _state: &mut Self,
        _handle: &DisplayHandle,
        _client: &Client,
        resource: New<WpFractionalScaleManagerV1>,
        _global_data: &(),
        data_init: &mut DataInit<'_, Self>,
    ) {
        data_init.init(resource, ());
    }
}

impl Dispatch<WpFractionalScaleManagerV1, ()> for CompositorState {
    fn request(
        state: &mut Self,
        _client: &Client,
        resource: &WpFractionalScaleManagerV1,
        request: wp_fractional_scale_manager_v1::Request,
        _data: &(),
        _handle: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wp_fractional_scale_manager_v1::Request::Destroy => {}
            wp_fractional_scale_manager_v1::Request::GetFractionalScale { id, surface } => {
                let Some(surface_data) = surface.data::<SurfaceData>() else {
                    return;
                };
                let mut surface_state = surface_data
                    .inner
                    .lock()
                    .unwrap_or_else(|error| error.into_inner());
                if surface_state
                    .fractional_scale
                    .as_ref()
                    .is_some_and(Resource::is_alive)
                {
                    resource.post_error(
                        wp_fractional_scale_manager_v1::Error::FractionalScaleExists,
                        "wl_surface already has fractional-scale feedback",
                    );
                    return;
                }
                let fractional_scale = data_init.init(
                    id,
                    FractionalScaleData {
                        surface: surface.clone(),
                    },
                );
                fractional_scale.preferred_scale(state.output_fractional_scale.max(1));
                surface_state.fractional_scale = Some(fractional_scale.clone());
                state.fractional_scales.push(fractional_scale);
            }
            _ => unreachable!("fractional-scale manager request added without an implementation"),
        }
    }
}

impl Dispatch<WpFractionalScaleV1, FractionalScaleData> for CompositorState {
    fn request(
        _state: &mut Self,
        _client: &Client,
        _resource: &WpFractionalScaleV1,
        request: wp_fractional_scale_v1::Request,
        _data: &FractionalScaleData,
        _handle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wp_fractional_scale_v1::Request::Destroy => {}
            _ => unreachable!("wp_fractional_scale_v1 request added without an implementation"),
        }
    }

    fn destroyed(
        state: &mut Self,
        _client: ClientId,
        resource: &WpFractionalScaleV1,
        data: &FractionalScaleData,
    ) {
        state
            .fractional_scales
            .retain(|scale| scale.id() != resource.id());
        if let Some(surface_data) = data.surface.data::<SurfaceData>() {
            let mut surface = surface_data
                .inner
                .lock()
                .unwrap_or_else(|error| error.into_inner());
            if surface
                .fractional_scale
                .as_ref()
                .is_some_and(|scale| scale.id() == resource.id())
            {
                surface.fractional_scale = None;
            }
        }
    }
}
impl GlobalDispatch<ZwpTextInputManagerV3, ()> for CompositorState {
    fn bind(
        state: &mut Self,
        _handle: &DisplayHandle,
        _client: &Client,
        resource: New<ZwpTextInputManagerV3>,
        _global_data: &(),
        data_init: &mut DataInit<'_, Self>,
    ) {
        data_init.init(resource, ());
        state.text_input_manager_binds = state.text_input_manager_binds.saturating_add(1);
    }
}

impl Dispatch<ZwpTextInputManagerV3, ()> for CompositorState {
    fn request(
        state: &mut Self,
        _client: &Client,
        _resource: &ZwpTextInputManagerV3,
        request: zwp_text_input_manager_v3::Request,
        _data: &(),
        _handle: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            zwp_text_input_manager_v3::Request::GetTextInput { id, seat } => {
                let focused_surface = state
                    .keyboard_focus_surface
                    .as_ref()
                    .filter(|surface| surface.id().same_client_as(&seat.id()))
                    .cloned();
                let text_input = data_init.init(
                    id,
                    TextInputData {
                        seat,
                        state: Mutex::new(TextInputState {
                            focused_surface: focused_surface.clone(),
                            ..TextInputState::default()
                        }),
                    },
                );
                if let Some(surface) = focused_surface {
                    text_input.enter(&surface);
                }
                state.text_input_count = state.text_input_count.saturating_add(1);
                state.text_inputs.push(text_input);
            }
            zwp_text_input_manager_v3::Request::Destroy => {}
            _ => unreachable!("text-input manager request added without an implementation"),
        }
    }
}

impl Dispatch<ZwpTextInputV3, TextInputData> for CompositorState {
    fn request(
        state: &mut Self,
        _client: &Client,
        resource: &ZwpTextInputV3,
        request: zwp_text_input_v3::Request,
        data: &TextInputData,
        _handle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            zwp_text_input_v3::Request::Destroy => {}
            zwp_text_input_v3::Request::Enable => {
                let mut text_state = data.state.lock().unwrap_or_else(|error| error.into_inner());
                text_state.pending_enabled = Some(true);
                text_state.pending_surrounding_text = None;
                text_state.pending_content_type = None;
                text_state.pending_cursor_rectangle = None;
            }
            zwp_text_input_v3::Request::Disable => {
                data.state
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                    .pending_enabled = Some(false);
            }
            zwp_text_input_v3::Request::SetSurroundingText {
                text,
                cursor,
                anchor,
            } => {
                let length = i32::try_from(text.len()).unwrap_or(i32::MAX);
                if text.len() <= 4_000
                    && cursor >= 0
                    && anchor >= 0
                    && cursor <= length
                    && anchor <= length
                    && text.is_char_boundary(cursor as usize)
                    && text.is_char_boundary(anchor as usize)
                {
                    data.state
                        .lock()
                        .unwrap_or_else(|error| error.into_inner())
                        .pending_surrounding_text = Some(SurroundingText {
                        text,
                        cursor,
                        anchor,
                    });
                }
            }
            zwp_text_input_v3::Request::SetTextChangeCause { .. } => {}
            zwp_text_input_v3::Request::SetContentType { hint, purpose } => {
                let hint = match hint {
                    WEnum::Value(hint) => hint.bits(),
                    WEnum::Unknown(hint) => hint,
                };
                let purpose = match purpose {
                    WEnum::Value(purpose) => purpose as u32,
                    WEnum::Unknown(purpose) => purpose,
                };
                data.state
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                    .pending_content_type = Some((hint, purpose));
            }
            zwp_text_input_v3::Request::SetCursorRectangle {
                x,
                y,
                width,
                height,
            } => {
                if width >= 0 && height >= 0 {
                    data.state
                        .lock()
                        .unwrap_or_else(|error| error.into_inner())
                        .pending_cursor_rectangle = Some((x, y, width, height));
                }
            }
            zwp_text_input_v3::Request::Commit => {
                let another_enabled = state.text_inputs.iter().any(|text_input| {
                    text_input.id() != resource.id()
                        && text_input.is_alive()
                        && text_input
                            .data::<TextInputData>()
                            .is_some_and(|other_data| {
                                other_data.seat.id() == data.seat.id()
                                    && other_data
                                        .state
                                        .lock()
                                        .unwrap_or_else(|error| error.into_inner())
                                        .enabled
                            })
                });
                let mut text_state = data.state.lock().unwrap_or_else(|error| error.into_inner());
                text_state.commit_count = text_state.commit_count.wrapping_add(1);
                if text_state.focused_surface.is_none() {
                    return;
                }
                let was_enabled = text_state.enabled;
                if let Some(enabled) = text_state.pending_enabled.take() {
                    text_state.enabled = enabled && !another_enabled;
                    text_state.surrounding_text = None;
                    text_state.content_type = (0, 0);
                    text_state.cursor_rectangle = None;
                }
                if let Some(surrounding_text) = text_state.pending_surrounding_text.take() {
                    text_state.surrounding_text = Some(surrounding_text);
                }
                if let Some(content_type) = text_state.pending_content_type.take() {
                    text_state.content_type = content_type;
                }
                if let Some(cursor_rectangle) = text_state.pending_cursor_rectangle.take() {
                    text_state.cursor_rectangle = Some(cursor_rectangle);
                }
                if text_state.enabled && !was_enabled {
                    state.ime_active = true;
                    state.ime_show_requests = state.ime_show_requests.saturating_add(1);
                } else if !text_state.enabled && was_enabled {
                    state.ime_active = false;
                    state.ime_hide_requests = state.ime_hide_requests.saturating_add(1);
                }
            }
            _ => {}
        }
    }

    fn destroyed(
        state: &mut Self,
        _client: ClientId,
        resource: &ZwpTextInputV3,
        data: &TextInputData,
    ) {
        let was_enabled = data
            .state
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .enabled;
        state
            .text_inputs
            .retain(|text_input| text_input.id() != resource.id());
        state.text_input_count = state.text_input_count.saturating_sub(1);
        if was_enabled {
            state.ime_active = state.text_inputs.iter().any(|text_input| {
                text_input.data::<TextInputData>().is_some_and(|data| {
                    data.state
                        .lock()
                        .unwrap_or_else(|error| error.into_inner())
                        .enabled
                })
            });
            state.ime_hide_requests = state.ime_hide_requests.saturating_add(1);
        }
    }
}
impl GlobalDispatch<WlDataDeviceManager, ()> for CompositorState {
    fn bind(
        state: &mut Self,
        _handle: &DisplayHandle,
        _client: &Client,
        resource: New<WlDataDeviceManager>,
        _global_data: &(),
        data_init: &mut DataInit<'_, Self>,
    ) {
        data_init.init(resource, ());
        state.data_device_manager_binds = state.data_device_manager_binds.saturating_add(1);
    }
}

impl Dispatch<WlDataDeviceManager, ()> for CompositorState {
    fn request(
        state: &mut Self,
        _client: &Client,
        _resource: &WlDataDeviceManager,
        request: wl_data_device_manager::Request,
        _data: &(),
        handle: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_data_device_manager::Request::CreateDataSource { id } => {
                let source = data_init.init(id, DataSourceData::default());
                state.data_source_count = state.data_source_count.saturating_add(1);
                state.data_sources.push(source);
            }
            wl_data_device_manager::Request::GetDataDevice { id, seat } => {
                let device = data_init.init(id, DataDeviceData { seat });
                state.data_device_count = state.data_device_count.saturating_add(1);
                state.data_devices.push(device.clone());
                if state.clipboard_active && state.android_clipboard_offered {
                    publish_offer_to_device(
                        state,
                        handle,
                        &device,
                        ClipboardOfferSource::AndroidClipboard,
                        TEXT_MIME_TYPES
                            .iter()
                            .map(|mime_type| (*mime_type).to_owned())
                            .collect(),
                    );
                }
            }
            wl_data_device_manager::Request::Release => {}
            _ => unreachable!("wl_data_device_manager request added without an implementation"),
        }
    }
}

impl Dispatch<WlDataSource, DataSourceData> for CompositorState {
    fn request(
        _state: &mut Self,
        _client: &Client,
        _resource: &WlDataSource,
        request: wl_data_source::Request,
        data: &DataSourceData,
        _handle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_data_source::Request::Offer { mime_type } => {
                let mut mime_types = data
                    .mime_types
                    .lock()
                    .unwrap_or_else(|error| error.into_inner());
                if !mime_types.contains(&mime_type) {
                    mime_types.push(mime_type);
                }
            }
            wl_data_source::Request::Destroy | wl_data_source::Request::SetActions { .. } => {}
            _ => unreachable!("wl_data_source request added without an implementation"),
        }
    }

    fn destroyed(
        state: &mut Self,
        _client: ClientId,
        resource: &WlDataSource,
        _data: &DataSourceData,
    ) {
        let was_selection = state
            .selection_source
            .as_ref()
            .is_some_and(|source| source.id() == resource.id());
        let was_drag = state
            .linux_drag_source
            .as_ref()
            .is_some_and(|source| source.id() == resource.id());
        state
            .data_sources
            .retain(|source| source.id() != resource.id());
        state.data_source_count = state.data_source_count.saturating_sub(1);
        if was_selection {
            state.selection_source = None;
            for device in state.data_devices.iter().filter(|device| device.is_alive()) {
                device.selection(None);
            }
        }
        if was_drag {
            state.linux_drag_source = None;
            state.pending_linux_drag_fds.clear();
            state.pending_linux_drag_mime_types.clear();
        }
    }
}

impl Dispatch<WlDataDevice, DataDeviceData> for CompositorState {
    fn request(
        state: &mut Self,
        _client: &Client,
        resource: &WlDataDevice,
        request: wl_data_device::Request,
        data: &DataDeviceData,
        handle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_data_device::Request::SetSelection { source, serial } => {
                let valid_serial = state.selection_serials.iter().any(|candidate| {
                    candidate.serial == serial
                        && candidate.surface.id().same_client_as(&resource.id())
                });
                if serial == 0 || !data.seat.id().same_client_as(&resource.id()) || !valid_serial {
                    return;
                }
                if let Some(source) = source.as_ref() {
                    let Some(source_data) = source.data::<DataSourceData>() else {
                        return;
                    };
                    if source_data.used.swap(true, Ordering::AcqRel) {
                        resource.post_error(
                            wl_data_device::Error::UsedSource,
                            "wl_data_source was already used",
                        );
                        return;
                    }
                }
                if let Some(previous) = state.selection_source.take() {
                    if source
                        .as_ref()
                        .is_none_or(|source| source.id() != previous.id())
                        && previous.is_alive()
                    {
                        previous.cancelled();
                    }
                }
                state.android_clipboard_offered = false;
                state.selection_source = source.clone();
                if let Some(source) = source.as_ref() {
                    queue_linux_copy(state, source);
                }
                publish_wayland_selection(state, handle, source.as_ref());
            }
            wl_data_device::Request::StartDrag {
                source,
                origin,
                icon: _,
                serial,
            } => {
                let valid_serial = state.selection_serials.iter().any(|candidate| {
                    candidate.serial == serial
                        && candidate.surface.id() == origin.id()
                        && candidate.surface.id().same_client_as(&resource.id())
                });
                if !data.seat.id().same_client_as(&resource.id()) || !valid_serial {
                    return;
                }
                let Some(source) = source else {
                    return;
                };
                let Some(source_data) = source.data::<DataSourceData>() else {
                    return;
                };
                if source_data.used.swap(true, Ordering::AcqRel) {
                    resource.post_error(
                        wl_data_device::Error::UsedSource,
                        "wl_data_source was already used",
                    );
                    return;
                }
                if let Some(previous) = state.linux_drag_source.take() {
                    if previous.is_alive() {
                        previous.cancelled();
                    }
                }
                state.pending_linux_drag_fds.clear();
                state.pending_linux_drag_mime_types.clear();
                if !queue_linux_drag(state, &source) {
                    source.cancelled();
                }
            }
            wl_data_device::Request::Release => {}
            _ => unreachable!("wl_data_device request added without an implementation"),
        }
    }

    fn destroyed(
        state: &mut Self,
        _client: ClientId,
        resource: &WlDataDevice,
        _data: &DataDeviceData,
    ) {
        state
            .data_devices
            .retain(|device| device.id() != resource.id());
        state.data_device_count = state.data_device_count.saturating_sub(1);
    }
}

impl Dispatch<WlDataOffer, DataOfferData> for CompositorState {
    fn request(
        state: &mut Self,
        _client: &Client,
        _resource: &WlDataOffer,
        request: wl_data_offer::Request,
        data: &DataOfferData,
        _handle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_data_offer::Request::Receive { mime_type, fd } => {
                if !data.mime_types.contains(&mime_type) {
                    return;
                }
                match &data.source {
                    ClipboardOfferSource::Wayland(source) if source.is_alive() => {
                        source.send(mime_type, fd.as_fd());
                    }
                    ClipboardOfferSource::AndroidClipboard if state.clipboard_active => {
                        state.pending_android_paste_fds.push_back(File::from(fd));
                    }
                    ClipboardOfferSource::AndroidDrag(payloads) => {
                        if let Some(bytes) = payloads
                            .lock()
                            .unwrap_or_else(|error| error.into_inner())
                            .get(&mime_type)
                        {
                            let mut output = File::from(fd);
                            let _ = output.write_all(bytes);
                        }
                    }
                    ClipboardOfferSource::Wayland(_) | ClipboardOfferSource::AndroidClipboard => {}
                }
            }
            wl_data_offer::Request::Finish => {
                if state
                    .android_drag
                    .as_ref()
                    .is_some_and(|drag| drag.offer.id() == _resource.id())
                {
                    state.android_drag = None;
                }
            }
            wl_data_offer::Request::Destroy
            | wl_data_offer::Request::Accept { .. }
            | wl_data_offer::Request::SetActions { .. } => {}
            _ => unreachable!("wl_data_offer request added without an implementation"),
        }
    }

    fn destroyed(
        state: &mut Self,
        _client: ClientId,
        resource: &WlDataOffer,
        _data: &DataOfferData,
    ) {
        if state
            .android_drag
            .as_ref()
            .is_some_and(|drag| drag.offer.id() == resource.id())
        {
            state.android_drag = None;
        }
        state
            .data_offers
            .retain(|offer| offer.id() != resource.id());
        state.data_offer_count = state.data_offer_count.saturating_sub(1);
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
            if surface.version() >= 6 {
                surface.preferred_buffer_scale(state.output_scale);
                surface.preferred_buffer_transform(wl_output::Transform::Normal);
            }
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
        seat.capabilities(
            wl_seat::Capability::Pointer
                | wl_seat::Capability::Keyboard
                | wl_seat::Capability::Touch,
        );
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
            wl_seat::Request::GetTouch { id } => {
                let touch = data_init.init(id, ());
                state.touches.push(touch);
            }
            wl_seat::Request::Release => {}
            _ => unreachable!("wl_seat request added without an implementation"),
        }
    }
}

impl Dispatch<WlTouch, ()> for CompositorState {
    fn request(
        _state: &mut Self,
        _client: &Client,
        _resource: &WlTouch,
        request: wl_touch::Request,
        _data: &(),
        _handle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_touch::Request::Release => {}
            _ => unreachable!("wl_touch request added without an implementation"),
        }
    }

    fn destroyed(state: &mut Self, _client: ClientId, resource: &WlTouch, _data: &()) {
        state.touches.retain(|touch| touch.id() != resource.id());
    }
}
impl Dispatch<WlPointer, ()> for CompositorState {
    fn request(
        state: &mut Self,
        _client: &Client,
        resource: &WlPointer,
        request: wl_pointer::Request,
        _data: &(),
        _handle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_pointer::Request::SetCursor {
                serial,
                surface,
                hotspot_x,
                hotspot_y,
            } => {
                if serial != state.last_pointer_enter_serial {
                    return;
                }
                let client_has_focus = state.pointer_focus_surface.as_ref().is_some_and(|focus| {
                    surface.as_ref().map_or_else(
                        || resource.id().same_client_as(&focus.id()),
                        |cursor| cursor.id().same_client_as(&focus.id()),
                    )
                });
                let updates_current = surface.as_ref().is_some_and(|surface| {
                    state
                        .cursor_surface
                        .as_ref()
                        .is_some_and(|cursor| cursor.id() == surface.id())
                });
                if !client_has_focus && !updates_current {
                    return;
                }
                let Some(surface) = surface else {
                    state.cursor_surface = None;
                    state.cursor_frame = None;
                    state.cursor_hotspot_x = 0;
                    state.cursor_hotspot_y = 0;
                    return;
                };
                if !resource.id().same_client_as(&surface.id()) {
                    return;
                }
                let Some(surface_data) = surface.data::<SurfaceData>() else {
                    return;
                };
                let mut surface_state = surface_data
                    .inner
                    .lock()
                    .unwrap_or_else(|error| error.into_inner());
                match surface_state.role {
                    None | Some(SurfaceRole::Cursor) => {
                        surface_state.role = Some(SurfaceRole::Cursor);
                    }
                    Some(_) => {
                        resource.post_error(
                            wl_pointer::Error::Role,
                            "cursor wl_surface already has another role",
                        );
                        return;
                    }
                }
                state.cursor_frame = surface_state.committed_frame.clone();
                drop(surface_state);
                state.cursor_surface = Some(surface);
                state.cursor_hotspot_x = hotspot_x;
                state.cursor_hotspot_y = hotspot_y;
            }
            wl_pointer::Request::Release => {}
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
    damage_batches: &mut Vec<(WlSurface, Vec<RegionRectangle>)>,
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
    let (children, local_damage) = {
        let mut surface_state = surface_data
            .inner
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if let Some(input_region) = surface_state.cached_input_region.take() {
            surface_state.committed_input_region = input_region;
        }
        if let Some(scale) = surface_state.cached_buffer_scale.take() {
            surface_state.committed_buffer_scale = scale;
        }
        if let Some(transform) = surface_state.cached_buffer_transform.take() {
            surface_state.committed_buffer_transform = transform;
        }
        if let Some(source) = surface_state.cached_viewport_source.take() {
            surface_state.committed_viewport_source = source;
        }
        if let Some(destination) = surface_state.cached_viewport_destination.take() {
            surface_state.committed_viewport_destination = destination;
        }
        if let Some(frame) = surface_state.cached_frame.take() {
            surface_state.committed_frame = frame;
        }
        callbacks.append(&mut surface_state.cached_callbacks);
        let local_damage = std::mem::take(&mut surface_state.cached_damage);
        let children = surface_state
            .children_below
            .iter()
            .chain(surface_state.children_above.iter())
            .cloned()
            .collect::<Vec<_>>();
        (children, local_damage)
    };
    if !local_damage.is_empty() {
        damage_batches.push((surface.clone(), local_damage));
    }
    for child in children.iter().filter(|child| child.is_alive()) {
        apply_cached_subsurface_tree(
            state,
            child,
            depth.saturating_add(1),
            apply_parent_state,
            callbacks,
            damage_batches,
        );
    }
}

fn apply_cached_subsurface_children(
    state: &CompositorState,
    parent: &WlSurface,
) -> (Vec<WlCallback>, Vec<(WlSurface, Vec<RegionRectangle>)>) {
    let Some(parent_data) = parent.data::<SurfaceData>() else {
        return (Vec::new(), Vec::new());
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
    let mut damage_batches = Vec::new();
    for child in children.iter().filter(|child| child.is_alive()) {
        apply_cached_subsurface_tree(state, child, 0, true, &mut callbacks, &mut damage_batches);
    }
    (callbacks, damage_batches)
}

fn append_damage_batches(
    state: &mut CompositorState,
    damage_batches: Vec<(WlSurface, Vec<RegionRectangle>)>,
) {
    let output_width = state.output_width.max(0) as u32;
    let output_height = state.output_height.max(0) as u32;
    for (surface, damage) in damage_batches {
        let Some((origin_x, origin_y)) = surface_origin_in_root(state, &surface, 0) else {
            continue;
        };
        state
            .presentation_damage
            .extend(damage.into_iter().filter_map(|rectangle| {
                rectangle
                    .translated(origin_x, origin_y)
                    .clip(output_width, output_height)
            }));
    }
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
                    let mut damage_batches = Vec::new();
                    apply_cached_subsurface_tree(
                        state,
                        &data.surface,
                        0,
                        false,
                        &mut callbacks,
                        &mut damage_batches,
                    );
                    append_damage_batches(state, damage_batches);
                    update_composited_frame(state);
                    state.presentation_callbacks.extend(callbacks);
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
                let surface = data_init.init(id, SurfaceData::default());
                state.surfaces.push(surface);
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
            wl_surface::Request::Damage {
                x,
                y,
                width,
                height,
            } => {
                if let Some(damage) = RegionRectangle::new(x, y, width, height) {
                    data.inner
                        .lock()
                        .unwrap_or_else(|error| error.into_inner())
                        .pending_surface_damage
                        .push(damage);
                }
            }
            wl_surface::Request::DamageBuffer {
                x,
                y,
                width,
                height,
            } => {
                if let Some(damage) = RegionRectangle::new(x, y, width, height) {
                    data.inner
                        .lock()
                        .unwrap_or_else(|error| error.into_inner())
                        .pending_buffer_damage
                        .push(damage);
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
            wl_surface::Request::SetOpaqueRegion { .. } => {}
            wl_surface::Request::Offset { x, y } => {
                let mut surface = data.inner.lock().unwrap_or_else(|error| error.into_inner());
                surface.pending_offset.0 = surface.pending_offset.0.saturating_add(x);
                surface.pending_offset.1 = surface.pending_offset.1.saturating_add(y);
            }
            wl_surface::Request::SetBufferTransform { transform } => match transform {
                WEnum::Value(transform) => {
                    data.inner
                        .lock()
                        .unwrap_or_else(|error| error.into_inner())
                        .pending_buffer_transform = Some(transform.into());
                }
                WEnum::Unknown(_) => {
                    resource.post_error(
                        wl_surface::Error::InvalidTransform,
                        "unknown buffer transform",
                    );
                }
            },
            wl_surface::Request::SetBufferScale { scale } => {
                if scale < 1 {
                    resource.post_error(
                        wl_surface::Error::InvalidScale,
                        "buffer scale must be positive",
                    );
                } else {
                    data.inner
                        .lock()
                        .unwrap_or_else(|error| error.into_inner())
                        .pending_buffer_scale = Some(scale);
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
                            xdg_state.pending_configures.push_back(XdgConfigure {
                                serial,
                                popup_geometry: None,
                            });
                            let parented = toplevel.data::<XdgToplevelData>().is_some_and(|data| {
                                data.parent
                                    .lock()
                                    .unwrap_or_else(|error| error.into_inner())
                                    .is_some()
                            });
                            let maximize_primary = state.tile_toplevels
                                && state.primary_toplevel.is_none()
                                && !parented;
                            let width = if maximize_primary {
                                state.output_width
                            } else {
                                0
                            };
                            let height = if maximize_primary {
                                state.output_height
                            } else {
                                0
                            };
                            let states = if maximize_primary {
                                encode_xdg_toplevel_states(&[
                                    xdg_toplevel::State::Maximized,
                                    xdg_toplevel::State::Activated,
                                ])
                            } else {
                                Vec::new()
                            };
                            toplevel.configure(width, height, states);
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
                            state.next_configure_serial =
                                state.next_configure_serial.wrapping_add(1).max(1);
                            let serial = state.next_configure_serial;
                            xdg_state.pending_configures.push_back(XdgConfigure {
                                serial,
                                popup_geometry: Some(geometry),
                            });
                            popup.configure(x, y, width, height);
                            xdg_surface.configure(serial);
                        }
                    }
                }
                let input_region_update = surface.pending_input_region.take();
                let pending_offset = std::mem::take(&mut surface.pending_offset);
                let surface_damage = std::mem::take(&mut surface.pending_surface_damage);
                let buffer_damage = std::mem::take(&mut surface.pending_buffer_damage);
                let damage_declared = !surface_damage.is_empty() || !buffer_damage.is_empty();
                let buffer_scale_update = surface.pending_buffer_scale.take();
                let buffer_transform_update = surface.pending_buffer_transform.take();
                let viewport_source_update = surface.pending_viewport_source.take();
                let viewport_destination_update = surface.pending_viewport_destination.take();
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
                let role = surface.role;
                let xdg_surface = surface.xdg_surface.clone();
                let xdg_toplevel = surface.xdg_toplevel.clone();
                drop(surface);
                let parent_geometry_changed =
                    xdg_surface.as_ref().is_some_and(commit_xdg_surface_state);
                let synchronized = role == Some(SurfaceRole::Subsurface)
                    && subsurface_effectively_synchronized(resource, state.surface_count);

                let mut surface = data.inner.lock().unwrap_or_else(|error| error.into_inner());
                let committed_scale = surface.committed_buffer_scale.max(1);
                let base_scale = if synchronized {
                    surface.cached_buffer_scale.unwrap_or(committed_scale)
                } else {
                    committed_scale
                };
                let base_transform = if synchronized {
                    surface
                        .cached_buffer_transform
                        .unwrap_or(surface.committed_buffer_transform)
                } else {
                    surface.committed_buffer_transform
                };
                let base_frame = if synchronized {
                    surface
                        .cached_frame
                        .as_ref()
                        .cloned()
                        .unwrap_or_else(|| surface.committed_frame.clone())
                } else {
                    surface.committed_frame.clone()
                };
                let base_viewport_source = if synchronized {
                    surface
                        .cached_viewport_source
                        .unwrap_or(surface.committed_viewport_source)
                } else {
                    surface.committed_viewport_source
                };
                let base_viewport_destination = if synchronized {
                    surface
                        .cached_viewport_destination
                        .unwrap_or(surface.committed_viewport_destination)
                } else {
                    surface.committed_viewport_destination
                };
                let was_mapped = base_frame.is_some();
                let next_scale = buffer_scale_update.unwrap_or(base_scale);
                let next_transform = buffer_transform_update.unwrap_or(base_transform);
                let next_viewport_source = viewport_source_update.unwrap_or(base_viewport_source);
                let next_viewport_destination =
                    viewport_destination_update.unwrap_or(base_viewport_destination);
                let viewport_state_changed =
                    viewport_source_update.is_some() || viewport_destination_update.is_some();
                let buffer_state_changed = frame_update.is_some()
                    || buffer_scale_update.is_some()
                    || buffer_transform_update.is_some()
                    || viewport_state_changed;
                let next_frame = if buffer_state_changed {
                    let source = frame_update
                        .clone()
                        .unwrap_or_else(|| base_frame.as_ref().map(original_buffer_frame));
                    match source {
                        Some(source) => {
                            let transformed =
                                match transform_buffer_frame(source, next_transform, next_scale) {
                                    Ok(frame) => frame,
                                    Err(()) => {
                                        resource.post_error(
                                        wl_surface::Error::InvalidSize,
                                        "transformed buffer dimensions must be divisible by scale",
                                    );
                                        return;
                                    }
                                };
                            match apply_viewport_to_frame(
                                transformed,
                                next_viewport_source,
                                next_viewport_destination,
                            ) {
                                Ok(frame) => Some(frame),
                                Err(error) => {
                                    let Some(viewport) = surface
                                        .viewport
                                        .as_ref()
                                        .filter(|viewport| viewport.is_alive())
                                    else {
                                        return;
                                    };
                                    match error {
                                        ViewportApplyError::BadSize => viewport.post_error(
                                            wp_viewport::Error::BadSize,
                                            "viewport source size must be integral without a destination",
                                        ),
                                        ViewportApplyError::OutOfBuffer => viewport.post_error(
                                            wp_viewport::Error::OutOfBuffer,
                                            "viewport source extends outside the transformed buffer",
                                        ),
                                    }
                                    return;
                                }
                            }
                        }
                        None => None,
                    }
                } else {
                    base_frame
                };
                let viewport_active =
                    next_viewport_source.is_some() || next_viewport_destination.is_some();
                let force_full_damage = buffer_scale_update.is_some()
                    || buffer_transform_update.is_some()
                    || viewport_state_changed
                    || (viewport_active && !buffer_damage.is_empty())
                    || (frame_update.is_some() && !damage_declared);
                let local_damage = damage_for_commit(
                    surface_damage,
                    buffer_damage,
                    next_frame.as_ref(),
                    next_transform,
                    next_scale,
                    force_full_damage,
                );

                if synchronized {
                    if let Some(input_region) = input_region_update {
                        surface.cached_input_region = Some(input_region);
                    }
                    surface.cached_buffer_scale = Some(next_scale);
                    surface.cached_buffer_transform = Some(next_transform);
                    surface.cached_viewport_source = Some(next_viewport_source);
                    surface.cached_viewport_destination = Some(next_viewport_destination);
                    if buffer_state_changed {
                        surface.cached_frame = Some(next_frame);
                    }
                    surface.cached_damage.extend(local_damage);
                    surface.cached_callbacks.append(&mut callbacks);
                    state.surface_commit_count = state.surface_commit_count.saturating_add(1);
                    return;
                }

                if let Some(input_region) = input_region_update {
                    surface.committed_input_region = input_region;
                }
                surface.committed_buffer_scale = next_scale;
                surface.committed_buffer_transform = next_transform;
                surface.committed_viewport_source = next_viewport_source;
                surface.committed_viewport_destination = next_viewport_destination;
                if buffer_state_changed {
                    surface.committed_frame = next_frame;
                }
                let latest_frame = surface.committed_frame.clone();
                let is_xdg_toplevel = role == Some(SurfaceRole::XdgToplevel);
                let is_cursor = role == Some(SurfaceRole::Cursor);
                let publishes_root_frame = surface_publishes_root_frame(
                    role,
                    surface.has_xdg_surface,
                    state.primary_toplevel.is_some(),
                );
                let has_frame = latest_frame.is_some();
                drop(surface);

                state.surface_commit_count = state.surface_commit_count.saturating_add(1);
                let damage_origin = if publishes_root_frame {
                    Some(root_surface_origin(state))
                } else {
                    surface_origin_in_root(state, resource, 0)
                };
                if let Some((origin_x, origin_y)) = damage_origin {
                    let output_width = state.output_width.max(0) as u32;
                    let output_height = state.output_height.max(0) as u32;
                    state
                        .presentation_damage
                        .extend(local_damage.into_iter().filter_map(|damage| {
                            damage
                                .translated(origin_x, origin_y)
                                .clip(output_width, output_height)
                        }));
                }
                if publishes_root_frame && has_frame {
                    if is_xdg_toplevel && state.primary_toplevel.is_none() {
                        state.primary_toplevel = xdg_toplevel.clone();
                    }
                    if is_xdg_toplevel && (!was_mapped || state.active_toplevel.is_none()) {
                        state.active_toplevel = xdg_toplevel.clone();
                        state.window_change_serial =
                            state.window_change_serial.wrapping_add(1).max(1);
                    }
                    let active = !is_xdg_toplevel
                        || state.active_toplevel.as_ref().is_some_and(|active| {
                            xdg_toplevel
                                .as_ref()
                                .is_some_and(|current| active.id() == current.id())
                        });
                    if active {
                        state.root_surface = Some(resource.clone());
                        state.root_frame = latest_frame.clone();
                    }
                    if is_xdg_toplevel && !was_mapped {
                        let parented = xdg_toplevel.as_ref().is_some_and(|toplevel| {
                            toplevel.data::<XdgToplevelData>().is_some_and(|data| {
                                data.parent
                                    .lock()
                                    .unwrap_or_else(|error| error.into_inner())
                                    .is_some()
                            })
                        });
                        if let (Some(toplevel), Some(frame)) =
                            (xdg_toplevel.as_ref(), latest_frame.as_ref())
                        {
                            let (width, height) = surface_content_size(resource, frame);
                            if secondary_toplevel_needs_output_size(
                                parented,
                                state.tile_toplevels,
                                width,
                                height,
                                state.output_width,
                                state.output_height,
                            ) {
                                queue_toplevel_configure(
                                    state,
                                    toplevel,
                                    secondary_toplevel_canvas_width(state.output_width),
                                    state.output_height,
                                    &[xdg_toplevel::State::Activated],
                                );
                            }
                        }
                    }
                } else if publishes_root_frame
                    && state
                        .root_surface
                        .as_ref()
                        .is_some_and(|root| root.id() == resource.id())
                {
                    if state.primary_toplevel.as_ref().is_some_and(|primary| {
                        xdg_toplevel
                            .as_ref()
                            .is_some_and(|current| primary.id() == current.id())
                    }) {
                        state.primary_toplevel = None;
                    }
                    let replacement = state.toplevels.iter().rev().find_map(|toplevel| {
                        let surface = toplevel_surface(toplevel)?;
                        let frame = surface_frame(&surface)?;
                        Some((toplevel.clone(), surface, frame))
                    });
                    if let Some((toplevel, surface, frame)) = replacement {
                        configure_toplevel_activation(state, &toplevel, true);
                        state.active_toplevel = Some(toplevel);
                        state.root_surface = Some(surface.clone());
                        state.root_frame = Some(frame);
                        state.pointer_focus_surface = Some(surface.clone());
                        state.pointer_inside = false;
                        state.pointer_pressed = false;
                        set_keyboard_focus(state, Some(surface));
                    } else {
                        state.active_toplevel = None;
                        state.root_surface = None;
                        state.root_frame = None;
                        state.pointer_focus_surface = None;
                        state.pointer_inside = false;
                        state.pointer_pressed = false;
                        set_keyboard_focus(state, None);
                    }
                    state.window_change_serial = state.window_change_serial.wrapping_add(1).max(1);
                }
                if is_cursor
                    && state
                        .cursor_surface
                        .as_ref()
                        .is_some_and(|cursor| cursor.id() == resource.id())
                {
                    state.cursor_frame = latest_frame;
                    state.cursor_hotspot_x =
                        state.cursor_hotspot_x.saturating_sub(pending_offset.0);
                    state.cursor_hotspot_y =
                        state.cursor_hotspot_y.saturating_sub(pending_offset.1);
                }
                if is_xdg_toplevel {
                    if has_frame
                        && state.active_toplevel.as_ref().is_some_and(|active| {
                            xdg_toplevel
                                .as_ref()
                                .is_some_and(|current| active.id() == current.id())
                        })
                        && state.popup_grab.as_ref().is_none_or(|grab| !grab.active)
                    {
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
                    } else if !has_frame
                        && state
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
                let (child_callbacks, child_damage) =
                    apply_cached_subsurface_children(state, resource);
                callbacks.extend(child_callbacks);
                append_damage_batches(state, child_damage);
                if parent_geometry_changed {
                    state.window_change_serial = state.window_change_serial.wrapping_add(1).max(1);
                    reconfigure_reactive_popups(state, xdg_surface.as_ref());
                }
                update_composited_frame(state);
                state.presentation_callbacks.extend(callbacks);
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
        state
            .active_touches
            .retain(|touch| touch.surface.id() != resource.id());
        state
            .surfaces
            .retain(|surface| surface.id() != resource.id());
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

fn toplevel_surface(toplevel: &XdgToplevel) -> Option<WlSurface> {
    toplevel
        .data::<XdgToplevelData>()?
        .xdg_surface
        .data::<XdgSurfaceData>()
        .map(|data| data.wl_surface.clone())
}

fn surface_frame(surface: &WlSurface) -> Option<Arc<CommittedFrame>> {
    surface
        .data::<SurfaceData>()?
        .inner
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .committed_frame
        .clone()
}

fn window_geometry_for_surface(surface: &WlSurface) -> Option<WindowGeometry> {
    surface
        .data::<SurfaceData>()
        .and_then(|data| {
            data.inner
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .xdg_surface
                .clone()
        })
        .and_then(|xdg_surface| {
            xdg_surface.data::<XdgSurfaceData>().and_then(|data| {
                data.state
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                    .committed_window_geometry
            })
        })
}

fn root_window_geometry(state: &CompositorState) -> Option<WindowGeometry> {
    window_geometry_for_surface(state.root_surface.as_ref()?)
}

fn surface_publishes_root_frame(
    role: Option<SurfaceRole>,
    has_xdg_surface: bool,
    has_primary_toplevel: bool,
) -> bool {
    match role {
        Some(SurfaceRole::XdgToplevel) => true,
        None => !has_xdg_surface && !has_primary_toplevel,
        Some(SurfaceRole::XdgPopup | SurfaceRole::Subsurface | SurfaceRole::Cursor) => false,
    }
}

fn primary_surface(state: &CompositorState) -> Option<WlSurface> {
    state.primary_toplevel.as_ref().and_then(toplevel_surface)
}

fn managed_root_frame(state: &CompositorState) -> Option<(WlSurface, Arc<CommittedFrame>)> {
    state
        .active_toplevel
        .as_ref()
        .and_then(|toplevel| {
            let surface = toplevel_surface(toplevel)?;
            let frame = surface_frame(&surface)?;
            Some((surface, frame))
        })
        .or_else(|| {
            let surface = primary_surface(state)?;
            let frame = surface_frame(&surface)?;
            Some((surface, frame))
        })
}

fn synchronize_managed_root(state: &mut CompositorState) {
    let Some((surface, frame)) = managed_root_frame(state) else {
        return;
    };
    if state
        .root_surface
        .as_ref()
        .is_none_or(|root| root.id() != surface.id())
    {
        state.root_surface = Some(surface);
        state.root_frame = Some(frame);
    }
}

fn encode_xdg_toplevel_states(states: &[xdg_toplevel::State]) -> Vec<u8> {
    let mut encoded = Vec::with_capacity(states.len() * std::mem::size_of::<u32>());
    for state in states {
        encoded.extend_from_slice(&(*state as u32).to_ne_bytes());
    }
    encoded
}

fn secondary_toplevel_needs_output_size(
    parented: bool,
    tile_toplevels: bool,
    width: u32,
    _height: u32,
    output_width: i32,
    output_height: i32,
) -> bool {
    parented
        && tile_toplevels
        && output_width > 0
        && output_height > 0
        && width > output_width as u32
}

fn secondary_toplevel_canvas_width(output_width: i32) -> i32 {
    output_width.saturating_mul(4).saturating_div(3).max(1)
}

fn queue_toplevel_configure(
    state: &mut CompositorState,
    toplevel: &XdgToplevel,
    width: i32,
    height: i32,
    states: &[xdg_toplevel::State],
) -> u32 {
    if width <= 0 || height <= 0 {
        return 0;
    }
    let Some(surface) = toplevel_surface(toplevel) else {
        return 0;
    };
    let Some(xdg_surface) = surface.data::<SurfaceData>().and_then(|data| {
        data.inner
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .xdg_surface
            .clone()
    }) else {
        return 0;
    };
    let Some(xdg_data) = xdg_surface.data::<XdgSurfaceData>() else {
        return 0;
    };
    state.next_configure_serial = state.next_configure_serial.wrapping_add(1).max(1);
    let serial = state.next_configure_serial;
    xdg_data
        .state
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .pending_configures
        .push_back(XdgConfigure {
            serial,
            popup_geometry: None,
        });
    toplevel.configure(width, height, encode_xdg_toplevel_states(states));
    xdg_surface.configure(serial);
    serial
}

fn configure_toplevel_activation(
    state: &mut CompositorState,
    toplevel: &XdgToplevel,
    activated: bool,
) -> u32 {
    let Some(surface) = toplevel_surface(toplevel) else {
        return 0;
    };
    let Some(frame) = surface_frame(&surface) else {
        return 0;
    };
    let primary = state
        .primary_toplevel
        .as_ref()
        .is_some_and(|candidate| candidate.id() == toplevel.id());
    let (content_width, content_height) = surface_content_size(&surface, &frame);
    let (width, height) =
        if primary && state.tile_toplevels && state.output_width > 0 && state.output_height > 0 {
            (state.output_width, state.output_height)
        } else {
            (
                i32::try_from(content_width).unwrap_or(i32::MAX),
                i32::try_from(content_height).unwrap_or(i32::MAX),
            )
        };
    let mut states = Vec::with_capacity(2);
    if primary && state.tile_toplevels {
        states.push(xdg_toplevel::State::Maximized);
    }
    if activated {
        states.push(xdg_toplevel::State::Activated);
    }
    queue_toplevel_configure(state, toplevel, width, height, &states)
}
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct ToplevelLayout {
    output_width: u32,
    output_height: u32,
    root_x: i32,
    root_y: i32,
    root_width: i32,
    root_height: i32,
    overlay_primary: bool,
}

fn surface_content_size(surface: &WlSurface, frame: &CommittedFrame) -> (u32, u32) {
    window_geometry_for_surface(surface)
        .and_then(|geometry| {
            Some((
                u32::try_from(geometry.width).ok()?,
                u32::try_from(geometry.height).ok()?,
            ))
        })
        .filter(|(width, height)| *width > 0 && *height > 0)
        .unwrap_or((frame.width, frame.height))
}

fn calculate_toplevel_layout(
    output_width: i32,
    output_height: i32,
    root_width: u32,
    root_height: u32,
    root_frame_width: u32,
    root_frame_height: u32,
    root_geometry: WindowGeometry,
    primary_size: Option<(u32, u32)>,
) -> ToplevelLayout {
    let Some((primary_width, primary_height)) = primary_size else {
        let output_width = u32::try_from(output_width.max(1)).unwrap_or(1);
        let output_height = u32::try_from(output_height.max(1)).unwrap_or(1);
        let fallback_width = root_width.max(output_width / 2);
        let fallback_height = output_height.saturating_mul(fallback_width) / output_width;
        let compact = root_width <= fallback_width
            && root_height.saturating_mul(4) <= fallback_height.saturating_mul(3);
        return ToplevelLayout {
            output_width: if compact { fallback_width } else { root_width },
            output_height: if compact {
                fallback_height
            } else {
                root_height
            },
            root_x: if compact {
                ((fallback_width - root_width) / 2) as i32
            } else {
                0
            } - root_geometry.x,
            root_y: if compact {
                ((fallback_height - root_height) / 2) as i32
            } else {
                0
            } - root_geometry.y,
            root_width: root_frame_width as i32,
            root_height: root_frame_height as i32,
            overlay_primary: false,
        };
    };
    let frame_overflows_geometry = root_frame_width > root_width.saturating_add(64)
        || root_frame_height > root_height.saturating_add(64);
    if frame_overflows_geometry {
        let width_limited = u64::from(root_frame_width) * u64::from(primary_height)
            >= u64::from(root_frame_height) * u64::from(primary_width);
        let (numerator, denominator) = if width_limited {
            (primary_width, root_frame_width)
        } else {
            (primary_height, root_frame_height)
        };
        let scaled_width = ((u64::from(root_frame_width) * u64::from(numerator))
            / u64::from(denominator))
        .max(1) as u32;
        let scaled_height = ((u64::from(root_frame_height) * u64::from(numerator))
            / u64::from(denominator))
        .max(1) as u32;
        return ToplevelLayout {
            output_width: primary_width,
            output_height: primary_height,
            root_x: ((primary_width.saturating_sub(scaled_width)) / 2) as i32,
            root_y: ((primary_height.saturating_sub(scaled_height)) / 2) as i32,
            root_width: scaled_width as i32,
            root_height: scaled_height as i32,
            overlay_primary: true,
        };
    }
    let oversized = root_frame_width > primary_width || root_frame_height > primary_height;
    if oversized {
        let width_limited = u64::from(root_frame_width) * u64::from(primary_height)
            >= u64::from(root_frame_height) * u64::from(primary_width);
        let (numerator, denominator) = if width_limited {
            (primary_width, root_frame_width)
        } else {
            (primary_height, root_frame_height)
        };
        let scale_u32 = |value: u32| {
            ((u64::from(value) * u64::from(numerator)) / u64::from(denominator)).max(1) as u32
        };
        let frame_width = scale_u32(root_frame_width);
        let frame_height = scale_u32(root_frame_height);
        return ToplevelLayout {
            output_width: primary_width,
            output_height: primary_height,
            root_x: ((primary_width.saturating_sub(frame_width)) / 2) as i32,
            root_y: ((primary_height.saturating_sub(frame_height)) / 2) as i32,
            root_width: frame_width as i32,
            root_height: frame_height as i32,
            overlay_primary: true,
        };
    }
    ToplevelLayout {
        output_width: primary_width,
        output_height: primary_height,
        root_x: ((primary_width.saturating_sub(root_width)) / 2) as i32 - root_geometry.x,
        root_y: ((primary_height.saturating_sub(root_height)) / 2) as i32 - root_geometry.y,
        root_width: root_frame_width as i32,
        root_height: root_frame_height as i32,
        overlay_primary: true,
    }
}

fn toplevel_layout(state: &CompositorState) -> Option<ToplevelLayout> {
    let root_surface = state.root_surface.as_ref()?;
    let root_frame = state.root_frame.as_ref()?;
    let (root_width, root_height) = surface_content_size(root_surface, root_frame);
    let root_geometry = root_window_geometry(state).unwrap_or(WindowGeometry {
        x: 0,
        y: 0,
        width: root_width as i32,
        height: root_height as i32,
    });
    if !state.tile_toplevels {
        return Some(ToplevelLayout {
            output_width: root_width,
            output_height: root_height,
            root_x: root_geometry.x.saturating_neg(),
            root_y: root_geometry.y.saturating_neg(),
            root_width: root_frame.width as i32,
            root_height: root_frame.height as i32,
            overlay_primary: false,
        });
    }
    let primary_size = primary_surface(state)
        .filter(|primary| primary.id() != root_surface.id())
        .and_then(|primary| {
            let frame = surface_frame(&primary)?;
            Some(surface_content_size(&primary, &frame))
        });
    Some(calculate_toplevel_layout(
        state.output_width,
        state.output_height,
        root_width,
        root_height,
        root_frame.width,
        root_frame.height,
        root_geometry,
        primary_size,
    ))
}
fn root_surface_origin(state: &CompositorState) -> (i32, i32) {
    toplevel_layout(state).map_or((0, 0), |layout| (layout.root_x, layout.root_y))
}

fn scale_surface_coordinate(value: i32, target_extent: i32, source_extent: u32) -> i32 {
    if target_extent <= 0 || source_extent == 0 {
        value
    } else {
        ((i64::from(value) * i64::from(target_extent)) / i64::from(source_extent)) as i32
    }
}

fn root_content_origin(state: &CompositorState) -> (i32, i32) {
    root_content_layout(state).map_or(root_surface_origin(state), |layout| (layout.0, layout.1))
}

fn content_layout(
    layout: ToplevelLayout,
    frame_width: u32,
    frame_height: u32,
    geometry: WindowGeometry,
) -> (i32, i32, i32, i32) {
    (
        layout.root_x.saturating_add(scale_surface_coordinate(
            geometry.x,
            layout.root_width,
            frame_width,
        )),
        layout.root_y.saturating_add(scale_surface_coordinate(
            geometry.y,
            layout.root_height,
            frame_height,
        )),
        scale_surface_coordinate(geometry.width, layout.root_width, frame_width).max(1),
        scale_surface_coordinate(geometry.height, layout.root_height, frame_height).max(1),
    )
}

fn root_content_layout(state: &CompositorState) -> Option<(i32, i32, i32, i32)> {
    let layout = toplevel_layout(state)?;
    let frame = state.root_frame.as_ref()?;
    let geometry = root_window_geometry(state).unwrap_or(WindowGeometry {
        x: 0,
        y: 0,
        width: frame.width as i32,
        height: frame.height as i32,
    });
    Some(content_layout(layout, frame.width, frame.height, geometry))
}

fn root_input_dimensions(state: &CompositorState) -> (i32, i32) {
    toplevel_layout(state).map_or((state.output_width, state.output_height), |layout| {
        (layout.root_width, layout.root_height)
    })
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
        return Some(root_surface_origin(state));
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
        Some(SurfaceRole::XdgPopup) => {
            let xdg_surface = xdg_surface?;
            let (x, y) = xdg_surface_origin(state, &xdg_surface, 0)?;
            let ancestor = xdg_toplevel_ancestor_surface(&xdg_surface, 0)?;
            if state
                .root_surface
                .as_ref()
                .is_some_and(|root| root.id() == ancestor.id())
            {
                let (root_x, root_y) = root_content_origin(state);
                Some((x.saturating_add(root_x), y.saturating_add(root_y)))
            } else {
                Some((x, y))
            }
        }
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
        Some(SurfaceRole::Cursor) | None => None,
    }
}

fn scale_input_coordinate(value: f64, target_extent: i32, source_extent: u32) -> f64 {
    if target_extent <= 0 || source_extent == 0 {
        value
    } else {
        value * f64::from(source_extent) / f64::from(target_extent)
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
    let target_x = pointer_x - f64::from(origin_x);
    let target_y = pointer_y - f64::from(origin_y);
    let surface_data = surface.data::<SurfaceData>()?;
    let (
        local_x,
        local_y,
        source_width,
        source_height,
        accepts_parent,
        children_below,
        children_above,
    ) = {
        let surface = surface_data
            .inner
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        let (source_width, source_height) = surface
            .committed_frame
            .as_ref()
            .map_or((0, 0), |frame| (frame.width, frame.height));
        let local_x = scale_input_coordinate(target_x, width, source_width);
        let local_y = scale_input_coordinate(target_y, height, source_height);
        (
            local_x,
            local_y,
            source_width,
            source_height,
            surface.committed_frame.is_some()
                && surface_accepts_pointer(
                    &surface,
                    local_x,
                    local_y,
                    source_width as i32,
                    source_height as i32,
                ),
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
            source_width,
            source_height,
            width,
            height,
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
            source_width,
            source_height,
            width,
            height,
            pointer_x,
            pointer_y,
            depth.saturating_add(1),
        ) {
            return Some(target);
        }
    }
    None
}

#[allow(clippy::too_many_arguments)]
fn subsurface_tree_pointer_target(
    state: &CompositorState,
    surface: &WlSurface,
    parent_x: i32,
    parent_y: i32,
    parent_source_width: u32,
    parent_source_height: u32,
    parent_target_width: i32,
    parent_target_height: i32,
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
    let target_x = scale_surface_coordinate(x, parent_target_width, parent_source_width);
    let target_y = scale_surface_coordinate(y, parent_target_height, parent_source_height);
    let target_width =
        scale_surface_coordinate(dimensions.0, parent_target_width, parent_source_width);
    let target_height =
        scale_surface_coordinate(dimensions.1, parent_target_height, parent_source_height);
    surface_tree_pointer_target(
        state,
        surface,
        parent_x.saturating_add(target_x),
        parent_y.saturating_add(target_y),
        target_width,
        target_height,
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

fn commit_xdg_surface_state(xdg_surface: &XdgSurface) -> bool {
    let Some(xdg_data) = xdg_surface.data::<XdgSurfaceData>() else {
        return false;
    };
    let (window_geometry_changed, popup_geometry) = {
        let mut state = xdg_data
            .state
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        (
            state.commit_window_geometry(),
            state
                .acknowledged_configure
                .and_then(|configure| configure.popup_geometry),
        )
    };
    let popup_geometry_changed = popup_geometry.is_some_and(|geometry| {
        xdg_data
            .wl_surface
            .data::<SurfaceData>()
            .and_then(|surface_data| {
                surface_data
                    .inner
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                    .xdg_popup
                    .clone()
            })
            .is_some_and(|popup| {
                let Some(popup_data) = popup.data::<XdgPopupData>() else {
                    return false;
                };
                let mut applied = popup_data
                    .applied_geometry
                    .lock()
                    .unwrap_or_else(|error| error.into_inner());
                let changed = *applied != Some(geometry);
                *applied = Some(geometry);
                changed
            })
    });
    window_geometry_changed || popup_geometry_changed
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
    let ancestor = xdg_toplevel_ancestor_surface(&data.parent, 0)?;
    if state
        .root_surface
        .as_ref()
        .is_some_and(|root| root.id() == ancestor.id())
    {
        let layout = toplevel_layout(state)?;
        let root = state.root_frame.as_ref()?;
        let (content_x, content_y) = root_content_origin(state);
        let scale_bound = |value: i32, target: i32, source: u32, upper: bool| {
            if target <= 0 || source == 0 {
                return value;
            }
            let scaled = f64::from(value) * f64::from(source) / f64::from(target);
            if upper {
                scaled.ceil() as i32
            } else {
                scaled.floor() as i32
            }
        };
        let left = scale_bound(-content_x, layout.root_width, root.width, false);
        let top = scale_bound(-content_y, layout.root_height, root.height, false);
        let right = scale_bound(
            state.output_width.saturating_sub(content_x),
            layout.root_width,
            root.width,
            true,
        );
        let bottom = scale_bound(
            state.output_height.saturating_sub(content_y),
            layout.root_height,
            root.height,
            true,
        );
        return Some(PopupBounds {
            left: left.saturating_sub(parent_x),
            top: top.saturating_sub(parent_y),
            right: right.saturating_sub(parent_x),
            bottom: bottom.saturating_sub(parent_y),
        });
    }
    let frame = surface_frame(&ancestor)?;
    let (width, height) = surface_content_size(&ancestor, &frame);
    let width = i32::try_from(width).ok()?;
    let height = i32::try_from(height).ok()?;
    Some(PopupBounds {
        left: parent_x.saturating_neg(),
        top: parent_y.saturating_neg(),
        right: width.saturating_sub(parent_x),
        bottom: height.saturating_sub(parent_y),
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
        Some(SurfaceRole::Subsurface | SurfaceRole::Cursor) | None => None,
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

fn surface_frame_layout(
    surface: &WlSurface,
    content_x: i32,
    content_y: i32,
    content_width: i32,
    content_height: i32,
) -> Option<(i32, i32, i32, i32)> {
    let frame = surface_frame(surface)?;
    let geometry = window_geometry_for_surface(surface).unwrap_or(WindowGeometry {
        x: 0,
        y: 0,
        width: frame.width as i32,
        height: frame.height as i32,
    });
    let geometry_width = u32::try_from(geometry.width).ok()?.max(1);
    let geometry_height = u32::try_from(geometry.height).ok()?.max(1);
    let offset_x = scale_surface_coordinate(geometry.x, content_width, geometry_width);
    let offset_y = scale_surface_coordinate(geometry.y, content_height, geometry_height);
    let frame_width = scale_surface_coordinate(frame.width as i32, content_width, geometry_width);
    let frame_height =
        scale_surface_coordinate(frame.height as i32, content_height, geometry_height);
    Some((
        content_x.saturating_sub(offset_x),
        content_y.saturating_sub(offset_y),
        frame_width,
        frame_height,
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
    let target_width = configured_width.max(0) as u32;
    let target_height = configured_height.max(0) as u32;
    if source.width == 0 || source.height == 0 || target_width == 0 || target_height == 0 {
        return;
    }
    for target_y in 0..target_height {
        let destination_y = i64::from(y) + i64::from(target_y);
        if destination_y < 0 || destination_y >= i64::from(destination.height) {
            continue;
        }
        let source_y =
            ((u64::from(target_y) * u64::from(source.height)) / u64::from(target_height)) as u32;
        for target_x in 0..target_width {
            let destination_x = i64::from(x) + i64::from(target_x);
            if destination_x < 0 || destination_x >= i64::from(destination.width) {
                continue;
            }
            let source_x =
                ((u64::from(target_x) * u64::from(source.width)) / u64::from(target_width)) as u32;
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
    let (source_width, source_height) = frame
        .as_ref()
        .map_or((0, 0), |frame| (frame.width, frame.height));
    for child in children_below.iter().filter(|child| child.is_alive()) {
        blend_subsurface_tree(
            state,
            destination,
            child,
            x,
            y,
            source_width,
            source_height,
            configured_width,
            configured_height,
            depth.saturating_add(1),
        );
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
        blend_subsurface_tree(
            state,
            destination,
            child,
            x,
            y,
            source_width,
            source_height,
            configured_width,
            configured_height,
            depth.saturating_add(1),
        );
    }
}

#[allow(clippy::too_many_arguments)]
fn blend_subsurface_tree(
    state: &CompositorState,
    destination: &mut CommittedFrame,
    surface: &WlSurface,
    parent_x: i32,
    parent_y: i32,
    parent_source_width: u32,
    parent_source_height: u32,
    parent_target_width: i32,
    parent_target_height: i32,
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
    let target_x = scale_surface_coordinate(x, parent_target_width, parent_source_width);
    let target_y = scale_surface_coordinate(y, parent_target_height, parent_source_height);
    let target_width = scale_surface_coordinate(width, parent_target_width, parent_source_width);
    let target_height =
        scale_surface_coordinate(height, parent_target_height, parent_source_height);
    blend_surface_tree(
        state,
        destination,
        surface,
        parent_x.saturating_add(target_x),
        parent_y.saturating_add(target_y),
        target_width,
        target_height,
        depth,
    );
}
fn update_composited_frame(state: &mut CompositorState) {
    synchronize_managed_root(state);
    let Some(root) = state.root_frame.as_ref() else {
        state.last_frame = None;
        state.last_frame_width = 0;
        state.last_frame_height = 0;
        state.last_frame_checksum = 0;
        return;
    };
    let Some(layout) = toplevel_layout(state) else {
        state.last_frame = None;
        return;
    };
    let pixel_count = layout
        .output_width
        .checked_mul(layout.output_height)
        .and_then(|pixels| pixels.checked_mul(4))
        .and_then(|bytes| usize::try_from(bytes).ok())
        .unwrap_or(0);
    let active_popups = state.popups.iter().any(|popup| {
        popup.is_alive()
            && popup
                .data::<XdgPopupData>()
                .is_some_and(|data| !data.dismissed.load(Ordering::Acquire))
    });
    let popup_base = if active_popups {
        state.popup_base_frame.as_ref().filter(|frame| {
            frame.width == layout.output_width && frame.height == layout.output_height
        })
    } else {
        None
    };
    let mut composed = CommittedFrame {
        width: layout.output_width,
        height: layout.output_height,
        format: root.format,
        pixels: if let Some(base) = popup_base {
            base.pixels.clone()
        } else if !state.tile_toplevels
            && root.format != wl_shm::Format::Argb8888
            && pixel_count == root.pixels.len()
        {
            root.pixels.clone()
        } else {
            vec![0; pixel_count]
        },
        source: None,
    };
    if pixel_count == 0 {
        state.last_frame = None;
        return;
    }
    if popup_base.is_none() && layout.overlay_primary {
        if let Some(primary) = primary_surface(state) {
            if let Some(frame) = surface_frame(&primary) {
                let geometry = window_geometry_for_surface(&primary).unwrap_or(WindowGeometry {
                    x: 0,
                    y: 0,
                    width: frame.width as i32,
                    height: frame.height as i32,
                });
                blend_surface_tree(
                    state,
                    &mut composed,
                    &primary,
                    geometry.x.saturating_neg(),
                    geometry.y.saturating_neg(),
                    frame.width as i32,
                    frame.height as i32,
                    0,
                );
                for pixel in composed.pixels.chunks_exact_mut(4) {
                    pixel[0] = ((u16::from(pixel[0]) * 3) / 5) as u8;
                    pixel[1] = ((u16::from(pixel[1]) * 3) / 5) as u8;
                    pixel[2] = ((u16::from(pixel[2]) * 3) / 5) as u8;
                }
            }
        }
    }
    let root_surface = state.root_surface.clone();
    if popup_base.is_some() {
        // The popup stack overlays the stable scene captured before the grab.
    } else if let Some(root_surface) = root_surface.as_ref() {
        blend_surface_tree(
            state,
            &mut composed,
            root_surface,
            layout.root_x,
            layout.root_y,
            layout.root_width,
            layout.root_height,
            0,
        );
    } else {
        blend_popup_frame(
            &mut composed,
            root,
            layout.root_x,
            layout.root_y,
            layout.root_width,
            layout.root_height,
        );
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

        let Some(ancestor) = xdg_toplevel_ancestor_surface(&data.parent, 0) else {
            continue;
        };
        let Some(root_surface) = root_surface.as_ref() else {
            continue;
        };
        if ancestor.id() != root_surface.id() {
            continue;
        }
        let Some((x, y, width, height)) = popup_geometry_in_root(state, popup) else {
            continue;
        };
        let (content_x, content_y) = root_content_origin(state);
        let scaled_x = scale_surface_coordinate(x, layout.root_width, root.width);
        let scaled_y = scale_surface_coordinate(y, layout.root_height, root.height);
        let scaled_width = scale_surface_coordinate(width, layout.root_width, root.width);
        let scaled_height = scale_surface_coordinate(height, layout.root_height, root.height);
        let content_x = scaled_x.saturating_add(content_x);
        let content_y = scaled_y.saturating_add(content_y);
        let Some((surface_x, surface_y, surface_width, surface_height)) = surface_frame_layout(
            &xdg_data.wl_surface,
            content_x,
            content_y,
            scaled_width,
            scaled_height,
        ) else {
            continue;
        };
        blend_surface_tree(
            state,
            &mut composed,
            &xdg_data.wl_surface,
            surface_x,
            surface_y,
            surface_width,
            surface_height,
            0,
        );
    }
    state.last_frame_width = composed.width;
    state.last_frame_height = composed.height;
    state.last_frame_checksum = composed.pixels.iter().fold(0u32, |checksum, value| {
        checksum.wrapping_add(u32::from(*value))
    });
    state.last_frame = Some(Arc::new(composed));
    if !active_popups && !state.popup_base_armed {
        state.popup_base_frame = None;
    }
}
fn xdg_toplevel_ancestor_surface(xdg_surface: &XdgSurface, depth: usize) -> Option<WlSurface> {
    if depth > 64 {
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
        Some(SurfaceRole::XdgToplevel) => Some(xdg_data.wl_surface.clone()),
        Some(SurfaceRole::XdgPopup) => {
            let popup = popup?;
            let data = popup.data::<XdgPopupData>()?;
            xdg_toplevel_ancestor_surface(&data.parent, depth.saturating_add(1))
        }
        Some(SurfaceRole::Subsurface | SurfaceRole::Cursor) | None => None,
    }
}

fn compose_toplevel_frame(
    state: &CompositorState,
    toplevel: &XdgToplevel,
) -> Option<Arc<CommittedFrame>> {
    let root_surface = toplevel_surface(toplevel)?;
    let root_frame = surface_frame(&root_surface)?;
    let (width, height) = surface_content_size(&root_surface, &root_frame);
    let geometry = window_geometry_for_surface(&root_surface).unwrap_or(WindowGeometry {
        x: 0,
        y: 0,
        width: width as i32,
        height: height as i32,
    });
    let pixel_count = width
        .checked_mul(height)?
        .checked_mul(4)
        .and_then(|bytes| usize::try_from(bytes).ok())?;
    if pixel_count == 0 {
        return None;
    }
    let mut composed = CommittedFrame {
        width,
        height,
        format: root_frame.format,
        pixels: vec![0; pixel_count],
        source: None,
    };
    blend_surface_tree(
        state,
        &mut composed,
        &root_surface,
        geometry.x.saturating_neg(),
        geometry.y.saturating_neg(),
        root_frame.width as i32,
        root_frame.height as i32,
        0,
    );
    for popup in state.popups.iter().filter(|popup| popup.is_alive()) {
        let Some(data) = popup.data::<XdgPopupData>() else {
            continue;
        };
        if data.dismissed.load(Ordering::Acquire) {
            continue;
        }
        let Some(ancestor) = xdg_toplevel_ancestor_surface(&data.parent, 0) else {
            continue;
        };
        if ancestor.id() != root_surface.id() {
            continue;
        }
        let Some(xdg_data) = data.xdg_surface.data::<XdgSurfaceData>() else {
            continue;
        };
        let Some((x, y, popup_width, popup_height)) = popup_geometry_in_root(state, popup) else {
            continue;
        };
        blend_surface_tree(
            state,
            &mut composed,
            &xdg_data.wl_surface,
            x,
            y,
            popup_width,
            popup_height,
            0,
        );
    }
    Some(Arc::new(composed))
}
fn reconfigure_reactive_popups(state: &mut CompositorState, changed_parent: Option<&XdgSurface>) {
    let popups = state.popups.clone();
    for popup in popups.iter().filter(|popup| popup.is_alive()) {
        let Some(data) = popup.data::<XdgPopupData>() else {
            continue;
        };
        if data.dismissed.load(Ordering::Acquire) {
            continue;
        }
        let positioner = data
            .positioner
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .clone();
        if !positioner.reactive
            || changed_parent.is_some_and(|parent| parent.id() != data.parent.id())
        {
            continue;
        }
        let old_geometry = *data
            .applied_geometry
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if old_geometry.is_none() {
            continue;
        }
        let Some(geometry) = constrained_popup_geometry(state, data, &positioner) else {
            continue;
        };
        if old_geometry == Some(geometry) {
            continue;
        }
        state.next_configure_serial = state.next_configure_serial.wrapping_add(1).max(1);
        let serial = state.next_configure_serial;
        if let Some(xdg_data) = data.xdg_surface.data::<XdgSurfaceData>() {
            xdg_data
                .state
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .pending_configures
                .push_back(XdgConfigure {
                    serial,
                    popup_geometry: Some(geometry),
                });
        }
        popup.configure(geometry.0, geometry.1, geometry.2, geometry.3);
        data.xdg_surface.configure(serial);
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
            .create_global::<CompositorState, WlSubcompositor, _>(1, ());
        display
            .handle()
            .create_global::<CompositorState, WlShm, _>(1, ());
        display
            .handle()
            .create_global::<CompositorState, XdgWmBase, _>(6, ());
        display
            .handle()
            .create_global::<CompositorState, WlSeat, _>(9, ());
        display
            .handle()
            .create_global::<CompositorState, WlDataDeviceManager, _>(3, ());
        display
            .handle()
            .create_global::<CompositorState, ZwpTextInputManagerV3, _>(1, ());
        display
            .handle()
            .create_global::<CompositorState, ZwpPointerGesturesV1, _>(3, ());
        display
            .handle()
            .create_global::<CompositorState, WpViewporter, _>(1, ());
        display
            .handle()
            .create_global::<CompositorState, WpFractionalScaleManagerV1, _>(1, ());
        display
            .handle()
            .create_global::<CompositorState, WlOutput, _>(4, ());
        let state = CompositorState {
            output_width: 320,
            output_height: 160,
            output_scale: 1,
            output_fractional_scale: 120,
            ..CompositorState::default()
        };
        Ok(Self {
            display,
            state,
            listener: None,
            socket_path: None,
            accepted_client_count: 0,
            stopping: AtomicBool::new(false),
        })
    }

    pub fn bind_socket(&mut self, path: &Path) -> std::io::Result<()> {
        if self.listener.is_some() {
            return Err(io::Error::new(
                io::ErrorKind::AlreadyExists,
                "Wayland listener is already bound",
            ));
        }
        if path.exists() {
            std::fs::remove_file(path)?;
        }
        let listener = UnixListener::bind(path)?;
        listener.set_nonblocking(true)?;
        self.listener = Some(listener);
        self.socket_path = Some(path.to_owned());
        Ok(())
    }

    fn accept_pending_clients(&mut self) -> std::io::Result<usize> {
        let Some(listener) = self.listener.as_ref() else {
            return Ok(0);
        };
        let mut streams = Vec::new();
        loop {
            match listener.accept() {
                Ok((stream, _)) => streams.push(stream),
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => break,
                Err(error) => return Err(error),
            }
        }
        let accepted = streams.len();
        for stream in streams {
            stream.set_nonblocking(true)?;
            self.display
                .handle()
                .insert_client(stream, Arc::new(()))
                .map_err(|error| io::Error::other(error.to_string()))?;
            self.accepted_client_count = self.accepted_client_count.saturating_add(1);
        }
        Ok(accepted)
    }

    pub fn accepted_client_count(&self) -> u32 {
        self.accepted_client_count
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
        let accepted = self.accept_pending_clients()?;
        let dispatched = self.display.dispatch_clients(&mut self.state)?;
        self.display.flush_clients()?;
        Ok(accepted.saturating_add(dispatched))
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

    pub fn popup_component(&self, index: u32, component: u32) -> i32 {
        let Some(popup) = usize::try_from(index)
            .ok()
            .and_then(|index| self.state.popups.get(index))
        else {
            return -1;
        };
        let Some(data) = popup.data::<XdgPopupData>() else {
            return -1;
        };
        let geometry = popup_geometry_in_root(&self.state, popup);
        let frame = data
            .xdg_surface
            .data::<XdgSurfaceData>()
            .and_then(|xdg| surface_frame(&xdg.wl_surface));
        match component {
            0 => geometry.map_or(0, |value| value.0),
            1 => geometry.map_or(0, |value| value.1),
            2 => geometry.map_or(0, |value| value.2),
            3 => geometry.map_or(0, |value| value.3),
            4 => frame.as_ref().map_or(0, |value| value.width as i32),
            5 => frame.as_ref().map_or(0, |value| value.height as i32),
            6 => i32::from(data.grabbed.load(Ordering::Acquire)),
            7 => i32::from(data.dismissed.load(Ordering::Acquire)),
            _ => -1,
        }
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

    pub fn set_toplevel_tiling(&mut self, enabled: bool) -> u32 {
        if self.state.tile_toplevels == enabled {
            return 0;
        }
        self.state.tile_toplevels = enabled;
        update_composited_frame(&mut self.state);
        1
    }

    pub fn xdg_surface_count(&self) -> u32 {
        self.state.xdg_surface_count
    }

    pub fn xdg_toplevel_count(&self) -> u32 {
        self.state.xdg_toplevel_count
    }

    pub fn window_count(&self) -> u32 {
        u32::try_from(self.state.toplevels.len()).unwrap_or(u32::MAX)
    }

    pub fn window_change_serial(&self) -> u32 {
        self.state.window_change_serial
    }

    pub fn window_component(&self, index: u32, component: u32) -> i32 {
        let Some(toplevel) = usize::try_from(index)
            .ok()
            .and_then(|index| self.state.toplevels.get(index))
        else {
            return -1;
        };
        let Some(data) = toplevel.data::<XdgToplevelData>() else {
            return -1;
        };
        let Some(surface) = data
            .xdg_surface
            .data::<XdgSurfaceData>()
            .map(|surface| surface.wl_surface.clone())
        else {
            return -1;
        };
        let geometry = window_geometry_for_surface(&surface);
        match component {
            0 => i32::try_from(toplevel.id().protocol_id()).unwrap_or(i32::MAX),
            1 => data
                .parent
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .as_ref()
                .map_or(0, |parent| {
                    i32::try_from(parent.id().protocol_id()).unwrap_or(i32::MAX)
                }),
            2 => i32::from(surface_frame(&surface).is_some()),
            3 => i32::from(
                self.state
                    .active_toplevel
                    .as_ref()
                    .is_some_and(|active| active.id() == toplevel.id()),
            ),
            4 => i32::from(
                self.state
                    .primary_toplevel
                    .as_ref()
                    .is_some_and(|primary| primary.id() == toplevel.id()),
            ),
            5 => geometry.map_or(0, |geometry| geometry.x),
            6 => geometry.map_or(0, |geometry| geometry.y),
            7 => geometry.map_or_else(
                || surface_frame(&surface).map_or(0, |frame| frame.width as i32),
                |geometry| geometry.width,
            ),
            8 => geometry.map_or_else(
                || surface_frame(&surface).map_or(0, |frame| frame.height as i32),
                |geometry| geometry.height,
            ),
            9 => i32::try_from(
                data.title
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                    .len(),
            )
            .unwrap_or(i32::MAX),
            10 => i32::try_from(
                data.app_id
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                    .len(),
            )
            .unwrap_or(i32::MAX),
            11 => surface_frame(&surface).map_or(0, |frame| frame.width as i32),
            12 => surface_frame(&surface).map_or(0, |frame| frame.height as i32),
            13 => surface.data::<SurfaceData>().map_or(0, |data| {
                data.inner
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                    .committed_buffer_scale
            }),
            14..=17 => {
                if self
                    .state
                    .root_surface
                    .as_ref()
                    .is_none_or(|root| root.id() != surface.id())
                {
                    return 0;
                }
                root_content_layout(&self.state).map_or(0, |layout| match component {
                    14 => layout.0,
                    15 => layout.1,
                    16 => layout.2,
                    17 => layout.3,
                    _ => 0,
                })
            }
            18 => toplevel_layout(&self.state).map_or(0, |layout| layout.output_width as i32),
            19 => toplevel_layout(&self.state).map_or(0, |layout| layout.output_height as i32),
            20..=23 => {
                if self
                    .state
                    .root_surface
                    .as_ref()
                    .is_none_or(|root| root.id() != surface.id())
                {
                    return 0;
                }
                toplevel_layout(&self.state).map_or(0, |layout| match component {
                    20 => layout.root_x,
                    21 => layout.root_y,
                    22 => layout.root_width,
                    23 => layout.root_height,
                    _ => 0,
                })
            }
            _ => -1,
        }
    }

    pub fn window_text(&self, index: u32, title: bool) -> Option<String> {
        let toplevel = self.state.toplevels.get(usize::try_from(index).ok()?)?;
        let data = toplevel.data::<XdgToplevelData>()?;
        Some(if title {
            data.title
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .clone()
        } else {
            data.app_id
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .clone()
        })
    }
    fn window_frame(&self, index: u32) -> Option<Arc<CommittedFrame>> {
        let toplevel = self.state.toplevels.get(usize::try_from(index).ok()?)?;
        compose_toplevel_frame(&self.state, toplevel)
    }

    pub fn activate_window(&mut self, id: u32) -> u32 {
        let Some(toplevel) = self
            .state
            .toplevels
            .iter()
            .find(|toplevel| toplevel.id().protocol_id() == id)
            .cloned()
        else {
            return 0;
        };
        let Some(surface) = toplevel_surface(&toplevel) else {
            return 0;
        };
        let Some(frame) = surface_frame(&surface) else {
            return 0;
        };
        let changed = self
            .state
            .active_toplevel
            .as_ref()
            .is_none_or(|active| active.id() != toplevel.id());
        self.state.active_toplevel = Some(toplevel);
        self.state.root_surface = Some(surface.clone());
        self.state.root_frame = Some(frame);
        self.state.pointer_focus_surface = Some(surface.clone());
        self.state.pointer_inside = false;
        self.state.pointer_pressed = false;
        set_keyboard_focus(&mut self.state, Some(surface));
        if changed {
            self.state.window_change_serial =
                self.state.window_change_serial.wrapping_add(1).max(1);
        }
        update_composited_frame(&mut self.state);
        1
    }

    pub fn configure_window(&mut self, id: u32, width: i32, height: i32) -> u32 {
        if self.activate_window(id) == 0 {
            return 0;
        }
        self.configure_focused_toplevel(width, height)
    }
    pub fn close_window(&self, id: u32) -> u32 {
        let Some(toplevel) = self
            .state
            .toplevels
            .iter()
            .find(|toplevel| toplevel.id().protocol_id() == id)
        else {
            return 0;
        };
        toplevel.close();
        1
    }
    pub fn xdg_ack_count(&self) -> u32 {
        self.state.xdg_ack_count
    }

    fn focused_xdg_resources(&self) -> Option<(XdgSurface, XdgToplevel)> {
        let mut surface = self
            .state
            .keyboard_focus_surface
            .as_ref()
            .or(self.state.pointer_focus_surface.as_ref())?
            .clone();
        for _ in 0..=self.state.surface_count {
            let surface_data = surface.data::<SurfaceData>()?;
            let (xdg_surface, toplevel, popup, subsurface) = {
                let surface_state = surface_data
                    .inner
                    .lock()
                    .unwrap_or_else(|error| error.into_inner());
                (
                    surface_state.xdg_surface.clone(),
                    surface_state.xdg_toplevel.clone(),
                    surface_state.xdg_popup.clone(),
                    surface_state.subsurface.clone(),
                )
            };
            if let (Some(xdg_surface), Some(toplevel)) = (xdg_surface, toplevel) {
                return Some((xdg_surface, toplevel));
            }
            if let Some(parent) = popup
                .and_then(|popup| popup.data::<XdgPopupData>().map(|data| data.parent.clone()))
                .and_then(|parent| {
                    parent
                        .data::<XdgSurfaceData>()
                        .map(|data| data.wl_surface.clone())
                })
            {
                surface = parent;
                continue;
            }
            if let Some(parent) = subsurface.and_then(|subsurface| {
                subsurface
                    .data::<SubsurfaceData>()
                    .map(|data| data.parent.clone())
            }) {
                surface = parent;
                continue;
            }
            return None;
        }
        None
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
            .push_back(XdgConfigure {
                serial,
                popup_geometry: None,
            });
        toplevel.configure(width, height, Vec::new());
        xdg_surface.configure(serial);
        serial
    }

    fn configure_managed_toplevel(&mut self, width: i32, height: i32) -> bool {
        let Some(toplevel) = self
            .state
            .active_toplevel
            .clone()
            .or_else(|| self.state.primary_toplevel.clone())
        else {
            return false;
        };
        let primary = self
            .state
            .primary_toplevel
            .as_ref()
            .is_some_and(|candidate| candidate.id() == toplevel.id());
        let states = if primary {
            vec![
                xdg_toplevel::State::Maximized,
                xdg_toplevel::State::Activated,
            ]
        } else {
            vec![xdg_toplevel::State::Activated]
        };
        queue_toplevel_configure(&mut self.state, &toplevel, width, height, &states) != 0
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
        let Some(fractional_scale) = u32::try_from(scale)
            .ok()
            .and_then(|scale| scale.checked_mul(120))
        else {
            return 0;
        };
        self.configure_output_fractional(width, height, scale, fractional_scale)
    }

    pub fn configure_output_fractional(
        &mut self,
        width: i32,
        height: i32,
        scale: i32,
        fractional_scale: u32,
    ) -> u32 {
        if width <= 0 || height <= 0 || scale <= 0 || fractional_scale == 0 {
            return 0;
        }
        self.state.output_width = width;
        self.state.output_height = height;
        self.state.output_scale = scale;
        self.state.output_fractional_scale = fractional_scale;
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
        for surface in self
            .state
            .surfaces
            .iter()
            .filter(|surface| surface.is_alive())
        {
            if surface.version() >= 6 {
                surface.preferred_buffer_scale(scale);
                surface.preferred_buffer_transform(wl_output::Transform::Normal);
            }
        }
        for fractional in self
            .state
            .fractional_scales
            .iter()
            .filter(|fractional| fractional.is_alive())
        {
            fractional.preferred_scale(fractional_scale);
        }
        self.reconfigure_reactive_popups();
        if self.state.tile_toplevels && self.configure_managed_toplevel(width, height) {
            updated = updated.saturating_add(1);
        }
        updated
    }

    fn reconfigure_reactive_popups(&mut self) {
        reconfigure_reactive_popups(&mut self.state, None);
    }

    pub fn seat_bind_count(&self) -> u32 {
        self.state.seat_binds
    }

    pub fn data_device_manager_bind_count(&self) -> u32 {
        self.state.data_device_manager_binds
    }

    pub fn data_source_count(&self) -> u32 {
        self.state.data_source_count
    }

    pub fn data_device_count(&self) -> u32 {
        self.state.data_device_count
    }

    pub fn data_offer_count(&self) -> u32 {
        self.state.data_offer_count
    }
    pub fn set_clipboard_active(&mut self, active: bool) -> u32 {
        self.state.clipboard_active = active;
        if !active {
            self.state.pending_android_paste_fds.clear();
            self.state.pending_linux_copy_fds.clear();
            self.state.pending_linux_drag_fds.clear();
            self.state.pending_linux_drag_mime_types.clear();
            if let Some(source) = self.state.linux_drag_source.take()
                && source.is_alive()
            {
                source.cancelled();
            }
            if let Some(drag) = self.state.android_drag.take()
                && drag.device.is_alive()
            {
                drag.device.leave();
            }
            if self.state.android_clipboard_offered {
                self.state.android_clipboard_offered = false;
                for device in self
                    .state
                    .data_devices
                    .iter()
                    .filter(|device| device.is_alive())
                {
                    device.selection(None);
                }
            }
        }
        u32::from(active)
    }

    pub fn offer_android_clipboard_text(&mut self) -> u32 {
        if !self.state.clipboard_active {
            return 0;
        }
        if let Some(previous) = self.state.selection_source.take()
            && previous.is_alive()
        {
            previous.cancelled();
        }
        self.state.android_clipboard_offered = true;
        let handle = self.display.handle();
        publish_android_selection(&mut self.state, &handle);
        u32::try_from(
            self.state
                .data_devices
                .iter()
                .filter(|device| device.is_alive())
                .count(),
        )
        .unwrap_or(u32::MAX)
    }

    pub fn take_android_paste_fd(&mut self) -> RawFd {
        self.state
            .pending_android_paste_fds
            .pop_front()
            .map_or(-1, IntoRawFd::into_raw_fd)
    }

    pub fn take_linux_copy_fd(&mut self) -> RawFd {
        self.state
            .pending_linux_copy_fds
            .pop_front()
            .map_or(-1, IntoRawFd::into_raw_fd)
    }

    pub fn take_linux_drag_fd(&mut self) -> RawFd {
        self.state
            .pending_linux_drag_fds
            .pop_front()
            .map_or(-1, IntoRawFd::into_raw_fd)
    }

    pub fn linux_drag_mime_type(&self) -> Option<&str> {
        self.state
            .pending_linux_drag_mime_types
            .front()
            .map(String::as_str)
    }

    pub fn take_linux_drag_mime_type(&mut self) -> Option<String> {
        self.state.pending_linux_drag_mime_types.pop_front()
    }

    pub fn finish_linux_drag(&mut self, accepted: bool) -> u32 {
        let Some(source) = self.state.linux_drag_source.take() else {
            return 0;
        };
        self.state.pending_linux_drag_fds.clear();
        self.state.pending_linux_drag_mime_types.clear();
        if !source.is_alive() {
            return 0;
        }
        if accepted && source.version() >= 3 {
            source.dnd_drop_performed();
            source.dnd_finished();
        } else {
            source.cancelled();
        }
        1
    }

    pub fn begin_android_drag(&mut self, x: f64, y: f64) -> u32 {
        self.cancel_android_drag();
        let Some(surface) = self.state.root_surface.clone() else {
            return 0;
        };
        let Some(device) = self
            .state
            .data_devices
            .iter()
            .find(|device| device.is_alive() && device.id().same_client_as(&surface.id()))
            .cloned()
        else {
            return 0;
        };
        let Ok(client) = self.display.handle().get_client(device.id()) else {
            return 0;
        };
        let payloads = Arc::new(Mutex::new(HashMap::new()));
        let mime_types = ANDROID_DRAG_MIME_TYPES
            .iter()
            .map(|mime_type| (*mime_type).to_owned())
            .collect::<Vec<_>>();
        let Ok(offer) = client.create_resource::<WlDataOffer, _, CompositorState>(
            &self.display.handle(),
            device.version().min(3),
            DataOfferData {
                source: ClipboardOfferSource::AndroidDrag(payloads.clone()),
                mime_types: mime_types.clone(),
            },
        ) else {
            return 0;
        };
        device.data_offer(&offer);
        for mime_type in mime_types {
            offer.offer(mime_type);
        }
        if offer.version() >= 3 {
            offer.source_actions(wl_data_device_manager::DndAction::Copy.into());
            offer.action(wl_data_device_manager::DndAction::Copy.into());
        }
        let serial = self.next_input_serial();
        let (local_x, local_y) = self.pointer_local_coordinates(&surface, x, y);
        device.enter(serial, &surface, local_x, local_y, Some(&offer));
        self.state.data_offer_count = self.state.data_offer_count.saturating_add(1);
        self.state.data_offers.push(offer.clone());
        self.state.android_drag = Some(AndroidDragState {
            device,
            offer,
            payloads,
        });
        1
    }

    pub fn android_drag_motion(&mut self, x: f64, y: f64, time: u32) -> u32 {
        if self.state.android_drag.is_none() && self.begin_android_drag(x, y) == 0 {
            return 0;
        }
        let Some(surface) = self.state.root_surface.as_ref() else {
            return 0;
        };
        let (local_x, local_y) = self.pointer_local_coordinates(surface, x, y);
        let Some(drag) = self.state.android_drag.as_ref() else {
            return 0;
        };
        drag.device.motion(time, local_x, local_y);
        1
    }

    pub fn android_drop_text(&mut self, text: Vec<u8>) -> u32 {
        const MAX_DRAG_BYTES: usize = 8 * 1024 * 1024;
        if text.len() > MAX_DRAG_BYTES {
            self.cancel_android_drag();
            return 0;
        }
        let Some(drag) = self.state.android_drag.as_ref() else {
            return 0;
        };
        let mut payloads = drag
            .payloads
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        payloads.insert(TEXT_MIME_TYPES[0].to_owned(), text.clone());
        payloads.insert(TEXT_MIME_TYPES[1].to_owned(), text);
        drop(payloads);
        drag.device.drop();
        1
    }

    pub fn android_drop_uri_list(&mut self, uri_list: Vec<u8>) -> u32 {
        const MAX_URI_LIST_BYTES: usize = 1024 * 1024;
        if uri_list.is_empty() || uri_list.len() > MAX_URI_LIST_BYTES {
            self.cancel_android_drag();
            return 0;
        }
        let Some(drag) = self.state.android_drag.as_ref() else {
            return 0;
        };
        drag.payloads
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .insert(URI_LIST_MIME_TYPE.to_owned(), uri_list);
        drag.device.drop();
        1
    }

    pub fn cancel_android_drag(&mut self) -> u32 {
        let Some(drag) = self.state.android_drag.take() else {
            return 0;
        };
        if drag.device.is_alive() {
            drag.device.leave();
        }
        let _ = drag.offer;
        1
    }

    pub fn text_input_manager_bind_count(&self) -> u32 {
        self.state.text_input_manager_binds
    }

    pub fn text_input_count(&self) -> u32 {
        self.state.text_input_count
    }

    pub fn ime_active(&self) -> u32 {
        u32::from(self.state.ime_active)
    }

    pub fn ime_show_request_count(&self) -> u32 {
        self.state.ime_show_requests
    }

    pub fn ime_hide_request_count(&self) -> u32 {
        self.state.ime_hide_requests
    }

    pub fn ime_surrounding_text_length(&self) -> i32 {
        self.enabled_text_input_state()
            .and_then(|state| state.surrounding_text.as_ref().map(|text| text.text.len()))
            .and_then(|length| i32::try_from(length).ok())
            .unwrap_or(-1)
    }

    pub fn ime_surrounding_text(&self) -> Option<String> {
        self.enabled_text_input_state().and_then(|state| {
            state
                .surrounding_text
                .as_ref()
                .map(|text| text.text.clone())
        })
    }

    pub fn ime_surrounding_cursor(&self) -> i32 {
        self.enabled_text_input_state()
            .and_then(|state| state.surrounding_text.as_ref().map(|text| text.cursor))
            .unwrap_or(-1)
    }

    pub fn ime_surrounding_anchor(&self) -> i32 {
        self.enabled_text_input_state()
            .and_then(|state| state.surrounding_text.as_ref().map(|text| text.anchor))
            .unwrap_or(-1)
    }
    pub fn ime_content_hint(&self) -> i32 {
        self.enabled_text_input_state()
            .and_then(|state| i32::try_from(state.content_type.0).ok())
            .unwrap_or(-1)
    }

    pub fn ime_content_purpose(&self) -> i32 {
        self.enabled_text_input_state()
            .and_then(|state| i32::try_from(state.content_type.1).ok())
            .unwrap_or(-1)
    }

    pub fn ime_cursor_rectangle_component(&self, component: usize) -> i32 {
        self.ime_cursor_rectangle()
            .and_then(|rectangle| {
                [rectangle.0, rectangle.1, rectangle.2, rectangle.3]
                    .get(component)
                    .copied()
            })
            .unwrap_or(-1)
    }

    pub fn ime_cursor_rectangle(&self) -> Option<(i32, i32, i32, i32)> {
        self.enabled_text_input_state()
            .and_then(|state| state.cursor_rectangle)
    }

    fn enabled_text_input_state(&self) -> Option<std::sync::MutexGuard<'_, TextInputState>> {
        self.state.text_inputs.iter().find_map(|text_input| {
            let data = text_input.data::<TextInputData>()?;
            let state = data.state.lock().unwrap_or_else(|error| error.into_inner());
            state.enabled.then_some(state)
        })
    }

    fn enabled_text_input(&self) -> Option<(ZwpTextInputV3, u32)> {
        self.state.text_inputs.iter().find_map(|text_input| {
            let data = text_input.data::<TextInputData>()?;
            let state = data.state.lock().unwrap_or_else(|error| error.into_inner());
            state
                .enabled
                .then_some((text_input.clone(), state.commit_count))
        })
    }

    pub fn ime_commit_text(&mut self, text: String) -> u32 {
        let Some((text_input, serial)) = self.enabled_text_input() else {
            return 0;
        };
        text_input.preedit_string(None, 0, 0);
        text_input.commit_string(Some(text));
        text_input.done(serial);
        1
    }

    pub fn ime_editor_action(&mut self, action: u32, time: u32) -> u32 {
        let key = if matches!(action, 5 | 7) { 15 } else { 28 };
        if self.keyboard_key(key, true, time) == 1 {
            let _ = self.keyboard_key(key, false, time);
            return 1;
        }
        let text = if key == 15 { "\t" } else { "\n" };
        self.ime_commit_text(text.to_owned())
    }

    pub fn ime_set_preedit(&mut self, text: String, cursor_begin: i32, cursor_end: i32) -> u32 {
        let Some((text_input, serial)) = self.enabled_text_input() else {
            return 0;
        };
        text_input.preedit_string(Some(text), cursor_begin, cursor_end);
        text_input.done(serial);
        1
    }

    pub fn ime_delete_surrounding(&mut self, before_length: u32, after_length: u32) -> u32 {
        let Some((text_input, serial)) = self.enabled_text_input() else {
            return 0;
        };
        text_input.delete_surrounding_text(before_length, after_length);
        text_input.done(serial);
        1
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

    fn keyboard_modifier_mask(pressed_keys: &[u32]) -> u32 {
        let mut mask = 0;
        if pressed_keys.iter().any(|key| matches!(key, 42 | 54)) {
            mask |= 1 << 0;
        }
        if pressed_keys.iter().any(|key| matches!(key, 29 | 97)) {
            mask |= 1 << 2;
        }
        if pressed_keys.iter().any(|key| matches!(key, 56 | 100)) {
            mask |= 1 << 3;
        }
        mask
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
        if let Some(surface) = self.state.keyboard_focus_surface.clone() {
            self.remember_selection_serial(serial, surface.clone());
            if pressed {
                if self
                    .state
                    .popup_grab
                    .as_ref()
                    .is_none_or(|grab| !grab.active)
                {
                    self.state.popup_base_frame = self.state.last_frame.clone();
                    self.state.popup_base_armed = true;
                }
                self.state.popup_grab_serial = Some(PopupGrabSerial { serial, surface });
            }
        }
        let key_state = if pressed {
            wl_keyboard::KeyState::Pressed
        } else {
            wl_keyboard::KeyState::Released
        };
        let previous_modifiers = Self::keyboard_modifier_mask(&self.state.pressed_keys);
        for keyboard in &keyboards {
            keyboard.key(serial, time, key, key_state.into());
        }
        if pressed {
            self.state.pressed_keys.push(key);
        } else {
            self.state
                .pressed_keys
                .retain(|pressed_key| *pressed_key != key);
        }
        let modifiers = Self::keyboard_modifier_mask(&self.state.pressed_keys);
        if modifiers != previous_modifiers {
            for keyboard in &keyboards {
                keyboard.modifiers(serial, modifiers, 0, 0, 0);
            }
        }
        self.state.keyboard_event_count = self
            .state
            .keyboard_event_count
            .saturating_add(1 + u32::from(modifiers != previous_modifiers));
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
            let layout = toplevel_layout(&self.state)?;
            let root_frame = self.state.root_frame.as_ref()?;
            let (root_x, root_y) = root_content_origin(&self.state);
            let popup_x = scale_surface_coordinate(popup_x, layout.root_width, root_frame.width);
            let popup_y = scale_surface_coordinate(popup_y, layout.root_height, root_frame.height);
            let width = scale_surface_coordinate(width, layout.root_width, root_frame.width);
            let height = scale_surface_coordinate(height, layout.root_height, root_frame.height);
            let content_x = popup_x.saturating_add(root_x);
            let content_y = popup_y.saturating_add(root_y);
            let Some((surface_x, surface_y, surface_width, surface_height)) =
                surface_frame_layout(&xdg_data.wl_surface, content_x, content_y, width, height)
            else {
                continue;
            };
            if let Some(target) = surface_tree_pointer_target(
                &self.state,
                &xdg_data.wl_surface,
                surface_x,
                surface_y,
                surface_width,
                surface_height,
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
            root_surface_origin(&self.state).0,
            root_surface_origin(&self.state).1,
            root_input_dimensions(&self.state).0,
            root_input_dimensions(&self.state).1,
            x,
            y,
            0,
        )
    }
    fn pointer_local_coordinates(&self, surface: &WlSurface, x: f64, y: f64) -> (f64, f64) {
        let origin = surface_origin_in_root(&self.state, surface, 0);
        let Some((origin_x, origin_y)) = origin else {
            return (x, y);
        };
        let local_x = x - f64::from(origin_x);
        let local_y = y - f64::from(origin_y);
        if self
            .state
            .root_surface
            .as_ref()
            .is_some_and(|root| root.id() == surface.id())
        {
            if let Some(frame) = surface_frame(surface) {
                let (target_width, target_height) = root_input_dimensions(&self.state);
                return (
                    scale_input_coordinate(local_x, target_width, frame.width),
                    scale_input_coordinate(local_y, target_height, frame.height),
                );
            }
        }
        (local_x, local_y)
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
    fn remember_selection_serial(&mut self, serial: u32, surface: WlSurface) {
        const MAX_RECENT_SERIALS: usize = 32;
        self.state
            .selection_serials
            .push_back(PopupGrabSerial { serial, surface });
        while self.state.selection_serials.len() > MAX_RECENT_SERIALS {
            self.state.selection_serials.pop_front();
        }
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
                    root_surface_origin(&self.state).0,
                    root_surface_origin(&self.state).1,
                    root_input_dimensions(&self.state).0,
                    root_input_dimensions(&self.state).1,
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
            .is_none_or(|focused| focused.id() != surface.id());
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
            self.state.last_pointer_enter_serial = serial;
        }
        self.state.pointer_inside = true;
        self.state.pointer_x = x;
        self.state.pointer_y = y;
        self.state.pointer_event_count = self.state.pointer_event_count.saturating_add(1);
        1
    }

    fn touch_resources_for_surface(&self, surface: &WlSurface) -> Vec<WlTouch> {
        self.state
            .touches
            .iter()
            .filter(|touch| touch.is_alive() && touch.id().same_client_as(&surface.id()))
            .cloned()
            .collect()
    }

    fn touch_target(&self, x: f64, y: f64) -> Option<(WlSurface, f64, f64)> {
        if self
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
                    root_surface_origin(&self.state).0,
                    root_surface_origin(&self.state).1,
                    root_input_dimensions(&self.state).0,
                    root_input_dimensions(&self.state).1,
                    x,
                    y,
                    0,
                )
            })
        }
    }

    pub fn touch_down(&mut self, id: i32, x: f64, y: f64, time: u32) -> u32 {
        if self.state.active_touches.iter().any(|touch| touch.id == id) {
            return 0;
        }
        let Some((surface, local_x, local_y)) = self.touch_target(x, y) else {
            return 0;
        };
        let touches = self.touch_resources_for_surface(&surface);
        if touches.is_empty() {
            return 0;
        }
        let serial = self.next_input_serial();
        self.remember_selection_serial(serial, surface.clone());
        if self
            .state
            .popup_grab
            .as_ref()
            .is_none_or(|grab| !grab.active)
        {
            self.state.popup_base_frame = self.state.last_frame.clone();
            self.state.popup_base_armed = true;
        }
        self.state.popup_grab_serial = Some(PopupGrabSerial {
            serial,
            surface: surface.clone(),
        });
        set_keyboard_focus(&mut self.state, Some(surface.clone()));
        for touch in touches {
            touch.down(serial, time, &surface, id, local_x, local_y);
            touch.frame();
        }
        self.state.active_touches.push(ActiveTouch { id, surface });
        self.state.touch_event_count = self.state.touch_event_count.saturating_add(1);
        1
    }

    pub fn touch_motion(&mut self, id: i32, x: f64, y: f64, time: u32) -> u32 {
        let Some(surface) = self
            .state
            .active_touches
            .iter()
            .find(|touch| touch.id == id)
            .map(|touch| touch.surface.clone())
        else {
            return 0;
        };
        let touches = self.touch_resources_for_surface(&surface);
        if touches.is_empty() {
            return 0;
        }
        let (local_x, local_y) = self.pointer_local_coordinates(&surface, x, y);
        for touch in touches {
            touch.motion(time, id, local_x, local_y);
            touch.frame();
        }
        self.state.touch_event_count = self.state.touch_event_count.saturating_add(1);
        1
    }

    pub fn touch_up(&mut self, id: i32, time: u32) -> u32 {
        let Some(index) = self
            .state
            .active_touches
            .iter()
            .position(|touch| touch.id == id)
        else {
            return 0;
        };
        let surface = self.state.active_touches[index].surface.clone();
        let touches = self.touch_resources_for_surface(&surface);
        if touches.is_empty() {
            return 0;
        }
        let serial = self.next_input_serial();
        for touch in touches {
            touch.up(serial, time, id);
            touch.frame();
        }
        self.state.active_touches.remove(index);
        self.state.touch_event_count = self.state.touch_event_count.saturating_add(1);
        1
    }

    pub fn touch_cancel(&mut self) -> u32 {
        if self.state.active_touches.is_empty() {
            return 0;
        }
        let mut touches = Vec::<WlTouch>::new();
        for active in &self.state.active_touches {
            for touch in self.touch_resources_for_surface(&active.surface) {
                if !touches.iter().any(|candidate| candidate.id() == touch.id()) {
                    touches.push(touch);
                }
            }
        }
        for touch in touches {
            touch.cancel();
            touch.frame();
        }
        self.state.active_touches.clear();
        self.state.touch_event_count = self.state.touch_event_count.saturating_add(1);
        1
    }

    fn gesture_focus_surface(&self) -> Option<WlSurface> {
        self.state
            .pointer_inside
            .then(|| self.state.pointer_focus_surface.clone())
            .flatten()
    }

    pub fn swipe_begin(&mut self, fingers: u32, time: u32) -> u32 {
        let Some(surface) = self.gesture_focus_surface() else {
            return 0;
        };
        let gestures = self
            .state
            .swipe_gestures
            .iter()
            .filter(|gesture| {
                gesture.is_alive()
                    && gesture.data::<PointerGestureData>().is_some_and(|data| {
                        data.pointer.is_alive() && data.pointer.id().same_client_as(&surface.id())
                    })
            })
            .cloned()
            .collect::<Vec<_>>();
        if gestures.is_empty() || fingers == 0 {
            return 0;
        }
        let serial = self.next_input_serial();
        for gesture in gestures {
            gesture.begin(serial, time, &surface, fingers);
        }
        self.state.gesture_event_count = self.state.gesture_event_count.saturating_add(1);
        1
    }

    pub fn swipe_update(&mut self, dx: f64, dy: f64, time: u32) -> u32 {
        let Some(surface) = self.gesture_focus_surface() else {
            return 0;
        };
        let mut sent = false;
        for gesture in self.state.swipe_gestures.iter().filter(|gesture| {
            gesture.is_alive()
                && gesture.data::<PointerGestureData>().is_some_and(|data| {
                    data.pointer.is_alive() && data.pointer.id().same_client_as(&surface.id())
                })
        }) {
            gesture.update(time, dx, dy);
            sent = true;
        }
        if sent {
            self.state.gesture_event_count = self.state.gesture_event_count.saturating_add(1);
        }
        u32::from(sent)
    }

    pub fn swipe_end(&mut self, cancelled: bool, time: u32) -> u32 {
        let Some(surface) = self.gesture_focus_surface() else {
            return 0;
        };
        let gestures = self
            .state
            .swipe_gestures
            .iter()
            .filter(|gesture| {
                gesture.is_alive()
                    && gesture.data::<PointerGestureData>().is_some_and(|data| {
                        data.pointer.is_alive() && data.pointer.id().same_client_as(&surface.id())
                    })
            })
            .cloned()
            .collect::<Vec<_>>();
        if gestures.is_empty() {
            return 0;
        }
        let serial = self.next_input_serial();
        for gesture in gestures {
            gesture.end(serial, time, i32::from(cancelled));
        }
        self.state.gesture_event_count = self.state.gesture_event_count.saturating_add(1);
        1
    }

    pub fn pinch_begin(&mut self, fingers: u32, time: u32) -> u32 {
        let Some(surface) = self.gesture_focus_surface() else {
            return 0;
        };
        let gestures = self
            .state
            .pinch_gestures
            .iter()
            .filter(|gesture| {
                gesture.is_alive()
                    && gesture.data::<PointerGestureData>().is_some_and(|data| {
                        data.pointer.is_alive() && data.pointer.id().same_client_as(&surface.id())
                    })
            })
            .cloned()
            .collect::<Vec<_>>();
        if gestures.is_empty() || fingers == 0 {
            return 0;
        }
        let serial = self.next_input_serial();
        for gesture in gestures {
            gesture.begin(serial, time, &surface, fingers);
        }
        self.state.gesture_event_count = self.state.gesture_event_count.saturating_add(1);
        1
    }

    pub fn pinch_update(&mut self, dx: f64, dy: f64, scale: f64, rotation: f64, time: u32) -> u32 {
        if !scale.is_finite() || scale <= 0.0 || !rotation.is_finite() {
            return 0;
        }
        let Some(surface) = self.gesture_focus_surface() else {
            return 0;
        };
        let mut sent = false;
        for gesture in self.state.pinch_gestures.iter().filter(|gesture| {
            gesture.is_alive()
                && gesture.data::<PointerGestureData>().is_some_and(|data| {
                    data.pointer.is_alive() && data.pointer.id().same_client_as(&surface.id())
                })
        }) {
            gesture.update(time, dx, dy, scale, rotation);
            sent = true;
        }
        if sent {
            self.state.gesture_event_count = self.state.gesture_event_count.saturating_add(1);
        }
        u32::from(sent)
    }

    pub fn pinch_end(&mut self, cancelled: bool, time: u32) -> u32 {
        let Some(surface) = self.gesture_focus_surface() else {
            return 0;
        };
        let gestures = self
            .state
            .pinch_gestures
            .iter()
            .filter(|gesture| {
                gesture.is_alive()
                    && gesture.data::<PointerGestureData>().is_some_and(|data| {
                        data.pointer.is_alive() && data.pointer.id().same_client_as(&surface.id())
                    })
            })
            .cloned()
            .collect::<Vec<_>>();
        if gestures.is_empty() {
            return 0;
        }
        let serial = self.next_input_serial();
        for gesture in gestures {
            gesture.end(serial, time, i32::from(cancelled));
        }
        self.state.gesture_event_count = self.state.gesture_event_count.saturating_add(1);
        1
    }

    pub fn hold_begin(&mut self, fingers: u32, time: u32) -> u32 {
        let Some(surface) = self.gesture_focus_surface() else {
            return 0;
        };
        let gestures = self
            .state
            .hold_gestures
            .iter()
            .filter(|gesture| {
                gesture.is_alive()
                    && gesture.data::<PointerGestureData>().is_some_and(|data| {
                        data.pointer.is_alive() && data.pointer.id().same_client_as(&surface.id())
                    })
            })
            .cloned()
            .collect::<Vec<_>>();
        if gestures.is_empty() || fingers == 0 {
            return 0;
        }
        let serial = self.next_input_serial();
        for gesture in gestures {
            gesture.begin(serial, time, &surface, fingers);
        }
        self.state.gesture_event_count = self.state.gesture_event_count.saturating_add(1);
        1
    }

    pub fn hold_end(&mut self, cancelled: bool, time: u32) -> u32 {
        let Some(surface) = self.gesture_focus_surface() else {
            return 0;
        };
        let gestures = self
            .state
            .hold_gestures
            .iter()
            .filter(|gesture| {
                gesture.is_alive()
                    && gesture.data::<PointerGestureData>().is_some_and(|data| {
                        data.pointer.is_alive() && data.pointer.id().same_client_as(&surface.id())
                    })
            })
            .cloned()
            .collect::<Vec<_>>();
        if gestures.is_empty() {
            return 0;
        }
        let serial = self.next_input_serial();
        for gesture in gestures {
            gesture.end(serial, time, i32::from(cancelled));
        }
        self.state.gesture_event_count = self.state.gesture_event_count.saturating_add(1);
        1
    }

    pub fn gesture_event_count(&self) -> u32 {
        self.state.gesture_event_count
    }
    pub fn pointer_enter_serial(&self) -> u32 {
        self.state.last_pointer_enter_serial
    }
    pub fn cursor_width(&self) -> u32 {
        self.state
            .cursor_frame
            .as_ref()
            .map_or(0, |frame| frame.width)
    }

    pub fn cursor_height(&self) -> u32 {
        self.state
            .cursor_frame
            .as_ref()
            .map_or(0, |frame| frame.height)
    }

    pub fn cursor_hotspot_component(&self, component: u32) -> i32 {
        match component {
            0 => self.state.cursor_hotspot_x,
            1 => self.state.cursor_hotspot_y,
            _ => 0,
        }
    }
    pub fn touch_count(&self) -> u32 {
        u32::try_from(self.state.touches.len()).unwrap_or(u32::MAX)
    }

    pub fn touch_event_count(&self) -> u32 {
        self.state.touch_event_count
    }
    pub fn pointer_button(&mut self, pressed: bool, time: u32) -> u32 {
        if !self.state.pointer_inside || self.state.pointer_pressed == pressed {
            return 0;
        }
        let Some((surface, pointers)) = self.focused_pointer_resources() else {
            return 0;
        };
        let serial = self.next_input_serial();
        self.remember_selection_serial(serial, surface.clone());
        if pressed {
            if self
                .state
                .popup_grab
                .as_ref()
                .is_none_or(|grab| !grab.active)
            {
                self.state.popup_base_frame = self.state.last_frame.clone();
                self.state.popup_base_armed = true;
            }
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

    pub fn pointer_axis(&mut self, horizontal: f64, vertical: f64, time: u32) -> u32 {
        if !self.state.pointer_inside || (horizontal == 0.0 && vertical == 0.0) {
            return 0;
        }
        let Some((_surface, pointers)) = self.focused_pointer_resources() else {
            return 0;
        };
        for pointer in pointers {
            if pointer.version() >= 5 {
                pointer.axis_source(wl_pointer::AxisSource::Wheel);
            }
            if vertical != 0.0 {
                let discrete = (-vertical).round() as i32;
                if pointer.version() >= 8 && discrete != 0 {
                    pointer.axis_value120(
                        wl_pointer::Axis::VerticalScroll,
                        discrete.saturating_mul(120),
                    );
                } else if pointer.version() >= 5 && discrete != 0 {
                    pointer.axis_discrete(wl_pointer::Axis::VerticalScroll, discrete);
                }
                if pointer.version() >= 9 {
                    pointer.axis_relative_direction(
                        wl_pointer::Axis::VerticalScroll,
                        wl_pointer::AxisRelativeDirection::Identical,
                    );
                }
                pointer.axis(time, wl_pointer::Axis::VerticalScroll, -vertical * 15.0);
            }
            if horizontal != 0.0 {
                let discrete = horizontal.round() as i32;
                if pointer.version() >= 8 && discrete != 0 {
                    pointer.axis_value120(
                        wl_pointer::Axis::HorizontalScroll,
                        discrete.saturating_mul(120),
                    );
                } else if pointer.version() >= 5 && discrete != 0 {
                    pointer.axis_discrete(wl_pointer::Axis::HorizontalScroll, discrete);
                }
                if pointer.version() >= 9 {
                    pointer.axis_relative_direction(
                        wl_pointer::Axis::HorizontalScroll,
                        wl_pointer::AxisRelativeDirection::Identical,
                    );
                }
                pointer.axis(time, wl_pointer::Axis::HorizontalScroll, horizontal * 15.0);
            }
            if pointer.version() >= 5 {
                pointer.frame();
            }
        }
        self.state.pointer_event_count = self.state.pointer_event_count.saturating_add(1);
        1
    }

    pub fn pending_frame_callback_count(&self) -> u32 {
        u32::try_from(self.state.presentation_callbacks.len()).unwrap_or(u32::MAX)
    }

    pub fn pending_damage_count(&self) -> u32 {
        u32::try_from(self.state.presentation_damage.len()).unwrap_or(u32::MAX)
    }

    pub fn pending_damage_component(&self, component: u32) -> i32 {
        let Some(bounds) = self
            .state
            .presentation_damage
            .iter()
            .copied()
            .reduce(RegionRectangle::union)
        else {
            return 0;
        };
        match component {
            0 => bounds.x,
            1 => bounds.y,
            2 => bounds.width,
            3 => bounds.height,
            _ => 0,
        }
    }

    pub fn present_frame(&mut self, time: u32) -> u32 {
        self.state.presentation_damage.clear();
        let callbacks = std::mem::take(&mut self.state.presentation_callbacks);
        let mut presented = 0u32;
        for callback in callbacks {
            if callback.is_alive() {
                callback.done(time);
                presented = presented.saturating_add(1);
            }
        }
        presented
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

fn receive_probe_data_source_send(
    socket_fd: RawFd,
    source_id: u32,
    expected_mime_type: &str,
    payload: &[u8],
) -> io::Result<usize> {
    let mut header_bytes = [0u8; 8];
    let mut io_vector = libc::iovec {
        iov_base: header_bytes.as_mut_ptr().cast(),
        iov_len: header_bytes.len(),
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
    if received != header_bytes.len() {
        return Err(io::Error::new(
            io::ErrorKind::UnexpectedEof,
            "incomplete wl_data_source.send header",
        ));
    }
    let object = u32::from_ne_bytes(header_bytes[0..4].try_into().expect("fixed header"));
    let word = u32::from_ne_bytes(header_bytes[4..8].try_into().expect("fixed header"));
    let size = (word >> 16) as usize;
    if object != source_id || word & 0xffff != 1 || size < 12 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "invalid wl_data_source.send event",
        ));
    }

    let control_message = unsafe { libc::CMSG_FIRSTHDR(&message) };
    if control_message.is_null()
        || unsafe { (*control_message).cmsg_level } != libc::SOL_SOCKET
        || unsafe { (*control_message).cmsg_type } != libc::SCM_RIGHTS
    {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "wl_data_source.send did not include an FD",
        ));
    }
    let received_fd =
        unsafe { ptr::read_unaligned(libc::CMSG_DATA(control_message).cast::<RawFd>()) };
    if received_fd < 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "wl_data_source.send FD was invalid",
        ));
    }
    let mut destination = unsafe { File::from_raw_fd(received_fd) };

    let mut body = vec![0u8; size - 8];
    let mut offset = 0usize;
    while offset < body.len() {
        let count = unsafe {
            libc::recv(
                socket_fd,
                body[offset..].as_mut_ptr().cast(),
                body.len() - offset,
                libc::MSG_WAITALL,
            )
        };
        if count <= 0 {
            return Err(if count == 0 {
                io::Error::new(
                    io::ErrorKind::UnexpectedEof,
                    "incomplete wl_data_source.send body",
                )
            } else {
                io::Error::last_os_error()
            });
        }
        offset += count as usize;
    }
    let length = u32::from_ne_bytes(body[0..4].try_into().expect("fixed string length")) as usize;
    if length == 0 || length > body.len() - 4 || body[4 + length - 1] != 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "invalid wl_data_source.send MIME type",
        ));
    }
    let mime_type = std::str::from_utf8(&body[4..4 + length - 1])
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?;
    if mime_type != expected_mime_type {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "unexpected wl_data_source.send MIME type",
        ));
    }
    destination.write_all(payload)?;
    Ok(payload.len())
}
fn receive_probe_drag_source_send(
    socket_fd: RawFd,
    source_id: u32,
    callback_id: u32,
    expected_mime_type: &str,
    payload: &[u8],
) -> io::Result<usize> {
    let mut pending = Vec::new();
    let mut destination: Option<File> = None;
    let mut targeted = false;
    let mut action_copy = false;
    let mut sent = false;
    let mut synced = false;
    for _ in 0..16 {
        let mut bytes = [0u8; 4096];
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
            let result = unsafe { libc::recvmsg(socket_fd, &mut message, 0) };
            if result >= 0 {
                break result as usize;
            }
            let error = io::Error::last_os_error();
            if error.kind() != io::ErrorKind::Interrupted {
                return Err(error);
            }
        };
        if received == 0 {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "drag source event stream closed",
            ));
        }
        let control_message = unsafe { libc::CMSG_FIRSTHDR(&message) };
        if !control_message.is_null()
            && unsafe { (*control_message).cmsg_level } == libc::SOL_SOCKET
            && unsafe { (*control_message).cmsg_type } == libc::SCM_RIGHTS
        {
            let received_fd =
                unsafe { ptr::read_unaligned(libc::CMSG_DATA(control_message).cast::<RawFd>()) };
            if received_fd >= 0 {
                if destination.is_some() {
                    unsafe { libc::close(received_fd) };
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "multiple drag source FDs",
                    ));
                }
                destination = Some(unsafe { File::from_raw_fd(received_fd) });
            }
        }
        pending.extend_from_slice(&bytes[..received]);
        loop {
            if pending.len() < 8 {
                break;
            }
            let object = u32::from_ne_bytes(pending[0..4].try_into().expect("fixed header"));
            let word = u32::from_ne_bytes(pending[4..8].try_into().expect("fixed header"));
            let opcode = word & 0xffff;
            let size = (word >> 16) as usize;
            if size < 8 {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "invalid drag source event size",
                ));
            }
            if pending.len() < size {
                break;
            }
            let body = &pending[8..size];
            let parse_string = |value: &[u8]| -> io::Result<Option<String>> {
                if value.len() < 4 {
                    return Err(io::Error::new(io::ErrorKind::InvalidData, "short string"));
                }
                let length =
                    u32::from_ne_bytes(value[0..4].try_into().expect("string length")) as usize;
                if length == 0 {
                    return Ok(None);
                }
                if length > value.len() - 4 || value[4 + length - 1] != 0 {
                    return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid string"));
                }
                std::str::from_utf8(&value[4..4 + length - 1])
                    .map(|text| Some(text.to_owned()))
                    .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))
            };
            if object == source_id && opcode == 0 {
                targeted = parse_string(body)?.as_deref() == Some(expected_mime_type);
            } else if object == source_id && opcode == 5 && body.len() == 4 {
                action_copy = u32::from_ne_bytes(body.try_into().expect("action body")) == 1;
            } else if object == source_id && opcode == 1 {
                sent = parse_string(body)?.as_deref() == Some(expected_mime_type);
            } else if object == callback_id && opcode == 0 {
                synced = true;
            }
            pending.drain(..size);
        }
        if targeted && action_copy && sent && synced && destination.is_some() {
            let mut destination = destination.expect("checked destination");
            destination.write_all(payload)?;
            return Ok(payload.len());
        }
    }
    Err(io::Error::new(
        io::ErrorKind::InvalidData,
        "incomplete coalesced drag source events",
    ))
}
fn send_probe_data_offer_receive(
    socket_fd: RawFd,
    offer_id: u32,
    mime_type: &str,
) -> io::Result<RawFd> {
    let (read_end, write_end) = create_cloexec_pipe()?;
    let encoded = mime_type.as_bytes();
    let length = encoded.len().saturating_add(1);
    let padded_length = length.saturating_add(3) & !3;
    let size = 8usize.saturating_add(4).saturating_add(padded_length);
    let size = u16::try_from(size)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "MIME type is too long"))?;
    let mut request = Vec::with_capacity(usize::from(size));
    append_wayland_header(&mut request, offer_id, 1, size);
    request.extend_from_slice(
        &u32::try_from(length)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "MIME type is too long"))?
            .to_ne_bytes(),
    );
    request.extend_from_slice(encoded);
    request.push(0);
    request.resize(usize::from(size), 0);
    send_fd(socket_fd, &request, write_end.as_raw_fd())?;
    Ok(read_end.into_raw_fd())
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

impl Drop for CompositorCore {
    fn drop(&mut self) {
        self.listener = None;
        if let Some(path) = self.socket_path.take() {
            let _ = std::fs::remove_file(path);
        }
    }
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
pub extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeReceiveDataSourceSend(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    socket_fd: i32,
    source_id: i32,
) -> i32 {
    const PAYLOAD: &[u8] = b"ARCHPHENE_WAYLAND_TO_ANDROID";
    let Ok(source_id) = u32::try_from(source_id) else {
        return -1;
    };
    match receive_probe_data_source_send(socket_fd, source_id, TEXT_MIME_TYPES[0], PAYLOAD) {
        Ok(size) => i32::try_from(size).unwrap_or(i32::MAX),
        Err(_) => -2,
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeReceiveDragSourceSend(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    socket_fd: i32,
    source_id: i32,
    callback_id: i32,
) -> i32 {
    const PAYLOAD: &[u8] = b"ARCHPHENE_WAYLAND_TO_ANDROID";
    let (Ok(source_id), Ok(callback_id)) = (u32::try_from(source_id), u32::try_from(callback_id))
    else {
        return -1;
    };
    match receive_probe_drag_source_send(
        socket_fd,
        source_id,
        callback_id,
        TEXT_MIME_TYPES[0],
        PAYLOAD,
    ) {
        Ok(size) => i32::try_from(size).unwrap_or(i32::MAX),
        Err(_) => -2,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeReceiveUriDragSourceSend(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    socket_fd: i32,
    source_id: i32,
    callback_id: i32,
) -> i32 {
    const PAYLOAD: &[u8] = b"file:///data/user/0/org.archphene.probe/files/linux-home/Documents/outbound%20drag.txt\r\n";
    let (Ok(source_id), Ok(callback_id)) = (u32::try_from(source_id), u32::try_from(callback_id))
    else {
        return -1;
    };
    match receive_probe_drag_source_send(
        socket_fd,
        source_id,
        callback_id,
        URI_LIST_MIME_TYPE,
        PAYLOAD,
    ) {
        Ok(size) => i32::try_from(size).unwrap_or(i32::MAX),
        Err(_) => -2,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeSendDataOfferReceive(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    socket_fd: i32,
    offer_id: i32,
) -> i32 {
    if offer_id == 0 {
        return -1;
    }
    send_probe_data_offer_receive(socket_fd, offer_id as u32, TEXT_MIME_TYPES[0]).unwrap_or(-2)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeSendDataOfferUriReceive(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    socket_fd: i32,
    offer_id: i32,
) -> i32 {
    if offer_id == 0 {
        return -1;
    }
    send_probe_data_offer_receive(socket_fd, offer_id as u32, URI_LIST_MIME_TYPE).unwrap_or(-2)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeTakeLinuxDragFd(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    core.take_linux_drag_fd()
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeTakeLinuxDragMimeKind(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    match core.take_linux_drag_mime_type().as_deref() {
        Some(URI_LIST_MIME_TYPE) => 2,
        Some(mime_type) if TEXT_MIME_TYPES.contains(&mime_type) => 1,
        Some(_) => -2,
        None => 0,
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeFinishLinuxDrag(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    accepted: u8,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    i32::try_from(core.finish_linux_drag(accepted != 0)).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeAndroidDragMotion(
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
    i32::try_from(core.android_drag_motion(f64::from(x), f64::from(y), time as u32))
        .unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeAndroidDropText(
    environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    value: jbyteArray,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    let Some(text) = java_byte_array(environment, value) else {
        return -2;
    };
    i32::try_from(core.android_drop_text(text)).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeAndroidDropUriList(
    environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    value: jbyteArray,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    let Some(uri_list) = java_byte_array(environment, value) else {
        return -2;
    };
    i32::try_from(core.android_drop_uri_list(uri_list)).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeCancelAndroidDrag(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    i32::try_from(core.cancel_android_drag()).unwrap_or(i32::MAX)
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
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeDataDeviceManagerBindCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.data_device_manager_bind_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeDataSourceCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.data_source_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeDataDeviceCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.data_device_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeDataOfferCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.data_offer_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeSetClipboardActive(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    active: u8,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    i32::try_from(core.set_clipboard_active(active != 0)).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeOfferAndroidClipboardText(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    i32::try_from(core.offer_android_clipboard_text()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeTakeAndroidPasteFd(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    core.take_android_paste_fd()
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeTakeLinuxCopyFd(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    core.take_linux_copy_fd()
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeTextInputManagerBindCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.text_input_manager_bind_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeTextInputCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.text_input_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeImeActive(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.ime_active()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeImeShowRequestCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.ime_show_request_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeImeHideRequestCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.ime_hide_request_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeImeSurroundingTextLength(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    core.ime_surrounding_text_length()
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeImeSurroundingCursor(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    core.ime_surrounding_cursor()
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeImeSurroundingAnchor(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    core.ime_surrounding_anchor()
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeImeContentHint(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    core.ime_content_hint()
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeImeContentPurpose(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    core.ime_content_purpose()
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeImeCursorRectangleComponent(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    component: i32,
) -> i32 {
    let (Some(core), Ok(component)) = (
        unsafe { (handle as *mut CompositorCore).as_ref() },
        usize::try_from(component),
    ) else {
        return -1;
    };
    core.ime_cursor_rectangle_component(component)
}
fn java_byte_array(environment: *mut std::ffi::c_void, value: jbyteArray) -> Option<Vec<u8>> {
    if environment.is_null() || value.is_null() {
        return None;
    }
    let environment = unsafe { JNIEnv::from_raw(environment.cast()).ok()? };
    let value = unsafe { JByteArray::from_raw(value) };
    environment.convert_byte_array(&value).ok()
}

fn copy_to_java_byte_array(
    environment: *mut std::ffi::c_void,
    destination: jbyteArray,
    source: &[u8],
) -> i32 {
    if environment.is_null() || destination.is_null() {
        return -1;
    }
    let environment = match unsafe { JNIEnv::from_raw(environment.cast()) } {
        Ok(environment) => environment,
        Err(_) => return -1,
    };
    let destination = unsafe { JByteArray::from_raw(destination) };
    let Ok(length) = environment.get_array_length(&destination) else {
        return -1;
    };
    let Ok(source_length) = i32::try_from(source.len()) else {
        return -2;
    };
    if length < source_length {
        return -2;
    }
    let source = source.iter().map(|byte| *byte as i8).collect::<Vec<_>>();
    if environment
        .set_byte_array_region(&destination, 0, &source)
        .is_err()
    {
        return -1;
    }
    source_length
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeImeCopySurroundingText(
    environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    destination: jbyteArray,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    let Some(text) = core.ime_surrounding_text() else {
        return -2;
    };
    copy_to_java_byte_array(environment, destination, text.as_bytes())
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeImeCommitText(
    environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    text: jbyteArray,
) -> i32 {
    let Some(text) =
        java_byte_array(environment, text).and_then(|text| String::from_utf8(text).ok())
    else {
        return -2;
    };
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    i32::try_from(core.ime_commit_text(text)).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeImeSetPreedit(
    environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    text: jbyteArray,
    cursor_begin: i32,
    cursor_end: i32,
) -> i32 {
    let Some(text) =
        java_byte_array(environment, text).and_then(|text| String::from_utf8(text).ok())
    else {
        return -2;
    };
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    i32::try_from(core.ime_set_preedit(text, cursor_begin, cursor_end)).unwrap_or(i32::MAX)
}
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeImeEditorAction(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    action: i32,
    time: i32,
) -> i32 {
    let Ok(action) = u32::try_from(action) else {
        return -2;
    };
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    i32::try_from(core.ime_editor_action(action, time as u32)).unwrap_or(i32::MAX)
}

#[cfg(target_os = "android")]
fn run_runtime_fd(executable_fd: i32) -> (i32, Vec<u8>) {
    if executable_fd < 0 {
        return (-libc::EBADF, Vec::new());
    }
    let inherited_fd = unsafe { libc::dup(executable_fd) };
    if inherited_fd < 0 {
        return (
            -io::Error::last_os_error()
                .raw_os_error()
                .unwrap_or(libc::EIO),
            Vec::new(),
        );
    }
    let flags = unsafe { libc::fcntl(inherited_fd, libc::F_GETFD) };
    if flags < 0
        || unsafe { libc::fcntl(inherited_fd, libc::F_SETFD, flags & !libc::FD_CLOEXEC) } < 0
    {
        unsafe { libc::close(inherited_fd) };
        return (
            -io::Error::last_os_error()
                .raw_os_error()
                .unwrap_or(libc::EIO),
            Vec::new(),
        );
    }
    let executable = match std::ffi::CString::new(format!("/proc/self/fd/{inherited_fd}")) {
        Ok(value) => value,
        Err(_) => {
            unsafe { libc::close(inherited_fd) };
            return (-libc::EINVAL, Vec::new());
        }
    };
    let argument = std::ffi::CString::new("archphene-runtime-module").unwrap();
    let arguments = [argument.as_ptr(), ptr::null()];
    let mut pipe = [-1; 2];
    if unsafe { libc::pipe2(pipe.as_mut_ptr(), libc::O_CLOEXEC) } != 0 {
        unsafe { libc::close(inherited_fd) };
        return (
            -io::Error::last_os_error()
                .raw_os_error()
                .unwrap_or(libc::EIO),
            Vec::new(),
        );
    }

    let child = unsafe { libc::fork() };
    if child == 0 {
        unsafe {
            libc::close(pipe[0]);
            libc::dup2(pipe[1], libc::STDOUT_FILENO);
            libc::dup2(pipe[1], libc::STDERR_FILENO);
            if pipe[1] > libc::STDERR_FILENO {
                libc::close(pipe[1]);
            }
            unsafe extern "C" {
                static mut environ: *mut *mut libc::c_char;
            }
            libc::execve(
                executable.as_ptr(),
                arguments.as_ptr(),
                environ.cast::<*const libc::c_char>(),
            );
            let message = b"runtime module exec failed\n";
            libc::write(
                libc::STDERR_FILENO,
                message.as_ptr().cast::<libc::c_void>(),
                message.len(),
            );
            libc::_exit(126);
        }
    }
    unsafe {
        libc::close(pipe[1]);
        libc::close(inherited_fd);
    }
    if child < 0 {
        unsafe { libc::close(pipe[0]) };
        return (
            -io::Error::last_os_error()
                .raw_os_error()
                .unwrap_or(libc::EIO),
            Vec::new(),
        );
    }

    let mut output = Vec::new();
    let mut buffer = [0u8; 1024];
    loop {
        let count = unsafe {
            libc::read(
                pipe[0],
                buffer.as_mut_ptr().cast::<libc::c_void>(),
                buffer.len(),
            )
        };
        if count == 0 {
            break;
        }
        if count < 0 {
            if io::Error::last_os_error().raw_os_error() == Some(libc::EINTR) {
                continue;
            }
            break;
        }
        let count = usize::try_from(count).unwrap_or(0);
        let remaining = 8191usize.saturating_sub(output.len());
        output.extend_from_slice(&buffer[..count.min(remaining)]);
    }
    unsafe { libc::close(pipe[0]) };

    let mut status = 0;
    while unsafe { libc::waitpid(child, &mut status, 0) } < 0 {
        if io::Error::last_os_error().raw_os_error() != Some(libc::EINTR) {
            return (-libc::ECHILD, output);
        }
    }
    let exit_code = if libc::WIFEXITED(status) {
        libc::WEXITSTATUS(status)
    } else if libc::WIFSIGNALED(status) {
        128 + libc::WTERMSIG(status)
    } else {
        -libc::ECHILD
    };
    (exit_code, output)
}

#[cfg(any(target_os = "android", test))]
const MAX_RUNTIME_LIBRARIES: usize = 510;
#[cfg(any(target_os = "android", test))]
const MAX_RUNTIME_LIBRARY_MANIFEST: usize = 128 * 1024;
#[cfg(any(target_os = "android", test))]
const MAX_RUNTIME_LINK_NAME: usize = 128;
const MAX_RUNTIME_ENVIRONMENT_MANIFEST: usize = 32 * 1024;
const MAX_RUNTIME_ENVIRONMENT_VARIABLES: usize = 64;
const MAX_RUNTIME_ARGUMENT_MANIFEST: usize = 32 * 1024;
const MAX_RUNTIME_ARGUMENTS: usize = 32;

#[cfg(any(target_os = "android", test))]
fn valid_runtime_link_name(name: &[u8]) -> bool {
    !name.is_empty()
        && name.len() <= MAX_RUNTIME_LINK_NAME
        && name != b"."
        && name != b".."
        && name != b"program"
        && name != b"loader"
        && name
            .iter()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(*byte, b'.' | b'_' | b'+' | b'-'))
}

#[cfg(any(target_os = "android", test))]
fn safe_runtime_program_name(name: &[u8]) -> bool {
    !name.is_empty()
        && name.len() <= 128
        && name.iter().all(|byte| {
            byte.is_ascii_alphanumeric() || matches!(*byte, b'@' | b'.' | b'_' | b'+' | b':' | b'-')
        })
}

#[cfg(any(target_os = "android", test))]
fn parse_runtime_library_manifest(manifest: &[u8]) -> Result<Vec<(i32, String)>, i32> {
    if manifest.is_empty()
        || manifest.len() > MAX_RUNTIME_LIBRARY_MANIFEST
        || !manifest.ends_with(b"\n")
    {
        return Err(libc::EINVAL);
    }
    let mut modules = Vec::new();
    for line in manifest[..manifest.len() - 1].split(|byte| *byte == b'\n') {
        if line.is_empty() || line.len() > MAX_RUNTIME_LINK_NAME + 16 {
            return Err(libc::EINVAL);
        }
        let mut fields = line.split(|byte| *byte == b'\t');
        let (Some(fd), Some(name), None) = (fields.next(), fields.next(), fields.next()) else {
            return Err(libc::EINVAL);
        };
        let Ok(fd) = std::str::from_utf8(fd) else {
            return Err(libc::EINVAL);
        };
        let Ok(fd) = fd.parse::<i32>() else {
            return Err(libc::EINVAL);
        };
        if fd < 0 || !valid_runtime_link_name(name) {
            return Err(libc::EINVAL);
        }
        let Ok(name) = std::str::from_utf8(name) else {
            return Err(libc::EINVAL);
        };
        if modules.iter().any(|(_, existing)| existing == name) {
            return Err(libc::EEXIST);
        }
        if modules.len() >= MAX_RUNTIME_LIBRARIES {
            return Err(libc::E2BIG);
        }
        modules.push((fd, name.to_owned()));
    }
    if modules.is_empty() {
        return Err(libc::EINVAL);
    }
    Ok(modules)
}

#[cfg(any(target_os = "android", test))]
fn parse_runtime_environment(
    manifest: &[u8],
) -> Result<Vec<(std::ffi::CString, std::ffi::CString)>, i32> {
    if manifest.is_empty() {
        return Ok(Vec::new());
    }
    if manifest.len() > MAX_RUNTIME_ENVIRONMENT_MANIFEST || !manifest.ends_with(b"\n") {
        return Err(libc::EINVAL);
    }
    let mut result = Vec::new();
    for line in manifest[..manifest.len() - 1].split(|byte| *byte == b'\n') {
        let Some(separator) = line.iter().position(|byte| *byte == b'=') else {
            return Err(libc::EINVAL);
        };
        let key = &line[..separator];
        let value = &line[separator + 1..];
        if key.is_empty()
            || key.len() > 64
            || !(key[0].is_ascii_uppercase() || key[0] == b'_')
            || !key
                .iter()
                .all(|byte| byte.is_ascii_uppercase() || byte.is_ascii_digit() || *byte == b'_')
            || value.len() > 4096
            || value.iter().any(|byte| matches!(*byte, b'\0' | b'\r'))
            || result.len() >= MAX_RUNTIME_ENVIRONMENT_VARIABLES
        {
            return Err(libc::EINVAL);
        }
        let key = std::ffi::CString::new(key).map_err(|_| libc::EINVAL)?;
        let value = std::ffi::CString::new(value).map_err(|_| libc::EINVAL)?;
        if result.iter().any(|(existing, _)| existing == &key) {
            return Err(libc::EEXIST);
        }
        result.push((key, value));
    }
    Ok(result)
}

#[cfg(any(target_os = "android", test))]
fn parse_runtime_arguments(manifest: &[u8]) -> Result<Vec<std::ffi::CString>, i32> {
    if manifest.is_empty() {
        return Ok(Vec::new());
    }
    if manifest.len() > MAX_RUNTIME_ARGUMENT_MANIFEST || !manifest.ends_with(b"\n") {
        return Err(libc::EINVAL);
    }
    let mut result = Vec::new();
    for argument in manifest[..manifest.len() - 1].split(|byte| *byte == b'\n') {
        if argument.len() > 4096
            || argument.iter().any(|byte| matches!(*byte, b'\0' | b'\r'))
            || result.len() >= MAX_RUNTIME_ARGUMENTS
        {
            return Err(libc::EINVAL);
        }
        result.push(std::ffi::CString::new(argument).map_err(|_| libc::EINVAL)?);
    }
    Ok(result)
}
#[cfg(any(target_os = "android", test))]
fn runtime_plugin_alias(name: &str) -> Option<&'static str> {
    if name.starts_with("libpipewire-module-") && name.ends_with(".so") {
        return Some("pipewire-0.3");
    }
    if name.starts_with("libgst") && name.ends_with(".so") {
        return Some("gstreamer-1.0");
    }
    match name {
        "libqwayland.so" => Some("platforms"),
        "libxdg-shell.so" => Some("wayland-shell-integration"),
        "libspa-support.so" => Some("spa-0.2/support"),
        "libspa-videoconvert.so" => Some("spa-0.2/videoconvert"),
        _ => None,
    }
}

#[cfg(target_os = "android")]
fn cleanup_runtime_fd_view(inherited: &mut Vec<i32>, links: &[PathBuf]) {
    for opened in inherited.drain(..) {
        unsafe { libc::close(opened) };
    }
    for link in links {
        let _ = std::fs::remove_file(link);
    }
}

#[cfg(target_os = "android")]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum RuntimeExecutionState {
    Starting,
    Running(libc::pid_t),
    Cancelling(libc::pid_t),
    Cancelled,
}

#[cfg(target_os = "android")]
fn runtime_executions() -> &'static Mutex<std::collections::HashMap<i64, RuntimeExecutionState>> {
    static EXECUTIONS: std::sync::OnceLock<
        Mutex<std::collections::HashMap<i64, RuntimeExecutionState>>,
    > = std::sync::OnceLock::new();
    EXECUTIONS.get_or_init(|| Mutex::new(std::collections::HashMap::new()))
}

#[cfg(target_os = "android")]
struct RuntimeExecutionGuard {
    id: i64,
}

#[cfg(target_os = "android")]
impl RuntimeExecutionGuard {
    fn begin(id: i64) -> Result<Self, i32> {
        if id <= 0 {
            return Ok(Self { id: 0 });
        }
        let mut executions = runtime_executions().lock().map_err(|_| libc::EIO)?;
        match executions.entry(id) {
            std::collections::hash_map::Entry::Vacant(entry) => {
                entry.insert(RuntimeExecutionState::Starting);
                Ok(Self { id })
            }
            std::collections::hash_map::Entry::Occupied(entry)
                if *entry.get() == RuntimeExecutionState::Cancelled =>
            {
                entry.remove();
                Err(libc::ECANCELED)
            }
            std::collections::hash_map::Entry::Occupied(_) => Err(libc::EBUSY),
        }
    }

    fn register_process_group(&self, pgid: libc::pid_t) -> bool {
        if self.id <= 0 {
            return true;
        }
        let Ok(mut executions) = runtime_executions().lock() else {
            return false;
        };
        match executions.get_mut(&self.id) {
            Some(state @ RuntimeExecutionState::Starting) => {
                *state = RuntimeExecutionState::Running(pgid);
                true
            }
            Some(state @ RuntimeExecutionState::Cancelled) => {
                *state = RuntimeExecutionState::Cancelling(pgid);
                false
            }
            _ => false,
        }
    }
}

#[cfg(target_os = "android")]
impl Drop for RuntimeExecutionGuard {
    fn drop(&mut self) {
        if self.id <= 0 {
            return;
        }
        if let Ok(mut executions) = runtime_executions().lock() {
            executions.remove(&self.id);
        }
    }
}

#[cfg(target_os = "android")]
fn cancel_runtime_execution(id: i64) {
    if id <= 0 {
        return;
    }
    let pgid = {
        let Ok(mut executions) = runtime_executions().lock() else {
            return;
        };
        let Some(state) = executions.get_mut(&id) else {
            executions.insert(id, RuntimeExecutionState::Cancelled);
            return;
        };
        match *state {
            RuntimeExecutionState::Starting => {
                *state = RuntimeExecutionState::Cancelled;
                0
            }
            RuntimeExecutionState::Running(pgid) => {
                *state = RuntimeExecutionState::Cancelling(pgid);
                pgid
            }
            RuntimeExecutionState::Cancelling(_) | RuntimeExecutionState::Cancelled => 0,
        }
    };
    if pgid <= 0 {
        return;
    }
    unsafe {
        libc::kill(-pgid, libc::SIGTERM);
    }
    std::thread::spawn(move || {
        std::thread::sleep(std::time::Duration::from_millis(750));
        let should_kill = runtime_executions()
            .lock()
            .map(|executions| executions.get(&id) == Some(&RuntimeExecutionState::Cancelling(pgid)))
            .unwrap_or(false);
        if should_kill {
            unsafe {
                libc::kill(-pgid, libc::SIGKILL);
            }
        }
    });
}

#[cfg(target_os = "android")]
fn forget_runtime_execution(id: i64) {
    if id <= 0 {
        return;
    }
    if let Ok(mut executions) = runtime_executions().lock() {
        executions.remove(&id);
    }
}

#[cfg(target_os = "android")]
unsafe fn configure_runtime_child(parent: libc::pid_t) -> bool {
    unsafe {
        libc::setpgid(0, 0) == 0
            && libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGKILL) == 0
            && libc::getppid() == parent
    }
}

#[cfg(target_os = "android")]
fn establish_runtime_process_group(child: libc::pid_t) -> bool {
    if unsafe { libc::setpgid(child, child) } == 0 {
        return true;
    }
    unsafe { libc::getpgid(child) == child }
}

#[cfg(target_os = "android")]
fn terminate_uid_processes(signal: i32) -> i32 {
    use std::os::unix::fs::MetadataExt;

    let own_pid = unsafe { libc::getpid() };
    let own_uid = unsafe { libc::geteuid() };
    let Ok(entries) = std::fs::read_dir("/proc") else {
        return -libc::EIO;
    };
    let mut terminated = 0;
    for entry in entries.flatten() {
        let Some(name) = entry.file_name().to_str().map(str::to_owned) else {
            continue;
        };
        let Ok(pid) = name.parse::<libc::pid_t>() else {
            continue;
        };
        if pid <= 1 || pid == own_pid {
            continue;
        }
        let Ok(metadata) = entry.metadata() else {
            continue;
        };
        if metadata.uid() != own_uid {
            continue;
        }
        if unsafe { libc::kill(pid, signal) } == 0 {
            terminated += 1;
        }
    }
    terminated
}

#[cfg(target_os = "android")]
fn run_glibc_fds(
    program_fd: i32,
    loader_fd: i32,
    library_manifest: &[u8],
    link_root: &[u8],
    environment_manifest: &[u8],
    program_name: &[u8],
    argument_manifest: &[u8],
    execution_id: i64,
) -> (i32, Vec<u8>) {
    let execution = match RuntimeExecutionGuard::begin(execution_id) {
        Ok(execution) => execution,
        Err(error) => return (-error, Vec::new()),
    };
    let libraries = match parse_runtime_library_manifest(library_manifest) {
        Ok(libraries) => libraries,
        Err(error) => return (-error, Vec::new()),
    };
    let environment = match parse_runtime_environment(environment_manifest) {
        Ok(environment) => environment,
        Err(error) => return (-error, Vec::new()),
    };
    let runtime_arguments = match parse_runtime_arguments(argument_manifest) {
        Ok(arguments) => arguments,
        Err(error) => return (-error, Vec::new()),
    };
    if !safe_runtime_program_name(program_name) {
        return (-libc::EINVAL, Vec::new());
    }
    let program_name = match std::ffi::CString::new(program_name) {
        Ok(value) => value,
        Err(_) => return (-libc::EINVAL, Vec::new()),
    };
    let Ok(link_root) = std::str::from_utf8(link_root) else {
        return (-libc::EINVAL, Vec::new());
    };
    let link_root = PathBuf::from(link_root);
    let mut source_fds = Vec::with_capacity(libraries.len() + 2);
    source_fds.extend([program_fd, loader_fd]);
    source_fds.extend(libraries.iter().map(|(fd, _)| *fd));
    if source_fds.iter().any(|fd| *fd < 0) {
        return (-libc::EBADF, Vec::new());
    }
    let mut link_names = Vec::with_capacity(libraries.len() + 2);
    link_names.extend(["program".to_owned(), "loader".to_owned()]);
    link_names.extend(libraries.into_iter().map(|(_, name)| name));

    let mut inherited = Vec::with_capacity(source_fds.len());
    for source in source_fds {
        let fd = unsafe { libc::dup(source) };
        if fd < 0 {
            let error = io::Error::last_os_error()
                .raw_os_error()
                .unwrap_or(libc::EIO);
            cleanup_runtime_fd_view(&mut inherited, &[]);
            return (-error, Vec::new());
        }
        let flags = unsafe { libc::fcntl(fd, libc::F_GETFD) };
        if flags < 0 || unsafe { libc::fcntl(fd, libc::F_SETFD, flags & !libc::FD_CLOEXEC) } < 0 {
            let error = io::Error::last_os_error()
                .raw_os_error()
                .unwrap_or(libc::EIO);
            unsafe { libc::close(fd) };
            cleanup_runtime_fd_view(&mut inherited, &[]);
            return (-error, Vec::new());
        }
        inherited.push(fd);
    }

    let mut links = link_names
        .iter()
        .map(|name| link_root.join(name))
        .collect::<Vec<_>>();
    for (index, (link, fd)) in links.iter().zip(inherited.iter()).enumerate() {
        let target = if index == 0 {
            link_root.join(".program")
        } else if index == 1 {
            PathBuf::from(format!("/proc/self/fd/{fd}"))
        } else {
            link_root.join(format!(".library-{}", index - 2))
        };
        let _ = std::fs::remove_file(link);
        if std::os::unix::fs::symlink(target, link).is_err() {
            cleanup_runtime_fd_view(&mut inherited, &links);
            return (-libc::EIO, Vec::new());
        }
    }
    let base_link_count = links.len();
    for index in 0..base_link_count {
        let name = &link_names[index];
        let Some(directory) = runtime_plugin_alias(name) else {
            continue;
        };
        let plugin_directory = link_root.join(directory);
        if std::fs::create_dir_all(&plugin_directory).is_err() {
            cleanup_runtime_fd_view(&mut inherited, &links);
            return (-libc::EIO, Vec::new());
        }
        let alias = plugin_directory.join(name);
        let _ = std::fs::remove_file(&alias);
        if std::os::unix::fs::symlink(link_root.join(format!(".library-{}", index - 2)), &alias)
            .is_err()
        {
            cleanup_runtime_fd_view(&mut inherited, &links);
            return (-libc::EIO, Vec::new());
        }
        links.push(alias);
    }

    let loader = match std::ffi::CString::new(links[1].as_os_str().as_encoded_bytes()) {
        Ok(value) => value,
        Err(_) => {
            cleanup_runtime_fd_view(&mut inherited, &links);
            return (-libc::EINVAL, Vec::new());
        }
    };
    let library_path = std::ffi::CString::new("--library-path").unwrap();
    let argv0 = std::ffi::CString::new("--argv0").unwrap();
    let directory = match std::ffi::CString::new(link_root.as_os_str().as_encoded_bytes()) {
        Ok(value) => value,
        Err(_) => {
            cleanup_runtime_fd_view(&mut inherited, &links);
            return (-libc::EINVAL, Vec::new());
        }
    };
    let program = match std::ffi::CString::new(links[0].as_os_str().as_encoded_bytes()) {
        Ok(value) => value,
        Err(_) => {
            cleanup_runtime_fd_view(&mut inherited, &links);
            return (-libc::EINVAL, Vec::new());
        }
    };
    let supervisor_name = std::ffi::CString::new("archphene-runtime-supervisor").unwrap();
    let supervisor = link_names
        .iter()
        .position(|name| name == "archphene-runtime-supervisor")
        .and_then(|index| std::ffi::CString::new(links[index].as_os_str().as_encoded_bytes()).ok());
    let mut arguments = if let Some(supervisor) = supervisor.as_ref() {
        vec![
            loader.as_ptr(),
            library_path.as_ptr(),
            directory.as_ptr(),
            argv0.as_ptr(),
            supervisor_name.as_ptr(),
            supervisor.as_ptr(),
            loader.as_ptr(),
            directory.as_ptr(),
            program_name.as_ptr(),
            program.as_ptr(),
        ]
    } else {
        vec![
            loader.as_ptr(),
            library_path.as_ptr(),
            directory.as_ptr(),
            argv0.as_ptr(),
            program_name.as_ptr(),
            program.as_ptr(),
        ]
    };
    arguments.extend(runtime_arguments.iter().map(|argument| argument.as_ptr()));
    arguments.push(ptr::null());
    let mut pipe = [-1; 2];
    if unsafe { libc::pipe2(pipe.as_mut_ptr(), libc::O_CLOEXEC) } != 0 {
        let error = io::Error::last_os_error()
            .raw_os_error()
            .unwrap_or(libc::EIO);
        cleanup_runtime_fd_view(&mut inherited, &links);
        return (-error, Vec::new());
    }

    let parent = unsafe { libc::getpid() };
    let child = unsafe { libc::fork() };
    if child == 0 {
        unsafe {
            if !configure_runtime_child(parent) {
                libc::_exit(125);
            }
            libc::close(pipe[0]);
            libc::dup2(pipe[1], libc::STDOUT_FILENO);
            libc::dup2(pipe[1], libc::STDERR_FILENO);
            if pipe[1] > libc::STDERR_FILENO {
                libc::close(pipe[1]);
            }
            for (key, value) in &environment {
                if libc::setenv(key.as_ptr(), value.as_ptr(), 1) != 0 {
                    libc::_exit(125);
                }
            }

            unsafe extern "C" {
                static mut environ: *mut *mut libc::c_char;
            }
            libc::execve(
                loader.as_ptr(),
                arguments.as_ptr(),
                environ.cast::<*const libc::c_char>(),
            );
            let message = b"glibc runtime exec failed\n";
            libc::write(
                libc::STDERR_FILENO,
                message.as_ptr().cast::<libc::c_void>(),
                message.len(),
            );
            libc::_exit(126);
        }
    }
    let fork_error = io::Error::last_os_error()
        .raw_os_error()
        .unwrap_or(libc::EIO);
    unsafe { libc::close(pipe[1]) };
    for opened in inherited.drain(..) {
        unsafe { libc::close(opened) };
    }
    if child < 0 {
        unsafe { libc::close(pipe[0]) };
        cleanup_runtime_fd_view(&mut inherited, &links);
        return (-fork_error, Vec::new());
    }
    if !establish_runtime_process_group(child) {
        unsafe {
            libc::kill(child, libc::SIGKILL);
            libc::waitpid(child, ptr::null_mut(), 0);
            libc::close(pipe[0]);
        }
        cleanup_runtime_fd_view(&mut inherited, &links);
        return (-libc::EIO, Vec::new());
    }
    if !execution.register_process_group(child) {
        unsafe {
            libc::kill(-child, libc::SIGKILL);
        }
    }

    let mut output = Vec::new();
    let mut buffer = [0u8; 1024];
    loop {
        let count = unsafe {
            libc::read(
                pipe[0],
                buffer.as_mut_ptr().cast::<libc::c_void>(),
                buffer.len(),
            )
        };
        if count == 0 {
            break;
        }
        if count < 0 {
            if io::Error::last_os_error().raw_os_error() == Some(libc::EINTR) {
                continue;
            }
            break;
        }
        let count = usize::try_from(count).unwrap_or(0);
        let limit = 64 * 1024 - 1usize;
        if count >= limit {
            output.clear();
            output.extend_from_slice(&buffer[count - limit..count]);
        } else {
            let overflow = output.len().saturating_add(count).saturating_sub(limit);
            if overflow > 0 {
                output.drain(..overflow);
            }
            output.extend_from_slice(&buffer[..count]);
        }
    }
    unsafe { libc::close(pipe[0]) };
    let mut status = 0;
    while unsafe { libc::waitpid(child, &mut status, 0) } < 0 {
        if io::Error::last_os_error().raw_os_error() != Some(libc::EINTR) {
            unsafe {
                libc::kill(-child, libc::SIGKILL);
            }
            cleanup_runtime_fd_view(&mut inherited, &links);
            return (-libc::ECHILD, output);
        }
    }
    unsafe {
        libc::kill(-child, libc::SIGKILL);
    }
    cleanup_runtime_fd_view(&mut inherited, &links);
    let exit_code = if libc::WIFEXITED(status) {
        libc::WEXITSTATUS(status)
    } else if libc::WIFSIGNALED(status) {
        128 + libc::WTERMSIG(status)
    } else {
        -libc::ECHILD
    };
    (exit_code, output)
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_bridge_RuntimeFdLauncher_nativeRunGlibc(
    environment: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
    program_fd: i32,
    loader_fd: i32,
    library_manifest: jbyteArray,
    link_directory: jbyteArray,
    runtime_environment: jbyteArray,
    program_name: jbyteArray,
    runtime_arguments: jbyteArray,
    execution_id: i64,
    output: jbyteArray,
) -> i32 {
    let (
        Some(library_manifest),
        Some(link_directory),
        Some(runtime_environment),
        Some(program_name),
        Some(runtime_arguments),
    ) = (
        java_byte_array(environment, library_manifest),
        java_byte_array(environment, link_directory),
        java_byte_array(environment, runtime_environment),
        java_byte_array(environment, program_name),
        java_byte_array(environment, runtime_arguments),
    )
    else {
        return -libc::EINVAL;
    };
    let (exit_code, captured) = run_glibc_fds(
        program_fd,
        loader_fd,
        &library_manifest,
        &link_directory,
        &runtime_environment,
        &program_name,
        &runtime_arguments,
        execution_id,
    );
    if copy_to_java_byte_array(environment, output, &captured) < 0 {
        return -libc::EFAULT;
    }
    exit_code
}
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_bridge_RuntimeFdLauncher_nativeCancelGlibc(
    _environment: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
    execution_id: i64,
) {
    cancel_runtime_execution(execution_id);
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_bridge_RuntimeFdLauncher_nativeForgetGlibc(
    _environment: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
    execution_id: i64,
) {
    forget_runtime_execution(execution_id);
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_bridge_RuntimeFdLauncher_nativeTerminateUidProcesses(
    _environment: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
) -> i32 {
    terminate_uid_processes(libc::SIGKILL)
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_bridge_RuntimeFdLauncher_nativeRunFd(
    environment: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
    fd: i32,
    output: jbyteArray,
) -> i32 {
    let (exit_code, captured) = run_runtime_fd(fd);
    if copy_to_java_byte_array(environment, output, &captured) < 0 {
        return -libc::EFAULT;
    }
    exit_code
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_bridge_NativeCompositor_nativeCreate(
    environment: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
    socket_path: jbyteArray,
) -> i64 {
    let Some(socket_path) =
        java_byte_array(environment, socket_path).and_then(|path| String::from_utf8(path).ok())
    else {
        return 0;
    };
    let Ok(mut core) = CompositorCore::new() else {
        return 0;
    };
    if core.bind_socket(Path::new(&socket_path)).is_err() {
        return 0;
    }
    Box::into_raw(Box::new(core)) as i64
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_bridge_NativeCompositor_nativeInt(
    _environment: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
    handle: i64,
    command: i32,
    a: i32,
    b: i32,
    c: i32,
    d: i32,
    e: i32,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    let value = match command {
        1 => {
            return core
                .dispatch_once()
                .map_or(-2, |count| i32::try_from(count).unwrap_or(i32::MAX));
        }
        2 => return i32::try_from(core.configure_output(a, b, c)).unwrap_or(i32::MAX),
        3 => core.accepted_client_count(),
        4 => core.surface_commit_count(),
        5 => core.last_frame_width(),
        6 => core.last_frame_height(),
        7 => core.pending_frame_callback_count(),
        8 => return i32::try_from(core.present_frame(a as u32)).unwrap_or(i32::MAX),
        9 => {
            return i32::try_from(core.pointer_motion(f64::from(a), f64::from(b), c as u32))
                .unwrap_or(i32::MAX);
        }
        10 => return i32::try_from(core.pointer_button(a != 0, b as u32)).unwrap_or(i32::MAX),
        11 => {
            return i32::try_from(core.pointer_axis(
                f64::from(a) / 1000.0,
                f64::from(b) / 1000.0,
                c as u32,
            ))
            .unwrap_or(i32::MAX);
        }
        12 => return i32::try_from(core.pointer_leave()).unwrap_or(i32::MAX),
        13 => {
            return i32::try_from(core.keyboard_key(a as u32, b != 0, c as u32))
                .unwrap_or(i32::MAX);
        }
        14 => {
            return i32::try_from(core.touch_down(a, f64::from(b), f64::from(c), d as u32))
                .unwrap_or(i32::MAX);
        }
        15 => {
            return i32::try_from(core.touch_motion(a, f64::from(b), f64::from(c), d as u32))
                .unwrap_or(i32::MAX);
        }
        16 => return i32::try_from(core.touch_up(a, b as u32)).unwrap_or(i32::MAX),
        17 => return i32::try_from(core.touch_cancel()).unwrap_or(i32::MAX),
        18 => core.ime_active(),
        19 => core.ime_show_request_count(),
        20 => core.ime_hide_request_count(),
        21 => return core.ime_surrounding_text_length(),
        22 => return core.ime_surrounding_cursor(),
        23 => return core.ime_surrounding_anchor(),
        24 => return core.ime_content_hint(),
        25 => return core.ime_content_purpose(),
        26 => return core.ime_cursor_rectangle_component(usize::try_from(a).unwrap_or(usize::MAX)),
        27 => {
            return i32::try_from(core.ime_delete_surrounding(
                u32::try_from(a).unwrap_or(0),
                u32::try_from(b).unwrap_or(0),
            ))
            .unwrap_or(i32::MAX);
        }
        28 => return i32::try_from(core.ime_editor_action(a as u32, b as u32)).unwrap_or(i32::MAX),
        29 => return i32::try_from(core.set_clipboard_active(a != 0)).unwrap_or(i32::MAX),
        30 => return i32::try_from(core.offer_android_clipboard_text()).unwrap_or(i32::MAX),
        31 => return core.take_android_paste_fd(),
        32 => return core.take_linux_copy_fd(),
        33 => core.cursor_width(),
        34 => core.cursor_height(),
        35 => return core.cursor_hotspot_component(a as u32),
        36 => return i32::try_from(core.dismiss_popups()).unwrap_or(i32::MAX),
        37 => return i32::try_from(core.pending_damage_count()).unwrap_or(i32::MAX),
        38 => return core.pending_damage_component(u32::try_from(a).unwrap_or(u32::MAX)),
        39 => return i32::try_from(core.xdg_popup_count()).unwrap_or(i32::MAX),
        40 => {
            return i32::try_from(core.swipe_begin(a.max(0) as u32, b as u32)).unwrap_or(i32::MAX);
        }
        41 => {
            return i32::try_from(core.swipe_update(
                f64::from(a) / 1000.0,
                f64::from(b) / 1000.0,
                c as u32,
            ))
            .unwrap_or(i32::MAX);
        }
        42 => return i32::try_from(core.swipe_end(a != 0, b as u32)).unwrap_or(i32::MAX),
        43 => {
            return i32::try_from(core.pinch_begin(a.max(0) as u32, b as u32)).unwrap_or(i32::MAX);
        }
        44 => {
            return i32::try_from(core.pinch_update(
                f64::from(a) / 1000.0,
                f64::from(b) / 1000.0,
                f64::from(c) / 1000.0,
                f64::from(d) / 1000.0,
                e as u32,
            ))
            .unwrap_or(i32::MAX);
        }
        45 => return i32::try_from(core.pinch_end(a != 0, b as u32)).unwrap_or(i32::MAX),
        46 => return i32::try_from(core.hold_begin(a.max(0) as u32, b as u32)).unwrap_or(i32::MAX),
        47 => return i32::try_from(core.hold_end(a != 0, b as u32)).unwrap_or(i32::MAX),
        48 => return i32::try_from(core.set_toplevel_tiling(a != 0)).unwrap_or(i32::MAX),
        49 => return i32::try_from(core.window_count()).unwrap_or(i32::MAX),
        50 => return i32::try_from(core.window_change_serial()).unwrap_or(i32::MAX),
        51 => return core.window_component(a as u32, b as u32),
        52 => return i32::try_from(core.activate_window(a as u32)).unwrap_or(i32::MAX),
        53 => return i32::try_from(core.configure_window(a as u32, b, c)).unwrap_or(i32::MAX),
        54 => return i32::try_from(core.close_window(a as u32)).unwrap_or(i32::MAX),
        55 => return i32::try_from(core.text_input_count()).unwrap_or(i32::MAX),
        56 => return i32::try_from(core.pointer_count()).unwrap_or(i32::MAX),
        57 => return i32::try_from(core.touch_count()).unwrap_or(i32::MAX),
        58 => return core.popup_component(a as u32, b as u32),
        59 => {
            return i32::try_from(core.android_drag_motion(f64::from(a), f64::from(b), c as u32))
                .unwrap_or(i32::MAX);
        }
        60 => return i32::try_from(core.cancel_android_drag()).unwrap_or(i32::MAX),
        61 => return core.take_linux_drag_fd(),
        62 => return i32::try_from(core.finish_linux_drag(a != 0)).unwrap_or(i32::MAX),
        63 => {
            return core.linux_drag_mime_type().map_or(-1, |mime_type| {
                i32::try_from(mime_type.len()).unwrap_or(i32::MAX)
            });
        }
        _ => return -3,
    };
    i32::try_from(value).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_bridge_NativeCompositor_nativeBytes(
    environment: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
    handle: i64,
    command: i32,
    value: jbyteArray,
    a: i32,
    b: i32,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    match command {
        1 => {
            let Some(text) = core.ime_surrounding_text() else {
                return -2;
            };
            copy_to_java_byte_array(environment, value, text.as_bytes())
        }
        2 | 3 => {
            let Some(text) =
                java_byte_array(environment, value).and_then(|text| String::from_utf8(text).ok())
            else {
                return -2;
            };
            let count = if command == 2 {
                core.ime_commit_text(text)
            } else {
                core.ime_set_preedit(text, a, b)
            };
            i32::try_from(count).unwrap_or(i32::MAX)
        }
        4 | 5 => {
            let Some(text) = core.window_text(a as u32, command == 4) else {
                return -2;
            };
            copy_to_java_byte_array(environment, value, text.as_bytes())
        }
        6 | 7 => {
            let Some(payload) = java_byte_array(environment, value) else {
                return -2;
            };
            let count = if command == 6 {
                core.android_drop_text(payload)
            } else {
                core.android_drop_uri_list(payload)
            };
            i32::try_from(count).unwrap_or(i32::MAX)
        }
        8 => {
            let Some(mime_type) = core.take_linux_drag_mime_type() else {
                return -2;
            };
            copy_to_java_byte_array(environment, value, mime_type.as_bytes())
        }
        _ => -3,
    }
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_bridge_NativeCompositor_nativeBitmap(
    environment: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
    handle: i64,
    command: i32,
    a: i32,
    bitmap: *mut std::ffi::c_void,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    match command {
        1 => copy_last_frame_to_bitmap(core, environment, bitmap),
        2 => core
            .state
            .cursor_frame
            .as_ref()
            .map_or(-1, |frame| copy_frame_to_bitmap(frame, environment, bitmap)),
        3 => core
            .window_frame(a as u32)
            .as_ref()
            .map_or(-1, |frame| copy_frame_to_bitmap(frame, environment, bitmap)),
        _ => -3,
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_bridge_NativeCompositor_nativeDestroy(
    _environment: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
    handle: i64,
) {
    if handle > 0 {
        drop(unsafe { Box::from_raw(handle as *mut CompositorCore) });
    }
}
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeImeDeleteSurrounding(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    before_length: i32,
    after_length: i32,
) -> i32 {
    let (Ok(before_length), Ok(after_length)) =
        (u32::try_from(before_length), u32::try_from(after_length))
    else {
        return -2;
    };
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    i32::try_from(core.ime_delete_surrounding(before_length, after_length)).unwrap_or(i32::MAX)
}
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativePendingFrameCallbackCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.pending_frame_callback_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativePendingDamageCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.pending_damage_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativePendingDamageComponent(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    component: i32,
) -> i32 {
    let Ok(component) = u32::try_from(component) else {
        return 0;
    };
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    core.pending_damage_component(component)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativePresentFrame(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    time: i32,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    i32::try_from(core.present_frame(time as u32)).unwrap_or(i32::MAX)
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
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeSwipeBegin(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    fingers: i32,
    time: i32,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    i32::try_from(core.swipe_begin(fingers.max(0) as u32, time as u32)).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeSwipeUpdate(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    dx_milli: i32,
    dy_milli: i32,
    time: i32,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    i32::try_from(core.swipe_update(
        f64::from(dx_milli) / 1000.0,
        f64::from(dy_milli) / 1000.0,
        time as u32,
    ))
    .unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeSwipeEnd(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    cancelled: bool,
    time: i32,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    i32::try_from(core.swipe_end(cancelled, time as u32)).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativePinchBegin(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    fingers: i32,
    time: i32,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    i32::try_from(core.pinch_begin(fingers.max(0) as u32, time as u32)).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativePinchUpdate(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    dx_milli: i32,
    dy_milli: i32,
    scale_milli: i32,
    rotation_milli: i32,
    time: i32,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    i32::try_from(core.pinch_update(
        f64::from(dx_milli) / 1000.0,
        f64::from(dy_milli) / 1000.0,
        f64::from(scale_milli) / 1000.0,
        f64::from(rotation_milli) / 1000.0,
        time as u32,
    ))
    .unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativePinchEnd(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    cancelled: bool,
    time: i32,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    i32::try_from(core.pinch_end(cancelled, time as u32)).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeHoldBegin(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    fingers: i32,
    time: i32,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    i32::try_from(core.hold_begin(fingers.max(0) as u32, time as u32)).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeHoldEnd(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    cancelled: bool,
    time: i32,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    i32::try_from(core.hold_end(cancelled, time as u32)).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeGestureEventCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.gesture_event_count()).unwrap_or(i32::MAX)
}
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativePointerEnterSerial(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    core.pointer_enter_serial() as i32
}
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeCursorWidth(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.cursor_width()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeCursorHeight(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.cursor_height()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeCursorHotspot(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    component: i32,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    core.cursor_hotspot_component(component as u32)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeCopyCursorToBitmap(
    environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    bitmap: *mut std::ffi::c_void,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    let Some(frame) = core.state.cursor_frame.as_ref() else {
        return -1;
    };
    #[cfg(target_os = "android")]
    {
        return copy_frame_to_bitmap(frame, environment, bitmap);
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = (environment, bitmap, frame);
        -2
    }
}
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeTouchCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.touch_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeTouchEventCount(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_ref() }) else {
        return -1;
    };
    i32::try_from(core.touch_event_count()).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeTouchDown(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    id: i32,
    x: i32,
    y: i32,
    time: i32,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    i32::try_from(core.touch_down(id, f64::from(x), f64::from(y), time as u32)).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeTouchMotion(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    id: i32,
    x: i32,
    y: i32,
    time: i32,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    i32::try_from(core.touch_motion(id, f64::from(x), f64::from(y), time as u32))
        .unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeTouchUp(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    id: i32,
    time: i32,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    i32::try_from(core.touch_up(id, time as u32)).unwrap_or(i32::MAX)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeTouchCancel(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    i32::try_from(core.touch_cancel()).unwrap_or(i32::MAX)
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
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativePointerAxis(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
    horizontal_milli: i32,
    vertical_milli: i32,
    time: i32,
) -> i32 {
    let Some(core) = (unsafe { (handle as *mut CompositorCore).as_mut() }) else {
        return -1;
    };
    i32::try_from(core.pointer_axis(
        f64::from(horizontal_milli) / 1000.0,
        f64::from(vertical_milli) / 1000.0,
        time as u32,
    ))
    .unwrap_or(i32::MAX)
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
    fn encodes_xdg_toplevel_states_for_the_wire() {
        let encoded = encode_xdg_toplevel_states(&[
            xdg_toplevel::State::Maximized,
            xdg_toplevel::State::Activated,
        ]);
        let values = encoded
            .chunks_exact(4)
            .map(|value| u32::from_ne_bytes(value.try_into().expect("state width")))
            .collect::<Vec<_>>();
        assert_eq!(
            values,
            vec![
                xdg_toplevel::State::Maximized as u32,
                xdg_toplevel::State::Activated as u32,
            ]
        );
    }

    #[test]
    fn validates_runtime_program_names() {
        assert!(safe_runtime_program_name(b"glmark2-es2-wayland"));
        assert!(safe_runtime_program_name(b"app@profile:1"));
        for name in [
            b"".as_slice(),
            b"../app",
            b"app/name",
            b"app name",
            b"app\0name",
        ] {
            assert!(!safe_runtime_program_name(name), "{name:?}");
        }
        assert!(!safe_runtime_program_name(&vec![b'a'; 129]));
    }

    #[test]
    fn parses_bounded_runtime_library_manifests() {
        let modules = parse_runtime_library_manifest(
            b"14\tlibc.so.6\n15\tlibarchphene_probe_dependency.so\n",
        )
        .expect("valid runtime library manifest");
        assert_eq!(
            modules,
            vec![
                (14, "libc.so.6".to_owned()),
                (15, "libarchphene_probe_dependency.so".to_owned()),
            ]
        );
    }

    #[test]
    fn maps_runtime_plugins_to_standard_qt_directories() {
        assert_eq!(runtime_plugin_alias("libqwayland.so"), Some("platforms"));
        assert_eq!(
            runtime_plugin_alias("libxdg-shell.so"),
            Some("wayland-shell-integration")
        );
        assert_eq!(runtime_plugin_alias("libQt6Core.so.6"), None);
        assert_eq!(
            runtime_plugin_alias("libpipewire-module-protocol-native.so"),
            Some("pipewire-0.3")
        );
        assert_eq!(
            runtime_plugin_alias("libspa-support.so"),
            Some("spa-0.2/support")
        );
        assert_eq!(
            runtime_plugin_alias("libspa-videoconvert.so"),
            Some("spa-0.2/videoconvert")
        );
        assert_eq!(
            runtime_plugin_alias("libgstpipewire.so"),
            Some("gstreamer-1.0")
        );
        assert_eq!(runtime_plugin_alias("libgstreamer-1.0.so.0"), None);
    }

    #[test]
    fn parses_bounded_runtime_environment() {
        let environment = parse_runtime_environment(
            b"HOME=/data/user/0/app/files/linux-home\nWAYLAND_DISPLAY=wayland-0\n__EGL_VENDOR_LIBRARY_DIRS=/runtime/egl\n",
        )
        .expect("valid runtime environment");
        assert_eq!(environment.len(), 3);
        assert_eq!(environment[0].0.to_bytes(), b"HOME");
        assert_eq!(environment[1].1.to_bytes(), b"wayland-0");
        assert_eq!(environment[2].0.to_bytes(), b"__EGL_VENDOR_LIBRARY_DIRS");
    }

    #[test]
    fn parses_bounded_runtime_arguments() {
        let arguments = parse_runtime_arguments(
            b"/data/user/0/app/files/linux-home/Documents/Android/example file.txt\n--line=4\n",
        )
        .expect("valid runtime arguments");
        assert_eq!(arguments.len(), 2);
        assert_eq!(
            arguments[0].to_bytes(),
            b"/data/user/0/app/files/linux-home/Documents/Android/example file.txt"
        );
        assert_eq!(arguments[1].to_bytes(), b"--line=4");
    }

    #[test]
    fn rejects_unsafe_runtime_arguments() {
        assert!(parse_runtime_arguments(b"unterminated").is_err());
        assert!(parse_runtime_arguments(b"bad\0argument\n").is_err());
        let oversized = vec![b'a'; 4097];
        let mut manifest = oversized;
        manifest.push(b'\n');
        assert!(parse_runtime_arguments(&manifest).is_err());
    }
    #[test]
    fn rejects_unsafe_runtime_environment() {
        for manifest in [
            b"home=/tmp\n".as_slice(),
            b"HOME=/tmp",
            b"HOME=/tmp\nHOME=/other\n",
            b"BAD-NAME=value\n",
            b"=value\n",
            b"HOME=/tmp\0bad\n",
        ] {
            assert!(parse_runtime_environment(manifest).is_err(), "{manifest:?}");
        }
    }

    #[test]
    fn rejects_unsafe_runtime_library_manifests() {
        for manifest in [
            b"".as_slice(),
            b"14\t../libc.so.6\n",
            b"14\tprogram\n",
            b"14\tlibc.so.6",
            b"bad\tlibc.so.6\n",
            b"14\tlibc.so.6\n15\tlibc.so.6\n",
            b"14\tlibc.so.6\n\n",
        ] {
            assert!(
                parse_runtime_library_manifest(manifest).is_err(),
                "{manifest:?}"
            );
        }
    }

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

    fn test_frame(width: u32, height: u32, values: &[u8]) -> Arc<CommittedFrame> {
        let pixels = values.iter().flat_map(|value| [*value, 0, 0, 0]).collect();
        Arc::new(CommittedFrame {
            width,
            height,
            format: wl_shm::Format::Xrgb8888,
            pixels,
            source: None,
        })
    }

    #[test]
    fn applies_inverse_buffer_transform_to_surface_pixels() {
        let frame = transform_buffer_frame(
            test_frame(2, 3, &[1, 2, 3, 4, 5, 6]),
            BufferTransform::Rotate90,
            1,
        )
        .expect("rotated frame");

        assert_eq!((frame.width, frame.height), (3, 2));
        assert_eq!(
            frame
                .pixels
                .chunks_exact(4)
                .map(|pixel| pixel[0])
                .collect::<Vec<_>>(),
            [2, 4, 6, 1, 3, 5]
        );
    }

    #[test]
    fn maps_every_wayland_buffer_transform_without_losing_pixels() {
        let transforms = [
            (BufferTransform::Normal, (2, 3)),
            (BufferTransform::Rotate90, (3, 2)),
            (BufferTransform::Rotate180, (2, 3)),
            (BufferTransform::Rotate270, (3, 2)),
            (BufferTransform::Flipped, (2, 3)),
            (BufferTransform::Flipped90, (3, 2)),
            (BufferTransform::Flipped180, (2, 3)),
            (BufferTransform::Flipped270, (3, 2)),
        ];
        for (transform, dimensions) in transforms {
            let frame = transform_buffer_frame(test_frame(2, 3, &[1, 2, 3, 4, 5, 6]), transform, 1)
                .expect("transformed frame");
            let mut values = frame
                .pixels
                .chunks_exact(4)
                .map(|pixel| pixel[0])
                .collect::<Vec<_>>();
            values.sort_unstable();
            assert_eq!((frame.width, frame.height), dimensions);
            assert_eq!(values, [1, 2, 3, 4, 5, 6]);
        }
    }

    #[test]
    fn scales_buffer_pixels_into_surface_coordinates() {
        let frame = transform_buffer_frame(
            test_frame(4, 2, &[1, 2, 3, 4, 5, 6, 7, 8]),
            BufferTransform::Normal,
            2,
        )
        .expect("scaled frame");

        assert_eq!((frame.width, frame.height), (2, 1));
        assert_eq!(
            frame
                .pixels
                .chunks_exact(4)
                .map(|pixel| pixel[0])
                .collect::<Vec<_>>(),
            [1, 3]
        );
        assert!(
            transform_buffer_frame(
                test_frame(3, 2, &[1, 2, 3, 4, 5, 6]),
                BufferTransform::Normal,
                2,
            )
            .is_err()
        );

        let rotated = transform_buffer_frame(
            test_frame(2, 3, &[1, 2, 3, 4, 5, 6]),
            BufferTransform::Rotate90,
            1,
        )
        .expect("rotated frame");
        let restored =
            transform_buffer_frame(rotated, BufferTransform::Normal, 1).expect("restored source");
        assert_eq!((restored.width, restored.height), (2, 3));
        assert_eq!(
            restored
                .pixels
                .chunks_exact(4)
                .map(|pixel| pixel[0])
                .collect::<Vec<_>>(),
            [1, 2, 3, 4, 5, 6]
        );
    }

    #[test]
    fn maps_buffer_damage_through_inverse_transform_and_scale() {
        let damage = BufferTransform::Rotate90
            .buffer_damage_to_surface(RegionRectangle::new(2, 0, 2, 2).expect("damage"), 4, 2, 2)
            .expect("mapped damage");
        assert_eq!(
            damage,
            RegionRectangle::new(0, 0, 1, 1).expect("expected damage")
        );
    }

    #[test]
    fn clips_and_unions_presentation_damage_without_overflow() {
        let first = RegionRectangle::new(-2, 1, 5, 4)
            .expect("first")
            .clip(4, 4)
            .expect("clipped");
        let second = RegionRectangle::new(2, 0, i32::MAX, 2)
            .expect("second")
            .clip(4, 4)
            .expect("clipped");
        assert_eq!(first, RegionRectangle::new(0, 1, 3, 3).expect("expected"));
        assert_eq!(
            first.union(second),
            RegionRectangle::new(0, 0, 4, 4).expect("expected")
        );
    }

    #[test]
    fn crops_and_scales_viewport_after_buffer_transform() {
        let source = test_frame(4, 2, &[1, 2, 3, 4, 5, 6, 7, 8]);

        let frame = apply_viewport_to_frame(
            source,
            Some(ViewportSource {
                x: 1.0,
                y: 0.0,
                width: 2.0,
                height: 2.0,
            }),
            Some((4, 4)),
        )
        .expect("valid viewport");
        assert_eq!((frame.width, frame.height), (4, 4));
        assert_eq!(&frame.pixels[0..4], &[2, 0, 0, 0]);
        assert_eq!(&frame.pixels[60..64], &[7, 0, 0, 0]);
        assert_eq!(
            (
                original_buffer_frame(&frame).width,
                original_buffer_frame(&frame).height
            ),
            (4, 2)
        );
    }

    #[test]
    fn rejects_fractional_viewport_source_without_destination() {
        let source = test_frame(2, 2, &[0; 16]);
        assert!(matches!(
            apply_viewport_to_frame(
                source,
                Some(ViewportSource {
                    x: 0.0,
                    y: 0.0,
                    width: 1.5,
                    height: 2.0,
                }),
                None,
            ),
            Err(ViewportApplyError::BadSize)
        ));
    }
    #[test]
    fn scales_frame_pixels_into_the_configured_rectangle() {
        let source = CommittedFrame {
            width: 2,
            height: 1,
            format: wl_shm::Format::Xrgb8888,
            pixels: vec![10, 20, 30, 255, 200, 210, 220, 255],
            source: None,
        };
        let mut destination = CommittedFrame {
            width: 4,
            height: 2,
            format: wl_shm::Format::Argb8888,
            pixels: vec![0; 4 * 2 * 4],
            source: None,
        };
        blend_popup_frame(&mut destination, &source, 0, 0, 4, 2);
        for row in 0..2 {
            let offset = row * 16;
            assert_eq!(
                &destination.pixels[offset..offset + 8],
                &[10, 20, 30, 255, 10, 20, 30, 255]
            );
            assert_eq!(
                &destination.pixels[offset + 8..offset + 16],
                &[200, 210, 220, 255, 200, 210, 220, 255]
            );
        }
    }

    #[test]
    fn clips_and_alpha_blends_popup_frames() {
        let mut destination = CommittedFrame {
            width: 2,
            height: 1,
            format: wl_shm::Format::Xrgb8888,
            pixels: vec![10, 20, 30, 0, 40, 50, 60, 0],
            source: None,
        };
        let source = CommittedFrame {
            width: 2,
            height: 1,
            format: wl_shm::Format::Argb8888,
            pixels: vec![110, 120, 130, 128, 210, 220, 230, 255],
            source: None,
        };

        blend_popup_frame(&mut destination, &source, 1, 0, 2, 1);

        assert_eq!(destination.pixels, [10, 20, 30, 0, 75, 85, 95, 0]);
    }
    #[test]
    fn crops_client_side_shadow_using_negative_surface_origin() {
        let mut source_pixels = Vec::new();
        for value in 0u8..16 {
            source_pixels.extend_from_slice(&[value, 0, 0, 0]);
        }
        let source = CommittedFrame {
            width: 4,
            height: 4,
            format: wl_shm::Format::Xrgb8888,
            pixels: source_pixels,
            source: None,
        };
        let mut output = CommittedFrame {
            width: 2,
            height: 2,
            format: wl_shm::Format::Xrgb8888,
            pixels: vec![0; 16],
            source: None,
        };

        blend_popup_frame(&mut output, &source, -1, -1, 4, 4);

        assert_eq!(output.pixels[0], 5);
        assert_eq!(output.pixels[4], 6);
        assert_eq!(output.pixels[8], 9);
        assert_eq!(output.pixels[12], 10);
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
    fn latches_window_geometry_only_on_surface_commit() {
        let first = WindowGeometry {
            x: 1,
            y: 2,
            width: 300,
            height: 200,
        };
        let second = WindowGeometry {
            x: 3,
            y: 4,
            width: 640,
            height: 360,
        };
        let mut state = XdgSurfaceState::default();

        state.pending_window_geometry = Some(first);
        assert_eq!(state.committed_window_geometry, None);
        assert!(state.commit_window_geometry());
        assert_eq!(state.committed_window_geometry, Some(first));
        assert_eq!(state.pending_window_geometry, None);

        state.pending_window_geometry = Some(first);
        assert!(!state.commit_window_geometry());
        state.pending_window_geometry = Some(second);
        assert!(state.commit_window_geometry());
        assert_eq!(state.committed_window_geometry, Some(second));
    }

    #[test]
    fn accepts_clients_from_owned_filesystem_socket_and_cleans_it_up() {
        let socket = std::env::temp_dir().join(format!(
            "archphene-compositor-{}-{}.sock",
            std::process::id(),
            std::thread::current().name().unwrap_or("test")
        ));
        let _ = std::fs::remove_file(&socket);
        let mut core = CompositorCore::new().expect("Wayland display");
        core.bind_socket(&socket).expect("bind socket");
        let _client = UnixStream::connect(&socket).expect("connect client");
        assert!(core.dispatch_once().expect("accept client") >= 1);
        assert_eq!(core.accepted_client_count(), 1);
        drop(core);
        assert!(!socket.exists());
    }

    #[test]
    fn creates_wayland_display_and_reports_protocol_version() {
        let mut core = CompositorCore::new().expect("Wayland display");
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
        assert_eq!(core.data_device_manager_bind_count(), 0);
        assert_eq!(core.data_source_count(), 0);
        assert_eq!(core.data_device_count(), 0);
        assert_eq!(core.data_offer_count(), 0);
        assert_eq!(core.take_android_paste_fd(), -1);
        assert_eq!(core.take_linux_copy_fd(), -1);
        assert_eq!(core.set_clipboard_active(true), 1);
        assert_eq!(core.offer_android_clipboard_text(), 0);
        assert_eq!(core.set_clipboard_active(false), 0);
        assert_eq!(core.text_input_manager_bind_count(), 0);
        assert_eq!(core.text_input_count(), 0);
        assert_eq!(core.ime_active(), 0);
        assert_eq!(core.ime_show_request_count(), 0);
        assert_eq!(core.ime_hide_request_count(), 0);
        assert_eq!(core.ime_surrounding_text_length(), -1);
        assert_eq!(core.ime_surrounding_cursor(), -1);
        assert_eq!(core.ime_surrounding_anchor(), -1);
        assert_eq!(core.ime_content_hint(), -1);
        assert_eq!(core.ime_content_purpose(), -1);
        assert_eq!(core.ime_cursor_rectangle(), None);
        assert_eq!(core.ime_cursor_rectangle_component(0), -1);
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
    fn roleless_auxiliary_surface_cannot_replace_an_xdg_toplevel() {
        assert!(surface_publishes_root_frame(None, false, false));
        assert!(!surface_publishes_root_frame(None, false, true));
        assert!(!surface_publishes_root_frame(None, true, false));
        assert!(surface_publishes_root_frame(
            Some(SurfaceRole::XdgToplevel),
            true,
            true,
        ));
        assert!(!surface_publishes_root_frame(
            Some(SurfaceRole::XdgPopup),
            true,
            true,
        ));
        assert!(!surface_publishes_root_frame(
            Some(SurfaceRole::Subsurface),
            false,
            true,
        ));
    }

    #[test]
    fn centers_compact_startup_window_against_phone_output() {
        let layout = calculate_toplevel_layout(
            1080,
            2205,
            536,
            185,
            556,
            205,
            WindowGeometry {
                x: 10,
                y: 10,
                width: 536,
                height: 185,
            },
            None,
        );
        assert_eq!((layout.output_width, layout.output_height), (540, 1102));
        assert_eq!((layout.root_x, layout.root_y), (-8, 448));
        assert!(!layout.overlay_primary);
    }

    #[test]
    fn expands_main_and_large_secondary_windows() {
        let main = calculate_toplevel_layout(
            1080,
            2205,
            540,
            1102,
            592,
            1148,
            WindowGeometry {
                x: 26,
                y: 23,
                width: 540,
                height: 1102,
            },
            None,
        );
        assert_eq!((main.output_width, main.output_height), (540, 1102));
        assert_eq!((main.root_x, main.root_y), (-26, -23));
        assert!(!main.overlay_primary);

        let chooser = calculate_toplevel_layout(
            1080,
            2205,
            540,
            900,
            560,
            920,
            WindowGeometry {
                x: 10,
                y: 10,
                width: 540,
                height: 900,
            },
            Some((540, 1102)),
        );
        assert_eq!((chooser.output_width, chooser.output_height), (540, 1102));
        assert_eq!((chooser.root_x, chooser.root_y), (0, 107));
        assert_eq!((chooser.root_width, chooser.root_height), (540, 887));
        assert!(chooser.overlay_primary);
    }

    #[test]
    fn centers_compact_secondary_window_over_primary() {
        let layout = calculate_toplevel_layout(
            1080,
            2205,
            374,
            546,
            394,
            566,
            WindowGeometry {
                x: 10,
                y: 10,
                width: 374,
                height: 546,
            },
            Some((540, 1102)),
        );
        assert_eq!((layout.output_width, layout.output_height), (540, 1102));
        assert_eq!((layout.root_x, layout.root_y), (73, 268));
        assert!(layout.overlay_primary);
    }
    #[test]
    fn only_wide_managed_secondary_windows_get_phone_canvas() {
        assert!(secondary_toplevel_needs_output_size(
            true, true, 1242, 2205, 1080, 2205,
        ));
        assert!(!secondary_toplevel_needs_output_size(
            true, true, 900, 1200, 1080, 2205,
        ));
        assert!(!secondary_toplevel_needs_output_size(
            false, true, 1242, 2205, 1080, 2205,
        ));
        assert_eq!(secondary_toplevel_canvas_width(1080), 1440);
    }

    #[test]
    fn uniformly_fits_oversized_secondary_window_over_primary() {
        let layout = calculate_toplevel_layout(
            1080,
            2205,
            1332,
            915,
            1332,
            915,
            WindowGeometry {
                x: 0,
                y: 0,
                width: 1332,
                height: 915,
            },
            Some((1080, 2205)),
        );
        assert_eq!((layout.output_width, layout.output_height), (1080, 2205));
        assert_eq!((layout.root_x, layout.root_y), (0, 732));
        assert_eq!((layout.root_width, layout.root_height), (1080, 741));
        assert!(layout.overlay_primary);
    }
    #[test]
    fn fits_client_buffer_that_overflows_window_geometry() {
        let layout = calculate_toplevel_layout(
            1080,
            2205,
            1080,
            2205,
            2910,
            2359,
            WindowGeometry {
                x: 26,
                y: 23,
                width: 1080,
                height: 2205,
            },
            Some((1080, 2205)),
        );
        assert_eq!((layout.output_width, layout.output_height), (1080, 2205));
        assert_eq!((layout.root_x, layout.root_y), (0, 665));
        assert_eq!((layout.root_width, layout.root_height), (1080, 875));
        assert!(layout.overlay_primary);
    }

    #[test]
    fn maps_phone_file_chooser_content_into_composited_output() {
        let layout = calculate_toplevel_layout(
            1080,
            2205,
            1462,
            2205,
            1514,
            2257,
            WindowGeometry {
                x: 26,
                y: 23,
                width: 1462,
                height: 2205,
            },
            Some((1080, 2205)),
        );
        assert_eq!(
            (
                layout.root_x,
                layout.root_y,
                layout.root_width,
                layout.root_height
            ),
            (0, 297, 1080, 1610)
        );
        assert_eq!(
            content_layout(
                layout,
                1514,
                2257,
                WindowGeometry {
                    x: 26,
                    y: 23,
                    width: 1462,
                    height: 2205,
                },
            ),
            (18, 313, 1042, 1572)
        );
    }

    #[test]
    fn maps_surface_popup_geometry_into_fitted_output() {
        assert_eq!(scale_surface_coordinate(170, 1080, 1440), 127);
        assert_eq!(scale_surface_coordinate(452, 1080, 1440), 339);
        assert_eq!(scale_surface_coordinate(-26, 1080, 1440), -19);
    }

    #[test]
    fn maps_fitted_output_coordinates_back_to_surface_coordinates() {
        assert_eq!(scale_input_coordinate(0.0, 1080, 1492), 0.0);
        assert!((scale_input_coordinate(540.0, 1080, 1492) - 746.0).abs() < 0.001);
        assert!((scale_input_coordinate(1626.0, 1692, 2257) - 2168.960_993).abs() < 0.001);
    }

    #[test]
    fn maps_pressed_evdev_keys_to_xkb_modifier_bits() {
        assert_eq!(CompositorCore::keyboard_modifier_mask(&[]), 0);
        assert_eq!(CompositorCore::keyboard_modifier_mask(&[42]), 1);
        assert_eq!(CompositorCore::keyboard_modifier_mask(&[29]), 4);
        assert_eq!(CompositorCore::keyboard_modifier_mask(&[56]), 8);
        assert_eq!(CompositorCore::keyboard_modifier_mask(&[54, 97, 100]), 13);
        assert_eq!(CompositorCore::keyboard_modifier_mask(&[24]), 0);
    }
    #[test]
    fn embeds_null_terminated_xkb_v1_keymap() {
        assert!(XKB_KEYMAP.starts_with(b"xkb_keymap {"));
        assert!(XKB_KEYMAP.ends_with(b"};\n\0"));
    }
}
