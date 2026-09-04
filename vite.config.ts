import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
      "next/navigation": fileURLToPath(new URL("./src/lib/next-shims/navigation.ts", import.meta.url)),
      "next/link": fileURLToPath(new URL("./src/lib/next-shims/Link.tsx", import.meta.url)),
      "next/image": fileURLToPath(new URL("./src/lib/next-shims/Image.tsx", import.meta.url)),
      "next/dynamic": fileURLToPath(new URL("./src/lib/next-shims/dynamic.tsx", import.meta.url)),
      "next-themes": fileURLToPath(new URL("./src/components/ThemeProvider.tsx", import.meta.url)),
    },
  },
  server: {
    port: 3001,
    strictPort: true,
  },
});

