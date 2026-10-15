package com.islevendor.pay;

import org.springframework.boot.SpringApplication;

public class TestIslevendorPayApplication {

	public static void main(String[] args) {
		SpringApplication.from(IslevendorPayApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
