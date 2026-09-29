# SQLPERF capture: deferred-run1

MySQL 8.0.46, 16 logical CPUs (Docker Desktop VM shared with the host), buffer pool 128 MB, seed 17.1s. Median of 5 warm EXPLAIN ANALYZE runs (one discarded warm-up).

| id | workload | median ms | returned | rows read (approx) | flags |
|---|---|---:|---:|---:|---|
| product.catalog_all | catalog | 0.452 | 1800 | 2000 | full_scan, no_key |
| product.by_pk | checkout | const | -1 | 0 |  |
| product.lock_by_pk | checkout | const | -1 | 0 |  |
| product.in_20_sellable | catalog/checkout | 0.073 | 19 | 19 |  |
| product.seller_page | seller-admin | 0.071 | 20 | 20 |  |
| product.seller_search | seller-admin | 0.123 | 0 | 40 |  |
| product.seller_count | seller-admin | 0.026 | 1 | 40 |  |
| product.rating_all | catalog | 10.5 | 1800 | 20000 |  |
| product.rating_in_20 | catalog | 0.049 | 0 | 0 |  |
| product.images_in_20 | catalog | 0.123 | 38 | 38 |  |
| order.member_page_hot | buyer-orders | 0.539 | 20 | 40 | filesort |
| order.member_page_typical | buyer-orders | 0.178 | 20 | 40 | filesort |
| order.member_page_status | buyer-orders | 0.484 | 20 | 69 | filesort |
| order.member_count_hot | buyer-orders | 0.302 | 1 | 1393 |  |
| order.seller_page | seller-orders | 15.1 | 20 | 13166 | full_scan, filesort, temporary, no_key |
| order.seller_count | seller-orders | 8.39 | 1 | 13126 | full_scan, no_key |
| order.admin_status_page | admin-orders | 0.345 | 20 | 40 | filesort |
| order.lines_in_20 | buyer-orders | 0.26 | 42 | 84 | filesort |
| order.history_in_20 | buyer-orders | 0.118 | 40 | 40 |  |
| order.header_lock | order-status | const | -1 | 1 |  |
| order.request_by_pk | checkout | const | -1 | 0 |  |
| payment.by_trade_no | payment-callback | const | -1 | 0 |  |
| payment.initiated_for_order | payment | 0.022 | 0 | 1 |  |
| payment.order_lock | payment-callback | const | -1 | 0 |  |
| reco.co_purchase | recommendation | 539.0 | 8 | 198676 | filesort, temporary |
| reco.popular | recommendation | 521.0 | 8 | 203600 | full_scan, filesort, temporary, no_key |
| reco.new_arrival | recommendation | 0.909 | 8 | 2000 | full_scan, filesort, no_key |
| reco.member_purchases | recommendation | 13.4 | 1437 | 2830 | filesort, temporary |
| reco.member_cart | recommendation | 0.007 | 4 | 4 |  |
| review.public_page | reviews | 0.07 | 10 | 20 |  |
| review.public_count | reviews | 0.02 | 1 | 10 |  |
| review.public_avg | reviews | 0.038 | 1 | 10 |  |
| review.verified_purchase | reviews | 0.09 | 1 | 23 |  |
| review.seller_moderation | reviews | 1.2 | 20 | 440 | filesort, temporary |
| review.admin_all | reviews | 6.72 | 20 | 20020 | full_scan, filesort, no_key |
| audit.latest | audit | 0.054 | 50 | 50 |  |
| audit.by_actor | audit | 0.139 | 50 | 50 |  |
| audit.by_target | audit | 0.122 | 33 | 33 |  |
| audit.action_window | audit | 2.79 | 50 | 2704 |  |
| audit.count_action_window | audit | 3.77 | 1 | 1858 |  |
| cart.items_by_email | cart | 0.032 | 4 | 8 | filesort |
| cart.member_lock | cart/checkout | const | -1 | 0 |  |
| coupon.by_code | coupon | const | -1 | 0 |  |

## Table sizes

| table | rows (est) | data MB | index MB |
|---|---:|---:|---:|
| order_status_history | 198973 | 15.55 | 10.52 |
| order_detail | 249174 | 15.55 | 24.06 |
| audit_log | 99630 | 11.52 | 16.06 |
| shop_order | 99728 | 8.52 | 20.06 |
| payment | 89650 | 8.52 | 8.55 |
| order_request | 99974 | 6.52 | 0.00 |
| product_review | 20097 | 2.52 | 3.03 |
| shopping_cart | 9847 | 1.52 | 1.08 |
| shipping_address | 5070 | 0.48 | 0.28 |
| member | 4987 | 0.38 | 0.30 |
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
-> Filter: (product.deleted_at is null)  (cost=203 rows=200) (actual time=0.0416..0.452 rows=1800 loops=1)
    -> Table scan on product  (cost=203 rows=2000) (actual time=0.0408..0.366 rows=2000 loops=1)


```

### product.by_pk (ProductRepository.findActiveById)

```sql
SELECT product_id, product_name, price, quantity, creator_id, deleted_at FROM product WHERE product_id = 'P01200' AND deleted_at IS NULL
```

```
-> Rows fetched before execution  (cost=0..0 rows=1) (actual time=50e-6..80e-6 rows=1 loops=1)


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
-> Filter: ((product.product_id in ('P00101','P00201','P00301','P00401','P00501','P00601','P00701','P00801','P00901','P01001','P01101','P01201','P01301','P01401','P01501','P01601','P01701','P01801','P01901','P02001')) and (product.deleted_at is null) and (product.quantity > 0))  (cost=9.02 rows=0.667) (actual time=0.0268..0.0752 rows=19 loops=1)
    -> Index range scan on product using PRIMARY over (product_id = 'P00101') OR (product_id = 'P00201') OR (18 more)  (cost=9.02 rows=20) (actual time=0.0244..0.0682 rows=19 loops=1)


```

### product.seller_page (ProductRepository.findBySeller)

```sql
SELECT product_id, product_name, price, quantity, creator_id, deleted_at FROM product WHERE creator_id = 'u3@example.com' ORDER BY product_id LIMIT 20 OFFSET 0
```

```
-> Limit: 20 row(s)  (cost=13.8 rows=20) (actual time=0.112..0.201 rows=20 loops=1)
    -> Index lookup on product using idx_product_creator (creator_id='u3@example.com')  (cost=13.8 rows=40) (actual time=0.111..0.199 rows=20 loops=1)


```

### product.seller_search (ProductRepository.search)

```sql
SELECT product_id, product_name, price, quantity, creator_id, deleted_at FROM product WHERE creator_id = 'u3@example.com' AND (LOWER(product_id) LIKE '%chair%' ESCAPE '\\' OR LOWER(product_name) LIKE '%chair%' ESCAPE '\\') AND deleted_at IS NULL ORDER BY product_id LIMIT 20 OFFSET 0
```

```
-> Limit: 20 row(s)  (cost=10.2 rows=4) (actual time=0.103..0.103 rows=0 loops=1)
    -> Filter: (((lower(product.product_id) like '%chair%' escape '\\') or (lower(product.product_name) like '%chair%' escape '\\')) and (product.deleted_at is null))  (cost=10.2 rows=4) (actual time=0.103..0.103 rows=0 loops=1)
        -> Index lookup on product using idx_product_creator (creator_id='u3@example.com')  (cost=10.2 rows=40) (actual time=0.0287..0.0914 rows=40 loops=1)


```

### product.seller_count (ProductRepository.count)

```sql
SELECT COUNT(*) FROM product WHERE creator_id = 'u3@example.com'
```

```
-> Aggregate: count(0)  (cost=9.47 rows=1) (actual time=0.0277..0.0278 rows=1 loops=1)
    -> Covering index lookup on product using idx_product_creator (creator_id='u3@example.com')  (cost=5.47 rows=40) (actual time=0.0144..0.0245 rows=40 loops=1)


```

### product.rating_all (ProductRepository.ratingSummaries(all))

```sql
SELECT product_id, ROUND(AVG(rating), 2) average_rating, COUNT(*) review_count FROM product_review WHERE visibility = 'VISIBLE' GROUP BY product_id
```

```
-> Group aggregate: avg(product_review.rating), count(0)  (cost=2251 rows=2000) (actual time=0.073..10.7 rows=1800 loops=1)
    -> Filter: (product_review.visibility = 'VISIBLE')  (cost=2050 rows=2010) (actual time=0.0672..9.32 rows=18000 loops=1)
        -> Index scan on product_review using idx_product_review_public  (cost=2050 rows=20097) (actual time=0.0636..8.03 rows=20000 loops=1)


```

### product.rating_in_20 (ProductRepository.ratingSummaries(ids))

```sql
SELECT product_id, ROUND(AVG(rating), 2) average_rating, COUNT(*) review_count FROM product_review WHERE visibility = 'VISIBLE' AND product_id IN ('P00101','P00201','P00301','P00401','P00501','P00601','P00701','P00801','P00901','P01001','P01101','P01201','P01301','P01401','P01501','P01601','P01701','P01801','P01901','P02001') GROUP BY product_id
```

```
-> Group aggregate: avg(product_review.rating), count(0)  (cost=16 rows=20) (actual time=0.0384..0.0384 rows=0 loops=1)
    -> Index range scan on product_review using idx_product_review_public over (product_id = 'P00101' AND visibility = 'VISIBLE') OR (product_id = 'P00201' AND visibility = 'VISIBLE') OR (18 more), with index condition: ((product_review.visibility = 'VISIBLE') and (product_review.product_id in ('P00101','P00201','P00301','P00401','P00501','P00601','P00701','P00801','P00901','P01001','P01101','P01201','P01301','P01401','P01501','P01601','P01701','P01801','P01901','P02001')))  (cost=14 rows=20) (actual time=0.0373..0.0373 rows=0 loops=1)


```

### product.images_in_20 (ProductRepository.imagesFor)

```sql
SELECT product_id, image_url FROM product_image WHERE product_id IN ('P00101','P00201','P00301','P00401','P00501','P00601','P00701','P00801','P00901','P01001','P01101','P01201','P01301','P01401','P01501','P01601','P01701','P01801','P01901','P02001') ORDER BY product_id, display_order, image_id
```

```
-> Index range scan on product_image using idx_product_image_product over (product_id = 'P00101') OR (product_id = 'P00201') OR (18 more), with index condition: (product_image.product_id in ('P00101','P00201','P00301','P00401','P00501','P00601','P00701','P00801','P00901','P01001','P01101','P01201','P01301','P01401','P01501','P01601','P01701','P01801','P01901','P02001'))  (cost=22.6 rows=39) (actual time=0.0255..0.172 rows=38 loops=1)


```

### order.member_page_hot (OrderRepository.findByMember(hot))

```sql
SELECT o.order_id, o.member_id, o.order_status, o.price, o.created_at, a.receiver_name, a.phone, CONCAT_WS(' ', a.postal_code, a.address) AS full_address, o.pay_status, o.coupon_id, o.coupon_code, o.discount_amount, (SELECT p.status FROM payment p WHERE p.order_id = o.order_id ORDER BY p.id DESC LIMIT 1) AS payment_status FROM shop_order o LEFT JOIN shipping_address a ON a.id = o.shipping_address_id WHERE o.member_id = 'u1@example.com' ORDER BY o.created_at DESC, o.order_id DESC LIMIT 20 OFFSET 0
```

```
-> Limit: 20 row(s)  (cost=1924 rows=20) (actual time=0.718..0.751 rows=20 loops=1)
    -> Nested loop left join  (cost=1924 rows=1393) (actual time=0.717..0.749 rows=20 loops=1)
        -> Index lookup on o using idx_shop_order_member (member_id='u1@example.com') (reverse)  (cost=1228 rows=1393) (actual time=0.707..0.712 rows=20 loops=1)
        -> Single-row index lookup on a using PRIMARY (id=o.shipping_address_id)  (cost=0.4 rows=1) (actual time=0.00167..0.00169 rows=1 loops=20)
-> Select #2 (subquery in projection; dependent)
    -> Limit: 1 row(s)  (cost=0.35 rows=1) (actual time=0.0074..0.00742 rows=0.95 loops=20)
        -> Sort: p.id DESC, limit input to 1 row(s) per chunk  (cost=0.35 rows=1) (actual time=0.0073..0.0073 rows=0.95 loops=20)
            -> Index lookup on p using idx_payment_order (order_id=o.order_id)  (cost=0.35 rows=1) (actual time=0.00595..0.00621 rows=0.95 loops=20)


```

### order.member_page_typical (OrderRepository.findByMember(typical))

```sql
SELECT o.order_id, o.member_id, o.order_status, o.price, o.created_at, a.receiver_name, a.phone, CONCAT_WS(' ', a.postal_code, a.address) AS full_address, o.pay_status, o.coupon_id, o.coupon_code, o.discount_amount, (SELECT p.status FROM payment p WHERE p.order_id = o.order_id ORDER BY p.id DESC LIMIT 1) AS payment_status FROM shop_order o LEFT JOIN shipping_address a ON a.id = o.shipping_address_id WHERE o.member_id = 'u1500@example.com' ORDER BY o.created_at DESC, o.order_id DESC LIMIT 20 OFFSET 0
```

```
-> Limit: 20 row(s)  (cost=28.5 rows=20) (actual time=0.0789..0.227 rows=20 loops=1)
    -> Nested loop left join  (cost=28.5 rows=23) (actual time=0.0785..0.226 rows=20 loops=1)
        -> Index lookup on o using idx_shop_order_member (member_id='u1500@example.com') (reverse)  (cost=19.9 rows=23) (actual time=0.0698..0.154 rows=20 loops=1)
        -> Single-row index lookup on a using PRIMARY (id=o.shipping_address_id)  (cost=0.279 rows=1) (actual time=0.00342..0.00344 rows=1 loops=20)
-> Select #2 (subquery in projection; dependent)
    -> Limit: 1 row(s)  (cost=0.35 rows=1) (actual time=0.0076..0.00762 rows=0.9 loops=20)
        -> Sort: p.id DESC, limit input to 1 row(s) per chunk  (cost=0.35 rows=1) (actual time=0.00749..0.00749 rows=0.9 loops=20)
            -> Index lookup on p using idx_payment_order (order_id=o.order_id)  (cost=0.35 rows=1) (actual time=0.0066..0.00686 rows=0.9 loops=20)


```

### order.member_page_status (OrderRepository.findByMember(status))

```sql
SELECT o.order_id, o.member_id, o.order_status, o.price, o.created_at, a.receiver_name, a.phone, CONCAT_WS(' ', a.postal_code, a.address) AS full_address, o.pay_status, o.coupon_id, o.coupon_code, o.discount_amount, (SELECT p.status FROM payment p WHERE p.order_id = o.order_id ORDER BY p.id DESC LIMIT 1) AS payment_status FROM shop_order o LEFT JOIN shipping_address a ON a.id = o.shipping_address_id WHERE o.member_id = 'u1@example.com' AND o.order_status = 'DELIVERED' ORDER BY o.created_at DESC, o.order_id DESC LIMIT 20 OFFSET 0
```

```
-> Limit: 20 row(s)  (cost=1394 rows=20) (actual time=1.14..1.17 rows=20 loops=1)
    -> Nested loop left join  (cost=1394 rows=696) (actual time=1.14..1.16 rows=20 loops=1)
        -> Filter: (o.order_status = 'DELIVERED')  (cost=1133 rows=696) (actual time=1.13..1.14 rows=20 loops=1)
            -> Index lookup on o using idx_shop_order_member (member_id='u1@example.com') (reverse)  (cost=1133 rows=1393) (actual time=1.13..1.14 rows=49 loops=1)
        -> Single-row index lookup on a using PRIMARY (id=o.shipping_address_id)  (cost=0.275 rows=1) (actual time=968e-6..985e-6 rows=1 loops=20)
-> Select #2 (subquery in projection; dependent)
    -> Limit: 1 row(s)  (cost=0.35 rows=1) (actual time=0.00965..0.00967 rows=1 loops=20)
        -> Sort: p.id DESC, limit input to 1 row(s) per chunk  (cost=0.35 rows=1) (actual time=0.00956..0.00956 rows=1 loops=20)
            -> Index lookup on p using idx_payment_order (order_id=o.order_id)  (cost=0.35 rows=1) (actual time=0.0088..0.00899 rows=1 loops=20)


```

### order.member_count_hot (OrderRepository.countByMember)

```sql
SELECT COUNT(*) FROM shop_order o WHERE o.member_id = 'u1@example.com'
```

```
-> Aggregate: count(0)  (cost=344 rows=1) (actual time=0.297..0.297 rows=1 loops=1)
    -> Covering index lookup on o using idx_shop_order_member (member_id='u1@example.com')  (cost=205 rows=1393) (actual time=0.0347..0.26 rows=1393 loops=1)


```

### order.seller_page (OrderRepository.findBySeller)

```sql
SELECT o.order_id, o.member_id, o.order_status, o.price, o.created_at, a.receiver_name, a.phone, CONCAT_WS(' ', a.postal_code, a.address) AS full_address, o.pay_status, o.coupon_id, o.coupon_code, o.discount_amount, (SELECT p.status FROM payment p WHERE p.order_id = o.order_id ORDER BY p.id DESC LIMIT 1) AS payment_status FROM (SELECT o.* FROM shop_order o WHERE EXISTS (SELECT 1 FROM order_detail d JOIN product p ON p.product_id = d.product_id WHERE d.order_id = o.order_id AND p.creator_id = 'u3@example.com') ORDER BY o.created_at DESC, o.order_id DESC LIMIT 20 OFFSET 0) o LEFT JOIN shipping_address a ON a.id = o.shipping_address_id ORDER BY o.created_at DESC, o.order_id DESC
```

```
-> Nested loop left join  (cost=3.1 rows=0) (actual time=14.4..14.6 rows=20 loops=1)
    -> Sort: o.created_at DESC, o.order_id DESC  (cost=2.6..2.6 rows=0) (actual time=14.4..14.4 rows=20 loops=1)
        -> Table scan on o  (cost=2.5..2.5 rows=0) (actual time=14.4..14.4 rows=20 loops=1)
            -> Materialize  (cost=0..0 rows=0) (actual time=14.4..14.4 rows=20 loops=1)
                -> Limit: 20 row(s)  (actual time=14.3..14.3 rows=20 loops=1)
                    -> Sort: o.created_at DESC, o.order_id DESC, limit input to 20 row(s) per chunk  (actual time=14.3..14.3 rows=20 loops=1)
                        -> Stream results  (cost=7.93e+6 rows=5088) (actual time=2.23..10.6 rows=6486 loops=1)
                            -> Nested loop inner join  (cost=7.93e+6 rows=5088) (actual time=2.23..8.99 rows=6486 loops=1)
                                -> Table scan on <subquery4>  (cost=1119..1185 rows=5088) (actual time=2.22..2.71 rows=6486 loops=1)
                                    -> Materialize with deduplication  (cost=1119..1119 rows=5088) (actual time=2.22..2.22 rows=6486 loops=1)
                                        -> Nested loop inner join  (cost=610 rows=5088) (actual time=0.0241..1.41 rows=6609 loops=1)
                                            -> Covering index lookup on p using idx_product_creator (creator_id='u3@example.com')  (cost=5.47 rows=40) (actual time=0.0106..0.0189 rows=40 loops=1)
                                            -> Covering index lookup on d using idx_order_detail_product_order (product_id=p.product_id)  (cost=2.71 rows=127) (actual time=0.00577..0.0289 rows=165 loops=40)
                                -> Single-row index lookup on o using PRIMARY (order_id=`<subquery4>`.order_id)  (cost=7.92e+6 rows=1) (actual time=862e-6..879e-6 rows=1 loops=6486)
    -> Single-row index lookup on a using PRIMARY (id=o.shipping_address_id)  (cost=0.3 rows=1) (actual time=0.00975..0.00978 rows=1 loops=20)
-> Select #2 (subquery in projection; dependent)
    -> Limit: 1 row(s)  (cost=0.35 rows=1) (actual time=0.0113..0.0114 rows=0.95 loops=20)
        -> Sort: p.id DESC, limit input to 1 row(s) per chunk  (cost=0.35 rows=1) (actual time=0.0112..0.0112 rows=0.95 loops=20)
            -> Index lookup on p using idx_payment_order (order_id=o.order_id)  (cost=0.35 rows=1) (actual time=0.00974..0.0101 rows=0.95 loops=20)


```

### order.seller_count (OrderRepository.countBySeller)

```sql
SELECT COUNT(*) FROM shop_order o WHERE EXISTS (SELECT 1 FROM order_detail d JOIN product p ON p.product_id = d.product_id WHERE d.order_id = o.order_id AND p.creator_id = 'u3@example.com')
```

```
-> Aggregate: count(0)  (cost=7.93e+6 rows=1) (actual time=8.39..8.39 rows=1 loops=1)
    -> Nested loop inner join  (cost=7.93e+6 rows=5088) (actual time=2.55..8.21 rows=6486 loops=1)
        -> Table scan on <subquery2>  (cost=1119..1185 rows=5088) (actual time=2.54..2.95 rows=6486 loops=1)
            -> Materialize with deduplication  (cost=1119..1119 rows=5088) (actual time=2.54..2.54 rows=6486 loops=1)
                -> Nested loop inner join  (cost=610 rows=5088) (actual time=0.0272..1.6 rows=6609 loops=1)
                    -> Covering index lookup on p using idx_product_creator (creator_id='u3@example.com')  (cost=5.47 rows=40) (actual time=0.0122..0.0206 rows=40 loops=1)
                    -> Covering index lookup on d using idx_order_detail_product_order (product_id=p.product_id)  (cost=2.71 rows=127) (actual time=0.00575..0.0326 rows=165 loops=40)
        -> Single-row covering index lookup on o using PRIMARY (order_id=`<subquery2>`.order_id)  (cost=7.92e+6 rows=1) (actual time=704e-6..721e-6 rows=1 loops=6486)


```

### order.admin_status_page (OrderRepository.findBySeller(admin,status))

```sql
SELECT o.order_id, o.member_id, o.order_status, o.price, o.created_at, a.receiver_name, a.phone, CONCAT_WS(' ', a.postal_code, a.address) AS full_address, o.pay_status, o.coupon_id, o.coupon_code, o.discount_amount, (SELECT p.status FROM payment p WHERE p.order_id = o.order_id ORDER BY p.id DESC LIMIT 1) AS payment_status FROM shop_order o LEFT JOIN shipping_address a ON a.id = o.shipping_address_id WHERE o.order_status = 'CREATED' ORDER BY o.created_at DESC, o.order_id DESC LIMIT 20 OFFSET 0
```

```
-> Limit: 20 row(s)  (cost=13715 rows=20) (actual time=0.278..0.313 rows=20 loops=1)
    -> Nested loop left join  (cost=13715 rows=29570) (actual time=0.277..0.312 rows=20 loops=1)
        -> Index lookup on o using idx_shop_order_status (order_status='CREATED') (reverse)  (cost=3366 rows=29570) (actual time=0.27..0.275 rows=20 loops=1)
        -> Single-row index lookup on a using PRIMARY (id=o.shipping_address_id)  (cost=0.25 rows=1) (actual time=0.00165..0.00167 rows=1 loops=20)
-> Select #2 (subquery in projection; dependent)
    -> Limit: 1 row(s)  (cost=0.35 rows=1) (actual time=0.00749..0.00751 rows=0.85 loops=20)
        -> Sort: p.id DESC, limit input to 1 row(s) per chunk  (cost=0.35 rows=1) (actual time=0.00737..0.00737 rows=0.85 loops=20)
            -> Index lookup on p using idx_payment_order (order_id=o.order_id)  (cost=0.35 rows=1) (actual time=0.00651..0.00673 rows=0.85 loops=20)


```

### order.lines_in_20 (OrderRepository.findLines)

```sql
SELECT d.order_id, d.product_id, p.product_name, d.quantity, d.unit_price, d.item_price, p.creator_id FROM order_detail d JOIN product p ON p.product_id = d.product_id WHERE d.order_id IN ('O00050000','O00050001','O00050002','O00050003','O00050004','O00050005','O00050006','O00050007','O00050008','O00050009','O00050010','O00050011','O00050012','O00050013','O00050014','O00050015','O00050016','O00050017','O00050018','O00050019') ORDER BY d.order_id, d.product_id
```

```
-> Nested loop inner join  (cost=72.1 rows=42) (actual time=0.185..0.257 rows=42 loops=1)
    -> Sort: d.order_id, d.product_id  (cost=57.4 rows=42) (actual time=0.178..0.179 rows=42 loops=1)
        -> Index range scan on d using fk_order_detail_order over (order_id = 'O00050000') OR (order_id = 'O00050001') OR (18 more), with index condition: (d.order_id in ('O00050000','O00050001','O00050002','O00050003','O00050004','O00050005','O00050006','O00050007','O00050008','O00050009','O00050010','O00050011','O00050012','O00050013','O00050014','O00050015','O00050016','O00050017','O00050018','O00050019'))  (cost=57.4 rows=42) (actual time=0.0311..0.137 rows=42 loops=1)
    -> Single-row index lookup on p using PRIMARY (product_id=d.product_id)  (cost=0.252 rows=1) (actual time=0.00169..0.00171 rows=1 loops=42)


```

### order.history_in_20 (OrderRepository.findHistory)

```sql
SELECT order_id, from_status, to_status, actor_role, created_at FROM order_status_history WHERE order_id IN ('O00050000','O00050001','O00050002','O00050003','O00050004','O00050005','O00050006','O00050007','O00050008','O00050009','O00050010','O00050011','O00050012','O00050013','O00050014','O00050015','O00050016','O00050017','O00050018','O00050019') ORDER BY order_id, id
```

```
-> Index range scan on order_status_history using idx_order_status_history_order over (order_id = 'O00050000') OR (order_id = 'O00050001') OR (18 more), with index condition: (order_status_history.order_id in ('O00050000','O00050001','O00050002','O00050003','O00050004','O00050005','O00050006','O00050007','O00050008','O00050009','O00050010','O00050011','O00050012','O00050013','O00050014','O00050015','O00050016','O00050017','O00050018','O00050019'))  (cost=23 rows=40) (actual time=0.0254..0.102 rows=40 loops=1)


```

### order.header_lock (OrderRepository.findHeaderForUpdate)

```sql
SELECT o.order_id, o.member_id, o.order_status, o.price, o.created_at, a.receiver_name, a.phone, CONCAT_WS(' ', a.postal_code, a.address) AS full_address, o.pay_status, o.coupon_id, o.coupon_code, o.discount_amount, (SELECT p.status FROM payment p WHERE p.order_id = o.order_id ORDER BY p.id DESC LIMIT 1) AS payment_status FROM shop_order o LEFT JOIN shipping_address a ON a.id = o.shipping_address_id WHERE o.order_id = 'O00050000' FOR UPDATE OF o
```

```
-> Rows fetched before execution  (cost=0..0 rows=1) (actual time=40e-6..80e-6 rows=1 loops=1)
-> Select #2 (subquery in projection; dependent)
    -> Limit: 1 row(s)  (cost=0.35 rows=1) (actual time=0.0102..0.0102 rows=1 loops=1)
        -> Index lookup on p using idx_payment_order (order_id='O00050000') (reverse)  (cost=0.35 rows=1) (actual time=0.00969..0.00969 rows=1 loops=1)


```

### order.request_by_pk (OrderRepository.findRequest)

```sql
SELECT request_id, order_id, member_id FROM order_request WHERE request_id = 'R00050000'
```

```
-> Rows fetched before execution  (cost=0..0 rows=1) (actual time=30e-6..60e-6 rows=1 loops=1)


```

### payment.by_trade_no (PaymentRepository.findByTradeNo)

```sql
SELECT id, order_id, merchant_trade_no, provider, amount, status, failure_reason, created_at, paid_at FROM payment WHERE merchant_trade_no = 'T50000'
```

```
-> Rows fetched before execution  (cost=0..0 rows=1) (actual time=40e-6..60e-6 rows=1 loops=1)


```

### payment.initiated_for_order (PaymentRepository.findInitiated)

```sql
SELECT id, order_id, merchant_trade_no, provider, amount, status, failure_reason, created_at, paid_at FROM payment WHERE order_id = 'O00050000' AND status = 'INITIATED'
```

```
-> Filter: (payment.`status` = 'INITIATED')  (cost=0.26 rows=0.1) (actual time=0.0219..0.0219 rows=0 loops=1)
    -> Index lookup on payment using idx_payment_order (order_id='O00050000')  (cost=0.26 rows=1) (actual time=0.0189..0.0202 rows=1 loops=1)


```

### payment.order_lock (PaymentRepository.lockOrder)

```sql
SELECT order_id, member_id, order_status, pay_status, price FROM shop_order WHERE order_id = 'O00050000' FOR UPDATE
```

```
-> Rows fetched before execution  (cost=0..0 rows=1) (actual time=40e-6..70e-6 rows=1 loops=1)


```

### reco.co_purchase (RecommendationRepository.coPurchased)

```sql
SELECT b.product_id, COUNT(DISTINCT o.member_id) AS score FROM order_detail a JOIN shop_order o ON o.order_id = a.order_id AND o.order_status <> 'CANCELLED' JOIN order_detail b ON b.order_id = a.order_id JOIN product p ON p.product_id = b.product_id AND p.deleted_at IS NULL AND p.quantity > 0 WHERE a.product_id IN ('P00001') AND b.product_id NOT IN ('P00001') GROUP BY b.product_id HAVING COUNT(DISTINCT o.member_id) >= 2 ORDER BY score DESC, b.product_id ASC LIMIT 8
```

```
-> Limit: 8 row(s)  (actual time=544..544 rows=8 loops=1)
    -> Sort: score DESC, b.product_id  (actual time=544..544 rows=8 loops=1)
        -> Filter: (`count(distinct shop_order.member_id)` >= 2)  (actual time=541..543 rows=1289 loops=1)
            -> Stream results  (actual time=541..543 rows=1524 loops=1)
                -> Group aggregate: count(distinct shop_order.member_id), count(distinct shop_order.member_id)  (actual time=541..543 rows=1524 loops=1)
                    -> Sort: b.product_id  (actual time=541..542 rows=6649 loops=1)
                        -> Stream results  (cost=2901 rows=2167) (actual time=0.238..535 rows=6649 loops=1)
                            -> Nested loop inner join  (cost=2901 rows=2167) (actual time=0.237..534 rows=6649 loops=1)
                                -> Nested loop inner join  (cost=2147 rows=2122) (actual time=0.0556..233 rows=186527 loops=1)
                                    -> Nested loop inner join  (cost=661 rows=4244) (actual time=0.0504..45.3 rows=196336 loops=1)
                                        -> Filter: ((p.product_id <> 'P00001') and (p.deleted_at is null) and (p.quantity > 0))  (cost=201 rows=33.4) (actual time=0.0373..1.03 rows=1599 loops=1)
                                            -> Index range scan on p using PRIMARY over (product_id < 'P00001') OR ('P00001' < product_id)  (cost=201 rows=1001) (actual time=0.0355..0.599 rows=1999 loops=1)
                                        -> Covering index lookup on b using idx_order_detail_product_order (product_id=p.product_id)  (cost=1.45 rows=127) (actual time=0.00384..0.0232 rows=123 loops=1599)
                                    -> Filter: (o.order_status <> 'CANCELLED')  (cost=0.25 rows=0.5) (actual time=815e-6..865e-6 rows=0.95 loops=196336)
                                        -> Single-row index lookup on o using PRIMARY (order_id=b.order_id)  (cost=0.25 rows=1) (actual time=716e-6..733e-6 rows=1 loops=196336)
                                -> Covering index lookup on a using idx_order_detail_product_order (product_id='P00001', order_id=b.order_id)  (cost=0.253 rows=1.02) (actual time=0.00153..0.00154 rows=0.0356 loops=186527)


```

### reco.popular (RecommendationRepository.popular)

```sql
SELECT d.product_id, COUNT(DISTINCT o.member_id) AS score FROM order_detail d JOIN shop_order o ON o.order_id = d.order_id AND o.order_status <> 'CANCELLED' JOIN product p ON p.product_id = d.product_id AND p.deleted_at IS NULL AND p.quantity > 0 WHERE 1 = 1 GROUP BY d.product_id HAVING COUNT(DISTINCT o.member_id) >= 2 ORDER BY score DESC, d.product_id ASC LIMIT 8
```

```
-> Limit: 8 row(s)  (actual time=503..503 rows=8 loops=1)
    -> Sort: score DESC, d.product_id  (actual time=503..503 rows=8 loops=1)
        -> Filter: (`count(distinct shop_order.member_id)` >= 2)  (actual time=427..502 rows=1600 loops=1)
            -> Stream results  (actual time=427..501 rows=1600 loops=1)
                -> Group aggregate: count(distinct shop_order.member_id), count(distinct shop_order.member_id)  (actual time=427..501 rows=1600 loops=1)
                    -> Sort: d.product_id  (actual time=424..431 rows=191799 loops=1)
                        -> Stream results  (cost=4092 rows=4239) (actual time=0.0441..256 rows=191799 loops=1)
                            -> Nested loop inner join  (cost=4092 rows=4239) (actual time=0.0434..229 rows=191799 loops=1)
                                -> Nested loop inner join  (cost=1124 rows=8479) (actual time=0.037..46.4 rows=201880 loops=1)
                                    -> Filter: ((p.deleted_at is null) and (p.quantity > 0))  (cost=203 rows=66.7) (actual time=0.023..0.793 rows=1600 loops=1)
                                        -> Table scan on p  (cost=203 rows=2000) (actual time=0.0218..0.484 rows=2000 loops=1)
                                    -> Covering index lookup on d using idx_order_detail_product_order (product_id=p.product_id)  (cost=1.29 rows=127) (actual time=0.00465..0.0239 rows=126 loops=1600)
                                -> Filter: (o.order_status <> 'CANCELLED')  (cost=0.25 rows=0.5) (actual time=767e-6..817e-6 rows=0.95 loops=201880)
                                    -> Single-row index lookup on o using PRIMARY (order_id=d.order_id)  (cost=0.25 rows=1) (actual time=672e-6..689e-6 rows=1 loops=201880)


```

### reco.new_arrival (RecommendationRepository.newArrivals)

```sql
SELECT p.product_id FROM product p WHERE p.deleted_at IS NULL AND p.quantity > 0 ORDER BY p.created_at DESC, p.product_id ASC LIMIT 8
```

```
-> Limit: 8 row(s)  (cost=203 rows=8) (actual time=1.22..1.22 rows=8 loops=1)
    -> Sort: p.created_at DESC, p.product_id, limit input to 8 row(s) per chunk  (cost=203 rows=2000) (actual time=1.22..1.22 rows=8 loops=1)
        -> Filter: ((p.deleted_at is null) and (p.quantity > 0))  (cost=203 rows=2000) (actual time=0.0441..0.543 rows=1600 loops=1)
            -> Table scan on p  (cost=203 rows=2000) (actual time=0.0421..0.404 rows=2000 loops=1)


```

### reco.member_purchases (RecommendationRepository.purchasedBy)

```sql
SELECT DISTINCT d.product_id FROM order_detail d JOIN shop_order o ON o.order_id = d.order_id WHERE o.member_id = 'u1@example.com' AND o.order_status <> 'CANCELLED' ORDER BY d.product_id
```

```
-> Sort: d.product_id  (actual time=20.6..20.6 rows=1437 loops=1)
    -> Table scan on <temporary>  (cost=1255..1280 rows=1765) (actual time=19.4..19.5 rows=1437 loops=1)
        -> Temporary table with deduplication  (cost=1255..1255 rows=1765) (actual time=19.4..19.4 rows=1437 loops=1)
            -> Nested loop inner join  (cost=1079 rows=1765) (actual time=0.47..18.8 rows=3282 loops=1)
                -> Filter: (o.order_status <> 'CANCELLED')  (cost=418 rows=697) (actual time=0.291..1.42 rows=1315 loops=1)
                    -> Index lookup on o using idx_shop_order_member (member_id='u1@example.com')  (cost=418 rows=1393) (actual time=0.29..1.33 rows=1393 loops=1)
                -> Index lookup on d using fk_order_detail_order (order_id=o.order_id)  (cost=0.696 rows=2.53) (actual time=0.0128..0.0131 rows=2.5 loops=1315)


```

### reco.member_cart (RecommendationRepository.carted)

```sql
SELECT c.product_id FROM shopping_cart c JOIN member m ON m.id = c.member_id WHERE m.email = 'u1500@example.com' ORDER BY c.product_id
```

```
-> Covering index lookup on c using uk_shopping_cart_member_product (member_id='1500')  (cost=0.659 rows=4) (actual time=0.00373..0.00616 rows=4 loops=1)


```

### review.public_page (ProductReviewRepository.findVisible)

```sql
SELECT pr.id, pr.product_id, pr.member_id, COALESCE(NULLIF(m.display_name, ''), 'x') reviewer_name, pr.rating, pr.content, pr.visibility, pr.created_at, pr.updated_at FROM product_review pr JOIN member m ON m.id = pr.member_id WHERE pr.product_id = 'P00003' AND pr.visibility = 'VISIBLE' ORDER BY pr.created_at DESC, pr.id DESC LIMIT 10 OFFSET 0
```

```
-> Limit: 10 row(s)  (cost=10.9 rows=10) (actual time=0.0307..0.0677 rows=10 loops=1)
    -> Nested loop inner join  (cost=10.9 rows=10) (actual time=0.0302..0.0668 rows=10 loops=1)
        -> Index lookup on pr using idx_product_review_public (product_id='P00003', visibility='VISIBLE') (reverse)  (cost=3.5 rows=10) (actual time=0.0223..0.0385 rows=10 loops=1)
        -> Single-row index lookup on m using PRIMARY (id=pr.member_id)  (cost=0.651 rows=1) (actual time=0.00258..0.0026 rows=1 loops=10)


```

### review.public_count (ProductReviewRepository.countVisible)

```sql
SELECT COUNT(*) FROM product_review WHERE product_id = 'P00003' AND visibility = 'VISIBLE'
```

```
-> Aggregate: count(0)  (cost=2.3 rows=1) (actual time=0.0202..0.0202 rows=1 loops=1)
    -> Covering index lookup on product_review using idx_product_review_public (product_id='P00003', visibility='VISIBLE')  (cost=1.3 rows=10) (actual time=0.0138..0.0179 rows=10 loops=1)


```

### review.public_avg (ProductReviewRepository.averageVisible)

```sql
SELECT ROUND(AVG(rating), 2) FROM product_review WHERE product_id = 'P00003' AND visibility = 'VISIBLE'
```

```
-> Aggregate: avg(product_review.rating)  (cost=4.5 rows=1) (actual time=0.0381..0.0381 rows=1 loops=1)
    -> Index lookup on product_review using idx_product_review_public (product_id='P00003', visibility='VISIBLE')  (cost=3.5 rows=10) (actual time=0.0215..0.0339 rows=10 loops=1)


```

### review.verified_purchase (ProductReviewRepository.hasPurchased)

```sql
SELECT COUNT(*) FROM shop_order o JOIN order_detail d ON d.order_id = o.order_id JOIN member m ON m.email = o.member_id WHERE m.id = 1500 AND d.product_id = 'P00001'
```

```
-> Aggregate: count(0)  (cost=11.5 rows=1) (actual time=0.0895..0.0896 rows=1 loops=1)
    -> Nested loop inner join  (cost=9.77 rows=17.5) (actual time=0.0884..0.0884 rows=0 loops=1)
        -> Filter: ('u1500@example.com' = o.member_id)  (cost=3.58 rows=17.2) (actual time=0.0136..0.0236 rows=23 loops=1)
            -> Covering index lookup on o using idx_shop_order_member (member_id='u1500@example.com')  (cost=3.58 rows=17.2) (actual time=0.0125..0.0196 rows=23 loops=1)
        -> Covering index lookup on d using idx_order_detail_product_order (product_id='P00001', order_id=o.order_id)  (cost=0.265 rows=1.02) (actual time=0.00269..0.00269 rows=0 loops=23)


```

### review.seller_moderation (ProductReviewRepository.findForSeller)

```sql
SELECT pr.id, pr.product_id, pr.member_id, COALESCE(NULLIF(m.display_name, ''), 'x') reviewer_name, pr.rating, pr.content, pr.visibility, pr.created_at, pr.updated_at FROM product_review pr JOIN member m ON m.id = pr.member_id JOIN product p ON p.product_id = pr.product_id WHERE p.creator_id = 'u3@example.com' ORDER BY pr.created_at DESC, pr.id DESC LIMIT 20 OFFSET 0
```

```
-> Limit: 20 row(s)  (actual time=1.19..1.2 rows=20 loops=1)
    -> Sort: pr.created_at DESC, pr.id DESC, limit input to 20 row(s) per chunk  (actual time=1.19..1.2 rows=20 loops=1)
        -> Stream results  (cost=289 rows=402) (actual time=0.039..1.12 rows=400 loops=1)
            -> Nested loop inner join  (cost=289 rows=402) (actual time=0.0343..0.943 rows=400 loops=1)
                -> Nested loop inner join  (cost=148 rows=402) (actual time=0.0292..0.618 rows=400 loops=1)
                    -> Covering index lookup on p using idx_product_creator (creator_id='u3@example.com')  (cost=7.67 rows=40) (actual time=0.0128..0.0223 rows=40 loops=1)
                    -> Index lookup on pr using idx_product_review_public (product_id=p.product_id)  (cost=2.54 rows=10) (actual time=0.00629..0.0144 rows=10 loops=40)
                -> Single-row index lookup on m using PRIMARY (id=pr.member_id)  (cost=0.25 rows=1) (actual time=680e-6..700e-6 rows=1 loops=400)


```

### review.admin_all (ProductReviewRepository.findForAdmin)

```sql
SELECT pr.id, pr.product_id, pr.member_id, COALESCE(NULLIF(m.display_name, ''), 'x') reviewer_name, pr.rating, pr.content, pr.visibility, pr.created_at, pr.updated_at FROM product_review pr JOIN member m ON m.id = pr.member_id ORDER BY pr.created_at DESC, pr.id DESC LIMIT 20 OFFSET 0
```

```
-> Limit: 20 row(s)  (cost=9084 rows=20) (actual time=6.65..6.68 rows=20 loops=1)
    -> Nested loop inner join  (cost=9084 rows=20097) (actual time=6.64..6.67 rows=20 loops=1)
        -> Sort: pr.created_at DESC, pr.id DESC  (cost=2050 rows=20097) (actual time=6.63..6.63 rows=20 loops=1)
            -> Table scan on pr  (cost=2050 rows=20097) (actual time=0.0335..3.55 rows=20000 loops=1)
        -> Single-row index lookup on m using PRIMARY (id=pr.member_id)  (cost=0.25 rows=1) (actual time=0.00184..0.00186 rows=1 loops=20)


```

### audit.latest (AuditLogRepository.find(no filter))

```sql
SELECT id, actor, actor_role, action, target_type, target_id, before_state, after_state, created_at FROM audit_log ORDER BY id DESC LIMIT 50 OFFSET 0
```

```
-> Limit: 50 row(s)  (cost=0.095 rows=50) (actual time=0.0221..0.044 rows=50 loops=1)
    -> Index scan on audit_log using PRIMARY (reverse)  (cost=0.095 rows=50) (actual time=0.0215..0.0413 rows=50 loops=1)


```

### audit.by_actor (AuditLogRepository.find(actor))

```sql
SELECT id, actor, actor_role, action, target_type, target_id, before_state, after_state, created_at FROM audit_log WHERE actor = 'u3@example.com' ORDER BY id DESC LIMIT 50 OFFSET 0
```

```
-> Limit: 50 row(s)  (cost=700 rows=50) (actual time=0.0189..0.0828 rows=50 loops=1)
    -> Index lookup on audit_log using idx_audit_log_actor (actor='u3@example.com') (reverse)  (cost=700 rows=2000) (actual time=0.0185..0.0802 rows=50 loops=1)


```

### audit.by_target (AuditLogRepository.find(target))

```sql
SELECT id, actor, actor_role, action, target_type, target_id, before_state, after_state, created_at FROM audit_log WHERE target_type = 'PRODUCT' AND target_id = 'P00001' ORDER BY id DESC LIMIT 50 OFFSET 0
```

```
-> Limit: 50 row(s)  (cost=11.6 rows=33) (actual time=0.0206..0.122 rows=33 loops=1)
    -> Index lookup on audit_log using idx_audit_log_target (target_type='PRODUCT', target_id='P00001') (reverse)  (cost=11.6 rows=33) (actual time=0.02..0.12 rows=33 loops=1)


```

### audit.action_window (AuditLogRepository.find(action+time))

```sql
SELECT id, actor, actor_role, action, target_type, target_id, before_state, after_state, created_at FROM audit_log WHERE action = 'PRODUCT_UPDATE' AND created_at >= NOW() - INTERVAL 7 DAY ORDER BY id DESC LIMIT 50 OFFSET 0
```

```
-> Limit: 50 row(s)  (cost=836 rows=50) (actual time=0.169..2.79 rows=50 loops=1)
    -> Filter: (audit_log.created_at >= <cache>((now() - interval 7 day)))  (cost=836 rows=528) (actual time=0.169..2.78 rows=50 loops=1)
        -> Index range scan on audit_log using idx_audit_log_action over (action = 'PRODUCT_UPDATE') (reverse), with index condition: (audit_log.`action` = 'PRODUCT_UPDATE')  (cost=836 rows=28302) (actual time=0.021..2.68 rows=2704 loops=1)


```

### audit.count_action_window (AuditLogRepository.count)

```sql
SELECT COUNT(*) FROM audit_log WHERE action = 'PRODUCT_UPDATE' AND created_at >= NOW() - INTERVAL 7 DAY
```

```
-> Aggregate: count(0)  (cost=889 rows=1) (actual time=2.06..2.06 rows=1 loops=1)
    -> Filter: (audit_log.`action` = 'PRODUCT_UPDATE')  (cost=836 rows=528) (actual time=0.143..2.05 rows=317 loops=1)
        -> Index range scan on audit_log using idx_audit_log_created over ('2026-09-22 03:56:47.000' <= created_at), with index condition: (audit_log.created_at >= <cache>((now() - interval 7 day)))  (cost=836 rows=1858) (actual time=0.142..1.93 rows=1858 loops=1)


```

### cart.items_by_email (CartRepository.findItems)

```sql
SELECT c.id, c.product_id, p.product_name, p.price, p.quantity AS stock FROM shopping_cart c JOIN member m ON m.id = c.member_id JOIN product p ON p.product_id = c.product_id WHERE m.email = 'u1500@example.com' ORDER BY c.id
```

```
-> Nested loop inner join  (cost=2.06 rows=4) (actual time=0.0247..0.0327 rows=4 loops=1)
    -> Sort: c.id  (cost=0.659 rows=4) (actual time=0.0164..0.0167 rows=4 loops=1)
        -> Index lookup on c using uk_shopping_cart_member_product (member_id='1500')  (cost=0.659 rows=4) (actual time=0.00581..0.00888 rows=4 loops=1)
    -> Single-row index lookup on p using PRIMARY (product_id=c.product_id)  (cost=0.275 rows=1) (actual time=0.0035..0.00354 rows=1 loops=4)


```

### cart.member_lock (CartRepository.lockMember)

```sql
SELECT id FROM member WHERE email = 'u1500@example.com' FOR UPDATE
```

```
-> Rows fetched before execution  (cost=0..0 rows=1) (actual time=60e-6..110e-6 rows=1 loops=1)


```

### coupon.by_code (CouponRepository.findByCode)

```sql
SELECT * FROM coupon WHERE code = 'SAVE5'
```

```
-> Rows fetched before execution  (cost=0..0 rows=1) (actual time=40e-6..80e-6 rows=1 loops=1)


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
