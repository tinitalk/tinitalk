import { localizedFunction } from './testI18n';
import { expect, it } from 'vitest';
import * as ts from 'typescript';
import appSource from './app.ts?raw';

// The app uses the DOM directly. Keep form values on their nodes, as in a browser.
class Node {
  children: Node[] = [];
  dataset: Record<string, string> = {};
  value = '';
  name = '';
  className = '';
  textContent = '';
  parentElement: Node | null = null;
  listeners = new Map<string, () => void>();
  get childNodes() { return this.children; }
  get firstElementChild() { return this.children[0]; }
  get isConnected(): boolean { return this.tag === 'screen' || Boolean(this.parentElement?.isConnected); }
  constructor(public tag: string) {}
  append(...children: Node[]) {
    for (const child of children) {
      if (child.parentElement) child.parentElement.children = child.parentElement.children.filter(item => item !== child);
      child.parentElement = this;
      this.children.push(child);
    }
  }
  replaceChildren(...children: Node[]) {
    for (const child of this.children) child.parentElement = null;
    this.children = [];
    this.append(...children);
  }
  addEventListener(type: string, callback: () => void) { this.listeners.set(type, callback); }
  dispatchEvent(event: { type: string }) { this.listeners.get(event.type)?.(); }
  focus() {}
  querySelector(selector: string): Node | null {
    const name = selector.match(/input\[name="(.*?)"\]/)?.[1];
    for (const child of this.children) {
      if (name && child.tag === 'input' && child.name === name) return child;
      if (selector === 'form' && child.tag === 'form') return child;
      if (selector === 'form.credentials' && child.tag === 'form' && child.className.includes('credentials')) return child;
      const nested = child.querySelector(selector);
      if (nested) return nested;
    }
    return null;
  }
}

it.each(['add-account', 'login', 'add-contact'])('keeps entered values on background renders of %s, but clears them after navigation', routeName => {
  const source = ts.createSourceFile('app.ts', appSource, ts.ScriptTarget.ES2022, true);
  const names = ['renderApp', 'credentialsScreen', 'credentialsReady', 'addContactScreen', 'element'];
  const code = ts.transpileModule(source.statements.filter(n => ts.isFunctionDeclaration(n) && names.includes(n.name!.text)).map(n => n.getText(source)).join('\n'), { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  const app = localizedFunction('Node', 'routeName', `
    const webCommit = '12345678';
    const owner = {id:'a', login:'alice', server:'https://family.example', sessionReplaced:routeName === 'login'};
    const list = [owner], screen = new Node('screen'), document = {createElement: tag => new Node(tag)};
    let route = routeName === 'login' ? {name:'login', accountId:'a'} : {name:routeName}, tab = 'contacts';
    let contactHistoryGeneration = 0, historyObserver, accountSubmission, contactGesture = false, deferredRender = false, showFavorites = true;
    const viewScroll = new Map(), notice = () => {}, wireHistoryScroll = () => {};
    let disposeView = () => {};
    const contactHistory = new Map(), contactHistoryCursors = new Map(), contactHistoryErrors = new Map();
    const renderCall = () => {}, closeActiveOverlay = () => {}, writeAppHistory = () => {};
    const appPage = body => body, appMark = () => new Node('mark');
    const homeScreen = () => new Node('home');
    const wireCredentialsPaste = () => {}, wireServerCheck = () => {}, normalizeServer = s => s, serverAddress = s => s;
    const inputField = (_, name) => Object.assign(new Node('input'), {name});
    const setFormValue = (form, name, value) => {form.querySelector('input[name="'+name+'"]').value = value;};
    ${code}
    return {screen, renderApp, leave: () => {owner.sessionReplaced = false; route = {name:'home'};},
      enter: () => {owner.sessionReplaced = routeName === 'login'; route = routeName === 'login' ? {name:'login', accountId:'a'} : {name:routeName};}};
  `)(Node, routeName);
  app.renderApp();
  const fields = routeName === 'add-contact' ? ['login', 'name'] : ['login', 'token', 'server'];
  for (const name of fields) app.screen.querySelector(`input[name="${name}"]`).value = `entered-${name}`;
  app.renderApp();
  expect(fields.map(name => app.screen.querySelector(`input[name="${name}"]`).value)).toEqual(fields.map(name => `entered-${name}`));
  app.leave(); app.renderApp(); app.enter(); app.renderApp();
  const privateField = routeName === 'add-contact' ? 'name' : 'token';
  expect(app.screen.querySelector(`input[name="${privateField}"]`).value).toBe('');
});

it.each(['login', 'add-account'])('opens a separate password screen for %s and restores credentials on Back', async mode => {
  const source = ts.createSourceFile('app.ts', appSource, ts.ScriptTarget.ES2022, true);
  const names = ['credentialsScreen', 'credentialsReady', 'element'];
  const code = ts.transpileModule(source.statements.filter(n => ts.isFunctionDeclaration(n) && names.includes(n.name!.text)).map(n => n.getText(source)).join('\n'), { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  const app = localizedFunction('Node', 'mode', `
    const document = {createElement: tag => new Node(tag)}, screen = new Node('screen');
    const list = [], webCommit = 'abc123', appMark = () => new Node('mark');
    let accountSubmission, disposeView = () => {}, activeOverlayClose, pageBack, submitted;
    const appPage = (body, options) => {
      const page = new Node('page'); page.textContent = options.title; pageBack = options.back; page.append(body); return page;
    };
    const registerActiveOverlay = close => { activeOverlayClose = close; return close; };
    const wireCredentialsPaste = () => {}, wireServerCheck = () => {}, normalizeServer = s => s;
    const inputField = (_, name) => Object.assign(new Node('input'), {name});
    const OperationError = class extends Error {};
    const showCredentialError = (_, err) => { throw err; };
    const submitAccount = async () => ({server:'family.example',login:'alice',temporaryPassword:'12345678'});
    const submitPasswordSetup = async (form, mode, setup) => {
      submitted = [mode, setup, form.querySelector('input[name="new_password"]').value];
    };
    ${code}
    screen.append(credentialsScreen(mode));
    return {
      screen,
      input: (name, value) => {
        screen.querySelector('input[name="'+name+'"]').value = value;
        screen.querySelector('form').dispatchEvent({type:'input'});
      },
      submit: async () => { screen.querySelector('form').onsubmit({preventDefault(){}}); await accountSubmission; },
      back: () => pageBack(), browserBack: () => activeOverlayClose(),
      submitted: () => submitted,
    };
  `)(Node, mode);
  app.input('login', 'alice'); app.input('token', '12345678'); app.input('server', 'family.example');
  await app.submit();
  const text = (node: Node): string => [node.textContent, ...node.children.map(text)].join(' ');
  expect(text(app.screen)).toContain('Придумайте пароль');
  expect(text(app.screen)).not.toContain('Временный пароль');
  expect(app.screen.querySelector('input[name="login"]')).toBeNull();
  expect(app.screen.querySelector('input[name="token"]')).toBeNull();
  expect(app.screen.querySelector('input[name="server"]')).toBeNull();
  app.input('new_password', '  мой длинный пароль  '); app.input('confirm_password', '  мой длинный пароль  ');
  await app.submit();
  expect(app.submitted()).toEqual([mode, {server:'family.example', login:'alice', temporaryPassword:'12345678'}, '  мой длинный пароль  ']);
  app.back();
  expect(app.screen.querySelector('input[name="login"]').value).toBe('alice');
  expect(app.screen.querySelector('input[name="token"]').value).toBe('12345678');
  expect(app.screen.querySelector('input[name="server"]').value).toBe('family.example');
  expect(app.screen.querySelector('input[name="new_password"]')).toBeNull();
  await app.submit();
  expect(app.screen.querySelector('input[name="new_password"]').value).toBe('');
  app.browserBack();
  expect(app.screen.querySelector('input[name="token"]').value).toBe('12345678');
});
