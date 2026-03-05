# CIB Seven LLM Worker

LLM-based decision engine worker for CIB Seven Platform using LangChain4j (via Apache Camel).

## Purpose

Acts as a **decision engine** for Camunda BPMN processes. It evaluates context, selects tools, generates arguments, or produces final answers.

## What It Does

- Evaluates user requests and conversation history
- Selects the next tool to execute (or determines no tool is needed)
- Generates required arguments for tool execution
- Produces final answers when the task is complete

## What It Does NOT Do

- Execute tools
- Call REST APIs
- Perform business logic
- Persist workflow state

## Architecture

- **External Task Topic**: `llm-decision`
- **LLM Providers**: Ollama (default), OpenAI
- **Integration**: Apache Camel `langchain4j-chat` component
- **Output**: Camunda process variables (`requiresTool`, `toolName`, `toolArgs`, `finalAnswer`)

## Configuration

See `src/main/resources/application.yaml` for configuration options.

Key settings:
- `llm.provider`: `ollama` or `openai`
- `llm.model-name`: Model to use (e.g., `llama3.2`, `gpt-4o-mini`)
- `llm.base-url`: LLM service endpoint
- `llm.api-key`: API key (required for OpenAI)
- `llm.temperature`: Sampling temperature (0.0 = deterministic)

## Running Locally

```bash
# Start Ollama (if using local LLM)
ollama serve

# Pull a model
ollama pull llama3.2

# Run the worker
mvn spring-boot:run
```

## Running with Docker

```bash
# Build the image
docker build -t cib-seven-llm-worker .

# Run the container
docker run -p 8082:8082 \
  -e CIB_SEVEN_BASE_URL=http://cib-seven-platform:8080/engine-rest \
  -e LLM_BASE_URL=http://ollama:11434 \
  cib-seven-llm-worker
```

## Integration with Camunda

The worker subscribes to the `llm-decision` external task topic. In your BPMN process, create a service task with:

- **Topic**: `llm-decision`
- **Input Variables**: `userRequest`, `conversationHistory`, `availableTools`
- **Output Variables**: `requiresTool`, `toolName`, `toolArgs`, `finalAnswer`

## License

Same as CIB Seven Platform

