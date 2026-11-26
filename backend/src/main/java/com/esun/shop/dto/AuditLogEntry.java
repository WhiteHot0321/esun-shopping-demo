package com.esun.shop.dto;

import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;

public record AuditLogEntry(long id, String actor, String actorRole, String action, String targetType,
                            String targetId, JsonNode before, JsonNode after, LocalDateTime createdAt) { }
