import { afterEach, describe, expect, it, vi } from 'vitest';
import { compactLandscape, landscapeLayout, cameraFit, previewSize, observeAdaptiveLayout } from './adaptiveLayout';

afterEach(() => vi.unstubAllGlobals());

it('uses two panes on landscape tablets without compact phone sizing', () => {
  for (const [width, height] of [[1280, 800], [1024, 768], [960, 600]]) {
    expect(landscapeLayout(width, height)).toBe(true);
    expect(compactLandscape(width, height)).toBe(false);
    expect(landscapeLayout(height, width)).toBe(false);
  }
  expect(landscapeLayout(800, 800)).toBe(false);
  expect(landscapeLayout(599, 360)).toBe(false);
  expect(landscapeLayout(600, 360)).toBe(true);
});

describe('compact phone landscape', () => {
  it('uses the app bounds, not the desktop viewport', () => {
    expect(compactLandscape(844, 390)).toBe(true);
    expect(compactLandscape(640, 320)).toBe(true);
    expect(compactLandscape(390, 844)).toBe(false);
    expect(compactLandscape(430, 760)).toBe(false);
    expect(compactLandscape(1024, 768)).toBe(false);
    expect(compactLandscape(390, 250)).toBe(false);
  });
});

it('preserves the layout while typing but reacts to a real rotation without replacing nodes', () => {
  let bounds = { width: 600, height: 800 }, editing = false;
  let resize!: () => void;
  const classes = new Set<string>();
  const disconnect = vi.fn(), changed = vi.fn();
  vi.stubGlobal('ResizeObserver', class { constructor(callback: () => void) { resize = callback; } observe() {} disconnect = disconnect; });
  vi.stubGlobal('document', { activeElement: { matches: () => editing } });
  const root = { getBoundingClientRect: () => bounds, addEventListener() {}, removeEventListener() {},
    classList: { contains: (name: string) => classes.has(name), toggle: (name: string, value: boolean) => value ? classes.add(name) : classes.delete(name) } };
  const stop = observeAdaptiveLayout(root as unknown as HTMLElement, changed);
  editing = true;
  bounds = { width: 600, height: 350 }; resize();
  expect(classes.has('landscape-layout')).toBe(false);
  bounds = { width: 844, height: 390 }; resize();
  expect(classes.has('landscape-layout')).toBe(true);
  expect(changed).toHaveBeenCalledOnce();
  bounds = { width: 390, height: 500 }; resize();
  expect(classes.has('landscape-layout')).toBe(false);
  stop(); expect(disconnect).toHaveBeenCalledOnce();
});

describe('camera proportions', () => {
  it('fills only matching orientations without stretching', () => {
    expect(cameraFit(844, 390, 1280, 720)).toBe('cover');
    expect(cameraFit(390, 844, 720, 1280)).toBe('cover');
    expect(cameraFit(844, 390, 720, 1280)).toBe('contain');
    expect(cameraFit(390, 844, 1280, 720)).toBe('contain');
    expect(cameraFit(390, 844, 0, 0)).toBe('contain');
  });
  it('keeps preview frame proportions and fits in the available space', () => {
    expect(previewSize(1280, 720, 800, 300)).toEqual({ width: 150, height: 84.375 });
    expect(previewSize(720, 1280, 800, 300)).toEqual({ width: 84.375, height: 150 });
    const small = previewSize(1280, 720, 80, 40);
    expect(small.width / small.height).toBeCloseTo(1280 / 720);
    expect(small.height).toBe(40);
    expect(small.width).toBeLessThanOrEqual(80);
  });
});
