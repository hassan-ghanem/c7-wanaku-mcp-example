# Camunda 7 + Wanaku MCP + Apache Camel + LLM
### Agentic Tool-Calling with BPMN Orchestration (Parallel Edition)

This repository demonstrates how to orchestrate an **LLM tool-calling agent** using **Camunda Platform 7 (CIB Seven)**, **Wanaku MCP Router**, and **Apache Camel**.

The implementation now supports **parallel multi-tool execution** using BPMN **Multi-Instance Service Tasks**, enabling the LLM to request multiple tools in a single iteration.

---

## Key Enhancement: Parallel Tool Calls

Previously, the agent executed **one tool per iteration**.

Now:

- The LLM can return **multiple tool calls at once**
- BPMN executes them **in parallel**
- Results are merged and fed back into the LLM
- Reduces number of iterations and improves performance

---

## Updated Architecture Overview

```
┌───────────────────────────────────────────────────────────────┐
│                       Camunda BPMN Process                    │
│                                                               │
│  [Fetch Tools] ──► [LLM Decision] ──► gateway                │
│                         │                                     │
│                         ▼                                     │
│              Multi-Instance Tool Execution (Parallel)         │
│                         │                                     │
│                  [Merge Results]                              │
│                         │                                     │
│                  Loop back to LLM                             │
│                         │                                     │
│                   Final Answer                                │
└───────────────────────────────────────────────────────────────┘
```

---

## Updated Data Model

### AgentDecision

```
AgentDecision
├── boolean requiresTool
├── List<ToolCall> toolCalls
└── String finalAnswer
```

### ToolCall

```
ToolCall
├── String callId
├── String toolName
└── Map<String,Object> toolArgs
```

---

## JSON Contract

### Multiple tool calls

```json
{
  "requiresTool": true,
  "toolCalls": [
    { "callId": "c1", "toolName": "searchDatabase", "toolArgs": { "query": "SELECT ..." } },
    { "callId": "c2", "toolName": "getWeather", "toolArgs": { "city": "Berlin" } }
  ],
  "finalAnswer": null
}
```

### Final answer

```json
{
  "requiresTool": false,
  "toolCalls": null,
  "finalAnswer": "The answer is 42."
}
```

---

## BPMN Execution Flow (Updated)

1. Fetch available tools from Wanaku
2. LLM evaluates request
3. If tools required:
   - BPMN executes **parallel multi-instance tool calls**
4. Each tool writes:
   - `toolResult_{callId}`
5. Script task merges results into `conversationHistory`
6. Loop continues until final answer

---

## Multi-Instance Tool Execution

- Implemented as a **top-level Service Task**
- Configuration:
  - `collection = toolCalls`
  - `elementVariable = currentToolCall`
  - `parallel = true`

### Per-instance variables

| Variable | Source |
|----------|--------|
| callId   | currentToolCall.callId |
| toolName | currentToolCall.toolName |
| toolArgs | currentToolCall.toolArgs |

### Output

```
toolResult_{callId}
```

---

## Merge Strategy

A **Nashorn JavaScript Script Task**:

- Appends LLM decision to history
- Appends each tool result as a user message
- Increments iteration counter

This ensures the LLM receives **full context** in subsequent iterations.

---

## Error Handling

- Tool errors → **Camunda incidents**
- No `toolError` variable is stored
- Failed branches stop execution before merge

---

## Updated Variable Flow

| Variable | Description |
|----------|------------|
| availableTools | Tools from Wanaku |
| toolCalls | List of tool calls from LLM |
| toolResult_{callId} | Result per tool |
| conversationHistory | Aggregated history |
| finalAnswer | Final LLM output |

---

## LLM Responsibilities (Updated)

The LLM decides:

### Independent tasks → Parallel

```
Weather + Product Price → same iteration
```

### Dependent tasks → Sequential

```
GetCustomerId → then GetOrders
```

---

## External Task Workers (Updated)

### LLM Worker

Outputs:

- `requiresTool`
- `toolCalls`
- `finalAnswer`

Includes:

- Parallel-aware system prompt
- callId sanitisation

---

### Wanaku Worker

Per-instance execution:

Reads:

- callId
- toolName
- toolArgs

Writes:

- `toolResult_{callId}`

---

## What Was Removed

- Single tool execution model
- `toolName` (process variable)
- `toolArgs` (process variable)
- `toolResult` (single value)
- `toolError` variable

---

## Benefits of the New Design

- Faster execution (parallel tools)
- Fewer LLM iterations
- Cleaner variable model
- Better scalability
- Native BPMN parallelism

---

## Running the Example

1. Start Camunda Platform 7
2. Start Wanaku MCP Router
3. Start workers:

```
cib-seven-llm-worker
cib-seven-wanaku-worker
```

4. Deploy BPMN model
5. Start process instance

---

## Repository Structure

```
bpmn/
  agent-loop.bpmn

workers/
  cib-seven-llm-worker
  cib-seven-wanaku-worker

wanaku/
  currency.json
  demo.rules.yaml
```

---

## Summary

This update transforms the agent from a **sequential tool executor** into a **parallel, multi-tool orchestration system**, fully leveraging BPMN capabilities while keeping the LLM focused on decision-making.

