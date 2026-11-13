package com.theoriacodex.app.settings

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.theoriacodex.app.applock.DeviceOwnerCheck
import com.theoriacodex.app.applock.appLockViewModel
import com.theoriacodex.app.applock.confirmDeviceOwner
import com.theoriacodex.app.ui.findActivity

/** Switching the lock on or off asks for the same proof the lock asks for, so it cannot lock its owner out. */
@Composable
internal fun AppLockRow() {
    val context = LocalContext.current
    val lock = appLockViewModel()
    val toggle = {
        context.findActivity().confirmDeviceOwner { result ->
            when (result) {
                DeviceOwnerCheck.CONFIRMED -> lock.update(!lock.enabled)
                // Without a screen lock the app lock already does nothing, so it may still be switched off.
                DeviceOwnerCheck.NO_SCREEN_LOCK -> if (lock.enabled) {
                    lock.update(false)
                } else {
                    Toast.makeText(context, "Set a screen lock on this device first", Toast.LENGTH_LONG).show()
                }
                DeviceOwnerCheck.CANCELLED -> Unit
            }
        }
    }
    SettingsRow(
        "App lock", "Fingerprint, face or screen lock to open", Icons.Default.Lock,
        onClick = toggle,
        trailing = { Switch(lock.enabled, onCheckedChange = { toggle() }) },
    )
}
