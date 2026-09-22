import { t } from './i18n';
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
    // Password resets revoke the token itself, so there may be no session
    // reason header. Both kinds of 401 require an explicit new login.
    if (response.status === 401) sessionReplacedHandler?.(account, sessionId);
    const message = replaced ? t('web_this_account_is_open_on_another_device_sign_in_again_0')
      : response.status === 401 ? t('web_you_have_been_signed_out_sign_in_again_1')
        : t('web_server_returned_value_value_2', response.status, (await response.text()).slice(0, 180));
    throw new APIError(response.status, message, replaced);
  }
  if (response.status === 204) return undefined as T;
  return await response.json() as T;
}
