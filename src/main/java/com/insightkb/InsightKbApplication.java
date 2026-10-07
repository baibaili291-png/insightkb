package com.insightkb;

import com.insightkb.config.InsightKbProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(InsightKbProperties.class)
public class InsightKbApplication {

    public static void main(String[] args) {
        SpringApplication.run(InsightKbApplication.class, args);
    }
}
