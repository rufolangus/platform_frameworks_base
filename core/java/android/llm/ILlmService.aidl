/*
 * Copyright (C) 2024 The AAOSP Project
 * Licensed under the Apache License, Version 2.0
 */
package android.llm;

import android.llm.ILlmResponseCallback;
import android.llm.LlmRequest;

/** @hide */
interface ILlmService {
    String submit(in LlmRequest request, ILlmResponseCallback callback);
    void cancel(String sessionId);
    String getAvailableServers();
    boolean isReady();
    String getModelInfo();
}
