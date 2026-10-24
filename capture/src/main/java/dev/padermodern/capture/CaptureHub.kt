package dev.padermodern.capture

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.padermodern.data.BusNameRepository
import dev.padermodern.data.BusNames
import dev.padermodern.data.SrsApi
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class SessionMeta(
    val sessionId: String,
    val startedUtc: String,
    val stoppedUtc: String? = null,
    val state: String = "running",
    val demo: Boolean = false,
    val appVersion: String = "",
    val device: String = "",
    val androidSdk: Int = 0,
    val scan: String = "",
    val permissions: List<String> = emptyList(),
    val events: Long = 0,
    val advertisements: Long = 0,
    val dropped: Long = 0,
    val bytes: Long = 0,
    val eventsSha256: String? = null,
    val resumedFrom: String? = null,
    val lookupSha256: String? = null,
)

data class CaptureState(
    val running: Boolean = false,
    val finishing: Boolean = false,
    val sessionId: String? = null,
    val demo: Boolean = false,
    val startedElapsedMs: Long = 0,
    val received: Long = 0,
    val queued: Long = 0,
    val written: Long = 0,
    val flushed: Long = 0,
    val dropped: Long = 0,
    val ignored: Long = 0,
    val storageBytes: Long = 0,
    val lastFlushMs: Long? = null,
    val error: String? = null,
    val bluetoothOn: Boolean = true,
    val scanStatus: String = "Bereit",
    val unfiltered: Boolean = false,
    val namesInfo: String = "Keine Namenstabelle geladen",
    val namesBusy: Boolean = false,
    val inspecting: String? = null,
    val viewingId: String? = null,
    val viewNotice: String? = null,
    val busy: String? = null,
    val mirror: String? = null,
    val requesting: String? = null,
) {
    val incomplete get() = dropped > 0 || error != null
}

private sealed interface Item {
    class Scan(val scan: RawScan) : Item
    class Evt(val type: String, val profileId: String?, val opId: String?, val data: JsonObject, val ms: Long = System.currentTimeMillis()) : Item
    class Flush(val done: CompletableDeferred<Unit>) : Item
}

@SuppressLint("MissingPermission")
object CaptureHub {
    private lateinit var app: Application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    val state = MutableStateFlow(CaptureState())
    val snapshot = MutableStateFlow(Snapshot())
    val sessions = MutableStateFlow<List<SessionMeta>>(emptyList())

    private val received = AtomicLong()
    private val dropped = AtomicLong()
    private val gapPending = AtomicLong()
    private val ignored = AtomicLong()
    @Volatile private var channel: Channel<Item>? = null

    private var sessionId = ""
    private var dir: File? = null
    private var writer: BufferedWriter? = null
    private var seq = 0L
    private var ads = 0L
    private var written = 0L
    private var flushed = 0L
    private var lastFlush: Long? = null
    private var index = CaptureIndex()
    private var consumer: Job? = null
    private var ticker: Job? = null
    private var demoJob: Job? = null
    private var requestJob: Job? = null
    private var meta = SessionMeta("", "")
    private var names: BusNames? = null
    private var lookupHash: String? = null
    private var writerError: String? = null
    private var startedElapsed = 0L
    private var testSession = false
    private var inspection: Inspection? = null
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var btReceiver: BroadcastReceiver? = null
    private var mirror: BufferedWriter? = null

    private val root get() = File(app.noBackupFilesDir, "bluetooth-captures")
    private val prefs get() = app.getSharedPreferences("capture", Context.MODE_PRIVATE)

    fun init(application: Application) {
        app = application
        scope.launch {
            refreshSessions(markInterrupted = true)
            loadCachedNames()
            sessions.value.firstOrNull()?.let { loadSession(it.sessionId) }
        }
    }

    fun mirrorEnabled() = prefs.getBoolean("mirror", true)
    fun setMirror(on: Boolean) { prefs.edit().putBoolean("mirror", on).apply() }

    private fun openMirror(id: String): BufferedWriter? = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$id-events.jsonl")
            put(MediaStore.Downloads.MIME_TYPE, "application/jsonl")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Brunel")
        }
        val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("insert")
        BufferedWriter(OutputStreamWriter(app.contentResolver.openOutputStream(uri, "w") ?: error("stream"), Charsets.UTF_8))
    }.getOrNull()

    fun hasConsent() = prefs.getBoolean("consent", false)
    fun grantConsent() = prefs.edit().putBoolean("consent", true).apply()

    val requiredPermissions: Array<String> = buildList {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    fun missingPermissions(): List<String> = requiredPermissions.filter {
        ContextCompat.checkSelfPermission(app, it) != PackageManager.PERMISSION_GRANTED
    }

    fun locationServiceOn(): Boolean = app.getSystemService(LocationManager::class.java)?.isLocationEnabled == true

    private fun bluetoothOn() = runCatching { app.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled }.getOrNull() == true

    fun setUnfiltered(on: Boolean) = state.update { it.copy(unfiltered = on) }

    fun start(demo: Boolean = false) {
        if (state.value.running || state.value.finishing) return
        if (!demo) {
            val missing = missingPermissions().filter { it != Manifest.permission.POST_NOTIFICATIONS }
            if (missing.isNotEmpty()) { state.update { it.copy(error = "Berechtigung fehlt: ${missing.joinToString { it.substringAfterLast('.') }}") }; return }
        }
        val id = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date()) +
            "-" + UUID.randomUUID().toString().take(4) + if (demo) "-demo" else ""
        val ch = Channel<Item>(QUEUE)
        channel = ch
        received.set(0); dropped.set(0); gapPending.set(0); ignored.set(0)
        state.update { it.copy(running = true, sessionId = id, demo = demo, viewingId = id, viewNotice = null, error = null, scanStatus = "Starte …") }
        runCatching {
            ContextCompat.startForegroundService(app, Intent(app, CaptureService::class.java))
        }
        scope.launch { begin(id, demo, ch) }
    }

    private suspend fun begin(id: String, demo: Boolean, ch: Channel<Item>) {
        sessionId = id
        testSession = demo
        seq = 0; ads = 0; written = 0; flushed = 0; lastFlush = null; writerError = null
        index = CaptureIndex()
        snapshot.value = Snapshot()
        startedElapsed = SystemClock.elapsedRealtime()
        val d = File(root, id).also { it.mkdirs() }
        dir = d
        val previous = sessions.value.firstOrNull { it.state == "interrupted" }?.sessionId
        meta = SessionMeta(
            sessionId = id, startedUtc = Instant.now().toString(), demo = demo,
            appVersion = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty(),
            device = "${Build.MANUFACTURER} ${Build.MODEL}", androidSdk = Build.VERSION.SDK_INT,
            scan = if (demo) "Demo (kein Scan)" else if (state.value.unfiltered) "ungefiltert, LOW_LATENCY, nicht-legacy" else "Filter Service 0x1f1f + Hersteller 0x7d78, LOW_LATENCY, nicht-legacy",
            permissions = requiredPermissions.filter { it !in missingPermissions() }.map { it.substringAfterLast('.') },
            resumedFrom = previous,
        )
        try {
            writer = BufferedWriter(OutputStreamWriter(FileOutputStream(File(d, "events.jsonl"), true), Charsets.UTF_8))
        } catch (e: IOException) { state.update { it.copy(running = false, error = "Log nicht beschreibbar: ${e.message}") }; return }
        writeMeta()
        mirror = if (mirrorEnabled()) openMirror(id) else null
        state.update { it.copy(mirror = if (!mirrorEnabled()) "Live-Kopie aus" else if (mirror != null) "Live-Kopie: Download/Brunel/$id-events.jsonl" else "Live-Kopie nicht möglich") }
        consumer = scope.launch { for (item in ch) handle(item) }
        ticker = scope.launch { var n = 0; while (true) { delay(500); tick(n++) } }
        if (demo) names = DemoData.names
        emit("session_start", data = buildJsonObject {
            put("app", meta.appVersion); put("device", meta.device); put("android", meta.androidSdk)
            put("scan", meta.scan); put("demo", demo)
            put("permissions", JsonArray(meta.permissions.map { JsonPrimitive(it) }))
            put("bluetoothOn", bluetoothOn()); put("locationServiceOn", locationServiceOn())
            meta.resumedFrom?.let { put("resumedFrom", it) }
        })
        lookupSnapshot(if (demo) "demo" else "cache")
        if (demo) state.update { it.copy(scanStatus = "Demo läuft · kein Funk-Scan") }
        if (demo) demoJob = scope.launch { DemoData.run(this@CaptureHub::submit, ::emit) }
        else startScan()
        refreshSessions()
    }

    fun stop() { scope.launch { finish("Benutzer") } }

    private suspend fun finish(reason: String) {
        if (!state.value.running || state.value.finishing) return
        state.update { it.copy(finishing = true, scanStatus = "Beende …", inspecting = null) }
        requestJob?.join()
        // keep the writer alive until the http outcome is recorded
        state.update { it.copy(running = false) }
        demoJob?.cancel()
        inspection?.cancel("Aufnahme beendet")
        stopScan()
        btReceiver?.let { runCatching { app.unregisterReceiver(it) } }
        btReceiver = null
        emit("session_stop", data = buildJsonObject { put("reason", reason); put("received", received.get()); put("dropped", dropped.get()) })
        ticker?.cancel()
        channel?.close()
        consumer?.join()
        channel = null
        runCatching { writer?.flush(); writer?.close() }
        runCatching { mirror?.flush(); mirror?.close() }
        mirror = null
        writer = null
        flushed = written
        val file = File(dir, "events.jsonl")
        val final = meta.copy(
            stoppedUtc = Instant.now().toString(), state = if (writerError != null || dropped.get() > 0) "incomplete" else "completed",
            events = seq, advertisements = ads, dropped = dropped.get(), bytes = file.length(),
            eventsSha256 = runCatching { sha256(file.readBytes()) }.getOrNull(), lookupSha256 = lookupHash,
        )
        meta = final
        File(dir, "session.json").writeText(CaptureJson.encodeToString(SessionMeta.serializer(), final))
        snapshot.value = index.snapshot()
        File(dir, "profiles.json").writeText(profilesJson(snapshot.value).toString())
        publish("Beendet")
        if (mirrorEnabled()) runCatching { export(final.sessionId, zip = true)?.let { saveToDownloads(it) } }
        runCatching { app.stopService(Intent(app, CaptureService::class.java)) }
        refreshSessions()
        state.update { it.copy(finishing = false) }
    }

    private fun writeMeta(extra: SessionMeta = meta) {
        runCatching { File(dir, "session.json").writeText(CaptureJson.encodeToString(SessionMeta.serializer(), extra)) }
    }

    private fun submit(scan: RawScan) = offer(Item.Scan(scan))

    private fun emit(type: String, profileId: String? = null, opId: String? = null, data: JsonObject = JsonObject(emptyMap())) {
        offer(Item.Evt(type, profileId, opId, data))
    }

    private fun offer(item: Item) {
        val ch = channel ?: return
        if (item is Item.Scan) received.incrementAndGet()
        if (ch.trySend(item).isFailure && item is Item.Scan) { dropped.incrementAndGet(); gapPending.incrementAndGet() }
    }

    private fun handle(item: Item) {
        try {
            val gap = gapPending.getAndSet(0)
            if (gap > 0) write("capture_gap", null, null, buildJsonObject { put("dropped", gap); put("reason", "Warteschlange voll") }, System.currentTimeMillis())
            when (item) {
                is Item.Scan -> advertisement(item.scan)
                is Item.Evt -> write(item.type, item.profileId, item.opId, item.data, item.ms)
                is Item.Flush -> { flushLog(); item.done.complete(Unit) }
            }
        } catch (e: IOException) {
            writerError = e.message ?: "Schreibfehler"
            stopScan()
            state.update { it.copy(error = "Speicherfehler: $writerError. Aufnahme unvollständig.") }
            if (item is Item.Flush) item.done.complete(Unit)
        }
    }

    private fun advertisement(s: RawScan) {
        val structures = parseStructures(s.bytes)
        val reason = candidateReason(structures)
        if (reason == null) { ignored.incrementAndGet(); return }
        val decoded = try { decodeBus(structures) } catch (e: RuntimeException) { Decoded("parser_exception", e.message) }
        val n = names
        val ad = RawAd(
            receivedMs = s.receivedMs, address = s.address, addressType = s.addressType, deviceName = s.name, rssi = s.rssi,
            scanTimestampNanos = s.scanTimestampNanos, bytes = s.bytes.hex(), length = s.bytes.size, hash = sha256(s.bytes).take(16),
            serviceUuids = structures.uuids, serviceData = structures.serviceData,
            manufacturer = structures.manufacturer.filter { it.size >= 2 }.associate { "0x%04x".format((it[0].toInt() and 0xff) or ((it[1].toInt() and 0xff) shl 8)) to it.copyOfRange(2, it.size).hex() },
            connectable = s.connectable, legacy = s.legacy, primaryPhy = s.primaryPhy, secondaryPhy = s.secondaryPhy,
            advertisingSid = s.sid, txPower = s.txPower, dataStatus = s.dataStatus, candidate = reason, decoded = decoded,
            line = decoded.lineId?.let { n?.lines?.get(it) }, destination = decoded.destinationId?.let { n?.destinations?.get(it) },
            nextStop = decoded.nextStopId?.let { n?.stops?.get(it) }, lookupHash = lookupHash?.take(16), test = s.test,
        )
        ads++
        write("advertisement", profileIdOf(sessionId, s.address), null, CaptureJson.encodeToJsonElement(RawAd.serializer(), ad) as JsonObject, s.receivedMs)
    }

    private fun write(type: String, profileId: String?, opId: String?, data: JsonObject, ms: Long) {
        val out = writer ?: return
        seq++
        val event = Event(
            sessionId = sessionId, sequence = seq, eventId = "$sessionId-$seq", type = type,
            timestampUtc = Instant.ofEpochMilli(ms).toString(), elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
            profileId = profileId, operationId = opId, data = data,
        )
        val line = CaptureJson.encodeToString(Event.serializer(), event) + "\n"
        out.write(line)
        mirror?.let { m ->
            try { m.write(line) } catch (e: IOException) {
                mirror = null
                state.update { it.copy(mirror = "Live-Kopie gestört: ${e.message}") }
            }
        }
        written++
        index.apply(event, ms)
    }

    private fun flushLog() {
        writer?.flush()
        runCatching { mirror?.flush() }
        flushed = written
        lastFlush = System.currentTimeMillis()
    }

    private fun tick(n: Int) {
        try {
            if (n % 2 == 1) flushLog()
            if (index.dirty) snapshot.value = index.snapshot()
            if (n % 20 == 19) writeMeta(meta.copy(events = seq, advertisements = ads, dropped = dropped.get()))
            publish(null)
            if (n % 6 == 0) CaptureService.notify(app, notificationText())
        } catch (e: IOException) {
            writerError = e.message
            state.update { it.copy(error = "Speicherfehler: ${e.message}. Aufnahme unvollständig.") }
        }
    }

    private fun notificationText() = "${received.get()} Pakete · ${snapshot.value.profiles.size} Busse"

    private fun publish(status: String?) {
        val size = runCatching { File(dir, "events.jsonl").length() }.getOrDefault(0)
        state.update {
            it.copy(
                received = received.get(), queued = received.get() - dropped.get(), written = written, flushed = flushed,
                dropped = dropped.get(), ignored = ignored.get(), storageBytes = size, lastFlushMs = lastFlush,
                bluetoothOn = bluetoothOn(), startedElapsedMs = startedElapsed, scanStatus = status ?: it.scanStatus,
            )
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            val raw = record.bytes ?: return
            submit(
                RawScan(
                    receivedMs = System.currentTimeMillis(), bytes = raw.copyOf(), address = result.device.address, addressType = null,
                    name = record.deviceName, rssi = result.rssi, scanTimestampNanos = result.timestampNanos,
                    connectable = result.isConnectable, legacy = result.isLegacy, primaryPhy = result.primaryPhy,
                    secondaryPhy = result.secondaryPhy, sid = result.advertisingSid.takeIf { it != ScanResult.SID_NOT_PRESENT },
                    txPower = result.txPower.takeIf { it != ScanResult.TX_POWER_NOT_PRESENT }, dataStatus = result.dataStatus,
                ),
            )
        }

        override fun onScanFailed(errorCode: Int) {
            emit("scan_error", data = buildJsonObject { put("errorCode", errorCode) })
            state.update { it.copy(scanStatus = "Scan fehlgeschlagen (Fehler $errorCode)", error = "Scan fehlgeschlagen (Fehler $errorCode). Aufnahme unvollständig.") }
        }
    }

    private fun startScan() {
        registerBluetoothReceiver()
        if (!bluetoothOn()) {
            state.update { it.copy(scanStatus = "Bluetooth ist aus") }
            emit("capture_gap", data = buildJsonObject { put("reason", "Bluetooth bei Start aus") })
            return
        }
        try {
            val s = app.getSystemService(BluetoothManager::class.java).adapter.bluetoothLeScanner ?: error("Kein LE-Scanner")
            val filters = if (state.value.unfiltered) emptyList() else listOf(
                ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString("00001f1f-0000-1000-8000-00805f9b34fb")).build(),
                ScanFilter.Builder().setManufacturerData(0x7d78, byteArrayOf()).build(),
            )
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setLegacy(false).build()
            s.startScan(filters, settings, scanCallback)
            scanner = s
            state.update { it.copy(scanStatus = "Scan läuft") }
        } catch (e: RuntimeException) {
            emit("scan_error", data = buildJsonObject { put("exception", e.toString()) })
            state.update { it.copy(scanStatus = "Scan nicht gestartet", error = "Scan nicht gestartet: ${e.message}") }
        }
    }

    private fun stopScan() {
        scanner?.let { runCatching { it.stopScan(scanCallback) } }
        scanner = null
    }

    private fun registerBluetoothReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                emit("bluetooth_state", data = buildJsonObject { put("state", st) })
                when (st) {
                    BluetoothAdapter.STATE_OFF -> {
                        scanner = null
                        emit("capture_gap", data = buildJsonObject { put("reason", "Bluetooth ausgeschaltet") })
                        state.update { it.copy(scanStatus = "Bluetooth aus · Aufnahme pausiert") }
                    }
                    BluetoothAdapter.STATE_ON -> if (state.value.running) {
                        emit("scan_resumed", data = buildJsonObject { put("reason", "Bluetooth eingeschaltet") })
                        scope.launch { startScan() }
                    }
                }
            }
        }
        btReceiver = receiver
        ContextCompat.registerReceiver(app, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun requestBusAction(profileId: String, action: BusAction, authorized: Boolean) {
        scope.launch {
            val st = state.value
            val bus = snapshot.value.profiles.firstOrNull { it.id == profileId } ?: return@launch
            val ad = bus.ads.firstOrNull() ?: return@launch
            val demo = st.demo || bus.test
            if (!st.running || st.finishing || st.viewingId != st.sessionId || writer == null || writerError != null ||
                st.requesting != null ||
                !action.supported(ad) || (!demo && (!authorized || ad.decoded.simulator ||
                    System.currentTimeMillis() - ad.receivedMs !in 0..30_000))
            ) return@launch
            val request = busRequest(action, ad.address)
            val op = UUID.randomUUID().toString()
            state.update { it.copy(requesting = profileId) }
            requestJob = scope.launch {
                val payload = buildJsonObject { put("btMac", ad.address) }.toString()
                suspend fun record(type: String, data: JsonObject) {
                    channel?.send(Item.Evt(type, profileId, op, data))
                }
                suspend fun flush() {
                    val done = CompletableDeferred<Unit>()
                    channel?.send(Item.Flush(done)) ?: error("Log geschlossen")
                    done.await()
                    check(writerError == null) { "Log nicht beschreibbar; Anfrage nicht sicher protokolliert" }
                }
                val summary = "POST ${action.url}\n$payload"
                try {
                    record("op_start", buildJsonObject {
                        put("kind", "network"); put("title", "${action.label}${if (demo) " · Demo" else " · HTTPS"}")
                    })
                    record("http_request", buildJsonObject {
                        put("method", "POST"); put("url", action.url); put("body", payload)
                        put("contentType", "application/json; charset=utf-8"); put("demo", demo)
                    })
                    flush()
                    val reply = if (demo) { delay(400); BusReply(200, "{\"status\":true}", false) }
                        else withContext(Dispatchers.IO) { BusRequests.send(request) }
                    record("http_response", buildJsonObject {
                        put("url", action.url); put("code", reply.code); put("body", reply.body)
                        put("truncated", reply.truncated); put("accepted", reply.accepted); put("demo", demo)
                    })
                    record("op_result", buildJsonObject {
                        put("status", if (reply.accepted) "ok" else "error")
                        put("detail", "$summary\nHTTP ${reply.code}\n${reply.body}" +
                            (if (reply.truncated) "\nAntwort nach 64 KiB gekürzt." else "") + "\n" +
                            if (demo) "Simuliert – keine Netzwerkanfrage." else if (reply.accepted)
                                "Server bestätigt. Die Ausführung am Fahrzeug ist damit nicht nachgewiesen."
                            else "Keine Serverbestätigung. Fahrzeugzustand unbekannt; keine automatische Wiederholung.")
                    })
                } catch (e: Exception) {
                    record("http_error", buildJsonObject { put("exception", e.toString()); put("url", action.url); put("demo", demo) })
                    record("op_result", buildJsonObject {
                        put("status", "error"); put("detail", "$summary\n${e.message}\nZustellung unbekannt. Nicht automatisch wiederholt.")
                    })
                } finally {
                    try {
                        flush()
                    } catch (e: Exception) {
                        state.update { it.copy(error = "Anfrage nicht vollständig gespeichert: ${e.message}. Aufnahme unvollständig.") }
                    }
                    state.update { it.copy(requesting = null) }
                }
            }
        }
    }

    fun addNote(text: String, profileId: String? = null) {
        if (text.isBlank()) return
        emit("note", profileId, data = buildJsonObject { put("text", text.trim()) })
    }

    private fun loadCachedNames() {
        val f = File(app.cacheDir, "bus_names.sqlite")
        if (!f.exists()) return
        runCatching { BusNameRepository.read(f) }.getOrNull()?.let { setNames(it, "Cache", sha256(f.readBytes())) }
    }

    private fun setNames(n: BusNames, source: String, hash: String) {
        names = n
        lookupHash = hash
        state.update { it.copy(namesInfo = "$source · ${n.lines.size} Linien · ${n.destinations.size} Ziele · ${n.stops.size} Halte") }
    }

    private fun lookupSnapshot(source: String) {
        val n = names
        val d = dir ?: return
        val cached = File(app.cacheDir, "bus_names.sqlite")
        if (!testSession && n != null && cached.exists()) runCatching { cached.copyTo(File(d, "lookups/Padersprinter.sqlite").also { it.parentFile?.mkdirs() }, overwrite = true) }
        emit("lookup_snapshot", data = buildJsonObject {
            put("source", source); put("sha256", lookupHash ?: "none")
            put("lines", n?.lines?.size ?: 0); put("destinations", n?.destinations?.size ?: 0); put("stops", n?.stops?.size ?: 0)
        })
    }

    fun loadNames() {
        if (state.value.namesBusy) return
        state.update { it.copy(namesBusy = true) }
        scope.launch {
            val op = UUID.randomUUID().toString().take(8)
            val running = state.value.running
            val started = SystemClock.elapsedRealtime()
            if (running) emit("op_start", opId = op, data = buildJsonObject {
                put("kind", "network"); put("title", "GET service.ivanto.de/srs/api/v1/bluetooth/?filename=Padersprinter")
            })
            try {
                val bytes = SrsApi.bluetoothDatabase()
                val staging = File(app.cacheDir, "bus_names.download")
                staging.writeBytes(bytes)
                val parsed = BusNameRepository.read(staging)
                check(staging.renameTo(File(app.cacheDir, "bus_names.sqlite"))) { "Tabelle konnte nicht gespeichert werden" }
                val hash = sha256(bytes)
                setNames(parsed, "Frisch geladen", hash)
                if (running) {
                    lookupSnapshot("network")
                    emit("op_result", opId = op, data = buildJsonObject {
                        put("status", "ok"); put("detail", "${bytes.size} Byte · sha256 ${hash.take(16)} · ${SystemClock.elapsedRealtime() - started} ms")
                    })
                }
            } catch (e: Exception) {
                state.update { it.copy(namesInfo = "Laden fehlgeschlagen: ${e.message}") }
                if (running) emit("op_result", opId = op, data = buildJsonObject { put("status", "error"); put("detail", e.toString()) })
            } finally {
                state.update { it.copy(namesBusy = false) }
            }
        }
    }

    fun inspect(profileId: String, address: String) {
        if (!state.value.running || state.value.inspecting != null) return
        val op = UUID.randomUUID().toString().take(8)
        state.update { it.copy(inspecting = op) }
        if (testSession) { scope.launch { DemoData.inspect(profileId, op, ::emit); state.update { it.copy(inspecting = null) } }; return }
        val job = Inspection(op, profileId, address)
        inspection = job
        job.begin()
    }

    private class Inspection(val op: String, val profileId: String, val address: String) : BluetoothGattCallback() {
        private var gatt: BluetoothGatt? = null
        private var done = false
        private var timeout: Job? = null
        private val started = SystemClock.elapsedRealtime()
        private val vehicleData = UUID.fromString("00002020-0000-1000-8000-00805f9b34fb")

        fun begin() {
            emit("op_start", profileId, op, buildJsonObject { put("kind", "connect"); put("title", "GATT-Verbindung (nur Lesen) zu $address") })
            timeout = scope.launch { delay(TIMEOUT_MS); finish("timeout", "Keine Fertigstellung nach ${TIMEOUT_MS / 1000} s") }
            try {
                val device = app.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
                gatt = device.connectGatt(app, false, this, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
                emit("connect_requested", profileId, op, buildJsonObject { put("immediateResult", gatt != null) })
                if (gatt == null) finish("error", "connectGatt lieferte null")
            } catch (e: RuntimeException) { finish("error", e.toString()) }
        }

        fun cancel(reason: String) = finish("cancelled", reason)

        @Synchronized
        private fun finish(status: String, detail: String) {
            if (done) return
            done = true
            timeout?.cancel()
            val cleanup = runCatching { gatt?.disconnect(); gatt?.close() }.fold({ "geschlossen" }, { "Fehler: $it" })
            emit("op_result", profileId, op, buildJsonObject {
                put("status", status); put("detail", "$detail · ${SystemClock.elapsedRealtime() - started} ms · GATT $cleanup")
            })
            state.update { it.copy(inspecting = null) }
            if (inspection === this) inspection = null
        }

        private fun blocked(status: Int) = status == 5 || status == 15 || status == 137

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            emit("connection_state", profileId, op, buildJsonObject { put("status", status); put("newState", newState) })
            when {
                newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS ->
                    if (!g.discoverServices()) finish("error", "discoverServices abgelehnt")
                newState == BluetoothProfile.STATE_DISCONNECTED -> finish("error", "Getrennt mit Status $status")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { finish(if (blocked(status)) "blocked" else "error", "Service-Suche Status $status"); return }
            emit("gatt_services", profileId, op, buildJsonObject {
                put("services", JsonArray(g.services.map { s ->
                    buildJsonObject {
                        put("uuid", s.uuid.toString())
                        put("characteristics", JsonArray(s.characteristics.map { c ->
                            JsonPrimitive("${c.uuid} props=0x%02x desc=[${c.descriptors.joinToString { it.uuid.toString() }}]".format(c.properties))
                        }))
                    }
                }))
            })
            val c = g.services.flatMap { it.characteristics }.firstOrNull { it.uuid == vehicleData }
            when {
                c == null -> finish("ok", "Services gelistet, Vehicle-Data 0x2020 nicht vorhanden. Nichts gelesen")
                c.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0 -> finish("ok", "Vehicle-Data 0x2020 nicht lesbar. Nichts gelesen")
                else -> {
                    emit("read_requested", profileId, op, buildJsonObject { put("characteristic", c.uuid.toString()) })
                    if (!g.readCharacteristic(c)) finish("error", "readCharacteristic abgelehnt")
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            val value = c.value
            emit("gatt_read", profileId, op, buildJsonObject {
                put("characteristic", c.uuid.toString()); put("status", status)
                if (status == BluetoothGatt.GATT_SUCCESS && value != null) { put("bytes", value.hex()); put("length", value.size) }
                else put("error", "Callback-Status $status")
            })
            when {
                status == BluetoothGatt.GATT_SUCCESS -> finish("ok", "Gelesen: ${value?.size ?: 0} Byte")
                blocked(status) -> finish("blocked", "Authentifizierung nötig (Status $status), nicht fortgesetzt")
                else -> finish("error", "Lesen fehlgeschlagen, Status $status")
            }
        }
    }

    fun refreshSessions(markInterrupted: Boolean = false) {
        val list = root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { d ->
            runCatching { CaptureJson.decodeFromString(SessionMeta.serializer(), File(d, "session.json").readText()) }.getOrNull()
        }.map { m ->
            if (m.state == "running" && markInterrupted) m.copy(state = "interrupted", bytes = File(root, "${m.sessionId}/events.jsonl").length()).also {
                runCatching { File(root, "${m.sessionId}/session.json").writeText(CaptureJson.encodeToString(SessionMeta.serializer(), it)) }
            } else m
        }.sortedByDescending { it.startedUtc }
        sessions.value = list
    }

    fun loadSession(id: String) {
        if (state.value.running) return
        state.update { it.copy(busy = "Lade Sitzung …") }
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                val idx = CaptureIndex()
                val lines = runCatching { File(root, "$id/events.jsonl").readText() }.getOrDefault("")
                var bad = 0
                var count = 0
                val parts = lines.split('\n')
                parts.forEachIndexed { i, line ->
                    if (line.isBlank()) return@forEachIndexed
                    runCatching {
                        val e = CaptureJson.decodeFromString(Event.serializer(), line)
                        idx.apply(e, Instant.parse(e.timestampUtc).toEpochMilli())
                        count++
                    }.onFailure { bad++ }
                }
                val tail = if (lines.isNotEmpty() && !lines.endsWith("\n")) "Unvollständige letzte Zeile erkannt. " else ""
                idx.markUnfinishedOperations()
                Triple(idx, "$count Ereignisse gelesen. $tail${if (bad > 0) "$bad Zeilen nicht lesbar." else ""}".trim(), 0)
            }
            index = result.first
            snapshot.value = index.snapshot()
            state.update { it.copy(viewingId = id, viewNotice = result.second, busy = null) }
        }
    }

    fun deleteSession(id: String) {
        if (state.value.running && state.value.sessionId == id) return
        scope.launch {
            File(root, id).deleteRecursively()
            if (state.value.viewingId == id) { index = CaptureIndex(); snapshot.value = Snapshot(); state.update { it.copy(viewingId = null, viewNotice = null) } }
            refreshSessions()
        }
    }

    private fun profilesJson(s: Snapshot) = buildJsonObject {
        put("derived", "Snapshot aus events.jsonl, nicht die Quelle der Wahrheit")
        put("profiles", JsonArray(s.profiles.map { p ->
            buildJsonObject {
                put("id", p.id); put("addresses", JsonArray(p.addresses.map { JsonPrimitive(it) }))
                put("hardwareIds", JsonArray(p.hardwareIds.map { JsonPrimitive(it) })); put("packets", p.packets); put("failures", p.failures)
                put("related", JsonArray(p.related.map { JsonPrimitive(it) }))
                put("runs", JsonArray(p.runs.map { r ->
                    buildJsonObject {
                        put("line", lineLabel(r.lineId, r.line)); put("destination", destinationLabel(r.destinationId, r.destination))
                        put("nextStop", stopLabel(r.nextStopId, r.nextStop)); put("course", r.course)
                        put("count", r.count)
                    }
                }))
            }
        }))
    }

    suspend fun export(id: String, zip: Boolean): File? {
        val live = state.value.running && state.value.sessionId == id
        if (live) {
            val done = CompletableDeferred<Unit>()
            channel?.send(Item.Flush(done))
            done.await()
        }
        return withContext(Dispatchers.IO) {
            val d = File(root, id)
            val raw = runCatching { File(d, "events.jsonl").readBytes() }.getOrNull() ?: return@withContext null
            val cut = raw.copyOf(raw.lastIndexOf('\n'.code.toByte()) + 1)
            val cutoff = cut.count { it == '\n'.code.toByte() }
            val out = File(app.cacheDir, "exports").also { it.mkdirs() }
            if (!zip) return@withContext File(out, "$id-events.jsonl").also { it.writeBytes(cut) }
            File(out, "$id.zip").also { target ->
                ZipOutputStream(target.outputStream()).use { z ->
                    fun add(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry("$id/$name")); z.write(bytes); z.closeEntry() }
                    add("events.jsonl", cut)
                    File(d, "session.json").takeIf { it.exists() }?.let { add("session.json", it.readBytes()) }
                    val prof = if (state.value.viewingId == id) profilesJson(snapshot.value).toString().toByteArray()
                        else File(d, "profiles.json").takeIf { it.exists() }?.readBytes()
                    prof?.let { add("profiles.json", it) }
                    File(d, "lookups").listFiles().orEmpty().forEach { add("lookups/${it.name}", it.readBytes()) }
                    add("export.json", """{"exportedUtc":"${Instant.now()}","cutoffSequence":$cutoff,"duringCapture":$live}""".toByteArray())
                }
            }
        }
    }

    fun shareUri(file: File): Uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)

    fun saveToDownloads(file: File): Boolean = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, if (file.extension == "zip") "application/zip" else "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Brunel")
        }
        val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("insert")
        app.contentResolver.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
    }.isSuccess

    fun storageBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clearSessions(demoOnly: Boolean) {
        scope.launch {
            val running = state.value.sessionId.takeIf { state.value.running }
            sessions.value.filter { it.sessionId != running && (!demoOnly || it.demo) }.forEach { File(root, it.sessionId).deleteRecursively() }
            val keep = sessions.value.firstOrNull { it.sessionId == state.value.viewingId }?.takeIf { demoOnly && !it.demo }
            if (state.value.viewingId != running && keep == null) {
                index = CaptureIndex(); snapshot.value = Snapshot(); state.update { it.copy(viewingId = null, viewNotice = null) }
            }
            refreshSessions()
        }
    }

    fun clearView() {
        if (state.value.running) return
        index = CaptureIndex(); snapshot.value = Snapshot(); state.update { it.copy(viewingId = null, viewNotice = null) }
    }

    fun clearExports() {
        scope.launch {
            File(app.cacheDir, "exports").deleteRecursively()
            runCatching { app.contentResolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?", arrayOf("Download/Brunel%")) }
        }
    }

    fun clearNames() {
        File(app.cacheDir, "bus_names.sqlite").delete()
        names = null; lookupHash = null
        state.update { it.copy(namesInfo = "Keine Namenstabelle geladen") }
    }

    fun resetConsent() = prefs.edit().remove("consent").apply()

    private const val QUEUE = 4096
    private const val TIMEOUT_MS = 20_000L
}
