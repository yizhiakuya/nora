import { useLocation, useNavigate } from "react-router-dom";
import { toast } from "sonner";
import { useChatSessions } from "./useChatSessions";

/** 两个删除入口共享撤销反馈和路由切换，计时与持久化由 store 管理。 */
export function useDeleteChatSession() {
  const location = useLocation();
  const navigate = useNavigate();
  return (id: string) => {
    const store = useChatSessions.getState();
    const session = store.sessions.find(item => item.id === id);
    if (!session) return;
    const targetId = new URLSearchParams(location.search).get("session") || store.activeId;
    const viewing = location.pathname === "/chat" && targetId === id;
    if (!store.deleteSession(id)) return;
    if (viewing) {
      const next = useChatSessions.getState().activeId;
      navigate(next ? `/chat?session=${encodeURIComponent(next)}` : "/", { replace: true });
    }
    toast.success(`已删除「${session.title.slice(0, 16)}${session.title.length > 16 ? "…" : ""}」`, {
      description: "5 秒内可撤销",
      duration: 5000,
      action: {
        label: "撤销",
        onClick: () => {
          if (!useChatSessions.getState().undoDeleteSession(id)) return;
          if (viewing) {
            useChatSessions.getState().setActive(id);
            navigate(`/chat?session=${encodeURIComponent(id)}`, { replace: true });
          }
        },
      },
    });
  };
}
