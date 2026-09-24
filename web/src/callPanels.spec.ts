import { expect, it, vi } from 'vitest';
import * as ts from 'typescript';
import appSource from './app.ts?raw';

const source = ts.createSourceFile('app.ts', appSource, ts.ScriptTarget.ES2022, true);
function code(name: string): string {
  const node = source.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === name)!;
  return ts.transpileModule(node.getText(source), {compilerOptions: {target: ts.ScriptTarget.ES2022}}).outputText;
}

class Node {
  children: Node[] = [];
  attributes = new Map<string, string>();
  disabled = false;
  onclick?: () => void;
  constructor(public tag: string, public className = '', public text = '') {}
  append(...nodes: Node[]) { this.children.push(...nodes); }
  setAttribute(key: string, value: string) { this.attributes.set(key, value); }
  cloneNode(): Node {
    const node = new Node(this.tag, this.className, this.text);
    node.children = this.children.map(child => child.cloneNode());
    return node;
  }
}
const element = (tag: string, className?: string, text?: string) => new Node(tag, className, text);

it.each([true, false])('preserves incoming header geometry only when ending a ringing call (accepted: %s)', accepted => {
  const snapshot = new Function('t', 'callDurationText', `${code('endedSnapshot')} return endedSnapshot;`)((key: string) => key, () => '00:10');
  const ended = snapshot({account: {id: 'one'}, peer: 'Alice', peerLogin: 'alice', incoming: true, accepted}, 'Ended');
  const render = new Function('element', 'avatar', 'photoForAccountPeer', `${code('endedCallScreen')} return endedCallScreen;`)(
    element, () => element('img', 'call-avatar'), () => '');
  expect((render(ended) as Node).className.includes('ended-incoming-call-screen')).toBe(!accepted);
  const outgoing = snapshot({account: {id: 'one'}, incoming: false, accepted: false}, 'Ended');
  expect((render(outgoing) as Node).className).not.toContain('ended-incoming-call-screen');
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
  const render = new Function('waitingCalls', 'element', 't', 'avatar', 'photoForAccountPeer', 'rejectWaiting', 'answerWaiting',
    `${code('waitingCallsPanel')} return waitingCallsPanel;`)(registry, element, (key: string) => key, avatar, photo, reject, answer);
  const panel: Node = render();
  expect(photo.mock.calls).toEqual([['one', 'same'], ['two', 'same']]);
  const rows = panel.children.slice(2);
  expect(rows).toHaveLength(2);
  rows.forEach((row, index) => {
    const [identity, actions] = row.children;
    expect(identity.children[1].text).toBe(calls[index].peer);
    expect(actions.children).toHaveLength(2);
    expect(actions.children[1].disabled).toBe(!calls[index].confirmed);
  });
  rows[0].children[1].children[0].onclick!();
  expect(reject).toHaveBeenCalledExactlyOnceWith(calls[0]);
  rows[0].children[1].children[1].onclick!();
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
