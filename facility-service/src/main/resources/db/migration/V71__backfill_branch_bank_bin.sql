-- Seeded sample branches (V2) carry bank_name but no bank_bin (added later in V3),
-- which makes payment checkout fail (VietQR needs a 6-digit BIN). Backfill known banks only.
UPDATE public.branches
SET bank_bin = CASE lower(btrim(bank_name))
        WHEN 'vietcombank' THEN '970436'
        WHEN 'techcombank' THEN '970407'
        WHEN 'bidv'        THEN '970418'
        WHEN 'mb bank'     THEN '970422'
        WHEN 'acb'         THEN '970416'
    END
WHERE bank_bin IS NULL
  AND lower(btrim(bank_name)) IN ('vietcombank', 'techcombank', 'bidv', 'mb bank', 'acb');
