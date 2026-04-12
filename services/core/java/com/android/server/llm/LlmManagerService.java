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
import android.content.pm.mcp.McpToolInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.util.Log;

import com.android.server.SystemService;
import com.android.server.pm.McpPackageHandler;

import java.io.File;
import java.util.UUID;

public class LlmManagerService extends SystemService {

    private static final String TAG = "LlmManagerService";
    private static final String SERVICE_NAME = "llm";
    private static final String MODEL_DIR = "/data/local/llm";
    private static final String SYSTEM_MODEL_DIR = "/system/etc/llm";

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
            Log.w(TAG, "No model file found in " + MODEL_DIR + " or " + SYSTEM_MODEL_DIR);
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
        // Check data partition first
        File dir = new File(MODEL_DIR);
        if (dir.exists()) {
            File[] files = dir.listFiles((d, name) -> name.endsWith(".gguf"));
            if (files != null && files.length > 0) {
                return files[0].getAbsolutePath();
            }
        }
        // Check system partition
        dir = new File(SYSTEM_MODEL_DIR);
        if (dir.exists()) {
            File[] files = dir.listFiles((d, name) -> name.endsWith(".gguf"));
            if (files != null && files.length > 0) {
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
                pm.getInstalledPackages(PackageManager.GET_SERVICES);

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
                    callback.onError(1, "Model not loaded. Push a .gguf file to /data/local/llm/");
                } catch (RemoteException e) {}
                return sessionId;
            }

            // Run inference on background thread
            mInferenceHandler.post(() -> {
                try {
                    String prompt = buildPrompt(request);
                    Log.i(TAG, "Generating for prompt: " + prompt.substring(0, Math.min(100, prompt.length())));

                    String result = nativeGenerate(
                            mNativeModelPtr,
                            prompt,
                            request.maxTokens > 0 ? request.maxTokens : 256,
                            request.temperature,
                            new NativeTokenCallback(callback));

                    callback.onComplete(result);
                    Log.i(TAG, "Generation complete: " + result.length() + " chars");
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
                return "No model loaded. Push .gguf to /data/local/llm/";
            }
            return nativeGetModelInfo(mNativeModelPtr);
        }
    };

    private String buildPrompt(LlmRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("<|im_start|>system\n");
        if (request.systemPrompt != null) {
            sb.append(request.systemPrompt);
        } else {
            sb.append("You are a helpful AI assistant running on Android.");
        }
        sb.append("\n<|im_end|>\n");
        sb.append("<|im_start|>user\n");
        sb.append(request.prompt);
        sb.append("\n<|im_end|>\n");
        sb.append("<|im_start|>assistant\n");
        return sb.toString();
    }

    public static class NativeTokenCallback {
        private final ILlmResponseCallback mCallback;
        public NativeTokenCallback(ILlmResponseCallback callback) {
            mCallback = callback;
        }
        @SuppressWarnings("unused")
        public void onToken(String token) {
            try { mCallback.onToken(token); }
            catch (RemoteException e) {}
        }
        @SuppressWarnings("unused")
        public boolean isCancelled() { return false; }
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
