export type LaunchEnvironment = {
  userAgent: string;
  maxTouchPoints?: number;
  userAgentData?: { mobile?: boolean; platform?: string };
  standalone?: boolean;
};
export type LaunchContext = 'app' | 'ios-install' | 'android-install' | 'mobile-install';
export type RelatedApp = { platform: string; url?: string; id?: string };

export function launchContext(environment: LaunchEnvironment): LaunchContext {
  if (environment.standalone) return 'app';
  const { userAgent, userAgentData, maxTouchPoints = 0 } = environment;
  if (/iPhone|iPad|iPod/i.test(userAgent) || (/Macintosh/i.test(userAgent) && maxTouchPoints > 1)) return 'ios-install';
  if (/Android/i.test(userAgent) || userAgentData?.platform === 'Android') return 'android-install';
  if (userAgentData?.mobile || /Mobile|Tablet/i.test(userAgent)) return 'mobile-install';
  return 'app';
}

// The worker can exclude this tab even after being restarted. Never use this
// marker to decide whether the page is allowed to run the calling interface.
export function installationPageURL(href: string, install: boolean): string {
  const url = new URL(href);
  if (install) url.searchParams.set('install', '1');
  else url.searchParams.delete('install');
  return url.href;
}

export function isInstallationPageURL(href: string): boolean {
  return new URL(href).searchParams.get('install') === '1';
}

export async function isInstalledPWA(base: string, query?: () => Promise<RelatedApp[]>): Promise<boolean | undefined> {
  if (!query) return undefined;
  try {
    const manifest = new URL('manifest.webmanifest', base).href;
    return (await query()).some(app => app.platform === 'webapp' && app.url && new URL(app.url, base).href === manifest);
  } catch { return undefined; }
}
