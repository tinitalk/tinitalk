import { t, type Message } from './i18n';
import { OperationError } from './userErrors';
import { api, APIError } from './api';
import type { Account, SignalEvent } from './model';

type Resume = { id: string; seq: number } | null;
export class SignalError extends Error {
  constructor(message: string, readonly code?: string, readonly callId?: string) { super(message); }
}
export class SignalConnection {
  private socket?: WebSocket;
  private opening?: Promise<void>;
  private stopped = false;
  private timer?: ReturnType<typeof setTimeout>;
  private attempt = 0;
  private pending = new Map<string, { event: SignalEvent; expires: number }>();
  private delivery = Promise.resolve();
  private foregroundCallNotifications = false;
  private confirmations = new Map<string, { resolve: () => void; reject: (error: Error) => void; timer: ReturnType<typeof setTimeout>; callId: string }>();
  constructor(public account: Account, private receive: (event: SignalEvent) => Promise<void>, private changed: (status: Message) => void, private failed: (error: Error, callId?: string) => void, private resume: () => Resume, private acknowledged: (event: SignalEvent) => void = () => undefined) {}
  get connected(): boolean { return this.socket?.readyState === WebSocket.OPEN; }
  connect(): Promise<void> {
    if (this.connected) return Promise.resolve();
    if (this.opening) return this.opening;
    if (this.stopped) return Promise.reject(new Error(t('web_disconnected_sign_in_again_126')));
    clearTimeout(this.timer);
    this.timer = undefined;
    this.opening = this.open().finally(() => { this.opening = undefined; });
    return this.opening;
  }
  private async open(): Promise<void> {
    this.changed('web_connecting_38');
    try {
      const result = await api<{ ticket: string; foreground_call_notifications?: boolean; contact_changes?: boolean }>(this.account, '/api/browser/socket-ticket', 'POST', {});
      if (this.stopped) return;
      const url = new URL('/api/browser/socket', this.account.server);
      url.searchParams.set('call_waiting', '1');
      if (result.contact_changes) url.searchParams.set('contact_changes', '1');
      this.foregroundCallNotifications = result.foreground_call_notifications === true;
      if (this.foregroundCallNotifications) url.searchParams.set('foreground_call_notifications', '1');
      url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
      const socket = new WebSocket(url, ['tinitalk.browser.v1', `ticket.${result.ticket}`]);
      this.socket = socket;
      await new Promise<void>((resolve, reject) => {
        const timeout = setTimeout(() => { socket.close(); reject(new OperationError('network', new Error(t('web_server_is_not_responding_127')))); }, 12000);
        socket.onopen = () => {
          clearTimeout(timeout);
          if (this.stopped || socket !== this.socket) { socket.close(); resolve(); return; }
          this.attempt = 0;
          this.changed('web_online_6');
          for (const [id, pending] of this.pending) {
            if (pending.expires <= Date.now()) this.pending.delete(id);
            else socket.send(JSON.stringify(pending.event));
          }
          const active = this.resume();
          if (active) this.send(active.id, 'call.resume', { last_seq: active.seq });
          resolve();
        };
        socket.onerror = () => { clearTimeout(timeout); reject(new OperationError('network', new Error(t('text_could_not_connect_to_the_server_27')))); };
        socket.onclose = () => {
          clearTimeout(timeout); reject(new OperationError('network', new Error(t('web_connection_closed_128'))));
          if (socket !== this.socket) return;
          this.socket = undefined;
          this.changed(this.stopped ? 'web_signed_out_129' : 'web_offline_7');
          this.schedule();
        };
        socket.onmessage = event => {
          if (socket !== this.socket || this.stopped) return;
          let frame: Record<string, unknown>;
          try { frame = JSON.parse(event.data); } catch { return; }
          if (typeof frame.ack === 'string') {
            this.settle(frame.ack);
            const pending = this.pending.get(frame.ack);
            this.pending.delete(frame.ack);
            if (pending) this.acknowledged(pending.event);
            return;
          }
          if (typeof frame.error === 'string') {
            if (typeof frame.event_id === 'string') this.settle(frame.event_id, new SignalError(String(frame.error)));
            if (typeof frame.event_id === 'string') this.pending.delete(frame.event_id);
            const code = typeof frame.code === 'string' ? frame.code : undefined;
            const callId = typeof frame.call_id === 'string' ? frame.call_id : undefined;
            this.failed(new SignalError(code === 'busy' ? t('web_the_person_is_busy_130') : frame.error, code, callId), callId);
            return;
          }
          if (typeof frame.type !== 'string' || typeof frame.call_id !== 'string' || !frame.payload || typeof frame.payload !== 'object') return;
          this.delivery = this.delivery.then(() => this.receive(frame as unknown as SignalEvent)).catch(error => this.failed(error instanceof Error ? error : new Error(String(error)), String(frame.call_id)));
        };
      });
    } catch (error) {
      if (error instanceof APIError && error.status === 401) { this.stopped = true; this.changed('web_sign_in_again_37'); this.failed(error); }
      else { this.changed('web_offline_7'); this.schedule(); }
      throw error;
    }
  }
  private schedule(): void {
    if (this.stopped || this.timer) return;
    this.timer = setTimeout(() => { this.timer = undefined; void this.connect().catch(() => undefined); }, Math.min(15000, 1000 * 2 ** Math.min(this.attempt++, 4)));
  }
  send(callId: string, type: string, payload: Record<string, unknown> = {}): string {
    const event: SignalEvent = { id: crypto.randomUUID(), call_id: callId, type, sent_at: Date.now(), payload };
    if (this.pending.size >= 256) throw new Error(t('web_too_many_unacknowledged_events_131'));
    this.pending.set(event.id, { event, expires: Date.now() + 20000 });
    if (this.connected) this.socket!.send(JSON.stringify(event));
    else void this.connect().catch(() => undefined);
    return event.id;
  }
  sendCallVisibility(callId: string, visible: boolean): boolean {
    if (!this.foregroundCallNotifications || !this.connected) return false;
    // Never queue/replay a visibility claim or reconnect just to send one.
    try {
      this.socket!.send(JSON.stringify({ id: crypto.randomUUID(), call_id: callId,
        type: 'call.visibility', sent_at: Date.now(), payload: { visible } }));
      return true;
    } catch { return false; }
  }
  sendConfirmed(callId: string, type: string, payload: Record<string, unknown> = {}): Promise<void> {
    return new Promise((resolve, reject) => {
      const id = this.send(callId, type, payload);
      const timer = setTimeout(() => {
        // A failed switch must not be replayed later against the previous call.
        this.pending.delete(id);
        this.settle(id, new Error('Signaling acknowledgement timed out'));
      }, 8_000);
      this.confirmations.set(id, { resolve, reject, timer, callId });
    });
  }
  private settle(id: string, error?: Error): void {
    const pending = this.confirmations.get(id);
    if (!pending) return;
    clearTimeout(pending.timer);
    this.confirmations.delete(id);
    if (error) pending.reject(error); else pending.resolve();
  }
  clearCall(callId: string): void { for (const [id, value] of this.pending) if (value.event.call_id === callId) this.pending.delete(id); }
  stop(): void {
    this.stopped = true; clearTimeout(this.timer); this.timer = undefined; this.socket?.close(); this.socket = undefined; this.pending.clear();
    for (const id of this.confirmations.keys()) this.settle(id, new Error('Signaling connection closed'));
  }
}
