use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicUsize, Ordering};

use archphene_core::{EVENT_SIZE, Runtime, SNAPSHOT_SIZE};

struct CountingAllocator;

static ALLOCATION_COUNT: AtomicUsize = AtomicUsize::new(0);

unsafe impl GlobalAlloc for CountingAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        ALLOCATION_COUNT.fetch_add(1, Ordering::Relaxed);
        unsafe { System.alloc(layout) }
    }

    unsafe fn dealloc(&self, pointer: *mut u8, layout: Layout) {
        unsafe { System.dealloc(pointer, layout) }
    }
}

#[global_allocator]
static ALLOCATOR: CountingAllocator = CountingAllocator;

#[test]
fn warmed_event_and_snapshot_path_does_not_allocate() {
    const BATCH_EVENTS: usize = 32;
    let mut runtime = Runtime::new(1);
    let mut batch = [0_u8; EVENT_SIZE * BATCH_EVENTS];
    for event in batch.chunks_exact_mut(EVENT_SIZE) {
        event[0..4].copy_from_slice(&1_u32.to_le_bytes());
    }
    let mut snapshot = [0_u8; SNAPSHOT_SIZE];

    runtime
        .submit_encoded_events(&batch)
        .expect("warm event submission");
    runtime.drain_input(BATCH_EVENTS);
    runtime
        .write_snapshot(&mut snapshot)
        .expect("warm snapshot");

    let before = ALLOCATION_COUNT.load(Ordering::SeqCst);
    for _ in 0..1_000 {
        runtime
            .submit_encoded_events(&batch)
            .expect("steady event submission");
        runtime.drain_input(BATCH_EVENTS);
        runtime
            .write_snapshot(&mut snapshot)
            .expect("steady snapshot");
    }
    let after = ALLOCATION_COUNT.load(Ordering::SeqCst);
    assert_eq!(after, before);
}
