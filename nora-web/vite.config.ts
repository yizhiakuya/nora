import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
      "next/dynamic": fileURLToPath(new URL("./src/lib/next-shims/dynamic.tsx", import.meta.url)),
      "next-themes": fileURLToPath(new URL("./src/components/ThemeProvider.tsx", import.meta.url)),
    },
  },
  server: {
    port: 3001,
    strictPort: true,
    proxy: {
      "/api": {
        target: "http://localhost:18080",
        changeOrigin: true,
      },
    },
  },
});
