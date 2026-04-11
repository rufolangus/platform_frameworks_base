/*
 * Copyright (C) 2024 The AAOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.llm;

/**
 * Callback interface for receiving LLM responses.
 *
 * Implemented by the caller (launcher or app) and passed to
 * ILlmService.submit(). The service calls these methods from a
 * Binder thread pool thread — callers must post to their own
 * handler if they need main-thread delivery.
 */
interface ILlmResponseCallback {

    /**
     * Called for each token as the LLM generates it (streaming).
     *
     * @param token the next generated token/text chunk
     */
    void onToken(String token);

    /**
     * Called when the LLM wants to invoke an MCP tool.
     * The caller can observe tool calls for UI purposes (e.g., showing
     * "Searching contacts..." in the launcher).
     *
     * @param toolName the tool being invoked (short or qualified name)
     * @param argumentsJson JSON object of the tool call arguments
     */
    void onToolCall(String toolName, String argumentsJson);

    /**
     * Called when a tool call completes and the LLM resumes generation.
     *
     * @param toolName the tool that completed
     * @param resultJson JSON string of the tool result
     */
    void onToolResult(String toolName, String resultJson);

    /**
     * Called when the LLM produces a structured UI response (server JSON)
     * intended for the launcher to render.
     *
     * @param serverUiJson JSON describing the UI to render
     */
    void onServerUi(String serverUiJson);

    /**
     * Called when generation is complete.
     *
     * @param fullResponse the complete generated text
     */
    void onComplete(String fullResponse);

    /**
     * Called if an error occurs during generation or tool execution.
     *
     * @param errorCode machine-readable error code
     * @param message human-readable error description
     */
    void onError(int errorCode, String message);
}
