ALTER TABLE users ADD COLUMN google_sub varchar(255);
ALTER TABLE users ADD COLUMN avatar_url text;
CREATE UNIQUE INDEX uq_users_google_sub ON users (google_sub) WHERE google_sub IS NOT NULL;
