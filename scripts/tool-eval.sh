#!/usr/bin/env bash
# 工具评测集(工具设计分析 P2-11,2026-09-18)
#
# 对齐业界四层评测的最小可用版:对每个用例打真实对话链路,
# 检查「工具选择」层(选没选对/该不该选)——不校验回答文本(避免过严验证器)。
#
# 用法(仓库根目录,agent-service 在跑):
#   bash scripts/tool-eval.sh                    # 全部用例
#   bash scripts/tool-eval.sh env-status-selected  # 单个用例(按 name 过滤)
#
# 用例文件:scripts/tool-eval-cases.json
#   字段:name / prompt / expectAny(应调用的工具之一)/ expectNone(负例:不应调用任何工具)/ note
#
# 输出:每用例 PASS/FAIL + 实际调用工具列表;结尾汇总。失败样例输出会话 id 供人工复盘。
# Windows 注意:所有 python 调用带 -X utf8(payload 直接写文件,不经 stdout,避免 GBK 编码炸)。
set -uo pipefail

API="http://localhost:8083/api/chat/sessions"
MODEL="${NORA_EVAL_MODEL:-deepseek-v4.1-flash}"
FILTER="${1:-}"
CASES="scripts/tool-eval-cases.json"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

if ! curl -sS --noproxy '*' -o /dev/null --max-time 3 "http://localhost:8083/actuator/health" 2>/dev/null; then
  echo "ERROR: agent-service(8083)不可达——先启动服务" >&2
  exit 1
fi

PASS=0; FAIL=0; TOTAL=0
FAILED_CASES=""

count=$(python -X utf8 -c "import json;d=json.load(open('$CASES',encoding='utf-8'));print(len(d))")
for i in $(seq 0 $((count-1))); do
  name=$(python -X utf8 -c "import json;d=json.load(open('$CASES',encoding='utf-8'));print(d[$i]['name'])")
  if [ -n "$FILTER" ] && [ "$name" != "$FILTER" ]; then continue; fi
  TOTAL=$((TOTAL+1))

  expect_none=$(python -X utf8 -c "import json;d=json.load(open('$CASES',encoding='utf-8'));print('1' if d[$i].get('expectNone') else '0')")
  expect_any=$(python -X utf8 -c "import json;d=json.load(open('$CASES',encoding='utf-8'));print(','.join(d[$i].get('expectAny',[])))")

  session="eval-$(date +%s)-$i"
  payload="$TMP/payload-$i.json"
  # payload 由 python 直接写文件(UTF-8),不经 stdout——中文 prompt 在 Windows GBK 控制台下必炸
  python -X utf8 - "$CASES" "$i" "$MODEL" "$payload" <<'PYEOF'
import json, sys
cases, idx, model, out = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]
d = json.load(open(cases, encoding='utf-8'))[idx]
with open(out, 'w', encoding='utf-8') as f:
    json.dump({'content': d['prompt'], 'model': model, 'permissionMode': 'FULL'}, f, ensure_ascii=False)
PYEOF

  out="$TMP/out-$i.txt"
  # curl 失败不再被忽略(审查报告 2026-09-19:此前 `|| true` 吞掉失败,
  # 空流被当成"没调工具"使负例误通过)。非零退出码 = 用例判 ERROR。
  curl_rc=0
  curl -sS --noproxy '*' -N -X POST "$API/$session/messages" \
    -H "Content-Type: application/json; charset=utf-8" \
    --data-binary @"$payload" --max-time 180 -o "$out" 2>"$TMP/err-$i.txt" || curl_rc=$?

  # 流完整性:必须见到终态事件(done/error)才算一次有效运行——
  # 空流/截断流不进入工具选择判定,直接判 ERROR(避免"失败也 PASS")
  stream_status=$(python -X utf8 - "$out" <<'PYEOF'
import sys
try:
    text = open(sys.argv[1], encoding='utf-8').read()
except Exception:
    print('unreadable')
    raise SystemExit
has_done = 'event:done' in text or 'event:done' in text.replace('event: done', 'event:done')
has_error = 'event:error' in text or 'event:error' in text.replace('event: error', 'event:error')
if has_done:
    print('done')
elif has_error:
    print('error')
else:
    print('incomplete')
PYEOF
)

  # 从 SSE 解析实际调用的工具名(去重、保序)
  called=$(python -X utf8 - "$out" <<'PYEOF'
import json, re, sys
seen = []
try:
    with open(sys.argv[1], encoding='utf-8') as f:
        text = f.read()
    for m in re.finditer(r'event:step\ndata:(\{.*?\})\n', text):
        d = json.loads(m.group(1))
        if d.get('type') == 'tool' and d.get('toolName'):
            n = d['toolName']
            if n not in seen:
                seen.append(n)
except Exception:
    pass
print(','.join(seen))
PYEOF
)

  # 运行有效性门(先于工具选择判定):curl 非零 / 流不完整 / 服务端 error 事件
  # 都不是"工具选择"结论,判 ERROR 并给出可操作的失败原因
  if [ "$curl_rc" -ne 0 ]; then
    FAIL=$((FAIL+1))
    FAILED_CASES="$FAILED_CASES $name"
    printf "ERROR %-32s curl 退出码=%s(请求失败,不算工具选择结论)  stderr=%s\n" \
      "$name" "$curl_rc" "$(head -c 120 "$TMP/err-$i.txt" 2>/dev/null | tr '\n' ' ')"
    continue
  fi
  if [ "$stream_status" != "done" ]; then
    FAIL=$((FAIL+1))
    FAILED_CASES="$FAILED_CASES $name"
    printf "ERROR %-32s 流未正常结束(status=%s;空流/截断/服务端错误不作工具选择判定)  session=%s\n" \
      "$name" "$stream_status" "$session"
    continue
  fi

  ok="FAIL"
  if [ "$expect_none" = "1" ]; then
    [ -z "$called" ] && ok="PASS"
  else
    IFS=',' read -ra wants <<< "$expect_any"
    for w in "${wants[@]}"; do
      case ",$called," in
        *",$w,"*) ok="PASS"; break ;;
      esac
    done
  fi

  if [ "$ok" = "PASS" ]; then
    PASS=$((PASS+1))
    printf "PASS  %-32s called=[%s]\n" "$name" "$called"
  else
    FAIL=$((FAIL+1))
    FAILED_CASES="$FAILED_CASES $name"
    printf "FAIL  %-32s called=[%s]  expect=[%s]%s\n" "$name" "$called" \
      "$([ "$expect_none" = "1" ] && echo '(none)' || echo "$expect_any")" "  session=$session"
  fi
done

echo
echo "==== 评测汇总: $PASS/$TOTAL 通过 ===="
if [ -n "$FAILED_CASES" ]; then
  echo "失败用例:$FAILED_CASES"
  echo "复盘:按 session id 查 agent_step(scripts/tool-usage-review.sh 同库),"
  echo "或前端打开该会话看完整时间线;对照 docs/agent-tool-design-analysis-2026-09-18.md §六 设计 checklist"
fi
[ "$FAIL" -eq 0 ]
