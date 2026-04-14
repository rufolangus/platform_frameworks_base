/*
 * Copyright (C) 2024 The AAOSP Project
 * Licensed under the Apache License, Version 2.0
 */
package android.llm;

import android.content.pm.mcp.McpToolCallInfo;

/**
 * @hide
 *
 * All methods are {@code oneway}. {@link LlmManagerService} lives in
 * system_server; outbound binder calls from system_server must be
 * FLAG_ONEWAY or the kernel binder driver throws
 * "Outgoing transactions from this process must be FLAG_ONEWAY" at
 * runtime. The callback surface is fire-and-forget anyway — the
 * launcher side never returns a value — so oneway is the correct
 * semantic and preserves ordering because a single callback object
 * uses a single binder proxy.
 */
interface ILlmResponseCallback {
    /** Streamed model token. Tokens inside <tool_call>…</tool_call> are
     *  suppressed by the dispatcher; clients only see prose-level output. */
    oneway void onToken(String token);

    /** Tool dispatch starting. {@code info.status == STATUS_STARTED}. */
    oneway void onToolCall(in McpToolCallInfo info);

    /** Tool dispatch finished — completed or failed. Inspect {@code info.status}. */
    oneway void onToolResult(in McpToolCallInfo info);

    /** Server-driven UI element emitted by the model (JSON). */
    oneway void onServerUi(String serverUiJson);

    /** Final answer for the session. */
    oneway void onComplete(String fullResponse);

    /** Error path. */
    oneway void onError(int errorCode, String message);
}
