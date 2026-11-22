package coffee.crema.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The wait before a held stepper button starts repeating, and the repeat's first and fastest interval. */
internal const val REPEAT_START_MS = 400L
internal const val REPEAT_FIRST_MS = 120L
internal const val REPEAT_FASTEST_MS = 25L

/** The next repeat interval: each one 15% shorter, down to [REPEAT_FASTEST_MS]. */
internal fun nextRepeatMs(current: Long): Long = (current * 0.85).toLong().coerceAtLeast(REPEAT_FASTEST_MS)

/**
 * A stepper button's press (#488: stepping to 500 one at a time isn't
 * usable): one step on press, then, held, steps that speed up. [onStep] is
 * read fresh each time, so each step sees the value the last one set. A
 * button for accessibility services too.
 */
@Composable
fun Modifier.repeatingPress(label: String, onStep: () -> Unit): Modifier {
    val step by rememberUpdatedState(onStep)
    return this
        .semantics {
            role = Role.Button
            onClick(label = label) { step(); true }
        }
        .pointerInput(Unit) {
            detectTapGestures(onPress = {
                step()
                coroutineScope {
                    val repeat = launch {
                        delay(REPEAT_START_MS)
                        var wait = REPEAT_FIRST_MS
                        while (isActive) {
                            step()
                            delay(wait)
                            wait = nextRepeatMs(wait)
                        }
                    }
                    tryAwaitRelease()
                    repeat.cancel()
                }
            })
        }
}
