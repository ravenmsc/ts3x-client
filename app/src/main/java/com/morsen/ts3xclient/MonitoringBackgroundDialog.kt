package com.morsen.ts3xclient

import android.content.Intent
import android.content.Context
import android.app.ActivityManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.morsen.ts3xclient.protocol.TeamSpeakMonitoringPrompt

internal data class MonitoringBackgroundStatus(val batteryExempt: Boolean, val backgroundRestricted: Boolean) {
    val needsSetup: Boolean get() = TeamSpeakMonitoringPrompt.needsSetup(batteryExempt, backgroundRestricted)
}

internal fun monitoringBackgroundStatus(context: Context): MonitoringBackgroundStatus {
    val exempt = context.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) == true
    val restricted = Build.VERSION.SDK_INT >= 28 &&
        context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true
    return MonitoringBackgroundStatus(exempt, restricted)
}

/** Offers both Android's Doze exemption and the device's per-app background settings. */
@Composable
internal fun MonitoringBackgroundDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var powerStatus by remember(context) { mutableStateOf(monitoringBackgroundStatus(context)) }
    var settingsUnavailable by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) powerStatus = monitoringBackgroundStatus(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Returning from Settings (or restoring the Activity) must not leave a redundant prompt open.
    if (!powerStatus.needsSetup) {
        LaunchedEffect(powerStatus) { onDismiss() }
        return
    }

    fun open(vararg intents: Intent) {
        settingsUnavailable = intents.none { intent ->
            runCatching { context.startActivity(intent); true }.getOrDefault(false)
        }
    }

    val appSettings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_monitor_background_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.ui_monitor_background_explanation))
                if (powerStatus.batteryExempt) {
                    Text(stringResource(R.string.ui_monitor_battery_exempt))
                } else {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            open(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}")),
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                                appSettings
                            )
                        }
                    ) { Text(stringResource(R.string.ui_monitor_disable_optimization)) }
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { open(appSettings, Intent(Settings.ACTION_SETTINGS)) }
                ) { Text(stringResource(R.string.ui_monitor_background_settings)) }
                if (settingsUnavailable) Text(stringResource(R.string.ui_monitor_settings_unavailable))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_monitor_background_done)) }
        }
    )
}
