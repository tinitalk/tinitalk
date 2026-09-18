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
  if (result.service !== 'tinitalk' || result.status !== 'ok') throw new Error('По этому адресу нет сервера TiniTalk.');
  if (!result.features?.includes('browser_v1')) throw new Error('Этот сервер нужно обновить для подключения PWA.');
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
  if (length < 8) return 'Пароль должен содержать не меньше 8 символов.';
  if (length > 128) return 'Пароль должен содержать не больше 128 символов.';
  return undefined;
}

export function authErrorMessage(code: AuthErrorCode, retryAfterSeconds?: number): string {
  switch (code) {
    case 'invalid_credentials': return 'Неверный логин или пароль.';
    case 'temporary_password_expired': return 'Срок действия пароля истёк. Попросите администратора выдать новый.';
    case 'temporary_password_locked': return 'Слишком много неверных попыток. Попросите администратора выдать новый пароль.';
    case 'password_retry_later': return `Слишком много попыток. Повторите через ${retryAfterSeconds ?? 1} сек.`;
    case 'invalid_password': return 'Пароль должен содержать от 8 до 128 символов.';
    case 'auth_busy': return 'Сервер занят. Попробуйте чуть позже.';
  }
}
