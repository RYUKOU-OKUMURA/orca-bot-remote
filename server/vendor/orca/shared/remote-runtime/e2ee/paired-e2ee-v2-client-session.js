"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.PairedE2EEV2ClientSession = void 0;
const mobile_e2ee_v2_contract_1 = require("./mobile-e2ee-v2-contract");
const mobile_e2ee_v2_framing_1 = require("./mobile-e2ee-v2-framing");
class PairedE2EEV2ClientSession {
    primitives;
    clientSecretKey;
    pinnedDesktopPublicKey;
    hello;
    inboundCounter = 0n;
    outboundCounter = 0n;
    schedule = null;
    transcriptHashB64Value = null;
    constructor(primitives, clientSecretKey, pinnedDesktopPublicKey, hello) {
        this.primitives = primitives;
        this.clientSecretKey = clientSecretKey;
        this.pinnedDesktopPublicKey = pinnedDesktopPublicKey;
        this.hello = hello;
    }
    static create(args) {
        const { primitives } = args;
        const keyPair = args.clientKeyPair ?? primitives.generateKeyPair();
        const clientNonce = args.clientNonce ?? primitives.randomBytes(32);
        if (clientNonce.length !== 32) {
            throw new Error(`Invalid client nonce length: ${clientNonce.length}`);
        }
        return new PairedE2EEV2ClientSession(primitives, keyPair.secretKey, primitives.publicKeyFromBase64(args.desktopPublicKeyB64), {
            type: 'e2ee_hello',
            v: 2,
            clientPublicKeyB64: primitives.publicKeyToBase64(keyPair.publicKey),
            clientNonceB64: primitives.encodeBase64(clientNonce),
            capabilities: { framing: [2], payloadKinds: ['text', 'binary'] },
            context: {
                protocol: 'orca-mobile-e2ee',
                initiator: 'mobile',
                responder: 'desktop',
                transport: args.transport,
                ...(args.relayHostId ? { relayHostId: args.relayHostId } : {})
            }
        });
    }
    acceptReady(ready) {
        const handshake = (0, mobile_e2ee_v2_contract_1.validateMobileE2EEV2Handshake)(this.hello, ready);
        if (!handshake || !equalBytes(handshake.desktopPublicKey, this.pinnedDesktopPublicKey)) {
            return false;
        }
        this.schedule = this.primitives.deriveV2KeySchedule({
            sharedSecret: this.primitives.deriveSharedKey(this.clientSecretKey, this.pinnedDesktopPublicKey),
            transcript: (0, mobile_e2ee_v2_contract_1.encodeMobileE2EEV2Transcript)(handshake),
            clientNonce: handshake.clientNonce,
            desktopNonce: handshake.desktopNonce
        });
        this.transcriptHashB64Value = this.primitives.encodeBase64(this.schedule.transcriptHash);
        return true;
    }
    get isReady() {
        return this.transcriptHashB64Value !== null;
    }
    get transcriptHashB64() {
        if (!this.transcriptHashB64Value) {
            throw new Error('E2EE v2 ready has not been accepted');
        }
        return this.transcriptHashB64Value;
    }
    openText(frameB64) {
        const frame = this.decodeCanonicalBase64(frameB64);
        if (!frame) {
            return null;
        }
        const plaintext = this.open(frame, 'text');
        if (!plaintext) {
            return null;
        }
        return this.primitives.decodeUtf8
            ? this.primitives.decodeUtf8(plaintext)
            : new TextDecoder().decode(plaintext);
    }
    openBinary(frame) {
        return this.open(frame, 'binary');
    }
    sealText(plaintext) {
        return this.primitives.encodeBase64(this.seal(new TextEncoder().encode(plaintext), 'text'));
    }
    sealBinary(plaintext) {
        return this.seal(plaintext, 'binary');
    }
    open(frame, payloadKind) {
        if (!this.schedule) {
            return null;
        }
        const plaintext = (0, mobile_e2ee_v2_framing_1.openMobileE2EEV2Frame)({
            frame,
            key: this.schedule.desktopToMobileKey,
            sessionId: this.schedule.sessionId,
            direction: 'desktop-to-mobile',
            payloadKind,
            expectedCounter: this.inboundCounter,
            openSecretbox: this.primitives.openSecretbox
        });
        if (plaintext) {
            this.inboundCounter++;
        }
        return plaintext;
    }
    seal(plaintext, payloadKind) {
        if (!this.schedule) {
            throw new Error('E2EE v2 ready has not been accepted');
        }
        const frame = (0, mobile_e2ee_v2_framing_1.sealMobileE2EEV2Frame)({
            payload: plaintext,
            key: this.schedule.mobileToDesktopKey,
            sessionId: this.schedule.sessionId,
            direction: 'mobile-to-desktop',
            payloadKind,
            counter: this.outboundCounter,
            sealSecretbox: this.primitives.sealSecretbox
        });
        this.outboundCounter++;
        return frame;
    }
    decodeCanonicalBase64(value) {
        if (this.primitives.decodeCanonicalBase64) {
            return this.primitives.decodeCanonicalBase64(value);
        }
        try {
            const bytes = this.primitives.decodeBase64(value);
            return this.primitives.encodeBase64(bytes) === value ? bytes : null;
        }
        catch {
            return null;
        }
    }
}
exports.PairedE2EEV2ClientSession = PairedE2EEV2ClientSession;
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
