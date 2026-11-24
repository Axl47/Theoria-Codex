package com.theoriacodex.app.applock

import android.app.Activity
import android.app.KeyguardManager
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
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
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.R
import com.theoriacodex.app.ui.findActivity
import com.theoriacodex.app.ui.theme.TheoriaNightTheme
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val APP_LOCK_GRACE_MS = 60_000L

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

internal class AppLockViewModel @JvmOverloads constructor(
    application: Application,
    private val io: CoroutineContext = Dispatchers.IO,
) : AndroidViewModel(application) {
    private var preferences: SharedPreferences? = null
    val session = AppLockSession()

    var enabled by mutableStateOf<Boolean?>(null)
        private set
    val locked: Boolean get() = enabled != false && !session.unlocked

    init {
        viewModelScope.launch {
            val (loaded, stored) = withContext(io) {
                val loaded = application.getSharedPreferences("app_lock", Context.MODE_PRIVATE)
                loaded to loaded.getBoolean(KEY_ENABLED, false)
            }
            preferences = loaded
            enabled = stored
        }
    }

    fun update(value: Boolean) {
        val loaded = preferences ?: return
        enabled = value
        session.unlock()
        loaded.edit { putBoolean(KEY_ENABLED, value) }
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
    }
}

@Composable
internal fun appLockViewModel(): AppLockViewModel =
    viewModel(viewModelStoreOwner = LocalContext.current.findActivity() as ComponentActivity)

@Composable
internal fun AppLockGate(lock: AppLockViewModel = appLockViewModel(), content: @Composable () -> Unit) {
    val activity = LocalContext.current.findActivity()
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
    LaunchedEffect(lock.enabled, activity) { activity?.setRecentsScreenshotEnabled(lock.enabled == false) }
    val savedState = rememberSaveableStateHolder()
    when {
        lock.enabled == null -> Unit
        lock.locked -> AppLockScreen(onUnlocked = lock.session::unlock)
        else -> savedState.SaveableStateProvider("content", content)
    }
}

@Composable
private fun AppLockScreen(onUnlocked: () -> Unit) {
    val activity = LocalContext.current.findActivity()
    val lifecycleOwner = LocalLifecycleOwner.current
    val unlocked by rememberUpdatedState(onUnlocked)
    val ask = { activity.confirmDeviceOwner { result -> if (result != DeviceOwnerCheck.CANCELLED) unlocked() } }
    DisposableEffect(lifecycleOwner, activity) {
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
