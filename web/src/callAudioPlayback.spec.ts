import { afterEach, expect, it, vi } from 'vitest';
import { CallAudioPlayback } from './callAudioPlayback';

const activePlaybacks: CallAudioPlayback[] = [];
afterEach(() => {
  activePlaybacks.splice(0).forEach(playback => playback.close());
  vi.unstubAllGlobals();
});

it('plays the original WebRTC audio track on the supplied media element', async () => {
  const h = setup();
  await h.playback.attach(h.remote);
  expect(h.media.srcObject).toBe(h.remote);
  expect(h.media.srcObject?.getTracks()).toEqual([h.audioTrack]);
  expect(h.media.paused).toBe(false);
  expect(h.media.muted).toBe(false);
  expect(h.events).toEqual([['src', h.remote], ['play']]);
});

it('never creates a processing graph, another decoder, a stream copy or microphone capture', async () => {
  const h = setup();
  const contextCreated = vi.fn();
  const context = {
    state: 'running', createMediaStreamDestination: () => ({ stream: stream(), disconnect() {} }),
    createMediaStreamSource: () => ({ connect() {}, disconnect() {} }), close: async () => {},
  };
  vi.stubGlobal('AudioContext', class { constructor() { contextCreated(); return context; } });
  const createdStream = vi.fn();
  vi.stubGlobal('MediaStream', class {
    constructor(private tracks: MediaStreamTrack[]) { createdStream(); }
    getTracks() { return this.tracks; }
    getAudioTracks() { return this.tracks.filter(track => track.kind === 'audio'); }
  });
  await h.playback.attach(h.remote);
  await h.playback.setOutputDevice('receiver');
  expect(contextCreated).not.toHaveBeenCalled();
  expect(createdStream).not.toHaveBeenCalled();
  expect(h.createElement).not.toHaveBeenCalled();
  expect(h.capture).not.toHaveBeenCalled();
  expect(h.media.srcObject).toBe(h.remote);
  expect(h.audioTrack.enabled).toBe(true);
});

it('does not reset srcObject for the same stream, including changes to its tracks', async () => {
  const h = setup();
  await h.playback.attach(h.remote);
  h.events.length = 0;
  h.remote.addTrack(track('audio'));
  await h.playback.attach(h.remote);
  expect(h.events).toEqual([['play']]);
  expect(h.media.srcObject).toBe(h.remote);
  expect(h.media.srcObject?.getAudioTracks()).toHaveLength(2);
});

it('replaces an original stream without changing the selected sink or stopping remote tracks', async () => {
  const h = setup();
  await h.playback.attach(h.remote);
  await h.playback.setOutputDevice('receiver');
  const replacement = stream();
  h.events.length = 0;
  await h.playback.attach(replacement);
  expect(h.events).toEqual([['src', replacement], ['play']]);
  expect(h.media.srcObject).toBe(replacement);
  expect(h.media.sinkId).toBe('receiver');
  expect(h.audioTrack.stop).not.toHaveBeenCalled();
});

it('calls native selection synchronously and keeps the original stream playing', async () => {
  const h = setup();
  await h.playback.attach(h.remote);
  const finish = pendingSelection(h);
  const selecting = h.playback.setOutputDevice('receiver');
  expect(h.events).toEqual([['sink', 'receiver']]);
  expect(h.media.srcObject).toBe(h.remote);
  finish();
  await selecting;
  expect(h.events).toEqual([['sink', 'receiver']]);
  expect(h.media.srcObject).toBe(h.remote);
  expect(h.media.sinkId).toBe('receiver');
});

it('leaves a newly attached stream untouched when an earlier output selection completes', async () => {
  const h = setup();
  await h.playback.attach(h.remote);
  const finish = pendingSelection(h);
  const selecting = h.playback.setOutputDevice('receiver');
  const replacement = stream();
  await h.playback.attach(replacement);
  h.events.length = 0;
  finish();
  await selecting;
  expect(h.events).toEqual([]);
  expect(h.media.srcObject).toBe(replacement);
});

it('does not restore or play a stream detached while selection was pending', async () => {
  const h = setup();
  await h.playback.attach(h.remote);
  const finish = pendingSelection(h);
  const selecting = h.playback.setOutputDevice('receiver');
  h.media.srcObject = null;
  h.events.length = 0;
  finish();
  await selecting;
  expect(h.events).toEqual([]);
  expect(h.media.srcObject).toBeNull();
});

it('retains the original audio track through repeated output changes', async () => {
  const h = setup();
  await h.playback.attach(h.remote);
  h.events.length = 0;
  for (let index = 0; index < 12; index++) {
    await h.playback.setOutputDevice(index % 2 ? 'speaker' : 'receiver');
    expect(h.media.srcObject).toBe(h.remote);
    expect(h.media.srcObject?.getTracks()).toEqual([h.audioTrack]);
  }
  expect(h.events).toEqual(Array.from({ length: 12 }, (_, index) => ['sink', index % 2 ? 'speaker' : 'receiver']));
  expect(h.audioTrack.stop).not.toHaveBeenCalled();
});

it('keeps a running original stream uninterrupted and resumes only when paused', async () => {
  const h = setup();
  await h.playback.attach(h.remote);
  h.events.length = 0;
  await h.playback.setOutputDevice('headphones');
  expect(h.events).toEqual([['sink', 'headphones']]);
  expect(h.media.srcObject).toBe(h.remote);
  h.media.paused = true;
  h.events.length = 0;
  await h.playback.setOutputDevice('speaker');
  expect(h.events).toEqual([['sink', 'speaker'], ['play']]);
  expect(h.media.srcObject).toBe(h.remote);
});

it.each(['reject', 'throw'])('does not interrupt playback when native selection fails by %s', async failure => {
  const h = setup();
  await h.playback.attach(h.remote);
  h.events.length = 0;
  const error = new DOMException('Output unavailable', 'NotFoundError');
  h.media.setSinkId.mockImplementationOnce(() => { if (failure === 'throw') throw error; return Promise.reject(error); });
  await expect(h.playback.setOutputDevice('receiver')).rejects.toBe(error);
  expect(h.events).toEqual([]);
  expect(h.media.srcObject).toBe(h.remote);
  expect(h.media.paused).toBe(false);
  await h.playback.setOutputDevice('speaker');
  expect(h.media.sinkId).toBe('speaker');
});

it('keeps playback usable when output selection is unsupported', async () => {
  const h = setup();
  Object.assign(h.media, { setSinkId: undefined });
  await h.playback.attach(h.remote);
  h.events.length = 0;
  await expect(h.playback.setOutputDevice('receiver')).rejects.toMatchObject({ name: 'NotSupportedError' });
  expect(h.events).toEqual([]);
  expect(h.media.srcObject).toBe(h.remote);
});

it('coalesces overlapping selections instead of queuing calls outside their gestures', async () => {
  const h = setup();
  await h.playback.attach(h.remote);
  const finish = pendingSelection(h);
  const first = h.playback.setOutputDevice('receiver');
  const second = h.playback.setOutputDevice('speaker');
  expect(second).toBe(first);
  expect(h.events).toEqual([['sink', 'receiver']]);
  finish();
  await Promise.all([first, second]);
  expect(h.media.setSinkId).toHaveBeenCalledOnce();
  await h.playback.setOutputDevice('speaker');
  expect(h.media.setSinkId).toHaveBeenCalledTimes(2);
});

it('does not revive an ended call when selection finishes after hangup', async () => {
  const h = setup();
  await h.playback.attach(h.remote);
  const finish = pendingSelection(h);
  const selecting = h.playback.setOutputDevice('receiver');
  h.playback.close();
  h.events.length = 0;
  finish();
  await selecting;
  expect(h.events).toEqual([]);
  expect(h.media.srcObject).toBeNull();
  expect(h.media.paused).toBe(true);
});

it('allows sink selection before a stream arrives without attempting playback', async () => {
  const h = setup();
  await h.playback.setOutputDevice('receiver');
  expect(h.events).toEqual([['sink', 'receiver']]);
  await h.playback.resume();
  expect(h.events).toEqual([['sink', 'receiver']]);
  await h.playback.attach(h.remote);
  expect(h.media.sinkId).toBe('receiver');
});

it('surfaces blocked playback recovery and lets a later gesture resume the original stream', async () => {
  const h = setup();
  await h.playback.attach(h.remote);
  h.media.paused = true;
  h.media.play.mockRejectedValueOnce(new DOMException('Playback blocked', 'NotAllowedError'));
  await expect(h.playback.setOutputDevice('receiver')).rejects.toMatchObject({ name: 'NotAllowedError' });
  expect(h.media.srcObject).toBe(h.remote);
  await h.playback.resume();
  expect(h.media.paused).toBe(false);
});

it('closes once without stopping remote tracks and preserves the sink for the next call', async () => {
  const h = setup();
  await h.playback.attach(h.remote);
  await h.playback.setOutputDevice('receiver');
  h.events.length = 0;
  h.playback.close(); h.playback.close();
  await h.playback.attach(stream());
  await h.playback.setOutputDevice('speaker');
  await h.playback.resume();
  expect(h.events).toEqual([['pause'], ['src', null]]);
  expect(h.media.sinkId).toBe('receiver');
  expect(h.audioTrack.stop).not.toHaveBeenCalled();
  const next = new CallAudioPlayback(h.media as unknown as HTMLMediaElement);
  activePlaybacks.push(next);
  const replacement = stream();
  await next.attach(replacement);
  expect(h.media.srcObject).toBe(replacement);
  expect(h.media.sinkId).toBe('receiver');
});

function track(kind: 'audio' | 'video') {
  return { kind, enabled: true, readyState: 'live', stop: vi.fn() } as unknown as MediaStreamTrack;
}

function stream(tracks = [track('audio')]) {
  return {
    getTracks: () => [...tracks], getAudioTracks: () => tracks.filter(track => track.kind === 'audio'),
    getVideoTracks: () => tracks.filter(track => track.kind === 'video'), addTrack: (track: MediaStreamTrack) => tracks.push(track),
  } as unknown as MediaStream;
}

function setup() {
  const capture = vi.fn();
  vi.stubGlobal('navigator', { mediaDevices: { getUserMedia: capture } });
  vi.stubGlobal('AudioContext', undefined);
  const events: unknown[][] = [];
  let attached: MediaStream | null = null;
  const media = {
    get srcObject() { return attached; },
    set srcObject(value: MediaStream | null) { events.push(['src', value]); attached = value; },
    sinkId: '', paused: true, muted: false,
    play: vi.fn(async () => { events.push(['play']); media.paused = false; }),
    pause: vi.fn(() => { events.push(['pause']); media.paused = true; }),
    setSinkId: vi.fn(async (id: string) => { events.push(['sink', id]); media.sinkId = id; }),
  };
  const helper = { srcObject: null, paused: true, muted: false, play: async () => { helper.paused = false; }, pause() {}, remove() {}, setAttribute() {} };
  const createElement = vi.fn(() => helper);
  vi.stubGlobal('document', { createElement, body: { append() {} }, addEventListener() {}, removeEventListener() {} });
  const audioTrack = track('audio');
  const remote = stream([audioTrack]);
  const playback = new CallAudioPlayback(media as unknown as HTMLMediaElement);
  activePlaybacks.push(playback);
  return { playback, media, remote, audioTrack, events, createElement, capture };
}

function pendingSelection(h: ReturnType<typeof setup>) {
  h.events.length = 0;
  let finish!: () => void;
  h.media.setSinkId.mockImplementationOnce(id => {
    h.events.push(['sink', id]);
    return new Promise<void>(resolve => { finish = () => { h.media.sinkId = id; resolve(); }; });
  });
  return () => finish();
}
