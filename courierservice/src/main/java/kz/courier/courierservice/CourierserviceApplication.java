package kz.courier.courierservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class CourierserviceApplication {

	public static void main(String[] args) {
		SpringApplication.run(CourierserviceApplication.class, args);
	}

}

