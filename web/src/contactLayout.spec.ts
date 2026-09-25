import { expect, it } from 'vitest';
import * as ts from 'typescript';
import appSource from './app.ts?raw';

const source = ts.createSourceFile('app.ts', appSource, ts.ScriptTarget.ES2022, true);
function code(name: string): string {
  const node = source.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === name)!;
  return ts.transpileModule(node.getText(source), {compilerOptions: {target: ts.ScriptTarget.ES2022}}).outputText;
}

it.each([false, true])('keeps contact identity separate from history, including empty history: %s', empty => {
  class Element {
    children: Element[] = [];
    disabled = false;
    classList = {add() {}};
    constructor(public tag: string, public className = '') {}
    append(...children: Element[]) { this.children.push(...children); }
    querySelector() { return null; }
  }
  const element = (tag: string, css?: string) => new Element(tag, css);
  const render = new Function('element', 'empty', `
    const contact = {login: 'alice', can_call: true, account: {id: 'one', server: 'https://example.invalid'}};
    const findContact = () => contact, contactDisplayName = () => 'Alice';
    const t = key => key, contactActionLabel = () => 'Call';
    const actionButton = (_label, _action, css) => element('button', css);
    const contactMenu = () => element('div', 'menu-holder');
    const favoriteStar = () => element('svg'), updateFavoriteButton = () => {};
    const accountKey = (id, login) => id + ':' + login, list = [contact.account], current = null;
    const avatar = (_name, _login, css) => element('div', css), photoForContact = () => null;
    const contactHistory = new Map([['one:alice', empty ? [] : [{}]]]);
    const contactHistoryErrors = new Set(), loadingContactHistory = new Set(), contactHistoryCursors = new Map();
    const historyRows = () => element('div', 'history-list'), contactHistoryMessage = () => element('div', 'history-empty');
    const appPage = body => {const page = element('div', 'app-page'); page.append(body); return page;};
    ${code('contactScreen')} return contactScreen;
  `)(element, empty);
  const page: Element = render('one', 'alice');
  const body = page.children[0];
  expect(body.className).toBe('contact-screen');
  expect(body.children.map(child => child.className)).toEqual(['contact-identity', 'contact-history']);
  expect(body.children[0].children.at(-1)?.className).toBe('primary call-wide');
  expect(body.children[1].children.at(-1)?.className).toBe(empty ? 'history-empty' : 'history-list');
});

it('selects the independent landscape history scroller without moving identity nodes', () => {
  const host = new Function(`${code('historyScrollHost')} return historyScrollHost;`)();
  const history = {};
  let landscape = false;
  const contact = {closest: () => landscape ? {} : null, querySelector: () => history};
  expect(host(contact)).toBe(contact);
  landscape = true;
  expect(host(contact)).toBe(history);
  const globalHistory = {closest: () => ({}), querySelector: () => null};
  expect(host(globalHistory)).toBe(globalHistory);
});
