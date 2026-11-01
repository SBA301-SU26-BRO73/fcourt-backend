CREATE TABLE staff_reactivation_commands (
    actor_id bigint NOT NULL,
    command_key uuid NOT NULL,
    user_id bigint NOT NULL REFERENCES users(id),
    branch_id bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(actor_id,command_key)
);
