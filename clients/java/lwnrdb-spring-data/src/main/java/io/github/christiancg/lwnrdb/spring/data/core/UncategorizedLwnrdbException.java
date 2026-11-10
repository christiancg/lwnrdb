package io.github.christiancg.lwnrdb.spring.data.core;

import java.io.Serial;
import org.springframework.dao.UncategorizedDataAccessException;

public class UncategorizedLwnrdbException extends UncategorizedDataAccessException {
    @Serial
    private static final long serialVersionUID = 1L;

    public UncategorizedLwnrdbException(String message, Throwable cause) {
        super(message, cause);
    }
}
