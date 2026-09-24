import { t } from './i18n';
import { afterEach, expect, it, vi } from 'vitest';
import { closeCallNotification, disablePush, enablePush, isActiveNotificationCall, notificationCallState, requestPushPermission, updatePushWorker } from './push';
import { accountScope, type Account, type PushRecord } from './model';
import { api, APIError } from './api';
import { readPush } from './storage';

vi.mock('./api', async importOriginal => ({ ...await importOriginal<typeof import('./api')>(), api: vi.fn() }));
vi.mock('./storage', () => ({ saveAccount: vi.fn(), readPush: vi.fn() }));
afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); vi.clearAllMocks(); });

const ringingOwner: Account = { id: 'a', server: 'https://family.example', login: 'bob', token: 'test', name: 'Bob', deviceId: 'd', sessionId: 's' };
function ringingPush(): PushRecord {
  return { id: 'a:call-1', accountId: 'a', callId: 'call-1', sessionId: 's', type: 'incoming_call', caller: 'alice', receivedAt: Date.now(), expiresAt: Date.now() + 30000 };
}

it('recognizes a waiting invitation even when another call is primary', async () => {
  vi.mocked(api).mockResolvedValue({call_id:'current', incoming_calls:[{call_id:'waiting'}]});
  await expect(isActiveNotificationCall(ringingOwner, 'waiting')).resolves.toBe(true);
  await expect(isActiveNotificationCall(ringingOwner, 'other')).resolves.toBe(false);
});

it('updates worker code without permission, subscription changes or server requests', async () => {
  const registration = { active: { state: 'activated' } };
  const register = vi.fn(async () => registration);
  vi.stubGlobal('Notification', { permission: 'denied' });
  vi.stubGlobal('navigator', { serviceWorker: { register } });
  await expect(updatePushWorker(ringingOwner, 'https://web.example/')).resolves.toBe(registration);
  expect(register).toHaveBeenCalledWith(expect.any(URL), { scope: accountScope('https://web.example/', ringingOwner.id), updateViaCache: 'none' });
  expect(api).not.toHaveBeenCalled();
});

it('recovers a background incoming call only while both the inbox and server still consider it active', async () => {
  vi.mocked(readPush).mockResolvedValue(ringingPush());
  vi.mocked(api).mockResolvedValue({ call_id: 'call-1' });
  expect(await notificationCallState(ringingOwner, 'call-1')).toBe('incoming');
  vi.mocked(api).mockResolvedValue(undefined);
  expect(await notificationCallState(ringingOwner, 'call-1')).toBe('ended');
});

it('stops on cancellation or expiration without waiting for a network request', async () => {
  vi.mocked(readPush).mockResolvedValue({ ...ringingPush(), type: 'call_cancel' });
  expect(await notificationCallState(ringingOwner, 'call-1')).toBe('ended');
  vi.mocked(readPush).mockResolvedValue({ ...ringingPush(), expiresAt: Date.now() - 1 });
  expect(await notificationCallState(ringingOwner, 'call-1')).toBe('ended');
  expect(api).not.toHaveBeenCalled();
});

it('ignores pushes belonging to an old session or a missing inbox record', async () => {
  vi.mocked(readPush).mockResolvedValue({ ...ringingPush(), sessionId: 'old' });
  expect(await notificationCallState(ringingOwner, 'call-1')).toBe('ignore');
  vi.mocked(readPush).mockResolvedValue(undefined);
  expect(await notificationCallState(ringingOwner, 'call-1')).toBe('ignore');
  expect(api).not.toHaveBeenCalled();
});

it('does not start ringing if cancellation arrives during server verification', async () => {
  vi.mocked(readPush).mockResolvedValueOnce(ringingPush()).mockResolvedValueOnce({ ...ringingPush(), type: 'call_cancel' });
  vi.mocked(api).mockResolvedValue({ call_id: 'call-1' });
  expect(await notificationCallState(ringingOwner, 'call-1')).toBe('ended');
});

it('asks the account worker to serialize a handled call with incoming pushes', async () => {
  const postMessage = vi.fn();
  vi.stubGlobal('navigator', { serviceWorker: { getRegistration: async () => ({
    scope: 'https://web.example/notifications/a/', active: { postMessage }, getNotifications: async () => [],
  }) } });
  await closeCallNotification('a', 'call-1', 'https://web.example/');
  expect(postMessage).toHaveBeenCalledWith({ type: 'call-handled', callId: 'call-1' });
});

it('keeps the replay path for an older server but does not hide authentication errors', async () => {
  const owner: Account = { id: 'a', server: 'https://family.example', login: 'bob', token: 'test', name: 'Bob', deviceId: 'd', sessionId: 's' };
  vi.mocked(api).mockRejectedValueOnce(new APIError(404, 'old server'));
  expect(await isActiveNotificationCall(owner, 'call-1')).toBe(true);
  vi.mocked(api).mockRejectedValueOnce(new APIError(401, 'session replaced'));
  await expect(isActiveNotificationCall(owner, 'call-1')).rejects.toMatchObject({ status: 401 });
});

it.each([
  [{ call_id: 'call-1' }, true],
  [{ call_id: 'another-call' }, false],
  [undefined, false],
])('checks the notification call against the selected account server: %j', async (active, expected) => {
  const owner: Account = { id: 'family-a', server: 'https://family.example', login: 'bob', token: 'test', name: 'Bob', deviceId: 'd', sessionId: 's' };
  vi.mocked(api).mockResolvedValue(active);
  expect(await isActiveNotificationCall(owner, 'call-1')).toBe(expected);
  expect(api).toHaveBeenCalledWith(owner, '/api/active-call?call_waiting=1');
});

it('closes only the matching call notification in its own account registration', async () => {
  const close = vi.fn(), getNotifications = vi.fn(async () => [{ close }]);
  const scope = 'https://official.example/notifications/family-a/';
  const getRegistration = vi.fn(async () => ({ scope, getNotifications }));
  vi.stubGlobal('navigator', { serviceWorker: { getRegistration } });
  await closeCallNotification('family-a', 'call-1', 'https://official.example/');
  expect(getRegistration).toHaveBeenCalledWith(scope);
  expect(getNotifications).toHaveBeenCalledWith({ tag: 'family-a:call-1' });
  expect(close).toHaveBeenCalledOnce();
  getRegistration.mockResolvedValue({ scope: 'https://official.example/', getNotifications });
  await closeCallNotification('family-a', 'call-1', 'https://official.example/');
  expect(close).toHaveBeenCalledOnce();
});

it.each([true, false])('registers an Apple fallback URL only when the server supports it (%s)', async supportsDeclarative => {
  const owner: Account = { id: 'family-a', server: 'https://family.example', login: 'bob', token: 'test', name: 'Bob', deviceId: 'd', sessionId: 's' };
  const subscription = {
    options: { applicationServerKey: new Uint8Array([1, 2, 3]).buffer },
    toJSON: () => ({ endpoint: 'https://web.push.apple.com/existing', keys: {} }), unsubscribe: vi.fn(),
  };
  const registration = { active: { state: 'activated' }, pushManager: { getSubscription: async () => subscription, subscribe: vi.fn() } };
  vi.stubGlobal('navigator', { serviceWorker: { register: async () => registration } });
  vi.stubGlobal('isSecureContext', true);
  vi.stubGlobal('window', { PushManager: {}, Notification: {} });
  vi.stubGlobal('Notification', { permission: 'granted' });
  vi.mocked(api).mockResolvedValue({ vapid_public_key: 'AQID', config_id: 'test', ...(supportsDeclarative ? { declarative_web_push: true } : {}) });
  await enablePush(owner, 'https://official.example/talk/');
  expect(api).toHaveBeenCalledWith(owner, '/api/device', 'PUT', expect.objectContaining({ webpush_subscription: {
    endpoint: 'https://web.push.apple.com/existing', keys: {}, client_type: 'web',
    ...(supportsDeclarative ? { web_app_url: 'https://official.example/talk/#account=family-a' } : {}),
  } }));
  expect(subscription.unsubscribe).not.toHaveBeenCalled();
  expect(registration.pushManager.subscribe).not.toHaveBeenCalled();
});

it('uses separate registrations and each server VAPID key; removing one leaves the other subscribed', async () => {
  const base = 'https://official.example/app/';
  const owners: Account[] = ['family-a', 'family-b'].map(id => ({ id, server: `https://${id}.example`, login: 'grandma', token: 'test', name: id, deviceId: id, sessionId: id }));
  const registrations = owners.map(owner => ({
    scope: accountScope(base, owner.id), active: { state: 'activated' }, unregister: vi.fn(),
    pushManager: { getSubscription: vi.fn(async () => null as unknown), subscribe: vi.fn(async () => ({
      toJSON: () => ({ endpoint: `https://push.example/${owner.id}`, keys: {} }), unsubscribe: vi.fn(),
    })) },
  }));
  const serviceWorker = {
    register: vi.fn(async (_url: URL, options: { scope: string }) => registrations.find(r => r.scope === options.scope)),
    getRegistration: vi.fn(async (scope: string) => registrations.find(r => r.scope === scope)),
  };
  vi.stubGlobal('navigator', { serviceWorker });
  vi.stubGlobal('isSecureContext', true);
  vi.stubGlobal('window', { PushManager: {}, Notification: {} });
  vi.stubGlobal('Notification', { permission: 'granted' });
  vi.mocked(api).mockImplementation(async (account, path) => path === '/api/webpush-config'
    ? { vapid_public_key: account.id === 'family-a' ? 'AQID' : 'BAUG', config_id: account.id } : undefined);
  await enablePush(owners[0], base);
  await enablePush(owners[1], base);
  expect(registrations[0].pushManager.subscribe).toHaveBeenCalledWith({ userVisibleOnly: true, applicationServerKey: new Uint8Array([1, 2, 3]) });
  expect(registrations[1].pushManager.subscribe).toHaveBeenCalledWith({ userVisibleOnly: true, applicationServerKey: new Uint8Array([4, 5, 6]) });
  const first = await registrations[0].pushManager.subscribe.mock.results[0].value;
  registrations[0].pushManager.getSubscription.mockResolvedValue(first);
  await disablePush(owners[0], base);
  expect(first.unsubscribe).toHaveBeenCalledOnce();
  expect(registrations[1].unregister).not.toHaveBeenCalled();
  expect(owners[1].pushConfigId).toBe('family-b');
  expect(owners[0].pushConfigId).toBeUndefined();
});

it('registers the built worker version and preserves the account scope and existing subscription', async () => {
  vi.stubEnv('VITE_PUSH_WORKER_VERSION', 'new-build');
  const owner: Account = { id: 'family-a', server: 'https://family.example', login: 'test', token: 'test', name: 'Test', deviceId: 'd', sessionId: 's' };
  const subscription = {
    options: { applicationServerKey: new Uint8Array([1, 2, 3]).buffer },
    toJSON: () => ({ endpoint: 'https://push.example/existing', keys: {} }),
    unsubscribe: vi.fn(),
  };
  const registration = {
    active: { state: 'activated' }, installing: null, waiting: null,
    pushManager: { getSubscription: async () => subscription, subscribe: vi.fn() },
  };
  const register = vi.fn(async () => registration);
  vi.stubGlobal('navigator', { serviceWorker: { register } });
  vi.stubGlobal('isSecureContext', true);
  vi.stubGlobal('window', { PushManager: {}, Notification: {} });
  vi.stubGlobal('Notification', { permission: 'granted' });
  vi.mocked(api).mockResolvedValue({ vapid_public_key: 'AQID', config_id: 'test' });
  await enablePush(owner, 'https://official.example/');
  expect(register).toHaveBeenCalledWith(new URL('https://official.example/push-worker.js?v=new-build'), {
    scope: 'https://official.example/notifications/family-a/', updateViaCache: 'none',
  });
  expect(subscription.unsubscribe).not.toHaveBeenCalled();
  expect(registration.pushManager.subscribe).not.toHaveBeenCalled();
  expect(api).toHaveBeenCalledWith(owner, '/api/device', 'PUT', expect.objectContaining({
    webpush_subscription: { endpoint: 'https://push.example/existing', keys: {}, client_type: 'web' },
  }));
});

it('waits for the new worker to activate even when an older worker is already active', async () => {
  const owner: Account = { id: 'family-a', server: 'https://family.example', login: 'test', token: 'test', name: 'Test', deviceId: 'd', sessionId: 's' };
  const installing = Object.assign(new EventTarget(), { state: 'installing' as ServiceWorkerState });
  const subscribe = vi.fn(async () => ({ toJSON: () => ({ endpoint: 'https://push.example/test', keys: {} }) }));
  vi.stubGlobal('navigator', { serviceWorker: { register: async () => ({
    active: { state: 'activated' }, installing, waiting: null,
    pushManager: { getSubscription: async () => null, subscribe },
  }) } });
  vi.stubGlobal('isSecureContext', true);
  vi.stubGlobal('window', { PushManager: {}, Notification: {} });
  vi.stubGlobal('Notification', { permission: 'granted' });
  vi.mocked(api).mockResolvedValue({ vapid_public_key: 'AQID', config_id: 'test' });
  const job = enablePush(owner, 'https://official.example/');
  // Flush configuration promises while the new worker is still installing.
  await new Promise(resolve => setTimeout(resolve, 0));
  expect(subscribe).not.toHaveBeenCalled();
  installing.state = 'activated';
  installing.dispatchEvent(new Event('statechange'));
  await job;
  expect(subscribe).toHaveBeenCalledOnce();
});

it('does not ask again when the user has already blocked notifications', async () => {
  const requestPermission = vi.fn(async () => 'denied');
  vi.stubGlobal('isSecureContext', true);
  vi.stubGlobal('window', { PushManager: {}, Notification: {} });
  vi.stubGlobal('navigator', { serviceWorker: {} });
  vi.stubGlobal('Notification', { permission: 'denied', requestPermission });
  await expect(enablePush(ringingOwner, 'https://web.example/')).rejects.toThrow(t('web_notifications_are_not_allowed_117'));
  expect(requestPermission).not.toHaveBeenCalled();
  expect(api).not.toHaveBeenCalled();
});

it('requests permission inside the user gesture and shares a still-open prompt', async () => {
  let userGesture = true;
  let decide!: (permission: NotificationPermission) => void;
  const requestPermission = vi.fn(() => {
    if (!userGesture) throw new Error('User gesture expired');
    return new Promise<NotificationPermission>(resolve => { decide = resolve; });
  });
  vi.stubGlobal('isSecureContext', true);
  vi.stubGlobal('window', { PushManager: {}, Notification: {} });
  vi.stubGlobal('navigator', { serviceWorker: {} });
  vi.stubGlobal('Notification', { permission: 'default', requestPermission });
  const login = requestPushPermission();
  userGesture = false;
  const retry = requestPushPermission();
  expect(requestPermission).toHaveBeenCalledOnce();
  decide('granted');
  expect(await login).toBe(true);
  expect(await retry).toBe(true);
  expect(api).not.toHaveBeenCalled();
});

it.each(['denied', 'default'] as const)('handles a %s permission decision without failing login', async decision => {
  vi.stubGlobal('isSecureContext', true);
  vi.stubGlobal('window', { PushManager: {}, Notification: {} });
  vi.stubGlobal('navigator', { serviceWorker: {} });
  vi.stubGlobal('Notification', { permission: 'default', requestPermission: async () => decision });
  expect(await requestPushPermission()).toBe(false);
});

it('allows a later button press to retry after the browser rejected a permission request', async () => {
  const requestPermission = vi.fn().mockRejectedValueOnce(new Error('NotAllowedError')).mockResolvedValueOnce('granted');
  vi.stubGlobal('isSecureContext', true);
  vi.stubGlobal('window', { PushManager: {}, Notification: {} });
  vi.stubGlobal('navigator', { serviceWorker: {} });
  vi.stubGlobal('Notification', { permission: 'default', requestPermission });
  expect(await requestPushPermission()).toBe(false);
  expect(await requestPushPermission()).toBe(true);
});
