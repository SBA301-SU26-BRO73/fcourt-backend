-- voucher-service (voucher_db) - du lieu mau tach tu ban cu (V7, V8, V17, V20...).

\restrict XfwZjBpfnFFObrqljNS9rYT5yfE9zCyQAuNxXluGAXlNtdAn68NGA8u50eFqyv0

INSERT INTO public.vouchers (id, code, discount_type, discount_value, min_order_value, max_discount_value, usage_limit, usage_count, start_date, expiry_date, active, created_at, updated_at, deleted_at, branch_id) VALUES
	(1, 'KM50K', 'FIXED', 50000.00, 100000.00, NULL, 100, 3, '2026-01-01 00:00:00+00', '2026-12-31 23:59:59+00', true, '2026-10-19 15:13:50.806331+00', '2026-10-19 15:13:50.806331+00', NULL, 1),
	(2, 'KM10PCT', 'PERCENTAGE', 10.00, 150000.00, 30000.00, 50, 2, '2026-01-01 00:00:00+00', '2026-12-31 23:59:59+00', true, '2026-10-19 15:13:50.82541+00', '2026-10-19 15:13:50.82541+00', NULL, 1);

SELECT pg_catalog.setval('public.vouchers_id_seq', 2, true);

\unrestrict XfwZjBpfnFFObrqljNS9rYT5yfE9zCyQAuNxXluGAXlNtdAn68NGA8u50eFqyv0
