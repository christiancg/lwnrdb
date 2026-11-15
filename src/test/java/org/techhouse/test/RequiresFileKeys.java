package org.techhouse.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.condition.EnabledIf;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@EnabledIf(value = "org.techhouse.test.PlatformSupport#fileKeys", disabledReason = "this platform reports no file key"
        + " (inode), which the power-loss shadow keys every file by")
public @interface RequiresFileKeys {
}
