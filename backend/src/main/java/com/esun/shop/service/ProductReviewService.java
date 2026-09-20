package com.esun.shop.service;

import com.esun.shop.dto.ReviewPageResponse;
import com.esun.shop.dto.ReviewRequest;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.Member;
import com.esun.shop.model.ProductReview;
import com.esun.shop.repository.MemberRepository;
import com.esun.shop.repository.ProductRepository;
import com.esun.shop.repository.ProductReviewRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class ProductReviewService {
    private static final Map<String, String> SORTS = Map.of(
            "newest", "pr.created_at DESC, pr.id DESC",
            "rating-high", "pr.rating DESC, pr.created_at DESC, pr.id DESC",
            "rating-low", "pr.rating ASC, pr.created_at DESC, pr.id DESC");

    private final ProductReviewRepository reviewRepository;
    private final ProductRepository productRepository;
    private final MemberRepository memberRepository;

    public ProductReviewService(ProductReviewRepository reviewRepository, ProductRepository productRepository,
                                MemberRepository memberRepository) {
        this.reviewRepository = reviewRepository;
        this.productRepository = productRepository;
        this.memberRepository = memberRepository;
    }

    public ReviewPageResponse getVisible(String productId, int page, int size, String sort) {
        requireProduct(productId);
        validatePage(page, size);
        String orderBy = SORTS.get(sort);
        if (orderBy == null) {
            throw new BusinessException("不支援的評論排序", HttpStatus.BAD_REQUEST);
        }
        return new ReviewPageResponse(
                reviewRepository.findVisible(productId, orderBy, size, page * size),
                reviewRepository.countVisible(productId), page, size,
                reviewRepository.averageVisible(productId), reviewRepository.countVisible(productId));
    }

    @Transactional
    public ProductReview create(String productId, String email, ReviewRequest request) {
        requireProduct(productId);
        Member member = requireMember(email);
        if (!reviewRepository.hasPurchased(member.getId(), productId)) {
            throw new BusinessException("只有購買過此商品的會員才能評論", HttpStatus.FORBIDDEN);
        }
        try {
            reviewRepository.insert(productId, member.getId(), request.getRating(), request.getContent().trim());
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("每位會員對同一商品只能留下一則評論", HttpStatus.CONFLICT);
        }
        return reviewRepository.findByMemberAndProduct(member.getId(), productId);
    }

    public ProductReview getMine(String productId, String email) {
        Member member = requireMember(email);
        return reviewRepository.findByMemberAndProduct(member.getId(), productId);
    }

    @Transactional
    public ProductReview update(long reviewId, String email, ReviewRequest request) {
        Member member = requireMember(email);
        ProductReview existing = requireReview(reviewId);
        if (!existing.getMemberId().equals(member.getId())) {
            throw new BusinessException("無權修改此評論", HttpStatus.FORBIDDEN);
        }
        if (reviewRepository.updateOwned(reviewId, member.getId(), request.getRating(), request.getContent().trim()) != 1) {
            throw new BusinessException("評論狀態已變更", HttpStatus.CONFLICT);
        }
        return reviewRepository.findById(reviewId);
    }

    @Transactional
    public void delete(long reviewId, String email) {
        Member member = requireMember(email);
        ProductReview existing = requireReview(reviewId);
        if (!existing.getMemberId().equals(member.getId())) {
            throw new BusinessException("無權刪除此評論", HttpStatus.FORBIDDEN);
        }
        if (reviewRepository.deleteOwned(reviewId, member.getId()) != 1) {
            throw new BusinessException("評論狀態已變更", HttpStatus.CONFLICT);
        }
    }

    public List<ProductReview> getForSeller(String email, Member.Role role, String productId, int page, int size) {
        requireSeller(role);
        validatePage(page, size);
        if (productId != null) requireProduct(productId);
        if (role == Member.Role.ADMIN) {
            return reviewRepository.findForAdmin(productId, size, page * size);
        }
        if (productId != null && !productRepository.isOwnedBy(productId, email)) {
            throw new BusinessException("無權管理此商品的評論", HttpStatus.FORBIDDEN);
        }
        return reviewRepository.findForSeller(email, productId, size, page * size);
    }

    @Transactional
    public ProductReview setVisibility(long reviewId, String email, Member.Role role,
                                       ProductReview.Visibility visibility) {
        requireSeller(role);
        ProductReview review = requireReview(reviewId);
        if (role != Member.Role.ADMIN && !productRepository.isOwnedBy(review.getProductId(), email)) {
            throw new BusinessException("無權管理此商品的評論", HttpStatus.FORBIDDEN);
        }
        if (reviewRepository.setVisibility(reviewId, visibility) != 1) {
            throw new BusinessException("評論狀態已變更", HttpStatus.CONFLICT);
        }
        return reviewRepository.findById(reviewId);
    }

    private void requireProduct(String productId) {
        if (productRepository.findById(productId) == null) {
            throw new BusinessException("商品不存在", HttpStatus.NOT_FOUND);
        }
    }

    private Member requireMember(String email) {
        Member member = email == null ? null : memberRepository.findByEmail(email);
        if (member == null) throw new BusinessException("會員不存在", HttpStatus.UNAUTHORIZED);
        return member;
    }

    private ProductReview requireReview(long reviewId) {
        ProductReview review = reviewRepository.findById(reviewId);
        if (review == null) throw new BusinessException("評論不存在", HttpStatus.NOT_FOUND);
        return review;
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 50) {
            throw new BusinessException("分頁參數不正確", HttpStatus.BAD_REQUEST);
        }
    }

    private static void requireSeller(Member.Role role) {
        if (role != Member.Role.SELLER && role != Member.Role.ADMIN) {
            throw new BusinessException("權限不足", HttpStatus.FORBIDDEN);
        }
    }
}
