import { useState } from "react";
import { Clock, Pencil, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import { Modal } from "@/components/ui/custom/Modal";
import { Skill } from "@/types";
import { toast } from "sonner";

interface SkillDetailModalProps {
  skill: Skill | null;
  onClose: () => void;
  onEdit: (skill: Skill) => void;
  onDelete: (skill: Skill) => void;
  onToggle: (id: number) => void;
}

export function SkillDetailModal({ skill, onClose, onEdit, onDelete, onToggle }: SkillDetailModalProps) {
  const [confirming, setConfirming] = useState(false);
  if (!skill) return null;

  const Icon = skill.icon;
  const usedInAutomations: Record<string, string> = {
    "SQL 查询": "每日数据备份",
    "代码执行": "CSV 上传入库",
    "服务日志": "服务异常告警",
  };
  const usedBy = usedInAutomations[skill.name];
  let schemaPreview = "";
  if (skill.schema) {
    try {
      schemaPreview = JSON.stringify(JSON.parse(skill.schema), null, 2);
    } catch {
      schemaPreview = skill.schema;
    }
  }

  const handleDelete = () => {
    onDelete(skill);
    toast.success(`已删除技能「${skill.name}」`);
    onClose();
  };

  return (
    <Modal isOpen onClose={onClose} title="技能详情" width="w-[94%] sm:w-[560px]">
      <div className="space-y-5">
        <div className="flex items-start justify-between gap-4">
          <div className="flex items-center gap-3 min-w-0">
            <div className={`w-11 h-11 rounded-xl flex items-center justify-center ${skill.bg} ${skill.color} shrink-0`}>
              <Icon className="w-5 h-5" />
            </div>
            <div className="min-w-0">
              <div className="flex items-center gap-2">
                <h3 className="text-base font-bold text-gray-800 dark:text-gray-100 truncate">{skill.name}</h3>
                <span className={`text-[10px] px-2 py-0.5 rounded font-medium shrink-0 ${skill.isOfficial ? "bg-blue-50 dark:bg-blue-950/40 text-blue-600 dark:text-blue-400" : "bg-gray-100 dark:bg-gray-800 text-gray-600 dark:text-gray-300"}`}>
                  {skill.isOfficial ? "官方" : "自定义"}
                </span>
              </div>
              <p className="text-xs text-gray-500 dark:text-gray-400 mt-1">{skill.desc}</p>
            </div>
          </div>
          <div className="flex items-center gap-2 shrink-0">
            <span className="text-[10px] text-gray-400 dark:text-gray-500">{skill.enabled ? "可用" : "已停用"}</span>
            <Switch checked={skill.enabled} onCheckedChange={() => onToggle(skill.id)} />
          </div>
        </div>

        <div className="grid grid-cols-2 gap-3 text-xs">
          <div className="bg-gray-50 dark:bg-gray-900 rounded-lg p-3">
            <div className="text-gray-400 dark:text-gray-500 mb-0.5">分类</div>
            <div className="text-gray-700 dark:text-gray-200 font-medium">{skill.category}</div>
          </div>
          <div className="bg-gray-50 dark:bg-gray-900 rounded-lg p-3">
            <div className="text-gray-400 dark:text-gray-500 mb-0.5">鉴权方式</div>
            <div className="text-gray-700 dark:text-gray-200 font-medium">{skill.authType ?? (skill.isOfficial ? "平台托管" : "无鉴权")}</div>
          </div>
          <div className="bg-gray-50 dark:bg-gray-900 rounded-lg p-3">
            <div className="text-gray-400 dark:text-gray-500 mb-0.5 flex items-center gap-1"><Clock className="w-3 h-3" /> 创建时间</div>
            <div className="text-gray-700 dark:text-gray-200 font-medium">{skill.createdAt ?? "官方预置"}</div>
          </div>
          <div className="bg-gray-50 dark:bg-gray-900 rounded-lg p-3">
            <div className="text-gray-400 dark:text-gray-500 mb-0.5">被自动任务使用</div>
            <div className="text-gray-700 dark:text-gray-200 font-medium">{usedBy ?? "暂无"}</div>
          </div>
        </div>

        <div>
          <div className="text-xs font-bold text-gray-700 dark:text-gray-200 mb-1.5">OpenAPI Schema</div>
          {schemaPreview ? (
            <div className="bg-[#1e1e1e] rounded-lg p-3 border border-gray-800 max-h-48 overflow-y-auto custom-scroll">
              <pre className="text-gray-300 font-mono text-[11px] leading-relaxed whitespace-pre-wrap">{schemaPreview}</pre>
            </div>
          ) : (
              <div className="bg-gray-50 dark:bg-gray-900 rounded-lg p-3 text-xs text-gray-400 dark:text-gray-500">内置能力，无需配置</div>
          )}
        </div>

        {!skill.isOfficial && confirming && (
          <div className="bg-red-50 dark:bg-red-950/40 border border-red-100 dark:border-red-900 rounded-lg p-3 flex items-center justify-between gap-3 animate-in fade-in">
            <span className="text-xs text-red-600 dark:text-red-400">确认删除「{skill.name}」？该操作不可恢复。</span>
            <div className="flex gap-2 shrink-0">
              <Button variant="outline" size="sm" className="h-7 text-xs bg-white dark:bg-gray-900" onClick={() => setConfirming(false)}>取消</Button>
              <Button size="sm" className="h-7 text-xs bg-red-600 dark:bg-red-500 hover:bg-red-700 dark:hover:bg-red-600" onClick={handleDelete}>确认删除</Button>
            </div>
          </div>
        )}
      </div>

      <div className="mt-6 pt-4 border-t border-gray-100 dark:border-gray-800 flex justify-end gap-2">
        {!skill.isOfficial && (
          <>
            <Button variant="outline" size="sm" className="h-8 text-xs bg-white dark:bg-gray-900 text-red-600 dark:text-red-400 border-red-200 dark:border-red-800 hover:bg-red-50 dark:hover:bg-red-950/40" onClick={() => setConfirming(true)}>
              <Trash2 className="w-3.5 h-3.5 mr-1" /> 删除
            </Button>
            <Button variant="outline" size="sm" className="h-8 text-xs bg-white dark:bg-gray-900" onClick={() => onEdit(skill)}>
              <Pencil className="w-3.5 h-3.5 mr-1" /> 编辑
            </Button>
          </>
        )}
        <Button size="sm" className="h-8 text-xs bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600" onClick={onClose}>关闭</Button>
      </div>
    </Modal>
  );
}
