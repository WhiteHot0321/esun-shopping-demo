package com.esun.shop.service;

import com.esun.shop.dto.CreateProductRequest;
import com.esun.shop.dto.UpdateProductRequest;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.Product;
import com.esun.shop.repository.ProductRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProductService {
    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /** Legacy bootstrap/testing entry point; HTTP creation always supplies a verified JWT subject. */
    @Deprecated(forRemoval = true)
    public void createProduct(CreateProductRequest request) {
        createProduct(request, "legacy");
    }

    public void createProduct(CreateProductRequest request, String creatorId) {
        requirePrincipal(creatorId);
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
        productRepository.addProduct(product);
    }

    public List<Product> getAvailableProducts() {
        return productRepository.getAvailableProducts();
    }

    public List<Product> getOwnedProducts(String creatorId) {
        requirePrincipal(creatorId);
        return productRepository.findByCreator(creatorId);
    }

    public Product getOwnedProduct(String productId, String creatorId) {
        return requireOwnedActive(productId, creatorId);
    }

    public void updateOwnedProduct(String productId, UpdateProductRequest request, String creatorId) {
        requireOwnedActive(productId, creatorId);
        if (productRepository.updateOwnedProduct(productId, creatorId, escapeHtml(request.getProductName().trim()), request.getPrice()) != 1) {
            throw new BusinessException("商品狀態已變更，請重新整理", HttpStatus.CONFLICT);
        }
    }

    public void deleteOwnedProduct(String productId, String creatorId) {
        requireOwnedActive(productId, creatorId);
        if (productRepository.softDeleteOwnedProduct(productId, creatorId) != 1) {
            throw new BusinessException("商品狀態已變更，請重新整理", HttpStatus.CONFLICT);
        }
    }

    public void restockOwnedProduct(String productId, int amount, String creatorId) {
        if (amount <= 0) throw new BusinessException("補貨量必須大於零", HttpStatus.BAD_REQUEST);
        requireOwnedActive(productId, creatorId);
        if (productRepository.restockOwnedProduct(productId, creatorId, amount) != 1) {
            throw new BusinessException("商品狀態已變更，請重新整理", HttpStatus.CONFLICT);
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
