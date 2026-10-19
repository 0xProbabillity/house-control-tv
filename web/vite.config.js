import { defineConfig } from 'vite';
import { resolve } from 'node:path';
export default defineConfig({build:{rolldownOptions:{input:{main:resolve(import.meta.dirname,'index.html'),widget:resolve(import.meta.dirname,'widget.html')}}}});
