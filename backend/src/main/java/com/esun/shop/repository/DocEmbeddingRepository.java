package com.esun.shop.repository;

import com.esun.shop.llm.EmbeddingException;
import com.esun.shop.model.DocEmbedding;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class DocEmbeddingRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DocEmbeddingRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<DocEmbedding> findAll() {
        String sql = "SELECT id, source_type, source_id, content, embedding, updated_at FROM doc_embedding";
        return jdbcTemplate.query(sql, this::mapRow);
    }

    public Map<String, LocalDateTime> findUpdatedAtBySourceType(String sourceType) {
        String sql = "SELECT source_id, updated_at FROM doc_embedding WHERE source_type = ?";
        Map<String, LocalDateTime> result = new HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            result.put(rs.getString("source_id"), rs.getTimestamp("updated_at").toLocalDateTime());
        }, sourceType);
        return result;
    }

    public void upsert(String sourceType, String sourceId, String content, float[] embedding) {
        String json;
        try {
            json = objectMapper.writeValueAsString(embedding);
        } catch (JsonProcessingException ex) {
            throw new EmbeddingException("Embedding 序列化失敗", ex);
        }
        String sql = "INSERT INTO doc_embedding (source_type, source_id, content, embedding) " +
                "VALUES (?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE content = VALUES(content), embedding = VALUES(embedding), updated_at = CURRENT_TIMESTAMP";
        jdbcTemplate.update(sql, sourceType, sourceId, content, json);
    }

    public void delete(String sourceType, String sourceId) {
        jdbcTemplate.update("DELETE FROM doc_embedding WHERE source_type = ? AND source_id = ?", sourceType, sourceId);
    }

    private DocEmbedding mapRow(ResultSet rs, int rowNum) throws SQLException {
        DocEmbedding doc = new DocEmbedding();
        doc.setId(rs.getLong("id"));
        doc.setSourceType(rs.getString("source_type"));
        doc.setSourceId(rs.getString("source_id"));
        doc.setContent(rs.getString("content"));
        try {
            doc.setEmbedding(objectMapper.readValue(rs.getString("embedding"), float[].class));
        } catch (JsonProcessingException ex) {
            throw new EmbeddingException("Embedding 反序列化失敗", ex);
        }
        doc.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
        return doc;
    }
}
