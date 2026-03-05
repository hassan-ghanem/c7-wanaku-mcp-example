package org.cibseven.worker.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.cibseven.worker.config.WanakuProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service that manages Keycloak OAuth 2.0 access tokens for authenticating
 * requests to the Wanaku MCP Router.
 *
 * <p>
 * Auth style mirrors {@code camel-integration-capability}: only
 * {@code clientId}, {@code clientSecret}, and an optional
 * {@code tokenEndpoint} are needed. When {@code wanaku.keycloak.token-endpoint}
 * is blank the token endpoint is auto-discovered via OpenID Connect Discovery:
 * {@code GET {wanaku.base-url}/.well-known/openid-configuration} &rarr;
 * {@code token_endpoint} field. This replicates the behaviour of
 * {@code TokenEndpoint.autoResolve(baseUrl, tokenEndpoint)} from the Wanaku
 * SDK.
 *
 * <p>
 * On application startup (via {@link PostConstruct}) this service fetches an
 * initial access token using the <em>client credentials</em> grant
 * ({@code grant_type=client_credentials}). A background thread then proactively
 * refreshes the token at 80&nbsp;% of its lifetime so that no request ever uses
 * an expired credential.
 *
 * <p>
 * This bean is only created when {@code wanaku.keycloak.enabled=true}.
 */
@Service
@ConditionalOnProperty(name = "wanaku.keycloak.enabled", havingValue = "true")
public class KeycloakTokenService {

    private static final Logger logger = LoggerFactory.getLogger(KeycloakTokenService.class);

    /**
     * Fraction of the token lifetime after which a proactive refresh is triggered.
     */
    private static final double REFRESH_THRESHOLD = 0.80;

    /** Delay (seconds) before retrying after a failed token fetch. */
    private static final long RETRY_DELAY_SECONDS = 30L;

    private final WanakuProperties wanakuProperties;
    private final RestTemplate restTemplate = new RestTemplate();

    /** Holds the current valid access token; {@code null} until the first fetch. */
    private final AtomicReference<String> currentToken = new AtomicReference<>();

    /**
     * Released as soon as the first token is stored (or a permanent failure is
     * detected). Allows other components to block until authentication is ready.
     */
    private final CountDownLatch initialTokenLatch = new CountDownLatch(1);

    /** Single-threaded scheduler for proactive token refresh and retries. */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "keycloak-token-refresh");
        t.setDaemon(true);
        return t;
    });

    public KeycloakTokenService(WanakuProperties wanakuProperties) {
        this.wanakuProperties = wanakuProperties;
    }

    /**
     * Fetch the initial access token. Called by Spring after bean construction and
     * before {@link org.cibseven.worker.config.McpClientConfig} initialises the MCP
     * client (guaranteed by Spring's dependency-injection ordering).
     */
    @PostConstruct
    public void fetchInitialToken() {
        fetchToken();
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
    }

    /**
     * Return the current access token.
     *
     * @return the access token string, or {@code null} if no token has been fetched
     *         yet
     */
    public String getCurrentToken() {
        return currentToken.get();
    }

    /**
     * Block until the first token has been fetched (or {@code timeout} elapses).
     *
     * @param timeout  maximum time to wait
     * @param timeUnit unit of {@code timeout}
     * @return {@code true} if a token is available, {@code false} if the wait timed
     *         out
     */
    public boolean awaitInitialToken(long timeout, TimeUnit timeUnit) {
        try {
            return initialTokenLatch.await(timeout, timeUnit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ── Internal token-fetch logic ────────────────────────────────────────────

    private void fetchToken() {
        WanakuProperties.Keycloak cfg = wanakuProperties.getKeycloak();
        String tokenUrl = resolveTokenUrl(cfg);
        if (tokenUrl == null) {
            logger.warn("Token endpoint could not be resolved — scheduling retry in {}s", RETRY_DELAY_SECONDS);
            scheduleRetry();
            return;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", cfg.getClientId());
            form.add("client_secret", cfg.getClientSecret());

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(form, headers);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(tokenUrl, request, Map.class);

            if (response == null) {
                logger.warn("Keycloak token response was null — scheduling retry in {}s", RETRY_DELAY_SECONDS);
                scheduleRetry();
                return;
            }

            String accessToken = (String) response.get("access_token");
            Number expiresIn = (Number) response.get("expires_in");

            if (accessToken == null || accessToken.isBlank()) {
                logger.warn("Keycloak token response contained no access_token — scheduling retry in {}s",
                        RETRY_DELAY_SECONDS);
                scheduleRetry();
                return;
            }

            currentToken.set(accessToken);
            initialTokenLatch.countDown(); // unblock any waiters

            long expiresInSeconds = expiresIn != null ? expiresIn.longValue() : 300L;
            long refreshInSeconds = (long) (expiresInSeconds * REFRESH_THRESHOLD);
            logger.info("Keycloak access token acquired successfully. Expires in {}s; next refresh in {}s.",
                    expiresInSeconds, refreshInSeconds);

            scheduleRefresh(refreshInSeconds);

        } catch (Exception e) {
            logger.warn("Failed to fetch Keycloak access token from {}: {} — scheduling retry in {}s",
                    tokenUrl, e.getMessage(), RETRY_DELAY_SECONDS);
            scheduleRetry();
        }
    }

    private void scheduleRefresh(long delaySeconds) {
        scheduler.schedule(this::fetchToken, delaySeconds, TimeUnit.SECONDS);
    }

    private void scheduleRetry() {
        scheduler.schedule(this::fetchToken, RETRY_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    // ── Token endpoint resolution (mirrors TokenEndpoint.autoResolve()) ──────

    /**
     * Resolves the token endpoint URL using the same two-step logic as
     * {@code TokenEndpoint.autoResolve(baseUrl, tokenEndpoint)} in the Wanaku SDK:
     * <ol>
     * <li>If {@code wanaku.keycloak.token-endpoint} is set → use it as-is.</li>
     * <li>Otherwise → perform OpenID Connect Discovery against
     * {@code wanaku.base-url} and extract the {@code token_endpoint}.</li>
     * </ol>
     */
    private String resolveTokenUrl(WanakuProperties.Keycloak cfg) {
        String explicit = cfg.getTokenEndpoint();
        if (explicit != null && !explicit.isBlank()) {
            logger.debug("Using explicit token endpoint: {}", explicit);
            return explicit;
        }
        // Auto-resolve from the Wanaku base URL via OIDC Discovery
        return discoverTokenEndpoint(wanakuProperties.getBaseUrl());
    }

    /**
     * Sends a GET to {@code {baseUrl}/.well-known/openid-configuration} and
     * returns the {@code token_endpoint} field, or {@code null} if discovery
     * fails or the document does not contain a {@code token_endpoint}.
     *
     * <p>
     * When {@code null} is returned, the caller schedules a retry rather
     * than crashing the application.
     */
    @SuppressWarnings("unchecked")
    private String discoverTokenEndpoint(String baseUrl) {
        String discoveryUrl = baseUrl.replaceAll("/+$", "") + "/.well-known/openid-configuration";
        logger.debug("Auto-resolving token endpoint via OIDC Discovery: {}", discoveryUrl);
        try {
            Map<String, Object> oidcConfig = restTemplate.getForObject(discoveryUrl, Map.class);
            if (oidcConfig != null) {
                String tokenEndpoint = (String) oidcConfig.get("token_endpoint");
                if (tokenEndpoint != null && !tokenEndpoint.isBlank()) {
                    logger.debug("Discovered token endpoint: {}", tokenEndpoint);
                    return tokenEndpoint;
                }
            }
        } catch (Exception e) {
            logger.warn("OIDC Discovery failed for {}: {}", discoveryUrl, e.getMessage());
        }
        logger.warn("OIDC Discovery did not return a token_endpoint from '{}'. "
                + "Set 'wanaku.keycloak.token-endpoint' explicitly in application.yaml to skip discovery.",
                baseUrl);
        return null;
    }
}
