package com.nora.agent.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nora.agent.service.AppSettingStore;
import com.nora.common.response.ApiResponse;

/**
 * 用户偏好(M2-05,2026-09-20,方案 §9):
 * 首版只提供三项**能落地**的结构化偏好——报告语言、默认成果目录、命名习惯。
 * 后端 app_setting 持久化(localStorage 只作缓存);运行上下文注入见
 * ChatContextAssembler.userPreferencesSummary。
 *
 * <p>白名单字段,防未知键写穿;每项都必须进入实际运行上下文,不做"只改 UI"的设置。
 */
@RestController
@RequestMapping("/api/user-preferences")
public class UserPreferencesController {

    static final String SETTING_KEY = "user-preferences";

    /** 白名单:报告语言 / 默认成果目录 / 命名习惯。 */
    private static final List<String> ALLOWED = List.of("reportLanguage", "defaultOutputDir", "namingStyle");

    private final AppSettingStore appSettingStore;

    public UserPreferencesController(AppSettingStore appSettingStore) {
        this.appSettingStore = appSettingStore;
    }

    /** GET → 当前偏好(缺省返回默认值)。 */
    @GetMapping
    public ApiResponse<Map<String, Object>> get() {
        return ApiResponse.ok(current());
    }

    /** PUT → 部分更新(merge);仅白名单字段。 */
    @PutMapping
    public ApiResponse<Map<String, Object>> update(@RequestBody Map<String, Object> patch) {
        Map<String, Object> merged = new LinkedHashMap<>(current());
        for (String k : ALLOWED) {
            if (patch.containsKey(k)) {
                Object v = patch.get(k);
                if (v == null || (v instanceof String s && s.isBlank())) {
                    merged.remove(k);
                } else {
                    merged.put(k, String.valueOf(v).trim());
                }
            }
        }
        appSettingStore.save(SETTING_KEY, merged);
        return ApiResponse.ok(current());
    }

    private Map<String, Object> current() {
        Map<String, Object> stored = appSettingStore.raw(SETTING_KEY);
        return stored != null ? stored : Map.of();
    }
}
