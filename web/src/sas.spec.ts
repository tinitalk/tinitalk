import { describe, expect, it } from 'vitest';
import {
  CallSASHandshake,
  EphemeralCallKey,
  parseSdpFingerprint,
  securityEmoji,
  x25519,
  type CallSecurityState,
} from './sas';

describe('call SAS', () => {
  it('matches the RFC 7748 X25519 test vector', () => {
    const alicePrivate = hex('77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a');
    const bobPrivate = hex('5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb');
    const alicePublic = hex('8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a');
    const bobPublic = hex('de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f');
    const shared = '4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742';
    expect(toHex(x25519(alicePrivate, basePoint()))).toBe(toHex(alicePublic));
    expect(toHex(x25519(bobPrivate, basePoint()))).toBe(toHex(bobPublic));
    expect(toHex(x25519(alicePrivate, bobPublic))).toBe(shared);
    expect(toHex(x25519(bobPrivate, alicePublic))).toBe(shared);
  });

  it('parses and checks a single SHA-256 SDP fingerprint', () => {
    const sdp = 'v=0\r\na=fingerprint:sha-256 00:01:02:03:04:05:06:07:08:09:0A:0B:0C:0D:0E:0F:10:11:12:13:14:15:16:17:18:19:1A:1B:1C:1D:1E:1F\r\n';
    expect(toHex(parseSdpFingerprint(sdp))).toBe('000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f');
    expect(() => parseSdpFingerprint(`${sdp}a=fingerprint:sha-256 FF:01:02:03:04:05:06:07:08:09:0A:0B:0C:0D:0E:0F:10:11:12:13:14:15:16:17:18:19:1A:1B:1C:1D:1E:1F\r\n`)).toThrow();
  });

  it('maps the full 12-digit SAS code range to five emoji', () => {
    const emoji = securityEmoji('9999 9999 9999');
    expect(emoji).toHaveLength(5);
    expect(emoji.every(Boolean)).toBe(true);
  });

  it('reports invalid SDP fingerprints as a security failure reason', async () => {
    const states: CallSecurityState[] = [];
    const caller = new CallSASHandshake(
      'call-1', 'alice', 'bob', 'caller',
      () => undefined,
      state => states.push(state),
      new EphemeralCallKey(hex('77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a')),
    );

    await caller.recordLocalSdp('v=0\r\n');

    expect(states.at(-1)).toEqual({ state: 'failed', reason: 'invalid_fingerprint' });
  });

  it('derives the same ready code on both sides after commit/key/reveal and transport connection', async () => {
    const callerSent: { type: string; payload: Record<string, unknown> }[] = [];
    const calleeSent: { type: string; payload: Record<string, unknown> }[] = [];
    const callerStates: CallSecurityState[] = [];
    const calleeStates: CallSecurityState[] = [];
    const caller = new CallSASHandshake(
      'call-1', 'alice', 'bob', 'caller',
      (type, payload) => callerSent.push({ type, payload }),
      state => callerStates.push(state),
      new EphemeralCallKey(hex('77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a')),
    );
    const callee = new CallSASHandshake(
      'call-1', 'alice', 'bob', 'callee',
      (type, payload) => calleeSent.push({ type, payload }),
      state => calleeStates.push(state),
      new EphemeralCallKey(hex('5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb')),
    );

    const callerSdp = sdpWithFingerprint('000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f');
    const calleeSdp = sdpWithFingerprint('202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f');
    await caller.recordLocalSdp(callerSdp);
    expect(callerSent[0].type).toBe('rtc.sas.commit');
    await callee.onCommitment(callerSent[0].payload);
    await callee.recordLocalSdp(calleeSdp);
    expect(calleeSent[0].type).toBe('rtc.sas.key');
    await caller.recordRemoteSdp(calleeSdp);
    await caller.onKey(calleeSent[0].payload);
    expect(callerSent[1].type).toBe('rtc.sas.reveal');
    await callee.recordRemoteSdp(callerSdp);
    await callee.onReveal(callerSent[1].payload);
    caller.onTransportConnected();
    callee.onTransportConnected();

    const callerReady = lastReady(callerStates);
    const calleeReady = lastReady(calleeStates);
    expect(callerReady?.state).toBe('ready');
    expect(calleeReady?.state).toBe('ready');
    expect(callerReady).toEqual(calleeReady);
    if (callerReady?.state === 'ready') expect(securityEmoji(callerReady.code)).toHaveLength(5);
  });
});

function lastReady(states: CallSecurityState[]): CallSecurityState | undefined {
  for (let index = states.length - 1; index >= 0; index -= 1) {
    if (states[index].state === 'ready') return states[index];
  }
  return undefined;
}

function sdpWithFingerprint(hexValue: string): string {
  return `v=0\r\na=fingerprint:sha-256 ${hexValue.match(/../g)?.join(':')}\r\n`;
}

function basePoint(): Uint8Array {
  const value = new Uint8Array(32);
  value[0] = 9;
  return value;
}

function hex(value: string): Uint8Array {
  return Uint8Array.from(value.match(/../g) ?? [], part => Number.parseInt(part, 16));
}

function toHex(value: Uint8Array): string {
  return Array.from(value, byte => byte.toString(16).padStart(2, '0')).join('');
}





