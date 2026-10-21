import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import App from "./App";
import "./app/globals.css";
import { Toaster } from "sonner";
import { ThemeProvider } from "@/components/ThemeProvider";

// 业务事实已由服务端持久化，旧快照不再参与恢复。
try {
  for (const key of ["nora-files", "db-connections", "knowledge-docs", "automations", "notifications"]) localStorage.removeItem(key);
} catch {
  // 存储不可用不影响内存列表与服务端同步。
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <ThemeProvider attribute="class" defaultTheme="system" enableSystem disableTransitionOnChange>
      <BrowserRouter>
        <App />
      </BrowserRouter>
      <Toaster position="top-center" richColors closeButton />
    </ThemeProvider>
  </StrictMode>
);
