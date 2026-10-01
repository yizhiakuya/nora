#!/usr/bin/env python3
"""Stop: 本会话改过 nora-web/src 时跑 tsc --noEmit。有类型错误 → 阻止停止并回显。"""
import os, subprocess, sys

try:
    sys.stderr.reconfigure(encoding="utf-8")
except Exception:
    pass

# 仓库根从脚本位置推导(scripts 位于 <root>/.claude/hooks/),避免硬编码机器路径
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SENTINEL = os.path.join(REPO_ROOT, ".claude", "hooks", ".tsc-pending")
NORA_WEB = os.path.join(REPO_ROOT, "nora-web")

def main():
    if not os.path.exists(SENTINEL):
        sys.exit(0)
    try:
        os.remove(SENTINEL)
    except Exception:
        pass
    try:
        r = subprocess.run("pnpm exec tsc --noEmit", shell=True, cwd=NORA_WEB,
                           capture_output=True, text=True, timeout=150)
    except Exception:
        sys.exit(0)
    if r.returncode != 0:
        out = (r.stdout + r.stderr).strip()
        print(f"nora-web 存在 TypeScript 错误,请修复后再结束:\n\n{out[:3000]}", file=sys.stderr)
        # 重建哨兵:修完后再 Stop 会重新校验
        try:
            open(SENTINEL, "w").close()
        except Exception:
            pass
        sys.exit(2)
    sys.exit(0)

if __name__ == "__main__":
    main()
