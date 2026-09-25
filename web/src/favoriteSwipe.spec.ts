import { afterEach, expect, it, vi } from 'vitest';
import * as ts from 'typescript';
import appSource from './app.ts?raw';

afterEach(() => vi.useRealTimers());
const source = ts.createSourceFile('app.ts', appSource, ts.ScriptTarget.Latest, true);
const declaration = source.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'wireFavoriteSwipe')!;
const code = ts.transpileModule(declaration.getText(source), { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;

function harness(initial = true, reducedMotion = true, deferRender = false) {
  const listeners = new Map<string, (event: any) => void>();
  let dragging = false;
  const style = () => ({ transform: '', transition: '', setProperty: vi.fn(), removeProperty: vi.fn() });
  const tabs = { style: style(), classList: { add: vi.fn(), remove: vi.fn(), toggle: vi.fn() } };
  const previews: any[] = [];
  const content = { style: style(), dataset: {} as Record<string, string>, clientWidth: 360, isConnected: true,
    getBoundingClientRect: () => ({}), closest: () => ({ querySelector: () => tabs }),
    parentElement: { append: (preview: any) => previews.push(preview) },
    addEventListener: (name: string, fn: (event: any) => void) => listeners.set(name, fn),
    querySelector: () => dragging ? {} : null };
  const element = () => ({ style: style(), remove: vi.fn(), append: vi.fn(), setAttribute: vi.fn(), scrollTop: 0 });
  let dispose = () => {};
  const render = vi.fn(() => { if (!deferRender) dispose(); });
  const app = new Function('renderApp', 'screen', 'matchMedia', 'initial', 'element', 'window', `let showFavorites=initial;
    let disposeView=()=>{}, deferredRender=false;
    const viewScroll=new Map(), contactsPage=()=>({});
    ${code}; return { wireFavoriteSwipe, selected: () => showFavorites, dispose: () => disposeView() };`)(
      render, { dataset: { viewKey: 'home:contacts' } }, () => ({ matches: reducedMotion }), initial,
      element, {addEventListener() {}, removeEventListener() {}});
  const select = app.wireFavoriteSwipe(content);
  dispose = app.dispose;
  const touch = (type: string, x: number, y: number) => {
    const event = { type, touches: [{ clientX: x, clientY: y }], changedTouches: [{ clientX: x, clientY: y }], preventDefault: vi.fn() };
    listeners.get(type)!(event);
    return event;
  };
  return { touch, content, render, previews, select, dispose: app.dispose, selected: app.selected, drag: () => { dragging = true; } };
}

it('keeps the destination visible while the touch-release guard defers rendering', () => {
  vi.useFakeTimers();
  const ui = harness(false, false, true);
  ui.touch('touchstart', 100, 100);
  ui.touch('touchmove', 220, 100);
  ui.touch('touchend', 220, 100);
  vi.advanceTimersByTime(220);
  expect(ui.selected()).toBe(true);
  expect(ui.render).toHaveBeenCalledOnce();
  expect(ui.content.style.transform).toBe('translateX(360px)');
  expect(ui.previews[0].style.transform).toBe('translateX(0px)');
  expect(ui.previews[0].remove).not.toHaveBeenCalled();
  ui.dispose();
  expect(ui.previews[0].remove).toHaveBeenCalledOnce();
});

it('slides both pages with the finger and commits only after settling', () => {
  vi.useFakeTimers();
  const ui = harness(true, false);
  ui.touch('touchstart', 250, 100);
  ui.touch('touchmove', 150, 103);
  expect(ui.content.style.transform).toBe('translateX(-100px)');
  expect(ui.previews[0].style.transform).toBe('translateX(260px)');
  expect(ui.content.dataset.interacting).toBe('true');
  expect(ui.render).not.toHaveBeenCalled();
  ui.touch('touchend', 150, 103);
  expect(ui.content.style.transform).toBe('translateX(-360px)');
  expect(ui.previews[0].style.transform).toBe('translateX(0px)');
  expect(ui.selected()).toBe(true);
  vi.advanceTimersByTime(220);
  expect(ui.selected()).toBe(false);
  expect(ui.render).toHaveBeenCalledOnce();
  expect(ui.previews[0].remove).toHaveBeenCalledOnce();
  expect(ui.content.dataset.interacting).toBeUndefined();
});

it('animates tab clicks and cancels pending navigation when disposed', () => {
  vi.useFakeTimers();
  const ui = harness(false, false);
  ui.select(true);
  expect(ui.previews[0].style.transform).toBe('translateX(0px)');
  expect(ui.render).not.toHaveBeenCalled();
  ui.dispose();
  vi.runAllTimers();
  expect(ui.render).not.toHaveBeenCalled();
  expect(ui.selected()).toBe(false);
});

it.each([true, false])('switches favorites/all in the appropriate direction (favorites: %s)', initial => {
  const ui = harness(initial), end = initial ? 100 : 300;
  ui.touch('touchstart', 200, 100);
  expect(ui.touch('touchmove', end, 103).preventDefault).toHaveBeenCalledOnce();
  ui.touch('touchend', end, 103);
  expect(ui.selected()).toBe(!initial);
  expect(ui.render).toHaveBeenCalledOnce();
  expect(ui.content.style.transform).toBe('');
});

it.each(['vertical', 'reorder', 'cancel', 'long-press', 'short', 'wrong-direction'])('does not switch for %s', scenario => {
  vi.useFakeTimers();
  const ui = harness();
  ui.touch('touchstart', 200, 100);
  if (scenario === 'reorder') ui.drag();
  if (scenario === 'long-press') vi.advanceTimersByTime(350);
  const x = scenario === 'short' ? 175 : scenario === 'wrong-direction' ? 300 : 100;
  const y = scenario === 'vertical' ? 200 : 100;
  ui.touch('touchmove', x, y);
  ui.touch(scenario === 'cancel' ? 'touchcancel' : 'touchend', x, y);
  expect(ui.selected()).toBe(true);
  expect(ui.render).not.toHaveBeenCalled();
});
