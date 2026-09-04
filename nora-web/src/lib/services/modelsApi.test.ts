import { describe, it, expect, vi, beforeEach, beforeAll } from "vitest";

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

describe("modelsApi", () => {
  let modelsApi: typeof import("./modelsApi").modelsApi;

  beforeAll(async () => {
    vi.stubEnv("VITE_USE_BACKEND", "true");
    modelsApi = (await import("./modelsApi")).modelsApi;
  });

  beforeEach(() => {
    mockFetch.mockReset();
  });

  it("listProviders maps backend rows to ModelProvider", async () => {
    mockFetch.mockResolvedValueOnce(
      jsonResponse([
        {
          id: 1,
          name: "Sub2API",
          protocol: "openai",
          endpoint: "http://192.168.0.109:28765/v1",
          masked: "sk-6••••••••e7a4",
          enabled: true,
          models: ["gpt-5.4-mini"],
          status: "ok",
        },
      ])
    );

    const providers = await modelsApi.listProviders();

    expect(providers).toHaveLength(1);
    const p = providers[0];
    expect(p.url).toBe("http://192.168.0.109:28765/v1");
    expect(p.masked).toBe("sk-6••••••••e7a4");
    expect(p.status).toBe("ok");
    expect(p.models).toEqual(["gpt-5.4-mini"]);
  });

  it("createProvider posts full payload", async () => {
    mockFetch.mockResolvedValueOnce(
      jsonResponse({
        id: 2,
        name: "DeepSeek",
        protocol: "openai",
        endpoint: "https://api.deepseek.com/v1",
        masked: "sk-d••••••••1a9b",
        enabled: true,
        models: ["deepseek-chat"],
        status: "untested",
      })
    );

    await modelsApi.createProvider({
      name: "DeepSeek",
      protocol: "openai",
      endpoint: "https://api.deepseek.com/v1",
      apiKey: "sk-raw",
      models: ["deepseek-chat"],
    });

    const [url, init] = mockFetch.mock.calls[0];
    expect(url).toBe("/api/models/providers");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body)).toEqual({
      name: "DeepSeek",
      protocol: "openai",
      endpoint: "https://api.deepseek.com/v1",
      apiKey: "sk-raw",
      models: ["deepseek-chat"],
    });
  });

  it("updateProvider sends partial patch", async () => {
    mockFetch.mockResolvedValueOnce(
      jsonResponse({
        id: 3, name: "X", protocol: "openai", endpoint: "http://e",
        masked: "—", enabled: false, models: [], status: "untested",
      })
    );

    await modelsApi.updateProvider(3, { enabled: false });

    const [url, init] = mockFetch.mock.calls[0];
    expect(url).toBe("/api/models/providers/3");
    expect(init.method).toBe("PUT");
    expect(JSON.parse(init.body)).toEqual({ enabled: false });
  });

  it("deleteProvider issues DELETE", async () => {
    mockFetch.mockResolvedValueOnce(jsonResponse(null));

    await modelsApi.deleteProvider(5);

    expect(mockFetch.mock.calls[0][0]).toBe("/api/models/providers/5");
    expect(mockFetch.mock.calls[0][1].method).toBe("DELETE");
  });

  it("testProvider returns status payload", async () => {
    mockFetch.mockResolvedValueOnce(jsonResponse({ status: "ok", error: null }));

    const result = await modelsApi.testProvider(9);

    expect(result.status).toBe("ok");
    expect(mockFetch.mock.calls[0][0]).toBe("/api/models/providers/9/test");
    expect(mockFetch.mock.calls[0][1].method).toBe("POST");
  });
});
