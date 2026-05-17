# Vendored DE1 profiles

The `*.tcl` files in this directory are the standard espresso/tea/cleaning
profiles shipped by the **de1app** project — the original Decent Espresso DE1
tablet application (`de1plus/profiles/`).

They are copied **verbatim**, with no edits, so the upstream files remain the
canonical source of truth. They are embedded into the `de1-domain` crate at
compile time (`include_str!`) and exposed as built-in Crema `Profile`s by the
[`builtin`](../src/builtin.rs) module.

## Origin

- Upstream project: <https://github.com/decentespresso/de1app>
- Path in upstream: `de1plus/profiles/`

## License

de1app is licensed **GPL-3.0**. Crema is licensed **GPL-3.0-or-later**, which
is license-compatible, so vendoring these files here is permitted. The
vendored files retain their upstream GPL-3.0 license.
