/** Match Android using the app bounds, not the surrounding desktop window. */
export function landscapeLayout(width: number, height: number): boolean {
  return width >= 600 && width > height;
}

export function compactLandscape(width: number, height: number): boolean {
  return landscapeLayout(width, height) && height < 600;
}

export function cameraFit(width: number, height: number, frameWidth: number, frameHeight: number): 'cover' | 'contain' {
  return frameWidth > 0 && frameHeight > 0 &&
    ((width > height && frameWidth > frameHeight) || (width < height && frameWidth < frameHeight)) ? 'cover' : 'contain';
}

export function previewSize(width: number, height: number, availableWidth: number, availableHeight: number) {
  const aspect = width > 0 && height > 0 ? width / height : 9 / 16;
  const w = aspect >= 1 ? 150 : 150 * aspect;
  const h = w / aspect;
  const scale = Math.min(1, Math.max(1, availableWidth) / w, Math.max(1, availableHeight) / h);
  return { width: w * scale, height: h * scale };
}

/** Keyboard resizing must not turn a portrait form into a two-pane form. */
export function observeAdaptiveLayout(root: HTMLElement, changed: () => void): () => void {
  let lastWidth = 0;
  const update = () => {
    const { width, height } = root.getBoundingClientRect();
    const editing = document.activeElement?.matches('input, textarea, [contenteditable=true]');
    if (editing && Math.abs(width - lastWidth) < 2) return;
    lastWidth = width;
    const wide = landscapeLayout(width, height);
    const compact = compactLandscape(width, height);
    if (root.classList.contains('landscape-layout') === wide &&
        root.classList.contains('compact-landscape') === compact) return;
    root.classList.toggle('landscape-layout', wide);
    root.classList.toggle('compact-landscape', compact);
    changed();
  };
  const observer = new ResizeObserver(update);
  observer.observe(root);
  root.addEventListener('focusout', update);
  update();
  return () => { observer.disconnect(); root.removeEventListener('focusout', update); };
}
