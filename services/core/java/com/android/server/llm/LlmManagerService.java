/*
 * Copyright (C) 2024 The AAOSP Project
 * Licensed under the Apache License, Version 2.0
 */
package com.android.server.llm;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.mcp.McpServerInfo;
import android.content.pm.mcp.McpToolCallInfo;
import android.content.pm.mcp.McpToolInfo;
import android.llm.ILlmResponseCallback;
import android.llm.ILlmService;
import android.llm.LlmRequest;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;

import com.android.server.SystemService;
import com.android.server.pm.McpPackageHandler;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
    /** llama.cpp context size the model was loaded with, in tokens. Set by loadModel(). */
    private volatile int mNativeModelCtxSize = 0;
    private String mModelPath;

    // v0.4: HITL consent + audit log
    private HitlConsentStore mConsentStore;
    private LlmSessionStore mSessionStore;
    // sessionIds the launcher (or anyone) asked us to abort. Checked at
    // every iteration of the chain loop and during consent gate waits.
    private final Set<String> mCanceledSessions =
            ConcurrentHashMap.newKeySet();
    // Parked consent prompts. Key = sessionId+"|"+toolName.
    private final Map<String, ConsentGate> mPendingGates =
            new ConcurrentHashMap<>();

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

            // v0.4: persistent stores
            try {
                mSessionStore = new LlmSessionStore(mContext);
                mConsentStore = new HitlConsentStore(mContext);
                Log.i(TAG, "Session + consent stores opened");
            } catch (Exception e) {
                Log.e(TAG, "Store init failed — running without persistence", e);
            }

            // Watch for app uninstall / upgrade so we can prune
            // stale consent grants. Signature-mismatch invalidation
            // also handled lazily on grant lookup.
            registerPackageMonitor();

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
            // v0.5: ctx bumped 2048 → 4096 for the 3B model. 0.5B couldn't
            // really use a longer context anyway; 3B benefits on chained
            // tool calls where the <tool_response> history can be bulky.
            // threads 4 → 8 to take advantage of Cuttlefish --cpus=16;
            // on a phone this should match the number of big cores (~6-8
            // on a modern flagship). No effect on model quality.
            mNativeModelCtxSize = 4096;
            mNativeModelPtr = nativeLoadModel(mModelPath, mNativeModelCtxSize, 0, 8);
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

            // Honor session continuity: if the caller passes a sessionId
            // we keep it (multi-turn chat), otherwise mint a fresh one.
            final String sessionId = (request.sessionId != null
                    && !request.sessionId.isEmpty())
                    ? request.sessionId : UUID.randomUUID().toString();
            Log.i(TAG, "Submit " + sessionId + " from uid "
                    + Binder.getCallingUid()
                    + (request.sessionId != null ? " (continuing)" : " (new)"));

            // A new submit on a canceled id is allowed — the user may have
            // started a fresh chat in the same session slot.
            mCanceledSessions.remove(sessionId);

            if (mNativeModelPtr == 0) {
                try {
                    callback.onError(1, "Model not loaded. Bake a .gguf into /product/etc/llm at build time or push to /data/local/llm at runtime.");
                } catch (RemoteException e) {}
                return sessionId;
            }

            final int callerUserId = UserHandle.getUserId(Binder.getCallingUid());

            // v0.4 debug: log the incoming user prompt + any carried history
            // so we can see exactly what the model is asked to answer.
            Log.i(TAG, "Submit prompt (" + (request.prompt == null ? 0
                    : request.prompt.length()) + " chars): "
                    + truncateForLog(request.prompt));
            if (request.conversationJson != null) {
                Log.i(TAG, "Submit history: "
                        + truncateForLog(request.conversationJson));
            }

            mInferenceHandler.post(() -> {
                try {
                    runChain(sessionId, callerUserId, request, callback);
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
            if (sessionId == null) return;
            Log.i(TAG, "Cancel " + sessionId);
            mCanceledSessions.add(sessionId);
            // Wake any parked consent gate for this session.
            for (Map.Entry<String, ConsentGate> e : mPendingGates.entrySet()) {
                if (e.getKey().startsWith(sessionId + "|")) {
                    e.getValue().resolve(HitlConsentStore.DECISION_DENY,
                            HitlConsentStore.SCOPE_ONCE,
                            HitlConsentStore.CONSENT_TIMED_OUT);
                }
            }
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

        @Override
        public void confirmToolCall(String sessionId, String toolName,
                int decision, int scope) {
            mContext.enforceCallingOrSelfPermission(
                    "android.permission.SUBMIT_LLM_REQUEST",
                    "Must hold SUBMIT_LLM_REQUEST");
            if (sessionId == null || toolName == null) return;
            // Defensive: unknown / weird decision values → DENY.
            if (decision != HitlConsentStore.DECISION_ALLOW) {
                decision = HitlConsentStore.DECISION_DENY;
            }
            if (scope < HitlConsentStore.SCOPE_ONCE
                    || scope > HitlConsentStore.SCOPE_FOREVER) {
                scope = HitlConsentStore.SCOPE_ONCE;
            }
            // Write-intent tools can't be granted FOREVER — downgrade.
            Boolean requires;
            synchronized (mToolRouteLock) {
                requires = mToolRequiresConsent.get(toolName);
            }
            if (requires != null && requires
                    && scope == HitlConsentStore.SCOPE_FOREVER) {
                Log.i(TAG, "Downgrading FOREVER→SESSION for write-intent tool "
                        + toolName);
                scope = HitlConsentStore.SCOPE_SESSION;
            }
            int auditCode = auditCodeFor(decision, scope);
            ConsentGate gate = mPendingGates.get(sessionId + "|" + toolName);
            if (gate == null) {
                Log.w(TAG, "confirmToolCall: no pending gate for "
                        + sessionId + "/" + toolName);
                return;
            }
            gate.resolve(decision, scope, auditCode);
        }

        @Override
        public void revokeToolGrant(String packageName, String toolName) {
            mContext.enforceCallingOrSelfPermission(
                    "android.permission.SUBMIT_LLM_REQUEST",
                    "Must hold SUBMIT_LLM_REQUEST");
            if (packageName == null || toolName == null || mConsentStore == null) return;
            int userId = UserHandle.getUserId(Binder.getCallingUid());
            mConsentStore.revoke(userId, packageName, toolName);
            Log.i(TAG, "Revoked " + packageName + "/" + toolName
                    + " for user " + userId);
        }

        @Override
        public String getRecentAuditCalls(int limit) {
            mContext.enforceCallingOrSelfPermission(
                    "android.permission.SUBMIT_LLM_REQUEST",
                    "Must hold SUBMIT_LLM_REQUEST");
            if (mConsentStore == null) return "[]";
            return mConsentStore.recentCallsJson(Math.max(1, Math.min(limit, 500)));
        }

        @Override
        public void endSession(String sessionId) {
            if (sessionId == null) return;
            Log.i(TAG, "endSession " + sessionId);
            mCanceledSessions.add(sessionId);
            if (mConsentStore != null) {
                mConsentStore.clearSessionScoped(sessionId);
            }
            // Resolve any parked gates as DENY so dispatchers unblock.
            for (Map.Entry<String, ConsentGate> e : mPendingGates.entrySet()) {
                if (e.getKey().startsWith(sessionId + "|")) {
                    e.getValue().resolve(HitlConsentStore.DECISION_DENY,
                            HitlConsentStore.SCOPE_ONCE,
                            HitlConsentStore.CONSENT_TIMED_OUT);
                }
            }
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            pw.println("LlmManagerService state:");
            pw.println("  model: " + (mNativeModelPtr != 0 ? mModelPath : "NOT LOADED"));
            pw.println("  pending consent gates: " + mPendingGates.size());
            pw.println("  canceled sessions: " + mCanceledSessions.size());
            // Pull registered tools from the registry directly so dumpsys
            // is meaningful even before the first submit populates the
            // per-submit routing cache. Includes the built-in launch_app.
            int toolCount = 0;
            List<McpServerInfo> servers = McpPackageHandler.getRegistry().getAllServers();
            for (McpServerInfo s : servers) {
                toolCount += (s.tools == null ? 0 : s.tools.size());
            }
            pw.println("  registered tools: " + (toolCount + 1)
                    + " (" + toolCount + " MCP + 1 built-in)");
            for (McpServerInfo s : servers) {
                if (s.tools == null) continue;
                for (McpToolInfo t : s.tools) {
                    pw.println("    " + t.name + " → " + s.packageName
                            + (t.requiresConfirmation ? " [consent]" : ""));
                }
            }
            pw.println("    " + TOOL_LAUNCH_APP + " → " + BUILTIN_PKG
                    + " [builtin]");
            // v0.5.1: prompt-size rolling histogram so we can decide if
            // context compaction / tool-result clearing are real needs.
            // Char counts, not token counts — divide by ~4 for Qwen BPE.
            int[] sys = promptSizeStats(PROMPT_SIZE_SYSTEM);
            int[] full = promptSizeStats(PROMPT_SIZE_FULL);
            pw.println("  prompt size (chars, ~4 chars/token):");
            if (sys == null) {
                pw.println("    system prompt: no samples yet");
            } else {
                pw.println("    system prompt   min=" + sys[0] + " p50=" + sys[1]
                        + " p95=" + sys[2] + " max=" + sys[3]
                        + " all-time-max=" + sys[4] + " samples=" + sys[5]);
            }
            if (full == null) {
                pw.println("    full ChatML:   no samples yet");
            } else {
                pw.println("    full ChatML    min=" + full[0] + " p50=" + full[1]
                        + " p95=" + full[2] + " max=" + full[3]
                        + " all-time-max=" + full[4] + " samples=" + full[5]);
                // v0.5.1: budget is whatever the model was actually loaded
                // with (mNativeModelCtxSize). Approx 4 chars/token for Qwen
                // BPE on English. Avoid hardcoded magic numbers; on a
                // future model bump this stays correct automatically.
                if (mNativeModelCtxSize > 0) {
                    long budgetChars = (long) mNativeModelCtxSize * 4;
                    int pct = (int) (full[2] * 100L / budgetChars);
                    pw.println("    (ctx budget is " + mNativeModelCtxSize
                            + " tokens ≈ " + budgetChars + " chars; p95 of "
                            + full[2] + " chars is " + pct + "% of budget)");
                } else {
                    pw.println("    (model not loaded — budget unknown)");
                }
            }
            if (mConsentStore != null) {
                pw.println("  recent audit (newest first):");
                for (String line : mConsentStore.recentCallsPlain(20)) {
                    pw.println("    " + line);
                }
            }
        }
    };

    /** Map (decision, scope) → audit_calls.consent_decision enum. */
    private static int auditCodeFor(int decision, int scope) {
        if (decision == HitlConsentStore.DECISION_DENY) {
            return HitlConsentStore.CONSENT_DENIED;
        }
        switch (scope) {
            case HitlConsentStore.SCOPE_SESSION:
                return HitlConsentStore.CONSENT_ALLOWED_SESS;
            case HitlConsentStore.SCOPE_FOREVER:
                return HitlConsentStore.CONSENT_ALLOWED_FOREVER;
            case HitlConsentStore.SCOPE_ONCE:
            default:
                return HitlConsentStore.CONSENT_ALLOWED_ONCE;
        }
    }

    // ============================================================
    //  Chain loop + consent gate
    // ============================================================

    /** Hard ceiling on tool iterations per submit. LlmRequest clamps to this. */
    private static final int MAX_CHAIN_ITERATIONS_CAP = 8;
    private static final int DEFAULT_CHAIN_ITERATIONS = 5;
    /** How long to park a dispatcher waiting for the user. */
    private static final long CONSENT_TIMEOUT_MS = 60_000L;

    // ------------------------------------------------------------------
    // v0.5.1: prompt-size instrumentation (char count, not token count —
    // Qwen BPE is ~3.5–4 chars/token for English). We need this to decide
    // whether context compaction / tool-result clearing are real problems
    // before building them. Chars are cheap to count; tokens require a
    // JNI call. All in-memory, rolling, surfaced via `dumpsys llm`.
    // ------------------------------------------------------------------
    private static final int PROMPT_SIZE_HIST_N = 100;
    private static final int PROMPT_SIZE_SYSTEM = 0; // system prompt only
    private static final int PROMPT_SIZE_FULL   = 1; // full ChatML prompt
    private final int[][] mPromptSizeHist = new int[2][PROMPT_SIZE_HIST_N];
    private final int[] mPromptSizeCount = new int[2];   // wraps around HIST_N
    private final int[] mPromptSizeMax = new int[2];
    private final Object mPromptSizeLock = new Object();

    /** Record a prompt character count into the rolling histogram. */
    private void recordPromptSize(int which, int chars) {
        synchronized (mPromptSizeLock) {
            int idx = mPromptSizeCount[which] % PROMPT_SIZE_HIST_N;
            mPromptSizeHist[which][idx] = chars;
            mPromptSizeCount[which]++;
            if (chars > mPromptSizeMax[which]) mPromptSizeMax[which] = chars;
        }
    }

    /** Compute min/p50/p95/max over the last PROMPT_SIZE_HIST_N samples. */
    private int[] promptSizeStats(int which) {
        synchronized (mPromptSizeLock) {
            int n = Math.min(mPromptSizeCount[which], PROMPT_SIZE_HIST_N);
            if (n == 0) return null;
            int[] snap = new int[n];
            System.arraycopy(mPromptSizeHist[which], 0, snap, 0, n);
            java.util.Arrays.sort(snap);
            int p50 = snap[n / 2];
            int p95 = snap[Math.min(n - 1, (int) (n * 0.95))];
            return new int[] { snap[0], p50, p95, snap[n - 1], mPromptSizeMax[which], mPromptSizeCount[which] };
        }
    }

    /**
     * Agentic loop. On each iteration: generate at tool-call temperature,
     * see if the model emitted a <tool_call>. If yes, dispatch (with HITL)
     * and loop with the response appended. If no, do one final pass at
     * answer temperature and emit the result.
     *
     * <p>Bounds: {@code maxToolCalls} (default 5, cap 8) per submit;
     * {@link #CONSENT_TIMEOUT_MS} per prompt; a 2-in-a-row unknown-tool
     * streak short-circuits the loop to avoid the model flailing.
     */
    private void runChain(String sessionId, int userId, LlmRequest request,
            ILlmResponseCallback callback) throws RemoteException {
        int maxIters = request.maxToolCalls > 0
                ? Math.min(request.maxToolCalls, MAX_CHAIN_ITERATIONS_CAP)
                : DEFAULT_CHAIN_ITERATIONS;
        // v0.4 tune: floor raised from 0.1 → 0.25. At 0.1 the model
        // pattern-locks on few-shot final-answer text and skips the
        // <tool_call> entirely for write requests. Still cool enough
        // that tool-call JSON stays stable.
        float toolCallTemp = Math.min(request.temperature, 0.25f);
        float answerTemp = request.temperature > 0 ? request.temperature : 0.7f;
        int maxTokens = request.maxTokens > 0 ? request.maxTokens : 256;

        // History accumulates assistant <tool_call> + user <tool_response>
        // pairs between iterations so the model sees its own prior steps.
        StringBuilder history = new StringBuilder();
        int unknownStreak = 0;

        for (int iter = 0; iter < maxIters; iter++) {
            if (mCanceledSessions.contains(sessionId)) {
                Log.i(TAG, "Chain " + sessionId + " canceled before iter " + iter);
                return;
            }
            String prompt = buildChainPrompt(request, history.toString());
            NativeTokenCallback ntc = new NativeTokenCallback(callback);
            String raw = nativeGenerate(mNativeModelPtr, prompt, maxTokens,
                    toolCallTemp, ntc);
            Log.i(TAG, "iter=" + iter + " raw=" + truncateForLog(raw));

            int start = raw == null ? -1 : raw.indexOf("<tool_call>");
            int end = raw == null ? -1 : raw.indexOf("</tool_call>",
                    Math.max(0, start));
            if (start < 0 || end < 0) {
                // No tool_call → the model chose to answer directly. Emit
                // verbatim (it's already prose) with chat tokens stripped.
                String clean = stripChatRemnants(raw == null ? "" : raw);
                try { callback.onToken(clean); } catch (RemoteException re) {}
                callback.onComplete(clean);
                Log.i(TAG, "Chain " + sessionId + " completed at iter " + iter
                        + " with direct answer (" + clean.length() + " chars)");
                return;
            }

            String toolCallBlock = raw.substring(start,
                    end + "</tool_call>".length());
            String body = raw.substring(start + "<tool_call>".length(), end).trim();
            String toolName = null;
            String argsStr = "{}";
            try {
                JSONObject obj = new JSONObject(body);
                toolName = obj.optString("name", null);
                JSONObject args = obj.optJSONObject("arguments");
                if (args != null) argsStr = args.toString();
            } catch (Exception e) {
                Log.w(TAG, "Bad tool_call JSON: " + body);
            }

            if (toolName == null) {
                // Malformed → treat like no tool call and break out.
                String clean = stripChatRemnants(raw);
                try { callback.onToken(clean); } catch (RemoteException re) {}
                callback.onComplete(clean);
                return;
            }

            String toolResponse = dispatchOneTool(sessionId, userId, iter,
                    toolName, argsStr, callback);

            // Append assistant's tool_call + the response as user turn so
            // the next iteration sees the full trace.
            history.append("<|im_start|>assistant\n").append(toolCallBlock)
                    .append("\n<|im_end|>\n");
            history.append("<|im_start|>user\n<tool_response>\n")
                    .append(toolResponse).append("\n</tool_response>\n<|im_end|>\n");

            // Unknown-tool circuit breaker: 2 in a row → give up chaining
            // and let the model answer.
            boolean unknown = toolResponse != null
                    && toolResponse.contains("\"unknown tool");
            unknownStreak = unknown ? unknownStreak + 1 : 0;
            if (unknownStreak >= 2) {
                Log.w(TAG, "Chain " + sessionId + " bailing — 2 unknown tools");
                break;
            }

            // v0.5.1: short-circuit on needs_permission. The launcher's
            // PermissionRequiredCard (keyed on this error string in the
            // tool_result we already emitted via fireToolResult in
            // dispatchOneTool) is the correct user-facing element — a
            // "Open settings" button with a working deep link. Running a
            // final answer pass just produces redundant prose ("Please
            // go to Settings...") that then replaces the card in the
            // chat view, stealing the actionable affordance. Skip the
            // answer pass, close the stream cleanly, leave the card as
            // the final message.
            if (toolResponse != null
                    && toolResponse.contains("\"error\":\"needs_permission\"")) {
                Log.i(TAG, "Chain " + sessionId
                        + " short-circuit on needs_permission (iter=" + iter + ")");
                try { callback.onToken(""); } catch (RemoteException re) {}
                try { callback.onComplete(""); } catch (RemoteException re) {}
                return;
            }
        }

        // Exhausted iterations or bailed — take one final answer pass with
        // the full history in context.
        if (mCanceledSessions.contains(sessionId)) return;
        String finalPrompt = buildChainPrompt(request, history.toString());
        NativeTokenCallback ntc2 = new NativeTokenCallback(callback);
        String finalRaw = nativeGenerate(mNativeModelPtr, finalPrompt, maxTokens,
                answerTemp, ntc2);
        String clean = stripChatRemnants(finalRaw == null ? "" : finalRaw);
        try { callback.onToken(clean); } catch (RemoteException re) {}
        callback.onComplete(clean);
        Log.i(TAG, "Chain " + sessionId + " completed via final answer pass ("
                + clean.length() + " chars)");
    }

    /**
     * Build a ChatML prompt that includes the system contract, the user
     * turn, any prior assistant/tool history for this chain, and an open
     * assistant turn for the model to fill.
     */
    private String buildChainPrompt(LlmRequest request, String history) {
        StringBuilder sb = new StringBuilder();
        sb.append("<|im_start|>system\n");
        sb.append(composeSystemPrompt(request));
        sb.append("<|im_end|>\n");
        // Prior-turn conversation (launcher-side history). Each entry is
        // {"role":"user"|"assistant"|"tool","content":"..."}. Skip the
        // current user prompt if the launcher included it, to avoid
        // duplicating it below.
        appendConversationHistory(sb, request);
        sb.append("<|im_start|>user\n");
        sb.append(escapeChat(request.prompt));
        sb.append("\n<|im_end|>\n");
        // In-chain history (this submit's own tool_call / tool_response
        // pairs) is appended after the user turn so the model sees its
        // prior actions within the current request.
        if (history != null && !history.isEmpty()) {
            sb.append(history);
        }
        sb.append("<|im_start|>assistant\n");
        // v0.5.1: record full ChatML prompt size for `dumpsys llm`.
        recordPromptSize(PROMPT_SIZE_FULL, sb.length());
        return sb.toString();
    }

    /**
     * Append previous-turn messages from {@link LlmRequest#conversationJson}
     * (launcher-managed history) as ChatML turns. Best-effort — a parse
     * failure just skips the history.
     */
    private void appendConversationHistory(StringBuilder sb, LlmRequest request) {
        if (request.conversationJson == null
                || request.conversationJson.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray(request.conversationJson);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.optJSONObject(i);
                if (m == null) continue;
                String role = m.optString("role", "user");
                String content = m.optString("content", "");
                if (content.isEmpty()) continue;
                // Avoid duplicating the current user prompt if the
                // launcher stuffed it into history as the last entry.
                if (i == arr.length() - 1 && "user".equals(role)
                        && content.equals(request.prompt)) continue;
                sb.append("<|im_start|>").append(role).append("\n");
                sb.append(escapeChat(content));
                sb.append("\n<|im_end|>\n");
            }
        } catch (Exception e) {
            Log.w(TAG, "Bad conversationJson: " + e);
        }
    }

    /**
     * Dispatch exactly one tool call: resolve the route, check persisted
     * consent, prompt the user if needed, bind + invoke, fire typed events,
     * and audit every step. Returns the tool response JSON (always a valid
     * string, synthesizing an {@code {"error":...}} on any failure).
     */
    private String dispatchOneTool(String sessionId, int userId, int iterIndex,
            String toolName, String argsStr, ILlmResponseCallback callback) {
        String pkgName, svcName;
        Boolean requiresConsentBox;
        synchronized (mToolRouteLock) {
            pkgName = mToolToPackage.get(toolName);
            svcName = mToolToService.get(toolName);
            requiresConsentBox = mToolRequiresConsent.get(toolName);
        }
        long t0 = SystemClock.uptimeMillis();
        if (pkgName == null || svcName == null) {
            String err = "{\"error\":\"unknown tool: " + toolName + "\"}";
            fireToolStarted(callback, sessionId, toolName, null, null,
                    argsStr, iterIndex);
            fireToolResult(callback, sessionId, toolName, null, null,
                    argsStr, err, McpToolCallInfo.STATUS_FAILED, 0, iterIndex);
            recordAudit(userId, sessionId, "(unknown)", toolName, argsStr,
                    err, McpToolCallInfo.STATUS_FAILED,
                    HitlConsentStore.CONSENT_NONE, 0, iterIndex);
            return err;
        }

        // Built-in framework tool (launch_app) — no bind, no consent
        // (unless we later gate it). Fires STARTED so the launcher can
        // react (it reads packageName==BUILTIN_PKG and fires the
        // launch Intent itself), then synthesizes a success response so
        // the model continues the chain. Fire-and-forget: the real side
        // effect (the app opening) is visible to the user.
        if (BUILTIN_PKG.equals(pkgName)) {
            fireToolStarted(callback, sessionId, toolName, pkgName, svcName,
                    argsStr, iterIndex);
            String result = "{\"status\":\"launched\",\"args\":" + argsStr + "}";
            int duration = (int) (SystemClock.uptimeMillis() - t0);
            fireToolResult(callback, sessionId, toolName, pkgName, svcName,
                    argsStr, result, McpToolCallInfo.STATUS_COMPLETED,
                    duration, iterIndex);
            recordAudit(userId, sessionId, pkgName, toolName, argsStr, result,
                    McpToolCallInfo.STATUS_COMPLETED,
                    HitlConsentStore.CONSENT_NONE, duration, iterIndex);
            return result;
        }

        boolean requiresConsent = requiresConsentBox != null && requiresConsentBox;

        // v0.5 UX fix — fire STARTED *before* the consent gate so the
        // user sees a tool-call card immediately when the model asks,
        // not only after they tap Allow. Matters most when consent
        // takes 60 s or the user is confused about why nothing
        // happened. The PERMISSION_REQUIRED event (below) is emitted
        // in addition, not instead — UI state distinguishes them.
        fireToolStarted(callback, sessionId, toolName, pkgName, svcName,
                argsStr, iterIndex);

        // --- HITL consent gate ---
        int consentAuditCode = HitlConsentStore.CONSENT_NONE;
        int grantDecision = HitlConsentStore.DECISION_ALLOW;
        if (requiresConsent) {
            HitlConsentStore.Decision persisted = mConsentStore == null
                    ? HitlConsentStore.Decision.PROMPT
                    : mConsentStore.check(userId, pkgName, toolName, sessionId);
            if (persisted == HitlConsentStore.Decision.DENY_PERSISTED) {
                String err = "{\"error\":\"denied_by_user\"}";
                fireToolResult(callback, sessionId, toolName, pkgName, svcName,
                        argsStr, err, McpToolCallInfo.STATUS_FAILED, 0, iterIndex);
                recordAudit(userId, sessionId, pkgName, toolName, argsStr, err,
                        McpToolCallInfo.STATUS_FAILED,
                        HitlConsentStore.CONSENT_DENIED,
                        (int) (SystemClock.uptimeMillis() - t0), iterIndex);
                return err;
            }
            if (persisted == HitlConsentStore.Decision.ALLOW) {
                consentAuditCode = HitlConsentStore.CONSENT_AUTO;
            } else {
                // Prompt — but only if the device is unlocked. On the
                // keyguard we don't surface consent UI.
                if (isDeviceLocked()) {
                    String err = "{\"error\":\"device_locked\"}";
                    fireToolResult(callback, sessionId, toolName, pkgName,
                            svcName, argsStr, err,
                            McpToolCallInfo.STATUS_FAILED, 0, iterIndex);
                    recordAudit(userId, sessionId, pkgName, toolName, argsStr,
                            err, McpToolCallInfo.STATUS_FAILED,
                            HitlConsentStore.CONSENT_DENIED,
                            (int) (SystemClock.uptimeMillis() - t0), iterIndex);
                    return err;
                }
                // Emit STATUS_PERMISSION_REQUIRED so the launcher pops the
                // consent UI, and park the dispatcher on a gate.
                McpToolCallInfo ask = new McpToolCallInfo(sessionId, toolName,
                        pkgName, svcName, argsStr, null,
                        System.currentTimeMillis(),
                        McpToolCallInfo.STATUS_PERMISSION_REQUIRED, -1,
                        iterIndex);
                try { callback.onToolCall(ask); } catch (RemoteException re) {}

                ConsentGate gate = new ConsentGate();
                String gateKey = sessionId + "|" + toolName;
                mPendingGates.put(gateKey, gate);
                try {
                    boolean settled = gate.await(CONSENT_TIMEOUT_MS);
                    if (!settled || mCanceledSessions.contains(sessionId)) {
                        gate.resolve(HitlConsentStore.DECISION_DENY,
                                HitlConsentStore.SCOPE_ONCE,
                                HitlConsentStore.CONSENT_TIMED_OUT);
                    }
                    grantDecision = gate.decision();
                    int scope = gate.scope();
                    consentAuditCode = gate.auditCode();

                    if (grantDecision == HitlConsentStore.DECISION_ALLOW
                            && mConsentStore != null
                            && scope != HitlConsentStore.SCOPE_ONCE) {
                        mConsentStore.record(userId, pkgName, toolName,
                                grantDecision, scope, sessionId);
                    } else if (grantDecision == HitlConsentStore.DECISION_DENY
                            && mConsentStore != null
                            && scope == HitlConsentStore.SCOPE_FOREVER) {
                        // "Never allow" — persist the deny.
                        mConsentStore.record(userId, pkgName, toolName,
                                grantDecision, scope, sessionId);
                    }
                } finally {
                    mPendingGates.remove(gateKey);
                }

                if (grantDecision != HitlConsentStore.DECISION_ALLOW) {
                    String err = "{\"error\":\"denied_by_user\"}";
                    fireToolResult(callback, sessionId, toolName, pkgName,
                            svcName, argsStr, err,
                            McpToolCallInfo.STATUS_FAILED, 0, iterIndex);
                    recordAudit(userId, sessionId, pkgName, toolName, argsStr,
                            err, McpToolCallInfo.STATUS_FAILED, consentAuditCode,
                            (int) (SystemClock.uptimeMillis() - t0), iterIndex);
                    return err;
                }
            }
        }

        // --- Invoke ---
        // (STARTED already fired up top, before the consent gate.)
        String result = invokeMcpTool(pkgName, svcName, toolName, argsStr);
        int duration = (int) (SystemClock.uptimeMillis() - t0);
        int status = (result != null && result.contains("\"error\""))
                ? McpToolCallInfo.STATUS_FAILED
                : McpToolCallInfo.STATUS_COMPLETED;
        fireToolResult(callback, sessionId, toolName, pkgName, svcName,
                argsStr, result, status, duration, iterIndex);
        recordAudit(userId, sessionId, pkgName, toolName, argsStr, result,
                status, consentAuditCode, duration, iterIndex);
        return result;
    }

    private void recordAudit(int userId, String sessionId, String pkg,
            String tool, String args, String result, int status,
            int consentDecision, int duration, int iterIndex) {
        if (mConsentStore == null) return;
        try {
            mConsentStore.recordAudit(userId, sessionId, pkg, tool, args,
                    result, status, consentDecision, duration, iterIndex);
        } catch (Exception e) {
            Log.w(TAG, "audit write failed", e);
        }
    }

    private boolean isDeviceLocked() {
        try {
            KeyguardManager km = mContext.getSystemService(KeyguardManager.class);
            return km != null && km.isDeviceLocked();
        } catch (Exception e) {
            return false;
        }
    }

    /** Prevent user prompts from injecting ChatML / tool-call tokens. */
    private static String escapeChat(String s) {
        if (s == null) return "";
        return s.replace("<|im_start|>", "<|im_ start|>")
                .replace("<|im_end|>", "<|im_ end|>")
                .replace("<tool_call>", "<tool_ call>")
                .replace("</tool_call>", "</tool_ call>")
                .replace("<tool_response>", "<tool_ response>")
                .replace("</tool_response>", "</tool_ response>");
    }

    private static String truncateForLog(String s) {
        if (s == null) return "null";
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    /**
     * Watch for package removal so we can prune stale consent grants.
     * App-upgrade (signature change) is handled lazily on grant lookup.
     */
    private void registerPackageMonitor() {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        f.addDataScheme("package");
        mContext.registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (mConsentStore == null) return;
                Uri data = intent.getData();
                if (data == null) return;
                String pkg = data.getSchemeSpecificPart();
                boolean replacing = intent.getBooleanExtra(
                        Intent.EXTRA_REPLACING, false);
                if (replacing) return; // upgrade — keep grants, signature check gates them
                Log.i(TAG, "Pruning consent grants for removed pkg " + pkg);
                mConsentStore.onPackageRemoved(pkg);
            }
        }, f, null, null);
    }

    /** Race-safe one-shot gate for a consent prompt. */
    private static final class ConsentGate {
        private final CountDownLatch mLatch = new CountDownLatch(1);
        // [decision, scope, auditCode]
        private final AtomicReference<int[]> mResolution = new AtomicReference<>();

        boolean await(long timeoutMs) {
            try {
                return mLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        void resolve(int decision, int scope, int auditCode) {
            if (mResolution.compareAndSet(null,
                    new int[]{decision, scope, auditCode})) {
                mLatch.countDown();
            }
        }

        int decision() {
            int[] r = mResolution.get();
            return r != null ? r[0] : HitlConsentStore.DECISION_DENY;
        }
        int scope() {
            int[] r = mResolution.get();
            return r != null ? r[1] : HitlConsentStore.SCOPE_ONCE;
        }
        int auditCode() {
            int[] r = mResolution.get();
            return r != null ? r[2] : HitlConsentStore.CONSENT_TIMED_OUT;
        }
    }


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
          + "WHEN TO CALL A TOOL\n"
          + "Only call a tool when answering the user's request requires data only the phone has, OR an action only an app can perform. If you can answer from your own knowledge, do that — don't reach for a tool reflexively.\n"
          + "BUT — if the user asks you to DO something to their data (add, create, save, send, update, delete, schedule), you MUST call a tool. Never pretend you did it or describe what would happen. Find the matching tool in the list below and call it. If no listed tool fits the requested action, say so plainly.\n"
          + "ONLY call tools listed below — never invent one. If no listed tool fits the request, answer directly or tell the user you can't.\n"
          + "\n"
          + "When extracting search terms from the user's question, use the bare keyword. Strip possessives ('s), articles (the/a/an), pronouns (my/his/her), and politeness words (please/can you).\n"
          + "    \"what's John's number?\"      → query \"John\"\n"
          + "    \"find me Sarah Chen's email\" → query \"Sarah Chen\"\n"
          + "    \"show my favorite contacts\"  → call list_favorites with no args\n"
          + "    \"do I have John's email\"     → query \"John\"\n"
          + "\n"
          + "CHAINING\n"
          + "You may call multiple tools in sequence to satisfy one request. Emit AT MOST ONE <tool_call> per turn. After each <tool_response>, decide whether you have enough information to answer; if not, call the next tool. Stop calling tools as soon as you can answer or once a tool can't help. Maximum of 5 tool calls per request.\n";

    private static final String OUTPUT_FORMAT_BLOCK =
            "OUTPUT FORMAT\n"
          + "- To call a tool, emit ONLY:\n"
          + "    <tool_call>{\"name\":\"...\",\"arguments\":{...}}</tool_call>\n"
          + "  No prose before or after. The OS handles dispatch and replies in the next turn with <tool_response>...</tool_response>.\n"
          + "- When answering, be concise. 1–3 sentences default. Use a bulleted list for multiple items. Plain text only — no markdown headers, no code fences, no JSON.\n"
          + "- On a tool error, one sentence + one next step.\n"
          + "- Don't call a tool for things you already know. Examples that DON'T need a tool: \"what's 2+2\", \"capital of France\", \"how many days in February\". Just answer.\n";

    /**
     * Negative-example routing. Steers the model away from pre-trained
     * shell / filesystem / ContentResolver habits toward the provided
     * MCP tools. Without this, Qwen 2.5 3B will occasionally suggest
     * `adb shell`, `cat /data/data/...`, or `content query ...` to the
     * user when the right answer is the listed tool.
     */
    private static final String TOOL_ROUTING_RULES =
            "TOOL ROUTING — strict\n"
          + "The user does not have a shell or root. NEVER suggest shell / adb / pm / content / cat / grep commands or content:// URIs. ALWAYS use one of the listed tools above.\n"
          + "- Contacts questions → search_contacts / get_contact / list_favorites / add_contact / update_contact. Never suggest reading /data/data/com.android.providers.contacts/ or using ContentResolver directly.\n"
          + "- Calendar questions → list_events / find_free_time / create_event. Never suggest reading calendar provider files or the Calendar ContentUris directly.\n"
          + "- Opening / starting / launching an app → the built-in launch_app tool with a name (fuzzy match works: \"launch_app\" args:{\"name\":\"Camera\"}). Never suggest \"tap the app icon\" or \"open the app drawer\" — the user asked you to do it, so call the tool.\n"
          + "If no listed tool matches, say so plainly in one sentence and stop. Do not describe workarounds the user cannot perform.\n";

    private static final String FEW_SHOT_EXAMPLES =
            "EXAMPLES\n"
          + "\n"
          + "(direct answer, no tool needed)\n"
          + "User: what's the capital of France?\n"
          + "Assistant: Paris.\n"
          + "\n"
          + "(launch an app — use launch_app for 'open X' / 'launch X' / 'start X')\n"
          + "User: open Settings\n"
          + "Assistant: <tool_call>{\"name\":\"launch_app\",\"arguments\":{\"name\":\"Settings\"}}</tool_call>\n"
          + "\n"
          + "(single tool call — read)\n"
          + "User: what's John's number?\n"
          + "Assistant: <tool_call>{\"name\":\"search_contacts\",\"arguments\":{\"query\":\"John\"}}</tool_call>\n"
          + "<tool_response>[{\"name\":\"John Smith\",\"phone\":\"555-1234\"},{\"name\":\"John Appleseed\",\"phone\":\"555-9876\"}]</tool_response>\n"
          + "Assistant: Two Johns in your contacts:\n"
          + "- John Smith — 555-1234\n"
          + "- John Appleseed — 555-9876\n"
          + "Which one?\n"
          + "\n"
          + "(single tool call — write. ALWAYS call the tool for add/create/save/send/update requests)\n"
          + "User: add Sarah Chen to my contacts with phone 555-9999\n"
          + "Assistant: <tool_call>{\"name\":\"add_contact\",\"arguments\":{\"name\":\"Sarah Chen\",\"phone\":\"555-9999\"}}</tool_call>\n"
          + "\n"
          + "(write tool — append to existing contact)\n"
          + "User: add 555-4321 as John Smith's work number\n"
          + "Assistant: <tool_call>{\"name\":\"update_contact\",\"arguments\":{\"name\":\"John Smith\",\"phone\":\"555-4321\"}}</tool_call>\n"
          + "\n"
          + "(empty result — say so plainly, don't invent)\n"
          + "User: what's Maria's number?\n"
          + "Assistant: <tool_call>{\"name\":\"search_contacts\",\"arguments\":{\"query\":\"Maria\"}}</tool_call>\n"
          + "<tool_response>[]</tool_response>\n"
          + "Assistant: No contact named Maria in your phone.\n"
          + "\n"
          + "(write tool — minimal)\n"
          + "User: save my mom's number 555-8888\n"
          + "Assistant: <tool_call>{\"name\":\"add_contact\",\"arguments\":{\"name\":\"Mom\",\"phone\":\"555-8888\"}}</tool_call>\n"
          + "\n"
          + "(consent denied by user — don't retry)\n"
          + "User: add Bob / 555-1111 to my contacts\n"
          + "Assistant: <tool_call>{\"name\":\"add_contact\",\"arguments\":{\"name\":\"Bob\",\"phone\":\"555-1111\"}}</tool_call>\n"
          + "<tool_response>{\"error\":\"denied_by_user\"}</tool_response>\n"
          + "Assistant: I didn't add the contact — you declined.\n";

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
        sb.append("\n").append(TOOL_ROUTING_RULES);
        sb.append("\n").append(FEW_SHOT_EXAMPLES);
        // v0.5.1: record the final prompt character count so `dumpsys llm`
        // can show a rolling histogram. Chars, not tokens — Qwen BPE is
        // ~3.5–4 chars/token for English, so divide by 4 for a rough
        // token estimate. We measure the system message only; the full
        // ChatML prompt (with user turn + history) is measured in
        // buildChainPrompt.
        recordPromptSize(PROMPT_SIZE_SYSTEM, sb.length());
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
        java.util.Map<String, Boolean> toolRequiresConsent = new java.util.HashMap<>();
        for (McpServerInfo s : allServers) {
            if (s.tools == null) continue;
            for (McpToolInfo t : s.tools) {
                tools.add(t);
                toolToPackage.put(t.name, s.packageName);
                toolToService.put(t.name, s.name);
                toolRequiresConsent.put(t.name, t.requiresConfirmation);
            }
        }
        // Always register the built-in launch_app tool — works even when
        // zero MCP apps are installed. "Open Settings" / "launch Camera"
        // must not depend on any MCP provider because being launchable
        // is a universal property of every installed app, not something
        // any one app should own.
        toolToPackage.put(TOOL_LAUNCH_APP, BUILTIN_PKG);
        toolToService.put(TOOL_LAUNCH_APP, BUILTIN_SVC);
        toolRequiresConsent.put(TOOL_LAUNCH_APP, false);

        // Cache the routing tables so the dispatcher can find the service later.
        synchronized (mToolRouteLock) {
            mToolToPackage = toolToPackage;
            mToolToService = toolToService;
            mToolRequiresConsent = toolRequiresConsent;
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
        // Built-in launch_app tool — framework-provided, no MCP required.
        // Takes a user-facing app name; launcher fuzzy-matches against
        // installed apps and fires the launch intent itself.
        sb.append("{\"type\":\"function\",\"function\":{");
        sb.append("\"name\":\"launch_app\",");
        sb.append("\"description\":\"Open an installed app. Use this for requests like 'open Settings', 'launch Camera', 'start the browser'. The name argument is the human-readable app name as the user would say it.\",");
        sb.append("\"parameters\":{\"type\":\"object\",\"properties\":{");
        sb.append("\"name\":{\"type\":\"string\",\"description\":\"App name as a user would say it (e.g., 'Settings', 'Camera', 'Calendar').\"}");
        sb.append("},\"required\":[\"name\"]}}}\n");
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
    private java.util.Map<String, Boolean> mToolRequiresConsent = new java.util.HashMap<>();

    // ============================================================
    //  Built-in tools (v0.5) — synthesized by the framework, not
    //  backed by an MCP service. Using the reserved "android" package
    //  name as the marker so dispatcher + launcher can recognize them.
    // ============================================================
    private static final String BUILTIN_PKG = "android";
    private static final String BUILTIN_SVC = "<builtin>";
    private static final String TOOL_LAUNCH_APP = "launch_app";

    private void fireToolStarted(ILlmResponseCallback cb, String sessionId,
            String tool, String pkg, String svc, String args, int iterIndex) {
        McpToolCallInfo info = new McpToolCallInfo(sessionId, tool, pkg, svc,
                args, null, System.currentTimeMillis(),
                McpToolCallInfo.STATUS_STARTED, -1, iterIndex);
        try { cb.onToolCall(info); } catch (RemoteException ignored) {}
    }

    private void fireToolResult(ILlmResponseCallback cb, String sessionId,
            String tool, String pkg, String svc, String args, String result,
            int status, int duration, int iterIndex) {
        McpToolCallInfo info = new McpToolCallInfo(sessionId, tool, pkg, svc,
                args, result, System.currentTimeMillis(), status, duration,
                iterIndex);
        try { cb.onToolResult(info); } catch (RemoteException ignored) {}
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
            // v0.5.1: bumped from 10s to 60s so the dispatcher wait
            // exceeds the MCP side's 30s permission gate and matches
            // CONSENT_TIMEOUT_MS. With the old 10s cap the dispatcher
            // returned "tool timeout" before a legitimately slow tool
            // (one waiting on user interaction) could respond, and the
            // launcher never saw the intended {"error":"needs_permission"}
            // JSON — so PermissionRequiredCard never fired.
            if (!latch.await(60, java.util.concurrent.TimeUnit.SECONDS)) {
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
