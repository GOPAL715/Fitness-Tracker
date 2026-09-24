package com.fittrack.api;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
@org.springframework.boot.autoconfigure.SpringBootApplication
@EnableScheduling
@ComponentScan("com.fittrack")
@EnableJpaRepositories({"com.fittrack.auth", "com.fittrack.domain.repositories"})
@EntityScan("com.fittrack")
public class FitTrackApplication { public static void main(String[] a){SpringApplication.run(FitTrackApplication.class,a);} }
