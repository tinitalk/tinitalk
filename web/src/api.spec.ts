import { afterEach, expect, it, vi } from 'vitest';
import { api, APIError, setSessionReplacedHandler } from './api';
import { OperationError } from './userErrors';
import type { Account } from './model';

const account = (): Account => ({ id: 'family', server: 'https://family.example', login: 'alice', token: 'test', name: 'Alice', deviceId: 'phone', sessionId: 'old-session' });
afterEach(() => { setSessionReplacedHandler(undefined); vi.unstubAllGlobals(); });

it('reports a replaced session from any authenticated API request', async () => {
  const owner = account();
  const replaced = vi.fn();
  setSessionReplacedHandler(replaced);
  vi.stubGlobal('fetch', async () => new Response('', { status: 401, headers: { 'X-TiniTalk-Auth-Reason': 'session_replaced' } }));
  await expect(api(owner, '/api/contacts')).rejects.toMatchObject({ status: 401, replaced: true });
  expect(replaced).toHaveBeenCalledExactlyOnceWith(owner, 'old-session');
});

it('does not treat network failures or ordinary authentication errors as a replacement', async () => {
  const replaced = vi.fn();
  setSessionReplacedHandler(replaced);
  const offline = new TypeError('offline');
  vi.stubGlobal('fetch', vi.fn().mockRejectedValueOnce(offline)
    .mockResolvedValueOnce(new Response('', { status: 401 }))
    .mockResolvedValueOnce(new Response('', { status: 503, headers: { 'X-TiniTalk-Auth-Reason': 'session_replaced' } })));
  const failedRequest = api(account(), '/api/contacts');
  await expect(failedRequest).rejects.toBeInstanceOf(OperationError);
  await expect(failedRequest).rejects.toMatchObject({ context: 'network', cause: offline });
  await expect(api(account(), '/api/contacts')).rejects.toBeInstanceOf(APIError);
  await expect(api(account(), '/api/contacts')).rejects.toBeInstanceOf(APIError);
  expect(replaced).not.toHaveBeenCalled();
});

it('identifies the session that sent a delayed request, not a newer login', async () => {
  const owner = account();
  const replaced = vi.fn();
  setSessionReplacedHandler(replaced);
  let complete!: (response: Response) => void;
  vi.stubGlobal('fetch', () => new Promise<Response>(resolve => { complete = resolve; }));
  const result = api(owner, '/api/contacts');
  owner.sessionId = 'new-session';
  complete(new Response('', { status: 401, headers: { 'X-TiniTalk-Auth-Reason': 'session_replaced' } }));
  await expect(result).rejects.toBeInstanceOf(APIError);
  expect(replaced).toHaveBeenCalledExactlyOnceWith(owner, 'old-session');
});
