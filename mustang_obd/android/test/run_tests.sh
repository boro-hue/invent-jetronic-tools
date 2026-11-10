#!/usr/bin/env bash
# JVM tests for the Android app's OBD core (no phone needed). Needs: JDK 8+, Python 3.
# Optional: third-party ELM327-emulator on a TCP port -> pass it as $1, e.g.
#   python3 -m elm -n 35000 -s car   (pip install ELM327-emulator)   then   ./run_tests.sh 35000
set -euo pipefail
cd "$(dirname "$0")"
EMU_PORT="${1:--}"
WORK="$(mktemp -d)"
trap 'kill $(jobs -p) 2>/dev/null || true; rm -rf "$WORK"' EXIT
unset JAVA_TOOL_OPTIONS || true

javac --release 8 -nowarn -d "$WORK/classes" ../src/io/github/borohue/mustangobd/{Transport,Elm,Pids,Obd,Session,LogSession,Snapshot,Json,BuildInfo,SimTransport,Car}.java JvmTests.java
python3 make_vectors.py > "$WORK/vectors.txt"

python3 sim_server.py 35101 messages 0 > "$WORK/sim1.log" 2>&1 &
python3 sim_server.py 35102 frames 5 > "$WORK/sim2.log" 2>&1 &
python3 sim_server.py 35103 messages 0 w211 > "$WORK/sim3.log" 2>&1 &
python3 sim_server.py 35104 messages 3 w211 iso9141 > "$WORK/sim4.log" 2>&1 &
sleep 1

# the Python tool's CSV header on the same simulator, to compare column naming
python3 ../../mustang_obd.py --tcp 127.0.0.1:35101 --log --seconds 2 > "$WORK/py.log" 2>&1
PYCSV="$(ls -t ../../logs/mustang_2*.csv | head -1)"
sleep 1   # distinct timestamp for the next log file name
python3 ../../mustang_obd.py --tcp 127.0.0.1:35103 --log --seconds 2 \
    --profile ../../profile_w211_e220cdi_2005.json > "$WORK/py_w211.log" 2>&1
PYW211="$(ls -t ../../logs/mustang_2*.csv | head -1)"

java -cp "$WORK/classes" io.github.borohue.mustangobd.JvmTests "$WORK/vectors.txt" 35101 35102 "$EMU_PORT" \
    "$PYCSV" 35103 35104 "$PYW211"
rm -f "$PYCSV" "${PYCSV%.csv}.meta.json" "$PYW211" "${PYW211%.csv}.meta.json"
