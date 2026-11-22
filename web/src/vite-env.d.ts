/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Another API to call directly instead of /api on the page's own origin (see src/api/client.ts). */
  readonly VITE_API_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
