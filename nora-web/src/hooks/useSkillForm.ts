import { useState, useEffect, useCallback } from "react";
import { Skill } from "@/types";

export interface SkillFormValues {
  name: string;
  category: string;
  schema: string;
  authType: string;
}

export interface SkillFormErrors {
  name?: string;
  schema?: string;
}

const CATEGORIES = ["自定义", "数据", "计算", "环境", "通知"];
const AUTH_OPTIONS = ["无鉴权", "Bearer Token", "API Key"];

export { CATEGORIES, AUTH_OPTIONS };

export function useSkillForm(initial: Skill | null, isOpen: boolean) {
  const [name, setName] = useState("");
  const [category, setCategory] = useState("自定义");
  const [schema, setSchema] = useState("");
  const [authType, setAuthType] = useState("无鉴权");
  const [token, setToken] = useState("");
  const [errors, setErrors] = useState<SkillFormErrors>({});

  // 打开时回填（编辑）或复位（创建）
  useEffect(() => {
    if (isOpen) {
      setName(initial?.name ?? "");
      setCategory(initial?.category ?? "自定义");
      setSchema(initial?.schema ?? "");
      setAuthType(initial?.authType ?? "无鉴权");
      setToken("");
      setErrors({});
    }
  }, [isOpen, initial]);

  const validate = useCallback((): SkillFormErrors | null => {
    const next: SkillFormErrors = {};
    if (!name.trim()) next.name = "工具名称不能为空";
    if (!schema.trim()) {
      next.schema = "OpenAPI Schema 不能为空";
    } else {
      try {
        JSON.parse(schema);
      } catch {
        next.schema = "Schema 不是合法的 JSON，请检查格式";
      }
    }
    if (next.name || next.schema) return next;
    return null;
  }, [name, schema]);

  const submit = useCallback(
    (onValid: (values: SkillFormValues) => void) => {
      const errs = validate();
      if (errs) {
        setErrors(errs);
        return;
      }
      onValid({ name: name.trim(), category, schema, authType });
    },
    [validate, name, category, schema, authType]
  );

  return {
    name, setName,
    category, setCategory,
    schema, setSchema,
    authType, setAuthType,
    token, setToken,
    errors,
    submit,
  };
}
