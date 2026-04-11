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

/**
 * Interface that apps implement to handle MCP tool invocations.
 *
 * When an app declares <mcp-server> in its manifest with
 * android:name=".MyMcpService", that Service must return an
 * IMcpToolProvider binder from onBind().
 *
 * The LLM System Service binds to the app's service and calls
 * invokeTool() when the LLM decides to use one of the app's tools.
 *
 * Example app-side implementation:
 *
 *   public class MyMcpService extends Service {
 *       private final IMcpToolProvider.Stub mBinder =
 *               new IMcpToolProvider.Stub() {
 *           @Override
 *           public String invokeTool(String toolName, String argsJson) {
 *               switch (toolName) {
 *                   case "send_message":
 *                       return handleSendMessage(argsJson);
 *                   default:
 *                       return "{\"error\": \"unknown tool\"}";
 *               }
 *           }
 *
 *           @Override
 *           public String readResource(String resourceName) {
 *               // Return resource content as JSON
 *           }
 *       };
 *
 *       @Override
 *       public IBinder onBind(Intent intent) {
 *           return mBinder;
 *       }
 *   }
 */
interface IMcpToolProvider {

    /**
     * Invoke a tool declared by this app's MCP server.
     *
     * Called by the LLM System Service when the model generates a
     * tool_call targeting one of this app's tools. The app executes
     * the action and returns the result.
     *
     * @param toolName the name of the tool to invoke
     * @param argumentsJson JSON object containing the tool arguments
     * @return JSON string with the tool result
     */
    String invokeTool(String toolName, String argumentsJson);

    /**
     * Read a resource declared by this app's MCP server.
     *
     * @param resourceName the name of the resource to read
     * @return JSON string with the resource content
     */
    String readResource(String resourceName);

    /**
     * List resources that match a URI template pattern.
     * Used for dynamic resource discovery beyond what's in the manifest.
     *
     * @param uriPattern optional URI pattern filter, null for all
     * @return JSON array of resource descriptors
     */
    String listResources(String uriPattern);
}
