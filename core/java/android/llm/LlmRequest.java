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

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulates a request to the LLM System Service.
 *
 * <p>Built via {@link LlmRequest.Builder}:
 * <pre>{@code
 * LlmRequest request = new LlmRequest.Builder("What meetings do I have today?")
 *         .setMaxTokens(1024)
 *         .setTemperature(0.7f)
 *         .setSystemPrompt("You are a helpful Android assistant.")
 *         .enableToolUse(true)
 *         .build();
 * }</pre>
 */
/** @hide */
public final class LlmRequest implements Parcelable {

    /** The user's prompt / message. */
    public final String prompt;

    /** Optional system prompt prepended to the conversation. */
    public final String systemPrompt;

    /** Maximum tokens to generate. 0 = model default. */
    public final int maxTokens;

    /** Sampling temperature. 0.0 = greedy, 1.0 = creative. */
    public final float temperature;

    /** Whether the LLM may invoke MCP tools to fulfill this request. */
    public final boolean toolUseEnabled;

    /**
     * If non-null, restrict tool use to only these tool names.
     * Null means all registered tools are available.
     */
    public final List<String> allowedTools;

    /**
     * Optional conversation history as a JSON array of messages.
     * Each message: {"role": "user"|"assistant"|"tool", "content": "..."}
     * Null for single-turn requests.
     */
    public final String conversationJson;

    /**
     * If true, the service should return structured server UI JSON
     * (for the launcher) instead of plain text.
     */
    public final boolean requestServerUi;

    private LlmRequest(Builder builder) {
        this.prompt = builder.prompt;
        this.systemPrompt = builder.systemPrompt;
        this.maxTokens = builder.maxTokens;
        this.temperature = builder.temperature;
        this.toolUseEnabled = builder.toolUseEnabled;
        this.allowedTools = builder.allowedTools;
        this.conversationJson = builder.conversationJson;
        this.requestServerUi = builder.requestServerUi;
    }

    private LlmRequest(Parcel in) {
        prompt = in.readString();
        systemPrompt = in.readString();
        maxTokens = in.readInt();
        temperature = in.readFloat();
        toolUseEnabled = in.readBoolean();
        allowedTools = in.createStringArrayList();
        conversationJson = in.readString();
        requestServerUi = in.readBoolean();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(prompt);
        dest.writeString(systemPrompt);
        dest.writeInt(maxTokens);
        dest.writeFloat(temperature);
        dest.writeBoolean(toolUseEnabled);
        dest.writeStringList(allowedTools);
        dest.writeString(conversationJson);
        dest.writeBoolean(requestServerUi);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<LlmRequest> CREATOR = new Creator<>() {
        @Override
        public LlmRequest createFromParcel(Parcel in) {
            return new LlmRequest(in);
        }

        @Override
        public LlmRequest[] newArray(int size) {
            return new LlmRequest[size];
        }
    };

    /**
     * Builder for LlmRequest.
     */
    public static final class Builder {
        private final String prompt;
        private String systemPrompt;
        private int maxTokens = 0;
        private float temperature = 0.7f;
        private boolean toolUseEnabled = true;
        private List<String> allowedTools;
        private String conversationJson;
        private boolean requestServerUi = false;

        public Builder(String prompt) {
            if (prompt == null) throw new NullPointerException("prompt");
            this.prompt = prompt;
        }

        public Builder setSystemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder setTemperature(float temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder enableToolUse(boolean enabled) {
            this.toolUseEnabled = enabled;
            return this;
        }

        public Builder setAllowedTools(List<String> tools) {
            this.allowedTools = tools != null ? new ArrayList<>(tools) : null;
            return this;
        }

        public Builder setConversationJson(String json) {
            this.conversationJson = json;
            return this;
        }

        public Builder setRequestServerUi(boolean requestServerUi) {
            this.requestServerUi = requestServerUi;
            return this;
        }

        public LlmRequest build() {
            return new LlmRequest(this);
        }
    }
}
