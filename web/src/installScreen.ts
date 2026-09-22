import { t } from './i18n';
import { installationPageURL, isInstalledPWA, launchContext, type LaunchEnvironment, type RelatedApp } from './installation';

type InstallPrompt = Event & {
  prompt(): Promise<unknown>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
};
type InstallNavigator = Navigator & LaunchEnvironment & { getInstalledRelatedApps?: () => Promise<RelatedApp[]> };

// Return before the app attaches call handlers or acquires the client lock.
export function showInstallationScreen(root: HTMLElement, base: string): boolean {
  const device = navigator as InstallNavigator;
  const standalone = window.matchMedia('(display-mode: standalone)');
  const context = launchContext({
    userAgent: device.userAgent, maxTouchPoints: device.maxTouchPoints, userAgentData: device.userAgentData,
    standalone: standalone.matches || device.standalone === true,
  });
  const install = context !== 'app';
  const url = installationPageURL(location.href, install);

  let prompt: InstallPrompt | undefined;
  window.addEventListener('beforeinstallprompt', event => {
    // Desktop users work in the browser; keep its automatic install promotion
    // hidden too. The browser's own installation menu remains available.
    event.preventDefault();
    if (install) prompt = event as InstallPrompt;
  });
  if (url !== location.href) {
    // Client.url is the document's creation URL. replaceState would leave the
    // worker seeing the old URL and let an installation tab intercept calls.
    location.replace(url);
    return true;
  }
  if (!install) return false;

  const page = element('main', 'credential-screen install-screen');
  const content = element('div', 'install-content');
  const brand = element('header', 'login-brand');
  const logo = element('img', 'install-logo');
  logo.src = new URL('icon-192.png', base).href;
  logo.alt = ''; logo.width = 64; logo.height = 64;
  const brandText = element('div');
  brandText.append(element('h1', '', 'TiniTalk'), element('p', '', t('text_calls_for_your_circle_288')));
  brand.append(logo, brandText);

  const status = element('div', 'install-status');
  status.setAttribute('role', 'status');
  const title = element('h2');
  const description = element('p', 'install-description');
  status.append(title, description);
  const button = element('button', 'primary wide');
  button.type = 'button'; button.textContent = t('web_install_app_84');
  button.setAttribute('aria-controls', 'install-instructions');
  const instructions = element('section', 'install-instructions');
  instructions.id = 'install-instructions';
  const steps = element('ol');
  const texts = context === 'ios-install' ? [
    t('web_tap_share_in_the_browser_menu_85'),
    t('web_choose_add_to_home_screen_86'),
    t('web_if_there_is_an_open_as_web_app_switch_leave_it_on_tap_add_87'),
    t('web_open_tinitalk_using_its_home_screen_icon_88'),
  ] : [
    t('web_open_the_browser_menu_89'),
    t('web_choose_install_app_or_add_to_home_screen_90'),
    t('web_confirm_installation_and_open_tinitalk_using_its_home_screen_icon_91'),
  ];
  steps.append(...texts.map(text => element('li', '', text)));
  instructions.append(element('h3', '', t('web_how_to_install_92')), steps,
    element('p', 'install-help', context === 'ios-install'
      ? t('web_if_this_option_is_missing_open_this_site_in_safari_installation_m_93')
      : t('web_if_this_option_is_missing_open_this_site_in_chrome_installation_m_94')));
  const existing = element('p', 'install-existing', t('web_already_installed_open_tinitalk_using_its_home_screen_icon_95'));
  content.append(brand, status, button, instructions, existing);
  page.append(content); root.replaceChildren(page);

  let installed = false;
  let showingInstructions = false;
  let prompting = false;
  let detection = 0;
  function render(): void {
    title.textContent = installed ? t('web_app_already_installed_96') : t('web_install_tinitalk_97');
    description.textContent = installed ? t('web_open_tinitalk_using_its_home_screen_icon_88')
      : t('web_on_phones_tinitalk_works_as_an_app_add_it_to_your_home_screen_to__98');
    button.hidden = installed;
    button.disabled = prompting;
    button.setAttribute('aria-expanded', String(showingInstructions && !installed));
    instructions.hidden = installed || !showingInstructions;
    existing.hidden = installed;
  }
  function installedNow(): void {
    detection += 1; installed = true; prompt = undefined; render();
  }
  async function checkInstalled(): Promise<void> {
    const request = ++detection;
    const result = await isInstalledPWA(base, device.getInstalledRelatedApps?.bind(device));
    if (request !== detection || result === undefined) return;
    installed = result;
    if (installed) prompt = undefined;
    render();
  }
  button.onclick = async () => {
    if (prompting || installed) return;
    const event = prompt; prompt = undefined;
    if (!event) { showingInstructions = !showingInstructions; render(); return; }
    prompting = true; render();
    try {
      // Must happen directly in the click handler, while activation is valid.
      await event.prompt();
      await event.userChoice;
    } catch { showingInstructions = true; }
    finally { prompting = false; render(); }
  };
  window.addEventListener('appinstalled', installedNow);
  window.addEventListener('pageshow', () => { void checkInstalled(); });
  document.addEventListener('visibilitychange', () => { if (!document.hidden) void checkInstalled(); });
  standalone.addEventListener('change', () => { if (standalone.matches) location.reload(); });
  render();
  void checkInstalled();

  // Installation/offline assets must work before login. Do not activate a
  // waiting shell here: a different window may still have an active call.
  if ('serviceWorker' in navigator && import.meta.env.PROD) {
    void navigator.serviceWorker.register(new URL('shell-worker.js', base), { scope: base, updateViaCache: 'none' }).catch(() => undefined);
  }
  return true;
}

function element<K extends keyof HTMLElementTagNameMap>(tag: K, className = '', text = ''): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  node.className = className;
  if (text) node.textContent = text;
  return node;
}
