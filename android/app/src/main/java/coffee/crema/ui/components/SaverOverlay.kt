package coffee.crema.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.ui.relativeAgo
import coffee.crema.ui.theme.JetBrainsMono
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * The screensaver overlay — crema's take on de1app's saver page (dim +
 * clock; `gui.tcl:206-223`) and Decenza's flip-clock saver: a near-black
 * scrim, a big mono clock, and an optional "last shot · 2h ago" line.
 *
 * Tap anywhere wakes: [onWake] dismisses the overlay AND wakes the DE1 when
 * it is asleep (one tap does both, like de1app's full-screen saver button).
 * The clock pixel-shifts a few dp each minute to protect OLED panels —
 * de1app's rotating saver images serve the same purpose.
 */
@Composable
fun SaverOverlay(
    /** Epoch ms the last shot completed, or null — drives the sub-line. */
    lastShotAtMs: Long?,
    onWake: () -> Unit,
) {
    // Minute-tick clock; also drives the OLED pixel-shift.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(10_000)
        }
    }
    val timeText = remember(nowMs / 60_000) {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(nowMs))
    }
    // Deterministic pseudo-random drift derived from the minute, ±12 dp.
    val minute = (nowMs / 60_000).toInt()
    val dx = ((minute * 37) % 25 - 12).dp
    val dy = ((minute * 53) % 25 - 12).dp
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF2000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onWake,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.offset(dx, dy)) {
            androidx.compose.material3.Text(
                timeText,
                style = TextStyle(
                    fontFamily = JetBrainsMono,
                    fontWeight = FontWeight.Medium,
                    fontSize = 64.sp,
                    letterSpacing = (-1).sp,
                    fontFeatureSettings = "tnum",
                ),
                color = Color(0xFF8A8A8A),
            )
            if (lastShotAtMs != null) {
                androidx.compose.material3.Text(
                    "last shot · ${relativeAgo(lastShotAtMs)}",
                    style = TextStyle(fontFamily = JetBrainsMono, fontSize = 14.sp),
                    color = Color(0xFF4E4E4E),
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}
