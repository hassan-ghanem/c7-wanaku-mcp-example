package org.cibseven.worker.model;

import java.io.Serializable;
import java.util.Map;

/**
 * Represents the decision made by the LLM agent.
 *
 * <p>The LLM evaluates the user request and conversation context, then decides whether
 * a tool needs to be executed or if a final answer can be provided.</p>
 *
 * <h3>JSON contract example</h3>
 * <pre>{@code
 * {
 *   "requiresTool": true,
 *   "toolName": "searchDatabase",
 *   "toolArgs": {
 *     "query": "SELECT * FROM users WHERE id = 123",
 *     "database": "production"
 *   },
 *   "finalAnswer": null
 * }
 * }</pre>
 *
 * <p>Or when no tool is needed:</p>
 * <pre>{@code
 * {
 *   "requiresTool": false,
 *   "toolName": null,
 *   "toolArgs": null,
 *   "finalAnswer": "The user with ID 123 is John Doe, registered on 2024-01-15."
 * }
 * }</pre>
 */
public class AgentDecision implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Whether a tool needs to be executed. */
    private boolean requiresTool;

    /** Name of the tool to execute (null if {@code requiresTool} is false). */
    private String toolName;

    /** Arguments to pass to the tool (null if {@code requiresTool} is false). */
    private Map<String, Object> toolArgs;

    /** Final answer to return to the user (null if {@code requiresTool} is true). */
    private String finalAnswer;

    public AgentDecision() {
    }

    public AgentDecision(boolean requiresTool, String toolName, Map<String, Object> toolArgs, String finalAnswer) {
        this.requiresTool = requiresTool;
        this.toolName = toolName;
        this.toolArgs = toolArgs;
        this.finalAnswer = finalAnswer;
    }

    // ── getters / setters ─────────────────────────────────────────────────────

    public boolean isRequiresTool() {
        return requiresTool;
    }

    public void setRequiresTool(boolean requiresTool) {
        this.requiresTool = requiresTool;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public Map<String, Object> getToolArgs() {
        return toolArgs;
    }

    public void setToolArgs(Map<String, Object> toolArgs) {
        this.toolArgs = toolArgs;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public void setFinalAnswer(String finalAnswer) {
        this.finalAnswer = finalAnswer;
    }

    @Override
    public String toString() {
        return "AgentDecision{" +
                "requiresTool=" + requiresTool +
                ", toolName='" + toolName + '\'' +
                ", toolArgs=" + toolArgs +
                ", finalAnswer='" + finalAnswer + '\'' +
                '}';
    }
}

