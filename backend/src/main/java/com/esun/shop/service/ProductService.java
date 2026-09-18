package com.esun.shop.service;

import com.esun.shop.dto.CreateProductRequest;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.llm.EmbeddingIndexService;
import com.esun.shop.model.Product;
import com.esun.shop.repository.ProductRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProductService {
    private final ProductRepository productRepository;
    private final EmbeddingIndexService embeddingIndexService;

    public ProductService(ProductRepository productRepository, EmbeddingIndexService embeddingIndexService) {
        this.productRepository = productRepository;
        this.embeddingIndexService = embeddingIndexService;
    }

    public void createProduct(CreateProductRequest request) {
        String productId = request.getProductId().trim();
        if (productRepository.findById(productId) != null) {
            throw new BusinessException("商品編號已存在", HttpStatus.CONFLICT);
        }

        Product product = new Product();
        product.setProductId(productId);
        product.setProductName(escapeHtml(request.getProductName().trim()));
        product.setPrice(request.getPrice());
        product.setQuantity(request.getQuantity());
        productRepository.addProduct(product);

        // 讓 AI 客服立即查得到新商品，不必等下次應用程式啟動才重新索引。
        embeddingIndexService.indexProduct(productId);
    }

    public List<Product> getAvailableProducts() {
        return productRepository.getAvailableProducts();
    }

    private String escapeHtml(String input) {
        return input.replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
