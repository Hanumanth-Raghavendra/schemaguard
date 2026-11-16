package io.github.hanumanthraghavendra.schemaguard.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Lets the web front end, served from another origin, call the API. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ApiProperties properties;

    public WebConfig(ApiProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(properties.cors().allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST")
                .allowedHeaders("Content-Type")
                .maxAge(3600);
    }
}
