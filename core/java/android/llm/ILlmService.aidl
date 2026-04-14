/*
 * Copyright (C) 2024 The AAOSP Project
 * Licensed under the Apache License, Version 2.0
 */
package android.llm;

import android.llm.ILlmResponseCallback;
import android.llm.LlmRequest;

/** @hide */
interface ILlmService {
    String submit(in LlmRequest request, ILlmResponseCallback callback);
    void cancel(String sessionId);
    String getAvailableServers();
    boolean isReady();
    String getModelInfo();

    /**
     * Resolve a pending HITL consent prompt for a tool call.
     *
     * @param sessionId The active LLM session that is parked on consent.
     * @param toolName  The tool the model wants to invoke.
     * @param decision  1 = ALLOW, 2 = DENY (other values treated as DENY).
     * @param scope     0 = ONCE (this call only), 1 = SESSION (this conversation),
     *                  2 = FOREVER (until user revokes). FOREVER is rejected by the
     *                  service for tools authored as mcpRequiresConfirmation=true
     *                  (write/destructive intent) and downgraded to SESSION.
     */
    void confirmToolCall(String sessionId, String toolName, int decision, int scope);

    /**
     * Revoke a previously persisted consent grant. Next call to the same
     * (package, tool) re-prompts the user. Intended for a future Settings →
     * AI → Tool Access surface; reachable today via `cmd llm revoke <pkg>
     * <tool>` for testing.
     */
    void revokeToolGrant(String packageName, String toolName);

    /**
     * Returns up to {@code limit} most recent tool-call audit entries as
     * a JSON array. Each element: {sessionId, packageName, toolName,
     * argsJson, resultJson, status, consentDecision, durationMs, ts}.
     */
    String getRecentAuditCalls(int limit);

    /**
     * Mark a session as ended. Clears SCOPE_SESSION consent grants and
     * any in-flight consent gates parked for this session. Called by
     * the launcher when the user starts a new chat or cancels a
     * conversation. Idempotent — safe to call on an already-ended id.
     */
    void endSession(String sessionId);
}
