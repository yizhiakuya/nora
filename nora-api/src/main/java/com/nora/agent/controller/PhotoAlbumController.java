package com.nora.agent.controller;

import java.io.IOException;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.service.PhotoAlbumService;
import com.nora.common.exception.BusinessException;
import com.nora.common.response.ApiResponse;

@RestController
@RequestMapping("/api/workspace/photo-albums")
public class PhotoAlbumController {
    private final PhotoAlbumService albums;
    private final ObjectMapper mapper;

    public PhotoAlbumController(PhotoAlbumService albums, ObjectMapper mapper) {
        this.albums = albums;
        this.mapper = mapper;
    }

    @GetMapping
    public ApiResponse<JsonNode> list() throws IOException {
        return execute(mapper.createObjectNode().put("action", "list"));
    }

    @PostMapping
    public ApiResponse<JsonNode> execute(@RequestBody JsonNode request) throws IOException {
        try { return ApiResponse.ok(albums.execute(request)); }
        catch (IllegalArgumentException e) { throw new BusinessException(400, e.getMessage()); }
    }
}
