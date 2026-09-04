# Nora

AI 驱动的个人文件管理与开发者工作台。

## 目录结构

```
Nora/
├── nora-web/     # 前端 — Vite 7 + React 18 + Tailwind + Zustand
└── nora-api/     # 后端 — Spring Boot 3.3 (Java 21) + PostgreSQL/pgvector，设计文档见 nora-api/docs/
```

前后端独立，分别 `pnpm install` / `pnpm dev`。
