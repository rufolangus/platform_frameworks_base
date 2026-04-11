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
import java.util.Collections;
import java.util.List;

/**
 * Information about an MCP server declared in an application's manifest.
 *
 * <p>Apps declare MCP servers via {@code <mcp-server>} elements inside
 * {@code <application>} in their {@code AndroidManifest.xml}. The system
 * parses these at install time and caches them in the MCP Tool Registry.
 */
/** @hide */
public final class McpServerInfo implements Parcelable {

    /** Fully qualified class name of the backing service. */
    public final String name;

    /** Package name of the declaring application. */
    public final String packageName;

    /** Human-readable description of this MCP server. */
    public final String description;

    /**
     * Permission required for the LLM System Service to bind to this server.
     * Null means no permission is required.
     */
    public final String permission;

    /** MCP protocol version this server conforms to (e.g., "2024-11-05"). */
    public final String protocolVersion;

    /** Tools declared by this MCP server. Unmodifiable. */
    public final List<McpToolInfo> tools;

    /** Resources declared by this MCP server. Unmodifiable. */
    public final List<McpResourceInfo> resources;

    public McpServerInfo(String name, String packageName, String description,
            String permission, String protocolVersion,
            List<McpToolInfo> tools, List<McpResourceInfo> resources) {
        this.name = name;
        this.packageName = packageName;
        this.description = description;
        this.permission = permission;
        this.protocolVersion = protocolVersion;
        this.tools = Collections.unmodifiableList(
                tools != null ? new ArrayList<>(tools) : new ArrayList<>());
        this.resources = Collections.unmodifiableList(
                resources != null ? new ArrayList<>(resources) : new ArrayList<>());
    }

    private McpServerInfo(Parcel in) {
        name = in.readString();
        packageName = in.readString();
        description = in.readString();
        permission = in.readString();
        protocolVersion = in.readString();
        tools = Collections.unmodifiableList(
                in.createTypedArrayList(McpToolInfo.CREATOR));
        resources = Collections.unmodifiableList(
                in.createTypedArrayList(McpResourceInfo.CREATOR));
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(name);
        dest.writeString(packageName);
        dest.writeString(description);
        dest.writeString(permission);
        dest.writeString(protocolVersion);
        dest.writeTypedList(tools);
        dest.writeTypedList(resources);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<McpServerInfo> CREATOR = new Creator<McpServerInfo>() {
        @Override
        public McpServerInfo createFromParcel(Parcel in) {
            return new McpServerInfo(in);
        }

        @Override
        public McpServerInfo[] newArray(int size) {
            return new McpServerInfo[size];
        }
    };

    @Override
    public String toString() {
        return "McpServerInfo{"
                + "name=" + name
                + ", packageName=" + packageName
                + ", tools=" + tools.size()
                + ", resources=" + resources.size()
                + "}";
    }
}
