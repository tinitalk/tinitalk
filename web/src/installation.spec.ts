import { expect, it } from 'vitest';
import { installationPageURL, isInstallationPageURL, isInstalledPWA, launchContext } from './installation';

const android = 'Mozilla/5.0 (Linux; Android 10; K) Chrome/152.0.0.0 Mobile Safari/537.36';
const iphone = 'Mozilla/5.0 (iPhone; CPU iPhone OS 26_0 like Mac OS X) AppleWebKit/605.1.15 Version/26.0 Mobile/15E148 Safari/604.1';
const mac = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15) AppleWebKit/605.1.15 Version/26.0 Safari/605.1.15';

it.each([
  [{ userAgent: android }, 'android-install'],
  [{ userAgent: 'Mozilla/5.0 (Linux; Android 12; Tablet) Chrome/152.0.0.0 Safari/537.36' }, 'android-install'],
  [{ userAgent: 'Mozilla/5.0 (X11; Linux x86_64)', userAgentData: { platform: 'Android', mobile: false } }, 'android-install'],
  [{ userAgent: iphone }, 'ios-install'],
  [{ userAgent: mac, maxTouchPoints: 5 }, 'ios-install'],
  [{ userAgent: 'another browser', userAgentData: { mobile: true } }, 'mobile-install'],
  [{ userAgent: mac, maxTouchPoints: 0 }, 'app'],
  [{ userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)', maxTouchPoints: 10 }, 'app'],
  [{ userAgent: 'Mozilla/5.0 (X11; Linux x86_64)' }, 'app'],
  [{ userAgent: android, standalone: true }, 'app'],
  [{ userAgent: iphone, standalone: true }, 'app'],
  [{ userAgent: mac, maxTouchPoints: 5, standalone: true }, 'app'],
] as const)('selects the right entry screen for %j', (environment, expected) => {
  expect(launchContext(environment)).toBe(expected);
});

it('marks only the installation tab and preserves incoming-call parameters', () => {
  const original = 'https://family.example/?source=invite#account=family&call=123&action=answer';
  const landing = installationPageURL(original, true);
  expect(isInstallationPageURL(landing)).toBe(true);
  expect(new URL(landing).hash).toBe(new URL(original).hash);
  expect(installationPageURL(landing, false)).toBe(original);
  expect(isInstallationPageURL(original)).toBe(false);
});

it('recognizes this installation only, including a deployment under a subpath', async () => {
  const base = 'https://family.example/talk/';
  expect(await isInstalledPWA(base, async () => [{ platform: 'webapp', url: './manifest.webmanifest' }])).toBe(true);
  expect(await isInstalledPWA(base, async () => [{ platform: 'webapp', url: 'https://official.example/manifest.webmanifest' }])).toBe(false);
  expect(await isInstalledPWA(base, async () => [{ platform: 'webapp', url: '/manifest.webmanifest' }])).toBe(false);
  expect(await isInstalledPWA(base, async () => [{ platform: 'play', id: 'org.tinitalk' }])).toBe(false);
  expect(await isInstalledPWA(base, async () => [])).toBe(false);
});

it('keeps an unknown installation state when detection is unavailable or fails', async () => {
  expect(await isInstalledPWA('https://family.example/')).toBeUndefined();
  expect(await isInstalledPWA('https://family.example/', async () => { throw new Error('unsupported'); })).toBeUndefined();
});
