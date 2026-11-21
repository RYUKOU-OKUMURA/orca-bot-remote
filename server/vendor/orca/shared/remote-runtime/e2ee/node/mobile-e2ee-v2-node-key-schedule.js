"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.deriveMobileE2EEV2KeySchedule = deriveMobileE2EEV2KeySchedule;
const node_crypto_1 = require("node:crypto");
const SALT_LABEL = new TextEncoder().encode('orca-mobile-e2ee/v2/salt\0');
const INFO_LABEL = new TextEncoder().encode('orca-mobile-e2ee/v2/session\0');
function deriveMobileE2EEV2KeySchedule(args) {
    requireLength(args.sharedSecret, 32, 'shared secret');
    requireLength(args.clientNonce, 32, 'client nonce');
    requireLength(args.desktopNonce, 32, 'desktop nonce');
    const transcriptHash = sha256(args.transcript);
    const salt = sha256(concatBytes([SALT_LABEL, args.clientNonce, args.desktopNonce]));
    const info = concatBytes([INFO_LABEL, transcriptHash]);
    const expanded = new Uint8Array((0, node_crypto_1.hkdfSync)('sha256', args.sharedSecret, salt, info, 96));
    return {
        mobileToDesktopKey: expanded.slice(0, 32),
        desktopToMobileKey: expanded.slice(32, 64),
        sessionId: expanded.slice(64, 96),
        transcriptHash
    };
}
function sha256(bytes) {
    return new Uint8Array((0, node_crypto_1.createHash)('sha256').update(bytes).digest());
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
function requireLength(bytes, expected, label) {
    if (bytes.length !== expected) {
        throw new Error(`Invalid ${label}: expected ${expected} bytes, got ${bytes.length}`);
    }
}
