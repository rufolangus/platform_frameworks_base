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

package com.android.server.llm;

import android.app.ActivityManager;
import android.content.Context;
import android.os.SystemProperties;
import android.util.Log;

import java.io.File;

/**
 * Selects and configures the LLM model based on device capabilities.
 *
 * <p>Model selection follows a tiered approach based on available RAM:
 * <ul>
 *   <li><b>High</b> (12GB+ RAM): Qwen 2.5 7B Q4_K_M — best quality</li>
 *   <li><b>Mid</b> (8GB+ RAM): Qwen 2.5 3B Q4_K_M — recommended default</li>
 *   <li><b>Low</b> (4-8GB RAM): Qwen 2.5 1.5B Q4_K_M — fast, lighter</li>
 *   <li><b>Minimal</b> (&lt;4GB RAM): Qwen 2.5 0.5B Q8_0 — basic capability</li>
 * </ul>
 *
 * <p>All config can be overridden via system properties:
 * <ul>
 *   <li>{@code persist.llm.model.path} — absolute path to .gguf file</li>
 *   <li>{@code persist.llm.model.tier} — force a tier: "high", "mid", "low", "minimal"</li>
 *   <li>{@code persist.llm.context_size} — context window in tokens</li>
 *   <li>{@code persist.llm.gpu_layers} — layers to offload to GPU</li>
 *   <li>{@code persist.llm.n_threads} — inference threads (default: big core count)</li>
 * </ul>
 *
 * <p>OEMs can also override via resource overlays:
 * <ul>
 *   <li>{@code config_llm_model_high}, {@code config_llm_model_mid}, etc.</li>
 *   <li>{@code config_llm_context_size}</li>
 * </ul>
 */
public class LlmModelConfig {

    private static final String TAG = "LlmModelConfig";

    /** Base directory for LLM model files. */
    private static final String MODEL_DIR = "/data/local/llm";

    /** Fallback directory in the system partition (for pre-installed models). */
    private static final String SYSTEM_MODEL_DIR = "/system/etc/llm";

    // Model filenames for each tier (Qwen 2.5 family)
    private static final String MODEL_HIGH = "qwen2.5-7b-instruct-q4_k_m.gguf";
    private static final String MODEL_MID = "qwen2.5-3b-instruct-q4_k_m.gguf";
    private static final String MODEL_LOW = "qwen2.5-1.5b-instruct-q4_k_m.gguf";
    private static final String MODEL_MINIMAL = "qwen2.5-0.5b-instruct-q8_0.gguf";

    // RAM thresholds (in MB)
    private static final long RAM_HIGH = 12 * 1024;     // 12 GB
    private static final long RAM_MID = 8 * 1024;       // 8 GB
    private static final long RAM_LOW = 4 * 1024;       // 4 GB

    public enum Tier {
        HIGH, MID, LOW, MINIMAL
    }

    /** Resolved model configuration. */
    public static class Config {
        public final String modelPath;
        public final int contextSize;
        public final int gpuLayers;
        public final int nThreads;
        public final Tier tier;
        public final String modelName;

        Config(String modelPath, int contextSize, int gpuLayers,
                int nThreads, Tier tier, String modelName) {
            this.modelPath = modelPath;
            this.contextSize = contextSize;
            this.gpuLayers = gpuLayers;
            this.nThreads = nThreads;
            this.tier = tier;
            this.modelName = modelName;
        }

        @Override
        public String toString() {
            return "LlmConfig{model=" + modelName
                    + ", tier=" + tier
                    + ", ctx=" + contextSize
                    + ", threads=" + nThreads
                    + ", gpu_layers=" + gpuLayers
                    + ", path=" + modelPath + "}";
        }
    }

    /**
     * Resolve the model configuration for this device.
     *
     * Priority:
     * 1. System property overrides (persist.llm.*)
     * 2. Device capability auto-detection
     * 3. Fallback defaults
     */
    public static Config resolve(Context context) {
        // Check for explicit path override
        String overridePath = SystemProperties.get("persist.llm.model.path", "");
        if (!overridePath.isEmpty() && new File(overridePath).exists()) {
            Log.i(TAG, "Using override model path: " + overridePath);
            return new Config(
                    overridePath,
                    getContextSize(),
                    getGpuLayers(),
                    getThreadCount(),
                    Tier.MID,
                    new File(overridePath).getName());
        }

        // Determine tier
        Tier tier = detectTier(context);
        String tierOverride = SystemProperties.get("persist.llm.model.tier", "");
        if (!tierOverride.isEmpty()) {
            try {
                tier = Tier.valueOf(tierOverride.toUpperCase());
                Log.i(TAG, "Using override tier: " + tier);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Invalid tier override: " + tierOverride);
            }
        }

        // Resolve model file for tier
        String modelFile = getModelFileForTier(tier);
        String modelPath = findModelFile(modelFile);

        if (modelPath == null) {
            // Try falling back to lower tiers
            Log.w(TAG, "Model " + modelFile + " not found, trying lower tiers");
            Tier[] fallbackOrder = {Tier.MID, Tier.LOW, Tier.MINIMAL};
            for (Tier fallback : fallbackOrder) {
                if (fallback.ordinal() <= tier.ordinal()) continue;
                String fallbackFile = getModelFileForTier(fallback);
                modelPath = findModelFile(fallbackFile);
                if (modelPath != null) {
                    tier = fallback;
                    modelFile = fallbackFile;
                    Log.i(TAG, "Fell back to " + tier + ": " + modelFile);
                    break;
                }
            }
        }

        if (modelPath == null) {
            Log.e(TAG, "No model file found in " + MODEL_DIR
                    + " or " + SYSTEM_MODEL_DIR);
            modelPath = MODEL_DIR + "/" + MODEL_MID; // Will fail at load time
        }

        int contextSize = getContextSizeForTier(tier);
        int gpuLayers = getGpuLayers();
        int nThreads = getThreadCount();

        Config config = new Config(modelPath, contextSize, gpuLayers,
                nThreads, tier, modelFile);
        Log.i(TAG, "Resolved config: " + config);
        return config;
    }

    /**
     * Auto-detect the appropriate tier based on device RAM.
     */
    private static Tier detectTier(Context context) {
        ActivityManager am = context.getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(memInfo);

        long totalRamMB = memInfo.totalMem / (1024 * 1024);

        if (totalRamMB >= RAM_HIGH) return Tier.HIGH;
        if (totalRamMB >= RAM_MID) return Tier.MID;
        if (totalRamMB >= RAM_LOW) return Tier.LOW;
        return Tier.MINIMAL;
    }

    private static String getModelFileForTier(Tier tier) {
        switch (tier) {
            case HIGH: return MODEL_HIGH;
            case MID: return MODEL_MID;
            case LOW: return MODEL_LOW;
            case MINIMAL: return MODEL_MINIMAL;
            default: return MODEL_MID;
        }
    }

    private static int getContextSizeForTier(Tier tier) {
        int override = SystemProperties.getInt("persist.llm.context_size", 0);
        if (override > 0) return override;

        switch (tier) {
            case HIGH: return 8192;
            case MID: return 4096;
            case LOW: return 2048;
            case MINIMAL: return 1024;
            default: return 4096;
        }
    }

    /**
     * Find the model file, checking data partition first, then system.
     */
    private static String findModelFile(String filename) {
        File dataModel = new File(MODEL_DIR, filename);
        if (dataModel.exists()) return dataModel.getAbsolutePath();

        File systemModel = new File(SYSTEM_MODEL_DIR, filename);
        if (systemModel.exists()) return systemModel.getAbsolutePath();

        return null;
    }

    private static int getContextSize() {
        return SystemProperties.getInt("persist.llm.context_size", 4096);
    }

    private static int getGpuLayers() {
        return SystemProperties.getInt("persist.llm.gpu_layers", 0);
    }

    private static int getThreadCount() {
        int override = SystemProperties.getInt("persist.llm.n_threads", 0);
        if (override > 0) return override;

        // Default: use half the available processors (big cores on ARM)
        return Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    }

    /**
     * Check if any model file is available on the device.
     */
    public static boolean isModelAvailable() {
        for (Tier tier : Tier.values()) {
            if (findModelFile(getModelFileForTier(tier)) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the expected download URL for a model tier.
     * Used by the setup wizard or first-boot provisioning.
     */
    public static String getModelDownloadUrl(Tier tier) {
        String file = getModelFileForTier(tier);
        return "https://huggingface.co/Qwen/Qwen2.5-"
                + getParamString(tier) + "-Instruct-GGUF/resolve/main/"
                + file;
    }

    private static String getParamString(Tier tier) {
        switch (tier) {
            case HIGH: return "7B";
            case MID: return "3B";
            case LOW: return "1.5B";
            case MINIMAL: return "0.5B";
            default: return "3B";
        }
    }
}
