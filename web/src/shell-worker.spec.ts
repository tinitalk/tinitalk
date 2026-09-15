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
