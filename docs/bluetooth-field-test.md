# Capture bus observations in Paderborn

Planned date: 24 October 2026. This checklist applies after the [capture-page specification](bluetooth-capture-spec.md) is implemented. The current app does not yet provide the specified recorder.

## Prepare the phone before departure

1. Install the debug build on the physical phone you will carry.
2. Grant the permissions requested by the capture page.
3. Start a short test session.
4. Check that the page shows a growing duration and a healthy writer state.
5. Add a note with a recognizable test label.
6. Stop the session.
7. Export the plain JSONL log and the complete session ZIP.
8. Open the export on another device.
9. Check that the note, session metadata, and stop event are present.
10. Load the name lookup database before leaving network coverage.
11. Check available storage and battery charge.
12. Pack a power bank.

If a second test device is available, send labeled simulator advertisements. Check that their raw bytes appear in a test session. Do not count simulator results as real-bus evidence.

## Verify interruptions before the visit

1. Start another test capture.
2. Open a different app screen.
3. Return to the debug page and check continuity.
4. Lock the phone for two minutes.
5. Unlock the phone and check for a capture gap.
6. Disable Bluetooth.
7. Check that the log records the interruption.
8. Enable Bluetooth and check the recorded resume behavior.
9. Stop and export the session.

If locked-screen capture is not verified, keep the app visible during observations. Do not assume that an active notification proves continued packet reception.

## Record the field session

1. Start capture before the first bus arrives.
2. Keep the default passive mode enabled.
3. Add a note with the stop name and your observation point.
4. When a bus arrives, note its visible line and destination.
5. If visible, note the fleet number separately from the decoded hardware ID.
6. Check the received-event and persisted-event counters.
7. Keep unresolved bus entries and parse failures.
8. Observe several buses on the same line when possible.
9. Add notes when you board, leave, or move to another stop.
10. Export a checkpoint after the first useful capture.

Keep external observations separate from decoded fields. A packet showing a next-stop ID does not by itself prove that the bus is at that stop.

## Inspect an authorized vehicle

1. Confirm that the vehicle is within the authorized test scope.
2. Select the observed device in the debug page.
3. Start one read-only inspection.
4. Wait for completion or the recorded timeout.
5. Check the discovered UUIDs and any allowlisted read result.
6. Check that the connection closed.

If authentication or pairing is required, retain the blocked result. Do not continue into authentication or send vehicle-control commands.

## Preserve the recording

1. Stop capture at the end of the session.
2. Wait for the writer to finish its final flush.
3. Export the full session ZIP.
4. Copy the ZIP to a second device.
5. Open `events.jsonl` and check the final session state.
6. Check any reported drop count, gap, or incomplete-capture warning.
7. Keep the original recording until the copied export has been verified.

Before sharing an export, review Bluetooth addresses, location records, and free-text notes. Use a redacted copy for public reports.

## Review the observations afterward

1. Compare visible line and destination notes with the decoded fields.
2. Check whether company ID `4` and protocol `16` appear on real buses.
3. Check whether each observed address has a consistent hardware ID.
4. Check for multiple addresses with the same hardware ID.
5. Compare line, course, destination, and next-stop changes over time.
6. List unknown payloads and parser errors before changing the decoder.
7. Compare scan-only sessions with authorized inspection sessions for interruptions.
8. List connection failures by original status code and elapsed time.

Treat successful discovery, successful reads, and passenger-control delivery as separate outcomes. This capture plan does not test passenger-control delivery.
