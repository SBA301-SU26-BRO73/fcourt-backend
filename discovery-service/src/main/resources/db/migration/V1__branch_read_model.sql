CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;

-- Immutable wrapper is deliberately avoided: normalize at write/query time.
CREATE TABLE branch_read_model (
    id BIGINT PRIMARY KEY,
    version BIGINT NOT NULL CHECK (version >= 1),
    name TEXT NOT NULL,
    address TEXT NOT NULL,
    city TEXT NOT NULL,
    district TEXT,
    search_text TEXT NOT NULL,
    city_key TEXT NOT NULL,
    district_key TEXT,
    latitude DOUBLE PRECISION CHECK (latitude BETWEEN -90 AND 90),
    longitude DOUBLE PRECISION CHECK (longitude BETWEEN -180 AND 180),
    active BOOLEAN NOT NULL,
    CHECK ((latitude IS NULL) = (longitude IS NULL))
);
CREATE INDEX branch_search_trgm ON branch_read_model USING gin (search_text gin_trgm_ops) WHERE active;
CREATE INDEX branch_location ON branch_read_model (city_key, district_key, id) WHERE active;
CREATE TABLE court_read_model (
    branch_id BIGINT NOT NULL REFERENCES branch_read_model(id) ON DELETE CASCADE,
    id BIGINT NOT NULL,
    court_type_id BIGINT NOT NULL,
    min_price NUMERIC(12,2) CHECK (min_price >= 0),
    max_price NUMERIC(12,2) CHECK (max_price >= min_price),
    PRIMARY KEY (branch_id, id),
    CHECK ((min_price IS NULL) = (max_price IS NULL))
);
CREATE INDEX court_search ON court_read_model (court_type_id, branch_id, min_price, max_price);
