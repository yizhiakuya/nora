import { requestJson } from "@/lib/api/client";
/** 后端 agent_skill 行(camelCase);列表接口 instructions 为 null(渐进披露)。 */
export interface BackendSkill {
  id: number;
  name: string;
  description: string;
  instructions?: string | null;
  category: string;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

/**
 * 技能后端接入层:
 * - listSkills   → GET    /api/skills           → Skill[](不含正文)
 * - getSkill     → GET    /api/skills/{id}      → Skill(含正文)
 * - createSkill  → POST   /api/skills
 * - updateSkill  → PUT    /api/skills/{id}
 * - deleteSkill  → DELETE /api/skills/{id}
 */
export const skillsApi = {
  async listSkills(): Promise<BackendSkill[]> {
    return requestJson<BackendSkill[]>("/skills");
  },
  async getSkill(id: number): Promise<BackendSkill> {
    return requestJson<BackendSkill>(`/skills/${id}`);
  },
  async createSkill(input: {
    name: string; description: string; instructions: string; category: string;
  }): Promise<BackendSkill> {
    return requestJson<BackendSkill>("/skills", { method: "POST", body: JSON.stringify(input) });
  },
  async updateSkill(id: number, patch: {
    name?: string; description?: string; instructions?: string; category?: string; enabled?: boolean;
  }): Promise<BackendSkill> {
    return requestJson<BackendSkill>(`/skills/${id}`, { method: "PUT", body: JSON.stringify(patch) });
  },
  async deleteSkill(id: number): Promise<void> {
    await requestJson<null>(`/skills/${id}`, { method: "DELETE" });
  },
};
