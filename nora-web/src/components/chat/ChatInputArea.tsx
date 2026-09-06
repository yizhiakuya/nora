import { useRef, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { Paperclip, FileText, AtSign, Layers, ChevronDown, Send, Loader2, Zap, Check, Settings, Brain, ShieldAlert } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useSkills } from "@/hooks/useSkills";
import { useModelProviders, REASONING_LEVELS } from "@/hooks/useModelProviders";
import { PERMISSION_MODE_META, type PermissionMode } from "@/lib/api/chatApi";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { toast } from "sonner";

interface ChatInputAreaProps {
  input: string;
  setInput: (value: string) => void;
  isSending: boolean;
  onSend: () => void;
  contextTokens?: number;
  contextLimit?: number;
  /** 对话框选的思考等级;undefined = 跟随设置页该模型默认 */
  reasoningLevel?: string;
  onReasoningLevelChange?: (level: string | undefined) => void;
  /** 权限模式:请求批准 / 帮我批准 / 完全访问权限 */
  permissionMode?: PermissionMode;
  onPermissionModeChange?: (mode: PermissionMode) => void;
}

export function ChatInputArea({ input, setInput, isSending, onSend, contextTokens = 0, contextLimit = 128000, reasoningLevel, onReasoningLevelChange, permissionMode = "assist", onPermissionModeChange }: ChatInputAreaProps) {
  const navigate = useNavigate();
  const skills = useSkills((s) => s.skills);
  const toggleSkill = useSkills((s) => s.toggleSkill);
  const providers = useModelProviders((s) => s.providers);
  const defaultModel = useModelProviders((s) => s.defaultModel);
  const setDefaultModel = useModelProviders((s) => s.setDefaultModel);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const contextPercent = Math.min(100, Math.round((contextTokens / contextLimit) * 100));

  // 当前模型在设置页配置的思考等级白名单;空 = 全部等级
  const activeProvider = providers.find((p) => p.enabled && p.models.includes(defaultModel));
  const configuredLevels = activeProvider?.modelSettings?.[defaultModel]?.reasoningLevels ?? [];
  const availableLevels = configuredLevels.length
    ? REASONING_LEVELS.filter((l) => configuredLevels.includes(l))
    : REASONING_LEVELS;

  const handleToggle = (id: number, name: string, enabled: boolean) => {
    toggleSkill(id);
    toast.success(`能力「${name}」已${enabled ? "停用" : "启用"}，AI ${enabled ? "不再" : "现在"}可以使用它`);
  };

  // 监听 input 变化，动态调整 textarea 高度
  useEffect(() => {
    const textarea = textareaRef.current;
    if (!textarea) return;

    // 强制先重置为 auto 以便在删除文字时能缩回
    textarea.style.height = 'auto';
    // 设置为滚动高度，最大限制交给 CSS maxHeight
    textarea.style.height = `${textarea.scrollHeight}px`;
  }, [input]);

  return (
    <div className="absolute bottom-0 left-0 right-0 p-6 bg-gradient-to-t from-[#f4f5f7] via-[#f4f5f7] to-transparent dark:from-gray-950 dark:via-gray-950 pointer-events-none">
      <div className="max-w-3xl mx-auto pointer-events-auto">
          {/* AI 能力状态条：启用=高亮，停用=置灰；点击切换 */}
          <div className="flex items-center gap-1.5 mb-2 flex-wrap">
            <span className="inline-flex items-center gap-1 text-[10px] text-muted-foreground mr-1">
              <Zap className="w-3 h-3" /> AI 能力
            </span>
            {skills.map((skill) => (
              <button
                key={skill.id}
                type="button"
                onClick={() => handleToggle(skill.id, skill.name, skill.enabled)}
                title={skill.desc}
                className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-medium border cursor-pointer transition-colors ${skill.enabled ? "bg-blue-50 dark:bg-blue-950/40 text-blue-600 dark:text-blue-400 border-blue-200 dark:border-blue-800" : "bg-muted text-muted-foreground border-border line-through opacity-60"}`}
              >
                <span className={`w-1.5 h-1.5 rounded-full ${skill.enabled ? "bg-blue-500" : "bg-gray-300 dark:bg-gray-700"}`} />
                {skill.name}
              </button>
            ))}
          </div>

          <div className="border border-border rounded-2xl bg-card shadow-sm focus-within:border-blue-500 focus-within:ring-4 focus-within:ring-blue-500/10 transition-all flex flex-col overflow-hidden relative group">
              <textarea
                ref={textareaRef}
                rows={1}
                style={{ minHeight: '52px', maxHeight: '240px' }}
                placeholder="给“AI 助理”发送消息..."
                className="w-full bg-transparent resize-none outline-none text-sm px-4 pt-4 pb-2 text-foreground placeholder:text-muted-foreground overflow-y-auto custom-scroll"
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={(e) => {
                    if (e.nativeEvent.isComposing) return;
                    if (e.key === 'Enter' && !e.shiftKey) {
                        e.preventDefault();
                        if (input.trim() && !isSending) {
                            onSend();
                        }
                    }
                }}
              ></textarea>

              <div className="flex justify-between items-end p-2.5 pt-1">
                  <div className="flex shrink-0 gap-0.5">
                      <Button variant="ghost" size="icon" className="w-8 h-8 text-muted-foreground hover:text-blue-600 dark:hover:text-blue-400"><Paperclip className="w-4 h-4" /></Button>
                      <Button variant="ghost" size="icon" className="w-8 h-8 text-muted-foreground hover:text-blue-600 dark:hover:text-blue-400"><FileText className="w-4 h-4" /></Button>
                      <Button variant="ghost" size="icon" className="w-8 h-8 text-muted-foreground hover:text-blue-600 dark:hover:text-blue-400"><AtSign className="w-4 h-4" /></Button>
                  </div>

                  <div className="flex min-w-0 flex-1 justify-end gap-2 items-center">
                      <div className="flex items-center gap-2 px-2 py-1 rounded-md text-[10px] text-muted-foreground font-medium hover:bg-muted cursor-pointer">
                          <Layers className="w-3 h-3 text-muted-foreground" />
                          <div className="w-16 h-1.5 bg-muted rounded-full overflow-hidden flex">
                              <div className={`h-full ${contextPercent >= 90 ? "bg-red-500" : contextPercent >= 75 ? "bg-amber-500" : "bg-blue-500"}`} style={{width: `${contextPercent}%`}}></div>
                          </div>
                          <span className="font-mono">{Math.round(contextTokens / 1000)}k/{Math.round(contextLimit / 1000)}k</span>
                      </div>

                      <div className="w-px h-3 bg-gray-200 dark:bg-gray-800"></div>

                      {/* 思考等级:仅列出设置页为当前模型启用的等级 */}
                      {onReasoningLevelChange && availableLevels.length > 0 && (
                        <DropdownMenu>
                          <DropdownMenuTrigger asChild>
                            <Button variant="ghost" size="sm" className="h-7 text-xs px-2 text-muted-foreground hover:text-foreground" title="思考等级">
                              <Brain className="w-3 h-3 mr-1" />
                              {reasoningLevel ?? "自动"} <ChevronDown className="w-3 h-3 ml-1 text-muted-foreground" />
                            </Button>
                          </DropdownMenuTrigger>
                          <DropdownMenuContent align="end" className="w-40 rounded-xl">
                            <DropdownMenuLabel className="text-xs text-muted-foreground">思考等级</DropdownMenuLabel>
                            <DropdownMenuItem
                              className="flex items-center justify-between cursor-pointer text-xs"
                              onClick={() => {
                                onReasoningLevelChange(undefined);
                                toast.success("思考等级已设为自动（跟随模型默认）");
                              }}
                            >
                              <span>自动</span>
                              {reasoningLevel === undefined && <Check className="w-3.5 h-3.5 text-blue-600 dark:text-blue-400" />}
                            </DropdownMenuItem>
                            {availableLevels.map((level) => (
                              <DropdownMenuItem
                                key={level}
                                className="flex items-center justify-between cursor-pointer text-xs font-mono"
                                onClick={() => {
                                  onReasoningLevelChange(level);
                                  toast.success(`思考等级已设为 ${level}`);
                                }}
                              >
                                <span>{level}</span>
                                {reasoningLevel === level && <Check className="w-3.5 h-3.5 text-blue-600 dark:text-blue-400" />}
                              </DropdownMenuItem>
                            ))}
                          </DropdownMenuContent>
                        </DropdownMenu>
                      )}

                      <div className="w-px h-3 bg-gray-200 dark:bg-gray-800"></div>

                      {/* 三档权限模式 */}
                      {onPermissionModeChange && (
                        <DropdownMenu>
                          <DropdownMenuTrigger asChild>
                            <Button
                              variant="ghost"
                              size="sm"
                              className={`h-7 text-xs px-2 ${permissionMode === "full" ? "text-orange-600 dark:text-orange-400" : "text-muted-foreground hover:text-foreground"}`}
                              title="权限模式"
                            >
                              <ShieldAlert className="w-3 h-3 mr-1" />
                              {PERMISSION_MODE_META[permissionMode].label}
                              <ChevronDown className="w-3 h-3 ml-1 text-muted-foreground" />
                            </Button>
                          </DropdownMenuTrigger>
                          <DropdownMenuContent align="end" className="w-72 rounded-xl p-1.5">
                            <DropdownMenuLabel className="px-2 py-1.5 text-xs text-muted-foreground">权限模式</DropdownMenuLabel>
                            {(Object.keys(PERMISSION_MODE_META) as PermissionMode[]).map((mode) => {
                              const meta = PERMISSION_MODE_META[mode];
                              const active = permissionMode === mode;
                              return (
                                <DropdownMenuItem
                                  key={mode}
                                  className={`items-start gap-2.5 rounded-lg py-2.5 cursor-pointer ${mode === "full" ? "text-orange-600 dark:text-orange-400 focus:text-orange-600 dark:focus:text-orange-400" : ""}`}
                                  onClick={() => {
                                    onPermissionModeChange(mode);
                                    toast.success(`权限模式已切换为「${meta.label}」`);
                                  }}
                                >
                                  <ShieldAlert className={`w-4 h-4 mt-0.5 shrink-0 ${mode === "full" ? "text-orange-500" : "text-muted-foreground"}`} />
                                  <span className="flex-1 min-w-0">
                                    <span className="block text-xs font-medium">{meta.label}</span>
                                    <span className="block text-[10px] leading-relaxed text-muted-foreground mt-0.5 whitespace-normal">{meta.description}</span>
                                  </span>
                                  {active && <Check className="w-3.5 h-3.5 mt-0.5 shrink-0" />}
                                </DropdownMenuItem>
                              );
                            })}
                          </DropdownMenuContent>
                        </DropdownMenu>
                      )}

                      <div className="w-px h-3 bg-gray-200 dark:bg-gray-800"></div>

                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <Button variant="ghost" size="sm" className="h-7 text-xs px-2 text-muted-foreground hover:text-foreground">
                            <span className="max-w-[160px] truncate">{defaultModel}</span> <ChevronDown className="w-3 h-3 ml-1 text-muted-foreground" />
                          </Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end" className="w-52 rounded-xl">
                          <DropdownMenuLabel className="text-xs text-muted-foreground">切换当前模型</DropdownMenuLabel>
                          {providers
                            .filter((p) => p.enabled)
                            .flatMap((p) =>
                              p.models.map((m) => (
                                <DropdownMenuItem
                                  key={`${p.id}-${m}`}
                                  className="flex items-center justify-between cursor-pointer text-xs"
                                  onClick={() => {
                                    setDefaultModel(m);
                                    toast.success(`已切换默认模型为 ${m}`);
                                  }}
                                >
                                  <span>{m}</span>
                                  {m === defaultModel && <Check className="w-3.5 h-3.5 text-blue-600 dark:text-blue-400" />}
                                </DropdownMenuItem>
                              ))
                            )}
                          <DropdownMenuSeparator />
                          <DropdownMenuItem
                            className="text-xs text-blue-600 dark:text-blue-400 cursor-pointer flex items-center gap-1.5"
                            onClick={() => navigate("/settings?tab=模型管理")}
                          >
                            <Settings className="w-3 h-3" /> 管理模型服务商...
                          </DropdownMenuItem>
                        </DropdownMenuContent>
                      </DropdownMenu>

                      <Button
                        size="icon"
                        className="shrink-0 w-8 h-8 bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600 text-white rounded-lg ml-1 transition-colors disabled:opacity-50"
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
