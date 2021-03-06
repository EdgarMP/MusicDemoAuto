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

package com.example.android.musicservicedemo;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.media.browse.MediaBrowser;
import android.media.browse.MediaBrowser.MediaItem;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.media.MediaBrowserService;
import android.util.Log;

import com.example.android.musicservicedemo.model.MusicProvider;
import com.example.android.musicservicedemo.utils.MediaIDHelper;
import com.example.android.musicservicedemo.utils.QueueHelper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.example.android.musicservicedemo.utils.MediaIDHelper.MEDIA_ID_MUSICS_BY_GENRE;
import static com.example.android.musicservicedemo.utils.MediaIDHelper.MEDIA_ID_ROOT;
import static com.example.android.musicservicedemo.utils.MediaIDHelper.createBrowseCategoryMediaID;
import static com.example.android.musicservicedemo.utils.MediaIDHelper.extractBrowseCategoryFromMediaID;

/**
 * Main entry point for the Android Automobile integration. This class needs to:
 *
 * <ul>
 *
 * <li> Extend {@link android.service.media.MediaBrowserService}, implementing the media browsing
 *      related methods {@link android.service.media.MediaBrowserService#onGetRoot} and
 *      {@link android.service.media.MediaBrowserService#onLoadChildren};
 * <li> Start a new {@link android.media.session.MediaSession} and notify its parent with the
 *      session's token {@link android.service.media.MediaBrowserService#setSessionToken};
 *
 * <li> Set a callback on the
 *      {@link android.media.session.MediaSession#setCallback(android.media.session.MediaSession.Callback)}.
 *      The callback will receive all the user's actions, like play, pause, etc;
 *
 * <li> Handle all the actual music playing using any method your app prefers (for example,
 *      {@link android.media.MediaPlayer})
 *
 * <li> Update playbackState, "now playing" metadata and queue, using MediaSession proper methods
 *      {@link android.media.session.MediaSession#setPlaybackState(android.media.session.PlaybackState)}
 *      {@link android.media.session.MediaSession#setMetadata(android.media.MediaMetadata)} and
 *      {@link android.media.session.MediaSession#setQueue(java.util.List)})
 *
 * <li> Be declared in AndroidManifest as an intent receiver for the action
 *      android.media.browse.MediaBrowserService
 *
 * <li> Declare a meta-data tag in AndroidManifest.xml linking to a xml resource
 *      with a &lt;automotiveApp&gt; root element. For a media app, this must include
 *      an &lt;uses name="media"/&gt; element as a child.
 *      For example, in AndroidManifest.xml:
 *          &lt;meta-data android:name="com.google.android.gms.car.application"
 *              android:resource="@xml/automotive_app_desc"/&gt;
 *      And in res/values/automotive_app_desc.xml:
 *          &lt;automotiveApp&gt;
 *              &lt;uses name="media"/&gt;
 *          &lt;/automotiveApp&gt;
 *
 * </ul>

 * <p>
 * Customization:
 *
 * <li> Add custom actions in the state passed to setPlaybackState(state)
 * <li> Handle custom actions in the MediaSession.Callback.onCustomAction
 * <li> Use UI theme primaryColor to set the player color
 *
 * @see <a href="README.txt">README.txt</a> for more details.
 *
 */

public class MusicService extends MediaBrowserService implements OnPreparedListener,
        OnCompletionListener, OnErrorListener, AudioManager.OnAudioFocusChangeListener {

    private static final String TAG = "MusicService";

    // Action to thumbs up a media item
    private static final String CUSTOM_ACTION_THUMBS_UP = "thumbs_up";

    // The volume we set the media player to when we lose audio focus, but are
    // allowed to reduce the volume instead of stopping playback.
    public static final float VOLUME_DUCK = 0.2f;

    // The volume we set the media player when we have audio focus.
    public static final float VOLUME_NORMAL = 1.0f;
    public static final String ANDROID_AUTO_PACKAGE_NAME = "com.google.android.projection.gearhead";
    public static final String ANDROID_AUTO_EMULATOR_PACKAGE_NAME = "com.example.android.media";

    // Music catalog manager
    private MusicProvider mMusicProvider;

    private MediaSession mSession;
    private MediaPlayer mMediaPlayer;

    // "Now playing" queue:
    private List<MediaSession.QueueItem> mPlayingQueue;
    private int mCurrentIndexOnQueue;

    // Current local media player state
    private int mState = PlaybackState.STATE_NONE;

    // Wifi lock that we hold when streaming files from the internet, in order
    // to prevent the device from shutting off the Wifi radio
    private WifiLock mWifiLock;

    private MediaNotification mMediaNotification;

    enum AudioFocus {
        NoFocusNoDuck, // we don't have audio focus, and can't duck
        NoFocusCanDuck, // we don't have focus, but can play at a low volume
                        // ("ducking")
        Focused // we have full audio focus
    }

    // Type of audio focus we have:
    private AudioFocus mAudioFocus = AudioFocus.NoFocusNoDuck;
    private AudioManager mAudioManager;

    // Indicates if we should start playing immediately after we gain focus.
    private boolean mPlayOnFocusGain;


    /*
     * (non-Javadoc)
     * @see android.app.Service#onCreate()
     */
    @Override
    public void onCreate() {
        super.onCreate();
        Log.wtf(TAG, "onCreate");

        mPlayingQueue = new ArrayList<>();

        // Create the Wifi lock (this does not acquire the lock, this just creates it)
        mWifiLock = ((WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE))
                .createWifiLock(WifiManager.WIFI_MODE_FULL, "MusicDemo_lock");

        // Create the music catalog metadata provider
        mMusicProvider = new MusicProvider();
        mMusicProvider.retrieveMedia(new MusicProvider.Callback() {
            @Override
            public void onMusicCatalogReady(boolean success) {
                Log.wtf(TAG, "onMusicCatalogReady");
                mState = success ? PlaybackState.STATE_STOPPED : PlaybackState.STATE_ERROR;
                Log.wtf(TAG, "mState" +mState);
            }
        });

        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        // Start a new MediaSession
        Log.wtf(TAG, "mAaudio" +
                "managre");
        mSession = new MediaSession(this, "MusicService");
        setSessionToken(mSession.getSessionToken());
        mSession.setCallback(new MediaSessionCallback());
        updatePlaybackState(null);

        mMediaNotification = new MediaNotification(this);
    }

    /*
     * (non-Javadoc)
     * @see android.app.Service#onDestroy()
     */
    @Override
    public void onDestroy() {
        Log.wtf(TAG, "onDestroy");

        // Service is being killed, so make sure we release our resources
        handleStopRequest(null);

        // In particular, always release the MediaSession to clean up resources
        // and notify associated MediaController(s).
        mSession.release();
    }


    // *********  MediaBrowserService methods:

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        Log.wtf(TAG, "OnGetRoot: clientPackageName=" + clientPackageName+ "; clientUid=" + clientUid + " ; rootHints=" + rootHints);
        // To ensure you are not allowing any arbitrary app to browse your app's contents, you
        // need to check the origin:
        if (!ANDROID_AUTO_PACKAGE_NAME.equals(clientPackageName) &&
                !ANDROID_AUTO_EMULATOR_PACKAGE_NAME.equals(clientPackageName)) {
            // If the request comes from an untrusted package, return null. No further calls will
            // be made to other media browsing methods.
            Log.w(TAG, "OnGetRoot: IGNORING request from untrusted package " + clientPackageName);
            return null;
        }
        return new BrowserRoot(MEDIA_ID_ROOT, null);
    }

    @Override
    public void onLoadChildren(final String parentMediaId, final Result<List<MediaItem>> result) {
        Log.wtf(TAG, "onLoadChildren");
        if (!mMusicProvider.isInitialized()) {
            Log.wtf(TAG, "!mMusicProvider.isInitialized()");
            // Use result.detach to allow calling result.sendResult from another thread:
            result.detach();

            mMusicProvider.retrieveMedia(new MusicProvider.Callback() {
                @Override
                public void onMusicCatalogReady(boolean success) {
                    if (success) {
                        loadChildrenImpl(parentMediaId, result);
                    } else {
                        updatePlaybackState(getString(R.string.error_no_metadata));
                        result.sendResult(new ArrayList<MediaItem>());
                    }
                }
            });

        } else {
            // If our music catalog is already loaded/cached, load them into result immediately
            loadChildrenImpl(parentMediaId, result);
        }
    }

    /**
     * Actual implementation of onLoadChildren that assumes that MusicProvider is already
     * initialized.
     */
    private void loadChildrenImpl(final String parentMediaId,
                                  final Result<List<MediaBrowser.MediaItem>> result) {
        Log.wtf(TAG, "OnLoadChildren: parentMediaId="+ parentMediaId);

        List<MediaBrowser.MediaItem> mediaItems = new ArrayList<>();

        if (MEDIA_ID_ROOT.equals(parentMediaId)) {
            Log.wtf(TAG, "OnLoadChildren.ROOT");
            mediaItems.add(new MediaBrowser.MediaItem(
                    new MediaDescription.Builder()
                        .setMediaId(MEDIA_ID_MUSICS_BY_GENRE)
                        .setTitle(getString(R.string.browse_genres))
                        .setIconUri(Uri.parse("android.resource://com.example.android.musicservicedemo/drawable/ic_by_genre"))
                        .setSubtitle(getString(R.string.browse_genre_subtitle))
                        .build(), MediaBrowser.MediaItem.FLAG_BROWSABLE
            ));

        } else if (MEDIA_ID_MUSICS_BY_GENRE.equals(parentMediaId)) {
            Log.wtf(TAG, "OnLoadChildren.GENRES");
            for (String genre: mMusicProvider.getGenres()) {
                MediaBrowser.MediaItem item = new MediaBrowser.MediaItem(
                    new MediaDescription.Builder()
                        .setMediaId(createBrowseCategoryMediaID(MEDIA_ID_MUSICS_BY_GENRE, genre))
                        .setTitle(genre)
                        .setSubtitle(getString(R.string.browse_musics_by_genre_subtitle, genre))
                        .build(), MediaBrowser.MediaItem.FLAG_BROWSABLE
                );
                mediaItems.add(item);
            }

        } else if (parentMediaId.startsWith(MEDIA_ID_MUSICS_BY_GENRE)) {
            String genre = extractBrowseCategoryFromMediaID(parentMediaId)[1];
            Log.wtf(TAG, "OnLoadChildren.SONGS_BY_GENRE  genre="+ genre);
            for (MediaMetadata track: mMusicProvider.getMusicsByGenre(genre)) {
                // Since mediaMetadata fields are immutable, we need to create a copy, so we
                // can set a hierarchy-aware mediaID. We will need to know the media hierarchy
                // when we get a onPlayFromMusicID call, so we can create the proper queue based
                // on where the music was selected from (by artist, by genre, random, etc)
                String hierarchyAwareMediaID = MediaIDHelper.createTrackMediaID(
                        MEDIA_ID_MUSICS_BY_GENRE, genre, track);
                MediaMetadata trackCopy = new MediaMetadata.Builder(track)
                        .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, hierarchyAwareMediaID)
                        .build();
                MediaBrowser.MediaItem bItem = new MediaBrowser.MediaItem(
                        trackCopy.getDescription(), MediaItem.FLAG_PLAYABLE);
                mediaItems.add(bItem);
            }
        } else {
            Log.w(TAG, "Skipping unmatched parentMediaId: " + parentMediaId);
        }
        result.sendResult(mediaItems);
    }



    // *********  MediaSession.Callback implementation:

    private final class MediaSessionCallback extends MediaSession.Callback {
        @Override
        public void onPlay() {
            Log.wtf(TAG, "play");

            if (mPlayingQueue == null || mPlayingQueue.isEmpty()) {
                mPlayingQueue = QueueHelper.getRandomQueue(mMusicProvider);
                mSession.setQueue(mPlayingQueue);
                mSession.setQueueTitle(getString(R.string.random_queue_title));
                // start playing from the beginning of the queue
                mCurrentIndexOnQueue = 0;
            }

            if (mPlayingQueue != null && !mPlayingQueue.isEmpty()) {
                handlePlayRequest();
            }
        }

        @Override
        public void onSkipToQueueItem(long queueId) {
            Log.wtf(TAG, "OnSkipToQueueItem:" + queueId);
            if (mPlayingQueue != null && !mPlayingQueue.isEmpty()) {

                // set the current index on queue from the music Id:
                mCurrentIndexOnQueue = QueueHelper.getMusicIndexOnQueue(mPlayingQueue, queueId);

                // play the music
                handlePlayRequest();
            }
        }

        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            Log.wtf(TAG, "playFromMediaId mediaId:"+ mediaId+ "  extras="+ extras);

            // The mediaId used here is not the unique musicId. This one comes from the
            // MediaBrowser, and is actually a "hierarchy-aware mediaID": a concatenation of
            // the hierarchy in MediaBrowser and the actual unique musicID. This is necessary
            // so we can build the correct playing queue, based on where the track was
            // selected from.
            mPlayingQueue = QueueHelper.getPlayingQueue(mediaId, mMusicProvider);
            mSession.setQueue(mPlayingQueue);
            String queueTitle = getString(R.string.browse_musics_by_genre_subtitle,
                    MediaIDHelper.extractBrowseCategoryValueFromMediaID(mediaId));
            mSession.setQueueTitle(queueTitle);

            if (mPlayingQueue != null && !mPlayingQueue.isEmpty()) {
                String uniqueMusicID = MediaIDHelper.extractMusicIDFromMediaID(mediaId);
                // set the current index on queue from the music Id:
                mCurrentIndexOnQueue = QueueHelper.getMusicIndexOnQueue(
                        mPlayingQueue, uniqueMusicID);

                // play the music
                handlePlayRequest();
            }
        }

        @Override
        public void onPause() {
            Log.wtf(TAG, "pause. current state=" + mState);
            handlePauseRequest();
        }

        @Override
        public void onStop() {
            Log.wtf(TAG, "stop. current state=" + mState);
            handleStopRequest(null);
        }

        @Override
        public void onSkipToNext() {
            Log.wtf(TAG, "skipToNext");
            mCurrentIndexOnQueue++;
            if (mPlayingQueue != null && mCurrentIndexOnQueue >= mPlayingQueue.size()) {
                mCurrentIndexOnQueue = 0;
            }
            if (QueueHelper.isIndexPlayable(mCurrentIndexOnQueue, mPlayingQueue)) {
                mState = PlaybackState.STATE_PLAYING;
                handlePlayRequest();
            } else {
                Log.e(TAG, "skipToNext: cannot skip to next. next Index=" +
                        mCurrentIndexOnQueue + " queue length=" +
                        (mPlayingQueue == null ? "null" : mPlayingQueue.size()));
                handleStopRequest("Cannot skip");
            }
        }

        @Override
        public void onSkipToPrevious() {
            Log.wtf(TAG, "skipToPrevious");

            mCurrentIndexOnQueue--;
            if (mPlayingQueue != null && mCurrentIndexOnQueue < 0) {
                // This sample's behavior: skipping to previous when in first song restarts the
                // first song.
                mCurrentIndexOnQueue = 0;
            }
            if (QueueHelper.isIndexPlayable(mCurrentIndexOnQueue, mPlayingQueue)) {
                mState = PlaybackState.STATE_PLAYING;
                handlePlayRequest();
            } else {
                Log.e(TAG, "skipToPrevious: cannot skip to previous. previous Index=" +
                        mCurrentIndexOnQueue + " queue length=" +
                        (mPlayingQueue == null ? "null" : mPlayingQueue.size()));
                handleStopRequest("Cannot skip");
            }
        }

        @Override
        public void onCustomAction(String action, Bundle extras) {
            if (CUSTOM_ACTION_THUMBS_UP.equals(action)) {
                Log.i(TAG, "onCustomAction: favorite for current track");
                MediaMetadata track = getCurrentPlayingMusic();
                if (track != null) {
                    String mediaId = track.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
                    mMusicProvider.setFavorite(mediaId, !mMusicProvider.isFavorite(mediaId));
                }
                updatePlaybackState(null);
            } else {
                Log.e(TAG, "Unsupported action: "+ action);
            }

        }

        @Override
        public void onPlayFromSearch(String query, Bundle extras) {
            Log.wtf(TAG, "playFromSearch  query="+ query);

            mPlayingQueue = QueueHelper.getPlayingQueueFromSearch(query, mMusicProvider);
            Log.wtf(TAG, "playFromSearch  playqueue.length=" + mPlayingQueue.size());
            mSession.setQueue(mPlayingQueue);

            if (mPlayingQueue != null && !mPlayingQueue.isEmpty()) {

                // start playing from the beginning of the queue
                mCurrentIndexOnQueue = 0;

                handlePlayRequest();
            }
        }
    }



    // *********  MediaPlayer listeners:

    /*
     * Called when media player is done playing current song.
     * @see android.media.MediaPlayer.OnCompletionListener
     */
    @Override
    public void onCompletion(MediaPlayer player) {
        Log.wtf(TAG, "onCompletion from MediaPlayer");
        // The media player finished playing the current song, so we go ahead
        // and start the next.
        if (mPlayingQueue != null && !mPlayingQueue.isEmpty()) {
            // In this sample, we restart the playing queue when it gets to the end:
            mCurrentIndexOnQueue++;
            if (mCurrentIndexOnQueue >= mPlayingQueue.size()) {
                mCurrentIndexOnQueue = 0;
            }
            handlePlayRequest();
        } else {
            // If there is nothing to play, we stop and release the resources:
            handleStopRequest(null);
        }
    }

    /*
     * Called when media player is done preparing.
     * @see android.media.MediaPlayer.OnPreparedListener
     */
    @Override
    public void onPrepared(MediaPlayer player) {
        Log.wtf(TAG, "onPrepared from MediaPlayer");
        // The media player is done preparing. That means we can start playing if we
        // have audio focus.
        configMediaPlayerState();
    }

    /**
     * Called when there's an error playing media. When this happens, the media
     * player goes to the Error state. We warn the user about the error and
     * reset the media player.
     *
     * @see android.media.MediaPlayer.OnErrorListener
     */
    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        Log.e(TAG, "Media player error: what=" + what + ", extra=" + extra);
        handleStopRequest("MediaPlayer error " + what + " (" + extra + ")");
        return true; // true indicates we handled the error
    }




    // *********  OnAudioFocusChangeListener listener:


    /**
     * Called by AudioManager on audio focus changes.
     */
    @Override
    public void onAudioFocusChange(int focusChange) {
        Log.wtf(TAG, "onAudioFocusChange. focusChange=" + focusChange);
        if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            // We have gained focus:
            mAudioFocus = AudioFocus.Focused;

        } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            // We have lost focus. If we can duck (low playback volume), we can keep playing.
            // Otherwise, we need to pause the playback.
            boolean canDuck = focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK;
            mAudioFocus = canDuck ? AudioFocus.NoFocusCanDuck : AudioFocus.NoFocusNoDuck;

            // If we are playing, we need to reset media player by calling configMediaPlayerState
            // with mAudioFocus properly set.
            if (mState == PlaybackState.STATE_PLAYING && !canDuck) {
                // If we don't have audio focus and can't duck, we save the information that
                // we were playing, so that we can resume playback once we get the focus back.
                mPlayOnFocusGain = true;
            }
        } else {
            Log.e(TAG, "onAudioFocusChange: Ignoring unsupported focusChange: " + focusChange);
        }

        configMediaPlayerState();
    }



    // *********  private methods:

    /**
     * Handle a request to play music
     */
    private void handlePlayRequest() {
        Log.wtf(TAG, "handlePlayRequest: mState=" + mState);

        mPlayOnFocusGain = true;
        tryToGetAudioFocus();

        if (!mSession.isActive()) {
            mSession.setActive(true);
        }

        // actually play the song
        if (mState == PlaybackState.STATE_PAUSED) {
            // If we're paused, just continue playback and restore the
            // 'foreground service' state.
            configMediaPlayerState();
        } else {
            // If we're stopped or playing a song,
            // just go ahead to the new song and (re)start playing
            playCurrentSong();
        }
    }


    /**
     * Handle a request to pause music
     */
    private void handlePauseRequest() {
        Log.wtf(TAG, "handlePauseRequest: mState=" + mState);

        if (mState == PlaybackState.STATE_PLAYING) {
            // Pause media player and cancel the 'foreground service' state.
            mState = PlaybackState.STATE_PAUSED;
            if (mMediaPlayer.isPlaying()) {
                mMediaPlayer.pause();
            }
            // while paused, retain the MediaPlayer but give up audio focus
            relaxResources(false);
            giveUpAudioFocus();
        }
        updatePlaybackState(null);
    }

        /**
         * Handle a request to stop music
         */
    private void handleStopRequest(String withError) {
        Log.wtf(TAG, "handleStopRequest: mState=" + mState + " error="+ withError);
        mState = PlaybackState.STATE_STOPPED;

        // let go of all resources...
        relaxResources(true);
        giveUpAudioFocus();
        updatePlaybackState(withError);

        mMediaNotification.stopNotification();

        // service is no longer necessary. Will be started again if needed.
        stopSelf();
    }

    /**
     * Releases resources used by the service for playback. This includes the
     * "foreground service" status, the wake locks and possibly the MediaPlayer.
     *
     * @param releaseMediaPlayer Indicates whether the Media Player should also
     *            be released or not
     */
    private void relaxResources(boolean releaseMediaPlayer) {
        Log.wtf(TAG, "relaxResources. releaseMediaPlayer=" + releaseMediaPlayer);
        // stop being a foreground service
        stopForeground(true);

        // stop and release the Media Player, if it's available
        if (releaseMediaPlayer && mMediaPlayer != null) {
            mMediaPlayer.reset();
            mMediaPlayer.release();
            mMediaPlayer = null;
        }

        // we can also release the Wifi lock, if we're holding it
        if (mWifiLock.isHeld()) {
            mWifiLock.release();
        }
    }

    /**
     * Reconfigures MediaPlayer according to audio focus settings and
     * starts/restarts it. This method starts/restarts the MediaPlayer
     * respecting the current audio focus state. So if we have focus, it will
     * play normally; if we don't have focus, it will either leave the
     * MediaPlayer paused or set it to a low volume, depending on what is
     * allowed by the current focus settings. This method assumes mPlayer !=
     * null, so if you are calling it, you have to do so from a context where
     * you are sure this is the case.
     */
    private void configMediaPlayerState() {
        Log.wtf(TAG, "configAndStartMediaPlayer. mAudioFocus=" + mAudioFocus);
        if (mAudioFocus == AudioFocus.NoFocusNoDuck) {
            // If we don't have audio focus and can't duck, we have to pause,
            if (mState == PlaybackState.STATE_PLAYING) {
                handlePauseRequest();
            }
        } else {  // we have audio focus:
            if (mAudioFocus == AudioFocus.NoFocusCanDuck) {
                mMediaPlayer.setVolume(VOLUME_DUCK, VOLUME_DUCK); // we'll be relatively quiet
            } else {
                mMediaPlayer.setVolume(VOLUME_NORMAL, VOLUME_NORMAL); // we can be loud again
            }
            // If we were playing when we lost focus, we need to resume playing.
            if (mPlayOnFocusGain) {
                if (!mMediaPlayer.isPlaying()) {
                    Log.wtf(TAG, "configAndStartMediaPlayer startMediaPlayer.");
                    mMediaPlayer.start();
                }
                mPlayOnFocusGain = false;
                mState = PlaybackState.STATE_PLAYING;
            }
        }
        updatePlaybackState(null);
    }

    /**
     * Makes sure the media player exists and has been reset. This will create
     * the media player if needed, or reset the existing media player if one
     * already exists.
     */
    private void createMediaPlayerIfNeeded() {
        Log.wtf(TAG, "createMediaPlayerIfNeeded. needed? " + (mMediaPlayer==null));
        if (mMediaPlayer == null) {
            mMediaPlayer = new MediaPlayer();

            // Make sure the media player will acquire a wake-lock while
            // playing. If we don't do that, the CPU might go to sleep while the
            // song is playing, causing playback to stop.
            mMediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);

            // we want the media player to notify us when it's ready preparing,
            // and when it's done playing:
            mMediaPlayer.setOnPreparedListener(this);
            mMediaPlayer.setOnCompletionListener(this);
            mMediaPlayer.setOnErrorListener(this);
        } else {
            mMediaPlayer.reset();
        }
    }

    /**
     * Starts playing the current song in the playing queue.
     */
    void playCurrentSong() {
        MediaMetadata track = getCurrentPlayingMusic();
        if (track == null) {
            Log.e(TAG, "playSong:  ignoring request to play next song, because cannot" +
                    " find it." +
                    " currentIndex=" + mCurrentIndexOnQueue +
                    " playQueue.size=" + (mPlayingQueue==null?"null": mPlayingQueue.size()));
            return;
        }

        String source = track.getString(MusicProvider.CUSTOM_METADATA_TRACK_SOURCE);
        Log.wtf(TAG, "playSong:  current (" + mCurrentIndexOnQueue + ") in playingQueue. " +
                " musicId=" + track.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) +
                " source=" + source);

        mState = PlaybackState.STATE_STOPPED;
        relaxResources(false); // release everything except MediaPlayer

        try {
            createMediaPlayerIfNeeded();

            mState = PlaybackState.STATE_BUFFERING;

            mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            Log.wtf(TAG, source);
            mMediaPlayer.setDataSource(source);

            // Starts preparing the media player in the background. When
            // it's done, it will call our OnPreparedListener (that is,
            // the onPrepared() method on this class, since we set the
            // listener to 'this'). Until the media player is prepared,
            // we *cannot* call start() on it!
            mMediaPlayer.prepareAsync();

            // If we are streaming from the internet, we want to hold a
            // Wifi lock, which prevents the Wifi radio from going to
            // sleep while the song is playing.
            mWifiLock.acquire();

            updatePlaybackState(null);
            updateMetadata();

        } catch (IOException ex) {
            Log.e(TAG, ex + "IOException playing song");
            updatePlaybackState(ex.getMessage());
        }
    }



    private void updateMetadata() {
        if (!QueueHelper.isIndexPlayable(mCurrentIndexOnQueue, mPlayingQueue)) {
            Log.e(TAG, "Can't retrieve current metadata.");
            mState = PlaybackState.STATE_ERROR;
            updatePlaybackState(getResources().getString(R.string.error_no_metadata));
            return;
        }
        MediaSession.QueueItem queueItem = mPlayingQueue.get(mCurrentIndexOnQueue);
        String mediaId = queueItem.getDescription().getMediaId();
        MediaMetadata track = mMusicProvider.getMusic(mediaId);
        String trackId = track.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        if (!mediaId.equals(trackId)) {
            throw new IllegalStateException("track ID (" + trackId + ") " +
                    "should match mediaId (" + mediaId + ")");
        }
        Log.wtf(TAG, "Updating metadata for MusicID= " + mediaId);
        mSession.setMetadata(track);
    }


    /**
     * Update the current media player state, optionally showing an error message.
     *
     * @param error if not null, error message to present to the user.
     *
     */
    private void updatePlaybackState(String error) {
        Log.wtf(TAG, "updatePlaybackState, setting session playback state to " + mState);
        long position = PlaybackState.PLAYBACK_POSITION_UNKNOWN;
        if (mMediaPlayer != null && mMediaPlayer.isPlaying()) {
            position = mMediaPlayer.getCurrentPosition();
        }
        PlaybackState.Builder stateBuilder = new PlaybackState.Builder()
                .setActions(getAvailableActions());

        setCustomAction(stateBuilder);

        // If there is an error message, send it to the playback state:
        if (error != null) {
            // Error states are really only supposed to be used for errors that cause playback to
            // stop unexpectedly and persist until the user takes action to fix it.
            stateBuilder.setErrorMessage(error);
            mState = PlaybackState.STATE_ERROR;
        }
        stateBuilder.setState(mState, position, 1.0f, SystemClock.elapsedRealtime());

        mSession.setPlaybackState(stateBuilder.build());

        if (mState == PlaybackState.STATE_PLAYING || mState == PlaybackState.STATE_PAUSED) {
            mMediaNotification.startNotification();
        }
    }

    private void setCustomAction(PlaybackState.Builder stateBuilder) {
        MediaMetadata currentMusic = getCurrentPlayingMusic();
        if (currentMusic != null) {
            // Set appropriate "Favorite" icon on Custom action:
            String mediaId = currentMusic.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            int favoriteIcon = R.drawable.ic_star_off;
            if (mMusicProvider.isFavorite(mediaId)) {
                favoriteIcon = R.drawable.ic_star_on;
            }
            Log.wtf(TAG, "updatePlaybackState, setting Favorite custom action of music " + mediaId+  " current favorite=" + mMusicProvider.isFavorite(mediaId));
            stateBuilder.addCustomAction(CUSTOM_ACTION_THUMBS_UP, getString(R.string.favorite),
                    favoriteIcon);
        }
    }

    private long getAvailableActions() {
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID |
                PlaybackState.ACTION_PLAY_FROM_SEARCH;
        if (mPlayingQueue == null || mPlayingQueue.isEmpty()) {
            return actions;
        }
        if (mState == PlaybackState.STATE_PLAYING) {
            actions |= PlaybackState.ACTION_PAUSE;
        }
        if (mCurrentIndexOnQueue > 0) {
            actions |= PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        }
        if (mCurrentIndexOnQueue < mPlayingQueue.size() - 1) {
            actions |= PlaybackState.ACTION_SKIP_TO_NEXT;
        }
        return actions;
    }

    private MediaMetadata getCurrentPlayingMusic() {
        if (QueueHelper.isIndexPlayable(mCurrentIndexOnQueue, mPlayingQueue)) {
            MediaSession.QueueItem item = mPlayingQueue.get(mCurrentIndexOnQueue);
            if (item != null) {
                Log.wtf(TAG, "getCurrentPlayingMusic for musicId=" + item.getDescription().getMediaId());
                return mMusicProvider.getMusic(item.getDescription().getMediaId());
            }
        }
        return null;
    }

    /**
     * Try to get the system audio focus.
     */
    void tryToGetAudioFocus() {
        Log.wtf(TAG, "tryToGetAudioFocus");
        if (mAudioFocus != AudioFocus.Focused) {
            int result = mAudioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                mAudioFocus = AudioFocus.Focused;
            }
        }

    }

    /**
     * Give up the audio focus.
     */
    void giveUpAudioFocus() {
        Log.wtf(TAG, "giveUpAudioFocus");
        if (mAudioFocus == AudioFocus.Focused) {
            if (mAudioManager.abandonAudioFocus(this) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                mAudioFocus = AudioFocus.NoFocusNoDuck;
            }
        }
    }

    /**
     * 
     *
     */
}
