use std::env;
use std::fs::{self, File};
use std::io::{self, Read};
use std::path::Path;

use archphene_packages::RepositoryArchitecture;
use archphene_packages::aur::{MAX_AUR_RPC_BYTES, MAX_AUR_SNAPSHOT_BYTES, review_aur_snapshot};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut arguments = env::args_os().skip(1);
    let rpc_path = arguments.next().ok_or("missing RPC response path")?;
    let snapshot_path = arguments.next().ok_or("missing snapshot path")?;
    let package = arguments
        .next()
        .ok_or("missing package name")?
        .into_string()
        .map_err(|_| "package name is not UTF-8")?;
    let architecture_argument = arguments.next().ok_or("missing architecture")?;
    let architecture = match architecture_argument.to_str() {
        Some("x86_64") => RepositoryArchitecture::X86_64,
        Some("aarch64") => RepositoryArchitecture::Aarch64,
        _ => return Err("architecture must be x86_64 or aarch64".into()),
    };
    if arguments.next().is_some() {
        return Err("unexpected extra arguments".into());
    }
    let rpc = read_bounded(Path::new(&rpc_path), MAX_AUR_RPC_BYTES)?;
    let snapshot = read_bounded(Path::new(&snapshot_path), MAX_AUR_SNAPSHOT_BYTES)?;
    let review = review_aur_snapshot(&rpc, &snapshot, &package, architecture)?;
    println!(
        "{} {} by {}",
        review.package_name,
        review.version,
        review.maintainer.as_deref().unwrap_or("orphaned"),
    );
    println!(
        "{} sources, {} runtime dependencies, {} build dependencies",
        review.sources.len(),
        review.dependencies.len(),
        review.make_dependencies.len(),
    );
    println!(
        "{} unverified sources, {} insecure transports, {} install script(s)",
        review.unverified_source_count,
        review.insecure_source_count,
        review.install_scripts.len(),
    );
    println!(
        "AUR commit {}, snapshot SHA-256 {}",
        review.snapshot_commit.as_deref().unwrap_or("unavailable"),
        review
            .snapshot_sha256
            .map(hex_sha256)
            .as_deref()
            .unwrap_or("unavailable"),
    );
    Ok(())
}

fn hex_sha256(bytes: [u8; 32]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut output = String::with_capacity(64);
    for byte in bytes {
        output.push(HEX[(byte >> 4) as usize] as char);
        output.push(HEX[(byte & 0x0f) as usize] as char);
    }
    output
}

fn read_bounded(path: &Path, limit: usize) -> io::Result<Vec<u8>> {
    let mut file = File::open(path)?;
    let declared_size = file.metadata()?.len();
    let bytes = read_exact_input(&mut file, declared_size, limit)?;
    if fs::metadata(path)?.len() != declared_size {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "input changed or exceeded its size limit",
        ));
    }
    Ok(bytes)
}

fn read_exact_input(
    reader: &mut impl Read,
    declared_size: u64,
    limit: usize,
) -> io::Result<Vec<u8>> {
    let length = usize::try_from(declared_size)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "input exceeds its size limit"))?;
    if length > limit {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "input exceeds its size limit",
        ));
    }
    let mut bytes = vec![0_u8; length];
    reader.read_exact(&mut bytes).map_err(|error| {
        if error.kind() == io::ErrorKind::UnexpectedEof {
            io::Error::new(
                io::ErrorKind::InvalidData,
                "input changed or exceeded its size limit",
            )
        } else {
            error
        }
    })?;
    let mut overflow = [0_u8; 1];
    if reader.read(&mut overflow)? != 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "input changed or exceeded its size limit",
        ));
    }
    Ok(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    #[test]
    fn exact_input_rejects_declared_size_changes() {
        assert_eq!(
            read_exact_input(&mut Cursor::new(b"input"), 5, 5).expect("exact input"),
            b"input",
        );
        assert_eq!(
            read_exact_input(&mut Cursor::new([]), 0, 5).expect("empty input"),
            b"",
        );
        for bytes in [&b"inpu"[..], &b"input!"[..]] {
            assert_eq!(
                read_exact_input(&mut Cursor::new(bytes), 5, 5)
                    .expect_err("size change")
                    .kind(),
                io::ErrorKind::InvalidData,
            );
        }
        assert_eq!(
            read_exact_input(&mut Cursor::new(b"input"), 5, 4)
                .expect_err("limit")
                .kind(),
            io::ErrorKind::InvalidData,
        );
    }
}
