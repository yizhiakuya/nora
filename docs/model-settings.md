# 模型管理设计 · 设置子页（2026-09-04 重构）

> 状态：已实现 · 范围：设置 → 模型管理 Tab 整体重设计（Tab 布局 + 服务商卡片 + 弹窗接入 + 协议类型）

## 1. 背景问题

旧版模型管理页把「默认模型 / 服务商列表 / 新增表单」全部挤在一个滚动页面，
交互简陋、信息密度低、删除无防误触、无协议区分。

## 2. 信息架构

```
设置 → 模型管理
├── 状态概览卡片（已连通 X · 可用模型 Y · 默认 Z） + 「接入服务商」主按钮
├── Tab: 服务商 (N)  ← 服务商卡片列表
└── Tab: 模型列表 (N) ← 按服务商分组的模型列表（radio 默认选中态）
```

支持 `?models` 深链（如 `/settings?tab=模型管理&models=models` 直达模型列表 Tab）。

## 3. 服务商卡片

| 区域 | 内容 |
|------|------|
| 头部 | 图标 + 名称 + 连通状态徽章（已连通/连接失败/未测试） + 协议徽章（蓝色标签） + 启用开关 |
| 详情 | 端点 URL（等宽字体）+ API 密钥（脱敏显示/隐藏切换） |
| 操作 | 「测试连通」（异步 900ms）+ 删除（先变「确认移除/取消」防误触） |

## 4. 接入新服务商（弹窗流程）

点击「接入服务商」打开 Modal，字段：

| 字段 | 行为 |
|------|------|
| 服务商名称 | 输入常见名称自动带出官方端点、模型列表与协议类型（OpenAI/Anthropic/Gemini/DeepSeek/Ollama） |
| 协议类型 | 下拉选择，三个选项：OpenAI 兼容（/v1/chat/completions）/ Anthropic（/v1/messages）/ Ollama（/api/chat） |
| 端点 URL | 始终可编辑（自动带出后可手动改为中转站或私有部署地址） |
| API 密钥 | 粘贴后本地脱敏存储，不上传云端 |

提交校验：名称/URL/密钥必填，URL 需 http(s):// 开头。

## 5. 数据层

`src/hooks/useModelProviders.ts`（zustand + persist）：

```ts
export type ProviderProtocol = "openai" | "ollama" | "anthropic";

export interface ModelProvider {
  id: number;
  name: string;
  url: string;
  masked: string;
  enabled: boolean;
  models: string[];
  status: "untested" | "ok" | "fail";
  protocol: ProviderProtocol;
}
```

对话页模型选择器与模型管理页共享同一 store，`defaultModel` 持久化。

## 6. 组件拆分

```
src/components/settings/
├── ModelSettings.tsx          # 状态概览 + Tab 切换壳
└── model/
    ├── ProvidersTab.tsx       # 服务商卡片列表（ProviderCard 内聚）
    ├── ModelsTab.tsx          # 模型列表（radio 默认选中态）
    └── AddProviderDialog.tsx  # 接入弹窗（含协议下拉 + URL 自动带出）
```

## 7. 验收清单

- [x] Tab 切换正常，计数准确
- [x] 服务商卡片：状态/协议徽章、启用开关、测试连通、删除防误触
- [x] 接入弹窗：名称自动带出端点/协议、协议切换显示描述、URL 可编辑
- [x] 模型列表：按服务商分组、radio 选中态、点击设为默认 + toast
- [x] 空状态 EmptyState + 引导按钮
- [x] typecheck / lint / test / build 全绿
