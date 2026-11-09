#!/usr/bin/env python3
"""LAN-only, read-only development preview server for Cartograph HTML prototypes."""
from __future__ import annotations

import argparse
import ipaddress
import json
import logging
import sys
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ALLOWED = tuple(ipaddress.ip_network(s) for s in (
    "127.0.0.0/8", "192.168.0.0/24"
))

class PreviewHandler(SimpleHTTPRequestHandler):
    def _allowed(self):
        try:
            addr = ipaddress.ip_address(self.client_address[0])
            if any(addr in subnet for subnet in ALLOWED):
                return True
        except ValueError:
            pass
        self.send_error(403, "Local network access only")
        return False

    def _health(self):
        body = json.dumps({"ok": True, "service": "cartograph-prototypes",
                           "version": "v4", "readonly": True}).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_GET(self):
        if not self._allowed():
            return
        if self.path.split("?", 1)[0] == "/healthz":
            return self._health()
        return super().do_GET()

    def do_HEAD(self):
        if not self._allowed():
            return
        if self.path.split("?", 1)[0] == "/healthz":
            return self._health()
        return super().do_HEAD()

    def send_header(self, keyword, value):
        if keyword.lower() == "content-type" and str(value).lower().startswith("text/html"):
            super().send_header("Cache-Control", "no-store")
        return super().send_header(keyword, value)

    def list_directory(self, path):
        self.send_error(403, "Directory listing disabled")
        return None

    def log_message(self, fmt, *args):
        logging.info("%s %s", self.client_address[0], fmt % args)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--bind", default="0.0.0.0")
    parser.add_argument("--root", type=Path, required=True)
    a = parser.parse_args()
    if not (a.root / "index.html").is_file():
        raise SystemExit(f"No index.html in {a.root}")
    logging.basicConfig(level=logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s",
                        stream=sys.stderr)
    handler = partial(PreviewHandler, directory=str(a.root.resolve()))
    with ThreadingHTTPServer((a.bind, a.port), handler) as srv:
        logging.info("Cartograph prototype preview on http://%s:%d root=%s",
                     a.bind, a.port, a.root.resolve())
        srv.serve_forever(poll_interval=0.5)

if __name__ == "__main__":
    main()
