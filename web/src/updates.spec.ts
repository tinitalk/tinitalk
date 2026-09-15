import { afterEach, expect, it, vi } from 'vitest';
import { canUpdateApplication, inspectUpdates, readWorkerVersion, updateStatus, waitForWorker, type UpdateReport } from './updates';
import type { Account } from './model';

afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });

const manifest = { build: 'web-current', shell: 'web-current', push: 'push-current' };
const owner: Account = { id: 'family', server: 'https://family.example', login: 'bob', token: 'secret', name: 'Bob', deviceId: 'd', sessionId: 's', pushConfigId: 'configured' };
function worker(version: string, kind = 'push'): ServiceWorker {
  return { state: 'activated', scriptURL: 'https://web.example/push-worker.js?v=push-current',
    postMessage: (_: unknown, ports: MessagePort[]) => ports[0].postMessage({ type: 'tinitalk-worker-version', kind, version }),
  } as unknown as ServiceWorker;
}
function ready(): UpdateReport {
  return { build: manifest.build, latest: manifest, checkedAt: Date.now(), controller: { version: manifest.shell, state: 'activated' }, workers: [
    { label: 'Оболочка', kind: 'shell', expected: manifest.shell, enabled: true, active: { version: manifest.shell, state: 'activated' }, waiting: null, installing: false },
    { label: 'Bob', kind: 'push', expected: manifest.push, enabled: true, subscribed: true, active: { version: manifest.push, state: 'activated' }, waiting: null, installing: false },
  ] };
}

it('reads the running worker, not the version promised by its URL', async () => {
  expect(await readWorkerVersion(worker('old-code'), 'push')).toEqual({ version: 'old-code', state: 'activated' });
});

it('does not present a legacy unresponsive worker as up to date', async () => {
  vi.useFakeTimers();
  const pending = readWorkerVersion({ state: 'activated', postMessage: vi.fn() } as unknown as ServiceWorker, 'push');
  await vi.advanceTimersByTimeAsync(3100);
  expect(await pending).toEqual({ version: null, state: 'activated' });
});

it('reports readiness only when loaded code, controller and every enabled worker match deployment', () => {
  expect(updateStatus(ready()).kind).toBe('ready');
  const report = ready(); report.workers[1].active!.version = 'old-push';
  expect(updateStatus(report).kind).toBe('update');
  report.workers[1].active!.version = null;
  expect(updateStatus(report).kind).toBe('unknown');
});

it('distinguishes waiting updates, an old page controller, offline checks and missing subscriptions', () => {
  const pending = ready(); pending.workers[0].waiting = { version: 'next', state: 'installed' };
  expect(updateStatus(pending).kind).toBe('update');
  const oldPage = ready(); oldPage.controller!.version = 'old-shell';
  expect(updateStatus(oldPage).kind).toBe('update');
  const offline = ready(); offline.latest = undefined; offline.error = 'Нет сети';
  expect(updateStatus(offline).kind).toBe('unknown');
  const disconnected = ready(); disconnected.workers[1].subscribed = false;
  expect(updateStatus(disconnected).kind).toBe('notifications');
});

it('offers application update only when deployed code is newer than running code', () => {
  expect(canUpdateApplication(ready())).toBe(false);
  const missingSubscription = ready(); missingSubscription.workers[1].subscribed = false;
  expect(canUpdateApplication(missingSubscription)).toBe(false);
  const oldPage = ready(); oldPage.build = 'old-build';
  expect(canUpdateApplication(oldPage)).toBe(true);
  const oldWorker = ready(); oldWorker.workers[1].active!.version = 'old-push';
  expect(canUpdateApplication(oldWorker)).toBe(true);
});

it('checks only this installation and its accounts; a root registration cannot stand in for a missing push worker', async () => {
  const shell = { scope: 'https://web.example/talk/', active: worker(manifest.shell, 'shell'), waiting: null, installing: null };
  const getRegistrations = vi.fn(async () => [shell, { ...shell, scope: 'https://web.example/app/notifications/old/' }]);
  vi.stubGlobal('navigator', { serviceWorker: { getRegistrations, controller: shell.active } });
  const fetch = vi.fn(async (_url: URL, _options: RequestInit) => new Response(JSON.stringify(manifest)));
  vi.stubGlobal('fetch', fetch);
  const report = await inspectUpdates('https://web.example/talk/', [owner], manifest.build);
  expect(report.workers).toHaveLength(2);
  expect(report.workers[1].active).toBeNull();
  expect(updateStatus(report).kind).toBe('update');
  expect(fetch.mock.calls[0][0].toString()).toBe('https://web.example/talk/version.json');
  expect(fetch.mock.calls[0][1]).toMatchObject({ cache: 'no-store' });
  expect(JSON.stringify(report)).not.toContain('secret');
});

it('waits for installation to finish and fails on a redundant worker', async () => {
  const eventTarget = new EventTarget();
  const changing = Object.assign(eventTarget, { state: 'installing' }) as unknown as ServiceWorker;
  const pending = waitForWorker(changing, ['installed', 'activated']);
  Object.assign(changing, { state: 'installed' }); changing.dispatchEvent(new Event('statechange'));
  await pending;
  Object.assign(changing, { state: 'redundant' });
  await expect(waitForWorker(changing, ['activated'])).rejects.toThrow();
});
