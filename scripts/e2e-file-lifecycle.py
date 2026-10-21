"""File/RAG transaction acceptance against an explicitly isolated PostgreSQL database.

NORA_E2E_DB_NAME must start with nora_e2e_ or nora_simplify_. Uses the local
nora-postgres container and a real HTTP application, without an embedding fixture.
"""
import importlib.util
import os
from pathlib import Path
import re
import subprocess
import uuid

spec = importlib.util.spec_from_file_location("standards", Path(__file__).with_name("e2e-standards.py"))
http = importlib.util.module_from_spec(spec)
spec.loader.exec_module(http)
database = os.environ.get("NORA_E2E_DB_NAME", "")
if not re.fullmatch(r"nora_(?:e2e|simplify)_[a-zA-Z0-9_]+", database):
    raise RuntimeError("An explicitly isolated NORA_E2E_DB_NAME is required")


def sql(query):
    result = subprocess.run(["rtk", "proxy", "docker", "exec", "nora-postgres", "psql", "-qAt",
                             "-v", "ON_ERROR_STOP=1", "-U", "nora", "-d", database, "-c", query],
                            check=True, capture_output=True, text=True, encoding="utf-8")
    return result.stdout.strip()


prefix = "lifecycle-" + uuid.uuid4().hex
boundary = uuid.uuid4().hex
content = b"original file retained until database commit\n"
body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{prefix}.txt"\r\n'
        'Content-Type: text/plain\r\n\r\n').encode() + content + f"\r\n--{boundary}--\r\n".encode()
file = http.request("/files/upload", "POST", body, content_type="multipart/form-data; boundary=" + boundary)[0]["data"]
id = file["id"]
query = "?ids=" + str(id)
constraint = "e2e_lifecycle_" + uuid.uuid4().hex
try:
    if sql(f"SELECT count(*) FROM schema_file.file_item WHERE id={id} AND name='{prefix}.txt'") != "1":
        raise RuntimeError("HTTP application and fixture database do not match")
    path = Path(sql(f"SELECT file_path FROM schema_file.file_item WHERE id={id}"))
    old = int(sql(f"INSERT INTO schema_rag.knowledge_doc(name,source,source_id,status,deleted_at) "
                  f"VALUES ('{prefix}-old','file',{id},'indexed',now()-interval '1 day') RETURNING id"))
    doc = int(sql(f"INSERT INTO schema_rag.knowledge_doc(name,source,source_id,status,chunks) "
                  f"VALUES ('{prefix}','file',{id},'indexed',1) RETURNING id"))
    sql(f"INSERT INTO schema_rag.knowledge_chunk(doc_id,chunk_index,content,deleted_at) VALUES "
        f"({old},0,'old version',now()-interval '1 day'),({doc},0,'current version',NULL)")
    http.data("/files/trash" + query, "DELETE")
    if http.request(f"/files/{id}/raw", raw=True)[0] != content:
        raise RuntimeError("Purge removed a live file")

    # A real database rejection must roll back both domains and keep disk bytes.
    sql(f"ALTER TABLE schema_rag.knowledge_doc ADD CONSTRAINT {constraint} "
        f"CHECK (source_id IS DISTINCT FROM {id} OR deleted_at IS NULL) NOT VALID")
    try:
        http.request("/files" + query, "DELETE", status=500)
        if sql(f"SELECT deleted_at IS NULL FROM schema_file.file_item WHERE id={id}") != "t" \
                or sql(f"SELECT deleted_at IS NULL FROM schema_rag.knowledge_doc WHERE id={doc}") != "t" \
                or http.request(f"/files/{id}/raw", raw=True)[0] != content:
            raise RuntimeError("File/RAG mutation was not atomic on database failure")
    finally:
        sql(f"ALTER TABLE schema_rag.knowledge_doc DROP CONSTRAINT {constraint}")
    print("PASS: purge ignores live files; RAG failure rolls back file state and retains original bytes", flush=True)

    sql(f"INSERT INTO schema_file.pending_rag_sync(file_id,mode,desired_state,version) VALUES ({id},'restore','present',9)")
    http.data("/files" + query, "DELETE")
    if sql(f"SELECT count(*) FROM schema_rag.knowledge_doc WHERE source_id={id} AND deleted_at IS NULL") != "0" \
            or sql(f"SELECT count(*) FROM schema_file.pending_rag_sync WHERE file_id={id}") != "0":
        raise RuntimeError("Delete did not update knowledge and clear superseded legacy delivery")
    http.data("/files/trash/restore", "POST", {"ids": [id]})
    if sql(f"SELECT id FROM schema_rag.knowledge_doc WHERE source_id={id} AND deleted_at IS NULL") != str(doc) \
            or sql(f"SELECT count(*) FROM schema_rag.knowledge_chunk WHERE doc_id={doc} AND deleted_at IS NULL") != "1" \
            or sql(f"SELECT count(*) FROM schema_rag.knowledge_chunk WHERE doc_id={old} AND deleted_at IS NULL") != "0":
        raise RuntimeError("Restore revived stale document/chunk versions or lost the current one")
    print("PASS: delete/restore share a transaction; only the latest indexed document and its chunks return", flush=True)

    http.data("/files" + query, "DELETE")
    http.data("/files/trash" + query, "DELETE")
    if path.exists() or sql(f"SELECT count(*) FROM schema_rag.knowledge_doc WHERE source_id={id}") != "0" \
            or sql(f"SELECT count(*) FROM schema_file.file_item WHERE id={id}") != "0":
        raise RuntimeError("Purge left file or knowledge rows behind")
    http.request(f"/files/{id}/raw", status=404)
    print("PASS: purge removes file metadata and all knowledge versions; raw bytes are no longer accessible", flush=True)
finally:
    http.data("/files" + query, "DELETE")
    http.data("/files/trash" + query, "DELETE")
