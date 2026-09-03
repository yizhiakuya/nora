import { Globe, Terminal, Database, GitBranch, Mail, FileText, FileSpreadsheet, FileImage, File } from "lucide-react";
import { Skill, FileItem, FolderItem } from "@/types";

export const MOCK_SKILLS: Skill[] = [
  { id: 1, name: "文件读取", desc: "AI 可读取已索引的文件内容作为上下文", icon: FileText, color: "text-blue-500 dark:text-blue-400", bg: "bg-blue-100 dark:bg-blue-900/50", category: "内置", enabled: true, isOfficial: true },
  { id: 2, name: "联网搜索", desc: "AI 可实时检索互联网信息补充回答", icon: Globe, color: "text-green-500 dark:text-green-400", bg: "bg-green-100 dark:bg-green-900/50", category: "内置", enabled: true, isOfficial: true },
  { id: 3, name: "SQL 查询", desc: "AI 可查询已连接的数据源（只读）", icon: Database, color: "text-orange-500 dark:text-orange-400", bg: "bg-orange-100 dark:bg-orange-900/50", category: "数据", enabled: true, isOfficial: true },
  { id: 4, name: "代码执行", desc: "AI 可在沙盒中运行 Python 脚本", icon: Terminal, color: "text-purple-500 dark:text-purple-400", bg: "bg-purple-100 dark:bg-purple-900/50", category: "计算", enabled: true, isOfficial: true },
  { id: 5, name: "服务日志", desc: "AI 可读取环境控制台的服务日志", icon: GitBranch, color: "text-gray-800 dark:text-gray-100", bg: "bg-gray-200 dark:bg-gray-800", category: "环境", enabled: false, isOfficial: true },
  { id: 6, name: "邮件通知", desc: "AI 可在任务完成/失败时发邮件通知", icon: Mail, color: "text-red-500 dark:text-red-400", bg: "bg-red-100 dark:bg-red-900/50", category: "通知", enabled: false, isOfficial: true },
];

export const MOCK_FOLDERS: FolderItem[] = [
  { name: "个人文档", count: 12 },
  { name: "项目文件", count: 8 },
  { name: "开发资料", count: 24 },
  { name: "下载数据", count: 3 }
];

export const MOCK_FILES: FileItem[] = [
  { id: 1, name: "2024_Q2_产品规划.pdf", type: "PDF 文档", size: "2.4 MB", date: "2024-06-02 14:30", icon: FileText, color: "text-red-500 dark:text-red-400", indexed: true },
  { id: 2, name: "竞品分析数据.xlsx", type: "Excel 表格", size: "1.2 MB", date: "2024-06-01 10:15", icon: FileSpreadsheet, color: "text-green-600 dark:text-green-400", indexed: true },
  { id: 3, name: "年度财报草稿.docx", type: "Word 文档", size: "845 KB", date: "2024-05-28 09:00", icon: FileText, color: "text-blue-500 dark:text-blue-400", indexed: true },
  { id: 4, name: "官网首页设计图.png", type: "PNG 图像", size: "4.8 MB", date: "2024-05-25 16:45", icon: FileImage, color: "text-purple-500 dark:text-purple-400", indexed: false },
  { id: 5, name: "会议纪要_0520.txt", type: "纯文本", size: "12 KB", date: "2024-05-20 18:30", icon: File, color: "text-gray-500 dark:text-gray-400", indexed: true },
];

