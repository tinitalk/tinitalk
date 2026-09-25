package org.tinitalk.ui
import androidx.compose.foundation.layout.fillMaxHeight

import org.tinitalk.i18n.appString

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
import org.tinitalk.i18n.AppLanguage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.tinitalk.BuildConfig
import org.tinitalk.R
import org.tinitalk.data.ServerCheckDetails
import org.tinitalk.ui.theme.BrandGold
import org.tinitalk.ui.theme.CallAnswerGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AboutScreen(
    serverUrl: String,
    internetAvailable: Boolean = true,
    onCheckServer: (String) -> ServerCheckDetails,
    onBack: () -> Unit,
) {
    var languagePicker by remember { mutableStateOf(false) }
    if (languagePicker) {
        Dialog(
            onDismissRequest = { languagePicker = false },
            properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
        ) {
            Surface(
                modifier = Modifier.padding(vertical = 16.dp).widthIn(max = 560.dp).fillMaxWidth()
                    .testTag("language-picker").semantics { paneTitle = appString(R.string.language_title) },
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
            ) {
                val languageScrollState = rememberScrollState()
                Column(
                    modifier = Modifier.testTag("language-list").languageScrollbar(languageScrollState)
                        .verticalScroll(languageScrollState).padding(12.dp).selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    (listOf("" to AppLanguage.systemLanguageLabel()) + AppLanguage.sortedLanguages()).forEach { (tag, label) ->
                        val selected = AppLanguage.selection == tag
                        val shape = RoundedCornerShape(16.dp)
                        Surface(
                            shape = shape,
                            color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)) else null,
                            modifier = Modifier.fillMaxWidth().clip(shape).selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { languagePicker = false; AppLanguage.select(tag) },
                            ),
                        ) {
                            Row(
                                modifier = Modifier.heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(Modifier.width(32.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                                    if (tag.isEmpty()) Icon(
                                        painterResource(R.drawable.ic_language_device), contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp),
                                    ) else Text(languageFlag(tag), fontSize = 26.sp)
                                }
                                Text(
                                    label, modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                                Box(Modifier.size(22.dp)) {
                                    if (selected) Icon(
                                        painterResource(R.drawable.ic_language_selected), contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    var details by remember(serverUrl) { mutableStateOf<ServerCheckDetails?>(null) }
    var checking by remember(serverUrl) { mutableStateOf(serverUrl.isNotBlank()) }
    val presentation = serverCheckPresentation(
        serverReady = serverUrl.isNotBlank(),
        checking = checking,
        result = details?.result,
        internetAvailable = internetAvailable,
    )

    LaunchedEffect(serverUrl, internetAvailable) {
        if (!internetAvailable || serverUrl.isBlank()) {
            checking = false
            return@LaunchedEffect
        }
        checking = true
        details = null
        details = withContext(Dispatchers.IO) { onCheckServer(serverUrl) }
        checking = false
    }

    BackHandler(onBack = onBack)
    val landscape = compactLandscape()
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent()
                    }
                },
            )
            val header: @Composable () -> Unit = {
                Row(
                    modifier = Modifier.fillMaxWidth().testTag("about-header")
                        .heightIn(min = if (landscape) 48.dp else 64.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompositionLocalProvider(LocalRippleConfiguration provides null) {
                        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = appString(R.string.text_back_101),
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        appString(R.string.text_about_102),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

            }
            val content: @Composable (Modifier) -> Unit = { modifier ->
                LazyColumn(
                    modifier = modifier.testTag("about-details"),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (!landscape) item(key = "about-brand") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            AppMark(112.dp)
                            Spacer(Modifier.heightIn(min = 18.dp))
                            Text(
                                "TiniTalk",
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    item(key = "about-language") {
                        Surface(
                            onClick = { languagePicker = true },
                            modifier = Modifier.fillMaxWidth().testTag("about-language"),
                            shape = RoundedCornerShape(22.dp),
                            color = MaterialTheme.colorScheme.surface,
                        ) {
                            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(appString(R.string.language_title), style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        AppLanguage.supported[AppLanguage.selection] ?: AppLanguage.systemLanguageLabel(),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Box(Modifier.size(48.dp).testTag("about-language-chevron")
                                    .clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_chevron_right),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(23.dp),
                                    )
                                }
                            }
                        }
                    }
                    item(key = "about-app") {
                        AboutInfoCard(
                            title = appString(R.string.text_application_103),
                            inlineValues = listOf(
                                appString(R.string.text_version_104) to BuildConfig.VERSION_NAME,
                                appString(R.string.text_commit_105) to BuildConfig.COMMIT_HASH,
                            ),
                        )
                    }
                    if (serverUrl.isNotBlank()) item(key = "about-server") {
                        AboutInfoCard(
                            title = appString(R.string.text_server_106),
                            values = listOf(
                                appString(R.string.text_address_107) to serverUrl.ifBlank { appString(R.string.text_not_specified_108) },
                            ),
                            inlineValues = listOf(
                                appString(R.string.text_api_version_109) to (details?.apiVersion?.toString() ?: appString(R.string.text_not_specified_110)),
                                appString(R.string.text_commit_105) to (details?.commit ?: appString(R.string.text_not_specified_108)),
                            ),
                        )
                    }
                    if (serverUrl.isNotBlank()) item(key = "about-server-status") {
                        ServerStatusCard(presentation)
                    }
                }
            }
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(LandscapeIdentityPaneWeight).fillMaxHeight()
                        .testTag("landscape-identity-panel").landscapeIdentityPane()) {
                        header()
                        Column(Modifier.weight(1f).fillMaxWidth()
                            .verticalScroll(rememberScrollState()).padding(top = 24.dp, bottom = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            AppMark(112.dp)
                            Text("TiniTalk", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                    content(Modifier.weight(1f - LandscapeIdentityPaneWeight).fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End)))
                }
            } else {
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    header()
                    content(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

/** Always show the scroll position when languages overflow, even before the first gesture. */
@Composable
private fun Modifier.languageScrollbar(state: ScrollState): Modifier {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    return drawWithContent {
        drawContent()
        val scrollRange = state.maxValue
        if (scrollRange <= 0 || scrollRange == Int.MAX_VALUE) return@drawWithContent
        val inset = 18.dp.toPx()
        val trackHeight = size.height - inset * 2
        if (trackHeight <= 0f) return@drawWithContent
        val width = 4.dp.toPx()
        val x = size.width - 8.dp.toPx()
        val radius = CornerRadius(width / 2)
        val thumbHeight = (trackHeight * size.height / (size.height + scrollRange))
            .coerceIn(24.dp.toPx().coerceAtMost(trackHeight), trackHeight)
        val thumbTop = inset + (trackHeight - thumbHeight) * (state.value.toFloat() / scrollRange).coerceIn(0f, 1f)
        drawRoundRect(color.copy(alpha = 0.12f), Offset(x, inset), Size(width, trackHeight), radius)
        drawRoundRect(color.copy(alpha = 0.65f), Offset(x, thumbTop), Size(width, thumbHeight), radius)
    }
}

private fun languageFlag(tag: String): String = when (tag) {
    "en" -> "🇬🇧"
    "ru" -> "🇷🇺"
    "pl" -> "🇵🇱"
    "de" -> "🇩🇪"
    "es" -> "🇪🇸"
    "fr" -> "🇫🇷"
    "pt" -> "🇧🇷"
    "it" -> "🇮🇹"
    "tr" -> "🇹🇷"
    "ja" -> "🇯🇵"
    "ko" -> "🇰🇷"
    "zh-Hans" -> "🇨🇳"
    else -> "🌐"
}

@Composable
private fun AboutInfoCard(
    title: String,
    values: List<Pair<String, String>> = emptyList(),
    inlineValues: List<Pair<String, String>> = emptyList(),
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            values.forEach { (label, value) ->
                Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        value,
                        modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            if (inlineValues.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    inlineValues.forEach { (label, value) ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                value,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerStatusCard(presentation: ServerCheckPresentation) {
    val color = when (presentation.indicator) {
        ServerCheckIndicator.Available -> CallAnswerGreen
        ServerCheckIndicator.Incompatible -> BrandGold
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, color.copy(alpha = 0.48f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (presentation.indicator) {
                ServerCheckIndicator.Checking -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
                ServerCheckIndicator.Available -> Icon(
                    painter = painterResource(R.drawable.ic_server_available),
                    contentDescription = null,
                    tint = CallAnswerGreen,
                )
                ServerCheckIndicator.Unavailable -> Icon(
                    painter = painterResource(R.drawable.ic_server_unavailable),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ServerCheckIndicator.Incompatible -> Icon(
                    painter = painterResource(R.drawable.ic_server_incompatible),
                    contentDescription = null,
                    tint = BrandGold,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    appString(R.string.text_server_status_111),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    presentation.message,
                    modifier = Modifier.padding(top = 3.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                )
            }
        }
    }
}
