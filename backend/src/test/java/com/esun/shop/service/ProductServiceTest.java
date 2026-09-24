package com.esun.shop.service;

import com.esun.shop.dto.BulkProductRequest;
import com.esun.shop.dto.CreateProductRequest;
import com.esun.shop.dto.ProductPageResponse;
import com.esun.shop.dto.UpdateProductRequest;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.llm.EmbeddingIndexService;
import com.esun.shop.model.Product;
import com.esun.shop.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProductService}. ProductRepository and EmbeddingIndexService are mocked so
 * these exercise only the service's own logic (duplicate-id check, HTML escaping, re-index trigger),
 * not the stored procedures behind the repository or the real embedding/LLM call.
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private EmbeddingIndexService embeddingIndexService;
    @Mock
    private ProductImageStorageService imageStorageService;
    @Mock
    private AuditLogService auditLogService;

    private ProductService productService;

    @BeforeEach
    void setUp() {
        productService = new ProductService(productRepository, embeddingIndexService, imageStorageService,
                auditLogService);
    }

    @Test
    void createProduct_newProductId_savesTrimmedAndEscapedProduct() {
        CreateProductRequest request = new CreateProductRequest();
        request.setProductId("P100");
        request.setProductName("  <script>alert('x')</script>  ");
        request.setPrice(new BigDecimal("199.00"));
        request.setQuantity(10);

        when(productRepository.findIncludingDeletedById("P100")).thenReturn(null);

        productService.createProduct(request);

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).addProduct(captor.capture());
        Product saved = captor.getValue();

        assertThat(saved.getProductId()).isEqualTo("P100");
        assertThat(saved.getProductName()).isEqualTo("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;");
        assertThat(saved.getPrice()).isEqualByComparingTo("199.00");
        assertThat(saved.getQuantity()).isEqualTo(10);
        assertThat(saved.getCreatorId()).isEqualTo("legacy");

        verify(embeddingIndexService).indexProduct("P100");
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

        assertThatThrownBy(() -> productService.createProduct(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(productRepository, never()).addProduct(any());
        verify(embeddingIndexService, never()).indexProduct(anyString());
    }

    @Test
    void createProduct_productIdWithWhitespace_trimsBeforeLookupAndSave() {
        CreateProductRequest request = new CreateProductRequest();
        request.setProductId("  P200  ");
        request.setProductName("  new product  ");
        request.setPrice(BigDecimal.ONE);
        request.setQuantity(3);

        when(productRepository.findIncludingDeletedById("P200")).thenReturn(null);

        productService.createProduct(request);

        verify(productRepository).findIncludingDeletedById("P200");
        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).addProduct(captor.capture());
        assertThat(captor.getValue().getProductId()).isEqualTo("P200");
        assertThat(captor.getValue().getProductName()).isEqualTo("new product");
        verify(embeddingIndexService).indexProduct("P200");
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
    void managementOperationsEnforceOwnershipAndUseAtomicRestock() {
        Product product = owned("P001", "owner@example.com");
        when(productRepository.findIncludingDeletedById("P001")).thenReturn(product);
        UpdateProductRequest update = new UpdateProductRequest();
        update.setProductName("<b>new</b>");
        update.setPrice(BigDecimal.TEN);

        assertThatThrownBy(() -> productService.updateOwnedProduct("P001", update, "other@example.com"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        when(productRepository.restockOwnedProduct("P001", "owner@example.com", 3)).thenReturn(1);
        productService.restockOwnedProduct("P001", 3, "owner@example.com");
        verify(productRepository).restockOwnedProduct("P001", "owner@example.com", 3);
        verify(embeddingIndexService).indexProduct("P001");
    }

    @Test
    void ownerListingAndUpdateDelegateWithValidatedPrincipal() {
        Product product = owned("P001", "owner@example.com");
        when(productRepository.findByCreator("owner@example.com")).thenReturn(List.of(product));
        when(productRepository.findIncludingDeletedById("P001")).thenReturn(product);
        assertThat(productService.getOwnedProducts("owner@example.com")).containsExactly(product);
        assertThat(productService.getOwnedProduct("P001", "owner@example.com")).isSameAs(product);

        UpdateProductRequest update = new UpdateProductRequest();
        update.setProductName("<b>new</b>");
        update.setPrice(BigDecimal.TEN);
        when(productRepository.updateOwnedProduct("P001", "owner@example.com", "&lt;b&gt;new&lt;/b&gt;", BigDecimal.TEN))
                .thenReturn(1);

        productService.updateOwnedProduct("P001", update, "owner@example.com");

        verify(embeddingIndexService).indexProduct("P001");
        assertThatThrownBy(() -> productService.getOwnedProducts(" "))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void conditionalUpdateAndRestockConflictsReturn409() {
        Product product = owned("P001", "owner@example.com");
        when(productRepository.findIncludingDeletedById("P001")).thenReturn(product);
        UpdateProductRequest update = new UpdateProductRequest();
        update.setProductName("new");
        update.setPrice(BigDecimal.TEN);

        when(productRepository.updateOwnedProduct("P001", "owner@example.com", "new", BigDecimal.TEN)).thenReturn(0);
        assertThatThrownBy(() -> productService.updateOwnedProduct("P001", update, "owner@example.com"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        when(productRepository.restockOwnedProduct("P001", "owner@example.com", 2)).thenReturn(0);
        assertThatThrownBy(() -> productService.restockOwnedProduct("P001", 2, "owner@example.com"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void deletedProductCannotBeManagedAndFailedConditionalWriteReturns409() {
        Product deleted = owned("P001", "owner@example.com");
        deleted.setDeletedAt(java.time.LocalDateTime.now());
        when(productRepository.findIncludingDeletedById("P001")).thenReturn(deleted);
        assertThatThrownBy(() -> productService.deleteOwnedProduct("P001", "owner@example.com"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        deleted.setDeletedAt(null);
        when(productRepository.softDeleteOwnedProduct("P001", "owner@example.com")).thenReturn(0);
        assertThatThrownBy(() -> productService.deleteOwnedProduct("P001", "owner@example.com"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void successfulSoftDeleteRemovesSearchIndex() {
        Product product = owned("P001", "owner@example.com");
        when(productRepository.findIncludingDeletedById("P001")).thenReturn(product);
        when(productRepository.softDeleteOwnedProduct("P001", "owner@example.com")).thenReturn(1);

        productService.deleteOwnedProduct("P001", "owner@example.com");

        verify(embeddingIndexService).removeProduct("P001");
    }

    @Test
    void searchNormalizesFiltersAndDelegatesPagingWithOwnerScope() {
        Product product = owned("P001", "owner@example.com");
        when(productRepository.searchByCreator("owner@example.com", "tea", "active", 10, 20)).thenReturn(List.of(product));
        when(productRepository.countByCreator("owner@example.com", "tea", "active")).thenReturn(21L);

        ProductPageResponse result = productService.searchOwnedProducts("owner@example.com", "  tea ", "ACTIVE", 2, 10);

        assertThat(result.products()).containsExactly(product);
        assertThat(result.total()).isEqualTo(21);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(10);

        when(productRepository.searchByCreator("owner@example.com", "", "all", 20, 0)).thenReturn(List.of());
        when(productRepository.countByCreator("owner@example.com", "", "all")).thenReturn(0L);
        assertThat(productService.searchOwnedProducts("owner@example.com", null, null, 0, 20).products()).isEmpty();
    }

    @Test
    void searchRejectsInvalidPagingStatusAndMissingPrincipal() {
        assertStatus(() -> productService.searchOwnedProducts("owner@example.com", "", "all", -1, 20), HttpStatus.BAD_REQUEST);
        assertStatus(() -> productService.searchOwnedProducts("owner@example.com", "", "all", 0, 0), HttpStatus.BAD_REQUEST);
        assertStatus(() -> productService.searchOwnedProducts("owner@example.com", "", "all", 0, 101), HttpStatus.BAD_REQUEST);
        assertStatus(() -> productService.searchOwnedProducts("owner@example.com", "", "bogus", 0, 20), HttpStatus.BAD_REQUEST);
        assertStatus(() -> productService.searchOwnedProducts(null, "", "all", 0, 20), HttpStatus.UNAUTHORIZED);
        verify(productRepository, never()).searchByCreator(anyString(), anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void bulkDeleteAndRestockRequireEveryIdToBeOwnedAndActive() {
        List<String> ids = List.of("P001", "P002");
        when(productRepository.countActiveOwned(ids, "owner@example.com")).thenReturn(2);
        when(productRepository.bulkSoftDeleteOwned(ids, "owner@example.com")).thenReturn(2);
        when(productRepository.bulkRestockOwned(ids, "owner@example.com", 5)).thenReturn(2);

        productService.bulkManageOwnedProducts(bulk(BulkProductRequest.Action.DELETE, null, "P001", " P002 "), "owner@example.com");
        verify(embeddingIndexService).removeProduct("P001");
        verify(embeddingIndexService).removeProduct("P002");

        productService.bulkManageOwnedProducts(bulk(BulkProductRequest.Action.RESTOCK, 5, "P001", "P002"), "owner@example.com");
        verify(embeddingIndexService).indexProduct("P001");
        verify(embeddingIndexService).indexProduct("P002");
    }

    @Test
    void bulkWithForeignOrMissingProductIsForbiddenAndWritesNothing() {
        List<String> ids = List.of("MINE", "THEIRS");
        when(productRepository.countActiveOwned(ids, "owner@example.com")).thenReturn(1);

        assertStatus(() -> productService.bulkManageOwnedProducts(
                bulk(BulkProductRequest.Action.DELETE, null, "MINE", "THEIRS"), "owner@example.com"), HttpStatus.FORBIDDEN);
        assertStatus(() -> productService.bulkManageOwnedProducts(
                bulk(BulkProductRequest.Action.RESTOCK, 2, "MINE", "THEIRS"), "owner@example.com"), HttpStatus.FORBIDDEN);

        verify(productRepository, never()).bulkSoftDeleteOwned(any(), anyString());
        verify(productRepository, never()).bulkRestockOwned(any(), anyString(), anyInt());
        verify(embeddingIndexService, never()).removeProduct(anyString());
    }

    @Test
    void bulkRejectsMalformedRequestsBeforeTouchingRepository() {
        assertStatus(() -> productService.bulkManageOwnedProducts(
                bulk(null, null, "P001"), "owner@example.com"), HttpStatus.BAD_REQUEST);
        assertStatus(() -> productService.bulkManageOwnedProducts(
                bulk(BulkProductRequest.Action.DELETE, null), "owner@example.com"), HttpStatus.BAD_REQUEST);
        BulkProductRequest nullIds = bulk(BulkProductRequest.Action.DELETE, null);
        nullIds.setProductIds(null);
        assertStatus(() -> productService.bulkManageOwnedProducts(nullIds, "owner@example.com"), HttpStatus.BAD_REQUEST);
        assertStatus(() -> productService.bulkManageOwnedProducts(
                bulk(BulkProductRequest.Action.DELETE, null, "P001", "P001"), "owner@example.com"), HttpStatus.BAD_REQUEST);
        assertStatus(() -> productService.bulkManageOwnedProducts(
                bulk(BulkProductRequest.Action.DELETE, null, "P001", " "), "owner@example.com"), HttpStatus.BAD_REQUEST);
        String[] tooMany = java.util.stream.IntStream.range(0, 101).mapToObj(i -> "P" + i).toArray(String[]::new);
        assertStatus(() -> productService.bulkManageOwnedProducts(
                bulk(BulkProductRequest.Action.DELETE, null, tooMany), "owner@example.com"), HttpStatus.BAD_REQUEST);
        assertStatus(() -> productService.bulkManageOwnedProducts(
                bulk(BulkProductRequest.Action.DELETE, null, "P001"), " "), HttpStatus.UNAUTHORIZED);
        verify(productRepository, never()).countActiveOwned(any(), anyString());
    }

    @Test
    void bulkRestockRequiresPositiveAmountAndConcurrentChangeReturns409() {
        List<String> ids = List.of("P001");
        when(productRepository.countActiveOwned(ids, "owner@example.com")).thenReturn(1);
        assertStatus(() -> productService.bulkManageOwnedProducts(
                bulk(BulkProductRequest.Action.RESTOCK, null, "P001"), "owner@example.com"), HttpStatus.BAD_REQUEST);
        assertStatus(() -> productService.bulkManageOwnedProducts(
                bulk(BulkProductRequest.Action.RESTOCK, 0, "P001"), "owner@example.com"), HttpStatus.BAD_REQUEST);
        verify(productRepository, never()).bulkRestockOwned(any(), anyString(), anyInt());

        when(productRepository.bulkSoftDeleteOwned(ids, "owner@example.com")).thenReturn(0);
        assertStatus(() -> productService.bulkManageOwnedProducts(
                bulk(BulkProductRequest.Action.DELETE, null, "P001"), "owner@example.com"), HttpStatus.CONFLICT);
        // 衝突（整批 rollback）時不得動到向量索引。
        verify(embeddingIndexService, never()).removeProduct(anyString());
        verify(embeddingIndexService, never()).indexProduct(anyString());
    }

    @Test
    void restockAmountIsCappedForSingleAndBulkRequests() {
        assertStatus(() -> productService.restockOwnedProduct("P001", ProductService.MAX_RESTOCK_AMOUNT + 1, "owner@example.com"),
                HttpStatus.BAD_REQUEST);
        assertStatus(() -> productService.bulkManageOwnedProducts(
                bulk(BulkProductRequest.Action.RESTOCK, ProductService.MAX_RESTOCK_AMOUNT + 1, "P001"), "owner@example.com"),
                HttpStatus.BAD_REQUEST);
        verify(productRepository, never()).restockOwnedProduct(anyString(), anyString(), anyInt());
        verify(productRepository, never()).bulkRestockOwned(any(), anyString(), anyInt());
    }

    @Test
    void searchRejectsOffsetsThatOverflowInt() {
        assertStatus(() -> productService.searchOwnedProducts("owner@example.com", "", "all", Integer.MAX_VALUE, 100),
                HttpStatus.BAD_REQUEST);
    }

    @Test
    void imageUploadEnforcesPerProductImageCapBeforeStoringFiles() {
        Product product = owned("P001", "owner@example.com");
        when(productRepository.findIncludingDeletedById("P001")).thenReturn(product);
        when(productRepository.countProductImages("P001")).thenReturn(ProductService.MAX_IMAGES_PER_PRODUCT - 1);
        MultipartFile[] files = {new MockMultipartFile("images", "a.png", "image/png", new byte[]{1}),
                new MockMultipartFile("images", "b.png", "image/png", new byte[]{1})};

        assertStatus(() -> productService.uploadOwnedProductImages("P001", files, "owner@example.com"), HttpStatus.BAD_REQUEST);
        verify(imageStorageService, never()).store(any());
    }

    @Test
    void imageUploadRequiresOwnershipAndPersistsMetadataInOrder() {
        Product product = owned("P001", "owner@example.com");
        when(productRepository.findIncludingDeletedById("P001")).thenReturn(product);
        MultipartFile[] files = {new MockMultipartFile("images", "a.png", "image/png", new byte[]{1})};
        ProductImageStorageService.StoredImage first = new ProductImageStorageService.StoredImage("/uploads/products/1.png", Path.of("1.png"));
        ProductImageStorageService.StoredImage second = new ProductImageStorageService.StoredImage("/uploads/products/2.png", Path.of("2.png"));
        when(imageStorageService.store(files)).thenReturn(List.of(first, second));

        List<String> urls = productService.uploadOwnedProductImages("P001", files, "owner@example.com");

        assertThat(urls).containsExactly("/uploads/products/1.png", "/uploads/products/2.png");
        verify(productRepository).addProductImage("P001", "/uploads/products/1.png");
        verify(productRepository).addProductImage("P001", "/uploads/products/2.png");
    }

    @Test
    void imageUploadByNonOwnerOrOnDeletedProductNeverStoresFiles() {
        Product product = owned("P001", "owner@example.com");
        when(productRepository.findIncludingDeletedById("P001")).thenReturn(product);
        MultipartFile[] files = {new MockMultipartFile("images", "a.png", "image/png", new byte[]{1})};

        assertStatus(() -> productService.uploadOwnedProductImages("P001", files, "other@example.com"), HttpStatus.FORBIDDEN);
        assertStatus(() -> productService.uploadOwnedProductImages("MISSING", files, "owner@example.com"), HttpStatus.NOT_FOUND);
        verify(imageStorageService, never()).store(any());
    }

    @Test
    void imageMetadataFailureRemovesAlreadyStoredFiles() {
        Product product = owned("P001", "owner@example.com");
        when(productRepository.findIncludingDeletedById("P001")).thenReturn(product);
        MultipartFile[] files = {new MockMultipartFile("images", "a.png", "image/png", new byte[]{1})};
        ProductImageStorageService.StoredImage stored = new ProductImageStorageService.StoredImage("/uploads/products/1.png", Path.of("1.png"));
        when(imageStorageService.store(files)).thenReturn(List.of(stored));
        doThrow(new IllegalStateException("db down")).when(productRepository).addProductImage("P001", "/uploads/products/1.png");

        assertThatThrownBy(() -> productService.uploadOwnedProductImages("P001", files, "owner@example.com"))
                .isInstanceOf(IllegalStateException.class);

        verify(imageStorageService).deleteQuietly(stored);
    }

    private static void assertStatus(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getStatus()).isEqualTo(expected));
    }

    private static BulkProductRequest bulk(BulkProductRequest.Action action, Integer amount, String... ids) {
        BulkProductRequest request = new BulkProductRequest();
        request.setAction(action);
        request.setAmount(amount);
        request.setProductIds(new java.util.ArrayList<>(List.of(ids)));
        return request;
    }

    private static Product owned(String productId, String owner) {
        Product product = new Product();
        product.setProductId(productId);
        product.setCreatorId(owner);
        product.setQuantity(1);
        product.setPrice(BigDecimal.ONE);
        return product;
    }
}
