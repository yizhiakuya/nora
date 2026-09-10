import { useEffect, useState } from "react";
import { Clock, Pencil, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import { Modal } from "@/components/ui/custom/Modal";
import { Skill } from "@/types";
import { toast } from "sonner";
import { useSkills } from "@/hooks/useSkills";

interface SkillDetailModalProps {
  skill: Skill | null;
  onClose: () => void;
  onEdit: (skill: Skill) => void;
  onDelete: (skill: Skill) => void;
  onToggle: (id: number) => void;
}

/**
 * 技能详情(指令型):基础信息 + 指令正文。
 * 列表接口不带正文,打开时按需拉详情(渐进披露);加载中显示占位。
 */
export function SkillDetailModal({ skill, onClose, onEdit, onDelete, onToggle }: SkillDetailModalProps) {
  const [confirming, setConfirming] = useState(false);
  const [loadingDetail, setLoadingDetail] = useState(false);
  const loadDetail = useSkills((s) => s.loadDetail);
  // 响应式订阅 store:详情加载/启停后弹窗内容自动刷新(不能用 getState 渲染)
  const skills = useSkills((s) => s.skills);
  const skillId = skill?.id;

  useEffect(() => {
    setConfirming(false);
    if (skillId == null) return;
    // 已有正文(本地创建/已加载过)不重复请求
    const existing = skills.find((s) => s.id === skillId);
    if (existing?.instructions != null) return;
    setLoadingDetail(true);
    void loadDetail(skillId).finally(() => setLoadingDetail(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 仅在打开目标变化时拉详情
  }, [skillId, loadDetail]);

  if (!skill) return null;
  // 以 store 中的最新数据渲染(详情加载/启停后同步)
  const current = skills.find((s) => s.id === skill.id) ?? skill;

  const Icon = current.icon;

  const handleDelete = () => {
    onDelete(current);
    toast.success(`已删除技能「${current.name}」`);
    onClose();
  };

  return (
    <Modal isOpen onClose={onClose} title="技能详情" width="w-[94%] sm:w-[560px]">
      <div className="space-y-5">
        <div className="flex items-start justify-between gap-4">
          <div className="flex items-center gap-3 min-w-0">
            <div className={`w-11 h-11 rounded-xl flex items-center justify-center ${current.bg} ${current.color} shrink-0`}>
              <Icon className="w-5 h-5" />
            </div>
            <div className="min-w-0">
              <div className="flex items-center gap-2">
                <h3 className="text-base font-bold text-foreground truncate">{current.name}</h3>
                <span className={`text-[10px] px-2 py-0.5 rounded font-medium shrink-0 ${current.isOfficial ? "bg-blue-50 dark:bg-blue-950/40 text-blue-600 dark:text-blue-400" : "bg-muted text-muted-foreground"}`}>
                  {current.isOfficial ? "官方" : "自定义"}
                </span>
              </div>
              <p className="text-xs text-muted-foreground mt-1">{current.desc}</p>
            </div>
          </div>
          <div className="flex items-center gap-2 shrink-0">
            <span className="text-[10px] text-muted-foreground">{current.enabled ? "可用" : "已停用"}</span>
            <Switch checked={current.enabled} onCheckedChange={() => onToggle(current.id)} />
          </div>
        </div>

        <div className="grid grid-cols-2 gap-3 text-xs">
          <div className="bg-muted rounded-lg p-3">
            <div className="text-muted-foreground mb-0.5">分类</div>
            <div className="text-foreground font-medium">{current.category}</div>
          </div>
          <div className="bg-muted rounded-lg p-3">
            <div className="text-muted-foreground mb-0.5 flex items-center gap-1"><Clock className="w-3 h-3" /> 更新时间</div>
            <div className="text-foreground font-medium">{formatTimestamp(current.createdAt)}</div>
          </div>
        </div>

        <div>
          <div className="text-xs font-bold text-foreground mb-1.5">技能指令</div>
          {loadingDetail ? (
            <div className="bg-muted rounded-lg p-3 text-xs text-muted-foreground">加载中…</div>
          ) : current.instructions ? (
            <div className="bg-[#1e1e1e] rounded-lg p-3 border border-gray-800 max-h-64 overflow-y-auto custom-scroll">
              <pre className="text-gray-300 font-mono text-[11px] leading-relaxed whitespace-pre-wrap">{current.instructions}</pre>
            </div>
          ) : (
            <div className="bg-muted rounded-lg p-3 text-xs text-muted-foreground">暂无指令内容</div>
          )}
        </div>

        {!current.isOfficial && confirming && (
          <div className="bg-red-50 dark:bg-red-950/40 border border-red-100 dark:border-red-900 rounded-lg p-3 flex items-center justify-between gap-3 animate-in fade-in">
            <span className="text-xs text-red-600 dark:text-red-400">确认删除「{current.name}」？该操作不可恢复。</span>
            <div className="flex gap-2 shrink-0">
              <Button variant="outline" size="sm" className="h-7 text-xs bg-card" onClick={() => setConfirming(false)}>取消</Button>
              <Button size="sm" className="h-7 text-xs bg-red-600 dark:bg-red-500 hover:bg-red-700 dark:hover:bg-red-600" onClick={handleDelete}>确认删除</Button>
            </div>
          </div>
        )}
      </div>

      <div className="mt-6 pt-4 border-t border-border flex justify-end gap-2">
        {!current.isOfficial && (
          <>
            <Button variant="outline" size="sm" className="h-8 text-xs bg-card text-red-600 dark:text-red-400 border-red-200 dark:border-red-800 hover:bg-red-50 dark:hover:bg-red-950/40" onClick={() => setConfirming(true)}>
              <Trash2 className="w-3.5 h-3.5 mr-1" /> 删除
            </Button>
            <Button variant="outline" size="sm" className="h-8 text-xs bg-card" onClick={() => onEdit(current)}>
              <Pencil className="w-3.5 h-3.5 mr-1" /> 编辑
            </Button>
          </>
        )}
        <Button size="sm" className="h-8 text-xs bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600" onClick={onClose}>关闭</Button>
      </div>
    </Modal>
  );
}

/** 后端时间戳 → 可读格式(去掉 T 与微秒,如 2026-09-10 22:27)。 */
function formatTimestamp(value?: string): string {
  if (!value) return "—";
  const m = value.match(/^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})/);
  return m ? `${m[1]} ${m[2]}` : value;
}
