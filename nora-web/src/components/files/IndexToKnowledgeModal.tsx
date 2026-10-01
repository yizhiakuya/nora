'use client';

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Modal } from "@/components/ui/custom/Modal";
import { Loader2 } from "lucide-react";
import type { FileItem, KnowledgeBase } from "@/types";
import { fetchBases, previewChunks, type ChunkPreview } from "@/lib/services/ragService";
import { filesApi } from "@/lib/services/filesApi";

/** 入知识库的分段配置(与后端 /index 的 chunkMode/chunkSize/overlap/separator 对齐)。 */
export interface IndexChunkConfig {
  mode?: string;
  chunkSize?: number;
  overlap?: number;
  separator?: string;
}

interface Props {
  /** 要索引的文件;null = 弹窗关闭 */
  file: FileItem | null;
  onClose: () => void;
  onConfirm: (file: FileItem, chunkConfig: IndexChunkConfig, baseId: number | null) => void;
  /** 提交中(父组件真实调用后端时置 true) */
  submitting?: boolean;
}

/**
 * 「加入知识库」配置弹窗(F5,2026-09-26):
 * 此前文件页「入知识库」按钮只发 fileId/name,分段方式与目标资料库无从选择。
 * 现在支持:目标资料库(默认库/具体库)、分段模式(结构分段 plain / 父块-子块
 * parent_child)、高级参数(chunkSize/overlap/separator,留空=默认 2000/200),
 * 并可**预览分段结果**(调 /rag/chunk-preview,不落库)再确认导入。
 */
export function IndexToKnowledgeModal({ file, onClose, onConfirm, submitting = false }: Props) {
  const [bases, setBases] = useState<KnowledgeBase[]>([]);
  const [baseId, setBaseId] = useState<number | null>(null);
  const [mode, setMode] = useState<string>("");
  const [chunkSize, setChunkSize] = useState<string>("");
  const [overlap, setOverlap] = useState<string>("");
  const [separator, setSeparator] = useState<string>("");
  const [preview, setPreview] = useState<ChunkPreview | null>(null);
  const [previewing, setPreviewing] = useState(false);
  const [previewError, setPreviewError] = useState<string | null>(null);

  // 打开时加载资料库列表(默认库在前,含文档数)
  useEffect(() => {
    if (!file) return;
    setBaseId(null);
    setMode("");
    setChunkSize("");
    setOverlap("");
    setSeparator("");
    setPreview(null);
    setPreviewError(null);
    fetchBases().then(setBases).catch(() => setBases([]));
  }, [file]);

  if (!file) return null;

  const config: IndexChunkConfig = {};
  if (mode) config.mode = mode;
  if (chunkSize.trim()) config.chunkSize = Number(chunkSize);
  if (overlap.trim()) config.overlap = Number(overlap);
  if (separator) config.separator = separator;

  const handlePreview = async () => {
    setPreviewing(true);
    setPreviewError(null);
    try {
      // 拉文件提取文本(与索引用同一解析路径),再按当前参数试切
      const text = await filesApi.fetchPreviewText(file.id);
      if (!text.trim()) {
        setPreviewError("该文件没有可提取的文本(扫描件/纯二进制需要 OCR,暂不支持)");
        setPreview(null);
        return;
      }
      setPreview(await previewChunks({ text, ...config }));
    } catch (e) {
      setPreviewError((e as Error).message);
      setPreview(null);
    } finally {
      setPreviewing(false);
    }
  };

  return (
    <Modal
      isOpen
      onClose={onClose}
      title={`「${file.name}」加入知识库`}
      width="w-[94%] sm:w-[560px]"
      footer={
        <>
          <Button variant="outline" size="sm" onClick={onClose}>取消</Button>
          <Button size="sm" disabled={submitting} onClick={() => onConfirm(file, config, baseId)}>
            {submitting ? <Loader2 className="w-3.5 h-3.5 mr-1 animate-spin" /> : null}
            {submitting ? "提交中…" : "确认导入"}
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        {/* 目标资料库 */}
        <div className="space-y-1.5">
          <div className="text-xs font-bold text-foreground">目标资料库</div>
          <div className="flex flex-wrap gap-1.5">
            <button
              type="button"
              onClick={() => setBaseId(null)}
              className={`px-3 py-1.5 rounded-lg border text-xs transition-colors ${baseId === null
                ? "border-blue-400 bg-blue-50 dark:bg-blue-950/40 text-blue-700 dark:text-blue-300"
                : "border-border hover:bg-muted text-muted-foreground"}`}
            >
              默认资料库
            </button>
            {bases.filter((b) => !b.isDefault).map((b) => (
              <button
                key={b.id}
                type="button"
                onClick={() => setBaseId(b.id)}
                className={`px-3 py-1.5 rounded-lg border text-xs transition-colors ${baseId === b.id
                  ? "border-blue-400 bg-blue-50 dark:bg-blue-950/40 text-blue-700 dark:text-blue-300"
                  : "border-border hover:bg-muted text-muted-foreground"}`}
              >
                {b.name}
                <span className="ml-1 text-[10px] opacity-70">{b.docCount} 篇</span>
              </button>
            ))}
          </div>
        </div>

        {/* 分段模式 */}
        <div className="space-y-1.5">
          <div className="text-xs font-bold text-foreground">分段模式</div>
          <div className="flex gap-1.5">
            {[
              { value: "", label: "结构分段", hint: "沿 Markdown 标题/段落/句末切分(默认)" },
              { value: "parent_child", label: "父块-子块", hint: "章节作父块,检索命中子块时注入父块完整上下文" },
            ].map((o) => (
              <button
                key={o.value}
                type="button"
                title={o.hint}
                onClick={() => setMode(o.value)}
                className={`px-3 py-1.5 rounded-lg border text-xs transition-colors ${mode === o.value
                  ? "border-blue-400 bg-blue-50 dark:bg-blue-950/40 text-blue-700 dark:text-blue-300"
                  : "border-border hover:bg-muted text-muted-foreground"}`}
              >
                {o.label}
              </button>
            ))}
          </div>
        </div>

        {/* 高级参数 */}
        <div className="space-y-1.5">
          <div className="text-xs font-bold text-foreground">高级参数 <span className="font-normal text-muted-foreground">(留空 = 默认 2000 / 200)</span></div>
          <div className="grid grid-cols-2 gap-2">
            <label className="text-[11px] text-muted-foreground space-y-1">
              <span>分段长度(200-8000)</span>
              <Input
                type="number" min={200} max={8000} placeholder="2000"
                value={chunkSize} onChange={(e) => setChunkSize(e.target.value)}
                className="h-8 text-xs"
              />
            </label>
            <label className="text-[11px] text-muted-foreground space-y-1">
              <span>重叠长度(0-1000)</span>
              <Input
                type="number" min={0} max={1000} placeholder="200"
                value={overlap} onChange={(e) => setOverlap(e.target.value)}
                className="h-8 text-xs"
              />
            </label>
          </div>
          <label className="text-[11px] text-muted-foreground space-y-1 block">
            <span>自定义分隔符(优先于结构切分)</span>
            <Input
              placeholder="如 ### 或 ;(留空用结构分段)"
              value={separator} onChange={(e) => setSeparator(e.target.value)}
              className="h-8 text-xs"
            />
          </label>
        </div>

        {/* 分段预览 */}
        <div className="space-y-1.5">
          <div className="flex items-center justify-between">
            <div className="text-xs font-bold text-foreground">分段预览</div>
            <Button variant="outline" size="sm" className="h-6 text-[11px]" disabled={previewing} onClick={() => void handlePreview()}>
              {previewing ? <Loader2 className="w-3 h-3 mr-1 animate-spin" /> : null}
              {previewing ? "试切中…" : "按当前参数试切"}
            </Button>
          </div>
          {previewError && <div className="text-[11px] text-red-600 dark:text-red-400">{previewError}</div>}
          {preview && (
            <div className="rounded-lg border border-border bg-muted/40 p-2.5 space-y-1.5 max-h-48 overflow-y-auto custom-scroll">
              <div className="text-[10px] text-muted-foreground">
                共 {preview.total} 段 · 模式 {preview.mode} · 长度 {preview.chunkSize} · 重叠 {preview.overlap}
                {preview.total > preview.chunks.length ? `(预览前 ${preview.chunks.length} 段)` : ""}
              </div>
              {preview.chunks.slice(0, 5).map((c) => (
                <div key={c.index} className="text-[11px] text-foreground/90 border-t border-border/60 pt-1.5 first:border-t-0 first:pt-0">
                  <span className="text-muted-foreground mr-1.5">#{c.index + 1}{c.hasParent ? " ·父块" : ""}</span>
                  {c.content.length > 160 ? c.content.slice(0, 160) + "…" : c.content}
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </Modal>
  );
}
