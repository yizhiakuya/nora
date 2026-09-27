'use client';

import { useNavigate } from "react-router-dom";
import { Plus, MessageSquare } from "lucide-react";
import { Modal } from "@/components/ui/custom/Modal";
import { buildAssistantHandoffUrl, type HandoffRef } from "@/lib/handoff";

/**
 * 「交给助手」去向选择(B6,2026-09-27,评审报告)。
 *
 * 此前跨页交接不带会话参数——对话页用当前活跃会话,「从另一份资料发起新需求」
 * 可能接着旧讨论工作,界面没有表达这个选择。现在交接入口明确让用户选:
 *
 * - **新建处理**(默认):新开一个会话,适合独立的新任务;
 * - **加入当前对话**:接到正在进行的讨论里,适合延续上下文。
 */
export function HandoffChoiceDialog({ isOpen, onClose, prompt, refs }: {
  isOpen: boolean;
  onClose: () => void;
  prompt: string;
  refs: HandoffRef[];
}) {
  const navigate = useNavigate();

  const go = (newSession: boolean) => {
    onClose();
    navigate(buildAssistantHandoffUrl(prompt, refs, newSession));
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title="交给助手"
      width="w-[92%] sm:w-[420px]"
    >
      <div className="space-y-2">
        <p className="text-[11px] text-muted-foreground pb-1">
          已附带 {refs.length} 项引用;选择处理去向(指令与引用进入输入区后可再编辑,不会直接发送)。
        </p>
        <button
          type="button"
          onClick={() => go(true)}
          className="w-full text-left px-3 py-2.5 rounded-lg border border-border hover:bg-muted transition-colors cursor-pointer"
        >
          <span className="flex items-center gap-2 text-sm font-medium text-foreground">
            <Plus className="w-3.5 h-3.5 text-blue-500" /> 新建处理
          </span>
          <span className="block text-[11px] text-muted-foreground mt-0.5">新开一个会话,适合独立的新任务</span>
        </button>
        <button
          type="button"
          onClick={() => go(false)}
          className="w-full text-left px-3 py-2.5 rounded-lg border border-border hover:bg-muted transition-colors cursor-pointer"
        >
          <span className="flex items-center gap-2 text-sm font-medium text-foreground">
            <MessageSquare className="w-3.5 h-3.5 text-muted-foreground" /> 加入当前对话
          </span>
          <span className="block text-[11px] text-muted-foreground mt-0.5">接到正在进行的讨论里,延续上下文</span>
        </button>
      </div>
    </Modal>
  );
}
