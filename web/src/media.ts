import type { SignalEvent } from './model';
import {
  CallSASHandshake,
  type CallSecurityFailureReason,
  type CallSecurityState,
  type CallSecurityUnavailableReason,
} from './sas';
import { CallAudioPlayback } from './callAudioPlayback';

export type { CallSecurityState } from './sas';

export type CallTransportRoute = 'unknown' | 'direct' | 'turn';
export type CallVideoFacing = 'front' | 'back';
export type CallVideoState = {
  allowed: boolean;
  requested: boolean;
  sending: boolean;
  remoteSending: boolean;
  canSwitchCamera: boolean;
  facing: CallVideoFacing;
  localStream?: MediaStream;
  remoteStream?: MediaStream;
  failure?: string;
};

type AudioCallOptions = {
  callId?: string;
  callerLogin?: string;
  calleeLogin?: string;
  security?: (state: CallSecurityState) => void;
  transportRoute?: (route: CallTransportRoute) => void;
  video?: (state: CallVideoState) => void;
};

const securityCodeTimeoutMs = 30_000;
const routePollMs = 3_000;
const videoMaxBitrate = 4_000_000;
const videoCaptureWidth = 1920;
const videoCaptureHeight = 1080;
const videoCaptureFps = 30;
const videoCaptureMinWidth = 1280;
const videoCaptureMinHeight = 720;

type RTCStatsLike = Record<string, unknown> & { id?: string; type?: string };

export class AudioCall {
  private playback: CallAudioPlayback;
  private peer?: RTCPeerConnection;
  private stream?: MediaStream;
  private remoteAudioStream?: MediaStream;
  private localVideoStream?: MediaStream;
  private localVideoTrack?: MediaStreamTrack;
  private remoteVideoStream?: MediaStream;
  private videoTransceiver?: RTCRtpTransceiver;
  private videoSender?: RTCRtpSender;
  private videoAllowed = false;
  private videoRequested = false;
  private videoSending = false;
  private remoteVideoSending = false;
  private canSwitchCamera = false;
  private cameraFacing: CallVideoFacing = 'front';
  private cameraRevision = 0;
  private cameraOperation: Promise<void> = Promise.resolve();
  private queued: RTCIceCandidateInit[] = [];
  private generation = '';
  private configuredGeneration?: string;
  private remoteReady = false;
  private closed = false;
  private muted = false;
  private connectedReported = false;
  private restartAt = -Infinity;
  private deferredRestart?: ReturnType<typeof setTimeout>;
  private credentialRefresh?: ReturnType<typeof setTimeout>;
  private recovery?: ReturnType<typeof setTimeout>;
  private terminal?: ReturnType<typeof setTimeout>;
  private sas?: CallSASHandshake;
  private sasTimeout?: ReturnType<typeof setTimeout>;
  private securityState?: CallSecurityState;
  private routeTimer?: ReturnType<typeof setInterval>;
  private routeSampleInFlight = false;
  private transportRoute: CallTransportRoute = 'unknown';

  constructor(
    private caller: boolean,
    audio: HTMLMediaElement,
    private send: (type: string, payload?: Record<string, unknown>) => void,
    private status: (text: string) => void,
    private failed: (error: Error) => void,
    private options: AudioCallOptions = {},
  ) {
    this.playback = new CallAudioPlayback(audio);
  }

  async capture(): Promise<void> {
    if (!navigator.mediaDevices?.getUserMedia) throw new Error('Микрофон недоступен. Откройте приложение по HTTPS.');
    const stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true }, video: false });
    if (this.closed) { stream.getTracks().forEach(t => t.stop()); return; }
    this.stream = stream;
    this.mute(this.muted);
    await this.refreshCameraAvailability();
  }

  mute(muted: boolean): void {
    this.muted = muted;
    this.stream?.getAudioTracks().forEach(track => { track.enabled = !muted; });
  }

  setAudioOutput(id: string): Promise<void> {
    // No await before the native output-selection API in the button's gesture.
    return this.playback.setOutputDevice(id);
  }

  videoState(): CallVideoState {
    return {
      allowed: this.videoAllowed,
      requested: this.videoRequested,
      sending: this.videoSending,
      remoteSending: this.remoteVideoSending,
      canSwitchCamera: this.canSwitchCamera,
      facing: this.cameraFacing,
      ...(this.localVideoStream ? { localStream: this.localVideoStream } : {}),
      ...(this.remoteVideoStream ? { remoteStream: this.remoteVideoStream } : {}),
    };
  }

  resendVideoState(): void {
    if (this.videoAllowed && this.peer && !this.closed) {
      this.send('rtc.video', { enabled: this.videoSending });
    }
  }

  async setVideoRequested(requested: boolean): Promise<void> {
    if (this.closed) return;
    this.videoRequested = requested;
    this.publishVideo();
    await this.updateCamera();
  }

  async switchCamera(): Promise<void> {
    if (this.closed || !this.videoRequested || !this.canSwitchCamera) return;
    this.cameraFacing = this.cameraFacing === 'front' ? 'back' : 'front';
    this.publishVideo();
    await this.updateCamera();
  }

  async receive(event: SignalEvent): Promise<void> {
    if (this.closed) return;
    const payload = event.payload;
    if (event.type === 'rtc.sas.commit') { await this.sas?.onCommitment(payload); return; }
    if (event.type === 'rtc.sas.key') { await this.sas?.onKey(payload); return; }
    if (event.type === 'rtc.sas.reveal') { await this.sas?.onReveal(payload); return; }
    if (event.type === 'rtc.video') {
      if (typeof payload.enabled !== 'boolean') return;
      this.remoteVideoSending = payload.enabled;
      this.publishVideo();
      return;
    }
    if (event.type === 'rtc.restart.request') { this.restart(); return; }
    if (event.type === 'rtc.restart') {
      this.generation = event.id;
      this.queued = [];
      this.remoteReady = false;
      this.stopRoutePolling();
      this.publishTransportRoute('unknown');
      return;
    }
    if (event.type === 'rtc.config') {
      const generation = typeof payload.restart_id === 'string' ? payload.restart_id : '';
      if (generation === this.configuredGeneration) return;
      this.configureSAS(payload);
      this.generation = generation;
      this.remoteReady = false;
      const iceServers = Array.isArray(payload.ice_servers) ? payload.ice_servers as (RTCIceServer & { expires_at?: string })[] : [];
      await this.setVideoAllowed(payload.video_allowed === true);
      if (!this.peer) this.createPeer(iceServers); else this.peer.setConfiguration({ iceServers });
      this.configuredGeneration = generation;
      // A fresh configuration also satisfies a pending restart request from the peer.
      clearTimeout(this.deferredRestart);
      clearTimeout(this.credentialRefresh);
      const expirations = iceServers.map(server => Date.parse(server.expires_at ?? '')).filter(Number.isFinite);
      if (expirations.length) {
        this.credentialRefresh = setTimeout(() => this.restart(), Math.max(0, Math.min(...expirations) - Date.now() - 60_000));
      }
      if (this.caller) {
        this.prepareVideoOffer();
        const offer = await this.peer!.createOffer({ iceRestart: Boolean(generation) });
        if (this.closed) return;
        await this.peer!.setLocalDescription(offer);
        const sdp = this.peer!.localDescription!.sdp;
        await this.sas?.recordLocalSdp(sdp);
        this.send('rtc.offer', { sdp });
      }
      return;
    }
    if (event.type === 'rtc.ice') {
      if (payload.removed === true) return;
      const generation = typeof payload.restart_id === 'string' ? payload.restart_id : '';
      if (generation !== this.generation) return;
      const candidate = { candidate: String(payload.candidate), sdpMid: String(payload.sdp_mid ?? '0'), sdpMLineIndex: Number(payload.sdp_mline_index ?? 0) };
      if (this.peer && this.remoteReady) await this.peer.addIceCandidate(candidate);
      else if (this.queued.length < 256) this.queued.push(candidate);
      return;
    }
    if ((event.type === 'rtc.offer' || event.type === 'rtc.answer') && this.peer) {
      if ((event.type === 'rtc.offer') === this.caller) throw new Error('Неожиданное предложение соединения');
      const remoteSdp = String(payload.sdp);
      if (event.type === 'rtc.offer') await this.sas?.recordRemoteSdp(remoteSdp);
      await this.peer.setRemoteDescription({ type: event.type === 'rtc.offer' ? 'offer' : 'answer', sdp: remoteSdp });
      if (event.type === 'rtc.answer') await this.sas?.recordRemoteSdp(remoteSdp);
      if (event.type === 'rtc.offer') this.prepareVideoAnswer();
      this.remoteReady = true;
      for (const candidate of this.queued.splice(0)) await this.peer.addIceCandidate(candidate);
      if (!this.caller) {
        const answer = await this.peer.createAnswer();
        if (this.closed) return;
        await this.peer.setLocalDescription(answer);
        const sdp = this.peer.localDescription!.sdp;
        await this.sas?.recordLocalSdp(sdp);
        this.send('rtc.answer', { sdp });
      }
    }
  }

  rejectSecurityFromServer(code?: string): void {
    if (code === 'call_sas_timeout') this.sas?.reject('exchange_timeout');
    else if (code === 'call_sas_unavailable') this.publishSecurity({ state: 'unavailable', reason: 'peer_unsupported' });
    else this.sas?.reject('unexpected_message');
  }

  private configureSAS(payload: Record<string, unknown>): void {
    if (this.securityState?.state === 'failed' || this.securityState?.state === 'unavailable') return;
    const allowed = payload.call_sas_allowed;
    if (this.sas) {
      if (allowed !== true) this.sas.reject('unexpected_message');
      return;
    }
    if (allowed === undefined) {
      this.publishUnavailable('server_unsupported');
      return;
    }
    if (typeof allowed !== 'boolean') {
      this.publishSecurity({ state: 'failed', reason: 'unexpected_message' });
      return;
    }
    if (!allowed) {
      this.publishUnavailable('peer_unsupported');
      return;
    }
    if (!this.options.callId || !this.options.callerLogin || !this.options.calleeLogin || !globalThis.crypto?.subtle || !globalThis.crypto.getRandomValues) {
      this.publishSecurity({ state: 'failed', reason: 'internal_error' });
      return;
    }
    this.sas = new CallSASHandshake(
      this.options.callId,
      this.options.callerLogin,
      this.options.calleeLogin,
      this.caller ? 'caller' : 'callee',
      (type, sasPayload) => this.send(type, sasPayload),
      state => this.publishSecurity(state),
    );
  }

  private publishUnavailable(reason: CallSecurityUnavailableReason): void {
    this.sas?.close();
    this.sas = undefined;
    this.publishSecurity({ state: 'unavailable', reason });
  }

  private publishSecurity(state: CallSecurityState): void {
    if (this.securityState?.state === 'failed') return;
    this.securityState = state;
    if (state.state === 'establishing') this.scheduleSASTimeout();
    else this.clearSASTimeout();
    this.options.security?.(state);
  }

  private scheduleSASTimeout(): void {
    if (this.sasTimeout) return;
    this.sasTimeout = setTimeout(() => {
      this.sasTimeout = undefined;
      this.sas?.timeOut();
    }, securityCodeTimeoutMs);
  }

  private clearSASTimeout(): void {
    clearTimeout(this.sasTimeout);
    this.sasTimeout = undefined;
  }

  private createPeer(iceServers: RTCIceServer[]): void {
    if (!this.stream) throw new Error('Сначала разрешите доступ к микрофону');
    const peer = new RTCPeerConnection({ iceServers });
    this.peer = peer;
    this.terminal = setTimeout(() => this.failed(new Error('Не удалось установить аудиосоединение')), 30000);
    this.stream.getTracks().forEach(track => peer.addTrack(track, this.stream!));
    peer.onicecandidate = event => {
      if (event.candidate && !this.closed) this.send('rtc.ice', { candidate: event.candidate.candidate, sdp_mid: event.candidate.sdpMid ?? '0', sdp_mline_index: event.candidate.sdpMLineIndex ?? 0, ...(this.generation ? { restart_id: this.generation } : {}) });
    };
    peer.ontrack = event => {
      if (this.closed) return;
      const track = event.track;
      // A negotiated video track can exist before it receives frames. Keep it
      // out of audio playback while preserving the original WebRTC tracks.
      const stream = track.kind === 'video'
        ? this.remoteVideoStream ??= new MediaStream()
        : this.remoteAudioStream ??= new MediaStream();
      stream.addTrack(track);
      track.onended = () => {
        if (this.closed) return;
        stream.removeTrack(track);
        if (track.kind === 'video') {
          if (this.remoteVideoStream !== stream) return;
          if (!stream.getVideoTracks().length) {
            this.remoteVideoStream = undefined;
            this.remoteVideoSending = false;
          }
          this.publishVideo();
        }
      };
      if (track.kind === 'video') {
        this.publishVideo();
      } else {
        void this.playback.attach(stream).catch(() => {
          if (!this.closed) this.status('Нажмите «Включить звук»');
        });
      }
    };
    peer.onconnectionstatechange = () => {
      if (this.closed) return;
      if (peer.connectionState === 'connected') {
        clearTimeout(this.deferredRestart);
        clearTimeout(this.recovery); clearTimeout(this.terminal); this.terminal = undefined;
        this.sas?.onTransportConnected();
        this.startRoutePolling(peer);
        this.status('Разговор');
        if (!this.connectedReported) { this.connectedReported = true; this.send('call.connected'); }
      } else if (peer.connectionState === 'failed') {
        this.sas?.reject('transport_failed');
        this.stopRoutePolling();
        this.publishTransportRoute('unknown');
        this.status('Восстанавливаем связь…');
        clearTimeout(this.recovery);
        this.recovery = setTimeout(() => this.restart(), 2000);
        this.terminal ??= setTimeout(() => this.failed(new Error('Не удалось восстановить соединение')), 30000);
      } else if (peer.connectionState === 'disconnected') {
        this.sas?.onTransportUnavailable();
        this.stopRoutePolling();
        this.publishTransportRoute('unknown');
        this.status('Восстанавливаем связь…');
        clearTimeout(this.recovery);
        this.recovery = setTimeout(() => this.restart(), 2000);
        this.terminal ??= setTimeout(() => this.failed(new Error('Не удалось восстановить соединение')), 30000);
      }
    };
  }

  private async setVideoAllowed(allowed: boolean): Promise<void> {
    if (this.videoAllowed === allowed) return;
    this.videoAllowed = allowed;
    if (!allowed) {
      this.videoRequested = false;
      await this.updateCamera();
      this.remoteVideoSending = false;
      this.remoteVideoStream?.getTracks().forEach(track => { track.onended = null; });
      this.remoteVideoStream = undefined;
      this.videoTransceiver = undefined;
      this.videoSender = undefined;
    }
    this.publishVideo();
  }

  private prepareVideoOffer(): void {
    if (!this.videoAllowed || !this.peer || this.videoTransceiver) return;
    const transceiver = this.peer.addTransceiver('video', {
      direction: 'sendrecv', streams: [this.stream!], sendEncodings: [videoEncodingParameters()],
    });
    this.videoTransceiver = transceiver;
    this.videoSender = transceiver.sender;
    void this.configureVideoSender();
  }

  private prepareVideoAnswer(): void {
    if (!this.videoAllowed || !this.peer || this.videoTransceiver || typeof this.peer.getTransceivers !== 'function') return;
    const transceiver = this.peer.getTransceivers().find(item => item.receiver.track.kind === 'video' && item.currentDirection !== 'stopped');
    if (!transceiver) return;
    transceiver.direction = 'sendrecv';
    this.videoTransceiver = transceiver;
    this.videoSender = transceiver.sender;
    this.videoSender.setStreams(this.stream!);
    void this.configureVideoSender();
  }

  private updateCamera(): Promise<void> {
    const revision = ++this.cameraRevision;
    const facing = this.cameraFacing;
    const requested = this.videoRequested;
    // Capture and sender replacement must finish before another camera operation
    // begins. A newer request invalidates the result of the current operation.
    const operation = this.cameraOperation.then(async () => {
      if (!this.cameraUpdateCurrent(revision)) return;
      if (requested) await this.startCamera(revision, facing);
      else await this.stopCamera(true);
    });
    this.cameraOperation = operation.catch(() => {});
    return operation;
  }

  private cameraUpdateCurrent(revision: number): boolean {
    return !this.closed && revision === this.cameraRevision;
  }

  private async startCamera(revision: number, facing: CallVideoFacing): Promise<void> {
    if (!this.videoAllowed) {
      await this.cameraFailed('Видео недоступно для этого звонка');
      return;
    }
    if (!this.videoSender) {
      await this.cameraFailed('Камера ещё готовится');
      return;
    }
    if (!navigator.mediaDevices?.getUserMedia) {
      await this.cameraFailed('Камера недоступна. Откройте приложение по HTTPS.');
      return;
    }
    const sender = this.videoSender;
    let stream: MediaStream | undefined;
    try {
      // iOS ends the old track when another camera opens. Remove its ended
      // handler and detach it first, so a late event cannot detach the new one.
      this.stopLocalVideoTracks();
      await sender.replaceTrack(null);
      if (!this.cameraUpdateCurrent(revision)) return;
      stream = await this.openCameraStream(facing);
      if (!this.cameraUpdateCurrent(revision)) return;
      const track = stream.getVideoTracks()[0];
      if (!track) throw new Error('Камера не вернула видеопоток');
      track.contentHint = 'motion';
      await sender.replaceTrack(track);
      if (!this.cameraUpdateCurrent(revision)) return;
      await this.configureVideoSender();
      if (!this.cameraUpdateCurrent(revision)) return;
      if (track.readyState === 'ended') throw new Error('Камера перестала передавать видео');
      this.localVideoStream = stream;
      this.localVideoTrack = track;
      stream = undefined;
      this.videoSending = true;
      track.onended = () => {
        if (this.localVideoTrack === track && !this.closed) {
          void this.setVideoRequested(false);
        }
      };
      this.send('rtc.video', { enabled: true });
      this.publishVideo();
      await this.refreshCameraAvailability();
    } catch (error) {
      if (this.cameraUpdateCurrent(revision)) {
        await this.cameraFailed(error instanceof Error && error.message ? error.message : 'Не удалось включить камеру');
      }
    } finally {
      // Unpublished captures still own the camera, including failed attachments
      // and results that arrive after camera-off, another switch, or hangup.
      if (stream) {
        stream.getTracks().forEach(track => track.stop());
        if (!this.closed) {
          try { await sender.replaceTrack(null); } catch { /* The peer may already be closing. */ }
        }
      }
    }
  }

  private async stopCamera(manual: boolean): Promise<void> {
    const wasSending = this.videoSending;
    this.videoSending = false;
    this.stopLocalVideoTracks();
    if (this.videoSender) {
      try { await this.videoSender.replaceTrack(null); } catch { /* Track detach is best-effort on old browsers. */ }
    }
    if (manual && wasSending && this.videoAllowed && !this.closed) this.send('rtc.video', { enabled: false });
    this.publishVideo();
  }

  private stopLocalVideoTracks(): void {
    const stream = this.localVideoStream;
    if (this.localVideoTrack) this.localVideoTrack.onended = null;
    this.localVideoStream = undefined;
    this.localVideoTrack = undefined;
    stream?.getTracks().forEach(track => track.stop());
  }

  private async cameraFailed(message: string): Promise<void> {
    this.videoRequested = false;
    await this.stopCamera(true);
    this.options.video?.({ ...this.videoState(), failure: message });
  }

  private publishVideo(): void {
    this.options.video?.(this.videoState());
  }

  private async refreshCameraAvailability(): Promise<void> {
    if (!navigator.mediaDevices?.enumerateDevices) return;
    try {
      const cameras = (await navigator.mediaDevices.enumerateDevices()).filter(device => device.kind === 'videoinput');
      const canSwitch = cameras.length > 1;
      if (this.canSwitchCamera !== canSwitch) {
        this.canSwitchCamera = canSwitch;
        this.publishVideo();
      }
    } catch {
      // Camera enumeration is advisory; capture itself remains the source of truth.
    }
  }

  private async configureVideoSender(): Promise<void> {
    const sender = this.videoSender;
    if (!sender?.getParameters || !sender.setParameters) return;
    try {
      const parameters = sender.getParameters();
      parameters.encodings ??= [{}];
      Object.assign(parameters.encodings[0], videoEncodingParameters());
      delete parameters.encodings[0].scaleResolutionDownBy;
      parameters.degradationPreference = 'maintain-framerate';
      await sender.setParameters(parameters);
    } catch {
      // Browsers differ in which RTP parameters may be changed; keep the call alive.
    }
  }

  restart(): void {
    if (this.closed || !this.peer) return;
    clearTimeout(this.deferredRestart);
    const delay = this.restartAt + 11000 - Date.now();
    if (delay > 0) {
      this.deferredRestart = setTimeout(() => this.restart(), delay);
      return;
    }
    this.restartAt = Date.now();
    this.stopRoutePolling();
    this.publishTransportRoute('unknown');
    this.send(this.caller ? 'rtc.restart' : 'rtc.restart.request');
  }

  close(): void {
    this.closed = true;
    this.cameraRevision++;
    this.videoRequested = false;
    this.videoSending = false;
    clearTimeout(this.recovery); clearTimeout(this.terminal); clearTimeout(this.deferredRestart); this.clearSASTimeout(); this.stopRoutePolling();
    clearTimeout(this.credentialRefresh);
    this.sas?.close(); this.sas = undefined;
    this.stream?.getTracks().forEach(t => t.stop());
    this.stopLocalVideoTracks();
    for (const stream of [this.remoteAudioStream, this.remoteVideoStream]) {
      stream?.getTracks().forEach(track => { track.onended = null; });
    }
    this.remoteAudioStream = undefined;
    this.remoteVideoStream = undefined;
    this.peer?.close(); this.playback.close();
  }

  private startRoutePolling(peer: RTCPeerConnection): void {
    if (this.routeTimer) return;
    void this.sampleTransportRoute(peer);
    this.routeTimer = setInterval(() => { void this.sampleTransportRoute(peer); }, routePollMs);
  }

  private stopRoutePolling(): void {
    clearInterval(this.routeTimer);
    this.routeTimer = undefined;
    this.routeSampleInFlight = false;
  }

  private async sampleTransportRoute(peer: RTCPeerConnection): Promise<void> {
    if (this.closed || this.routeSampleInFlight) return;
    if (typeof peer.getStats !== 'function') return;
    this.routeSampleInFlight = true;
    try {
      this.publishTransportRoute(transportRouteFromStats(await peer.getStats()));
    } catch {
      // Keep the last route if the browser cannot provide stats for this sample.
    } finally {
      this.routeSampleInFlight = false;
    }
  }

  private publishTransportRoute(route: CallTransportRoute): void {
    if (this.transportRoute === route) return;
    this.transportRoute = route;
    this.options.transportRoute?.(route);
  }

  private async openCameraStream(facing: CallVideoFacing): Promise<MediaStream> {
    if (!navigator.mediaDevices?.getUserMedia) throw new Error('Камера недоступна. Откройте приложение по HTTPS.');
    try {
      return await navigator.mediaDevices.getUserMedia({ audio: false, video: cameraConstraints(facing, true) });
    } catch (error) {
      if (!isOverconstrained(error)) throw error;
      return navigator.mediaDevices.getUserMedia({ audio: false, video: cameraConstraints(facing, false) });
    }
  }
}

function cameraConstraints(facing: CallVideoFacing, requireHd = false): MediaTrackConstraints {
  return {
    facingMode: { ideal: facing === 'front' ? 'user' : 'environment' },
    width: requireHd ? { min: videoCaptureMinWidth, ideal: videoCaptureWidth } : { ideal: videoCaptureWidth },
    height: requireHd ? { min: videoCaptureMinHeight, ideal: videoCaptureHeight } : { ideal: videoCaptureHeight },
    frameRate: requireHd ? { min: 24, ideal: videoCaptureFps, max: videoCaptureFps } : { ideal: videoCaptureFps, max: videoCaptureFps },
  };
}

function videoEncodingParameters(): RTCRtpEncodingParameters {
  return { maxBitrate: videoMaxBitrate, maxFramerate: videoCaptureFps };
}

function isOverconstrained(error: unknown): boolean {
  return error instanceof DOMException && (error.name === 'OverconstrainedError' || error.name === 'ConstraintNotSatisfiedError');
}

export function transportRouteFromStats(report: RTCStatsReport): CallTransportRoute {
  const stats = new Map<string, RTCStatsLike>();
  report.forEach((value, key) => stats.set(key, value as RTCStatsLike));
  const values = Array.from(stats.values());
  const transport = values.find(item => item.type === 'transport' && typeof item.selectedCandidatePairId === 'string');
  const selectedId = stringValue(transport, 'selectedCandidatePairId');
  const pair = (selectedId ? stats.get(selectedId) : undefined)
    ?? values.find(item => item.type === 'candidate-pair' && (item.selected === true || (item.nominated === true && item.state === 'succeeded')));
  const localType = candidateType(stats.get(stringValue(pair, 'localCandidateId')));
  const remoteType = candidateType(stats.get(stringValue(pair, 'remoteCandidateId')));
  if (localType === 'relay' || remoteType === 'relay') return 'turn';
  if (localType && remoteType) return 'direct';
  return 'unknown';
}

function candidateType(candidate: RTCStatsLike | undefined): string {
  const value = stringValue(candidate, 'candidateType');
  return value === 'host' || value === 'srflx' || value === 'prflx' || value === 'relay' ? value : '';
}

function stringValue(value: RTCStatsLike | undefined, key: string): string {
  const item = value?.[key];
  return typeof item === 'string' ? item : '';
}
