# Crema relay protocol v4

Normative description of the wire format and the cryptography implemented by `de1-relay`. It follows the relay
v4 spec (`.scratch/relay-v4/SPEC.md`, §2 and §4); where the implementation pinned down something the spec left
open, or had to differ from it, this document says so under **Implementation notes** at the end. Keep it in sync
with the code: `tests/vectors/v4.json` is the byte-level reference.

Words: the **hub** holds the DE1 and is the WebSocket server; a **remote** mirrors it and is the WebSocket
client, the Noise initiator and SPAKE2 side A. A **pin** is a stored peer static public key. **Pairing** is the
one-time code ceremony; a **resume** is every later connection. A **record** is one Noise transport message; a
**message** is one application payload (one `Frame`'s JSON), split into records when large.

## 1. Identities and keys

- Static key: X25519. Generated as 32 bytes from the seed's `"static"` stream (§7); the scalar multiplication
  clamps them.
- Device id: `SHA-256("CremaRelay v4 device id" ‖ static_public)[0..16]`, written as 32 lowercase hex characters.
  The short id (UI, NSD `kid`) is its first 8 characters. After every handshake each side checks
  `DeviceId::of(peer_static)` against the id the peer claimed.
- Low-order keys: a public key `P` is low-order when `X25519(k, P)` is all zero for a clamped `k` (equivalently,
  for every clamped `k`). That covers the five low-order u-coordinates (0, 1, p − 1 and the two of order 8), the
  non-canonical p and p + 1, and the same seven with bit 255 set. Every X25519 inside a handshake fails when its
  output is all zero (compared in constant time), and `PinSet` refuses low-order keys.

## 2. The pre-handshake hellos (plaintext, advisory)

### 2.1 ClientHello (remote → hub, the only JSON message)

A WebSocket text message, at most 1 024 bytes, in the v2/v3 `hello` shape so an old hub answers with its own
version denial. The remote sends exactly this field order:

```json
{"type":"hello","v":4,"vmin":4,"vmax":4,"role":"secondary","clientId":"<32 hex>","clientName":"Pixel 8","intent":"pair"}
{"type":"hello","v":4,"vmin":4,"vmax":4,"role":"secondary","clientId":"","clientName":"","intent":"resume"}
```

The hub validates the raw bytes as UTF-8 and parses them strictly: unknown fields are ignored; `type` must be
`"hello"`; `vmin`, `vmax` (0..255, `vmin ≤ vmax`), `role` (`"secondary"`), `clientId`, `clientName` and `intent`
(`"pair"`/`"resume"`) are required. For `pair`, `clientId` is 32 lowercase hex and `clientName` at most 64 bytes.
For `resume`, both are empty (the plaintext names nobody). Anything else is `MALFORMED`.

A hello without `vmax` and without `vmin` and with `v < 4` is a **legacy** (v2/v3) hello: the hub sends exactly
`{"type":"denied","reason":"version mismatch"}` as a text message and closes 4426. It never sends binary to it.

Version: the hub picks the highest version in both ranges. None → `ABORT(VERSION)` with the hub's max, close 4426.
A remote never retries with a lower range after `ABORT(VERSION)`, a legacy denial or any other failure.

### 2.2 ServerHello (hub → remote)

```
0x10 ‖ version u8 ‖ status u8 ‖ hub_id [16] ‖ secs_left u16 ‖ name_len u8 ‖ name ‖ ext_len u16 ‖ ext
```

Integers are big-endian. `name` is UTF-8, at most 64 bytes. `ext` is empty in v4.0; a receiver ignores its
content but it is bound like every other byte. All lengths must add up exactly.

| status | value | meaning | `secs_left` |
|---|---|---|---|
| `READY` | 1 | resume: go ahead | 0 |
| `AWAITING_CODE` | 2 | pair: slot granted | seconds left in the window |
| `PAIRING_CLOSED` | 3 | pair: window closed | 0 |
| `BUSY` | 4 | pair: another pairing holds the slot | 0 |
| `LOCKED_OUT` | 5 | pair: too many wrong codes | seconds left in the lockout |

A refusing status is followed by a close (4403). A remote accepts only `version` 4; any other version gets
`ABORT(VERSION)` and a close (4426).

## 3. Binary envelope

Every binary WebSocket message is `kind u8 ‖ body`, at most 65 536 bytes in all.

| kind | name | direction | body |
|---|---|---|---|
| `0x10` | `SERVER_HELLO` | H → R | §2.2 |
| `0x11` | `ABORT` | either, before the handshake completes | `reason u8 ‖ detail_len u8 ‖ detail` (ASCII, ≤ 64; for `VERSION` exactly one byte, the sender's max version) |
| `0x20` | `PAKE_A` | R → H | the 33-byte SPAKE2 message A |
| `0x21` | `PAKE_B` | H → R | the 33-byte SPAKE2 message B |
| `0x30` | `NOISE_HS` | either | one Noise handshake message of exactly its pattern's length (§5) |
| `0x40` | `RECORD` | either, after the handshake | one Noise transport message, 17..65 535 bytes |

`ABORT.reason`: 1 `PAIRING_CLOSED`, 2 `BUSY`, 3 `LOCKED_OUT`, 4 `CANCELLED`, 5 `TIMEOUT`, 6 `HANDSHAKE_FAILED`,
7 `HOST_MISMATCH`, 8 `VERSION`, 9 `MALFORMED`.

An unknown kind, a kind out of order for the phase (including an `ABORT` once the session is established), an
oversize message, or a text message after the ClientHello ends the connection (`ABORT(MALFORMED)` and 4400 while a handshake is still in progress on that side; 4400 alone
after it).

## 4. Pairing

```
R → H  ClientHello      intent=pair, clientId = R's id, clientName
H → R  ServerHello      AWAITING_CODE, H's id, secs_left
R → H  PAKE_A           (R generates and shows the code, then sends at once)
         … the hub user types the code …
H → R  PAKE_B
R → H  NOISE_HS #1      XXpsk3:  e                     (48 bytes)
H → R  NOISE_HS #2               e, ee, s, es          (96 bytes)
R → H  NOISE_HS #3               s, se, psk            (64 bytes)   H: AEAD failure = wrong code
         H: PairPending; the shell stores the pin, inserts it into the live PinSet, calls confirm_paired()
H → R  RECORD           inner PAIR_COMPLETE (empty)                 R: decrypts = confirmed
         both close 1000; R reconnects with a resume
```

1. **Code.** From the remote's `"code"` stream: draw `u32` values until one is below 4 294 000 000, take it
   modulo 10⁶, zero-pad to six ASCII digits. One code per attempt (connection); it never crosses the wire and
   lives `min(120 s, secs_left)` from display.
2. **Code entry.** Exactly six ASCII digits; anything else is refused without starting SPAKE2 and without
   counting as an attempt. SPAKE2 runs at most once per hub connection.
3. **SPAKE2.** RustCrypto `spake2` 0.4.0, `Ed25519Group` (Brian Warner's python-spake2 construction, not
   RFC 9382). Remote = side A, hub = side B, password = the six ASCII digits,
   `id_A = "CremaRelay v4 remote " ‖ remote_device_id`, `id_B = "CremaRelay v4 hub " ‖ hub_device_id` (raw 16-byte
   ids). The scalar comes from the connection's `"spake"` stream.
4. **Element check**, on every received SPAKE2 message, before `finish`, in this order: length 33 and side byte
   `'A'`/`'B'` as expected; bytes 1..33 decompress to a point P; P re-compresses to the same 32 bytes (rejects
   y ≥ p); P is not the identity; P is torsion-free. Any failure is `MALFORMED`. On the hub a bad `PAKE_A` ends
   the connection and frees the slot **without** counting a failure (no code was entered, no SPAKE2 ran).
5. **Prologue.** `pair_prologue = "CremaRelay v4 pair" ‖ 0x00 ‖ lp(client_hello) ‖ lp(server_hello) ‖ lp(pake_a)
   ‖ lp(pake_b)`, `lp(x) = u16_be(len(x)) ‖ x`. `client_hello` is the exact text bytes, `server_hello` the whole
   binary message including its kind byte, `pake_a`/`pake_b` the 33-byte SPAKE2 messages (without kind bytes).
6. **psk.** `HKDF-SHA256(salt = SHA-256(pair_prologue), ikm = spake2_output, info = "CremaRelay v4 psk" ‖
   remote_device_id ‖ hub_device_id, L = 32)`. The SPAKE2 output is used for nothing else.
7. **Noise.** `Noise_XXpsk3_25519_ChaChaPoly_SHA256`, psk at position 3, the static keys, the prologue, through
   the resolver of §7. Every handshake payload is empty and every message has its exact length (48 / 96 / 64);
   anything else is `MALFORMED`. After message 2 the remote checks the hub's static against the ServerHello's
   `hub_id`; after message 3 the hub checks the remote's static against `clientId` (`PeerIdMismatch`).
8. **Key confirmation and failure counting.** The hub confirms when message 3 decrypts. A message 3 that fails
   authentication after a code entry is `WrongCode`: the hub counts one failure, sends `ABORT(HANDSHAKE_FAILED)`
   and closes 4401. **This is the only event that counts.** A DH failure (low-order key), a malformed message, a
   timeout (including after code entry), a cancel, a closed window and `reject_pairing` free the slot uncounted.
   The remote confirms only when the `PAIR_COMPLETE` record decrypts.
9. **Two-phase commit on the hub.** On message 3 success the hub emits `PairPending`, exposes the remote's key and
   sends nothing. The shell persists the pin, inserts it into the live `PinSet`, and calls `confirm_paired()`.
   - `confirm_paired()` returns `Ok` **only** when it sends `PAIR_COMPLETE`: under one gate lock it checks that the
     connection still holds the pairing slot and records the success (closing the window); then it emits `Paired`
     and closes 1000.
   - Every other outcome is an `Err`: `InvalidState` while the key isn't in the live pin set (nothing changes, the
     shell may insert and retry) or outside `AwaitCommit`; `Timeout` when `PAIR_FINISH_MS` (10 s from code entry)
     has passed; `PairingClosed` when the gate was closed or reopened since; `Closed` when the attempt already ended
     (a cancel, a timeout, the remote's `ABORT`); `Poisoned` for a poisoned lock. When the call itself ended the
     attempt, its `ABORT`/close Step is held and returned by the next `tick()` (or any other call).
   - **Rollback rule (normative):** the shell keeps the pin only if `confirm_paired()` returned `Ok`. On any `Err`
     other than the retryable `InvalidState`, and on any `PairingFailed` after `PairPending`, it removes the key from
     the live `PinSet` and from the store, then applies `tick()`'s Step.
   - If the write fails the shell calls `reject_pairing()` instead: `ABORT(CANCELLED)`, close 4403, no failure.
   - The remote pins on `PAIR_COMPLETE`.

## 5. Resume

```
R → H  ClientHello      intent=resume, clientId="", clientName=""
H → R  ServerHello      READY, H's id               R: id ≠ pin → ABORT(HOST_MISMATCH), close 4401; no Noise
R → H  NOISE_HS #1      IK: e, es, s, ss   (96 bytes)
         H reads it, takes R's static, looks it up in the LIVE pin set
H → R  NOISE_HS #2      IK: e, ee, se      (48 bytes)  R: decrypts = hub authenticated
         unknown R: H also sends an authenticated CLOSE(NOT_PAIRED) record and closes 4403
R → H  RECORD           inner HEARTBEAT ("client finished", must be first)
         H re-reads the live pin set: present → admitted (Established); removed → CLOSE(REVOKED), 4403
H → R  RECORD           FRAME Welcome, then the session
```

- `resume_prologue = "CremaRelay v4 resume" ‖ 0x00 ‖ lp(client_hello) ‖ lp(server_hello)`. In both prologues a
  field longer than 65 535 bytes can't be encoded and is `MALFORMED` (never truncated).
- The hub's pins are read when message 1 arrives and again at admission, never snapshotted at accept time.
- **Admission rule.** Nothing with a side effect happens before the remote's first record decrypts as a
  `HEARTBEAT` (its keys need the remote's ephemeral and the hub's fresh one). Any other first record closes 4400.
  A replayed message 1 gets a message 2 and then times out (`RESUME_DEADLINE_MS` = 5 s after the ServerHello,
  close 4408) with nothing admitted.
- A failure to read message 1 (a rewritten hello, the wrong hub key, a low-order key) is
  `ABORT(HANDSHAKE_FAILED)`, 4401. A failure to read message 2 on the remote is `HubUnverified` (advisory).

## 6. Transport

After a handshake each direction has its own CipherState (snow's `TransportState`).

- **Inner plaintext**: `flags u8 ‖ type u8 ‖ payload`. `flags` bit 0 = `MORE`; bits 1–7 must be zero.

  | type | name | payload |
  |---|---|---|
  | `0x01` | `FRAME` | UTF-8 JSON of one `Frame`; the only fragmentable type |
  | `0x02` | `HEARTBEAT` | empty |
  | `0x03` | `PAIR_COMPLETE` | empty; hub → remote, pairing only |
  | `0x04` | `CLOSE` | `code u8 ‖ text_len u8 ‖ text` (UTF-8, ≤ 64); 1 `REVOKED`, 2 `NOT_PAIRED`, 3 `HUB_STOPPED`, 4 `PROTOCOL`, 5 `NORMAL` |

- **Canonical fragmentation.** A message of 0..65 517 bytes is one `FRAME` record without `MORE`. A larger one is
  split so every record but the last carries exactly 65 517 bytes and `MORE`, and the last 1..65 517 bytes
  without it. A fragmented message's records are contiguous. `MORE` on any other type, a short non-final fragment,
  an empty final fragment, another type between fragments, an unknown type, a non-empty `HEARTBEAT` or
  `PAIR_COMPLETE`, or a malformed `CLOSE` closes 4400. Messages are at most 1 MiB: `send` refuses more, and a
  receiver closes 4400 as soon as a reassembly would exceed it. Nothing is reassembled before authentication; a
  session that ends drops its partial buffer.
- **Nonces.** Implicit per-direction counters from 0. Any decrypt failure is fatal: close 4401, nothing more is
  delivered, no resynchronisation. At 2⁶² records in a direction the connection gives up.
- **Rekey.** After the record with nonce `n` where `(n + 1) % 65 536 == 0`, the sender calls `rekey_outgoing()`
  and the receiver, after decrypting it, `rekey_incoming()`.
- **Liveness.** A side that has sent nothing for 10 s sends a `HEARTBEAT`. A side that received no complete
  message and no `HEARTBEAT` for 30 s closes 4408 (fragments alone don't count).
- **Ordering.** A call returns a message's records in order; the shell sends them contiguously, under one lock
  per connection, so encryption order equals wire order.
- **Channel binding.** `channel_binding()` is the Noise handshake hash `h` (32 bytes), read before the switch to
  transport mode. Anything bound to a session derives from it as `HKDF-SHA256(salt = h, info = "CremaRelay v4 " ‖
  purpose)`.

## 7. Randomness

The crate draws no entropy. Every key pair and connection is built from a 32-byte seed the bridge fills.
Sub-streams: `HKDF-SHA256(salt = none, ikm = seed, info = label)` keys a ChaCha20 generator, for the labels
`"static"` (key generation), `"code"` (the pairing code) and `"spake"` (the SPAKE2 scalar). snow's randomness
comes from our resolver: each `resolve_rng()` call returns a fresh stream keyed by
`HKDF-SHA256(ikm = seed, info = "noise" ‖ u64_be(counter))`, the counter shared by every builder made from the
connection's seed, so no two builders share ephemerals. DH, cipher and hash are snow's default (X25519 through
the all-zero check, ChaCha20-Poly1305, SHA-256).

Test seeds (vectors only, feature `test-seeds`): `SHA-256("de1-relay test seed " ‖ label ‖ " " ‖ n)`, `n` in
decimal.

## 8. Deadlines and close codes

| phase | deadline | on expiry |
|---|---|---|
| hub, waiting for the ClientHello | 5 s from accept | `ABORT(TIMEOUT)`, 4408 |
| hub, `AWAITING_CODE`, no `PAKE_A` | 5 s from the ServerHello (or the window's end) | `ABORT(TIMEOUT)`, 4408, slot freed |
| hub, `PAKE_A` in, no code | the window's end | `ABORT(TIMEOUT)`, 4408, slot freed |
| hub, code entered (handshake and commit) | 10 s from code entry | `ABORT(TIMEOUT)`, 4408, slot freed, not counted |
| hub, resume handshake | 5 s from the ServerHello | `ABORT(TIMEOUT)`, 4408 |
| hub, waiting for the client-finished record | same 5 s | 4408, nothing sent |
| remote, waiting for the ServerHello | 5 s | 4408, **nothing sent** (the hub may be legacy) |
| remote, code shown | `min(120 s, secs_left)` | `ABORT(TIMEOUT)`, 4408 |
| remote, pairing handshake and `PAIR_COMPLETE` | 10 s from `PAKE_B` | `ABORT(TIMEOUT)`, 4408 |
| remote, resume handshake | 5 s from the ServerHello | `ABORT(TIMEOUT)`, 4408 |
| established | heartbeat after 10 s idle; 30 s receive timeout | 4408 |

WebSocket close codes: 1000 normal (pairing done, `CLOSE(NORMAL/HUB_STOPPED)`, a peer's `ABORT`), 4400 protocol
error, 4401 authentication failed (handshake, record, wrong code, `HOST_MISMATCH`), 4403 refused (pairing closed,
busy, locked, cancelled, rejected, unknown or revoked remote), 4408 timeout, 4426 version (legacy or no common
version).

## 9. Refusals: authenticated or advisory

Only a `CLOSE` record (decrypted with the session's keys) may change trust state or role. `ABORT`, a ServerHello
status, a legacy denial, a failed IK message 2 and a `HOST_MISMATCH` are advisory: wording only.

## 10. Events (the shell contract)

Events cross as JSON `{"type": …, "content": {…}}`. Names are sanitized (control characters, format characters
such as bidi overrides and zero-width marks, U+2028 and U+2029 removed, trimmed, at most 32 characters, blank →
"Unnamed device"); keys never appear in events.

| event | side | when |
|---|---|---|
| `LegacyPeer{version, name}` | hub | a v2/v3 remote got the legacy denial |
| `PairRequest{remote_id, remote_name}` | hub | slot granted; show the code dialog |
| `PairPending{remote_id, remote_name}` | hub | the code matched; persist, insert into the pin set, `confirm_paired()` |
| `PairingFailed{reason, failures, locked}` | both | a pairing attempt ended without success. Hub reasons: `WrongCode` (the only counted one), `Timeout`, `Cancelled`, `Malformed`, `HandshakeFailed`, `PeerIdMismatch`, `LowOrderKey`, or an `ABORT` reason from the remote. Remote: `NotConfirmed` once message 3 is out, otherwise `Timeout`, `Malformed`, `HandshakeFailed`, `PeerIdMismatch` |
| `UnknownRemote{remote_id}` | hub | an unpinned remote resumed; it got `CLOSE(NOT_PAIRED)` |
| `HubHello{hub_id, hub_name, status, secs_left}` | remote | a ServerHello arrived (advisory) |
| `ShowCode{code, expires_at_ms}` | remote | show the code |
| `Refused{reason, authenticated}` | remote | the hub refused: `authenticated: true` for a `CLOSE` record (`Revoked`, `NotPaired`, `HubStopped`, `Protocol`, `Normal`); otherwise an `ABORT` reason, a ServerHello status, `HostMismatch`, `HubOutdated`, `HubUnverified`, `Version` or `Timeout` |
| `Paired{peer_id, peer_name}` | both | pairing completed |
| `Established{peer_id}` | both | hub: admitted; remote: hub authenticated (the shell says "Connected" only on the first `FRAME`) |
| `PeerClosed{reason}` | hub | the remote ended an established session with an authenticated `CLOSE` record (`Normal`, `Protocol`, …) |
| `Failed{error}` | both | the connection failed for a reason no other event covers; a plaintext `ABORT` after the session is established is `Failed{UnexpectedMessage}` with close 4400 |

On the remote, an `ABORT` that arrives during a pairing (after the ServerHello) is reported as follows:
`HANDSHAKE_FAILED` (the hub's wrong-code answer) or `MALFORMED` gives `PairingFailed` alone (reason
`NotConfirmed` once message 3 is out, otherwise the abort reason); any other reason gives `Refused{reason, false}`,
followed in `AwaitConfirm` by `PairingFailed{NotConfirmed}`. So a `PairingFailed` without a `Refused` in the same
step, other than `Timeout` (the code expired), is a failed attempt the shell may retry automatically (up to
`MAX_PAIR_ATTEMPTS`); a `Refused` or a `Timeout` is a stop.

## 11. Zeroization (best effort)

Our copies of the static private key, the SPAKE2 output, the psk, the HKDF outputs and sub-seeds, and the code
are wiped on drop, and their `Debug` prints `[redacted]`. Not covered: snow's internal copies (its static key, the
psk and chaining key in `HandshakeState`, the CipherState keys; snow 0.10 has no zeroization), `spake2`'s password
copy and scalars, the ChaCha20 generator states, and the bytes that cross UniFFI or wasm memory. An attacker who
can read process memory is out of scope.

## Implementation notes (where the code pins down or differs from the spec)

1. **XXpsk3 message 1 is 48 bytes, not 32.** In a psk handshake every `e` token also runs `MixKey(e)`, so message
   1's empty payload is encrypted and carries a 16-byte tag. The cacophony vector for this suite agrees (64-byte
   ciphertext for a 16-byte payload). Messages 2 and 3 (96, 64) and IK (96, 48) are as specified.
2. **Prologue PAKE fields** are the 33-byte SPAKE2 messages, without the envelope kind byte (the spec said "each
   hello is the exact byte string sent" and left the PAKE fields implicit).
3. **The Noise stream counter** is encoded as 8 bytes big-endian after `"noise"`.
4. **`confirm_paired()` checks the live pin set and the slot atomically.** It refuses with `InvalidState` until the
   peer's key is in it (the commit order of §4 step 9 is enforced by the core), checks the slot and records the
   success under one gate lock, and returns `Err` for every outcome that doesn't send `PAIR_COMPLETE`, so the
   shell knows to roll the pin back.
5. **The pending slot has a lease** (the holder's own deadline), and a `HubConnection` frees its slot on drop, so
   a connection the shell dropped without a terminal step can't keep pairing `BUSY`. Opening a new window (after
   the old one closed or ran out) bumps the generation, so a slot holder from the old window aborts.
6. **`enter_code` before `PAKE_A` arrived** is `InvalidState` (state unchanged); the remote sends `PAKE_A` with
   the code's display, before a user can type it. On an ended attempt it is `Err` (`Closed`, or `Timeout` /
   `PairingClosed` when the call itself ended it, its Step held for `tick()`), as for `confirm_paired()`.
7. **Events** use typeshare's adjacently tagged form `{"type", "content"}` (house style, as `CoreOutput`), not
   `#[serde(tag = "type")]` alone, which typeshare 1.x can't generate.
8. **Close codes** not fixed by the spec: a cancel, a reject and a refusing ServerHello status close 4403; a
   `HOST_MISMATCH` closes 4401; a peer's `ABORT` closes 1000.
9. **`LOCKED_OUT` ServerHello** carries the lockout's seconds left in `secs_left`.
10. **`PeerClosed{reason}`** is a hub event the spec doesn't list: the remote's authenticated `CLOSE` record.
11. **Names drop format characters** (Unicode Cf: bidi embeddings, overrides and isolates, zero-width spaces and
    joiners, the BOM, soft hyphen, tag characters) as well as control characters, so a name can't reorder or hide
    text in the hub's code dialog. Emoji sequences built with a zero-width joiner show as their parts.
