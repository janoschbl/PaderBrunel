package dev.padermodern.data

import org.junit.Assert.*
import org.junit.Test

class BusAdvertisementTest {
    @Test fun parsesUnsignedLittleEndianAndSimulator() {
        val record = BusAdvertisement.parse(packet())!!
        assertEquals(0x80ff, record.lineId)
        assertEquals(3, record.destinationId)
        assertEquals(4, record.nextStopId)
        assertEquals(5, record.hardwareId)
        assertEquals(6, record.course)
        assertFalse(record.simulator)
        assertTrue(BusAdvertisement.parse(packet(simulator = true))!!.simulator)
    }

    @Test fun rejectsMalformedAndUnsupportedAdvertisements() {
        assertNull(BusAdvertisement.parse(byteArrayOf(5, 3, 0x1f)))
        assertNull(BusAdvertisement.parse(packet().copyOfRange(0, 8)))
        assertNull(BusAdvertisement.parse(packet(protocol = 15)))
        assertNull(BusAdvertisement.parse(packet(company = 3)))
        assertNull(BusAdvertisement.parse(packet(header = byteArrayOf(1, 2))))
        assertNull(BusAdvertisement.parse(packet(includeService = false)))
    }

    @Test fun requiresExactDirectionAndUniqueNextStop() {
        val leg = PartialRoute(shortName = "4", lineDirection = "Paderborn, nach Rathaus", stations = listOf(
            Station(name = "Bahnhof", locality = "Paderborn"), Station(name = "Rathaus", locality = "Paderborn")
        ))
        val names = BusNames(mapOf(1 to "4"), mapOf(2 to "Rathaus"), mapOf(3 to "Bahnhof"))
        val rec = BusRecord(4, 1, 2, 3, 0, 0)
        assertEquals(0, names.match(rec, leg))
        assertNull(names.match(rec.copy(simulator = true), leg))
        assertNull(names.match(rec, leg.copy(lineDirection = "Markt")))
        assertNull(names.match(rec, leg.copy(stations = leg.stations + leg.stations.first())))
    }

    @Test fun trackerNeedsDistinctGpsConfirmedObservationsAndMonotonicChanges() {
        val tracker = BusMatchTracker()
        assertNull(tracker.observe("a", 2, 0, 2))
        assertFalse(tracker.matched)
        tracker.observe("a", 2, 2_000, null)
        assertFalse(tracker.matched)
        tracker.observe("a", 2, 2_000, 2)
        assertTrue(tracker.matched)
        assertEquals(2, tracker.nextIndex)
        assertNull(tracker.observe("a", 1, 3_000, 1))
        assertNull(tracker.observe("a", 3, 4_000, null))
        assertEquals(3, tracker.observe("a", 3, 6_000, null))
        assertEquals(3, tracker.nextIndex)
    }

    @Test fun duplicatedTimestampsDoNotConfirmBus() {
        val tracker = BusMatchTracker()
        tracker.observe("a", 2, 0, 2)
        tracker.observe("a", 2, 0, 2)
        assertFalse(tracker.matched)
        tracker.observe("a", 2, 1_999, 2)
        assertFalse(tracker.matched)
    }

    @Test fun boundBusContinuesThroughGpsLossAndStopsAtExpiry() {
        val tracker = BusMatchTracker()
        tracker.observe("a", 1, 0, 1); tracker.observe("a", 1, 2_000, 1)
        assertTrue(tracker.matched)
        tracker.observe("a", 1, 8_000, null)
        tracker.observe("a", 2, 10_000, null)
        assertEquals(2, tracker.observe("a", 2, 12_000, null))
        assertTrue(tracker.expire(22_000))
        assertFalse(tracker.expire(22_001))
    }

    @Test fun ambiguityRecoversOnlyForOriginalBoundBus() {
        val tracker = BusMatchTracker()
        tracker.observe("a", 2, 0, 2); tracker.observe("a", 2, 2_000, 2)
        tracker.observe("b", 2, 3_000, 2)
        assertFalse(tracker.matched)
        tracker.observe("a", 2, 9_000, null)
        assertFalse(tracker.matched)
        assertTrue(tracker.expire(13_001))
        assertFalse(tracker.ambiguous)
        tracker.observe("a", 3, 14_000, null)
        assertEquals(3, tracker.observe("a", 3, 16_000, null))
    }

    @Test fun matchesStreetAbbreviationButNotMissingLineOrDirection() {
        val names = BusNames(mapOf(1 to "8"), mapOf(2 to "Neuenbeken"), mapOf(3 to "Cheruskerstraße"))
        val leg = PartialRoute(shortName = "8", lineDirection = "Neuenbeken", stations = listOf(Station(name = "Cheruskerstr.")))
        val record = BusRecord(4, 1, 2, 3, 4, 5)
        assertEquals(0, names.match(record, leg))
        assertNull(names.match(record, leg.copy(shortName = null)))
        assertNull(names.match(record, leg.copy(lineDirection = null)))
        assertNull(names.match(record.copy(company = 3), leg))
    }

    @Test fun ambiguityAndExpiryNeverSilentlyRebind() {
        val tracker = BusMatchTracker()
        tracker.observe("a", 2, 0, 2); tracker.observe("a", 2, 2_000, 2)
        assertTrue(tracker.matched)
        tracker.observe("b", 3, 3_000, 3)
        assertTrue(tracker.ambiguous)
        assertFalse(tracker.matched)
        tracker.expire(14_000)
        assertFalse(tracker.matched)
        tracker.observe("b", 3, 15_000, 3); tracker.observe("b", 3, 17_000, 3)
        assertFalse(tracker.matched)
    }

    @Test fun returningBusNeedsFreshConfirmationAfterSilence() {
        val tracker = BusMatchTracker()
        tracker.observe("a", 2, 0, 2); tracker.observe("a", 2, 2_000, 2)
        assertTrue(tracker.matched)
        assertFalse(tracker.expire(12_001))
        tracker.observe("a", 2, 13_000, null)
        assertFalse(tracker.expire(14_000))
        tracker.observe("a", 2, 15_000, null)
        assertTrue(tracker.matched)
        tracker.observe("a", 3, 16_000, null)
        assertFalse(tracker.matched)
        assertEquals(3, tracker.observe("a", 3, 18_000, null))
        assertTrue(tracker.matched)
    }

    // real packets and reads from the 24 October 2026 field log
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val line8 = hex("13ff787d001213e121040101803800000000000002010603031f1f0409697669")
    private val line12 = hex("040969766313ff787d001214e119d05181803132000000000003031f1f0b09426c75655a20352e3738")
    private val line1 = hex("0319000002010603031f1f13ff787d0010050401006f00e903000300000000")
    private val atRathausplatz = hex("4e6575656e62656b656e486f6c7467726576656e73747261c39f651d52617468617573706c61747a1d")
    private val atFroebel = hex("4e6575656e62656b656e486f6c7467726576656e73747261c39f651d4672c3b662656c73747261c39f651d")
    private val line12Read = hex("4b61756b656e626572677669612041756620646572204c696574684b61756b656e6265726776696120476f6c64626572671d5765737465726e746f721d")
    // shortName, lineDirection and stops as the routes api returns them for Hauptbahnhof to Langer Weg
    private val leg8 = PartialRoute(shortName = "8", lineDirection = "Neuenbeken", stations = listOf("Hauptbahnhof", "Westerntor",
        "Rathausplatz", "Kamp", "Am Bogen", "Gierstor", "Fröbelstraße", "Langer Weg").map { Station(name = it, locality = "Paderborn") })

    @Test fun parsesProtocol18LineTextAndDataVersion() {
        val bus = BusAdvertisement.parse(line8)!!
        assertEquals("8", bus.line); assertEquals(3, bus.dataVersion); assertFalse(bus.simulator)
        assertEquals("12", BusAdvertisement.parse(line12)!!.line)
        assertNull(BusAdvertisement.parse(line8.copyOf().also { it[13] = 0x00 }))
        val oldStyle = BusAdvertisement.parse(line1)!!
        assertEquals(1, oldStyle.lineId); assertEquals(1001, oldStyle.nextStopId); assertNull(oldStyle.line)
    }

    @Test fun parsesVehicleDataFields() {
        assertEquals(VehicleData("NeuenbekenHoltgrevenstraße", "Rathausplatz"), VehicleData.parse(atRathausplatz))
        assertEquals("Westerntor", VehicleData.parse(line12Read)!!.nextStop)
        assertNull(VehicleData.parse("Neuenbeken".toByteArray()))
        assertNull(VehicleData.parse(ByteArray(0)))
    }

    @Test fun matchesProtocol18BusByTextAgainstApiLeg() {
        val bus = BusAdvertisement.parse(line8)!!
        assertEquals(2, bus.match(VehicleData.parse(atRathausplatz)!!, leg8))
        assertEquals(6, bus.match(VehicleData.parse(atFroebel)!!, leg8))
        assertEquals(0, bus.match(VehicleData("NeuenbekenHoltgrevenstraße", "Hauptbahnhof"), leg8))
        assertNull(bus.match(VehicleData("Hauptbahnhof", "Rathausplatz"), leg8))
        assertNull(bus.match(VehicleData("NeuenbekenHoltgrevenstraße", "Detmolder Tor"), leg8))
        assertNull(bus.match(VehicleData.parse(atRathausplatz)!!, leg8.copy(shortName = "11")))
        assertNull(bus.match(VehicleData.parse(atRathausplatz)!!, leg8.copy(lineDirection = null)))
        assertNull(BusAdvertisement.parse(line12)!!.match(VehicleData.parse(line12Read)!!, leg8.copy(shortName = "11", lineDirection = "Kaukenberg")))
        assertNull(BusNames(mapOf(0 to "8"), emptyMap(), emptyMap()).match(bus, leg8))
    }

    private fun packet(
        protocol: Int = 16, company: Int = 4, simulator: Boolean = false,
        header: ByteArray = byteArrayOf(if (simulator) 0x79 else 0x78, 0x7d.toByte()), includeService: Boolean = true,
    ): ByteArray {
        val data = ByteArray(18)
        data[0] = header[0]; data[1] = header[1]; data[2] = (protocol shr 8).toByte(); data[3] = protocol.toByte()
        data[5] = company.toByte()
        listOf(0x80ff, 3, 4, 5, 6).forEachIndexed { n, value -> data[6 + n * 2] = value.toByte(); data[7 + n * 2] = (value shr 8).toByte() }
        val service = if (includeService) byteArrayOf(3, 3, 0x1f, 0x1f) else byteArrayOf(2, 1, 6)
        return service + byteArrayOf(19, 0xff.toByte()) + data
    }
}
