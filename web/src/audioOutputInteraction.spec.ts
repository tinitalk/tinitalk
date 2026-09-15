import { afterEach, expect, it, onTestFinished, vi } from 'vitest';
import * as ts from 'typescript';
import appSource from './app.ts?raw';
import { CallAudioPlayback } from './callAudioPlayback';

afterEach(() => vi.unstubAllGlobals());

// Execute the real handlers without starting the account/network application.
// Selecting declarations by AST keeps this a behavior test, not a text assertion.
const source = ts.createSourceFile('app.ts', appSource, ts.ScriptTarget.ES2022, true, ts.ScriptKind.TS);
const functionNames = [
  'roundCallAction', 'toggleAudioOutput', 'selectAudioOutput', 'directAudioOutput',
  'audioOutputSheet', 'sinkAudio', 'outputMediaDevices', 'audioOutputSelectionSupported',
  'currentAudioOutputId', 'refreshAudioOutputs', 'audioOutputLabel',
  'audioOutputPromptSupported', 'promptAudioOutputSelection', 'selectAudioOutputById',
  'audioOutputErrorText',
  'videoElement',
  'renderCall',
];
const declarations = functionNames.map(name => {
  const declaration = source.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === name);
  if (!declaration) throw new Error(`Missing application function: ${name}`);
  return declaration.getText(source);
});
const handlers = ts.transpileModule(declarations.join('\n'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None },
}).outputText;

type Output = { id: string; label: string };
type Action = () => void | Promise<void>;
type UI = {
  roundCallAction(label: string, iconName: string, tone: string, action: Action): FakeElement;
  audioOutputSelectionSupported(): boolean;
  refreshAudioOutputs(): Promise<void>;
  toggleAudioOutput(): Promise<void>;
  audioOutputSheet(): void;
  videoElement(className: string, stream: MediaStream): HTMLVideoElement;
  renderCall(): void;
};
const createUI = new Function('environment', `
  const {
    audio, remoteVideo, current, root, element, icon, callTones, failure, callLayer, callContent,
    closeSheet, registerActiveOverlay, audioOutputUnsupportedDialog,
    audioOutputUnavailableDialog,
  } = environment;
  const callToneState = () => ({});
  const videoModeActive = () => true;
  const videoCallScreen = () => element('div', 'video-call-screen');
  const incomingVisibility = { refresh() {} };
  const positionLocalPreview = () => {};
  let endedCall = null;
  let audioOutputs = environment.outputs;
  let audioOutputsLoading;
  ${handlers}
  return { roundCallAction, toggleAudioOutput, audioOutputSheet, videoElement, renderCall, audioOutputSelectionSupported, refreshAudioOutputs };
`) as (environment: Record<string, unknown>) => UI;

it('reaches the playing audio element during a sound-button tap with two cached outputs', async () => {
  const harness = setup();
  const button = harness.soundButton();

  harness.click(button);

  expect.soft(harness.audio.setSinkId).toHaveBeenCalledExactlyOnceWith('receiver');
  expect.soft(harness.routes).toEqual([{ id: 'receiver', duringClick: true }]);
  await nextTurn();
  expect(harness.failure).not.toHaveBeenCalled();
  expect(harness.audio.srcObject).toBe(harness.remote);
});

it.each([
  'Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) Version/26.6.1 Mobile Safari/604.1',
  'Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) CriOS/152.0 Mobile Safari/604.1',
  'Mozilla/5.0 (iPad; CPU OS 18_7 like Mac OS X) Version/26.6.1 Mobile Safari/604.1',
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15) Version/26.6.1 Safari/605.1.15',
])('disables output selection on iOS despite an exposed setSinkId API: %s', async userAgent => {
  const h = setup();
  Object.assign(navigator, { userAgent, maxTouchPoints: 5 });

  expect(h.ui.audioOutputSelectionSupported()).toBe(false);
  await h.ui.refreshAudioOutputs();
  h.ui.audioOutputSheet();

  expect(h.outputRows()).toHaveLength(0);
  expect(h.enumerateDevices).not.toHaveBeenCalled();
  expect(h.audio.setSinkId).not.toHaveBeenCalled();
  expect(h.audio.srcObject).toBe(h.remote);
  expect(h.audio.pause).not.toHaveBeenCalled();
});

it('keeps output selection available on a Mac with the native API', () => {
  const h = setup();
  Object.assign(navigator, { userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15) Safari/605.1.15', maxTouchPoints: 0 });
  expect(h.ui.audioOutputSelectionSupported()).toBe(true);
});

it('selects a sheet row on the playing audio element during that row tap', async () => {
  const harness = setup();
  harness.ui.audioOutputSheet();
  const row = harness.outputRows()[1];
  expect(row).toBeDefined();

  harness.click(row);

  expect.soft(harness.audio.setSinkId).toHaveBeenCalledExactlyOnceWith('receiver');
  expect.soft(harness.routes).toEqual([{ id: 'receiver', duringClick: true }]);
  await nextTurn();
  expect(harness.failure).not.toHaveBeenCalled();
});

it('keeps the local camera preview muted', () => {
  const harness = setup();
  const stream = {} as MediaStream;
  const video = harness.ui.videoElement('local-video', stream);
  expect(video.srcObject).toBe(stream);
  expect(video.muted).toBe(true);
});

it('keeps the playing remote element attached when call controls are redrawn or remote video is hidden', () => {
  const h = setup();
  h.ui.renderCall();
  h.remoteVideo.paused = false;
  h.ui.renderCall();
  expect(h.callLayer.children).toEqual([h.remoteVideo, h.callContent]);
  expect(h.callContent.children).toHaveLength(1);
  expect(h.audio.removed).toBe(false);
  expect(h.audio.srcObject).toBe(h.remote);
  expect(h.audio.play).not.toHaveBeenCalled();
  expect(h.audio.pause).not.toHaveBeenCalled();
  expect(h.callLayer.classList.contains('has-remote-video')).toBe(true);
  expect(h.remoteVideo.srcObject).toBe(h.remoteVideoStream);
  expect(h.remoteVideo.muted).toBe(true);
  expect(h.remoteVideo.play).toHaveBeenCalledOnce();

  h.current.video.remoteSending = false;
  h.ui.renderCall();
  expect(h.callLayer.classList.contains('has-remote-video')).toBe(false);
  expect(h.audio.removed).toBe(false);
  expect(h.audio.srcObject).toBe(h.remote);
  expect(h.remoteVideo.srcObject).toBe(h.remoteVideoStream);
  expect(h.remoteVideo.pause).not.toHaveBeenCalled();
  expect(h.remoteVideo.play).toHaveBeenCalledOnce();
});

it('clears only the video player when its last remote track disappears', () => {
  const h = setup();
  h.ui.renderCall();
  h.current.video.remoteStream = undefined;
  h.ui.renderCall();
  expect(h.remoteVideo.srcObject).toBeNull();
  expect(h.remoteVideo.pause).toHaveBeenCalledOnce();
  expect(h.audio.srcObject).toBe(h.remote);
  expect(h.audio.play).not.toHaveBeenCalled();
  expect(h.audio.pause).not.toHaveBeenCalled();
});

it('requires a fresh sheet-row tap after loading an empty output cache', async () => {
  const harness = setup([]);
  let finishEnumeration!: (devices: MediaDeviceInfo[]) => void;
  harness.enumerateDevices.mockImplementationOnce(() => new Promise(resolve => { finishEnumeration = resolve; }));

  harness.click(harness.soundButton());
  await nextTurn();
  expect(harness.enumerateDevices).toHaveBeenCalledOnce();
  expect(harness.audio.setSinkId).not.toHaveBeenCalled();
  finishEnumeration(devices());
  await nextTurn();

  expect.soft(harness.audio.setSinkId).not.toHaveBeenCalled();
  const rows = harness.outputRows();
  expect(rows).toHaveLength(2);
  harness.click(rows[1]);
  expect.soft(harness.routes).toEqual([{ id: 'receiver', duringClick: true }]);
  await nextTurn();
  expect(harness.failure).not.toHaveBeenCalled();
});

it.each(['synchronous', 'asynchronous'])('reports a %s round-button action failure', async kind => {
  const harness = setup();
  const error = new Error('Action failed');
  const action = kind === 'synchronous'
    ? () => { throw error; }
    : () => Promise.reject(error);
  const button = harness.ui.roundCallAction('Test', 'volume', 'neutral', action).children[0];

  expect(() => harness.click(button)).not.toThrow();
  await nextTurn();

  expect(harness.failure).toHaveBeenCalledExactlyOnceWith(error);
});

function setup(outputs: Output[] = [
  { id: 'default', label: 'Speaker' },
  { id: 'receiver', label: 'Receiver' },
]) {
  let duringClick = false;
  const routes: { id: string; duringClick: boolean }[] = [];
  const enumerateDevices = vi.fn(async () => devices());
  vi.stubGlobal('navigator', {
    userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/152.0.0.0',
    maxTouchPoints: 0,
    mediaDevices: { enumerateDevices },
  });
  const remote = {} as MediaStream;
  const audio = Object.assign(new FakeElement('audio'), {
    srcObject: remote as MediaStream | null,
    sinkId: 'default', paused: false,
    play: vi.fn(async () => { audio.paused = false; }),
    pause: vi.fn(() => { audio.paused = true; }),
    setSinkId: vi.fn((id: string) => {
      routes.push({ id, duringClick });
      if (!duringClick) return Promise.reject(new DOMException('Selection lost its click', 'NotAllowedError'));
      audio.sinkId = id;
      return Promise.resolve();
    }),
  });
  const playback = new CallAudioPlayback(audio as unknown as HTMLAudioElement);
  onTestFinished(() => playback.close());
  const root = new FakeElement('main');
  const callLayer = new FakeElement('section');
  const callContent = new FakeElement('div');
  const remoteVideoStream = {} as MediaStream;
  const remoteVideo = Object.assign(new FakeElement('video'), {
    srcObject: null as MediaStream | null, muted: false, paused: true,
    play: vi.fn(async () => { remoteVideo.paused = false; }),
    pause: vi.fn(() => { remoteVideo.paused = true; }),
  });
  callLayer.append(remoteVideo, callContent);
  const current = {
    video: { remoteSending: true, remoteStream: remoteVideoStream as MediaStream | undefined },
    media: { setAudioOutput: (id: string) => playback.setOutputDevice(id) },
  };
  const failure = vi.fn();
  const ui = createUI({
    audio, remoteVideo, root, outputs, failure, current, callLayer, callContent,
    callTones: { unlock: vi.fn(), update: vi.fn() },
    element: (tag: string, className = '', text = '') => new FakeElement(tag, className, text),
    icon: () => new FakeElement('span'),
    closeSheet: (overlay: FakeElement) => overlay.remove(),
    registerActiveOverlay: (close: () => void) => close,
    audioOutputUnsupportedDialog: vi.fn(),
    audioOutputUnavailableDialog: vi.fn(),
  });
  return {
    ui, audio, remoteVideo, remoteVideoStream, remote, routes, failure, enumerateDevices, current, callLayer, callContent,
    soundButton: () => ui.roundCallAction('Звук', 'volume', 'neutral', ui.toggleAudioOutput).children[0],
    outputRows: () => root.descendants().filter(node => node.className.split(' ').includes('audio-output-row')),
    click: (button: FakeElement) => {
      duringClick = true;
      try { return button.onclick?.(); }
      finally { duringClick = false; }
    },
  };
}

function devices(): MediaDeviceInfo[] {
  return [
    { kind: 'audiooutput', deviceId: 'default', label: 'Speaker' },
    { kind: 'audiooutput', deviceId: 'receiver', label: 'Receiver' },
  ].map(device => ({ ...device, groupId: '', toJSON: () => ({ ...device }) }) as MediaDeviceInfo);
}

function nextTurn(): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, 0));
}

class FakeElement {
  play = vi.fn(async () => {});
  children: FakeElement[] = [];
  dataset: Record<string, string> = {};
  onclick?: Action;
  removed = false;
  hidden = false;
  private classes = new Set<string>();
  classList = {
    toggle: (name: string, force: boolean) => { if (force) this.classes.add(name); else this.classes.delete(name); },
    contains: (name: string) => this.classes.has(name),
  };

  constructor(readonly tag: string, readonly className = '', readonly textContent = '') {}
  append(...children: FakeElement[]): void { this.children.push(...children); }
  replaceChildren(...children: FakeElement[]): void {
    this.children.forEach(child => child.remove());
    this.children = children;
  }
  querySelector(): null { return null; }
  remove(): void { this.removed = true; }
  descendants(): FakeElement[] {
    return this.children.filter(child => !child.removed).flatMap(child => [child, ...child.descendants()]);
  }
}
