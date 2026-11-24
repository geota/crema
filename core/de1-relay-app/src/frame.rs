//! The relay's application frames: one JSON object per relay message,
//! tagged by `"type"`, its fields inline (`{"type":"attach","id":7,
//! "address":"…"}`). The wire is byte for byte what the Android shell's
//! kotlinx codec wrote and read: `tests/golden/v4/frames/` and `unknown/`
//! pin it, and the Android parity test compares this codec with the old
//! kotlinx one on hostile input.
//!
//! - [`encode_frame`]: fields in declaration order; a field at its default
//!   (`args` empty, `result` or `src` absent) stays off the wire.
//! - [`decode_frame`]: the relay v4 decode (spec §4.5). A frame of an
//!   unknown `type` is [`Decoded::Unknown`], to skip, so a later v4.x can add
//!   frames; malformed JSON, a missing or non-string `type`, or a known type
//!   with bad fields is an error, which closes the session. Unknown fields
//!   are ignored. Fields read as kotlinx read them ([`crate::json`]): a
//!   number may be quoted or carry an integral exponent; a string must be
//!   quoted; a duplicate key keeps its last value.

use crate::json::{self, Value};

/// A device the hub holds, as a roster lists it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeviceInfo {
    /// The hub's Bluetooth address for it.
    pub address: String,
    /// Its advertised name.
    pub name: String,
    /// `"de1"` or `"scale"` (informational).
    pub kind: String,
    /// Its connection state's name.
    pub state: String,
}

/// One relay application frame. Field names are the wire's, in snake case.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Frame {
    /// The hub's first frame on an admitted session.
    Welcome {
        primary_name: String,
        authority: String,
        roster: Vec<DeviceInfo>,
        /// `"control"` or `"mirror"`; required, and anything but
        /// `"control"` is view-only.
        scope: String,
    },
    /// The devices the hub holds, pushed when they change.
    Roster {
        devices: Vec<DeviceInfo>,
    },
    Attach {
        id: i64,
        address: String,
    },
    Attached {
        id: i64,
        address: String,
        state: String,
    },
    Detach {
        id: i64,
        address: String,
    },
    Detached {
        id: i64,
        address: String,
    },
    Read {
        id: i64,
        address: String,
        service: String,
        char: String,
    },
    ReadOk {
        id: i64,
        hex: String,
    },
    ReadErr {
        id: i64,
        reason: String,
    },
    Write {
        id: i64,
        address: String,
        service: String,
        char: String,
        hex: String,
    },
    WriteOk {
        id: i64,
    },
    WriteErr {
        id: i64,
        reason: String,
    },
    /// A relayed control ([`crate::decode_command`] reads `method` and `args`).
    Control {
        id: i64,
        method: String,
        args: String,
    },
    ControlOk {
        id: i64,
        result: Option<String>,
    },
    ControlErr {
        id: i64,
        reason: String,
    },
    Notify {
        address: String,
        service: String,
        char: String,
        hex: String,
        t: i64,
        src: Option<String>,
    },
    State {
        address: String,
        state: String,
    },
    /// The hub's session config: an app-level JSON blob, carried as text.
    Config {
        json: String,
    },
    /// The hub's grinder block on its own (grinder-ble §6.2): an app-level
    /// JSON blob like [`Frame::Config`]'s, numbered from the same counter, so
    /// a turning collar doesn't re-send the whole snapshot. A remote that
    /// doesn't know it skips it and keeps the grinder of the last snapshot.
    Grinder {
        json: String,
    },
    /// A display-only "who drove the machine" notice.
    Event {
        text: String,
    },
}

/// Every frame `type` this codec knows.
pub const FRAME_TYPES: [&str; 20] = [
    "welcome",
    "roster",
    "attach",
    "attached",
    "detach",
    "detached",
    "read",
    "readOk",
    "readErr",
    "write",
    "writeOk",
    "writeErr",
    "control",
    "controlOk",
    "controlErr",
    "notify",
    "state",
    "config",
    "grinder",
    "event",
];

/// What a relay message decodes to.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Decoded {
    /// A known frame.
    Frame(Frame),
    /// A frame of a `type` this codec doesn't know: skip it.
    Unknown(String),
}

/// Why a relay message isn't a frame. Each closes the session; the kinds
/// keep the exceptions the Android shell's kotlinx decode threw apart.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FrameError {
    /// Valid JSON, but not an object.
    NotAnObject,
    /// An object without a string `type`.
    NoType,
    /// Malformed JSON, or a known type with missing or bad fields.
    Malformed(String),
}

impl std::fmt::Display for FrameError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::NotAnObject => f.write_str("a frame must be a JSON object"),
            Self::NoType => f.write_str("frame without a type"),
            Self::Malformed(why) => write!(f, "malformed frame: {why}"),
        }
    }
}

impl std::error::Error for FrameError {}

impl Frame {
    /// The frame's `type` on the wire.
    #[must_use]
    pub fn type_name(&self) -> &'static str {
        match self {
            Self::Welcome { .. } => "welcome",
            Self::Roster { .. } => "roster",
            Self::Attach { .. } => "attach",
            Self::Attached { .. } => "attached",
            Self::Detach { .. } => "detach",
            Self::Detached { .. } => "detached",
            Self::Read { .. } => "read",
            Self::ReadOk { .. } => "readOk",
            Self::ReadErr { .. } => "readErr",
            Self::Write { .. } => "write",
            Self::WriteOk { .. } => "writeOk",
            Self::WriteErr { .. } => "writeErr",
            Self::Control { .. } => "control",
            Self::ControlOk { .. } => "controlOk",
            Self::ControlErr { .. } => "controlErr",
            Self::Notify { .. } => "notify",
            Self::State { .. } => "state",
            Self::Config { .. } => "config",
            Self::Grinder { .. } => "grinder",
            Self::Event { .. } => "event",
        }
    }
}

/// Builds one frame object: `{"type":…`, then fields, then `}`.
struct Writer(String);

impl Writer {
    fn new(frame: &Frame) -> Self {
        let mut out = String::from("{\"type\":");
        json::write_string(&mut out, frame.type_name());
        Self(out)
    }

    fn key(&mut self, key: &str) {
        self.0.push(',');
        json::write_string(&mut self.0, key);
        self.0.push(':');
    }

    fn str(&mut self, key: &str, value: &str) -> &mut Self {
        self.key(key);
        json::write_string(&mut self.0, value);
        self
    }

    fn opt(&mut self, key: &str, value: Option<&str>) -> &mut Self {
        if let Some(v) = value {
            self.str(key, v);
        }
        self
    }

    fn long(&mut self, key: &str, value: i64) -> &mut Self {
        self.key(key);
        self.0.push_str(&value.to_string());
        self
    }

    fn devices(&mut self, key: &str, devices: &[DeviceInfo]) -> &mut Self {
        self.key(key);
        self.0.push('[');
        for (i, d) in devices.iter().enumerate() {
            if i > 0 {
                self.0.push(',');
            }
            self.0.push_str("{\"address\":");
            json::write_string(&mut self.0, &d.address);
            self.0.push_str(",\"name\":");
            json::write_string(&mut self.0, &d.name);
            self.0.push_str(",\"kind\":");
            json::write_string(&mut self.0, &d.kind);
            self.0.push_str(",\"state\":");
            json::write_string(&mut self.0, &d.state);
            self.0.push('}');
        }
        self.0.push(']');
        self
    }

    fn done(&mut self) -> String {
        self.0.push('}');
        std::mem::take(&mut self.0)
    }
}

/// `frame`'s wire text.
#[must_use]
pub fn encode_frame(frame: &Frame) -> String {
    let mut w = Writer::new(frame);
    match frame {
        Frame::Welcome {
            primary_name,
            authority,
            roster,
            scope,
        } => w
            .str("primaryName", primary_name)
            .str("authority", authority)
            .devices("roster", roster)
            .str("scope", scope),
        Frame::Roster { devices } => w.devices("devices", devices),
        Frame::Attach { id, address }
        | Frame::Detach { id, address }
        | Frame::Detached { id, address } => w.long("id", *id).str("address", address),
        Frame::Attached { id, address, state } => w
            .long("id", *id)
            .str("address", address)
            .str("state", state),
        Frame::Read {
            id,
            address,
            service,
            char,
        } => w
            .long("id", *id)
            .str("address", address)
            .str("service", service)
            .str("char", char),
        Frame::ReadOk { id, hex } => w.long("id", *id).str("hex", hex),
        Frame::ReadErr { id, reason }
        | Frame::WriteErr { id, reason }
        | Frame::ControlErr { id, reason } => w.long("id", *id).str("reason", reason),
        Frame::Write {
            id,
            address,
            service,
            char,
            hex,
        } => w
            .long("id", *id)
            .str("address", address)
            .str("service", service)
            .str("char", char)
            .str("hex", hex),
        Frame::WriteOk { id } => w.long("id", *id),
        Frame::Control { id, method, args } => {
            let w = w.long("id", *id).str("method", method);
            if args.is_empty() {
                w
            } else {
                w.str("args", args)
            }
        }
        Frame::ControlOk { id, result } => w.long("id", *id).opt("result", result.as_deref()),
        Frame::Notify {
            address,
            service,
            char,
            hex,
            t,
            src,
        } => w
            .str("address", address)
            .str("service", service)
            .str("char", char)
            .str("hex", hex)
            .long("t", *t)
            .opt("src", src.as_deref()),
        Frame::State { address, state } => w.str("address", address).str("state", state),
        Frame::Config { json } | Frame::Grinder { json } => w.str("json", json),
        Frame::Event { text } => w.str("text", text),
    }
    .done()
}

/// Reads one frame's fields as kotlinx's tree decoder did.
struct Fields<'a> {
    obj: &'a Value,
    type_name: &'a str,
}

fn bad<T>(why: String) -> Result<T, FrameError> {
    Err(FrameError::Malformed(why))
}

impl Fields<'_> {
    fn field(&self, key: &str) -> Option<&Value> {
        self.obj.get(key)
    }

    fn missing<T>(&self, key: &str) -> Result<T, FrameError> {
        bad(format!("{}: field '{key}' is required", self.type_name))
    }

    /// A string field: a quoted string; `null`, a bare literal or a
    /// structure is refused.
    fn str(&self, key: &str) -> Result<String, FrameError> {
        match self.field(key) {
            None => self.missing(key),
            Some(v) => string_value(v, self.type_name, key),
        }
    }

    /// A string field that defaults to `""` when absent (`null` is refused).
    fn str_or_empty(&self, key: &str) -> Result<String, FrameError> {
        match self.field(key) {
            None => Ok(String::new()),
            Some(v) => string_value(v, self.type_name, key),
        }
    }

    /// An optional string: absent or `null` is `None`.
    fn opt(&self, key: &str) -> Result<Option<String>, FrameError> {
        match self.field(key) {
            None | Some(Value::Null) => Ok(None),
            Some(v) => string_value(v, self.type_name, key).map(Some),
        }
    }

    /// A Long field: any primitive whose text kotlinx reads as a Long.
    fn long(&self, key: &str) -> Result<i64, FrameError> {
        let content = match self.field(key) {
            None => return self.missing(key),
            Some(Value::Null) => "null",
            Some(Value::Literal { content, .. }) => content.as_str(),
            Some(_) => return bad(format!("{}: '{key}' must be a number", self.type_name)),
        };
        json::parse_long(content).or_else(|e| bad(format!("{}: '{key}': {e}", self.type_name)))
    }

    fn devices(&self, key: &str) -> Result<Vec<DeviceInfo>, FrameError> {
        let Some(v) = self.field(key) else {
            return self.missing(key);
        };
        let Value::Array(items) = v else {
            return bad(format!("{}: '{key}' must be an array", self.type_name));
        };
        items
            .iter()
            .map(|item| {
                if !matches!(item, Value::Object(_)) {
                    return bad(format!("{}: '{key}' holds a non-object", self.type_name));
                }
                let d = Fields {
                    obj: item,
                    type_name: "DeviceInfo",
                };
                Ok(DeviceInfo {
                    address: d.str("address")?,
                    name: d.str("name")?,
                    kind: d.str("kind")?,
                    state: d.str("state")?,
                })
            })
            .collect()
    }
}

fn string_value(v: &Value, type_name: &str, key: &str) -> Result<String, FrameError> {
    match v {
        Value::Literal {
            content,
            quoted: true,
        } => Ok(content.clone()),
        _ => bad(format!("{type_name}: '{key}' must be a quoted string")),
    }
}

/// Decode one relay message (spec §4.5). See the module docs.
///
/// # Errors
/// [`FrameError`] for anything that must close the session.
pub fn decode_frame(text: &str) -> Result<Decoded, FrameError> {
    let value = json::parse(text).map_err(|e| FrameError::Malformed(e.0))?;
    if !matches!(value, Value::Object(_)) {
        return Err(FrameError::NotAnObject);
    }
    let type_name = match value.get("type") {
        Some(Value::Literal {
            content,
            quoted: true,
        }) => content.as_str(),
        _ => return Err(FrameError::NoType),
    };
    if !FRAME_TYPES.contains(&type_name) {
        return Ok(Decoded::Unknown(type_name.to_owned()));
    }
    let f = Fields {
        obj: &value,
        type_name,
    };
    let frame = match type_name {
        "welcome" => Frame::Welcome {
            primary_name: f.str("primaryName")?,
            authority: f.str("authority")?,
            roster: f.devices("roster")?,
            scope: f.str("scope")?,
        },
        "roster" => Frame::Roster {
            devices: f.devices("devices")?,
        },
        "attach" => Frame::Attach {
            id: f.long("id")?,
            address: f.str("address")?,
        },
        "attached" => Frame::Attached {
            id: f.long("id")?,
            address: f.str("address")?,
            state: f.str("state")?,
        },
        "detach" => Frame::Detach {
            id: f.long("id")?,
            address: f.str("address")?,
        },
        "detached" => Frame::Detached {
            id: f.long("id")?,
            address: f.str("address")?,
        },
        "read" => Frame::Read {
            id: f.long("id")?,
            address: f.str("address")?,
            service: f.str("service")?,
            char: f.str("char")?,
        },
        "readOk" => Frame::ReadOk {
            id: f.long("id")?,
            hex: f.str("hex")?,
        },
        "readErr" => Frame::ReadErr {
            id: f.long("id")?,
            reason: f.str("reason")?,
        },
        "write" => Frame::Write {
            id: f.long("id")?,
            address: f.str("address")?,
            service: f.str("service")?,
            char: f.str("char")?,
            hex: f.str("hex")?,
        },
        "writeOk" => Frame::WriteOk { id: f.long("id")? },
        "writeErr" => Frame::WriteErr {
            id: f.long("id")?,
            reason: f.str("reason")?,
        },
        "control" => Frame::Control {
            id: f.long("id")?,
            method: f.str("method")?,
            args: f.str_or_empty("args")?,
        },
        "controlOk" => Frame::ControlOk {
            id: f.long("id")?,
            result: f.opt("result")?,
        },
        "controlErr" => Frame::ControlErr {
            id: f.long("id")?,
            reason: f.str("reason")?,
        },
        "notify" => Frame::Notify {
            address: f.str("address")?,
            service: f.str("service")?,
            char: f.str("char")?,
            hex: f.str("hex")?,
            t: f.long("t")?,
            src: f.opt("src")?,
        },
        "state" => Frame::State {
            address: f.str("address")?,
            state: f.str("state")?,
        },
        "config" => Frame::Config {
            json: f.str("json")?,
        },
        "grinder" => Frame::Grinder {
            json: f.str("json")?,
        },
        "event" => Frame::Event {
            text: f.str("text")?,
        },
        other => return Ok(Decoded::Unknown(other.to_owned())),
    };
    Ok(Decoded::Frame(frame))
}
