import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthError, authenticate, changePassword, logout, personalPasswordError } from './auth';
import type { Account } from './model';
import { OperationError } from './userErrors';

afterEach(() => vi.unstubAllGlobals());

describe('authenticate', () => {
  it('uses the password endpoint only when health advertises password_auth_v1', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(Response.json({ service: 'tinitalk', status: 'ok', features: ['browser_v1', 'password_auth_v1'] }))
      .mockResolvedValueOnce(Response.json({ token: 'issued-token', password_required: false }));
    vi.stubGlobal('fetch', fetch);

    await expect(authenticate('https://family.example', 'alice', '  personal password  ')).resolves.toEqual({
      token: 'issued-token', passwordRequired: false, passwordAuth: true,
    });
    expect(fetch).toHaveBeenNthCalledWith(2, new URL('https://family.example/api/auth/login'), expect.objectContaining({
      method: 'POST', body: JSON.stringify({ login: 'alice', password: '  personal password  ' }),
    }));
  });

  it('treats the entered credential as a legacy token only when health lacks the feature', async () => {
    const fetch = vi.fn().mockResolvedValue(Response.json({ service: 'tinitalk', status: 'ok', features: ['browser_v1'] }));
    vi.stubGlobal('fetch', fetch);

    await expect(authenticate('https://old.example', 'alice', 'old-token')).resolves.toEqual({
      token: 'old-token', passwordRequired: false, passwordAuth: false,
    });
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it('does not fall back to a legacy token when health or new authentication fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')));
    await expect(authenticate('https://family.example', 'alice', 'secret')).rejects.toMatchObject({ context: 'network' });

    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(Response.json({ service: 'tinitalk', status: 'ok', features: ['browser_v1', 'password_auth_v1'] }))
      .mockResolvedValueOnce(Response.json({ error: 'invalid_credentials' }, { status: 401 })));
    await expect(authenticate('https://family.example', 'alice', 'secret')).rejects.toMatchObject({
      status: 401, code: 'invalid_credentials',
    });
  });

  it('returns setup state without inventing an access token', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(Response.json({ service: 'tinitalk', status: 'ok', features: ['browser_v1', 'password_auth_v1'] }))
      .mockResolvedValueOnce(Response.json({ password_required: true })));
    await expect(authenticate('https://family.example', 'alice', '1234 5678')).resolves.toEqual({
      passwordRequired: true, passwordAuth: true,
    });
  });

  it('reads retry timing from the JSON response and Retry-After header', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(Response.json({ service: 'tinitalk', status: 'ok', features: ['browser_v1', 'password_auth_v1'] }))
      .mockResolvedValueOnce(Response.json({ error: 'password_retry_later', retry_after: 18 }, {
        status: 429, headers: { 'Retry-After': '21' },
      })));
    const result = authenticate('https://family.example', 'alice', 'secret');
    await expect(result).rejects.toBeInstanceOf(AuthError);
    await expect(result).rejects.toMatchObject({ code: 'password_retry_later', retryAfterSeconds: 18 });
  });

  it('reports an unreadable successful authentication response as a lost response', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(Response.json({ service: 'tinitalk', status: 'ok', features: ['browser_v1', 'password_auth_v1'] }))
      .mockResolvedValueOnce(new Response('not-json', { status: 200 })));
    await expect(authenticate('https://family.example', 'alice', 'secret')).rejects.toBeInstanceOf(OperationError);
  });

  it('does not send credentials to a wrong-service or incompatible health response', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(Response.json({ service: 'not-tinitalk', status: 'ok', features: [] }));
    vi.stubGlobal('fetch', fetch);
    await expect(authenticate('https://wrong.example', 'alice', 'secret')).rejects.toThrow('TiniTalk');
    expect(fetch).toHaveBeenCalledTimes(1);

    fetch.mockReset().mockResolvedValueOnce(Response.json({ service: 'tinitalk', status: 'ok', features: [] }));
    await expect(authenticate('https://old.example', 'alice', 'old-token')).rejects.toThrow('обновить');
    expect(fetch).toHaveBeenCalledTimes(1);
  });
});

it('preserves all password whitespace when setting a personal password', async () => {
  const fetch = vi.fn().mockResolvedValue(Response.json({ token: 'replacement', password_required: false }));
  vi.stubGlobal('fetch', fetch);
  await expect(changePassword('https://family.example', 'alice', '1234 5678', '  long personal pass  ')).resolves.toEqual({ token: 'replacement' });
  expect(fetch).toHaveBeenCalledWith(new URL('https://family.example/api/auth/password'), expect.objectContaining({
    body: JSON.stringify({ login: 'alice', password: '1234 5678', new_password: '  long personal pass  ' }),
  }));
});

describe('password response recovery', () => {
  it('requires sign-in when an HTTP 200 body fails after the response headers arrive', async () => {
    const failure = new TypeError('connection reset while receiving the token');
    const response = new Response(new ReadableStream<Uint8Array>({
      start(controller) { controller.enqueue(new TextEncoder().encode('{"token":"replacement')); },
      pull(controller) { controller.error(failure); },
    }), { status: 200 });
    const fetch = vi.fn().mockResolvedValue(response);
    vi.stubGlobal('fetch', fetch);

    const result = changePassword('https://family.example', 'alice', 'old-token', 'new-password');

    await expect(result).rejects.toBeInstanceOf(OperationError);
    await expect(result).rejects.toMatchObject({ context: 'network', cause: failure });
    expect(fetch).toHaveBeenCalledOnce();
  });

  it.each(['{"token":"truncated', 'not-json', ''])('requires sign-in for an incomplete or malformed HTTP 200 body: %j', async body => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body, { status: 200 })));
    await expect(changePassword('https://family.example', 'alice', 'old-token', 'new-password')).rejects.toBeInstanceOf(OperationError);
  });

  it.each([{}, null, { token: '' }, { token: ' ' }, { token: 42 }, { password_required: true },
    { token: 'replacement', password_required: true }])('requires sign-in when a successful mutation has no usable token: %j', async body => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(body)));
    await expect(changePassword('https://family.example', 'alice', 'old-token', 'new-password')).rejects.toBeInstanceOf(OperationError);
  });

  it.each([
    [401, 'invalid_credentials'], [403, 'temporary_password_locked'], [429, 'password_retry_later'], [503, 'auth_busy'],
  ] as const)('keeps an explicit HTTP %s rejection as an authentication error', async (status, code) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ error: code, retry_after: 21 }, { status })));
    const result = changePassword('https://family.example', 'alice', 'old-token', 'new-password');
    await expect(result).rejects.toBeInstanceOf(AuthError);
    await expect(result).rejects.toMatchObject({ status, code, retryAfterSeconds: 21 });
  });

  it.each([401, 403, 429, 503])('keeps an HTTP %s rejection explicit even if its body is lost', async status => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(new ReadableStream<Uint8Array>({
      pull(controller) { controller.error(new TypeError('connection reset')); },
    }), { status, headers: { 'Retry-After': '21' } })));
    const result = changePassword('https://family.example', 'alice', 'old-token', 'new-password');
    await expect(result).rejects.toBeInstanceOf(AuthError);
    await expect(result).rejects.toMatchObject({ status, code: 'auth_busy', retryAfterSeconds: 21 });
  });
});

it('counts Unicode code points without trimming personal passwords', () => {
  expect(personalPasswordError('😀'.repeat(7))).toBeTruthy();
  expect(personalPasswordError('😀'.repeat(8))).toBeUndefined();
  expect(personalPasswordError(' '.repeat(8))).toBeUndefined();
  expect(personalPasswordError('a'.repeat(129))).toBeTruthy();
});

it('logs out the presented session with Basic authentication', async () => {
  const account: Account = { id: 'a', server: 'https://family.example', login: 'алиса', token: 'token', name: 'Alice', deviceId: 'phone', sessionId: 'session', passwordAuth: true };
  const fetch = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
  vi.stubGlobal('fetch', fetch);
  await logout(account);
  expect(fetch).toHaveBeenCalledWith(new URL('https://family.example/api/auth/logout'), expect.objectContaining({
    method: 'POST', body: undefined, headers: expect.objectContaining({
      Authorization: `Basic ${btoa(String.fromCharCode(...new TextEncoder().encode('алиса:token')))}`,
      'X-TiniTalk-Device-ID': 'phone',
      'X-TiniTalk-Session-ID': 'session',
    }),
  }));
});
