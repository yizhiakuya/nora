import { Sparkles, Brain, Wrench, Database, MessageSquare, ChevronDown, Loader2 } from "lucide-react";
import { ChatMessage } from "@/lib/api/chatApi";
import { Markdown } from "@/components/shared/Markdown";

export function ChatMessageItem({ msg }: { msg: ChatMessage }) {
  if (msg.role === 'user') {
    return (
      <>
        <div className="flex gap-4 animate-in fade-in slide-in-from-bottom-2">
            <div className="flex-1"></div>
            <div className="bg-[#f4f5f7] dark:bg-gray-950 p-4 rounded-2xl rounded-tr-sm border border-gray-100 dark:border-gray-800 text-sm leading-relaxed max-w-[80%] whitespace-pre-wrap">
                {msg.content}
            </div>
            <div className="w-8 h-8 rounded-full flex-shrink-0 bg-blue-500 text-white text-[10px] font-bold flex items-center justify-center shadow-sm">NC</div>
        </div>
        <div className="text-right text-[10px] text-gray-400 dark:text-gray-500 pr-12 mt-1">{msg.timestamp}</div>
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
              <div className="text-sm font-medium flex items-center gap-2 mb-4">AI 助理 <span className="text-[10px] text-gray-400 dark:text-gray-500 font-normal">{msg.timestamp}</span></div>
              
              <div className="relative pl-6 space-y-5 before:absolute before:inset-y-2 before:left-2.5 before:w-px before:bg-gray-200 dark:before:bg-gray-700">
                  {msg.steps?.map((step, idx) => (
                      <div key={step.id || idx} className="relative animate-in fade-in slide-in-from-top-2">
                          <div className="absolute -left-[27.5px] w-5 h-5 rounded-full bg-[#f8fafc] dark:bg-gray-900 border border-gray-200 dark:border-gray-800 flex items-center justify-center top-0 shadow-[0_0_0_2px_rgba(255,255,255,1)]">
                              {step.type === 'think' ? <Brain className="w-[10px] h-[10px] text-gray-500 dark:text-gray-400" /> : <Wrench className="w-[10px] h-[10px] text-orange-500 dark:text-orange-400" />}
                          </div>
                          <div className="border border-gray-100 dark:border-gray-800 rounded-xl overflow-hidden bg-[#f8fafc] dark:bg-gray-900">
                              <div className="flex items-center justify-between p-2.5 px-3 bg-white dark:bg-gray-900 cursor-pointer hover:bg-gray-50 dark:hover:bg-gray-800 transition-colors border-b border-gray-100 dark:border-gray-800">
                                  <div className="flex items-center gap-3">
                                      <span className="text-xs font-medium text-gray-700 dark:text-gray-200">{step.title}</span>
                                      {step.status === 'running' && <span className="text-[10px] bg-blue-50 dark:bg-blue-950/40 text-blue-600 dark:text-blue-400 px-1.5 py-0.5 rounded border border-blue-100 dark:border-blue-900 flex items-center gap-1"><Loader2 className="w-3 h-3 animate-spin" /> 执行中</span>}
                                      {step.status === 'completed' && <span className="text-[10px] bg-green-50 dark:bg-green-950/40 text-green-600 dark:text-green-400 px-1.5 py-0.5 rounded border border-green-100 dark:border-green-900">已完成</span>}
                                  </div>
                                  <ChevronDown className="w-3 h-3 text-gray-300 dark:text-gray-600" />
                              </div>
                              <div className="p-3 bg-[#fcfcfd] dark:bg-gray-900">
                                  <div className="space-y-1.5 text-xs">
                                      <div className="flex items-center justify-between text-gray-700 dark:text-gray-200 font-medium">
                                          <div className="flex items-center gap-1.5"><Database className="text-blue-500 dark:text-blue-400 w-3 h-3" /> {step.detail}</div>
                                          {step.duration && <span className="text-[10px] text-gray-400 dark:text-gray-500">{step.duration}</span>}
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
                        <div className="text-sm text-gray-700 dark:text-gray-200 leading-relaxed pt-0.5">
                            <div className="prose prose-sm prose-blue max-w-none">
                              <Markdown>{msg.content}</Markdown>
                            </div>
                            {msg.isTyping && <span className="inline-block w-1.5 h-4 ml-1 align-middle bg-blue-500 animate-pulse"></span>}
                        </div>
                    </div>
                  )}

              </div>
          </div>
      </div>
    </>
  );
}
