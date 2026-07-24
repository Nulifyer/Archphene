use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicUsize, Ordering};

use archphene_terminal::{MAX_DAMAGE_BYTES, Terminal};

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
    let mut damage = vec![0; MAX_DAMAGE_BYTES];
    let output = b"\x1b[?1;66;67;2004h\x1b=\x1b[20h\x1b[?1049h\rprogress \
        \x1b[32;1mcomplete\x1b[38;5;196;48;2;0;95;175m!\x1b[0m \
        \x1b(0lqk\x1b(B\xe2\x98\x83\x1b[K\
        \x1b[4h\x1b[2@xx\x1b[4l\x1b[2P\x1b[3X\x1b[2L\x1b[2M\
        \x1b[2S\x1b[2T\x1b[2b\x1b[3d\x1b[7`\x1b[2E\x1b[2F\
        \x1bH\x1b[2I\x1b[2Z\x1b[g\x1b[s\x1b[u\x1bM\x1b[?1049l\
        \x1b[?1;66;67;2004l\x1b>\x1b[20l";
    terminal.feed(output);
    terminal
        .write_damage(&mut damage)
        .expect("initial terminal damage");

    let before = ALLOCATION_COUNT.load(Ordering::SeqCst);
    for _ in 0..1_000 {
        terminal.feed(output);
        assert!(terminal.write_damage(&mut damage).expect("terminal damage") > 0);
    }
    let after = ALLOCATION_COUNT.load(Ordering::SeqCst);
    assert_eq!(after, before);
}
