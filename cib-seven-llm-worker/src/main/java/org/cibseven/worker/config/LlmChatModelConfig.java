package org.cibseven.worker.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Spring configuration for creating the LangChain4j {@link ChatModel} bean.
 *
 * <p>
 * Based on the {@link LlmProperties#getProvider()} setting, this configuration
 * creates either an {@link OllamaChatModel}, {@link OpenAiChatModel}, or
 * {@link dev.langchain4j.model.googleai.GoogleAiGeminiChatModel} instance
 * with the appropriate connection parameters and inference settings.
 * </p>
 *
 * <p>
 * The created bean is autowired into the Camel LangChain4j chat component.
 * </p>
 */
@Configuration
public class LlmChatModelConfig {

    private static final Logger logger = LoggerFactory.getLogger(LlmChatModelConfig.class);

    private final LlmProperties llmProperties;

    public LlmChatModelConfig(LlmProperties llmProperties) {
        this.llmProperties = llmProperties;
    }

    /**
     * Creates a {@link ChatModel} bean based on the configured provider.
     *
     * @return configured chat model instance (Ollama, OpenAI, or Gemini)
     * @throws IllegalArgumentException if the provider is not supported
     */
    @Bean
    public ChatModel chatModel() {
        String provider = llmProperties.getProvider().toLowerCase();

        logger.info("Initializing LLM ChatModel with provider: {}, model: {}, baseUrl: {}",
                provider, llmProperties.getModelName(), llmProperties.getBaseUrl());

        return switch (provider) {
            case "ollama" -> createOllamaModel();
            case "openai" -> createOpenAiModel();
            case "gemini" -> createGeminiModel();
            default -> throw new IllegalArgumentException(
                    "Unsupported LLM provider: " + provider + ". Supported: ollama, openai, gemini");
        };
    }

    private ChatModel createOllamaModel() {
        logger.info("Creating Ollama chat model");

        return OllamaChatModel.builder()
                .baseUrl(llmProperties.getBaseUrl())
                .modelName(llmProperties.getModelName())
                .temperature(llmProperties.getTemperature())
                .timeout(Duration.ofSeconds(llmProperties.getTimeoutSeconds()))
                .build();
    }

    private ChatModel createOpenAiModel() {
        logger.info("Creating OpenAI chat model");

        if (llmProperties.getApiKey() == null || llmProperties.getApiKey().trim().isEmpty()) {
            throw new IllegalStateException(
                    "OpenAI API key is required. Set llm.api-key in application.yaml or LLM_API_KEY environment variable.");
        }

        return OpenAiChatModel.builder()
                .baseUrl(llmProperties.getBaseUrl())
                .apiKey(llmProperties.getApiKey())
                .modelName(llmProperties.getModelName())
                .temperature(llmProperties.getTemperature())
                .timeout(Duration.ofSeconds(llmProperties.getTimeoutSeconds()))
                .build();
    }

    private ChatModel createGeminiModel() {
        logger.info("Creating Gemini chat model");

        if (llmProperties.getApiKey() == null || llmProperties.getApiKey().trim().isEmpty()) {
            throw new IllegalStateException(
                    "Gemini API key is required. Set llm.api-key in application.yaml or LLM_API_KEY environment variable.");
        }

        return GoogleAiGeminiChatModel.builder()
                .apiKey(llmProperties.getApiKey())
                .modelName(llmProperties.getModelName())
                .temperature(llmProperties.getTemperature())
                .build();
    }
}
