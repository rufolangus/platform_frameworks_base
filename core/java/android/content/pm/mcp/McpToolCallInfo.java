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

package android.content.pm.mcp;

import android.annotation.IntDef;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A single tool-call event in an LLM session — covers the lifecycle from
 * the moment the LLM emits a {@code <tool_call>} through the dispatcher
 * binding to the owning app's {@link android.llm.IMcpToolProvider} and
 * the result coming back.
 *
 * <p>Delivered to clients via
 * {@link android.llm.ILlmResponseCallback#onToolCall(McpToolCallInfo)} when
 * dispatch starts and
 * {@link android.llm.ILlmResponseCallback#onToolResult(McpToolCallInfo)} when
 * it completes (or fails). The {@link #status} field tells you which.
 *
 * <p>The launcher uses {@link #packageName} to render the owning app's
 * icon + label next to {@link #toolName}, so the user always knows which
 * app is being asked to do what on their behalf — the human-in-the-loop
 * audit surface.
 */
/** @hide */
public final class McpToolCallInfo implements Parcelable {

    /** Dispatcher has resolved the tool, about to bind & invoke. */
    public static final int STATUS_STARTED   = 0;

    /** Tool returned successfully. {@link #resultJson} is populated. */
    public static final int STATUS_COMPLETED = 1;

    /** Bind failed, timeout, or the tool returned an error JSON. */
    public static final int STATUS_FAILED    = 2;

    /**
     * Tool requires a runtime permission the owning app doesn't yet
     * hold. The HITL consent UI handles this. Reserved for the next
     * milestone — not yet emitted by the current dispatcher.
     */
    public static final int STATUS_PERMISSION_REQUIRED = 3;

    /** @hide */
    @IntDef({STATUS_STARTED, STATUS_COMPLETED, STATUS_FAILED,
            STATUS_PERMISSION_REQUIRED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Status {}

    /** LLM session this call belongs to. Matches {@code submit()}'s return. */
    public final String sessionId;

    /** Tool the LLM asked for (e.g. {@code "search_contacts"}). */
    public final String toolName;

    /** Owning app's package (e.g. {@code "com.android.contacts.mcp"}). */
    public final String packageName;

    /** Fully-qualified service class for the {@code IMcpToolProvider}. */
    public final String serviceName;

    /** Tool arguments as the LLM emitted them. JSON object. */
    public final String argumentsJson;

    /**
     * Tool result, JSON. Populated only for {@link #STATUS_COMPLETED} and
     * {@link #STATUS_FAILED} (the latter usually wraps an error string).
     * Null for {@link #STATUS_STARTED}.
     */
    public final String resultJson;

    /** Wall-clock time the event was emitted. */
    public final long timestampMillis;

    /** See {@link Status}. */
    public final @Status int status;

    /**
     * Round-trip duration from {@link #STATUS_STARTED} to terminal
     * status, in milliseconds. {@code -1} on the {@code STARTED} event.
     */
    public final int durationMillis;

    /**
     * Zero-based position of this call within a chained agentic loop.
     * {@code 0} for single-call exchanges. Lets clients render each
     * step in the chain accumulating in chat scroll instead of one
     * card overwriting the previous.
     */
    public final int iterationIndex;

    public McpToolCallInfo(String sessionId, String toolName,
            String packageName, String serviceName,
            String argumentsJson, String resultJson,
            long timestampMillis, @Status int status, int durationMillis,
            int iterationIndex) {
        this.sessionId = sessionId;
        this.toolName = toolName;
        this.packageName = packageName;
        this.serviceName = serviceName;
        this.argumentsJson = argumentsJson;
        this.resultJson = resultJson;
        this.timestampMillis = timestampMillis;
        this.status = status;
        this.durationMillis = durationMillis;
        this.iterationIndex = iterationIndex;
    }

    private McpToolCallInfo(Parcel in) {
        sessionId = in.readString();
        toolName = in.readString();
        packageName = in.readString();
        serviceName = in.readString();
        argumentsJson = in.readString();
        resultJson = in.readString();
        timestampMillis = in.readLong();
        status = in.readInt();
        durationMillis = in.readInt();
        iterationIndex = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(sessionId);
        dest.writeString(toolName);
        dest.writeString(packageName);
        dest.writeString(serviceName);
        dest.writeString(argumentsJson);
        dest.writeString(resultJson);
        dest.writeLong(timestampMillis);
        dest.writeInt(status);
        dest.writeInt(durationMillis);
        dest.writeInt(iterationIndex);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<McpToolCallInfo> CREATOR =
            new Creator<McpToolCallInfo>() {
        @Override
        public McpToolCallInfo createFromParcel(Parcel in) {
            return new McpToolCallInfo(in);
        }

        @Override
        public McpToolCallInfo[] newArray(int size) {
            return new McpToolCallInfo[size];
        }
    };

    @Override
    public String toString() {
        return "McpToolCallInfo{"
                + "tool=" + toolName
                + ", pkg=" + packageName
                + ", status=" + status
                + ", durMs=" + durationMillis
                + "}";
    }
}
