package coffee.crema.ui.components

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coffee.crema.beans.BeanImageStore
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest

/*
 * Bean bag photo — the display + capture surface for Phase C.
 *
 * Photos are stored by BeanImageStore keyed by bean id; the bean record only
 * carries a small `imageRef` "has a photo" marker. Display resolves the file BY
 * BEAN ID (never by parsing imageRef), so a photo restored from a cross-shell
 * `.crema.zip` shows with no extra work. The Coil request is cache-busted on the
 * bean's `updatedAt` so a re-captured photo replaces the old one without an app
 * restart; a missing / unreadable file falls back to the roaster mark.
 */

/** A Coil request for a bean's photo file, cache-busted on [updatedAt] so a
 *  re-capture (same path, new bytes) reloads instead of serving the old bitmap. */
private fun beanPhotoRequest(ctx: Context, beanId: String, updatedAt: Long): ImageRequest =
    ImageRequest.Builder(ctx)
        .data(BeanImageStore.beanImageFile(ctx, beanId))
        .memoryCacheKey("bean-image:$beanId:$updatedAt")
        .build()

/**
 * The bag photo (resolved by bean id) drawn into [modifier], or [fallback] when
 * the bean has no photo / it can't be loaded. [imageRef] is used ONLY as the
 * "has a photo" flag — non-null for a captured OR a cross-shell-restored bean.
 */
@Composable
fun BeanPhotoBox(
    beanId: String,
    imageRef: String?,
    updatedAt: Long,
    modifier: Modifier = Modifier,
    /** `Crop` for the square avatars/heroes; `Fit` for the full-screen viewer,
     *  where cropping a bag label would defeat the point. */
    contentScale: ContentScale = ContentScale.Crop,
    fallback: @Composable () -> Unit,
) {
    if (imageRef == null) {
        fallback()
        return
    }
    val ctx = LocalContext.current
    val model = remember(beanId, updatedAt) { beanPhotoRequest(ctx, beanId, updatedAt) }
    SubcomposeAsyncImage(
        model = model,
        contentDescription = "Bag photo",
        contentScale = contentScale,
        modifier = modifier,
        loading = { fallback() },
        error = { fallback() },
    )
}

/** Square bean avatar — the bag photo, or the deterministic roaster mark. Drop-in
 *  for [RoasterMarkAvatar] on bean tiles + editors. */
@Composable
fun BeanAvatar(
    beanId: String,
    imageRef: String?,
    updatedAt: Long,
    fallbackName: String?,
    sizeDp: Int,
    cornerDp: Int,
    fontSize: TextUnit,
) {
    BeanPhotoBox(
        beanId = beanId,
        imageRef = imageRef,
        updatedAt = updatedAt,
        modifier = Modifier.size(sizeDp.dp).clip(RoundedCornerShape(cornerDp.dp)),
        fallback = { RoasterMarkAvatar(fallbackName, sizeDp, cornerDp, fontSize) },
    )
}

/**
 * The bag photo full-screen, pinch-zoomable (issue 61).
 *
 * A bag photo is usually a photo of the *label*, so fit-to-screen on a handset
 * is not enough — the reason to have taken it is to read it back. Hence zoom
 * (up to 5×) and pan, with the pan clamped so the image cannot be flung off
 * the screen and lost. Double-tap toggles between fit and 2.5×, the gesture
 * every gallery app has trained users to expect.
 *
 * Dismissed by a single tap, the close button, or the system back gesture.
 */
@Composable
fun BeanPhotoViewer(
    beanId: String,
    imageRef: String?,
    updatedAt: Long,
    caption: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var box by remember { mutableStateOf(IntSize.Zero) }

        // Keep the image anchored: at 1× there is nothing to pan, and beyond it
        // the offset is bounded by how much of the image is off-screen.
        fun clamp(next: Offset, s: Float): Offset {
            val maxX = (box.width * (s - 1f) / 2f).coerceAtLeast(0f)
            val maxY = (box.height * (s - 1f) / 2f).coerceAtLeast(0f)
            return Offset(next.x.coerceIn(-maxX, maxX), next.y.coerceIn(-maxY, maxY))
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .onSizeChanged { box = it }
                // Transform first: pinch/pan must see the pointer stream before
                // the tap detector gets a chance to claim it.
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val next = (scale * zoom).coerceIn(1f, 5f)
                        scale = next
                        offset = if (next <= 1f) Offset.Zero else clamp(offset + pan, next)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        // Tap-to-dismiss only applies at fit. While zoomed in, a
                        // stray tap closing the viewer and losing the user's
                        // position is the wrong outcome — reset to fit instead,
                        // so it takes two taps to leave.
                        onTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                onDismiss()
                            }
                        },
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            BeanPhotoBox(
                beanId = beanId,
                imageRef = imageRef,
                updatedAt = updatedAt,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
                fallback = {},
            )
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.14f)),
            ) {
                PhIcon("x", sizeDp = 20, tint = Color.White)
            }
        }
    }
}

/** The camera + gallery triggers for a bean's photo, wired to the VM. Built by
 *  [rememberBeanPhotoPicker]; the editors render their own menu/sheet that calls
 *  these. */
class BeanPhotoPicker(
    val takePhoto: () -> Unit,
    val pickFromGallery: () -> Unit,
)

/**
 * Remember the two activity-result launchers for capturing a bag photo:
 *  - **camera** via [ActivityResultContracts.TakePicture] (writes to a
 *    FileProvider content Uri the VM hands out — no CAMERA permission needed,
 *    since the manifest doesn't declare it).
 *  - **gallery** via [ActivityResultContracts.PickVisualMedia] (the modern
 *    photo picker — no permission).
 *
 * Both hand the resulting Uri to [onPicked] (the VM reads + stores the bytes).
 */
@Composable
fun rememberBeanPhotoPicker(
    beanId: String,
    newCameraUri: (String) -> Uri?,
    onPicked: (String, Uri) -> Unit,
): BeanPhotoPicker {
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPicked(beanId, uri)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (ok && uri != null) onPicked(beanId, uri)
    }
    return remember(beanId) {
        BeanPhotoPicker(
            takePhoto = {
                val uri = newCameraUri(beanId)
                if (uri != null) {
                    pendingCameraUri = uri
                    camera.launch(uri)
                }
            },
            pickFromGallery = {
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        )
    }
}
