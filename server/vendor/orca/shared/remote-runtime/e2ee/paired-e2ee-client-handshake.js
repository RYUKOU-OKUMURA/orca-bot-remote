"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.PairedE2EEDowngradeError = void 0;
exports.createPairedE2EEClientHandshake = createPairedE2EEClientHandshake;
exports.createPairedE2EELegacyChannel = createPairedE2EELegacyChannel;
exports.pairedE2EEV2TokenAuth = pairedE2EEV2TokenAuth;
exports.classifyPairedE2EEV2AuthReply = classifyPairedE2EEV2AuthReply;
const device_key_proof_1 = require("../pairing/device-key-proof");
const paired_e2ee_v2_client_session_1 = require("./paired-e2ee-v2-client-session");
class PairedE2EEDowngradeError extends Error {
    constructor(stage) {
        super(`host is latched to E2EE v2; refusing legacy ${stage}`);
    }
}
exports.PairedE2EEDowngradeError = PairedE2EEDowngradeError;
function createPairedE2EEClientHandshake(args) {
    return args.framing === 'v2' ? createV2Handshake(args) : createLegacyHandshake(args);
}
function createLegacyHandshake(args) {
    const { primitives } = args;
    const parseJson = args.parseJson ?? JSON.parse;
    const serializeJson = args.serializeJson ?? JSON.stringify;
    const mayUseLegacy = () => args.legacyGate?.mayUseLegacy() ?? true;
    const keyPair = args.clientKeyPair ?? primitives.generateKeyPair();
    const channel = createPairedE2EELegacyChannel(primitives, primitives.deriveSharedKey(keyPair.secretKey, primitives.publicKeyFromBase64(args.hostPublicKeyB64)));
    return {
        framing: 'legacy',
        channel,
        helloText: () => {
            if (!mayUseLegacy()) {
                throw new PairedE2EEDowngradeError('hello');
            }
            return JSON.stringify({
                type: 'e2ee_hello',
                publicKeyB64: primitives.publicKeyToBase64(keyPair.publicKey)
            });
        },
        acceptReadyText: (frame) => {
            const ready = parseJsonOrUndefined(frame, parseJson);
            if (ready === undefined) {
                return 'invalid';
            }
            if (readStringField(ready, 'type') !== 'e2ee_ready') {
                return 'unexpected';
            }
            // Another socket may have latched v2 while this one dialed; recheck downgrade policy.
            return mayUseLegacy() ? 'accepted' : 'downgrade_refused';
        },
        transcriptHashB64: () => null,
        sealAuth: async (credential, options) => {
            if (credential.kind !== 'token') {
                throw new Error('device-key credentials need E2EE v2');
            }
            if (!mayUseLegacy()) {
                throw new PairedE2EEDowngradeError('auth');
            }
            return channel.sealText(serializeJson({
                type: 'e2ee_auth',
                deviceToken: credential.deviceToken,
                ...(options?.clientCapabilities ? { clientCapabilities: options.clientCapabilities } : {})
            }));
        },
        classifyAuthReply: (plaintext) => {
            const reply = parseJsonOrUndefined(plaintext, parseJson);
            if (reply === undefined) {
                return { kind: 'invalid' };
            }
            const type = readStringField(reply, 'type');
            return type === 'e2ee_authenticated' ? { kind: 'authenticated' } : rejectedReply(reply);
        }
    };
}
function createPairedE2EELegacyChannel(primitives, sharedKey) {
    return {
        sealText: (plaintext) => primitives.sealLegacyText(plaintext, sharedKey),
        openText: (frame) => primitives.openLegacyText(frame, sharedKey),
        sealBinary: (plaintext) => primitives.sealLegacyBinary(plaintext, sharedKey),
        openBinary: (frame) => primitives.openLegacyBinary(frame, sharedKey)
    };
}
function createV2Handshake(args) {
    const parseJson = args.parseJson ?? JSON.parse;
    const serializeJson = args.serializeJson ?? JSON.stringify;
    if (!args.transport) {
        throw new Error('E2EE v2 handshake requires a transport context');
    }
    const session = paired_e2ee_v2_client_session_1.PairedE2EEV2ClientSession.create({
        primitives: args.primitives,
        desktopPublicKeyB64: args.hostPublicKeyB64,
        transport: args.transport,
        relayHostId: args.relayHostId,
        clientKeyPair: args.clientKeyPair,
        clientNonce: args.clientNonce
    });
    return {
        framing: 'v2',
        channel: session,
        helloText: () => JSON.stringify(session.hello),
        acceptReadyText: (frame) => {
            const ready = parseJsonOrUndefined(frame, parseJson);
            if (ready === undefined) {
                return 'invalid';
            }
            return session.acceptReady(ready) ? 'accepted' : 'unexpected';
        },
        transcriptHashB64: () => (session.isReady ? session.transcriptHashB64 : null),
        sealAuth: async (credential, options) => {
            // Why read first: throws before the ready, so nothing is signed without a transcript.
            const transcriptHashB64 = session.transcriptHashB64;
            if (credential.kind === 'token') {
                // Why opt-in: mobile v2 token auth stays the exact key set every v2 host accepts.
                const capabilities = args.v2TokenCapabilities ? options?.clientCapabilities : undefined;
                return session.sealText(serializeJson({
                    ...pairedE2EEV2TokenAuth(transcriptHashB64, credential.deviceToken),
                    ...(capabilities ? { clientCapabilities: capabilities } : {})
                }));
            }
            const signature = await credential.sign((0, device_key_proof_1.encodeDeviceProofMessage)('auth', args.primitives.decodeBase64(transcriptHashB64)));
            return session.sealText(serializeJson({
                type: 'e2ee_auth',
                v: 2,
                transcriptHashB64,
                deviceId: credential.deviceId,
                deviceProofB64: args.primitives.encodeBase64(signature),
                ...(options?.clientCapabilities ? { clientCapabilities: options.clientCapabilities } : {})
            }));
        },
        classifyAuthReply: (plaintext) => {
            const reply = parseJsonOrUndefined(plaintext, parseJson);
            return reply === undefined
                ? { kind: 'invalid' }
                : classifyPairedE2EEV2AuthReply(reply, session.transcriptHashB64);
        }
    };
}
function pairedE2EEV2TokenAuth(transcriptHashB64, deviceToken) {
    return { type: 'e2ee_auth', v: 2, transcriptHashB64, deviceToken };
}
function classifyPairedE2EEV2AuthReply(reply, transcriptHashB64) {
    // Why exact: the authenticated reply must echo this session's transcript.
    const authenticated = isRecord(reply) &&
        Object.keys(reply).sort().join(',') === 'transcriptHashB64,type,v' &&
        reply.type === 'e2ee_authenticated' &&
        reply.v === 2 &&
        reply.transcriptHashB64 === transcriptHashB64;
    return authenticated ? { kind: 'authenticated' } : rejectedReply(reply);
}
function parseJsonOrUndefined(text, parseJson) {
    try {
        return parseJson(text);
    }
    catch {
        return undefined;
    }
}
function isRecord(value) {
    return typeof value === 'object' && value !== null;
}
function readStringField(value, field) {
    if (!isRecord(value)) {
        return null;
    }
    const fieldValue = value[field];
    return typeof fieldValue === 'string' ? fieldValue : null;
}
function rejectedReply(reply) {
    const errorDeviceId = isRecord(reply) ? readStringField(reply.error, 'deviceId') : null;
    return {
        kind: 'rejected',
        type: readStringField(reply, 'type'),
        errorCode: readErrorCode(reply),
        ...(errorDeviceId ? { errorDeviceId } : {})
    };
}
function readErrorCode(value) {
    return isRecord(value) ? readStringField(value.error, 'code') : null;
}
