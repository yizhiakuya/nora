"""真实模型与数据库的压缩检查点验收；中继仅记录请求并注入失败/延迟。"""
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
    report = {"checks": [], "turns": [], "session": "slash-e2e-" + stamp}
    path = Path("logs/chat-commands-e2e-" + stamp + ".json")
    requests = []
    session = report["session"]

    def check(value, label):
        report["checks"].append({"label": label, "passed": bool(value)})
        path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf8")
        print("PASS" if value else "FAIL", label, flush=True)

    def sql(query):
        return subprocess.run(["rtk", "proxy", "docker", "exec", "nora-postgres", "psql", "-U", "nora", "-d", "nora", "-At", "-c", query],
                              capture_output=True, text=True, encoding="utf8", check=True).stdout

    def api(endpoint, data=None, method=None):
        with op.open(Request(base + endpoint, None if data is None else json.dumps(data, ensure_ascii=False).encode(),
                             {"Content-Type": "application/json"}, method=method), timeout=30) as response:
            value = json.load(response)
            return value.get("data", value) if isinstance(value, dict) else value

    providers = api("/api/models/providers")
    source = next(provider for provider in providers if provider["enabled"] and model in provider["models"])
    url = urlsplit(source["endpoint"])
    origin = url.scheme + "://" + url.netloc
    provider = int(next(line for line in sql("INSERT INTO schema_agent.model_provider(name,protocol,endpoint,api_key,enabled,models,status,model_settings) "
        f"SELECT 'slash-e2e-{stamp}',protocol,endpoint,api_key,true,models,status,model_settings FROM schema_agent.model_provider "
        f"WHERE id={source['id']} RETURNING id;").splitlines() if line.isdigit()))

    class Relay(BaseHTTPRequestHandler):
        mode = "normal"
        entered = threading.Event()
        release = threading.Event()

        def log_message(self, *_):
            pass

        def do_POST(self):
            body = self.rfile.read(int(self.headers["Content-Length"]))
            requests.append(json.loads(body))
            mode = Relay.mode
            Relay.entered.set()
            if mode == "delay":
                Relay.release.wait(45)
                return
            if mode == "reject":
                self.send_response(400)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"type": "error", "error": {"type": "invalid_request_error", "message": "controlled E2E compaction rejection"}}).encode())
                return
            headers = {key: value for key, value in self.headers.items() if key.lower() not in ("host", "content-length", "connection")}
            try:
                response = op.open(Request(origin + self.path, body, headers), timeout=300)
            except HTTPError as error:
                response = error
            with response:
                self.send_response(response.status)
                self.send_header("Content-Type", response.headers.get("Content-Type", "text/event-stream"))
                self.end_headers()
                try:
                    while chunk := response.read1(8192):
                        self.wfile.write(chunk)
                        self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                    pass

    relay = ThreadingHTTPServer(("127.0.0.1", 0), Relay)
    threading.Thread(target=relay.serve_forever, daemon=True).start()

    def configure(window=128_000):
        api(f"/api/models/providers/{provider}", {"endpoint": f"http://127.0.0.1:{relay.server_port}" + url.path.rstrip("/"),
            "modelSettings": {model: {"protocol": "anthropic", "contextWindow": window}}}, "PUT")

    def stream(label, compact=False, focus="", request_id=None, prompt=None, delayed=False):
        payload = {"model": model, "providerId": provider, "reasoningLevel": "none"}
        payload.update({"requestId": request_id or str(uuid.uuid4()), "focus": focus} if compact else {"content": prompt, "permissionMode": "ask"})
        item = {"label": label, "events": [], "done": None, "requestId": payload.get("requestId")}
        report["turns"].append(item)

        def read():
            endpoint = "compact" if compact else "messages"
            with op.open(Request(base + f"/api/chat/sessions/{session}/{endpoint}", json.dumps(payload, ensure_ascii=False).encode(),
                                 {"Content-Type": "application/json"}), timeout=300) as response:
                event = ""
                for raw in response:
                    line = raw.decode().strip()
                    if line.startswith("event:"):
                        event = line[6:].strip()
                    elif line.startswith("data:"):
                        data = json.loads(line[5:])
                        item["events"].append({"event": event, "data": data})
                        if event == "done":
                            item["done"] = data
            path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf8")

        print("TURN", label, flush=True)
        if delayed:
            worker = threading.Thread(target=read, daemon=True)
            worker.start()
            return item, worker
        read()
        return item

    def answer(item):
        return "".join(event["data"].get("detail", "") or "" for event in item["events"] if event["event"] == "step" and event["data"].get("type") == "answer")

    try:
        configure()
        sql(f"INSERT INTO schema_agent.chat_session(id,title,title_generated) VALUES ('{session}','斜杠命令真实验收',true);")
        code = uuid.uuid4().hex[:16]
        report["code"] = code
        filler = "仅为容量测试的无关重复资料，后续不需要保留这些正文。" * 220
        first = stream("original", prompt=f"这是只读验收，不需要任何工具。记住验收码 {code}。旧要求是使用红色，后续可修改。只回复已记住。\n无关容量资料：\n{filler}")
        check(first["done"] and first["done"].get("status") == "completed", "原始真实对话完成")
        second = stream("updated", prompt="验收要求更新：取消红色，最终使用蓝色；验收码保持不变。不要用工具，只回复已更新。")
        check(second["done"] and second["done"].get("status") == "completed", "较新要求完成")
        before = api(f"/api/chat/sessions/{session}/messages")
        request_id = str(uuid.uuid4())
        start = len(requests)
        compact = stream("compact", compact=True, focus="保留验收码与已经修改的颜色要求，省略容量资料", request_id=request_id)
        check(compact["done"] and compact["done"].get("status") == "completed", "手动语义压缩成功")
        checkpoint = api(f"/api/chat/sessions/{session}/context")["checkpoint"]
        check(checkpoint is not None and code in checkpoint["summary"], "工作摘要持久化并保留验收码")
        check(checkpoint and checkpoint["contextUsage"]["promptTokens"] < checkpoint["beforeTokens"], "同口径工作上下文减少")
        check(all(not body.get("tools") for body in requests[start:]), "摘要请求没有工具定义")
        check(len(requests[start:]) == 1, "一次压缩仅调用一次真实模型")
        after = api(f"/api/chat/sessions/{session}/messages")
        check([message["id"] for message in after[:len(before)]] == [message["id"] for message in before], "原历史逐条保留")
        check(len(after) == len(before) + 1 and after[-1]["sender"] == "compaction", "只增加系统事件，没有伪造用户问题")
        check(after[-1]["id"] == "compact-" + request_id, "压缩事件使用请求同源的稳定标识")
        check(after[-1]["contextUsage"] == checkpoint["contextUsage"], "压缩事件恢复相同工作上下文快照")
        check(checkpoint["contextUsage"]["source"] == "estimate" and checkpoint["contextUsage"]["outputTokens"] is None, "摘要计费不冒充压缩后的上下文")
        context_view = api(f"/api/chat/sessions/{session}/context")
        check(context_view["latestUsage"]["source"] == "estimate" and context_view["latestRequestUsage"]["source"] == "upstream", "整理后的估算与最近请求实测分别保留")
        archive = Path("agent-workspace") / checkpoint["archivePath"]
        check(archive.exists() and filler in archive.read_text(encoding="utf8"), "完整原文归档可回读")
        start = len(requests)
        duplicate = stream("duplicate", compact=True, focus="保留验收码与已经修改的颜色要求，省略容量资料", request_id=request_id)
        check(duplicate["done"] and len(requests) == start, "相同请求 ID 不重复调用模型")
        check(len(api(f"/api/chat/sessions/{session}/messages")) == len(after), "幂等重传不新增历史事件")
        start = len(requests)
        followup = stream("followup", prompt="无需工具。请只输出之前的验收码和最终颜色，格式为 验收码|颜色。")
        text = answer(followup)
        check(code in text and "蓝" in text and "红" not in text, "压缩后下一轮遵循最新有效要求")
        request_text = json.dumps(requests[start:], ensure_ascii=False)
        check("历史工作摘要" in request_text and filler[:1000] not in request_text, "下一次请求使用检查点，没有重新装回旧长文")
        check(checkpoint == api(f"/api/chat/sessions/{session}/context")["checkpoint"], "正常后续对话保留成功检查点")

        Relay.mode = "reject"
        rejected_id = str(uuid.uuid4())
        rejected = stream("failure", compact=True, request_id=rejected_id)
        check(rejected["done"] and rejected["done"]["status"] == "failed", "上游拒绝时记录失败终态")
        check(any("controlled E2E" in event["data"].get("message", "") for event in rejected["events"] if event["event"] == "error"), "上游原始错误可见")
        check(checkpoint == api(f"/api/chat/sessions/{session}/context")["checkpoint"], "压缩失败保留原检查点")
        start = len(requests)
        stream("failure-duplicate", compact=True, request_id=rejected_id)
        check(len(requests) == start, "失败请求重传返回原终态，不再次计费")

        Relay.mode = "delay"
        Relay.entered.clear()
        cancelled, worker = stream("cancel", compact=True, delayed=True)
        check(Relay.entered.wait(30), "压缩进入实际上游调用")
        live = api(f"/api/chat/sessions/{session}/turn/live")
        check(live["running"] and live.get("operation") == "compact", "刷新探测明确标记压缩运行")
        try:
            api(f"/api/chat/sessions/{session}/messages", {"content": "不应插入的并发请求"}, "POST")
            conflict = False
        except HTTPError as error:
            conflict = error.code == 409
        check(conflict, "普通发送与压缩共享会话互斥")
        api(f"/api/chat/sessions/{session}/cancel", {}, "POST")
        worker.join(30)
        check(not worker.is_alive() and cancelled["done"] and cancelled["done"].get("stopped"), "压缩可取消并正常结束流")
        check(checkpoint == api(f"/api/chat/sessions/{session}/context")["checkpoint"], "取消不替换成功检查点")
        Relay.release.set()
        Relay.mode = "normal"

        api(f"/api/chat/sessions/{session}/messages/2", method="DELETE")
        check(api(f"/api/chat/sessions/{session}/context")["checkpoint"] is None, "编辑历史在同一事务中使检查点失效")
        check(len(api(f"/api/chat/sessions/{session}/messages")) == 2, "编辑截断保留正确的原始历史边界")

        configure(14_000)
        start = len(requests)
        automatic = stream("automatic", prompt="验收要求更新：取消红色，最终使用蓝色；验收码保持不变。无需工具，只输出验收码和蓝色。")
        check(automatic["done"] and automatic["done"].get("status") == "completed", "达到阈值后自动摘要并完成当前回答")
        check(any(event["event"] == "step" and event["data"].get("id") == "s-auto-compact"
                  and event["data"].get("status") == "completed" for event in automatic["events"]), "自动整理调用共用语义压缩核心")
        auto_checkpoint = api(f"/api/chat/sessions/{session}/context")["checkpoint"]
        check(auto_checkpoint and auto_checkpoint["boundaryId"] == before[1]["id"], "自动检查点只覆盖已完成的稳定历史")
        check(code in answer(automatic) and "蓝" in answer(automatic), "自动压缩保留本轮最新要求与验收码")
        check(len(requests[start:]) == 2 and not requests[start].get("tools") and requests[start + 1].get("tools"), "自动摘要无工具，正常回答仍使用原工具能力")
        check("历史工作摘要" in json.dumps(requests[-1], ensure_ascii=False) and filler[:1000] not in json.dumps(requests[-1], ensure_ascii=False), "阈值整理后实际模型请求使用摘要")
        check(api(f"/api/chat/sessions/{session}/messages")[0]["content"].endswith(filler), "自动压缩仍保留原始长文供回读")
    finally:
        Relay.release.set()
        api(f"/api/models/providers/{provider}", method="DELETE")
        relay.shutdown()
    failures = [item for item in report["checks"] if not item["passed"]]
    print(json.dumps({"report": str(path), "checks": len(report["checks"]), "failures": len(failures), "session": session}), flush=True)
    if failures:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
