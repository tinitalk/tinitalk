import { t, currentLocale, currentLanguage, selectedLanguage, selectLanguage, languages, browserLanguages, resolveLanguage, translate, onLanguageChange, relocalize, type Message } from './i18n';
import { explainError, OperationError } from './userErrors';
import './style.css';
import { Favorites } from './favorites';
import { bindHistoryScroll } from './historyScroll';
import { showInstallationScreen } from './installScreen';
import {
  accountForLogin,
  accountKey,
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
import { closeCallNotification, enablePush, disablePush, isActiveNotificationCall, notificationCallState, pushEnabled, pushSupport, requestPushPermission, updatePushWorker, syncPushLanguage } from './push';
import { SignalConnection, SignalError } from './signal';
import { IncomingCallVisibility } from './incomingVisibility';
import { AudioCall, type CallSecurityState, type CallTransportRoute, type CallVideoState } from './media';
import { securityEmoji, type CallSecurityFailureReason, type CallSecurityUnavailableReason } from './sas';
import { CallToneController, type CallToneEndReason, type CallToneState } from './callTones';
import { WaitingInvites, WaitingTone, type WaitingInvite } from './waitingCalls';
import { buildTime, canUpdateApplication, fetchBuildVersions, inspectUpdates, updateStatus, waitForWorker, webBuild, webCommit, type UpdateReport } from './updates';
import { microphoneControlIcon } from './callControls';
import { AuthError, authenticate, authErrorMessage, changePassword, logout, personalPasswordError } from './auth';

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
  status: Message;
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
  incomingLayout?: boolean;
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
type PendingPasswordSetup = { server: string; login: string; temporaryPassword: string; previous?: Account };

const list: Account[] = [];
const removingAccounts = new Set<Account>();
const rotatingCredentials = new Set<Account>();
const activatingAccounts = new Set<Account>();
const connections = new Map<string, SignalConnection>();
const states = new Map<string, Message>();
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
const favorites = new Favorites({ getItem: key => localStorage.getItem(key), setItem: (key, value) => localStorage.setItem(key, value) });
let showFavorites = true;
let contactGesture = false;
let deferredRender = false;
const viewScroll = new Map<string, number>();
let disposeView = () => {};
const pendingNotificationActions = new Map<string, NotificationCallAction>();
let resolveAccountsReady!: () => void;
const accountsReady = new Promise<void>(resolve => { resolveAccountsReady = resolve; });
const openingNotificationCalls = new Map<string, Promise<void>>();
const recoveringAccounts = new Map<string, Promise<void>>();
const loadingContactHistory = new Set<string>();
const aboutServers = new Map<string, AboutServerState>();
let audioOutputs: AudioOutputDevice[] = [];
let audioOutputsLoading: Promise<void> | undefined;
function callReplies(): { code: CallReplyCode; text: string; result: string; receivedHistory: string; sentHistory: string }[] { return [
  { code: 'cannot_talk', text: t('call_reply_cannot_talk'), result: t('call_reply_result_cannot_talk'), receivedHistory: t('call_reply_history_cannot_talk'), sentHistory: t('call_reply_history_sent_cannot_talk') },
  { code: 'call_me_later', text: t('call_reply_call_me_later'), result: t('call_reply_result_call_me_later'), receivedHistory: t('call_reply_history_call_me_later'), sentHistory: t('call_reply_history_sent_call_me_later') },
  { code: 'will_call_back', text: t('call_reply_will_call_back'), result: t('call_reply_result_will_call_back'), receivedHistory: t('call_reply_history_will_call_back'), sentHistory: t('call_reply_history_sent_will_call_back') },
];
}
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
const waitingCalls = new WaitingInvites();
const waitingTone = new WaitingTone();
let waitingTimer: ReturnType<typeof setTimeout> | undefined;
const waitingPromotions = new Set<WaitingInvite>();
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

function notice(message: string, undo?: () => void): void {
  const box = document.querySelector<HTMLParagraphElement>('#notice')!;
  clearTimeout(noticeTimer);
  noticeTimer = undefined;
  box.textContent = message;
  box.hidden = !message;
  box.onclick = message ? () => notice('') : null;
  if (message) {
    if (undo) box.append(actionButton(t('text_undo_196'), () => { undo(); notice(''); }, 'notice-undo'));
    box.append(element('span', 'notice-progress'));
    noticeTimer = setTimeout(() => notice(''), 3_000);
  }
}

function failure(error: unknown, retry?: () => void | Promise<void>): void {
  if (error instanceof APIError && error.replaced) return;
  const explanation = explainError(error, navigator);
  if (explanation) {
    notice('');
    closeActiveOverlay();
    const modal = dialog(explanation.title);
    modal.body.append(element('p', '', explanation.message));
    modal.actions.append(actionButton(t('text_close_272'), () => modal.close(), 'secondary'));
    if (retry) modal.actions.append(actionButton(explanation.retryLabel ?? t('text_retry_234'), async () => {
      await closeDialog(modal, 'remove');
      try { await retry(); } catch (nextError) { failure(nextError, retry); }
    }, 'primary'));
    return;
  }
  notice(error instanceof Error ? error.message : t('web_could_not_complete_the_action_3'));
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
  const collator = new Intl.Collator(currentLocale(), { sensitivity: 'base' });
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
  return [...(name.trim() || fallback.trim() || 'T')][0]?.toLocaleUpperCase(currentLocale()) ?? 'T';
}

async function claim(account: Account): Promise<void> {
  const health = await api<{ features: string[] }>(account, '/healthz');
  if (!health.features?.includes('browser_v1')) throw new Error(t('web_update_this_server_to_connect_the_web_app_4'));
  const session = await api<{ session_id: string }>(account, '/api/browser/session', 'POST', { device_id: account.deviceId });
  account.sessionId = session.session_id;
}

function markSessionReplaced(account: Account, sessionId: string): void {
  // Delayed responses from the previous login must not invalidate a new one.
  if (!sessionId || rotatingCredentials.has(account) || list.find(item => item.id === account.id) !== account || account.sessionId !== sessionId || account.sessionReplaced) return;
  requireAccountLogin(account);
}

function requireAccountLogin(account: Account): void {
  if (list.find(item => item.id === account.id) !== account || account.sessionReplaced) return;
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

function beginCredentialRotation(account: Account): void {
  if (rotatingCredentials.has(account) || removingAccounts.has(account)) throw new Error(t('web_wait_for_the_previous_action_to_finish_5'));
  rotatingCredentials.add(account);
  connections.get(account.id)?.stop();
  connections.delete(account.id);
}

function endCredentialRotation(account: Account | undefined, reconnect = false): void {
  if (!account) return;
  rotatingCredentials.delete(account);
  if (reconnect && list.includes(account) && !account.sessionReplaced && !removingAccounts.has(account)) connectAccount(account);
}

function connectAccount(account: Account, recoverActive = true): void {
  connections.get(account.id)?.stop();
  if (account.sessionReplaced || rotatingCredentials.has(account)) return;
  if (account.passwordAuth && !account.sessionId) {
    void resumeAccountActivation(account);
    return;
  }
  const connection = new SignalConnection(account,
    event => receive(account, event),
    status => {
      if (account.sessionReplaced || !list.includes(account)) return;
      states.set(account.id, status);
      if (status === 'web_online_6') connectAndResume(account);
      if (status === 'web_online_6') syncNotificationLanguages();
      if (status === 'web_online_6') void refreshAccountContacts(account).then(() => renderApp()).catch(() => undefined);
      if (status === 'web_online_6' && current?.account.id === account.id) current.media.resendVideoState();
      renderApp();
    },
    (error, callId) => {
      if (error instanceof APIError && error.replaced) return;
      const waiting = callId ? waitingCalls.get(account.id, callId) : undefined;
      if (waiting) { removeWaiting(waiting); notice(t('waiting_call_unavailable')); return; }
      if (error instanceof SignalError && error.code?.startsWith('call_sas_') && current?.account.id === account.id && (!callId || current.id === callId)) {
        current.media.rejectSecurityFromServer(error.code);
        renderCall();
        return;
      }
      failure(error);
      if (current?.account.id === account.id && (!callId || current.id === callId)) {
        finishCurrentCall(error instanceof SignalError && error.code === 'busy' ? t('text_busy_91') : t('text_call_ended_93'), true, '', signalFailureToneReason(error));
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
  if (account.sessionReplaced || rotatingCredentials.has(account)) return;
  if (account.passwordAuth && !account.sessionId) {
    void resumeAccountActivation(account);
    return;
  }
  const connection = connections.get(account.id);
  if (!connection) return;
  if (recoveringAccounts.has(account.id)) return;
  const job = connection.connect().then(() => resumeActiveCall(account)).catch(() => undefined)
    .finally(() => recoveringAccounts.delete(account.id));
  recoveringAccounts.set(account.id, job);
}

async function resumeAccountActivation(account: Account): Promise<void> {
  if (activatingAccounts.has(account) || !list.includes(account) || removingAccounts.has(account)) return;
  activatingAccounts.add(account);
  try {
    await claim(account);
    if (!list.includes(account) || removingAccounts.has(account)) return;
    await saveAccount(account);
    connectAccount(account);
    if (account.pushConfigId) void restorePushRegistration(account);
    await refreshPasswordState(account);
    await refreshAll(false);
  } catch (error) {
    if (!list.includes(account)) return;
    if (error instanceof APIError && error.status === 401) requireAccountLogin(account);
    else states.set(account.id, 'web_offline_7');
  } finally {
    activatingAccounts.delete(account);
    renderApp();
  }
}

async function restorePushRegistration(account: Account): Promise<void> {
  const token = account.token;
  const sessionId = account.sessionId;
  const stillCurrent = () => list.includes(account) && !account.sessionReplaced && !removingAccounts.has(account)
    && !rotatingCredentials.has(account) && account.token === token && account.sessionId === sessionId
    && Boolean(account.pushConfigId);
  // The saved config ID expresses the user's choice; disabling push clears it.
  // A password change removes the server registration, not the browser subscription.
  if (!sessionId || !stillCurrent()) return;
  notifications.set(account.id, false);
  try {
    await enablePush(account, base, false);
    if (stillCurrent()) notifications.set(account.id, true);
  } catch {
    if (stillCurrent()) notice(t('web_could_not_enable_notifications_try_enabling_them_in_your_profile_8'));
  } finally {
    if (stillCurrent() && route.name === 'profile') renderApp();
  }
}

async function resumeActiveCall(account: Account): Promise<void> {
  if (account.sessionReplaced || !list.includes(account)) return;
  const active = await api<{ call_id: string; incoming_calls?: {call_id: string}[] }>(account, '/api/active-call?call_waiting=1').catch(() => null);
  if (!active?.call_id || account.sessionReplaced || !list.includes(account)) return;
  for (const incoming of active.incoming_calls ?? []) {
    if (current?.account.id === account.id && current.id === incoming.call_id) continue;
    connections.get(account.id)?.send(incoming.call_id, 'call.resume', {last_seq: 0});
  }
  if (current) return;
  if (openingNotificationCalls.has(callKey(account.id, active.call_id))) return;
  connections.get(account.id)?.send(active.call_id, 'call.resume', { last_seq: 0 });
}

function renderApp(): void {
  renderCall();
  if (contactGesture) { deferredRender = true; return; }
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
  if (screen.dataset.viewKey === viewKey && route.name === 'contact') {
    const key = accountKey(route.accountId, route.login);
    const contact = findContact(route.accountId, route.login);
    // Local actions must remain visible even when history keeps the card mounted.
    const star = screen.querySelector<HTMLButtonElement>('.favorite-toggle');
    if (star) updateFavoriteButton(star, key);
    // Keep the measured, scrollable card while a background invalidation reloads
    // its history. An empty loading placeholder would clamp scrollTop to zero.
    if (contact && !contact.account.sessionReplaced && !contactHistory.has(key) && !contactHistoryErrors.has(key)) {
      if (!loadingContactHistory.has(key)) void loadContactHistory(contact, true).catch(failure);
      return;
    }
  }
  if (screen.dataset.viewKey === viewKey && screen.querySelector('[data-interacting="true"]')) {
    deferredRender = true;
    return;
  }
  const scrollKey = viewKey + (route.name === 'home' && tab === 'contacts' ? ':' + showFavorites : '');
  const oldScroller = screen.querySelector<HTMLElement>('.home-content, .contact-screen');
  if (oldScroller && screen.dataset.scrollKey) viewScroll.set(screen.dataset.scrollKey, oldScroller.scrollTop);
  if (screen.dataset.viewKey !== viewKey) notice('');
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
  disposeView();
  disposeView = () => {};
  const view = route.name === 'login' ? credentialsScreen('login', loginAccount)
    : route.name === 'add-account' ? credentialsScreen('add-account')
      : route.name === 'add-contact' ? addContactScreen()
        : route.name === 'profile' ? profileScreen()
          : route.name === 'about' ? aboutScreen()
            : route.name === 'contact' ? contactScreen(route.accountId, route.login)
              : homeScreen();
  const scrollTop = viewScroll.get(scrollKey) ?? 0;
  historyObserver?.disconnect();
  screen.replaceChildren(view);
  screen.dataset.viewKey = viewKey;
  screen.dataset.scrollKey = scrollKey;
  const scroller = screen.querySelector<HTMLElement>('.home-content, .contact-screen');
  if (scroller) wireHistoryScroll(scroller);
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
    top.append(iconButton(t('text_back_101'), 'arrowBack', options.back, 'top-back'));
    top.append(element('h1', '', options.title ?? ''));
  } else {
    const brand = element('button', 'brand-button');
    brand.type = 'button';
    brand.append(appMark('42px'), element('span', '', 'TiniTalk'));
    brand.onclick = () => navigate({ name: 'about' });
    top.append(brand, element('span', 'top-spacer'));
  }
  if (!options.back) {
    const profile = iconButton(t('text_profile_308'), list.length > 1 ? 'contacts' : 'person', () => navigate({ name: 'profile' }), 'profile-button');
    if (list.some(account => account.sessionReplaced)) {
      const dot = element('span', 'profile-attention-dot');
      dot.ariaHidden = 'true';
      profile.append(dot);
      profile.title = t('web_profile_sign_in_again_9');
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
  const refreshText = tab === 'contacts' ? t('web_updating_contacts_10') : t('web_updating_history_11');
  const indicator = pullRefreshIndicator(pullRefreshing === tab ? refreshText : t('web_pull_down_to_refresh_12'));
  content.append(indicator, tab === 'contacts' ? contactsPage() : historyPage());
  wirePullRefresh(content, indicator, tab);
  const nav = element('nav', 'bottom-nav');
  nav.append(navItem(t('text_contacts_298'), 'contacts', tab === 'contacts', () => switchHomeTab('contacts')));
  nav.append(navItem(t('text_history_251'), 'history', tab === 'history', () => switchHomeTab('history'), unreadCount()));
  const wrap = element('div', 'home-wrap');
  if (tab === 'contacts' && allContacts().some(c => favorites.keys.includes(accountKey(c.account.id, c.login)))) {
    const tabs = element('div', `favorite-tabs ${showFavorites ? '' : 'all-selected'}`);
    const previousTabs = screen.querySelector('.favorite-tabs');
    if (previousTabs && previousTabs.classList.contains('all-selected') !== !showFavorites) {
      const targetAll = !showFavorites;
      tabs.classList.toggle('all-selected', !targetAll);
      requestAnimationFrame(() => requestAnimationFrame(() => {
        if (tabs.isConnected) tabs.classList.toggle('all-selected', targetAll);
      }));
    }
    tabs.setAttribute('role', 'tablist');
    for (const [index, label] of [t('text_favorites_247'), t('text_all_248')].entries()) {
      const button = actionButton(label, () => { showFavorites = index === 0; renderApp(); }, 'favorite-tab');
      button.setAttribute('role', 'tab');
      button.setAttribute('aria-selected', String(showFavorites === (index === 0)));
      tabs.append(button);
    }
    wrap.append(tabs);
  }
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
      text.textContent = pull >= threshold ? t('web_release_to_refresh_13') : t('web_pull_down_to_refresh_12');
    }
  }

  function resetPull(): void {
    scroller.classList.remove('pulling');
    scroller.style.removeProperty('--pull-y');
    scroller.style.removeProperty('--pull-alpha');
    scroller.style.removeProperty('--pull-rotate');
    pull = 0;
    if (text && pullRefreshing !== refreshTab) text.textContent = t('web_pull_down_to_refresh_12');
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
  const all = allContacts();
  const byKey = new Map(all.map(c => [accountKey(c.account.id, c.login), c]));
  const starred = favorites.keys.flatMap(key => byKey.has(key) ? [byKey.get(key)!] : []);
  const favoriteMode = showFavorites && starred.length > 0;
  const contacts = favoriteMode ? starred : all;
  if (!contacts.length) {
    const empty = element('div', 'empty-state');
    empty.append(element('h2', '', t('text_no_contacts_yet_300')), element('p', '', t('text_add_your_first_contact_301')));
    empty.append(actionButton(t('text_add_303'), () => navigate({ name: 'add-contact' }), 'text-action'));
    page.append(empty);
    return page;
  }
  const duplicates = contactsRequiringServerSubtitle(contacts);
  const listEl = element('div', 'material-list');
  for (const contact of contacts) {
    const row = contactRow(contact, duplicates.has(accountKey(contact.account.id, contact.login)));
    row.dataset.peer = accountKey(contact.account.id, contact.login);
    if (!favoriteMode && favorites.keys.includes(row.dataset.peer)) {
      const badge = element('span', 'favorite-badge');
      badge.append(favoriteStar());
      row.querySelector('.contact-avatar')?.append(badge);
    }
    listEl.append(row);
  }
  if (favoriteMode) wireFavoriteDrag(listEl);
  const add = actionButton(t('text_add_303'), () => navigate({ name: 'add-contact' }), 'text-action list-add');
  page.append(listEl);
  if (!favoriteMode) page.append(add);
  return page;
}

function favoriteStar(): SVGSVGElement {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('aria-hidden', 'true');
  svg.innerHTML = '<path d="M12 2.5 15.3 7.7 21.4 9.3 17.5 14.1 17.8 20.5 12 18.2 6.2 20.5 6.5 14.1 2.6 9.3 8.7 7.7Z" fill="currentColor"/>';
  return svg;
}

function wireFavoriteDrag(listEl: HTMLElement): void {
  let timer: ReturnType<typeof setTimeout> | undefined;
  let dragged: HTMLElement | undefined;
  let ghost: HTMLElement | undefined;
  let pointer = -1, startX = 0, startY = 0, latestY = 0, offset = 0, frame = 0;
  let original: HTMLElement[] = [];
  let suppressClick = false;
  const finish = (save: boolean) => {
    clearTimeout(timer);
    cancelAnimationFrame(frame);
    contactGesture = false;
    if (!dragged) {
      if (deferredRender) { deferredRender = false; setTimeout(renderApp, 0); }
      return;
    }
    const row = dragged;
    dragged = undefined;
    ghost?.remove(); ghost = undefined;
    row.classList.remove('drag-placeholder');
    if (!save) listEl.replaceChildren(...original);
    try {
      if (save) favorites.reorder(Array.from(listEl.children, child => (child as HTMLElement).dataset.peer!));
    } catch (error) { listEl.replaceChildren(...original); failure(error); }
    if (deferredRender) { deferredRender = false; renderApp(); }
  };
  const tick = () => {
    if (!dragged || !ghost || !listEl.isConnected) { finish(false); return; }
    ghost.style.top = `${latestY - offset}px`;
    const scroller = listEl.closest<HTMLElement>('.home-content')!;
    const bounds = scroller.getBoundingClientRect();
    const edge = 48;
    scroller.scrollTop += latestY < bounds.top + edge ? -8 : latestY > bounds.bottom - edge ? 8 : 0;
    const siblings = Array.from(listEl.children) as HTMLElement[];
    // Hit testing must use layout positions, not the animated visual positions:
    // otherwise a moving neighbour repeatedly reverses the reorder.
    const firstOffset = siblings[0]?.offsetTop ?? 0;
    const listTop = listEl.getBoundingClientRect().top;
    const target = siblings.find(row => row !== dragged && latestY < listTop + row.offsetTop - firstOffset + row.offsetHeight / 2);
    const next = target ?? null;
    if (dragged.nextElementSibling !== next) {
      const before = new Map(siblings.map(row => [row, row.getBoundingClientRect().top]));
      listEl.insertBefore(dragged, next);
      if (!matchMedia('(prefers-reduced-motion: reduce)').matches) for (const row of siblings) {
        if (row !== dragged) {
          const delta = before.get(row)! - row.getBoundingClientRect().top;
          if (delta) row.animate([{ transform: `translateY(${delta}px)` }, { transform: 'translateY(0)' }], { duration: 160 });
        }
      }
    }
    frame = requestAnimationFrame(tick);
  };
  listEl.addEventListener('pointerdown', event => {
    if (event.button !== 0 || !event.isPrimary) return;
    const row = (event.target as Element).closest<HTMLElement>('[data-peer]');
    if (!row) return;
    contactGesture = true;
    suppressClick = false;
    pointer = event.pointerId; startX = event.clientX; startY = latestY = event.clientY;
    timer = setTimeout(() => {
      if (!row.isConnected) return;
      dragged = row; contactGesture = true; suppressClick = true;
      original = Array.from(listEl.children) as HTMLElement[];
      const rect = row.getBoundingClientRect(); offset = startY - rect.top;
      ghost = row.cloneNode(true) as HTMLElement;
      ghost.classList.add('drag-floating'); ghost.setAttribute('aria-hidden', 'true');
      ghost.style.width = `${rect.width}px`; ghost.style.left = `${rect.left}px`;
      document.body.append(ghost);
      row.classList.add('drag-placeholder');
      listEl.setPointerCapture(pointer);
      frame = requestAnimationFrame(tick);
    }, 350);
  });
  listEl.addEventListener('pointermove', event => {
    if (event.pointerId !== pointer) return;
    latestY = event.clientY;
    if (!dragged && Math.hypot(event.clientX - startX, event.clientY - startY) > 8) clearTimeout(timer);
    if (dragged) event.preventDefault();
  });
  // Native scrolling stays available until the long press claims the gesture.
  listEl.addEventListener('touchmove', event => {
    if (dragged && event.touches.length === 1) {
      latestY = event.touches[0].clientY;
      event.preventDefault();
      event.stopPropagation(); // Do not also start pull-to-refresh beneath a dragged row.
    }
  }, { passive: false });
  listEl.addEventListener('pointerup', () => finish(true));
  listEl.addEventListener('pointercancel', () => finish(false));
  listEl.addEventListener('lostpointercapture', () => finish(false));
  const released = (event: PointerEvent) => { if (event.pointerId === pointer) finish(event.type === 'pointerup'); };
  const blurred = () => finish(false);
  window.addEventListener('pointerup', released);
  window.addEventListener('pointercancel', released);
  window.addEventListener('blur', blurred);
  const previousDispose = disposeView;
  disposeView = () => {
    previousDispose(); clearTimeout(timer); cancelAnimationFrame(frame); ghost?.remove();
    window.removeEventListener('pointerup', released); window.removeEventListener('pointercancel', released); window.removeEventListener('blur', blurred);
  };
  listEl.addEventListener('contextmenu', event => event.preventDefault());
  listEl.addEventListener('click', event => { if (suppressClick) { event.preventDefault(); event.stopPropagation(); suppressClick = false; } }, true);
  listEl.addEventListener('keydown', event => {
    if (!event.altKey || !['ArrowUp', 'ArrowDown'].includes(event.key)) return;
    const row = (event.target as Element).closest<HTMLElement>('[data-peer]');
    if (!row) return;
    event.preventDefault();
    const adjacent = event.key === 'ArrowUp' ? row.previousElementSibling : row.nextElementSibling;
    if (!adjacent) return;
    listEl.insertBefore(row, event.key === 'ArrowUp' ? adjacent : adjacent.nextElementSibling);
    try { favorites.reorder(Array.from(listEl.children, child => (child as HTMLElement).dataset.peer!)); } catch (error) { failure(error); renderApp(); }
    row.focus();
  });
  for (const row of listEl.children) row.setAttribute('aria-description', t('web_hold_to_move_keyboard_alt_and_the_up_or_down_arrow_14'));
}

function wireHistoryScroll(scroller: HTMLElement): void {
  const page = scroller.closest<HTMLElement>('.app-page')!;
  const contact = page.classList.contains('collapsing-contact');
  if (!contact && !scroller.querySelector('.history-list')) return;
  const up = iconButton(t('text_back_to_top_204'), 'chevron', () => {}, 'scroll-top');
  page.append(up);
  const cleanup = bindHistoryScroll(scroller, up, () => {
    if (deferredRender) { deferredRender = false; renderApp(); }
  });
  const previousDispose = disposeView;
  disposeView = () => { previousDispose(); cleanup(); };
}

function contactRow(contact: AccountContact, showServer: boolean): HTMLElement {
  const name = contactDisplayName(contact);
  const row = element('button', 'contact-row');
  row.type = 'button';
  row.onclick = () => openContact(contact);
  row.append(avatar(name, contact.login, 'contact-avatar', photoForContact(contact)));
  const text = element('span', 'contact-text');
  const details = [showServer ? serverHost(contact.account.server) : '', contact.can_call ? '' : t('text_calls_not_available_yet_305')].filter(Boolean).join(' • ');
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
    empty.append(clock, element('h2', '', t('text_no_call_history_yet_277')), element('p', '', t('text_your_incoming_and_outgoing_calls_will_appear_here_278')));
    page.append(empty);
    return page;
  }
  page.append(historyRows(rows, true));
  if (loadingMoreHistory) page.append(loadingBlock());
  else if (!loadingHistory) {
    const more = allHistory().length > historyVisibleLimit || list.some(a => !a.sessionReplaced && !historyErrors.has(a.id) && (historyCursors.get(a.id) ?? 0) > 0);
    if (more) page.append(historyMoreButton(() => loadMoreHistory()));
    if (historyErrors.size) page.append(actionButton(t('web_could_not_load_history_retry_15'), () => loadMoreHistory(true), 'text-action'));
  }
  return page;
}

function historyMoreButton(action: () => Promise<void>): HTMLElement {
  const button = actionButton(t('web_load_more_16'), action, 'text-action');
  button.dataset.historyMore = 'true';
  return button;
}

function historyRows(rows: (HistoryItem | AccountHistory)[], showPeer: boolean): HTMLElement {
  const listEl = element('div', 'history-list');
  let group: HTMLElement;
  rows.forEach((item, index) => {
    const day = historyDayLabel(item.started_at);
    const previous = rows[index - 1];
    if (!previous || historyDayLabel(previous.started_at) !== day) {
      group = element('section', 'history-day');
      const heading = element('h3', 'day-label');
      heading.append(element('span', '', day));
      group.append(heading); listEl.append(group);
    }
    group.append(historyRow(item, showPeer));
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
    else notice(t('text_contact_no_longer_available_211'));
  };
  if (showPeer) row.append(avatar(name, item.peer_login, 'history-avatar', account ? photoForAccountPeer(account.id, item.peer_login) : ''));
  const text = element('span', 'history-text');
  text.append(element('strong', '', showPeer ? name : (item.direction === 'incoming' ? t('text_incoming_273') : t('text_outgoing_274'))));
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
  body.append(languageButton());
  body.append(aboutApplicationCard());
  for (const entry of entries) {
    body.append(aboutInfoCard(t('text_server_106'), [
      [t('text_address_107'), serverAddress(entry.server)],
    ], [
      [t('text_api_version_109'), entry.state.details?.api_version ? String(entry.state.details.api_version) : t('text_not_specified_110')],
      [t('text_commit_105'), entry.state.details?.commit?.trim() || t('text_not_specified_108')],
    ]));
    body.append(aboutServerStatusCard(entry.state));
  }
  return appPage(body, { title: t('text_about_102'), back: () => goBack({ name: 'home' }), className: 'about-app-page' });
}

function aboutApplicationCard(): HTMLElement {
  const card = aboutInfoCard(t('text_application_103'), [], [
    [t('text_version_104'), t('web_web_version_value_17', webCommit)],
    [t('web_built_18'), buildTime(webBuild)],
  ]);
  card.append(aboutUpdateStatusRow());
  if (canUpdateApplication(updateReport)) {
    const update = actionButton(t('web_update_app_19'), updateWebApplication, 'primary wide about-update-button');
    update.disabled = checkingUpdates || updatingApp || Boolean(current);
    card.append(update);
  }
  return card;
}

function aboutUpdateStatusRow(): HTMLElement {
  const status = updateReport ? updateStatus(updateReport) : undefined;
  const kind = !checkingUpdates && !updateError ? status?.kind || '' : '';
  const label = updatingApp ? t('web_installing_update_20')
    : checkingUpdates ? t('web_checking_version_21')
      : updateError || status?.kind === 'unknown' ? t('web_could_not_check_22')
        : status?.kind === 'update' ? t('web_new_version_available_23')
          : t('web_up_to_date_24');
  const row = element('div', `about-update-row ${kind}`.trim());
  row.setAttribute('role', 'status');
  if (updateError || status?.text) row.title = updateError || status?.text || '';
  row.append(element('span', 'about-update-status', label));
  const check = iconButton(t('web_check_for_updates_25'), 'refresh', checkAppUpdates, 'about-check-button');
  check.disabled = checkingUpdates || updatingApp;
  row.append(check);
  return row;
}

async function checkAppUpdates(showProgress = true): Promise<void> {
  if (checkingUpdates) return;
  checkingUpdates = true; updateError = '';
  if (showProgress && route.name === 'about') renderApp();
  try { updateReport = await inspectUpdates(base, [...list]); }
  catch { updateError = t('web_could_not_check_background_services_check_your_connection_and_try_26'); }
  finally { checkingUpdates = false; if (route.name === 'about') renderApp(); }
}

async function updateWebApplication(): Promise<void> {
  if (updatingApp || current) return;
  updatingApp = true; updateError = ''; renderApp();
  try {
    const latest = await fetchBuildVersions(base);
    if (!('serviceWorker' in navigator)) throw new Error(t('web_service_worker_unavailable_27'));
    shellRegistration = await navigator.serviceWorker.register(new URL('shell-worker.js', base), { scope: base, updateViaCache: 'none' });
    await shellRegistration.update();
    if (shellRegistration.installing) await waitForWorker(shellRegistration.installing, ['installed', 'activated']);
    if (current) throw new Error(t('web_update_ready_end_the_call_and_tap_update_app_again_28'));
    // A newer main bundle knows which push code to register. Do not downgrade
    // its registrations using a stale page's embedded worker fingerprint.
    if (latest.build === webBuild) {
      // Updating code does not require notification permission or a family server.
      // Subscription reconciliation runs independently during startup.
      await Promise.all(list.filter(account => !account.sessionReplaced && account.pushConfigId)
        .map(account => updatePushWorker(account, base)));
    }
    if (current) throw new Error(t('web_update_ready_try_again_after_the_call_29'));
    const waiting = shellRegistration.waiting;
    if (waiting) {
      waiting.postMessage({ type: 'activate-update' });
      await waitForWorker(waiting, ['activated']);
    }
    if (!current) location.reload();
  } catch (error) {
    updateError = error instanceof Error ? error.message : t('web_could_not_update_the_app_30');
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
  text.append(element('strong', '', t('text_server_status_111')), element('span', '', status.text));
  card.append(mark, text);
  return card;
}

function aboutServerStatus(state: AboutServerState): { kind: 'checking' | 'available' | 'incompatible' | 'unavailable'; text: string } {
  if (state.loading) return { kind: 'checking', text: t('text_checking_connection_282') };
  if (state.error) return { kind: 'unavailable', text: state.error };
  const details = state.details;
  if (!details) return { kind: 'unavailable', text: t('text_server_unavailable_check_the_address_and_your_connection_285') };
  if (details.service !== 'tinitalk' || details.status !== 'ok') return { kind: 'unavailable', text: t('text_no_tinitalk_server_at_this_address_284') };
  if (!details.features?.includes('browser_v1')) return { kind: 'incompatible', text: t('text_the_server_is_incompatible_with_this_app_version_18') };
  return { kind: 'available', text: t('text_tinitalk_server_available_283') };
}

function ensureAboutServerDetails(account: Account): void {
  const server = normalizeServer(account.server);
  const cached = aboutServers.get(server);
  if (cached?.loading || cached?.details || cached?.error) return;
  aboutServers.set(server, { loading: true });
  void fetch(new URL('/healthz', server), { cache: 'no-store', signal: AbortSignal.timeout(8000) })
    .then(async response => {
      if (!response.ok) throw new Error(t('text_no_tinitalk_server_at_this_address_284'));
      return await response.json() as ServerHealth;
    })
    .then(details => { aboutServers.set(server, { loading: false, details }); })
    .catch(error => {
      aboutServers.set(server, { loading: false, error: error instanceof Error ? error.message : t('text_server_unavailable_check_the_address_and_your_connection_285') });
    })
    .finally(() => { if (route.name === 'about') renderApp(); });
}

function profileScreen(): HTMLElement {
  const body = element('main', 'form-page profile-page');
  const accountsBlock = element('div', 'account-list');
  for (const account of list) accountsBlock.append(accountCard(account));
  body.append(accountsBlock);
  body.append(actionButton(t('text_add_303'), () => navigate({ name: 'add-account' }), 'text-action list-add'));
  return appPage(body, { title: t('text_profile_308'), back: () => goBack({ name: 'home' }) });
}

function accountCard(account: Account): HTMLElement {
  const card = element('article', 'account-card');
  const top = element('div', 'profile-account-top');
  top.append(element('strong', '', account.login));
  const remove = iconButton(account.sessionReplaced ? t('text_delete_239') : t('text_sign_out_323'), account.sessionReplaced ? 'delete' : 'logout',
    () => confirmRemoveAccount(account), 'logout-button');
  top.append(remove);
  const server = element('p', 'profile-server', serverAddress(account.server));
  const status = profileAccountStatus(account);
  const statusRow = element('div', `profile-server-status ${status.kind}`);
  if (status.kind === 'checking') statusRow.append(element('span', 'tiny-spinner'));
  else if (status.kind !== 'available') statusRow.append(icon('serverUnavailable', 'status-icon'));
  statusRow.append(element('span', '', status.text));
  card.append(top, server, statusRow, account.sessionReplaced
    ? actionButton(t('text_sign_in_again_113'), () => navigate({ name: 'login', accountId: account.id }), 'primary profile-notification-button')
    : profileAccountActions(account));
  return card;
}

function profileAccountActions(account: Account): HTMLElement {
  const actions = element('div', 'profile-account-actions');
  actions.append(notificationButton(account));
  void loadProfilePasswordAction(account, actions);
  return actions;
}

async function loadProfilePasswordAction(account: Account, actions: HTMLElement): Promise<void> {
  const sessionId = account.sessionId;
  const token = account.token;
  const stillCurrent = () => actions.isConnected && list.includes(account) && !account.sessionReplaced
    && !removingAccounts.has(account) && !rotatingCredentials.has(account)
    && account.sessionId === sessionId && account.token === token;
  try {
    // Do not show a cached action while checking, or on servers without password auth.
    const health = await api<ServerHealth>(account, '/healthz');
    if (!stillCurrent() || !health.features?.includes('password_auth_v1') || !sessionId) return;
    const profile = await api<{ password_set?: unknown }>(account, '/api/me');
    if (!stillCurrent() || typeof profile.password_set !== 'boolean') return;
    account.passwordAuth = true;
    account.passwordSet = profile.password_set;
    await saveAccount(account);
    if (!stillCurrent()) return;
    actions.append(actionButton(profile.password_set ? t('text_change_password_326') : t('text_set_password_327'),
      () => changePasswordDialog(account), 'secondary profile-password-button'));
  } catch {
    // The account's existing connection status conveys connectivity problems.
    // Without a confirmed result, there is no password action to offer.
  }
}

function notificationButton(account: Account): HTMLButtonElement {
  const unsupported = pushSupport();
  const enabled = notifications.get(account.id) === true;
  const button = actionButton(enabled ? t('web_disable_notifications_31') : t('web_enable_notifications_32'), async () => {
    if (unsupported) {
      notice(unsupported);
      return;
    }
    if (enabled) {
      await disablePush(account, base);
      notifications.set(account.id, false);
      notice(t('web_notifications_disabled_33'));
    } else {
      await enablePush(account, base);
      notifications.set(account.id, await pushEnabled(account, base).catch(() => false));
      notice(notifications.get(account.id) ? t('web_notifications_enabled_34') : t('web_could_not_enable_notifications_35'));
    }
    renderApp();
  }, `profile-notification-button ${enabled ? 'secondary' : 'primary'}`);
  if (unsupported) {
    button.classList.remove('primary');
    button.classList.add('secondary');
    button.textContent = t('web_notifications_unavailable_36');
    button.title = unsupported;
  }
  return button;
}

function profileAccountStatus(account: Account): { kind: 'checking' | 'available' | 'unavailable'; text: string } {
  if (account.sessionReplaced) return { kind: 'unavailable', text: t('web_sign_in_again_37') };
  const status = states.get(account.id);
  if (!status || status === 'web_connecting_38') return { kind: 'checking', text: t('text_checking_325') };
  if (status === 'web_online_6') return { kind: 'available', text: t('text_server_available_119') };
  return { kind: 'unavailable', text: status === 'web_offline_7' ? t('text_server_unavailable_120') : t(status) };
}

async function preparePasswordAccount(account: Account): Promise<void> {
  if (!account.passwordAuth) {
    const details = await api<{ features?: string[] }>(account, '/healthz');
    if (!details.features?.includes('password_auth_v1')) return;
    account.passwordAuth = true;
    await saveAccount(account);
  }
  await refreshPasswordState(account);
}

async function confirmRemoveAccount(account: Account): Promise<void> {
  const replaced = account.sessionReplaced === true;
  const modal = dialog(replaced ? t('web_remove_account_from_the_list_39') : t('text_sign_out_of_this_account_321'));
  modal.body.append(element('p', '', replaced
    ? t('web_remove_value_from_the_accounts_on_this_device_40', account.login)
    : t('text_you_will_need_to_sign_in_again_to_receive_calls_322')));
  modal.actions.append(actionButton(t('text_cancel_12'), () => closeDialog(modal), 'secondary'));
  modal.actions.append(actionButton(replaced ? t('text_delete_239') : t('text_sign_out_323'), async () => {
    await closeDialog(modal, 'remove');
    await removeAccount(account);
  }, 'danger'));
}

async function removeAccount(account: Account): Promise<void> {
  if (current?.account.id === account.id) throw new Error(t('text_end_the_call_first_32'));
  if (removingAccounts.has(account)) return;
  if (!account.sessionReplaced) beginCredentialRotation(account);
  removingAccounts.add(account);
  try {
    if (!account.sessionReplaced) await logout(account);
    await disablePush(account, base).catch(() => undefined);
    connections.get(account.id)?.stop();
    connections.delete(account.id);
    await deleteAccount(account.id);
    favorites.removeAccount(account.id);
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
    endCredentialRotation(account, true);
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

function updateFavoriteButton(button: HTMLButtonElement, key: string): void {
  const starred = favorites.keys.includes(key);
  button.classList.toggle('selected', starred);
  button.setAttribute('aria-label', starred ? t('text_remove_from_favorites_225') : t('text_add_to_favorites_226'));
  button.setAttribute('aria-pressed', String(starred));
}

async function changePasswordDialog(account: Account): Promise<void> {
  if (current?.account.id === account.id) { notice(t('text_end_the_call_first_32')); return; }
  await preparePasswordAccount(account);
  if (!account.passwordAuth) throw new Error(t('web_update_the_server_to_sign_in_with_a_password_41'));
  if (account.passwordSet === undefined) throw new Error(t('web_could_not_check_whether_a_password_is_set_try_again_42'));
  const installing = account.passwordSet === false;
  const modal = dialog(installing ? t('text_set_password_327') : t('text_change_password_326'));
  const form = element('form', 'material-form password-form');
  form.noValidate = true;
  if (!installing) form.append(inputField(t('text_current_password_328'), 'current_password', 'password'));
  form.append(inputField(t('text_new_password_319'), 'new_password', 'password'),
    inputField(t('text_repeat_password_320'), 'confirm_password', 'password'));
  const hint = element('small', 'supporting-text', installing
    ? t('text_at_least_8_characters_we_recommend_combining_lowercase_and_upperc_318')
    : t('web_enter_your_current_password_the_new_password_must_have_at_least_8_43'));
  const error = element('p', 'form-error');
  error.hidden = true;
  form.append(hint, error);
  const cancel = actionButton(t('text_cancel_12'), () => closeDialog(modal), 'secondary');
  const submit = element('button', 'primary', t('text_save_245'));
  submit.type = 'button';
  let busy = false;
  let retryUntil = 0;
  let retryTimer: ReturnType<typeof setTimeout> | undefined;
  const update = () => {
    const data = new FormData(form);
    const currentReady = account.passwordSet !== true || Boolean(String(data.get('current_password')));
    submit.disabled = busy || Date.now() < retryUntil || !currentReady || !String(data.get('new_password')) || !String(data.get('confirm_password'));
  };
  form.addEventListener('input', update);
  submit.onclick = () => form.requestSubmit();
  form.onsubmit = event => {
    event.preventDefault();
    if (submit.disabled) return;
    let issued = false;
    void (async () => {
      busy = true;
      cancel.disabled = true;
      update();
      error.hidden = true;
      const data = new FormData(form);
      const next = String(data.get('new_password'));
      const confirmation = String(data.get('confirm_password'));
      const validation = personalPasswordError(next);
      if (validation) throw new Error(validation);
      if (next !== confirmation) throw new Error(t('text_passwords_do_not_match_310'));
      const enteredCurrent = data.get('current_password');
      const currentPassword = typeof enteredCurrent === 'string' && enteredCurrent ? enteredCurrent : account.token;
      beginCredentialRotation(account);
      const result = await changePassword(account.server, account.login, currentPassword, next);
      issued = true;
      account.token = result.token;
      account.passwordAuth = true;
      account.passwordSet = true;
      account.sessionId = '';
      if (account.pushConfigId) notifications.set(account.id, false);
      await saveAccount(account);
      await claim(account);
      await saveAccount(account);
      endCredentialRotation(account);
      connectAccount(account);
      await closeDialog(modal, 'remove');
      notice(t('web_password_changed_sign_in_again_on_other_devices_44'));
      if (account.pushConfigId) void restorePushRegistration(account);
    })().catch(err => {
      if (issued) {
        endCredentialRotation(account);
        void closeDialog(modal, 'remove');
        connectAccount(account);
        notice(t('web_password_saved_the_connection_will_resume_when_you_are_back_onlin_45'));
        return;
      }
      if (err instanceof OperationError) {
        endCredentialRotation(account);
        requireAccountLogin(account);
        void closeDialog(modal, 'remove');
        notice(t('web_no_response_from_the_server_try_signing_in_with_your_new_password_46'));
        return;
      }
      endCredentialRotation(account, true);
      error.hidden = false;
      error.textContent = err instanceof Error ? err.message : String(err);
      busy = false;
      cancel.disabled = false;
      if (err instanceof AuthError && err.code === 'password_retry_later' && err.retryAfterSeconds) {
        retryUntil = Date.now() + err.retryAfterSeconds * 1000;
        clearTimeout(retryTimer);
        const tick = () => {
          if (!form.isConnected) return;
          const remaining = Math.max(0, Math.ceil((retryUntil - Date.now()) / 1000));
          error.textContent = authErrorMessage('password_retry_later', remaining || 1);
          update();
          if (remaining > 0) retryTimer = setTimeout(tick, 250);
        };
        tick();
      } else update();
    });
  };
  modal.body.append(form);
  modal.actions.append(cancel, submit);
  update();
}

function contactScreen(accountId: string, login: string): HTMLElement {
  const contact = findContact(accountId, login);
  if (!contact) {
    const body = element('main', 'empty-state');
    body.append(element('h2', '', t('text_contact_unavailable_51')), element('p', '', t('web_refresh_your_contacts_47')));
    return appPage(body, { title: t('text_contact_224'), back: () => goBack({ name: 'home' }) });
  }
  const name = contactDisplayName(contact);
  const menu = contactMenu(contact);
  const star = actionButton('', () => {
    const key = accountKey(accountId, login);
    const position = favorites.keys.indexOf(key);
    favorites.set(key, position < 0);
    renderApp();
    if (position >= 0) notice(t('text_removed_from_favorites_299'), () => { favorites.set(key, true, position); renderApp(); });
  }, 'favorite-toggle');
  star.append(favoriteStar());
  updateFavoriteButton(star, accountKey(accountId, login));
  const actions = element('div', 'contact-top-actions');
  actions.append(star, menu);
  const body = element('main', 'contact-screen');
  body.append(avatar(name, contact.login, 'profile-avatar', photoForContact(contact)));
  body.append(element('h2', 'profile-name', name));
  body.append(element('p', 'profile-login', `${contact.login}${list.length > 1 ? `@${serverAddress(contact.account.server)}` : ''}`));
  const call = actionButton(contactActionLabel(contact), () => contactCall(contact), `primary call-wide ${!contact.can_call ? 'call-unavailable' : ''}`.trim(), 'call');
  call.disabled = Boolean(current && !samePeer(contact, current));
  body.append(call);
  body.append(element('h3', 'section-title', t('text_call_history_233')));
  const rows = contactHistory.get(accountKey(accountId, login));
  const key = accountKey(accountId, login);
  if (!rows) {
    if (!contactHistoryErrors.has(key)) body.append(loadingBlock());
    if (!loadingContactHistory.has(key) && !contactHistoryErrors.has(key)) void loadContactHistory(contact, true).catch(failure);
  } else if (!rows.length) {
    body.append(contactHistoryMessage(t('text_no_calls_with_this_contact_yet_236')));
  } else {
    body.append(historyRows(rows, false));
  }
  if (contactHistoryErrors.has(key)) {
    body.append(actionButton(t('web_could_not_load_history_retry_15'), () => loadContactHistory(contact, true, Boolean(rows && contactHistoryCursors.get(key))), 'text-action'));
  } else if (rows && loadingContactHistory.has(key)) body.append(loadingBlock());
  else if (rows && (contactHistoryCursors.get(key) ?? 0) > 0) body.append(historyMoreButton(() => loadContactHistory(contact, false, true)));
  const page = appPage(body, { title: '', back: () => goBack({ name: 'home' }), menu: actions });
  page.classList.add('collapsing-contact');
  const compact = element('div', 'compact-contact');
  compact.append(avatar(name, contact.login, 'compact-avatar', photoForContact(contact)), element('strong', '', name));
  page.querySelector('.top-bar h1')?.replaceWith(compact);
  return page;
}

function contactMenu(contact: AccountContact): HTMLElement {
  const holder = element('div', 'menu-holder');
  const closeMenu = () => holder.classList.remove('open');
  const opener = iconButton(t('text_actions_for_value_227', contactDisplayName(contact)), 'more', () => {
    if (holder.classList.contains('open')) {
      dismissActiveOverlay();
      return;
    }
    holder.classList.add('open');
    registerActiveOverlay(closeMenu);
  });
  const menu = element('div', 'popup-menu');
  menu.append(menuItem(t('text_rename_228'), 'edit', () => { closeActiveOverlay(false); renameDialog(contact); }));
  menu.append(menuItem(t('text_change_photo_229'), 'photoCamera', () => { closeActiveOverlay(false); photoSheet(contact); }));
  menu.append(menuItem(t('text_delete_contact_232'), 'delete', () => { closeActiveOverlay(false); deleteDialog(contact); }, 'danger-text'));
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
  panel.append(element('h2', '', t('text_contact_photo_214')));
  panel.append(actionButton(t('web_choose_48'), () => fileInput.click(), 'primary wide'));
  if (contactPhotosByKey.has(key)) {
    panel.append(actionButton(t('text_remove_photo_217'), async () => {
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
  if (!file.type.startsWith('image/')) throw new Error(t('web_choose_an_image_49'));
  const source = await readFileDataUrl(file);
  const image = await loadImage(source);
  if (!image.naturalWidth || !image.naturalHeight) throw new Error(t('text_could_not_open_the_image_220'));
  if (!sheet.isConnected) return;
  closeActiveOverlay(false);
  showPhotoEditor(contact, image);
}

function showPhotoEditor(contact: AccountContact, image: HTMLImageElement): void {
  const overlay = element('section', 'photo-editor-overlay');
  const title = element('h2', '', t('text_adjust_photo_218'));
  const stage = element('div', 'photo-editor-stage');
  const viewport = element('div', 'photo-crop-viewport');
  const preview = element('img');
  preview.alt = '';
  preview.draggable = false;
  preview.src = image.src;
  const ring = element('span', 'photo-crop-ring');
  const actions = element('div', 'photo-editor-actions');
  const cancel = actionButton(t('text_cancel_12'), () => closePhotoEditor(), 'photo-editor-cancel');
  const done = actionButton(t('text_done_219'), async () => {
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
        done.replaceChildren(document.createTextNode(t('text_done_219')));
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
  if (!context) throw new Error(t('web_could_not_prepare_the_photo_50'));
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
    reader.onerror = () => reject(reader.error ?? new Error(t('text_could_not_open_the_image_220')));
    reader.onload = () => resolve(String(reader.result ?? ''));
    reader.readAsDataURL(file);
  });
}

function loadImage(source: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error(t('text_could_not_open_the_image_220')));
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
  return audioOutputs.find(output => output.id === current)?.label ?? audioOutputs[0]?.label ?? t('text_device_191');
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
      if (!audioOutputs.length) audioOutputs = [{ id: 'default', label: t('web_default_device_51') }];
    })
    .finally(() => { audioOutputsLoading = undefined; });
  await audioOutputsLoading;
  if (render) renderCall();
}

function audioOutputLabel(device: MediaDeviceInfo, index: number): string {
  const raw = device.label.trim();
  const lower = raw.toLocaleLowerCase(currentLocale());
  if (device.deviceId === 'default') return raw || t('web_default_device_51');
  if (device.deviceId === 'communications') return raw || t('web_communication_device_52');
  if (lower.includes('bluetooth')) return raw || 'Bluetooth';
  if (lower.includes('headset') || lower.includes('headphone') || lower.includes('науш')) return raw || t('text_headphones_189');
  if (lower.includes('speaker') || lower.includes('динами')) return raw || t('text_speaker_188');
  if (lower.includes('earpiece') || lower.includes('phone') || lower.includes('телефон')) return raw || t('text_phone_187');
  return raw || t('web_device_value_56', index + 1);
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
      t('web_no_other_audio_devices_found_57'),
      t('web_the_browser_does_not_expose_separate_options_for_the_speaker_earp_58'),
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
  const output = audioOutputs.find(item => item.id === id) ?? { id, label: label || t('web_selected_device_59') };
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
    if (error.name === 'NotAllowedError' || error.name === 'SecurityError') return t('web_the_browser_did_not_allow_audio_device_selection_60');
    if (error.name === 'NotFoundError') return t('web_this_audio_device_is_no_longer_available_61');
    if (error.name === 'AbortError') return t('web_could_not_switch_audio_output_62');
  }
  return t('web_could_not_switch_audio_output_62');
}

function audioOutputUnsupportedDialog(): void {
  audioOutputUnavailableDialog(
    t('web_audio_switching_unavailable_63'),
    t('web_this_browser_does_not_let_web_apps_choose_the_speaker_or_earpiece_64'),
  );
}

function audioOutputUnavailableDialog(title: string, message: string): void {
  const modal = dialog(title);
  modal.body.append(element('p', '', message));
  modal.actions.append(actionButton(t('text_ok_61'), () => closeDialog(modal), 'primary'));
}

function audioOutputSheet(): void {
  if (!audioOutputSelectionSupported()) return;
  const overlay = element('div', 'sheet-overlay');
  const panel = element('section', 'bottom-sheet audio-output-sheet');
  panel.append(element('h2', '', t('text_audio_output_185')));
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
  if (current && samePeer(contact, current)) return t('text_return_to_call_206');
  if (current) return t('text_end_the_current_call_first_207');
  return t('text_call_208');
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
  const modal = dialog(t('text_cannot_call_yet_53'));
  modal.body.append(element('p', '', t('text_you_can_call_once_value_adds_you_to_their_contacts_54', contactDisplayName(contact))));
  modal.actions.append(actionButton(t('text_ok_61'), () => closeDialog(modal), 'primary'));
}

function addContactScreen(): HTMLElement {
  const body = element('main', 'form-page');
  const form = element('form', 'material-form');
  form.noValidate = true;
  const accountField = list.length > 1 ? selectField(t('text_server_106'), 'account', list.map(account => ({ value: account.id, label: `${serverAddress(account.server)} · ${account.login}` }))) : null;
  if (accountField) form.append(accountField);
  form.append(inputField(t('text_username_116'), 'login', 'text'));
  form.append(inputField(t('text_name_in_your_contacts_127'), 'name', 'text'));
  const error = element('p', 'form-error');
  error.hidden = true;
  const submit = element('button', 'primary wide', t('text_add_114'));
  submit.type = 'submit';
  form.append(error, submit);
  form.onsubmit = event => {
    event.preventDefault();
    void (async () => {
      const data = new FormData(form);
      const account = list.find(item => item.id === String(data.get('account') || list[0]?.id));
      const login = String(data.get('login')).trim();
      const name = String(data.get('name')).trim();
      if (!account || !login || !name) throw new Error(t('web_enter_a_username_and_contact_name_65'));
      if ([...name].length > 64) throw new Error(t('web_the_contact_name_must_be_no_longer_than_64_characters_66'));
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
  return appPage(body, { title: t('text_add_contact_125'), back: () => goBack({ name: 'home' }), className: 'form-app-page' });
}

function credentialsScreen(mode: 'login' | 'add-account', reauth?: Account): HTMLElement {
  const flow = element('div', 'credentials-flow');
  const page = element('section', `credential-screen ${mode}`);
  const form = element('form', 'material-form credentials');
  form.noValidate = true;
  const standalone = mode === 'login' && (!reauth || list.length === 1);
  let brand: HTMLElement | undefined;
  if (standalone) {
    brand = element('div', 'login-brand');
    brand.append(appMark('52px'));
    const title = element('div');
    title.append(element('h1', '', 'TiniTalk'), element('p', '', t('text_calls_for_your_circle_288')));
    brand.append(title);
    form.append(brand);
  }
  form.append(inputField(t('text_username_116'), 'login', 'text', true));
  form.append(inputField(t('text_password_117'), 'token', 'password', true));
  const server = inputField(t('text_server_address_118'), 'server', 'text', true, 'talk.example.com');
  const serverStatus = element('small', 'supporting-text', t('text_enter_a_server_address_281'));
  server.append(serverStatus);
  form.append(server);
  if (reauth?.sessionReplaced) {
    form.append(element('p', 'reauth-explanation', t('web_your_previous_session_ended_sign_in_again_to_receive_calls_here_67')));
  }
  const error = element('p', 'form-error');
  error.hidden = true;
  const submit = element('button', 'primary wide', mode === 'login' ? t('text_sign_in_115') : t('text_add_114'));
  submit.type = 'submit';
  let pendingSetup: PendingPasswordSetup | undefined;
  let retryUntil = 0;
  let retryTimer: ReturnType<typeof setTimeout> | undefined;
  const updateSubmit = () => {
    submit.disabled = Boolean(accountSubmission) || Date.now() < retryUntil || !credentialsReady(form, Boolean(pendingSetup));
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
    error.hidden = true;
    accountSubmission = (async () => {
      if (pendingSetup) {
        await submitPasswordSetup(form, mode, pendingSetup);
        return;
      }
      const setup = await submitAccount(form, mode);
      if (!setup || !form.isConnected) return;
      pendingSetup = setup;
      const loginFields = Array.from(form.childNodes);
      const loginHost = form.parentElement!;
      const loginView = flow.firstElementChild!;
      // Reuse the normal Back-stack handling, but show a full page instead of a
      // dialog. Credentials stay on detached DOM nodes, never in history state.
      const back = registerActiveOverlay(() => {
        pendingSetup = undefined;
        clearTimeout(retryTimer);
        retryUntil = 0;
        error.hidden = true;
        submit.textContent = mode === 'login' ? t('text_sign_in_115') : t('text_add_114');
        form.replaceChildren(...loginFields);
        loginHost.append(form);
        flow.replaceChildren(loginView);
        updateSubmit();
      });
      form.replaceChildren();
      form.append(element('p', 'reauth-explanation', t('text_at_least_8_characters_we_recommend_combining_lowercase_and_upperc_318')),
        inputField(t('text_new_password_319'), 'new_password', 'password'),
        inputField(t('text_repeat_password_320'), 'confirm_password', 'password'), error, submit);
      const body = element('main', 'form-page');
      body.append(form);
      flow.replaceChildren(appPage(body, {
        title: t('text_choose_a_password_317'), back: () => { if (!accountSubmission) back(); }, className: 'form-app-page',
      }));
      submit.textContent = t('text_sign_in_115');
      form.querySelector<HTMLInputElement>('input[name="new_password"]')?.focus();
    })().catch(err => {
      if (pendingSetup && err instanceof OperationError) {
        closeActiveOverlay();
        screen.dataset.viewKey = '';
        renderApp();
        notice(t('web_no_response_from_the_server_try_signing_in_with_your_new_password_68'));
        return;
      }
      showCredentialError(error, err, () => {
        retryUntil = 0;
        updateSubmit();
      }, seconds => {
        retryUntil = Date.now() + seconds * 1000;
        clearTimeout(retryTimer);
        const tick = () => {
          if (!form.isConnected) return;
          const remaining = Math.max(0, Math.ceil((retryUntil - Date.now()) / 1000));
          error.textContent = authErrorMessage('password_retry_later', remaining || 1);
          updateSubmit();
          if (remaining > 0) retryTimer = setTimeout(tick, 250);
        };
        tick();
      });
    }).finally(() => {
      accountSubmission = undefined;
      updateSubmit();
      // Navigation may have opened another credentials form while signing in.
      screen.querySelector<HTMLFormElement>('form.credentials')?.dispatchEvent(new Event('input'));
    });
  };
  if (standalone) {
    page.append(form, element('p', 'version', `v ${webCommit}`));
    flow.append(page);
  } else {
    const body = element('main', 'form-page');
    body.append(form);
    flow.append(appPage(body, { title: reauth ? t('text_sign_in_again_113') : t('text_add_account_112'), back: () => goBack({ name: 'profile' }), className: 'form-app-page' }));
  }
  const previousDispose = disposeView;
  disposeView = () => { previousDispose(); clearTimeout(retryTimer); };
  return flow;
}

function credentialsReady(form: HTMLFormElement, passwordSetup = false): boolean {
  if (passwordSetup) {
    return Boolean(form.querySelector<HTMLInputElement>('input[name="new_password"]')?.value)
      && Boolean(form.querySelector<HTMLInputElement>('input[name="confirm_password"]')?.value);
  }
  const login = form.querySelector<HTMLInputElement>('input[name="login"]')?.value.trim() ?? '';
  const token = form.querySelector<HTMLInputElement>('input[name="token"]')?.value ?? '';
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
  input.setAttribute('autocomplete', name === 'login' ? 'username'
    : name === 'new_password' || name === 'confirm_password' ? 'new-password'
      : name === 'token' || name === 'current_password' ? 'current-password' : 'url');
  input.autocapitalize = 'none';
  input.spellcheck = false;
  const field = inputFieldShell(label, input);
  if (paste) field.querySelector('.input-box')!.append(iconButton(t('text_paste_124'), 'paste', async () => pasteIntoField(input)));
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
  if (!navigator.clipboard?.readText) throw new Error(t('web_clipboard_unavailable_69'));
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
  setFormValue(form, input.name, input.name === 'token' ? value : value.trim());
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
      status.textContent = t('text_enter_a_server_address_281');
      status.dataset.state = 'neutral';
      return;
    }
    status.textContent = t('text_checking_connection_282');
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
    if (!response.ok) return { state: 'bad', message: t('text_no_tinitalk_server_at_this_address_284') };
    const health = await response.json() as { features?: string[] };
    if (!health.features?.includes('browser_v1')) return { state: 'warn', message: t('text_the_server_is_incompatible_with_this_app_version_18') };
    return { state: 'ok', message: t('text_tinitalk_server_available_283') };
  } catch {
    return { state: 'bad', message: t('text_server_unavailable_check_the_address_and_your_connection_285') };
  }
}

async function submitAccount(form: HTMLFormElement, mode: 'login' | 'add-account'): Promise<PendingPasswordSetup | undefined> {
  const data = new FormData(form);
  const server = normalizeServer(String(data.get('server')));
  const login = String(data.get('login')).trim();
  const password = String(data.get('token'));
  if (!login || !password) throw new Error(t('web_enter_your_username_and_password_70'));
  const previous = accountForLogin(list, server, login);
  const loginStillValid = () => !previous || (list.includes(previous) && !removingAccounts.has(previous));
  if (!loginStillValid()) return;
  // Request during the submit gesture, before network latency can expire it.
  const pushPermission = requestPushPermission();
  if (previous) beginCredentialRotation(previous);
  try {
    const authenticated = await authenticate(server, login, password);
    if (authenticated.passwordRequired) {
      endCredentialRotation(previous, true);
      return { server, login, temporaryPassword: password, previous };
    }
    await finishAccountLogin(form, mode, server, login, authenticated.token, authenticated.passwordAuth, previous, loginStillValid, pushPermission);
  } catch (error) {
    endCredentialRotation(previous, true);
    throw error;
  }
}

async function submitPasswordSetup(form: HTMLFormElement, mode: 'login' | 'add-account', setup: PendingPasswordSetup): Promise<void> {
  const data = new FormData(form);
  const password = String(data.get('new_password'));
  const confirmation = String(data.get('confirm_password'));
  const validation = personalPasswordError(password);
  if (validation) throw new Error(validation);
  if (password !== confirmation) throw new Error(t('text_passwords_do_not_match_310'));
  const loginStillValid = () => !setup.previous || (list.includes(setup.previous) && !removingAccounts.has(setup.previous));
  if (!loginStillValid()) return;
  const pushPermission = requestPushPermission();
  if (setup.previous) beginCredentialRotation(setup.previous);
  try {
    const result = await changePassword(setup.server, setup.login, setup.temporaryPassword, password);
    await finishAccountLogin(form, mode, setup.server, setup.login, result.token, true, setup.previous, loginStillValid, pushPermission, true);
  } catch (error) {
    endCredentialRotation(setup.previous, true);
    throw error;
  }
}

async function finishAccountLogin(form: HTMLFormElement, mode: 'login' | 'add-account', server: string, login: string,
  token: string, passwordAuth: boolean, previous: Account | undefined, loginStillValid: () => boolean,
  pushPermission: Promise<boolean>, passwordSet?: boolean): Promise<void> {
  const account: Account = { id: previous?.id ?? crypto.randomUUID(), server, login, token, name: previous?.name ?? login,
    deviceId: previous?.deviceId ?? crypto.randomUUID(), sessionId: '', pushConfigId: previous?.pushConfigId, passwordSet: previous?.passwordSet,
    ...(passwordAuth ? { passwordAuth: true as const } : {}) };
  let staged = false;
  const install = () => {
    if (previous) list.splice(list.indexOf(previous), 1, account);
    else list.push(account);
    staged = true;
    endCredentialRotation(previous);
  };
  const stillCurrent = () => staged ? list.includes(account) && !removingAccounts.has(account) : loginStillValid();
  try {
    await persistAndClaim(account, passwordAuth, stillCurrent, install);
  } catch (error) {
    if (!staged || !(error instanceof OperationError)) throw error;
    notice(t('web_sign_in_saved_the_connection_will_resume_when_you_are_back_online_71'));
  }
  if (!stillCurrent()) return;
  if (!staged) install();
  await refreshPasswordState(account, passwordSet);
  if (!stillCurrent()) return;
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
    notice(t('web_could_not_enable_notifications_try_enabling_them_in_your_profile_8'));
  }).finally(() => { if (route.name === 'profile') renderApp(); });
  await refreshAll(false);
  if (!previous && mode === 'add-account') notice(t('web_account_added_72'));
}

async function refreshPasswordState(account: Account, known?: boolean): Promise<void> {
  if (!account.passwordAuth || !account.sessionId) return;
  let passwordSet = known;
  if (passwordSet === undefined) {
    const result = await api<{ password_set?: unknown }>(account, '/api/me').catch(() => undefined);
    if (typeof result?.password_set !== 'boolean') return;
    passwordSet = result.password_set;
  }
  account.passwordSet = passwordSet;
  await saveAccount(account);
}

async function persistAndClaim(account: Account, persistBeforeClaim: boolean, loginStillValid: () => boolean, onPersisted: () => void = () => {}): Promise<void> {
  if (persistBeforeClaim) await saveAccount(account);
  if (!loginStillValid()) { if (persistBeforeClaim) await deleteAccount(account.id); return; }
  if (persistBeforeClaim) onPersisted();
  await claim(account);
  if (!loginStillValid()) { if (persistBeforeClaim) await deleteAccount(account.id); return; }
  await saveAccount(account);
  if (!loginStillValid()) await deleteAccount(account.id);
}

function showCredentialError(error: HTMLElement, value: unknown, onOrdinary: () => void, onRetry: (seconds: number) => void): void {
  error.hidden = false;
  error.textContent = value instanceof Error ? value.message : String(value);
  if (value instanceof AuthError && value.code === 'password_retry_later' && value.retryAfterSeconds) onRetry(value.retryAfterSeconds);
  else onOrdinary();
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
  const lines = value.split(/\r?\n/);
  while (lines.length && !lines[0].trim()) lines.shift();
  while (lines.length && !lines.at(-1)!.trim()) lines.pop();
  if (lines.length === 3) {
    const login = lines[0].trim();
    const server = lines[2].trim();
    if (login && server && !login.includes('@') && !/\s/.test(login) && !/\s/.test(server)) {
      try { normalizeServer(server); return [login, lines[1], server]; } catch { return null; }
    }
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
  if (account.passwordAuth && !account.sessionId) return Promise.resolve();
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
  const targets = list.filter(account => !account.sessionReplaced && !(account.passwordAuth && !account.sessionId));
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
  const modal = dialog(t('text_edit_name_242'));
  const input = element('input');
  input.value = contactDisplayName(contact);
  input.maxLength = 64;
  const error = element('p', 'form-error');
  error.hidden = true;
  const save = actionButton(t('text_save_245'), async () => {
    const name = input.value.trim();
    if (!name) throw new Error(t('text_enter_a_name_244'));
    await api<Contact>(contact.account, `/api/contacts/${encodeURIComponent(contact.login)}/name`, 'PUT', { custom_name: name });
    await closeDialog(modal, 'remove');
    await refreshContacts();
    replaceRoute({ name: 'contact', accountId: contact.account.id, login: contact.login });
  }, 'primary');
  modal.body.append(inputFieldShell(t('text_contact_name_243'), input), error);
  modal.actions.append(actionButton(t('text_cancel_12'), () => closeDialog(modal), 'secondary'), save);
}

function deleteDialog(contact: AccountContact): void {
  const modal = dialog(t('text_delete_contact_237'));
  modal.body.append(element('p', '', t('text_remove_value_from_your_contacts_238', contactDisplayName(contact))));
  modal.actions.append(actionButton(t('text_cancel_12'), () => closeDialog(modal), 'secondary'));
  modal.actions.append(actionButton(t('text_delete_239'), async () => {
    await api(contact.account, `/api/contacts/${encodeURIComponent(contact.login)}`, 'DELETE');
    favorites.removeContact(accountKey(contact.account.id, contact.login));
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
    const key = contactDisplayName(contact).toLocaleLowerCase(currentLocale());
    groups.set(key, [...(groups.get(key) ?? []), contact]);
  }
  const result = new Set<string>();
  for (const group of groups.values()) {
    if (new Set(group.map(contact => serverHost(contact.account.server).toLocaleLowerCase(currentLocale()))).size > 1) {
      for (const contact of group) result.add(accountKey(contact.account.id, contact.login));
    }
  }
  return result;
}

const noAnswerOutcomes = new Set(['unreachable', 'unanswered', 'cancelled_before_ringing', 'cancelled_after_ringing', 'interrupted_before_answer']);

function historyStatus(item: HistoryItem): string {
  if (item.outcome === 'completed') return t('text_call_value_253', historyDuration(item.duration_seconds));
  if (item.outcome === 'interrupted') return t('text_connection_lost_value_254', historyDuration(item.duration_seconds));
  if (noAnswerOutcomes.has(item.outcome)) {
    if (item.direction === 'incoming') return item.reached ? t('text_missed_255') : t('text_missed_offline_256');
    return item.reached ? t('text_unanswered_257') : t('text_unanswered_offline_258');
  }
  if (item.direction === 'incoming') {
    if (item.outcome === 'busy') return t('text_missed_you_were_busy_259');
    if (item.outcome === 'rejected') return item.reply_code ? replyStatus(item.reply_code, true) : t('text_you_declined_the_call_260');
    if (item.outcome === 'connection_failed') return t('text_connection_not_established_261');
    return t('text_call_ended_262');
  }
  if (item.outcome === 'busy') return t('text_busy_91');
  if (item.outcome === 'rejected') return item.reply_code ? replyStatus(item.reply_code, false) : t('text_call_declined_263');
  if (item.outcome === 'connection_failed') return t('text_connection_not_established_261');
  return t('text_call_ended_262');
}

function replyStatus(code: string, sent: boolean): string {
  const reply = callReplies().find(item => item.code === code);
  return reply ? (sent ? reply.sentHistory : reply.receivedHistory) : (sent ? t('text_you_declined_the_call_260') : t('text_call_declined_263'));
}

function replyResultText(code: unknown): string {
  return typeof code === 'string' ? callReplies().find(item => item.code === code)?.result ?? '' : '';
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
  if (day === today) return t('text_today_264');
  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1).getTime();
  if (day === yesterday) return t('text_yesterday_265');
  return date.toLocaleDateString(currentLocale(), { day: 'numeric', month: 'long', ...(date.getFullYear() === now.getFullYear() ? {} : { year: 'numeric' }) });
}

function historyTime(startedAt: number): string {
  return new Date(startedAt * 1000).toLocaleTimeString(currentLocale(), { hour: '2-digit', minute: '2-digit' });
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
  if (day === today) return t('text_missed_at_value_266', historyTime(startedAt));
  if (day === today - 86400_000) return t('text_missed_yesterday_267');
  return t('text_missed_value_268', date.toLocaleDateString(currentLocale()));
}

function createCall(account: Account, id: string, peer: string, peerLogin: string, incoming: boolean): ActiveCall {
  dismissEndedCall(false);
  const value: ActiveCall = {
    account, id, peer, peerLogin, incoming, started: incoming, accepted: false, seq: 0,
    status: incoming ? 'text_incoming_call_62' : 'text_trying_to_connect_5',
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
      if (status === 'web_in_call_73') markCallConnected(value);
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
  call.status = 'web_in_call_73';
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
    incomingLayout: call.incoming && !call.accepted,
    accountId: call.account.id,
    peer: call.peer || 'TiniTalk',
    peerLogin: call.peerLogin || call.peer,
    status: detail ? t('text_call_ended_93') : status,
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
  promoteWaiting();
  renderCall();
  void refreshAll(false).catch(() => undefined);
}

function callToneState(call: ActiveCall, endReason?: CallToneEndReason): CallToneState {
  const direction = call.incoming ? 'incoming' : 'outgoing';
  if (endReason) return { direction, phase: 'ended', connected: Boolean(call.connectedAt), endReason };
  if (call.connectedAt) return { direction, phase: 'active', connected: true, reconnecting: call.status === 'text_reconnecting_131' };
  if (call.accepted || call.answering) return { direction, phase: 'active', connected: false };
  if (!call.incoming && call.status === 'text_waiting_for_an_answer_4') return { direction, phase: 'ringing', connected: false };
  return { direction, phase: call.incoming ? 'ringing' : 'connecting', connected: false, expiresAt: call.incomingExpiresAt };
}

function inferredCallEndReason(call: ActiveCall, status: string): CallToneEndReason {
  if (call.connectedAt) return 'remote_hangup';
  if (status === t('text_busy_91')) return 'busy';
  if (status === t('text_call_declined_193')) return 'rejected';
  if (status === t('text_no_answer_194')) return 'timed_out';
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
  if (call.connectedAt) return t('text_call_ended_93');
  if (type === 'call.busy') return t('text_busy_91');
  if (type === 'call.reject') return call.incoming ? t('text_call_ended_93') : t('text_call_declined_193');
  if (type === 'call.expire') return call.incoming ? t('text_call_ended_93') : t('text_no_answer_194');
  return t('text_call_ended_93');
}

async function outgoing(account: Account, contact: Contact): Promise<void> {
  if (removingAccounts.has(account) || !list.includes(account)) throw new Error(t('web_disconnecting_account_74'));
  if (account.sessionReplaced) { navigate({ name: 'login', accountId: account.id }); return; }
  if (current || waitingCalls.selected) throw new Error(t('text_end_the_current_call_first_207'));
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
      finishCurrentCall(t('text_no_answer_194'), true, '', 'timed_out');
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
    call.status = 'text_connecting_130';
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
  finishCurrentCall(t('text_call_ended_93'), true, '', endReason ?? (call.connectedAt ? 'local_hangup' : 'cancelled'));
}

function rejectWithReply(code: CallReplyCode): void {
  const call = current;
  if (!call || !call.incoming || call.accepted) return;
  const connection = connections.get(call.account.id)!;
  connection.clearCall(call.id);
  connection.send(call.id, 'call.reject', { reply_code: code });
  finishCurrentCall(t('text_call_ended_93'), true, '', 'rejected');
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
  const button = element('button', 'primary call-audio-retry', t('web_enable_sound_75'));
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
  for (const pending of waitingCalls.entries.values()) {
    if (pending.account.sessionReplaced || !list.includes(pending.account) || !waitingCalls.has(pending)) {
      waitingCalls.remove(pending);
      waitingPromotions.delete(pending);
    }
    if (current?.connectedAt && !pending.waiting && !waitingPromotions.has(pending)) {
      waitingPromotions.add(pending);
      connections.get(pending.account.id)?.send(pending.event.call_id, 'call.waiting', {waiting: true});
    }
  }
  waitingTone.update(current?.connectedAt && !waitingCalls.selected
    ? Math.max(0, ...[...waitingCalls.entries.values()].filter(item => item.confirmed).map(item => item.deadline)) : 0,
    currentAudioOutputId());
  callLayer.hidden = !current && !endedCall && !waitingCalls.entries.size;
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
  if (waitingCalls.entries.size) callContent.append(waitingCallsPanel());
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
    view.append(element('p', 'call-detail', t(call.status)));
  }
  const actions = element('div', `call-actions ${incomingPending ? 'incoming-actions' : ''}`);
  if (incomingPending) {
    actions.append(incomingCallAction(t('text_answer_64'), 'answer', accept));
    actions.append(incomingCallAction(t('text_decline_63'), 'end', hangup, true));
    view.append(element('span', 'call-spacer'), actions, incomingReplySheet());
  } else {
    if (!call.incoming && !call.accepted) {
      actions.append(roundCallAction(t('text_camera_175'), 'videoCamera', 'disabled', () => undefined, true));
    } else {
      const cameraDisabled = !call.video.allowed;
      actions.append(roundCallAction(t('text_camera_175'), 'videoCamera', call.video.requested ? 'camera-active' : cameraDisabled ? 'disabled' : 'neutral', () => toggleCamera(call), cameraDisabled));
    }
    if (audioOutputSelectionSupported()) actions.append(roundCallAction(t('text_audio_181'), 'volume', 'neutral', toggleAudioOutput));
    actions.append(roundCallAction(t('text_microphone_178'), microphoneControlIcon(call.muted), call.muted ? 'active' : 'neutral', () => {
      call.muted = !call.muted;
      call.media.mute(call.muted);
      renderCall();
    }));
    actions.append(roundCallAction(call.accepted ? t('text_end_call_98') : t('text_undo_196'), 'call', 'end rotated', hangup));
    if (call.video.failure) view.append(element('p', 'call-video-warning', t('text_could_not_turn_on_the_camera_172')));
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
  if (call.video.failure && !call.video.sending) controls.append(element('p', 'call-video-warning', t('text_could_not_turn_on_the_camera_172')));
  const actions = element('div', 'call-actions video-actions');
  if (call.video.sending && call.video.canSwitchCamera) {
    actions.append(roundCallAction(t('text_rotate_173'), 'switchCamera', 'neutral', () => {
      restartVideoControlsAutoHide(call);
      return switchCamera(call);
    }));
  }
  actions.append(roundCallAction(t('text_camera_175'), 'videoCamera', call.video.requested ? 'camera-active' : 'neutral', () => {
    restartVideoControlsAutoHide(call);
    return toggleCamera(call);
  }));
  if (audioOutputSelectionSupported()) {
    actions.append(roundCallAction(t('text_audio_181'), 'volume', 'neutral', () => {
      restartVideoControlsAutoHide(call);
      return toggleAudioOutput();
    }));
  }
  actions.append(roundCallAction(t('text_microphone_178'), microphoneControlIcon(call.muted), call.muted ? 'active' : 'neutral', () => {
    restartVideoControlsAutoHide(call);
    call.muted = !call.muted;
    call.media.mute(call.muted);
    renderCall();
  }));
  actions.append(roundCallAction(t('text_end_call_98'), 'call', 'end rotated', () => {
    restartVideoControlsAutoHide(call);
    hangup();
  }));
  if (audioOutputSelectionSupported()) controls.append(element('p', 'video-route-label', t('text_audio_value_166', currentAudioOutputLabel())));
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
  const view = element('div', `call-screen ended-call-screen${call.incomingLayout ? ' ended-incoming-call-screen' : ''}`);
  view.append(element('p', 'call-status', call.status));
  view.append(avatar(call.peer || 'TiniTalk', call.peerLogin || call.peer, 'call-avatar', photoForAccountPeer(call.accountId, call.peerLogin)));
  view.append(element('h2', 'call-name', call.peer || 'TiniTalk'));
  if (call.detail) view.append(element('p', 'call-detail', call.detail));
  if (call.explanation) view.append(element('p', 'call-ended-explanation', call.explanation));
  view.append(element('span', 'call-spacer'), element('span', 'call-ended-footer'));
  return view;
}

function callStatusText(call: ActiveCall): string {
  if (call.connectedAt && call.status === 'web_in_call_73') return t('text_in_a_call_133');
  return call.accepted ? t(call.status) : call.incoming ? t('text_incoming_call_62') : t(call.status);
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
  box.ariaLabel = route === 'turn' ? t('text_connection_via_turn_168') : t('text_direct_connection_167');
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
    panel.ariaLabel = t('web_security_code_76') + emoji;
  } else if (security.state === 'failed') {
    panel.textContent = t('text_connection_is_not_secure_142');
  } else if (security.state === 'unavailable') {
    panel.textContent = t('text_cannot_verify_connection_security_140');
  } else {
    panel.textContent = t('text_checking_connection_security_141');
  }
  panel.onclick = () => securityDialog(security);
  return panel;
}

function securityDialog(security: CallSecurityState): void {
  const modal = dialog(securityDetailsTitle(security));
  modal.body.append(element('p', '', securityDetailsText(security)));
  modal.actions.append(actionButton(t('text_ok_61'), () => closeDialog(modal), 'primary'));
}

function securityDetailsTitle(security: CallSecurityState): string {
  if (security.state === 'ready') return t('text_security_code_146');
  if (security.state === 'unavailable') return t('text_cannot_verify_security_147');
  if (security.state === 'failed') return t('text_connection_is_not_secure_142');
  return t('text_checking_connection_security_145');
}

function securityDetailsText(security: CallSecurityState): string {
  if (security.state === 'ready') {
    return t('web_compare_all_5_emoji_with_the_other_person_if_they_match_the_conne_77');
  }
  if (security.state === 'unavailable') {
    return securityUnavailableText(security.reason);
  }
  if (security.state === 'failed') {
    return securityFailureText(security.reason) + t('text_n_nend_the_call_and_do_not_share_confidential_information_153');
  }
  return t('text_the_phones_exchange_temporary_keys_and_verify_webrtc_certificates_148');
}

function securityUnavailableText(reason: CallSecurityUnavailableReason): string {
  if (reason === 'server_unsupported') return t('text_the_tinitalk_server_is_out_of_date_the_app_cannot_verify_the_secu_151');
  return t('text_the_other_person_s_app_is_out_of_date_the_security_of_this_call_c_152');
}

function securityFailureText(reason: CallSecurityFailureReason): string {
  switch (reason) {
    case 'exchange_timeout': return t('text_the_security_check_timed_out_the_call_is_not_secure_154');
    case 'transport_timeout': return t('text_a_secure_connection_was_not_established_in_time_the_call_is_not_s_155');
    case 'transport_failed': return t('text_could_not_establish_a_secure_connection_the_call_is_not_secure_156');
    case 'unexpected_message': return t('text_verification_data_arrived_out_of_order_or_was_corrupted_the_call__157');
    case 'invalid_fingerprint': return t('text_the_connection_certificate_is_invalid_the_call_is_not_secure_158');
    case 'fingerprint_mismatch': return t('text_the_connection_certificate_does_not_match_the_verification_data_t_159');
    case 'commitment_mismatch': return t('text_verification_data_changed_after_the_call_started_the_call_is_not__160');
    case 'fingerprint_changed': return t('text_the_connection_certificate_changed_during_the_call_the_call_is_no_161');
    case 'invalid_public_key': return t('text_an_invalid_security_key_was_received_the_call_is_not_secure_162');
    case 'internal_error': return t('text_the_security_check_failed_the_call_is_not_secure_163');
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
  const sheet = element('div', 'incoming-reply-sheet initializing');
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
  scrim.setAttribute('aria-label', t('call_reply_close'));
  scrim.onclick = close;
  handle.type = 'button';
  handle.append(element('span', 'incoming-reply-grip'), element('span', '', t('call_reply_sheet_title')));
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

  for (const [index, reply] of callReplies().entries()) {
    const row = element('button', 'incoming-reply-option', reply.text);
    row.type = 'button';
    row.onclick = () => rejectWithReply(reply.code);
    listBox.append(row);
    if (index < callReplies().length - 1) listBox.append(element('span', 'incoming-reply-divider'));
  }

  panel.append(listBox);
  sheet.append(handle, panel);
  frame.append(sheet);
  layer.append(scrim, frame);
  // Reserve the actual translated handle height before the first paint.
  const reservation = element('div', 'incoming-reply-reservation');
  reservation.setAttribute('aria-hidden', 'true');
  reservation.setAttribute('inert', '');
  reservation.append(handle.cloneNode(true));
  shell.append(reservation, layer);
  requestAnimationFrame(() => {
    setOffset(openState ? 0 : measurePanel());
    sheet.getBoundingClientRect();
    sheet.classList.remove('initializing');
  });
  return shell;
}

function removeWaiting(invite: WaitingInvite, terminal = true): void {
  waitingCalls.remove(invite, terminal);
  waitingPromotions.delete(invite);
  void closeCallNotification(invite.account.id, invite.event.call_id, base).catch(() => undefined);
  renderCall();
}

function rejectWaiting(invite: WaitingInvite, seen = true): void {
  if (!waitingCalls.has(invite) || waitingCalls.selected === invite) return;
  connections.get(invite.account.id)?.send(invite.event.call_id, 'call.reject', {reason: 'busy', ...(seen ? {seen: true} : {})});
  removeWaiting(invite);
  promoteWaiting();
}

function tickWaiting(): void {
  clearTimeout(waitingTimer);
  for (const pending of waitingCalls.entries.values()) {
    if (pending !== waitingCalls.selected && pending.deadline <= Date.now()) rejectWaiting(pending, false);
  }
  promoteWaiting();
  if (waitingCalls.entries.size) waitingTimer = setTimeout(tickWaiting, 250);
}

function promoteWaiting(): void {
  if (current || waitingCalls.selected) return;
  for (const pending of waitingCalls.entries.values()) {
    if (pending.deadline <= Date.now()) continue;
    if (!pending.waiting) {
      removeWaiting(pending, false);
      dismissEndedCall(false);
      void receive(pending.account, pending.event);
      return;
    }
    if (pending.confirmed && !waitingPromotions.has(pending)) {
      waitingPromotions.add(pending);
      connections.get(pending.account.id)?.send(pending.event.call_id, 'call.waiting', {waiting: false});
    }
  }
}

function receiveWaiting(account: Account, event: SignalEvent): boolean {
  if (event.type === 'call.incoming' && waitingCalls.isDismissed(account, event.call_id)) return true;
  const pending = waitingCalls.get(account.id, event.call_id);
  if (pending) {
    if (event.seq && event.seq <= pending.seq && event.type !== 'call.incoming') return true;
    if (event.type === 'call.waiting') {
      waitingCalls.acknowledge(pending, event);
      waitingPromotions.delete(pending);
      promoteWaiting();
    } else if (['call.end', 'call.cancel', 'call.reject', 'call.expire'].includes(event.type)) {
      removeWaiting(pending);
      if (current?.account.id === account.id && current.id === event.call_id) return false;
      promoteWaiting();
      void refreshAll(false).catch(() => undefined);
    } else if (event.type.startsWith('rtc.') && waitingCalls.selected === pending) {
      if (pending.buffered.length < 256) pending.buffered.push(event);
    }
    renderCall();
    return true;
  }
  if (event.type !== 'call.incoming' || (!current && !waitingCalls.selected) ||
      (current?.account.id === account.id && current.id === event.call_id)) return false;
  if (event.payload.call_waiting_supported !== true || (!current?.connectedAt && !waitingCalls.selected)) {
    connections.get(account.id)?.send(event.call_id, 'call.reject', {reason: 'busy'});
    return true;
  }
  const login = String(event.payload.caller_login || '');
  const contact = contactsByAccount.get(account.id)?.find(item => item.login === login);
  const invite = waitingCalls.add(account, event, contact ? contactDisplayName(contact) : login);
  if (!invite) connections.get(account.id)?.send(event.call_id, 'call.reject', {reason: 'busy'});
  else {
    connections.get(account.id)?.send(event.call_id, 'call.waiting', {waiting: true});
    tickWaiting();
    renderCall();
  }
  return true;
}

async function answerWaiting(invite: WaitingInvite): Promise<void> {
  if (!waitingCalls.select(invite)) return;
  waitingTone.stop();
  renderCall();
  const previous = current;
  let accepted = false;
  const switchTimeout = setTimeout(() => {
    if (waitingCalls.selected !== invite) return;
    connections.get(invite.account.id)?.send(invite.event.call_id, 'call.end');
    if (current?.account.id === invite.account.id && current.id === invite.event.call_id) endLocal(true);
    removeWaiting(invite);
    notice(t('waiting_call_unavailable'));
  }, 12_000);
  try {
    const connection = connections.get(invite.account.id);
    if (!connection) throw new Error('Connection removed');
    const replaceOnServer = previous?.account.id === invite.account.id && previous.accepted;
    if (!replaceOnServer && previous) {
      connections.get(previous.account.id)?.send(previous.id, previous.accepted ? 'call.end' : 'call.reject');
      closeActiveCall(true);
    }
    await connection.sendConfirmed(invite.event.call_id, 'call.accept', {
      supports_video: true, supports_call_sas: true,
      ...(replaceOnServer ? {replace_call_id: previous.id} : {}),
    });
    accepted = true;
    if (!waitingCalls.has(invite) || waitingCalls.selected !== invite) {
      connection.send(invite.event.call_id, 'call.end');
      return;
    }
    if (current === previous) closeActiveCall(true);
    if (current) throw new Error('Call changed during acceptance');
    dismissEndedCall(false);
    const next = createCall(invite.account, invite.event.call_id, invite.peer, String(invite.event.payload.caller_login || ''), true);
    next.accepted = true;
    next.answering = true;
    next.status = 'text_connecting_130';
    next.seq = invite.seq;
    current = next;
    // Previous media is closed before acquiring another microphone.
    await next.media.capture();
    if (current !== next || !waitingCalls.has(invite)) {
      next.media.close();
      if (current === next) finishCurrentCall(t('text_call_ended_93'), true);
      return;
    }
    next.answering = false;
    removeWaiting(invite);
    for (const buffered of invite.buffered) await receive(invite.account, buffered);
    connection.send(next.id, 'call.resume', {last_seq: next.seq});
    renderCall();
  } catch {
    // A lost ACK may hide a successful acceptance. End it explicitly, never retry
    // the switch against whichever call happens to be current now.
    connections.get(invite.account.id)?.send(invite.event.call_id, 'call.end');
    if (accepted && current?.account.id === invite.account.id && current.id === invite.event.call_id) endLocal(true);
    removeWaiting(invite);
    notice(t('waiting_call_unavailable'));
  } finally {
    clearTimeout(switchTimeout);
    if (waitingCalls.selected === invite) waitingCalls.selected = undefined;
    promoteWaiting();
    renderCall();
  }
}

function waitingCallsPanel(): HTMLElement {
  const panel = element('section', 'waiting-calls-panel');
  panel.setAttribute('aria-label', t('waiting_calls_title'));
  panel.append(element('h3', '', t('waiting_calls_title')),
    element('p', 'waiting-calls-hint', t('waiting_answer_ends_current')));
  for (const pending of waitingCalls.entries.values()) {
    const row = element('div', 'waiting-call-row');
    const identity = element('div', 'waiting-call-identity');
    const login = String(pending.event.payload.caller_login || '');
    identity.append(avatar(pending.peer, login, 'waiting-call-avatar', photoForAccountPeer(pending.account.id, login)),
      element('span', 'waiting-call-name', pending.peer));
    const actions = element('div', 'waiting-call-actions');
    const reject = element('button', 'secondary waiting-call-reject', t('text_busy_91'));
    const answer = element('button', 'primary waiting-call-answer', t('text_answer_64'));
    reject.disabled = Boolean(waitingCalls.selected);
    answer.disabled = Boolean(waitingCalls.selected) || !pending.confirmed;
    reject.onclick = () => rejectWaiting(pending);
    answer.onclick = () => { void answerWaiting(pending); };
    actions.append(reject, answer);
    row.append(identity, actions);
    panel.append(row);
  }
  return panel;
}

async function receive(account: Account, event: SignalEvent): Promise<void> {
  if (account.sessionReplaced || removingAccounts.has(account) || !list.includes(account)) return;
  if (event.type === 'contact.changed') {
    // HTTP must not block the socket's call event queue.
    void refreshAccountContacts(account).then(() => renderApp()).catch(() => undefined);
    return;
  }
  if (receiveWaiting(account, event)) return;
  if (event.type === 'call.incoming' && !current) {
    if (Date.now() > event.sent_at + 45000) return;
    const login = String(event.payload.caller_login || '');
    const contact = contactsByAccount.get(account.id)?.find(item => item.login === login);
    current = createCall(account, event.call_id, contact ? contactDisplayName(contact) : login || t('text_incoming_call_62'), login, true);
    const call = current;
    call.incomingExpiresAt = event.sent_at + 45000;
    call.expiry = setTimeout(() => {
      if (current !== call || call.accepted) return;
      connections.get(account.id)?.clearCall(call.id);
      finishCurrentCall(t('text_call_ended_93'), true, '', 'timed_out');
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
    connections.get(account.id)!.send(event.call_id, 'call.reject', {reason: 'busy'});
    return;
  }
  const call = current;
  if (!call || call.account.id !== account.id || call.id !== event.call_id) return;
  if (event.seq && event.seq <= call.seq) return;
  if (event.seq) call.seq = event.seq;
  if (['call.end', 'call.cancel', 'call.reject', 'call.expire', 'call.busy'].includes(event.type)) {
    connections.get(account.id)!.clearCall(call.id);
    const explanation = event.type === 'call.reject' && !call.incoming ? replyResultText(event.payload.reply_code) : '';
    const busy = event.type === 'call.reject' && event.payload.reason === 'busy';
    finishCurrentCall(busy ? t('text_busy_91') : terminalStatus(call, event.type), true, explanation, busy ? 'busy' : terminalToneReason(call, event.type));
    return;
  }
  if (event.type === 'call.accept') {
    call.accepted = true;
    clearTimeout(call.expiry);
    call.status = 'text_connecting_130';
    renderCall();
  }
  if (event.type === 'call.connected') {
    markCallConnected(call);
    renderCall();
  }
  if (event.type === 'call.ringing') {
    call.status = 'text_waiting_for_an_answer_4';
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
  if (current && !matchesIncoming()) {
    connections.get(accountId)?.send(callId, 'call.resume', {last_seq: 0});
    return;
  }
  const state = await notificationCallState(account, callId);
  if (state === 'ended') {
    if (matchesIncoming()) finishCurrentCall(t('text_call_ended_93'), false, '', 'cancelled');
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
    notice(t('web_this_account_has_been_removed_78'));
    return;
  }
  if (account.sessionReplaced) { navigate({ name: 'login', accountId: account.id }); return; }
  replaceRoute({ name: 'home' });
  if (current) {
    if (current.account.id !== accountId || current.id !== callId) {
      const waiting = waitingCalls.get(accountId, callId);
      if (waiting) {
        const requested = takePendingNotificationAction(accountId, callId) ?? action;
        if (requested === 'answer') void answerWaiting(waiting);
        else if (requested === 'reject') rejectWaiting(waiting);
        renderCall();
        return;
      }
      notice(t('text_end_the_current_call_first_207'));
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
    notice(t('web_the_call_has_already_ended_or_was_answered_on_another_device_79'));
    return;
  }
  // A declarative fallback can be opened without a local inbox record, and an
  // Apple notification can outlive the ringing call. Verify it with its server.
  if (!await isActiveNotificationCall(account, callId)) {
    notice(t('web_the_call_has_already_ended_or_was_answered_on_another_device_79'));
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
    // Pending sessions restore push in resumeAccountActivation, after claim.
    if (account.passwordAuth && !account.sessionId) continue;
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
  window.addEventListener('online', () => { for (const account of list) connectAndResume(account); syncNotificationLanguages(); });
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
      syncNotificationLanguages();
      if (route.name === 'about' && !updatingApp) void checkAppUpdates();
      for (const account of list) connectAndResume(account);
      if (list.length) void refreshAll(false).catch(() => undefined);
    }
  });
  window.addEventListener('beforeunload', event => { if (current) { event.preventDefault(); event.returnValue = ''; } });

  if (navigator.locks) {
    void navigator.locks.request('tinitalk-pwa-client', { ifAvailable: true }, async lock => {
      if (!lock) {
        notice(t('web_tinitalk_is_already_open_in_another_window_use_it_or_close_it_and_80'));
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

function languageButton(): HTMLElement {
  const selected = languages.find(language => language.tag === selectedLanguage());
  const button = actionButton('', showLanguagePicker, 'language-setting');
  const current = element('span', 'language-current', selected?.name || systemLanguageLabel());
  button.append(element('span', 'language-label', t('language_title')), current);
  return button;
}

function systemLanguageLabel(): string {
  return translate(resolveLanguage(browserLanguages().slice(0, 1)), 'language_system');
}

function showLanguagePicker(): void {
  const modal = dialog(t('language_title'));
  modal.body.classList.add('language-options');
  modal.body.setAttribute('role', 'radiogroup');
  modal.body.setAttribute('aria-label', t('language_title'));
  const collator = new Intl.Collator(browserLanguages()[0] || 'en');
  const choices = [{ tag: '' as const, name: systemLanguageLabel(), flag: 'system' },
    ...[...languages].sort((a, b) => collator.compare(a.name, b.name))];
  for (const choice of choices) {
    const active = choice.tag === selectedLanguage();
    const button = actionButton('', async () => {
      await closeDialog(modal, 'remove');
      await selectLanguage(choice.tag);
    }, `language-option${active ? ' selected' : ''}`);
    button.setAttribute('role', 'radio');
    button.setAttribute('aria-checked', String(active));
    const flag = element('span', `language-flag flag-${choice.flag}`);
    flag.setAttribute('aria-hidden', 'true');
    if (!choice.tag) flag.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M7 2H17Q19 2 19 4V20Q19 22 17 22H7Q5 22 5 20V4Q5 2 7 2ZM10 18H14"/></svg>';
    const name = element('span', 'language-name', choice.name);
    name.lang = choice.tag || resolveLanguage(browserLanguages().slice(0, 1));
    const check = element('span', 'language-check');
    if (active) check.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12L10 17L19 7"/></svg>';
    check.setAttribute('aria-hidden', 'true');
    button.append(flag, name, check);
    modal.body.append(button);
  }
  modal.actions.append(actionButton(t('text_cancel_12'), modal.close, 'text-action'));
}

let displayLanguage = currentLanguage();
function syncNotificationLanguages(): void {
  for (const account of list) {
    if (removingAccounts.has(account) || rotatingCredentials.has(account)) continue;
    // Retry after reconnect/foreground; do not interrupt calls with background errors.
    void syncPushLanguage(account, base).catch(() => undefined);
  }
}
let appStarted = false;
onLanguageChange(() => {
  if (!appStarted) return;
  syncNotificationLanguages();
  if (endedCall) {
    endedCall.status = relocalize(endedCall.status, displayLanguage);
    endedCall.detail = relocalize(endedCall.detail, displayLanguage);
    endedCall.explanation = relocalize(endedCall.explanation, displayLanguage);
  }
  displayLanguage = currentLanguage();
  delete screen.dataset.viewKey;
  renderApp();
});
if (!showInstallationScreen(root, base)) { appStarted = true; startAppClient(); }
