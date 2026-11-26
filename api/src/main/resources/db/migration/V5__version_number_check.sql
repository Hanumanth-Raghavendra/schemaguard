-- Versions are numbered from 1.
ALTER TABLE schema_version ADD CONSTRAINT schema_version_number_positive CHECK (number > 0);
