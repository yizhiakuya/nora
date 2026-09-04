package com.nora.agent.api;

/**
 * A retrieval citation attached to an agent answer.
 *
 * <p>Produced by the RAG tool during the ReAct loop (architecture-v2.md
 * section 4.6): the agent cites which document chunk grounded its reply,
 * so the frontend can render provenance next to the message.</p>
 *
 * @param docName    human-readable document name (e.g. {@code arch-notes.md})
 * @param source     storage location or URI of the document
 * @param chunkIndex 0-based index of the cited chunk within the document
 * @param score      retrieval similarity score in [0, 1]
 * @param snippet    short excerpt of the cited chunk
 */
public record Citation(
        String docName,
        String source,
        int chunkIndex,
        double score,
        String snippet) {
}
