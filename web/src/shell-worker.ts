/// <reference lib="webworker" />
import { reportWorkerVersion } from './workerVersion';
const shell = self as unknown as ServiceWorkerGlobalScope;
reportWorkerVersion(shell, 'shell');
const base = new URL('./', shell.location.href);
// Old shells are retained: an open call must keep using its original JS build.
// Activation is explicitly requested by the page only while no call is active.
declare const __BUILD_ID__: string;
const cacheName = `tinitalk-shell-${base.pathname}-${__BUILD_ID__}`;
shell.addEventListener('install', event => event.waitUntil((async () => {
  const cache = await caches.open(cacheName);
  const response = await fetch(new URL('index.html', base), { cache: 'reload' });
  if (!response.ok) throw new Error('shell unavailable');
  const html = await response.clone().text();
  await cache.put(new URL('index.html', base), response);
  const assets = [...html.matchAll(/(?:src|href)="([^"#]+)"/g)].map(match => new URL(match[1], base)).filter(url => url.origin === base.origin && url.pathname.includes('/assets/'));
  await cache.addAll(assets.map(url => url.href));
})()));
shell.addEventListener('activate', event => event.waitUntil(shell.clients.claim()));
shell.addEventListener('message', event => { if (event.data?.type === 'activate-update') void shell.skipWaiting(); });
shell.addEventListener('fetch', event => {
  const url = new URL(event.request.url);
  if (event.request.method !== 'GET' || url.origin !== base.origin || !url.pathname.startsWith(base.pathname) || url.pathname.startsWith('/api/') || url.pathname.startsWith('/healthz')) return;
  if (event.request.mode === 'navigate') {
    event.respondWith((async () => {
      let response: Response | undefined;
      try {
        response = await fetch(event.request);
        if (response.status < 500) return response;
      } catch { /* Offline: open the saved application. */ }
      const cached = await (await caches.open(cacheName)).match(new URL('index.html', base));
      return cached ?? response ?? Response.error();
    })());
  } else if (url.pathname.startsWith(new URL('assets/', base).pathname)) {
    event.respondWith((async () => {
      const cache = await caches.open(cacheName);
      const existing = await cache.match(event.request);
      if (existing) return existing;
      const response = await fetch(event.request);
      if (response.ok) await cache.put(event.request, response.clone());
      return response;
    })());
  }
});
