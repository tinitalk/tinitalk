export type ErrorContext = 'microphone' | 'camera' | 'network';

export class OperationError extends Error {
  constructor(public context: ErrorContext, cause: unknown) {
    super(context === 'network' ? 'Нет подключения к серверу. Проверьте интернет и повторите действие.' :
      context === 'camera' ? 'Не удалось включить камеру.' : 'Не удалось включить микрофон.', { cause });
  }
}

export function explainError(error: unknown, device = { userAgent: '', maxTouchPoints: 0 }): { title: string; message: string; retryLabel?: string } | undefined {
  const context = error instanceof OperationError ? error.context : undefined;
  const cause = error instanceof OperationError ? error.cause : error;
  const name = cause instanceof Error ? cause.name : '';
  if (cause instanceof Error && 'status' in cause && typeof cause.status === 'number' && cause.status >= 500) {
    return { title: 'Сервер временно недоступен', message: 'Не удалось выполнить действие. Попробуйте ещё раз позже.' };
  }
  if (context === 'microphone' || context === 'camera') {
    const mic = context === 'microphone';
    const ios = /iPhone|iPad|iPod/i.test(device.userAgent)
      || (/Macintosh/i.test(device.userAgent) && device.maxTouchPoints > 1);
    if (name === 'NotAllowedError' || name === 'SecurityError') return {
      title: mic ? 'Разрешите микрофон' : 'Разрешите камеру',
      message: ios
        ? `Если вы отказали в доступе, завершите текущий звонок, откройте переключатель приложений и смахните TiniTalk вверх. Затем откройте приложение заново с экрана «Домой». При запросе доступа к ${mic ? 'микрофону' : 'камере'} нажмите «Разрешить».`
        : `Разрешите доступ к ${mic ? 'микрофону' : 'камере'} в настройках сайта или приложения. Если браузер сохранил запрет, повторное нажатие не откроет запрос разрешения. После изменения настройки нажмите «Проверить доступ».`,
      retryLabel: 'Проверить доступ',
    };
    if (name === 'NotFoundError') return {
      title: mic ? 'Микрофон не найден' : 'Камера не найдена',
      message: 'Проверьте подключение устройства и повторите действие.',
    };
    if (name === 'NotReadableError' || name === 'AbortError') return {
      title: mic ? 'Микрофон недоступен' : 'Камера недоступна',
      message: 'Возможно, устройство занято другим приложением. Закройте его и повторите действие.',
    };
    return { title: mic ? 'Не удалось включить микрофон' : 'Не удалось включить камеру',
      message: 'Проверьте разрешения и доступность устройства. Приложение должно быть открыто по HTTPS.' };
  }
  if (context === 'network' || name === 'TimeoutError' ||
      (cause instanceof TypeError && /fetch|network|load failed/i.test(cause.message))) {
    return { title: 'Нет подключения к серверу', message: 'Проверьте интернет. Если подключение есть, возможно, сервер временно недоступен. Повторите действие позже.' };
  }
  return undefined;
}
