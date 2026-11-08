-- Branch photo gallery (SCRUM-47). url is the CDN link served to clients; public_id is the storage
-- provider's handle, needed to delete the remote file.
CREATE TABLE public.branch_images (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    branch_id bigint NOT NULL REFERENCES public.branches(id) ON DELETE CASCADE,
    url text NOT NULL,
    public_id text NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamp with time zone NOT NULL DEFAULT now()
);
CREATE INDEX ix_branch_images_branch ON public.branch_images (branch_id, sort_order, id);
