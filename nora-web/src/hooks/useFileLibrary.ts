'use client';

import { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { Bot, HardDrive, Trash2 } from "lucide-react";
import { useSelection } from "@/hooks/useSelection";
import { useSimulatedUpload } from "@/hooks/useUpload";
import { useFileViewer } from "@/hooks/useFileViewer";
import { toast } from "sonner";
import { FileItem } from "@/types";
import { useFiles } from "@/hooks/useFiles";
import { useKnowledgeDocs } from "@/hooks/useKnowledgeDocs";
import { useNotifications } from "@/hooks/useNotifications";
import { useRecentFiles } from "@/hooks/useRecentFiles";
import { usePreferences } from "@/hooks/usePreferences";
import { filesApi, humanSize, type BackendFolder } from "@/lib/services/filesApi";

export function useFileLibrary() {
  const [params, setParams] = useSearchParams();
  const workspacePath = params.get("workspace");
  const [searchQuery, setSearchQuery] = useState("");
  /**
   * 资料页视图(M1-03,2026-09-20;B1 命名修正 2026-09-27):files(全部文件,
   * 默认)/ knowledge(长期知识)/ results(任务结果——自动任务执行记录)。
   * URL ?view= 同步,刷新/返回键/复制链接保持一致。旧链接无 view 参数时按 files 处理。
   */
  const view = params.get("view");
  const dataView = view === "knowledge" || view === "results" ? view : "files";
  const switchDataView = (v: "files" | "knowledge" | "results") => {
    setCurrentFolder(null);
    setWorkspaceDir(null);
    setMediaCacheOpen(false);
    setTrashOpen(false);
    setParams((current) => {
      const next = new URLSearchParams(current);
      if (v === "files") next.delete("view");
      else next.set("view", v);
      next.delete("workspace");
      next.delete("open");
      return next;
    }, { replace: true });
  };
  /** 工作区导航状态:null=资料根视图;""=工作区根目录;"memory/..."=子目录。
   *  工作区是文件系统的一部分——像普通文件夹一样进入,而不是独立 Tab。 */
  const [workspaceDir, setWorkspaceDir] = useState<string | null>(null);
  /** 媒体缓存文件夹视图(与工作区互斥)。 */
  const [mediaCacheOpen, setMediaCacheOpen] = useState(false);
  /** 当前进入的用户文件夹(null = 根视图)。 */
  const [currentFolder, setCurrentFolder] = useState<BackendFolder | null>(null);
  /** 用户文件夹列表(来自后端)。 */
  const [folders, setFolders] = useState<BackendFolder[]>([]);
  /** 回收站视图。 */
  const [trashOpen, setTrashOpen] = useState(false);
  /** 排序:名称/大小/时间 × 升降序(前端排序,列表数据量级小)。 */
  const [sortBy, setSortBy] = useState<"name" | "size" | "date">("date");
  const [sortAsc, setSortAsc] = useState(false);
  /** 视图模式:列表(默认)/ 网格;偏好持久化(刷新保持)。 */
  const [viewMode, setViewMode] = useState<"list" | "grid">(() => {
    if (typeof window === "undefined") return "list";
    return (localStorage.getItem("nora-files-view") as "list" | "grid") || "list";
  });
  /** 小屏(手机/窄窗)自动用网格:列表表格在窄屏下操作列不可达;
   *  网格卡片是窄屏的自然形态。大屏仍按用户偏好。 */
  const [isNarrow, setIsNarrow] = useState(false);
  useEffect(() => {
    const mq = window.matchMedia("(max-width: 767px)");
    setIsNarrow(mq.matches);
    const onChange = (e: MediaQueryListEvent) => setIsNarrow(e.matches);
    mq.addEventListener("change", onChange);
    return () => mq.removeEventListener("change", onChange);
  }, []);
  const effectiveView = isNarrow ? "grid" : viewMode;
  const switchView = (mode: "list" | "grid") => {
    setViewMode(mode);
    try { localStorage.setItem("nora-files-view", mode); } catch { /* 隐私模式等忽略 */ }
  };
  /** 新建/重命名文件夹弹窗状态。 */
  const [folderDialog, setFolderDialog] = useState<{ mode: "create" } | { mode: "rename"; folder: BackendFolder } | null>(null);
  const [folderNameInput, setFolderNameInput] = useState("");
  /** 移动文件弹窗(选中文件 → 选择目标文件夹)。 */
  const [moveOpen, setMoveOpen] = useState(false);

  const files = useFiles((s) => s.files);
  const markIndexed = useFiles((s) => s.markIndexed);
  const syncFile = useFiles((s) => s.syncFile);
  const syncFromBackend = useFiles((s) => s.syncFromBackend);

  const upload = useSimulatedUpload();
  const viewer = useFileViewer();
  const indexFileFromBackend = useKnowledgeDocs((s) => s.indexFileFromBackend);
  const addNotification = useNotifications((s) => s.addNotification);
  const addRecent = useRecentFiles((s) => s.addRecent);

  // 后端模式:进入页面拉一次真实文件列表 + 文件夹列表
  useEffect(() => {
    void syncFromBackend();
    filesApi.listFolders().then(setFolders).catch(() => { /* 文件夹不可用时留空 */ });
  }, [syncFromBackend]);

  // 深链:?open=<fileId>(知识库「原文件」跳转)→ 拉取该文件并打开预览
  useEffect(() => {
    const openId = new URLSearchParams(window.location.search).get("open");
    if (!openId) return;
    let cancelled = false;
    (async () => {
      try {
        const { filesApi: api } = await import("@/lib/services/filesApi");
        const all = await api.listFiles();
        const target = all.find((f) => String(f.id) === openId);
        if (!target || cancelled) return;
        // 文件在文件夹内时先导航到所在文件夹,让列表与预览上下文一致
        if (target.folderId != null) {
          const folderList = await api.listFolders();
          const folder = folderList.find((fo) => fo.id === target.folderId);
          if (folder) setCurrentFolder(folder);
        }
        addRecent(target.name, target.type, `file:${target.id}`);
        // 列表上下文:目标所在视图(文件夹内/根),让弹窗内 ←/→ 可连续浏览
        const viewList = target.folderId != null
          ? all.filter((f) => f.folderId === target.folderId)
          : all.filter((f) => f.folderId == null);
        void viewer.open(target, viewList);
      } catch { /* 深链失败静默(正常列表仍可用) */ }
    })();
    return () => { cancelled = true; };
    // 仅首挂载执行一次;viewer/addRecent 为稳定引用
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 深链:?workspace=<相对路径>(产物画廊「打开」按钮)→ 进入工作区浏览器定位。
  // 目标是目录 → 直接进入;目标是文件 → 定位父目录并打开共享查看器。
  useEffect(() => {
    const wsPath = workspacePath;
    if (wsPath == null) return;
    if (wsPath === "") { setWorkspaceDir(""); return; }
    let cancelled = false;
    (async () => {
      const { workspaceApi } = await import("@/lib/services/workspaceApi");
      const parentDir = wsPath.includes("/") ? wsPath.slice(0, wsPath.lastIndexOf("/")) : "";
      try {
        // 父目录里查同名条目:directory=true → 目标是目录(直接进入);否则按文件处理
        const entries = await workspaceApi.listFiles(parentDir);
        if (cancelled) return;
        // path 为相对工作区根的路径(与 wsPath 同基准);endsWith 兜底兼容
        // 后端可能返回带前缀的形态
        const self = entries.find((e) => e.path === wsPath || e.path.endsWith("/" + wsPath));
        if (self?.directory) {
          setWorkspaceDir(wsPath);
        } else {
          setWorkspaceDir(parentDir);
          await useFileViewer.getState().openTargets([`workspace:${wsPath}`]);
        }
      } catch {
        // 查询失败退化为「按文件处理」(进父目录)
        if (!cancelled) setWorkspaceDir(parentDir);
      }
    })();
    return () => { cancelled = true; };
  }, [workspacePath]);

  /** 当前视图中的文件(根视图=无归属文件;文件夹内=该文件夹文件)。 */
  const inRootView = workspaceDir === null && !mediaCacheOpen && !trashOpen && currentFolder === null;
  const viewFiles = currentFolder === null
    ? files.filter((f) => f.folderId == null)
    : files.filter((f) => f.folderId === currentFolder.id);

  /** 排序 + 搜索(前端;列表数据量级小)。 */
  const parseSize = (s: string): number => {
    const m = s.match(/([\d.]+)\s*(B|KB|MB|GB)?/i);
    if (!m) return 0;
    const n = parseFloat(m[1]);
    const unit = (m[2] ?? "B").toUpperCase();
    return n * (unit === "GB" ? 1e9 : unit === "MB" ? 1e6 : unit === "KB" ? 1e3 : 1);
  };
  const sortedFiles = [...viewFiles].sort((a, b) => {
    let cmp: number;
    if (sortBy === "name") cmp = a.name.localeCompare(b.name, "zh");
    else if (sortBy === "size") cmp = parseSize(a.size) - parseSize(b.size);
    else cmp = (a.date ?? "").localeCompare(b.date ?? "");
    return sortAsc ? cmp : -cmp;
  });
  const filteredFiles = sortedFiles.filter((f) => f.name.toLowerCase().includes(searchQuery.toLowerCase()));
  const selection = useSelection(filteredFiles, "id");

  /** 上传目标文件夹(进入文件夹后上传到该文件夹)。 */
  useEffect(() => {
    upload.setTargetFolder(currentFolder?.id ?? null);
  }, [currentFolder, upload]);

  const refreshFolders = () => {
    filesApi.listFolders().then(setFolders).catch(() => { /* 忽略瞬时失败 */ });
  };

  const handleDeleteSelected = () => {
    const count = selection.selectedIds.length;
    filesApi.deleteFiles(selection.selectedIds)
      .then(() => {
        toast.success(`已删除 ${count} 个文件`);
        // 删除成功即标记查看器标签失效(打开中的文件立即提示,不等用户刷新)
        useFileViewer.getState().markMissing(selection.selectedIds.map(id => `file:${id}`));
        void syncFromBackend();
        refreshFolders();
      })
      .catch((e: Error) => toast.error(`删除失败：${e.message}`));
    selection.clearSelection();
  };

  /** 批量下载(服务端打包:单文件出原文件,多文件出 zip)。 */
  const handleDownloadSelected = () => {
    if (selection.selectedIds.length === 0) return;
    window.open(filesApi.downloadUrl(selection.selectedIds), "_blank");
  };

  /** 下载单个文件。 */
  const handleDownloadOne = (file: FileItem) => {
    window.open(filesApi.downloadUrl([file.id]), "_blank");
  };

  /** 重命名单个文件。 */
  const handleRenameFile = async (file: FileItem) => {
    const next = window.prompt("重命名文件", file.name);
    if (next == null || next.trim() === "" || next === file.name) return;
    try {
      const updated = await filesApi.renameFile(file.id, next.trim());
      syncFile(updated);
      toast.success("已重命名");
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "重命名失败");
    }
  };

  /** 移动单个文件(打开移动弹窗并预选它)。 */
  const [moveTargetIds, setMoveTargetIds] = useState<number[]>([]);
  const handleMoveOne = (file: FileItem) => {
    setMoveTargetIds([file.id]);
    setMoveOpen(true);
  };

  const handleMoveSelected = () => {
    if (selection.selectedIds.length === 0) return;
    setMoveTargetIds([...selection.selectedIds]);
    setMoveOpen(true);
  };

  /**
   * 交给助手(M2-02;B6 2026-09-27):选中文件作为结构化引用,先选去向
   * (新建处理 / 加入当前对话),再带入对话页预填指令与引用 chip。
   */
  const [handoff, setHandoff] = useState<{ prompt: string; refs: import("@/lib/handoff").HandoffRef[] } | null>(null);
  const handleAskAssistant = () => {
    if (selection.selectedIds.length === 0) return;
    const selected = files.filter((f) => selection.selectedIds.includes(f.id));
    const refs = selected.map((f) => ({ kind: "file" as const, id: f.id, name: f.name }));
    const prompt = selected.length === 1
      ? `请阅读并处理这份资料:${selected[0].name}`
      : `请比较这 ${selected.length} 份资料的差异,给我一份报告。`;
    setHandoff({ prompt, refs });
  };

  /** 执行移动。 */
  const doMove = async (folderId: number | null) => {
    try {
      const moved = await filesApi.moveFiles(moveTargetIds, folderId);
      toast.success(`已移动 ${moved} 个文件${folderId == null ? "到根目录" : ""}`);
      setMoveOpen(false);
      selection.clearSelection();
      await syncFromBackend();
      refreshFolders();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "移动失败");
    }
  };

  /** 提交新建/重命名文件夹。 */
  const submitFolderDialog = async () => {
    if (!folderDialog) return;
    const name = folderNameInput.trim();
    if (!name) {
      toast.error("文件夹名不能为空");
      return;
    }
    try {
      if (folderDialog.mode === "create") {
        await filesApi.createFolder(name);
        toast.success(`已创建文件夹「${name}」`);
      } else {
        await filesApi.renameFolder(folderDialog.folder.id, name);
        toast.success("已重命名");
      }
      setFolderDialog(null);
      setFolderNameInput("");
      refreshFolders();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "操作失败");
    }
  };

  /** 删除文件夹(文件回根目录)。 */
  const handleDeleteFolder = async (folder: BackendFolder) => {
    if (!window.confirm(`确认删除文件夹「${folder.name}」？\n其中的 ${folder.fileCount} 个文件会回到根目录（不删除文件）。`)) return;
    try {
      const moved = await filesApi.deleteFolder(folder.id);
      toast.success(`已删除文件夹${moved > 0 ? `，${moved} 个文件已回到根目录` : ""}`);
      refreshFolders();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "删除失败");
    }
  };

  const handleUploadComplete = (uploadedFile?: FileItem) => {
    if (!uploadedFile) return;
    syncFile(uploadedFile);
    addRecent(uploadedFile.name, uploadedFile.type, `file:${uploadedFile.id}`);
    addNotification("上传完成", `「${uploadedFile.name}」已保存到资料，可在列表中查看。`);
    refreshFolders();
    // 上传后自动入库(2026-09-19 接线):「设置 → 知识库与 AI」的开关
    // 此前存了没人消费——现在真正生效(仅对可提取文本的文件有意义,
    // 后端对无文本文件返回 422 时静默跳过,不打断上传流程)
    if (usePreferences.getState().knowledgeAI.autoIndex) {
      void indexFileFromBackend(uploadedFile.id, uploadedFile.name)
        .then(() => {
          markIndexed(uploadedFile.id);
          addNotification("文件索引入库",
            `「${uploadedFile.name}」已自动加入知识库(可在设置中关闭自动索引)。`, "indexed");
        })
        .catch(() => { /* 无文本/未配置嵌入:静默(手动索引入口仍在) */ });
    }
  };

  const [indexTarget, setIndexTarget] = useState<FileItem | null>(null);
  const [indexSubmitting, setIndexSubmitting] = useState(false);

  const handleIndexFile = (file: FileItem) => {
    // F5(2026-09-26):先进配置弹窗(目标资料库 + 分段参数 + 预览),
    // 确认后再带参数提交——此前固定只发 fileId/name,后端参数被忽略
    setIndexTarget(file);
  };

  /** 配置弹窗确认:带分段配置与目标资料库真实提交(F5)。 */
  const handleIndexConfirm = (file: FileItem, chunkConfig: { mode?: string; chunkSize?: number; overlap?: number; separator?: string }, baseId: number | null) => {
    setIndexSubmitting(true);
    indexFileFromBackend(file.id, file.name, chunkConfig, baseId)
      .then(() => {
        // 索引是异步的:file-service 触发 rag-service,完成后回调置位;这里先乐观标记
        markIndexed(file.id);
        addNotification(
          "文件索引入库",
          `「${file.name}」已开始解析与向量化，完成后 AI 即可检索其内容。`,
          "indexed"
        );
        toast.success(`「${file.name}」索引任务已提交`);
        setIndexTarget(null);
      })
      .catch((e: Error) => toast.error(`索引失败：${e.message}`))
      .finally(() => setIndexSubmitting(false));
  };

  /** 共享的文件夹行数据(列表/网格两视图共用同一来源与动作)。 */
  const folderRowsData = currentFolder === null ? [
    ...(currentFolder === null && !mediaCacheOpen ? [
      {
        name: "Agent 工作区",
        description: "AI 的工作目录与长期记忆(SOUL/AGENTS/USER/MEMORY.md)",
        icon: Bot,
        onOpen: () => setWorkspaceDir(""),
      },
      {
        name: "媒体缓存",
        description: "相册等远程媒体的本地副本(查看秒开、手机离线可看)",
        icon: HardDrive,
        onOpen: () => setMediaCacheOpen(true),
      },
      {
        name: "回收站",
        description: "已删除的文件（可恢复或彻底删除）",
        icon: Trash2,
        onOpen: () => setTrashOpen(true),
      },
    ] : []),
    ...folders.map((folder) => ({
      name: folder.name,
      description: `${folder.fileCount} 个文件 · ${humanSize(folder.totalBytes)}`,
      icon: undefined,
      onOpen: () => setCurrentFolder(folder),
      onRename: () => {
        setFolderNameInput(folder.name);
        setFolderDialog({ mode: "rename", folder });
      },
      onDelete: () => void handleDeleteFolder(folder),
      // 拖拽文件到此文件夹 = 移动(拖拽整理的自然交互)
      onDropFiles: (ids: number[]) => {
        void (async () => {
          try {
            const moved = await filesApi.moveFiles(ids, folder.id);
            toast.success(`已移动 ${moved} 个文件到「${folder.name}」`);
            selection.clearSelection();
            await syncFromBackend();
            refreshFolders();
          } catch (e) {
            toast.error(e instanceof Error ? e.message : "移动失败");
          }
        })();
      },
    })),
  ] : [];

  /** 面包屑:根 / 用户文件夹(动态段)。 */
  const breadcrumbTail = currentFolder
    ? [{ label: currentFolder.name, isCurrent: true }]
    : [];

  const handleDeleteOne = (file: FileItem) => {
    filesApi.deleteFiles([file.id]).then(() => {
      toast.success("已删除");
      useFileViewer.getState().markMissing([`file:${file.id}`]);
      void syncFromBackend();
      refreshFolders();
    }).catch((e: Error) => toast.error(`删除失败：${e.message}`));
  };

  return {
    dataView, switchDataView, workspaceDir, setWorkspaceDir, mediaCacheOpen, setMediaCacheOpen, trashOpen, setTrashOpen, currentFolder, setCurrentFolder, inRootView, breadcrumbTail, searchQuery, setSearchQuery, folderNameInput, setFolderNameInput, folderDialog, setFolderDialog, upload, viewMode, switchView, sortBy, setSortAsc, setSortBy, sortAsc, viewFiles, folders, selection, handleDownloadSelected, handleMoveSelected, handleDeleteSelected, handleAskAssistant, effectiveView, filteredFiles, viewer, handleIndexFile, handleDownloadOne, handleRenameFile, handleMoveOne, handleDeleteOne, folderRowsData, submitFolderDialog, moveOpen, setMoveOpen, moveTargetIds, doMove, handoff, setHandoff, handleUploadComplete, syncFromBackend, refreshFolders, indexTarget, setIndexTarget, handleIndexConfirm, indexSubmitting
  };
}

export type FileLibraryState = ReturnType<typeof useFileLibrary>;
