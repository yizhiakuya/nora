"""真实模型/工具验收；复制测试渠道，不更改用户渠道或默认模型。"""
import argparse
import json
from pathlib import Path
import socket
import subprocess
import time
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request, build_opener


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:18080")
    parser.add_argument("--source-provider", type=int, default=7)
    parser.add_argument("--model", default="deepseek-v4.1-flash")
    parser.add_argument("--case", choices=["all", "success", "tools", "error", "network", "cancel", "write"], default="all")
    parser.add_argument("--protocol", choices=["all", "openai", "responses", "anthropic"], default="all")
    args = parser.parse_args()
    protocols = ["openai", "responses", "anthropic"] if args.protocol == "all" else [args.protocol]
    opener = build_opener(ProxyHandler({}))
    stamp = str(int(time.time()))
    record_path = Path("agent-workspace/browser/output-recovery-" + stamp + ".json")
    record_path.parent.mkdir(parents=True, exist_ok=True)
    record = {"checks": [], "turns": []}

    def api(path, payload=None, method=None):
        request = Request(args.base + path, None if payload is None else json.dumps(payload).encode(),
                          {"Content-Type": "application/json"}, method=method)
        with opener.open(request, timeout=30) as response:
            data = json.load(response)
            return data.get("data", data) if isinstance(data, dict) else data

    def check(condition, label):
        record["checks"].append({"label": label, "passed": bool(condition)})
        record_path.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
        if not condition:
            raise AssertionError(label)
        print("PASS", label, flush=True)

    sql = ("INSERT INTO schema_agent.model_provider(name,protocol,endpoint,api_key,enabled,models,status,model_settings) "
           f"SELECT 'output-recovery-e2e-{stamp}',protocol,endpoint,api_key,true,models,status,model_settings "
           f"FROM schema_agent.model_provider WHERE id={args.source_provider} AND deleted_at IS NULL RETURNING id;")
    result = subprocess.run(["rtk", "proxy", "docker", "exec", "nora-postgres", "psql", "-U", "nora", "-d", "nora",
                             "-At", "-c", sql], check=True, capture_output=True, text=True, encoding="utf-8")
    provider = int(next(line for line in result.stdout.splitlines() if line.isdigit()))
    record["testProvider"] = provider
    print("TEST_PROVIDER", provider, flush=True)

    def model_settings(protocol="openai"):
        return api(f"/api/models/providers/{provider}", {"modelSettings": {args.model: {
            "contextWindow": 128000, "protocol": protocol,
            "reasoningLevels": ["none", "minimal", "low", "medium", "high", "xhigh", "max"]}}}, "PUT")

    source_endpoint = next(p["endpoint"] for p in api("/api/models/providers") if p["id"] == args.source_provider)
    # 绑定但不监听，真实触发连接失败；不伪造上游响应。
    unavailable = socket.socket()
    unavailable.bind(("127.0.0.1", 0))
    unavailable_endpoint = f"http://127.0.0.1:{unavailable.getsockname()[1]}/v1"

    def endpoint(value):
        api(f"/api/models/providers/{provider}", {"endpoint": value}, "PUT")

    def turn(label, prompt, level="max", hook=None):
        session = f"output-recovery-{label}-{stamp}"
        payload = {"content": prompt, "model": args.model, "providerId": provider,
                   "reasoningLevel": level, "permissionMode": "full"}
        request = Request(args.base + f"/api/chat/sessions/{session}/messages",
                          json.dumps(payload, ensure_ascii=False).encode(), {"Content-Type": "application/json"})
        item = {"session": session, "events": []}
        record["turns"].append(item)
        print("TURN", session, flush=True)
        with opener.open(request, timeout=180) as response:
            event = ""
            for raw in response:
                line = raw.decode().strip()
                if line.startswith("event:"):
                    event = line[6:].strip()
                elif line.startswith("data:"):
                    data = json.loads(line[5:])
                    item["events"].append({"event": event, "data": data})
                    if hook:
                        hook(event, data, session)
                    if event in ("done", "error") or (event == "step" and data.get("id", "").startswith("s-retry-")):
                        print(event, data.get("status", data.get("errorCode", "")), flush=True)
                    if event in ("step", "done", "error", "question_required"):
                        record_path.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
        item["history"] = api(f"/api/chat/sessions/{session}/messages")
        return item["events"]

    def retries(events):
        return sum(e["event"] == "step" and e["data"].get("id", "").startswith("s-retry-") for e in events)

    def done(events):
        return next(e["data"] for e in events if e["event"] == "done")

    try:
        saved = model_settings()
        check("maxOutputTokens" not in saved["modelSettings"][args.model], "模型设置不再提供输出预算")

        if args.case in ("all", "success"):
            for protocol in protocols:
                model_settings(protocol)
                events = turn("success-" + protocol, "不使用工具。计算1+1，只回答数值。", "none")
                check(retries(events) == 0, protocol + " 正常回答不重试")
                check(done(events)["status"] == "completed", protocol + " 正常完成")
                answers = [e["data"].get("detail", "") for e in events if e["event"] == "step"
                           and e["data"].get("type") == "answer"]
                check(any("2" in text for text in answers), protocol + " 最终答案正确")

        if args.case in ("all", "tools"):
            for protocol in protocols:
                model_settings(protocol)
                events = turn("tools-" + protocol,
                              '调用一次 run_command 执行 rtk proxy python -c "print(17*19)"，'
                              "根据真实命令结果回答数值，不读写文件，不调用其他工具。")
                calls = [e["data"] for e in events if e["event"] == "step"
                         and e["data"].get("toolName") == "run_command"
                         and e["data"].get("status") == "completed"]
                check(len(calls) == 1 and "323" in str(calls[0].get("result")), protocol + " 真实工具执行一次")
                check(any(e["event"] == "reasoning_delta" for e in events), protocol + " 实时转发思考事件")
                check(done(events)["status"] == "completed" and retries(events) == 0, protocol + " 工具结果后正常继续")
                check(any(e["event"] == "step" and e["data"].get("type") == "answer"
                          and "323" in e["data"].get("detail", "") for e in events), protocol + " 回答基于工具结果")

        if args.case in ("all", "error"):
            invalid_key = "invalid-nora-error-e2e-" + stamp
            api(f"/api/models/providers/{provider}", {"apiKey": invalid_key}, "PUT")
            try:
                for protocol in protocols:
                    model_settings(protocol)
                    body = {"model": args.model, "stream": True}
                    body.update({"input": "hi"} if protocol == "responses"
                                else {"messages": [{"role": "user", "content": "hi"}]})
                    path = {"responses": "/responses", "anthropic": "/messages"}.get(protocol, "/chat/completions")
                    headers = {"Content-Type": "application/json", "Authorization": "Bearer " + invalid_key}
                    if protocol == "anthropic":
                        headers = {"Content-Type": "application/json", "x-api-key": invalid_key,
                                   "anthropic-version": "2023-06-01"}
                    request = Request(source_endpoint.rstrip("/") + path, json.dumps(body).encode(),
                                      headers)
                    try:
                        with opener.open(request, timeout=30):
                            raise AssertionError("无效密钥应由真实上游拒绝")
                    except HTTPError as error:
                        raw_error = error.read().decode("utf-8")
                        record.setdefault("upstreamErrors", []).append({"protocol": protocol,
                                                                      "status": error.code, "body": raw_error})
                    events = turn("raw-error-" + protocol, "不使用工具，只回复你好。", "none")
                    failure = next(e["data"] for e in events if e["event"] == "error")
                    check(failure["message"] == raw_error, protocol + " 原样透传真实上游错误体")
                    check(not failure.get("hint"), protocol + " 不附加错误建议")
                    check(retries(events) == 0 and done(events)["status"] == "failed", protocol + " 上游拒绝直接结束")
                    step = next(e["data"] for e in events if e["event"] == "step" and e["data"].get("id") == "s-error")
                    check(step["detail"] == raw_error, protocol + " 错误步骤保留原文")
                    history = record["turns"][-1]["history"]
                    saved_error = next(s["detail"] for m in history if m["role"] == "assistant"
                                       for s in m["steps"] if s["id"] == "s-error")
                    check(saved_error == raw_error, protocol + " 刷新历史保留原文")
            finally:
                restore = (f"UPDATE schema_agent.model_provider SET api_key=(SELECT api_key FROM schema_agent.model_provider "
                           f"WHERE id={args.source_provider}) WHERE id={provider};")
                subprocess.run(["rtk", "proxy", "docker", "exec", "nora-postgres", "psql", "-U", "nora", "-d", "nora",
                                "-At", "-c", restore], check=True, capture_output=True)

        if args.case in ("all", "network"):
            endpoint(unavailable_endpoint)
            for protocol in protocols:
                model_settings(protocol)
                events = turn("network-" + protocol, "不使用工具，只回复你好。", "max")
                check(retries(events) == 2, protocol + " 空连接失败最多尝试三次")
                check(done(events)["status"] == "failed", protocol + " 连接仍失败时明确结束")
                check(any(e["event"] == "error" and e["data"].get("errorCode") == "LLM_STREAM_INTERRUPTED" for e in events), protocol + " 保留连接错误原因")
            endpoint(source_endpoint)

        if args.case in ("all", "cancel"):
            model_settings(protocols[-1])
            cancelled = False

            def stop(event, data, session):
                nonlocal cancelled
                if event == "reasoning_delta" and not cancelled:
                    api(f"/api/chat/sessions/{session}/cancel", {}, "POST")
                    cancelled = True

            events = turn("cancel", "仅在内部推理中逐一证明1至1000每个整数的各种分解性质，最后总结结果，不调用工具。", hook=stop)
            check(cancelled and done(events)["status"] == "cancelled", "停止生成真实取消")
            check(retries(events) == 0, "取消不触发重试")

        if args.case in ("all", "write"):
            endpoint(source_endpoint)
            model_settings(protocols[-1])
            relative_path = f"output-recovery/{stamp}-network.txt"
            marker = "write-once-" + stamp
            changed = False

            def cut_after_write(event, data, session):
                nonlocal changed
                if event == "step" and data.get("toolName") == "run_command" and data.get("status") == "running" and not changed:
                    endpoint(unavailable_endpoint)
                    changed = True

            command = ("rtk proxy python -c \"from pathlib import Path; import time; "
                       f"p=Path('{relative_path}'); p.parent.mkdir(parents=True,exist_ok=True); "
                       f"f=p.open('a',encoding='utf-8'); f.write('{marker}\\n'); f.close(); time.sleep(1)\"")
            prompt = ("先调用一次 run_command 执行以下命令，在默认工作区执行，不更改命令：\n" + command
                      + "\n命令完成后只回复已完成，不要再写文件，也不要调用其他工具。")
            events = turn("write-network", prompt, "none", hook=cut_after_write)
            check(changed and retries(events) == 2, "工具执行后仅重试模型请求两次")
            check(any(e["event"] == "step" and e["data"].get("toolName") == "run_command" and e["data"].get("status") == "completed" for e in events), "保留工具成果")
            check(done(events)["status"] == "partial", "未完成任务标为部分完成")
            check(any(e["event"] == "error" and e["data"].get("errorCode") == "LLM_STREAM_INTERRUPTED" for e in events), "明确报错")
            file = Path("agent-workspace") / relative_path
            check(file.read_text(encoding="utf-8").splitlines() == [marker], "真实写入仅一次")
        print("RECORD", record_path.resolve(), flush=True)
    finally:
        try:
            for item in record["turns"]:
                if not any(e["event"] == "done" for e in item["events"]):
                    api(f"/api/chat/sessions/{item['session']}/cancel", {}, "POST")
        finally:
            unavailable.close()
            api(f"/api/models/providers/{provider}", method="DELETE")
            record_path.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
