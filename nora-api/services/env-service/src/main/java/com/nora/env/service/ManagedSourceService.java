package com.nora.env.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 纳管日志源(managed_source)清单:环境控制台的唯一事实来源。
 * 用户显式添加/删除,不做自动发现;FILE 源读本地日志文件,
 * DOCKER 源走 {@link DockerClientService}。
 */
@Service
public class ManagedSourceService {

    private static final Logger log = LoggerFactory.getLogger(ManagedSourceService.class);

    private final JdbcTemplate jdbcTemplate;

    public ManagedSourceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 一条纳管源(前端 ServiceInstance/日志源共用形状)。 */
    public record SourceView(
            long id,
            String kind,          // FILE | DOCKER | PROC
            String name,
            String fileLogPath,   // FILE 源
            String containerName, // DOCKER 源
            String command,       // PROC 源:启动命令
            String workDir,       // PROC 源:工作目录
            boolean enabled) {

        /** 兼容 FILE/DOCKER 两参旧构造(测试/旧调用方)。 */
        public SourceView(long id, String kind, String name, String fileLogPath, String containerName, boolean enabled) {
            this(id, kind, name, fileLogPath, containerName, null, null, enabled);
        }
    }

    /** 列出全部纳管源(含暂停的,前端标灰)。 */
    public List<SourceView> list() {
        return jdbcTemplate.query(
                "SELECT id, kind, name, file_log_path, container_name, command, work_dir, enabled " +
                        "FROM managed_source ORDER BY id",
                (rs, i) -> new SourceView(
                        rs.getLong("id"),
                        rs.getString("kind"),
                        rs.getString("name"),
                        rs.getString("file_log_path"),
                        rs.getString("container_name"),
                        rs.getString("command"),
                        rs.getString("work_dir"),
                        rs.getBoolean("enabled")));
    }

    /** 添加一条纳管源;name 唯一,kind 与对应字段必须匹配。 */
    public SourceView create(String kind, String name, String fileLogPath, String containerName,
                             String command, String workDir) {
        String k = kind == null ? "" : kind.toUpperCase();
        if (!List.of("FILE", "DOCKER", "PROC").contains(k)) {
            throw new IllegalArgumentException("kind 必须是 FILE、DOCKER 或 PROC");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if ("FILE".equals(k) && (fileLogPath == null || fileLogPath.isBlank())) {
            throw new IllegalArgumentException("FILE 源必须提供 fileLogPath");
        }
        if ("DOCKER".equals(k) && (containerName == null || containerName.isBlank())) {
            throw new IllegalArgumentException("DOCKER 源必须提供 containerName");
        }
        if ("PROC".equals(k) && (command == null || command.isBlank())) {
            throw new IllegalArgumentException("PROC 源必须提供启动命令(command)");
        }
        try {
            jdbcTemplate.update(
                    "INSERT INTO managed_source (kind, name, file_log_path, container_name, command, work_dir) " +
                            "VALUES (?, ?, ?, ?, ?, ?)",
                    k, name.trim(),
                    "FILE".equals(k) ? fileLogPath.trim() : null,
                    "DOCKER".equals(k) ? containerName.trim() : null,
                    "PROC".equals(k) ? command.trim() : null,
                    "PROC".equals(k) ? (workDir == null || workDir.isBlank() ? null : workDir.trim()) : null);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new IllegalArgumentException("同名纳管源已存在: " + name.trim());
        }
        return getByName(name.trim());
    }

    /** 删除一条纳管源(只删清单,不动容器/文件本身)。 */
    public boolean delete(long id) {
        return jdbcTemplate.update("DELETE FROM managed_source WHERE id = ?", id) > 0;
    }

    /** 暂停/恢复纳管(暂停后 /services 不再返回该项)。 */
    public boolean setEnabled(long id, boolean enabled) {
        return jdbcTemplate.update("UPDATE managed_source SET enabled = ? WHERE id = ?", enabled, id) > 0;
    }

    private SourceView getByName(String name) {
        List<SourceView> rows = jdbcTemplate.query(
                "SELECT id, kind, name, file_log_path, container_name, command, work_dir, enabled " +
                        "FROM managed_source WHERE name = ?",
                (rs, i) -> new SourceView(
                        rs.getLong("id"),
                        rs.getString("kind"),
                        rs.getString("name"),
                        rs.getString("file_log_path"),
                        rs.getString("container_name"),
                        rs.getString("command"),
                        rs.getString("work_dir"),
                        rs.getBoolean("enabled")),
                name);
        if (rows.isEmpty()) {
            throw new IllegalStateException("纳管源创建后查询失败: " + name);
        }
        return rows.get(0);
    }

    /**
     * 读取 FILE 源日志文件末尾 N 行(RandomAccessFile 从尾部回扫,零依赖)。
     * 文件不存在/不可读时抛 IOException 由调用方转 ERROR 文案。
     */
    public List<String> tailFile(String path, int maxLines) throws IOException {
        Deque<String> lines = new java.util.ArrayDeque<>(maxLines);
        try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
            long pos = raf.length();
            int newlines = 0;
            // 从文件尾往前扫,数够 maxLines 个换行(或到文件头)后整段读出
            while (pos > 0 && newlines <= maxLines) {
                pos--;
                raf.seek(pos);
                if (raf.read() == '\n') {
                    newlines++;
                }
            }
            if (newlines <= maxLines && pos == 0) {
                raf.seek(0);
            } else {
                raf.seek(pos + 1);
            }
            // 一次读出目标段,按行拆(尾部多读的一行是下一轮增量的锚点,无害)
            byte[] buf = new byte[(int) (raf.length() - raf.getFilePointer())];
            raf.readFully(buf);
            String segment = new String(buf, StandardCharsets.UTF_8);
            for (String line : segment.split("\n", -1)) {
                if (line.isBlank()) {
                    continue;
                }
                if (lines.size() == maxLines) {
                    lines.pollFirst();
                }
                lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
            }
        }
        return new ArrayList<>(lines);
    }

    /** 增量读取结果:本次完整行 + 下一次读取偏移(半行不消费,留待下次) */
    public record TailChunk(List<String> lines, long nextOffset) {
    }

    /**
     * 从指定字节偏移增量读取日志到文件尾(SSE 轮询用,替代「tail N 行 + 行数游标」
     * 的旧方案——文件超过 N 行后行数游标永不前进导致断流)。
     * 文件被轮转/截断(长度小于偏移)时自动回退全量重读。
     */
    public TailChunk readFrom(String path, long offset) throws IOException {
        java.io.File f = new java.io.File(path);
        long len = f.length();
        long pos = offset > len ? 0 : offset;
        List<String> lines = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
            long readLen = Math.min(len - pos, 4L * 1024 * 1024); // 单次最多 4MB,防日志爆发
            if (readLen > 0) {
                raf.seek(pos);
                byte[] buf = new byte[(int) readLen];
                raf.readFully(buf);
                String segment = new String(buf, StandardCharsets.UTF_8);
                int idx = 0;
                for (; ; ) {
                    int nl = segment.indexOf('\n', idx);
                    if (nl < 0) {
                        break;
                    }
                    String line = segment.substring(idx, nl);
                    if (line.endsWith("\r")) {
                        line = line.substring(0, line.length() - 1);
                    }
                    if (!line.isBlank()) {
                        lines.add(line);
                    }
                    idx = nl + 1;
                }
                pos += idx; // 只消费完整行
            }
        }
        return new TailChunk(lines, pos);
    }
}
