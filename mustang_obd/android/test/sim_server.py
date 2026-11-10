#!/usr/bin/env python3
"""Serve the Python ELM327 + Mustang simulator (../../elm_sim.py) over TCP, like a WiFi
adapter, so the Java core can be tested against it:

    python3 sim_server.py PORT [count_semantics] [chunk] [vehicle] [quirk]

count_semantics: messages | frames | ignore (default messages); chunk: max bytes per send
(default 0 = whole reply) to exercise reassembly of fragmented adapter output; vehicle:
mustang (CAN, default) | w211 (K-line); quirk: e.g. iso9141 (W211 answers as ISO 9141-2).
"""
import os
import socket
import sys
import threading

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
from elm_sim import SimElm327  # noqa: E402


def handle(conn, semantics, chunk, vehicle, quirks):
    elm = SimElm327(count_semantics=semantics, vehicle=vehicle, quirks=quirks)
    buf = ""
    with conn:
        while True:
            try:
                d = conn.recv(4096)
            except OSError:
                return
            if not d:
                return
            for ch in d.decode("ascii", "replace"):
                if ch != "\r":
                    buf += ch
                    continue
                out = elm.handle(buf).encode("ascii")
                buf = ""
                step = chunk or len(out)
                for i in range(0, len(out), step):
                    conn.sendall(out[i:i + step])


def main():
    port = int(sys.argv[1])
    semantics = sys.argv[2] if len(sys.argv) > 2 else "messages"
    chunk = int(sys.argv[3]) if len(sys.argv) > 3 else 0
    vehicle = sys.argv[4] if len(sys.argv) > 4 else "mustang"
    quirks = {sys.argv[5]} if len(sys.argv) > 5 else set()
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(4)
    print(f"sim on 127.0.0.1:{port} ({vehicle}, {semantics}, chunk {chunk}, {quirks})", flush=True)
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=handle, args=(conn, semantics, chunk, vehicle, quirks), daemon=True).start()


if __name__ == "__main__":
    main()
