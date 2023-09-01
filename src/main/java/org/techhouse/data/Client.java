package org.techhouse.data;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Client {
    private final String address;
    private final LocalDateTime connectionTime = LocalDateTime.now();
    private LocalDateTime lastCommandTime;
}
