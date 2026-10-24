package dev.padermodern.companion

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.padermodern.MainActivity
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class BluetoothReceptionDeviceTest {
    @Test fun currentOperatorMappingLoadsReadOnly() = runBlocking {
        val names = BusNameRepository.load(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue(names.lines.isNotEmpty())
        assertTrue(names.destinations.isNotEmpty())
        assertTrue(names.stops.isNotEmpty())
    }

    @Test fun filteredScannerStartsAndStopsWithoutVehicleConnections() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION).all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val running = AtomicBoolean(false)
        val message = AtomicReference("")
        var scanner: PassiveBusScanner? = null
        try {
            instrumentation.runOnMainSync {
                scanner = PassiveBusScanner(context, { _, _, _ -> }, { active, text -> running.set(active); message.set(text) })
                scanner!!.start()
            }
            Thread.sleep(3_000)
            assertTrue("Filtered BLE scan failed: ${message.get()}", running.get())
        } finally {
            instrumentation.runOnMainSync { scanner?.stop() }
            scenario.close()
        }
    }
}
