import { expect, it, vi } from 'vitest';
import * as ts from 'typescript';
import appSource from './app.ts?raw';
import { microphoneControlIcon } from './callControls';

it('uses the same microphone icon as native Android in both mute states', () => {
  expect(microphoneControlIcon(false)).toBe('micOff');
  expect(microphoneControlIcon(true)).toBe('micOff');
});

it('invokes audio recovery directly in the button gesture and ignores an ended call', async () => {
  const source = ts.createSourceFile('app.ts', appSource, ts.ScriptTarget.ES2022, true);
  const node = source.statements.find(n => ts.isFunctionDeclaration(n) && n.name?.text === 'audioRecoveryButton')!;
  const code = ts.transpileModule(node.getText(source), { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  const resumeAudio = vi.fn(async () => {});
  const app = new Function('resumeAudio', `
    const call = {media:{resumeAudio}}, element = () => ({});
    let current = call;
    ${code}
    return {button:audioRecoveryButton(call), end:() => {current = null}};
  `)(resumeAudio);
  app.button.onclick();
  expect(resumeAudio).toHaveBeenCalledOnce();
  app.end();
  app.button.onclick();
  expect(resumeAudio).toHaveBeenCalledOnce();
});
