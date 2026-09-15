import { expect, it, vi } from 'vitest';
import * as ts from 'typescript';
import appSource from './app.ts?raw';

const source = ts.createSourceFile('app.ts', appSource, ts.ScriptTarget.ES2022, true);
const names = ['refreshHistory', 'loadContactHistory', 'loadMoreHistory', 'historyReadVisible', 'mergeHistoryItems', 'applyUnread', 'applyUnreadState', 'markHistoryRead'];
const code = `const unreadVersions = new WeakMap(), unreadReads = new WeakMap(), unreadMissedCountByAccount = new Map(), unreadMissedByContact = new Map();\n` + ts.transpileModule(source.statements.filter(node => ts.isFunctionDeclaration(node) && names.includes(node.name?.text ?? '')).map(node => node.getText(source)).join('\n'), { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;

it('invalidates contact history and discards a peer response started before the refresh', async () => {
  const account = { id: 'a' };
  const old = { items: [{ id: 1 }], latest_id: 0 };
  const fresh = { items: [{ id: 2 }], latest_id: 0 };
  let resolveOld!: (page: unknown) => void;
  const api = vi.fn().mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; })).mockResolvedValue(fresh);
  const renderApp = vi.fn();
  const create = new Function('api', 'renderApp', 'account', `
    const list = [account], contactHistory = new Map([['a:b', [{id: 1}]]]), historyByAccount = new Map(), loadingContactHistory = new Set();
    let historyRevision = 0, loadingHistory = false, contactHistoryGeneration = 0;
    let loadingMoreHistory = false, historyVisibleLimit = 50;
    const historyCursors = new Map(), historyErrors = new Set(), contactHistoryCursors = new Map(), contactHistoryErrors = new Set();
    const accountKey = (id, login) => id + ':' + login;
    const failure = () => {};
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

it('loads older calls with the server cursor, retains rows and removes duplicates', async () => {
  const account = { id: 'a' };
  const api = vi.fn().mockResolvedValue({ items: [{ id: 50 }, { id: 49 }], next_before: 49, latest_id: 60 });
  const create = new Function('api', 'account', `
    const list = [account], historyByAccount = new Map([['a', [{id: 50}]]]), historyCursors = new Map([['a', 50]]), historyErrors = new Set();
    let historyRevision = 0, loadingHistory = false, loadingMoreHistory = false, historyVisibleLimit = 50, contactHistoryGeneration = 0;
    const renderApp = () => {};
    ${code}
    return { loadMoreHistory, historyByAccount, historyCursors };
  `);
  const app = create(api, account);
  await app.loadMoreHistory();
  expect(api.mock.calls[0][1]).toContain('before=50');
  expect(app.historyByAccount.get('a')).toEqual([{ id: 50 }, { id: 49 }]);
  expect(app.historyCursors.get('a')).toBe(49);
});

function harness(api: ReturnType<typeof vi.fn>) {
  return new Function('api', `
    const account = {id: 'a'}, list = [account], historyByAccount = new Map(), contactHistory = new Map(), loadingContactHistory = new Set();
    const historyCursors = new Map(), historyErrors = new Set(), contactHistoryCursors = new Map(), contactHistoryErrors = new Set();
    let historyRevision = 0, loadingHistory = false, loadingMoreHistory = false, historyVisibleLimit = 50, contactHistoryGeneration = 0;
    let route = {name: 'contact', accountId: 'a', login: 'b'}, tab = 'history', current = null;
    const document = {hidden: false};
    const renderApp = () => {}, failure = () => {};
    const accountKey = (id, login) => id + ':' + login;
    ${code}
    return { account, list, historyByAccount, contactHistory, historyCursors, contactHistoryCursors, historyErrors, contactHistoryErrors,
      refreshHistory, loadMoreHistory, loadContactHistory, document, unreadMissedCountByAccount,
      leave: () => { route = {name: 'home'}; contactHistoryGeneration++; },
      home: () => { route = {name: 'home'}; },
      contacts: () => { route = {name: 'home'}; tab = 'contacts'; },
      contact: {account, login: 'b'} };
  `)(api);
}

it('does not restore missed calls from a global snapshot taken before a contact was read', async () => {
  let finish!: (page: unknown) => void;
  const unread = { items: [{id:5}], latest_id:5, next_before:0, unread_missed_count:1, unread_missed:[{peer_login:'b', started_at:1}] };
  const api = vi.fn((_account, url, method) => {
    if (url === '/api/calls?limit=50') return new Promise(resolve => { finish = resolve; });
    return Promise.resolve(method === 'PUT' ? {...unread, unread_missed_count:0, unread_missed:[]} : unread);
  });
  const app = harness(api);
  const refresh = app.refreshHistory(false);
  await app.loadContactHistory(app.contact, true);
  expect(app.unreadMissedCountByAccount.get('a')).toBe(0);
  finish(unread);
  await refresh;
  expect(app.unreadMissedCountByAccount.get('a')).toBe(0);
});

it('orders read mutations from contact and global history within an account', async () => {
  const reads: ((page: unknown) => void)[] = [];
  const page = {items:[{id:5}], latest_id:5, next_before:0, unread_missed_count:2, unread_missed:[]};
  const api = vi.fn((_account, _url, method) => method === 'PUT'
    ? new Promise(resolve => reads.push(resolve)) : Promise.resolve(page));
  const app = harness(api);
  const contact = app.loadContactHistory(app.contact, true);
  await vi.waitFor(() => expect(reads).toHaveLength(1));
  app.home();
  const global = app.refreshHistory(true);
  await Promise.resolve();
  expect(app.historyByAccount.has('a')).toBe(false);
  expect(reads).toHaveLength(1);
  reads[0]({...page, unread_missed_count:1});
  await contact;
  await vi.waitFor(() => expect(reads).toHaveLength(2));
  reads[1]({...page, unread_missed_count:0});
  await global;
  expect(app.unreadMissedCountByAccount.get('a')).toBe(0);
});

it('fetches newly missed calls after a pending read instead of overwriting them with its response', async () => {
  let finishRead!: (page: unknown) => void;
  const page = {items:[{id:5}], latest_id:5, next_before:0, unread_missed_count:1, unread_missed:[]};
  const api = vi.fn((_account, _url, method) => method === 'PUT'
    ? new Promise(resolve => { finishRead = resolve; }) : Promise.resolve(page));
  const app = harness(api);
  const contact = app.loadContactHistory(app.contact, true);
  await vi.waitFor(() => expect(finishRead).toBeDefined());
  const refresh = app.refreshHistory(false);
  await Promise.resolve();
  expect(api).toHaveBeenCalledTimes(2);
  finishRead({...page, unread_missed_count:0});
  await Promise.all([contact, refresh]);
  expect(app.unreadMissedCountByAccount.get('a')).toBe(1);
});

it('preserves contact rows on failure and retries the same cursor', async () => {
  const api = vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValue({ items: [{id: 4}], next_before: 0 });
  const app = harness(api);
  app.contactHistory.set('a:b', [{id: 5}]);
  app.contactHistoryCursors.set('a:b', 5);
  await app.loadContactHistory(app.contact, false, true);
  expect(app.contactHistory.get('a:b')).toEqual([{id: 5}]);
  expect(app.contactHistoryErrors.has('a:b')).toBe(true);
  await app.loadContactHistory(app.contact, false, true);
  expect(api.mock.calls.map(call => call[1])).toEqual(Array(2).fill('/api/calls?peer=b&limit=50&before=5'));
  expect(app.contactHistory.get('a:b')).toEqual([{id: 5}, {id: 4}]);
  expect(app.contactHistoryErrors.size).toBe(0);
  await app.loadContactHistory(app.contact, false, true);
  expect(api).toHaveBeenCalledTimes(2);
});

it('ignores old global pagination after a refresh and prevents parallel loads', async () => {
  let resolve!: (value: unknown) => void;
  const api = vi.fn().mockImplementationOnce(() => new Promise(r => { resolve = r; })).mockResolvedValue({items: [{id: 60}], next_before: 60});
  const app = harness(api);
  app.historyCursors.set('a', 50);
  const pending = app.loadMoreHistory();
  await app.loadMoreHistory();
  expect(api).toHaveBeenCalledTimes(1);
  await app.refreshHistory(false);
  resolve({items: [{id: 49}], next_before: 49});
  await pending;
  expect(app.historyByAccount.get('a')).toEqual([{id: 60}]);
  expect(app.historyCursors.get('a')).toBe(60);
});

it.each(['leave', 'hidden'])('does not mark missed calls read after %s', async action => {
  let resolve!: (value: unknown) => void;
  const api = vi.fn().mockImplementationOnce(() => new Promise(r => { resolve = r; }));
  const app = harness(api);
  const pending = app.loadContactHistory(app.contact, true);
  if (action === 'leave') app.leave(); else app.document.hidden = true;
  resolve({items: [{id: 5}], latest_id: 5, next_before: 0});
  await pending;
  expect(api).toHaveBeenCalledTimes(1);
});

it('keeps each account cursor and identical IDs independent', async () => {
  const api = vi.fn().mockImplementation(async account => ({items: [{id: 1, peer_login: account.id}], next_before: 0}));
  const app = harness(api);
  app.list.push({id: 'second'});
  app.historyCursors.set('second', 9);
  app.historyCursors.set('a', 5);
  await app.loadMoreHistory();
  expect(app.historyByAccount.get('a')).toEqual([{id: 1, peer_login: 'a'}]);
  expect(app.historyByAccount.get('second')).toEqual([{id: 1, peer_login: 'second'}]);
  expect(api.mock.calls[1][1]).toContain('before=9');
  expect(api.mock.calls[0][1]).toContain('before=5');
});

it('marks only the visible contact read and never marks an appended page read', async () => {
  const api = vi.fn().mockResolvedValue({items: [{id: 5}], latest_id: 5, next_before: 5});
  const app = harness(api);
  await app.loadContactHistory(app.contact, true);
  expect(api.mock.calls[1]).toEqual([app.account, '/api/calls/read', 'PUT', {through_id: 5, peer_login: 'b'}]);
  await app.loadContactHistory(app.contact, false, true);
  expect(api).toHaveBeenCalledTimes(3);
});

it('does not mark global history read when the user switches back to contacts during loading', async () => {
  let resolve!: (value: unknown) => void;
  const api = vi.fn().mockImplementationOnce(() => new Promise(r => { resolve = r; }));
  const app = harness(api);
  app.home();
  const pending = app.refreshHistory(true);
  app.contacts();
  resolve({items: [{id: 5}], latest_id: 5, next_before: 0});
  await pending;
  expect(api).toHaveBeenCalledTimes(1);
});

it('retains a failed server history and retries while another account finishes pagination', async () => {
  const api = vi.fn().mockImplementation(async account => {
    if (account.id === 'a') throw new Error('offline');
    return {items: [{id: 2}], next_before: 0};
  });
  const app = harness(api);
  app.list.push({id: 'second'});
  app.historyByAccount.set('a', [{id: 10}]);
  app.historyCursors.set('a', 10);
  app.historyCursors.set('second', 3);
  await app.loadMoreHistory();
  expect(app.historyByAccount.get('a')).toEqual([{id: 10}]);
  expect(app.historyByAccount.get('second')).toEqual([{id: 2}]);
  expect(app.historyErrors.has('a')).toBe(true);
  api.mockResolvedValue({items: [{id: 9}], next_before: 0});
  await app.loadMoreHistory(true);
  expect(api.mock.calls.at(-1)?.[1]).toContain('before=10');
  expect(app.historyByAccount.get('a')).toEqual([{id: 10}, {id: 9}]);
  expect(app.historyErrors.size).toBe(0);
});
