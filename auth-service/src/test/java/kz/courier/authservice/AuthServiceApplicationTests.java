package kz.courier.authservice;

import kz.courier.auth.v1.AuthServiceGrpc;
import kz.courier.auth.v1.RegisterRequest;
import kz.courier.auth.v1.RegisterResponse;
import kz.courier.auth.v1.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class AuthServiceApplicationTests {

	@Test
	void contextLoads() {
	}
//
//	@Autowired
//	private AuthServiceGrpc.AuthServiceBlockingStub blockingStub;
//
//	@Test
//	void testRegister() {
//		RegisterRequest request = RegisterRequest.newBuilder()
//				.setEmail("newuser@example.com")
//				.setPassword("Password123!")
//				.setFirstName("John")
//				.setLastName("Doe")
//				.setRole(Role.CLIENT)
//				.build();
//
//		RegisterResponse response = blockingStub.register(request);
//		assertNotNull(response);
//		assertTrue(response.getUserId().length() > 0);
//	}

}
