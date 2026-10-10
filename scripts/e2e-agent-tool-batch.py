"""真实模型 / SSE / 文件服务验收：并发、失败、换行编辑、审批与停止生成。

使用明确的短指令测 harness 行为，推理等级仅作用于测试会话，不修改用户设置。
"""
import argparse
import json
import os
from pathlib import Path
import sys
import tempfile
import time
from urllib.parse import quote
from urllib.request import ProxyHandler, Request, build_opener


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://localhost:18083")
    parser.add_argument("--model", default="deepseek-v4.1-flash")
    parser.add_argument("--provider", type=int, default=7)
    parser.add_argument("--reasoning-level", default="minimal")
    parser.add_argument("--case", choices=["platform", "batch", "edit", "approval", "cancel", "automation", "all"], default="all")
    parser.add_argument("--tool", choices=["powershell", "sh"], default="powershell" if sys.platform == "win32" else "sh")
    parser.add_argument("--keep-sessions", action="store_true")
    parser.add_argument("--automation-base", default="http://localhost:18086")
    args = parser.parse_args()
    opener = build_opener(ProxyHandler({}))
    prefix = "agent-tools-e2e-" + str(time.time_ns())
    records = []
    files = []
    headers = {"Content-Type": "application/json"}
    if os.environ.get("NORA_AUTH_TOKEN"):
        headers["Authorization"] = "Bearer " + os.environ["NORA_AUTH_TOKEN"]

    def request(path, data=None, method=None, base=None):
        body = None if data is None else json.dumps(data, ensure_ascii=False).encode()
        with opener.open(Request((base or args.base) + path, body,
                                 headers, method=method), timeout=30) as response:
            result = json.load(response)
        if isinstance(result, dict) and "code" in result:
            if result["code"] != 0:
                raise RuntimeError(result.get("message"))
            return result.get("data")
        return result

    def run(case, prompt, mode="FULL", handle=None):
        session = prefix + "-" + case
        payload = {"content": prompt, "model": args.model, "providerId": args.provider,
                   "permissionMode": mode, "reasoningLevel": args.reasoning_level}
        record = {"session": session, "tools": {}, "events": [], "done": None}
        records.append(record)
        print("SESSION", session, flush=True)
        started = time.monotonic()
        req = Request(args.base + "/api/chat/sessions/" + session + "/messages",
                      json.dumps(payload, ensure_ascii=False).encode(), headers)
        with opener.open(req, timeout=300) as response:
            event = ""
            for line in response:
                line = line.decode().strip()
                if line.startswith("event:"):
                    event = line[6:].strip()
                elif line.startswith("data:"):
                    data = json.loads(line[5:])
                    now = time.monotonic() - started
                    if event == "step":
                        step = data.get("step", data)
                        if step.get("toolName"):
                            item = record["tools"].setdefault(step["id"], {"start": now})
                            if step.get("status") != "running":
                                item["end"] = now
                            item["step"] = step
                            if record["events"] and record["events"][-1].get("id") == step["id"] and record["events"][-1].get("status") == step.get("status"):
                                continue
                            record["events"].append({"event": event, "id": step["id"], "status": step.get("status"), "time": now})
                            print("TOOL", step["toolName"], step.get("status"), round(now, 3), flush=True)
                    elif event == "done":
                        record["done"] = data
                    elif event == "error":
                        raise RuntimeError(data.get("message"))
                    elif event == "approval_required":
                        record["events"].append({"event": event, "time": now})
                    if handle:
                        handle(session, record, event, data, now)
        if not record["done"]:
            raise RuntimeError("没有收到轮次终态")
        history = request("/api/chat/sessions/" + session + "/messages")
        persisted = {step["id"]: step for step in history[-1].get("steps", []) if step.get("toolName")}
        for key, item in record["tools"].items():
            if key not in persisted:
                raise RuntimeError("工具步骤持久化缺失")
            # 取消会关闭执行中的流,最终命令状态以落库快照为准。
            if record["done"].get("status") == "cancelled":
                if persisted[key].get("status") in ("running", "pending"):
                    raise RuntimeError("取消后仍持久化为执行中")
                item["step"] = persisted[key]
            elif persisted[key].get("status") != item["step"].get("status"):
                raise RuntimeError("工具终态不一致")
            step = item["step"]
            if step["toolName"] in ("powershell", "sh", "run_command"):
                if step["toolName"] != args.tool or "shell" in json.loads(step["input"]["rawArgs"]):
                    raise RuntimeError("终端工具与平台不一致或仍接受 shell 参数")
        record["elapsed"] = time.monotonic() - started
        return record

    try:
        if args.case in ("platform", "all"):
            record = run("platform", "请通过当前平台注册的终端工具运行一条无副作用命令，查询实际操作系统和当前工作目录。不要使用其他工具，不需要扫描目录或读取文件。拿到结果后中文简短总结。")
            tools = list(record["tools"].values())
            if not tools or any(x["step"]["toolName"] != args.tool or x["step"]["status"] != "completed" for x in tools):
                raise RuntimeError("模型没有自动使用当前平台的原生工具")
            content = "\n".join(x["step"].get("result", {}).get("content", "") for x in tools)
            if not any(os_name in content for os_name in (("Windows", "Win32NT") if args.tool == "powershell" else ("Linux", "Darwin"))):
                raise RuntimeError("没有取得实际操作系统信息")
            if record["done"].get("status") != "completed":
                raise RuntimeError("环境查询没有正常结束")
            print("PASS 自动注册平台工具、真实环境与持久化", args.tool, flush=True)

        if args.case in ("batch", "all"):
            commands = ["Start-Sleep -Seconds 4; Write-Output 'PARALLEL-ONE'; [Environment]::OSVersion.Platform; $PSVersionTable.PSVersion.ToString()",
                        "Start-Sleep -Seconds 2; Write-Output 'PARALLEL-TWO'",
                        "cmd /c exit 7; Write-Output 'MUST-NOT-RUN'"] if args.tool == "powershell" else [
                            "sleep 4; printf 'PARALLEL-ONE\\n'; uname -s; readlink /proc/$$/exe",
                            "sleep 2; printf 'PARALLEL-TWO\\n'", "exit 7; printf 'MUST-NOT-RUN\\n'"]
            record = run("batch", f"这是工具并发验收。请在同一轮直接调用三次 {args.tool}，严格分别使用以下三条互不依赖的当前平台命令，不合并、不修改命令、不使用其他工具、无需进度前言："
                         + json.dumps(commands, ensure_ascii=False)
                         + "。第三条预期失败，收到三个结果后只简短说明各条输出和失败情况，不重试。")
            tools = list(record["tools"].values())
            if len(tools) != 3 or len({x["step"]["roundIndex"] for x in tools}) != 1:
                raise RuntimeError("模型没有在同一轮发出三个调用")
            waiting = [x for x in tools if "PARALLEL-" in x["step"].get("input", {}).get("rawArgs", "")]
            overlap = min(x["end"] for x in waiting) - max(x["start"] for x in waiting)
            if overlap < 1.5 or max(x["start"] for x in tools) - min(x["start"] for x in tools) > 1:
                raise RuntimeError("工具调用未实际并发")
            contents = [x["step"].get("result", {}).get("content", "") for x in tools]
            if not all(any(marker in content for content in contents) for marker in ("PARALLEL-ONE", "PARALLEL-TWO")):
                raise RuntimeError("独立工具结果丢失")
            if not any(("Win32NT" if args.tool == "powershell" else "Linux") in content for content in contents):
                raise RuntimeError("真实执行平台不符")
            failed = [x for x in tools if x["step"]["status"] == "failed"]
            if len(failed) != 1 or "MUST-NOT-RUN" in failed[0]["step"]["result"].get("content", ""):
                raise RuntimeError("原生程序中途失败仍执行了后续命令")
            if record["done"].get("status") != "completed" or not record["done"].get("hadToolErrors"):
                raise RuntimeError("已处理的工具错误被误判为运行失败，或错误记录丢失")
            record["overlapSeconds"] = round(overlap, 3)
            print("PASS 并发、单工具失败隔离与持久化", record["overlapSeconds"], flush=True)

        if args.case in ("edit", "all"):
            cases = [("crlf.md", "\ufeff前言\r\n[原文]\\E\r\n第二行\r\n结尾\r\n", "[原文]\\E\n第二行", "替换\n新行",
                      "\ufeff前言\r\n替换\r\n新行\r\n结尾\r\n"),
                     ("lf.md", "前言\n原文\n第二行\n结尾\n", "原文\r\n第二行", "替换\r\n新行", "前言\n替换\n新行\n结尾\n"),
                     ("duplicate.md", "a\r\nb\na\nb\n", "a\nb", "x", "a\r\nb\na\nb\n"),
                     ("overlap.md", "aaa", "aa", "x", "aaa")]
            edits = []
            for name, original, old, new, expected in cases:
                path = prefix + "-" + name
                files.append(path)
                request("/api/workspace/file", {"path": path, "content": original}, "PUT")
                edits.append({"action": "edit", "path": path, "old_string": old, "new_string": new})
            record = run("edit", "这是换行编辑验收，已创建四个测试文件。仅直接用 manage_workspace 各执行一次下面的 edit 参数；严格使用给定原文，禁止 write、终端、目录扫描、修改其他文件或提交 Git。"
                         + json.dumps(edits, ensure_ascii=False) + "。这些是 JSON 参数，换行只解码一次，不要再次转义成反斜杠文本；第二项的 old_string 和 new_string 含真实 CRLF 控制字符。前两次应成功，后两次应因原文不唯一而报错；不要重试。四个编辑完成后结束，无需额外核验，验收脚本会核对字节。")
            statuses = [x["step"]["status"] for x in record["tools"].values()]
            if len(statuses) != 4 or statuses.count("completed") != 2 or statuses.count("failed") != 2:
                raise RuntimeError("跨换行编辑或唯一性检查失败")
            if any(not x["step"]["result"].get("fileChanges") for x in record["tools"].values() if x["step"]["status"] == "completed"):
                raise RuntimeError("成功编辑未返回真实文件变更记录")
            for (name, _, _, _, expected), path in zip(cases, files):
                req = Request(args.base + "/api/workspace/file/raw?path=" + quote(path), headers=headers)
                with opener.open(req, timeout=30) as response:
                    if response.read() != expected.encode():
                        raise RuntimeError("编辑损坏了 BOM、原换行格式或周围文本: " + name)
            print("PASS LF/CRLF 编辑、BOM 保存、重复及重叠原文拒绝", flush=True)

        if args.case in ("approval", "all"):
            def approve(session, record, event, data, now):
                if event == "approval_required":
                    token = data.get("approvalToken")
                    if not token:
                        raise RuntimeError("审批事件缺少 token")
                    request("/api/chat/approvals/" + token + "?sessionId=" + quote(session), {"approved": True})
            commands = ["Write-Output 'APPROVAL-ONE'", "Write-Output 'APPROVAL-TWO'"] if args.tool == "powershell" else [
                "printf 'APPROVAL-ONE\\n'", "printf 'APPROVAL-TWO\\n'"]
            record = run("approval", f"这是审批验收，仅在同一轮发出两个 {args.tool}，严格分别执行："
                         + json.dumps(commands, ensure_ascii=False)
                         + "。不要合并、不要其他工具；系统会批准这两条无副作用命令，之后简短结束。", "ASK", approve)
            tools = list(record["tools"].values())
            if len(tools) != 2 or len([x for x in record["events"] if x["event"] == "approval_required"]) != 2:
                raise RuntimeError("ASK 批量调用没有逐项审批")
            if tools[1]["start"] < tools[0]["end"] or any(x["step"]["status"] != "completed" for x in tools):
                raise RuntimeError("审批调用未保持串行生命周期")
            print("PASS ASK 逐项审批、批准后串行执行", flush=True)

        if args.case in ("cancel", "all"):
            def stop(session, record, event, data, now):
                if len(record["tools"]) == 2 and all(x["step"].get("status") == "running" for x in record["tools"].values()) and "cancelAt" not in record:
                    record["cancelAt"] = now
                    request("/api/chat/sessions/" + session + "/cancel", {})
            commands = ["Start-Sleep -Seconds 30; Write-Output 'CANCEL-ONE'", "Start-Sleep -Seconds 30; Write-Output 'CANCEL-TWO'"] if args.tool == "powershell" else [
                "sleep 30; printf 'CANCEL-ONE\\n'", "sleep 30; printf 'CANCEL-TWO\\n'"]
            record = run("cancel", f"这是取消验收，在同一轮直接发出两个独立 {args.tool}，严格分别执行："
                         + json.dumps(commands, ensure_ascii=False)
                         + "。不合并、不修改命令、不调用其他工具、无需进度前言。", handle=stop)
            if "cancelAt" not in record or record["elapsed"] - record["cancelAt"] > 10 or record["done"].get("status") != "cancelled":
                raise RuntimeError("整批工具取消没有及时收敛")
            if any("CANCEL-" in x["step"].get("result", {}).get("content", "") for x in record["tools"].values()):
                raise RuntimeError("取消后仍执行了后续输出命令")
            print("PASS 并发工具取消", round(record["elapsed"] - record["cancelAt"], 3), flush=True)

        if args.case in ("automation", "all"):
            rules = request("/api/automations", base=args.automation_base)
            record = run("automation", "这是自动任务读取验收，仅调用一次 manage_automation(action=list)，拿到结果后简短结束；不要查库或其他工具。")
            tools = list(record["tools"].values())
            if len(tools) != 1 or tools[0]["step"]["status"] != "completed":
                raise RuntimeError("自动任务读取失败")
            content = tools[0]["step"]["result"].get("content", "")
            if not rules or not all(rule["actionJson"] in content for rule in rules):
                raise RuntimeError("没有真实规则可核对或工具丢弃动作正文")
            print("PASS 自动任务完整动作指令直接返回", flush=True)
    finally:
        output = Path(tempfile.gettempdir()) / (prefix + ".json")
        output.write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding="utf-8")
        print("RECORD", output, flush=True)
        for path in files:
            request("/api/workspace/file?path=" + quote(path), method="DELETE")
        if not args.keep_sessions:
            for record in records:
                request("/api/chat/sessions/" + record["session"], method="DELETE")


if __name__ == "__main__":
    main()
