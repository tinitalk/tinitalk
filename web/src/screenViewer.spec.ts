import { expect, it } from 'vitest';
import { ScreenViewTransform } from './screenViewer';

it('fits the whole image, clamps zoom and prevents panning into empty space', () => {
  const view = new ScreenViewTransform();
  expect(view.offset(400, 600, 16 / 9)).toEqual({ x: 0, y: 0 });
  view.move(2, { x: 900, y: 900 }, { x: 0, y: 0 }, 400, 600, 16 / 9);
  expect(view.scale).toBe(2);
  expect(view.offset(400, 600, 16 / 9)).toEqual({ x: 200, y: 0 });
  view.move(100, { x: 900, y: 900 }, { x: 0, y: 0 }, 400, 600, 16 / 9);
  expect(view.scale).toBe(4);
  expect(view.offset(400, 600, 16 / 9)).toEqual({ x: 600, y: 150 });
  view.move(.01, { x: 900, y: 900 }, { x: 0, y: 0 }, 400, 600, 16 / 9);
  expect(view.scale).toBe(1);
  expect(view.offset(400, 600, 16 / 9)).toEqual({ x: 0, y: 0 });
});

it('zooms around the selected text and keeps relative position as panels slide', () => {
  const view = new ScreenViewTransform();
  view.move(2, { x: 0, y: 0 }, { x: 50, y: 60 }, 400, 600, 1);
  expect(view.offset(400, 600, 1)).toEqual({ x: -50, y: -60 });
  expect(view.offset(600, 700, 1)).toEqual({ x: -75, y: -90 });
  view.reset();
  expect(view.scale).toBe(1);
  expect(view.offset(600, 700, 1)).toEqual({ x: 0, y: 0 });
});
