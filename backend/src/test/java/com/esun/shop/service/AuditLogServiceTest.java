package com.esun.shop.service;

import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.AuditAction;
import com.esun.shop.model.Member;
import com.esun.shop.repository.AuditLogRepository;
import com.esun.shop.repository.MemberRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {
    @Mock private AuditLogRepository repository;
    @Mock private MemberRepository memberRepository;

    private AuditLogService service;

    @BeforeEach
    void setUp() {
        service = new AuditLogService(repository, memberRepository, new ObjectMapper());
    }

    @Test
    void recordUsesSuppliedRoleAndSerialisesSnapshots() {
        service.record("a@example.com", Member.Role.ADMIN, AuditAction.PRODUCT_UPDATE, "P1",
                Map.of("price", 1), Map.of("price", 2));

        verify(repository).insert("a@example.com", "ADMIN", "PRODUCT_UPDATE", "PRODUCT", "P1",
                "{\"price\":1}", "{\"price\":2}");
        verify(memberRepository, never()).findByEmail(any());
    }

    @Test
    void recordResolvesRoleFromMemberTableAndFallsBackToUnknown() {
        Member seller = new Member();
        seller.setRole(Member.Role.SELLER);
        when(memberRepository.findByEmail("s@example.com")).thenReturn(seller);
        when(memberRepository.findByEmail("ghost@example.com")).thenReturn(null);

        service.record("s@example.com", null, AuditAction.PRODUCT_CREATE, "P1", null, Map.of());
        service.record("ghost@example.com", null, AuditAction.PRODUCT_CREATE, "P2", null, null);

        verify(repository).insert("s@example.com", "SELLER", "PRODUCT_CREATE", "PRODUCT", "P1", null, "{}");
        verify(repository).insert("ghost@example.com", "UNKNOWN", "PRODUCT_CREATE", "PRODUCT", "P2", null, null);
    }

    @Test
    void recordRejectsMissingActor() {
        assertThatThrownBy(() -> service.record(" ", Member.Role.ADMIN, AuditAction.PRODUCT_CREATE, "P1", null, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(repository, never()).insert(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void searchValidatesPagingActionAndTimeRange() {
        assertBadRequest(() -> service.search(null, null, null, null, null, null, -1, 20));
        assertBadRequest(() -> service.search(null, null, null, null, null, null, 0, 0));
        assertBadRequest(() -> service.search(null, null, null, null, null, null, 0, 101));
        assertBadRequest(() -> service.search(null, "BOGUS", null, null, null, null, 0, 20));
        assertBadRequest(() -> service.search(null, null, null, null, "yesterday", null, 0, 20));
        assertBadRequest(() -> service.search(null, null, null, null, "2026-02-01T00:00:00", "2026-01-01T00:00:00", 0, 20));
        verify(repository, never()).search(any(), anyInt(), anyInt());
    }

    @Test
    void searchPassesTrimmedFilterAndOffsetToRepository() {
        service.search("a@example.com", "PRODUCT_DELETE", "PRODUCT", "P1", "2026-01-01T00:00:00", null, 2, 10);

        verify(repository).search(eq(new AuditLogRepository.Filter("a@example.com", "PRODUCT_DELETE", "PRODUCT", "P1",
                java.time.LocalDateTime.of(2026, 1, 1, 0, 0), null)), eq(10), eq(20));
    }

    private static void assertBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
