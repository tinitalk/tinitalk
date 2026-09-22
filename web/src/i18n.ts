import en from './locales/en.json';
import ru from './locales/ru.json';
import pl from './locales/pl.json';
import de from './locales/de.json';
import es from './locales/es.json';
import fr from './locales/fr.json';
import pt from './locales/pt.json';
import it from './locales/it.json';
import tr from './locales/tr.json';
import ja from './locales/ja.json';
import ko from './locales/ko.json';
import zh from './locales/zh-Hans.json';

export const catalogs = { en, ru, pl, de, es, fr, pt, it, tr, ja, ko, 'zh-Hans': zh };
export type Language = keyof typeof catalogs;
export type Message = keyof typeof en;
export const languages: { tag: Language; name: string; flag: string }[] = [
  { tag: 'en', name: 'English', flag: 'gb' },
  { tag: 'ru', name: 'Русский', flag: 'ru' },
  { tag: 'pl', name: 'Polski', flag: 'pl' },
  { tag: 'de', name: 'Deutsch', flag: 'de' },
  { tag: 'es', name: 'Español', flag: 'es' },
  { tag: 'fr', name: 'Français', flag: 'fr' },
  { tag: 'pt', name: 'Português (Brasil)', flag: 'br' },
  { tag: 'it', name: 'Italiano', flag: 'it' },
  { tag: 'tr', name: 'Türkçe', flag: 'tr' },
  { tag: 'ja', name: '日本語', flag: 'jp' },
  { tag: 'ko', name: '한국어', flag: 'kr' },
  { tag: 'zh-Hans', name: '简体中文', flag: 'cn' },
];
const storageKey = 'tinitalk.language';
const cacheName = 'tinitalk-preferences';
const cachePath = '/__tinitalk_language';
let selection: Language | '' = readSelection();
const listeners = new Set<() => void>();

function readSelection(): Language | '' {
  try { return validSelection(localStorage.getItem(storageKey)); } catch { return ''; }
}
function validSelection(value: unknown): Language | '' {
  return typeof value === 'string' && Object.hasOwn(catalogs, value) ? value as Language : '';
}
export function browserLanguages(): readonly string[] {
  return typeof navigator === 'undefined' ? ['en'] : navigator.languages?.length ? navigator.languages : [navigator.language || 'en'];
}
export function resolveLanguage(tags: readonly string[]): Language {
  for (const tag of tags) {
    try {
      const locale = new Intl.Locale(tag);
      if (locale.language === 'zh') {
        if (locale.script === 'Hans' || (!locale.script && !['TW', 'HK', 'MO'].includes(locale.region || ''))) return 'zh-Hans';
      } else if (Object.hasOwn(catalogs, locale.language)) return locale.language as Language;
    } catch { /* Ignore an invalid browser locale. */ }
  }
  return 'en';
}
export function selectedLanguage(): Language | '' { return selection; }
export function currentLanguage(): Language { return selection || resolveLanguage(browserLanguages()); }
export function currentLocale(): string { return currentLanguage() === 'pt' ? 'pt-BR' : currentLanguage(); }
export function t(key: Message, ...args: (string | number)[]): string {
  return translate(currentLanguage(), key, ...args);
}
export function translate(language: Language, key: Message, ...args: (string | number)[]): string {
  const value = catalogs[language][key] || en[key];
  return value.replace(/\{(\d+)\}/g, (match, index: string) => args[Number(index)] === undefined ? match : String(args[Number(index)]));
}
export function onLanguageChange(listener: () => void): () => void { listeners.add(listener); return () => listeners.delete(listener); }
function changed(): void {
  if (typeof document !== 'undefined') document.documentElement.lang = currentLanguage();
  for (const listener of listeners) listener();
}
export async function selectLanguage(value: Language | ''): Promise<void> {
  // Keep the preference for the page and for workers that run with no open page.
  localStorage.setItem(storageKey, value);
  selection = value;
  try {
    if (typeof caches !== 'undefined') await (await caches.open(cacheName)).put(cachePath, new Response(value));
  } catch { /* A restricted browser can still use the page's saved preference. */ }
  changed();
}
export async function loadWorkerLanguage(): Promise<void> {
  try {
    const response = await (await caches.open(cacheName)).match(cachePath);
    selection = validSelection(response ? await response.text() : '');
  } catch { selection = ''; }
}
// Refresh previously rendered status text without restarting connections.
// Active call state uses message keys, not translated text.
export function relocalize(value: string, previous: Language): string {
  const key = (Object.keys(en) as Message[]).find(key => catalogs[previous][key] === value);
  return key ? t(key) : value;
}
if (typeof window !== 'undefined') {
  window.addEventListener('languagechange', changed);
  window.addEventListener('storage', event => { if (event.key === storageKey) { selection = readSelection(); changed(); } });
  if (typeof document !== 'undefined') document.documentElement.lang = currentLanguage();
}
