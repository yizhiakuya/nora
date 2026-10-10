package com.nora.agent.service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nora.agent.service.BrowserEvidenceService.CollectedRow;
import com.nora.common.exception.BusinessException;

@Service
public class BrowserTaskService {
    private final BrowserEvidenceService evidence;
    private final ObjectMapper mapper;

    public BrowserTaskService(BrowserEvidenceService evidence, ObjectMapper mapper) {
        this.evidence = evidence; this.mapper = mapper;
    }

    JsonNode current(String sessionId, List<JsonNode> exports) throws IOException {
        JsonNode plan = evidence.latestPlan(sessionId);
        if (plan == null) return mapper.nullNode();
        List<JsonNode> captures = captures(sessionId, plan);
        List<String> ids = captures.stream().map(row -> row.path("id").asText()).toList();
        JsonNode receipt = exports.stream().filter(row -> row.path("planId").equals(plan.path("id"))).findFirst().orElse(null);
        if (receipt == null) {
            JsonNode latest = evidence.latestReceipt(sessionId, plan.path("id").asText());
            if (latest != null) receipt = evidence.receiptSummary(sessionId, latest);
        }
        List<CollectedRow> rows = receipt == null ? evidence.collectedRows(sessionId, plan, ids)
                : selectedRows(sessionId, plan, receipt);
        var quality = quality(rows);
        String phase = plan.path("cancelled").asBoolean() ? "cancelled"
                : receipt != null ? receipt.path("verified").asBoolean() ? "completed" : "needs_check"
                : awaitingReview(plan) && !rows.isEmpty() ? "review"
                : plan.path("review").path("approved").asBoolean() ? "collecting" : "planning";
        ObjectNode summary = (ObjectNode) plan.deepCopy();
        summary.remove("excludedKeys");
        summary.put("phase", phase).put("rowCount", rows.size()).put("usableRows", quality.usableRows())
                .put("duplicateCount", quality.duplicateIds().size()).put("missingCount", quality.missingIds().size());
        summary.set("captureIds", mapper.valueToTree(ids));
        summary.set("latestReceipt", receipt == null ? mapper.nullNode() : receipt);
        return summary;
    }

    int extractionLimit(String sessionId, String planId, int requested) throws IOException {
        JsonNode plan = evidence.read(sessionId, "plans", planId);
        if (plan.path("cancelled").asBoolean()) throw new IllegalArgumentException("任务已取消，请停止采集");
        if (!awaitingReview(plan)) return requested;
        int count = captures(sessionId, plan).stream().mapToInt(row -> row.path("rows").size()).sum();
        int remaining = Math.min(3, plan.path("minRows").asInt()) - count;
        if (remaining <= 0) throw new IllegalArgumentException("样本已采集，请结束本轮并等待用户查看样本；不要继续采集或自行确认");
        return Math.min(requested, remaining);
    }

    void guardPlan(String sessionId, String action, JsonNode args) throws IOException {
        if (!List.of("plan", "extract", "export").contains(action)) return;
        JsonNode latest = evidence.latestPlan(sessionId);
        if (latest != null && !latest.path("cancelled").asBoolean()
                && !latest.path("sourceUrl").asText("").isBlank()
                && ("plan".equals(action) || !latest.path("id").asText().equals(args.path("planId").asText()))) {
            JsonNode receipt = evidence.latestReceipt(sessionId, latest.path("id").asText());
            if (receipt != null) receipt = evidence.receiptSummary(sessionId, receipt);
            if (receipt == null || !receipt.path("verified").asBoolean()) {
                throw new IllegalArgumentException("当前网页任务已有冻结计划，使用 planId=" + latest.path("id").asText() + "，不要另建计划绕过样本查看或原始字段与数量要求");
            }
        }
    }

    JsonNode decide(String sessionId, JsonNode args) throws IOException {
        ObjectNode plan = (ObjectNode) evidence.read(sessionId, "plans", BrowserEvidenceService.required(args, "planId"));
        String action = BrowserEvidenceService.required(args, "action");
        String feedback = args.path("feedback").asText("").trim();
        if (feedback.length() > 1500) throw new IllegalArgumentException("调整要求最多 1500 字");
        if ("cancel".equals(action)) plan.put("cancelled", true);
        else {
            if (plan.path("cancelled").asBoolean()) throw new IllegalArgumentException("任务已取消");
            if (!awaitingReview(plan)) throw new IllegalArgumentException("样本决定已提交，请刷新任务状态");
            if (captures(sessionId, plan).isEmpty()) throw new IllegalArgumentException("尚未采集样本");
            if ("resample".equals(action)) {
                if (feedback.isBlank()) throw new IllegalArgumentException("请填写重新采样的调整要求");
                plan.put("sampleRevision", plan.path("sampleRevision").asInt() + 1);
            }
            plan.set("review", mapper.createObjectNode().put("approved", "approve".equals(action))
                    .put("feedback", feedback).put("decidedAt", Instant.now().toString()));
        }
        evidence.save(sessionId, "plans", plan.path("id").asText(), plan);
        return plan;
    }

    JsonNode rows(String sessionId, JsonNode args) throws IOException {
        JsonNode receipt = args.path("receiptId").asText("").isBlank() ? null
                : evidence.read(sessionId, "receipts", args.path("receiptId").asText());
        String planId = receipt == null ? BrowserEvidenceService.required(args, "planId") : receipt.path("planId").asText();
        JsonNode plan = evidence.read(sessionId, "plans", planId);
        List<String> ids = receipt == null ? captures(sessionId, plan).stream().map(row -> row.path("id").asText()).toList()
                : BrowserEvidenceService.strings(receipt.path("captureIds"));
        List<CollectedRow> rows = evidence.collectedRows(sessionId, plan, ids);
        List<String> selected = receipt == null || !receipt.has("rowIds") ? rows.stream().map(CollectedRow::id).toList()
                : BrowserEvidenceService.strings(receipt.path("rowIds"));
        var quality = quality(rows);
        int offset = Math.max(0, args.path("offset").asInt());
        int limit = Math.max(1, Math.min(100, args.path("limit").asInt(50)));
        ObjectNode result = mapper.createObjectNode().put("planId", planId).put("total", rows.size()).put("offset", offset)
                .put("minRows", plan.path("minRows").asInt()).put("preview", awaitingReview(plan));
        result.set("columns", plan.path("columns"));
        result.set("selectedIds", mapper.valueToTree(selected));
        result.set("duplicateIds", mapper.valueToTree(quality.duplicateIds()));
        result.set("missingIds", mapper.valueToTree(quality.missingIds()));
        result.set("duplicateGroups", mapper.valueToTree(quality.duplicateGroups()));
        result.set("rows", mapper.valueToTree(rows.subList(Math.min(offset, rows.size()), Math.min(offset + limit, rows.size()))));
        return result;
    }

    @Transactional(rollbackFor = IOException.class)
    public JsonNode adjust(String sessionId, JsonNode args) throws IOException {
        JsonNode receipt = evidence.read(sessionId, "receipts", BrowserEvidenceService.required(args, "receiptId"));
        JsonNode latest = evidence.latestReceipt(sessionId, receipt.path("planId").asText());
        if (latest == null || !latest.path("id").equals(receipt.path("id"))) {
            throw new BusinessException(409, "结果已有新版本，请载入新结果后再保存选行");
        }
        ObjectNode plan = (ObjectNode) evidence.read(sessionId, "plans", receipt.path("planId").asText());
        List<String> ids = BrowserEvidenceService.strings(receipt.path("captureIds"));
        List<CollectedRow> rows = evidence.collectedRows(sessionId, plan, ids);
        List<String> selected = BrowserEvidenceService.strings(args.path("rowIds"));
        Set<String> available = new LinkedHashSet<>(rows.stream().map(CollectedRow::id).toList());
        if (selected.isEmpty() || selected.stream().distinct().count() != selected.size() || !available.containsAll(selected)) {
            throw new IllegalArgumentException("请保留至少一行真实采集结果，不能传入未知或重复的行 ID");
        }
        Set<String> selectedIds = new LinkedHashSet<>(selected);
        Set<String> excluded = new LinkedHashSet<>(BrowserEvidenceService.strings(plan.path("excludedKeys")));
        rows.stream().filter(row -> !selectedIds.contains(row.id())).forEach(row -> excluded.add(row.key()));
        rows.stream().filter(row -> selectedIds.contains(row.id())).forEach(row -> excluded.remove(row.key()));
        plan.set("excludedKeys", mapper.valueToTree(excluded));
        evidence.save(sessionId, "plans", plan.path("id").asText(), plan);
        ObjectNode export = mapper.createObjectNode().put("planId", plan.path("id").asText()).put("parentReceiptId", receipt.path("id").asText())
                .put("filename", java.nio.file.Path.of(receipt.path("path").asText()).getFileName().toString());
        export.set("captureIds", mapper.valueToTree(ids)); export.set("rowIds", mapper.valueToTree(selected));
        return evidence.export(sessionId, null, export);
    }

    private List<CollectedRow> selectedRows(String sessionId, JsonNode plan, JsonNode receipt) throws IOException {
        List<CollectedRow> rows = evidence.collectedRows(sessionId, plan, BrowserEvidenceService.strings(receipt.path("captureIds")));
        if (!receipt.has("rowIds")) return rows;
        Set<String> selected = new LinkedHashSet<>(BrowserEvidenceService.strings(receipt.path("rowIds")));
        return rows.stream().filter(row -> selected.contains(row.id())).toList();
    }

    private List<JsonNode> captures(String sessionId, JsonNode plan) throws IOException {
        List<JsonNode> rows = evidence.records(sessionId, "captures", plan.path("id").asText());
        if (awaitingReview(plan)) return rows.stream().filter(row -> row.path("sampleRevision").asInt() == plan.path("sampleRevision").asInt()).toList();
        List<JsonNode> full = rows.stream().filter(row -> !row.path("sample").asBoolean()).toList();
        return full.isEmpty() ? rows.stream().filter(row -> row.path("sampleRevision").asInt() == plan.path("sampleRevision").asInt()).toList() : full;
    }

    private static boolean awaitingReview(JsonNode plan) {
        return plan.path("previewFirst").asBoolean() && !plan.path("review").path("approved").asBoolean();
    }

    private static Quality quality(List<CollectedRow> rows) {
        List<String> duplicates = new ArrayList<>(), missing = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>(); int usable = 0;
        java.util.Map<String, List<String>> groups = new java.util.LinkedHashMap<>();
        for (CollectedRow row : rows) {
            groups.computeIfAbsent(row.key(), ignored -> new ArrayList<>()).add(row.id());
            boolean duplicate = !seen.add(row.key());
            boolean incomplete = row.values().values().stream().anyMatch(String::isBlank);
            if (duplicate) duplicates.add(row.id());
            if (incomplete) missing.add(row.id());
            if (!duplicate && !incomplete) usable++;
        }
        return new Quality(usable, duplicates, missing, groups.values().stream().filter(group -> group.size() > 1).toList());
    }

    private record Quality(int usableRows, List<String> duplicateIds, List<String> missingIds, List<List<String>> duplicateGroups) { }
}
