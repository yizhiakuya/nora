#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""manage_mcp update E2E:让 agent 用 update 动作改测试服务器的 url,验证工具面闭环。"""
import json
import time
import urllib.request

BASE = "http://localhost:8080"
TOKEN = "megumin"
SESSION = "sess-e2e-mcpupdate-" + str(int(time.time()))


def req(method, path, body=None, timeout=90):
    url = BASE + path
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    h = {"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json"}
    r = urllib.request.Request(url, data=data, headers=h, method=method)
    return urllib.request.urlopen(r, timeout=timeout)


content = ("把 MCP 服务器 e2e-update-test 的地址改成 http://localhost:19998/mcp2,"
           "用 manage_mcp 的 update 动作完成,不要 remove 重注册")
print(f"=== 发送到 {SESSION} ===")
resp = req("POST", f"/api/chat/sessions/{SESSION}/messages",
           {"content": content, "permissionMode": "full"})

buf = b""
start = time.time()
done = False
while time.time() - start < 180:
    chunk = resp.read1(65536)
    if not chunk:
        break
    buf += chunk
    text = buf.decode("utf-8", errors="replace")
    while "\n\n" in text:
        block, text = text.split("\n\n", 1)
        buf = text.encode("utf-8")
        ev, data = None, ""
        for l in block.split("\n"):
            if l.startswith("event:"):
                ev = l[6:].strip()
            elif l.startswith("data:"):
                data += l[5:].strip()
        if ev == "step":
            try:
                p = json.loads(data)
                if p.get("toolName"):
                    print(f"[tool] {p.get('status')} {p.get('toolName')} :: {str(p.get('detail'))[:120]}")
            except Exception:
                pass
        elif ev == "approval_required":
            p = json.loads(data)
            print("!! approval:", p.get("summary"))
            req("POST", f"/api/chat/approvals/{p.get('approvalToken')}?sessionId={SESSION}", {"approved": True})
        elif ev == "question_required":
            p = json.loads(data)
            print("!! question:", p.get("question"))
        elif ev == "done":
            print("=== done ===", data[:200])
            done = True
            break
        elif ev == "error":
            print("!! error:", data[:300])
    if done:
        break
resp.close()

# 验证 DB 里的 url 已更新
import subprocess
out = subprocess.run(["docker", "exec", "nora-postgres", "psql", "-U", "nora", "-d", "nora", "-t", "-A",
                      "-c", "select url, status from schema_agent.mcp_server where name='e2e-update-test';"],
                     capture_output=True, text=True, encoding="utf-8").stdout
print("=== DB 现状 ===", out.strip())

# 拉最终回答
time.sleep(1)
r3 = req("GET", f"/api/chat/sessions/{SESSION}/messages")
msgs = json.loads(r3.read().decode("utf-8"))
for m in msgs:
    if m.get("role") == "assistant":
        print("=== 最终回答(前 300 字) ===")
        print((m.get("content") or "")[:300])
