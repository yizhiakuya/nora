"""真实图片上传、原生图像请求、运行中插入与历史回读；中继只透传真实模型响应。"""
import base64
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import threading
import time
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import ProxyHandler, Request, build_opener
import uuid

from PIL import Image, ImageDraw, ImageFont


def main():
    stamp = str(time.time_ns())
    base = os.environ.get("NORA_E2E_BASE_URL", "http://127.0.0.1:18080").rstrip("/")
    database = os.environ.get("NORA_E2E_DB_NAME", "")
    if not database.startswith(("nora_e2e_", "nora_simplify_")) or not database.replace("_", "").isalnum():
        raise RuntimeError("An explicitly isolated NORA_E2E_DB_NAME is required")
    auth = {"Authorization": "Bearer " + os.environ.get("NORA_AUTH_TOKEN", "")}
    opener = build_opener(ProxyHandler({}))
    report = {"checks": [], "turns": [], "requests": []}
    output = Path("logs/chat-images-e2e-" + stamp + ".json")
    picture = Path("logs/chat-image-paste-fixture.png")
    image = Image.new("RGB", (800, 350), "white")
    draw = ImageDraw.Draw(image)
    draw.text((45, 40), "827193", font=ImageFont.truetype("C:/Windows/Fonts/arial.ttf", 90), fill="black")
    draw.rectangle((45, 185, 755, 310), fill="blue")
    image.save(picture)
    expected = hashlib.sha256(picture.read_bytes()).hexdigest()

    def check(value, label):
        report["checks"].append({"label": label, "passed": bool(value)})
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf8")
        if not value:
            raise AssertionError(label)
        print("PASS", label, flush=True)

    def api(path, data=None, method=None):
        with opener.open(Request(base + path, None if data is None else json.dumps(data, ensure_ascii=False).encode(),
                                 {"Content-Type": "application/json", **auth}, method=method), timeout=30) as response:
            result = json.load(response)
            return result.get("data", result) if isinstance(result, dict) else result

    def sql(query):
        return subprocess.run(["rtk", "proxy", "docker", "exec", "nora-postgres", "psql", "-U", "nora", "-d", database, "-At", "-c", query],
                              check=True, capture_output=True, text=True, encoding="utf8").stdout

    model = "deepseek-v4.1-flash"
    source = next(p for p in api("/api/models/providers") if p["name"] == "nora-a" and p["enabled"])
    url = urlsplit(source["endpoint"])
    origin = url.scheme + "://" + url.netloc
    provider = int(next(line for line in sql("INSERT INTO schema_agent.model_provider(name,protocol,endpoint,api_key,enabled,models,status,model_settings) "
        f"SELECT 'image-e2e-{stamp}',protocol,endpoint,api_key,true,models,status,model_settings FROM schema_agent.model_provider "
        f"WHERE id={source['id']} RETURNING id;").splitlines() if line.isdigit()))
    gate = threading.Event()
    entered = threading.Event()

    class Relay(BaseHTTPRequestHandler):
        hold_next = False

        def log_message(self, *_):
            pass

        def do_POST(self):
            body = self.rfile.read(int(self.headers["Content-Length"]))
            payload = json.loads(body)
            blocks = []
            def collect(parts, role):
                for part in parts:
                    if not isinstance(part, dict):
                        continue
                    if part.get("type") == "image":
                        blocks.append({"role": role, "mime": part["source"]["media_type"],
                            "hash": hashlib.sha256(base64.b64decode(part["source"]["data"])).hexdigest()})
                    if isinstance(part.get("content"), list):
                        collect(part["content"], role)
            for message in payload.get("messages", []):
                content = message.get("content", [])
                if isinstance(content, list):
                    collect(content, message["role"])
            report["requests"].append({"path": self.path, "images": blocks})
            if Relay.hold_next:
                Relay.hold_next = False
                entered.set()
                if not gate.wait(30):
                    raise TimeoutError("steering gate was not released")
            headers = {k: v for k, v in self.headers.items() if k.lower() not in ("host", "content-length", "connection")}
            try:
                response = opener.open(Request(origin + self.path, body, headers), timeout=180)
            except HTTPError as error:
                response = error
            with response:
                self.send_response(response.status)
                self.send_header("Content-Type", response.headers.get("Content-Type", "text/event-stream"))
                self.end_headers()
                while chunk := response.read1(8192):
                    self.wfile.write(chunk)
                    self.wfile.flush()

    relay = ThreadingHTTPServer(("127.0.0.1", 0), Relay)
    threading.Thread(target=relay.serve_forever, daemon=True).start()
    file_id = None
    try:
        api(f"/api/models/providers/{provider}", {"endpoint": f"http://127.0.0.1:{relay.server_port}" + url.path.rstrip("/"),
            "modelSettings": {model: {"protocol": "anthropic"}}}, "PUT")
        boundary = uuid.uuid4().hex
        raw = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="image-qa.png"\r\nContent-Type: image/png\r\n\r\n'.encode()
               + picture.read_bytes() + f"\r\n--{boundary}--\r\n".encode())
        with opener.open(Request(base + "/api/files/upload", raw,
                                 {"Content-Type": "multipart/form-data; boundary=" + boundary, **auth}), timeout=30) as response:
            file_id = json.load(response)["data"]["id"]
        check(bool(file_id), "实际图片上传到文件服务")
        ref = f"\n\n[引用文件] image-qa.png (file_id={file_id}) —— 内容已随消息提供"

        def run(label, prompt, context=None, session=None):
            session = session or "chat-images-" + stamp + "-" + label
            sql(f"INSERT INTO schema_agent.chat_session(id,title,title_generated) VALUES ('{session}','图片输入验收 · {label}',true) ON CONFLICT DO NOTHING;")
            item = {"session": session, "label": label, "errors": [], "answer": ""}
            report["turns"].append(item)
            payload = {"content": prompt, "model": model, "providerId": provider, "reasoningLevel": "none", "permissionMode": "full"}
            if context:
                payload["context"] = context
            print("TURN", label, session, flush=True)
            with opener.open(Request(base + f"/api/chat/sessions/{session}/messages", json.dumps(payload, ensure_ascii=False).encode(),
                                     {"Content-Type": "application/json", **auth}), timeout=180) as response:
                event = ""
                for raw in response:
                    line = raw.decode().strip()
                    if line.startswith("event:"):
                        event = line[6:].strip()
                    elif line.startswith("data:"):
                        data = json.loads(line[5:])
                        if event == "error":
                            item["errors"].append(data)
                        elif event == "done":
                            item["done"] = data
            history = api(f"/api/chat/sessions/{session}/messages")
            item["answer"] = next((m.get("content", "") for m in reversed(history) if m["role"] == "assistant"), "")
            return item

        item = run("direct", "读取所附图片顶部的六位数字和底部色块颜色。只回答数字和颜色，不调用工具。" + ref,
                   {"version": 1, "refs": [{"kind": "file", "id": str(file_id), "label": "image-qa.png"}]})
        check(not item["errors"] and "827193" in item["answer"] and "蓝" in item["answer"], "真实模型从图片识别数字和颜色")
        check(len(report["requests"][0]["images"]) == 1 and report["requests"][0]["images"][0]["hash"] == expected,
              "结构化引用与正文引用去重，原生 Anthropic 图像字节一致")
        history = api(f"/api/chat/sessions/{item['session']}/messages")
        check("file_id=" in json.dumps(history) and "data:image" not in json.dumps(history), "历史只保存稳定文件引用")
        previous = len(report["requests"])
        reread = run("history", "用 manage_file 重新读取之前引用的图片，告诉我图片数字。", session=item["session"])
        check(not reread["errors"] and "827193" in reread["answer"] and
              any(r["images"] for r in report["requests"][previous:]), "下一轮通过真实文件工具回读历史图片")

        Relay.hold_next = True
        session = "chat-images-" + stamp + "-insert"
        previous = len(report["requests"])
        worker = threading.Thread(target=run, args=("insert", "先只回答准备好了。", None, session))
        worker.start()
        try:
            check(entered.wait(30), "插入测试已进入真实模型请求")
            turn = api(f"/api/chat/sessions/{session}/turn/live")
            api(f"/api/chat/sessions/{session}/steer", {"turnId": turn["turnId"], "messageId": str(uuid.uuid4()),
                "content": "补充图片：读取顶部六位数字和底部颜色，只回答数字和颜色，不调用工具。" + ref})
        finally:
            gate.set()
        worker.join(180)
        inserted = report["turns"][-1]
        check(not worker.is_alive() and not inserted["errors"] and "827193" in inserted["answer"] and "蓝" in inserted["answer"],
              "运行中插入的图片进入下一次请求并正确识别")
        check(any(image["hash"] == expected for r in report["requests"][previous + 1:] for image in r["images"]),
              "插入引用实际传入原生图像输入")
    finally:
        gate.set()
        api(f"/api/models/providers/{provider}", method="DELETE")
        relay.shutdown()
        if file_id:
            with opener.open(Request(base + f"/api/files?ids={file_id}", headers=auth, method="DELETE"), timeout=30):
                pass
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf8")
        print("REPORT", output, flush=True)


if __name__ == "__main__":
    main()
