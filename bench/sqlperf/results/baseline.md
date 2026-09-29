# SQLPERF capture: baseline

MySQL 8.0.46, 16 logical CPUs (Docker Desktop VM shared with the host), buffer pool 128 MB, seed 17.0s. Median of 5 warm EXPLAIN ANALYZE runs (one discarded warm-up).

| id | workload | median ms | returned | rows read (approx) | flags |
|---|---|---:|---:|---:|---|
| product.catalog_all | catalog | 0.456 | 1800 | 2000 | full_scan, no_key |
| product.by_pk | checkout | const | -1 | 0 |  |
| product.lock_by_pk | checkout | const | -1 | 0 |  |
| product.in_20_sellable | catalog/checkout | 0.077 | 19 | 19 |  |
| product.seller_page | seller-admin | 0.083 | 20 | 20 |  |
| product.seller_search | seller-admin | 0.145 | 0 | 40 |  |
| product.seller_count | seller-admin | 0.027 | 1 | 40 |  |
| product.rating_all | catalog | 10.7 | 1800 | 20000 |  |
| product.rating_in_20 | catalog | 0.037 | 0 | 0 |  |
| product.images_in_20 | catalog | 0.135 | 38 | 38 |  |
| order.member_page_hot | buyer-orders | 0.539 | 20 | 40 | filesort |
| order.member_page_typical | buyer-orders | 0.123 | 13 | 26 | filesort |
| order.member_page_status | buyer-orders | 0.481 | 20 | 61 | filesort |
| order.member_count_hot | buyer-orders | 0.329 | 1 | 1475 |  |
| order.seller_page | seller-orders | 41.6 | 20 | 18467 | full_scan, filesort, temporary, no_key |
| order.seller_count | seller-orders | 8.36 | 1 | 13467 | full_scan, no_key |
| order.admin_status_page | admin-orders | 0.343 | 20 | 40 | filesort |
| order.lines_in_20 | buyer-orders | 0.256 | 42 | 84 | filesort |
| order.history_in_20 | buyer-orders | 0.1 | 40 | 40 |  |
| order.header_lock | order-status | const | -1 | 1 |  |
| order.request_by_pk | checkout | const | -1 | 0 |  |
| payment.by_trade_no | payment-callback | const | -1 | 0 |  |
| payment.initiated_for_order | payment | 0.023 | 0 | 1 |  |
| payment.order_lock | payment-callback | const | -1 | 0 |  |
| reco.co_purchase | recommendation | 548.0 | 8 | 198676 | filesort, temporary |
| reco.popular | recommendation | 502.0 | 8 | 203600 | full_scan, filesort, temporary, no_key |
| reco.new_arrival | recommendation | 0.905 | 8 | 2000 | full_scan, filesort, no_key |
| reco.member_purchases | recommendation | 16.6 | 1454 | 2929 | filesort, temporary |
| reco.member_cart | recommendation | 0.007 | 4 | 4 |  |
| review.public_page | reviews | 0.071 | 10 | 20 |  |
| review.public_count | reviews | 0.02 | 1 | 10 |  |
| review.public_avg | reviews | 0.039 | 1 | 10 |  |
| review.verified_purchase | reviews | 0.06 | 1 | 13 |  |
| review.seller_moderation | reviews | 1.2 | 20 | 440 | filesort, temporary |
| review.admin_all | reviews | 6.69 | 20 | 20020 | full_scan, filesort, no_key |
| audit.latest | audit | 0.049 | 50 | 50 |  |
| audit.by_actor | audit | 0.111 | 50 | 50 |  |
| audit.by_target | audit | 0.134 | 33 | 33 |  |
| audit.action_window | audit | 2.63 | 50 | 2617 |  |
| audit.count_action_window | audit | 3.92 | 1 | 1982 |  |
| cart.items_by_email | cart | 0.027 | 4 | 8 | filesort |
| cart.member_lock | cart/checkout | const | -1 | 0 |  |
| coupon.by_code | coupon | const | -1 | 0 |  |

## Table sizes

| table | rows (est) | data MB | index MB |
|---|---:|---:|---:|
| order_status_history | 198973 | 15.55 | 10.52 |
| order_detail | 249174 | 15.55 | 23.06 |
| audit_log | 99596 | 11.52 | 16.06 |
| shop_order | 99728 | 8.52 | 19.06 |
| payment | 89703 | 8.52 | 8.55 |
| order_request | 99974 | 6.52 | 0.00 |
| product_review | 20097 | 2.52 | 3.03 |
| shopping_cart | 9845 | 1.52 | 1.08 |
| shipping_address | 4854 | 0.48 | 0.28 |
| member | 4961 | 0.38 | 0.30 |
| product_image | 4000 | 0.31 | 0.19 |
| product | 2000 | 0.20 | 0.08 |
| password_reset_token | 0 | 0.02 | 0.03 |
| faq | 0 | 0.02 | 0.00 |
| doc_embedding | 0 | 0.02 | 0.02 |
| coupon_member_usage | 0 | 0.02 | 0.00 |
| coupon | 20 | 0.02 | 0.02 |

## Plans

### product.catalog_all (ProductRepository.findAll)

```sql
SELECT product_id, product_name, price, quantity, creator_id, deleted_at FROM product WHERE deleted_at IS NULL
```

```
-> Filter: (product.deleted_at is null)  (cost=203 rows=200) (actual time=0.0375..0.392 rows=1800 loops=1)
    -> Table scan on product  (cost=203 rows=2000) (actual time=0.0368..0.323 rows=2000 loops=1)


```

### product.by_pk (ProductRepository.findActiveById)

```sql
SELECT product_id, product_name, price, quantity, creator_id, deleted_at FROM product WHERE product_id = 'P01200' AND deleted_at IS NULL
```

```
-> Rows fetched before execution  (cost=0..0 rows=1) (actual time=40e-6..80e-6 rows=1 loops=1)


```

### product.lock_by_pk (ProductRepository.findByIdForUpdate)

```sql
SELECT product_id, product_name, price, quantity, creator_id, deleted_at FROM product WHERE product_id = 'P00001' FOR UPDATE
```

```
-> Rows fetched before execution  (cost=0..0 rows=1) (actual time=40e-6..70e-6 rows=1 loops=1)


```

### product.in_20_sellable (ProductRepository.findSellableByIds)

```sql
SELECT product_id, product_name, price, quantity, creator_id, deleted_at FROM product WHERE product_id IN ('P00101','P00201','P00301','P00401','P00501','P00601','P00701','P00801','P00901','P01001','P01101','P01201','P01301','P01401','P01501','P01601','P01701','P01801','P01901','P02001') AND deleted_at IS NULL AND quantity > 0
```

```
-> Filter: ((product.product_id in ('P00101','P00201','P00301','P00401','P00501','P00601','P00701','P00801','P00901','P01001','P01101','P01201','P01301','P01401','P01501','P01601','P01701','P01801','P01901','P02001')) and (product.deleted_at is null) and (product.quantity > 0))  (cost=9.02 rows=0.667) (actual time=0.0265..0.0747 rows=19 loops=1)
    -> Index range scan on product using PRIMARY over (product_id = 'P00101') OR (product_id = 'P00201') OR (18 more)  (cost=9.02 rows=20) (actual time=0.024..0.0676 rows=19 loops=1)


```

### product.seller_page (ProductRepository.findBySeller)

```sql
SELECT product_id, product_name, price, quantity, creator_id, deleted_at FROM product WHERE creator_id = 'u3@example.com' ORDER BY product_id LIMIT 20 OFFSET 0
```

```
-> Limit: 20 row(s)  (cost=13.8 rows=20) (actual time=0.114..0.171 rows=20 loops=1)
    -> Index lookup on product using idx_product_creator (creator_id='u3@example.com')  (cost=13.8 rows=40) (actual time=0.114..0.17 rows=20 loops=1)


```

### product.seller_search (ProductRepository.search)

```sql
SELECT product_id, product_name, price, quantity, creator_id, deleted_at FROM product WHERE creator_id = 'u3@example.com' AND (LOWER(product_id) LIKE '%chair%' ESCAPE '\\' OR LOWER(product_name) LIKE '%chair%' ESCAPE '\\') AND deleted_at IS NULL ORDER BY product_id LIMIT 20 OFFSET 0
```

```
-> Limit: 20 row(s)  (cost=10.2 rows=4) (actual time=0.103..0.103 rows=0 loops=1)
    -> Filter: (((lower(product.product_id) like '%chair%' escape '\\') or (lower(product.product_name) like '%chair%' escape '\\')) and (product.deleted_at is null))  (cost=10.2 rows=4) (actual time=0.102..0.102 rows=0 loops=1)
        -> Index lookup on product using idx_product_creator (creator_id='u3@example.com')  (cost=10.2 rows=40) (actual time=0.0296..0.0899 rows=40 loops=1)


```

### product.seller_count (ProductRepository.count)

```sql
SELECT COUNT(*) FROM product WHERE creator_id = 'u3@example.com'
```

```
-> Aggregate: count(0)  (cost=9.47 rows=1) (actual time=0.0286..0.0287 rows=1 loops=1)
    -> Covering index lookup on product using idx_product_creator (creator_id='u3@example.com')  (cost=5.47 rows=40) (actual time=0.014..0.0248 rows=40 loops=1)


```

### product.rating_all (ProductRepository.ratingSummaries(all))

```sql
SELECT product_id, ROUND(AVG(rating), 2) average_rating, COUNT(*) review_count FROM product_review WHERE visibility = 'VISIBLE' GROUP BY product_id
```

```
-> Group aggregate: avg(product_review.rating), count(0)  (cost=2251 rows=2000) (actual time=0.0786..10.7 rows=1800 loops=1)
    -> Filter: (product_review.visibility = 'VISIBLE')  (cost=2050 rows=2010) (actual time=0.0726..9.32 rows=18000 loops=1)
        -> Index scan on product_review using idx_product_review_public  (cost=2050 rows=20097) (actual time=0.0687..8.03 rows=20000 loops=1)


```

### product.rating_in_20 (ProductRepository.ratingSummaries(ids))

```sql
SELECT product_id, ROUND(AVG(rating), 2) average_rating, COUNT(*) review_count FROM product_review WHERE visibility = 'VISIBLE' AND product_id IN ('P00101','P00201','P00301','P00401','P00501','P00601','P00701','P00801','P00901','P01001','P01101','P01201','P01301','P01401','P01501','P01601','P01701','P01801','P01901','P02001') GROUP BY product_id
```

```
-> Group aggregate: avg(product_review.rating), count(0)  (cost=16 rows=20) (actual time=0.0375..0.0375 rows=0 loops=1)
    -> Index range scan on product_review using idx_product_review_public over (product_id = 'P00101' AND visibility = 'VISIBLE') OR (product_id = 'P00201' AND visibility = 'VISIBLE') OR (18 more), with index condition: ((product_review.visibility = 'VISIBLE') and (product_review.product_id in ('P00101','P00201','P00301','P00401','P00501','P00601','P00701','P00801','P00901','P01001','P01101','P01201','P01301','P01401','P01501','P01601','P01701','P01801','P01901','P02001')))  (cost=14 rows=20) (actual time=0.0364..0.0364 rows=0 loops=1)


```

### product.images_in_20 (ProductRepository.imagesFor)

```sql
SELECT product_id, image_url FROM product_image WHERE product_id IN ('P00101','P00201','P00301','P00401','P00501','P00601','P00701','P00801','P00901','P01001','P01101','P01201','P01301','P01401','P01501','P01601','P01701','P01801','P01901','P02001') ORDER BY product_id, display_order, image_id
```

```
-> Index range scan on product_image using idx_product_image_product over (product_id = 'P00101') OR (product_id = 'P00201') OR (18 more), with index condition: (product_image.product_id in ('P00101','P00201','P00301','P00401','P00501','P00601','P00701','P00801','P00901','P01001','P01101','P01201','P01301','P01401','P01501','P01601','P01701','P01801','P01901','P02001'))  (cost=22.6 rows=39) (actual time=0.0222..0.14 rows=38 loops=1)


```

### order.member_page_hot (OrderRepository.findByMember(hot))

```sql
SELECT o.order_id, o.member_id, o.order_status, o.price, o.created_at, a.receiver_name, a.phone, CONCAT_WS(' ', a.postal_code, a.address) AS full_address, o.pay_status, o.coupon_id, o.coupon_code, o.discount_amount, (SELECT p.status FROM payment p WHERE p.order_id = o.order_id ORDER BY p.id DESC LIMIT 1) AS payment_status FROM shop_order o LEFT JOIN shipping_address a ON a.id = o.shipping_address_id WHERE o.member_id = 'u1@example.com' ORDER BY o.created_at DESC, o.order_id DESC LIMIT 20 OFFSET 0
```

```
-> Limit: 20 row(s)  (cost=2021 rows=20) (actual time=0.682..0.723 rows=20 loops=1)
    -> Nested loop left join  (cost=2021 rows=1475) (actual time=0.682..0.721 rows=20 loops=1)
        -> Index lookup on o using idx_shop_order_member (member_id='u1@example.com') (reverse)  (cost=1284 rows=1475) (actual time=0.669..0.676 rows=20 loops=1)
        -> Single-row index lookup on a using PRIMARY (id=o.shipping_address_id)  (cost=0.4 rows=1) (actual time=0.00203..0.00205 rows=1 loops=20)
-> Select #2 (subquery in projection; dependent)
    -> Limit: 1 row(s)  (cost=0.35 rows=1) (actual time=0.00677..0.00679 rows=0.85 loops=20)
        -> Sort: p.id DESC, limit input to 1 row(s) per chunk  (cost=0.35 rows=1) (actual time=0.00667..0.00667 rows=0.85 loops=20)
            -> Index lookup on p using idx_payment_order (order_id=o.order_id)  (cost=0.35 rows=1) (actual time=0.00572..0.00596 rows=0.85 loops=20)


```

### order.member_page_typical (OrderRepository.findByMember(typical))

```sql
SELECT o.order_id, o.member_id, o.order_status, o.price, o.created_at, a.receiver_name, a.phone, CONCAT_WS(' ', a.postal_code, a.address) AS full_address, o.pay_status, o.coupon_id, o.coupon_code, o.discount_amount, (SELECT p.status FROM payment p WHERE p.order_id = o.order_id ORDER BY p.id DESC LIMIT 1) AS payment_status FROM shop_order o LEFT JOIN shipping_address a ON a.id = o.shipping_address_id WHERE o.member_id = 'u1500@example.com' ORDER BY o.created_at DESC, o.order_id DESC LIMIT 20 OFFSET 0
```

```
-> Limit: 20 row(s)  (cost=16.7 rows=13) (actual time=0.105..0.2 rows=13 loops=1)
    -> Nested loop left join  (cost=16.7 rows=13) (actual time=0.104..0.199 rows=13 loops=1)
        -> Index lookup on o using idx_shop_order_member (member_id='u1500@example.com') (reverse)  (cost=11.2 rows=13) (actual time=0.0857..0.146 rows=13 loops=1)
        -> Single-row index lookup on a using PRIMARY (id=o.shipping_address_id)  (cost=0.333 rows=1) (actual time=0.00382..0.00384 rows=1 loops=13)
-> Select #2 (subquery in projection; dependent)
    -> Limit: 1 row(s)  (cost=0.35 rows=1) (actual time=0.00733..0.00735 rows=0.923 loops=13)
        -> Sort: p.id DESC, limit input to 1 row(s) per chunk  (cost=0.35 rows=1) (actual time=0.00722..0.00722 rows=0.923 loops=13)
            -> Index lookup on p using idx_payment_order (order_id=o.order_id)  (cost=0.35 rows=1) (actual time=0.00608..0.00635 rows=0.923 loops=13)


```

### order.member_page_status (OrderRepository.findByMember(status))

```sql
SELECT o.order_id, o.member_id, o.order_status, o.price, o.created_at, a.receiver_name, a.phone, CONCAT_WS(' ', a.postal_code, a.address) AS full_address, o.pay_status, o.coupon_id, o.coupon_code, o.discount_amount, (SELECT p.status FROM payment p WHERE p.order_id = o.order_id ORDER BY p.id DESC LIMIT 1) AS payment_status FROM shop_order o LEFT JOIN shipping_address a ON a.id = o.shipping_address_id WHERE o.member_id = 'u1@example.com' AND o.order_status = 'DELIVERED' ORDER BY o.created_at DESC, o.order_id DESC LIMIT 20 OFFSET 0
```

```
-> Limit: 20 row(s)  (cost=1511 rows=20) (actual time=1.31..1.34 rows=20 loops=1)
    -> Nested loop left join  (cost=1511 rows=738) (actual time=1.31..1.33 rows=20 loops=1)
        -> Filter: (o.order_status = 'DELIVERED')  (cost=1198 rows=738) (actual time=1.3..1.31 rows=20 loops=1)
            -> Index lookup on o using idx_shop_order_member (member_id='u1@example.com') (reverse)  (cost=1198 rows=1475) (actual time=1.3..1.3 rows=41 loops=1)
        -> Single-row index lookup on a using PRIMARY (id=o.shipping_address_id)  (cost=0.325 rows=1) (actual time=0.00113..0.00114 rows=1 loops=20)
-> Select #2 (subquery in projection; dependent)
    -> Limit: 1 row(s)  (cost=0.35 rows=1) (actual time=0.0105..0.0105 rows=0.8 loops=20)
        -> Sort: p.id DESC, limit input to 1 row(s) per chunk  (cost=0.35 rows=1) (actual time=0.0104..0.0104 rows=0.8 loops=20)
            -> Index lookup on p using idx_payment_order (order_id=o.order_id)  (cost=0.35 rows=1) (actual time=0.0096..0.00978 rows=0.8 loops=20)


```

### order.member_count_hot (OrderRepository.countByMember)

```sql
SELECT COUNT(*) FROM shop_order o WHERE o.member_id = 'u1@example.com'
```

```
-> Aggregate: count(0)  (cost=364 rows=1) (actual time=0.356..0.356 rows=1 loops=1)
    -> Covering index lookup on o using idx_shop_order_member (member_id='u1@example.com')  (cost=217 rows=1475) (actual time=0.0349..0.317 rows=1475 loops=1)


```

### order.seller_page (OrderRepository.findBySeller)

```sql
SELECT o.order_id, o.member_id, o.order_status, o.price, o.created_at, a.receiver_name, a.phone, CONCAT_WS(' ', a.postal_code, a.address) AS full_address, o.pay_status, o.coupon_id, o.coupon_code, o.discount_amount, (SELECT p.status FROM payment p WHERE p.order_id = o.order_id ORDER BY p.id DESC LIMIT 1) AS payment_status FROM shop_order o LEFT JOIN shipping_address a ON a.id = o.shipping_address_id WHERE EXISTS (SELECT 1 FROM order_detail d JOIN product p ON p.product_id = d.product_id WHERE d.order_id = o.order_id AND p.creator_id = 'u3@example.com') ORDER BY o.created_at DESC, o.order_id DESC LIMIT 20 OFFSET 0
```

```
-> Limit: 20 row(s)  (actual time=39.2..39.2 rows=20 loops=1)
    -> Sort: o.created_at DESC, o.order_id DESC, limit input to 20 row(s) per chunk  (actual time=39.2..39.2 rows=20 loops=1)
        -> Stream results  (cost=2.56e+6 rows=25.6e+6) (actual time=3.8..34.7 rows=6627 loops=1)
            -> Left hash join (a.id = o.shipping_address_id)  (cost=2.56e+6 rows=25.6e+6) (actual time=3.78..13.8 rows=6627 loops=1)
                -> Nested loop inner join  (cost=8.21e+6 rows=5274) (actual time=2.35..9.99 rows=6627 loops=1)
                    -> Table scan on <subquery3>  (cost=1172..1240 rows=5274) (actual time=2.34..3.04 rows=6627 loops=1)
                        -> Materialize with deduplication  (cost=1172..1172 rows=5274) (actual time=2.34..2.34 rows=6627 loops=1)
                            -> Nested loop inner join  (cost=644 rows=5274) (actual time=0.0223..1.48 rows=6781 loops=1)
                                -> Covering index lookup on p using idx_product_creator (creator_id='u3@example.com')  (cost=5.47 rows=40) (actual time=0.00779..0.0155 rows=40 loops=1)
                                -> Covering index lookup on d using idx_order_detail_product_order (product_id=p.product_id)  (cost=3.12 rows=132) (actual time=0.00569..0.0305 rows=170 loops=40)
                    -> Single-row index lookup on o using PRIMARY (order_id=`<subquery3>`.order_id)  (cost=8.21e+6 rows=1) (actual time=935e-6..951e-6 rows=1 loops=6627)
                -> Hash
                    -> Table scan on a  (cost=493 rows=4854) (actual time=0.0276..0.9 rows=5000 loops=1)
-> Select #2 (subquery in projection; dependent)
    -> Limit: 1 row(s)  (cost=0.35 rows=1) (actual time=0.00242..0.00244 rows=0.923 loops=6627)
        -> Sort: p.id DESC, limit input to 1 row(s) per chunk  (cost=0.35 rows=1) (actual time=0.00235..0.00235 rows=0.923 loops=6627)
            -> Index lookup on p using idx_payment_order (order_id=o.order_id)  (cost=0.35 rows=1) (actual time=0.00192..0.00208 rows=0.923 loops=6627)


```

### order.seller_count (OrderRepository.countBySeller)

```sql
SELECT COUNT(*) FROM shop_order o WHERE EXISTS (SELECT 1 FROM order_detail d JOIN product p ON p.product_id = d.product_id WHERE d.order_id = o.order_id AND p.creator_id = 'u3@example.com')
```

```
-> Aggregate: count(0)  (cost=8.21e+6 rows=1) (actual time=8.39..8.39 rows=1 loops=1)
    -> Nested loop inner join  (cost=8.21e+6 rows=5274) (actual time=2.51..8.22 rows=6627 loops=1)
        -> Table scan on <subquery2>  (cost=1172..1240 rows=5274) (actual time=2.51..2.93 rows=6627 loops=1)
            -> Materialize with deduplication  (cost=1172..1172 rows=5274) (actual time=2.5..2.5 rows=6627 loops=1)
                -> Nested loop inner join  (cost=644 rows=5274) (actual time=0.0273..1.57 rows=6781 loops=1)
                    -> Covering index lookup on p using idx_product_creator (creator_id='u3@example.com')  (cost=5.47 rows=40) (actual time=0.0121..0.0207 rows=40 loops=1)
                    -> Covering index lookup on d using idx_order_detail_product_order (product_id=p.product_id)  (cost=3.12 rows=132) (actual time=0.00658..0.0318 rows=170 loops=40)
        -> Single-row covering index lookup on o using PRIMARY (order_id=`<subquery2>`.order_id)  (cost=8.21e+6 rows=1) (actual time=689e-6..706e-6 rows=1 loops=6627)


```

### order.admin_status_page (OrderRepository.findBySeller(admin,status))

```sql
SELECT o.order_id, o.member_id, o.order_status, o.price, o.created_at, a.receiver_name, a.phone, CONCAT_WS(' ', a.postal_code, a.address) AS full_address, o.pay_status, o.coupon_id, o.coupon_code, o.discount_amount, (SELECT p.status FROM payment p WHERE p.order_id = o.order_id ORDER BY p.id DESC LIMIT 1) AS payment_status FROM shop_order o LEFT JOIN shipping_address a ON a.id = o.shipping_address_id WHERE o.order_status = 'CREATED' ORDER BY o.created_at DESC, o.order_id DESC LIMIT 20 OFFSET 0
```

```
-> Limit: 20 row(s)  (cost=15001 rows=20) (actual time=0.289..0.327 rows=20 loops=1)
    -> Nested loop left join  (cost=15001 rows=32428) (actual time=0.288..0.326 rows=20 loops=1)
        -> Index lookup on o using idx_shop_order_status (order_status='CREATED') (reverse)  (cost=3652 rows=32428) (actual time=0.279..0.283 rows=20 loops=1)
        -> Single-row index lookup on a using PRIMARY (id=o.shipping_address_id)  (cost=0.25 rows=1) (actual time=0.00195..0.00197 rows=1 loops=20)
-> Select #2 (subquery in projection; dependent)
    -> Limit: 1 row(s)  (cost=0.35 rows=1) (actual time=0.0057..0.00572 rows=0.85 loops=20)
        -> Sort: p.id DESC, limit input to 1 row(s) per chunk  (cost=0.35 rows=1) (actual time=0.00561..0.00561 rows=0.85 loops=20)
            -> Index lookup on p using idx_payment_order (order_id=o.order_id)  (cost=0.35 rows=1) (actual time=0.00476..0.00497 rows=0.85 loops=20)


```

### order.lines_in_20 (OrderRepository.findLines)

```sql
SELECT d.order_id, d.product_id, p.product_name, d.quantity, d.unit_price, d.item_price, p.creator_id FROM order_detail d JOIN product p ON p.product_id = d.product_id WHERE d.order_id IN ('O00050000','O00050001','O00050002','O00050003','O00050004','O00050005','O00050006','O00050007','O00050008','O00050009','O00050010','O00050011','O00050012','O00050013','O00050014','O00050015','O00050016','O00050017','O00050018','O00050019') ORDER BY d.order_id, d.product_id
```

```
-> Nested loop inner join  (cost=75.9 rows=42) (actual time=0.185..0.256 rows=42 loops=1)
    -> Sort: d.order_id, d.product_id  (cost=61.2 rows=42) (actual time=0.177..0.179 rows=42 loops=1)
        -> Index range scan on d using fk_order_detail_order over (order_id = 'O00050000') OR (order_id = 'O00050001') OR (18 more), with index condition: (d.order_id in ('O00050000','O00050001','O00050002','O00050003','O00050004','O00050005','O00050006','O00050007','O00050008','O00050009','O00050010','O00050011','O00050012','O00050013','O00050014','O00050015','O00050016','O00050017','O00050018','O00050019'))  (cost=61.2 rows=42) (actual time=0.0314..0.138 rows=42 loops=1)
    -> Single-row index lookup on p using PRIMARY (product_id=d.product_id)  (cost=0.252 rows=1) (actual time=0.00169..0.00171 rows=1 loops=42)


```

### order.history_in_20 (OrderRepository.findHistory)

```sql
SELECT order_id, from_status, to_status, actor_role, created_at FROM order_status_history WHERE order_id IN ('O00050000','O00050001','O00050002','O00050003','O00050004','O00050005','O00050006','O00050007','O00050008','O00050009','O00050010','O00050011','O00050012','O00050013','O00050014','O00050015','O00050016','O00050017','O00050018','O00050019') ORDER BY order_id, id
```

```
-> Index range scan on order_status_history using idx_order_status_history_order over (order_id = 'O00050000') OR (order_id = 'O00050001') OR (18 more), with index condition: (order_status_history.order_id in ('O00050000','O00050001','O00050002','O00050003','O00050004','O00050005','O00050006','O00050007','O00050008','O00050009','O00050010','O00050011','O00050012','O00050013','O00050014','O00050015','O00050016','O00050017','O00050018','O00050019'))  (cost=23 rows=40) (actual time=0.0252..0.1 rows=40 loops=1)


```

### order.header_lock (OrderRepository.findHeaderForUpdate)

```sql
SELECT o.order_id, o.member_id, o.order_status, o.price, o.created_at, a.receiver_name, a.phone, CONCAT_WS(' ', a.postal_code, a.address) AS full_address, o.pay_status, o.coupon_id, o.coupon_code, o.discount_amount, (SELECT p.status FROM payment p WHERE p.order_id = o.order_id ORDER BY p.id DESC LIMIT 1) AS payment_status FROM shop_order o LEFT JOIN shipping_address a ON a.id = o.shipping_address_id WHERE o.order_id = 'O00050000' FOR UPDATE OF o
```

```
-> Rows fetched before execution  (cost=0..0 rows=1) (actual time=50e-6..80e-6 rows=1 loops=1)
-> Select #2 (subquery in projection; dependent)
    -> Limit: 1 row(s)  (cost=0.35 rows=1) (actual time=0.00835..0.00839 rows=1 loops=1)
        -> Index lookup on p using idx_payment_order (order_id='O00050000') (reverse)  (cost=0.35 rows=1) (actual time=0.0078..0.0078 rows=1 loops=1)


```

### order.request_by_pk (OrderRepository.findRequest)

```sql
SELECT request_id, order_id, member_id FROM order_request WHERE request_id = 'R00050000'
```

```
-> Rows fetched before execution  (cost=0..0 rows=1) (actual time=60e-6..100e-6 rows=1 loops=1)


```

### payment.by_trade_no (PaymentRepository.findByTradeNo)

```sql
SELECT id, order_id, merchant_trade_no, provider, amount, status, failure_reason, created_at, paid_at FROM payment WHERE merchant_trade_no = 'T50000'
```

```
-> Rows fetched before execution  (cost=0..0 rows=1) (actual time=30e-6..60e-6 rows=1 loops=1)


```

### payment.initiated_for_order (PaymentRepository.findInitiated)

```sql
SELECT id, order_id, merchant_trade_no, provider, amount, status, failure_reason, created_at, paid_at FROM payment WHERE order_id = 'O00050000' AND status = 'INITIATED'
```

```
-> Filter: (payment.`status` = 'INITIATED')  (cost=0.26 rows=0.1) (actual time=0.0241..0.0241 rows=0 loops=1)
    -> Index lookup on payment using idx_payment_order (order_id='O00050000')  (cost=0.26 rows=1) (actual time=0.0211..0.0225 rows=1 loops=1)


```

### payment.order_lock (PaymentRepository.lockOrder)

```sql
SELECT order_id, member_id, order_status, pay_status, price FROM shop_order WHERE order_id = 'O00050000' FOR UPDATE
```

```
-> Rows fetched before execution  (cost=0..0 rows=1) (actual time=50e-6..80e-6 rows=1 loops=1)


```

### reco.co_purchase (RecommendationRepository.coPurchased)

```sql
SELECT b.product_id, COUNT(DISTINCT o.member_id) AS score FROM order_detail a JOIN shop_order o ON o.order_id = a.order_id AND o.order_status <> 'CANCELLED' JOIN order_detail b ON b.order_id = a.order_id JOIN product p ON p.product_id = b.product_id AND p.deleted_at IS NULL AND p.quantity > 0 WHERE a.product_id IN ('P00001') AND b.product_id NOT IN ('P00001') GROUP BY b.product_id HAVING COUNT(DISTINCT o.member_id) >= 2 ORDER BY score DESC, b.product_id ASC LIMIT 8
```

```
-> Limit: 8 row(s)  (actual time=548..548 rows=8 loops=1)
    -> Sort: score DESC, b.product_id  (actual time=548..548 rows=8 loops=1)
        -> Filter: (`count(distinct shop_order.member_id)` >= 2)  (actual time=545..547 rows=1233 loops=1)
            -> Stream results  (actual time=545..547 rows=1495 loops=1)
                -> Group aggregate: count(distinct shop_order.member_id), count(distinct shop_order.member_id)  (actual time=545..547 rows=1495 loops=1)
                    -> Sort: b.product_id  (actual time=545..546 rows=6592 loops=1)
                        -> Stream results  (cost=3027 rows=2264) (actual time=0.152..539 rows=6592 loops=1)
                            -> Nested loop inner join  (cost=3027 rows=2264) (actual time=0.151..538 rows=6592 loops=1)
                                -> Nested loop inner join  (cost=2219 rows=2199) (actual time=0.0454..230 rows=186698 loops=1)
                                    -> Nested loop inner join  (cost=679 rows=4399) (actual time=0.0407..44.7 rows=196518 loops=1)
                                        -> Filter: ((p.product_id <> 'P00001') and (p.deleted_at is null) and (p.quantity > 0))  (cost=201 rows=33.4) (actual time=0.0304..0.933 rows=1599 loops=1)
                                            -> Index range scan on p using PRIMARY over (product_id < 'P00001') OR ('P00001' < product_id)  (cost=201 rows=1001) (actual time=0.0282..0.526 rows=1999 loops=1)
                                        -> Covering index lookup on b using idx_order_detail_product_order (product_id=p.product_id)  (cost=1.55 rows=132) (actual time=0.00394..0.0229 rows=123 loops=1599)
                                    -> Filter: (o.order_status <> 'CANCELLED')  (cost=0.25 rows=0.5) (actual time=804e-6..855e-6 rows=0.95 loops=196518)
                                        -> Single-row index lookup on o using PRIMARY (order_id=b.order_id)  (cost=0.25 rows=1) (actual time=704e-6..721e-6 rows=1 loops=196518)
                                -> Covering index lookup on a using idx_order_detail_product_order (product_id='P00001', order_id=b.order_id)  (cost=0.264 rows=1.03) (actual time=0.00157..0.00158 rows=0.0353 loops=186698)


```

### reco.popular (RecommendationRepository.popular)

```sql
SELECT d.product_id, COUNT(DISTINCT o.member_id) AS score FROM order_detail d JOIN shop_order o ON o.order_id = d.order_id AND o.order_status <> 'CANCELLED' JOIN product p ON p.product_id = d.product_id AND p.deleted_at IS NULL AND p.quantity > 0 WHERE 1 = 1 GROUP BY d.product_id HAVING COUNT(DISTINCT o.member_id) >= 2 ORDER BY score DESC, d.product_id ASC LIMIT 8
```

```
-> Limit: 8 row(s)  (actual time=497..497 rows=8 loops=1)
    -> Sort: score DESC, d.product_id  (actual time=497..497 rows=8 loops=1)
        -> Filter: (`count(distinct shop_order.member_id)` >= 2)  (actual time=420..496 rows=1600 loops=1)
            -> Stream results  (actual time=420..496 rows=1600 loops=1)
                -> Group aggregate: count(distinct shop_order.member_id), count(distinct shop_order.member_id)  (actual time=420..495 rows=1600 loops=1)
                    -> Sort: d.product_id  (actual time=417..424 rows=191967 loops=1)
                        -> Stream results  (cost=4235 rows=4394) (actual time=0.0589..247 rows=191967 loops=1)
                            -> Nested loop inner join  (cost=4235 rows=4394) (actual time=0.058..220 rows=191967 loops=1)
                                -> Nested loop inner join  (cost=1159 rows=8788) (actual time=0.051..45.7 rows=202050 loops=1)
                                    -> Filter: ((p.deleted_at is null) and (p.quantity > 0))  (cost=203 rows=66.7) (actual time=0.0299..0.697 rows=1600 loops=1)
                                        -> Table scan on p  (cost=203 rows=2000) (actual time=0.0283..0.42 rows=2000 loops=1)
                                    -> Covering index lookup on d using idx_order_detail_product_order (product_id=p.product_id)  (cost=1.35 rows=132) (actual time=0.00439..0.0236 rows=126 loops=1600)
                                -> Filter: (o.order_status <> 'CANCELLED')  (cost=0.25 rows=0.5) (actual time=723e-6..772e-6 rows=0.95 loops=202050)
                                    -> Single-row index lookup on o using PRIMARY (order_id=d.order_id)  (cost=0.25 rows=1) (actual time=627e-6..644e-6 rows=1 loops=202050)


```

### reco.new_arrival (RecommendationRepository.newArrivals)

```sql
SELECT p.product_id FROM product p WHERE p.deleted_at IS NULL AND p.quantity > 0 ORDER BY p.created_at DESC, p.product_id ASC LIMIT 8
```

```
-> Limit: 8 row(s)  (cost=203 rows=8) (actual time=0.919..0.919 rows=8 loops=1)
    -> Sort: p.created_at DESC, p.product_id, limit input to 8 row(s) per chunk  (cost=203 rows=2000) (actual time=0.918..0.918 rows=8 loops=1)
        -> Filter: ((p.deleted_at is null) and (p.quantity > 0))  (cost=203 rows=2000) (actual time=0.0389..0.416 rows=1600 loops=1)
            -> Table scan on p  (cost=203 rows=2000) (actual time=0.0373..0.311 rows=2000 loops=1)


```

### reco.member_purchases (RecommendationRepository.purchasedBy)

```sql
SELECT DISTINCT d.product_id FROM order_detail d JOIN shop_order o ON o.order_id = d.order_id WHERE o.member_id = 'u1@example.com' AND o.order_status <> 'CANCELLED' ORDER BY d.product_id
```

```
-> Sort: d.product_id  (actual time=22.1..22.2 rows=1454 loops=1)
    -> Table scan on <temporary>  (cost=1276..1300 rows=1761) (actual time=20.8..20.8 rows=1454 loops=1)
        -> Temporary table with deduplication  (cost=1276..1276 rows=1761) (actual time=20.8..20.8 rows=1454 loops=1)
            -> Nested loop inner join  (cost=1099 rows=1761) (actual time=0.252..20 rows=3436 loops=1)
                -> Filter: (o.order_status <> 'CANCELLED')  (cost=443 rows=738) (actual time=0.224..1.58 rows=1379 loops=1)
                    -> Index lookup on o using idx_shop_order_member (member_id='u1@example.com')  (cost=443 rows=1475) (actual time=0.222..1.46 rows=1475 loops=1)
                -> Index lookup on d using fk_order_detail_order (order_id=o.order_id)  (cost=0.652 rows=2.39) (actual time=0.0128..0.0132 rows=2.49 loops=1379)


```

### reco.member_cart (RecommendationRepository.carted)

```sql
SELECT c.product_id FROM shopping_cart c JOIN member m ON m.id = c.member_id WHERE m.email = 'u1500@example.com' ORDER BY c.product_id
```

```
-> Covering index lookup on c using uk_shopping_cart_member_product (member_id='1500')  (cost=0.659 rows=4) (actual time=0.0061..0.00912 rows=4 loops=1)


```

### review.public_page (ProductReviewRepository.findVisible)

```sql
SELECT pr.id, pr.product_id, pr.member_id, COALESCE(NULLIF(m.display_name, ''), 'x') reviewer_name, pr.rating, pr.content, pr.visibility, pr.created_at, pr.updated_at FROM product_review pr JOIN member m ON m.id = pr.member_id WHERE pr.product_id = 'P00003' AND pr.visibility = 'VISIBLE' ORDER BY pr.created_at DESC, pr.id DESC LIMIT 10 OFFSET 0
```

```
-> Limit: 10 row(s)  (cost=10.9 rows=10) (actual time=0.0325..0.0706 rows=10 loops=1)
    -> Nested loop inner join  (cost=10.9 rows=10) (actual time=0.0319..0.0697 rows=10 loops=1)
        -> Index lookup on pr using idx_product_review_public (product_id='P00003', visibility='VISIBLE') (reverse)  (cost=3.5 rows=10) (actual time=0.0233..0.0387 rows=10 loops=1)
        -> Single-row index lookup on m using PRIMARY (id=pr.member_id)  (cost=0.651 rows=1) (actual time=0.00284..0.00286 rows=1 loops=10)


```

### review.public_count (ProductReviewRepository.countVisible)

```sql
SELECT COUNT(*) FROM product_review WHERE product_id = 'P00003' AND visibility = 'VISIBLE'
```

```
-> Aggregate: count(0)  (cost=2.3 rows=1) (actual time=0.0208..0.0209 rows=1 loops=1)
    -> Covering index lookup on product_review using idx_product_review_public (product_id='P00003', visibility='VISIBLE')  (cost=1.3 rows=10) (actual time=0.0138..0.0184 rows=10 loops=1)


```

### review.public_avg (ProductReviewRepository.averageVisible)

```sql
SELECT ROUND(AVG(rating), 2) FROM product_review WHERE product_id = 'P00003' AND visibility = 'VISIBLE'
```

```
-> Aggregate: avg(product_review.rating)  (cost=4.5 rows=1) (actual time=0.0413..0.0413 rows=1 loops=1)
    -> Index lookup on product_review using idx_product_review_public (product_id='P00003', visibility='VISIBLE')  (cost=3.5 rows=10) (actual time=0.0234..0.037 rows=10 loops=1)


```

### review.verified_purchase (ProductReviewRepository.hasPurchased)

```sql
SELECT COUNT(*) FROM shop_order o JOIN order_detail d ON d.order_id = o.order_id JOIN member m ON m.email = o.member_id WHERE m.id = 1500 AND d.product_id = 'P00001'
```

```
-> Aggregate: count(0)  (cost=13.2 rows=1) (actual time=0.0602..0.0603 rows=1 loops=1)
    -> Nested loop inner join  (cost=11.2 rows=20.3) (actual time=0.0407..0.0577 rows=1 loops=1)
        -> Filter: ('u1500@example.com' = o.member_id)  (cost=3.94 rows=19.7) (actual time=0.0118..0.0172 rows=13 loops=1)
            -> Covering index lookup on o using idx_shop_order_member (member_id='u1500@example.com')  (cost=3.94 rows=19.7) (actual time=0.011..0.0147 rows=13 loops=1)
        -> Covering index lookup on d using idx_order_detail_product_order (product_id='P00001', order_id=o.order_id)  (cost=0.27 rows=1.03) (actual time=0.00285..0.00295 rows=0.0769 loops=13)


```

### review.seller_moderation (ProductReviewRepository.findForSeller)

```sql
SELECT pr.id, pr.product_id, pr.member_id, COALESCE(NULLIF(m.display_name, ''), 'x') reviewer_name, pr.rating, pr.content, pr.visibility, pr.created_at, pr.updated_at FROM product_review pr JOIN member m ON m.id = pr.member_id JOIN product p ON p.product_id = pr.product_id WHERE p.creator_id = 'u3@example.com' ORDER BY pr.created_at DESC, pr.id DESC LIMIT 20 OFFSET 0
```

```
-> Limit: 20 row(s)  (actual time=1.21..1.21 rows=20 loops=1)
    -> Sort: pr.created_at DESC, pr.id DESC, limit input to 20 row(s) per chunk  (actual time=1.21..1.21 rows=20 loops=1)
        -> Stream results  (cost=289 rows=402) (actual time=0.0458..1.14 rows=400 loops=1)
            -> Nested loop inner join  (cost=289 rows=402) (actual time=0.0408..0.959 rows=400 loops=1)
                -> Nested loop inner join  (cost=148 rows=402) (actual time=0.0346..0.604 rows=400 loops=1)
                    -> Covering index lookup on p using idx_product_creator (creator_id='u3@example.com')  (cost=7.67 rows=40) (actual time=0.0147..0.0267 rows=40 loops=1)
                    -> Index lookup on pr using idx_product_review_public (product_id=p.product_id)  (cost=2.54 rows=10) (actual time=0.00575..0.0139 rows=10 loops=40)
                -> Single-row index lookup on m using PRIMARY (id=pr.member_id)  (cost=0.25 rows=1) (actual time=752e-6..772e-6 rows=1 loops=400)


```

### review.admin_all (ProductReviewRepository.findForAdmin)

```sql
SELECT pr.id, pr.product_id, pr.member_id, COALESCE(NULLIF(m.display_name, ''), 'x') reviewer_name, pr.rating, pr.content, pr.visibility, pr.created_at, pr.updated_at FROM product_review pr JOIN member m ON m.id = pr.member_id ORDER BY pr.created_at DESC, pr.id DESC LIMIT 20 OFFSET 0
```

```
-> Limit: 20 row(s)  (cost=9084 rows=20) (actual time=6.72..6.75 rows=20 loops=1)
    -> Nested loop inner join  (cost=9084 rows=20097) (actual time=6.72..6.75 rows=20 loops=1)
        -> Sort: pr.created_at DESC, pr.id DESC  (cost=2050 rows=20097) (actual time=6.71..6.71 rows=20 loops=1)
            -> Table scan on pr  (cost=2050 rows=20097) (actual time=0.0304..3.35 rows=20000 loops=1)
        -> Single-row index lookup on m using PRIMARY (id=pr.member_id)  (cost=0.25 rows=1) (actual time=0.00183..0.00184 rows=1 loops=20)


```

### audit.latest (AuditLogRepository.find(no filter))

```sql
SELECT id, actor, actor_role, action, target_type, target_id, before_state, after_state, created_at FROM audit_log ORDER BY id DESC LIMIT 50 OFFSET 0
```

```
-> Limit: 50 row(s)  (cost=0.095 rows=50) (actual time=0.0242..0.0566 rows=50 loops=1)
    -> Index scan on audit_log using PRIMARY (reverse)  (cost=0.095 rows=50) (actual time=0.0235..0.053 rows=50 loops=1)


```

### audit.by_actor (AuditLogRepository.find(actor))

```sql
SELECT id, actor, actor_role, action, target_type, target_id, before_state, after_state, created_at FROM audit_log WHERE actor = 'u3@example.com' ORDER BY id DESC LIMIT 50 OFFSET 0
```

```
-> Limit: 50 row(s)  (cost=700 rows=50) (actual time=0.0185..0.0735 rows=50 loops=1)
    -> Index lookup on audit_log using idx_audit_log_actor (actor='u3@example.com') (reverse)  (cost=700 rows=2000) (actual time=0.0179..0.0712 rows=50 loops=1)


```

### audit.by_target (AuditLogRepository.find(target))

```sql
SELECT id, actor, actor_role, action, target_type, target_id, before_state, after_state, created_at FROM audit_log WHERE target_type = 'PRODUCT' AND target_id = 'P00001' ORDER BY id DESC LIMIT 50 OFFSET 0
```

```
-> Limit: 50 row(s)  (cost=11.6 rows=33) (actual time=0.022..0.134 rows=33 loops=1)
    -> Index lookup on audit_log using idx_audit_log_target (target_type='PRODUCT', target_id='P00001') (reverse)  (cost=11.6 rows=33) (actual time=0.0213..0.131 rows=33 loops=1)


```

### audit.action_window (AuditLogRepository.find(action+time))

```sql
SELECT id, actor, actor_role, action, target_type, target_id, before_state, after_state, created_at FROM audit_log WHERE action = 'PRODUCT_UPDATE' AND created_at >= NOW() - INTERVAL 7 DAY ORDER BY id DESC LIMIT 50 OFFSET 0
```

```
-> Limit: 50 row(s)  (cost=892 rows=50) (actual time=0.0779..2.58 rows=50 loops=1)
    -> Filter: (audit_log.created_at >= <cache>((now() - interval 7 day)))  (cost=892 rows=563) (actual time=0.0775..2.58 rows=50 loops=1)
        -> Index range scan on audit_log using idx_audit_log_action over (action = 'PRODUCT_UPDATE') (reverse), with index condition: (audit_log.`action` = 'PRODUCT_UPDATE')  (cost=892 rows=28302) (actual time=0.0183..2.48 rows=2617 loops=1)


```

### audit.count_action_window (AuditLogRepository.count)

```sql
SELECT COUNT(*) FROM audit_log WHERE action = 'PRODUCT_UPDATE' AND created_at >= NOW() - INTERVAL 7 DAY
```

```
-> Aggregate: count(0)  (cost=948 rows=1) (actual time=2.2..2.2 rows=1 loops=1)
    -> Filter: (audit_log.`action` = 'PRODUCT_UPDATE')  (cost=892 rows=563) (actual time=0.139..2.19 rows=326 loops=1)
        -> Index range scan on audit_log using idx_audit_log_created over ('2026-09-22 03:47:22.000' <= created_at), with index condition: (audit_log.created_at >= <cache>((now() - interval 7 day)))  (cost=892 rows=1982) (actual time=0.137..2.06 rows=1982 loops=1)


```

### cart.items_by_email (CartRepository.findItems)

```sql
SELECT c.id, c.product_id, p.product_name, p.price, p.quantity AS stock FROM shopping_cart c JOIN member m ON m.id = c.member_id JOIN product p ON p.product_id = c.product_id WHERE m.email = 'u1500@example.com' ORDER BY c.id
```

```
-> Nested loop inner join  (cost=2.06 rows=4) (actual time=0.0228..0.0284 rows=4 loops=1)
    -> Sort: c.id  (cost=0.659 rows=4) (actual time=0.0148..0.015 rows=4 loops=1)
        -> Index lookup on c using uk_shopping_cart_member_product (member_id='1500')  (cost=0.659 rows=4) (actual time=0.00479..0.00746 rows=4 loops=1)
    -> Single-row index lookup on p using PRIMARY (product_id=c.product_id)  (cost=0.275 rows=1) (actual time=0.00292..0.00294 rows=1 loops=4)


```

### cart.member_lock (CartRepository.lockMember)

```sql
SELECT id FROM member WHERE email = 'u1500@example.com' FOR UPDATE
```

```
-> Rows fetched before execution  (cost=0..0 rows=1) (actual time=30e-6..50e-6 rows=1 loops=1)


```

### coupon.by_code (CouponRepository.findByCode)

```sql
SELECT * FROM coupon WHERE code = 'SAVE5'
```

```
-> Rows fetched before execution  (cost=0..0 rows=1) (actual time=30e-6..60e-6 rows=1 loops=1)


```

## EXPLAIN-only (mutating) shapes

### product.update_seller_pk (ProductRepository.update)

```sql
UPDATE product SET product_name = 'x', price = 10 WHERE product_id = 'P01200' AND creator_id = 'u3@example.com' AND deleted_at IS NULL
```

```
[{"id": "1", "select_type": "UPDATE", "table": "product", "partitions": "NULL", "type": "range", "possible_keys": "PRIMARY,idx_product_creator", "key": "PRIMARY", "key_len": "82", "ref": "const", "rows": "1", "filtered": "100.00", "Extra": "Using where"}]
```

### product.restock_pk (ProductRepository.restock)

```sql
UPDATE product SET quantity = quantity + 5 WHERE product_id = 'P01200' AND creator_id = 'u3@example.com' AND deleted_at IS NULL
```

```
[{"id": "1", "select_type": "UPDATE", "table": "product", "partitions": "NULL", "type": "range", "possible_keys": "PRIMARY,idx_product_creator", "key": "PRIMARY", "key_len": "82", "ref": "const", "rows": "1", "filtered": "100.00", "Extra": "Using where"}]
```

### order.status_cas (OrderRepository.updateStatus)

```sql
UPDATE shop_order SET order_status = 'CONFIRMED' WHERE order_id = 'O00050000' AND order_status = 'CREATED'
```

```
[{"id": "1", "select_type": "UPDATE", "table": "shop_order", "partitions": "NULL", "type": "range", "possible_keys": "PRIMARY,idx_shop_order_status", "key": "PRIMARY", "key_len": "122", "ref": "const", "rows": "1", "filtered": "100.00", "Extra": "Using where"}]
```

### payment.cas (PaymentRepository.update)

```sql
UPDATE payment SET status = 'SUCCEEDED' WHERE id = 5 AND status = 'INITIATED'
```

```
[{"id": "1", "select_type": "UPDATE", "table": "payment", "partitions": "NULL", "type": "range", "possible_keys": "PRIMARY", "key": "PRIMARY", "key_len": "8", "ref": "const", "rows": "1", "filtered": "100.00", "Extra": "Using where"}]
```
