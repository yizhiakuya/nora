import { useFileLibrary } from "@/hooks/useFileLibrary";
import { FilesHeader } from "./FilesHeader";
import { FileListing } from "./FileListing";
import { FileDialogs } from "./FileDialogs";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
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

      <div className="page-content custom-scroll">
        <div className="page-container">
          <div><h1 className="page-heading">资料</h1><p className="page-description">集中管理文件、长期知识与已保存的成果。</p></div>
          <Tabs value={dataView} onValueChange={value => switchDataView(value as "files" | "knowledge" | "results")}>
          {/* 资料视图切换(M1-03;B1 修正 2026-09-27):第三个 tab 改为
              「已保存成果」= 对话保存的文件/文档(saved_artifact 服务端登记,
              可打开并回到来源会话);自动任务执行记录在「任务 → 执行历史」 */}
          <TabsList aria-label="资料视图">
            {([
              { key: "files", label: "全部文件" },
              { key: "knowledge", label: "长期知识" },
              { key: "results", label: "已保存成果" },
            ] as const).map((v) => (
              <TabsTrigger key={v.key} value={v.key}>{v.label}</TabsTrigger>
            ))}
          </TabsList>
          <TabsContent value={dataView} className="mt-6">
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
          </TabsContent>
          </Tabs>

        </div>
      </div>

      <FileDialogs library={library} />
    </>
  );
}
