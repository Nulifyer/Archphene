use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicUsize, Ordering};

use archphene_jobs::{JobOperation, JobState, PackageJobs};

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
fn warmed_in_memory_job_updates_do_not_allocate() {
    let mut jobs = PackageJobs::new();
    let job = jobs
        .begin(JobOperation::Install, "extra", "dotnet-sdk", 1)
        .expect("warm job");
    jobs.update(job.id, JobState::Queued, 0, 0, "Queued", 2)
        .expect("warm update");

    let before = ALLOCATION_COUNT.load(Ordering::SeqCst);
    for now in 3..1_003 {
        jobs.update(job.id, JobState::Queued, 0, 0, "Queued", now)
            .expect("steady job update");
        assert!(jobs.get(job.id).is_some());
    }
    let after = ALLOCATION_COUNT.load(Ordering::SeqCst);
    assert_eq!(after, before);
}
