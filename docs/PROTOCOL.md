# Invent Jetronic serial protocol (reverse-engineered July 2026)

Source: USBPcap capture of an InventCommander 4.1.7 ↔ ECU "Jetronic_XXXX" session
(`commander_session.pcap`, 2.1 MB — the author's capture, not distributed). No public spec exists (vendor
confirmed proprietary), so this was derived from traffic. **Verified**: frame parsing +
CRC pass 100 % on captured frames; confirmed channels decode to correct idle values.

## Link  (VERIFIED from FTDI control transfers + live logging 2026-07-21)
- FTDI FT232R USB-serial, **COM3**.
- **Runtime data protocol baud = 19200, 8N1** (from FTDI SET_BAUD wValue=0x809C, SET_DATA=0x0008).
  NOTE: 115200 is only the BOOTLOADER baud ('B' at power-on) — the running ECU talks at **19200**.
- **Line state required: RTS asserted (high), DTR low.** With RTS low the ECU does not reply.
- The adapter is half-duplex/K-line style: it **echoes your own TX back on RX**. Every reply is
  preceded by an echo of the poll (a cmd-0x00 frame) — the reader skips it by matching reply cmd.
- Frame = `55 00 AA 00 | LEN | body(LEN)`. **No trailing byte** after body (an earlier +1 assumption
  was a bug — it ate the next frame's sync).
- Master/slave: ECU never speaks unpolled. ~20 Hz polling is comfortable (Commander used ~11 Hz).

## Frame format (both directions)
```
55 00 AA 00 | LEN | <body: LEN bytes> 
```
- `55 00 AA 00` — 4-byte sync.
- `LEN` — number of body bytes.
- body[0] = command/reply id; remaining bytes = data.
- **Checksum:** the short 3-byte CONTROL frames satisfy `XOR(LEN, *body) == 0`. The 51-byte
  DATA frame does NOT (verified: only ~3/1261 pass) — it uses a different checksum
  (exe strings suggest CRC16-CCITT) or none. **Framing is done on sync(4)+LEN instead**;
  that is reliable (data decodes correctly). Do not XOR-gate data frames.
- Live data frame body = `01` + **50 data bytes** (d0..d49). Earlier "49 + CRC" was wrong;
  the last byte d49 is data (constant 8 at idle — a status/flag byte, not a checksum).

## Commands observed
| Host sends (full frame) | ECU replies | Meaning |
|---|---|---|
| `55 00 AA 00 03 00 03 00` | `01`-frame, LEN=0x33 (51) | **Live telemetry** (poll this continuously) |
| `55 00 AA 00 03 F0 F3 00` | `F1`-frame, LEN=0x1F | Firmware version (ASCII, e.g. "4.1.7") |
| `55 00 AA 00 03 90 93 00` | `91`-frame, LEN=0x43 | ECU name (ASCII "Jetronic_XXXX") |
(`read from ECU` / map read + flash commands not yet mapped — not needed for logging.)

## Live telemetry frame (reply id 0x01)
LEN=0x33=51 → body = `01` + **50 data bytes** (d0..d49; d49 is a status byte, not a checksum — see above).
Data offsets (0-based, after the 0x01 id):

### VERIFIED MAP (align_decode 2026-07-21, combined capture, R²≥0.99 unless noted)
`d[i]` = 0-based offset into the 50 data bytes. Decode = mul·raw + add.

| channel | encoding | mul | add |
|---|---|---|---|
| **Rpm** | 6.24616e6 / LE[5,6] | — | RPM stored as PERIOD (inverse) |
| **Map** (kPa) | d[24] | ×2.0 | |
| **Clt** (°C) | d[10] | ×1 | |
| **Iat** (°C) | d[11] | ×1 | |
| **InjTime** (ms) | LE[8,9] | ×0.00642 | |
| **InjCount** | d[7] | ×1 | |
| **InjEndAngle** (°) | d[33] | ×2.0 | |
| **Tps** (%) | d[13] | ×0.3929 | −0.45 |
| **Afr** | d[18] | ×0.1165 | (hi byte of lambda ADC) |
| **TargetAfr** | d[27] | ×0.11497 | 0x80→14.7 |
| **LambdaCorrFast** (%) | s8[21] | ×0.992 | signed |
| **LambdaCorrSlow** (%) | s8[22] | ×0.999 | signed |
| **IdlePos** (%) | d[23] | ×0.388 | +0.3 |
| **Voltage** (V) | d[12] | ×0.0999 | |
| **Load16** | d[4] | ×0.0625 | (=/16) |
| **ChargeTemp** (°C) | d[35] | ×1 | |
| **Airflow** | d[31] | ×1 | |
| **MafEmu** | d[3] | ×0.0197 | (≈MAP-in-kPa at idle — earlier false MAP guess!) |
| **FuelConsumed** | LE[36,37] | ×1e-5 | accumulator, may wrap |
| **MapVoltage** (V) | d[40] | ×0.0196 | |
| **CltVoltage** (V) | d[15] | ×0.02 | |
| **IatVoltage** (V) | d[16] | ×0.02 | |
| **TpsVoltage** (V) | LE[13,14] | ×7.6e-5 | raw TPS ADC |
| **LambdaVoltage** (V) | BE[17,18] | ×7.6e-5 | raw lambda ADC |
| **Runlevel** | d[0] | ×1 | =3 running |

### NOT YET LOCKED (constant during the decode session → need a log where they MOVE)
InjDuty (collided with d[33]), AccelCorr / AccelTotalCorr / AccelFlag (no tip-in enrichment fired),
AfrError, InjChargeTCorr, LambdaHeating, Angle (=0, EZL owns ignition), Dwell, Debug1/2,
and all error/status/input *Flag bits (were 0/constant). All still logged raw as d00..d49.
Re-run align_decode on a drive log that exercises accel enrichment / faults to lock these.

## How to extend / re-derive the map for your firmware (zero guessing)
USBPcap sniffs at USB level *below* Commander, so **Commander can log its own CSV at the
same time** as a USBPcap capture (no COM-port conflict). Procedure:
1. Plug in cable, ignition ON, engine idling. Run `re-tools/capture_combo.ps1` (elevated) to start USBPcap.
2. In Commander: connect, then **START ITS OWN CSV LOGGING**.
3. Make values MOVE for ~2-3 min: several throttle blips, let it warm (CLT rise), rev holds,
   a short drive if safe. Movement in every channel = clean regression.
4. Stop Commander logging; note the CSV filename it wrote to Logs/.
5. Run:  `py re-tools\decode_pcap.py`  (point PCAP at combo_session.pcap) then
         `py re-tools\align_decode.py captures\combo_session.pcap "Logs\...<that CSV>.csv"`
   -> it time-aligns via Map(d3) and linear-regresses every CSV channel vs every byte decode,
   writing captures/channel_map.txt with per-channel byte offset + scale + R^2.
6. Paste the R^2>0.98 rows into the CHANNELS table in jetronic_logger.py. Done — full labelled logger.

STATUS 2026-07-21 (final): the combined capture was done and the 25 channels in the table
above are locked by align_decode at R²≥0.99, shipped in `jetronic_logger.py` (CHANNELS).
Earlier hand-guesses were corrected in the process: d3 = MafEmu (not Map — coincidence at
idle), TargetAfr = d27 (not d1), RPM is period-encoded. Transient/flag/dwell/debug channels
remain unlocked (they never moved during the decode session) but are always logged raw as
d00–d49 — the procedure above is exactly how to lock them, or to re-derive the whole map
on a different firmware. Fuzzy matching without time alignment (an earlier attempt) gave
contradictory hits and is NOT trusted; use the combined capture.
