package com.example.pulse;

import android.net.Uri;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

final class SpotifyClient {
    static final String REDIRECT_URI = "pulse-auth://callback";
    static final String SCOPES = "playlist-read-private playlist-read-collaborative";

    interface TokenCallback { void onSuccess(); void onError(String message); }
    interface PlaylistsCallback { void onSuccess(List<Models.SpotifyPlaylist> playlists); void onError(String message); }
    interface PlaylistCallback { void onSuccess(Models.SpotifyPlaylist playlist); void onError(String message); }
    interface SnapshotCallback { void onSuccess(String snapshotId); void onError(String message); }

    private final AppStore store;
    private final String clientId;

    SpotifyClient(AppStore store, String clientId) {
        this.store = store;
        this.clientId = clientId == null ? "" : clientId.trim();
    }

    boolean configured() { return !clientId.isEmpty(); }
    boolean hasSession() { return !store.refreshToken().isEmpty() || (!store.accessToken().isEmpty() && store.tokenExpiry() > System.currentTimeMillis()); }

    Uri buildAuthorizeUri() throws Exception {
        String verifier = randomBase64Url(64);
        String state = randomBase64Url(24);
        String challenge = base64Url(sha256(verifier.getBytes(StandardCharsets.UTF_8)));
        store.savePkce(verifier, state);

        return Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("redirect_uri", REDIRECT_URI)
                .appendQueryParameter("scope", SCOPES)
                .appendQueryParameter("state", state)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("code_challenge", challenge)
                .build();
    }

    void exchangeCode(String code, TokenCallback cb) {
        new Thread(() -> {
            try {
                String verifier = store.pkceVerifier();
                if (verifier.isEmpty()) throw new Exception("Missing PKCE verifier. Try connecting again.");
                String body = form(
                        "client_id", clientId,
                        "grant_type", "authorization_code",
                        "code", code,
                        "redirect_uri", REDIRECT_URI,
                        "code_verifier", verifier
                );
                JSONObject json = requestJson("POST", "https://accounts.spotify.com/api/token", null, body);
                saveTokenResponse(json);
                store.clearPkce();
                cb.onSuccess();
            } catch (Exception e) {
                cb.onError(cleanError(e));
            }
        }).start();
    }

    void ensureToken(TokenCallback cb) {
        if (!store.accessToken().isEmpty() && store.tokenExpiry() > System.currentTimeMillis() + 60_000L) {
            cb.onSuccess();
            return;
        }
        String refresh = store.refreshToken();
        if (refresh.isEmpty()) {
            cb.onError("Spotify session expired. Connect Spotify again.");
            return;
        }
        new Thread(() -> {
            try {
                String body = form(
                        "client_id", clientId,
                        "grant_type", "refresh_token",
                        "refresh_token", refresh
                );
                JSONObject json = requestJson("POST", "https://accounts.spotify.com/api/token", null, body);
                saveTokenResponse(json);
                cb.onSuccess();
            } catch (Exception e) {
                cb.onError(cleanError(e));
            }
        }).start();
    }

    void getCurrentUserPlaylists(PlaylistsCallback cb) {
        ensureToken(new TokenCallback() {
            @Override public void onSuccess() {
                new Thread(() -> {
                    try {
                        List<Models.SpotifyPlaylist> out = new ArrayList<>();
                        String next = "https://api.spotify.com/v1/me/playlists?limit=50";
                        int safety = 0;
                        while (next != null && !next.isEmpty() && safety++ < 20) {
                            JSONObject root = requestJson("GET", next, bearer(), null);
                            JSONArray items = root.optJSONArray("items");
                            if (items != null) {
                                for (int i = 0; i < items.length(); i++) {
                                    JSONObject o = items.optJSONObject(i);
                                    if (o != null) out.add(parsePlaylist(o));
                                }
                            }
                            next = root.optString("next", "");
                        }
                        cb.onSuccess(out);
                    } catch (Exception e) { cb.onError(cleanError(e)); }
                }).start();
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    void fetchPlaylist(String playlistId, PlaylistCallback cb) {
        ensureToken(new TokenCallback() {
            @Override public void onSuccess() {
                new Thread(() -> {
                    try {
                        JSONObject meta = requestJson("GET", "https://api.spotify.com/v1/playlists/" + encPath(playlistId), bearer(), null);
                        Models.SpotifyPlaylist p = parsePlaylist(meta);
                        p.imported = true;
                        p.tracks.clear();

                        String next = "https://api.spotify.com/v1/playlists/" + encPath(playlistId) + "/items?limit=100";
                        int safety = 0;
                        while (next != null && !next.isEmpty() && safety++ < 50) {
                            JSONObject root = requestJson("GET", next, bearer(), null);
                            JSONArray items = root.optJSONArray("items");
                            if (items != null) {
                                for (int i = 0; i < items.length(); i++) {
                                    JSONObject wrapper = items.optJSONObject(i);
                                    if (wrapper == null) continue;
                                    JSONObject item = wrapper.optJSONObject("item");
                                    if (item == null) item = wrapper.optJSONObject("track");
                                    if (item == null) continue;
                                    Models.SpotifyTrack t = parseTrack(item);
                                    if (t != null) p.tracks.add(t);
                                }
                            }
                            next = root.optString("next", "");
                        }
                        p.totalItems = p.tracks.size();
                        p.lastSyncedAt = System.currentTimeMillis();
                        cb.onSuccess(p);
                    } catch (Exception e) { cb.onError(cleanError(e)); }
                }).start();
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    void fetchSnapshot(String playlistId, SnapshotCallback cb) {
        ensureToken(new TokenCallback() {
            @Override public void onSuccess() {
                new Thread(() -> {
                    try {
                        String url = "https://api.spotify.com/v1/playlists/" + encPath(playlistId) + "?fields=snapshot_id";
                        JSONObject o = requestJson("GET", url, bearer(), null);
                        cb.onSuccess(o.optString("snapshot_id", ""));
                    } catch (Exception e) { cb.onError(cleanError(e)); }
                }).start();
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private Models.SpotifyPlaylist parsePlaylist(JSONObject o) {
        Models.SpotifyPlaylist p = new Models.SpotifyPlaylist();
        p.id = o.optString("id");
        p.name = o.optString("name", "Untitled playlist");
        p.snapshotId = o.optString("snapshot_id");
        JSONObject owner = o.optJSONObject("owner");
        if (owner != null) p.owner = owner.optString("display_name", owner.optString("id", ""));
        JSONArray images = o.optJSONArray("images");
        if (images != null && images.length() > 0) {
            JSONObject img = images.optJSONObject(0);
            if (img != null) p.artworkUrl = img.optString("url");
        }
        JSONObject itemInfo = o.optJSONObject("items");
        if (itemInfo == null) itemInfo = o.optJSONObject("tracks");
        if (itemInfo != null) p.totalItems = itemInfo.optInt("total", 0);
        return p;
    }

    private Models.SpotifyTrack parseTrack(JSONObject item) {
        String type = item.optString("type", "track");
        if (!"track".equals(type) && !type.isEmpty()) return null;
        Models.SpotifyTrack t = new Models.SpotifyTrack();
        t.id = item.optString("id");
        t.title = item.optString("name", "Unknown track");
        t.spotifyUri = item.optString("uri");
        JSONObject external = item.optJSONObject("external_urls");
        if (external != null) t.spotifyUrl = external.optString("spotify");
        JSONArray artists = item.optJSONArray("artists");
        if (artists != null && artists.length() > 0) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < artists.length(); i++) {
                JSONObject a = artists.optJSONObject(i);
                if (a == null) continue;
                if (b.length() > 0) b.append(", ");
                b.append(a.optString("name"));
            }
            if (b.length() > 0) t.artist = b.toString();
        }
        JSONObject album = item.optJSONObject("album");
        if (album != null) {
            t.album = album.optString("name");
            JSONArray images = album.optJSONArray("images");
            if (images != null && images.length() > 0) {
                JSONObject img = images.optJSONObject(0);
                if (img != null) t.artworkUrl = img.optString("url");
            }
        }
        return t;
    }

    private void saveTokenResponse(JSONObject json) throws Exception {
        if (json.has("error")) throw new Exception(json.optString("error_description", json.optString("error")));
        String access = json.optString("access_token");
        if (access.isEmpty()) throw new Exception("Spotify did not return an access token.");
        String refresh = json.optString("refresh_token", "");
        long expiry = System.currentTimeMillis() + Math.max(60, json.optLong("expires_in", 3600)) * 1000L;
        store.saveTokens(access, refresh, expiry);
    }

    private String bearer() { return "Bearer " + store.accessToken(); }

    private JSONObject requestJson(String method, String url, String authorization, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "application/json");
        if (authorization != null && !authorization.isEmpty()) c.setRequestProperty("Authorization", authorization);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        int code = c.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String text = readAll(stream);
        if (code < 200 || code >= 300) {
            String message = "Spotify error " + code;
            try {
                JSONObject err = new JSONObject(text);
                JSONObject e = err.optJSONObject("error");
                if (e != null) message += ": " + e.optString("message", e.toString());
                else message += ": " + err.optString("error_description", err.optString("error", text));
            } catch (Exception ignored) { if (!text.isEmpty()) message += ": " + text; }
            throw new Exception(message);
        }
        return text.isEmpty() ? new JSONObject() : new JSONObject(text);
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) b.append(line);
        }
        return b.toString();
    }

    private static String form(String... pairs) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (b.length() > 0) b.append('&');
            b.append(Uri.encode(pairs[i])).append('=').append(Uri.encode(pairs[i + 1]));
        }
        return b.toString();
    }

    private static String encPath(String v) { return Uri.encode(v); }
    private static byte[] sha256(byte[] value) throws Exception { return MessageDigest.getInstance("SHA-256").digest(value); }
    private static String base64Url(byte[] bytes) { return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING); }
    private static String randomBase64Url(int bytes) { byte[] data = new byte[bytes]; new SecureRandom().nextBytes(data); return base64Url(data); }
    private static String cleanError(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
