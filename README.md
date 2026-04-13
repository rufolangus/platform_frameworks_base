# AAOSP fork of frameworks/base

This is the [AAOSP](https://github.com/rufolangus/AAOSP) fork of AOSP's
`frameworks/base`, branch `aaosp-v15` (rebased on `android-15.0.0_r1`).

Adds the platform pieces that make MCP and an on-device LLM first-class:

- `services/core/java/com/android/server/llm/LlmManagerService.java` —
  system service exposing `android.llm.ILlmService`; loads Qwen via JNI
  to llama.cpp; runs the agentic tool-call loop.
- `services/core/java/com/android/server/pm/{McpManifestParser,McpRegistry,McpPackageHandler}.java` —
  parses `<mcp-server>` from app manifests, system-wide tool registry.
- `core/java/android/llm/{ILlmService,IMcpToolProvider}.aidl` — binder contracts.
- `core/java/android/content/pm/mcp/{McpServerInfo,McpToolInfo,McpInputInfo,McpResourceInfo}.java` —
  MCP parcelables.
- `core/res/AndroidManifest.xml` — adds `BIND_LLM_MCP_SERVICE` permission.
- `tools/aapt2/link/ManifestFixer.cpp` — whitelists `<mcp-server>` in aapt2.
- `core/java/com/android/internal/pm/pkg/parsing/ParsingPackageUtils.java` —
  skips `<mcp-server>` cleanly so sibling `<service>` tags still parse.
- `services/proguard.flags` — `-keep` for the JNI callback class.

For the umbrella project (build instructions, demo, full architecture),
see https://github.com/rufolangus/AAOSP.

## License

Apache 2.0 (this file). The full AOSP `NOTICE` file is preserved at the
repo root.
