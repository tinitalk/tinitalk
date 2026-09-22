import { translate } from './i18n';

// Existing extracted-function fixtures exercise Russian labels explicitly.
// Supply real translation dependencies instead of adding globals to production.
export function localizedFunction(...args: string[]): Function {
  return new Function('t', 'currentLocale', ...args).bind(undefined,
    (key: Parameters<typeof translate>[1], ...values: (string | number)[]) => translate('ru', key, ...values),
    () => 'ru');
}
