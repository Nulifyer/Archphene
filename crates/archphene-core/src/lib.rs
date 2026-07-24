#![forbid(unsafe_code)]

pub const PROTOCOL_VERSION: u32 = 1;
pub const EVENT_SIZE: usize = 24;
pub const INPUT_QUEUE_CAPACITY: usize = 256;
pub const SNAPSHOT_SIZE: usize = 64;

const SNAPSHOT_MAGIC: u32 = u32::from_le_bytes(*b"ARPH");
const MAX_EVENT_KIND: u32 = 16;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u32)]
pub enum Lifecycle {
    Starting = 1,
    Running = 2,
    Suspended = 3,
    Stopping = 4,
    Stopped = 5,
    Faulted = 6,
}

impl Lifecycle {
    pub fn from_raw(value: u32) -> Option<Self> {
        match value {
            1 => Some(Self::Starting),
            2 => Some(Self::Running),
            3 => Some(Self::Suspended),
            4 => Some(Self::Stopping),
            5 => Some(Self::Stopped),
            6 => Some(Self::Faulted),
            _ => None,
        }
    }

    fn can_transition_to(self, next: Self) -> bool {
        matches!(
            (self, next),
            (
                Self::Starting,
                Self::Running | Self::Stopping | Self::Faulted
            ) | (
                Self::Running,
                Self::Suspended | Self::Stopping | Self::Faulted
            ) | (
                Self::Suspended,
                Self::Running | Self::Stopping | Self::Faulted
            ) | (Self::Stopping, Self::Stopped | Self::Faulted)
        ) || self == next
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct InputEvent {
    pub kind: u32,
    pub flags: u32,
    pub time_nanos: u64,
    pub argument_0: i32,
    pub argument_1: i32,
}

impl InputEvent {
    fn decode(bytes: &[u8]) -> Option<Self> {
        if bytes.len() != EVENT_SIZE {
            return None;
        }
        let kind = u32::from_le_bytes(bytes[0..4].try_into().ok()?);
        if kind == 0 || kind > MAX_EVENT_KIND {
            return None;
        }
        Some(Self {
            kind,
            flags: u32::from_le_bytes(bytes[4..8].try_into().ok()?),
            time_nanos: u64::from_le_bytes(bytes[8..16].try_into().ok()?),
            argument_0: i32::from_le_bytes(bytes[16..20].try_into().ok()?),
            argument_1: i32::from_le_bytes(bytes[20..24].try_into().ok()?),
        })
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum RuntimeError {
    InvalidLifecycle,
    InvalidEventBatch,
    QueueFull,
    SnapshotTooSmall,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct RuntimeSnapshot {
    pub generation: u64,
    pub lifecycle: Lifecycle,
    pub queue_depth: u32,
    pub accepted_events: u64,
    pub rejected_events: u64,
    pub drained_events: u64,
    pub status_flags: u32,
}

pub struct Runtime {
    generation: u64,
    lifecycle: Lifecycle,
    input_queue: [Option<InputEvent>; INPUT_QUEUE_CAPACITY],
    input_read_index: usize,
    input_length: usize,
    accepted_events: u64,
    rejected_events: u64,
    drained_events: u64,
    status_flags: u32,
}

impl Runtime {
    pub fn new(generation: u64) -> Self {
        Self {
            generation,
            lifecycle: Lifecycle::Starting,
            input_queue: [None; INPUT_QUEUE_CAPACITY],
            input_read_index: 0,
            input_length: 0,
            accepted_events: 0,
            rejected_events: 0,
            drained_events: 0,
            status_flags: 0,
        }
    }

    pub fn transition(&mut self, next: Lifecycle) -> Result<(), RuntimeError> {
        if !self.lifecycle.can_transition_to(next) {
            return Err(RuntimeError::InvalidLifecycle);
        }
        self.lifecycle = next;
        Ok(())
    }

    pub fn submit_encoded_events(&mut self, bytes: &[u8]) -> Result<usize, RuntimeError> {
        if bytes.is_empty() || !bytes.len().is_multiple_of(EVENT_SIZE) {
            self.rejected_events = self.rejected_events.saturating_add(1);
            return Err(RuntimeError::InvalidEventBatch);
        }

        let event_count = bytes.len() / EVENT_SIZE;
        if event_count > INPUT_QUEUE_CAPACITY - self.input_length {
            self.rejected_events = self
                .rejected_events
                .saturating_add(u64::try_from(event_count).unwrap_or(u64::MAX));
            return Err(RuntimeError::QueueFull);
        }

        for chunk in bytes.chunks_exact(EVENT_SIZE) {
            if InputEvent::decode(chunk).is_none() {
                self.rejected_events = self
                    .rejected_events
                    .saturating_add(u64::try_from(event_count).unwrap_or(u64::MAX));
                return Err(RuntimeError::InvalidEventBatch);
            }
        }

        for chunk in bytes.chunks_exact(EVENT_SIZE) {
            let Some(event) = InputEvent::decode(chunk) else {
                return Err(RuntimeError::InvalidEventBatch);
            };
            let write_index = (self.input_read_index + self.input_length) % INPUT_QUEUE_CAPACITY;
            self.input_queue[write_index] = Some(event);
            self.input_length += 1;
        }
        self.accepted_events = self
            .accepted_events
            .saturating_add(u64::try_from(event_count).unwrap_or(u64::MAX));
        Ok(event_count)
    }

    pub fn drain_input(&mut self, maximum: usize) -> usize {
        let drain_count = maximum.min(self.input_length);
        for _ in 0..drain_count {
            self.input_queue[self.input_read_index] = None;
            self.input_read_index = (self.input_read_index + 1) % INPUT_QUEUE_CAPACITY;
            self.input_length -= 1;
        }
        self.drained_events = self
            .drained_events
            .saturating_add(u64::try_from(drain_count).unwrap_or(u64::MAX));
        drain_count
    }

    pub fn snapshot(&self) -> RuntimeSnapshot {
        RuntimeSnapshot {
            generation: self.generation,
            lifecycle: self.lifecycle,
            queue_depth: u32::try_from(self.input_length).unwrap_or(u32::MAX),
            accepted_events: self.accepted_events,
            rejected_events: self.rejected_events,
            drained_events: self.drained_events,
            status_flags: self.status_flags,
        }
    }

    pub fn set_status_flags(&mut self, status_flags: u32) {
        self.status_flags = status_flags;
    }

    pub fn add_status_flags(&mut self, status_flags: u32) {
        self.status_flags |= status_flags;
    }

    pub fn remove_status_flags(&mut self, status_flags: u32) {
        self.status_flags &= !status_flags;
    }

    pub fn write_snapshot(&self, output: &mut [u8]) -> Result<usize, RuntimeError> {
        if output.len() < SNAPSHOT_SIZE {
            return Err(RuntimeError::SnapshotTooSmall);
        }
        output[..SNAPSHOT_SIZE].fill(0);
        let snapshot = self.snapshot();
        write_u32(output, 0, SNAPSHOT_MAGIC);
        write_u32(output, 4, PROTOCOL_VERSION);
        write_u32(output, 8, snapshot.lifecycle as u32);
        write_u32(output, 12, snapshot.queue_depth);
        write_u64(output, 16, snapshot.generation);
        write_u64(output, 24, snapshot.accepted_events);
        write_u64(output, 32, snapshot.rejected_events);
        write_u64(output, 40, snapshot.drained_events);
        write_u32(output, 48, snapshot.status_flags);
        Ok(SNAPSHOT_SIZE)
    }
}

fn write_u32(output: &mut [u8], offset: usize, value: u32) {
    output[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
}

fn write_u64(output: &mut [u8], offset: usize, value: u64) {
    output[offset..offset + 8].copy_from_slice(&value.to_le_bytes());
}

#[cfg(test)]
mod tests {
    use super::*;

    fn event(kind: u32, timestamp: u64) -> [u8; EVENT_SIZE] {
        let mut bytes = [0_u8; EVENT_SIZE];
        bytes[0..4].copy_from_slice(&kind.to_le_bytes());
        bytes[8..16].copy_from_slice(&timestamp.to_le_bytes());
        bytes
    }

    #[test]
    fn lifecycle_rejects_invalid_backwards_transitions() {
        let mut runtime = Runtime::new(7);
        assert_eq!(runtime.transition(Lifecycle::Running), Ok(()));
        assert_eq!(
            runtime.transition(Lifecycle::Starting),
            Err(RuntimeError::InvalidLifecycle)
        );
        assert_eq!(runtime.snapshot().lifecycle, Lifecycle::Running);
    }

    #[test]
    fn event_batches_are_atomic_and_bounded() {
        let mut runtime = Runtime::new(1);
        let valid = event(1, 22);
        for _ in 0..INPUT_QUEUE_CAPACITY {
            assert_eq!(runtime.submit_encoded_events(&valid), Ok(1));
        }
        assert_eq!(
            runtime.submit_encoded_events(&valid),
            Err(RuntimeError::QueueFull)
        );
        assert_eq!(
            runtime.snapshot().queue_depth as usize,
            INPUT_QUEUE_CAPACITY
        );
        assert_eq!(
            runtime.drain_input(INPUT_QUEUE_CAPACITY),
            INPUT_QUEUE_CAPACITY
        );
        assert_eq!(runtime.snapshot().queue_depth, 0);
    }

    #[test]
    fn invalid_event_does_not_partially_enter_queue() {
        let mut runtime = Runtime::new(1);
        let mut batch = [0_u8; EVENT_SIZE * 2];
        batch[..EVENT_SIZE].copy_from_slice(&event(1, 1));
        batch[EVENT_SIZE..].copy_from_slice(&event(MAX_EVENT_KIND + 1, 2));
        assert_eq!(
            runtime.submit_encoded_events(&batch),
            Err(RuntimeError::InvalidEventBatch)
        );
        assert_eq!(runtime.snapshot().queue_depth, 0);
    }

    #[test]
    fn snapshot_has_stable_little_endian_layout() {
        let runtime = Runtime::new(0x0102_0304_0506_0708);
        let mut output = [0_u8; SNAPSHOT_SIZE];
        assert_eq!(runtime.write_snapshot(&mut output), Ok(SNAPSHOT_SIZE));
        assert_eq!(&output[0..4], b"ARPH");
        assert_eq!(
            u32::from_le_bytes(output[4..8].try_into().expect("version bytes")),
            PROTOCOL_VERSION
        );
        assert_eq!(
            u64::from_le_bytes(output[16..24].try_into().expect("generation bytes")),
            0x0102_0304_0506_0708
        );
    }
}
