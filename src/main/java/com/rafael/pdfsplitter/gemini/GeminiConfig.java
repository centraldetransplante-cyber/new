package com.rafael.pdfsplitter.gemini;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "gemini")
public interface GeminiConfig {

    @WithDefault("")
    String apiKey();

    @WithDefault("gemini-2.0-flash")
    String modelo();
}
