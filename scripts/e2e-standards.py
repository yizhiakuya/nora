"""真实 HTTP 验收。运行前启动单应用 Nora 与 PostgreSQL；Redis 数据源验收需独立 Redis 测试实例。

NORA_E2E_BASE_URL 默认 http://127.0.0.1:18080,NORA_AUTH_TOKEN 按需设置。
仅创建/清理带唯一前缀的测试记录,不改动既有记录。
"""
import json
import os
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread
from email.message import EmailMessage
from email.policy import HTTP
from io import BytesIO
from urllib.parse import unquote
from urllib.error import HTTPError, URLError
from urllib.request import ProxyHandler, Request, build_opener
from zipfile import ZipFile

BASE = os.environ.get("NORA_E2E_BASE_URL", "http://127.0.0.1:18080").rstrip("/")
TOKEN = os.environ.get("NORA_AUTH_TOKEN", "")
CLIENT = build_opener(ProxyHandler({}))


def request(path, method="GET", data=None, status=200, content_type="application/json", raw=False):
    headers = {"Content-Type": content_type}
    if TOKEN:
        headers["Authorization"] = "Bearer " + TOKEN
    body = data if isinstance(data, bytes) else data.encode() if isinstance(data, str) else json.dumps(data).encode() if data is not None else None
    req = Request(BASE + "/api" + path, data=body, headers=headers, method=method)
    try:
        response = CLIENT.open(req, timeout=45)
    except HTTPError as error:
        response = error
    with response:
        actual = response.status
        body = response.read()
        if raw:
            if actual != status:
                raise RuntimeError(f"{method} {path}: HTTP {actual}; expected {status}")
            return body, response.headers
        payload = json.loads(body)
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
            data("/files")
            data("/automations")
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
    print("PASS: user preferences are reachable through the application")

    before = {row["id"] for row in data("/models/providers")}
    for _ in range(2):
        request("/models/providers/probe", "POST", {"endpoint": "http://127.0.0.1:1", "apiKey": "e2e-no-secret"}, 502)
    request("/models/providers/probe", "POST", {"endpoint": "file:///invalid"}, 400)
    after = {row["id"] for row in data("/models/providers")}
    if before != after:
        raise RuntimeError("model discovery changed provider records")
    print("PASS: repeated failed model discovery creates no records")
    model_response_contract()

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
        key = name + ":value"
        try:
            data(f"/datasources/{connection_id}/query", "POST", {"sql": f'SET {key} "hello world; 中文"'})
            result = data(f"/datasources/{connection_id}/query", "POST", {"sql": f"GET {key}"})
            if result["rows"] != [["hello world; 中文"]]:
                raise RuntimeError("native Redis write/read or quoted parameters failed")
            data(f"/datasources/{connection_id}/execute", "POST", {"sql": f"EXISTS {key}"})
            request(f"/datasources/{connection_id}/query", "POST", {"sql": " "}, 400)
        finally:
            data(f"/datasources/{connection_id}/query", "POST", {"sql": f"DEL {key}"})
        print("PASS: Redis native read/write / quoted values / shared query and execute routes")
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
    postgres_execution(name)
    skill_conflicts(name)
    schedule_validation()
    downloads()


def model_response_contract():
    # 仅构造上游协议边界，解析和 HTTP 错误映射仍由真实 Nora 服务完成。
    bodies = [b"<html>login</html>", b"", b"{}", b'{"data":[{"id":7}]}', b'{"data":[]}']

    class Upstream(BaseHTTPRequestHandler):
        def do_GET(self):
            body = bodies[int(self.path.split("/")[1])]
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_args):
            pass

    with ThreadingHTTPServer(("127.0.0.1", 0), Upstream) as server:
        worker = Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            for index, expected in enumerate(["JSON", "响应为空", "data 数组", "模型 id", None]):
                payload, _ = request("/models/providers/probe", "POST", {
                    "endpoint": f"http://127.0.0.1:{server.server_port}/{index}",
                }, 502 if expected else 200)
                if expected and expected not in payload["message"]:
                    raise RuntimeError("model protocol failure did not reach the parser: " + payload["message"])
                if not expected and payload["data"]["models"] != []:
                    raise RuntimeError("valid empty model list was not preserved")
        finally:
            server.shutdown()
            worker.join()
    print("PASS: HTTP 200 malformed/empty/missing-data/invalid-id responses fail; valid empty models succeed")


def postgres_execution(name):
    connection_id = None
    table = "standards_" + uuid.uuid4().hex[:12]
    try:
        connection = data("/datasources", "POST", {
            "name": name + "-pg", "engine": "postgresql",
            "host": os.environ.get("NORA_E2E_DB_HOST", "127.0.0.1"),
            "port": int(os.environ.get("NORA_E2E_DB_PORT", "15432")),
            "database": os.environ.get("NORA_E2E_DB_NAME", "nora"),
            "username": os.environ.get("NORA_E2E_DB_USER", "nora"),
            "password": os.environ.get("NORA_DB_PASSWORD", "nora"),
        })
        connection_id = connection["id"]
        path = f"/datasources/{connection_id}"
        result = data(path + "/query", "POST", {"sql": f"CREATE TABLE public.{table}(id int PRIMARY KEY, value text); INSERT INTO public.{table} VALUES (1, '; into'); SELECT value FROM public.{table}"})
        if result["rows"] != [["; into"]]:
            raise RuntimeError("SQL batch did not return its last result")
        result = data(path + "/query", "POST", {"sql": f"UPDATE public.{table} SET value='updated' WHERE id=1 RETURNING value"})
        if result["rows"] != [["updated"]]:
            raise RuntimeError("UPDATE RETURNING did not return real data")
        data(path + "/execute", "POST", {"sql": f"EXPLAIN ANALYZE UPDATE public.{table} SET value='explained' WHERE id=1"})
        result = data(path + "/query", "POST", {"sql": f"WITH deleted AS (DELETE FROM public.{table} RETURNING value) SELECT * FROM deleted"})
        if result["rows"] != [["explained"]]:
            raise RuntimeError("data-modifying CTE was not executed")
        for count in [199, 200, 250]:
            result = data(path + "/query", "POST", {"sql": f"SELECT generate_series(1,{count})"})
            if result["rowCount"] != min(count, 200) or result["truncated"] != (count > 200):
                raise RuntimeError("result display bounds or truncation flag failed")
        request(path + "/query", "POST", {"sql": " "}, 400)
        if not data(path + "/history"):
            raise RuntimeError("execution history was not saved")
        print("PASS: SQL DDL / batch / UPDATE RETURNING / write CTE / EXPLAIN ANALYZE / bounded output / history")
    finally:
        if connection_id is not None:
            try:
                data(f"/datasources/{connection_id}/query", "POST", {"sql": f"DROP TABLE IF EXISTS public.{table}"})
            finally:
                data(f"/datasources/{connection_id}", "DELETE")


def skill_conflicts(name):
    skill = data("/skills", "POST", {"name": name, "instructions": "E2E only"})
    try:
        conflict, _ = request("/skills", "POST", {"name": name, "instructions": "duplicate"}, 409)
        if conflict.get("category") != "CONFLICT" or any(word in conflict["message"] for word in ["INSERT", "agent_skill", "SQLException"]):
            raise RuntimeError("duplicate conflict classification leaked SQL details")
        request(f'/skills/{skill["id"]}', "PUT", {"name": "x" * 101}, 400)
        request("/skills/9223372036854775807", "PUT", {"name": "missing"}, 404)
        print("PASS: duplicate skill = 409 / invalid update = 400 / missing resource = 404")
    finally:
        data(f'/skills/{skill["id"]}', "DELETE")


def schedule_validation():
    for local_time in ["9:00", "09:00", "23:59"]:
        preview = data("/automations/schedule-preview", "POST", {"triggerType": "daily", "schedule": json.dumps({"localTime": local_time, "timezone": "America/New_York"})})
        if len(preview) != 3:
            raise RuntimeError("schedule preview did not return three future runs")
    for local_time in ["24:00", "25:00", "9:60", "invalid"]:
        request("/automations/schedule-preview", "POST", {"triggerType": "daily", "schedule": json.dumps({"localTime": local_time})}, 400)
    print("PASS: schedule uses strict time validation and timezone-aware preview")


def downloads():
    ids = []
    contents = {}
    try:
        for name in ['中文 "资料".txt', "second.txt"]:
            payload = (name + "\nE2E download bytes").encode() + b"\x00\xff\r\n"
            boundary = uuid.uuid4().hex
            part = EmailMessage()
            part.add_header("Content-Disposition", "form-data", name="file", filename=name)
            part["Content-Type"] = "application/octet-stream"
            # EmailMessage normalizes body newlines; serialize only headers to retain arbitrary file bytes.
            body = b"--" + boundary.encode() + b"\r\n" + part.as_bytes(policy=HTTP) + payload + b"\r\n--" + boundary.encode() + b"--\r\n"
            item = request("/files/upload", "POST", body, content_type=f"multipart/form-data; boundary={boundary}")[0]["data"]
            ids.append(item["id"])
            contents[item["name"]] = payload
            downloaded, headers = request(f'/files/download?ids={item["id"]}', raw=True)
            if downloaded != payload or item["name"] not in unquote(headers.get("Content-Disposition", "")):
                raise RuntimeError("single download bytes or UTF-8 filename failed")
        downloaded, headers = request("/files/download?ids=" + ",".join(map(str, ids)), raw=True)
        with ZipFile(BytesIO(downloaded)) as archive:
            if {name: archive.read(name) for name in archive.namelist()} != contents:
                raise RuntimeError("ZIP download contents failed")
        print("PASS: single and ZIP downloads retain bytes and UTF-8/quoted names")
    finally:
        if ids:
            query = "?ids=" + ",".join(map(str, ids))
            data("/files" + query, "DELETE")
            data("/files/trash" + query, "DELETE")


if __name__ == "__main__":
    run()
