"""真实 HTTP 验收。运行前启动 gateway/agent/datasource 与本地 Redis。

NORA_E2E_BASE_URL 默认 http://127.0.0.1:18080,NORA_AUTH_TOKEN 按需设置。
仅创建/清理带唯一前缀的测试记录,不改动既有记录。
"""
import json
import os
import time
import uuid
from urllib.error import HTTPError, URLError
from urllib.request import ProxyHandler, Request, build_opener

BASE = os.environ.get("NORA_E2E_BASE_URL", "http://127.0.0.1:18080").rstrip("/")
TOKEN = os.environ.get("NORA_AUTH_TOKEN", "")
CLIENT = build_opener(ProxyHandler({}))


def request(path, method="GET", data=None, status=200, content_type="application/json"):
    headers = {"Content-Type": content_type}
    if TOKEN:
        headers["Authorization"] = "Bearer " + TOKEN
    body = data.encode() if isinstance(data, str) else json.dumps(data).encode() if data is not None else None
    req = Request(BASE + "/api" + path, data=body, headers=headers, method=method)
    try:
        response = CLIENT.open(req, timeout=45)
    except HTTPError as error:
        response = error
    with response:
        actual = response.status
        payload = json.loads(response.read())
        if actual != status or payload.get("code") != (0 if status == 200 else status):
            raise RuntimeError(f"{method} {path}: HTTP {actual}, code {payload.get('code')}; expected {status}")
        return payload, response.headers


def data(path, method="GET", body=None):
    return request(path, method, body)[0]["data"]


def run():
    deadline = time.monotonic() + 120
    while True:
        try:
            data("/models/providers")
            data("/datasources")
            break
        except (URLError, RuntimeError, TimeoutError):
            if time.monotonic() >= deadline:
                raise
            time.sleep(2)

    request("/datasources", "POST", "{", 400)
    request("/datasources/not-a-number/schema", status=400)
    _, headers = request("/datasources/not-a-number", status=405)
    if "DELETE" not in headers.get("Allow", ""):
        raise RuntimeError("405 lost the Allow header")
    request("/datasources", "POST", "{}", 415, "text/plain")
    print("PASS: malformed JSON / path parameter / method / media type retain HTTP semantics")

    if not isinstance(data("/user-preferences"), dict):
        raise RuntimeError("user preferences did not reach the agent service")
    print("PASS: user preferences are reachable through the gateway")

    before = {row["id"] for row in data("/models/providers")}
    for _ in range(2):
        request("/models/providers/probe", "POST", {"endpoint": "http://127.0.0.1:1", "apiKey": "e2e-no-secret"}, 502)
    request("/models/providers/probe", "POST", {"endpoint": "file:///invalid"}, 400)
    after = {row["id"] for row in data("/models/providers")}
    if before != after:
        raise RuntimeError("model discovery changed provider records")
    print("PASS: repeated failed model discovery creates no records")

    name = "standards-e2e-" + uuid.uuid4().hex[:12]
    provider_id = connection_id = None
    try:
        provider = data("/models/providers", "POST", {
            "name": name, "protocol": "openai", "endpoint": "http://127.0.0.1:1",
            "apiKey": "e2e-key-never-used", "models": [],
        })
        provider_id = provider["id"]
        provider = data(f"/models/providers/{provider_id}", "PUT", {"enabled": False, "name": name + "-edited"})
        stored = next(row for row in data("/models/providers") if row["id"] == provider_id)
        if stored["enabled"] or stored["name"] != name + "-edited" or "e2e-key-never-used" in json.dumps(stored):
            raise RuntimeError("provider update/masking was not persisted")
        request("/models/providers/probe", "POST", {"providerId": provider_id, "endpoint": "file:///draft"}, 400)
        print("PASS: provider CRUD persists, masks credentials, and probe uses the draft endpoint")

        connection = data("/datasources", "POST", {
            "name": name, "engine": "redis", "host": os.environ.get("NORA_E2E_REDIS_HOST", "127.0.0.1"),
            "port": int(os.environ.get("NORA_E2E_REDIS_PORT", "16379")),
            "database": "0", "username": "", "password": os.environ.get("NORA_E2E_REDIS_PASSWORD", ""),
        })
        connection_id = connection["id"]
        tested = data(f"/datasources/{connection_id}/test", "POST")
        if not tested["ok"]:
            raise RuntimeError("real Redis connectivity test failed")
        result = data(f"/datasources/{connection_id}/query", "POST", {"sql": "INFO"})
        if result["rowCount"] < 1:
            raise RuntimeError("real Redis query returned no data")
        request(f"/datasources/{connection_id}/query", "POST", {"sql": "SET standards-e2e forbidden"}, 400)
        print("PASS: datasource creation / real connection / read query / write rejection")
    finally:
        try:
            if connection_id is not None:
                data(f"/datasources/{connection_id}", "DELETE")
        finally:
            if provider_id is not None:
                data(f"/models/providers/{provider_id}", "DELETE")
    if any(row["id"] == provider_id for row in data("/models/providers")) or any(row["id"] == connection_id for row in data("/datasources")):
        raise RuntimeError("deleted test records remained visible")
    print("PASS: deleted records disappear from authoritative lists")


if __name__ == "__main__":
    run()
