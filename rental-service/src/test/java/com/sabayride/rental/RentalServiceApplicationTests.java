package com.sabayride.rental;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RentalServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
