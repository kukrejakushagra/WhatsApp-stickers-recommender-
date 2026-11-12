package com.kukreja.stickersearch.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import android.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.viewModels
import androidx.core.net.toUri
import com.kukreja.stickersearch.ui.kds.theme.KdsTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private val storagePermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { vm.refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) { vm.handleShareIntent(intent); vm.handleOpenIntent(intent) }
        setContent {
            val ui by vm.state.collectAsStateWithLifecycle()
            val mode = ui.themeMode
            val dark = when (mode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            // Status and navigation bar icons follow the app's colours, not just the phone's.
            DisposableEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose { }
            }
            KdsTheme(dark = dark) {
                MainScreen(
                    vm = vm,
                    openAllFilesSettings = {
                        // Android 11+: the "All files access" settings page. Android 9-10: the storage permission.
                        if (android.os.Build.VERSION.SDK_INT >= 30) {
                            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:$packageName".toUri()))
                        } else {
                            storagePermission.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.handleShareIntent(intent)
        vm.handleOpenIntent(intent)
    }
}
