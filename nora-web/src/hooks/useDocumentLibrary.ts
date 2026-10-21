'use client';

import { useEffect, useMemo, useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { useKnowledgeDocs } from "@/hooks/useKnowledgeDocs";
import { KnowledgeBase, KnowledgeDoc, KnowledgeSource, DocDetail } from "@/types";
import { addChunk, createBase, deleteChunk, fetchBases, fetchDocDetail, reindexDoc, setChunkEnabled, setDocBase, setDocEnabled, updateChunk } from "@/lib/services/ragService";
import { savedArtifactsApi } from "@/lib/services/savedArtifactsApi";
import { toast } from "sonner";

const SOURCE_ORDER: KnowledgeSource[] = ["file", "database", "repo", "environment", "chat", "text"];

export function useDocumentLibrary() {
  const allDocs = useKnowledgeDocs((s) => s.docs);
  const removeDoc = useKnowledgeDocs((s) => s.removeDoc);
  const removeDocs = useKnowledgeDocs((s) => s.removeDocs);
  const renameDoc = useKnowledgeDocs((s) => s.renameDoc);

  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [busyId, setBusyId] = useState<number | null>(null);
  const [renaming, setRenaming] = useState<KnowledgeDoc | null>(null);
  const [renameValue, setRenameValue] = useState("");
  const [detail, setDetail] = useState<DocDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState<number | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<number[] | null>(null);
  /** 资料库分组(阶段 B 闭环):建库/移库的 UI 状态 */
  const [bases, setBases] = useState<KnowledgeBase[]>([]);
  const [moving, setMoving] = useState<KnowledgeDoc | null>(null);
  const [newBaseName, setNewBaseName] = useState("");
  const [creatingBase, setCreatingBase] = useState(false);
  /** 分段级管理(阶段 D):编辑/新增/操作中状态 */
  const [editingChunkId, setEditingChunkId] = useState<number | null>(null);
  const [editingChunkText, setEditingChunkText] = useState("");
  const [addingChunk, setAddingChunk] = useState(false);
  const [newChunkText, setNewChunkText] = useState("");
  const [chunkBusy, setChunkBusy] = useState(false);
  const detailVersion = useRef(0);
  const chunkPending = useRef(false);

  /** 分段操作后用返回列表原地刷新详情(避免整文档重拉)。 */
  const applyChunks = (docId: number, chunks: DocDetail["chunks"]) => {
    setDetail((prev) => (prev?.doc.id === docId ? { ...prev, chunks, doc: { ...prev.doc, chunks: chunks.length } } : prev));
  };

  const handleChunkSave = async (chunkId: number) => {
    if (!detail || chunkPending.current) return;
    const docId = detail.doc.id, version = detailVersion.current;
    chunkPending.current = true;
    setChunkBusy(true);
    try {
      const chunks = await updateChunk(chunkId, editingChunkText);
      if (version !== detailVersion.current) return;
      applyChunks(docId, chunks);
      setEditingChunkId(null);
      toast.success("分段已保存,向量已重算");
    } catch (e) {
      if (version === detailVersion.current) toast.error(`保存失败：${(e as Error).message}`);
    } finally {
      if (version === detailVersion.current) { chunkPending.current = false; setChunkBusy(false); }
    }
  };

  const handleChunkToggle = async (chunkId: number, enabled: boolean) => {
    if (!detail || chunkPending.current) return;
    const docId = detail.doc.id, version = detailVersion.current;
    chunkPending.current = true;
    setChunkBusy(true);
    try {
      const chunks = await setChunkEnabled(chunkId, enabled);
      if (version !== detailVersion.current) return;
      applyChunks(docId, chunks);
      toast.success(enabled ? "分段已启用" : "分段已停用(退出检索)");
    } catch (e) {
      if (version === detailVersion.current) toast.error(`操作失败：${(e as Error).message}`);
    } finally {
      if (version === detailVersion.current) { chunkPending.current = false; setChunkBusy(false); }
    }
  };

  const handleChunkDelete = async (chunkId: number) => {
    if (!detail || chunkPending.current) return;
    const docId = detail.doc.id, version = detailVersion.current;
    chunkPending.current = true;
    setChunkBusy(true);
    try {
      const chunks = await deleteChunk(chunkId);
      if (version !== detailVersion.current) return;
      applyChunks(docId, chunks);
      toast.success("分段已删除,剩余分段已重新编号");
    } catch (e) {
      if (version === detailVersion.current) toast.error(`删除失败：${(e as Error).message}`);
    } finally {
      if (version === detailVersion.current) { chunkPending.current = false; setChunkBusy(false); }
    }
  };

  const handleChunkAdd = async (docId: number) => {
    if (detail?.doc.id !== docId || chunkPending.current) return;
    const version = detailVersion.current;
    chunkPending.current = true;
    setChunkBusy(true);
    try {
      const chunks = await addChunk(docId, newChunkText);
      if (version !== detailVersion.current) return;
      applyChunks(docId, chunks);
      setAddingChunk(false);
      setNewChunkText("");
      toast.success("分段已添加,向量已重算");
    } catch (e) {
      if (version === detailVersion.current) toast.error(`添加失败：${(e as Error).message}`);
    } finally {
      if (version === detailVersion.current) { chunkPending.current = false; setChunkBusy(false); }
    }
  };

  const refreshBases = () => {
    fetchBases().then(setBases).catch(() => undefined);
  };
  useEffect(refreshBases, []);

  const handleMove = async (doc: KnowledgeDoc, baseId: number | null) => {
    setMoving(null);
    setBusyId(doc.id);
    try {
      const updated = await setDocBase(doc.id, baseId);
      useKnowledgeDocs.setState((state) => ({
        docs: state.docs.map((d) => (d.id === doc.id ? { ...d, ...updated } : d)),
      }));
      const target = baseId == null ? "默认资料库" : bases.find((b) => b.id === baseId)?.name ?? `#${baseId}`;
      toast.success(`「${doc.name}」已移入「${target}」`);
      refreshBases();
    } catch (e) {
      toast.error(`移动失败：${(e as Error).message}`);
    } finally {
      setBusyId(null);
    }
  };

  const handleCreateBase = async () => {
    const name = newBaseName.trim();
    if (!name) return;
    setCreatingBase(true);
    try {
      await createBase(name);
      toast.success(`资料库「${name}」已创建`);
      setNewBaseName("");
      refreshBases();
    } catch (e) {
      toast.error(`创建失败：${(e as Error).message}`);
    } finally {
      setCreatingBase(false);
    }
  };

  const grouped = useMemo(() => {
    const map = new Map<KnowledgeSource, KnowledgeDoc[]>();
    for (const src of SOURCE_ORDER) {
      const docs = allDocs.filter((d) => d.source === src);
      if (docs.length) map.set(src, docs);
    }
    return map;
  }, [allDocs]);

  const allIds = useMemo(() => allDocs.map((d) => d.id), [allDocs]);
  // 文档列表被外部刷新(syncFromBackend/轮次结束)后,selected 里可能残留已删 id:
  // 派生一个只含现存文档的选择集,全选判定与批量删除都以它为准
  const selectedLive = useMemo(
    () => new Set(allIds.filter((id) => selected.has(id))),
    [allIds, selected]
  );
  const allSelected = allIds.length > 0 && selectedLive.size === allIds.length;

  const toggleOne = (id: number) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const toggleAll = () => {
    // 用 selectedLive(排除已不存在的 id)判定,残留旧 id 时也能正确切换
    setSelected((prev) => {
      const live = new Set(allIds.filter((id) => prev.has(id)));
      return live.size === allIds.length ? new Set<number>() : new Set(allIds);
    });
  };

  /** 文档详情抽屉里的来源会话(A2:对话保存的文档可回到来源会话)。 */
  const [detailSourceSession, setDetailSourceSession] = useState<string | null>(null);
  const [searchParams, setSearchParams] = useSearchParams();
  const detailId = searchParams.get("doc");
  const openDetail = (doc: KnowledgeDoc) => {
    setSearchParams(previous => { const next = new URLSearchParams(previous); next.set("doc", String(doc.id)); return next; });
  };
  const closeDetail = () => {
    setSearchParams(previous => { const next = new URLSearchParams(previous); next.delete("doc"); return next; });
  };
  useEffect(() => {
    let disposed = false;
    const version = ++detailVersion.current;
    chunkPending.current = false;
    setEditingChunkId(null); setEditingChunkText("");
    setAddingChunk(false); setNewChunkText(""); setChunkBusy(false);
    setDetail(null); setDetailSourceSession(null);
    if (!detailId) { setDetailLoading(null); return; }
    const id = Number(detailId);
    if (!Number.isSafeInteger(id) || id <= 0) { toast.error("Invalid document ID: " + detailId); return; }
    setDetailLoading(id);
    void (async () => {
      try {
        const value = await fetchDocDetail(id);
        if (disposed) return;
        setDetail(value);
        if (value.doc.source === "chat") {
          const artifacts = await savedArtifactsApi.list(200);
          if (!disposed) setDetailSourceSession(artifacts.find(a => a.kind === "knowledge_doc" && a.path === String(id))?.sessionId ?? null);
        }
      } catch (error) {
        if (!disposed) toast.error(error instanceof Error ? error.message : String(error));
      } finally { if (!disposed) setDetailLoading(null); }
    })();
    return () => { disposed = true; detailVersion.current = version + 1; };
  }, [detailId]);

  const handleReindex = async (doc: KnowledgeDoc) => {
    setBusyId(doc.id);
    try {
      await reindexDoc(doc.id);
      toast.success(`「${doc.name}」已重建索引`);
    } catch (e) {
      toast.error(`重建索引失败：${(e as Error).message}`);
    } finally {
      setBusyId(null);
    }
  };

  /** 停用/启用(阶段 B):停用=退出检索,数据与索引保留。 */
  const handleToggleEnabled = async (doc: KnowledgeDoc) => {
    const next = doc.enabled === false;
    setBusyId(doc.id);
    try {
      const updated = await setDocEnabled(doc.id, next);
      useKnowledgeDocs.setState((state) => ({
        docs: state.docs.map((d) => (d.id === doc.id ? { ...d, ...updated } : d)),
      }));
      toast.success(next
        ? `「${doc.name}」已启用,重新参与检索`
        : `「${doc.name}」已停用(退出检索,数据保留;可随时启用)`);
    } catch (e) {
      toast.error(`操作失败：${(e as Error).message}`);
    } finally {
      setBusyId(null);
    }
  };

  const runDelete = async (ids: number[]) => {
    setConfirmDelete(null);
    try {
      if (ids.length === 1) {
        await removeDoc(ids[0]);
      } else {
        await removeDocs(ids);
      }
      setSelected((prev) => {
        const next = new Set(prev);
        ids.forEach((id) => next.delete(id));
        return next;
      });
      toast.success(ids.length === 1 ? "已删除该文档" : `已删除 ${ids.length} 个文档`);
    } catch (e) {
      toast.error(`删除失败：${(e as Error).message}`);
    }
  };

  const submitRename = async () => {
    if (!renaming) return;
    const doc = renaming;
    setBusyId(doc.id);
    try {
      await renameDoc(doc.id, renameValue);
      setRenaming(null);
      toast.success("已重命名");
    } catch (e) {
      toast.error(`重命名失败：${(e as Error).message}`);
    } finally {
      setBusyId(null);
    }
  };

  return {
    closeDetail,
    allDocs, selectedLive, allSelected, toggleAll, toggleOne, grouped, busyId, renaming, setRenaming, renameValue, setRenameValue, detail, setDetail, detailLoading, confirmDelete, setConfirmDelete, bases, moving, setMoving, newBaseName, setNewBaseName, creatingBase, editingChunkId, setEditingChunkId, editingChunkText, setEditingChunkText, addingChunk, setAddingChunk, newChunkText, setNewChunkText, chunkBusy, handleChunkSave, handleChunkToggle, handleChunkDelete, handleChunkAdd, handleMove, handleCreateBase, detailSourceSession, openDetail, handleReindex, handleToggleEnabled, runDelete, submitRename
  };
}

export type DocumentLibraryState = ReturnType<typeof useDocumentLibrary>;
