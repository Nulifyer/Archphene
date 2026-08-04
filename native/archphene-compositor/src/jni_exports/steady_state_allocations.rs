use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::Cell;
use std::fs::OpenOptions;
use std::os::unix::fs::FileExt;
use std::os::unix::net::UnixStream;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::mpsc;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use wayland_client::globals::{GlobalListContents, registry_queue_init};
use wayland_client::protocol::{
    wl_callback as client_wl_callback,
    wl_compositor as client_wl_compositor, wl_registry as client_wl_registry,
    wl_subcompositor as client_wl_subcompositor, wl_subsurface as client_wl_subsurface,
    wl_surface as client_wl_surface,
};
use wayland_client::{Connection, QueueHandle};
use wayland_server::protocol::wl_shm;
use wayland_server::Resource;

use super::{
    BufferTransform, CommittedFrame, CompositorCore, RegionRectangle, ShmBufferInner,
    ShmPoolInner, ShmSnapshotState, SurfaceData, SurfaceState, apply_cached_subsurface_children,
    copy_frame_to_rgba_buffer, damage_for_commit_into, push_bounded_damage,
    restore_commit_damage_scratch, restore_pending_damage_buffers,
    surface_snapshot_allows_in_place, take_pending_damage, PresentationCopyDamage,
};

#[derive(Default)]
struct SubsurfaceClient;

impl wayland_client::Dispatch<client_wl_registry::WlRegistry, GlobalListContents>
    for SubsurfaceClient
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

wayland_client::delegate_noop!(SubsurfaceClient: client_wl_compositor::WlCompositor);
wayland_client::delegate_noop!(SubsurfaceClient: client_wl_subcompositor::WlSubcompositor);
wayland_client::delegate_noop!(SubsurfaceClient: ignore client_wl_surface::WlSurface);
wayland_client::delegate_noop!(SubsurfaceClient: ignore client_wl_subsurface::WlSubsurface);
wayland_client::delegate_noop!(SubsurfaceClient: ignore client_wl_callback::WlCallback);

struct ThreadCountingAllocator;

thread_local! {
    static TRACK_ALLOCATIONS: Cell<bool> = const { Cell::new(false) };
    static ALLOCATION_COUNT: Cell<usize> = const { Cell::new(0) };
}

unsafe impl GlobalAlloc for ThreadCountingAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        if TRACK_ALLOCATIONS
            .try_with(|tracking| tracking.get())
            .unwrap_or(false)
        {
            ALLOCATION_COUNT.with(|count| count.set(count.get() + 1));
        }
        unsafe { System.alloc(layout) }
    }

    unsafe fn dealloc(&self, pointer: *mut u8, layout: Layout) {
        unsafe { System.dealloc(pointer, layout) }
    }
}

#[global_allocator]
static ALLOCATOR: ThreadCountingAllocator = ThreadCountingAllocator;

struct FixturePath(PathBuf);

impl Drop for FixturePath {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}

fn create_fixture() -> (FixturePath, std::fs::File) {
    static NEXT_FIXTURE: AtomicU64 = AtomicU64::new(0);
    let nonce = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("system clock before Unix epoch")
        .as_nanos();
    let path = std::env::temp_dir().join(format!(
        "archphene-shm-allocation-{}-{nonce}-{}.bin",
        std::process::id(),
        NEXT_FIXTURE.fetch_add(1, Ordering::Relaxed),
    ));
    let file = OpenOptions::new()
        .create_new(true)
        .read(true)
        .write(true)
        .open(&path)
        .expect("create unique SHM allocation fixture");
    (FixturePath(path), file)
}

fn count_allocations(operation: impl FnOnce()) -> usize {
    ALLOCATION_COUNT.with(|count| count.set(0));
    TRACK_ALLOCATIONS.with(|tracking| tracking.set(true));
    operation();
    TRACK_ALLOCATIONS.with(|tracking| tracking.set(false));
    ALLOCATION_COUNT.with(Cell::get)
}

fn collect_commit_damage(
    surface: &mut SurfaceState,
    frame: &Arc<CommittedFrame>,
    surface_rectangle: RegionRectangle,
    buffer_rectangle: RegionRectangle,
) -> usize {
    let SurfaceState {
        pending_surface_damage,
        pending_surface_damage_full,
        ..
    } = surface;
    push_bounded_damage(
        pending_surface_damage,
        pending_surface_damage_full,
        surface_rectangle,
    );
    let SurfaceState {
        pending_buffer_damage,
        pending_buffer_damage_full,
        ..
    } = surface;
    push_bounded_damage(
        pending_buffer_damage,
        pending_buffer_damage_full,
        buffer_rectangle,
    );
    let (surface_damage, buffer_damage, overflow) = take_pending_damage(surface);
    let mut damage = std::mem::take(&mut surface.commit_damage_scratch);
    damage_for_commit_into(
        &mut damage,
        &surface_damage,
        &buffer_damage,
        Some(frame),
        BufferTransform::Normal,
        1,
        overflow,
    );
    let count = damage.len();
    restore_pending_damage_buffers(surface, surface_damage, buffer_damage);
    restore_commit_damage_scratch(surface, damage);
    count
}

#[test]
fn warmed_retained_shm_damage_does_not_allocate() {
    let (_fixture, file) = create_fixture();
    file.write_all_at(&[1, 0, 0, 0, 2, 0, 0, 0], 0)
        .expect("write SHM allocation fixture");
    let buffer = ShmBufferInner {
        pool: Arc::new(Mutex::new(ShmPoolInner { file, size: 8 })),
        patch: Mutex::new(Vec::new()),
        offset: 0,
        width: 2,
        height: 1,
        stride: 8,
        format: wl_shm::Format::Xrgb8888,
    };
    let previous = Arc::new(CommittedFrame::new(
        2,
        1,
        wl_shm::Format::Xrgb8888,
        vec![1, 0, 0, 0, 2, 0, 0, 0],
        None,
    ));
    let damage = [RegionRectangle::new(1, 0, 1, 1).expect("damage")];
    let surface_damage = RegionRectangle::new(0, 0, 1, 1).expect("surface damage");
    let buffer_damage = RegionRectangle::new(1, 0, 1, 1).expect("buffer damage");
    let mut surface = SurfaceState::default();
    let snapshot_state = || ShmSnapshotState {
        surface_damage: &[],
        buffer_damage: &damage,
        transform: BufferTransform::Normal,
        scale: 1,
        viewport_active: false,
        allow_in_place: true,
        force_full_damage: false,
    };

    buffer
        .snapshot(Some(&previous), snapshot_state())
        .expect("warm retained SHM patch");
    assert_eq!(
        collect_commit_damage(&mut surface, &previous, surface_damage, buffer_damage),
        2,
    );
    buffer
        .pool
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .file
        .write_all_at(&[9, 0, 0, 0], 0)
        .expect("diverge undamaged SHM pixel");
    let visible = Arc::new(CommittedFrame::new(
        2,
        1,
        wl_shm::Format::Xrgb8888,
        vec![1, 0, 0, 0, 2, 0, 0, 0],
        None,
    ));
    let detached = buffer
        .snapshot(
            Some(&visible),
            ShmSnapshotState {
                surface_damage: &[],
                buffer_damage: &damage,
                transform: BufferTransform::Normal,
                scale: 1,
                viewport_active: false,
                allow_in_place: false,
                force_full_damage: false,
            },
        )
        .expect("warm detached synchronized snapshot");
    let detached_original = Arc::clone(&detached);
    let transformed = Arc::new(CommittedFrame::new(
        2,
        1,
        wl_shm::Format::Xrgb8888,
        vec![0; 8],
        Some(detached),
    ));
    let viewport = Arc::new(CommittedFrame::new(
        2,
        1,
        wl_shm::Format::Xrgb8888,
        vec![0; 8],
        Some(transformed),
    ));
    let mut synchronized_surface = SurfaceState {
        committed_frame: Some(Arc::clone(&visible)),
        cached_frame: Some(Some(viewport)),
        ..SurfaceState::default()
    };
    assert!(surface_snapshot_allows_in_place(
        &synchronized_surface,
        true,
        false,
    ));

    let allocations = count_allocations(|| {
        for iteration in 0..1_000 {
            let value = if iteration & 1 == 0 { 3 } else { 4 };
            buffer
                .pool
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .file
                .write_all_at(&[value, 0, 0, 0], 4)
                .expect("update SHM allocation fixture");
            let frame = buffer
                .snapshot(Some(&previous), snapshot_state())
                .expect("retained SHM patch");
            assert!(Arc::ptr_eq(&frame, &previous));
            let pixels = frame.pixels();
            assert_eq!(pixels[0], 1);
            assert_eq!(pixels[4], value);
            drop(pixels);
            let cached = synchronized_surface
                .cached_frame
                .as_ref()
                .and_then(Option::as_ref)
                .cloned()
                .expect("detached synchronized cache");
            let synchronized = buffer
                .snapshot(
                    Some(&cached),
                    ShmSnapshotState {
                        surface_damage: &[],
                        buffer_damage: &damage,
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
                .expect("reuse detached synchronized snapshot");
            assert!(Arc::ptr_eq(&synchronized, &detached_original));
            assert_eq!(synchronized.pixels()[4], value);
            assert_eq!(visible.pixels()[4], 2);
            assert_eq!(
                collect_commit_damage(&mut surface, &previous, surface_damage, buffer_damage),
                2,
            );
        }
    });

    assert_eq!(allocations, 0);
    synchronized_surface.committed_frame = synchronized_surface
        .cached_frame
        .take()
        .expect("publish synchronized cache");
    synchronized_surface.cached_frame = Some(synchronized_surface.committed_frame.clone());
    assert!(!surface_snapshot_allows_in_place(
        &synchronized_surface,
        true,
        false,
    ));
}

#[test]
fn warmed_hardware_buffer_damage_conversion_does_not_allocate() {
    let frame = CommittedFrame::new(
        4,
        2,
        wl_shm::Format::Xrgb8888,
        vec![
            1, 2, 3, 0, 4, 5, 6, 0, 7, 8, 9, 0, 10, 11, 12, 0, 13, 14, 15, 0, 16,
            17, 18, 0, 19, 20, 21, 0, 22, 23, 24, 0,
        ],
        None,
    );
    let damage = PresentationCopyDamage::Region(
        RegionRectangle::new(1, 0, 2, 2).expect("bounded conversion damage"),
    );
    let mut destination = vec![0xaa; 40];

    assert_eq!(
        copy_frame_to_rgba_buffer(&frame, 4, 2, 20, &mut destination, damage),
        0,
    );
    destination.fill(0xaa);
    let allocations = count_allocations(|| {
        for _ in 0..1_000 {
            assert_eq!(
                copy_frame_to_rgba_buffer(&frame, 4, 2, 20, &mut destination, damage),
                0,
            );
        }
    });

    assert_eq!(allocations, 0);
    assert_eq!(&destination[0..4], &[0xaa; 4]);
    assert_eq!(&destination[4..12], &[6, 5, 4, 255, 9, 8, 7, 255]);
    assert_eq!(&destination[12..20], &[0xaa; 8]);
    assert_eq!(&destination[20..24], &[0xaa; 4]);
    assert_eq!(&destination[24..32], &[18, 17, 16, 255, 21, 20, 19, 255]);
    assert_eq!(&destination[32..40], &[0xaa; 8]);
}

#[test]
fn warmed_synchronized_subsurface_release_does_not_allocate() {
    let socket = std::env::temp_dir().join(format!(
        "archphene-subsurface-allocation-{}-{}.sock",
        std::process::id(),
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("system clock before Unix epoch")
            .as_nanos(),
    ));
    let allocations = Arc::new(AtomicUsize::new(usize::MAX));
    let server_allocations = Arc::clone(&allocations);
    let server_socket = socket.clone();
    let (ready_sender, ready_receiver) = mpsc::sync_channel(1);
    let (parent_release_sender, parent_release_receiver) = mpsc::sync_channel(1);
    let server = std::thread::spawn(move || {
        let mut core = CompositorCore::new().expect("Wayland display");
        core.bind_socket(&server_socket).expect("bind socket");
        ready_sender.send(()).expect("publish bound socket");
        let setup_deadline = Instant::now() + Duration::from_secs(5);
        while core.state.presentation_callbacks.len() != 1 {
            assert!(
                Instant::now() < setup_deadline,
                "timed out waiting for parent-commit release"
            );
            core.dispatch_once().expect("dispatch subsurface client");
        }
        parent_release_sender
            .send(())
            .expect("publish parent-commit release");
        while core.state.presentation_callbacks.len() != 2 {
            assert!(
                Instant::now() < setup_deadline,
                "timed out waiting for desync release"
            );
            core.dispatch_once().expect("dispatch desync request");
        }
        let parent = core.state.surfaces[0].clone();
        let child = core.state.surfaces[1].clone();
        core.state.root_surface = Some(parent.clone());
        let damage = RegionRectangle::new(1, 2, 3, 4).expect("cached damage");
        assert!(
            child
                .data::<SurfaceData>()
                .expect("child surface data")
                .inner
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .cached_callbacks
                .is_empty(),
            "protocol releases must drain cached callbacks"
        );
        let callback = core
            .state
            .presentation_callbacks
            .pop()
            .expect("recyclable released callback");
        let callback_capacity = core.state.presentation_callbacks.capacity();
        assert_eq!(core.present_frame(1), 1);
        assert_eq!(
            core.state.presentation_callbacks.capacity(),
            callback_capacity
        );
        {
            let mut child_state = child
                .data::<SurfaceData>()
                .expect("child surface data")
                .inner
                .lock()
                .unwrap_or_else(|error| error.into_inner());
            child_state.cached_callbacks.push(callback);
        }
        let cache_damage = || {
            child
                .data::<SurfaceData>()
                .expect("child surface data")
                .inner
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .cached_damage
                .push(damage);
        };

        cache_damage();
        apply_cached_subsurface_children(&mut core.state, &parent);
        assert_eq!(core.state.presentation_damage.as_slice(), &[damage]);
        assert_eq!(core.state.presentation_callbacks.len(), 1);
        core.state.presentation_damage.clear();
        child
            .data::<SurfaceData>()
            .expect("child surface data")
            .inner
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .cached_callbacks
            .push(core.state.presentation_callbacks.pop().expect("callback"));

        let count = count_allocations(|| {
            for _ in 0..1_000 {
                cache_damage();
                apply_cached_subsurface_children(&mut core.state, &parent);
                assert_eq!(core.state.presentation_damage.as_slice(), &[damage]);
                assert_eq!(core.state.presentation_callbacks.len(), 1);
                core.state.presentation_damage.clear();
                child
                    .data::<SurfaceData>()
                    .expect("child surface data")
                    .inner
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                .cached_callbacks
                .push(core.state.presentation_callbacks.pop().expect("callback"));
            }
        });
        cache_damage();
        apply_cached_subsurface_children(&mut core.state, &parent);
        let callback_capacity = core.state.presentation_callbacks.capacity();
        assert_eq!(core.present_frame(2), 1);
        assert_eq!(
            core.state.presentation_callbacks.capacity(),
            callback_capacity
        );
        server_allocations.store(count, Ordering::Release);
    });

    ready_receiver
        .recv_timeout(Duration::from_secs(5))
        .expect("server socket readiness");
    let connection = Connection::from_socket(UnixStream::connect(&socket).expect("connect client"))
        .expect("client connection");
    let (globals, events) =
        registry_queue_init::<SubsurfaceClient>(&connection).expect("registry");
    let queue = events.handle();
    let compositor = globals
        .bind::<client_wl_compositor::WlCompositor, _, _>(&queue, 1..=6, ())
        .expect("wl_compositor");
    let subcompositor = globals
        .bind::<client_wl_subcompositor::WlSubcompositor, _, _>(&queue, 1..=1, ())
        .expect("wl_subcompositor");
    let parent = compositor.create_surface(&queue, ());
    let child = compositor.create_surface(&queue, ());
    let subsurface = subcompositor.get_subsurface(&child, &parent, &queue, ());
    let _parent_release_callback = child.frame(&queue, ());
    child.damage(1, 2, 3, 4);
    child.commit();
    parent.commit();
    connection.flush().expect("flush parent-commit release");
    parent_release_receiver
        .recv_timeout(Duration::from_secs(5))
        .expect("parent-commit callback release");
    let _desync_release_callback = child.frame(&queue, ());
    child.commit();
    subsurface.set_desync();
    connection.flush().expect("flush desync release");

    server.join().expect("server thread");
    assert_eq!(allocations.load(Ordering::Acquire), 0);
    assert!(!socket.exists());
}
