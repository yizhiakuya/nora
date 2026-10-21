package com.nora.agent.service;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 单测只作路径冒烟，结果正确性由真实服务 E2E 验证。 */
class PhotoAlbumServiceTest {
    @TempDir Path directory;

    @Test
    void createUpdateFeedbackAndInheritPreferences() throws Exception {
        var mapper = new ObjectMapper();
        var workspace = new AgentWorkspaceService(directory.toString());
        var viewer = new ViewerService(workspace, null, null, null);
        var service = new PhotoAlbumService(workspace, viewer, mapper);
        ImageIO.write(new BufferedImage(20, 15, BufferedImage.TYPE_INT_RGB), "png", directory.resolve("photo.png").toFile());
        service.execute(mapper.readTree("""
                {"action":"create","album":"smoke","title":"相册","photos":[{"target":"workspace:photo.png","caption":"真实测试图片"}]}
                """));
        var read = service.execute(mapper.readTree("{\"action\":\"read\",\"album\":\"smoke\"}"));
        service.execute(mapper.readTree("""
                {"action":"update","album":"smoke","revision":1,"photos":[{"target":"workspace:photo.png"}]}
                """));
        var feedback = mapper.createObjectNode().put("action", "feedback").put("album", "smoke")
                .put("revision", 2).put("feedback", "以后用深色，文案朴素").put("remember", true);
        feedback.putObject("style").put("theme", "night").put("captionTone", "plain");
        feedback.putArray("captions").addObject().put("hash", read.path("document").path("photos").get(0).path("hash").asText())
                .put("caption", "测试照片");
        service.execute(feedback);
        service.execute(mapper.readTree("""
                {"action":"create","album":"inherited","photos":[{"target":"workspace:photo.png"}]}
                """));
        service.execute(mapper.readTree("{\"action\":\"list\"}"));
        RiskClassifier.classify("manage_photo_album", feedback.toString(), workspace);
    }
}
