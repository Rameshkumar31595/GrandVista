package com.grandvista.roomservice.security;

import com.grandvista.roomservice.util.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class InternalApiKeyFilterTest {

    private final SecurityErrorWriter errorWriter = mock(SecurityErrorWriter.class);
    private final InternalApiKeyFilter filter = new InternalApiKeyFilter("secret-key", errorWriter);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void percentEncodedInternalPathIsStillTreatedAsInternal() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/%69nternal/holds/1");
        request.setServletPath("/internal/holds/1"); // what the container hands to MVC and Spring Security

        assertThat(InternalApiKeyFilter.isInternalPath(request)).isTrue();
        assertThat(filter.shouldNotFilter(request)).isFalse();
    }

    @Test
    void ordinaryPathsAreNotInternal() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/rooms/1");
        request.setServletPath("/rooms/1");
        MockHttpServletRequest lookalike = new MockHttpServletRequest("GET", "/internalx/1");
        lookalike.setServletPath("/internalx/1");

        assertThat(filter.shouldNotFilter(request)).isTrue();
        assertThat(filter.shouldNotFilter(lookalike)).isTrue();
    }

    @Test
    void missingOrWrongKeyIsRejectedAndNeverMarkedVerified() throws Exception {
        for (String key : new String[]{null, "", "wrong-key", "secret-key "}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/holds/1");
            if (key != null) {
                request.addHeader(InternalApiKeyFilter.HEADER, key);
            }
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, new MockHttpServletResponse(), chain);

            assertThat(chain.getRequest()).as("chain must not continue for key '%s'", key).isNull();
            assertThat(request.getAttribute(InternalApiKeyFilter.VERIFIED_ATTRIBUTE)).isNull();
        }
        verify(errorWriter, org.mockito.Mockito.times(4)).writeForbidden(any(), any(), eq("Internal endpoint: trusted service credentials required"));
    }

    @Test
    void validKeyWithoutTokenActsAsTheTrustedSystem() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/holds/1");
        request.addHeader(InternalApiKeyFilter.HEADER, "secret-key");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(request.getAttribute(InternalApiKeyFilter.VERIFIED_ATTRIBUTE)).isNotNull();
        assertThat(AuthenticatedUser.from(SecurityContextHolder.getContext().getAuthentication()))
                .isEqualTo(AuthenticatedUser.SYSTEM);
    }

    @Test
    void validKeyWithATokenHeaderDoesNotReplaceTheGuestIdentity() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/holds/1");
        request.addHeader(InternalApiKeyFilter.HEADER, "secret-key");
        request.addHeader("Authorization", "Bearer some.jwt.value");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull(); // left to the bearer-token filter
        assertThat(request.getAttribute(InternalApiKeyFilter.VERIFIED_ATTRIBUTE)).isNotNull();
    }

    @Test
    void authorizationRuleNeedsBothAnAuthenticatedCallerAndAVerifiedKey() {
        var rule = InternalApiKeyFilter.requireVerifiedKey();
        var guest = new UsernamePasswordAuthenticationToken("guest", "n/a", AuthorityUtils.createAuthorityList("ROLE_USER"));
        var anonymous = new AnonymousAuthenticationToken("key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        MockHttpServletRequest verified = new MockHttpServletRequest();
        verified.setAttribute(InternalApiKeyFilter.VERIFIED_ATTRIBUTE, Boolean.TRUE);
        MockHttpServletRequest unverified = new MockHttpServletRequest();

        assertThat(rule.authorize(() -> guest, new RequestAuthorizationContext(verified)).isGranted()).isTrue();
        assertThat(rule.authorize(() -> guest, new RequestAuthorizationContext(unverified)).isGranted())
                .as("an authenticated user whose request skipped the key check must be refused").isFalse();
        assertThat(rule.authorize(() -> anonymous, new RequestAuthorizationContext(verified)).isGranted()).isFalse();
    }
}
