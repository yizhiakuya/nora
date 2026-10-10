"""真实模型提问流，用于浏览器刷新、外部回答与停止接续验收。"""
import argparse
import json
from pathlib import Path
import tempfile
import time
from urllib.request import ProxyHandler, Request, build_opener


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:18083")
    parser.add_argument("--session", default="chat-state-e2e-" + str(int(time.time())))
    parser.add_argument("--model", default="deepseek-v4.1-flash")
    parser.add_argument("--provider", type=int, default=7)
    parser.add_argument("--prompt")
    parser.add_argument("--reasoning-level")
    parser.add_argument("--record", type=Path)
    args = parser.parse_args()
    prompt = "这是对话状态验收，请按顺序执行：先调用 ask_user 提问『选择资料范围』，选项为『范围 A』『范围 B』；收到回答后，再调用 ask_user 提问『选择导出格式』，选项为『CSV』『HTML』。两次提问分开调用，每次等待回答；不要使用其他工具，最后只简短回复所选范围和格式。"
    opener = build_opener(ProxyHandler({}))
    payload = {"content": args.prompt or prompt, "model": args.model, "providerId": args.provider, "permissionMode": "assist"}
    if args.reasoning_level:
        payload["reasoningLevel"] = args.reasoning_level
    request = Request(args.base + "/api/chat/sessions/" + args.session + "/messages",
                      json.dumps(payload, ensure_ascii=False).encode(), {"Content-Type": "application/json"})
    record = {"session": args.session, "events": []}
    path = args.record or Path(tempfile.gettempdir()) / (args.session + ".json")
    print("SESSION", args.session, flush=True)
    with opener.open(request, timeout=300) as response:
        event = ""
        for line in response:
            line = line.decode("utf-8").strip()
            if line.startswith("event:"):
                event = line[6:].strip()
            elif line.startswith("data:"):
                data = json.loads(line[5:])
                record["events"].append({"event": event, "data": data})
                path.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
                if event in ("question_required", "done", "error"):
                    print(event, data.get("question", data.get("status", data.get("message", ""))), flush=True)
    print("RECORD", path, flush=True)
    inserted = [item["data"]["id"] for item in record["events"]
                if item["event"] == "step" and (item["data"].get("context") or {}).get("kind") == "user-input"]
    if inserted:
        messages = json.load(opener.open(args.base + "/api/chat/sessions/" + args.session + "/messages", timeout=15))
        positions = [next(i for i, message in enumerate(messages) if message["id"] == message_id) for message_id in inserted]
        assert positions == sorted(positions), "Inserted user messages changed order"
        assert messages[positions[0] - 1]["role"] == "assistant", "Earlier assistant progress must precede inserted input"
        last = positions[-1] + 1
        assert messages[last]["role"] == "assistant", "Continuation must follow inserted input"
        steps = [step for message in messages[:last + 1] for step in message.get("steps", [])]
        assert len({step["id"] for step in steps}) == len(steps), "A tool or reasoning step appeared in multiple segments"
        assert all((step.get("context") or {}).get("kind") != "user-input" for step in steps), "Internal boundaries leaked into persisted messages"
        print("PASS inserted message order, continuation, unique step placement", flush=True)


if __name__ == "__main__":
    main()
