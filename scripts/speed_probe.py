# -*- coding: utf-8 -*-
"""Multi-connection Baidu direct-link speed probe (PC)."""
import re
import sys
import threading
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

UA = (
    "netdisk;P2SP;3.0.20.233;netdisk;8.7.9.102;"
    "PC;PC-Windows;10.0.19045;WindowsBaiduYunGuanJia"
)


def main():
    url = sys.argv[1] if len(sys.argv) > 1 else ""
    if not url:
        print("usage: speed_probe.py <url>")
        return 2
    m = re.search(r"[?&]size=(\d+)", url)
    if not m:
        print("no size= in url")
        return 2
    size = int(m.group(1))
    chunk = int(sys.argv[2]) if len(sys.argv) > 2 else 1024 * 1024
    workers = int(sys.argv[3]) if len(sys.argv) > 3 else 8
    lock = threading.Lock()
    done = [0]

    def fetch(i, s, e):
        req = urllib.request.Request(
            url,
            headers={
                "User-Agent": UA,
                "Range": f"bytes={s}-{e}",
                "Connection": "Keep-Alive",
                "Accept-Encoding": "identity",
            },
        )
        with urllib.request.urlopen(req, timeout=120) as resp:
            data = resp.read()
        with lock:
            done[0] += len(data)
        return len(data)

    ranges = []
    start = 0
    idx = 0
    while start < size:
        end = min(start + chunk - 1, size - 1)
        ranges.append((idx, start, end))
        start = end + 1
        idx += 1

    t0 = time.time()
    with ThreadPoolExecutor(max_workers=workers) as ex:
        futs = [ex.submit(fetch, *r) for r in ranges]
        for f in as_completed(futs):
            f.result()
    dt = time.time() - t0
    mbps = size / dt / 1024 / 1024
    print(
        f"size={size} chunks={len(ranges)} workers={workers} "
        f"chunk={chunk} time={dt:.2f}s speed={mbps:.2f} MB/s done={done[0]}"
    )
    return 0 if mbps >= 10 else 1


if __name__ == "__main__":
    raise SystemExit(main())
