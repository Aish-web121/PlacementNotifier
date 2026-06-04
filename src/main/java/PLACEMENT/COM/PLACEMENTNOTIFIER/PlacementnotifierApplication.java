package PLACEMENT.COM.PLACEMENTNOTIFIER;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PlacementnotifierApplication {

	public static void main(String[] args) {
		SpringApplication.run(PlacementnotifierApplication.class, args);
	}

}
