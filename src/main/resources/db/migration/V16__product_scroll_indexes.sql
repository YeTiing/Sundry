-- keyset 分页：按 id 倒序，避免 offset 深分页
ALTER TABLE product
    ADD KEY idx_product_public_id (status, deleted_at, id),
    ADD KEY idx_product_public_category_id (status, deleted_at, category_id, id);
