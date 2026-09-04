import { Sparkles, Brain, Wrench, Database, MessageSquare, ChevronDown, Loader2, BookOpen, FileCode, Server, FileText, Check } from "lucide-react";
import { useState } from "react";
import { ChatMessage } from "@/lib/api/chatApi";
import { Markdown } from "@/components/shared/Markdown";
import { toast } from "sonner";
import { useKnowledgeDocs } from "@/hooks/useKnowledgeDocs";

const SOURCE_ICON: Record<string, React.ElementType> = {
  file: FileText,
  database: Database,
  repo: FileCode,
  environment: Server,
  chat: MessageSquare,
};

function SourceCitations({ sources }: { sources: NonNullable<ChatMessage["sources"]> }) {
  return (
    <div className="relative animate-in fade-in slide-in-from-bottom-2">
      <div className="absolute -left-[27.5px] w-5 h-5 rounded-full bg-purple-50 dark:bg-purple-950/40 border border-purple-100 dark:border-purple-900 flex items-center justify-center top-0 shadow-[0_0_0_2px_rgba(255,255,255,1)] dark:shadow-[0_0_0_2px_rgba(17,24,39,1)]">
        <BookOpen className="w-[10px] h-[10px] text-purple-500 dark:text-purple-400" />
      </div>
      <div className="pt-0.5">
        <div className="text-[10px] text-muted-foreground mb-2">引用来源 · {sources.length} 个知识库片段</div>
        <div className="space-y-2">
          {sources.map((s, i) => {
            const Icon = SOURCE_ICON[s.source] ?? FileText;
            return (
              <div key={i} className="bg-card border border-border rounded-xl p-3 relative overflow-hidden">
                <div className="absolute left-0 top-0 bottom-0 w-1 bg-purple-500" style={{ opacity: s.score }} />
                <div className="flex items-center justify-between mb-1.5">
                  <div className="flex items-center gap-1.5 min-w-0">
                    <Icon className="w-3 h-3 text-purple-500 dark:text-purple-400 shrink-0" />
                    <span className="text-xs font-medium text-foreground truncate">{s.docName}</span>
                    <span className="text-[9px] text-muted-foreground shrink-0">chunk #{s.chunkIndex}</span>
                  </div>
                  <span className="text-[9px] font-bold text-purple-600 dark:text-purple-400 bg-purple-50 dark:bg-purple-950/40 px-1.5 py-0.5 rounded-full tabular-nums shrink-0">
                    {(s.score * 100).toFixed(0)}%
                  </span>
                </div>
                <p className="text-[11px] text-muted-foreground leading-relaxed">{s.snippet}</p>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

function SaveToKnowledgeButton({ msg }: { msg: ChatMessage }) {
  const [saved, setSaved] = useState(false);
  const addChatDoc = useKnowledgeDocs((s) => s.addChatDoc);

  const handleSave = () => {
    if (saved) return;
    const title = `对话结论 · ${msg.content.slice(0, 24).replace(/[#*\n]/g, "").trim()}…`;
    addChatDoc(title, msg.content);
    setSaved(true);
    toast.success("已保存到知识库（对话产出）");
  };

  return (
    <button
      type="button"
      onClick={handleSave}
      className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-[11px] font-medium border transition-colors cursor-pointer ${saved ? "bg-green-50 dark:bg-green-950/40 text-green-600 dark:text-green-400 border-green-200 dark:border-green-800" : "bg-card text-muted-foreground border-border hover:text-blue-600 dark:hover:text-blue-400 hover:border-blue-300 dark:hover:border-blue-700"}`}
    >
      {saved ? <Check className="w-3 h-3" /> : <BookOpen className="w-3 h-3" />}
      {saved ? "已保存到知识库" : "保存到知识库"}
    </button>
  );
}

export function ChatMessageItem({ msg }: { msg: ChatMessage }) {
  if (msg.role === 'user') {
    return (
      <>
        <div className="flex gap-4 animate-in fade-in slide-in-from-bottom-2">
            <div className="flex-1"></div>
            <div className="bg-background p-4 rounded-2xl rounded-tr-sm border border-border text-sm leading-relaxed max-w-[80%] whitespace-pre-wrap">
                {msg.content}
            </div>
            <div className="w-8 h-8 rounded-full flex-shrink-0 bg-blue-500 text-white text-[10px] font-bold flex items-center justify-center shadow-sm">NC</div>
        </div>
        <div className="text-right text-[10px] text-muted-foreground pr-12 mt-1">{msg.timestamp}</div>
      </>
    );
  }

  return (
    <>
      <div className="flex gap-4 animate-in fade-in slide-in-from-bottom-2">
          <div className="w-8 h-8 bg-blue-600 dark:bg-blue-500 rounded-full flex items-center justify-center text-white flex-shrink-0 shadow-sm mt-1">
              <Sparkles className="w-4 h-4" />
          </div>
          <div className="flex-1 overflow-hidden">
              <div className="text-sm font-medium flex items-center gap-2 mb-4">AI 助理 <span className="text-[10px] text-muted-foreground font-normal">{msg.timestamp}</span></div>
              
              <div className="relative pl-6 space-y-5 before:absolute before:inset-y-2 before:left-2.5 before:w-px before:bg-gray-200 dark:before:bg-gray-700">
                  {msg.steps?.map((step, idx) => (
                      <div key={step.id || idx} className="relative animate-in fade-in slide-in-from-top-2">
                          <div className="absolute -left-[27.5px] w-5 h-5 rounded-full bg-[#f8fafc] dark:bg-gray-900 border border-border flex items-center justify-center top-0 shadow-[0_0_0_2px_rgba(255,255,255,1)]">
                              {step.type === 'think' ? <Brain className="w-[10px] h-[10px] text-muted-foreground" /> : <Wrench className="w-[10px] h-[10px] text-orange-500 dark:text-orange-400" />}
                          </div>
                          <div className="border border-border rounded-xl overflow-hidden bg-[#f8fafc] dark:bg-gray-900">
                              <div className="flex items-center justify-between p-2.5 px-3 bg-card cursor-pointer hover:bg-muted transition-colors border-b border-border">
                                  <div className="flex items-center gap-3">
                                      <span className="text-xs font-medium text-foreground">{step.title}</span>
                                      {step.status === 'running' && <span className="text-[10px] bg-blue-50 dark:bg-blue-950/40 text-blue-600 dark:text-blue-400 px-1.5 py-0.5 rounded border border-blue-100 dark:border-blue-900 flex items-center gap-1"><Loader2 className="w-3 h-3 animate-spin" /> 执行中</span>}
                                      {step.status === 'completed' && <span className="text-[10px] bg-green-50 dark:bg-green-950/40 text-green-600 dark:text-green-400 px-1.5 py-0.5 rounded border border-green-100 dark:border-green-900">已完成</span>}
                                  </div>
                                  <ChevronDown className="w-3 h-3 text-muted-foreground/60" />
                              </div>
                              <div className="p-3 bg-[#fcfcfd] dark:bg-gray-900">
                                  <div className="space-y-1.5 text-xs">
                                      <div className="flex items-center justify-between text-foreground font-medium">
                                          <div className="flex items-center gap-1.5"><Database className="text-blue-500 dark:text-blue-400 w-3 h-3" /> {step.detail}</div>
                                          {step.duration && <span className="text-[10px] text-muted-foreground">{step.duration}</span>}
                                      </div>
                                  </div>
                              </div>
                          </div>
                      </div>
                  ))}

                  {(msg.content || msg.isTyping) && (
                    <div className="relative animate-in fade-in">
                        <div className="absolute -left-[27.5px] w-5 h-5 rounded-full bg-blue-50 dark:bg-blue-950/40 border border-blue-100 dark:border-blue-900 flex items-center justify-center top-0 shadow-[0_0_0_2px_rgba(255,255,255,1)]">
                            <MessageSquare className="w-[10px] h-[10px] text-blue-500 dark:text-blue-400" />
                        </div>
                        <div className="text-sm text-foreground leading-relaxed pt-0.5">
                            <div className="prose prose-sm prose-blue max-w-none">
                              <Markdown>{msg.content}</Markdown>
                            </div>
                            {msg.isTyping && <span className="inline-block w-1.5 h-4 ml-1 align-middle bg-blue-500 animate-pulse"></span>}
                        </div>
                    </div>
                  )}

                  {!msg.isTyping && msg.sources && msg.sources.length > 0 && (
                    <SourceCitations sources={msg.sources} />
                  )}

                  {!msg.isTyping && msg.content && (
                    <div className="relative pt-1">
                      <SaveToKnowledgeButton msg={msg} />
                    </div>
                  )}

              </div>
          </div>
      </div>
    </>
  );
}
