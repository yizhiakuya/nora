"""真实模型与工具验收：请求重试、运行中补充、完成边界与取消。

代理只透传真实上游，或注入一次 HTTP 故障；不生成模型回答。
"""
import argparse
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import subprocess
import threading
import time
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import ProxyHandler, Request, build_opener
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://127.0.0.1:18083")
    parser.add_argument("--provider", type=int, default=7)
    parser.add_argument("--model", default="deepseek-v4.1-flash")
    parser.add_argument("--case", choices=["all", "retry", "interaction", "configuration"], default="all")
    args = parser.parse_args()
    opener = build_opener(ProxyHandler({}))
    stamp = str(time.time_ns())
    record = {"checks": [], "turns": []}
    output = Path("logs/agent-harness-e2e-" + stamp + ".json")
    output.parent.mkdir(parents=True, exist_ok=True)
    files = []

    def api(path, data=None, method=None):
        body = None if data is None else json.dumps(data, ensure_ascii=False).encode()
        with opener.open(Request(args.base + path, body, {"Content-Type": "application/json"}, method=method), timeout=30) as response:
            value = json.load(response)
            return value.get("data", value) if isinstance(value, dict) else value

    def check(condition, label):
        record["checks"].append({"label": label, "passed": bool(condition)})
        output.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf8")
        if not condition:
            raise AssertionError(label)
        print("PASS", label, flush=True)

    def sql(command):
        return subprocess.run(["rtk", "proxy", "docker", "exec", "nora-postgres", "psql", "-U", "nora", "-d", "nora", "-At", "-c", command],
                              check=True, capture_output=True, text=True, encoding="utf8").stdout

    providers = api("/api/models/providers")
    source = next(p for p in providers if p["id"] == args.provider)
    upstream = urlsplit(source["endpoint"])
    origin = upstream.scheme + "://" + upstream.netloc
    provider = int(next(line for line in sql(
        "INSERT INTO schema_agent.model_provider(name,protocol,endpoint,api_key,enabled,models,status,model_settings) "
        f"SELECT 'harness-e2e-{stamp}',protocol,endpoint,api_key,true,models,status,model_settings "
        f"FROM schema_agent.model_provider WHERE id={args.provider} AND deleted_at IS NULL RETURNING id;").splitlines() if line.isdigit()))
    gate = threading.Event()
    state = {}
    lock = threading.Lock()

    class Relay(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            body = self.rfile.read(int(self.headers["Content-Length"]))
            payload = json.loads(body)
            messages = payload.get("messages", payload.get("input", []))
            has_result = any(m.get("role") == "tool" or m.get("type") == "function_call_output"
                             or isinstance(m.get("content"), list) and any(c.get("type") == "tool_result" for c in m["content"])
                             for m in messages if isinstance(m, dict))
            with lock:
                state["requests"] = state.get("requests", 0) + 1
                first = state["requests"] == 1
                digest = hashlib.sha256(body).hexdigest()
                if state.get("fault") and has_result and not state.get("injected"):
                    state["injected"] = True
                    state["failedRequest"] = digest
                    raw = b'{"error":{"type":"rate_limit_error","message":"harness transient fault"}}'
                    self.send_response(state["fault"])
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(raw)))
                    self.end_headers()
                    self.wfile.write(raw)
                    return
                if state.get("failedRequest") and "retrySameRequest" not in state:
                    state["retrySameRequest"] = digest == state["failedRequest"]
            headers = {k: v for k, v in self.headers.items() if k.lower() not in ("host", "content-length", "connection")}
            try:
                response = opener.open(Request(origin + self.path, body, headers), timeout=180)
            except HTTPError as error:
                response = error
            with response:
                self.send_response(response.status)
                self.send_header("Content-Type", response.headers.get("Content-Type", "application/json"))
                self.end_headers()
                if first and state.get("hold"):
                    gate.wait(30)
                while chunk := response.read1(16384):
                    self.wfile.write(chunk)
                    self.wfile.flush()

    relay = ThreadingHTTPServer(("127.0.0.1", 0), Relay)
    relay.daemon_threads = True
    threading.Thread(target=relay.serve_forever, daemon=True).start()
    proxy_endpoint = f"http://127.0.0.1:{relay.server_port}" + upstream.path.rstrip("/")

    def configure(protocol, fault=None, hold=False):
        with lock:
            state.clear()
            state.update({"fault": fault, "hold": hold})
        gate.clear()
        api(f"/api/models/providers/{provider}", {"endpoint": proxy_endpoint, "modelSettings": {
            args.model: {"protocol": protocol, "contextWindow": 128000}}}, "PUT")

    def run(label, prompt, hook=None, model=None):
        session = "harness-e2e-" + stamp + "-" + label
        item = {"session": session, "steps": {}, "errors": []}
        record["turns"].append(item)
        body = json.dumps({"content": prompt, "providerId": provider, "model": model or args.model,
                           "reasoningLevel": "none", "permissionMode": "full"}, ensure_ascii=False).encode()
        sql(f"INSERT INTO schema_agent.chat_session(id,title,title_generated) VALUES ('{session}','Harness E2E',true);")
        print("TURN", label, flush=True)
        with opener.open(Request(args.base + f"/api/chat/sessions/{session}/messages", body, {"Content-Type": "application/json"}), timeout=180) as response:
            event = ""
            for raw in response:
                line = raw.decode().strip()
                if line.startswith("event:"):
                    event = line[6:].strip()
                elif line.startswith("data:"):
                    data = json.loads(line[5:])
                    if event == "step":
                        # 不把真实模型的完整思考内容写入验收记录。
                        item["steps"][data["id"]] = {k: v for k, v in data.items() if k != "detail" or data.get("type") != "think"}
                    elif event == "done":
                        item["done"] = data
                    elif event == "error":
                        item["errors"].append(data)
                    if hook:
                        hook(session, event, data)
        item["history"] = api(f"/api/chat/sessions/{session}/messages")
        for message in item["history"]:
            for step in message.get("steps") or []:
                if step.get("type") == "think":
                    step.pop("detail", None)
        return item

    def steer(session, content, message_id=None):
        turn_id = api(f"/api/chat/sessions/{session}/turn/live")["turnId"]
        payload = {"turnId": turn_id, "messageId": message_id or str(uuid.uuid4()), "content": content}
        api(f"/api/chat/sessions/{session}/steer", payload)
        return payload

    def check_configuration():
        item = run("configuration", "只回答你好", model="unconfigured-e2e-" + stamp)
        check(item["done"]["status"] == "failed" and item["done"]["stopReason"] == "LLM_NOT_CONFIGURED",
              "未配置模型明确失败，不伪装正常回答")
        check(item["errors"][0]["message"] == "未配置可用的模型" and item["history"][-1]["content"] == "",
              "配置错误原文与历史一致，没有生成回答")

    try:
        if args.case == "configuration":
            check_configuration()
            print("RECORD", output.resolve(), flush=True)
            return
        for protocol, status in ([] if args.case == "interaction" else [("openai", 429), ("anthropic", 503), ("responses", 429)]):
            configure(protocol, status)
            path = Path("agent-workspace/browser/harness-" + stamp + "-" + protocol + ".txt")
            files.append(path)
            relative = path.relative_to("agent-workspace").as_posix()
            command = f'''rtk proxy python -c "from pathlib import Path; p=Path('{relative}'); p.parent.mkdir(parents=True,exist_ok=True); f=p.open('a'); f.write('once\\n'); f.close()"'''
            item = run("retry-" + protocol, "只调用一次 powershell，在默认工作区原样执行：\n" + command + "\n根据成功的工具结果只回答 done，不再调用任何工具。")
            tools = [s for s in item["steps"].values() if s.get("toolName") == "powershell"]
            check(state.get("injected") and state.get("retrySameRequest"), protocol + " 工具后 HTTP 故障重试同一请求")
            check(len(tools) == 1 and tools[0]["status"] == "completed" and path.read_text().splitlines() == ["once"], protocol + " 写入工具只执行一次")
            check(len([s for key, s in item["steps"].items() if key.startswith("s-retry-")]) == 1, protocol + " 恢复后不继续重试")
            check(item["done"]["status"] == "completed" and item["done"]["stopReason"] == "completed" and not item["errors"], protocol + " 真实请求恢复并正常结束")

        if args.case == "retry":
            print("RECORD", output.resolve(), flush=True)
            return
        configure("anthropic")
        sent = []
        marker = "UPDATED-" + stamp

        def while_tool(session, event, data):
            if not sent and event == "step" and data.get("toolName") == "powershell" and data.get("status") == "running":
                payload = steer(session, "补充要求：无需其他工具，最终只回答 " + marker)
                sent.append(payload)
                api(f"/api/chat/sessions/{session}/steer", payload)
                try:
                    api(f"/api/chat/sessions/{session}/messages", {"content": "duplicate owner"})
                    raise AssertionError("并发主轮次未拒绝")
                except HTTPError as error:
                    check(error.code == 409, "补充要求沿用单个执行循环")

        item = run("steer", "调用一次 powershell 原样执行 Start-Sleep -Seconds 4; Write-Output 'ready'，随后只回复 original。", while_tool)
        check(len([m for m in item["history"] if m.get("sender") == "steering"]) == 1, "补充指令重复提交只落库一次")
        check(item["history"][-1]["content"].strip() == marker and item["done"]["status"] == "completed", "工具边界消费补充要求并改变最终结果")
        api(f"/api/chat/sessions/{item['session']}/steer", sent[0])
        check(True, "轮次完成后重试已受理输入保持幂等")
        try:
            api(f"/api/chat/sessions/{item['session']}/steer", {**sent[0], "messageId": str(uuid.uuid4())})
            raise AssertionError("过期轮次仍受理新输入")
        except HTTPError as error:
            check(error.code == 409, "完成的轮次拒绝新输入")
        step_count = sql(f"SELECT count(*) FROM schema_agent.agent_step WHERE run_id='{sent[0]['turnId']}' AND step_id IS NOT NULL;")
        check(any(line.isdigit() and int(line) > 0 for line in step_count.splitlines()), "工具步骤持久化保留运行和步骤 ID")

        configure("anthropic", hold=True)
        result = {}
        session = "harness-e2e-" + stamp + "-finish"
        thread = threading.Thread(target=lambda: result.update(item=run("finish", "不使用工具，1+1是多少？只回复数字。")))
        thread.start()
        deadline = time.monotonic() + 20
        while not state.get("requests") and time.monotonic() < deadline:
            time.sleep(.1)
        check(bool(state.get("requests")), "最终回答请求已发出")
        steer(session, "补充要求：最后只回答 " + marker)
        gate.set()
        thread.join(180)
        item = result["item"]
        check(item["history"][-1]["content"].strip() == marker, "最终回答前补充要求不会被丢弃")
        check(item["steps"]["s-message-1"]["type"] == "commentary", "被补充要求替代的回答保留为过程信息")

        configure("anthropic")
        queued = []

        def cancel_with_input(session, event, data):
            if not queued and event == "step" and data.get("toolName") == "powershell" and data.get("status") == "running":
                queued.append(steer(session, "补充要求：最后回复 cancelled-input"))
                api(f"/api/chat/sessions/{session}/cancel", {}, "POST")

        item = run("cancel", "调用一次 powershell 执行 Start-Sleep -Seconds 8; Write-Output 'ready'，然后回答。", cancel_with_input)
        check(item["done"]["status"] == "cancelled" and item["done"]["stopReason"] == "cancelled", "取消原因与真实运行终态一致")
        check(any(s.get("title") == "补充要求未处理" and s.get("status") == "partial" for s in item["steps"].values()), "取消时未消费的补充明确保留")
        if args.case == "all":
            check_configuration()
        print("RECORD", output.resolve(), flush=True)
    finally:
        gate.set()
        for item in record["turns"]:
            if "done" not in item:
                api(f"/api/chat/sessions/{item['session']}/cancel", {}, "POST")
        api(f"/api/models/providers/{provider}", method="DELETE")
        relay.shutdown()
        relay.server_close()
        for path in files:
            path.unlink(missing_ok=True)
        output.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf8")


if __name__ == "__main__":
    main()
