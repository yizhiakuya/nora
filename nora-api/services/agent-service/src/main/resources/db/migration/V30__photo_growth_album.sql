-- 新增技能，不覆盖用户已创建的同名技能。
INSERT INTO agent_skill (name, description, instructions, category)
SELECT '照片成长相册', '从真实照片挑选代表图，生成离线网页相册；记住明确的风格反馈，支持按内容去重的增量更新', $skill$
# 照片成长相册

## 做什么

结合手机相册或用户提供的照片，制作有时间顺序、真实观察和简洁故事的成长相册。交付真实、自包含的 HTML 文件；源照片保持原样。用户纠正风格后即时更新相册，明确要求以后沿用时才保存偏好。以后有新照片可以再次增量更新。

## 开始前

1. manage_photo_album(action=list) 查看现有相册和 preferences。已有相册更新用 read，拿到最新 revision 和照片清单；不要新建重复相册。
2. 明确主体、时间范围和照片来源。用户已经说清时直接执行；缺少会影响选片的信息才用 ask_user 简短询问。
3. 使用真实可用的 MCP 工具：manage_mcp list 找手机服务器，tools 获取精确 schema。手机离线或工具失败时如实说明，可使用已导入的照片；不编造手机在线、检索结果或照片。
4. 已记住的 preferences.rule 是用户的相册偏好。新相册的文案语气按 preferences.style.captionTone（plain=朴素简洁，warm=温暖克制）处理；当前用户要求优先。

## 选片

- 先按相册和时间范围搜索，再用手机照片拼图/看图工具或 manage_file read 实际查看候选。只对已经看到的照片描述内容。
- 从不同阶段选代表图，避免近似连拍占满名额，最多 24 张。日期必须来自工具元数据；没有可靠拍摄日期就省略 date，不把文件下载时间、文件名猜测当拍摄日期。
- 同一主体的识别有疑问时说明并请用户选择；不要从照片推断健康状况、精确年龄、品种或没有证据的成长结论。
- 手机照片用现有 fetch_media 下载选中的 URL 到工作区，再根据真实返回路径使用 workspace:引用。不要凭空构造 URL；下载失败不能当已获得素材。
- 文件中心图片用 file:id，已缓存图片用 media:键，工作区图片用 workspace:相对路径。不得传任意 URL 或绝对路径。

## 创建与交付

manage_photo_album(action=create, album="主体-年份的英文标识", title="相册标题", summary="基于真实观察的整体说明", photos=[{target:"真实文件引用", date:"YYYY-MM-DD，可省略", caption:"实际看图后的描述"}])。

可以明确指定 style={theme:"paper|night", layout:"timeline|grid", captionTone:"plain|warm"}；没有新要求时省略以继承已记住的风格。

生成器负责校验、内容哈希去重、按日期排序、图像缩放和内嵌。支持 JPEG/PNG/GIF 的静态预览；格式不支持时如实说明，不伪造转换成功。默认 paper + timeline + warm。

成功后调用 open_file(targets=[返回的 htmlTarget], intent="deliver")。它会提供可查看、下载的原生文件卡片并登记已保存成果。最终回答简短说明选了多少张、哪些日期未记录，附文件入口即可。

不要自行手写另一套 HTML 相册，不使用远程图片链接、第三方 CDN 或脚本。不要把生成成功当交付成功，检查 open_file 的实际结果。

## 用户纠正风格

先 read 拿到最新 revision。将用户原话放在 feedback 字段，将明确的主题、布局、语气要求映射到 style。

如果用户要求改文案，基于 read.document.photos 中的 hash 和原文，结合已看过的照片重新写 captions=[{hash:"已有 hash",caption:"修改后的描述"}]。然后一次 feedback 调用同时传 revision、feedback、style 和 captions，当前相册立即生成新版本。

只有用户明确说“以后都这样”“记住这个风格”等，才传 remember=true；否则只修改当前相册。保存的是可查看、可撤销的工作区偏好，不声称已训练模型。若要修改整套流程，可用现有 manage_skill 创建用户专属技能，但不要自动改写本技能和权限规则。

成功后再次 open_file 交付新版本，并说明哪些具体要求已经记住。旧版仍可打开。

## 追加照片

先 read，再仅检索尚未覆盖的时间范围；仍要真实看图与选片。update 使用同一 album 和最新 revision，photos 只传新增候选。内容重复会跳过，已有照片保持；需要改写旧文案时显式传 captions。

409 表示版本已变化，重新 read 后判断实际进度再继续；不要反复重放旧 revision。所有照片验证成功后才提交当前清单，错误要如实报告。

默认由用户再次提出需求触发更新。用户明确希望定期更新时，使用既有 manage_automation 创建带有效 schedule 的任务，指令须保留同一 album、范围和选择规则；不声称已经自动监听手机新增照片。
$skill$, '生活'
WHERE NOT EXISTS (SELECT 1 FROM agent_skill WHERE name = '照片成长相册' AND deleted_at IS NULL);

UPDATE agent_skill SET instructions = instructions || E'\n\n## 照片成长相册\n使用「照片成长相册」技能选片，再调用 manage_photo_album（list/read/create/update/feedback）。生成固定工作区内的独立 HTML，增量按照片内容去重，反馈支持调整当前文案/样式和明确记住偏好；修改必须带最新 revision。生成后用 open_file intent=deliver 交付。\n', updated_at = now()
WHERE name = '工作台使用手册' AND deleted_at IS NULL;
