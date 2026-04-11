/*
 * Copyright (C) 2024 The AAOSP Project
 * Licensed under the Apache License, Version 2.0
 */

package com.android.server.llm;

import android.content.Context;
import android.llm.ILlmResponseCallback;
import android.llm.ILlmService;
import android.llm.LlmRequest;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Log;

import com.android.server.SystemService;
import com.android.server.pm.McpPackageHandler;

import java.util.UUID;

/**
 * LLM System Service — runs in system_server.
 * Minimal build-verification version.
 */
public class LlmManagerService extends SystemService {

    private static final String TAG = "LlmManagerService";
    private static final String SERVICE_NAME = "llm";

    private final Context mContext;

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
            // TODO: Initialize LlmModelConfig, LlmSessionStore,
            // McpConsentManager, load model via JNI
        }
    }

    private final ILlmService.Stub mBinder = new ILlmService.Stub() {

        @Override
        public String submit(LlmRequest request,
                ILlmResponseCallback callback) {
            mContext.enforceCallingOrSelfPermission(
                    "android.permission.SUBMIT_LLM_REQUEST",
                    "Must hold SUBMIT_LLM_REQUEST permission");

            String sessionId = UUID.randomUUID().toString();
            Log.i(TAG, "Submit request " + sessionId
                    + " from uid " + Binder.getCallingUid());

            // TODO: Run inference, tool calls, etc.
            try {
                callback.onComplete("LLM service is running but model not loaded yet.");
            } catch (RemoteException e) {
                Log.w(TAG, "Callback dead", e);
            }

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
            // Return as JSON to avoid exposing MCP parcelables in public AIDL
            java.util.List<android.content.pm.mcp.McpServerInfo> servers =
                    McpPackageHandler.getRegistry().getAllServers();
            org.json.JSONArray arr = new org.json.JSONArray();
            for (android.content.pm.mcp.McpServerInfo s : servers) {
                arr.put(s.toString());
            }
            return arr.toString();
        }

        @Override
        public boolean isReady() {
            return false; // Model not loaded in this minimal version
        }

        @Override
        public String getModelInfo() {
            return "AAOSP LLM Service (build verification)";
        }
    };
}
