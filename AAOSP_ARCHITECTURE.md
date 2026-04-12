# AAOSP — Agentic Android Open Source Project

A fork of Android 15 (AOSP `trunk_staging`) that integrates a Model Context
Protocol (MCP) host and an on-device LLM **as native OS services**, so that
any third-party app can declare tools and resources in its manifest and have
them discovered and invokable by the system LLM.

## Vision

Today, MCP runs in app processes and talks to remote LLMs. AAOSP moves both
sides into the platform:

- **`LlmManagerService`** runs inside `system_server` and exposes a binder
  API (`android.llm.ILlmService`) backed by a JNI bridge to llama.cpp
  (Qwen 2.5 0.5B as the bring-up model).
- **`McpRegistry`** is a system-wide registry of MCP servers contributed by
  installed apps via `<mcp-server>` declarations in `AndroidManifest.xml`.
- The launcher (`AgenticLauncher`) is the user-facing chat surface; a
  reference MCP-providing app (`ContactsMcp`) demonstrates the contract.

## Architecture

```
                       ┌────────────────────────────────────────┐
                       │             system_server              │
                       │                                        │
   AgenticLauncher ──► │  LlmManagerService (ILlmService)       │
                       │   ├─ NativeTokenCallback (R8 -keep)    │
                       │   ├─ JNI: System.loadLibrary("llm_jni")│
                       │   │      fallback /data/local/llm/...  │
                       │   └─ discoverMcpServices() at          │
                       │      PHASE_BOOT_COMPLETED              │
                       │         │                              │
                       │         ▼                              │
                       │  McpRegistry / McpPackageHandler       │
                       │   ├─ McpManifestParser                 │
                       │   │   parses <mcp-server>/<tool>/      │
                       │   │   <input>/<resource> from APK      │
                       │   └─ keyed by package name             │
                       └────────────────────────────────────────┘
                                    │
                                    ▼ binder
                       ┌────────────────────────────────────────┐
                       │  ContactsMcpService (system_ext app)   │
                       │  permission = BIND_LLM_MCP_SERVICE     │
                       │  tools: search_contacts, get_contact,  │
                       │         list_favorites                  │
                       └────────────────────────────────────────┘

  llama.cpp (libllm_jni.so) ◄─── JNI ── LlmManagerService
  Qwen 2.5 0.5B (.gguf)     ◄── mmap ── llama.cpp
```

## Manifest contract

An MCP-providing app declares both an Android `<service>` (the binder
endpoint) and an `<mcp-server>` (the metadata the registry parses):

```xml
<application>
  <service
      android:name=".MyMcpService"
      android:permission="android.permission.BIND_LLM_MCP_SERVICE"
      android:exported="true" />

  <mcp-server
      android:name=".MyMcpService"
      android:description="@string/desc"
      android:permission="android.permission.BIND_LLM_MCP_SERVICE">
    <tool android:name="my_tool" android:description="@string/tool_desc">
      <input android:name="arg" android:description="@string/arg_desc" />
    </tool>
    <resource android:name="thing"
              android:mcpUri="content://com.example/things"
              android:mimeType="application/json" />
  </mcp-server>
</application>
```

`BIND_LLM_MCP_SERVICE` is declared in framework with
`protectionLevel="signature|privileged"` — only platform-signed or
privileged (`/system/{,_ext}/priv-app/`) apps can host MCP services.

## Repository inventory

All repos branch from AOSP `android-15.0.0_r1` unless noted. Repos marked
**(fork)** have a GitHub fork; the rest are committed locally pending fork
creation.

| Repo | Branch | Key changes |
|------|--------|-------------|
| `frameworks/base` | `aaosp-v15` | `LlmManagerService`, `McpRegistry`, `McpManifestParser`, `McpPackageHandler`, `ParsingPackageUtils` (`<mcp-server>` skip), `ManifestFixer.cpp` (aapt2 whitelist), `BIND_LLM_MCP_SERVICE` permission, `proguard.flags` (`-keep` JNI callbacks), `ILlmService.aidl`, `LlmRequest`, `McpServerInfo`/`McpToolInfo`/`McpResourceInfo`/`McpInputInfo` parcelables |
| `external/llama.cpp` **(fork)** | `main` | `Android.bp` for `libllama` + `libllm_jni`; `jni/llm_jni.cpp`; `ggml-cpu-impl.c` rename to dodge dup-basename; b4547 baseline |
| `packages/apps/AgenticLauncher` **(fork)** | `main` | Compose-based chat surface; binds `ILlmService`; reads `McpRegistry` via `McpPackageHandler.getRegistry()`; `privapp-permissions-agenticlauncher.xml` allowlists `SUBMIT_LLM_REQUEST`/`QUERY_ALL_PACKAGES` |
| `packages/apps/ContactsMcp` | n/a (in-tree) | Reference MCP provider: `<mcp-server>` with `search_contacts`, `get_contact`, `list_favorites` tools |
| `system/sepolicy` | aaosp branch | `private/service_contexts` adds `llm` service entry; `service_fuzzer_bindings.go` exception |
| `device/google/cuttlefish` | aaosp branch | `vsoc_x86_64/phone/aosp_cf.mk`: relaxed artifact path requirements; product partition tweaks |
| `build/make` | aaosp branch | `target/product/handheld_system_ext.mk`: `PRODUCT_PACKAGES += AgenticLauncher ContactsMcp`; copies privapp xml |

## Critical fixes applied during bring-up

These took multiple cycles to nail down — recording so we don't repeat them:

1. **R8 stripped JNI callback methods** — added
   `-keep class com.android.server.llm.LlmManagerService$NativeTokenCallback { *; }`
   in `frameworks/base/services/proguard.flags`. The class is also `public`
   with `public` methods so the keep matches.
2. **aapt2 rejected `<mcp-server>`** — patched
   `tools/aapt2/link/ManifestFixer.cpp` to whitelist `<mcp-server>` and its
   `<tool>`/`<input>`/`<resource>` children under `<application>`.
3. **PMS dropped `<service>` after `<mcp-server>`** — patched
   `ParsingPackageUtils.java` so the `<mcp-server>` case calls
   `XmlUtils.skipCurrentTag(parser)` and returns success, not consuming
   sibling tags.
4. **`BIND_LLM_MCP_SERVICE` was `signature`-only** — privileged system_ext
   apps can't be platform-signed; bumped to `signature|privileged`.
5. **Discovery ran before PMS finished scanning** — moved
   `discoverMcpServices()` from `PHASE_SYSTEM_SERVICES_READY` (500) to
   `PHASE_BOOT_COMPLETED` (1000).
6. **`libllm_jni.so` not auto-installed to `/system/lib64`** — currently
   pushed to `/data/local/llm/` post-boot; `static {}` block in
   `LlmManagerService` falls back to `System.load("/data/local/llm/...")`.
   TODO: install via Soong `system_lib_64` + signing.
7. **dm-verity hash mismatch when only `system.img` rebuilt** — must do
   full `m -j32` (or `m droid`), not just `m systemimage`, otherwise the
   device boots into recovery.
8. **Cuttlefish quirk: `adb reboot` doesn't restart the VM cleanly** —
   pipeline must do `stop_cvd && launch_cvd` for a real cycle.
9. **`launch_cvd` wipes `/data`** — the lib + model push must come *after*
   the final launch, not before.

## Build & run

```bash
# One-time
source build/envsetup.sh
lunch aosp_cf_x86_64_phone-trunk_staging-userdebug

# Build (full image — incremental systemimage alone is a trap)
m -j32

# Boot
launch_cvd --daemon \
  --gpu_mode=guest_swiftshader --start_webrtc=true \
  --cpus=8 --memory_mb=8192 \
  --extra_kernel_cmdline='androidboot.selinux=permissive'

# Side-load JNI lib + model into the booted device
adb root && adb shell mkdir -p /data/local/llm
adb push out/soong/.intermediates/external/llama.cpp/libllm_jni/\
android_product_x86_64_silvermont_shared/libllm_jni.so /data/local/llm/
adb push external/llama.cpp/models/qwen2.5-0.5b-instruct-q8_0.gguf /data/local/llm/
adb shell stop && adb shell start    # framework restart loads JNI fresh

# Verify
adb shell service list | grep llm
adb shell logcat -d -s LlmManagerService LlmJNI McpManifestParser
```

## Adding a new MCP-providing app

1. Drop the APK in `packages/apps/YourMcpApp/` with the two-tag manifest
   pattern shown above.
2. Add to `build/make/target/product/handheld_system_ext.mk`:
   `PRODUCT_PACKAGES += YourMcpApp`.
3. Sign as a platform/system_ext app (placed under `/system_ext/priv-app/`).
4. On boot, `LlmManagerService.discoverMcpServices()` will pick it up at
   `PHASE_BOOT_COMPLETED` and register tools in `McpRegistry`.

## Known gaps / TODO

- `libllm_jni.so` install path → ship in `/system/lib64` not `/data/local/`.
- `qwen2.5-0.5b.gguf` ships as a runtime push, not yet baked into a system
  partition (size considerations).
- `dumpsys llm` segfaults — needs proper `dump()` impl.
- `McpManifestParser` is wired but currently bypassed by hardcoded fallback
  in `LlmManagerService.discoverMcpServices()` for the contacts package.
- No human-in-the-loop consent UI yet for tool invocations.
- `LlmSessionStore` (SQLite session persistence) scaffolded but not wired.
- Soong incremental cache occasionally serves stale `framework.jar` after
  source edits; full `m -j32` is the workaround.
