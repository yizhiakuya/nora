'use client';

import { useEffect, useState } from "react";
import { Header } from "@/components/layout/Header";
import { Search, FolderPlus, CloudUpload, Bot, HardDrive, Trash2, LayoutGrid, List } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Modal } from "@/components/ui/custom/Modal";
import { FileTable, BatchActionBar } from "@/components/files/FileTable";
import { FileGrid } from "@/components/files/FileGrid";
import { UploadModal } from "@/components/ui/custom/UploadModal";
import { useSelection } from "@/hooks/useSelection";
import { useSimulatedUpload } from "@/hooks/useUpload";
import { useFileViewer } from "@/hooks/useFileViewer";
import { FileViewerModal } from "@/components/files/viewer/FileViewerModal";
import { WorkspaceBrowser } from "@/components/files/WorkspaceBrowser";
import { MediaCacheBrowser } from "@/components/files/MediaCacheBrowser";
import { TrashBrowser } from "@/components/files/TrashBrowser";
import { toast } from "sonner";
import { FileItem } from "@/types";
import { useFiles } from "@/hooks/useFiles";
import { useKnowledgeDocs } from "@/hooks/useKnowledgeDocs";
import { useNotifications } from "@/hooks/useNotifications";
import { useRecentFiles } from "@/hooks/useRecentFiles";
import { usePreferences } from "@/hooks/usePreferences";
import { filesApi, humanSize, type BackendFolder } from "@/lib/services/filesApi";
import { USE_BACKEND } from "@/lib/api/client";
import { KnowledgeView } from "@/components/knowledge/KnowledgeView";
import { SavedResultsView } from "@/components/files/SavedResultsView";

export default function FilesPage() {
  const [searchQuery, setSearchQuery] = useState("");
  /**
   * 资料页视图(M1-03,2026-09-20,方案 §4.2):files(全部文件,默认)/
   * knowledge(长期知识)/ results(已保存成果)。URL ?view= 同步,刷新/
   * 返回键/复制链接保持一致。旧链接无 view 参数时按 files 处理。
   */
  const [dataView, setDataView] = useState<"files" | "knowledge" | "results">(() => {
    const v = new URLSearchParams(window.location.search).get("view");
    return v === "knowledge" || v === "results" ? v : "files";
  });
  const switchDataView = (v: "files" | "knowledge" | "results") => {
    setDataView(v);
    // 切视图时退出子视图(文件夹/工作区/缓存/回收站),避免状态叠加
    setCurrentFolder(null);
    setWorkspaceDir(null);
    setMediaCacheOpen(false);
    setTrashOpen(false);
    const params = new URLSearchParams(window.location.search);
    if (v === "files") params.delete("view");
    else params.set("view", v);
    const qs = params.toString();
    window.history.replaceState(null, "", `/files${qs ? `?${qs}` : ""}`);
  };
  /** 工作区导航状态:null=文件中心根视图;""=工作区根目录;"memory/..."=子目录。
   *  工作区是文件系统的一部分——像普通文件夹一样进入,而不是独立 Tab。 */
  const [workspaceDir, setWorkspaceDir] = useState<string | null>(null);
  /** 媒体缓存文件夹视图(与工作区互斥)。 */
  const [mediaCacheOpen, setMediaCacheOpen] = useState(false);
  /** 当前进入的用户文件夹(null = 根视图)。 */
  const [currentFolder, setCurrentFolder] = useState<BackendFolder | null>(null);
  /** 用户文件夹列表(后端;mock 模式为空)。 */
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
  const addFile = useFiles((s) => s.addFile);
  const deleteFiles = useFiles((s) => s.deleteFiles);
  const markIndexed = useFiles((s) => s.markIndexed);
  const syncFile = useFiles((s) => s.syncFile);
  const syncFromBackend = useFiles((s) => s.syncFromBackend);

  const upload = useSimulatedUpload();
  const viewer = useFileViewer();
  const indexFile = useKnowledgeDocs((s) => s.indexFile);
  const indexFileFromBackend = useKnowledgeDocs((s) => s.indexFileFromBackend);
  const addNotification = useNotifications((s) => s.addNotification);
  const addRecent = useRecentFiles((s) => s.addRecent);

  // 后端模式:进入页面拉一次真实文件列表 + 文件夹列表
  useEffect(() => {
    if (!USE_BACKEND) return;
    filesApi.listFiles()
      .then((items) => items.forEach((item) => syncFile(item)))
      .catch(() => { /* 后端不可用时沿用本地缓存 */ });
    filesApi.listFolders().then(setFolders).catch(() => { /* 文件夹不可用时留空 */ });
  }, [syncFile]);

  // 深链:?open=<fileId>(知识库「原文件」跳转)→ 拉取该文件并打开预览
  useEffect(() => {
    if (!USE_BACKEND) return;
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
        addRecent(target.name, target.type);
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
  // 目标是目录 → 直接进入;目标是文件 → 进入其所在目录(2026-09-18)。
  useEffect(() => {
    const wsPath = new URLSearchParams(window.location.search).get("workspace");
    if (wsPath == null) return;
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
        }
      } catch {
        // 查询失败退化为「按文件处理」(进父目录)
        if (!cancelled) setWorkspaceDir(parentDir);
      }
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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
    if (!USE_BACKEND) return;
    filesApi.listFolders().then(setFolders).catch(() => { /* 忽略瞬时失败 */ });
  };

  const handleDeleteSelected = () => {
    const count = selection.selectedIds.length;
    if (USE_BACKEND) {
      filesApi.deleteFiles(selection.selectedIds)
        .then(() => {
          toast.success(`已删除 ${count} 个文件`);
          void syncFromBackend();
          refreshFolders();
        })
        .catch((e: Error) => toast.error(`删除失败：${e.message}`));
      selection.clearSelection();
      return;
    }
    deleteFiles(selection.selectedIds);
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

  const handleUploadComplete = (fileName?: string, uploadedFile?: FileItem) => {
    if (uploadedFile) {
      syncFile(uploadedFile);
      addRecent(uploadedFile.name, uploadedFile.type);
      addNotification("上传完成", `「${uploadedFile.name}」已保存到文件中心，可在列表中查看。`);
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
      return;
    }
    const defaultName = `上传文档_${Date.now().toString().slice(-4)}.pdf`;
    const newFile = addFile(fileName ?? defaultName);
    addRecent(newFile.name, newFile.type);
    addNotification("上传完成", `「${newFile.name}」已保存到文件中心，可在列表中查看。`);
  };

  const handleIndexFile = (file: FileItem) => {
    if (USE_BACKEND) {
      indexFileFromBackend(file.id, file.name)
        .then(() => {
          // 索引是异步的:file-service 触发 rag-service,完成后回调置位;这里先乐观标记
          markIndexed(file.id);
          addNotification(
            "文件索引入库",
            `「${file.name}」已开始解析与向量化，完成后 AI 即可检索其内容。`,
            "indexed"
          );
          toast.success(`「${file.name}」索引任务已提交`);
        })
        .catch((e: Error) => toast.error(`索引失败：${e.message}`));
      return;
    }
    indexFile(file.name);
    markIndexed(file.id);
    addNotification(
      "文件索引入库",
      `「${file.name}」已完成解析与向量化，AI 现在可以检索其内容。`,
      "indexed"
    );
    toast.success(`「${file.name}」已加入知识库`);
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
      onRename: USE_BACKEND ? () => {
        setFolderNameInput(folder.name);
        setFolderDialog({ mode: "rename", folder });
      } : undefined,
      onDelete: USE_BACKEND ? () => void handleDeleteFolder(folder) : undefined,
      // 拖拽文件到此文件夹 = 移动(拖拽整理的自然交互)
      onDropFiles: USE_BACKEND ? (ids: number[]) => {
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
      } : undefined,
    })),
  ] : [];

  /** 面包屑:根 / 用户文件夹(动态段)。 */
  const breadcrumbTail = currentFolder
    ? [{ label: currentFolder.name, isCurrent: true }]
    : [];

  return (
    <>
      <Header
        breadcrumbs={[
          { label: "工作台", href: "/", isCurrent: false },
          {
            label: "文件中心",
            isCurrent: inRootView,
            // 非根视图(文件夹/工作区/媒体缓存/回收站内)时点击回退到文件中心根
            onClick: inRootView ? undefined : () => {
              setCurrentFolder(null);
              setWorkspaceDir(null);
              setMediaCacheOpen(false);
              setTrashOpen(false);
            },
          },
          ...(workspaceDir !== null
            ? [{ label: "Agent 工作区", isCurrent: true }]
            : []),
          ...(mediaCacheOpen
            ? [{ label: "媒体缓存", isCurrent: true }]
            : []),
          ...(trashOpen
            ? [{ label: "回收站", isCurrent: true }]
            : []),
          ...breadcrumbTail,
        ]}
        actions={
          <div className="flex items-center gap-1 sm:gap-2 shrink-0">
            {workspaceDir === null && !mediaCacheOpen && !trashOpen && (
            <>
            <div className="relative w-[100px] sm:w-[180px] shrink-0">
              <Search className="absolute left-2.5 top-1/2 transform -translate-y-1/2 text-muted-foreground w-3.5 h-3.5" />
              <Input
                placeholder="搜索..."
                className="pl-7 pr-3 py-1.5 h-8 bg-muted border-border text-xs focus-visible:ring-1 focus-visible:ring-blue-500 transition-all w-full"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
              />
            </div>
            <div className="w-px h-5 bg-gray-200 dark:bg-gray-800 mx-1 shrink-0 hidden sm:block"></div>
            <Button
              variant="outline"
              size="sm"
              className="h-8 text-xs bg-card text-foreground hover:bg-muted shrink-0 hidden md:flex"
              onClick={() => {
                if (!USE_BACKEND) {
                  toast.info("文件夹需要连接后端服务");
                  return;
                }
                setFolderNameInput("");
                setFolderDialog({ mode: "create" });
              }}
            >
              <FolderPlus className="w-3.5 h-3.5 mr-1.5" /> 新建文件夹
            </Button>
            <Button size="sm" className="h-8 text-xs bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600 shrink-0" onClick={upload.open}>
              <CloudUpload className="w-3.5 h-3.5 sm:mr-1.5" /> <span className="hidden sm:inline">{currentFolder ? `上传到「${currentFolder.name}」` : "上传文件"}</span>
            </Button>
            </>
            )}
          </div>
        }
      />

      <div className="flex-1 overflow-y-auto custom-scroll p-4 sm:p-6 bg-background relative">
        <div className="max-w-6xl mx-auto pb-24">
          {/* 资料视图切换(M1-03):全部文件 / 长期知识 / 已保存成果 */}
          <div role="tablist" aria-label="资料视图" className="flex gap-1 p-1 bg-muted/50 rounded-lg w-fit mb-4">
            {([
              { key: "files", label: "全部文件" },
              { key: "knowledge", label: "长期知识" },
              { key: "results", label: "已保存成果" },
            ] as const).map((v) => (
              <button
                key={v.key}
                type="button"
                role="tab"
                aria-selected={dataView === v.key}
                onClick={() => switchDataView(v.key)}
                className={`px-4 py-1.5 text-xs font-medium rounded-md cursor-pointer transition-all ${dataView === v.key ? "bg-card text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground"}`}
              >
                {v.label}
              </button>
            ))}
          </div>

          {dataView === "knowledge" ? (
            <KnowledgeView />
          ) : dataView === "results" ? (
            <SavedResultsView />
          ) : trashOpen ? (
            <TrashBrowser onExit={() => setTrashOpen(false)} />
          ) : mediaCacheOpen ? (
            <MediaCacheBrowser onExit={() => setMediaCacheOpen(false)} />
          ) : workspaceDir !== null ? (
            <WorkspaceBrowser
              dir={workspaceDir}
              onNavigate={setWorkspaceDir}
              onExit={() => setWorkspaceDir(null)}
            />
          ) : (
            <>
              {/* 面包屑(文件夹内可点击返回根) */}
              {currentFolder && (
                <div className="flex items-center gap-1.5 mb-4 text-xs animate-in fade-in">
                  <button
                    type="button"
                    className="text-muted-foreground hover:text-foreground transition-colors"
                    onClick={() => setCurrentFolder(null)}
                  >
                    文件中心
                  </button>
                  <span className="text-muted-foreground/50">/</span>
                  <span className="text-foreground font-medium">{currentFolder.name}</span>
                </div>
              )}

              <div className="flex items-center justify-between mb-4 animate-in fade-in gap-3 flex-wrap">
                <h2 className="text-sm font-bold text-foreground">{currentFolder ? currentFolder.name : "所有文件"}</h2>
                <div className="flex items-center gap-3">
                  {/* 视图切换:列表 / 网格(窄屏自动网格,切换按钮隐藏) */}
                  <div className="hidden md:flex items-center rounded-lg border border-border overflow-hidden">
                    <button
                      type="button"
                      title="列表视图"
                      onClick={() => switchView("list")}
                      className={`p-1.5 transition-colors cursor-pointer ${viewMode === "list" ? "bg-muted text-foreground" : "text-muted-foreground hover:text-foreground"}`}
                    >
                      <List className="w-3.5 h-3.5" />
                    </button>
                    <button
                      type="button"
                      title="网格视图"
                      onClick={() => switchView("grid")}
                      className={`p-1.5 transition-colors cursor-pointer ${viewMode === "grid" ? "bg-muted text-foreground" : "text-muted-foreground hover:text-foreground"}`}
                    >
                      <LayoutGrid className="w-3.5 h-3.5" />
                    </button>
                  </div>
                  {/* 排序控件:名称/大小/时间,点击切换升降序 */}
                  <div className="flex items-center gap-1 text-xs">
                    {([
                      ["date", "时间"],
                      ["name", "名称"],
                      ["size", "大小"],
                    ] as const).map(([key, label]) => (
                      <button
                        key={key}
                        type="button"
                        className={`px-1.5 py-0.5 rounded transition-colors ${sortBy === key ? "bg-muted text-foreground font-medium" : "text-muted-foreground hover:text-foreground"}`}
                        onClick={() => {
                          if (sortBy === key) setSortAsc((v) => !v);
                          else {
                            setSortBy(key);
                            setSortAsc(false);
                          }
                        }}
                      >
                        {label}
                        {sortBy === key && <span className="ml-0.5">{sortAsc ? "↑" : "↓"}</span>}
                      </button>
                    ))}
                  </div>
                  <div className="text-xs text-muted-foreground">
                    共 {viewFiles.length} 个文件{!currentFolder && folders.length > 0 ? ` · ${folders.length} 个文件夹` : ""}
                  </div>
                </div>
              </div>

              {/* 批量操作栏(两视图共用;网格视图此前缺失) */}
              <BatchActionBar
                selection={selection}
                onDownloadSelected={handleDownloadSelected}
                onMoveSelected={USE_BACKEND ? handleMoveSelected : undefined}
                onDeleteSelected={handleDeleteSelected}
              />

              {effectiveView === "list" ? (
                <FileTable
                  files={filteredFiles}
                  selection={selection}
                  onDeleteSelected={handleDeleteSelected}
                  onDownloadSelected={handleDownloadSelected}
                  onMoveSelected={USE_BACKEND ? handleMoveSelected : undefined}
                  onOpen={(f) => { addRecent(f.name, f.type); void viewer.open(f, filteredFiles); }}
                  onIndex={handleIndexFile}
                  onDownload={(f) => handleDownloadOne(f)}
                  onRename={USE_BACKEND ? handleRenameFile : undefined}
                  onMove={USE_BACKEND ? handleMoveOne : undefined}
                  onDelete={(f) => {
                    if (USE_BACKEND) {
                      filesApi.deleteFiles([f.id])
                        .then(() => { toast.success("已删除"); void syncFromBackend(); refreshFolders(); })
                        .catch((e: Error) => toast.error(`删除失败：${e.message}`));
                      return;
                    }
                    deleteFiles([f.id]);
                    toast.success("已删除");
                  }}
                  folderRows={folderRowsData}
                />
              ) : (
                <FileGrid
                  files={filteredFiles}
                  selection={selection}
                  onOpen={(f) => { addRecent(f.name, f.type); void viewer.open(f, filteredFiles); }}
                  onDownload={(f) => handleDownloadOne(f)}
                  onRename={USE_BACKEND ? handleRenameFile : undefined}
                  onMove={USE_BACKEND ? handleMoveOne : undefined}
                  onDelete={(f) => {
                    if (USE_BACKEND) {
                      filesApi.deleteFiles([f.id])
                        .then(() => { toast.success("已删除"); void syncFromBackend(); refreshFolders(); })
                        .catch((e: Error) => toast.error(`删除失败：${e.message}`));
                      return;
                    }
                    deleteFiles([f.id]);
                    toast.success("已删除");
                  }}
                  folderRows={folderRowsData}
                />
              )}
            </>
          )}

        </div>
      </div>

      {/* 新建/重命名文件夹弹窗 */}
      <Modal
        isOpen={folderDialog != null}
        onClose={() => setFolderDialog(null)}
        title={folderDialog?.mode === "rename" ? "重命名文件夹" : "新建文件夹"}
        width="w-[92%] sm:w-[420px]"
        footer={
          <>
            <Button variant="outline" size="sm" onClick={() => setFolderDialog(null)}>取消</Button>
            <Button size="sm" onClick={submitFolderDialog}>
              {folderDialog?.mode === "rename" ? "保存" : "创建"}
            </Button>
          </>
        }
      >
        <Input
          autoFocus
          placeholder="文件夹名称"
          value={folderNameInput}
          onChange={(e) => setFolderNameInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") void submitFolderDialog();
          }}
        />
      </Modal>

      {/* 移动文件弹窗 */}
      <Modal
        isOpen={moveOpen}
        onClose={() => setMoveOpen(false)}
        title={`移动 ${moveTargetIds.length} 个文件到…`}
        width="w-[92%] sm:w-[460px]"
      >
        <div className="space-y-1.5">
          <button
            type="button"
            className="w-full text-left px-3 py-2 rounded-lg border border-border hover:bg-muted transition-colors text-sm"
            onClick={() => void doMove(null)}
          >
            📁 根目录（不放入文件夹）
          </button>
          {folders.map((folder) => (
            <button
              key={folder.id}
              type="button"
              className="w-full text-left px-3 py-2 rounded-lg border border-border hover:bg-muted transition-colors text-sm"
              onClick={() => void doMove(folder.id)}
            >
              📁 {folder.name}
              <span className="ml-2 text-xs text-muted-foreground">{folder.fileCount} 个文件</span>
            </button>
          ))}
          {folders.length === 0 && (
            <div className="text-xs text-muted-foreground py-3 text-center">
              还没有文件夹——先在工具栏「新建文件夹」创建
            </div>
          )}
        </div>
      </Modal>

      <UploadModal
        upload={upload}
        title={currentFolder ? `上传到「${currentFolder.name}」` : "上传到文件中心"}
        onUploadComplete={handleUploadComplete}
        onBatchComplete={(ok) => {
          // 多文件批量完成:刷新列表与文件夹计数(逐个 sync 已在 onUploadComplete 做过,
          // 这里再拉一次保证一致——批量上传几十个文件时避免逐条去重遗漏)
          if (ok > 0) {
            void syncFromBackend();
            refreshFolders();
          }
        }}
      />
      <FileViewerModal
        file={viewer.activeFile}
        preview={viewer.preview}
        status={viewer.status}
        onClose={viewer.close}
        onNavigate={viewer.navigate}
        hasPrev={viewer.hasPrev}
        hasNext={viewer.hasNext}
        position={viewer.position}
      />
    </>
  );
}
