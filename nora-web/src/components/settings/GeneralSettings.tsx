import { Bot, ArrowRight, Globe, Moon, Sun } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { useTheme } from "@/components/ThemeProvider";
import { useNavigate } from "react-router-dom";
import { usePreferences, type LinkOpenMode } from "@/hooks/usePreferences";

export function GeneralSettings() {
  const { theme, setTheme, resolvedTheme } = useTheme();
  const navigate = useNavigate();
  const linkOpenMode = usePreferences(state => state.linkOpenMode);
  const setLinkOpenMode = usePreferences(state => state.setLinkOpenMode);

  return (
    <div className="surface overflow-hidden">
      <div className="p-5 sm:p-6 space-y-6">
        <button
          type="button"
          onClick={() => navigate("/agent")}
          className="w-full flex items-center justify-between gap-3 px-4 py-3 rounded-lg bg-muted/40 border border-border cursor-pointer hover:border-primary/40 transition-colors text-left"
        >
          <div className="min-w-0 flex items-center gap-3">
            <Bot className="w-4 h-4 text-primary shrink-0" />
            <div>
              <div className="text-sm font-medium text-foreground">Agent 配置</div>
              <div className="text-xs text-muted-foreground mt-0.5">管理模型、权限、指令与记忆</div>
            </div>
          </div>
          <ArrowRight className="w-4 h-4 text-muted-foreground shrink-0" />
        </button>

        <div className="space-y-4">
          <div>
            <h2 className="section-heading">外观</h2>
            <p className="text-xs text-muted-foreground mt-1">设置会立即生效。</p>
          </div>
          <div className="flex flex-wrap items-center justify-between gap-4">
            <div className="flex items-center gap-3">
              <div className="w-9 h-9 rounded-lg bg-muted flex items-center justify-center">
                {resolvedTheme === "dark" ? <Moon className="w-4 h-4" /> : <Sun className="w-4 h-4" />}
              </div>
              <label htmlFor="appearance-theme" className="text-sm font-medium">主题</label>
            </div>
            <Select value={theme} onValueChange={(value) => setTheme(value as typeof theme)}>
              <SelectTrigger id="appearance-theme" className="w-36"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="system">跟随系统</SelectItem>
                <SelectItem value="light">浅色</SelectItem>
                <SelectItem value="dark">深色</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </div>

        <div className="border-t border-border pt-6 space-y-4">
          <div>
            <h2 className="section-heading">链接</h2>
            <p className="text-xs text-muted-foreground mt-1">即时生效，并保存在当前浏览器。</p>
          </div>
          <div className="flex flex-wrap items-center justify-between gap-4">
            <div className="flex items-center gap-3 min-w-0">
              <div className="w-9 h-9 rounded-lg bg-muted flex items-center justify-center shrink-0"><Globe className="w-4 h-4" /></div>
              <div>
                <label htmlFor="links-open-mode" className="text-sm font-medium">链接打开方式</label>
                <p className="text-xs text-muted-foreground mt-1">文档、对话和采集结果中的网页链接</p>
              </div>
            </div>
            <Select value={linkOpenMode} onValueChange={value => setLinkOpenMode(value as LinkOpenMode)}>
              <SelectTrigger id="links-open-mode" className="w-36"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="internal">内置浏览器</SelectItem>
                <SelectItem value="external">外部浏览器</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <p className="text-xs leading-relaxed text-muted-foreground">内置浏览器在右侧工作区新建标签页；外部浏览器在访问 Nora 的浏览器里新建标签页。</p>
        </div>

        <div className="border-t border-border pt-6 space-y-2">
          <h2 className="section-heading">数据存储</h2>
          <p className="text-sm leading-relaxed text-muted-foreground">文件、索引和会话由 Nora 部署的服务器管理。调用模型时，相关内容会发送到你配置的模型服务商。</p>
        </div>
      </div>
      <div className="bg-muted/30 px-5 sm:px-6 py-4 border-t border-border flex justify-end">
        <Button variant="outline" size="sm" onClick={() => setTheme("system")}>跟随系统外观</Button>
      </div>
    </div>
  );
}
