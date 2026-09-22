import { t } from './i18n';
import { OperationError } from './userErrors';
import type { Account } from './model';

export type AuthErrorCode = 'invalid_credentials' | 'temporary_password_expired' | 'temporary_password_locked'
  | 'password_retry_later' | 'invalid_password' | 'auth_busy';

export class AuthError extends Error {
  constructor(public status: number, public code: AuthErrorCode, public retryAfterSeconds?: number) {
    super(authErrorMessage(code, retryAfterSeconds));
  }
}

type Health = { service?: string; status?: string; features?: string[] };
type LoginResponse = { token?: string; password_required: boolean };

const requestOptions = (body?: unknown): RequestInit => ({
  method: body === undefined ? 'GET' : 'POST',
  credentials: 'omit', cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(12000),
  headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
  body: body === undefined ? undefined : JSON.stringify(body),
});

async function request(url: URL, options: RequestInit): Promise<Response> {
  return fetch(url, options).catch(error => { throw new OperationError('network', error); });
}

async function health(server: string): Promise<Health> {
  const response = await request(new URL('/healthz', server), requestOptions());
  if (!response.ok) throw new AuthError(response.status, 'auth_busy');
  const result = await response.json().catch(() => { throw new AuthError(503, 'auth_busy'); }) as Health;
  if (result.service !== 'tinitalk' || result.status !== 'ok') throw new Error(t('text_no_tinitalk_server_at_this_address_284'));
  if (!result.features?.includes('browser_v1')) throw new Error(t('web_update_this_server_to_connect_the_web_app_4'));
  return result;
}

function retryAfter(response: Response, payload: { retry_after?: unknown }): number | undefined {
  const body = Number(payload.retry_after);
  if (Number.isFinite(body) && body > 0) return Math.min(3600, Math.ceil(body));
  const header = Number(response.headers.get('Retry-After'));
  return Number.isFinite(header) && header > 0 ? Math.min(3600, Math.ceil(header)) : undefined;
}

async function authJSON<T>(server: string, path: string, body: unknown): Promise<T> {
  const response = await request(new URL(path, server), requestOptions(body));
  if (!response.ok) {
    const payload = await response.json().catch(() => ({})) as { error?: unknown; retry_after?: unknown };
    const code = isAuthErrorCode(payload.error) ? payload.error : 'auth_busy';
    throw new AuthError(response.status, code, retryAfter(response, payload));
  }
  // Receiving success headers is not enough: a password mutation may already
  // have revoked the old token even when its replacement cannot be read.
  return await response.json().catch(error => { throw new OperationError('network', error); }) as T;
}

function isAuthErrorCode(value: unknown): value is AuthErrorCode {
  return value === 'invalid_credentials' || value === 'temporary_password_expired'
    || value === 'temporary_password_locked' || value === 'password_retry_later'
    || value === 'invalid_password' || value === 'auth_busy';
}

export async function authenticate(server: string, login: string, password: string): Promise<
  { token: string; passwordRequired: false; passwordAuth: boolean }
  | { passwordRequired: true; passwordAuth: true }
> {
  const passwordAuth = (await health(server)).features?.includes('password_auth_v1') === true;
  if (!passwordAuth) return { token: password.trim(), passwordRequired: false, passwordAuth: false };
  const result = await authJSON<LoginResponse>(server, '/api/auth/login', { login, password });
  if (result.password_required) return { passwordRequired: true, passwordAuth: true };
  if (!result.token) throw new AuthError(503, 'auth_busy');
  return { token: result.token, passwordRequired: false, passwordAuth: true };
}

export async function changePassword(server: string, login: string, password: string, newPassword: string): Promise<{ token: string }> {
  const result = await authJSON<LoginResponse>(server, '/api/auth/password', { login, password, new_password: newPassword });
  if (typeof result?.token !== 'string' || !result.token.trim() || result.password_required) {
    throw new OperationError('network', new Error('Invalid password response'));
  }
  return { token: result.token };
}

export async function logout(account: Account): Promise<void> {
  let supported = account.passwordAuth === true;
  if (!supported) supported = (await health(account.server)).features?.includes('password_auth_v1') === true;
  if (!supported) return;
  const auth = btoa(String.fromCharCode(...new TextEncoder().encode(`${account.login}:${account.token}`)));
  const response = await request(new URL('/api/auth/logout', account.server), {
    method: 'POST', credentials: 'omit', cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(12000),
    headers: {
      Authorization: `Basic ${auth}`,
      'X-TiniTalk-Device-ID': account.deviceId,
      'X-TiniTalk-Session-ID': account.sessionId,
    }, body: undefined,
  });
  if (!response.ok && response.status !== 401) {
    const payload = await response.json().catch(() => ({})) as { error?: unknown; retry_after?: unknown };
    throw new AuthError(response.status, isAuthErrorCode(payload.error) ? payload.error : 'auth_busy', retryAfter(response, payload));
  }
}

export function personalPasswordError(password: string): string | undefined {
  const length = Array.from(password).length;
  if (length < 8) return t('web_the_password_must_contain_at_least_8_characters_81');
  if (length > 128) return t('web_the_password_must_contain_no_more_than_128_characters_82');
  return undefined;
}

export function authErrorMessage(code: AuthErrorCode, retryAfterSeconds?: number): string {
  switch (code) {
    case 'invalid_credentials': return t('text_incorrect_username_or_password_21');
    case 'temporary_password_expired': return t('text_password_expired_ask_your_administrator_for_a_new_one_311');
    case 'temporary_password_locked': return t('text_too_many_incorrect_attempts_ask_your_administrator_for_a_new_pass_312');
    case 'password_retry_later': return t('web_too_many_attempts_try_again_in_value_seconds_83', retryAfterSeconds ?? 1);
    case 'invalid_password': return t('text_password_must_contain_8_to_128_characters_309');
    case 'auth_busy': return t('text_the_server_is_busy_try_again_later_315');
  }
}
