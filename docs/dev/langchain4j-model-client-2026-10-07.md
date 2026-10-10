# 模型客户端切换至 LangChain4j

- Agent Service 引入 LangChain4j 的 Anthropic、OpenAI Spring Boot Starter；版本在本模块统一管理。
- 服务商、模型和协议继续从数据库按请求解析，使用对应的 StreamingChatModel。
- Chat Completions、Responses、Messages 的请求生成、SSE 解析和工具参数累积由库负责。
- Nora 保留工具执行、审批、上下文和会话事件；当前工具循环保留 SDK 的 AiMessage，包含思考签名。
- 保留原始 HTTP/SSE 错误、取消与现有有限连接重试；兼容渠道省略输出上限，避免 SDK 小默认值覆盖上游默认。
- 用真实上游验证三种协议的回答、工具往返、错误、断连和取消。

验收：Agent Service 148 个冒烟测试通过；前端类型检查、Lint、117 个冒烟测试和构建通过。
同一个 DeepSeek 模型、同一个中转的三种协议共 53 项真实检查通过。
Anthropic 实际请求 `/v1/messages`，兼容模型请求未注入 `max_tokens`。
记录：`agent-workspace/browser/output-recovery-1791339919.json`。
