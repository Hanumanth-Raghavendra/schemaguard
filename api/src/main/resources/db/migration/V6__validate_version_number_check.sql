-- Checks the existing rows against the constraint V5 added, without blocking reads or writes.
ALTER TABLE schema_version VALIDATE CONSTRAINT schema_version_number_positive;
