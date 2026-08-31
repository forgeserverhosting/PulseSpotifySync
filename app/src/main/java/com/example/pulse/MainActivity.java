package com.example.pulse;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int AUDIO_PERMISSION = 401;
    private static final int BG = Color.rgb(7, 8, 12);
    private static final int CARD = Color.rgb(18, 20, 28);
    private static final int CARD_2 = Color.rgb(25, 27, 37);
    private static final int TEXT = Color.rgb(248, 248, 252);
    private static final int MUTED = Color.rgb(143, 147, 160);
    private static final int ACCENT = Color.rgb(139, 92, 246);
    private static final int SPOTIFY = Color.rgb(30, 215, 96);

    private final Handler handler = new Handler();
    private final ExecutorService imagePool = Executors.newFixedThreadPool(3);
    private final Map<String, Bitmap> bitmapCache = new HashMap<>();
    private final List<Models.LocalTrack> localTracks = new ArrayList<>();
    private final List<Models.SpotifyPlaylist> importedPlaylists = new ArrayList<>();
    private final List<Models.SpotifyPlaylist> spotifyPlaylists = new ArrayList<>();

    private AppStore store;
    private SpotifyClient spotify;

    private LinearLayout root;
    private FrameLayout page;
    private LinearLayout miniPlayer;
    private TextView miniTitle;
    private TextView miniArtist;
    private Button miniPlay;
    private SeekBar miniSeek;
    private LinearLayout bottomNav;

    private MediaPlayer mediaPlayer;
    private int currentLocalIndex = -1;
    private String currentPage = "home";
    private long lastSeekUpdate = 0L;

    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            try {
                if (mediaPlayer != null && miniSeek != null) {
                    int duration = mediaPlayer.getDuration();
                    int position = mediaPlayer.getCurrentPosition();
                    miniSeek.setMax(Math.max(1, duration));
                    if (System.currentTimeMillis() - lastSeekUpdate > 700) miniSeek.setProgress(position);
                }
            } catch (Exception ignored) {}
            handler.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
        store = new AppStore(this);
        importedPlaylists.addAll(store.loadImportedPlaylists());
        spotify = new SpotifyClient(store, resolvedClientId());
        buildShell();
        requestAudioAndLoad();
        handleSpotifyCallback(getIntent());
        showHome();
        handler.post(progressTick);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSpotifyCallback(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        if (spotify != null && spotify.configured() && spotify.hasSession() && !importedPlaylists.isEmpty()) {
            long now = System.currentTimeMillis();
            if (now - store.lastAutoSync() > 60_000L) {
                store.setLastAutoSync(now);
                syncAll(false);
            }
        }
    }

    private String resolvedClientId() {
        String built = BuildConfig.SPOTIFY_CLIENT_ID == null ? "" : BuildConfig.SPOTIFY_CLIENT_ID.trim();
        return built.isEmpty() ? store.developerClientId() : built;
    }

    private void rebuildSpotifyClient() { spotify = new SpotifyClient(store, resolvedClientId()); }

    private void buildShell() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top, 0, bottom);
            return insets;
        });

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(14), dp(18), dp(10));

        TextView logo = text("PULSE", 28, TEXT, true);
        logo.setLetterSpacing(0.08f);
        header.addView(logo, new LinearLayout.LayoutParams(0, dp(48), 1f));

        TextView version = pill("v0.4", ACCENT, Color.WHITE);
        header.addView(version, new LinearLayout.LayoutParams(dp(58), dp(30)));
        logo.setOnLongClickListener(v -> { showDeveloperSetup(); return true; });
        version.setOnLongClickListener(v -> { showDeveloperSetup(); return true; });
        root.addView(header);

        page = new FrameLayout(this);
        root.addView(page, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        buildMiniPlayer();
        buildBottomNav();
        setContentView(root);
        root.requestApplyInsets();
    }

    private void buildMiniPlayer() {
        miniPlayer = new LinearLayout(this);
        miniPlayer.setOrientation(LinearLayout.VERTICAL);
        miniPlayer.setPadding(dp(14), dp(10), dp(14), dp(8));
        miniPlayer.setBackground(round(CARD, 18));
        miniPlayer.setVisibility(View.GONE);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView art = text("♪", 23, Color.WHITE, true);
        art.setGravity(Gravity.CENTER);
        art.setBackground(round(ACCENT, 12));
        row.addView(art, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setPadding(dp(12), 0, dp(8), 0);
        miniTitle = text("Nothing playing", 15, TEXT, true);
        miniTitle.setSingleLine(true); miniTitle.setEllipsize(TextUtils.TruncateAt.END);
        miniArtist = text("", 12, MUTED, false);
        miniArtist.setSingleLine(true); miniArtist.setEllipsize(TextUtils.TruncateAt.END);
        meta.addView(miniTitle);
        meta.addView(miniArtist);
        row.addView(meta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        miniPlay = button("▶", CARD_2, TEXT);
        row.addView(miniPlay, new LinearLayout.LayoutParams(dp(52), dp(48)));
        miniPlayer.addView(row);

        miniSeek = new SeekBar(this);
        if (Build.VERSION.SDK_INT >= 21) {
            miniSeek.setProgressTintList(ColorStateList.valueOf(ACCENT));
            miniSeek.setThumbTintList(ColorStateList.valueOf(ACCENT));
        }
        miniPlayer.addView(miniSeek, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));
        miniPlay.setOnClickListener(v -> togglePlay());
        miniSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { if (fromUser) lastSeekUpdate = System.currentTimeMillis(); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { lastSeekUpdate = System.currentTimeMillis(); }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                lastSeekUpdate = System.currentTimeMillis();
                try { if (mediaPlayer != null) mediaPlayer.seekTo(seekBar.getProgress()); } catch (Exception ignored) {}
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(12), dp(4), dp(12), dp(6));
        root.addView(miniPlayer, lp);
    }

    private void buildBottomNav() {
        bottomNav = new LinearLayout(this);
        bottomNav.setOrientation(LinearLayout.HORIZONTAL);
        bottomNav.setPadding(dp(10), dp(6), dp(10), dp(8));
        bottomNav.setBackgroundColor(Color.rgb(10, 11, 16));
        addNav("⌂\nHome", "home");
        addNav("♫\nLibrary", "library");
        addNav("⇩\nImport", "import");
        addNav("⌕\nSearch", "search");
        root.addView(bottomNav, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(70)));
    }

    private void addNav(String label, String key) {
        TextView v = text(label, 12, MUTED, true);
        v.setGravity(Gravity.CENTER);
        v.setOnClickListener(x -> {
            currentPage = key;
            if ("home".equals(key)) showHome();
            else if ("library".equals(key)) showLibrary();
            else if ("import".equals(key)) showImport();
            else showSearch();
            updateNavColors();
        });
        v.setTag(key);
        bottomNav.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
    }

    private void updateNavColors() {
        for (int i = 0; i < bottomNav.getChildCount(); i++) {
            TextView t = (TextView) bottomNav.getChildAt(i);
            t.setTextColor(currentPage.equals(t.getTag()) ? Color.WHITE : MUTED);
        }
    }

    private ScrollView newPage(String title, String subtitle) {
        page.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(8), dp(18), dp(24));
        TextView h = text(title, 30, TEXT, true);
        h.setLetterSpacing(-0.02f);
        body.addView(h);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = text(subtitle, 14, MUTED, false);
            s.setPadding(0, dp(3), 0, dp(18));
            body.addView(s);
        }
        scroll.addView(body);
        scroll.setTag(body);
        page.addView(scroll);
        updateNavColors();
        return scroll;
    }

    private LinearLayout body(ScrollView scroll) { return (LinearLayout) scroll.getTag(); }

    private void showHome() {
        currentPage = "home";
        ScrollView scroll = newPage("Your music", "Pulse keeps playback local and Spotify focused on playlist transfer.");
        LinearLayout b = body(scroll);

        LinearLayout hero = card();
        TextView eyebrow = text(spotify.hasSession() ? "SPOTIFY CONNECTED" : "PULSE v0.4", 11, spotify.hasSession() ? SPOTIFY : ACCENT, true);
        hero.addView(eyebrow);
        TextView heroTitle = text(importedPlaylists.isEmpty() ? "Bring your playlists home." : importedPlaylists.size() + " Spotify playlist" + (importedPlaylists.size() == 1 ? "" : "s") + " in Pulse", 23, TEXT, true);
        heroTitle.setPadding(0, dp(6), 0, dp(7));
        hero.addView(heroTitle);
        TextView desc = text(importedPlaylists.isEmpty() ? "Connect Spotify once, choose what to import, then Pulse remembers the mapping and checks for playlist changes." : "Sync status is saved per playlist. Local matches play directly inside Pulse.", 14, MUTED, false);
        hero.addView(desc);
        Button action = button(importedPlaylists.isEmpty() ? "Open Spotify Import" : "Sync imported playlists", importedPlaylists.isEmpty() ? ACCENT : CARD_2, TEXT);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)); ap.setMargins(0, dp(14), 0, 0);
        hero.addView(action, ap);
        action.setOnClickListener(v -> { if (importedPlaylists.isEmpty()) { currentPage = "import"; showImport(); } else syncAll(true); });
        b.addView(hero, cardParams());

        sectionTitle(b, "Recently available");
        if (localTracks.isEmpty()) {
            b.addView(emptyCard("No local music found yet", "Allow Music & audio access or add audio files to your phone."), cardParams());
        } else {
            int max = Math.min(6, localTracks.size());
            for (int i = 0; i < max; i++) b.addView(localTrackRow(localTracks.get(i), i));
        }

        sectionTitle(b, "Imported playlists");
        if (importedPlaylists.isEmpty()) {
            b.addView(emptyCard("Nothing imported yet", "Your owned or collaborative Spotify playlists will show here after import."), cardParams());
        } else {
            for (Models.SpotifyPlaylist p : importedPlaylists) b.addView(importedPlaylistCard(p));
        }
    }

    private void showLibrary() {
        currentPage = "library";
        ScrollView scroll = newPage("Library", localTracks.size() + " playable local tracks");
        LinearLayout b = body(scroll);
        if (localTracks.isEmpty()) {
            b.addView(emptyCard("No playable audio", "Pulse scans Android's media library for MP3, M4A, FLAC and other supported audio."), cardParams());
            Button refresh = button("Refresh library", ACCENT, TEXT); refresh.setOnClickListener(v -> requestAudioAndLoad());
            b.addView(refresh, buttonParams());
        } else {
            for (int i = 0; i < localTracks.size(); i++) b.addView(localTrackRow(localTracks.get(i), i));
        }
    }

    private void showImport() {
        currentPage = "import";
        ScrollView scroll = newPage("Spotify Import", "Spotify is the source. Pulse stores the playlist mapping and sync state.");
        LinearLayout b = body(scroll);

        if (!spotify.configured()) {
            LinearLayout c = card();
            TextView x = text("Spotify activation needed", 21, TEXT, true); c.addView(x);
            TextView d = text("The sync engine is built, but Spotify requires Pulse to have a registered developer Client ID. Normal users never enter this. Once a Premium-owned Pulse developer app exists, the credential can be built in and this button becomes the normal Spotify consent flow.", 14, MUTED, false);
            d.setPadding(0, dp(8), 0, dp(14)); c.addView(d);
            Button connect = button("Connect Spotify", SPOTIFY, Color.BLACK); c.addView(connect, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
            connect.setOnClickListener(v -> showNotConfiguredDialog());
            b.addView(c, cardParams());
            return;
        }

        if (!spotify.hasSession()) {
            LinearLayout c = card();
            TextView x = text("Connect Spotify", 22, TEXT, true); c.addView(x);
            TextView d = text("Pulse opens Spotify's own authorization page. Your Spotify password never enters Pulse. We request playlist read access only.", 14, MUTED, false);
            d.setPadding(0, dp(8), 0, dp(14)); c.addView(d);
            Button connect = button("Continue with Spotify", SPOTIFY, Color.BLACK);
            c.addView(connect, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
            connect.setOnClickListener(v -> beginSpotifyLogin());
            b.addView(c, cardParams());
            return;
        }

        LinearLayout tools = new LinearLayout(this); tools.setOrientation(LinearLayout.HORIZONTAL);
        Button load = button(spotifyPlaylists.isEmpty() ? "Load playlists" : "Refresh", CARD_2, TEXT);
        Button sync = button("Sync All", ACCENT, TEXT);
        tools.addView(load, new LinearLayout.LayoutParams(0, dp(50), 1f));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(50), 1f); sp.setMargins(dp(8), 0, 0, 0); tools.addView(sync, sp);
        b.addView(tools, buttonParams());
        load.setOnClickListener(v -> loadSpotifyPlaylists(true));
        sync.setOnClickListener(v -> syncAll(true));

        TextView connection = text("Connected • " + importedPlaylists.size() + " imported", 13, SPOTIFY, true);
        connection.setPadding(dp(2), dp(6), 0, dp(8)); b.addView(connection);

        if (spotifyPlaylists.isEmpty()) {
            b.addView(emptyCard("Ready to import", "Tap Load playlists to retrieve the playlists available to your Spotify account."), cardParams());
            loadSpotifyPlaylists(false);
        } else {
            Button importAll = button("Import all available", SPOTIFY, Color.BLACK);
            importAll.setOnClickListener(v -> importAllAvailable());
            b.addView(importAll, buttonParams());
            for (Models.SpotifyPlaylist p : spotifyPlaylists) b.addView(spotifyPlaylistCard(p));
        }

        Button disconnect = button("Disconnect Spotify", Color.rgb(45, 24, 30), Color.rgb(255, 164, 177));
        disconnect.setOnClickListener(v -> {
            store.clearSpotifySession(); rebuildSpotifyClient(); spotifyPlaylists.clear();
            Toast.makeText(this, "Spotify disconnected. Imported playlists stay in Pulse.", Toast.LENGTH_SHORT).show(); showImport();
        });
        b.addView(disconnect, buttonParams());
    }

    private void showSearch() {
        currentPage = "search";
        ScrollView scroll = newPage("Search", "Search your local library and imported Spotify metadata.");
        LinearLayout b = body(scroll);
        EditText input = new EditText(this);
        input.setHint("Songs, artists, playlists…");
        input.setHintTextColor(MUTED); input.setTextColor(TEXT); input.setSingleLine(true);
        input.setPadding(dp(16), 0, dp(16), 0); input.setBackground(round(CARD, 16));
        b.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        LinearLayout results = new LinearLayout(this); results.setOrientation(LinearLayout.VERTICAL); results.setPadding(0, dp(12), 0, 0); b.addView(results);
        renderSearchResults(results, "");
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int before, int count) { renderSearchResults(results, s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void renderSearchResults(LinearLayout results, String query) {
        results.removeAllViews();
        String q = normalize(query);
        int shown = 0;
        for (int i = 0; i < localTracks.size(); i++) {
            Models.LocalTrack t = localTracks.get(i);
            if (q.isEmpty() || normalize(t.title + " " + t.artist).contains(q)) {
                results.addView(localTrackRow(t, i)); shown++; if (shown >= 30) break;
            }
        }
        if (shown < 30) {
            for (Models.SpotifyPlaylist p : importedPlaylists) {
                if (!q.isEmpty() && normalize(p.name).contains(q)) { results.addView(importedPlaylistCard(p)); shown++; }
                for (Models.SpotifyTrack t : p.tracks) {
                    if (shown >= 30) break;
                    if (!q.isEmpty() && !normalize(t.title + " " + t.artist + " " + p.name).contains(q)) continue;
                    results.addView(spotifyTrackRow(t)); shown++;
                }
                if (shown >= 30) break;
            }
        }
        if (shown == 0) results.addView(emptyCard("No matches", "Try another title, artist or playlist name."), cardParams());
    }

    private View localTrackRow(Models.LocalTrack track, int index) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(8), dp(9), dp(8), dp(9));
        ImageView art = artworkView();
        if (track.albumId > 0) {
            try { art.setImageURI(ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), track.albumId)); } catch (Exception ignored) {}
        }
        row.addView(art, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout meta = new LinearLayout(this); meta.setOrientation(LinearLayout.VERTICAL); meta.setPadding(dp(12), 0, dp(8), 0);
        TextView title = text(cleanTitle(track.title), 16, TEXT, true); title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
        TextView sub = text(cleanArtist(track.artist) + "  •  " + time(track.durationMs), 12, MUTED, false); sub.setSingleLine(true); sub.setEllipsize(TextUtils.TruncateAt.END);
        meta.addView(title); meta.addView(sub); row.addView(meta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView play = text("▶", 17, TEXT, true); play.setGravity(Gravity.CENTER); row.addView(play, new LinearLayout.LayoutParams(dp(40), dp(40)));
        row.setOnClickListener(v -> playLocal(index));
        return row;
    }

    private View spotifyTrackRow(Models.SpotifyTrack track) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(8), dp(9), dp(8), dp(9));
        ImageView art = artworkView(); loadRemoteArtwork(track.artworkUrl, art); row.addView(art, new LinearLayout.LayoutParams(dp(54), dp(54)));
        Models.LocalTrack match = findLocalMatch(track);
        LinearLayout meta = new LinearLayout(this); meta.setOrientation(LinearLayout.VERTICAL); meta.setPadding(dp(12), 0, dp(8), 0);
        TextView title = text(track.title, 16, TEXT, true); title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
        TextView sub = text(track.artist + "  •  " + (match != null ? "On device" : "Spotify metadata"), 12, match != null ? SPOTIFY : MUTED, false); sub.setSingleLine(true); sub.setEllipsize(TextUtils.TruncateAt.END);
        meta.addView(title); meta.addView(sub); row.addView(meta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView action = text(match != null ? "▶" : "↗", 17, TEXT, true); action.setGravity(Gravity.CENTER); row.addView(action, new LinearLayout.LayoutParams(dp(40), dp(40)));
        row.setOnClickListener(v -> {
            Models.LocalTrack local = findLocalMatch(track);
            if (local != null) playLocal(localTracks.indexOf(local)); else openSpotifyTrack(track);
        });
        return row;
    }

    private View spotifyPlaylistCard(Models.SpotifyPlaylist p) {
        LinearLayout c = card();
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView art = artworkView(); loadRemoteArtwork(p.artworkUrl, art); row.addView(art, new LinearLayout.LayoutParams(dp(68), dp(68)));
        LinearLayout meta = new LinearLayout(this); meta.setOrientation(LinearLayout.VERTICAL); meta.setPadding(dp(13), 0, 0, 0);
        TextView name = text(p.name, 17, TEXT, true); name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END);
        TextView sub = text((p.totalItems > 0 ? p.totalItems + " items" : "Spotify playlist") + (p.owner.isEmpty() ? "" : "  •  " + p.owner), 12, MUTED, false);
        meta.addView(name); meta.addView(sub); row.addView(meta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); c.addView(row);
        boolean already = findImported(p.id) != null;
        Button importBtn = button(already ? "Sync this playlist" : "Import to Pulse", already ? CARD_2 : SPOTIFY, already ? TEXT : Color.BLACK);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)); ip.setMargins(0, dp(12), 0, 0); c.addView(importBtn, ip);
        importBtn.setOnClickListener(v -> importPlaylist(p, true));
        return c;
    }

    private View importedPlaylistCard(Models.SpotifyPlaylist p) {
        LinearLayout c = card();
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView art = artworkView(); loadRemoteArtwork(p.artworkUrl, art); row.addView(art, new LinearLayout.LayoutParams(dp(70), dp(70)));
        LinearLayout meta = new LinearLayout(this); meta.setOrientation(LinearLayout.VERTICAL); meta.setPadding(dp(13), 0, 0, 0);
        TextView name = text(p.name, 17, TEXT, true); name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END);
        int matched = countLocalMatches(p);
        String status = p.tracks.size() + " tracks  •  " + matched + " playable here";
        TextView sub = text(status, 12, matched > 0 ? SPOTIFY : MUTED, false);
        TextView synced = text(p.lastSyncedAt == 0 ? "Not synced yet" : "Synced " + friendlyTime(p.lastSyncedAt), 11, MUTED, false);
        meta.addView(name); meta.addView(sub); meta.addView(synced); row.addView(meta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); c.addView(row);
        c.setOnClickListener(v -> showPlaylistDetails(p));
        return c;
    }

    private void showPlaylistDetails(Models.SpotifyPlaylist p) {
        page.removeAllViews();
        ScrollView s = new ScrollView(this); LinearLayout b = new LinearLayout(this); b.setOrientation(LinearLayout.VERTICAL); b.setPadding(dp(18), dp(8), dp(18), dp(24)); s.addView(b); page.addView(s);
        Button back = button("← Back", CARD_2, TEXT); back.setOnClickListener(v -> showHome()); b.addView(back, new LinearLayout.LayoutParams(dp(110), dp(46)));
        TextView title = text(p.name, 29, TEXT, true); title.setPadding(0, dp(18), 0, dp(3)); b.addView(title);
        TextView sub = text(p.tracks.size() + " tracks • " + countLocalMatches(p) + " matched on this phone", 13, MUTED, false); sub.setPadding(0, 0, 0, dp(12)); b.addView(sub);
        Button sync = button("Sync now", ACCENT, TEXT); sync.setOnClickListener(v -> importPlaylist(p, true)); b.addView(sync, buttonParams());
        for (Models.SpotifyTrack t : p.tracks) b.addView(spotifyTrackRow(t));
    }

    private void sectionTitle(LinearLayout b, String value) {
        TextView t = text(value, 18, TEXT, true); t.setPadding(dp(2), dp(22), 0, dp(8)); b.addView(t);
    }

    private LinearLayout card() { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(16), dp(16), dp(16), dp(16)); c.setBackground(round(CARD, 20)); return c; }
    private View emptyCard(String title, String message) { LinearLayout c = card(); c.addView(text(title, 17, TEXT, true)); TextView m = text(message, 13, MUTED, false); m.setPadding(0, dp(6), 0, 0); c.addView(m); return c; }
    private LinearLayout.LayoutParams cardParams() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); p.setMargins(0, dp(6), 0, dp(6)); return p; }
    private LinearLayout.LayoutParams buttonParams() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)); p.setMargins(0, dp(8), 0, dp(8)); return p; }

    private TextView text(String value, int sp, int color, boolean bold) { TextView t = new TextView(this); t.setText(value); t.setTextSize(sp); t.setTextColor(color); if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t; }
    private TextView pill(String value, int background, int foreground) { TextView t = text(value, 12, foreground, true); t.setGravity(Gravity.CENTER); t.setBackground(round(background, 30)); return t; }
    private Button button(String value, int background, int foreground) { Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setTextSize(14); b.setTextColor(foreground); b.setTypeface(Typeface.DEFAULT_BOLD); b.setPadding(dp(12), 0, dp(12), 0); b.setBackground(round(background, 15)); return b; }
    private GradientDrawable round(int color, int radiusDp) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radiusDp)); return d; }
    private ImageView artworkView() { ImageView v = new ImageView(this); v.setScaleType(ImageView.ScaleType.CENTER_INSIDE); v.setBackground(round(CARD_2, 12)); v.setClipToOutline(true); return v; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void requestAudioAndLoad() {
        String permission = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{permission}, AUDIO_PERMISSION);
        else scanLocalMusic();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == AUDIO_PERMISSION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) scanLocalMusic();
        else Toast.makeText(this, "Pulse needs Music & audio permission for local playback.", Toast.LENGTH_LONG).show();
    }

    private void scanLocalMusic() {
        localTracks.clear();
        Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] columns = { MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM_ID };
        try (Cursor c = getContentResolver().query(base, columns, MediaStore.Audio.Media.IS_MUSIC + "!=0", null, MediaStore.Audio.Media.TITLE + " COLLATE NOCASE")) {
            if (c != null) {
                int id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                int albumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
                while (c.moveToNext()) {
                    long mediaId = c.getLong(id);
                    localTracks.add(new Models.LocalTrack(mediaId, c.getString(title), c.getString(artist), c.getLong(duration), c.getLong(albumId), ContentUris.withAppendedId(base, mediaId)));
                }
            }
        } catch (Exception e) { Toast.makeText(this, "Could not scan local music: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
        if ("library".equals(currentPage)) showLibrary(); else if ("search".equals(currentPage)) showSearch(); else showHome();
    }

    private void playLocal(int index) {
        if (index < 0 || index >= localTracks.size()) return;
        releasePlayer();
        currentLocalIndex = index;
        Models.LocalTrack t = localTracks.get(index);
        mediaPlayer = MediaPlayer.create(this, t.uri);
        if (mediaPlayer == null) { Toast.makeText(this, "Pulse couldn't play that file.", Toast.LENGTH_SHORT).show(); return; }
        mediaPlayer.setOnCompletionListener(mp -> playNextLocal());
        mediaPlayer.start();
        miniPlayer.setVisibility(View.VISIBLE);
        miniTitle.setText(cleanTitle(t.title)); miniArtist.setText(cleanArtist(t.artist)); miniPlay.setText("Ⅱ");
        miniSeek.setMax(Math.max(1, mediaPlayer.getDuration()));
    }

    private void togglePlay() {
        if (mediaPlayer == null) { if (!localTracks.isEmpty()) playLocal(currentLocalIndex >= 0 ? currentLocalIndex : 0); return; }
        try { if (mediaPlayer.isPlaying()) { mediaPlayer.pause(); miniPlay.setText("▶"); } else { mediaPlayer.start(); miniPlay.setText("Ⅱ"); } } catch (Exception ignored) {}
    }

    private void playNextLocal() { if (localTracks.isEmpty()) return; playLocal(currentLocalIndex >= localTracks.size() - 1 ? 0 : currentLocalIndex + 1); }
    private void releasePlayer() { if (mediaPlayer != null) { try { mediaPlayer.release(); } catch (Exception ignored) {} mediaPlayer = null; } }

    private void beginSpotifyLogin() {
        if (!spotify.configured()) { showNotConfiguredDialog(); return; }
        try { startActivity(new Intent(Intent.ACTION_VIEW, spotify.buildAuthorizeUri())); }
        catch (Exception e) { Toast.makeText(this, "Spotify login error: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private void handleSpotifyCallback(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        Uri data = intent.getData();
        if (!"pulse-auth".equals(data.getScheme()) || !"callback".equals(data.getHost())) return;
        String error = data.getQueryParameter("error");
        String state = data.getQueryParameter("state");
        String expected = store.oauthState();
        if (error != null) { Toast.makeText(this, "Spotify authorization: " + error, Toast.LENGTH_LONG).show(); store.clearPkce(); return; }
        if (expected == null || expected.isEmpty() || state == null || !expected.equals(state)) { Toast.makeText(this, "Spotify security check failed. Connect again.", Toast.LENGTH_LONG).show(); store.clearPkce(); return; }
        String code = data.getQueryParameter("code");
        if (code == null || code.isEmpty()) return;
        Toast.makeText(this, "Finishing Spotify connection…", Toast.LENGTH_SHORT).show();
        spotify.exchangeCode(code, new SpotifyClient.TokenCallback() {
            @Override public void onSuccess() { runOnUiThread(() -> { Toast.makeText(MainActivity.this, "Spotify connected ✓", Toast.LENGTH_SHORT).show(); currentPage = "import"; showImport(); loadSpotifyPlaylists(true); }); }
            @Override public void onError(String message) { runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show()); }
        });
    }

    private void loadSpotifyPlaylists(boolean toast) {
        if (!spotify.configured() || !spotify.hasSession()) return;
        if (toast) Toast.makeText(this, "Loading Spotify playlists…", Toast.LENGTH_SHORT).show();
        spotify.getCurrentUserPlaylists(new SpotifyClient.PlaylistsCallback() {
            @Override public void onSuccess(List<Models.SpotifyPlaylist> playlists) {
                runOnUiThread(() -> { spotifyPlaylists.clear(); spotifyPlaylists.addAll(playlists); if ("import".equals(currentPage)) showImport(); });
            }
            @Override public void onError(String message) { runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show()); }
        });
    }

    private void importPlaylist(Models.SpotifyPlaylist source, boolean toast) {
        if (toast) Toast.makeText(this, "Syncing " + source.name + "…", Toast.LENGTH_SHORT).show();
        spotify.fetchPlaylist(source.id, new SpotifyClient.PlaylistCallback() {
            @Override public void onSuccess(Models.SpotifyPlaylist p) {
                runOnUiThread(() -> {
                    Models.SpotifyPlaylist old = findImported(p.id);
                    if (old != null) importedPlaylists.remove(old);
                    p.imported = true; importedPlaylists.add(p); store.saveImportedPlaylists(importedPlaylists);
                    Toast.makeText(MainActivity.this, p.name + " synced ✓", Toast.LENGTH_SHORT).show();
                    if ("import".equals(currentPage)) showImport(); else showHome();
                });
            }
            @Override public void onError(String message) { runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show()); }
        });
    }

    private void importAllAvailable() {
        if (spotifyPlaylists.isEmpty()) return;
        Toast.makeText(this, "Importing available playlists…", Toast.LENGTH_SHORT).show();
        importAvailableAt(0, 0, 0);
    }

    private void importAvailableAt(int index, int ok, int failed) {
        if (index >= spotifyPlaylists.size()) {
            runOnUiThread(() -> { store.saveImportedPlaylists(importedPlaylists); Toast.makeText(this, "Import complete: " + ok + " synced" + (failed > 0 ? ", " + failed + " unavailable" : ""), Toast.LENGTH_LONG).show(); showImport(); });
            return;
        }
        Models.SpotifyPlaylist source = spotifyPlaylists.get(index);
        spotify.fetchPlaylist(source.id, new SpotifyClient.PlaylistCallback() {
            @Override public void onSuccess(Models.SpotifyPlaylist p) {
                Models.SpotifyPlaylist old = findImported(p.id); if (old != null) importedPlaylists.remove(old); p.imported = true; importedPlaylists.add(p);
                importAvailableAt(index + 1, ok + 1, failed);
            }
            @Override public void onError(String message) { importAvailableAt(index + 1, ok, failed + 1); }
        });
    }

    private void syncAll(boolean announce) {
        if (importedPlaylists.isEmpty() || !spotify.configured() || !spotify.hasSession()) return;
        if (announce) Toast.makeText(this, "Checking Spotify for changes…", Toast.LENGTH_SHORT).show();
        List<Models.SpotifyPlaylist> copy = new ArrayList<>(importedPlaylists);
        syncAt(copy, 0, 0, 0, announce);
    }

    private void syncAt(List<Models.SpotifyPlaylist> list, int index, int changed, int failed, boolean announce) {
        if (index >= list.size()) {
            store.saveImportedPlaylists(importedPlaylists);
            if (announce) runOnUiThread(() -> Toast.makeText(this, changed == 0 ? "Everything is already synced ✓" : changed + " playlist" + (changed == 1 ? "" : "s") + " updated ✓", Toast.LENGTH_LONG).show());
            runOnUiThread(() -> { if ("home".equals(currentPage)) showHome(); else if ("import".equals(currentPage)) showImport(); });
            return;
        }
        Models.SpotifyPlaylist local = list.get(index);
        spotify.fetchSnapshot(local.id, new SpotifyClient.SnapshotCallback() {
            @Override public void onSuccess(String snapshot) {
                if (!snapshot.isEmpty() && snapshot.equals(local.snapshotId)) { local.lastSyncedAt = System.currentTimeMillis(); syncAt(list, index + 1, changed, failed, announce); return; }
                spotify.fetchPlaylist(local.id, new SpotifyClient.PlaylistCallback() {
                    @Override public void onSuccess(Models.SpotifyPlaylist updated) {
                        Models.SpotifyPlaylist old = findImported(updated.id); if (old != null) importedPlaylists.remove(old); updated.imported = true; importedPlaylists.add(updated);
                        syncAt(list, index + 1, changed + 1, failed, announce);
                    }
                    @Override public void onError(String message) { syncAt(list, index + 1, changed, failed + 1, announce); }
                });
            }
            @Override public void onError(String message) { syncAt(list, index + 1, changed, failed + 1, announce); }
        });
    }

    private Models.SpotifyPlaylist findImported(String id) { for (Models.SpotifyPlaylist p : importedPlaylists) if (p.id.equals(id)) return p; return null; }
    private int countLocalMatches(Models.SpotifyPlaylist p) { int n = 0; for (Models.SpotifyTrack t : p.tracks) if (findLocalMatch(t) != null) n++; return n; }

    private Models.LocalTrack findLocalMatch(Models.SpotifyTrack spotifyTrack) {
        String title = normalize(spotifyTrack.title); String artist = normalize(spotifyTrack.artist);
        if (title.isEmpty()) return null;
        for (Models.LocalTrack l : localTracks) {
            String lt = normalize(cleanTitle(l.title));
            if (!(lt.equals(title) || lt.contains(title) || title.contains(lt))) continue;
            String la = normalize(cleanArtist(l.artist));
            if (artist.isEmpty() || la.isEmpty() || "unknown artist".equals(la) || la.contains(artist) || artist.contains(la)) return l;
        }
        return null;
    }

    private void openSpotifyTrack(Models.SpotifyTrack t) {
        String target = !t.spotifyUri.isEmpty() ? t.spotifyUri : t.spotifyUrl;
        if (target.isEmpty()) { Toast.makeText(this, "No playable local copy is available for this track.", Toast.LENGTH_SHORT).show(); return; }
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target))); }
        catch (Exception e) { Toast.makeText(this, "No local copy found. Spotify link couldn't be opened.", Toast.LENGTH_SHORT).show(); }
    }

    private void loadRemoteArtwork(String url, ImageView target) {
        if (url == null || url.isEmpty()) return;
        synchronized (bitmapCache) { Bitmap cached = bitmapCache.get(url); if (cached != null) { target.setImageBitmap(cached); return; } }
        imagePool.execute(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection(); c.setConnectTimeout(8000); c.setReadTimeout(8000); c.setInstanceFollowRedirects(true);
                try (InputStream in = c.getInputStream()) {
                    Bitmap b = BitmapFactory.decodeStream(in);
                    if (b != null) { synchronized (bitmapCache) { if (bitmapCache.size() > 50) bitmapCache.clear(); bitmapCache.put(url, b); } runOnUiThread(() -> target.setImageBitmap(b)); }
                }
            } catch (Exception ignored) {} finally { if (c != null) c.disconnect(); }
        });
    }

    private void showNotConfiguredDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Spotify sync engine is ready")
                .setMessage("This build still needs Pulse's Spotify developer Client ID before Spotify can open the consent screen. Spotify currently requires the developer-app owner to have Premium.\n\nNormal Pulse users will never see or enter this credential.")
                .setPositiveButton("OK", null)
                .setNeutralButton("Developer setup", (d, w) -> showDeveloperSetup())
                .show();
    }

    private void showDeveloperSetup() {
        EditText input = new EditText(this); input.setHint("Spotify Client ID"); input.setText(store.developerClientId()); input.setSingleLine(true);
        int pad = dp(20); FrameLayout wrap = new FrameLayout(this); wrap.setPadding(pad, dp(4), pad, 0); wrap.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("Pulse developer setup")
                .setMessage("Internal testing only. The production app will have this built in. No client secret is used.")
                .setView(wrap)
                .setPositiveButton("Save", (d, which) -> { store.setDeveloperClientId(input.getText().toString()); store.clearSpotifySession(); rebuildSpotifyClient(); Toast.makeText(this, "Developer Client ID saved.", Toast.LENGTH_SHORT).show(); if ("import".equals(currentPage)) showImport(); })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String cleanTitle(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "Unknown track";
        String s = raw.trim().replace('_', ' ');
        String lower = s.toLowerCase(Locale.US);
        for (String ext : new String[]{".mp3", ".m4a", ".flac", ".wav", ".ogg"}) if (lower.endsWith(ext)) { s = s.substring(0, s.length() - ext.length()); break; }
        if (s.startsWith("[") && s.contains("]]")) {
            int end = s.lastIndexOf("]]" );
            if (end >= 0 && end + 2 < s.length()) {
                String candidate = s.substring(end + 2).trim();
                if (!candidate.isEmpty()) s = candidate;
            }
        }
        return s.trim().isEmpty() ? "Unknown track" : s.trim();
    }

    private String cleanArtist(String raw) { return raw == null || raw.trim().isEmpty() || "<unknown>".equalsIgnoreCase(raw.trim()) ? "Unknown artist" : raw.trim(); }
    private String normalize(String s) { if (s == null) return ""; return s.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", " ").trim(); }
    private String time(long ms) { long sec = Math.max(0, ms / 1000); return (sec / 60) + ":" + String.format(Locale.US, "%02d", sec % 60); }
    private String friendlyTime(long when) { return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(when); }

    @Override protected void onDestroy() {
        handler.removeCallbacks(progressTick);
        releasePlayer();
        imagePool.shutdownNow();
        super.onDestroy();
    }
}
