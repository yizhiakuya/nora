"""真实服务的 Agent 配置验收；只保存当前值，不改变用户配置。"""
import json
import os
from urllib.parse import quote
from urllib.request import ProxyHandler, Request, build_opener


def main():
    base = os.environ.get("NORA_BASE_URL", "http://127.0.0.1:18080").rstrip("/")
    token = os.environ.get("NORA_AUTH_TOKEN", "")
    headers = {"Content-Type": "application/json", "Cache-Control": "no-cache"}
    if token:
        headers["Authorization"] = "Bearer " + token
    opener = build_opener(ProxyHandler({}))

    def api(path, patch=None):
        body = json.dumps(patch).encode() if patch is not None else None
        with opener.open(Request(base + "/api" + path, body, headers, method="PUT" if body is not None else "GET"), timeout=20) as response:
            return json.load(response)["data"]

    settings = api("/chat/settings")
    patch = {key: settings.get(key) for key in ("permissionMode", "reasoningLevel")}
    assert api("/chat/settings", patch) == settings, "Agent 配置保存不一致"
    assert api("/chat/settings") == settings, "Agent 配置刷新不一致"
    preferences = api("/user-preferences")
    assert api("/user-preferences", preferences) == preferences, "成果偏好保存不一致"
    assert api("/user-preferences") == preferences, "成果偏好刷新不一致"
    assert isinstance(api("/models/providers"), list), "模型列表读取失败"
    files = api("/workspace/files")
    for path in ("SOUL.md", "AGENTS.md", "USER.md", "MEMORY.md"):
        assert any(entry["path"] == path and not entry["directory"] for entry in files), path + " 未列出"
        assert isinstance(api("/workspace/file?path=" + quote(path))["content"], str), path + " 无法读取"
    for path in ("/skills", "/mcp/servers", "/chat/settings/env-vars", "/network/proxy"):
        api(path)
    print("PASS Agent 配置与成果偏好保存/刷新；模型、技能、连接、环境与四个指令文件读取")


if __name__ == "__main__":
    main()
