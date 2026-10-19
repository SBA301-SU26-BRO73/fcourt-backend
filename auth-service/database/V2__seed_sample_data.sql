-- auth-service (auth_db) - du lieu mau tach tu ban cu (V7, V8, V17, V20...).

\restrict R28qvp9btVOzAnZTTgJ0mq9EKc9Nk1fSMioqMHyPW4BA8sCINYWkAfEWYLS6NU0

INSERT INTO public.users (id, email, password_hash, phone, role, status, created_at, updated_at, deleted_at, full_name) OVERRIDING SYSTEM VALUE VALUES
	(1, 'owner01@demo.com', '$2a$10$BQCthYGJ5LA3C3PaK9WqC.Hg14/ABgM.nVwWmQH4AyIq4FPz/nBAC', '0909000001', 'ADMIN', 'ACTIVE', '2026-10-19 15:13:44.44398+00', '2026-10-19 15:13:44.44398+00', NULL, 'Admin Một'),
	(2, 'owner02@demo.com', '$2a$10$BQCthYGJ5LA3C3PaK9WqC.Hg14/ABgM.nVwWmQH4AyIq4FPz/nBAC', '0909000002', 'ADMIN', 'ACTIVE', '2026-10-19 15:13:44.44398+00', '2026-10-19 15:13:44.44398+00', NULL, 'Admin Hai'),
	(3, 'owner03@demo.com', '$2a$10$BQCthYGJ5LA3C3PaK9WqC.Hg14/ABgM.nVwWmQH4AyIq4FPz/nBAC', '0909000003', 'ADMIN', 'ACTIVE', '2026-10-19 15:13:44.44398+00', '2026-10-19 15:13:44.44398+00', NULL, 'Admin Ba'),
	(4, 'owner04@demo.com', '$2a$10$BQCthYGJ5LA3C3PaK9WqC.Hg14/ABgM.nVwWmQH4AyIq4FPz/nBAC', '0909000004', 'ADMIN', 'ACTIVE', '2026-10-19 15:13:44.44398+00', '2026-10-19 15:13:44.44398+00', NULL, 'Admin Bốn'),
	(5, 'owner05@demo.com', '$2a$10$BQCthYGJ5LA3C3PaK9WqC.Hg14/ABgM.nVwWmQH4AyIq4FPz/nBAC', '0909000005', 'ADMIN', 'ACTIVE', '2026-10-19 15:13:44.44398+00', '2026-10-19 15:13:44.44398+00', NULL, 'Admin Năm'),
	(6, 'customer03@demo.com', '$2a$10$BQCthYGJ5LA3C3PaK9WqC.Hg14/ABgM.nVwWmQH4AyIq4FPz/nBAC', '0919000003', 'CUSTOMER', 'ACTIVE', '2026-10-19 15:13:44.452377+00', '2026-10-19 15:13:44.452377+00', NULL, 'Khách Ba'),
	(7, 'customer04@demo.com', '$2a$10$BQCthYGJ5LA3C3PaK9WqC.Hg14/ABgM.nVwWmQH4AyIq4FPz/nBAC', '0919000004', 'CUSTOMER', 'ACTIVE', '2026-10-19 15:13:44.452377+00', '2026-10-19 15:13:44.452377+00', NULL, 'Khách Bốn'),
	(8, 'customer05@demo.com', '$2a$10$BQCthYGJ5LA3C3PaK9WqC.Hg14/ABgM.nVwWmQH4AyIq4FPz/nBAC', '0919000005', 'CUSTOMER', 'ACTIVE', '2026-10-19 15:13:44.452377+00', '2026-10-19 15:13:44.452377+00', NULL, 'Khách Năm'),
	(9, 'customer02@demo.com', '$2a$10$BQCthYGJ5LA3C3PaK9WqC.Hg14/ABgM.nVwWmQH4AyIq4FPz/nBAC', '0919000002', 'CUSTOMER', 'ACTIVE', '2026-10-19 15:13:44.452377+00', '2026-10-19 15:13:44.452377+00', NULL, 'Khách Hai'),
	(10, 'customer01@demo.com', '$2a$10$BQCthYGJ5LA3C3PaK9WqC.Hg14/ABgM.nVwWmQH4AyIq4FPz/nBAC', '0919000001', 'CUSTOMER', 'ACTIVE', '2026-10-19 15:13:44.452377+00', '2026-10-19 15:13:44.452377+00', NULL, 'Khách Một'),
	(11, 'staff02@demo.com', '$2a$10$BQCthYGJ5LA3C3PaK9WqC.Hg14/ABgM.nVwWmQH4AyIq4FPz/nBAC', '0929000002', 'STAFF', 'ACTIVE', '2026-10-19 15:13:44.458799+00', '2026-10-19 15:13:44.458799+00', NULL, 'Nhân Viên Hai'),
	(12, 'staff01@demo.com', '$2a$10$BQCthYGJ5LA3C3PaK9WqC.Hg14/ABgM.nVwWmQH4AyIq4FPz/nBAC', '0929000001', 'STAFF', 'ACTIVE', '2026-10-19 15:13:44.458799+00', '2026-10-19 15:13:44.458799+00', NULL, 'Nhân Viên Một'),
	(13, 'staff05@demo.com', '$2a$10$BQCthYGJ5LA3C3PaK9WqC.Hg14/ABgM.nVwWmQH4AyIq4FPz/nBAC', '0929000005', 'STAFF', 'ACTIVE', '2026-10-19 15:13:44.458799+00', '2026-10-19 15:13:44.458799+00', NULL, 'Nhân Viên Năm'),
	(14, 'staff04@demo.com', '$2a$10$BQCthYGJ5LA3C3PaK9WqC.Hg14/ABgM.nVwWmQH4AyIq4FPz/nBAC', '0929000004', 'STAFF', 'ACTIVE', '2026-10-19 15:13:44.458799+00', '2026-10-19 15:13:44.458799+00', NULL, 'Nhân Viên Bốn'),
	(15, 'staff03@demo.com', '$2a$10$BQCthYGJ5LA3C3PaK9WqC.Hg14/ABgM.nVwWmQH4AyIq4FPz/nBAC', '0929000003', 'STAFF', 'ACTIVE', '2026-10-19 15:13:44.458799+00', '2026-10-19 15:13:44.458799+00', NULL, 'Nhân Viên Ba');

SELECT pg_catalog.setval('public.user_account_action_logs_id_seq', 1, false);

SELECT pg_catalog.setval('public.user_documents_id_seq', 1, false);

SELECT pg_catalog.setval('public.users_id_seq', 15, true);

\unrestrict R28qvp9btVOzAnZTTgJ0mq9EKc9Nk1fSMioqMHyPW4BA8sCINYWkAfEWYLS6NU0
