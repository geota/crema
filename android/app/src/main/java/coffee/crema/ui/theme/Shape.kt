package coffee.crema.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/*
 * Crema corner radii — from tablet/m3-tokens.css.
 *
 *   extraSmall  4dp  — chips, tags, tabular cells
 *   small       8dp  — buttons, small cards, inputs
 *   medium     12dp  — cards, panels
 *   large      16dp  — hero cards, modals, the TARE button
 *   extraLarge 24dp  — bottom sheets, large hero cards (web --radius-xl)
 *   (pill)    999dp  — pills, the start/stop control, avatars → use CircleShape
 *                      or RoundedCornerShape(50) per-component.
 *
 * THE "FEELS NATIVE" RULE: never use the same radius on a control and its
 * parent card — always step down one tier (12dp card → 8dp button inside it).
 */
val CremaShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)
