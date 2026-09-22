import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { catalogs, type Language } from './i18n';

const saved = new Map<string, string>();
let cached: string | undefined;
beforeEach(() => {
  vi.resetModules(); saved.clear(); cached = undefined;
  vi.stubGlobal('navigator', { languages: ['de-DE'], language: 'de-DE' });
  vi.stubGlobal('localStorage', { getItem: (key: string) => saved.get(key) ?? null, setItem: (key: string, value: string) => saved.set(key, value) });
  vi.stubGlobal('caches', { open: async () => ({
    put: async (_key: string, response: Response) => { cached = await response.text(); },
    match: async () => cached === undefined ? undefined : new Response(cached),
  }) });
});
afterEach(() => vi.unstubAllGlobals());

it.each([
  [['ru-RU'], 'ru'], [['pt-PT'], 'pt'], [['es-MX'], 'es'], [['ja-JP'], 'ja'], [['ko-KR'], 'ko'],
  [['zh-CN'], 'zh-Hans'], [['zh-SG'], 'zh-Hans'], [['zh-Hans-TW'], 'zh-Hans'],
  [['zh-TW'], 'en'], [['zh-Hant-CN'], 'en'], [['zh-HK', 'de'], 'de'],
  [['fi-FI'], 'en'], [['fi', 'pl'], 'pl'], [[], 'en'], [['not_a_locale'], 'en'],
] as [string[], Language][])('resolves %j to %s', async (tags, expected) => {
  const { resolveLanguage } = await import('./i18n');
  expect(resolveLanguage(tags)).toBe(expected);
});

it('uses browser language on the first login and falls back to English', async () => {
  const i18n = await import('./i18n');
  expect(i18n.selectedLanguage()).toBe('');
  expect(i18n.t('text_sign_in_115')).toBe(catalogs.de.text_sign_in_115);
  vi.stubGlobal('navigator', { languages: ['fi-FI'] });
  expect(i18n.t('text_sign_in_115')).toBe(catalogs.en.text_sign_in_115);
});

it('persists manual selection for a new page and a worker, and restores automatic selection', async () => {
  let i18n = await import('./i18n');
  const changed = vi.fn(); i18n.onLanguageChange(changed);
  await i18n.selectLanguage('ja');
  expect(changed).toHaveBeenCalledOnce();
  expect(cached).toBe('ja');
  expect(i18n.t('text_sign_in_115')).toBe(catalogs.ja.text_sign_in_115);
  vi.resetModules(); i18n = await import('./i18n');
  expect(i18n.selectedLanguage()).toBe('ja');
  await i18n.selectLanguage('');
  expect(i18n.currentLanguage()).toBe('de');
  expect(cached).toBe('');
  cached = 'pl';
  vi.stubGlobal('localStorage', undefined);
  vi.resetModules(); i18n = await import('./i18n');
  await i18n.loadWorkerLanguage();
  expect(i18n.currentLanguage()).toBe('pl');
});

it('updates the page even if worker preference storage is unavailable', async () => {
  vi.stubGlobal('caches', { open: async () => { throw new Error('unavailable'); } });
  const i18n = await import('./i18n');
  const changed = vi.fn(); i18n.onLanguageChange(changed);
  await i18n.selectLanguage('es');
  expect(changed).toHaveBeenCalledOnce();
  expect(i18n.currentLanguage()).toBe('es');
});

it('ignores unsupported persisted language values', async () => {
  saved.set('tinitalk.language', 'lt');
  const i18n = await import('./i18n');
  expect(i18n.currentLanguage()).toBe('de');
});

it('formats substitutions as plain text without interpreting their contents', async () => {
  const { translate } = await import('./i18n');
  expect(translate('en', 'web_value_is_calling_118', '<script>{0}</script>')).toBe('📞 <script>{0}</script> is calling');
});

describe('translation catalogs', () => {
  const keys = Object.keys(catalogs.en).sort();
  const placeholders = (text: string) => [...text.matchAll(/\{\d+\}/g)].map(m => m[0]).sort();
  it.each(Object.entries(catalogs))('%s has complete messages and matching placeholders', (_language, catalog) => {
    expect(Object.keys(catalog).sort()).toEqual(keys);
    for (const key of keys as (keyof typeof catalogs.en)[]) {
      expect(catalog[key].trim(), key).not.toBe('');
      expect(placeholders(catalog[key]), key).toEqual(placeholders(catalogs.en[key]));
      expect(catalog[key], key).not.toMatch(/%\d+\$[sd]/);
    }
  });
});
