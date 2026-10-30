//! The pre-handshake hellos (§4.2, §4.3) and the legacy version check (§4.6).
//!
//! Both hellos are advisory: they are bound into the Noise prologue byte for
//! byte, so a rewrite in transit fails the handshake.

use serde::{Deserialize, Serialize};

use crate::{
    CLIENT_HELLO_MAX, DeviceId, NAME_MAX_BYTES, NAME_MAX_CHARS, PROTOCOL_VERSION, RelayError,
    VERSION_MAX, VERSION_MIN, wire::kind,
};

/// The exact text a v4 hub sends a v2/v3 remote before closing with 4426.
pub const LEGACY_DENIAL: &str = r#"{"type":"denied","reason":"version mismatch"}"#;

/// What the remote wants from this connection.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Intent {
    /// The one-time code ceremony.
    Pair,
    /// A reconnect with the pins (Noise IK).
    Resume,
}

impl Intent {
    fn as_str(self) -> &'static str {
        match self {
            Self::Pair => "pair",
            Self::Resume => "resume",
        }
    }
}

/// The remote's ClientHello: a JSON text in the v2/v3 `hello` shape, so an
/// old hub decodes it and answers with its own version denial.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ClientHello {
    /// Lowest version the remote speaks.
    pub vmin: u8,
    /// Highest version the remote speaks.
    pub vmax: u8,
    /// Pair or resume.
    pub intent: Intent,
    /// The remote's device id (pairing only; resume sends an empty `clientId`).
    pub client_id: Option<DeviceId>,
    /// The remote's display name (pairing only; empty on resume).
    pub client_name: String,
}

#[derive(Serialize)]
struct WireHello<'a> {
    #[serde(rename = "type")]
    kind: &'a str,
    v: u8,
    vmin: u8,
    vmax: u8,
    role: &'a str,
    #[serde(rename = "clientId")]
    client_id: &'a str,
    #[serde(rename = "clientName")]
    client_name: &'a str,
    intent: &'a str,
}

#[derive(Deserialize)]
struct RawHello {
    #[serde(rename = "type")]
    kind: String,
    v: u32,
    vmin: Option<u32>,
    vmax: Option<u32>,
    role: Option<String>,
    #[serde(rename = "clientId")]
    client_id: Option<String>,
    #[serde(rename = "clientName")]
    client_name: Option<String>,
    intent: Option<String>,
}

/// What a hub made of a ClientHello.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ParsedClientHello {
    /// A v4-format hello (its range may still share no version with ours).
    V4(ClientHello),
    /// A v2/v3 hello (`vmax` absent, `v` < 4).
    Legacy {
        /// The peer's `v`.
        version: u32,
        /// The peer's sanitized name.
        name: String,
    },
}

impl ClientHello {
    /// The hello for a pairing connection.
    pub fn pair(client_id: DeviceId, client_name: &str) -> Self {
        Self {
            vmin: VERSION_MIN,
            vmax: VERSION_MAX,
            intent: Intent::Pair,
            client_id: Some(client_id),
            client_name: wire_name(client_name),
        }
    }

    /// The hello for a resume: no id and no name, so the plaintext doesn't
    /// identify the remote (§2.6).
    pub fn resume() -> Self {
        Self {
            vmin: VERSION_MIN,
            vmax: VERSION_MAX,
            intent: Intent::Resume,
            client_id: None,
            client_name: String::new(),
        }
    }

    /// The exact text sent (and bound into the prologue).
    pub fn encode(&self) -> Vec<u8> {
        let id = self.client_id.map(|id| id.to_hex()).unwrap_or_default();
        let wire = WireHello {
            kind: "hello",
            v: PROTOCOL_VERSION,
            vmin: self.vmin,
            vmax: self.vmax,
            role: "secondary",
            client_id: &id,
            client_name: &self.client_name,
            intent: self.intent.as_str(),
        };
        // Serializing a struct of strings and integers can't fail.
        serde_json::to_vec(&wire).unwrap_or_else(|_| unreachable!("hello serializes"))
    }

    /// Parses the raw bytes of a ClientHello text frame with the strict schema
    /// of §4.2: unknown fields ignored, missing or ill-typed fields `Malformed`.
    ///
    /// # Errors
    /// [`RelayError::Malformed`] for anything that isn't a well-formed v4 or
    /// legacy hello.
    pub fn parse(raw: &[u8]) -> Result<ParsedClientHello, RelayError> {
        if raw.len() > CLIENT_HELLO_MAX {
            return Err(RelayError::Malformed("hello too large"));
        }
        let text = core::str::from_utf8(raw).map_err(|_| RelayError::Malformed("hello utf-8"))?;
        let raw: RawHello =
            serde_json::from_str(text).map_err(|_| RelayError::Malformed("hello json"))?;
        if raw.kind != "hello" {
            return Err(RelayError::Malformed("hello type"));
        }
        let Some(vmax) = raw.vmax else {
            if raw.vmin.is_none() && raw.v < u32::from(PROTOCOL_VERSION) {
                return Ok(ParsedClientHello::Legacy {
                    version: raw.v,
                    name: sanitize_name(raw.client_name.as_deref().unwrap_or("")),
                });
            }
            return Err(RelayError::Malformed("hello range"));
        };
        let vmin = raw.vmin.ok_or(RelayError::Malformed("hello vmin"))?;
        let (Ok(vmin), Ok(vmax)) = (u8::try_from(vmin), u8::try_from(vmax)) else {
            return Err(RelayError::Malformed("hello range"));
        };
        if vmin > vmax {
            return Err(RelayError::Malformed("hello range"));
        }
        if raw.role.as_deref() != Some("secondary") {
            return Err(RelayError::Malformed("hello role"));
        }
        let client_id = raw
            .client_id
            .ok_or(RelayError::Malformed("hello clientId"))?;
        let client_name = raw
            .client_name
            .ok_or(RelayError::Malformed("hello clientName"))?;
        let intent = match raw.intent.as_deref() {
            Some("pair") => Intent::Pair,
            Some("resume") => Intent::Resume,
            _ => return Err(RelayError::Malformed("hello intent")),
        };
        let client_id = match intent {
            Intent::Pair => {
                if client_name.len() > NAME_MAX_BYTES {
                    return Err(RelayError::Malformed("hello clientName length"));
                }
                Some(DeviceId::from_hex(&client_id)?)
            }
            Intent::Resume => {
                if !client_id.is_empty() || !client_name.is_empty() {
                    return Err(RelayError::Malformed("resume hello carries an identity"));
                }
                None
            }
        };
        Ok(ParsedClientHello::V4(ClientHello {
            vmin,
            vmax,
            intent,
            client_id,
            client_name,
        }))
    }

    /// The highest version both ranges share, if any (§4.6 downgrade rule).
    pub fn common_version(&self) -> Option<u8> {
        let lo = self.vmin.max(VERSION_MIN);
        let hi = self.vmax.min(VERSION_MAX);
        (lo <= hi).then_some(hi)
    }
}

/// `SERVER_HELLO.status` (§4.3).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum HelloStatus {
    /// Resume: go ahead with IK.
    Ready = 1,
    /// Pair: slot granted; show the code.
    AwaitingCode = 2,
    /// Pair: the window is closed.
    PairingClosed = 3,
    /// Pair: another pairing is in progress.
    Busy = 4,
    /// Pair: locked out after too many wrong codes.
    LockedOut = 5,
}

impl HelloStatus {
    fn from_u8(v: u8) -> Option<Self> {
        Some(match v {
            1 => Self::Ready,
            2 => Self::AwaitingCode,
            3 => Self::PairingClosed,
            4 => Self::Busy,
            5 => Self::LockedOut,
            _ => return None,
        })
    }

    /// The name used in events.
    pub fn name(self) -> &'static str {
        match self {
            Self::Ready => "Ready",
            Self::AwaitingCode => "AwaitingCode",
            Self::PairingClosed => "PairingClosed",
            Self::Busy => "Busy",
            Self::LockedOut => "LockedOut",
        }
    }
}

/// The hub's ServerHello: `0x10 ‖ version ‖ status ‖ hub_id[16] ‖ secs_left u16 ‖
/// name_len u8 ‖ name ‖ ext_len u16 ‖ ext`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ServerHello {
    /// The version the hub chose.
    pub version: u8,
    /// What the hub makes of the request.
    pub status: HelloStatus,
    /// The hub's device id.
    pub hub_id: DeviceId,
    /// Seconds left in the pairing window (`AwaitingCode`) or the lockout (`LockedOut`).
    pub secs_left: u16,
    /// The hub's display name (UTF-8, ≤ 64 bytes).
    pub name: String,
    /// Extensions: empty in v4.0; receivers ignore the content (it is still bound).
    pub ext: Vec<u8>,
}

impl ServerHello {
    /// The exact bytes sent (and bound into the prologue), kind byte included.
    pub fn encode(&self) -> Vec<u8> {
        let mut cut = self.name.len().min(NAME_MAX_BYTES);
        while !self.name.is_char_boundary(cut) {
            cut -= 1;
        }
        let name = &self.name.as_bytes()[..cut];
        let ext_len = u16::try_from(self.ext.len()).unwrap_or(u16::MAX);
        let ext = &self.ext[..usize::from(ext_len)];
        let mut out = Vec::with_capacity(24 + name.len() + ext.len());
        out.push(kind::SERVER_HELLO);
        out.push(self.version);
        out.push(self.status as u8);
        out.extend_from_slice(&self.hub_id.0);
        out.extend_from_slice(&self.secs_left.to_be_bytes());
        out.push(u8::try_from(name.len()).unwrap_or(0));
        out.extend_from_slice(name);
        out.extend_from_slice(&ext_len.to_be_bytes());
        out.extend_from_slice(ext);
        out
    }

    /// Parses a whole binary message whose kind byte is `SERVER_HELLO`.
    ///
    /// # Errors
    /// [`RelayError::Malformed`] unless every length is consistent, the status
    /// is known and the name is UTF-8 of at most 64 bytes.
    pub fn parse(msg: &[u8]) -> Result<Self, RelayError> {
        let bad = RelayError::Malformed("server hello");
        let [kind::SERVER_HELLO, version, status, rest @ ..] = msg else {
            return Err(bad);
        };
        let status = HelloStatus::from_u8(*status).ok_or(RelayError::Malformed("hello status"))?;
        let (hub_id, rest) = rest.split_at_checked(16).ok_or(bad.clone())?;
        let [s0, s1, name_len, rest @ ..] = rest else {
            return Err(bad);
        };
        let name_len = usize::from(*name_len);
        if name_len > NAME_MAX_BYTES {
            return Err(RelayError::Malformed("hello name length"));
        }
        let (name, rest) = rest.split_at_checked(name_len).ok_or(bad.clone())?;
        let [e0, e1, ext @ ..] = rest else {
            return Err(bad);
        };
        if usize::from(u16::from_be_bytes([*e0, *e1])) != ext.len() {
            return Err(bad);
        }
        let name = core::str::from_utf8(name).map_err(|_| RelayError::Malformed("hello name"))?;
        let mut id = [0_u8; 16];
        id.copy_from_slice(hub_id);
        Ok(Self {
            version: *version,
            status,
            hub_id: DeviceId(id),
            secs_left: u16::from_be_bytes([*s0, *s1]),
            name: name.to_owned(),
            ext: ext.to_vec(),
        })
    }
}

/// Whether a text from the hub is a v2/v3 `denied` frame.
pub(crate) fn is_legacy_denial(raw: &[u8]) -> bool {
    #[derive(Deserialize)]
    struct Denied {
        #[serde(rename = "type")]
        kind: String,
    }
    serde_json::from_slice::<Denied>(raw).is_ok_and(|d| d.kind == "denied")
}

/// The display-name rule (§3.4, formerly the shell's `sanitizePeerName`):
/// control and format characters and U+2028/U+2029 removed, trimmed, at most
/// 32 characters, and "Unnamed device" when nothing is left.
pub fn sanitize_name(name: &str) -> String {
    let filtered: String = name
        .chars()
        .filter(|&c| !c.is_control() && !is_format(c) && c != '\u{2028}' && c != '\u{2029}')
        .collect();
    let taken: String = filtered.trim().chars().take(NAME_MAX_CHARS).collect();
    if taken.trim().is_empty() {
        "Unnamed device".to_owned()
    } else {
        taken
    }
}

/// Unicode general category Cf (format characters): bidi embeddings, overrides
/// and isolates, zero-width spaces and joiners, the BOM, soft hyphen, tag
/// characters and the like. In a name they reorder or hide text, so the hub's
/// code dialog would show something other than what was sent.
fn is_format(c: char) -> bool {
    matches!(
        u32::from(c),
        0x00AD
            | 0x0600..=0x0605
            | 0x061C
            | 0x06DD
            | 0x070F
            | 0x0890..=0x0891
            | 0x08E2
            | 0x180E
            | 0x200B..=0x200F
            | 0x202A..=0x202E
            | 0x2060..=0x2064
            | 0x2066..=0x206F
            | 0xFEFF
            | 0xFFF9..=0xFFFB
            | 0x110BD
            | 0x110CD
            | 0x13430..=0x1343F
            | 0x1BCA0..=0x1BCA3
            | 0x1D173..=0x1D17A
            | 0xE0001
            | 0xE0020..=0xE007F
    )
}

/// A name as it goes on the wire: sanitized, then cut to 64 UTF-8 bytes on a
/// character boundary.
pub(crate) fn wire_name(name: &str) -> String {
    let mut s = sanitize_name(name);
    if s.len() > NAME_MAX_BYTES {
        let mut cut = NAME_MAX_BYTES;
        while !s.is_char_boundary(cut) {
            cut -= 1;
        }
        s.truncate(cut);
    }
    s
}
