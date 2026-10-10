import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { toast } from "sonner";
import { parseChatCommand, type ChatSettingsMenu } from "@/lib/chatCommands";
import { useChatSessions } from "./useChatSessions";
import { useSideWorkspace } from "./useSideWorkspace";

export type ChatCommandPanel = "help" | "context" | "status";

export function useChatCommands(input: string, setInput: (input: string) => void, sendMessage: () => void) {
  const navigate = useNavigate();
  const [panel, setPanel] = useState<ChatCommandPanel | null>(null);
  const [settingsMenu, setSettingsMenu] = useState<ChatSettingsMenu | null>(null);
  const send = () => {
    const parsed = parseChatCommand(input);
    if (!parsed || parsed.command.name === "compact") { sendMessage(); return; }
    const name = parsed.command.name;
    if (parsed.argument) { toast.error(`/${name} 不接受参数`); return; }
    setInput("");
    if (name === "help" || name === "context" || name === "status") setPanel(name);
    else if (name === "model" || name === "reasoning" || name === "permissions") setSettingsMenu(name);
    else if (name === "tasks") useSideWorkspace.getState().show("tasks");
    else if (name === "mcp") navigate("/agent?section=connections");
    else if (name === "new") requestAnimationFrame(() => {
      const id = useChatSessions.getState().createSession();
      navigate(`/chat?session=${encodeURIComponent(id)}`);
    });
  };
  return { send, panel, closePanel: () => setPanel(null), settingsMenu, setSettingsMenu };
}
