type Point = { x: number; y: number };

// Pan is relative to the fitted image, so sliding panels and rotation preserve
// the part of the screen being read. Same 1–4x range as the Android viewer.
export class ScreenViewTransform {
  scale = 1;
  private pan: Point = { x: 0, y: 0 };

  reset(): void { this.scale = 1; this.pan = { x: 0, y: 0 }; }

  offset(width: number, height: number, aspect: number): Point {
    const image = this.image(width, height, aspect);
    return this.limit({ x: this.pan.x * image.x, y: this.pan.y * image.y }, image, width, height);
  }

  move(factor: number, movement: Point, focus: Point, width: number, height: number, aspect: number): void {
    const image = this.image(width, height, aspect);
    const previous = this.offset(width, height, aspect);
    const next = Math.max(1, Math.min(4, this.scale * factor));
    const ratio = next / this.scale;
    this.scale = next;
    const pan = this.limit({ x: (previous.x - focus.x) * ratio + focus.x + movement.x,
      y: (previous.y - focus.y) * ratio + focus.y + movement.y }, image, width, height);
    this.pan = { x: pan.x / Math.max(1, image.x), y: pan.y / Math.max(1, image.y) };
  }

  private image(width: number, height: number, aspect: number): Point {
    const x = Math.min(width, height * aspect);
    return { x, y: x / aspect };
  }

  private limit(pan: Point, image: Point, width: number, height: number): Point {
    const x = Math.max(0, (image.x * this.scale - width) / 2);
    const y = Math.max(0, (image.y * this.scale - height) / 2);
    return { x: Math.max(-x, Math.min(x, pan.x)), y: Math.max(-y, Math.min(y, pan.y)) };
  }
}

export function bindScreenViewer(surface: HTMLElement, video: HTMLVideoElement, transform: ScreenViewTransform,
  fit: HTMLButtonElement, onTap: () => void, onInteraction: () => void): () => void {
  const pointers = new Map<number, Point>();
  let moved = false;
  let origin: Point | undefined;
  const aspect = () => video.videoWidth && video.videoHeight ? video.videoWidth / video.videoHeight : 9 / 16;
  let lastAspect = aspect();
  const update = () => {
    const bounds = surface.getBoundingClientRect();
    const currentAspect = aspect();
    if (currentAspect !== lastAspect) { transform.reset(); lastAspect = currentAspect; }
    const pan = transform.offset(bounds.width, bounds.height, currentAspect);
    video.style.transform = `translate(${pan.x}px, ${pan.y}px) scale(${transform.scale})`;
    fit.hidden = transform.scale === 1;
  };
  const reset = () => { transform.reset(); update(); onInteraction(); };
  fit.onclick = event => { event.stopPropagation(); reset(); };
  surface.onpointerdown = event => {
    if (event.button !== 0 && event.pointerType === 'mouse') return;
    if (!pointers.size) { origin = { x: event.clientX, y: event.clientY }; moved = false; }
    pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
    if (pointers.size > 1) moved = true;
    surface.setPointerCapture(event.pointerId);
  };
  surface.onpointermove = event => {
    if (!pointers.has(event.pointerId)) return;
    const before = [...pointers.values()];
    pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
    const after = [...pointers.values()];
    if (origin && Math.hypot(event.clientX - origin.x, event.clientY - origin.y) > 5) moved = true;
    if (!moved) return;
    const center = (points: Point[]) => ({ x: points.reduce((n, p) => n + p.x, 0) / points.length,
      y: points.reduce((n, p) => n + p.y, 0) / points.length });
    const from = center(before), to = center(after);
    const distance = (points: Point[]) => Math.hypot(points[0].x - points[1].x, points[0].y - points[1].y);
    const factor = before.length === 2 ? distance(after) / Math.max(1, distance(before)) : 1;
    const bounds = surface.getBoundingClientRect();
    transform.move(factor, { x: to.x - from.x, y: to.y - from.y },
      { x: from.x - bounds.left - bounds.width / 2, y: from.y - bounds.top - bounds.height / 2 }, bounds.width, bounds.height, aspect());
    update();
    onInteraction();
  };
  surface.onpointerup = event => {
    if (!pointers.delete(event.pointerId)) return;
    if (!pointers.size && !moved) onTap();
  };
  surface.onpointercancel = event => { pointers.delete(event.pointerId); moved = true; };
  surface.onclick = event => event.stopPropagation();
  surface.ondblclick = event => { event.preventDefault(); reset(); };
  surface.addEventListener('wheel', event => {
    event.preventDefault();
    const bounds = surface.getBoundingClientRect();
    transform.move(Math.exp(-event.deltaY * .002), { x: 0, y: 0 },
      { x: event.clientX - bounds.left - bounds.width / 2, y: event.clientY - bounds.top - bounds.height / 2 }, bounds.width, bounds.height, aspect());
    update();
    onInteraction();
  }, { passive: false });
  update();
  return update;
}
