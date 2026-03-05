package org.cibseven.worker.model;

import dev.langchain4j.data.message.ChatMessage;
import java.io.Serializable;
import java.util.List;

/**
 * Request model for calling the LLM via Apache Camel langchain4j-chat component.
 *
 * <p>Contains the list of chat messages (system prompt, user messages, assistant responses)
 * that form the conversation context sent to the LLM.</p>
 *
 * <p>The Camel route expects a {@code List<ChatMessage>} as the message body when using
 * the {@code CHAT_MULTIPLE_MESSAGES} operation.</p>
 */
public class LlmChatRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * List of chat messages forming the conversation.
     * Includes system prompts, user messages, and assistant responses.
     */
    private List<ChatMessage> messages;

    public LlmChatRequest() {
    }

    public LlmChatRequest(List<ChatMessage> messages) {
        this.messages = messages;
    }

    // ── getters / setters ─────────────────────────────────────────────────────

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<ChatMessage> messages) {
        this.messages = messages;
    }

    @Override
    public String toString() {
        return "LlmChatRequest{" +
                "messages=" + messages +
                '}';
    }
}

