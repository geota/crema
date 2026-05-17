//! Standalone `uniffi-bindgen` CLI — generates the foreign-language bindings.
//!
//! After building the `cdylib`, generate the Kotlin bindings with:
//!
//! ```text
//! cargo run --bin uniffi-bindgen -- generate \
//!     --library target/debug/libde1_ffi.dylib \
//!     --language kotlin --out-dir <dir>
//! ```

fn main() {
    uniffi::uniffi_bindgen_main();
}
