package com.example.demo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class ClaudeRestTemplateConfig {

    @Bean("claudeRestTemplate")
    public RestTemplate claudeRestTemplate(
            @Value("${claude.api.key}") String apiKey,
            @Value("${claude.api.version}") String apiVersion) {

        SimpleClientHttpRequestFactory factory =
                new SimpleClientHttpRequestFactory();

        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(120_000);

        RestTemplate restTemplate = new RestTemplate(factory);

        restTemplate.getInterceptors().add(
                (request, body, execution) -> {
                    if (apiKey != null && !apiKey.isBlank()) {
                        request.getHeaders().set("x-api-key", apiKey);
                    }

                    request.getHeaders().set("anthropic-version", apiVersion);

                    request.getHeaders()
                            .setContentType(MediaType.APPLICATION_JSON);

                    return execution.execute(request, body);
                });

        return restTemplate;
    }
}
