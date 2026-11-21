"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.encodeDeviceProofMessage = encodeDeviceProofMessage;
exports.decodeCanonicalBase64 = decodeCanonicalBase64;
exports.isDevicePublicKeyB64 = isDevicePublicKeyB64;
exports.isDeviceProofB64 = isDeviceProofB64;
exports.verifyDeviceProof = verifyDeviceProof;
exports.sanitizeDeviceLabel = sanitizeDeviceLabel;
const tweetnacl_1 = __importDefault(require("tweetnacl"));
const DOMAIN = 'orca-device-proof/v1';
const TRANSCRIPT_HASH_BYTES = 32;
const PUBLIC_KEY_BYTES = 32;
const SIGNATURE_BYTES = 64;
/** `orca-device-proof/v1 \0 purpose \0 transcriptHash`: one connection, one purpose. */
function encodeDeviceProofMessage(purpose, transcriptHash) {
    if (transcriptHash.length !== TRANSCRIPT_HASH_BYTES) {
        throw new Error('device proof needs a 32-byte transcript hash');
    }
    const prefix = new TextEncoder().encode(`${DOMAIN}\0${purpose}\0`);
    const message = new Uint8Array(prefix.length + transcriptHash.length);
    message.set(prefix);
    message.set(transcriptHash, prefix.length);
    return message;
}
function decodeCanonicalBase64(value, byteLength) {
    if (typeof value !== 'string' || value.length === 0 || value.length > 256) {
        return null;
    }
    try {
        const binary = atob(value);
        if (binary.length !== byteLength || btoa(binary) !== value) {
            return null;
        }
        const bytes = new Uint8Array(binary.length);
        for (let index = 0; index < binary.length; index++) {
            bytes[index] = binary.charCodeAt(index);
        }
        return bytes;
    }
    catch {
        return null;
    }
}
function isDevicePublicKeyB64(value) {
    return decodeCanonicalBase64(value, PUBLIC_KEY_BYTES) !== null;
}
function isDeviceProofB64(value) {
    return decodeCanonicalBase64(value, SIGNATURE_BYTES) !== null;
}
function verifyDeviceProof(args) {
    const publicKey = decodeCanonicalBase64(args.devicePublicKeyB64, PUBLIC_KEY_BYTES);
    const signature = decodeCanonicalBase64(args.deviceProofB64, SIGNATURE_BYTES);
    const transcriptHash = decodeCanonicalBase64(args.transcriptHashB64, TRANSCRIPT_HASH_BYTES);
    if (!publicKey || !signature || !transcriptHash) {
        return false;
    }
    return tweetnacl_1.default.sign.detached.verify(encodeDeviceProofMessage(args.purpose, transcriptHash), signature, publicKey);
}
// Remove controls/bidi from approval/device-list labels so one device cannot disguise itself as another.
function sanitizeDeviceLabel(value, fallback) {
    if (typeof value !== 'string') {
        return fallback;
    }
    const cleaned = value
        .replace(/[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]/gu, '')
        .replace(/\s+/g, ' ')
        .trim()
        .slice(0, 64);
    return cleaned.length > 0 ? cleaned : fallback;
}
