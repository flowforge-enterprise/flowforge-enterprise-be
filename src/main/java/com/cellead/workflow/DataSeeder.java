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
            users.save(new AppUser("requester", passwordEncoder.encode(Constants.DEFAULT_PASSWORD), Role.REQUESTER));
            users.save(new AppUser("approver", passwordEncoder.encode(Constants.DEFAULT_PASSWORD), Role.APPROVER));
            users.save(new AppUser("admin", passwordEncoder.encode(Constants.DEFAULT_PASSWORD), Role.ADMIN));
        };
    }
}
