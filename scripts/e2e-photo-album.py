"""照片相册真实服务验收；不替换模型、图片或存储边界。

运行：python -X utf8 scripts/e2e-photo-album.py --image workspace:photos/a.jpg --image workspace:photos/b.jpg
默认直连本地 Agent 服务；通过网关时可设置 NORA_AUTH_TOKEN。
"""

import argparse
import json
import os
from pathlib import Path
import time
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request, build_opener


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:18080")
    parser.add_argument("--image", action="append", required=True)
    parser.add_argument("--workspace", type=Path, default=Path(__file__).resolve().parents[1] / "agent-workspace")
    args = parser.parse_args()
    if len(args.image) != 2:
        parser.error("请提供两张内容不同的真实照片")
    opener = build_opener(ProxyHandler({}))
    prefix = f"album-e2e-{int(time.time())}"
    headers = {"Content-Type": "application/json"}
    if os.environ.get("NORA_AUTH_TOKEN"):
        headers["Authorization"] = "Bearer " + os.environ["NORA_AUTH_TOKEN"]

    def call(payload, expected=200):
        request = Request(args.base + "/api/workspace/photo-albums", json.dumps(payload, ensure_ascii=False).encode(), headers)
        try:
            with opener.open(request, timeout=30) as response:
                status, body = response.status, json.load(response)
        except HTTPError as error:
            status, body = error.code, json.load(error)
        if status != expected or (expected == 200 and body["code"] != 0):
            raise RuntimeError(f"HTTP {status}: {body.get('message')}")
        return body.get("data")

    def check(condition, name):
        if not condition:
            raise RuntimeError(name)
        print("PASS", name, flush=True)

    def read(album=prefix):
        return call({"action": "read", "album": album})

    preferences = (args.workspace / "photo-albums" / "preferences.json").resolve()
    if not preferences.is_relative_to(args.workspace.resolve()):
        raise RuntimeError("偏好文件超出测试工作区")
    previous_preferences = preferences.read_bytes() if preferences.exists() else None
    try:
        created = call({"action": "create", "album": prefix, "title": "真实照片验收 <script>alert(1)</script>",
                        "photos": [{"target": args.image[0], "caption": "真实素材 <b>不能当 HTML 执行</b> {{title}}"}]})
        check(created["revision"] == 1 and created["photoCount"] == 1, "创建真实相册")
        html_path = args.workspace / created["htmlTarget"][10:]
        html = html_path.read_text(encoding="utf-8")
        check("data:image/jpeg;base64," in html and len(html.encode()) <= 2 * 1024 * 1024, "图片内嵌且满足查看器上限")
        check("<script>alert(1)</script>" not in html and "&lt;b&gt;" in html and "{{title}}" in html,
              "用户内容被转义且不再次解释模板")
        check("拍摄日期未记录" in html, "未记录的拍摄日期不推测补全")
        updated = call({"action": "update", "album": prefix, "revision": 1,
                        "photos": [{"target": args.image[0]}, {"target": args.image[1], "caption": "第二张真实素材"}]})
        check(updated["photoCount"] == 2 and updated["added"] == 1 and updated["skipped"] == 1, "增量合并并跳过重复内容")
        check(html_path.read_text(encoding="utf-8") == html, "旧版交付文件保留")
        call({"action": "update", "album": prefix, "revision": 1}, expected=409)
        check(read()["revision"] == 2, "拒绝旧版本覆盖")
        call({"action": "update", "album": prefix, "revision": 2,
              "photos": [{"target": args.image[1]}, {"target": "workspace:no-such-photo-e2e.png"}]}, expected=400)
        check(read()["revision"] == 2, "坏输入不提交当前清单")
        call({"action": "create", "album": prefix + "-bad-date", "photos": [{"target": args.image[0], "date": "2026-99-99"}]}, expected=400)
        call({"action": "create", "album": "../outside", "photos": [{"target": args.image[0]}]}, expected=400)
        call({"action": "delete", "album": prefix}, expected=400)
        check(not (args.workspace / "photo-albums" / (prefix + "-bad-date") / "album.json").exists(), "拒绝无效日期、越界标识和未知动作")
        photo_hash = read()["document"]["photos"][0]["hash"]
        feedback = call({"action": "feedback", "album": prefix, "revision": 2,
                         "feedback": "验收场景：以后使用深色网格，文案保持朴素简洁", "remember": True,
                         "style": {"theme": "night", "layout": "grid", "captionTone": "plain"},
                         "captions": [{"hash": photo_hash, "caption": "第一张照片"}]})
        document = read()["document"]
        check(feedback["remembered"] and document["feedback"][-1]["text"].startswith("验收场景"), "忠实记录明确反馈")
        latest_html = (args.workspace / feedback["htmlTarget"][10:]).read_text(encoding="utf-8")
        check('class="album night grid"' in latest_html and "第一张照片" in latest_html, "当前相册立即应用样式和文案反馈")
        inherited = call({"action": "create", "album": prefix + "-inherited", "photos": [{"target": args.image[1]}]})
        check(inherited["style"] == feedback["style"] and read(prefix + "-inherited")["document"]["selectionRule"].startswith("验收场景"),
              "新相册继承已记住的规则和风格")
        print(json.dumps({"album": prefix, "htmlTarget": feedback["htmlTarget"]}, ensure_ascii=False), flush=True)
    finally:
        # 验收记忆路径后恢复用户原偏好；仅清理本测试创建的单个偏好文件。
        if previous_preferences is None:
            preferences.unlink(missing_ok=True)
        else:
            preferences.write_bytes(previous_preferences)


if __name__ == "__main__":
    main()
