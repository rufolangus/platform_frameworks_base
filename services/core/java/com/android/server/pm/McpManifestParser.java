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

package com.android.server.pm;

import android.content.pm.mcp.McpInputInfo;
import android.content.pm.mcp.McpResourceInfo;
import android.content.pm.mcp.McpServerInfo;
import android.content.pm.mcp.McpToolInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.util.Log;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses {@code <mcp-server>} elements from AndroidManifest.xml.
 *
 * <p>Called by PackageManagerService during package install/update to extract
 * MCP server declarations and populate the {@link McpRegistry}.
 *
 * <p>Uses {@link TypedArray} via {@link Resources#obtainAttributes} so that
 * {@code @string/} resource references are properly resolved.
 *
 * <p>Expected manifest structure:
 * <pre>{@code
 * <application ...>
 *     <mcp-server android:name=".MyMcpService"
 *                 android:description="@string/mcp_desc"
 *                 android:permission="com.example.permission.MCP"
 *                 android:mcpVersion="2024-11-05">
 *
 *         <tool android:name="send_message"
 *               android:description="@string/tool_send_desc"
 *               android:mcpRequiresConfirmation="true">
 *             <input android:name="recipient"
 *                    android:mcpType="string"
 *                    android:mcpRequired="true"
 *                    android:description="@string/input_recipient_desc" />
 *         </tool>
 *
 *         <resource android:name="recent_messages"
 *                   android:mcpUri="content://com.example/messages/recent"
 *                   android:description="@string/resource_msgs_desc"
 *                   android:mimeType="application/json" />
 *     </mcp-server>
 * </application>
 * }</pre>
 */
public class McpManifestParser {

    private static final String TAG = "McpManifestParser";

    private static final String TAG_MCP_SERVER = "mcp-server";
    private static final String TAG_TOOL = "tool";
    private static final String TAG_INPUT = "input";
    private static final String TAG_RESOURCE = "resource";

    /**
     * Parse all {@code <mcp-server>} elements from a package's manifest.
     *
     * <p>Must be called while the parser is positioned inside the
     * {@code <application>} element. The caller (ParsingPackageUtils) should
     * delegate to this method when it encounters an {@code <mcp-server>} tag.
     *
     * @param res the package's Resources, used for attribute resolution
     * @param parser XML parser positioned at an {@code <mcp-server>} start tag
     * @param packageName the declaring package's name
     * @return the parsed MCP server declaration, or null on error
     */
    public static McpServerInfo parseMcpServer(Resources res,
            XmlResourceParser parser, String packageName)
            throws XmlPullParserException, IOException {

        TypedArray sa = res.obtainAttributes(parser,
                R.styleable.AndroidManifestMcpServer);

        String name = sa.getNonConfigurationString(
                R.styleable.AndroidManifestMcpServer_name, 0);
        String description = sa.getNonConfigurationString(
                R.styleable.AndroidManifestMcpServer_description, 0);
        String permission = sa.getNonConfigurationString(
                R.styleable.AndroidManifestMcpServer_permission, 0);
        String version = sa.getNonConfigurationString(
                R.styleable.AndroidManifestMcpServer_mcpVersion, 0);

        sa.recycle();

        if (name == null) {
            Log.w(TAG, "Skipping <mcp-server> without android:name in "
                    + packageName);
            skipCurrentTag(parser);
            return null;
        }

        // Resolve relative class name (e.g., ".MyMcpService" -> "com.example.MyMcpService")
        if (name.startsWith(".")) {
            name = packageName + name;
        } else if (!name.contains(".")) {
            name = packageName + "." + name;
        }

        List<McpToolInfo> tools = new ArrayList<>();
        List<McpResourceInfo> resources = new ArrayList<>();

        int depth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > depth)) {
            if (type != XmlPullParser.START_TAG) continue;

            switch (parser.getName()) {
                case TAG_TOOL:
                    McpToolInfo tool = parseTool(res, parser);
                    if (tool != null) tools.add(tool);
                    break;
                case TAG_RESOURCE:
                    McpResourceInfo resource = parseResource(res, parser);
                    if (resource != null) resources.add(resource);
                    break;
                default:
                    Log.w(TAG, "Unknown element <" + parser.getName()
                            + "> inside <mcp-server> in " + packageName);
                    skipCurrentTag(parser);
                    break;
            }
        }

        McpServerInfo server = new McpServerInfo(name, packageName, description,
                permission, version, tools, resources);
        Log.i(TAG, "Parsed MCP server: " + server);
        return server;
    }

    private static McpToolInfo parseTool(Resources res,
            XmlResourceParser parser)
            throws XmlPullParserException, IOException {

        TypedArray sa = res.obtainAttributes(parser,
                R.styleable.AndroidManifestMcpTool);

        String name = sa.getNonConfigurationString(
                R.styleable.AndroidManifestMcpTool_name, 0);
        String description = sa.getNonConfigurationString(
                R.styleable.AndroidManifestMcpTool_description, 0);
        String permission = sa.getNonConfigurationString(
                R.styleable.AndroidManifestMcpTool_permission, 0);
        boolean requiresConfirmation = sa.getBoolean(
                R.styleable.AndroidManifestMcpTool_mcpRequiresConfirmation,
                false);

        sa.recycle();

        if (name == null) {
            Log.w(TAG, "Skipping <tool> without android:name");
            skipCurrentTag(parser);
            return null;
        }

        List<McpInputInfo> inputs = new ArrayList<>();
        int depth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > depth)) {
            if (type != XmlPullParser.START_TAG) continue;

            if (TAG_INPUT.equals(parser.getName())) {
                McpInputInfo input = parseInput(res, parser);
                if (input != null) inputs.add(input);
            } else {
                skipCurrentTag(parser);
            }
        }

        return new McpToolInfo(name, description, permission,
                requiresConfirmation, inputs);
    }

    private static McpInputInfo parseInput(Resources res,
            XmlResourceParser parser)
            throws XmlPullParserException, IOException {

        TypedArray sa = res.obtainAttributes(parser,
                R.styleable.AndroidManifestMcpInput);

        String name = sa.getNonConfigurationString(
                R.styleable.AndroidManifestMcpInput_name, 0);
        String description = sa.getNonConfigurationString(
                R.styleable.AndroidManifestMcpInput_description, 0);
        int type = sa.getInt(
                R.styleable.AndroidManifestMcpInput_mcpType,
                McpInputInfo.TYPE_STRING);
        boolean required = sa.getBoolean(
                R.styleable.AndroidManifestMcpInput_mcpRequired, false);
        String enumStr = sa.getNonConfigurationString(
                R.styleable.AndroidManifestMcpInput_mcpEnumValues, 0);

        sa.recycle();

        if (name == null) {
            Log.w(TAG, "Skipping <input> without android:name");
            skipCurrentTag(parser);
            return null;
        }

        String[] enumValues = null;
        if (enumStr != null && !enumStr.isEmpty()) {
            enumValues = enumStr.split("\\|");
        }

        skipCurrentTag(parser);

        return new McpInputInfo(name, description, type, required, enumValues);
    }

    private static McpResourceInfo parseResource(Resources res,
            XmlResourceParser parser)
            throws XmlPullParserException, IOException {

        TypedArray sa = res.obtainAttributes(parser,
                R.styleable.AndroidManifestMcpResource);

        String name = sa.getNonConfigurationString(
                R.styleable.AndroidManifestMcpResource_name, 0);
        String uri = sa.getNonConfigurationString(
                R.styleable.AndroidManifestMcpResource_mcpUri, 0);
        String description = sa.getNonConfigurationString(
                R.styleable.AndroidManifestMcpResource_description, 0);
        String mimeType = sa.getNonConfigurationString(
                R.styleable.AndroidManifestMcpResource_mcpMimeType, 0);
        String permission = sa.getNonConfigurationString(
                R.styleable.AndroidManifestMcpResource_permission, 0);

        sa.recycle();

        if (name == null || uri == null) {
            Log.w(TAG, "Skipping <resource> without android:name or android:mcpUri");
            skipCurrentTag(parser);
            return null;
        }

        skipCurrentTag(parser);

        return new McpResourceInfo(name, uri, description, mimeType, permission);
    }

    /** Skip the current element and all its children. */
    private static void skipCurrentTag(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        int depth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > depth)) {
            // consume
        }
    }
}
