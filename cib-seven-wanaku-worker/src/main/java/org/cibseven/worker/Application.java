package org.cibseven.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main application class for CIB Seven Wanaku Worker.
 * 
 * This application provides MCP (Model Context Protocol) tool execution for
 * Camunda BPMN processes.
 * It subscribes to the "wanaku-tool-execute" external task topic and executes
 * tools via the
 * Wanaku MCP Router using the MCP Java SDK's Streamable HTTP transport.
 * 
 * The worker receives tool names and arguments from the LLM decision engine,
 * executes the tools
 * via the MCP protocol, and returns the results to the Camunda process.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
