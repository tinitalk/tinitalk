import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { DesktopVideoExpansion } from './desktopVideo';

vi.mock('./i18n', () => ({ t: (key: string) => key }));

let keydown: (event: { key: string; defaultPrevented?: boolean }) => void;
let mediaChanged: () => void;
let media: { matches: boolean; addEventListener: ReturnType<typeof vi.fn> };
let button: any;
let classes: Set<string>;
let resized = vi.fn<() => void>();

beforeEach(() => {
  classes = new Set();
  button = { hidden: false, attributes: new Map(), focus: vi.fn(),
    setAttribute(key: string, value: string) { this.attributes.set(key, value); } };
  media = { matches: true, addEventListener: vi.fn((_type, fn) => { mediaChanged = fn; }) };
  vi.stubGlobal('window', { matchMedia: () => media,
    addEventListener: (_type: string, fn: typeof keydown) => { keydown = fn; } });
  vi.stubGlobal('document', { createElement: () => button, body: { append: vi.fn(),
    classList: { toggle: (name: string, value: boolean) => value ? classes.add(name) : classes.delete(name) } } });
  vi.stubGlobal('requestAnimationFrame', (fn: () => void) => { fn(); return 1; });
  resized = vi.fn();
});
afterEach(() => vi.unstubAllGlobals());

it('expands and collapses the same call without replacing its media', () => {
  const control = new DesktopVideoExpansion(resized);
  expect(button.hidden).toBe(true);
  control.update('account/call');
  expect(button.hidden).toBe(false);
  expect(button.title).toBe('video_expand');
  button.onclick();
  expect(classes.has('video-expanded')).toBe(true);
  expect(button.attributes.get('aria-expanded')).toBe('true');
  expect(button.title).toBe('video_collapse');
  control.update('account/call');
  expect(classes.has('video-expanded')).toBe(true);
  button.onclick();
  expect(classes.has('video-expanded')).toBe(false);
  expect(resized).toHaveBeenCalledTimes(2);
});

it('collapses with Escape but leaves handled keys alone', () => {
  const control = new DesktopVideoExpansion(resized);
  control.update('account/call');
  button.onclick();
  keydown({ key: 'Escape', defaultPrevented: true });
  expect(classes.has('video-expanded')).toBe(true);
  keydown({ key: 'Escape' });
  expect(classes.has('video-expanded')).toBe(false);
  expect(button.focus).toHaveBeenCalledOnce();
});

it.each([null, 'account/next-call', 'other-account/call'])('resets expansion when video ends or the call changes to %s', next => {
  const control = new DesktopVideoExpansion(resized);
  control.update('account/call');
  button.onclick();
  control.update(next);
  expect(classes.has('video-expanded')).toBe(false);
  expect(button.hidden).toBe(next === null);
});

it('hides the desktop control and restores the phone layout on a narrow or touch viewport', () => {
  const control = new DesktopVideoExpansion(resized);
  control.update('account/call');
  button.onclick();
  media.matches = false;
  mediaChanged();
  expect(button.hidden).toBe(true);
  expect(classes.has('video-expanded')).toBe(false);
  button.onclick();
  expect(classes.has('video-expanded')).toBe(false);
  media.matches = true;
  mediaChanged();
  expect(button.hidden).toBe(false);
  expect(classes.has('video-expanded')).toBe(false);
});
