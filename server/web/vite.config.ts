import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// ビルド結果は Spring Boot の static フォルダーに出力し、jar に同梱して配信する。
// 開発中（npm run dev）は /api を Spring Boot（localhost:8080）へ転送する。
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: "../src/main/resources/static",
    emptyOutDir: true,
  },
  server: {
    proxy: { "/api": "http://localhost:8080" },
  },
});
