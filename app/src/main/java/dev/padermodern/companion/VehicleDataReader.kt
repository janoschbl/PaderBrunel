package dev.padermodern.companion

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.padermodern.data.VehicleData
import java.util.UUID

// protocol 18 buses only advertise the line, destination and next stop need a gatt read of 0x2020.
// reads only, never touches the request characteristic 0x1f20
@SuppressLint("MissingPermission")
internal class VehicleDataReader(private val context: Context) {
    private class Entry(val version: Int?, val data: VehicleData, val readAt: Long)
    private val handler = Handler(Looper.getMainLooper())
    private val entries = mutableMapOf<String, Entry>()
    private val attempts = mutableMapOf<String, Long>()
    private var active: Read? = null

    // data for this packet if a read for the same data version is recent, starts a read otherwise
    fun current(address: String, version: Int?): VehicleData? {
        val now = SystemClock.elapsedRealtime()
        entries.values.removeIf { now - it.readAt > FORGET_MS }
        val entry = entries[address]?.takeIf { it.version == version }
        val retried = attempts[address]?.let { now - it < RETRY_MS } == true
        if ((entry == null || now - entry.readAt > REFRESH_MS) && active == null && !retried) {
            attempts[address] = now
            active = Read(address, version).also { it.begin() }
        }
        return entry?.takeIf { now - it.readAt <= MAX_AGE_MS }?.data
    }

    fun stop() {
        active?.finish(null)
        entries.clear()
        attempts.clear()
    }

    private inner class Read(val address: String, val version: Int?) : BluetoothGattCallback() {
        private var gatt: BluetoothGatt? = null
        private var done = false
        private val timeout = Runnable { finish(null) }

        fun begin() {
            handler.postDelayed(timeout, TIMEOUT_MS)
            gatt = runCatching {
                context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
                    .connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE)
            }.getOrNull()
            if (gatt == null) finish(null)
        }

        fun finish(data: VehicleData?) {
            if (done) return
            done = true
            handler.removeCallbacks(timeout)
            runCatching { gatt?.disconnect(); gatt?.close() }
            if (data != null) entries[address] = Entry(version, data, SystemClock.elapsedRealtime())
            if (active === this) active = null
        }

        private fun post(data: VehicleData?) { handler.post { finish(data) } }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (!g.discoverServices()) post(null)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) post(null)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val c = g.getService(SERVICE)?.getCharacteristic(VEHICLE_DATA)
            if (status != BluetoothGatt.GATT_SUCCESS || c == null || c.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0 ||
                !g.readCharacteristic(c)) post(null)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            read(value, status)

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) read(c.value ?: ByteArray(0), status)
        }

        private fun read(value: ByteArray, status: Int) {
            post(if (status == BluetoothGatt.GATT_SUCCESS) VehicleData.parse(value) else null)
        }
    }

    companion object {
        private val SERVICE = UUID.fromString("0000201f-0000-1000-8000-00805f9b34fb")
        private val VEHICLE_DATA = UUID.fromString("00002020-0000-1000-8000-00805f9b34fb")
        // one bus in the field log needed 17 s from connect to services
        private const val TIMEOUT_MS = 20_000L
        private const val REFRESH_MS = 15_000L
        private const val MAX_AGE_MS = 30_000L
        private const val RETRY_MS = 5_000L
        private const val FORGET_MS = 120_000L
    }
}
