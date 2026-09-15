import { OperationError } from './userErrors';
import type { Account } from './model';

export class APIError extends Error {
  constructor(public status: number, message: string, public replaced = false) { super(message); }
}
let sessionReplacedHandler: ((account: Account, sessionId: string) => void) | undefined;
export function setSessionReplacedHandler(handler: typeof sessionReplacedHandler): void {
  sessionReplacedHandler = handler;
}
export async function api<T>(account: Account, path: string, method = 'GET', body?: unknown): Promise<T> {
  const sessionId = account.sessionId;
  const auth = btoa(String.fromCharCode(...new TextEncoder().encode(`${account.login}:${account.token}`)));
  const response = await fetch(new URL(path, account.server), {
    method, credentials: 'omit', cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(12000),
    headers: { Authorization: `Basic ${auth}`, 'X-TiniTalk-Session-ID': sessionId, 'X-TiniTalk-Device-ID': account.deviceId, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    body: body === undefined ? undefined : JSON.stringify(body),
  }).catch(error => { throw new OperationError('network', error); });
  if (!response.ok) {
    const replaced = response.status === 401 && response.headers.get('X-TiniTalk-Auth-Reason') === 'session_replaced';
    if (replaced) sessionReplacedHandler?.(account, sessionId);
    const message = replaced ? 'Учётка открыта на другом устройстве. Войдите снова.'
      : response.status === 401 ? 'Проверьте логин и ключ доступа.'
        : `Сервер ответил ${response.status}: ${(await response.text()).slice(0, 180)}`;
    throw new APIError(response.status, message, replaced);
  }
  if (response.status === 204) return undefined as T;
  return await response.json() as T;
}
