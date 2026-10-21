import { fireEvent, render, screen } from "@testing-library/react";
import { FileImage } from "lucide-react";
import { it } from "vitest";
import { FileGrid } from "./FileGrid";
import { useSelection } from "@/hooks/useSelection";
import type { FileItem } from "@/types";

const files: FileItem[] = [{ id: 1, name: "photo.png", type: "image", size: "1 KB", date: "2026-10-13", icon: FileImage, color: "text-muted-foreground", indexed: false }];

it("handles thumbnail failure and keyboard-accessible file selection", () => {
  function Grid() {
    const selection = useSelection(files, "id");
    return <FileGrid files={files} selection={selection} onOpen={() => undefined} folderRows={[{ name: "项目资料", description: "文件夹", onOpen: () => undefined }]} />;
  }
  const view = render(<Grid />);
  fireEvent.error(screen.getByRole("img", { name: "photo.png" }));
  fireEvent.click(screen.getByRole("button", { name: "选择 photo.png" }));
  fireEvent.click(screen.getByRole("button", { name: "选择 photo.png" }));
  fireEvent.click(screen.getByRole("button", { name: "项目资料 文件夹" }));
  view.unmount();
});
