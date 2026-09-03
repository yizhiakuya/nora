import { Globe, Terminal, Database, GitBranch, Mail, Ticket, FileText, FileSpreadsheet, FileImage, File, Server, Cloud, HardDrive, Activity } from "lucide-react";
import { Skill, FileItem, FolderItem, DataSource } from "@/types";

export const MOCK_SKILLS: Skill[] = [
  { id: 1, name: "Python 沙盒环境", desc: "提供安全隔离的代码执行环境...", icon: Terminal, color: "text-blue-500", bg: "bg-blue-100", category: "计算", enabled: true, isOfficial: true },
  { id: 2, name: "Bing 联网搜索", desc: "赋予智能体实时检索互联网信息的能力...", icon: Globe, color: "text-green-500", bg: "bg-green-100", category: "搜索", enabled: true, isOfficial: true },
  { id: 3, name: "PostgreSQL 查询器", desc: "连接并读取指定只读数据库...", icon: Database, color: "text-orange-500", bg: "bg-orange-100", category: "数据", enabled: true, isOfficial: true },
  { id: 4, name: "GitHub 代码分析", desc: "通过 OAuth 连接 GitHub...", icon: GitBranch, color: "text-gray-800", bg: "bg-gray-200", category: "集成", enabled: false, isOfficial: true },
  { id: 5, name: "Gmail 邮件助手", desc: "授权读取、草拟与发送邮件...", icon: Mail, color: "text-red-500", bg: "bg-red-100", category: "集成", enabled: false, isOfficial: true },
  { id: 6, name: "内部工单查询 API", desc: "自定义接入公司内部 Jira/工单系统...", icon: Ticket, color: "text-purple-500", bg: "bg-purple-100", category: "自定义", enabled: true, isOfficial: false },
];

export const MOCK_FOLDERS: FolderItem[] = [
  { name: "项目文档", count: 12 },
  { name: "财务报表", count: 8 },
  { name: "设计资产", count: 24 },
  { name: "个人收藏", count: 3 }
];

export const MOCK_FILES: FileItem[] = [
  { id: 1, name: "2024_Q2_产品规划.pdf", type: "PDF 文档", size: "2.4 MB", date: "2024-06-02 14:30", icon: FileText, color: "text-red-500", agent: "产品助理" },
  { id: 2, name: "竞品分析数据.xlsx", type: "Excel 表格", size: "1.2 MB", date: "2024-06-01 10:15", icon: FileSpreadsheet, color: "text-green-600", agent: "数据分析师" },
  { id: 3, name: "年度财报草稿.docx", type: "Word 文档", size: "845 KB", date: "2024-05-28 09:00", icon: FileText, color: "text-blue-500", agent: null },
  { id: 4, name: "官网首页设计图.png", type: "PNG 图像", size: "4.8 MB", date: "2024-05-25 16:45", icon: FileImage, color: "text-purple-500", agent: null },
  { id: 5, name: "会议纪要_0520.txt", type: "纯文本", size: "12 KB", date: "2024-05-20 18:30", icon: File, color: "text-gray-500", agent: null },
];

export const MOCK_SOURCES: DataSource[] = [
  { id: 1, name: "生产环境只读数据库 (PostgreSQL)", type: "SQL Database", host: "db-prod.company.internal", status: "connected", syncStatus: "实时同步中", icon: Server, color: "text-blue-500", bg: "bg-blue-100" },
  { id: 2, name: "Salesforce CRM 数据", type: "OAuth API", host: "api.salesforce.com", status: "connected", syncStatus: "每小时同步", icon: Cloud, color: "text-blue-400", bg: "bg-blue-50" },
  { id: 3, name: "本地知识文档挂载盘", type: "Local Volume", host: "/mnt/data/knowledge", status: "connected", syncStatus: "监听变更中", icon: HardDrive, color: "text-gray-600", bg: "bg-gray-100" },
  { id: 4, name: "Jira 敏捷开发面板", type: "Webhook", host: "company.atlassian.net", status: "error", syncStatus: "认证失败 (401)", icon: Activity, color: "text-red-500", bg: "bg-red-100" },
];
