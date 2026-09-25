/** Match the Android short, wide layout, using the app viewport rather than the desktop window. */
export function compactLandscape(width: number, height: number): boolean {
  return width >= 600 && height < 600 && width > height;
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
    const wide = compactLandscape(width, height);
    if (root.classList.contains('compact-landscape') === wide) return;
    root.classList.toggle('compact-landscape', wide);
    changed();
  };
  const observer = new ResizeObserver(update);
  observer.observe(root);
  root.addEventListener('focusout', update);
  update();
  return () => { observer.disconnect(); root.removeEventListener('focusout', update); };
}
