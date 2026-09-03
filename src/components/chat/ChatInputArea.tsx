import { Paperclip, FileText, AtSign, Layers, ChevronDown, Send, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";

interface ChatInputAreaProps {
  input: string;
  setInput: (value: string) => void;
  isSending: boolean;
  onSend: () => void;
}

export function ChatInputArea({ input, setInput, isSending, onSend }: ChatInputAreaProps) {
  return (
    <div className="absolute bottom-0 left-0 right-0 p-6 bg-gradient-to-t from-[#f4f5f7] via-[#f4f5f7] to-transparent pointer-events-none">
      <div className="max-w-3xl mx-auto pointer-events-auto">
          <div className="border border-gray-200 rounded-2xl bg-white shadow-sm focus-within:border-blue-500 focus-within:ring-4 focus-within:ring-blue-500/10 transition-all flex flex-col overflow-hidden relative group">
              <textarea 
                rows={2} 
                placeholder="给“AI 助理”发送消息..." 
                className="w-full bg-transparent resize-none outline-none text-sm p-4 pb-0 text-gray-800 placeholder-gray-400"
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
                      <Button variant="ghost" size="icon" className="w-8 h-8 text-gray-400 hover:text-blue-600"><Paperclip className="w-4 h-4" /></Button>
                      <Button variant="ghost" size="icon" className="w-8 h-8 text-gray-400 hover:text-blue-600"><FileText className="w-4 h-4" /></Button>
                      <Button variant="ghost" size="icon" className="w-8 h-8 text-gray-400 hover:text-blue-600"><AtSign className="w-4 h-4" /></Button>
                  </div>
                  
                  <div className="flex gap-2 items-center">
                      <div className="flex items-center gap-2 px-2 py-1 rounded-md text-[10px] text-gray-500 font-medium hover:bg-gray-50 cursor-pointer">
                          <Layers className="w-3 h-3 text-gray-400" />
                          <div className="w-16 h-1.5 bg-gray-100 rounded-full overflow-hidden flex">
                              <div className="h-full bg-blue-500" style={{width: '25%'}}></div>
                          </div>
                          <span className="font-mono">33k/128k</span>
                      </div>
                      
                      <div className="w-px h-3 bg-gray-200"></div>

                      <Button variant="ghost" size="sm" className="h-7 text-xs px-2 text-gray-600">
                          GPT-4o <ChevronDown className="w-3 h-3 ml-1 text-gray-400" />
                      </Button>
                      
                      <Button 
                        size="icon" 
                        className="w-8 h-8 bg-blue-600 hover:bg-blue-700 text-white rounded-lg ml-1 transition-colors disabled:opacity-50"
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
