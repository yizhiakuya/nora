/**
 * 消息级「已保存」状态持久化(2026-09-21 修复重复保存 bug)。
 *
 * 问题:此前「保存为文件」/「保存到知识库」的已保存状态只存在组件 useState——
 * 刷新/切会话后组件重挂载,状态丢失,按钮又可点,点一次生成一个新文件
 * (文件名带时间戳,每次都是新路径)。用户实测:切屏回来重新可点,重复生成。
 *
 * 方案:按 (sessionId + 内容哈希) 把保存结果写进 localStorage——
 * 消息内容不变(历史重载后 content 一致)则键一致,已保存状态跨刷新/切屏保持。
 * 同时「保存为文件」的文件名改用内容哈希后缀(替代时间戳):即使状态意外
 * 丢失再点,也只是覆盖同一文件,不再产生副本(双保险)。
 *
 * B1(2026-09-27):localStorage 只是**按钮状态的本地镜像**——成果归属的
 * 权威记录在服务端(saved_artifact 表,保存成功后经 /api/saved-artifacts
 * 登记),换浏览器也能从资料页找到「这个文件来自哪次对话」。
 */
const STORAGE_KEY = "nora-saved-artifacts";

export interface SavedRecord {
  /** 「保存为文件」的工作区路径(已保存时存在) */
  filePath?: string;
  /** 「保存到知识库」是否已保存 */
  knowledgeSaved?: boolean;
  /** 「保存到知识库」的知识文档 id(服务端返回;登记与跳转用) */
  knowledgeDocId?: number;
}

function loadAll(): Record<string, SavedRecord> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw) as Record<string, SavedRecord>;
    return parsed && typeof parsed === "object" ? parsed : {};
  } catch {
    return {};
  }
}

/** 读某消息的保存记录(无则空对象)。 */
export function getSavedRecord(key: string): SavedRecord {
  return loadAll()[key] ?? {};
}

/** 合并写入某消息的保存记录(部分字段更新)。 */
export function markSaved(key: string, patch: Partial<SavedRecord>): void {
  try {
    const all = loadAll();
    all[key] = { ...all[key], ...patch };
    localStorage.setItem(STORAGE_KEY, JSON.stringify(all));
  } catch {
    /* 隐私模式/配额满:状态不持久化,功能不受影响 */
  }
}

/** djb2 哈希(非加密,仅做稳定键/文件名后缀)。 */
function djb2(s: string): string {
  let h = 5381;
  for (let i = 0; i < s.length; i++) {
    h = ((h << 5) + h + s.charCodeAt(i)) | 0;
  }
  return (h >>> 0).toString(36);
}

/**
 * 消息保存状态键:会话 + 内容哈希。
 * 历史重载后 content 与流式期间一致 → 键一致 → 已保存状态可恢复。
 */
export function contentKey(sessionId: string | undefined, content: string): string {
  return `s${djb2((sessionId ?? "") + "|" + content)}`;
}

/**
 * 内容哈希后缀(文件名用,6-8 位):
 * 同一回答重复保存 → 同一文件名 → 覆盖而非新增副本。
 */
export function contentHashSuffix(content: string): string {
  const h = djb2(content);
  return h.padStart(6, "0").slice(0, 8);
}
