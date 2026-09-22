import { t } from './i18n';
export type ErrorContext = 'microphone' | 'camera' | 'network';

export class OperationError extends Error {
  constructor(public context: ErrorContext, cause: unknown) {
    super(context === 'network' ? t('web_no_connection_to_the_server_check_your_internet_connection_and_tr_149') :
      context === 'camera' ? t('text_could_not_turn_on_the_camera_172') : t('web_could_not_enable_the_microphone_150'), { cause });
  }
}

export function explainError(error: unknown, device = { userAgent: '', maxTouchPoints: 0 }): { title: string; message: string; retryLabel?: string } | undefined {
  const context = error instanceof OperationError ? error.context : undefined;
  const cause = error instanceof OperationError ? error.cause : error;
  const name = cause instanceof Error ? cause.name : '';
  if (cause instanceof Error && 'status' in cause && typeof cause.status === 'number' && cause.status >= 500) {
    return { title: t('web_server_temporarily_unavailable_151'), message: t('web_could_not_complete_the_action_try_again_later_152') };
  }
  if (context === 'microphone' || context === 'camera') {
    const mic = context === 'microphone';
    const ios = /iPhone|iPad|iPod/i.test(device.userAgent)
      || (/Macintosh/i.test(device.userAgent) && device.maxTouchPoints > 1);
    if (name === 'NotAllowedError' || name === 'SecurityError') return {
      title: mic ? t('web_allow_microphone_access_153') : t('web_allow_camera_access_154'),
      message: ios
        ? t('web_if_you_denied_access_end_the_call_open_the_app_switcher_and_swipe_155', mic ? t('web_microphone_156') : t('web_camera_157'))
        : t('web_allow_value_access_in_the_site_or_app_settings_if_the_browser_sav_158', mic ? t('web_microphone_156') : t('web_camera_157')),
      retryLabel: t('web_check_access_159'),
    };
    if (name === 'NotFoundError') return {
      title: mic ? t('web_microphone_not_found_160') : t('web_camera_not_found_161'),
      message: t('web_check_the_device_connection_and_try_again_162'),
    };
    if (name === 'NotReadableError' || name === 'AbortError') return {
      title: mic ? t('web_microphone_unavailable_163') : t('web_camera_unavailable_164'),
      message: t('web_the_device_may_be_in_use_by_another_app_close_it_and_try_again_165'),
    };
    return { title: mic ? t('web_could_not_enable_the_microphone_166') : t('text_could_not_turn_on_the_camera_172'),
      message: t('web_check_device_permissions_and_availability_the_app_must_be_opened__167') };
  }
  if (context === 'network' || name === 'TimeoutError' ||
      (cause instanceof TypeError && /fetch|network|load failed/i.test(cause.message))) {
    return { title: t('web_no_connection_to_the_server_168'), message: t('web_check_your_internet_connection_if_it_works_the_server_may_be_temp_169') };
  }
  return undefined;
}
