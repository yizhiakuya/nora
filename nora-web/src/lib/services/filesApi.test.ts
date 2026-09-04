import { describe, it, expect, vi, beforeAll, beforeEach } from "vitest";

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

    expect(items).toHaveLength(1);
    const f = items[0];
    expect(f.id).toBe(1);
    expect(f.name).toBe("guide.md");
    expect(f.size).toBe("232 B");
    expect(f.indexed).toBe(true);
    expect(f.date).toBe("2026-09-05 00:36");
    expect(f.icon).toBeDefined();
    expect(mockFetch).toHaveBeenCalledWith(
      "/api/files",
      expect.objectContaining({ headers: expect.objectContaining({ "Content-Type": "application/json" }) })
    );
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

    expect(item.name).toBe("doc.pdf");
    expect(item.size).toBe("1.0 KB");
    const [url, init] = mockFetch.mock.calls[0];
    expect(url).toBe("/api/files/upload");
    expect(init.method).toBe("POST");
    expect(init.body).toBeInstanceOf(FormData);
    // multipart 边界不能被 Content-Type: application/json 覆盖
    const headers = init.headers as Record<string, string> | undefined;
    expect(headers?.["Content-Type"]).toBeUndefined();
  });

  it("deleteFiles joins ids into query param", async () => {
    mockFetch.mockResolvedValueOnce(jsonResponse(null));

    await filesApi.deleteFiles([1, 2, 3]);

    expect(mockFetch.mock.calls[0][0]).toBe("/api/files?ids=1,2,3");
    expect(mockFetch.mock.calls[0][1].method).toBe("DELETE");
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

    expect(mockFetch.mock.calls[0][0]).toBe("/api/files/5/index");
    expect(mockFetch.mock.calls[0][1].method).toBe("POST");
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

    expect(preview.kind).toBe("text");
    expect(preview.text).toBe("hello world");
  });

  it("fetchPreview falls back to image kind for binary without text", async () => {
    mockFetch.mockResolvedValueOnce(
      jsonResponse({ fileId: 8, type: "text", textContent: null, name: "pic.png", size: "1 KB" })
    );

    const preview = await filesApi.fetchPreview(8, "pic.png");

    expect(preview.kind).toBe("image");
  });

  it("rejects when backend envelope carries error code", async () => {
    const body = JSON.stringify({ code: 404, data: null, message: "File not found: 99" });
    mockFetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      text: () => Promise.resolve(body),
      json: () => Promise.resolve(JSON.parse(body)),
    });

    await expect(filesApi.fetchPreview(99, "x.txt")).rejects.toThrow("File not found: 99");
  });
});
