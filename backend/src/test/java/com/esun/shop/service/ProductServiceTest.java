package com.esun.shop.service;

import com.esun.shop.dto.CreateProductRequest;
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

        when(productRepository.findById("P100")).thenReturn(null);

        productService.createProduct(request);

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).addProduct(captor.capture());
        Product saved = captor.getValue();

        assertThat(saved.getProductId()).isEqualTo("P100");
        assertThat(saved.getProductName()).isEqualTo("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;");
        assertThat(saved.getPrice()).isEqualByComparingTo("199.00");
        assertThat(saved.getQuantity()).isEqualTo(10);
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
        when(productRepository.findById("P001")).thenReturn(existing);

        assertThatThrownBy(() -> productService.createProduct(request))
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

        when(productRepository.findById("P200")).thenReturn(null);

        productService.createProduct(request);

        verify(productRepository).findById("P200");
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
}
