-- subscription-service (subscription_db) - du lieu mau tach tu ban cu (V7, V8, V17, V20...).

\restrict ngwAYJOVImIjIeJfMBPgbhr811Pz6MyWvdgWLp2IZSpFTN63hufghXiyVeA4E3o

INSERT INTO public.court_types (id, name, created_at, updated_at, name_en, description, icon, color, active, deleted_at) OVERRIDING SYSTEM VALUE VALUES
	(1, 'Pickleball', '2026-10-19 15:13:44.412303+00', '2026-10-19 15:13:44.412303+00', 'Pickleball', 'Sân pickleball tiêu chuẩn 6.1m × 13.4m', 'circle-dot', '#0EA5E9', true, NULL),
	(2, 'Bóng đá mini', '2026-10-19 15:13:44.412303+00', '2026-10-19 15:13:44.412303+00', 'Mini Football', 'Sân cỏ nhân tạo 5–7 người', 'trophy', '#65A30D', true, NULL),
	(3, 'Tennis', '2026-10-19 15:13:44.412303+00', '2026-10-19 15:13:44.412303+00', 'Tennis', 'Sân tennis ngoài trời / trong nhà', 'zap', '#D97706', true, NULL),
	(4, 'Bóng rổ', '2026-10-19 15:13:44.412303+00', '2026-10-19 15:13:44.412303+00', 'Basketball', 'Sân bóng rổ nửa sân / nguyên sân', 'star', '#7C3AED', true, NULL),
	(5, 'Bóng chuyền', '2026-10-19 15:13:44.412303+00', '2026-10-19 15:13:44.412303+00', 'Volleyball', 'Sân bóng chuyền trong nhà', 'layers', '#DB2777', false, NULL),
	(6, 'Cầu lông', '2026-10-19 15:13:44.412303+00', '2026-10-19 15:13:44.412303+00', 'Badminton', 'Sân cầu lông trong nhà, mặt sân thảm hoặc gỗ', 'feather', '#15803D', true, NULL);

INSERT INTO public.subscription_plans (id, name, max_courts, max_branches, is_active, created_at, updated_at, tagline, monthly_price, yearly_price, color, popular, deleted_at) OVERRIDING SYSTEM VALUE VALUES
	(1, 'Tiêu chuẩn', 15, 3, true, '2026-10-19 15:13:44.426884+00', '2026-10-19 15:13:44.426884+00', 'Phổ biến cho chuỗi vừa', 699000, 6990000, '#15803D', true, NULL),
	(2, 'Chuyên nghiệp', 99, 99, true, '2026-10-19 15:13:44.426884+00', '2026-10-19 15:13:44.426884+00', 'Cho chuỗi sân quy mô lớn', 1499000, 14990000, '#0F6B4A', false, NULL),
	(3, 'Cơ bản', 5, 1, true, '2026-10-19 15:13:44.426884+00', '2026-10-19 15:13:44.426884+00', 'Cho chủ sân mới bắt đầu', 299000, 2990000, '#5B6B66', false, NULL);

INSERT INTO public.plan_features (id, plan_id, feature) OVERRIDING SYSTEM VALUE VALUES
	(1, 1, 'Tối đa 15 sân'),
	(2, 3, 'Báo cáo doanh thu theo ngày'),
	(3, 2, 'Không giới hạn cơ sở'),
	(4, 2, 'Quản lý khách hàng (CRM)'),
	(5, 1, 'Báo cáo & biểu đồ chi tiết'),
	(6, 3, 'Hỗ trợ qua email'),
	(7, 2, 'Không giới hạn sân'),
	(8, 2, 'Phân quyền nhân viên'),
	(9, 1, 'Tối đa 3 cơ sở'),
	(10, 3, 'Tối đa 5 sân'),
	(11, 3, '1 cơ sở'),
	(12, 2, 'Hỗ trợ 24/7 riêng'),
	(13, 1, 'Quản lý đặt sân nâng cao'),
	(14, 2, 'API & tích hợp'),
	(15, 1, 'Hỗ trợ ưu tiên'),
	(16, 3, 'Quản lý đặt sân cơ bản'),
	(17, 1, 'Khuyến mãi & mã giảm giá');

INSERT INTO public.subscriptions (id, admin_id, plan_id, max_courts, max_branches, start_date, end_date, created_at, updated_at, deleted_at) OVERRIDING SYSTEM VALUE VALUES
	(1, 2, 2, 99, 99, '2026-01-01', '2027-01-01', '2026-10-19 15:13:44.464337+00', '2026-10-19 15:13:44.464337+00', NULL),
	(2, 5, 2, 99, 99, '2026-01-01', '2027-01-01', '2026-10-19 15:13:44.464337+00', '2026-10-19 15:13:44.464337+00', NULL),
	(3, 4, 2, 99, 99, '2026-01-01', '2027-01-01', '2026-10-19 15:13:44.464337+00', '2026-10-19 15:13:44.464337+00', NULL),
	(4, 1, 2, 99, 99, '2026-01-01', '2027-01-01', '2026-10-19 15:13:44.464337+00', '2026-10-19 15:13:44.464337+00', NULL),
	(5, 3, 2, 99, 99, '2026-01-01', '2027-01-01', '2026-10-19 15:13:44.464337+00', '2026-10-19 15:13:44.464337+00', NULL);

SELECT pg_catalog.setval('public.court_types_id_seq', 6, true);

SELECT pg_catalog.setval('public.plan_features_id_seq', 17, true);

SELECT pg_catalog.setval('public.subscription_payments_id_seq', 1, false);

SELECT pg_catalog.setval('public.subscription_plans_id_seq', 3, true);

SELECT pg_catalog.setval('public.subscriptions_id_seq', 5, true);

\unrestrict ngwAYJOVImIjIeJfMBPgbhr811Pz6MyWvdgWLp2IZSpFTN63hufghXiyVeA4E3o
