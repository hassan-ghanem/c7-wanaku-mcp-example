package org.cibseven.worker.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cibseven.bpm.client.ExternalTaskClient;
import org.cibseven.bpm.client.task.ExternalTask;
import org.cibseven.bpm.client.task.ExternalTaskService;
import org.cibseven.worker.config.WanakuProperties;
import org.cibseven.worker.model.ToolMetadata;
import org.cibseven.worker.service.WanakuToolRegistryService;
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
 * External task worker that exposes the Wanaku tool registry as a Camunda process variable.
 * Subscribes to the {@code wanaku-tools-fetch} topic (configurable via
 * {@code wanaku.tools-fetch-topic}).
 *
 * <p>When a BPMN process reaches a service task with this topic, the handler reads the
 * in-memory tool cache from {@link WanakuToolRegistryService}, serialises each
 * {@link ToolMetadata} entry to a plain {@code Map<String, Object>}, and writes the
 * resulting list as the {@code availableTools} process variable.  The variable is consumed
 * downstream by the {@code llm-decision} service task so that the LLM knows which tools
 * it may call.</p>
 *
 * <p>The handler can be disabled independently of the tool-execution handler by setting
 * {@code wanaku.tools-fetch-enabled=false} in {@code application.yaml}.
 * Setting {@code wanaku.enabled=false} disables the entire worker including this handler.</p>
 *
 * <h3>Input Variables (from Camunda process)</h3>
 * <p>None required.</p>
 *
 * <h3>Output Variables (to Camunda process)</h3>
 * <ul>
 *   <li>{@code availableTools} (List&lt;Map&gt;): Serialised list of available tool
 *       metadata, each entry containing {@code name}, {@code description}, and
 *       optionally {@code inputSchema}.  Compatible with
 *       {@code LlmExternalTaskHandler.convertToToolMetadata()}.</li>
 * </ul>
 */
@Service
public class WanakuToolsFetchHandler {

    private static final Logger logger = LoggerFactory.getLogger(WanakuToolsFetchHandler.class);

    @Value("${camunda.bpm.client.base-url}")
    private String baseUrl;

    @Value("${camunda.bpm.client.async-response-timeout}")
    private int asyncResponseTimeout;

    @Value("${camunda.bpm.client.lock-duration}")
    private int lockDuration;

    @Autowired
    private WanakuProperties wanakuProperties;

    @Autowired
    private WanakuToolRegistryService toolRegistryService;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Subscribe to the tools-fetch external task topic after the application is fully ready.
     * This ensures the tool registry has already performed its initial fetch.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void subscribeExternalTask() {

        if (!wanakuProperties.isEnabled()) {
            logger.warn("Wanaku worker is disabled (wanaku.enabled=false). "
                    + "Tools-fetch external task subscription will not be started.");
            return;
        }

        if (!wanakuProperties.isToolsFetchEnabled()) {
            logger.warn("Tools-fetch handler is disabled (wanaku.tools-fetch-enabled=false). "
                    + "Subscription to topic '{}' will not be started.",
                    wanakuProperties.getToolsFetchTopic());
            return;
        }

        logger.info("Subscribing to external task topic: {}", wanakuProperties.getToolsFetchTopic());

        ExternalTaskClient client = ExternalTaskClient.create()
                .baseUrl(baseUrl)
                .asyncResponseTimeout(asyncResponseTimeout)
                .build();

        client.subscribe(wanakuProperties.getToolsFetchTopic())
                .lockDuration(lockDuration)
                .handler(this::handleTask)
                .open();

        logger.info("Successfully subscribed to external task topic: {}",
                wanakuProperties.getToolsFetchTopic());
    }

    private void handleTask(ExternalTask externalTask, ExternalTaskService externalTaskService) {
        try {
            logger.info("Processing tools-fetch external task: {}", externalTask.getId());

            // Read the cached tool list — no network call, instant
            List<ToolMetadata> tools = toolRegistryService.getAvailableTools();

            logger.debug("Serialising {} tool(s) into availableTools process variable", tools.size());

            // Serialise each ToolMetadata to Map<String, Object> so Camunda can store it as
            // a plain spin/JSON variable.  LlmExternalTaskHandler.convertToToolMetadata()
            // deserialises this back via objectMapper.convertValue(rawTool, ToolMetadata.class).
            List<Map<String, Object>> availableTools = new ArrayList<>(tools.size());
            for (ToolMetadata tool : tools) {
                Map<String, Object> toolMap = objectMapper.convertValue(
                        tool, new TypeReference<Map<String, Object>>() {});
                availableTools.add(toolMap);
            }

            Map<String, Object> variables = new HashMap<>();
            variables.put("availableTools", availableTools);

            externalTaskService.complete(externalTask, variables);

            logger.info("Tools-fetch task completed: {} tool(s) written to availableTools (task: {})",
                    availableTools.size(), externalTask.getId());

        } catch (Exception e) {
            logger.error("Error processing tools-fetch external task: {}", externalTask.getId(), e);
            handleFailure(externalTaskService, externalTask, e);
        }
    }

    /**
     * Handle task failure by reporting the error back to Camunda.
     */
    private void handleFailure(ExternalTaskService externalTaskService,
                                ExternalTask externalTask,
                                Exception e) {
        String errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        externalTaskService.handleFailure(externalTask, errorMessage, null, 0, 0);
    }
}

