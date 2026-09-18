package com.esun.shop.service;

import com.esun.shop.dto.CreateProductRequest;
import com.esun.shop.dto.UpdateProductRequest;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.Product;
import com.esun.shop.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProductService}. ProductRepository is mocked so these
 * exercise only the service's own logic (duplicate-id check, HTML escaping),
 * not the stored procedures behind the repository.
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    private ProductService productService;

    @BeforeEach
    void setUp() {
        productService = new ProductService(productRepository);
    }

    @Test
    void createProduct_newProductId_savesTrimmedAndEscapedProduct() {
        CreateProductRequest request = new CreateProductRequest();
        request.setProductId("P100");
        request.setProductName("  <script>alert('x')</script>  ");
        request.setPrice(new BigDecimal("199.00"));
        request.setQuantity(10);

        when(productRepository.findIncludingDeletedById("P100")).thenReturn(null);

        productService.createProduct(request, "owner@example.com");

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).addProduct(captor.capture());
        Product saved = captor.getValue();

        assertThat(saved.getProductId()).isEqualTo("P100");
        assertThat(saved.getProductName()).isEqualTo("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;");
        assertThat(saved.getPrice()).isEqualByComparingTo("199.00");
        assertThat(saved.getQuantity()).isEqualTo(10);
        assertThat(saved.getCreatorId()).isEqualTo("owner@example.com");
    }

    @Test
    void createProduct_duplicateProductId_returns409AndDoesNotSave() {
        CreateProductRequest request = new CreateProductRequest();
        request.setProductId("P001");
        request.setProductName("existing product");
        request.setPrice(BigDecimal.TEN);
        request.setQuantity(1);

        Product existing = new Product();
        existing.setProductId("P001");
        when(productRepository.findIncludingDeletedById("P001")).thenReturn(existing);

        assertThatThrownBy(() -> productService.createProduct(request, "owner@example.com"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(productRepository, never()).addProduct(any());
    }

    @Test
    void createProduct_productIdWithWhitespace_trimsBeforeLookupAndSave() {
        CreateProductRequest request = new CreateProductRequest();
        request.setProductId("  P200  ");
        request.setProductName("  new product  ");
        request.setPrice(BigDecimal.ONE);
        request.setQuantity(3);

        when(productRepository.findIncludingDeletedById("P200")).thenReturn(null);

        productService.createProduct(request, "owner@example.com");

        verify(productRepository).findIncludingDeletedById("P200");
        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).addProduct(captor.capture());
        assertThat(captor.getValue().getProductId()).isEqualTo("P200");
        assertThat(captor.getValue().getProductName()).isEqualTo("new product");
    }

    @Test
    void getAvailableProducts_delegatesToRepository() {
        Product p1 = new Product();
        p1.setProductId("P001");
        when(productRepository.getAvailableProducts()).thenReturn(List.of(p1));

        List<Product> result = productService.getAvailableProducts();

        assertThat(result).containsExactly(p1);
        verify(productRepository, never()).findById(anyString());
    }

    @Test
    void nonOwnerCannotUpdateDeleteOrRestock() {
        Product product = owned("P001", "owner@example.com");
        when(productRepository.findIncludingDeletedById("P001")).thenReturn(product);
        UpdateProductRequest update = new UpdateProductRequest();
        update.setProductName("changed"); update.setPrice(BigDecimal.TEN);

        assertThatThrownBy(() -> productService.updateOwnedProduct("P001", update, "other@example.com"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> productService.deleteOwnedProduct("P001", "other@example.com"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> productService.restockOwnedProduct("P001", 5, "other@example.com"))
                .isInstanceOf(BusinessException.class);
        verify(productRepository, never()).updateOwnedProduct(anyString(), anyString(), anyString(), any());
        verify(productRepository, never()).softDeleteOwnedProduct(anyString(), anyString());
        verify(productRepository, never()).restockOwnedProduct(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void ownerRestockUsesAtomicRepositoryIncrementAndRejectsNonPositiveAmount() {
        when(productRepository.findIncludingDeletedById("P001")).thenReturn(owned("P001", "owner@example.com"));
        when(productRepository.restockOwnedProduct("P001", "owner@example.com", 3)).thenReturn(1);
        productService.restockOwnedProduct("P001", 3, "owner@example.com");
        verify(productRepository).restockOwnedProduct("P001", "owner@example.com", 3);
        assertThatThrownBy(() -> productService.restockOwnedProduct("P001", 0, "owner@example.com"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void legacyCreateProduct_delegatesWithLegacyCreator() {
        CreateProductRequest request = new CreateProductRequest();
        request.setProductId("P300");
        request.setProductName("legacy product");
        request.setPrice(BigDecimal.ONE);
        request.setQuantity(1);
        when(productRepository.findIncludingDeletedById("P300")).thenReturn(null);

        productService.createProduct(request);

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).addProduct(captor.capture());
        assertThat(captor.getValue().getCreatorId()).isEqualTo("legacy");
    }

    @Test
    void getOwnedProducts_delegatesToRepositoryAndRejectsMissingPrincipal() {
        Product p1 = owned("P001", "owner@example.com");
        when(productRepository.findByCreator("owner@example.com")).thenReturn(List.of(p1));

        assertThat(productService.getOwnedProducts("owner@example.com")).containsExactly(p1);

        assertThatThrownBy(() -> productService.getOwnedProducts(" "))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void getOwnedProduct_returnsProduct_whenOwnedAndActive() {
        Product product = owned("P001", "owner@example.com");
        when(productRepository.findIncludingDeletedById("P001")).thenReturn(product);

        assertThat(productService.getOwnedProduct("P001", "owner@example.com")).isSameAs(product);
    }

    @Test
    void requireOwnedActive_missingOrSoftDeletedProduct_returns404() {
        when(productRepository.findIncludingDeletedById("MISSING")).thenReturn(null);
        assertThatThrownBy(() -> productService.getOwnedProduct("MISSING", "owner@example.com"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        Product deleted = owned("P001", "owner@example.com");
        deleted.setDeletedAt(java.time.LocalDateTime.now());
        when(productRepository.findIncludingDeletedById("P001")).thenReturn(deleted);
        assertThatThrownBy(() -> productService.getOwnedProduct("P001", "owner@example.com"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void updateOwnedProduct_ownerSuccess_andConcurrentChangeReturns409() {
        Product product = owned("P001", "owner@example.com");
        when(productRepository.findIncludingDeletedById("P001")).thenReturn(product);
        UpdateProductRequest update = new UpdateProductRequest();
        update.setProductName("<b>new</b>"); update.setPrice(BigDecimal.TEN);
        when(productRepository.updateOwnedProduct("P001", "owner@example.com", "&lt;b&gt;new&lt;/b&gt;", BigDecimal.TEN)).thenReturn(1);

        productService.updateOwnedProduct("P001", update, "owner@example.com");
        verify(productRepository).updateOwnedProduct("P001", "owner@example.com", "&lt;b&gt;new&lt;/b&gt;", BigDecimal.TEN);

        when(productRepository.updateOwnedProduct(anyString(), anyString(), anyString(), any())).thenReturn(0);
        assertThatThrownBy(() -> productService.updateOwnedProduct("P001", update, "owner@example.com"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void deleteOwnedProduct_ownerSuccess_andConcurrentChangeReturns409() {
        Product product = owned("P001", "owner@example.com");
        when(productRepository.findIncludingDeletedById("P001")).thenReturn(product);
        when(productRepository.softDeleteOwnedProduct("P001", "owner@example.com")).thenReturn(1);

        productService.deleteOwnedProduct("P001", "owner@example.com");
        verify(productRepository).softDeleteOwnedProduct("P001", "owner@example.com");

        when(productRepository.softDeleteOwnedProduct(anyString(), anyString())).thenReturn(0);
        assertThatThrownBy(() -> productService.deleteOwnedProduct("P001", "owner@example.com"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    private static Product owned(String productId, String owner) {
        Product product = new Product();
        product.setProductId(productId); product.setCreatorId(owner); product.setQuantity(1); product.setPrice(BigDecimal.ONE);
        return product;
    }
}
