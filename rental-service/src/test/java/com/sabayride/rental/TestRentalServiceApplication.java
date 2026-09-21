package com.sabayride.rental;

import org.springframework.boot.SpringApplication;

public class TestRentalServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(RentalServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
