ALTER TABLE staff ADD COLUMN email varchar(255);
ALTER TABLE staff ADD COLUMN full_name varchar(150);
ALTER TABLE staff ADD COLUMN phone varchar(20);
CREATE INDEX idx_staff_branch_id ON staff(branch_id, id);

CREATE TABLE staff_work_shifts (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    staff_id bigint NOT NULL REFERENCES staff(id) ON DELETE CASCADE,
    day_of_week smallint NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    start_time time NOT NULL,
    end_time time NOT NULL,
    CHECK (end_time > start_time),
    UNIQUE (staff_id, day_of_week, start_time)
);

CREATE TABLE staff_commands (
    actor_id bigint NOT NULL,
    command_key uuid NOT NULL,
    kind varchar(20) NOT NULL,
    fingerprint varchar(64) NOT NULL,
    staff_id bigint NOT NULL REFERENCES staff(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (actor_id, command_key)
);

CREATE TABLE staff_audit (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id bigint NOT NULL,
    staff_id bigint NOT NULL REFERENCES staff(id),
    action varchar(20) NOT NULL,
    command_key uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(actor_id, command_key)
);
