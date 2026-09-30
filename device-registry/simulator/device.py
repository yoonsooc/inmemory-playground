#!/usr/bin/env python3
"""디바이스 시뮬레이터.

각 디바이스는 SSE 스트림을 열어 붙잡고 있기만 한다. 하트비트는 서버가 보낸다 (ADR-0003).
연결이 끊기면 백오프 후 다시 연결한다. 외부 의존성 없음 (python3 표준 라이브러리만 사용).

  python3 simulator/device.py --url http://localhost:8080 --count 3
"""
import argparse
import json
import sys
import threading
import time
import urllib.request
from datetime import datetime


def log(device_id, msg):
    ts = datetime.now().strftime("%H:%M:%S.%f")[:-3]
    print(f"{ts} [{device_id}] {msg}", flush=True)


class Device:
    def __init__(self, device_id, base_url, read_timeout):
        self.id = device_id
        self.base_url = base_url.rstrip("/")
        self.read_timeout = read_timeout  # 서버 ping 주기보다 넉넉히 길어야 한다
        self.server_id = None

    def run(self):
        backoff = 1
        while True:
            try:
                self.stream()
                backoff = 1
            except Exception as e:  # 연결 거절, 서버 kill, 읽기 타임아웃 등 전부 여기로 온다
                log(self.id, f"SSE closed: {type(e).__name__}: {e}")
            self.server_id = None
            log(self.id, f"reconnecting in {backoff}s")
            time.sleep(backoff)
            backoff = min(backoff * 2, 10)

    def stream(self):
        req = urllib.request.Request(f"{self.base_url}/devices/{self.id}/events",
                                     headers={"Accept": "text/event-stream"})
        resp = urllib.request.urlopen(req, timeout=self.read_timeout)
        log(self.id, f"SSE open (HTTP {resp.status})")
        event, data = None, []
        try:
            while True:
                line = resp.readline()
                if not line:
                    raise ConnectionError("server closed the stream")
                line = line.decode().rstrip("\r\n")
                if line.startswith("event:"):
                    event = line[6:].strip()
                elif line.startswith("data:"):
                    data.append(line[5:].strip())
                elif line == "":
                    if event or data:
                        self.on_event(event or "message", "\n".join(data))
                    event, data = None, []
        finally:
            resp.close()

    def on_event(self, event, data):
        if event == "connected":
            self.server_id = json.loads(data).get("serverId")
            log(self.id, f"connected to server={self.server_id}")
        elif event == "ping":
            log(self.id, f"ping from {data}")
        else:
            log(self.id, f"event={event} data={data}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--url", default="http://localhost:8080", help="nginx(LB) 주소")
    p.add_argument("--count", type=int, default=1, help="디바이스 수")
    p.add_argument("--prefix", default="dev", help="디바이스 id 접두사 (dev-1, dev-2, ...)")
    p.add_argument("--read-timeout", type=float, default=35,
                   help="이 시간 동안 서버에서 아무것도 안 오면 끊긴 것으로 보고 재연결 (서버 ping 10s 기준)")
    args = p.parse_args()

    devices = [Device(f"{args.prefix}-{i}", args.url, args.read_timeout) for i in range(1, args.count + 1)]
    for d in devices:
        threading.Thread(target=d.run, name=d.id, daemon=True).start()
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("bye", file=sys.stderr)


if __name__ == "__main__":
    main()
