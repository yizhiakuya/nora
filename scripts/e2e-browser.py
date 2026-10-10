"""通过真实 agent-service 和公开网站验证内置浏览器，不启动测试网页或伪造采集值。"""
import argparse
import csv
import json
import re
from pathlib import Path
import tempfile
import time
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request, build_opener


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:18083")
    parser.add_argument("--workspace", type=Path, default=Path(__file__).resolve().parents[1] / "agent-workspace")
    args = parser.parse_args()
    session = "browser-api-e2e-" + str(int(time.time()))
    base = args.base + "/api/workspace/browser/" + session
    opener = build_opener(ProxyHandler({}))
    results = []
    # 仅选真实文章链接；分类标题的空 href 会在页面 hydration 后变成 #。
    article_selector = ".theme-doc-sidebar-menu a.menu__link[href*='/docs/']"

    def api(data=None, task=False, allowed_error=False, session_id=None, history=False):
        body = None if data is None else json.dumps(data, ensure_ascii=False).encode()
        endpoint = args.base + "/api/workspace/browser/" + session_id if session_id else base
        request = Request(endpoint + ("/tasks" if task else "?history=true" if history else ""), body, {"Content-Type": "application/json"})
        try:
            with opener.open(request, timeout=60) as response:
                result = json.load(response)
        except HTTPError as failure:
            result = json.load(failure)
        if result.get("code") != 0:
            if allowed_error:
                return result
            raise RuntimeError(result.get("message", str(result)))
        if allowed_error:
            raise RuntimeError("应当拒绝的操作被接受")
        return result["data"]

    def passed(label):
        results.append(label)
        print("PASS", label, flush=True)

    try:
        state = api()
        if state["active"]:
            raise RuntimeError("测试会话不应预先存在浏览器")
        passed("未创建会话时不会自动启动浏览器")
        state = api({"action": "open"})
        if not state["active"] or state["tabs"][0]["url"] != "about:blank":
            raise RuntimeError("未传网址时没有打开真实空白页")
        first_blank = state["activeTabId"]
        state = api({"action": "open"})
        if state["activeTabId"] != first_blank or len(state["tabs"]) != 1:
            raise RuntimeError("重复打开浏览器意外新建了标签页")
        passed("省略网址可打开真实空白页，重复打开复用当前标签")
        api({"action": "open", "url": "file:///C:/Windows/win.ini"}, allowed_error=True)
        passed("传入非 HTTP/HTTPS 网址仍被拒绝")
        state = api({"action": "open", "url": "https://playwright.dev/java/docs/intro"})
        if not state["active"] or state["owner"] != "human":
            raise RuntimeError("真实浏览器未打开")
        first_tab = state["activeTabId"]
        passed("真实 Chromium 打开 Playwright 官方文档")
        state = api({"action": "open"})
        if state["activeTabId"] != first_tab or not state["tabs"][0]["url"].startswith("https://playwright.dev/java/docs/intro"):
            raise RuntimeError("省略网址覆盖了当前网页")
        passed("已有网页时省略网址保留当前页面")
        snapshot = api({"action": "snapshot"}, task=True)
        if "Installation" not in snapshot.get("snapshot", "") or "ref=" not in snapshot["snapshot"]:
            raise RuntimeError("未得到真实 AI ARIA 快照")
        passed("AI ARIA 快照带真实可操作元素引用")
        with opener.open(base + "/frame", timeout=30) as response:
            frame = response.read()
        if not frame.startswith(b"\xff\xd8") or len(frame) < 5000:
            raise RuntimeError("网页画面不是有效真实 JPEG")
        (Path(tempfile.gettempdir()) / (session + ".jpg")).write_bytes(frame)
        passed("返回真实浏览器画面")
        reference = re.search(r'link "Writing tests" \[ref=([a-z][0-9]+)\]', snapshot["snapshot"])
        if not reference:
            raise RuntimeError("真实快照中未找到 Writing tests 的元素引用")
        state = api({"action": "click", "ref": reference.group(1)})
        api({"action": "snapshot"}, task=True)
        state = api()
        if not any("/writing-tests" in tab["url"] for tab in state["tabs"]):
            raise RuntimeError("ARIA ref 未点击实际网页链接")
        api({"action": "back"})
        passed("用真实 ARIA ref 点击文档链接，并后退恢复页面")
        plan = api({"action": "plan", "task": "官方文档导航资料", "columns": ["title", "url"], "minRows": 8}, task=True)
        capture = api({"action": "extract", "planId": plan["id"], "rowsSelector": article_selector,
                       "fields": [{"name": "title"}, {"name": "url", "attribute": "href"}], "limit": 8}, task=True)
        if len(capture["rows"]) != 8 or not all(row["url"].startswith("https://playwright.dev/") for row in capture["rows"]):
            raise RuntimeError("真实导航提取结果不正确")
        passed("提取八条真实导航标题及绝对链接")
        receipt = api({"action": "export", "planId": plan["id"], "captureIds": [capture["id"]], "filename": "official-docs.csv"}, task=True)
        artifact = args.workspace / receipt["path"]
        with artifact.open(encoding="utf-8", newline="") as file:
            rows = list(csv.DictReader(file))
        if not receipt["verified"] or len(rows) != 8 or any(row["title"] != capture["rows"][i]["title"] or row["url"] != capture["rows"][i]["url"] for i, row in enumerate(rows)):
            raise RuntimeError("真实 CSV 未通过数据一致性验收")
        passed("CSV 文件重读并逐项核对，保留来源和采集时间")
        if any(row["source_url"] != snapshot["url"] or not row["captured_at"] for row in rows):
            raise RuntimeError("未保留原始来源")
        original = artifact.read_bytes()
        artifact.write_bytes(original + b"tampered,data\r\n")
        changed = api({"action": "verify", "receiptId": receipt["id"]}, task=True)
        if changed["verified"]:
            raise RuntimeError("篡改文件未使验收失效")
        api({"action": "learn", "receiptId": receipt["id"], "name": session, "instructions": "该方法不能被保存"}, task=True, allowed_error=True)
        passed("改动产物使验收失效，并阻止学习无效成果")
        artifact.write_bytes(original)
        receipt = api({"action": "verify", "receiptId": receipt["id"]}, task=True)
        if not receipt["verified"]:
            raise RuntimeError("恢复原始文件后验收未通过")
        bad_plan = api({"action": "plan", "columns": ["title", "url"], "minRows": 9}, task=True)
        bad_capture = api({"action": "extract", "planId": bad_plan["id"], "rowsSelector": article_selector,
                           "fields": [{"name": "title"}, {"name": "url", "attribute": "href"}], "limit": 8}, task=True)
        failed = api({"action": "export", "planId": bad_plan["id"], "captureIds": [bad_capture["id"]]}, task=True)
        if failed["verified"]:
            raise RuntimeError("不足行数的文件被验收通过")
        passed("冻结的最少行数不足时如实验收失败")
        api({"action": "extract", "planId": plan["id"], "rowsSelector": article_selector,
             "fields": [{"name": "other"}], "limit": 1}, task=True, allowed_error=True)
        api({"action": "export", "planId": plan["id"], "captureIds": [capture["id"]], "filename": "../escape.csv"}, task=True, allowed_error=True)
        passed("字段偏离计划与路径逃逸被拒绝")
        missing_plan = api({"action": "plan", "columns": ["title", "missing"], "minRows": 1}, task=True)
        missing_capture = api({"action": "extract", "planId": missing_plan["id"], "rowsSelector": article_selector,
                               "fields": [{"name": "title"}, {"name": "missing", "attribute": "data-e2e-missing"}], "limit": 1}, task=True)
        missing_receipt = api({"action": "export", "planId": missing_plan["id"], "captureIds": [missing_capture["id"]]}, task=True)
        if missing_receipt["verified"] or api()["task"]["missingCount"] != 1 or api()["task"]["usableRows"] != 0:
            raise RuntimeError("真实页面缺失字段没有使结果验收失败")
        api({"action": "rows", "receiptId": missing_receipt["id"]}, task=True, allowed_error=True, session_id=session + "-other")
        passed("缺失必需字段不能通过验收，不同会话不能读取采集结果")
        review_plan = api({"action": "plan", "task": "网页任务样本与选行验收", "sourceUrl": snapshot["url"],
                           "columns": ["title", "url"], "minRows": 8, "previewFirst": True, "uniqueRows": True}, task=True)
        extraction = {"action": "extract", "planId": review_plan["id"], "rowsSelector": article_selector,
                      "fields": [{"name": "title"}, {"name": "url", "attribute": "href"}], "limit": 8}
        api({"action": "approve", "planId": review_plan["id"]}, task=True, allowed_error=True)
        api({**extraction, "fields": [{"name": "title", "attribute": "text"}, {"name": "url", "attribute": "href"}]},
            task=True, allowed_error=True)
        if api()["task"]["rowCount"] != 0:
            raise RuntimeError("正文参数错误消耗了样本额度")
        passed("误把正文当作 HTML 属性时明确报错，不占用样本额度")
        sample = api(extraction, task=True)
        if len(sample["rows"]) != 3 or api()["task"]["phase"] != "review":
            raise RuntimeError("样本没有停在三行供用户查看")
        api(extraction, task=True, allowed_error=True)
        api({"action": "export", "planId": review_plan["id"], "captureIds": [sample["id"]]}, task=True, allowed_error=True)
        sample_page = api({"action": "rows", "planId": review_plan["id"]}, task=True)
        if sample_page["total"] != 3 or not sample_page["preview"]:
            raise RuntimeError("样本预览与真实采集记录不一致")
        passed("样本最多三行，未经用户确认无法继续采集或导出")
        feedback = "重新检查导航链接"
        api({"action": "resample", "planId": review_plan["id"], "feedback": feedback}, task=True)
        current = api()["task"]
        if current["rowCount"] != 0 or current["review"]["feedback"] != feedback:
            raise RuntimeError("重新采样没有清空当前样本或保留反馈")
        replacement = api(extraction, task=True)
        if replacement["sampleRevision"] != 1 or len(replacement["rows"]) != 3:
            raise RuntimeError("新样本没有独立版本")
        api({"action": "approve", "planId": review_plan["id"]}, task=True)
        if api()["task"]["rowCount"] != 3:
            raise RuntimeError("确认样本后重新计入了已被替换的旧样本")
        full = api(extraction, task=True)
        repeat = api(extraction, task=True)
        duplicate = api({"action": "export", "planId": review_plan["id"], "captureIds": [full["id"], repeat["id"]]}, task=True)
        page = api({"action": "rows", "receiptId": duplicate["id"], "offset": 3, "limit": 2}, task=True)
        if duplicate["verified"] or len(page["duplicateGroups"]) != 8 or len(page["rows"]) != 2 or page["total"] != 16:
            raise RuntimeError("重复资料没有被识别，或结果分页不正确")
        kept = [group[0] for group in page["duplicateGroups"]]
        deduped = api({"action": "adjust", "receiptId": duplicate["id"], "rowIds": kept}, task=True)
        if not deduped["verified"] or deduped["rowCount"] != 8 or api()["task"]["rowCount"] != 8:
            raise RuntimeError("去重选行后没有重新核对真实 CSV")
        passed("重新采样保留反馈，确认后全量采集，重复行去重后通过验收")
        if len(api()["exports"]) != 1 or len(api(history=True)["exports"]) < 2:
            raise RuntimeError("轻量轮询与历史收据查询没有分开")
        passed("普通状态只核对最新收据，按需查询仍可读取历史版本")

        # 仅阻塞本次隔离测试目录；所有路径先确认仍在工作区内，finally 原样恢复。
        export_dir = (args.workspace / deduped["path"]).resolve().parent.parent
        backup_dir = export_dir.with_name("exports-e2e-backup")
        for target in (export_dir, backup_dir):
            target.relative_to(args.workspace.resolve())
        if backup_dir.exists() or not export_dir.is_dir():
            raise RuntimeError("测试导出目录不是预期的独立目录")
        export_dir.rename(backup_dir)
        try:
            export_dir.write_text("E2E 临时阻塞导出目录", encoding="utf-8")
            api({"action": "adjust", "receiptId": deduped["id"], "rowIds": kept[1:]}, task=True, allowed_error=True)
        finally:
            if export_dir.is_file():
                export_dir.unlink()
            backup_dir.rename(export_dir)
        if api()["task"]["latestReceipt"]["id"] != deduped["id"]:
            raise RuntimeError("导出失败仍提交了新收据")
        deduped = api({"action": "export", "planId": review_plan["id"], "captureIds": [full["id"], repeat["id"]], "rowIds": kept}, task=True)
        if deduped["rowCount"] != 8 or not deduped["verified"]:
            raise RuntimeError("导出失败仍提交了计划排除项")
        passed("真实文件写入失败时，选行排除项和收据一起回滚，恢复后八行完整导出")
        source_file = args.workspace / deduped["path"]
        original = source_file.read_bytes()
        reduced = api({"action": "adjust", "receiptId": deduped["id"], "rowIds": kept[1:]}, task=True)
        current = api()["task"]
        if reduced["verified"] or reduced["rowCount"] != 7 or current["phase"] != "needs_check" or current["minRows"] != 8:
            raise RuntimeError("删除行后没有如实报告数量不足")
        if source_file.read_bytes() != original or reduced["parentReceiptId"] != deduped["id"]:
            raise RuntimeError("调整覆盖了原始文件，或缺少版本关联")
        api({"action": "adjust", "receiptId": reduced["id"], "rowIds": ["unknown"]}, task=True, allowed_error=True)
        passed("取消保留一行生成七行新版本，原始八行要求与旧文件保持可追溯")
        more = api({**extraction, "limit": 9}, task=True)
        supplemented = api({"action": "export", "planId": review_plan["id"], "captureIds": [more["id"]]}, task=True)
        current = api()["task"]
        if not supplemented["verified"] or supplemented["rowCount"] != 8 or current["minRows"] != 8:
            raise RuntimeError("补采没有通过原始验收要求")
        with (args.workspace / supplemented["path"]).open(encoding="utf-8", newline="") as file:
            supplemented_rows = list(csv.DictReader(file))
        if any(row["title"] == full["rows"][0]["title"] and row["url"] == full["rows"][0]["url"] for row in supplemented_rows):
            raise RuntimeError("补采重新加入了用户排除的资料")
        stale = api({"action": "adjust", "receiptId": reduced["id"], "rowIds": kept}, task=True, allowed_error=True)
        if stale.get("code") != 409 or api()["task"]["latestReceipt"]["id"] != supplemented["id"]:
            raise RuntimeError("旧版本选行覆盖了补采结果")
        passed("旧收据保存被拒绝，补采的新版本保持当前结果")
        api({"action": "cancel", "planId": review_plan["id"]}, task=True)
        api(extraction, task=True, allowed_error=True)
        if api()["task"]["phase"] != "cancelled":
            raise RuntimeError("取消任务没有阻止继续采集")
        passed("补采保留人工排除项，取消任务后不能继续采集")
        state = api({"action": "open", "url": "https://www.python.org/", "newTab": True})
        if len(state["tabs"]) != 2:
            raise RuntimeError("第二个真实网页标签未打开")
        state = api({"action": "select_tab", "tabId": first_tab})
        if state["activeTabId"] != first_tab:
            raise RuntimeError("网页标签切换失败")
        passed("多标签共享浏览器而不串会话")
        state = api({"action": "release"})
        if state["owner"] != "agent":
            raise RuntimeError("交给 Agent 失败")
        api({"action": "reload"}, allowed_error=True)
        state = api({"action": "takeover"})
        if state["owner"] != "human":
            raise RuntimeError("用户接管失败")
        passed("操作权切换生效，错误操作方被拒绝")
        api({"action": "close"})
        if api()["active"]:
            raise RuntimeError("浏览器会话未关闭")
        passed("会话关闭并保存独立登录存储状态")
    finally:
        try:
            if api()["active"]:
                api({"action": "takeover"})
                api({"action": "close"})
        except (RuntimeError, OSError):
            pass
        record = Path(tempfile.gettempdir()) / (session + ".json")
        record.write_text(json.dumps({"session": session, "checks": results}, ensure_ascii=False, indent=2), encoding="utf-8")
        print("RECORD", record, flush=True)


if __name__ == "__main__":
    main()
