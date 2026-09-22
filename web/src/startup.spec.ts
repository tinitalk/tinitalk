import { localizedFunction } from './testI18n';
import { expect, it, onTestFinished, vi } from 'vitest';
import * as ts from 'typescript';
import sourceText from './app.ts?raw';
import { SignalConnection } from './signal';
import { api, APIError } from './api';

it('recovers an incoming call that arrived during an ordinary socket outage', async () => {
  vi.useFakeTimers();
  onTestFinished(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });
  class Socket {
    static OPEN = 1;
    static instances: Socket[] = [];
    readyState = 0;
    sent: { type: string; call_id: string }[] = [];
    onopen?: () => void;
    onclose?: () => void;
    constructor() { Socket.instances.push(this); }
    open() { this.readyState = 1; this.onopen?.(); }
    close() { this.readyState = 3; this.onclose?.(); }
    send(raw: string) { this.sent.push(JSON.parse(raw)); }
  }
  vi.stubGlobal('WebSocket', Socket);
  let incoming = false;
  vi.stubGlobal('fetch', async (url: URL) => url.pathname.endsWith('socket-ticket')
    ? Response.json({ ticket: 'ticket' })
    : incoming ? Response.json({ call_id: 'incoming-1' }) : new Response(null, { status: 204 }));
  const source = ts.createSourceFile('app.ts', sourceText, ts.ScriptTarget.ES2022, true);
  const names = ['connectAccount', 'connectAndResume', 'resumeActiveCall'];
  const code = ts.transpileModule(source.statements.filter(n => ts.isFunctionDeclaration(n) && names.includes(n.name!.text)).map(n => n.getText(source)).join('\n'), { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  const app = localizedFunction('SignalConnection', 'api', 'APIError', `
    const account = {id:'a', server:'https://family.example', login:'alice', token:'test', deviceId:'a', sessionId:'s'};
    const list = [account], connections = new Map(), states = new Map(), recoveringAccounts = new Map(), openingNotificationCalls = new Map();
    const rotatingCredentials = new Set();
    let current = null;
    const receive = async () => {}, refreshAccountContacts = async () => {}, renderApp = () => {}, failure = () => {};
    const callKey = (a,b) => a+':'+b;
    const syncNotificationLanguages = () => {};
    ${code}
    connectAccount(account);
    return {connection: connections.get(account.id)};
  `)(SignalConnection, api, APIError);
  onTestFinished(() => app.connection.stop());
  await vi.advanceTimersByTimeAsync(0);
  const first = Socket.instances[0];
  first.open();
  await vi.advanceTimersByTimeAsync(0);
  expect(first.sent).toEqual([]);
  first.close();
  incoming = true;
  await vi.advanceTimersByTimeAsync(1000);
  const second = Socket.instances[1];
  second.open();
  await vi.advanceTimersByTimeAsync(0);
  expect(second.sent).toEqual([expect.objectContaining({ type: 'call.resume', call_id: 'incoming-1' })]);
});

it.each(['', '#account=a&call=call-1&action=answer'])('starts incoming recovery before stalled background work (%s)', async hash => {
  const source = ts.createSourceFile('app.ts', sourceText, ts.ScriptTarget.ES2022, true);
  const init = source.statements.find(n => ts.isFunctionDeclaration(n) && n.name?.text === 'init')!;
  const code = ts.transpileModule(init.getText(source).replace('import.meta.env.PROD', 'false'), {compilerOptions: {target: ts.ScriptTarget.ES2022}}).outputText;
  const recover = vi.fn(), open = vi.fn().mockResolvedValue(undefined);
  const stalled = () => new Promise(() => {});
  const run = localizedFunction('recover', 'open', 'stalled', 'hash', `
    const list = [], accounts = async () => [{id:'a'}, {id:'b'}], location = {hash};
    const contactPhotos = stalled, pushEnabled = stalled, refreshAll = stalled;
    const contactPhotosByKey = new Map(), notifications = new Map(), connections = new Map();
    const connectAccount = () => {}, connectAndResume = recover, openHash = open;
    const renderApp = () => {}, writeAppHistory = () => {}, failure = () => {}, resolveAccountsReady = () => {};
    const navigator = {}, base = 'https://example.org/', pushSupport = () => null;
    let route, tab;
    ${code}
    void init();
  `);
  run(recover, open, stalled, hash);
  await new Promise(resolve => setTimeout(resolve, 0));
  expect(recover.mock.calls.map(call => call[0].id)).toEqual(hash ? ['b'] : ['a', 'b']);
  expect(open).toHaveBeenCalledWith(hash);
});

it('queues early notifications, coalesces duplicates and retains answer until the invite arrives', async () => {
  const source = ts.createSourceFile('app.ts', sourceText, ts.ScriptTarget.ES2022, true);
  const node = source.statements.find(n => ts.isFunctionDeclaration(n) && n.name?.text === 'openCall')!;
  const code = ts.transpileModule(node.getText(source), {compilerOptions: {target: ts.ScriptTarget.ES2022}}).outputText;
  let ready!: () => void, finish!: (result: boolean) => void;
  const accountsReady = new Promise<void>(resolve => { ready = resolve; });
  const restore = vi.fn(() => new Promise<boolean>(resolve => { finish = resolve; }));
  const app = localizedFunction('accountsReady', 'restoreNotificationCall', `
    const openingNotificationCalls = new Map(), pendingNotificationActions = new Map();
    const callKey = (a,b) => a+':'+b;
    ${code}
    return {openCall, pendingNotificationActions};
  `)(accountsReady, restore);
  const first = app.openCall('a', 'c');
  const second = app.openCall('a', 'c', 'answer');
  expect(restore).not.toHaveBeenCalled();
  ready();
  await Promise.resolve();
  expect(restore).toHaveBeenCalledTimes(1);
  finish(true);
  await Promise.all([first, second]);
  expect(app.pendingNotificationActions.get('a:c')).toBe('answer');
});
