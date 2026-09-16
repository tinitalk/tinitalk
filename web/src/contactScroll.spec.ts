import { expect, it } from 'vitest';
import { contactHeaderFrame, contactSnapTarget, type HeaderPose } from './contactScroll';

it('settles in the gesture direction even after a small movement', () => {
  expect(contactSnapTarget(70, 400, 1200, 1)).toBe(400);
  expect(contactSnapTarget(350, 400, 1200, -1)).toBe(0);
});

it('does not pull a short page back or interrupt a fling beyond the header', () => {
  expect(contactSnapTarget(180, 400, 200, 1)).toBeUndefined();
  expect(contactSnapTarget(700, 400, 1200, 1)).toBeUndefined();
  expect(contactSnapTarget(0, 400, 1200, -1)).toBeUndefined();
});

it('settles an interrupted partial header even when the new touch did not move', () => {
  expect(contactSnapTarget(180, 400, 1200, 0)).toBe(0);
  expect(contactSnapTarget(220, 400, 1200, 0)).toBe(400);
});

it('keeps space between the photo and both name layouts throughout the shared motion', () => {
  const photoFrom: HeaderPose = {x:56,y:86,width:208,height:208,font:80};
  const photoTo: HeaderPose = {x:60,y:12,width:40,height:40,font:20};
  const nameFrom: HeaderPose = {x:20,y:316,width:280,height:66,font:28};
  const nameTo: HeaderPose = {x:110,y:18.5,width:124,height:27,font:20};
  for (let step = 0; step <= 100; step++) {
    const p = step / 100;
    const frame = contactHeaderFrame(photoFrom, photoTo, nameFrom, nameTo, p);
    const cx = frame.photo.x + frame.photo.size / 2, cy = frame.photo.y + frame.photo.size / 2;
    for (const [width, height] of [[nameFrom.width * frame.name.scale, nameFrom.height * frame.name.scale], [nameTo.width * frame.name.scale / (20/28), nameTo.height * frame.name.scale / (20/28)]]) {
      const dx = Math.max(frame.name.x - cx, 0, cx - frame.name.x - width);
      const dy = Math.max(frame.name.y - cy, 0, cy - frame.name.y - height);
      expect(Math.hypot(dx, dy) - frame.photo.size / 2, `gap at ${step}%`).toBeGreaterThanOrEqual(9);
    }
    expect(frame.photo.y).toBeCloseTo(86 - 74 * p);
    expect(frame.name.y).toBeCloseTo(316 - 297.5 * p);
  }
  expect(contactHeaderFrame(photoFrom, photoTo, nameFrom, nameTo, 0)).toEqual({photo:{x:56,y:86,size:208},name:{x:20,y:316,scale:1}});
  const end = contactHeaderFrame(photoFrom, photoTo, nameFrom, nameTo, 1);
  expect(end.photo).toEqual({x:60,y:12,size:40});
  expect(end.name.x).toBe(110);
  expect(end.name.y).toBe(18.5);
  // The shrinking photo must ease into its compact size, without an abrupt
  // velocity change when it no longer needs to fit above the name.
  const dock = 168 / 211.5;
  const size = (p: number) => contactHeaderFrame(photoFrom, photoTo, nameFrom, nameTo, p).photo.size;
  const beforeDock = size(dock - .0002) - size(dock - .0001);
  const afterDock = size(dock + .0001) - size(dock + .0002);
  expect(Math.abs(beforeDock - afterDock)).toBeLessThan(.002);
});
