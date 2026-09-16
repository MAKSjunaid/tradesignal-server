package com.tradesignal.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradesignal.model.AppState;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;

/**
 * Persists the whole AppState as one JSON blob.
 *
 * IMPORTANT: on Render's free tier the container filesystem is ephemeral - it is
 * destroyed every time the service sleeps or restarts, which wipes local files.
 * So if a DATABASE_URL environment variable is present we store state in Postgres
 * (survives restarts); otherwise we fall back to a local file, which is fine for
 * running on your own machine but WILL lose data on a free Render instance.
 */
@Component
public class DataStore {

    private final ObjectMapper mapper = new ObjectMapper();
    private final File dataDir = new File("data");
    private final File storeFile = new File(dataDir, "store.json");
    private final PostgresStateRepository postgres;

    private AppState state;

    public DataStore(PostgresStateRepository postgres) {
        this.postgres = postgres;
        if (!dataDir.exists()) dataDir.mkdirs();
        this.state = load();
    }

    /** True when state is being kept in Postgres and will survive restarts. */
    public boolean isDurable() {
        return postgres.isEnabled();
    }

    private synchronized AppState load() {
        if (postgres.isEnabled()) {
            try {
                String json = postgres.read();
                if (json != null && !json.isBlank()) {
                    return mapper.readValue(json, AppState.class);
                }
                return new AppState();
            } catch (Exception e) {
                System.err.println("Could not read state from Postgres, starting fresh: " + e.getMessage());
                return new AppState();
            }
        }
        try {
            if (storeFile.exists()) return mapper.readValue(storeFile, AppState.class);
        } catch (IOException e) {
            System.err.println("Could not read store.json, starting fresh: " + e.getMessage());
        }
        return new AppState();
    }

    public synchronized AppState get() {
        return state;
    }

    public synchronized void save() {
        try {
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(state);
            if (postgres.isEnabled()) {
                postgres.write(json);
            } else {
                mapper.writerWithDefaultPrettyPrinter().writeValue(storeFile, state);
            }
        } catch (Exception e) {
            System.err.println("Could not persist state: " + e.getMessage());
        }
    }

    public synchronized void reset() {
        this.state = new AppState();
        save();
    }
}
