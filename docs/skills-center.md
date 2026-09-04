# 功能设计文档：AI 能力中心业务闭环

> 状态：已实现 · 范围：纯前端 Mock · 遵循 AGENTS.md 前端边界

## 1. 背景与问题

技能中心此前存在五处业务缺口：
1. 「保存并创建」只关弹窗，**创建是假动作**，表单数据全部丢弃
2. 技能「从哪来」没有叙事（官方/自定义仅是数据字段，无来源故事）
3. 生命周期残缺：无编辑、无删除、无详情（点卡片无响应）
4. 入口孤立：AI 能力页「添加技能」弹窗批量启停，与技能中心数据共享
5. 表单零校验：名称可空、Schema 不校验 JSON、无分类归属

## 2. 数据模型变更（src/types/index.ts）

```ts
export interface Skill {
  // 原有字段不变
  createdAt?: string;   // 自定义技能创建时间（官方技能无）
  schema?: string;      // OpenAPI Schema 原文（自定义技能）
  authType?: string;    // 无鉴权 | Bearer Token | API Key
}
```

## 3. 状态层（src/hooks/useSkills.ts）

zustand store（对齐 useGlobalTasks 模式），以 MOCK_SKILLS 播种：

- skills: Skill[]
- addSkill(skill)：追加（页面层负责构造完整对象与 id/createdAt）
- updateSkill(id, patch)：编辑保存
- removeSkill(id)：删除（仅自定义）
- toggleSkill(id)：启停（官方/自定义均可）

AI 能力页与技能中心消费同一 store —— 启停状态全局一致。

## 4. 表单（SkillFormModal，替代原 CreateSkillModal）

| 字段 | 校验 |
|------|------|
| 技能名称 | 必填（否则红字提示） |
| 分类 | 下拉：计算/数据/搜索/集成/自定义（新建默认「自定义」） |
| OpenAPI Schema | 必填且必须为合法 JSON（JSON.parse 校验，错误行内提示） |
| 鉴权设置 | 下拉 + Token 输入（Mock，仅保存鉴权方式） |

编辑模式复用同一弹窗：标题/按钮文案切换，初始值回填。

## 5. 详情（SkillDetailModal）

点击卡片打开：描述、分类、鉴权、创建时间（自定义）、Schema 预览
（格式化 JSON 深色块；官方技能显示「官方维护，无需配置」）
（静态映射 Mock）、启停开关。

编辑 / 删除入口收敛在详情弹窗底部（删除带确认步骤），卡片本身保持
「点击看详情 + 开关启停」的简单语义。

## 6. AI 能力页打通（SkillGrid）

- 技能与工具区改为读取 useSkills 中启用中的技能，开关即 toggleSkill
- 「添加技能」打开批量启停弹窗（勾选 = 启用），并提供「去技能中心创建」跳转

## 7. 验收清单

- 创建：校验生效 → 保存后新卡片出现（自定义徽标 + 创建时间 + 默认「自定义」分类）+ toast
- 编辑：详情 → 编辑回填 → 保存后卡片更新
- 删除：详情 → 删除 → 确认 → 卡片消失；官方技能无删除入口
- 详情：五类信息完整展示，Schema 格式化
- AI 能力页：技能列表来自 store；添加技能弹窗批量启停
- 官方技能不可编辑/删除
- typecheck / lint / test / build 全绿


