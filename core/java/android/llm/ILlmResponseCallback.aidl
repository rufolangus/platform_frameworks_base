/*
 * Copyright (C) 2024 The AAOSP Project
 * Licensed under the Apache License, Version 2.0
 */
package android.llm;

/** @hide */
interface ILlmResponseCallback {
    void onToken(String token);
    void onToolCall(String toolName, String argumentsJson);
    void onToolResult(String toolName, String resultJson);
    void onServerUi(String serverUiJson);
    void onComplete(String fullResponse);
    void onError(int errorCode, String message);
}
