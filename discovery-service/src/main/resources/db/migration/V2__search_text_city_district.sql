UPDATE branch_read_model
SET search_text = lower(unaccent(concat_ws(' ', name, address, city, district)));
