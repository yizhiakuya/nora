"""Real single-application acceptance; requires a configured, enabled model provider.

Use an isolated database and file/workspace directories. No simulated LLM responses.
NORA_AUTH_TOKEN supplies the application's access token.
"""
import argparse
import json
import os
import subprocess
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request, build_opener

parser = argparse.ArgumentParser()
parser.add_argument("--base", default="http://127.0.0.1:18080")
parser.add_argument("--provider", type=int, required=True)
parser.add_argument("--model", required=True)
parser.add_argument("--case", choices=["all", "tools", "resources", "run", "automation", "annotated", "builtins", "catalog", "steering"], default="all")
args = parser.parse_args()
client = build_opener(ProxyHandler({}))
token = os.environ.get("NORA_AUTH_TOKEN", "")
prefix = "single-app-" + uuid.uuid4().hex[:10]
sessions, rules, connections, files = [], [], [], []
sources = []


def call(path, body=None, method=None, status=200, authorized=True, raw=False):
    headers = {"Content-Type": "application/json"}
    if token and authorized:
        headers["Authorization"] = "Bearer " + token
    payload = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
    request = Request(args.base + "/api" + path, payload, headers, method=method or ("POST" if body is not None else "GET"))
    try:
        response = client.open(request, timeout=180)
    except HTTPError as error:
        response = error
    with response:
        content = response.read()
        if response.status != status:
            raise RuntimeError(f"{path}: HTTP {response.status}; expected {status}: {content[:300]!r}")
    if raw:
        return content
    result = json.loads(content)
    return result["data"] if isinstance(result, dict) and result.get("code") == 0 and "data" in result else result


def payload(content):
    return {"content": content, "providerId": args.provider, "model": args.model,
            "reasoningLevel": "none", "permissionMode": "full"}


def session(suffix):
    sid = prefix + "-" + suffix
    sessions.append(sid)
    return sid


def stream(sid, content, events, context=None, mode="full", guided=True):
    body = payload(("需要的专用工具未声明时先 load_tools，再按声明执行下列任务；不必重复加载。\n" if guided else "") + content)
    body["permissionMode"] = mode
    if context is not None:
        body["context"] = context
    request = Request(args.base + f"/api/chat/sessions/{sid}/messages", json.dumps(body).encode(),
                      {"Content-Type": "application/json", "Authorization": "Bearer " + token})
    with client.open(request, timeout=180) as response:
        event = "message"
        for line in response:
            text = line.decode().strip()
            if text.startswith("event:"):
                event = text[6:].strip()
            elif text.startswith("data:"):
                events.append((event, json.loads(text[5:].strip())))
    return events


def wait(predicate, timeout=120):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        value = predicate()
        if value:
            return value
        time.sleep(.15)
    raise RuntimeError("Condition did not become true before timeout")


def history(sid):
    return call(f"/chat/sessions/{sid}/messages")


def catalog():
    """Natural task routing and real persisted results; no tool hints in the first prompt."""
    database = os.environ.get("NORA_E2E_DB_NAME", "")
    workspace = os.environ.get("NORA_E2E_WORKSPACE_ROOT", "")
    if not database.startswith("nora_e2e_") or not workspace:
        raise RuntimeError("Catalog acceptance requires an isolated database and workspace")
    from pathlib import Path
    marker = "CATALOG_LOG_" + uuid.uuid4().hex
    log_file = Path(workspace) / (prefix + ".log")
    log_file.write_text(marker + "\n", encoding="utf-8")
    source = call("/environment/managed", {"kind": "FILE", "name": prefix, "fileLogPath": str(log_file)})
    sources.append(source["id"])
    try:
        sid, events = session("catalog"), []
        stream(sid, "查看当前工作台已纳管服务的实时状态。只查询并简要汇报，不操作启停，不查数据库或执行终端命令。", events, guided=False)
        completed = [v for e, v in events if e == "step" and v.get("status") == "completed" and v.get("toolName")]
        names = [v["toolName"] for v in completed]
        if "load_tools" not in names or "manage_service" not in names or names.index("load_tools") > names.index("manage_service"):
            raise RuntimeError(f"Natural task did not load then use service tool: {names}")
        status = next(v for v in completed if v["toolName"] == "manage_service"
                      and json.loads(v["input"]["rawArgs"]).get("action") == "status")
        if prefix not in status["result"]["content"] or "观察位置" not in status["result"]["content"]:
            raise RuntimeError("Service status lost real source or observer evidence")
        events = []
        stream(sid, f"接着读取纳管源 {prefix} 的最近两行日志，并返回其中的标记。不重新查询状态，也不要改动文件。", events, guided=False)
        completed = [v for e, v in events if e == "step" and v.get("status") == "completed" and v.get("toolName")]
        if any(v["toolName"] == "load_tools" for v in completed):
            raise RuntimeError("Retained tool history failed to restore loaded schema")
        if not any(v["toolName"] == "manage_service" and marker in v["result"]["content"] for v in completed):
            raise RuntimeError("Service logs did not preserve the real marker")
        if not any(marker in str(m.get("steps")) for m in history(sid)):
            raise RuntimeError("Persisted history lost the real result")
        print("PASS: natural task loads service tools; follow-up reuses schema; real status/logs and persisted results verified", flush=True)
        sid, events = session("catalog-invalid"), []
        stream(sid, "调用一次 load_tools，names=['missing_catalog_tool']，这是无副作用的错误处理验收。收到错误后直接报告，不重试。", events, guided=False)
        if not any(e == "step" and v.get("toolName") == "load_tools" and v.get("status") == "failed"
                   and "工具不存在" in v.get("result", {}).get("content", "") for e, v in events):
            raise RuntimeError("Invalid tool name was not visibly rejected")
        print("PASS: unknown tool loading is rejected without executing a domain operation", flush=True)
    finally:
        log_file.unlink(missing_ok=True)


def steering():
    from pathlib import Path
    if not os.environ.get("NORA_E2E_DB_NAME", "").startswith("nora_e2e_"):
        raise RuntimeError("Steering acceptance requires an isolated database")
    marker = "STEER_REF_" + uuid.uuid4().hex
    reference = Path(os.environ["NORA_E2E_WORKSPACE_ROOT"]) / (prefix + ".txt")
    reference.write_text(marker, encoding="utf-8")
    def snapshot(sid):
        request = Request(args.base + f"/api/chat/sessions/{sid}/turn/stream", headers={"Authorization": "Bearer " + token})
        with client.open(request, timeout=15) as response:
            event = "message"
            for line in response:
                text = line.decode().strip()
                if text.startswith("event:"):
                    event = text[6:].strip()
                elif text.startswith("data:") and event == "snapshot":
                    return json.loads(text[5:].strip())
        raise RuntimeError("Reconnect did not return a snapshot")
    try:
        for route in ("queue", "direct"):
            sid, events = session("steering-" + route), []
            with ThreadPoolExecutor(max_workers=1) as pool:
                task = pool.submit(stream, sid, "调用 ask_user 提问 QUESTION_WAIT，timeoutSeconds=300，等待回答后按用户最新要求继续。", events)
                try:
                    question = wait(lambda: next((v for e, v in events if e == "question_required"), None))
                    before = snapshot(sid)
                    if before.get("question", {}).get("questionToken") != question["questionToken"]:
                        raise RuntimeError("Reconnect lost the waiting question")
                    live = call(f"/chat/sessions/{sid}/turn/live")
                    content = "先不处理原问题。读取本次引用文件，然后调用 ask_user 提问 SECOND_WAIT 等我回答；得到回答后回复引用中的 STEER_REF_ 标记和 SECOND_ANSWER_OK，不做其他操作。"
                    context = {"version": 1, "refs": [{"kind": "workspace", "id": reference.name, "label": reference.name}]}
                    item = {"id": str(uuid.uuid4()), "kind": "message", "content": content, "refs": [],
                            "limitToRefs": False, "request": {**payload(content), "context": context}}
                    if route == "queue":
                        call(f"/chat/sessions/{sid}/queue", item)
                        if not call(f"/chat/sessions/{sid}/questions"):
                            raise RuntimeError("Ordinary queueing unexpectedly dismissed the question")
                        for _ in range(2):
                            call(f"/chat/sessions/{sid}/queue/{item['id']}/insert", {"turnId": live["turnId"]})
                    else:
                        for _ in range(2):
                            call(f"/chat/sessions/{sid}/steer", {"turnId": live["turnId"], "messageId": item["id"], "content": content, "context": context})
                    wait(lambda: all(q["questionToken"] != question["questionToken"] for q in call(f"/chat/sessions/{sid}/questions")), 5)
                    next_question = wait(lambda: next((v for e, v in events if e == "question_required" and v["questionToken"] != question["questionToken"]), None))
                    if not any(q["questionToken"] == next_question["questionToken"] for q in call(f"/chat/sessions/{sid}/questions")):
                        raise RuntimeError("Consumed steering signal dismissed a later question in the same run")
                    after = snapshot(sid)
                    if after.get("question", {}).get("questionToken") != next_question["questionToken"]:
                        raise RuntimeError("Reconnect retained the superseded question")
                    call(f"/chat/answers/{next_question['questionToken']}?sessionId={sid}", {"answer": "继续"})
                    task.result(timeout=90)
                    call(f"/chat/answers/{question['questionToken']}?sessionId={sid}", {"answer": "late answer"}, status=400)
                finally:
                    if not task.done():
                        call(f"/chat/sessions/{sid}/cancel", method="POST")
                        task.result(timeout=30)
            messages = history(sid)
            steps = [v for e, v in events if e == "step" and v.get("toolName") == "ask_user" and v.get("status") == "completed"]
            if len(steps) != 2 or "插入" not in steps[0]["result"]["content"] or "用户回答" not in steps[1]["result"]["content"] or marker not in messages[-1].get("content", ""):
                raise RuntimeError("Question steering lost outcome or referenced content")
            if len([m for m in messages if m.get("sender") == "steering"]) != 1:
                raise RuntimeError("Repeated insert duplicated persisted input")
            if next(v for e, v in reversed(events) if e == "done")["status"] != "completed":
                raise RuntimeError("Steered run did not complete")
            if "SECOND_ANSWER_OK" not in history(sid)[-1].get("content", ""):
                raise RuntimeError("Normal answer failed after steering")
            report = Path("logs/question-steering") / (prefix + "-" + route + ".json")
            report.parent.mkdir(parents=True, exist_ok=True)
            report.write_text(json.dumps({"events": events, "before": before, "after": after}), encoding="utf-8")
            subprocess.run(["node", "scripts/e2e-question-steering-client.mjs", str(report)], check=True)
            print(f"PASS: {route} insertion wakes question; duplicate, stale ticket, reference, persistence and later answer verified", flush=True)
    finally:
        reference.unlink(missing_ok=True)


def tools():
    row = call("/datasources", {"name": prefix, "engine": "postgresql", "host": "127.0.0.1",
               "port": int(os.environ.get("NORA_E2E_DB_PORT", "15432")),
               "database": os.environ.get("NORA_E2E_DB_NAME", "nora"), "username": "nora",
               "password": os.environ.get("NORA_DB_PASSWORD", "nora")})
    connections.append(row["id"])
    sid, events = session("tools"), []
    stream(sid, f"只调用 execute_sql 一次，datasource 参数为字符串 {row['id']}，sql 为 SELECT 'SINGLE_APP_SQL_OK' AS result。然后只回复查到的结果，不调用其他工具。", events)
    steps = [v for event, v in events if event == "step" and v.get("toolName") == "execute_sql"]
    if not any(s["status"] == "completed" and "SINGLE_APP_SQL_OK" in str(s.get("result")) for s in steps):
        raise RuntimeError("Generated SQL contract or direct domain execution failed")
    if next(v for e, v in reversed(events) if e == "done")["status"] != "completed":
        raise RuntimeError("SQL run did not complete")
    if not any(m.get("steps") for m in history(sid)):
        raise RuntimeError("Reload lost persisted tool steps")
    print("PASS: real model uses generated SQL contract; direct execution and reload retain results", flush=True)
    sid, events = session("tools-truncated"), []
    stream(sid, f"只调用 execute_sql 一次，datasource='{row['id']}'，sql='SELECT generate_series(1, 250) AS value'。"
           "不要调用其他工具，之后只回复 SDK_SQL_BOUNDED_OK。", events)
    if not any(e == "step" and v.get("toolName") == "execute_sql" and v.get("status") == "completed"
               and v.get("result", {}).get("truncated") is True for e, v in events):
        raise RuntimeError("SDK lost SQL truncation metadata")
    print("PASS: real bounded SQL retains truncation metadata through SDK result adaptation", flush=True)


def annotated():
    """Opt-in: isolated host, 150-line canary log and an owned nora-tools-* container."""
    container = os.environ.get("NORA_E2E_CONTAINER", "")
    log_file = os.environ.get("NORA_E2E_TOOL_LOG_FILE", "")
    if not container.startswith("nora-tools-") or not log_file:
        raise RuntimeError("Annotated E2E requires an owned nora-tools-* container and canary log path")

    def running():
        return subprocess.check_output(["docker", "inspect", "--format", "{{.State.Running}}", container], text=True).strip() == "true"

    if not running():
        raise RuntimeError("Owned container must initially be running")
    log_name = prefix + "-logs"
    container_alias = prefix + "-container"
    for body in [{"kind": "FILE", "name": log_name, "fileLogPath": log_file},
                 {"kind": "DOCKER", "name": container_alias, "containerName": container}]:
        sources.append(call("/environment/managed", body)["id"])
    call(f"/environment/services/{log_name}/stop", method="POST", status=400)
    sid, events = session("annotated-read"), []
    stream(sid, f"先按需 load_tools 加载 manage_service，再调用一次 action=logs，target='{log_name}'，不填 limit；"
           "同时调用 manage_service action=status 一次。不要调用无关工具。之后只回复 SDK_READ_OK。", events)
    completed = [v for e, v in events if e == "step" and v.get("status") == "completed"]
    log_step = next(v for v in completed if v.get("toolName") == "manage_service" and json.loads(v["input"]["rawArgs"]).get("action") == "logs")
    if log_step["result"]["content"].splitlines() != [f"SDK_LOG_{i:03d}" for i in range(100, 150)]:
        raise RuntimeError("SDK default limit or real log execution failed")
    if not any(v.get("toolName") == "manage_service" and json.loads(v["input"]["rawArgs"]).get("action") == "status" for v in completed):
        raise RuntimeError("No-argument environment tool failed")
    if not any(s.get("toolName") == "manage_service" for m in history(sid) for s in m.get("steps") or []):
        raise RuntimeError("Reload lost annotated tool steps")
    print("PASS: SDK binds default log limit; environment snapshot and reload preserve real results", flush=True)

    for approved in (False, True):
        sid, events = session("annotated-approve-" + str(approved)), []
        with ThreadPoolExecutor(max_workers=1) as pool:
            task = pool.submit(stream, sid, f"先按需加载 manage_service，再调用一次，target='{container_alias}'，action='stop'。"
                               "审批拒绝后不要重试。不要调用其他工具，之后只回复操作结果。", events, mode="assist")
            approval = wait(lambda: call(f"/chat/sessions/{sid}/approvals"))
            if not running():
                raise RuntimeError("Container changed before approval")
            call(f"/chat/approvals/{approval[0]['approvalToken']}?sessionId={sid}", {"approved": approved})
            task.result(timeout=180)
        expected_status = "completed" if approved else "declined"
        if not any(e == "step" and v.get("toolName") == "manage_service" and v.get("status") == expected_status
                   for e, v in events) or running() != (not approved):
            raise RuntimeError("SDK container execution bypassed or lost the permission gate")
    sid, events = session("annotated-start"), []
    stream(sid, f"先按需加载 manage_service，再调用一次，target='{container_alias}'，action='start'；之后只回复 SDK_START_OK。", events)
    if not running() or not any(e == "step" and v.get("toolName") == "manage_service" and v.get("status") == "completed"
                                for e, v in events):
        raise RuntimeError("Generated enum action did not start the owned container")
    print("PASS: denied approval leaves container untouched; approved stop and FULL start execute real SDK tools", flush=True)

    sid, events = session("annotated-cancel"), []
    with ThreadPoolExecutor(max_workers=1) as pool:
        task = pool.submit(stream, sid, f"先按需加载 manage_service，再调用，target='{container_alias}'，action='stop'。", events, mode="assist")
        wait(lambda: call(f"/chat/sessions/{sid}/approvals"))
        call(f"/chat/sessions/{sid}/cancel", method="POST")
        task.result(timeout=180)
    if not running() or call(f"/chat/sessions/{sid}/approvals") or next(v for e, v in reversed(events) if e == "done")["status"] != "cancelled":
        raise RuntimeError("Cancelled permission wait executed a tool or retained the session")
    print("PASS: cancellation clears pending approval without executing the SDK tool", flush=True)

    missing = prefix + "-absent-container"
    sources.append(call("/environment/managed", {"kind": "DOCKER", "name": missing, "containerName": missing})["id"])
    sid, events = session("annotated-unknown"), []
    stream(sid, f"先按需加载 manage_service，再调用一次，target='{missing}'，action='restart'；失败后不要重试或调用其他工具，仅回复错误摘要。", events)
    if not any(e == "step" and v.get("toolName") == "manage_service" and v.get("status") == "unknown"
               and "结果未知" in v.get("result", {}).get("content", "") for e, v in events):
        raise RuntimeError("SDK lost the real container failure's unknown-outcome metadata")
    print("PASS: real failed container command retains unknown outcome through the SDK and Run", flush=True)


def resources():
    marker = "FILE_CONTEXT_" + uuid.uuid4().hex[:12]
    boundary = uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{prefix}.txt"\r\n'
            f'Content-Type: text/plain\r\n\r\n{marker}\r\n--{boundary}--\r\n').encode()
    request = Request(args.base + "/api/files/upload", body,
                      {"Content-Type": "multipart/form-data; boundary=" + boundary, "Authorization": "Bearer " + token})
    with client.open(request, timeout=30) as response:
        file = json.load(response)["data"]
    files.append(file["id"])
    sid, events = session("resources"), []
    stream(sid, f"依次调用这些工具各一次：manage_file action=read id='file:{file['id']}'；"
           f"open_file targets=['file:{file['id']}'] intent=inspect；load_tools names=['manage_service','manage_mcp'] 后，manage_service action=status；manage_mcp action=list。"
           "不要调用其他工具或命令。最后仅输出引用文件中的验收标识。", events,
           {"version": 1, "refs": [{"kind": "file", "id": str(file["id"]), "label": file["name"]}]})
    completed = {v.get("toolName") for e, v in events if e == "step" and v.get("status") == "completed"}
    if not {"manage_file", "open_file", "manage_service", "manage_mcp"} <= completed:
        raise RuntimeError("File, viewer, environment or MCP component execution failed")
    if next(v for e, v in reversed(events) if e == "done")["status"] != "completed" \
            or marker not in history(sid)[-1]["content"]:
        raise RuntimeError("File context or resource run result was lost")
    print("PASS: file reference, viewer, environment and MCP tools share the application components", flush=True)


def builtins():
    """Complex SDK records, actual file effects and pending-question cancellation."""
    from pathlib import Path
    root = Path(os.environ["NORA_E2E_WORKSPACE_ROOT"]).resolve()
    db = os.environ.get("NORA_E2E_DB_NAME", "")
    if not db.startswith(("nora_e2e_", "nora_simplify_")):
        raise RuntimeError("Builtins E2E requires an explicitly owned database")
    path = prefix + ".txt"
    owned = root / path
    sid, events = session("builtins"), []
    stream(sid, f"这是原生工具契约验收，只按以下步骤操作，不读取额外技能或记忆。\n"
           f"1. manage_workspace action=write，path='{path}'，content='NATIVE_BEFORE'。\n"
           f"2. manage_workspace action=edit，path='{path}'，old_string='NATIVE_BEFORE'，new_string='NATIVE_AFTER'。\n"
           f"3. manage_workspace action=read，path='{path}'。\n"
           f"4. open_file targets=['workspace:{path}']，intent=inspect。\n"
           f"5. manage_skill action=create，name='{prefix}'，description='SDK 验收'，instructions='仅在明确要求时输出 SDK_SKILL_CANARY'。\n"
           f"6. manage_skill action=read，target='{prefix}'。\n"
           f"7. manage_skill action=update，target='{prefix}'，enabled=false。\n"
           f"8. manage_skill action=remove，target='{prefix}'。\n"
           "最后只回复 NATIVE_BUILTINS_DONE。", events)
    final_steps = {v["id"]: v for e, v in events if e == "step" and v.get("toolName")}
    if any(v.get("status") != "completed" for v in final_steps.values()) \
            or not {"manage_workspace", "open_file", "manage_skill"} <= {v["toolName"] for v in final_steps.values()}:
        raise RuntimeError("Complex SDK tool did not complete: " + str([(v["toolName"], v["status"]) for v in final_steps.values()]))
    if owned.read_text(encoding="utf-8") != "NATIVE_AFTER" \
            or not any(v.get("result", {}).get("files") for v in final_steps.values() if v["toolName"] == "open_file"):
        raise RuntimeError("Workspace effects or native file references were lost")
    if not any(s.get("result", {}).get("fileChanges") for m in history(sid) for s in m.get("steps") or []):
        raise RuntimeError("Reload lost file changes")
    owned.unlink()
    print("PASS: typed workspace edits, file delivery and skill CRUD execute and persist real effects", flush=True)

    workspace_transfers(root)

    resources()
    file_id = files[-1]
    sid, events = session("builtins-file-validation"), []
    stream(sid, f"这是参数校验验收，只调用一次 manage_file action=delete，id='{file_id},invalid'。"
           "不要修正参数，不要重试。报告工具错误即可。", events)
    if not any(e == "step" and v.get("toolName") == "manage_file" and v.get("status") == "failed"
               and "文件 ID 必须为正整数" in json.dumps(v.get("result"), ensure_ascii=False) for e, v in events):
        raise RuntimeError("Mixed IDs did not produce a known validation failure")
    if not any(v["id"] == file_id for v in call("/files")):
        raise RuntimeError("Validation partially deleted a real file")
    print("PASS: mixed invalid file IDs are rejected before any file is deleted", flush=True)

    sid, events = session("builtins-question-cancel"), []
    with ThreadPoolExecutor(max_workers=1) as pool:
        task = pool.submit(stream, sid, "只调用 ask_user，question='SDK 取消验收'，options=['继续','停止']，timeoutSeconds=300。", events)
        wait(lambda: any(e == "question_required" for e, _ in events))
        call(f"/chat/sessions/{sid}/cancel", method="POST")
        task.result(timeout=30)
    wait(lambda: not call(f"/chat/sessions/{sid}/turn/live")["running"], 30)
    message = history(sid)[-1]
    if message.get("runStatus") != "cancelled" \
            or not message.get("steps") \
            or not any(e == "done" and v.get("status") == "cancelled" and v.get("stopped") for e, v in events) \
            or any("未回答" in str(v.get("result")) for e, v in events if e == "step" and v.get("toolName") == "ask_user"):
        raise RuntimeError("Question cancellation did not preserve process and terminal state: " + json.dumps({
            "role": message.get("role"), "runStatus": message.get("runStatus"),
            "done": [v for e, v in events if e == "done"],
            "questionResults": [v.get("result") for e, v in events if e == "step" and v.get("toolName") == "ask_user"],
        }, ensure_ascii=False))
    print("PASS: pending SDK question cancellation preserves process, cancelled Run and stopped done event", flush=True)


def workspace_transfers(root):
    import tempfile
    with tempfile.TemporaryDirectory(prefix=prefix + "-transfers-", dir=root) as directory:
        from pathlib import Path
        fixture = Path(directory)
        source = fixture / "source"
        source.mkdir()
        for name, content in {"a.txt": "alpha", "b.txt": "beta", "a.bin": "one", "b.bin": "two"}.items():
            (source / name).write_text(content, encoding="utf-8")
        (source / "tree").mkdir()
        (source / "tree" / "inside.txt").write_text("nested", encoding="utf-8")
        for name in ("copy-conflict", "move-conflict"):
            (fixture / name).mkdir()
            (fixture / name / "b.txt").write_text("keep", encoding="utf-8")
        relative = fixture.relative_to(root).as_posix()
        operations = [
            ("copy", relative + "/source/*.txt", relative + "/source/tree/child"),
            # Matching the directory itself must also reject recursive self-copy.
            ("copy", relative + "/source/t*", relative + "/source/tree/child"),
            ("copy", relative + "/source/*.txt", relative + "/copy-conflict"),
            ("move", relative + "/source/*.txt", relative + "/move-conflict"),
            ("copy", relative + "/source/tree", relative + "/source/tree/child"),
            ("copy", relative + "/source/*.txt", relative + "/copied"),
            ("move", relative + "/source/*.bin", relative + "/moved"),
            ("copy", relative + "/source/tree", relative + "/tree-copy"),
            ("move", relative + "/copied/a.txt", relative + "/renamed.txt"),
        ]
        # First operation writes ordinary files beneath another source directory and is valid.
        sid, events = session("workspace-transfers"), []
        commands = [f"{index + 1}. manage_workspace " + json.dumps({"action": action, "path": path, "to": to}, ensure_ascii=False)
                    for index, (action, path, to) in enumerate(operations)]
        stream(sid, "这是文件操作验收。按顺序每项调用一次 manage_workspace，不调用其他工具；预期失败的项也只调用一次，不修正参数。\n"
               + "\n".join(commands) + "\n最后只回复 WORKSPACE_TRANSFERS_DONE。", events)
        final = {value["id"]: value for event, value in events if event == "step" and value.get("toolName") == "manage_workspace"}
        statuses = [step["status"] for step in final.values()]
        if statuses != ["completed", "failed", "failed", "failed", "failed", "completed", "completed", "completed", "completed"]:
            raise RuntimeError("Workspace transfer tool outcomes differ: " + str(statuses))
        if (fixture / "copy-conflict" / "a.txt").exists() or (fixture / "move-conflict" / "a.txt").exists() \
                or (fixture / "copy-conflict" / "b.txt").read_text() != "keep" \
                or (fixture / "move-conflict" / "b.txt").read_text() != "keep":
            raise RuntimeError("Batch preflight allowed partial effects or overwrote a destination")
        if (source / "tree" / "child" / "tree").exists() \
                or (fixture / "renamed.txt").read_text() != "alpha" \
                or (fixture / "copied" / "b.txt").read_text() != "beta" \
                or (fixture / "moved" / "a.bin").read_text() != "one" \
                or (fixture / "moved" / "b.bin").read_text() != "two" \
                or (source / "a.bin").exists() or (source / "b.bin").exists() \
                or (fixture / "tree-copy" / "inside.txt").read_text() != "nested":
            raise RuntimeError("Move/copy did not preserve expected file bytes and source ownership")
        entries = call("/workspace/files?dir=" + relative)
        entry = next(value for value in entries if value["path"] == relative + "/source")
        if entry["fileCount"] != 5 or entry["size"] != 24 or entry["incomplete"]:
            raise RuntimeError("Directory file count and byte total disagree with real files: " + str(entry))
        print("PASS: ordinary/glob moves and copies preserve bytes; recursive targets and conflicts fail before writes; directory summaries match", flush=True)


def runs():
    def queued(text):
        return {"id": str(uuid.uuid4()), "kind": "message", "content": text, "refs": [],
                "limitToRefs": False, "request": payload(text)}
    sid, events = session("queue"), []
    with ThreadPoolExecutor(max_workers=1) as pool:
        task = pool.submit(stream, sid, "调用 ask_user 问我 SINGLE_APP_WAIT 并等待回答。得到回答后仅回复 RUN_CONTINUED。", events)
        question = wait(lambda: next((v for e, v in events if e == "question_required"), None))
        live = call(f"/chat/sessions/{sid}/turn/live")
        if not live["running"] or live["bufferedEvents"] < 1:
            raise RuntimeError("Run is not reconnectable while waiting")
        call(f"/chat/sessions/{sid}/messages", payload("duplicate"), status=409)
        removed = queued("WITHDRAWN_MUST_NOT_RUN")
        call(f"/chat/sessions/{sid}/queue", removed)
        call(f"/chat/sessions/{sid}/queue/{removed['id']}", method="DELETE")
        steering = queued("补充要求：不再等待原问题回答，最终答案附加 STEERING_OK，直接完成，不要再次提问。")
        call(f"/chat/sessions/{sid}/queue", steering)
        call(f"/chat/sessions/{sid}/queue/{steering['id']}/insert", {"turnId": live["turnId"]})
        waiting = queued("不调用工具，只回复 QUEUE_DONE。")
        call(f"/chat/sessions/{sid}/queue", waiting)
        wait(lambda: not call(f"/chat/sessions/{sid}/questions"), 5)
        call(f"/chat/answers/{question['questionToken']}?sessionId={sid}", {"answer": "继续"}, status=400)
        task.result(timeout=180)
    wait(lambda: not call(f"/chat/sessions/{sid}/turn/live")["running"] and not call(f"/chat/sessions/{sid}/queue")["messages"])
    messages = history(sid)
    if len([m for m in messages if m["role"] == "user"]) != 3 or "QUEUE_DONE" not in messages[-1].get("content", ""):
        raise RuntimeError("Queue/steering order or background dispatch failed")
    sid, events = session("cancel"), []
    with ThreadPoolExecutor(max_workers=1) as pool:
        task = pool.submit(stream, sid, "调用 ask_user 问我 CANCEL_WAIT 并等待回答。", events)
        wait(lambda: any(e == "question_required" for e, v in events))
        call(f"/chat/sessions/{sid}/cancel", method="POST")
        task.result(timeout=30)
    if next(v for e, v in reversed(events) if e == "done")["status"] != "cancelled":
        raise RuntimeError("Cancellation did not preserve the run terminal status")
    wait(lambda: not call(f"/chat/sessions/{sid}/turn/live")["running"])
    after = []
    stream(sid, "不调用工具，仅回复 AFTER_CANCEL_OK。", after)
    if next(v for e, v in reversed(after) if e == "done")["status"] != "completed" or "AFTER_CANCEL_OK" not in history(sid)[-1]["content"]:
        raise RuntimeError("Session could not start a fresh run after cancellation")
    print("PASS: session ownership, live replay state, queue withdrawal/insertion/dispatch and cancellation", flush=True)


def automation():
    call("/automations", {"name": prefix, "triggerType": "manual", "actionType": "sql", "sql": "SELECT 1"}, status=400)
    rule = call("/automations", {"name": prefix, "triggerType": "manual", "actionType": "agent", "prompt": "不调用工具，仅回复 AUTOMATION_RUN_OK。"})
    rules.append(rule["id"])
    result = call(f"/automations/{rule['id']}/run", method="POST")
    if result["status"] != "success" or "AUTOMATION_RUN_OK" not in result["detail"]:
        raise RuntimeError("Automation did not consume the shared persisted Run result")
    sid = "auto-rule-" + str(rule["id"])
    sessions.append(sid)
    if not history(sid):
        raise RuntimeError("Automation has no persisted conversation")
    if not any(n["event"] == "taskDone" and prefix in (n["detail"] or "") for n in call("/notifications")):
        raise RuntimeError("Direct notification persistence failed")
    print("PASS: explicit SQL binding; automation consumes shared Run terminal result and stores notifications", flush=True)


try:
    if token:
        call("/files", status=401, authorized=False)
        call("/auth/status", authorized=False)
        call("/files?token=" + token, authorized=False)
    if args.case in ("all", "tools"):
        tools()
    if args.case in ("all", "resources"):
        resources()
    if args.case in ("all", "run"):
        runs()
    if args.case in ("all", "automation"):
        automation()
    if args.case == "annotated":
        annotated()
    if args.case == "builtins":
        builtins()
    if args.case == "catalog":
        catalog()
    if args.case == "steering":
        steering()
finally:
    for sid in sessions:
        call(f"/chat/sessions/{sid}/cancel", method="POST")
        wait(lambda: not call(f"/chat/sessions/{sid}/turn/live")["running"], 30)
        call(f"/chat/sessions/{sid}", method="DELETE")
    for id in rules:
        call(f"/automations/{id}", method="DELETE")
    for id in connections:
        call(f"/datasources/{id}", method="DELETE")
    for id in files:
        call(f"/files?ids={id}", method="DELETE")
        call(f"/files/trash?ids={id}", method="DELETE")
    for id in sources:
        call(f"/environment/managed/{id}", method="DELETE")
