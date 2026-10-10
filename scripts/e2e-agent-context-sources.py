"""真实模型验收环境感知、单行记录回读、按行兼容及现有 MCP；不修改模型配置。"""
import argparse
import json
import os
from pathlib import Path
import re
import time
from urllib.parse import quote
from urllib.request import ProxyHandler, Request, build_opener
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default=os.getenv("NORA_BASE_URL", "http://127.0.0.1:18083"))
    parser.add_argument("--tool", choices=["sh", "powershell"], default="powershell")
    parser.add_argument("--mcp", action="store_true")
    parser.add_argument("--case", choices=["all", "single-line"], default="all")
    args = parser.parse_args()
    headers = {"Content-Type": "application/json"}
    if os.getenv("NORA_AUTH_TOKEN"):
        headers["Authorization"] = "Bearer " + os.environ["NORA_AUTH_TOKEN"]
    opener = build_opener(ProxyHandler({}))
    stamp = str(time.time_ns())
    report = Path("logs/context-sources-" + stamp + ".json")
    report.parent.mkdir(parents=True, exist_ok=True)
    record = {"checks": [], "turns": []}

    def api(path, data=None, method=None):
        body = None if data is None else json.dumps(data, ensure_ascii=False).encode()
        with opener.open(Request(args.base.rstrip("/") + "/api" + path, body, headers, method=method), timeout=30) as response:
            value = json.load(response)
            return value.get("data", value) if isinstance(value, dict) else value

    def check(condition, label):
        record["checks"].append({"label": label, "passed": bool(condition)})
        report.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
        print("PASS" if condition else "FAIL", label, flush=True)

    provider = next(p for p in api("/models/providers") if p.get("enabled") and p.get("models"))
    model = provider["models"][0]

    def turn(label, prompt):
        session = "context-sources-" + stamp + "-" + label
        item = {"session": session, "tools": {}, "errors": []}
        record["turns"].append(item)
        payload = {"content": prompt, "model": model, "providerId": provider["id"],
                   "reasoningLevel": "none", "permissionMode": "full"}
        started = time.monotonic()
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
                        item["tools"][data["id"]] = {k: data.get(k) for k in ("toolName", "status", "input", "duration", "result")}
                    elif event == "error":
                        item["errors"].append(data)
        item["durationSeconds"] = round(time.monotonic() - started, 2)
        messages = api("/chat/sessions/" + session + "/messages")
        item["answer"] = messages[-1]["content"]
        check(not item["errors"] and messages[-1].get("runStatus") == "completed", label + " 完成且无错误")
        return item

    files_before = {name: api("/workspace/file?path=" + quote(name))["content"] for name in ("AGENTS.md", "USER.md", "MEMORY.md")}
    if args.case == "all":
        runtime = turn("runtime", "根据本次已提供的环境快照，简短说明当前命令工具、执行账户、Agent 进程 PATH 是否有 node、python3、uv。不要执行命令，不改记忆。")
        check(not runtime["tools"] and args.tool in runtime["answer"].lower(), "直接使用当前环境上下文，无探测调用")

    code = uuid.uuid4().hex
    command = ("code=$(cat /proc/sys/kernel/random/uuid | tr -d '-'); printf '%40000s' '' | tr ' ' a; printf 'CODE=%s|' \"$code\"; printf '%40000s' '' | tr ' ' b") if args.tool == "sh" else (
        "$code=[guid]::NewGuid().ToString('N'); Write-Output ('a' * 40000 + 'CODE=' + $code + '|' + 'b' * 40000)")
    evidence = turn("single-line", "这是一次性的单行长输出验收：仅执行一次命令工具，命令原样为：\n" + command
        + "\n找出输出中段的 CODE 值。被截断时从工具实际给出的完整记录路径回读；"
        + "目标在约第 40001 个字符处，按记录的分页单位读取。不得重跑命令，不扫描其他文件，不写记忆。最终只回答 CODE。")
    tools = list(evidence["tools"].values())
    paths = set(re.findall(r"context/tool-[a-f0-9]+\.txt", json.dumps(tools)))
    codes = [match.group(1) for path in paths
             for match in re.finditer(r"CODE=([a-f0-9]{32})", api("/workspace/file?path=" + quote(path))["content"])]
    check(bool(codes) and any(value in evidence["answer"] for value in codes), "单行记录中段真实回读准确")
    check(sum(t["toolName"] == args.tool for t in tools) == 1, "一次性发令命令没有重放")
    check(any(t["toolName"] == "manage_workspace" and '"unit":"chars"' in
              json.dumps(t.get("input", {}), ensure_ascii=False).replace(" ", "").replace('\\"', '"') for t in tools), "使用通用字符分页回读")
    check(all(t["status"] == "completed" for t in tools), "回读过程无失败工具")

    if args.case == "all":
        path = "context-e2e/" + stamp + "-lines.txt"
        api("/workspace/file", {"path": path, "content": "第一行\nSECOND=" + code + "\n第三行\n"}, "PUT")
        try:
            lines = turn("lines", "用 manage_workspace read 读取 " + path + "，offset=2、limit=1，默认按行读取。只回答第二行，不写记忆。")
            check("SECOND=" + code in lines["answer"] and "第一行" not in lines["answer"], "原有按行分页保持正确")
        finally:
            api("/workspace/file?path=" + quote(path), method="DELETE")

    if args.mcp:
        item = turn("mcp", "核实现有 context7 MCP 是否可用，真实调用一次 resolve-library-id 查询 React 库。不新增或修改 MCP，不探测其他服务，不写记忆。")
        tools = list(item["tools"].values())
        check(any(t["toolName"].startswith("mcp__context7__") or
                  (t["toolName"] == "manage_mcp" and '"call"' in str(t["input"]).replace('\\"', '"')) for t in tools), "现有 Context7 真实调用成功")
        check(all(t["status"] == "completed" for t in tools), "MCP 核实无工具失败")
        check(not any(t["toolName"] in ("manage_skill", "search_knowledge", "manage_knowledge", "manage_file", args.tool) for t in tools), "MCP 核实不搜索缺失手册、空资料库或运行时")
    check(all(api("/workspace/file?path=" + quote(name))["content"] == text for name, text in files_before.items()), "验收没有修改用户指令和记忆")
    print("RECORD", report.resolve(), flush=True)
    assert all(check["passed"] for check in record["checks"]), "上下文来源验收存在失败项"


if __name__ == "__main__":
    main()
