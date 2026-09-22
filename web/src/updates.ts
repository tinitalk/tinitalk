import { t, currentLocale } from './i18n';
import { accountScope, type Account } from './model';

export const webBuild = import.meta.env.VITE_WEB_BUILD_ID || 'dev';
export const webCommit = import.meta.env.VITE_WEB_COMMIT || 'unknown';
export type BuildVersions = { build: string; shell: string; push: string };
export type WorkerVersion = { version: string | null; state: ServiceWorkerState };
export type WorkerReport = {
  label: string; kind: 'shell' | 'push'; expected: string; enabled: boolean;
  active: WorkerVersion | null; waiting: WorkerVersion | null; installing: boolean;
  subscribed?: boolean | null;
};
export type UpdateReport = {
  build: string; latest?: BuildVersions; error?: string; checkedAt: number;
  controller: WorkerVersion | null; workers: WorkerReport[];
};

// Ask the executing code through a private channel. A script URL or a waiting
// worker alone is not evidence that an installed PWA is running the new code.
export function readWorkerVersion(worker: ServiceWorker | null, kind: 'shell' | 'push'): Promise<WorkerVersion | null> {
  if (!worker) return Promise.resolve(null);
  return new Promise(resolve => {
    const channel = new MessageChannel();
    const finish = (version: string | null) => {
      clearTimeout(timeout); channel.port1.close(); channel.port2.close();
      resolve({ version, state: worker.state });
    };
    const timeout = setTimeout(() => finish(null), 3000);
    channel.port1.onmessage = event => {
      const reply = event.data;
      if (reply?.type === 'tinitalk-worker-version' && reply.kind === kind && typeof reply.version === 'string') finish(reply.version);
    };
    try { worker.postMessage({ type: 'tinitalk-worker-version' }, [channel.port2]); }
    catch { finish(null); }
  });
}

export function waitForWorker(worker: ServiceWorker, states: ServiceWorkerState[]): Promise<void> {
  return new Promise((resolve, reject) => {
    const finish = (error?: Error) => {
      clearTimeout(timeout); worker.removeEventListener('statechange', changed);
      if (error) reject(error); else resolve();
    };
    const changed = () => {
      if (states.includes(worker.state)) finish();
      else if (worker.state === 'redundant') finish(new Error(t('web_could_not_install_the_update_134')));
    };
    const timeout = setTimeout(() => finish(new Error(t('web_the_update_has_not_been_installed_yet_check_again_135'))), 15000);
    worker.addEventListener('statechange', changed); changed();
  });
}

export async function fetchBuildVersions(base: string): Promise<BuildVersions> {
  let response: Response;
  try { response = await fetch(new URL('version.json', base), { cache: 'no-store', signal: AbortSignal.timeout(8000) }); }
  catch { throw new Error(t('web_could_not_check_the_server_build_check_your_connection_136')); }
  if (!response.ok) throw new Error(t('web_the_server_did_not_report_a_build_time_check_that_the_new_web_ver_137'));
  let value;
  try { value = await response.json(); }
  catch { throw new Error(t('web_the_server_returned_invalid_build_information_138')); }
  if (!value || !['build', 'shell', 'push'].every(key => typeof value[key] === 'string' && value[key].length > 0 && value[key].length < 100)) {
    throw new Error(t('web_could_not_read_the_server_build_information_139'));
  }
  return { build: value.build, shell: value.shell, push: value.push };
}

export async function inspectUpdates(base: string, accounts: Account[], build = webBuild): Promise<UpdateReport> {
  const report: UpdateReport = { build, checkedAt: Date.now(), controller: null, workers: [] };
  const latestJob = fetchBuildVersions(base).then(latest => { report.latest = latest; }).catch(error => {
    report.error = error instanceof Error ? error.message : t('web_could_not_reach_the_server_140');
  });
  if (!('serviceWorker' in navigator)) {
    await latestJob;
    report.error = t('web_service_worker_unavailable_open_the_installed_app_over_https_141');
    return report;
  }
  const sw = navigator.serviceWorker;
  const [registrations, controller] = await Promise.all([
    sw.getRegistrations(), readWorkerVersion(sw.controller, 'shell'), latestJob,
  ]);
  report.controller = controller;
  const targets = [
    { scope: base, label: t('web_app_shell_142'), kind: 'shell' as const, enabled: true },
    ...accounts.filter(owner => !owner.sessionReplaced).map(owner => ({ scope: accountScope(base, owner.id), label: `Push · ${owner.login} · ${new URL(owner.server).host}`, kind: 'push' as const, enabled: Boolean(owner.pushConfigId) })),
  ];
  report.workers = await Promise.all(targets.map(async target => {
    // Exact scopes exclude obsolete /app/ registrations and other accounts.
    const registration = registrations.find(item => item.scope === target.scope);
    const [active, waiting, subscribed] = await Promise.all([
      readWorkerVersion(registration?.active ?? null, target.kind),
      readWorkerVersion(registration?.waiting ?? null, target.kind),
      target.kind === 'push' && registration ? registration.pushManager.getSubscription().then(value => Boolean(value)).catch(() => null) : Promise.resolve(false),
    ]);
    return { label: target.label, kind: target.kind, enabled: target.enabled, expected: report.latest?.[target.kind] || '',
      active, waiting, installing: Boolean(registration?.installing), ...(target.kind === 'push' ? { subscribed } : {}) };
  }));
  report.checkedAt = Date.now();
  return report;
}

export function updateStatus(report: UpdateReport): { kind: 'ready' | 'update' | 'unknown' | 'notifications'; text: string } {
  if (report.error || !report.latest) return { kind: 'unknown', text: report.error || t('web_could_not_check_for_updates_143') };
  const enabled = report.workers.filter(item => item.enabled);
  if (report.build !== report.latest.build || enabled.some(item => item.waiting || item.installing || !item.active || item.active.state !== 'activated')) {
    return { kind: 'update', text: t('web_update_available_tap_update_app_144') };
  }
  if (!report.controller || !report.controller.version || enabled.some(item => !item.active?.version)) {
    return { kind: 'unknown', text: t('web_some_background_services_did_not_report_their_version_tap_update__145') };
  }
  if (report.controller.version !== report.latest.shell || enabled.some(item => item.active?.version !== item.expected)) {
    return { kind: 'update', text: t('web_some_components_have_not_updated_tap_update_app_146') };
  }
  if (enabled.some(item => item.kind === 'push' && item.subscribed !== true)) {
    return { kind: 'notifications', text: t('web_the_build_is_updated_but_the_notification_subscription_is_not_con_147') };
  }
  return { kind: 'ready', text: t('web_everything_is_up_to_date_app_and_active_background_services_148') };
}

export function canUpdateApplication(report: UpdateReport | undefined): boolean {
  if (!report?.latest || report.error) return false;
  const kind = updateStatus(report).kind;
  // A known deployment can also repair a worker that cannot report its version.
  return kind === 'update' || kind === 'unknown';
}

export function buildTime(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString(currentLocale(), { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit', timeZoneName: 'short' });
}
