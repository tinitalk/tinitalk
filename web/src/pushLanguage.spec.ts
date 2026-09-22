import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { api } from './api';
import { enablePush, syncPushLanguage } from './push';
import { accountScope, type Account } from './model';

vi.mock('./api', () => ({ api: vi.fn(), APIError: class extends Error {} }));
vi.mock('./storage', () => ({ readPush: vi.fn(), saveAccount: vi.fn() }));
const base = 'https://web.example/talk/';
let owner: Account;
let subscribe: ReturnType<typeof vi.fn>, unsubscribe: ReturnType<typeof vi.fn>;
beforeEach(() => {
  vi.clearAllMocks();
  owner = { id: 'family', server: 'https://family.example', login: 'alice', token: 'test', name: '', deviceId: 'd', sessionId: 's', pushConfigId: 'c' };
  subscribe = vi.fn(); unsubscribe = vi.fn();
  const registration = {
    scope: accountScope(base, owner.id), active: { state: 'activated' },
    pushManager: { subscribe, getSubscription: async () => ({
      options: { applicationServerKey: new Uint8Array([1, 2, 3]).buffer },
      unsubscribe, toJSON: () => ({ endpoint: 'https://web.push.apple.com/test', keys: { auth: 'a', p256dh: 'b' } }),
    }) },
  };
  vi.stubGlobal('navigator', { languages: ['de-DE'], serviceWorker: {
    getRegistration: async () => registration, register: async () => registration,
  } });
  vi.stubGlobal('window', { PushManager: {}, Notification: {} });
  vi.stubGlobal('isSecureContext', true);
  vi.stubGlobal('Notification', { permission: 'granted', requestPermission: vi.fn() });
  vi.mocked(api).mockImplementation(async (_account, path) => path === '/api/webpush-config'
    ? { config_id: 'c', declarative_web_push: true, webpush_language: true } : undefined);
});
afterEach(() => vi.unstubAllGlobals());

it('updates existing metadata and deduplicates it without subscribing or asking permission', async () => {
  await syncPushLanguage(owner, base);
  expect(api).toHaveBeenCalledWith(owner, '/api/device', 'PUT', expect.objectContaining({
    webpush_subscription: expect.objectContaining({ language: 'de', endpoint: 'https://web.push.apple.com/test', web_app_url: 'https://web.example/talk/#account=family' }),
  }));
  await syncPushLanguage(owner, base);
  expect(api).toHaveBeenCalledTimes(2);
  expect(subscribe).not.toHaveBeenCalled(); expect(unsubscribe).not.toHaveBeenCalled();
  expect(Notification.requestPermission).not.toHaveBeenCalled();
});

it('sends the new effective language after a browser language change', async () => {
  await syncPushLanguage(owner, base);
  Object.defineProperty(navigator, 'languages', { value: ['ja-JP'], configurable: true });
  await syncPushLanguage(owner, base);
  expect(api).toHaveBeenLastCalledWith(owner, '/api/device', 'PUT', expect.objectContaining({ webpush_subscription: expect.objectContaining({ language: 'ja' }) }));
});

it('does not send unknown fields to an older server', async () => {
  vi.mocked(api).mockResolvedValue({ config_id: 'c', declarative_web_push: true });
  await syncPushLanguage(owner, base);
  expect(api).toHaveBeenCalledTimes(1);
  expect(api).toHaveBeenCalledWith(owner, '/api/webpush-config');
});

it.each(['registration', 'metadata'] as const)('synchronizes after an old server is upgraded following %s, without reloading the page', async initial => {
  let supportsLanguage = false;
  vi.mocked(api).mockImplementation(async (_account, path) => path === '/api/webpush-config'
    ? { config_id: 'c', vapid_public_key: 'AQID', declarative_web_push: true, webpush_language: supportsLanguage } : undefined);
  if (initial === 'registration') {
    await enablePush(owner, base, false);
    const body = vi.mocked(api).mock.calls.find(([, path]) => path === '/api/device')![3] as { webpush_subscription: object };
    expect(body.webpush_subscription).not.toHaveProperty('language');
  } else {
    await syncPushLanguage(owner, base);
    expect(api).toHaveBeenCalledTimes(1);
  }

  supportsLanguage = true;
  await syncPushLanguage(owner, base);
  expect(api).toHaveBeenLastCalledWith(owner, '/api/device', 'PUT', expect.objectContaining({
    webpush_subscription: expect.objectContaining({ language: 'de' }),
  }));
  const requests = vi.mocked(api).mock.calls.length;
  await syncPushLanguage(owner, base);
  expect(api).toHaveBeenCalledTimes(requests);
  expect(subscribe).not.toHaveBeenCalled(); expect(unsubscribe).not.toHaveBeenCalled();
  expect(Notification.requestPermission).not.toHaveBeenCalled();
});

it('retries after a failed update, without marking it synchronized', async () => {
  vi.mocked(api).mockImplementationOnce(async () => ({ config_id: 'c', webpush_language: true }))
    .mockRejectedValueOnce(new TypeError('offline'));
  await expect(syncPushLanguage(owner, base)).rejects.toThrow('offline');
  await syncPushLanguage(owner, base);
  expect(api).toHaveBeenCalledTimes(4);
});

it('leaves disabled notifications disabled', async () => {
  owner.pushConfigId = undefined;
  await syncPushLanguage(owner, base);
  expect(api).not.toHaveBeenCalled(); expect(subscribe).not.toHaveBeenCalled();
});

it('does not overwrite a subscription after the server changes its configuration', async () => {
  vi.mocked(api).mockResolvedValue({ config_id: 'new-key', webpush_language: true });
  await syncPushLanguage(owner, base);
  expect(api).toHaveBeenCalledTimes(1); expect(unsubscribe).not.toHaveBeenCalled();
});
