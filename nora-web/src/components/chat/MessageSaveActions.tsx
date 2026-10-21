import { BookOpen, Check, FileDown, Loader2 } from "lucide-react";
import { useState } from "react";
import type { ChatMessage } from "@/lib/api/chatApi";
import { toast } from "sonner";
import { saveTextAsync } from "@/lib/services/ragService";
import { workspaceApi } from "@/lib/services/workspaceApi";
import { contentKey, contentHashSuffix, getSavedRecord, markSaved } from "@/lib/saveState";
import { savedArtifactsApi } from "@/lib/services/savedArtifactsApi";
import { parseArtifactsFence } from "@/lib/artifacts";
import { useFileViewer } from "@/hooks/useFileViewer";
import { Button } from "@/components/ui/button";

export function SaveToKnowledgeButton({ msg, sessionId }: { msg: ChatMessage; sessionId?: string }) {
  // 已保存状态按 (会话+内容哈希) 持久化:刷新/切屏后恢复(2026-09-21 修复重复保存)
  const key = contentKey(sessionId, msg.content);
  const [saved, setSaved] = useState(() => getSavedRecord(key).knowledgeSaved === true);
  const [saving, setSaving] = useState(false);

  const handleSave = async () => {
    if (saved || saving) return;
    setSaving(true);
    // 入库按名称覆盖，内容键防止开头相同的不同回答互相覆盖。
    const title = `对话结论 · ${msg.content.slice(0, 24).replace(/[#*\n]/g, "").trim()} · ${key}`;
    try {
      const doc = await saveTextAsync(title, msg.content);
      if (!doc) throw new Error("服务端未返回已保存的知识文档");
      setSaved(true);
      markSaved(key, { knowledgeSaved: true, knowledgeDocId: doc.id });
      // 内容保存与成果归属登记是两个结果，登记失败不能撤销已保存的状态。
      void savedArtifactsApi.register({
        kind: "knowledge_doc",
        path: String(doc.id),
        name: title,
        sessionId,
        messageKey: key,
      }).catch((error: unknown) => {
        toast.error("文档已入库，但成果归属登记失败", { description: error instanceof Error ? error.message : String(error) });
      });
      toast.success("已入库，可在知识库检索");
    } catch (error) {
      toast.error(`入库失败：${error instanceof Error ? error.message : String(error)}`);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Button variant="ghost" size="sm"
      type="button"
      onClick={handleSave}
      disabled={saved || saving}
      aria-busy={saving}
      className={`h-8 gap-1.5 px-2 text-xs ${saved ? "text-green-700 dark:text-green-400 disabled:opacity-100" : "text-muted-foreground"}`}
    >
      {saving ? <Loader2 className="w-3 h-3 animate-spin" /> : saved ? <Check className="w-3 h-3" /> : <BookOpen className="w-3 h-3" />}
      {saving ? "保存中…" : saved ? "已保存到知识库" : "保存到知识库"}
    </Button>
  );
}

/**
 * 「保存为文件」(M2-03,2026-09-20,方案 §4.1 S1):
 * 把回答写成工作区真实 Markdown 文件(返回验证过的路径),与「保存到知识库」
 * 是两个不同动作(分别说明):文件=可下载/可引用的资产;知识库=可被检索。
 *
 * 2026-09-21 修复重复生成:① 已保存状态按 (会话+内容哈希) 持久化,刷新/切屏
 * 后恢复;② 文件名用内容哈希后缀替代时间戳——即使状态意外丢失再点,也只是
 * 覆盖同一文件,不再产生副本(双保险)。
 */
export function SaveAsFileButton({ msg, sessionId }: { msg: ChatMessage; sessionId?: string }) {
  const key = contentKey(sessionId, msg.content);
  const [savedPath, setSavedPath] = useState<string | null>(
    () => getSavedRecord(key).filePath ?? null
  );
  const [saving, setSaving] = useState(false);

  const handleSave = async () => {
    if (savedPath) { void useFileViewer.getState().openTargets([`workspace:${savedPath}`], undefined, { sessionId }); return; }
    if (saving) return;
    setSaving(true);
    try {
      // 文件名:首行标题(去 Markdown 标记)+ 内容哈希后缀——同一回答重复保存
      // 命中同一路径(覆盖),不因时间戳变化产生副本
      const legacyReport = parseArtifactsFence(msg.content)?.find(gallery => gallery.gallery === "text" && typeof gallery.data.text === "string");
      const body = legacyReport ? String(legacyReport.data.text) : msg.content;
      const firstLine = body.split("\n").map((l) => l.replace(/^#+\s*/, "").trim()).find((l) => l.length > 0) ?? "回答";
      const base = firstLine.replace(/[\\/:*?"<>|]/g, "").slice(0, 40) || "回答";
      const path = `reports/${base}-${contentHashSuffix(msg.content)}.md`;
      await workspaceApi.writeFile(path, body);
      // 写后回读验证(方案要求"返回验证过的路径",不空口报成功)
      const check = await workspaceApi.readFile(path);
      if (check !== body) {
        throw new Error("写入后校验不一致");
      }
      setSavedPath(path);
      markSaved(key, { filePath: path });
      void savedArtifactsApi.register({
        kind: "workspace_file",
        path,
        name: firstLine.slice(0, 60) || "回答",
        sessionId,
        messageKey: key,
      }).catch((error: unknown) => {
        toast.error("文件已保存，但成果归属登记失败", { description: error instanceof Error ? error.message : String(error) });
      });
      toast.success(`已保存:工作区 ${path}`, { description: "可在「资料 → 工作区」中打开" });
    } catch (e) {
      toast.error(`保存失败：${(e as Error).message}`);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Button variant="ghost" size="sm"
      type="button"
      onClick={handleSave}
      disabled={saving}
      aria-busy={saving}
      className={`h-8 gap-1.5 px-2 text-xs ${savedPath ? "text-green-700 dark:text-green-400" : "text-muted-foreground"}`}
      title={savedPath ? `已保存:${savedPath}` : "保存为工作区 Markdown 文件(可下载/引用)"}
    >
      {saving ? <Loader2 className="w-3 h-3 animate-spin" /> : savedPath ? <Check className="w-3 h-3" /> : <FileDown className="w-3 h-3" />}
      {savedPath ? "已保存为文件" : "保存为文件"}
    </Button>
  );
}

