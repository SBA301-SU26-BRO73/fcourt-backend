-- payment-service (payment_db) - du lieu mau tach tu ban cu (V7, V8, V17, V20...).

\restrict IHtVBJSydQGV5AbThySqc67NhrRycwKICgjf0SrVR5QSfd4xFCcopErKEV79I7K

INSERT INTO public.payments (id, branch_id, amount, bill_image_url, status, confirmed_at, confirmed_by, created_at, updated_at, voucher_id, discount_amount) OVERRIDING SYSTEM VALUE VALUES
	(3, 1, 150000.00, 'https://res.cloudinary.com/demo/image/upload/v123456/bill1.jpg', 'CONFIRMED', '2026-10-19 15:13:51.532085+00', 1, '2026-10-17 15:13:51.532085+00', '2026-10-17 15:13:51.532085+00', 1, 50000.00),
	(4, 1, 270000.00, 'https://res.cloudinary.com/demo/image/upload/v123456/bill2.jpg', 'CONFIRMED', '2026-10-19 15:13:51.543465+00', 1, '2026-10-18 15:13:51.543465+00', '2026-10-18 15:13:51.543465+00', 2, 30000.00);

SELECT pg_catalog.setval('public.payments_id_seq', 4, true);

SELECT pg_catalog.setval('public.refund_requests_id_seq', 1, false);

\unrestrict IHtVBJSydQGV5AbThySqc67NhrRycwKICgjf0SrVR5QSfd4xFCcopErKEV79I7K
