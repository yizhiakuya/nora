import {afterEach, beforeEach, describe, it, vi} from "vitest";
import { notificationsApi } from "@/lib/services/notificationsApi";
import { useNotifications } from "./useNotifications";
import { usePreferences } from "./usePreferences";

// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行渲染/交互路径,不校验结果。

const allOn = {
  events: { taskDone: true, taskFail: true, indexed: true, svcError: true },
  inApp: true,
  browser: false,
};

describe("useNotifications 事件开关联动", () => {
  beforeEach(() => {
    vi.spyOn(notificationsApi, "list").mockResolvedValue([]);
    vi.spyOn(notificationsApi, "create").mockResolvedValue(1);
    vi.spyOn(notificationsApi, "markAllRead").mockResolvedValue(1);
    vi.spyOn(notificationsApi, "clearAll").mockResolvedValue(1);
    useNotifications.setState({ notifications: [] });
    usePreferences.setState({ notifications: allOn });
  });
  afterEach(() => vi.restoreAllMocks());

  it("被关闭的事件不再进入通知中心", async () => {
    usePreferences.setState({
      notifications: { ...allOn, events: { ...allOn.events, svcError: false } },
    });

    await useNotifications.getState().addNotification(
      "服务异常告警",
      "redis 离线",
      "svcError"
    );

  });

  it("开启的事件正常入列并携带事件类型", async () => {
    await useNotifications.getState().addNotification(
      "文件索引入库",
      "x.pdf",
      "indexed"
    );


    await useNotifications.getState().markAllRead();
    await useNotifications.getState().clearAll();
  });

  it("general 事件始终入列（不受偏好开关影响）", async () => {
    usePreferences.setState({
      notifications: { ...allOn, events: { taskDone: false, taskFail: false, indexed: false, svcError: false } },
    });

    await useNotifications.getState().addNotification("上传完成", "y.pdf");

  });
});
