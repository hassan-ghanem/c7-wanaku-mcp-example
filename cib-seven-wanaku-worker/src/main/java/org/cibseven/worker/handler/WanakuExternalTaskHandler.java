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
 * Each instance of this handler executes ONE tool call from the
 * {@code toolCalls} list produced by {@code LlmExternalTaskHandler}.
 * It is always run inside a BPMN Multi-Instance Service Task (parallel,
 * not sequential), so multiple instances may execute concurrently.
 * </p>
 *
 * <p>
 * The worker can be disabled by setting {@code wanaku.enabled=false} in
 * {@code application.yaml}.
 * </p>
 *
 * <h3>Input Variables (from BPMN multi-instance element variable mapping)</h3>
 * <ul>
 * <li>{@code callId}  (String, required): Sanitised unique identifier for this
 *     tool call within the current iteration. Used as the suffix of the output
 *     variable names.</li>
 * <li>{@code toolName} (String, required): Name of the tool to execute.</li>
 * <li>{@code toolArgs} (Map, optional): Arguments to pass to the tool.</li>
 * </ul>
 *
 * <h3>Output Variables (written to process scope per instance)</h3>
 * <ul>
 * <li>{@code toolResult_<callId>} (String): Concatenated text result from the
 *     MCP tool. Empty string if the tool returns no text content.</li>
 * </ul>
 *
 * <h3>Error Behaviour</h3>
 * <p>A tool-level MCP error ({@code CallToolResult.isError() == true}) is
 * treated the same as a transport exception: {@code handleFailure()} is called,
 * which decrements the Camunda retry counter and ultimately raises a Camunda
 * incident when no retries remain. This ensures that a failing parallel branch
 * surfaces as a visible incident rather than silently propagating error text
 * back to the LLM.</p>
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

            // ── Read per-instance variables injected by the BPMN multi-instance mapping ──
            String callId   = externalTask.getVariable("callId");
            String toolName = externalTask.getVariable("toolName");
            Map<String, Object> toolArgs = externalTask.getVariable("toolArgs");

            // Validate required inputs
            if (callId == null || callId.isBlank()) {
                handleFailure(externalTaskService, externalTask,
                        new IllegalArgumentException("callId is required"));
                return;
            }
            if (toolName == null || toolName.trim().isEmpty()) {
                handleFailure(externalTaskService, externalTask,
                        new IllegalArgumentException("toolName is required"));
                return;
            }

            if (toolArgs == null) {
                toolArgs = new HashMap<>();
            }

            // Check if tool exists in registry (warn-only; proceed regardless)
            if (!toolRegistryService.toolExists(toolName)) {
                logger.warn("Tool '{}' not found in Wanaku registry. Attempting execution anyway.", toolName);
            }

            logger.debug("Calling Wanaku tool '{}' (callId='{}') with args: {}", toolName, callId, toolArgs);

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

            // ── Tool-level MCP error → Camunda incident ───────────────────────────
            // A tool-level error (CallToolResult.isError() == true) is treated as a
            // hard failure: we raise a Camunda incident so the failing parallel branch
            // is visible and does not silently produce bad data in conversationHistory.
            if (result.isError() != null && result.isError()) {
                String errorMessage = extractResultText(result);
                if (errorMessage.isEmpty()) {
                    errorMessage = "Tool '" + toolName + "' returned an error with no message.";
                }
                logger.error("Tool '{}' (callId='{}') returned a tool-level error: {}", toolName, callId, errorMessage);
                handleFailure(externalTaskService, externalTask,
                        new RuntimeException("Tool error from '" + toolName + "': " + errorMessage));
                return;
            }

            // ── Success → write indexed result variable ────────────────────────────
            String resultText = extractResultText(result);
            logger.info("Tool '{}' (callId='{}') executed successfully", toolName, callId);

            Map<String, Object> variables = new HashMap<>();
            variables.put("toolResult_" + callId, resultText);

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
