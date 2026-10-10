-- 拼图只作粗筛；真实验收发现格号可能错配，生成前必须逐张核对已选原图。
UPDATE agent_skill
SET instructions = instructions || E'\n\n## 最终逐张核对\n\n拼图用于筛选候选，不作为最终文案的唯一依据。手机选片下载到工作区后，必须逐张 manage_file(action=read, path="实际返回的 workspace:引用") 查看最终入册图片；一次读取一张，核对该文件对应的主体、动作和背景，再写它的 caption。元数据日期仍按同一照片的 id 对应，不能混用拼图格号。不要只 list 文件或凭另一格照片的印象写文案。看不到图时如实说明，不生成声称已经看图的文案。增量更新的新增照片也执行此步骤。\n',
    updated_at = now()
WHERE name = '照片成长相册' AND deleted_at IS NULL
  AND instructions LIKE '%生成器负责校验、内容哈希去重、时间排序、图像缩放和内嵌%'
  AND instructions NOT LIKE '%## 最终逐张核对%';
