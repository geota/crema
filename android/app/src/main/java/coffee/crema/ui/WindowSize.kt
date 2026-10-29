package coffee.crema.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpSize

/**
 * The space this window gives the app's content, in dp: the window's
 * container minus the STABLE system-bar insets (counted even while immersive
 * mode hides the bars). That is what `Configuration.screenWidthDp` /
 * `screenHeightDp` reported on the Android 12–14 tablets the layout
 * breakpoints were tuned on, but it follows the actual window in
 * split-screen, freeform and on foldables, where the configuration values
 * describe the display (lint ConfigurationScreenWidthHeight; cohort review
 * 2026-10-25, P3-4).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun windowContentDpSize(): DpSize {
    val container = LocalWindowInfo.current.containerDpSize
    val bars = WindowInsets.systemBarsIgnoringVisibility
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    return with(density) {
        DpSize(
            width = container.width - (bars.getLeft(density, direction) + bars.getRight(density, direction)).toDp(),
            height = container.height - (bars.getTop(density) + bars.getBottom(density)).toDp(),
        )
    }
}
