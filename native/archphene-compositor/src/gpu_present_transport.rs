//! Same-UID fixed-frame Unix transport for the private APHB channel.

#![allow(unsafe_code)]

use std::io::{self, Read};
use std::mem::{size_of, zeroed};
use std::os::fd::AsRawFd;
use std::os::unix::fs::{FileTypeExt, MetadataExt};
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::{Path, PathBuf};

use crate::gpu_present_protocol::GPU_PRESENT_FRAME_BYTES;

const MAX_UNIX_SOCKET_PATH_BYTES: usize = 103;

#[derive(Clone, Copy)]
struct SocketIdentity {
    device: u64,
    inode: u64,
}

pub(crate) struct GpuPresentEndpoint {
    listener: UnixListener,
    stream: Option<UnixStream>,
    path: PathBuf,
    identity: SocketIdentity,
    expected_uid: u32,
    frame: [u8; GPU_PRESENT_FRAME_BYTES],
    frame_length: usize,
}

impl GpuPresentEndpoint {
    pub(crate) fn bind(path: &Path, expected_uid: u32) -> io::Result<Self> {
        let path_bytes = path.as_os_str().as_encoded_bytes();
        if !path.is_absolute()
            || path_bytes.is_empty()
            || path_bytes.len() > MAX_UNIX_SOCKET_PATH_BYTES
            || path_bytes.contains(&0)
        {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "invalid GPU present socket path",
            ));
        }
        match std::fs::symlink_metadata(path) {
            Ok(metadata) if metadata.file_type().is_socket() => std::fs::remove_file(path)?,
            Ok(_) => {
                return Err(io::Error::new(
                    io::ErrorKind::AlreadyExists,
                    "GPU present path is not a socket",
                ));
            }
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => return Err(error),
        }
        let listener = UnixListener::bind(path)?;
        listener.set_nonblocking(true)?;
        let metadata = std::fs::symlink_metadata(path)?;
        if !metadata.file_type().is_socket() {
            return Err(io::Error::other("GPU present listener is not a socket"));
        }
        Ok(Self {
            listener,
            stream: None,
            path: path.to_owned(),
            identity: SocketIdentity {
                device: metadata.dev(),
                inode: metadata.ino(),
            },
            expected_uid,
            frame: [0; GPU_PRESENT_FRAME_BYTES],
            frame_length: 0,
        })
    }

    pub(crate) fn poll_frame(&mut self) -> io::Result<Option<[u8; GPU_PRESENT_FRAME_BYTES]>> {
        if self.stream.is_none() {
            match self.listener.accept() {
                Ok((stream, _)) => {
                    if peer_uid(&stream)? != self.expected_uid {
                        return Ok(None);
                    }
                    stream.set_nonblocking(true)?;
                    self.stream = Some(stream);
                }
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => return Ok(None),
                Err(error) => return Err(error),
            }
        }
        let stream = self.stream.as_mut().expect("stream accepted");
        loop {
            match stream.read(&mut self.frame[self.frame_length..]) {
                Ok(0) => {
                    self.stream = None;
                    self.frame_length = 0;
                    return Ok(None);
                }
                Ok(count) => {
                    self.frame_length += count;
                    if self.frame_length == GPU_PRESENT_FRAME_BYTES {
                        self.frame_length = 0;
                        return Ok(Some(self.frame));
                    }
                }
                Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => return Ok(None),
                Err(error) => return Err(error),
            }
        }
    }
}

impl Drop for GpuPresentEndpoint {
    fn drop(&mut self) {
        self.stream = None;
        let owned = std::fs::symlink_metadata(&self.path).is_ok_and(|metadata| {
            metadata.file_type().is_socket()
                && metadata.dev() == self.identity.device
                && metadata.ino() == self.identity.inode
        });
        if owned {
            let _ = std::fs::remove_file(&self.path);
        }
    }
}

pub(crate) fn current_euid() -> u32 {
    // SAFETY: `geteuid` has no preconditions or owned return value.
    unsafe { libc::geteuid() }
}

fn peer_uid(stream: &UnixStream) -> io::Result<u32> {
    // SAFETY: the socket is live, `credentials` and `length` point to writable
    // ABI-compatible values, and their lifetimes cover this call.
    unsafe {
        let mut credentials: libc::ucred = zeroed();
        let mut length = size_of::<libc::ucred>() as libc::socklen_t;
        let result = libc::getsockopt(
            stream.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            (&raw mut credentials).cast(),
            &raw mut length,
        );
        if result != 0 {
            return Err(io::Error::last_os_error());
        }
        if length as usize != size_of::<libc::ucred>() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "invalid GPU present peer credentials",
            ));
        }
        Ok(credentials.uid)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    #[test]
    fn receives_one_fragmented_same_uid_fixed_frame_and_cleans_up() {
        let path = std::env::temp_dir().join(format!(
            "archphene-gpu-transport-{}.sock",
            std::process::id()
        ));
        let _ = std::fs::remove_file(&path);
        let mut endpoint = GpuPresentEndpoint::bind(&path, current_euid()).expect("endpoint");
        let mut sender = UnixStream::connect(&path).expect("connect sender");
        let frame = std::array::from_fn::<_, GPU_PRESENT_FRAME_BYTES, _>(|index| index as u8);
        sender.write_all(&frame[..17]).expect("first fragment");
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(2);
        while endpoint.poll_frame().expect("partial frame").is_some() {
            assert!(std::time::Instant::now() < deadline);
        }
        sender.write_all(&frame[17..]).expect("second fragment");
        let received = loop {
            if let Some(received) = endpoint.poll_frame().expect("complete frame") {
                break received;
            }
            assert!(std::time::Instant::now() < deadline);
            std::thread::yield_now();
        };
        assert_eq!(received, frame);
        drop(endpoint);
        assert!(!path.exists());
    }

    #[test]
    fn rejects_unsafe_paths_and_does_not_unlink_a_replacement() {
        assert_eq!(
            GpuPresentEndpoint::bind(Path::new("relative.sock"), current_euid())
                .err()
                .expect("relative path")
                .kind(),
            io::ErrorKind::InvalidInput
        );
        let path = std::env::temp_dir().join(format!(
            "archphene-gpu-replacement-{}.sock",
            std::process::id()
        ));
        let _ = std::fs::remove_file(&path);
        let endpoint = GpuPresentEndpoint::bind(&path, current_euid()).expect("endpoint");
        std::fs::remove_file(&path).expect("remove owned socket");
        let replacement = UnixListener::bind(&path).expect("replacement socket");
        drop(endpoint);
        assert!(path.exists());
        drop(replacement);
        std::fs::remove_file(path).expect("remove replacement");
    }

    #[test]
    fn disconnect_discards_partial_frame_before_replacement() {
        let path = std::env::temp_dir().join(format!(
            "archphene-gpu-reconnect-{}.sock",
            std::process::id()
        ));
        let _ = std::fs::remove_file(&path);
        let mut endpoint = GpuPresentEndpoint::bind(&path, current_euid()).expect("endpoint");
        let mut first = UnixStream::connect(&path).expect("first helper");
        first.write_all(&[0x55; 9]).expect("partial frame");
        let _ = endpoint.poll_frame().expect("read partial frame");
        drop(first);
        assert_eq!(endpoint.poll_frame().expect("observe disconnect"), None);
        let mut replacement = UnixStream::connect(&path).expect("replacement helper");
        let frame = [0xa5; GPU_PRESENT_FRAME_BYTES];
        replacement.write_all(&frame).expect("replacement frame");
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(2);
        loop {
            if let Some(received) = endpoint.poll_frame().expect("replacement receive") {
                assert_eq!(received, frame);
                break;
            }
            assert!(std::time::Instant::now() < deadline);
            std::thread::yield_now();
        }
    }
}
