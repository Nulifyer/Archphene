//! Bounded commit identity state for the private virpipe-to-Wayland contract.
//!
//! The wire protocol associates a helper-presented resource and fence sequence
//! with one exact `wl_surface.commit`. Actual AHB admission remains gated by the
//! separately authenticated [`crate::gpu_present_protocol`] registry.

#![cfg_attr(not(test), allow(dead_code))]

pub(crate) const MAX_GPU_SURFACE_BINDINGS: usize = 32;
const MAX_GPU_SURFACE_RESOURCES: usize = 3;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct GpuSurfaceIdentity {
    pub(crate) helper_generation: u32,
    pub(crate) resource_id: u32,
    pub(crate) fence_sequence: u64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum GpuSurfaceIdentityError {
    InvalidGeneration,
    BindingLimit,
    DuplicateBinding,
    DuplicateSurface,
    UnknownBinding,
    ResourceLimit,
    DuplicateResource,
    UnknownResource,
    InvalidIdentity,
    StaleFence,
}

#[derive(Clone, Copy)]
struct ResourceState {
    resource_id: u32,
    last_fence_sequence: u64,
}

struct SurfaceBinding {
    binding_id: u32,
    surface_id: u32,
    pending: Option<Option<GpuSurfaceIdentity>>,
    committed: Option<GpuSurfaceIdentity>,
}

pub(crate) struct GpuSurfaceIdentityRegistry {
    helper_generation: u32,
    resources: [Option<ResourceState>; MAX_GPU_SURFACE_RESOURCES],
    bindings: Vec<SurfaceBinding>,
}

impl GpuSurfaceIdentityRegistry {
    pub(crate) fn new(helper_generation: u32) -> Result<Self, GpuSurfaceIdentityError> {
        if helper_generation == 0 {
            return Err(GpuSurfaceIdentityError::InvalidGeneration);
        }
        Ok(Self {
            helper_generation,
            resources: [None; MAX_GPU_SURFACE_RESOURCES],
            bindings: Vec::with_capacity(MAX_GPU_SURFACE_BINDINGS),
        })
    }

    pub(crate) fn replace_helper(
        &mut self,
        helper_generation: u32,
    ) -> Result<(), GpuSurfaceIdentityError> {
        if helper_generation == 0 || helper_generation <= self.helper_generation {
            return Err(GpuSurfaceIdentityError::InvalidGeneration);
        }
        self.helper_generation = helper_generation;
        self.resources.fill(None);
        for binding in &mut self.bindings {
            binding.pending = None;
            binding.committed = None;
        }
        Ok(())
    }

    pub(crate) fn register_resource(
        &mut self,
        resource_id: u32,
    ) -> Result<(), GpuSurfaceIdentityError> {
        if resource_id == 0 {
            return Err(GpuSurfaceIdentityError::InvalidIdentity);
        }
        if self
            .resources
            .iter()
            .flatten()
            .any(|resource| resource.resource_id == resource_id)
        {
            return Err(GpuSurfaceIdentityError::DuplicateResource);
        }
        let Some(slot) = self.resources.iter_mut().find(|slot| slot.is_none()) else {
            return Err(GpuSurfaceIdentityError::ResourceLimit);
        };
        *slot = Some(ResourceState {
            resource_id,
            last_fence_sequence: 0,
        });
        Ok(())
    }

    pub(crate) fn release_resource(
        &mut self,
        resource_id: u32,
    ) -> Result<(), GpuSurfaceIdentityError> {
        let Some(slot) = self.resources.iter_mut().find(|slot| {
            slot.as_ref()
                .is_some_and(|resource| resource.resource_id == resource_id)
        }) else {
            return Err(GpuSurfaceIdentityError::UnknownResource);
        };
        *slot = None;
        for binding in &mut self.bindings {
            if binding
                .pending
                .flatten()
                .is_some_and(|identity| identity.resource_id == resource_id)
            {
                binding.pending = None;
            }
            if binding
                .committed
                .is_some_and(|identity| identity.resource_id == resource_id)
            {
                binding.committed = None;
            }
        }
        Ok(())
    }

    pub(crate) fn bind_surface(
        &mut self,
        binding_id: u32,
        surface_id: u32,
    ) -> Result<(), GpuSurfaceIdentityError> {
        if binding_id == 0 || surface_id == 0 {
            return Err(GpuSurfaceIdentityError::InvalidIdentity);
        }
        if self
            .bindings
            .iter()
            .any(|binding| binding.binding_id == binding_id)
        {
            return Err(GpuSurfaceIdentityError::DuplicateBinding);
        }
        if self
            .bindings
            .iter()
            .any(|binding| binding.surface_id == surface_id)
        {
            return Err(GpuSurfaceIdentityError::DuplicateSurface);
        }
        if self.bindings.len() >= MAX_GPU_SURFACE_BINDINGS {
            return Err(GpuSurfaceIdentityError::BindingLimit);
        }
        self.bindings.push(SurfaceBinding {
            binding_id,
            surface_id,
            pending: None,
            committed: None,
        });
        Ok(())
    }

    pub(crate) fn destroy_binding(
        &mut self,
        binding_id: u32,
    ) -> Result<(), GpuSurfaceIdentityError> {
        let Some(index) = self
            .bindings
            .iter()
            .position(|binding| binding.binding_id == binding_id)
        else {
            return Err(GpuSurfaceIdentityError::UnknownBinding);
        };
        self.bindings.swap_remove(index);
        Ok(())
    }

    pub(crate) fn set_resource(
        &mut self,
        binding_id: u32,
        identity: GpuSurfaceIdentity,
    ) -> Result<(), GpuSurfaceIdentityError> {
        if identity.helper_generation != self.helper_generation
            || identity.resource_id == 0
            || identity.fence_sequence == 0
        {
            return Err(GpuSurfaceIdentityError::InvalidIdentity);
        }
        let Some(resource) = self
            .resources
            .iter_mut()
            .flatten()
            .find(|resource| resource.resource_id == identity.resource_id)
        else {
            return Err(GpuSurfaceIdentityError::UnknownResource);
        };
        if identity.fence_sequence <= resource.last_fence_sequence {
            return Err(GpuSurfaceIdentityError::StaleFence);
        }
        let Some(binding) = self
            .bindings
            .iter_mut()
            .find(|binding| binding.binding_id == binding_id)
        else {
            return Err(GpuSurfaceIdentityError::UnknownBinding);
        };
        resource.last_fence_sequence = identity.fence_sequence;
        binding.pending = Some(Some(identity));
        Ok(())
    }

    pub(crate) fn clear(&mut self, binding_id: u32) -> Result<(), GpuSurfaceIdentityError> {
        let Some(binding) = self
            .bindings
            .iter_mut()
            .find(|binding| binding.binding_id == binding_id)
        else {
            return Err(GpuSurfaceIdentityError::UnknownBinding);
        };
        binding.pending = Some(None);
        Ok(())
    }

    /// Latches pending identity state on the matching `wl_surface.commit`.
    ///
    /// A new standard `wl_buffer` without a pending GPU identity explicitly
    /// replaces an older GPU-backed commit. Damage-only commits retain it.
    pub(crate) fn commit(
        &mut self,
        binding_id: u32,
        standard_buffer_updated: bool,
    ) -> Result<Option<GpuSurfaceIdentity>, GpuSurfaceIdentityError> {
        let Some(binding) = self
            .bindings
            .iter_mut()
            .find(|binding| binding.binding_id == binding_id)
        else {
            return Err(GpuSurfaceIdentityError::UnknownBinding);
        };
        if let Some(pending) = binding.pending.take() {
            binding.committed = pending;
        } else if standard_buffer_updated {
            binding.committed = None;
        }
        Ok(binding.committed)
    }

    pub(crate) fn commit_surface(
        &mut self,
        surface_id: u32,
        standard_buffer_updated: bool,
    ) -> Result<Option<GpuSurfaceIdentity>, GpuSurfaceIdentityError> {
        let Some(binding_id) = self
            .bindings
            .iter()
            .find(|binding| binding.surface_id == surface_id)
            .map(|binding| binding.binding_id)
        else {
            return Ok(None);
        };
        self.commit(binding_id, standard_buffer_updated)
    }

    pub(crate) fn committed_surface(&self, surface_id: u32) -> Option<GpuSurfaceIdentity> {
        self.bindings
            .iter()
            .find(|binding| binding.surface_id == surface_id)
            .and_then(|binding| binding.committed)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn identity(generation: u32, resource_id: u32, fence_sequence: u64) -> GpuSurfaceIdentity {
        GpuSurfaceIdentity {
            helper_generation: generation,
            resource_id,
            fence_sequence,
        }
    }

    #[test]
    fn bounds_and_uniquely_owns_surface_bindings() {
        let mut registry = GpuSurfaceIdentityRegistry::new(4).expect("registry");
        registry.bind_surface(1, 100).expect("first binding");
        assert_eq!(
            registry.bind_surface(1, 101),
            Err(GpuSurfaceIdentityError::DuplicateBinding)
        );
        assert_eq!(
            registry.bind_surface(2, 100),
            Err(GpuSurfaceIdentityError::DuplicateSurface)
        );
        for value in 2..=MAX_GPU_SURFACE_BINDINGS as u32 {
            registry
                .bind_surface(value, 99 + value)
                .expect("bounded binding");
        }
        assert_eq!(
            registry.bind_surface(100, 1000),
            Err(GpuSurfaceIdentityError::BindingLimit)
        );
    }

    #[test]
    fn accepts_only_current_known_scoped_resources() {
        let mut registry = GpuSurfaceIdentityRegistry::new(7).expect("registry");
        registry.bind_surface(1, 100).expect("binding");
        registry.register_resource(11).expect("resource");
        assert_eq!(
            registry.register_resource(11),
            Err(GpuSurfaceIdentityError::DuplicateResource)
        );
        registry.register_resource(12).expect("second resource");
        registry.register_resource(13).expect("third resource");
        assert_eq!(
            registry.register_resource(14),
            Err(GpuSurfaceIdentityError::ResourceLimit)
        );
        assert_eq!(
            registry.set_resource(1, identity(6, 11, 1)),
            Err(GpuSurfaceIdentityError::InvalidIdentity)
        );
        assert_eq!(
            registry.set_resource(1, identity(7, 14, 1)),
            Err(GpuSurfaceIdentityError::UnknownResource)
        );
        registry
            .set_resource(1, identity(7, 11, 1))
            .expect("scoped identity");
        assert_eq!(registry.commit(1, false), Ok(Some(identity(7, 11, 1))));
    }

    #[test]
    fn latches_exact_commits_and_standard_buffers_replace_them() {
        let mut registry = GpuSurfaceIdentityRegistry::new(1).expect("registry");
        registry.bind_surface(1, 100).expect("binding");
        registry.register_resource(5).expect("resource");
        registry
            .set_resource(1, identity(1, 5, 9))
            .expect("identity");
        assert_eq!(registry.commit(1, true), Ok(Some(identity(1, 5, 9))));
        assert_eq!(registry.commit(1, false), Ok(Some(identity(1, 5, 9))));
        assert_eq!(registry.commit(1, true), Ok(None));
        registry
            .set_resource(1, identity(1, 5, 10))
            .expect("next identity");
        registry.clear(1).expect("clear pending identity");
        assert_eq!(registry.commit(1, false), Ok(None));
    }

    #[test]
    fn rejects_stale_fences_across_surface_bindings() {
        let mut registry = GpuSurfaceIdentityRegistry::new(3).expect("registry");
        registry.register_resource(8).expect("resource");
        registry.bind_surface(1, 100).expect("first binding");
        registry.bind_surface(2, 200).expect("second binding");
        registry
            .set_resource(1, identity(3, 8, 12))
            .expect("new fence");
        assert_eq!(
            registry.set_resource(2, identity(3, 8, 12)),
            Err(GpuSurfaceIdentityError::StaleFence)
        );
        assert_eq!(
            registry.set_resource(2, identity(3, 8, 11)),
            Err(GpuSurfaceIdentityError::StaleFence)
        );
    }

    #[test]
    fn helper_replacement_and_resource_release_clear_committed_identity() {
        let mut registry = GpuSurfaceIdentityRegistry::new(2).expect("registry");
        registry.bind_surface(1, 100).expect("binding");
        registry.register_resource(4).expect("resource");
        registry
            .set_resource(1, identity(2, 4, 1))
            .expect("identity");
        assert!(registry.commit(1, false).expect("commit").is_some());
        registry.release_resource(4).expect("release");
        assert_eq!(registry.commit(1, false), Ok(None));
        registry.replace_helper(3).expect("replacement");
        assert_eq!(
            registry.set_resource(1, identity(2, 4, 2)),
            Err(GpuSurfaceIdentityError::InvalidIdentity)
        );
        assert_eq!(
            registry.replace_helper(3),
            Err(GpuSurfaceIdentityError::InvalidGeneration)
        );
        registry.destroy_binding(1).expect("destroy binding");
        assert_eq!(
            registry.commit(1, false),
            Err(GpuSurfaceIdentityError::UnknownBinding)
        );
    }
}
