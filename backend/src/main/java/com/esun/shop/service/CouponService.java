package com.esun.shop.service;

import com.esun.shop.dto.CouponPageResponse;
import com.esun.shop.dto.CouponPreview;
import com.esun.shop.dto.CouponView;
import com.esun.shop.dto.CreateCouponRequest;
import com.esun.shop.dto.UpdateCouponRequest;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.AuditAction;
import com.esun.shop.model.DiscountType;
import com.esun.shop.model.Member;
import com.esun.shop.repository.CouponRepository;
import com.esun.shop.repository.CouponRepository.Coupon;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Coupon issuing (ADMIN) and redemption (checkout).
 *
 * Redemption is the money-sensitive part. {@link #redeem} runs inside the order-creation transaction, takes the coupon
 * row lock (a current read, so it sees every committed redemption regardless of the caller's snapshot), validates
 * every rule against the locked row and only then bumps the counters. The global quota and the per-member limit
 * therefore cannot be exceeded by concurrent checkouts, and the discount is always computed server-side from
 * server-side prices; a client can only name a code. Cancelling an order gives its slot back via {@link #release}.
 *
 * Lock order, kept identical on every path: order row -> coupon row -> coupon_member_usage row -> product rows.
 */
@Service
public class CouponService {
    private static final Pattern CODE = Pattern.compile("^[A-Z0-9_-]{3,32}$");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** What a successful redemption took: the coupon identity plus the discount that was actually granted. */
    public record Applied(long couponId, String code, BigDecimal discount) { }

    private final CouponRepository repository;
    private final AuditLogService auditLogService;

    public CouponService(CouponRepository repository, AuditLogService auditLogService) {
        this.repository = repository;
        this.auditLogService = auditLogService;
    }

    // ---- checkout ----

    /** Advisory preview for the cart; nothing is reserved, so checkout may still be refused if quota runs out. */
    public CouponPreview preview(String rawCode, String memberId, BigDecimal subtotal) {
        Coupon coupon = requireKnown(normalize(rawCode));
        BigDecimal discount = validateAndPrice(coupon, subtotal, LocalDateTime.now());
        if (repository.memberUsage(coupon.id(), memberId) >= coupon.perMemberLimit()) throw memberLimitReached();
        return new CouponPreview(coupon.code(), subtotal, discount, subtotal.subtract(discount));
    }

    /**
     * Redeems one use for {@code memberId} against {@code subtotal}. MANDATORY: the coupon lock must live as long as the
     * order transaction, otherwise the quota check and the order insert would not be atomic.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Applied redeem(String rawCode, String memberId, BigDecimal subtotal) {
        Coupon known = requireKnown(normalize(rawCode));
        Coupon coupon = repository.lockById(known.id()).orElseThrow(CouponService::notFound);
        BigDecimal discount = validateAndPrice(coupon, subtotal, LocalDateTime.now());
        if (!repository.tryIncrementMemberUsage(coupon.id(), memberId, coupon.perMemberLimit())) {
            throw memberLimitReached();
        }
        repository.incrementUsed(coupon.id());
        return new Applied(coupon.id(), coupon.code(), discount);
    }

    /** Returns the slot taken by a cancelled order. Caller has already locked the order row. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(long couponId, String memberId) {
        repository.lockById(couponId).ifPresent(coupon -> {
            repository.decrementMemberUsage(couponId, memberId);
            repository.decrementUsed(couponId);
        });
    }

    // ---- admin ----

    @Transactional
    public CouponView create(CreateCouponRequest request, String actor, Member.Role role) {
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) throw new BusinessException("優惠碼格式不合法", HttpStatus.BAD_REQUEST);
        DiscountType type = request.discountType();
        if (type == DiscountType.PERCENT && request.discountValue().compareTo(HUNDRED) >= 0) {
            throw new BusinessException("百分比折扣必須介於 1 到 99", HttpStatus.BAD_REQUEST);
        }
        if (type == DiscountType.FIXED && request.maxDiscount() != null) {
            throw new BusinessException("固定金額折扣不需設定折扣上限", HttpStatus.BAD_REQUEST);
        }
        if (!request.expiresAt().isAfter(request.startsAt())) {
            throw new BusinessException("到期時間必須晚於開始時間", HttpStatus.BAD_REQUEST);
        }
        if (!request.expiresAt().isAfter(LocalDateTime.now())) {
            throw new BusinessException("到期時間必須晚於現在", HttpStatus.BAD_REQUEST);
        }
        Coupon draft = new Coupon(0, code, type, request.discountValue(), request.maxDiscount(),
                request.minOrderAmount() == null ? BigDecimal.ZERO : request.minOrderAmount(), request.totalQuota(), 0,
                request.perMemberLimit() == null ? 1 : request.perMemberLimit(), request.startsAt(),
                request.expiresAt(), true, actor, null);
        long id;
        try {
            id = repository.insert(draft);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException("優惠碼已存在", HttpStatus.CONFLICT);
        }
        Coupon created = repository.lockById(id).orElseThrow(CouponService::notFound);
        auditLogService.record(actor, role, AuditAction.COUPON_CREATE, String.valueOf(id), null, snapshot(created));
        return view(created, LocalDateTime.now());
    }

    /** Before-state is read under the row lock so the audit chain stays gapless under concurrent edits. */
    @Transactional
    public CouponView update(long id, UpdateCouponRequest request, String actor, Member.Role role) {
        Coupon before = repository.lockById(id).orElseThrow(CouponService::notFound);
        if (!request.expiresAt().isAfter(before.startsAt())) {
            throw new BusinessException("到期時間必須晚於開始時間", HttpStatus.BAD_REQUEST);
        }
        if (request.totalQuota() != null && request.totalQuota() < before.usedCount()) {
            throw new BusinessException("總發行量不可小於已使用次數 " + before.usedCount(), HttpStatus.BAD_REQUEST);
        }
        repository.updateMutable(id, request.active(), request.expiresAt(), request.totalQuota());
        Coupon after = repository.lockById(id).orElseThrow(CouponService::notFound);
        auditLogService.record(actor, role, AuditAction.COUPON_UPDATE, String.valueOf(id), snapshot(before), snapshot(after));
        return view(after, LocalDateTime.now());
    }

    /** Enable/disable only: unlike {@link #update} it cannot overwrite a concurrent edit of expiry or quota. */
    @Transactional
    public CouponView setActive(long id, boolean active, String actor, Member.Role role) {
        Coupon before = repository.lockById(id).orElseThrow(CouponService::notFound);
        repository.updateActive(id, active);
        Coupon after = repository.lockById(id).orElseThrow(CouponService::notFound);
        auditLogService.record(actor, role, AuditAction.COUPON_UPDATE, String.valueOf(id), snapshot(before), snapshot(after));
        return view(after, LocalDateTime.now());
    }

    public CouponPageResponse list(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new BusinessException("分頁參數不合法", HttpStatus.BAD_REQUEST);
        }
        LocalDateTime now = LocalDateTime.now();
        return new CouponPageResponse(repository.list(size, page * size).stream().map(c -> view(c, now)).toList(),
                repository.count(), page, size);
    }

    // ---- rules ----

    /**
     * Checks every rule against {@code coupon} and returns the discount to grant. Pure with respect to the database, so
     * it is applied identically to the advisory preview and to the locked row at redemption.
     */
    static BigDecimal validateAndPrice(Coupon coupon, BigDecimal subtotal, LocalDateTime now) {
        if (!coupon.active()) throw notFound();
        if (now.isBefore(coupon.startsAt())) throw new BusinessException("優惠券尚未開始", HttpStatus.BAD_REQUEST);
        if (!now.isBefore(coupon.expiresAt())) throw new BusinessException("優惠券已過期", HttpStatus.BAD_REQUEST);
        if (subtotal.compareTo(coupon.minOrderAmount()) < 0) {
            throw new BusinessException("訂單金額未達優惠券最低消費 " + coupon.minOrderAmount().stripTrailingZeros().toPlainString(),
                    HttpStatus.BAD_REQUEST);
        }
        if (coupon.totalQuota() != null && coupon.usedCount() >= coupon.totalQuota()) {
            throw new BusinessException("優惠券已被領完", HttpStatus.CONFLICT);
        }
        return computeDiscount(coupon, subtotal);
    }

    /**
     * Whole-dollar discount (ECPay only accepts integer amounts), capped by maxDiscount and by subtotal - 1 so the
     * payable amount never drops below 1 (the payment ledger requires a positive amount).
     */
    static BigDecimal computeDiscount(Coupon coupon, BigDecimal subtotal) {
        BigDecimal discount = coupon.type() == DiscountType.FIXED
                ? coupon.value()
                : subtotal.multiply(coupon.value()).divide(HUNDRED, 0, RoundingMode.HALF_UP);
        if (coupon.type() == DiscountType.PERCENT && coupon.maxDiscount() != null) {
            discount = discount.min(coupon.maxDiscount());
        }
        discount = discount.min(subtotal.subtract(BigDecimal.ONE));
        if (discount.signum() <= 0) {
            throw new BusinessException("訂單金額不足以套用此優惠券", HttpStatus.BAD_REQUEST);
        }
        return discount.setScale(2, RoundingMode.HALF_UP);
    }

    private Coupon requireKnown(String code) {
        if (code == null || !CODE.matcher(code).matches()) throw notFound();
        return repository.findByCode(code).orElseThrow(CouponService::notFound);
    }

    private static String normalize(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) throw new BusinessException("請輸入優惠碼", HttpStatus.BAD_REQUEST);
        return rawCode.trim().toUpperCase(Locale.ROOT);
    }

    private static BusinessException notFound() {
        return new BusinessException("優惠碼不存在或已停用", HttpStatus.NOT_FOUND);
    }

    private static BusinessException memberLimitReached() {
        return new BusinessException("您已達此優惠券的使用次數上限", HttpStatus.CONFLICT);
    }

    private static CouponView view(Coupon c, LocalDateTime now) {
        String status;
        if (!c.active()) status = "DISABLED";
        else if (now.isBefore(c.startsAt())) status = "SCHEDULED";
        else if (!now.isBefore(c.expiresAt())) status = "EXPIRED";
        else if (c.totalQuota() != null && c.usedCount() >= c.totalQuota()) status = "EXHAUSTED";
        else status = "ACTIVE";
        return new CouponView(c.id(), c.code(), c.type().name(), c.value(), c.maxDiscount(), c.minOrderAmount(),
                c.totalQuota(), c.usedCount(), c.perMemberLimit(), c.startsAt(), c.expiresAt(), c.active(), status,
                c.createdBy(), c.createdAt());
    }

    private static Map<String, Object> snapshot(Coupon c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", c.code());
        map.put("discountType", c.type().name());
        map.put("discountValue", c.value());
        map.put("maxDiscount", c.maxDiscount());
        map.put("minOrderAmount", c.minOrderAmount());
        map.put("totalQuota", c.totalQuota());
        map.put("perMemberLimit", c.perMemberLimit());
        map.put("startsAt", c.startsAt());
        map.put("expiresAt", c.expiresAt());
        map.put("active", c.active());
        map.put("usedCount", c.usedCount());
        return map;
    }
}
