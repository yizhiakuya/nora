import { Code, Key } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Modal } from "@/components/ui/custom/Modal";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { useSkillForm, CATEGORIES, AUTH_OPTIONS, SkillFormValues } from "@/hooks/useSkillForm";
import { Skill } from "@/types";

export type { SkillFormValues };

interface SkillFormModalProps {
  isOpen: boolean;
  onClose: () => void;
  /** 编辑模式传入待编辑技能；创建模式传 null */
  initial?: Skill | null;
  onSubmit: (values: SkillFormValues) => void;
}

export function SkillFormModal({ isOpen, onClose, initial, onSubmit }: SkillFormModalProps) {
  const isEdit = !!initial;
  const form = useSkillForm(initial ?? null, isOpen);
  const { name, setName, category, setCategory, schema, setSchema, authType, setAuthType, token, setToken, errors, submit } = form;

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title={isEdit ? `编辑技能 · ${initial?.name}` : "创建自定义技能 (OpenAPI)"}
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
          <label className="text-xs font-bold text-gray-700 dark:text-gray-200">技能名称</label>
          <Input
            placeholder="例如：查询外部实时汇率"
            className={`h-9 text-sm ${errors.name ? "border-red-400 focus-visible:ring-red-400" : ""}`}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
          {errors.name && <p className="text-[11px] text-red-500 dark:text-red-400">{errors.name}</p>}
        </div>

        <div className="space-y-1.5">
          <label className="text-xs font-bold text-gray-700 dark:text-gray-200">技能分类</label>
          <Select value={category} onValueChange={setCategory}>
            <SelectTrigger className="w-[180px]">
              <SelectValue placeholder="选择分类" />
            </SelectTrigger>
            <SelectContent>
              {CATEGORIES.map((c) => (
                <SelectItem key={c} value={c}>{c}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <div className="space-y-1.5">
          <div className="flex justify-between items-end">
            <label className="text-xs font-bold text-gray-700 dark:text-gray-200">OpenAPI Schema</label>
            <span className="text-[10px] text-blue-600 dark:text-blue-400 cursor-pointer hover:underline flex items-center gap-1">
              <Code className="w-3 h-3" /> AI 辅助生成
            </span>
          </div>
          <div className={`bg-[#1e1e1e] rounded-lg p-3 border shadow-inner ${errors.schema ? "border-red-500" : "border-gray-800"}`}>
            <textarea
              rows={6}
              className="w-full bg-transparent text-gray-300 font-mono text-xs leading-relaxed resize-none focus:outline-none custom-scroll"
              placeholder={'{\n  "openapi": "3.0.0",\n  "info": { "title": "API Name" },\n  "paths": {}\n}'}
              value={schema}
              onChange={(e) => setSchema(e.target.value)}
            ></textarea>
          </div>
          {errors.schema && <p className="text-[11px] text-red-500 dark:text-red-400">{errors.schema}</p>}
        </div>

        <div className="space-y-1.5">
          <label className="text-xs font-bold text-gray-700 dark:text-gray-200 flex items-center gap-1">
            <Key className="w-3 h-3" /> 鉴权设置 (可选)
          </label>
          <div className="flex gap-2">
            <Select value={authType} onValueChange={setAuthType}>
              <SelectTrigger className="w-[150px]">
                <SelectValue placeholder="鉴权方式" />
              </SelectTrigger>
              <SelectContent>
                {AUTH_OPTIONS.map((a) => (
                  <SelectItem key={a} value={a}>{a}</SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Input placeholder="输入 Token..." className="h-9 text-sm flex-1" value={token} onChange={(e) => setToken(e.target.value)} />
          </div>
        </div>
      </div>
    </Modal>
  );
}
