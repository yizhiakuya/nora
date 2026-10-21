"""Measure real Chromium owners in an isolated, memory-limited Linux Agent."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import subprocess
import threading
import time

import requests


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", required=True)
    parser.add_argument("--base", default="http://127.0.0.1:18093")
    parser.add_argument("--owners", type=int, default=2)
    args = parser.parse_args()
    stamp = str(time.time_ns())
    report = {"samples": [], "checks": []}
    path = Path("logs") / ("browser-resources-" + stamp + ".json")
    http = requests.Session()
    http.trust_env = False
    sessions = []
    release = threading.Event()
    download_started = threading.Event()

    def docker(*command):
        return subprocess.run(["rtk", "proxy", "docker", *command], capture_output=True, text=True, check=True).stdout.strip()

    def save():
        path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf8")

    def check(value, label):
        report["checks"].append({"label": label, "passed": bool(value)})
        save()
        print("PASS" if value else "FAIL", label, flush=True)
        if not value:
            raise AssertionError(label)

    def sample(label):
        values = docker("exec", args.container, "cat", "/sys/fs/cgroup/memory.current", "/sys/fs/cgroup/memory.peak", "/sys/fs/cgroup/memory.max", "/sys/fs/cgroup/memory.swap.current", "/sys/fs/cgroup/memory.swap.max").splitlines()
        events = dict(line.split() for line in docker("exec", args.container, "cat", "/sys/fs/cgroup/memory.events").splitlines())
        memory = dict(line.split() for line in docker("exec", args.container, "cat", "/sys/fs/cgroup/memory.stat").splitlines())
        sample = {"stage": label, **dict(zip(("current", "peak", "limit", "swapCurrent", "swapLimit"), map(int, values))),
                  "events": {key: int(value) for key, value in events.items()}}
        sample.update(inactiveFile=int(memory["inactive_file"]), workingSet=max(0, sample["current"] - int(memory["inactive_file"])))
        report["samples"].append(sample)
        save()
        print(label, "MiB", round(sample["current"] / 1048576, 1), "working", round(sample["workingSet"] / 1048576, 1), "peak", round(sample["peak"] / 1048576, 1), flush=True)

    def api(sid, body=None):
        response = http.request("GET" if body is None else "POST", args.base + "/api/workspace/browser/" + sid, json=body, timeout=50)
        response.raise_for_status()
        return response.json()["data"]

    def browser_pids():
        return {int(pid) for line in docker("exec", args.container, "ps", "-eo", "pid=,comm=").splitlines()
                for pid, command in [line.split()] if command == "node" or command.startswith("chrome")}

    class Page(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            if self.path == "/download":
                self.send_response(200)
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Content-Disposition", 'attachment; filename="unfinished.bin"')
                self.send_header("X-Content-Type-Options", "nosniff")
                self.send_header("Content-Length", "1000000")
                self.end_headers()
                self.wfile.write(b"x" * 2048)
                self.wfile.flush()
                download_started.set()
                release.wait(65)
                return
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(("<title>Browser capacity audit</title><a href='/download' download>Start slow download</a><table>" + "<tr><td>real page row</td><td>capacity-canary</td></tr>" * 500 + "</table>").encode())

    server = ThreadingHTTPServer(("0.0.0.0", 0), Page)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        sample("idle")
        for index in range(args.owners):
            sid = "linux-browser-" + stamp + "-" + str(index)
            sessions.append(sid)
            started = time.monotonic()
            api(sid, {"action": "takeover"})
            api(sid, {"action": "open", "url": f"http://host.docker.internal:{server.server_port}"})
            valid_frames = True
            elapsed = []
            for _ in range(10):
                frame_started = time.monotonic()
                frame = http.get(args.base + f"/api/workspace/browser/{sid}/frame", timeout=10)
                frame.raise_for_status()
                valid_frames &= frame.headers.get("Content-Type", "").startswith("image/")
                elapsed.append(time.monotonic() - frame_started)
            check(valid_frames, f"owner {index + 1} 连续返回十次真实画面")
            sample("owners-" + str(index + 1))
            report["samples"][-1].update(elapsed=round(time.monotonic() - started, 3), slowestFrame=round(max(elapsed), 3))
            check(max(elapsed) < 2, "取帧延迟保持在两秒内")
            check(all(api(item)["active"] for item in sessions), "已有浏览器会话全部可用")
        blocked = http.post(args.base + "/api/workspace/browser/linux-browser-extra-" + stamp, json={"action": "takeover"}, timeout=10)
        check(blocked.status_code == 400, "超过活跃 owner 上限的请求立即拒绝")
        state = json.loads(docker("inspect", args.container, "--format", "{{json .State}}"))
        check(state["Running"] and not state["OOMKilled"] and report["samples"][-1]["events"]["oom_kill"] == 0,
              "内存限制下实际页面与连续取帧没有任何进程被 OOM 杀死")
        check(report["samples"][-1]["swapCurrent"] == 0 and report["samples"][-1]["workingSet"] < report["samples"][-1]["limit"] * 0.9,
              "无需 swap，扣除可回收文件缓存后的工作集留有运行余量")
        a, b = sessions[:2]
        api(a, {"action": "close"})
        baseline = browser_pids()
        api(a, {"action": "takeover"})
        api(a, {"action": "open", "url": f"http://host.docker.internal:{server.server_port}"})
        owned_a = browser_pids() - baseline
        api(a, {"action": "click", "role": "link", "name": "Start slow download"})
        check(download_started.wait(5), "Linux 下载进入真实长期等待")
        started = time.monotonic()
        check(api(a)["busy"] and api(b)["active"] and http.get(args.base + f"/api/workspace/browser/{b}/frame", timeout=5).status_code == 200
              and time.monotonic() - started < 2, "Linux 卡住会话与另一会话的状态及画面均及时响应")
        deadline = time.monotonic() + 50
        while api(a)["active"] and time.monotonic() < deadline:
            time.sleep(0.2)
        check(not api(a)["active"] and bool(owned_a) and owned_a.isdisjoint(browser_pids()) and api(b)["active"],
              "Linux 截止时间清理所属 Node 与 Chromium 进程，另一会话仍可用")
        api(a, {"action": "takeover"})
        check(api(a)["active"] and api(b)["active"], "Linux 超时会话重新打开可用")
    finally:
        release.set()
        for sid in sessions:
            try:
                api(sid, {"action": "close"})
            except requests.RequestException as error:
                report.setdefault("cleanupErrors", []).append(type(error).__name__)
        server.shutdown()
        save()
        print("REPORT", path, flush=True)


if __name__ == "__main__":
    main()
