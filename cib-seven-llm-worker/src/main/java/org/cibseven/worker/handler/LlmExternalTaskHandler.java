package org.cibseven.worker.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.apache.camel.ProducerTemplate;
import org.cibseven.bpm.client.ExternalTaskClient;
import org.cibseven.bpm.client.task.ExternalTask;
import org.cibseven.bpm.client.task.ExternalTaskService;
import org.cibseven.worker.config.LlmProperties;
import org.cibseven.worker.model.AgentDecision;
import org.cibseven.worker.model.ToolMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * External task worker for LLM-based decision making.
 * Subscribes to the "llm-decision" topic (configurable via {@code llm.topic}).
 *
 * <p>
 * The worker receives user requests and conversation context, calls the LLM
 * to decide whether a tool needs to be executed or a final answer can be
 * provided,
 * and returns the decision as Camunda process variables.
 * </p>
 *
 * <p>
 * The worker can be disabled by setting {@code llm.enabled=false} in
 * {@code application.yaml}.
 * </p>
 *
 * <h3>Input Variables (from Camunda process)</h3>
 * <ul>
 * <li>{@code userRequest} (String, required): The user's request or
 * question</li>
 * <li>{@code conversationHistory} (List<Map>, optional): Previous messages in
 * the conversation</li>
 * <li>{@code availableTools} (List<Map>, optional): Tools available for the LLM
 * to use</li>
 * </ul>
 *
 * <h3>Output Variables (to Camunda process)</h3>
 * <ul>
 * <li>{@code requiresTool} (Boolean): Whether a tool needs to be executed</li>
 * <li>{@code toolName} (String): Name of the tool to execute (null if no tool
 * needed)</li>
 * <li>{@code toolArgs} (Map): Arguments for the tool (null if no tool
 * needed)</li>
 * <li>{@code finalAnswer} (String): Final answer to the user (null if tool
 * needed)</li>
 * </ul>
 */
@Service
public class LlmExternalTaskHandler {

    private static final Logger logger = LoggerFactory.getLogger(LlmExternalTaskHandler.class);

    @Value("${camunda.bpm.client.base-url}")
    private String baseUrl;

    @Value("${camunda.bpm.client.async-response-timeout}")
    private int asyncResponseTimeout;

    @Value("${camunda.bpm.client.lock-duration}")
    private int lockDuration;

    @Autowired
    private LlmProperties llmProperties;

    @Autowired
    private ProducerTemplate producerTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Subscribe to external tasks after the application is fully ready.
     * This ensures the Camel context and LLM model are initialized.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void subscribeExternalTask() {

        if (!llmProperties.isEnabled()) {
            logger.warn("LLM worker is disabled (llm.enabled=false). "
                    + "External task subscription will not be started.");
            return;
        }

        logger.info("Subscribing to external task topic: {}", llmProperties.getTopic());

        ExternalTaskClient client = ExternalTaskClient.create()
                .baseUrl(baseUrl)
                .asyncResponseTimeout(asyncResponseTimeout)
                .build();

        client.subscribe(llmProperties.getTopic())
                .lockDuration(lockDuration)
                .handler(this::handleTask)
                .open();

        logger.info("Successfully subscribed to external task topic: {}", llmProperties.getTopic());
    }

    private void handleTask(ExternalTask externalTask, ExternalTaskService externalTaskService) {
        try {
            logger.info("Processing external task: {}", externalTask.getId());

            // Extract input variables
            String userRequest = externalTask.getVariable("userRequest");
            List<Map<String, Object>> conversationHistory = externalTask.getVariable("conversationHistory");
            List<Map<String, Object>> availableToolsRaw = externalTask.getVariable("availableTools");

            // Validate required input
            if (userRequest == null || userRequest.trim().isEmpty()) {
                handleFailure(externalTaskService, externalTask,
                        new IllegalArgumentException("userRequest is required"));
                return;
            }

            // Convert available tools to ToolMetadata
            List<ToolMetadata> availableTools = convertToToolMetadata(availableToolsRaw);

            // Build chat messages for LLM
            List<ChatMessage> messages = buildChatMessages(userRequest, conversationHistory, availableTools);

            logger.debug("Calling LLM with {} messages", messages.size());

            // Call LLM via Camel route
            String llmResponse = producerTemplate.requestBody("direct:callLlm", messages, String.class);

            logger.debug("LLM response: {}", llmResponse);

            // Parse LLM response to AgentDecision
            AgentDecision decision = parseLlmResponse(llmResponse);

            // Prepare output variables
            Map<String, Object> variables = new HashMap<>();
            variables.put("requiresTool", decision.isRequiresTool());
            variables.put("toolName", decision.getToolName());
            variables.put("toolArgs", decision.getToolArgs());
            variables.put("finalAnswer", decision.getFinalAnswer());

            // Complete the external task
            externalTaskService.complete(externalTask, variables);
            logger.info("External task completed successfully: {}", externalTask.getId());

        } catch (Exception e) {
            logger.error("Error executing external task: {}", externalTask.getId(), e);
            handleFailure(externalTaskService, externalTask, e);
        }
    }

    /**
     * Convert raw tool data from Camunda variables to ToolMetadata objects.
     */
    private List<ToolMetadata> convertToToolMetadata(List<Map<String, Object>> rawTools) {
        if (rawTools == null || rawTools.isEmpty()) {
            return new ArrayList<>();
        }

        List<ToolMetadata> tools = new ArrayList<>();
        for (Map<String, Object> rawTool : rawTools) {
            try {
                ToolMetadata tool = objectMapper.convertValue(rawTool, ToolMetadata.class);
                tools.add(tool);
            } catch (Exception e) {
                logger.warn("Failed to convert tool metadata: {}", rawTool, e);
            }
        }
        return tools;
    }

    /**
     * Build the list of chat messages to send to the LLM.
     * Includes system prompt, conversation history, and current user request.
     */
    private List<ChatMessage> buildChatMessages(
            String userRequest,
            List<Map<String, Object>> conversationHistory,
            List<ToolMetadata> availableTools) {

        List<ChatMessage> messages = new ArrayList<>();

        // Add system prompt
        String systemPrompt = buildSystemPrompt(availableTools);
        messages.add(new SystemMessage(systemPrompt));

        // Add conversation history
        if (conversationHistory != null && !conversationHistory.isEmpty()) {
            for (Map<String, Object> historyEntry : conversationHistory) {
                String role = (String) historyEntry.get("role");
                String content = (String) historyEntry.get("content");

                if ("user".equals(role)) {
                    messages.add(new UserMessage(content));
                } else if ("assistant".equals(role)) {
                    messages.add(new AiMessage(content));
                }
            }
        }

        // Add current user request
        messages.add(new UserMessage(userRequest));

        return messages;
    }

    /**
     * Build the system prompt that instructs the LLM on how to respond.
     */
    private String buildSystemPrompt(List<ToolMetadata> availableTools) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(
                "You are an intelligent agent that helps users by deciding whether to use tools or provide direct answers.\n\n");
        prompt.append(
                "Your task is to analyze the user's request and respond with a JSON object in the following format:\n\n");
        prompt.append("If a tool is needed:\n");
        prompt.append("{\n");
        prompt.append("  \"requiresTool\": true,\n");
        prompt.append("  \"toolName\": \"<tool_name>\",\n");
        prompt.append("  \"toolArgs\": {\"arg1\": \"value1\", \"arg2\": \"value2\"},\n");
        prompt.append("  \"finalAnswer\": null\n");
        prompt.append("}\n\n");
        prompt.append("If no tool is needed (you can answer directly):\n");
        prompt.append("{\n");
        prompt.append("  \"requiresTool\": false,\n");
        prompt.append("  \"toolName\": null,\n");
        prompt.append("  \"toolArgs\": null,\n");
        prompt.append("  \"finalAnswer\": \"<your answer>\"\n");
        prompt.append("}\n\n");

        if (availableTools != null && !availableTools.isEmpty()) {
            prompt.append("Available tools:\n");
            for (ToolMetadata tool : availableTools) {
                prompt.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append("\n");
                if (tool.getInputSchema() != null) {
                    try {
                        String schemaJson = objectMapper.writeValueAsString(tool.getInputSchema());
                        prompt.append("  Input schema: ").append(schemaJson).append("\n");
                    } catch (Exception e) {
                        logger.warn("Failed to serialize input schema for tool: {}", tool.getName(), e);
                    }
                }
            }
            prompt.append("\n");
        }

        prompt.append(
                "IMPORTANT: Respond ONLY with valid JSON. Do not include any explanatory text before or after the JSON.");

        return prompt.toString();
    }

    /**
     * Parse the LLM's response into an AgentDecision object.
     */
    private AgentDecision parseLlmResponse(String llmResponse) throws Exception {
        // Extract JSON from response (in case LLM adds extra text)
        String jsonResponse = extractJson(llmResponse);

        // Parse JSON to AgentDecision
        return objectMapper.readValue(jsonResponse, AgentDecision.class);
    }

    /**
     * Extract JSON object from LLM response (handles cases where LLM adds extra
     * text).
     */
    private String extractJson(String response) {
        // Find first '{' and last '}'
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');

        if (start == -1 || end == -1 || start >= end) {
            // No JSON found, return as-is and let parser fail with clear error
            return response;
        }

        return response.substring(start, end + 1);
    }

    /**
     * Handle task failure with configurable retry logic.
     *
     * <p>
     * On the very first failure {@code externalTask.getRetries()} is {@code null}
     * (Camunda has not set it yet), so the retry counter is seeded from
     * {@code llmProperties.getRetries() - 1}. On each subsequent attempt the
     * counter stored in Camunda is decremented by one until it reaches zero, at
     * which
     * point the task is moved to the incident queue.
     * </p>
     *
     * <p>
     * Set {@code llm.retries=0} to disable retries and fail immediately.
     * </p>
     */
    private void handleFailure(ExternalTaskService externalTaskService, ExternalTask externalTask, Exception e) {
        String errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();

        // Seed on first attempt (retries == null), then decrement each time
        int remaining = externalTask.getRetries() != null
                ? externalTask.getRetries() - 1
                : llmProperties.getRetries() - 1;
        remaining = Math.max(0, remaining);

        long interval = remaining > 0 ? llmProperties.getRetryIntervalMs() : 0L;

        externalTaskService.handleFailure(externalTask, errorMessage, e.toString(), remaining, interval);

        if (remaining > 0) {
            logger.warn("External task {} failed, retrying in {}ms. Retries remaining: {}. Error: {}",
                    externalTask.getId(), interval, remaining, errorMessage);
        } else {
            logger.error("External task {} failed with no retries remaining. Error: {}",
                    externalTask.getId(), errorMessage);
        }
    }
}
