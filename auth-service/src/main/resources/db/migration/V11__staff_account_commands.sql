CREATE TABLE staff_account_commands (
  actor_id bigint NOT NULL, command_key uuid NOT NULL,
  kind varchar(16) NOT NULL, fingerprint varchar(64) NOT NULL,
  user_id bigint NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(actor_id, command_key)
);
