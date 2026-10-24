package dev.padermodern.companion

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.padermodern.data.BusAdvertisement
import dev.padermodern.data.BusRecord

@SuppressLint("MissingPermission")
internal class PassiveBusScanner(
    private val context: Context,
    private val record: (String, BusRecord, Long) -> Unit,
    private val status: (Boolean, String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var startedAt = 0L
    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val raw = result.scanRecord?.bytes ?: return
            val bus = BusAdvertisement.parse(raw) ?: return
            if (bus.simulator) return
            // address is an in-memory session key, never persisted
            val key = runCatching { "${result.device.address}:${bus.hardwareId}:${bus.course}" }.getOrNull() ?: return
            handler.post {
                val timestamp = result.timestampNanos / 1_000_000
                if (scanner != null && timestamp >= startedAt) record(key, bus, timestamp)
            }
        }
        override fun onScanFailed(errorCode: Int) { handler.post {
            scanner = null
            status(false, "Bus-Bluetooth konnte nicht gestartet werden (Fehler $errorCode). Aus- und wieder einschalten.")
        } }
    }

    fun start() {
        stop()
        if (!permitted()) { status(false, "Berechtigung für Geräte in der Nähe fehlt"); return }
        try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter?.isEnabled != true) { status(false, "Bluetooth ist ausgeschaltet"); return }
            val receiver = adapter.bluetoothLeScanner
            if (receiver == null) { status(false, "Bluetooth-Empfang nicht verfügbar"); return }
            scanner = receiver
            startedAt = SystemClock.elapsedRealtime()
            receiver.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString("00001f1f-0000-1000-8000-00805f9b34fb")).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(), callback,
            )
            status(true, "Suche passenden Bus · GPS bleibt aktiv")
        } catch (e: RuntimeException) {
            stop()
            status(false, "Bus-Bluetooth nicht verfügbar. Bluetooth und Berechtigungen prüfen.")
        }
    }

    fun refresh() {
        if (scanner == null) return
        if (!permitted() || runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled }.getOrNull() != true) {
            stop()
            status(false, "Bluetooth oder Berechtigung wurde ausgeschaltet")
        }
    }

    fun stop() {
        val previous = scanner
        scanner = null
        if (previous != null) runCatching { previous.stopScan(callback) }
        handler.removeCallbacksAndMessages(null)
    }

    private fun permitted() = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION).all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
}
