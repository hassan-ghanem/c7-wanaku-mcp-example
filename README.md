# Camunda 7 + Wanaku MCP + Apache Camel + LLM
### Agentic Tool-Calling with BPMN Orchestration

This repository demonstrates how to orchestrate an **LLM tool-calling agent** using **Camunda Platform 7 (CIB Seven)**, **Wanaku MCP Router**, and **Apache Camel**.

The example implements an **iterative agent loop inside BPMN** where an LLM decides when to call tools and Camunda orchestrates the execution flow.

BPMN provides **full process traceability, retries, incidents, and monitoring**, while the LLM focuses only on reasoning and decision making.

---

## Architecture Overview

The system combines BPMN orchestration with an LLM decision loop.

```
┌───────────────────────────────────────────────────────────────┐
│                       Camunda BPMN Process                    │
│                                                               │
│  [Fetch Tools] ──► [LLM Decision] ──► gateway                │
│   wanaku-tools-fetch     llm-decision     requiresTool?       │
│                                              │ yes   │ no     │
│                                       [Execute Tool]  │       │
│                                       wanaku-tool     │       │
│                                              │        │       │
│                                   loop back to LLM    │       │
│                                              │        │       │
│                                          Final Answer ◄       │
└───────────────────────────────────────────────────────────────┘
```

---

## Architecture Components

### BPMN (Camunda Platform 7)

BPMN orchestrates the agent loop and provides:

- Process orchestration
- Observability
- Retry handling
- Incident management
- Human intervention when required

BPMN also acts as a **common language between business and technical teams**, allowing both groups to understand and discuss the workflow.

---

### LLM Worker

Responsible for reasoning and deciding the next action.

Output example:

```json
{
  "requiresTool": true,
  "toolName": "currency-rate",
  "toolArgs": {
    "walletId": 152,
    "targetCurrency": "USD"
  }
}
```

The BPMN gateway checks `requiresTool` to decide whether to execute a tool or finish the process.

---

### Wanaku MCP Router

Wanaku acts as the **bridge between the LLM and the tool ecosystem**.

It exposes tools through the **Model Context Protocol (MCP)** and allows the agent to discover and execute them dynamically.

---

### Apache Camel

Apache Camel handles **enterprise routing and system integration**.

Camel routes can:

- Call APIs
- Access databases
- Integrate with enterprise systems
- Implement business logic

Wanaku exposes these Camel routes as **MCP tools** that the LLM can call.

---

## External Task Workers

Two workers are used in the example.

---

### Wanaku Worker

Handles tool discovery and execution.

| Topic                 | Handler                     | Purpose                       |
| --------------------- | --------------------------- | ----------------------------- |
| `wanaku-tools-fetch`  | `WanakuToolsFetchHandler`   | Retrieves available MCP tools |
| `wanaku-tool-execute` | `WanakuExternalTaskHandler` | Executes selected MCP tool    |

---

### LLM Worker

Handles the reasoning step.

| Topic          | Handler                  |
| -------------- | ------------------------ |
| `llm-decision` | `LlmExternalTaskHandler` |

Responsibilities:

- Reads the user request
- Reads available tools
- Decides whether a tool is required
- Returns the decision variables to BPMN

---

## Variable Flow

Main variables used during execution.

| Variable         | Description                              |
| ---------------- | ---------------------------------------- |
| `userRequest`    | Original user prompt                     |
| `availableTools` | List of tools exposed by Wanaku          |
| `requiresTool`   | Boolean indicating if a tool is required |
| `toolName`       | Name of tool to execute                  |
| `toolArgs`       | Tool parameters                          |
| `toolResult`     | Result returned from tool                |
| `finalAnswer`    | Final response from the LLM              |

---

## Execution Loop

1. BPMN fetches available tools from Wanaku.
2. LLM evaluates the request.
3. If a tool is required, BPMN triggers the Wanaku worker.
4. Wanaku executes the selected MCP tool.
5. Tool result is returned to the process.
6. BPMN loops back to the LLM.
7. Loop continues until `requiresTool = false`.

---

## Example Tools

### HTTP Tool

Defined in `currency.json`. Used for retrieving currency information (e.g., `currency-rate`).

### Apache Camel Tools

Defined in `demo.rules.yaml`. These routes expose enterprise integrations as MCP tools.

---

## Running the Example

1. Start Camunda Platform 7
2. Start Wanaku MCP Router
3. Start the two workers:

```
cib-seven-llm-worker
cib-seven-wanaku-worker
```

4. Deploy the BPMN model.
5. Start a process instance.

---

## Tested Environment

This example has been tested with:

- **Wanaku MCP Router**
- **Official Camel Integration Capability**
- **Version 0.0.9**

The following tools were added to the Wanaku toolset:

- HTTP currency tool defined in `currency.json`
- Camel tools defined in `demo.rules.yaml`

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

## Demonstration

A **short silent clip** demonstrating the example execution is attached to this repository.

The clip shows:

- BPMN process execution
- LLM decision loop
- Tool invocation through Wanaku
- Final response generation
