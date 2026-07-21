# Free logging tools for Invent Jetronic + a warning about the 4.1.6 → 4.1.7 update

Hi everyone. I converted a Mercedes M103 3.0 (W124/W126-era, originally KE-Jetronic) to an Invent Jetronic standalone — fuel and idle control only, stock EZL ignition kept. While chasing my own tuning problems I ended up reverse-engineering the ECU's serial protocol and building a free standalone logger, plus documentation of everything I learned. It is all public here: **https://github.com/boro-hue/invent-jetronic-tools**. But first, one thing you should check.

## ⚠ If you updated firmware 4.1.6 → 4.1.7: check your map

On my ECU, the 4.1.7 updater migrated the map's Param2 settings block **positionally** into the new layout (43 → 41 bytes). Thirteen tail bytes landed in re-purposed slots: index 30 became 0 where every stock 4.1.7 calibration carries 50 (a transient threshold), and indexes 34/37 were clamped from 140/180 down to 30. The fuel tables were untouched, so the map *looks* fine in the app.

Symptoms on my car (byte-identical map, only firmware changed):

- accel correction active in 84 % of driving samples, even at steady state
- lambda correction reset to 0 by tiny (~2 kPa) MAP wobbles — 30 resets per log vs 1 on 4.1.6
- lean, jumpy idle and a slowly growing rpm surge in Drive — after ~20 min it **stalled the engine**

**One-line check:** read your ECU back and look at `Param2raw` byte 30 (0-based) in the .imf — if it is 0, you are affected (never 0 in any shipped 4.1.7 calibration I checked). Re-flashing your old map through the 4.1.7 app does **not** fix it — it migrates the same way (verified against read-back). The working fix (transplanting the stock 4.1.7 Param2 tail) and the full evidence chain are written up in the repo.

## The free logger

`jetronic_logger.py` — gap-free CSV logging at 20 Hz over USB or Bluetooth, including while driving. On my setup, Commander's own CSV logging captured only short bursts with minutes-long gaps once moving (93 % of one drive lost); this logger recorded 7 continuous minutes with zero gaps. It decodes 25 channels (RPM, MAP, CLT/IAT, injection time, TPS, lambda plus fast/slow corrections, idle valve position, voltage, …) and stores all 50 raw bytes of every frame for future decoding.

```
py -m pip install pyserial
py jetronic_logger.py --handshake            # USB smoke test
py jetronic_logger.py --bt --log --hz 20     # wireless drive log (pair "Jetronic", PIN 0000)
```

Close Commander first — the ECU accepts one connection at a time.

## Also in the repo

- **docs/PROTOCOL.md** — the serial protocol: framing, commands, verified channel map
- **docs/BLUETOOTH.md** — pairing and wireless-logging guide
- **re-tools/** — the toolkit that auto-derives the channel map, so you can extend it to other engines and firmware versions
- M103 conversion field notes (injector-flow preset trap, idle-target ledge, methodology)
- a flash-hygiene guide — the read-back habits that caught all of the above
- a ready-to-send bug report for Invent about the migration issue

## Want to help?

- Run the logger on other firmware versions and engines (other Mercedes, Audi, …) and report back
- Contribute channel maps via the re-tools workflow
- Diff your own pre/post-update maps — confirm or refute the migration bug on more samples
- Open issues and PRs on GitHub

## Fine print

Everything here is **unofficial**: reverse-engineered for interoperability with my own hardware, not affiliated with or endorsed by Invent. Genuine respect to Invent — the hardware is great value; the goal is to help users and get one migration bug fixed, and a detailed report is going to them directly. Use at your own risk: modifying ECU data can stall or damage an engine, so verify every change stationary before driving. And please, no vendor software or firmware files in this thread or in the repo — tools, docs, and read-outs of your own ECU only.
