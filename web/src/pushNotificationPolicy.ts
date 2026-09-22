import { t } from './i18n';
import type { PushRecord } from './model';

// Covers Safari on macOS, iPhone browsers and iPad's desktop user agent. Other
// browser engines use their existing policy even if their UA says AppleWebKit.
export function usesAppleWebPush(userAgent: string): boolean {
  return /AppleWebKit\//.test(userAgent) && !/\b(?:Chrome|Chromium|Android|Edg|OPR)\b/.test(userAgent);
}

export function hasNotificationActionCollision(userAgent: string): boolean {
  // Chromium's Android PendingIntent wrappers collapsed multiple action buttons
  // to the last action before M153. JS cannot recover the original button.
  // https://crbug.com/534387021
  const chrome = /\b(?:Chrome|Chromium)\/(\d+)\./.exec(userAgent);
  return /\bAndroid\b/i.test(userAgent) && Boolean(chrome && Number(chrome[1]) < 153);
}

type PushNotificationAction = { action: 'answer' | 'reject'; title: string };

type PushNotificationDecision = {
  show: boolean;
  openVisibleClient: boolean;
  closeExisting: boolean;
  title?: string;
  body?: string;
  actions?: PushNotificationAction[];
  defaultAction?: 'answer';
  silent?: boolean;
};

type PushNotificationInput = {
  record: PushRecord;
  raw: Record<string, string>;
  ownerName?: string;
  now?: number;
  hasVisibleClient: boolean;
  maxActions?: number;
  userAgent?: string;
  requiresVisibleNotification?: boolean;
};

export function decidePushNotification(input: PushNotificationInput): PushNotificationDecision {
  const now = input.now ?? Date.now();
  const incoming = input.record.type === 'incoming_call' && input.record.expiresAt > now;
  const apple = input.requiresVisibleNotification || usesAppleWebPush(input.userAgent || '');
  if (apple) {
    // Apple revokes subscriptions used for invisible pushes. Even an invite
    // racing cancellation or an open app needs a visible (possibly quiet)
    // result. The server stops sending silent cancellation/sync to Apple.
    if (incoming) {
      const caller = input.raw.caller || input.record.caller;
      return {
        show: true, openVisibleClient: input.hasVisibleClient, closeExisting: false,
        title: caller ? t('web_value_is_calling_118', caller) : t('web_incoming_call_119'),
        body: t('web_value_tap_to_open_the_incoming_call_120', input.ownerName || 'TiniTalk'),
        silent: input.hasVisibleClient,
      };
    }
    if (input.record.type === 'session_replaced') {
      return { show: true, openVisibleClient: false, closeExisting: false,
        title: t('web_signed_in_on_another_device_121'), body: input.ownerName || 'TiniTalk', silent: false };
    }
    return {
      show: true, openVisibleClient: false, closeExisting: false, silent: true,
      title: input.record.type === 'incoming_call' || input.record.type === 'call_cancel' ? t('web_the_call_has_already_ended_122') : 'TiniTalk',
      body: t('web_open_the_app_to_check_your_calls_110'),
    };
  }
  if (incoming && input.hasVisibleClient) {
    return { show: false, openVisibleClient: true, closeExisting: true };
  }
  if (incoming) {
    const singleAction = hasNotificationActionCollision(input.userAgent || '');
    return {
      show: true,
      openVisibleClient: false,
      closeExisting: false,
      title: input.raw.caller || input.record.caller || t('text_incoming_call_62'),
      body: `${input.ownerName || 'TiniTalk'} · ${singleAction ? t('web_tap_the_notification_to_answer_123') : t('web_tap_to_answer_124')}`,
      actions: singleAction ? [{ action: 'reject', title: t('text_decline_63') }] : [
        { action: 'answer' as const, title: t('web_answer_125') },
        { action: 'reject' as const, title: t('text_decline_63') },
      ],
      ...(singleAction ? { defaultAction: 'answer' as const } : {}),
    };
  }
  if (input.record.type === 'call_cancel' || input.record.type === 'incoming_call') {
    return { show: false, openVisibleClient: false, closeExisting: true };
  }
  if (input.record.type === 'session_replaced') {
    return {
      show: true,
      openVisibleClient: false,
      closeExisting: false,
      title: t('web_signed_in_on_another_device_121'),
      body: input.ownerName || 'TiniTalk',
    };
  }
  return { show: false, openVisibleClient: false, closeExisting: false };
}
