package it.michelegiammarini.sleeppausetv

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import it.michelegiammarini.sleeppausetv.ui.MainViewModel
import it.michelegiammarini.sleeppausetv.ui.SleepPauseScreen
import it.michelegiammarini.sleeppausetv.ui.theme.SleepPauseTheme

class MainActivity : ComponentActivity() {
    private var afterPermissionGranted: (() -> Unit)? = null
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            afterPermissionGranted?.invoke()
        }
        afterPermissionGranted = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SleepPauseTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val vm: MainViewModel = viewModel(factory = MainViewModel.factory(application))
                    SleepPauseScreen(
                        viewModel = vm,
                        ensurePermissions = ::ensurePermissions
                    )
                }
            }
        }
    }

    private fun ensurePermissions(onGranted: () -> Unit) {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (permissions.isEmpty()) onGranted()
        else {
            afterPermissionGranted = onGranted
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }
}
