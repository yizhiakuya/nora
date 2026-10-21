-- file-service initial schema (schema_file, see architecture-v2.md section 7.1)
CREATE TABLE file_item (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    file_path   VARCHAR(500),
    mime_type   VARCHAR(100),
    size_bytes  BIGINT,
    indexed     BOOLEAN DEFAULT false,
    created_at  TIMESTAMP DEFAULT now()
);
