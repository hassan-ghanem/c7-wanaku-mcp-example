package org.cibseven.worker.route;

import org.apache.camel.builder.RouteBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Apache Camel route for calling the LLM using the langchain4j-chat component.
 *
 * <p>
 * This route provides a {@code direct:callLlm} endpoint that accepts a
 * {@code List<ChatMessage>} as input and returns the LLM's response as a
 * String.
 * </p>
 *
 * <p>
 * The route uses the {@code CHAT_MULTIPLE_MESSAGES} operation to send the
 * entire conversation history (system prompt + user messages + assistant
 * responses)
 * to the LLM for context-aware decision making.
 * </p>
 *
 * <h3>Usage</h3>
 * 
 * <pre>{@code
 * List<ChatMessage> messages = new ArrayList<>();
 * messages.add(new SystemMessage("You are a helpful assistant..."));
 * messages.add(new UserMessage("What is the capital of France?"));
 *
 * String response = producerTemplate.requestBody("direct:callLlm", messages, String.class);
 * }</pre>
 */
@Component
public class LlmRoute extends RouteBuilder {

    private static final Logger logger = LoggerFactory.getLogger(LlmRoute.class);

    @Override
    public void configure() throws Exception {

        // Route for calling the LLM with multiple messages (conversation history)
        from("direct:callLlm")
                .routeId("llm-chat-route")
                .log("Calling LLM with ${body.size()} messages")
                .to("langchain4j-chat:llmChat?chatModel=#chatModel&chatOperation=CHAT_MULTIPLE_MESSAGES")
                .log("LLM response received: ${body}")
                .onException(Exception.class)
                .handled(true)
                .process(exchange -> {
                    Exception exception = exchange.getProperty(org.apache.camel.Exchange.EXCEPTION_CAUGHT,
                            Exception.class);
                    logger.error("Error calling LLM", exception);
                    exchange.getIn().setBody("ERROR: " + exception.getMessage());
                })
                .end();
    }
}
