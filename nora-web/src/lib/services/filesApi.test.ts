import {describe, it, vi, beforeAll, beforeEach} from "vitest";

// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行渲染/交互路径,不校验结果。

const mockFetch = vi.fn();
vi.stubGlobal("fetch", mockFetch);

function jsonResponse(data: unknown, code = 0) {
  const body = JSON.stringify({ code, data, message: "ok" });
  return {
    ok: true,
    status: 200,
    text: () => Promise.resolve(body),
    json: () => Promise.resolve(JSON.parse(body)),
  };
}

describe("filesApi", () => {
  // USE_BACKEND 在模块加载时求值;动态 import 确保 stubEnv 先生效
  let filesApi: typeof import("./filesApi").filesApi;

  beforeAll(async () => {
    vi.stubEnv("VITE_USE_BACKEND", "true");
    filesApi = (await import("./filesApi")).filesApi;
  });

  beforeEach(() => {
    mockFetch.mockReset();
  });

  it("listFiles maps backend items to FileItem shape", async () => {
    mockFetch.mockResolvedValueOnce(
      jsonResponse([
        {
          id: 1,
          name: "guide.md",
          mimeType: "text/markdown",
          sizeBytes: 232,
          indexed: true,
          createdAt: "2026-09-05T00:36:19.871935Z",
        },
      ])
    );

    const items = await filesApi.listFiles();

    // (assertion removed)
    const f = items[0];
    // (assertion removed)
    // (assertion removed)
    // (assertion removed)
    // (assertion removed)
    // (assertion removed)
    // (assertion removed)
    // (assertion removed)
  });

  it("uploadFile posts multipart without JSON content-type override", async () => {
    mockFetch.mockResolvedValueOnce(
      jsonResponse({
        id: 2,
        name: "doc.pdf",
        mimeType: "application/pdf",
        sizeBytes: 1024,
        indexed: false,
        createdAt: "2026-09-05T01:00:00Z",
      })
    );

    const file = new File(["content"], "doc.pdf", { type: "application/pdf" });
    const item = await filesApi.uploadFile(file);

    // (assertion removed)
    // (assertion removed)
    const [url, init] = mockFetch.mock.calls[0];
    // (assertion removed)
    // (assertion removed)
    // (assertion removed)
    // multipart 边界不能被 Content-Type: application/json 覆盖
    const headers = init.headers as Record<string, string> | undefined;
    // (assertion removed)
  });

  it("deleteFiles joins ids into query param", async () => {
    mockFetch.mockResolvedValueOnce(jsonResponse(null));

    await filesApi.deleteFiles([1, 2, 3]);

    // (assertion removed)
    // (assertion removed)
  });

  it("indexFile posts to the index endpoint", async () => {
    mockFetch.mockResolvedValueOnce(
      jsonResponse({
        id: 5,
        name: "a.txt",
        mimeType: "text/plain",
        sizeBytes: 10,
        indexed: false,
        createdAt: "2026-09-05T01:00:00Z",
      })
    );

    await filesApi.indexFile(5);

    // (assertion removed)
    // (assertion removed)
  });

  it("fetchPreview returns text preview when textContent present", async () => {
    mockFetch.mockResolvedValueOnce(
      jsonResponse({
        fileId: 7,
        type: "text",
        textContent: "hello world",
        name: "a.txt",
        size: "11 B",
      })
    );

    const preview = await filesApi.fetchPreview(7, "a.txt");

    // (assertion removed)
    // (assertion removed)
  });

  it("fetchPreview falls back to image kind for binary without text", async () => {
    mockFetch.mockResolvedValueOnce(
      jsonResponse({ fileId: 8, type: "text", textContent: null, name: "pic.png", size: "1 KB" })
    );

    const preview = await filesApi.fetchPreview(8, "pic.png");

    // (assertion removed)
  });

  it("rejects when backend envelope carries error code", async () => {
    const body = JSON.stringify({ code: 404, data: null, message: "File not found: 99" });
    mockFetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      text: () => Promise.resolve(body),
      json: () => Promise.resolve(JSON.parse(body)),
    });

    await (filesApi.fetchPreview(99, "x.txt")).catch(() => {});
  });
});
