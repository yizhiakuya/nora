UPDATE agent_skill SET instructions = instructions || $workflow$

## 网页任务表单与样本查看

用户从网页任务表单发起时已有服务端 planId、sourceUrl、columns、minRows。先 state 查看 task，沿用该计划，不能另建或降低标准。用中文说明当前步骤和结果，不向用户叙述 selector、planId 或工具参数。

previewFirst=true 且 review.approved=false 时：打开起始网址、查看真实页面、确认符合用户目标的行，只 extract 至多三条样本（数量小于三时按目标数量）。sampleRevision 对应本次样本；服务端会限制总样本数量。随后结束本轮，简短告知用户可以打开任务卡里的样本查看；不要继续采集、export、learn、open_file 或试图自行确认样本。

用户在界面重新采样后：state 查看 task.review.feedback 和新的 sampleRevision，使用原 planId 按调整要求重新采集样本，随后结束本轮等待查看。用户确认继续后：state 查看 review.approved 和反馈，按同一计划重新采全量，通常用全量 captureIds 导出（不要把已经重新采过的样本叠加为重复行）。uniqueRows=true 时不能有完全重复资料行；可 rows(planId/receiptId, offset, limit) 查看真实行和重复/缺失列表，export 的 rowIds 可保留真实行子集。

用户调整结果表格后会创建新版本。补采时保留最新结果中用户选择的内容，不重新纳入已排除行；服务端同一计划仍过滤这些内容。不能减少 minRows 或删掉必需字段来冒充完成。网页没有足够有效资料时明确报告实际数量和缺失项，可以交付未达标的部分结果，但不能称已验收通过。

表单任务 export 后，CSV 和验收单自动登记到已有成果列表，用户从任务结果表格查看与下载；完成回复只需说明来源、有效条数和待处理事项，不必自动 open_file 遮住任务页面。直接在聊天里发起的其他任务仍可用 open_file intent=deliver 交付。
$workflow$, updated_at = now()
WHERE name = '浏览器资料收集' AND deleted_at IS NULL;

UPDATE agent_skill SET instructions = instructions || E'\n\n## 网页资料任务入口\n首页「网页资料收集」可以填写网址、目标、字段和数量；默认先查看最多三条样本。对话上方任务卡显示实际进度，用户可调整要求重新采样或确认继续。结果表格可选行、去重、保存新 CSV 版本与下载；数量不足或字段缺失如实提示并可补采，冻结标准不降低。浏览器由用户展开查看和接管，任务与成果沿用现有会话、成果体系。\n', updated_at = now()
WHERE name = '工作台使用手册' AND deleted_at IS NULL;
