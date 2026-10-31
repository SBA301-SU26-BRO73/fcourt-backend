-- Demo data for SCRUM-8, same style and demo password hash as V2:
-- one platform super admin and three court owners awaiting approval
-- with their business licence / identity documents.

INSERT INTO public.users (id, email, password_hash, phone, role, status, created_at, updated_at, deleted_at, full_name) OVERRIDING SYSTEM VALUE VALUES
	(16, 'superadmin@demo.com', '$2a$10$BQCthYGJ5LA3C3PaK9WqC.Hg14/ABgM.nVwWmQH4AyIq4FPz/nBAC', '0939000001', 'SUPER_ADMIN', 'ACTIVE', '2026-10-19 15:13:44.44398+00', '2026-10-19 15:13:44.44398+00', NULL, 'Quản Trị Hệ Thống'),
	(17, 'owner06@demo.com', '$2a$10$BQCthYGJ5LA3C3PaK9WqC.Hg14/ABgM.nVwWmQH4AyIq4FPz/nBAC', '0909000006', 'ADMIN', 'PENDING_APPROVAL', '2026-10-20 08:00:00+00', '2026-10-20 08:00:00+00', NULL, 'Chủ Sân Sáu'),
	(18, 'owner07@demo.com', '$2a$10$BQCthYGJ5LA3C3PaK9WqC.Hg14/ABgM.nVwWmQH4AyIq4FPz/nBAC', '0909000007', 'ADMIN', 'PENDING_APPROVAL', '2026-10-20 09:00:00+00', '2026-10-20 09:00:00+00', NULL, 'Chủ Sân Bảy'),
	(19, 'owner08@demo.com', '$2a$10$BQCthYGJ5LA3C3PaK9WqC.Hg14/ABgM.nVwWmQH4AyIq4FPz/nBAC', '0909000008', 'ADMIN', 'PENDING_APPROVAL', '2026-10-20 10:00:00+00', '2026-10-20 10:00:00+00', NULL, 'Chủ Sân Tám');

INSERT INTO public.user_documents (user_id, url, doc_type, created_at) VALUES
	(17, 'https://res.cloudinary.com/fcourt-demo/image/upload/owner06/business-license.jpg', 'LEGAL', '2026-10-20 08:00:00+00'),
	(17, 'https://res.cloudinary.com/fcourt-demo/image/upload/owner06/citizen-id.jpg', 'LEGAL', '2026-10-20 08:00:00+00'),
	(17, 'https://res.cloudinary.com/fcourt-demo/image/upload/owner06/court-front.jpg', 'COURT_IMAGE', '2026-10-20 08:00:00+00'),
	(18, 'https://res.cloudinary.com/fcourt-demo/image/upload/owner07/business-license.jpg', 'LEGAL', '2026-10-20 09:00:00+00'),
	(18, 'https://res.cloudinary.com/fcourt-demo/image/upload/owner07/citizen-id.jpg', 'LEGAL', '2026-10-20 09:00:00+00'),
	(19, 'https://res.cloudinary.com/fcourt-demo/image/upload/owner08/business-license.jpg', 'LEGAL', '2026-10-20 10:00:00+00');

SELECT pg_catalog.setval('public.users_id_seq', 19, true);
