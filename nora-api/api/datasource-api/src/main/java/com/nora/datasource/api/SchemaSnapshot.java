package com.nora.datasource.api;

import java.io.Serializable;
import java.util.List;

/**
 * 所连数据库的完整 schema,由 {@link DatasourceService#schema(Long)} 返回。
 *
 * @param tables 连接的全部表(按驱动顺序);绝不 {@code null},可为空
 */
public record SchemaSnapshot(
        List<DbTable> tables) implements Serializable {

    private static final long serialVersionUID = 1L;
}
