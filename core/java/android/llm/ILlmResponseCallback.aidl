/*
 * Copyright (C) 2024 The AAOSP Project
 * Licensed under the Apache License, Version 2.0
 */
package android.llm;

import android.content.pm.mcp.McpToolCallInfo;

/** @hide */
interface ILlmResponseCallback {
    /** Streamed model token. Tokens inside <tool_call>…</tool_call> are
     *  suppressed by the dispatcher; clients only see prose-level output. */
    void onToken(String token);

    /** Tool dispatch starting. {@code info.status == STATUS_STARTED}. */
    void onToolCall(in McpToolCallInfo info);

    /** Tool dispatch finished — completed or failed. Inspect {@code info.status}. */
    void onToolResult(in McpToolCallInfo info);

    /** Server-driven UI element emitted by the model (JSON). */
    void onServerUi(String serverUiJson);

    /** Final answer for the session. */
    void onComplete(String fullResponse);

    /** Error path. */
    void onError(int errorCode, String message);
}
