import { afterEach, expect, it, vi } from 'vitest';
import { showInstallationScreen } from './installScreen';

afterEach(() => vi.unstubAllGlobals());

function environment(href: string, mobile: boolean, standalone = false) {
  const replace = vi.fn(), addEventListener = vi.fn();
  vi.stubGlobal('navigator', { userAgent: mobile ? 'Android' : 'Windows', standalone });
  vi.stubGlobal('location', { href, replace });
  vi.stubGlobal('window', { matchMedia: () => ({ matches: false }), addEventListener });
  // No DOM: redirecting/install gating must happen before rendering or startup.
  return { replace, addEventListener };
}

it('navigates to the installation URL so the worker sees its real creation URL', () => {
  const { replace } = environment('https://web.example/#account=a&call=b', true);
  expect(showInstallationScreen({} as HTMLElement, 'https://web.example/')).toBe(true);
  expect(replace).toHaveBeenCalledWith('https://web.example/?install=1#account=a&call=b');
});

it('removes a shared installation marker with a real navigation inside the PWA', () => {
  const { replace } = environment('https://web.example/?install=1#account=a&call=b', true, true);
  expect(showInstallationScreen({} as HTMLElement, 'https://web.example/')).toBe(true);
  expect(replace).toHaveBeenCalledWith('https://web.example/#account=a&call=b');
});

it('allows desktop startup and suppresses automatic installation promotion', () => {
  const { replace, addEventListener } = environment('https://web.example/', false);
  expect(showInstallationScreen({} as HTMLElement, 'https://web.example/')).toBe(false);
  expect(replace).not.toHaveBeenCalled();
  const preventDefault = vi.fn();
  addEventListener.mock.calls.find(([name]) => name === 'beforeinstallprompt')![1]({ preventDefault });
  expect(preventDefault).toHaveBeenCalledOnce();
});
