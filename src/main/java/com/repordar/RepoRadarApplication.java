package com.repordar;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RepoRadarApplication {
    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(RepoRadarApplication.class, args)));
    }
}
