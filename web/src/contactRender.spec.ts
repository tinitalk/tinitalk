import { expect, it } from 'vitest';
import * as ts from 'typescript';
import appSource from './app.ts?raw';
import { Favorites } from './favorites';

it.each([false, true])('updates the favorite button while history is pending (initially starred: %s)', initiallyStarred => {
  const source = ts.createSourceFile('app.ts', appSource, ts.ScriptTarget.ES2022, true);
  const names = ['renderApp', 'contactScreen', 'updateFavoriteButton'];
  const code = ts.transpileModule(source.statements.filter(node => ts.isFunctionDeclaration(node) && names.includes(node.name!.text)).map(node => node.getText(source)).join('\n'), { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  const stored = new Map<string, string>();
  const favorites = new Favorites({ getItem: key => stored.get(key) ?? null, setItem: (key, value) => { stored.set(key, value); } });
  if (initiallyStarred) favorites.set('a:anna', true);
  const app = new Function('favorites', `
    class Node {
      children = []; attributes = {}; scrollTop = 170;
      classes = new Set();
      classList = {add: value => this.classes.add(value), toggle: (value, on) => on ? this.classes.add(value) : this.classes.delete(value)};
      append(...children) { this.children.push(...children); }
      setAttribute(key, value) { this.attributes[key] = value; }
      querySelector() { return null; }
    }
    const account = {id:'a'}, list = [account], route = {name:'contact',accountId:'a',login:'anna'}, tab = 'contacts';
    const contact = {account,login:'anna',can_call:true};
    let contactGesture = false, deferredRender = false, current = null, star, undo, loads = 0;
    const contactHistory = new Map(), contactHistoryErrors = new Set(), loadingContactHistory = new Set();
    const accountKey = (a,b) => a+':'+b, findContact = () => contact;
    const renderCall = () => {}, failure = error => {throw error;};
    const loadContactHistory = () => {loads++; loadingContactHistory.add('a:anna'); return new Promise(() => {});};
    const element = () => new Node(), avatar = () => new Node(), contactMenu = () => new Node(), favoriteStar = () => new Node();
    const contactDisplayName = () => 'Anna', contactActionLabel = () => 'Call', loadingBlock = () => new Node(), photoForContact = () => undefined;
    const actionButton = (_, action, className) => {
      const button = new Node(); button.click = action;
      if (className === 'favorite-toggle') star = button;
      return button;
    };
    const appPage = (body, options) => { const page = new Node(); page.append(body, options.menu); return page; };
    const notice = (_, action) => {undo = action;};
    const screen = {dataset:{viewKey:JSON.stringify(route)+':'+tab},querySelector:selector=>selector === '.favorite-toggle' ? star : null,
      replaceChildren:()=>{throw Error('scrollable card replaced while history is pending');}};
    ${code}
    const page = contactScreen('a','anna');
    return {star,page,loads:()=>loads,undo:()=>undo()};
  `)(favorites);

  const expectStarred = (starred: boolean) => {
    expect(favorites.keys.includes('a:anna')).toBe(starred);
    expect(app.star.classes.has('selected')).toBe(starred);
    expect(app.star.attributes['aria-pressed']).toBe(String(starred));
    expect(app.star.attributes['aria-label']).toBe(starred ? 'Убрать из избранных' : 'Добавить в избранные');
  };
  expectStarred(initiallyStarred);
  const scroller = app.page.children[0];
  app.star.click();
  expectStarred(!initiallyStarred);
  if (initiallyStarred) app.undo();
  else app.star.click();
  expectStarred(initiallyStarred);
  expect(app.page.children[0]).toBe(scroller);
  expect(scroller.scrollTop).toBe(170);
  expect(app.loads()).toBe(1);
});

it('keeps the scrolled contact mounted while history reloads or the header is moving', () => {
  const source = ts.createSourceFile('app.ts', appSource, ts.ScriptTarget.ES2022, true);
  const code = ts.transpileModule(source.statements.filter(node => ts.isFunctionDeclaration(node) && node.name?.text === 'renderApp').map(node => node.getText(source)).join('\n'), {compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText;
  const app = new Function(`
    const account = {id:'a'}, list = [account], route = {name:'contact',accountId:'a',login:'anna'}, tab = 'contacts';
    let contactGesture = false, deferredRender = false, busy = false, loads = 0;
    const contactHistory = new Map(), contactHistoryErrors = new Set(), loadingContactHistory = new Set();
    const accountKey = (a,b) => a+':'+b, findContact = () => ({account,login:'anna'});
    const renderCall = () => {};
    const card = {scrollTop:170};
    const screen = {dataset:{viewKey:JSON.stringify(route)+':'+tab},
      querySelector: selector => selector === '[data-interacting="true"]' && busy ? card : null,
      replaceChildren: () => {throw Error('card replaced during a gesture');}};
    const loadContactHistory = () => {loads++; loadingContactHistory.add('a:anna'); return Promise.resolve();};
    const failure = error => {throw error;};
    ${code}
    return {renderApp,screen,card,contactHistory,loaded:()=>loads,deferred:()=>deferredRender,
      move:()=>{busy=true;contactHistory.set('a:anna',[{id:1}]);}};
  `)();
  app.renderApp();
  app.renderApp();
  expect(app.loaded()).toBe(1);
  app.move();
  app.renderApp();
  expect(app.deferred()).toBe(true);
  expect(app.card.scrollTop).toBe(170);
});
