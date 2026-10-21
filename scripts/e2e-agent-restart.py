"""真实模型与工具的重启验收；中转仅暂停请求，绝不替换模型响应。"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import subprocess
import sys
import threading
import time
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import ProxyHandler, Request, build_opener
import uuid


def main():
    base = "http://127.0.0.1:18080"
    stamp = str(time.time_ns())
    prefix = "restart-e2e-" + stamp
    model = "deepseek-v4.1-flash"
    opener = build_opener(ProxyHandler({}))
    report = Path("logs") / (prefix + ".json")
    fixture = Path("agent-workspace/restart-e2e") / stamp
    fixture.mkdir(parents=True)
    record = {"checks": [], "cases": {}}
    requests = []
    held, release = threading.Event(), threading.Event()

    def api(path, data=None, method=None):
        body = None if data is None else json.dumps(data, ensure_ascii=False).encode()
        with opener.open(Request(base + path, body, {"Content-Type": "application/json"}, method=method), timeout=30) as response:
            value = json.load(response)
            return value.get("data", value) if isinstance(value, dict) else value

    def sql(query):
        result = subprocess.run(["rtk", "proxy", "docker", "exec", "nora-postgres", "psql", "-U", "nora", "-d", "nora", "-At", "-c", query],
                                check=True, capture_output=True, text=True, encoding="utf8")
        return result.stdout.strip()

    def check(value, label):
        record["checks"].append({"label": label, "passed": bool(value)})
        report.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf8")
        print("PASS" if value else "FAIL", label, flush=True)
        assert value, label

    def wait_for(predicate, label, timeout=180):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(0.25)
        raise TimeoutError(label)

    def history(session):
        return api(f"/api/chat/sessions/{session}/messages")

    def restart():
        assert sql("SELECT count(*) FROM schema_agent.chat_run WHERE status IN ('queued','running','awaiting_approval','cancelling') "
                   f"AND session_id NOT LIKE '{prefix}%';") == "0", "有用户任务运行，不能中断服务"
        command = "$p=Get-NetTCPConnection -LocalPort 18080 -State Listen | Select-Object -First 1; "
        command += "$j=Get-CimInstance Win32_Process -Filter ('ProcessId = ' + $p.OwningProcess); "
        command += "if ($j.CommandLine -notlike '*nora-api-0.1.0-SNAPSHOT.jar*') {throw 'Unexpected port owner'}; Stop-Process -Id $p.OwningProcess -Force"
        subprocess.run(["rtk", "proxy", "powershell", "-NoProfile", "-Command", command], check=True, capture_output=True)
        release.set()
        subprocess.Popen(["rtk", "proxy", "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "scripts/backend.ps1", "-Action", "start"],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        def ready():
            try:
                return api("/actuator/health")["status"] == "UP"
            except OSError:
                return False
        wait_for(ready, "服务重新就绪", 90)
        print("RESTARTED", flush=True)

    source = next(p for p in api("/api/models/providers") if p["id"] == 7)
    upstream = urlsplit(source["endpoint"])
    origin = upstream.scheme + "://" + upstream.netloc

    class Relay(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            body = self.rfile.read(int(self.headers["Content-Length"]))
            payload = json.loads(body)
            requests.append(payload)
            text = body.decode()
            if not release.is_set() and prefix + "-completed" in text and '"tool_result"' in text and "PROJECT_CODE=" in text:
                held.set()
                release.wait(240)
                return  # 旧 JVM 已结束，暂停的请求也随之结束。
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
    provider = int(sql("INSERT INTO schema_agent.model_provider(name,protocol,endpoint,api_key,enabled,models,status,model_settings) "
                       f"SELECT '{prefix}',protocol,endpoint,api_key,true,models,status,model_settings "
                       "FROM schema_agent.model_provider WHERE id=7 AND deleted_at IS NULL RETURNING id;").splitlines()[0])
    api(f"/api/models/providers/{provider}", {"endpoint": f"http://127.0.0.1:{relay.server_port}" + upstream.path.rstrip("/")}, "PUT")
    notes = fixture / "requirements.md"
    notes.write_text("测试任务只允许一次发令，核实已执行结果时只能只读。", encoding="utf8")
    context = {"version": 1, "refs": [{"kind": "workspace", "id": notes.relative_to("agent-workspace").as_posix(), "label": "发令约定"}],
               "retrievalScope": {"docIds": [999999999]}}

    def run(label, prompt, permission="full", resume=False):
        session = prefix + "-" + label
        if not resume:
            sql(f"INSERT INTO schema_agent.chat_session(id,title,title_generated) VALUES ('{session}','重启验收 · {label}',true);")
        item = {"session": session, "steps": {}, "approvals": [], "errors": []}
        record["cases"][label + ("-resume" if resume else "")] = item

        def consume():
            payload = {"content": prompt + "\n任务标识：" + session, "model": model, "providerId": provider,
                       "reasoningLevel": "none", "permissionMode": permission, "context": context}
            try:
                with opener.open(Request(base + f"/api/chat/sessions/{session}/messages", json.dumps(payload, ensure_ascii=False).encode(),
                                         {"Content-Type": "application/json"}), timeout=300) as response:
                    event = ""
                    for raw in response:
                        line = raw.decode().strip()
                        if line.startswith("event:"):
                            event = line[6:].strip()
                        elif line.startswith("data:"):
                            data = json.loads(line[5:])
                            if event == "step":
                                item["steps"][data["id"]] = data
                            elif event == "approval_required":
                                item["approvals"].append(data)
                            elif event == "done":
                                item["done"] = data
                            elif event == "error":
                                item["errors"].append(data)
            except (OSError, TimeoutError) as error:
                item["connectionEnd"] = str(error)

        thread = threading.Thread(target=consume, daemon=True)
        thread.start()
        return item, thread

    def check_journal_failure():
        session = prefix + "-journal-failure"
        sql("CREATE FUNCTION schema_agent.restart_e2e_journal() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
            f"IF NEW.session_id='{session}' AND NEW.tool_name IS NOT NULL AND NEW.status='running' "
            "THEN RAISE EXCEPTION 'E2E journal unavailable'; END IF; RETURN NEW; END $$;")
        try:
            sql("CREATE TRIGGER restart_e2e_journal BEFORE INSERT ON schema_agent.agent_step "
                "FOR EACH ROW EXECUTE FUNCTION schema_agent.restart_e2e_journal();")
            path = (fixture / "journal-failure.txt").relative_to("agent-workspace").as_posix()
            item, thread = run("journal-failure", "只使用 manage_workspace 的 write 操作把文本 journal 写入工作区 " + path + "，不要列目录。")
            thread.join(180)
            check(not thread.is_alive() and item.get("done", {}).get("status") == "failed", "实际日志写入失败会终止整轮")
            check(not (Path("agent-workspace") / path).exists(), "执行开始记录保存失败时工具未产生写入")
        finally:
            sql("DROP TRIGGER IF EXISTS restart_e2e_journal ON schema_agent.agent_step;")
            sql("DROP FUNCTION schema_agent.restart_e2e_journal();")

    try:
        if "--journal-only" in sys.argv:
            check_journal_failure()
            return
        codes = {label: uuid.uuid4().hex for label in ["completed", "unknown"]}
        counters = {}
        for label, code in codes.items():
            counter = fixture / (label + "-count.txt")
            counters[label] = counter
            script = fixture / (label + ".py")
            script.write_text("from pathlib import Path\nimport os,time\n"
                              + f"p=Path({str(counter.resolve())!r})\np.write_text((p.read_text() if p.exists() else '')+'PROJECT_CODE={code}\\n')\n"
                              + (f"Path({str((fixture/'child.pid').resolve())!r}).write_text(str(os.getpid()))\ntime.sleep(240)\n" if label == "unknown" else "time.sleep(2)\n")
                              + f"print('PROJECT_CODE={code}',flush=True)\n", encoding="utf8")
        completed, complete_thread = run("completed", "这是一次性发令任务，原样用 run_command 运行一次 rtk proxy python "
            + (fixture / "completed.py").relative_to("agent-workspace").as_posix()
            + "。禁止重复运行、读取脚本或扫描无关目录。命令完成后，最终只输出 PROJECT_CODE 的编码。")
        wait_for(lambda: any(s.get("toolName") == "run_command" and s["status"] == "running" for s in completed["steps"].values()), "发令开始")
        live = api(f"/api/chat/sessions/{completed['session']}/turn/live")
        steering = "补充要求：最终输出 JSON，字段 code 和 scope，scope 必须为 updated；仍禁止重复发令。"
        api(f"/api/chat/sessions/{completed['session']}/steer", {"turnId": live["turnId"], "messageId": str(uuid.uuid4()), "content": steering})
        wait_for(held.is_set, "完成工具已落库，暂停下一次真实模型请求")
        unknown, unknown_thread = run("unknown", "这是一次性发令任务，原样用 run_command 运行一次 rtk proxy python "
            + (fixture / "unknown.py").relative_to("agent-workspace").as_posix()
            + "。输出凭据在工作区 " + counters["unknown"].relative_to("agent-workspace").as_posix()
            + "。禁止重复发令、读取脚本或扫描无关目录。最终只输出凭据里的编码。")
        wait_for(lambda: counters["unknown"].exists() and (fixture / "child.pid").exists(), "工具实际写入，但未返回")
        approval_path = (fixture / "approval.txt").relative_to("agent-workspace").as_posix()
        approval, approval_thread = run("approval", "只使用 manage_workspace 的 write 操作把文本 approved 写入工作区 "
                                        + approval_path + "，不要先列目录。完成后仅回复已写入。", "ask")
        wait_for(lambda: bool(approval["approvals"]), "真实模型工具等待审批")
        cancelled, cancelled_thread = run("cancelled", "只使用 manage_workspace 的 write 操作把文本 cancelled 写入工作区 "
                                          + (fixture / "cancelled.txt").relative_to("agent-workspace").as_posix() + "。", "ask")
        wait_for(lambda: bool(cancelled["approvals"]), "取消夹具等待审批")
        api(f"/api/chat/sessions/{cancelled['session']}/cancel", {}, "POST")
        cancelled_thread.join(15)
        cancelled_history = history(cancelled["session"])
        check(cancelled_history[-1]["runStatus"] == "cancelled", "真实取消请求留下 cancelled 终态")
        restart()
        for thread in [complete_thread, unknown_thread, approval_thread]:
            thread.join(5)
        # 已知夹具进程不再需要等待；只停它自己的 PID。
        child = int((fixture / "child.pid").read_text())
        subprocess.run(["rtk", "proxy", "powershell", "-NoProfile", "-Command", f"Stop-Process -Id {child} -Force -ErrorAction SilentlyContinue"], capture_output=True)
        recovered = {label: history(item["session"]) for label, item in [("completed", completed), ("unknown", unknown), ("approval", approval)]}
        record["recovered"] = recovered
        check(history(cancelled["session"]) == cancelled_history and not (fixture / "cancelled.txt").exists(), "用户停止后重启不续跑、不写文件")
        for label, messages in recovered.items():
            check(messages[-1]["runStatus"] == "interrupted", label + " 中断状态可从历史恢复")
            check(messages[-1]["taskContext"]["retrievalScope"]["docIds"] == [999999999], label + " 原检索范围保留")
            steps = [s for message in messages for s in (message.get("steps") or [])]
            check(len({s["id"] for s in steps}) == len(steps), label + " 步骤不重复")
            tools = [s for s in steps if s.get("toolName")]
            check(any(s["status"] == {"completed": "completed", "unknown": "unknown", "approval": "declined"}[label] for s in tools), label + " 执行状态准确")
        messages = recovered["completed"]
        index = next(i for i, m in enumerate(messages) if m["content"] == steering)
        check(messages[index-1]["role"] == "assistant" and messages[index+1]["role"] == "assistant", "已查看过程排在插入要求之前，恢复段排在之后")
        check(messages[index-1].get("runStatus") is None and messages[index+1]["runStatus"] == "interrupted", "运行终态仅标记最后一个助手分段")
        check(not (Path("agent-workspace") / approval_path).exists(), "旧审批未导致写入")
        try:
            api(f"/api/chat/approvals/{approval['approvals'][0]['approvalToken']}?sessionId={approval['session']}", {"approved": True})
            stale_rejected = False
        except HTTPError:
            stale_rejected = True
        check(stale_rejected, "旧审批凭证已失效")
        restart()
        check(all(history(item["session"]) == recovered[label] for label, item in [("completed", completed), ("unknown", unknown), ("approval", approval)]), "第二次重启不重复生成消息或执行工具")
        for label, counter in counters.items():
            item, thread = run(label, "继续因服务重启中断的原任务，遵循原要求和补充要求。复用已完成结果；结果未知先只读核实凭据，禁止重复发令。", resume=True)
            thread.join(240)
            check(not thread.is_alive() and item.get("done", {}).get("status") == "completed", label + " 真实模型继续完成")
            answer = history(item["session"])[-1]["content"]
            check(codes[label] in answer, label + " 沿用正确凭据")
            check(counter.read_text().splitlines() == ["PROJECT_CODE=" + codes[label]], label + " 实际发令仍仅一次")
            if label == "completed":
                check("updated" in answer, "续跑继续遵守重启前的补充要求")
            else:
                check(any(s.get("toolName") == "manage_workspace" and s.get("input", {}).get("target") == counters[label].relative_to("agent-workspace").as_posix()
                          for s in item["steps"].values()), "未知操作先只读核实真实凭据")
        paired = True
        for request in requests:
            pending = set()
            for message in request.get("messages", []):
                parts = message.get("content", [])
                for part in parts if isinstance(parts, list) else []:
                    if part.get("type") == "tool_use":
                        pending.add(part["id"])
                    elif part.get("type") == "tool_result":
                        paired &= part["tool_use_id"] in pending
                        pending.discard(part["tool_use_id"])
            paired &= not pending
        check(paired, "所有真实模型请求的工具调用和结果完整配对")
        check_journal_failure()
        print("BROWSER_SESSION", approval["session"], flush=True)
    finally:
        release.set()
        for session in {item["session"] for item in record["cases"].values()}:
            try:
                api(f"/api/chat/sessions/{session}/cancel", {}, "POST")
            except OSError:
                pass  # 仅清理本次验收任务；进程已结束时无在途任务可取消。
        sql(f"UPDATE schema_agent.model_provider SET enabled=false,deleted_at=now() WHERE id={provider};")
        relay.shutdown()
        relay.server_close()
        report.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf8")
        print("REPORT", report.resolve(), flush=True)


if __name__ == "__main__":
    main()
