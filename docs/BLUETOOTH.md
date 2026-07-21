# Bluetooth logging (Invent Jetronic)

The ECU's Bluetooth module is an **HC-06 (Bluetooth Classic / SPP)**:
- device name: **Jetronic**
- pairing PIN: **0000**
- Bluetooth 2.0, Serial Port Profile (not BLE)

Because it's Classic SPP, Windows turns it into an ordinary virtual COM port once paired,
so `jetronic_logger.py` talks to it with the **exact same protocol** as the USB/FTDI cable.
The only difference is which COM port, plus a short connect delay.

Wireless logging is ideal for driving: laptop on the seat, no cable to the ECU.

## One-time pairing (Windows)
1. Ignition ON so the HC-06 is powered (its LED blinks when unpaired).
2. Settings → Bluetooth & devices → Add device → Bluetooth.
3. Pick **"Jetronic"**. If asked for a PIN, enter **0000**.
4. After pairing, Windows creates a **"Standard Serial over Bluetooth link (COMx)"** —
   usually TWO ports appear (Incoming + Outgoing). We use the **Outgoing** one.
   Find it: Settings → Bluetooth → More Bluetooth settings → COM Ports tab (note the "Outgoing" COMx),
   or just run:  `py jetronic_logger.py --list-bt`

## Use
```
py jetronic_logger.py --bt --handshake                  # smoke test (fw + name + live poll)
py jetronic_logger.py --bt --log --hz 20 --seconds 600  # 10-min wireless log
py jetronic_logger.py --port COM7 --log                 # if auto-detect picks the wrong BT port
```
- `--bt` auto-finds the Bluetooth SPP port and retries the connection while the RFCOMM link
  comes up (a few seconds is normal on first connect).
- **Close InventCommander first** — the ECU accepts only one connection at a time
  (Commander over BT OR this logger, never both).

## Notes / gotchas
- Baud is nominal over SPP (BT ignores it); the module<->ECU UART baud is fixed in the HC-06.
- Effective BT 2.0 SPP throughput easily covers the poll; expect a little more latency/jitter
  than USB, so achieved Hz may be slightly lower. Raw bytes are unaffected.
- If `--list-bt` shows nothing: not paired yet, or ignition off. Re-pair with ignition on.
- If connection drops mid-drive (BT range/interference), the logger keeps trying to read;
  restart the command if it stalls. USB cable remains the more reliable option for critical logs.
- **Label-finalization (the combined USBPcap+Commander capture) must still be done over USB** —
  BT can't be passively sniffed, and Commander + logger can't both hold the BT link at once.
  Once labels are locked over USB, they apply identically to BT logs.
