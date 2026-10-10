-- Renames the per-preset compaction flag to automatic-only semantics.
--
-- The flag no longer gates compaction as a whole: it restrains only the threshold-triggered
-- (automatic) summarization, ANDed with the per-user preference, while user-requested compaction
-- remains available regardless. `RENAME COLUMN` preserves the column's type, nullability, default and
-- every stored value, so a preset that had compaction switched off stays switched off and a row that
-- relied on the default keeps `1`. No data rewrite, no index or constraint references the column.
--
-- Downgrade is not supported: older code reads the column by its previous name and would fall back to
-- the "compaction allowed" default of a fresh column.

ALTER TABLE model_presets RENAME COLUMN compaction_enabled TO automatic_compaction_enabled;
