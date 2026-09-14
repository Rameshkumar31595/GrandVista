import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The dev server runs on 5173 - the origin allowed by the API Gateway CORS policy.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
  },
});
