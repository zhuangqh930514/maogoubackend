-- Exact point-in-time evidence fingerprints used by each frozen training row.

ALTER TABLE ai_training_dataset_item
    ADD COLUMN universe_fingerprint VARCHAR(128) NULL AFTER label_fingerprint,
    ADD COLUMN trading_state_fingerprint VARCHAR(128) NULL AFTER universe_fingerprint,
    ADD KEY idx_training_dataset_item_pit_lineage
        (training_dataset_id, universe_fingerprint, trading_state_fingerprint);
