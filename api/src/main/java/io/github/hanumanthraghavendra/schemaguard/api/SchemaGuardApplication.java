package io.github.hanumanthraghavendra.schemaguard.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** The SchemaGuard REST service. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SchemaGuardApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchemaGuardApplication.class, args);
    }
}
