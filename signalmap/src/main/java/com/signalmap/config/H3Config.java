package com.signalmap.config;

import com.uber.h3core.H3Core;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class H3Config {

    /**
     * H3Core loads a native library on first init and is thread-safe for the
     * pure calculation methods we use, so a single shared bean is fine.
     */
    @Bean
    public H3Core h3Core() throws IOException {
        return H3Core.newInstance();
    }
}
