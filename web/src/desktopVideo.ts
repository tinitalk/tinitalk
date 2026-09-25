import { t } from './i18n';

const desktopQuery = '(min-width: 760px) and (hover: hover) and (pointer: fine)';
const expandPath = 'M8 3H3v5 M16 3h5v5 M3 16v5h5 M21 16v5h-5';
const collapsePath = 'M3 8h5V3 M21 8h-5V3 M8 21v-5H3 M16 21v-5h5';

/** Changes only layout: the existing video element, tracks and call stay mounted. */
export class DesktopVideoExpansion {
  private readonly media = window.matchMedia(desktopQuery);
  private readonly button = document.createElement('button');
  private callKey: string | null = null;
  private expanded = false;

  constructor(private readonly onResize: () => void) {
    this.button.type = 'button';
    this.button.className = 'desktop-video-toggle';
    this.button.hidden = true;
    this.button.setAttribute('aria-controls', 'call-layer');
    // Outside #app: the desktop phone intentionally clips all of its children.
    document.body.append(this.button);
    this.button.onclick = () => {
      if (!this.callKey || !this.media.matches) return;
      this.setExpanded(!this.expanded);
    };
    this.media.addEventListener('change', () => {
      if (!this.media.matches) this.setExpanded(false);
      this.render();
    });
    window.addEventListener('keydown', event => {
      if (event.key === 'Escape' && !event.defaultPrevented && this.expanded) {
        this.setExpanded(false);
        this.button.focus({ preventScroll: true });
      }
    });
  }

  update(callKey: string | null): void {
    if (this.callKey !== callKey) this.setExpanded(false);
    this.callKey = callKey;
    this.render();
  }

  private setExpanded(value: boolean): void {
    if (this.expanded === value) return;
    this.expanded = value;
    document.body.classList.toggle('video-expanded', value);
    this.render();
    requestAnimationFrame(this.onResize);
  }

  private render(): void {
    this.button.hidden = !this.callKey || !this.media.matches;
    const label = t(this.expanded ? 'video_collapse' : 'video_expand');
    this.button.title = label;
    this.button.setAttribute('aria-label', label);
    this.button.setAttribute('aria-expanded', String(this.expanded));
    this.button.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="${this.expanded ? collapsePath : expandPath}"/></svg>`;
  }
}
