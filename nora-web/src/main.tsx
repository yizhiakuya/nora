import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import App from "./App";
import "./app/globals.css";
import { Toaster } from "sonner";
import { ThemeProvider } from "@/components/ThemeProvider";

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
