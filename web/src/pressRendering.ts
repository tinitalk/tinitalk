/** Keep the pressed DOM node alive until the browser has delivered its click. */
export function bindPressRendering(root: HTMLElement, onReleased: () => void): () => void {
  const host = root.ownerDocument.defaultView!;
  const pointers = new Set<number>();
  let timer: ReturnType<typeof setTimeout> | undefined;
  const release = () => {
    clearTimeout(timer);
    delete root.dataset.pressing;
    // Run after click handlers (including their promises), not during capture.
    timer = setTimeout(onReleased, 0);
  };
  const down = (event: PointerEvent) => {
    if (event.button !== 0) return;
    clearTimeout(timer);
    pointers.add(event.pointerId);
    root.dataset.pressing = 'true';
  };
  const up = (event: PointerEvent) => {
    if (!pointers.delete(event.pointerId) || pointers.size) return;
    if (event.type === 'pointercancel') release();
    // Touch browsers may deliver click later than pointerup. If scrolling or
    // releasing outside prevents click altogether, do not leave rendering locked.
    else timer = setTimeout(release, 350);
  };
  const click = () => { if (!pointers.size && root.dataset.pressing) release(); };
  const blur = () => { pointers.clear(); release(); };
  root.addEventListener('pointerdown', down, true);
  host.addEventListener('pointerup', up, true);
  host.addEventListener('pointercancel', up, true);
  host.addEventListener('click', click, true);
  host.addEventListener('blur', blur);
  return () => {
    clearTimeout(timer); pointers.clear(); delete root.dataset.pressing;
    root.removeEventListener('pointerdown', down, true);
    host.removeEventListener('pointerup', up, true);
    host.removeEventListener('pointercancel', up, true);
    host.removeEventListener('click', click, true);
    host.removeEventListener('blur', blur);
  };
}
