/// <reference lib="webworker" />
declare const __WORKER_VERSION__: string;

export function reportWorkerVersion(scope: ServiceWorkerGlobalScope, kind: 'shell' | 'push'): void {
  scope.addEventListener('message', event => {
    if (event.data?.type !== 'tinitalk-worker-version') return;
    event.ports[0]?.postMessage({ type: 'tinitalk-worker-version', kind,
      version: typeof __WORKER_VERSION__ === 'undefined' ? 'dev' : __WORKER_VERSION__ });
  });
}
