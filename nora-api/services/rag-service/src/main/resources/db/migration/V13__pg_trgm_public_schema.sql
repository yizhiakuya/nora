-- 修复:pg_trgm 可能被装进 schema_rag(而非 public),导致关键词检索通道不可用。
--
-- 背景(2026-10-01 megumin 实测):Flyway 迁移在 default-schema(schema_rag)
-- 的 search_path 下执行 V3 的 CREATE EXTENSION,扩展对象落在 schema_rag;
-- 而运行时 JDBC 连接的 search_path 是 "$user", public——看不到 <<% 操作符与
-- strict_word_similarity 函数,关键词通道每次报 bad SQL grammar,整条检索
-- 静默降级为纯向量(专有名词/错误码召回质量下降)。
--
-- 修复:扩展对象统一挪回 public(默认 search_path 永远包含),与本地环境一致。
-- 幂等:已在 public 或扩展不存在时跳过。
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_extension
        WHERE extname = 'pg_trgm' AND extnamespace <> 'public'::regnamespace
    ) THEN
        ALTER EXTENSION pg_trgm SET SCHEMA public;
    END IF;
END $$;
