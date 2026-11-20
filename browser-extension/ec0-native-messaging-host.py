#!/usr/bin/env python3
"""Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
Native Messaging Host bridging Chromium/Firefox extensions to ec0's
authenticated loopback IPC server (`~/.config/ec0/daemon_ipc.json`).
Zero shell execution; strict length caps; loopback 127.0.0.1 only.
"""
import json
import os
import socket
import struct
import sys

MAX_MSG_BYTES = 16 * 1024


def read_native_message():
    raw_len = sys.stdin.buffer.read(4)
    if len(raw_len) < 4:
        return None
    msg_len = struct.unpack("<I", raw_len)[0]
    if msg_len == 0 or msg_len > MAX_MSG_BYTES:
        return None
    raw = sys.stdin.buffer.read(msg_len)
    if len(raw) != msg_len:
        return None
    return json.loads(raw.decode("utf-8"))


def write_native_message(obj):
    encoded = json.dumps(obj).encode("utf-8")
    sys.stdout.buffer.write(struct.pack("<I", len(encoded)))
    sys.stdout.buffer.write(encoded)
    sys.stdout.buffer.flush()


def descriptor_path():
    xdg = os.environ.get("XDG_CONFIG_HOME", "").strip()
    base = xdg if xdg else os.path.join(os.path.expanduser("~"), ".config")
    return os.path.join(base, "ec0", "daemon_ipc.json")


def forward_to_daemon(msg):
    path = descriptor_path()
    with open(path, "r", encoding="utf-8") as f:
        desc = json.load(f)
    port = int(desc["port"])
    token = str(desc["token"])
    if not (1 <= port <= 65535) or not token:
        return {"ok": False, "error": "invalid_descriptor"}

    req = {
        "token": token,
        "command": "handoff_tab",
        "payload": {
            "url": str(msg.get("url", ""))[:2048],
            "title": str(msg.get("title", ""))[:128],
            "media_position_sec": max(0, int(msg.get("media_position_sec", 0))),
        },
    }
    line = (json.dumps(req) + "\n").encode("utf-8")
    with socket.create_connection(("127.0.0.1", port), timeout=2.0) as sock:
        sock.sendall(line)
        resp_line = sock.makefile("r", encoding="utf-8").readline(MAX_MSG_BYTES)
    return json.loads(resp_line) if resp_line else {"ok": False}


def main():
    msg = read_native_message()
    if not isinstance(msg, dict):
        return
    try:
        resp = forward_to_daemon(msg)
    except Exception as exc:
        resp = {"ok": False, "error": type(exc).__name__}
    write_native_message(resp)


if __name__ == "__main__":
    main()
