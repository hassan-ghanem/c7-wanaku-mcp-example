package org.cibseven.worker.model;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Represents the decision made by the LLM agent in a single iteration of the agentic loop.
 *
 * <p>The LLM evaluates the user request and conversation context, then decides whether
 * one or more tools need to be executed in parallel, or if a final answer can be
 * provided directly.</p>
 *
 * <h3>JSON contract — tool call(s) needed</h3>
 * <pre>{@code
 * {
 *   "requiresTool": true,
 *   "toolCalls": [
 *     { "callId": "c1", "toolName": "searchDatabase", "toolArgs": { "query": "SELECT ..." } },
 *     { "callId": "c2", "toolName": "getWeather",     "toolArgs": { "city": "Berlin" } }
 *   ],
 *   "finalAnswer": null
 * }
 * }</pre>
 *
 * <p>A single tool call uses the same structure with one entry in {@code toolCalls}.</p>
 *
 * <h3>JSON contract — direct answer</h3>
 * <pre>{@code
 * {
 *   "requiresTool": false,
 *   "toolCalls": null,
 *   "finalAnswer": "The answer is 42."
 * }
 * }</pre>
 */
public class AgentDecision implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Whether tool calls are needed in this iteration. */
    private boolean requiresTool;

    /**
     * List of tool calls to execute.
     * <p>May contain one or more entries. When the entries are independent of each other
     * (no tool's output is needed as another's input), they are executed in parallel by
     * the BPMN Multi-Instance Service Task.</p>
     * <p>{@code null} when {@code requiresTool} is {@code false}.</p>
     */
    private List<ToolCall> toolCalls;

    /**
     * The LLM's final answer to the user.
     * {@code null} when {@code requiresTool} is {@code true}.
     */
    private String finalAnswer;

    public AgentDecision() {
    }

    public AgentDecision(boolean requiresTool, List<ToolCall> toolCalls, String finalAnswer) {
        this.requiresTool = requiresTool;
        this.toolCalls = toolCalls;
        this.finalAnswer = finalAnswer;
    }

    // ── getters / setters ─────────────────────────────────────────────────────

    public boolean isRequiresTool() {
        return requiresTool;
    }

    public void setRequiresTool(boolean requiresTool) {
        this.requiresTool = requiresTool;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<ToolCall> toolCalls) {
        this.toolCalls = toolCalls;
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
                ", toolCalls=" + toolCalls +
                ", finalAnswer='" + finalAnswer + '\'' +
                '}';
    }

    // ── nested ToolCall class ─────────────────────────────────────────────────

    /**
     * A single tool call within an {@link AgentDecision}.
     *
     * <p>Each instance is executed as one iteration of the BPMN Multi-Instance Service Task.
     * The {@code callId} is sanitised by the Java handler before being written to Camunda,
     * so it can safely be used as part of a process variable name
     * ({@code toolResult_<callId>}, {@code toolError_<callId>}).</p>
     */
    public static class ToolCall implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * Unique identifier for this call within the current decision.
         * The Java handler sanitises this value (strips non-alphanumeric characters)
         * before writing it to the Camunda process scope.
         * Example values produced by the LLM: {@code "c1"}, {@code "c2"}.
         */
        private String callId;

        /** Name of the MCP tool to invoke. Must match a tool registered in Wanaku. */
        private String toolName;

        /**
         * Key/value arguments to pass to the tool.
         * Must conform to the tool's {@code inputSchema}. An empty map is acceptable
         * for tools with no required parameters.
         */
        private Map<String, Object> toolArgs;

        public ToolCall() {
        }

        public ToolCall(String callId, String toolName, Map<String, Object> toolArgs) {
            this.callId = callId;
            this.toolName = toolName;
            this.toolArgs = toolArgs;
        }

        // ── getters / setters ─────────────────────────────────────────────────

        public String getCallId() {
            return callId;
        }

        public void setCallId(String callId) {
            this.callId = callId;
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

        @Override
        public String toString() {
            return "ToolCall{" +
                    "callId='" + callId + '\'' +
                    ", toolName='" + toolName + '\'' +
                    ", toolArgs=" + toolArgs +
                    '}';
        }
    }
}
