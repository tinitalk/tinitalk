package org.tinitalk.ui

import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Use two panes only on wide, short windows; keyboard visibility must not change the layout. */
internal fun usesCompactLandscape(widthDp: Int, heightDp: Int): Boolean =
    widthDp >= 600 && heightDp < 600 && widthDp > heightDp

@Composable
internal fun compactLandscape(): Boolean {
    val configuration = LocalConfiguration.current
    return usesCompactLandscape(configuration.screenWidthDp, configuration.screenHeightDp)
}

internal const val LandscapeIdentityPaneWeight = 0.38f

/** Shared contact/caller panel: edge-to-edge surface, divider, and safe content. */
@Composable
internal fun Modifier.landscapeIdentityPane(): Modifier {
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    return background(MaterialTheme.colorScheme.surface)
        .leftNavigationBarBackdrop()
        .drawWithContent {
            drawContent()
            val thickness = 1.dp.toPx()
            val x = if (layoutDirection == LayoutDirection.Ltr) size.width - thickness / 2 else thickness / 2
            drawLine(dividerColor, Offset(x, 0f), Offset(x, size.height), thickness)
        }
        // The centered identity leaves room for the side camera cutout already.
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)
            .union(WindowInsets.navigationBars.only(WindowInsetsSides.Start)))
}

/** Paint only the system navigation area, not the side camera cutout or app controls. */
@Composable
internal fun Modifier.leftNavigationBarBackdrop(
    navigationBars: WindowInsets = WindowInsets.navigationBars,
): Modifier {
    val color = MaterialTheme.colorScheme.surfaceVariant
    return drawWithContent {
        drawContent()
        val width = navigationBars.getLeft(this, layoutDirection).toFloat().coerceAtMost(size.width)
        if (width > 0f) drawRect(color, size = Size(width, size.height))
    }
}

@Composable
internal fun AccountPasswordDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
    landscapeDescription: @Composable () -> Unit = {},
) {
    if (!compactLandscape()) {
        AlertDialog(onDismissRequest = onDismissRequest, title = title, text = text,
            confirmButton = confirmButton, dismissButton = dismissButton)
    } else {
        Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
                color = MaterialTheme.colorScheme.background) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(0.4f).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
                        ProvideTextStyle(MaterialTheme.typography.titleLarge, title)
                        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp)
                            .verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                            landscapeDescription()
                        }
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically) {
                            dismissButton()
                            confirmButton()
                        }
                    }
                    Box(Modifier.weight(0.6f).fillMaxHeight().padding(16.dp),
                        contentAlignment = Alignment.Center) { text() }
                }
            }
        }
    }
}
