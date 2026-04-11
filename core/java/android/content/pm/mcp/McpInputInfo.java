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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import android.annotation.IntDef;

/**
 * Describes an input parameter for an MCP tool.
 *
 * <p>Maps to the JSON Schema "properties" entries in the MCP tool's
 * inputSchema. Declared in the manifest as child {@code <input>} elements
 * of a {@code <tool>}.
 *
 * <pre>{@code
 * <input android:name="recipient"
 *        android:type="string"
 *        android:required="true"
 *        android:description="Contact name or phone number" />
 * }</pre>
 */
/** @hide */
public final class McpInputInfo implements Parcelable {

    @IntDef({TYPE_STRING, TYPE_NUMBER, TYPE_INTEGER, TYPE_BOOLEAN, TYPE_ARRAY, TYPE_OBJECT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface InputType {}

    public static final int TYPE_STRING = 0;
    public static final int TYPE_NUMBER = 1;
    public static final int TYPE_INTEGER = 2;
    public static final int TYPE_BOOLEAN = 3;
    public static final int TYPE_ARRAY = 4;
    public static final int TYPE_OBJECT = 5;

    /** Parameter name, used as the JSON key in tool_call arguments. */
    public final String name;

    /** Human-readable description for LLM context. */
    public final String description;

    /** JSON Schema type. */
    @InputType
    public final int type;

    /** Whether this parameter is required. */
    public final boolean required;

    /**
     * Optional enum values. If non-null, the input must be one of these values.
     * Corresponds to JSON Schema "enum".
     */
    public final String[] enumValues;

    public McpInputInfo(String name, String description, @InputType int type,
            boolean required, String[] enumValues) {
        this.name = name;
        this.description = description;
        this.type = type;
        this.required = required;
        this.enumValues = enumValues;
    }

    private McpInputInfo(Parcel in) {
        name = in.readString();
        description = in.readString();
        type = in.readInt();
        required = in.readBoolean();
        enumValues = in.createStringArray();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(name);
        dest.writeString(description);
        dest.writeInt(type);
        dest.writeBoolean(required);
        dest.writeStringArray(enumValues);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** Convert the type int to its JSON Schema string. */
    public String typeToString() {
        switch (type) {
            case TYPE_STRING: return "string";
            case TYPE_NUMBER: return "number";
            case TYPE_INTEGER: return "integer";
            case TYPE_BOOLEAN: return "boolean";
            case TYPE_ARRAY: return "array";
            case TYPE_OBJECT: return "object";
            default: return "string";
        }
    }

    /** Parse a type string from the manifest into an @InputType int. */
    public static @InputType int parseType(String typeStr) {
        if (typeStr == null) return TYPE_STRING;
        switch (typeStr) {
            case "number": return TYPE_NUMBER;
            case "integer": return TYPE_INTEGER;
            case "boolean": return TYPE_BOOLEAN;
            case "array": return TYPE_ARRAY;
            case "object": return TYPE_OBJECT;
            default: return TYPE_STRING;
        }
    }

    public static final Creator<McpInputInfo> CREATOR = new Creator<>() {
        @Override
        public McpInputInfo createFromParcel(Parcel in) {
            return new McpInputInfo(in);
        }

        @Override
        public McpInputInfo[] newArray(int size) {
            return new McpInputInfo[size];
        }
    };

    @Override
    public String toString() {
        return "McpInputInfo{name=" + name + ", type=" + typeToString()
                + ", required=" + required + "}";
    }
}
