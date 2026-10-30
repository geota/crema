package coffee.crema.ble.proxy

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Wait until the hub's [Frame.Welcome] reached a raw client link (relay v4:
 *  the hub sends it first on an admitted session). [rx] is the client's
 *  collected incoming frames. */
internal suspend fun awaitWelcome(rx: List<Frame>) {
    withTimeout(2_000) { while (rx.none { it is Frame.Welcome }) delay(5) }
}
