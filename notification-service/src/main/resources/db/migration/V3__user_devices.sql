CREATE TABLE user_devices (
    id bigserial PRIMARY KEY,
    user_id bigint NOT NULL,
    device_id varchar(128) NOT NULL,
    platform varchar(16) NOT NULL,
    fcm_token varchar(512) NOT NULL,
    endpoint_arn varchar(512),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_devices_user_device UNIQUE (user_id, device_id)
);

CREATE INDEX idx_user_devices_user_id ON user_devices (user_id);
