package com.listener.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.listener.app.config.AudioProperties;
import com.listener.app.config.DlqProperties;
import com.listener.app.config.GitHubProperties;
import com.listener.app.config.RetryProperties;
import com.listener.app.config.TranscriptionProperties;

@SpringBootApplication
@EnableRetry
@EnableScheduling
@EnableConfigurationProperties({
        TranscriptionProperties.class,
        GitHubProperties.class,
        AudioProperties.class,
        RetryProperties.class,
        DlqProperties.class
})
public class ListenerAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(ListenerAppApplication.class, args);
    }
}
