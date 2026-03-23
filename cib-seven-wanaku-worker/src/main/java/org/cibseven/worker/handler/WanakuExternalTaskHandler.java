package org.cibseven.worker.handler;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.cibseven.bpm.client.ExternalTaskClient;
import org.cibseven.bpm.client.exception.EngineException;
import org.cibseven.bpm.client.task.ExternalTask;
import org.cibseven.bpm.client.task.ExternalTaskService;
import org.cibseven.worker.config.WanakuProperties;
import org.cibseven.worker.service.WanakuToolRegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * External task worker for Wanaku MCP tool execution.
 * Subscribes to the "wanaku-tool-execute" topic (configurable via
 * {@code wanaku.topic}).
 *
 * <p>
 * The worker receives tool execution requests from Camunda (typically from the
 * LLM worker),
 * calls Wanaku's MCP endpoint via the MCP Java SDK's Streamable HTTP transport,
 * and returns
 * the result.
 * </p>
 *
 * <p>
 * The worker can be disabled by setting {@code wanaku.enabled=false} in
 * {@code application.yaml}.
 * </p>
 *
 * <h3>Input Variables (from Camunda process)</h3>
 * <ul>
 * <li>{@code toolName} (String, required): Name of the tool to execute</li>
 * <li>{@code toolArgs} (Map, required): Arguments to pass to the tool</li>
 * </ul>
 *
 * <h3>Output Variables (to Camunda process)</h3>
 * <ul>
 * <li>{@code toolResult} (String): Result from the tool execution</li>
 * <li>{@code toolError} (String, optional): Error message if execution
 * failed</li>
 * </ul>
 */
@Service
public class WanakuExternalTaskHandler {

    private static final Logger logger = LoggerFactory.getLogger(WanakuExternalTaskHandler.class);

    /**
     * Single-thread scheduler used to periodically extend the Camunda lock while
     * an MCP {@code callTool()} request is in progress. One thread is sufficient
     * because heartbeats are lightweight and non-overlapping.
     */
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "wanaku-lock-heartbeat");
        t.setDaemon(true);
        return t;
    });

    private final String baseUrl;

    private final int asyncResponseTimeout;

    private final int lockDuration;

    private final WanakuProperties wanakuProperties;

    private final McpSyncClient mcpClient;

    private final WanakuToolRegistryService toolRegistryService;

    public WanakuExternalTaskHandler(
            @Value("${camunda.bpm.client.base-url}") String baseUrl,
            @Value("${camunda.bpm.client.async-response-timeout}") int asyncResponseTimeout,
            @Value("${camunda.bpm.client.lock-duration}") int lockDuration,
            WanakuProperties wanakuProperties,
            McpSyncClient mcpClient,
            WanakuToolRegistryService toolRegistryService) {
        this.baseUrl = baseUrl;
        this.asyncResponseTimeout = asyncResponseTimeout;
        this.lockDuration = lockDuration;
        this.wanakuProperties = wanakuProperties;
        this.mcpClient = mcpClient;
        this.toolRegistryService = toolRegistryService;
    }

    /**
     * Subscribe to external tasks after the application is fully ready.
     * This ensures the MCP client and tool registry are initialized.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void subscribeExternalTask() {

        if (!wanakuProperties.isEnabled()) {
            logger.warn("Wanaku worker is disabled (wanaku.enabled=false). "
                    + "External task subscription will not be started.");
            return;
        }

        logger.info("Subscribing to external task topic: {}", wanakuProperties.getTopic());

        ExternalTaskClient client = ExternalTaskClient.create()
                .baseUrl(baseUrl)
                .asyncResponseTimeout(asyncResponseTimeout)
                .build();

        client.subscribe(wanakuProperties.getTopic())
                .lockDuration(lockDuration)
                .handler(this::handleTask)
                .open();

        logger.info("Successfully subscribed to external task topic: {}", wanakuProperties.getTopic());
    }

    private void handleTask(ExternalTask externalTask, ExternalTaskService externalTaskService) {
        try {
            logger.info("Processing external task: {}", externalTask.getId());

            // Extract input variables
            String toolName = externalTask.getVariable("toolName");
            Map<String, Object> toolArgs = externalTask.getVariable("toolArgs");

            // Validate required input
            if (toolName == null || toolName.trim().isEmpty()) {
                handleFailure(externalTaskService, externalTask,
                        new IllegalArgumentException("toolName is required"));
                return;
            }

            if (toolArgs == null) {
                toolArgs = new HashMap<>();
            }

            // Check if tool exists in registry
            if (!toolRegistryService.toolExists(toolName)) {
                logger.warn("Tool '{}' not found in Wanaku registry. Attempting execution anyway.", toolName);
            }

            logger.debug("Calling Wanaku tool '{}' with args: {}", toolName, toolArgs);

            // ── Lock-extension heartbeat ──────────────────────────────────────────
            // The Camunda lock has a finite duration (camunda.bpm.client.lock-duration).
            // MCP tool calls — especially LLM-backed ones — can take much longer than
            // that duration. Without this heartbeat the engine auto-unlocks the task
            // and re-delivers it, causing the same tool to be invoked a second time
            // concurrently. We extend the lock every lockDuration/2 ms so it never
            // expires while callTool() is still blocking.
            //
            // IMPORTANT — concurrent-update guard:
            // ScheduledFuture.cancel(false) only prevents *future* firings; it returns
            // immediately without waiting for a heartbeat that is already in progress.
            // If callTool() throws while extendLock() is mid-HTTP-call, both
            // extendLock() and the subsequent handleFailure()/complete() would hit
            // Camunda at the same time and produce ENGINE-03005 ("Entity was updated by
            // another transaction concurrently").
            // The heartbeatMutex + heartbeatStopped flag guarantee that complete() /
            // handleFailure() only runs *after* the last extendLock() HTTP call has
            // fully returned.
            final Object heartbeatMutex = new Object();
            final AtomicBoolean heartbeatStopped = new AtomicBoolean(false);

            long heartbeatIntervalMs = lockDuration / 2L;
            ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(() -> {
                synchronized (heartbeatMutex) {
                    // Re-check inside the lock: cancellation may have happened while we
                    // were waiting to acquire heartbeatMutex.
                    if (heartbeatStopped.get()) {
                        return;
                    }
                    try {
                        externalTaskService.extendLock(externalTask, lockDuration);
                        logger.debug("Extended lock for task {} by {} ms", externalTask.getId(), lockDuration);
                    } catch (Exception ex) {
                        // Log but do not rethrow — a missed extension is not fatal;
                        // the lock will still be valid for another heartbeatIntervalMs.
                        logger.warn("Failed to extend lock for task {}: {}", externalTask.getId(), ex.getMessage());
                    }
                }
            }, heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
            // ─────────────────────────────────────────────────────────────────────

            CallToolResult result;
            try {
                // Call tool via MCP SDK (blocks until the HTTP response arrives or timeout)
                result = mcpClient.callTool(new CallToolRequest(toolName, toolArgs));
            } finally {
                // 1. Stop future firings immediately.
                heartbeat.cancel(false);
                // 2. Acquire the mutex: this blocks until any in-progress extendLock()
                // HTTP call has returned, then permanently disables the heartbeat.
                // After this synchronized block, complete()/handleFailure() can
                // safely update the task entity without a concurrent-update conflict.
                synchronized (heartbeatMutex) {
                    heartbeatStopped.set(true);
                }
            }

            // Prepare output variables
            Map<String, Object> variables = new HashMap<>();

            if (result.isError() != null && result.isError()) {
                // Handle error
                String errorMessage = extractResultText(result);
                variables.put("toolResult", null);
                variables.put("toolError", errorMessage.isEmpty() ? "Unknown error" : errorMessage);

                logger.error("Tool '{}' execution failed: {}", toolName, errorMessage);
            } else {
                // Extract result text from content
                String resultText = extractResultText(result);
                variables.put("toolResult", resultText);
                variables.put("toolError", null);

                logger.info("Tool '{}' executed successfully", toolName);
            }

            // Complete the external task.
            // Guard against ENGINE-03005: if the lock was reclaimed by the engine
            // while we were executing (e.g. transient heartbeat failure, app restart
            // during a long run) the entity revision will have changed. Camunda will
            // re-deliver the task automatically, so we only need to log and return.
            try {
                externalTaskService.complete(externalTask, variables);
                logger.info("External task completed successfully: {}", externalTask.getId());
            } catch (EngineException ex) {
                logger.warn("Could not complete task {} — lock was reclaimed by the engine "
                        + "(ENGINE-03005); task will be re-delivered automatically. Cause: {}",
                        externalTask.getId(), ex.getMessage());
            }

        } catch (Exception e) {
            logger.error("Error executing external task: {}", externalTask.getId(), e);
            handleFailure(externalTaskService, externalTask, e);
        }
    }

    @PreDestroy
    public void shutdown() {
        logger.info("Shutting down lock-heartbeat scheduler");
        heartbeatScheduler.shutdown();
    }

    /**
     * Extract result text from MCP CallToolResult content.
     */
    private String extractResultText(CallToolResult result) {
        if (result.content() == null) {
            return "";
        }

        StringBuilder resultText = new StringBuilder();
        for (McpSchema.Content item : result.content()) {
            if (item instanceof McpSchema.TextContent textContent) {
                if (resultText.length() > 0) {
                    resultText.append("\n");
                }
                resultText.append(textContent.text());
            }
        }

        return resultText.toString();
    }

    /**
     * Handle task failure with configurable retry logic.
     *
     * <p>
     * On the very first failure {@code externalTask.getRetries()} is {@code null}
     * (Camunda has not set it yet), so the retry counter is seeded from
     * {@code wanakuProperties.getRetries() - 1}. On each subsequent attempt the
     * counter stored in Camunda is decremented by one until it reaches zero, at
     * which
     * point the task is moved to the incident queue.
     * </p>
     *
     * <p>
     * Set {@code wanaku.retries=0} to disable retries and fail immediately.
     * </p>
     */
    private void handleFailure(ExternalTaskService externalTaskService, ExternalTask externalTask, Exception e) {
        String errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();

        // Seed on first attempt (retries == null), then decrement each time
        int remaining = externalTask.getRetries() != null
                ? externalTask.getRetries() - 1
                : wanakuProperties.getRetries() - 1;
        remaining = Math.max(0, remaining);

        long interval = remaining > 0 ? wanakuProperties.getRetryIntervalMs() : 0L;

        // Guard against ENGINE-03005: the lock may have been reclaimed by the engine
        // (e.g. transient heartbeat failure, network partition, app restart mid-task).
        // In that case Camunda will re-deliver the task automatically — we must not
        // let the EngineException propagate to TopicSubscriptionManager, which would
        // only log TASK/CLIENT-03004 noise without any remediation.
        try {
            externalTaskService.handleFailure(externalTask, errorMessage, e.toString(), remaining, interval);
        } catch (EngineException ex) {
            logger.warn("Could not report failure for task {} — lock was reclaimed by the engine "
                    + "(ENGINE-03005); task will be re-delivered automatically. Cause: {}",
                    externalTask.getId(), ex.getMessage());
            return;
        }

        if (remaining > 0) {
            logger.warn("External task {} failed, retrying in {}ms. Retries remaining: {}. Error: {}",
                    externalTask.getId(), interval, remaining, errorMessage);
        } else {
            logger.error("External task {} failed with no retries remaining. Error: {}",
                    externalTask.getId(), errorMessage);
        }
    }
}
