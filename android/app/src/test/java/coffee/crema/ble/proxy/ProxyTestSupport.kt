package coffee.crema.ble.proxy

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Drive the handshake by hand from a raw client link: Hello, then answer the
 *  Challenge with [proof] (none = a request to pair), until the relay replies
 *  with a Welcome or a Denied. [rx] is the client's collected incoming frames. */
internal suspend fun handshake(client: FrameLink, rx: List<Frame>, clientId: String = "phone-1", proof: String? = null) {
    client.send(Frame.Hello(PROXY_PROTOCOL_VERSION, "secondary", clientId, "Phone"))
    withTimeout(2_000) { while (rx.none { it is Frame.Challenge }) delay(5) }
    client.send(Frame.Auth(proof))
    withTimeout(2_000) { while (rx.none { it is Frame.Welcome || it is Frame.Denied }) delay(5) }
}
