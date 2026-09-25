import { t } from './i18n';
import { afterEach, expect, it, onTestFinished, vi } from 'vitest';
import { AudioCall, transportRouteFromStats } from './media';
import type { SignalEvent } from './model';

afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });

it('keeps blocked voice playback actionable after transport connects and resumes on a gesture', async () => {
  const blocked = vi.fn();
  const h = await receivingCall(false, blocked);
  h.player.play.mockRejectedValueOnce(new DOMException('blocked', 'NotAllowedError'));
  h.incoming(track('audio'));
  await vi.waitFor(() => expect(blocked).toHaveBeenLastCalledWith(true));
  const stream = h.player.srcObject;
  h.peer.connectionState = 'connected';
  h.peer.onconnectionstatechange!();
  expect(blocked).toHaveBeenLastCalledWith(true);
  h.player.play.mockClear();
  const resumed = h.call.resumeAudio();
  expect(h.player.play).toHaveBeenCalledOnce();
  await resumed;
  expect(blocked).toHaveBeenLastCalledWith(false);
  expect(h.player.srcObject).toBe(stream);
});

it('applies mute selected while microphone capture is pending', async () => {
  const track = { enabled: true, stop: vi.fn() };
  let resolve!: (stream: unknown) => void;
  vi.stubGlobal('navigator', { mediaDevices: {
    getUserMedia: () => new Promise(r => { resolve = r; }),
    enumerateDevices: async () => [],
  } });
  const audio = { pause: vi.fn(), srcObject: null } as unknown as HTMLMediaElement;
  const call = new AudioCall(true, audio, vi.fn(), vi.fn(), vi.fn());
  const capture = call.capture();
  call.mute(true);
  resolve({ getAudioTracks: () => [track], getTracks: () => [track] });
  await capture;
  expect(track.enabled).toBe(false);
  call.mute(false);
  expect(track.enabled).toBe(true);
  call.close();
});

it.each([true, false])('refreshes TURN credentials before expiry (caller=%s)', async caller => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-09-15T12:00:00Z'));
  const h = await receivingCall(caller);
  await h.call.receive({ id: 'config-2', call_id: 'call', type: 'rtc.config', sent_at: Date.now(), payload: {
    restart_id: 'generation-2', ice_servers: [
      { urls: 'stun:example.org' },
      { urls: 'turn:example.org', username: 'user', credential: 'secret', expires_at: '2026-09-15T12:10:00Z' },
    ],
  } });
  h.send.mockClear();
  await vi.advanceTimersByTimeAsync(539_999);
  expect(h.send).not.toHaveBeenCalled();
  await vi.advanceTimersByTimeAsync(1);
  expect(h.send).toHaveBeenCalledExactlyOnceWith(caller ? 'rtc.restart' : 'rtc.restart.request');
  h.call.close();
  expect(vi.getTimerCount()).toBe(0);
});

it('replaces the TURN refresh deadline on new configuration and cancels it on hangup', async () => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-09-15T12:00:00Z'));
  const h = await receivingCall();
  const config = (id: string, expires_at: string) => h.call.receive({ id, call_id: 'call', type: 'rtc.config', sent_at: Date.now(), payload: {
    restart_id: id, ice_servers: [{ urls: 'turn:example.org', username: 'user', credential: id, expires_at }],
  } });
  await config('first', '2026-09-15T12:10:00Z');
  await vi.advanceTimersByTimeAsync(300_000);
  await config('second', '2026-09-15T12:15:00Z');
  await vi.advanceTimersByTimeAsync(240_000);
  expect(h.send).not.toHaveBeenCalled();
  await vi.advanceTimersByTimeAsync(300_000);
  expect(h.send).toHaveBeenCalledExactlyOnceWith('rtc.restart.request');
  await config('third', '2026-09-15T12:25:00Z');
  h.call.close();
  await vi.advanceTimersByTimeAsync(600_000);
  expect(h.send).toHaveBeenCalledTimes(1);
  expect(vi.getTimerCount()).toBe(0);
});

it('retries a second connection loss after the restart cooldown instead of dropping it', async () => {
  vi.useFakeTimers();
  vi.setSystemTime(100_000);
  const h = await receivingCall();
  const state = (value: RTCPeerConnectionState) => {
    h.peer.connectionState = value;
    h.peer.onconnectionstatechange!();
  };
  state('disconnected');
  await vi.advanceTimersByTimeAsync(2000);
  expect(h.send).toHaveBeenCalledExactlyOnceWith('rtc.restart.request');
  state('connected');
  await vi.advanceTimersByTimeAsync(1000);
  state('disconnected');
  await vi.advanceTimersByTimeAsync(10_000);
  expect(h.send.mock.calls.filter(([type]) => type === 'rtc.restart.request')).toHaveLength(2);
  state('connected');
  await vi.advanceTimersByTimeAsync(30_000);
  expect(h.failed).not.toHaveBeenCalled();
  h.call.close();
  expect(vi.getTimerCount()).toBe(0);
});

it.each(['connected', 'closed'] as const)('cancels a deferred restart when the call is %s', async result => {
  vi.useFakeTimers();
  vi.setSystemTime(100_000);
  const h = await receivingCall();
  h.call.restart();
  await vi.advanceTimersByTimeAsync(1000);
  h.call.restart();
  if (result === 'closed') h.call.close();
  else {
    h.peer.connectionState = 'connected';
    h.peer.onconnectionstatechange!();
  }
  await vi.advanceTimersByTimeAsync(20_000);
  expect(h.send.mock.calls.filter(([type]) => type === 'rtc.restart.request')).toHaveLength(1);
});

it('selects the call output without awaiting statistics or resetting the incoming stream', async () => {
  vi.useFakeTimers();
  const h = await receivingCall();
  const waitingStats = deferred<RTCStatsReport>();
  Object.assign(h.peer, { getStats: () => waitingStats.promise });
  const audio = Object.assign(h.player, {
    paused: false, sinkId: 'speaker',
    setSinkId: vi.fn(async (id: string) => { audio.sinkId = id; }),
  });
  const receivedTrack = track('audio');
  h.incoming(receivedTrack);
  const stream = audio.srcObject;
  const selecting = h.call.setAudioOutput('receiver');
  // An unresolved getStats must not push the native call out of the button tap.
  expect(audio.setSinkId).toHaveBeenCalledExactlyOnceWith('receiver');
  await selecting;
  expect(audio.srcObject).toBe(stream);
  h.call.close();
  expect(vi.getTimerCount()).toBe(0);
});

it('buffers ICE until the matching remote SDP, including a restart with fresh TURN credentials', async () => {
  vi.useFakeTimers();
  const stop = vi.fn();
  vi.stubGlobal('navigator', { mediaDevices: { getUserMedia: async () => ({ getTracks: () => [{ stop }], getAudioTracks: () => [] }) } });
  const peer = {
    addTrack: vi.fn(), setConfiguration: vi.fn(), addIceCandidate: vi.fn(), close: vi.fn(),
    setRemoteDescription: vi.fn(), createAnswer: async () => ({ type: 'answer', sdp: 'answer' }),
    setLocalDescription: vi.fn(), localDescription: { sdp: 'answer' },
  };
  vi.stubGlobal('RTCPeerConnection', class { constructor() { return peer; } });
  const audio = { pause: vi.fn(), srcObject: null } as unknown as HTMLAudioElement;
  const call = new AudioCall(false, audio, vi.fn(), vi.fn(), vi.fn());
  const event = (type: string, payload = {}, id = 'event'): SignalEvent => ({ id, call_id: 'call', type, sent_at: Date.now(), payload });
  await call.capture();
  await call.receive(event('rtc.ice', { candidate: 'first' }));
  await call.receive(event('rtc.config', { ice_servers: [] }));
  expect(peer.addIceCandidate).not.toHaveBeenCalled();
  await call.receive(event('rtc.offer', { sdp: 'offer' }));
  expect(peer.addIceCandidate).toHaveBeenCalledTimes(1);
  await call.receive(event('rtc.restart', {}, 'generation-2'));
  const servers = [{ urls: 'turn:example.org', username: 'fresh', credential: 'new' }];
  await call.receive(event('rtc.config', { ice_servers: servers, restart_id: 'generation-2' }));
  expect(peer.setConfiguration).toHaveBeenCalledWith({ iceServers: servers });
  await call.receive(event('rtc.ice', { candidate: 'old' }));
  await call.receive(event('rtc.ice', { candidate: 'new', restart_id: 'generation-2' }));
  expect(peer.addIceCandidate).toHaveBeenCalledTimes(1);
  await call.receive(event('rtc.offer', { sdp: 'new-offer' }));
  expect(peer.addIceCandidate).toHaveBeenCalledTimes(2);
  expect(peer.addIceCandidate).toHaveBeenLastCalledWith(expect.objectContaining({ candidate: 'new' }));
  call.close();
  expect(stop).toHaveBeenCalledOnce();
  expect(peer.close).toHaveBeenCalledOnce();
  expect(vi.getTimerCount()).toBe(0);
});

it('releases microphone permission results that arrive after hangup', async () => {
  const stop = vi.fn();
  let resolve!: (stream: unknown) => void;
  vi.stubGlobal('navigator', { mediaDevices: { getUserMedia: () => new Promise(r => { resolve = r; }) } });
  const call = new AudioCall(true, { pause: vi.fn() } as unknown as HTMLAudioElement, vi.fn(), vi.fn(), vi.fn());
  const capturing = call.capture();
  call.close();
  resolve({ getTracks: () => [{ stop }] });
  await capturing;
  expect(stop).toHaveBeenCalledOnce();
});

it('detects whether the active ICE route is direct or TURN', () => {
  expect(transportRouteFromStats(statsReport([
    ['transport', { type: 'transport', selectedCandidatePairId: 'pair' }],
    ['pair', { type: 'candidate-pair', localCandidateId: 'local', remoteCandidateId: 'remote' }],
    ['local', { type: 'local-candidate', candidateType: 'host' }],
    ['remote', { type: 'remote-candidate', candidateType: 'srflx' }],
  ]))).toBe('direct');

  expect(transportRouteFromStats(statsReport([
    ['pair', { type: 'candidate-pair', nominated: true, state: 'succeeded', localCandidateId: 'local', remoteCandidateId: 'remote' }],
    ['local', { type: 'local-candidate', candidateType: 'relay' }],
    ['remote', { type: 'remote-candidate', candidateType: 'srflx' }],
  ]))).toBe('turn');

  expect(transportRouteFromStats(statsReport([
    ['pair', { type: 'candidate-pair', nominated: true, state: 'in-progress' }],
  ]))).toBe('unknown');
});

it('negotiates a video transceiver and publishes local camera state', async () => {
  const audioTrack = track('audio');
  const videoTrack = track('video');
  const microphoneStream = mediaStream([audioTrack], []);
  const getUserMedia = vi.fn(async (constraints: MediaStreamConstraints) => constraints.video
    ? mediaStream([videoTrack], [videoTrack])
    : microphoneStream);
  vi.stubGlobal('navigator', { mediaDevices: { getUserMedia } });
  const videoSender = {
    replaceTrack: vi.fn(async () => undefined),
    getParameters: vi.fn(() => ({ encodings: [{}] })),
    setParameters: vi.fn(async () => undefined),
  };
  const transceiver = {
    sender: videoSender,
    receiver: { track: track('video') },
    currentDirection: 'sendrecv',
    direction: 'sendrecv',
  };
  const peer = {
    addTrack: vi.fn(),
    addTransceiver: vi.fn(() => transceiver),
    createOffer: async () => ({ type: 'offer', sdp: 'offer' }),
    setLocalDescription: vi.fn(),
    localDescription: { sdp: 'offer' },
    close: vi.fn(),
  };
  vi.stubGlobal('RTCPeerConnection', class { constructor() { return peer; } });
  const send = vi.fn();
  const call = new AudioCall(true, { pause: vi.fn(), srcObject: null } as unknown as HTMLAudioElement, send, vi.fn(), vi.fn());
  const event = (type: string, payload = {}, id = 'event'): SignalEvent => ({ id, call_id: 'call', type, sent_at: Date.now(), payload });
  await call.capture();
  await call.receive(event('rtc.config', { ice_servers: [], video_allowed: true }));
  expect(peer.addTrack).toHaveBeenCalledWith(audioTrack, microphoneStream);
  expect(peer.addTransceiver).toHaveBeenCalledWith('video', {
    direction: 'sendrecv', streams: [microphoneStream],
    sendEncodings: [{ maxBitrate: 4_000_000, maxFramerate: 30 }],
  });
  expect(send).toHaveBeenCalledWith('rtc.offer', { sdp: 'offer' });
  await call.setVideoRequested(true);
  expect(getUserMedia).toHaveBeenLastCalledWith(expect.objectContaining({
    audio: false,
    video: expect.objectContaining({
      width: { min: 1280, ideal: 1920 },
      height: { min: 720, ideal: 1080 },
      frameRate: { min: 24, ideal: 30, max: 30 },
    }),
  }));
  expect(videoSender.replaceTrack).toHaveBeenCalledWith(videoTrack);
  expect(videoSender.setParameters).toHaveBeenLastCalledWith(expect.objectContaining({
    encodings: [expect.objectContaining({ maxBitrate: 4_000_000, maxFramerate: 30 })],
    degradationPreference: 'maintain-framerate',
  }));
  expect(videoTrack.contentHint).toBe('motion');
  expect(send).toHaveBeenLastCalledWith('rtc.video', { enabled: true });
  await call.setVideoRequested(false);
  expect(videoSender.replaceTrack).toHaveBeenLastCalledWith(null);
  expect(send).toHaveBeenLastCalledWith('rtc.video', { enabled: false });
});

it('publishes camera switch availability only when the browser reports multiple cameras', async () => {
  const audioTrack = track('audio');
  const videoStates: ReturnType<AudioCall['videoState']>[] = [];
  vi.stubGlobal('navigator', {
    mediaDevices: {
      getUserMedia: async () => mediaStream([audioTrack], []),
      enumerateDevices: async () => [
        mediaDevice('videoinput', 'front'),
        mediaDevice('videoinput', 'back'),
        mediaDevice('audioinput', 'microphone'),
      ],
    },
  });
  const call = new AudioCall(true, { pause: vi.fn() } as unknown as HTMLAudioElement, vi.fn(), vi.fn(), vi.fn(), {
    video: state => videoStates.push(state),
  });
  await call.capture();
  expect(videoStates.at(-1)?.canSwitchCamera).toBe(true);
  call.close();
});

it('answers an incoming video offer using the negotiated remote transceiver', async () => {
  const audioTrack = track('audio');
  const microphoneStream = mediaStream([audioTrack], []);
  vi.stubGlobal('navigator', { mediaDevices: { getUserMedia: async () => microphoneStream } });
  const negotiationOrder: string[] = [];
  const transceiver = {
    sender: {
      replaceTrack: vi.fn(async () => undefined),
      setStreams: vi.fn(() => { negotiationOrder.push('streams'); }),
    },
    receiver: { track: track('video') },
    currentDirection: 'sendrecv',
    direction: 'recvonly',
  };
  const peer = {
    addTrack: vi.fn(),
    addTransceiver: vi.fn(),
    getTransceivers: vi.fn(() => [transceiver]),
    setConfiguration: vi.fn(),
    setRemoteDescription: vi.fn(),
    createAnswer: async () => { negotiationOrder.push('answer'); return { type: 'answer', sdp: 'answer' }; },
    setLocalDescription: vi.fn(),
    localDescription: { sdp: 'answer' },
    close: vi.fn(),
  };
  vi.stubGlobal('RTCPeerConnection', class { constructor() { return peer; } });
  const send = vi.fn();
  const call = new AudioCall(false, { pause: vi.fn(), srcObject: null } as unknown as HTMLAudioElement, send, vi.fn(), vi.fn());
  const event = (type: string, payload = {}, id = 'event'): SignalEvent => ({ id, call_id: 'call', type, sent_at: Date.now(), payload });
  await call.capture();
  await call.receive(event('rtc.config', { ice_servers: [], video_allowed: true }));
  await call.receive(event('rtc.offer', { sdp: 'offer' }));
  expect(peer.addTransceiver).not.toHaveBeenCalled();
  expect(peer.getTransceivers).toHaveBeenCalled();
  expect(transceiver.direction).toBe('sendrecv');
  expect(transceiver.sender.setStreams).toHaveBeenCalledExactlyOnceWith(microphoneStream);
  expect(negotiationOrder).toEqual(['streams', 'answer']);
  expect(send).toHaveBeenCalledWith('rtc.answer', { sdp: 'answer' });
});

it.each(['audio-first', 'video-first'])('keeps original received audio separate from video with %s delivery', async order => {
  const { call, peer, player, incoming } = await receivingCall();
  const audio = track('audio'), video = track('video');
  const tracks = order === 'audio-first' ? [audio, video] : [video, audio];
  incoming(tracks[0]);
  const firstStream = player.srcObject;
  if (order === 'video-first') {
    expect(firstStream).toBeNull();
    expect(player.play).not.toHaveBeenCalled();
  }
  incoming(tracks[1]);

  const playbackStream = player.srcObject as MediaStream;
  if (order === 'audio-first') expect(playbackStream).toBe(firstStream);
  expect(playbackStream.getTracks()).toEqual([audio]);
  expect(call.videoState().remoteStream?.getTracks()).toEqual([video]);
  expect(call.videoState().remoteStream).not.toBe(playbackStream);
  expect(player.play).toHaveBeenCalledOnce();
  expect(peer.close).not.toHaveBeenCalled();
  expect(audio.stop).not.toHaveBeenCalled();
  expect(video.stop).not.toHaveBeenCalled();
});

it('keeps a dormant remote video out of audio playback before camera activation', async () => {
  const { call, player, incoming } = await receivingCall();
  const audio = track('audio'), dormantVideo = Object.assign(track('video'), { muted: false });
  incoming(audio);
  const playbackStream = player.srcObject;
  player.play.mockClear();
  incoming(dormantVideo);

  // A negotiated live video track can exist before any frames or camera-on signal.
  expect(call.videoState().remoteSending).toBe(false);
  expect(call.videoState().remoteStream?.getTracks()).toEqual([dormantVideo]);
  expect(player.srcObject).toBe(playbackStream);
  expect((player.srcObject as MediaStream).getTracks()).toEqual([audio]);
  expect(player.play).not.toHaveBeenCalled();
  expect(player.pause).not.toHaveBeenCalled();
});

it('retains the remote video track across camera off and on without restarting audio', async () => {
  const { call, player, incoming } = await receivingCall();
  const audio = track('audio'), video = track('video');
  incoming(audio); incoming(video);
  const playbackStream = player.srcObject;
  const videoStream = call.videoState().remoteStream;
  player.play.mockClear();
  for (const enabled of [true, false, true]) {
    await call.receive({ id: 'video', call_id: 'call', type: 'rtc.video', sent_at: Date.now(), payload: { enabled } });
    expect(call.videoState()).toMatchObject({ remoteSending: enabled, remoteStream: videoStream });
    expect(videoStream?.getTracks()).toEqual([video]);
    expect(player.srcObject).toBe(playbackStream);
  }
  expect((player.srcObject as MediaStream).getTracks()).toEqual([audio]);
  expect(player.play).not.toHaveBeenCalled();
  expect(player.pause).not.toHaveBeenCalled();
  expect(video.stop).not.toHaveBeenCalled();
});

it('removes an ended remote video without replacing audio playback or stopping other tracks', async () => {
  const { call, player, incoming, microphone } = await receivingCall();
  const audio = track('audio'), video = track('video');
  incoming(audio); incoming(video);
  const playbackStream = player.srcObject as MediaStream;
  const videoStream = call.videoState().remoteStream;
  expect(videoStream?.getTracks()).toEqual([video]);
  await call.receive({ id: 'video', call_id: 'call', type: 'rtc.video', sent_at: Date.now(), payload: { enabled: true } });
  player.play.mockClear();

  video.onended?.call(video, new Event('ended'));

  expect(player.srcObject).toBe(playbackStream);
  expect(playbackStream.getTracks()).toEqual([audio]);
  expect(call.videoState()).toMatchObject({ remoteSending: false });
  expect(call.videoState().remoteStream).toBeUndefined();
  expect(videoStream?.getTracks()).toEqual([]);
  expect(player.play).not.toHaveBeenCalled();
  expect(player.pause).not.toHaveBeenCalled();
  expect(audio.stop).not.toHaveBeenCalled();
  expect(video.stop).not.toHaveBeenCalled();
  expect(microphone.readyState).toBe('live');
});

it('keeps a replacement remote video when the previous remote track ends', async () => {
  const { call, player, incoming } = await receivingCall();
  const audio = track('audio'), previous = track('video'), replacement = track('video');
  incoming(audio); incoming(previous); incoming(replacement);
  const playbackStream = player.srcObject as MediaStream;
  const videoStream = call.videoState().remoteStream;
  await call.receive({ id: 'video', call_id: 'call', type: 'rtc.video', sent_at: Date.now(), payload: { enabled: true } });
  player.play.mockClear();

  previous.onended?.call(previous, new Event('ended'));

  expect(player.srcObject).toBe(playbackStream);
  expect(playbackStream.getTracks()).toEqual([audio]);
  expect(call.videoState()).toMatchObject({ remoteSending: true, remoteStream: videoStream });
  expect(videoStream?.getTracks()).toEqual([replacement]);
  expect(player.play).not.toHaveBeenCalled();
  expect(player.pause).not.toHaveBeenCalled();
  expect(replacement.stop).not.toHaveBeenCalled();
});

it('releases both remote streams and their callbacks on hangup without stopping received tracks', async () => {
  const { call, player, incoming } = await receivingCall();
  const audio = track('audio'), video = track('video');
  incoming(audio); incoming(video);
  const queuedVideoEnded = video.onended;
  call.close();
  expect(audio.onended).toBeNull();
  expect(video.onended).toBeNull();
  expect(player.srcObject).toBeNull();
  expect(call.videoState().remoteStream).toBeUndefined();
  player.play.mockClear();
  queuedVideoEnded?.call(video, new Event('ended'));
  incoming(track('audio'));
  expect(player.srcObject).toBeNull();
  expect(player.play).not.toHaveBeenCalled();
  expect(audio.stop).not.toHaveBeenCalled();
  expect(video.stop).not.toHaveBeenCalled();
});

it('clears discarded remote video callbacks when video permission is removed', async () => {
  const { call, player, incoming } = await receivingCall();
  const audio = track('audio'), video = track('video');
  incoming(audio); incoming(video);
  const playbackStream = player.srcObject;
  await call.receive({ id: 'config-2', call_id: 'call', type: 'rtc.config', sent_at: Date.now(), payload: { video_allowed: false, ice_servers: [], restart_id: 'generation-2' } });
  expect(video.onended).toBeNull();
  expect(call.videoState().remoteStream).toBeUndefined();
  expect(player.srcObject).toBe(playbackStream);
  expect((player.srcObject as MediaStream).getTracks()).toEqual([audio]);
});

it('keeps sending the new camera when iOS delivers the previous camera ended event during replacement', async () => {
  const { call, sender, front, back, send } = await cameraCall();
  await call.setVideoRequested(true);
  send.mockClear();
  sender.replaceTrack.mockImplementation(async next => {
    sender.track = next;
    if (next === back) {
      // iOS ends the previous camera when opening another one. Its queued
      // event can arrive after replaceTrack, before the new preview is stored.
      front.onended?.call(front, new Event('ended'));
    }
  });

  await call.switchCamera();

  expect(call.videoState()).toMatchObject({ requested: true, sending: true, facing: 'back' });
  expect(call.videoState().localStream?.getVideoTracks()).toEqual([back]);
  expect(sender.track).toBe(back);
  expect(back.readyState).toBe('live');
  expect(send).not.toHaveBeenCalledWith('rtc.video', { enabled: false });
});

it('does not resume video if the camera is turned off while the new track is being attached', async () => {
  const { call, sender, back, send } = await cameraCall();
  await call.setVideoRequested(true);
  const attaching = deferred<void>();
  const attached = deferred<void>();
  sender.replaceTrack.mockImplementation(async next => {
    if (next === back) {
      attaching.resolve();
      await attached.promise;
    }
    sender.track = next;
  });
  const switching = call.switchCamera();
  await attaching.promise;
  send.mockClear();
  const stopping = call.setVideoRequested(false);
  attached.resolve();
  await Promise.all([switching, stopping]);

  expect(call.videoState()).toMatchObject({ requested: false, sending: false });
  expect(call.videoState().localStream).toBeUndefined();
  expect(sender.track).toBeNull();
  expect(back.readyState).toBe('ended');
  expect(send).not.toHaveBeenCalledWith('rtc.video', { enabled: true });
});

it('releases a camera attached after the call was hung up', async () => {
  const { call, sender, back, send } = await cameraCall();
  await call.setVideoRequested(true);
  const attaching = deferred<void>();
  const attached = deferred<void>();
  sender.replaceTrack.mockImplementation(async next => {
    if (next === back) {
      attaching.resolve();
      await attached.promise;
    }
    sender.track = next;
  });
  const switching = call.switchCamera();
  await attaching.promise;
  call.close();
  send.mockClear();
  attached.resolve();
  await switching;

  expect(call.videoState().localStream).toBeUndefined();
  expect(back.readyState).toBe('ended');
  expect(send).not.toHaveBeenCalledWith('rtc.video', { enabled: true });
});

it('releases the captured camera if attaching it to WebRTC fails', async () => {
  const { call, sender, back, video } = await cameraCall();
  await call.setVideoRequested(true);
  sender.replaceTrack.mockImplementation(async next => {
    if (next === back) throw new Error('Camera attachment failed');
    sender.track = next;
  });
  await expect(call.switchCamera()).rejects.toMatchObject({ context: 'camera' });

  expect(back.readyState).toBe('ended');
  expect(sender.track).toBeNull();
  expect(video).toHaveBeenLastCalledWith(expect.objectContaining({ requested: false, sending: false, failure: t('text_could_not_turn_on_the_camera_172') }));
});

it('serializes rapid camera switches and sends only the most recently requested camera', async () => {
  const { call, sender, front, back, getUserMedia, send } = await cameraCall();
  await call.setVideoRequested(true);
  const openingBack = deferred<void>();
  const openedBack = deferred<MediaStream>();
  const nextFront = track('video');
  getUserMedia.mockImplementationOnce(async () => {
    expect(front.readyState).toBe('ended');
    expect(sender.track).toBeNull();
    openingBack.resolve();
    return openedBack.promise;
  }).mockResolvedValueOnce(mediaStream([nextFront], [nextFront]));
  send.mockClear();
  const firstSwitch = call.switchCamera();
  await openingBack.promise;
  const secondSwitch = call.switchCamera();
  openedBack.resolve(mediaStream([back], [back]));
  await Promise.all([firstSwitch, secondSwitch]);

  expect(call.videoState()).toMatchObject({ facing: 'front', requested: true, sending: true });
  expect(call.videoState().localStream?.getVideoTracks()).toEqual([nextFront]);
  expect(sender.track).toBe(nextFront);
  expect(sender.replaceTrack).not.toHaveBeenCalledWith(back);
  expect(back.readyState).toBe('ended');
  expect(nextFront.readyState).toBe('live');
  expect(send.mock.calls).toEqual([['rtc.video', { enabled: true }]]);
});

it('turns video off when the current camera ends unexpectedly, without stopping the microphone', async () => {
  const { call, front, sender, microphone, video, send } = await cameraCall();
  await call.setVideoRequested(true);
  front.onended?.call(front, new Event('ended'));
  await vi.waitFor(() => expect(video).toHaveBeenLastCalledWith(expect.objectContaining({ requested: false, sending: false })));

  expect(sender.track).toBeNull();
  expect(front.readyState).toBe('ended');
  expect(microphone.readyState).toBe('live');
  expect(send).toHaveBeenLastCalledWith('rtc.video', { enabled: false });
});

function screenEvent(presenter = 'bob', ready = false, id = 'share'): SignalEvent {
  return { id: crypto.randomUUID(), call_id: 'call', type: 'rtc.screen', sent_at: Date.now(),
    payload: { enabled: Boolean(presenter), presenter_id: presenter, share_id: id, ready } };
}

async function sharingCall() {
  const h = await cameraCall(true);
  const screen = track('video');
  const getDisplayMedia = vi.fn(async () => mediaStream([screen], [screen]));
  Object.assign(navigator.mediaDevices, { getDisplayMedia });
  return { ...h, screen, getDisplayMedia };
}

it('receives screen sharing without a capture API, releasing the camera before acknowledging readiness', async () => {
  const h = await cameraCall(true);
  await h.call.setVideoRequested(true);
  expect(h.call.videoState().screen.captureSupported).toBe(false);
  const detach = deferred<void>();
  h.sender.replaceTrack.mockImplementationOnce(async next => { await detach.promise; h.sender.track = next; });
  const preparing = h.call.receive(screenEvent());
  await vi.waitFor(() => expect(h.front.readyState).toBe('ended'));
  expect(h.send).not.toHaveBeenCalledWith('rtc.screen.ready', expect.anything());
  detach.resolve();
  await preparing;
  expect(h.send).toHaveBeenLastCalledWith('rtc.screen.ready', { share_id: 'share' });
  await h.call.receive(screenEvent('bob', true));
  await h.call.receive({ ...screenEvent(), type: 'rtc.video', payload: { enabled: true } });
  expect(h.call.videoState()).toMatchObject({ requested: false, sending: false, remoteSending: true, screen: { remote: true, ready: true } });
  await h.call.setVideoRequested(true);
  expect(h.call.videoState().requested).toBe(false);
  expect(h.getUserMedia).toHaveBeenCalledTimes(2); // microphone + initial camera, not screen viewing
  await h.call.receive({ ...screenEvent(), type: 'rtc.video', payload: { enabled: false } });
  await h.call.receive(screenEvent(''));
  expect(h.call.videoState()).toMatchObject({ remoteSending: false, requested: false, screen: { remote: false } });
  expect(h.microphone.readyState).toBe('live');
});

it.each([true, false])('preserves replayed remote video state after a stopped-screen snapshot (camera=%s)', async camera => {
  const h = await cameraCall(true);
  await h.call.receive(screenEvent());
  await h.call.receive(screenEvent('bob', true));
  await h.call.receive({ ...screenEvent(), type: 'rtc.video', payload: { enabled: true } });

  // Resume replays rtc.video first, then supplies the current screen snapshot.
  // The presenter stopped sharing and may have enabled the camera while offline.
  await h.call.receive({ ...screenEvent(), type: 'rtc.video', payload: { enabled: false } });
  if (camera) await h.call.receive({ ...screenEvent(), type: 'rtc.video', payload: { enabled: true } });
  await h.call.receive(screenEvent('', false, 'resume'));
  expect(h.call.videoState()).toMatchObject({ remoteSending: camera, screen: { remote: false, ready: false } });
  expect(h.microphone.readyState).toBe('live');
});

it('preserves replayed video when the first screen snapshot is already ready', async () => {
  const h = await cameraCall(true);
  await h.call.receive({ ...screenEvent(), type: 'rtc.video', payload: { enabled: true } });
  await h.call.receive(screenEvent('bob', true));
  expect(h.call.videoState()).toMatchObject({ remoteSending: true, screen: { remote: true, ready: true } });
  expect(h.send).toHaveBeenCalledWith('rtc.screen.ready', { share_id: 'share' });
});

it.each([true, false])('stops viewing when video-off arrives before/after screen-off (video first=%s)', async videoFirst => {
  const h = await cameraCall(true);
  await h.call.receive(screenEvent());
  await h.call.receive(screenEvent('bob', true));
  await h.call.receive({ ...screenEvent(), type: 'rtc.video', payload: { enabled: true } });
  const videoOff = { ...screenEvent(), type: 'rtc.video', payload: { enabled: false } };
  const screenOff = screenEvent('');
  for (const event of videoFirst ? [videoOff, screenOff] : [screenOff, videoOff]) await h.call.receive(event);
  expect(h.call.videoState()).toMatchObject({ remoteSending: false, screen: { remote: false, ready: false } });
  expect(h.microphone.readyState).toBe('live');
});

it('requests capture in the gesture but transmits only after both peers are ready, prioritizing text resolution', async () => {
  const h = await sharingCall();
  const start = h.call.startScreen();
  expect(h.getDisplayMedia).toHaveBeenCalledExactlyOnceWith({ video: { frameRate: { ideal: 30, max: 30 } }, audio: false });
  await start;
  const id = h.send.mock.calls.find(([type]) => type === 'rtc.screen')![1].share_id;
  expect(h.screen.enabled).toBe(false);
  expect(h.sender.track).toBeNull();
  await h.call.receive(screenEvent('alice', false, id));
  expect(h.sender.track).toBeNull();
  await h.call.receive(screenEvent('alice', true, id));
  expect(h.sender.track).toBe(h.screen);
  expect(h.screen.enabled).toBe(true);
  expect(h.screen.contentHint).toBe('detail');
  expect(h.sender.setParameters).toHaveBeenLastCalledWith(expect.objectContaining({ degradationPreference: 'maintain-resolution', encodings: [expect.objectContaining({ maxBitrate: 4_000_000, maxFramerate: 30 })] }));
  expect(h.call.videoState().screen.sending).toBe(true);
  h.call.resendVideoState();
  expect(h.send).toHaveBeenLastCalledWith('rtc.video', { enabled: true });
  await h.call.receive(screenEvent('alice', true, id));
  expect(h.getDisplayMedia).toHaveBeenCalledOnce();
  await h.call.stopScreen();
  expect(h.screen.readyState).toBe('ended');
  expect(h.sender.track).toBeNull();
  expect(h.call.videoState().screen.requested).toBe(false);
  expect(h.microphone.readyState).toBe('live');
  await h.call.setVideoRequested(true);
  expect(h.sender.track).toBe(h.front);
  expect(h.sender.setParameters).toHaveBeenLastCalledWith(expect.objectContaining({ degradationPreference: 'maintain-framerate' }));
});

it.each(['hangup', 'cancel', 'remote'] as const)('disposes capture selected after %s while the browser picker was open', async action => {
  const h = await sharingCall();
  const picker = deferred<MediaStream>();
  h.getDisplayMedia.mockReturnValueOnce(picker.promise);
  const start = h.call.startScreen();
  if (action === 'hangup') h.call.close();
  else if (action === 'cancel') await h.call.stopScreen();
  else await h.call.receive(screenEvent());
  picker.resolve(mediaStream([h.screen], [h.screen]));
  await start;
  expect(h.screen.readyState).toBe('ended');
  expect(h.send.mock.calls.filter(([type, payload]) => type === 'rtc.screen' && payload.enabled)).toHaveLength(0);
  expect(h.sender.track).toBeNull();
});

it('treats picker cancellation as cancellation, and capture failure as nonfatal to the audio call', async () => {
  const h = await sharingCall();
  h.getDisplayMedia.mockRejectedValueOnce(new DOMException('denied', 'NotAllowedError'));
  await h.call.startScreen();
  expect(h.call.videoState().screen).toMatchObject({ requested: false, failure: undefined });
  h.getDisplayMedia.mockRejectedValueOnce(new Error('capture failed'));
  await h.call.startScreen();
  expect(h.call.videoState().screen).toMatchObject({ requested: false, failure: 'text_could_not_start_screen_sharing_1' });
  expect(h.microphone.readyState).toBe('live');
});

it('times out preparation, ignores replayed local grants, and never reopens capture without a gesture', async () => {
  vi.useFakeTimers();
  const h = await sharingCall();
  await h.call.startScreen();
  const id = h.send.mock.calls.find(([type]) => type === 'rtc.screen')![1].share_id;
  await vi.advanceTimersByTimeAsync(15_000);
  expect(h.screen.readyState).toBe('ended');
  expect(h.call.videoState().screen.requested).toBe(false);
  await h.call.receive(screenEvent('alice', true, id));
  expect(h.send).toHaveBeenLastCalledWith('rtc.screen', { enabled: false, share_id: id });
  expect(h.getDisplayMedia).toHaveBeenCalledOnce();
  expect(h.sender.track).toBeNull();
  h.call.close();
  expect(vi.getTimerCount()).toBe(0);
});

it('stops screen transmission when the browser ends capture or the server rejects a start', async () => {
  const h = await sharingCall();
  await h.call.startScreen();
  h.call.rejectScreenFromServer('screen_share_busy');
  await vi.waitFor(() => expect(h.call.videoState().screen.requested).toBe(false));
  expect(h.screen.readyState).toBe('ended');
  expect(h.call.videoState().screen.failure).toBe('text_the_other_person_is_already_sharing_their_screen_0');
  const next = track('video');
  h.getDisplayMedia.mockResolvedValueOnce(mediaStream([next], [next]));
  await h.call.startScreen();
  const id = h.send.mock.calls.filter(([type, payload]) => type === 'rtc.screen' && payload.enabled).at(-1)![1].share_id;
  await h.call.receive(screenEvent('alice', true, id));
  next.stop();
  next.onended!(new Event('ended'));
  await vi.waitFor(() => expect(h.call.videoState().screen.requested).toBe(false));
  expect(h.sender.track).toBeNull();
  expect(h.send).toHaveBeenCalledWith('rtc.video', { enabled: false });
});

it('does not resurrect a screen whose sender attachment completed after stop', async () => {
  const h = await sharingCall();
  await h.call.startScreen();
  const id = h.send.mock.calls.find(([type]) => type === 'rtc.screen')![1].share_id;
  await h.call.receive(screenEvent('alice', false, id));
  const attached = deferred<void>();
  h.sender.replaceTrack.mockImplementationOnce(async next => { await attached.promise; h.sender.track = next; });
  const ready = h.call.receive(screenEvent('alice', true, id));
  await vi.waitFor(() => expect(h.sender.replaceTrack).toHaveBeenLastCalledWith(h.screen));
  const stopping = h.call.stopScreen();
  attached.resolve();
  await Promise.all([ready, stopping]);
  expect(h.sender.track).toBeNull();
  expect(h.screen.readyState).toBe('ended');
  expect(h.send).not.toHaveBeenCalledWith('rtc.video', { enabled: true });
});

it('keeps sharing unavailable on old servers and never opens the picker while either camera is on', async () => {
  const old = await cameraCall();
  const capture = vi.fn();
  Object.assign(navigator.mediaDevices, { getDisplayMedia: capture });
  await old.call.startScreen();
  await old.call.receive(screenEvent());
  expect(capture).not.toHaveBeenCalled();
  expect(old.call.videoState().screen.remote).toBe(false);
  const h = await sharingCall();
  await h.call.setVideoRequested(true);
  await h.call.startScreen();
  expect(h.getDisplayMedia).not.toHaveBeenCalled();
  await h.call.setVideoRequested(false);
  await h.call.receive({ ...screenEvent(), type: 'rtc.video', payload: { enabled: true } });
  await h.call.startScreen();
  expect(h.getDisplayMedia).not.toHaveBeenCalled();
});

it('stops and releases a pending camera before granting remote screen readiness', async () => {
  const h = await cameraCall(true);
  const camera = deferred<MediaStream>();
  h.getUserMedia.mockReturnValueOnce(camera.promise);
  const opening = h.call.setVideoRequested(true);
  await vi.waitFor(() => expect(h.getUserMedia).toHaveBeenCalledTimes(2));
  const sharing = h.call.receive(screenEvent());
  expect(h.send).not.toHaveBeenCalledWith('rtc.screen.ready', expect.anything());
  camera.resolve(mediaStream([h.front], [h.front]));
  await Promise.all([opening, sharing]);
  expect(h.front.readyState).toBe('ended');
  expect(h.sender.track).toBeNull();
  expect(h.send).toHaveBeenLastCalledWith('rtc.screen.ready', { share_id: 'share' });
});

async function cameraCall(screenSharing = false) {
  const microphone = track('audio');
  const front = track('video');
  const back = track('video');
  const getUserMedia = vi.fn(async (constraints: MediaStreamConstraints) => {
    if (!constraints.video) return mediaStream([microphone], []);
    const facing = (constraints.video as MediaTrackConstraints).facingMode as ConstrainDOMStringParameters;
    const camera = facing.ideal === 'environment' ? back : front;
    return mediaStream([camera], [camera]);
  });
  vi.stubGlobal('navigator', { mediaDevices: {
    getUserMedia,
    enumerateDevices: async () => [mediaDevice('videoinput', 'front'), mediaDevice('videoinput', 'back')],
  } });
  const sender = {
    track: null as MediaStreamTrack | null,
    replaceTrack: vi.fn(async (next: MediaStreamTrack | null) => { sender.track = next; }),
    getParameters: () => ({ encodings: [{}] }),
    setParameters: vi.fn(async () => undefined),
  };
  const peer = {
    addTrack: vi.fn(),
    addTransceiver: () => ({ sender }),
    createOffer: async () => ({ type: 'offer', sdp: 'offer' }),
    setLocalDescription: vi.fn(),
    localDescription: { sdp: 'offer' },
    close: vi.fn(),
  };
  vi.stubGlobal('RTCPeerConnection', class { constructor() { return peer; } });
  const send = vi.fn();
  const video = vi.fn();
  const call = new AudioCall(true, { pause: vi.fn() } as unknown as HTMLAudioElement, send, vi.fn(), vi.fn(), { video, callerLogin: 'alice', calleeLogin: 'bob' });
  onTestFinished(() => call.close());
  await call.capture();
  await call.receive({ id: 'config', call_id: 'call', type: 'rtc.config', sent_at: Date.now(), payload: { video_allowed: true, screen_sharing_allowed: screenSharing, ice_servers: [] } });
  return { call, sender, front, back, microphone, getUserMedia, send, video };
}

async function receivingCall(caller = false, playbackBlocked = (_blocked: boolean) => {}) {
  class RemoteStream {
    private tracks: MediaStreamTrack[] = [];
    constructor(tracks: MediaStreamTrack[] = []) { this.tracks = [...tracks]; }
    getTracks() { return [...this.tracks]; }
    getAudioTracks() { return this.tracks.filter(track => track.kind === 'audio'); }
    getVideoTracks() { return this.tracks.filter(track => track.kind === 'video'); }
    addTrack(track: MediaStreamTrack) { if (!this.tracks.includes(track)) this.tracks.push(track); }
    removeTrack(track: MediaStreamTrack) { this.tracks = this.tracks.filter(item => item !== track); }
  }
  vi.stubGlobal('MediaStream', RemoteStream);
  const microphone = track('audio');
  vi.stubGlobal('navigator', { mediaDevices: { getUserMedia: async () => mediaStream([microphone], []) } });
  const peer = { addTrack: vi.fn(), setConfiguration: vi.fn(), close: vi.fn(), ontrack: undefined as ((event: RTCTrackEvent) => void) | undefined,
    createOffer: async () => ({ type: 'offer', sdp: 'offer' }), setLocalDescription: vi.fn(), localDescription: { sdp: 'offer' },
    connectionState: 'new' as RTCPeerConnectionState, onconnectionstatechange: undefined as (() => void) | undefined };
  vi.stubGlobal('RTCPeerConnection', class { constructor() { return peer; } });
  const player = { srcObject: null as MediaProvider | null, pause: vi.fn(), play: vi.fn(async () => undefined) };
  const send = vi.fn(), failed = vi.fn();
  const call = new AudioCall(caller, player as unknown as HTMLVideoElement, send, vi.fn(), failed, { playbackBlocked });
  onTestFinished(() => call.close());
  await call.capture();
  await call.receive({ id: 'config', call_id: 'call', type: 'rtc.config', sent_at: Date.now(), payload: { video_allowed: !caller, ice_servers: [] } });
  const incoming = (track: MediaStreamTrack) => peer.ontrack!({ track, streams: [] } as unknown as RTCTrackEvent);
  return { call, peer, player, incoming, microphone, send, failed };
}

function statsReport(entries: [string, Record<string, unknown>][]): RTCStatsReport {
  return new Map(entries) as unknown as RTCStatsReport;
}

function track(kind: 'audio' | 'video'): MediaStreamTrack {
  let readyState: MediaStreamTrackState = 'live';
  return {
    kind, enabled: true, onended: null,
    get readyState() { return readyState; },
    stop: vi.fn(() => { readyState = 'ended'; }),
  } as unknown as MediaStreamTrack;
}

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}

function mediaStream(tracks: MediaStreamTrack[], videoTracks: MediaStreamTrack[]): MediaStream {
  return {
    getTracks: () => tracks,
    getAudioTracks: () => tracks.filter(item => item.kind === 'audio'),
    getVideoTracks: () => videoTracks,
  } as unknown as MediaStream;
}

function mediaDevice(kind: MediaDeviceKind, deviceId: string): MediaDeviceInfo {
  return { deviceId, groupId: 'group', kind, label: deviceId, toJSON: () => ({}) } as MediaDeviceInfo;
}
