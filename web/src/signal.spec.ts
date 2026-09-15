import { afterEach, expect, it, vi } from 'vitest';
import { SignalConnection } from './signal';
import { api } from './api';
import type { Account } from './model';

class FakeSocket {
  static OPEN = 1;
  static instances: FakeSocket[] = [];
  readyState = 0;
  sent: string[] = [];
  onmessage?: (event: { data: string }) => void;
  onopen?: () => void;
  onclose?: () => void;
  constructor(public url: URL) { FakeSocket.instances.push(this); }
  send(raw: string): void { this.sent.push(raw); }
  open(): void { this.readyState = 1; this.onopen?.(); }
  close(): void { this.readyState = 3; this.onclose?.(); }
}

it('negotiates visibility support and never replays stale visible acknowledgements', async () => {
  vi.useFakeTimers();
  vi.stubGlobal('WebSocket', FakeSocket);
  vi.mocked(api).mockResolvedValue({ ticket: 'ticket', foreground_call_notifications: true });
  const account: Account = { id: 'a', server: 'https://family.example', login: 'alice', token: 'test', name: 'Alice', deviceId: 'a', sessionId: 's' };
  const connection = new SignalConnection(account, async () => undefined, vi.fn(), vi.fn(), () => null);
  expect(connection.sendCallVisibility('call-1', true)).toBe(false);
  expect(api).not.toHaveBeenCalled();
  const opening = connection.connect();
  await Promise.resolve();
  let socket = FakeSocket.instances.at(-1)!;
  expect(socket.url.searchParams.get('foreground_call_notifications')).toBe('1');
  socket.open();
  await opening;
  expect(connection.sendCallVisibility('call-1', true)).toBe(true);
  expect(JSON.parse(socket.sent[0])).toMatchObject({ call_id: 'call-1', type: 'call.visibility', payload: { visible: true } });
  socket.close();
  expect(connection.sendCallVisibility('call-1', false)).toBe(false);
  const reconnect = connection.connect();
  await Promise.resolve();
  socket = FakeSocket.instances.at(-1)!;
  socket.open();
  await reconnect;
  expect(socket.sent).toEqual([]);
  connection.stop();
});

it('does not send visibility messages to an older family server', async () => {
  vi.useFakeTimers();
  vi.stubGlobal('WebSocket', FakeSocket);
  vi.mocked(api).mockResolvedValue({ ticket: 'legacy' });
  const account: Account = { id: 'a', server: 'https://family.example', login: 'alice', token: 'test', name: 'Alice', deviceId: 'a', sessionId: 's' };
  const connection = new SignalConnection(account, async () => undefined, vi.fn(), vi.fn(), () => null);
  const opening = connection.connect();
  await Promise.resolve();
  const socket = FakeSocket.instances.at(-1)!;
  socket.open();
  await opening;
  expect(connection.sendCallVisibility('call-1', true)).toBe(false);
  expect(socket.sent).toEqual([]);
  expect(socket.url.search).toBe('');
  connection.stop();
});

vi.mock('./api', () => ({ api: vi.fn(), APIError: class extends Error {} }));
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); vi.clearAllMocks(); });

it('retries again after an online event interrupts the previous reconnect timer', async () => {
  vi.useFakeTimers();
  vi.mocked(api).mockRejectedValue(new Error('offline'));
  const account: Account = { id: 'a', server: 'https://family.example', login: 'alice', token: 'test', name: 'Alice', deviceId: 'a', sessionId: 's' };
  const connection = new SignalConnection(account, async () => undefined, vi.fn(), vi.fn(), () => null);
  await expect(connection.connect()).rejects.toThrow('offline');
  expect(api).toHaveBeenCalledTimes(1);
  // A manual online/visibility reconnect happens before the timer fires.
  await expect(connection.connect()).rejects.toThrow('offline');
  expect(api).toHaveBeenCalledTimes(2);
  await vi.advanceTimersByTimeAsync(2100);
  expect(api).toHaveBeenCalledTimes(3);
  connection.stop();
  expect(vi.getTimerCount()).toBe(0);
});


it('reports a terminal event only after its server acknowledgement, once', async () => {
  vi.useFakeTimers();
  vi.stubGlobal('WebSocket', FakeSocket);
  vi.mocked(api).mockResolvedValue({ ticket: 'ticket' });
  const account: Account = { id: 'a', server: 'https://family.example', login: 'alice', token: 'test', name: 'Alice', deviceId: 'a', sessionId: 's' };
  const acknowledged = vi.fn();
  const connection = new SignalConnection(account, async () => undefined, vi.fn(), vi.fn(), () => null, acknowledged);
  const opening = connection.connect();
  await Promise.resolve();
  const socket = FakeSocket.instances.at(-1)!;
  socket.open();
  await opening;
  connection.clearCall('call-1');
  const id = connection.send('call-1', 'call.end');
  expect(acknowledged).not.toHaveBeenCalled();
  socket.onmessage?.({ data: JSON.stringify({ ack: id }) });
  expect(acknowledged).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ type: 'call.end', call_id: 'call-1' }));
  socket.onmessage?.({ data: JSON.stringify({ ack: id }) });
  expect(acknowledged).toHaveBeenCalledTimes(1);
  connection.stop();
});
