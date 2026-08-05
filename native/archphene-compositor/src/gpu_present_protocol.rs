//! Fixed private wire contract for future virpipe-backed AHB presentation.
//!
//! The byte contract is intentionally independent from vtest command payloads:
//! the helper and compositor exchange Android buffer handles and fence FDs on a
//! session-private side channel while vtest retains renderer commands. Every
//! frame carries the authenticated session, helper generation, and random
//! token so stale helpers and cross-session resources fail closed.

pub(crate) const GPU_PRESENT_FRAME_BYTES: usize = 64;
pub(crate) const MAX_PRESENT_RESOURCES: usize = 3;
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
pub(crate) const MAX_RETAINED_PRESENT_TARGETS: usize = 15;
pub(crate) const MAX_PRESENT_DIMENSION: u32 = 8192;
pub(crate) const MAX_PRESENT_PIXELS: u64 = 33_554_432;
pub(crate) const MAX_PRESENT_RESOURCE_BYTES: u64 = MAX_PRESENT_PIXELS * 4;
pub(crate) const MAX_PRESENT_TOTAL_BYTES: u64 =
    MAX_PRESENT_RESOURCE_BYTES * MAX_PRESENT_RESOURCES as u64;

const MAGIC: &[u8; 4] = b"APHB";
const VERSION: u16 = 1;
const FORMAT_RGBA8888: u32 = 1;
const FLAG_HANDLE: u16 = 1;
const FLAG_FENCE: u16 = 1 << 1;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct GpuPresentScope {
    pub(crate) session_id: u32,
    pub(crate) helper_generation: u32,
    pub(crate) token: [u8; 16],
}

impl GpuPresentScope {
    pub(crate) fn valid(self) -> bool {
        self.session_id != 0
            && self.helper_generation != 0
            && self.token.iter().any(|byte| *byte != 0)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct PresentResource {
    pub(crate) resource_id: u32,
    pub(crate) slot: u16,
    pub(crate) width: u32,
    pub(crate) height: u32,
    pub(crate) estimated_bytes: u64,
}

impl PresentResource {
    fn valid(self) -> bool {
        self.resource_id != 0
            && usize::from(self.slot) < MAX_PRESENT_RESOURCES
            && (1..=MAX_PRESENT_DIMENSION).contains(&self.width)
            && (1..=MAX_PRESENT_DIMENSION).contains(&self.height)
            && u64::from(self.width) * u64::from(self.height) <= MAX_PRESENT_PIXELS
            && self.estimated_bytes >= u64::from(self.width) * u64::from(self.height) * 4
            && self.estimated_bytes <= MAX_PRESENT_RESOURCE_BYTES
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum GpuPresentMessage {
    Hello,
    Resource(PresentResource),
    Present {
        resource_id: u32,
        fence_sequence: u64,
    },
    Release {
        resource_id: u32,
        fence_sequence: u64,
    },
    DropResource {
        resource_id: u32,
    },
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum GpuPresentProtocolError {
    InvalidScope,
    InvalidFrame,
    InvalidResource,
    DuplicateResource,
    ResourceLimit,
    UnknownResource,
    StaleFence,
    OutstandingFence,
}

pub(crate) fn encode_gpu_present(
    scope: GpuPresentScope,
    message: GpuPresentMessage,
) -> Result<[u8; GPU_PRESENT_FRAME_BYTES], GpuPresentProtocolError> {
    if !scope.valid() {
        return Err(GpuPresentProtocolError::InvalidScope);
    }
    let mut frame = [0_u8; GPU_PRESENT_FRAME_BYTES];
    frame[..4].copy_from_slice(MAGIC);
    put_u16(&mut frame, 4, VERSION);
    put_u32(&mut frame, 8, scope.session_id);
    put_u32(&mut frame, 12, scope.helper_generation);
    frame[16..32].copy_from_slice(&scope.token);
    match message {
        GpuPresentMessage::Hello => put_u16(&mut frame, 6, 1),
        GpuPresentMessage::Resource(resource) => {
            if !resource.valid() {
                return Err(GpuPresentProtocolError::InvalidResource);
            }
            put_u16(&mut frame, 6, 2);
            put_u32(&mut frame, 32, resource.resource_id);
            put_u16(&mut frame, 36, resource.slot);
            put_u16(&mut frame, 38, FLAG_HANDLE);
            put_u32(&mut frame, 40, resource.width);
            put_u32(&mut frame, 44, resource.height);
            put_u32(&mut frame, 48, FORMAT_RGBA8888);
            put_u64(&mut frame, 56, resource.estimated_bytes);
        }
        GpuPresentMessage::Present {
            resource_id,
            fence_sequence,
        } if resource_id != 0 && fence_sequence != 0 => {
            put_u16(&mut frame, 6, 3);
            put_u32(&mut frame, 32, resource_id);
            put_u16(&mut frame, 38, FLAG_FENCE);
            put_u64(&mut frame, 48, fence_sequence);
        }
        GpuPresentMessage::Release {
            resource_id,
            fence_sequence,
        } if resource_id != 0 && fence_sequence != 0 => {
            put_u16(&mut frame, 6, 4);
            put_u32(&mut frame, 32, resource_id);
            put_u16(&mut frame, 38, FLAG_FENCE);
            put_u64(&mut frame, 48, fence_sequence);
        }
        GpuPresentMessage::DropResource { resource_id } if resource_id != 0 => {
            put_u16(&mut frame, 6, 5);
            put_u32(&mut frame, 32, resource_id);
        }
        _ => return Err(GpuPresentProtocolError::InvalidFrame),
    }
    Ok(frame)
}

pub(crate) fn decode_gpu_present(
    expected: GpuPresentScope,
    frame: &[u8],
) -> Result<GpuPresentMessage, GpuPresentProtocolError> {
    if !expected.valid() {
        return Err(GpuPresentProtocolError::InvalidScope);
    }
    if frame.len() != GPU_PRESENT_FRAME_BYTES
        || &frame[..4] != MAGIC
        || get_u16(frame, 4) != VERSION
        || get_u32(frame, 8) != expected.session_id
        || get_u32(frame, 12) != expected.helper_generation
        || !token_matches(&frame[16..32], &expected.token)
    {
        return Err(GpuPresentProtocolError::InvalidFrame);
    }
    let resource_id = get_u32(frame, 32);
    match get_u16(frame, 6) {
        1 if frame[32..].iter().all(|byte| *byte == 0) => Ok(GpuPresentMessage::Hello),
        2 if get_u16(frame, 38) == FLAG_HANDLE
            && get_u32(frame, 48) == FORMAT_RGBA8888
            && frame[52..56].iter().all(|byte| *byte == 0) =>
        {
            let resource = PresentResource {
                resource_id,
                slot: get_u16(frame, 36),
                width: get_u32(frame, 40),
                height: get_u32(frame, 44),
                estimated_bytes: get_u64(frame, 56),
            };
            resource
                .valid()
                .then_some(GpuPresentMessage::Resource(resource))
                .ok_or(GpuPresentProtocolError::InvalidResource)
        }
        3 if resource_id != 0
            && get_u16(frame, 38) == FLAG_FENCE
            && get_u64(frame, 48) != 0
            && frame[36..38].iter().all(|byte| *byte == 0)
            && frame[40..48].iter().all(|byte| *byte == 0)
            && frame[56..].iter().all(|byte| *byte == 0) =>
        {
            Ok(GpuPresentMessage::Present {
                resource_id,
                fence_sequence: get_u64(frame, 48),
            })
        }
        4 if resource_id != 0
            && get_u16(frame, 38) == FLAG_FENCE
            && get_u64(frame, 48) != 0
            && frame[36..38].iter().all(|byte| *byte == 0)
            && frame[40..48].iter().all(|byte| *byte == 0)
            && frame[56..].iter().all(|byte| *byte == 0) =>
        {
            Ok(GpuPresentMessage::Release {
                resource_id,
                fence_sequence: get_u64(frame, 48),
            })
        }
        5 if resource_id != 0 && frame[36..].iter().all(|byte| *byte == 0) => {
            Ok(GpuPresentMessage::DropResource { resource_id })
        }
        _ => Err(GpuPresentProtocolError::InvalidFrame),
    }
}

fn token_matches(candidate: &[u8], expected: &[u8; 16]) -> bool {
    candidate.len() == expected.len()
        && candidate
            .iter()
            .zip(expected)
            .fold(0_u8, |difference, (left, right)| {
                difference | (left ^ right)
            })
            == 0
}

#[derive(Clone)]
pub(crate) struct GpuPresentRegistry {
    scope: GpuPresentScope,
    resources: [Option<PresentResource>; MAX_PRESENT_RESOURCES],
    last_fence: [u64; MAX_PRESENT_RESOURCES],
    last_release: [u64; MAX_PRESENT_RESOURCES],
    total_bytes: u64,
    hello_received: bool,
}

impl GpuPresentRegistry {
    pub(crate) fn new(scope: GpuPresentScope) -> Result<Self, GpuPresentProtocolError> {
        if !scope.valid() {
            return Err(GpuPresentProtocolError::InvalidScope);
        }
        Ok(Self {
            scope,
            resources: [None; MAX_PRESENT_RESOURCES],
            last_fence: [0; MAX_PRESENT_RESOURCES],
            last_release: [0; MAX_PRESENT_RESOURCES],
            total_bytes: 0,
            hello_received: false,
        })
    }

    pub(crate) fn apply_frame(
        &mut self,
        frame: &[u8],
    ) -> Result<GpuPresentMessage, GpuPresentProtocolError> {
        let message = decode_gpu_present(self.scope, frame)?;
        if message == GpuPresentMessage::Hello {
            if self.hello_received {
                return Err(GpuPresentProtocolError::InvalidFrame);
            }
            self.hello_received = true;
            return Ok(message);
        }
        if !self.hello_received {
            return Err(GpuPresentProtocolError::InvalidFrame);
        }
        match message {
            GpuPresentMessage::Hello => unreachable!("hello handled before state dispatch"),
            GpuPresentMessage::Resource(resource) => {
                if self.resources.iter().flatten().any(|known| {
                    known.resource_id == resource.resource_id || known.slot == resource.slot
                }) {
                    return Err(GpuPresentProtocolError::DuplicateResource);
                }
                let projected = self.total_bytes.saturating_add(resource.estimated_bytes);
                if projected > MAX_PRESENT_TOTAL_BYTES {
                    return Err(GpuPresentProtocolError::ResourceLimit);
                }
                let Some(index) = self.resources.iter().position(Option::is_none) else {
                    return Err(GpuPresentProtocolError::ResourceLimit);
                };
                self.resources[index] = Some(resource);
                self.total_bytes = projected;
            }
            GpuPresentMessage::Present {
                resource_id,
                fence_sequence,
            } => {
                let Some(index) = self.resource_index(resource_id) else {
                    return Err(GpuPresentProtocolError::UnknownResource);
                };
                if fence_sequence <= self.last_fence[index] {
                    return Err(GpuPresentProtocolError::StaleFence);
                }
                if self.last_fence[index] != self.last_release[index] {
                    return Err(GpuPresentProtocolError::OutstandingFence);
                }
                self.last_fence[index] = fence_sequence;
            }
            GpuPresentMessage::Release {
                resource_id,
                fence_sequence,
            } => {
                let Some(index) = self.resource_index(resource_id) else {
                    return Err(GpuPresentProtocolError::UnknownResource);
                };
                if fence_sequence != self.last_fence[index]
                    || fence_sequence <= self.last_release[index]
                {
                    return Err(GpuPresentProtocolError::StaleFence);
                }
                self.last_release[index] = fence_sequence;
            }
            GpuPresentMessage::DropResource { resource_id } => {
                let Some(index) = self.resource_index(resource_id) else {
                    return Err(GpuPresentProtocolError::UnknownResource);
                };
                if self.last_fence[index] != self.last_release[index] {
                    return Err(GpuPresentProtocolError::OutstandingFence);
                }
                let resource = self.resources[index].take().expect("located resource");
                self.last_fence[index] = 0;
                self.last_release[index] = 0;
                self.total_bytes = self.total_bytes.saturating_sub(resource.estimated_bytes);
            }
        }
        Ok(message)
    }

    fn resource_index(&self, resource_id: u32) -> Option<usize> {
        self.resources.iter().position(|resource| {
            resource.is_some_and(|resource| resource.resource_id == resource_id)
        })
    }
}

fn put_u16(frame: &mut [u8], offset: usize, value: u16) {
    frame[offset..offset + 2].copy_from_slice(&value.to_le_bytes());
}

fn put_u32(frame: &mut [u8], offset: usize, value: u32) {
    frame[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
}

fn put_u64(frame: &mut [u8], offset: usize, value: u64) {
    frame[offset..offset + 8].copy_from_slice(&value.to_le_bytes());
}

fn get_u16(frame: &[u8], offset: usize) -> u16 {
    u16::from_le_bytes(frame[offset..offset + 2].try_into().expect("fixed frame"))
}

fn get_u32(frame: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes(frame[offset..offset + 4].try_into().expect("fixed frame"))
}

fn get_u64(frame: &[u8], offset: usize) -> u64 {
    u64::from_le_bytes(frame[offset..offset + 8].try_into().expect("fixed frame"))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn scope() -> GpuPresentScope {
        GpuPresentScope {
            session_id: 7,
            helper_generation: 3,
            token: [0x5a; 16],
        }
    }

    fn resource(id: u32, slot: u16) -> PresentResource {
        PresentResource {
            resource_id: id,
            slot,
            width: 1920,
            height: 1080,
            estimated_bytes: 1920 * 1080 * 4,
        }
    }

    #[test]
    fn round_trips_fixed_scoped_messages() {
        for message in [
            GpuPresentMessage::Hello,
            GpuPresentMessage::Resource(resource(11, 0)),
            GpuPresentMessage::Present {
                resource_id: 11,
                fence_sequence: 0x1_0000_0002,
            },
            GpuPresentMessage::Release {
                resource_id: 11,
                fence_sequence: 0x1_0000_0002,
            },
            GpuPresentMessage::DropResource { resource_id: 11 },
        ] {
            let frame = encode_gpu_present(scope(), message).expect("encode message");
            assert_eq!(frame.len(), GPU_PRESENT_FRAME_BYTES);
            assert_eq!(decode_gpu_present(scope(), &frame), Ok(message));
        }
    }

    #[test]
    fn rejects_cross_session_generation_token_and_trailing_fields() {
        let mut frame = encode_gpu_present(scope(), GpuPresentMessage::Hello).expect("hello");
        frame[8] ^= 1;
        assert_eq!(
            decode_gpu_present(scope(), &frame),
            Err(GpuPresentProtocolError::InvalidFrame),
        );
        frame = encode_gpu_present(scope(), GpuPresentMessage::Hello).expect("hello");
        frame[12] ^= 1;
        assert_eq!(
            decode_gpu_present(scope(), &frame),
            Err(GpuPresentProtocolError::InvalidFrame),
        );
        frame = encode_gpu_present(scope(), GpuPresentMessage::Hello).expect("hello");
        frame[16] ^= 1;
        assert_eq!(
            decode_gpu_present(scope(), &frame),
            Err(GpuPresentProtocolError::InvalidFrame),
        );
        frame = encode_gpu_present(scope(), GpuPresentMessage::Hello).expect("hello");
        frame[63] = 1;
        assert_eq!(
            decode_gpu_present(scope(), &frame),
            Err(GpuPresentProtocolError::InvalidFrame),
        );
    }

    #[test]
    fn bounds_resources_bytes_and_monotonic_fences() {
        let mut registry = GpuPresentRegistry::new(scope()).expect("registry");
        let hello = encode_gpu_present(scope(), GpuPresentMessage::Hello).expect("hello frame");
        assert_eq!(registry.apply_frame(&hello), Ok(GpuPresentMessage::Hello));
        assert_eq!(
            registry.apply_frame(&hello),
            Err(GpuPresentProtocolError::InvalidFrame)
        );
        for slot in 0..MAX_PRESENT_RESOURCES as u16 {
            let message = GpuPresentMessage::Resource(resource(u32::from(slot) + 1, slot));
            let frame = encode_gpu_present(scope(), message).expect("resource frame");
            assert_eq!(registry.apply_frame(&frame), Ok(message));
        }
        let overflow = GpuPresentMessage::Resource(resource(99, 0));
        assert_eq!(
            registry.apply_frame(&encode_gpu_present(scope(), overflow).expect("overflow frame")),
            Err(GpuPresentProtocolError::DuplicateResource),
        );
        let present = GpuPresentMessage::Present {
            resource_id: 1,
            fence_sequence: 1,
        };
        assert_eq!(
            registry.apply_frame(&encode_gpu_present(scope(), present).expect("present frame")),
            Ok(present),
        );
        assert_eq!(
            registry.apply_frame(&encode_gpu_present(scope(), present).expect("stale frame")),
            Err(GpuPresentProtocolError::StaleFence),
        );
        let next_present = GpuPresentMessage::Present {
            resource_id: 1,
            fence_sequence: 2,
        };
        assert_eq!(
            registry.apply_frame(
                &encode_gpu_present(scope(), next_present).expect("busy present frame")
            ),
            Err(GpuPresentProtocolError::OutstandingFence),
        );
        let drop_resource = GpuPresentMessage::DropResource { resource_id: 1 };
        assert_eq!(
            registry
                .apply_frame(&encode_gpu_present(scope(), drop_resource).expect("busy drop frame")),
            Err(GpuPresentProtocolError::OutstandingFence),
        );
        let release = GpuPresentMessage::Release {
            resource_id: 1,
            fence_sequence: 1,
        };
        assert_eq!(
            registry.apply_frame(&encode_gpu_present(scope(), release).expect("release frame")),
            Ok(release),
        );
        assert_eq!(
            registry.apply_frame(
                &encode_gpu_present(scope(), drop_resource).expect("drop resource frame")
            ),
            Ok(drop_resource),
        );
        let replacement = GpuPresentMessage::Resource(resource(100, 0));
        assert_eq!(
            registry.apply_frame(&encode_gpu_present(scope(), replacement).expect("replacement")),
            Ok(replacement),
        );
    }

    #[test]
    fn rejects_invalid_dimensions_and_unknown_resources() {
        let invalid = PresentResource {
            width: MAX_PRESENT_DIMENSION + 1,
            ..resource(1, 0)
        };
        assert_eq!(
            encode_gpu_present(scope(), GpuPresentMessage::Resource(invalid)),
            Err(GpuPresentProtocolError::InvalidResource),
        );
        let padded = PresentResource {
            estimated_bytes: 1920 * 1088 * 4,
            ..resource(2, 1)
        };
        assert!(encode_gpu_present(scope(), GpuPresentMessage::Resource(padded)).is_ok());
        let underestimated = PresentResource {
            estimated_bytes: 1920 * 1080 * 4 - 1,
            ..resource(3, 2)
        };
        assert_eq!(
            encode_gpu_present(scope(), GpuPresentMessage::Resource(underestimated)),
            Err(GpuPresentProtocolError::InvalidResource),
        );
        let mut registry = GpuPresentRegistry::new(scope()).expect("registry");
        let present = GpuPresentMessage::Present {
            resource_id: 404,
            fence_sequence: 1,
        };
        let present_frame = encode_gpu_present(scope(), present).expect("unknown");
        assert_eq!(
            registry.apply_frame(&present_frame),
            Err(GpuPresentProtocolError::InvalidFrame),
        );
        let hello = encode_gpu_present(scope(), GpuPresentMessage::Hello).expect("hello frame");
        assert_eq!(registry.apply_frame(&hello), Ok(GpuPresentMessage::Hello));
        assert_eq!(
            registry.apply_frame(&present_frame),
            Err(GpuPresentProtocolError::UnknownResource),
        );
    }
}
