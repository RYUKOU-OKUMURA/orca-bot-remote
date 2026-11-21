"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.MOBILE_E2EE_V2_TRANSCRIPT_DOMAIN = exports.MOBILE_E2EE_V2_PROTOCOL = void 0;
exports.validateMobileE2EEV2Handshake = validateMobileE2EEV2Handshake;
exports.encodeMobileE2EEV2Transcript = encodeMobileE2EEV2Transcript;
exports.MOBILE_E2EE_V2_PROTOCOL = 'orca-mobile-e2ee';
exports.MOBILE_E2EE_V2_TRANSCRIPT_DOMAIN = 'orca-mobile-e2ee/v2/transcript';
const BASE64URL_16_PATTERN = /^[A-Za-z0-9_-]{16}$/;
function validateMobileE2EEV2Handshake(helloValue, readyValue) {
    if (!isExactRecord(helloValue, [
        'type',
        'v',
        'clientPublicKeyB64',
        'clientNonceB64',
        'capabilities',
        'context'
    ])) {
        return null;
    }
    if (!isExactRecord(readyValue, [
        'type',
        'v',
        'desktopPublicKeyB64',
        'clientNonceB64',
        'desktopNonceB64',
        'selection',
        'context'
    ])) {
        return null;
    }
    if (helloValue.type !== 'e2ee_hello' || helloValue.v !== 2) {
        return null;
    }
    if (readyValue.type !== 'e2ee_ready' || readyValue.v !== 2) {
        return null;
    }
    if (!hasExactCapabilities(helloValue.capabilities) || !hasExactSelection(readyValue.selection)) {
        return null;
    }
    const helloContext = parseContext(helloValue.context);
    const readyContext = parseContext(readyValue.context);
    if (!helloContext || !readyContext || !contextsEqual(helloContext, readyContext)) {
        return null;
    }
    if (readyValue.clientNonceB64 !== helloValue.clientNonceB64) {
        return null;
    }
    const clientPublicKey = decodeCanonicalBase64Bytes(helloValue.clientPublicKeyB64, 32);
    const desktopPublicKey = decodeCanonicalBase64Bytes(readyValue.desktopPublicKeyB64, 32);
    const clientNonce = decodeCanonicalBase64Bytes(helloValue.clientNonceB64, 32);
    const desktopNonce = decodeCanonicalBase64Bytes(readyValue.desktopNonceB64, 32);
    if (!clientPublicKey || !desktopPublicKey || !clientNonce || !desktopNonce) {
        return null;
    }
    return {
        hello: helloValue,
        ready: readyValue,
        clientPublicKey,
        desktopPublicKey,
        clientNonce,
        desktopNonce
    };
}
function encodeMobileE2EEV2Transcript(handshake) {
    const { hello, ready } = handshake;
    const fields = [
        ['domain', utf8(exports.MOBILE_E2EE_V2_TRANSCRIPT_DOMAIN)],
        ['mobile-to-desktop.type', utf8(hello.type)],
        ['mobile-to-desktop.version', uint32(hello.v)],
        ['mobile-to-desktop.client-public-key', handshake.clientPublicKey],
        ['mobile-to-desktop.client-nonce', handshake.clientNonce],
        ['mobile-to-desktop.capabilities.framing', encodeNumberList(hello.capabilities.framing)],
        [
            'mobile-to-desktop.capabilities.payload-kinds',
            encodeStringList(hello.capabilities.payloadKinds)
        ],
        ['mobile-to-desktop.context.protocol', utf8(hello.context.protocol)],
        ['mobile-to-desktop.context.initiator', utf8(hello.context.initiator)],
        ['mobile-to-desktop.context.responder', utf8(hello.context.responder)],
        ['mobile-to-desktop.context.transport', utf8(hello.context.transport)],
        ['mobile-to-desktop.context.relay-host-id', utf8(hello.context.relayHostId ?? '')],
        ['desktop-to-mobile.type', utf8(ready.type)],
        ['desktop-to-mobile.version', uint32(ready.v)],
        ['desktop-to-mobile.desktop-public-key', handshake.desktopPublicKey],
        ['desktop-to-mobile.client-nonce-echo', handshake.clientNonce],
        ['desktop-to-mobile.desktop-nonce', handshake.desktopNonce],
        ['desktop-to-mobile.selection.framing', uint32(ready.selection.framing)],
        ['desktop-to-mobile.selection.payload-kinds', encodeStringList(ready.selection.payloadKinds)],
        ['desktop-to-mobile.context.protocol', utf8(ready.context.protocol)],
        ['desktop-to-mobile.context.initiator', utf8(ready.context.initiator)],
        ['desktop-to-mobile.context.responder', utf8(ready.context.responder)],
        ['desktop-to-mobile.context.transport', utf8(ready.context.transport)],
        ['desktop-to-mobile.context.relay-host-id', utf8(ready.context.relayHostId ?? '')]
    ];
    return concatBytes(fields.map(([name, value]) => concatBytes([uint32(utf8(name).length), utf8(name), uint32(value.length), value])));
}
function parseContext(value) {
    if (!isRecord(value)) {
        return null;
    }
    const transport = value.transport;
    const keys = transport === 'relay'
        ? ['protocol', 'initiator', 'responder', 'transport', 'relayHostId']
        : ['protocol', 'initiator', 'responder', 'transport'];
    if (!isExactRecord(value, keys)) {
        return null;
    }
    if (value.protocol !== exports.MOBILE_E2EE_V2_PROTOCOL ||
        value.initiator !== 'mobile' ||
        value.responder !== 'desktop' ||
        (transport !== 'direct' && transport !== 'relay')) {
        return null;
    }
    if (transport === 'relay' &&
        (typeof value.relayHostId !== 'string' || !BASE64URL_16_PATTERN.test(value.relayHostId))) {
        return null;
    }
    return value;
}
function hasExactCapabilities(value) {
    return (isExactRecord(value, ['framing', 'payloadKinds']) &&
        Array.isArray(value.framing) &&
        value.framing.length === 1 &&
        value.framing[0] === 2 &&
        Array.isArray(value.payloadKinds) &&
        value.payloadKinds.length === 2 &&
        value.payloadKinds[0] === 'text' &&
        value.payloadKinds[1] === 'binary');
}
function hasExactSelection(value) {
    return (isExactRecord(value, ['framing', 'payloadKinds']) &&
        value.framing === 2 &&
        Array.isArray(value.payloadKinds) &&
        value.payloadKinds.length === 2 &&
        value.payloadKinds[0] === 'text' &&
        value.payloadKinds[1] === 'binary');
}
function contextsEqual(left, right) {
    return (left.protocol === right.protocol &&
        left.initiator === right.initiator &&
        left.responder === right.responder &&
        left.transport === right.transport &&
        left.relayHostId === right.relayHostId);
}
function decodeCanonicalBase64Bytes(value, length) {
    if (typeof value !== 'string' ||
        value.length !== Math.ceil(length / 3) * 4 ||
        !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value)) {
        return null;
    }
    try {
        const binary = atob(value);
        const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
        return bytes.length === length && encodeBase64(bytes) === value ? bytes : null;
    }
    catch {
        return null;
    }
}
function encodeBase64(bytes) {
    let binary = '';
    for (const byte of bytes) {
        binary += String.fromCharCode(byte);
    }
    return btoa(binary);
}
function encodeNumberList(values) {
    return concatBytes([uint32(values.length), ...values.map(uint32)]);
}
function encodeStringList(values) {
    return concatBytes([
        uint32(values.length),
        ...values.map((value) => {
            const bytes = utf8(value);
            return concatBytes([uint32(bytes.length), bytes]);
        })
    ]);
}
function uint32(value) {
    const bytes = new Uint8Array(4);
    new DataView(bytes.buffer).setUint32(0, value, false);
    return bytes;
}
function utf8(value) {
    return new TextEncoder().encode(value);
}
function concatBytes(parts) {
    const result = new Uint8Array(parts.reduce((total, part) => total + part.length, 0));
    let offset = 0;
    for (const part of parts) {
        result.set(part, offset);
        offset += part.length;
    }
    return result;
}
function isRecord(value) {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}
function isExactRecord(value, keys) {
    if (!isRecord(value)) {
        return false;
    }
    const actual = Object.keys(value).sort();
    const expected = [...keys].sort();
    return actual.length === expected.length && actual.every((key, index) => key === expected[index]);
}
