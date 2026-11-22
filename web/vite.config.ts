/// <reference types="vitest/config" />
import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// /api goes to the API, as Vercel's rewrite does in production (D-090). Point it elsewhere with
// SCHEMAGUARD_API, for example SCHEMAGUARD_API=https://schemaguard-api-c00y.onrender.com npm run dev.
const api = { '/api': { target: process.env.SCHEMAGUARD_API ?? 'http://localhost:8080', changeOrigin: true } };

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: { proxy: api },
  preview: { proxy: api },
  build: {
    // Monaco is loaded on demand as its own chunk; the app shell stays small.
    chunkSizeWarningLimit: 4000,
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    restoreMocks: true,
    unstubGlobals: true,
  },
});
