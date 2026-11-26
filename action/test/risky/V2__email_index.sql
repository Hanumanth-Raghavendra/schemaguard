-- Builds the index without CONCURRENTLY, so writes to customers wait until it is done.
CREATE INDEX customers_email ON customers (email);
