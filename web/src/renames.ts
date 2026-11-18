import type { RenameDto } from './api/client.ts';

/** Renames are plain data, so their JSON identifies them. */
export function renameKey(rename: RenameDto): string {
  return JSON.stringify(rename);
}
