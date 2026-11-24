package com.wakepilot.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.wakepilot.app.data.AppRole
import com.wakepilot.app.data.ConfigStore
import com.wakepilot.app.ui.ControllerScreen
import com.wakepilot.app.ui.HomeScreen
import com.wakepilot.app.ui.SentinelScreen
import com.wakepilot.app.ui.WakePilotTheme

class MainActivity : ComponentActivity() {

    private var onQrResult: ((String) -> Unit)? = null

    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (!contents.isNullOrBlank()) {
            onQrResult?.invoke(contents)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = ConfigStore(this)

        setContent {
            WakePilotTheme {
                var role by remember { mutableStateOf(store.role) }
                var scannedUri by remember { mutableStateOf<String?>(null) }

                onQrResult = { uri -> scannedUri = uri }

                when (role) {
                    AppRole.NONE -> HomeScreen(
                        onSelectRole = { chosen ->
                            store.role = chosen
                            role = chosen
                        },
                    )
                    AppRole.SENTINEL -> SentinelScreen(
                        store = store,
                        onSwitchRole = {
                            store.role = AppRole.NONE
                            role = AppRole.NONE
                        },
                    )
                    AppRole.CONTROLLER -> ControllerScreen(
                        store = store,
                        scannedPairUri = scannedUri,
                        onConsumeScannedUri = { scannedUri = null },
                        onRequestScanQr = {
                            val opts = ScanOptions().apply {
                                setPrompt("扫描备用手机哨兵屏幕上的配对二维码")
                                setBeepEnabled(false)
                                setOrientationLocked(false)
                            }
                            qrLauncher.launch(opts)
                        },
                        onSwitchRole = {
                            store.role = AppRole.NONE
                            role = AppRole.NONE
                        },
                    )
                }
            }
        }
    }
}
