package app.stickersearch.ui

import android.content.Intent
import android.os.Bundle
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
import app.stickersearch.ui.kds.theme.KdsTheme

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
                        // Android 9-10 only: the storage permission, a fallback to picking the folder.
                        if (android.os.Build.VERSION.SDK_INT < 30) storagePermission.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
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
