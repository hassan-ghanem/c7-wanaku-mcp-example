package org.cibseven.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import org.cibseven.worker.config.WanakuProperties;
import org.cibseven.worker.model.ToolMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Service for fetching and caching the list of available tools from Wanaku.
 *
 * <p>
 * This service periodically fetches the tool registry from Wanaku via the MCP
 * SDK's
 * {@code listTools()} method and caches the results in memory.
 * </p>
 *
 * <p>
 * The cached tool list can be used by the LLM worker to provide context about
 * available tools when making decisions.
 * </p>
 */
@Service
public class WanakuToolRegistryService {

    private static final Logger logger = LoggerFactory.getLogger(WanakuToolRegistryService.class);

    /** How often to refresh the tool registry (in minutes). */
    private static final int REFRESH_INTERVAL_MINUTES = 5;

    /**
     * How long to wait for the initial Keycloak token before the first MCP connect
     * attempt. Set to slightly more than
     * {@code KeycloakTokenService.RETRY_DELAY_SECONDS}
     * (30 s) so a single Keycloak retry has time to succeed.
     */
    private static final long INITIAL_TOKEN_WAIT_SECONDS = 60L;

    @Autowired
    private WanakuProperties wanakuProperties;

    @Autowired
    private McpSyncClient mcpClient;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Keycloak token service — present only when
     * {@code wanaku.keycloak.enabled=true}.
     */
    @Autowired
    private Optional<KeycloakTokenService> keycloakTokenService;

    /** Cached list of available tools. */
    private volatile List<ToolMetadata> cachedTools = new ArrayList<>();

    /** Scheduler for periodic refresh. */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "wanaku-tool-registry-refresh");
        t.setDaemon(true);
        return t;
    });

    /**
     * Start periodic refresh of tool registry after application is ready.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startPeriodicRefresh() {
        if (!wanakuProperties.isEnabled()) {
            logger.warn("Wanaku worker is disabled. Tool registry refresh will not be started.");
            return;
        }

        logger.info("Starting periodic tool registry refresh (every {} minutes)", REFRESH_INTERVAL_MINUTES);

        // Schedule periodic refresh; initial delay 0 runs immediately on the background
        // thread so the ApplicationReadyEvent is not blocked by the HTTP connect
        // timeout.
        scheduler.scheduleAtFixedRate(
                this::refreshToolRegistry,
                0,
                REFRESH_INTERVAL_MINUTES,
                TimeUnit.MINUTES);
    }

    /**
     * Fetch the tool registry from Wanaku via MCP SDK and update the cache.
     *
     * <p>
     * On the very first call the method waits up to
     * {@value #INITIAL_TOKEN_WAIT_SECONDS} seconds for the Keycloak token to arrive
     * before connecting to Wanaku, to avoid an unauthenticated HTTP request that
     * would be rejected with HTTP 401.
     * </p>
     */
    public void refreshToolRegistry() {
        // Wait for Keycloak token on the first attempt so the HTTP connect
        // is authenticated. Subsequent calls skip this — the token is always set.
        keycloakTokenService.ifPresent(svc -> {
            if (!svc.awaitInitialToken(INITIAL_TOKEN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                logger.warn("Keycloak token not available after {}s — proceeding anyway",
                        INITIAL_TOKEN_WAIT_SECONDS);
            }
        });

        try {
            logger.debug("Fetching tool registry via MCP listTools()");

            ListToolsResult listResult = mcpClient.listTools();

            if (listResult == null || listResult.tools() == null) {
                logger.warn("MCP listTools() returned null");
                cachedTools = new ArrayList<>();
                return;
            }

            List<ToolMetadata> tools = listResult.tools().stream()
                    .map(this::convertToToolMetadata)
                    .toList();

            cachedTools = tools;
            logger.info("Tool registry refreshed successfully. {} tools available.", tools.size());

            if (logger.isDebugEnabled()) {
                tools.forEach(tool -> logger.debug("  - {}: {}", tool.getName(), tool.getDescription()));
            }

        } catch (Exception e) {
            logger.warn("Failed to refresh tool registry from Wanaku — will retry in {} min: {}",
                    REFRESH_INTERVAL_MINUTES, e.getMessage());
        }
    }

    /**
     * Convert an MCP SDK {@link McpSchema.Tool} to our {@link ToolMetadata}.
     *
     * <p>
     * The SDK's {@code inputSchema} is a {@link McpSchema.JsonSchema} record;
     * we convert it to a {@code Map<String, Object>} via Jackson so that downstream
     * serialisation (e.g.&nbsp;to Camunda process variables) remains unchanged.
     * </p>
     */
    @SuppressWarnings("unchecked")
    private ToolMetadata convertToToolMetadata(McpSchema.Tool tool) {
        Map<String, Object> inputSchemaMap = null;
        if (tool.inputSchema() != null) {
            inputSchemaMap = objectMapper.convertValue(tool.inputSchema(), Map.class);
        }
        return new ToolMetadata(tool.name(), tool.description(), inputSchemaMap);
    }

    /**
     * Get the cached list of available tools.
     *
     * @return list of tool metadata (may be empty if refresh failed)
     */
    public List<ToolMetadata> getAvailableTools() {
        return new ArrayList<>(cachedTools);
    }

    /**
     * Check if a tool with the given name exists.
     *
     * @param toolName name of the tool to check
     * @return true if the tool exists, false otherwise
     */
    public boolean toolExists(String toolName) {
        return cachedTools.stream()
                .anyMatch(tool -> tool.getName().equals(toolName));
    }

    /**
     * Get metadata for a specific tool.
     *
     * @param toolName name of the tool
     * @return tool metadata, or null if not found
     */
    public ToolMetadata getTool(String toolName) {
        return cachedTools.stream()
                .filter(tool -> tool.getName().equals(toolName))
                .findFirst()
                .orElse(null);
    }

    /**
     * Shutdown the scheduler when the application stops.
     */
    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
    }
}
