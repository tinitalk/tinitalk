/** Called after user scrolling settles; flings outside the header remain untouched. */
export function contactSnapTarget(offset: number, extent: number, maxScroll: number, direction: number): number | undefined {
  if (extent <= 0 || maxScroll < Math.ceil(extent) || offset <= 0 || offset >= extent) return undefined;
  const forward = direction ? direction > 0 : offset >= extent / 2;
  return forward ? Math.ceil(extent) : 0;
}

export type HeaderPose = { x: number; y: number; width: number; height: number; font: number };

/** Move the identity together; fit the photo above the name until it fits beside it. */
export function contactHeaderFrame(photoFrom: HeaderPose, photoTo: HeaderPose, nameFrom: HeaderPose, nameTo: HeaderPose, progress: number) {
  const p = Math.max(0, Math.min(1, progress));
  const horizontal = 1 - (1 - p) ** 3;
  const lerp = (from: number, to: number, fraction = p) => from + (to - from) * fraction;
  const photoY = lerp(photoFrom.y, photoTo.y), nameY = lerp(nameFrom.y, nameTo.y);
  const gap = lerp(nameFrom.y - photoFrom.y - photoFrom.width, nameTo.x - photoTo.x - photoTo.width);
  const extra = Math.max(0, nameY - photoY - gap - photoTo.width);
  // Ease the last 16px of shrinking to zero velocity, staying within the gap.
  const taper = Math.min(1, extra / 16);
  return {
    photo: {
      x: lerp(photoFrom.x, photoTo.x, horizontal), y: photoY,
      size: photoTo.width + extra * taper * (2 - taper),
    },
    name: { x: lerp(nameFrom.x, nameTo.x, horizontal), y: nameY, scale: lerp(1, nameTo.font / nameFrom.font) },
  };
}
