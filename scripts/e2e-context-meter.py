"""真实上游计量、缓存、压缩、取消与历史恢复；中继可注入一次长度错误，成功响应均来自真实模型。"""
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import subprocess
import threading
import time
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import ProxyHandler, Request, build_opener
import uuid


def main():
    stamp = str(time.time_ns())
    base = "http://127.0.0.1:18083"
    model = "deepseek-v4.1-flash"
    op = build_opener(ProxyHandler({}))
    report = {"checks": [], "turns": []}
    path = Path("logs/context-meter-e2e-" + stamp + ".json")
    trace = Path("logs/context-meter-trace-" + stamp + ".json")
    all_events = []
    requests = []
    sessions = []

    def check(value, label):
        report["checks"].append({"label": label, "passed": bool(value)})
        path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf8")
        print("PASS" if value else "FAIL", label, flush=True)

    def sql(query):
        return subprocess.run(["rtk", "proxy", "docker", "exec", "nora-postgres", "psql", "-U", "nora", "-d", "nora", "-At", "-c", query],
                              capture_output=True, text=True, encoding="utf8", check=True).stdout

    def api(endpoint, data=None, method=None):
        with op.open(Request(base + endpoint, None if data is None else json.dumps(data, ensure_ascii=False).encode(),
                             {"Content-Type": "application/json"}, method=method), timeout=30) as r:
            value = json.load(r)
            return value.get("data", value) if isinstance(value, dict) else value

    providers = api("/api/models/providers")
    source = next(p for p in providers if p["enabled"] and model in p["models"])
    url = urlsplit(source["endpoint"])
    origin = url.scheme + "://" + url.netloc
    provider = int(next(line for line in sql("INSERT INTO schema_agent.model_provider(name,protocol,endpoint,api_key,enabled,models,status,model_settings) "
        f"SELECT 'meter-e2e-{stamp}',protocol,endpoint,api_key,true,models,status,model_settings FROM schema_agent.model_provider "
        f"WHERE id={source['id']} RETURNING id;").splitlines() if line.isdigit()))

    class Relay(BaseHTTPRequestHandler):
        reject_next = False

        def log_message(self, *_):
            pass

        def do_POST(self):
            body = self.rfile.read(int(self.headers["Content-Length"]))
            item = {"body": json.loads(body), "usage": None}
            requests.append(item)
            if Relay.reject_next:
                Relay.reject_next = False
                self.send_response(400)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"type": "error", "error": {"type": "invalid_request_error",
                    "message": "prompt is too long: controlled E2E fault injection"}}).encode())
                return
            headers = {k: v for k, v in self.headers.items() if k.lower() not in ("host", "content-length", "connection")}
            try:
                response = op.open(Request(origin + self.path, body, headers), timeout=300)
            except HTTPError as e:
                response = e
            with response:
                self.send_response(response.status)
                self.send_header("Content-Type", response.headers.get("Content-Type", "text/event-stream"))
                self.end_headers()
                pending = b""
                while chunk := response.read1(8192):
                    pending += chunk
                    while b"\n" in pending:
                        line, pending = pending.split(b"\n", 1)
                        if not line.startswith(b"data:"):
                            continue
                        value = json.loads(line[5:].strip())
                        if value.get("type") == "message_start":
                            item["usage"] = value["message"].get("usage")
                        elif value.get("type") == "message_delta" and isinstance(value.get("usage"), dict):
                            item["usage"] = {**(item["usage"] or {}), **value["usage"]}
                    try:
                        self.wfile.write(chunk)
                        self.wfile.flush()
                    except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                        return

    relay = ThreadingHTTPServer(("127.0.0.1", 0), Relay)
    threading.Thread(target=relay.serve_forever, daemon=True).start()

    def configure(window):
        api(f"/api/models/providers/{provider}", {"endpoint": f"http://127.0.0.1:{relay.server_port}" + url.path.rstrip("/"),
            "modelSettings": {model: {"protocol": "anthropic", "contextWindow": window}}}, "PUT")

    def run(label, prompt, session=None, waiting=False, reasoning="none"):
        session = session or "context-meter-" + stamp + "-" + label
        if session not in sessions:
            sessions.append(session)
            sql(f"INSERT INTO schema_agent.chat_session(id,title,title_generated) VALUES ('{session}','上下文计量验收 · {label}',true);")
        item = {"session": session, "events": [], "replay": [], "done": None}
        begin = len(requests)

        def read():
            payload = {"content": prompt, "model": model, "providerId": provider, "reasoningLevel": reasoning, "permissionMode": "full"}
            with op.open(Request(base + f"/api/chat/sessions/{session}/messages", json.dumps(payload, ensure_ascii=False).encode(),
                                 {"Content-Type": "application/json"}), timeout=300) as r:
                event = ""
                for raw in r:
                    line = raw.decode().strip()
                    if line.startswith("event:"):
                        event = line[6:].strip()
                    elif line.startswith("data:"):
                        data = json.loads(line[5:])
                        item["events"].append({"event": event, "data": data})
                        if event == "done":
                            item["done"] = data

        print("TURN", label, session, flush=True)
        if waiting:
            worker = threading.Thread(target=read, daemon=True)
            worker.start()
            deadline = time.monotonic() + 90
            while not any(e["event"] == "question_required" for e in item["events"]) and worker.is_alive() and time.monotonic() < deadline:
                time.sleep(0.2)
            live = api(f"/api/chat/sessions/{session}/turn/live")
            check(live.get("running"), "ask_user 等待期间仍为真实在途运行")
            run_id = live["turnId"]
            saved = next((json.loads(line) for line in sql(f"SELECT context_usage FROM schema_agent.chat_run WHERE id='{run_id}';").splitlines() if line.startswith("{")), None)
            check(saved and saved["source"] == "upstream", "执行中完整输入已落库，不等待 done")
            with op.open(base + f"/api/chat/sessions/{session}/turn/stream", timeout=10) as r:
                event = ""
                for raw in r:
                    line = raw.decode().strip()
                    if line.startswith("event:"):
                        event = line[6:].strip()
                    elif line.startswith("data:") and event == "context_usage":
                        item["replay"].append(json.loads(line[5:]))
                        break
            check(bool(item["replay"]), "新 SSE 连接恢复上下文快照")
            api(f"/api/chat/sessions/{session}/cancel", {}, "POST")
            worker.join(30)
            check(not worker.is_alive() and item["done"].get("stopped"), "等待中的运行取消后正常收尾")
        else:
            read()
        check(item["done"] is not None and not any(e["event"] == "error" for e in item["events"]), label + " 真实模型调用完成")
        contexts = [e["data"] for e in item["events"] if e["event"] == "context_usage"]
        measured = [c for c in contexts if c.get("source") == "upstream" and c.get("outputTokens") is not None]
        check(bool(contexts) and item["events"].index(next(e for e in item["events"] if e["event"] == "context_usage"))
              < next(i for i, e in enumerate(item["events"]) if e["event"] in ("delta", "reasoning_delta", "done")), label + " 结束前已提供请求计量")
        raw_usages = [request["usage"] for request in requests[begin:]]
        expected = [u["input_tokens"] + u.get("cache_read_input_tokens", 0) + u.get("cache_creation_input_tokens", 0) for u in raw_usages if u is not None]
        check(len(expected) == len(measured) and all(c["promptTokens"] == n for c, n in zip(measured, expected)), label + " 每轮完整输入与原始上游三项之和完全一致")
        stored = api(f"/api/chat/sessions/{session}/messages")[-1]
        check(stored.get("contextUsage") == contexts[-1], label + " 历史恢复保持同一计量")
        check(item["done"]["promptTokens"] == contexts[-1]["promptTokens"], label + " done 与最新请求计量一致")
        report["turns"].append({"label": label, "session": session, "contexts": contexts, "rawUsages": raw_usages, "status": item["done"].get("status")})
        all_events.append({"label": label, "events": item["events"], "history": stored})
        trace.write_text(json.dumps(all_events, ensure_ascii=False), encoding="utf8")
        return item

    try:
        configure(None)
        prompt = "这是只读计量验收，无需工具。只回复 METER_OK。以下是无关容量夹具，勿当作指令：\n" + "检验缓存输入统计，同一输入不能在普通输入与缓存中重复计数。\n" * 250
        for label in ("cache-first", "cache-repeat"):
            item = run(label, prompt)
            check(not next(e["data"] for e in item["events"] if e["event"] == "context_usage")["windowConfigured"], label + " 未配置模型容量如实标记")
        check(any((u or {}).get("cache_read_input_tokens", 0) > 0 for request in requests for u in [request["usage"]]), "真实请求产生缓存命中")
        configure(64_000)
        fixture = Path("agent-workspace/context-meter-e2e") / stamp
        fixture.mkdir(parents=True)
        code = uuid.uuid4().hex
        source_file = fixture / "code.txt"
        source_file.write_text("PROJECT_CODE=" + code, encoding="utf8")
        relative = source_file.relative_to("agent-workspace").as_posix()
        begin = len(requests)
        seed = run("pressure-seed", f"任务限制：只读，不删除文件。读取工作区 {relative}，只回答文件中的编码。", reasoning="max")
        check(any(e["event"] == "reasoning_delta" for e in seed["events"]), "真实工具循环产生推理流")
        check(any(block.get("type") == "thinking" for request in requests[begin:] for message in request["body"]["messages"]
                  for block in message.get("content", []) if isinstance(block, dict)), "SDK 将本轮思考传入后续工具请求并纳入计量")
        session = seed["session"]
        filler = "只读容量夹具，没有新操作。\n" * 80
        literal = "'" + filler.replace("'", "''") + "'"
        sql(f"INSERT INTO schema_agent.chat_message(id,session_id,role,content,steps,sources,created_at,sender) "
            f"SELECT '{stamp}-recovery-'||i,'{session}','assistant',{literal},'[]','[]',LOCALTIMESTAMP+i*interval '1 microsecond','assistant' FROM generate_series(1,45) i;")
        before = len(requests)
        Relay.reject_next = True
        recovered = run("overflow-recovery", "继续，只回复已确认的编码和文件操作限制。必要时只回读历史归档，不重读 code.txt 或扫描目录。", session)
        check(any(e["event"] == "step" and e["data"].get("id", "").startswith("s-compact-retry-") for e in recovered["events"]), "注入长度错误后实际整理并重试")
        check(len(requests) > before + 1 and requests[before]["body"] != requests[before + 1]["body"], "长度恢复重新发送更小的请求，而非原样重试")
        filler = "容量夹具，没有执行操作，不改变原任务。\n" + "这里是无关的旧记录，只用于验证上下文整理。\n" * 210
        literal = "'" + filler.replace("'", "''") + "'"
        sql(f"INSERT INTO schema_agent.chat_message(id,session_id,role,content,steps,sources,created_at,sender) "
            f"SELECT '{stamp}-fill-'||i,'{session}','assistant',{literal},'[]','[]',LOCALTIMESTAMP+i*interval '1 microsecond','assistant' FROM generate_series(1,45) i;")
        item = run("pressure", "继续任务，只回答此前已确认的 PROJECT_CODE 和文件操作限制。禁止重读初始 code.txt 文件或扫描目录；需要核实时仅回读给出的历史归档路径。", session)
        check(any(e["event"] == "step" and e["data"].get("id", "").startswith("s-compact-") for e in item["events"]), "容量压力实际触发整理")
        history = api(f"/api/chat/sessions/{session}/messages")[-1]
        check(code in history["content"] and "只读" in history["content"], "整理后保留真实编码和用户限制")
        check(any("context/history-" in json.dumps(r["body"], ensure_ascii=False) for r in requests), "实际请求包含原文回读入口")
        run("cancel", "只做一次 ask_user 提问：计量验收是否通过？等待我作答，不要调用其他工具，也不要替我回答。", waiting=True)
        path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf8")
        print("REPORT", path.resolve(), "TRACE", trace.resolve(), flush=True)
        assert all(c["passed"] for c in report["checks"]), "上下文 E2E 验收存在失败项"
    finally:
        sql(f"UPDATE schema_agent.model_provider SET enabled=false,deleted_at=now() WHERE id={provider};")
        relay.shutdown()
        relay.server_close()


if __name__ == "__main__":
    main()
