# CIB Seven Wanaku Worker

MCP (Model Context Protocol) tool execution worker for CIB Seven Platform using the MCP Java SDK with Streamable HTTP transport.

## Purpose

Executes tools via the **Wanaku MCP Router** for Camunda BPMN processes. Acts as the execution layer for tool calls decided by the LLM worker.

## What It Does

- Connects to Wanaku's MCP Router via Streamable HTTP transport
- Receives tool execution requests from Camunda (tool name + arguments)
- Calls tools using the MCP SDK's `callTool()` method
- Returns tool execution results to the Camunda process
- Fetches and caches available tools using the MCP SDK's `listTools()` method

## What It Does NOT Do

- Make decisions about which tools to use (that's the LLM worker's job)
- Directly interact with LLMs
- Manage conversation state

## Architecture

- **External Task Topics**: `wanaku-tool-execute`, `wanaku-tools-fetch`
- **MCP Protocol**: Streamable HTTP transport via MCP Java SDK (`io.modelcontextprotocol.sdk:mcp:0.18.0`)
- **MCP Client**: `McpSyncClient` with `HttpClientStreamableHttpTransport`
- **Output**: Camunda process variables (`toolResult`, `availableTools`)

## Configuration

See `src/main/resources/application.yaml` for configuration options.

Key settings:
- `wanaku.base-url`: Wanaku MCP Router base URL (e.g., `http://localhost:8080`)
- `wanaku.mcp-endpoint`: Streamable HTTP endpoint path (default: `/mcp/`)
- `wanaku.timeout-seconds`: MCP request timeout

## Running Locally

```bash
# Ensure Wanaku MCP Router is running on http://localhost:8080
# (See https://github.com/wanaku-ai/wanaku-demos for setup)

# Run the worker
mvn spring-boot:run
```

## Running with Docker

```bash
# Build the image
docker build -t cib-seven-wanaku-worker .

# Run the container
docker run -p 8083:8083 \
  -e CIB_SEVEN_BASE_URL=http://cib-seven-platform:8080/engine-rest \
  -e WANAKU_BASE_URL=http://wanaku:8080 \
  -e WANAKU_MCP_ENDPOINT=/mcp/ \
  cib-seven-wanaku-worker
```

## Integration with Camunda

The worker subscribes to two external task topics:

### Tool Execution (`wanaku-tool-execute`)

- **Input Variables**: `toolName`, `toolArgs`
- **Output Variables**: `toolResult`, `toolError`

### Tool Catalogue Fetch (`wanaku-tools-fetch`)

- **Input Variables**: *(none)*
- **Output Variables**: `availableTools`

## License

Same as CIB Seven Platform
