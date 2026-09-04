package com.nora.rag.service;

import com.nora.rag.api.RetrievalResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Vector search over {@code schema_rag.knowledge_chunk} via pgvector cosine
 * distance. Score is {@code 1 - cosine_distance} so higher is better, matching
 * the frontend RetrievalResult contract.
 */
@Service
public class RetrievalService {

    private static final String SEARCH_SQL = """
            SELECT c.chunk_index, c.content, d.name, d.source,
                   1 - (c.embedding <=> ?::vector) AS score
            FROM schema_rag.knowledge_chunk c
            JOIN schema_rag.knowledge_doc d ON d.id = c.doc_id
            ORDER BY c.embedding <=> ?::vector
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;

    public RetrievalService(JdbcTemplate jdbcTemplate, EmbeddingService embeddingService) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingService = embeddingService;
    }

    /**
     * Semantic search: embeds the query, then returns the top-K chunks by
     * cosine similarity.
     *
     * @param query natural-language query text
     * @param topK  maximum number of results
     * @return scored chunks, best first
     */
    public List<RetrievalResult> search(String query, int topK) {
        float[] vector = embeddingService.embed(query);
        String vectorLiteral = toPgVectorLiteral(vector);

        return jdbcTemplate.query(
                SEARCH_SQL,
                (rs, rowNum) -> new RetrievalResult(
                        rs.getString("name"),
                        rs.getInt("chunk_index"),
                        rs.getDouble("score"),
                        snippet(rs.getString("content")),
                        rs.getString("source")
                ),
                vectorLiteral, vectorLiteral, topK
        );
    }

    /** Renders a float vector as a PGvector literal, e.g. "[0.1,0.2]". */
    static String toPgVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 9 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    /** Trims chunk content to a prompt-friendly snippet. */
    static String snippet(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= 500 ? content : content.substring(0, 500) + "…";
    }
}
