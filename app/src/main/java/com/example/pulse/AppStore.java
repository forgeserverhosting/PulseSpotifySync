package com.example.pulse;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

final class AppStore {
    private final SharedPreferences prefs;

    AppStore(Context context) {
        prefs = context.getSharedPreferences("pulse_store", Context.MODE_PRIVATE);
    }

    void saveTokens(String access, String refresh, long expiryMs) {
        SharedPreferences.Editor e = prefs.edit()
                .putString("spotify_access", access == null ? "" : access)
                .putLong("spotify_expiry", expiryMs);
        if (refresh != null && !refresh.isEmpty()) e.putString("spotify_refresh", refresh);
        e.apply();
    }

    String accessToken() { return prefs.getString("spotify_access", ""); }
    String refreshToken() { return prefs.getString("spotify_refresh", ""); }
    long tokenExpiry() { return prefs.getLong("spotify_expiry", 0L); }

    void clearSpotifySession() {
        prefs.edit()
                .remove("spotify_access")
                .remove("spotify_refresh")
                .remove("spotify_expiry")
                .remove("pkce_verifier")
                .remove("oauth_state")
                .apply();
    }

    void savePkce(String verifier, String state) {
        prefs.edit().putString("pkce_verifier", verifier).putString("oauth_state", state).apply();
    }

    String pkceVerifier() { return prefs.getString("pkce_verifier", ""); }
    String oauthState() { return prefs.getString("oauth_state", ""); }
    void clearPkce() { prefs.edit().remove("pkce_verifier").remove("oauth_state").apply(); }

    String developerClientId() { return prefs.getString("developer_client_id", ""); }
    void setDeveloperClientId(String value) { prefs.edit().putString("developer_client_id", value == null ? "" : value.trim()).apply(); }

    long lastAutoSync() { return prefs.getLong("last_auto_sync", 0L); }
    void setLastAutoSync(long value) { prefs.edit().putLong("last_auto_sync", value).apply(); }

    void saveImportedPlaylists(List<Models.SpotifyPlaylist> playlists) {
        JSONArray a = new JSONArray();
        for (Models.SpotifyPlaylist p : playlists) {
            if (!p.imported) continue;
            try { a.put(p.toJson()); } catch (Exception ignored) {}
        }
        prefs.edit().putString("imported_playlists", a.toString()).apply();
    }

    List<Models.SpotifyPlaylist> loadImportedPlaylists() {
        List<Models.SpotifyPlaylist> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("imported_playlists", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) out.add(Models.SpotifyPlaylist.fromJson(o));
            }
        } catch (Exception ignored) {}
        return out;
    }
}
