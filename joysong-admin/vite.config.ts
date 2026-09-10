import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
  base: process.env.VITE_BASE_PATH || '/',
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    clearMocks: true,
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        configure(proxy) {
          proxy.on('proxyReq', (proxyReq, req) => {
            const origin = req.headers.origin
            // The browser calls this local dev server; ECS is reached through SSH.
            // Only omit Origin for same-origin requests from the trusted local UI.
            if (
              (origin === 'http://127.0.0.1:3000' || origin === 'http://localhost:3000') &&
              origin === `http://${req.headers.host}`
            ) {
              proxyReq.removeHeader('origin')
            }
          })
        },
      },
      '/images': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
