import { localizedFunction } from './testI18n';
import { expect, it, vi } from 'vitest';
import * as ts from 'typescript';
import appSource from './app.ts?raw';
import { APIError } from './api';
import { AuthError } from './auth';
import { OperationError } from './userErrors';
import type { Account } from './model';

function functionCode(names: string[]): string {
  const source = ts.createSourceFile('app.ts', appSource, ts.ScriptTarget.ES2022, true);
  const selected = names.map(name => {
    const node = source.statements.find(item => ts.isFunctionDeclaration(item) && item.name?.text === name);
    if (!node) throw new Error(`missing function ${name}`);
    return node.getText(source);
  }).join('\n');
  return ts.transpileModule(selected, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
}

function harness(enabled = true, passwordSet = true) {
  const account: Account = {
    id: 'alice', server: 'https://family.example', login: 'alice', name: 'Alice',
    deviceId: 'browser', token: 'old-token', sessionId: 'old-session', passwordAuth: true,
    passwordSet, ...(enabled ? { pushConfigId: 'push-config' } : {}),
  };
  const list = [account];
  const order: string[] = [];
  const notifications = new Map([[account.id, enabled]]);
  const elements: Record<string, { onsubmit?: (event: { preventDefault(): void }) => void }> = {};
  const element = vi.fn((_tag, className) => {
    const node = { append() {}, addEventListener() {},
      onsubmit: undefined as ((event: { preventDefault(): void }) => void) | undefined };
    elements[className] = node;
    return node;
  });
  const saveAccount = vi.fn(async () => { order.push(`save:${account.token}:${account.sessionId}`); });
  const claim = vi.fn(async () => { order.push(`claim:${account.token}`); account.sessionId = 'new-session'; });
  const enablePush = vi.fn(async () => { order.push(`push:${account.token}:${account.sessionId}`); });
  const connectAccount = vi.fn();
  const closeDialog = vi.fn().mockResolvedValue(undefined);
  const notice = vi.fn();
  const changePassword = vi.fn(async () => { order.push('password'); return { token: 'new-token' }; });
  const requireAccountLogin = vi.fn();
  const modal = { body: { append() {} }, actions: { append() {} } };
  const code = functionCode(['changePasswordDialog', 'resumeAccountActivation', 'restorePushRegistration']);
  const app = localizedFunction('dependencies', `
    const {account,list,notifications,saveAccount,claim,enablePush,connectAccount,closeDialog,notice,
      changePassword,requireAccountLogin,element,modal,APIError,AuthError,OperationError} = dependencies;
    const base = '/', current = null, route = {name:'profile'}, activatingAccounts = new Set();
    const removingAccounts = new Set(), rotatingCredentials = new Set(), states = new Map();
    const preparePasswordAccount = async () => {}, personalPasswordError = () => undefined;
    const dialog = () => modal, inputField = () => ({}), actionButton = () => ({});
    const refreshPasswordState = async () => {}, refreshAll = async () => {}, renderApp = () => {};
    const beginCredentialRotation = () => rotatingCredentials.add(account);
    const endCredentialRotation = () => rotatingCredentials.delete(account);
    class FormData { get(key) { return {
      current_password: account.passwordSet ? 'old password' : null,
      new_password: 'new password', confirm_password: 'new password'
    }[key]; } }
    ${code}
    return { changePasswordDialog, resumeAccountActivation, restorePushRegistration };
  `)({account, list, notifications, saveAccount, claim, enablePush, connectAccount, closeDialog, notice,
    changePassword, requireAccountLogin, element, modal, APIError, AuthError, OperationError});
  const submitPassword = async () => {
    await app.changePasswordDialog(account);
    elements['material-form password-form'].onsubmit!({preventDefault() {}});
  };
  return {account, list, order, notifications, saveAccount, claim, enablePush, connectAccount, closeDialog,
    notice, changePassword, requireAccountLogin, app, submitPassword};
}

it.each([false, true])('restores enabled push after password mutation and session claim (password set: %s)', async passwordSet => {
  const test = harness(true, passwordSet);
  await test.submitPassword();
  await vi.waitFor(() => expect(test.closeDialog).toHaveBeenCalledOnce());

  expect(test.order).toEqual([
    'password', 'save:new-token:', 'claim:new-token', 'save:new-token:new-session',
    'push:new-token:new-session',
  ]);
  expect(test.changePassword).toHaveBeenCalledExactlyOnceWith(test.account.server, test.account.login,
    passwordSet ? 'old password' : 'old-token', 'new password');
  expect(test.enablePush).toHaveBeenCalledExactlyOnceWith(test.account, '/', false);
  expect(test.notifications.get(test.account.id)).toBe(true);
});

it('restores push after reconnect resumes a failed session claim without another password mutation', async () => {
  const test = harness();
  test.claim.mockRejectedValueOnce(new Error('offline'));
  await test.submitPassword();
  await vi.waitFor(() => expect(test.closeDialog).toHaveBeenCalledOnce());
  expect(test.account.token).toBe('new-token');
  expect(test.account.sessionId).toBe('');
  expect(test.enablePush).not.toHaveBeenCalled();
  expect(test.notifications.get(test.account.id)).toBe(false);

  await test.app.resumeAccountActivation(test.account);

  expect(test.claim).toHaveBeenCalledTimes(2);
  expect(test.changePassword).toHaveBeenCalledOnce();
  expect(test.enablePush).toHaveBeenCalledExactlyOnceWith(test.account, '/', false);
  expect(test.notifications.get(test.account.id)).toBe(true);
});

it('does not enable disabled notifications after password change or activation recovery', async () => {
  const test = harness(false);
  await test.submitPassword();
  await vi.waitFor(() => expect(test.closeDialog).toHaveBeenCalledOnce());
  test.account.sessionId = '';
  await test.app.resumeAccountActivation(test.account);

  expect(test.enablePush).not.toHaveBeenCalled();
  expect(test.notifications.get(test.account.id)).toBe(false);
});

it('defers startup push registration until the saved password session is claimed', async () => {
  const test = harness();
  test.account.sessionId = '';
  const source = ts.createSourceFile('app.ts', appSource, ts.ScriptTarget.ES2022, true);
  const init = source.statements.find(item => ts.isFunctionDeclaration(item) && item.name?.text === 'init') as ts.FunctionDeclaration;
  const loop = init.body!.statements.filter(ts.isForOfStatement).at(-1)!;
  const code = ts.transpileModule(`async function startupPush() { ${loop.getText(source)} }`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const run = localizedFunction('list', 'enablePush', `
    const pushSupport = () => null, base = '/', notifications = new Map(), route = {name:'home'};
    const renderApp = () => {}, checkAppUpdates = async () => {};
    ${code}
    return startupPush;
  `)(test.list, test.enablePush);

  await run();
  expect(test.enablePush).not.toHaveBeenCalled();
  await test.app.resumeAccountActivation(test.account);
  expect(test.enablePush).toHaveBeenCalledExactlyOnceWith(test.account, '/', false);
});

it('reports push failure without undoing the new session or retrying password change', async () => {
  const test = harness();
  test.enablePush.mockRejectedValueOnce(new Error('offline'));
  await test.submitPassword();
  await vi.waitFor(() => expect(test.closeDialog).toHaveBeenCalledOnce());

  expect(test.notifications.get(test.account.id)).toBe(false);
  expect(test.notice).toHaveBeenCalledWith('Не удалось подключить уведомления. Попробуйте включить их в профиле.');
  expect(test.notice.mock.lastCall).toEqual(['Не удалось подключить уведомления. Попробуйте включить их в профиле.']);
  expect(test.account.token).toBe('new-token');
  expect(test.account.sessionId).toBe('new-session');
  expect(test.account.pushConfigId).toBe('push-config');
  expect(test.changePassword).toHaveBeenCalledOnce();
  expect(test.requireAccountLogin).not.toHaveBeenCalled();
});

it.each(['removed', 'replaced', 'disabled', 'new-session'])('does not apply a stale push result after %s', async reason => {
  const test = harness();
  let resolve!: () => void;
  test.enablePush.mockImplementationOnce(() => new Promise<void>(done => { resolve = done; }));
  const pending = test.app.restorePushRegistration(test.account);
  expect(test.notifications.get(test.account.id)).toBe(false);

  if (reason === 'removed') test.list.length = 0;
  if (reason === 'replaced') test.account.sessionReplaced = true;
  if (reason === 'disabled') test.account.pushConfigId = undefined;
  if (reason === 'new-session') test.account.sessionId = 'another-session';
  resolve();
  await pending;

  expect(test.notifications.get(test.account.id)).toBe(false);
  expect(test.notice).not.toHaveBeenCalled();
});
