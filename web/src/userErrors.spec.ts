import { expect, it } from 'vitest';
import { explainError, OperationError } from './userErrors';
import { t } from './i18n';

it.each(['microphone', 'camera'] as const)('explains denied %s access without browser jargon', context => {
  const result = explainError(new OperationError(context, new DOMException('Permission denied', 'NotAllowedError')));
  expect(result?.title).toBe(t(context === 'microphone' ? 'web_allow_microphone_access_153' : 'web_allow_camera_access_154'));
  expect(result?.message).toBe(t('web_allow_value_access_in_the_site_or_app_settings_if_the_browser_sav_158',
    t(context === 'microphone' ? 'web_microphone_156' : 'web_camera_157')));
  expect(result?.retryLabel).toBe(t('web_check_access_159'));
});

it.each(['microphone', 'camera'] as const)('suggests restarting the iOS PWA after denied %s access', context => {
  const error = new OperationError(context, new DOMException('Permission denied', 'NotAllowedError'));
  for (const device of [
    { userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X)', maxTouchPoints: 5 },
    { userAgent: 'Mozilla/5.0 (iPad; CPU OS 18_7 like Mac OS X)', maxTouchPoints: 5 },
    { userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15)', maxTouchPoints: 5 },
  ]) {
    const result = explainError(error, device);
    expect(result?.message).toBe(t('web_if_you_denied_access_end_the_call_open_the_app_switcher_and_swipe_155',
      t(context === 'microphone' ? 'web_microphone_156' : 'web_camera_157')));
  }
  for (const device of [
    { userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15)', maxTouchPoints: 0 },
    { userAgent: 'Mozilla/5.0 (Linux; Android 16)', maxTouchPoints: 5 },
  ]) {
    expect(explainError(error, device)?.message).toBe(t('web_allow_value_access_in_the_site_or_app_settings_if_the_browser_sav_158',
      t(context === 'microphone' ? 'web_microphone_156' : 'web_camera_157')));
  }
});

it('does not claim that an unreadable camera is definitely busy', () => {
  expect(explainError(new OperationError('camera', new DOMException('', 'NotReadableError')))?.message)
    .toBe(t('web_the_device_may_be_in_use_by_another_app_close_it_and_try_again_165'));
});

it('distinguishes missing devices from a denied permission', () => {
  expect(explainError(new OperationError('microphone', new DOMException('', 'NotFoundError')))?.title).toBe(t('web_microphone_not_found_160'));
});

it('explains network failures without mistaking programming errors for connection failures', () => {
  expect(explainError(new OperationError('network', new TypeError('Failed to fetch')))?.title).toBe(t('web_no_connection_to_the_server_168'));
  expect(explainError(new TypeError('Cannot read properties of undefined'))).toBeUndefined();
});
