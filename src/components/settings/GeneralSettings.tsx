import { useState, useEffect } from "react";
import { CheckCircle2, Moon, Sun } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import { useTheme } from "next-themes";
import { toast } from "sonner";
import { useTimedSequence } from "@/hooks/useTimedSequence";
import { useRouter } from "next/navigation";
import { Cpu, ArrowRight } from "lucide-react";

export function GeneralSettings() {
  const [saved, setSaved] = useState(false);
  const { schedule, cancelAll } = useTimedSequence();
  const { setTheme, resolvedTheme } = useTheme();
  const [mounted, setMounted] = useState(false);
  const router = useRouter();

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
        <button
          type="button"
          onClick={() => router.push("/settings?tab=" + encodeURIComponent("模型管理"))}
          className="w-full flex items-center justify-between px-4 py-3 rounded-lg bg-muted/40 border border-border cursor-pointer hover:border-primary/40 transition-colors text-left"
        >
          <div className="flex items-center gap-3">
            <Cpu className="w-4 h-4 text-primary" />
            <div>
              <div className="text-sm font-medium text-foreground">默认大语言模型与服务商接入</div>
              <div className="text-xs text-muted-foreground mt-0.5">在设置 → 模型管理 中配置</div>
            </div>
          </div>
          <ArrowRight className="w-4 h-4 text-muted-foreground" />
        </button>

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
            <label className="text-sm font-bold text-foreground">本地数据</label>
            <p className="text-xs text-muted-foreground">所有文件与索引数据仅保存在本机浏览器与本地存储，不上传云端。</p>
          </div>
          <div className="flex items-center justify-between">
            <div>
              <div className="text-sm font-medium text-foreground">启动时恢复上次浏览的页面</div>
              <div className="text-xs text-muted-foreground mt-0.5">关闭后每次打开都从首页开始</div>
            </div>
            <Switch defaultChecked />
          </div>
        </div>
      </div>

      <div className="bg-muted/30 p-4 border-t border-border flex justify-end gap-3">
        <Button variant="outline" size="sm" className="bg-background">还原默认</Button>
        <Button
          size="sm"
          className={`transition-colors ${saved ? "bg-green-600 dark:bg-green-500 hover:bg-green-700 dark:hover:bg-green-600 text-white" : "bg-primary hover:bg-primary/90 text-primary-foreground"}`}
          onClick={handleSave}
        >
          {saved ? <><CheckCircle2 className="w-4 h-4 mr-1.5" /> 已保存</> : "保存更改"}
        </Button>
      </div>
    </div>
  );
}
