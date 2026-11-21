"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.MAX_E2EE_ENCRYPTED_BASE64_CHARACTERS = void 0;
exports.generateKeyPair = generateKeyPair;
exports.deriveSharedKey = deriveSharedKey;
exports.publicKeyFromBase64 = publicKeyFromBase64;
exports.publicKeyToBase64 = publicKeyToBase64;
exports.encrypt = encrypt;
exports.decrypt = decrypt;
exports.encryptBytes = encryptBytes;
exports.decryptBytes = decryptBytes;
// Share NaCl box format across desktop/CLI/mobile pairing with Node-compatible primitives, avoiding main imports from CLI.
const tweetnacl_1 = __importDefault(require("tweetnacl"));
const MAX_E2EE_TEXT_PLAINTEXT_BYTES = 4 * 1024 * 1024;
const LEGACY_E2EE_FRAME_OVERHEAD_BYTES = tweetnacl_1.default.box.nonceLength + tweetnacl_1.default.box.overheadLength;
exports.MAX_E2EE_ENCRYPTED_BASE64_CHARACTERS = Math.ceil((MAX_E2EE_TEXT_PLAINTEXT_BYTES + LEGACY_E2EE_FRAME_OVERHEAD_BYTES) / 3) * 4;
function generateKeyPair() {
    return tweetnacl_1.default.box.keyPair();
}
function deriveSharedKey(ourSecretKey, peerPublicKey) {
    return tweetnacl_1.default.box.before(peerPublicKey, ourSecretKey);
}
function publicKeyFromBase64(b64) {
    if (b64.length > 44) {
        throw new Error('Invalid public key: encoded value is too large');
    }
    const key = Uint8Array.from(Buffer.from(b64, 'base64'));
    if (key.length !== 32) {
        throw new Error(`Invalid public key: expected 32 bytes, got ${key.length}`);
    }
    return key;
}
function publicKeyToBase64(key) {
    return Buffer.from(key).toString('base64');
}
function encrypt(plaintext, sharedKey) {
    const messageBytes = new TextEncoder().encode(plaintext);
    return Buffer.from(encryptBytes(messageBytes, sharedKey)).toString('base64');
}
function decrypt(encrypted, sharedKey) {
    if (encrypted.length > exports.MAX_E2EE_ENCRYPTED_BASE64_CHARACTERS) {
        return null;
    }
    const bundle = Uint8Array.from(Buffer.from(encrypted, 'base64'));
    const plaintext = decryptBytes(bundle, sharedKey);
    return plaintext ? new TextDecoder().decode(plaintext) : null;
}
function encryptBytes(plaintext, sharedKey) {
    const nonce = tweetnacl_1.default.randomBytes(tweetnacl_1.default.box.nonceLength);
    const ciphertext = tweetnacl_1.default.box.after(plaintext, nonce, sharedKey);
    const bundle = new Uint8Array(nonce.length + ciphertext.length);
    bundle.set(nonce);
    bundle.set(ciphertext, nonce.length);
    return bundle;
}
function decryptBytes(bundle, sharedKey) {
    if (bundle.length < tweetnacl_1.default.box.nonceLength + tweetnacl_1.default.box.overheadLength) {
        return null;
    }
    const nonce = bundle.slice(0, tweetnacl_1.default.box.nonceLength);
    const ciphertext = bundle.slice(tweetnacl_1.default.box.nonceLength);
    const plaintext = tweetnacl_1.default.box.open.after(ciphertext, nonce, sharedKey);
    if (!plaintext) {
        return null;
    }
    return plaintext;
}
