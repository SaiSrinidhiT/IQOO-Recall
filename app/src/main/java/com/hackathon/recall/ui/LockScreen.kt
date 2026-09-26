package com.hackathon.recall.ui

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hackathon.recall.R

/**
 * BiometricPrompt gate (brief F10): strong biometric or the device credential. With no screen lock at
 * all the vault can't be protected; the user is told so and may continue (docs/DECISIONS.md).
 */
@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val activity = LocalContext.current as Activity
    var error by remember { mutableStateOf<String?>(null) }
    val secure = remember { activity.getSystemService(KeyguardManager::class.java).isDeviceSecure }
    val title = stringResource(R.string.biometric_title)

    fun prompt() {
        authenticate(activity, title, onUnlocked) { error = it }
    }
    LaunchedEffect(Unit) { if (secure) prompt() }

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.titleMedium)
        if (secure) {
            Text(stringResource(R.string.lock_subtitle), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = ::prompt) { Text(stringResource(R.string.action_unlock)) }
        } else {
            Text(stringResource(R.string.lock_no_credential), color = MaterialTheme.colorScheme.error)
            Button(onClick = onUnlocked) { Text(stringResource(R.string.action_continue)) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

fun authenticate(activity: Activity, title: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
    val manager = activity.getSystemService(BiometricManager::class.java)
    val strongOrCredential = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    // Biometrics not enrolled (or no strong sensor): fall back to the device credential alone.
    val allowed = if (manager.canAuthenticate(strongOrCredential) == BiometricManager.BIOMETRIC_SUCCESS) strongOrCredential
    else BiometricManager.Authenticators.DEVICE_CREDENTIAL
    BiometricPrompt.Builder(activity)
        .setTitle(title)
        .setAllowedAuthenticators(allowed)
        .build()
        .authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onError(errString.toString())
        })
}
