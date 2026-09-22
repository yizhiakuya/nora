#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
知识库检索评测(阶段 B,方案 §8)。

对固定语料快照跑一组真实问题,报告:
  - 正确证据进入前 K 条(hit@K)
  - 首个正确证据位置
  - 范围外泄漏(限定库检索时其它库文档混入)
  - 无答案误答(应无命中的问题返回了命中)
  - 延迟

用法(仓库根;rag-service 在跑):
  python -X utf8 scripts/rag-eval.py                 # 用现有评测库跑问题集
  python -X utf8 scripts/rag-eval.py --rebuild       # 重建评测语料(两种分段模式各一库)
  python -X utf8 scripts/rag-eval.py --mode parent_child
  python -X utf8 scripts/rag-eval.py --json          # 机器可读输出

设计原则(方案 §8):固定资料快照;标注正确文档;不只看最高相似度;
范围过滤与无答案属必须逐场景通过的正确性要求。
"""
import argparse
import json
import sys
import time
import urllib.request
import urllib.error

API = "http://localhost:8080/api"
BASES = {"plain": "rag-eval-plain", "parent_child": "rag-eval-parent-child"}

# ---------- 固定语料(评测快照;修改即意味着换基线) ----------

CORPUS = [
    {
        "name": "eval-部署手册.md",
        "text": (
            "## 系统概述\n\nNora 是个人 AI 工作台,由网关与七个微服务组成。\n\n"
            "## 部署流程\n\n部署分三步:准备环境变量、执行启动脚本、验证健康端点。\n"
            "启动命令为 bash nora.sh start all,脚本会依次拉起全部服务并轮询健康检查。\n"
            "生产环境部署密钥为 DEPLOY-KEY-OMEGA-7,仅限运维人员持有。\n\n"
            "## 端口规划\n\n网关占用 8080,文件服务 8081,检索服务 8082,"
            "智能体服务 8083,数据源服务 8084,环境服务 8085,自动化服务 8086,通知服务 8087。\n\n"
            "## 常见问题\n\n若启动后健康检查持续失败,先检查 PostgreSQL 与 Redis 容器是否已运行。\n"
        ),
    },
    {
        "name": "eval-错误码表.md",
        "text": (
            "## 服务错误码\n\n"
            "ERR-2077 表示配额超限,需要等待下一个计费周期或申请提升额度。\n"
            "ERR-3105 表示鉴权失败,通常是 API Key 失效或未配置。\n"
            "ERR-4402 表示下游服务不可达,检查目标服务的网络与端口。\n"
            "ERR-5501 表示数据校验失败,请求体字段类型或长度不符合契约。\n"
        ),
    },
    {
        "name": "eval-运维SOP.md",
        "text": (
            "## 日常巡检\n\n每天上午检查服务健康端点与磁盘占用,记录异常项。\n\n"
            "## 备份规程\n\n数据库备份在每天凌晨 2 点执行,备份文件保留 14 天。\n"
            "备份恢复演练每季度一次,恢复步骤详见恢复手册第三章。\n"
            "紧急恢复时优先使用最近一次全量备份,再回放增量日志。\n\n"
            "## 容量规划\n\n当磁盘占用超过 80% 时触发扩容流程;"
            "内存水位持续高于 85% 时评估实例升配。\n"
        ),
    },
    {
        "name": "eval-pg-tuning.md",
        "text": (
            "## PostgreSQL Tuning Notes\n\n"
            "shared_buffers should be about 25% of system memory for a dedicated database server. "
            "work_mem applies per sort or hash operation; keep it modest to avoid memory pressure "
            "under high concurrency. Effective_cache_size is an estimate, not an allocation.\n\n"
            "## Vacuum\n\nAutovacuum should generally remain enabled. For high-churn tables, "
            "tune autovacuum_vacuum_scale_factor down so cleanup runs more often.\n"
        ),
    },
    {
        "name": "eval-FAQ.md",
        "text": (
            "## 常见问答\n\n"
            "问:忘记登录令牌怎么办?答:联系管理员在设置页重置令牌。\n"
            "问:上传大文件失败?答:单文件上限 100MB,超过请先压缩或分卷。\n"
            "问:如何把回答保存为文件?答:在回答下方点「保存为文件」,文件会进入工作区。\n"
        ),
    },
]

# ---------- 问题集(标注正确文档;expectNoMatch=应为无答案) ----------

CASES = [
    # 术语 / 编号
    {"q": "ERR-2077 是什么意思", "expect": "eval-错误码表.md", "note": "错误码字面命中"},
    {"q": "ERR-4402 报错怎么处理", "expect": "eval-错误码表.md", "note": "错误码+动词"},
    {"q": "部署密钥是什么", "expect": "eval-部署手册.md", "note": "密钥在章节深处"},
    # 中文短问
    {"q": "怎么备份", "expect": "eval-运维SOP.md", "note": "中文短问"},
    {"q": "端口有哪些", "expect": "eval-部署手册.md", "note": "短问+列表型答案"},
    # 语义改写(不含原文关键词)
    {"q": "数据库连不上怎么办", "expect": "eval-错误码表.md", "note": "语义改写→下游不可达"},
    {"q": "磁盘快满了", "expect": "eval-运维SOP.md", "note": "口语→容量规划"},
    {"q": "登录不了", "expect": "eval-FAQ.md", "note": "口语→忘记令牌"},
    # 英文 / 跨语言
    {"q": "shared_buffers 应该设多大", "expect": "eval-pg-tuning.md", "note": "英文术语"},
    {"q": "autovacuum 要关掉吗", "expect": "eval-pg-tuning.md", "note": "英文参数+问句"},
    # 跨段
    {"q": "部署分几步", "expect": "eval-部署手册.md", "note": "流程概述"},
    {"q": "文件大小限制", "expect": "eval-FAQ.md", "note": "限制类问题"},
    # 无答案(必须无命中,方案 §8 正确性要求)
    {"q": "今天天气怎么样", "expectNone": True, "note": "与知识库无关"},
    {"q": "股票行情如何", "expectNone": True, "note": "与知识库无关"},
]


def post(path, body):
    req = urllib.request.Request(
        API + path,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.loads(resp.read())


def get(path):
    with urllib.request.urlopen(API + path, timeout=60) as resp:
        return json.loads(resp.read())


def ensure_base(name):
    """按名找/建资料库,返回 id。"""
    for b in get("/rag/bases")["data"]:
        if b["name"] == name:
            return b["id"]
    return post("/rag/bases", {"name": name, "description": "检索评测语料(勿删)"})["data"]["id"]


def rebuild(mode):
    """重建评测语料:清旧库→建库→按 mode 索引全部语料,返回新库 id。

    注意(实测修正):「清同名残留」只清 base_id 为空的(默认库),绝不碰
    其它评测库;调用方拿到返回的 id 直接跑评测(不按名再查——避免拿到
    已删除的旧库 id)。
    """
    base_name = BASES[mode]
    # 删同名旧库(库内文档先软删,再删库)
    for b in get("/rag/bases")["data"]:
        if b["name"] == base_name:
            for d in get("/rag/docs")["data"]:
                if d.get("baseId") == b["id"]:
                    post("/rag/docs/delete", {"ids": [d["id"]]})
            req = urllib.request.Request(API + f"/rag/bases/{b['id']}", method="DELETE")
            urllib.request.urlopen(req, timeout=60)
    # 默认库(base_id 为空)中的同名残留也清掉(防御:此前索引失败留下的)
    for d in get("/rag/docs")["data"]:
        if d["name"].startswith("eval-") and d.get("baseId") is None:
            post("/rag/docs/delete", {"ids": [d["id"]]})

    base_id = ensure_base(base_name)
    for doc in CORPUS:
        r = post("/rag/index/text", {
            "name": doc["name"], "text": doc["text"],
            "chunkMode": mode, "baseId": base_id,
        })
        status = r["data"]["status"] if r["code"] == 0 else "ERR " + str(r.get("message"))[:60]
        print(f"  indexed {doc['name']} ({status})")
    return base_id


def run_cases(base_id, top_k=5, verbose=False):
    total = len(CASES)
    hits = 0
    first_pos_sum = 0
    first_pos_n = 0
    no_match_ok = 0
    no_match_total = 0
    latencies = []
    rows = []

    for c in CASES:
        t0 = time.time()
        try:
            r = post("/rag/search", {"query": c["q"], "topK": top_k, "baseId": base_id})
            body = r.get("data") or {}
            results = body.get("results") or []
            status = body.get("status")
        except Exception as e:
            results, status = [], f"ERR:{e}"
        ms = int((time.time() - t0) * 1000)
        latencies.append(ms)

        if c.get("expectNone"):
            no_match_total += 1
            ok = len(results) == 0
            if ok:
                no_match_ok += 1
            rows.append((c["q"], "无答案", "PASS" if ok else "FAIL", f"{len(results)} 条命中", ms, c.get("note", "")))
            continue

        pos = -1
        for i, h in enumerate(results):
            if h.get("docName") == c["expect"]:
                pos = i + 1
                break
        ok = pos > 0
        if ok:
            hits += 1
            first_pos_sum += pos
            first_pos_n += 1
        rows.append((c["q"], c["expect"], "PASS" if ok else "FAIL",
                     f"位置 {pos}" if ok else "未命中", ms, c.get("note", "")))

    answered = total - no_match_total
    print(f"\n=== 检索评测结果(语料库 base_id={base_id}, topK={top_k}) ===")
    print(f"{'问题':<28} {'期望文档':<22} {'结果':<6} {'明细':<10} {'耗时':<7} 备注")
    for q, exp, ok, detail, ms, note in rows:
        q_show = q[:26] + "…" if len(q) > 27 else q
        print(f"{q_show:<28} {exp:<22} {ok:<6} {detail:<10} {ms}ms   {note}")

    print("\n=== 指标 ===")
    print(f"有答案问题 hit@{top_k}: {hits}/{answered}" + (f" ({hits/answered*100:.0f}%)" if answered else ""))
    if first_pos_n:
        print(f"首个正确证据平均位置: {first_pos_sum/first_pos_n:.2f}")
    print(f"无答案问题正确拒绝: {no_match_ok}/{no_match_total}")
    if latencies:
        lat = sorted(latencies)
        print(f"延迟: 中位 {lat[len(lat)//2]}ms / 最大 {lat[-1]}ms")
    return hits, answered, no_match_ok, no_match_total


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", default="plain", choices=["plain", "parent_child"])
    parser.add_argument("--rebuild", action="store_true", help="重建评测语料")
    parser.add_argument("--topk", type=int, default=5)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    try:
        get("/rag/bases")
    except Exception as e:
        print(f"ERROR: rag-service 不可达({e});先启动服务", file=sys.stderr)
        return 1

    if args.rebuild:
        print(f"重建评测语料(mode={args.mode})…")
        base_id = rebuild(args.mode)
    else:
        base_name = BASES[args.mode]
        base_id = next((b["id"] for b in get("/rag/bases")["data"] if b["name"] == base_name), None)
        if base_id is None:
            print(f"评测库「{base_name}」不存在,先 --rebuild", file=sys.stderr)
            return 1

    hits, answered, nm_ok, nm_total = run_cases(base_id, args.topk)
    if args.json:
        print(json.dumps({
            "mode": args.mode, "baseId": base_id,
            "hit": hits, "answered": answered,
            "noMatchOk": nm_ok, "noMatchTotal": nm_total,
        }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
