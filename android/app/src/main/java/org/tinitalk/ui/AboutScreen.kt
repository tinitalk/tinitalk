package org.tinitalk.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    onCheckServer: (String) -> ServerCheckDetails,
    onBack: () -> Unit,
) {
    var details by remember(serverUrl) { mutableStateOf<ServerCheckDetails?>(null) }
    var checking by remember(serverUrl) { mutableStateOf(true) }
    val presentation = serverCheckPresentation(serverUrl.isNotBlank(), checking, details?.result)

    LaunchedEffect(serverUrl) {
        checking = true
        details = null
        details = withContext(Dispatchers.IO) { onCheckServer(serverUrl) }
        checking = false
    }

    BackHandler(onBack = onBack)
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
            Column(
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompositionLocalProvider(LocalRippleConfiguration provides null) {
                        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = "Назад",
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "О программе",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item(key = "about-brand") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            AppMark(84.dp)
                            Spacer(Modifier.heightIn(min = 18.dp))
                            Text(
                                "TiniTalk",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    item(key = "about-app") {
                        AboutInfoCard(
                            title = "Приложение",
                            inlineValues = listOf(
                                "Версия" to BuildConfig.VERSION_NAME,
                                "Коммит" to BuildConfig.COMMIT_HASH,
                            ),
                        )
                    }
                    item(key = "about-server") {
                        AboutInfoCard(
                            title = "Сервер",
                            values = listOf(
                                "Адрес" to serverUrl.ifBlank { "Не указан" },
                            ),
                            inlineValues = listOf(
                                "Версия API" to (details?.apiVersion?.toString() ?: "Не указана"),
                                "Коммит" to (details?.commit ?: "Не указан"),
                            ),
                        )
                    }
                    item(key = "about-server-status") {
                        ServerStatusCard(presentation)
                    }
                }
            }
        }
    }
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
                    "Состояние сервера",
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
