package com.nora.agent.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nora.agent.service.AppSettingStore;
import com.nora.common.response.ApiResponse;

/**
 * Agent 全局设置(对话页三件套):权限模式 / 默认模型 / 思考等级覆写。
 *
 * <p>持久化在 {@code app_setting} 表 key=agent 下(JSONB),跨会话、跨浏览器
 * 生效——前端 localStorage 只作缓存,真相在这里。修改即时保存,无重启。
 *
 * <p>权限模式语义见 {@link com.nora.agent.service.PermissionMode}:
 * ASK(每次询问) / ASSIST(高风险询问,默认) / FULL(全自动,CRITICAL 仍强制审批)。
 */
@RestController
@RequestMapping("/api/chat/settings")
public class AgentSettingsController {

    static final String SETTING_KEY = "agent";

    private final AppSettingStore appSettingStore;

    public AgentSettingsController(AppSettingStore appSettingStore) {
        this.appSettingStore = appSettingStore;
    }

    /** GET /api/chat/settings → 当前 agent 全局设置(缺省字段回默认值)。 */
    @GetMapping
    public ApiResponse<Map<String, Object>> get() {
        return ApiResponse.ok(current());
    }

    /** PUT /api/chat/settings → 保存(部分更新,merge 到已存值)。 */
    @PutMapping
    public ApiResponse<Map<String, Object>> update(@RequestBody Map<String, Object> patch) {
        Map<String, Object> merged = new java.util.HashMap<>(current());
        // 白名单字段,防未知键写穿
        for (String k : java.util.List.of("permissionMode", "model", "reasoningLevel")) {
            if (patch.containsKey(k)) {
                merged.put(k, patch.get(k));
            }
        }
        appSettingStore.save(SETTING_KEY, merged);
        return ApiResponse.ok(current());
    }

    private Map<String, Object> current() {
        // raw() 自带异常兜底(表未建/库不可用返回 null);设置项无运行时
        // 副作用,前端每轮把 permissionMode 随请求带上即可
        Map<String, Object> stored = appSettingStore.raw(SETTING_KEY);
        return stored != null ? stored : Map.of("permissionMode", "assist");
    }
}
