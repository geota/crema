package coffee.crema.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coffee.crema.ui.idleSleepChoices
import coffee.crema.ui.idleSleepIndex
import coffee.crema.ui.idleSleepLabel

/** "Sleep after" — the hint under the idle-sleep stepper. */
const val IDLE_SLEEP_HINT =
    "Put the DE1 to sleep after this long unused — no touches in Crema, no brewing, steaming or rinsing. Scale readings don't count."

/** The screensaver row's hint — screen only since the idle timer owns sleep. */
const val SCREENSAVER_HINT = "Show a dim clock after this long idle. Screen only — the machine sleeps on its own timer (Machine → Sleep after)."

/** The wake-with-saver row's hint (de1app: tapping the saver wakes the machine). */
const val WAKE_WITH_SAVER_HINT =
    "Tapping the clock while the DE1 sleeps also wakes it, and it starts heating. Off: the tap only dismisses — wake with the power button."

/**
 * The "Sleep after" row (Settings → Machine → Machine sleep), shared by the
 * tablet and phone settings: a stepper over the core's idle-sleep choices
 * (Never / 15 / 30 / 60 / 90 / 120 min), de1app's "Cool down after" and
 * Decenza's auto-sleep, default 30.
 */
@Composable
fun IdleSleepRow(minutes: Int, onChange: (Int) -> Unit, style: CremaStepperStyle) {
    val choices = remember { idleSleepChoices() }
    CremaSettingsRow("Sleep after", IDLE_SLEEP_HINT) {
        CremaStepper(
            value = idleSleepIndex(minutes, choices).toDouble(),
            step = 1.0,
            min = 0.0,
            max = (choices.size - 1).toDouble(),
            fmt = { idleSleepLabel(choices[it.toInt().coerceIn(0, choices.size - 1)]) },
            style = style,
            onChange = { onChange(choices[it.toInt().coerceIn(0, choices.size - 1)]) },
        )
    }
}
