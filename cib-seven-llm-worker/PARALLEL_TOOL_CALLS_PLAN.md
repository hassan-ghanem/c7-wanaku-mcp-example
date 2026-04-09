# Implementation Plan: Parallel Multi-Instance Tool Calls

> **Status:** IMPLEMENTED — all open questions resolved, all Java files updated.

---

## Decisions (locked)

| Question | Decision |
|---|---|
| Dynamic vs. fixed parallel gateway | **Multi-Instance Service Task** (top-level, dynamic N, parallel) |
| `callId` sanitisation | **Java handler** sanitises before writing to Camunda |
| Merge script language | **Nashorn JavaScript** |
| Error policy for branch failure | **Camunda incident** (tool-level MCP error = `handleFailure()`) |
| `conversationHistory` ownership | **BPMN** (Script Task after Multi-Instance Service Task) |

---

## 1. What Was Replaced (removed entirely)

| Removed | Reason |
|---|---|
| `AgentDecision.toolName` (scalar field) | Moved into `ToolCall.toolName` |
| `AgentDecision.toolArgs` (scalar field) | Moved into `ToolCall.toolArgs` |
| Camunda process var `toolName` | Supplied per-instance by BPMN element variable mapping |
| Camunda process var `toolArgs` | Supplied per-instance by BPMN element variable mapping |
| Camunda process var `toolResult` | Replaced by `toolResult_{callId}` |
| Camunda process var `toolError` | Replaced — errors now raise Camunda incidents |
| Old single-tool system prompt | Replaced by parallel-aware prompt |

---

## 2. New Data Model

### `AgentDecision` (rewritten)

```
AgentDecision
├── boolean requiresTool
├── List<ToolCall> toolCalls   ← null when requiresTool=false
└── String finalAnswer         ← null when requiresTool=true

AgentDecision.ToolCall
├── String callId              ← sanitised by Java handler
├── String toolName
└── Map<String,Object> toolArgs
```

### JSON contract — tool call(s) needed

```json
{
  "requiresTool": true,
  "toolCalls": [
    { "callId": "c1", "toolName": "searchDatabase", "toolArgs": { "query": "SELECT ..." } },
    { "callId": "c2", "toolName": "getWeather",     "toolArgs": { "city": "Berlin" } }
  ],
  "finalAnswer": null
}
```

### JSON contract — direct answer

```json
{
  "requiresTool": false,
  "toolCalls": null,
  "finalAnswer": "The answer is 42."
}
```

---

## 3. Files Changed

### 3.1 `model/AgentDecision.java` — cib-seven-llm-worker (REWRITTEN)

- Removed scalar `toolName`, `toolArgs` fields
- Added `List<ToolCall> toolCalls`
- Added nested static class `ToolCall` with `callId`, `toolName`, `toolArgs`

### 3.2 `handler/LlmExternalTaskHandler.java` — cib-seven-llm-worker (MODIFIED)

**`buildSystemPrompt()`** — new parallel-tool-call prompt:
- Two clear JSON shape templates
- Explicit parallel rules (independent = batch; dependent = sequence)
- `callId` naming instruction (`letters/digits/underscores only`)

**`handleTask()` output block** — now emits three variables only:
```java
variables.put("requiresTool", decision.isRequiresTool());
variables.put("finalAnswer",  decision.getFinalAnswer());
variables.put("toolCalls",    objectMapper.convertValue(decision.getToolCalls(), List.class));
```

**New `sanitiseCallId()` method:**
1. Replace `[^a-zA-Z0-9_]` → `_`
2. Strip leading/trailing underscores
3. If empty after sanitisation → `"c" + System.nanoTime()` fallback

### 3.3 `handler/WanakuExternalTaskHandler.java` — cib-seven-wanaku-worker (MODIFIED)

**`handleTask()` reads three per-instance variables** (injected by BPMN element variable mapping):
```
callId   → String (required — validated, fails task if blank)
toolName → String (required)
toolArgs → Map    (optional, defaults to empty map)
```

**Tool-level MCP error → Camunda incident:**
```java
if (result.isError() != null && result.isError()) {
    handleFailure(externalTaskService, externalTask,
        new RuntimeException("Tool error from '" + toolName + "': " + errorMessage));
    return;
}
```

**Success → indexed result variable only:**
```java
variables.put("toolResult_" + callId, resultText);
```

---

## 4. BPMN Wiring

### Complete process structure

> **Note:** Parallel execution is implemented as a **top-level Multi-Instance Service Task**
> (`CallMCPToolTask`) directly on the main process — no subprocess wrapper is used.
> Camunda's multi-instance join behaviour ensures `MergeToolResultsTask` only runs
> after **all** parallel instances have completed.

```
Start Event
  │  sets: userRequest, conversationHistory=null, max=5, iteration=1
  ▼
Service Task: "Fetch MCP Tools"  (wanaku-tools-fetch)
  out: availableTools, iteration=1
  │
  ▼
Exclusive Gateway  ◄─────────────────────────────────────────────────────────┐
  │                                                                           │
  ▼                                                                           │
Service Task: "Call LLM"  (llm-decision)                                     │
  in:  userRequest, conversationHistory, availableTools                       │
  out: requiresTool, toolCalls, finalAnswer                                   │
  │                                                                           │
  ▼                                                                           │
Exclusive Gateway: LLM Response?                                              │
  ├── finalAnswer != null or iteration > max ──► User Task "Display Result"  │
  │                                              ──► End Event               │
  │                                                                           │
  └── iteration <= max and requiresTool ───►                                  │
                                                                              │
      Service Task: "Call MCP Tool"  (wanaku-tool-execute)                   │
        Multi-Instance (PARALLEL):                                            │
          collection      = toolCalls                                         │
          elementVariable = currentToolCall                                   │
          asyncAfter      = true                                              │
        Input mapping (per instance):                                         │
          callId   ← currentToolCall.callId                                   │
          toolName ← currentToolCall.toolName                                 │
          toolArgs ← currentToolCall.toolArgs                                 │
        Output (per instance, written to process scope):                      │
          toolResult_{callId}                                                 │
      │                                                                       │
      ▼                                                                       │
      Script Task: "Merge Tool Results"  (asyncBefore=true, Nashorn JS)      │
        reads:  toolCalls, toolResult_{callId}…                               │
        writes: conversationHistory (appended), iteration++                   │
        │                                                                     │
        └─────────────────────────────────────────────────────────────────────┘
```

---

## 5. Merge Script Task (Nashorn JavaScript)

Placed on the Script Task immediately after the Multi-Instance Service Task (`CallMCPToolTask`).
- **Script format:** JavaScript (Nashorn)
- **Script engine:** (leave blank — Camunda 7 defaults to Nashorn)
- **`camunda:asyncBefore=true`** on the Script Task ensures it executes in a fresh transaction
  after all parallel `wanaku-tool-execute` instances have joined.

```javascript
var ArrayList = java.util.ArrayList;
var HashMap   = java.util.HashMap;
var JSON      = org.cibseven.spin.Spin.JSON;

var history = execution.getVariable("conversationHistory");
var historyList = (history == null) ? new ArrayList() : new ArrayList(history);

var toolCalls = execution.getVariable("toolCalls");

// 1. Record the LLM's prior decision as an assistant message
//    so future iterations have full context of what was decided.
if (toolCalls != null && toolCalls.size() > 0) {
    var assistantMsg = new HashMap();
    assistantMsg.put("role", "assistant");
    assistantMsg.put("content", JSON(toolCalls).toString());
    historyList.add(assistantMsg);
}

// 2. Append one user message per parallel tool call result.
//    The variable name is toolResult_{callId}, written by WanakuExternalTaskHandler.
if (toolCalls != null) {
    for (var i = 0; i < toolCalls.size(); i++) {
        var call     = toolCalls.get(i);
        var callId   = call.get("callId");
        var toolName = call.get("toolName");

        var result = execution.getVariable("toolResult_" + callId);
        var rawResult = (result != null) ? result : "(no output)";

        var userMsg = new HashMap();
        userMsg.put("role", "user");
        userMsg.put("content", "[Tool " + callId + " " + toolName + " result]: " + rawResult);
        historyList.add(userMsg);
    }
}

// 3. Persist the updated history
execution.setVariable("conversationHistory", historyList);

// 4. Increment the iteration counter
var currentIteration = execution.getVariable("iteration");
execution.setVariable("iteration", currentIteration + 1);
```

> **Note on history format:** The assistant turn records the full `toolCalls` JSON array
> (so the LLM knows what it decided); each tool result is a separate user message tagged
> `[Tool {callId} {toolName} result]: ...`. No `toolError` variable is written — branch
> failures surface as Camunda incidents before the multi-instance join completes.

---

## 6. Updated Variable Flow

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Process Variable Scope                                                   │
│                                                                           │
│  wanaku-tools-fetch ──► availableTools ──► llm-decision reads            │
│                                                                           │
│  llm-decision ──► requiresTool   ──► exclusive gateway condition         │
│               ──► toolCalls      ──► multi-instance collection           │
│               ──► finalAnswer    ──► End Event                           │
│                                                                           │
│  wanaku-tool-execute (per instance):                                      │
│               ──► toolResult_{callId}  (e.g. toolResult_c1)             │
│                                                                           │
│  merge-results script:                                                    │
│               ──► conversationHistory  (appended with all results)       │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 7. Dependency Detection — LLM Responsibility

Encoded in the system prompt. The LLM decides:

**Independent → one `toolCalls` list, parallel execution:**
```
User: "Weather in Berlin and price of product #5?"

LLM:
{ "requiresTool": true, "toolCalls": [
    { "callId": "c1", "toolName": "getWeather",    "toolArgs": { "city": "Berlin" } },
    { "callId": "c2", "toolName": "getProductPrice","toolArgs": { "productId": 5 } }
]}
→ Both run concurrently. One LLM iteration.
```

**Dependent → separate iterations:**
```
User: "Order history for user@example.com?"

Iteration 1:
{ "requiresTool": true, "toolCalls": [
    { "callId": "c1", "toolName": "getCustomerId", "toolArgs": { "email": "user@example.com" } }
]}
→ Result appended to conversationHistory as:
  "[Tool c1 getCustomerId result]: 42"

Iteration 2 (LLM now knows customerId=42):
{ "requiresTool": true, "toolCalls": [
    { "callId": "c1", "toolName": "getOrderHistory", "toolArgs": { "customerId": 42 } }
]}
```

---

## 8. Files NOT Changed

- `WanakuToolsFetchHandler.java` — tool catalogue fetch unchanged
- `LlmRoute.java` — Camel route is agnostic to decision format
- `LlmProperties.java` — no new config
- `WanakuProperties.java` — no new config
- `LlmChatModelConfig.java` — unchanged
- `WanakuToolRegistryService.java` — unchanged
