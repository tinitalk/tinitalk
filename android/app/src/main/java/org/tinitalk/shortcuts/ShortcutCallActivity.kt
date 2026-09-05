package org.tinitalk.shortcuts

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.tinitalk.CallLaunchError
import org.tinitalk.CallLaunchErrorDialog
import org.tinitalk.launchContactCall
import org.tinitalk.ui.theme.TiniTalkTheme

/** Launched by Android's shortcut service as our UID, never by an external intent. */
class ShortcutCallActivity : ComponentActivity() {
    private var consumed = false
    private var launching = false
    private var error by mutableStateOf<CallLaunchError?>(null)
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            error = null
            consumed = false
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) dialOnce()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        consumed = savedInstanceState?.getBoolean("consumed") == true ||
            intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        savedInstanceState?.getString("error_title")?.let { title ->
            error = CallLaunchError(title, savedInstanceState.getString("error_message").orEmpty(), savedInstanceState.getBoolean("error_microphone"))
        }
        setContent {
            TiniTalkTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                error?.let { problem ->
                    CallLaunchErrorDialog(problem, onDismiss = ::finish, onRequestMicrophone = {
                        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    })
                }
            }
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        if (consumed && !launching && error == null) finish()
        else dialOnce()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (launching) return
        setIntent(intent)
        error = null
        consumed = false
    }

    private fun dialOnce() {
        if (consumed || launching) return
        consumed = true
        val peer = shortcutPeer(intent)
        if (peer == null) {
            finish()
            return
        }
        launching = true
        lifecycleScope.launch {
            try {
                error = launchContactCall(peer)
                if (error == null) finish()
            } finally {
                launching = false
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("consumed", consumed)
        error?.let {
            outState.putString("error_title", it.title)
            outState.putString("error_message", it.message)
            outState.putBoolean("error_microphone", it.needsMicrophone)
        }
        super.onSaveInstanceState(outState)
    }
}
