"""通过真实模型、手机或已导入的真实照片完成相册交付验收。"""

import argparse
import json
import os
from pathlib import Path
import tempfile
import time
from urllib.request import ProxyHandler, Request, build_opener


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:18080")
    parser.add_argument("--image", action="append", help="已导入的真实照片引用；省略时验证手机猫相册")
    parser.add_argument("--model")
    parser.add_argument("--provider", type=int)
    parser.add_argument("--scope-probe", action="store_true", help="仅验证未限定下载范围被真实执行器拒绝")
    args = parser.parse_args()
    base = args.base
    session = f"photo-album-demo-{int(time.time())}"
    album = session
    prompt = ("使用「照片成长相册」技能做一次真实功能演示：从手机「猫」相册选不同时段的猫照片，最多3张，"
              "做一份猫咪时光相册。不必确认其他信息，检索整个猫相册即可。先实际看图，日期只用真实元数据，"
              "不要推断精确年龄或声称照片一定是同一只猫。标识固定为 " + album + "。"
              "只用 fetch_media items 下载选中的真实URL（从 photos_search urls=original 获取），不要整库下载。"
              "下载到唯一工作区目录 photos/" + album + "。"
              "入册前逐张 manage_file read 实际下载的图片，再逐张写文案；拼图只用于粗筛。"
              "用 manage_photo_album 生成，再 open_file 交付可打开的网页。没有真实素材就如实说明。")
    if args.image:
        prompt = ("使用「照片成长相册」技能，把以下已导入的真实照片做成一份选片演示相册："
                  + json.dumps(args.image, ensure_ascii=False)
                  + "。这次只用这些工作区照片，无需调用手机工具。先通过 manage_file read 逐张实际看图再写说明，"
                  "没有真实拍摄日期就省略 date，标题和说明如实反映看到的素材，不推测年龄或身份。最多3张。"
                  "无需再确认，标识固定为 " + album + "，最后用 manage_photo_album 生成并 open_file 交付网页。")
    if args.scope_probe:
        prompt = ("对 fetch_media 做一次真实错误输入验收：只调用一次，参数为 "
                  + json.dumps({"folder": "photos/" + album, "type": "photo"})
                  + "。不要补范围，不设 all，不运行命令。让执行器校验并拒绝，返回错误后简短报告。")
    opener = build_opener(ProxyHandler({}))
    if os.environ.get("NORA_AUTH_TOKEN"):
        opener.addheaders = [("Authorization", "Bearer " + os.environ["NORA_AUTH_TOKEN"])]
    payload = {"content": prompt, "permissionMode": "FULL"}
    if args.model:
        payload["model"] = args.model
    if args.provider:
        payload["providerId"] = args.provider
    request = Request(base + "/api/chat/sessions/" + session + "/messages",
                      json.dumps(payload, ensure_ascii=False).encode(), {"Content-Type": "application/json"})
    steps = {}
    content = []
    done = None
    event = ""
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
                    if step.get("toolName"):
                        previous = steps.get(step["id"], {})
                        steps[step["id"]] = step
                        if step.get("status") != previous.get("status"):
                            print("TOOL", step["toolName"], step.get("status"), flush=True)
                elif event == "delta":
                    content.append(data.get("content", data.get("text", "")))
                elif event == "error":
                    print("ERROR", data.get("message"), flush=True)
                elif event == "done":
                    done = data
    record = {"session": session, "album": album, "done": done, "content": "".join(content), "steps": list(steps.values())}
    output = Path(tempfile.gettempdir()) / (session + ".json")
    output.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
    print("RECORD", output, flush=True)
    print("DONE", json.dumps(done, ensure_ascii=False), flush=True)
    if args.scope_probe:
        fetches = [step for step in steps.values() if step.get("toolName") == "fetch_media"]
        if len(fetches) != 1 or fetches[0].get("status") != "failed" or "未指定下载范围" not in fetches[0].get("result", {}).get("content", ""):
            raise RuntimeError("未限定下载范围没有被真实执行器拒绝")
        print("PASS 未限定下载范围被拒绝，未开始下载", flush=True)
        return
    verify_album(record, base, phone=not args.image)


def verify_album(record, base="http://localhost:18080", phone=True):
    """按真实成果验收；保留曾发生过工具错误的 partial 状态，不把成功重试当成果缺失。"""
    steps = record["steps"]
    done = record.get("done")
    opener = build_opener(ProxyHandler({}))
    if os.environ.get("NORA_AUTH_TOKEN"):
        opener.addheaders = [("Authorization", "Bearer " + os.environ["NORA_AUTH_TOKEN"])]
    tools = {step["toolName"] for step in steps if step.get("status") == "completed"}
    if not {"manage_skill", "manage_photo_album", "open_file"}.issubset(tools) or not done or done.get("status") not in ("completed", "partial"):
        raise RuntimeError("真实 Agent 未完成技能读取、相册生成和文件交付，请检查记录")
    request = Request(base + "/api/workspace/photo-albums",
                      json.dumps({"action": "read", "album": record["album"]}).encode(), {"Content-Type": "application/json"})
    with opener.open(request, timeout=30) as response:
        saved = json.load(response)["data"]
    if not 1 <= saved["photoCount"] <= 3:
        raise RuntimeError("相册没有保存 1–3 张真实照片")
    reads = set()
    for step in steps:
        if step.get("status") != "completed" or step.get("toolName") not in ("manage_file", "manage_workspace"):
            continue
        item = json.loads(step.get("input", {}).get("rawArgs", "{}"))
        path = item.get("id", item.get("path", item.get("filename", "")))
        if item.get("action") == "read" and path:
            reads.add(path if path.startswith("workspace:") else "workspace:" + path)
    if not all(photo["target"] in reads for photo in saved["document"]["photos"]):
        raise RuntimeError("最终入册的照片未逐张实际读取核对")
    if phone:
        downloads = [json.loads(step.get("input", {}).get("rawArgs", "{}"))
                     for step in steps if step.get("toolName") == "fetch_media" and step.get("status") == "completed"]
        if not downloads or any(not 1 <= len(item.get("items", [])) <= 3 for item in downloads):
            raise RuntimeError("没有按已选的 1–3 张照片限定下载范围")
    deliveries = [json.loads(step.get("input", {}).get("rawArgs", "{}"))
                  for step in steps if step.get("toolName") == "open_file" and step.get("status") == "completed"]
    if not any(item.get("intent") == "deliver" and saved["htmlTarget"] in item.get("targets", []) for item in deliveries):
        raise RuntimeError("未交付本次生成的相册版本")
    print("ALBUM", saved["htmlTarget"], "PHOTOS", saved["photoCount"], flush=True)
    if done["status"] == "partial":
        print("RECOVERED 工具输入错误已重试纠正；逐张读取、限定下载和实际文件交付检查均通过", flush=True)
    print("PASS 真实模型 + 真实照片 + 相册工具 + 文件交付", flush=True)


if __name__ == "__main__":
    main()
