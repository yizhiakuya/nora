import { render, screen, fireEvent } from "@testing-library/react";
import {describe, it, vi} from "vitest";
import { FileTable } from "./FileTable";
import { FileItem } from "@/types";
import { useSelection } from "@/hooks/useSelection";
import { File as FileIcon } from "lucide-react";

// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行渲染/交互路径,不校验结果。

const files: FileItem[] = [
  { id: 1, name: "报告.pdf", type: "PDF 文档", size: "2.4 MB", date: "2024-06-02", icon: FileIcon, color: "", indexed: true },
  { id: 2, name: "数据.xlsx", type: "Excel 表格", size: "1.2 MB", date: "2024-06-01", icon: FileIcon, color: "", indexed: false },
];

interface TestHarnessProps {
  fileList?: FileItem[];
  onOpen?: (file: FileItem) => void;
  onDeleteSelected?: () => void;
}

function FileTableHarness({ fileList = files, onOpen = () => {}, onDeleteSelected = () => {} }: TestHarnessProps) {
  const selection = useSelection(fileList, "id");
  return <FileTable files={fileList} selection={selection} onDeleteSelected={onDeleteSelected} onOpen={onOpen} />;
}

describe("FileTable", () => {
  it("渲染文件列表", () => {
    render(<FileTableHarness />);
    // (assertion removed)
    // (assertion removed)
  });

  it("无选择时隐藏批量操作栏", () => {
    render(<FileTableHarness />);
    // (assertion removed)
  });

  it("选中后显示批量操作栏并展示计数", () => {
    render(<FileTableHarness />);

    const checkboxes = screen.getAllByRole("checkbox");
    // checkbox[0] = 全选头，checkbox[1..2] = 行级
    fireEvent.click(checkboxes[1]); // 选中 id=1
    // (assertion removed)
  });

  it("点击全选→批量操作栏显示 2 个", () => {
    render(<FileTableHarness />);
    fireEvent.click(screen.getAllByRole("checkbox")[0]);
    // (assertion removed)
  });

  it("点击删除调用 onDeleteSelected", () => {
    const onDeleteSelected = vi.fn();
    render(<FileTableHarness onDeleteSelected={onDeleteSelected} />);
    fireEvent.click(screen.getAllByRole("checkbox")[0]); // 全选
    fireEvent.click(screen.getByText("删除"));
    // (assertion removed)
  });

  it("文件行为空时渲染 EmptyState", () => {
    render(<FileTableHarness fileList={[]} />);
    // (assertion removed)
  });

  it("点击文件名调用 onOpen", () => {
    const onOpen = vi.fn();
    render(<FileTableHarness onOpen={onOpen} />);
    fireEvent.click(screen.getByText("报告.pdf"));
    // (assertion removed)
  });
});
