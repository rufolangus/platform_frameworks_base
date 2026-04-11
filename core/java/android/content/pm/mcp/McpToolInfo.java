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

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

/**
 * Describes a single MCP tool declared in an app's manifest.
 *
 * <p>A tool is a function the LLM can invoke. The app handles execution
 * and returns results via Binder.
 *
 * <pre>{@code
 * <tool android:name="send_message"
 *       android:description="Send a message to a contact">
 *     <input android:name="recipient"
 *            android:type="string"
 *            android:required="true"
 *            android:description="Contact name or phone number" />
 *     <input android:name="body"
 *            android:type="string"
 *            android:required="true"
 *            android:description="Message content" />
 * </tool>
 * }</pre>
 */
/** @hide */
public final class McpToolInfo implements Parcelable {

    /** Tool name, used as the function identifier in MCP tool_call. */
    public final String name;

    /** Human-readable description for LLM context. */
    public final String description;

    /**
     * If non-null, this permission is required in addition to the server-level
     * permission before the LLM Service may invoke this tool.
     */
    public final String permission;

    /**
     * If true, invoking this tool requires explicit user confirmation
     * before execution (e.g., sending a payment).
     */
    public final boolean requiresConfirmation;

    /** Input parameters for this tool. */
    public final List<McpInputInfo> inputs;

    public McpToolInfo(String name, String description, String permission,
            boolean requiresConfirmation, List<McpInputInfo> inputs) {
        this.name = name;
        this.description = description;
        this.permission = permission;
        this.requiresConfirmation = requiresConfirmation;
        this.inputs = inputs != null ? inputs : new ArrayList<>();
    }

    private McpToolInfo(Parcel in) {
        name = in.readString();
        description = in.readString();
        permission = in.readString();
        requiresConfirmation = in.readBoolean();
        inputs = in.createTypedArrayList(McpInputInfo.CREATOR);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(name);
        dest.writeString(description);
        dest.writeString(permission);
        dest.writeBoolean(requiresConfirmation);
        dest.writeTypedList(inputs);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<McpToolInfo> CREATOR = new Creator<>() {
        @Override
        public McpToolInfo createFromParcel(Parcel in) {
            return new McpToolInfo(in);
        }

        @Override
        public McpToolInfo[] newArray(int size) {
            return new McpToolInfo[size];
        }
    };

    @Override
    public String toString() {
        return "McpToolInfo{name=" + name + ", inputs=" + inputs.size() + "}";
    }
}
