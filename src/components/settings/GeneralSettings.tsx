import { useState, useEffect } from "react";
import { CheckCircle2, Moon, Sun } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { useTheme } from "next-themes";
import { toast } from "sonner";
import { useTimedSequence } from "@/hooks/useTimedSequence";

export function GeneralSettings() {
  const [saved, setSaved] = useState(false);
  const [model, setModel] = useState("GPT-4o (推荐)");
  const { schedule, cancelAll } = useTimedSequence();
  const { setTheme, resolvedTheme } = useTheme();
  const [mounted, setMounted] = useState(false);

  // Avoid hydration mismatch
  useEffect(() => setMounted(true), []);

  const handleSave = () => {
    cancelAll();
    setSaved(true);
    toast.success("设置已保存");
    schedule(() => setSaved(false), 2000);
  };

  return (
    <div className="bg-card dark:bg-card rounded-xl border border-border shadow-sm overflow-hidden">
      <div className="p-6 space-y-6">
        <div className="space-y-1.5">
          <label className="text-sm font-bold text-foreground">默认大语言模型 (LLM)</label>
          <p className="text-xs text-muted-foreground mb-2">选择在对话和智能体中默认使用的基础大模型。</p>
          <Select value={model} onValueChange={setModel}>
            <SelectTrigger className="w-full max-w-sm h-10 rounded-lg">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="GPT-4o (推荐)">GPT-4o (推荐)</SelectItem>
              <SelectItem value="Claude 3.5 Sonnet">Claude 3.5 Sonnet</SelectItem>
              <SelectItem value="Gemini 1.5 Pro">Gemini 1.5 Pro</SelectItem>
              <SelectItem value="自定义私有模型部署">自定义私有模型部署</SelectItem>
            </SelectContent>
          </Select>
        </div>

        <div className="w-full h-px bg-border"></div>

        <div className="space-y-4">
          <div>
            <label className="text-sm font-bold text-foreground">工作区偏好</label>
            <p className="text-xs text-muted-foreground">自定义您的工作台视觉与交互体验。</p>
          </div>

          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="w-8 h-8 rounded-lg bg-muted flex items-center justify-center">
                {mounted && resolvedTheme === "dark" ? <Moon className="w-4 h-4 text-foreground" /> : <Sun className="w-4 h-4 text-foreground" />}
              </div>
              <div>
                <div className="text-sm font-medium text-foreground">深色模式 (Dark Mode)</div>
                <div className="text-xs text-muted-foreground mt-0.5">全局应用极夜风格视觉</div>
              </div>
            </div>
            <Switch
              checked={mounted && resolvedTheme === "dark"}
              onCheckedChange={(checked) => setTheme(checked ? "dark" : "light")}
            />
          </div>

          <div className="flex items-center justify-between">
            <div>
              <div className="text-sm font-medium text-foreground">代码块自动换行</div>
              <div className="text-xs text-muted-foreground mt-0.5">在对话中输出的代码段是否默认折叠过长行</div>
            </div>
            <Switch defaultChecked />
          </div>

          <div className="flex items-center justify-between">
            <div>
              <div className="text-sm font-medium text-foreground">紧凑模式</div>
              <div className="text-xs text-muted-foreground mt-0.5">减小组件间距，在屏幕上显示更多内容</div>
            </div>
            <Switch />
          </div>
        </div>

        <div className="w-full h-px bg-border"></div>

        <div className="space-y-4">
          <div>
            <label className="text-sm font-bold text-foreground">默认文件隐私</label>
            <p className="text-xs text-muted-foreground">新上传到文件中心和知识库的文件默认权限。</p>
          </div>
          <div className="flex gap-4">
            <label className="flex items-center gap-2 text-sm text-muted-foreground cursor-pointer hover:text-foreground">
              <input type="radio" name="privacy" className="text-primary focus:ring-primary w-4 h-4 accent-primary" defaultChecked />
              仅自己可见 (Private)
            </label>
            <label className="flex items-center gap-2 text-sm text-muted-foreground cursor-pointer hover:text-foreground">
              <input type="radio" name="privacy" className="text-primary focus:ring-primary w-4 h-4 accent-primary" />
              工作区可见 (Workspace)
            </label>
          </div>
        </div>
      </div>

      <div className="bg-muted/30 p-4 border-t border-border flex justify-end gap-3">
        <Button variant="outline" size="sm" className="bg-background">还原默认</Button>
        <Button
          size="sm"
          className={`transition-colors ${saved ? "bg-green-600 hover:bg-green-700 text-white" : "bg-primary hover:bg-primary/90 text-primary-foreground"}`}
          onClick={handleSave}
        >
          {saved ? <><CheckCircle2 className="w-4 h-4 mr-1.5" /> 已保存</> : "保存更改"}
        </Button>
      </div>
    </div>
  );
}
