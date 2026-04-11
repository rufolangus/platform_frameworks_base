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

import android.llm.ILlmResponseCallback;
import android.llm.LlmRequest;
import android.content.pm.mcp.McpServerInfo;

/**
 * System service interface for the LLM System Service.
 *
 * Accessed by apps and the launcher via LlmManager, which wraps this
 * Binder interface with a friendlier API.
 *
 * The service runs in system_server and delegates inference to a native
 * llama.cpp daemon. It discovers MCP tools from the McpRegistry and
 * orchestrates tool calls to apps via IMcpToolProvider.
 */
interface ILlmService {

    /**
     * Submit a prompt to the LLM. The response is delivered asynchronously
     * via the callback. The service may invoke MCP tools on installed apps
     * as part of fulfilling the request.
     *
     * @param request the prompt and configuration
     * @param callback receives streamed tokens and the final response
     * @return a session ID that can be used to cancel the request
     */
    String submit(in LlmRequest request, ILlmResponseCallback callback);

    /**
     * Cancel an in-flight request.
     *
     * @param sessionId the session ID returned by submit()
     */
    void cancel(String sessionId);

    /**
     * Get all MCP tools currently available across installed apps.
     * Used by the launcher to show available capabilities.
     *
     * @return list of MCP server declarations from all installed packages
     */
    List<McpServerInfo> getAvailableServers();

    /**
     * Check whether the LLM runtime is loaded and ready for inference.
     */
    boolean isReady();

    /**
     * Get info about the loaded model (name, parameter count, quantization).
     * Returns null if no model is loaded.
     */
    String getModelInfo();
}
