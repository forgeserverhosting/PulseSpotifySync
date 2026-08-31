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
        prefs = context.getSharedPreferences("pulse_store_v2", Context.MODE_PRIVATE);
    }

    boolean showAllAudio() { return prefs.getBoolean("show_all_audio", false); }
    void setShowAllAudio(boolean value) { prefs.edit().putBoolean("show_all_audio", value).apply(); }

    void saveImportedPlaylists(List<Models.ImportedPlaylist> playlists) {
        JSONArray a = new JSONArray();
        for (Models.ImportedPlaylist p : playlists) {
            try { a.put(p.toJson()); } catch (Exception ignored) {}
        }
        prefs.edit().putString("imported_playlists", a.toString()).apply();
    }

    List<Models.ImportedPlaylist> loadImportedPlaylists() {
        List<Models.ImportedPlaylist> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("imported_playlists", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) out.add(Models.ImportedPlaylist.fromJson(o));
            }
        } catch (Exception ignored) {}
        return out;
    }
}
