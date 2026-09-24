import { t } from './i18n';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { account, contactPhoto, readPush, savePush } from './storage';
import { callKey, type Account, type PushRecord } from './model';

vi.mock('./storage', () => ({ account: vi.fn(), contactPhoto: vi.fn(), readPush: vi.fn(), savePush: vi.fn(), prunePushes: vi.fn(async () => {}) }));
const owner: Account = { id: 'family', server: 'https://family.example', login: 'bob', token: 'test-token', name: 'Bob', deviceId: 'phone', sessionId: 'session' };
const callId = '018f7d51-40a1-7bb5-a2d0-7e47f9180101';
let record: PushRecord;
const focus = vi.fn(async () => {}), postMessage = vi.fn(), openWindow = vi.fn(), showNotification = vi.fn();
const request = vi.fn(async () => new Response(null, { status: 204 }));
let listeners: Record<string, (event: any) => void>;
let clients: unknown[];

beforeEach(async () => {
  vi.resetModules(); vi.clearAllMocks();
  postMessage.mockReset();
  listeners = {};
  clients = [{ url: 'https://web.example/', visibilityState: 'hidden', focused: false, focus, postMessage }];
  record = { id: callKey(owner.id, callId), accountId: owner.id, callId, type: 'incoming_call', caller: 'alice', expiresAt: Date.now() + 40000, receivedAt: Date.now(), sessionId: owner.sessionId };
  vi.mocked(account).mockResolvedValue(owner);
  vi.mocked(contactPhoto).mockReset().mockResolvedValue(undefined);
  vi.mocked(readPush).mockImplementation(async () => record);
  vi.mocked(savePush).mockImplementation(async value => { record = value; return value.id; });
  request.mockReset().mockResolvedValue(new Response(null, { status: 204 }));
  vi.stubGlobal('fetch', request);
  vi.stubGlobal('self', {
    location: { href: 'https://web.example/push-worker.js?v=current' },
    registration: { scope: 'https://web.example/notifications/family/', getNotifications: async () => [], showNotification },
    clients: { matchAll: async () => clients, openWindow },
    addEventListener: (type: string, callback: (event: any) => void) => { listeners[type] = callback; },
  });
  await import('./push-worker');
});
afterEach(() => vi.unstubAllGlobals());

async function dispatch(type: string, event: object): Promise<void> {
  let job: Promise<unknown> | undefined;
  listeners[type]({ ...event, waitUntil: (promise: Promise<unknown>) => { job = promise; } });
  await job;
}
async function click(action: string): Promise<void> {
  const close = vi.fn();
  await dispatch('notificationclick', { action, notification: { close, data: { accountId: owner.id, callId, sessionId: owner.sessionId } } });
  expect(close).toHaveBeenCalledOnce();
}

async function incomingNotification(userAgent: string) {
  vi.stubGlobal('navigator', { userAgent });
  await dispatch('push', { data: { json: () => ({ type: 'incoming_call', call_id: callId, caller_login: 'alice', target_session_id: owner.sessionId, expires_at: new Date(Date.now() + 40000).toISOString() }) } });
  expect(showNotification).toHaveBeenCalledOnce();
  const options = showNotification.mock.calls[0][1];
  return { ...options, close: vi.fn() };
}
it('uses the caller photo from this account in an incoming notification', async () => {
  vi.mocked(contactPhoto).mockResolvedValue({id: 'photo', accountId: owner.id, login: 'alice', dataUrl: 'data:image/png;base64,AAAA', updatedAt: 1});
  const notification = await incomingNotification('Chrome/153 Android');
  expect(notification.icon).toBe('data:image/png;base64,AAAA');
});

it('keeps the app icon when reading the photo fails', async () => {
  vi.mocked(contactPhoto).mockRejectedValue(new Error('storage unavailable'));
  const notification = await incomingNotification('Chrome/153 Android');
  expect(notification.icon).toBe('https://web.example/icon-192.png');
});

it('never uses another account photo', async () => {
  vi.mocked(contactPhoto).mockResolvedValue({id: 'photo', accountId: 'other', login: 'alice', dataUrl: 'data:image/png;base64,AAAA', updatedAt: 1});
  const notification = await incomingNotification('Chrome/153 Android');
  expect(notification.icon).toBe('https://web.example/icon-192.png');
});

function staysInBackground(): void {
  expect(focus).not.toHaveBeenCalled(); expect(openWindow).not.toHaveBeenCalled(); expect(showNotification).not.toHaveBeenCalled();
  expect(postMessage.mock.calls.every(([message]) => message.type !== 'open-call')).toBe(true);
}

const appleUA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 Version/26.0 Mobile/15E148 Safari/604.1';

it('uses the saved language for an incoming notification without an open app', async () => {
  clients = [];
  vi.stubGlobal('caches', { open: async () => ({ match: async () => new Response('ja') }) });
  const notification = await incomingNotification('Mozilla/5.0 (Windows NT 10.0) Chrome/130.0.0.0');
  expect(notification.actions).toEqual([{ action: 'answer', title: '応答' }, { action: 'reject', title: '拒否' }]);
});

it('opens the lock owner instead of a visible duplicate tab', async () => {
  const duplicateFocus = vi.fn(), duplicateMessage = vi.fn();
  clients = [
    { id: 'duplicate', url: 'https://web.example/', visibilityState: 'visible', focused: true, focus: duplicateFocus, postMessage: duplicateMessage },
    { id: 'owner', url: 'https://web.example/', visibilityState: 'hidden', focused: false, focus, postMessage },
  ];
  vi.stubGlobal('navigator', { locks: { query: async () => ({ held: [{ name: 'tinitalk-pwa-client', clientId: 'owner' }] }) } });
  await click('answer');
  expect(duplicateFocus).not.toHaveBeenCalled();
  expect(duplicateMessage).not.toHaveBeenCalled();
  expect(focus).toHaveBeenCalledOnce();
  expect(postMessage).toHaveBeenCalledWith({ type: 'open-call', accountId: owner.id, callId, action: 'answer' });
  expect(openWindow).not.toHaveBeenCalled();
});

it('does not suppress an incoming notification for a blocked visible tab', async () => {
  clients = [{ id: 'duplicate', url: 'https://web.example/', visibilityState: 'visible', focused: true, focus, postMessage }];
  vi.stubGlobal('navigator', { userAgent: 'Chrome', locks: { query: async () => ({ held: [] }) } });
  await dispatch('push', { data: { json: () => ({ type: 'incoming_call', call_id: callId, target_session_id: owner.sessionId, expires_at: new Date(Date.now() + 40000).toISOString() }) } });
  expect(showNotification).toHaveBeenCalledOnce();
  expect(showNotification.mock.calls[0][1].silent).not.toBe(true);
  expect(postMessage).not.toHaveBeenCalled();
  await click('answer');
  expect(focus).not.toHaveBeenCalled();
  expect(openWindow).toHaveBeenCalledOnce();
});

it('keeps an audible incoming notification when only the installation page is visible', async () => {
  clients = [{ url: 'https://web.example/?install=1', visibilityState: 'visible', focused: true, focus, postMessage }];
  const notification = await incomingNotification('Mozilla/5.0 (Linux; Android 10; K) Chrome/152.0.0.0 Mobile Safari/537.36');
  expect(notification.silent).not.toBe(true);
  expect(postMessage).not.toHaveBeenCalled();
});

it('does not focus the installation page when opening a call from a notification', async () => {
  clients = [{ url: 'https://web.example/?install=1', visibilityState: 'visible', focused: true, focus, postMessage }];
  await click('answer');
  expect(focus).not.toHaveBeenCalled();
  expect(postMessage).not.toHaveBeenCalled();
  expect(openWindow).toHaveBeenCalledWith(`https://web.example/#account=family&call=${callId}&action=answer`);
});

it('chooses the running PWA over the visible installation page', async () => {
  const landingFocus = vi.fn(), landingMessage = vi.fn();
  clients = [
    { url: 'https://web.example/?install=1', visibilityState: 'visible', focused: true, focus: landingFocus, postMessage: landingMessage },
    { url: 'https://web.example/', visibilityState: 'hidden', focused: false, focus, postMessage },
  ];
  await click('answer');
  expect(landingFocus).not.toHaveBeenCalled();
  expect(landingMessage).not.toHaveBeenCalled();
  expect(focus).toHaveBeenCalledOnce();
  expect(openWindow).not.toHaveBeenCalled();
});

it('remembers an in-app answer before the delayed Apple invite arrives', async () => {
  vi.stubGlobal('navigator', { userAgent: appleUA });
  let inbox: PushRecord | undefined;
  vi.mocked(readPush).mockImplementation(async () => inbox);
  vi.mocked(savePush).mockImplementation(async value => { inbox = value; return value.id; });
  const close = vi.fn();
  (self as unknown as ServiceWorkerGlobalScope).registration.getNotifications = vi.fn(async () => [{ close }] as unknown as Notification[]);
  await dispatch('message', { data: { type: 'call-handled', callId } });
  expect(inbox).toMatchObject({ id: `family:${callId}`, type: 'call_cancel' });
  expect(close).toHaveBeenCalledOnce();
  await dispatch('push', { data: { json: () => ({ type: 'incoming_call', call_id: callId, target_session_id: owner.sessionId, expires_at: new Date(Date.now() + 30000).toISOString() }) } });
  expect(inbox?.type).toBe('call_cancel');
  expect(showNotification.mock.calls[0][0]).toBe(t('web_the_call_has_already_ended_122'));
  expect(postMessage.mock.calls.some(([message]) => message.type === 'open-call')).toBe(false);
});

it('shows an Apple incoming notification even with the app visible, without another sound', async () => {
  clients = [{ url: 'https://web.example/', visibilityState: 'visible', focused: true, focus, postMessage }];
  const notification = await incomingNotification(appleUA);
  expect(notification.silent).toBe(true);
  expect(notification.actions ?? []).toEqual([]);
  expect(notification.defaultAction).toBeUndefined();
  expect(postMessage).toHaveBeenCalledWith({ type: 'open-call', accountId: owner.id, callId });
});

it('does not show a second Apple notification when the visible window closes during delivery', async () => {
  clients = [{ url: 'https://web.example/', visibilityState: 'visible', focused: true, focus, postMessage }];
  postMessage.mockImplementationOnce(() => { throw new Error('window closed'); });
  const notification = await incomingNotification(appleUA);
  expect(notification.tag).toBe(`family:${callId}`);
});

it.each([false, true])('falls back to a system notification only if delivery to the visible app throws: %s', async closed => {
  vi.stubGlobal('navigator', {userAgent:'Chrome'});
  clients = [{url:'https://web.example/', visibilityState:'visible', focused:true, focus, postMessage}];
  postMessage.mockImplementation(message => {
    if (closed && message.type === 'open-call') throw new Error('window closed');
    // Restoring the call is asynchronous; the page does not acknowledge display.
  });
  await dispatch('push', {data:{json:() => ({type:'incoming_call', call_id:callId, target_session_id:owner.sessionId, expires_at:new Date(Date.now()+40000).toISOString()})}});
  expect(postMessage.mock.calls.filter(([message]) => message.type === 'open-call')).toHaveLength(1);
  expect(showNotification).toHaveBeenCalledTimes(closed ? 1 : 0);
  if (closed) expect(showNotification.mock.calls[0][1]).toMatchObject({tag:callKey(owner.id, callId)});
});

it.each([true, false])('opens the correct account from Apple declarative push (existing window: %s)', async hasWindow => {
  vi.stubGlobal('navigator', { userAgent: appleUA });
  if (!hasWindow) clients = [];
  // WebKit exposes proposed notification.data, with event.data === null.
  await dispatch('push', { data: null, notification: { data: { tinitalk: {
    type: 'incoming_call', call_id: callId, caller: 'Алиса', caller_login: 'alice',
    target_session_id: owner.sessionId, target_device_id: owner.deviceId,
    expires_at: new Date(Date.now() + 40000).toISOString(),
  } } } });
  expect(showNotification).toHaveBeenCalledOnce();
  expect(showNotification.mock.calls[0][0]).toContain('Алиса');
  const options = showNotification.mock.calls[0][1];
  expect(options.silent).toBe(false);
  expect(options.tag).toBe(`family:${callId}`);
  expect(options.actions ?? []).toEqual([]);
  expect(record).toMatchObject({ accountId: 'family', callId, type: 'incoming_call', caller: 'alice' });
  await dispatch('notificationclick', { action: '', notification: { ...options, close: vi.fn() } });
  if (hasWindow) expect(postMessage).toHaveBeenCalledWith({ type: 'open-call', accountId: 'family', callId, action: undefined });
  else expect(openWindow).toHaveBeenCalledWith(`https://web.example/#account=family&call=${callId}`);
  expect(request).not.toHaveBeenCalled();
});

it('does not silently drop an expired Apple invite or offer to accept it', async () => {
  vi.stubGlobal('navigator', { userAgent: appleUA });
  await dispatch('push', { data: { json: () => ({ type: 'incoming_call', call_id: callId, target_session_id: owner.sessionId, expires_at: new Date(Date.now() - 1000).toISOString() }) } });
  expect(showNotification).toHaveBeenCalledOnce();
  expect(showNotification.mock.calls[0][1].actions ?? []).toEqual([]);
  expect(showNotification.mock.calls[0][1].defaultAction).toBeUndefined();
  expect(postMessage.mock.calls.some(([message]) => message.type === 'open-call')).toBe(false);
});

it('shows a generic Apple notification if local account data is unavailable', async () => {
  vi.stubGlobal('navigator', { userAgent: appleUA });
  vi.mocked(account).mockRejectedValue(new Error('storage unavailable'));
  await dispatch('push', { data: { json: () => ({ type: 'incoming_call', call_id: callId, caller: 'Private name', expires_at: new Date(Date.now() + 40000).toISOString() }) } });
  expect(showNotification).toHaveBeenCalledOnce();
  expect(showNotification.mock.calls[0][0]).not.toContain('Private name');
  expect(showNotification.mock.calls[0][1].actions ?? []).toEqual([]);
  expect(postMessage.mock.calls.some(([message]) => message.type === 'open-call')).toBe(false);
});

it('reads the declarative JSON envelope on older Safari without event.notification', async () => {
  vi.stubGlobal('navigator', { userAgent: appleUA });
  await dispatch('push', { data: { json: () => ({ web_push: 8030, notification: { data: { tinitalk: {
    type: 'incoming_call', call_id: callId, caller_login: 'alice', target_session_id: owner.sessionId,
    expires_at: new Date(Date.now() + 40000).toISOString(),
  } } } }) } });
  expect(showNotification).toHaveBeenCalledOnce();
  expect(record.type).toBe('incoming_call');
  expect(showNotification.mock.calls[0][1].navigate).toBe(`https://web.example/#account=family&call=${callId}`);
});

it('opens an OS fallback notification even when no push inbox was written', async () => {
  vi.stubGlobal('navigator', { userAgent: appleUA });
  vi.mocked(readPush).mockResolvedValue(undefined);
  clients = [];
  await dispatch('notificationclick', { action: '', notification: { close: vi.fn(), data: {
    accountId: 'family', callId, sessionId: owner.sessionId, tinitalk: { type: 'incoming_call' },
  } } });
  expect(openWindow).toHaveBeenCalledWith(`https://web.example/#account=family&call=${callId}`);
  expect(request).not.toHaveBeenCalled();
});

it.each([
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15) AppleWebKit/605.1.15 Version/26.0 Mobile/15E148 Safari/604.1',
  'Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 CriOS/152.0.0.0 Mobile/15E148 Safari/604.1',
])('keeps Apple visible-push policy for desktop iPad and Chrome on iOS: %s', async userAgent => {
  const notification = await incomingNotification(userAgent);
  expect(notification.actions ?? []).toEqual([]);
  expect(notification.silent).toBe(false);
});

it('does not sound a repeated Apple notification for the same call', async () => {
  (self as unknown as ServiceWorkerGlobalScope).registration.getNotifications = vi.fn(async () => [{ tag: `family:${callId}` }] as Notification[]);
  const notification = await incomingNotification(appleUA);
  expect(notification.tag).toBe(`family:${callId}`);
  expect(notification.silent).toBe(true);
});

it('turns an Apple push for a replaced session into a generic card without exposing the caller', async () => {
  vi.stubGlobal('navigator', { userAgent: appleUA });
  await dispatch('push', { notification: { data: { tinitalk: {
    type: 'incoming_call', call_id: callId, caller: 'Private name', target_session_id: 'old-session',
    expires_at: new Date(Date.now() + 40000).toISOString(),
  } } } });
  expect(showNotification).toHaveBeenCalledOnce();
  expect(showNotification.mock.calls[0][0]).toBe('TiniTalk');
  expect(record.type).toBe('stale');
  expect(postMessage.mock.calls.some(([message]) => message.type === 'open-call')).toBe(false);
});

it.each([true, false])('declines without opening or focusing the app (existing window: %s)', async hasWindow => {
  if (!hasWindow) clients = [];
  await click('reject');
  expect(request).toHaveBeenCalledOnce();
  const [url, options] = request.mock.calls[0] as unknown as [URL, RequestInit];
  expect(url.href).toBe(`https://family.example/api/calls/${callId}/reject`);
  expect(options).toMatchObject({ method: 'POST', credentials: 'omit', redirect: 'error', headers: {
    Authorization: `Basic ${btoa('bob:test-token')}`, 'X-TiniTalk-Session-ID': 'session', 'X-TiniTalk-Device-ID': 'phone',
  } });
  expect(record.type).toBe('call_cancel');
  staysInBackground();
  await click('reject');
  expect(request).toHaveBeenCalledOnce();
});

it.each(['expired', 'replaced', 'cancelled', 'removed'])('ignores a %s notification', async state => {
  if (state === 'expired') record.expiresAt = Date.now() - 1;
  if (state === 'replaced') vi.mocked(account).mockResolvedValue({ ...owner, sessionId: 'new-session' });
  if (state === 'cancelled') record.type = 'call_cancel';
  if (state === 'removed') vi.mocked(account).mockResolvedValue(undefined);
  await click('reject');
  expect(request).not.toHaveBeenCalled(); staysInBackground();
});

it('never opens the app or records success if the request fails', async () => {
  request.mockRejectedValue(new TypeError('offline'));
  await click('reject');
  expect(request).toHaveBeenCalledTimes(2);
  expect(record.type).toBe('incoming_call'); staysInBackground();
});

it('does not retry an authentication failure', async () => {
  request.mockResolvedValue(new Response('unauthorized', { status: 401 }));
  await click('reject');
  expect(request).toHaveBeenCalledOnce();
  expect(record.type).toBe('incoming_call'); staysInBackground();
});

it('retries a server failure and records only the acknowledged result', async () => {
  request.mockResolvedValueOnce(new Response('unavailable', { status: 503 }));
  await click('reject');
  expect(request).toHaveBeenCalledTimes(2);
  expect(record.type).toBe('call_cancel'); staysInBackground();
});

it('serializes a repeated invite with an in-flight background rejection', async () => {
  let acknowledge!: (response: Response) => void;
  request.mockReturnValueOnce(new Promise(resolve => { acknowledge = resolve; }));
  const rejection = click('reject');
  await vi.waitFor(() => expect(request).toHaveBeenCalledOnce());
  const invite = dispatch('push', { data: { json: () => ({ type: 'incoming_call', call_id: callId, target_session_id: owner.sessionId, expires_at: new Date(Date.now() + 30000).toISOString() }) } });
  acknowledge(new Response(null, { status: 204 }));
  await Promise.all([rejection, invite]);
  expect(record.type).toBe('call_cancel'); staysInBackground();
});

it('does not resurrect the notification when a repeated invite arrives after rejection', async () => {
  await click('reject');
  await dispatch('push', { data: { json: () => ({ type: 'incoming_call', call_id: callId, caller_login: 'alice', target_session_id: owner.sessionId, expires_at: new Date(Date.now() + 30000).toISOString() }) } });
  expect(record.type).toBe('call_cancel'); staysInBackground();
});

it.each(['answer', ''])('still opens the call for action %s', async action => {
  await click(action);
  expect(focus).toHaveBeenCalledOnce();
  expect(postMessage).toHaveBeenCalledWith({ type: 'open-call', accountId: owner.id, callId, action: action || undefined });
  expect(request).not.toHaveBeenCalled();
});

it('does not trust a reject action from an old two-button notification on affected Android Chrome', async () => {
  vi.stubGlobal('navigator', { userAgent: 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/152.0.0.0 Mobile Safari/537.36' });
  await dispatch('notificationclick', { action: 'reject', notification: {
    close: vi.fn(),
    actions: [{ action: 'answer', title: t('web_answer_125') }, { action: 'reject', title: t('text_decline_63') }],
    data: { accountId: owner.id, callId, sessionId: owner.sessionId },
  } });
  expect(request).not.toHaveBeenCalled();
  expect(focus).toHaveBeenCalledOnce();
  expect(postMessage).toHaveBeenCalledWith({ type: 'open-call', accountId: owner.id, callId, action: undefined });
});

it.each([
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/152.0.0.0 Safari/537.36',
  'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/153.0.0.0 Mobile Safari/537.36',
])('keeps a genuine reject action on an unaffected browser: %s', async userAgent => {
  vi.stubGlobal('navigator', { userAgent });
  await dispatch('notificationclick', { action: 'reject', notification: {
    close: vi.fn(),
    actions: [{ action: 'answer', title: t('web_answer_125') }, { action: 'reject', title: t('text_decline_63') }],
    data: { accountId: owner.id, callId, sessionId: owner.sessionId },
  } });
  expect(request).toHaveBeenCalledOnce();
  staysInBackground();
});

it.each([true, false])('accepts by tapping the single-button Android notification body (existing window: %s)', async hasWindow => {
  if (!hasWindow) clients = [];
  const notification = await incomingNotification('Mozilla/5.0 (Linux; Android 10; K) Chrome/152.0.0.0 Mobile Safari/537.36');
  expect(notification.actions).toEqual([{ action: 'reject', title: t('text_decline_63') }]);
  await dispatch('notificationclick', { action: '', notification });
  expect(request).not.toHaveBeenCalled();
  if (hasWindow) {
    expect(postMessage).toHaveBeenCalledWith({ type: 'open-call', accountId: owner.id, callId, action: 'answer' });
  } else {
    expect(openWindow).toHaveBeenCalledWith(`https://web.example/#account=family&call=${callId}&action=answer`);
  }
});

it('quietly rejects from the single button without confusing it with a body tap', async () => {
  const notification = await incomingNotification('Mozilla/5.0 (Linux; Android 10; K) Chrome/152.0.0.0 Mobile Safari/537.36');
  showNotification.mockClear();
  await dispatch('notificationclick', { action: 'reject', notification });
  expect(request).toHaveBeenCalledOnce();
  staysInBackground();
});

it.each(['answer', 'reject', ''])('keeps two Windows buttons and their separate actions: %s', async action => {
  const notification = await incomingNotification('Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/152.0.0.0 Safari/537.36');
  expect(notification.actions).toEqual([{ action: 'answer', title: t('web_answer_125') }, { action: 'reject', title: t('text_decline_63') }]);
  showNotification.mockClear();
  await dispatch('notificationclick', { action, notification });
  if (action === 'reject') {
    expect(request).toHaveBeenCalledOnce();
    staysInBackground();
  } else {
    expect(request).not.toHaveBeenCalled();
    expect(postMessage).toHaveBeenCalledWith({ type: 'open-call', accountId: owner.id, callId, action: action || undefined });
  }
});
