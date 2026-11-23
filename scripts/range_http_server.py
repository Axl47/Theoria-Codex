#!/usr/bin/env python3
"""Serves a directory over HTTP on 127.0.0.1 with byte-range support, and appends one line per response to a log:
`<path> <status> <bytes sent>`. The vision acceptance uses it so video frame sampling reads in ranges, as from a CDN,
and its downloaded bytes can be counted. Standard library only.

Usage: scripts/range_http_server.py <directory> <port> <log-file>
"""
import http.server
import os
import re
import sys
from functools import partial

RANGE = re.compile(r"bytes=(\d*)-(\d*)$")


class RangeHandler(http.server.SimpleHTTPRequestHandler):
    log_path = ""

    def do_GET(self) -> None:
        path = self.translate_path(self.path)
        if not os.path.isfile(path):
            self.send_error(404)
            return
        size = os.path.getsize(path)
        start, end, status = 0, size - 1, 200
        match = RANGE.match(self.headers.get("Range", ""))
        if match and (match.group(1) or match.group(2)):
            if match.group(1):
                start = int(match.group(1))
                end = min(int(match.group(2)), size - 1) if match.group(2) else size - 1
            else:
                start = max(size - int(match.group(2)), 0)
            if start > end:
                self.send_error(416)
                return
            status = 206
        self.send_response(status)
        self.send_header("Content-Type", self.guess_type(path))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        sent = 0
        with open(path, "rb") as file:
            file.seek(start)
            remaining = end - start + 1
            try:
                while remaining > 0:
                    chunk = file.read(min(65536, remaining))
                    self.wfile.write(chunk)
                    sent += len(chunk)
                    remaining -= len(chunk)
            except (BrokenPipeError, ConnectionResetError):
                pass
        with open(self.log_path, "a") as log:
            log.write(f"{self.path} {status} {sent}\n")

    def log_message(self, format: str, *args: object) -> None:
        pass


def main() -> None:
    directory, port, log_path = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    RangeHandler.log_path = log_path
    server = http.server.ThreadingHTTPServer(("127.0.0.1", port), partial(RangeHandler, directory=directory))
    server.serve_forever()


if __name__ == "__main__":
    main()
