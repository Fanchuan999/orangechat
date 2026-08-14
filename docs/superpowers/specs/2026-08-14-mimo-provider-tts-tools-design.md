# Daddy MiMo Provider, TTS Safety, and Tool Deconflict Design

Date: 2026-08-14

## Goal

Bring three low-risk RikkaHub improvements into Daddy v206 without disturbing Daddy-specific companion behavior:

- Add MiMo as a first-class chat provider so `mimo-v2.5` can be selected for normal chat.
- Keep MiniMax as the current practical TTS default, while improving MiMo TTS configuration as an optional path.
- Add a final tool deconflict guard so local, system, MCP, plugin, memory, workspace, and skill tools cannot silently overwrite each other.

This version must not change Daddy's cache-friendly context truncation, proactive messaging, night watch, Ombre/Termux integration, widgets, or companion space behavior.

## Scope

### MiMo Chat Provider

Add a built-in OpenAI-compatible provider named `MiMo` in the provider list. It should use the existing `ProviderSetting.OpenAI` shape because MiMo's chat API is OpenAI-compatible in Daddy's current architecture.

Default values:

- Name: `MiMo`
- Base URL: `https://api.xiaomimimo.com/v1`
- Chat completions path: `/chat/completions`
- Built-in models:
  - `mimo-v2.5`, text + image input, tool/reasoning abilities from `ModelRegistry`
  - `mimo-v2.5-pro`, text-only by default unless the registry says otherwise

The user still controls whether this provider is enabled and which model an assistant uses.

### MiMo TTS Optional Improvements

Do not switch the user's current TTS away from MiniMax. MiniMax remains valid and should keep working exactly as before.

Improve MiMo TTS only as an optional provider:

- Keep existing fields: API key, base URL, model, voice.
- Add optional controls only if they map cleanly to the existing MiMo TTS request path.
- If exact upstream semantics are uncertain, prefer a simple `stylePrompt` or `instruction` field over pretending full emotion support exists.

The generated request should remain compatible with the current MiMo streaming audio path.

### Tool Deconflict Guard

Daddy already namespaces MCP and plugin tools. Add a final guard in the tool surface builder to enforce unique tool names across all sources.

Behavior:

- Preserve first occurrence order.
- Drop later duplicates with the same final tool name.
- Prefer a quiet log warning over crashing.
- Do not rename local/system tools automatically because the model-visible names are part of their contract.

This is a safety layer. It should not replace existing MCP/plugin namespacing.

## Non-Goals

- No full RikkaHub merge.
- No Gradle/Kotlin version upgrade.
- No context truncation behavior change.
- No automatic TTS provider switching.
- No new voice cloning feature.
- No broad settings-page redesign.

## Testing

Add or update focused tests:

- Default providers include MiMo with `mimo-v2.5`.
- MiMo models are recognized by `ModelRegistry` as expected.
- MiMo TTS default serialization remains backward-compatible.
- Tool deconflict removes duplicate tool names while preserving first occurrence.

Manual verification after build:

- Provider page shows MiMo.
- User can add API key and select `mimo-v2.5`.
- Existing MiniMax TTS settings remain present.
- Existing MCP/plugin tools still appear with namespaced names.

## Risks

MiMo API details may differ from the current placeholder base URL or model names. To reduce risk, all provider fields stay editable.

Tool deconflict can hide a duplicate tool if two sources produce the same final name. This is preferable to sending duplicate tool names to providers and failing the whole request.

MiMo TTS style controls should stay conservative until tested against the real API key.
