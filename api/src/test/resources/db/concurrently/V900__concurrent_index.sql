-- Only for DatabaseMigrationTest: what SG-R101 advises, which under Flyway's default
-- transactional lock waits forever for Flyway's own transaction (D-115).
CREATE INDEX CONCURRENTLY analysis_created_at_idx ON analysis (created_at);
