CREATE TABLE reviews (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    branch_id bigint NOT NULL CHECK (branch_id > 0),
    court_id bigint NOT NULL CHECK (court_id > 0),
    booking_id bigint NOT NULL UNIQUE CHECK (booking_id > 0),
    user_id bigint NOT NULL CHECK (user_id > 0),
    rating smallint NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment varchar(2000),
    image_urls jsonb NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(image_urls)='array' AND jsonb_array_length(image_urls)<=5),
    admin_reply varchar(2000),
    reply_author_id bigint CHECK (reply_author_id > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    replied_at timestamptz,
    CHECK ((admin_reply IS NULL AND replied_at IS NULL AND reply_author_id IS NULL)
        OR (admin_reply IS NOT NULL AND length(btrim(admin_reply))>0 AND replied_at IS NOT NULL AND reply_author_id IS NOT NULL))
);
CREATE INDEX reviews_branch_page ON reviews(branch_id,created_at DESC,id DESC);

CREATE TABLE branch_review_stats (
    branch_id bigint PRIMARY KEY CHECK (branch_id > 0),
    review_count bigint NOT NULL CHECK (review_count > 0),
    rating_sum bigint NOT NULL,
    star_1 bigint NOT NULL CHECK (star_1 >= 0),
    star_2 bigint NOT NULL CHECK (star_2 >= 0),
    star_3 bigint NOT NULL CHECK (star_3 >= 0),
    star_4 bigint NOT NULL CHECK (star_4 >= 0),
    star_5 bigint NOT NULL CHECK (star_5 >= 0),
    CHECK (star_1+star_2+star_3+star_4+star_5=review_count),
    CHECK (star_1+2*star_2+3*star_3+4*star_4+5*star_5=rating_sum)
);
CREATE TABLE review_cache_version (
    id integer PRIMARY KEY CHECK (id=1),
    version bigint NOT NULL CHECK (version >= 0)
);
INSERT INTO review_cache_version(id,version) VALUES (1,0);
