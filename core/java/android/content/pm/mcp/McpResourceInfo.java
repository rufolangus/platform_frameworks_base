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

/**
 * Describes an MCP resource declared in an app's manifest.
 *
 * <p>Resources are data endpoints the LLM can read. They typically map to
 * Android ContentProviders but are surfaced with MCP-compatible metadata.
 *
 * <pre>{@code
 * <resource android:name="contacts"
 *           android:uri="content://com.example.app/contacts"
 *           android:description="User's contact list"
 *           android:mimeType="application/json" />
 * }</pre>
 */
public final class McpResourceInfo implements Parcelable {

    /** Resource name, used as the identifier in MCP resource reads. */
    public final String name;

    /** Content URI to read this resource. */
    public final String uri;

    /** Human-readable description for LLM context. */
    public final String description;

    /** MIME type of the resource content. */
    public final String mimeType;

    /**
     * If non-null, this permission is required to read this resource,
     * in addition to any server-level permission.
     */
    public final String permission;

    public McpResourceInfo(String name, String uri, String description,
            String mimeType, String permission) {
        this.name = name;
        this.uri = uri;
        this.description = description;
        this.mimeType = mimeType;
        this.permission = permission;
    }

    private McpResourceInfo(Parcel in) {
        name = in.readString();
        uri = in.readString();
        description = in.readString();
        mimeType = in.readString();
        permission = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(name);
        dest.writeString(uri);
        dest.writeString(description);
        dest.writeString(mimeType);
        dest.writeString(permission);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<McpResourceInfo> CREATOR = new Creator<>() {
        @Override
        public McpResourceInfo createFromParcel(Parcel in) {
            return new McpResourceInfo(in);
        }

        @Override
        public McpResourceInfo[] newArray(int size) {
            return new McpResourceInfo[size];
        }
    };

    @Override
    public String toString() {
        return "McpResourceInfo{name=" + name + ", uri=" + uri + "}";
    }
}
