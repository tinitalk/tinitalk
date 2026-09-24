import type { Account, SignalEvent } from './model';

export type WaitingInvite = {
  account: Account;
  sessionId: string | undefined;
  event: SignalEvent;
  peer: string;
  deadline: number;
  originalDeadline: number;
  seq: number;
  confirmed: boolean;
  waiting: boolean;
  buffered: SignalEvent[];
};

/** Invitations only: no media objects and no implicit selection on cancellation. */
export class WaitingInvites {
  readonly entries = new Map<string, WaitingInvite>();
  private dismissed = new Map<string, number>();
  selected?: WaitingInvite;
  key(accountId: string, callId: string): string { return JSON.stringify([accountId, callId]); }
  get(accountId: string, callId: string): WaitingInvite | undefined { return this.entries.get(this.key(accountId, callId)); }
  add(account: Account, event: SignalEvent, peer: string, now = Date.now()): WaitingInvite | undefined {
    if (this.isDismissed(account, event.call_id, now)) return;
    const existing = this.get(account.id, event.call_id);
    if (existing) return existing.sessionId === account.sessionId ? existing : undefined;
    if (this.entries.size >= 8 || event.sent_at + 45_000 <= now) return;
    const invite: WaitingInvite = { account, sessionId: account.sessionId, event, peer,
      deadline: Math.min(now + 15_000, event.sent_at + 45_000), originalDeadline: event.sent_at + 45_000,
      seq: event.seq ?? 0, confirmed: false, waiting: true, buffered: [] };
    this.entries.set(this.key(account.id, event.call_id), invite);
    return invite;
  }
  acknowledge(invite: WaitingInvite, event: SignalEvent, now = Date.now()): boolean {
    if (!this.has(invite) || (event.seq ?? 0) <= invite.seq || typeof event.payload.waiting !== 'boolean' ||
        typeof event.payload.remaining_ms !== 'number') return false;
    const upper = invite.waiting && !event.payload.waiting ? invite.originalDeadline : invite.deadline;
    invite.deadline = Math.min(upper, now + Math.max(0, event.payload.remaining_ms));
    invite.waiting = event.payload.waiting;
    invite.seq = event.seq!;
    invite.confirmed = true;
    return true;
  }
  select(invite: WaitingInvite, now = Date.now()): boolean {
    if (this.selected || !this.has(invite) || !invite.confirmed || invite.deadline <= now) return false;
    this.selected = invite;
    return true;
  }
  has(invite: WaitingInvite): boolean {
    return this.get(invite.account.id, invite.event.call_id) === invite && invite.sessionId === invite.account.sessionId;
  }
  isDismissed(account: Account, callId: string, now = Date.now()): boolean {
    for (const [key, expires] of this.dismissed) if (expires <= now) this.dismissed.delete(key);
    return this.dismissed.has(JSON.stringify([account.id, account.sessionId, callId]));
  }
  remove(invite: WaitingInvite, terminal = true): void {
    if (this.get(invite.account.id, invite.event.call_id) !== invite) return;
    if (terminal) {
      this.dismissed.set(JSON.stringify([invite.account.id, invite.sessionId, invite.event.call_id]), Date.now() + 120_000);
      while (this.dismissed.size > 256) this.dismissed.delete(this.dismissed.keys().next().value!);
    }
    this.entries.delete(this.key(invite.account.id, invite.event.call_id));
    if (this.selected === invite) this.selected = undefined;
  }
}

/** Audio-thread loop bounded by the invitations' deadlines, even in a throttled tab. */
export class WaitingTone {
  private context?: AudioContext;
  private source?: AudioBufferSourceNode;
  private deadline = 0;
  private revision = 0;
  private sinkId = '';
  update(deadline: number, sinkId = ''): void {
    if (sinkId !== this.sinkId) { this.stop(); this.sinkId = sinkId; }
    this.deadline = deadline;
    if (deadline <= Date.now()) { this.stop(); return; }
    if (this.source && this.context) {
      this.source.stop(this.context.currentTime + (deadline - Date.now()) / 1000);
      return;
    }
    if (typeof AudioContext === 'undefined') return;
    const context = this.context ??= new AudioContext();
    const revision = ++this.revision;
    const routable = context as AudioContext & { setSinkId?: (id: string) => Promise<void> };
    const route = routable.setSinkId?.(sinkId).catch(() => undefined) ?? Promise.resolve();
    void Promise.all([route, context.resume()]).then(() => {
      if (revision !== this.revision || this.deadline <= Date.now() || this.source) return;
      const buffer = context.createBuffer(1, context.sampleRate * 4, context.sampleRate);
      const data = buffer.getChannelData(0);
      for (const start of [0, 0.8]) {
        for (let i = 0; i < context.sampleRate * 0.2; i++) {
          const time = i / context.sampleRate;
          const envelope = Math.min(1, time / 0.01, (0.2 - time) / 0.01);
          data[Math.floor(start * context.sampleRate) + i] = 0.12 * envelope * Math.sin(2 * Math.PI * 425 * time);
        }
      }
      const source = context.createBufferSource();
      source.buffer = buffer; source.loop = true; source.connect(context.destination);
      this.source = source;
      source.onended = () => { source.disconnect(); if (this.source === source) this.source = undefined; };
      source.start(); source.stop(context.currentTime + (this.deadline - Date.now()) / 1000);
    }).catch(() => undefined);
  }
  stop(): void {
    this.revision++;
    if (this.source) { this.source.onended = null; try { this.source.stop(); } catch { /* ended */ } this.source.disconnect(); this.source = undefined; }
    const context = this.context;
    this.context = undefined;
    if (context) void context.close().catch(() => undefined);
  }
}
