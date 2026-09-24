package com.esun.shop.service;

import com.esun.shop.dto.RecommendationItem;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.Product;
import com.esun.shop.repository.ProductRepository;
import com.esun.shop.repository.RecommendationRepository;
import com.esun.shop.repository.RecommendationRepository.Candidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecommendationServiceTest {
    private RecommendationRepository recommendations;
    private ProductRepository products;
    private RecommendationService service;

    @BeforeEach
    void setUp() {
        recommendations = mock(RecommendationRepository.class);
        products = mock(ProductRepository.class);
        service = new RecommendationService(recommendations, products, 2);
        when(products.findById("A")).thenReturn(product("A"));
        when(recommendations.coPurchased(anyCollection(), anyCollection(), anyInt(), anyInt())).thenReturn(List.of());
        when(recommendations.popular(anyCollection(), anyInt(), anyInt())).thenReturn(List.of());
        when(recommendations.newArrivals(anyCollection(), anyInt())).thenReturn(List.of());
    }

    @Test
    void rejectsOutOfRangeLimitBeforeTouchingTheDatabase() {
        for (int bad : new int[] {0, -1, RecommendationService.MAX_LIMIT + 1}) {
            assertThatThrownBy(() -> service.forProduct("A", bad)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.forMember("a@b.c", bad)).isInstanceOf(BusinessException.class);
        }
        verify(recommendations, never()).coPurchased(anyCollection(), anyCollection(), anyInt(), anyInt());
        verify(recommendations, never()).purchasedProductIds(any());
    }

    @Test
    void unknownOrDeletedProductIsNotFound() {
        assertThatThrownBy(() -> service.forProduct("MISSING", 6))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getStatus().value()).isEqualTo(404));
        verify(recommendations, never()).coPurchased(anyCollection(), anyCollection(), anyInt(), anyInt());
    }

    @Test
    void tiersFillInOrderWithoutDuplicatesAndCarryTheirReason() {
        when(recommendations.coPurchased(anyCollection(), anyCollection(), anyInt(), anyInt()))
                .thenReturn(List.of(new Candidate("B", 3), new Candidate("C", 2)));
        // POPULAR also reports B: the strongest (first) signal wins and B is not repeated.
        when(recommendations.popular(anyCollection(), anyInt(), anyInt()))
                .thenReturn(List.of(new Candidate("B", 9), new Candidate("D", 5)));
        when(recommendations.newArrivals(anyCollection(), anyInt())).thenReturn(List.of("E", "F"));
        when(products.findAvailableByIds(anyCollection())).thenReturn(List.of(
                product("F"), product("E"), product("D"), product("C"), product("B")));

        List<RecommendationItem> items = service.forProduct("A", 5);

        assertThat(items).extracting(i -> i.product().getProductId()).containsExactly("B", "C", "D", "E", "F");
        assertThat(items).extracting(RecommendationItem::reason)
                .containsExactly("CO_PURCHASE", "CO_PURCHASE", "POPULAR", "NEW_ARRIVAL", "NEW_ARRIVAL");
        assertThat(items).extracting(RecommendationItem::score).containsExactly(3L, 2L, 5L, 0L, 0L);
    }

    @Test
    void laterTiersAreOnlyAskedForWhatIsStillMissingAndNeverForTakenIds() {
        when(recommendations.coPurchased(anyCollection(), anyCollection(), anyInt(), anyInt()))
                .thenReturn(List.of(new Candidate("B", 3)));
        when(products.findAvailableByIds(anyCollection())).thenReturn(List.of(product("B")));

        service.forProduct("A", 4);

        ArgumentCaptor<Collection<String>> excluded = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Integer> remaining = ArgumentCaptor.forClass(Integer.class);
        verify(recommendations).popular(excluded.capture(), anyInt(), remaining.capture());
        assertThat(excluded.getValue()).containsExactlyInAnyOrder("A", "B");
        assertThat(remaining.getValue()).isEqualTo(3);
        verify(recommendations).coPurchased(anyCollection(), anyCollection(), org.mockito.ArgumentMatchers.eq(2), anyInt());
    }

    @Test
    void aProductThatSoldOutBetweenTheReadsDropsOutInsteadOfBreakingTheList() {
        when(recommendations.coPurchased(anyCollection(), anyCollection(), anyInt(), anyInt()))
                .thenReturn(List.of(new Candidate("B", 3), new Candidate("C", 2)));
        when(products.findAvailableByIds(anyCollection())).thenReturn(List.of(product("C")));

        assertThat(service.forProduct("A", 2)).extracting(i -> i.product().getProductId()).containsExactly("C");
    }

    @Test
    void memberListExcludesEverythingBoughtOrCartedAndAnchorsOnPurchases() {
        when(recommendations.purchasedProductIds("m@x.y")).thenReturn(List.of("P1", "P2"));
        when(recommendations.cartProductIds("m@x.y")).thenReturn(List.of("K1"));

        service.forMember("m@x.y", 3);

        ArgumentCaptor<Collection<String>> anchors = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Collection<String>> exclude = ArgumentCaptor.forClass(Collection.class);
        verify(recommendations).coPurchased(anchors.capture(), exclude.capture(), anyInt(), anyInt());
        assertThat(anchors.getValue()).containsExactly("P1", "P2");
        assertThat(exclude.getValue()).containsExactlyInAnyOrder("P1", "P2", "K1");
    }

    @Test
    void emptyResultWhenNothingIsSellable() {
        assertThat(service.forProduct("A", 6)).isEmpty();
        verify(products, never()).findAvailableByIds(anyCollection());
    }

    private static Product product(String id) {
        Product p = new Product();
        p.setProductId(id);
        p.setProductName("Product " + id);
        return p;
    }
}
