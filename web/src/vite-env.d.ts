/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Base address of the SchemaGuard API; without it see API_URL in src/api/client.ts. */
  readonly VITE_API_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
