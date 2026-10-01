#!/usr/bin/env python3
"""PostToolUse(Edit|Write): nora-web/src 下 .ts/.tsx 文件改动后跑 eslint --fix。
lint 失败不阻断,仅回显;同时写入 stop_tsc 哨兵,让 Stop 钩子跑 tsc --noEmit 把关。
"""
import json, os, subprocess, sys

for s in (sys.stdout, sys.stderr):
    try:
        s.reconfigure(encoding="utf-8")
    except Exception:
        pass

# 仓库根从脚本位置推导(scripts 位于 <root>/.claude/hooks/),避免硬编码机器路径
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
NORA_WEB = os.path.join(REPO_ROOT, "nora-web")
SENTINEL = os.path.join(REPO_ROOT, ".claude", "hooks", ".tsc-pending")

def main():
    try:
        data = json.load(sys.stdin)
    except Exception:
        sys.exit(0)
    fp = (data.get("tool_input") or {}).get("file_path", "") or ""
    fp = os.path.abspath(fp.replace("/", os.sep))
    if not fp.endswith((".ts", ".tsx")) or (os.sep + "src" + os.sep) not in fp or "nora-web" not in fp:
        sys.exit(0)

    rel = os.path.relpath(fp, NORA_WEB)
    try:
        r = subprocess.run(f'npx eslint --fix "{rel}"', shell=True, cwd=NORA_WEB,
                           capture_output=True, text=True, timeout=60)
        if r.returncode != 0 and r.stdout.strip():
            print(f"eslint({rel}): {r.stdout.strip()[:1500]}", file=sys.stderr)
    except Exception:
        pass

    try:
        open(SENTINEL, "w").close()
    except Exception:
        pass
    sys.exit(0)

if __name__ == "__main__":
    main()
