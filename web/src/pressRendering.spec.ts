import { afterEach, expect, it, vi } from 'vitest';
import { bindPressRendering } from './pressRendering';

afterEach(() => vi.useRealTimers());
function fixture() {
  vi.useFakeTimers();
  const host = new EventTarget();
  const root = Object.assign(new EventTarget(), { dataset: {} as Record<string, string>, ownerDocument: { defaultView: host } });
  const released = vi.fn();
  const dispose = bindPressRendering(root as unknown as HTMLElement, released);
  const send = (type: string, pointerId = 1, button = 0) => {
    const event = Object.assign(new Event(type), { pointerId, button });
    (type === 'pointerdown' ? root : host).dispatchEvent(event);
  };
  return { root, released, send, dispose };
}

it('holds rendering from pointerdown through pointerup until click is delivered', () => {
  const f = fixture();
  f.send('pointerdown');
  expect(f.root.dataset.pressing).toBe('true');
  f.send('pointerup');
  vi.advanceTimersByTime(200);
  expect(f.root.dataset.pressing).toBe('true');
  expect(f.released).not.toHaveBeenCalled();
  f.send('click');
  expect(f.root.dataset.pressing).toBeUndefined();
  expect(f.released).not.toHaveBeenCalled();
  vi.runAllTimers();
  expect(f.released).toHaveBeenCalledOnce();
  f.dispose();
});

it.each(['pointercancel', 'blur', 'pointerup'])('releases without click after %s', type => {
  const f = fixture();
  f.send('pointerdown'); f.send(type); vi.runAllTimers();
  expect(f.root.dataset.pressing).toBeUndefined();
  expect(f.released).toHaveBeenCalledOnce();
  f.dispose();
});

it('ignores unrelated releases and keeps multiple touches protected', () => {
  const f = fixture();
  f.send('pointerdown', 1); f.send('pointerdown', 2);
  f.send('pointerup', 3); f.send('pointerup', 1); f.send('click');
  vi.runAllTimers();
  expect(f.root.dataset.pressing).toBe('true');
  f.send('pointercancel', 2); vi.runAllTimers();
  expect(f.root.dataset.pressing).toBeUndefined();
  f.dispose();
});

it('cleans pending timers on disposal', () => {
  const f = fixture();
  f.send('pointerdown'); f.send('pointerup'); f.dispose(); vi.runAllTimers();
  expect(f.root.dataset.pressing).toBeUndefined();
  expect(f.released).not.toHaveBeenCalled();
});
