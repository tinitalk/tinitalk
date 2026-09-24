import { expect, it, vi } from 'vitest';
import { WaitingInvites, WaitingTone } from './waitingCalls';
import type { Account, SignalEvent } from './model';
import * as ts from 'typescript';
import appSource from './app.ts?raw';

const account = (id = 'a'): Account => ({id, server: `https://${id}.example`, login: 'me', token: 't', sessionId: 's', name: 'Me', deviceId: 'd'});
const event = (call = 'call', now = Date.now()): SignalEvent => ({id: 'event', call_id: call, type: 'call.incoming', sent_at: now, seq: 1, payload: {caller_login: 'other', call_waiting_supported: true}});

it('uses one bounded waiting tone on the conversation output and closes it on cancellation', async () => {
  const source = { connect: vi.fn(), disconnect: vi.fn(), start: vi.fn(), stop: vi.fn(), onended: null };
  const context = {
    currentTime: 0, sampleRate: 8_000, destination: {},
    resume: vi.fn().mockResolvedValue(undefined), close: vi.fn().mockResolvedValue(undefined),
    setSinkId: vi.fn().mockResolvedValue(undefined),
    createBuffer: () => ({ getChannelData: () => new Float32Array(32_000) }),
    createBufferSource: vi.fn(() => source),
  };
  vi.stubGlobal('AudioContext', function () { return context; });
  const tone = new WaitingTone();
  try {
    tone.update(Date.now() + 15_000, 'headset');
    await vi.waitFor(() => expect(source.start).toHaveBeenCalledOnce());
    expect(context.setSinkId).toHaveBeenCalledWith('headset');
    expect(source.stop.mock.calls[0][0]).toBeGreaterThan(14);
    tone.update(Date.now() + 12_000, 'headset');
    expect(context.createBufferSource).toHaveBeenCalledOnce();
    tone.stop();
    expect(context.close).toHaveBeenCalledOnce();
    expect(source.disconnect).toHaveBeenCalledOnce();
  } finally { vi.unstubAllGlobals(); }
});
function acknowledge(registry: WaitingInvites, pending: NonNullable<ReturnType<WaitingInvites['add']>>, seq = 2, now = Date.now(), waiting = true, remaining = 15_000) {
  return registry.acknowledge(pending, {...pending.event, type: 'call.waiting', seq, payload: {waiting, remaining_ms: remaining}}, now);
}

it('keeps identical IDs on different servers separate and never extends duplicates', () => {
  const registry = new WaitingInvites();
  const a = account(), b = account('b');
  const first = registry.add(a, event('same', 0), 'First', 0)!;
  const second = registry.add(b, event('same', 0), 'Second', 2000)!;
  expect(registry.add(a, event('same', 0), 'First', 8000)).toBe(first);
  acknowledge(registry, first, 2, 8000);
  expect(first.deadline).toBe(15_000);
  registry.remove(first);
  expect(registry.get(b.id, 'same')).toBe(second);
});

it('requires confirmation, limits selections and rejects expired entries', () => {
  const registry = new WaitingInvites();
  const first = registry.add(account(), event('one', 0), 'First', 0)!;
  const second = registry.add(account(), event('two', 0), 'Second', 0)!;
  expect(registry.select(first, 0)).toBe(false);
  acknowledge(registry, first, 2, 0);
  acknowledge(registry, second, 2, 0);
  expect(registry.select(first, 1)).toBe(true);
  expect(registry.select(second, 1)).toBe(false);
  registry.remove(first);
  expect(registry.select(second, 15_000)).toBe(false);
});

it('promotion preserves the original deadline and ignores old acknowledgements', () => {
  const registry = new WaitingInvites();
  const pending = registry.add(account(), event('one', 0), 'First', 1000)!;
  acknowledge(registry, pending, 2, 1000);
  acknowledge(registry, pending, 3, 5000, false, 45_000);
  expect(pending.deadline).toBe(45_000);
  expect(acknowledge(registry, pending, 2, 5000)).toBe(false);
  expect(pending.waiting).toBe(false);
});

it('limits all accounts together and invalidates replaced sessions', () => {
  const registry = new WaitingInvites();
  const owner = account();
  const first = registry.add(owner, event(), 'First')!;
  for (let i = 1; i < 8; i++) expect(registry.add(account(String(i)), event(), 'Caller')).toBeDefined();
  expect(registry.add(account('overflow'), event(), 'Caller')).toBeUndefined();
  owner.sessionId = 'replacement';
  expect(registry.has(first)).toBe(false);
  expect(registry.select(first)).toBe(false);
});

it('a dismissed invitation cannot reappear after a delayed push or replay', () => {
  const registry = new WaitingInvites(), owner = account();
  const incoming = event();
  const pending = registry.add(owner, incoming, 'Caller')!;
  registry.remove(pending);
  expect(registry.add(owner, incoming, 'Caller')).toBeUndefined();
  expect(registry.isDismissed(owner, incoming.call_id)).toBe(true);
});

const source = ts.createSourceFile('app.ts', appSource, ts.ScriptTarget.ES2022, true);
const rejectionCode = ['rejectWaiting', 'tickWaiting'].map(name => ts.transpileModule(
  source.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === name)!.getText(source),
  {compilerOptions: {target: ts.ScriptTarget.ES2022}},
).outputText).join('\n');

it.each([true, false])('marks a waiting call seen only for manual rejection (manual: %s)', manual => {
  const registry = new WaitingInvites(), owner = account();
  const pending = registry.add(owner, event(), 'Caller')!;
  const send = vi.fn();
  const harness = new Function('waitingCalls', 'connections', `
    let waitingTimer;
    const removeWaiting = invite => waitingCalls.remove(invite), promoteWaiting = () => {};
    ${rejectionCode}
    return {rejectWaiting, tickWaiting};
  `)(registry, new Map([[owner.id, {send}]]));
  if (manual) harness.rejectWaiting(pending);
  else { pending.deadline = Date.now() - 1; harness.tickWaiting(); }
  expect(send).toHaveBeenCalledExactlyOnceWith(pending.event.call_id, 'call.reject',
    manual ? {reason: 'busy', seen: true} : {reason: 'busy'});
  expect(registry.entries.size).toBe(0);
});

const receiveWaitingCode = ts.transpileModule(source.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'receiveWaiting')!.getText(source), {
  compilerOptions: {target: ts.ScriptTarget.ES2022},
}).outputText;

it.each([false, true])('keeps the legacy busy path without waiting commands on an old server (other account: %s)', otherAccount => {
  const owner = account(), target = otherAccount ? account('b') : owner;
  const current = {account: owner, id: 'ongoing', connectedAt: Date.now()};
  const registry = new WaitingInvites();
  const send = vi.fn();
  const receive = new Function('current', 'waitingCalls', 'connections', `
    ${receiveWaitingCode}
    return receiveWaiting;
  `)(current, registry, new Map([[target.id, {send}]]));
  const incoming = {...event(), payload: {caller_login: 'other'}};
  expect(receive(target, incoming)).toBe(true);
  expect(send).toHaveBeenCalledExactlyOnceWith(incoming.call_id, 'call.reject', {reason: 'busy'});
  expect(registry.entries.size).toBe(0);
  expect(current.id).toBe('ongoing');
});

it('leaves ordinary incoming calls from old servers to the existing ringing handler', () => {
  const receive = new Function('waitingCalls', `
    const current = null;
    ${receiveWaitingCode}
    return receiveWaiting;
  `)(new WaitingInvites());
  expect(receive(account(), {...event(), payload: {caller_login: 'other'}})).toBe(false);
});

const answerCode = ts.transpileModule(source.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'answerWaiting')!.getText(source), {
  compilerOptions: {target: ts.ScriptTarget.ES2022},
}).outputText;

function switchHarness(sameServer: boolean, confirm: () => Promise<void> = async () => {}) {
  const owner = account(), target = sameServer ? owner : account('b');
  const registry = new WaitingInvites();
  const pending = registry.add(target, event(), 'Second')!;
  acknowledge(registry, pending);
  const actions: string[] = [];
  const send = vi.fn((_id, type) => {actions.push(type); return 'id';});
  const accept = vi.fn(async (_id, _type, payload) => {actions.push('accept'); await confirm(); return payload;});
  const connection = {send, sendConfirmed: accept};
  const app = new Function('waitingCalls', 'invite', 'previousAccount', 'connection', 'actions', `
    let current = {account: previousAccount, id:'previous', accepted:true};
    const previous = current, waitingTone = {stop() {}}, connections = new Map([[previousAccount.id,connection], [invite.account.id,connection]]);
    const renderCall = () => {}, dismissEndedCall = () => {}, notice = () => {}, t = key => key, promoteWaiting = () => {};
    const closeActiveCall = () => { actions.push('close'); current = null; };
    const createCall = (account, id) => ({account, id, media:{capture:async () => {actions.push('capture')},close() {}}});
    const removeWaiting = item => waitingCalls.remove(item);
    const receive = async () => {}, endLocal = () => {current = null};
    ${answerCode}
    return {answer:()=>answerWaiting(invite), current:()=>current, previous};
  `)(registry, pending, owner, connection, actions);
  return {app, actions, registry, pending, send, accept};
}

it('validates a same-server replacement before closing old media', async () => {
  const h = switchHarness(true);
  await h.app.answer();
  expect(h.actions.slice(0, 3)).toEqual(['accept', 'close', 'capture']);
  expect(h.accept).toHaveBeenCalledWith('call', 'call.accept', expect.objectContaining({replace_call_id: 'previous'}));
  expect(h.app.current().id).toBe('call');
});

it('closes the old call first when accepting on another server', async () => {
  const h = switchHarness(false);
  await h.app.answer();
  expect(h.actions.slice(0, 4)).toEqual(['call.end', 'close', 'accept', 'capture']);
  expect(h.accept.mock.calls[0][2]).not.toHaveProperty('replace_call_id');
});

it('rejected acceptance preserves the current call on the same server', async () => {
  const h = switchHarness(true, async () => {throw new Error('cancelled');});
  await h.app.answer();
  expect(h.app.current()).toBe(h.app.previous);
  expect(h.actions).not.toContain('close');
  expect(h.registry.entries.size).toBe(0);
});

it('a cancellation during acceptance cannot open a dead call', async () => {
  let confirm!: () => void;
  const h = switchHarness(true, () => new Promise(resolve => {confirm = resolve;}));
  const answering = h.app.answer();
  h.registry.remove(h.pending);
  confirm();
  await answering;
  expect(h.actions).not.toContain('capture');
  expect(h.send).toHaveBeenCalledWith('call', 'call.end');
});
