'use client';

import { SOURCE_META } from "@/lib/knowledgeSourceMeta";
import { KnowledgeDoc, KnowledgeSource } from "@/types";

import type { DocumentLibraryState } from "@/hooks/useDocumentLibrary";
import { DocumentRow } from "./DocumentRow";

export function DocumentGroup({ src, docs, library }: { src: KnowledgeSource; docs: KnowledgeDoc[]; library: DocumentLibraryState }) {
  const meta = SOURCE_META[src];
  const Icon = meta.icon;
  return (<>
          <div key={src}>
            <div className="flex items-center gap-2 mb-3">
              <Icon className={`w-4 h-4 ${meta.color}`} />
              <span className="text-sm font-bold text-foreground">{meta.label}</span>
              <span className="text-xs text-muted-foreground">({docs.length})</span>
            </div>
            <div className="bg-card border border-border rounded-xl overflow-hidden overflow-x-auto">
              <table className="w-full text-left table-fixed md:table-auto md:min-w-[640px]">
                <thead>
                  <tr className="bg-muted border-b border-border text-xs text-muted-foreground">
                    <th className="p-3 pl-4 w-10" />
                    <th className="p-3 font-medium">文档名</th>
                    <th className="p-3 font-medium hidden md:table-cell">Chunks</th>
                    <th className="p-3 font-medium hidden lg:table-cell">大小</th>
                    <th className="p-3 font-medium hidden lg:table-cell">质量</th>
                    <th className="p-3 font-medium w-[76px] md:w-auto">状态</th>
                    <th className="p-3 font-medium text-right pr-4 hidden md:table-cell">更新时间</th>
                    <th className="p-3 font-medium text-right pr-4 w-[104px] md:w-auto">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {docs.map((doc) => <DocumentRow key={doc.id} doc={doc} library={library} />)}
                </tbody>
              </table>
            </div>
          </div>
  </>);
}
