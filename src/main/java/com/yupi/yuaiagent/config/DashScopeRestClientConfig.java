package com.yupi.yuaiagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class DashScopeRestClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofMinutes(5));

        return RestClient.builder()
                .requestFactory(factory);
    }
}
