package com.repordar;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * RepoRadar 应用入口。
 *
 * @author frank
 */
@SpringBootApplication
public class RepoRadarApplication {
    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(RepoRadarApplication.class, args)));
    }
}
