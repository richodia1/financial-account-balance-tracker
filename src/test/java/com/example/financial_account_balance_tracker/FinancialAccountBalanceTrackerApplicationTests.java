package com.example.financial_account_balance_tracker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(properties = "tracker.producer.enabled=false")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FinancialAccountBalanceTrackerApplicationTests {

	@Test
	void contextLoads() {
	}

}
