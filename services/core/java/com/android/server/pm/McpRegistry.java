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

import android.content.pm.mcp.McpServerInfo;
import android.content.pm.mcp.McpToolInfo;
import android.util.ArrayMap;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory cache of all MCP server declarations across installed packages.
 *
 * <p>Populated by PackageManagerService at boot (from parsed package data)
 * and updated on package install/update/uninstall. Queried by the LLM
 * System Service to discover available tools and resources.
 *
 * <p>Thread-safe: all mutations and reads are synchronized.
 *
 * <h3>Tool name resolution</h3>
 * <p>Tools are indexed by short name (e.g., "send_message") when unique.
 * When multiple packages register the same tool name, ALL entries for that
 * name use qualified form ("com.example.app/send_message"). This ensures
 * consistent behavior regardless of install order or package count.
 */
public class McpRegistry {

    private static final String TAG = "McpRegistry";

    /** Map from package name to list of MCP servers declared by that package. */
    private final ArrayMap<String, List<McpServerInfo>> mServersByPackage =
            new ArrayMap<>();

    /** Flattened index: tool name (short or qualified) -> ToolEntry. */
    private final ArrayMap<String, ToolEntry> mToolIndex = new ArrayMap<>();

    /**
     * Register MCP servers for a package. Replaces any previous registration
     * for that package (used on both install and update).
     */
    public synchronized void registerPackage(String packageName,
            List<McpServerInfo> servers) {
        if (servers == null || servers.isEmpty()) {
            unregisterPackage(packageName);
            return;
        }

        mServersByPackage.put(packageName, new ArrayList<>(servers));
        rebuildToolIndex();

        Log.i(TAG, "Registered " + servers.size() + " MCP server(s) for "
                + packageName);
    }

    /**
     * Remove all MCP registrations for a package (on uninstall).
     */
    public synchronized void unregisterPackage(String packageName) {
        if (mServersByPackage.remove(packageName) != null) {
            rebuildToolIndex();
            Log.i(TAG, "Unregistered MCP servers for " + packageName);
        }
    }

    /**
     * Get all MCP servers declared by a specific package.
     */
    public synchronized List<McpServerInfo> getServersForPackage(
            String packageName) {
        List<McpServerInfo> servers = mServersByPackage.get(packageName);
        return servers != null
                ? Collections.unmodifiableList(servers)
                : Collections.emptyList();
    }

    /**
     * Get all registered MCP servers across all packages.
     */
    public synchronized List<McpServerInfo> getAllServers() {
        List<McpServerInfo> all = new ArrayList<>();
        for (int i = 0; i < mServersByPackage.size(); i++) {
            all.addAll(mServersByPackage.valueAt(i));
        }
        return Collections.unmodifiableList(all);
    }

    /**
     * Look up a tool by name across all registered packages.
     * Accepts both short names ("send_message") and qualified names
     * ("com.example.app/send_message").
     *
     * @return the tool entry, or null if no tool with that name is registered
     */
    public synchronized ToolEntry findTool(String toolName) {
        return mToolIndex.get(toolName);
    }

    /**
     * Get all registered tool names (short or qualified). Used by the LLM
     * System Service to build the tool list for the model context.
     */
    public synchronized List<String> getAllToolNames() {
        return new ArrayList<>(mToolIndex.keySet());
    }

    /**
     * Get all tool entries. Used by the LLM System Service to build the
     * complete tool list with metadata for the model context.
     */
    public synchronized List<ToolEntry> getAllTools() {
        return new ArrayList<>(mToolIndex.values());
    }

    /**
     * Get the total count of registered tools across all packages.
     */
    public synchronized int getToolCount() {
        return mToolIndex.size();
    }

    /**
     * Two-pass index build:
     *   Pass 1: collect all (shortName -> list of ToolEntry) to detect collisions.
     *   Pass 2: for each short name, if unique use short name; if colliding,
     *           ALL entries get qualified names.
     *
     * This guarantees consistent naming regardless of package count or order.
     */
    private void rebuildToolIndex() {
        mToolIndex.clear();

        // Pass 1: group all tools by short name
        ArrayMap<String, List<ToolEntry>> byShortName = new ArrayMap<>();

        for (int i = 0; i < mServersByPackage.size(); i++) {
            String packageName = mServersByPackage.keyAt(i);
            List<McpServerInfo> servers = mServersByPackage.valueAt(i);
            for (McpServerInfo server : servers) {
                for (McpToolInfo tool : server.tools) {
                    List<ToolEntry> entries = byShortName.get(tool.name);
                    if (entries == null) {
                        entries = new ArrayList<>();
                        byShortName.put(tool.name, entries);
                    }
                    entries.add(new ToolEntry(tool, server, packageName));
                }
            }
        }

        // Pass 2: assign index keys
        for (int i = 0; i < byShortName.size(); i++) {
            String shortName = byShortName.keyAt(i);
            List<ToolEntry> entries = byShortName.valueAt(i);

            if (entries.size() == 1) {
                // Unique: use short name
                ToolEntry entry = entries.get(0);
                entry.indexKey = shortName;
                mToolIndex.put(shortName, entry);
            } else {
                // Collision: ALL entries get qualified names
                for (ToolEntry entry : entries) {
                    String qualifiedName = entry.packageName + "/" + shortName;
                    entry.indexKey = qualifiedName;
                    mToolIndex.put(qualifiedName, entry);
                }
                Log.i(TAG, "Tool '" + shortName + "' registered by "
                        + entries.size() + " packages, using qualified names");
            }
        }
    }

    /**
     * A tool entry in the index, linking a tool to its owning server and package.
     */
    public static class ToolEntry {
        public final McpToolInfo tool;
        public final McpServerInfo server;
        public final String packageName;
        /** The key this entry is stored under in the index (short or qualified name). */
        public String indexKey;

        ToolEntry(McpToolInfo tool, McpServerInfo server, String packageName) {
            this.tool = tool;
            this.server = server;
            this.packageName = packageName;
            this.indexKey = tool.name;
        }

        @Override
        public String toString() {
            return "ToolEntry{" + packageName + "/" + tool.name + "}";
        }
    }
}
