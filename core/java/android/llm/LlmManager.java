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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemService;
import android.content.Context;
import android.content.pm.mcp.McpServerInfo;
import android.os.RemoteException;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * SDK-side manager for the LLM System Service.
 *
 * <p>Obtain an instance via {@link Context#getSystemService}:
 * <pre>{@code
 * LlmManager llm = context.getSystemService(LlmManager.class);
 * }</pre>
 *
 * <h3>Simple text generation</h3>
 * <pre>{@code
 * LlmRequest request = new LlmRequest.Builder("Summarize my notifications")
 *         .setMaxTokens(256)
 *         .build();
 *
 * llm.submit(request, executor, new LlmManager.Callback() {
 *     @Override
 *     public void onToken(String token) {
 *         // Update UI with streamed token
 *     }
 *
 *     @Override
 *     public void onComplete(String fullResponse) {
 *         // Final response
 *     }
 *
 *     @Override
 *     public void onError(int errorCode, String message) {
 *         // Handle error
 *     }
 * });
 * }</pre>
 *
 * <h3>Launcher: request structured UI</h3>
 * <pre>{@code
 * LlmRequest request = new LlmRequest.Builder("Show me today's schedule")
 *         .setRequestServerUi(true)
 *         .enableToolUse(true)
 *         .build();
 *
 * llm.submit(request, executor, new LlmManager.Callback() {
 *     @Override
 *     public void onServerUi(String serverUiJson) {
 *         // Render the structured UI JSON
 *     }
 * });
 * }</pre>
 */
@SystemService(Context.LLM_SERVICE)
public class LlmManager {

    private final ILlmService mService;

    /** @hide */
    public LlmManager(ILlmService service) {
        mService = service;
    }

    /**
     * Submit a prompt to the LLM with streaming callbacks.
     *
     * @param request the prompt and configuration
     * @param executor executor on which callback methods are invoked
     * @param callback receives tokens, tool calls, and the final response
     * @return a session handle that can be used to cancel the request
     */
    @NonNull
    public Session submit(@NonNull LlmRequest request,
            @NonNull Executor executor, @NonNull Callback callback) {
        try {
            String sessionId = mService.submit(request,
                    new CallbackWrapper(executor, callback));
            return new Session(sessionId, mService);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get all MCP servers registered across installed apps.
     */
    @NonNull
    public List<McpServerInfo> getAvailableServers() {
        try {
            List<McpServerInfo> servers = mService.getAvailableServers();
            return servers != null ? servers : Collections.emptyList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Check whether the LLM runtime is loaded and ready.
     */
    public boolean isReady() {
        try {
            return mService.isReady();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get info about the currently loaded model.
     *
     * @return model description string, or null if no model is loaded
     */
    @Nullable
    public String getModelInfo() {
        try {
            return mService.getModelInfo();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Handle to an in-flight LLM request. Allows cancellation.
     */
    public static final class Session {
        private final String mSessionId;
        private final ILlmService mService;

        Session(String sessionId, ILlmService service) {
            mSessionId = sessionId;
            mService = service;
        }

        /** Get the unique session ID. */
        @NonNull
        public String getId() {
            return mSessionId;
        }

        /** Cancel this request. No further callbacks will be delivered. */
        public void cancel() {
            try {
                mService.cancel(mSessionId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Callback for receiving LLM responses. All methods are optional —
     * override only what you need.
     */
    public abstract static class Callback {
        /** Called for each streamed token. */
        public void onToken(@NonNull String token) {}

        /** Called when the LLM invokes an MCP tool. */
        public void onToolCall(@NonNull String toolName,
                @NonNull String argumentsJson) {}

        /** Called when a tool call completes. */
        public void onToolResult(@NonNull String toolName,
                @NonNull String resultJson) {}

        /** Called when the LLM produces structured UI JSON. */
        public void onServerUi(@NonNull String serverUiJson) {}

        /** Called when generation is complete. */
        public void onComplete(@NonNull String fullResponse) {}

        /** Called on error. */
        public void onError(int errorCode, @NonNull String message) {}
    }

    /**
     * Wraps a Callback with an Executor to ensure callbacks are
     * delivered on the caller's chosen thread.
     */
    private static class CallbackWrapper extends ILlmResponseCallback.Stub {
        private final Executor mExecutor;
        private final Callback mCallback;

        CallbackWrapper(Executor executor, Callback callback) {
            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public void onToken(String token) {
            mExecutor.execute(() -> mCallback.onToken(token));
        }

        @Override
        public void onToolCall(String toolName, String argumentsJson) {
            mExecutor.execute(() -> mCallback.onToolCall(toolName, argumentsJson));
        }

        @Override
        public void onToolResult(String toolName, String resultJson) {
            mExecutor.execute(() -> mCallback.onToolResult(toolName, resultJson));
        }

        @Override
        public void onServerUi(String serverUiJson) {
            mExecutor.execute(() -> mCallback.onServerUi(serverUiJson));
        }

        @Override
        public void onComplete(String fullResponse) {
            mExecutor.execute(() -> mCallback.onComplete(fullResponse));
        }

        @Override
        public void onError(int errorCode, String message) {
            mExecutor.execute(() -> mCallback.onError(errorCode, message));
        }
    }
}
