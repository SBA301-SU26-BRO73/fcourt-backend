-- Branch names are unique among live branches (rule of the monolith BranchService).
CREATE UNIQUE INDEX ux_branches_live_name ON public.branches (btrim(name)) WHERE deleted_at IS NULL;
