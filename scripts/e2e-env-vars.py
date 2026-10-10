"""真实模型、数据库、命令和 MCP SDK 的环境变量工具验收。

依赖测试机已有 mcp==2.2.0（含 uvicorn）；远端容器访问测试机时传 --mcp-host。
只使用独立测试变量，不输出凭据或修改已有 MCP。
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import socket
import sys
import threading
import time
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request, build_opener

from mcp.server.mcpserver import MCPServer
from mcp.server.transport_security import TransportSecuritySettings
from starlette.responses import JSONResponse
from starlette.middleware.base import BaseHTTPMiddleware
import uvicorn


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://127.0.0.1:18083")
    parser.add_argument("--tool", choices=["powershell", "sh"], default="powershell")
    parser.add_argument("--mcp-host", default="127.0.0.1")
    args = parser.parse_args()
    opener = build_opener(ProxyHandler({}))
    headers = {"Content-Type": "application/json"}
    if os.getenv("NORA_AUTH_TOKEN"):
        headers["Authorization"] = "Bearer " + os.environ["NORA_AUTH_TOKEN"]
    root = args.base.rstrip("/") + "/api"
    stamp = str(time.time_ns())
    session = "env-vars-e2e-" + stamp
    prefix = "NORA_E2E_" + stamp
    key, public, blocked = (prefix + suffix for suffix in ("_CREDENTIAL", "_URL", "_BLOCKED"))
    env_path = "/chat/settings/env-vars"
    messages_path = "/chat/sessions/" + session + "/messages"
    keys = [key, public, blocked] + [prefix + "_" + str(i) for i in range(8)]
    secret = 'test-credential-"quote\\path$7${' + keys[3] + '}'
    report = {"session": session, "checks": [], "turns": []}
    report_path = Path("logs/" + session + ".json")
    report_path.parent.mkdir(parents=True, exist_ok=True)
    server_id = None

    def save():
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    def check(condition, label):
        report["checks"].append({"label": label, "passed": bool(condition)})
        save()
        print("PASS" if condition else "FAIL", label, flush=True)
        if not condition:
            raise RuntimeError(label)

    def api(path, body=None, method=None):
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        with opener.open(Request(root + path, data, headers, method=method), timeout=45) as response:
            result = json.load(response)
            return result.get("data", result) if isinstance(result, dict) else result

    provider = next(p for p in api("/models/providers") if p.get("enabled") and p.get("models"))
    baseline = api(env_path)

    def turn(prompt, permission="full", approval=False):
        tools, approvals, errors = {}, [], []
        payload = {"content": prompt + "\n这是隔离验收，不读技能、文件或记忆，也不记录新记忆。不要复述变量原值。",
                   "providerId": provider["id"], "model": provider["models"][0],
                   "reasoningLevel": "none", "permissionMode": permission}
        started = time.monotonic()
        with opener.open(Request(root + messages_path, json.dumps(payload, ensure_ascii=False).encode(), headers), timeout=180) as response:
            event = ""
            for raw in response:
                line = raw.decode().strip()
                if line.startswith("event:"):
                    event = line[6:].strip()
                elif line.startswith("data:"):
                    data = json.loads(line[5:])
                    if event == "step" and data.get("toolName"):
                        tools[data["id"]] = data
                    elif event == "error":
                        errors.append(data)
                    elif event == "approval_required":
                        approvals.append(data)
                        api("/chat/sessions/" + session + "/cancel", {}, "POST")
                        break
        report["turns"].append({"seconds": round(time.monotonic() - started, 2),
                                 "tools": [{"name": t["toolName"], "status": t["status"]} for t in tools.values()],
                                 "approvalCount": len(approvals), "errorCount": len(errors)})
        save()
        check(bool(approvals) if approval else not errors and not approvals, "轮次按权限正常执行")
        return tools, approvals

    def only_env(tools):
        return tools and all(t["toolName"] == "manage_env_vars" and t["status"] == "completed" for t in tools.values())

    with socket.socket() as port_probe:
        port_probe.bind(("0.0.0.0", 0))
        port = port_probe.getsockname()[1]
    sdk = MCPServer("environment-variables-e2e")

    @sdk.tool()
    def credential_probe() -> dict:
        """仅返回测试连接是否工作，不返回凭据。"""
        return {"ok": True}

    app = sdk.streamable_http_app(stateless_http=True, json_response=True, host="0.0.0.0",
                                  transport_security=TransportSecuritySettings(enable_dns_rebinding_protection=False))
    matched_headers = []

    async def authenticate(request, call_next):
        matched = request.headers.get("authorization") == "Bearer " + secret
        matched_headers.append(matched)
        if not matched:
            return JSONResponse({"error": "invalid test credential"}, status_code=401)
        return await call_next(request)

    app.add_middleware(BaseHTTPMiddleware, dispatch=authenticate)

    server = uvicorn.Server(uvicorn.Config(app, host="0.0.0.0", port=port, log_level="error", access_log=False))
    server_thread = threading.Thread(target=server.run, daemon=True)
    server_thread.start()
    deadline = time.monotonic() + 10
    while not server.started and server_thread.is_alive() and time.monotonic() < deadline:
        time.sleep(.05)

    try:
        check(server.started, "官方 MCP SDK 测试服务已启动")
        tools, _ = turn("保存一个工作台环境变量，变量名 " + key + "，值为字符串 "
                        + json.dumps("first-fixture-value") + "，按默认密钥处理。只需保存这一项并确认。")
        check(only_env(tools), "模型使用原生变量工具，不探索接口或执行命令")
        saved = next(v for v in api(env_path) if v["key"] == key)
        check(saved["secret"] is True and saved["value"] == "••••••", "省略 secret 默认隐藏原值，设置页可读到同一变量")
        check("first-fixture-value" not in json.dumps(list(tools.values())), "实时工具参数、标题及结果隐藏原值")
        api(env_path + "/" + key, {"value": secret, "note": "SDK credential fixture"}, "PUT")
        api(env_path + "/" + public, {"value": "http://" + args.mcp_host + ":" + str(port) + "/mcp", "secret": False}, "PUT")
        with ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(lambda k: api(env_path + "/" + k, {"value": "concurrent-" + k, "secret": False}, "PUT"), keys[3:]))
        current = api(env_path)
        check(all(any(v["key"] == k for v in current) for k in keys if k != blocked), "并发单项写入不丢变量")
        check([v for v in current if not v["key"].startswith(prefix)] == baseline, "新增和更新保留全部已有变量")

        for body in ({"value": "••••••"}, {"value": ""}, {"value": "x\u0000y"}):
            try:
                api(env_path + "/" + key, body, "PUT")
                check(False, "非法变量值被拒绝")
            except HTTPError as error:
                check(error.code == 400, "非法变量值被拒绝")
        before = api(env_path)
        try:
            api(env_path, {"vars": [{"key": key, "value": "must-not-save"}, {"key": "9INVALID", "value": "invalid"}]}, "PUT")
            check(False, "全量替换校验失败回滚")
        except HTTPError as error:
            check(error.code == 400 and api(env_path) == before, "全量替换校验失败回滚")

        tools, _ = turn("只调用 manage_env_vars action=list，查看已存变量；不要读取真实密钥。", "assist")
        check(only_env(tools) and secret not in json.dumps(list(tools.values())), "查询是只读操作，结果已脱敏")
        commands = ("[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($env:" + key + "))).ToLower()"
                    if args.tool == "powershell" else "printf '%s' \"$" + key + "\" | sha256sum")
        tools, _ = turn("只调用一次 " + args.tool + "，command 原样为：\n" + commands
                        + "\n命令完成后只确认执行完毕，不重试或输出密钥。")
        check(any(t["toolName"] == args.tool and t["status"] == "completed"
                  and hashlib.sha256(secret.encode()).hexdigest() in json.dumps(t.get("result")) for t in tools.values()),
              "命令收到精确变量值，包括引号、反斜线和占位符")
        fixture = api("/mcp/servers", {"name": "env-vars-e2e-" + stamp, "url": "${" + public + "}",
                                       "transport": "STREAMABLE", "headers": {"Authorization": "Bearer ${" + key + "}"}}, "POST")
        server_id = fixture["id"]
        refreshed = api("/mcp/servers/" + str(server_id) + "/refresh", {}, "POST")
        check(refreshed["status"] == "connected" and len(matched_headers) >= 2 and all(matched_headers),
              "MCP 真实握手和工具发现使用引用，JSON 替换不损坏值")
        tools, _ = turn("只调用一次 manage_mcp，action=call，target=" + str(server_id)
                        + "，tool=credential_probe，arguments={}。返回成功则结束。")
        check(any(t["toolName"] == "manage_mcp" and t["status"] == "completed"
                  and 'true' in json.dumps(t.get("result")) for t in tools.values()), "真实 MCP 工具可调用")
        _, approvals = turn("只调用 manage_env_vars，action=set，key=" + blocked
                            + "，value=blocked-fixture-value。", "assist", approval=True)
        check(not any(v["key"] == blocked for v in api(env_path))
              and "blocked-fixture-value" not in json.dumps(approvals), "审批展示隐藏变量值，批准前没有写入")
        # 等取消完成，避免随后删除轮次进入排队。
        time.sleep(.5)
        tools, _ = turn("只调用 manage_env_vars，action=delete，key=" + key + "。删除后确认，不做其他操作。")
        check(only_env(tools) and not any(v["key"] == key for v in api(env_path)), "模型可以删除指定变量")
        history = api(messages_path)
        steps = [s for message in history for s in message.get("steps", []) if s.get("toolName") == "manage_env_vars"]
        check("first-fixture-value" not in json.dumps(steps) and secret not in json.dumps(steps), "持久化历史中的变量参数和结果已脱敏")
    finally:
        if server_id is not None:
            api("/mcp/servers/" + str(server_id), method="DELETE")
        for name in keys:
            api(env_path + "/" + name, method="DELETE")
        server.should_exit = True
        server_thread.join(5)
        check(api(env_path) == baseline, "清理测试变量后原有配置完全一致")
        save()
    print("REPORT", report_path.resolve(), flush=True)


if __name__ == "__main__":
    main()
