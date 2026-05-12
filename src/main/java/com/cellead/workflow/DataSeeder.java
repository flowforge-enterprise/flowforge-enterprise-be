package com.cellead.workflow;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
class DataSeeder {
    @Bean
    CommandLineRunner seedUsers(UserRepository users, PasswordEncoder passwordEncoder) {
        return args -> {
            if (users.count() > 0) {
                return;
            }
            users.save(new AppUser("requester", passwordEncoder.encode("password123"), Role.REQUESTER));
            users.save(new AppUser("approver", passwordEncoder.encode("password123"), Role.APPROVER));
            users.save(new AppUser("admin", passwordEncoder.encode("password123"), Role.ADMIN));
        };
    }
}
