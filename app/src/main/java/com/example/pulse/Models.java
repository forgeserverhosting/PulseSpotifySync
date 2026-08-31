package com.example.pulse;

import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

final class Models {
    private Models() {}

    static final class LocalTrack {
        final long id;
        final String title;
        final String artist;
        final long durationMs;
        final long albumId;
        final Uri uri;

        LocalTrack(long id, String title, String artist, long durationMs, long albumId, Uri uri) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.durationMs = durationMs;
            this.albumId = albumId;
            this.uri = uri;
        }
    }

    static final class SpotifyTrack {
        String id = "";
        String title = "Unknown track";
        String artist = "Unknown artist";
        String album = "";
        String artworkUrl = "";
        String spotifyUrl = "";
        String spotifyUri = "";

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("title", title);
            o.put("artist", artist);
            o.put("album", album);
            o.put("artworkUrl", artworkUrl);
            o.put("spotifyUrl", spotifyUrl);
            o.put("spotifyUri", spotifyUri);
            return o;
        }

        static SpotifyTrack fromJson(JSONObject o) {
            SpotifyTrack t = new SpotifyTrack();
            t.id = o.optString("id");
            t.title = o.optString("title", "Unknown track");
            t.artist = o.optString("artist", "Unknown artist");
            t.album = o.optString("album");
            t.artworkUrl = o.optString("artworkUrl");
            t.spotifyUrl = o.optString("spotifyUrl");
            t.spotifyUri = o.optString("spotifyUri");
            return t;
        }
    }

    static final class SpotifyPlaylist {
        String id = "";
        String name = "Untitled playlist";
        String owner = "";
        String snapshotId = "";
        String artworkUrl = "";
        int totalItems = 0;
        boolean imported = false;
        long lastSyncedAt = 0L;
        final List<SpotifyTrack> tracks = new ArrayList<>();

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name);
            o.put("owner", owner);
            o.put("snapshotId", snapshotId);
            o.put("artworkUrl", artworkUrl);
            o.put("totalItems", totalItems);
            o.put("imported", imported);
            o.put("lastSyncedAt", lastSyncedAt);
            JSONArray a = new JSONArray();
            for (SpotifyTrack t : tracks) a.put(t.toJson());
            o.put("tracks", a);
            return o;
        }

        static SpotifyPlaylist fromJson(JSONObject o) {
            SpotifyPlaylist p = new SpotifyPlaylist();
            p.id = o.optString("id");
            p.name = o.optString("name", "Untitled playlist");
            p.owner = o.optString("owner");
            p.snapshotId = o.optString("snapshotId");
            p.artworkUrl = o.optString("artworkUrl");
            p.totalItems = o.optInt("totalItems", 0);
            p.imported = o.optBoolean("imported", true);
            p.lastSyncedAt = o.optLong("lastSyncedAt", 0L);
            JSONArray a = o.optJSONArray("tracks");
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject t = a.optJSONObject(i);
                    if (t != null) p.tracks.add(SpotifyTrack.fromJson(t));
                }
            }
            return p;
        }
    }
}
