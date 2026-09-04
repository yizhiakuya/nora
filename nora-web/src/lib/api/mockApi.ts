import { FileItem, FilePreview, FilePreviewKind } from '@/types';
import { delay } from './delay';

export { delay };

// ==========================================
// 模拟 API 服务 (Mock API Services)
// ==========================================

export const MockAPI = {
  files: {
    getPreview: async (file: Pick<FileItem, "id" | "name" | "type">): Promise<FilePreview> => {
      await delay(500);
      return buildPreview(file);
    }
  }
}

// ==========================================
// 文件预览内容工厂（按 FileItem.type 映射 kind）
// ==========================================

const toKind = (type: string): FilePreviewKind => {
  if (type.includes("PDF")) return "pdf";
  if (type.includes("Word")) return "word";
  if (type.includes("Excel")) return "excel";
  if (type.includes("图像") || type.includes("图")) return "image";
  if (type.includes("文本")) return "text";
  return "unknown";
};

const IMAGE_PLACEHOLDER = `data:image/svg+xml;utf8,${encodeURIComponent(
  `<svg xmlns="http://www.w3.org/2000/svg" width="800" height="560"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#dbeafe"/><stop offset="1" stop-color="#c7d2fe"/></linearGradient></defs><rect width="800" height="560" fill="url(#g)"/><circle cx="620" cy="140" r="70" fill="#fef3c7"/><path d="M0 470 L220 300 L400 470 L520 380 L800 560 L0 560 Z" fill="#93c5fd"/><path d="M0 520 L260 380 L480 520 L800 460 L800 560 L0 560 Z" fill="#60a5fa"/></svg>`
)}`;

const buildPreview = (file: Pick<FileItem, "id" | "name" | "type">): FilePreview => {
  const kind = toKind(file.type);
  switch (kind) {
    case "pdf":
      return {
        kind,
        pages: 8,
      };
    case "word":
      return {
        kind,
        text: [
          `# ${file.name.replace(/\.\w+$/, "")}`,
          "",
          "## 一、适用范围",
          `本文档围绕「${file.name}」整理服务部署的标准流程与注意事项，供开发与运维参考。`,
          "",
          "## 二、核心要点",
          "1. 部署前确认 Node 版本 ≥ 20，数据库迁移已执行。",
          "2. 环境变量以 .env.production 为准，禁止提交密钥。",
          "3. Redis 连接失败时先检查端口 6379 与连接池上限。",
          "",
          "## 三、故障排查",
          "- ECONNREFUSED：目标服务未启动，先到环境控制台查看服务状态。",
          "- 502：上游进程崩溃，查看 api-gateway 日志定位。",
        ].join("\n"),
      };
    case "excel":
      return {
        kind,
        table: {
          columns: ["日期", "服务", "CPU", "内存", "状态"],
          rows: [
            ["06-01", "api-gateway", "12%", "245 MB", "健康"],
            ["06-01", "worker-service", "8%", "180 MB", "健康"],
            ["06-02", "postgres", "4%", "420 MB", "健康"],
            ["06-02", "redis", "0%", "0 MB", "离线"],
            ["06-03", "api-gateway", "15%", "260 MB", "健康"],
            ["06-03", "postgres", "5%", "435 MB", "健康"],
          ],
        },
      };
    case "image":
      return { kind, imageUrl: IMAGE_PLACEHOLDER };
    case "text":
      return {
        kind,
        text: [
          `# 周会纪要 ${file.name}`,
          "",
          "时间：2024-06-20 14:00 - 15:10",
          "参与：Nora、后端协作同学",
          "",
          "[14:02] Nora：本周完成 orders 表索引优化，查询耗时从 120ms 降到 8ms。",
          "[14:15] 协作：Redis 偶发 ECONNREFUSED，怀疑连接池上限过低。",
          "[14:33] Nora：下版本把连接池 maxActive 从 50 调到 100，并加缓存预热。",
          "[14:51] 全员：确认下周迭代范围，接口文档同步至知识库。",
          "[15:08] 散会。",
        ].join("\n"),
      };
    default:
      return { kind: "unknown" };
  }
};
