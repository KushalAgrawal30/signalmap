package com.signalmap.common;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OperatorRegistry {

    private final JdbcTemplate jdbc;
    private final Map<String, Integer> nameToId = new ConcurrentHashMap<>();
    private final Map<Integer, String> idToName = new ConcurrentHashMap<>();

    public OperatorRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void load() {
        nameToId.clear();
        idToName.clear();
        jdbc.query("SELECT id, name FROM operators", rs -> {
            int id = rs.getInt("id");
            String name = rs.getString("name");
            nameToId.put(name.toLowerCase(Locale.ROOT), id);
            idToName.put(id, name);
        });
    }

    public Optional<Integer> idFor(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(nameToId.get(name.toLowerCase(Locale.ROOT)));
    }

    public String nameFor(int id) {
        return idToName.get(id);
    }

    public String knownNames() {
        return String.join(", ", idToName.values());
    }
}