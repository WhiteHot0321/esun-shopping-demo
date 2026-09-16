package com.esun.shop.repository;

import com.esun.shop.llm.IndexableDoc;
import com.esun.shop.model.Faq;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class FaqRepository {
    private static final RowMapper<Faq> FAQ_ROW_MAPPER = (rs, rowNum) -> {
        Faq faq = new Faq();
        faq.setId(rs.getLong("id"));
        faq.setQuestion(rs.getString("question"));
        faq.setAnswer(rs.getString("answer"));
        faq.setCategory(rs.getString("category"));
        faq.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return faq;
    };

    private final JdbcTemplate jdbcTemplate;

    public FaqRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Faq findById(Long id) {
        String sql = "SELECT id, question, answer, category, created_at FROM faq WHERE id = ?";
        List<Faq> list = jdbcTemplate.query(sql, FAQ_ROW_MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<IndexableDoc> findAllForIndexing() {
        String sql = "SELECT id, question, answer, created_at FROM faq";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new IndexableDoc(
                "faq",
                String.valueOf(rs.getLong("id")),
                "Q: " + rs.getString("question") + "\nA: " + rs.getString("answer"),
                rs.getTimestamp("created_at").toLocalDateTime()));
    }
}
