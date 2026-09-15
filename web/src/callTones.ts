export type CallToneDirection = 'incoming' | 'outgoing';
export type CallTonePhase = 'connecting' | 'ringing' | 'active' | 'ended';
export type CallToneEndReason =
  | 'busy'
  | 'rejected'
  | 'timed_out'
  | 'failed'
  | 'connection_lost'
  | 'not_in_contacts'
  | 'cancelled'
  | 'local_hangup'
  | 'remote_hangup';
export type CallToneMode = 'silent' | 'incoming' | 'reaching' | 'ringing' | 'reconnecting' | 'busy' | 'congestion' | 'ended';

export type CallToneState = {
  direction: CallToneDirection;
  phase: CallTonePhase;
  connected: boolean;
  reconnecting?: boolean;
  endReason?: CallToneEndReason;
  expiresAt?: number;
};

const failureReasons = new Set<CallToneEndReason>(['timed_out', 'failed', 'connection_lost', 'not_in_contacts']);

export function callToneMode(state: CallToneState): CallToneMode {
  if (state.phase === 'ended' && state.endReason === 'busy') return 'busy';
  if (state.phase === 'ended' && state.connected) return 'ended';
  if (state.phase === 'ended' && state.direction === 'outgoing' && state.endReason === 'rejected') return 'busy';
  if (state.phase === 'ended' && state.direction === 'outgoing' && state.endReason && failureReasons.has(state.endReason)) return 'congestion';
  if (state.phase === 'active' && state.reconnecting) return 'reconnecting';
  if (state.direction === 'outgoing' && state.phase === 'connecting') return 'reaching';
  if (state.direction === 'outgoing' && state.phase === 'ringing') return 'ringing';
  if (state.direction === 'incoming' && state.phase === 'ringing') return 'incoming';
  return 'silent';
}

type AudioContextFactory = new () => AudioContext;
type WebKitAudioWindow = Window & typeof globalThis & { webkitAudioContext?: AudioContextFactory };
type AudioSessionControl = { type: string };

const toneVolume = 0.9;
const toneFadeSeconds = 0.012;
const pulseToneMillis = 180;
const pulseToneIntervalMillis = 4_000;
const terminalStartTimeoutMillis = 3_000;

export class CallToneController {
  private context?: AudioContext;
  private mode: CallToneMode = 'silent';
  private timers = new Set<ReturnType<typeof setTimeout>>();
  private oscillators = new Set<OscillatorNode>();
  private gains = new Set<GainNode>();
  private generation = 0;
  private loopSources = new Set<AudioBufferSourceNode>();
  private progressSource?: AudioBufferSourceNode;
  private progressStartedAt = 0;
  private terminalPlaybackExpiresAt = 0;
  private incomingExpiresAt = 0;
  private incomingBuffer?: AudioBuffer;
  private incomingSource?: AudioBufferSourceNode;
  private incomingSession?: { session: AudioSessionControl; previousType: string };
  private conversationActive = false;
  private suspension?: Promise<void>;

  unlock(): void {
    if (this.conversationActive && this.mode === 'silent') return;
    // Call synchronously from an ordinary tap/key press, before any await.
    void this.resumeContext().then(context => this.playIncomingTone(context)).catch(() => undefined);
  }

  resumeOnForeground(): void {
    if (this.expireTerminalPlayback()) return;
    if (this.mode !== 'incoming') return;
    if (this.incomingExpiresAt <= Date.now()) this.updateMode('silent');
    else this.unlock();
  }

  update(state: CallToneState): void {
    this.conversationActive = state.phase === 'active';
    this.updateMode(callToneMode(state), state.expiresAt);
  }

  updateMode(next: CallToneMode, expiresAt = this.mode === 'incoming' ? this.incomingExpiresAt : Date.now() + 45_000): void {
    if (next === 'incoming' && expiresAt <= Date.now()) next = 'silent';
    if (next === this.mode && (next !== 'incoming' || expiresAt === this.incomingExpiresAt)) {
      if (next === 'silent') this.suspendContext();
      return;
    }
    if (this.mode === 'reaching' && next === 'ringing') {
      this.mode = 'ringing';
      if (this.context && this.progressSource) this.transitionToRingback(this.context);
      // If resume is pending, its callback schedules the delivery pulse followed
      // by ringback on the same audio clock. Never launch a second resume/pulse.
      return;
    }
    this.stopScheduled();
    this.mode = next;
    this.incomingExpiresAt = next === 'incoming' ? expiresAt : 0;
    switch (next) {
      case 'incoming': {
        const generation = this.generation;
        void this.resumeContext().then(context => {
          if (generation === this.generation) this.playIncomingTone(context);
        }).catch(() => undefined);
        this.setTimer(() => this.updateMode('silent'), Math.max(0, expiresAt - Date.now()));
        break;
      }
      case 'reaching':
      case 'reconnecting':
        this.startPromptPulses(next);
        break;
      case 'ringing':
        this.startRingback();
        break;
      case 'busy':
        this.startFiniteTone([425], 500, 500, 3);
        break;
      case 'congestion':
        this.startFiniteTone([425], 200, 200, 6);
        break;
      case 'ended':
        this.startFiniteTone([1200], 100, 100, 2, 0.09);
        break;
      case 'silent':
        this.suspendContext();
        break;
    }
  }

  close(): void {
    this.stopScheduled();
    this.mode = 'silent';
    this.conversationActive = false;
    this.suspendContext();
  }

  idle(): void {
    // Closing/redrawing the UI does not own a terminal signal's lifetime.
    // A new call uses update(), which still cancels any previous signal.
    if (this.mode !== 'ended' && this.mode !== 'busy' && this.mode !== 'congestion') this.updateMode('silent');
  }

  private playIncomingTone(context?: AudioContext): void {
    if (!context || context.state !== 'running' || this.mode !== 'incoming' || this.incomingSource) return;
    const remaining = (this.incomingExpiresAt - Date.now()) / 1000;
    if (remaining <= 0) { this.updateMode('silent'); return; }
    // A soft C–E chime lasting <1 second, then ~4 seconds of silence. The audio
    // thread repeats it even when a hidden page's JS timers are throttled.
    if (!this.incomingBuffer) {
      const buffer = context.createBuffer(1, context.sampleRate * 5, context.sampleRate);
      const samples = buffer.getChannelData(0);
      for (const [offset, frequency] of [[0, 523.25], [0.38, 659.25]]) {
        const start = Math.round(offset * context.sampleRate);
        const length = Math.round(0.52 * context.sampleRate);
        for (let i = 0; i < length; i++) {
          const t = i / context.sampleRate;
          const attack = Math.min(1, t / 0.035);
          const release = Math.max(0, 1 - t / 0.52) ** 2;
          samples[start + i] += 0.32 * attack * release * Math.sin(2 * Math.PI * frequency * t);
        }
      }
      this.incomingBuffer = buffer;
    }
    const source = context.createBufferSource();
    source.buffer = this.incomingBuffer;
    source.loop = true;
    source.connect(context.destination);
    this.incomingSource = source;
    source.onended = () => {
      source.disconnect();
      if (this.incomingSource === source) {
        this.incomingSource = undefined;
        this.updateMode('silent');
      }
    };
    source.start();
    // Bound playback on the audio thread too, independent of page timers.
    source.stop(context.currentTime + remaining);
  }

  private stopIncomingTone(): void {
    const source = this.incomingSource;
    this.incomingSource = undefined;
    if (!source) return;
    source.onended = null;
    try { source.stop(); } catch { /* already stopped */ }
    source.disconnect();
  }

  private beginIncomingSession(): void {
    if (this.incomingSession || typeof navigator === 'undefined') return;
    try {
      const session = (navigator as Navigator & { audioSession?: AudioSessionControl }).audioSession;
      if (!session || session.type === 'playback' || session.type === 'play-and-record') return;
      const previousType = session.type;
      // Safari's default Web Audio category stops on backgrounding. Request
      // playback only for the pending ringtone, before resuming its context.
      session.type = 'playback';
      this.incomingSession = { session, previousType };
    } catch { /* Optional API: keep ordinary playback in other browsers. */ }
  }

  private endIncomingSession(): void {
    const owned = this.incomingSession;
    this.incomingSession = undefined;
    if (!owned) return;
    try {
      // Do not overwrite a session subsequently changed by another audio user.
      if (owned.session.type === 'playback') owned.session.type = owned.previousType;
    } catch { /* The browser may have already discarded the session. */ }
  }

  private startPromptPulses(mode: 'reaching' | 'reconnecting'): void {
    const generation = this.generation;
    void this.resumeContext().then(context => {
      if (!context || generation !== this.generation) return;
      this.progressStartedAt = context.currentTime;
      this.progressSource = this.scheduleLoop(context, [400, 1200], pulseToneMillis,
        pulseToneIntervalMillis - pulseToneMillis, context.currentTime);
      if (mode === 'reaching' && this.mode === 'ringing') this.transitionToRingback(context);
    }).catch(() => undefined);
  }

  private transitionToRingback(context: AudioContext): void {
    const interval = pulseToneIntervalMillis / 1000;
    const cycle = Math.floor(Math.max(0, context.currentTime - this.progressStartedAt) / interval);
    const pulseEnd = this.progressStartedAt + cycle * interval + pulseToneMillis / 1000;
    const startAt = Math.max(context.currentTime, pulseEnd);
    this.progressSource?.stop(startAt);
    this.progressSource = undefined;
    this.scheduleLoop(context, [425], 1_000, 4_000, startAt);
  }

  private startRingback(): void {
    const generation = this.generation;
    void this.resumeContext().then(context => {
      if (!context || generation !== this.generation) return;
      this.scheduleLoop(context, [425], 1_000, 4_000, context.currentTime);
    }).catch(() => undefined);
  }

  private scheduleLoop(context: AudioContext, frequencies: number[], onMillis: number,
    offMillis: number, startAt: number): AudioBufferSourceNode {
    const buffer = context.createBuffer(1, Math.round(context.sampleRate * (onMillis + offMillis) / 1000), context.sampleRate);
    const samples = buffer.getChannelData(0);
    const duration = onMillis / 1000;
    for (let i = 0; i < Math.round(context.sampleRate * duration); i++) {
      const t = i / context.sampleRate;
      const envelope = Math.max(0, Math.min(1, t / toneFadeSeconds, (duration - t) / toneFadeSeconds));
      for (const frequency of frequencies) {
        samples[i] += toneVolume / frequencies.length * envelope * Math.sin(2 * Math.PI * frequency * t);
      }
    }
    const source = context.createBufferSource();
    source.buffer = buffer;
    source.loop = true;
    source.connect(context.destination);
    this.loopSources.add(source);
    source.onended = () => { this.loopSources.delete(source); source.disconnect(); };
    source.start(startAt);
    return source;
  }

  private startFiniteTone(frequencies: number[], onMillis: number, offMillis: number,
    count: number, volume = toneVolume): void {
    const generation = this.generation;
    const startDeadline = Date.now() + terminalStartTimeoutMillis;
    // Expire an unstarted signal; once audible, only audio completion ends it.
    this.setTimer(() => this.updateMode('silent'), terminalStartTimeoutMillis);
    void this.resumeContext().then(context => {
      if (!context || generation !== this.generation) return;
      if (Date.now() >= startDeadline) { this.updateMode('silent'); return; }
      this.clearTimers();
      const startAt = context.currentTime;
      this.terminalPlaybackExpiresAt = Date.now() + (count - 1) * (onMillis + offMillis) + onMillis + toneFadeSeconds * 1000;
      for (let i = 0; i < count; i++) {
        this.scheduleTone(context, frequencies, onMillis,
          startAt + i * (onMillis + offMillis) / 1000, volume,
          i === count - 1 ? () => {
            if (generation === this.generation) this.updateMode('silent');
          } : undefined);
      }
    }).catch(() => undefined);
  }

  private scheduleTone(context: AudioContext, frequencies: number[], durationMillis: number,
    startAt: number, volume: number, onended?: () => void): void {
    const stopAt = startAt + durationMillis / 1000;
    const gain = context.createGain();
    this.gains.add(gain);
    const perFrequencyVolume = volume / Math.max(1, frequencies.length);
    gain.gain.setValueAtTime(0, startAt);
    gain.gain.linearRampToValueAtTime(perFrequencyVolume, startAt + toneFadeSeconds);
    gain.gain.setValueAtTime(perFrequencyVolume, Math.max(startAt, stopAt - toneFadeSeconds));
    gain.gain.linearRampToValueAtTime(0, stopAt);
    gain.connect(context.destination);
    const toneOscillators = new Set<OscillatorNode>();

    for (const frequency of frequencies) {
      const oscillator = context.createOscillator();
      oscillator.type = 'sine';
      oscillator.frequency.setValueAtTime(frequency, startAt);
      oscillator.connect(gain);
      this.oscillators.add(oscillator);
      toneOscillators.add(oscillator);
      oscillator.onended = () => {
        this.oscillators.delete(oscillator);
        toneOscillators.delete(oscillator);
        oscillator.disconnect();
        if (!toneOscillators.size) {
          this.gains.delete(gain);
          gain.disconnect();
          onended?.();
        }
      };
      oscillator.start(startAt);
      oscillator.stop(stopAt + toneFadeSeconds);
    }
  }

  private audioContext(): AudioContext | undefined {
    if (this.context) return this.context;
    const audioWindow = window as WebKitAudioWindow;
    const Factory = audioWindow.AudioContext ?? audioWindow.webkitAudioContext;
    if (!Factory) return undefined;
    this.context = new Factory();
    this.context.addEventListener('statechange', () => {
      // Audio time pauses while suspended. Drop the old source so resuming
      // cannot revive an expired call or extend its original deadline.
      if (this.context?.state !== 'running') this.stopIncomingTone();
      else if (!this.expireTerminalPlayback()) this.playIncomingTone(this.context);
    });
    return this.context;
  }

  private async resumeContext(): Promise<AudioContext | undefined> {
    if (this.expireTerminalPlayback()) return undefined;
    const context = this.audioContext();
    if (!context) return undefined;
    if (this.mode === 'incoming' && this.incomingExpiresAt > Date.now()) this.beginIncomingSession();
    // A pending suspend may not have changed state yet. Queue resume directly
    // (inside the gesture when available), after that native suspend command.
    if (context.state !== 'running' || this.suspension) await context.resume();
    // Answering/hanging up can happen while resume waits for autoplay. Never
    // leave a late grant running alongside voice, or after the signal ended.
    if (this.mode === 'silent') {
      this.suspendContext();
      return undefined;
    }
    return context;
  }

  private suspendContext(): void {
    const context = this.context;
    if (!context || context.state === 'suspended' || context.state === 'closed' || this.suspension) return;
    // Keep the gesture-unlocked context for later calls, but release its audio
    // hardware while no tones are needed. Never process remote voice here.
    const pending = context.suspend();
    this.suspension = pending;
    const settled = () => {
      if (this.suspension === pending) this.suspension = undefined;
    };
    void pending.then(settled, settled);
  }

  private expireTerminalPlayback(): boolean {
    if (!this.terminalPlaybackExpiresAt || Date.now() < this.terminalPlaybackExpiresAt) return false;
    // Audio time may have frozen while the page was backgrounded. Do not resume
    // the tail of an obsolete signal on a later tap or foreground transition.
    this.updateMode('silent');
    return true;
  }

  private setTimer(callback: () => void, delayMillis: number): void {
    const timer = setTimeout(() => {
      this.timers.delete(timer);
      callback();
    }, delayMillis);
    this.timers.add(timer);
  }

  private clearTimers(): void {
    for (const timer of this.timers) clearTimeout(timer);
    this.timers.clear();
  }

  private stopScheduled(): void {
    this.generation += 1;
    this.terminalPlaybackExpiresAt = 0;
    this.clearTimers();
    this.stopIncomingTone();
    this.endIncomingSession();
    this.progressSource = undefined;
    for (const source of this.loopSources) {
      source.onended = null;
      try { source.stop(); } catch { /* already stopped */ }
      source.disconnect();
    }
    this.loopSources.clear();
    for (const oscillator of this.oscillators) {
      try {
        oscillator.onended = null;
        oscillator.stop();
        oscillator.disconnect();
      } catch {
        // The oscillator may already be stopped by the browser audio thread.
      }
    }
    this.oscillators.clear();
    for (const gain of this.gains) {
      try {
        gain.disconnect();
      } catch {
        // The gain may already be disconnected after the last oscillator ended.
      }
    }
    this.gains.clear();
  }
}
