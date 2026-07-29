# Production test suites

New Rust host tests live beside their crates. Android cross-boundary and
full-device gates live in `scripts/` and write ignored evidence under
`tooling/build/`.

The initial production base is covered by:

- `cargo test --workspace --locked --offline`
- `scripts/test-archphene-base.sh --serial <serial> --install-apk`

Legacy prototype tests remain in place as behavioral references until each
retained capability has a production replacement.
