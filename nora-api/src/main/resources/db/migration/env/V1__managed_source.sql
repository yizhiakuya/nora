-- 纳管日志源:环境控制台的唯一事实来源(用户显式添加,不做自动发现)
-- kind = FILE(裸进程日志文件,如微服务 *.stdout.log)| DOCKER(容器,走 docker logs)
CREATE TABLE managed_source (
    id              BIGSERIAL PRIMARY KEY,
    kind            VARCHAR(16)  NOT NULL CHECK (kind IN ('FILE', 'DOCKER')),
    name            VARCHAR(120) NOT NULL UNIQUE,
    file_log_path   TEXT,        -- FILE 源:日志文件绝对路径
    container_name  VARCHAR(120),-- DOCKER 源:容器名
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT kind_payload CHECK (
        (kind = 'FILE'   AND file_log_path  IS NOT NULL AND container_name IS NULL) OR
        (kind = 'DOCKER' AND container_name IS NOT NULL AND file_log_path  IS NULL)
    )
);

-- 预置 Nora 自身的基础设施:7 个微服务(文件日志)+ 3 个容器
INSERT INTO managed_source (kind, name, file_log_path) VALUES
    ('FILE', 'gateway-service',    'D:\claude\Nora\gateway-service.stdout.log'),
    ('FILE', 'agent-service',      'D:\claude\Nora\agent-service.stdout.log'),
    ('FILE', 'rag-service',        'D:\claude\Nora\rag-service.stdout.log'),
    ('FILE', 'datasource-service', 'D:\claude\Nora\datasource-service.stdout.log'),
    ('FILE', 'env-service',        'D:\claude\Nora\env-service.stdout.log'),
    ('FILE', 'file-service',       'D:\claude\Nora\file-service.stdout.log'),
    ('FILE', 'automation-service', 'D:\claude\Nora\automation-service.stdout.log');
INSERT INTO managed_source (kind, name, container_name) VALUES
    ('DOCKER', 'nora-nacos',   'nora-nacos'),
    ('DOCKER', 'nora-redis',   'nora-redis'),
    ('DOCKER', 'nora-postgres','nora-postgres');
