# How the Bluetooth bus radar works

Status: explanation of the original PaderSprinter Mobil 8.7.8 app, not a claim about tested vehicle behavior.

The bus radar identifies nearby vehicles and lets passengers request a stop, boarding assistance, or a door-finding signal. It does not measure distance like a physical radar.

The [capture-page specification](bluetooth-capture-spec.md) describes the proposed debug feature for Brunel. The [field-test checklist](bluetooth-field-test.md) describes the planned Paderborn visit on 24 October 2026.

## Discovery does not require a connection

A Bluetooth Low Energy module broadcasts short advertising packets from the bus. Nearby phones receive these packets without a GATT connection.

In the ivanto system design, the module gets vehicle data from the onboard computer through IBIS. The module broadcasts that data. It does not calculate the route itself.

The original app scans for advertisements, filters for the BusAccess service identifier `1f1f`, and parses manufacturer-specific data. It selects the parser through the packet's protocol version.

The app processes scan results in one-second windows. It removes vehicles after five missed windows or more than ten seconds without a sighting. Signal strength helps resolve certain duplicate entries. It does not provide an exact distance, direction, or general proximity ranking.

An advertisement is a broadcast, not a response to a passenger request. Its broadcast interval is not established by the static analysis.

## Small identifiers become readable bus information

The original app's company database maps protocol `16` and transport-company ID `4` to `Padersprinter`. That mapping is evidence of support, not a recording from a real bus.

The version-16 manufacturer payload includes these fields:

- Service flags at byte 4.
- Transport-company ID at byte 5.
- Line ID at bytes 6 to 7.
- Destination ID at bytes 8 to 9.
- Next-stop ID at bytes 10 to 11.
- Hardware ID at bytes 12 to 13.
- Course at bytes 14 to 15.

The two-byte identifiers above use little-endian order. The protocol version at bytes 2 to 3 uses big-endian order. Offsets refer to the manufacturer payload, not the full scan record.

The original app resolves line, destination, and stop IDs through a local database. It can update those lookup files through these routes:

```text
GET https://service.ivanto.de/srs/api/v1/bluetooth/updates/
GET https://service.ivanto.de/srs/api/v1/bluetooth/?filename={name}
```

The general SRS client adds its tenant and app-identification query parameters. A lookup-file download is separate from nearby vehicle discovery.

## Passenger requests have two delivery paths

For a direct request, the phone connects to the selected Bluetooth address and writes one byte to GATT characteristic `00001f20-0000-1000-8000-00805f9b34fb`.

The commands are `0x01` for a stop request, `0x02` for boarding assistance, and `0x03` for the door-finding signal. These are vehicle-control requests, not harmless connection tests.

The original app also provides these HTTPS action routes:

```text
POST https://service.ivanto.de/busaccess/stopRequest
POST https://service.ivanto.de/busaccess/serviceRequest
POST https://service.ivanto.de/busaccess/doorsignalRequest
```

Each route accepts a JSON object with one field, `btMac`, containing the selected vehicle's Bluetooth address. The route selects the action. The body selects the vehicle.

The app combines Bluetooth delivery and server delivery as competing paths. The server's onward connection to the bus is not visible in the APK. LTE, a broker, and any particular onboard interface remain unproven for this path.

## Completion is not proof of physical action

The Bluetooth path completes when the characteristic write completes. The inspected path does not wait for a separate proof that the driver saw the request.

The server response model has a Boolean `status`. A true value completes the server path. A false value or an HTTP error leaves that path pending in the inspected implementation. This allows the Bluetooth path to complete instead.

An outer ten-second timeout bounds the combined operation. After success, the original app shows a confirmation and applies the configured one-second request cooldown.

The expected server model is not a captured server response. Neither a successful write nor a true server status proves that assistance arrived or a speaker emitted sound.

## Additional reads are separate from control commands

The APK also supports protocol 18. Its vehicle-data code can read characteristic `00002020-0000-1000-8000-00805f9b34fb` for destination, stop-list, and optional via text.

The original radar configuration sets `useVehicleData=false`. The UI does not consume that vehicle-data payload in this build. That setting alone does not prove that every earlier read is skipped.

The separate `BusCoreApi` challenge-response implementation is not the visible BusAccess radar path. The packaged MQTT vehicle-map feature is also separate and disabled in this build.

## Why the bus has this hardware

The main purpose is accessible passenger information and interaction. Bluetooth identifies the actual vehicle nearby, rather than only the bus expected by the timetable.

That distinction helps passengers who cannot read the destination display. Boarding-assistance requests avoid the need to reach a physical button. An acoustic door-finding signal can help a blind passenger locate the entrance.

The APK does not establish the hardware model installed on each PaderSprinter bus. It also does not prove that every vehicle supports every action.

## Evidence and limits

The main source is a static analysis of the original app (sections 3.6, 5, and 8 of that report). That investigation did not execute the app, query action endpoints, or communicate with a real bus. The response handling was additionally checked in the decompiled `RequestSenderManager.java` and `RequestApiResponse.java` of the `de.geomobile.busaccess.api.sender` package.

Platform and manufacturer references:

- [Android: Bluetooth Low Energy overview](https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview).
- [Android: transfer BLE data](https://developer.android.com/develop/connectivity/bluetooth/ble/transfer-ble-data).
- [ivanto system overview](https://ivanto.geomobile.de/).
- [ivantoConnect product description](https://www.ivanto.de/_Resources/Persistent/60e3ead954a8947e2af2a0b059761584e71ee568/ivantoConnect_Flyer_V1911%20%28web%29.pdf).
