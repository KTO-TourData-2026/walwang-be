package com.server.walwang_be;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WalwangBeApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalwangBeApplication.class, args);
    }

}
