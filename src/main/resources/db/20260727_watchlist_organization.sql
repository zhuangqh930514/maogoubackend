-- Persistent pinning and query index for large personal watchlists.
SET @schema_name = DATABASE();

SET @sql = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema=@schema_name AND table_name='watch_stock' AND column_name='pinned'),
    'SELECT 1',
    'ALTER TABLE watch_stock ADD COLUMN pinned TINYINT NOT NULL DEFAULT 0 AFTER priority'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.statistics
    WHERE table_schema=@schema_name AND table_name='watch_stock' AND index_name='idx_watch_stock_user_pinned_order'),
    'SELECT 1',
    'CREATE INDEX idx_watch_stock_user_pinned_order ON watch_stock (user_id, deleted, pinned, priority, created_at)'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
