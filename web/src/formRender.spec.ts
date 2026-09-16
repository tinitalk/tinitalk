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
  constructor(public tag: string) {}
  append(...children: Node[]) { this.children.push(...children); }
  replaceChildren(...children: Node[]) { this.children = children; }
  addEventListener() {}
  querySelector(selector: string): Node | null {
    const name = selector.match(/input\[name="(.*?)"\]/)?.[1];
    for (const child of this.children) {
      if (name && child.tag === 'input' && child.name === name) return child;
      if (selector === 'form' && child.tag === 'form') return child;
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
  const app = new Function('Node', 'routeName', `
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
