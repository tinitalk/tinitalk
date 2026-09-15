import { afterEach, expect, it, vi } from 'vitest';
import { IncomingCallVisibility } from './incomingVisibility';

afterEach(() => { vi.useRealTimers(); });

it('renews only a displayed incoming call and releases immediately when hidden', () => {
  vi.useFakeTimers();
  let call: { accountId: string; callId: string } | null = { accountId: 'family', callId: 'call-1' };
  let visible = true;
  const sent: unknown[] = [];
  const state = new IncomingCallVisibility(() => call, () => visible, (target, visible) => sent.push({ ...target, visible }));
  state.refresh();
  state.refresh(); // A rerender must not create more timers or flood acknowledgements.
  expect(sent).toEqual([{ accountId: 'family', callId: 'call-1', visible: true }]);
  vi.advanceTimersByTime(2100);
  expect(sent).toHaveLength(3);
  visible = false;
  state.refresh();
  expect(sent.at(-1)).toEqual({ accountId: 'family', callId: 'call-1', visible: false });
  vi.advanceTimersByTime(5000);
  expect(sent).toHaveLength(4);
  expect(vi.getTimerCount()).toBe(0);
  visible = true;
  state.refresh();
  expect(sent.at(-1)).toEqual({ accountId: 'family', callId: 'call-1', visible: true });
  call = null; // Answered, rejected, expired, or call UI closed.
  state.refresh();
  expect(vi.getTimerCount()).toBe(0);
});

it('never renews from a delayed callback after the page becomes hidden or the call changes', () => {
  vi.useFakeTimers();
  let visible = true;
  let call = { accountId: 'first-family', callId: 'old-call' };
  const sent: unknown[] = [];
  const state = new IncomingCallVisibility(() => call, () => visible, (target, visible) => sent.push({ ...target, visible }));
  state.refresh();
  visible = false; // Even if visibilitychange is delayed, timer must recheck.
  vi.advanceTimersByTime(1000);
  expect(sent.at(-1)).toEqual({ ...call, visible: false });
  visible = true;
  call = { accountId: 'second-family', callId: 'new-call' };
  state.refresh();
  expect(sent.at(-1)).toEqual({ ...call, visible: true });
  state.suspend(); // pagehide / freezing must release even if visible still says true.
  expect(sent.at(-1)).toEqual({ ...call, visible: false });
  expect(vi.getTimerCount()).toBe(0);
  state.refresh();
  expect(vi.getTimerCount()).toBe(0);
  state.resume();
  expect(sent.at(-1)).toEqual({ ...call, visible: true });
  state.suspend();
});
