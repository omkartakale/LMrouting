import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

// All `/api/**` calls hit the Spring Boot backend (default port 8080).
// Dev proxies them so the React app keeps the same fetch URLs as the
// original static frontend. The production build (`npm run build`) emits
// to ../src/main/resources/static so Spring Boot serves the SPA from the
// JAR — the user can either keep the legacy HTML files alongside or copy
// the new dist output in. We do NOT auto-replace the existing files.
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
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
  build: {
    outDir: 'dist',
    sourcemap: true,
    rollupOptions: {
      input: {
        // Main allocation SPA (replaces /index.html)
        main: path.resolve(__dirname, 'index.html'),
        // Affinity setup page (replaces /affinity-setup.html)
        'affinity-setup': path.resolve(__dirname, 'affinity-setup.html'),
      },
    },
  },
});
