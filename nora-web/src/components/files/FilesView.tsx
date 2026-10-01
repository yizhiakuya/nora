import { useFileLibrary } from "@/hooks/useFileLibrary";
import { FilesHeader } from "./FilesHeader";
import { FileListing } from "./FileListing";
import { FileDialogs } from "./FileDialogs";
import { KnowledgeView } from "@/components/knowledge/KnowledgeView";
import { SavedArtifactsView } from "./SavedArtifactsView";
import { WorkspaceBrowser } from "./WorkspaceBrowser";
import { MediaCacheBrowser } from "./MediaCacheBrowser";
import { TrashBrowser } from "./TrashBrowser";

export function FilesView() {
  const library = useFileLibrary();
  const { dataView, switchDataView, trashOpen, setTrashOpen, mediaCacheOpen,
    setMediaCacheOpen, workspaceDir, setWorkspaceDir } = library;
  return (
    <>
      <FilesHeader library={library} />

      <div className="flex-1 overflow-y-auto custom-scroll p-4 sm:p-6 bg-background relative">
        <div className="max-w-6xl mx-auto pb-24">
          {/* 资料视图切换(M1-03;B1 修正 2026-09-27):第三个 tab 改为
              「已保存成果」= 对话保存的文件/文档(saved_artifact 服务端登记,
              可打开并回到来源会话);自动任务执行记录在「任务 → 执行历史」 */}
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
            <SavedArtifactsView />
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
              <FileListing library={library} />
            </>
          )}

        </div>
      </div>

      <FileDialogs library={library} />
    </>
  );
}
