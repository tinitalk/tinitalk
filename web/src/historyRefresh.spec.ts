import { expect, it, vi } from 'vitest';
import * as ts from 'typescript';
import appSource from './app.ts?raw';

const source = ts.createSourceFile('app.ts', appSource, ts.ScriptTarget.ES2022, true);
const names = ['refreshHistory', 'loadContactHistory'];
const code = ts.transpileModule(source.statements.filter(node => ts.isFunctionDeclaration(node) && names.includes(node.name?.text ?? '')).map(node => node.getText(source)).join('\n'), { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;

it('invalidates contact history and discards a peer response started before the refresh', async () => {
  const account = { id: 'a' };
  const old = { items: [{ id: 1 }], latest_id: 0 };
  const fresh = { items: [{ id: 2 }], latest_id: 0 };
  let resolveOld!: (page: unknown) => void;
  const api = vi.fn().mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; })).mockResolvedValue(fresh);
  const renderApp = vi.fn();
  const create = new Function('api', 'renderApp', 'account', `
    const list = [account], contactHistory = new Map([['a:b', [{id: 1}]]]), historyByAccount = new Map(), loadingContactHistory = new Set();
    let historyRevision = 0, loadingHistory = false;
    const accountKey = (id, login) => id + ':' + login;
    const applyUnread = () => {}, applyUnreadState = () => {}, failure = () => {};
    ${code}
    return { refreshHistory, loadContactHistory, contactHistory, loadingContactHistory };
  `);
  const app = create(api, renderApp, account);
  const contact = { account, login: 'b' };
  const pending = app.loadContactHistory(contact, false);
  await app.refreshHistory(false);
  expect(app.contactHistory.has('a:b')).toBe(false);
  resolveOld(old);
  await pending;
  expect(app.contactHistory.has('a:b')).toBe(false);
  expect(app.loadingContactHistory.size).toBe(0);
  await app.loadContactHistory(contact, false);
  expect(app.contactHistory.get('a:b')).toEqual(fresh.items);
});
