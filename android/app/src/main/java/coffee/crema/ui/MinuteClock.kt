package coffee.crema.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import coffee.crema.history.minuteBucket
import coffee.crema.history.msToNextMinute
import kotlinx.coroutines.delay

/**
 * The wall clock's current minute ([minuteBucket]), ticking while composed: a
 * body that reads the clock itself is only as fresh as its last recomposition,
 * and a telemetry sample no longer recomposes the screens.
 */
@Composable
fun rememberMinuteBucket(): Long {
    val bucket by produceState(minuteBucket(System.currentTimeMillis())) {
        while (true) {
            val now = System.currentTimeMillis()
            value = minuteBucket(now)
            delay(msToNextMinute(now))
        }
    }
    return bucket
}
