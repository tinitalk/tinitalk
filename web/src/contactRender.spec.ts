import { expect, it } from 'vitest';
import * as ts from 'typescript';
import appSource from './app.ts?raw';

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
      querySelector: () => busy ? card : null,
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
