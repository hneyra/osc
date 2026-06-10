package dev.osc.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "dev.osc")
@EnableScheduling
public class OscApplication {

    public static void main(String[] args) {
        SpringApplication.run(OscApplication.class, args);
    }
}
