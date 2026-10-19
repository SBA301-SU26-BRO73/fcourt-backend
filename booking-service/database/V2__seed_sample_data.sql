-- booking-service (booking_db) - du lieu mau tach tu ban cu (V7, V8, V17, V20...).

\restrict wDWZpzbkaecNtAb00Ke9s4AEkk8NCe6a0KYrYqbjLhCjNADxggMUeGQcNkfwS9q

INSERT INTO public.bookings (id, court_id, customer_id, payment_id, guest_phone, date, status, total_price, created_at, updated_at, checkin_code, checked_in_at, completed_at, booking_code) OVERRIDING SYSTEM VALUE VALUES
	(3, 21, 10, 3, NULL, '2026-07-20', 'AWAITING_CONFIRMATION', 200000.00, '2026-10-19 15:13:51.549223+00', '2026-10-19 15:13:51.549223+00', 'checkin-code-mock-1', NULL, NULL, 'MOCKBK01'),
	(4, 21, 9, 4, NULL, '2026-07-21', 'AWAITING_CONFIRMATION', 300000.00, '2026-10-19 15:13:51.556471+00', '2026-10-19 15:13:51.556471+00', 'checkin-code-mock-2', NULL, NULL, 'MOCKBK02');

SELECT pg_catalog.setval('public.booking_slots_id_seq', 1, false);

SELECT pg_catalog.setval('public.bookings_id_seq', 4, true);

\unrestrict wDWZpzbkaecNtAb00Ke9s4AEkk8NCe6a0KYrYqbjLhCjNADxggMUeGQcNkfwS9q
