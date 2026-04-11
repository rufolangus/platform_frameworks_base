/*
 * Copyright (C) 2024 The AAOSP Project
 * Licensed under the Apache License, Version 2.0
 */
package android.llm;

/** @hide */
interface IMcpToolProvider {
    String invokeTool(String toolName, String argumentsJson);
    String readResource(String resourceName);
    String listResources(String uriPattern);
}
