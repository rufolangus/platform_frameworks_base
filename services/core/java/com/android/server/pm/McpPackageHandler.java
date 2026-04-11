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
import android.util.Log;

import java.util.List;

/**
 * Integration point between PackageManagerService and the MCP subsystem.
 *
 * <p>This class bridges the AOSP package lifecycle with MCP registration.
 * It is called from PMS at three points:
 *
 * <ol>
 *   <li><b>Parse</b>: during manifest parsing, when a {@code <mcp-server>}
 *       tag is encountered inside {@code <application>}. Delegates to
 *       {@link McpManifestParser}.</li>
 *   <li><b>Commit</b>: after a package is successfully installed or updated.
 *       Registers parsed MCP servers with the {@link McpRegistry}.</li>
 *   <li><b>Remove</b>: when a package is uninstalled. Removes its MCP
 *       servers from the registry.</li>
 * </ol>
 *
 * <h3>Integration into AOSP (required patches)</h3>
 *
 * <h4>1. ParsingPackageUtils.parseBaseApplication()</h4>
 * <p>In the tag-dispatch switch/if-else inside parseBaseApplication(), add
 * a case for "mcp-server":
 * <pre>{@code
 * // In ParsingPackageUtils.parseBaseApplication(), inside the tag switch:
 * case "mcp-server":
 *     McpServerInfo mcpServer = McpManifestParser.parseMcpServer(
 *             res, parser, packageName);
 *     if (mcpServer != null) {
 *         pkg.addMcpServer(mcpServer);
 *     }
 *     break;
 * }</pre>
 *
 * <h4>2. ParsingPackageImpl / PackageImpl</h4>
 * <p>Add a field and accessor for MCP servers:
 * <pre>{@code
 * // In ParsingPackageImpl.java:
 * private List<McpServerInfo> mcpServers = new ArrayList<>();
 *
 * public ParsingPackageImpl addMcpServer(McpServerInfo server) {
 *     mcpServers.add(server);
 *     return this;
 * }
 *
 * public List<McpServerInfo> getMcpServers() {
 *     return mcpServers;
 * }
 * }</pre>
 *
 * <h4>3. PackageManagerService.commitPackagesLocked() / installPackageLI()</h4>
 * <p>After the package is committed:
 * <pre>{@code
 * McpPackageHandler.onPackageInstalled(pkg.getPackageName(),
 *         pkg.getMcpServers());
 * }</pre>
 *
 * <h4>4. PackageManagerService.removePackageLI()</h4>
 * <p>Before the package data is removed:
 * <pre>{@code
 * McpPackageHandler.onPackageRemoved(pkg.getPackageName());
 * }</pre>
 *
 * <h4>5. SystemServer.startOtherServices()</h4>
 * <p>Register McpRegistry as a singleton accessible to the LLM System Service:
 * <pre>{@code
 * McpPackageHandler.initialize();
 * }</pre>
 */
public class McpPackageHandler {

    private static final String TAG = "McpPackageHandler";

    private static volatile McpRegistry sRegistry;

    /**
     * Initialize the MCP subsystem. Called once from SystemServer during boot.
     */
    public static void initialize() {
        sRegistry = new McpRegistry();
        Log.i(TAG, "MCP subsystem initialized");
    }

    /**
     * Get the singleton McpRegistry. Used by the LLM System Service to
     * discover available tools.
     *
     * @throws IllegalStateException if called before initialize()
     */
    public static McpRegistry getRegistry() {
        McpRegistry registry = sRegistry;
        if (registry == null) {
            throw new IllegalStateException(
                    "McpPackageHandler.initialize() not called");
        }
        return registry;
    }

    /**
     * Called by PMS after a package is installed or updated.
     * Registers the package's MCP servers in the global registry.
     *
     * @param packageName the installed package's name
     * @param servers MCP servers parsed from the package's manifest (may be empty)
     */
    public static void onPackageInstalled(String packageName,
            List<McpServerInfo> servers) {
        McpRegistry registry = sRegistry;
        if (registry == null) {
            Log.w(TAG, "onPackageInstalled called before initialize, skipping "
                    + packageName);
            return;
        }

        if (servers != null && !servers.isEmpty()) {
            registry.registerPackage(packageName, servers);
            Log.i(TAG, "Package " + packageName + " registered "
                    + servers.size() + " MCP server(s), total tools: "
                    + registry.getToolCount());
        }
    }

    /**
     * Called by PMS when a package is uninstalled.
     * Removes the package's MCP servers from the global registry.
     *
     * @param packageName the removed package's name
     */
    public static void onPackageRemoved(String packageName) {
        McpRegistry registry = sRegistry;
        if (registry == null) {
            Log.w(TAG, "onPackageRemoved called before initialize, skipping "
                    + packageName);
            return;
        }

        registry.unregisterPackage(packageName);
        Log.i(TAG, "Package " + packageName + " unregistered, remaining tools: "
                + registry.getToolCount());
    }

    /**
     * Called during boot to populate the registry with MCP servers from
     * all currently installed packages.
     *
     * @param installedPackages all installed packages with their parsed MCP data
     */
    public static void onBootCompleted(
            List<BootMcpEntry> installedPackages) {
        McpRegistry registry = sRegistry;
        if (registry == null) {
            Log.w(TAG, "onBootCompleted called before initialize");
            return;
        }

        int totalServers = 0;
        for (BootMcpEntry entry : installedPackages) {
            if (entry.servers != null && !entry.servers.isEmpty()) {
                registry.registerPackage(entry.packageName, entry.servers);
                totalServers += entry.servers.size();
            }
        }

        Log.i(TAG, "Boot complete: " + totalServers + " MCP server(s) from "
                + installedPackages.size() + " package(s), "
                + registry.getToolCount() + " total tools");
    }

    /**
     * Data holder for boot-time MCP registration.
     */
    public static class BootMcpEntry {
        public final String packageName;
        public final List<McpServerInfo> servers;

        public BootMcpEntry(String packageName, List<McpServerInfo> servers) {
            this.packageName = packageName;
            this.servers = servers;
        }
    }
}
