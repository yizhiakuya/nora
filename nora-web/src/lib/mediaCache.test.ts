// @vitest-environment jsdom
import { afterEach, describe, it } from "vitest";
import { setAuthToken, withAuthToken } from "./auth";
import { mediaCacheUrl } from "./mediaCache";

afterEach(() => localStorage.clear());

describe("媒体地址鉴权冒烟", () => {
  it("覆盖本站 API、外部媒体、已有令牌与片段", () => {
    setAuthToken("media-smoke-token");
    for (const url of [
      "/api/files/1/raw", `${window.location.origin}/api/files/1/raw?v=1#page=2`,
      "/api/files/1/raw?token=existing", "/api/files/1/raw#token=fragment",
      "https://media.example/photo.png", "//media.example/photo.png",
      "data:image/png;base64,AA==", "blob:https://media.example/id",
      "/logo.png", "http://[", "",
    ]) {
      withAuthToken(url);
      mediaCacheUrl(url);
    }
  });

  it("无令牌的地址仍可用于免登录部署", () => {
    withAuthToken("/api/files/1/raw");
    mediaCacheUrl("/api/files/1/raw");
    mediaCacheUrl("https://media.example/photo.png");
  });
});
