'use client';

import { useEffect, useState } from "react";
import { CheckCircle2, Info } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import { useSkills } from "@/hooks/useSkills";
import { useTimedSequence } from "@/hooks/useTimedSequence";
import { usePreferences } from "@/hooks/usePreferences";
import { fetchIndexStats } from "@/lib/services/ragService";
import { toast } from "sonner";

/**
 * 知识库与 AI 设置(2026-09-19 去假数据)。
 *
 * 此前"嵌入模型/分块策略"两个下拉是假的:存了偏好但无人消费,后端实际
 * 用服务端配置(jina-embeddings-v3 / ChunkingService 固定 2000 字符)。
 * 现在改为**只读展示后端真实值**(GET /rag/index/stats),不再让用户以为
 * 改了会生效;真正可配的两项保留:
 * - 上传后自动入库(autoIndex):files 页上传完成回调真实消费;
 * - AI 能力默认开关:与「AI 能力」页共享同一 store(真实生效)。
 */
export function KnowledgeAISettings() {
  const [saved, setSaved] = useState(false);
  const { schedule, cancelAll } = useTimedSequence();
  const skills = useSkills((s) => s.skills);
  const toggleSkill = useSkills((s) => s.toggleSkill);

  const knowledgeAI = usePreferences((s) => s.knowledgeAI);
  const setKnowledgeAI = usePreferences((s) => s.setKnowledgeAI);

  // 后端真实嵌入配置(只读展示;服务端 application.yml 决定)
  const [serverStats, setServerStats] = useState<{ model: string; vectorDim: number } | null>(null);
  useEffect(() => {
    let mounted = true;
    fetchIndexStats()
      .then((s) => { if (mounted) setServerStats({ model: s.model, vectorDim: s.vectorDim }); })
      .catch(() => { /* 后端不可用时保持未知态 */ });
    return () => { mounted = false; };
  }, []);

  const handleSave = () => {
    cancelAll();
    setSaved(true);
    toast.success("知识库与 AI 设置已保存");
    schedule(() => setSaved(false), 2000);
  };

  return (
    <div className="bg-card dark:bg-card rounded-xl border border-border shadow-sm overflow-hidden">
      <div className="p-6 space-y-6">
        <div className="space-y-1.5">
          <label className="text-sm font-bold text-foreground">嵌入模型</label>
          <p className="text-xs text-muted-foreground mb-2">
            文档向量化所用模型,由服务端配置(rag-service <code className="font-mono">nora.embedding.*</code> / .env.local)。
          </p>
          <div className="w-full max-w-sm h-10 rounded-lg border border-border bg-muted/40 px-3 flex items-center text-sm text-foreground">
            {serverStats
              ? `${serverStats.model} (${serverStats.vectorDim} 维)`
              : "加载中…"}
          </div>
          <p className="text-[10px] text-muted-foreground flex items-center gap-1">
            <Info className="w-3 h-3" /> 切换模型需改服务端配置并重建索引,故不在此提供修改入口。
          </p>
        </div>

        <div className="space-y-1.5">
          <label className="text-sm font-bold text-foreground">分块策略</label>
          <p className="text-xs text-muted-foreground mb-2">
            文档切分由 rag-service 固定策略执行(约 2000 字符/块,重叠 200,按空白边界切分)。
          </p>
          <div className="w-full max-w-sm h-10 rounded-lg border border-border bg-muted/40 px-3 flex items-center text-sm text-foreground">
            2000 字符 / 块(重叠 200)
          </div>
        </div>

        <div className="w-full h-px bg-border"></div>

        <div className="flex items-center justify-between">
          <div>
            <div className="text-sm font-medium text-foreground">上传后自动加入知识库</div>
            <div className="text-xs text-muted-foreground mt-0.5">新文件完成上传后自动解析并索引入库(无文本文件自动跳过)</div>
          </div>
          <Switch
            checked={knowledgeAI.autoIndex}
            onCheckedChange={(v) => {
              setKnowledgeAI({ autoIndex: v });
              toast.success(v ? "已开启:上传文件将自动入库" : "已关闭:上传文件需手动「加入知识库」");
            }}
          />
        </div>

        <div className="w-full h-px bg-border"></div>

        <div className="space-y-3">
          <div>
            <label className="text-sm font-bold text-foreground">技能默认开关</label>
            <p className="text-xs text-muted-foreground mt-0.5">与设置页「技能」共享同一状态，控制对话中 AI 可使用的技能。</p>
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
            {skills.map((skill) => (
              <div key={skill.id} className="flex items-center justify-between px-3 py-2 rounded-lg bg-muted/40 border border-border">
                <div className="min-w-0">
                  <div className="text-xs font-medium text-foreground truncate">{skill.name}</div>
                  <div className="text-[10px] text-muted-foreground truncate">{skill.desc}</div>
                </div>
                <Switch checked={skill.enabled} onCheckedChange={() => toggleSkill(skill.id)} />
              </div>
            ))}
          </div>
        </div>
      </div>

      <div className="bg-muted/30 p-4 border-t border-border flex justify-end gap-3">
        <Button size="sm" className={`transition-colors ${saved ? "bg-green-600 dark:bg-green-500 text-white" : "bg-primary text-primary-foreground"}`} onClick={handleSave}>
          {saved ? <><CheckCircle2 className="w-4 h-4 mr-1.5" /> 已保存</> : "保存更改"}
        </Button>
      </div>
    </div>
  );
}
