package com.fittrack.app;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

class AppDataServiceTest {
    @Test
    void rejectsMissingPrincipal() {
        AppDataService service = new AppDataService(new JdbcTemplate());
        assertThrows(IllegalArgumentException.class, () -> service.load(null));
    }
}
