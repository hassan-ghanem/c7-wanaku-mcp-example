# External Task Handler Reference

This document describes every Camunda external task handler across the two workers that make up the CIB Seven AI pipeline: **cib-seven-wanaku-worker** and **cib-seven-llm-worker**.

---

## Table of Contents

1. [System Architecture Overview](#1-system-architecture-overview)
2. [Handler: `WanakuToolsFetchHandler`](#2-handler-wanakutoolsfetchhandler--cib-seven-wanaku-worker)
3. [Handler: `LlmExternalTaskHandler`](#3-handler-llmexternaltaskhandler--cib-seven-llm-worker)
4. [Handler: `WanakuExternalTaskHandler`](#4-handler-wanakuexternaltaskhandler--cib-seven-wanaku-worker)
5. [BPMN Wiring Guide](#5-bpmn-wiring-guide)
6. [Retry Behaviour Reference](#6-retry-behaviour-reference)
7. [Variable Flow Across Handlers](#7-variable-flow-across-handlers)
8. [Configuration Quick-Reference](#8-configuration-quick-reference)

---

## 1. System Architecture Overview

The two workers together implement a **ReAct-style agentic loop** inside a Camunda BPMN process:

```
┌───────────────────────────────────────────────────────────────────────┐
│  BPMN Process (CIB Seven / Camunda 7)                                 │
│                                                                       │
│  [Fetch Tools]──────► [LLM Decision]──────► gateway                  │
│  wanaku-tools-fetch    llm-decision        requiresTool?              │
│                                               │ yes          │ no     │
│                                         [Execute Tool]   [End/Answer] │
│                                         wanaku-tool-execute           │
│                                               │                       │
│                                     loop back to [LLM Decision]       │
└───────────────────────────────────────────────────────────────────────┘
```

| Step | Service Task Topic | Handler | Worker |
|---|---|---|---|
| 1 — Populate tool catalogue | `wanaku-tools-fetch` | `WanakuToolsFetchHandler` | cib-seven-wanaku-worker |
| 2 — Decide next action | `llm-decision` | `LlmExternalTaskHandler` | cib-seven-llm-worker |
| 3 — Execute chosen tool | `wanaku-tool-execute` | `WanakuExternalTaskHandler` | cib-seven-wanaku-worker |

Steps 2 and 3 repeat in a loop until the LLM decides no more tools are needed (`requiresTool = false`).

---

## 2. Handler: `WanakuToolsFetchHandler` — cib-seven-wanaku-worker

### Summary

| Attribute | Value |
|---|---|
| **Class** | `org.cibseven.worker.handler.WanakuToolsFetchHandler` |
| **Worker project** | `cib-seven-wanaku-worker` |
| **Camunda topic** | `wanaku-tools-fetch` (configurable: `wanaku.tools-fetch-topic`) |
| **Enabled flag** | `wanaku.enabled` **and** `wanaku.tools-fetch-enabled` (both must be `true`) |

### Primary Purpose

Snapshots the in-memory Wanaku tool catalogue into the Camunda process scope as the `availableTools` variable. This is a pure in-memory read — no outbound network call is made at task execution time; the catalogue is maintained by `WanakuToolRegistryService`, which refreshes via the MCP SDK's `listTools()` method every five minutes in the background.

### Input Variables

| Variable | Java type | Required | Description |
|---|---|---|---|
| *(none)* | — | — | No input variables are consumed |

### Output Variables

| Variable | Java type | Description |
|---|---|---|
| `availableTools` | `List<Map<String, Object>>` | Serialised list of `ToolMetadata` objects. Each map contains `name` (String), `description` (String), and optionally `inputSchema` (Map — JSON Schema). |

**`availableTools` element shape:**
```json
{
  "name": "searchDatabase",
  "description": "Searches the production database using SQL queries",
  "inputSchema": {
    "type": "object",
    "properties": {
      "query": { "type": "string" },
      "database": { "type": "string" }
    },
    "required": ["query", "database"]
  }
}
```

### Role in the Overall System

This handler is the **bridge between the Wanaku tool registry and the BPMN process scope**. Without it, `LlmExternalTaskHandler` would receive an empty `availableTools` list and the LLM would have no knowledge of which tools exist. The handler must be placed in the BPMN flow _before_ any `llm-decision` service task that is expected to choose a tool. Typically it appears once per process instance, near the start.

The tool data it writes is directly deserialised by `LlmExternalTaskHandler.convertToToolMetadata()` using Jackson's `objectMapper.convertValue(rawTool, ToolMetadata.class)`.

### Retry Behaviour

`WanakuToolsFetchHandler` uses **no retries** (hardcoded `retries=0, retryInterval=0`). Because execution is a pure in-memory read from `WanakuToolRegistryService.getAvailableTools()`, the only plausible failure mode is a Jackson serialisation bug, which is not transient and would not benefit from retrying.

| Setting | Value |
|---|---|
| Retries | 0 (hardcoded, not configurable) |
| Retry interval | 0 ms |
| On failure | Task fails immediately → Camunda incident |

### When to Use

- Place a service task with topic `wanaku-tools-fetch` at the **beginning of any agentic sub-process** that requires the LLM to select tools.
- May also be placed before each LLM decision cycle if the set of available tools is expected to change during a long-running process instance.
- Can be disabled (`wanaku.tools-fetch-enabled=false`) in deployments where `availableTools` is populated via a different mechanism (e.g., set as an initial process variable by the start form).

---

## 3. Handler: `LlmExternalTaskHandler` — cib-seven-llm-worker

### Summary

| Attribute | Value |
|---|---|
| **Class** | `org.cibseven.worker.handler.LlmExternalTaskHandler` |
| **Worker project** | `cib-seven-llm-worker` |
| **Camunda topic** | `llm-decision` (configurable: `llm.topic`) |
| **LLM providers** | `ollama` (default), `openai` |
| **Enabled flag** | `llm.enabled` |

### Primary Purpose

Calls a Large Language Model (Ollama or OpenAI) with the user's request, conversation history, and the list of available tools. Returns a structured `AgentDecision` that tells the BPMN process either to call a specific tool next or to deliver a final answer to the user.

### Input Variables

| Variable | Java type | Required | Description |
|---|---|---|---|
| `userRequest` | `String` | **Required** | The user's current question or instruction. An empty/null value causes immediate task failure. |
| `conversationHistory` | `List<Map<String, Object>>` | Optional | Prior turns in the conversation. Each map must contain `"role"` (`"user"` or `"assistant"`) and `"content"` (String). Pass `null` or omit for the first turn. |
| `availableTools` | `List<Map<String, Object>>` | Optional | Tool catalogue produced by `WanakuToolsFetchHandler`. Each map must be a serialised `ToolMetadata` with `"name"`, `"description"`, and optionally `"inputSchema"`. Pass `null` or omit to restrict the LLM to direct answers only. |

**`conversationHistory` element shape:**
```json
{ "role": "user",      "content": "What is the weather in Berlin?" }
{ "role": "assistant", "content": "{\"requiresTool\": true, ...}" }
```

### Output Variables

| Variable | Java type | When set | Description |
|---|---|---|---|
| `requiresTool` | `Boolean` | Always | `true` if the LLM chose a tool; `false` if it produced a final answer. Drive the BPMN exclusive gateway on this variable. |
| `toolName` | `String` | `requiresTool = true` | Exact name of the tool from `availableTools` that should be called next. `null` when no tool is needed. |
| `toolArgs` | `Map<String, Object>` | `requiresTool = true` | Key/value arguments for the tool, matching the tool's `inputSchema`. `null` when no tool is needed. |
| `finalAnswer` | `String` | `requiresTool = false` | The LLM's complete response text for the user. `null` when a tool is needed. |

**`AgentDecision` JSON contract (LLM must produce one of these two shapes):**
```json
// Tool call
{ "requiresTool": true,  "toolName": "searchDatabase", "toolArgs": {"query": "..."}, "finalAnswer": null }

// Direct answer
{ "requiresTool": false, "toolName": null, "toolArgs": null, "finalAnswer": "The answer is 42." }
```

The handler extracts the first `{…}` block from the raw LLM text before parsing, so models that prepend or append prose do not break JSON deserialisation.

### Role in the Overall System

`LlmExternalTaskHandler` is the **decision-making brain** of the agentic loop. It sits between `WanakuToolsFetchHandler` (which populates the tool catalogue) and `WanakuExternalTaskHandler` (which executes the chosen tool). It does not call Wanaku directly — it only reasons about _which_ tool to call and _what arguments_ to pass. This strict separation means the decision engine can be swapped (different model, different provider) without any change to the execution layer.

Internal call path:
```
handleTask()
  → buildChatMessages()               builds SystemMessage + history + UserMessage
  → producerTemplate → direct:callLlm     Apache Camel → LangChain4j → LLM provider
  ← raw LLM text
  → extractJson() / parseLlmResponse()    strips non-JSON prose, deserialises AgentDecision
  → externalTaskService.complete()        writes variables back to Camunda
```

The system prompt injected by `buildSystemPrompt()` lists every tool from `availableTools` (name, description, and JSON Schema) so the LLM has full knowledge of the tool contracts.

### Retry Behaviour

| Setting | Default | Env override | Notes |
|---|---|---|---|
| `llm.retries` | `3` | `LLM_RETRIES` | Seed on first failure; decremented each re-delivery |
| `llm.retry-interval-ms` | `10 000` ms | `LLM_RETRY_INTERVAL_MS` | 10 s default — longer than Wanaku to accommodate LLM cold-start and provider rate limits |

Counter mechanics (identical across all configurable handlers):
```
Attempt 1: getRetries() == null  → remaining = retries - 1  (e.g. 3-1 = 2)
Attempt 2: getRetries() == 2    → remaining = 2 - 1 = 1
Attempt 3: getRetries() == 1    → remaining = 1 - 1 = 0  → Camunda incident
```
Set `llm.retries=0` to skip retries entirely and create an incident on the first failure.

### When to Use

- In **every agentic loop** where a BPMN process needs an LLM to reason about a user request.
- In **multi-turn conversations**: carry prior turns via `conversationHistory` so the model has context across loop iterations and tool results.
- In **tool-free answer** flows: omit `availableTools` (or set `wanaku.tools-fetch-enabled=false`) to restrict the LLM to direct answers, turning the worker into a simple Q&A service.
- In **classifier / router** patterns: use the `requiresTool` / `toolName` outputs to drive exclusive gateways that route to completely different sub-processes based on user intent.

---

## 4. Handler: `WanakuExternalTaskHandler` — cib-seven-wanaku-worker

### Summary

| Attribute | Value |
|---|---|
| **Class** | `org.cibseven.worker.handler.WanakuExternalTaskHandler` |
| **Worker project** | `cib-seven-wanaku-worker` |
| **Camunda topic** | `wanaku-tool-execute` (configurable: `wanaku.topic`) |
| **Enabled flag** | `wanaku.enabled` |

### Primary Purpose

Executes a single MCP tool call against the Wanaku MCP Router. Takes a tool name and arguments from Camunda process variables, calls the tool using the MCP Java SDK's `McpSyncClient.callTool()` over Streamable HTTP transport, and writes the result (or error message) back to the process.

### Input Variables

| Variable | Java type | Required | Description |
|---|---|---|---|
| `toolName` | `String` | **Required** | Name of the MCP tool to invoke. Must match a tool registered in Wanaku. An empty/null value causes immediate task failure. |
| `toolArgs` | `Map<String, Object>` | Optional | Key/value arguments passed as-is to the tool. If `null` the handler substitutes an empty map — tools with no required parameters accept this. |

### Output Variables

| Variable | Java type | When set | Description |
|---|---|---|---|
| `toolResult` | `String` | Success path | Concatenated text from all `TextContent` items in the MCP `CallToolResult`. Empty string if the tool returns no text content. |
| `toolError` | `String` | Tool-level error | Error text extracted from the `CallToolResult` when `isError` is `true`. `null` on success. The task still _completes_ (no Camunda incident), so the BPMN process can branch on `toolError != null`. |

### Role in the Overall System

`WanakuExternalTaskHandler` is the **execution arm** of the agentic loop. It is always reached because `LlmExternalTaskHandler` decided a tool is needed (`requiresTool = true`) and already set `toolName` / `toolArgs`. It never makes its own decisions — it only translates Camunda variables into MCP `callTool()` invocations and translates the response back into Camunda variables.

The handler uses `WanakuToolRegistryService.toolExists()` for a soft pre-flight check. If the tool is not in the cached registry it logs a warning but proceeds anyway, so execution is never blocked by a stale or temporarily unavailable registry.

Internal call path:
```
handleTask()
  → validate toolName (fail fast if null/blank)
  → toolRegistryService.toolExists()              warn-only pre-flight
  → mcpClient.callTool(CallToolRequest)            MCP SDK → Streamable HTTP transport → Wanaku
  ← CallToolResult
  → extractResultText()     concatenates TextContent items
  → externalTaskService.complete(toolResult, toolError)
```

### Retry Behaviour

| Setting | Default | Env override | Notes |
|---|---|---|---|
| `wanaku.retries` | `3` | `WANAKU_RETRIES` | Covers transient network errors reaching Wanaku |
| `wanaku.retry-interval-ms` | `5 000` ms | `WANAKU_RETRY_INTERVAL_MS` | 5 s — shorter than LLM since MCP reconnect is fast |

**Important distinction:** a **tool-level error** (`CallToolResult.isError() = true`) does _not_ trigger a retry — the task completes with `toolError` set. Only transport-level exceptions (Streamable HTTP connection failure, timeout, unexpected response) use the retry counter.

### When to Use

- Always in conjunction with the `llm-decision` service task. The standard wiring routes the `requiresTool = true` branch of the exclusive gateway to this service task.
- When the BPMN process needs to call a Wanaku tool **directly** (bypassing the LLM), set `toolName` and `toolArgs` as process variables from the start event or a preceding task and route straight to this service task.
- For **parallel tool execution**: multiple service tasks with topic `wanaku-tool-execute` in a parallel gateway use distinct input-variable mappings — the Camunda external task lock mechanism prevents race conditions.

---

## 5. BPMN Wiring Guide

### Canonical Agentic Loop Pattern

```
Start Event
  │  (sets: userRequest)
  ▼
Service Task: "Fetch Available Tools"
  topic = wanaku-tools-fetch
  out:  availableTools
  │
  ▼
Service Task: "LLM Decision"   ◄──────────────────────────────────────┐
  topic = llm-decision                                                 │
  in:   userRequest, conversationHistory, availableTools               │
  out:  requiresTool, toolName, toolArgs, finalAnswer                  │
  │                                                                    │
  ▼                                                                    │
Exclusive Gateway: requiresTool?                                       │
  ├── true  ──►  Service Task: "Execute Tool"                          │
  │              topic = wanaku-tool-execute                           │
  │              in:   toolName, toolArgs                              │
  │              out:  toolResult, toolError                           │
  │              │                                                     │
  │        Script Task: append toolResult to conversationHistory ──────┘
  │
  └── false ──►  End Event (deliver finalAnswer to user)
```

### Minimum Variable Set per Service Task

| Service Task | Required input variables | Output variables to map |
|---|---|---|
| `wanaku-tools-fetch` | *(none)* | `availableTools` |
| `llm-decision` | `userRequest` | `requiresTool`, `toolName`, `toolArgs`, `finalAnswer` |
| `wanaku-tool-execute` | `toolName` | `toolResult`, `toolError` |

---

## 6. Retry Behaviour Reference

| Handler | Default retries | Default interval | Configurable | Failure that triggers retry |
|---|---|---|---|---|
| `WanakuToolsFetchHandler` | 0 | 0 ms | No | Any exception (rare — in-memory read only) |
| `LlmExternalTaskHandler` | 3 | 10 000 ms | Yes (`llm.*`) | Any uncaught exception (network, parse error, LLM 5xx) |
| `WanakuExternalTaskHandler` | 3 | 5 000 ms | Yes (`wanaku.*`) | Transport exception only (not tool-level `isError` results) |

**Seeded-counter algorithm** (used by `LlmExternalTaskHandler` and `WanakuExternalTaskHandler`):

```java
int remaining = externalTask.getRetries() != null
    ? externalTask.getRetries() - 1        // subsequent attempts
    : properties.getRetries() - 1;         // seed on first failure
remaining = Math.max(0, remaining);        // guard against retries=0 config
long interval = remaining > 0 ? properties.getRetryIntervalMs() : 0L;
externalTaskService.handleFailure(task, message, stackTrace, remaining, interval);
```

---

## 7. Variable Flow Across Handlers

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  Process Variable Scope                                                      │
│                                                                              │
│  wanaku-tools-fetch writes ──► availableTools ──► llm-decision reads        │
│                                                                              │
│  llm-decision writes ──► requiresTool ──► gateway condition                 │
│                     ──► toolName      ──► wanaku-tool-execute reads          │
│                     ──► toolArgs      ──► wanaku-tool-execute reads          │
│                     ──► finalAnswer   ──► End Event / user response          │
│                                                                              │
│  wanaku-tool-execute writes ──► toolResult ──► append to conversationHistory │
│                             ──► toolError  ──► optional error branch         │
└──────────────────────────────────────────────────────────────────────────────┘
```

**`toolResult` → `conversationHistory` feedback loop** (process implementer's responsibility):

After `wanaku-tool-execute` completes, a script task or execution listener should append the tool result to `conversationHistory` before the token returns to `llm-decision`. Recommended format:

```json
[
  { "role": "user",      "content": "What is the weather in Berlin?" },
  { "role": "assistant", "content": "I need to call getWeather with city=Berlin" },
  { "role": "user",      "content": "Tool result: {\"temperature\": 12, \"condition\": \"cloudy\"}" }
]
```

---

## 8. Configuration Quick-Reference

### cib-seven-llm-worker (`application.yaml` — `llm.*`)

| Key | Default | Env var | Description |
|---|---|---|---|
| `llm.enabled` | `true` | `LLM_ENABLED` | Disable the entire worker |
| `llm.provider` | `ollama` | `LLM_PROVIDER` | `ollama` or `openai` |
| `llm.model-name` | `llama3.2` | `LLM_MODEL_NAME` | Model identifier |
| `llm.base-url` | `http://localhost:11434` | `LLM_BASE_URL` | LLM service endpoint |
| `llm.api-key` | *(empty)* | `LLM_API_KEY` | Required for OpenAI |
| `llm.temperature` | `0.0` | `LLM_TEMPERATURE` | 0.0 = deterministic |
| `llm.timeout-seconds` | `120` | `LLM_TIMEOUT_SECONDS` | HTTP timeout to LLM |
| `llm.topic` | `llm-decision` | `LLM_TOPIC` | Camunda external task topic |
| `llm.retries` | `3` | `LLM_RETRIES` | Retry attempts (0 = no retries) |
| `llm.retry-interval-ms` | `10000` | `LLM_RETRY_INTERVAL_MS` | Wait between retries (ms) |

### cib-seven-wanaku-worker (`application.yaml` — `wanaku.*`)

| Key | Default | Env var | Description |
|---|---|---|---|
| `wanaku.enabled` | `true` | `WANAKU_ENABLED` | Disable both handlers and the MCP client |
| `wanaku.base-url` | `http://localhost:8080` | `WANAKU_BASE_URL` | Wanaku MCP Router base URL |
| `wanaku.mcp-endpoint` | `/mcp/` | `WANAKU_MCP_ENDPOINT` | Streamable HTTP endpoint path (appended to `base-url`) |
| `wanaku.timeout-seconds` | `30` | `WANAKU_TIMEOUT_SECONDS` | MCP request timeout |
| `wanaku.topic` | `wanaku-tool-execute` | `WANAKU_TOPIC` | Topic for `WanakuExternalTaskHandler` |
| `wanaku.tools-fetch-topic` | `wanaku-tools-fetch` | `WANAKU_TOOLS_FETCH_TOPIC` | Topic for `WanakuToolsFetchHandler` |
| `wanaku.tools-fetch-enabled` | `true` | `WANAKU_TOOLS_FETCH_ENABLED` | Disable only the fetch handler |
| `wanaku.retries` | `3` | `WANAKU_RETRIES` | Retry attempts for `WanakuExternalTaskHandler` |
| `wanaku.retry-interval-ms` | `5000` | `WANAKU_RETRY_INTERVAL_MS` | Wait between retries (ms) |
