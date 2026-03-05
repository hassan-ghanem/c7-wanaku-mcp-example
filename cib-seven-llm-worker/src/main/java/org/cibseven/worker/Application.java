package org.cibseven.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main application class for CIB Seven LLM Worker.
 * 
 * This application provides LLM-based decision making for Camunda BPMN processes.
 * It subscribes to the "llm-decision" external task topic and uses LangChain4j
 * to interact with Large Language Models (Ollama or OpenAI) via Apache Camel.
 * 
 * The LLM evaluates context, selects tools, generates arguments, or produces
 * final answers based on the process state.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}

