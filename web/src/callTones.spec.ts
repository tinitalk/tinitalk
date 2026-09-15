import { afterEach, describe, expect, it, vi } from 'vitest';
import { CallToneController, callToneMode, type CallToneEndReason, type CallToneState } from './callTones';

const outgoing = (phase: CallToneState['phase'], extra: Partial<CallToneState> = {}): CallToneState => ({
  direction: 'outgoing',
  phase,
  connected: false,
  ...extra,
});

const incoming = (phase: CallToneState['phase'], extra: Partial<CallToneState> = {}): CallToneState => ({
  direction: 'incoming',
  phase,
  connected: false,
  ...extra,
});

describe('call tone mode', () => {
  it('uses a delivery attempt tone before normal ringback starts', () => {
    expect(callToneMode(outgoing('connecting'))).toBe('reaching');
    expect(callToneMode(outgoing('ringing'))).toBe('ringing');
    expect(callToneMode(outgoing('active'))).toBe('silent');
    expect(callToneMode(outgoing('active', { connected: true, reconnecting: true }))).toBe('reconnecting');
  });

  it('uses busy for busy and rejected outgoing attempts', () => {
    expect(callToneMode(outgoing('ended', { endReason: 'busy' }))).toBe('busy');
    expect(callToneMode(outgoing('ended', { endReason: 'rejected' }))).toBe('busy');
  });

  it('uses congestion for failed outgoing attempts', () => {
    for (const endReason of ['timed_out', 'failed', 'connection_lost', 'not_in_contacts'] satisfies CallToneEndReason[]) {
      expect(callToneMode(outgoing('ended', { endReason }))).toBe('congestion');
    }
  });

  it('keeps incoming misses silent and completed conversations audible', () => {
    expect(callToneMode(incoming('ringing'))).toBe('incoming');
    expect(callToneMode(incoming('active'))).toBe('silent');
    expect(callToneMode(incoming('ended', { endReason: 'rejected' }))).toBe('silent');
    expect(callToneMode(outgoing('ended', { connected: true, endReason: 'remote_hangup' }))).toBe('ended');
    expect(callToneMode(incoming('ended', { connected: true, endReason: 'local_hangup' }))).toBe('ended');
  });

  it('stays silent when the caller cancels their own unanswered attempt', () => {
    expect(callToneMode(outgoing('ended', { endReason: 'cancelled' }))).toBe('silent');
    expect(callToneMode(outgoing('ended', { endReason: 'local_hangup' }))).toBe('silent');
  });
});

function testSource() {
  return { buffer: null as AudioBuffer | null, loop: false, connect: vi.fn(), disconnect: vi.fn(), start: vi.fn(), stop: vi.fn(), onended: null as (() => void) | null };
}

class TestAudioContext extends EventTarget {
  state = 'running';
  currentTime = 10;
  sampleRate = 8000;
  destination = {};
  sources: ReturnType<typeof testSource>[] = [];
  resume = vi.fn(async () => { this.state = 'running'; });
  suspend = vi.fn(async () => { this.state = 'suspended'; });
  oscillators: ReturnType<typeof testSource>[] = [];
  createGain() {
    return { gain: { setValueAtTime: vi.fn(), linearRampToValueAtTime: vi.fn() }, connect: vi.fn(), disconnect: vi.fn() };
  }
  createOscillator() {
    const source = { ...testSource(), type: 'sine', frequency: { setValueAtTime: vi.fn() } };
    this.oscillators.push(source);
    return source;
  }
  createBuffer(_channels: number, length: number, sampleRate: number) {
    const samples = new Float32Array(length);
    return { getChannelData: () => samples, duration: length / sampleRate };
  }
  createBufferSource() {
    const source = testSource();
    this.sources.push(source);
    return source;
  }
  changeState(state: string) { this.state = state; this.dispatchEvent(new Event('statechange')); }
}

function audioHarness() {
  vi.useFakeTimers();
  const context = new TestAudioContext();
  vi.stubGlobal('window', { AudioContext: class { constructor() { return context; } } });
  return { context, tones: new CallToneController() };
}
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

describe('incoming ringtone playback', () => {
  it('suspends tones during a conversation and ignores control clicks, then rings the next call', async () => {
    const { context, tones } = audioHarness();
    tones.update(incoming('ringing', { expiresAt: Date.now() + 30000 }));
    await Promise.resolve();
    tones.update(incoming('active', { connected: true }));
    await Promise.resolve();
    expect(context.state).toBe('suspended');
    const resumeCalls = context.resume.mock.calls.length;
    tones.unlock();
    tones.unlock();
    tones.resumeOnForeground();
    await Promise.resolve();
    expect(context.resume).toHaveBeenCalledTimes(resumeCalls);
    expect(context.sources).toHaveLength(1);
    tones.close();
    tones.update(incoming('ringing', { expiresAt: Date.now() + 30000 }));
    await Promise.resolve(); await Promise.resolve();
    expect(context.state).toBe('running');
    expect(context.sources).toHaveLength(2);
  });

  it('does not revive the tone context when a pending resume finishes after answering', async () => {
    const { context, tones } = audioHarness();
    context.state = 'suspended';
    let grant!: () => void;
    context.resume.mockImplementation(() => new Promise<void>(resolve => {
      grant = () => { context.state = 'running'; resolve(); };
    }));
    tones.update(incoming('ringing', { expiresAt: Date.now() + 30000 }));
    tones.update(incoming('active', { connected: true }));
    grant();
    await Promise.resolve(); await Promise.resolve();
    expect(context.state).toBe('suspended');
    expect(context.sources).toHaveLength(0);
  });

  it('releases idle priming without losing the context needed for the next incoming call', async () => {
    const { context, tones } = audioHarness();
    tones.unlock();
    await Promise.resolve(); await Promise.resolve();
    expect(context.state).toBe('suspended');
    tones.update(incoming('ringing', { expiresAt: Date.now() + 30000 }));
    await Promise.resolve(); await Promise.resolve();
    expect(context.sources).toHaveLength(1);
  });

  it('resumes tones even while an earlier suspension is still pending', async () => {
    const { context, tones } = audioHarness();
    tones.update(incoming('ringing', { expiresAt: Date.now() + 30000 }));
    await Promise.resolve();
    let suspended!: () => void;
    context.suspend.mockImplementation(() => new Promise<void>(resolve => {
      suspended = () => { context.state = 'suspended'; resolve(); };
    }));
    tones.update(incoming('active', { connected: true }));
    // Native state may still say running while the suspend command is queued.
    context.resume.mockImplementation(async () => { suspended(); context.state = 'running'; });
    tones.update(incoming('ringing', { expiresAt: Date.now() + 30000 }));
    await Promise.resolve(); await Promise.resolve();
    expect(context.resume).toHaveBeenCalledOnce();
    expect(context.state).toBe('running');
    expect(context.sources).toHaveLength(2);
  });

  it('plays reconnecting signals and releases their context again when voice returns', async () => {
    const { context, tones } = audioHarness();
    tones.update(outgoing('connecting'));
    await Promise.resolve();
    expect(context.sources).toHaveLength(1);
    tones.update(outgoing('active', { connected: true }));
    await Promise.resolve();
    expect(context.state).toBe('suspended');
    tones.update(outgoing('active', { connected: true, reconnecting: true }));
    await Promise.resolve(); await Promise.resolve();
    expect(context.sources).toHaveLength(2);
    tones.update(outgoing('active', { connected: true }));
    await Promise.resolve();
    expect(context.state).toBe('suspended');
    expect(vi.getTimerCount()).toBe(0);
  });

  it.each(['busy', 'congestion'] as const)('releases the context when the finite %s signal finishes', async mode => {
    const { context, tones } = audioHarness();
    tones.updateMode(mode);
    await vi.advanceTimersByTimeAsync(0);
    expect(context.oscillators.length).toBeGreaterThan(0);
    context.oscillators.at(-1)!.onended!();
    expect(context.state).toBe('suspended');
    expect(vi.getTimerCount()).toBe(0);
  });

  it.each(['local_hangup', 'remote_hangup', 'connection_lost'] as const)('plays a quiet complete end signal after delayed audio resume: %s', async endReason => {
    const { context, tones } = audioHarness();
    const gains: ReturnType<TestAudioContext['createGain']>[] = [];
    const createGain = context.createGain.bind(context);
    vi.spyOn(context, 'createGain').mockImplementation(() => {
      const gain = createGain(); gains.push(gain); return gain;
    });
    context.state = 'suspended';
    let resume!: () => void;
    context.resume.mockImplementation(() => new Promise<void>(resolve => {
      resume = () => { context.state = 'running'; resolve(); };
    }));
    tones.update(outgoing('ended', { connected: true, endReason }));
    await vi.advanceTimersByTimeAsync(600);
    expect(context.oscillators).toHaveLength(0);
    resume();
    await vi.advanceTimersByTimeAsync(0);
    expect(context.oscillators).toHaveLength(2);
    expect(context.oscillators[0].start).toHaveBeenCalledWith(10);
    expect(context.oscillators[1].start).toHaveBeenCalledWith(10.2);
    expect(gains).toHaveLength(2);
    for (const gain of gains) expect(gain.gain.linearRampToValueAtTime.mock.calls[0][0]).toBeCloseTo(0.09);
    await vi.advanceTimersByTimeAsync(1000);
    expect(context.state).toBe('running');
    context.oscillators[1].onended!();
    expect(context.state).toBe('suspended');
    expect(vi.getTimerCount()).toBe(0);
  });

  it('does not play a delayed end signal over a new call', async () => {
    const { context, tones } = audioHarness();
    context.state = 'suspended';
    let resume!: () => void;
    context.resume.mockImplementation(() => new Promise<void>(resolve => {
      resume = () => { context.state = 'running'; resolve(); };
    }));
    tones.updateMode('ended');
    tones.update(outgoing('active', { connected: true }));
    resume();
    await vi.advanceTimersByTimeAsync(0);
    expect(context.oscillators).toHaveLength(0);
    expect(context.state).toBe('suspended');
  });

  it('selects background playback before asking iOS to resume incoming audio', async () => {
    const { context, tones } = audioHarness();
    const session = { type: 'auto' };
    vi.stubGlobal('navigator', { audioSession: session });
    context.state = 'suspended';
    context.resume.mockImplementation(async () => {
      expect(session.type).toBe('playback');
      context.state = 'running';
    });
    tones.update(incoming('ringing', { expiresAt: Date.now() + 30000 }));
    await Promise.resolve(); await Promise.resolve();
    expect(session.type).toBe('playback');
    expect(context.sources).toHaveLength(1);
    tones.close();
    expect(session.type).toBe('auto');
  });

  it.each(['active', 'ended'] as const)('restores the prior audio session synchronously when the incoming call becomes %s', async phase => {
    const { tones } = audioHarness();
    const session = { type: 'auto' };
    vi.stubGlobal('navigator', { audioSession: session });
    tones.update(incoming('ringing', { expiresAt: Date.now() + 30000 }));
    await Promise.resolve();
    expect(session.type).toBe('playback');
    tones.update(incoming(phase, phase === 'ended' ? { endReason: 'cancelled' } : {}));
    expect(session.type).toBe('auto');
  });

  it('releases the playback session when audio expires before the page timer runs', async () => {
    const { context, tones } = audioHarness();
    const session = { type: 'auto' };
    vi.stubGlobal('navigator', { audioSession: session });
    tones.update(incoming('ringing', { expiresAt: Date.now() + 1000 }));
    await Promise.resolve();
    expect(session.type).toBe('playback');
    vi.setSystemTime(Date.now() + 1000);
    context.sources[0].onended!();
    expect(session.type).toBe('auto');
    expect(vi.getTimerCount()).toBe(0);
  });

  it('preserves playback across suspension, retries on foreground and respects the original deadline', async () => {
    const { context, tones } = audioHarness();
    const session = { type: 'auto' };
    vi.stubGlobal('navigator', { audioSession: session });
    tones.update(incoming('ringing', { expiresAt: Date.now() + 10000 }));
    await Promise.resolve();
    context.changeState('suspended');
    expect(session.type).toBe('playback');
    vi.setSystemTime(Date.now() + 6000);
    tones.resumeOnForeground();
    await Promise.resolve(); await Promise.resolve();
    expect(context.resume).toHaveBeenCalledOnce();
    expect(context.sources).toHaveLength(2);
    expect(context.sources[1].stop).toHaveBeenCalledWith(14);
    context.changeState('suspended');
    vi.setSystemTime(Date.now() + 5000);
    tones.resumeOnForeground();
    expect(context.resume).toHaveBeenCalledOnce();
    expect(session.type).toBe('auto');
  });

  it('does not start an audio context just because an idle app became visible', () => {
    const { context, tones } = audioHarness();
    context.state = 'suspended';
    tones.resumeOnForeground();
    expect(context.resume).not.toHaveBeenCalled();
    expect(context.sources).toHaveLength(0);
  });

  it('does not override a recording session or undo a subsequent audio session change', async () => {
    const { tones } = audioHarness();
    const session = { type: 'play-and-record' };
    vi.stubGlobal('navigator', { audioSession: session });
    tones.update(incoming('ringing', { expiresAt: Date.now() + 30000 }));
    await Promise.resolve();
    expect(session.type).toBe('play-and-record');
    tones.close();
    expect(session.type).toBe('play-and-record');
    session.type = 'auto';
    tones.update(incoming('ringing', { expiresAt: Date.now() + 30000 }));
    await Promise.resolve();
    expect(session.type).toBe('playback');
    session.type = 'play-and-record';
    tones.close();
    expect(session.type).toBe('play-and-record');
  });

  it('still plays when the optional audio session setting is rejected', async () => {
    const { context, tones } = audioHarness();
    vi.stubGlobal('navigator', { audioSession: { get type() { return 'auto'; }, set type(_type: string) { throw new Error('unsupported'); } } });
    tones.update(incoming('ringing', { expiresAt: Date.now() + 30000 }));
    await Promise.resolve();
    expect(context.sources).toHaveLength(1);
    tones.close();
  });

  it('repeats a soft melody with a long quiet gap without background JS timers', async () => {
    const { context, tones } = audioHarness();
    const state = incoming('ringing', { expiresAt: Date.now() + 30000 });
    tones.update(state);
    await Promise.resolve();
    tones.update(state);
    const [source] = context.sources;
    expect(context.sources).toHaveLength(1);
    expect(source.loop).toBe(true);
    expect(source.start).toHaveBeenCalledOnce();
    expect(source.stop).toHaveBeenCalledWith(40); // Audio thread enforces the call deadline.
    const samples = source.buffer!.getChannelData(0);
    expect(Math.max(...samples)).toBeGreaterThan(0.15);
    expect(Math.max(...samples)).toBeLessThanOrEqual(0.35);
    expect(samples.slice(context.sampleRate).every(value => value === 0)).toBe(true);
    expect(source.buffer!.duration).toBeGreaterThanOrEqual(4);
    expect(vi.getTimerCount()).toBe(1); // Only expiration, no repeating JS timer.
    tones.close();
  });

  it.each(['active', 'ended'] as const)('stops immediately when the incoming call becomes %s', async phase => {
    const { context, tones } = audioHarness();
    tones.update(incoming('ringing', { expiresAt: Date.now() + 30000 }));
    await Promise.resolve();
    tones.update(incoming(phase, phase === 'ended' ? { endReason: 'cancelled' } : {}));
    expect(context.sources[0].stop).toHaveBeenLastCalledWith();
    expect(context.sources[0].disconnect).toHaveBeenCalledOnce();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('does not start a ringtone after a delayed autoplay grant if the call has ended', async () => {
    const { context, tones } = audioHarness();
    context.state = 'suspended';
    let grant!: () => void;
    context.resume.mockImplementation(() => new Promise<void>(resolve => { grant = () => { context.state = 'running'; resolve(); }; }));
    tones.update(incoming('ringing', { expiresAt: Date.now() + 30000 }));
    tones.update(incoming('ended', { endReason: 'rejected' }));
    grant();
    await Promise.resolve(); await Promise.resolve();
    expect(context.sources).toHaveLength(0);
  });

  it('does not ring an expired call after a suspended page resumes, even before timers run', async () => {
    const { context, tones } = audioHarness();
    tones.update(incoming('ringing', { expiresAt: Date.now() + 1000 }));
    await Promise.resolve();
    context.changeState('suspended');
    expect(context.sources[0].disconnect).toHaveBeenCalledOnce();
    vi.setSystemTime(Date.now() + 2000);
    context.changeState('running');
    tones.unlock();
    await Promise.resolve(); await Promise.resolve();
    expect(context.sources).toHaveLength(1);
    tones.close();
  });

  it('resumes a still pending call with its remaining time, then expires silently', async () => {
    const { context, tones } = audioHarness();
    tones.update(incoming('ringing', { expiresAt: Date.now() + 10000 }));
    await Promise.resolve();
    context.changeState('suspended');
    vi.setSystemTime(Date.now() + 6000);
    context.changeState('running');
    expect(context.sources).toHaveLength(2);
    expect(context.sources[1].stop).toHaveBeenCalledWith(14);
    await vi.advanceTimersByTimeAsync(10000);
    expect(context.sources[1].disconnect).toHaveBeenCalledOnce();
  });
});

describe('status tone timing', () => {
  it('uses one audio-time origin even if the clock advances while nodes are created', async () => {
    const { context, tones } = audioHarness();
    const create = context.createOscillator.bind(context);
    vi.spyOn(context, 'createOscillator').mockImplementation(() => {
      context.currentTime += 0.01;
      return create();
    });
    tones.updateMode('busy');
    await vi.advanceTimersByTimeAsync(0);
    expect(context.oscillators.map(o => o.start.mock.calls[0][0])).toEqual([10, 11, 12]);
  });

  it('does not revive an old terminal signal after background audio suspension', async () => {
    const { context, tones } = audioHarness();
    tones.updateMode('ended');
    await vi.advanceTimersByTimeAsync(0);
    context.changeState('suspended');
    vi.setSystemTime(Date.now() + 10000);
    tones.unlock();
    await vi.advanceTimersByTimeAsync(0);
    expect(context.resume).not.toHaveBeenCalled();
    expect(context.oscillators.every(o => o.disconnect.mock.calls.length > 0)).toBe(true);
  });

  it('expires an unstarted end signal instead of playing it after a late permission grant', async () => {
    const { context, tones } = audioHarness();
    context.state = 'suspended';
    let resume!: () => void;
    context.resume.mockImplementation(() => new Promise<void>(resolve => {
      resume = () => { context.state = 'running'; resolve(); };
    }));
    tones.updateMode('ended');
    vi.setSystemTime(Date.now() + 10000); // Page timers have not run yet.
    resume();
    await vi.advanceTimersByTimeAsync(0);
    expect(context.oscillators).toHaveLength(0);
    expect(context.state).toBe('suspended');
  });

  it('cancels the previous terminal signal as soon as a new call starts', async () => {
    const { context, tones } = audioHarness();
    tones.updateMode('busy');
    await vi.advanceTimersByTimeAsync(0);
    tones.update(outgoing('connecting'));
    await vi.advanceTimersByTimeAsync(0);
    expect(context.oscillators.every(o => o.disconnect.mock.calls.length > 0)).toBe(true);
    expect(context.sources).toHaveLength(1);
  });

  it.each([
    ['busy', [10, 11, 12], 0.5],
    ['congestion', [10, 10.4, 10.8, 11.2, 11.6, 12], 0.2],
  ] as const)('schedules every complete %s beep after one delayed resume', async (mode, starts, duration) => {
    const { context, tones } = audioHarness();
    context.state = 'suspended';
    let resume!: () => void;
    context.resume.mockImplementation(() => new Promise<void>(resolve => {
      resume = () => { context.state = 'running'; resolve(); };
    }));
    tones.updateMode(mode);
    await vi.advanceTimersByTimeAsync(2500);
    expect(context.resume).toHaveBeenCalledOnce();
    expect(context.oscillators).toHaveLength(0);
    resume();
    await vi.advanceTimersByTimeAsync(0);
    expect(context.oscillators).toHaveLength(starts.length);
    for (const [i, start] of starts.entries()) {
      expect(context.oscillators[i].start).toHaveBeenCalledWith(start);
      expect(context.oscillators[i].stop.mock.calls[0][0]).toBeCloseTo(start + duration + 0.012);
    }
    expect(vi.getTimerCount()).toBe(0);
    await vi.advanceTimersByTimeAsync(5000);
    expect(context.state).toBe('running'); // Audio completion, not a page timer, owns cleanup.
    context.oscillators.at(-1)!.onended!();
    expect(context.state).toBe('suspended');
  });

  it.each(['reaching', 'ringing', 'reconnecting'] as const)('repeats %s without page timers', async mode => {
    const { context, tones } = audioHarness();
    tones.updateMode(mode);
    await vi.advanceTimersByTimeAsync(0);
    expect(context.sources).toHaveLength(1);
    const source = context.sources[0];
    expect(source.loop).toBe(true);
    const onSeconds = mode === 'ringing' ? 1 : 0.18;
    expect(source.buffer!.duration).toBe(mode === 'ringing' ? 5 : 4);
    const samples = source.buffer!.getChannelData(0);
    expect(samples.slice(0, onSeconds * context.sampleRate).some(v => Math.abs(v) > 0.2)).toBe(true);
    expect(samples.slice(onSeconds * context.sampleRate).every(v => v === 0)).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
    await vi.advanceTimersByTimeAsync(16000);
    expect(context.sources).toHaveLength(1);
    tones.updateMode('silent');
    expect(source.disconnect).toHaveBeenCalledOnce();
  });

  it('finishes the delivery pulse before ringback even when delivery beats audio resume', async () => {
    const { context, tones } = audioHarness();
    context.state = 'suspended';
    let resume!: () => void;
    context.resume.mockImplementation(() => new Promise<void>(resolve => {
      resume = () => { context.state = 'running'; resolve(); };
    }));
    tones.updateMode('reaching');
    await vi.advanceTimersByTimeAsync(30);
    tones.updateMode('ringing');
    await vi.advanceTimersByTimeAsync(220);
    resume();
    await vi.advanceTimersByTimeAsync(0);
    expect(context.resume).toHaveBeenCalledOnce();
    expect(context.sources).toHaveLength(2);
    expect(context.sources[0].start).toHaveBeenCalledWith(10);
    expect(context.sources[0].stop).toHaveBeenCalledWith(10.18);
    expect(context.sources[1].start).toHaveBeenCalledWith(10.18);
    tones.update(outgoing('active', { connected: true }));
    expect(context.sources.every(s => s.disconnect.mock.calls.length > 0)).toBe(true);
  });

  it.each([[10.1, 10.18], [14.1, 14.18], [14.9, 14.9]])('hands delivery over at audio time %s without cutting a pulse', async (now, expectedStart) => {
    const { context, tones } = audioHarness();
    tones.updateMode('reaching');
    await vi.advanceTimersByTimeAsync(0);
    context.currentTime = now;
    tones.updateMode('ringing');
    expect(context.sources[0].stop).toHaveBeenCalledWith(expectedStart);
    expect(context.sources[1].start).toHaveBeenCalledWith(expectedStart);
  });

});
