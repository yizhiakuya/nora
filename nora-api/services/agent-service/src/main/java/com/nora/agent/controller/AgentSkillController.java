package com.nora.agent.controller;

import com.nora.agent.service.AgentSkillService;
import com.nora.common.exception.BusinessException;
import com.nora.common.response.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 技能管理 API({@code /api/skills}):设置中心技能页(替代原纯前端 mock)
 * 与 agent 的 manage_skill 工具共用同一服务层。
 *
 * <p>列表接口不返回正文(大文本),详情接口带正文——前端列表 + 点击查看详情,
 * 与 agent 的渐进披露策略一致。
 */
@RestController
@RequestMapping("/api/skills")
public class AgentSkillController {

    private final AgentSkillService skillService;

    public AgentSkillController(AgentSkillService skillService) {
        this.skillService = skillService;
    }

    /** 列表(不含正文)。 */
    @GetMapping
    public ApiResponse<List<AgentSkillService.SkillView>> list() {
        return ApiResponse.ok(skillService.list().stream()
                .map(AgentSkillService.SkillView::summary)
                .toList());
    }

    /** 详情(含正文)。 */
    @GetMapping("/{id}")
    public ApiResponse<AgentSkillService.SkillView> get(@PathVariable long id) {
        AgentSkillService.SkillView skill = skillService.get(id);
        if (skill == null) {
            throw new BusinessException(404, "技能不存在: " + id);
        }
        return ApiResponse.ok(skill);
    }

    @PostMapping
    public ApiResponse<AgentSkillService.SkillView> create(@RequestBody CreateRequest request) {
        try {
            return ApiResponse.ok(skillService.create(
                    request.name(), request.description(), request.instructions(), request.category()));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, e.getMessage());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BusinessException(409, "技能名称已存在: " + request.name());
        }
    }

    @PutMapping("/{id}")
    public ApiResponse<AgentSkillService.SkillView> update(@PathVariable long id,
                                                           @RequestBody UpdateRequest request) {
        try {
            return ApiResponse.ok(skillService.update(id, request.name(), request.description(),
                    request.instructions(), request.category(), request.enabled()));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(404, e.getMessage());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BusinessException(409, "技能名称已存在: " + request.name());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        if (!skillService.delete(id)) {
            throw new BusinessException(404, "技能不存在: " + id);
        }
        return ApiResponse.ok();
    }

    public record CreateRequest(String name, String description, String instructions, String category) {
    }

    public record UpdateRequest(String name, String description, String instructions,
                                String category, Boolean enabled) {
    }
}
