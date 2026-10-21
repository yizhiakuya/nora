package com.nora.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthFilterTest {
    @Test
    void handlesPublicPrivateBearerAndMediaRequests() throws Exception {
        for (String path : new String[]{"/api/auth/status", "/api/chat/health", "/api/files"}) {
            var request = new MockHttpServletRequest("GET", path);
            request.setServletPath(path);
            new AuthFilter("secret").doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
            request = new MockHttpServletRequest("GET", path);
            request.setServletPath(path);
            request.addHeader("Authorization", "Bearer secret");
            new AuthFilter("secret").doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
        }
        var media = new MockHttpServletRequest("GET", "/api/media/test");
        media.setServletPath("/api/media/test");
        media.setParameter("token", "secret");
        new AuthFilter("secret").doFilter(media, new MockHttpServletResponse(), (req, res) -> {});
        new AuthFilter("").doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> {});
    }
}
