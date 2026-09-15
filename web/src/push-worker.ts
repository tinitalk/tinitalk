/// <reference lib="webworker" />
import { accountFromScope, callKey, canOpenIncoming, deepLink, type PushRecord } from './model';
import { api, APIError } from './api';
import { decidePushNotification, hasNotificationActionCollision, usesAppleWebPush } from './pushNotificationPolicy';
import { account, readPush, savePush, prunePushes } from './storage';
import { reportWorkerVersion } from './workerVersion';
import { isInstallationPageURL } from './installation';

const sw = self as unknown as ServiceWorkerGlobalScope;
reportWorkerVersion(sw, 'push');
const base = new URL('./', sw.location.href).href;
type NotificationOptionsWithActions = NotificationOptions & { navigate?: string; actions?: { action: string; title: string; icon?: string }[] };
type DeclarativePushEvent = PushEvent & { notification?: Notification | null };
type NotificationConstructorWithMaxActions = typeof Notification & { maxActions?: number };
sw.addEventListener('install', event => event.waitUntil(sw.skipWaiting()));
let delivery = Promise.resolve();
sw.addEventListener('message', event => {
  if (event.data?.type !== 'call-handled' || typeof event.data.callId !== 'string' || !event.data.callId) return;
  const task = delivery.then(async () => {
    const id = accountFromScope(sw.registration.scope);
    if (!id) return;
    const callId = event.data.callId as string;
    const key = callKey(id, callId);
    const previous = await readPush(key).catch(() => undefined);
    await savePush({ id: key, accountId: id, callId, caller: '', expiresAt: 0, sessionId: '',
      ...previous, type: 'call_cancel', receivedAt: Date.now() });
    await closeNotifications(key);
  });
  delivery = task.catch(() => undefined);
  event.waitUntil(delivery);
});
sw.addEventListener('push', event => {
  // Keep a cancel and a simultaneous late invite in durable arrival order.
  const task = delivery.then(() => handlePush(event)).catch(async error => {
    // Declarative pushes have an OS-owned fallback. Older Safari subscriptions
    // need an explicit fallback when a local error prevents normal rendering.
    if (!(event as DeclarativePushEvent).notification && usesAppleWebPush(navigator.userAgent)) {
      await sw.registration.showNotification('TiniTalk', {
        body: 'Откройте приложение, чтобы проверить звонки', tag: 'tinitalk-fallback', silent: true,
      });
      return;
    }
    throw error;
  });
  delivery = task.catch(() => undefined);
  event.waitUntil(task);
});
sw.addEventListener('notificationclick', event => {
  event.notification.close();
  const shownActions = (event.notification as Notification & { actions?: { action: string }[] }).actions;
  if (event.action && hasNotificationActionCollision(navigator.userAgent) && (shownActions?.length ?? 0) > 1) {
    // Old notifications can survive a worker update. Their action value is
    // ambiguous, so let the user choose on the call screen instead of rejecting.
    event.waitUntil(openCall(event.notification.data));
    return;
  }
  if (event.action === 'reject') {
    // Serialize with pushes so a concurrent invite cannot overwrite the local
    // terminal record after the server has acknowledged the rejection.
    const task = delivery.then(() => rejectCall(event.notification.data));
    delivery = task.catch(() => undefined);
    event.waitUntil(delivery);
    return;
  }
  // Only notifications explicitly rendered with body-as-answer have this
  // default. A normal body tap keeps opening the incoming call screen.
  const action = notificationCallAction(event.action)
    ?? (!event.action && event.notification.data?.defaultAction === 'answer' ? 'answer' : undefined);
  event.waitUntil(openCall(event.notification.data, action));
});

async function handlePush(event: PushEvent): Promise<void> {
  const id = accountFromScope(sw.registration.scope);
  if (!id) return;
  const raw = pushData(event);
  const apple = Boolean((event as DeclarativePushEvent).notification) || usesAppleWebPush(navigator.userAgent);
  const owner = await account(id).catch(() => undefined);
  const callId = typeof raw.call_id === 'string' ? raw.call_id : '';
  const key = callKey(id, callId);
  const previous = await readPush(key).catch(() => undefined);
  const isCurrent = owner && !owner.sessionReplaced && (!raw.target_session_id || raw.target_session_id === owner.sessionId) && (!raw.target_device_id || raw.target_device_id === owner.deviceId)
    && (raw.type !== 'session_replaced' || raw.revoked_session_id === owner.sessionId);
  let record: PushRecord = {
    id: key, accountId: id, callId, type: isCurrent ? raw.type : 'stale',
    caller: typeof raw.caller_login === 'string' ? raw.caller_login : '',
    expiresAt: Date.parse(raw.expires_at) || 0, receivedAt: Date.now(), sessionId: raw.type === 'session_replaced' ? raw.revoked_session_id || '' : raw.target_session_id || '',
  };
  // A late/repeated invite must never resurrect a cancelled call.
  if (previous?.type === 'call_cancel' && record.type === 'incoming_call') record = previous;
  await savePush(record).catch(() => undefined);

  const clients = await appClients().catch(() => []);
  const visibleClient = visibleAppClient(clients);
  const policy = { record, raw, ownerName: owner?.name, hasVisibleClient: Boolean(visibleClient), maxActions: notificationMaxActions(), userAgent: navigator.userAgent, requiresVisibleNotification: apple };
  let decision = decidePushNotification(policy);
  if (!decision.show && decision.openVisibleClient && visibleClient) {
    const sent = notifyClient(visibleClient, { type: 'open-call', accountId: id, callId });
    decision = sent ? { ...decision, openVisibleClient: false }
      : decidePushNotification({ ...policy, hasVisibleClient: false });
  }
  if (decision.closeExisting) await closeNotifications(key);
  if (decision.show) {
    const options: NotificationOptionsWithActions = {
      body: decision.body || owner?.name || 'TiniTalk',
      tag: key, icon: new URL('icon-192.png', base).href,
      data: { accountId: id, callId, sessionId: owner?.sessionId, ...(decision.defaultAction ? { defaultAction: decision.defaultAction } : {}) },
    };
    if (decision.actions?.length) options.actions = decision.actions;
    if (decision.silent !== undefined) options.silent = decision.silent;
    if (apple) {
      options.navigate = deepLink(base, id, callId);
      // Retries replace the same call's card without sounding twice.
      if ((await sw.registration.getNotifications({ tag: key }).catch(() => [])).length) options.silent = true;
    }
    await sw.registration.showNotification(decision.title || 'TiniTalk', options);
  }
  if (decision.openVisibleClient && visibleClient) notifyClient(visibleClient, { type: 'open-call', accountId: id, callId });
  for (const client of clients) notifyClient(client, { type: 'push-updated', accountId: id, callId });
  await prunePushes().catch(() => undefined);
}

function pushData(event: PushEvent): Record<string, string> {
  try {
    // WebKit's declarative PushEvent has event.data === null. The same JSON
    // arrives via event.data on engines without declarative parsing.
    const proposed = (event as DeclarativePushEvent).notification;
    let value = proposed ? proposed.data?.tinitalk : event.data?.json();
    if (value?.web_push === 8030) value = value.notification?.data?.tinitalk;
    if (!value || typeof value !== 'object' || Array.isArray(value)) return {};
    return Object.fromEntries(Object.entries(value).filter((entry): entry is [string, string] => typeof entry[1] === 'string'));
  } catch { return {}; }
}

async function appClients(): Promise<WindowClient[]> {
  const clients = await sw.clients.matchAll({ type: 'window', includeUncontrolled: true });
  const candidates = (clients as WindowClient[]).filter(client => client.url.startsWith(base) && !isInstallationPageURL(client.url));
  if (!navigator.locks) return candidates;
  // Only the lock owner starts the app; duplicate tabs cannot handle calls.
  // LockInfo.clientId is the service worker Client.id.
  const { held = [] } = await navigator.locks.query();
  const owner = held.find(lock => lock.name === 'tinitalk-pwa-client')?.clientId;
  return candidates.filter(client => client.id === owner);
}

function visibleAppClient(clients: WindowClient[]): WindowClient | undefined {
  return clients.find(client => client.focused && client.visibilityState === 'visible')
    ?? clients.find(client => client.visibilityState === 'visible')
    ?? clients.find(client => client.focused);
}

function notifyClient(client: WindowClient, message: object): boolean {
  // A window may close after matchAll(); let the caller fall back to a notification.
  try { client.postMessage(message); return true; } catch { return false; }
}

async function closeNotifications(tag: string): Promise<void> {
  const notifications = await sw.registration.getNotifications({ tag }).catch(() => []);
  for (const notification of notifications) notification.close();
}

function notificationMaxActions(): number {
  const notification = typeof Notification === 'undefined' ? undefined : Notification as NotificationConstructorWithMaxActions;
  return typeof notification?.maxActions === 'number' ? notification.maxActions : 0;
}

function notificationCallAction(value: string): 'answer' | 'reject' | undefined {
  return value === 'answer' || value === 'reject' ? value : undefined;
}

async function rejectCall(data: unknown): Promise<void> {
  const id = accountFromScope(sw.registration.scope);
  if (!id || !data || typeof data !== 'object' || !('callId' in data) || typeof data.callId !== 'string' || !data.callId) return;
  const callId = data.callId;
  const owner = await account(id);
  const record = await readPush(callKey(id, callId));
  if (!owner || !record || !canOpenIncoming(record, owner)) return;
  if ('sessionId' in data && data.sessionId && data.sessionId !== owner.sessionId) return;
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      await api<void>(owner, `/api/calls/${encodeURIComponent(callId)}/reject`, 'POST');
      break;
    } catch (error) {
      // Retry a lost response once: the endpoint is idempotent. Never open the
      // app or create another notification, including on an offline device.
      if (attempt === 1 || Date.now() >= record.expiresAt || (error instanceof APIError && error.status < 500)) return;
    }
  }
  await savePush({ ...record, type: 'call_cancel', receivedAt: Date.now() });
  await closeNotifications(record.id);
  for (const client of await appClients()) client.postMessage({ type: 'push-updated', accountId: id, callId });
}

async function openCall(data: unknown, action?: 'answer' | 'reject'): Promise<void> {
  const id = accountFromScope(sw.registration.scope);
  if (!id) return;
  const callId = data && typeof data === 'object' && 'callId' in data && typeof data.callId === 'string' ? data.callId : '';
  const target = deepLink(base, id, callId, action);
  // The page belongs to the shell registration, not this account's push scope.
  const clients = await appClients();
  for (const client of clients) {
    try { await client.focus(); client.postMessage({ type: 'open-call', accountId: id, callId, action }); return; } catch { /* try opening a window */ }
  }
  await sw.clients.openWindow(target);
}
