import { accountScope, base64Key, callKey, canOpenIncoming, type Account } from './model';
import { api, APIError } from './api';
import { readPush, saveAccount } from './storage';

const jobs = new Map<string, Promise<void>>();
let permissionRequest: Promise<NotificationPermission> | undefined;

export async function notificationCallState(account: Account, callId: string): Promise<'incoming' | 'ended' | 'ignore'> {
  const localState = async () => {
    const record = await readPush(callKey(account.id, callId));
    if (!record || record.accountId !== account.id || (record.sessionId && record.sessionId !== account.sessionId)) return 'ignore';
    if (record.type === 'call_cancel') return 'ended';
    if (record.type !== 'incoming_call') return 'ignore';
    return canOpenIncoming(record, account) ? 'incoming' : 'ended';
  };
  const state = await localState();
  if (state !== 'incoming') return state;
  if (!await isActiveNotificationCall(account, callId)) return 'ended';
  // Cancellation may have arrived while the server request was in flight.
  return localState();
}

export async function isActiveNotificationCall(account: Account, callId: string): Promise<boolean> {
  if (!callId) return false;
  try {
    const active = await api<{ call_id: string } | undefined>(account, '/api/active-call');
    return active?.call_id === callId;
  } catch (error) {
    // Older family servers only support call.resume, which still validates the
    // call before sending an invite. Authentication/network failures propagate.
    if (error instanceof APIError && error.status === 404) return true;
    throw error;
  }
}

export async function closeCallNotification(accountId: string, callId: string, base: string): Promise<void> {
  if (!callId || !('serviceWorker' in navigator)) return;
  const scope = accountScope(base, accountId);
  const registration = await navigator.serviceWorker.getRegistration(scope);
  if (registration?.scope !== scope) return;
  // Let the worker serialize the terminal record with incoming push delivery,
  // rather than racing two separate IndexedDB writers in the page and worker.
  registration.active?.postMessage({ type: 'call-handled', callId });
  for (const notification of await registration.getNotifications({ tag: callKey(accountId, callId) })) notification.close();
}
function serial(account: Account, operation: () => Promise<void>): Promise<void> {
  const job = (jobs.get(account.id) ?? Promise.resolve()).catch(() => undefined).then(operation);
  jobs.set(account.id, job);
  return job.finally(() => { if (jobs.get(account.id) === job) jobs.delete(account.id); });
}

export function pushSupport(): string | null {
  if (!isSecureContext) return 'Для уведомлений и микрофона нужен HTTPS.';
  if (!('serviceWorker' in navigator) || !('PushManager' in window) || !('Notification' in window)) return 'Уведомления недоступны. На iPhone добавьте сайт на экран «Домой» и откройте оттуда.';
  return null;
}
export async function requestPushPermission(): Promise<boolean> {
  if (pushSupport()) return false;
  if (Notification.permission !== 'default') return Notification.permission === 'granted';
  let request: Promise<NotificationPermission> | undefined;
  try {
    // Start synchronously from the user's click, before login/network awaits.
    request = permissionRequest ??= Notification.requestPermission();
    return await request === 'granted';
  } catch {
    return false;
  } finally {
    if (permissionRequest === request) permissionRequest = undefined;
  }
}
async function active(registration: ServiceWorkerRegistration): Promise<void> {
  // A previous active worker must not hide a replacement still installing.
  const worker = registration.installing ?? registration.waiting ?? registration.active;
  if (!worker) throw new Error('Обработчик недоступен');
  if (worker.state === 'activated') return;
  await new Promise<void>((resolve, reject) => {
    const finish = (error?: Error) => {
      clearTimeout(timeout);
      worker.removeEventListener('statechange', changed);
      if (error) reject(error); else resolve();
    };
    const changed = () => {
      if (worker.state === 'activated') finish();
      if (worker.state === 'redundant') finish(new Error('Не удалось установить обработчик'));
    };
    const timeout = setTimeout(() => finish(new Error('Не удалось запустить обработчик уведомлений.')), 12000);
    worker.addEventListener('statechange', changed);
    changed();
  });
}
export async function enablePush(account: Account, base: string, ask = true): Promise<void> {
  const unsupported = pushSupport(); if (unsupported) throw new Error(unsupported);
  // Call before the first await: permission comes directly from a button press.
  if (ask && !await requestPushPermission()) throw new Error('Уведомления не разрешены. Их можно включить в настройках сайта.');
  if (Notification.permission !== 'granted') throw new Error('Уведомления не разрешены.');
  await serial(account, () => configurePush(account, base));
}
export async function updatePushWorker(account: Account, base: string): Promise<ServiceWorkerRegistration> {
  const script = new URL('push-worker.js', base);
  // Change the script URL with its content, keeping the subscription's scope.
  if (import.meta.env.VITE_PUSH_WORKER_VERSION) script.searchParams.set('v', import.meta.env.VITE_PUSH_WORKER_VERSION);
  const registration = await navigator.serviceWorker.register(script, { scope: accountScope(base, account.id), updateViaCache: 'none' });
  await active(registration);
  return registration;
}
async function configurePush(account: Account, base: string): Promise<void> {
  const config = await api<{ vapid_public_key: string; config_id: string; declarative_web_push?: boolean }>(account, '/api/webpush-config');
  const registration = await updatePushWorker(account, base);
  let subscription = await registration.pushManager.getSubscription();
  const key = base64Key(config.vapid_public_key);
  const oldKey = subscription?.options.applicationServerKey;
  if (subscription && (!oldKey || new Uint8Array(oldKey).some((b,i) => b !== key[i]) || oldKey.byteLength !== key.byteLength)) {
    await subscription.unsubscribe(); subscription = null;
  }
  subscription ??= await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key });
  const raw = subscription.toJSON();
  // The installation's origin can differ from this account's family server.
  // Advertise fallback navigation only after the new worker is active and only
  // to supporting servers; old servers reject unknown subscription fields.
  const apple = raw.endpoint && new URL(raw.endpoint).hostname.endsWith('.push.apple.com');
  const webAppURL = new URL(base);
  webAppURL.hash = new URLSearchParams({ account: account.id }).toString();
  await api(account, '/api/device', 'PUT', { device_id: account.deviceId, config_id: config.config_id, webpush_subscription: {
    endpoint: raw.endpoint, keys: raw.keys, client_type: 'web',
    ...(apple && config.declarative_web_push ? { web_app_url: webAppURL.href } : {}),
  } });
  account.pushConfigId = config.config_id;
  await saveAccount(account);
}
export async function pushEnabled(account: Account, base: string): Promise<boolean> {
  if (pushSupport() || Notification.permission !== 'granted') return false;
  const registration = await navigator.serviceWorker.getRegistration(accountScope(base, account.id));
  return registration?.scope === accountScope(base, account.id) && Boolean(await registration.pushManager.getSubscription()) && Boolean(account.pushConfigId);
}
export async function disablePush(account: Account, base: string): Promise<void> {
  await serial(account, () => removePush(account, base));
}
async function removePush(account: Account, base: string): Promise<void> {
  if ('serviceWorker' in navigator) {
    const registration = await navigator.serviceWorker.getRegistration(accountScope(base, account.id));
    if (registration?.scope === accountScope(base, account.id)) {
      const subscription = await registration.pushManager.getSubscription();
      if (subscription) await subscription.unsubscribe();
      await registration.unregister();
    }
  }
  account.pushConfigId = undefined;
  await saveAccount(account);
}
