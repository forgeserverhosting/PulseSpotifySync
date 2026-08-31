package com.example.pulse;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
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
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int AUDIO_PERMISSION = 401;
    private static final int PICK_PLAYLIST = 402;

    private static final int BG = Color.rgb(7, 8, 12);
    private static final int CARD = Color.rgb(18, 20, 28);
    private static final int CARD_2 = Color.rgb(25, 27, 37);
    private static final int TEXT = Color.rgb(248, 248, 252);
    private static final int MUTED = Color.rgb(143, 147, 160);
    private static final int ACCENT = Color.rgb(139, 92, 246);
    private static final int GOOD = Color.rgb(45, 212, 191);

    private final Handler handler = new Handler();
    private final List<Models.LocalTrack> allLocalTracks = new ArrayList<>();
    private final List<Models.LocalTrack> visibleLocalTracks = new ArrayList<>();
    private final List<Models.ImportedPlaylist> importedPlaylists = new ArrayList<>();

    private AppStore store;
    private LinearLayout root;
    private FrameLayout page;
    private LinearLayout miniPlayer;
    private TextView miniTitle;
    private TextView miniArtist;
    private FrameLayout miniArtwork;
    private Button miniPrev;
    private Button miniPlay;
    private Button miniNext;
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
        buildShell();
        requestAudioAndLoad();
        handleSharedIntent(getIntent());
        showHome();
        handler.post(progressTick);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSharedIntent(intent);
    }

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

        TextView version = pill("v0.5", ACCENT, Color.WHITE);
        header.addView(version, new LinearLayout.LayoutParams(dp(58), dp(30)));
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
        miniPlayer.setPadding(dp(12), dp(8), dp(12), dp(6));
        miniPlayer.setBackground(round(CARD, 18));
        miniPlayer.setVisibility(View.GONE);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        miniArtwork = artworkBox("♪", 46);
        row.addView(miniArtwork, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setPadding(dp(11), 0, dp(8), 0);
        miniTitle = text("Nothing playing", 14, TEXT, true);
        miniTitle.setSingleLine(true);
        miniTitle.setEllipsize(TextUtils.TruncateAt.END);
        miniArtist = text("", 12, MUTED, false);
        miniArtist.setSingleLine(true);
        miniArtist.setEllipsize(TextUtils.TruncateAt.END);
        meta.addView(miniTitle);
        meta.addView(miniArtist);
        row.addView(meta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        meta.setOnClickListener(v -> showNowPlaying());
        miniArtwork.setOnClickListener(v -> showNowPlaying());

        miniPrev = button("‹", CARD_2, TEXT);
        miniPlay = button("▶", ACCENT, Color.WHITE);
        miniNext = button("›", CARD_2, TEXT);
        row.addView(miniPrev, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(dp(46), dp(42));
        playLp.setMargins(dp(5), 0, dp(5), 0);
        row.addView(miniPlay, playLp);
        row.addView(miniNext, new LinearLayout.LayoutParams(dp(42), dp(42)));
        miniPlayer.addView(row);

        miniSeek = new SeekBar(this);
        miniSeek.setPadding(0, 0, 0, 0);
        if (Build.VERSION.SDK_INT >= 21) {
            miniSeek.setProgressTintList(ColorStateList.valueOf(ACCENT));
            miniSeek.setThumbTintList(ColorStateList.valueOf(ACCENT));
        }
        miniPlayer.addView(miniSeek, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));

        miniPrev.setOnClickListener(v -> playPreviousLocal());
        miniPlay.setOnClickListener(v -> togglePlay());
        miniNext.setOnClickListener(v -> playNextLocal());
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
        bottomNav.setPadding(dp(10), dp(5), dp(10), dp(7));
        bottomNav.setBackgroundColor(Color.rgb(10, 11, 16));
        addNav("⌂\nHome", "home");
        addNav("♫\nLibrary", "library");
        addNav("⇩\nImport", "import");
        addNav("⌕\nSearch", "search");
        root.addView(bottomNav, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(70)));
    }

    private void addNav(String label, String key) {
        TextView t = text(label, 11, MUTED, true);
        t.setGravity(Gravity.CENTER);
        t.setTag(key);
        t.setOnClickListener(v -> {
            if ("home".equals(key)) showHome();
            else if ("library".equals(key)) showLibrary();
            else if ("import".equals(key)) showImport();
            else showSearch();
        });
        bottomNav.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
    }

    private void updateNavColors() {
        if (bottomNav == null) return;
        for (int i = 0; i < bottomNav.getChildCount(); i++) {
            View v = bottomNav.getChildAt(i);
            if (v instanceof TextView) ((TextView) v).setTextColor(currentPage.equals(v.getTag()) ? TEXT : MUTED);
        }
    }

    private ScrollView newPage(String title, String subtitle) {
        page.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(dp(20), dp(8), dp(20), dp(28));
        TextView h = text(title, 29, TEXT, true);
        b.addView(h);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(subtitle, 13, MUTED, false);
            sub.setPadding(0, dp(4), 0, dp(16));
            b.addView(sub);
        }
        scroll.addView(b, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.setTag(b);
        page.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        updateNavColors();
        return scroll;
    }

    private LinearLayout body(ScrollView scroll) { return (LinearLayout) scroll.getTag(); }

    private void showHome() {
        currentPage = "home";
        ScrollView scroll = newPage("Your music", "Local playback first. Playlist import without account setup.");
        LinearLayout b = body(scroll);

        LinearLayout hero = card();
        hero.addView(text("PULSE v0.5", 11, ACCENT, true));
        TextView heroTitle = text(importedPlaylists.isEmpty() ? "Bring your playlists home." : importedPlaylists.size() + " playlist" + (importedPlaylists.size() == 1 ? "" : "s") + " in Pulse", 23, TEXT, true);
        heroTitle.setPadding(0, dp(6), 0, dp(7));
        hero.addView(heroTitle);
        TextView desc = text(importedPlaylists.isEmpty()
                ? "Import M3U, CSV or JSON playlist files. Pulse remembers the source file so you can refresh it later."
                : "Imported playlists stay searchable and Pulse matches their songs to playable files already on your phone.", 14, MUTED, false);
        hero.addView(desc);
        Button action = button(importedPlaylists.isEmpty() ? "Import a playlist" : "Open imported playlists", ACCENT, TEXT);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        ap.setMargins(0, dp(14), 0, 0);
        hero.addView(action, ap);
        action.setOnClickListener(v -> showImport());
        b.addView(hero, cardParams());

        sectionTitle(b, "Recently available");
        if (visibleLocalTracks.isEmpty()) {
            b.addView(emptyCard("No music found yet", store.showAllAudio() ? "No playable audio is visible." : "Music mode hides voice notes, recordings and very short clips. Switch to All audio in Library if needed."), cardParams());
        } else {
            int max = Math.min(6, visibleLocalTracks.size());
            for (int i = 0; i < max; i++) b.addView(localTrackRow(visibleLocalTracks.get(i), i));
        }

        sectionTitle(b, "Imported playlists");
        if (importedPlaylists.isEmpty()) {
            b.addView(emptyCard("Nothing imported yet", "M3U, M3U8, CSV and JSON playlist files will show here."), cardParams());
        } else {
            for (Models.ImportedPlaylist p : importedPlaylists) b.addView(importedPlaylistCard(p));
        }
    }

    private void showLibrary() {
        currentPage = "library";
        applyAudioFilter();
        ScrollView scroll = newPage("Library", visibleLocalTracks.size() + (store.showAllAudio() ? " playable audio files" : " music tracks"));
        LinearLayout b = body(scroll);

        LinearLayout filter = new LinearLayout(this);
        filter.setOrientation(LinearLayout.HORIZONTAL);
        Button music = button("Music", store.showAllAudio() ? CARD_2 : ACCENT, TEXT);
        Button all = button("All audio", store.showAllAudio() ? ACCENT : CARD_2, TEXT);
        filter.addView(music, new LinearLayout.LayoutParams(0, dp(46), 1f));
        LinearLayout.LayoutParams allLp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        allLp.setMargins(dp(8), 0, 0, 0);
        filter.addView(all, allLp);
        b.addView(filter, buttonParams());
        music.setOnClickListener(v -> { store.setShowAllAudio(false); applyAudioFilter(); showLibrary(); });
        all.setOnClickListener(v -> { store.setShowAllAudio(true); applyAudioFilter(); showLibrary(); });

        if (visibleLocalTracks.isEmpty()) {
            b.addView(emptyCard("No playable audio", "Pulse scans Android's media library for supported audio files."), cardParams());
            Button refresh = button("Refresh library", ACCENT, TEXT);
            refresh.setOnClickListener(v -> requestAudioAndLoad());
            b.addView(refresh, buttonParams());
        } else {
            for (int i = 0; i < visibleLocalTracks.size(); i++) b.addView(localTrackRow(visibleLocalTracks.get(i), i));
        }
    }

    private void showImport() {
        currentPage = "import";
        ScrollView scroll = newPage("Playlist Import", "No developer ID. No Spotify password. No account setup.");
        LinearLayout b = body(scroll);

        LinearLayout importCard = card();
        importCard.addView(text("Import from a file", 21, TEXT, true));
        TextView d = text("Choose an M3U/M3U8, CSV or JSON playlist. Pulse remembers the file and can refresh the playlist from that same source later.", 14, MUTED, false);
        d.setPadding(0, dp(8), 0, dp(14));
        importCard.addView(d);
        Button choose = button("Choose playlist file", ACCENT, TEXT);
        importCard.addView(choose, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        choose.setOnClickListener(v -> pickPlaylistFile());
        b.addView(importCard, cardParams());

        LinearLayout spotifyNote = card();
        spotifyNote.addView(text("Spotify direct sync is parked", 18, TEXT, true));
        TextView note = text("Pulse is not asking users for Spotify developer credentials. If you export a Spotify playlist to M3U, CSV or JSON with a tool you trust, Pulse can import it here. Direct live Spotify account sync can be added later only through Spotify's official app-access route.", 13, MUTED, false);
        note.setPadding(0, dp(7), 0, 0);
        spotifyNote.addView(note);
        b.addView(spotifyNote, cardParams());

        sectionTitle(b, "Imported playlists");
        if (importedPlaylists.isEmpty()) {
            b.addView(emptyCard("Nothing imported yet", "Choose a playlist file above. You can import more than one."), cardParams());
        } else {
            for (Models.ImportedPlaylist p : importedPlaylists) b.addView(importedPlaylistCard(p));
        }
    }

    private void showSearch() {
        currentPage = "search";
        ScrollView scroll = newPage("Search", "Search your music and every imported playlist.");
        LinearLayout b = body(scroll);
        EditText input = new EditText(this);
        input.setHint("Songs, artists, playlists…");
        input.setHintTextColor(MUTED);
        input.setTextColor(TEXT);
        input.setSingleLine(true);
        input.setPadding(dp(16), 0, dp(16), 0);
        input.setBackground(round(CARD, 16));
        b.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setPadding(0, dp(12), 0, 0);
        b.addView(results);
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
        for (int i = 0; i < visibleLocalTracks.size(); i++) {
            Models.LocalTrack t = visibleLocalTracks.get(i);
            if (q.isEmpty() || normalize(cleanTitle(t.title) + " " + cleanArtist(t.artist)).contains(q)) {
                results.addView(localTrackRow(t, i));
                shown++;
                if (shown >= 30) break;
            }
        }
        if (shown < 30 && !q.isEmpty()) {
            for (Models.ImportedPlaylist p : importedPlaylists) {
                if (normalize(p.name).contains(q)) {
                    results.addView(importedPlaylistCard(p));
                    shown++;
                }
                for (Models.ImportedTrack t : p.tracks) {
                    if (shown >= 30) break;
                    if (!normalize(t.title + " " + t.artist + " " + p.name).contains(q)) continue;
                    results.addView(importedTrackRow(t));
                    shown++;
                }
                if (shown >= 30) break;
            }
        }
        if (shown == 0) results.addView(emptyCard("No matches", "Try another title, artist or playlist name."), cardParams());
    }

    private View localTrackRow(Models.LocalTrack track, int index) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(9), dp(8), dp(9));

        FrameLayout art = localArtwork(track, 54);
        row.addView(art, new LinearLayout.LayoutParams(dp(54), dp(54)));

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setPadding(dp(12), 0, dp(8), 0);
        TextView title = text(cleanTitle(track.title), 16, TEXT, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        TextView sub = text(cleanArtist(track.artist) + "  •  " + time(track.durationMs), 12, MUTED, false);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        meta.addView(title);
        meta.addView(sub);
        row.addView(meta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView play = text("▶", 17, TEXT, true);
        play.setGravity(Gravity.CENTER);
        row.addView(play, new LinearLayout.LayoutParams(dp(40), dp(40)));
        row.setOnClickListener(v -> playLocal(index));
        return row;
    }

    private View importedTrackRow(Models.ImportedTrack track) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(9), dp(8), dp(9));
        FrameLayout art = artworkBox(initial(track.title), 50);
        row.addView(art, new LinearLayout.LayoutParams(dp(50), dp(50)));

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setPadding(dp(12), 0, dp(8), 0);
        TextView title = text(track.title, 15, TEXT, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        Models.LocalTrack match = findLocalMatch(track);
        String subText = track.artist + (match != null ? "  •  Playable here" : "  •  Imported metadata");
        TextView sub = text(subText, 12, match != null ? GOOD : MUTED, false);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        meta.addView(title);
        meta.addView(sub);
        row.addView(meta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView action = text(match != null ? "▶" : "↗", 16, TEXT, true);
        action.setGravity(Gravity.CENTER);
        row.addView(action, new LinearLayout.LayoutParams(dp(40), dp(40)));
        row.setOnClickListener(v -> {
            Models.LocalTrack local = findLocalMatch(track);
            if (local != null) playLocal(indexInVisible(local));
            else openSource(track);
        });
        return row;
    }

    private View importedPlaylistCard(Models.ImportedPlaylist p) {
        LinearLayout c = card();
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(artworkBox(initial(p.name), 58), new LinearLayout.LayoutParams(dp(58), dp(58)));
        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setPadding(dp(12), 0, 0, 0);
        TextView name = text(p.name, 17, TEXT, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        int matched = countLocalMatches(p);
        TextView status = text(p.tracks.size() + " tracks  •  " + matched + " playable here", 12, MUTED, false);
        TextView sync = text(p.sourceType + "  •  " + (p.lastSyncedAt > 0 ? "Updated " + friendlyTime(p.lastSyncedAt) : "Imported"), 11, GOOD, false);
        meta.addView(name);
        meta.addView(status);
        meta.addView(sync);
        top.addView(meta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        c.addView(top);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(12), 0, 0);
        Button open = button("Open", CARD_2, TEXT);
        Button refresh = button("Refresh file", ACCENT, TEXT);
        actions.addView(open, new LinearLayout.LayoutParams(0, dp(46), 1f));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        rp.setMargins(dp(8), 0, 0, 0);
        actions.addView(refresh, rp);
        open.setOnClickListener(v -> showPlaylistDetails(p));
        refresh.setOnClickListener(v -> refreshPlaylist(p));
        c.addView(actions);
        return c;
    }

    private void showPlaylistDetails(Models.ImportedPlaylist p) {
        currentPage = "import";
        ScrollView scroll = newPage(p.name, p.tracks.size() + " imported tracks • " + countLocalMatches(p) + " playable locally");
        LinearLayout b = body(scroll);
        Button back = button("← Back", CARD_2, TEXT);
        back.setOnClickListener(v -> showImport());
        b.addView(back, new LinearLayout.LayoutParams(dp(110), dp(46)));
        Button refresh = button("Refresh from source file", ACCENT, TEXT);
        refresh.setOnClickListener(v -> refreshPlaylist(p));
        b.addView(refresh, buttonParams());
        for (Models.ImportedTrack t : p.tracks) b.addView(importedTrackRow(t));
        Button remove = button("Remove playlist", Color.rgb(45, 24, 30), Color.rgb(255, 164, 177));
        remove.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Remove " + p.name + "?")
                .setMessage("This removes the imported playlist from Pulse. It does not delete the source file.")
                .setPositiveButton("Remove", (d, w) -> { importedPlaylists.remove(p); store.saveImportedPlaylists(importedPlaylists); showImport(); })
                .setNegativeButton("Cancel", null)
                .show());
        b.addView(remove, buttonParams());
    }

    private void sectionTitle(LinearLayout b, String value) {
        TextView t = text(value, 18, TEXT, true);
        t.setPadding(dp(2), dp(20), 0, dp(8));
        b.addView(t);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        c.setBackground(round(CARD, 20));
        return c;
    }

    private View emptyCard(String title, String message) {
        LinearLayout c = card();
        c.addView(text(title, 17, TEXT, true));
        TextView m = text(message, 13, MUTED, false);
        m.setPadding(0, dp(6), 0, 0);
        c.addView(m);
        return c;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(6), 0, dp(6));
        return p;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        p.setMargins(0, dp(8), 0, dp(8));
        return p;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView pill(String value, int background, int foreground) {
        TextView t = text(value, 12, foreground, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(background, 30));
        return t;
    }

    private Button button(String value, int background, int foreground) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(foreground);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setBackground(round(background, 15));
        return b;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private FrameLayout artworkBox(String label, int sizeDp) {
        FrameLayout box = new FrameLayout(this);
        box.setBackground(round(CARD_2, 13));
        TextView fallback = text(label, 21, Color.WHITE, true);
        fallback.setGravity(Gravity.CENTER);
        box.addView(fallback, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return box;
    }

    private FrameLayout localArtwork(Models.LocalTrack track, int sizeDp) {
        FrameLayout box = artworkBox(initial(cleanTitle(track.title)), sizeDp);
        if (track.albumId > 0) {
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            try { img.setImageURI(ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), track.albumId)); } catch (Exception ignored) {}
            box.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        return box;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void requestAudioAndLoad() {
        String permission = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{permission}, AUDIO_PERMISSION);
        else scanLocalAudio();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == AUDIO_PERMISSION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) scanLocalAudio();
        else Toast.makeText(this, "Pulse needs Music & audio permission for local playback.", Toast.LENGTH_LONG).show();
    }

    private void scanLocalAudio() {
        allLocalTracks.clear();
        Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        List<String> cols = new ArrayList<>();
        cols.add(MediaStore.Audio.Media._ID);
        cols.add(MediaStore.Audio.Media.TITLE);
        cols.add(MediaStore.Audio.Media.ARTIST);
        cols.add(MediaStore.Audio.Media.DURATION);
        cols.add(MediaStore.Audio.Media.ALBUM_ID);
        cols.add(MediaStore.Audio.Media.DISPLAY_NAME);
        if (Build.VERSION.SDK_INT >= 29) cols.add(MediaStore.Audio.Media.RELATIVE_PATH);

        try (Cursor c = getContentResolver().query(base, cols.toArray(new String[0]), null, null, MediaStore.Audio.Media.TITLE + " COLLATE NOCASE")) {
            if (c != null) {
                int id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                int album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
                int display = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
                int relative = Build.VERSION.SDK_INT >= 29 ? c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH) : -1;
                while (c.moveToNext()) {
                    long durationMs = c.getLong(duration);
                    if (durationMs <= 0) continue;
                    long trackId = c.getLong(id);
                    String relativePath = relative >= 0 ? c.getString(relative) : "";
                    allLocalTracks.add(new Models.LocalTrack(
                            trackId,
                            c.getString(title),
                            c.getString(artist),
                            durationMs,
                            c.getLong(album),
                            ContentUris.withAppendedId(base, trackId),
                            c.getString(display),
                            relativePath));
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "Could not scan local audio: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        applyAudioFilter();
        if ("library".equals(currentPage)) showLibrary();
        else if ("search".equals(currentPage)) showSearch();
        else showHome();
    }

    private void applyAudioFilter() {
        visibleLocalTracks.clear();
        for (Models.LocalTrack t : allLocalTracks) {
            if (store.showAllAudio() || isLikelyMusic(t)) visibleLocalTracks.add(t);
        }
        if (currentLocalIndex >= visibleLocalTracks.size()) currentLocalIndex = -1;
    }

    private boolean isLikelyMusic(Models.LocalTrack t) {
        String hay = (t.displayName + " " + t.relativePath + " " + t.title).toLowerCase(Locale.US);
        String[] blocked = {"whatsapp", "voicemail", "voice mail", "voice note", "recording", "recordings", "call recording", "ringtones", "notifications", "alarms", "textnow"};
        for (String x : blocked) if (hay.contains(x)) return false;
        String name = t.displayName == null ? "" : t.displayName.toLowerCase(Locale.US);
        if (name.startsWith("aud-") || name.startsWith("ptt-")) return false;
        if (t.durationMs < 35_000L) return false;
        if (t.albumId > 0) return true;
        String artist = cleanArtist(t.artist);
        return !"Unknown artist".equals(artist) || t.durationMs >= 75_000L;
    }

    private void playLocal(int index) {
        if (index < 0 || index >= visibleLocalTracks.size()) return;
        releasePlayer();
        currentLocalIndex = index;
        Models.LocalTrack track = visibleLocalTracks.get(index);
        mediaPlayer = MediaPlayer.create(this, track.uri);
        if (mediaPlayer == null) {
            Toast.makeText(this, "Pulse couldn't play that file.", Toast.LENGTH_SHORT).show();
            return;
        }
        mediaPlayer.setOnCompletionListener(mp -> playNextLocal());
        mediaPlayer.start();
        miniPlayer.setVisibility(View.VISIBLE);
        miniTitle.setText(cleanTitle(track.title));
        miniArtist.setText(cleanArtist(track.artist));
        miniPlay.setText("Ⅱ");
        miniSeek.setMax(Math.max(1, mediaPlayer.getDuration()));
        updateMiniArtwork(track);
    }

    private void updateMiniArtwork(Models.LocalTrack track) {
        miniArtwork.removeAllViews();
        TextView fallback = text(initial(cleanTitle(track.title)), 20, Color.WHITE, true);
        fallback.setGravity(Gravity.CENTER);
        miniArtwork.addView(fallback, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (track.albumId > 0) {
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            try { img.setImageURI(ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), track.albumId)); } catch (Exception ignored) {}
            miniArtwork.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    private void togglePlay() {
        if (mediaPlayer == null) {
            if (!visibleLocalTracks.isEmpty()) playLocal(currentLocalIndex >= 0 ? currentLocalIndex : 0);
            return;
        }
        try {
            if (mediaPlayer.isPlaying()) { mediaPlayer.pause(); miniPlay.setText("▶"); }
            else { mediaPlayer.start(); miniPlay.setText("Ⅱ"); }
        } catch (Exception ignored) {}
    }

    private void playPreviousLocal() {
        if (visibleLocalTracks.isEmpty()) return;
        playLocal(currentLocalIndex <= 0 ? visibleLocalTracks.size() - 1 : currentLocalIndex - 1);
    }

    private void playNextLocal() {
        if (visibleLocalTracks.isEmpty()) return;
        playLocal(currentLocalIndex >= visibleLocalTracks.size() - 1 ? 0 : currentLocalIndex + 1);
    }

    private void showNowPlaying() {
        if (currentLocalIndex < 0 || currentLocalIndex >= visibleLocalTracks.size()) return;
        Models.LocalTrack track = visibleLocalTracks.get(currentLocalIndex);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(22), dp(12), dp(22), dp(12));
        FrameLayout art = localArtwork(track, 220);
        LinearLayout.LayoutParams artLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220));
        artLp.setMargins(0, 0, 0, dp(16));
        wrap.addView(art, artLp);
        TextView title = text(cleanTitle(track.title), 22, Color.BLACK, true);
        TextView artist = text(cleanArtist(track.artist), 14, Color.DKGRAY, false);
        wrap.addView(title);
        wrap.addView(artist);
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(14), 0, 0);
        Button prev = new Button(this); prev.setText("PREV");
        Button play = new Button(this); play.setText(mediaPlayer != null && mediaPlayer.isPlaying() ? "PAUSE" : "PLAY");
        Button next = new Button(this); next.setText("NEXT");
        controls.addView(prev, new LinearLayout.LayoutParams(0, dp(50), 1f));
        controls.addView(play, new LinearLayout.LayoutParams(0, dp(50), 1f));
        controls.addView(next, new LinearLayout.LayoutParams(0, dp(50), 1f));
        wrap.addView(controls);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(wrap).setNegativeButton("Close", null).create();
        prev.setOnClickListener(v -> { playPreviousLocal(); dialog.dismiss(); showNowPlaying(); });
        play.setOnClickListener(v -> { togglePlay(); play.setText(mediaPlayer != null && mediaPlayer.isPlaying() ? "PAUSE" : "PLAY"); });
        next.setOnClickListener(v -> { playNextLocal(); dialog.dismiss(); showNowPlaying(); });
        dialog.show();
    }

    private void releasePlayer() {
        if (mediaPlayer != null) {
            try { mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }
    }

    private void pickPlaylistFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/x-mpegurl", "application/vnd.apple.mpegurl", "text/csv", "application/json", "text/plain"});
        startActivityForResult(intent, PICK_PLAYLIST);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_PLAYLIST && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(uri, flags & Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            importPlaylistUri(uri, true);
        }
    }

    private void handleSharedIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri != null) {
            importPlaylistUri(uri, true);
            return;
        }
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text != null && text.contains("open.spotify.com/playlist")) {
            Toast.makeText(this, "Spotify links alone don't expose playlist tracks to Pulse without Spotify API access. Export the playlist to M3U, CSV or JSON and import that file instead.", Toast.LENGTH_LONG).show();
        }
    }

    private void importPlaylistUri(Uri uri, boolean announce) {
        new Thread(() -> {
            try {
                Models.ImportedPlaylist parsed = PlaylistImporter.parse(this, uri);
                runOnUiThread(() -> {
                    Models.ImportedPlaylist old = findImported(parsed.id);
                    if (old != null) importedPlaylists.remove(old);
                    importedPlaylists.add(parsed);
                    store.saveImportedPlaylists(importedPlaylists);
                    if (announce) Toast.makeText(this, parsed.name + " imported • " + parsed.tracks.size() + " tracks", Toast.LENGTH_LONG).show();
                    showImport();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Couldn't import that playlist: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void refreshPlaylist(Models.ImportedPlaylist playlist) {
        if (playlist.sourceUri == null || playlist.sourceUri.isEmpty()) {
            Toast.makeText(this, "This playlist no longer has a source file.", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Refreshing " + playlist.name + "…", Toast.LENGTH_SHORT).show();
        importPlaylistUri(Uri.parse(playlist.sourceUri), true);
    }

    private Models.ImportedPlaylist findImported(String id) {
        for (Models.ImportedPlaylist p : importedPlaylists) if (p.id.equals(id)) return p;
        return null;
    }

    private int countLocalMatches(Models.ImportedPlaylist p) {
        int n = 0;
        for (Models.ImportedTrack t : p.tracks) if (findLocalMatch(t) != null) n++;
        return n;
    }

    private Models.LocalTrack findLocalMatch(Models.ImportedTrack imported) {
        String title = normalize(imported.title);
        String artist = normalize(imported.artist);
        if (title.isEmpty()) return null;
        for (Models.LocalTrack l : allLocalTracks) {
            String lt = normalize(cleanTitle(l.title));
            if (!(lt.equals(title) || lt.contains(title) || title.contains(lt))) continue;
            String la = normalize(cleanArtist(l.artist));
            if (artist.isEmpty() || "unknown artist".equals(artist) || la.isEmpty() || "unknown artist".equals(la) || la.contains(artist) || artist.contains(la)) return l;
        }
        return null;
    }

    private int indexInVisible(Models.LocalTrack track) {
        int i = visibleLocalTracks.indexOf(track);
        if (i >= 0) return i;
        if (!store.showAllAudio()) {
            store.setShowAllAudio(true);
            applyAudioFilter();
            i = visibleLocalTracks.indexOf(track);
        }
        return i;
    }

    private void openSource(Models.ImportedTrack t) {
        if (t.sourceUrl == null || t.sourceUrl.trim().isEmpty()) {
            Toast.makeText(this, "No local match or source link is available for this track.", Toast.LENGTH_SHORT).show();
            return;
        }
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(t.sourceUrl))); }
        catch (Exception e) { Toast.makeText(this, "Couldn't open the source link.", Toast.LENGTH_SHORT).show(); }
    }

    private String cleanTitle(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "Unknown track";
        String s = raw.trim().replace('_', ' ');
        String lower = s.toLowerCase(Locale.US);
        for (String ext : new String[]{".mp3", ".m4a", ".flac", ".wav", ".ogg", ".aac"}) {
            if (lower.endsWith(ext)) { s = s.substring(0, s.length() - ext.length()); break; }
        }
        if (s.matches("(?i)^AUD-\\d{8}-WA\\d+.*")) {
            String digits = s.replaceAll("(?i)^AUD-(\\d{4})(\\d{2})(\\d{2})-WA.*", "$1-$2-$3");
            return "Audio " + digits;
        }
        if (s.startsWith("[") && s.contains("]]")) {
            int end = s.lastIndexOf("]]" );
            if (end >= 0 && end + 2 < s.length()) {
                String candidate = s.substring(end + 2).trim();
                if (!candidate.isEmpty()) s = candidate;
            }
        }
        return s.trim().isEmpty() ? "Unknown track" : s.trim();
    }

    private String cleanArtist(String raw) {
        return raw == null || raw.trim().isEmpty() || "<unknown>".equalsIgnoreCase(raw.trim()) ? "Unknown artist" : raw.trim();
    }

    private String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private String time(long ms) {
        long sec = Math.max(0, ms / 1000);
        return (sec / 60) + ":" + String.format(Locale.US, "%02d", sec % 60);
    }

    private String friendlyTime(long when) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(when);
    }

    private String initial(String value) {
        if (value == null) return "♪";
        String s = value.trim();
        if (s.isEmpty()) return "♪";
        return s.substring(0, 1).toUpperCase(Locale.US);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(progressTick);
        releasePlayer();
        super.onDestroy();
    }
}
