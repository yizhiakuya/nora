package com.nora.agent.service;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

class MessageRefResolverTest {
    @TempDir Path directory;

    @Test
    void attachesImageReferencesAndKeepsTextAndUnavailableReferencesReadable() throws Exception {
        var workspace = new AgentWorkspaceService(directory.toString());
        var resolver = new MessageRefResolver(null, null, null, null, new ViewerService(workspace, null, null, null));
        ImageIO.write(new BufferedImage(20, 15, BufferedImage.TYPE_INT_RGB), "png", directory.resolve("photo.png").toFile());
        Files.writeString(directory.resolve("notes.txt"), "text reference");
        var references = resolver.resolveContext(null, List.of(
                new MessageRefResolver.Ref("viewer", 0, "photo", "workspace:photo.png"),
                new MessageRefResolver.Ref("viewer", 0, "notes", "workspace:notes.txt"),
                new MessageRefResolver.Ref("viewer", 0, "missing", "workspace:missing.png")));
        var mapper = new ObjectMapper();
        dev.langchain4j.data.message.ChatMessageSerializer.messagesToJson(List.of(
                WireMessage.user("Read the image").withImages(references.images()).message(),
                WireMessage.user("Text only").withImages(List.of()).message()));
    }
}
