import { describe, expect, it } from 'vitest';
import { t } from './i18n';
import { decidePushNotification } from './pushNotificationPolicy';
import type { PushRecord } from './model';

const baseRecord: PushRecord = {
  id: 'account-a:call-1',
  accountId: 'account-a',
  callId: 'call-1',
  type: 'incoming_call',
  caller: 'alex',
  expiresAt: 2_000,
  receivedAt: 1,
  sessionId: 'session-a',
};

describe('push notification policy', () => {
  it.each([
    'Mozilla/5.0 (Linux; Android 10; SM-N960F) AppleWebKit/537.36 Chrome/152.0.7977.83 Mobile Safari/537.36',
    'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/152.0.0.0 Mobile Safari/537.36',
  ])('avoids colliding buttons on affected Android Chrome: %s', userAgent => {
    const decision = decidePushNotification({ record: baseRecord, raw: {}, now: 1_000, hasVisibleClient: false, maxActions: 2, userAgent });
    expect(decision.actions).toEqual([{ action: 'reject', title: t('text_decline_63') }]);
    expect(decision.defaultAction).toBe('answer');
    expect(decision.body).toContain(t('web_tap_the_notification_to_answer_123'));
  });

  it.each([
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/152.0.0.0 Safari/537.36',
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/152.0.0.0 Safari/537.36 Edg/152.0.0.0',
    'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/153.0.0.0 Mobile Safari/537.36',
    'Mozilla/5.0 (Android 10; Mobile; rv:150.0) Gecko/150.0 Firefox/150.0',
  ])('keeps both actions on browsers without the Android Chromium collision: %s', userAgent => {
    const decision = decidePushNotification({ record: baseRecord, raw: {}, now: 1_000, hasVisibleClient: false, maxActions: 2, userAgent });
    expect(decision.actions).toEqual([{ action: 'answer', title: t('web_answer_125') }, { action: 'reject', title: t('text_decline_63') }]);
    expect(decision.defaultAction).toBeUndefined();
  });

  it('shows a system notification only for a fresh incoming call without a visible app window', () => {
    expect(decidePushNotification({
      record: baseRecord,
      raw: { caller: 'Алексей' },
      ownerName: 'alex_web',
      now: 1_000,
      hasVisibleClient: false,
      maxActions: 2,
    })).toEqual({
      show: true,
      openVisibleClient: false,
      closeExisting: false,
      title: 'Алексей',
      body: `alex_web · ${t('web_tap_to_answer_124')}`,
      actions: [
        { action: 'answer', title: t('web_answer_125') },
        { action: 'reject', title: t('text_decline_63') },
      ],
    });
  });

  it('keeps notification actions in the payload even when support probing is pessimistic', () => {
    expect(decidePushNotification({
      record: baseRecord,
      raw: { caller: 'Алексей' },
      ownerName: 'alex_web',
      now: 1_000,
      hasVisibleClient: false,
      maxActions: 0,
    })).toEqual({
      show: true,
      openVisibleClient: false,
      closeExisting: false,
      title: 'Алексей',
      body: `alex_web · ${t('web_tap_to_answer_124')}`,
      actions: [
        { action: 'answer', title: t('web_answer_125') },
        { action: 'reject', title: t('text_decline_63') },
      ],
    });
  });

  it('opens the visible app window instead of showing a system notification for an incoming call', () => {
    expect(decidePushNotification({
      record: baseRecord,
      raw: { caller: 'Алексей' },
      ownerName: 'alex_web',
      now: 1_000,
      hasVisibleClient: true,
    })).toEqual({
      show: false,
      openVisibleClient: true,
      closeExisting: true,
    });
  });

  it('uses terminal call pushes only to close an existing call notification', () => {
    expect(decidePushNotification({
      record: { ...baseRecord, type: 'call_cancel', expiresAt: 0 },
      raw: { call_event: 'call.expire' },
      ownerName: 'alex_web',
      now: 1_000,
      hasVisibleClient: false,
    })).toEqual({
      show: false,
      openVisibleClient: false,
      closeExisting: true,
    });
  });
});
