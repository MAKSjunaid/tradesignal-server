package com.tradesignal.store;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

/**
 * Stores the whole app state as a single JSON row in Postgres, so it survives
 * Render free-tier container restarts (the local filesystem does not).
 *
 * Enabled only when a DATABASE_URL environment variable is set. Accepts either a
 * standard JDBC URL (jdbc:postgresql://...) or the postgres://user:pass@host/db
 * form that Neon, Supabase and Render's own Postgres hand out.
 */
@Component
public class PostgresStateRepository {

    private static final String ROW_ID = "singleton";

    private String jdbcUrl;
    private Properties connectionProps;
    private boolean enabled = false;

    public PostgresStateRepository() {
        String raw = System.getenv("DATABASE_URL");
        if (raw == null || raw.isBlank()) {
            System.out.println("DATABASE_URL not set - falling back to local file storage "
                    + "(NOTE: this does not survive restarts on Render's free tier).");
            return;
        }
        try {
            parseUrl(raw.trim());
            initSchema();
            enabled = true;
            System.out.println("Using Postgres for durable state storage.");
        } catch (Exception e) {
            System.err.println("Could not initialise Postgres (" + e.getMessage()
                    + ") - falling back to local file storage.");
            enabled = false;
        }
    }

    private void parseUrl(String raw) {
        connectionProps = new Properties();
        if (raw.startsWith("jdbc:")) {
            jdbcUrl = raw;
            return;
        }
        // postgres://user:password@host:port/database?params
        URI uri = URI.create(raw);
        String userInfo = uri.getUserInfo();
        if (userInfo != null) {
            String[] parts = userInfo.split(":", 2);
            connectionProps.setProperty("user", parts[0]);
            if (parts.length > 1) connectionProps.setProperty("password", parts[1]);
        }
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String query = uri.getQuery() == null ? "" : "?" + uri.getQuery();
        jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath() + query;
        // Managed Postgres providers (Neon, Supabase, Render) require TLS.
        if (!jdbcUrl.contains("sslmode=")) {
            connectionProps.setProperty("sslmode", "require");
        }
    }

    private Connection connect() throws Exception {
        return DriverManager.getConnection(jdbcUrl, connectionProps);
    }

    private void initSchema() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS app_state ("
                    + "id TEXT PRIMARY KEY, "
                    + "payload TEXT NOT NULL, "
                    + "updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW())");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String read() throws Exception {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("SELECT payload FROM app_state WHERE id = ?")) {
            ps.setString(1, ROW_ID);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public void write(String json) throws Exception {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO app_state (id, payload, updated_at) VALUES (?, ?, NOW()) "
                             + "ON CONFLICT (id) DO UPDATE SET payload = EXCLUDED.payload, updated_at = NOW()")) {
            ps.setString(1, ROW_ID);
            ps.setString(2, json);
            ps.executeUpdate();
        }
    }
}
