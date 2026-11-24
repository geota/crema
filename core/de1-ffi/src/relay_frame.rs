//! The relay's application frames for the Android shell: a UniFFI mirror
//! of [`de1_relay_app::Frame`] and its codec. Kotlin's `FrameCodec`
//! delegates here, so the wire and what is accepted are the core's.
//!
//! Kotlin strings cross as UTF-8: the shell writes a lone surrogate as `?`
//! before encoding (what its UTF-8 encoder wrote for one on the wire), and
//! the decoder reads an escaped lone surrogate as U+FFFD.

use de1_relay_app as app;

/// A device the hub holds ([`app::DeviceInfo`]).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct RelayDeviceInfo {
    /// The hub's address for it.
    pub address: String,
    /// Its advertised name.
    pub name: String,
    /// `"de1"` or `"scale"`.
    pub kind: String,
    /// Its connection state.
    pub state: String,
}

impl From<RelayDeviceInfo> for app::DeviceInfo {
    fn from(d: RelayDeviceInfo) -> Self {
        Self {
            address: d.address,
            name: d.name,
            kind: d.kind,
            state: d.state,
        }
    }
}

impl From<app::DeviceInfo> for RelayDeviceInfo {
    fn from(d: app::DeviceInfo) -> Self {
        Self {
            address: d.address,
            name: d.name,
            kind: d.kind,
            state: d.state,
        }
    }
}

/// One relay frame ([`app::Frame`]); fields as the wire names them.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum RelayFrame {
    /// The hub's first frame.
    Welcome {
        primary_name: String,
        authority: String,
        roster: Vec<RelayDeviceInfo>,
        scope: String,
    },
    /// The hub's devices.
    Roster { devices: Vec<RelayDeviceInfo> },
    /// Attach to a device.
    Attach { id: i64, address: String },
    /// Attached.
    Attached {
        id: i64,
        address: String,
        state: String,
    },
    /// Detach from a device.
    Detach { id: i64, address: String },
    /// Detached.
    Detached { id: i64, address: String },
    /// A GATT read.
    Read {
        id: i64,
        address: String,
        service: String,
        char: String,
    },
    /// A read's result.
    ReadOk { id: i64, hex: String },
    /// A failed read.
    ReadErr { id: i64, reason: String },
    /// A GATT write.
    Write {
        id: i64,
        address: String,
        service: String,
        char: String,
        hex: String,
    },
    /// A completed write.
    WriteOk { id: i64 },
    /// A refused or failed write.
    WriteErr { id: i64, reason: String },
    /// A relayed control.
    Control {
        id: i64,
        method: String,
        args: String,
    },
    /// An accepted control.
    ControlOk { id: i64, result: Option<String> },
    /// A refused control.
    ControlErr { id: i64, reason: String },
    /// A device notification.
    Notify {
        address: String,
        service: String,
        char: String,
        hex: String,
        t: i64,
        src: Option<String>,
    },
    /// A connection state change.
    State { address: String, state: String },
    /// The hub's session config.
    Config { json: String },
    /// The hub's grinder block on its own.
    Grinder { json: String },
    /// A who-drove-it notice.
    Event { text: String },
}

impl From<RelayFrame> for app::Frame {
    fn from(f: RelayFrame) -> Self {
        match f {
            RelayFrame::Welcome {
                primary_name,
                authority,
                roster,
                scope,
            } => Self::Welcome {
                primary_name,
                authority,
                roster: roster.into_iter().map(Into::into).collect(),
                scope,
            },
            RelayFrame::Roster { devices } => Self::Roster {
                devices: devices.into_iter().map(Into::into).collect(),
            },
            RelayFrame::Attach { id, address } => Self::Attach { id, address },
            RelayFrame::Attached { id, address, state } => Self::Attached { id, address, state },
            RelayFrame::Detach { id, address } => Self::Detach { id, address },
            RelayFrame::Detached { id, address } => Self::Detached { id, address },
            RelayFrame::Read {
                id,
                address,
                service,
                char,
            } => Self::Read {
                id,
                address,
                service,
                char,
            },
            RelayFrame::ReadOk { id, hex } => Self::ReadOk { id, hex },
            RelayFrame::ReadErr { id, reason } => Self::ReadErr { id, reason },
            RelayFrame::Write {
                id,
                address,
                service,
                char,
                hex,
            } => Self::Write {
                id,
                address,
                service,
                char,
                hex,
            },
            RelayFrame::WriteOk { id } => Self::WriteOk { id },
            RelayFrame::WriteErr { id, reason } => Self::WriteErr { id, reason },
            RelayFrame::Control { id, method, args } => Self::Control { id, method, args },
            RelayFrame::ControlOk { id, result } => Self::ControlOk { id, result },
            RelayFrame::ControlErr { id, reason } => Self::ControlErr { id, reason },
            RelayFrame::Notify {
                address,
                service,
                char,
                hex,
                t,
                src,
            } => Self::Notify {
                address,
                service,
                char,
                hex,
                t,
                src,
            },
            RelayFrame::State { address, state } => Self::State { address, state },
            RelayFrame::Config { json } => Self::Config { json },
            RelayFrame::Grinder { json } => Self::Grinder { json },
            RelayFrame::Event { text } => Self::Event { text },
        }
    }
}

impl From<app::Frame> for RelayFrame {
    fn from(f: app::Frame) -> Self {
        match f {
            app::Frame::Welcome {
                primary_name,
                authority,
                roster,
                scope,
            } => Self::Welcome {
                primary_name,
                authority,
                roster: roster.into_iter().map(Into::into).collect(),
                scope,
            },
            app::Frame::Roster { devices } => Self::Roster {
                devices: devices.into_iter().map(Into::into).collect(),
            },
            app::Frame::Attach { id, address } => Self::Attach { id, address },
            app::Frame::Attached { id, address, state } => Self::Attached { id, address, state },
            app::Frame::Detach { id, address } => Self::Detach { id, address },
            app::Frame::Detached { id, address } => Self::Detached { id, address },
            app::Frame::Read {
                id,
                address,
                service,
                char,
            } => Self::Read {
                id,
                address,
                service,
                char,
            },
            app::Frame::ReadOk { id, hex } => Self::ReadOk { id, hex },
            app::Frame::ReadErr { id, reason } => Self::ReadErr { id, reason },
            app::Frame::Write {
                id,
                address,
                service,
                char,
                hex,
            } => Self::Write {
                id,
                address,
                service,
                char,
                hex,
            },
            app::Frame::WriteOk { id } => Self::WriteOk { id },
            app::Frame::WriteErr { id, reason } => Self::WriteErr { id, reason },
            app::Frame::Control { id, method, args } => Self::Control { id, method, args },
            app::Frame::ControlOk { id, result } => Self::ControlOk { id, result },
            app::Frame::ControlErr { id, reason } => Self::ControlErr { id, reason },
            app::Frame::Notify {
                address,
                service,
                char,
                hex,
                t,
                src,
            } => Self::Notify {
                address,
                service,
                char,
                hex,
                t,
                src,
            },
            app::Frame::State { address, state } => Self::State { address, state },
            app::Frame::Config { json } => Self::Config { json },
            app::Frame::Grinder { json } => Self::Grinder { json },
            app::Frame::Event { text } => Self::Event { text },
        }
    }
}

/// What a relay message decodes to ([`app::decode_frame`]).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum RelayFrameDecoded {
    /// A known frame.
    Frame {
        /// The frame.
        frame: RelayFrame,
    },
    /// A frame of a type this build doesn't know: skip it.
    Unknown {
        /// Its `type`.
        type_name: String,
    },
    /// Valid JSON, but not an object: close the session.
    NotAnObject,
    /// An object without a string `type`: close the session.
    NoType,
    /// Malformed, or a known type with bad fields: close the session.
    Malformed {
        /// What was wrong.
        detail: String,
    },
}

/// `frame`'s wire text. See [`app::encode_frame`].
#[uniffi::export]
pub fn relay_encode_frame(frame: RelayFrame) -> String {
    app::encode_frame(&frame.into())
}

/// Decode one relay message. See [`app::decode_frame`].
#[uniffi::export]
pub fn relay_decode_frame(text: String) -> RelayFrameDecoded {
    match app::decode_frame(&text) {
        Ok(app::frame::Decoded::Frame(f)) => RelayFrameDecoded::Frame { frame: f.into() },
        Ok(app::frame::Decoded::Unknown(type_name)) => RelayFrameDecoded::Unknown { type_name },
        Err(app::FrameError::NotAnObject) => RelayFrameDecoded::NotAnObject,
        Err(app::FrameError::NoType) => RelayFrameDecoded::NoType,
        Err(app::FrameError::Malformed(detail)) => RelayFrameDecoded::Malformed { detail },
    }
}

/// Every frame `type` the codec knows.
#[uniffi::export]
pub fn relay_frame_types() -> Vec<String> {
    app::FRAME_TYPES.iter().map(|t| (*t).to_owned()).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_frame_crosses_both_ways() {
        let frame = RelayFrame::Roster {
            devices: vec![RelayDeviceInfo {
                address: "a".into(),
                name: "DE1".into(),
                kind: "de1".into(),
                state: "CONNECTED".into(),
            }],
        };
        let text = relay_encode_frame(frame.clone());
        assert_eq!(
            text,
            r#"{"type":"roster","devices":[{"address":"a","name":"DE1","kind":"de1","state":"CONNECTED"}]}"#
        );
        assert_eq!(relay_decode_frame(text), RelayFrameDecoded::Frame { frame });
        assert_eq!(
            relay_decode_frame("[]".into()),
            RelayFrameDecoded::NotAnObject
        );
        assert_eq!(
            relay_decode_frame(r#"{"type":"x"}"#.into()),
            RelayFrameDecoded::Unknown {
                type_name: "x".into()
            }
        );
        assert_eq!(relay_frame_types().len(), 20);
    }
}
