import { afterEach, beforeEach, expect, it, vi } from 'vitest';

let listeners: Record<string, (event: any) => void>;
let cached: Response | undefined;
beforeEach(async () => {
  vi.resetModules();
  listeners = {};
  cached = new Response('saved application');
  vi.stubGlobal('__BUILD_ID__', 'test');
  vi.stubGlobal('self', { location: { href: 'https://web.example/shell-worker.js' }, addEventListener: (type: string, fn: (event: any) => void) => { listeners[type] = fn; } });
  vi.stubGlobal('caches', { open: async () => ({ match: async () => cached }) });
  await import('./shell-worker');
});
afterEach(() => vi.unstubAllGlobals());

function navigate(): Promise<Response> {
  let result!: Promise<Response>;
  listeners.fetch({ request: { url: 'https://web.example/', method: 'GET', mode: 'navigate' }, respondWith: (response: Promise<Response>) => { result = response; } });
  return result;
}

it('precaches modulepreloaded translations alongside the application for offline use', async () => {
  const html = `<script type="module" src="./assets/index-app.js"></script>
    <link rel="modulepreload" crossorigin href="./assets/translations-catalogs.js">
    <link rel="stylesheet" href="./assets/index-style.css">
    <link rel="manifest" href="./manifest.webmanifest">`;
  const put = vi.fn().mockResolvedValue(undefined);
  const addAll = vi.fn().mockResolvedValue(undefined);
  vi.stubGlobal('fetch', async () => new Response(html));
  vi.stubGlobal('caches', { open: async () => ({ put, addAll }) });

  let installed!: Promise<void>;
  listeners.install({ waitUntil: (task: Promise<void>) => { installed = task; } });
  await installed;

  expect(put).toHaveBeenCalledWith(new URL('https://web.example/index.html'), expect.any(Response));
  expect(addAll).toHaveBeenCalledWith([
    'https://web.example/assets/index-app.js',
    'https://web.example/assets/translations-catalogs.js',
    'https://web.example/assets/index-style.css',
  ]);
});

it.each([502, 503, 'offline'])('opens the cached application when hosting returns %s', async status => {
  vi.stubGlobal('fetch', async () => {
    if (typeof status === 'string') throw new TypeError('offline');
    return new Response('hosting error', { status });
  });
  const response = await navigate();
  expect(response.status).toBe(200);
  expect(await response.text()).toBe('saved application');
});

it.each([200, 404])('retains the network response with status %s', async status => {
  vi.stubGlobal('fetch', async () => new Response('network', { status }));
  const response = await navigate();
  expect(response.status).toBe(status);
  expect(await response.text()).toBe('network');
});

it('retains a hosting error if no saved shell is available', async () => {
  cached = undefined;
  vi.stubGlobal('fetch', async () => new Response('unavailable', { status: 503 }));
  expect((await navigate()).status).toBe(503);
});
