import { afterEach, expect, it, vi } from 'vitest';
import * as ts from 'typescript';
import appSource from './app.ts?raw';
import { personalPasswordError } from './auth';
import { accountForLogin, normalizeServer, type Account } from './model';
import { OperationError } from './userErrors';

afterEach(() => vi.useRealTimers());

function functionCode(names: string[]): string {
  const source = ts.createSourceFile('app.ts', appSource, ts.ScriptTarget.ES2022, true);
  const selected = names.map(name => {
    const node = source.statements.find(item => ts.isFunctionDeclaration(item) && item.name?.text === name);
    if (!node) throw new Error(`missing function ${name}`);
    return node.getText(source);
  }).join('\n');
  return ts.transpileModule(selected, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
}

type LoginPath = 'account' | 'password-setup';
const paths: LoginPath[] = ['account', 'password-setup'];
const authenticated = { token: 'issued-token', passwordAuth: true, passwordRequired: false };

function harness(path: LoginPath) {
  const permission = deferred<boolean>();
  const authentication = deferred<typeof authenticated>();
  const order: string[] = [];
  const requestPushPermission = vi.fn(() => { order.push('permission'); return permission.promise; });
  const network = vi.fn(() => { order.push('authentication'); return authentication.promise; });
  const enablePush = vi.fn(async () => { order.push('push'); });
  const connectAccount = vi.fn();
  const replaceRoute = vi.fn(() => { order.push('home'); });
  const list: Account[] = [];
  const notifications = new Map<string, boolean>();
  const fields: Record<string, string> = {
    server: 'https://family.example', login: 'alice', token: 'temporary-password',
    new_password: 'personal-password', confirm_password: 'personal-password',
  };
  const form = { reset: vi.fn() };
  class FormDataStub { get(name: string) { return fields[name]; } }
  const code = functionCode(['submitAccount', 'submitPasswordSetup', 'finishAccountLogin']);
  const submit = new Function('dependencies', `
    const {FormData,personalPasswordError,accountForLogin,normalizeServer,OperationError,
      requestPushPermission,network,enablePush,connectAccount,replaceRoute,list,notifications,form,path} = dependencies;
    const authenticate = network, changePassword = network, removingAccounts = new Set();
    const beginCredentialRotation = () => {}, endCredentialRotation = () => {};
    const base = '/', route = {name:'login'}, notice = () => {}, renderApp = () => {};
    const crypto = {randomUUID: () => 'generated-id'};
    const persistAndClaim = async (account, _passwordAuth, _valid, install) => {
      account.sessionId = 'claimed-session'; install();
    };
    const refreshPasswordState = async () => {}, pushEnabled = async () => false, refreshAll = async () => {};
    let tab = 'contacts';
    ${code}
    return () => path === 'account' ? submitAccount(form, 'login') : submitPasswordSetup(form, 'login', {
      server:'https://family.example', login:'alice', temporaryPassword:'temporary-password',
    });
  `)({ FormData: FormDataStub, personalPasswordError, accountForLogin, normalizeServer, OperationError,
    requestPushPermission, network, enablePush, connectAccount, replaceRoute, list, notifications, form, path });
  return { submit: submit as () => Promise<unknown>, permission, authentication, order, requestPushPermission,
    network, enablePush, connectAccount, replaceRoute, list, notifications, fields, form };
}

it.each(paths)('requests permission synchronously before slow %s authentication and registers only after login', async path => {
  vi.useFakeTimers();
  const app = harness(path);
  setTimeout(() => app.authentication.resolve(authenticated), 6000);

  const pending = app.submit();

  expect(app.order).toEqual(['permission', 'authentication']);
  app.permission.resolve(true);
  await vi.advanceTimersByTimeAsync(5999);
  expect(app.enablePush).not.toHaveBeenCalled();
  expect(app.connectAccount).not.toHaveBeenCalled();
  await vi.advanceTimersByTimeAsync(1);
  await pending;

  expect(app.requestPushPermission).toHaveBeenCalledOnce();
  expect(app.order).toEqual(['permission', 'authentication', 'home', 'push']);
  expect(app.enablePush).toHaveBeenCalledExactlyOnceWith(app.list[0], '/', false);
  expect(app.list[0]).toMatchObject({ token: 'issued-token', sessionId: 'claimed-session' });
  expect(app.notifications.get(app.list[0].id)).toBe(true);
});

it.each(paths)('finishes %s login while permission is unresolved and registers after a later grant', async path => {
  const app = harness(path);
  const pending = app.submit();
  app.authentication.resolve(authenticated);

  await pending;

  expect(app.replaceRoute).toHaveBeenCalledExactlyOnceWith({ name: 'home' });
  expect(app.form.reset).toHaveBeenCalledOnce();
  expect(app.connectAccount).toHaveBeenCalledWith(app.list[0]);
  expect(app.enablePush).not.toHaveBeenCalled();
  app.permission.resolve(true);
  await vi.waitFor(() => expect(app.notifications.get(app.list[0].id)).toBe(true));
  expect(app.enablePush).toHaveBeenCalledExactlyOnceWith(app.list[0], '/', false);
  expect(app.requestPushPermission).toHaveBeenCalledOnce();
});

it.each(paths.flatMap(path => ['authentication-error', 'permission-denied'].map(reason => ({ path, reason }))))(
  'does not register push for $path with $reason', async ({ path, reason }) => {
    const app = harness(path);
    const pending = app.submit();
    app.permission.resolve(reason !== 'permission-denied');
    if (reason === 'authentication-error') {
      const rejected = expect(pending).rejects.toThrow('invalid credentials');
      app.authentication.reject(new Error('invalid credentials'));
      await rejected;
      expect(app.replaceRoute).not.toHaveBeenCalled();
      expect(app.list).toEqual([]);
    } else {
      app.authentication.resolve(authenticated);
      await pending;
      expect(app.replaceRoute).toHaveBeenCalledWith({ name: 'home' });
    }
    expect(app.enablePush).not.toHaveBeenCalled();
  },
);

it.each(paths)('validates %s fields before requesting permission or contacting the server', async path => {
  const app = harness(path);
  app.fields[path === 'account' ? 'login' : 'new_password'] = '';

  await expect(app.submit()).rejects.toThrow();

  expect(app.requestPushPermission).not.toHaveBeenCalled();
  expect(app.network).not.toHaveBeenCalled();
});
