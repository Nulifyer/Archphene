#![deny(unsafe_code)]
#![deny(unsafe_op_in_unsafe_fn)]

use std::collections::{HashMap, VecDeque};
use std::fs::File;
use std::io::{self, Write};
use std::ops::Range;
use std::os::fd::{AsFd, AsRawFd, FromRawFd, IntoRawFd, OwnedFd, RawFd};
use std::os::unix::fs::{FileExt, FileTypeExt, MetadataExt};
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::{Path, PathBuf};
#[cfg(target_os = "android")]
use std::ptr;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

#[cfg(target_os = "android")]
use jni::objects::{JByteBuffer, JObject};
#[cfg(target_os = "android")]
use jni::sys::jboolean;
use jni::{JNIEnv, objects::JByteArray, sys::jbyteArray};
use wayland_protocols::wp::cursor_shape::v1::server::wp_cursor_shape_device_v1::{
    self, WpCursorShapeDeviceV1,
};
use wayland_protocols::wp::cursor_shape::v1::server::wp_cursor_shape_manager_v1::{
    self, WpCursorShapeManagerV1,
};
use wayland_protocols::wp::fractional_scale::v1::server::wp_fractional_scale_manager_v1::{
    self, WpFractionalScaleManagerV1,
};
use wayland_protocols::wp::fractional_scale::v1::server::wp_fractional_scale_v1::{
    self, WpFractionalScaleV1,
};
use wayland_protocols::wp::pointer_constraints::zv1::server::zwp_confined_pointer_v1::{
    self, ZwpConfinedPointerV1,
};
use wayland_protocols::wp::pointer_constraints::zv1::server::zwp_locked_pointer_v1::{
    self, ZwpLockedPointerV1,
};
use wayland_protocols::wp::pointer_constraints::zv1::server::zwp_pointer_constraints_v1::{
    self, ZwpPointerConstraintsV1,
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
use wayland_protocols::wp::relative_pointer::zv1::server::zwp_relative_pointer_manager_v1::{
    self, ZwpRelativePointerManagerV1,
};
use wayland_protocols::wp::relative_pointer::zv1::server::zwp_relative_pointer_v1::{
    self, ZwpRelativePointerV1,
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

const MAX_TOPLEVELS: usize = 32;
const MAX_ACTIVE_TOUCHES: usize = 32;
const MAX_PENDING_CLIPBOARD_TRANSFERS: usize = 4;
const MAX_REGION_OPERATIONS: usize = 64;
const MAX_CONFINEMENT_BOUNDARIES: usize = MAX_REGION_OPERATIONS * 8 + 4;
#[cfg(target_os = "android")]
const MAX_CLIPBOARD_BYTES: usize = 65_536;

/// Reviewed Unix descriptor and ancillary-data boundary.
///
/// The compositor core deals in owned `File`/`OwnedFd` values and byte slices;
/// raw pointers, libc message layouts, and raw-descriptor construction remain
/// confined here. Every received descriptor is immediately wrapped in `File`
/// so error paths close it deterministically.
#[allow(unsafe_code)]
mod syscall_ffi {
    use std::ffi::CStr;
    use std::fs::File;
    use std::io;
    use std::mem::{size_of, zeroed};
    use std::os::fd::{AsRawFd, FromRawFd, RawFd};
    use std::ptr;

    pub(super) fn poll_one(
        descriptor: RawFd,
        events: i16,
        timeout_millis: i32,
    ) -> io::Result<Option<i16>> {
        let mut poll_descriptor = libc::pollfd {
            fd: descriptor,
            events,
            revents: 0,
        };
        // SAFETY: `poll_descriptor` is a valid writable element for the
        // declared count of one and remains alive for the call.
        let result = unsafe { libc::poll(&mut poll_descriptor, 1, timeout_millis) };
        if result > 0 {
            Ok(Some(poll_descriptor.revents))
        } else if result == 0 {
            Ok(None)
        } else {
            Err(io::Error::last_os_error())
        }
    }

    pub(super) fn set_nonblocking(descriptor: RawFd) -> io::Result<()> {
        // SAFETY: `F_GETFL` does not dereference a variadic argument. Callers
        // pass a live descriptor owned by their `File`.
        let flags = unsafe { libc::fcntl(descriptor, libc::F_GETFL) };
        if flags < 0 {
            return Err(io::Error::last_os_error());
        }
        if flags & libc::O_NONBLOCK == 0 {
            // SAFETY: `F_SETFL` expects the integer flags argument supplied
            // here; the descriptor remains owned by the caller.
            if unsafe { libc::fcntl(descriptor, libc::F_SETFL, flags | libc::O_NONBLOCK) } < 0 {
                return Err(io::Error::last_os_error());
            }
        }
        Ok(())
    }

    pub(super) fn read(descriptor: RawFd, destination: &mut [u8]) -> io::Result<usize> {
        // SAFETY: the mutable slice provides a valid writable region for
        // exactly `destination.len()` bytes for the duration of the call.
        let result = unsafe {
            libc::read(
                descriptor,
                destination.as_mut_ptr().cast(),
                destination.len(),
            )
        };
        if result < 0 {
            Err(io::Error::last_os_error())
        } else {
            Ok(result as usize)
        }
    }

    pub(super) fn write(descriptor: RawFd, source: &[u8]) -> io::Result<usize> {
        // SAFETY: the immutable slice provides a valid readable region for
        // exactly `source.len()` bytes for the duration of the call.
        let result = unsafe { libc::write(descriptor, source.as_ptr().cast(), source.len()) };
        if result < 0 {
            Err(io::Error::last_os_error())
        } else {
            Ok(result as usize)
        }
    }

    pub(super) fn memfd(name: &CStr, flags: libc::c_uint) -> io::Result<File> {
        // SAFETY: `name` is NUL-terminated and live for the syscall. A
        // successful return transfers ownership of a new descriptor.
        let descriptor =
            unsafe { libc::syscall(libc::SYS_memfd_create, name.as_ptr(), flags) as RawFd };
        if descriptor < 0 {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: successful `memfd_create` returned a new owned descriptor.
        Ok(unsafe { File::from_raw_fd(descriptor) })
    }

    pub(super) fn add_seals(file: &File, seals: libc::c_int) -> io::Result<()> {
        // SAFETY: `F_ADD_SEALS` expects the integer bit mask supplied here,
        // and `file` keeps the descriptor live for the call.
        if unsafe { libc::fcntl(file.as_raw_fd(), libc::F_ADD_SEALS, seals) } < 0 {
            Err(io::Error::last_os_error())
        } else {
            Ok(())
        }
    }

    pub(super) fn cloexec_pipe() -> io::Result<(File, File)> {
        let mut descriptors = [-1; 2];
        // SAFETY: the two-element output array is writable for the call.
        if unsafe { libc::pipe2(descriptors.as_mut_ptr(), libc::O_CLOEXEC) } != 0 {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: successful `pipe2` returned two distinct owned descriptors.
        Ok(unsafe {
            (
                File::from_raw_fd(descriptors[0]),
                File::from_raw_fd(descriptors[1]),
            )
        })
    }

    pub(super) fn send_with_fd(
        socket_fd: RawFd,
        bytes: &[u8],
        transferred_fd: RawFd,
    ) -> io::Result<usize> {
        let mut io_vector = libc::iovec {
            iov_base: bytes.as_ptr().cast_mut().cast(),
            iov_len: bytes.len(),
        };
        // SAFETY: the libc CMSG helpers are evaluated with the size of one
        // descriptor, and the aligned word buffer below is at least that size.
        let control_size = unsafe { libc::CMSG_SPACE(size_of::<RawFd>() as u32) } as usize;
        let control_words = control_size.div_ceil(size_of::<usize>());
        let mut control = vec![0usize; control_words];
        // SAFETY: an all-zero `msghdr` is its documented empty state.
        let mut message: libc::msghdr = unsafe { zeroed() };
        message.msg_iov = &mut io_vector;
        message.msg_iovlen = 1;
        message.msg_control = control.as_mut_ptr().cast();
        message.msg_controllen = control_size;

        // SAFETY: `message` describes the live, aligned control allocation.
        let header = unsafe { libc::CMSG_FIRSTHDR(&message) };
        if header.is_null() {
            return Err(io::Error::other("could not allocate SCM_RIGHTS header"));
        }
        // SAFETY: the control allocation has `CMSG_SPACE` bytes for exactly
        // one RawFd, and the header/data locations came from libc.
        unsafe {
            (*header).cmsg_level = libc::SOL_SOCKET;
            (*header).cmsg_type = libc::SCM_RIGHTS;
            (*header).cmsg_len = libc::CMSG_LEN(size_of::<RawFd>() as u32) as usize;
            ptr::write(libc::CMSG_DATA(header).cast::<RawFd>(), transferred_fd);
        }

        // SAFETY: the iovec and ancillary buffers remain live and immutable
        // for this synchronous call.
        let sent = unsafe { libc::sendmsg(socket_fd, &message, libc::MSG_NOSIGNAL) };
        if sent < 0 {
            Err(io::Error::last_os_error())
        } else {
            Ok(sent as usize)
        }
    }

    pub(super) struct ReceivedMessage {
        pub(super) length: usize,
        pub(super) flags: libc::c_int,
        pub(super) descriptor: Option<File>,
    }

    pub(super) fn receive_with_optional_fd(
        socket_fd: RawFd,
        destination: &mut [u8],
        flags: libc::c_int,
    ) -> io::Result<ReceivedMessage> {
        let mut io_vector = libc::iovec {
            iov_base: destination.as_mut_ptr().cast(),
            iov_len: destination.len(),
        };
        // SAFETY: see `send_with_fd`; this buffer holds exactly one descriptor.
        let control_size = unsafe { libc::CMSG_SPACE(size_of::<RawFd>() as u32) } as usize;
        let control_words = control_size.div_ceil(size_of::<usize>());
        let mut control = vec![0usize; control_words];
        // SAFETY: an all-zero `msghdr` is its documented empty state.
        let mut message: libc::msghdr = unsafe { zeroed() };
        message.msg_iov = &mut io_vector;
        message.msg_iovlen = 1;
        message.msg_control = control.as_mut_ptr().cast();
        message.msg_controllen = control_size;

        let length = loop {
            // SAFETY: all message-owned buffers remain live and writable for
            // this synchronous call.
            let result = unsafe { libc::recvmsg(socket_fd, &mut message, flags) };
            if result >= 0 {
                break result as usize;
            }
            let error = io::Error::last_os_error();
            if error.kind() != io::ErrorKind::Interrupted {
                return Err(error);
            }
        };
        let message_flags = message.msg_flags;
        if message_flags & libc::MSG_CTRUNC != 0 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "truncated descriptor control message",
            ));
        }

        // SAFETY: `message` still points at the live aligned control buffer.
        let header = unsafe { libc::CMSG_FIRSTHDR(&message) };
        let descriptor = if header.is_null() {
            None
        } else {
            // SAFETY: `header` points inside the live control buffer.
            let valid = unsafe {
                (*header).cmsg_level == libc::SOL_SOCKET
                    && (*header).cmsg_type == libc::SCM_RIGHTS
                    && (*header).cmsg_len == libc::CMSG_LEN(size_of::<RawFd>() as u32) as usize
            };
            if !valid {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "invalid descriptor control message",
                ));
            }
            // SAFETY: exact CMSG length validation above proves one RawFd is
            // present at the libc-provided data address.
            let received_fd =
                unsafe { ptr::read_unaligned(libc::CMSG_DATA(header).cast::<RawFd>()) };
            if received_fd < 0 {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "received descriptor was invalid",
                ));
            }
            // SAFETY: SCM_RIGHTS installs a new descriptor owned by this
            // process; wrapping it immediately gives every error path RAII.
            Some(unsafe { File::from_raw_fd(received_fd) })
        };
        Ok(ReceivedMessage {
            length,
            flags: message_flags,
            descriptor,
        })
    }

    pub(super) fn receive(socket_fd: RawFd, destination: &mut [u8]) -> io::Result<usize> {
        loop {
            // SAFETY: the mutable slice is a valid writable region for the
            // duration of the synchronous call.
            let result = unsafe {
                libc::recv(
                    socket_fd,
                    destination.as_mut_ptr().cast(),
                    destination.len(),
                    libc::MSG_WAITALL,
                )
            };
            if result >= 0 {
                return Ok(result as usize);
            }
            let error = io::Error::last_os_error();
            if error.kind() != io::ErrorKind::Interrupted {
                return Err(error);
            }
        }
    }
}

fn pointer_button_bit(button: u32) -> Option<u8> {
    (272..=276)
        .contains(&button)
        .then(|| 1_u8 << (button - 272))
}

/// Owns protocol dispatch independently from Android Activity and rendering state.
pub struct CompositorCore {
    display: Display<CompositorState>,
    state: CompositorState,
    listener: Option<UnixListener>,
    socket_path: Option<PathBuf>,
    socket_identity: Option<SocketIdentity>,
    accepted_client_count: u32,
    stopping: AtomicBool,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct SocketIdentity {
    device: u64,
    inode: u64,
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
    output_mode_width: i32,
    output_mode_height: i32,
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
    cached_subsurface_traversal: Vec<(WlSurface, usize)>,
    next_input_serial: u32,
    pointers: Vec<WlPointer>,
    pointer_focus_surface: Option<WlSurface>,
    last_pointer_enter_serial: u32,
    cursor_surface: Option<WlSurface>,
    cursor_frame: Option<Arc<CommittedFrame>>,
    cursor_hotspot_x: i32,
    cursor_hotspot_y: i32,
    cursor_system_icon: i32,
    cursor_change_serial: u32,
    touches: Vec<WlTouch>,
    active_touches: Vec<ActiveTouch>,
    touch_event_count: u32,
    swipe_gestures: Vec<ZwpPointerGestureSwipeV1>,
    pinch_gestures: Vec<ZwpPointerGesturePinchV1>,
    hold_gestures: Vec<ZwpPointerGestureHoldV1>,
    gesture_event_count: u32,
    relative_pointers: Vec<ZwpRelativePointerV1>,
    locked_pointers: Vec<ZwpLockedPointerV1>,
    confined_pointers: Vec<ZwpConfinedPointerV1>,
    active_locked_pointer: Option<ZwpLockedPointerV1>,
    active_confined_pointer: Option<ZwpConfinedPointerV1>,
    pointer_capture_change_serial: u32,
    host_active: bool,
    pointer_inside: bool,
    pointer_buttons: u8,
    pointer_x: f64,
    pointer_y: f64,
    keyboard_count: u32,
    keyboard_event_count: u32,
    keyboards: Vec<WlKeyboard>,
    keyboard_focus_surface: Option<WlSurface>,
    selection_focus_dirty: bool,
    pressed_keys: Vec<u32>,
    reported_modifiers: u32,
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
    android_clipboard_has_html: bool,
    pending_android_paste_fds: VecDeque<ClipboardTransfer>,
    pending_linux_copy_fds: VecDeque<ClipboardTransfer>,
    pending_linux_clipboard_clear: bool,
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
    ime_change_serial: u32,
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
    pending_surface_damage_full: bool,
    pending_buffer_damage: Vec<RegionRectangle>,
    pending_buffer_damage_full: bool,
    commit_damage_scratch: Vec<RegionRectangle>,
    pending_callbacks: Vec<WlCallback>,
    pending_input_region: Option<Option<RegionState>>,
    committed_input_region: Option<RegionState>,
    cached_input_region: Option<Option<RegionState>>,
    pending_opaque_region: Option<Option<RegionState>>,
    committed_opaque_region: Option<RegionState>,
    cached_opaque_region: Option<Option<RegionState>>,
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
    entered_outputs: Vec<u32>,
}

fn take_pending_damage(
    surface: &mut SurfaceState,
) -> (Vec<RegionRectangle>, Vec<RegionRectangle>, bool) {
    let surface_damage = std::mem::take(&mut surface.pending_surface_damage);
    let surface_damage_full = std::mem::take(&mut surface.pending_surface_damage_full);
    let buffer_damage = std::mem::take(&mut surface.pending_buffer_damage);
    let buffer_damage_full = std::mem::take(&mut surface.pending_buffer_damage_full);
    (
        surface_damage,
        buffer_damage,
        surface_damage_full || buffer_damage_full,
    )
}

fn restore_pending_damage_buffers(
    surface: &mut SurfaceState,
    mut surface_damage: Vec<RegionRectangle>,
    mut buffer_damage: Vec<RegionRectangle>,
) {
    surface_damage.clear();
    buffer_damage.clear();
    surface.pending_surface_damage = surface_damage;
    surface.pending_buffer_damage = buffer_damage;
}

fn restore_commit_damage_scratch(surface: &mut SurfaceState, mut damage: Vec<RegionRectangle>) {
    damage.clear();
    surface.commit_damage_scratch = damage;
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

enum CursorShapeTarget {
    Pointer(WlPointer),
    Tablet,
}

struct CursorShapeDeviceData {
    target: CursorShapeTarget,
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

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum ClipboardFormat {
    PlainText,
    Html,
}

impl ClipboardFormat {
    const fn code(self) -> i32 {
        match self {
            Self::PlainText => 1,
            Self::Html => 2,
        }
    }
}

struct ClipboardTransfer {
    descriptor: File,
    format: ClipboardFormat,
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

#[derive(PartialEq, Eq)]
struct SurroundingText {
    text: String,
    cursor: i32,
    anchor: i32,
}

fn apply_text_input_commit(text_state: &mut TextInputState, another_enabled: bool) -> bool {
    let mut changed = false;
    if let Some(enabled) = text_state.pending_enabled.take() {
        let enabled = enabled && !another_enabled;
        changed |= text_state.enabled != enabled
            || text_state.surrounding_text.is_some()
            || text_state.content_type != (0, 0)
            || text_state.cursor_rectangle.is_some();
        text_state.enabled = enabled;
        text_state.surrounding_text = None;
        text_state.content_type = (0, 0);
        text_state.cursor_rectangle = None;
    }
    if let Some(surrounding_text) = text_state.pending_surrounding_text.take() {
        changed |= text_state.surrounding_text.as_ref() != Some(&surrounding_text);
        text_state.surrounding_text = Some(surrounding_text);
    }
    if let Some(content_type) = text_state.pending_content_type.take() {
        changed |= text_state.content_type != content_type;
        text_state.content_type = content_type;
    }
    if let Some(cursor_rectangle) = text_state.pending_cursor_rectangle.take() {
        changed |= text_state.cursor_rectangle.is_none();
        text_state.cursor_rectangle = Some(cursor_rectangle);
    }
    changed
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

struct RelativePointerData {
    pointer: WlPointer,
}

struct PointerConstraintData {
    surface: WlSurface,
    pointer: WlPointer,
    persistent: bool,
    region: Mutex<PointerConstraintRegionState>,
    eligible: AtomicBool,
    active: AtomicBool,
}

struct PointerConstraintRegionState {
    pending: Option<Option<RegionState>>,
    cached: Option<Option<RegionState>>,
    committed: Option<RegionState>,
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
    toplevel_size: Option<(i32, i32)>,
    restores_windowed: bool,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct WindowGeometry {
    x: i32,
    y: i32,
    width: i32,
    height: i32,
}

impl XdgSurfaceState {
    fn initial_configure_sent(&self) -> bool {
        !self.pending_configures.is_empty() || self.acknowledged_configure.is_some()
    }

    fn has_pending_toplevel_size(&self, width: i32, height: i32) -> bool {
        self.pending_configures
            .iter()
            .rev()
            .find_map(|configure| configure.toplevel_size)
            == Some((width, height))
    }

    fn commit_windowed_restoration(&mut self) -> bool {
        let Some(configure) = self.acknowledged_configure.as_mut() else {
            return false;
        };
        let restores_windowed = configure.restores_windowed;
        configure.restores_windowed = false;
        restores_windowed
    }

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
    size_constraints: Mutex<ToplevelSizeConstraints>,
    windowed_size: Mutex<Option<(u32, u32)>>,
    fullscreen_requested: AtomicBool,
    maximized_requested: AtomicBool,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
struct ToplevelSizeConstraints {
    min_width: i32,
    min_height: i32,
    max_width: i32,
    max_height: i32,
}

impl ToplevelSizeConstraints {
    fn constrain(self, width: i32, height: i32) -> (i32, i32) {
        fn extent(value: i32, minimum: i32, maximum: i32) -> i32 {
            if value <= 0 {
                return value;
            }
            let value = if minimum > 0 {
                value.max(minimum)
            } else {
                value
            };
            if maximum > 0 && (minimum == 0 || maximum >= minimum) {
                value.min(maximum)
            } else {
                value
            }
        }
        (
            extent(width, self.min_width, self.max_width),
            extent(height, self.min_height, self.max_height),
        )
    }

    fn constrain_bounded(self, width: i32, height: i32) -> (i32, i32) {
        let (constrained_width, constrained_height) = self.constrain(width, height);
        let bound = |requested: i32, constrained: i32| {
            if requested <= 0 || constrained <= 0 {
                constrained
            } else {
                constrained.min(requested.saturating_mul(4).saturating_div(3))
            }
        };
        (
            bound(width, constrained_width),
            bound(height, constrained_height),
        )
    }
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
    pixels: Mutex<Vec<u8>>,
    source: Option<Arc<CommittedFrame>>,
}

impl CommittedFrame {
    fn new(
        width: u32,
        height: u32,
        format: wl_shm::Format,
        pixels: Vec<u8>,
        source: Option<Arc<CommittedFrame>>,
    ) -> Self {
        Self {
            width,
            height,
            format,
            pixels: Mutex::new(pixels),
            source,
        }
    }

    fn pixels(&self) -> std::sync::MutexGuard<'_, Vec<u8>> {
        self.pixels
            .lock()
            .unwrap_or_else(|error| error.into_inner())
    }

    fn pixels_mut(&mut self) -> &mut Vec<u8> {
        self.pixels
            .get_mut()
            .unwrap_or_else(|error| error.into_inner())
    }

    fn into_pixels(self) -> Vec<u8> {
        self.pixels
            .into_inner()
            .unwrap_or_else(|error| error.into_inner())
    }
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

    fn surface_damage_to_buffer(
        self,
        damage: RegionRectangle,
        buffer_width: u32,
        buffer_height: u32,
        scale: i32,
    ) -> Option<RegionRectangle> {
        let scale = u32::try_from(scale).ok().filter(|scale| *scale > 0)?;
        let (physical_width, physical_height) = self.surface_size(buffer_width, buffer_height);
        if physical_width % scale != 0 || physical_height % scale != 0 {
            return None;
        }
        let surface_width = physical_width / scale;
        let surface_height = physical_height / scale;
        let damage = damage.clip(surface_width, surface_height)?;
        let left = u32::try_from(damage.x).ok()?.checked_mul(scale)?;
        let top = u32::try_from(damage.y).ok()?.checked_mul(scale)?;
        let right = u32::try_from(damage.right())
            .ok()?
            .checked_mul(scale)?
            .checked_sub(1)?;
        let bottom = u32::try_from(damage.bottom())
            .ok()?
            .checked_mul(scale)?
            .checked_sub(1)?;
        let corners = [
            self.buffer_coordinates(left, top, physical_width, physical_height),
            self.buffer_coordinates(right, top, physical_width, physical_height),
            self.buffer_coordinates(left, bottom, physical_width, physical_height),
            self.buffer_coordinates(right, bottom, physical_width, physical_height),
        ];
        let min_x = corners.iter().map(|point| point.0).min()?;
        let min_y = corners.iter().map(|point| point.1).min()?;
        let max_x = corners.iter().map(|point| point.0).max()?;
        let max_y = corners.iter().map(|point| point.1).max()?;
        RegionRectangle::new(
            min_x as i32,
            min_y as i32,
            max_x.checked_sub(min_x)?.checked_add(1)? as i32,
            max_y.checked_sub(min_y)?.checked_add(1)? as i32,
        )
    }
}

fn damage_for_commit_into(
    damage: &mut Vec<RegionRectangle>,
    surface_damage: &[RegionRectangle],
    buffer_damage: &[RegionRectangle],
    frame: Option<&Arc<CommittedFrame>>,
    transform: BufferTransform,
    scale: i32,
    force_full: bool,
) {
    damage.clear();
    let Some(frame) = frame else {
        return;
    };
    let scale = u32::try_from(scale).unwrap_or(1).max(1);
    damage.extend(
        surface_damage
            .iter()
            .filter_map(|rectangle| rectangle.clip(frame.width, frame.height)),
    );
    let source = original_buffer_frame(frame);
    damage.extend(buffer_damage.iter().filter_map(|rectangle| {
        transform.buffer_damage_to_surface(*rectangle, source.width, source.height, scale)
    }));
    if force_full {
        damage.clear();
        if let Some(full) = RegionRectangle::new(0, 0, frame.width as i32, frame.height as i32) {
            damage.push(full);
        }
    }
}

fn original_buffer_frame(frame: &Arc<CommittedFrame>) -> Arc<CommittedFrame> {
    let mut source = Arc::clone(frame);
    while let Some(parent) = source.source.as_ref() {
        source = Arc::clone(parent);
    }
    source
}

fn synchronized_cache_frame_is_detached(surface: &SurfaceState) -> bool {
    let Some(cached) = surface.cached_frame.as_ref().and_then(Option::as_ref) else {
        return false;
    };
    let cached = original_buffer_frame(cached);
    surface
        .committed_frame
        .as_ref()
        .is_none_or(|committed| !Arc::ptr_eq(&cached, &original_buffer_frame(committed)))
}

fn surface_snapshot_allows_in_place(
    surface: &SurfaceState,
    synchronized: bool,
    metadata_changed: bool,
) -> bool {
    !metadata_changed && (!synchronized || synchronized_cache_frame_is_detached(surface))
}

fn presentation_buffer_frame(
    frame: &Arc<CommittedFrame>,
    prefer_original: bool,
    transform: BufferTransform,
    viewport_source: Option<ViewportSource>,
) -> Arc<CommittedFrame> {
    if prefer_original && transform == BufferTransform::Normal && viewport_source.is_none() {
        original_buffer_frame(frame)
    } else {
        Arc::clone(frame)
    }
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
    let source_pixels = source.pixels();
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
                .copy_from_slice(&source_pixels[source_index..source_index + 4]);
        }
    }
    drop(source_pixels);
    Ok(Arc::new(CommittedFrame::new(
        width,
        height,
        source.format,
        pixels,
        Some(source),
    )))
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
    let frame_pixels = frame.pixels();
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
                .copy_from_slice(&frame_pixels[source_index..source_index + 4]);
        }
    }
    drop(frame_pixels);
    Ok(Arc::new(CommittedFrame::new(
        width,
        height,
        frame.format,
        pixels,
        Some(frame),
    )))
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

/// Reviewed Android bitmap and native-window boundary.
///
/// JNI references are accepted only by the two explicitly unsafe entry
/// functions. A successful surface conversion becomes an owned RAII window;
/// locked pixel pointers become bounded slices only after dimensions, stride,
/// format, nullness, and byte-count validation.
#[cfg(target_os = "android")]
#[allow(unsafe_code)]
mod android_graphics_ffi {
    use std::ffi::{c_char, c_void};
    use std::marker::PhantomData;
    use std::mem::zeroed;
    use std::ptr::{self, NonNull};
    use std::sync::{Arc, Mutex};

    const ANDROID_RGBA_8888: i32 = 1;
    const MAX_DIMENSION: usize = 8192;
    const MAX_PIXEL_BYTES: usize = 33_554_432 * 4;
    const HARDWARE_BUFFER_SLOTS: usize = 3;
    const MAX_HARDWARE_BUFFER_SLOTS: usize = 15;
    const HARDWARE_BUFFER_CPU_WRITE_OFTEN: u64 = 3 << 4;
    const HARDWARE_BUFFER_GPU_SAMPLED_IMAGE: u64 = 1 << 8;
    const SURFACE_VISIBILITY_SHOW: i8 = 1;
    const SURFACE_TRANSPARENCY_OPAQUE: i8 = 2;

    #[repr(C)]
    struct AndroidBitmapInfo {
        width: u32,
        height: u32,
        stride: u32,
        format: i32,
        flags: u32,
    }

    #[repr(C)]
    struct HardwareBufferDescription {
        width: u32,
        height: u32,
        layers: u32,
        format: u32,
        usage: u64,
        stride: u32,
        reserved_zero: u32,
        reserved_one: u64,
    }

    enum AndroidNativeWindow {}
    enum AndroidHardwareBuffer {}
    enum AndroidSurfaceControl {}
    enum AndroidSurfaceTransaction {}
    enum AndroidSurfaceTransactionStats {}

    #[repr(C)]
    struct AndroidRect {
        left: i32,
        top: i32,
        right: i32,
        bottom: i32,
    }

    #[link(name = "jnigraphics")]
    unsafe extern "C" {
        #[link_name = "AndroidBitmap_getInfo"]
        fn android_bitmap_get_info(
            environment: *mut c_void,
            bitmap: *mut c_void,
            info: *mut AndroidBitmapInfo,
        ) -> i32;
        #[link_name = "AndroidBitmap_lockPixels"]
        fn android_bitmap_lock_pixels(
            environment: *mut c_void,
            bitmap: *mut c_void,
            address: *mut *mut c_void,
        ) -> i32;
        #[link_name = "AndroidBitmap_unlockPixels"]
        fn android_bitmap_unlock_pixels(environment: *mut c_void, bitmap: *mut c_void) -> i32;
    }

    #[link(name = "android")]
    unsafe extern "C" {
        #[link_name = "ANativeWindow_fromSurface"]
        fn android_native_window_from_surface(
            environment: *mut c_void,
            surface: *mut c_void,
        ) -> *mut AndroidNativeWindow;
        #[link_name = "ANativeWindow_release"]
        fn android_native_window_release(window: *mut AndroidNativeWindow);
        #[link_name = "AHardwareBuffer_allocate"]
        fn android_hardware_buffer_allocate(
            description: *const HardwareBufferDescription,
            output: *mut *mut AndroidHardwareBuffer,
        ) -> i32;
        #[link_name = "AHardwareBuffer_release"]
        fn android_hardware_buffer_release(buffer: *mut AndroidHardwareBuffer);
        #[link_name = "AHardwareBuffer_describe"]
        fn android_hardware_buffer_describe(
            buffer: *const AndroidHardwareBuffer,
            description: *mut HardwareBufferDescription,
        );
        #[link_name = "AHardwareBuffer_lock"]
        fn android_hardware_buffer_lock(
            buffer: *mut AndroidHardwareBuffer,
            usage: u64,
            fence: i32,
            rectangle: *const AndroidRect,
            address: *mut *mut c_void,
        ) -> i32;
        #[link_name = "AHardwareBuffer_unlock"]
        fn android_hardware_buffer_unlock(
            buffer: *mut AndroidHardwareBuffer,
            fence: *mut i32,
        ) -> i32;
        #[link_name = "ASurfaceControl_createFromWindow"]
        fn android_surface_control_create_from_window(
            parent: *mut AndroidNativeWindow,
            debug_name: *const c_char,
        ) -> *mut AndroidSurfaceControl;
        #[link_name = "ASurfaceControl_release"]
        fn android_surface_control_release(control: *mut AndroidSurfaceControl);
        #[link_name = "ASurfaceTransaction_create"]
        fn android_surface_transaction_create() -> *mut AndroidSurfaceTransaction;
        #[link_name = "ASurfaceTransaction_delete"]
        fn android_surface_transaction_delete(transaction: *mut AndroidSurfaceTransaction);
        #[link_name = "ASurfaceTransaction_apply"]
        fn android_surface_transaction_apply(transaction: *mut AndroidSurfaceTransaction);
        #[link_name = "ASurfaceTransaction_setBuffer"]
        fn android_surface_transaction_set_buffer(
            transaction: *mut AndroidSurfaceTransaction,
            control: *mut AndroidSurfaceControl,
            buffer: *mut AndroidHardwareBuffer,
            acquire_fence: i32,
        );
        #[link_name = "ASurfaceTransaction_setGeometry"]
        fn android_surface_transaction_set_geometry(
            transaction: *mut AndroidSurfaceTransaction,
            control: *mut AndroidSurfaceControl,
            source: *const AndroidRect,
            destination: *const AndroidRect,
            transform: i32,
        );
        #[link_name = "ASurfaceTransaction_setDamageRegion"]
        fn android_surface_transaction_set_damage_region(
            transaction: *mut AndroidSurfaceTransaction,
            control: *mut AndroidSurfaceControl,
            rectangles: *const AndroidRect,
            count: u32,
        );
        #[link_name = "ASurfaceTransaction_setVisibility"]
        fn android_surface_transaction_set_visibility(
            transaction: *mut AndroidSurfaceTransaction,
            control: *mut AndroidSurfaceControl,
            visibility: i8,
        );
        #[link_name = "ASurfaceTransaction_setBufferTransparency"]
        fn android_surface_transaction_set_buffer_transparency(
            transaction: *mut AndroidSurfaceTransaction,
            control: *mut AndroidSurfaceControl,
            transparency: i8,
        );
        #[link_name = "ASurfaceTransaction_setOnComplete"]
        fn android_surface_transaction_set_on_complete(
            transaction: *mut AndroidSurfaceTransaction,
            context: *mut c_void,
            callback: unsafe extern "C" fn(*mut c_void, *mut AndroidSurfaceTransactionStats),
        );
        #[link_name = "ASurfaceTransactionStats_getPreviousReleaseFenceFd"]
        fn android_surface_transaction_previous_release_fence(
            stats: *mut AndroidSurfaceTransactionStats,
            control: *mut AndroidSurfaceControl,
        ) -> i32;
    }

    pub(super) struct NativeWindow {
        presentation: Arc<PresentationState>,
    }

    // SAFETY: presentation calls remain serialized on the launcher surface
    // thread. Android may invoke completion callbacks on arbitrary threads;
    // those callbacks touch only the mutex-protected bounded slot state.
    unsafe impl Send for NativeWindow {}

    struct PresentationState {
        control: NonNull<AndroidSurfaceControl>,
        buffers: Mutex<PresentationBuffers>,
    }

    // SAFETY: the opaque control is used only by Android transaction functions
    // and the slot collection is protected by its mutex.
    unsafe impl Send for PresentationState {}
    // SAFETY: see the Send rationale; no unprotected Rust state is shared.
    unsafe impl Sync for PresentationState {}

    struct PresentationBuffers {
        slots: Vec<Box<HardwareBufferSlot>>,
        generation: u32,
        width: usize,
        height: usize,
        destination_width: i32,
        destination_height: i32,
        current: Option<u64>,
        next_id: u64,
    }

    struct HardwareBufferSlot {
        id: u64,
        generation: u32,
        buffer: NonNull<AndroidHardwareBuffer>,
        stride_bytes: usize,
        state: HardwareBufferState,
        release_context: ReleaseContext,
    }

    enum HardwareBufferState {
        Available(i32),
        Writing,
        Current,
        PendingRelease,
    }

    struct SlotReservation {
        id: u64,
        buffer: NonNull<AndroidHardwareBuffer>,
        width: usize,
        height: usize,
        stride_bytes: usize,
    }

    struct ReleaseContext {
        slot_id: u64,
        presentation: Mutex<Option<Arc<PresentationState>>>,
    }

    impl NativeWindow {
        /// Acquires a native-window reference from valid JNI references.
        ///
        /// # Safety
        ///
        /// `environment` must be the current JNI environment and `surface`
        /// must be a live `android.view.Surface` reference for this call.
        pub(super) unsafe fn from_surface(
            environment: *mut c_void,
            surface: *mut c_void,
        ) -> Option<Self> {
            if environment.is_null() || surface.is_null() {
                return None;
            }
            // SAFETY: the caller guarantees both JNI references are valid for
            // this invocation; Android returns an acquired window reference.
            let window =
                NonNull::new(unsafe { android_native_window_from_surface(environment, surface) })?;
            let control = NonNull::new(unsafe {
                android_surface_control_create_from_window(
                    window.as_ptr(),
                    c"Archphene Linux frame".as_ptr(),
                )
            });
            // SAFETY: fromSurface returned one acquired reference; the child
            // SurfaceControl retains the parent relationship independently.
            unsafe { android_native_window_release(window.as_ptr()) };
            let control = control?;
            Some(Self {
                presentation: Arc::new(PresentationState {
                    control,
                    buffers: Mutex::new(PresentationBuffers {
                        slots: Vec::with_capacity(MAX_HARDWARE_BUFFER_SLOTS),
                        generation: 0,
                        width: 0,
                        height: 0,
                        destination_width: 0,
                        destination_height: 0,
                        current: None,
                        next_id: 1,
                    }),
                }),
            })
        }

        pub(super) fn set_rgba_geometry(
            &mut self,
            width: i32,
            height: i32,
            destination_width: i32,
            destination_height: i32,
        ) -> Result<(), ()> {
            if width <= 0
                || height <= 0
                || destination_width <= 0
                || destination_height <= 0
                || width as usize > MAX_DIMENSION
                || height as usize > MAX_DIMENSION
                || (width as usize)
                    .checked_mul(height as usize)
                    .is_none_or(|pixels| pixels > MAX_PIXEL_BYTES / 4)
            {
                return Err(());
            }
            let (width, height) = (width as usize, height as usize);
            {
                let buffers = self
                    .presentation
                    .buffers
                    .lock()
                    .unwrap_or_else(|error| error.into_inner());
                if buffers.width == width
                    && buffers.height == height
                    && buffers.destination_width == destination_width
                    && buffers.destination_height == destination_height
                {
                    return Ok(());
                }
            }
            let mut new_slots = allocate_hardware_buffers(width, height)?;
            let mut buffers = self
                .presentation
                .buffers
                .lock()
                .unwrap_or_else(|error| error.into_inner());
            collect_retired_buffers(&mut buffers);
            if buffers.slots.len() + new_slots.len() > MAX_HARDWARE_BUFFER_SLOTS {
                release_hardware_slots(&mut new_slots);
                return Err(());
            }
            buffers.generation = buffers.generation.wrapping_add(1).max(1);
            for slot in &mut new_slots {
                slot.id = buffers.next_id;
                slot.release_context.slot_id = slot.id;
                slot.generation = buffers.generation;
                buffers.next_id = buffers.next_id.wrapping_add(1).max(1);
            }
            buffers.width = width;
            buffers.height = height;
            buffers.destination_width = destination_width;
            buffers.destination_height = destination_height;
            buffers.slots.extend(new_slots);
            Ok(())
        }

        pub(super) fn with_locked_rgba<R>(
            &mut self,
            operation: impl FnOnce(WindowBuffer<'_>) -> R,
        ) -> Result<(R, i32), i32> {
            let reservation = reserve_hardware_slot(&self.presentation).ok_or(-8)?;
            let Some(byte_count) = reservation
                .stride_bytes
                .checked_mul(reservation.height)
                .filter(|count| *count <= MAX_PIXEL_BYTES)
            else {
                return_hardware_slot(&self.presentation, reservation.id, -1);
                return Err(-3);
            };
            let mut address = ptr::null_mut();
            // SAFETY: the reserved slot is exclusively owned by this call and
            // was allocated with matching CPU-write usage.
            if unsafe {
                android_hardware_buffer_lock(
                    reservation.buffer.as_ptr(),
                    HARDWARE_BUFFER_CPU_WRITE_OFTEN,
                    -1,
                    ptr::null(),
                    &mut address,
                )
            } != 0
            {
                return_hardware_slot(&self.presentation, reservation.id, -1);
                return Err(-2);
            }
            let Some(address) = NonNull::new(address.cast::<u8>()) else {
                let _ = unsafe {
                    android_hardware_buffer_unlock(reservation.buffer.as_ptr(), ptr::null_mut())
                };
                return_hardware_slot(&self.presentation, reservation.id, -1);
                return Err(-3);
            };
            // SAFETY: AHardwareBuffer_describe supplied this bounded stride and
            // height, and the buffer remains locked for this borrow.
            let pixels = unsafe { std::slice::from_raw_parts_mut(address.as_ptr(), byte_count) };
            let result = operation(WindowBuffer {
                width: reservation.width,
                height: reservation.height,
                stride_bytes: reservation.stride_bytes,
                pixels,
            });
            let mut acquire_fence = -1;
            // SAFETY: exactly balances the successful exclusive lock.
            let unlocked = unsafe {
                android_hardware_buffer_unlock(reservation.buffer.as_ptr(), &mut acquire_fence)
            };
            if unlocked != 0 {
                close_descriptor(acquire_fence);
                return_hardware_slot(&self.presentation, reservation.id, -1);
                return Err(-4);
            }
            let posted = present_hardware_slot(&self.presentation, reservation, acquire_fence);
            Ok((result, posted))
        }
    }

    impl Drop for PresentationState {
        fn drop(&mut self) {
            let mut buffers = self
                .buffers
                .lock()
                .unwrap_or_else(|error| error.into_inner());
            release_hardware_slots(&mut buffers.slots);
            // SAFETY: createFromWindow returned this one owned reference.
            unsafe { android_surface_control_release(self.control.as_ptr()) };
        }
    }

    pub(super) struct WindowBuffer<'a> {
        pub(super) width: usize,
        pub(super) height: usize,
        pub(super) stride_bytes: usize,
        pub(super) pixels: &'a mut [u8],
    }

    fn allocate_hardware_buffers(
        width: usize,
        height: usize,
    ) -> Result<Vec<Box<HardwareBufferSlot>>, ()> {
        let mut slots = Vec::with_capacity(HARDWARE_BUFFER_SLOTS);
        let description = HardwareBufferDescription {
            width: width as u32,
            height: height as u32,
            layers: 1,
            format: ANDROID_RGBA_8888 as u32,
            usage: HARDWARE_BUFFER_CPU_WRITE_OFTEN | HARDWARE_BUFFER_GPU_SAMPLED_IMAGE,
            stride: 0,
            reserved_zero: 0,
            reserved_one: 0,
        };
        for _ in 0..HARDWARE_BUFFER_SLOTS {
            let mut raw = ptr::null_mut();
            if unsafe { android_hardware_buffer_allocate(&description, &mut raw) } != 0 {
                release_hardware_slots(&mut slots);
                return Err(());
            }
            let Some(buffer) = NonNull::new(raw) else {
                release_hardware_slots(&mut slots);
                return Err(());
            };
            let mut actual: HardwareBufferDescription = unsafe { zeroed() };
            unsafe { android_hardware_buffer_describe(buffer.as_ptr(), &mut actual) };
            let Some(stride_bytes) = (actual.stride as usize).checked_mul(4) else {
                unsafe { android_hardware_buffer_release(buffer.as_ptr()) };
                release_hardware_slots(&mut slots);
                return Err(());
            };
            if actual.width as usize != width
                || actual.height as usize != height
                || actual.layers != 1
                || actual.format != ANDROID_RGBA_8888 as u32
                || stride_bytes < width * 4
                || stride_bytes
                    .checked_mul(height)
                    .is_none_or(|count| count > MAX_PIXEL_BYTES)
            {
                unsafe { android_hardware_buffer_release(buffer.as_ptr()) };
                release_hardware_slots(&mut slots);
                return Err(());
            }
            slots.push(Box::new(HardwareBufferSlot {
                id: 0,
                generation: 0,
                buffer,
                stride_bytes,
                state: HardwareBufferState::Available(-1),
                release_context: ReleaseContext {
                    slot_id: 0,
                    presentation: Mutex::new(None),
                },
            }));
        }
        Ok(slots)
    }

    fn reserve_hardware_slot(presentation: &PresentationState) -> Option<SlotReservation> {
        let mut buffers = presentation
            .buffers
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        collect_retired_buffers(&mut buffers);
        let generation = buffers.generation;
        let width = buffers.width;
        let height = buffers.height;
        let mut selected = None;
        for index in 0..buffers.slots.len() {
            if buffers.slots[index].generation == generation
                && available_fence_signaled(&mut buffers.slots[index].state)
            {
                selected = Some(index);
                break;
            }
        }
        let index = selected?;
        let slot = &mut buffers.slots[index];
        slot.state = HardwareBufferState::Writing;
        Some(SlotReservation {
            id: slot.id,
            buffer: slot.buffer,
            width,
            height,
            stride_bytes: slot.stride_bytes,
        })
    }

    fn return_hardware_slot(presentation: &PresentationState, slot_id: u64, fence: i32) {
        let mut buffers = presentation
            .buffers
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if let Some(slot) = buffers.slots.iter_mut().find(|slot| slot.id == slot_id) {
            slot.state = HardwareBufferState::Available(fence);
        } else {
            close_descriptor(fence);
        }
    }

    fn present_hardware_slot(
        presentation: &Arc<PresentationState>,
        reservation: SlotReservation,
        acquire_fence: i32,
    ) -> i32 {
        let Some(transaction) = NonNull::new(unsafe { android_surface_transaction_create() })
        else {
            close_descriptor(acquire_fence);
            return_hardware_slot(presentation, reservation.id, -1);
            return -5;
        };
        let (completion_context, source, destination) = {
            let mut buffers = presentation
                .buffers
                .lock()
                .unwrap_or_else(|error| error.into_inner());
            let Some(index) = buffers
                .slots
                .iter()
                .position(|slot| slot.id == reservation.id)
            else {
                close_descriptor(acquire_fence);
                unsafe { android_surface_transaction_delete(transaction.as_ptr()) };
                return -6;
            };
            if !matches!(buffers.slots[index].state, HardwareBufferState::Writing) {
                close_descriptor(acquire_fence);
                unsafe { android_surface_transaction_delete(transaction.as_ptr()) };
                return -6;
            }
            let completion_context = buffers.current.and_then(|previous| {
                let slot = buffers.slots.iter_mut().find(|slot| slot.id == previous)?;
                slot.state = HardwareBufferState::PendingRelease;
                let mut callback_presentation = slot
                    .release_context
                    .presentation
                    .lock()
                    .unwrap_or_else(|error| error.into_inner());
                debug_assert!(callback_presentation.is_none());
                *callback_presentation = Some(Arc::clone(presentation));
                Some(
                    std::ptr::from_ref(&slot.release_context)
                        .cast_mut()
                        .cast::<c_void>(),
                )
            });
            buffers.slots[index].state = HardwareBufferState::Current;
            buffers.current = Some(reservation.id);
            (
                completion_context,
                AndroidRect {
                    left: 0,
                    top: 0,
                    right: reservation.width as i32,
                    bottom: reservation.height as i32,
                },
                AndroidRect {
                    left: 0,
                    top: 0,
                    right: buffers.destination_width,
                    bottom: buffers.destination_height,
                },
            )
        };
        unsafe {
            android_surface_transaction_set_buffer(
                transaction.as_ptr(),
                presentation.control.as_ptr(),
                reservation.buffer.as_ptr(),
                acquire_fence,
            );
            android_surface_transaction_set_geometry(
                transaction.as_ptr(),
                presentation.control.as_ptr(),
                &source,
                &destination,
                0,
            );
            android_surface_transaction_set_damage_region(
                transaction.as_ptr(),
                presentation.control.as_ptr(),
                &source,
                1,
            );
            android_surface_transaction_set_buffer_transparency(
                transaction.as_ptr(),
                presentation.control.as_ptr(),
                SURFACE_TRANSPARENCY_OPAQUE,
            );
            android_surface_transaction_set_visibility(
                transaction.as_ptr(),
                presentation.control.as_ptr(),
                SURFACE_VISIBILITY_SHOW,
            );
        }
        if let Some(context) = completion_context {
            unsafe {
                android_surface_transaction_set_on_complete(
                    transaction.as_ptr(),
                    context,
                    surface_transaction_complete,
                );
            }
        }
        unsafe {
            android_surface_transaction_apply(transaction.as_ptr());
            android_surface_transaction_delete(transaction.as_ptr());
        }
        0
    }

    unsafe extern "C" fn surface_transaction_complete(
        context: *mut c_void,
        stats: *mut AndroidSurfaceTransactionStats,
    ) {
        if context.is_null() {
            return;
        }
        let context = unsafe { &*context.cast::<ReleaseContext>() };
        let Some(presentation) = context
            .presentation
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .take()
        else {
            return;
        };
        let fence = if stats.is_null() {
            -1
        } else {
            unsafe {
                android_surface_transaction_previous_release_fence(
                    stats,
                    presentation.control.as_ptr(),
                )
            }
        };
        let mut buffers = presentation
            .buffers
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if let Some(slot) = buffers
            .slots
            .iter_mut()
            .find(|slot| slot.id == context.slot_id)
        {
            slot.state = HardwareBufferState::Available(fence);
        } else {
            close_descriptor(fence);
        }
    }

    fn available_fence_signaled(state: &mut HardwareBufferState) -> bool {
        let HardwareBufferState::Available(fence) = state else {
            return false;
        };
        if *fence < 0 {
            return true;
        }
        let mut descriptor = libc::pollfd {
            fd: *fence,
            events: libc::POLLIN,
            revents: 0,
        };
        let ready = unsafe { libc::poll(&mut descriptor, 1, 0) } > 0;
        if ready {
            close_descriptor(*fence);
            *fence = -1;
        }
        ready
    }

    fn collect_retired_buffers(buffers: &mut PresentationBuffers) {
        let generation = buffers.generation;
        let mut index = 0;
        while index < buffers.slots.len() {
            let retired = buffers.slots[index].generation != generation
                && available_fence_signaled(&mut buffers.slots[index].state);
            if retired {
                let slot = buffers.slots.swap_remove(index);
                unsafe { android_hardware_buffer_release(slot.buffer.as_ptr()) };
            } else {
                index += 1;
            }
        }
    }

    fn release_hardware_slots(slots: &mut Vec<Box<HardwareBufferSlot>>) {
        for slot in slots.drain(..) {
            if let HardwareBufferState::Available(fence) = slot.state {
                close_descriptor(fence);
            }
            unsafe { android_hardware_buffer_release(slot.buffer.as_ptr()) };
        }
    }

    fn close_descriptor(descriptor: i32) {
        if descriptor >= 0 {
            let _ = unsafe { libc::close(descriptor) };
        }
    }

    pub(super) struct BitmapPixels<'a> {
        pub(super) stride_bytes: usize,
        pub(super) pixels: &'a mut [u8],
        _locked: PhantomData<&'a mut [u8]>,
    }

    /// Locks one valid JNI Bitmap and exposes only its validated RGBA bytes.
    ///
    /// # Safety
    ///
    /// `environment` must be the current JNI environment and `bitmap` a live
    /// mutable RGBA_8888 Bitmap reference for the duration of this call.
    pub(super) unsafe fn with_rgba_bitmap<R>(
        environment: *mut c_void,
        bitmap: *mut c_void,
        expected_width: u32,
        expected_height: u32,
        operation: impl FnOnce(BitmapPixels<'_>) -> R,
    ) -> Result<(R, i32), i32> {
        if environment.is_null() || bitmap.is_null() {
            return Err(-2);
        }
        // SAFETY: the caller guarantees valid JNI references and `info` is a
        // writable ABI-compatible record.
        let mut info: AndroidBitmapInfo = unsafe { zeroed() };
        if unsafe { android_bitmap_get_info(environment, bitmap, &mut info) } != 0 {
            return Err(-2);
        }
        let width = usize::try_from(info.width).map_err(|_| -3)?;
        let height = usize::try_from(info.height).map_err(|_| -3)?;
        let stride_bytes = usize::try_from(info.stride).map_err(|_| -3)?;
        let row_bytes = width.checked_mul(4).ok_or(-3)?;
        let byte_count = stride_bytes.checked_mul(height).ok_or(-3)?;
        if info.format != ANDROID_RGBA_8888
            || info.width != expected_width
            || info.height != expected_height
            || width > MAX_DIMENSION
            || height > MAX_DIMENSION
            || stride_bytes < row_bytes
            || byte_count > MAX_PIXEL_BYTES
        {
            return Err(-3);
        }

        let mut address = ptr::null_mut();
        // SAFETY: the caller guarantees valid JNI references and `address` is
        // a writable output pointer.
        if unsafe { android_bitmap_lock_pixels(environment, bitmap, &mut address) } != 0 {
            return Err(-4);
        }
        let Some(address) = NonNull::new(address.cast::<u8>()) else {
            // SAFETY: a successful lock must be balanced even if Android
            // unexpectedly returned a null pixel address.
            let _ = unsafe { android_bitmap_unlock_pixels(environment, bitmap) };
            return Err(-4);
        };
        // SAFETY: AndroidBitmap info supplies a locked allocation of at least
        // stride*height bytes; it remains locked through the callback.
        let pixels = unsafe { std::slice::from_raw_parts_mut(address.as_ptr(), byte_count) };
        let result = operation(BitmapPixels {
            stride_bytes,
            pixels,
            _locked: PhantomData,
        });
        // SAFETY: this exactly balances the successful lock above.
        let unlocked = unsafe { android_bitmap_unlock_pixels(environment, bitmap) };
        Ok((result, unlocked))
    }
}

#[cfg(target_os = "android")]
struct LauncherSurfaceCompositor {
    core: CompositorCore,
    window: Option<android_graphics_ffi::NativeWindow>,
    surface_width: i32,
    surface_height: i32,
    buffer_width: i32,
    buffer_height: i32,
    last_presented_commit: u32,
    last_presentation_signature: Option<[i32; 6]>,
    last_reported_ime_serial: Option<u32>,
    last_reported_pointer_capture_serial: Option<u32>,
    last_reported_cursor_serial: Option<u32>,
}

#[cfg(target_os = "android")]
impl LauncherSurfaceCompositor {
    fn detach_surface(&mut self) {
        self.window = None;
    }

    fn attach_surface(
        &mut self,
        mut window: android_graphics_ffi::NativeWindow,
        width: i32,
        height: i32,
        density_dpi: i32,
        geometry_percent: i32,
    ) -> i32 {
        if !valid_launcher_surface_size(width, height)
            || !valid_launcher_density(density_dpi)
            || !valid_launcher_geometry_percent(geometry_percent)
        {
            return -2;
        }
        let density_dpi = launcher_density_dpi(width, height, density_dpi, geometry_percent);
        let logical_width = launcher_logical_extent(width, density_dpi);
        let logical_height = launcher_logical_extent(height, density_dpi);
        if window
            .set_rgba_geometry(logical_width, logical_height, width, height)
            .is_err()
        {
            return -4;
        }
        self.detach_surface();
        self.window = Some(window);
        self.surface_width = width;
        self.surface_height = height;
        self.buffer_width = logical_width;
        self.buffer_height = logical_height;
        self.last_presented_commit = u32::MAX;
        self.last_presentation_signature = None;
        self.last_reported_ime_serial = None;
        self.last_reported_pointer_capture_serial = None;
        // Android already supplies an arrow before a Wayland client chooses a
        // cursor. Report only subsequent client changes so attach does not
        // incorrectly replace that default with the protocol's null cursor.
        self.last_reported_cursor_serial = Some(self.core.cursor_change_serial());
        configure_launcher_output_resolved(&mut self.core, width, height, density_dpi);
        0
    }

    fn dispatch_and_present(&mut self, time: u32) -> i32 {
        if self.core.dispatch_once().is_err() {
            return -2;
        }
        let mut flags = i32::from(self.core.accepted_client_count() != 0);
        let commit = self.core.surface_commit_count();
        if commit != self.last_presented_commit
            && let Some(window) = self.window.as_mut()
            && copy_last_frame_to_native_window(
                &self.core,
                window,
                &mut self.buffer_width,
                &mut self.buffer_height,
                self.surface_width,
                self.surface_height,
            ) == 0
        {
            self.last_presented_commit = commit;
            self.core.present_frame(time);
            flags |= 1 << 1;
            let signature = launcher_presentation_signature(&self.core.state);
            if self.last_presentation_signature != Some(signature) {
                self.last_presentation_signature = Some(signature);
                flags |= 1 << 2;
            }
        }
        if self.core.state.pending_linux_clipboard_clear {
            flags |= 1 << 3;
        }
        if !self.core.state.pending_linux_copy_fds.is_empty() {
            flags |= 1 << 4;
        }
        if !self.core.state.pending_android_paste_fds.is_empty() {
            flags |= 1 << 5;
        }
        let ime_serial = self.core.ime_change_serial();
        if self.last_reported_ime_serial != Some(ime_serial) {
            self.last_reported_ime_serial = Some(ime_serial);
            flags |= 1 << 6;
        }
        let pointer_capture_serial = self.core.pointer_capture_change_serial();
        if self.last_reported_pointer_capture_serial != Some(pointer_capture_serial) {
            self.last_reported_pointer_capture_serial = Some(pointer_capture_serial);
            flags |= 1 << 7;
        }
        let cursor_serial = self.core.cursor_change_serial();
        if self.last_reported_cursor_serial != Some(cursor_serial) {
            self.last_reported_cursor_serial = Some(cursor_serial);
            flags |= 1 << 8;
        }
        flags
    }
}

#[cfg(target_os = "android")]
impl Drop for LauncherSurfaceCompositor {
    fn drop(&mut self) {
        self.detach_surface();
        self.core.close_socket();
    }
}

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
fn valid_launcher_surface_size(width: i32, height: i32) -> bool {
    const MAX_DIMENSION: i32 = 8192;
    const MAX_PIXELS: i64 = 33_554_432;
    width > 0
        && height > 0
        && width <= MAX_DIMENSION
        && height <= MAX_DIMENSION
        && i64::from(width) * i64::from(height) <= MAX_PIXELS
}

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
fn valid_launcher_density(density_dpi: i32) -> bool {
    (72..=1_000).contains(&density_dpi)
}

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
fn valid_launcher_geometry_percent(geometry_percent: i32) -> bool {
    geometry_percent == 0 || matches!(geometry_percent, 75 | 100 | 125 | 150)
}

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
fn launcher_logical_extent(physical: i32, density_dpi: i32) -> i32 {
    i32::try_from((i64::from(physical) * 160 + i64::from(density_dpi) / 2) / i64::from(density_dpi))
        .unwrap_or(1)
        .max(1)
}

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
fn launcher_auto_density_dpi(width: i32, height: i32, android_density_dpi: i32) -> i32 {
    const MIN_DESKTOP_LOGICAL_EXTENT: i32 = 432;
    let short_extent = width.min(height);
    let density_for_minimum = i32::try_from(
        i64::from(short_extent)
            .saturating_mul(160)
            .saturating_div(i64::from(MIN_DESKTOP_LOGICAL_EXTENT)),
    )
    .unwrap_or(android_density_dpi);
    android_density_dpi
        .min(density_for_minimum)
        .clamp(72, 1_000)
}

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
fn launcher_density_dpi(
    width: i32,
    height: i32,
    android_density_dpi: i32,
    geometry_percent: i32,
) -> i32 {
    let automatic = launcher_auto_density_dpi(width, height, android_density_dpi);
    if geometry_percent == 0 {
        automatic
    } else {
        automatic
            .saturating_mul(geometry_percent)
            .saturating_add(50)
            .saturating_div(100)
            .clamp(72, 1_000)
    }
}

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
fn configure_launcher_output(
    core: &mut CompositorCore,
    width: i32,
    height: i32,
    density_dpi: i32,
    geometry_percent: i32,
) -> u32 {
    if !valid_launcher_surface_size(width, height)
        || !valid_launcher_density(density_dpi)
        || !valid_launcher_geometry_percent(geometry_percent)
    {
        return 0;
    }
    let density_dpi = launcher_density_dpi(width, height, density_dpi, geometry_percent);
    configure_launcher_output_resolved(core, width, height, density_dpi)
}

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
fn configure_launcher_output_resolved(
    core: &mut CompositorCore,
    width: i32,
    height: i32,
    density_dpi: i32,
) -> u32 {
    let logical_width = launcher_logical_extent(width, density_dpi);
    let logical_height = launcher_logical_extent(height, density_dpi);
    let scale = density_dpi.saturating_add(159).saturating_div(160).max(1);
    let fractional_scale = u32::try_from(
        density_dpi
            .saturating_mul(120)
            .saturating_add(80)
            .saturating_div(160),
    )
    .unwrap_or(120)
    .max(1);
    core.configure_output_physical(
        logical_width,
        logical_height,
        width,
        height,
        scale,
        fractional_scale,
    )
}

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
fn wait_for_clipboard_descriptor(
    descriptor: RawFd,
    events: i16,
    deadline: Instant,
) -> io::Result<bool> {
    loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return Ok(false);
        }
        let timeout = remaining.as_millis().clamp(1, i32::MAX as u128) as i32;
        match syscall_ffi::poll_one(descriptor, events, timeout) {
            Ok(Some(revents)) => {
                if revents & libc::POLLNVAL != 0 {
                    return Err(io::Error::from_raw_os_error(libc::EBADF));
                }
                return Ok(true);
            }
            Ok(None) => return Ok(false),
            Err(error) if error.raw_os_error() == Some(libc::EINTR) => {}
            Err(error) => {
                return Err(error);
            }
        }
    }
}

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
fn set_clipboard_descriptor_nonblocking(descriptor: RawFd) -> io::Result<()> {
    syscall_ffi::set_nonblocking(descriptor)
}

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
fn read_clipboard_descriptor(descriptor: File, output: &mut [u8], timeout_millis: u64) -> i32 {
    if set_clipboard_descriptor_nonblocking(descriptor.as_raw_fd()).is_err() {
        return -1;
    }
    let deadline = Instant::now() + Duration::from_millis(timeout_millis);
    let mut length = 0_usize;
    loop {
        match wait_for_clipboard_descriptor(descriptor.as_raw_fd(), libc::POLLIN, deadline) {
            Ok(true) => {}
            Ok(false) => return -2,
            Err(_) => return -1,
        }
        let mut overflow_probe = [0_u8; 1];
        let destination = if length < output.len() {
            &mut output[length..]
        } else {
            &mut overflow_probe
        };
        let read = match syscall_ffi::read(descriptor.as_raw_fd(), destination) {
            Ok(read) => read,
            Err(error)
                if matches!(error.raw_os_error(), Some(libc::EINTR) | Some(libc::EAGAIN)) =>
            {
                continue;
            }
            Err(_) => return -1,
        };
        if read == 0 {
            return i32::try_from(length).unwrap_or(-1);
        }
        if length == output.len() {
            return -3;
        }
        length += read;
    }
}

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
fn write_clipboard_descriptor(descriptor: File, input: &[u8], timeout_millis: u64) -> i32 {
    if set_clipboard_descriptor_nonblocking(descriptor.as_raw_fd()).is_err() {
        return -1;
    }
    let deadline = Instant::now() + Duration::from_millis(timeout_millis);
    let mut offset = 0_usize;
    while offset < input.len() {
        match wait_for_clipboard_descriptor(descriptor.as_raw_fd(), libc::POLLOUT, deadline) {
            Ok(true) => {}
            Ok(false) => return -2,
            Err(_) => return -1,
        }
        let written = match syscall_ffi::write(descriptor.as_raw_fd(), &input[offset..]) {
            Ok(written) => written,
            Err(error)
                if matches!(error.raw_os_error(), Some(libc::EINTR) | Some(libc::EAGAIN)) =>
            {
                continue;
            }
            Err(_) => return -1,
        };
        if written == 0 {
            return -1;
        }
        offset += written;
    }
    i32::try_from(offset).unwrap_or(-1)
}

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
fn android_key_to_evdev(key: i32) -> Option<u32> {
    const LETTERS: [u32; 26] = [
        30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50, 49, 24, 25, 16, 19, 31, 20, 22, 47, 17,
        45, 21, 44,
    ];
    const DIGITS: [u32; 10] = [11, 2, 3, 4, 5, 6, 7, 8, 9, 10];
    if (29..=54).contains(&key) {
        return LETTERS.get((key - 29) as usize).copied();
    }
    if (7..=16).contains(&key) {
        return DIGITS.get((key - 7) as usize).copied();
    }
    Some(match key {
        66 | 23 => 28,
        67 => 14,
        112 => 111,
        62 => 57,
        61 => 15,
        111 | 4 => 1,
        81 | 70 => 13,
        69 => 12,
        71 => 26,
        72 => 27,
        74 => 39,
        75 => 40,
        68 => 41,
        73 => 43,
        55 => 51,
        56 => 52,
        76 => 53,
        59 => 42,
        60 => 54,
        115 => 58,
        113 => 29,
        114 => 97,
        57 => 56,
        58 => 100,
        117 => 125,
        118 => 126,
        82 => 139,
        19 => 103,
        21 => 105,
        22 => 106,
        20 => 108,
        122 => 102,
        123 => 107,
        92 => 104,
        93 => 109,
        124 => 110,
        131..=140 => 59 + (key - 131) as u32,
        141 => 87,
        142 => 88,
        _ => return None,
    })
}

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
fn android_button_to_evdev(button: i32) -> Option<u32> {
    Some(match button {
        1 => 272,
        2 => 273,
        4 => 274,
        8 => 275,
        16 => 276,
        _ => return None,
    })
}

fn android_meta_to_wayland(meta: i32) -> u32 {
    const ANDROID_SHIFT: i32 = 0x0000_00c1;
    const ANDROID_ALT: i32 = 0x0000_0032;
    const ANDROID_CONTROL: i32 = 0x0000_7000;
    const ANDROID_META: i32 = 0x0007_0000;
    u32::from(meta & ANDROID_SHIFT != 0)
        | (u32::from(meta & ANDROID_CONTROL != 0) << 2)
        | (u32::from(meta & ANDROID_ALT != 0) << 3)
        | (u32::from(meta & ANDROID_META != 0) << 6)
}

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
fn dispatch_launcher_input_record(core: &mut CompositorCore, record: [i32; 6]) -> Result<u32, ()> {
    const MIN_COORDINATE: i32 = -8192;
    const MAX_COORDINATE: i32 = 32768;
    const MAX_AXIS_FIXED: i32 = 120_000;
    const MAX_RELATIVE_FIXED: i32 = 16_384_000;
    let [kind, a, b, c, d, e] = record;
    let coordinate = |value: i32| (MIN_COORDINATE..=MAX_COORDINATE).contains(&value);
    match kind {
        1 if (0..MAX_ACTIVE_TOUCHES as i32).contains(&a)
            && coordinate(b)
            && coordinate(c)
            && e == 0 =>
        {
            Ok(core.touch_down(a, f64::from(b), f64::from(c), d as u32))
        }
        2 if (0..MAX_ACTIVE_TOUCHES as i32).contains(&a)
            && coordinate(b)
            && coordinate(c)
            && e == 0 =>
        {
            Ok(core.touch_motion(a, f64::from(b), f64::from(c), d as u32))
        }
        3 if (0..MAX_ACTIVE_TOUCHES as i32).contains(&a) && c == 0 && d == 0 && e == 0 => {
            Ok(core.touch_up(a, b as u32))
        }
        4 if a == 0 && b == 0 && c == 0 && d == 0 && e == 0 => Ok(core.touch_cancel()),
        5 if (1..=512).contains(&a) && (0..=2).contains(&b) && d >= 0 && e == 0 => {
            let Some(key) = android_key_to_evdev(a) else {
                return Ok(0);
            };
            let modifiers = android_meta_to_wayland(d);
            Ok(match b {
                0 => core.keyboard_key_with_modifiers(key, false, c as u32, modifiers),
                1 => core.keyboard_key_with_modifiers(key, true, c as u32, modifiers),
                2 => core.keyboard_repeat_with_modifiers(key, c as u32, modifiers),
                _ => unreachable!("bounded key action"),
            })
        }
        6 if coordinate(a) && coordinate(b) && d == 0 && e == 0 => {
            Ok(core.pointer_motion(f64::from(a), f64::from(b), c as u32))
        }
        7 if (0..=1).contains(&a) && c == 0 && d == 0 && e == 0 => {
            Ok(core.pointer_button(a != 0, b as u32))
        }
        8 if (0..=1).contains(&b) && d == 0 && e == 0 => {
            let Some(button) = android_button_to_evdev(a) else {
                return Err(());
            };
            Ok(core.pointer_button_code(button, b != 0, c as u32))
        }
        9 if (a != 0 || b != 0)
            && (-MAX_AXIS_FIXED..=MAX_AXIS_FIXED).contains(&a)
            && (-MAX_AXIS_FIXED..=MAX_AXIS_FIXED).contains(&b)
            && d == 0
            && e == 0 =>
        {
            Ok(core.pointer_axis(f64::from(a) / 1000.0, f64::from(b) / 1000.0, c as u32))
        }
        10 if (0..=1).contains(&a) && c == 0 && d == 0 && e == 0 => {
            Ok(core.set_host_active(a != 0))
        }
        11 if (a != 0 || b != 0)
            && (-MAX_RELATIVE_FIXED..=MAX_RELATIVE_FIXED).contains(&a)
            && (-MAX_RELATIVE_FIXED..=MAX_RELATIVE_FIXED).contains(&b)
            && (-MAX_RELATIVE_FIXED..=MAX_RELATIVE_FIXED).contains(&c)
            && (-MAX_RELATIVE_FIXED..=MAX_RELATIVE_FIXED).contains(&d) =>
        {
            Ok(core.pointer_relative_motion(
                f64::from(a) / 1000.0,
                f64::from(b) / 1000.0,
                f64::from(c) / 1000.0,
                f64::from(d) / 1000.0,
                e as u32,
            ))
        }
        12 if a == 0 && b == 0 && c == 0 && d == 0 && e == 0 => Ok(core.cancel_pointer_capture()),
        _ => Err(()),
    }
}

#[cfg(target_os = "android")]
fn copy_last_frame_to_native_window(
    core: &CompositorCore,
    window: &mut android_graphics_ffi::NativeWindow,
    buffer_width: &mut i32,
    buffer_height: &mut i32,
    surface_width: i32,
    surface_height: i32,
) -> i32 {
    let Some(frame) = launcher_presentation_frame(&core.state) else {
        return -1;
    };
    let (Ok(width), Ok(height)) = (i32::try_from(frame.width), i32::try_from(frame.height)) else {
        return -2;
    };
    if width != *buffer_width || height != *buffer_height {
        if window
            .set_rgba_geometry(width, height, surface_width, surface_height)
            .is_err()
        {
            return -2;
        }
        *buffer_width = width;
        *buffer_height = height;
    }
    let (result, posted) = match window
        .with_locked_rgba(|buffer| copy_frame_to_native_window_buffer(&frame, buffer))
    {
        Ok(result) => result,
        Err(error) => return error,
    };
    if result != 0 {
        result
    } else if posted != 0 {
        -7
    } else {
        0
    }
}

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
fn launcher_presentation_frame(state: &CompositorState) -> Option<Arc<CommittedFrame>> {
    let fallback = state.last_frame.as_ref()?.clone();
    if !state.tile_toplevels
        || state.cursor_frame.is_some()
        || state.popups.iter().any(|popup| {
            popup.is_alive()
                && popup
                    .data::<XdgPopupData>()
                    .is_some_and(|data| !data.dismissed.load(Ordering::Acquire))
        })
    {
        return Some(fallback);
    }
    let root_surface = state.root_surface.as_ref()?;
    let layout = toplevel_layout(state)?;
    if layout.overlay_primary
        || layout.root_x != 0
        || layout.root_y != 0
        || layout.root_width != state.output_width
        || layout.root_height != state.output_height
    {
        return Some(fallback);
    }
    let data = root_surface.data::<SurfaceData>()?;
    let surface = data.inner.lock().unwrap_or_else(|error| error.into_inner());
    let frame = surface.committed_frame.as_ref()?.clone();
    if surface.committed_buffer_transform != BufferTransform::Normal
        || surface.committed_viewport_source.is_some()
        || !surface.children_below.is_empty()
        || !surface.children_above.is_empty()
    {
        return Some(fallback);
    }
    drop(surface);
    let geometry = window_geometry_for_surface(root_surface).unwrap_or(WindowGeometry {
        x: 0,
        y: 0,
        width: frame.width as i32,
        height: frame.height as i32,
    });
    if geometry.x != 0
        || geometry.y != 0
        || geometry.width != frame.width as i32
        || geometry.height != frame.height as i32
    {
        return Some(fallback);
    }
    Some(original_buffer_frame(&frame))
}

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
fn launcher_presentation_component(state: &CompositorState, component: i32) -> i32 {
    let selected = launcher_presentation_frame(state);
    let root = state.root_surface.as_ref().and_then(surface_frame);
    let original = root.as_ref().map(original_buffer_frame);
    let root_geometry = root.as_ref().map(|frame| {
        state
            .root_surface
            .as_ref()
            .and_then(window_geometry_for_surface)
            .unwrap_or(WindowGeometry {
                x: 0,
                y: 0,
                width: frame.width as i32,
                height: frame.height as i32,
            })
    });
    let root_layout = toplevel_layout(state);
    let content_layout = root_content_layout(state);
    let primary_pending_configures = state
        .primary_toplevel
        .as_ref()
        .and_then(toplevel_surface)
        .and_then(|surface| {
            surface.data::<SurfaceData>().and_then(|data| {
                data.inner
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                    .xdg_surface
                    .clone()
            })
        })
        .and_then(|xdg_surface| {
            xdg_surface.data::<XdgSurfaceData>().map(|data| {
                data.state
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                    .pending_configures
                    .len()
            })
        })
        .and_then(|count| i32::try_from(count).ok())
        .unwrap_or(-1);
    let surface_state = state
        .root_surface
        .as_ref()
        .and_then(|surface| surface.data::<SurfaceData>())
        .map(|data| data.inner.lock().unwrap_or_else(|error| error.into_inner()));
    match component {
        0 => selected.as_ref().map_or(0, |frame| frame.width as i32),
        1 => selected.as_ref().map_or(0, |frame| frame.height as i32),
        2 => state
            .last_frame
            .as_ref()
            .map_or(0, |frame| frame.width as i32),
        3 => state
            .last_frame
            .as_ref()
            .map_or(0, |frame| frame.height as i32),
        4 => original.as_ref().map_or(0, |frame| frame.width as i32),
        5 => original.as_ref().map_or(0, |frame| frame.height as i32),
        6 => root.as_ref().map_or(0, |frame| frame.width as i32),
        7 => root.as_ref().map_or(0, |frame| frame.height as i32),
        8 => surface_state
            .as_ref()
            .map_or(0, |surface| surface.committed_buffer_scale),
        9 => {
            let mut reasons = 0;
            if state.cursor_frame.is_some() {
                reasons |= 1;
            }
            if state.popups.iter().any(|popup| {
                popup.is_alive()
                    && popup
                        .data::<XdgPopupData>()
                        .is_some_and(|data| !data.dismissed.load(Ordering::Acquire))
            }) {
                reasons |= 1 << 1;
            }
            if surface_state.as_ref().is_some_and(|surface| {
                surface.committed_buffer_transform != BufferTransform::Normal
            }) {
                reasons |= 1 << 2;
            }
            if surface_state
                .as_ref()
                .is_some_and(|surface| surface.committed_viewport_source.is_some())
            {
                reasons |= 1 << 3;
            }
            if surface_state.as_ref().is_some_and(|surface| {
                !surface.children_below.is_empty() || !surface.children_above.is_empty()
            }) {
                reasons |= 1 << 4;
            }
            reasons
        }
        10 => state.output_width,
        11 => state.output_height,
        12 => state.output_mode_width,
        13 => state.output_mode_height,
        14 => i32::try_from(state.surface_commit_count).unwrap_or(i32::MAX),
        15 => i32::try_from(state.xdg_ack_count).unwrap_or(i32::MAX),
        16 => i32::try_from(state.next_configure_serial).unwrap_or(i32::MAX),
        17 => primary_pending_configures,
        18 => i32::try_from(state.output_event_count).unwrap_or(i32::MAX),
        19 => i32::try_from(state.output_binds).unwrap_or(i32::MAX),
        20..=23 => root_geometry.map_or(0, |geometry| match component {
            20 => geometry.x,
            21 => geometry.y,
            22 => geometry.width,
            23 => geometry.height,
            _ => 0,
        }),
        24..=27 => root_layout.map_or(0, |layout| match component {
            24 => layout.root_x,
            25 => layout.root_y,
            26 => layout.root_width,
            27 => layout.root_height,
            _ => 0,
        }),
        28..=31 => content_layout.map_or(0, |layout| match component {
            28 => layout.0,
            29 => layout.1,
            30 => layout.2,
            31 => layout.3,
            _ => 0,
        }),
        32..=33 => root_layout.map_or(0, |layout| match component {
            32 => i32::try_from(layout.output_width).unwrap_or(i32::MAX),
            33 => i32::try_from(layout.output_height).unwrap_or(i32::MAX),
            _ => 0,
        }),
        34 => requested_window_state_flags(state),
        _ => -1,
    }
}

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
fn requested_window_state_flags(state: &CompositorState) -> i32 {
    active_or_primary(
        state.active_toplevel.as_ref(),
        state.primary_toplevel.as_ref(),
    )
    .and_then(|toplevel| toplevel.data::<XdgToplevelData>())
    .map_or(0, |data| {
        i32::from(data.fullscreen_requested.load(Ordering::Acquire))
            | (i32::from(data.maximized_requested.load(Ordering::Acquire)) << 1)
    })
}

fn active_or_primary<'a, T>(active: Option<&'a T>, primary: Option<&'a T>) -> Option<&'a T> {
    active.or(primary)
}

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
fn launcher_presentation_signature(state: &CompositorState) -> [i32; 6] {
    let root = state.root_surface.as_ref().and_then(surface_frame);
    let original = root.as_ref().map(original_buffer_frame);
    let buffer_scale = state
        .root_surface
        .as_ref()
        .and_then(|surface| surface.data::<SurfaceData>())
        .map(|data| data.inner.lock().unwrap_or_else(|error| error.into_inner()))
        .map_or(0, |surface| surface.committed_buffer_scale);
    [
        original.as_ref().map_or(0, |frame| frame.width as i32),
        original.as_ref().map_or(0, |frame| frame.height as i32),
        root.as_ref().map_or(0, |frame| frame.width as i32),
        root.as_ref().map_or(0, |frame| frame.height as i32),
        buffer_scale,
        requested_window_state_flags(state),
    ]
}

#[cfg(target_os = "android")]
fn copy_frame_to_native_window_buffer(
    frame: &CommittedFrame,
    buffer: android_graphics_ffi::WindowBuffer<'_>,
) -> i32 {
    let android_graphics_ffi::WindowBuffer {
        width,
        height,
        stride_bytes: destination_stride,
        pixels: destination,
    } = buffer;
    destination.fill(0);

    let frame_width = frame.width as usize;
    let frame_height = frame.height as usize;
    if frame_width == 0 || frame_height == 0 {
        return -4;
    }
    let Some(source_stride) = frame_width.checked_mul(4) else {
        return -4;
    };
    let frame_pixels = frame.pixels();
    if width == frame_width && height == frame_height {
        for row in 0..height {
            let source_start = row * source_stride;
            let destination_start = row * destination_stride;
            if copy_wayland_pixels_to_android(
                &frame_pixels[source_start..source_start + source_stride],
                frame.format,
                &mut destination[destination_start..destination_start + source_stride],
            )
            .is_err()
            {
                return -5;
            }
        }
        return 0;
    }
    for destination_y in 0..height {
        let source_y = destination_y.saturating_mul(frame_height) / height;
        for destination_x in 0..width {
            let source_x = destination_x.saturating_mul(frame_width) / width;
            let source = source_y
                .saturating_mul(source_stride)
                .saturating_add(source_x.saturating_mul(4));
            let target = destination_y
                .saturating_mul(destination_stride)
                .saturating_add(destination_x.saturating_mul(4));
            let Some(source_pixel) = frame_pixels.get(source..source + 4) else {
                return -5;
            };
            let Some(destination_pixel) = destination.get_mut(target..target + 4) else {
                return -5;
            };
            destination_pixel[0] = source_pixel[2];
            destination_pixel[1] = source_pixel[1];
            destination_pixel[2] = source_pixel[0];
            destination_pixel[3] = if frame.format == wl_shm::Format::Argb8888 {
                source_pixel[3]
            } else {
                u8::MAX
            };
        }
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

const MAX_PENDING_DAMAGE_RECTANGLES: usize = 64;

fn push_bounded_damage(
    damage: &mut Vec<RegionRectangle>,
    full_damage: &mut bool,
    rectangle: RegionRectangle,
) {
    if *full_damage {
        return;
    }
    if damage.len() < MAX_PENDING_DAMAGE_RECTANGLES {
        damage.push(rectangle);
    } else {
        damage.clear();
        *full_damage = true;
    }
}

fn push_accumulated_damage(damage: &mut Vec<RegionRectangle>, rectangle: RegionRectangle) {
    if damage.len() < MAX_PENDING_DAMAGE_RECTANGLES {
        damage.push(rectangle);
        return;
    }
    let bounds = damage
        .iter()
        .copied()
        .chain(std::iter::once(rectangle))
        .reduce(RegionRectangle::union);
    damage.clear();
    if let Some(bounds) = bounds {
        damage.push(bounds);
    }
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
    patch: Mutex<Vec<u8>>,
    offset: usize,
    width: usize,
    height: usize,
    stride: usize,
    format: wl_shm::Format,
}

pub struct ShmBufferData {
    inner: Arc<ShmBufferInner>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum ShmReadDamage {
    Full,
    Unchanged,
    Region(RegionRectangle),
}

struct ShmSnapshotState<'a> {
    surface_damage: &'a [RegionRectangle],
    buffer_damage: &'a [RegionRectangle],
    transform: BufferTransform,
    scale: i32,
    viewport_active: bool,
    allow_in_place: bool,
    force_full_damage: bool,
}

impl ShmBufferInner {
    fn snapshot(
        &self,
        previous: Option<&Arc<CommittedFrame>>,
        state: ShmSnapshotState<'_>,
    ) -> io::Result<Arc<CommittedFrame>> {
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

        let previous = previous.map(original_buffer_frame);
        let compatible_previous = previous.filter(|frame| {
            frame.width == self.width as u32
                && frame.height == self.height as u32
                && frame.format == self.format
                && frame.pixels().len() == frame_bytes
        });
        let damage = if state.force_full_damage {
            ShmReadDamage::Full
        } else {
            self.read_damage(
                compatible_previous.is_some(),
                state.surface_damage,
                state.buffer_damage,
                state.transform,
                state.scale,
                state.viewport_active,
            )
        };
        if state.allow_in_place {
            if let Some(previous) = compatible_previous.as_ref() {
                match damage {
                    ShmReadDamage::Unchanged => return Ok(Arc::clone(previous)),
                    ShmReadDamage::Region(region) => {
                        self.apply_region_patch(previous, row_bytes, region)?;
                        return Ok(Arc::clone(previous));
                    }
                    ShmReadDamage::Full => {}
                }
            }
        }
        let mut pixels = match (compatible_previous, damage) {
            (Some(previous), ShmReadDamage::Unchanged | ShmReadDamage::Region(_)) => {
                previous.pixels().clone()
            }
            _ => vec![0u8; frame_bytes],
        };
        self.read_pixels(&mut pixels, row_bytes, damage)?;
        Ok(Arc::new(CommittedFrame::new(
            self.width as u32,
            self.height as u32,
            self.format,
            pixels,
            None,
        )))
    }

    fn apply_region_patch(
        &self,
        frame: &CommittedFrame,
        row_bytes: usize,
        region: RegionRectangle,
    ) -> io::Result<()> {
        let left = usize::try_from(region.x)
            .map_err(|_| io::Error::other("negative SHM damage origin"))?;
        let top = usize::try_from(region.y)
            .map_err(|_| io::Error::other("negative SHM damage origin"))?;
        let width = usize::try_from(region.width)
            .map_err(|_| io::Error::other("negative SHM damage width"))?;
        let height = usize::try_from(region.height)
            .map_err(|_| io::Error::other("negative SHM damage height"))?;
        let destination_left = left
            .checked_mul(4)
            .ok_or_else(|| io::Error::other("SHM damage offset overflow"))?;
        let damage_bytes = width
            .checked_mul(4)
            .ok_or_else(|| io::Error::other("SHM damage size overflow"))?;
        let patch_bytes = damage_bytes
            .checked_mul(height)
            .ok_or_else(|| io::Error::other("SHM damage size overflow"))?;
        let mut patch = self.patch.lock().unwrap_or_else(|error| error.into_inner());
        patch.resize(patch_bytes, 0);
        {
            let pool = self.pool.lock().unwrap_or_else(|error| error.into_inner());
            for (row, destination) in patch.chunks_exact_mut(damage_bytes).enumerate() {
                let source_row = top
                    .checked_add(row)
                    .ok_or_else(|| io::Error::other("SHM damage offset overflow"))?;
                let source_offset = self.source_offset(source_row, destination_left)?;
                pool.file.read_exact_at(destination, source_offset as u64)?;
            }
        }
        let mut pixels = frame.pixels();
        for (row, source) in patch.chunks_exact(damage_bytes).enumerate() {
            let destination_start = top
                .checked_add(row)
                .and_then(|row| row.checked_mul(row_bytes))
                .and_then(|offset| offset.checked_add(destination_left))
                .ok_or_else(|| io::Error::other("SHM damage offset overflow"))?;
            let destination_end = destination_start
                .checked_add(damage_bytes)
                .ok_or_else(|| io::Error::other("SHM damage size overflow"))?;
            pixels[destination_start..destination_end].copy_from_slice(source);
        }
        Ok(())
    }

    fn read_pixels(
        &self,
        pixels: &mut [u8],
        row_bytes: usize,
        damage: ShmReadDamage,
    ) -> io::Result<()> {
        let pool = self.pool.lock().unwrap_or_else(|error| error.into_inner());
        match damage {
            ShmReadDamage::Full => {
                for (row, destination) in pixels.chunks_exact_mut(row_bytes).enumerate() {
                    let source_offset = self.source_offset(row, 0)?;
                    pool.file.read_exact_at(destination, source_offset as u64)?;
                }
            }
            ShmReadDamage::Unchanged => {}
            ShmReadDamage::Region(region) => {
                let left = usize::try_from(region.x)
                    .map_err(|_| io::Error::other("negative SHM damage origin"))?;
                let top = usize::try_from(region.y)
                    .map_err(|_| io::Error::other("negative SHM damage origin"))?;
                let width = usize::try_from(region.width)
                    .map_err(|_| io::Error::other("negative SHM damage width"))?;
                let height = usize::try_from(region.height)
                    .map_err(|_| io::Error::other("negative SHM damage height"))?;
                let destination_left = left
                    .checked_mul(4)
                    .ok_or_else(|| io::Error::other("SHM damage offset overflow"))?;
                let damage_bytes = width
                    .checked_mul(4)
                    .ok_or_else(|| io::Error::other("SHM damage size overflow"))?;
                for row in top..top + height {
                    let destination_start = row
                        .checked_mul(row_bytes)
                        .and_then(|offset| offset.checked_add(destination_left))
                        .ok_or_else(|| io::Error::other("SHM damage offset overflow"))?;
                    let destination_end = destination_start
                        .checked_add(damage_bytes)
                        .ok_or_else(|| io::Error::other("SHM damage size overflow"))?;
                    let source_offset = self.source_offset(row, destination_left)?;
                    pool.file.read_exact_at(
                        &mut pixels[destination_start..destination_end],
                        source_offset as u64,
                    )?;
                }
            }
        }
        Ok(())
    }

    fn source_offset(&self, row: usize, byte_offset: usize) -> io::Result<usize> {
        self.offset
            .checked_add(
                row.checked_mul(self.stride)
                    .ok_or_else(|| io::Error::other("SHM source offset overflow"))?,
            )
            .and_then(|offset| offset.checked_add(byte_offset))
            .ok_or_else(|| io::Error::other("SHM source offset overflow"))
    }

    fn read_damage(
        &self,
        has_compatible_previous: bool,
        surface_damage: &[RegionRectangle],
        buffer_damage: &[RegionRectangle],
        transform: BufferTransform,
        scale: i32,
        viewport_active: bool,
    ) -> ShmReadDamage {
        if !has_compatible_previous || (surface_damage.is_empty() && buffer_damage.is_empty()) {
            return ShmReadDamage::Full;
        }
        if viewport_active && !surface_damage.is_empty() {
            return ShmReadDamage::Full;
        }
        let mut combined: Option<RegionRectangle> = None;
        for damage in buffer_damage {
            if let Some(damage) = damage.clip(self.width as u32, self.height as u32) {
                combined = Some(match combined {
                    Some(current) => current.union(damage),
                    None => damage,
                });
            }
        }
        for damage in surface_damage {
            let Some(damage) = transform.surface_damage_to_buffer(
                *damage,
                self.width as u32,
                self.height as u32,
                scale,
            ) else {
                let (physical_width, physical_height) =
                    transform.surface_size(self.width as u32, self.height as u32);
                let valid_scale = u32::try_from(scale)
                    .ok()
                    .filter(|scale| *scale > 0)
                    .is_some_and(|scale| {
                        physical_width % scale == 0 && physical_height % scale == 0
                    });
                if !valid_scale {
                    return ShmReadDamage::Full;
                }
                continue;
            };
            combined = Some(match combined {
                Some(current) => current.union(damage),
                None => damage,
            });
        }
        combined.map_or(ShmReadDamage::Unchanged, ShmReadDamage::Region)
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
                if toplevel_limit_reached(state.toplevels.len()) {
                    resource.post_error(
                        xdg_surface::Error::AlreadyConstructed,
                        "Archphene supports at most 32 simultaneous toplevel windows",
                    );
                    return;
                }
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
                        size_constraints: Mutex::new(ToplevelSizeConstraints::default()),
                        windowed_size: Mutex::new(None),
                        fullscreen_requested: AtomicBool::new(false),
                        maximized_requested: AtomicBool::new(false),
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
                /*
                 * Toolkits may retain the serial which initiated a menu
                 * transition even when focus or accessibility-generated input
                 * produces a newer serial before xdg_popup.grab is dispatched.
                 * Accept any bounded recent input serial owned by this client,
                 * as wl_data_device selection validation already does.
                 */
                let serial_matches_client = state.selection_serials.iter().rev().any(|candidate| {
                    candidate.serial == effective_serial
                        && candidate.surface.id().same_client_as(&data.parent.id())
                });
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
                            toplevel_size: None,
                            restores_windowed: false,
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
                if invalid_toplevel_parent(resource, &parent) {
                    resource.post_error(
                        xdg_toplevel::Error::InvalidParent,
                        "xdg_toplevel parent must be an acyclic toplevel from the same client",
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
            | xdg_toplevel::Request::SetMinimized => {}
            xdg_toplevel::Request::SetMaximized => {
                remember_windowed_toplevel_size(resource);
                data.maximized_requested.store(true, Ordering::Release);
                queue_requested_toplevel_configure(state, resource, false);
            }
            xdg_toplevel::Request::UnsetMaximized => {
                data.maximized_requested.store(false, Ordering::Release);
                queue_requested_toplevel_configure(state, resource, true);
            }
            xdg_toplevel::Request::SetFullscreen { .. } => {
                remember_windowed_toplevel_size(resource);
                data.fullscreen_requested.store(true, Ordering::Release);
                queue_requested_toplevel_configure(state, resource, false);
            }
            xdg_toplevel::Request::UnsetFullscreen => {
                data.fullscreen_requested.store(false, Ordering::Release);
                queue_requested_toplevel_configure(state, resource, true);
            }
            xdg_toplevel::Request::SetMaxSize { width, height } => {
                if width < 0 || height < 0 {
                    resource.post_error(
                        xdg_toplevel::Error::InvalidSize,
                        "minimum and maximum sizes cannot be negative",
                    );
                } else {
                    let mut constraints = data
                        .size_constraints
                        .lock()
                        .unwrap_or_else(|error| error.into_inner());
                    constraints.max_width = width;
                    constraints.max_height = height;
                }
            }
            xdg_toplevel::Request::SetMinSize { width, height } => {
                if width < 0 || height < 0 {
                    resource.post_error(
                        xdg_toplevel::Error::InvalidSize,
                        "minimum and maximum sizes cannot be negative",
                    );
                } else {
                    let mut constraints = data
                        .size_constraints
                        .lock()
                        .unwrap_or_else(|error| error.into_inner());
                    constraints.min_width = width;
                    constraints.min_height = height;
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
        for toplevel in &state.toplevels {
            let Some(child_data) = toplevel.data::<XdgToplevelData>() else {
                continue;
            };
            let mut parent = child_data
                .parent
                .lock()
                .unwrap_or_else(|error| error.into_inner());
            if parent
                .as_ref()
                .is_some_and(|candidate| candidate.id() == resource.id())
            {
                *parent = None;
            }
        }
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
                state.active_toplevel = Some(toplevel.clone());
                configure_toplevel_activation(state, &toplevel, true);
                state.root_surface = Some(surface.clone());
                state.root_frame = Some(frame);
                state.pointer_focus_surface = Some(surface.clone());
                state.pointer_inside = false;
                state.pointer_buttons = 0;
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

fn toplevel_limit_reached(count: usize) -> bool {
    count >= MAX_TOPLEVELS
}

fn invalid_toplevel_parent(resource: &XdgToplevel, parent: &Option<XdgToplevel>) -> bool {
    let mut current = parent.clone();
    for _ in 0..MAX_TOPLEVELS {
        let Some(candidate) = current else {
            return false;
        };
        if candidate.id() == resource.id() || !candidate.id().same_client_as(&resource.id()) {
            return true;
        }
        let Some(candidate_data) = candidate.data::<XdgToplevelData>() else {
            return true;
        };
        current = candidate_data
            .parent
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .clone();
    }
    current.is_some()
}

fn create_keymap_file() -> io::Result<File> {
    let file = syscall_ffi::memfd(
        c"archphene-keymap",
        libc::MFD_CLOEXEC | libc::MFD_ALLOW_SEALING,
    )?;
    file.set_len(XKB_KEYMAP.len() as u64)?;
    file.write_all_at(XKB_KEYMAP, 0)?;
    let seals = libc::F_SEAL_SHRINK | libc::F_SEAL_GROW | libc::F_SEAL_WRITE | libc::F_SEAL_SEAL;
    syscall_ffi::add_seals(&file, seals)?;
    Ok(file)
}

fn remember_selection_serial(state: &mut CompositorState, serial: u32, surface: WlSurface) {
    const MAX_RECENT_SERIALS: usize = 32;
    state
        .selection_serials
        .push_back(PopupGrabSerial { serial, surface });
    while state.selection_serials.len() > MAX_RECENT_SERIALS {
        state.selection_serials.pop_front();
    }
}

fn set_keyboard_focus(state: &mut CompositorState, mut surface: Option<WlSurface>) {
    if !state.host_active {
        surface = None;
    }
    if state.keyboard_focus_surface.as_ref().map(Resource::id) == surface.as_ref().map(Resource::id)
    {
        return;
    }

    state.selection_focus_dirty = true;
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
            state.ime_change_serial = state.ime_change_serial.wrapping_add(1);
        }
        state.pressed_keys.clear();
        state.reported_modifiers = 0;
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
        remember_selection_serial(state, serial, surface.clone());
        state.keyboard_focus_surface = Some(surface);
    } else {
        state.pressed_keys.clear();
        state.reported_modifiers = 0;
    }
}

fn keyboard_focus_owner(surface: &WlSurface, depth: usize) -> Option<WlSurface> {
    if depth > 64 {
        return None;
    }
    let data = surface.data::<SurfaceData>()?;
    let (role, parent) = {
        let surface_state = data.inner.lock().unwrap_or_else(|error| error.into_inner());
        (
            surface_state.role,
            surface_state
                .subsurface
                .as_ref()
                .and_then(|subsurface| subsurface.data::<SubsurfaceData>())
                .map(|subsurface| subsurface.parent.clone()),
        )
    };
    match role {
        Some(SurfaceRole::XdgToplevel | SurfaceRole::XdgPopup) => Some(surface.clone()),
        Some(SurfaceRole::Subsurface) => keyboard_focus_owner(&parent?, depth.saturating_add(1)),
        Some(SurfaceRole::Cursor) | None => None,
    }
}

fn pointer_keyboard_focus_surface(
    state: &CompositorState,
    hit_surface: &WlSurface,
) -> Option<WlSurface> {
    if let Some(grab) = state.popup_grab.as_ref().filter(|grab| grab.active) {
        return grab
            .stack
            .last()
            .and_then(|popup| popup.data::<XdgPopupData>())
            .and_then(|data| data.xdg_surface.data::<XdgSurfaceData>())
            .map(|data| data.wl_surface.clone())
            .or_else(|| Some(grab.root.clone()));
    }
    keyboard_focus_owner(hit_surface, 0)
}

fn keyboard_focus_toplevel(
    state: &CompositorState,
    focus_surface: &WlSurface,
) -> Option<XdgToplevel> {
    let surface_data = focus_surface.data::<SurfaceData>()?;
    let (toplevel, xdg_surface) = {
        let surface = surface_data
            .inner
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        (surface.xdg_toplevel.clone(), surface.xdg_surface.clone())
    };
    if let Some(toplevel) = toplevel {
        return Some(toplevel);
    }
    let ancestor = xdg_toplevel_ancestor_surface(&xdg_surface?, 0)?;
    state.toplevels.iter().find_map(|candidate| {
        toplevel_surface(candidate)
            .filter(|surface| surface.id() == ancestor.id())
            .map(|_| candidate.clone())
    })
}
const TEXT_MIME_TYPES: [&str; 2] = ["text/plain;charset=utf-8", "text/plain"];
const HTML_MIME_TYPE: &str = "text/html";
const URI_LIST_MIME_TYPE: &str = "text/uri-list";
const ANDROID_DRAG_MIME_TYPES: [&str; 3] =
    [TEXT_MIME_TYPES[0], TEXT_MIME_TYPES[1], URI_LIST_MIME_TYPE];

fn create_cloexec_pipe() -> io::Result<(File, File)> {
    syscall_ffi::cloexec_pipe()
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

fn data_device_has_keyboard_focus(state: &CompositorState, device: &WlDataDevice) -> bool {
    state
        .keyboard_focus_surface
        .as_ref()
        .is_some_and(|surface| device.id().same_client_as(&surface.id()))
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
    let focused_surface = state.keyboard_focus_surface.clone();
    for device in devices.iter().filter(|device| {
        focused_surface
            .as_ref()
            .is_some_and(|surface| device.id().same_client_as(&surface.id()))
    }) {
        publish_offer_to_device(
            state,
            handle,
            device,
            ClipboardOfferSource::Wayland(source.clone()),
            mime_types.clone(),
        );
    }
}

fn android_clipboard_mime_types(has_html: bool) -> Vec<String> {
    let mut mime_types = Vec::with_capacity(TEXT_MIME_TYPES.len() + usize::from(has_html));
    if has_html {
        mime_types.push(HTML_MIME_TYPE.to_owned());
    }
    mime_types.extend(
        TEXT_MIME_TYPES
            .iter()
            .map(|mime_type| (*mime_type).to_owned()),
    );
    mime_types
}

fn publish_android_selection(state: &mut CompositorState, handle: &DisplayHandle) {
    let mime_types = android_clipboard_mime_types(state.android_clipboard_has_html);
    let devices = state.data_devices.clone();
    let focused_surface = state.keyboard_focus_surface.clone();
    for device in devices.iter().filter(|device| {
        focused_surface
            .as_ref()
            .is_some_and(|surface| device.id().same_client_as(&surface.id()))
    }) {
        publish_offer_to_device(
            state,
            handle,
            device,
            ClipboardOfferSource::AndroidClipboard,
            mime_types.clone(),
        );
    }
}

fn sync_focused_selection(state: &mut CompositorState, handle: &DisplayHandle) {
    let source = state
        .selection_source
        .clone()
        .filter(|source| source.is_alive());
    let has_selection = state.android_clipboard_offered || source.is_some();
    for device in state.data_devices.iter().filter(|device| {
        device.is_alive() && (!has_selection || !data_device_has_keyboard_focus(state, device))
    }) {
        device.selection(None);
    }
    if state.android_clipboard_offered {
        publish_android_selection(state, handle);
    } else if let Some(source) = source {
        publish_wayland_selection(state, handle, Some(&source));
    }
}

fn clipboard_format_for_mime(mime_type: &str) -> Option<ClipboardFormat> {
    if mime_type == HTML_MIME_TYPE {
        Some(ClipboardFormat::Html)
    } else if TEXT_MIME_TYPES.contains(&mime_type) {
        Some(ClipboardFormat::PlainText)
    } else {
        None
    }
}

fn source_clipboard_mime(source: &WlDataSource) -> Option<(String, ClipboardFormat)> {
    let source_data = source.data::<DataSourceData>()?;
    let mime_types = source_data
        .mime_types
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    if mime_types.iter().any(|offered| offered == HTML_MIME_TYPE) {
        return Some((HTML_MIME_TYPE.to_owned(), ClipboardFormat::Html));
    }
    TEXT_MIME_TYPES
        .iter()
        .find(|candidate| mime_types.iter().any(|offered| offered == **candidate))
        .map(|mime_type| ((*mime_type).to_owned(), ClipboardFormat::PlainText))
}

fn queue_linux_copy(state: &mut CompositorState, source: &WlDataSource) {
    if !state.clipboard_active
        || !source.is_alive()
        || state.pending_linux_copy_fds.len() >= MAX_PENDING_CLIPBOARD_TRANSFERS
    {
        return;
    }
    let Some((mime_type, format)) = source_clipboard_mime(source) else {
        return;
    };
    let Ok((read_end, write_end)) = create_cloexec_pipe() else {
        return;
    };
    source.send(mime_type, write_end.as_fd());
    state.pending_linux_copy_fds.push_back(ClipboardTransfer {
        descriptor: read_end,
        format,
    });
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
        source.action(wl_data_device_manager::DndAction::Copy);
    }
    source.send(mime_type.clone(), write_end.as_fd());
    state.pending_linux_drag_fds.push_back(read_end);
    state.pending_linux_drag_mime_types.push_back(mime_type);
    state.linux_drag_source = Some(source.clone());
    true
}

impl GlobalDispatch<ZwpRelativePointerManagerV1, ()> for CompositorState {
    fn bind(
        _state: &mut Self,
        _handle: &DisplayHandle,
        _client: &Client,
        resource: New<ZwpRelativePointerManagerV1>,
        _global_data: &(),
        data_init: &mut DataInit<'_, Self>,
    ) {
        data_init.init(resource, ());
    }
}

impl Dispatch<ZwpRelativePointerManagerV1, ()> for CompositorState {
    fn request(
        state: &mut Self,
        _client: &Client,
        _resource: &ZwpRelativePointerManagerV1,
        request: zwp_relative_pointer_manager_v1::Request,
        _data: &(),
        _handle: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            zwp_relative_pointer_manager_v1::Request::Destroy => {}
            zwp_relative_pointer_manager_v1::Request::GetRelativePointer { id, pointer } => {
                let relative = data_init.init(id, RelativePointerData { pointer });
                state.relative_pointers.push(relative);
            }
            _ => unreachable!("relative-pointer manager request added without an implementation"),
        }
    }
}

impl Dispatch<ZwpRelativePointerV1, RelativePointerData> for CompositorState {
    fn request(
        _state: &mut Self,
        _client: &Client,
        _resource: &ZwpRelativePointerV1,
        request: zwp_relative_pointer_v1::Request,
        _data: &RelativePointerData,
        _handle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            zwp_relative_pointer_v1::Request::Destroy => {}
            _ => unreachable!("relative-pointer request added without an implementation"),
        }
    }

    fn destroyed(
        state: &mut Self,
        _client: ClientId,
        resource: &ZwpRelativePointerV1,
        _data: &RelativePointerData,
    ) {
        state
            .relative_pointers
            .retain(|relative| relative.id() != resource.id());
    }
}

fn surface_has_pointer_constraint(state: &CompositorState, surface: &WlSurface) -> bool {
    state.locked_pointers.iter().any(|constraint| {
        constraint.is_alive()
            && constraint
                .data::<PointerConstraintData>()
                .is_some_and(|data| data.surface.id() == surface.id())
    }) || state.confined_pointers.iter().any(|constraint| {
        constraint.is_alive()
            && constraint
                .data::<PointerConstraintData>()
                .is_some_and(|data| data.surface.id() == surface.id())
    })
}

fn deactivate_pointer_lock(state: &mut CompositorState) {
    let Some(active) = state.active_locked_pointer.take() else {
        return;
    };
    if let Some(data) = active.data::<PointerConstraintData>() {
        data.active.store(false, Ordering::Release);
        if active.is_alive() {
            active.unlocked();
        }
        if !data.persistent {
            state
                .locked_pointers
                .retain(|constraint| constraint.id() != active.id());
        }
    }
    state.pointer_capture_change_serial =
        state.pointer_capture_change_serial.wrapping_add(1).max(1);
}

fn deactivate_pointer_confine(state: &mut CompositorState) {
    let Some(active) = state.active_confined_pointer.take() else {
        return;
    };
    if let Some(data) = active.data::<PointerConstraintData>() {
        data.active.store(false, Ordering::Release);
        if active.is_alive() {
            active.unconfined();
        }
        if !data.persistent {
            state
                .confined_pointers
                .retain(|constraint| constraint.id() != active.id());
        }
    }
    state.pointer_capture_change_serial =
        state.pointer_capture_change_serial.wrapping_add(1).max(1);
}

fn pointer_constraint_contains(
    state: &CompositorState,
    data: &PointerConstraintData,
    x: f64,
    y: f64,
) -> bool {
    let (local_x, local_y) = pointer_constraint_local_coordinates(state, data, x, y);
    let requested_contains = data
        .region
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .committed
        .as_ref()
        .is_none_or(|region| region.contains(local_x, local_y));
    let input_contains = data.surface.data::<SurfaceData>().is_none_or(|surface| {
        surface
            .inner
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .committed_input_region
            .as_ref()
            .is_none_or(|region| region.contains(local_x, local_y))
    });
    let surface_contains = surface_frame(&data.surface).is_none_or(|frame| {
        local_x >= 0.0
            && local_y >= 0.0
            && local_x < f64::from(frame.width)
            && local_y < f64::from(frame.height)
    });
    requested_contains && input_contains && surface_contains
}

fn pointer_constraint_local_coordinates(
    state: &CompositorState,
    data: &PointerConstraintData,
    x: f64,
    y: f64,
) -> (f64, f64) {
    let (origin_x, origin_y) = surface_origin_in_root(state, &data.surface, 0).unwrap_or((0, 0));
    let mut local_x = x - f64::from(origin_x);
    let mut local_y = y - f64::from(origin_y);
    if state
        .root_surface
        .as_ref()
        .is_some_and(|root| root.id() == data.surface.id())
    {
        if let Some(frame) = surface_frame(&data.surface) {
            let (target_width, target_height) = root_input_dimensions(state);
            local_x = scale_input_coordinate(local_x, target_width, frame.width);
            local_y = scale_input_coordinate(local_y, target_height, frame.height);
        }
    }
    (local_x, local_y)
}

fn bounded_confinement_region(region: Option<&RegionState>) -> bool {
    region.is_none_or(|region| region.operations.len() <= MAX_REGION_OPERATIONS)
}

fn pointer_constraint_supports_confinement(data: &PointerConstraintData) -> bool {
    let requested = data
        .region
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    if !bounded_confinement_region(requested.committed.as_ref()) {
        return false;
    }
    data.surface.data::<SurfaceData>().is_none_or(|surface| {
        let surface = surface
            .inner
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        bounded_confinement_region(surface.committed_input_region.as_ref())
    })
}

fn append_rectangle_boundaries(
    boundaries: &mut [f64; MAX_CONFINEMENT_BOUNDARIES],
    count: &mut usize,
    rectangle: RegionRectangle,
    start_x: f64,
    start_y: f64,
    delta_x: f64,
    delta_y: f64,
) {
    let mut append = |boundary: f64, start: f64, delta: f64| {
        if delta == 0.0 || *count == boundaries.len() {
            return;
        }
        let fraction = (boundary - start) / delta;
        if fraction.is_finite() && fraction > 0.0 && fraction < 1.0 {
            boundaries[*count] = fraction;
            *count += 1;
        }
    };
    append(f64::from(rectangle.x), start_x, delta_x);
    append(rectangle.right() as f64, start_x, delta_x);
    append(f64::from(rectangle.y), start_y, delta_y);
    append(rectangle.bottom() as f64, start_y, delta_y);
}

fn append_region_boundaries(
    boundaries: &mut [f64; MAX_CONFINEMENT_BOUNDARIES],
    count: &mut usize,
    region: Option<&RegionState>,
    start_x: f64,
    start_y: f64,
    delta_x: f64,
    delta_y: f64,
) {
    let Some(region) = region else {
        return;
    };
    for operation in &region.operations {
        let rectangle = match operation {
            RegionOperation::Add(rectangle) | RegionOperation::Subtract(rectangle) => *rectangle,
        };
        append_rectangle_boundaries(
            boundaries, count, rectangle, start_x, start_y, delta_x, delta_y,
        );
    }
}

fn first_outside_partition<F>(
    boundaries: &mut [f64; MAX_CONFINEMENT_BOUNDARIES],
    boundary_count: usize,
    contains_fraction: F,
) -> f64
where
    F: Fn(f64) -> bool,
{
    if !contains_fraction(0.0) {
        return 0.0;
    }
    boundaries[..boundary_count].sort_unstable_by(f64::total_cmp);
    let bisect_boundary = |mut inside: f64, mut outside: f64| {
        for _ in 0..24 {
            let candidate = (inside + outside) * 0.5;
            if contains_fraction(candidate) {
                inside = candidate;
            } else {
                outside = candidate;
            }
        }
        inside
    };

    let mut previous_boundary = 0.0;
    let mut last_inside = 0.0;
    for boundary in boundaries[..boundary_count]
        .iter()
        .copied()
        .chain(std::iter::once(1.0))
    {
        if boundary <= previous_boundary {
            continue;
        }
        let interval = (previous_boundary + boundary) * 0.5;
        if !contains_fraction(interval) {
            return bisect_boundary(last_inside, interval);
        }
        last_inside = interval;
        previous_boundary = boundary;
    }
    if contains_fraction(1.0) {
        1.0
    } else {
        bisect_boundary(last_inside, 1.0)
    }
}

fn first_confinement_boundary(
    state: &CompositorState,
    data: &PointerConstraintData,
    start_x: f64,
    start_y: f64,
    delta_x: f64,
    delta_y: f64,
) -> f64 {
    let (local_start_x, local_start_y) =
        pointer_constraint_local_coordinates(state, data, start_x, start_y);
    let (local_end_x, local_end_y) =
        pointer_constraint_local_coordinates(state, data, start_x + delta_x, start_y + delta_y);
    let local_delta_x = local_end_x - local_start_x;
    let local_delta_y = local_end_y - local_start_y;
    let mut boundaries = [0.0; MAX_CONFINEMENT_BOUNDARIES];
    let mut boundary_count = 0_usize;
    {
        let requested = data
            .region
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        append_region_boundaries(
            &mut boundaries,
            &mut boundary_count,
            requested.committed.as_ref(),
            local_start_x,
            local_start_y,
            local_delta_x,
            local_delta_y,
        );
    }
    if let Some(surface) = data.surface.data::<SurfaceData>() {
        let surface = surface
            .inner
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        append_region_boundaries(
            &mut boundaries,
            &mut boundary_count,
            surface.committed_input_region.as_ref(),
            local_start_x,
            local_start_y,
            local_delta_x,
            local_delta_y,
        );
    }
    if let Some(frame) = surface_frame(&data.surface) {
        if let Some(bounds) = RegionRectangle::new(0, 0, frame.width as i32, frame.height as i32) {
            append_rectangle_boundaries(
                &mut boundaries,
                &mut boundary_count,
                bounds,
                local_start_x,
                local_start_y,
                local_delta_x,
                local_delta_y,
            );
        }
    }
    let contains_fraction = |fraction: f64| {
        pointer_constraint_contains(
            state,
            data,
            start_x + delta_x * fraction,
            start_y + delta_y * fraction,
        )
    };
    first_outside_partition(&mut boundaries, boundary_count, contains_fraction)
}

fn activate_pointer_lock(state: &mut CompositorState, constraint: &ZwpLockedPointerV1) {
    if state.active_locked_pointer.is_some()
        || !state.host_active
        || !state.pointer_inside
        || !constraint.is_alive()
    {
        return;
    }
    let Some(data) = constraint.data::<PointerConstraintData>() else {
        return;
    };
    if !data.eligible.load(Ordering::Acquire) {
        return;
    }
    let focused = state
        .pointer_focus_surface
        .as_ref()
        .is_some_and(|surface| surface.id() == data.surface.id());
    let known_pointer = state
        .pointers
        .iter()
        .any(|pointer| pointer.is_alive() && pointer.id() == data.pointer.id());
    if !focused
        || !known_pointer
        || !pointer_constraint_contains(state, data, state.pointer_x, state.pointer_y)
        || data.active.swap(true, Ordering::AcqRel)
    {
        return;
    }
    constraint.locked();
    state.active_locked_pointer = Some(constraint.clone());
    state.pointer_capture_change_serial =
        state.pointer_capture_change_serial.wrapping_add(1).max(1);
}

fn activate_pointer_lock_for_focus(state: &mut CompositorState) {
    if state.active_locked_pointer.is_some() || state.active_confined_pointer.is_some() {
        return;
    }
    let candidate = state.locked_pointers.iter().find_map(|constraint| {
        let data = constraint.data::<PointerConstraintData>()?;
        state
            .pointer_focus_surface
            .as_ref()
            .is_some_and(|surface| surface.id() == data.surface.id())
            .then(|| constraint.clone())
    });
    if let Some(candidate) = candidate {
        activate_pointer_lock(state, &candidate);
        return;
    }
    let candidate = state.confined_pointers.iter().find_map(|constraint| {
        let data = constraint.data::<PointerConstraintData>()?;
        state
            .pointer_focus_surface
            .as_ref()
            .is_some_and(|surface| surface.id() == data.surface.id())
            .then(|| constraint.clone())
    });
    if let Some(candidate) = candidate {
        activate_pointer_confine(state, &candidate);
    }
}

fn activate_pointer_confine(state: &mut CompositorState, constraint: &ZwpConfinedPointerV1) {
    if state.active_locked_pointer.is_some()
        || state.active_confined_pointer.is_some()
        || !state.host_active
        || !state.pointer_inside
        || !constraint.is_alive()
    {
        return;
    }
    let Some(data) = constraint.data::<PointerConstraintData>() else {
        return;
    };
    let focused = state
        .pointer_focus_surface
        .as_ref()
        .is_some_and(|surface| surface.id() == data.surface.id());
    let known_pointer = state
        .pointers
        .iter()
        .any(|pointer| pointer.is_alive() && pointer.id() == data.pointer.id());
    if !focused
        || !known_pointer
        || !data.eligible.load(Ordering::Acquire)
        || !pointer_constraint_supports_confinement(data)
        || !pointer_constraint_contains(state, data, state.pointer_x, state.pointer_y)
        || data.active.swap(true, Ordering::AcqRel)
    {
        return;
    }
    constraint.confined();
    state.active_confined_pointer = Some(constraint.clone());
    state.pointer_capture_change_serial =
        state.pointer_capture_change_serial.wrapping_add(1).max(1);
}

fn pointer_constraint_data(
    surface: WlSurface,
    pointer: WlPointer,
    lifetime: WEnum<zwp_pointer_constraints_v1::Lifetime>,
    region: Option<RegionState>,
) -> Option<PointerConstraintData> {
    let persistent = match lifetime {
        WEnum::Value(zwp_pointer_constraints_v1::Lifetime::Oneshot) => false,
        WEnum::Value(zwp_pointer_constraints_v1::Lifetime::Persistent) => true,
        WEnum::Unknown(_) => return None,
        _ => return None,
    };
    Some(PointerConstraintData {
        surface,
        pointer,
        persistent,
        region: Mutex::new(PointerConstraintRegionState {
            pending: None,
            cached: None,
            committed: region,
        }),
        eligible: AtomicBool::new(true),
        active: AtomicBool::new(false),
    })
}

fn pointer_constraint_region(region: Option<WlRegion>) -> Option<Option<RegionState>> {
    match region {
        Some(region) => region.data::<RegionData>().map(|data| {
            Some(
                data.inner
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                    .clone(),
            )
        }),
        None => Some(None),
    }
}

fn cache_pointer_constraint_regions(state: &CompositorState, surface: &WlSurface) {
    for constraint in &state.locked_pointers {
        let Some(data) = constraint.data::<PointerConstraintData>() else {
            continue;
        };
        if data.surface.id() != surface.id() {
            continue;
        }
        let mut region = data
            .region
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if let Some(pending) = region.pending.take() {
            region.cached = Some(pending);
        }
    }
    for constraint in &state.confined_pointers {
        let Some(data) = constraint.data::<PointerConstraintData>() else {
            continue;
        };
        if data.surface.id() != surface.id() {
            continue;
        }
        let mut region = data
            .region
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if let Some(pending) = region.pending.take() {
            region.cached = Some(pending);
        }
    }
}

fn apply_pointer_constraint_regions(state: &mut CompositorState, surface: &WlSurface) {
    let mut locked_outside = false;
    for constraint in &state.locked_pointers {
        let Some(data) = constraint.data::<PointerConstraintData>() else {
            continue;
        };
        if data.surface.id() != surface.id() {
            continue;
        }
        {
            let mut region = data
                .region
                .lock()
                .unwrap_or_else(|error| error.into_inner());
            let update = region.cached.take().or_else(|| region.pending.take());
            if let Some(update) = update {
                region.committed = update;
            }
        }
        if data.active.load(Ordering::Acquire)
            && !pointer_constraint_contains(state, data, state.pointer_x, state.pointer_y)
        {
            locked_outside = true;
        }
    }
    let mut confined_outside = false;
    for constraint in &state.confined_pointers {
        let Some(data) = constraint.data::<PointerConstraintData>() else {
            continue;
        };
        if data.surface.id() != surface.id() {
            continue;
        }
        {
            let mut region = data
                .region
                .lock()
                .unwrap_or_else(|error| error.into_inner());
            let update = region.cached.take().or_else(|| region.pending.take());
            if let Some(update) = update {
                region.committed = update;
            }
        }
        if data.active.load(Ordering::Acquire)
            && (!pointer_constraint_supports_confinement(data)
                || !pointer_constraint_contains(state, data, state.pointer_x, state.pointer_y))
        {
            confined_outside = true;
        }
    }
    if locked_outside {
        deactivate_pointer_lock(state);
    }
    if confined_outside {
        deactivate_pointer_confine(state);
    }
    activate_pointer_lock_for_focus(state);
}

impl GlobalDispatch<ZwpPointerConstraintsV1, ()> for CompositorState {
    fn bind(
        _state: &mut Self,
        _handle: &DisplayHandle,
        _client: &Client,
        resource: New<ZwpPointerConstraintsV1>,
        _global_data: &(),
        data_init: &mut DataInit<'_, Self>,
    ) {
        data_init.init(resource, ());
    }
}

impl Dispatch<ZwpPointerConstraintsV1, ()> for CompositorState {
    fn request(
        state: &mut Self,
        _client: &Client,
        resource: &ZwpPointerConstraintsV1,
        request: zwp_pointer_constraints_v1::Request,
        _data: &(),
        _handle: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            zwp_pointer_constraints_v1::Request::Destroy => {}
            zwp_pointer_constraints_v1::Request::LockPointer {
                id,
                surface,
                pointer,
                region,
                lifetime,
            } => {
                if surface_has_pointer_constraint(state, &surface) {
                    resource.post_error(
                        zwp_pointer_constraints_v1::Error::AlreadyConstrained,
                        "surface already has a pointer constraint",
                    );
                    return;
                }
                let Some(region) = pointer_constraint_region(region) else {
                    resource.post_error(0u32, "pointer lock uses an unknown region");
                    return;
                };
                let Some(data) = pointer_constraint_data(surface, pointer, lifetime, region) else {
                    resource.post_error(0u32, "invalid pointer-constraint lifetime");
                    return;
                };
                let constraint = data_init.init(id, data);
                state.locked_pointers.push(constraint.clone());
                activate_pointer_lock(state, &constraint);
            }
            zwp_pointer_constraints_v1::Request::ConfinePointer {
                id,
                surface,
                pointer,
                region,
                lifetime,
            } => {
                if surface_has_pointer_constraint(state, &surface) {
                    resource.post_error(
                        zwp_pointer_constraints_v1::Error::AlreadyConstrained,
                        "surface already has a pointer constraint",
                    );
                    return;
                }
                let Some(region) = pointer_constraint_region(region) else {
                    resource.post_error(0u32, "pointer confinement uses an unknown region");
                    return;
                };
                let Some(data) = pointer_constraint_data(surface, pointer, lifetime, region) else {
                    resource.post_error(0u32, "invalid pointer-constraint lifetime");
                    return;
                };
                let constraint = data_init.init(id, data);
                state.confined_pointers.push(constraint.clone());
                activate_pointer_confine(state, &constraint);
            }
            _ => unreachable!("pointer-constraints request added without an implementation"),
        }
    }
}

impl Dispatch<ZwpLockedPointerV1, PointerConstraintData> for CompositorState {
    fn request(
        _state: &mut Self,
        _client: &Client,
        _resource: &ZwpLockedPointerV1,
        request: zwp_locked_pointer_v1::Request,
        data: &PointerConstraintData,
        _handle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            zwp_locked_pointer_v1::Request::Destroy
            | zwp_locked_pointer_v1::Request::SetCursorPositionHint { .. } => {}
            zwp_locked_pointer_v1::Request::SetRegion { region } => {
                let Some(region) = pointer_constraint_region(region) else {
                    return;
                };
                data.region
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                    .pending = Some(region);
            }
            _ => unreachable!("locked-pointer request added without an implementation"),
        }
    }

    fn destroyed(
        state: &mut Self,
        _client: ClientId,
        resource: &ZwpLockedPointerV1,
        _data: &PointerConstraintData,
    ) {
        let was_active = state
            .active_locked_pointer
            .as_ref()
            .is_some_and(|active| active.id() == resource.id());
        state
            .locked_pointers
            .retain(|constraint| constraint.id() != resource.id());
        if was_active {
            state.active_locked_pointer = None;
            state.pointer_capture_change_serial =
                state.pointer_capture_change_serial.wrapping_add(1).max(1);
        }
    }
}

impl Dispatch<ZwpConfinedPointerV1, PointerConstraintData> for CompositorState {
    fn request(
        _state: &mut Self,
        _client: &Client,
        _resource: &ZwpConfinedPointerV1,
        request: zwp_confined_pointer_v1::Request,
        data: &PointerConstraintData,
        _handle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            zwp_confined_pointer_v1::Request::Destroy => {}
            zwp_confined_pointer_v1::Request::SetRegion { region } => {
                let Some(region) = pointer_constraint_region(region) else {
                    return;
                };
                data.region
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                    .pending = Some(region);
            }
            _ => unreachable!("confined-pointer request added without an implementation"),
        }
    }

    fn destroyed(
        state: &mut Self,
        _client: ClientId,
        resource: &ZwpConfinedPointerV1,
        _data: &PointerConstraintData,
    ) {
        let was_active = state
            .active_confined_pointer
            .as_ref()
            .is_some_and(|active| active.id() == resource.id());
        state
            .confined_pointers
            .retain(|constraint| constraint.id() != resource.id());
        if was_active {
            state.active_confined_pointer = None;
            state.pointer_capture_change_serial =
                state.pointer_capture_change_serial.wrapping_add(1).max(1);
        }
    }
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
                let changed = apply_text_input_commit(&mut text_state, another_enabled);
                if text_state.enabled && !was_enabled {
                    state.ime_active = true;
                    state.ime_show_requests = state.ime_show_requests.saturating_add(1);
                } else if !text_state.enabled && was_enabled {
                    state.ime_active = false;
                    state.ime_hide_requests = state.ime_hide_requests.saturating_add(1);
                }
                if changed {
                    state.ime_change_serial = state.ime_change_serial.wrapping_add(1);
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
            state.ime_change_serial = state.ime_change_serial.wrapping_add(1);
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
                if state.clipboard_active
                    && state.android_clipboard_offered
                    && data_device_has_keyboard_focus(state, &device)
                {
                    publish_offer_to_device(
                        state,
                        handle,
                        &device,
                        ClipboardOfferSource::AndroidClipboard,
                        android_clipboard_mime_types(state.android_clipboard_has_html),
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
                state.android_clipboard_has_html = false;
                state.pending_android_paste_fds.clear();
                state.pending_linux_copy_fds.clear();
                state.pending_linux_clipboard_clear = false;
                state.selection_source = source.clone();
                if let Some(source) = source.as_ref() {
                    state.pending_linux_clipboard_clear = false;
                    queue_linux_copy(state, source);
                } else if state.clipboard_active {
                    state.pending_linux_clipboard_clear = true;
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
                        if state.pending_android_paste_fds.len() < MAX_PENDING_CLIPBOARD_TRANSFERS
                            && let Some(format) = clipboard_format_for_mime(&mime_type)
                        {
                            state
                                .pending_android_paste_fds
                                .push_back(ClipboardTransfer {
                                    descriptor: File::from(fd),
                                    format,
                                });
                        }
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
fn enter_surface_output(state: &mut CompositorState, surface: &WlSurface, output: &WlOutput) {
    if !surface.is_alive() || !output.is_alive() || !output.id().same_client_as(&surface.id()) {
        return;
    }
    let Some(data) = surface.data::<SurfaceData>() else {
        return;
    };
    let protocol_id = output.id().protocol_id();
    let mut surface_state = data.inner.lock().unwrap_or_else(|error| error.into_inner());
    if surface_state.committed_frame.is_none()
        || surface_state.entered_outputs.contains(&protocol_id)
        || surface_state.entered_outputs.len() >= 8
    {
        return;
    }
    surface_state.entered_outputs.push(protocol_id);
    drop(surface_state);
    surface.enter(output);
    if surface.version() >= 6 {
        surface.preferred_buffer_scale(state.output_scale);
        surface.preferred_buffer_transform(wl_output::Transform::Normal);
    }
    state.output_event_count = state.output_event_count.saturating_add(1);
}

fn enter_surface_outputs(state: &mut CompositorState, surface: &WlSurface) {
    for output in state.outputs.clone() {
        enter_surface_output(state, surface, &output);
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
            state.output_mode_width,
            state.output_mode_height,
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
        for surface in state.surfaces.clone() {
            enter_surface_output(state, &surface, &output);
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
        let protocol_id = resource.id().protocol_id();
        for surface in &state.surfaces {
            if let Some(data) = surface.data::<SurfaceData>() {
                data.inner
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                    .entered_outputs
                    .retain(|entered| *entered != protocol_id);
            }
        }
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
                    remember_selection_serial(state, serial, surface.clone());
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

// Android's standard PointerIcon type values. Keeping this translation in the
// compositor lets cursor-shape requests cross the bridge as one integer and
// avoids allocating and rasterizing a bitmap for every standard cursor.
const ANDROID_CURSOR_ARROW: i32 = 1000;
const ANDROID_CURSOR_CONTEXT_MENU: i32 = 1001;
const ANDROID_CURSOR_HAND: i32 = 1002;
const ANDROID_CURSOR_HELP: i32 = 1003;
const ANDROID_CURSOR_WAIT: i32 = 1004;
const ANDROID_CURSOR_CELL: i32 = 1006;
const ANDROID_CURSOR_CROSSHAIR: i32 = 1007;
const ANDROID_CURSOR_TEXT: i32 = 1008;
const ANDROID_CURSOR_VERTICAL_TEXT: i32 = 1009;
const ANDROID_CURSOR_ALIAS: i32 = 1010;
const ANDROID_CURSOR_COPY: i32 = 1011;
const ANDROID_CURSOR_NO_DROP: i32 = 1012;
const ANDROID_CURSOR_ALL_SCROLL: i32 = 1013;
const ANDROID_CURSOR_HORIZONTAL_RESIZE: i32 = 1014;
const ANDROID_CURSOR_VERTICAL_RESIZE: i32 = 1015;
const ANDROID_CURSOR_NESW_RESIZE: i32 = 1016;
const ANDROID_CURSOR_NWSE_RESIZE: i32 = 1017;
const ANDROID_CURSOR_ZOOM_IN: i32 = 1018;
const ANDROID_CURSOR_ZOOM_OUT: i32 = 1019;
const ANDROID_CURSOR_GRAB: i32 = 1020;
const ANDROID_CURSOR_GRABBING: i32 = 1021;
const CUSTOM_CURSOR_ICON: i32 = -1;

fn android_cursor_icon(shape: wp_cursor_shape_device_v1::Shape) -> i32 {
    use wp_cursor_shape_device_v1::Shape;

    match shape {
        Shape::Default => ANDROID_CURSOR_ARROW,
        Shape::ContextMenu | Shape::DndAsk => ANDROID_CURSOR_CONTEXT_MENU,
        Shape::Help => ANDROID_CURSOR_HELP,
        Shape::Pointer => ANDROID_CURSOR_HAND,
        Shape::Progress | Shape::Wait => ANDROID_CURSOR_WAIT,
        Shape::Cell => ANDROID_CURSOR_CELL,
        Shape::Crosshair => ANDROID_CURSOR_CROSSHAIR,
        Shape::Text => ANDROID_CURSOR_TEXT,
        Shape::VerticalText => ANDROID_CURSOR_VERTICAL_TEXT,
        Shape::Alias => ANDROID_CURSOR_ALIAS,
        Shape::Copy => ANDROID_CURSOR_COPY,
        Shape::Move | Shape::AllScroll | Shape::AllResize => ANDROID_CURSOR_ALL_SCROLL,
        Shape::NoDrop | Shape::NotAllowed => ANDROID_CURSOR_NO_DROP,
        Shape::Grab => ANDROID_CURSOR_GRAB,
        Shape::Grabbing => ANDROID_CURSOR_GRABBING,
        Shape::EResize | Shape::WResize | Shape::EwResize | Shape::ColResize => {
            ANDROID_CURSOR_HORIZONTAL_RESIZE
        }
        Shape::NResize | Shape::SResize | Shape::NsResize | Shape::RowResize => {
            ANDROID_CURSOR_VERTICAL_RESIZE
        }
        Shape::NeResize | Shape::SwResize | Shape::NeswResize => ANDROID_CURSOR_NESW_RESIZE,
        Shape::NwResize | Shape::SeResize | Shape::NwseResize => ANDROID_CURSOR_NWSE_RESIZE,
        Shape::ZoomIn => ANDROID_CURSOR_ZOOM_IN,
        Shape::ZoomOut => ANDROID_CURSOR_ZOOM_OUT,
        _ => ANDROID_CURSOR_ARROW,
    }
}

fn cursor_changed(state: &mut CompositorState) {
    state.cursor_change_serial = state.cursor_change_serial.wrapping_add(1).max(1);
}

impl GlobalDispatch<WpCursorShapeManagerV1, ()> for CompositorState {
    fn bind(
        _state: &mut Self,
        _handle: &DisplayHandle,
        _client: &Client,
        resource: New<WpCursorShapeManagerV1>,
        _global_data: &(),
        data_init: &mut DataInit<'_, Self>,
    ) {
        data_init.init(resource, ());
    }
}

impl Dispatch<WpCursorShapeManagerV1, ()> for CompositorState {
    fn request(
        _state: &mut Self,
        _client: &Client,
        _resource: &WpCursorShapeManagerV1,
        request: wp_cursor_shape_manager_v1::Request,
        _data: &(),
        _handle: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wp_cursor_shape_manager_v1::Request::Destroy => {}
            wp_cursor_shape_manager_v1::Request::GetPointer {
                cursor_shape_device,
                pointer,
            } => {
                data_init.init(
                    cursor_shape_device,
                    CursorShapeDeviceData {
                        target: CursorShapeTarget::Pointer(pointer),
                    },
                );
            }
            wp_cursor_shape_manager_v1::Request::GetTabletToolV2 {
                cursor_shape_device,
                ..
            } => {
                data_init.init(
                    cursor_shape_device,
                    CursorShapeDeviceData {
                        target: CursorShapeTarget::Tablet,
                    },
                );
            }
            _ => unreachable!("cursor-shape manager request added without an implementation"),
        }
    }
}

impl Dispatch<WpCursorShapeDeviceV1, CursorShapeDeviceData> for CompositorState {
    fn request(
        state: &mut Self,
        _client: &Client,
        resource: &WpCursorShapeDeviceV1,
        request: wp_cursor_shape_device_v1::Request,
        data: &CursorShapeDeviceData,
        _handle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wp_cursor_shape_device_v1::Request::Destroy => {}
            wp_cursor_shape_device_v1::Request::SetShape { serial, shape } => {
                let WEnum::Value(shape) = shape else {
                    resource.post_error(
                        wp_cursor_shape_device_v1::Error::InvalidShape,
                        "unknown cursor shape",
                    );
                    return;
                };
                let CursorShapeTarget::Pointer(pointer) = &data.target else {
                    return;
                };
                if serial != state.last_pointer_enter_serial
                    || !pointer.is_alive()
                    || state
                        .pointer_focus_surface
                        .as_ref()
                        .is_none_or(|surface| !pointer.id().same_client_as(&surface.id()))
                {
                    return;
                }
                state.cursor_surface = None;
                state.cursor_frame = None;
                state.cursor_hotspot_x = 0;
                state.cursor_hotspot_y = 0;
                state.cursor_system_icon = android_cursor_icon(shape);
                cursor_changed(state);
            }
            _ => unreachable!("cursor-shape device request added without an implementation"),
        }
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
                    state.cursor_system_icon = 0;
                    cursor_changed(state);
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
                state.cursor_system_icon = CUSTOM_CURSOR_ICON;
                cursor_changed(state);
            }
            wl_pointer::Request::Release => {}
            _ => unreachable!("wl_pointer request added without an implementation"),
        }
    }

    fn destroyed(state: &mut Self, _client: ClientId, resource: &WlPointer, _data: &()) {
        if state
            .active_locked_pointer
            .as_ref()
            .and_then(|constraint| constraint.data::<PointerConstraintData>())
            .is_some_and(|data| data.pointer.id() == resource.id())
        {
            deactivate_pointer_lock(state);
        }
        if state
            .active_confined_pointer
            .as_ref()
            .and_then(|constraint| constraint.data::<PointerConstraintData>())
            .is_some_and(|data| data.pointer.id() == resource.id())
        {
            deactivate_pointer_confine(state);
        }
        state.locked_pointers.retain(|constraint| {
            constraint
                .data::<PointerConstraintData>()
                .is_none_or(|data| data.pointer.id() != resource.id())
        });
        state.confined_pointers.retain(|constraint| {
            constraint
                .data::<PointerConstraintData>()
                .is_none_or(|data| data.pointer.id() != resource.id())
        });
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
                            patch: Mutex::new(Vec::new()),
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
    state: &mut CompositorState,
    surface: &WlSurface,
    apply_parent_state: bool,
) {
    let mut traversal = std::mem::take(&mut state.cached_subsurface_traversal);
    traversal.push((surface.clone(), 0));
    apply_cached_subsurface_stack(state, &mut traversal, apply_parent_state);
    traversal.clear();
    state.cached_subsurface_traversal = traversal;
}

fn apply_cached_subsurface_stack(
    state: &mut CompositorState,
    traversal: &mut Vec<(WlSurface, usize)>,
    apply_parent_state: bool,
) {
    while let Some((surface, depth)) = traversal.pop() {
        if depth > state.surface_count as usize || !surface.is_alive() {
            continue;
        }
        let Some(surface_data) = surface.data::<SurfaceData>() else {
            continue;
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
        let mut local_damage = {
            let mut surface_state = surface_data
                .inner
                .lock()
                .unwrap_or_else(|error| error.into_inner());
            if let Some(input_region) = surface_state.cached_input_region.take() {
                surface_state.committed_input_region = input_region;
            }
            if let Some(opaque_region) = surface_state.cached_opaque_region.take() {
                surface_state.committed_opaque_region = opaque_region;
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
            state
                .presentation_callbacks
                .append(&mut surface_state.cached_callbacks);
            let next_depth = depth.saturating_add(1);
            traversal.extend(
                surface_state
                    .children_below
                    .iter()
                    .chain(surface_state.children_above.iter())
                    .rev()
                    .filter(|child| child.is_alive())
                    .cloned()
                    .map(|child| (child, next_depth)),
            );
            std::mem::take(&mut surface_state.cached_damage)
        };
        apply_pointer_constraint_regions(state, &surface);
        if !local_damage.is_empty() {
            let output_width = state.output_width.max(0) as u32;
            let output_height = state.output_height.max(0) as u32;
            if let Some((origin_x, origin_y)) = surface_origin_in_root(state, &surface, 0) {
                for rectangle in local_damage.iter().filter_map(|rectangle| {
                    rectangle
                        .translated(origin_x, origin_y)
                        .clip(output_width, output_height)
                }) {
                    push_accumulated_damage(&mut state.presentation_damage, rectangle);
                }
            }
        }
        local_damage.clear();
        surface_data
            .inner
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .cached_damage = local_damage;
    }
}

fn apply_cached_subsurface_children(state: &mut CompositorState, parent: &WlSurface) {
    let Some(parent_data) = parent.data::<SurfaceData>() else {
        return;
    };
    let mut traversal = std::mem::take(&mut state.cached_subsurface_traversal);
    {
        let mut parent_state = parent_data
            .inner
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        let pending_count = parent_state.pending_subsurface_stack.len();
        for index in 0..pending_count {
            let (surface, sibling, above) = parent_state.pending_subsurface_stack[index].clone();
            apply_subsurface_order(&mut parent_state, &surface, &sibling, parent, above);
        }
        parent_state.pending_subsurface_stack.clear();
        traversal.extend(
            parent_state
                .children_below
                .iter()
                .chain(parent_state.children_above.iter())
                .rev()
                .filter(|child| child.is_alive())
                .cloned()
                .map(|child| (child, 0)),
        );
    }
    apply_cached_subsurface_stack(state, &mut traversal, true);
    traversal.clear();
    state.cached_subsurface_traversal = traversal;
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
                    apply_cached_subsurface_tree(state, &data.surface, false);
                    update_composited_frame(state);
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
                    let mut surface = data.inner.lock().unwrap_or_else(|error| error.into_inner());
                    let SurfaceState {
                        pending_surface_damage,
                        pending_surface_damage_full,
                        ..
                    } = &mut *surface;
                    push_bounded_damage(
                        pending_surface_damage,
                        pending_surface_damage_full,
                        damage,
                    );
                }
            }
            wl_surface::Request::DamageBuffer {
                x,
                y,
                width,
                height,
            } => {
                if let Some(damage) = RegionRectangle::new(x, y, width, height) {
                    let mut surface = data.inner.lock().unwrap_or_else(|error| error.into_inner());
                    let SurfaceState {
                        pending_buffer_damage,
                        pending_buffer_damage_full,
                        ..
                    } = &mut *surface;
                    push_bounded_damage(pending_buffer_damage, pending_buffer_damage_full, damage);
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
            wl_surface::Request::SetOpaqueRegion { region } => {
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
                    .pending_opaque_region = Some(region);
            }
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
                            let parented = toplevel.data::<XdgToplevelData>().is_some_and(|data| {
                                data.parent
                                    .lock()
                                    .unwrap_or_else(|error| error.into_inner())
                                    .is_some()
                            });
                            let maximize_primary = state.tile_toplevels
                                && state.primary_toplevel.is_none()
                                && !parented;
                            let states =
                                requested_toplevel_states(toplevel, maximize_primary, false);
                            let fills_output = states.iter().any(|candidate| {
                                matches!(
                                    candidate,
                                    xdg_toplevel::State::Fullscreen
                                        | xdg_toplevel::State::Maximized
                                )
                            });
                            let width = if fills_output { state.output_width } else { 0 };
                            let height = if fills_output { state.output_height } else { 0 };
                            let (width, height) =
                                constrain_toplevel_configure(toplevel, width, height);
                            xdg_state.pending_configures.push_back(XdgConfigure {
                                serial,
                                popup_geometry: None,
                                toplevel_size: Some((width, height)),
                                restores_windowed: false,
                            });
                            toplevel.configure(width, height, encode_xdg_toplevel_states(&states));
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
                                toplevel_size: None,
                                restores_windowed: false,
                            });
                            popup.configure(x, y, width, height);
                            xdg_surface.configure(serial);
                        }
                    }
                }
                let input_region_update = surface.pending_input_region.take();
                let opaque_region_update = surface.pending_opaque_region.take();
                let pending_offset = std::mem::take(&mut surface.pending_offset);
                let (surface_damage, buffer_damage, damage_overflow) =
                    take_pending_damage(&mut surface);
                let damage_declared =
                    damage_overflow || !surface_damage.is_empty() || !buffer_damage.is_empty();
                let buffer_scale_update = surface.pending_buffer_scale.take();
                let buffer_transform_update = surface.pending_buffer_transform.take();
                let viewport_source_update = surface.pending_viewport_source.take();
                let viewport_destination_update = surface.pending_viewport_destination.take();
                let buffer_assignment = surface.pending_buffer.take();
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
                let snapshot_allows_in_place = surface_snapshot_allows_in_place(
                    &surface,
                    synchronized,
                    buffer_scale_update.is_some()
                        || buffer_transform_update.is_some()
                        || viewport_source_update.is_some()
                        || viewport_destination_update.is_some(),
                );
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
                let viewport_active =
                    next_viewport_source.is_some() || next_viewport_destination.is_some();
                let frame_update = if let Some(assignment) = buffer_assignment {
                    Some(match assignment {
                        Some(buffer) => match buffer.inner.snapshot(
                            base_frame.as_ref(),
                            ShmSnapshotState {
                                surface_damage: &surface_damage,
                                buffer_damage: &buffer_damage,
                                transform: next_transform,
                                scale: next_scale,
                                viewport_active,
                                allow_in_place: snapshot_allows_in_place,
                                force_full_damage: damage_overflow,
                            },
                        ) {
                            Ok(frame) => {
                                if buffer.resource.is_alive() {
                                    buffer.resource.release();
                                }
                                Some(frame)
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
                let force_full_damage = buffer_scale_update.is_some()
                    || buffer_transform_update.is_some()
                    || viewport_state_changed
                    || (viewport_active && !buffer_damage.is_empty())
                    || (frame_update.is_some() && !damage_declared)
                    || damage_overflow;
                let mut local_damage = std::mem::take(&mut surface.commit_damage_scratch);
                damage_for_commit_into(
                    &mut local_damage,
                    &surface_damage,
                    &buffer_damage,
                    next_frame.as_ref(),
                    next_transform,
                    next_scale,
                    force_full_damage,
                );
                restore_pending_damage_buffers(&mut surface, surface_damage, buffer_damage);

                if synchronized {
                    if let Some(input_region) = input_region_update {
                        surface.cached_input_region = Some(input_region);
                    }
                    if let Some(opaque_region) = opaque_region_update {
                        surface.cached_opaque_region = Some(opaque_region);
                    }
                    surface.cached_buffer_scale = Some(next_scale);
                    surface.cached_buffer_transform = Some(next_transform);
                    surface.cached_viewport_source = Some(next_viewport_source);
                    surface.cached_viewport_destination = Some(next_viewport_destination);
                    if buffer_state_changed {
                        surface.cached_frame = Some(next_frame);
                    }
                    for damage in local_damage.iter().copied() {
                        push_accumulated_damage(&mut surface.cached_damage, damage);
                    }
                    restore_commit_damage_scratch(&mut surface, local_damage);
                    surface.cached_callbacks.append(&mut callbacks);
                    cache_pointer_constraint_regions(state, resource);
                    state.surface_commit_count = state.surface_commit_count.saturating_add(1);
                    return;
                }

                if let Some(input_region) = input_region_update {
                    surface.committed_input_region = input_region;
                }
                if let Some(opaque_region) = opaque_region_update {
                    surface.committed_opaque_region = opaque_region;
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
                let previously_active_toplevel =
                    if is_xdg_toplevel && !was_mapped && latest_frame.is_some() {
                        state.active_toplevel.clone()
                    } else {
                        None
                    };
                let is_cursor = role == Some(SurfaceRole::Cursor);
                let publishes_root_frame = surface_publishes_root_frame(
                    role,
                    surface.has_xdg_surface,
                    state.primary_toplevel.is_some(),
                );
                let has_frame = latest_frame.is_some();
                drop(surface);
                apply_pointer_constraint_regions(state, resource);

                state.surface_commit_count = state.surface_commit_count.saturating_add(1);
                if has_frame {
                    enter_surface_outputs(state, resource);
                }
                let damage_origin = if publishes_root_frame {
                    Some(root_surface_origin(state))
                } else {
                    surface_origin_in_root(state, resource, 0)
                };
                if let Some((origin_x, origin_y)) = damage_origin {
                    let output_width = state.output_width.max(0) as u32;
                    let output_height = state.output_height.max(0) as u32;
                    for damage in local_damage.iter().filter_map(|damage| {
                        damage
                            .translated(origin_x, origin_y)
                            .clip(output_width, output_height)
                    }) {
                        push_accumulated_damage(&mut state.presentation_damage, damage);
                    }
                }
                restore_commit_damage_scratch(
                    &mut data.inner.lock().unwrap_or_else(|error| error.into_inner()),
                    local_damage,
                );
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
                        let newly_active = xdg_toplevel.as_ref().is_some_and(|current| {
                            state
                                .active_toplevel
                                .as_ref()
                                .is_some_and(|active| active.id() == current.id())
                        });
                        if newly_active {
                            if let Some(previous) =
                                previously_active_toplevel.as_ref().filter(|previous| {
                                    xdg_toplevel
                                        .as_ref()
                                        .is_some_and(|current| previous.id() != current.id())
                                })
                            {
                                configure_toplevel_activation(state, previous, false);
                            }
                        }
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
                                    if newly_active {
                                        &[xdg_toplevel::State::Activated]
                                    } else {
                                        &[]
                                    },
                                );
                            } else if newly_active {
                                configure_toplevel_activation(state, toplevel, true);
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
                        state.pointer_buttons = 0;
                        set_keyboard_focus(state, Some(surface));
                    } else {
                        state.active_toplevel = None;
                        state.root_surface = None;
                        state.root_frame = None;
                        state.pointer_focus_surface = None;
                        state.pointer_inside = false;
                        state.pointer_buttons = 0;
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
                    state.cursor_system_icon = CUSTOM_CURSOR_ICON;
                    cursor_changed(state);
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
                            state.pointer_buttons = 0;
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
                        state.pointer_buttons = 0;
                        set_keyboard_focus(state, None);
                    }
                }
                state.presentation_callbacks.append(&mut callbacks);
                apply_cached_subsurface_children(state, resource);
                if parent_geometry_changed {
                    state.window_change_serial = state.window_change_serial.wrapping_add(1).max(1);
                    reconfigure_reactive_popups(state, xdg_surface.as_ref());
                }
                update_composited_frame(state);
            }
            _ => unreachable!("wl_surface request added without an implementation"),
        }
    }

    fn destroyed(state: &mut Self, _client: ClientId, resource: &WlSurface, _data: &SurfaceData) {
        if state
            .cursor_surface
            .as_ref()
            .is_some_and(|cursor| cursor.id() == resource.id())
        {
            state.cursor_surface = None;
            state.cursor_frame = None;
            state.cursor_hotspot_x = 0;
            state.cursor_hotspot_y = 0;
            state.cursor_system_icon = 0;
            cursor_changed(state);
        }
        if state
            .active_locked_pointer
            .as_ref()
            .and_then(|constraint| constraint.data::<PointerConstraintData>())
            .is_some_and(|data| data.surface.id() == resource.id())
        {
            deactivate_pointer_lock(state);
        }
        if state
            .active_confined_pointer
            .as_ref()
            .and_then(|constraint| constraint.data::<PointerConstraintData>())
            .is_some_and(|data| data.surface.id() == resource.id())
        {
            deactivate_pointer_confine(state);
        }
        state.locked_pointers.retain(|constraint| {
            constraint
                .data::<PointerConstraintData>()
                .is_none_or(|data| data.surface.id() != resource.id())
        });
        state.confined_pointers.retain(|constraint| {
            constraint
                .data::<PointerConstraintData>()
                .is_none_or(|data| data.surface.id() != resource.id())
        });
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
            state.pointer_buttons = 0;
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
        let _ = request;
        unreachable!("wl_callback has no client requests")
    }
}

impl Dispatch<WlRegion, RegionData> for CompositorState {
    fn request(
        _state: &mut Self,
        _client: &Client,
        resource: &WlRegion,
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
            let mut region = data.inner.lock().unwrap_or_else(|error| error.into_inner());
            if region.operations.len() >= MAX_REGION_OPERATIONS {
                resource.post_error(0u32, "region operation limit exceeded");
                return;
            }
            region.operations.push(operation);
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

fn constrain_toplevel_configure(toplevel: &XdgToplevel, width: i32, height: i32) -> (i32, i32) {
    toplevel
        .data::<XdgToplevelData>()
        .map_or((width, height), |data| {
            data.size_constraints
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .constrain_bounded(width, height)
        })
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
    queue_toplevel_configure_with_restoration(state, toplevel, width, height, states, false)
}

fn queue_toplevel_configure_with_restoration(
    state: &mut CompositorState,
    toplevel: &XdgToplevel,
    width: i32,
    height: i32,
    states: &[xdg_toplevel::State],
    restores_windowed: bool,
) -> u32 {
    if (width == 0) != (height == 0) || width < 0 || height < 0 {
        return 0;
    }
    let (width, height) = if width == 0 {
        (0, 0)
    } else {
        constrain_toplevel_configure(toplevel, width, height)
    };
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
            toplevel_size: if width == 0 {
                None
            } else {
                Some((width, height))
            },
            restores_windowed,
        });
    toplevel.configure(width, height, encode_xdg_toplevel_states(states));
    xdg_surface.configure(serial);
    serial
}

fn requested_toplevel_states(
    toplevel: &XdgToplevel,
    maximized_by_layout: bool,
    activated: bool,
) -> Vec<xdg_toplevel::State> {
    let (fullscreen, maximized) = toplevel
        .data::<XdgToplevelData>()
        .map(|data| {
            (
                data.fullscreen_requested.load(Ordering::Acquire),
                data.maximized_requested.load(Ordering::Acquire),
            )
        })
        .unwrap_or((false, false));
    configured_toplevel_states(fullscreen, maximized, maximized_by_layout, activated)
}

fn configured_toplevel_states(
    fullscreen: bool,
    maximized: bool,
    maximized_by_layout: bool,
    activated: bool,
) -> Vec<xdg_toplevel::State> {
    let presentation = if fullscreen {
        Some(xdg_toplevel::State::Fullscreen)
    } else if maximized || maximized_by_layout {
        Some(xdg_toplevel::State::Maximized)
    } else {
        None
    };
    match (presentation, activated) {
        (Some(presentation), true) => {
            vec![presentation, xdg_toplevel::State::Activated]
        }
        (Some(presentation), false) => vec![presentation],
        (None, true) => vec![xdg_toplevel::State::Activated],
        (None, false) => Vec::new(),
    }
}

fn restoring_to_windowed_state(
    states: &[xdg_toplevel::State],
    restoration_requested: bool,
) -> bool {
    restoration_requested
        && !states.iter().any(|candidate| {
            matches!(
                candidate,
                xdg_toplevel::State::Fullscreen | xdg_toplevel::State::Maximized
            )
        })
}

fn remember_windowed_toplevel_size(toplevel: &XdgToplevel) {
    let Some(data) = toplevel.data::<XdgToplevelData>() else {
        return;
    };
    if data.fullscreen_requested.load(Ordering::Acquire)
        || data.maximized_requested.load(Ordering::Acquire)
    {
        return;
    }
    let Some(surface) = toplevel_surface(toplevel) else {
        return;
    };
    let Some(frame) = surface_frame(&surface) else {
        return;
    };
    let size = surface_content_size(&surface, &frame);
    if size.0 == 0 || size.1 == 0 {
        return;
    }
    *data
        .windowed_size
        .lock()
        .unwrap_or_else(|error| error.into_inner()) = Some(size);
}

fn retained_windowed_toplevel_size(toplevel: &XdgToplevel) -> Option<(u32, u32)> {
    toplevel.data::<XdgToplevelData>().and_then(|data| {
        *data
            .windowed_size
            .lock()
            .unwrap_or_else(|error| error.into_inner())
    })
}

fn toplevel_configure_size(
    states: &[xdg_toplevel::State],
    output_width: i32,
    output_height: i32,
    windowed_size: Option<(u32, u32)>,
    content_size: Option<(u32, u32)>,
    restoring_windowed: bool,
) -> (i32, i32) {
    let fills_output = states.iter().any(|candidate| {
        matches!(
            candidate,
            xdg_toplevel::State::Fullscreen | xdg_toplevel::State::Maximized
        )
    });
    if fills_output && output_width > 0 && output_height > 0 {
        (output_width, output_height)
    } else if let Some((content_width, content_height)) = if restoring_windowed {
        windowed_size
    } else {
        content_size
    } {
        (
            i32::try_from(content_width).unwrap_or(i32::MAX),
            i32::try_from(content_height).unwrap_or(i32::MAX),
        )
    } else {
        (0, 0)
    }
}

fn queue_requested_toplevel_configure(
    state: &mut CompositorState,
    toplevel: &XdgToplevel,
    restoring_windowed: bool,
) -> u32 {
    let initial_configure_sent = toplevel
        .data::<XdgToplevelData>()
        .and_then(|data| data.xdg_surface.data::<XdgSurfaceData>())
        .is_some_and(|data| {
            data.state
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .initial_configure_sent()
        });
    if !initial_configure_sent {
        return 0;
    }
    let Some(surface) = toplevel_surface(toplevel) else {
        return 0;
    };
    let primary = state
        .primary_toplevel
        .as_ref()
        .is_some_and(|candidate| candidate.id() == toplevel.id());
    let activated = state
        .active_toplevel
        .as_ref()
        .is_some_and(|candidate| candidate.id() == toplevel.id());
    let states = requested_toplevel_states(toplevel, primary && state.tile_toplevels, activated);
    let retained_windowed_size = retained_windowed_toplevel_size(toplevel);
    let restoring_to_windowed = restoring_to_windowed_state(
        &states,
        restoring_windowed || retained_windowed_size.is_some(),
    );
    let content_size = surface_frame(&surface).map(|frame| surface_content_size(&surface, &frame));
    let (width, height) = toplevel_configure_size(
        &states,
        state.output_width,
        state.output_height,
        retained_windowed_size,
        content_size,
        restoring_to_windowed,
    );
    queue_toplevel_configure_with_restoration(
        state,
        toplevel,
        width,
        height,
        &states,
        restoring_to_windowed,
    )
}

fn configure_toplevel_activation(
    state: &mut CompositorState,
    toplevel: &XdgToplevel,
    activated: bool,
) -> u32 {
    let Some(surface) = toplevel_surface(toplevel) else {
        return 0;
    };
    let primary = state
        .primary_toplevel
        .as_ref()
        .is_some_and(|candidate| candidate.id() == toplevel.id());
    let states = requested_toplevel_states(toplevel, primary && state.tile_toplevels, activated);
    let retained_windowed_size = retained_windowed_toplevel_size(toplevel);
    let restoring_to_windowed =
        restoring_to_windowed_state(&states, retained_windowed_size.is_some());
    let content_size = surface_frame(&surface).map(|frame| surface_content_size(&surface, &frame));
    let (width, height) = toplevel_configure_size(
        &states,
        state.output_width,
        state.output_height,
        retained_windowed_size,
        content_size,
        restoring_to_windowed,
    );
    queue_toplevel_configure_with_restoration(
        state,
        toplevel,
        width,
        height,
        &states,
        restoring_to_windowed,
    )
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

// Geometry inputs stay primitive in this hot path; grouping them would not
// reduce the protocol state that must be validated together.
#[allow(clippy::too_many_arguments)]
fn calculate_toplevel_layout(
    output_width: i32,
    output_height: i32,
    root_width: u32,
    root_height: u32,
    root_frame_width: u32,
    root_frame_height: u32,
    root_geometry: WindowGeometry,
    primary_size: Option<(u32, u32)>,
    primary_resize_pending: bool,
) -> ToplevelLayout {
    // Android resizes the Surface before a Wayland client can acknowledge and
    // commit the matching xdg_toplevel size. During that bounded interval the
    // old buffer is visibly stretched into the new Surface. Apply the same
    // transform to hit testing so a touch cannot land above or below the
    // control currently under the user's finger.
    if primary_resize_pending
        && (root_width != output_width.max(1) as u32 || root_height != output_height.max(1) as u32)
    {
        let output_width = output_width.max(1);
        let output_height = output_height.max(1);
        return ToplevelLayout {
            output_width: output_width as u32,
            output_height: output_height as u32,
            root_x: scale_surface_coordinate(
                root_geometry.x.saturating_neg(),
                output_width,
                root_width,
            ),
            root_y: scale_surface_coordinate(
                root_geometry.y.saturating_neg(),
                output_height,
                root_height,
            ),
            root_width: scale_surface_coordinate(root_frame_width as i32, output_width, root_width)
                .max(1),
            root_height: scale_surface_coordinate(
                root_frame_height as i32,
                output_height,
                root_height,
            )
            .max(1),
            overlay_primary: false,
        };
    }
    let Some((_primary_width, _primary_height)) = primary_size else {
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
    // The Android Surface is the presentation and input coordinate space.
    // A primary client can temporarily retain its old dimensions while the
    // IME is hiding or showing and a modal child is already mapped. Using
    // that stale client size as the output canvas stretches the child and
    // makes visible touch targets disagree with input coordinates.
    let primary_width = u32::try_from(output_width.max(1)).unwrap_or(1);
    let primary_height = u32::try_from(output_height.max(1)).unwrap_or(1);
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
    let primary_resize_pending = state
        .primary_toplevel
        .as_ref()
        .and_then(toplevel_surface)
        .filter(|primary| primary.id() == root_surface.id())
        .and_then(|primary| {
            primary
                .data::<SurfaceData>()
                .and_then(|data| {
                    data.inner
                        .lock()
                        .unwrap_or_else(|error| error.into_inner())
                        .xdg_surface
                        .clone()
                })
                .and_then(|xdg_surface| {
                    xdg_surface.data::<XdgSurfaceData>().map(|data| {
                        data.state
                            .lock()
                            .unwrap_or_else(|error| error.into_inner())
                            .has_pending_toplevel_size(state.output_width, state.output_height)
                    })
                })
        })
        .unwrap_or(false);
    Some(calculate_toplevel_layout(
        state.output_width,
        state.output_height,
        root_width,
        root_height,
        root_frame.width,
        root_frame.height,
        root_geometry,
        primary_size,
        primary_resize_pending,
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

// Recursive hit testing carries one bounded transform/clip context without
// allocating an intermediate node object per surface.
#[allow(clippy::too_many_arguments)]
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
    let (window_geometry_changed, popup_geometry, restores_windowed) = {
        let mut state = xdg_data
            .state
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        (
            state.commit_window_geometry(),
            state
                .acknowledged_configure
                .and_then(|configure| configure.popup_geometry),
            state.commit_windowed_restoration(),
        )
    };
    if restores_windowed {
        if let Some(toplevel) = xdg_data
            .wl_surface
            .data::<SurfaceData>()
            .and_then(|surface_data| {
                surface_data
                    .inner
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                    .xdg_toplevel
                    .clone()
            })
        {
            if let Some(data) = toplevel.data::<XdgToplevelData>() {
                *data
                    .windowed_size
                    .lock()
                    .unwrap_or_else(|error| error.into_inner()) = None;
            }
        }
    }
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
    blend_frame(
        destination,
        source,
        None,
        x,
        y,
        configured_width,
        configured_height,
    );
}

fn blend_frame(
    destination: &mut CommittedFrame,
    source: &CommittedFrame,
    opaque_region: Option<&RegionState>,
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
    let destination_width = destination.width;
    let destination_height = destination.height;
    let destination_format = destination.format;
    let source_pixels = source.pixels();
    let destination_pixels = destination.pixels_mut();
    for target_y in 0..target_height {
        let destination_y = i64::from(y) + i64::from(target_y);
        if destination_y < 0 || destination_y >= i64::from(destination_height) {
            continue;
        }
        let source_y =
            ((u64::from(target_y) * u64::from(source.height)) / u64::from(target_height)) as u32;
        for target_x in 0..target_width {
            let destination_x = i64::from(x) + i64::from(target_x);
            if destination_x < 0 || destination_x >= i64::from(destination_width) {
                continue;
            }
            let source_x =
                ((u64::from(target_x) * u64::from(source.width)) / u64::from(target_width)) as u32;
            let source_index = ((source_y * source.width + source_x) * 4) as usize;
            let destination_index =
                ((destination_y as u32 * destination_width + destination_x as u32) * 4) as usize;
            let source_is_opaque = opaque_region.is_some_and(|region| {
                region.contains(f64::from(target_x) + 0.5, f64::from(target_y) + 0.5)
            });
            let source_alpha = if source.format == wl_shm::Format::Argb8888 && !source_is_opaque {
                u32::from(source_pixels[source_index + 3])
            } else {
                255
            };
            let destination_alpha = if destination_format == wl_shm::Format::Argb8888 {
                u32::from(destination_pixels[destination_index + 3])
            } else {
                255
            };
            for channel in 0..3 {
                destination_pixels[destination_index + channel] = blend_channel(
                    source_pixels[source_index + channel],
                    source_alpha,
                    destination_pixels[destination_index + channel],
                    destination_alpha,
                );
            }
            if destination_format == wl_shm::Format::Argb8888 {
                destination_pixels[destination_index + 3] =
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

// Recursive composition carries one bounded transform/clip context without
// allocating an intermediate node object per surface.
#[allow(clippy::too_many_arguments)]
fn blend_surface_tree(
    state: &CompositorState,
    destination: &mut CommittedFrame,
    surface: &WlSurface,
    x: i32,
    y: i32,
    configured_width: i32,
    configured_height: i32,
    depth: usize,
    prefer_original_buffers: bool,
) {
    if depth > state.surface_count as usize {
        return;
    }
    let Some(surface_data) = surface.data::<SurfaceData>() else {
        return;
    };
    let (frame, children_below, children_above, transform, viewport_source, opaque_region) = {
        let surface = surface_data
            .inner
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        (
            surface.committed_frame.clone(),
            surface.children_below.clone(),
            surface.children_above.clone(),
            surface.committed_buffer_transform,
            surface.committed_viewport_source,
            surface.committed_opaque_region.clone(),
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
            prefer_original_buffers,
        );
    }
    if let Some(frame) = frame {
        let presentation =
            presentation_buffer_frame(&frame, prefer_original_buffers, transform, viewport_source);
        blend_frame(
            destination,
            &presentation,
            opaque_region.as_ref(),
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
            prefer_original_buffers,
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
    prefer_original_buffers: bool,
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
        prefer_original_buffers,
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
    let previous_frame = state.last_frame.take();
    let output_width = if state.tile_toplevels {
        u32::try_from(state.output_mode_width)
            .ok()
            .filter(|width| *width > 0)
            .unwrap_or(layout.output_width)
    } else {
        layout.output_width
    };
    let output_height = if state.tile_toplevels {
        u32::try_from(state.output_mode_height)
            .ok()
            .filter(|height| *height > 0)
            .unwrap_or(layout.output_height)
    } else {
        layout.output_height
    };
    let prefer_original_buffers =
        output_width != layout.output_width || output_height != layout.output_height;
    let scale_x = |value: i32| {
        scale_surface_coordinate(value, output_width as i32, layout.output_width.max(1))
    };
    let scale_y = |value: i32| {
        scale_surface_coordinate(value, output_height as i32, layout.output_height.max(1))
    };
    let pixel_count = output_width
        .checked_mul(output_height)
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
        state
            .popup_base_frame
            .as_ref()
            .filter(|frame| frame.width == output_width && frame.height == output_height)
    } else {
        None
    };
    let reusable_pixels = previous_frame
        .and_then(|frame| Arc::try_unwrap(frame).ok())
        .filter(|frame| {
            frame.width == output_width
                && frame.height == output_height
                && frame.format == root.format
                && frame.pixels().len() == pixel_count
        })
        .map(CommittedFrame::into_pixels);
    let pixels = if let Some(base) = popup_base {
        base.pixels().clone()
    } else if !prefer_original_buffers
        && !state.tile_toplevels
        && root.format != wl_shm::Format::Argb8888
        && pixel_count == root.pixels().len()
    {
        root.pixels().clone()
    } else if let Some(mut pixels) = reusable_pixels {
        pixels.fill(0);
        pixels
    } else {
        vec![0; pixel_count]
    };
    let mut composed = CommittedFrame::new(output_width, output_height, root.format, pixels, None);
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
                    scale_x(geometry.x.saturating_neg()),
                    scale_y(geometry.y.saturating_neg()),
                    scale_x(frame.width as i32),
                    scale_y(frame.height as i32),
                    0,
                    prefer_original_buffers,
                );
                for pixel in composed.pixels_mut().chunks_exact_mut(4) {
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
            scale_x(layout.root_x),
            scale_y(layout.root_y),
            scale_x(layout.root_width),
            scale_y(layout.root_height),
            0,
            prefer_original_buffers,
        );
    } else {
        let presentation = if prefer_original_buffers {
            original_buffer_frame(root)
        } else {
            Arc::clone(root)
        };
        blend_popup_frame(
            &mut composed,
            &presentation,
            scale_x(layout.root_x),
            scale_y(layout.root_y),
            scale_x(layout.root_width),
            scale_y(layout.root_height),
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
            scale_x(surface_x),
            scale_y(surface_y),
            scale_x(surface_width),
            scale_y(surface_height),
            0,
            prefer_original_buffers,
        );
    }
    state.last_frame_width = composed.width;
    state.last_frame_height = composed.height;
    state.last_frame_checksum = composed.pixels().iter().fold(0u32, |checksum, value| {
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

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
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
    let mut composed =
        CommittedFrame::new(width, height, root_frame.format, vec![0; pixel_count], None);
    blend_surface_tree(
        state,
        &mut composed,
        &root_surface,
        geometry.x.saturating_neg(),
        geometry.y.saturating_neg(),
        root_frame.width as i32,
        root_frame.height as i32,
        0,
        false,
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
            false,
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
                    toplevel_size: None,
                    restores_windowed: false,
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
            .create_global::<CompositorState, ZwpRelativePointerManagerV1, _>(1, ());
        display
            .handle()
            .create_global::<CompositorState, ZwpPointerConstraintsV1, _>(1, ());
        display
            .handle()
            .create_global::<CompositorState, WpCursorShapeManagerV1, _>(2, ());
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
            output_mode_width: 320,
            output_mode_height: 160,
            output_scale: 1,
            output_fractional_scale: 120,
            host_active: true,
            ..CompositorState::default()
        };
        Ok(Self {
            display,
            state,
            listener: None,
            socket_path: None,
            socket_identity: None,
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
        let metadata = std::fs::symlink_metadata(path)?;
        if !metadata.file_type().is_socket() {
            return Err(io::Error::other(
                "Wayland listener path is not a Unix socket",
            ));
        }
        self.listener = Some(listener);
        self.socket_path = Some(path.to_owned());
        self.socket_identity = Some(SocketIdentity {
            device: metadata.dev(),
            inode: metadata.ino(),
        });
        Ok(())
    }

    fn close_socket(&mut self) {
        self.listener = None;
        let path = self.socket_path.take();
        let identity = self.socket_identity.take();
        let (Some(path), Some(identity)) = (path, identity) else {
            return;
        };
        let owned = std::fs::symlink_metadata(&path).is_ok_and(|metadata| {
            metadata.file_type().is_socket()
                && metadata.dev() == identity.device
                && metadata.ino() == identity.inode
        });
        if owned {
            let _ = std::fs::remove_file(path);
        }
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

    pub fn adopt_client(&mut self, fd: OwnedFd) -> std::io::Result<()> {
        let stream = UnixStream::from(fd);
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
        if self.state.selection_focus_dirty {
            self.state.selection_focus_dirty = false;
            let handle = self.display.handle();
            sync_focused_selection(&mut self.state, &handle);
        }
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
                let has_pointers = self.pointer_resources_for_surface(&root).next().is_some();
                self.state.pointer_inside = has_pointers;
                if has_pointers {
                    let serial = self.next_input_serial();
                    for pointer in self.pointer_resources_for_surface(&root) {
                        pointer.enter(serial, &root, self.state.pointer_x, self.state.pointer_y);
                        if pointer.version() >= 5 {
                            pointer.frame();
                        }
                    }
                }
            }
            self.state.pointer_focus_surface = Some(root.clone());
            self.state.pointer_buttons = 0;
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
    #[cfg_attr(not(target_os = "android"), allow(dead_code))]
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
        if changed {
            if let Some(previous) = self.state.active_toplevel.clone() {
                configure_toplevel_activation(&mut self.state, &previous, false);
            }
            self.state.active_toplevel = Some(toplevel.clone());
            configure_toplevel_activation(&mut self.state, &toplevel, true);
        }
        self.state.root_surface = Some(surface.clone());
        self.state.root_frame = Some(frame);
        self.state.pointer_focus_surface = Some(surface.clone());
        self.state.pointer_inside = false;
        self.state.pointer_buttons = 0;
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

    pub fn close_all_windows(&self) -> u32 {
        let mut closed = 0_u32;
        for toplevel in &self.state.toplevels {
            if toplevel.is_alive() {
                toplevel.close();
                closed = closed.saturating_add(1);
            }
        }
        closed
    }

    pub fn set_host_active(&mut self, active: bool) -> u32 {
        if self.state.host_active == active {
            return 0;
        }
        if !active {
            deactivate_pointer_lock(&mut self.state);
            deactivate_pointer_confine(&mut self.state);
            let mut changed = self.touch_cancel();
            changed = changed.saturating_add(self.release_pointer_buttons(0));
            changed = changed.saturating_add(self.pointer_leave());
            self.state.host_active = false;
            if self.state.keyboard_focus_surface.is_some() {
                set_keyboard_focus(&mut self.state, None);
                changed = changed.saturating_add(1);
            }
            return changed;
        }
        self.state.host_active = true;
        let surface = self
            .state
            .active_toplevel
            .as_ref()
            .and_then(toplevel_surface)
            .or_else(|| self.state.root_surface.clone());
        if surface.is_none() || self.state.keyboard_focus_surface.is_some() {
            return 0;
        }
        set_keyboard_focus(&mut self.state, surface);
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
        let (width, height) = constrain_toplevel_configure(&toplevel, width, height);
        xdg_data
            .state
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .pending_configures
            .push_back(XdgConfigure {
                serial,
                popup_geometry: None,
                toplevel_size: Some((width, height)),
                restores_windowed: false,
            });
        let primary = self
            .state
            .primary_toplevel
            .as_ref()
            .is_some_and(|candidate| candidate.id() == toplevel.id());
        let states =
            requested_toplevel_states(&toplevel, primary && self.state.tile_toplevels, true);
        toplevel.configure(width, height, encode_xdg_toplevel_states(&states));
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
        self.configure_output_physical(width, height, width, height, scale, fractional_scale)
    }

    pub fn configure_output_physical(
        &mut self,
        logical_width: i32,
        logical_height: i32,
        mode_width: i32,
        mode_height: i32,
        scale: i32,
        fractional_scale: u32,
    ) -> u32 {
        if logical_width <= 0
            || logical_height <= 0
            || mode_width <= 0
            || mode_height <= 0
            || scale <= 0
            || fractional_scale == 0
        {
            return 0;
        }
        self.state.output_width = logical_width;
        self.state.output_height = logical_height;
        self.state.output_mode_width = mode_width;
        self.state.output_mode_height = mode_height;
        self.state.output_scale = scale;
        self.state.output_fractional_scale = fractional_scale;
        let mut updated = 0u32;
        for output in self.state.outputs.iter().filter(|output| output.is_alive()) {
            output.mode(
                wl_output::Mode::Current | wl_output::Mode::Preferred,
                mode_width,
                mode_height,
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
        let managed = self
            .state
            .active_toplevel
            .as_ref()
            .or(self.state.primary_toplevel.as_ref())
            .map(|resource| resource.id());
        let resized_toplevels = self
            .state
            .toplevels
            .iter()
            .filter(|toplevel| {
                let tiled = self.state.tile_toplevels && managed == Some(toplevel.id());
                let requested = toplevel.data::<XdgToplevelData>().is_some_and(|data| {
                    data.fullscreen_requested.load(Ordering::Acquire)
                        || data.maximized_requested.load(Ordering::Acquire)
                });
                tiled || requested
            })
            .cloned()
            .collect::<Vec<_>>();
        for toplevel in resized_toplevels {
            if queue_requested_toplevel_configure(&mut self.state, &toplevel, false) != 0 {
                updated = updated.saturating_add(1);
            }
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
            self.state.pending_linux_clipboard_clear = false;
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
                self.state.android_clipboard_has_html = false;
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

    pub fn offer_android_clipboard(&mut self, has_html: bool) -> u32 {
        if !self.state.clipboard_active {
            return 0;
        }
        if let Some(previous) = self.state.selection_source.take()
            && previous.is_alive()
        {
            previous.cancelled();
        }
        self.state.pending_android_paste_fds.clear();
        self.state.android_clipboard_offered = true;
        self.state.android_clipboard_has_html = has_html;
        self.state.pending_linux_clipboard_clear = false;
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

    pub fn offer_android_clipboard_text(&mut self) -> u32 {
        self.offer_android_clipboard(false)
    }

    pub fn clear_android_clipboard(&mut self) -> u32 {
        let mut changed = false;
        if let Some(previous) = self.state.selection_source.take()
            && previous.is_alive()
        {
            previous.cancelled();
            changed = true;
        }
        changed |= self.state.android_clipboard_offered;
        self.state.android_clipboard_offered = false;
        self.state.android_clipboard_has_html = false;
        self.state.pending_android_paste_fds.clear();
        self.state.pending_linux_clipboard_clear = false;
        for device in self
            .state
            .data_devices
            .iter()
            .filter(|device| device.is_alive())
        {
            device.selection(None);
        }
        u32::from(changed)
    }

    pub fn take_android_paste_fd(&mut self) -> RawFd {
        self.state
            .pending_android_paste_fds
            .pop_front()
            .map_or(-1, |transfer| transfer.descriptor.into_raw_fd())
    }

    pub fn android_paste_format(&self) -> i32 {
        self.state
            .pending_android_paste_fds
            .front()
            .map_or(0, |transfer| transfer.format.code())
    }

    pub fn take_linux_copy_fd(&mut self) -> RawFd {
        self.state
            .pending_linux_copy_fds
            .pop_front()
            .map_or(-1, |transfer| transfer.descriptor.into_raw_fd())
    }

    pub fn linux_copy_format(&self) -> i32 {
        self.state
            .pending_linux_copy_fds
            .front()
            .map_or(0, |transfer| transfer.format.code())
    }

    pub fn take_linux_clipboard_clear(&mut self) -> bool {
        std::mem::take(&mut self.state.pending_linux_clipboard_clear)
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
            offer.source_actions(wl_data_device_manager::DndAction::Copy);
            offer.action(wl_data_device_manager::DndAction::Copy);
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

    pub fn ime_change_serial(&self) -> u32 {
        self.state.ime_change_serial
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
        if pressed_keys.iter().any(|key| matches!(key, 125 | 126)) {
            mask |= 1 << 6;
        }
        mask
    }
    pub fn keyboard_key(&mut self, key: u32, pressed: bool, time: u32) -> u32 {
        self.keyboard_key_with_modifiers(key, pressed, time, self.state.reported_modifiers)
    }

    pub fn keyboard_key_with_modifiers(
        &mut self,
        key: u32,
        pressed: bool,
        time: u32,
        reported_modifiers: u32,
    ) -> u32 {
        if !self.state.host_active {
            return 0;
        }
        let keyboards = self.focused_keyboard_resources();
        if keyboards.is_empty() {
            return 0;
        }
        let duplicate = self.state.pressed_keys.contains(&key) == pressed;
        let previous_modifiers =
            Self::keyboard_modifier_mask(&self.state.pressed_keys) | self.state.reported_modifiers;
        self.state.reported_modifiers = reported_modifiers & 0x4d;
        if duplicate {
            let modifiers = Self::keyboard_modifier_mask(&self.state.pressed_keys)
                | self.state.reported_modifiers;
            if modifiers == previous_modifiers {
                return 0;
            }
            let serial = self.next_input_serial();
            for keyboard in keyboards {
                keyboard.modifiers(serial, modifiers, 0, 0, 0);
            }
            self.state.keyboard_event_count = self.state.keyboard_event_count.saturating_add(1);
            return 1;
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
        for keyboard in &keyboards {
            keyboard.key(serial, time, key, key_state);
        }
        if pressed {
            self.state.pressed_keys.push(key);
        } else {
            self.state
                .pressed_keys
                .retain(|pressed_key| *pressed_key != key);
        }
        let modifiers =
            Self::keyboard_modifier_mask(&self.state.pressed_keys) | self.state.reported_modifiers;
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

    pub fn keyboard_repeat(&mut self, key: u32, time: u32) -> u32 {
        self.keyboard_repeat_with_modifiers(key, time, self.state.reported_modifiers)
    }

    pub fn keyboard_repeat_with_modifiers(
        &mut self,
        key: u32,
        time: u32,
        reported_modifiers: u32,
    ) -> u32 {
        if !self.state.host_active || !self.state.pressed_keys.contains(&key) {
            return 0;
        }
        let keyboards = self.focused_keyboard_resources();
        if keyboards.is_empty() {
            return 0;
        }
        let previous_modifiers =
            Self::keyboard_modifier_mask(&self.state.pressed_keys) | self.state.reported_modifiers;
        self.state.reported_modifiers = reported_modifiers & 0x4d;
        let modifiers =
            Self::keyboard_modifier_mask(&self.state.pressed_keys) | self.state.reported_modifiers;
        let serial = self.next_input_serial();
        for keyboard in &keyboards {
            keyboard.key(serial, time, key, wl_keyboard::KeyState::Pressed);
        }
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

    fn pointer_resources_for_surface(
        &self,
        surface: &WlSurface,
    ) -> impl Iterator<Item = &WlPointer> {
        let surface_id = surface.id();
        self.state
            .pointers
            .iter()
            .filter(move |pointer| pointer.is_alive() && pointer.id().same_client_as(&surface_id))
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
    fn focused_pointer_surface(&self) -> Option<WlSurface> {
        let surface = self.state.pointer_focus_surface.clone()?;
        let has_pointer = self
            .pointer_resources_for_surface(&surface)
            .next()
            .is_some();
        has_pointer.then_some(surface)
    }

    fn next_input_serial(&mut self) -> u32 {
        self.state.next_input_serial = self.state.next_input_serial.wrapping_add(1).max(1);
        self.state.next_input_serial
    }
    fn remember_selection_serial(&mut self, serial: u32, surface: WlSurface) {
        remember_selection_serial(&mut self.state, serial, surface);
    }

    pub fn pointer_motion(&mut self, x: f64, y: f64, time: u32) -> u32 {
        if !self.state.host_active {
            return 0;
        }
        if self.state.active_locked_pointer.is_some()
            || self.state.active_confined_pointer.is_some()
        {
            let delta_x = x - self.state.pointer_x;
            let delta_y = y - self.state.pointer_y;
            self.state.pointer_x = x;
            self.state.pointer_y = y;
            return self.pointer_relative_motion(delta_x, delta_y, delta_x, delta_y, time);
        }
        let target = if self.state.pointer_buttons != 0 {
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
            if self.state.pointer_buttons != 0 || !self.state.pointer_inside {
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
            deactivate_pointer_lock(&mut self.state);
            deactivate_pointer_confine(&mut self.state);
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

        if self
            .pointer_resources_for_surface(&surface)
            .next()
            .is_none()
        {
            return 0;
        }
        if self.state.pointer_inside {
            for pointer in self.pointer_resources_for_surface(&surface) {
                pointer.motion(time, local_x, local_y);
                if pointer.version() >= 5 {
                    pointer.frame();
                }
            }
        } else {
            let serial = self.next_input_serial();
            for pointer in self.pointer_resources_for_surface(&surface) {
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
        activate_pointer_lock_for_focus(&mut self.state);
        1
    }

    pub fn pointer_relative_motion(
        &mut self,
        delta_x: f64,
        delta_y: f64,
        unaccelerated_x: f64,
        unaccelerated_y: f64,
        time_millis: u32,
    ) -> u32 {
        if !self.state.host_active
            || !self.state.pointer_inside
            || !delta_x.is_finite()
            || !delta_y.is_finite()
            || !unaccelerated_x.is_finite()
            || !unaccelerated_y.is_finite()
            || (delta_x == 0.0 && delta_y == 0.0)
        {
            return 0;
        }
        let Some(focus) = self.state.pointer_focus_surface.clone() else {
            return 0;
        };
        let timestamp = u64::from(time_millis).saturating_mul(1_000);
        let timestamp_high = (timestamp >> 32) as u32;
        let timestamp_low = timestamp as u32;
        let mut delivered = 0_u32;
        for relative in &self.state.relative_pointers {
            let Some(data) = relative.data::<RelativePointerData>() else {
                continue;
            };
            if !relative.is_alive()
                || !data.pointer.is_alive()
                || !data.pointer.id().same_client_as(&focus.id())
            {
                continue;
            }
            relative.relative_motion(
                timestamp_high,
                timestamp_low,
                delta_x,
                delta_y,
                unaccelerated_x,
                unaccelerated_y,
            );
            delivered = delivered.saturating_add(1);
        }
        if delivered != 0 {
            self.state.pointer_event_count =
                self.state.pointer_event_count.saturating_add(delivered);
        }
        let Some(constraint) = self.state.active_confined_pointer.clone() else {
            return delivered;
        };
        let Some(data) = constraint.data::<PointerConstraintData>() else {
            return delivered;
        };
        let start_x = self.state.pointer_x;
        let start_y = self.state.pointer_y;
        // Region operations can create holes and disconnected islands. Walk
        // the segment's bounded rectangle-edge partitions and stop at the
        // first outside interval, even when the final point is inside again.
        // The fixed boundary array and bisection perform no heap allocation.
        let fraction =
            first_confinement_boundary(&self.state, data, start_x, start_y, delta_x, delta_y);
        let next_x = start_x + delta_x * fraction;
        let next_y = start_y + delta_y * fraction;
        if next_x == start_x && next_y == start_y {
            return delivered;
        }
        let (local_x, local_y) = self.pointer_local_coordinates(&focus, next_x, next_y);
        for pointer in self.pointer_resources_for_surface(&focus) {
            pointer.motion(time_millis, local_x, local_y);
            if pointer.version() >= 5 {
                pointer.frame();
            }
        }
        self.state.pointer_x = next_x;
        self.state.pointer_y = next_y;
        self.state.pointer_event_count = self.state.pointer_event_count.saturating_add(1);
        delivered.saturating_add(1)
    }

    pub fn pointer_capture_active(&self) -> bool {
        self.state.active_locked_pointer.is_some() || self.state.active_confined_pointer.is_some()
    }

    pub fn pointer_capture_change_serial(&self) -> u32 {
        self.state.pointer_capture_change_serial
    }

    pub fn cancel_pointer_capture(&mut self) -> u32 {
        if let Some(active) = self.state.active_locked_pointer.as_ref() {
            if let Some(data) = active.data::<PointerConstraintData>() {
                // A user/system capture escape makes this constraint ineligible
                // without forgetting the live protocol object. It therefore cannot
                // recapture automatically or permit a conflicting second request.
                data.eligible.store(false, Ordering::Release);
            }
            deactivate_pointer_lock(&mut self.state);
            return 1;
        }
        let Some(active) = self.state.active_confined_pointer.as_ref() else {
            return 0;
        };
        if let Some(data) = active.data::<PointerConstraintData>() {
            // A user/system capture escape makes this constraint ineligible
            // without forgetting the live protocol object. It therefore cannot
            // recapture automatically or permit a conflicting second request.
            data.eligible.store(false, Ordering::Release);
        }
        deactivate_pointer_confine(&mut self.state);
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
        if !self.state.host_active
            || self.state.active_touches.len() >= MAX_ACTIVE_TOUCHES
            || self.state.active_touches.iter().any(|touch| touch.id == id)
        {
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
        if !self.state.host_active {
            return 0;
        }
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

    pub fn cursor_system_icon(&self) -> i32 {
        self.state.cursor_system_icon
    }

    pub fn cursor_change_serial(&self) -> u32 {
        self.state.cursor_change_serial
    }

    pub fn touch_count(&self) -> u32 {
        u32::try_from(self.state.touches.len()).unwrap_or(u32::MAX)
    }

    pub fn touch_event_count(&self) -> u32 {
        self.state.touch_event_count
    }
    pub fn pointer_button(&mut self, pressed: bool, time: u32) -> u32 {
        self.pointer_button_code(272, pressed, time)
    }

    pub fn pointer_button_code(&mut self, button: u32, pressed: bool, time: u32) -> u32 {
        let Some(bit) = pointer_button_bit(button) else {
            return 0;
        };
        let was_pressed = self.state.pointer_buttons & bit != 0;
        if !self.state.host_active || !self.state.pointer_inside || was_pressed == pressed {
            return 0;
        }
        let Some(surface) = self.focused_pointer_surface() else {
            return 0;
        };
        let serial = self.next_input_serial();
        self.remember_selection_serial(serial, surface.clone());
        if pressed {
            let keyboard_focus = pointer_keyboard_focus_surface(&self.state, &surface);
            if let Some(keyboard_focus) = keyboard_focus {
                if let Some(toplevel) = keyboard_focus_toplevel(&self.state, &keyboard_focus) {
                    let changes_active = self
                        .state
                        .active_toplevel
                        .as_ref()
                        .is_none_or(|active| active.id() != toplevel.id());
                    if changes_active {
                        self.activate_window(toplevel.id().protocol_id());
                        self.state.pointer_focus_surface = Some(surface.clone());
                        self.state.pointer_inside = true;
                    }
                }
                set_keyboard_focus(&mut self.state, Some(keyboard_focus));
            }
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
        }
        let button_state = if pressed {
            wl_pointer::ButtonState::Pressed
        } else {
            wl_pointer::ButtonState::Released
        };
        for pointer in self.pointer_resources_for_surface(&surface) {
            pointer.button(serial, time, button, button_state);
            if pointer.version() >= 5 {
                pointer.frame();
            }
        }
        if pressed {
            self.state.pointer_buttons |= bit;
        } else {
            self.state.pointer_buttons &= !bit;
        }
        self.state.pointer_event_count = self.state.pointer_event_count.saturating_add(1);
        1
    }

    fn release_pointer_buttons(&mut self, time: u32) -> u32 {
        let pressed = self.state.pointer_buttons;
        let mut changed = 0_u32;
        for button in [272, 273, 274, 275, 276] {
            if pointer_button_bit(button).is_some_and(|bit| pressed & bit != 0) {
                changed = changed.saturating_add(self.pointer_button_code(button, false, time));
            }
        }
        // Losing the Android host focus is authoritative even if a Wayland
        // pointer resource disappeared before its release could be delivered.
        self.state.pointer_buttons = 0;
        changed
    }

    pub fn pointer_axis(&mut self, horizontal: f64, vertical: f64, time: u32) -> u32 {
        if !self.state.host_active
            || !self.state.pointer_inside
            || (horizontal == 0.0 && vertical == 0.0)
        {
            return 0;
        }
        let Some(surface) = self.focused_pointer_surface() else {
            return 0;
        };
        for pointer in self.pointer_resources_for_surface(&surface) {
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
        let mut presented = 0u32;
        for callback in self.state.presentation_callbacks.drain(..) {
            if callback.is_alive() {
                callback.done(time);
                presented = presented.saturating_add(1);
            }
        }
        presented
    }

    pub fn pointer_leave(&mut self) -> u32 {
        if !self.state.pointer_inside || self.state.pointer_buttons != 0 {
            return 0;
        }
        deactivate_pointer_lock(&mut self.state);
        deactivate_pointer_confine(&mut self.state);
        let Some(surface) = self.focused_pointer_surface() else {
            return 0;
        };
        let serial = self.next_input_serial();
        for pointer in self.pointer_resources_for_surface(&surface) {
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
    let sent = syscall_ffi::send_with_fd(socket_fd, bytes, transferred_fd)?;
    if sent != bytes.len() {
        return Err(io::Error::new(
            io::ErrorKind::WriteZero,
            "partial Wayland sendmsg",
        ));
    }
    Ok(())
}

fn receive_probe_keymap(socket_fd: RawFd, keyboard_id: u32) -> io::Result<usize> {
    let mut bytes = [0u8; 16];
    let received = syscall_ffi::receive_with_optional_fd(socket_fd, &mut bytes, libc::MSG_WAITALL)?;
    if received.length != bytes.len() {
        return Err(io::Error::new(
            io::ErrorKind::UnexpectedEof,
            "incomplete wl_keyboard.keymap event",
        ));
    }
    if received.flags & libc::MSG_TRUNC != 0 {
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

    let file = received.descriptor.ok_or_else(|| {
        io::Error::new(
            io::ErrorKind::InvalidData,
            "wl_keyboard.keymap event did not include an FD",
        )
    })?;
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
    let received =
        syscall_ffi::receive_with_optional_fd(socket_fd, &mut header_bytes, libc::MSG_WAITALL)?;
    if received.length != header_bytes.len() {
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

    let mut destination = received.descriptor.ok_or_else(|| {
        io::Error::new(
            io::ErrorKind::InvalidData,
            "wl_data_source.send did not include an FD",
        )
    })?;

    let mut body = vec![0u8; size - 8];
    let mut offset = 0usize;
    while offset < body.len() {
        let count = syscall_ffi::receive(socket_fd, &mut body[offset..])?;
        if count == 0 {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "incomplete wl_data_source.send body",
            ));
        }
        offset += count;
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
        let received = syscall_ffi::receive_with_optional_fd(socket_fd, &mut bytes, 0)?;
        if received.length == 0 {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "drag source event stream closed",
            ));
        }
        if let Some(received_descriptor) = received.descriptor {
            if destination.is_some() {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "multiple drag source FDs",
                ));
            }
            destination = Some(received_descriptor);
        }
        pending.extend_from_slice(&bytes[..received.length]);
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
    let file = syscall_ffi::memfd(c"archphene-probe", libc::MFD_CLOEXEC)?;
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
        self.close_socket();
    }
}

/// Raw JNI entry points.
///
/// All exported Java symbols are enclosed here, including their conversion of
/// Java-owned handles, arrays, direct buffers, surfaces, and bitmaps. Existing
/// null/length/range checks stay next to that conversion before control reaches
/// the compositor core. Descriptor, Android graphics, and runtime-process
/// operations use their reviewed boundary modules.
#[allow(clippy::missing_safety_doc)]
#[allow(unsafe_code)]
#[rustfmt::skip]
mod jni_exports {
    use super::*;

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
    let row_bytes = match usize::try_from(frame.width)
        .ok()
        .and_then(|width| width.checked_mul(4))
    {
        Some(value) => value,
        None => return -3,
    };
    // SAFETY: these arguments come directly from the active JNI invocation;
    // the boundary wrapper validates nullness, format, dimensions, stride, and
    // byte count before exposing the locked pixels.
    let result = unsafe {
        android_graphics_ffi::with_rgba_bitmap(
            environment,
            bitmap,
            frame.width,
            frame.height,
            |bitmap| {
                let frame_pixels = frame.pixels();
                for (source, destination) in frame_pixels
                    .chunks_exact(row_bytes)
                    .zip(bitmap.pixels.chunks_exact_mut(bitmap.stride_bytes))
                {
                    if copy_wayland_pixels_to_android(
                        source,
                        frame.format,
                        &mut destination[..row_bytes],
                    )
                    .is_err()
                    {
                        return -5;
                    }
                }
                0
            },
        )
    };
    match result {
        Ok((copy_result, _)) if copy_result != 0 => copy_result,
        Ok((_, 0)) => 0,
        Ok(_) => -6,
        Err(error) => error,
    }
}

const JNI_HANDLE_INDEX_BITS: u32 = 16;
const JNI_HANDLE_INDEX_MASK: u64 = u16::MAX as u64;
const JNI_HANDLE_GENERATION_MAX: u64 = (i64::MAX as u64) >> JNI_HANDLE_INDEX_BITS;

struct JniHandleSlot<T> {
    generation: u64,
    value: Option<T>,
}

struct JniHandleRegistry<T, const N: usize> {
    slots: [JniHandleSlot<T>; N],
}

impl<T, const N: usize> JniHandleRegistry<T, N> {
    fn new() -> Self {
        Self {
            slots: std::array::from_fn(|_| JniHandleSlot {
                generation: 0,
                value: None,
            }),
        }
    }

    fn insert(&mut self, value: T) -> Option<i64> {
        let index = self
            .slots
            .iter()
            .position(|slot| slot.value.is_none() && slot.generation < JNI_HANDLE_GENERATION_MAX)?;
        let slot = &mut self.slots[index];
        let encoded_index = u16::try_from(index.checked_add(1)?).ok()?;
        slot.generation += 1;
        let raw =
            (slot.generation << JNI_HANDLE_INDEX_BITS) | u64::from(encoded_index);
        let handle = i64::try_from(raw).ok()?;
        slot.value = Some(value);
        Some(handle)
    }

    fn index(&self, handle: i64) -> Option<usize> {
        let raw = u64::try_from(handle).ok()?;
        let generation = raw >> JNI_HANDLE_INDEX_BITS;
        let encoded_index = u16::try_from(raw & JNI_HANDLE_INDEX_MASK).ok()?;
        let index = usize::from(encoded_index.checked_sub(1)?);
        let slot = self.slots.get(index)?;
        (generation != 0 && slot.generation == generation && slot.value.is_some())
            .then_some(index)
    }

    fn take(&mut self, handle: i64) -> Option<T> {
        let index = self.index(handle)?;
        self.slots[index].value.take()
    }
}

struct JniHandleGuard<'a, T, const N: usize> {
    registry: std::sync::MutexGuard<'a, JniHandleRegistry<T, N>>,
    index: usize,
}

impl<T, const N: usize> std::ops::Deref for JniHandleGuard<'_, T, N> {
    type Target = T;

    fn deref(&self) -> &Self::Target {
        self.registry.slots[self.index]
            .value
            .as_ref()
            .expect("validated JNI handle slot")
    }
}

impl<T, const N: usize> std::ops::DerefMut for JniHandleGuard<'_, T, N> {
    fn deref_mut(&mut self) -> &mut Self::Target {
        self.registry.slots[self.index]
            .value
            .as_mut()
            .expect("validated JNI handle slot")
    }
}

const MAX_CORE_COMPOSITORS: usize = 8;

fn core_compositors() -> &'static Mutex<JniHandleRegistry<CompositorCore, MAX_CORE_COMPOSITORS>> {
    static REGISTRY: std::sync::OnceLock<
        Mutex<JniHandleRegistry<CompositorCore, MAX_CORE_COMPOSITORS>>,
    > = std::sync::OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(JniHandleRegistry::new()))
}

fn register_core_compositor(compositor: CompositorCore) -> i64 {
    core_compositors()
        .lock()
        .ok()
        .and_then(|mut registry| registry.insert(compositor))
        .unwrap_or(0)
}

fn core_compositor(
    handle: i64,
) -> Option<JniHandleGuard<'static, CompositorCore, MAX_CORE_COMPOSITORS>> {
    let registry = core_compositors().lock().ok()?;
    let index = registry.index(handle)?;
    Some(JniHandleGuard { registry, index })
}

fn remove_core_compositor(handle: i64) -> Option<CompositorCore> {
    core_compositors().lock().ok()?.take(handle)
}

#[cfg(target_os = "android")]
const MAX_LAUNCHER_COMPOSITORS: usize = 4;

#[cfg(target_os = "android")]
fn launcher_compositors(
) -> &'static Mutex<JniHandleRegistry<LauncherSurfaceCompositor, MAX_LAUNCHER_COMPOSITORS>> {
    static REGISTRY: std::sync::OnceLock<
        Mutex<JniHandleRegistry<LauncherSurfaceCompositor, MAX_LAUNCHER_COMPOSITORS>>,
    > = std::sync::OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(JniHandleRegistry::new()))
}

#[cfg(target_os = "android")]
fn register_launcher_compositor(compositor: LauncherSurfaceCompositor) -> i64 {
    launcher_compositors()
        .lock()
        .ok()
        .and_then(|mut registry| registry.insert(compositor))
        .unwrap_or(0)
}

#[cfg(target_os = "android")]
fn launcher_compositor(
    handle: i64,
) -> Option<JniHandleGuard<'static, LauncherSurfaceCompositor, MAX_LAUNCHER_COMPOSITORS>> {
    let registry = launcher_compositors().lock().ok()?;
    let index = registry.index(handle)?;
    Some(JniHandleGuard { registry, index })
}

#[cfg(target_os = "android")]
fn remove_launcher_compositor(handle: i64) -> Option<LauncherSurfaceCompositor> {
    launcher_compositors().lock().ok()?.take(handle)
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
pub extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeCompoundConfinementProbe(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
) -> jni::sys::jboolean {
    let requested = RegionState {
        operations: vec![
            RegionOperation::Add(RegionRectangle {
                x: 0,
                y: 0,
                width: 30,
                height: 10,
            }),
            RegionOperation::Subtract(RegionRectangle {
                x: 10,
                y: 0,
                width: 10,
                height: 10,
            }),
        ],
    };
    let mut boundaries = [0.0; MAX_CONFINEMENT_BOUNDARIES];
    let mut boundary_count = 0;
    append_region_boundaries(
        &mut boundaries,
        &mut boundary_count,
        Some(&requested),
        5.0,
        5.0,
        20.0,
        0.0,
    );
    let fraction = first_outside_partition(&mut boundaries, boundary_count, |fraction| {
        requested.contains(5.0 + 20.0 * fraction, 5.0)
    });
    jni::sys::jboolean::from((9.99..10.0).contains(&(5.0 + 20.0 * fraction)))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeCreateCore(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
) -> i64 {
    match CompositorCore::new() {
        Ok(core) => register_core_compositor(core),
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
    let Some(mut core) = core_compositor(handle) else {
        return -1;
    };
    if fd < 0 {
        return -2;
    }
    // SAFETY: Java calls this only with the result of
    // ParcelFileDescriptor.detachFd(), which transfers sole ownership to this
    // JNI invocation. `OwnedFd` closes it on every subsequent error path.
    let fd = unsafe { OwnedFd::from_raw_fd(fd) };
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
        core_compositor(handle),
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
        return -1;
    };
    i32::try_from(core.ime_editor_action(action, time as u32)).unwrap_or(i32::MAX)
}

/// Reviewed runtime process boundary for fork/exec, process groups, signals,
/// descriptor inheritance, and child output collection.
///
/// Its public surface accepts validated integers/byte manifests and returns
/// owned Rust values plus errno-style status. All raw libc process operations
/// remain inside this module.
#[cfg(any(target_os = "android", test))]
#[allow(unsafe_code)]
mod runtime_process_ffi {
#[cfg(target_os = "android")]
use super::*;

#[cfg(target_os = "android")]
pub(super) fn run_runtime_fd(executable_fd: i32) -> (i32, Vec<u8>) {
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
#[cfg(any(target_os = "android", test))]
const MAX_RUNTIME_ENVIRONMENT_MANIFEST: usize = 32 * 1024;
#[cfg(any(target_os = "android", test))]
pub(super) const MAX_RUNTIME_ENVIRONMENT_VARIABLES: usize = 96;
#[cfg(any(target_os = "android", test))]
const MAX_RUNTIME_ARGUMENT_MANIFEST: usize = 32 * 1024;
#[cfg(any(target_os = "android", test))]
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
pub(super) fn safe_runtime_program_name(name: &[u8]) -> bool {
    !name.is_empty()
        && name.len() <= 128
        && name.iter().all(|byte| {
            byte.is_ascii_alphanumeric() || matches!(*byte, b'@' | b'.' | b'_' | b'+' | b':' | b'-')
        })
}

#[cfg(any(target_os = "android", test))]
pub(super) fn parse_runtime_library_manifest(manifest: &[u8]) -> Result<Vec<(i32, String)>, i32> {
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
pub(super) fn parse_runtime_environment(
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
pub(super) fn parse_runtime_arguments(manifest: &[u8]) -> Result<Vec<std::ffi::CString>, i32> {
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
pub(super) fn runtime_plugin_alias(name: &str) -> Option<&'static str> {
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

#[cfg(any(target_os = "android", test))]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(super) enum RuntimeExecutionState {
    Starting,
    Running {
        pgid: libc::pid_t,
        target: libc::pid_t,
    },
    Cancelling(libc::pid_t),
    Cancelled,
}

#[cfg(any(target_os = "android", test))]
pub(super) const MAX_RUNTIME_EXECUTIONS: usize = 32;

#[cfg(any(target_os = "android", test))]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct RuntimeExecutionEntry {
    id: i64,
    state: RuntimeExecutionState,
}

#[cfg(any(target_os = "android", test))]
pub(super) struct RuntimeExecutionRegistry {
    entries: [Option<RuntimeExecutionEntry>; MAX_RUNTIME_EXECUTIONS],
}

#[cfg(any(target_os = "android", test))]
impl RuntimeExecutionRegistry {
    pub(super) const fn new() -> Self {
        Self {
            entries: [None; MAX_RUNTIME_EXECUTIONS],
        }
    }

    pub(super) fn begin(&mut self, id: i64) -> Result<(), i32> {
        if let Some(index) = self.index(id) {
            if self.entries[index]
                .is_some_and(|entry| entry.state == RuntimeExecutionState::Cancelled)
            {
                self.entries[index] = None;
                return Err(libc::ECANCELED);
            }
            return Err(libc::EBUSY);
        }
        let Some(slot) = self.entries.iter_mut().find(|entry| entry.is_none()) else {
            return Err(libc::ENOSPC);
        };
        *slot = Some(RuntimeExecutionEntry {
            id,
            state: RuntimeExecutionState::Starting,
        });
        Ok(())
    }

    pub(super) fn register_process_group(
        &mut self,
        id: i64,
        pgid: libc::pid_t,
        target: libc::pid_t,
    ) -> bool {
        let Some(entry) = self.entry_mut(id) else {
            return false;
        };
        match entry.state {
            RuntimeExecutionState::Starting => {
                entry.state = RuntimeExecutionState::Running { pgid, target };
                true
            }
            RuntimeExecutionState::Cancelled => {
                entry.state = RuntimeExecutionState::Cancelling(pgid);
                false
            }
            RuntimeExecutionState::Running { .. } | RuntimeExecutionState::Cancelling(_) => false,
        }
    }

    pub(super) fn cancel(&mut self, id: i64) -> Option<libc::pid_t> {
        if let Some(entry) = self.entry_mut(id) {
            return match entry.state {
                RuntimeExecutionState::Starting => {
                    entry.state = RuntimeExecutionState::Cancelled;
                    None
                }
                RuntimeExecutionState::Running { pgid, .. } => {
                    entry.state = RuntimeExecutionState::Cancelling(pgid);
                    Some(pgid)
                }
                RuntimeExecutionState::Cancelling(_) | RuntimeExecutionState::Cancelled => None,
            };
        }
        if let Some(slot) = self.entries.iter_mut().find(|entry| entry.is_none()) {
            *slot = Some(RuntimeExecutionEntry {
                id,
                state: RuntimeExecutionState::Cancelled,
            });
        }
        None
    }

    pub(super) fn running_target(&self, id: i64) -> Option<(libc::pid_t, libc::pid_t)> {
        match self.entry(id)?.state {
            RuntimeExecutionState::Running { pgid, target } => Some((pgid, target)),
            RuntimeExecutionState::Starting
            | RuntimeExecutionState::Cancelling(_)
            | RuntimeExecutionState::Cancelled => None,
        }
    }

    pub(super) fn state(&self, id: i64) -> Option<RuntimeExecutionState> {
        self.entry(id).map(|entry| entry.state)
    }

    pub(super) fn remove(&mut self, id: i64) {
        if let Some(index) = self.index(id) {
            self.entries[index] = None;
        }
    }

    fn entry(&self, id: i64) -> Option<&RuntimeExecutionEntry> {
        self.entries.iter().flatten().find(|entry| entry.id == id)
    }

    fn entry_mut(&mut self, id: i64) -> Option<&mut RuntimeExecutionEntry> {
        self.entries
            .iter_mut()
            .flatten()
            .find(|entry| entry.id == id)
    }

    fn index(&self, id: i64) -> Option<usize> {
        self.entries
            .iter()
            .position(|entry| entry.is_some_and(|entry| entry.id == id))
    }
}

#[cfg(target_os = "android")]
fn runtime_executions() -> &'static Mutex<RuntimeExecutionRegistry> {
    static EXECUTIONS: std::sync::OnceLock<Mutex<RuntimeExecutionRegistry>> =
        std::sync::OnceLock::new();
    EXECUTIONS.get_or_init(|| Mutex::new(RuntimeExecutionRegistry::new()))
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
        executions.begin(id)?;
        Ok(Self { id })
    }

    fn register_process_group(&self, pgid: libc::pid_t, target: libc::pid_t) -> bool {
        if self.id <= 0 {
            return true;
        }
        let Ok(mut executions) = runtime_executions().lock() else {
            return false;
        };
        executions.register_process_group(self.id, pgid, target)
    }
}

#[cfg(target_os = "android")]
impl Drop for RuntimeExecutionGuard {
    fn drop(&mut self) {
        if self.id <= 0 {
            return;
        }
        if let Ok(mut executions) = runtime_executions().lock() {
            executions.remove(self.id);
        }
    }
}

#[cfg(target_os = "android")]
pub(super) fn cancel_runtime_execution(id: i64) {
    if id <= 0 {
        return;
    }
    let pgid = {
        let Ok(mut executions) = runtime_executions().lock() else {
            return;
        };
        executions.cancel(id).unwrap_or(0)
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
            .map(|executions| executions.state(id) == Some(RuntimeExecutionState::Cancelling(pgid)))
            .unwrap_or(false);
        if should_kill {
            unsafe {
                libc::kill(-pgid, libc::SIGKILL);
            }
        }
    });
}

#[cfg(target_os = "android")]
pub(super) fn signal_runtime_execution(id: i64, signal: i32) -> i32 {
    if id <= 0 || (signal != libc::SIGUSR1 && signal != libc::SIGUSR2) {
        return -libc::EINVAL;
    }
    let pgid = {
        let Ok(executions) = runtime_executions().lock() else {
            return -libc::EIO;
        };
        executions.running_target(id).ok_or(libc::ESRCH)
    };
    let (pgid, target) = match pgid {
        Ok(value) => value,
        Err(error) => return -error,
    };
    // Signal the exact target recorded by the subreaper at fork time, not the
    // whole process group: terminals and complex applications may have child
    // shells and helpers for which SIGUSR1/2 have unrelated meanings.
    if target <= 0 || unsafe { libc::getpgid(target) } != pgid {
        return -libc::ESRCH;
    }
    if unsafe { libc::kill(target, signal) } == 0 {
        0
    } else {
        -io::Error::last_os_error()
            .raw_os_error()
            .unwrap_or(libc::EIO)
    }
}

#[cfg(target_os = "android")]
pub(super) fn forget_runtime_execution(id: i64) {
    if id <= 0 {
        return;
    }
    if let Ok(mut executions) = runtime_executions().lock() {
        executions.remove(id);
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
unsafe fn configure_runtime_target(supervisor: libc::pid_t) -> bool {
    unsafe {
        libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGKILL) == 0 && libc::getppid() == supervisor
    }
}

#[cfg(target_os = "android")]
fn runtime_exit_code(status: i32) -> i32 {
    if libc::WIFEXITED(status) {
        libc::WEXITSTATUS(status)
    } else if libc::WIFSIGNALED(status) {
        128 + libc::WTERMSIG(status)
    } else {
        125
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
pub(super) fn terminate_uid_processes(signal: i32) -> i32 {
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
pub(super) fn run_glibc_fds(
    program_fd: i32,
    loader_fd: i32,
    library_manifest: &[u8],
    link_root: &[u8],
    environment_manifest: &[u8],
    program_name: &[u8],
    argument_manifest: &[u8],
    execution_id: i64,
    descriptor_libraries: bool,
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
        } else if descriptor_libraries {
            PathBuf::from(format!("/proc/self/fd/{fd}"))
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
        let target = if descriptor_libraries {
            link_root.join(name)
        } else {
            link_root.join(format!(".library-{}", index - 2))
        };
        if std::os::unix::fs::symlink(target, &alias).is_err() {
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
    let mut target_pipe = [-1; 2];
    if unsafe { libc::pipe2(target_pipe.as_mut_ptr(), libc::O_CLOEXEC) } != 0 {
        let error = io::Error::last_os_error()
            .raw_os_error()
            .unwrap_or(libc::EIO);
        unsafe {
            libc::close(pipe[0]);
            libc::close(pipe[1]);
        }
        cleanup_runtime_fd_view(&mut inherited, &links);
        return (-error, Vec::new());
    }

    let parent = unsafe { libc::getpid() };
    let child = unsafe { libc::fork() };
    if child == 0 {
        unsafe {
            libc::close(target_pipe[0]);
            if !configure_runtime_child(parent) {
                libc::_exit(125);
            }
            let name = b"loader\0";
            if libc::prctl(libc::PR_SET_NAME, name.as_ptr()) != 0
                || libc::prctl(libc::PR_SET_CHILD_SUBREAPER, 1) != 0
            {
                libc::_exit(125);
            }
            let supervisor = libc::getpid();
            let target = libc::fork();
            if target == 0 {
                libc::close(target_pipe[1]);
                if !configure_runtime_target(supervisor) {
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
            if target < 0 {
                libc::_exit(125);
            }
            let target_bytes = target.to_ne_bytes();
            if libc::write(
                target_pipe[1],
                target_bytes.as_ptr().cast::<libc::c_void>(),
                target_bytes.len(),
            ) != target_bytes.len() as isize
            {
                libc::kill(target, libc::SIGKILL);
                libc::_exit(125);
            }
            libc::close(target_pipe[1]);
            libc::close(pipe[0]);
            let mut target_status = 0;
            loop {
                let mut status = 0;
                let waited = libc::waitpid(-1, &mut status, 0);
                if waited == target {
                    target_status = status;
                } else if waited < 0 {
                    let error = io::Error::last_os_error().raw_os_error();
                    if error == Some(libc::EINTR) {
                        continue;
                    }
                    if error == Some(libc::ECHILD) {
                        break;
                    }
                    libc::_exit(125);
                }
            }
            libc::_exit(runtime_exit_code(target_status));
        }
    }
    let fork_error = io::Error::last_os_error()
        .raw_os_error()
        .unwrap_or(libc::EIO);
    unsafe { libc::close(pipe[1]) };
    unsafe { libc::close(target_pipe[1]) };
    for opened in inherited.drain(..) {
        unsafe { libc::close(opened) };
    }
    if child < 0 {
        unsafe {
            libc::close(pipe[0]);
            libc::close(target_pipe[0]);
        }
        cleanup_runtime_fd_view(&mut inherited, &links);
        return (-fork_error, Vec::new());
    }
    if !establish_runtime_process_group(child) {
        unsafe {
            libc::kill(child, libc::SIGKILL);
            libc::waitpid(child, ptr::null_mut(), 0);
            libc::close(pipe[0]);
            libc::close(target_pipe[0]);
        }
        cleanup_runtime_fd_view(&mut inherited, &links);
        return (-libc::EIO, Vec::new());
    }
    let mut target_bytes = [0u8; std::mem::size_of::<libc::pid_t>()];
    let mut target_read = 0usize;
    while target_read < target_bytes.len() {
        let count = unsafe {
            libc::read(
                target_pipe[0],
                target_bytes[target_read..]
                    .as_mut_ptr()
                    .cast::<libc::c_void>(),
                target_bytes.len() - target_read,
            )
        };
        if count > 0 {
            target_read += count as usize;
            continue;
        }
        if count < 0 && io::Error::last_os_error().raw_os_error() == Some(libc::EINTR) {
            continue;
        }
        break;
    }
    unsafe { libc::close(target_pipe[0]) };
    if target_read != target_bytes.len() {
        unsafe {
            libc::kill(-child, libc::SIGKILL);
            libc::waitpid(child, ptr::null_mut(), 0);
            libc::close(pipe[0]);
        }
        cleanup_runtime_fd_view(&mut inherited, &links);
        return (-libc::ECHILD, Vec::new());
    }
    let target = libc::pid_t::from_ne_bytes(target_bytes);
    if !execution.register_process_group(child, target) {
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

}
#[cfg(any(target_os = "android", test))]
use runtime_process_ffi::*;

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
    descriptor_libraries: i32,
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
        descriptor_libraries != 0,
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
pub unsafe extern "system" fn Java_org_archphene_bridge_RuntimeFdLauncher_nativeSignalGlibc(
    _environment: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
    execution_id: i64,
    signal: i32,
) -> i32 {
    signal_runtime_execution(execution_id, signal)
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

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_app_launcher_NativeLauncherCompositor_nativeCreate(
    environment: *mut std::ffi::c_void,
    _owner: *mut std::ffi::c_void,
    socket_path: jbyteArray,
    width: i32,
    height: i32,
    density_dpi: i32,
    geometry_percent: i32,
) -> i64 {
    if !valid_launcher_surface_size(width, height)
        || !valid_launcher_density(density_dpi)
        || !valid_launcher_geometry_percent(geometry_percent)
    {
        return 0;
    }
    let Some(socket_path) =
        java_byte_array(environment, socket_path).and_then(|path| String::from_utf8(path).ok())
    else {
        return 0;
    };
    if socket_path.is_empty()
        || socket_path.len() > 4096
        || socket_path.as_bytes().contains(&0)
        || !Path::new(&socket_path).is_absolute()
    {
        return 0;
    }
    let Ok(mut core) = CompositorCore::new() else {
        return 0;
    };
    // Each generated Android launcher owns one Linux application viewport.
    // Its primary xdg-toplevel therefore follows the Android Surface instead
    // of retaining an arbitrary desktop default window size.
    core.set_toplevel_tiling(true);
    if core.bind_socket(Path::new(&socket_path)).is_err() {
        return 0;
    }
    configure_launcher_output(&mut core, width, height, density_dpi, geometry_percent);
    let cursor_serial = core.cursor_change_serial();
    register_launcher_compositor(LauncherSurfaceCompositor {
        core,
        window: None,
        surface_width: width,
        surface_height: height,
        buffer_width: 0,
        buffer_height: 0,
        last_presented_commit: u32::MAX,
        last_presentation_signature: None,
        last_reported_ime_serial: None,
        last_reported_pointer_capture_serial: None,
        last_reported_cursor_serial: Some(cursor_serial),
    })
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_app_launcher_NativeLauncherCompositor_nativeAttachSurface(
    environment: *mut std::ffi::c_void,
    _owner: *mut std::ffi::c_void,
    handle: i64,
    surface: *mut std::ffi::c_void,
    width: i32,
    height: i32,
    density_dpi: i32,
    geometry_percent: i32,
) -> i32 {
    let Some(mut compositor) = launcher_compositor(handle) else {
        return -1;
    };
    // SAFETY: Android invokes this JNI method with the current JNIEnv and a
    // live `android.view.Surface`; the boundary acquires its own native-window
    // reference before the Java reference may expire.
    let Some(window) = (unsafe {
        android_graphics_ffi::NativeWindow::from_surface(environment, surface)
    }) else {
        return -3;
    };
    compositor.attach_surface(window, width, height, density_dpi, geometry_percent)
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_app_launcher_NativeLauncherCompositor_nativeDetachSurface(
    _environment: *mut std::ffi::c_void,
    _owner: *mut std::ffi::c_void,
    handle: i64,
) {
    if let Some(mut compositor) = launcher_compositor(handle) {
        compositor.detach_surface();
    }
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_app_launcher_NativeLauncherCompositor_nativeRequestClose(
    _environment: *mut std::ffi::c_void,
    _owner: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(compositor) = launcher_compositor(handle) else {
        return -1;
    };
    i32::try_from(compositor.core.close_all_windows()).unwrap_or(i32::MAX)
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_archphene_app_launcher_NativeLauncherCompositor_nativeCopyPresentationSnapshot(
    environment: JNIEnv,
    _owner: JObject,
    handle: i64,
    output: JByteBuffer,
) -> i32 {
    let Some(compositor) = launcher_compositor(handle) else {
        return -1;
    };
    const COMPONENTS: usize = 35;
    const BYTES: usize = COMPONENTS * std::mem::size_of::<i32>();
    let (Ok(capacity), Ok(address)) = (
        environment.get_direct_buffer_capacity(&output),
        environment.get_direct_buffer_address(&output),
    ) else {
        return -2;
    };
    if capacity < BYTES || address.is_null() {
        return -2;
    }
    let output = unsafe { std::slice::from_raw_parts_mut(address, BYTES) };
    for (component, destination) in output.chunks_exact_mut(4).enumerate() {
        destination.copy_from_slice(
            &launcher_presentation_component(&compositor.core.state, component as i32)
                .to_le_bytes(),
        );
    }
    COMPONENTS as i32
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_app_launcher_NativeLauncherCompositor_nativeSetHostActive(
    _environment: *mut std::ffi::c_void,
    _owner: *mut std::ffi::c_void,
    handle: i64,
    active: jboolean,
) {
    if let Some(mut compositor) = launcher_compositor(handle) {
        compositor.core.set_host_active(active != 0);
    }
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_app_launcher_NativeLauncherCompositor_nativeSetClipboardActive(
    _environment: *mut std::ffi::c_void,
    _owner: *mut std::ffi::c_void,
    handle: i64,
    active: jboolean,
) {
    if let Some(mut compositor) = launcher_compositor(handle) {
        compositor.core.set_clipboard_active(active != 0);
    }
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_app_launcher_NativeLauncherCompositor_nativeOfferAndroidClipboardText(
    _environment: *mut std::ffi::c_void,
    _owner: *mut std::ffi::c_void,
    handle: i64,
    has_html: jboolean,
) -> i32 {
    let Some(mut compositor) = launcher_compositor(handle) else {
        return -1;
    };
    i32::try_from(compositor.core.offer_android_clipboard(has_html != 0)).unwrap_or(i32::MAX)
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_app_launcher_NativeLauncherCompositor_nativeClearAndroidClipboard(
    _environment: *mut std::ffi::c_void,
    _owner: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(mut compositor) = launcher_compositor(handle) else {
        return -1;
    };
    i32::try_from(compositor.core.clear_android_clipboard()).unwrap_or(i32::MAX)
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_app_launcher_NativeLauncherCompositor_nativeTakeAndroidPasteFd(
    _environment: *mut std::ffi::c_void,
    _owner: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(mut compositor) = launcher_compositor(handle) else {
        return -1;
    };
    compositor.core.take_android_paste_fd()
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_app_launcher_NativeLauncherCompositor_nativeAndroidPasteFormat(
    _environment: *mut std::ffi::c_void,
    _owner: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(compositor) = launcher_compositor(handle) else {
        return -1;
    };
    compositor.core.android_paste_format()
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_app_launcher_NativeLauncherCompositor_nativeTakeLinuxCopyFd(
    _environment: *mut std::ffi::c_void,
    _owner: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(mut compositor) = launcher_compositor(handle) else {
        return -1;
    };
    compositor.core.take_linux_copy_fd()
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_app_launcher_NativeLauncherCompositor_nativeLinuxCopyFormat(
    _environment: *mut std::ffi::c_void,
    _owner: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(compositor) = launcher_compositor(handle) else {
        return -1;
    };
    compositor.core.linux_copy_format()
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_app_launcher_NativeLauncherCompositor_nativeTakeLinuxClipboardClear(
    _environment: *mut std::ffi::c_void,
    _owner: *mut std::ffi::c_void,
    handle: i64,
) -> jboolean {
    let Some(mut compositor) = launcher_compositor(handle) else {
        return 0;
    };
    jboolean::from(compositor.core.take_linux_clipboard_clear())
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_app_launcher_NativeLauncherCompositor_nativeImeChangeSerial(
    _environment: *mut std::ffi::c_void,
    _owner: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(compositor) = launcher_compositor(handle) else {
        return -1;
    };
    compositor.core.ime_change_serial() as i32
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_app_launcher_NativeLauncherCompositor_nativeImeActive(
    _environment: *mut std::ffi::c_void,
    _owner: *mut std::ffi::c_void,
    handle: i64,
) -> jboolean {
    let Some(compositor) = launcher_compositor(handle) else {
        return 0;
    };
    jboolean::from(compositor.core.ime_active() != 0)
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_app_launcher_NativeLauncherCompositor_nativePointerCaptureActive(
    _environment: *mut std::ffi::c_void,
    _owner: *mut std::ffi::c_void,
    handle: i64,
) -> jboolean {
    let Some(compositor) = launcher_compositor(handle) else {
        return 0;
    };
    jboolean::from(compositor.core.pointer_capture_active())
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_app_launcher_NativeLauncherCompositor_nativeCursorSystemIcon(
    _environment: *mut std::ffi::c_void,
    _owner: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(compositor) = launcher_compositor(handle) else {
        return -1;
    };
    compositor.core.cursor_system_icon()
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_app_launcher_NativeLauncherCompositor_nativeCursorComponent(
    _environment: *mut std::ffi::c_void,
    _owner: *mut std::ffi::c_void,
    handle: i64,
    component: i32,
) -> i32 {
    let Some(compositor) = launcher_compositor(handle) else {
        return -1;
    };
    match component {
        0 => i32::try_from(compositor.core.cursor_width()).unwrap_or(i32::MAX),
        1 => i32::try_from(compositor.core.cursor_height()).unwrap_or(i32::MAX),
        2 => compositor.core.cursor_hotspot_component(0),
        3 => compositor.core.cursor_hotspot_component(1),
        _ => -2,
    }
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_app_launcher_NativeLauncherCompositor_nativeCopyCursor(
    environment: *mut std::ffi::c_void,
    _owner: *mut std::ffi::c_void,
    handle: i64,
    bitmap: *mut std::ffi::c_void,
) -> i32 {
    let Some(compositor) = launcher_compositor(handle) else {
        return -1;
    };
    compositor
        .core
        .state
        .cursor_frame
        .as_ref()
        .map_or(-1, |frame| copy_frame_to_bitmap(frame, environment, bitmap))
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_app_launcher_NativeLauncherCompositor_nativeImeSurroundingTextLength(
    _environment: *mut std::ffi::c_void,
    _owner: *mut std::ffi::c_void,
    handle: i64,
) -> i32 {
    let Some(compositor) = launcher_compositor(handle) else {
        return -1;
    };
    compositor.core.ime_surrounding_text_length()
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_archphene_app_launcher_NativeLauncherCompositor_nativeCopyImeSurroundingText(
    environment: JNIEnv,
    _owner: JObject,
    handle: i64,
    output: JByteBuffer,
    capacity: i32,
) -> i32 {
    let Some(compositor) = launcher_compositor(handle) else {
        return -1;
    };
    let Some(text) = compositor.core.ime_surrounding_text() else {
        return -2;
    };
    let bytes = text.as_bytes();
    let (Ok(capacity), Ok(actual_capacity), Ok(address)) = (
        usize::try_from(capacity),
        environment.get_direct_buffer_capacity(&output),
        environment.get_direct_buffer_address(&output),
    ) else {
        return -1;
    };
    if capacity > actual_capacity || bytes.len() > capacity || address.is_null() {
        return -2;
    }
    unsafe { std::ptr::copy_nonoverlapping(bytes.as_ptr(), address, bytes.len()) };
    i32::try_from(bytes.len()).unwrap_or(-2)
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_app_launcher_NativeLauncherCompositor_nativeImeStateComponent(
    _environment: *mut std::ffi::c_void,
    _owner: *mut std::ffi::c_void,
    handle: i64,
    component: i32,
) -> i32 {
    let Some(compositor) = launcher_compositor(handle) else {
        return -1;
    };
    match component {
        0 => compositor.core.ime_surrounding_cursor(),
        1 => compositor.core.ime_surrounding_anchor(),
        2 => compositor.core.ime_content_hint(),
        3 => compositor.core.ime_content_purpose(),
        4..=7 => compositor
            .core
            .ime_cursor_rectangle_component((component - 4) as usize),
        _ => -1,
    }
}

#[cfg(target_os = "android")]
fn launcher_ime_text(environment: &JNIEnv, input: &JByteBuffer, length: i32) -> Option<String> {
    let (Ok(length), Ok(capacity), Ok(address)) = (
        usize::try_from(length),
        environment.get_direct_buffer_capacity(input),
        environment.get_direct_buffer_address(input),
    ) else {
        return None;
    };
    if length > 16_384 || length > capacity || address.is_null() {
        return None;
    }
    let bytes = unsafe { std::slice::from_raw_parts(address.cast_const(), length) };
    std::str::from_utf8(bytes).ok().map(str::to_owned)
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_archphene_app_launcher_NativeLauncherCompositor_nativeImeText(
    environment: JNIEnv,
    _owner: JObject,
    handle: i64,
    operation: i32,
    input: JByteBuffer,
    length: i32,
    cursor_begin: i32,
    cursor_end: i32,
) -> i32 {
    let Some(text) = launcher_ime_text(&environment, &input, length) else {
        return -2;
    };
    let Some(mut compositor) = launcher_compositor(handle) else {
        return -1;
    };
    let result = match operation {
        1 => compositor.core.ime_commit_text(text),
        2 if cursor_begin >= 0 && cursor_end >= 0 => {
            compositor
                .core
                .ime_set_preedit(text, cursor_begin, cursor_end)
        }
        _ => return -2,
    };
    i32::try_from(result).unwrap_or(i32::MAX)
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_app_launcher_NativeLauncherCompositor_nativeImeDeleteSurrounding(
    _environment: *mut std::ffi::c_void,
    _owner: *mut std::ffi::c_void,
    handle: i64,
    before_bytes: i32,
    after_bytes: i32,
) -> i32 {
    let (Some(mut compositor), Ok(before_bytes), Ok(after_bytes)) = (
        launcher_compositor(handle),
        u32::try_from(before_bytes),
        u32::try_from(after_bytes),
    ) else {
        return -2;
    };
    i32::try_from(
        compositor
            .core
            .ime_delete_surrounding(before_bytes, after_bytes),
    )
    .unwrap_or(i32::MAX)
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_app_launcher_NativeLauncherCompositor_nativeImeEditorAction(
    _environment: *mut std::ffi::c_void,
    _owner: *mut std::ffi::c_void,
    handle: i64,
    action: i32,
    time_millis: i32,
) -> i32 {
    let (Some(mut compositor), Ok(action)) = (
        launcher_compositor(handle),
        u32::try_from(action),
    ) else {
        return -2;
    };
    i32::try_from(
        compositor
            .core
            .ime_editor_action(action, time_millis as u32),
    )
    .unwrap_or(i32::MAX)
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_archphene_app_launcher_NativeLauncherCompositor_nativeReadClipboardFd(
    environment: JNIEnv,
    _owner: JObject,
    descriptor: i32,
    output: JByteBuffer,
    capacity: i32,
    timeout_millis: i32,
) -> i32 {
    if descriptor < 0 {
        return -1;
    }
    let descriptor = unsafe { File::from_raw_fd(descriptor) };
    let (Ok(capacity), Ok(actual_capacity), Ok(address)) = (
        usize::try_from(capacity),
        environment.get_direct_buffer_capacity(&output),
        environment.get_direct_buffer_address(&output),
    ) else {
        return -1;
    };
    if capacity == 0
        || capacity > MAX_CLIPBOARD_BYTES
        || capacity > actual_capacity
        || address.is_null()
        || !(1..=5_000).contains(&timeout_millis)
    {
        return -1;
    }
    let output = unsafe { std::slice::from_raw_parts_mut(address, capacity) };
    read_clipboard_descriptor(descriptor, output, timeout_millis as u64)
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_archphene_app_launcher_NativeLauncherCompositor_nativeWriteClipboardFd(
    environment: JNIEnv,
    _owner: JObject,
    descriptor: i32,
    input: JByteBuffer,
    length: i32,
    timeout_millis: i32,
) -> i32 {
    if descriptor < 0 {
        return -1;
    }
    let descriptor = unsafe { File::from_raw_fd(descriptor) };
    let (Ok(length), Ok(capacity), Ok(address)) = (
        usize::try_from(length),
        environment.get_direct_buffer_capacity(&input),
        environment.get_direct_buffer_address(&input),
    ) else {
        return -1;
    };
    if length > MAX_CLIPBOARD_BYTES
        || length > capacity
        || address.is_null()
        || !(1..=5_000).contains(&timeout_millis)
    {
        return -1;
    }
    let input = unsafe { std::slice::from_raw_parts(address.cast_const(), length) };
    write_clipboard_descriptor(descriptor, input, timeout_millis as u64)
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_app_launcher_NativeLauncherCompositor_nativeDispatchAndPresent(
    _environment: *mut std::ffi::c_void,
    _owner: *mut std::ffi::c_void,
    handle: i64,
    time_millis: i32,
) -> i32 {
    let Some(mut compositor) = launcher_compositor(handle) else {
        return -1;
    };
    compositor.dispatch_and_present(time_millis as u32)
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_archphene_app_launcher_NativeLauncherCompositor_nativeInputBatch(
    environment: JNIEnv,
    _owner: JObject,
    handle: i64,
    input: JByteBuffer,
    record_count: i32,
) -> i32 {
    const RECORD_BYTES: usize = 24;
    const MAX_RECORDS: usize = 32;
    let (Some(mut compositor), Ok(record_count)) = (
        launcher_compositor(handle),
        usize::try_from(record_count),
    ) else {
        return -1;
    };
    if record_count == 0 || record_count > MAX_RECORDS {
        return -2;
    }
    let Ok(capacity) = environment.get_direct_buffer_capacity(&input) else {
        return -2;
    };
    let Some(length) = record_count.checked_mul(RECORD_BYTES) else {
        return -2;
    };
    if length > capacity {
        return -2;
    }
    let Ok(address) = environment.get_direct_buffer_address(&input) else {
        return -2;
    };
    if address.is_null() {
        return -2;
    }
    let bytes = unsafe { std::slice::from_raw_parts(address.cast_const(), length) };
    let mut handled = 0_i32;
    for record in bytes.chunks_exact(RECORD_BYTES) {
        let field = |index: usize| {
            i32::from_le_bytes(
                record[index * 4..index * 4 + 4]
                    .try_into()
                    .expect("fixed input field"),
            )
        };
        let fields = [field(0), field(1), field(2), field(3), field(4), field(5)];
        let Ok(accepted) = dispatch_launcher_input_record(&mut compositor.core, fields) else {
            return -3;
        };
        handled = handled.saturating_add(i32::from(accepted != 0));
    }
    handled
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_app_launcher_NativeLauncherCompositor_nativeDestroy(
    _environment: *mut std::ffi::c_void,
    _owner: *mut std::ffi::c_void,
    handle: i64,
) {
    drop(remove_launcher_compositor(handle));
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
    register_core_compositor(core)
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
    let Some(mut core) = core_compositor(handle) else {
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
        64 => return i32::try_from(core.set_host_active(a != 0)).unwrap_or(i32::MAX),
        65 => return i32::try_from(core.keyboard_count()).unwrap_or(i32::MAX),
        66 => {
            return i32::try_from(core.focused_keyboard_resources().len()).unwrap_or(i32::MAX);
        }
        67 => return core.cursor_system_icon(),
        68 => return core.cursor_change_serial() as i32,
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
        return -1;
    };
    match command {
        1 => copy_last_frame_to_bitmap(&core, environment, bitmap),
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
    drop(remove_core_compositor(handle));
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(mut core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
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
    let Some(core) = core_compositor(handle) else {
        return -1;
    };
    copy_last_frame_to_bitmap(&core, environment, bitmap)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_archphene_compositorprobe_MainActivity_nativeDestroyCore(
    _environment: *mut std::ffi::c_void,
    _activity: *mut std::ffi::c_void,
    handle: i64,
) {
    drop(remove_core_compositor(handle));
}

#[cfg(test)]
#[allow(unsafe_code)]
mod steady_state_allocations;

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicU8;
    use wayland_client::globals::{GlobalListContents, registry_queue_init};
    use wayland_client::protocol::{
        wl_compositor as client_wl_compositor, wl_pointer as client_wl_pointer,
        wl_registry as client_wl_registry, wl_seat as client_wl_seat,
        wl_region as client_wl_region, wl_surface as client_wl_surface,
    };
    use wayland_client::{Connection, QueueHandle};
    use wayland_protocols::wp::pointer_constraints::zv1::client::zwp_confined_pointer_v1 as client_confined_pointer;
    use wayland_protocols::wp::pointer_constraints::zv1::client::zwp_locked_pointer_v1 as client_locked_pointer;
    use wayland_protocols::wp::pointer_constraints::zv1::client::zwp_pointer_constraints_v1 as client_pointer_constraints;
    use wayland_protocols::wp::relative_pointer::zv1::client::zwp_relative_pointer_manager_v1 as client_relative_pointer_manager;
    use wayland_protocols::wp::relative_pointer::zv1::client::zwp_relative_pointer_v1 as client_relative_pointer;
    use wayland_protocols::wp::cursor_shape::v1::client::wp_cursor_shape_device_v1 as client_cursor_shape_device;
    use wayland_protocols::wp::cursor_shape::v1::client::wp_cursor_shape_manager_v1 as client_cursor_shape_manager;

    #[derive(Default)]
    struct PointerProtocolClient {
        locked: u32,
        unlocked: u32,
        confined: u32,
        unconfined: u32,
        relative_motion: Option<(f64, f64)>,
        pointer_motion: Option<(f64, f64)>,
        pointer_enter_serial: u32,
    }

    impl wayland_client::Dispatch<client_wl_registry::WlRegistry, GlobalListContents>
        for PointerProtocolClient
    {
        fn event(
            _state: &mut Self,
            _proxy: &client_wl_registry::WlRegistry,
            _event: client_wl_registry::Event,
            _data: &GlobalListContents,
            _connection: &Connection,
            _queue: &QueueHandle<Self>,
        ) {
        }
    }

    wayland_client::delegate_noop!(
        PointerProtocolClient: client_wl_compositor::WlCompositor
    );
    wayland_client::delegate_noop!(
        PointerProtocolClient: ignore client_wl_surface::WlSurface
    );
    wayland_client::delegate_noop!(
        PointerProtocolClient: ignore client_wl_region::WlRegion
    );
    wayland_client::delegate_noop!(PointerProtocolClient: ignore client_wl_seat::WlSeat);
    wayland_client::delegate_noop!(
        PointerProtocolClient:
        client_relative_pointer_manager::ZwpRelativePointerManagerV1
    );
    wayland_client::delegate_noop!(
        PointerProtocolClient: client_pointer_constraints::ZwpPointerConstraintsV1
    );
    wayland_client::delegate_noop!(
        PointerProtocolClient: client_cursor_shape_manager::WpCursorShapeManagerV1
    );
    wayland_client::delegate_noop!(
        PointerProtocolClient: ignore client_cursor_shape_device::WpCursorShapeDeviceV1
    );

    impl wayland_client::Dispatch<client_locked_pointer::ZwpLockedPointerV1, ()>
        for PointerProtocolClient
    {
        fn event(
            state: &mut Self,
            _proxy: &client_locked_pointer::ZwpLockedPointerV1,
            event: client_locked_pointer::Event,
            _data: &(),
            _connection: &Connection,
            _queue: &QueueHandle<Self>,
        ) {
            match event {
                client_locked_pointer::Event::Locked => {
                    state.locked = state.locked.saturating_add(1);
                }
                client_locked_pointer::Event::Unlocked => {
                    state.unlocked = state.unlocked.saturating_add(1);
                }
                _ => {}
            }
        }
    }

    impl wayland_client::Dispatch<client_confined_pointer::ZwpConfinedPointerV1, ()>
        for PointerProtocolClient
    {
        fn event(
            state: &mut Self,
            _proxy: &client_confined_pointer::ZwpConfinedPointerV1,
            event: client_confined_pointer::Event,
            _data: &(),
            _connection: &Connection,
            _queue: &QueueHandle<Self>,
        ) {
            match event {
                client_confined_pointer::Event::Confined => {
                    state.confined = state.confined.saturating_add(1);
                }
                client_confined_pointer::Event::Unconfined => {
                    state.unconfined = state.unconfined.saturating_add(1);
                }
                _ => {}
            }
        }
    }

    impl wayland_client::Dispatch<client_wl_pointer::WlPointer, ()> for PointerProtocolClient {
        fn event(
            state: &mut Self,
            _proxy: &client_wl_pointer::WlPointer,
            event: client_wl_pointer::Event,
            _data: &(),
            _connection: &Connection,
            _queue: &QueueHandle<Self>,
        ) {
            match event {
                client_wl_pointer::Event::Enter { serial, .. } => {
                    state.pointer_enter_serial = serial;
                }
                client_wl_pointer::Event::Motion {
                    surface_x,
                    surface_y,
                    ..
                } => {
                    state.pointer_motion = Some((surface_x, surface_y));
                }
                _ => {}
            }
        }
    }

    impl wayland_client::Dispatch<client_relative_pointer::ZwpRelativePointerV1, ()>
        for PointerProtocolClient
    {
        fn event(
            state: &mut Self,
            _proxy: &client_relative_pointer::ZwpRelativePointerV1,
            event: client_relative_pointer::Event,
            _data: &(),
            _connection: &Connection,
            _queue: &QueueHandle<Self>,
        ) {
            if let client_relative_pointer::Event::RelativeMotion { dx, dy, .. } = event {
                state.relative_motion = Some((dx, dy));
            }
        }
    }

    #[test]
    fn standard_cursor_shapes_round_trip_and_publish_each_same_sized_change() {
        let socket = std::env::temp_dir().join(format!(
            "archphene-cursor-shape-{}.sock",
            std::process::id()
        ));
        let _ = std::fs::remove_file(&socket);
        let stage = Arc::new(AtomicU8::new(0));
        let server_stage = Arc::clone(&stage);
        let server_socket = socket.clone();
        let server = std::thread::spawn(move || {
            let mut core = CompositorCore::new().expect("Wayland display");
            core.bind_socket(&server_socket).expect("bind socket");
            while server_stage.load(Ordering::Acquire) != 5 {
                core.dispatch_once().expect("dispatch client");
                match server_stage.load(Ordering::Acquire) {
                    1 if core.state.surfaces.len() == 1 && core.state.pointers.len() == 1 => {
                        core.state.pointer_focus_surface = core.state.surfaces.first().cloned();
                        core.state.last_pointer_enter_serial = 77;
                        server_stage.store(2, Ordering::Release);
                    }
                    3 if core.cursor_change_serial() == 1 => {
                        assert_eq!(core.cursor_system_icon(), ANDROID_CURSOR_TEXT);
                        assert_eq!(core.cursor_width(), 0);
                        assert!(core.state.cursor_surface.is_none());
                        server_stage.store(4, Ordering::Release);
                    }
                    _ => std::thread::yield_now(),
                }
            }
            while core.cursor_change_serial() != 2 {
                core.dispatch_once().expect("dispatch second shape");
            }
            assert_eq!(core.cursor_system_icon(), ANDROID_CURSOR_CROSSHAIR);
            assert_eq!(core.cursor_width(), 0);
        });

        let connection = loop {
            match UnixStream::connect(&socket) {
                Ok(stream) => break Connection::from_socket(stream).expect("client connection"),
                Err(error)
                    if matches!(
                        error.kind(),
                        io::ErrorKind::NotFound | io::ErrorKind::ConnectionRefused
                    ) =>
                {
                    std::thread::yield_now();
                }
                Err(error) => panic!("connect client: {error}"),
            }
        };
        let (globals, mut events) =
            registry_queue_init::<PointerProtocolClient>(&connection).expect("registry");
        let queue = events.handle();
        let compositor = globals
            .bind::<client_wl_compositor::WlCompositor, _, _>(&queue, 1..=6, ())
            .expect("wl_compositor");
        let seat = globals
            .bind::<client_wl_seat::WlSeat, _, _>(&queue, 1..=9, ())
            .expect("wl_seat");
        let cursor_manager = globals
            .bind::<client_cursor_shape_manager::WpCursorShapeManagerV1, _, _>(
                &queue,
                1..=2,
                (),
            )
            .expect("cursor-shape manager");
        let _surface = compositor.create_surface(&queue, ());
        let pointer = seat.get_pointer(&queue, ());
        let cursor = cursor_manager.get_pointer(&pointer, &queue, ());
        connection.flush().expect("flush cursor-shape objects");
        stage.store(1, Ordering::Release);
        while stage.load(Ordering::Acquire) != 2 {
            events
                .roundtrip(&mut PointerProtocolClient::default())
                .expect("cursor-shape object roundtrip");
        }

        cursor.set_shape(77, client_cursor_shape_device::Shape::Text);
        connection.flush().expect("flush text cursor");
        stage.store(3, Ordering::Release);
        while stage.load(Ordering::Acquire) != 4 {
            events
                .roundtrip(&mut PointerProtocolClient::default())
                .expect("text cursor roundtrip");
        }

        cursor.set_shape(77, client_cursor_shape_device::Shape::Crosshair);
        connection.flush().expect("flush crosshair cursor");
        stage.store(5, Ordering::Release);
        server.join().expect("server thread");
        assert!(!socket.exists());
    }

    #[test]
    fn every_standard_cursor_shape_maps_to_an_android_system_icon() {
        use wp_cursor_shape_device_v1::Shape;

        let shapes = [
            Shape::Default,
            Shape::ContextMenu,
            Shape::Help,
            Shape::Pointer,
            Shape::Progress,
            Shape::Wait,
            Shape::Cell,
            Shape::Crosshair,
            Shape::Text,
            Shape::VerticalText,
            Shape::Alias,
            Shape::Copy,
            Shape::Move,
            Shape::NoDrop,
            Shape::NotAllowed,
            Shape::Grab,
            Shape::Grabbing,
            Shape::EResize,
            Shape::NResize,
            Shape::NeResize,
            Shape::NwResize,
            Shape::SResize,
            Shape::SeResize,
            Shape::SwResize,
            Shape::WResize,
            Shape::EwResize,
            Shape::NsResize,
            Shape::NeswResize,
            Shape::NwseResize,
            Shape::ColResize,
            Shape::RowResize,
            Shape::AllScroll,
            Shape::ZoomIn,
            Shape::ZoomOut,
            Shape::DndAsk,
            Shape::AllResize,
        ];
        assert_eq!(shapes.len(), 36);
        assert!(
            shapes
                .iter()
                .all(|shape| android_cursor_icon(*shape) >= ANDROID_CURSOR_ARROW)
        );
    }

    #[test]
    fn relative_pointer_lock_round_trips_through_the_wayland_protocol() {
        let socket =
            std::env::temp_dir().join(format!("archphene-pointer-{}.sock", std::process::id()));
        let _ = std::fs::remove_file(&socket);
        let stage = Arc::new(AtomicU8::new(0));
        let server_stage = Arc::clone(&stage);
        let server_socket = socket.clone();
        let server = std::thread::spawn(move || {
            let mut core = CompositorCore::new().expect("Wayland display");
            core.bind_socket(&server_socket).expect("bind socket");
            while server_stage.load(Ordering::Acquire) != 11 {
                core.dispatch_once().expect("dispatch client");
                match server_stage.load(Ordering::Acquire) {
                    1 if core.state.locked_pointers.len() == 1
                        && core.state.relative_pointers.len() == 1
                        && core.state.surfaces.len() == 1
                        && core.state.pointers.len() == 1 =>
                    {
                        core.state.pointer_focus_surface = core.state.surfaces.first().cloned();
                        core.state.pointer_inside = true;
                        activate_pointer_lock_for_focus(&mut core.state);
                        assert!(core.pointer_capture_active());
                        assert_eq!(core.pointer_relative_motion(3.5, -2.25, 3.5, -2.25, 7), 1);
                        server_stage.store(2, Ordering::Release);
                    }
                    3 if core.state.locked_pointers[0]
                        .data::<PointerConstraintData>()
                        .expect("lock data")
                        .region
                        .lock()
                        .unwrap_or_else(|error| error.into_inner())
                        .pending
                        .is_some() =>
                    {
                        assert!(core.pointer_capture_active());
                        server_stage.store(4, Ordering::Release);
                    }
                    5 if !core.pointer_capture_active() => {
                        server_stage.store(6, Ordering::Release);
                    }
                    7 if core.pointer_capture_active() => {
                        server_stage.store(8, Ordering::Release);
                    }
                    9 => {
                        assert_eq!(core.cancel_pointer_capture(), 1);
                        assert!(!core.pointer_capture_active());
                        assert_eq!(core.state.locked_pointers.len(), 1);
                        assert!(
                            !core.state.locked_pointers[0]
                                .data::<PointerConstraintData>()
                                .expect("persistent lock data")
                                .eligible
                                .load(Ordering::Acquire)
                        );
                        server_stage.store(10, Ordering::Release);
                    }
                    _ => std::thread::yield_now(),
                }
            }
        });

        let connection = loop {
            match UnixStream::connect(&socket) {
                Ok(stream) => break Connection::from_socket(stream).expect("client connection"),
                Err(error)
                    if matches!(
                        error.kind(),
                        io::ErrorKind::NotFound | io::ErrorKind::ConnectionRefused
                    ) =>
                {
                    std::thread::yield_now();
                }
                Err(error) => panic!("connect client: {error}"),
            }
        };
        let (globals, mut events) =
            registry_queue_init::<PointerProtocolClient>(&connection).expect("registry");
        let queue = events.handle();
        let compositor = globals
            .bind::<client_wl_compositor::WlCompositor, _, _>(&queue, 1..=6, ())
            .expect("wl_compositor");
        let seat = globals
            .bind::<client_wl_seat::WlSeat, _, _>(&queue, 1..=9, ())
            .expect("wl_seat");
        let relative_manager = globals
            .bind::<client_relative_pointer_manager::ZwpRelativePointerManagerV1, _, _>(
                &queue,
                1..=1,
                (),
            )
            .expect("relative-pointer manager");
        let constraints = globals
            .bind::<client_pointer_constraints::ZwpPointerConstraintsV1, _, _>(
                &queue,
                1..=1,
                (),
            )
            .expect("pointer-constraints manager");
        let surface = compositor.create_surface(&queue, ());
        let pointer = seat.get_pointer(&queue, ());
        let _relative = relative_manager.get_relative_pointer(&pointer, &queue, ());
        let initial_region = compositor.create_region(&queue, ());
        initial_region.add(0, 0, 10, 10);
        let lock = constraints.lock_pointer(
            &surface,
            &pointer,
            Some(&initial_region),
            client_pointer_constraints::Lifetime::Persistent,
            &queue,
            (),
        );
        connection.flush().expect("flush protocol requests");
        stage.store(1, Ordering::Release);

        let mut client = PointerProtocolClient::default();
        for _ in 0..4 {
            events.roundtrip(&mut client).expect("locked roundtrip");
            if client.locked == 1 && client.relative_motion.is_some() {
                break;
            }
        }
        assert_eq!(client.locked, 1);
        assert_eq!(client.relative_motion, Some((3.5, -2.25)));

        let outside_region = compositor.create_region(&queue, ());
        outside_region.add(100, 100, 10, 10);
        lock.set_region(Some(&outside_region));
        connection.flush().expect("flush pending lock region");
        stage.store(3, Ordering::Release);
        while stage.load(Ordering::Acquire) != 4 {
            std::thread::yield_now();
        }
        assert_eq!(client.unlocked, 0);

        surface.commit();
        connection.flush().expect("flush surface commit");
        stage.store(5, Ordering::Release);
        for _ in 0..4 {
            events.roundtrip(&mut client).expect("unlocked roundtrip");
            if client.unlocked == 1 && stage.load(Ordering::Acquire) == 6 {
                break;
            }
        }
        assert_eq!(client.unlocked, 1);

        lock.set_region(None);
        surface.commit();
        connection.flush().expect("flush full-surface lock region");
        stage.store(7, Ordering::Release);
        for _ in 0..4 {
            events.roundtrip(&mut client).expect("relocked roundtrip");
            if client.locked == 2 && stage.load(Ordering::Acquire) == 8 {
                break;
            }
        }
        assert_eq!(client.locked, 2);

        stage.store(9, Ordering::Release);
        for _ in 0..4 {
            events
                .roundtrip(&mut client)
                .expect("capture escape roundtrip");
            if client.unlocked == 2 {
                break;
            }
        }
        assert_eq!(client.unlocked, 2);
        stage.store(11, Ordering::Release);
        server.join().expect("server thread");
        assert!(!socket.exists());
    }

    #[test]
    fn pointer_confinement_keeps_virtual_motion_inside_the_committed_region() {
        let socket = std::env::temp_dir().join(format!(
            "archphene-pointer-confine-{}.sock",
            std::process::id()
        ));
        let _ = std::fs::remove_file(&socket);
        let stage = Arc::new(AtomicU8::new(0));
        let server_stage = Arc::clone(&stage);
        let server_socket = socket.clone();
        let server = std::thread::spawn(move || {
            let mut core = CompositorCore::new().expect("Wayland display");
            core.bind_socket(&server_socket).expect("bind socket");
            while server_stage.load(Ordering::Acquire) != 5 {
                core.dispatch_once().expect("dispatch client");
                match server_stage.load(Ordering::Acquire) {
                    1 if core.state.confined_pointers.len() == 1
                        && core.state.relative_pointers.len() == 1
                        && core.state.surfaces.len() == 1
                        && core.state.pointers.len() == 1 =>
                    {
                        core.state.pointer_focus_surface = core.state.surfaces.first().cloned();
                        core.state.pointer_inside = true;
                        core.state.pointer_x = 5.0;
                        core.state.pointer_y = 5.0;
                        activate_pointer_lock_for_focus(&mut core.state);
                        assert!(core.pointer_capture_active());
                        assert_eq!(core.pointer_relative_motion(20.0, 0.0, 20.0, 0.0, 7), 2);
                        assert!(core.state.pointer_x >= 9.99);
                        assert!(core.state.pointer_x < 10.0);
                        assert_eq!(core.state.pointer_y, 5.0);
                        server_stage.store(2, Ordering::Release);
                    }
                    3 => {
                        assert_eq!(core.cancel_pointer_capture(), 1);
                        assert!(!core.pointer_capture_active());
                        assert_eq!(core.state.confined_pointers.len(), 1);
                        assert!(
                            !core.state.confined_pointers[0]
                                .data::<PointerConstraintData>()
                                .expect("persistent confinement data")
                                .eligible
                                .load(Ordering::Acquire)
                        );
                        server_stage.store(4, Ordering::Release);
                    }
                    _ => std::thread::yield_now(),
                }
            }
        });

        let connection = loop {
            match UnixStream::connect(&socket) {
                Ok(stream) => break Connection::from_socket(stream).expect("client connection"),
                Err(error)
                    if matches!(
                        error.kind(),
                        io::ErrorKind::NotFound | io::ErrorKind::ConnectionRefused
                    ) =>
                {
                    std::thread::yield_now();
                }
                Err(error) => panic!("connect client: {error}"),
            }
        };
        let (globals, mut events) =
            registry_queue_init::<PointerProtocolClient>(&connection).expect("registry");
        let queue = events.handle();
        let compositor = globals
            .bind::<client_wl_compositor::WlCompositor, _, _>(&queue, 1..=6, ())
            .expect("wl_compositor");
        let seat = globals
            .bind::<client_wl_seat::WlSeat, _, _>(&queue, 1..=9, ())
            .expect("wl_seat");
        let relative_manager = globals
            .bind::<client_relative_pointer_manager::ZwpRelativePointerManagerV1, _, _>(
                &queue,
                1..=1,
                (),
            )
            .expect("relative-pointer manager");
        let constraints = globals
            .bind::<client_pointer_constraints::ZwpPointerConstraintsV1, _, _>(
                &queue,
                1..=1,
                (),
            )
            .expect("pointer-constraints manager");
        let surface = compositor.create_surface(&queue, ());
        let pointer = seat.get_pointer(&queue, ());
        let _relative = relative_manager.get_relative_pointer(&pointer, &queue, ());
        let region = compositor.create_region(&queue, ());
        // The endpoint at x=25 is inside the second island, but confinement
        // must stop at the first edge of the subtracted hole.
        region.add(0, 0, 30, 10);
        region.subtract(10, 0, 10, 10);
        let _confine = constraints.confine_pointer(
            &surface,
            &pointer,
            Some(&region),
            client_pointer_constraints::Lifetime::Persistent,
            &queue,
            (),
        );
        connection.flush().expect("flush confinement requests");
        stage.store(1, Ordering::Release);

        let mut client = PointerProtocolClient::default();
        for _ in 0..4 {
            events.roundtrip(&mut client).expect("confined roundtrip");
            if client.confined == 1
                && client.relative_motion.is_some()
                && client.pointer_motion.is_some()
            {
                break;
            }
        }
        assert_eq!(client.confined, 1);
        assert_eq!(client.relative_motion, Some((20.0, 0.0)));
        let (pointer_x, pointer_y) = client.pointer_motion.expect("confined pointer motion");
        assert!(pointer_x >= 9.99);
        assert!(pointer_x < 10.0);
        assert_eq!(pointer_y, 5.0);

        stage.store(3, Ordering::Release);
        for _ in 0..4 {
            events.roundtrip(&mut client).expect("unconfined roundtrip");
            if client.unconfined == 1 {
                break;
            }
        }
        assert_eq!(client.unconfined, 1);
        stage.store(5, Ordering::Release);
        server.join().expect("server thread");
        assert!(!socket.exists());
    }

    #[test]
    fn jni_handle_registry_is_bounded_and_rejects_stale_generations() {
        let mut registry = JniHandleRegistry::<u32, 2>::new();
        let first = registry.insert(11).expect("first handle");
        let second = registry.insert(22).expect("second handle");
        assert!(first > 0);
        assert!(second > 0);
        assert_eq!(registry.insert(33), None);
        assert_eq!(registry.take(first), Some(11));
        assert_eq!(registry.index(first), None);

        let replacement = registry.insert(33).expect("reused slot");
        assert_ne!(replacement, first);
        assert_eq!(registry.index(first), None);
        assert_eq!(
            registry.slots[registry.index(replacement).expect("replacement index")].value,
            Some(33)
        );
        assert_eq!(registry.take(second), Some(22));
        assert_eq!(registry.take(second), None);
        assert_eq!(registry.index(0), None);
        assert_eq!(registry.index(-1), None);

        let mut retired = JniHandleRegistry::<u32, 1>::new();
        retired.slots[0].generation = JNI_HANDLE_GENERATION_MAX;
        assert_eq!(retired.insert(44), None);
    }

    #[test]
    fn compositor_jni_handle_rejects_use_after_destroy() {
        let handle =
            Java_org_archphene_compositorprobe_MainActivity_nativeCreateCore(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
            );
        assert!(handle > 0);
        assert_eq!(
            unsafe {
                Java_org_archphene_compositorprobe_MainActivity_nativeCompositorBindCount(
                    std::ptr::null_mut(),
                    std::ptr::null_mut(),
                    handle,
                )
            },
            0
        );
        unsafe {
            Java_org_archphene_compositorprobe_MainActivity_nativeDestroyCore(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                handle,
            );
        }
        assert_eq!(
            unsafe {
                Java_org_archphene_compositorprobe_MainActivity_nativeCompositorBindCount(
                    std::ptr::null_mut(),
                    std::ptr::null_mut(),
                    handle,
                )
            },
            -1
        );

        let replacement =
            Java_org_archphene_compositorprobe_MainActivity_nativeCreateCore(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
            );
        assert!(replacement > 0);
        assert_ne!(replacement, handle);
        unsafe {
            Java_org_archphene_compositorprobe_MainActivity_nativeDestroyCore(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                replacement,
            );
        }
    }

    #[test]
    fn runtime_execution_registry_is_bounded_and_reuses_slots() {
        let mut registry = RuntimeExecutionRegistry::new();
        for id in 1..=MAX_RUNTIME_EXECUTIONS as i64 {
            registry.begin(id).expect("bounded execution slot");
        }
        assert_eq!(
            registry.begin(MAX_RUNTIME_EXECUTIONS as i64 + 1),
            Err(libc::ENOSPC)
        );
        assert_eq!(registry.begin(1), Err(libc::EBUSY));
        assert!(registry.register_process_group(1, 101, 102));
        assert_eq!(registry.running_target(1), Some((101, 102)));
        assert_eq!(registry.cancel(1), Some(101));
        assert_eq!(
            registry.state(1),
            Some(RuntimeExecutionState::Cancelling(101))
        );
        registry.remove(1);
        registry
            .begin(MAX_RUNTIME_EXECUTIONS as i64 + 1)
            .expect("recycled execution slot");
    }

    #[test]
    fn runtime_execution_registry_consumes_early_cancellation_once() {
        let mut registry = RuntimeExecutionRegistry::new();
        assert_eq!(registry.cancel(77), None);
        assert_eq!(registry.state(77), Some(RuntimeExecutionState::Cancelled));
        assert_eq!(registry.begin(77), Err(libc::ECANCELED));
        assert_eq!(registry.state(77), None);
        registry
            .begin(77)
            .expect("retry after consumed cancellation");
        assert_eq!(registry.cancel(77), None);
        assert!(!registry.register_process_group(77, 201, 202));
        assert_eq!(
            registry.state(77),
            Some(RuntimeExecutionState::Cancelling(201))
        );
    }

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
    fn fullscreen_requests_override_layout_maximization_until_unset() {
        assert_eq!(
            configured_toplevel_states(true, false, true, true),
            vec![
                xdg_toplevel::State::Fullscreen,
                xdg_toplevel::State::Activated,
            ],
        );
        assert_eq!(
            configured_toplevel_states(false, false, true, true),
            vec![
                xdg_toplevel::State::Maximized,
                xdg_toplevel::State::Activated,
            ],
        );
        assert_eq!(
            configured_toplevel_states(false, false, false, true),
            vec![xdg_toplevel::State::Activated],
        );
        assert_eq!(
            configured_toplevel_states(false, true, false, false),
            vec![xdg_toplevel::State::Maximized],
        );
    }

    #[test]
    fn presentation_prefers_active_toplevel_with_primary_fallback() {
        let primary = 1;
        let active = 2;
        assert_eq!(active_or_primary(Some(&active), Some(&primary)), Some(&active));
        assert_eq!(active_or_primary(None, Some(&primary)), Some(&primary));
        assert_eq!(active_or_primary::<i32>(None, None), None);
    }

    #[test]
    fn presentation_and_restoration_choose_current_sizes() {
        assert!(!restoring_to_windowed_state(
            &[xdg_toplevel::State::Maximized],
            true,
        ));
        assert!(restoring_to_windowed_state(
            &[xdg_toplevel::State::Activated],
            true,
        ));
        for state in [
            xdg_toplevel::State::Fullscreen,
            xdg_toplevel::State::Maximized,
        ] {
            assert_eq!(
                toplevel_configure_size(
                    &[state],
                    432,
                    881,
                    Some((374, 546)),
                    Some((432, 881)),
                    false,
                ),
                (432, 881),
            );
        }
        assert_eq!(
            toplevel_configure_size(
                &[xdg_toplevel::State::Activated],
                432,
                881,
                Some((374, 546)),
                Some((432, 881)),
                false,
            ),
            (432, 881),
        );
        assert_eq!(
            toplevel_configure_size(
                &[xdg_toplevel::State::Activated],
                432,
                881,
                Some((374, 546)),
                Some((432, 881)),
                true,
            ),
            (374, 546),
        );
        assert_eq!(
            toplevel_configure_size(
                &[xdg_toplevel::State::Activated],
                432,
                881,
                None,
                None,
                false,
            ),
            (0, 0),
        );
        assert_eq!(
            toplevel_configure_size(
                &[xdg_toplevel::State::Activated],
                432,
                881,
                None,
                Some((432, 881)),
                true,
            ),
            (0, 0),
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
        assert!(!safe_runtime_program_name(&[b'a'; 129]));
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

        let at_limit = (0..MAX_RUNTIME_ENVIRONMENT_VARIABLES)
            .map(|index| format!("ARCHPHENE_TEST_{index}=value\n"))
            .collect::<String>();
        assert_eq!(
            parse_runtime_environment(at_limit.as_bytes())
                .expect("environment entry limit should be accepted")
                .len(),
            MAX_RUNTIME_ENVIRONMENT_VARIABLES
        );
        let over_limit = format!("{at_limit}ARCHPHENE_TEST_OVER=value\n");
        assert_eq!(
            parse_runtime_environment(over_limit.as_bytes()),
            Err(libc::EINVAL)
        );
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
        let pixels = values
            .iter()
            .flat_map(|value| [*value, 0, 0, 0])
            .collect();
        Arc::new(CommittedFrame::new(
            width,
            height,
            wl_shm::Format::Xrgb8888,
            pixels,
            None,
        ))
    }

    fn frame_values(frame: &CommittedFrame) -> Vec<u8> {
        frame
            .pixels()
            .chunks_exact(4)
            .map(|pixel| pixel[0])
            .collect()
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
            frame_values(&frame),
            [2, 4, 6, 1, 3, 5]
        );
    }

    #[test]
    fn physical_presentation_preserves_original_client_raster() {
        let original = test_frame(2, 2, &[1, 2, 3, 4]);
        let logical = Arc::new(CommittedFrame::new(
            1,
            1,
            wl_shm::Format::Xrgb8888,
            vec![1, 0, 0, 0],
            Some(Arc::clone(&original)),
        ));
        let selected = presentation_buffer_frame(&logical, true, BufferTransform::Normal, None);
        assert!(Arc::ptr_eq(&selected, &original));

        let mut output =
            CommittedFrame::new(2, 2, wl_shm::Format::Xrgb8888, vec![0; 16], None);
        blend_popup_frame(&mut output, &selected, 0, 0, 2, 2);
        assert_eq!(
            frame_values(&output),
            [1, 2, 3, 4]
        );
        assert!(Arc::ptr_eq(
            &presentation_buffer_frame(&logical, true, BufferTransform::Rotate90, None,),
            &logical,
        ));
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
            let mut values = frame_values(&frame);
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
            frame_values(&frame),
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
            frame_values(&restored),
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
        assert_eq!(
            BufferTransform::Rotate90.surface_damage_to_buffer(damage, 4, 2, 2),
            RegionRectangle::new(2, 0, 2, 2)
        );
    }

    #[test]
    fn bounds_pending_damage_and_promotes_overflow_to_full() {
        let rectangle = RegionRectangle::new(0, 0, 1, 1).expect("damage");
        let mut damage = Vec::new();
        let mut full = false;
        for _ in 0..MAX_PENDING_DAMAGE_RECTANGLES {
            push_bounded_damage(&mut damage, &mut full, rectangle);
        }
        assert_eq!(damage.len(), MAX_PENDING_DAMAGE_RECTANGLES);
        assert!(!full);

        push_bounded_damage(&mut damage, &mut full, rectangle);
        assert!(damage.is_empty());
        assert!(full);
        push_bounded_damage(&mut damage, &mut full, rectangle);
        assert!(damage.is_empty());

        let mut surface = SurfaceState::default();
        for _ in 0..=MAX_PENDING_DAMAGE_RECTANGLES {
            let SurfaceState {
                pending_buffer_damage,
                pending_buffer_damage_full,
                ..
            } = &mut surface;
            push_bounded_damage(
                pending_buffer_damage,
                pending_buffer_damage_full,
                rectangle,
            );
        }
        let (surface_damage, buffer_damage, overflow) = take_pending_damage(&mut surface);
        assert!(surface_damage.is_empty());
        assert!(buffer_damage.is_empty());
        assert!(overflow);
        restore_pending_damage_buffers(&mut surface, surface_damage, buffer_damage);
        let SurfaceState {
            pending_buffer_damage,
            pending_buffer_damage_full,
            ..
        } = &mut surface;
        push_bounded_damage(
            pending_buffer_damage,
            pending_buffer_damage_full,
            rectangle,
        );
        let (_, buffer_damage, overflow) = take_pending_damage(&mut surface);
        assert_eq!(buffer_damage, [rectangle]);
        assert!(!overflow);

        let mut accumulated = Vec::new();
        for x in 0..=MAX_PENDING_DAMAGE_RECTANGLES {
            push_accumulated_damage(
                &mut accumulated,
                RegionRectangle::new(x as i32, 0, 1, 1).expect("accumulated damage"),
            );
        }
        assert_eq!(accumulated, [RegionRectangle::new(0, 0, 65, 1).expect("union")]);
    }

    #[test]
    fn retains_undamaged_shm_pixels_and_reads_only_the_damage_bounds() {
        let path = std::env::temp_dir().join(format!(
            "archphene-shm-damage-{}.bin",
            std::process::id()
        ));
        let _ = std::fs::remove_file(&path);
        let file = std::fs::OpenOptions::new()
            .create_new(true)
            .read(true)
            .write(true)
            .open(&path)
            .expect("create SHM fixture");
        let next_pixels = [
            10, 0, 0, 0, 20, 0, 0, 0, 30, 0, 0, 0, 40, 0, 0, 0,
        ];
        file.write_all_at(&next_pixels, 0).expect("write SHM fixture");
        let buffer = ShmBufferInner {
            pool: Arc::new(Mutex::new(ShmPoolInner { file, size: 16 })),
            patch: Mutex::new(Vec::new()),
            offset: 0,
            width: 2,
            height: 2,
            stride: 8,
            format: wl_shm::Format::Xrgb8888,
        };
        let previous = test_frame(2, 2, &[1, 2, 3, 4]);
        let frame = buffer
            .snapshot(
                Some(&previous),
                ShmSnapshotState {
                    surface_damage: &[],
                    buffer_damage: &[RegionRectangle::new(1, 0, 1, 1).expect("damage")],
                    transform: BufferTransform::Normal,
                    scale: 1,
                    viewport_active: false,
                    allow_in_place: true,
                    force_full_damage: false,
                },
            )
            .expect("partial SHM snapshot");
        assert!(Arc::ptr_eq(&frame, &previous));
        assert_eq!(frame_values(&frame), [1, 20, 3, 4]);
        let replacement_pixels = [
            50, 0, 0, 0, 60, 0, 0, 0, 70, 0, 0, 0, 80, 0, 0, 0,
        ];
        buffer
            .pool
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .file
            .write_all_at(&replacement_pixels, 0)
            .expect("replace SHM fixture");
        let synchronized = buffer
            .snapshot(
                Some(&previous),
                ShmSnapshotState {
                    surface_damage: &[],
                    buffer_damage: &[RegionRectangle::new(0, 0, 1, 1).expect("damage")],
                    transform: BufferTransform::Normal,
                    scale: 1,
                    viewport_active: false,
                    allow_in_place: false,
                    force_full_damage: false,
                },
            )
            .expect("detached synchronized snapshot");
        assert!(!Arc::ptr_eq(&synchronized, &previous));
        assert_eq!(frame_values(&synchronized), [50, 20, 3, 4]);
        assert_eq!(frame_values(&previous), [1, 20, 3, 4]);
        let mut synchronized_surface = SurfaceState {
            committed_frame: Some(Arc::clone(&previous)),
            cached_frame: Some(Some(Arc::clone(&synchronized))),
            ..SurfaceState::default()
        };
        assert!(synchronized_cache_frame_is_detached(&synchronized_surface));
        buffer
            .pool
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .file
            .write_all_at(&[90, 0, 0, 0], 4)
            .expect("update detached synchronized pixel");
        let reused_synchronized = buffer
            .snapshot(
                Some(&synchronized),
                ShmSnapshotState {
                    surface_damage: &[],
                    buffer_damage: &[RegionRectangle::new(1, 0, 1, 1).expect("damage")],
                    transform: BufferTransform::Normal,
                    scale: 1,
                    viewport_active: false,
                    allow_in_place: surface_snapshot_allows_in_place(
                        &synchronized_surface,
                        true,
                        false,
                    ),
                    force_full_damage: false,
                },
            )
            .expect("reused synchronized snapshot");
        assert!(Arc::ptr_eq(&reused_synchronized, &synchronized));
        assert_eq!(frame_values(&reused_synchronized), [50, 90, 3, 4]);
        assert_eq!(frame_values(&previous), [1, 20, 3, 4]);
        synchronized_surface.cached_frame = Some(Some(reused_synchronized));
        assert!(!surface_snapshot_allows_in_place(
            &synchronized_surface,
            true,
            true,
        ));
        synchronized_surface.committed_frame = synchronized_surface
            .cached_frame
            .take()
            .expect("cached synchronized state");
        synchronized_surface.cached_frame = Some(synchronized_surface.committed_frame.clone());
        assert!(!synchronized_cache_frame_is_detached(&synchronized_surface));

        let forced = buffer
            .snapshot(
                Some(&previous),
                ShmSnapshotState {
                    surface_damage: &[],
                    buffer_damage: &[RegionRectangle::new(0, 0, 1, 1).expect("damage")],
                    transform: BufferTransform::Normal,
                    scale: 1,
                    viewport_active: false,
                    allow_in_place: true,
                    force_full_damage: true,
                },
            )
            .expect("forced full SHM snapshot");
        assert!(!Arc::ptr_eq(&forced, &previous));
        assert_eq!(frame_values(&forced), [50, 90, 70, 80]);

        buffer
            .pool
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .file
            .set_len(4)
            .expect("truncate SHM fixture");
        assert!(
            buffer
                .snapshot(
                    Some(&previous),
                    ShmSnapshotState {
                        surface_damage: &[],
                        buffer_damage: &[RegionRectangle::new(0, 1, 1, 1).expect("damage")],
                        transform: BufferTransform::Normal,
                        scale: 1,
                        viewport_active: false,
                        allow_in_place: true,
                        force_full_damage: false,
                    },
                )
                .is_err()
        );
        assert_eq!(frame_values(&previous), [1, 20, 3, 4]);
        std::fs::remove_file(path).expect("remove SHM fixture");
    }

    #[test]
    fn shm_damage_falls_back_when_retention_cannot_preserve_semantics() {
        let file = File::open("/dev/null").expect("open harmless fixture");
        let buffer = ShmBufferInner {
            pool: Arc::new(Mutex::new(ShmPoolInner { file, size: 16 })),
            patch: Mutex::new(Vec::new()),
            offset: 0,
            width: 2,
            height: 2,
            stride: 8,
            format: wl_shm::Format::Xrgb8888,
        };
        let damage = [RegionRectangle::new(0, 0, 1, 1).expect("damage")];
        assert_eq!(
            buffer.read_damage(
                false,
                &damage,
                &[],
                BufferTransform::Normal,
                1,
                false
            ),
            ShmReadDamage::Full
        );
        assert_eq!(
            buffer.read_damage(
                true,
                &damage,
                &[],
                BufferTransform::Normal,
                1,
                true
            ),
            ShmReadDamage::Full
        );
        assert_eq!(
            buffer.read_damage(true, &[], &[], BufferTransform::Normal, 1, false),
            ShmReadDamage::Full
        );
        assert_eq!(
            buffer.read_damage(
                true,
                &[RegionRectangle::new(4, 4, 1, 1).expect("outside")],
                &[],
                BufferTransform::Normal,
                1,
                false
            ),
            ShmReadDamage::Unchanged
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
        assert_eq!(&frame.pixels()[0..4], &[2, 0, 0, 0]);
        assert_eq!(&frame.pixels()[60..64], &[7, 0, 0, 0]);
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
        let source = CommittedFrame::new(
            2,
            1,
            wl_shm::Format::Xrgb8888,
            vec![10, 20, 30, 255, 200, 210, 220, 255],
            None,
        );
        let mut destination =
            CommittedFrame::new(4, 2, wl_shm::Format::Argb8888, vec![0; 4 * 2 * 4], None);
        blend_popup_frame(&mut destination, &source, 0, 0, 4, 2);
        let destination_pixels = destination.pixels();
        for row in 0..2 {
            let offset = row * 16;
            assert_eq!(
                &destination_pixels[offset..offset + 8],
                &[10, 20, 30, 255, 10, 20, 30, 255]
            );
            assert_eq!(
                &destination_pixels[offset + 8..offset + 16],
                &[200, 210, 220, 255, 200, 210, 220, 255]
            );
        }
    }

    #[test]
    fn clips_and_alpha_blends_popup_frames() {
        let mut destination = CommittedFrame::new(
            2,
            1,
            wl_shm::Format::Xrgb8888,
            vec![10, 20, 30, 0, 40, 50, 60, 0],
            None,
        );
        let source = CommittedFrame::new(
            2,
            1,
            wl_shm::Format::Argb8888,
            vec![110, 120, 130, 128, 210, 220, 230, 255],
            None,
        );

        blend_popup_frame(&mut destination, &source, 1, 0, 2, 1);

        assert_eq!(
            *destination.pixels(),
            [10, 20, 30, 0, 75, 85, 95, 0]
        );
    }

    #[test]
    fn opaque_surface_region_ignores_argb_alpha() {
        let mut destination =
            CommittedFrame::new(2, 1, wl_shm::Format::Argb8888, vec![0; 8], None);
        let source = CommittedFrame::new(
            2,
            1,
            wl_shm::Format::Argb8888,
            vec![10, 20, 30, 0, 40, 50, 60, 0],
            None,
        );
        let opaque_region = RegionState {
            operations: vec![RegionOperation::Add(
                RegionRectangle::new(0, 0, 1, 1).expect("valid region"),
            )],
        };

        blend_frame(&mut destination, &source, Some(&opaque_region), 0, 0, 2, 1);

        assert_eq!(
            *destination.pixels(),
            [10, 20, 30, 255, 0, 0, 0, 0]
        );
    }

    #[test]
    fn opaque_surface_region_uses_surface_coordinates_when_scaled() {
        let mut destination =
            CommittedFrame::new(2, 1, wl_shm::Format::Argb8888, vec![0; 8], None);
        let source = CommittedFrame::new(
            4,
            1,
            wl_shm::Format::Argb8888,
            vec![10, 20, 30, 0, 10, 20, 30, 0, 40, 50, 60, 0, 40, 50, 60, 0],
            None,
        );
        let opaque_region = RegionState {
            operations: vec![RegionOperation::Add(
                RegionRectangle::new(0, 0, 1, 1).expect("valid region"),
            )],
        };

        blend_frame(&mut destination, &source, Some(&opaque_region), 0, 0, 2, 1);

        assert_eq!(
            *destination.pixels(),
            [10, 20, 30, 255, 0, 0, 0, 0]
        );
    }

    #[test]
    fn crops_client_side_shadow_using_negative_surface_origin() {
        let mut source_pixels = Vec::new();
        for value in 0u8..16 {
            source_pixels.extend_from_slice(&[value, 0, 0, 0]);
        }
        let source =
            CommittedFrame::new(4, 4, wl_shm::Format::Xrgb8888, source_pixels, None);
        let mut output =
            CommittedFrame::new(2, 2, wl_shm::Format::Xrgb8888, vec![0; 16], None);

        blend_popup_frame(&mut output, &source, -1, -1, 4, 4);

        let output_pixels = output.pixels();
        assert_eq!(output_pixels[0], 5);
        assert_eq!(output_pixels[4], 6);
        assert_eq!(output_pixels[8], 9);
        assert_eq!(output_pixels[12], 10);
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
        let surface = SurfaceState {
            committed_input_region: Some(RegionState {
                operations: vec![RegionOperation::Add(RegionRectangle {
                    x: 1,
                    y: 0,
                    width: 2,
                    height: 2,
                })],
            }),
            ..SurfaceState::default()
        };

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
        let mut state = XdgSurfaceState {
            pending_window_geometry: Some(first),
            ..XdgSurfaceState::default()
        };
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
    fn does_not_send_toplevel_state_before_initial_surface_commit() {
        let mut state = XdgSurfaceState::default();
        assert!(!state.initial_configure_sent());
        state.pending_configures.push_back(XdgConfigure {
            serial: 1,
            popup_geometry: None,
            toplevel_size: Some((0, 0)),
            restores_windowed: false,
        });
        assert!(state.initial_configure_sent());
        state.pending_configures.clear();
        state.acknowledged_configure = Some(XdgConfigure {
            serial: 1,
            popup_geometry: None,
            toplevel_size: Some((0, 0)),
            restores_windowed: true,
        });
        assert!(state.initial_configure_sent());
        assert!(state.commit_windowed_restoration());
        assert!(!state.commit_windowed_restoration());
    }

    #[test]
    fn text_input_commit_only_reports_android_visible_changes() {
        let mut state = TextInputState {
            pending_enabled: Some(true),
            ..TextInputState::default()
        };
        assert!(apply_text_input_commit(&mut state, false));
        assert!(state.enabled);

        state.pending_surrounding_text = Some(SurroundingText {
            text: "hello".to_owned(),
            cursor: 5,
            anchor: 5,
        });
        state.pending_content_type = Some((3, 7));
        assert!(apply_text_input_commit(&mut state, false));

        state.pending_surrounding_text = Some(SurroundingText {
            text: "hello".to_owned(),
            cursor: 5,
            anchor: 5,
        });
        state.pending_content_type = Some((3, 7));
        state.pending_cursor_rectangle = Some((1, 2, 3, 4));
        assert!(apply_text_input_commit(&mut state, false));
        assert_eq!(state.cursor_rectangle, Some((1, 2, 3, 4)));

        state.pending_cursor_rectangle = Some((5, 6, 7, 8));
        assert!(!apply_text_input_commit(&mut state, false));
        assert_eq!(state.cursor_rectangle, Some((5, 6, 7, 8)));

        state.pending_surrounding_text = Some(SurroundingText {
            text: "hello!".to_owned(),
            cursor: 6,
            anchor: 6,
        });
        assert!(apply_text_input_commit(&mut state, false));

        state.pending_enabled = Some(false);
        assert!(apply_text_input_commit(&mut state, false));
        assert!(!state.enabled);
        assert!(state.surrounding_text.is_none());
        assert_eq!(state.content_type, (0, 0));
    }

    #[test]
    fn first_cursor_rectangle_advances_empty_editor_evidence() {
        let mut state = TextInputState {
            enabled: true,
            surrounding_text: Some(SurroundingText {
                text: String::new(),
                cursor: 0,
                anchor: 0,
            }),
            pending_cursor_rectangle: Some((0, 0, 0, 0)),
            ..TextInputState::default()
        };
        assert!(apply_text_input_commit(&mut state, false));
        assert_eq!(state.cursor_rectangle, Some((0, 0, 0, 0)));
    }

    #[test]
    fn accepts_clients_from_owned_filesystem_socket_and_cleans_it_up() {
        let socket =
            std::env::temp_dir().join(format!("archphene-compositor-{}.sock", std::process::id()));
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
    fn stale_compositor_cannot_unlink_a_replacement_socket() {
        let socket = std::env::temp_dir().join(format!(
            "archphene-compositor-replacement-{}.sock",
            std::process::id(),
        ));
        let _ = std::fs::remove_file(&socket);
        let mut stale = CompositorCore::new().expect("stale Wayland display");
        stale.bind_socket(&socket).expect("bind stale socket");
        let mut replacement = CompositorCore::new().expect("replacement Wayland display");
        replacement
            .bind_socket(&socket)
            .expect("bind replacement socket");

        drop(stale);
        assert!(socket.exists());
        let _client = UnixStream::connect(&socket).expect("connect replacement client");
        assert!(replacement.dispatch_once().expect("accept replacement client") >= 1);

        drop(replacement);
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
        assert!(!core.take_linux_clipboard_clear());
        assert_eq!(core.set_clipboard_active(true), 1);
        assert_eq!(core.offer_android_clipboard_text(), 0);
        assert_eq!(core.clear_android_clipboard(), 1);
        assert_eq!(core.clear_android_clipboard(), 0);
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
    fn bounds_simultaneous_toplevel_windows() {
        assert!(!toplevel_limit_reached(MAX_TOPLEVELS - 1));
        assert!(toplevel_limit_reached(MAX_TOPLEVELS));
        assert!(toplevel_limit_reached(MAX_TOPLEVELS + 1));
    }

    #[test]
    fn toplevel_size_constraints_preserve_client_bounds() {
        assert_eq!(
            ToplevelSizeConstraints::default().constrain(432, 882),
            (432, 882)
        );
        let constraints = ToplevelSizeConstraints {
            min_width: 460,
            min_height: 607,
            max_width: 900,
            max_height: 1_000,
        };
        assert_eq!(constraints.constrain(432, 882), (460, 882));
        assert_eq!(constraints.constrain_bounded(432, 882), (460, 882));
        assert_eq!(constraints.constrain(1_200, 1_400), (900, 1_000));
        assert_eq!(constraints.constrain(0, 0), (0, 0));

        let conflicting = ToplevelSizeConstraints {
            min_width: 600,
            max_width: 500,
            ..ToplevelSizeConstraints::default()
        };
        assert_eq!(conflicting.constrain(432, 882), (600, 882));
        assert_eq!(conflicting.constrain_bounded(432, 882), (576, 882));

        let hostile = ToplevelSizeConstraints {
            min_width: i32::MAX,
            min_height: i32::MAX,
            ..ToplevelSizeConstraints::default()
        };
        assert_eq!(hostile.constrain_bounded(432, 882), (576, 1_176));
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
            false,
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
            false,
        );
        assert_eq!((main.output_width, main.output_height), (540, 1102));
        assert_eq!((main.root_x, main.root_y), (-26, -23));
        assert!(!main.overlay_primary);

        let chooser = calculate_toplevel_layout(
            540,
            1102,
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
            false,
        );
        assert_eq!((chooser.output_width, chooser.output_height), (540, 1102));
        assert_eq!((chooser.root_x, chooser.root_y), (0, 107));
        assert_eq!((chooser.root_width, chooser.root_height), (540, 887));
        assert!(chooser.overlay_primary);
    }

    #[test]
    fn centers_compact_secondary_window_over_primary() {
        let layout = calculate_toplevel_layout(
            540,
            1102,
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
            false,
        );
        assert_eq!((layout.output_width, layout.output_height), (540, 1102));
        assert_eq!((layout.root_x, layout.root_y), (73, 268));
        assert!(layout.overlay_primary);
    }

    #[test]
    fn centers_compact_secondary_against_output_when_primary_resize_is_stale() {
        let layout = calculate_toplevel_layout(
            432,
            881,
            384,
            220,
            404,
            240,
            WindowGeometry {
                x: 10,
                y: 10,
                width: 384,
                height: 220,
            },
            Some((432, 537)),
            false,
        );
        assert_eq!((layout.output_width, layout.output_height), (432, 881));
        assert_eq!((layout.root_x, layout.root_y), (14, 320));
        assert_eq!((layout.root_width, layout.root_height), (404, 240));
        assert!(layout.overlay_primary);
    }

    #[test]
    fn pending_primary_resize_keeps_visible_touch_transform_exact() {
        let layout = calculate_toplevel_layout(
            432,
            537,
            432,
            881,
            432,
            881,
            WindowGeometry {
                x: 0,
                y: 0,
                width: 432,
                height: 881,
            },
            None,
            true,
        );
        assert_eq!((layout.output_width, layout.output_height), (432, 537));
        assert_eq!((layout.root_x, layout.root_y), (0, 0));
        assert_eq!((layout.root_width, layout.root_height), (432, 537));
        assert!(!layout.overlay_primary);
        assert!(
            (scale_input_coordinate(268.5, layout.root_height, 881) - 440.5).abs() < 0.001
        );
    }

    #[test]
    fn pending_primary_resize_requires_the_current_output_size() {
        let mut state = XdgSurfaceState::default();
        state.pending_configures.push_back(XdgConfigure {
            serial: 1,
            popup_geometry: None,
            toplevel_size: Some((432, 881)),
            restores_windowed: false,
        });
        assert!(!state.has_pending_toplevel_size(432, 537));

        state.pending_configures.push_back(XdgConfigure {
            serial: 2,
            popup_geometry: None,
            toplevel_size: Some((432, 537)),
            restores_windowed: false,
        });
        assert!(state.has_pending_toplevel_size(432, 537));
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
            false,
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
            false,
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
            false,
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
        assert!((scale_input_coordinate(1626.0, 1692, 2257) - 2_168.960_993).abs() < 0.001);
    }

    #[test]
    fn maps_pressed_evdev_keys_to_xkb_modifier_bits() {
        assert_eq!(CompositorCore::keyboard_modifier_mask(&[]), 0);
        assert_eq!(CompositorCore::keyboard_modifier_mask(&[42]), 1);
        assert_eq!(CompositorCore::keyboard_modifier_mask(&[29]), 4);
        assert_eq!(CompositorCore::keyboard_modifier_mask(&[56]), 8);
        assert_eq!(CompositorCore::keyboard_modifier_mask(&[54, 97, 100]), 13);
        assert_eq!(CompositorCore::keyboard_modifier_mask(&[125]), 1 << 6);
        assert_eq!(
            CompositorCore::keyboard_modifier_mask(&[42, 29, 56, 126]),
            0x4d
        );
        assert_eq!(CompositorCore::keyboard_modifier_mask(&[24]), 0);
    }

    #[test]
    fn maps_bounded_android_hardware_keys_to_evdev() {
        assert_eq!(android_key_to_evdev(29), Some(30));
        assert_eq!(android_key_to_evdev(54), Some(44));
        assert_eq!(android_key_to_evdev(7), Some(11));
        assert_eq!(android_key_to_evdev(16), Some(10));
        assert_eq!(android_key_to_evdev(66), Some(28));
        assert_eq!(android_key_to_evdev(68), Some(41));
        assert_eq!(android_key_to_evdev(69), Some(12));
        assert_eq!(android_key_to_evdev(70), Some(13));
        assert_eq!(android_key_to_evdev(71), Some(26));
        assert_eq!(android_key_to_evdev(72), Some(27));
        assert_eq!(android_key_to_evdev(73), Some(43));
        assert_eq!(android_key_to_evdev(74), Some(39));
        assert_eq!(android_key_to_evdev(75), Some(40));
        assert_eq!(android_key_to_evdev(55), Some(51));
        assert_eq!(android_key_to_evdev(56), Some(52));
        assert_eq!(android_key_to_evdev(76), Some(53));
        assert_eq!(android_key_to_evdev(82), Some(139));
        assert_eq!(android_key_to_evdev(115), Some(58));
        assert_eq!(android_key_to_evdev(117), Some(125));
        assert_eq!(android_key_to_evdev(118), Some(126));
        assert_eq!(android_key_to_evdev(113), Some(29));
        assert_eq!(android_key_to_evdev(142), Some(88));
        assert_eq!(android_key_to_evdev(0), None);
        assert_eq!(android_key_to_evdev(143), None);
        assert_eq!(android_meta_to_wayland(0), 0);
        assert_eq!(android_meta_to_wayland(0x41), 1);
        assert_eq!(android_meta_to_wayland(0x2000), 1 << 2);
        assert_eq!(android_meta_to_wayland(0x20), 1 << 3);
        assert_eq!(android_meta_to_wayland(0x7073), 0x0d);
        assert_eq!(android_meta_to_wayland(0x10000), 1 << 6);
        assert_eq!(android_meta_to_wayland(0x77073), 0x4d);
    }

    #[test]
    fn maps_all_supported_android_mouse_buttons_without_aliasing() {
        assert_eq!(android_button_to_evdev(1), Some(272));
        assert_eq!(android_button_to_evdev(2), Some(273));
        assert_eq!(android_button_to_evdev(4), Some(274));
        assert_eq!(android_button_to_evdev(8), Some(275));
        assert_eq!(android_button_to_evdev(16), Some(276));
        assert_eq!(android_button_to_evdev(0), None);
        assert_eq!(android_button_to_evdev(3), None);
        assert_eq!(pointer_button_bit(272), Some(1));
        assert_eq!(pointer_button_bit(276), Some(16));
        assert_eq!(pointer_button_bit(277), None);
    }

    #[test]
    fn launcher_input_records_are_semantic_bounded_and_reset_host_focus() {
        let mut core = CompositorCore::new().expect("compositor");
        assert!(core.state.host_active);
        assert_eq!(
            dispatch_launcher_input_record(&mut core, [10, 0, 7, 0, 0, 0]),
            Ok(0)
        );
        assert!(!core.state.host_active);
        assert_eq!(core.pointer_motion(10.0, 10.0, 8), 0);
        assert_eq!(
            dispatch_launcher_input_record(&mut core, [10, 1, 9, 0, 0, 0]),
            Ok(0)
        );
        assert!(core.state.host_active);
        assert_eq!(
            dispatch_launcher_input_record(&mut core, [8, 3, 1, 9, 0, 0]),
            Err(())
        );
        assert_eq!(
            dispatch_launcher_input_record(&mut core, [9, 0, 120_001, 9, 0, 0]),
            Err(())
        );
        assert_eq!(
            dispatch_launcher_input_record(&mut core, [1, 32, 0, 0, 9, 0]),
            Err(())
        );
        assert_eq!(
            dispatch_launcher_input_record(&mut core, [5, 29, 2, 9, 0, 0]),
            Ok(0)
        );
        assert_eq!(
            dispatch_launcher_input_record(&mut core, [5, 29, 3, 9, 0, 0]),
            Err(())
        );
        assert_eq!(
            dispatch_launcher_input_record(&mut core, [11, 1_000, -500, 1_000, -500, 10]),
            Ok(0)
        );
        assert_eq!(
            dispatch_launcher_input_record(&mut core, [11, 16_384_001, 0, 0, 0, 10]),
            Err(())
        );
        assert_eq!(
            dispatch_launcher_input_record(&mut core, [12, 0, 0, 0, 0, 0]),
            Ok(0)
        );
    }

    #[test]
    fn launcher_density_separates_android_pixels_from_wayland_output() {
        let mut core = CompositorCore::new().expect("compositor");
        core.set_toplevel_tiling(true);
        assert_eq!(configure_launcher_output(&mut core, 1080, 2316, 450, 0), 0);
        assert_eq!(
            (core.state.output_width, core.state.output_height),
            (432, 926)
        );
        assert_eq!(
            (core.state.output_mode_width, core.state.output_mode_height),
            (1080, 2316)
        );
        assert_eq!(core.state.output_scale, 3);
        assert_eq!(core.state.output_fractional_scale, 300);

    }

    #[test]
    fn launcher_auto_density_only_floors_desktop_logical_space() {
        assert_eq!(launcher_auto_density_dpi(1080, 2202, 420), 400);
        assert_eq!(launcher_auto_density_dpi(1080, 1343, 420), 400);
        assert_eq!(launcher_auto_density_dpi(2316, 978, 420), 362);
        assert_eq!(launcher_auto_density_dpi(1920, 1080, 160), 160);
        assert_eq!(launcher_auto_density_dpi(2560, 1600, 320), 320);
    }

    #[test]
    fn launcher_explicit_geometry_scales_the_automatic_baseline() {
        assert_eq!(launcher_density_dpi(1080, 2202, 420, 0), 400);
        assert_eq!(launcher_density_dpi(1080, 2202, 420, 75), 300);
        assert_eq!(launcher_density_dpi(1080, 2202, 420, 100), 400);
        assert_eq!(launcher_density_dpi(1080, 2202, 420, 125), 500);
        assert_eq!(launcher_density_dpi(1080, 2202, 420, 150), 600);
        assert!(valid_launcher_geometry_percent(0));
        assert!(valid_launcher_geometry_percent(150));
        assert!(!valid_launcher_geometry_percent(50));
        assert!(!valid_launcher_geometry_percent(200));

        let mut core = CompositorCore::new().expect("compositor");
        core.set_toplevel_tiling(true);
        assert_eq!(
            configure_launcher_output(&mut core, 1080, 2202, 420, 125),
            0
        );
        assert_eq!(
            (core.state.output_width, core.state.output_height),
            (346, 705)
        );
        assert_eq!(core.state.output_fractional_scale, 375);
    }

    #[test]
    fn clipboard_descriptor_io_is_bounded_and_deadline_driven() {
        use std::io::Read;

        let (read_end, mut write_end) = create_cloexec_pipe().expect("read pipe");
        write_end.write_all(b"clipboard").expect("write fixture");
        drop(write_end);
        let mut output = [0_u8; 16];
        assert_eq!(read_clipboard_descriptor(read_end, &mut output, 100), 9);
        assert_eq!(&output[..9], b"clipboard");

        let (read_end, mut write_end) = create_cloexec_pipe().expect("overflow pipe");
        write_end
            .write_all(b"four")
            .expect("write overflow fixture");
        drop(write_end);
        assert_eq!(
            read_clipboard_descriptor(read_end, &mut output[..3], 100),
            -3
        );

        let (read_end, _write_end) = create_cloexec_pipe().expect("timeout pipe");
        assert_eq!(read_clipboard_descriptor(read_end, &mut output, 10), -2);

        let (mut read_end, write_end) = create_cloexec_pipe().expect("write pipe");
        assert_eq!(write_clipboard_descriptor(write_end, b"android", 100), 7);
        let mut received = [0_u8; 7];
        read_end
            .read_exact(&mut received)
            .expect("read written text");
        assert_eq!(&received, b"android");
    }

    #[test]
    fn clipboard_mime_contract_prefers_html_and_preserves_plain_fallbacks() {
        assert_eq!(
            clipboard_format_for_mime(HTML_MIME_TYPE),
            Some(ClipboardFormat::Html),
        );
        for mime_type in TEXT_MIME_TYPES {
            assert_eq!(
                clipboard_format_for_mime(mime_type),
                Some(ClipboardFormat::PlainText),
            );
        }
        assert_eq!(clipboard_format_for_mime("image/png"), None);
        assert_eq!(
            android_clipboard_mime_types(false),
            vec![TEXT_MIME_TYPES[0], TEXT_MIME_TYPES[1]],
        );
        assert_eq!(
            android_clipboard_mime_types(true),
            vec![HTML_MIME_TYPE, TEXT_MIME_TYPES[0], TEXT_MIME_TYPES[1]],
        );
    }

    #[test]
    fn embeds_null_terminated_xkb_v1_keymap() {
        assert!(XKB_KEYMAP.starts_with(b"xkb_keymap {"));
        assert!(XKB_KEYMAP.ends_with(b"};\n\0"));
        for symbols in [
            b"[ 1, exclam ]".as_slice(),
            b"[ minus, underscore ]".as_slice(),
            b"[ bracketleft, braceleft ]".as_slice(),
            b"[ semicolon, colon ]".as_slice(),
            b"[ backslash, bar ]".as_slice(),
            b"[ slash, question ]".as_slice(),
        ] {
            assert!(
                XKB_KEYMAP.windows(symbols.len()).any(|window| window == symbols),
                "keymap omitted {symbols:?}"
            );
        }
    }
}

}
