//! Generated bindings for Archphene's private GPU commit-identity protocol.

#![allow(clippy::all)]
#![allow(dead_code, missing_docs, non_camel_case_types, non_snake_case)]
#![allow(
    non_upper_case_globals,
    unused_imports,
    unused_unsafe,
    unused_variables
)]

pub(crate) mod server {
    use wayland_server;
    use wayland_server::protocol::*;

    pub(crate) mod __interfaces {
        use wayland_server::protocol::__interfaces::*;
        wayland_scanner::generate_interfaces!("./protocols/archphene-gpu-present-v1.xml");
    }
    use self::__interfaces::*;
    wayland_scanner::generate_server_code!("./protocols/archphene-gpu-present-v1.xml");
}

#[cfg(test)]
pub(crate) mod client {
    use wayland_client;
    use wayland_client::protocol::*;

    pub(crate) mod __interfaces {
        use wayland_client::protocol::__interfaces::*;
        wayland_scanner::generate_interfaces!("./protocols/archphene-gpu-present-v1.xml");
    }
    use self::__interfaces::*;
    wayland_scanner::generate_client_code!("./protocols/archphene-gpu-present-v1.xml");
}
