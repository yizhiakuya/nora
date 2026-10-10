package com.nora.agent.service;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.dto.CommandExecutionDto;

/** 只保存开始/结束快照；实时输出留在文件与有界内存，不逐 tick 写库。 */
@Service
public class CommandExecutionStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public CommandExecutionStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void save(CommandExecutionDto execution) {
        try {
            jdbc.update("""
                    INSERT INTO command_execution (id, session_id, snapshot) VALUES (?, ?, ?::jsonb)
                    ON CONFLICT (id) DO UPDATE SET snapshot = EXCLUDED.snapshot
                    """, execution.executionId(), execution.sessionId(), mapper.writeValueAsString(execution));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot persist command execution", e);
        }
    }

    public CommandExecutionDto find(String sessionId, String id) {
        return jdbc.query("SELECT snapshot FROM command_execution WHERE session_id = ? AND id = ?",
                (rs, row) -> decode(rs.getString(1)), sessionId, id).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("命令任务不存在: " + id));
    }

    public List<CommandExecutionDto> findAll(String sessionId, java.util.Set<String> ids) {
        if (ids.isEmpty()) return List.of();
        var args = new java.util.ArrayList<Object>(); args.add(sessionId); args.addAll(ids);
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbc.query("SELECT snapshot FROM command_execution WHERE session_id = ? AND id IN (" + placeholders + ")",
                (rs, row) -> decode(rs.getString(1)), args.toArray());
    }

    public List<CommandExecutionDto> list(String sessionId) {
        return jdbc.query("SELECT snapshot FROM command_execution WHERE session_id = ? AND NOT hidden ORDER BY created_at DESC LIMIT 100",
                (rs, row) -> decode(rs.getString(1)), sessionId);
    }

    public List<CommandExecutionDto> clearFinished(String sessionId) {
        return jdbc.query("""
                UPDATE command_execution SET hidden = true WHERE session_id = ? AND snapshot->>'status' <> 'running'
                RETURNING snapshot
                """, (rs, row) -> decode(rs.getString(1)), sessionId);
    }

    public void recover() {
        jdbc.update("""
                UPDATE command_execution SET snapshot = snapshot ||
                  jsonb_build_object('status', 'unknown', 'finishedAt', (extract(epoch from now()) * 1000)::bigint,
                    'error', '服务已重启，旧进程结果未知；请核实实际状态，不要自动重放命令。')
                WHERE snapshot->>'status' = 'running'
                """);
    }

    private CommandExecutionDto decode(String json) {
        try { return mapper.readValue(json, CommandExecutionDto.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot read command execution", e); }
    }
}
