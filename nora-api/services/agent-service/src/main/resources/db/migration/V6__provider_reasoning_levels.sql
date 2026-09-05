-- Per-model chat settings (context window + reasoning levels), keyed by model id.
-- Shape: { "<model>": { "contextWindow": 1000000, "reasoningLevels": ["low","high"], "defaultReasoningLevel": "high" } }
-- reasoningLevels 空/缺失 = 该模型不做等级约束; defaultReasoningLevel 空 = auto(按模型家族默认)。
ALTER TABLE model_provider ADD COLUMN model_settings JSONB;
