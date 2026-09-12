-- 表/列注释(schema_agent):Schema 浏览页与 agent 的 schema 工具直接展示这些注释,
-- 让「表结构」带上语义(2026-09-12 用户反馈:只有字段名和类型太少)。
-- COMMENT ON 幂等,重复执行安全。

COMMENT ON TABLE agent_reflection IS 'Agent 反思记录:任务签名对应经验总结,同类任务失败后回放参考';
COMMENT ON COLUMN agent_reflection.id IS '主键';
COMMENT ON COLUMN agent_reflection.session_id IS '所属会话 id';
COMMENT ON COLUMN agent_reflection.task_signature IS '任务签名(工具+参数摘要),用于匹配同类任务';
COMMENT ON COLUMN agent_reflection.reflection IS '反思正文:失败原因与下次改进';
COMMENT ON COLUMN agent_reflection.created_at IS '创建时间';

COMMENT ON TABLE agent_skill IS '指令型技能:启用后目录注入系统提示,正文按需 read(渐进披露)';
COMMENT ON COLUMN agent_skill.id IS '主键';
COMMENT ON COLUMN agent_skill.name IS '技能名称';
COMMENT ON COLUMN agent_skill.description IS '一句话描述(注入目录用)';
COMMENT ON COLUMN agent_skill.instructions IS '技能正文指令(按需拉取,不占每轮预算)';
COMMENT ON COLUMN agent_skill.category IS '分类';
COMMENT ON COLUMN agent_skill.enabled IS '是否启用';
COMMENT ON COLUMN agent_skill.created_at IS '创建时间';
COMMENT ON COLUMN agent_skill.updated_at IS '更新时间';
COMMENT ON COLUMN agent_skill.deleted_at IS '软删除时间;NULL=存活';

COMMENT ON TABLE agent_step IS '对话步骤:每轮 SSE 事件的持久化(思考/工具/注入),刷新可回放';
COMMENT ON COLUMN agent_step.id IS '主键';
COMMENT ON COLUMN agent_step.session_id IS '所属会话 id';
COMMENT ON COLUMN agent_step.step_index IS '步骤序号(会话内递增)';
COMMENT ON COLUMN agent_step.step_type IS '步骤类型:s-reasoning/s-tool/s-context 等';
COMMENT ON COLUMN agent_step.title IS '步骤标题(时间线展示)';
COMMENT ON COLUMN agent_step.detail IS '步骤详情文本';
COMMENT ON COLUMN agent_step.status IS '状态:running/done/error';
COMMENT ON COLUMN agent_step.duration_ms IS '耗时(毫秒)';
COMMENT ON COLUMN agent_step.created_at IS '创建时间';
COMMENT ON COLUMN agent_step.tool_name IS '工具名(工具步骤)';
COMMENT ON COLUMN agent_step.tool_input IS '工具入参(脱敏后的 JSON)';
COMMENT ON COLUMN agent_step.tool_result IS '工具结果摘要(JSON)';
COMMENT ON COLUMN agent_step.round_index IS 'ReAct 轮次序号,跨轮工具链重建用';

COMMENT ON TABLE app_setting IS '应用键值设置(如 GitHub OAuth client_id),value 为 JSON';
COMMENT ON COLUMN app_setting.key IS '设置键';
COMMENT ON COLUMN app_setting.value IS '设置值(JSON)';
COMMENT ON COLUMN app_setting.updated_at IS '更新时间';

COMMENT ON TABLE chat_message IS '会话消息:用户/助手消息及步骤、引用来源的快照';
COMMENT ON COLUMN chat_message.id IS '消息 id(字符串主键)';
COMMENT ON COLUMN chat_message.session_id IS '所属会话 id';
COMMENT ON COLUMN chat_message.role IS '角色:user/assistant';
COMMENT ON COLUMN chat_message.content IS '消息正文(Markdown)';
COMMENT ON COLUMN chat_message.steps IS '步骤快照(JSON)';
COMMENT ON COLUMN chat_message.sources IS '引用来源快照(JSON)';
COMMENT ON COLUMN chat_message.created_at IS '创建时间';
COMMENT ON COLUMN chat_message.deleted_at IS '软删除时间;NULL=存活';

COMMENT ON TABLE chat_session IS '对话会话';
COMMENT ON COLUMN chat_session.id IS '会话 id(字符串主键)';
COMMENT ON COLUMN chat_session.title IS '会话标题(首条消息生成)';
COMMENT ON COLUMN chat_session.created_at IS '创建时间';
COMMENT ON COLUMN chat_session.deleted_at IS '软删除时间;NULL=存活';

COMMENT ON TABLE mcp_server IS 'MCP 服务器注册表:远程(SSE/STREAMABLE)或本地进程(STDIO)';
COMMENT ON COLUMN mcp_server.id IS '主键';
COMMENT ON COLUMN mcp_server.name IS '服务器名(工具前缀 mcp__{name}__*)';
COMMENT ON COLUMN mcp_server.url IS '远程端点 URL(STDIO 为空)';
COMMENT ON COLUMN mcp_server.transport IS '传输类型:SSE/STREAMABLE/STDIO';
COMMENT ON COLUMN mcp_server.headers IS '请求头(JSON,含密钥;API 回读脱敏)';
COMMENT ON COLUMN mcp_server.enabled IS '是否启用';
COMMENT ON COLUMN mcp_server.status IS '连接状态:connected/error/…';
COMMENT ON COLUMN mcp_server.status_detail IS '状态详情(错误信息等)';
COMMENT ON COLUMN mcp_server.tools_cache IS '工具清单缓存(JSON)';
COMMENT ON COLUMN mcp_server.created_at IS '创建时间';
COMMENT ON COLUMN mcp_server.command IS 'STDIO 启动命令(如 npx)';
COMMENT ON COLUMN mcp_server.args IS 'STDIO 命令参数(JSON 数组)';
COMMENT ON COLUMN mcp_server.env IS 'STDIO 环境变量(JSON,含密钥)';
COMMENT ON COLUMN mcp_server.deleted_at IS '软删除时间;NULL=存活';

COMMENT ON TABLE model_provider IS '模型服务商:OpenAI 兼容/Anthropic 协议端点与密钥';
COMMENT ON COLUMN model_provider.id IS '主键';
COMMENT ON COLUMN model_provider.name IS '服务商名称';
COMMENT ON COLUMN model_provider.protocol IS '协议类型:openai/anthropic/responses';
COMMENT ON COLUMN model_provider.endpoint IS 'API 端点(base url)';
COMMENT ON COLUMN model_provider.api_key IS 'API 密钥(明文;不回传,仅掩码展示)';
COMMENT ON COLUMN model_provider.enabled IS '是否启用';
COMMENT ON COLUMN model_provider.models IS '模型清单(测试连通时自动发现)';
COMMENT ON COLUMN model_provider.status IS '连通状态:ok/error/…';
COMMENT ON COLUMN model_provider.model_settings IS '按模型的设置(JSON:上下文窗口/推理等级等)';
COMMENT ON COLUMN model_provider.deleted_at IS '软删除时间;NULL=存活';
