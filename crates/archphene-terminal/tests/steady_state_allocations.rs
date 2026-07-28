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
        \x1bH\x1b[2I\x1b[2Z\x1b[g\x1b[s\x1b[u\x1bM\
        \x1b[2;20r\x1b[?6h\x1b[2;1H\x1b[99B\x1b[99A\x1b[?6l\
        \x1b[?7lno-wrap\x1b[?7he\xcc\x81\xe7\x95\x8c\
        \xf0\x9f\x87\xba\xf0\x9f\x87\xb8\
        \xf0\x9f\x91\xa8\xe2\x80\x8d\xf0\x9f\x91\xa9\xe2\x80\x8d\
        \xf0\x9f\x91\xa7\xe2\x80\x8d\xf0\x9f\x91\xa6\x1b[?1049l\
        \r\nscrollback\x1b[3J\x1b[?1;5;66;67;2004l\x1b>\x1b[20l\x1b[!p\
        \x1b[c\x1b[>c\x1b[18t\x1b[5n\x1b[6n\x1b[?6n\
        \x1b[?5$p\x1b[?2004$p\x1b[4$p\x1b[9999$p\
        \x1b]4;25;rgb:12/34/56\x07\x1b]4;25;?\x1b\\\x1b]104;25\x07\
        \x1b[?69h\x1b[3;70s\x1b[?6hmargin-wrap-data\x1b[?69$p\x1b[?69l\
        \x1b[1 q\x1b[6 q\x1b[0 q\
        \x1b[?1000;1004;1006h\x1b[?1000$p\x1b[?1004$p\x1b[?1006$p\
        \x1b[?1002h\x1b[?1016h\x1b[?1002;1004;1016l";
    terminal.feed(output);
    terminal.consume_reply(usize::MAX);
    terminal
        .write_damage(&mut damage)
        .expect("initial terminal damage");

    let before = ALLOCATION_COUNT.load(Ordering::SeqCst);
    for _ in 0..1_000 {
        terminal.feed(output);
        terminal.consume_reply(usize::MAX);
        assert!(terminal.write_damage(&mut damage).expect("terminal damage") > 0);
    }
    let after = ALLOCATION_COUNT.load(Ordering::SeqCst);
    assert_eq!(after, before);
}
