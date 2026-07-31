use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::Cell;
use std::fs::OpenOptions;
use std::os::unix::fs::FileExt;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

use wayland_server::protocol::wl_shm;

use super::{
    BufferTransform, CommittedFrame, RegionRectangle, ShmBufferInner, ShmPoolInner,
    ShmSnapshotState,
};

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
    let snapshot_state = || ShmSnapshotState {
        surface_damage: &[],
        buffer_damage: &damage,
        transform: BufferTransform::Normal,
        scale: 1,
        viewport_active: false,
        allow_in_place: true,
    };

    buffer
        .snapshot(Some(&previous), snapshot_state())
        .expect("warm retained SHM patch");
    buffer
        .pool
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .file
        .write_all_at(&[9, 0, 0, 0], 0)
        .expect("diverge undamaged SHM pixel");

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
        }
    });

    assert_eq!(allocations, 0);
}
