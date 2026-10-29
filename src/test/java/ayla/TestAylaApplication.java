package ayla;

import org.springframework.boot.SpringApplication;

public class TestAylaApplication {

	public static void main(String[] args) {
		SpringApplication.from(AylaApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
