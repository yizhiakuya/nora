import { create } from "zustand";
import { Task } from "@/types";

interface TaskState {
  tasks: Task[];
  setTasks: (tasks: Task[]) => void;
}

/**
 * 全局任务唯一数据源：
 * - 任务中心页面从 MockAPI 拉取后写入此 store
 * - Header 任务角标从同一 store 读取，保证两处数据永远一致
 */
export const useGlobalTasks = create<TaskState>((set) => ({
  tasks: [],
  setTasks: (tasks) => set({ tasks }),
}));
