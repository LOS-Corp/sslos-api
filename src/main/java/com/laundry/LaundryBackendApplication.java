package com.laundry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LaundryBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(LaundryBackendApplication.class, args);
	}

}
