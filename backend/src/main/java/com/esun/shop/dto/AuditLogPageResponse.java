package com.esun.shop.dto;

import java.util.List;

public record AuditLogPageResponse(List<AuditLogEntry> entries, long total, int page, int size) { }
