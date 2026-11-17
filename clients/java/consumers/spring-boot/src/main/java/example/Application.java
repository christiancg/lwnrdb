package example;

import java.time.LocalDateTime;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    CommandLineRunner run(OrderService service) {
        return args -> {
            service.replace(new Order("o-0", "cancelled", 0, LocalDateTime.now()),
                    new Order("o-1", "active", 120, LocalDateTime.now()));
            service.largeActiveOrders().forEach(System.out::println);
        };
    }
}
