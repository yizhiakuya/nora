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
          "## 一、背景概述",
          `本文档围绕「${file.name}」整理当前阶段的结论与后续计划，供团队评审与执行参考。`,
          "",
          "## 二、核心要点",
          "1. 目标范围已与相关方对齐，优先级按季度路线图推进。",
          "2. 资源投入保持稳定，关键节点设置双周检查点。",
          "3. 风险项集中在依赖协作与数据准确性，需提前准备兜底方案。",
          "",
          "## 三、下一步行动",
          "- 各模块负责人在周五前提交细化排期。",
          "- 数据侧补充校验规则，避免口径不一致。",
          "- 下次评审时间：下周四 14:00。",
        ].join("\n"),
      };
    case "excel":
      return {
        kind,
        table: {
          columns: ["月份", "销售额（万元）", "环比", "负责人", "状态"],
          rows: [
            ["1 月", "1,284", "+8.2%", "张伟", "已归档"],
            ["2 月", "1,102", "-14.1%", "张伟", "已归档"],
            ["3 月", "1,467", "+33.1%", "李娜", "已归档"],
            ["4 月", "1,589", "+8.3%", "李娜", "进行中"],
            ["5 月", "1,733", "+9.1%", "王强", "进行中"],
            ["6 月", "1,912", "+10.3%", "王强", "待复核"],
          ],
        },
      };
    case "image":
      return { kind, imageUrl: IMAGE_PLACEHOLDER };
    case "text":
      return {
        kind,
        text: [
          `# 会议纪要 ${file.name}`,
          "",
          "时间：2024-06-20 14:00 - 15:10",
          "参与：产品组全员、负责人",
          "",
          "[14:02] 项目负责人：Q2 复盘整体节奏正常，重点看转化漏斗。",
          "[14:15] 产品：漏斗第三步流失率环比下降 4.6%，与新版引导相关。",
          "[14:33] 产品：下一版本优先处理移动端加载时长，目标 P90 < 2s。",
          "[14:51] 全员：确认下周迭代范围，会前同步 PRD 至知识库。",
          "[15:08] 散会。",
        ].join("\n"),
      };
    default:
      return { kind: "unknown" };
  }
};
