import { expect, it } from 'vitest';
import { explainError, OperationError } from './userErrors';

it.each(['microphone', 'camera'] as const)('explains denied %s access without browser jargon', context => {
  const result = explainError(new OperationError(context, new DOMException('Permission denied', 'NotAllowedError')));
  expect(result?.title).toBe(context === 'microphone' ? 'Разрешите микрофон' : 'Разрешите камеру');
  expect(result?.message).toContain('настройках');
  expect(result?.retryLabel).toBe('Проверить доступ');
  expect(result?.message).toContain('сохранил запрет');
});

it.each(['microphone', 'camera'] as const)('suggests restarting the iOS PWA after denied %s access', context => {
  const error = new OperationError(context, new DOMException('Permission denied', 'NotAllowedError'));
  for (const device of [
    { userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X)', maxTouchPoints: 5 },
    { userAgent: 'Mozilla/5.0 (iPad; CPU OS 18_7 like Mac OS X)', maxTouchPoints: 5 },
    { userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15)', maxTouchPoints: 5 },
  ]) {
    const result = explainError(error, device);
    expect(result?.message).toContain('смахните TiniTalk вверх');
    expect(result?.message).toContain('завершите текущий звонок');
    expect(result?.message).toContain('«Разрешить»');
    expect(result?.message.includes('настройках')).toBe(false);
  }
  for (const device of [
    { userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15)', maxTouchPoints: 0 },
    { userAgent: 'Mozilla/5.0 (Linux; Android 16)', maxTouchPoints: 5 },
  ]) {
    expect(explainError(error, device)?.message).toContain('настройках');
  }
});

it('does not claim that an unreadable camera is definitely busy', () => {
  expect(explainError(new OperationError('camera', new DOMException('', 'NotReadableError')))?.message).toContain('Возможно');
});

it('distinguishes missing devices from a denied permission', () => {
  expect(explainError(new OperationError('microphone', new DOMException('', 'NotFoundError')))?.title).toBe('Микрофон не найден');
});

it('explains network failures without mistaking programming errors for connection failures', () => {
  expect(explainError(new OperationError('network', new TypeError('Failed to fetch')))?.title).toBe('Нет подключения к серверу');
  expect(explainError(new TypeError('Cannot read properties of undefined'))).toBeUndefined();
});
