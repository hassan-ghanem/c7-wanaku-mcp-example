package org.cibseven.worker.model;

import java.io.Serializable;
import java.util.Map;

/**
 * Metadata describing a tool available from the Wanaku MCP Router.
 *
 * <p>This model represents a tool fetched from Wanaku's management REST API.
 * It includes the tool's name, description, and input schema (JSON Schema format).</p>
 *
 * <h3>Example</h3>
 * <pre>{@code
 * {
 *   "name": "searchDatabase",
 *   "description": "Searches the production database using SQL queries",
 *   "inputSchema": {
 *     "type": "object",
 *     "properties": {
 *       "query": {
 *         "type": "string",
 *         "description": "SQL query to execute"
 *       },
 *       "database": {
 *         "type": "string",
 *         "description": "Database name (production, staging, dev)"
 *       }
 *     },
 *     "required": ["query", "database"]
 *   }
 * }
 * }</pre>
 */
public class ToolMetadata implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Unique name of the tool. */
    private String name;

    /** Human-readable description of what the tool does. */
    private String description;

    /**
     * JSON Schema describing the tool's input parameters.
     * Typically contains {@code type}, {@code properties}, and {@code required} fields.
     */
    private Map<String, Object> inputSchema;

    public ToolMetadata() {
    }

    public ToolMetadata(String name, String description, Map<String, Object> inputSchema) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    // ── getters / setters ─────────────────────────────────────────────────────

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, Object> getInputSchema() {
        return inputSchema;
    }

    public void setInputSchema(Map<String, Object> inputSchema) {
        this.inputSchema = inputSchema;
    }

    @Override
    public String toString() {
        return "ToolMetadata{" +
                "name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", inputSchema=" + inputSchema +
                '}';
    }
}

