-- 文件中心文件夹(2026-09-17):用户组织上传文件的目录结构。
--
-- 为什么需要:文件中心此前是平铺列表——「新建文件夹」是假按钮,没有
-- 重命名/移动,用户无法把"项目资料"与"临时上传"分开。文件夹让它从
-- "上传暂存区"变成真正的知识资产库(与 RAG 索引/对话引用配套)。
--
-- 设计:
--   - 单层结构(v1):folder_id NULL = 根目录;不做无限层级(个人工作台
--     的文件量级用扁平目录足够,多层级徒增交互与维护成本);
--   - 删除文件夹 = 其中的文件回到根目录(不连带删除文件——软删除的
--     教训:数据不真丢);
--   - name 在同一父级内唯一(UNIQUE 约束含 NULL 语义处理见下)。
CREATE TABLE file_folder (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(120) NOT NULL,
    created_at TIMESTAMP DEFAULT now()
);

-- 同一父级(这里只有根)内文件夹名不重复;NULL 不参与 UNIQUE,当前无层级,
-- 直接全表唯一即可。
ALTER TABLE file_folder ADD CONSTRAINT uq_file_folder_name UNIQUE (name);

-- 文件归属文件夹:NULL = 根目录。删除文件夹时置回 NULL(不级联删除)。
ALTER TABLE file_item ADD COLUMN IF NOT EXISTS folder_id BIGINT
    REFERENCES file_folder(id) ON DELETE SET NULL;

COMMENT ON TABLE file_folder IS '文件中心文件夹(单层;删除文件夹时文件回到根目录)';
COMMENT ON COLUMN file_item.folder_id IS '所属文件夹;NULL=根目录';
