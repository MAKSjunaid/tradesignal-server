package com.tradesignal.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradesignal.model.AppState;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;

/**
 * Very small file-backed store. Not built for high concurrency, but this
 * app only ever has one background scheduler plus a handful of HTTP
 * requests at a time, so a synchronized in-memory object + write-through
 * to disk is enough.
 */
@Component
public class DataStore {

    private final ObjectMapper mapper = new ObjectMapper();
    private final File dataDir = new File("data");
    private final File storeFile = new File(dataDir, "store.json");

    private AppState state;

    public DataStore() {
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
        this.state = load();
    }

    private synchronized AppState load() {
        try {
            if (storeFile.exists()) {
                return mapper.readValue(storeFile, AppState.class);
            }
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
            mapper.writerWithDefaultPrettyPrinter().writeValue(storeFile, state);
        } catch (IOException e) {
            System.err.println("Could not write store.json: " + e.getMessage());
        }
    }

    public synchronized void reset() {
        this.state = new AppState();
        save();
    }
}
