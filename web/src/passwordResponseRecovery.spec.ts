import { localizedFunction } from './testI18n';
import { afterEach, expect, it, vi } from 'vitest';
import * as ts from 'typescript';
import { AuthError, authErrorMessage, changePassword, personalPasswordError } from './auth';
import { OperationError } from './userErrors';
import appSource from './app.ts?raw';

afterEach(() => vi.unstubAllGlobals());

// Exercise the real dialog handler and HTTP parser; only the DOM and app
// services are stand-ins, matching the other app-level unit tests.
function passwordDialogHarness(passwordSet: boolean) {
  const source = ts.createSourceFile('app.ts', appSource, ts.ScriptTarget.ES2022, true);
  const declaration = source.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'changePasswordDialog');
  if (!declaration) throw new Error('missing changePasswordDialog');
  const code = ts.transpileModule(declaration.getText(source), { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  const account = { id: 'a', server: 'https://family.example', login: 'alice', token: 'old-token', passwordAuth: true, passwordSet };
  const nodes = new Map<string, any>();
  const element = (_tag: string, className: string, textContent = '') => {
    const node = { textContent, classList: { add() {} }, append: vi.fn(), addEventListener: vi.fn(), isConnected: true };
    nodes.set(className, node);
    return node;
  };
  const modal = { overlay: { classList: { add() {} } }, body: { append: vi.fn() }, actions: { append: vi.fn() } };
  const closeDialog = vi.fn(), requireAccountLogin = vi.fn(), notice = vi.fn(), connectAccount = vi.fn();
  const fields = new Map([['current_password', 'old-password'], ['new_password', 'new-password'], ['confirm_password', 'new-password']]);
  class FormDataStub { get(name: string) { return name === 'current_password' && !passwordSet ? null : fields.get(name); } }
  const dependencies = {
    current: null, preparePasswordAccount: async () => {}, dialog: () => modal, element,
    inputField: vi.fn(), actionButton: () => ({ disabled: false }), closeDialog,
    FormData: FormDataStub, changePassword, personalPasswordError, OperationError, AuthError, authErrorMessage,
    beginCredentialRotation: vi.fn(), endCredentialRotation: vi.fn(), saveAccount: vi.fn(), claim: vi.fn(),
    connectAccount, requireAccountLogin, notice,
  };
  const open = localizedFunction(...Object.keys(dependencies), `${code}; return changePasswordDialog;`)(...Object.values(dependencies));
  return { account, closeDialog, requireAccountLogin, notice, connectAccount,
    open: () => open(account),
    submit: () => nodes.get('material-form password-form').onsubmit({ preventDefault() {} }),
    error: () => nodes.get('form-error'),
  };
}

it.each([false, true])('leaves the password dialog for re-login after losing HTTP 200 body (existing password: %s)', async passwordSet => {
  const response = new Response(new ReadableStream<Uint8Array>({
    start(controller) { controller.enqueue(new TextEncoder().encode('{"token":"replacement')); },
    pull(controller) { controller.error(new TypeError('connection reset')); },
  }), { status: 200 });
  const fetch = vi.fn().mockResolvedValue(response);
  vi.stubGlobal('fetch', fetch);
  const app = passwordDialogHarness(passwordSet);
  await app.open();

  app.submit();

  await vi.waitFor(() => expect(app.requireAccountLogin).toHaveBeenCalledExactlyOnceWith(app.account));
  expect(app.closeDialog).toHaveBeenCalledWith(expect.anything(), 'remove');
  expect(app.notice).toHaveBeenCalledWith(expect.stringContaining('Попробуйте войти с новым паролем'));
  expect(app.connectAccount).not.toHaveBeenCalled();
  expect(fetch).toHaveBeenCalledOnce();
});

it('keeps the password form open after an explicit credential rejection', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ error: 'invalid_credentials' }, { status: 401 })));
  const app = passwordDialogHarness(true);
  await app.open();

  app.submit();

  await vi.waitFor(() => expect(app.error().textContent).toBe(authErrorMessage('invalid_credentials')));
  expect(app.requireAccountLogin).not.toHaveBeenCalled();
  expect(app.closeDialog).not.toHaveBeenCalled();
});
