import { useState, useEffect, useCallback } from "react";
import { Skill } from "@/types";

export interface SkillFormValues {
  name: string;
  description: string;
  instructions: string;
  category: string;
}

export interface SkillFormErrors {
  name?: string;
  instructions?: string;
}

const CATEGORIES = ["自定义", "数据", "计算", "环境", "通知"];
export { CATEGORIES };

export function useSkillForm(initial: Skill | null, isOpen: boolean) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [instructions, setInstructions] = useState("");
  const [category, setCategory] = useState("自定义");
  const [errors, setErrors] = useState<SkillFormErrors>({});

  // 打开时回填（编辑）或复位（创建）
  useEffect(() => {
    if (isOpen) {
      setName(initial?.name ?? "");
      setDescription(initial?.desc ?? "");
      setInstructions(initial?.instructions ?? "");
      setCategory(initial?.category ?? "自定义");
      setErrors({});
    }
  }, [isOpen, initial]);

  const validate = useCallback((): SkillFormErrors | null => {
    const next: SkillFormErrors = {};
    if (!name.trim()) next.name = "技能名称不能为空";
    if (!instructions.trim()) next.instructions = "技能指令不能为空";
    if (next.name || next.instructions) return next;
    return null;
  }, [name, instructions]);

  const submit = useCallback(
    (onValid: (values: SkillFormValues) => void) => {
      const errs = validate();
      if (errs) {
        setErrors(errs);
        return;
      }
      onValid({
        name: name.trim(),
        description: description.trim(),
        instructions: instructions.trim(),
        category,
      });
    },
    [validate, name, description, instructions, category]
  );

  return {
    name, setName,
    description, setDescription,
    instructions, setInstructions,
    category, setCategory,
    errors,
    submit,
  };
}
