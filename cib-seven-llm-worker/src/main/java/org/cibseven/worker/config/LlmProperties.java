package org.cibseven.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for the LLM external task worker.
 *
 * <p>
 * Binds to the {@code llm} prefix in {@code application.yaml}.
 * Controls which LLM provider is used (ollama, openai, or gemini), the model
 * name,
 * endpoint URL, and inference parameters.
 * </p>
 *
 * <h3>YAML example</h3>
 * 
 * <pre>{@code
 * llm:
 *   enabled: true
 *   provider: ollama          # ollama | openai | gemini
 *   model-name: llama3.2
 *   base-url: http://localhost:11434
 *   api-key: ""
 *   temperature: 0.0
 *   timeout-seconds: 60
 *   topic: llm-decision       # Camunda external task topic
 *   retries: 3                # Camunda external task retry attempts on transient failure
 *   retry-interval-ms: 10000  # Milliseconds to wait between retry attempts (longer for LLM cold-start)
 * }</pre>
 */
@Configuration
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    /** Whether the LLM external task worker is active. */
    private boolean enabled = true;

    /**
     * LLM provider to use. Supported values: {@code ollama}, {@code openai},
     * {@code gemini}.
     * Defaults to {@code ollama} for local development.
     */
    private String provider = "ollama";

    /** Name of the model to use (e.g. {@code llama3.2}, {@code gpt-4o-mini}). */
    private String modelName = "llama3.2";

    /**
     * Base URL of the LLM service.
     * For Ollama: {@code http://localhost:11434}.
     * For OpenAI: {@code https://api.openai.com/v1}.
     */
    private String baseUrl = "http://localhost:11434";

    /** API key required by the provider. Leave empty for Ollama. */
    private String apiKey = "";

    /** Sampling temperature. 0.0 = deterministic, higher = more creative. */
    private double temperature = 0.0;

    /** HTTP request timeout in seconds when calling the LLM provider. */
    private int timeoutSeconds = 120;

    /** Camunda external task topic this worker subscribes to. */
    private String topic = "llm-decision";

    /**
     * Number of Camunda external task retry attempts when a transient failure
     * occurs
     * (e.g. LLM service unavailable, cold-start timeout, HTTP 5xx). The first
     * attempt
     * is not counted; setting this to {@code 3} means one initial attempt plus
     * three
     * retries (four total). Set to {@code 0} to disable retries and fail
     * immediately.
     */
    private int retries = 3;

    /**
     * Milliseconds to wait before Camunda re-delivers the external task after a
     * failure.
     * Defaults to 10 seconds — longer than Kafka/Wanaku to accommodate LLM
     * cold-start
     * and rate-limit back-off scenarios. Only used when {@code retries > 0}.
     */
    private long retryIntervalMs = 10000L;

    // ── getters / setters ─────────────────────────────────────────────────────

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
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
}
