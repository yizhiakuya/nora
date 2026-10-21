"""真实模型/工具的长上下文验收；容量历史填充明确标注，代理仅透传请求。"""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import subprocess
import threading
import time
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import ProxyHandler, Request, build_opener
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://127.0.0.1:18080")
    parser.add_argument("--provider", type=int, default=7)
    parser.add_argument("--model", default="deepseek-v4.1-flash")
    parser.add_argument("--phase", choices=["baseline", "verify"], default="verify")
    parser.add_argument("--case", choices=["all", "output", "failure", "micro", "history", "pressure"], default="all")
    args = parser.parse_args()
    stamp = str(time.time_ns())
    opener = build_opener(ProxyHandler({}))
    record = {"phase": args.phase, "model": args.model, "checks": [], "turns": []}
    report = Path("logs/context-e2e-" + args.phase + "-" + stamp + ".json")
    report.parent.mkdir(parents=True, exist_ok=True)
    fixture = Path("agent-workspace/context-e2e") / stamp
    fixture.mkdir(parents=True)
    requests = []

    def api(path, data=None, method=None):
        body = None if data is None else json.dumps(data, ensure_ascii=False).encode()
        with opener.open(Request(args.base + path, body, {"Content-Type": "application/json"}, method=method), timeout=30) as response:
            value = json.load(response)
            return value.get("data", value) if isinstance(value, dict) else value

    def sql(query):
        return subprocess.run(["rtk", "proxy", "docker", "exec", "nora-postgres", "psql", "-U", "nora", "-d", "nora", "-At", "-c", query],
                              check=True, capture_output=True, text=True, encoding="utf8").stdout

    def check(value, label):
        record["checks"].append({"label": label, "passed": bool(value)})
        report.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf8")
        print("PASS" if value else "FAIL", label, flush=True)

    source = next(p for p in api("/api/models/providers") if p["id"] == args.provider)
    upstream = urlsplit(source["endpoint"])
    origin = upstream.scheme + "://" + upstream.netloc
    provider = int(next(line for line in sql(
        "INSERT INTO schema_agent.model_provider(name,protocol,endpoint,api_key,enabled,models,status,model_settings) "
        f"SELECT 'context-e2e-{stamp}',protocol,endpoint,api_key,true,models,status,model_settings "
        f"FROM schema_agent.model_provider WHERE id={args.provider} AND deleted_at IS NULL RETURNING id;").splitlines() if line.isdigit()))

    class Relay(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            body = self.rfile.read(int(self.headers["Content-Length"]))
            requests.append(json.loads(body))
            headers = {k: v for k, v in self.headers.items() if k.lower() not in ("host", "content-length", "connection")}
            try:
                response = opener.open(Request(origin + self.path, body, headers), timeout=300)
            except HTTPError as error:
                response = error
            with response:
                self.send_response(response.status)
                self.send_header("Content-Type", response.headers.get("Content-Type", "text/event-stream"))
                self.end_headers()
                while chunk := response.read1(8192):
                    try:
                        self.wfile.write(chunk)
                        self.wfile.flush()
                    except (BrokenPipeError, ConnectionAbortedError, ConnectionResetError):
                        return

    relay = ThreadingHTTPServer(("127.0.0.1", 0), Relay)
    threading.Thread(target=relay.serve_forever, daemon=True).start()
    endpoint = f"http://127.0.0.1:{relay.server_port}" + upstream.path.rstrip("/")

    def configure(window):
        api(f"/api/models/providers/{provider}", {"endpoint": endpoint, "modelSettings": {
            args.model: {"protocol": "anthropic", "contextWindow": window}}}, "PUT")
        requests.clear()

    def run(label, prompt, session=None, insert=False):
        session = session or "context-e2e-" + stamp + "-" + label
        if not session_exists(session):
            sql(f"INSERT INTO schema_agent.chat_session(id,title,title_generated) VALUES ('{session}','长上下文验收 · {label}',true);")
        item = {"session": session, "steps": {}, "errors": [], "inserted": False}
        record["turns"].append(item)
        payload = {"content": prompt, "model": args.model, "providerId": provider,
                   "reasoningLevel": "none", "permissionMode": "full"}
        print("TURN", label, flush=True)
        with opener.open(Request(args.base + f"/api/chat/sessions/{session}/messages", json.dumps(payload, ensure_ascii=False).encode(),
                                 {"Content-Type": "application/json"}), timeout=300) as response:
            event = ""
            for raw in response:
                line = raw.decode().strip()
                if line.startswith("event:"):
                    event = line[6:].strip()
                elif line.startswith("data:"):
                    data = json.loads(line[5:])
                    if event == "step":
                        item["steps"][data["id"]] = {k: v for k, v in data.items() if k != "detail"}
                        if not item.get("stoppedByFixture") and len([s for s in item["steps"].values() if s.get("toolName")]) > 8:
                            item["stoppedByFixture"] = True
                            api(f"/api/chat/sessions/{session}/cancel", {}, "POST")
                        if insert and not item["inserted"] and data.get("toolName") == "run_command" and data.get("status") == "running":
                            live = api(f"/api/chat/sessions/{session}/turn/live")
                            api(f"/api/chat/sessions/{session}/steer", {"turnId": live["turnId"], "messageId": str(uuid.uuid4()),
                                "content": '补充要求：最终只输出 JSON，字段为 code 和 scope，scope 必须为 updated；仍然禁止重跑发令命令。'})
                            item["inserted"] = True
                    elif event == "done":
                        item["done"] = data
                    elif event == "error":
                        item["errors"].append(data)
        item["answer"] = api(f"/api/chat/sessions/{session}/messages")[-1]["content"]
        item["requestCount"] = len(requests)
        paired = True
        for request in requests:
            pending = set()
            for message in request.get("messages", []):
                parts = message.get("content", [])
                if not isinstance(parts, list):
                    continue
                for part in parts:
                    if part.get("type") == "tool_use":
                        pending.add(part["id"])
                    elif part.get("type") == "tool_result":
                        paired = paired and part["tool_use_id"] in pending
                        pending.discard(part["tool_use_id"])
            paired = paired and not pending
        check(paired, label + " 请求中工具调用与结果完整配对")
        return item

    def session_exists(session):
        return any(line == "1" for line in sql(f"SELECT 1 FROM schema_agent.chat_session WHERE id='{session}';").splitlines())

    try:
        for label in (["output", "failure", "micro"] if args.case == "all" else [args.case] if args.case in ("output", "failure", "micro") else []):
            configure(32000 if label == "micro" else 128000)
            code = uuid.uuid4().hex
            counter = fixture / (label + "-count.txt")
            emit = fixture / (label + ".py")
            emit.write_text("from pathlib import Path\nimport sys\n"
                + f"p=Path({str(counter.resolve())!r})\np.write_text(p.read_text()+'once\\n' if p.exists() else 'once\\n')\n"
                + "print('password=E2E_REDACT_ME')\n"
                + "for i in range(1800):\n"
                + f" print('PROJECT_CODE={code}' if i==1000 else f'row {{i:04d}} '+('abcdefghijklmnopqrstuvwxyz '*3))\n"
                + ("sys.exit(2)\n" if label == "failure" else ""), encoding="utf8")
            relative = emit.relative_to("agent-workspace").as_posix()
            extra = ""
            if label == "micro":
                extra = "发令完成后，依次在三个独立工具轮次执行下列只读容量命令，不能并列调用：\n" + "\n".join(
                    f"Write-Output 'stage-{i}'; 1..1400 | ForEach-Object {{ 'abcdefgh' * 10 }}" for i in range(2, 5)) + "\n完成全部四个工具轮次后，再找第一份输出的编码。"
            item = run(label, f"这是一次性发令任务。在默认工作区原样运行一次 run_command：rtk proxy python {relative}。" + extra +
                "从工具输出找到 PROJECT_CODE。长输出只能从工具给出的完整记录路径回读；"
                "如果结果没有提供实际回读路径，直接说明无法核实并结束，不要猜路径或继续找记录。"
                "禁止重跑命令、读发令脚本或扫描无关目录。即使命令非零退出，也需要找出输出里的编码。最终只回答编码。", insert=label == "output")
            check(code in item["answer"], label + " 中段证据可恢复且答案准确")
            check(counter.read_text().splitlines() == ["once"], label + " 发令命令仅执行一次")
            tools = [s for s in item["steps"].values() if s.get("toolName") == "run_command"]
            check(any(s["status"] == ("failed" if label == "failure" else "completed") for s in tools), label + " 保留真实执行状态")
            if label == "output":
                check(item["inserted"] and 'updated' in item["answer"], "长输出处理期间消费补充要求")
            paths = set(re.findall(r"context/(?:tool|history)-[a-f0-9]+\.txt", json.dumps(item, ensure_ascii=False)))
            check(any(code in (Path("agent-workspace") / p).read_text(encoding="utf8") for p in paths), label + " 原文实际落盘")
            check(bool(paths) and all("E2E_REDACT_ME" not in (Path("agent-workspace") / p).read_text(encoding="utf8") for p in paths), label + " 落盘证据已脱敏")
            if label == "micro":
                check(any(key.startswith("s-compact-") for key in item["steps"]), "多轮工具执行实际触发上下文压缩")
                check("早期工具结果已压缩" in json.dumps(requests, ensure_ascii=False), "压缩后的工具记录进入实际模型请求")

        for label in (["history", "pressure"] if args.case == "all" else [args.case] if args.case in ("history", "pressure") else []):
            configure(128000)
            code = uuid.uuid4().hex
            source_file = fixture / (label + ".txt")
            source_file.write_text("PROJECT_CODE=" + code, encoding="utf8")
            relative = source_file.relative_to("agent-workspace").as_posix()
            item = run(label + "-seed", f"任务约定：只读、不删除文件。读取工作区 {relative}，只回复其完整文本，这就是已完成阶段的结论。")
            check(code in item["answer"], label + " 种子阶段来自真实模型和文件工具")
            session = item["session"]
            # 容量夹具，不冒充真实模型执行：填充记录不改变任务，也不提供编码。
            filler = "容量填充记录，没有执行任何操作，原约定保持。" if label == "history" else "容量填充记录，没有执行任何操作。\n" + "已略过无关数据。\n" * 500
            literal = "'" + filler.replace("'", "''") + "'"
            sql(f"INSERT INTO schema_agent.chat_message(id,session_id,role,content,steps,sources,created_at,sender) "
                f"SELECT '{stamp}-{label}-fill-'||i,'{session}','assistant',{literal},'[]','[]',"
                "LOCALTIMESTAMP + i * interval '1 microsecond','assistant' FROM generate_series(1,45) AS i;")
            configure(32000 if label == "pressure" else 128000)
            item = run(label + "-continue", "继续原任务。只回答已完成阶段的 PROJECT_CODE 和仍有效的文件操作限制。"
                "不要重读最初的输入文件，不要扫描工作区；需要回查时只读系统提供的历史记录路径。", session=session)
            check(code in item["answer"], label + " 继续任务保留先前结论")
            check("只读" in item["answer"] and "删" in item["answer"], label + " 用户限制仍有效")
            request_text = json.dumps(requests, ensure_ascii=False)
            if label == "pressure":
                check("context/history-" in request_text, "预算不足时提供历史回读路径")
            check(not any(relative in str(s.get("input")) for s in item["steps"].values()), label + " 不重新读取已完成的原始输入")
        report.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf8")
        print("RECORD", report.resolve(), flush=True)
        if args.phase == "verify":
            assert all(c["passed"] for c in record["checks"]), "长上下文验收存在失败项"
    finally:
        sql(f"UPDATE schema_agent.model_provider SET enabled=false,deleted_at=now() WHERE id={provider};")
        relay.shutdown()
        relay.server_close()


if __name__ == "__main__":
    main()
