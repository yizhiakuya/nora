import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Modal } from "@/components/ui/custom/Modal";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { useSkillForm, CATEGORIES, SkillFormValues } from "@/hooks/useSkillForm";
import { Skill } from "@/types";

export type { SkillFormValues };

interface SkillFormModalProps {
  isOpen: boolean;
  onClose: () => void;
  /** 编辑模式传入待编辑技能；创建模式传 null */
  initial?: Skill | null;
  onSubmit: (values: SkillFormValues) => void;
}

/**
 * 技能编辑弹窗(指令型):名称 + 描述(何时用) + 分类 + 指令正文(Markdown)。
 * 启用后目录注入 agent 上下文,正文由 agent 按需读取遵循。
 */
export function SkillFormModal({ isOpen, onClose, initial, onSubmit }: SkillFormModalProps) {
  const isEdit = !!initial;
  const form = useSkillForm(initial ?? null, isOpen);
  const { name, setName, description, setDescription, instructions, setInstructions,
    category, setCategory, errors, submit } = form;

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title={isEdit ? `编辑技能 · ${initial?.name}` : "新建技能"}
      width="w-[94%] sm:w-[620px]"
      footer={
        <>
          <Button variant="outline" size="sm" onClick={onClose}>取消</Button>
          <Button size="sm" className="bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600" onClick={() => submit(onSubmit)}>
            {isEdit ? "保存修改" : "保存并创建"}
          </Button>
        </>
      }
    >
      <div className="space-y-5">
        <div className="space-y-1.5">
          <label className="text-xs font-bold text-foreground">技能名称</label>
          <Input
            placeholder="例如：周报生成"
            className={`h-9 text-sm ${errors.name ? "border-red-400 focus-visible:ring-red-400" : ""}`}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
          {errors.name && <p className="text-[11px] text-red-500 dark:text-red-400">{errors.name}</p>}
        </div>

        <div className="grid grid-cols-[1fr_180px] gap-3">
          <div className="space-y-1.5">
            <label className="text-xs font-bold text-foreground">一句话描述</label>
            <Input
              placeholder="何时该用这个技能（会展示给 AI）"
              className="h-9 text-sm"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
          </div>
          <div className="space-y-1.5">
            <label className="text-xs font-bold text-foreground">分类</label>
            <Select value={category} onValueChange={setCategory}>
              <SelectTrigger className="w-full">
                <SelectValue placeholder="选择分类" />
              </SelectTrigger>
              <SelectContent>
                {CATEGORIES.map((c) => (
                  <SelectItem key={c} value={c}>{c}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </div>

        <div className="space-y-1.5">
          <label className="text-xs font-bold text-foreground">技能指令（Markdown）</label>
          <div className={`bg-[#1e1e1e] rounded-lg p-3 border shadow-inner ${errors.instructions ? "border-red-500" : "border-gray-800"}`}>
            <textarea
              rows={9}
              className="w-full bg-transparent text-gray-300 font-mono text-xs leading-relaxed resize-none focus:outline-none custom-scroll"
              placeholder={"# 任务步骤\n1. 先做什么\n2. 再做什么\n\n（AI 会在任务相关时读取并遵循这段指令）"}
              value={instructions}
              onChange={(e) => setInstructions(e.target.value)}
            ></textarea>
          </div>
          {errors.instructions && <p className="text-[11px] text-red-500 dark:text-red-400">{errors.instructions}</p>}
          <p className="text-[10px] text-muted-foreground">提示：对话中直接让 AI「把刚才的流程存成技能」，它也能自动创建。</p>
        </div>
      </div>
    </Modal>
  );
}
