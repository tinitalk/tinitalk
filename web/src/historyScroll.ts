import { contactHeaderFrame, contactSnapTarget, type HeaderPose } from './contactScroll';

const clamp = (value: number) => Math.max(0, Math.min(1, value));
const lerp = (from: number, to: number, progress: number) => from + (to - from) * progress;

/** One measured layout per size, compositor transforms per frame — like the native header. */
export function bindHistoryScroll(scroller: HTMLElement, up: HTMLButtonElement, onIdle: () => void): () => void {
  const page = scroller.closest<HTMLElement>('.app-page')!;
  const large = scroller.querySelector<HTMLElement>('.profile-avatar');
  const name = scroller.querySelector<HTMLElement>('.profile-name');
  const small = page.querySelector<HTMLElement>('.compact-avatar');
  const smallName = page.querySelector<HTMLElement>('.compact-contact strong');
  const address = scroller.querySelector<HTMLElement>('.profile-login');
  const contact = Boolean(large && name && small && smallName && address) && !page.closest('.compact-landscape');
  const dates = Array.from(scroller.querySelectorAll<HTMLElement>('.day-label'));
  const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
  const clones: HTMLElement[] = [];
  const clone = (source: HTMLElement | null, className: string) => {
    if (!contact || !source) return null;
    const copy = source.cloneNode(true) as HTMLElement;
    copy.classList.add('contact-morph', className);
    copy.setAttribute('aria-hidden', 'true');
    page.append(copy); clones.push(copy);
    return copy;
  };
  const photo = clone(large, 'morph-photo');
  const expandedName = clone(name, 'morph-name-expanded');
  const compactName = clone(smallName, 'morph-name-compact');
  const spacer = contact ? document.createElement('div') : null;
  if (spacer) {
    spacer.className = 'contact-scroll-space';
    spacer.setAttribute('aria-hidden', 'true');
    scroller.append(spacer);
  }
  let photoFrom: HeaderPose, photoTo: HeaderPose, nameFrom: HeaderPose, nameTo: HeaderPose;
  let expandedInset = {x: 0, y: 0}, compactInset = {x: 0, y: 0};
  let extent = 0, scrollerTop = 0, disposed = false;
  let paintFrame = 0, animationFrame = 0;
  let animationTarget: number | undefined;
  let idleTimer: ReturnType<typeof setTimeout> | undefined;
  let touching = false, mouseDown = false, userScrolling = false, direction = 0;
  let lastOffset = scroller.scrollTop, lastTime = performance.now(), velocity = 0;
  let pinned: HTMLElement | undefined;
  const idle = () => { delete scroller.dataset.interacting; if (!disposed) onIdle(); };

  const paint = () => {
    const offset = scroller.scrollTop;
    up.hidden = offset < (contact ? extent : 300);
    // Read sticky positions before writing styles. Only the visible day's chip gets a background.
    const nextPinned = dates.find(date => {
      const bounds = date.getBoundingClientRect();
      return bounds.top <= scrollerTop + 1 && bounds.bottom > scrollerTop;
    });
    if (nextPinned !== pinned) {
      pinned?.classList.remove('pinned'); nextPinned?.classList.add('pinned'); pinned = nextPinned;
    }
    if (!contact || !extent || !photo || !expandedName || !compactName) return;
    const p = clamp(offset / extent);
    page.classList.toggle('is-collapsed', p >= 1);
    const frame = contactHeaderFrame(photoFrom, photoTo, nameFrom, nameTo, p);
    photo.style.transform = `translate3d(${frame.photo.x}px, ${frame.photo.y}px, 0) scale(${frame.photo.size / photoFrom.width})`;
    const { x, y, scale } = frame.name;
    const blend = clamp((p - .65) / .3);
    const compactScale = scale * nameFrom.font / nameTo.font;
    // Centered multi-line and left-aligned single-line layouts must share a
    // text origin while blending, not merely share their boxes' top-left.
    const dx = expandedInset.x * scale - compactInset.x * compactScale;
    const dy = expandedInset.y * scale - compactInset.y * compactScale;
    expandedName.style.transform = `translate3d(${x - dx * blend}px, ${y - dy * blend}px, 0) scale(${scale})`;
    expandedName.style.opacity = String(1 - blend);
    // Clip only near the compact toolbar; never change text layout during travel.
    const clip = Math.max(0, nameFrom.width - nameTo.width / scale) * p;
    expandedName.style.clipPath = `inset(0 ${clip}px 0 ${Math.max(0, dx / scale * blend)}px)`;
    expandedName.setAttribute('aria-hidden', String(p >= .8));
    compactName.style.transform = `translate3d(${x + dx * (1 - blend)}px, ${y + dy * (1 - blend)}px, 0) scale(${compactScale})`;
    compactName.style.opacity = String(blend);
    compactName.setAttribute('aria-hidden', String(p < .8));
  };
  const schedulePaint = () => {
    if (!paintFrame && !animationFrame) paintFrame = requestAnimationFrame(() => { paintFrame = 0; paint(); });
  };
  const measure = () => {
    if (disposed) return;
    const bounds = page.getBoundingClientRect(), scrollBounds = scroller.getBoundingClientRect();
    scrollerTop = scrollBounds.top;
    // The navigation below the list already includes the device's safe area.
    // Follow its actual top edge instead of adding a second fixed bottom inset.
    if (!contact) up.style.bottom = `${Math.max(8, bounds.bottom - scrollBounds.bottom + 8)}px`;
    if (contact) {
      const pose = (element: HTMLElement, inScroll: boolean): HeaderPose => {
        const rect = element.getBoundingClientRect();
        return { x: rect.left - bounds.left, y: rect.top - bounds.top + (inScroll ? scroller.scrollTop : 0), width: rect.width, height: rect.height, font: parseFloat(getComputedStyle(element).fontSize) };
      };
      photoFrom = pose(large!, true); photoTo = pose(small!, false);
      nameFrom = pose(name!, true); nameTo = pose(smallName!, false);
      const textInset = (element: HTMLElement) => {
        const text = element.firstChild;
        if (!text?.textContent || text.nodeType !== Node.TEXT_NODE) return {x: 0, y: 0};
        const range = document.createRange();
        range.setStart(text, 0); range.setEnd(text, [...text.textContent][0].length);
        const letter = range.getBoundingClientRect(), box = element.getBoundingClientRect();
        return {x: letter.left - box.left, y: letter.top - box.top};
      };
      expandedInset = textInset(name!); compactInset = textInset(smallName!);
      // Dock when the avatar/name block scrolls out, as on Android. Including
      // the call controls here makes the moving name lag behind and overlap them.
      extent = address!.getBoundingClientRect().top - scrollerTop + scroller.scrollTop;
      // Even an empty history needs enough scroll range to reach the toolbar.
      // Measure the spacer's start, not scrollHeight (which floors at clientHeight).
      const contentHeight = spacer!.getBoundingClientRect().top - scrollerTop + scroller.scrollTop + parseFloat(getComputedStyle(scroller).paddingBottom);
      spacer!.style.height = `${Math.max(0, scroller.clientHeight + Math.ceil(extent) - contentHeight)}px`;
      for (const [copy, layout] of [[photo!, photoFrom], [expandedName!, nameFrom], [compactName!, nameTo]] as const) {
        copy.style.width = `${layout.width}px`;
        copy.style.height = `${layout.height}px`;
        copy.style.fontSize = `${layout.font}px`;
      }
    }
    paint();
  };

  const cancelAnimation = () => {
    cancelAnimationFrame(animationFrame); animationFrame = 0;
    animationTarget = undefined;
    clearTimeout(idleTimer);
  };
  const animateTo = (target: number) => {
    cancelAnimation(); userScrolling = false;
    animationTarget = target;
    cancelAnimationFrame(paintFrame); paintFrame = 0;
    scroller.dataset.interacting = 'true';
    const from = scroller.scrollTop, started = performance.now();
    if (reducedMotion.matches) { scroller.scrollTop = target; paint(); animationTarget = undefined; idle(); return; }
    // Critically damped settling: no overshoot, no sudden linear stop at the toolbar.
    const duration = 650;
    const curve = (t: number) => 1 - (1 + 10 * t) * Math.exp(-10 * t);
    const step = (now: number) => {
      const elapsed = clamp((now - started) / duration);
      scroller.scrollTop = lerp(from, target, curve(elapsed) / curve(1));
      paint();
      if (elapsed < 1) animationFrame = requestAnimationFrame(step);
      else { animationFrame = 0; animationTarget = undefined; lastOffset = scroller.scrollTop; idle(); }
    };
    animationFrame = requestAnimationFrame(step);
  };
  const settle = () => {
    clearTimeout(idleTimer);
    if (touching || mouseDown || animationFrame || !userScrolling) return;
    const target = contact ? contactSnapTarget(scroller.scrollTop, extent, scroller.scrollHeight - scroller.clientHeight, direction) : undefined;
    userScrolling = false;
    if (target !== undefined) animateTo(target);
    else idle();
  };
  const scheduleSettle = () => { clearTimeout(idleTimer); idleTimer = setTimeout(settle, 100); };
  const begin = () => {
    const interruptedTarget = animationTarget;
    cancelAnimation(); userScrolling = true;
    direction = interruptedTarget === undefined ? 0 : Math.sign(interruptedTarget - scroller.scrollTop);
    scroller.dataset.interacting = 'true';
    lastOffset = scroller.scrollTop; lastTime = performance.now(); velocity = 0;
  };
  const onScroll = () => {
    if (disposed) return;
    const now = performance.now(), delta = scroller.scrollTop - lastOffset;
    // Native momentum or restored scroll can arrive after touchend/re-render.
    // Do not leave a partial header without an owner to finish settling it.
    if (contact && !animationFrame && !userScrolling && scroller.scrollTop > 0 && scroller.scrollTop < extent && Math.abs(delta) >= .5) {
      userScrolling = true;
      scroller.dataset.interacting = 'true';
    }
    if (userScrolling && Math.abs(delta) >= .5) {
      direction = Math.sign(delta);
      velocity = delta / Math.max(1, now - lastTime);
    }
    lastOffset = scroller.scrollTop; lastTime = now;
    schedulePaint();
    if (userScrolling && !touching && !mouseDown) scheduleSettle();
  };
  const onWheel = (event: WheelEvent) => {
    if (!event.deltaY) return;
    if (!userScrolling || animationFrame) begin();
    direction = Math.sign(event.deltaY); scheduleSettle();
  };
  const onTouchStart = () => { touching = true; begin(); };
  const onTouchEnd = (event: TouchEvent) => {
    if (event.touches.length) return;
    touching = false;
    // A slow released drag settles immediately. A flick keeps its native momentum;
    // if it stops within the header, the idle handler completes it afterwards.
    if (performance.now() - lastTime > 70 || Math.abs(velocity) < .35) settle();
    else scheduleSettle();
  };
  const onPointerDown = (event: PointerEvent) => { if (event.pointerType !== 'touch') { mouseDown = true; begin(); } };
  const onPointerUp = (event: PointerEvent) => {
    if (event.pointerType !== 'touch' && mouseDown) { mouseDown = false; scheduleSettle(); }
  };
  const onKeyDown = (event: KeyboardEvent) => {
    if (['ArrowDown', 'ArrowUp', 'PageDown', 'PageUp', 'Home', 'End', ' '].includes(event.key)) { begin(); scheduleSettle(); }
  };
  const onBlur = () => { touching = false; mouseDown = false; cancelAnimation(); userScrolling = false; idle(); };
  const toTop = () => animateTo(0);
  scroller.addEventListener('scroll', onScroll, {passive:true});
  // Cancel a running snap before the browser applies the wheel delta. With a
  // passive listener a compositor scroll can race the next animation frame.
  // We do not preventDefault: ordinary wheel/trackpad scrolling remains native.
  scroller.addEventListener('wheel', onWheel, {passive:false});
  scroller.addEventListener('touchstart', onTouchStart, {passive:true});
  scroller.addEventListener('touchend', onTouchEnd, {passive:true});
  scroller.addEventListener('touchcancel', onTouchEnd, {passive:true});
  scroller.addEventListener('pointerdown', onPointerDown);
  scroller.addEventListener('keydown', onKeyDown);
  window.addEventListener('pointerup', onPointerUp);
  window.addEventListener('pointercancel', onPointerUp);
  window.addEventListener('blur', onBlur);
  up.addEventListener('click', toTop);
  const resize = new ResizeObserver(measure);
  resize.observe(scroller);
  if (name) resize.observe(name);
  if (smallName) resize.observe(smallName);
  document.fonts.addEventListener('loadingdone', measure);
  measure();
  return () => {
    disposed = true; cancelAnimation(); cancelAnimationFrame(paintFrame); resize.disconnect();
    clones.forEach(copy => copy.remove()); spacer?.remove();
    page.classList.remove('is-collapsed'); pinned?.classList.remove('pinned');
    scroller.removeEventListener('scroll', onScroll); scroller.removeEventListener('wheel', onWheel);
    scroller.removeEventListener('touchstart', onTouchStart); scroller.removeEventListener('touchend', onTouchEnd); scroller.removeEventListener('touchcancel', onTouchEnd);
    scroller.removeEventListener('pointerdown', onPointerDown); scroller.removeEventListener('keydown', onKeyDown);
    window.removeEventListener('pointerup', onPointerUp); window.removeEventListener('pointercancel', onPointerUp); window.removeEventListener('blur', onBlur);
    document.fonts.removeEventListener('loadingdone', measure); up.removeEventListener('click', toTop);
  };
}
