package com.esun.shop.security;

import com.esun.shop.model.Member;
import com.esun.shop.repository.MemberRepository;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Slice tests (@WebMvcTest) have no database, but JwtAuthFilter now checks member status/token_version on every
 * request. This stub reports every member as ACTIVE at token_version 0 - the version JwtService.generateToken(email,
 * role) stamps - so the existing controller tests keep exercising the controllers, not revocation.
 */
@TestConfiguration
public class ActiveMemberTestConfig {
    @Bean
    MemberRepository memberRepository() {
        MemberRepository repository = Mockito.mock(MemberRepository.class);
        Mockito.when(repository.findAuthState(Mockito.anyString()))
                .thenReturn(new MemberRepository.AuthState(Member.Status.ACTIVE, 0));
        return repository;
    }
}
