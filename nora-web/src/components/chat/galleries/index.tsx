'use client';

import type { ComponentType } from "react";
import { MediaGallery } from "./MediaGallery";
import { FilesGallery } from "./FilesGallery";
import { TableGallery } from "./TableGallery";
import { DiffGallery } from "./DiffGallery";
import { TimelineGallery } from "./TimelineGallery";
import { KeyValueGallery } from "./KeyValueGallery";
import { TextGallery } from "./TextGallery";
import { ListGallery } from "./ListGallery";
import type { ArtifactGallery } from "@/lib/artifacts";

/**
 * 画廊注册表(2026-09-18 v4,按业务分画廊)。
 *
 * 每种业务一个独立组件(本目录);协议里 gallery 字段声明用哪个。
 * 新增业务画廊 = 加一个组件文件 + 这里注册一行(互不影响,无共享状态)。
 * 未知 gallery 名 → 降级 ListGallery(前向兼容,永不报错)。
 */
const REGISTRY: Record<string, ComponentType<{ data: Record<string, unknown> }>> = {
  media: MediaGallery,
  files: FilesGallery,
  table: TableGallery,
  diff: DiffGallery,
  timeline: TimelineGallery,
  keyvalue: KeyValueGallery,
  text: TextGallery,
  list: ListGallery,
};

/** 渲染一条画廊实例(按 gallery 名查注册表;未知降级 list)。 */
export function ArtifactsBlock({ gallery }: { gallery: ArtifactGallery }) {
  const Component = REGISTRY[gallery.gallery] ?? ListGallery;
  return <Component data={gallery.data} />;
}
