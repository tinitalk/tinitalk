import { expect, it, vi } from 'vitest';
import * as ts from 'typescript';
import appSource from './app.ts?raw';

it('fetches contacts for a new session without joining the previous session request', async () => {
  const source = ts.createSourceFile('app.ts', appSource, ts.ScriptTarget.ES2022, true);
  const code = ts.transpileModule(source.statements.filter(n => ts.isFunctionDeclaration(n) && n.name?.text === 'refreshAccountContacts').map(n => n.getText(source)).join('\n'), { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  const previous = { id: 'a', sessionId: 'old' }, current = { id: 'a', sessionId: 'new' };
  const list = [previous];
  let resolveOld!: (value: unknown) => void;
  const fresh = [{ login: 'bob', can_call: true }];
  const api = vi.fn().mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; })).mockResolvedValue(fresh);
  const app = new Function('api', 'list', `const contactsByAccount = new Map(), contactRefreshes = new Map(), current = null; ${code}; return { refreshAccountContacts, contactsByAccount };`)(api, list);
  const pending = app.refreshAccountContacts(previous);
  list.splice(0, 1, current);
  const resumed = app.refreshAccountContacts(current);
  resolveOld([{ login: 'bob', can_call: false }]);
  await Promise.all([pending, resumed]);
  expect(api).toHaveBeenCalledWith(current, '/api/contacts');
  expect(app.contactsByAccount.get('a')).toEqual(fresh);
});

it('discards an in-flight contact list after another invalidation and fetches fresh availability', async () => {
  const source = ts.createSourceFile('app.ts', appSource, ts.ScriptTarget.ES2022, true);
  const code = ts.transpileModule(source.statements.filter(n => ts.isFunctionDeclaration(n) && n.name?.text === 'refreshAccountContacts').map(n => n.getText(source)).join('\n'), { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  const account = { id: 'a' };
  let resolve!: (value: unknown) => void;
  const api = vi.fn().mockImplementationOnce(() => new Promise(r => { resolve = r; })).mockResolvedValue([{ login: 'bob', can_call: true }]);
  const create = new Function('api', 'account', `const list = [account], contactsByAccount = new Map(), contactRefreshes = new Map(), current = null; ${code}; return { refresh: () => refreshAccountContacts(account), contactsByAccount };`);
  const app = create(api, account);
  const first = app.refresh();
  const second = app.refresh();
  resolve([{ login: 'bob', can_call: false }]);
  await Promise.all([first, second]);
  expect(api).toHaveBeenCalledTimes(2);
  expect(app.contactsByAccount.get('a')).toEqual([{ login: 'bob', can_call: true }]);
});
