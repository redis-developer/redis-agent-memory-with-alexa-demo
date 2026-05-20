package io.redis.devrel.demos.myjarvis.tools;

import dev.langchain4j.agent.tool.Tool;
import io.redis.devrel.demos.myjarvis.services.MemoryService;

public class AgentMemoryServerTool {

    private final MemoryService memoryService;

    public AgentMemoryServerTool(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Tool("Check the agent memory server health")
    public boolean checkAgentMemoryServerHealth() {
        return memoryService.checkHealth();
    }
}
