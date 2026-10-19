-- staff-operations-service (staff_db) - du lieu mau tach tu ban cu (V7, V8, V17, V20...).

\restrict yTvU0VvaY39rrk27ul8AHSvwMZoGFc3ERd8xqwmK21YnPFazcf3gQ3lVgDtFLFC

INSERT INTO public.staff (id, user_id, branch_id, created_at, updated_at, deleted_at) OVERRIDING SYSTEM VALUE VALUES
	(1, 12, 1, '2026-10-19 15:13:44.485677+00', '2026-10-19 15:13:44.485677+00', NULL),
	(2, 11, 2, '2026-10-19 15:13:44.485677+00', '2026-10-19 15:13:44.485677+00', NULL),
	(3, 15, 9, '2026-10-19 15:13:44.485677+00', '2026-10-19 15:13:44.485677+00', NULL),
	(4, 14, 15, '2026-10-19 15:13:44.485677+00', '2026-10-19 15:13:44.485677+00', NULL),
	(5, 13, 16, '2026-10-19 15:13:44.485677+00', '2026-10-19 15:13:44.485677+00', NULL);

SELECT pg_catalog.setval('public.staff_id_seq', 5, true);

\unrestrict yTvU0VvaY39rrk27ul8AHSvwMZoGFc3ERd8xqwmK21YnPFazcf3gQ3lVgDtFLFC
