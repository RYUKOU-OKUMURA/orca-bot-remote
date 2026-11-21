"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.createFixedKeyLegacyHandshakeForTest = createFixedKeyLegacyHandshakeForTest;
const paired_e2ee_client_handshake_1 = require("../../../vendor/orca/shared/remote-runtime/e2ee/paired-e2ee-client-handshake");
const paired_e2ee_node_primitives_1 = require("../../../vendor/orca/shared/remote-runtime/e2ee/node/paired-e2ee-node-primitives");
// Fixed legacy shared key lets tests seal host frames without ECDH.
function createFixedKeyLegacyHandshakeForTest(sharedKey) {
    return {
        ...(0, paired_e2ee_client_handshake_1.createPairedE2EEClientHandshake)({
            primitives: paired_e2ee_node_primitives_1.NODE_PAIRED_E2EE_PRIMITIVES,
            hostPublicKeyB64: Buffer.alloc(32, 9).toString('base64'),
            framing: 'legacy'
        }),
        channel: (0, paired_e2ee_client_handshake_1.createPairedE2EELegacyChannel)(paired_e2ee_node_primitives_1.NODE_PAIRED_E2EE_PRIMITIVES, sharedKey)
    };
}
