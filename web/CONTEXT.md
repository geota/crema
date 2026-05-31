# Web

The SvelteKit front-end for Crema: the brew dashboard, history, bean/profile
libraries, and settings. It drives the DE1 espresso machine and the scale over
Web Bluetooth (through the Rust-WASM `core`), persists locally, and syncs to the
Visualizer cloud service. Async I/O is structured with Effect-TS.

## Language

### Visualizer sync

**Visualizer**:
The external coffee-shot service at `visualizer.coffee` that Crema uploads shots,
beans, and roasters to and pulls them back from. A signed-in user with the premium
tier can write; free-tier accounts are read-only.
_Avoid_: the server, the cloud, the backend.

**visualizerCall**:
The single authenticated entry point to **Visualizer** (`services/visualizer-call.ts`).
Every request funnels through it: it attaches a fresh access token (refresh-once on
401), maps each HTTP status onto the **VisualizerCallError** taxonomy (402/403 →
premium-gated, 404 → not-found, 204 → null), and decodes the body. The sync modules
(`ShotSync`, `BeanSync`) and the `UploadQueue`'s retry policy all sit on it.
_Avoid_: the fetch wrapper, the API client (that lower layer is `HttpClient`).

**VisualizerCallError**:
The closed union of typed failures a **visualizerCall** can surface — premium-gated,
not-found, not-authenticated, token-refresh-failed, network, and raw HTTP-status. A
subset is **recoverable** (network / 5xx / 408 / status-0): worth a time-based retry
through the `UploadQueue`. The rest need user action (sign in, upgrade) and are
terminal.
_Avoid_: API error, sync error.

## Example dialogue

> **Dev:** When a shot upload gets a 402, do we retry it?
> **Domain expert:** No — a 402 from a **visualizerCall** maps to the premium-gated
> arm of **VisualizerCallError**, which isn't **recoverable**. The shot stays local
> and the user sees "premium required". We only re-queue the recoverable ones —
> a dropped connection, a 503, a timeout.
> **Dev:** And the 401 path?
> **Domain expert:** That's handled inside the **visualizerCall** itself — it
> refreshes the token once and retries before the error ever reaches you. You only
> see not-authenticated if the refresh also fails.
