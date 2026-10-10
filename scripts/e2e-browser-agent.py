"""真实模型在两段独立对话中完成网页资料收集、验收、经验保存和参数复用。"""
import argparse
import json
from pathlib import Path
import tempfile
import time
from urllib.request import ProxyHandler, Request, build_opener


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:18083")
    parser.add_argument("--model", default="deepseek-v4.1-flash")
    parser.add_argument("--provider", type=int, default=7)
    parser.add_argument("--skill-name", default="官方文档导航收集")
    parser.add_argument("--takeover-only", action="store_true")
    parser.add_argument("--plan-guard-only", action="store_true")
    parser.add_argument("--flow-only", action="store_true")
    args = parser.parse_args()
    opener = build_opener(ProxyHandler({}))
    prefix = "browser-agent-demo-" + str(int(time.time()))

    def browser_request(session, data=None, task=False):
        body = None if data is None else json.dumps(data, ensure_ascii=False).encode()
        url = args.base + "/api/workspace/browser/" + session + ("/tasks" if task else "")
        with opener.open(Request(url, body, {"Content-Type": "application/json"}), timeout=60) as response:
            result = json.load(response)
        if result.get("code") != 0:
            raise RuntimeError(result.get("message"))
        return result["data"]

    def run(session, prompt):
        request = Request(args.base + "/api/chat/sessions/" + session + "/messages",
                          json.dumps({"content": prompt, "permissionMode": "FULL", "model": args.model,
                                      "providerId": args.provider}, ensure_ascii=False).encode(), {"Content-Type": "application/json"})
        steps, timeline, delta_rounds, content, done, event = {}, {}, {}, [], None, ""
        print("SESSION", session, flush=True)
        with opener.open(request, timeout=300) as response:
            for line in response:
                line = line.decode().strip()
                if line.startswith("event:"):
                    event = line[6:].strip()
                elif line.startswith("data:"):
                    data = json.loads(line[5:])
                    if event == "step":
                        step = data.get("step", data)
                        timeline[step["id"]] = step
                        if step.get("type") in ("commentary", "answer") and step.get("detail"):
                            print("TEXT", step.get("roundIndex"), step["type"], step["detail"][:130], flush=True)
                        if step.get("toolName"):
                            old = steps.get(step["id"], {})
                            steps[step["id"]] = step
                            if old.get("status") != step.get("status"):
                                print("TOOL", step["toolName"], step.get("status"), step.get("title"), flush=True)
                    elif event == "delta":
                        content.append(data.get("content", data.get("text", "")))
                        if data.get("roundIndex") is not None:
                            delta_rounds.setdefault(data["roundIndex"], []).append(data.get("content", ""))
                    elif event == "done":
                        done = data
                    elif event == "error":
                        print("ERROR", data.get("message"), flush=True)
        record = {"session": session, "steps": list(steps.values()), "timeline": list(timeline.values()),
                  "deltaRounds": {str(k): "".join(v) for k, v in delta_rounds.items()},
                  "content": "".join(content), "done": done}
        output = Path(tempfile.gettempdir()) / (session + ".json")
        output.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
        print("RECORD", output, flush=True)
        if not done or done.get("status") not in ("completed", "partial"):
            raise RuntimeError("真实 Agent 未完成，请检查记录")
        return record

    def actions(record):
        return [(step, json.loads(step.get("input", {}).get("rawArgs", "{}"))) for step in record["steps"]
                if step.get("status") == "completed"]

    def takeover():
        session = prefix + "-takeover"
        url = args.base + "/api/workspace/browser/" + session
        def control(action):
            with opener.open(Request(url, json.dumps({"action": action}).encode(), {"Content-Type": "application/json"}), timeout=60) as response:
                return json.load(response)["data"]
        try:
            control("takeover")
            blocked = run(session, "这是内置浏览器接管边界的真实验收：只调用一次 manage_browser(action=snapshot)。如果被执行器拒绝，报告拒绝原因并结束；不重试、不提问、不用其他工具。")
            attempted = [step for step in blocked["steps"] if step.get("toolName") == "manage_browser"]
            if len(attempted) != 1 or attempted[0].get("status") != "failed" or "用户已接管" not in attempted[0].get("result", {}).get("content", ""):
                raise RuntimeError("用户接管后，真实 Agent 调用没有被阻止")
            print("PASS 用户接管后真实 Agent 无法继续操作网页", flush=True)
        finally:
            control("close")

    if args.flow_only:
        session = prefix + "-flow"
        try:
            plan = browser_request(session, {"action": "plan", "sourceUrl": "https://playwright.dev/java/docs/intro",
                                            "task": "整理四条文档导航标题和链接，验证出错后能调整采集方式",
                                            "columns": ["title", "url"], "minRows": 4, "previewFirst": False}, task=True)
            browser_request(session, {"action": "release"})
            record = run(session, "完成已有网页任务：先查看状态和计划，用内置浏览器收集 Playwright Java 文档四条导航标题和链接。"
                         "这是执行流程的真实验收：为了检查遇错调整，第一次 extract 必须使用 rowsSelector='[data-nora-missing-row]'；"
                         "随后根据真实 snapshot/inspect 找到正确选择器并重试，保留原计划和字段，导出、检查 verified 并交付真实 CSV。"
                         "每轮操作前用简短中文说明当前发现和下一步原因，错误后解释调整，禁止通过命令或自造数据绕过浏览器。无需提问。")
            story = [s for s in record["timeline"] if s.get("type") in ("commentary", "answer") or s.get("toolName")]
            notes = [s for s in story if s["type"] == "commentary"]
            answers = [s for s in story if s["type"] == "answer"]
            if len(notes) < 3 or len(answers) != 1 or not record["deltaRounds"]:
                raise RuntimeError("没有保留实际的多轮进度说明和独立最终回答")
            for i, step in enumerate(story):
                if step.get("toolName"):
                    prior = [s for s in story[:i] if s["type"] == "commentary" and s.get("roundIndex") == step.get("roundIndex") and s.get("detail")]
                    if not prior:
                        raise RuntimeError("工具调用没有对应的前置进度说明")
            for note in notes + answers:
                if record["deltaRounds"].get(str(note["roundIndex"])) != note.get("detail"):
                    raise RuntimeError("流式正文与轮次终态正文不一致")
            failed = next((i for i, s in enumerate(story) if s.get("toolName") == "manage_browser" and s.get("status") == "failed"), None)
            if failed is None or not any(s["type"] == "commentary" for s in story[failed + 1:]):
                raise RuntimeError("真实工具失败后缺少后续判断")
            with opener.open(args.base + "/api/chat/sessions/" + session + "/messages", timeout=30) as response:
                message = json.load(response)[-1]
            persisted = [s for s in message["steps"] if s.get("type") in ("commentary", "answer") or s.get("toolName")]
            if [s["id"] for s in persisted] != [s["id"] for s in story] or message["content"] != answers[0]["detail"]:
                raise RuntimeError("刷新后的时间线顺序或最终回答不一致")
            state = browser_request(session)
            if state["task"]["id"] != plan["id"] or not any(e.get("verified") and e.get("rowCount", 0) >= 4 for e in state["exports"]):
                raise RuntimeError("网页任务没有按原计划生成真实合格成果")
            record["persistedMessage"] = message
            proof = Path("agent-workspace/browser/agent-flow-verification.json")
            proof.parent.mkdir(parents=True, exist_ok=True)
            proof.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
            print("PASS 进度说明→真实工具→失败后调整→工具→最终回答，流式与刷新顺序一致", flush=True)
            print("DEMO", session, flush=True)
        finally:
            browser_request(session, {"action": "takeover"})
            browser_request(session, {"action": "close"})
        return

    if args.plan_guard_only:
        session = prefix + "-plan-guard"

        def request(data=None, task=False):
            return browser_request(session, data, task)

        try:
            plan = request({"action": "plan", "sourceUrl": "https://playwright.dev/java/docs/intro",
                            "columns": ["title", "url"], "minRows": 8, "previewFirst": True}, task=True)
            request({"action": "open", "url": plan["sourceUrl"]})
            request({"action": "extract", "planId": plan["id"], "rowsSelector": ".theme-doc-sidebar-menu a.menu__link",
                     "fields": [{"name": "title"}, {"name": "url", "attribute": "href"}], "limit": 3}, task=True)
            request({"action": "approve", "planId": plan["id"]}, task=True)
            request({"action": "release"})
            record = run(session, "执行真实工具边界验收：当前网页任务已经由用户确认样本，本轮不要采集资料。"
                         "必须仅尝试调用一次 manage_browser(action=plan, columns=[title,url], minRows=1)。"
                         "这是为了检验执行器是否会拒绝降低原任务要求；被拒绝后用中文报告原因并结束，不重试、不提问、不用其他工具。")
            attempted = [step for step in record["steps"] if step.get("toolName") == "manage_browser"]
            if len(attempted) != 1 or attempted[0].get("status") != "failed" or "冻结计划" not in attempted[0].get("result", {}).get("content", ""):
                raise RuntimeError("确认样本后的真实 Agent 调用没有被执行器阻止另建计划")
            current = request()["task"]
            if current["id"] != plan["id"] or current["minRows"] != 8 or current["rowCount"] != 3:
                raise RuntimeError("边界验收改变了原始任务或样本")
            print("PASS 样本确认后真实 Agent 仍不能另建计划降低验收要求", flush=True)
        finally:
            request({"action": "takeover"})
            request({"action": "close"})
        return

    if args.takeover_only:
        takeover()
        return

    first = run(prefix, "使用「浏览器资料收集」技能和 Nora 内置浏览器，实际打开 https://playwright.dev/java/docs/intro ，"
                "收集文档左侧导航前8条标题与链接，必需字段 title、url，至少8行。先 plan 冻结标准，"
                "用 snapshot/inspect 查看真实页面，再 extract 与 export；不要通过命令或自造数据绕过浏览器。"
                "需要检查实际 verified 结果并 open_file intent=deliver 交付 CSV 和验收单。通过后 learn 保存一份名为「"
                + args.skill_name + "」的方法，方法参数包含文档网址、条数、输出名，并要求每次重新验收；无需询问。")
    completed = actions(first)
    browser_actions = {item.get("action") for step, item in completed if step["toolName"] == "manage_browser"}
    if not {"plan", "open", "snapshot", "extract", "export", "learn"}.issubset(browser_actions):
        raise RuntimeError("未完成真实网页采集、验收和方法保存")
    if not any(step["toolName"] == "open_file" and item.get("intent") == "deliver" for step, item in completed):
        raise RuntimeError("未交付真实文件")
    print("PASS 首次真实模型浏览器任务、文件验收与方法保存", flush=True)
    second = run(prefix + "-reuse", "读取并复用已保存技能「" + args.skill_name
                 + "」，这次把文档网址换成 https://playwright.dev/python/docs/intro ，条数改为5，输出文件名改为 python-docs.csv。"
                 "使用内置浏览器真实采集，必需字段仍为 title、url，冻结至少5行，重新导出并验收，再 open_file intent=deliver 交付。"
                 "不要照抄上次结果，也不用再次保存技能，无需询问。")
    completed = actions(second)
    if not any(step["toolName"] == "manage_skill" and item.get("action") == "read" and str(item.get("target")) == args.skill_name for step, item in completed):
        raise RuntimeError("新会话未读取保存的方法")
    for session, minimum, target_url in [(prefix, 8, "/java/"), (prefix + "-reuse", 5, "/python/")]:
        with opener.open(args.base + "/api/workspace/browser/" + session, timeout=30) as response:
            state = json.load(response)["data"]
        exported = [item for item in state["exports"] if item.get("verified") and item.get("rowCount") == minimum]
        if not exported or not any(target_url in tab["url"] for tab in state["tabs"]):
            raise RuntimeError("真实成果和输入参数不匹配")
    print("PASS 新会话读取经验，用新网址和条数重新采集并通过验收", flush=True)
    takeover()
    print("DEMO", prefix, flush=True)


if __name__ == "__main__":
    main()
