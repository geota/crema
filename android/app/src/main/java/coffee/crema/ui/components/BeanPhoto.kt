package coffee.crema.ui.components

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
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
        contentScale = ContentScale.Crop,
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
