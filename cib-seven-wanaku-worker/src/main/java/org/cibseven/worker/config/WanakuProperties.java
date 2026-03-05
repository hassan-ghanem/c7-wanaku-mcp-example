package org.cibseven.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for the Wanaku external task worker.
 *
 * <p>
 * Binds to the {@code wanaku} prefix in {@code application.yaml}.
 * Controls the Wanaku MCP Router base URL, Streamable HTTP endpoint, timeout,
 * and the
 * Camunda external-task topic this worker subscribes to.
 * </p>
 *
 * <h3>YAML example</h3>
 *
 * <pre>{@code
 * wanaku:
 *   enabled: true
 *   base-url: http://localhost:8080
 *   mcp-endpoint: /mcp/
 *   timeout-seconds: 30
 *   topic: wanaku-tool-execute
 *   tools-fetch-topic: wanaku-tools-fetch
 *   tools-fetch-enabled: true
 *   retries: 3
 *   retry-interval-ms: 5000
 *   keycloak:
 *     enabled: true
 *     client-id: wanaku-mcp-router
 *     client-secret: my-client-secret
 *     token-endpoint:  # optional — auto-resolved from wanaku.base-url via OIDC Discovery when blank
 * }</pre>
 */
@Configuration
@ConfigurationProperties(prefix = "wanaku")
public class WanakuProperties {

    /** Whether the Wanaku external task worker is active. */
    private boolean enabled = true;

    /** Base URL of the Wanaku MCP Router (without trailing slash). */
    private String baseUrl = "http://localhost:8080";

    /**
     * Streamable HTTP endpoint path on the Wanaku MCP Router (e.g. {@code /mcp/}).
     * Passed directly to
     * {@link io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport.Builder#endpoint}.
     */
    private String mcpEndpoint = "/mcp/";

    /** Request timeout in seconds when communicating with the MCP server. */
    private int timeoutSeconds = 30;

    /** Camunda external task topic this worker subscribes to. */
    private String topic = "wanaku-tool-execute";

    /**
     * Camunda external task topic for fetching the list of available tools.
     * The corresponding handler serialises the cached tool registry and sets
     * it as the {@code availableTools} process variable.
     */
    private String toolsFetchTopic = "wanaku-tools-fetch";

    /**
     * Whether the tools-fetch external task subscription is active.
     * Can be set to {@code false} to disable the {@code wanaku-tools-fetch}
     * topic independently of the main {@code wanaku-tool-execute} topic,
     * while {@code enabled} remains {@code true}.
     */
    private boolean toolsFetchEnabled = true;

    /**
     * Number of Camunda external task retry attempts when a transient failure
     * occurs
     * (e.g. network error reaching Wanaku). The first attempt is not counted;
     * setting
     * this to {@code 3} means one initial attempt plus three retries (four total).
     * Set to {@code 0} to disable retries and fail immediately.
     */
    private int retries = 3;

    /**
     * Milliseconds to wait before Camunda re-delivers the external task after a
     * failure.
     * Only used when {@code retries > 0}.
     */
    private long retryIntervalMs = 5000L;

    /**
     * Keycloak OAuth 2.0 configuration for automatic token management.
     * When {@code keycloak.enabled=true}, access tokens are fetched via the
     * client credentials grant and refreshed automatically before expiry.
     */
    private Keycloak keycloak = new Keycloak();

    // ── getters / setters ─────────────────────────────────────────────────────

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getMcpEndpoint() {
        return mcpEndpoint;
    }

    public void setMcpEndpoint(String mcpEndpoint) {
        this.mcpEndpoint = mcpEndpoint;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getToolsFetchTopic() {
        return toolsFetchTopic;
    }

    public void setToolsFetchTopic(String toolsFetchTopic) {
        this.toolsFetchTopic = toolsFetchTopic;
    }

    public boolean isToolsFetchEnabled() {
        return toolsFetchEnabled;
    }

    public void setToolsFetchEnabled(boolean toolsFetchEnabled) {
        this.toolsFetchEnabled = toolsFetchEnabled;
    }

    public int getRetries() {
        return retries;
    }

    public void setRetries(int retries) {
        this.retries = retries;
    }

    public long getRetryIntervalMs() {
        return retryIntervalMs;
    }

    public void setRetryIntervalMs(long retryIntervalMs) {
        this.retryIntervalMs = retryIntervalMs;
    }

    public Keycloak getKeycloak() {
        return keycloak;
    }

    public void setKeycloak(Keycloak keycloak) {
        this.keycloak = keycloak;
    }

    // ── Nested Keycloak configuration ─────────────────────────────────────────

    /**
     * Keycloak OAuth 2.0 client credentials configuration.
     *
     * <p>
     * When {@code enabled=true}, the worker fetches an access token using the
     * client credentials grant ({@code grant_type=client_credentials}) and
     * automatically refreshes it before expiry. The token is injected into every
     * MCP request as {@code Authorization: Bearer <token>}.
     *
     * <p>
     * Auth style mirrors {@code camel-integration-capability}: only
     * {@code clientId}, {@code clientSecret}, and an optional
     * {@code tokenEndpoint} are needed. When {@code token-endpoint} is left
     * blank the endpoint is auto-discovered via OpenID Connect Discovery
     * ({@code GET {wanaku.base-url}/.well-known/openid-configuration}).
     */
    public static class Keycloak {

        /** Whether Keycloak OAuth 2.0 token management is active. */
        private boolean enabled = false;

        /** OAuth 2.0 client ID registered in the Keycloak realm. */
        private String clientId;

        /** OAuth 2.0 client secret for the registered client. */
        private String clientSecret;

        /**
         * Optional explicit token endpoint URL (e.g.
         * {@code http://keycloak:8080/realms/wanaku/protocol/openid-connect/token}).
         * When blank, the endpoint is auto-resolved from {@code wanaku.base-url}
         * via OpenID Connect Discovery — the same behaviour as
         * {@code TokenEndpoint.autoResolve()} in the Wanaku SDK.
         */
        private String tokenEndpoint;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getTokenEndpoint() {
            return tokenEndpoint;
        }

        public void setTokenEndpoint(String tokenEndpoint) {
            this.tokenEndpoint = tokenEndpoint;
        }
    }
}
