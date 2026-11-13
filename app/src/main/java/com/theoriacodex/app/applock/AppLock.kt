package com.theoriacodex.app.applock

import android.app.Activity
import android.app.KeyguardManager
import android.app.Application
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.R
import com.theoriacodex.app.ui.findActivity
import com.theoriacodex.app.ui.theme.TheoriaNightTheme

/** How long the app may be away from the front before it asks again. */
internal const val APP_LOCK_GRACE_MS = 60_000L

/**
 * Whether this run of the app has passed the lock, and when time away ends that. Away means not in front: in the
 * background, or shrunk to picture-in-picture, which keeps playing but must not hand back the whole app later.
 */
internal class AppLockSession {
    var unlocked by mutableStateOf(false)
        private set
    private var leftAtMs: Long? = null

    fun unlock() {
        unlocked = true
    }

    fun onPause(nowMs: Long) {
        leftAtMs = nowMs
    }

    fun onResume(nowMs: Long) {
        val leftAt = leftAtMs ?: return
        leftAtMs = null
        if (nowMs - leftAt > APP_LOCK_GRACE_MS) unlocked = false
    }
}

/**
 * The lock choice and this run's [session]. It is scoped to the activity, so an unlock survives rotation and ends
 * with the process: a fresh start is always locked. The choice is kept on this device, outside the settings file,
 * so exporting or restoring a backup never switches it.
 */
internal class AppLockViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("app_lock", Context.MODE_PRIVATE)
    val session = AppLockSession()
    var enabled by mutableStateOf(preferences.getBoolean(KEY_ENABLED, false))
        private set
    val locked: Boolean get() = enabled && !session.unlocked

    /** Called once the owner has proved themselves, so switching the lock on leaves the app open. */
    fun update(value: Boolean) {
        preferences.edit { putBoolean(KEY_ENABLED, value) }
        enabled = value
        session.unlock()
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
    }
}

/** The one lock shared by the app root and Settings, whatever screen asks for it. */
@Composable
internal fun appLockViewModel(): AppLockViewModel =
    viewModel(viewModelStoreOwner = LocalContext.current.findActivity() as ComponentActivity)

/**
 * Shows [content] only while the app is unlocked. A locked app does not compose [content] at all, so nothing plays or
 * can be read behind the lock; its saved UI state is kept and restored on unlock. While the lock is on, Android also
 * leaves the app out of the recent-apps preview.
 */
@Composable
internal fun AppLockGate(content: @Composable () -> Unit) {
    val activity = LocalContext.current.findActivity()
    val lock = appLockViewModel()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, lock) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> lock.session.onPause(SystemClock.elapsedRealtime())
                Lifecycle.Event.ON_RESUME -> lock.session.onResume(SystemClock.elapsedRealtime())
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(lock.enabled, activity) { activity?.setRecentsScreenshotEnabled(!lock.enabled) }
    val savedState = rememberSaveableStateHolder()
    if (lock.locked) {
        AppLockScreen(onUnlocked = lock.session::unlock)
    } else {
        savedState.SaveableStateProvider("content", content)
    }
}

@Composable
private fun AppLockScreen(onUnlocked: () -> Unit) {
    val activity = LocalContext.current.findActivity()
    val lifecycleOwner = LocalLifecycleOwner.current
    val unlocked by rememberUpdatedState(onUnlocked)
    // A device without a screen lock cannot ask, and must not shut its owner out.
    val ask = { activity.confirmDeviceOwner { result -> if (result != DeviceOwnerCheck.CANCELLED) unlocked() } }
    DisposableEffect(lifecycleOwner, activity) {
        // Ask once each time the app comes to the front; after a cancel, the button asks again.
        var askOnResume = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> askOnResume = true
                Lifecycle.Event.ON_RESUME -> if (askOnResume && activity?.isInPictureInPictureMode != true) {
                    askOnResume = false
                    ask()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    TheoriaNightTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(48.dp))
                Button(onClick = ask) { Text("Unlock") }
            }
        }
    }
}

internal enum class DeviceOwnerCheck { CONFIRMED, CANCELLED, NO_SCREEN_LOCK }

private const val OWNER_AUTHENTICATORS =
    BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

/**
 * Asks whoever holds the device to prove they own it, with a biometric or the screen lock. Only a device with no
 * screen lock at all reports [DeviceOwnerCheck.NO_SCREEN_LOCK]; a prompt that cannot be shown right now counts as
 * cancelled, so the lock holds and can be tried again.
 */
internal fun Activity?.confirmDeviceOwner(onResult: (DeviceOwnerCheck) -> Unit) {
    val keyguard = this?.getSystemService(KeyguardManager::class.java)
    if (this == null || keyguard == null) {
        onResult(DeviceOwnerCheck.CANCELLED)
        return
    }
    if (!keyguard.isDeviceSecure) {
        onResult(DeviceOwnerCheck.NO_SCREEN_LOCK)
        return
    }
    BiometricPrompt.Builder(this)
        .setTitle(getString(R.string.app_name))
        .setAllowedAuthenticators(OWNER_AUTHENTICATORS)
        .build()
        .authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                onResult(DeviceOwnerCheck.CONFIRMED)

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) =
                onResult(DeviceOwnerCheck.CANCELLED)
        })
}
