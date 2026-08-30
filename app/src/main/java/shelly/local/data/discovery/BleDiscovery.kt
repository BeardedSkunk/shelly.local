package shelly.local.data.discovery

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

// Permissions are verified by hasBlePermissions() before the scan starts.
@SuppressLint("MissingPermission")
fun discoverViaBle(context: Context): Flow<DiscoveredDevice> = callbackFlow {
    val btManager = context.getSystemService(BluetoothManager::class.java)
    val adapter = btManager?.adapter
    if (adapter == null || !adapter.isEnabled) {
        close()
        return@callbackFlow
    }
    if (!hasBlePermissions(context)) {
        close()
        return@callbackFlow
    }

    val scanner = adapter.bluetoothLeScanner
    val settings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: return
            if (!name.startsWith("Shelly", ignoreCase = true)) return
            // BLE-only devices can't be controlled by IP; we surface them for awareness.
            // Gen2 devices in setup mode expose a BLE provisioning interface but no IP yet.
            trySend(DiscoveredDevice(
                name = name,
                ipAddress = "",   // user must connect to the device AP first
                source = DiscoverySource.BLE,
            ))
        }
        override fun onScanFailed(errorCode: Int) {}
    }

    try {
        scanner.startScan(null, settings, callback)
    } catch (_: SecurityException) {
        close()
        return@callbackFlow
    }
    awaitClose {
        try { scanner.stopScan(callback) } catch (_: SecurityException) {}
    }
}

fun hasBlePermissions(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }
}

fun blePermissionsToRequest(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
