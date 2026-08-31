package com.example.pulse;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class PlaylistImporter {
    private PlaylistImporter() {}

    static Models.ImportedPlaylist parse(Context context, Uri uri) throws Exception {
        String displayName = displayName(context, uri);
        String lower = displayName.toLowerCase(Locale.US);
        String text = readText(context, uri);

        Models.ImportedPlaylist p;
        if (lower.endsWith(".m3u") || lower.endsWith(".m3u8")) p = parseM3u(text);
        else if (lower.endsWith(".csv")) p = parseCsv(text);
        else if (lower.endsWith(".json")) p = parseJson(text);
        else {
            String trimmed = text.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) p = parseJson(text);
            else if (trimmed.startsWith("#EXTM3U") || trimmed.contains("#EXTINF")) p = parseM3u(text);
            else p = parseCsv(text);
        }

        if (p.name == null || p.name.trim().isEmpty() || "Untitled playlist".equals(p.name)) {
            p.name = stripExtension(displayName);
        }
        p.sourceUri = uri.toString();
        p.sourceType = typeFromName(displayName);
        p.lastSyncedAt = System.currentTimeMillis();
        p.id = sha1(uri.toString());
        return p;
    }

    private static Models.ImportedPlaylist parseM3u(String text) {
        Models.ImportedPlaylist p = new Models.ImportedPlaylist();
        p.sourceType = "M3U";
        String pending = null;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#PLAYLIST:")) {
                p.name = line.substring("#PLAYLIST:".length()).trim();
                continue;
            }
            if (line.startsWith("#EXTINF:")) {
                int comma = line.indexOf(',');
                pending = comma >= 0 ? line.substring(comma + 1).trim() : "";
                continue;
            }
            if (line.startsWith("#")) continue;

            Models.ImportedTrack t = new Models.ImportedTrack();
            t.sourceUrl = line;
            String label = pending == null || pending.isEmpty() ? fileLabel(line) : pending;
            pending = null;
            splitArtistTitle(label, t);
            p.tracks.add(t);
        }
        return p;
    }

    private static Models.ImportedPlaylist parseCsv(String text) {
        Models.ImportedPlaylist p = new Models.ImportedPlaylist();
        p.sourceType = "CSV";
        List<List<String>> rows = csvRows(text);
        if (rows.isEmpty()) return p;

        List<String> header = rows.get(0);
        int title = findHeader(header, "title", "track", "track name", "name");
        int artist = findHeader(header, "artist", "artists", "artist name");
        int album = findHeader(header, "album", "album name");
        int url = findHeader(header, "url", "spotify url", "track url", "uri", "link");
        boolean hasHeader = title >= 0 || artist >= 0 || album >= 0 || url >= 0;
        int start = hasHeader ? 1 : 0;
        if (!hasHeader) { title = 0; artist = header.size() > 1 ? 1 : -1; }

        for (int i = start; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            Models.ImportedTrack t = new Models.ImportedTrack();
            t.title = value(r, title, "Unknown track");
            t.artist = value(r, artist, "Unknown artist");
            t.album = value(r, album, "");
            t.sourceUrl = value(r, url, "");
            if (!t.title.trim().isEmpty()) p.tracks.add(t);
        }
        return p;
    }

    private static Models.ImportedPlaylist parseJson(String text) throws Exception {
        Models.ImportedPlaylist p = new Models.ImportedPlaylist();
        p.sourceType = "JSON";
        String trimmed = text.trim();
        JSONArray tracks;
        if (trimmed.startsWith("[")) {
            tracks = new JSONArray(trimmed);
        } else {
            JSONObject root = new JSONObject(trimmed);
            p.name = first(root, "name", "playlistName", "title");
            tracks = root.optJSONArray("tracks");
            if (tracks == null) tracks = root.optJSONArray("items");
            if (tracks == null) tracks = root.optJSONArray("entries");
            if (tracks == null) tracks = new JSONArray();
        }

        for (int i = 0; i < tracks.length(); i++) {
            Object item = tracks.opt(i);
            if (item instanceof String) {
                Models.ImportedTrack t = new Models.ImportedTrack();
                splitArtistTitle((String) item, t);
                p.tracks.add(t);
                continue;
            }
            JSONObject o = tracks.optJSONObject(i);
            if (o == null) continue;
            JSONObject nested = o.optJSONObject("track");
            if (nested == null) nested = o.optJSONObject("item");
            if (nested != null) o = nested;
            Models.ImportedTrack t = new Models.ImportedTrack();
            t.title = first(o, "title", "name", "trackName");
            if (t.title.isEmpty()) t.title = "Unknown track";
            t.artist = artistFromJson(o);
            t.album = albumFromJson(o);
            t.sourceUrl = urlFromJson(o);
            p.tracks.add(t);
        }
        return p;
    }

    private static String artistFromJson(JSONObject o) {
        String direct = first(o, "artist", "artistName");
        if (!direct.isEmpty()) return direct;
        JSONArray artists = o.optJSONArray("artists");
        if (artists == null || artists.length() == 0) return "Unknown artist";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < artists.length(); i++) {
            Object item = artists.opt(i);
            String name = "";
            if (item instanceof String) name = (String) item;
            else if (item instanceof JSONObject) name = ((JSONObject) item).optString("name");
            if (!name.isEmpty()) { if (b.length() > 0) b.append(", "); b.append(name); }
        }
        return b.length() == 0 ? "Unknown artist" : b.toString();
    }

    private static String albumFromJson(JSONObject o) {
        Object album = o.opt("album");
        if (album instanceof String) return (String) album;
        if (album instanceof JSONObject) return ((JSONObject) album).optString("name");
        return "";
    }

    private static String urlFromJson(JSONObject o) {
        String direct = first(o, "url", "spotifyUrl", "uri", "link");
        if (!direct.isEmpty()) return direct;
        JSONObject ex = o.optJSONObject("external_urls");
        return ex == null ? "" : ex.optString("spotify");
    }

    private static String first(JSONObject o, String... keys) {
        for (String k : keys) {
            String value = o.optString(k, "");
            if (!value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static List<List<String>> csvRows(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quote = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                if (quote && i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else quote = !quote;
            } else if (c == ',' && !quote) {
                row.add(cell.toString().trim()); cell.setLength(0);
            } else if ((c == '\n' || c == '\r') && !quote) {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString().trim()); cell.setLength(0);
                if (!(row.size() == 1 && row.get(0).isEmpty())) rows.add(row);
                row = new ArrayList<>();
            } else cell.append(c);
        }
        row.add(cell.toString().trim());
        if (!(row.size() == 1 && row.get(0).isEmpty())) rows.add(row);
        return rows;
    }

    private static int findHeader(List<String> header, String... names) {
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i).toLowerCase(Locale.US).trim();
            for (String n : names) if (h.equals(n)) return i;
        }
        return -1;
    }

    private static String value(List<String> row, int index, String fallback) {
        return index >= 0 && index < row.size() && !row.get(index).trim().isEmpty() ? row.get(index).trim() : fallback;
    }

    private static void splitArtistTitle(String label, Models.ImportedTrack t) {
        String s = label == null ? "" : label.trim();
        int split = s.indexOf(" - ");
        if (split > 0) {
            t.artist = s.substring(0, split).trim();
            t.title = s.substring(split + 3).trim();
        } else {
            t.title = s.isEmpty() ? "Unknown track" : s;
            t.artist = "Unknown artist";
        }
    }

    private static String readText(Context context, Uri uri) throws Exception {
        StringBuilder b = new StringBuilder();
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) b.append(line).append('\n');
        }
        return b.toString();
    }

    private static String displayName(Context context, Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {}
        String last = uri.getLastPathSegment();
        return last == null ? "Imported playlist" : last;
    }

    private static String fileLabel(String value) {
        String s = value == null ? "" : value;
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < s.length()) s = s.substring(slash + 1);
        return stripExtension(s).replace('_', ' ').trim();
    }

    private static String stripExtension(String s) {
        if (s == null) return "Imported playlist";
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }

    private static String typeFromName(String s) {
        String lower = s.toLowerCase(Locale.US);
        if (lower.endsWith(".m3u") || lower.endsWith(".m3u8")) return "M3U";
        if (lower.endsWith(".csv")) return "CSV";
        if (lower.endsWith(".json")) return "JSON";
        return "File";
    }

    private static String sha1(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : bytes) b.append(String.format(Locale.US, "%02x", x));
            return b.toString();
        } catch (Exception e) { return Integer.toHexString(value.hashCode()); }
    }
}
