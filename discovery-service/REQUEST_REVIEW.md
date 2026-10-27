# [Review] BE Discovery ? Kh?i t?o service v? API t?m ki?m chi nh?nh

- Lo?i: Request Review; assignee: Minh (Leader).
- Task cha: task th? t? #15/111, Sprint 1 #15/34. Ch?a x?c minh Jira key; kh?ng suy ?o?n SCRUM-15.
- Estimate: 1 ng?y.

## Vi?c c?n l?m
- [ ] Spring Boot 3 / Java 21, port 8085, PostgreSQL discovery_db ri?ng.
- [ ] Read model chi nh?nh v? s?n; t?m g?n ??ng pg_trgm, b? d?u ti?ng Vi?t.
- [ ] Kafka BranchUpdatedEvent full snapshot, version t?ng theo branch; b? event c?/tr?ng; gi? tombstone.
- [ ] GET /api/public/branches kh?ng JWT; query/city/district/courtTypeId/minPrice/maxPrice, ph?n trang v? Haversine.
- [ ] Ki?m th? API, d? li?u, Kafka; ?o latency ri?ng tr??c khi x?c nh?n <80ms.

## ?nh h??ng ??n team
- Ph? thu?c SCRUM-14 v? SCRUM-16. Facility hi?n ch? c? SQL, ch?a c? producer.
- C?n th?ng nh?t topic/event: district, GPS, danh s?ch s?n active v?i gi? t? time_slot_templates.
- Court/pricing/status/delete c?ng ph?i ph?t l?i full snapshot branch; outbox v? replay thu?c facility owner.
- Kh?ng ??c ch?o facility_db, kh?ng chuy?n logic booking/auth sang discovery.

## Tr?ng th?i th?c t?
??y l? b?n nh?p n?i dung tr??c khi code, ch?a ph?i ticket Jira ?? t?o ho?c ?? ???c duy?t.
Ng??i d?ng y?u c?u tri?n khai v? xu?t h??ng d?n Jira sau khi ho?n th?nh; kh?ng t? ghi nh?n approval.
