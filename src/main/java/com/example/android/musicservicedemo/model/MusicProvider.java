/*
 * Copyright (C) 2014 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.musicservicedemo.model;

import android.media.MediaMetadata;
import android.os.AsyncTask;
import android.util.Log;

import com.example.android.musicservicedemo.utils.LogHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Utility class to get a list of MusicTrack's based on a server-side JSON
 * configuration.
 */
public class MusicProvider {

    private static final String TAG = "MusicProvider";

    //private static final String CATALOG_URL = "http://storage.googleapis.com/automotive-media/music.json";
    private static final String CATALOG_URL = "https://api.myjson.com/bins/uoyd5";
    public static final String CUSTOM_METADATA_TRACK_SOURCE = "__SOURCE__";

    private static String JSON_MUSIC = "music";
    private static String JSON_TITLE = "title";
    private static String JSON_ALBUM = "album";
    private static String JSON_ARTIST = "artist";
    private static String JSON_GENRE = "genre";
    private static String JSON_SOURCE = "source";
    private static String JSON_IMAGE = "image";
    private static String JSON_TRACK_NUMBER = "trackNumber";
    private static String JSON_TOTAL_TRACK_COUNT = "totalTrackCount";
    private static String JSON_DURATION = "duration";

    private final ReentrantLock initializationLock = new ReentrantLock();

    // Categorized caches for music track data:
    private final HashMap<String, List<MediaMetadata>> mMusicListByGenre;
    private final HashMap<String, MediaMetadata> mMusicListById;

    private final HashSet<String> mFavoriteTracks;

    enum State {
        NON_INITIALIZED, INITIALIZING, INITIALIZED;
    }

    private State mCurrentState = State.NON_INITIALIZED;


    public interface Callback {
        void onMusicCatalogReady(boolean success);
    }

    public MusicProvider() {
        mMusicListByGenre = new HashMap<>();
        mMusicListById = new HashMap<>();
        mFavoriteTracks = new HashSet<>();
    }

    /**
     * Get an iterator over the list of genres
     *
     * @return
     */
    public Iterable<String> getGenres() {
        if (mCurrentState != State.INITIALIZED) {
            return new ArrayList<>(0);
        }
        return mMusicListByGenre.keySet();
    }

    /**
     * Get music tracks of the given genre
     *
     * @return
     */
    public Iterable<MediaMetadata> getMusicsByGenre(String genre) {
        if (mCurrentState != State.INITIALIZED || !mMusicListByGenre.containsKey(genre)) {
            return new ArrayList<MediaMetadata>();
        }
        return mMusicListByGenre.get(genre);
    }

    /**
     * Very basic implementation of a search that filter music tracks which title containing
     * the given query.
     *
     * @return
     */
    public Iterable<MediaMetadata> searchMusics(String titleQuery) {
        ArrayList<MediaMetadata> result = new ArrayList<>();
        if (mCurrentState != State.INITIALIZED) {
            return result;
        }
        titleQuery = titleQuery.toLowerCase();
        for (MediaMetadata track: mMusicListById.values()) {
            if (track.getString(MediaMetadata.METADATA_KEY_TITLE).toLowerCase()
                    .contains(titleQuery)) {
                result.add(track);
            }
        }
        return result;
    }

    public MediaMetadata getMusic(String mediaId) {
        Log.wtf(TAG, "getMusic");
        return mMusicListById.get(mediaId);
    }

    public void setFavorite(String mediaId, boolean favorite) {
        if (favorite) {
            mFavoriteTracks.add(mediaId);
        } else {
            mFavoriteTracks.remove(mediaId);
        }
    }

    public boolean isFavorite(String musicId) {
        return mFavoriteTracks.contains(musicId);
    }

    public boolean isInitialized() {
        return mCurrentState == State.INITIALIZED;
    }

    /**
     * Get the list of music tracks from a server and caches the track information
     * for future reference, keying tracks by mediaId and grouping by genre.
     *
     * @return
     */
    public void retrieveMedia(final Callback callback) {

        if (mCurrentState == State.INITIALIZED) {
            // Nothing to do, execute callback immediately
            callback.onMusicCatalogReady(true);
            return;
        }

        // Asynchronously load the music catalog in a separate thread
        new AsyncTask<Object, Object, Object>() {
            @Override
            protected Object doInBackground(Object[] objects) {
                retrieveMediaAsync(callback);
                return null;
            }
        }.execute();
    }

    private void retrieveMediaAsync(Callback callback) {
        Log.wtf(TAG, "retrieveMediaAsync");
        initializationLock.lock();

        try {
            if (mCurrentState == State.NON_INITIALIZED) {
                Log.wtf(TAG, "NON_INITIALIZED");
                mCurrentState = State.INITIALIZING;

                int slashPos = CATALOG_URL.lastIndexOf('/');
                String path = CATALOG_URL.substring(0, slashPos + 1);
                JSONObject jsonObj = parseUrl(CATALOG_URL);
                Log.wtf(TAG, jsonObj.toString());

                JSONArray tracks = jsonObj.getJSONArray(JSON_MUSIC);

                if (tracks != null) {
                    Log.wtf(TAG, "tracks");
                    for (int j = 0; j < tracks.length(); j++) {
                        MediaMetadata item = buildFromJSON(tracks.getJSONObject(j), path);

                        String genre = item.getString(MediaMetadata.METADATA_KEY_GENRE);
                        Log.wtf(TAG, genre);
                        List<MediaMetadata> list = mMusicListByGenre.get(genre);
                        if (list == null) {
                            list = new ArrayList<>();
                        }
                        list.add(item);
                        mMusicListByGenre.put(genre, list);
                        mMusicListById.put(item.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                                item);
                    }
                }
                mCurrentState = State.INITIALIZED;
                Log.wtf(TAG, ""+ mCurrentState);
            }
        } catch (RuntimeException | JSONException e) {
            Log.wtf(TAG, "Could not retrieve music list");
        } finally {
            if (mCurrentState != State.INITIALIZED) {
                // Something bad happened, so we reset state to NON_INITIALIZED to allow
                // retries (eg if the network connection is temporary unavailable)
                mCurrentState = State.NON_INITIALIZED;
                Log.wtf(TAG, "NON_INITIALIZED");
            }
            initializationLock.unlock();
            if (callback != null) {
                Log.wtf(TAG, "callback");
                callback.onMusicCatalogReady(mCurrentState == State.INITIALIZED);
            }
        }
    }

    private MediaMetadata buildFromJSON(JSONObject json, String basePath) throws JSONException {
        Log.wtf(TAG, "buildFromJSON");
        String title = json.getString(JSON_TITLE);
        String album = json.getString(JSON_ALBUM);
        String artist = json.getString(JSON_ARTIST);
        String genre = json.getString(JSON_GENRE);
        String source = json.getString(JSON_SOURCE);
        String iconUrl = json.getString(JSON_IMAGE);
        int trackNumber = json.getInt(JSON_TRACK_NUMBER);
        int totalTrackCount = json.getInt(JSON_TOTAL_TRACK_COUNT);
        int duration = json.getInt(JSON_DURATION) * 1000; // ms

        Log.wtf(TAG, "Found music track: "+ json);

        // Media is stored relative to JSON file
        if (!source.startsWith("http")) {
            source = basePath + source;
        }
        if (!iconUrl.startsWith("http")) {
            iconUrl = basePath + iconUrl;
        }
        // Since we don't have a unique ID in the server, we fake one using the hashcode of
        // the music source. In a real world app, this could come from the server.
        String id = String.valueOf(source.hashCode());

        // Adding the music source to the MediaMetadata (and consequently using it in the
        // mediaSession.setMetadata) is not a good idea for a real world music app, because
        // the session metadata can be accessed by notification listeners. This is done in this
        // sample for convenience only.
        return new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, id)
                .putString(CUSTOM_METADATA_TRACK_SOURCE, source)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                .putString(MediaMetadata.METADATA_KEY_GENRE, genre)
                .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, iconUrl)
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, trackNumber)
                .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, totalTrackCount)
                .build();
    }

    /**
     * Download a JSON file from a server, parse the content and return the JSON
     * object.
     *
     * @param urlString
     * @return JSONObject
     */
    private JSONObject parseUrl(String urlString) throws JSONException {
        Log.wtf(TAG, "parseUrl");

//        //InputStream is = null;
//        try {
//            java.net.URL url = new java.net.URL(urlString);
//            Log.wtf(TAG, "parseUrl1");
//            URLConnection urlConnection = url.openConnection();
//            Log.wtf(TAG, "parseUrl2");
//            Log.wtf(TAG, "parseUrl2 "+urlConnection.toString());
//            //is = new BufferedInputStream(urlConnection.getInputStream());
//            Log.wtf(TAG, "parseUrl3");
//            BufferedReader reader = new BufferedReader(new InputStreamReader(urlConnection.getInputStream(), "iso-8859-1"), 8);
//            Log.wtf(TAG, "parseUrl4");
//            StringBuilder sb = new StringBuilder();
//            Log.wtf(TAG, "parseUrl5");
//            String line = null;
//            while ((line = reader.readLine()) != null) {
//                Log.wtf(TAG, "line: "+line);
//                sb.append(line);
//            }
//            return new JSONObject(sb.toString());
//        } catch (Exception e) {
//            Log.wtf(TAG, "Failed to parse the json for media list", e);
//            return null;
//        } finally {
//            if (is != null) {
//                try {
//                    is.close();
//                } catch (IOException e) {
//                    // ignore
//                }
//            }
        //}
String s = "{\n" +
        "  \"music\": [\n" +
        "    {\n" +
        "      \"title\": \"Jorge Chavez International Airport\n\",\n" +
        "      \"album\": \"Latin America\",\n" +
        "      \"artist\": \"SPJC\",\n" +
        "      \"genre\": \"ATC - Live\",\n" +
        "      \"source\": \"http://bos.liveatc.net/spjc2\",\n" +
        "      \"image\": \"https://www.fertur-travel.com/blog/wp-content/uploads/2013/04/Lima-Jorge-Ch%C3%A1vez-International-Airport.jpg\",\n" +
        "      \"trackNumber\": 0,\n" +
        "      \"totalTrackCount\": 0,\n" +
        "      \"duration\": 0\n" +
        "    },\n" +
        "    {\n" +
        "      \"title\": \"Benito Juarez International Airport\",\n" +
        "      \"album\": \"Latin America\",\n" +
        "      \"artist\": \"MMMX\",\n" +
        "      \"genre\": \"ATC - Live\",\n" +
        "      \"source\": \"http://d.liveatc.net/mmmx1\",\n" +
        "      \"image\": \"https://cdn.lopezdoriga.com/wp-content/uploads/2017/02/Aeropuerto.jpg\",\n" +
        "      \"trackNumber\": 0,\n" +
        "      \"totalTrackCount\": 0,\n" +
        "      \"duration\": 0\n" +
        "    },\n" +
        "    {\n" +
        "      \"title\": \"Mariano Escboedo International Airport\",\n" +
        "      \"album\": \"Latin America\",\n" +
        "      \"artist\": \"MMMY\",\n" +
        "      \"genre\": \"ATC - Live\",\n" +
        "      \"source\": \"http://d.liveatc.net/mmmy\",\n" +
        "      \"image\": \"myAlbum\",\n" +
        "      \"trackNumber\": 0,\n" +
        "      \"totalTrackCount\": 0,\n" +
        "      \"duration\": 0\n" +
        "    }\n" +
        "  ]\n" +
        "}";
        return new JSONObject(s);
    }
}