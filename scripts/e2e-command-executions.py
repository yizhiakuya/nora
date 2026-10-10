"""真实模型、命令进程与持久化 API 的后台任务验收；不 mock 服务。"""
import argparse
import json
import os
import sys
from pathlib import Path
import threading
import time
from urllib.parse import quote
from urllib.request import ProxyHandler, Request, build_opener


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://127.0.0.1:18083")
    parser.add_argument("--tool", choices=["sh", "powershell"], default="powershell")
    parser.add_argument("--keep", action="store_true", help="保留首个运行任务用于浏览器验收")
    args = parser.parse_args()
    headers = {"Content-Type": "application/json"}
    if os.getenv("NORA_AUTH_TOKEN"):
        headers["Authorization"] = "Bearer " + os.environ["NORA_AUTH_TOKEN"]
    opener = build_opener(ProxyHandler({}))
    session = "command-e2e-" + str(time.time_ns())
    root = args.base.rstrip("/") + "/api"
    record = {"session": session, "checks": [], "turns": []}
    report = Path("logs/" + session + ".json")
    report.parent.mkdir(parents=True, exist_ok=True)

    def save():
        report.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")

    def check(condition, label):
        record["checks"].append({"label": label, "passed": bool(condition)})
        save()
        print("PASS" if condition else "FAIL", label, flush=True)
        if not condition:
            raise RuntimeError(label)

    def api(path, data=None, method=None):
        body = None if data is None else json.dumps(data, ensure_ascii=False).encode()
        with opener.open(Request(root + path, body, headers, method=method), timeout=30) as response:
            value = json.load(response)
            return value.get("data", value) if isinstance(value, dict) else value

    provider = next(p for p in api("/models/providers") if p.get("enabled") and p.get("models"))
    model = provider["models"][0]
    commands = "/chat/sessions/" + session + "/commands"
    messages = "/chat/sessions/" + session + "/messages"

    def turn(prompt, permission="full"):
        item = {"tools": {}, "errors": []}
        record["turns"].append(item)
        payload = {"content": prompt, "model": model, "providerId": provider["id"],
                   "reasoningLevel": "none", "permissionMode": permission}
        started = time.monotonic()
        try:
            with opener.open(Request(root + messages, json.dumps(payload, ensure_ascii=False).encode(), headers), timeout=240) as response:
                event = ""
                for raw in response:
                    line = raw.decode().strip()
                    if line.startswith("event:"):
                        event = line[6:].strip()
                    elif line.startswith("data:"):
                        data = json.loads(line[5:])
                        if event == "step" and data.get("toolName"):
                            item["tools"][data["id"]] = {k: data.get(k) for k in ("toolName", "status", "input", "result")}
                        elif event == "error":
                            item["errors"].append(data)
                        elif event == "approval_required":
                            item["errors"].append("验收任务出现意外审批")
                            api("/chat/sessions/" + session + "/cancel", {}, "POST")
                            break
        except Exception as error:
            item["errors"].append(str(error))
        item["durationSeconds"] = round(time.monotonic() - started, 2)
        save()
        return item

    def until(predicate, seconds=60):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            value = predicate()
            if value:
                return value
            time.sleep(.25)
        raise TimeoutError("等待真实状态超时")

    def output(text):
        return "[Console]::Write('" + text + "')" if args.tool == "powershell" else "printf '%s' '" + text + "'"

    def sleep(seconds):
        return "Start-Sleep -Seconds " + str(seconds) if args.tool == "powershell" else "sleep " + str(seconds)

    def finished(path):
        value = api(path)
        return value if value.get("finishedAt") else None

    try:
        command = output("BACKGROUND_FIRST") + "; " + sleep(15) + "; " + output("BACKGROUND_SECOND") + "; " + sleep(180)
        first = turn("后台命令验收。只调用一次 " + args.tool + "，command 原样如下，background=true，timeout=240，title=后台命令验收：\n"
                     + command + "\n工具返回后立即回答‘后台已启动’，不要等待、查询或重跑，不写文件或记忆。")
        check(not first["errors"], "模型正常启动后台命令")
        job = until(lambda: next(iter(api(commands)), None))
        record["executionId"] = job["executionId"]
        execution = commands + "/" + job["executionId"]
        check(job["status"] == "running", "正常最终回复保留运行进程")
        until(lambda: "BACKGROUND_FIRST" in api(execution)["output"])
        check(True, "命令结束前收到无换行输出")
        if args.keep:
            save()
            print("SESSION", session, flush=True)
            print("RECORD", report.resolve(), flush=True)
            return

        second = turn("后台任务保持运行；不要调用工具，只回复 COMMUNICATION_OK，不写记忆。")
        check(not second["errors"] and not second["tools"] and "COMMUNICATION_OK" in api(messages)[-1]["content"]
              and api(execution)["status"] == "running", "后台运行时可以开始下一轮正常交流")
        query = turn("仅调用一次 command_execution，action=wait，executionId=" + job["executionId"]
                     + "，yieldMs=0。然后回答任务状态，仍在运行则立即结束回答，不重复轮询，不启动命令。", "assist")
        check(not query["errors"] and any(t["toolName"] == "command_execution" for t in query["tools"].values())
              and len(api(commands)) == 1, "查询始终操作同一任务，没有重跑命令")
        until(lambda: "BACKGROUND_SECOND" in api(execution)["output"], 30)
        check(True, "后台输出持续更新到后续内容")
        snapshots = [s for message in api(messages) for s in message.get("steps", []) if (s.get("result") or {}).get("execution")]
        check(any(s["result"]["execution"]["executionId"] == job["executionId"]
                  and "BACKGROUND_SECOND" in s["result"]["execution"]["output"] for s in snapshots), "刷新历史获得任务最新输出")

        cancel_thread = threading.Thread(target=lambda: turn("取消验收。仅调用一次 " + args.tool
            + "，command=" + sleep(180) + "，yieldMs=30000，timeout=240，title=当前轮次取消验收。等待工具返回后结束，不做其他操作。"))
        cancel_thread.start()
        current = until(lambda: next((x for x in api(commands) if x["executionId"] != job["executionId"]), None))
        api("/chat/sessions/" + session + "/cancel", {}, "POST")
        cancel_thread.join(30)
        stopped = until(lambda: finished(commands + "/" + current["executionId"]))
        check(not cancel_thread.is_alive() and stopped["status"] == "cancelled"
              and api(execution)["status"] == "running", "停止当前轮次清理本轮进程，保留上一轮后台任务")
        stop = turn("仅调用一次 command_execution，action=stop，executionId=" + job["executionId"]
                    + "。然后回复已停止，不启动任何命令。")
        stopped = until(lambda: finished(execution))
        check(not stop["errors"] and stopped["status"] == "cancelled", "模型可停止既有后台任务")
        check(stopped["exitCode"] is not None and "BACKGROUND_SECOND" in stopped["output"], "停止后保存退出码和最终输出")
        history = api(messages)
        check(any(((s.get("result") or {}).get("execution") or {}).get("status") == "cancelled"
                  for message in history for s in message.get("steps", [])), "历史记录同步最终任务状态")

        timeout_turn = turn("超时验收。只调用一次 " + args.tool + "，command=" + sleep(30)
                            + "，background=true，timeout=2，title=安静命令超时验收。立即结束回答，不等待不重试。")
        timed = until(lambda: next((x for x in api(commands) if x["timeoutSeconds"] == 2 and x.get("finishedAt")), None))
        check(not timeout_turn["errors"] and timed["status"] == "timed_out" and timed["elapsedMs"] < 6000,
              "真实工具调用的总超时独立于模型和输出")
        api(commands, method="DELETE")
        check(not api(commands) and api(execution)["status"] == "cancelled", "清空已结束列表保留对话任务记录")
        log_path = stopped["logTarget"].removeprefix("workspace:")
        check("BACKGROUND_SECOND" in api("/workspace/file?path=" + quote(log_path))["content"], "清空列表后完整日志仍可读取")
        print("RECORD", report.resolve(), flush=True)
    finally:
        if not args.keep:
            for job in api(commands):
                if job["status"] == "running":
                    api(commands + "/" + job["executionId"] + "/stop", {}, "POST")
            save()


if __name__ == "__main__":
    main()
