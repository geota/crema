package coffee.crema.ui.components

import android.animation.ValueAnimator
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Temporary messages (brew messages PRD §2): one Crema style for every
 * one-shot message — a raised surface with a hairline border, an icon by kind,
 * the message, at most one copper text action, a ✕, and a 2dp copper
 * countdown line along the bottom (none for a pinned message).
 *
 * The queue, timeouts and results stay Material's: [CremaMessageHost] is a
 * [SnackbarHost] with a custom slot, so `showSnackbar` still returns
 * ActionPerformed for the action and Dismissed for a timeout or the ✕.
 *
 * Placement: just above the brew controls. Screens mark what the message sits
 * on with [messageAnchor]: Brew's bottom bar / Steam-Water-Flush row
 * ([MessageAnchorSlot.Controls]), the phone's bottom navigation
 * ([MessageAnchorSlot.Nav]) and the tablet rail ([MessageAnchorSlot.Rail],
 * whose edge is the strip's left edge on pages without a bottom bar). The
 * message overlays content; it never moves layout.
 */

/** What a message is about: picks its icon (and the error's border + live region). */
enum class MessageKind { Info, Success, Error }

/** [SnackbarVisuals] with a [kind]. A plain `showSnackbar(message)` is [MessageKind.Info]. */
data class CremaMessageVisuals(
    override val message: String,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = true,
    override val duration: SnackbarDuration =
        if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Indefinite,
    val kind: MessageKind = MessageKind.Info,
) : SnackbarVisuals

/** The Material timeout for [this] duration before accessibility scaling (null: pinned). */
internal fun SnackbarDuration.baseTimeoutMs(): Long? = when (this) {
    SnackbarDuration.Short -> 4_000L
    SnackbarDuration.Long -> 10_000L
    SnackbarDuration.Indefinite -> null
}

/** The time [SnackbarHost] waits before it dismisses [visuals] — the same sum
 *  Material does, so the countdown line ends when the message goes. */
internal fun messageTimeoutMs(visuals: SnackbarVisuals, a11y: AccessibilityManager?): Long? {
    val base = visuals.duration.baseTimeoutMs() ?: return null
    return a11y?.calculateRecommendedTimeoutMillis(
        base,
        containsIcons = true,
        containsText = true,
        containsControls = visuals.actionLabel != null,
    ) ?: base
}

/**
 * Show the aborted-shot discard ("Undo"). Undo restores the shot; a timeout or
 * the ✕ drops it. Data-loss sensitive: keep the mapping exact.
 */
suspend fun SnackbarHostState.showDiscardMessage(
    message: String,
    onUndo: () -> Unit,
    onDrop: () -> Unit,
) {
    val result = showSnackbar(
        CremaMessageVisuals(message, actionLabel = "Undo", duration = SnackbarDuration.Long),
    )
    if (result == SnackbarResult.ActionPerformed) onUndo() else onDrop()
}

/** Show an undoable [message] ("Undo"): Undo runs [onUndo]; a timeout or the ✕, [onDrop]. */
suspend fun SnackbarHostState.showUndoMessage(
    message: String,
    onUndo: () -> Unit,
    onDrop: () -> Unit,
) {
    val result = showSnackbar(
        CremaMessageVisuals(message, actionLabel = "Undo", duration = SnackbarDuration.Long),
    )
    if (result == SnackbarResult.ActionPerformed) onUndo() else onDrop()
}

/** Show the kept-aside notice: it stays until Share or the ✕. */
suspend fun SnackbarHostState.showKeptAsideMessage(
    message: String,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val result = showSnackbar(
        CremaMessageVisuals(
            message,
            actionLabel = "Share",
            duration = SnackbarDuration.Indefinite,
            kind = MessageKind.Error,
        ),
    )
    if (result == SnackbarResult.ActionPerformed) onShare() else onDismiss()
}

// ── Anchors ─────────────────────────────────────────────────────────────────

/** What a message can sit on, most specific first. */
enum class MessageAnchorSlot { Controls, Nav, Rail }

/** Where the anchors are, in root coordinates. Written by [messageAnchor]. */
@Stable
class MessageAnchors {
    private val rects = mutableStateMapOf<MessageAnchorSlot, Pair<Any, Rect>>()

    operator fun get(slot: MessageAnchorSlot): Rect? = rects[slot]?.second

    internal fun set(slot: MessageAnchorSlot, owner: Any, rect: Rect) {
        val cur = rects[slot]
        if (cur == null || cur.first !== owner || cur.second != rect) rects[slot] = owner to rect
    }

    /** Only the current owner clears: a screen leaving after its successor
     *  arrived (a navigation crossfade) leaves the successor's anchor. */
    internal fun clear(slot: MessageAnchorSlot, owner: Any) {
        if (rects[slot]?.first === owner) rects.remove(slot)
    }
}

val LocalMessageAnchors = staticCompositionLocalOf { MessageAnchors() }

/** Mark this element as [slot]: a message sits on top of it. */
fun Modifier.messageAnchor(slot: MessageAnchorSlot): Modifier = this then MessageAnchorElement(slot)

private data class MessageAnchorElement(val slot: MessageAnchorSlot) : ModifierNodeElement<MessageAnchorNode>() {
    override fun create() = MessageAnchorNode(slot)
    override fun InspectorInfo.inspectableProperties() {
        name = "messageAnchor"
        properties["slot"] = slot
    }

    override fun update(node: MessageAnchorNode) {
        if (node.slot != slot) {
            node.release()
            node.slot = slot
        }
    }
}

private class MessageAnchorNode(var slot: MessageAnchorSlot) :
    Modifier.Node(), GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode {
    private var anchors: MessageAnchors? = null

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val a = currentValueOf(LocalMessageAnchors)
        anchors = a
        a.set(slot, this, coordinates.boundsInRoot())
    }

    fun release() {
        anchors?.clear(slot, this)
    }

    override fun onDetach() {
        release()
        anchors = null
    }
}

// ── Host ────────────────────────────────────────────────────────────────────

/**
 * The app's one message host, drawn over everything at the root. [wide]: the
 * tablet strip (rail edge to screen edge, on top of the bottom bar); else the
 * phone card (16dp gutters, above the brew controls or the bottom navigation).
 */
@Composable
fun BoxScope.CremaMessageHost(
    hostState: SnackbarHostState,
    wide: Boolean,
) {
    val anchors = LocalMessageAnchors.current
    val density = LocalDensity.current
    var hostBounds by remember { mutableStateOf<Rect?>(null) }
    Box(
        Modifier
            .matchParentSize()
            .onGloballyPositioned { hostBounds = it.boundsInRoot() },
    ) {
        val host = hostBounds
        val target = anchors[MessageAnchorSlot.Controls] ?: anchors[MessageAnchorSlot.Nav]
        val rail = anchors[MessageAnchorSlot.Rail]
        fun px(v: Float): Dp = with(density) { v.coerceAtLeast(0f).toDp() }
        val place = if (host != null && target != null) {
            Modifier.padding(
                bottom = px(host.bottom - target.top) + if (wide) 0.dp else 8.dp,
                start = if (wide) px(target.left - host.left) else 16.dp,
                end = if (wide) px(host.right - target.right) else 16.dp,
            )
        } else {
            // No bottom bar: the bottom of the content area.
            Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(
                    bottom = if (wide) 0.dp else 8.dp,
                    start = if (wide) px((rail?.right ?: 0f) - (host?.left ?: 0f)) else 16.dp,
                    end = if (wide) 0.dp else 16.dp,
                )
        }
        SnackbarHost(
            hostState = hostState,
            modifier = Modifier.align(Alignment.BottomStart).then(place).fillMaxWidth(),
        ) { data -> CremaMessage(data, wide) }
    }
}

/** One message: the wide strip or the phone card. */
@Composable
internal fun CremaMessage(data: SnackbarData, wide: Boolean) {
    val visuals = data.visuals
    val kind = (visuals as? CremaMessageVisuals)?.kind ?: MessageKind.Info
    val tel = coffee.crema.ui.theme.CremaTheme.telemetry
    val copper = MaterialTheme.colorScheme.primary
    val iconTint = when (kind) {
        MessageKind.Info -> copper
        MessageKind.Success -> tel.success
        MessageKind.Error -> MaterialTheme.colorScheme.error
    }
    val border = when (kind) {
        MessageKind.Error -> MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.outline
    }
    val icon = when (kind) {
        MessageKind.Info -> "info"
        MessageKind.Success -> "check-circle"
        MessageKind.Error -> "warning"
    }
    val timeout = messageTimeoutMs(visuals, LocalAccessibilityManager.current)
    // The countdown runs on the frame clock, not an animation, so it tracks
    // the host's own timer; with animations off (reduced motion) it isn't drawn.
    val animate = remember { ValueAnimator.areAnimatorsEnabled() }
    val countdown = timeout != null && animate
    var left by remember(data) { mutableFloatStateOf(1f) }
    if (timeout != null && animate) {
        LaunchedEffect(data) {
            val start = withFrameMillis { it }
            while (left > 0f) {
                val now = withFrameMillis { it }
                left = (1f - (now - start).toFloat() / timeout).coerceIn(0f, 1f)
            }
        }
    }
    val shape: Shape = if (wide) RectangleShape else RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (wide) Modifier else Modifier.shadow(8.dp, shape))
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(
                if (wide) {
                    // A hairline along the top: the strip's other edges meet the
                    // bottom bar and the screen edges.
                    Modifier.drawBehind {
                        drawLine(border, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
                    }
                } else {
                    Modifier.border(1.dp, border, shape)
                },
            )
            .drawBehind {
                if (countdown) {
                    val h = 2.dp.toPx()
                    drawRect(copper, topLeft = Offset(0f, size.height - h), size = Size(size.width * left, h))
                }
            }
            .semantics {
                liveRegion = if (kind == MessageKind.Error) LiveRegionMode.Assertive else LiveRegionMode.Polite
            }
            .heightIn(min = if (wide) 32.dp else 56.dp)
            .padding(start = if (wide) 20.dp else 14.dp, end = if (wide) 8.dp else 4.dp)
            .testTag("crema-message"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PhIcon(icon, sizeDp = if (wide) 16 else 18, tint = iconTint, contentDescription = null)
        Text(
            visuals.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (wide) 2 else 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = if (wide) 6.dp else 10.dp),
        )
        visuals.actionLabel?.let { label ->
            MessageButton(wide, onClick = data::performAction, tag = "crema-message-action") {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = copper,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
        // Every message has a ✕: Dismissed, as a timeout is.
        MessageButton(wide, onClick = data::dismiss, tag = "crema-message-dismiss", label = "Dismiss notification") {
            PhIcon("x", sizeDp = 14, tint = MaterialTheme.colorScheme.onSurfaceVariant, contentDescription = "Dismiss notification")
        }
    }
}

/** A message's text or ✕ button: the strip's full 32dp height, the card's 44dp. */
@Composable
private fun RowScope.MessageButton(
    wide: Boolean,
    onClick: () -> Unit,
    tag: String,
    label: String? = null,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .height(if (wide) 32.dp else 44.dp)
            .widthIn(min = if (wide) 40.dp else 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) { content() }
}
