#!/usr/bin/env python3
"""Maven Central local relay: serves http://127.0.0.1:18099/maven2/<path>
from ~/workspace/m2cache, fetching misses from Maven Central via curl
(curl handles this sandbox's egress proxy; Java wagon cannot).
Cache is shared with the 2026-11-15 setup (~/.m2/settings.xml mirror localcache).
"""
import os
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

CACHE = Path.home() / "workspace" / "m2cache"
CENTRAL = "https://repo.maven.apache.org/maven2/"
PORT = 18099


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stderr.write("[relay] %s\n" % (fmt % args))

    def do_GET(self):
        prefix = "/maven2/"
        if not self.path.startswith(prefix):
            self.send_error(404)
            return
        rel = self.path[len(prefix):].split("?")[0]
        if not rel or ".." in rel.split("/"):
            self.send_error(404)
            return
        target = CACHE / rel
        if not target.is_file():
            target.parent.mkdir(parents=True, exist_ok=True)
            tmp = target.with_suffix(target.suffix + ".part")
            r = subprocess.run(
                ["curl", "-sfL", "--max-time", "120", "-o", str(tmp), CENTRAL + rel]
            )
            if r.returncode != 0 or not tmp.exists() or tmp.stat().st_size == 0:
                tmp.unlink(missing_ok=True)
                self.send_error(404)
                return
            tmp.rename(target)
        data = target.read_bytes()
        self.send_response(200)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Content-Type", "application/octet-stream")
        self.end_headers()
        self.wfile.write(data)

    do_HEAD = do_GET


if __name__ == "__main__":
    CACHE.mkdir(parents=True, exist_ok=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
