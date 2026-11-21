"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.NODE_PAIRED_E2EE_PRIMITIVES = void 0;
const tweetnacl_1 = __importDefault(require("tweetnacl"));
const e2ee_crypto_1 = require("./e2ee-crypto");
const mobile_e2ee_v2_node_key_schedule_1 = require("./mobile-e2ee-v2-node-key-schedule");
exports.NODE_PAIRED_E2EE_PRIMITIVES = {
    generateKeyPair: e2ee_crypto_1.generateKeyPair,
    deriveSharedKey: e2ee_crypto_1.deriveSharedKey,
    randomBytes: (length) => tweetnacl_1.default.randomBytes(length),
    encodeBase64: (bytes) => Buffer.from(bytes).toString('base64'),
    decodeBase64: (value) => Uint8Array.from(Buffer.from(value, 'base64')),
    publicKeyFromBase64: e2ee_crypto_1.publicKeyFromBase64,
    publicKeyToBase64: e2ee_crypto_1.publicKeyToBase64,
    sealLegacyText: e2ee_crypto_1.encrypt,
    openLegacyText: e2ee_crypto_1.decrypt,
    sealLegacyBinary: e2ee_crypto_1.encryptBytes,
    openLegacyBinary: e2ee_crypto_1.decryptBytes,
    deriveV2KeySchedule: mobile_e2ee_v2_node_key_schedule_1.deriveMobileE2EEV2KeySchedule
};
