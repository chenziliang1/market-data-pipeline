package com.example.demo.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyFilterTest {

    @Test
    void protectedEndpointIsRefusedWhenNoKeyIsConfigured() throws Exception {
        MockHttpServletResponse response = run(new ApiKeyFilter(""), "POST", "/api/admin/dead-letters/replay", "anything");

        assertThat(response.getStatus()).isEqualTo(503);
    }

    @Test
    void correctKeyIsAccepted() throws Exception {
        MockHttpServletResponse response = run(new ApiKeyFilter("secret"), "POST", "/api/load/BTCUSDT/1/2", "secret");

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void keyOfDifferentLengthIsRejected() throws Exception {
        MockHttpServletResponse response = run(new ApiKeyFilter("secret"), "POST", "/api/load/BTCUSDT/1/2", "secret-and-more");

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void readOnlyEndpointsDoNotNeedKey() throws Exception {
        MockHttpServletResponse response = run(new ApiKeyFilter("secret"), "GET", "/api/aggregates/DAILY/BTCUSDT/1/2", null);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private static MockHttpServletResponse run(ApiKeyFilter filter, String method, String path, String key)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (key != null) {
            request.addHeader(ApiKeyFilter.HEADER, key);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
