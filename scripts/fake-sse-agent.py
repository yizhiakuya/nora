#!/usr/bin/env python3
# F2 确定性复现的受控 SSE 端点:按会话 id(auto-rule-N)返回不同流型。
# 与审查报告 F2 的隔离复现同款——真实 automation-service 的 ActionExecutor
# 打这个假 agent-service,验证终态判定不再依赖「有文字」。
import re
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

# rule id -> 流型
CASES = {
    "auto-rule-30": "partial-error",   # 部分 delta → error(无 done)
    "auto-rule-31": "eof",             # 部分 delta → EOF(无 done)
    "auto-rule-32": "stopped",         # 部分 delta → done(stopped)
    "auto-rule-33": "done-completed",  # delta → done(completed)
    "auto-rule-34": "done-partial",    # delta → done(partial)
    "auto-rule-35": "done-failed",     # delta → done(failed)
    "auto-rule-36": "legacy-done",     # 旧后端:done 无 status 字段(应 unknown)
}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"  # 无 Content-Length 时客户端读到 EOF 结束

    def do_POST(self):
        m = re.match(r"/api/chat/sessions/([^/]+)/messages", self.path)
        sid = m.group(1) if m else ""
        case = CASES.get(sid, "done-completed")
        length = int(self.headers.get("Content-Length", 0))
        self.rfile.read(length)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()

        def w(s):
            self.wfile.write(s.encode("utf-8"))
            self.wfile.flush()

        w('event:delta\ndata:{"content":"部分回答内容"}\n\n')
        time.sleep(0.1)
        if case == "partial-error":
            w('event:error\ndata:{"message":"模型调用失败"}\n\n')
        elif case == "eof":
            pass  # 直接关连接:无终态
        elif case == "stopped":
            w('event:done\ndata:{"stopped":true,"status":"cancelled"}\n\n')
        elif case == "done-completed":
            w('event:done\ndata:{"stopped":false,"status":"completed"}\n\n')
        elif case == "done-partial":
            w('event:done\ndata:{"stopped":false,"status":"partial"}\n\n')
        elif case == "done-failed":
            w('event:done\ndata:{"stopped":false,"status":"failed"}\n\n')
        elif case == "legacy-done":
            w('event:done\ndata:{"stopped":false}\n\n')  # 旧后端无 status

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    HTTPServer(("0.0.0.0", 8083), Handler).serve_forever()
