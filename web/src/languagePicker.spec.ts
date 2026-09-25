import { expect, it } from 'vitest';
import * as ts from 'typescript';
import sourceText from './app.ts?raw';
import { languages, resolveLanguage, translate, type Language, type Message } from './i18n';

const source = ts.createSourceFile('app.ts', sourceText, ts.ScriptTarget.Latest, true);
const functions = ['languageButton', 'systemLanguageLabel', 'showLanguagePicker', 'callToneState', 'callStatusText', 'callReplies'];
const code = ts.transpileModule(source.statements.filter(n => ts.isFunctionDeclaration(n) && functions.includes(n.name!.text)).map(n => n.getText(source)).join('\n'), { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;

function harness(initial: Language | '') {
  let selection = initial;
  let closed = false;
  const node = (_tag = '', className = '', text = ''): any => ({ className, text, children: [], attributes: {},
    classList: { add() {} }, style: {}, addEventListener() {}, append(...children: unknown[]) { this.children.push(...children); },
    setAttribute(key: string, value: string) { this.attributes[key] = value; },
  });
  const heading = node();
  const modal = { overlay: { ...node(), querySelector: () => heading }, body: node(), actions: node(), close() { closed = true; } };
  modal.body.parentElement = node();
  class Observer { observe() {} disconnect() {} }
  const t = (key: Message, ...args: (string | number)[]) => translate(selection || 'de', key, ...args);
  const ui = new Function('t', 'selectedLanguage', 'selectLanguage', 'languages', 'browserLanguages', 'resolveLanguage', 'translate', 'dialog', 'element', 'actionButton', 'closeDialog', 'ResizeObserver', 'MutationObserver', 'root', 'requestAnimationFrame', 'icon', `${code}; return { languageButton, showLanguagePicker, callToneState, callStatusText, callReplies };`)(
    t, () => selection, async (value: Language | '') => { expect(closed).toBe(true); selection = value; },
    languages, () => ['de-DE'], resolveLanguage, translate, () => modal, node,
    (label: string, action: () => unknown, cls: string) => ({ ...node('button', cls, label), click: action }),
    async () => { closed = true; },
    Observer, Observer, node(), () => {}, () => node(),
  );
  return { ui, modal, selection: () => selection };
}

it('shows all languages, a system-language label and a highlighted current selection', async () => {
  const { ui, modal, selection } = harness('es');
  ui.showLanguagePicker();
  const choices = modal.body.children;
  expect(choices).toHaveLength(13);
  expect(choices[0].children[1].text).toBe(translate('de', 'language_system'));
  expect(choices.filter((n: any) => n.attributes['aria-checked'] === 'true')).toHaveLength(1);
  const spanish = choices.find((n: any) => n.children[1].lang === 'es');
  expect(spanish.className).toContain('selected');
  expect(spanish.attributes['aria-checked']).toBe('true');
  await choices.find((n: any) => n.children[1].lang === 'ja').click();
  expect(selection()).toBe('ja');
  expect(ui.languageButton().children[0].children[0].text).toBe(translate('ja', 'language_title'));
  expect(modal.actions.hidden).toBe(true);
});

it('changes call labels and reply choices without changing call phases', () => {
  for (const language of languages) {
    const { ui } = harness(language.tag);
    const ringing = { incoming: false, status: 'text_waiting_for_an_answer_4' };
    expect(ui.callToneState(ringing)).toMatchObject({ phase: 'ringing', connected: false });
    expect(ui.callStatusText(ringing)).toBe(translate(language.tag, 'text_waiting_for_an_answer_4'));
    expect(ui.callToneState({ ...ringing, connectedAt: 1, status: 'text_reconnecting_131' })).toMatchObject({ phase: 'active', reconnecting: true });
    expect(ui.callReplies()[0].text).toBe(translate(language.tag, 'call_reply_cannot_talk'));
  }
});
