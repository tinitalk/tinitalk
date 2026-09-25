import { localizedFunction } from './testI18n';
import { expect, it, vi } from 'vitest';
import * as ts from 'typescript';
import appSource from './app.ts?raw';

function functionCode(names: string[]): string {
  const source = ts.createSourceFile('app.ts', appSource, ts.ScriptTarget.ES2022, true);
  const selected = names.map(name => {
    const node = source.statements.find(item => ts.isFunctionDeclaration(item) && item.name?.text === name);
    if (!node) throw new Error(`missing function ${name}`);
    return node.getText(source);
  }).join('\n');
  return ts.transpileModule(selected, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
}

function profilePasswordActionHarness(api: ReturnType<typeof vi.fn>) {
  const code = functionCode(['loadProfilePasswordAction']);
  const account = {id:'a', token:'token', sessionId:'session', passwordAuth:true, passwordSet:true};
  const actions = {isConnected:true, append:vi.fn(), closest: () => null};
  const load = localizedFunction('api', 'account', `
    const list = [account], removingAccounts = new Set(), rotatingCredentials = new Set();
    const saveAccount = async () => {}, actionButton = label => label;
    ${code}
    return loadProfilePasswordAction;
  `)(api, account);
  return {account, actions, load: () => load(account, actions)};
}

it.each([false, true])('shows a password action only after both checks finish (password set: %s)', async passwordSet => {
  let resolveHealth!: (value: unknown) => void;
  let resolveProfile!: (value: unknown) => void;
  const health = new Promise(resolve => { resolveHealth = resolve; });
  const profile = new Promise(resolve => { resolveProfile = resolve; });
  const api = vi.fn().mockReturnValueOnce(health).mockReturnValueOnce(profile);
  const app = profilePasswordActionHarness(api);
  const pending = app.load();
  expect(app.actions.append).not.toHaveBeenCalled();
  resolveHealth({features:['password_auth_v1']});
  await Promise.resolve();
  expect(app.actions.append).not.toHaveBeenCalled();
  resolveProfile({password_set:passwordSet});
  await pending;
  expect(app.actions.append).toHaveBeenCalledExactlyOnceWith(passwordSet ? 'Сменить пароль' : 'Задать пароль');
});

it('hides password actions on old servers despite cached account flags', async () => {
  const api = vi.fn().mockResolvedValue({features:['browser_v1']});
  const app = profilePasswordActionHarness(api);
  await app.load();
  expect(app.actions.append).not.toHaveBeenCalled();
  expect(api).toHaveBeenCalledOnce();
});

it.each([undefined, 'false'])('hides password actions when the server does not confirm a boolean state: %s', async passwordSet => {
  const app = profilePasswordActionHarness(vi.fn()
    .mockResolvedValueOnce({features:['password_auth_v1']}).mockResolvedValueOnce({password_set:passwordSet}));
  await app.load();
  expect(app.actions.append).not.toHaveBeenCalled();
});

it('hides password actions when the status request fails', async () => {
  const app = profilePasswordActionHarness(vi.fn()
    .mockResolvedValueOnce({features:['password_auth_v1']}).mockRejectedValueOnce(new Error('offline')));
  await app.load();
  expect(app.actions.append).not.toHaveBeenCalled();
});

it('ignores a profile result after navigation', async () => {
  let resolveProfile!: (value: unknown) => void;
  const profile = new Promise(resolve => { resolveProfile = resolve; });
  const app = profilePasswordActionHarness(vi.fn()
    .mockResolvedValueOnce({features:['password_auth_v1']}).mockReturnValueOnce(profile));
  const pending = app.load();
  await Promise.resolve();
  app.actions.isConnected = false;
  resolveProfile({password_set:false});
  await pending;
  expect(app.actions.append).not.toHaveBeenCalled();
});

it('persists a newly issued token before claiming a browser session', async () => {
  const order: string[] = [];
  const saveAccount = vi.fn(async account => { order.push(`save:${account.token}:${account.sessionId}`); });
  const claim = vi.fn(async account => { order.push(`claim:${account.token}`); account.sessionId = 'session'; });
  const code = functionCode(['persistAndClaim']);
  const run = localizedFunction('saveAccount', 'claim', `${code}; return persistAndClaim;`)(saveAccount, claim);
  const account = { token: 'issued', sessionId: '' };

  await run(account, true, () => true);

  expect(order).toEqual(['save:issued:', 'claim:issued', 'save:issued:session']);
});

it('activates legacy-server accounts only after the browser session is claimed', async () => {
  const order: string[] = [];
  const saveAccount = vi.fn(async () => { order.push('save'); });
  const claim = vi.fn(async account => { order.push('claim'); account.sessionId = 'legacy-session'; });
  const code = functionCode(['persistAndClaim']);
  const run = localizedFunction('saveAccount', 'claim', `${code}; return persistAndClaim;`)(saveAccount, claim);
  const account = {token:'legacy-token', sessionId:''};
  await run(account, false, () => true, () => order.push('install'));
  expect(order).toEqual(['claim', 'save']);
  expect(account).toEqual({token:'legacy-token', sessionId:'legacy-session'});
});

it.each([false, true, undefined])('confirms logout normally regardless of password state: %s', async passwordSet => {
  const code = functionCode(['confirmRemoveAccount']);
  const modal = {body:{append:vi.fn()}, actions:{append:vi.fn()}};
  const dialog = vi.fn(() => modal);
  const element = vi.fn((_tag, _className, text) => text);
  const actionButton = vi.fn((label, action) => ({label, action}));
  const closeDialog = vi.fn(), removeAccount = vi.fn();
  const confirm = localizedFunction('dialog', 'element', 'actionButton', 'closeDialog', 'removeAccount', `
    ${code}
    return confirmRemoveAccount;
  `)(dialog, element, actionButton, closeDialog, removeAccount);
  const account = {id:'a', passwordAuth:true, passwordSet};

  await confirm(account);

  expect(dialog).toHaveBeenCalledExactlyOnceWith('Выйти из аккаунта?');
  expect(modal.body.append).toHaveBeenCalledExactlyOnceWith('Чтобы снова принимать звонки, потребуется войти ещё раз.');
  expect(actionButton.mock.calls.map(call => call[0])).toEqual(['Отмена', 'Выйти']);
  expect(removeAccount).not.toHaveBeenCalled();
  await actionButton.mock.results[1].value.action();
  expect(removeAccount).toHaveBeenCalledExactlyOnceWith(account);
});

it('ends the server session before removing local account data', async () => {
  const code = functionCode(['removeAccount']);
  const order: string[] = [];
  const account = { id: 'a', passwordAuth: true };
  const app = localizedFunction('logout', 'account', 'order', `
    const current = null, removingAccounts = new Set(), base = '/', list = [account];
    const beginCredentialRotation = () => {}, endCredentialRotation = () => {};
    const disablePush = async () => {}, connections = new Map(), deleteAccount = async () => { order.push('delete'); };
    const favorites = {removeAccount() {}}, prunePushes = async () => {}, deletePhotosForAccount = async () => {};
    const clearUnread = () => {}, contactsByAccount = new Map(), historyByAccount = new Map(), states = new Map(), notifications = new Map();
    const replaceRoute = () => {};
    ${code}
    return { removeAccount };
  `)(async () => { order.push('logout'); }, account, order);

  await app.removeAccount(account);

  expect(order).toEqual(['logout', 'delete']);
});

it('preserves password whitespace when parsing pasted credentials', () => {
  const code = functionCode(['splitAccountAddress', 'splitCredentials']);
  const parse = localizedFunction(`
    const normalizeServer = value => value;
    ${code}
    return splitCredentials;
  `)();

  expect(parse('alice\n  long personal pass  \nfamily.example\n')).toEqual(['alice', '  long personal pass  ', 'family.example']);
  expect(parse('alice@family.example\n  long personal pass  ')).toEqual(['alice', '  long personal pass  ', 'family.example']);
});

it('disconnects and identity-guards an account during credential rotation', () => {
  const code = functionCode(['beginCredentialRotation', 'markSessionReplaced']);
  const stop = vi.fn(), saveAccount = vi.fn();
  const account = { id: 'a', sessionId: 'old-session', token: 'old-token' };
  const app = localizedFunction('stop', 'saveAccount', 'account', `
    const list = [account], rotatingCredentials = new Set(), connections = new Map([['a', {stop}]]);
    const removingAccounts = new Set();
    const clearUnread = () => {}, states = new Map(), contactsByAccount = new Map();
    let current = null, endedCall = null, endedCallTimer, tab = 'contacts', route = {name:'home'};
    const closeCallNotification = async () => {}, base = '/', prunePushes = async () => {}, replaceRoute = () => {};
    ${code}
    beginCredentialRotation(account);
    markSessionReplaced(account, 'old-session');
    return {connections, rotatingCredentials};
  `)(stop, saveAccount, account);

  expect(stop).toHaveBeenCalledOnce();
  expect(app.connections.has('a')).toBe(false);
  expect(app.rotatingCredentials.has(account)).toBe(true);
  expect(saveAccount).not.toHaveBeenCalled();
  expect(account).not.toHaveProperty('sessionReplaced');
});

it('records whether the authenticated account has a personal password', async () => {
  const code = functionCode(['refreshPasswordState']);
  const api = vi.fn().mockResolvedValue({ password_set: false });
  const saveAccount = vi.fn().mockResolvedValue(undefined);
  const refresh = localizedFunction('api', 'saveAccount', `${code}; return refreshPasswordState;`)(api, saveAccount);
  const account: { passwordAuth: boolean; passwordSet?: boolean; sessionId: string } = { passwordAuth: true, sessionId: 'claimed' };

  await refresh(account);

  expect(api).toHaveBeenCalledWith(account, '/api/me');
  expect(account.passwordSet).toBe(false);
  expect(saveAccount).toHaveBeenCalledWith(account);
});

it('installs a saved token in memory before claim and keeps it if the connection is lost', async () => {
  const code = functionCode(['persistAndClaim']);
  const order: string[] = [];
  const saved: unknown[] = [];
  const claim = vi.fn(async () => { order.push('claim'); throw new Error('offline'); });
  const saveAccount = vi.fn(async account => { saved.push({...account}); order.push('save'); });
  const run = localizedFunction('saveAccount', 'claim', `${code}; return persistAndClaim;`)(saveAccount, claim);
  const account = {token: 'new-token', sessionId: ''};
  await expect(run(account, true, () => true, () => order.push('install'))).rejects.toThrow('offline');
  expect(order).toEqual(['save', 'install', 'claim']);
  expect(saved).toEqual([account]);
});

it('resumes a saved token by claiming only, without exchanging a password again', async () => {
  const code = functionCode(['resumeAccountActivation']);
  const account = {id:'a', token:'issued', sessionId:'', passwordAuth:true};
  const claim = vi.fn(async a => { a.sessionId = 'claimed'; });
  const connectAccount = vi.fn();
  const run = localizedFunction('account', 'claim', 'connectAccount', `
    const list=[account], activatingAccounts=new Set(), removingAccounts=new Set(), states=new Map();
    const saveAccount=async()=>{}, refreshPasswordState=async()=>{}, refreshAll=async()=>{}, renderApp=()=>{};
    ${code}
    return resumeAccountActivation;
  `)(account, claim, connectAccount);
  await run(account);
  expect(claim).toHaveBeenCalledOnce();
  expect(connectAccount).toHaveBeenCalledWith(account);
  expect(account.sessionId).toBe('claimed');
});
