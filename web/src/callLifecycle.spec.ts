import { expect, it, vi } from 'vitest';
import * as ts from 'typescript';
import appSource from './app.ts?raw';

const source = ts.createSourceFile('app.ts', appSource, ts.ScriptTarget.ES2022, true);
const names = ['outgoing', 'accept', 'closeActiveCall'];
const code = ts.transpileModule(source.statements.filter(n => ts.isFunctionDeclaration(n) && names.includes(n.name?.text ?? '')).map(n => n.getText(source)).join('\n'), { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;

it('accepts another account while the cancelled outgoing connection is still pending', async () => {
  let connected!: () => void, captured!: () => void;
  const first = { id: 'a' }, second = { id: 'b' };
  const connecting = vi.fn(() => new Promise<void>(resolve => { connected = resolve; }));
  const capture = vi.fn(() => new Promise<void>(resolve => { captured = resolve; }));
  const send = vi.fn();
  const incoming = { id: 'incoming', account: second, incoming: true, media: { capture } };
  const app = new Function('first', 'connecting', 'send', `
    let current = null, localPreviewCallId, localPreviewDragPosition, videoControlsCallId, videoControlsVisible;
    const list = [first], removingAccounts = new Set(), waitingCalls = {};
    const connections = new Map([['a', {connect: connecting, clearCall: () => {}}], ['b', {connect: async () => {}, send}]]);
    const createCall = account => ({account, media: {capture: async () => {}, close: () => {}}});
    const contactDisplayName = () => 'peer', renderCall = () => {}, refreshAudioOutputs = async () => {};
    const incomingVisibility = {refresh: () => {}}, stopVideoControlsAutoHide = () => {}, stopCallTicker = () => {};
    const closeActiveOverlay = () => {}, callTones = {update: () => {}}, callToneState = () => ({});
    const closeCallNotification = async () => {}, base = '/', failure = error => {throw error};
    ${code}
    return { outgoing: () => outgoing(first, {login:'peer'}), closeActiveCall, accept, incoming: call => {current = call} };
  `)(first, connecting, send);
  const pending = app.outgoing();
  await vi.waitFor(() => expect(connecting).toHaveBeenCalledOnce());
  app.closeActiveCall();
  app.incoming(incoming);
  const answer = app.accept();
  expect(capture).toHaveBeenCalledOnce();
  connected();
  await pending;
  await app.accept();
  expect(capture).toHaveBeenCalledOnce();
  captured();
  await answer;
  expect(send).toHaveBeenCalledWith('incoming', 'call.accept', expect.any(Object));
});
