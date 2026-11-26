-- Versions are numbered from 1. NOT VALID adds the constraint without reading the rows;
-- V6 validates them under a lock that lets reads and writes continue.
SET lock_timeout = '5s';
ALTER TABLE schema_version ADD CONSTRAINT schema_version_number_positive CHECK (number > 0) NOT VALID;
