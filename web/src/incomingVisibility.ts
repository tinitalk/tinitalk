type IncomingTarget = { accountId: string; callId: string };

// These acknowledgements describe a currently displayed screen, never durable
// signaling. They must stop as soon as the page is hidden or the call is gone.
export class IncomingCallVisibility {
  private timer?: ReturnType<typeof setTimeout>;
  private last?: { target: IncomingTarget; visible: boolean };
  private suspended = false;

  constructor(
    private current: () => IncomingTarget | null,
    private isVisible: () => boolean,
    private send: (target: IncomingTarget, visible: boolean) => void,
  ) {}

  refresh(renew = false): void {
    const target = this.current();
    if (!target || this.suspended) {
      this.clearTimer();
      this.last = undefined;
      return;
    }
    const visible = this.isVisible();
    if (renew || this.last?.target.accountId !== target.accountId ||
      this.last?.target.callId !== target.callId || this.last?.visible !== visible) {
      this.send(target, visible);
      this.last = { target, visible };
    }
    if (!visible) this.clearTimer();
    else if (!this.timer) {
      this.timer = setTimeout(() => { this.timer = undefined; this.refresh(true); }, 1000);
    }
  }

  suspend(): void {
    if (this.last?.visible) this.send(this.last.target, false);
    this.suspended = true;
    this.clearTimer();
    this.last = undefined;
  }

  resume(): void { this.suspended = false; this.refresh(true); }

  private clearTimer(): void { clearTimeout(this.timer); this.timer = undefined; }
}
