# Sniffing the InventCommander ↔ ECU protocol

Goal: capture the raw USB-serial traffic while InventCommander shows live data,
so the protocol (poll commands + reply frames) can be decoded and a standalone
continuous logger built.

Windows allows only ONE program on COM3 at a time, so we capture at the USB
level underneath InventCommander — the app keeps working normally.

## One-time setup

1. Download Wireshark from https://www.wireshark.org/download.html
2. During install, **tick the "USBPcap" component** (it is an optional checkbox).
3. Reboot when asked.

## Capture session (~2 minutes)

1. Car ignition ON (engine can be off or idling — idling is better: live values move).
   Car stationary, handbrake on; the capture is completely passive and writes nothing to the ECU.
2. Start Wireshark → in the capture list pick **USBPcap1** (if several, the one
   whose expander shows "FTDI" / "USB Serial Port").
3. Start the capture (blue fin button).
4. NOW start InventCommander, connect to the ECU as usual, and:
   - watch the live gauges for ~30 s,
   - open the tables/map view once,
   - do a "read from ECU" once (this exposes the read_all command),
   - disconnect.
5. Stop the Wireshark capture (red square).
6. File → Save As → `commander_session.pcap` (pick the classic **pcap** format — the decode
   scripts expect it), into a `captures/` subfolder next to these scripts.

Then decode it with the scripts in this folder: `decode_pcap.py` extracts the two byte
streams, `split_frames.py` / `analyze_frames.py` classify the frames. The frame format and
channel map that came out of exactly this procedure are documented in
[`../docs/PROTOCOL.md`](../docs/PROTOCOL.md) — including how `align_decode.py` locks
channel scalings against a simultaneous Commander CSV log.

## Quick alternative first (no install)

Before Wireshark: with InventCommander CLOSED and ignition ON, run

    py ..\jetronic_logger.py --probe

If the ECU streams data by itself, the probe finds it and the whole USBPcap step
may be unnecessary. (On the documented ECU it does not — the protocol turned out to be
strictly poll/response, see `../docs/PROTOCOL.md` — so expect to need the capture.)
