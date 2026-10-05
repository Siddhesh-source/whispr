package dev.whispr.core.designsystem.icon

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import dev.whispr.core.designsystem.R

/**
 * The app's icon set: Material Symbols Rounded (Apache 2.0), vendored as
 * vector drawables so we do not depend on the deprecated extended-icons
 * artifact. Directional icons are auto-mirrored for RTL.
 */
object WhisprIcons {
    val Back: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_arrow_back)
    val Send: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_send)
    val Sent: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_check)
    val Delivered: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_done_all)
    val Pending: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_schedule)
    val Error: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_error)
    val Offline: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cloud_off)
    val Chat: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_chat)
    val Settings: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_settings)
    val Refresh: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_refresh)
    val Person: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_person)
    val PersonAdd: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_person_add)
    val Copy: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_copy)
    val Unlocked: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lock_open)
    val QrCode: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_qr_code)
    val QrScanner: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_qr_scanner)
    val Verified: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_verified)
    val Warning: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_warning)
    val Edit: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_edit)
    val Image: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_image)
    val Group: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_group)
    val Attach: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_attach)
    val Mic: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_mic)
    val Stop: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_stop)
    val Play: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_play)
    val Pause: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_pause)
    val File: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_file)
    val Download: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_download)
    val React: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_mood)
    val Leave: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_logout)
}
