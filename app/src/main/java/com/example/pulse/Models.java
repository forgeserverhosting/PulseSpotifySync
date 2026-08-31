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
        final String displayName;
        final String relativePath;

        LocalTrack(long id, String title, String artist, long durationMs, long albumId, Uri uri,
                   String displayName, String relativePath) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.durationMs = durationMs;
            this.albumId = albumId;
            this.uri = uri;
            this.displayName = displayName == null ? "" : displayName;
            this.relativePath = relativePath == null ? "" : relativePath;
        }
    }

    static final class ImportedTrack {
        String title = "Unknown track";
        String artist = "Unknown artist";
        String album = "";
        String sourceUrl = "";

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("title", title);
            o.put("artist", artist);
            o.put("album", album);
            o.put("sourceUrl", sourceUrl);
            return o;
        }

        static ImportedTrack fromJson(JSONObject o) {
            ImportedTrack t = new ImportedTrack();
            t.title = o.optString("title", "Unknown track");
            t.artist = o.optString("artist", "Unknown artist");
            t.album = o.optString("album");
            t.sourceUrl = o.optString("sourceUrl");
            return t;
        }
    }

    static final class ImportedPlaylist {
        String id = "";
        String name = "Untitled playlist";
        String sourceUri = "";
        String sourceType = "File";
        long lastSyncedAt = 0L;
        final List<ImportedTrack> tracks = new ArrayList<>();

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name);
            o.put("sourceUri", sourceUri);
            o.put("sourceType", sourceType);
            o.put("lastSyncedAt", lastSyncedAt);
            JSONArray a = new JSONArray();
            for (ImportedTrack t : tracks) a.put(t.toJson());
            o.put("tracks", a);
            return o;
        }

        static ImportedPlaylist fromJson(JSONObject o) {
            ImportedPlaylist p = new ImportedPlaylist();
            p.id = o.optString("id");
            p.name = o.optString("name", "Untitled playlist");
            p.sourceUri = o.optString("sourceUri");
            p.sourceType = o.optString("sourceType", "File");
            p.lastSyncedAt = o.optLong("lastSyncedAt", 0L);
            JSONArray a = o.optJSONArray("tracks");
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject t = a.optJSONObject(i);
                    if (t != null) p.tracks.add(ImportedTrack.fromJson(t));
                }
            }
            return p;
        }
    }
}
