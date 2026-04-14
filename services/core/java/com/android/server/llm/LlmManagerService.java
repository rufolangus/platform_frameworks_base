/*
 * Copyright (C) 2024 The AAOSP Project
 * Licensed under the Apache License, Version 2.0
 */
package com.android.server.llm;

import android.content.Context;
import android.llm.ILlmResponseCallback;
import android.llm.ILlmService;
import android.llm.LlmRequest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.mcp.McpServerInfo;
import android.content.pm.mcp.McpToolCallInfo;
import android.content.pm.mcp.McpToolInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.server.SystemService;
import com.android.server.pm.McpPackageHandler;

import java.io.File;
import java.util.UUID;

public class LlmManagerService extends SystemService {

    private static final String TAG = "LlmManagerService";
    private static final String SERVICE_NAME = "llm";
    // Model search path, in priority order. /product/etc/llm is the
    // canonical location populated by aaosp_platform_build's
    // PRODUCT_COPY_FILES rule (Qwen 2.5 GGUF baked into the image).
    // /system/etc/llm is a legacy fallback. /data/local/llm is the dev
    // override — anything pushed there via `adb push` wins for testing.
    private static final String[] MODEL_SEARCH_DIRS = {
            "/data/local/llm",
            "/product/etc/llm",
            "/system/etc/llm",
    };

    private final Context mContext;
    private HandlerThread mInferenceThread;
    private Handler mInferenceHandler;
    private volatile long mNativeModelPtr = 0;
    private String mModelPath;

    public LlmManagerService(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void onStart() {
        publishBinderService(SERVICE_NAME, mBinder);
        Log.i(TAG, "LLM System Service published");
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            McpPackageHandler.initialize();
            Log.i(TAG, "LLM System Service ready");

            mInferenceThread = new HandlerThread("llm-inference",
                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
            mInferenceThread.start();
            mInferenceHandler = new Handler(mInferenceThread.getLooper());

            // Try to load model
            mInferenceHandler.post(this::loadModel);
        }
        if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            // Run MCP discovery after PackageManager has finished scanning
            // so third-party APKs (ContactsMcp etc.) are visible.
            discoverMcpServices();
        }
    }

    private void loadModel() {
        // Find model file
        mModelPath = findModel();
        if (mModelPath == null) {
            Log.w(TAG, "No .gguf model file found in any of: "
                    + java.util.Arrays.toString(MODEL_SEARCH_DIRS));
            return;
        }

        Log.i(TAG, "Loading model from " + mModelPath);
        try {
            mNativeModelPtr = nativeLoadModel(mModelPath, 2048, 0, 4);
            if (mNativeModelPtr != 0) {
                Log.i(TAG, "Model loaded: " + nativeGetModelInfo(mNativeModelPtr));
            } else {
                Log.e(TAG, "Failed to load model");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading model", e);
        }
    }

    private String findModel() {
        for (String path : MODEL_SEARCH_DIRS) {
            File dir = new File(path);
            if (!dir.exists()) continue;
            File[] files = dir.listFiles((d, name) -> name.endsWith(".gguf"));
            if (files != null && files.length > 0) {
                Log.i(TAG, "Found model: " + files[0].getAbsolutePath());
                return files[0].getAbsolutePath();
            }
        }
        return null;
    }

    private void discoverMcpServices() {
        PackageManager pm = mContext.getPackageManager();

        // Scan for packages that declare a service gated by BIND_LLM_MCP_SERVICE,
        // then parse their AndroidManifest.xml for <mcp-server> / <tool> declarations.
        java.util.List<android.content.pm.PackageInfo> packages =
                pm.getInstalledPackages(PackageManager.GET_SERVICES
                        | PackageManager.MATCH_DISABLED_COMPONENTS
                        | PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);

        Log.i(TAG, "MCP scan: total packages with GET_SERVICES = " + packages.size());
        int withServices = 0, contactsSeen = 0;
        for (android.content.pm.PackageInfo pkg : packages) {
            if (pkg.services != null && pkg.services.length > 0) withServices++;
            if (pkg.packageName != null && pkg.packageName.contains("contacts.mcp")) {
                contactsSeen++;
                Log.i(TAG, "MCP scan: SAW " + pkg.packageName + " services="
                        + (pkg.services == null ? "null"
                                : ("len=" + pkg.services.length)));
                if (pkg.services != null) {
                    for (ServiceInfo svc : pkg.services) {
                        Log.i(TAG, "MCP scan:   svc " + svc.name
                                + " perm=" + svc.permission
                                + " exported=" + svc.exported);
                    }
                }
            }
        }
        Log.i(TAG, "MCP scan: " + withServices + " pkgs have services, "
                + contactsSeen + " contacts.mcp matches");

        int toolCount = 0;
        int pkgCount = 0;
        for (android.content.pm.PackageInfo pkg : packages) {
            if (pkg.services == null) continue;
            boolean hasMcpService = false;
            for (ServiceInfo svc : pkg.services) {
                if ("android.permission.BIND_LLM_MCP_SERVICE".equals(svc.permission)) {
                    hasMcpService = true;
                    break;
                }
            }
            if (!hasMcpService) continue;
            Log.i(TAG, "MCP scan: candidate " + pkg.packageName);

            java.util.List<McpServerInfo> servers = parseManifestMcpServers(pm, pkg.packageName);
            if (servers.isEmpty()) {
                Log.w(TAG, "Package " + pkg.packageName
                        + " declares BIND_LLM_MCP_SERVICE but has no <mcp-server> in manifest");
                continue;
            }
            McpPackageHandler.getRegistry().registerPackage(pkg.packageName, servers);
            pkgCount++;
            for (McpServerInfo s : servers) {
                toolCount += (s.tools == null ? 0 : s.tools.size());
            }
            Log.i(TAG, "Registered MCP package " + pkg.packageName
                    + " with " + servers.size() + " server(s)");
        }

        Log.i(TAG, "MCP discovery complete: " + pkgCount + " package(s), "
                + toolCount + " tool(s)");
    }

    /**
     * Read a package's AndroidManifest.xml and extract every {@code <mcp-server>}
     * declaration under {@code <application>}, delegating attribute parsing to
     * {@link com.android.server.pm.McpManifestParser}.
     */
    private java.util.List<McpServerInfo> parseManifestMcpServers(
            PackageManager pm, String packageName) {
        java.util.List<McpServerInfo> out = new java.util.ArrayList<>();
        android.content.res.XmlResourceParser parser = null;
        try {
            android.content.pm.ApplicationInfo ai =
                    pm.getApplicationInfo(packageName, 0);
            android.content.res.Resources res = pm.getResourcesForApplication(ai);
            android.content.res.AssetManager am = res.getAssets();
            parser = am.openXmlResourceParser(0, "AndroidManifest.xml");

            int type;
            // Advance to <application>
            while ((type = parser.next()) != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (type == org.xmlpull.v1.XmlPullParser.START_TAG
                        && "application".equals(parser.getName())) {
                    break;
                }
            }
            if (type == org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                Log.w(TAG, "No <application> in manifest for " + packageName);
                return out;
            }

            int appDepth = parser.getDepth();
            while ((type = parser.next()) != org.xmlpull.v1.XmlPullParser.END_DOCUMENT
                    && (type != org.xmlpull.v1.XmlPullParser.END_TAG
                            || parser.getDepth() > appDepth)) {
                if (type != org.xmlpull.v1.XmlPullParser.START_TAG) continue;
                if ("mcp-server".equals(parser.getName())) {
                    McpServerInfo s = com.android.server.pm.McpManifestParser
                            .parseMcpServer(res, parser, packageName);
                    if (s != null) out.add(s);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse MCP manifest for " + packageName + ": " + e);
        } finally {
            if (parser != null) parser.close();
        }
        return out;
    }

    private final ILlmService.Stub mBinder = new ILlmService.Stub() {

        @Override
        public String submit(LlmRequest request,
                ILlmResponseCallback callback) {
            mContext.enforceCallingOrSelfPermission(
                    "android.permission.SUBMIT_LLM_REQUEST",
                    "Must hold SUBMIT_LLM_REQUEST");

            String sessionId = UUID.randomUUID().toString();
            Log.i(TAG, "Submit " + sessionId + " from uid " + Binder.getCallingUid());

            if (mNativeModelPtr == 0) {
                try {
                    callback.onError(1, "Model not loaded. Bake a .gguf into /product/etc/llm at build time or push to /data/local/llm at runtime.");
                } catch (RemoteException e) {}
                return sessionId;
            }

            // Run inference on background thread
            mInferenceHandler.post(() -> {
                try {
                    String prompt = buildPrompt(request);
                    Log.i(TAG, "Generating for prompt: " + prompt.substring(0, Math.min(200, prompt.length())));

                    NativeTokenCallback ntc = new NativeTokenCallback(callback);
                    // Tool-call pass: low temperature so the JSON is
                    // deterministic. Same query → same {"name":"…",
                    // "arguments":{…}}. Qwen 0.5B drifts wildly on tool
                    // args at the default temperature.
                    float toolCallTemp = Math.min(request.temperature, 0.1f);
                    String result = nativeGenerate(
                            mNativeModelPtr,
                            prompt,
                            request.maxTokens > 0 ? request.maxTokens : 256,
                            toolCallTemp,
                            ntc);

                    Log.i(TAG, "Raw LLM output (tool-call pass, temp="
                            + toolCallTemp + "): " + result);

                    // Detect and dispatch tool call(s) in Qwen <tool_call>...</tool_call> format.
                    String toolResult = maybeExecuteToolCall(result, callback, sessionId);
                    if (toolResult != null) {
                        // Tool ran. Round 2: feed the tool result back to the LLM
                        // and let it generate a natural-language final answer.
                        String continuation = buildContinuationPrompt(
                                request, result, toolResult);
                        Log.i(TAG, "Round-2 continuation prompt: "
                                + continuation.substring(0,
                                        Math.min(200, continuation.length())));
                        NativeTokenCallback ntc2 = new NativeTokenCallback(callback);
                        // Answer pass: caller's requested temperature
                        // (default 0.7-ish). Some creativity is fine
                        // here — we're just rendering verifiably-real
                        // data into prose.
                        float answerTemp = request.temperature > 0
                                ? request.temperature : 0.7f;
                        String finalAnswer = nativeGenerate(
                                mNativeModelPtr,
                                continuation,
                                request.maxTokens > 0 ? request.maxTokens : 256,
                                answerTemp,
                                ntc2);
                        // Strip any stray <tool_call> the model might emit again,
                        // and any chat-format remnants.
                        String clean = stripChatRemnants(finalAnswer);
                        try { callback.onToken(clean); } catch (RemoteException re) {}
                        callback.onComplete(clean);
                        Log.i(TAG, "Generation+tool+round2 complete: "
                                + clean.length() + " chars");
                    } else {
                        // No tool call — emit the raw buffer as-is.
                        ntc.emitBuffer();
                        callback.onComplete(result);
                        Log.i(TAG, "Generation complete: " + result.length() + " chars");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Inference error", e);
                    try {
                        callback.onError(2, "Inference error: " + e.getMessage());
                    } catch (RemoteException re) {}
                }
            });

            return sessionId;
        }

        @Override
        public void cancel(String sessionId) {
            Log.i(TAG, "Cancel " + sessionId);
        }

        @Override
        public String getAvailableServers() {
            mContext.enforceCallingOrSelfPermission(
                    "android.permission.SUBMIT_LLM_REQUEST",
                    "Must hold SUBMIT_LLM_REQUEST");
            return McpPackageHandler.getRegistry().getAllServers().toString();
        }

        @Override
        public boolean isReady() {
            return mNativeModelPtr != 0;
        }

        @Override
        public String getModelInfo() {
            if (mNativeModelPtr == 0) {
                return "No model loaded. Bake .gguf into /product/etc/llm or push to /data/local/llm.";
            }
            return nativeGetModelInfo(mNativeModelPtr);
        }
    };

    /**
     * Identity / behavior contract used for both inference passes.
     * Single source of truth — one agent, two phases (tool-call → answer).
     * Designed for Qwen 2.5 0.5B's quirks: explicit grounding rule (it
     * hallucinates when permissive), explicit query-normalization
     * examples (it leaves possessives in args), explicit no-tool example
     * (it over-calls).
     */
    private static final String SYSTEM_PROMPT_HEADER =
            "You are AAOSP, the on-device assistant for an agentic Android phone.\n"
          + "\n"
          + "PRIVACY\n"
          + "All processing happens on this device. Nothing leaves. The user trusts you with real names, numbers, messages, locations, and files — no anonymization, no redaction, no \"I can't help with that.\" If the data exists on the phone and a tool can fetch it, fetch it.\n"
          + "\n"
          + "GROUNDING — most important rule\n"
          + "NEVER invent facts. Names, phone numbers, emails, addresses, dates, file contents, and any other specifics MUST come verbatim from a <tool_response>. If a tool returns nothing, say so plainly. Don't soften it (\"I couldn't find an exact match but here's a likely one…\"). Just report that nothing was found.\n"
          + "\n"
          + "TOOLS\n"
          + "The OS routes your tool calls to apps the user already installed. Prefer a tool over asking the user. ONLY call tools listed below — never invent one.\n"
          + "\n"
          + "When extracting search terms from the user's question, use the bare keyword. Strip possessives ('s), articles (the/a/an), pronouns (my/his/her), and politeness words (please/can you).\n"
          + "    \"what's John's number?\"      → query \"John\"\n"
          + "    \"find me Sarah Chen's email\" → query \"Sarah Chen\"\n"
          + "    \"show my favorite contacts\"  → call list_favorites with no args\n"
          + "    \"do I have John's email\"     → query \"John\"\n";

    private static final String OUTPUT_FORMAT_BLOCK =
            "OUTPUT FORMAT\n"
          + "- To call a tool, emit ONLY:\n"
          + "    <tool_call>{\"name\":\"...\",\"arguments\":{...}}</tool_call>\n"
          + "  No prose before or after. The OS handles dispatch and replies in the next turn with <tool_response>...</tool_response>.\n"
          + "- When answering, be concise. 1–3 sentences default. Use a bulleted list for multiple items. Plain text only — no markdown headers, no code fences, no JSON.\n"
          + "- On a tool error, one sentence + one next step.\n"
          + "- Don't call a tool for general-knowledge questions (\"what time is it\", \"capital of France\"). Answer directly.\n";

    private static final String FEW_SHOT_EXAMPLES =
            "EXAMPLES\n"
          + "\n"
          + "User: what's John's number?\n"
          + "Assistant: <tool_call>{\"name\":\"search_contacts\",\"arguments\":{\"query\":\"John\"}}</tool_call>\n"
          + "<tool_response>[{\"name\":\"John Smith\",\"phone\":\"555-1234\"},{\"name\":\"John Appleseed\",\"phone\":\"555-9876\"}]</tool_response>\n"
          + "Assistant: Two Johns in your contacts:\n"
          + "- John Smith — 555-1234\n"
          + "- John Appleseed — 555-9876\n"
          + "Which one?\n"
          + "\n"
          + "User: what's Maria's number?\n"
          + "Assistant: <tool_call>{\"name\":\"search_contacts\",\"arguments\":{\"query\":\"Maria\"}}</tool_call>\n"
          + "<tool_response>[]</tool_response>\n"
          + "Assistant: No contact named Maria in your phone.\n"
          + "\n"
          + "User: what time is it?\n"
          + "Assistant: It's 9:42 PM.\n";

    /** Build the full ChatML system message body (no <|im_start|> wrapper). */
    private String composeSystemPrompt(LlmRequest request) {
        StringBuilder sb = new StringBuilder();
        if (request.systemPrompt != null && !request.systemPrompt.isEmpty()) {
            // Caller-supplied system prompt overrides the AAOSP default.
            sb.append(request.systemPrompt).append("\n");
        } else {
            sb.append(SYSTEM_PROMPT_HEADER);
        }
        String toolsBlock = buildToolsBlock();
        if (toolsBlock != null) {
            sb.append("\n").append(toolsBlock).append("\n");
        }
        sb.append("\n").append(OUTPUT_FORMAT_BLOCK);
        sb.append("\n").append(FEW_SHOT_EXAMPLES);
        return sb.toString();
    }

    private String buildPrompt(LlmRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("<|im_start|>system\n");
        sb.append(composeSystemPrompt(request));
        sb.append("<|im_end|>\n");
        sb.append("<|im_start|>user\n");
        sb.append(request.prompt);
        sb.append("\n<|im_end|>\n");
        sb.append("<|im_start|>assistant\n");
        return sb.toString();
    }

    /**
     * Build the tools block in Qwen 2.5's expected format. Returns null if
     * no MCP servers are registered.
     */
    private String buildToolsBlock() {
        java.util.List<McpServerInfo> allServers =
                McpPackageHandler.getRegistry().getAllServers();
        java.util.List<McpToolInfo> tools = new java.util.ArrayList<>();
        java.util.Map<String, String> toolToPackage = new java.util.HashMap<>();
        java.util.Map<String, String> toolToService = new java.util.HashMap<>();
        for (McpServerInfo s : allServers) {
            if (s.tools == null) continue;
            for (McpToolInfo t : s.tools) {
                tools.add(t);
                toolToPackage.put(t.name, s.packageName);
                toolToService.put(t.name, s.name);
            }
        }
        if (tools.isEmpty()) return null;

        // Cache the routing tables so the dispatcher can find the service later.
        synchronized (mToolRouteLock) {
            mToolToPackage = toolToPackage;
            mToolToService = toolToService;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Tools\n\n");
        sb.append("You may call one or more functions to assist with the user query.\n\n");
        sb.append("You are provided with function signatures within <tools></tools> XML tags:\n");
        sb.append("<tools>\n");
        for (McpToolInfo t : tools) {
            sb.append("{\"type\":\"function\",\"function\":{");
            sb.append("\"name\":\"").append(jsonEscape(t.name)).append("\",");
            sb.append("\"description\":\"").append(jsonEscape(
                    t.description == null ? "" : t.description)).append("\",");
            sb.append("\"parameters\":{\"type\":\"object\",\"properties\":{");
            if (t.inputs != null) {
                boolean first = true;
                for (android.content.pm.mcp.McpInputInfo in : t.inputs) {
                    if (!first) sb.append(",");
                    sb.append("\"").append(jsonEscape(in.name)).append("\":{");
                    sb.append("\"type\":\"string\",");
                    sb.append("\"description\":\"").append(jsonEscape(
                            in.description == null ? "" : in.description)).append("\"}");
                    first = false;
                }
            }
            sb.append("},\"required\":[");
            if (t.inputs != null) {
                boolean first = true;
                for (android.content.pm.mcp.McpInputInfo in : t.inputs) {
                    if (in.required) {
                        if (!first) sb.append(",");
                        sb.append("\"").append(jsonEscape(in.name)).append("\"");
                        first = false;
                    }
                }
            }
            sb.append("]}}}\n");
        }
        sb.append("</tools>\n\n");
        sb.append("For each function call, return a json object with function name and ");
        sb.append("arguments within <tool_call></tool_call> XML tags:\n");
        sb.append("<tool_call>\n{\"name\": <function-name>, \"arguments\": <args-json-object>}\n</tool_call>");
        return sb.toString();
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }

    private final Object mToolRouteLock = new Object();
    private java.util.Map<String, String> mToolToPackage = new java.util.HashMap<>();
    private java.util.Map<String, String> mToolToService = new java.util.HashMap<>();

    /**
     * If the LLM output contains a <tool_call>{"name":..., "arguments":...}</tool_call>,
     * bind to the owning MCP service via {@link android.llm.IMcpToolProvider} and invoke
     * the tool. Returns the tool result text, or null if no tool call was present
     * or dispatch failed.
     */
    private String maybeExecuteToolCall(String llmOutput,
            ILlmResponseCallback callback, String sessionId) {
        if (llmOutput == null) return null;
        int start = llmOutput.indexOf("<tool_call>");
        if (start < 0) return null;
        int end = llmOutput.indexOf("</tool_call>", start);
        if (end < 0) return null;
        String body = llmOutput.substring(start + "<tool_call>".length(), end).trim();
        try {
            org.json.JSONObject obj = new org.json.JSONObject(body);
            String name = obj.optString("name", null);
            org.json.JSONObject args = obj.optJSONObject("arguments");
            if (name == null) {
                Log.w(TAG, "tool_call missing name: " + body);
                return null;
            }
            String pkgName, svcName;
            synchronized (mToolRouteLock) {
                pkgName = mToolToPackage.get(name);
                svcName = mToolToService.get(name);
            }
            String argsStr = args == null ? "{}" : args.toString();
            if (pkgName == null || svcName == null) {
                Log.w(TAG, "tool_call for unknown tool: " + name);
                String err = "{\"error\":\"unknown tool: " + name + "\"}";
                fireToolResult(callback, sessionId, name, null, null, argsStr, err,
                        McpToolCallInfo.STATUS_FAILED, 0);
                return err;
            }
            Log.i(TAG, "Dispatching tool " + name + " -> " + pkgName + "/" + svcName);
            // Fire the STARTED event so the launcher can show "Searching contacts..."
            // with the owning app's icon.
            long t0 = android.os.SystemClock.uptimeMillis();
            fireToolStarted(callback, sessionId, name, pkgName, svcName, argsStr);
            String result = invokeMcpTool(pkgName, svcName, name, argsStr);
            int duration = (int) (android.os.SystemClock.uptimeMillis() - t0);
            int status = (result != null && result.contains("\"error\""))
                    ? McpToolCallInfo.STATUS_FAILED
                    : McpToolCallInfo.STATUS_COMPLETED;
            fireToolResult(callback, sessionId, name, pkgName, svcName, argsStr,
                    result, status, duration);
            return result;
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse tool_call: " + body, e);
            return null;
        }
    }

    private void fireToolStarted(ILlmResponseCallback cb, String sessionId,
            String tool, String pkg, String svc, String args) {
        McpToolCallInfo info = new McpToolCallInfo(sessionId, tool, pkg, svc,
                args, null, System.currentTimeMillis(),
                McpToolCallInfo.STATUS_STARTED, -1);
        try { cb.onToolCall(info); } catch (RemoteException ignored) {}
    }

    private void fireToolResult(ILlmResponseCallback cb, String sessionId,
            String tool, String pkg, String svc, String args, String result,
            int status, int duration) {
        McpToolCallInfo info = new McpToolCallInfo(sessionId, tool, pkg, svc,
                args, result, System.currentTimeMillis(), status, duration);
        try { cb.onToolResult(info); } catch (RemoteException ignored) {}
    }

    /**
     * Best-effort pretty-print of a tool's JSON result. If it parses as a JSON
     * array of objects we render rows; if it's an object we render key:value
     * lines; on error and a Java exception JSON we surface a friendly error;
     * otherwise we return the raw text.
     */
    /**
     * Build the "round 2" prompt that tells the LLM what tool was just called,
     * what the result was, and asks it to produce a natural-language answer.
     * This is the standard agentic loop: user → assistant tool_call →
     * tool_response → assistant final answer.
     */
    private String buildContinuationPrompt(LlmRequest request,
            String firstRoundOutput, String toolResultJson) {
        // Same identity, same rules — the AAOSP system prompt is one
        // contract across both passes. The grounding rule + few-shot
        // examples in composeSystemPrompt() are what teach the model
        // to render tool results faithfully (no fabrication, no
        // omission); no bandaid prose needed here anymore.
        StringBuilder sb = new StringBuilder();
        sb.append("<|im_start|>system\n");
        sb.append(composeSystemPrompt(request));
        sb.append("<|im_end|>\n");
        sb.append("<|im_start|>user\n").append(request.prompt).append("\n<|im_end|>\n");
        // Echo the first-round tool_call back as the assistant turn so
        // the model has its own action in chat history.
        String toolCallLine = "";
        int s = firstRoundOutput.indexOf("<tool_call>");
        int e = firstRoundOutput.indexOf("</tool_call>", s);
        if (s >= 0 && e > s) {
            toolCallLine = firstRoundOutput.substring(s, e + "</tool_call>".length());
        }
        sb.append("<|im_start|>assistant\n").append(toolCallLine).append("\n<|im_end|>\n");
        sb.append("<|im_start|>user\n<tool_response>\n");
        sb.append(toolResultJson);
        sb.append("\n</tool_response>\n<|im_end|>\n");
        sb.append("<|im_start|>assistant\n");
        return sb.toString();
    }

    /** Belt-and-suspenders cleaning of any chat tokens the model might leak. */
    private static String stripChatRemnants(String s) {
        if (s == null) return "";
        // Drop any second tool_call attempt
        int tc = s.indexOf("<tool_call>");
        if (tc >= 0) s = s.substring(0, tc);
        // Drop chat tokens
        s = s.replace("<|im_start|>", "")
             .replace("<|im_end|>", "")
             .replace("assistant\n", "")
             .replace("user\n", "");
        return s.trim();
    }

    private static String humanizeToolResult(String json) {
        if (json == null) return "(no result)";
        String s = json.trim();
        try {
            if (s.startsWith("[")) {
                org.json.JSONArray arr = new org.json.JSONArray(s);
                if (arr.length() == 0) return "(no results)";
                StringBuilder out = new StringBuilder();
                for (int i = 0; i < arr.length(); i++) {
                    if (i > 0) out.append("\n");
                    Object item = arr.get(i);
                    if (item instanceof org.json.JSONObject) {
                        org.json.JSONObject o = (org.json.JSONObject) item;
                        boolean first = true;
                        java.util.Iterator<String> keys = o.keys();
                        while (keys.hasNext()) {
                            String k = keys.next();
                            if (!first) out.append(" · ");
                            out.append(o.optString(k, ""));
                            first = false;
                        }
                    } else {
                        out.append(item.toString());
                    }
                }
                return out.toString();
            }
            if (s.startsWith("{")) {
                org.json.JSONObject o = new org.json.JSONObject(s);
                if (o.has("error")) return "Error: " + o.optString("error");
                StringBuilder out = new StringBuilder();
                java.util.Iterator<String> keys = o.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    out.append(k).append(": ").append(o.optString(k, "")).append("\n");
                }
                return out.toString().trim();
            }
        } catch (Exception ignored) {}
        return s;
    }

    private String invokeMcpTool(String pkgName, String svcName,
            String toolName, String argsJson) {
        Intent intent = new Intent();
        intent.setClassName(pkgName, svcName);
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        final String[] result = new String[1];
        final android.content.ServiceConnection conn = new android.content.ServiceConnection() {
            @Override
            public void onServiceConnected(android.content.ComponentName name,
                    IBinder service) {
                try {
                    android.llm.IMcpToolProvider provider =
                            android.llm.IMcpToolProvider.Stub.asInterface(service);
                    result[0] = provider.invokeTool(toolName, argsJson);
                } catch (Exception e) {
                    result[0] = "{\"error\":\"" + jsonEscape(e.toString()) + "\"}";
                } finally {
                    latch.countDown();
                }
            }
            @Override public void onServiceDisconnected(android.content.ComponentName name) {}
        };
        try {
            boolean bound = mContext.bindService(intent, conn,
                    android.content.Context.BIND_AUTO_CREATE);
            if (!bound) {
                return "{\"error\":\"bind failed for " + pkgName + "/" + svcName + "\"}";
            }
            if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                return "{\"error\":\"tool timeout\"}";
            }
            return result[0] != null ? result[0] : "{\"error\":\"null result\"}";
        } catch (Exception e) {
            return "{\"error\":\"" + jsonEscape(e.toString()) + "\"}";
        } finally {
            try { mContext.unbindService(conn); } catch (Exception ignored) {}
        }
    }

    public static class NativeTokenCallback {
        // Pure buffer — never forwards intermediate tokens to the launcher.
        // The dispatcher decides what the user actually sees and emits the
        // clean final response via onToken + onComplete after generation.
        private final ILlmResponseCallback mCallback;
        private final StringBuilder mBuffer = new StringBuilder();
        public NativeTokenCallback(ILlmResponseCallback callback) {
            mCallback = callback;
        }
        @SuppressWarnings("unused")
        public void onToken(String token) {
            if (token != null) mBuffer.append(token);
        }
        @SuppressWarnings("unused")
        public boolean isCancelled() { return false; }
        /** Send the held buffer verbatim — used when there's no tool_call. */
        void emitBuffer() {
            try { mCallback.onToken(mBuffer.toString()); }
            catch (RemoteException e) {}
        }
    }

    static {
        try {
            try {
                System.loadLibrary("llm_jni");
            } catch (UnsatisfiedLinkError e1) {
                System.load("/data/local/llm/libllm_jni.so");
            }
            Log.i(TAG, "libllm_jni loaded");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load libllm_jni: " + e.getMessage());
        }
    }

    private static native long nativeLoadModel(String path, int ctxSize, int gpuLayers, int nThreads);
    private static native String nativeGenerate(long ptr, String prompt, int maxTokens, float temp, NativeTokenCallback cb);
    private static native String nativeGetModelInfo(long ptr);
    private static native void nativeUnloadModel(long ptr);
}
