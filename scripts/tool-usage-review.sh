#!/usr/bin/env bash
# 工具使用数据复盘(工具设计分析 P2-10,2026-09-18)
#
# 用 schema_agent.agent_step 的真实调用数据回答四个问题:
#   1. 零调用工具 → 候选退役 / 改 lazy(省每轮 tools spec 固定成本)
#   2. 高失败工具 → schema/描述改进候选(附失败样例)
#   3. 高耗时工具 → 进度/取消/异步化候选
#   4. 高轮次任务 → 一等工具候选(fetch_media 模式:把多步编排收进工具实现)
#
# 用法(仓库根目录):
#   bash scripts/tool-usage-review.sh            # 默认窗口 30 天
#   bash scripts/tool-usage-review.sh 7          # 自定义天数
#
# 依赖:nora-postgres 容器在运行(凭据与 application.yml 一致:nora/nora)。
set -euo pipefail

DAYS="${1:-30}"
PSQL="docker exec -i nora-postgres psql -U nora -d nora -X -q -P pager=off"

echo "=================================================================="
echo " 工具使用复盘 · 最近 ${DAYS} 天"
echo "=================================================================="

echo
echo "── 1. 调用量排名(含失败率/耗时)───────────────────────────────────"
# 口径说明:agent_step 对每个工具调用先落一行 running、终态再落一行——
# 统计只取终态行(status in completed/failed/declined);tool_name 为空的是
# RAG 检索等非工具步骤,一并排除。
$PSQL <<SQL
SELECT tool_name AS "工具",
       count(*) AS "调用",
       round(100.0 * count(*) FILTER (WHERE status='failed') / count(*), 1) AS "失败%",
       round(avg(duration_ms)) AS "均耗时ms",
       max(duration_ms) AS "峰值ms"
FROM schema_agent.agent_step
WHERE step_type='tool' AND tool_name IS NOT NULL AND tool_name <> ''
  AND status IN ('completed','failed','declined')
  AND created_at > now() - interval '${DAYS} days'
GROUP BY tool_name
ORDER BY count(*) DESC;
SQL

echo
echo "── 2. 零调用候选(有会话活动的窗口内从未被调用的内置工具)────────"
# 内置工具全集(与 ChatToolsSpec 同步维护;MCP 挂载工具随服务器变化,单独看)
$PSQL <<SQL
WITH builtin(name) AS (
  VALUES ('execute_sql'), ('read_service_logs'), ('execute_write_sql'),
         ('manage_container'), ('manage_datasource'), ('manage_service'),
         ('read_file'), ('manage_workspace'), ('fetch_media'),
         ('manage_skill'), ('manage_mcp'), ('run_command'),
         ('search_knowledge'), ('manage_knowledge'), ('manage_automation'),
         ('environment_status')
),
used AS (
  SELECT DISTINCT tool_name FROM schema_agent.agent_step
  WHERE step_type='tool' AND status IN ('completed','failed','declined')
    AND created_at > now() - interval '${DAYS} days'
)
SELECT b.name AS "零调用工具", '候选:退役 / 改 lazy / 并入既有工具' AS "动作"
FROM builtin b LEFT JOIN used u ON u.tool_name = b.name
WHERE u.tool_name IS NULL;
SQL

echo
echo "── 3. 高失败工具 Top(失败样例前 2 条)────────────────────────────"
$PSQL <<SQL
SELECT tool_name AS "工具",
       count(*) AS "失败次数",
       string_agg(DISTINCT left(tool_result->>'content', 100), E'\n    ' ORDER BY left(tool_result->>'content', 100)) AS "失败样例"
FROM (
  SELECT tool_name, tool_result,
         row_number() OVER (PARTITION BY tool_name ORDER BY created_at DESC) AS rn
  FROM schema_agent.agent_step
  WHERE step_type='tool' AND status='failed'
    AND created_at > now() - interval '${DAYS} days'
) t
WHERE rn <= 2
GROUP BY tool_name
ORDER BY count(*) DESC
LIMIT 10;
SQL

echo
echo "── 4. 高耗时工具 Top(均耗时 > 10s;口径:终态行,含审批等待)─────────"
$PSQL <<SQL
SELECT tool_name AS "工具",
       count(*) AS "调用",
       round(avg(duration_ms)/1000.0, 1) AS "均耗时s",
       round(max(duration_ms)/1000.0, 1) AS "峰值s",
       '候选:进度反馈 / 取消支持 / 异步化' AS "动作"
FROM schema_agent.agent_step
WHERE step_type='tool' AND tool_name IS NOT NULL AND tool_name <> ''
  AND status IN ('completed','failed','declined')
  AND created_at > now() - interval '${DAYS} days'
GROUP BY tool_name
HAVING avg(duration_ms) > 10000
ORDER BY avg(duration_ms) DESC;
SQL

echo
echo "── 5. 高轮次任务 Top(单会话工具调用数 → 一等工具候选)────────────"
$PSQL <<SQL
SELECT session_id AS "会话",
       count(*) AS "工具调用数",
       count(DISTINCT tool_name) AS "不同工具",
       string_agg(DISTINCT tool_name, ', ' ORDER BY tool_name) AS "工具集"
FROM schema_agent.agent_step
WHERE step_type='tool' AND tool_name IS NOT NULL AND tool_name <> ''
  AND status IN ('completed','failed','declined')
  AND created_at > now() - interval '${DAYS} days'
GROUP BY session_id
ORDER BY count(*) DESC
LIMIT 8;
SQL

echo
echo "── 6. MCP 服务器级使用率(评估 eager/lazy 取舍)───────────────────"
$PSQL <<SQL
SELECT split_part(tool_name, '__', 2) AS "服务器",
       count(*) AS "调用",
       count(DISTINCT tool_name) AS "用到的工具数"
FROM schema_agent.agent_step
WHERE step_type='tool' AND tool_name LIKE 'mcp\_\_%'
  AND status IN ('completed','failed','declined')
  AND created_at > now() - interval '${DAYS} days'
GROUP BY 1
ORDER BY count(*) DESC;
SQL

echo
echo "── 7. 当前挂载面(eager/lazy 与工具数)────────────────────────────"
$PSQL <<SQL
SELECT name AS "服务器", tool_policy AS "策略",
       coalesce(jsonb_array_length(tools_cache::jsonb->'tools'), 0) AS "工具数",
       enabled AS "启用"
FROM schema_agent.mcp_server
WHERE deleted_at IS NULL
ORDER BY id;
SQL

echo
echo "复盘要点(对照 docs/agent-tool-design-analysis-2026-09-18.md §5 P2-10):"
echo "  · 零调用 → 先查是否描述/命名问题,再考虑退役或 lazy"
echo "  · 高失败 → 改进 schema(enum/默认值)与错误消息(三段式),而非加工具"
echo "  · 高耗时 → 检查进度/取消;若属常见多步任务 → 做一等工具"
echo "  · 高轮次会话 → 抽读该会话步骤,识别可合并的工作流"
