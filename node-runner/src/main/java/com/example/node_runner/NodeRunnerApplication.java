package com.example.node_runner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@EnableConfigurationProperties
@ComponentScan(basePackages = {
		"com.example.node_runner",
		"com.example.networking.http",
		"com.example.networking.util"
})
public class NodeRunnerApplication {

	public static void main(String[] args) {
		SpringApplication.run(NodeRunnerApplication.class, args);
	}

}
