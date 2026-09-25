import { expect, it, vi } from 'vitest';
import * as ts from 'typescript';
import appSource from './app.ts?raw';
import androidScreenShareIcon from '../../android/app/src/main/res/drawable/ic_screen_share.xml?raw';

const source = ts.createSourceFile('app.ts', appSource, ts.ScriptTarget.ES2022, true);
function code(name: string): string {
  const node = source.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === name)!;
  return ts.transpileModule(node.getText(source), {compilerOptions: {target: ts.ScriptTarget.ES2022}}).outputText;
}

class Node {
  dataset: Record<string, string> = {};
  children: Node[] = [];
  attributes = new Map<string, string>();
  disabled = false;
  onclick?: () => void;
  constructor(public tag: string, public className = '', public text = '') {}
  append(...nodes: Node[]) { this.children.push(...nodes); }
  setAttribute(key: string, value: string) { this.attributes.set(key, value); }
  querySelector() { return null; }
  cloneNode(): Node {
    const node = new Node(this.tag, this.className, this.text);
    node.children = this.children.map(child => child.cloneNode());
    return node;
  }
}
const element = (tag: string, className?: string, text?: string) => new Node(tag, className, text);

it('uses the exact Android screen sharing icon including its outline weight', () => {
  const declaration = source.statements.find(node => ts.isVariableStatement(node)
    && node.declarationList.declarations.some(item => item.name.getText(source) === 'iconPaths'))!;
  const js = ts.transpileModule(declaration.getText(source), {compilerOptions: {target: ts.ScriptTarget.ES2022}}).outputText;
  const paths = new Function(`${js} return iconPaths.screenShare;`)();
  expect(paths).toEqual([{
    d: androidScreenShareIcon.match(/android:pathData="([^"]+)"/)![1],
    fill: false,
    strokeWidth: Number(androidScreenShareIcon.match(/android:strokeWidth="([^"]+)"/)![1]),
  }]);
});

it('uses the same icon-only start/stop controls as Android without a persistent sharing label', () => {
  const startScreen = vi.fn(), stopScreen = vi.fn();
  const call = { connectedAt: 123, status: 'web_in_call_73', video: { requested: false, sending: false, remoteSending: false,
    screen: { allowed: true, captureSupported: true, requested: false, remote: false, sending: false } }, media: { startScreen, stopScreen } };
  const iconButton = (label: string, name: string, _action: unknown, css: string) => {
    const button = element('button', css);
    button.setAttribute('aria-label', label);
    button.append(element('svg', name));
    return button;
  };
  const notice = vi.fn();
  const render = new Function('current', 'element', 't', 'iconButton', 'setScreenSharingNotice', 'renderCall',
    `${code('screenSharingControls')} return screenSharingControls;`)(call, element, (key: string) => key, iconButton, notice, vi.fn());
  const idle: Node = render(call);
  expect(idle.children).toHaveLength(1);
  expect(idle.children[0].className).toBe('screen-share-start');
  expect(idle.children[0].disabled).toBe(false);
  expect(idle.children[0].attributes.get('aria-label')).toBe('text_share_screen_165');
  idle.children[0].onclick!();
  expect(startScreen).toHaveBeenCalledOnce(); // no deferred microtask before the permission API
  call.video.screen.requested = true;
  const preparing: Node = render(call);
  expect(preparing.children[0].className).toBe('screen-share-stop');
  expect(preparing.children[0].attributes.get('aria-label')).toBe('text_stop_screen_sharing_164');
  expect(preparing.children[0].children[0].className).toBe('screenShare');
  expect(preparing.children[1].text).toBe('text_preparing_to_share_135');
  preparing.children[0].onclick!();
  expect(stopScreen).toHaveBeenCalledOnce();
  expect(notice).toHaveBeenCalledWith(call, 'text_screen_sharing_stopped_48');
  call.video.screen.sending = true;
  expect(render(call).children).toHaveLength(1);
  call.status = 'text_reconnecting_131';
  expect(render(call).children[1].text).toBe('text_sharing_paused_reconnecting_134');
  expect(render(call).children[0].disabled).toBe(false); // Stop is always available.
  call.video.requested = true;
  expect(render(call).children[0].className).toBe('screen-share-stop'); // A late camera update cannot hide Stop.
  call.video.requested = false;
  call.video.screen.requested = false;
  expect(render(call).children[0].disabled).toBe(true);
});

it('hides a call-scoped sharing notice after two seconds without clearing a newer notice', () => {
  vi.useFakeTimers();
  try {
    const call: {sharingNotice?: {message: string}} = {};
    const render = vi.fn();
    const show = new Function('current', 'renderCall', `${code('setScreenSharingNotice')} return setScreenSharingNotice;`)(call, render);
    show(call, 'text_screen_sharing_started_129');
    vi.advanceTimersByTime(1500);
    expect(call.sharingNotice?.message).toBe('text_screen_sharing_started_129');
    show(call, 'text_screen_sharing_stopped_48');
    vi.advanceTimersByTime(500);
    expect(call.sharingNotice?.message).toBe('text_screen_sharing_stopped_48');
    vi.advanceTimersByTime(1500);
    expect(call.sharingNotice).toBeUndefined();
    expect(render).toHaveBeenCalledOnce();
  } finally { vi.useRealTimers(); }
});

it.each(['old-server', 'receive-only', 'local-camera', 'remote-camera', 'remote-screen', 'not-connected'])('hides the start panel for %s', scenario => {
  const call = { connectedAt: 123, video: { requested: false, sending: false, remoteSending: false,
    screen: { allowed: true, captureSupported: true, requested: false, remote: false } } };
  if (scenario === 'old-server') call.video.screen.allowed = false;
  if (scenario === 'receive-only') call.video.screen.captureSupported = false;
  if (scenario === 'local-camera') call.video.requested = true;
  if (scenario === 'remote-camera') call.video.remoteSending = true;
  if (scenario === 'remote-screen') call.video.screen.remote = true;
  if (scenario === 'not-connected') call.connectedAt = 0;
  const render = new Function(`${code('screenSharingControls')} return screenSharingControls;`)();
  expect(render(call)).toBeUndefined();
});

it('renders received screens with one status, no camera buttons or local preview, and working call controls', () => {
  const end = vi.fn(), output = vi.fn();
  const call = { id: 'one', peer: 'Bob', connectedAt: Date.now(), status: 'web_in_call_73', video: {
    remoteSending: true, remoteStream: {}, requested: true, sending: true, canSwitchCamera: true,
    localStream: {}, screen: { remote: true, ready: true },
  } };
  const action = (label: string, _icon: string, css: string, onclick: () => void) => {
    const node = element('button', css, label); node.onclick = onclick; return node;
  };
  const render = new Function('element', 't', 'roundCallAction', 'hangup', 'toggleAudioOutput', `
    let localPreviewCallId, localPreviewDragPosition, videoControlsVisible = true;
    const prepareVideoControls = () => {}, callStatusText = () => 'In call', callDurationText = () => '1:00';
    const audioOutputSelectionSupported = () => true, currentAudioOutputLabel = () => 'Speaker';
    const microphoneControlIcon = () => 'mic', restartVideoControlsAutoHide = () => {};
    ${code('videoCallScreen')} return videoCallScreen;
  `)(element, (key: string) => key, action, end, output);
  const view: Node = render(call);
  expect(view.className).toContain('screen-sharing-viewer');
  const header = view.children.find(node => node.className === 'video-call-top')!;
  expect(header.children.map(node => node.text)).toEqual(['text_screen_sharing_in_progress_198', 'Bob', '1:00']);
  const controls = view.children.find(node => node.className === 'video-controls')!;
  const actions = controls.children.find(node => node.className === 'call-actions video-actions')!;
  expect(actions.children.map(node => node.text)).toEqual(['text_audio_181', 'text_microphone_178', 'text_end_call_98']);
  actions.children[0].onclick!(); actions.children[2].onclick!();
  expect(output).toHaveBeenCalledOnce(); expect(end).toHaveBeenCalledOnce();
  expect(view.children.some(node => node.className === 'local-video-preview')).toBe(false);
});

it.each([true, false])('preserves incoming header geometry only when ending a ringing call (accepted: %s)', accepted => {
  const snapshot = new Function('t', 'callDurationText', `${code('endedSnapshot')} return endedSnapshot;`)((key: string) => key, () => '00:10');
  const ended = snapshot({account: {id: 'one'}, peer: 'Alice', peerLogin: 'alice', incoming: true, accepted}, 'Ended');
  const render = new Function('element', 'avatar', 'photoForAccountPeer', `${code('endedCallScreen')} return endedCallScreen;`)(
    element, () => element('img', 'call-avatar'), () => '');
  expect((render(ended) as Node).className.includes('ended-incoming-call-screen')).toBe(!accepted);
  const outgoing = snapshot({account: {id: 'one'}, incoming: false, accepted: false}, 'Ended');
  expect((render(outgoing) as Node).className).not.toContain('ended-incoming-call-screen');
});

it.each([true, false])('keeps camera rotation visible without disabling audio selection (can switch: %s)', canSwitch => {
  const call = { id: 'camera', peer: 'Bob', connectedAt: Date.now(), video: {
    remoteSending: true, remoteStream: {}, requested: false, sending: canSwitch, canSwitchCamera: canSwitch,
    screen: { remote: false },
  } };
  const action = (label: string, _icon: string, css: string, onclick: () => void, disabled = false) => {
    const node = element('button', css, label); node.onclick = onclick; node.disabled = disabled; return node;
  };
  const render = new Function('element', 't', 'roundCallAction', `
    let localPreviewCallId, localPreviewDragPosition, videoControlsVisible = true;
    const prepareVideoControls = () => {}, callStatusText = () => 'In call', callDurationText = () => '1:00';
    const audioOutputSelectionSupported = () => true, currentAudioOutputLabel = () => 'Speaker';
    const microphoneControlIcon = () => 'mic';
    ${code('videoCallScreen')} return videoCallScreen;
  `)(element, (key: string) => key, action);
  const view: Node = render(call);
  const controls = view.children.find(node => node.className === 'video-controls')!;
  const actions = controls.children.find(node => node.className === 'call-actions video-actions')!;
  expect(actions.children.map(node => node.text)).toEqual(['text_rotate_173', 'text_camera_175', 'text_audio_181', 'text_microphone_178', 'text_end_call_98']);
  expect(actions.children[0].disabled).toBe(!canSwitch);
  expect(actions.children[2].disabled).toBe(false);
});

it('keeps waiting caller identity and photos above separate action rows', async () => {
  const calls = [
    {account: {id: 'one'}, event: {payload: {caller_login: 'same'}}, peer: 'A very long caller name', confirmed: true},
    {account: {id: 'two'}, event: {payload: {caller_login: 'same'}}, peer: 'Second caller', confirmed: false},
  ];
  const photo = vi.fn((account: string, login: string) => `${account}/${login}`);
  const avatar = vi.fn((_name, _login, css) => element('img', css));
  const reject = vi.fn(), answer = vi.fn();
  const registry = {entries: new Map(calls.map((call, index) => [index, call])), selected: undefined as unknown};
  const reply = vi.fn();
  const iconButton = (label: string, _icon: string, action: () => void, css: string) => {
    const button = element('button', css, label); button.onclick = action; return button;
  };
  const render = new Function('waitingCalls', 'element', 't', 'avatar', 'photoForAccountPeer', 'rejectWaiting', 'answerWaiting', 'iconButton', 'waitingReplySheet',
    `${code('waitingCallsPanel')} return waitingCallsPanel;`)(registry, element, (key: string) => key, avatar, photo, reject, answer, iconButton, reply);
  const panel: Node = render();
  expect(photo.mock.calls).toEqual([['one', 'same'], ['two', 'same']]);
  const rows = panel.children.slice(2);
  expect(rows).toHaveLength(2);
  rows.forEach((row, index) => {
    const [identity, actions] = row.children;
    expect(identity.children[1].text).toBe(calls[index].peer);
    expect(actions.children).toHaveLength(3);
    expect(actions.children[1].disabled).toBe(!calls[index].confirmed);
    expect(actions.children[2].disabled).toBe(!calls[index].confirmed);
  });
  rows[0].children[1].children[0].onclick!();
  expect(reject).toHaveBeenCalledExactlyOnceWith(calls[0]);
  rows[0].children[1].children[1].onclick!();
  expect(reply).toHaveBeenCalledExactlyOnceWith(calls[0]);
  rows[0].children[1].children[2].onclick!();
  expect(answer).toHaveBeenCalledExactlyOnceWith(calls[0]);
  registry.selected = calls[0];
  expect((render() as Node).children.slice(2).every(row => row.children[1].children.every(button => button.disabled))).toBe(true);
});

it('reserves the translated reply handle before the first animation frame without an accessible duplicate', () => {
  const frame = vi.fn();
  const render = new Function('element', 't', 'callReplies', 'requestAnimationFrame',
    `${code('incomingReplySheet')} return incomingReplySheet;`)(element, () => 'A long translated reply title', () => [], frame);
  const shell: Node = render();
  const [reservation, layer] = shell.children;
  expect(reservation.className).toBe('incoming-reply-reservation');
  expect(reservation.attributes.get('aria-hidden')).toBe('true');
  expect(reservation.attributes.has('inert')).toBe(true);
  const sheet = layer.children[1].children[0];
  expect(sheet.className).toContain('initializing');
  expect(reservation.children[0].children.map(node => node.text)).toEqual(sheet.children[0].children.map(node => node.text));
  expect(reservation.children[0].onclick).toBeUndefined();
  expect(frame).toHaveBeenCalledOnce();
});

it('groups the reply recipient photo and name above a secondary action caption', () => {
  const invite = {account: {id: 'two'}, event: {payload: {caller_login: 'alex'}}, peer: 'Alexandra',
    confirmed: true, deadline: Date.now() + 15_000};
  const root = element('main');
  const photo = vi.fn(() => 'contact-photo');
  const avatar = vi.fn((_name, _login, css) => element('img', css));
  const render = new Function('waitingCalls', 'element', 't', 'avatar', 'photoForAccountPeer', 'root', 'registerActiveOverlay', 'callReplies',
    `let waitingReply; ${code('waitingReplySheet')} return waitingReplySheet;`)(
    {has: () => true}, element, (key: string) => key, avatar, photo, root, () => () => {}, () => []);
  render(invite);
  const panel = root.children[0].children[0];
  expect(panel.attributes.get('aria-label')).toBe('call_reply_sheet_title — Alexandra');
  const [image, identity] = panel.children[0].children;
  expect(image.className).toBe('waiting-reply-avatar');
  expect(identity.children.map(node => [node.tag, node.text])).toEqual([
    ['h2', 'Alexandra'], ['p', 'call_reply_sheet_title'],
  ]);
  expect(photo).toHaveBeenCalledExactlyOnceWith('two', 'alex');
  expect(avatar).toHaveBeenCalledExactlyOnceWith('Alexandra', 'alex', 'waiting-reply-avatar', 'contact-photo');
});
