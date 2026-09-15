package com.rafael.pdfsplitter.gemini;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "gemini")
public interface GeminiConfig {

    @WithDefault("")
    String apiKey();

    @WithDefault("gemini-3.5-flash-lite")
    String modelo();
}
