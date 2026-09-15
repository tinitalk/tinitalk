export type CallSecurityUnavailableReason = 'server_unsupported' | 'peer_unsupported';
export type CallSecurityFailureReason =
  | 'exchange_timeout'
  | 'transport_timeout'
  | 'transport_failed'
  | 'unexpected_message'
  | 'invalid_fingerprint'
  | 'fingerprint_mismatch'
  | 'commitment_mismatch'
  | 'fingerprint_changed'
  | 'invalid_public_key'
  | 'internal_error';

export type CallSecurityState =
  | { state: 'establishing' }
  | { state: 'ready'; code: string }
  | { state: 'unavailable'; reason: CallSecurityUnavailableReason }
  | { state: 'failed'; reason: CallSecurityFailureReason };

export type CallSASRole = 'caller' | 'callee';

type SignalPayload = Record<string, unknown>;
type SendSAS = (type: string, payload: SignalPayload) => void;
type PublishSecurity = (state: CallSecurityState) => void;

const keySize = 32;
const p = (1n << 255n) - 19n;
const a24 = 121665n;
const codeModulus = 10n ** 12n;
const commitmentDomain = 'tinitalk-call-sas-v1/commit';
const saltDomain = 'tinitalk-call-sas-v1/salt';
const codeDomain = 'tinitalk-call-sas-v1/code';

class CallSecurityException extends Error {
  constructor(readonly reason: CallSecurityFailureReason) { super(reason); }
}

export class EphemeralCallKey {
  readonly publicKey: Uint8Array;
  private privateKey?: Uint8Array;

  constructor(privateKey = randomBytes(keySize)) {
    if (privateKey.length !== keySize) throw new Error('X25519 private key must be 32 bytes');
    this.privateKey = new Uint8Array(privateKey);
    this.publicKey = x25519(this.privateKey, basePoint());
  }

  sharedSecret(peerPublicKey: Uint8Array): Uint8Array {
    if (!this.privateKey) throw new Error('X25519 private key was destroyed');
    if (peerPublicKey.length !== keySize) throw new Error('X25519 public key must be 32 bytes');
    return x25519(this.privateKey, peerPublicKey);
  }

  close(): void {
    this.privateKey?.fill(0);
    this.privateKey = undefined;
  }
}

export class CallSASHandshake {
  private localFingerprint?: Uint8Array;
  private remoteFingerprint?: Uint8Array;
  private receivedCommitment?: Uint8Array;
  private peerPublicKey?: Uint8Array;
  private peerAdvertisedFingerprint?: Uint8Array;
  private sentCommitment = false;
  private sentKey = false;
  private sentReveal = false;
  private transportConnected = false;
  private code?: string;
  private failed = false;
  private closed = false;
  private queue = Promise.resolve();

  constructor(
    private readonly callId: string,
    private readonly caller: string,
    private readonly callee: string,
    private readonly role: CallSASRole,
    private readonly send: SendSAS,
    private readonly publish: PublishSecurity,
    private readonly key: EphemeralCallKey = new EphemeralCallKey(),
  ) {
    this.publish({ state: 'establishing' });
  }

  recordLocalSdp(sdp: string): Promise<void> {
    return this.enqueue(async () => {
      this.recordFingerprint(true, this.parseFingerprint(sdp));
      if (this.role === 'caller') await this.maybeSendCommitment();
      else await this.maybeSendKey();
    });
  }

  recordRemoteSdp(sdp: string): Promise<void> {
    return this.enqueue(async () => {
      this.recordFingerprint(false, this.parseFingerprint(sdp));
      if (this.role === 'caller') await this.maybeCompleteCaller();
      else await this.maybeCompleteCallee();
    });
  }

  onCommitment(payload: SignalPayload): Promise<void> {
    return this.enqueue(async () => {
      securityRequire(this.role === 'callee' && !this.receivedCommitment, 'unexpected_message');
      this.receivedCommitment = decodePayloadValue(payload, 'commitment', 'unexpected_message');
      await this.maybeSendKey();
    });
  }

  onKey(payload: SignalPayload): Promise<void> {
    return this.enqueue(async () => {
      securityRequire(this.role === 'caller' && !this.peerPublicKey, 'unexpected_message');
      this.peerPublicKey = decodePayloadValue(payload, 'public_key', 'invalid_public_key');
      this.peerAdvertisedFingerprint = decodePayloadFingerprint(payload);
      await this.maybeCompleteCaller();
    });
  }

  onReveal(payload: SignalPayload): Promise<void> {
    return this.enqueue(async () => {
      securityRequire(this.role === 'callee' && !this.peerPublicKey, 'unexpected_message');
      this.peerPublicKey = decodePayloadValue(payload, 'public_key', 'invalid_public_key');
      this.peerAdvertisedFingerprint = decodePayloadFingerprint(payload);
      await this.maybeCompleteCallee();
    });
  }

  onTransportConnected(): void {
    if (this.failed || this.closed) return;
    this.transportConnected = true;
    this.publishCodeIfReady();
  }

  onTransportUnavailable(): void {
    if (this.failed || this.closed) return;
    this.transportConnected = false;
    if (this.code) this.publish({ state: 'establishing' });
  }

  reject(reason: CallSecurityFailureReason): void {
    this.fail(reason);
  }

  timeOut(): void {
    if (this.code) {
      if (!this.transportConnected) this.fail('transport_timeout');
    } else {
      this.fail('exchange_timeout');
    }
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    this.clearKeyMaterial();
  }

  private enqueue(action: () => Promise<void> | void): Promise<void> {
    const next = this.queue.then(async () => {
      if (this.failed || this.closed) return;
      try {
        await action();
      } catch (error) {
        this.fail(error instanceof CallSecurityException ? error.reason : 'internal_error');
      }
    });
    this.queue = next.catch(() => undefined);
    return next;
  }

  private async maybeSendCommitment(): Promise<void> {
    if (this.sentCommitment) return;
    const fingerprint = this.localFingerprint;
    if (!fingerprint) return;
    this.sentCommitment = true;
    const commitmentBytes = await commitment(this.callId, this.key.publicKey, fingerprint);
    try {
      this.send('rtc.sas.commit', { commitment: encodeBase64Url(commitmentBytes) });
    } finally {
      commitmentBytes.fill(0);
    }
  }

  private async maybeSendKey(): Promise<void> {
    if (this.sentKey || !this.receivedCommitment) return;
    const fingerprint = this.localFingerprint;
    if (!fingerprint) return;
    this.sentKey = true;
    this.send('rtc.sas.key', {
      public_key: encodeBase64Url(this.key.publicKey),
      fingerprint: encodeFingerprint(fingerprint),
    });
  }

  private async maybeCompleteCaller(): Promise<void> {
    if (this.code) return;
    const remote = this.remoteFingerprint;
    const advertised = this.peerAdvertisedFingerprint;
    if (!remote || !advertised) return;
    securityRequire(constantTimeEqual(remote, advertised), 'fingerprint_mismatch');
    const peerKey = this.peerPublicKey;
    const local = this.localFingerprint;
    if (!peerKey || !local) return;
    if (!this.sentReveal) {
      this.sentReveal = true;
      this.send('rtc.sas.reveal', {
        public_key: encodeBase64Url(this.key.publicKey),
        fingerprint: encodeFingerprint(local),
      });
    }
    await this.deriveCode(this.key.sharedSecret(peerKey), this.key.publicKey, peerKey, local, remote);
  }

  private async maybeCompleteCallee(): Promise<void> {
    if (this.code) return;
    const expected = this.receivedCommitment;
    const peerKey = this.peerPublicKey;
    const advertised = this.peerAdvertisedFingerprint;
    const remote = this.remoteFingerprint;
    if (!expected || !peerKey || !advertised || !remote) return;
    securityRequire(constantTimeEqual(remote, advertised), 'fingerprint_mismatch');
    const actual = await commitment(this.callId, peerKey, advertised);
    try {
      securityRequire(constantTimeEqual(expected, actual), 'commitment_mismatch');
    } finally {
      actual.fill(0);
    }
    const local = this.localFingerprint;
    if (!local) return;
    await this.deriveCode(this.key.sharedSecret(peerKey), peerKey, this.key.publicKey, remote, local);
  }

  private async deriveCode(
    sharedSecret: Uint8Array,
    callerPublicKey: Uint8Array,
    calleePublicKey: Uint8Array,
    callerFingerprint: Uint8Array,
    calleeFingerprint: Uint8Array,
  ): Promise<void> {
    try {
      this.code = await deriveSecurityCode(
        sharedSecret,
        this.callId,
        this.caller,
        this.callee,
        callerPublicKey,
        calleePublicKey,
        callerFingerprint,
        calleeFingerprint,
      );
    } finally {
      sharedSecret.fill(0);
      this.key.close();
    }
    this.publishCodeIfReady();
  }

  private publishCodeIfReady(): void {
    if (!this.code || !this.transportConnected) return;
    this.publish({ state: 'ready', code: this.code });
  }

  private recordFingerprint(local: boolean, fingerprint: Uint8Array): void {
    const previous = local ? this.localFingerprint : this.remoteFingerprint;
    securityRequire(!previous || constantTimeEqual(previous, fingerprint), 'fingerprint_changed');
    if (previous) {
      fingerprint.fill(0);
    } else if (local) {
      this.localFingerprint = fingerprint;
    } else {
      this.remoteFingerprint = fingerprint;
    }
  }

  private parseFingerprint(sdp: string): Uint8Array {
    try {
      return parseSdpFingerprint(sdp);
    } catch {
      throw new CallSecurityException('invalid_fingerprint');
    }
  }

  private fail(reason: CallSecurityFailureReason): void {
    if (this.failed || this.closed) return;
    this.failed = true;
    this.clearKeyMaterial();
    this.publish({ state: 'failed', reason });
  }

  private clearKeyMaterial(): void {
    this.key.close();
    this.localFingerprint?.fill(0);
    this.remoteFingerprint?.fill(0);
    this.receivedCommitment?.fill(0);
    this.peerPublicKey?.fill(0);
    this.peerAdvertisedFingerprint?.fill(0);
    this.localFingerprint = undefined;
    this.remoteFingerprint = undefined;
    this.receivedCommitment = undefined;
    this.peerPublicKey = undefined;
    this.peerAdvertisedFingerprint = undefined;
  }
}

export async function commitment(callId: string, callerPublicKey: Uint8Array, callerFingerprint: Uint8Array): Promise<Uint8Array> {
  return digestTranscript(commitmentDomain, ascii(callId), callerPublicKey, callerFingerprint);
}

export async function deriveSecurityCode(
  sharedSecret: Uint8Array,
  callId: string,
  caller: string,
  callee: string,
  callerPublicKey: Uint8Array,
  calleePublicKey: Uint8Array,
  callerFingerprint: Uint8Array,
  calleeFingerprint: Uint8Array,
): Promise<string> {
  if (sharedSecret.length !== keySize) throw new Error('X25519 shared secret must be 32 bytes');
  for (const value of [callerPublicKey, calleePublicKey, callerFingerprint, calleeFingerprint]) {
    if (value.length !== keySize) throw new Error('SAS key material must be 32 bytes');
  }
  const salt = await digestTranscript(saltDomain, ascii(callId));
  const info = transcript(
    codeDomain,
    ascii(callId),
    utf8(caller),
    utf8(callee),
    callerPublicKey,
    calleePublicKey,
    callerFingerprint,
    calleeFingerprint,
  );
  const output = await hkdfSha256(sharedSecret, salt, info, 8);
  try {
    const numeric = bytesToBigEndian(output) % codeModulus;
    const digits = numeric.toString().padStart(12, '0');
    return `${digits.slice(0, 4)} ${digits.slice(4, 8)} ${digits.slice(8)}`;
  } finally {
    salt.fill(0);
    info.fill(0);
    output.fill(0);
  }
}

export function encodeBase64Url(value: Uint8Array): string {
  let binary = '';
  for (const byte of value) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

export function decodeBase64Url(value: string): Uint8Array {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) throw new Error('value must be base64url');
  const padded = value.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - value.length % 4) % 4);
  const binary = atob(padded);
  const bytes = Uint8Array.from(binary, char => char.charCodeAt(0));
  if (bytes.length !== keySize || encodeBase64Url(bytes) !== value) throw new Error('value must be canonical 32-byte base64url');
  return bytes;
}

export function encodeFingerprint(value: Uint8Array): string {
  if (value.length !== keySize) throw new Error('fingerprint must be 32 bytes');
  return Array.from(value, byte => byte.toString(16).padStart(2, '0')).join('');
}

export function decodeFingerprint(value: string): Uint8Array {
  if (!/^[0-9a-f]{64}$/.test(value)) throw new Error('fingerprint must be lowercase SHA-256 hex');
  const result = new Uint8Array(keySize);
  for (let index = 0; index < keySize; index++) result[index] = Number.parseInt(value.slice(index * 2, index * 2 + 2), 16);
  return result;
}

export function parseSdpFingerprint(sdp: string): Uint8Array {
  const fingerprints: Uint8Array[] = [];
  for (const rawLine of sdp.split(/\n/)) {
    const line = rawLine.replace(/\r$/, '');
    if (!line.toLowerCase().startsWith('a=fingerprint:')) continue;
    const value = line.slice(line.indexOf(':') + 1).trim();
    const parts = value.split(/\s+/, 2);
    if (!line.startsWith('a=fingerprint:') || parts.length !== 2 || parts[0].toLowerCase() !== 'sha-256') {
      throw new Error('SDP contains an unsupported DTLS fingerprint');
    }
    fingerprints.push(decodeColonHex(parts[1]));
  }
  if (!fingerprints.length) throw new Error('SDP has no SHA-256 DTLS fingerprint');
  if (!fingerprints.slice(1).every(item => constantTimeEqual(fingerprints[0], item))) {
    throw new Error('SDP contains different DTLS fingerprints');
  }
  return fingerprints[0];
}

export function securityEmoji(code: string): string[] {
  const digits = code.replace(/\s/g, '');
  if (!/^\d{12}$/.test(digits)) throw new Error('security code must contain exactly 12 digits');
  let value = BigInt(digits);
  const result = Array<string>(5);
  for (let index = result.length - 1; index >= 0; index--) {
    result[index] = securitySymbols[Number(value % 256n)];
    value /= 256n;
  }
  if (value !== 0n) throw new Error('security code is outside emoji range');
  return result;
}

function decodePayloadValue(payload: SignalPayload, name: string, reason: CallSecurityFailureReason): Uint8Array {
  const value = payload[name];
  try {
    if (typeof value !== 'string') throw new Error('missing string');
    return decodeBase64Url(value);
  } catch {
    throw new CallSecurityException(reason);
  }
}

function decodePayloadFingerprint(payload: SignalPayload): Uint8Array {
  const value = payload.fingerprint;
  try {
    if (typeof value !== 'string') throw new Error('missing string');
    return decodeFingerprint(value);
  } catch {
    throw new CallSecurityException('invalid_fingerprint');
  }
}

function securityRequire(condition: boolean, reason: CallSecurityFailureReason): void {
  if (!condition) throw new CallSecurityException(reason);
}

function decodeColonHex(value: string): Uint8Array {
  const parts = value.split(':');
  if (parts.length !== keySize || !parts.every(part => /^[0-9a-fA-F]{2}$/.test(part))) {
    throw new Error('invalid SHA-256 DTLS fingerprint');
  }
  return Uint8Array.from(parts, part => Number.parseInt(part, 16));
}

async function digestTranscript(domain: string, ...values: Uint8Array[]): Promise<Uint8Array> {
  const encoded = transcript(domain, ...values);
  try {
    return await sha256(encoded);
  } finally {
    encoded.fill(0);
  }
}

function transcript(domain: string, ...values: Uint8Array[]): Uint8Array {
  const domainBytes = ascii(domain);
  const parts = [domainBytes, ...values];
  const total = parts.reduce((sum, value) => sum + 4 + value.length, 0);
  const output = new Uint8Array(total);
  const view = new DataView(output.buffer, output.byteOffset, output.byteLength);
  let offset = 0;
  for (const value of parts) {
    view.setUint32(offset, value.length, false);
    offset += 4;
    output.set(value, offset);
    offset += value.length;
  }
  return output;
}

async function hkdfSha256(inputKey: Uint8Array, salt: Uint8Array, info: Uint8Array, length: number): Promise<Uint8Array> {
  if (length < 1 || length > 255 * 32) throw new Error('invalid HKDF output length');
  const pseudoRandomKey = await hmacSha256(salt, inputKey);
  const output = new Uint8Array(length);
  let previous: Uint8Array = new Uint8Array(0);
  let offset = 0;
  let counter = 1;
  try {
    while (offset < length) {
      const input = new Uint8Array(previous.length + info.length + 1);
      input.set(previous, 0);
      input.set(info, previous.length);
      input[input.length - 1] = counter;
      previous.fill(0);
      previous = await hmacSha256(pseudoRandomKey, input);
      input.fill(0);
      const copied = Math.min(previous.length, length - offset);
      output.set(previous.slice(0, copied), offset);
      offset += copied;
      counter++;
    }
    return output;
  } finally {
    previous.fill(0);
    pseudoRandomKey.fill(0);
  }
}

async function sha256(value: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await globalThis.crypto.subtle.digest('SHA-256', cryptoBuffer(value)));
}

async function hmacSha256(keyBytes: Uint8Array, value: Uint8Array): Promise<Uint8Array> {
  const key = await globalThis.crypto.subtle.importKey('raw', cryptoBuffer(keyBytes), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return new Uint8Array(await globalThis.crypto.subtle.sign('HMAC', key, cryptoBuffer(value)));
}

function cryptoBuffer(value: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(value.byteLength);
  copy.set(value);
  return copy.buffer;
}

function randomBytes(length: number): Uint8Array {
  const output = new Uint8Array(length);
  globalThis.crypto.getRandomValues(output);
  return output;
}

function ascii(value: string): Uint8Array {
  return Uint8Array.from(value, char => {
    const code = char.charCodeAt(0);
    if (code > 0x7f) throw new Error('value must be ASCII');
    return code;
  });
}

function utf8(value: string): Uint8Array {
  return new TextEncoder().encode(value);
}

function bytesToBigEndian(bytes: Uint8Array): bigint {
  let result = 0n;
  for (const byte of bytes) result = (result << 8n) | BigInt(byte);
  return result;
}

function bytesToLittleEndian(bytes: Uint8Array): bigint {
  let result = 0n;
  for (let index = bytes.length - 1; index >= 0; index--) result = (result << 8n) | BigInt(bytes[index]);
  return result;
}

function littleEndianToBytes(value: bigint): Uint8Array {
  const output = new Uint8Array(keySize);
  let current = mod(value);
  for (let index = 0; index < output.length; index++) {
    output[index] = Number(current & 0xffn);
    current >>= 8n;
  }
  return output;
}

function basePoint(): Uint8Array {
  const point = new Uint8Array(keySize);
  point[0] = 9;
  return point;
}

export function x25519(scalar: Uint8Array, point: Uint8Array): Uint8Array {
  if (scalar.length !== keySize || point.length !== keySize) throw new Error('X25519 values must be 32 bytes');
  const k = new Uint8Array(scalar);
  k[0] &= 248;
  k[31] &= 127;
  k[31] |= 64;
  const u = new Uint8Array(point);
  u[31] &= 127;
  const x1 = bytesToLittleEndian(u);
  let x2 = 1n;
  let z2 = 0n;
  let x3 = x1;
  let z3 = 1n;
  let swap = 0n;
  const scalarValue = bytesToLittleEndian(k);
  for (let pos = 254; pos >= 0; pos--) {
    const bit = (scalarValue >> BigInt(pos)) & 1n;
    swap ^= bit;
    [x2, x3] = conditionalSwap(swap, x2, x3);
    [z2, z3] = conditionalSwap(swap, z2, z3);
    swap = bit;
    const a = mod(x2 + z2);
    const aa = mod(a * a);
    const b = mod(x2 - z2);
    const bb = mod(b * b);
    const e = mod(aa - bb);
    const c = mod(x3 + z3);
    const d = mod(x3 - z3);
    const da = mod(d * a);
    const cb = mod(c * b);
    x3 = mod((da + cb) ** 2n);
    z3 = mod(x1 * mod((da - cb) ** 2n));
    x2 = mod(aa * bb);
    z2 = mod(e * mod(aa + a24 * e));
  }
  [x2, x3] = conditionalSwap(swap, x2, x3);
  [z2, z3] = conditionalSwap(swap, z2, z3);
  return littleEndianToBytes(mod(x2 * modInverse(z2)));
}

function conditionalSwap(swap: bigint, a: bigint, b: bigint): [bigint, bigint] {
  return swap === 0n ? [a, b] : [b, a];
}

function mod(value: bigint): bigint {
  const result = value % p;
  return result >= 0n ? result : result + p;
}

function modPow(base: bigint, exponent: bigint): bigint {
  let result = 1n;
  let current = mod(base);
  let exp = exponent;
  while (exp > 0n) {
    if (exp & 1n) result = mod(result * current);
    current = mod(current * current);
    exp >>= 1n;
  }
  return result;
}

function modInverse(value: bigint): bigint {
  return modPow(value, p - 2n);
}

function constantTimeEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let index = 0; index < a.length; index++) diff |= a[index] ^ b[index];
  return diff === 0;
}

const securitySymbols = `
😀 😂 😍 😎 🤠 😱 😡 😴 🤢 🤯 🤡 💀 😈 👻 👽 🤖
🐒 🐶 🦊 🦝 🐱 🐯 🐴 🦄 🦓 🐮 🐷 🐐 🐪 🦒 🐘 🦏
🦛 🐭 🐰 🦔 🦇 🐼 🦨 🦘 🐧 🦅 🦉 🦩 🦚 🐸 🐊 🐢
🐍 🐌 🦖 🐳 🐬 🐙 🦀 🦋 🍇 🍉 🍋 🍌 🍍 🍎 🍒 🍓
🥝 🥥 🥑 🍆 🥕 🌽 🫑 🥦 🧄 🍞 🥐 🧀 🍗 🥓 🍔 🍟
🍕 🌭 🍳 🍿 🍣 🍦 🎂 🍭 🌍 🧭 🌞 🌙 🌟 🌈 ⚡ 🔥
❄️ ☔ 💧 🌊 🌋 🌪️ ✈️ 🚀 🌴 🌵 🍀 🌻 🍄 🏠 🏥 🏭
🏰 🗽 ⛺ 🌉 🎡 🛝 🚂 🚢 ⚽ 🏹 🛹 🪂 🤿 🏄 🏋️ 🎳
🏓 🤺 🥊 🏆 🥇 🎯 🎣 🎿 🛷 🤹 🪁 🎱 🔮 🪄 🎮 🎰
🎲 🧩 🪃 🎫 🛒 🎭 🎨 🧵 🎃 🎄 🎆 🎈 🎤 🎁 🎸 🥁
🪑 🦺 👔 👕 👖 🧣 🧤 🧦 👗 🎒 👟 👑 🎩 🎓 🪖 💄
💍 💎 🔔 🎵 🎧 📻 🎷 🧺 🎹 🪣 📱 🔋 🔌 💻 💾 💿
📷 📺 🔍 💡 🔦 🔑 🔒 🔨 🔧 🧲 💣 💊 💉 💰 🚿 🔬
🧹 ⏰ 🧯 🪓 🛡️ 🚪 🚽 🧼 ❤️ 💯 💥 💤 🧬 💬 👁️ 👂
👃 👄 🧠 🦷 🦴 👣 👍 ✋ 🙏 💪 ✅ ❌ ❓ ❗ ⚠️ 🚫
♻️ ☢️ 🚦 🆘 🛑 ▶️ ⏸️ 🕯️ 🧪 🩺 🛏️ ⚓ ✂️ 📎 📖 ⌛
`.trim().split(/\s+/);

if (securitySymbols.length !== 256 || new Set(securitySymbols).size !== securitySymbols.length) {
  throw new Error('security emoji table must contain 256 unique symbols');
}
