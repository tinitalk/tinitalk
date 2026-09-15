import { explainError } from './userErrors';
import './style.css';
import { showInstallationScreen } from './installScreen';
import {
  accountForLogin,
  callKey,
  canOpenIncoming,
  normalizeServer,
  type Account,
  type Contact,
  type ContactPhoto,
  type HistoryItem,
  type HistoryPage,
  type SignalEvent,
} from './model';
import { accounts, saveAccount, deleteAccount, readPush, prunePushes, contactPhotos, saveContactPhoto, deleteContactPhoto } from './storage';
import { api, APIError, setSessionReplacedHandler } from './api';
import { closeCallNotification, enablePush, disablePush, isActiveNotificationCall, notificationCallState, pushEnabled, pushSupport, requestPushPermission, updatePushWorker } from './push';
import { SignalConnection, SignalError } from './signal';
import { IncomingCallVisibility } from './incomingVisibility';
import { AudioCall, type CallSecurityState, type CallTransportRoute, type CallVideoState } from './media';
import { securityEmoji, type CallSecurityFailureReason, type CallSecurityUnavailableReason } from './sas';
import { CallToneController, type CallToneEndReason, type CallToneState } from './callTones';
import { buildTime, canUpdateApplication, fetchBuildVersions, inspectUpdates, updateStatus, waitForWorker, webBuild, type UpdateReport } from './updates';
import { microphoneControlIcon } from './callControls';

const base = new URL('./', document.baseURI).href;
const root = document.querySelector<HTMLDivElement>('#app')!;
root.innerHTML = '<audio id="remote-audio" autoplay playsinline></audio><div id="screen"></div><section id="call-layer" hidden><video id="remote-media" autoplay playsinline muted></video><div id="call-content"></div></section><p id="notice" role="status" aria-live="polite" hidden></p>';

const screen = document.querySelector<HTMLDivElement>('#screen')!;
const callLayer = document.querySelector<HTMLElement>('#call-layer')!;
const callContent = document.querySelector<HTMLElement>('#call-content')!;
const audio = document.querySelector<HTMLAudioElement>('#remote-audio')!;
const remoteVideo = document.querySelector<HTMLVideoElement>('#remote-media')!;

type Route =
  | { name: 'home' }
  | { name: 'login'; accountId?: string }
  | { name: 'add-account' }
  | { name: 'add-contact' }
  | { name: 'profile' }
  | { name: 'about' }
  | { name: 'contact'; accountId: string; login: string };
type Tab = 'contacts' | 'history';
type AppHistoryState = { tinitalk: 'web'; route: Route; tab: Tab; index: number; overlay?: true };
type AccountContact = Contact & { account: Account };
type AccountHistory = HistoryItem & { account: Account };
type ActiveCall = {
  account: Account;
  id: string;
  peer: string;
  peerLogin: string;
  incoming: boolean;
  started: boolean;
  accepted: boolean;
  answering?: boolean;
  seq: number;
  status: string;
  security: CallSecurityState;
  transportRoute: CallTransportRoute;
  video: CallVideoState;
  media: AudioCall;
  muted: boolean;
  audioBlocked?: boolean;
  connectedAt?: number;
  expiry?: ReturnType<typeof setTimeout>;
  incomingExpiresAt?: number;
};
type CallReplyCode = 'cannot_talk' | 'call_me_later' | 'will_call_back';
type EndedCall = {
  accountId: string;
  peer: string;
  peerLogin: string;
  status: string;
  detail: string;
  explanation: string;
};
type ServerHealth = { service?: string; status?: string; api_version?: number; commit?: string; features?: string[] };
type AboutServerState = { loading: boolean; details?: ServerHealth; error?: string };
type CropTransform = { scale: number; offsetX: number; offsetY: number };
type CropViewport = { width: number; height: number };
type CropSourceRect = { left: number; top: number; size: number };
type SinkAudioElement = HTMLMediaElement & {
  sinkId?: string;
  setSinkId?: (sinkId: string) => Promise<void>;
};
type AudioOutputDevice = { id: string; label: string };
type AudioOutputSelectionDevice = MediaDeviceInfo | { deviceId?: string; label?: string; kind?: string };
type AudioOutputMediaDevices = MediaDevices & {
  selectAudioOutput?: (options?: { deviceId?: string }) => Promise<AudioOutputSelectionDevice>;
};
type LocalPreviewPosition = { left: number; top: number };
type LocalPreviewBounds = { left: number; top: number; right: number; bottom: number };
type SelfPreviewCorner = 'TopLeft' | 'TopRight' | 'BottomLeft' | 'BottomRight';
type NotificationCallAction = 'answer' | 'reject';

const list: Account[] = [];
const removingAccounts = new Set<Account>();
const connections = new Map<string, SignalConnection>();
const states = new Map<string, string>();
const notifications = new Map<string, boolean>();
const contactsByAccount = new Map<string, Contact[]>();
const historyByAccount = new Map<string, HistoryItem[]>();
const unreadMissedByContact = new Map<string, number>();
const unreadMissedCountByAccount = new Map<string, number>();
const unreadVersions = new WeakMap<Account, number>();
const unreadReads = new WeakMap<Account, Promise<void>>();
const contactHistory = new Map<string, HistoryItem[]>();
const historyCursors = new Map<string, number>();
const historyErrors = new Set<string>();
const contactHistoryCursors = new Map<string, number>();
const contactHistoryErrors = new Set<string>();
let historyVisibleLimit = 50;
let loadingMoreHistory = false;
let historyObserver: IntersectionObserver | undefined;
let contactHistoryGeneration = 0;
let historyRevision = 0;
const contactPhotosByKey = new Map<string, string>();
const pendingNotificationActions = new Map<string, NotificationCallAction>();
let resolveAccountsReady!: () => void;
const accountsReady = new Promise<void>(resolve => { resolveAccountsReady = resolve; });
const openingNotificationCalls = new Map<string, Promise<void>>();
const recoveringAccounts = new Map<string, Promise<void>>();
const loadingContactHistory = new Set<string>();
const aboutServers = new Map<string, AboutServerState>();
let audioOutputs: AudioOutputDevice[] = [];
let audioOutputsLoading: Promise<void> | undefined;
const callReplies: { code: CallReplyCode; text: string; result: string; receivedHistory: string; sentHistory: string }[] = [
  { code: 'cannot_talk', text: 'Не могу говорить', result: 'сейчас не может говорить', receivedHistory: 'Не могли говорить', sentHistory: 'Вы не могли говорить' },
  { code: 'call_me_later', text: 'Перезвоните мне позднее', result: 'просит перезвонить позже', receivedHistory: 'Просили перезвонить позже', sentHistory: 'Вы просили перезвонить позже' },
  { code: 'will_call_back', text: 'Я вам перезвоню', result: 'обещает перезвонить позже', receivedHistory: 'Обещали перезвонить', sentHistory: 'Вы обещали перезвонить' },
];
const maxCropScale = 12;

let route: Route = { name: 'home' };
let tab: Tab = 'contacts';
let historyIndex = 0;
let loadingContacts = false;
let loadingHistory = false;
let shellRegistration: ServiceWorkerRegistration | undefined;
let updateReport: UpdateReport | undefined;
let checkingUpdates = false;
let updatingApp = false;
let updateError = '';
let accountSubmission: Promise<void> | undefined;
let current: ActiveCall | null = null;
let endedCall: EndedCall | null = null;
let pullRefreshing: Tab | null = null;
let callTicker: ReturnType<typeof setInterval> | undefined;
let endedCallTimer: ReturnType<typeof setTimeout> | undefined;
let noticeTimer: ReturnType<typeof setTimeout> | undefined;
let activeOverlayClose: (() => void) | undefined;
let pendingOverlayHistoryRemoval: (() => void) | undefined;
let localPreviewCallId = '';
let localPreviewDragPosition: LocalPreviewPosition | undefined;
let localPreviewCorner: SelfPreviewCorner = readSelfPreviewCorner();
let videoControlsCallId = '';
let videoControlsVisible = true;
let videoControlsHideTimer: ReturnType<typeof setTimeout> | undefined;
const callTones = new CallToneController();
const incomingVisibility = new IncomingCallVisibility(
  () => current?.incoming && !current.accepted && !callLayer.hidden &&
    callLayer.querySelector('.incoming-call-screen') && (current.incomingExpiresAt ?? 0) > Date.now()
    ? { accountId: current.account.id, callId: current.id } : null,
  () => document.visibilityState === 'visible',
  (target, visible) => { connections.get(target.accountId)?.sendCallVisibility(target.callId, visible); },
);
const videoControlsAutoHideMs = 5_000;
const selfPreviewCornerStorageKey = 'tinitalk.selfPreviewCorner';
const selfPreviewCorners: SelfPreviewCorner[] = ['TopLeft', 'TopRight', 'BottomLeft', 'BottomRight'];

type IconPath = string | { d: string; fill?: boolean; strokeWidth?: number };

const iconPaths = {
  arrowBack: ['M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.42,-1.41L7.83,13H20v-2z'],
  call: ['M6.62,10.79C8.06,13.62 10.38,15.93 13.21,17.38L15.41,15.18C15.68,14.91 16.08,14.82 16.43,14.94C17.55,15.31 18.75,15.5 20,15.5C20.55,15.5 21,15.95 21,16.5V20C21,20.55 20.55,21 20,21C10.61,21 3,13.39 3,4C3,3.45 3.45,3 4,3H7.5C8.05,3 8.5,3.45 8.5,4C8.5,5.25 8.69,6.45 9.06,7.57C9.17,7.92 9.09,8.31 8.81,8.59L6.62,10.79Z'],
  contacts: ['M9,11a4,4 0,1 0,0 -8a4,4 0,0 0,0 8M9,13c-4.42,0 -8,2.24 -8,5v2h16v-2c0,-2.76 -3.58,-5 -8,-5M17.5,11a3,3 0,1 0,0 -6a3,3 0,0 0,0 6M17.5,13c-0.54,0 -1.06,0.04 -1.55,0.11c1.87,1.1 3.05,2.82 3.05,4.89v2h5v-2c0,-2.76 -2.91,-5 -6.5,-5'],
  history: ['M13,3a9,9 0,0 0,-8.95 8H1l4,4l4,-4H6.07A7,7 0,1 1,8 16.9l-1.42,1.48A9,9 0,1 0,13 3M12,7v6l5,3l1,-1.73l-4,-2.37V7z'],
  paste: ['M19,2h-4.18C14.4,0.84 13.3,0 12,0S9.6,0.84 9.18,2H5C3.9,2 3,2.9 3,4v16c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM12,2c0.55,0 1,0.45 1,1s-0.45,1 -1,1 -1,-0.45 -1,-1 0.45,-1 1,-1zM19,20H5V4h2v3h10V4h2v16z'],
  person: ['M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM12,14c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z'],
  more: ['M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z'],
  chevron: ['M9.29,6.71a0.9959,0.9959 0,0 0,0 1.41L13.17,12l-3.88,3.88a0.9959,0.9959 0,1 0,1.41 1.41l4.59,-4.59a0.9959,0.9959 0,0 0,0 -1.41L10.7,6.7a0.9959,0.9959 0,0 0,-1.41 0.01z'],
  refresh: ['M17.65,6.35C16.2,4.9 14.21,4 12,4C7.58,4 4.01,7.58 4.01,12S7.58,20 12,20C15.73,20 18.84,17.45 19.73,14H17.65C16.83,16.33 14.61,18 12,18C8.69,18 6,15.31 6,12S8.69,6 12,6C13.66,6 15.14,6.69 16.22,7.78L13,11H20V4L17.65,6.35Z'],
  mic: ['M12,14c1.66,0 3,-1.34 3,-3V5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.42 2.72,6.23 6,6.72V21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z'],
  micOff: ['M19,11h-1.7c0,0.74 -0.16,1.43 -0.43,2.05l1.23,1.23c0.56,-0.98 0.9,-2.09 0.9,-3.28zM15,11V5c0,-1.66 -1.34,-3 -3,-3 -1.54,0 -2.79,1.16 -2.96,2.65L15,10.61V11zM4.27,3L3,4.27l6.01,6.01V11c0,1.66 1.33,3 2.99,3 0.22,0 0.44,-0.03 0.65,-0.08l1.66,1.66c-0.71,0.33 -1.5,0.52 -2.31,0.52 -2.76,0 -5.3,-2.1 -5.3,-5.1H5c0,3.42 2.72,6.23 6,6.72V21h2v-3.28c0.91,-0.13 1.78,-0.45 2.54,-0.9L19.73,21 21,19.73 4.27,3z'],
  volume: ['M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z'],
  edit: ['M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34a0.9959,0.9959 0,0 0,-1.41 0l-1.83,1.83 3.75,3.75 1.83,-1.83z'],
  delete: ['M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM8,9h8v10H8V9zM15.5,4l-1,-1h-5l-1,1H5v2h14V4z'],
  logout: ['M17,7l-1.41,1.41L18.17,11H8v2h10.17l-2.58,2.58L17,17l5,-5zM4,5h8V3H4c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h8v-2H4V5z'],
  serverUnavailable: ['M18.3,5.71 12,12l-6.3,-6.29 -1.41,1.41L10.59,13.41 4.29,19.71l1.41,1.41L12,14.83l6.3,6.29 1.41,-1.41L13.41,13.41l6.3,-6.29z'],
  photoCamera: ['M9,2L7.17,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2h-3.17L15,2H9zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5zM12,15.2c1.77,0 3.2,-1.43 3.2,-3.2S13.77,8.8 12,8.8 8.8,10.23 8.8,12 10.23,15.2 12,15.2z'],
  videoCamera: ['M17,10.5V7c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2v-3.5l4,4v-11l-4,4z'],
  switchCamera: [
    { d: 'M4.5,9.25C5.75,6.35 8.63,4.5 11.8,4.5H18.5M15.75,1.75L18.5,4.5L15.75,7.25', fill: false, strokeWidth: 2.1 },
    { d: 'M19.5,14.75C18.25,17.65 15.37,19.5 12.2,19.5H5.5M8.25,22.25L5.5,19.5L8.25,16.75', fill: false, strokeWidth: 2.1 },
  ],
  historyIncoming: ['M20,5.41L18.59,4L7,15.59V9H5V19H15V17H8.41L20,5.41Z'],
  historyOutgoing: ['M9,5V7H15.59L4,18.59L5.41,20L17,8.41V15H19V5H9Z'],
  historyMissedIncoming: ['M19.59,7L12,14.59L6.41,9H11V7H3V15H5V10.41L12,17.41L21,8.41L19.59,7Z'],
  historyMissedOutgoing: ['M4.41,7L12,14.59L17.59,9H13V7H21V15H19V10.41L12,17.41L3,8.41L4.41,7Z'],
  historyMarkBusy: ['M4,10H20V14H4Z'],
  historyMarkRejected: ['M5.4,3L3,5.4L9.6,12L3,18.6L5.4,21L12,14.4L18.6,21L21,18.6L14.4,12L21,5.4L18.6,3L12,9.6Z'],
  historyMarkFailed: ['M10,3H14V14H10ZM10,17H14V21H10Z'],
  historyMarkInterrupted: ['M13,2L4,14H10L9,22L20,9H14L13,2Z'],
} satisfies Record<string, IconPath[]>;

function element<K extends keyof HTMLElementTagNameMap>(tag: K, className = '', text = ''): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag);
  if (className) el.className = className;
  if (text) el.textContent = text;
  return el;
}

function icon(name: keyof typeof iconPaths, className = ''): HTMLSpanElement {
  const wrapper = element('span', `icon ${className}`.trim());
  wrapper.innerHTML = `<svg viewBox="0 0 24 24" aria-hidden="true">${iconPaths[name].map(iconPathSvg).join('')}</svg>`;
  return wrapper;
}

function iconPathSvg(path: IconPath): string {
  if (typeof path === 'string') return `<path d="${path}"></path>`;
  if (path.fill === false) {
    return `<path d="${path.d}" fill="none" stroke="currentColor" stroke-width="${path.strokeWidth ?? 2}" stroke-linecap="round" stroke-linejoin="round"></path>`;
  }
  return `<path d="${path.d}"></path>`;
}

function appMark(size = '42px'): HTMLElement {
  const mark = element('span', 'app-mark');
  mark.style.width = size;
  mark.style.height = size;
  mark.append(icon('call'));
  return mark;
}

function actionButton(label: string, action: () => void | Promise<void>, className = '', iconName?: keyof typeof iconPaths): HTMLButtonElement {
  const btn = element('button', className);
  btn.type = 'button';
  if (iconName) btn.append(icon(iconName));
  btn.append(document.createTextNode(label));
  btn.onclick = () => {
    if (iconName === 'call') callTones.unlock();
    btn.disabled = true;
    void Promise.resolve().then(action).catch(failure).finally(() => { btn.disabled = false; });
  };
  return btn;
}

function iconButton(label: string, iconName: keyof typeof iconPaths, action: () => void | Promise<void>, className = ''): HTMLButtonElement {
  const btn = element('button', `icon-button ${className}`.trim());
  btn.type = 'button';
  btn.ariaLabel = label;
  btn.title = label;
  btn.append(icon(iconName));
  btn.onclick = () => { void Promise.resolve().then(action).catch(failure); };
  return btn;
}

function notice(message: string): void {
  const box = document.querySelector<HTMLParagraphElement>('#notice')!;
  clearTimeout(noticeTimer);
  noticeTimer = undefined;
  box.textContent = message;
  box.hidden = !message;
  box.onclick = message ? () => notice('') : null;
  if (message) noticeTimer = setTimeout(() => notice(''), 4_000);
}

function failure(error: unknown, retry?: () => void | Promise<void>): void {
  if (error instanceof APIError && error.replaced) return;
  const explanation = explainError(error, navigator);
  if (explanation) {
    notice('');
    closeActiveOverlay();
    const modal = dialog(explanation.title);
    modal.body.append(element('p', '', explanation.message));
    modal.actions.append(actionButton('Закрыть', () => modal.close(), 'secondary'));
    if (retry) modal.actions.append(actionButton(explanation.retryLabel ?? 'Повторить', async () => {
      await closeDialog(modal, 'remove');
      try { await retry(); } catch (nextError) { failure(nextError, retry); }
    }, 'primary'));
    return;
  }
  notice(error instanceof Error ? error.message : 'Не удалось выполнить действие.');
}

function contactDisplayName(contact: Pick<Contact, 'display_name' | 'login'>): string {
  return contact.display_name.trim() || contact.login;
}

function serverAddress(server: string): string {
  return normalizeServer(server).replace(/^https:\/\//i, '');
}

function serverHost(server: string): string {
  return new URL(normalizeServer(server)).host;
}

function accountKey(accountId: string, login: string): string {
  return `${accountId}:${login}`;
}

function notificationCallAction(value: unknown): NotificationCallAction | undefined {
  return value === 'answer' || value === 'reject' ? value : undefined;
}

function takePendingNotificationAction(accountId: string, callId: string): NotificationCallAction | undefined {
  const key = callKey(accountId, callId);
  const action = pendingNotificationActions.get(key);
  pendingNotificationActions.delete(key);
  return action;
}

function applyNotificationCallAction(call: ActiveCall, action: NotificationCallAction): void {
  if (current !== call || !call.incoming || call.accepted) return;
  if (action === 'reject') {
    hangup('rejected');
    return;
  }
  void accept().catch(failure);
}

function allContacts(): AccountContact[] {
  const collator = new Intl.Collator('ru', { sensitivity: 'base' });
  return list.filter(account => !account.sessionReplaced).flatMap(account => (contactsByAccount.get(account.id) ?? []).map(contact => ({ ...contact, account })))
    .sort((a, b) => collator.compare(contactDisplayName(a), contactDisplayName(b)) || collator.compare(a.login, b.login) || collator.compare(a.account.server, b.account.server));
}

function allHistory(): AccountHistory[] {
  return list.filter(account => !account.sessionReplaced).flatMap(account => (historyByAccount.get(account.id) ?? []).map(item => ({ ...item, account })))
    .sort((a, b) => b.started_at - a.started_at || list.indexOf(a.account) - list.indexOf(b.account) || b.id - a.id);
}

function findContact(accountId: string, login: string): AccountContact | undefined {
  const account = list.find(item => item.id === accountId);
  const contact = contactsByAccount.get(accountId)?.find(item => item.login === login);
  return account && !account.sessionReplaced && contact ? { ...contact, account } : undefined;
}

function contactPhotoKey(accountId: string, login: string): string {
  return accountKey(accountId, login);
}

function photoForContact(contact: AccountContact): string {
  return contactPhotosByKey.get(contactPhotoKey(contact.account.id, contact.login)) ?? '';
}

function photoForAccountPeer(accountId: string, login: string): string {
  return contactPhotosByKey.get(contactPhotoKey(accountId, login)) ?? '';
}

function avatar(name: string, login: string, className = '', photoUrl = ''): HTMLElement {
  const colors = ['#394A67', '#514464', '#30514D', '#60443B', '#4E5337', '#593F4C'];
  let hash = 0;
  for (const char of login) hash = (hash * 31 + char.charCodeAt(0)) | 0;
  const box = element('span', `avatar ${className} ${photoUrl ? 'has-photo' : ''}`.trim(), photoUrl ? '' : initial(name, login));
  box.style.setProperty('--avatar-color', colors[Math.abs(hash) % colors.length]);
  if (photoUrl) {
    const image = element('img');
    image.alt = '';
    image.src = photoUrl;
    box.append(image);
  }
  return box;
}

function initial(name: string, fallback: string): string {
  return [...(name.trim() || fallback.trim() || 'T')][0]?.toLocaleUpperCase('ru-RU') ?? 'T';
}

async function claim(account: Account): Promise<void> {
  const health = await api<{ features: string[] }>(account, '/healthz');
  if (!health.features?.includes('browser_v1')) throw new Error('Этот сервер нужно обновить для подключения PWA.');
  const session = await api<{ session_id: string }>(account, '/api/browser/session', 'POST', { device_id: account.deviceId });
  account.sessionId = session.session_id;
}

function markSessionReplaced(account: Account, sessionId: string): void {
  // Delayed responses from the previous login must not invalidate a new one.
  if (!sessionId || list.find(item => item.id === account.id) !== account || account.sessionId !== sessionId || account.sessionReplaced) return;
  account.sessionReplaced = true;
  clearUnread(account.id);
  connections.get(account.id)?.stop();
  connections.delete(account.id);
  states.delete(account.id);
  contactsByAccount.delete(account.id);
  if (current?.account.id === account.id) {
    closeActiveOverlay();
    void closeCallNotification(account.id, current.id, base).catch(() => undefined);
    closeActiveCall();
  }
  if (endedCall?.accountId === account.id) { endedCall = null; clearTimeout(endedCallTimer); }
  void saveAccount(account).catch(failure);
  void prunePushes(account.id).catch(() => undefined);
  if (list.length === 1) {
    tab = 'contacts';
    replaceRoute({ name: 'login', accountId: account.id });
  } else if (route.name === 'contact' && route.accountId === account.id) {
    tab = 'contacts';
    replaceRoute({ name: 'home' });
  } else renderApp();
}

function connectAccount(account: Account, recoverActive = true): void {
  connections.get(account.id)?.stop();
  if (account.sessionReplaced) return;
  const connection = new SignalConnection(account,
    event => receive(account, event),
    status => {
      if (account.sessionReplaced || !list.includes(account)) return;
      states.set(account.id, status);
      if (status === 'На связи') connectAndResume(account);
      if (status === 'На связи') void refreshAccountContacts(account).then(() => renderApp()).catch(() => undefined);
      if (status === 'На связи' && current?.account.id === account.id) current.media.resendVideoState();
      renderApp();
    },
    (error, callId) => {
      if (error instanceof APIError && error.replaced) return;
      if (error instanceof SignalError && error.code?.startsWith('call_sas_') && current?.account.id === account.id && (!callId || current.id === callId)) {
        current.media.rejectSecurityFromServer(error.code);
        renderCall();
        return;
      }
      failure(error);
      if (current?.account.id === account.id && (!callId || current.id === callId)) {
        finishCurrentCall(error instanceof SignalError && error.code === 'busy' ? 'Занято' : 'Звонок завершён', true, '', signalFailureToneReason(error));
      }
    },
    () => current?.account.id === account.id && current.started ? { id: current.id, seq: current.seq } : null,
    event => {
      if (account.sessionReplaced || !list.includes(account)) return;
      // The sender receives an ACK only after the server has stored the final result.
      if (['call.end', 'call.reject', 'call.cancel'].includes(event.type)) void refreshAll(false).catch(() => undefined);
    });
  connections.set(account.id, connection);
  if (recoverActive) connectAndResume(account);
  else void connection.connect().catch(() => undefined);
}

function connectAndResume(account: Account): void {
  if (account.sessionReplaced) return;
  const connection = connections.get(account.id);
  if (!connection) return;
  if (recoveringAccounts.has(account.id)) return;
  const job = connection.connect().then(() => resumeActiveCall(account)).catch(() => undefined)
    .finally(() => recoveringAccounts.delete(account.id));
  recoveringAccounts.set(account.id, job);
}

async function resumeActiveCall(account: Account): Promise<void> {
  if (current || account.sessionReplaced || !list.includes(account)) return;
  const active = await api<{ call_id: string }>(account, '/api/active-call').catch(() => null);
  if (!active?.call_id || current || account.sessionReplaced || !list.includes(account)) return;
  if (openingNotificationCalls.has(callKey(account.id, active.call_id))) return;
  connections.get(account.id)?.send(active.call_id, 'call.resume', { last_seq: 0 });
}

function renderApp(): void {
  renderCall();
  if (!list.length && route.name !== 'login') {
    route = { name: 'login' };
    writeAppHistory('replace');
  }
  const onlyAccount = list.length === 1 ? list[0] : undefined;
  if (onlyAccount?.sessionReplaced && (route.name !== 'login' || route.accountId !== onlyAccount.id)) {
    closeActiveOverlay();
    route = { name: 'login', accountId: onlyAccount.id };
    writeAppHistory('replace');
  }
  const loginAccountId = route.name === 'login' ? route.accountId : undefined;
  const loginAccount = list.find(account => account.id === loginAccountId);
  if (loginAccountId && !loginAccount?.sessionReplaced) {
    route = { name: 'home' };
    writeAppHistory('replace');
  }
  const viewKey = JSON.stringify(route) + ':' + tab;
  // Background refreshes must not replace the form the user is editing.
  // Navigation changes the key and naturally discards the old form and token.
  if (screen.dataset.viewKey === viewKey && ['login', 'add-account', 'add-contact'].includes(route.name)) return;
  if (screen.dataset.viewKey !== viewKey) {
    contactHistoryGeneration++;
    if (route.name === 'contact') {
      const key = accountKey(route.accountId, route.login);
      contactHistory.delete(key);
      contactHistoryCursors.delete(key);
      contactHistoryErrors.delete(key);
    }
  }
  const view = route.name === 'login' ? credentialsScreen('login', loginAccount)
    : route.name === 'add-account' ? credentialsScreen('add-account')
      : route.name === 'add-contact' ? addContactScreen()
        : route.name === 'profile' ? profileScreen()
          : route.name === 'about' ? aboutScreen()
            : route.name === 'contact' ? contactScreen(route.accountId, route.login)
              : homeScreen();
  const scrollTop = screen.dataset.viewKey === viewKey ? screen.querySelector<HTMLElement>('.home-content, .contact-screen')?.scrollTop ?? 0 : 0;
  historyObserver?.disconnect();
  screen.replaceChildren(view);
  screen.dataset.viewKey = viewKey;
  const scroller = screen.querySelector<HTMLElement>('.home-content, .contact-screen');
  if (scroller) scroller.scrollTop = scrollTop;
  const sentinel = screen.querySelector<HTMLElement>('[data-history-more]');
  if (sentinel && scroller) {
    historyObserver = new IntersectionObserver(entries => {
      if (!sentinel.isConnected || document.hidden || current || !entries.some(entry => entry.isIntersecting)) return;
      historyObserver?.disconnect();
      sentinel.click();
    }, { root: scroller });
    historyObserver.observe(sentinel);
  }
}

function navigate(next: Route): void {
  if (sameRoute(route, next)) return;
  closeActiveOverlay();
  if (next.name === 'about') { updateReport = undefined; updateError = ''; }
  route = next;
  writeAppHistory('push');
  renderApp();
}

function replaceRoute(next: Route): void {
  closeActiveOverlay();
  route = next;
  writeAppHistory('replace');
  renderApp();
}

function goBack(fallback: Route): void {
  if (historyIndex > 0) {
    window.history.back();
    return;
  }
  replaceRoute(fallback);
}

function switchHomeTab(next: Tab): void {
  if (tab === next) return;
  if (next === 'contacts' && route.name === 'home' && historyIndex > 0) {
    tab = 'contacts';
    renderApp();
    window.history.back();
    return;
  }
  tab = next;
  writeAppHistory(next === 'history' ? 'push' : 'replace');
  renderApp();
  if (next === 'history') void refreshHistory(true).catch(failure);
}

function closeActiveOverlay(clearHistory = true): void {
  const close = activeOverlayClose;
  if (!close) return;
  close();
  if (clearHistory && currentAppHistoryState()?.overlay) writeAppHistory('replace');
}

function dismissActiveOverlay(): void {
  if (!activeOverlayClose) return;
  if (currentAppHistoryState()?.overlay && historyIndex > 0) {
    window.history.back();
    return;
  }
  closeActiveOverlay(false);
}

function removeActiveOverlayHistoryEntry(): Promise<void> {
  if (!activeOverlayClose) return Promise.resolve();
  if (currentAppHistoryState()?.overlay && historyIndex > 0) {
    return new Promise(resolve => {
      const previous = pendingOverlayHistoryRemoval;
      pendingOverlayHistoryRemoval = () => {
        previous?.();
        resolve();
      };
      window.history.back();
    });
  }
  closeActiveOverlay(false);
  return Promise.resolve();
}

function registerActiveOverlay(close: () => void): () => void {
  closeActiveOverlay(false);
  writeAppHistory(currentAppHistoryState()?.overlay ? 'replace' : 'push', true);
  let active = true;
  const wrapped = () => {
    if (!active) return;
    active = false;
    if (activeOverlayClose === wrapped) activeOverlayClose = undefined;
    close();
  };
  activeOverlayClose = wrapped;
  return dismissActiveOverlay;
}

function currentAppHistoryState(): AppHistoryState | undefined {
  return isAppHistoryState(window.history.state) ? window.history.state : undefined;
}

function writeAppHistory(mode: 'push' | 'replace', overlay = false): void {
  if (mode === 'push') historyIndex += 1;
  const state: AppHistoryState = overlay ? { tinitalk: 'web', route, tab, index: historyIndex, overlay: true } : { tinitalk: 'web', route, tab, index: historyIndex };
  window.history[mode === 'push' ? 'pushState' : 'replaceState'](state, '', appHistoryUrl());
}

function appHistoryUrl(): string {
  return `${location.pathname}${location.search}`;
}

function applyHistoryState(value: unknown): boolean {
  if (!isAppHistoryState(value)) return false;
  if (value.route.name === 'about' && route.name !== 'about') { updateReport = undefined; updateError = ''; }
  route = value.route;
  tab = value.tab;
  historyIndex = value.index;
  renderApp();
  return true;
}

function applyPopstate(value: unknown): void {
  const hadOverlay = Boolean(activeOverlayClose);
  if (activeOverlayClose) activeOverlayClose();
  if (!applyHistoryState(value)) {
    route = list.length ? { name: 'home' } : { name: 'login' };
    tab = 'contacts';
    historyIndex = 0;
    renderApp();
  }
  if (hadOverlay) {
    const resolve = pendingOverlayHistoryRemoval;
    pendingOverlayHistoryRemoval = undefined;
    resolve?.();
  }
}

function isAppHistoryState(value: unknown): value is AppHistoryState {
  if (!value || typeof value !== 'object') return false;
  const state = value as Partial<AppHistoryState>;
  return state.tinitalk === 'web'
    && typeof state.index === 'number'
    && isTab(state.tab)
    && isRoute(state.route);
}

function isTab(value: unknown): value is Tab {
  return value === 'contacts' || value === 'history';
}

function isRoute(value: unknown): value is Route {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Partial<Route>;
  if (candidate.name === 'login') return candidate.accountId === undefined || typeof candidate.accountId === 'string';
  if (candidate.name === 'contact') {
    return typeof (candidate as { accountId?: unknown }).accountId === 'string'
      && typeof (candidate as { login?: unknown }).login === 'string';
  }
  return candidate.name === 'home'
    || candidate.name === 'add-account'
    || candidate.name === 'add-contact'
    || candidate.name === 'profile'
    || candidate.name === 'about';
}

function sameRoute(left: Route, right: Route): boolean {
  if (left.name !== right.name) return false;
  if (left.name === 'login' && right.name === 'login') return left.accountId === right.accountId;
  if (left.name === 'contact' && right.name === 'contact') {
    return left.accountId === right.accountId && left.login === right.login;
  }
  return true;
}

function appPage(content: HTMLElement, options: { title?: string; back?: () => void; menu?: HTMLElement; className?: string } = {}): HTMLElement {
  const page = element('section', `app-page ${options.back ? 'has-back' : ''} ${options.className ?? ''}`.trim());
  const top = element('header', 'top-bar');
  if (options.back) {
    top.append(iconButton('Назад', 'arrowBack', options.back, 'top-back'));
    top.append(element('h1', '', options.title ?? ''));
  } else {
    const brand = element('button', 'brand-button');
    brand.type = 'button';
    brand.append(appMark('42px'), element('span', '', 'TiniTalk'));
    brand.onclick = () => navigate({ name: 'about' });
    top.append(brand, element('span', 'top-spacer'));
  }
  if (!options.back) {
    const profile = iconButton('Профиль', list.length > 1 ? 'contacts' : 'person', () => navigate({ name: 'profile' }), 'profile-button');
    if (list.some(account => account.sessionReplaced)) {
      const dot = element('span', 'profile-attention-dot');
      dot.ariaHidden = 'true';
      profile.append(dot);
      profile.title = 'Профиль — требуется повторный вход';
      profile.ariaLabel = profile.title;
    }
    top.append(profile);
  } else if (options.menu) {
    top.append(options.menu);
  }
  page.append(top, content);
  return page;
}

function homeScreen(): HTMLElement {
  const content = element('main', `home-content pull-refresh-host ${pullRefreshing === tab ? 'refreshing' : ''}`.trim());
  const refreshText = tab === 'contacts' ? 'Обновляем контакты…' : 'Обновляем историю…';
  const indicator = pullRefreshIndicator(pullRefreshing === tab ? refreshText : 'Потяните вниз для обновления');
  content.append(indicator, tab === 'contacts' ? contactsPage() : historyPage());
  wirePullRefresh(content, indicator, tab);
  const nav = element('nav', 'bottom-nav');
  nav.append(navItem('Контакты', 'contacts', tab === 'contacts', () => switchHomeTab('contacts')));
  nav.append(navItem('История', 'history', tab === 'history', () => switchHomeTab('history'), unreadCount()));
  const wrap = element('div', 'home-wrap');
  wrap.append(content, nav);
  return appPage(wrap);
}

function pullRefreshIndicator(text: string): HTMLElement {
  const box = element('div', 'pull-refresh-indicator');
  box.append(element('span', 'pull-refresh-spinner'), element('span', 'pull-refresh-text', text));
  return box;
}

function wirePullRefresh(scroller: HTMLElement, indicator: HTMLElement, refreshTab: Tab): void {
  const threshold = 72;
  const maxPull = 112;
  let tracking = false;
  let pulling = false;
  let startY = 0;
  let pull = 0;
  const text = indicator.querySelector<HTMLElement>('.pull-refresh-text');

  function setPull(value: number): void {
    pull = Math.max(0, Math.min(maxPull, value));
    scroller.style.setProperty('--pull-y', `${pull}px`);
    scroller.style.setProperty('--pull-alpha', String(Math.min(1, pull / 34)));
    scroller.style.setProperty('--pull-rotate', `${Math.min(1, pull / threshold) * 180}deg`);
    if (text && pullRefreshing !== refreshTab) {
      text.textContent = pull >= threshold ? 'Отпустите для обновления' : 'Потяните вниз для обновления';
    }
  }

  function resetPull(): void {
    scroller.classList.remove('pulling');
    scroller.style.removeProperty('--pull-y');
    scroller.style.removeProperty('--pull-alpha');
    scroller.style.removeProperty('--pull-rotate');
    pull = 0;
    if (text && pullRefreshing !== refreshTab) text.textContent = 'Потяните вниз для обновления';
  }

  scroller.addEventListener('touchstart', event => {
    if (event.touches.length !== 1 || pullRefreshing || current || scroller.scrollTop > 0) return;
    tracking = true;
    pulling = false;
    startY = event.touches[0].clientY;
  }, { passive: true });

  scroller.addEventListener('touchmove', event => {
    if (!tracking || event.touches.length !== 1 || pullRefreshing) return;
    const delta = event.touches[0].clientY - startY;
    if (delta <= 0) {
      if (pulling) resetPull();
      pulling = false;
      return;
    }
    if (scroller.scrollTop > 0) return;
    if (delta < 6 && !pulling) return;
    pulling = true;
    scroller.classList.add('pulling');
    event.preventDefault();
    setPull(delta * 0.62);
  }, { passive: false });

  const finish = () => {
    if (!tracking) return;
    const shouldRefresh = pulling && pull >= threshold && !pullRefreshing;
    tracking = false;
    pulling = false;
    if (shouldRefresh) void triggerPullRefresh(refreshTab).catch(failure);
    else resetPull();
  };
  scroller.addEventListener('touchend', finish, { passive: true });
  scroller.addEventListener('touchcancel', finish, { passive: true });
}

async function triggerPullRefresh(refreshTab: Tab): Promise<void> {
  if (pullRefreshing) return;
  pullRefreshing = refreshTab;
  renderApp();
  try {
    if (refreshTab === 'contacts') await refreshContacts({ renderStart: false, renderEnd: false });
    else await refreshHistory(true, { renderStart: false, renderEnd: false });
  } finally {
    pullRefreshing = null;
    renderApp();
  }
}

function navItem(label: string, iconName: keyof typeof iconPaths, selected: boolean, action: () => void, badge = 0): HTMLButtonElement {
  const btn = element('button', `nav-item ${selected ? 'selected' : ''}`.trim());
  btn.type = 'button';
  btn.append(icon(iconName));
  const row = element('span', 'nav-label', label);
  if (badge > 0) row.append(element('span', 'badge', badge > 99 ? '99+' : String(badge)));
  btn.append(row);
  btn.onclick = action;
  return btn;
}

function contactsPage(): HTMLElement {
  const page = element('section', 'page-list');
  if (loadingContacts && allContacts().length === 0) {
    page.append(loadingBlock());
    return page;
  }
  const contacts = allContacts();
  if (!contacts.length) {
    const empty = element('div', 'empty-state');
    empty.append(element('h2', '', 'Контактов пока нет'), element('p', '', 'Добавьте первый контакт.'));
    empty.append(actionButton('＋ Добавить', () => navigate({ name: 'add-contact' }), 'text-action'));
    page.append(empty);
    return page;
  }
  const duplicates = contactsRequiringServerSubtitle(contacts);
  const listEl = element('div', 'material-list');
  for (const contact of contacts) listEl.append(contactRow(contact, duplicates.has(accountKey(contact.account.id, contact.login))));
  const add = actionButton('＋ Добавить', () => navigate({ name: 'add-contact' }), 'text-action list-add');
  page.append(listEl, add);
  return page;
}

function contactRow(contact: AccountContact, showServer: boolean): HTMLElement {
  const name = contactDisplayName(contact);
  const row = element('button', 'contact-row');
  row.type = 'button';
  row.onclick = () => openContact(contact);
  row.append(avatar(name, contact.login, 'contact-avatar', photoForContact(contact)));
  const text = element('span', 'contact-text');
  const details = [showServer ? serverHost(contact.account.server) : '', contact.can_call ? '' : 'Звонки пока недоступны'].filter(Boolean).join(' • ');
  text.append(element('strong', '', name));
  if (details) text.append(element('small', '', details));
  const missedAt = unreadMissedByContact.get(accountKey(contact.account.id, contact.login));
  if (missedAt) text.append(element('small', 'missed', missedContactSubtitle(missedAt)));
  const chevron = element('span', 'chevron-bubble');
  chevron.append(icon('chevron'));
  row.append(text, chevron);
  return row;
}

function historyPage(): HTMLElement {
  const page = element('section', 'page-list');
  const rows = allHistory().slice(0, historyVisibleLimit);
  if (loadingHistory && rows.length === 0) {
    page.append(loadingBlock());
    return page;
  }
  if (!rows.length && !historyErrors.size) {
    const empty = element('div', 'empty-state');
    const clock = element('span', 'empty-icon', '◷');
    empty.append(clock, element('h2', '', 'История звонков пока пуста'), element('p', '', 'Здесь появятся входящие и исходящие звонки.'));
    page.append(empty);
    return page;
  }
  page.append(historyRows(rows, true));
  if (loadingMoreHistory) page.append(loadingBlock());
  else if (!loadingHistory) {
    const more = allHistory().length > historyVisibleLimit || list.some(a => !a.sessionReplaced && !historyErrors.has(a.id) && (historyCursors.get(a.id) ?? 0) > 0);
    if (more) page.append(historyMoreButton(() => loadMoreHistory()));
    if (historyErrors.size) page.append(actionButton('Не удалось загрузить историю. Повторить', () => loadMoreHistory(true), 'text-action'));
  }
  return page;
}

function historyMoreButton(action: () => Promise<void>): HTMLElement {
  const button = actionButton('Загрузить ещё', action, 'text-action');
  button.dataset.historyMore = 'true';
  return button;
}

function historyRows(rows: (HistoryItem | AccountHistory)[], showPeer: boolean): HTMLElement {
  const listEl = element('div', 'history-list');
  rows.forEach((item, index) => {
    const day = historyDayLabel(item.started_at);
    const previous = rows[index - 1];
    if (!previous || historyDayLabel(previous.started_at) !== day) listEl.append(element('h3', 'day-label', day));
    listEl.append(historyRow(item, showPeer));
  });
  return listEl;
}

function historyRow(item: HistoryItem | AccountHistory, showPeer: boolean): HTMLElement {
  const account = 'account' in item ? item.account : undefined;
  const name = item.peer_name.trim() || item.peer_login;
  const row = element('button', `history-row ${historyColorClass(item)}`);
  row.type = 'button';
  if (account) row.onclick = () => {
    const contact = findContact(account.id, item.peer_login);
    if (contact) openContact(contact);
    else notice('Контакт больше недоступен');
  };
  if (showPeer) row.append(avatar(name, item.peer_login, 'history-avatar', account ? photoForAccountPeer(account.id, item.peer_login) : ''));
  const text = element('span', 'history-text');
  text.append(element('strong', '', showPeer ? name : (item.direction === 'incoming' ? 'Входящий' : 'Исходящий')));
  const status = element('small', 'history-status');
  status.append(historyCallIcon(item), document.createTextNode(historyStatus(item)));
  text.append(status);
  row.append(text, element('time', '', historyTime(item.started_at)));
  return row;
}

function historyCallIcon(item: HistoryItem): HTMLElement {
  const names = historyIconNames(item);
  const wrap = element('span', 'history-call-icon');
  wrap.append(icon(names.direction, 'history-direction-icon'));
  if (names.mark) {
    const badge = element('span', 'history-mark-badge');
    badge.append(icon(names.mark));
    wrap.append(badge);
  }
  return wrap;
}

function historyIconNames(item: HistoryItem): { direction: keyof typeof iconPaths; mark?: keyof typeof iconPaths } {
  const missed = noAnswerOutcomes.has(item.outcome);
  const direction = missed
    ? item.direction === 'incoming' ? 'historyMissedIncoming' : 'historyMissedOutgoing'
    : item.direction === 'incoming' ? 'historyIncoming' : 'historyOutgoing';
  const mark = item.outcome === 'busy' ? 'historyMarkBusy'
    : item.outcome === 'rejected' ? 'historyMarkRejected'
      : item.outcome === 'connection_failed' ? 'historyMarkFailed'
        : item.outcome === 'interrupted' ? 'historyMarkInterrupted'
          : undefined;
  return { direction, mark };
}

function aboutScreen(): HTMLElement {
  const body = element('main', 'about-page');
  body.append(aboutBrandBlock());
  const entries = aboutServerEntries();
  for (const { account } of entries) ensureAboutServerDetails(account);
  if (!updateReport && !checkingUpdates && !updateError) void checkAppUpdates(false);
  body.append(aboutApplicationCard(entries));
  for (const entry of entries) {
    body.append(aboutInfoCard('Сервер', [
      ['Адрес', serverAddress(entry.server)],
    ], [
      ['Версия API', entry.state.details?.api_version ? String(entry.state.details.api_version) : 'Не указана'],
      ['Коммит', entry.state.details?.commit?.trim() || 'Не указан'],
    ]));
    body.append(aboutServerStatusCard(entry.state));
  }
  return appPage(body, { title: 'О программе', back: () => goBack({ name: 'home' }), className: 'about-app-page' });
}

function aboutApplicationCard(entries: { account: Account; server: string; state: AboutServerState }[]): HTMLElement {
  const card = aboutInfoCard('Приложение', [], [
    ['Версия', `web-версия · ${aboutServerCommit(entries)}`],
    ['Собрано', buildTime(webBuild)],
  ]);
  card.append(aboutUpdateStatusRow());
  if (canUpdateApplication(updateReport)) {
    const update = actionButton('Обновить приложение', updateWebApplication, 'primary wide about-update-button');
    update.disabled = checkingUpdates || updatingApp || Boolean(current);
    card.append(update);
  }
  return card;
}

function aboutUpdateStatusRow(): HTMLElement {
  const status = updateReport ? updateStatus(updateReport) : undefined;
  const kind = !checkingUpdates && !updateError ? status?.kind || '' : '';
  const label = updatingApp ? 'Устанавливаем обновление…'
    : checkingUpdates ? 'Проверяем версию…'
      : updateError || status?.kind === 'unknown' ? 'Не удалось проверить'
        : status?.kind === 'update' ? 'Доступна новая версия'
          : 'Актуальная версия';
  const row = element('div', `about-update-row ${kind}`.trim());
  row.setAttribute('role', 'status');
  if (updateError || status?.text) row.title = updateError || status?.text || '';
  row.append(element('span', 'about-update-status', label));
  const check = iconButton('Проверить обновления', 'refresh', checkAppUpdates, 'about-check-button');
  check.disabled = checkingUpdates || updatingApp;
  row.append(check);
  return row;
}

async function checkAppUpdates(showProgress = true): Promise<void> {
  if (checkingUpdates) return;
  checkingUpdates = true; updateError = '';
  if (showProgress && route.name === 'about') renderApp();
  try { updateReport = await inspectUpdates(base, [...list]); }
  catch { updateError = 'Не удалось проверить обработчики. Проверьте подключение и повторите попытку.'; }
  finally { checkingUpdates = false; if (route.name === 'about') renderApp(); }
}

async function updateWebApplication(): Promise<void> {
  if (updatingApp || current) return;
  updatingApp = true; updateError = ''; renderApp();
  try {
    const latest = await fetchBuildVersions(base);
    if (!('serviceWorker' in navigator)) throw new Error('Service Worker недоступен');
    shellRegistration = await navigator.serviceWorker.register(new URL('shell-worker.js', base), { scope: base, updateViaCache: 'none' });
    await shellRegistration.update();
    if (shellRegistration.installing) await waitForWorker(shellRegistration.installing, ['installed', 'activated']);
    if (current) throw new Error('Обновление готово. Завершите звонок и нажмите «Обновить приложение» ещё раз.');
    // A newer main bundle knows which push code to register. Do not downgrade
    // its registrations using a stale page's embedded worker fingerprint.
    if (latest.build === webBuild) {
      // Updating code does not require notification permission or a family server.
      // Subscription reconciliation runs independently during startup.
      await Promise.all(list.filter(account => !account.sessionReplaced && account.pushConfigId)
        .map(account => updatePushWorker(account, base)));
    }
    if (current) throw new Error('Обновление готово. Повторите после завершения звонка.');
    const waiting = shellRegistration.waiting;
    if (waiting) {
      waiting.postMessage({ type: 'activate-update' });
      await waitForWorker(waiting, ['activated']);
    }
    if (!current) location.reload();
  } catch (error) {
    updateError = error instanceof Error ? error.message : 'Не удалось обновить приложение';
  } finally {
    updatingApp = false;
    if (route.name === 'about') renderApp();
  }
}

function aboutBrandBlock(): HTMLElement {
  const block = element('section', 'about-brand');
  block.append(appMark('84px'), element('h2', '', 'TiniTalk'));
  return block;
}

function aboutServerEntries(): { server: string; account: Account; state: AboutServerState }[] {
  const byServer = new Map<string, Account>();
  for (const account of list) {
    const server = normalizeServer(account.server);
    if (!byServer.has(server)) byServer.set(server, account);
  }
  return Array.from(byServer, ([server, account]) => ({
    server,
    account,
    state: aboutServers.get(server) ?? { loading: false },
  }));
}

function aboutServerCommit(entries: { state: AboutServerState }[]): string {
  const commits = Array.from(new Set(entries.map(entry => entry.state.details?.commit?.trim()).filter(Boolean) as string[]));
  if (commits.length === 1) return commits[0];
  if (commits.length > 1) return 'Несколько серверов';
  return entries.some(entry => entry.state.loading) ? 'Проверяем…' : 'Не указан';
}

function aboutInfoCard(title: string, values: [string, string][] = [], inlineValues: [string, string][] = []): HTMLElement {
  const card = element('section', 'about-card');
  card.append(element('h3', '', title));
  for (const [label, value] of values) {
    const row = element('div', 'about-value');
    row.append(element('small', '', label), element('span', '', value));
    card.append(row);
  }
  if (inlineValues.length) {
    const flow = element('div', 'about-inline-values');
    for (const [label, value] of inlineValues) {
      const row = element('span', 'about-inline-value');
      row.append(element('small', '', label), element('span', '', value));
      flow.append(row);
    }
    card.append(flow);
  }
  return card;
}

function aboutServerStatusCard(state: AboutServerState): HTMLElement {
  const status = aboutServerStatus(state);
  const card = element('section', `about-status-card ${status.kind}`);
  const mark = element('span', 'about-status-mark');
  if (status.kind === 'checking') mark.append(element('span', 'tiny-spinner'));
  else if (status.kind === 'available') mark.textContent = '✓';
  else if (status.kind === 'incompatible') mark.textContent = '!';
  else mark.append(icon('serverUnavailable'));
  const text = element('span', 'about-status-text');
  text.append(element('strong', '', 'Состояние сервера'), element('span', '', status.text));
  card.append(mark, text);
  return card;
}

function aboutServerStatus(state: AboutServerState): { kind: 'checking' | 'available' | 'incompatible' | 'unavailable'; text: string } {
  if (state.loading) return { kind: 'checking', text: 'Проверяем подключение…' };
  if (state.error) return { kind: 'unavailable', text: state.error };
  const details = state.details;
  if (!details) return { kind: 'unavailable', text: 'Сервер недоступен. Проверьте адрес и подключение к сети' };
  if (details.service !== 'tinitalk' || details.status !== 'ok') return { kind: 'unavailable', text: 'По этому адресу нет сервера TiniTalk' };
  if (!details.features?.includes('browser_v1')) return { kind: 'incompatible', text: 'Сервер несовместим с этой версией приложения' };
  return { kind: 'available', text: 'Сервер TiniTalk доступен' };
}

function ensureAboutServerDetails(account: Account): void {
  const server = normalizeServer(account.server);
  const cached = aboutServers.get(server);
  if (cached?.loading || cached?.details || cached?.error) return;
  aboutServers.set(server, { loading: true });
  void fetch(new URL('/healthz', server), { cache: 'no-store', signal: AbortSignal.timeout(8000) })
    .then(async response => {
      if (!response.ok) throw new Error('По этому адресу нет сервера TiniTalk');
      return await response.json() as ServerHealth;
    })
    .then(details => { aboutServers.set(server, { loading: false, details }); })
    .catch(error => {
      aboutServers.set(server, { loading: false, error: error instanceof Error ? error.message : 'Сервер недоступен. Проверьте адрес и подключение к сети' });
    })
    .finally(() => { if (route.name === 'about') renderApp(); });
}

function profileScreen(): HTMLElement {
  const body = element('main', 'form-page profile-page');
  const accountsBlock = element('div', 'account-list');
  for (const account of list) accountsBlock.append(accountCard(account));
  body.append(accountsBlock);
  body.append(actionButton('＋ Добавить', () => navigate({ name: 'add-account' }), 'text-action list-add'));
  return appPage(body, { title: 'Профиль', back: () => goBack({ name: 'home' }) });
}

function accountCard(account: Account): HTMLElement {
  const card = element('article', 'account-card');
  const top = element('div', 'profile-account-top');
  top.append(element('strong', '', account.login));
  const remove = iconButton(account.sessionReplaced ? 'Удалить' : 'Выйти', account.sessionReplaced ? 'delete' : 'logout',
    () => confirmRemoveAccount(account), 'logout-button');
  top.append(remove);
  const server = element('p', 'profile-server', serverAddress(account.server));
  const status = profileAccountStatus(account);
  const statusRow = element('div', `profile-server-status ${status.kind}`);
  if (status.kind === 'checking') statusRow.append(element('span', 'tiny-spinner'));
  else if (status.kind !== 'available') statusRow.append(icon('serverUnavailable', 'status-icon'));
  statusRow.append(element('span', '', status.text));
  card.append(top, server, statusRow, account.sessionReplaced
    ? actionButton('Войти снова', () => navigate({ name: 'login', accountId: account.id }), 'primary profile-notification-button')
    : notificationButton(account));
  return card;
}

function notificationButton(account: Account): HTMLButtonElement {
  const unsupported = pushSupport();
  const enabled = notifications.get(account.id) === true;
  const button = actionButton(enabled ? 'Отключить уведомления' : 'Включить уведомления', async () => {
    if (unsupported) {
      notice(unsupported);
      return;
    }
    if (enabled) {
      await disablePush(account, base);
      notifications.set(account.id, false);
      notice('Уведомления отключены.');
    } else {
      await enablePush(account, base);
      notifications.set(account.id, await pushEnabled(account, base).catch(() => false));
      notice(notifications.get(account.id) ? 'Уведомления включены.' : 'Не удалось включить уведомления.');
    }
    renderApp();
  }, `profile-notification-button ${enabled ? 'secondary' : 'primary'}`);
  if (unsupported) {
    button.classList.remove('primary');
    button.classList.add('secondary');
    button.textContent = 'Уведомления недоступны';
    button.title = unsupported;
  }
  return button;
}

function profileAccountStatus(account: Account): { kind: 'checking' | 'available' | 'unavailable'; text: string } {
  if (account.sessionReplaced) return { kind: 'unavailable', text: 'Вход выполнен на другом устройстве' };
  const status = states.get(account.id);
  if (!status || status === 'Подключение…') return { kind: 'checking', text: 'Проверяем…' };
  if (status === 'На связи') return { kind: 'available', text: 'Сервер доступен' };
  return { kind: 'unavailable', text: status === 'Нет связи' ? 'Сервер недоступен' : status };
}

function confirmRemoveAccount(account: Account): void {
  const replaced = account.sessionReplaced === true;
  const modal = dialog(replaced ? 'Удалить аккаунт из списка?' : 'Выйти из аккаунта?');
  modal.body.append(element('p', '', replaced
    ? `Убрать «${account.login}» из списка аккаунтов на этом устройстве?`
    : 'Чтобы снова принимать звонки, потребуется войти ещё раз.'));
  modal.actions.append(actionButton('Отмена', () => closeDialog(modal), 'secondary'));
  modal.actions.append(actionButton(replaced ? 'Удалить' : 'Выйти', async () => {
    await closeDialog(modal, 'remove');
    await removeAccount(account);
  }, 'danger'));
}

async function removeAccount(account: Account): Promise<void> {
  if (current?.account.id === account.id) throw new Error('Сначала завершите звонок.');
  if (removingAccounts.has(account)) return;
  removingAccounts.add(account);
  try {
    await disablePush(account, base).catch(() => undefined);
    connections.get(account.id)?.stop();
    connections.delete(account.id);
    await deleteAccount(account.id);
    await prunePushes(account.id);
    await deletePhotosForAccount(account.id);
    const index = list.indexOf(account);
    if (index >= 0) list.splice(index, 1);
    clearUnread(account.id);
    contactsByAccount.delete(account.id);
    historyByAccount.delete(account.id);
    states.delete(account.id);
    notifications.delete(account.id);
    replaceRoute(list.length ? { name: 'profile' } : { name: 'login' });
  } finally {
    removingAccounts.delete(account);
  }
}

async function deletePhotosForAccount(accountId: string): Promise<void> {
  const prefix = `${accountId}:`;
  for (const key of [...contactPhotosByKey.keys()]) {
    if (!key.startsWith(prefix)) continue;
    await deleteContactPhoto(key);
    contactPhotosByKey.delete(key);
  }
}

function contactScreen(accountId: string, login: string): HTMLElement {
  const contact = findContact(accountId, login);
  if (!contact) {
    const body = element('main', 'empty-state');
    body.append(element('h2', '', 'Контакт недоступен'), element('p', '', 'Обновите список контактов.'));
    return appPage(body, { title: 'Контакт', back: () => goBack({ name: 'home' }) });
  }
  const name = contactDisplayName(contact);
  const menu = contactMenu(contact);
  const body = element('main', 'contact-screen');
  body.append(avatar(name, contact.login, 'profile-avatar', photoForContact(contact)));
  body.append(element('h2', 'profile-name', name));
  body.append(element('p', 'profile-login', `${contact.login}${list.length > 1 ? `@${serverAddress(contact.account.server)}` : ''}`));
  const call = actionButton(contactActionLabel(contact), () => contactCall(contact), `primary call-wide ${!contact.can_call ? 'call-unavailable' : ''}`.trim(), 'call');
  call.disabled = Boolean(current && !samePeer(contact, current));
  body.append(call);
  body.append(element('h3', 'section-title', 'История звонков'));
  const rows = contactHistory.get(accountKey(accountId, login));
  const key = accountKey(accountId, login);
  if (!rows) {
    if (!contactHistoryErrors.has(key)) body.append(loadingBlock());
    if (!loadingContactHistory.has(key) && !contactHistoryErrors.has(key)) void loadContactHistory(contact, true).catch(failure);
  } else if (!rows.length) {
    body.append(contactHistoryMessage('Звонков с этим контактом пока не было'));
  } else {
    body.append(historyRows(rows, false));
  }
  if (contactHistoryErrors.has(key)) {
    body.append(actionButton('Не удалось загрузить историю. Повторить', () => loadContactHistory(contact, true, Boolean(rows && contactHistoryCursors.get(key))), 'text-action'));
  } else if (rows && loadingContactHistory.has(key)) body.append(loadingBlock());
  else if (rows && (contactHistoryCursors.get(key) ?? 0) > 0) body.append(historyMoreButton(() => loadContactHistory(contact, false, true)));
  return appPage(body, { title: 'Контакт', back: () => goBack({ name: 'home' }), menu });
}

function contactMenu(contact: AccountContact): HTMLElement {
  const holder = element('div', 'menu-holder');
  const closeMenu = () => holder.classList.remove('open');
  const opener = iconButton(`Действия контакта ${contactDisplayName(contact)}`, 'more', () => {
    if (holder.classList.contains('open')) {
      dismissActiveOverlay();
      return;
    }
    holder.classList.add('open');
    registerActiveOverlay(closeMenu);
  });
  const menu = element('div', 'popup-menu');
  menu.append(menuItem('Переименовать', 'edit', () => { closeActiveOverlay(false); renameDialog(contact); }));
  menu.append(menuItem('Изменить фото', 'photoCamera', () => { closeActiveOverlay(false); photoSheet(contact); }));
  menu.append(menuItem('Удалить контакт', 'delete', () => { closeActiveOverlay(false); deleteDialog(contact); }, 'danger-text'));
  holder.append(opener, menu);
  return holder;
}

function menuItem(label: string, iconName: keyof typeof iconPaths, action: () => void, className = ''): HTMLButtonElement {
  const item = element('button', `menu-item ${className}`.trim());
  item.type = 'button';
  item.append(icon(iconName), element('span', '', label));
  item.onclick = action;
  return item;
}

function contactHistoryMessage(message: string): HTMLElement {
  const box = element('div', 'history-message');
  box.append(element('p', '', message));
  return box;
}

function photoSheet(contact: AccountContact): void {
  const key = contactPhotoKey(contact.account.id, contact.login);
  const overlay = element('div', 'sheet-overlay');
  const panel = element('section', 'bottom-sheet');
  const fileInput = element('input');
  fileInput.type = 'file';
  fileInput.accept = 'image/*';
  fileInput.hidden = true;
  fileInput.onchange = () => {
    const file = fileInput.files?.[0];
    if (!file) return;
    void openPhotoEditor(contact, file, overlay).catch(failure);
  };
  panel.append(element('h2', '', 'Фото контакта'));
  panel.append(actionButton('Выбрать', () => fileInput.click(), 'primary wide'));
  if (contactPhotosByKey.has(key)) {
    panel.append(actionButton('Удалить фото', async () => {
      await deleteContactPhoto(key);
      contactPhotosByKey.delete(key);
      await closeSheet(overlay, 'remove');
      renderApp();
    }, 'text-action wide'));
  }
  panel.append(fileInput);
  overlay.append(panel);
  overlay.onclick = event => { if (event.target === overlay) closeSheet(overlay); };
  const close = registerActiveOverlay(() => overlay.remove());
  root.append(overlay);
  overlay.dataset.closeId = 'photo-sheet';
  (overlay as HTMLElement & { closeOverlay?: () => void }).closeOverlay = close;
}

async function openPhotoEditor(contact: AccountContact, file: File, sheet: HTMLElement): Promise<void> {
  if (!file.type.startsWith('image/')) throw new Error('Выберите изображение.');
  const source = await readFileDataUrl(file);
  const image = await loadImage(source);
  if (!image.naturalWidth || !image.naturalHeight) throw new Error('Не удалось открыть изображение');
  if (!sheet.isConnected) return;
  closeActiveOverlay(false);
  showPhotoEditor(contact, image);
}

function showPhotoEditor(contact: AccountContact, image: HTMLImageElement): void {
  const overlay = element('section', 'photo-editor-overlay');
  const title = element('h2', '', 'Настройте фото');
  const stage = element('div', 'photo-editor-stage');
  const viewport = element('div', 'photo-crop-viewport');
  const preview = element('img');
  preview.alt = '';
  preview.draggable = false;
  preview.src = image.src;
  const ring = element('span', 'photo-crop-ring');
  const actions = element('div', 'photo-editor-actions');
  const cancel = actionButton('Отмена', () => closePhotoEditor(), 'photo-editor-cancel');
  const done = actionButton('Готово', async () => {
    done.disabled = true;
    done.classList.add('saving');
    done.replaceChildren(element('span', 'tiny-spinner'));
    try {
      const dataUrl = renderContactPhotoDataUrl(image, transform, currentViewport());
      await saveContactPhotoDataUrl(contact, dataUrl);
      await closePhotoEditor('remove');
    } catch (error) {
      if (done.isConnected) {
        done.classList.remove('saving');
        done.replaceChildren(document.createTextNode('Готово'));
      }
      throw error;
    }
  }, 'primary');

  let transform: CropTransform = { scale: 1, offsetX: 0, offsetY: 0 };
  let initialized = false;
  const pointers = new Map<number, { x: number; y: number }>();
  let lastCentroid: { x: number; y: number } | undefined;
  let lastDistance = 0;

  function currentViewport(): CropViewport {
    return {
      width: Math.max(1, viewport.clientWidth || 280),
      height: Math.max(1, viewport.clientHeight || 280),
    };
  }

  function initialize(): void {
    const cropViewport = currentViewport();
    transform = defaultCropTransform(image.naturalWidth, image.naturalHeight, cropViewport);
    initialized = true;
    render();
  }

  function render(): void {
    if (!initialized) return;
    const cropViewport = currentViewport();
    transform = clampCropTransform(image.naturalWidth, image.naturalHeight, transform, cropViewport);
    const baseScale = fitCropScale(image.naturalWidth, image.naturalHeight, cropViewport);
    preview.style.width = `${image.naturalWidth * baseScale * transform.scale}px`;
    preview.style.height = `${image.naturalHeight * baseScale * transform.scale}px`;
    preview.style.left = `calc(50% + ${transform.offsetX}px)`;
    preview.style.top = `calc(50% + ${transform.offsetY}px)`;
  }

  function pointerPosition(event: PointerEvent): { x: number; y: number } {
    const rect = viewport.getBoundingClientRect();
    return { x: event.clientX - rect.left, y: event.clientY - rect.top };
  }

  function gestureState(): { centroid: { x: number; y: number }; distance: number } | undefined {
    const values = [...pointers.values()];
    if (!values.length) return undefined;
    if (values.length === 1) return { centroid: values[0], distance: 0 };
    const [a, b] = values;
    return {
      centroid: { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 },
      distance: Math.hypot(a.x - b.x, a.y - b.y),
    };
  }

  function resetGesture(): void {
    const state = gestureState();
    lastCentroid = state?.centroid;
    lastDistance = state?.distance ?? 0;
  }

  function closePhotoEditor(mode: 'dismiss' | 'remove' = 'dismiss'): Promise<void> | void {
    if (mode === 'remove') return removeActiveOverlayHistoryEntry();
    ((overlay as HTMLElement & { closeOverlay?: () => void }).closeOverlay ?? (() => overlay.remove()))();
  }

  function onResize(): void {
    render();
  }

  viewport.append(preview, ring);
  stage.append(viewport);
  actions.append(cancel, done);
  overlay.append(title, stage, actions);

  viewport.onpointerdown = event => {
    if (!initialized || done.disabled) return;
    event.preventDefault();
    viewport.setPointerCapture(event.pointerId);
    pointers.set(event.pointerId, pointerPosition(event));
    viewport.classList.add('dragging');
    resetGesture();
  };
  viewport.onpointermove = event => {
    if (!initialized || !pointers.has(event.pointerId) || done.disabled) return;
    event.preventDefault();
    pointers.set(event.pointerId, pointerPosition(event));
    const state = gestureState();
    if (!state || !lastCentroid) return;
    const pan = { x: state.centroid.x - lastCentroid.x, y: state.centroid.y - lastCentroid.y };
    const zoom = state.distance > 0 && lastDistance > 0 ? state.distance / lastDistance : 1;
    transform = applyCropGesture(
      image.naturalWidth,
      image.naturalHeight,
      transform,
      state.centroid,
      pan,
      zoom,
      currentViewport(),
    );
    lastCentroid = state.centroid;
    lastDistance = state.distance;
    render();
  };
  const releasePointer = (event: PointerEvent) => {
    pointers.delete(event.pointerId);
    if (viewport.hasPointerCapture(event.pointerId)) viewport.releasePointerCapture(event.pointerId);
    if (!pointers.size) viewport.classList.remove('dragging');
    resetGesture();
  };
  viewport.onpointerup = releasePointer;
  viewport.onpointercancel = releasePointer;
  viewport.onwheel = event => {
    if (!initialized || done.disabled) return;
    event.preventDefault();
    const rect = viewport.getBoundingClientRect();
    const centroid = { x: event.clientX - rect.left, y: event.clientY - rect.top };
    const zoom = Math.exp(-event.deltaY * 0.0016);
    transform = applyCropGesture(image.naturalWidth, image.naturalHeight, transform, centroid, { x: 0, y: 0 }, zoom, currentViewport());
    render();
  };

  const close = registerActiveOverlay(() => {
    window.removeEventListener('resize', onResize);
    overlay.remove();
  });
  root.append(overlay);
  overlay.dataset.closeId = 'photo-editor';
  (overlay as HTMLElement & { closeOverlay?: () => void }).closeOverlay = close;
  window.addEventListener('resize', onResize);
  requestAnimationFrame(initialize);
}

function closeSheet(overlay: HTMLElement, mode: 'dismiss' | 'remove' = 'dismiss'): Promise<void> | void {
  if (mode === 'remove') {
    if (activeOverlayClose) return removeActiveOverlayHistoryEntry();
    overlay.remove();
    return;
  }
  ((overlay as HTMLElement & { closeOverlay?: () => void }).closeOverlay ?? (() => overlay.remove()))();
}

async function saveContactPhotoDataUrl(contact: AccountContact, dataUrl: string): Promise<void> {
  const key = contactPhotoKey(contact.account.id, contact.login);
  const row: ContactPhoto = {
    id: key,
    accountId: contact.account.id,
    login: contact.login,
    dataUrl,
    updatedAt: Date.now(),
  };
  await saveContactPhoto(row);
  contactPhotosByKey.set(key, dataUrl);
  renderApp();
}

function renderContactPhotoDataUrl(image: HTMLImageElement, transform: CropTransform, viewport: CropViewport): string {
  const source = cropRectForViewport(image.naturalWidth, image.naturalHeight, transform, viewport);
  const canvas = document.createElement('canvas');
  canvas.width = 512;
  canvas.height = 512;
  const context = canvas.getContext('2d');
  if (!context) throw new Error('Не удалось подготовить фото');
  context.drawImage(
    image,
    Math.round(source.left),
    Math.round(source.top),
    Math.round(source.size),
    Math.round(source.size),
    0,
    0,
    512,
    512,
  );
  return canvas.toDataURL('image/jpeg', 0.88);
}

function defaultCropTransform(imageWidth: number, imageHeight: number, viewport: CropViewport): CropTransform {
  return { scale: minCropScale(imageWidth, imageHeight, viewport), offsetX: 0, offsetY: 0 };
}

function clampCropTransform(imageWidth: number, imageHeight: number, transform: CropTransform, viewport: CropViewport): CropTransform {
  const minScale = minCropScale(imageWidth, imageHeight, viewport);
  const scale = Math.min(Math.max(transform.scale, minScale), Math.max(maxCropScale, minScale));
  const baseScale = fitCropScale(imageWidth, imageHeight, viewport);
  const displayedWidth = imageWidth * baseScale * scale;
  const displayedHeight = imageHeight * baseScale * scale;
  const maxOffsetX = Math.max(0, (displayedWidth - viewport.width) / 2);
  const maxOffsetY = Math.max(0, (displayedHeight - viewport.height) / 2);
  return {
    scale,
    offsetX: Math.min(Math.max(transform.offsetX, -maxOffsetX), maxOffsetX),
    offsetY: Math.min(Math.max(transform.offsetY, -maxOffsetY), maxOffsetY),
  };
}

function applyCropGesture(
  imageWidth: number,
  imageHeight: number,
  transform: CropTransform,
  centroid: { x: number; y: number },
  pan: { x: number; y: number },
  zoom: number,
  viewport: CropViewport,
): CropTransform {
  const current = clampCropTransform(imageWidth, imageHeight, transform, viewport);
  const nextScale = current.scale * zoom;
  const scaleChange = nextScale / current.scale;
  const centroidFromCenter = {
    x: centroid.x - viewport.width / 2,
    y: centroid.y - viewport.height / 2,
  };
  const zoomedOffsetX = centroidFromCenter.x - (centroidFromCenter.x - current.offsetX) * scaleChange;
  const zoomedOffsetY = centroidFromCenter.y - (centroidFromCenter.y - current.offsetY) * scaleChange;
  return clampCropTransform(imageWidth, imageHeight, {
    scale: nextScale,
    offsetX: zoomedOffsetX + pan.x,
    offsetY: zoomedOffsetY + pan.y,
  }, viewport);
}

function cropRectForViewport(imageWidth: number, imageHeight: number, transform: CropTransform, viewport: CropViewport): CropSourceRect {
  const clamped = clampCropTransform(imageWidth, imageHeight, transform, viewport);
  const baseScale = fitCropScale(imageWidth, imageHeight, viewport);
  const visibleWidth = viewport.width / (baseScale * clamped.scale);
  const visibleHeight = viewport.height / (baseScale * clamped.scale);
  const cropSize = Math.min(visibleWidth, visibleHeight, imageWidth, imageHeight);
  const centeredLeft = (imageWidth - cropSize) / 2;
  const centeredTop = (imageHeight - cropSize) / 2;
  return {
    left: Math.min(Math.max(centeredLeft - clamped.offsetX / (baseScale * clamped.scale), 0), imageWidth - cropSize),
    top: Math.min(Math.max(centeredTop - clamped.offsetY / (baseScale * clamped.scale), 0), imageHeight - cropSize),
    size: cropSize,
  };
}

function fitCropScale(imageWidth: number, imageHeight: number, viewport: CropViewport): number {
  return Math.min(viewport.width / imageWidth, viewport.height / imageHeight);
}

function minCropScale(imageWidth: number, imageHeight: number, viewport: CropViewport): number {
  const fit = fitCropScale(imageWidth, imageHeight, viewport);
  const cover = Math.max(viewport.width / imageWidth, viewport.height / imageHeight);
  return Math.max(1, cover / fit);
}

function readFileDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(reader.error ?? new Error('Не удалось открыть изображение'));
    reader.onload = () => resolve(String(reader.result ?? ''));
    reader.readAsDataURL(file);
  });
}

function loadImage(source: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error('Не удалось открыть изображение'));
    image.src = source;
  });
}

function sinkAudio(): SinkAudioElement {
  return audio as SinkAudioElement;
}

function outputMediaDevices(): AudioOutputMediaDevices | undefined {
  return navigator.mediaDevices as AudioOutputMediaDevices | undefined;
}

function audioOutputSelectionSupported(): boolean {
  // iOS can expose setSinkId and report success without changing the physical
  // output for a remote WebRTC track. Keep the system route on these devices.
  const ios = /iPhone|iPad|iPod/i.test(navigator.userAgent)
    || (/Macintosh/i.test(navigator.userAgent) && navigator.maxTouchPoints > 1);
  return !ios && typeof sinkAudio().setSinkId === 'function' && Boolean(navigator.mediaDevices?.enumerateDevices);
}

function currentAudioOutputId(): string {
  return sinkAudio().sinkId || 'default';
}

function currentAudioOutputLabel(): string {
  const current = currentAudioOutputId();
  return audioOutputs.find(output => output.id === current)?.label ?? audioOutputs[0]?.label ?? 'Устройство';
}

async function refreshAudioOutputs(render = false): Promise<void> {
  if (!audioOutputSelectionSupported()) {
    audioOutputs = [];
    if (render) renderCall();
    return;
  }
  if (audioOutputsLoading) {
    await audioOutputsLoading;
    if (render) renderCall();
    return;
  }
  audioOutputsLoading = navigator.mediaDevices.enumerateDevices()
    .then(devices => {
      const outputs = devices.filter(device => device.kind === 'audiooutput');
      audioOutputs = outputs.map((device, index) => ({
        id: device.deviceId || 'default',
        label: audioOutputLabel(device, index),
      }));
      if (!audioOutputs.length) audioOutputs = [{ id: 'default', label: 'Устройство по умолчанию' }];
    })
    .finally(() => { audioOutputsLoading = undefined; });
  await audioOutputsLoading;
  if (render) renderCall();
}

function audioOutputLabel(device: MediaDeviceInfo, index: number): string {
  const raw = device.label.trim();
  const lower = raw.toLocaleLowerCase('ru-RU');
  if (device.deviceId === 'default') return raw || 'Устройство по умолчанию';
  if (device.deviceId === 'communications') return raw || 'Устройство связи';
  if (lower.includes('bluetooth')) return raw || 'Bluetooth';
  if (lower.includes('headset') || lower.includes('headphone') || lower.includes('науш')) return raw || 'Наушники';
  if (lower.includes('speaker') || lower.includes('динами')) return raw || 'Динамик';
  if (lower.includes('earpiece') || lower.includes('phone') || lower.includes('телефон')) return raw || 'Телефон';
  return raw || `Устройство ${index + 1}`;
}

async function toggleAudioOutput(): Promise<void> {
  if (!audioOutputSelectionSupported()) {
    audioOutputUnsupportedDialog();
    return;
  }
  // The list is populated when acquiring the microphone and on devicechange.
  // Reach setSinkId directly from this tap, before play() or enumeration.
  const next = directAudioOutput();
  if (next) return selectAudioOutput(next);
  if (audioOutputs.length > 1) {
    audioOutputSheet();
    return;
  }
  if (audioOutputPromptSupported()) return promptAudioOutputSelection();

  const call = current;
  await refreshAudioOutputs(false);
  if (current !== call) return;
  // Enumeration ended this direct gesture path. Require a fresh device-row tap.
  if (audioOutputs.length > 1) audioOutputSheet();
  else {
    audioOutputUnavailableDialog(
      'Других устройств звука не видно',
      'Браузер не показывает web-приложению отдельные варианты вроде громкого динамика, телефона или наушников. Переключите звук через системное меню телефона.',
    );
  }
}

function audioOutputPromptSupported(): boolean {
  return typeof outputMediaDevices()?.selectAudioOutput === 'function';
}

async function promptAudioOutputSelection(): Promise<void> {
  const selectAudioOutput = outputMediaDevices()?.selectAudioOutput;
  if (!selectAudioOutput) {
    audioOutputUnsupportedDialog();
    return;
  }
  try {
    const selected = await selectAudioOutput.call(outputMediaDevices());
    const id = selected.deviceId || 'default';
    await selectAudioOutputById(id, selected.label || undefined);
  } catch (error) {
    if (error instanceof DOMException && error.name === 'NotAllowedError') return;
    throw new Error(audioOutputErrorText(error));
  }
}

async function selectAudioOutputById(id: string, label?: string): Promise<void> {
  const output = audioOutputs.find(item => item.id === id) ?? { id, label: label || 'Выбранное устройство' };
  await selectAudioOutput(output);
}

function directAudioOutput(): AudioOutputDevice | undefined {
  if (audioOutputs.length !== 2) return undefined;
  const current = currentAudioOutputId();
  return audioOutputs.find(output => output.id !== current) ?? audioOutputs[0];
}

async function selectAudioOutput(output: AudioOutputDevice): Promise<void> {
  if (!audioOutputSelectionSupported()) return;
  const setSinkId = sinkAudio().setSinkId;
  if (!setSinkId) {
    audioOutputUnsupportedDialog();
    return;
  }
  try {
    if (current) await current.media.setAudioOutput(output.id);
    else {
      await setSinkId.call(sinkAudio(), output.id);
      await audio.play();
    }
    await refreshAudioOutputs(false);
    renderCall();
  } catch (error) {
    throw new Error(audioOutputErrorText(error));
  }
}

function audioOutputErrorText(error: unknown): string {
  if (error instanceof DOMException) {
    if (error.name === 'NotAllowedError' || error.name === 'SecurityError') return 'Браузер не разрешил выбрать устройство звука.';
    if (error.name === 'NotFoundError') return 'Это устройство звука больше недоступно.';
    if (error.name === 'AbortError') return 'Не удалось переключить устройство звука.';
  }
  return 'Не удалось переключить устройство звука.';
}

function audioOutputUnsupportedDialog(): void {
  audioOutputUnavailableDialog(
    'Переключение звука недоступно',
    'Этот браузер не даёт web-приложению управлять выводом на громкий или разговорный динамик. TiniTalk может переключать звук только там, где браузер поддерживает выбор аудиовыхода.',
  );
}

function audioOutputUnavailableDialog(title: string, message: string): void {
  const modal = dialog(title);
  modal.body.append(element('p', '', message));
  modal.actions.append(actionButton('Понятно', () => closeDialog(modal), 'primary'));
}

function audioOutputSheet(): void {
  if (!audioOutputSelectionSupported()) return;
  const overlay = element('div', 'sheet-overlay');
  const panel = element('section', 'bottom-sheet audio-output-sheet');
  panel.append(element('h2', '', 'Куда выводить звук'));
  const current = currentAudioOutputId();
  for (const output of audioOutputs) {
    const selected = output.id === current;
    const row = element('button', `audio-output-row ${selected ? 'selected' : ''}`.trim());
    row.type = 'button';
    row.append(icon('volume'), element('span', '', output.label));
    if (selected) row.append(element('strong', '', '✓'));
    row.onclick = () => {
      closeSheet(overlay);
      void selectAudioOutput(output).catch(failure);
    };
    panel.append(row);
  }
  overlay.append(panel);
  overlay.onclick = event => { if (event.target === overlay) closeSheet(overlay); };
  const close = registerActiveOverlay(() => overlay.remove());
  root.append(overlay);
  overlay.dataset.closeId = 'audio-output-sheet';
  (overlay as HTMLElement & { closeOverlay?: () => void }).closeOverlay = close;
}

function contactActionLabel(contact: AccountContact): string {
  if (current && samePeer(contact, current)) return 'Вернуться к звонку';
  if (current) return 'Сначала завершите текущий звонок';
  return 'Позвонить';
}

function samePeer(contact: AccountContact, call: ActiveCall): boolean {
  return call.account.id === contact.account.id && call.peerLogin === contact.login;
}

async function contactCall(contact: AccountContact): Promise<void> {
  if (current && samePeer(contact, current)) {
    renderCall();
    return;
  }
  if (!contact.can_call) {
    unavailableCallDialog(contact);
    return;
  }
  await outgoing(contact.account, contact);
}

function unavailableCallDialog(contact: AccountContact): void {
  const modal = dialog('Пока нельзя позвонить');
  modal.body.append(element('p', '', `Позвонить можно после того, как ${contactDisplayName(contact)} добавит вас в свой список контактов.`));
  modal.actions.append(actionButton('Понятно', () => closeDialog(modal), 'primary'));
}

function addContactScreen(): HTMLElement {
  const body = element('main', 'form-page');
  const form = element('form', 'material-form');
  form.noValidate = true;
  const accountField = list.length > 1 ? selectField('Сервер', 'account', list.map(account => ({ value: account.id, label: `${serverAddress(account.server)} · ${account.login}` }))) : null;
  if (accountField) form.append(accountField);
  form.append(inputField('Логин', 'login', 'text'));
  form.append(inputField('Имя в контактах', 'name', 'text'));
  const error = element('p', 'form-error');
  error.hidden = true;
  const submit = element('button', 'primary wide', 'Добавить');
  submit.type = 'submit';
  form.append(error, submit);
  form.onsubmit = event => {
    event.preventDefault();
    void (async () => {
      const data = new FormData(form);
      const account = list.find(item => item.id === String(data.get('account') || list[0]?.id));
      const login = String(data.get('login')).trim();
      const name = String(data.get('name')).trim();
      if (!account || !login || !name) throw new Error('Заполните логин и имя контакта.');
      if ([...name].length > 64) throw new Error('Имя контакта должно быть не длиннее 64 символов.');
      submit.disabled = true;
      await api(account, `/api/contacts/${encodeURIComponent(login)}`, 'PUT', { custom_name: name });
      tab = 'contacts';
      replaceRoute({ name: 'home' });
      await refreshContacts();
    })().catch(err => {
      error.hidden = false;
      error.textContent = err instanceof Error ? err.message : String(err);
    }).finally(() => { submit.disabled = false; });
  };
  body.append(form);
  return appPage(body, { title: 'Добавить контакт', back: () => goBack({ name: 'home' }), className: 'form-app-page' });
}

function credentialsScreen(mode: 'login' | 'add-account', reauth?: Account): HTMLElement {
  const page = element('section', `credential-screen ${mode}`);
  const form = element('form', 'material-form credentials');
  form.noValidate = true;
  const standalone = mode === 'login' && (!reauth || list.length === 1);
  if (standalone) {
    const header = element('div', 'login-brand');
    header.append(appMark('52px'));
    const title = element('div');
    title.append(element('h1', '', 'TiniTalk'), element('p', '', 'Звонки для своих'));
    header.append(title);
    form.append(header);
  }
  form.append(inputField('Логин', 'login', 'text', true));
  form.append(inputField('Токен', 'token', 'password', true));
  const server = inputField('Адрес сервера', 'server', 'text', true, 'talk.example.com');
  const serverStatus = element('small', 'supporting-text', 'Введите адрес сервера');
  server.append(serverStatus);
  form.append(server);
  if (reauth?.sessionReplaced) {
    form.append(element('p', 'reauth-explanation', 'Вход выполнен на другом устройстве. Войдите снова, чтобы принимать звонки здесь.'));
  }
  const error = element('p', 'form-error');
  error.hidden = true;
  const submit = element('button', 'primary wide', mode === 'login' ? 'Войти' : 'Добавить');
  submit.type = 'submit';
  const updateSubmit = () => {
    submit.disabled = Boolean(accountSubmission) || !credentialsReady(form);
  };
  form.append(error, submit);
  wireCredentialsPaste(form);
  wireServerCheck(form, serverStatus);
  form.addEventListener('input', updateSubmit);
  if (reauth?.sessionReplaced) {
    setFormValue(form, 'login', reauth.login);
    setFormValue(form, 'server', serverAddress(reauth.server));
  }
  updateSubmit();
  form.onsubmit = event => {
    event.preventDefault();
    if (submit.disabled || accountSubmission) return;
    submit.disabled = true;
    accountSubmission = submitAccount(form, mode, error, submit).catch(err => {
      error.hidden = false;
      error.textContent = err instanceof Error ? err.message : String(err);
    }).finally(() => {
      accountSubmission = undefined;
      updateSubmit();
      // Navigation may have opened another credentials form while signing in.
      screen.querySelector<HTMLFormElement>('form.credentials')?.dispatchEvent(new Event('input'));
    });
  };
  if (standalone) {
    page.append(form, element('p', 'version', 'web'));
    return page;
  }
  const body = element('main', 'form-page');
  body.append(form);
  return appPage(body, { title: reauth ? 'Войти снова' : 'Добавить аккаунт', back: () => goBack({ name: 'profile' }), className: 'form-app-page' });
}

function credentialsReady(form: HTMLFormElement): boolean {
  const login = form.querySelector<HTMLInputElement>('input[name="login"]')?.value.trim() ?? '';
  const token = form.querySelector<HTMLInputElement>('input[name="token"]')?.value.trim() ?? '';
  const server = form.querySelector<HTMLInputElement>('input[name="server"]')?.value.trim() ?? '';
  if (!login || !token || !server) return false;
  try {
    normalizeServer(server);
    return true;
  } catch {
    return false;
  }
}

function inputField(label: string, name: string, type: string, paste = false, placeholder = ''): HTMLElement {
  const input = element('input');
  input.name = name;
  input.type = type;
  input.placeholder = placeholder;
  input.setAttribute('autocomplete', name === 'login' ? 'username' : name === 'token' ? 'current-password' : 'url');
  input.autocapitalize = 'none';
  input.spellcheck = false;
  const field = inputFieldShell(label, input);
  if (paste) field.querySelector('.input-box')!.append(iconButton('Вставить', 'paste', async () => pasteIntoField(input)));
  return field;
}

function selectField(label: string, name: string, options: { value: string; label: string }[]): HTMLElement {
  const field = element('label', 'field');
  field.append(element('span', '', label));
  const select = element('select');
  select.name = name;
  for (const option of options) {
    const item = element('option');
    item.value = option.value;
    item.textContent = option.label;
    select.append(item);
  }
  const box = element('span', 'input-box');
  box.append(select);
  field.append(box);
  return field;
}

async function pasteIntoField(input: HTMLInputElement): Promise<void> {
  if (!navigator.clipboard?.readText) throw new Error('Буфер обмена недоступен.');
  const value = await navigator.clipboard.readText();
  const form = input.form;
  if (!form) return;
  const credentials = splitCredentials(value);
  if (credentials) {
    setFormValue(form, 'login', credentials[0]);
    setFormValue(form, 'token', credentials[1]);
    setFormValue(form, 'server', credentials[2]);
    form.querySelector<HTMLInputElement>('input[name="server"]')?.dispatchEvent(new Event('input'));
    return;
  }
  if (input.name === 'login') {
    const account = splitAccountAddress(value);
    if (account) {
      setFormValue(form, 'login', account[0]);
      setFormValue(form, 'server', account[1]);
      form.querySelector<HTMLInputElement>('input[name="server"]')?.dispatchEvent(new Event('input'));
      return;
    }
  }
  setFormValue(form, input.name, value.trim());
}

function wireCredentialsPaste(form: HTMLFormElement): void {
  form.querySelector<HTMLInputElement>('input[name="login"]')?.addEventListener('input', event => {
    if (!(event instanceof InputEvent) || event.inputType !== 'insertFromPaste') return;
    const input = event.currentTarget as HTMLInputElement | null;
    if (!input || input.value.length < 3 || !input.value.includes('@')) return;
    const account = splitAccountAddress(input.value);
    if (!account) return;
    setFormValue(form, 'login', account[0]);
    setFormValue(form, 'server', account[1]);
    form.querySelector<HTMLInputElement>('input[name="server"]')?.dispatchEvent(new Event('input'));
  });
}

function wireServerCheck(form: HTMLFormElement, status: HTMLElement): void {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const input = form.querySelector<HTMLInputElement>('input[name="server"]')!;
  input.addEventListener('input', () => {
    clearTimeout(timer);
    let server = '';
    try {
      server = normalizeServer(input.value);
    } catch {
      status.textContent = 'Введите адрес сервера';
      status.dataset.state = 'neutral';
      return;
    }
    status.textContent = 'Проверяем подключение…';
    status.dataset.state = 'neutral';
    timer = setTimeout(() => {
      void checkServer(server).then(result => {
        status.textContent = result.message;
        status.dataset.state = result.state;
      });
    }, 500);
  });
}

async function checkServer(server: string): Promise<{ state: string; message: string }> {
  try {
    const response = await fetch(new URL('/healthz', server), { cache: 'no-store', signal: AbortSignal.timeout(8000) });
    if (!response.ok) return { state: 'bad', message: 'По этому адресу нет сервера TiniTalk' };
    const health = await response.json() as { features?: string[] };
    if (!health.features?.includes('browser_v1')) return { state: 'warn', message: 'Сервер несовместим с этой версией приложения' };
    return { state: 'ok', message: 'Сервер TiniTalk доступен' };
  } catch {
    return { state: 'bad', message: 'Сервер недоступен. Проверьте адрес и подключение к сети' };
  }
}

async function submitAccount(form: HTMLFormElement, mode: 'login' | 'add-account', error: HTMLElement, submit: HTMLButtonElement): Promise<void> {
  error.hidden = true;
  const data = new FormData(form);
  const server = normalizeServer(String(data.get('server')));
  const login = String(data.get('login')).trim();
  const token = String(data.get('token')).trim();
  if (!login || !token) throw new Error('Заполните логин и токен.');
  const previous = accountForLogin(list, server, login);
  const loginStillValid = () => !previous || (list.includes(previous) && !removingAccounts.has(previous));
  if (!loginStillValid()) return;
  submit.disabled = true;
  const account: Account = { id: previous?.id ?? crypto.randomUUID(), server, login, token, name: previous?.name ?? login,
    deviceId: previous?.deviceId ?? crypto.randomUUID(), sessionId: '', pushConfigId: previous?.pushConfigId };
  const pushPermission = requestPushPermission();
  await claim(account);
  if (!loginStillValid()) return;
  await saveAccount(account);
  if (!loginStillValid()) {
    // Removal can finish while IndexedDB is still saving the new session.
    await deleteAccount(account.id);
    return;
  }
  if (previous) list.splice(list.indexOf(previous), 1, account);
  else list.push(account);
  connectAccount(account);
  notifications.set(account.id, await pushEnabled(account, base).catch(() => false));
  tab = 'contacts';
  form.reset();
  replaceRoute({ name: 'home' });
  // Permission is independent of login: a dismissed dialog must not block the
  // contacts screen, and a failed login must never register a push endpoint.
  const stillSignedIn = () => list.includes(account) && !account.sessionReplaced;
  void pushPermission.then(async granted => {
    if (!granted || !stillSignedIn()) return;
    await enablePush(account, base, false);
    if (stillSignedIn()) notifications.set(account.id, true);
  }).catch(() => {
    if (!stillSignedIn()) return;
    notifications.set(account.id, false);
    notice('Не удалось подключить уведомления. Попробуйте включить их в профиле.');
  }).finally(() => { if (route.name === 'profile') renderApp(); });
  await refreshAll(false);
  if (!previous && mode === 'add-account') notice('Аккаунт добавлен.');
}

function setFormValue(form: HTMLFormElement, name: string, value: string): void {
  const input = form.elements.namedItem(name);
  if (input instanceof HTMLInputElement) {
    input.value = value;
    input.dispatchEvent(new Event('input', { bubbles: true }));
  }
}

function splitAccountAddress(value: string): [string, string] | null {
  const trimmed = value.trim();
  const separator = trimmed.indexOf('@');
  if (separator <= 0 || separator !== trimmed.lastIndexOf('@') || separator === trimmed.length - 1) return null;
  const login = trimmed.slice(0, separator);
  const server = trimmed.slice(separator + 1);
  if (/\s/.test(login) || /\s/.test(server)) return null;
  return [login, server];
}

function splitCredentials(value: string): [string, string, string] | null {
  const lines = value.split(/\r?\n/).map(line => line.trim()).filter(Boolean);
  if (lines.length === 3 && !lines[0].includes('@') && !/\s/.test(lines[0]) && !/\s/.test(lines[2])) {
    try { normalizeServer(lines[2]); return [lines[0], lines[1], lines[2]]; } catch { return null; }
  }
  if (lines.length === 2) {
    const account = splitAccountAddress(lines[0]);
    if (account) return [account[0], lines[1], account[1]];
  }
  return null;
}

async function refreshAll(markHistoryRead: boolean): Promise<void> {
  await Promise.all([refreshContacts(), refreshHistory(markHistoryRead)]);
}

const contactRefreshes = new Map<Account, { dirty: boolean; promise: Promise<void> }>();

function refreshAccountContacts(account: Account): Promise<void> {
  const pending = contactRefreshes.get(account);
  if (pending) {
    pending.dirty = true;
    return pending.promise;
  }
  const state = { dirty: false, promise: Promise.resolve() };
  contactRefreshes.set(account, state);
  state.promise = (async () => {
    do {
      state.dirty = false;
      if (account.sessionReplaced || !list.includes(account)) return;
      const contacts = await api<Contact[]>(account, '/api/contacts');
      // A newer invalidation makes this response obsolete.
      if (!state.dirty && !account.sessionReplaced && list.includes(account)) contactsByAccount.set(account.id, contacts);
      if (!state.dirty && current?.account === account) {
        const contact = contacts.find(item => item.login === current?.peerLogin);
        if (contact) current.peer = contactDisplayName(contact);
      }
    } while (state.dirty);
  })().finally(() => contactRefreshes.delete(account));
  return state.promise;
}

async function refreshContacts(options: { renderStart?: boolean; renderEnd?: boolean } = {}): Promise<void> {
  const renderStart = options.renderStart ?? true;
  const renderEnd = options.renderEnd ?? true;
  loadingContacts = true;
  if (renderStart) renderApp();
  const results = await Promise.allSettled(list.filter(account => !account.sessionReplaced).map(async account => {
    await refreshAccountContacts(account);
  }));
  loadingContacts = false;
  const failed = results.find(result => result.status === 'rejected') as PromiseRejectedResult | undefined;
  if (failed) failure(failed.reason);
  if (renderEnd) renderApp();
}

async function refreshHistory(markRead: boolean, options: { renderStart?: boolean; renderEnd?: boolean } = {}): Promise<void> {
  const renderStart = options.renderStart ?? true;
  const renderEnd = options.renderEnd ?? true;
  const revision = ++historyRevision;
  contactHistory.clear();
  contactHistoryCursors.clear();
  contactHistoryErrors.clear();
  historyErrors.clear();
  historyVisibleLimit = 50;
  loadingMoreHistory = false;
  loadingHistory = true;
  if (renderStart) renderApp();
  const targets = list.filter(account => !account.sessionReplaced);
  for (const account of targets) historyCursors.set(account.id, 0);
  const results = await Promise.allSettled(targets.map(async account => {
    const pendingRead = unreadReads.get(account);
    if (pendingRead) await pendingRead.catch(() => undefined);
    const unreadVersion = unreadVersions.get(account) ?? 0;
    const page = await api<HistoryPage>(account, '/api/calls?limit=50');
    if (revision !== historyRevision || account.sessionReplaced || !list.includes(account)) return;
    historyByAccount.set(account.id, page.items);
    historyCursors.set(account.id, page.next_before);
    applyUnread(account, page, unreadVersion);
    if (markRead && page.latest_id > 0 && historyReadVisible()) {
      await markHistoryRead(account, page.latest_id);
    }
  }));
  if (revision !== historyRevision) return;
  targets.forEach((account, index) => {
    if (!account.sessionReplaced && list.includes(account) && results[index]?.status === 'rejected') historyErrors.add(account.id);
  });
  loadingHistory = false;
  const failed = results.find(result => result.status === 'rejected') as PromiseRejectedResult | undefined;
  if (failed) failure(failed.reason);
  if (renderEnd) renderApp();
}

function historyReadVisible(accountId?: string, login?: string): boolean {
  return !document.hidden && !current && (accountId === undefined
    ? route.name === 'home' && tab === 'history'
    : route.name === 'contact' && route.accountId === accountId && route.login === login);
}

function mergeHistoryItems(previous: HistoryItem[], items: HistoryItem[]): HistoryItem[] {
  return [...new Map([...previous, ...items].map(item => [item.id, item])).values()];
}

async function loadMoreHistory(retry = false): Promise<void> {
  if (loadingHistory || loadingMoreHistory) return;
  const revision = historyRevision;
  loadingMoreHistory = true;
  if (!retry) historyVisibleLimit += 50;
  const targets = list.filter(account => !account.sessionReplaced && (retry
    ? historyErrors.has(account.id)
    : !historyErrors.has(account.id) && (historyCursors.get(account.id) ?? 0) > 0));
  renderApp();
  await Promise.all(targets.map(async account => {
    const before = historyCursors.get(account.id) ?? 0;
    try {
      const pendingRead = unreadReads.get(account);
      if (pendingRead) await pendingRead.catch(() => undefined);
      const unreadVersion = unreadVersions.get(account) ?? 0;
      const page = await api<HistoryPage>(account, `/api/calls?limit=50&before=${before}`);
      if (revision !== historyRevision || account.sessionReplaced || !list.includes(account)) return;
      historyByAccount.set(account.id, before ? mergeHistoryItems(historyByAccount.get(account.id) ?? [], page.items) : page.items);
      historyCursors.set(account.id, page.next_before);
      historyErrors.delete(account.id);
      applyUnread(account, page, unreadVersion);
    } catch {
      if (revision === historyRevision && !account.sessionReplaced && list.includes(account)) historyErrors.add(account.id);
    }
  }));
  if (revision === historyRevision) { loadingMoreHistory = false; renderApp(); }
}

async function loadContactHistory(contact: AccountContact, markRead: boolean, append = false): Promise<void> {
  const key = accountKey(contact.account.id, contact.login);
  if (loadingContactHistory.has(key)) return;
  const before = append ? contactHistoryCursors.get(key) ?? 0 : 0;
  if (append && !before) return;
  const revision = historyRevision;
  const generation = contactHistoryGeneration;
  loadingContactHistory.add(key);
  contactHistoryErrors.delete(key);
  if (append) renderApp();
  try {
    const pendingRead = unreadReads.get(contact.account);
    if (pendingRead) await pendingRead.catch(() => undefined);
    const unreadVersion = unreadVersions.get(contact.account) ?? 0;
    const page = await api<HistoryPage>(contact.account, `/api/calls?peer=${encodeURIComponent(contact.login)}&limit=50&before=${before}`);
    if (revision !== historyRevision || generation !== contactHistoryGeneration || contact.account.sessionReplaced || !list.includes(contact.account)) return;
    contactHistory.set(key, append ? mergeHistoryItems(contactHistory.get(key) ?? [], page.items) : page.items);
    contactHistoryCursors.set(key, page.next_before);
    applyUnread(contact.account, page, unreadVersion);
    if (markRead && page.latest_id > 0 && historyReadVisible(contact.account.id, contact.login)) {
      await markHistoryRead(contact.account, page.latest_id, contact.login);
    }
  } catch {
    if (revision === historyRevision && generation === contactHistoryGeneration && !contact.account.sessionReplaced && list.includes(contact.account)) contactHistoryErrors.add(key);
  } finally {
    loadingContactHistory.delete(key);
    // A stale in-flight request must release its slot before rendering can reload it.
    renderApp();
  }
}

function markHistoryRead(account: Account, throughId: number, peerLogin?: string): Promise<void> {
  // Read responses contain account-wide counters, so their writes must stay ordered.
  const pending = (unreadReads.get(account) ?? Promise.resolve()).catch(() => undefined).then(async () => {
    if (account.sessionReplaced || !list.includes(account)) return;
    const read = await api<HistoryPage>(account, '/api/calls/read', 'PUT', {
      through_id: throughId, ...(peerLogin ? { peer_login: peerLogin } : {}),
    });
    if (account.sessionReplaced || !list.includes(account)) return;
    unreadVersions.set(account, (unreadVersions.get(account) ?? 0) + 1);
    applyUnreadState(account, read);
  });
  unreadReads.set(account, pending);
  return pending;
}

function applyUnread(account: Account, page: HistoryPage, version: number): void {
  if (version !== (unreadVersions.get(account) ?? 0)) return;
  applyUnreadState(account, { unread_missed_count: page.unread_missed_count, unread_missed: page.unread_missed });
}

function applyUnreadState(account: Account, state: Pick<HistoryPage, 'unread_missed_count' | 'unread_missed'>): void {
  unreadMissedCountByAccount.set(account.id, state.unread_missed_count);
  for (const key of [...unreadMissedByContact.keys()]) if (key.startsWith(`${account.id}:`)) unreadMissedByContact.delete(key);
  for (const missed of state.unread_missed ?? []) unreadMissedByContact.set(accountKey(account.id, missed.peer_login), missed.started_at);
}

function unreadCount(): number {
  return list.reduce((sum, account) => sum + (account.sessionReplaced ? 0 : unreadMissedCountByAccount.get(account.id) ?? 0), 0);
}

function clearUnread(accountId: string): void {
  unreadMissedCountByAccount.delete(accountId);
  for (const key of unreadMissedByContact.keys()) if (key.startsWith(`${accountId}:`)) unreadMissedByContact.delete(key);
}

function openContact(contact: AccountContact): void {
  navigate({ name: 'contact', accountId: contact.account.id, login: contact.login });
}

function renameDialog(contact: AccountContact): void {
  const modal = dialog('Изменить имя');
  const input = element('input');
  input.value = contactDisplayName(contact);
  input.maxLength = 64;
  const error = element('p', 'form-error');
  error.hidden = true;
  const save = actionButton('Сохранить', async () => {
    const name = input.value.trim();
    if (!name) throw new Error('Введите имя.');
    await api<Contact>(contact.account, `/api/contacts/${encodeURIComponent(contact.login)}/name`, 'PUT', { custom_name: name });
    await closeDialog(modal, 'remove');
    await refreshContacts();
    replaceRoute({ name: 'contact', accountId: contact.account.id, login: contact.login });
  }, 'primary');
  modal.body.append(inputFieldShell('Имя контакта', input), error);
  modal.actions.append(actionButton('Отмена', () => closeDialog(modal), 'secondary'), save);
}

function deleteDialog(contact: AccountContact): void {
  const modal = dialog('Удалить контакт?');
  modal.body.append(element('p', '', `Удалить «${contactDisplayName(contact)}» из списка контактов?`));
  modal.actions.append(actionButton('Отмена', () => closeDialog(modal), 'secondary'));
  modal.actions.append(actionButton('Удалить', async () => {
    await api(contact.account, `/api/contacts/${encodeURIComponent(contact.login)}`, 'DELETE');
    const key = contactPhotoKey(contact.account.id, contact.login);
    await deleteContactPhoto(key);
    contactPhotosByKey.delete(key);
    await closeDialog(modal, 'remove');
    replaceRoute({ name: 'home' });
    await refreshContacts();
  }, 'danger'));
}

function inputFieldShell(label: string, input: HTMLInputElement): HTMLElement {
  // :placeholder-shown follows typing, paste, autofill and direct value changes.
  // The real label supplies the empty state; a hint appears only on input focus.
  if (!input.placeholder) input.placeholder = ' ';
  const field = element('label', 'field');
  field.append(element('span', '', label));
  const box = element('span', 'input-box');
  box.append(input);
  field.append(box);
  return field;
}

function dialog(title: string): { overlay: HTMLElement; body: HTMLElement; actions: HTMLElement; close: () => void } {
  const overlay = element('div', 'dialog-overlay');
  const panel = element('section', 'dialog');
  const body = element('div', 'dialog-body');
  const actions = element('div', 'dialog-actions');
  panel.append(element('h2', '', title), body, actions);
  overlay.append(panel);
  const close = registerActiveOverlay(() => overlay.remove());
  root.append(overlay);
  return { overlay, body, actions, close };
}

function closeDialog(modal: { overlay: HTMLElement; close?: () => void }, mode: 'dismiss' | 'remove' = 'dismiss'): Promise<void> | void {
  if (mode === 'remove') {
    if (activeOverlayClose) return removeActiveOverlayHistoryEntry();
    modal.overlay.remove();
    return;
  }
  (modal.close ?? (() => modal.overlay.remove()))();
}

function loadingBlock(): HTMLElement {
  const box = element('div', 'loading-block');
  box.append(element('span', 'spinner'));
  return box;
}

function contactsRequiringServerSubtitle(contacts: AccountContact[]): Set<string> {
  const groups = new Map<string, AccountContact[]>();
  for (const contact of contacts) {
    const key = contactDisplayName(contact).toLocaleLowerCase('ru-RU');
    groups.set(key, [...(groups.get(key) ?? []), contact]);
  }
  const result = new Set<string>();
  for (const group of groups.values()) {
    if (new Set(group.map(contact => serverHost(contact.account.server).toLocaleLowerCase('ru-RU'))).size > 1) {
      for (const contact of group) result.add(accountKey(contact.account.id, contact.login));
    }
  }
  return result;
}

const noAnswerOutcomes = new Set(['unreachable', 'unanswered', 'cancelled_before_ringing', 'cancelled_after_ringing', 'interrupted_before_answer']);

function historyStatus(item: HistoryItem): string {
  if (item.outcome === 'completed') return `Разговор · ${historyDuration(item.duration_seconds)}`;
  if (item.outcome === 'interrupted') return `Связь прервалась · ${historyDuration(item.duration_seconds)}`;
  if (noAnswerOutcomes.has(item.outcome)) {
    if (item.direction === 'incoming') return item.reached ? 'Пропущенный' : 'Пропущенный (не в сети)';
    return item.reached ? 'Неотвеченный' : 'Неотвеченный (не в сети)';
  }
  if (item.direction === 'incoming') {
    if (item.outcome === 'busy') return 'Пропущенный (вы были заняты)';
    if (item.outcome === 'rejected') return item.reply_code ? replyStatus(item.reply_code, true) : 'Вы отклонили вызов';
    if (item.outcome === 'connection_failed') return 'Связь не установлена';
    return 'Вызов завершён';
  }
  if (item.outcome === 'busy') return 'Занято';
  if (item.outcome === 'rejected') return item.reply_code ? replyStatus(item.reply_code, false) : 'Вызов отклонён';
  if (item.outcome === 'connection_failed') return 'Связь не установлена';
  return 'Вызов завершён';
}

function replyStatus(code: string, sent: boolean): string {
  const reply = callReplies.find(item => item.code === code);
  return reply ? (sent ? reply.sentHistory : reply.receivedHistory) : (sent ? 'Вы отклонили вызов' : 'Вызов отклонён');
}

function replyResultText(code: unknown): string {
  return typeof code === 'string' ? callReplies.find(item => item.code === code)?.result ?? '' : '';
}

function historyColorClass(item: HistoryItem): string {
  if (item.direction === 'incoming' && (noAnswerOutcomes.has(item.outcome) || item.outcome === 'busy')) return 'missed';
  if (item.outcome === 'completed') return 'completed';
  return 'neutral';
}

function historyDayLabel(startedAt: number): string {
  const date = new Date(startedAt * 1000);
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const day = new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
  if (day === today) return 'Сегодня';
  if (day === today - 86400_000) return 'Вчера';
  return date.toLocaleDateString('ru-RU', { day: 'numeric', month: 'long', ...(date.getFullYear() === now.getFullYear() ? {} : { year: 'numeric' }) });
}

function historyTime(startedAt: number): string {
  return new Date(startedAt * 1000).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
}

function historyDuration(seconds: number): string {
  const safe = Math.max(0, seconds);
  const hours = Math.floor(safe / 3600);
  const minutes = Math.floor((safe % 3600) / 60);
  const rest = safe % 60;
  return hours > 0 ? `${hours}:${String(minutes).padStart(2, '0')}:${String(rest).padStart(2, '0')}` : `${minutes}:${String(rest).padStart(2, '0')}`;
}

function missedContactSubtitle(startedAt: number): string {
  const date = new Date(startedAt * 1000);
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const day = new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
  if (day === today) return `Пропущенный в ${historyTime(startedAt)}`;
  if (day === today - 86400_000) return 'Пропущенный вчера';
  return `Пропущенный ${date.toLocaleDateString('ru-RU')}`;
}

function createCall(account: Account, id: string, peer: string, peerLogin: string, incoming: boolean): ActiveCall {
  dismissEndedCall(false);
  const value: ActiveCall = {
    account, id, peer, peerLogin, incoming, started: incoming, accepted: false, seq: 0,
    status: incoming ? 'Входящий звонок' : 'Пробуем связаться…',
    security: { state: 'establishing' },
    transportRoute: 'unknown',
    video: { allowed: false, requested: false, sending: false, remoteSending: false, canSwitchCamera: false, facing: 'front' },
    muted: false,
    media: null as unknown as AudioCall,
  };
  value.media = new AudioCall(!incoming, audio,
    (type, payload) => { if (current === value) connections.get(account.id)!.send(id, type, payload); },
    status => {
      if (current !== value) return;
      if (status === 'Разговор') markCallConnected(value);
      else value.status = status;
      renderCall();
    },
    error => { if (current === value) { failure(error); hangup(value.connectedAt ? 'connection_lost' : 'failed'); } },
    {
      callId: id,
      callerLogin: incoming ? peerLogin : account.login,
      calleeLogin: incoming ? account.login : peerLogin,
      security: state => {
        if (current !== value) return;
        value.security = state;
        renderCall();
      },
      transportRoute: route => {
        if (current !== value) return;
        value.transportRoute = route;
        renderCall();
      },
      video: state => {
        if (current !== value) return;
        value.video = state;
        renderCall();
      },
      playbackBlocked: blocked => {
        if (current !== value || value.audioBlocked === blocked) return;
        value.audioBlocked = blocked;
        renderCall();
      },
    });
  return value;
}

function markCallConnected(call: ActiveCall): void {
  call.accepted = true;
  call.status = 'Разговор';
  call.connectedAt ??= Date.now();
  clearTimeout(call.expiry);
  startCallTicker();
}

function startCallTicker(): void {
  if (callTicker) return;
  callTicker = setInterval(() => {
    if (current?.connectedAt) updateCallDurationLabels(current);
    else stopCallTicker();
  }, 1000);
}

function updateCallDurationLabels(call: ActiveCall): void {
  if (!call.connectedAt) return;
  const text = callDurationText(Date.now() - call.connectedAt);
  callLayer.querySelectorAll<HTMLElement>('[data-call-duration]').forEach(item => { item.textContent = text; });
}

function stopCallTicker(): void {
  clearInterval(callTicker);
  callTicker = undefined;
}

function dismissEndedCall(render = true): void {
  clearTimeout(endedCallTimer);
  endedCallTimer = undefined;
  endedCall = null;
  callTones.idle();
  if (render) renderCall();
}

function showEndedCall(snapshot: EndedCall): void {
  endedCall = snapshot;
  clearTimeout(endedCallTimer);
  endedCallTimer = setTimeout(() => dismissEndedCall(), 3_000);
}

function endedSnapshot(call: ActiveCall, status: string, explanation = ''): EndedCall {
  const detail = call.connectedAt ? callDurationText(Date.now() - call.connectedAt) : '';
  return {
    accountId: call.account.id,
    peer: call.peer || 'TiniTalk',
    peerLogin: call.peerLogin || call.peer,
    status: detail ? 'Звонок завершён' : status,
    detail,
    explanation,
  };
}

function closeActiveCall(keepTerminal = false): void {
  if (current && !keepTerminal) connections.get(current.account.id)?.clearCall(current.id);
  current?.media.close();
  clearTimeout(current?.expiry);
  current = null;
  incomingVisibility.refresh();
  localPreviewCallId = '';
  localPreviewDragPosition = undefined;
  videoControlsCallId = '';
  videoControlsVisible = true;
  stopVideoControlsAutoHide();
  stopCallTicker();
}

function finishCurrentCall(status: string, keepTerminal = false, explanation = '', endReason?: CallToneEndReason): void {
  const call = current;
  if (!call) return;
  void closeCallNotification(call.account.id, call.id, base).catch(() => undefined);
  closeActiveOverlay();
  callTones.update(callToneState(call, endReason ?? inferredCallEndReason(call, status)));
  const snapshot = endedSnapshot(call, status, explanation);
  closeActiveCall(keepTerminal);
  showEndedCall(snapshot);
  renderCall();
  void refreshAll(false).catch(() => undefined);
}

function callToneState(call: ActiveCall, endReason?: CallToneEndReason): CallToneState {
  const direction = call.incoming ? 'incoming' : 'outgoing';
  if (endReason) return { direction, phase: 'ended', connected: Boolean(call.connectedAt), endReason };
  if (call.connectedAt) return { direction, phase: 'active', connected: true, reconnecting: call.status === 'Восстанавливаем связь…' };
  if (call.accepted || call.answering) return { direction, phase: 'active', connected: false };
  if (!call.incoming && call.status === 'Ждём ответа…') return { direction, phase: 'ringing', connected: false };
  return { direction, phase: call.incoming ? 'ringing' : 'connecting', connected: false, expiresAt: call.incomingExpiresAt };
}

function inferredCallEndReason(call: ActiveCall, status: string): CallToneEndReason {
  if (call.connectedAt) return 'remote_hangup';
  if (status === 'Занято') return 'busy';
  if (status === 'Звонок отклонён') return 'rejected';
  if (status === 'Нет ответа') return 'timed_out';
  return call.incoming ? 'remote_hangup' : 'cancelled';
}

function terminalToneReason(call: ActiveCall, type: string): CallToneEndReason {
  if (call.connectedAt) return 'remote_hangup';
  if (type === 'call.busy') return 'busy';
  if (type === 'call.reject') return 'rejected';
  if (type === 'call.expire') return 'timed_out';
  if (type === 'call.cancel') return 'cancelled';
  if (type === 'call.end') return 'remote_hangup';
  return 'failed';
}

function signalFailureToneReason(error: Error): CallToneEndReason {
  if (error instanceof SignalError && error.code === 'busy') return 'busy';
  return 'failed';
}

function terminalStatus(call: ActiveCall, type: string): string {
  if (call.connectedAt) return 'Звонок завершён';
  if (type === 'call.busy') return 'Занято';
  if (type === 'call.reject') return call.incoming ? 'Звонок завершён' : 'Звонок отклонён';
  if (type === 'call.expire') return call.incoming ? 'Звонок завершён' : 'Нет ответа';
  return 'Звонок завершён';
}

async function outgoing(account: Account, contact: Contact): Promise<void> {
  if (removingAccounts.has(account) || !list.includes(account)) throw new Error('Аккаунт отключается.');
  if (account.sessionReplaced) { navigate({ name: 'login', accountId: account.id }); return; }
  if (current) throw new Error('Сначала завершите текущий звонок.');
  const call = createCall(account, crypto.randomUUID(), contactDisplayName(contact), contact.login, false);
  current = call;
  renderCall();
  try {
    await call.media.capture();
    if (current !== call) return;
    await refreshAudioOutputs(false);
    if (current !== call) return;
    await connections.get(account.id)!.connect();
    if (current !== call) return;
    connections.get(account.id)!.send(call.id, 'call.start', { callee_id: contact.login, supports_video: true, supports_call_sas: true, supports_cross_call: false });
    call.started = true;
    call.expiry = setTimeout(() => {
      if (current !== call || call.accepted) return;
      const connection = connections.get(account.id)!;
      connection.clearCall(call.id);
      connection.send(call.id, 'call.cancel');
      finishCurrentCall('Нет ответа', true, '', 'timed_out');
    }, 47000);
  } catch (error) {
    if (current === call) {
      endLocal();
      failure(error, () => outgoing(account, contact));
    }
  }
}

async function accept(): Promise<void> {
  const call = current;
  if (!call || !call.incoming || call.accepted || call.answering) return;
  closeActiveOverlay();
  call.answering = true;
  callTones.update(callToneState(call));
  try {
    await call.media.capture();
    if (current !== call) return;
    await refreshAudioOutputs(false);
    if (current !== call) return;
    await connections.get(call.account.id)!.connect();
    if (current !== call) return;
    call.accepted = true;
    clearTimeout(call.expiry);
    call.status = 'Соединяемся…';
    connections.get(call.account.id)!.send(call.id, 'call.accept', { supports_video: true, supports_call_sas: true });
    void closeCallNotification(call.account.id, call.id, base).catch(() => undefined);
    renderCall();
  } catch (error) {
    if (current === call) failure(error, () => current === call ? accept() : undefined);
  } finally {
    call.answering = false;
    if (current === call) renderCall();
  }
}

function hangup(endReason?: CallToneEndReason): void {
  const call = current;
  if (!call) return;
  const connection = connections.get(call.account.id)!;
  connection.clearCall(call.id);
  connection.send(call.id, call.accepted ? 'call.end' : call.incoming ? 'call.reject' : 'call.cancel');
  finishCurrentCall('Звонок завершён', true, '', endReason ?? (call.connectedAt ? 'local_hangup' : 'cancelled'));
}

function rejectWithReply(code: CallReplyCode): void {
  const call = current;
  if (!call || !call.incoming || call.accepted) return;
  const connection = connections.get(call.account.id)!;
  connection.clearCall(call.id);
  connection.send(call.id, 'call.reject', { reply_code: code });
  finishCurrentCall('Звонок завершён', true, '', 'rejected');
}

function endLocal(keepTerminal = false): void {
  closeActiveOverlay();
  closeActiveCall(keepTerminal);
  renderCall();
  void refreshAll(false).catch(() => undefined);
}

async function toggleCamera(call: ActiveCall): Promise<void> {
  if (current !== call || !call.accepted) return;
  try { await call.media.setVideoRequested(!call.video.requested); }
  catch (error) { if (current === call) failure(error, () => toggleCamera(call)); }
  if (current === call) renderCall();
}

async function switchCamera(call: ActiveCall): Promise<void> {
  if (current !== call || !call.video.sending || !call.video.canSwitchCamera) return;
  try { await call.media.switchCamera(); }
  catch (error) { if (current === call) failure(error, () => toggleCamera(call)); }
  if (current === call) renderCall();
}

function videoModeActive(call: ActiveCall): boolean {
  return call.accepted && call.video.allowed && (call.video.sending || call.video.remoteSending);
}

function videoControlsMayAutoHide(call: ActiveCall): boolean {
  return Boolean(call.video.remoteSending && call.video.remoteStream);
}

function prepareVideoControls(call: ActiveCall): void {
  const mayAutoHide = videoControlsMayAutoHide(call);
  if (videoControlsCallId !== call.id) {
    videoControlsCallId = call.id;
    videoControlsVisible = true;
  }
  if (!mayAutoHide) {
    videoControlsVisible = true;
    stopVideoControlsAutoHide();
    return;
  }
  if (videoControlsVisible) scheduleVideoControlsAutoHide(call);
}

function toggleVideoControls(call: ActiveCall): void {
  if (!videoControlsMayAutoHide(call)) return;
  videoControlsVisible = !videoControlsVisible;
  applyVideoControlsVisibility();
  if (videoControlsVisible) scheduleVideoControlsAutoHide(call);
  else stopVideoControlsAutoHide();
}

function restartVideoControlsAutoHide(call: ActiveCall): void {
  if (!videoControlsMayAutoHide(call)) return;
  videoControlsVisible = true;
  applyVideoControlsVisibility();
  scheduleVideoControlsAutoHide(call);
}

function scheduleVideoControlsAutoHide(call: ActiveCall): void {
  stopVideoControlsAutoHide();
  if (!videoControlsMayAutoHide(call)) return;
  videoControlsHideTimer = setTimeout(() => {
    if (current !== call || !videoControlsMayAutoHide(call)) return;
    videoControlsVisible = false;
    applyVideoControlsVisibility();
  }, videoControlsAutoHideMs);
}

function stopVideoControlsAutoHide(): void {
  clearTimeout(videoControlsHideTimer);
  videoControlsHideTimer = undefined;
}

function applyVideoControlsVisibility(): void {
  const screen = callLayer.querySelector<HTMLElement>('.video-call-screen');
  screen?.classList.toggle('controls-hidden', !videoControlsVisible);
  positionLocalPreview(screen?.querySelector<HTMLElement>('.local-video-preview') ?? null);
}

function audioRecoveryButton(call: ActiveCall): HTMLButtonElement {
  const button = element('button', 'primary call-audio-retry', 'Включить звук');
  button.type = 'button';
  button.onclick = () => {
    if (current !== call) return;
    button.disabled = true;
    // Invoke play directly in the gesture; keep the action if the browser still refuses.
    void call.media.resumeAudio().catch(() => undefined).finally(() => { button.disabled = false; });
  };
  return button;
}

function renderCall(): void {
  if (current) callTones.update(callToneState(current));
  else if (!endedCall) callTones.idle();
  callLayer.hidden = !current && !endedCall;
  // Keep video mounted independently of voice. A negotiated video track may
  // never deliver a frame in a voice call, so its readiness cannot gate audio.
  remoteVideo.muted = true;
  const videoStream = current?.video.remoteStream ?? null;
  if (remoteVideo.srcObject !== videoStream) {
    if (!videoStream) remoteVideo.pause();
    remoteVideo.srcObject = videoStream;
  }
  if (videoStream && remoteVideo.paused) {
    void remoteVideo.play().catch(() => { /* Muted video can wait for its first frame. */ });
  }
  // Redraw only controls, retaining both players and their original tracks.
  callLayer.classList.toggle('has-remote-video', Boolean(current && videoModeActive(current)
    && current.video.remoteSending && current.video.remoteStream));
  callContent.replaceChildren();
  if (!current) {
    incomingVisibility.refresh();
    if (endedCall) callContent.append(endedCallScreen(endedCall));
    return;
  }
  const call = current;
  const incomingPending = call.incoming && !call.accepted;
  if (call.audioBlocked) callContent.append(audioRecoveryButton(call));
  if (videoModeActive(call)) {
    const videoScreen = videoCallScreen(call);
    callContent.append(videoScreen);
    incomingVisibility.refresh();
    positionLocalPreview(videoScreen.querySelector<HTMLElement>('.local-video-preview'));
    return;
  }
  const view = element('div', `call-screen ${incomingPending ? 'incoming-call-screen' : ''}`.trim());
  view.append(element('p', 'call-status', callStatusText(call)));
  if (call.connectedAt) view.append(transportRouteIndicator(call.transportRoute));
  view.append(avatar(call.peer || 'TiniTalk', call.peerLogin || call.peer, 'call-avatar', photoForAccountPeer(call.account.id, call.peerLogin)));
  view.append(element('h2', 'call-name', call.peer || 'TiniTalk'));
  if (call.connectedAt) {
    const duration = element('p', 'call-detail', callDurationText(Date.now() - call.connectedAt));
    duration.dataset.callDuration = 'true';
    view.append(duration);
    view.append(securityPanel(call.security));
  } else if (call.accepted) {
    view.append(element('p', 'call-detail', call.status));
  }
  const actions = element('div', `call-actions ${incomingPending ? 'incoming-actions' : ''}`);
  if (incomingPending) {
    actions.append(incomingCallAction('Ответить', 'answer', accept));
    actions.append(incomingCallAction('Отклонить', 'end', hangup, true));
    view.append(element('span', 'call-spacer'), actions, incomingReplySheet());
  } else {
    if (!call.incoming && !call.accepted) {
      actions.append(roundCallAction('Камера', 'videoCamera', 'disabled', () => undefined, true));
    } else {
      const cameraDisabled = !call.video.allowed;
      actions.append(roundCallAction('Камера', 'videoCamera', call.video.requested ? 'camera-active' : cameraDisabled ? 'disabled' : 'neutral', () => toggleCamera(call), cameraDisabled));
    }
    if (audioOutputSelectionSupported()) actions.append(roundCallAction('Звук', 'volume', 'neutral', toggleAudioOutput));
    actions.append(roundCallAction('Микрофон', microphoneControlIcon(call.muted), call.muted ? 'active' : 'neutral', () => {
      call.muted = !call.muted;
      call.media.mute(call.muted);
      renderCall();
    }));
    actions.append(roundCallAction(call.accepted ? 'Завершить' : 'Отменить', 'call', 'end rotated', hangup));
    if (call.video.failure) view.append(element('p', 'call-video-warning', 'Не удалось включить камеру'));
    view.append(element('span', 'call-spacer'), actions);
  }
  callContent.append(view);
  incomingVisibility.refresh();
}

function videoCallScreen(call: ActiveCall): HTMLElement {
  if (localPreviewCallId !== call.id) {
    localPreviewCallId = call.id;
    localPreviewDragPosition = undefined;
  }
  const remoteVisible = call.video.remoteSending && call.video.remoteStream;
  prepareVideoControls(call);
  const view = element('div', `call-screen video-call-screen ${videoControlsVisible ? '' : 'controls-hidden'}`.trim());
  view.onclick = event => {
    if (!videoControlsMayAutoHide(call)) return;
    if ((event.target as HTMLElement).closest('.video-controls, .local-video-preview')) return;
    toggleVideoControls(call);
  };
  const stage = element('div', 'video-stage');
  // Without remote video Android keeps the full CallScreenSurface: status,
  // prominent avatar, name and duration. The compact header belongs over video.
  const identity = element('div', remoteVisible ? 'video-call-top' : 'video-fallback');
  identity.append(element('p', 'call-status', callStatusText(call)));
  if (!remoteVisible) {
    identity.append(avatar(call.peer || 'TiniTalk', call.peerLogin || call.peer, 'call-avatar', photoForAccountPeer(call.account.id, call.peerLogin)));
  }
  const duration = element('p', 'call-detail', callDurationText(Date.now() - (call.connectedAt ?? Date.now())));
  duration.dataset.callDuration = 'true';
  identity.append(element('h2', 'call-name', call.peer || 'TiniTalk'), duration);
  if (remoteVisible) {
    view.append(identity);
  } else {
    stage.append(identity);
  }

  const localStream = call.video.localStream;
  if (localStream && call.video.requested) {
    const preview = element('div', 'local-video-preview');
    if (call.video.facing === 'front') preview.classList.add('mirrored');
    preview.append(videoElement('local-video', localStream));
    makeLocalPreviewDraggable(preview);
    view.append(preview);
  }

  const controls = element('div', 'video-controls');
  if (call.video.failure && !call.video.sending) controls.append(element('p', 'call-video-warning', 'Не удалось включить камеру'));
  const actions = element('div', 'call-actions video-actions');
  if (call.video.sending && call.video.canSwitchCamera) {
    actions.append(roundCallAction('Повернуть', 'switchCamera', 'neutral', () => {
      restartVideoControlsAutoHide(call);
      return switchCamera(call);
    }));
  }
  actions.append(roundCallAction('Камера', 'videoCamera', call.video.requested ? 'camera-active' : 'neutral', () => {
    restartVideoControlsAutoHide(call);
    return toggleCamera(call);
  }));
  if (audioOutputSelectionSupported()) {
    actions.append(roundCallAction('Звук', 'volume', 'neutral', () => {
      restartVideoControlsAutoHide(call);
      return toggleAudioOutput();
    }));
  }
  actions.append(roundCallAction('Микрофон', microphoneControlIcon(call.muted), call.muted ? 'active' : 'neutral', () => {
    restartVideoControlsAutoHide(call);
    call.muted = !call.muted;
    call.media.mute(call.muted);
    renderCall();
  }));
  actions.append(roundCallAction('Завершить', 'call', 'end rotated', () => {
    restartVideoControlsAutoHide(call);
    hangup();
  }));
  if (audioOutputSelectionSupported()) controls.append(element('p', 'video-route-label', `Звук: ${currentAudioOutputLabel()}`));
  controls.append(actions);
  view.append(stage, controls);
  return view;
}

function videoElement(className: string, stream: MediaStream): HTMLVideoElement {
  const video = element('video', className);
  video.autoplay = true;
  video.playsInline = true;
  // Only the local preview uses this helper; remote video stays mounted.
  video.muted = true;
  video.srcObject = stream;
  void video.play().catch(() => undefined);
  return video;
}

function positionLocalPreview(preview: HTMLElement | null): void {
  if (!preview) return;
  const bounds = localPreviewBounds(preview);
  applyLocalPreviewPosition(preview, localPreviewDragPosition ?? localPreviewPositionForCorner(localPreviewCorner, bounds));
}

function applyLocalPreviewPosition(preview: HTMLElement, position: LocalPreviewPosition): void {
  preview.style.left = `${Math.round(position.left)}px`;
  preview.style.top = `${Math.round(position.top)}px`;
  preview.style.right = 'auto';
  preview.style.bottom = 'auto';
}

function makeLocalPreviewDraggable(preview: HTMLElement): void {
  let pointerId: number | undefined;
  let startX = 0;
  let startY = 0;
  let startLeft = 0;
  let startTop = 0;

  preview.onpointerdown = event => {
    if (event.pointerType === 'mouse' && event.button !== 0) return;
    const screenRect = localPreviewContainer(preview).getBoundingClientRect();
    const rect = preview.getBoundingClientRect();
    pointerId = event.pointerId;
    startX = event.clientX;
    startY = event.clientY;
    startLeft = rect.left - screenRect.left;
    startTop = rect.top - screenRect.top;
    localPreviewDragPosition = clampLocalPreviewPosition(preview, startLeft, startTop);
    preview.classList.add('dragging');
    preview.setPointerCapture(event.pointerId);
    stopVideoControlsAutoHide();
    event.preventDefault();
  };
  preview.onpointermove = event => {
    if (pointerId !== event.pointerId) return;
    const position = clampLocalPreviewPosition(preview, startLeft + event.clientX - startX, startTop + event.clientY - startY);
    localPreviewDragPosition = position;
    applyLocalPreviewPosition(preview, position);
    event.preventDefault();
  };
  const finish = (event: PointerEvent) => {
    if (pointerId !== event.pointerId) return;
    pointerId = undefined;
    preview.classList.remove('dragging');
    try { preview.releasePointerCapture(event.pointerId); } catch { /* The pointer may already be released by the browser. */ }
    const bounds = localPreviewBounds(preview);
    const currentPosition = localPreviewDragPosition ?? clampLocalPreviewPosition(preview, startLeft, startTop);
    localPreviewCorner = nearestLocalPreviewCorner(currentPosition, bounds);
    saveSelfPreviewCorner(localPreviewCorner);
    localPreviewDragPosition = undefined;
    applyLocalPreviewPosition(preview, localPreviewPositionForCorner(localPreviewCorner, bounds));
    if (current && videoControlsVisible) scheduleVideoControlsAutoHide(current);
  };
  preview.onpointerup = finish;
  preview.onpointercancel = finish;
}

function localPreviewContainer(preview: HTMLElement): HTMLElement {
  return preview.closest<HTMLElement>('.video-call-screen') ?? callLayer;
}

function localPreviewBounds(preview: HTMLElement): LocalPreviewBounds {
  const container = localPreviewContainer(preview);
  const rect = preview.getBoundingClientRect();
  const previewWidth = rect.width || preview.offsetWidth || 96;
  const previewHeight = rect.height || preview.offsetHeight || Math.round(previewWidth * 16 / 9);
  const top = videoControlsVisible ? container.querySelector<HTMLElement>('.video-call-top')?.getBoundingClientRect().height ?? 0 : 0;
  const bottom = videoControlsVisible ? container.querySelector<HTMLElement>('.video-controls')?.getBoundingClientRect().height ?? 0 : 0;
  const margin = 12;
  const left = margin;
  const boundsTop = top + margin;
  const right = Math.max(left, container.clientWidth - previewWidth - margin);
  const boundsBottom = Math.max(boundsTop, container.clientHeight - previewHeight - bottom - margin);
  return { left, top: boundsTop, right, bottom: boundsBottom };
}

function localPreviewPositionForCorner(corner: SelfPreviewCorner, bounds: LocalPreviewBounds): LocalPreviewPosition {
  switch (corner) {
    case 'TopLeft': return { left: bounds.left, top: bounds.top };
    case 'TopRight': return { left: bounds.right, top: bounds.top };
    case 'BottomLeft': return { left: bounds.left, top: bounds.bottom };
    case 'BottomRight': return { left: bounds.right, top: bounds.bottom };
  }
}

function clampLocalPreviewPosition(preview: HTMLElement, left: number, top: number): LocalPreviewPosition {
  const bounds = localPreviewBounds(preview);
  return {
    left: Math.min(Math.max(left, bounds.left), bounds.right),
    top: Math.min(Math.max(top, bounds.top), bounds.bottom),
  };
}

function nearestLocalPreviewCorner(position: LocalPreviewPosition, bounds: LocalPreviewBounds): SelfPreviewCorner {
  return selfPreviewCorners.reduce((best, corner) => {
    const bestPosition = localPreviewPositionForCorner(best, bounds);
    const nextPosition = localPreviewPositionForCorner(corner, bounds);
    return squaredDistance(position, nextPosition) < squaredDistance(position, bestPosition) ? corner : best;
  }, selfPreviewCorners[0]);
}

function squaredDistance(a: LocalPreviewPosition, b: LocalPreviewPosition): number {
  const dx = a.left - b.left;
  const dy = a.top - b.top;
  return dx * dx + dy * dy;
}

function readSelfPreviewCorner(): SelfPreviewCorner {
  try {
    const value = localStorage.getItem('tinitalk.selfPreviewCorner');
    return isSelfPreviewCorner(value) ? value : 'BottomRight';
  } catch {
    return 'BottomRight';
  }
}

function saveSelfPreviewCorner(corner: SelfPreviewCorner): void {
  try { localStorage.setItem(selfPreviewCornerStorageKey, corner); } catch { /* Preference persistence is best-effort. */ }
}

function isSelfPreviewCorner(value: unknown): value is SelfPreviewCorner {
  return value === 'TopLeft' || value === 'TopRight' || value === 'BottomLeft' || value === 'BottomRight';
}

function endedCallScreen(call: EndedCall): HTMLElement {
  const view = element('div', 'call-screen ended-call-screen');
  view.append(element('p', 'call-status', call.status));
  view.append(avatar(call.peer || 'TiniTalk', call.peerLogin || call.peer, 'call-avatar', photoForAccountPeer(call.accountId, call.peerLogin)));
  view.append(element('h2', 'call-name', call.peer || 'TiniTalk'));
  if (call.detail) view.append(element('p', 'call-detail', call.detail));
  if (call.explanation) view.append(element('p', 'call-ended-explanation', call.explanation));
  view.append(element('span', 'call-spacer'), element('span', 'call-ended-footer'));
  return view;
}

function callStatusText(call: ActiveCall): string {
  if (call.connectedAt && call.status === 'Разговор') return 'Идёт разговор';
  return call.accepted ? call.status : call.incoming ? 'Входящий звонок' : call.status;
}

function callDurationText(durationMs: number): string {
  const totalSeconds = Math.max(0, Math.floor(durationMs / 1000));
  const seconds = totalSeconds % 60;
  const minutes = Math.floor(totalSeconds / 60) % 60;
  const hours = Math.floor(totalSeconds / 3600);
  return hours > 0
    ? `${hours}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
    : `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
}

function transportRouteIndicator(route: CallTransportRoute): HTMLElement {
  const box = element('span', `call-route ${route}`);
  if (route === 'unknown') return box;
  box.setAttribute('role', 'img');
  box.ariaLabel = route === 'turn' ? 'Соединение через TURN' : 'Прямое соединение';
  box.append(routePhoneIcon(), routeArrowIcon());
  if (route === 'turn') box.append(routeServerIcon(), routeArrowIcon());
  box.append(routePhoneIcon());
  return box;
}

function routePhoneIcon(): HTMLElement {
  const item = element('span', 'route-icon route-phone');
  item.append(icon('call'));
  return item;
}

function routeArrowIcon(): HTMLElement {
  const item = element('span', 'route-icon route-arrow');
  item.innerHTML = '<svg viewBox="0 0 18 12" aria-hidden="true"><path d="M5,1 L1,6 L5,11 M1,6 L17,6 M13,1 L17,6 L13,11" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"></path></svg>';
  return item;
}

function routeServerIcon(): HTMLElement {
  const item = element('span', 'route-icon route-server');
  item.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4,4.5 L20,4.5 L20,10 L4,10 Z M4,14 L20,14 L20,19.5 L4,19.5 Z" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"></path><path d="M7,7.25 m-1,0 a1,1 0,1 0,2 0 a1,1 0,1 0,-2 0 M7,16.75 m-1,0 a1,1 0,1 0,2 0 a1,1 0,1 0,-2 0" fill="currentColor"></path></svg>';
  return item;
}

function securityPanel(security: CallSecurityState): HTMLElement {
  const panel = element('button', `call-security ${security.state}`);
  panel.type = 'button';
  panel.disabled = security.state === 'establishing';
  if (security.state === 'ready') {
    const emoji = securityEmoji(security.code).join(' ');
    panel.textContent = emoji;
    panel.ariaLabel = "\u041a\u043e\u0434 \u0431\u0435\u0437\u043e\u043f\u0430\u0441\u043d\u043e\u0441\u0442\u0438: " + emoji;
  } else if (security.state === 'failed') {
    panel.textContent = "\u0421\u043e\u0435\u0434\u0438\u043d\u0435\u043d\u0438\u0435 \u043d\u0435\u0431\u0435\u0437\u043e\u043f\u0430\u0441\u043d\u043e";
  } else if (security.state === 'unavailable') {
    panel.textContent = "\u041d\u0435 \u0443\u0434\u0430\u0451\u0442\u0441\u044f \u043f\u043e\u0434\u0442\u0432\u0435\u0440\u0434\u0438\u0442\u044c \u0431\u0435\u0437\u043e\u043f\u0430\u0441\u043d\u043e\u0441\u0442\u044c \u0441\u043e\u0435\u0434\u0438\u043d\u0435\u043d\u0438\u044f";
  } else {
    panel.textContent = "\u041f\u0440\u043e\u0432\u0435\u0440\u044f\u0435\u043c \u0431\u0435\u0437\u043e\u043f\u0430\u0441\u043d\u043e\u0441\u0442\u044c \u0441\u043e\u0435\u0434\u0438\u043d\u0435\u043d\u0438\u044f\u2026";
  }
  panel.onclick = () => securityDialog(security);
  return panel;
}

function securityDialog(security: CallSecurityState): void {
  const modal = dialog(securityDetailsTitle(security));
  modal.body.append(element('p', '', securityDetailsText(security)));
  modal.actions.append(actionButton("\u041f\u043e\u043d\u044f\u0442\u043d\u043e", () => closeDialog(modal), 'primary'));
}

function securityDetailsTitle(security: CallSecurityState): string {
  if (security.state === 'ready') return "\u041a\u043e\u0434 \u0431\u0435\u0437\u043e\u043f\u0430\u0441\u043d\u043e\u0441\u0442\u0438";
  if (security.state === 'unavailable') return "\u041d\u0435 \u0443\u0434\u0430\u0451\u0442\u0441\u044f \u043f\u043e\u0434\u0442\u0432\u0435\u0440\u0434\u0438\u0442\u044c \u0431\u0435\u0437\u043e\u043f\u0430\u0441\u043d\u043e\u0441\u0442\u044c";
  if (security.state === 'failed') return "\u0421\u043e\u0435\u0434\u0438\u043d\u0435\u043d\u0438\u0435 \u043d\u0435\u0431\u0435\u0437\u043e\u043f\u0430\u0441\u043d\u043e";
  return "\u041f\u0440\u043e\u0432\u0435\u0440\u044f\u0435\u043c \u0431\u0435\u0437\u043e\u043f\u0430\u0441\u043d\u043e\u0441\u0442\u044c \u0441\u043e\u0435\u0434\u0438\u043d\u0435\u043d\u0438\u044f";
}

function securityDetailsText(security: CallSecurityState): string {
  if (security.state === 'ready') {
    return "\u0421\u0440\u0430\u0432\u043d\u0438\u0442\u0435 \u0432\u0441\u0435 5 \u044d\u043c\u043e\u0434\u0437\u0438 \u0441 \u0441\u043e\u0431\u0435\u0441\u0435\u0434\u043d\u0438\u043a\u043e\u043c. \u0415\u0441\u043b\u0438 \u043e\u043d\u0438 \u0441\u043e\u0432\u043f\u0430\u0434\u0430\u044e\u0442, \u0441\u043e\u0435\u0434\u0438\u043d\u0435\u043d\u0438\u0435 \u0437\u0430\u0449\u0438\u0449\u0435\u043d\u043e. \u0415\u0441\u043b\u0438 \u043e\u0442\u043b\u0438\u0447\u0430\u0435\u0442\u0441\u044f \u0445\u043e\u0442\u044f \u0431\u044b \u043e\u0434\u0438\u043d \u044d\u043c\u043e\u0434\u0437\u0438, \u0437\u0430\u0432\u0435\u0440\u0448\u0438\u0442\u0435 \u0437\u0432\u043e\u043d\u043e\u043a.";
  }
  if (security.state === 'unavailable') {
    return securityUnavailableText(security.reason);
  }
  if (security.state === 'failed') {
    return securityFailureText(security.reason) + "\n\n\u0417\u0430\u0432\u0435\u0440\u0448\u0438\u0442\u0435 \u0437\u0432\u043e\u043d\u043e\u043a \u0438 \u043d\u0435 \u0441\u043e\u043e\u0431\u0449\u0430\u0439\u0442\u0435 \u043a\u043e\u043d\u0444\u0438\u0434\u0435\u043d\u0446\u0438\u0430\u043b\u044c\u043d\u044b\u0435 \u0434\u0430\u043d\u043d\u044b\u0435.";
  }
  return "\u0422\u0435\u043b\u0435\u0444\u043e\u043d\u044b \u043e\u0431\u043c\u0435\u043d\u0438\u0432\u0430\u044e\u0442\u0441\u044f \u0432\u0440\u0435\u043c\u0435\u043d\u043d\u044b\u043c\u0438 \u043a\u043b\u044e\u0447\u0430\u043c\u0438 \u0438 \u043f\u0440\u043e\u0432\u0435\u0440\u044f\u044e\u0442 \u0441\u0435\u0440\u0442\u0438\u0444\u0438\u043a\u0430\u0442\u044b WebRTC.";
}

function securityUnavailableText(reason: CallSecurityUnavailableReason): string {
  if (reason === 'server_unsupported') return "\u0421\u0435\u0440\u0432\u0435\u0440 TiniTalk \u0443\u0441\u0442\u0430\u0440\u0435\u043b. \u041f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0435 \u043d\u0435 \u043c\u043e\u0436\u0435\u0442 \u043f\u043e\u0434\u0442\u0432\u0435\u0440\u0434\u0438\u0442\u044c \u0431\u0435\u0437\u043e\u043f\u0430\u0441\u043d\u043e\u0441\u0442\u044c \u044d\u0442\u043e\u0433\u043e \u0437\u0432\u043e\u043d\u043a\u0430.";
  return "\u041f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0435 \u0441\u043e\u0431\u0435\u0441\u0435\u0434\u043d\u0438\u043a\u0430 \u0443\u0441\u0442\u0430\u0440\u0435\u043b\u043e. \u0411\u0435\u0437\u043e\u043f\u0430\u0441\u043d\u043e\u0441\u0442\u044c \u044d\u0442\u043e\u0433\u043e \u0437\u0432\u043e\u043d\u043a\u0430 \u043d\u0435\u043b\u044c\u0437\u044f \u043f\u043e\u0434\u0442\u0432\u0435\u0440\u0434\u0438\u0442\u044c.";
}

function securityFailureText(reason: CallSecurityFailureReason): string {
  switch (reason) {
    case 'exchange_timeout': return "\u041f\u0440\u043e\u0432\u0435\u0440\u043a\u0430 \u0431\u0435\u0437\u043e\u043f\u0430\u0441\u043d\u043e\u0441\u0442\u0438 \u043d\u0435 \u0437\u0430\u0432\u0435\u0440\u0448\u0438\u043b\u0430\u0441\u044c \u0432 \u043e\u0442\u0432\u0435\u0434\u0451\u043d\u043d\u043e\u0435 \u0432\u0440\u0435\u043c\u044f. \u0417\u0432\u043e\u043d\u043e\u043a \u043d\u0435\u0431\u0435\u0437\u043e\u043f\u0430\u0441\u0435\u043d.";
    case 'transport_timeout': return "\u0417\u0430\u0449\u0438\u0449\u0451\u043d\u043d\u043e\u0435 \u0441\u043e\u0435\u0434\u0438\u043d\u0435\u043d\u0438\u0435 \u043d\u0435 \u0443\u0441\u0442\u0430\u043d\u043e\u0432\u0438\u043b\u043e\u0441\u044c \u0432 \u043e\u0442\u0432\u0435\u0434\u0451\u043d\u043d\u043e\u0435 \u0432\u0440\u0435\u043c\u044f. \u0417\u0432\u043e\u043d\u043e\u043a \u043d\u0435\u0431\u0435\u0437\u043e\u043f\u0430\u0441\u0435\u043d.";
    case 'transport_failed': return "\u041d\u0435 \u0443\u0434\u0430\u043b\u043e\u0441\u044c \u0443\u0441\u0442\u0430\u043d\u043e\u0432\u0438\u0442\u044c \u0437\u0430\u0449\u0438\u0449\u0451\u043d\u043d\u043e\u0435 \u0441\u043e\u0435\u0434\u0438\u043d\u0435\u043d\u0438\u0435. \u0417\u0432\u043e\u043d\u043e\u043a \u043d\u0435\u0431\u0435\u0437\u043e\u043f\u0430\u0441\u0435\u043d.";
    case 'unexpected_message': return "\u0414\u0430\u043d\u043d\u044b\u0435 \u043f\u0440\u043e\u0432\u0435\u0440\u043a\u0438 \u043f\u0440\u0438\u0448\u043b\u0438 \u0432 \u043d\u0435\u043f\u0440\u0430\u0432\u0438\u043b\u044c\u043d\u043e\u043c \u043f\u043e\u0440\u044f\u0434\u043a\u0435 \u0438\u043b\u0438 \u0431\u044b\u043b\u0438 \u043f\u043e\u0432\u0440\u0435\u0436\u0434\u0435\u043d\u044b. \u0417\u0432\u043e\u043d\u043e\u043a \u043d\u0435\u0431\u0435\u0437\u043e\u043f\u0430\u0441\u0435\u043d.";
    case 'invalid_fingerprint': return "\u0421\u0435\u0440\u0442\u0438\u0444\u0438\u043a\u0430\u0442 \u0441\u043e\u0435\u0434\u0438\u043d\u0435\u043d\u0438\u044f \u0441\u043e\u0434\u0435\u0440\u0436\u0438\u0442 \u043e\u0448\u0438\u0431\u043a\u0443. \u0417\u0432\u043e\u043d\u043e\u043a \u043d\u0435\u0431\u0435\u0437\u043e\u043f\u0430\u0441\u0435\u043d.";
    case 'fingerprint_mismatch': return "\u0421\u0435\u0440\u0442\u0438\u0444\u0438\u043a\u0430\u0442 \u0441\u043e\u0435\u0434\u0438\u043d\u0435\u043d\u0438\u044f \u043d\u0435 \u0441\u043e\u0432\u043f\u0430\u043b \u0441 \u0434\u0430\u043d\u043d\u044b\u043c\u0438 \u043f\u0440\u043e\u0432\u0435\u0440\u043a\u0438. \u0417\u0432\u043e\u043d\u043e\u043a \u043d\u0435\u0431\u0435\u0437\u043e\u043f\u0430\u0441\u0435\u043d.";
    case 'commitment_mismatch': return "\u0414\u0430\u043d\u043d\u044b\u0435 \u043f\u0440\u043e\u0432\u0435\u0440\u043a\u0438 \u0438\u0437\u043c\u0435\u043d\u0438\u043b\u0438\u0441\u044c \u043f\u043e\u0441\u043b\u0435 \u043d\u0430\u0447\u0430\u043b\u0430 \u0437\u0432\u043e\u043d\u043a\u0430. \u0417\u0432\u043e\u043d\u043e\u043a \u043d\u0435\u0431\u0435\u0437\u043e\u043f\u0430\u0441\u0435\u043d.";
    case 'fingerprint_changed': return "\u0421\u0435\u0440\u0442\u0438\u0444\u0438\u043a\u0430\u0442 \u0441\u043e\u0435\u0434\u0438\u043d\u0435\u043d\u0438\u044f \u0438\u0437\u043c\u0435\u043d\u0438\u043b\u0441\u044f \u0432\u043e \u0432\u0440\u0435\u043c\u044f \u0437\u0432\u043e\u043d\u043a\u0430. \u0417\u0432\u043e\u043d\u043e\u043a \u043d\u0435\u0431\u0435\u0437\u043e\u043f\u0430\u0441\u0435\u043d.";
    case 'invalid_public_key': return "\u041f\u043e\u043b\u0443\u0447\u0435\u043d \u043d\u0435\u043f\u0440\u0430\u0432\u0438\u043b\u044c\u043d\u044b\u0439 \u043a\u043b\u044e\u0447 \u0431\u0435\u0437\u043e\u043f\u0430\u0441\u043d\u043e\u0441\u0442\u0438. \u0417\u0432\u043e\u043d\u043e\u043a \u043d\u0435\u0431\u0435\u0437\u043e\u043f\u0430\u0441\u0435\u043d.";
    case 'internal_error': return "\u041f\u0440\u043e\u0438\u0437\u043e\u0448\u043b\u0430 \u043e\u0448\u0438\u0431\u043a\u0430 \u043f\u0440\u043e\u0432\u0435\u0440\u043a\u0438 \u0431\u0435\u0437\u043e\u043f\u0430\u0441\u043d\u043e\u0441\u0442\u0438. \u0417\u0432\u043e\u043d\u043e\u043a \u043d\u0435\u0431\u0435\u0437\u043e\u043f\u0430\u0441\u0435\u043d.";
  }
}

function roundCallAction(label: string, iconName: keyof typeof iconPaths, tone: string, action: () => void | Promise<void>, disabled = false): HTMLElement {
  const wrap = element('span', 'round-action');
  const btn = element('button', `round-button ${tone}`.trim());
  btn.type = 'button';
  btn.disabled = disabled;
  btn.ariaLabel = label;
  btn.append(icon(iconName));
  btn.onclick = async () => {
    callTones.unlock();
    // Evaluate the action inside the click, preserving gesture-gated media APIs.
    try { await action(); } catch (error) { failure(error); }
  };
  wrap.append(btn, element('span', '', label));
  return wrap;
}

function incomingCallAction(label: string, tone: 'answer' | 'end', action: () => void | Promise<void>, rotated = false): HTMLElement {
  const travel = 104;
  const threshold = 0.68;
  const wrap = element('span', 'incoming-slide-action');
  const track = element('span', 'incoming-slide-track');
  const btn = element('button', `incoming-slide-button ${tone} ${rotated ? 'rotated' : ''}`.trim());
  const surface = element('span', 'incoming-slide-surface');
  let startY = 0;
  let progress = 0;
  let dragging = false;
  let suppressClick = false;
  let committed = false;

  function setProgress(value: number): void {
    progress = Math.max(0, Math.min(1, value));
    btn.style.setProperty('--slide-offset', `${-travel * progress}px`);
  }

  function commit(): void {
    if (btn.disabled || committed) return;
    callTones.unlock();
    committed = true;
    btn.disabled = true;
    btn.classList.add('committed');
    setProgress(1);
    void Promise.resolve()
      .then(action)
      .catch(failure)
      .finally(() => {
        if (!btn.isConnected) return;
        committed = false;
        btn.disabled = false;
        btn.classList.remove('committed');
        setProgress(0);
      });
  }

  btn.type = 'button';
  btn.ariaLabel = label;
  surface.append(icon('call'));
  btn.append(surface);
  btn.onclick = event => {
    if (suppressClick) {
      event.preventDefault();
      suppressClick = false;
      return;
    }
    commit();
  };
  btn.onpointerdown = event => {
    if (btn.disabled) return;
    dragging = true;
    suppressClick = false;
    startY = event.clientY;
    btn.classList.add('dragging');
    btn.setPointerCapture(event.pointerId);
  };
  btn.onpointermove = event => {
    if (!dragging) return;
    const delta = startY - event.clientY;
    if (Math.abs(delta) > 4) suppressClick = true;
    setProgress(delta / travel);
  };
  const release = (event: PointerEvent) => {
    if (!dragging) return;
    dragging = false;
    btn.classList.remove('dragging');
    if (btn.hasPointerCapture(event.pointerId)) btn.releasePointerCapture(event.pointerId);
    if (progress >= threshold) {
      suppressClick = true;
      commit();
    } else {
      setProgress(0);
    }
  };
  btn.onpointerup = release;
  btn.onpointercancel = event => {
    if (!dragging) return;
    dragging = false;
    btn.classList.remove('dragging');
    if (btn.hasPointerCapture(event.pointerId)) btn.releasePointerCapture(event.pointerId);
    setProgress(0);
  };

  wrap.append(track, btn);
  return wrap;
}

function incomingReplySheet(): HTMLElement {
  const shell = element('div', 'incoming-reply-shell');
  const layer = element('div', 'incoming-reply-layer');
  const scrim = element('button', 'incoming-reply-scrim');
  const frame = element('div', 'incoming-reply-frame');
  const sheet = element('div', 'incoming-reply-sheet');
  const handle = element('button', 'incoming-reply-handle');
  const panel = element('section', 'incoming-reply-panel');
  const listBox = element('div', 'incoming-reply-list');
  let startX = 0;
  let startY = 0;
  let startOffset = 0;
  let sheetOffset = 210;
  let panelHeight = 210;
  let openState = false;
  let dragging = false;
  let suppressClick = false;
  let dismissOverlay: (() => void) | undefined;

  function measurePanel(): number {
    panelHeight = Math.max(48, panel.getBoundingClientRect().height || panel.offsetHeight || panelHeight);
    return panelHeight;
  }

  function setOffset(value: number): void {
    const max = measurePanel();
    sheetOffset = Math.max(0, Math.min(max, value));
    sheet.style.setProperty('--reply-sheet-offset', `${sheetOffset}px`);
    const progress = max > 0 ? 1 - sheetOffset / max : 0;
    scrim.style.opacity = `${0.65 * progress}`;
    shell.classList.toggle('open', sheetOffset < max - 0.5);
    shell.classList.toggle('expanded', sheetOffset <= 0.5);
  }

  function applySettled(expanded: boolean): void {
    openState = expanded;
    sheet.classList.remove('dragging');
    setOffset(expanded ? 0 : panelHeight);
  }

  function collapseFromHistory(): void {
    dismissOverlay = undefined;
    applySettled(false);
  }

  function settle(expanded: boolean): void {
    if (expanded) {
      applySettled(true);
      dismissOverlay ??= registerActiveOverlay(collapseFromHistory);
      return;
    }
    if (dismissOverlay) {
      dismissOverlay();
      return;
    }
    applySettled(false);
  }

  function toggle(): void {
    settle(!openState);
  }

  function open(): void {
    settle(true);
  }

  function close(): void {
    settle(false);
  }

  function resetDrag(pointerId?: number): void {
    dragging = false;
    sheet.classList.remove('dragging');
    if (pointerId !== undefined && handle.hasPointerCapture(pointerId)) handle.releasePointerCapture(pointerId);
  }

  scrim.type = 'button';
  scrim.setAttribute('aria-label', 'Закрыть варианты ответа');
  scrim.onclick = close;
  handle.type = 'button';
  handle.append(element('span', 'incoming-reply-grip'), element('span', '', 'Ответить сообщением'));
  handle.onclick = event => {
    if (suppressClick) {
      event.preventDefault();
      suppressClick = false;
      return;
    }
    toggle();
  };
  handle.onpointerdown = event => {
    if (event.pointerType === 'mouse' && event.button !== 0) return;
    measurePanel();
    startX = event.clientX;
    startY = event.clientY;
    startOffset = openState ? 0 : panelHeight;
    dragging = true;
    suppressClick = false;
    sheet.classList.add('dragging');
    handle.setPointerCapture(event.pointerId);
  };
  handle.onpointermove = event => {
    if (!dragging) return;
    const deltaY = event.clientY - startY;
    const deltaX = event.clientX - startX;
    if (Math.abs(deltaY) > 8 || Math.abs(deltaX) > 8) suppressClick = true;
    setOffset(startOffset + deltaY);
  };
  handle.onpointerup = event => {
    if (!dragging) return;
    const deltaY = event.clientY - startY;
    const deltaX = event.clientX - startX;
    const vertical = Math.abs(deltaY) > Math.abs(deltaX);
    const shouldOpen = vertical && Math.abs(deltaY) >= 36
      ? deltaY < 0
      : sheetOffset < panelHeight / 2;
    resetDrag(event.pointerId);
    settle(shouldOpen);
  };
  handle.onpointercancel = event => {
    resetDrag(event.pointerId);
    settle(openState);
  };

  for (const [index, reply] of callReplies.entries()) {
    const row = element('button', 'incoming-reply-option', reply.text);
    row.type = 'button';
    row.onclick = () => rejectWithReply(reply.code);
    listBox.append(row);
    if (index < callReplies.length - 1) listBox.append(element('span', 'incoming-reply-divider'));
  }

  panel.append(listBox);
  sheet.append(handle, panel);
  frame.append(sheet);
  layer.append(scrim, frame);
  shell.append(layer);
  requestAnimationFrame(() => setOffset(openState ? 0 : measurePanel()));
  return shell;
}

async function receive(account: Account, event: SignalEvent): Promise<void> {
  if (account.sessionReplaced || removingAccounts.has(account) || !list.includes(account)) return;
  if (event.type === 'contact.changed') {
    // HTTP must not block the socket's call event queue.
    void refreshAccountContacts(account).then(() => renderApp()).catch(() => undefined);
    return;
  }
  if (event.type === 'call.incoming' && !current) {
    if (Date.now() > event.sent_at + 45000) return;
    const login = String(event.payload.caller_login || '');
    const contact = contactsByAccount.get(account.id)?.find(item => item.login === login);
    current = createCall(account, event.call_id, contact ? contactDisplayName(contact) : login || 'Входящий звонок', login, true);
    const call = current;
    call.incomingExpiresAt = event.sent_at + 45000;
    call.expiry = setTimeout(() => {
      if (current !== call || call.accepted) return;
      connections.get(account.id)?.clearCall(call.id);
      finishCurrentCall('Звонок завершён', true, '', 'timed_out');
    }, Math.max(0, event.sent_at + 45000 - Date.now()));
    const notificationAction = takePendingNotificationAction(account.id, call.id);
    connections.get(account.id)!.send(call.id, 'call.ringing');
    if (notificationAction === 'reject') {
      applyNotificationCallAction(call, notificationAction);
      return;
    }
    renderCall();
    if (notificationAction) applyNotificationCallAction(call, notificationAction);
  } else if (event.type === 'call.incoming' && current && (current.account.id !== account.id || current.id !== event.call_id)) {
    connections.get(account.id)!.send(event.call_id, 'call.reject');
    return;
  }
  const call = current;
  if (!call || call.account.id !== account.id || call.id !== event.call_id) return;
  if (event.seq && event.seq <= call.seq) return;
  if (event.seq) call.seq = event.seq;
  if (['call.end', 'call.cancel', 'call.reject', 'call.expire', 'call.busy'].includes(event.type)) {
    connections.get(account.id)!.clearCall(call.id);
    const explanation = event.type === 'call.reject' && !call.incoming ? replyResultText(event.payload.reply_code) : '';
    finishCurrentCall(terminalStatus(call, event.type), true, explanation, terminalToneReason(call, event.type));
    return;
  }
  if (event.type === 'call.accept') {
    call.accepted = true;
    clearTimeout(call.expiry);
    call.status = 'Соединяемся…';
    renderCall();
  }
  if (event.type === 'call.connected') {
    markCallConnected(call);
    renderCall();
  }
  if (event.type === 'call.ringing') {
    call.status = 'Ждём ответа…';
    renderCall();
  }
  if (event.type.startsWith('rtc.')) await call.media.receive(event);
}

async function syncPushCall(accountId: string, callId: string): Promise<void> {
  await accountsReady;
  const account = list.find(item => item.id === accountId);
  if (!account || account.sessionReplaced) return;
  if (!callId) {
    const record = await readPush(callKey(accountId, callId));
    if (record?.type === 'session_replaced') markSessionReplaced(account, record.sessionId);
    return;
  }
  const matchesIncoming = () => current?.account.id === accountId && current.id === callId && current.incoming && !current.accepted;
  if (current && !matchesIncoming()) return;
  const state = await notificationCallState(account, callId);
  if (state === 'ended') {
    if (matchesIncoming()) finishCurrentCall('Звонок завершён', false, '', 'cancelled');
  } else if (state === 'incoming' && !current) {
    const connection = connections.get(accountId);
    if (!connection) return;
    await connection.connect();
    const record = await readPush(callKey(accountId, callId));
    if (!current && record && canOpenIncoming(record, account)) {
      // Signaling validates and replays the call. No focus, navigation or
      // microphone request: a running hidden page can simply start ringing.
      connection.send(callId, 'call.resume', { last_seq: 0 });
    }
  }
}

async function openCall(accountId: string, callId: string, action?: NotificationCallAction): Promise<void> {
  await accountsReady;
  const key = callKey(accountId, callId);
  if (action) pendingNotificationActions.set(key, action);
  const existing = openingNotificationCalls.get(key);
  if (existing) return existing;
  const job = restoreNotificationCall(accountId, callId, action).then(resuming => {
    if (!resuming) pendingNotificationActions.delete(key);
  }).catch(error => {
    pendingNotificationActions.delete(key);
    throw error;
  }).finally(() => {
    openingNotificationCalls.delete(key);
  });
  openingNotificationCalls.set(key, job);
  return job;
}

async function restoreNotificationCall(accountId: string, callId: string, action?: NotificationCallAction): Promise<boolean | void> {
  const account = list.find(item => item.id === accountId);
  if (!account) {
    notice('Эта учётка больше не добавлена.');
    return;
  }
  if (account.sessionReplaced) { navigate({ name: 'login', accountId: account.id }); return; }
  replaceRoute({ name: 'home' });
  if (current) {
    if (current.account.id !== accountId || current.id !== callId) {
      notice('Сначала завершите текущий звонок.');
      return;
    }
    const requested = takePendingNotificationAction(accountId, callId) ?? action;
    if (requested) applyNotificationCallAction(current, requested);
    return;
  }
  if (!callId) {
    return;
  }
  const record = await readPush(callKey(accountId, callId)).catch(() => undefined);
  if (record && !canOpenIncoming(record, account)) {
    notice('Звонок уже завершён или принят на другом устройстве.');
    return;
  }
  // A declarative fallback can be opened without a local inbox record, and an
  // Apple notification can outlive the ringing call. Verify it with its server.
  if (!await isActiveNotificationCall(account, callId)) {
    notice('Звонок уже завершён или принят на другом устройстве.');
    return;
  }
  const resumed = current as ActiveCall | null; // Signaling may have resumed it during the HTTP await.
  if (resumed) {
    const requested = takePendingNotificationAction(accountId, callId) ?? action;
    if (resumed.account.id === accountId && resumed.id === callId && requested) applyNotificationCallAction(resumed, requested);
    return;
  }
  try {
    await connections.get(accountId)!.connect();
    connections.get(accountId)!.send(callId, 'call.resume', { last_seq: 0 });
    return true;
  } catch (error) {
    if (action) pendingNotificationActions.delete(callKey(accountId, callId));
    throw error;
  }
}

async function openHash(hash = location.hash): Promise<void> {
  const params = new URLSearchParams(hash.replace(/^#/, ''));
  if (params.has('account')) {
    const id = params.get('account')!;
    const call = params.get('call') || '';
    const action = notificationCallAction(params.get('action'));
    writeAppHistory('replace');
    await openCall(id, call, action);
  }
}

async function init(): Promise<void> {
  const startupHash = location.hash;
  list.push(...await accounts());
  route = list.length ? { name: 'home' } : { name: 'login' };
  tab = 'contacts';
  writeAppHistory('replace');
  for (const account of list) {
    connectAccount(account, false);
  }
  resolveAccountsReady();
  renderApp();
  const target = new URLSearchParams(startupHash.replace(/^#/, ''));
  void openHash(startupHash).catch(failure);
  for (const account of list) {
    if (target.get('account') !== account.id || !target.get('call')) connectAndResume(account);
    void pushEnabled(account, base).then(enabled => { notifications.set(account.id, enabled); }).catch(() => undefined);
  }
  if (list.length) void refreshAll(false).catch(failure);
  void contactPhotos().then(photos => {
    for (const photo of photos) contactPhotosByKey.set(photo.id, photo.dataUrl);
    renderApp();
  }).catch(() => undefined);
  if ('serviceWorker' in navigator && import.meta.env.PROD) {
    shellRegistration = await navigator.serviceWorker.register(new URL('shell-worker.js', base), { scope: base, updateViaCache: 'none' });
    const updateState = () => { if (route.name === 'about' && !updatingApp) void checkAppUpdates(); else renderApp(); };
    shellRegistration.addEventListener('updatefound', () => shellRegistration?.installing?.addEventListener('statechange', updateState));
    let controlled = Boolean(navigator.serviceWorker.controller);
    navigator.serviceWorker.addEventListener('controllerchange', () => { if (controlled && !current && !updatingApp) location.reload(); controlled = true; });
  }
  for (const account of list) if (!account.sessionReplaced && account.pushConfigId && pushSupport() === null) {
    void enablePush(account, base, false).then(() => { notifications.set(account.id, true); }).catch(() => { notifications.set(account.id, false); }).finally(() => {
      if (route.name === 'about') void checkAppUpdates(); else renderApp();
    });
  }
}

function startAppClient(): void {
  setSessionReplacedHandler(markSessionReplaced);
  document.addEventListener('click', () => callTones.unlock(), { capture: true });
  document.addEventListener('keydown', () => callTones.unlock(), { capture: true });
  if ('serviceWorker' in navigator) navigator.serviceWorker.addEventListener('message', event => {
    if (event.data?.type === 'open-call') void openCall(event.data.accountId, event.data.callId, notificationCallAction(event.data.action)).catch(failure);
    if (event.data?.type === 'push-updated') {
      void syncPushCall(event.data.accountId, event.data.callId).catch(() => undefined).finally(() => {
        if (!current) void refreshAll(false).catch(() => undefined);
      });
    }
  });
  window.addEventListener('popstate', event => {
    applyPopstate(event.state);
  });
  window.addEventListener('hashchange', () => { void openHash().catch(failure); });
  window.addEventListener('online', () => { for (const account of list) connectAndResume(account); });
  window.addEventListener('pagehide', () => incomingVisibility.suspend());
  window.addEventListener('pageshow', () => incomingVisibility.resume());
  document.addEventListener('freeze', () => incomingVisibility.suspend());
  document.addEventListener('resume', () => incomingVisibility.resume());
  navigator.mediaDevices?.addEventListener?.('devicechange', () => {
    if (current) void refreshAudioOutputs(true).catch(() => undefined);
  });
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) incomingVisibility.refresh();
    else incomingVisibility.resume();
    if (!document.hidden) {
      callTones.resumeOnForeground();
      if (route.name === 'about' && !updatingApp) void checkAppUpdates();
      for (const account of list) connectAndResume(account);
      if (list.length) void refreshAll(false).catch(() => undefined);
    }
  });
  window.addEventListener('beforeunload', event => { if (current) { event.preventDefault(); event.returnValue = ''; } });

  if (navigator.locks) {
    void navigator.locks.request('tinitalk-pwa-client', { ifAvailable: true }, async lock => {
      if (!lock) {
        notice('TiniTalk уже открыт в другом окне. Используйте его или закройте и обновите эту страницу.');
        screen.hidden = true;
        return;
      }
      await init().catch(failure);
      await new Promise<void>(() => undefined);
    }).catch(failure);
  } else {
    void init().catch(failure);
  }
}

if (!showInstallationScreen(root, base)) startAppClient();
