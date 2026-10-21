"""Use the real model and configured CLI to verify the Agent's execution perspective."""
import argparse
import json
import os
from pathlib import Path
import time
from urllib.request import ProxyHandler, Request, build_opener


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://127.0.0.1:18080")
    parser.add_argument("--provider", type=int, required=True)
    parser.add_argument("--reasoning", default="max")
    parser.add_argument("--tool", choices=("sh", "powershell"), default="sh")
    parser.add_argument("--login", required=True)
    args = parser.parse_args()
    opener = build_opener(ProxyHandler({}))
    headers = {"Content-Type": "application/json"}
    if os.getenv("NORA_AUTH_TOKEN"):
        headers["Authorization"] = "Bearer " + os.environ["NORA_AUTH_TOKEN"]
    stamp = str(time.time_ns())
    record = {"checks": [], "turns": []}
    report = Path("logs/system-agent-e2e/" + stamp + ".json")
    report.parent.mkdir(parents=True, exist_ok=True)

    def save():
        report.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")

    def api(path, data=None):
        body = None if data is None else json.dumps(data, ensure_ascii=False).encode()
        with opener.open(Request(args.base.rstrip("/") + "/api" + path, body, headers), timeout=30) as response:
            value = json.load(response)
        return value.get("data", value) if isinstance(value, dict) else value

    def check(condition, label):
        record["checks"].append({"passed": bool(condition), "label": label})
        save()
        print("PASS" if condition else "FAIL", label, flush=True)
        if not condition:
            raise RuntimeError(label)

    provider = next(p for p in api("/models/providers") if p["id"] == args.provider)

    def turn(label, content):
        session = "system-agent-" + stamp + "-" + label
        item = {"session": session, "tools": {}, "errors": []}
        record["turns"].append(item)
        payload = {"content": content, "providerId": args.provider, "model": provider["models"][0],
                   "reasoningLevel": args.reasoning, "permissionMode": "full"}
        started = time.monotonic()
        try:
            with opener.open(Request(args.base.rstrip("/") + "/api/chat/sessions/" + session + "/messages",
                                     json.dumps(payload, ensure_ascii=False).encode(), headers), timeout=300) as response:
                event = ""
                for raw in response:
                    line = raw.decode().strip()
                    if line.startswith("event:"):
                        event = line[6:].strip()
                    elif line.startswith("data:"):
                        data = json.loads(line[5:])
                        if event == "step" and data.get("toolName"):
                            item["tools"][data["id"]] = data
                            save()
                            if len(item["tools"]) > 10:
                                raise RuntimeError("Unexpected exploratory tool loop")
                        elif event == "error":
                            item["errors"].append(data)
                        elif event in ("question_required", "approval_required"):
                            item[event] = data
                            raise RuntimeError("Configured read-only CLI query unexpectedly requires user input")
        finally:
            live = api("/chat/sessions/" + session + "/turn/live")
            if live.get("active"):
                api("/chat/sessions/" + session + "/cancel", {})
            item["durationSeconds"] = round(time.monotonic() - started, 2)
            item["messages"] = api("/chat/sessions/" + session + "/messages")
            save()
        answer = item["messages"][-1]
        check(not item["errors"] and answer.get("runStatus") == "completed", label + " 完成且无错误")
        print("TURN", session, "tools=" + str(len(item["tools"])), "seconds=" + str(item["durationSeconds"]), flush=True)
        return item, answer

    item, answer = turn("runtime", "根据本次注入的运行环境，说明命令工具、执行域、用户主目录及 gh 路径。直接回答，不执行工具，也不读取历史记录。")
    text = answer["content"]
    check(not item["tools"], "运行环境快照足够回答，无额外环境探索")
    check(args.tool in text and "gh" in text and "/app/data/tools/bin" in text, "快照识别已配置 PATH 中的 gh")
    if args.tool == "sh":
        check("容器" in text and "/app" in text, "执行域与用户目录真实，不将容器当作宿主机")

    item, answer = turn("repos", "看下我gh仓库")
    tools = list(item["tools"].values())
    check(tools and tools[0]["toolName"] == args.tool, "原始要求首先使用当前系统命令")
    check(all(t["toolName"] in (args.tool, "command_execution") for t in tools), "使用直接 CLI，不搜索历史、浏览器或内部管理接口")
    commands = "\n".join(str(t.get("input", {})) for t in tools if t["toolName"] == args.tool)
    check("gh" in commands and all(word not in commands for word in ("grep", "find ", "hosts.yml", "oauth_token")), "账号和仓库通过 CLI 查询，不扫描配置或提取令牌")
    check(args.login in answer["content"] and len(answer["content"]) > 100, "回答包含真实登录账号和仓库结果")
    check(all(t.get("status") == "completed" for t in tools), "实际工具调用全部完成")
    print("REPORT", report, flush=True)


if __name__ == "__main__":
    main()
