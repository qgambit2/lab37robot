import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Dev: `npm run dev` serves the UI on :5173 and proxies API calls to the
// Spring app on :8081. Build: `npm run build` emits straight into the
// backend's static resources, so the app serves the UI itself at :8081.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/v1': 'http://localhost:8081',
      '/mocks': 'http://localhost:8081',
    },
  },
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
})
