# Tool Discovery Mechanism Analysis - cib-seven-wanaku-worker

## Executive Summary

The tool discovery feature in `cib-seven-wanaku-worker` is **fully implemented** and operational. It uses the MCP Java SDK's `McpSyncClient.listTools()` method to fetch and cache available tools from the Wanaku MCP Router via Streamable HTTP transport.

---

## 1. When Does Tool Discovery Happen?

Tool discovery occurs at **two specific times**:

### A. Application Startup (Immediate)
- **Trigger**: `ApplicationReadyEvent` from Spring Boot
- **Method**: `WanakuToolRegistryService.startPeriodicRefresh()`
- **Behavior**: Calls `refreshToolRegistry()` immediately
- **Timing**: After all Spring beans (including `McpSyncClient`) are initialized

### B. Periodic Refresh (Every 5 Minutes)
- **Trigger**: `ScheduledExecutorService` scheduled task
- **Method**: `WanakuToolRegistryService.refreshToolRegistry()`
- **Interval**: Every 5 minutes (hardcoded constant)
- **Scheduler**: Single-threaded executor

---

## 2. Implementation Status

### ✅ Fully Implemented Components

1. **WanakuToolRegistryService** - Complete implementation
   - Periodic refresh via MCP SDK `listTools()` ✅
   - In-memory caching ✅
   - Error handling ✅
   - Tool lookup methods ✅

2. **Configuration** - Complete
   - `WanakuProperties` with `mcpEndpoint` ✅
   - Default value: `/mcp/` ✅
   - Environment variable override support ✅

3. **MCP Client** - Complete
   - `McpClientConfig` creates `McpSyncClient` with `HttpClientStreamableHttpTransport` ✅
   - Configurable timeout via `wanaku.timeout-seconds` ✅
   - Graceful shutdown via `DisposableBean.destroy()` ✅

4. **Integration** - Complete
   - Used by `WanakuExternalTaskHandler` for tool existence check ✅
   - Used by `WanakuToolsFetchHandler` to populate Camunda process variables ✅
   - Jackson ObjectMapper for `McpSchema.Tool` → `ToolMetadata` conversion ✅

### ⚠️ Limitations & Considerations

1. **Refresh Interval Not Configurable**
   - Hardcoded to 5 minutes
   - Cannot be changed via `application.yaml`
   - Requires code change to modify

2. **No Manual Refresh Endpoint**
   - No REST API to trigger refresh on-demand
   - Only automatic refresh available

3. **No Health Check**
   - No indicator if tool registry is stale

---

## 3. How Tool Discovery Works

### Fetch & Cache Mechanism

```
┌─────────────────────────────────────────────────────────────┐
│ Application Startup                                         │
└────────────────┬────────────────────────────────────────────┘
                 │
                 v
┌─────────────────────────────────────────────────────────────┐
│ McpClientConfig creates McpSyncClient                       │
│ - HttpClientStreamableHttpTransport(baseUrl + mcpEndpoint)       │
│ - client.initialize() — establishes Streamable HTTP connection          │
└────────────────┬────────────────────────────────────────────┘
                 │
                 v
┌─────────────────────────────────────────────────────────────┐
│ ApplicationReadyEvent fired                                 │
└────────────────┬────────────────────────────────────────────┘
                 │
                 v
┌─────────────────────────────────────────────────────────────┐
│ WanakuToolRegistryService.startPeriodicRefresh()            │
│ - Check if wanaku.enabled = true                            │
│ - Call refreshToolRegistry() immediately                    │
│ - Schedule periodic refresh every 5 minutes                 │
└────────────────┬────────────────────────────────────────────┘
                 │
                 v
┌─────────────────────────────────────────────────────────────┐
│ refreshToolRegistry()                                       │
│ 1. mcpClient.listTools()  (MCP protocol over Streamable HTTP)           │
│ 2. Convert McpSchema.Tool → ToolMetadata via Jackson        │
│ 3. Update cachedTools (volatile field)                      │
│ 4. Log success/failure                                      │
└────────────────┬────────────────────────────────────────────┘
                 │
                 v
┌─────────────────────────────────────────────────────────────┐
│ In-Memory Cache (volatile List<ToolMetadata>)               │
│ - Thread-safe reads via volatile keyword                    │
│ - Atomic replacement on each refresh                        │
└─────────────────────────────────────────────────────────────┘
```

### MCP SDK Tool Structure

The MCP SDK's `McpSchema.Tool` contains:
- `name()` — tool name (String)
- `description()` — tool description (String)
- `inputSchema()` — JSON Schema as `McpSchema.JsonSchema`

These are converted to `ToolMetadata` with `inputSchema` serialised as `Map<String, Object>` via Jackson `objectMapper.convertValue()`.

---

## 4. Refresh Interval Configuration

### Current Implementation
- **Hardcoded**: `REFRESH_INTERVAL_MINUTES = 5`
- **Not Configurable**: Cannot be changed via `application.yaml`

### Recommendation: Make It Configurable

1. Add property to `WanakuProperties.java`:
```java
private int toolsRefreshIntervalMinutes = 5;
```

2. Update `application.yaml`:
```yaml
wanaku:
  tools-refresh-interval-minutes: 5
```

3. Update `WanakuToolRegistryService.java`:
```java
scheduler.scheduleAtFixedRate(
    this::refreshToolRegistry,
    wanakuProperties.getToolsRefreshIntervalMinutes(),
    wanakuProperties.getToolsRefreshIntervalMinutes(),
    TimeUnit.MINUTES
);
```

---

## 5. Usage in WanakuExternalTaskHandler

The tool registry is used for **validation only**:

```java
// Check if tool exists in registry
if (!toolRegistryService.toolExists(toolName)) {
    logger.warn("Tool '{}' not found in Wanaku registry. Attempting execution anyway.", toolName);
}
```

**Important**: Tool execution proceeds even if the tool is not in the registry. This is a **soft validation** approach that allows:
- Execution of newly added tools before the next refresh
- Execution of tools if the registry fetch failed
- Graceful degradation if Wanaku is temporarily unavailable

---

## Summary

| Aspect | Status | Details |
|--------|--------|---------|
| **Implementation** | ✅ Complete | Fully functional via MCP SDK |
| **Transport** | ✅ Streamable HTTP | `HttpClientStreamableHttpTransport` |
| **Startup Fetch** | ✅ Yes | Immediate on ApplicationReadyEvent |
| **Periodic Refresh** | ✅ Yes | Every 5 minutes |
| **Configurable Interval** | ❌ No | Hardcoded to 5 minutes |
| **Error Handling** | ✅ Yes | Logs errors, continues operation |
| **Thread Safety** | ✅ Yes | Volatile field for cache |
| **Graceful Shutdown** | ✅ Yes | MCP client closed via `DisposableBean` |
| **Health Check** | ❌ No | Not implemented |
| **Manual Refresh** | ❌ No | No REST endpoint |

The tool discovery mechanism is **production-ready** but could benefit from the recommended improvements for better observability and configurability.
