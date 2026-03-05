package org.cibseven.worker.config;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.cibseven.worker.service.KeycloakTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Spring configuration that creates and manages the MCP client connection.
 *
 * <p>
 * Creates an {@link McpSyncClient} using Streamable HTTP transport that
 * connects to the
 * Wanaku MCP Router. The client is initialised on application startup and
 * gracefully closed on shutdown.
 * </p>
 *
 * <p>
 * The entire configuration is conditional on {@code wanaku.enabled=true}.
 * </p>
 */
@Configuration
@ConditionalOnProperty(name = "wanaku.enabled", havingValue = "true", matchIfMissing = true)
public class McpClientConfig implements DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(McpClientConfig.class);

    private McpSyncClient mcpClient;

    /**
     * Creates the MCP sync client.
     *
     * <p>
     * Authentication:
     * <ul>
     * <li>Keycloak OAuth 2.0 (when {@code wanaku.keycloak.enabled=true}) — token is
     * fetched dynamically on every request via
     * {@link HttpClientStreamableHttpTransport.Builder#httpRequestCustomizer}.</li>
     * <li>No authentication — when Keycloak is not configured.</li>
     * </ul>
     *
     * @param wanakuProperties     Wanaku configuration
     * @param keycloakTokenService optional Keycloak token service (present only
     *                             when
     *                             {@code wanaku.keycloak.enabled=true})
     */
    @Bean
    public McpSyncClient mcpSyncClient(WanakuProperties wanakuProperties,
            Optional<KeycloakTokenService> keycloakTokenService) {
        String baseUrl = wanakuProperties.getBaseUrl();
        String mcpEndpoint = wanakuProperties.getMcpEndpoint();

        // Wanaku requires the endpoint path to end with '/' for Streamable HTTP
        if (!mcpEndpoint.endsWith("/")) {
            mcpEndpoint += "/";
        }

        logger.info("Creating MCP client: {}{}", baseUrl, mcpEndpoint);

        var transportBuilder = HttpClientStreamableHttpTransport
                .builder(baseUrl)
                .endpoint(mcpEndpoint);

        // Inject Keycloak Bearer token into every MCP request when enabled
        keycloakTokenService.ifPresent(svc -> transportBuilder
                .httpRequestCustomizer((builder, _method, _endpoint, _body, _context) -> {
                    String token = svc.getCurrentToken();
                    if (token != null && !token.isBlank()) {
                        builder.header("Authorization", "Bearer " + token);
                    } else {
                        logger.warn("Keycloak token not yet available — sending request without auth");
                    }
                }));

        mcpClient = McpClient.sync(transportBuilder.build())
                .requestTimeout(Duration.ofSeconds(wanakuProperties.getTimeoutSeconds()))
                .build();

        // Wait for the initial Keycloak token so the MCP initialize handshake is
        // authenticated
        keycloakTokenService.ifPresent(svc -> {
            if (!svc.awaitInitialToken(60, TimeUnit.SECONDS)) {
                logger.warn("Keycloak token not available after 60s — MCP init may fail");
            }
        });

        try {
            mcpClient.initialize();
            logger.info("MCP client initialised successfully");
        } catch (Exception e) {
            logger.warn("MCP client initialisation failed: {} — will retry on first external task", e.getMessage());
        }

        return mcpClient;
    }

    @Override
    public void destroy() {
        if (mcpClient != null) {
            mcpClient.closeGracefully();
        }
    }
}
