"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.sealMobileE2EEV2Frame = sealMobileE2EEV2Frame;
exports.openMobileE2EEV2Frame = openMobileE2EEV2Frame;
const tweetnacl_1 = __importDefault(require("tweetnacl"));
const NONCE_LENGTH = 24;
const SESSION_ID_LENGTH = 32;
const HEADER_LENGTH = SESSION_ID_LENGTH + 1 + 1 + 8;
const FRAME_VERSION = 2;
const MAX_COUNTER = (1n << 64n) - 1n;
function sealMobileE2EEV2Frame(args) {
    validateFrameInputs(args.key, args.sessionId, args.counter);
    const header = encodeHeader(args);
    const nonce = encodeNonce(args);
    const plaintext = concatBytes([header, args.payload]);
    const ciphertext = (args.sealSecretbox ?? tweetnacl_1.default.secretbox)(plaintext, nonce, args.key);
    return concatBytes([nonce, ciphertext]);
}
function openMobileE2EEV2Frame(args) {
    validateFrameInputs(args.key, args.sessionId, args.expectedCounter);
    if (args.frame.length < NONCE_LENGTH + tweetnacl_1.default.secretbox.overheadLength + HEADER_LENGTH) {
        return null;
    }
    const expected = {
        sessionId: args.sessionId,
        direction: args.direction,
        payloadKind: args.payloadKind,
        counter: args.expectedCounter
    };
    const nonce = encodeNonce(expected);
    if (!equalBytes(args.frame.subarray(0, NONCE_LENGTH), nonce)) {
        return null;
    }
    // The length check above must stay ahead of this: native libsodium crashes on a box shorter than its MAC.
    const openSecretbox = args.openSecretbox ?? tweetnacl_1.default.secretbox.open;
    const plaintext = openSecretbox(args.frame.subarray(NONCE_LENGTH), nonce, args.key);
    if (!plaintext) {
        return null;
    }
    const header = encodeHeader(expected);
    if (!equalBytes(plaintext.subarray(0, HEADER_LENGTH), header)) {
        return null;
    }
    return plaintext.slice(HEADER_LENGTH);
}
function encodeHeader(args) {
    const header = new Uint8Array(HEADER_LENGTH);
    header.set(args.sessionId, 0);
    header[SESSION_ID_LENGTH] = directionByte(args.direction);
    header[SESSION_ID_LENGTH + 1] = payloadKindByte(args.payloadKind);
    writeUint64(header, SESSION_ID_LENGTH + 2, args.counter);
    return header;
}
function encodeNonce(args) {
    const nonce = new Uint8Array(NONCE_LENGTH);
    // Fresh per-socket keys/sessionId make this direction/kind/counter nonce layout unique without another RNG draw.
    nonce.set(args.sessionId.subarray(0, 12), 0);
    nonce[12] = FRAME_VERSION;
    nonce[13] = directionByte(args.direction);
    nonce[14] = payloadKindByte(args.payloadKind);
    nonce[15] = 0;
    writeUint64(nonce, 16, args.counter);
    return nonce;
}
function directionByte(direction) {
    return direction === 'mobile-to-desktop' ? 0 : 1;
}
function payloadKindByte(kind) {
    return kind === 'text' ? 0 : 1;
}
function validateFrameInputs(key, sessionId, counter) {
    if (key.length !== tweetnacl_1.default.secretbox.keyLength) {
        throw new Error(`Invalid E2EE v2 key length: ${key.length}`);
    }
    if (sessionId.length !== SESSION_ID_LENGTH) {
        throw new Error(`Invalid E2EE v2 session ID length: ${sessionId.length}`);
    }
    if (counter < 0n || counter > MAX_COUNTER) {
        throw new Error(`Invalid E2EE v2 counter: ${counter}`);
    }
}
function writeUint64(target, offset, value) {
    let remaining = value;
    for (let index = 7; index >= 0; index--) {
        target[offset + index] = Number(remaining & 0xffn);
        remaining >>= 8n;
    }
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
function equalBytes(left, right) {
    if (left.length !== right.length) {
        return false;
    }
    let difference = 0;
    for (let index = 0; index < left.length; index++) {
        difference |= left[index] ^ right[index];
    }
    return difference === 0;
}
