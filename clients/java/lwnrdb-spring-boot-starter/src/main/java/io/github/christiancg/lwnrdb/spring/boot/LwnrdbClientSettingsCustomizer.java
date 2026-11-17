package io.github.christiancg.lwnrdb.spring.boot;

import io.github.christiancg.lwnrdb.client.ClientSettings;

@FunctionalInterface
public interface LwnrdbClientSettingsCustomizer {
    void customize(ClientSettings.Builder builder);
}
