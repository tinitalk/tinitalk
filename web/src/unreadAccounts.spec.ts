import { expect, it, vi } from 'vitest';
import * as ts from 'typescript';
import appSource from './app.ts?raw';

const source = ts.createSourceFile('app.ts', appSource, ts.ScriptTarget.ES2022, true);
const names = ['removeAccount', 'markSessionReplaced', 'unreadCount', 'clearUnread', 'receive', 'outgoing', 'submitAccount', 'claim'];
const code = ts.transpileModule(source.statements.filter(node => ts.isFunctionDeclaration(node) && names.includes(node.name?.text ?? '')).map(node => node.getText(source)).join('\n'), { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;

it.each(['remove', 'revoke'])('clears missed calls for an account on %s without clearing another account', async action => {
  const app = new Function(`
    const favorites = {removeAccount() {}};
    const account = {id: 'a', sessionId: 'session'}, list = [account, {id: 'b'}];
    const unreadMissedCountByAccount = new Map([['a', 3], ['b', 2]]);
    const unreadMissedByContact = new Map([['a:alice', 1], ['b:bob', 2]]);
    const connections = new Map(), states = new Map(), contactsByAccount = new Map(), historyByAccount = new Map(), notifications = new Map();
    const current = null, endedCall = null, route = {name: 'home'}, base = '/', removingAccounts = new Set();
    const disablePush = async () => {}, deleteAccount = async () => {}, prunePushes = async () => {}, deletePhotosForAccount = async () => {}, saveAccount = async () => {};
    const replaceRoute = () => {}, renderApp = () => {}, failure = () => {};
    ${code}
    return { account, removeAccount, markSessionReplaced, unreadCount, unreadMissedByContact };
  `)();
  expect(app.unreadCount()).toBe(5);
  if (action === 'remove') await app.removeAccount(app.account);
  else app.markSessionReplaced(app.account, 'session');
  expect(app.unreadCount()).toBe(2);
  expect([...app.unreadMissedByContact]).toEqual([['b:bob', 2]]);
});

it('does not admit incoming or outgoing calls during account removal', async () => {
  let finish!: () => void;
  const disablePush = () => new Promise<void>(resolve => { finish = resolve; });
  const createCall = vi.fn(() => { throw new Error('must not create a call'); });
  const app = new Function('disablePush', 'createCall', `
    const favorites = {removeAccount() {}};
    const account = {id:'a'}, list = [account], removingAccounts = new Set();
    const connections = new Map(), contactsByAccount = new Map(), historyByAccount = new Map(), states = new Map(), notifications = new Map();
    const unreadMissedCountByAccount = new Map(), unreadMissedByContact = new Map();
    let current = null;
    const base = '/', deleteAccount = async () => {}, prunePushes = async () => {}, deletePhotosForAccount = async () => {}, replaceRoute = () => {};
    const contactDisplayName = () => 'peer';
    ${code}
    return {account, removeAccount, receive, outgoing};
  `)(disablePush, createCall);
  const pending = app.removeAccount(app.account);
  await expect(app.outgoing(app.account, {login:'peer'})).rejects.toThrow();
  await app.receive(app.account, {type:'call.incoming', call_id:'call', sent_at:Date.now(), payload:{caller_login:'peer'}});
  expect(createCall).not.toHaveBeenCalled();
  finish();
  await pending;
});

it.each(['claim', 'save'])('does not replace another account when removed during re-login %s', async stage => {
  let finish!: () => void;
  const pause = () => new Promise<void>(resolve => { finish = resolve; });
  const api = vi.fn(async (_account, path) => {
    if (path === '/healthz') return {features:['browser_v1']};
    if (stage === 'claim') await pause();
    return {session_id:'new'};
  });
  const saveAccount = vi.fn(stage === 'save' ? pause : async () => {});
  const connectAccount = vi.fn();
  const deleteAccount = vi.fn(async () => {});
  const app = new Function('api', 'saveAccount', 'connectAccount', 'deleteAccount', `
    const favorites = {removeAccount() {}};
    const account = {id:'a', sessionId:'old', sessionReplaced:true}, other = {id:'b'}, list = [account, other], removingAccounts = new Set();
    const connections = new Map(), contactsByAccount = new Map(), historyByAccount = new Map(), states = new Map(), notifications = new Map();
    const unreadMissedCountByAccount = new Map(), unreadMissedByContact = new Map();
    const current = null, route = {name:'profile'}, base = '/', disablePush = async () => {}, prunePushes = async () => {}, deletePhotosForAccount = async () => {}, replaceRoute = () => {}, renderApp = () => {};
    const FormData = class {get(name) {return name === 'server' ? 'https://a.example' : 'value'}};
    const normalizeServer = value => value, accountForLogin = () => account, crypto = {randomUUID: () => 'id'};
    const requestPushPermission = async () => false, pushEnabled = async () => false, refreshAll = async () => {}, notice = () => {};
    let tab;
    ${code}
    return {account, other, list, removeAccount, login: () => submitAccount({reset() {}}, 'login', {}, {})};
  `)(api, saveAccount, connectAccount, deleteAccount);
  const login = app.login();
  await vi.waitFor(() => expect(finish).toBeDefined());
  await app.removeAccount(app.account);
  finish();
  await login;
  expect(app.list).toEqual([app.other]);
  expect(connectAccount).not.toHaveBeenCalled();
  if (stage === 'claim') expect(saveAccount).not.toHaveBeenCalled();
  expect(deleteAccount).toHaveBeenCalledTimes(stage === 'save' ? 2 : 1);
});
