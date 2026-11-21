"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.structuredAgentSessionPayloadFingerprint = structuredAgentSessionPayloadFingerprint;
exports.structuredAgentSessionDomainFingerprint = structuredAgentSessionDomainFingerprint;
exports.structuredAgentSessionCreateFingerprint = structuredAgentSessionCreateFingerprint;
exports.createStructuredAgentSessionOperationId = createStructuredAgentSessionOperationId;
const sha256_1 = require("../../../hashing/sha256");
function canonicalize(value) {
    if (value === null || typeof value !== 'object') {
        return JSON.stringify(value ?? null);
    }
    if (Array.isArray(value)) {
        return `[${value.map(canonicalize).join(',')}]`;
    }
    const entries = Object.entries(value)
        .filter(([, entry]) => entry !== undefined)
        .sort(([left], [right]) => (left < right ? -1 : left > right ? 1 : 0));
    return `{${entries.map(([key, entry]) => `${JSON.stringify(key)}:${canonicalize(entry)}`).join(',')}}`;
}
function structuredAgentSessionPayloadFingerprint(input) {
    const bytes = (0, sha256_1.sha256)(new TextEncoder().encode(canonicalize({ method: input.method, sessionId: input.sessionId, fields: input.fields })));
    return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
}
function structuredAgentSessionDomainFingerprint(input) {
    return structuredAgentSessionPayloadFingerprint({
        method: input.domain,
        sessionId: input.sessionId,
        fields: input.fields
    });
}
function structuredAgentSessionCreateFingerprint(input) {
    return structuredAgentSessionPayloadFingerprint({
        method: 'agentSession.create',
        sessionId: input.sessionId,
        fields: {
            worktree: input.worktree,
            agent: input.agent,
            // Dropping undefined preserves blank-create digests; resume intent must not replay as blank create.
            resumeFrom: input.resumeFrom,
            forkFrom: input.forkFrom,
            // Include tabId in the host digest; retries replay its recorded host-owned ID.
            tabId: input.tabId,
            firstMessage: input.firstMessage,
            optionsMode: input.optionsMode,
            options: input.options
        }
    });
}
function createStructuredAgentSessionOperationId(randomUuid, now = Date.now()) {
    const timestamp = Math.trunc(now).toString();
    const entropy = randomUuid().replaceAll('-', '').toLowerCase();
    if (!/^\d{13}$/.test(timestamp) || !/^[0-9a-f]{32}$/.test(entropy)) {
        throw new Error('Unable to create a durable operation id');
    }
    return `${timestamp}-${entropy}`;
}
