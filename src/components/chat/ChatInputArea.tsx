import { Paperclip, FileText, AtSign, Layers, ChevronDown, Send, Loader2, Zap } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useSkills } from "@/hooks/useSkills";
import { useModelProviders } from "@/hooks/useModelProviders";
import { toast } from "sonner";

interface ChatInputAreaProps {
  input: string;
  setInput: (value: string) => void;
  isSending: boolean;
  onSend: () => void;
}

export function ChatInputArea({ input, setInput, isSending, onSend }: ChatInputAreaProps) {
  const skills = useSkills((s) => s.skills);
  const toggleSkill = useSkills((s) => s.toggleSkill);
  const defaultModel = useModelProviders((s) => s.defaultModel);

  const handleToggle = (id: number, name: string, enabled: boolean) => {
    toggleSkill(id);
    toast.success(`能力「${name}」已${enabled ? "停用" : "启用"}，AI ${enabled ? "不再" : "现在"}可以使用它`);
  };

  return (
    <div className="absolute bottom-0 left-0 right-0 p-6 bg-gradient-to-t from-[#f4f5f7] via-[#f4f5f7] to-transparent dark:from-gray-950 dark:via-gray-950 pointer-events-none">
      <div className="max-w-3xl mx-auto pointer-events-auto">
          {/* AI 能力状态条：启用=高亮，停用=置灰；点击切换 */}
          <div className="flex items-center gap-1.5 mb-2 flex-wrap">
            <span className="inline-flex items-center gap-1 text-[10px] text-gray-400 dark:text-gray-500 mr-1">
              <Zap className="w-3 h-3" /> AI 能力
            </span>
            {skills.map((skill) => (
              <button
                key={skill.id}
                type="button"
                onClick={() => handleToggle(skill.id, skill.name, skill.enabled)}
                title={skill.desc}
                className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-medium border cursor-pointer transition-colors ${skill.enabled ? "bg-blue-50 dark:bg-blue-950/40 text-blue-600 dark:text-blue-400 border-blue-200 dark:border-blue-800" : "bg-gray-100 dark:bg-gray-900 text-gray-400 dark:text-gray-600 border-gray-200 dark:border-gray-800 line-through opacity-60"}`}
              >
                <span className={`w-1.5 h-1.5 rounded-full ${skill.enabled ? "bg-blue-500" : "bg-gray-300 dark:bg-gray-700"}`} />
                {skill.name}
              </button>
            ))}
          </div>

          <div className="border border-gray-200 dark:border-gray-800 rounded-2xl bg-white dark:bg-gray-900 shadow-sm focus-within:border-blue-500 focus-within:ring-4 focus-within:ring-blue-500/10 transition-all flex flex-col overflow-hidden relative group">
              <textarea 
                rows={2} 
                placeholder="给“AI 助理”发送消息..." 
                className="w-full bg-transparent resize-none outline-none text-sm p-4 pb-0 text-gray-800 dark:text-gray-100 placeholder-gray-400 dark:placeholder-gray-500"
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={(e) => {
                    if (e.nativeEvent.isComposing) return;
                    if (e.key === 'Enter' && !e.shiftKey) {
                        e.preventDefault();
                        onSend();
                    }
                }}
              ></textarea>
              
              <div className="flex justify-between items-end p-2.5 pt-1">
                  <div className="flex gap-0.5">
                      <Button variant="ghost" size="icon" className="w-8 h-8 text-gray-400 dark:text-gray-500 hover:text-blue-600 dark:hover:text-blue-400"><Paperclip className="w-4 h-4" /></Button>
                      <Button variant="ghost" size="icon" className="w-8 h-8 text-gray-400 dark:text-gray-500 hover:text-blue-600 dark:hover:text-blue-400"><FileText className="w-4 h-4" /></Button>
                      <Button variant="ghost" size="icon" className="w-8 h-8 text-gray-400 dark:text-gray-500 hover:text-blue-600 dark:hover:text-blue-400"><AtSign className="w-4 h-4" /></Button>
                  </div>
                  
                  <div className="flex gap-2 items-center">
                      <div className="flex items-center gap-2 px-2 py-1 rounded-md text-[10px] text-gray-500 dark:text-gray-400 font-medium hover:bg-gray-50 dark:hover:bg-gray-800 cursor-pointer">
                          <Layers className="w-3 h-3 text-gray-400 dark:text-gray-500" />
                          <div className="w-16 h-1.5 bg-gray-100 dark:bg-gray-800 rounded-full overflow-hidden flex">
                              <div className="h-full bg-blue-500" style={{width: '25%'}}></div>
                          </div>
                          <span className="font-mono">33k/128k</span>
                      </div>
                      
                      <div className="w-px h-3 bg-gray-200 dark:bg-gray-800"></div>

                      <Button variant="ghost" size="sm" className="h-7 text-xs px-2 text-gray-600 dark:text-gray-300">
                          {defaultModel} <ChevronDown className="w-3 h-3 ml-1 text-gray-400 dark:text-gray-500" />
                      </Button>
                      
                      <Button 
                        size="icon" 
                        className="w-8 h-8 bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600 text-white rounded-lg ml-1 transition-colors disabled:opacity-50"
                        onClick={onSend}
                        disabled={!input.trim() || isSending}
                      >
                          {isSending ? <Loader2 className="w-4 h-4 animate-spin" /> : <Send className="w-3.5 h-3.5 ml-0.5" />}
                      </Button>
                  </div>
              </div>
          </div>
      </div>
  </div>
  );
}
