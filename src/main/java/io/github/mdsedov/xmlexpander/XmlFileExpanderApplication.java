package io.github.mdsedov.xmlexpander;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class XmlFileExpanderApplication {

    public static void main(String[] args) {
        SpringApplication.run(XmlFileExpanderApplication.class, args);
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService expansionExecutor() {
        return Executors.newSingleThreadExecutor(Thread.ofPlatform()
                .name("xml-expansion-", 0)
                .factory());
    }
}
