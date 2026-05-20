package io.redis.devrel.demos.myjarvis.services;

import java.util.Optional;

public class UserService {

    private final MemoryService memoryService;

    public UserService(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    public Optional<String> getUserName(String userId) {
        return memoryService.getUserName(userId);
    }

    public boolean createUser(String userId, String userName) {
        return memoryService.createUser(userId, userName);
    }
}
