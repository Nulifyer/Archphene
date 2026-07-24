use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicUsize, Ordering};

use archphene_terminal::Terminal;

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
fn warmed_parser_grid_and_damage_path_does_not_allocate() {
    let mut terminal = Terminal::new(24, 80).expect("terminal");
    let output = b"\rprogress \x1b[32;1mcomplete\x1b[0m \xe2\x98\x83\x1b[K";
    terminal.feed(output);
    terminal.take_dirty_rows();

    let before = ALLOCATION_COUNT.load(Ordering::SeqCst);
    for _ in 0..1_000 {
        terminal.feed(output);
        assert!(terminal.take_dirty_rows().is_some());
    }
    let after = ALLOCATION_COUNT.load(Ordering::SeqCst);
    assert_eq!(after, before);
}
