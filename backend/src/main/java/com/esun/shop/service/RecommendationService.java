package com.esun.shop.service;

import com.esun.shop.dto.RecommendationItem;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.Product;
import com.esun.shop.repository.ProductRepository;
import com.esun.shop.repository.RecommendationRepository;
import com.esun.shop.repository.RecommendationRepository.Candidate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only recommendations derived from order history (item-based co-purchase, then best sellers, then new arrivals).
 * It never writes and never touches the checkout path, so it takes no locks and cannot affect stock or order
 * consistency; the whole request runs in one read-only transaction so its several queries see one snapshot.
 *
 * Tiers are concatenated in order and de-duplicated, so a product appears once, labelled by the strongest signal.
 */
@Service
public class RecommendationService {
    public static final int DEFAULT_LIMIT = 6;
    public static final int MAX_LIMIT = 20;
    static final String CO_PURCHASE = "CO_PURCHASE";
    static final String POPULAR = "POPULAR";
    static final String NEW_ARRIVAL = "NEW_ARRIVAL";

    private final RecommendationRepository recommendations;
    private final ProductRepository products;
    private final int minSupport;

    public RecommendationService(RecommendationRepository recommendations, ProductRepository products,
                                 @Value("${recommendation.min-support:2}") int minSupport) {
        this.recommendations = recommendations;
        this.products = products;
        this.minSupport = Math.max(1, minSupport);
    }

    /** "Customers who bought this also bought" — public, aggregate-only. */
    @Transactional(readOnly = true)
    public List<RecommendationItem> forProduct(String productId, int limit) {
        requireLimit(limit);
        if (products.findById(productId) == null) {
            throw new BusinessException("找不到商品", HttpStatus.NOT_FOUND);
        }
        Set<String> exclude = new LinkedHashSet<>(List.of(productId));
        return fill(List.of(productId), exclude, limit);
    }

    /** Personalised list for a member: co-purchases of their own basket history, minus what they own or carted. */
    @Transactional(readOnly = true)
    public List<RecommendationItem> forMember(String email, int limit) {
        requireLimit(limit);
        List<String> purchased = recommendations.purchasedProductIds(email);
        Set<String> exclude = new LinkedHashSet<>(purchased);
        exclude.addAll(recommendations.cartProductIds(email));
        return fill(purchased, exclude, limit);
    }

    private List<RecommendationItem> fill(List<String> anchors, Set<String> exclude, int limit) {
        Map<String, String> reasons = new LinkedHashMap<>();
        Map<String, Long> scores = new LinkedHashMap<>();
        add(reasons, scores, CO_PURCHASE,
                recommendations.coPurchased(anchors, exclude, minSupport, limit), limit);
        if (reasons.size() < limit) {
            add(reasons, scores, POPULAR,
                    recommendations.popular(withKeys(exclude, reasons), minSupport, limit - reasons.size()), limit);
        }
        if (reasons.size() < limit) {
            List<Candidate> fresh = recommendations.newArrivals(withKeys(exclude, reasons), limit - reasons.size())
                    .stream().map(id -> new Candidate(id, 0)).toList();
            add(reasons, scores, NEW_ARRIVAL, fresh, limit);
        }
        if (reasons.isEmpty()) return List.of();

        Map<String, Product> byId = new LinkedHashMap<>();
        products.findAvailableByIds(reasons.keySet()).forEach(product -> byId.put(product.getProductId(), product));
        List<RecommendationItem> result = new ArrayList<>();
        // Rank order comes from the tiers; a product that sold out or was deleted between the two reads just drops out.
        reasons.forEach((id, reason) -> {
            Product product = byId.get(id);
            if (product != null) result.add(new RecommendationItem(product, reason, scores.get(id)));
        });
        return result;
    }

    private static void add(Map<String, String> reasons, Map<String, Long> scores, String reason,
                            List<Candidate> candidates, int limit) {
        for (Candidate candidate : candidates) {
            if (reasons.size() >= limit) return;
            if (reasons.putIfAbsent(candidate.productId(), reason) == null) {
                scores.put(candidate.productId(), candidate.score());
            }
        }
    }

    private static Set<String> withKeys(Set<String> exclude, Map<String, String> reasons) {
        Set<String> all = new LinkedHashSet<>(exclude);
        all.addAll(reasons.keySet());
        return all;
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BusinessException("limit 必須介於 1 到 " + MAX_LIMIT, HttpStatus.BAD_REQUEST);
        }
    }
}
