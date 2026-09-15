import { defineConfig } from 'vite';
import { build } from 'esbuild';
import { cp, mkdir, readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';

async function versionedWorker(entry: string, define: Record<string, string> = {}) {
  // Hash the bundled code with a fixed version placeholder to avoid hashing a
  // self-referential string. The executing worker reports this fingerprint.
  const marker = '__TINITALK_WORKER_FINGERPRINT__';
  const template = (await build({ entryPoints: [entry], bundle: true, write: false,
    format: 'iife', target: 'es2022', minify: true,
    define: { ...define, __WORKER_VERSION__: JSON.stringify(marker) },
  })).outputFiles[0].text;
  const version = createHash('sha256').update(template).digest('hex').slice(0, 16);
  return { version, contents: template.replaceAll(marker, version) };
}

// Based on tinimsg's separate worker build. All paths are relative, so one
// artifact works at / on both nginx and the household server.
export default defineConfig(async ({ command }) => {
  // Embed the worker's content version in the application before Vite builds it.
  // This also makes a push-only change update the app bundle and shell cache ID.
  const builtAt = new Date().toISOString();
  const pushWorker = command === 'build' ? await versionedWorker('src/push-worker.ts') : undefined;
  return {
    base: './',
    define: pushWorker ? {
      'import.meta.env.VITE_PUSH_WORKER_VERSION': JSON.stringify(pushWorker.version),
      'import.meta.env.VITE_WEB_BUILD_ID': JSON.stringify(builtAt),
    } : {},
    plugins: [{
      name: 'tinitalk-workers',
      apply: 'build',
      async closeBundle() {
        await writeFile('dist/push-worker.js', pushWorker!.contents);
        const id = createHash('sha256').update(await readFile('dist/index.html')).digest('hex').slice(0, 16);
        const shellWorker = await versionedWorker('src/shell-worker.ts', { __BUILD_ID__: JSON.stringify(id) });
        await writeFile('dist/shell-worker.js', shellWorker.contents);
        await writeFile('dist/version.json', JSON.stringify({ build: builtAt, shell: shellWorker.version, push: pushWorker!.version }) + '\n');
        // Keep the package-local build for go:embed; publish alongside the APKs
        // and server binaries. Retain older hashed assets for open clients.
        await mkdir('../dist/tinitalk-web', { recursive: true });
        await cp('dist', '../dist/tinitalk-web', { recursive: true });
      },
    }],
  };
});
