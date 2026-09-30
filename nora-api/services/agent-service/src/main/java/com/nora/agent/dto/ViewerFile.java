package com.nora.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ViewerFile(String target, String name, String mimeType, long size,
                         String modifiedAt, String version, String previewKind,
                         Capabilities capabilities, Delivery delivery) {
    public record Capabilities(boolean preview, boolean source, boolean download,
                               boolean edit, boolean attach) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Delivery(String status, Long artifactId, String error) { }

    public ViewerFile withDelivery(Delivery value) {
        return new ViewerFile(target, name, mimeType, size, modifiedAt, version,
                previewKind, capabilities, value);
    }
}
