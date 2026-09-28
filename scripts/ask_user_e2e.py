#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""ask_user E2E:发一条指代不明的消息 → 期待 question_required → 作答 → 看 done。

用法: python ask_user_e2e.py
"""
import json
import re
import sys
import threading
import time
import urllib.request

BASE = "http://localhost:8080"
TOKEN = "megumin"
SESSION = "sess-e2e-askuser-" + str(int(time.time()))


def req(method, path, body=None, headers=None, stream=False, timeout=60):
    url = BASE + path
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    h = {"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json"}
    if headers:
        h.update(headers)
    r = urllib.request.Request(url, data=data, headers=h, method=method)
    return urllib.request.urlopen(r, timeout=timeout)


def main():
    content = "帮我处理一下那个东西"  # 指代不明:无任何上下文可确定所指
    print(f"=== 发送消息到 {SESSION} ===")
    resp = req("POST", f"/api/chat/sessions/{SESSION}/messages",
               {"content": content, "permissionMode": "full"})
    question_token = None
    question_payload = None
    answer_sent = False
    done = False
    events = []

    buf = b""
    start = time.time()
    while time.time() - start < 300:
        chunk = resp.read1(65536) if hasattr(resp, "read1") else resp.read(65536)
        if not chunk:
            break
        buf += chunk
        text = buf.decode("utf-8", errors="replace")
        # SSE 事件按空行切
        while "\n\n" in text:
            block, text = text.split("\n\n", 1)
            buf = text.encode("utf-8")
            lines = [l for l in block.split("\n") if l.strip()]
            ev, data = None, ""
            for l in lines:
                if l.startswith("event:"):
                    ev = l[6:].strip()
                elif l.startswith("data:"):
                    data += l[5:].strip()
            if not ev:
                continue
            events.append(ev)
            if ev == "question_required":
                payload = json.loads(data)
                question_token = payload.get("questionToken")
                question_payload = payload
                print("=== 收到 question_required ===")
                print("问题:", payload.get("question"))
                print("选项:", payload.get("options"))
                # 应答每一个提问(脚本自动选第一个选项)
                if question_token:
                    time.sleep(1)
                    opts = payload.get("options") or []
                    ans = opts[0] if opts else "自由回答:测试"
                    try:
                        r2 = req("POST", f"/api/chat/answers/{question_token}?sessionId={SESSION}",
                                 {"answer": ans})
                        print("=== 已提交回答 ===", r2.status, ans)
                    except Exception as e:
                        print("!! 提交回答失败:", e)
            elif ev == "step":
                try:
                    p = json.loads(data)
                    print(f"[step] {p.get('id')} {p.get('status')} {p.get('title')} :: {str(p.get('detail'))[:80]}")
                except Exception:
                    pass
            elif ev == "approval_required":
                payload = json.loads(data)
                print("!! 收到 approval_required(应无审批):", payload.get("summary"))
                # 批准以免卡住
                req("POST", f"/api/chat/approvals/{payload.get('approvalToken')}?sessionId={SESSION}",
                    {"approved": True})
            elif ev == "done":
                print("=== done ===", data[:300])
                done = True
                break
            elif ev == "error":
                print("!! error:", data[:300])
        if done:
            break
        # 若 30s 内没收到 question_required,打印诊断
        if time.time() - start > 60 and not question_token:
            print("!! 60s 未见 question_required(模型可能没调 ask_user)")
            break
    resp.close()
    print("=== 事件序列 ===", events)

    # 拉取最终消息验证答案回填
    time.sleep(2)
    try:
        r3 = req("GET", f"/api/chat/sessions/{SESSION}/messages")
        msgs = json.loads(r3.read().decode("utf-8"))
        for m in msgs:
            if m.get("role") == "assistant":
                print("=== 最终回答(前 400 字) ===")
                print((m.get("content") or "")[:400])
                steps = m.get("steps") or []
                for s in steps:
                    if s.get("toolName") == "ask_user":
                        print("=== ask_user 步骤 ===", s.get("status"), s.get("result"))
    except Exception as e:
        print("拉历史失败:", e)


if __name__ == "__main__":
    main()
