package io.github.hanumanthraghavendra.schemaguard.api;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** The SchemaGuard REST service. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SchemaGuardApplication {

    public static void main(String[] args) {
        // Every time the service handles is an Instant, and the PostgreSQL driver sends the
        // JVM's zone to the server at login. Old zone names such as Asia/Calcutta are refused
        // by servers without legacy time zone data, so the service runs in UTC.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(SchemaGuardApplication.class, args);
    }
}
