import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { viteStaticCopy } from 'vite-plugin-static-copy';

// Build output goes straight into the Spring Boot static resources so the whole
// system ships as a single jar. During development, /api is proxied to the backend.
//
// Monaco Editor assets are copied to /monaco/vs so the editor loads from the same
// origin (works fully offline / on intranet). See src/main.tsx loader.config.
export default defineConfig({
  plugins: [
    react(),
    viteStaticCopy({
      targets: [{ src: 'node_modules/monaco-editor/min/vs', dest: 'monaco' }],
    }),
  ],
  build: {
    outDir: '../main-service/src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
