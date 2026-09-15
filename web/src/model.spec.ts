import { describe, expect, it } from 'vitest';
import { accountForLogin, accountFromScope, accountIdentity, accountScope, callKey, canOpenIncoming, deepLink, normalizeServer, type Account, type PushRecord } from './model';

describe('one account per server', () => {
  const account: Account = { id: 'family', server: 'https://family.example', login: 'alice', token: 'test', name: 'Alice', deviceId: 'phone', sessionId: 'session' };

  it.each(['family.example', 'https://FAMILY.example/', 'https://family.example:443'])('rejects another login on the same server: %s', server => {
    expect(() => accountForLogin([account], server, 'bob')).toThrow('Аккаунт с этого сервера уже добавлен');
  });
  it('allows an account on a different server', () => {
    expect(accountForLogin([account], 'other.example', 'alice')).toBeUndefined();
    expect(accountForLogin([], 'family.example', 'alice')).toBeUndefined();
  });
  it('only reuses the same account when its session was replaced', () => {
    expect(() => accountForLogin([account], account.server, account.login)).toThrow();
    const replaced = { ...account, sessionReplaced: true };
    expect(accountForLogin([replaced], 'family.example', ' alice ')).toBe(replaced);
    expect(() => accountForLogin([replaced], 'family.example', 'bob')).toThrow();
  });
  it('requires removing an existing duplicate before signing in again', () => {
    const replaced = { ...account, sessionReplaced: true };
    const other = { ...account, id: 'other', login: 'bob' };
    expect(() => accountForLogin([replaced, other], 'family.example', 'alice')).toThrow();
    expect(accountForLogin([replaced], 'family.example', 'alice')).toBe(replaced);
  });
});

describe('account and notification isolation', () => {
  it('distinguishes the same login on different servers', () => {
    expect(accountIdentity('https://one.example','alice')).not.toBe(accountIdentity('https://two.example','alice'));
    expect(accountIdentity('https://ONE.example/','alice')).toBe(accountIdentity('https://one.example','alice'));
  });
  it('uses separate registrations under either deployment prefix', () => {
    for (const base of ['https://official.example/', 'https://family.example/app/']) {
      expect(accountScope(base,'account-a')).not.toBe(accountScope(base,'account-b'));
      expect(accountFromScope(accountScope(base,'account-a'))).toBe('account-a');
      expect(new URL(deepLink(base,'account-a','call-1')).pathname).toBe(new URL(base).pathname);
      expect(new URLSearchParams(new URL(deepLink(base,'account-a','call-1','answer')).hash.slice(1)).get('action')).toBe('answer');
    }
    expect(() => accountScope('https://example.org/','../bad')).toThrow();
  });
  it('does not reopen expired, cancelled, replaced, or another account calls', () => {
    const account = { id:'a', sessionId:'session-a' } as Account;
    const record: PushRecord = { id:callKey('a','call'), accountId:'a', callId:'call',type:'incoming_call',caller:'bob',expiresAt:200,receivedAt:1,sessionId:'session-a' };
    expect(canOpenIncoming(record,account,100)).toBe(true);
    expect(canOpenIncoming(record,account,200)).toBe(false);
    expect(canOpenIncoming({...record,type:'call_cancel'},account,100)).toBe(false);
    expect(canOpenIncoming({...record,accountId:'b'},account,100)).toBe(false);
    expect(canOpenIncoming({...record,sessionId:'old'},account,100)).toBe(false);
    expect(canOpenIncoming(record,{...account,sessionReplaced:true},100)).toBe(false);
    expect(callKey('a','call')).not.toBe(callKey('b','call'));
  });
  it('rejects credentials and paths in server addresses', () => {
    for (const value of ['http://example.org','https://user:secret@example.org','https://example.org/api','https://example.org/?x=1']) expect(() => normalizeServer(value)).toThrow();
    expect(normalizeServer('http://localhost:8080')).toBe('http://localhost:8080');
  });
});
