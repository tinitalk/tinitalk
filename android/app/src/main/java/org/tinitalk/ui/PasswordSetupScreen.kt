package org.tinitalk.ui

import org.tinitalk.i18n.appString

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tinitalk.R
import org.tinitalk.data.httpsServerUrl

@Composable
internal fun PasswordSetupScreen(
    credentials: AccountCredentials,
    loading: Boolean,
    errorMessage: String?,
    internetAvailable: Boolean,
    retryAtMillis: Long,
    onSubmit: (String, String, String, String) -> Unit,
    onBack: () -> Unit,
) {
    var password by remember(credentials) { mutableStateOf("") }
    var confirmation by remember(credentials) { mutableStateOf("") }
    var validationMessage by remember(credentials) { mutableStateOf<String?>(null) }
    val retry = retrySeconds(retryAtMillis)
    val server = httpsServerUrl(credentials.server)
    val canSubmit = internetAvailable && !loading && retry == 0L && server != null &&
        credentials.password.isNotEmpty() && password.isNotEmpty() && confirmation.isNotEmpty()
    val submit: () -> Unit = {
        if (canSubmit) {
            validationMessage = personalPasswordError(password) ?: passwordConfirmationError(password, confirmation)
            if (validationMessage == null) {
                onSubmit(checkNotNull(server), credentials.login, credentials.password, password)
            }
        }
    }
    // The original credential is intentionally kept only in memory. After process
    // recreation, return to sign-in instead of displaying an unusable setup form.
    LaunchedEffect(credentials) {
        if (credentials.password.isEmpty()) onBack()
    }
    BackHandler { if (!loading) onBack() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0xFF111D30), MaterialTheme.colorScheme.background)),
            ).statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    IconButton(onClick = onBack, enabled = !loading) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = appString(R.string.text_back_101))
                    }
                }
                Text(appString(R.string.text_choose_a_password_317), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 468.dp).fillMaxWidth().padding(24.dp)) {
                Text(
                    appString(R.string.text_at_least_8_characters_we_recommend_combining_lowercase_and_upperc_318),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    password, { password = it; validationMessage = null }, Modifier.fillMaxWidth(),
                    label = { Text(appString(R.string.text_new_password_319)) }, singleLine = true, enabled = !loading,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    confirmation, { confirmation = it; validationMessage = null }, Modifier.fillMaxWidth(),
                    label = { Text(appString(R.string.text_repeat_password_320)) }, singleLine = true, enabled = !loading,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                (validationMessage ?: if (retryAtMillis > 0) {
                    if (retry > 0) appString(R.string.text_try_again_in_value_s_122, retry) else appString(R.string.text_you_can_try_again_123)
                } else errorMessage)?.let { message ->
                    Spacer(Modifier.height(14.dp))
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer) {
                        Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Button(onClick = submit, enabled = canSubmit, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                    if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Text(appString(R.string.text_sign_in_115), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
