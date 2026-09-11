package com.nora.agent.dto;

/**
 * SSE {@code sources} event payload entry, matching the frontend Citation
 * contract (types/index.ts).
 *
 * @param docId      knowledge_doc id (identity for dedup; names are not unique)
 * @param docName    document name
 * @param source     storage source (file/database/repo/…)
 * @param chunkIndex chunk position within the document
 * @param score      retrieval similarity score
 * @param snippet    text excerpt of the cited chunk
 */
public record CitationDto(
        Long docId,
        String docName,
        String source,
        int chunkIndex,
        double score,
        String snippet
) {
}
