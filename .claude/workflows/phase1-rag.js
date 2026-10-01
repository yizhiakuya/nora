export const meta = {
  name: 'phase1-rag',
  description: 'Implement RAG pipeline: file-service (upload+Tika) and rag-service (chunk+embed+pgvector search), gateway routes, frontend switch',
  phases: [
    { title: 'Implement', detail: 'file-service and rag-service in parallel' },
    { title: 'Integrate', detail: 'build + live smoke test with real PG and Jina embeddings' },
    { title: 'Frontend', detail: 'switch ragService.ts to real API behind USE_BACKEND' },
    { title: 'Verify', detail: 'end-to-end: upload → index → search via browser UI' },
  ],
}

const CTX = `Project: Nora personal workbench backend, D:\\claude\\Nora\\nora-api (Windows Git Bash; forward slashes in bash).
Phase 0 skeleton is committed and green: 16 Maven modules under com.nora:nora-parent:0.1.0-SNAPSHOT, mvn install passes with 27 tests.
Design source of truth: docs/architecture-v2.md (sections 4, 5, 7, 12) and docs/nora-api-initiation-2026-09-04.md (sections 4, 5 — table DDL + REST contract). READ THE RELEVANT SECTIONS FIRST.
Infrastructure running in Docker (dev-basic profile): postgres pgvector pg16 at localhost:5432 (db nora/user nora/pass nora, vector ext already created), redis :6379, nacos :8848. Use "docker exec nora-postgres psql -U nora -d nora -c ..." to inspect DB.
Embedding provider: Jina AI, OpenAI-compatible at https://api.jina.ai/v1 (key in D:\\claude\\Nora\\nora-api\\.env.local as NORA_EMBEDDING_API_KEY — read it when needed, never commit, never echo full key). Model jina-embeddings-v3, max dimension 1024, supports batch input. Verified working with curl.
Existing code to study before writing (match style exactly):
- services/env-service/** (controller/service/yml pattern), services/file-service/** (current placeholder), common/nora-common (ApiResponse envelope: ok(data)/error(code,message))
- api/file-api (FileItem record: id,name,mimeType,sizeBytes,indexed,createdAt / FilePreview: fileId,type,textContent / FileService interface / FileUploadRequest)
- api/rag-api (RagService: search(SearchRequest), getIndexStats(); RetrievalResult: docName,chunkIndex,score,snippet,source; IndexStatistics: docCount,chunkCount,embeddingModel,indexedAt)
Parent pom manages: spring-boot 3.3.4 BOMs (spring-cloud 2023.0.3, spring-cloud-alibaba 2023.0.1.2, langchain4j-bom 1.0.1), tika-core + tika-parsers-standard-package 2.9.2 (just added), dubbo 3.3.0. MyBatis-Flex is NOT yet managed — if you use it, add dev.langchain4j:langchain4j-pgvector:1.0.1 (BOM-managed) instead, or plain spring-boot-starter-jdbc + hand SQL (simpler, preferred for Phase 1).
REST envelope: every endpoint returns ApiResponse.ok(data) — frontend client.ts unwraps {code:0,data,message}. Errors: BusinessException + GlobalExceptionHandler (nora-common).
IMPORTANT boundaries:
- file-service owns schema_file (file_item table). rag-service owns schema_rag (knowledge_doc, knowledge_chunk).
- NO cross-schema JOIN. rag-service fetches file text via file-service (REST through Nacos or direct configured URL for Phase 1 — simplest: RestTemplate/WebClient to http://file-service via Nacos discovery lb URL, or plain configurable base URL http://localhost:8081; choose configurable base URL with Nacos fallback documented, keep it simple).
- Inter-service event (file.uploaded) for Phase 1: SIMPLEST viable = synchronous REST trigger: after upload, file-service POSTs {fileId} to rag-service /api/rag/index (fire-and-forget async via @Async). The v2 design's RocketMQ outbox comes later (Phase 3); document this deviation in code comment.
- Keep Tika extraction inside file-service (it also serves /api/files/{id}/preview).

FRONTEND CONTRACT (must match exactly — frontend client.ts already live):
- POST /api/files/upload  multipart/form-data, field "file" → ApiResponse<FileItem JSON: {id,name,mimeType,sizeBytes,indexed,createdAt}> (camelCase)
- GET  /api/files?ids=1,2 (optional) → ApiResponse<FileItem[]>  (list all if no ids)
- DELETE /api/files?ids=1,2 → ApiResponse<Void>
- GET  /api/files/{id}/preview → ApiResponse<{fileId,type,textContent}>
- POST /api/files/{id}/index → ApiResponse<FileItem> (triggers async indexing, returns updated item)
- GET  /api/rag/docs → ApiResponse<KnowledgeDoc JSON: {id,name,source,chunks,status,size,updatedAt,quality}> (source values: file|database|repo|environment|chat; status: indexed|processing|failed)
- GET  /api/rag/index/stats → ApiResponse<{totalDocs,totalChunks,vectorDim,model,lastUpdate,pendingDocs,vectorReady,graphReady}> (EXACT field names — frontend IndexStatus.tsx consumes these)
- POST /api/rag/search {query,topK} → ApiResponse<[{docName,source,chunkIndex,score,snippet}]> (source is KnowledgeSource string, NOT file path)
- POST /api/rag/citations {query,topK} → ApiResponse<[{docName,source,chunkIndex,score,snippet}]>
- REST routes go through gateway :8080 (add routes file + rag in gateway application.yml, same pattern as existing agent route).
`

phase('Implement')

const impl = await parallel([
  () => agent(`${CTX}

YOUR TASK: implement file-service fully (services/file-service).

Scope:
1. pom.xml: add tika-core + tika-parsers-standard-package (versions BOM-managed in parent), spring-boot-starter-jdbc, org.postgresql:postgresql (runtime, version from spring-boot BOM).
2. Flyway: add org.flywaydb:flyway-core + flyway-database-postgresql (BOM-managed), V1__init.sql creating schema_file.file_item exactly per v1 doc section 4.1 (id BIGSERIAL PK, name VARCHAR(255) NOT NULL, file_path VARCHAR(500), mime_type VARCHAR(100), size_bytes BIGINT, indexed BOOLEAN DEFAULT false, created_at TIMESTAMP DEFAULT now()). Set flyway schemas=schema_file, default-schema=schema_file, create-schemas=true in application.yml.
3. FileStorageService: stores uploaded bytes under configurable dir (nora.file.storage-dir, default ./data/files/{id}{ext}), extracts text via Tika (Tika facade, detect mime), persists file_item row via JdbcTemplate.
4. TextExtractionService: Tika-based; returns extracted plain text (cap at 500_000 chars). Unit-testable: method extract(InputStream, filename) — test with a small txt and a tiny docx (build the docx in-test via POI XWPFDocument if trivial, else test txt + a fake binary → expect graceful empty/exception per design).
5. FileController (REST, all ApiResponse-wrapped):
   - POST /api/files/upload (MultipartFile file)
   - GET /api/files (optional ids param)
   - DELETE /api/files (ids param; also delete stored bytes)
   - GET /api/files/{id}/preview → {fileId, type:"text", textContent} via Tika extraction (404 via BusinessException if missing)
   - POST /api/files/{id}/index → triggers rag-service: fire-and-forget async POST {fileId} to configured base URL (nora.rag.base-url, default http://localhost:8082) endpoint /api/rag/index, catch+log all errors (never fail the caller); returns current FileItem (indexed=false, rag will flip it later — for Phase 1 also expose that rag-service will call back POST /api/files/{id}/indexed to flip the flag; implement that endpoint too: POST /api/files/{id}/indexed → ApiResponse<FileItem> sets indexed=true).
6. application.yml: port 8081, datasource jdbc:postgresql://localhost:5432/nora (user/pass nora), flyway config, nacos discovery stays, nora.file.storage-dir, nora.rag.base-url.
7. Dubbo provider: SKIP for Phase 1 (keep REST-only inter-service; Dubbo comes with Phase 2 agent work). Implement FileService (api interface) methods as a plain @Service internals reused by controller — do NOT register Dubbo.
8. Tests: JdbcTemplate-free unit tests for extraction + controller layer with mocked service (Mockito, spring-boot-starter-test provides). NO @SpringBootTest (no DB in CI). Plain JUnit.

Build check: cd D:\\claude\\Nora\\nora-api && mvn -B -pl services/file-service -am install -DskipTests=false 2>&1 | tail -20. Fix until green.
Report: endpoints implemented, files touched, test count, deviations. Under 250 words.`,
    { label: 'impl:file-service', phase: 'Implement', effort: 'high' }),

  () => agent(`${CTX}

YOUR TASK: implement rag-service fully (services/rag-service).

Scope:
1. pom.xml: add spring-boot-starter-jdbc, postgresql runtime, flyway-core + flyway-database-postgresql, dev.langchain4j:langchain4j-open-ai (BOM-managed 1.0.1; provides OpenAiEmbeddingModel with baseUrl() override → point at Jina).
2. Flyway V1__init.sql in schema_rag: knowledge_doc (id BIGSERIAL PK, name VARCHAR(255) NOT NULL, source VARCHAR(20) NOT NULL, chunks INTEGER DEFAULT 0, status VARCHAR(20) DEFAULT 'processing', size VARCHAR(20), quality INTEGER DEFAULT 0, updated_at TIMESTAMP DEFAULT now()) and knowledge_chunk (id BIGSERIAL PK, doc_id BIGINT REFERENCES schema_rag.knowledge_doc(id) ON DELETE CASCADE, chunk_index INTEGER NOT NULL, content TEXT, embedding vector(1024), token_count INTEGER, created_at TIMESTAMP DEFAULT now()) + HNSW index (vector_cosine_ops). flyway schemas=schema_rag, default-schema=schema_rag, create-schemas=true. NOTE: dimension 1024 (Jina v3 max), NOT 1536 — v1 doc says 1536 for OpenAI; document the deviation in migration comment.
3. EmbeddingService: wraps OpenAiEmbeddingModel.builder().baseUrl(configured).apiKey(env NORA_EMBEDDING_API_KEY or property nora.embedding.api-key).modelName(jina-embeddings-v3).dimensions(1024).build(). Config record via @ConfigurationProperties(prefix="nora.embedding"): api-key (default ""), base-url (default https://api.jina.ai/v1), model (default jina-embeddings-v3), dimensions (default 1024). When api-key is blank → embedding methods throw BusinessException("embedding not configured") — endpoints degrade gracefully (docs list still works, search returns empty or 503-style business error).
4. ChunkingService: split text into ~500-token chunks with 50 overlap — implement as character-based approximation (2000 chars per chunk, 200 char overlap, tuned for zh+en mixed). Unit tests: short text→1 chunk; long text→N chunks with overlap; empty→0.
5. RetrievalService: search(query, topK) → embed query (task=query embedding), SQL: SELECT c.chunk_index, c.content, d.name, d.source, 1 - (c.embedding <=> ?::vector) AS score FROM schema_rag.knowledge_chunk c JOIN schema_rag.knowledge_doc d ON d.id=c.doc_id ORDER BY c.embedding <=> ?::vector LIMIT ? — pass embedding as PGvector literal string "[0.1,0.2,...]". Map to RetrievalResult records. Score = 1 - cosine_distance (higher better, matches frontend expectation).
6. RagController (REST, ApiResponse-wrapped):
   - POST /api/rag/index {fileId} → fetch file text: GET {nora.file-service.base-url}/api/files/{fileId}/preview (configurable, default http://localhost:8081) using RestClient; then chunk+embed+persist knowledge_doc(status=processing→indexed)+knowledge_chunk rows; after success POST callback /api/files/{fileId}/indexed to file-service; on embed failure set status=failed. Runs synchronously in this endpoint for Phase 1 (file-service calls it async). Returns KnowledgeDoc JSON.
   - GET /api/rag/docs → all knowledge_doc rows mapped to {id,name,source,chunks,status,size,updatedAt,quality} (updatedAt as "yyyy-MM-dd HH:mm" string).
   - GET /api/rag/index/stats → {totalDocs,totalChunks,vectorDim,model,lastUpdate,pendingDocs,vectorReady,graphReady} computed from DB (vectorDim=1024 or configured dims; model=configured model name; lastUpdate=max(updated_at) formatted or "—"; pendingDocs=count status=processing; vectorReady=chunkCount>0; graphReady=false).
   - POST /api/rag/search {query,topK} → RetrievalResult[] with source = doc.source string.
   - POST /api/rag/citations {query,topK} → same as search (alias endpoint).
7. application.yml: port 8082, datasource, flyway, nacos, nora.embedding.*, nora.file-service.base-url.
8. Tests: ChunkingService unit tests (real logic), RetrievalService/Controller with mocked JdbcTemplate+embedding (Mockito). NO @SpringBootTest.

Build check: mvn -B -pl services/rag-service -am install 2>&1 | tail -20. Fix until green.
Report: endpoints, files, tests, deviations (esp. dimension 1024 + sync indexing). Under 250 words.`,
    { label: 'impl:rag-service', phase: 'Implement', effort: 'high' }),
])

log(`Implement done: ${impl.filter(Boolean).length}/2`)

phase('Integrate')
const integration = await agent(`${CTX}

file-service and rag-service are now implemented (previous phase). Gateway routes may still be missing. Your job: full live integration verification + fixes.

1. Add gateway routes if absent (services/gateway-service/src/main/resources/application.yml): file → lb://file-service Path=/api/files/**, rag → lb://rag-service Path=/api/rag/**. Rebuild gateway.
2. cd D:\\claude\\Nora\\nora-api && mvn -B install 2>&1 | tail -15 — all 16 modules green.
3. Load the embedding key into shell: export NORA_EMBEDDING_API_KEY=$(grep NORA_EMBEDDING_API_KEY .env.local | cut -d= -f2)
4. Start stack (background, logs to files in /tmp or nora-api/*.log then clean up): file-service (8081), rag-service (8082), gateway (8080). Wait for nacos registration (sleep 20, check http://localhost:8080/actuator/health and services' /actuator/health).
5. LIVE TEST via gateway :8080:
   a. echo "Nora 是一个个人工作台。pgvector 提供向量检索。Redis 用作缓存。部署手册见 docs。" > /tmp/test-doc.txt (+ a second English file about "PostgreSQL tuning guide: shared_buffers, work_mem, autovacuum")
   b. curl -F "file=@/tmp/test-doc.txt" http://localhost:8080/api/files/upload → expect code 0, id, indexed=false
   c. curl http://localhost:8080/api/files → list contains it
   d. curl http://localhost:8080/api/files/{id}/preview → textContent non-empty
   e. curl -X POST http://localhost:8080/api/files/{id}/index → accepted
   f. sleep for indexing (embeddings take seconds; poll GET /api/rag/docs until status=indexed, max 30s)
   g. curl -X POST http://localhost:8080/api/rag/search -d '{"query":"向量检索怎么实现","topK":3}' → expect hits with docName test-doc.txt, score>0.3, snippet real content
   h. curl http://localhost:8080/api/rag/index/stats → totalDocs>=1, totalChunks>=1, model=jina-embeddings-v3, vectorReady=true
   i. upload + index the English tuning file, search "how to tune autovacuum" → should hit the English doc (cross-language check optional)
   j. curl -X DELETE http://localhost:8080/api/files?ids={id} → then GET /api/rag/docs still shows doc (rag doc deletion cascade is fine to skip Phase 1 — but if you implement delete propagation via rag internal endpoint, even better; at minimum the file row and bytes are gone)
6. Fix ANY failure: NPEs, JSON shape mismatches (camelCase!), flyway schema issues, pgvector literal formatting, Jina API quirks (model name, batch), gateway 503s. Iterate until the full chain passes.
7. Kill java processes at the end (taskkill //F //IM java.exe). Clean log files.
8. DB state check: docker exec nora-postgres psql -U nora -d nora -c "SELECT count(*) FROM schema_rag.knowledge_chunk" — verify rows.

Report: each test a-g result (PASS/FAIL + fix applied), final mvn install status, deviations. Under 400 words.`,
  { label: 'integrate', phase: 'Integrate', effort: 'high' })

phase('Frontend')
const frontend = await agent(`${CTX}

Backend RAG endpoints are live-verified. YOUR TASK: switch nora-web's RAG layer to the real backend behind the existing USE_BACKEND toggle. Frontend root: D:\\claude\\Nora\\nora-web (pnpm, vite, vitest).

READ FIRST:
- src/lib/api/client.ts (requestJson + API_BASE + USE_BACKEND pattern — requestJson unwraps {code,data,message} envelope, throws on code!=0)
- src/lib/services/ragService.ts (current mock: searchDocs(query, docs, topK), computeIndexStats(docs), generateCitations(query, docs, topK) — note they take docs from caller)
- Consumers: src/components/knowledge/IndexStatus.tsx (computeIndexStats(docs) sync), src/components/knowledge/RetrievalTest.tsx (searchDocs(query, docs) sync), src/lib/api/chatApi.ts (generateCitations in MockChatAPI)
- src/types/index.ts (IndexStats: {totalDocs,totalChunks,vectorDim,model,lastUpdate,pendingDocs,vectorReady,graphReady}; RetrievalResult/Citation/KnowledgeDoc)

CHANGES (minimal, zero-breakage when USE_BACKEND=false):
1. ragService.ts: add async functions that call the real API when USE_BACKEND, else fall back to existing mock logic:
   - searchDocsAsync(query, topK=8): USE_BACKEND ? requestJson<RetrievalResult[]>("/rag/search", POST {query,topK}) : mock searchDocs(query, useKnowledgeDocs.getState().docs, topK)
   - fetchIndexStatsAsync(): USE_BACKEND ? requestJson<IndexStats>("/rag/index/stats") : computeIndexStats(docs)
   - generateCitationsAsync(query, topK=2): same pattern via /rag/citations
   Keep the existing sync functions exported (chatApi MockChatAPI still imports generateCitations). New async names must not collide.
2. RetrievalTest.tsx: use searchDocsAsync in handleSearch (it's already in a setTimeout/schedule wrapper — adapt to async: keep the 800ms demo delay only when !USE_BACKEND; when USE_BACKEND show real loading state, no artificial delay).
3. IndexStatus.tsx: fetch stats via useEffect + fetchIndexStatsAsync (useState/useEffect, loading fallback to local mock compute until loaded).
4. Add tests (vitest, follow existing *.test.ts patterns — see src/hooks/useChat.test.ts for store mocking approach): mock global fetch returning envelope {code:0,data:...} for search/stats/citations paths; assert unwrapping and fallback path when USE_BACKEND=false (mock import.meta.env.VITE_USE_BACKEND — see how vite-env / client.ts tests handle it; if no pattern exists, put toggle-dependent logic in injectable function param or skip that case and test the request path directly with the mock function exported).
5. Run quality gates from nora-web: pnpm typecheck && pnpm lint && pnpm test && pnpm build — ALL must pass.
6. Do NOT touch useChat/useFiles/upload flows (Phase 1 scope is RAG read path only). Do NOT add .env files.

Report: files changed, test results (counts), any contract mismatches found (backend field name vs frontend expectation) — if a mismatch exists that requires a backend fix, FIX THE BACKEND (it's in D:\\claude\\Nora\\nora-api) and note it. Under 300 words.`,
  { label: 'frontend', phase: 'Frontend', effort: 'high' })

phase('Verify')
const verify = await agent(`${CTX}

Full chain implemented (backend + frontend switch). YOUR TASK: end-to-end browser verification.

1. Start infra + services: docker compose --profile dev-basic up -d (already running); export NORA_EMBEDDING_API_KEY from .env.local; start file-service, rag-service, gateway (jars from target/, background, log to files).
2. Frontend dev server: cd D:\\claude\\Nora\\nora-web && VITE_USE_BACKEND=true pnpm dev (background, port 3001, proxies /api → :8080).
3. Browser automation is available via MCP preview tools? You are a headless agent — instead verify via HTTP-level checks against the dev server proxy AND the backend directly:
   - curl http://localhost:3001/api/rag/index/stats through the vite proxy (proves proxy wiring) — expect code 0 envelope
   - upload a fresh test file through the vite proxy: curl -F "file=@..." http://localhost:3001/api/files/upload, then index, poll docs, search — full chain through the exact port/path the browser uses
4. Frontend unit/type/lint/build gates: cd nora-web && pnpm typecheck && pnpm lint && pnpm test && pnpm build — all green.
5. Kill all java + node processes you started (taskkill //F //IM java.exe; kill node PIDs). Remove scratch files (/tmp/test-doc*.txt, *.log).
6. git -C D:\\claude\\Nora status --short — list what changed (do NOT commit).

Report: proxy chain results, quality gate results, anything that failed and how fixed. Under 300 words.`,
  { label: 'e2e-verify', phase: 'Verify', effort: 'high' })

return {
  impl: impl.filter(Boolean).length,
  integration: typeof integration === 'string' ? integration.slice(0, 600) : integration,
  frontend: typeof frontend === 'string' ? frontend.slice(0, 600) : frontend,
  verify: typeof verify === 'string' ? verify.slice(0, 600) : verify,
}
