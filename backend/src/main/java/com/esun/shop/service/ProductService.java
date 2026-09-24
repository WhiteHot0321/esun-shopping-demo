package com.esun.shop.service;

import com.esun.shop.dto.CreateProductRequest;
import com.esun.shop.dto.UpdateProductRequest;
import com.esun.shop.dto.BulkProductRequest;
import com.esun.shop.dto.ProductPageResponse;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.llm.EmbeddingIndexService;
import com.esun.shop.model.AuditAction;
import com.esun.shop.model.Member;
import com.esun.shop.model.Product;
import com.esun.shop.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class ProductService {
    static final int MAX_RESTOCK_AMOUNT = 1_000_000;
    static final int MAX_IMAGES_PER_PRODUCT = 10;

    private final ProductRepository productRepository;
    private final EmbeddingIndexService embeddingIndexService;
    private final ProductImageStorageService imageStorageService;
    private final AuditLogService auditLogService;

    private final TransactionOperations transactions;

    @Autowired
    public ProductService(ProductRepository productRepository, EmbeddingIndexService embeddingIndexService,
                          ProductImageStorageService imageStorageService, AuditLogService auditLogService,
                          PlatformTransactionManager transactionManager) {
        this(productRepository, embeddingIndexService, imageStorageService, auditLogService,
                new TransactionTemplate(transactionManager));
    }

    /** 單元測試用：不開真實交易。 */
    ProductService(ProductRepository productRepository, EmbeddingIndexService embeddingIndexService,
                   ProductImageStorageService imageStorageService, AuditLogService auditLogService) {
        this(productRepository, embeddingIndexService, imageStorageService, auditLogService,
                TransactionOperations.withoutTransaction());
    }

    private ProductService(ProductRepository productRepository, EmbeddingIndexService embeddingIndexService,
                           ProductImageStorageService imageStorageService, AuditLogService auditLogService,
                           TransactionOperations transactions) {
        this.productRepository = productRepository;
        this.embeddingIndexService = embeddingIndexService;
        this.imageStorageService = imageStorageService;
        this.auditLogService = auditLogService;
        this.transactions = transactions;
    }

    public void createProduct(CreateProductRequest request) {
        createProduct(request, "legacy");
    }

    public void createProduct(CreateProductRequest request, String creatorId) {
        if (creatorId == null || creatorId.isBlank()) {
            throw new BusinessException("缺少登入憑證", HttpStatus.UNAUTHORIZED);
        }
        String productId = request.getProductId().trim();
        if (productRepository.findIncludingDeletedById(productId) != null) {
            throw new BusinessException("商品編號已存在", HttpStatus.CONFLICT);
        }

        Product product = new Product();
        product.setProductId(productId);
        product.setProductName(escapeHtml(request.getProductName().trim()));
        product.setPrice(request.getPrice());
        product.setQuantity(request.getQuantity());
        product.setCreatorId(creatorId);
        // 稽核紀錄與商品寫入同一交易：兩者一起提交或一起 rollback。
        transactions.executeWithoutResult(status -> {
            productRepository.addProduct(product);
            auditLogService.record(creatorId, null, AuditAction.PRODUCT_CREATE, productId, null, snapshot(product));
        });

        // 讓 AI 客服立即查得到新商品，不必等下次應用程式啟動才重新索引。
        embeddingIndexService.indexProduct(productId);
    }

    public boolean isOwnedBy(String productId, String creatorId) {
        return creatorId != null && productRepository.isOwnedBy(productId, creatorId);
    }

    public List<Product> getAvailableProducts() {
        return productRepository.getAvailableProducts();
    }

    public List<Product> getOwnedProducts(String creatorId) {
        requirePrincipal(creatorId);
        return productRepository.findByCreator(creatorId);
    }

    public ProductPageResponse searchOwnedProducts(String creatorId, String keyword, String status, int page, int size) {
        requirePrincipal(creatorId);
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException("分頁參數不合法", HttpStatus.BAD_REQUEST);
        }
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new BusinessException("分頁參數不合法", HttpStatus.BAD_REQUEST);
        }
        String normalizedStatus = status == null ? "all" : status.toLowerCase();
        if (!List.of("all", "active", "deleted").contains(normalizedStatus)) {
            throw new BusinessException("商品狀態篩選不合法", HttpStatus.BAD_REQUEST);
        }
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        return new ProductPageResponse(
                productRepository.searchByCreator(creatorId, normalizedKeyword, normalizedStatus, size, page * size),
                productRepository.countByCreator(creatorId, normalizedKeyword, normalizedStatus), page, size);
    }

    public Product getOwnedProduct(String productId, String creatorId) {
        return requireOwnedActive(productId, creatorId);
    }

    public void updateOwnedProduct(String productId, UpdateProductRequest request, String creatorId) {
        requireOwnedActive(productId, creatorId);
        transactions.executeWithoutResult(status -> {
            // 先鎖列再讀 before，稽核記錄的「更新前」才不會被並發更新蓋掉。
            Map<String, Object> before = snapshot(productRepository.lockIncludingDeletedById(productId));
            if (productRepository.updateOwnedProduct(productId, creatorId,
                    escapeHtml(request.getProductName().trim()), request.getPrice()) != 1) {
                throw new BusinessException("商品狀態已變更，請重新整理", HttpStatus.CONFLICT);
            }
            auditLogService.record(creatorId, null, AuditAction.PRODUCT_UPDATE, productId, before,
                    snapshot(productRepository.findIncludingDeletedById(productId)));
        });
        embeddingIndexService.indexProduct(productId);
    }

    public void deleteOwnedProduct(String productId, String creatorId) {
        requireOwnedActive(productId, creatorId);
        transactions.executeWithoutResult(status -> {
            Map<String, Object> before = snapshot(productRepository.lockIncludingDeletedById(productId));
            if (productRepository.softDeleteOwnedProduct(productId, creatorId) != 1) {
                throw new BusinessException("商品狀態已變更，請重新整理", HttpStatus.CONFLICT);
            }
            auditLogService.record(creatorId, null, AuditAction.PRODUCT_DELETE, productId, before,
                    snapshot(productRepository.findIncludingDeletedById(productId)));
        });
        embeddingIndexService.removeProduct(productId);
    }

    public void restockOwnedProduct(String productId, int amount, String creatorId) {
        requireValidRestockAmount(amount);
        requireOwnedActive(productId, creatorId);
        transactions.executeWithoutResult(status -> {
            Map<String, Object> before = snapshot(productRepository.lockIncludingDeletedById(productId));
            if (productRepository.restockOwnedProduct(productId, creatorId, amount) != 1) {
                throw new BusinessException("商品狀態已變更，請重新整理", HttpStatus.CONFLICT);
            }
            Map<String, Object> after = snapshot(productRepository.findIncludingDeletedById(productId));
            after.put("restockAmount", amount);
            auditLogService.record(creatorId, null, AuditAction.PRODUCT_RESTOCK, productId, before, after);
        });
        embeddingIndexService.indexProduct(productId);
    }

    public void bulkManageOwnedProducts(BulkProductRequest request, String creatorId) {
        requirePrincipal(creatorId);
        if (request.getAction() == null) throw new BusinessException("缺少批量操作", HttpStatus.BAD_REQUEST);
        List<String> ids = request.getProductIds() == null ? List.of() : request.getProductIds().stream()
                .filter(id -> id != null && !id.isBlank()).map(String::trim)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new), List::copyOf));
        if (ids.isEmpty() || ids.size() > 100 || ids.size() != request.getProductIds().size()) {
            throw new BusinessException("商品清單包含空白、重複或超過 100 筆", HttpStatus.BAD_REQUEST);
        }
        boolean delete = request.getAction() == BulkProductRequest.Action.DELETE;
        if (!delete) {
            if (request.getAmount() == null) {
                throw new BusinessException("批量補貨量必須大於零", HttpStatus.BAD_REQUEST);
            }
            requireValidRestockAmount(request.getAmount());
        }
        // 交易只包住「檢查擁有權 + 條件式更新」，任何一筆不符即整批 rollback（全有或全無）。
        transactions.executeWithoutResult(status -> {
            if (productRepository.countActiveOwned(ids, creatorId) != ids.size()) {
                throw new BusinessException("商品不存在或無權管理", HttpStatus.FORBIDDEN);
            }
            int changed = delete
                    ? productRepository.bulkSoftDeleteOwned(ids, creatorId)
                    : productRepository.bulkRestockOwned(ids, creatorId, request.getAmount());
            if (changed != ids.size()) throw new BusinessException("商品狀態已變更，請重新整理", HttpStatus.CONFLICT);
            // 批量操作逐商品各記一筆：稽核要能以「單一商品」追溯。UPDATE 已鎖住這些列，之後讀到的即為精確的
            // after，before 可由 after 與本次操作反推（刪除 → 未刪除；補貨 → 數量減去補貨量）。
            Member.Role actorRole = auditLogService.roleOf(creatorId);
            for (Product after : productRepository.findIncludingDeletedByIds(ids)) {
                Map<String, Object> afterState = snapshot(after);
                Map<String, Object> beforeState = new LinkedHashMap<>(afterState);
                if (delete) {
                    beforeState.put("deleted", false);
                } else {
                    beforeState.put("quantity", after.getQuantity() - request.getAmount());
                    afterState.put("restockAmount", request.getAmount());
                }
                auditLogService.record(creatorId, actorRole,
                        delete ? AuditAction.PRODUCT_DELETE : AuditAction.PRODUCT_RESTOCK,
                        after.getProductId(), beforeState, afterState);
            }
        });
        // 向量索引（可能呼叫 LLM 並寫入資料庫）在交易提交之後才更新：rollback 時索引不會與資料不一致，
        // 也不會在持有商品列鎖的交易內等待外部服務。
        ids.forEach(delete ? embeddingIndexService::removeProduct : embeddingIndexService::indexProduct);
    }

    @Transactional
    public List<String> uploadOwnedProductImages(String productId, MultipartFile[] files, String creatorId) {
        requireOwnedActive(productId, creatorId);
        int incoming = files == null ? 0 : files.length;
        if (productRepository.countProductImages(productId) + incoming > MAX_IMAGES_PER_PRODUCT) {
            throw new BusinessException("每個商品最多 " + MAX_IMAGES_PER_PRODUCT + " 張圖片", HttpStatus.BAD_REQUEST);
        }
        List<ProductImageStorageService.StoredImage> stored = imageStorageService.store(files);
        try {
            stored.forEach(image -> productRepository.addProductImage(productId, image.url()));
            List<String> urls = stored.stream().map(ProductImageStorageService.StoredImage::url).toList();
            auditLogService.record(creatorId, null, AuditAction.PRODUCT_IMAGE_UPLOAD, productId, null,
                    Map.of("addedImageUrls", urls));
            return urls;
        } catch (RuntimeException ex) {
            stored.forEach(imageStorageService::deleteQuietly);
            throw ex;
        }
    }

    /** 稽核用商品快照：只放白名單業務欄位。 */
    private static Map<String, Object> snapshot(Product product) {
        Map<String, Object> state = new LinkedHashMap<>();
        if (product == null) return state;
        state.put("productName", product.getProductName());
        state.put("price", product.getPrice());
        state.put("quantity", product.getQuantity());
        state.put("creatorId", product.getCreatorId());
        state.put("deleted", product.getDeletedAt() != null);
        return state;
    }

    private static void requireValidRestockAmount(int amount) {
        if (amount <= 0 || amount > MAX_RESTOCK_AMOUNT) {
            throw new BusinessException("補貨量必須介於 1 到 " + MAX_RESTOCK_AMOUNT, HttpStatus.BAD_REQUEST);
        }
    }

    private Product requireOwnedActive(String productId, String creatorId) {
        requirePrincipal(creatorId);
        Product product = productRepository.findIncludingDeletedById(productId);
        if (product == null || product.getDeletedAt() != null) {
            throw new BusinessException("商品不存在", HttpStatus.NOT_FOUND);
        }
        if (!creatorId.equals(product.getCreatorId())) {
            throw new BusinessException("無權管理此商品", HttpStatus.FORBIDDEN);
        }
        return product;
    }

    private static void requirePrincipal(String creatorId) {
        if (creatorId == null || creatorId.isBlank()) {
            throw new BusinessException("缺少登入憑證", HttpStatus.UNAUTHORIZED);
        }
    }

    private String escapeHtml(String input) {
        return input.replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
