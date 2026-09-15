export type Account = {
  id: string; server: string; login: string; token: string; name: string;
  deviceId: string; sessionId: string; pushConfigId?: string; sessionReplaced?: boolean;
};
export type Contact = { login: string; display_name: string; custom_name?: string; can_call: boolean };
export type ContactPhoto = { id: string; accountId: string; login: string; dataUrl: string; updatedAt: number };
export type SignalEvent = { id: string; call_id: string; type: string; sent_at: number; seq?: number; payload: Record<string, unknown> };
export type PushRecord = { id: string; accountId: string; callId: string; type: string; caller: string; expiresAt: number; receivedAt: number; sessionId: string };
export type HistoryItem = {
  id: number;
  peer_login: string;
  peer_name: string;
  direction: 'incoming' | 'outgoing';
  outcome: string;
  reply_code?: string;
  reached: boolean;
  started_at: number;
  duration_seconds: number;
};
export type HistoryPage = {
  items: HistoryItem[];
  next_before: number;
  latest_id: number;
  unread_missed_count: number;
  unread_missed: { peer_login: string; peer_name: string; started_at: number; missed_count: number }[];
};

export function normalizeServer(value: string): string {
  const url = new URL(value.includes('://') ? value.trim() : `https://${value.trim()}`);
  const local = ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname);
  if ((url.protocol !== 'https:' && !(local && url.protocol === 'http:')) || url.username || url.password || url.search || url.hash || (url.pathname !== '/' && url.pathname !== '')) {
    throw new Error('Укажите HTTPS-адрес сервера без пути, пароля и параметров.');
  }
  return url.origin;
}

export function accountIdentity(server: string, login: string): string { return `${normalizeServer(server)}\n${login.trim()}`; }
export function accountForLogin(accounts: readonly Account[], server: string, login: string): Account | undefined {
  const origin = normalizeServer(server);
  const existing = accounts.filter(account => normalizeServer(account.server) === origin);
  if (!existing.length) return undefined;
  if (existing.length === 1 && existing[0].sessionReplaced && existing[0].login.trim() === login.trim()) return existing[0];
  throw new Error('Аккаунт с этого сервера уже добавлен');
}
export function accountScope(base: string, id: string): string {
  if (!/^[a-zA-Z0-9-]+$/.test(id)) throw new Error('Некорректный идентификатор учётки');
  return new URL(`notifications/${id}/`, base).href;
}
export function accountFromScope(scope: string): string | null {
  return new URL(scope).pathname.match(/\/notifications\/([a-zA-Z0-9-]+)\/$/)?.[1] ?? null;
}
export function callKey(accountId: string, callId: string): string { return `${accountId}:${callId}`; }
export function canOpenIncoming(record: PushRecord, account: Account, now = Date.now()): boolean {
  return !account.sessionReplaced && record.accountId === account.id && record.type === 'incoming_call' && record.expiresAt > now && (!record.sessionId || record.sessionId === account.sessionId);
}
export function deepLink(base: string, accountId: string, callId: string, action?: string): string {
  const url = new URL(base);
  const params = new URLSearchParams({ account: accountId, call: callId });
  if (action) params.set('action', action);
  url.hash = params.toString();
  return url.href;
}
export function base64Key(value: string): Uint8Array<ArrayBuffer> {
  const raw = atob(value.replace(/-/g, '+').replace(/_/g, '/'));
  return Uint8Array.from(raw, c => c.charCodeAt(0));
}
