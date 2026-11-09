package com.thermling.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.thermling.app.ThermlingState
import com.thermling.platform.AndroidFileDialogs
import com.thermling.platform.AndroidPermissions
import com.thermling.platform.AndroidPlatform
import com.thermling.ui.ThermlingApp
import com.thermling.ui.ThermlingTheme
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private lateinit var platform: AndroidPlatform
    private lateinit var state: ThermlingState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Activity result launchers must be registered before the activity is
        // started; doing it here also covers configuration changes.
        val dialogs = AndroidFileDialogs(this)
        val permissions = AndroidPermissions(this)

        platform = AndroidPlatform(applicationContext, dialogs)
        state = ThermlingState(platform)

        setContent {
            ThermlingTheme {
                ThermlingApp(state)
            }
        }

        permissions.requestIfNeeded()
    }

    override fun onDestroy() {
        if (::state.isInitialized) {
            // Closing the socket can wait for an in-flight read; keep the main
            // thread free while the activity is going away.
            thread(name = "thermling-disconnect") {
                runBlocking { state.disconnectPrinter() }
            }
        }
        super.onDestroy()
    }
}
