//! Bounded Android EGL/GLES output renderer.
//!
//! This module is Android-only and keeps every EGL/GL object on the compositor
//! owner thread. It imports manager-owned `AHardwareBuffer` output slots as
//! render targets, retains one source texture, uploads only admitted damage,
//! and finishes GPU work before the existing SurfaceControl handoff. Callers
//! retain the CPU-locked path as the mandatory fallback.

#![allow(unsafe_code)]

use std::ffi::{CStr, c_char, c_void};
use std::ptr::{self, NonNull};

use crate::gpu_damage::stage_rgba_damage;
pub(crate) use crate::gpu_damage::{Damage, SourceFormat};

type EglDisplay = *mut c_void;
type EglConfig = *mut c_void;
type EglContext = *mut c_void;
type EglSurface = *mut c_void;
type EglClientBuffer = *mut c_void;
type EglImage = *mut c_void;

const EGL_FALSE: i32 = 0;
const EGL_TRUE: i32 = 1;
const EGL_NONE: i32 = 0x3038;
const EGL_EXTENSIONS: i32 = 0x3055;
const EGL_RED_SIZE: i32 = 0x3024;
const EGL_GREEN_SIZE: i32 = 0x3023;
const EGL_BLUE_SIZE: i32 = 0x3022;
const EGL_ALPHA_SIZE: i32 = 0x3021;
const EGL_SURFACE_TYPE: i32 = 0x3033;
const EGL_PBUFFER_BIT: i32 = 0x0001;
const EGL_RENDERABLE_TYPE: i32 = 0x3040;
const EGL_OPENGL_ES2_BIT: i32 = 0x0004;
const EGL_CONTEXT_CLIENT_VERSION: i32 = 0x3098;
const EGL_WIDTH: i32 = 0x3057;
const EGL_HEIGHT: i32 = 0x3056;
const EGL_OPENGL_ES_API: u32 = 0x30a0;
const EGL_NATIVE_BUFFER_ANDROID: u32 = 0x3140;
const EGL_IMAGE_PRESERVED_KHR: i32 = 0x30d2;

const GL_FALSE: u8 = 0;
const GL_EXTENSIONS: u32 = 0x1f03;
const GL_VERTEX_SHADER: u32 = 0x8b31;
const GL_FRAGMENT_SHADER: u32 = 0x8b30;
const GL_COMPILE_STATUS: u32 = 0x8b81;
const GL_LINK_STATUS: u32 = 0x8b82;
const GL_TEXTURE_2D: u32 = 0x0de1;
const GL_TEXTURE0: u32 = 0x84c0;
const GL_TEXTURE_MIN_FILTER: u32 = 0x2801;
const GL_TEXTURE_MAG_FILTER: u32 = 0x2800;
const GL_TEXTURE_WRAP_S: u32 = 0x2802;
const GL_TEXTURE_WRAP_T: u32 = 0x2803;
const GL_NEAREST: i32 = 0x2600;
const GL_CLAMP_TO_EDGE: i32 = 0x812f;
const GL_RGBA: u32 = 0x1908;
const GL_UNSIGNED_BYTE: u32 = 0x1401;
const GL_RENDERBUFFER: u32 = 0x8d41;
const GL_FRAMEBUFFER: u32 = 0x8d40;
const GL_COLOR_ATTACHMENT0: u32 = 0x8ce0;
const GL_FRAMEBUFFER_COMPLETE: u32 = 0x8cd5;
const GL_FLOAT: u32 = 0x1406;
const GL_TRIANGLE_STRIP: u32 = 0x0005;
const GL_UNPACK_ALIGNMENT: u32 = 0x0cf5;
const GL_NO_ERROR: u32 = 0;

const MAX_TARGETS: usize = 15;
const MAX_PIXEL_BYTES: usize = 33_554_432 * 4;

type EglGetNativeClientBufferAndroid = unsafe extern "C" fn(*mut c_void) -> EglClientBuffer;
type EglCreateImageKhr =
    unsafe extern "C" fn(EglDisplay, EglContext, u32, EglClientBuffer, *const i32) -> EglImage;
type EglDestroyImageKhr = unsafe extern "C" fn(EglDisplay, EglImage) -> i32;
type GlEglImageTargetRenderbufferStorageOes = unsafe extern "C" fn(u32, EglImage);

#[link(name = "EGL")]
unsafe extern "C" {
    fn eglGetDisplay(native_display: *mut c_void) -> EglDisplay;
    fn eglInitialize(display: EglDisplay, major: *mut i32, minor: *mut i32) -> i32;
    fn eglBindAPI(api: u32) -> i32;
    fn eglChooseConfig(
        display: EglDisplay,
        attributes: *const i32,
        configs: *mut EglConfig,
        config_size: i32,
        count: *mut i32,
    ) -> i32;
    fn eglCreateContext(
        display: EglDisplay,
        config: EglConfig,
        share: EglContext,
        attributes: *const i32,
    ) -> EglContext;
    fn eglDestroyContext(display: EglDisplay, context: EglContext) -> i32;
    fn eglCreatePbufferSurface(
        display: EglDisplay,
        config: EglConfig,
        attributes: *const i32,
    ) -> EglSurface;
    fn eglDestroySurface(display: EglDisplay, surface: EglSurface) -> i32;
    fn eglMakeCurrent(
        display: EglDisplay,
        draw: EglSurface,
        read: EglSurface,
        context: EglContext,
    ) -> i32;
    fn eglQueryString(display: EglDisplay, name: i32) -> *const c_char;
    fn eglGetProcAddress(name: *const c_char) -> *const c_void;
}

#[link(name = "GLESv2")]
unsafe extern "C" {
    fn glGetString(name: u32) -> *const u8;
    fn glGetError() -> u32;
    fn glCreateShader(kind: u32) -> u32;
    fn glShaderSource(shader: u32, count: i32, source: *const *const c_char, length: *const i32);
    fn glCompileShader(shader: u32);
    fn glGetShaderiv(shader: u32, parameter: u32, value: *mut i32);
    fn glDeleteShader(shader: u32);
    fn glCreateProgram() -> u32;
    fn glAttachShader(program: u32, shader: u32);
    fn glBindAttribLocation(program: u32, index: u32, name: *const c_char);
    fn glLinkProgram(program: u32);
    fn glGetProgramiv(program: u32, parameter: u32, value: *mut i32);
    fn glDeleteProgram(program: u32);
    fn glUseProgram(program: u32);
    fn glGetUniformLocation(program: u32, name: *const c_char) -> i32;
    fn glUniform1i(location: i32, value: i32);
    fn glGenTextures(count: i32, textures: *mut u32);
    fn glDeleteTextures(count: i32, textures: *const u32);
    fn glActiveTexture(texture: u32);
    fn glBindTexture(target: u32, texture: u32);
    fn glTexParameteri(target: u32, parameter: u32, value: i32);
    fn glTexImage2D(
        target: u32,
        level: i32,
        internal_format: i32,
        width: i32,
        height: i32,
        border: i32,
        format: u32,
        kind: u32,
        pixels: *const c_void,
    );
    fn glTexSubImage2D(
        target: u32,
        level: i32,
        x: i32,
        y: i32,
        width: i32,
        height: i32,
        format: u32,
        kind: u32,
        pixels: *const c_void,
    );
    fn glPixelStorei(parameter: u32, value: i32);
    fn glGenRenderbuffers(count: i32, renderbuffers: *mut u32);
    fn glDeleteRenderbuffers(count: i32, renderbuffers: *const u32);
    fn glBindRenderbuffer(target: u32, renderbuffer: u32);
    fn glGenFramebuffers(count: i32, framebuffers: *mut u32);
    fn glDeleteFramebuffers(count: i32, framebuffers: *const u32);
    fn glBindFramebuffer(target: u32, framebuffer: u32);
    fn glFramebufferRenderbuffer(target: u32, attachment: u32, kind: u32, renderbuffer: u32);
    fn glCheckFramebufferStatus(target: u32) -> u32;
    fn glViewport(x: i32, y: i32, width: i32, height: i32);
    fn glEnableVertexAttribArray(index: u32);
    fn glVertexAttribPointer(
        index: u32,
        size: i32,
        kind: u32,
        normalized: u8,
        stride: i32,
        pointer: *const c_void,
    );
    fn glDrawArrays(mode: u32, first: i32, count: i32);
    fn glFinish();
}

struct ImportedTarget {
    slot_id: u64,
    image: EglImage,
    renderbuffer: u32,
    framebuffer: u32,
}

struct EglContextGuard {
    display: EglDisplay,
    context: EglContext,
    surface: EglSurface,
}

impl Drop for EglContextGuard {
    fn drop(&mut self) {
        unsafe {
            let _ = eglMakeCurrent(
                self.display,
                ptr::null_mut(),
                ptr::null_mut(),
                ptr::null_mut(),
            );
            let _ = eglDestroySurface(self.display, self.surface);
            let _ = eglDestroyContext(self.display, self.context);
        }
    }
}

pub(crate) struct GpuRenderer {
    display: EglDisplay,
    context: EglContext,
    surface: EglSurface,
    get_native_buffer: EglGetNativeClientBufferAndroid,
    create_image: EglCreateImageKhr,
    destroy_image: EglDestroyImageKhr,
    image_target_renderbuffer: GlEglImageTargetRenderbufferStorageOes,
    program: u32,
    texture: u32,
    sampler: i32,
    texture_width: i32,
    texture_height: i32,
    staging: Vec<u8>,
    targets: Vec<ImportedTarget>,
}

impl GpuRenderer {
    pub(crate) fn new() -> Result<Self, ()> {
        // SAFETY: every call follows the EGL/GLES ABI and all returned handles
        // are checked before ownership is retained.
        unsafe {
            let display = eglGetDisplay(ptr::null_mut());
            if display.is_null()
                || eglInitialize(display, ptr::null_mut(), ptr::null_mut()) == EGL_FALSE
            {
                return Err(());
            }
            Self::create(display)
        }
    }

    unsafe fn create(display: EglDisplay) -> Result<Self, ()> {
        if !extension_present(
            unsafe { eglQueryString(display, EGL_EXTENSIONS) },
            b"EGL_ANDROID_image_native_buffer",
        ) || unsafe { eglBindAPI(EGL_OPENGL_ES_API) } == EGL_FALSE
        {
            return Err(());
        }
        let config_attributes = [
            EGL_RED_SIZE,
            8,
            EGL_GREEN_SIZE,
            8,
            EGL_BLUE_SIZE,
            8,
            EGL_ALPHA_SIZE,
            8,
            EGL_SURFACE_TYPE,
            EGL_PBUFFER_BIT,
            EGL_RENDERABLE_TYPE,
            EGL_OPENGL_ES2_BIT,
            EGL_NONE,
        ];
        let mut config = ptr::null_mut();
        let mut count = 0;
        if unsafe {
            eglChooseConfig(
                display,
                config_attributes.as_ptr(),
                &mut config,
                1,
                &mut count,
            )
        } == EGL_FALSE
            || count != 1
            || config.is_null()
        {
            return Err(());
        }
        let context_attributes = [EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE];
        let context = unsafe {
            eglCreateContext(
                display,
                config,
                ptr::null_mut(),
                context_attributes.as_ptr(),
            )
        };
        if context.is_null() {
            return Err(());
        }
        let surface_attributes = [EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE];
        let surface =
            unsafe { eglCreatePbufferSurface(display, config, surface_attributes.as_ptr()) };
        if surface.is_null() {
            let _ = unsafe { eglDestroyContext(display, context) };
            return Err(());
        }
        let context_guard = EglContextGuard {
            display,
            context,
            surface,
        };
        if unsafe { eglMakeCurrent(display, surface, surface, context) } == EGL_FALSE
            || !extension_present(
                unsafe { glGetString(GL_EXTENSIONS) }.cast(),
                b"GL_OES_EGL_image",
            )
        {
            return Err(());
        }
        let get_native_buffer = unsafe {
            load_egl::<EglGetNativeClientBufferAndroid>(c"eglGetNativeClientBufferANDROID")?
        };
        let create_image = unsafe { load_egl::<EglCreateImageKhr>(c"eglCreateImageKHR")? };
        let destroy_image = unsafe { load_egl::<EglDestroyImageKhr>(c"eglDestroyImageKHR")? };
        let image_target_renderbuffer = unsafe {
            load_egl::<GlEglImageTargetRenderbufferStorageOes>(
                c"glEGLImageTargetRenderbufferStorageOES",
            )?
        };
        // Ensure the import entry points are jointly usable before retaining
        // the context. The first two are used by `import_target` through these
        // fields to avoid another unchecked loader lookup.
        let program = unsafe { create_program()? };
        let mut texture = 0;
        unsafe { glGenTextures(1, &mut texture) };
        if texture == 0 {
            unsafe { glDeleteProgram(program) };
            return Err(());
        }
        unsafe {
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        }
        let sampler = unsafe { glGetUniformLocation(program, c"frame".as_ptr()) };
        if sampler < 0 || unsafe { glGetError() } != GL_NO_ERROR {
            unsafe {
                glDeleteTextures(1, &texture);
                glDeleteProgram(program);
            }
            return Err(());
        }
        let renderer = Self {
            display,
            context,
            surface,
            get_native_buffer,
            create_image,
            destroy_image,
            image_target_renderbuffer,
            program,
            texture,
            sampler,
            texture_width: 0,
            texture_height: 0,
            staging: Vec::new(),
            targets: Vec::with_capacity(MAX_TARGETS),
        };
        std::mem::forget(context_guard);
        Ok(renderer)
    }

    pub(crate) fn render(
        &mut self,
        slot_id: u64,
        hardware_buffer: *mut c_void,
        target_width: i32,
        target_height: i32,
        source_width: i32,
        source_height: i32,
        source: &[u8],
        format: SourceFormat,
        damage: Damage,
    ) -> Result<(), ()> {
        if hardware_buffer.is_null()
            || target_width <= 0
            || target_height <= 0
            || source_width != target_width
            || source_height != target_height
        {
            return Err(());
        }
        self.make_current()?;
        let texture_recreated = self.ensure_texture(source_width, source_height)?;
        let damage = if texture_recreated {
            Damage {
                x: 0,
                y: 0,
                width: source_width,
                height: source_height,
            }
        } else {
            damage
        };
        self.upload_damage(source_width, source_height, source, format, damage)?;
        let target_index = match self
            .targets
            .iter()
            .position(|target| target.slot_id == slot_id)
        {
            Some(index) => index,
            None => self.import_target(slot_id, hardware_buffer)?,
        };
        let target = &self.targets[target_index];
        const VERTICES: [f32; 16] = [
            -1.0, -1.0, 0.0, 0.0, 1.0, -1.0, 1.0, 0.0, -1.0, 1.0, 0.0, 1.0, 1.0, 1.0, 1.0, 1.0,
        ];
        // SAFETY: the context is current, object names are owned by this
        // renderer, and the stack vertex array remains live through DrawArrays.
        unsafe {
            glBindFramebuffer(GL_FRAMEBUFFER, target.framebuffer);
            if glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE {
                return Err(());
            }
            glViewport(0, 0, target_width, target_height);
            glUseProgram(self.program);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, self.texture);
            glUniform1i(self.sampler, 0);
            glEnableVertexAttribArray(0);
            glEnableVertexAttribArray(1);
            glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * 4, VERTICES.as_ptr().cast());
            glVertexAttribPointer(
                1,
                2,
                GL_FLOAT,
                GL_FALSE,
                4 * 4,
                VERTICES.as_ptr().add(2).cast(),
            );
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            glFinish();
            if glGetError() != GL_NO_ERROR {
                return Err(());
            }
        }
        Ok(())
    }

    pub(crate) fn remove_target(&mut self, slot_id: u64) -> Result<(), ()> {
        let Some(index) = self
            .targets
            .iter()
            .position(|target| target.slot_id == slot_id)
        else {
            return Ok(());
        };
        self.make_current()?;
        let target = self.targets.swap_remove(index);
        unsafe {
            glDeleteFramebuffers(1, &target.framebuffer);
            glDeleteRenderbuffers(1, &target.renderbuffer);
            (self.destroy_image)(self.display, target.image);
        }
        Ok(())
    }

    fn make_current(&self) -> Result<(), ()> {
        // SAFETY: all handles are owned by this renderer and used only on its
        // compositor owner thread.
        (unsafe { eglMakeCurrent(self.display, self.surface, self.surface, self.context) }
            != EGL_FALSE)
            .then_some(())
            .ok_or(())
    }

    fn ensure_texture(&mut self, width: i32, height: i32) -> Result<bool, ()> {
        let bytes = usize::try_from(width)
            .ok()
            .and_then(|width| {
                usize::try_from(height)
                    .ok()
                    .and_then(|height| width.checked_mul(height))
            })
            .and_then(|pixels| pixels.checked_mul(4))
            .filter(|bytes| *bytes <= MAX_PIXEL_BYTES)
            .ok_or(())?;
        if self.texture_width == width && self.texture_height == height {
            return Ok(false);
        }
        self.staging
            .try_reserve_exact(bytes.saturating_sub(self.staging.len()))
            .map_err(|_| ())?;
        self.staging.resize(bytes, 0);
        // SAFETY: the current context owns the bound texture; null initializes
        // bounded storage without reading client memory.
        unsafe {
            glBindTexture(GL_TEXTURE_2D, self.texture);
            glTexImage2D(
                GL_TEXTURE_2D,
                0,
                GL_RGBA as i32,
                width,
                height,
                0,
                GL_RGBA,
                GL_UNSIGNED_BYTE,
                ptr::null(),
            );
            if glGetError() != GL_NO_ERROR {
                return Err(());
            }
        }
        self.texture_width = width;
        self.texture_height = height;
        Ok(true)
    }

    fn upload_damage(
        &mut self,
        width: i32,
        height: i32,
        source: &[u8],
        format: SourceFormat,
        damage: Damage,
    ) -> Result<(), ()> {
        stage_rgba_damage(width, height, source, format, damage, &mut self.staging)?;
        // SAFETY: staging retains at least the exact bounded damaged rectangle.
        unsafe {
            glBindTexture(GL_TEXTURE_2D, self.texture);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glTexSubImage2D(
                GL_TEXTURE_2D,
                0,
                damage.x,
                damage.y,
                damage.width,
                damage.height,
                GL_RGBA,
                GL_UNSIGNED_BYTE,
                self.staging.as_ptr().cast(),
            );
            if glGetError() != GL_NO_ERROR {
                return Err(());
            }
        }
        Ok(())
    }

    fn import_target(&mut self, slot_id: u64, hardware_buffer: *mut c_void) -> Result<usize, ()> {
        if self.targets.len() >= MAX_TARGETS {
            return Err(());
        }
        // SAFETY: the hardware buffer remains owned by the presentation slot
        // until this renderer deletes its image during owner-thread teardown.
        let client_buffer = unsafe { (self.get_native_buffer)(hardware_buffer) };
        if client_buffer.is_null() {
            return Err(());
        }
        let attributes = [EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE];
        let image = unsafe {
            (self.create_image)(
                self.display,
                ptr::null_mut(),
                EGL_NATIVE_BUFFER_ANDROID,
                client_buffer,
                attributes.as_ptr(),
            )
        };
        if image.is_null() {
            return Err(());
        }
        let mut renderbuffer = 0;
        let mut framebuffer = 0;
        unsafe {
            glGenRenderbuffers(1, &mut renderbuffer);
            glBindRenderbuffer(GL_RENDERBUFFER, renderbuffer);
            (self.image_target_renderbuffer)(GL_RENDERBUFFER, image);
            glGenFramebuffers(1, &mut framebuffer);
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glFramebufferRenderbuffer(
                GL_FRAMEBUFFER,
                GL_COLOR_ATTACHMENT0,
                GL_RENDERBUFFER,
                renderbuffer,
            );
        }
        if renderbuffer == 0
            || framebuffer == 0
            || unsafe { glCheckFramebufferStatus(GL_FRAMEBUFFER) } != GL_FRAMEBUFFER_COMPLETE
            || unsafe { glGetError() } != GL_NO_ERROR
        {
            unsafe {
                if framebuffer != 0 {
                    glDeleteFramebuffers(1, &framebuffer);
                }
                if renderbuffer != 0 {
                    glDeleteRenderbuffers(1, &renderbuffer);
                }
                (self.destroy_image)(self.display, image);
            }
            return Err(());
        }
        self.targets.push(ImportedTarget {
            slot_id,
            image,
            renderbuffer,
            framebuffer,
        });
        Ok(self.targets.len() - 1)
    }
}

impl Drop for GpuRenderer {
    fn drop(&mut self) {
        let _ = self.make_current();
        for target in self.targets.drain(..) {
            unsafe {
                glDeleteFramebuffers(1, &target.framebuffer);
                glDeleteRenderbuffers(1, &target.renderbuffer);
                (self.destroy_image)(self.display, target.image);
            }
        }
        unsafe {
            glDeleteTextures(1, &self.texture);
            glDeleteProgram(self.program);
            let _ = eglMakeCurrent(
                self.display,
                ptr::null_mut(),
                ptr::null_mut(),
                ptr::null_mut(),
            );
            let _ = eglDestroySurface(self.display, self.surface);
            let _ = eglDestroyContext(self.display, self.context);
        }
    }
}

unsafe fn load_egl<T: Copy>(name: &CStr) -> Result<T, ()> {
    if std::mem::size_of::<T>() != std::mem::size_of::<*mut c_void>() {
        return Err(());
    }
    let address = NonNull::new(unsafe { eglGetProcAddress(name.as_ptr()) }.cast_mut()).ok_or(())?;
    // SAFETY: the caller supplies the exact C ABI function-pointer type for the
    // named EGL/GLES extension entry point.
    Ok(unsafe { std::mem::transmute_copy::<*mut c_void, T>(&address.as_ptr()) })
}

fn extension_present(raw: *const c_char, wanted: &[u8]) -> bool {
    if raw.is_null() || wanted.is_empty() || wanted.iter().any(u8::is_ascii_whitespace) {
        return false;
    }
    let extensions = unsafe { CStr::from_ptr(raw) }.to_bytes();
    extensions
        .split(|byte| byte.is_ascii_whitespace())
        .any(|extension| extension == wanted)
}

unsafe fn compile_shader(kind: u32, source: &CStr) -> Result<u32, ()> {
    let shader = unsafe { glCreateShader(kind) };
    if shader == 0 {
        return Err(());
    }
    let pointer = source.as_ptr();
    unsafe {
        glShaderSource(shader, 1, &pointer, ptr::null());
        glCompileShader(shader);
    }
    let mut compiled = 0;
    unsafe { glGetShaderiv(shader, GL_COMPILE_STATUS, &mut compiled) };
    if compiled == 0 {
        unsafe { glDeleteShader(shader) };
        return Err(());
    }
    Ok(shader)
}

unsafe fn create_program() -> Result<u32, ()> {
    let vertex = unsafe {
        compile_shader(
            GL_VERTEX_SHADER,
            c"attribute vec2 position; attribute vec2 uv; varying vec2 texcoord; void main() { gl_Position = vec4(position, 0.0, 1.0); texcoord = uv; }",
        )?
    };
    let fragment = match unsafe {
        compile_shader(
            GL_FRAGMENT_SHADER,
            c"precision mediump float; varying vec2 texcoord; uniform sampler2D frame; void main() { gl_FragColor = texture2D(frame, texcoord); }",
        )
    } {
        Ok(shader) => shader,
        Err(()) => {
            unsafe { glDeleteShader(vertex) };
            return Err(());
        }
    };
    let program = unsafe { glCreateProgram() };
    if program == 0 {
        unsafe {
            glDeleteShader(vertex);
            glDeleteShader(fragment);
        }
        return Err(());
    }
    unsafe {
        glAttachShader(program, vertex);
        glAttachShader(program, fragment);
        glBindAttribLocation(program, 0, c"position".as_ptr());
        glBindAttribLocation(program, 1, c"uv".as_ptr());
        glLinkProgram(program);
        glDeleteShader(vertex);
        glDeleteShader(fragment);
    }
    let mut linked = 0;
    unsafe { glGetProgramiv(program, GL_LINK_STATUS, &mut linked) };
    if linked == 0 {
        unsafe { glDeleteProgram(program) };
        return Err(());
    }
    Ok(program)
}
