"""Local real-service/model verification of the architecture audit fixes.

Faults affect only unique audit sessions and SDK fixture processes. No production
endpoint, existing provider or session is changed; request relays only forward
the real model. Assertions belong to this E2E program, not unit smoke tests.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import time
import uuid
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import ProxyHandler, Request, build_opener

import psutil
import requests
import socket
from PIL import Image, ImageDraw, ImageFont


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checks", default="core,mcp,browser,context")
    parser.add_argument("--agent-pid", type=int, required=True)
    args = parser.parse_args()
    selected = args.checks.split(",")
    stamp = str(time.time_ns())
    prefix = "audit-" + stamp
    root = Path("agent-workspace/qa-audit") / stamp
    root = root.resolve()
    root.mkdir(parents=True)
    report_path = Path("logs") / (prefix + ".json")
    report = {"checks": [], "turns": []}
    http = requests.Session()
    http.trust_env = False
    base = "http://127.0.0.1:18080/api"
    workers = ThreadPoolExecutor(max_workers=4)
    fixture_ids = []
    provider_ids = []
    credential_key = None

    def check(value, label, detail=None):
        report["checks"].append({"label": label, "passed": bool(value), "detail": detail})
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf8")
        print("PASS" if value else "FAIL", label, flush=True)
        if not value:
            raise AssertionError(label)

    def api(path, body=None, method="GET", timeout=50, origin=None):
        response = http.request(method, (origin or base) + path, json=body, timeout=timeout)
        response.raise_for_status()
        result = response.json()
        return result.get("data", result) if isinstance(result, dict) else result

    def sql(statement):
        result = subprocess.run(["rtk", "proxy", "docker", "exec", "-i", "nora-postgres", "psql", "-U", "nora", "-d", "nora", "-tA", "-v", "ON_ERROR_STOP=1"],
                                input=statement, text=True, encoding="utf8", capture_output=True, check=True)
        return result.stdout.strip()

    provider = next(p for p in api("/models/providers") if p["enabled"] and p["name"] == "nora-a")
    model = provider["models"][0]

    def request(content, context=None, provider_id=None, reasoning="none"):
        return {"content": content, "context": context, "model": model, "providerId": provider_id or provider["id"],
                "reasoningLevel": reasoning, "permissionMode": "full"}

    def session(suffix):
        sid = prefix + "-" + suffix
        sql(f"INSERT INTO schema_agent.chat_session(id,title,title_generated) VALUES ('{sid}','架构验收 · {suffix}',true);")
        return sid

    def turn(sid, prompt, context=None, provider_id=None, callback=None, reasoning="none"):
        print("TURN", sid, flush=True)
        events = []
        started = time.monotonic()
        with http.post(base + f"/chat/sessions/{sid}/messages", json=request(prompt, context, provider_id, reasoning), stream=True, timeout=240) as response:
            response.raise_for_status()
            response.encoding = "utf-8"
            event = ""
            for line in response.iter_lines(chunk_size=4096, decode_unicode=True):
                if not line:
                    continue
                if line.startswith("event:"):
                    event = line[6:].strip()
                elif line.startswith("data:"):
                    data = json.loads(line[5:])
                    events.append((event, data))
                    if callback:
                        callback(event, data)
        report["turns"].append({"session": sid, "elapsed": round(time.monotonic() - started, 2),
            "done": [d for e, d in events if e == "done"], "errors": [d for e, d in events if e == "error"]})
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf8")
        return events

    def wait(get, ready, seconds=60):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            value = get()
            if ready(value):
                return value
            time.sleep(0.2)
        raise TimeoutError("Condition did not become ready")

    def history(sid):
        return api(f"/chat/sessions/{sid}/messages")

    def queue(sid, content, context=None):
        value = {"id": str(uuid.uuid4()), "kind": "message", "content": content, "refs": [], "limitToRefs": False,
                 "request": request(content, context)}
        api(f"/chat/sessions/{sid}/queue", value, "POST")
        return value

    def queued(sid):
        return api(f"/chat/sessions/{sid}/queue")

    def tool_steps(events, name=None):
        return [d for e, d in events if e == "step" and d.get("toolName") and (name is None or d["toolName"] == name)]

    def nonnull(value):
        return {k: nonnull(v) for k, v in value.items() if v is not None} if isinstance(value, dict) else value

    def pids():
        return {p.pid for p in psutil.Process(args.agent_pid).children(recursive=True)}

    def restart_agent(options=()):
        process = psutil.Process(args.agent_pid)
        command = process.cmdline()
        if not any("nora-api-0.1.0-SNAPSHOT.jar" in value for value in command):
            raise RuntimeError("Refusing to stop unrelated process")
        process.kill()
        process.wait(20)
        with Path("logs/audit-agent-service.out.log").open("ab") as output, Path("logs/audit-agent-service.err.log").open("ab") as error:
            launched = subprocess.Popen(command[:3] + list(options), cwd=Path.cwd(), stdout=output, stderr=error,
                                        creationflags=subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0)
        args.agent_pid = launched.pid

        def health():
            try:
                return http.get("http://127.0.0.1:18080/actuator/health", timeout=2).status_code
            except requests.RequestException:
                return 0
        wait(health, lambda code: code == 200, 60)
        print("AGENT_PID", args.agent_pid, flush=True)

    try:
        if "task-scope" in selected or "task-target" in selected:
            original_id = "sess-1791592446888"
            original = history(original_id)
            check(len(original) >= 6, "验收来源包含真实失败与取消轮次")
            sid = session("scope-history")
            def literal(value):
                return "NULL" if value is None else "'" + str(value).replace("'", "''") + "'"
            run_ids = {m["runId"] for m in original[:6] if m.get("runId")}
            sql("INSERT INTO schema_agent.chat_run SELECT (jsonb_populate_record(NULL::schema_agent.chat_run, to_jsonb(r) || "
                + f"jsonb_build_object('id',md5(r.id||'{sid}'),'session_id','{sid}'))).* FROM schema_agent.chat_run r "
                + "WHERE r.id IN (" + ",".join(literal(v) for v in run_ids) + ");")
            for message in original[:6]:
                row = {"id": str(uuid.uuid4()), "session_id": sid, "role": message["role"], "content": message["content"],
                       "steps": json.dumps(message["steps"], ensure_ascii=False) if message.get("steps") is not None else None,
                       "sources": json.dumps(message["sources"], ensure_ascii=False) if message.get("sources") is not None else None,
                       "duration_ms": message.get("durationMs"), "prompt_tokens": message.get("promptTokens"),
                       "context_window": message.get("contextWindow"), "created_at": message["createdAt"], "sender": message.get("sender")}
                # Run metadata must accompany the copied trace; the original session remains untouched.
                run_id = message.get("runId")
                values = [literal(value) for value in row.values()]
                sql("INSERT INTO schema_agent.chat_message(" + ",".join(row) + ",run_id) VALUES ("
                    + ",".join(values) + (f",md5({literal(run_id)}||'{sid}'));" if run_id else ",NULL);"))
            copied = history(sid)
            check(len(copied) == 6 and copied[3].get("runStatus") == "partial" and copied[-1].get("runStatus") == "cancelled", "真实失败与取消历史及轮次终态进入独立副本")

            def scope_turn(target_session, prompt):
                calls = {}
                questions = []
                report.setdefault("scope", []).append({"session": target_session, "reasoningLevel": "max", "tools": calls, "questions": questions})
                def observed(event, data):
                    if event not in ("step", "question_required"):
                        return
                    if event == "step" and data.get("toolName"):
                        calls[data["id"]] = data
                        if len(calls) > 8:
                            api(f"/chat/sessions/{target_session}/cancel", {}, "POST")
                            raise AssertionError("Status query expanded into an exploratory tool loop")
                    elif event == "question_required":
                        questions.append(data)
                        check("megumin" in data["question"].lower(), "目标不明确时围绕实际候选询问，而非扩大排查")
                        api(f"/chat/answers/{data['questionToken']}?sessionId={target_session}", {"answer": "只查看 megumin 服务器的系统资源与容器概况，不查询本机，也不排障。"}, "POST")
                    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf8")
                try:
                    events = turn(target_session, prompt, callback=observed, reasoning="max")
                finally:
                    if api(f"/chat/sessions/{target_session}/turn/live").get("running"):
                        api(f"/chat/sessions/{target_session}/cancel", {}, "POST")
                tools = list(calls.values())
                check(any(e == "done" and d.get("status") == "completed" for e, d in events)
                      and not any(e == "error" for e, _ in events), "max 档状态查询完成，未发生保存错误")
                check(any(s.get("toolName", "").startswith("mcp__megumin-ssh__") for s in tools)
                      and all(s.get("toolName") == "ask_user" or s.get("toolName", "").startswith("mcp__megumin-ssh__") for s in tools),
                      "选定 megumin 后不混入本机命令、纳管源或工作区扫描")
                check(all(s.get("status") == "completed" for s in tools), "SSH 调用无单位误填导致的失败或重试")
                for tool in tools:
                    if tool.get("toolName", "").endswith("__ssh_exec"):
                        params = json.loads(tool["input"]["rawArgs"])
                        check("timeout" not in params or params["timeout"] >= 1000, "毫秒超时使用原工具默认值或明确毫秒数")
                answer = history(target_session)[-1]["content"]
                check(bool(answer) and "megumin" in answer.lower() and "DESKTOP-TOG85IG" not in answer,
                      "最终回复限定目标服务器，未将未运行的开发服务推断为生产故障")
                return questions

            scope_turn(sid, "只看 megumin 服务器状态，只读看系统资源和容器概况。"
                       if "task-target" in selected else "看下服务器状态")
            scope_turn(session("scope-fresh"), "看下 megumin 服务器状态，只读看系统资源和容器概况。")
            check(history(original_id) == original, "原会话消息、步骤及状态保持不变，未自动重放旧命令")

        if "output" in selected:
            env_origin = "http://127.0.0.1:18080/api"
            source_ids = []
            try:
                for age, name in [(0, "fresh"), (7200, "stale")]:
                    file = root / (name + ".log")
                    file.write_text("observation only\n", encoding="utf8")
                    os.utime(file, (time.time() - age, time.time() - age))
                    source = api("/environment/managed", {"kind": "FILE", "name": prefix + "-" + name,
                        "fileLogPath": str(file)}, "POST", origin=env_origin)
                    source_ids.append(source["id"])
                sources = api("/environment/services", origin=env_origin)
                observed = [s for s in sources if int(s["id"]) in source_ids]
                check(len(observed) == 2 and all(s["health"] == "unknown" and s["status"] == "unknown" for s in observed),
                      "新旧日志都只描述日志状态，不误报服务健康或离线")
                check(all(s.get("observer", {}).get("host") and s["observer"]["platform"].startswith("Windows")
                          and s["observer"]["scope"] == "host" for s in sources), "纳管源明确标注 Windows 观察主机与执行域")
                sid = session("output")
                command = "Write-Output 'UTF8_BEFORE'; wsl --list --verbose; [Console]::Write('left' + [char]0 + 'right'); Write-Output '中文输出测试'; Write-Output 'UTF8_AFTER'"
                events = turn(sid, f"Isolated QA. Call powershell exactly once with command={json.dumps(command, ensure_ascii=False)}. Then reply only OUTPUT_UTF8_OK. Do not retry.")
                steps = list({s["id"]: s for s in tool_steps(events, "powershell")}.values())
                check(len(steps) == 1 and steps[0]["status"] == "completed" and not any(e == "error" for e, _ in events),
                      "真实 WSL 与 NUL 输出命令正常完成并保存步骤，无数据库异常")
                saved = history(sid)[-1]
                output = steps[0]["result"]["execution"]["output"]
                check("NAME" in output and "docker-desktop" in output and "中文输出测试" in output
                      and "left␀right" in output and "\0" not in output, "WSL 正确使用 UTF-8，中文完整，NUL 以可见符号保留")
                execution = steps[0]["result"]["execution"]
                log_file = Path("agent-workspace") / execution["logTarget"].removeprefix("workspace:")
                log_text = log_file.read_text(encoding="utf8")
                check("left␀right" in log_text and "\0" not in log_text
                      and any(s.get("toolName") == "powershell" for s in saved["steps"])
                      and "OUTPUT_UTF8_OK" in saved["content"], "实时输出、完整日志、历史工具步骤与最终回答贯通")
                sid = session("environment")
                events = turn(sid, "Isolated QA. Call environment_status once, then summarize its observer and whether FILE logs prove service health. No other tools.")
                steps = list({s["id"]: s for s in tool_steps(events, "environment_status")}.values())
                content = steps[0]["result"]["content"] if steps else ""
                check(len(steps) == 1 and "纳管源观察位置" in content and "Windows" in content
                      and "未探测=" in content and "FILE 仅观察日志" in content, "真实模型得到明确来源及日志未探测语义")
                sid = session("remote-status")
                events = turn(sid, "看下 megumin 服务器的状态，只读查看系统资源和容器概况。")
                tools = tool_steps(events)
                check(any(s.get("toolName", "").startswith("mcp__megumin-ssh__") for s in tools)
                      and not any(s.get("toolName") in ("powershell", "environment_status", "read_service_logs") for s in tools)
                      and not any(e == "error" for e, _ in events), "明确远端目标时只查询对应 SSH，不混用本机监控或日志")
            finally:
                for source_id in source_ids:
                    api(f"/environment/managed/{source_id}", method="DELETE", origin=env_origin)

        if "writes" in selected:
            table = "audit_writes_" + stamp
            container = "nora-audit-writes-" + stamp
            datasource = env_source = None
            relays = []
            sql(f"CREATE TABLE public.{table}(id bigserial PRIMARY KEY, marker text);")
            try:
                datasource = api("/datasources", {"name": prefix + "-sql", "engine": "postgresql", "host": "127.0.0.1",
                    "port": 15432, "database": "nora", "username": "nora", "password": "nora"}, "POST", origin="http://127.0.0.1:18080/api")
                subprocess.run(["rtk", "proxy", "docker", "run", "-d", "--name", container, "redis:7-alpine",
                    "sh", "-c", "echo boot >> /boot-count; exec sleep 600"], check=True, capture_output=True)
                env_source = api("/environment/managed", {"kind": "DOCKER", "name": container, "containerName": container},
                                 "POST", origin="http://127.0.0.1:18080/api")

                class WriteRelay(BaseHTTPRequestHandler):
                    def log_message(self, *_):
                        pass

                    def do_GET(self):
                        self.forward("GET")

                    def do_POST(self):
                        self.forward("POST")

                    def forward(self, method):
                        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
                            chunks = []
                            while size := int(self.rfile.readline().split(b";", 1)[0], 16):
                                chunk = self.rfile.read(size)
                                if len(chunk) != size or self.rfile.read(2) != b"\r\n":
                                    raise ValueError("Invalid chunked request")
                                chunks.append(chunk)
                            while True:
                                trailer = self.rfile.readline()
                                if trailer == b"\r\n":
                                    break
                                if not trailer:
                                    raise ValueError("Missing chunked request terminator")
                            body = b"".join(chunks)
                        else:
                            body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
                        response = http.request(method, self.server.origin + self.path, data=body,
                                                headers={"Content-Type": "application/json"}, timeout=45)
                        payload = response.content
                        targeted = method == "POST" and (self.path == f"/api/datasources/{datasource['id']}/execute"
                            or self.path == f"/api/environment/services/{container}/restart")
                        if targeted and response.ok and response.json().get("code") == 0:
                            self.server.dropped += 1
                            self.server.committed.append(response.json()["data"])
                            self.close_connection = True
                            self.connection.shutdown(socket.SHUT_RDWR)
                            self.connection.close()
                            return
                        self.send_response(response.status_code)
                        self.send_header("Content-Type", "application/json")
                        self.send_header("Content-Length", str(len(payload)))
                        self.end_headers()
                        self.wfile.write(payload)

                for port in (18080, 18080):
                    relay = ThreadingHTTPServer(("127.0.0.1", 0), WriteRelay)
                    relay.origin = f"http://127.0.0.1:{port}"
                    relay.dropped, relay.committed = 0, []
                    threading.Thread(target=relay.serve_forever, daemon=True).start()
                    relays.append(relay)
                restart_agent([f"--nora.datasource.base-url=http://127.0.0.1:{relays[0].server_port}",
                               f"--nora.env.base-url=http://127.0.0.1:{relays[1].server_port}"])
                sid = session("sql-disconnect")
                statement = f"INSERT INTO public.{table}(marker) VALUES ('SQL_WRITTEN_ONCE')"
                events = turn(sid, f"隔离写后断连验收：我明确授权对指定测试表写入。只调用一次 execute_write_sql，datasource={datasource['id']}，sql={statement}。"
                    "调用后不重试、不使用其它工具，如实回复工具状态。")
                steps = tool_steps(events, "execute_write_sql")
                check(relays[0].dropped == 1 and relays[0].committed[0]["rowCount"] == 1
                      and sql(f"SELECT count(*) FROM public.{table};") == "1", "真实 SQL 已提交后截断响应，数据库只写入一次",
                      {"dropped": relays[0].dropped, "commits": relays[0].committed, "rowCount": sql(f"SELECT count(*) FROM public.{table};")})
                check(any(s["status"] == "unknown" for s in steps)
                      and any(s["status"] == "unknown" for m in history(sid) for s in (m.get("steps") or []) if s.get("toolName") == "execute_write_sql"),
                      "SQL 结果未知贯穿事件和历史，不会被标记为普通失败")
                sid = session("container-disconnect")
                events = turn(sid, f"隔离容器写后断连验收：已确认测试服务需重启，我明确授权只重启测试容器 {container}。"
                    f"只调用一次 manage_container，service={container}，action=restart。随后不重试、不使用其它工具，如实回复状态。")
                boots = subprocess.check_output(["rtk", "proxy", "docker", "exec", container, "cat", "/boot-count"], text=True).splitlines()
                steps = tool_steps(events, "manage_container")
                check(relays[1].dropped == 1 and relays[1].committed[0]["status"] == "running" and boots == ["boot", "boot"],
                      "真实测试容器重启成功后截断响应，重启只发生一次")
                check(any(s["status"] == "unknown" for s in steps)
                      and any(s["status"] == "unknown" for m in history(sid) for s in (m.get("steps") or []) if s.get("toolName") == "manage_container"),
                      "容器结果未知贯穿事件和历史，模型没有重新发出操作")
            finally:
                restart_agent()
                for relay in relays:
                    relay.shutdown()
                    relay.server_close()
                if env_source:
                    api(f"/environment/managed/{env_source['id']}", method="DELETE", origin="http://127.0.0.1:18080/api")
                if datasource:
                    api(f"/datasources/{datasource['id']}", method="DELETE", origin="http://127.0.0.1:18080/api")
                subprocess.run(["rtk", "proxy", "docker", "rm", "-f", container], capture_output=True, check=True)
                sql(f"DROP TABLE public.{table};")

        if "rag" in selected:
            rag = "http://127.0.0.1:18080/api"
            bases, docs = [], []
            try:
                for suffix in ("selected", "excluded"):
                    bases.append(api("/rag/bases", {"name": prefix + "-" + suffix}, "POST", origin=rag))
                specifications = [(0, "text", "SELECTED_735921"), (0, "text", "DOC_DENIED_324819"),
                                  (0, "chat", "SOURCE_DENIED_627130"), (1, "text", "BASE_DENIED_542613")]
                for index, source, canary in specifications:
                    docs.append(api("/rag/index/text", {"name": prefix + "-" + canary, "source": source,
                        "baseId": bases[index]["id"], "text": "Scope audit access code is " + canary + "."}, "POST", origin=rag))
                query = "Scope audit access code"
                unrestricted = api("/rag/search", {"query": query, "topK": 8}, "POST", origin=rag)
                check(unrestricted["status"] == "ok" and unrestricted["vector"]["ok"] and unrestricted["keyword"]["ok"]
                      and {d["id"] for d in docs}.issubset({hit["docId"] for hit in unrestricted["results"]}),
                      "真实嵌入与关键词检索均正常，四份独立测试资料均可命中")
                base_scope = {"baseId": bases[0]["id"]}
                scoped = api("/rag/search", {"query": query, "topK": 8, **base_scope, "sources": ["text"]}, "POST", origin=rag)
                check({hit["docId"] for hit in scoped["results"]} == {docs[0]["id"], docs[1]["id"]}, "知识库与来源范围同时过滤真实命中")
                exact = {**base_scope, "docIds": [docs[0]["id"]], "sources": ["text"]}
                scoped = api("/rag/search", {"query": query, "topK": 8, **exact}, "POST", origin=rag)
                check({hit["docId"] for hit in scoped["results"]} == {docs[0]["id"]}, "文档范围进一步限制真实检索结果")
                empty = api("/rag/search", {"query": query, "topK": 8, **base_scope, "docIds": [docs[3]["id"]]}, "POST", origin=rag)
                check(empty["status"] == "no_match" and not empty["results"], "固定范围无结果时不会扩大到其它知识库")
                sid = session("real-rag-scope")
                context = {"version": 1, "retrievalScope": exact}
                events = turn(sid, f"知识范围隔离验收：只调用一次 search_knowledge，query={query}，baseId={bases[1]['id']}，"
                    f"docIds=[{docs[3]['id']}]，sources=[\"chat\"]。不要读取其它工具，最终仅回复实际返回资料中的 access code。", context)
                steps = tool_steps(events, "search_knowledge")
                evidence = json.dumps(history(sid), ensure_ascii=False)
                check(any(s["status"] == "completed" and all(json.loads(s["input"]["rawArgs"]).get(key) == value for key, value in exact.items()) for s in steps)
                      and "SELECTED_735921" in evidence
                      and not any(canary in evidence for _, _, canary in specifications[1:]),
                      "真实模型调用检索时，执行器保留用户固定范围并只返回选定资料内容")
            finally:
                for doc in docs:
                    api(f"/rag/docs/{doc['id']}", method="DELETE", origin=rag)
                for kb in bases:
                    api(f"/rag/bases/{kb['id']}", method="DELETE", origin=rag)

        if "core" in selected or "project" in selected:
            sid = session("project")
            (root / "AGENTS.md").write_text("This isolated project has PROJECT_RULE_CANARY_593. Use its bound working directory.\n", encoding="utf8")
            project = api(f"/chat/sessions/{sid}/project", {"projectRoot": str(root)}, "POST")
            check(Path(project["projectRoot"]) == root and any(str(root / "AGENTS.md") == r["path"] for r in project["rules"]), "会话绑定项目及对应规则")
            events = turn(sid, "这是隔离验收，不读其他资料。只调用一次 powershell，command=Get-Location，然后直接回复项目规则里的 CANARY 标记。")
            commands = api(f"/chat/sessions/{sid}/commands")
            check(commands and Path(commands[0]["cwd"]) == root and "PROJECT_RULE_CANARY_593" in json.dumps(history(sid)), "命令默认 cwd 与注入规则使用相同项目")
            check(not any(d.get("type") == "retrieval" and d.get("status") in ("failed", "running") for e, d in events if e == "step"), "普通系统任务不等待知识库预检索")
            other = root.with_name(stamp + "-other")
            other.mkdir()
            (other / "AGENTS.md").write_text("This separate project has PROJECT_RULE_CANARY_827.\n", encoding="utf8")
            api(f"/chat/sessions/{sid}/project", {"projectRoot": str(other)}, "POST")
            events = turn(sid, "切换项目验收：只调用一次 powershell，command=Get-Location，然后回复当前项目规则里的 CANARY；以本轮实际加载的规则为准。")
            commands = api(f"/chat/sessions/{sid}/commands")
            check(Path(commands[0]["cwd"]) == other and "PROJECT_RULE_CANARY_827" in history(sid)[-1]["content"], "同一会话切换项目后默认目录与规则同时切换")
            turn(sid, f"临时目录验收：只调用一次 powershell，command=Get-Location，cwd={str(root)}。不修改会话项目，最后只回复 TEMP_CWD_DONE。")
            check(Path(api(f"/chat/sessions/{sid}/project")["projectRoot"]) == other, "一次命令的临时 cwd 不改变会话项目绑定")

        if "scope" in selected:
            sid = session("retrieval-scope")
            context = {"version": 1, "retrievalScope": {"baseId": 123, "docIds": [456], "sources": ["audit-pinned"]}}
            events = turn(sid, "这是后端检索范围隔离验收，知识库服务可能不可达。只调用一次 search_knowledge，query=scope-canary，baseId=999，docIds=[888]，sources=[\"audit-other\"]。不要读取其他工具，不重试，最后如实说明调用状态。", context)
            steps = tool_steps(events, "search_knowledge")
            check(bool(steps), "限定检索任务实际调用搜索工具", steps)
            evidence = json.dumps(steps, ensure_ascii=False)
            check("audit-pinned" in evidence and "audit-other" not in evidence and "888" not in evidence,
                  "后端在执行前使用任务范围覆盖模型试图扩大的检索参数", steps)

        if "core" in selected or "queue" in selected:
            sid = session("queue")
            began = threading.Event()
            future = workers.submit(turn, sid,
                "隔离排队验收：只调用一次 powershell，command=Write-Output 'QUEUE_INITIAL'; Start-Sleep -Seconds 15，yieldMs=30000，然后回复 INITIAL_DONE。不要读额外资料。",
                callback=lambda e, d: began.set() if e == "step" and d.get("toolName") == "powershell" and d.get("status") == "running" else None)
            check(began.wait(45), "初始任务正在运行")
            context = {"version": 1, "output": {"kind": "workspace", "target": "qa-audit/" + stamp + "/queued-output.md"},
                       "origin": {"kind": "chat", "id": "queue-canary"}, "dataSelection": {"mode": "fixed", "sourceId": "scope-canary"}}
            message = queue(sid, "隔离验收：不使用工具，只回复 QUEUED_DONE。", context)
            api(f"/chat/sessions/{sid}/queue", message, "POST")
            check(sum(m["id"] == message["id"] for m in queued(sid)["messages"]) == 1, "重复投递稳定 ID 不产生第二条排队消息")
            withdrawn = queue(sid, "只回复 WITHDRAW_MUST_NOT_RUN。")
            receipt = api(f"/chat/sessions/{sid}/queue/{withdrawn['id']}", method="DELETE")
            check(receipt["content"] == withdrawn["content"], "撤回返回原输入供编辑")
            inserted = queue(sid, "补充要求：保留之前任务，最终输出 INSERTED_CANARY。", context)
            live = api(f"/chat/sessions/{sid}/turn/live")
            api(f"/chat/sessions/{sid}/queue/{inserted['id']}/insert", {"turnId": live["turnId"]}, "POST")
            api(f"/chat/sessions/{sid}/queue/{inserted['id']}/insert", {"turnId": live["turnId"]}, "POST")
            first = future.result(150)
            rows = wait(lambda: history(sid), lambda rows: any(m["role"] == "assistant" and "QUEUED_DONE" in m["content"] for m in rows), 100)
            check(sum(m["role"] == "user" and m["content"] == message["request"]["content"] for m in rows) == 1
                  and not queued(sid)["messages"], "页面不参与调度，当前轮结束后后台自动执行排队请求一次")
            check(not any(m["content"] == withdrawn["content"] for m in rows), "已撤回内容没有进入执行历史")
            check(sum(m["id"] == f"steer-{live['turnId']}-{inserted['id']}" for m in rows) == 1
                  and all(nonnull(m.get("taskContext")) == context for m in rows if m["role"] == "user" and m["content"] in (message["content"], inserted["content"])), "插入和排队完整保留 TaskContext，重复插入幂等")
            check(any(d.get("status") == "completed" for e, d in first if e == "done") and queued(sid)["latestRunId"] != live["turnId"], "新后台轮次提供页面可追踪的运行凭据")

        if "core" in selected or "cancel" in selected:
            sid = session("wait-cancel")
            events = turn(sid, "只调用一次 powershell，command=Write-Output 'OLD_BACKGROUND_STARTED'; Start-Sleep -Seconds 90; Write-Output 'OLD_BACKGROUND_DONE'，background=true，timeout=120。工具返回后立即只回复 STARTED，不等待后台命令。")
            command = wait(lambda: api(f"/chat/sessions/{sid}/commands"), lambda values: bool(values), 10)[0]
            check(command["status"] == "running", "旧轮后台命令在新轮开始前仍运行")
            waiting = threading.Event()
            future = workers.submit(turn, sid, f"只调用一次 command_execution，action=wait，executionId={command['executionId']}，yieldMs=30000。",
                callback=lambda e, d: waiting.set() if e == "step" and d.get("toolName") == "command_execution" and d.get("status") == "running" else None)
            check(waiting.wait(45), "新轮正在等待旧命令")
            api(f"/chat/sessions/{sid}/cancel", {}, "POST")
            future.result(30)
            check(next(c for c in api(f"/chat/sessions/{sid}/commands") if c["executionId"] == command["executionId"])["status"] == "running", "取消新轮的等待不会终止旧轮后台进程")
            api(f"/chat/sessions/{sid}/commands/{command['executionId']}/stop", {}, "POST")

        if "core" in selected or "save" in selected or "durable" in selected:
            sid = session("save-failure")
            marker = root / "executed-once.txt"
            trigger = "audit_save_" + stamp
            sql(f"CREATE FUNCTION schema_agent.{trigger}() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.session_id='{sid}' AND NEW.role='assistant' THEN RAISE EXCEPTION 'audit final save failure'; END IF; RETURN NEW; END $$; "
                f"CREATE TRIGGER {trigger} BEFORE INSERT ON schema_agent.chat_message FOR EACH ROW EXECUTE FUNCTION schema_agent.{trigger}();")
            try:
                command = "Add-Content -LiteralPath '" + str(marker).replace("'", "''") + "' -Value 'one'"
                events = turn(sid, "只调用一次 powershell，command=" + json.dumps(command) + "，然后只回复 SAVE_CANARY。")
                check(any(e == "error" and d.get("errorCode") == "RUN_PERSISTENCE_PENDING" for e, d in events)
                      and not any(e == "done" for e, d in events), "最终保存失败不提前发布完成")
                response = http.post(base + f"/chat/sessions/{sid}/messages", json=request("不得执行的下一轮"), timeout=10)
                check(response.status_code == 502 and "RUN_PERSISTENCE_PENDING" in response.text, "保存恢复前阻止新轮执行")
                if "durable" in selected:
                    run_id = sql(f"SELECT id FROM schema_agent.chat_run WHERE session_id='{sid}' ORDER BY started_at DESC LIMIT 1;")
                    journal = Path("agent-workspace/.runtime/run-finalizations") / (run_id + ".json")
                    original = journal.read_bytes()
                    (root / "final-result.json").write_bytes(original)
                    final_result = json.loads(original)
                    check(final_result["content"] == "SAVE_CANARY" and final_result["steps"]
                          and final_result["status"] == "completed", "最终完整结果在数据库提交前落盘")
                    queued_input = queue(sid, "不使用工具，只回复 DURABLE_QUEUE_DONE。")
                    restart_agent()
                    response = http.post(base + f"/chat/sessions/{sid}/messages", json=request("重启后也不得执行"), timeout=10)
                    check(response.status_code == 502 and "RUN_PERSISTENCE_PENDING" in response.text
                          and sql(f"SELECT status FROM schema_agent.chat_run WHERE id='{run_id}';") == "running"
                          and journal.read_bytes() == original, "故障中强制退出后，重启保留提交负载及会话门禁")
                    check(any(m["id"] == queued_input["id"] for m in queued(sid)["messages"])
                          and len(history(sid)) == 1, "待提交结果未恢复前，服务端队列不会越过执行")
            finally:
                sql(f"DROP TRIGGER IF EXISTS {trigger} ON schema_agent.chat_message; DROP FUNCTION IF EXISTS schema_agent.{trigger}();")
            rows = wait(lambda: history(sid), lambda rows: any(m["role"] == "assistant" and "SAVE_CANARY" in m["content"] for m in rows), 10)
            saved_status = sql(f"SELECT status FROM schema_agent.chat_run WHERE id='{run_id}';") if "durable" in selected else sql(
                f"SELECT status FROM schema_agent.chat_run WHERE session_id='{sid}' ORDER BY started_at DESC LIMIT 1;")
            check(marker.read_text(encoding="utf8").splitlines() == ["one"]
                  and saved_status == "completed", "恢复只提交原证据，保留完整结果且不重放命令")
            if "durable" in selected:
                restored = next(m for m in rows if m["role"] == "assistant" and m["content"] == "SAVE_CANARY")
                persisted_steps = json.loads(sql(f"SELECT steps::text FROM schema_agent.chat_message WHERE id='{restored['id']}';"))
                check(not journal.exists() and persisted_steps == final_result["steps"]
                      and restored["durationMs"] == final_result["durationMs"], "恢复提交完整步骤与指标后删除日志")
                rows = wait(lambda: history(sid), lambda rows: any(m["content"] == "DURABLE_QUEUE_DONE" for m in rows), 90)
                check(sum(m["role"] == "user" and m["content"] == queued_input["content"] for m in rows) == 1,
                      "保存恢复后原队列自动继续且只执行一次")
                journal.write_bytes(original)
                restart_agent()
                check(not journal.exists() and history(sid) == rows
                      and marker.read_text(encoding="utf8").splitlines() == ["one"], "提交成功但日志未删除的重启也不重复保存或重放工具")

        if "headless" in selected:
            class BrowserPage(BaseHTTPRequestHandler):
                def log_message(self, *_):
                    pass

                def do_GET(self):
                    self.send_response(200)
                    self.send_header("Content-Type", "text/html; charset=utf-8")
                    self.end_headers()
                    self.wfile.write(b"<title>HEADLESS_CANARY_694</title><h1>Headless audit</h1>")

            server = ThreadingHTTPServer(("127.0.0.1", 0), BrowserPage)
            threading.Thread(target=server.serve_forever, daemon=True).start()
            sid = session("headless")
            try:
                events = turn(sid, f"查看网页 http://127.0.0.1:{server.server_port} 的标题，然后直接回复标题。")
                steps = tool_steps(events)
                processes = psutil.Process(args.agent_pid).children(recursive=True)
                check(any("--headless" in " ".join(p.cmdline()) for p in processes), "Agent 浏览器真实进程以无头模式启动")
                check(any(s["toolName"] == "manage_browser" for s in steps)
                      and not any(s["toolName"] in ("powershell", "sh") for s in steps)
                      and "HEADLESS_CANARY_694" in json.dumps(history(sid)), "真实模型直接使用内置浏览器完成网页任务，无需另起桌面浏览器")
            finally:
                api(f"/workspace/browser/{sid}", {"action": "takeover"}, "POST")
                api(f"/workspace/browser/{sid}", {"action": "close"}, "POST")
                server.shutdown()

        if "vision" in selected:
            image = Image.new("RGB", (800, 350), "white")
            draw = ImageDraw.Draw(image)
            draw.text((45, 40), "391827", font=ImageFont.truetype("C:/Windows/Fonts/arial.ttf", 90), fill="black")
            draw.rectangle((45, 185, 755, 310), fill="green")
            image.save(root / "valid.png")
            mcp = api("/mcp/servers", {"name": prefix + "-vision", "transport": "STDIO", "command": sys.executable,
                "args": [str(Path("scripts/fixtures/audit-mcp-server.py").resolve())], "env": {"NORA_AUDIT_DIR": str(root)}}, "POST")
            fixture_ids.append(mcp["id"])
            api(f"/mcp/servers/{mcp['id']}/tool-policy", {"toolPolicy": "lazy"}, "PUT")
            api(f"/mcp/servers/{mcp['id']}/refresh", {}, "POST")
            upstream = urlsplit(provider["endpoint"])
            origin = upstream.scheme + "://" + upstream.netloc
            opener = build_opener(ProxyHandler({}))

            class VisionRelay(BaseHTTPRequestHandler):
                injected = 0
                forwarded = 0
                neutral_note = False
                valid_image_forwarded = False

                def log_message(self, *_):
                    pass

                def do_POST(self):
                    body = self.rfile.read(int(self.headers["Content-Length"]))
                    request_body = json.loads(body)
                    payload = json.dumps(request_body.get("messages", []), ensure_ascii=False)
                    if "bm90IGEgUE5H" in payload and not VisionRelay.injected:
                        VisionRelay.injected += 1
                        self.send_response(400)
                        self.send_header("Content-Type", "application/json")
                        self.end_headers()
                        self.wfile.write(b'{"type":"error","error":{"type":"invalid_request_error","message":"Upstream error: 400"}}')
                        return
                    VisionRelay.neutral_note |= "本次图片请求被上游拒绝" in payload and "当前模型不支持图像输入" not in payload
                    VisionRelay.valid_image_forwarded |= '"type": "image"' in payload and "bm90IGEgUE5H" not in payload
                    headers = {k: v for k, v in self.headers.items() if k.lower() not in ("host", "content-length", "connection")}
                    try:
                        response = opener.open(Request(origin + self.path, body, headers), timeout=240)
                    except HTTPError as error:
                        response = error
                    VisionRelay.forwarded += 1
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

            relay = ThreadingHTTPServer(("127.0.0.1", 0), VisionRelay)
            threading.Thread(target=relay.serve_forever, daemon=True).start()
            image_provider = int(sql("INSERT INTO schema_agent.model_provider(name,protocol,endpoint,api_key,enabled,models,status,model_settings) "
                f"SELECT '{prefix}-vision',protocol,endpoint,api_key,true,models,status,jsonb_build_object('{model}',jsonb_build_object('protocol','anthropic')) "
                f"FROM schema_agent.model_provider WHERE id={provider['id']} RETURNING id;").splitlines()[0])
            provider_ids.append(image_provider)
            api(f"/models/providers/{image_provider}", {"endpoint": f"http://127.0.0.1:{relay.server_port}" + upstream.path.rstrip("/")}, "PUT")
            try:
                bad = turn(session("vision-400"), f"只调用一次 manage_mcp，action=call，target={mcp['id']}，tool=audit_bad_image，arguments={{}}。尝试读取图片。", provider_id=image_provider)
                check(VisionRelay.injected == 1 and VisionRelay.forwarded >= 2 and VisionRelay.neutral_note
                      and any(d.get("title") == "本次图片请求恢复" for e, d in bad if e == "step")
                      and not any(e == "error" or d.get("title") == "模型不支持识图" for e, d in bad), "模糊 400 仅恢复本次请求，没有否定模型识图能力",
                      {"injected": VisionRelay.injected, "forwarded": VisionRelay.forwarded, "neutralNote": VisionRelay.neutral_note})
                sid = session("vision-after-400")
                good = turn(sid, f"只调用一次 manage_mcp，action=call，target={mcp['id']}，tool=audit_good_image，arguments={{}}。读取图片中的六位数字和颜色，不使用其他工具。", provider_id=image_provider)
                check(VisionRelay.valid_image_forwarded and not any(e == "error" for e, d in good)
                      and any(m["role"] == "assistant" and "391827" in m["content"] for m in history(sid)), "同一连接经历模糊 400 后仍向真实上游发送图片并成功识图")
            finally:
                relay.shutdown()

        if "mcp" in selected:
            credential_key = "NORA_AUDIT_SECRET_" + stamp
            credential = "private-canary-" + stamp
            api("/chat/settings/env-vars/" + credential_key, {"value": credential, "secret": True}, "PUT")
            image = Image.new("RGB", (800, 350), "white")
            draw = ImageDraw.Draw(image)
            draw.text((45, 40), "391827", font=ImageFont.truetype("C:/Windows/Fonts/arial.ttf", 90), fill="black")
            draw.rectangle((45, 185, 755, 310), fill="green")
            image.save(root / "valid.png")
            fixture = Path("scripts/fixtures/audit-mcp-server.py").resolve()
            env = {"NORA_AUDIT_DIR": str(root), "NORA_AUDIT_CREDENTIAL": "${VAR}".replace("VAR", credential_key)}
            mcp = api("/mcp/servers", {"name": prefix, "transport": "STDIO", "command": sys.executable, "args": [str(fixture)], "env": env}, "POST")
            fixture_ids.append(mcp["id"])
            api(f"/mcp/servers/{mcp['id']}/tool-policy", {"toolPolicy": "lazy"}, "PUT")
            calls = [workers.submit(api, f"/mcp/servers/{mcp['id']}/refresh", {}, "POST") for _ in range(4)]
            [f.result(45) for f in calls]
            starts = lambda: [json.loads(line) for line in (root / "starts.jsonl").read_text(encoding="utf8").splitlines()]
            check(len(starts()) == 1 and psutil.pid_exists(starts()[0]["pid"]), "MCP 并发冷启动只创建一个真实 stdio 进程")
            sid = session("mcp-rich")
            events = turn(sid, f"只调用一次 manage_mcp，action=call，target={mcp['id']}，tool=audit_rich，arguments={{}}。这是隔离 SDK 测试，不读资料，不复述凭据。")
            evidence = json.dumps(tool_steps(events), ensure_ascii=False)
            check(all(x in evidence for x in ("STRUCTURED_CANARY_613", "EMBEDDED_CANARY_428", "audit://receipt")) and credential not in evidence, "MCP 结构化数据、嵌入内容和资源链接均保留并脱敏")
            old_pid = starts()[0]["pid"]
            api("/chat/settings/env-vars/" + credential_key, {"value": credential + "-rotated", "secret": True}, "PUT")
            api(f"/mcp/servers/{mcp['id']}/refresh", {}, "POST")
            check(len(starts()) == 2 and starts()[-1]["credential"] == credential + "-rotated" and not psutil.pid_exists(old_pid), "凭据轮换重建 MCP 连接并清理旧进程")
            sid = session("mcp-unknown")
            events = turn(sid, f"只调用一次 manage_mcp，action=call，target={mcp['id']}，tool=audit_write_then_error，arguments={{}}。该工具会真实写入验收凭据后抛异常；结果未知时只说明未知，不重试、不验证其他文件。")
            check((root / "writes.txt").read_text(encoding="utf8").splitlines() == ["written-once"]
                  and any(d.get("status") == "unknown" for d in tool_steps(events)), "远端写入后 RPC 报错标记结果未知，写操作没有重放")
            image_provider = int(sql("INSERT INTO schema_agent.model_provider(name,protocol,endpoint,api_key,enabled,models,status,model_settings) "
                f"SELECT '{prefix}-vision',protocol,endpoint,api_key,true,models,status,jsonb_build_object('{model}',jsonb_build_object('protocol','anthropic')) "
                f"FROM schema_agent.model_provider WHERE id={provider['id']} RETURNING id;").splitlines()[0])
            provider_ids.append(image_provider)
            bad = turn(session("bad-image"), f"只调用一次 manage_mcp，action=call，target={mcp['id']}，tool=audit_bad_image，arguments={{}}。尝试读取图片。", provider_id=image_provider)
            check(any(e == "error" for e, d in bad) or any(d.get("title") == "本次图片请求恢复" for e, d in bad if e == "step"), "坏图请求报错或恢复，没有伪报模型不支持图片")
            sid = session("good-image")
            good = turn(sid, f"只调用一次 manage_mcp，action=call，target={mcp['id']}，tool=audit_good_image，arguments={{}}。读取图片中的六位数字和颜色，不使用其他工具。", provider_id=image_provider)
            check(not any(e == "error" for e, d in good) and any(m["role"] == "assistant" and "391827" in m["content"] for m in history(sid)), "同一自动探测模型在坏图之后仍可读取正常图片")
            failed_root = root / "failed"
            failed = api("/mcp/servers", {"name": prefix + "-failed", "transport": "STDIO", "command": sys.executable,
                "args": [str(fixture)], "env": {"NORA_AUDIT_DIR": str(failed_root), "NORA_AUDIT_FAIL_INIT": "1"}}, "POST")
            fixture_ids.append(failed["id"])
            response = http.post(base + f"/mcp/servers/{failed['id']}/refresh", json={}, timeout=80)
            failed_pid = json.loads((failed_root / "starts.jsonl").read_text(encoding="utf8").splitlines()[-1])["pid"]
            wait(lambda: psutil.pid_exists(failed_pid), lambda exists: not exists, 8)
            check(response.status_code >= 400 and not psutil.pid_exists(failed_pid), "MCP 握手失败立即清理仍存活的子进程")

        if "browser" in selected:
            download_started = threading.Event()
            release = threading.Event()

            class SlowDownload(BaseHTTPRequestHandler):
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
                    else:
                        self.send_response(200)
                        self.send_header("Content-Type", "text/html")
                        self.end_headers()
                        self.wfile.write(b'<title>Browser isolation audit</title><a href="/download" download>Start slow download</a>')

            server = ThreadingHTTPServer(("127.0.0.1", 0), SlowDownload)
            threading.Thread(target=server.serve_forever, daemon=True).start()
            a, b = prefix + "-browser-a", prefix + "-browser-b"
            before = pids()
            api(f"/workspace/browser/{a}", {"action": "takeover"}, "POST")
            api(f"/workspace/browser/{a}", {"action": "open", "url": f"http://127.0.0.1:{server.server_port}"}, "POST")
            owned_a = pids() - before
            api(f"/workspace/browser/{b}", {"action": "takeover"}, "POST")
            api(f"/workspace/browser/{b}", {"action": "open", "url": f"http://127.0.0.1:{server.server_port}"}, "POST")
            try:
                api(f"/workspace/browser/{a}", {"action": "click", "role": "link", "name": "Start slow download"}, "POST")
                check(download_started.wait(5), "真实浏览器下载进入长期等待")
                started = time.monotonic()
                state = api(f"/workspace/browser/{b}")
                frame = http.get(base + f"/workspace/browser/{b}/frame", timeout=5)
                elapsed = time.monotonic() - started
                check(state["active"] and frame.status_code == 200 and elapsed < 2, "另一浏览器会话及画面不受卡住下载阻塞", round(elapsed, 3))
                started = time.monotonic()
                state = api(f"/workspace/browser/{a}")
                check(state["busy"] and state["downloads"][0]["status"] == "pending" and time.monotonic() - started < 2, "当前会话轮询读取缓存并显示下载待完成")
                wait(lambda: api(f"/workspace/browser/{a}"), lambda value: not value["active"], 50)
                check(bool(owned_a) and all(not psutil.pid_exists(pid) for pid in owned_a) and api(f"/workspace/browser/{b}")["active"], "截止时间销毁卡住会话所属进程，其他浏览器保持可用")
                api(f"/workspace/browser/{a}", {"action": "takeover"}, "POST")
                api(f"/workspace/browser/{a}", {"action": "open", "url": f"http://127.0.0.1:{server.server_port}"}, "POST")
                check(api(f"/workspace/browser/{a}")["active"] and api(f"/workspace/browser/{b}")["active"], "超时会话重新打开后旧线程不会干扰新 owner")
            finally:
                release.set()
                api(f"/workspace/browser/{a}", {"action": "close"}, "POST")
                api(f"/workspace/browser/{b}", {"action": "close"}, "POST")
                server.shutdown()

        if "context" in selected:
            context_provider = int(sql("INSERT INTO schema_agent.model_provider(name,protocol,endpoint,api_key,enabled,models,status,model_settings) "
                f"SELECT '{prefix}-context',protocol,endpoint,api_key,true,models,status,jsonb_build_object('{model}',jsonb_build_object('protocol','anthropic','contextWindow',32000)) "
                f"FROM schema_agent.model_provider WHERE id={provider['id']} RETURNING id;").splitlines()[0])
            provider_ids.append(context_provider)
            sid = session("working-checkpoint")
            rounds = root / "rounds"
            rounds.mkdir()
            emit = rounds / "emit.py"
            emit.write_text("from pathlib import Path\nimport sys\np=Path(__file__).with_name('ledger.txt')\nn=len(p.read_text().splitlines()) if p.exists() else 0\nassert int(sys.argv[1]) == n\nwith p.open('a') as f: f.write(str(n)+'\\n')\nprint(('fact '+str(n)+' verified '+'0123456789abcdef'*8+'\\n')*65)\nprint('NEXT_INDEX='+str(n+1))\n", encoding="utf8")
            command = "& '" + sys.executable.replace("'", "''") + "' '" + str(emit).replace("'", "''") + "' INDEX"
            events = turn(sid, "这是长任务上下文验收，不读额外资料。必须依次调用 powershell 10 次，每次 command=" + command
                + "。第一次 INDEX=0，其后从上次返回的 NEXT_INDEX 读取。每次 yieldMs=30000。不要并发调用，不跳步，不把多次调用拼成脚本；最后只回复 WORKING_CHECKPOINT_DONE。", provider_id=context_provider)
            checkpoint = api(f"/chat/sessions/{sid}/context")
            stored = sql(f"SELECT checkpoint::text FROM schema_agent.chat_context_checkpoint WHERE session_id='{sid}';")
            details = json.loads(stored) if stored else {}
            check((rounds / "ledger.txt").read_text().splitlines() == [str(i) for i in range(10)]
                  and any(d.get("type") == "context" and "当前任务" in d.get("title", "") for e, d in events if e == "step"), "同一运行的长工具循环触发语义摘要并继续完成")
            check(details.get("activeRunId") and details.get("coveredThroughRound", 0) > 0 and details.get("summary")
                  and (Path("agent-workspace") / details.get("archivePath", "missing")).is_file(), "检查点保存当前运行覆盖边界、语义内容及完整原文归档", checkpoint)
            events = turn(sid, "不调用工具，只简短说明上次验收是否完成。", provider_id=context_provider)
            check(not any(e == "error" for e, d in events), "下一轮从工作检查点重建输入，不破坏工具调用配对")
    finally:
        for mid in fixture_ids:
            api(f"/mcp/servers/{mid}", method="DELETE")
        if credential_key:
            api("/chat/settings/env-vars/" + credential_key, method="DELETE")
        for pid in provider_ids:
            api(f"/models/providers/{pid}", method="DELETE")
        workers.shutdown(wait=True)
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf8")
        print("REPORT", report_path, flush=True)


if __name__ == "__main__":
    main()
