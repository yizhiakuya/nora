#!/usr/bin/env python3
"""PreToolUse(Bash): mvn package 前检查"本次会被重建的" nora 服务是否正在运行。

运行中的 java 进程锁住其模块 jar,repackage 会 Unable to rename。
目标模块从 -pl/--projects 解析:只有命中列表里的服务在跑才拦截,
不在重建范围的服务(它们的 jar 锁不影响本次构建)放行;
未指定 -pl / 含排除项 / 解析不到时,按全量保守处理。
"""
import json, re, subprocess, sys

for s in (sys.stdout, sys.stderr):
    try:
        s.reconfigure(encoding="utf-8")
    except Exception:
        pass

PORT_MODULE = {
    8080: "gateway-service",
    8081: "file-service",
    8082: "rag-service",
    8083: "agent-service",
    8084: "datasource-service",
    8085: "env-service",
    8086: "automation-service",
}


def requested_modules(cmd: str):
    """从 -pl/--projects 提取目标模块名集合;无此参数返回 None(视为全量)。"""
    m = re.search(r"(?:^|\s)(?:-pl|--projects)(?:=|\s+)(?:\"([^\"]+)\"|'([^']+)'|(\S+))", cmd)
    if not m:
        return None
    raw = next(g for g in m.groups() if g)
    names = set()
    for item in re.split(r"[,\s]+", raw):
        item = item.strip().replace("\\", "/").rstrip("/")
        if not item:
            continue
        if item.startswith("!"):
            return None  # 有排除项,保守按全量
        seg = item.rsplit("/", 1)[-1].rsplit(":", 1)[-1]  # services/x 或 g:a 形式
        if seg:
            names.add(seg)
    return names or None


def main():
    try:
        data = json.load(sys.stdin)
    except Exception:
        sys.exit(0)
    cmd = (data.get("tool_input") or {}).get("command", "")
    if not re.search(r"\bmvn\b.*\bpackage\b|\brepackage\b", cmd):
        sys.exit(0)

    targets = requested_modules(cmd)  # None = 全量

    try:
        out = subprocess.run(["netstat", "-ano"], capture_output=True, text=True, timeout=10).stdout
    except Exception:
        sys.exit(0)

    blocked = {}
    for line in out.splitlines():
        if "LISTENING" not in line:
            continue
        parts = line.split()
        if len(parts) < 5:
            continue
        local, pid = parts[1], parts[-1]
        port = local.rsplit(":", 1)[-1]
        if not (port.isdigit() and int(port) in PORT_MODULE):
            continue
        module = PORT_MODULE[int(port)]
        if targets is not None and module not in targets:
            continue  # 该模块不在本次重建范围,jar 锁不影响构建
        # 确认是 java 进程(nora 服务)才硬拦
        try:
            name = subprocess.run(
                ["powershell", "-NoProfile", "-Command",
                 f"(Get-Process -Id {pid}).ProcessName"],
                capture_output=True, text=True, timeout=10).stdout.strip()
        except Exception:
            name = "?"
        if name.lower() in ("java", "javaw"):
            blocked[pid] = int(port)  # 去重:IPv4/IPv6 双栈会重复上报同一 PID

    if blocked:
        stop = " ".join(f"powershell Stop-Process -Id {pid} -Force;" for pid in blocked)
        detail = ", ".join(f"port {p} (PID {pid})" for pid, p in blocked.items())
        scope = "本次重建的模块" if targets is not None else "全部服务"
        print(f"nora 服务端口被 java 进程占用({scope}在重建范围内),package 会因 jar 被锁失败。"
              f"占用: {detail}。先执行: {stop} 然后重试。", file=sys.stderr)
        sys.exit(2)
    sys.exit(0)


if __name__ == "__main__":
    main()
