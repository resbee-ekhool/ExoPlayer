/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.google.android.exoplayer2.drm;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.drm.DefaultDrmSession.ProvisioningManager;
import com.google.android.exoplayer2.drm.DrmInitData.SchemeData;
import com.google.android.exoplayer2.drm.DrmSession.DrmSessionException;
import com.google.android.exoplayer2.drm.ExoMediaDrm.OnEventListener;
import com.google.android.exoplayer2.upstream.DefaultLoadErrorHandlingPolicy;
import com.google.android.exoplayer2.upstream.LoadErrorHandlingPolicy;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.EventDispatcher;
import com.google.android.exoplayer2.util.Log;
import com.google.android.exoplayer2.util.Util;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A {@link DrmSessionManager} that supports playbacks using {@link ExoMediaDrm}. */
@TargetApi(18)
public class DefaultDrmSessionManager<T extends ExoMediaCrypto>
    implements DrmSessionManager<T>, ProvisioningManager<T> {

  /**
   * Builder for {@link DefaultDrmSessionManager} instances.
   *
   * <p>See {@link #Builder} for the list of default values.
   */
  public static final class Builder {

    private final HashMap<String, String> keyRequestParameters;
    private UUID uuid;
    private ExoMediaDrm.Provider<ExoMediaCrypto> exoMediaDrmProvider;
    private boolean multiSession;
    private boolean allowPlaceholderSessions;
    @Flags private int flags;
    private LoadErrorHandlingPolicy loadErrorHandlingPolicy;

    /**
     * Creates a builder with default values. The default values are:
     *
     * <ul>
     *   <li>{@link #setKeyRequestParameters keyRequestParameters}: An empty map.
     *   <li>{@link #setUuidAndExoMediaDrmProvider UUID}: {@link C#WIDEVINE_UUID}.
     *   <li>{@link #setUuidAndExoMediaDrmProvider ExoMediaDrm.Provider}: {@link
     *       FrameworkMediaDrm#DEFAULT_PROVIDER}.
     *   <li>{@link #setMultiSession multiSession}: Not allowed by default.
     *   <li>{@link #setAllowPlaceholderSessions allowPlaceholderSession}: Not allowed by default.
     *   <li>{@link #setPlayClearSamplesWithoutKeys playClearSamplesWithoutKeys}: Not allowed by
     *       default.
     *   <li>{@link #setLoadErrorHandlingPolicy LoadErrorHandlingPolicy}: {@link
     *       DefaultLoadErrorHandlingPolicy}.
     * </ul>
     */
    @SuppressWarnings("unchecked")
    public Builder() {
      keyRequestParameters = new HashMap<>();
      uuid = C.WIDEVINE_UUID;
      exoMediaDrmProvider = (ExoMediaDrm.Provider) FrameworkMediaDrm.DEFAULT_PROVIDER;
      multiSession = false;
      allowPlaceholderSessions = false;
      flags = 0;
      loadErrorHandlingPolicy = new DefaultLoadErrorHandlingPolicy();
    }

    /**
     * Sets the parameters to pass to {@link ExoMediaDrm#getKeyRequest(byte[], List, int, HashMap)}.
     *
     * @param keyRequestParameters A map with parameters.
     * @return This builder.
     */
    public Builder setKeyRequestParameters(Map<String, String> keyRequestParameters) {
      this.keyRequestParameters.clear();
      this.keyRequestParameters.putAll(Assertions.checkNotNull(keyRequestParameters));
      return this;
    }

    /**
     * Sets the UUID of the DRM scheme and the {@link ExoMediaDrm.Provider} to use.
     *
     * @param uuid The UUID of the DRM scheme.
     * @param exoMediaDrmProvider The {@link ExoMediaDrm.Provider}.
     * @return This builder.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Builder setUuidAndExoMediaDrmProvider(
        UUID uuid, ExoMediaDrm.Provider exoMediaDrmProvider) {
      this.uuid = Assertions.checkNotNull(uuid);
      this.exoMediaDrmProvider = Assertions.checkNotNull(exoMediaDrmProvider);
      return this;
    }

    /**
     * Sets whether this session manager is allowed to acquire multiple simultaneous sessions.
     *
     * <p>Users should pass false when a single key request will obtain all keys required to decrypt
     * the associated content. {@code multiSession} is required when content uses key rotation.
     *
     * @param multiSession Whether this session manager is allowed to acquire multiple simultaneous
     *     sessions.
     * @return This builder.
     */
    public Builder setMultiSession(boolean multiSession) {
      this.multiSession = multiSession;
      return this;
    }

    /**
     * Sets whether this session manager is allowed to acquire placeholder sessions.
     *
     * <p>Placeholder sessions allow the use of secure renderers to play clear content.
     *
     * @param allowPlaceholderSessions Whether this session manager is allowed to acquire
     *     placeholder sessions.
     * @return This builder.
     */
    public Builder setAllowPlaceholderSessions(boolean allowPlaceholderSessions) {
      this.allowPlaceholderSessions = allowPlaceholderSessions;
      return this;
    }

    /**
     * Sets whether clear samples should be played when keys are not available. Keys are considered
     * unavailable when the load request is taking place, or when the key request has failed.
     *
     * <p>This option does not affect placeholder sessions.
     *
     * @param playClearSamplesWithoutKeys Whether clear samples should be played when keys are not
     *     available.
     * @return This builder.
     */
    public Builder setPlayClearSamplesWithoutKeys(boolean playClearSamplesWithoutKeys) {
      if (playClearSamplesWithoutKeys) {
        this.flags |= FLAG_PLAY_CLEAR_SAMPLES_WITHOUT_KEYS;
      } else {
        this.flags &= ~FLAG_PLAY_CLEAR_SAMPLES_WITHOUT_KEYS;
      }
      return this;
    }

    /**
     * Sets the {@link LoadErrorHandlingPolicy} for key and provisioning requests.
     *
     * @param loadErrorHandlingPolicy A {@link LoadErrorHandlingPolicy}.
     * @return This builder.
     */
    public Builder setLoadErrorHandlingPolicy(LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
      this.loadErrorHandlingPolicy = Assertions.checkNotNull(loadErrorHandlingPolicy);
      return this;
    }

    /** Builds a {@link DefaultDrmSessionManager} instance. */
    public DefaultDrmSessionManager<ExoMediaCrypto> build(MediaDrmCallback mediaDrmCallback) {
      return new DefaultDrmSessionManager<>(
          uuid,
          exoMediaDrmProvider,
          mediaDrmCallback,
          keyRequestParameters,
          multiSession,
          allowPlaceholderSessions,
          flags,
          loadErrorHandlingPolicy);
    }
  }

  /**
   * Signals that the {@link DrmInitData} passed to {@link #acquireSession} does not contain does
   * not contain scheme data for the required UUID.
   */
  public static final class MissingSchemeDataException extends Exception {

    private MissingSchemeDataException(UUID uuid) {
      super("Media does not support uuid: " + uuid);
    }
  }

  /**
   * The key to use when passing CustomData to a PlayReady instance in an optional parameter map.
   */
  public static final String PLAYREADY_CUSTOM_DATA_KEY = "PRCustomData";

  /**
   * Determines the action to be done after a session acquired. One of {@link #MODE_PLAYBACK},
   * {@link #MODE_QUERY}, {@link #MODE_DOWNLOAD} or {@link #MODE_RELEASE}.
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({MODE_PLAYBACK, MODE_QUERY, MODE_DOWNLOAD, MODE_RELEASE})
  public @interface Mode {}
  /**
   * Loads and refreshes (if necessary) a license for playback. Supports streaming and offline
   * licenses.
   */
  public static final int MODE_PLAYBACK = 0;
  /** Restores an offline license to allow its status to be queried. */
  public static final int MODE_QUERY = 1;
  /** Downloads an offline license or renews an existing one. */
  public static final int MODE_DOWNLOAD = 2;
  /** Releases an existing offline license. */
  public static final int MODE_RELEASE = 3;
  /** Number of times to retry for initial provisioning and key request for reporting error. */
  public static final int INITIAL_DRM_REQUEST_RETRY_COUNT = 3;

  private static final String TAG = "DefaultDrmSessionMgr";

  private final UUID uuid;
  private final ExoMediaDrm.Provider<T> exoMediaDrmProvider;
  private final MediaDrmCallback callback;
  @Nullable private final HashMap<String, String> optionalKeyRequestParameters;
  private final EventDispatcher<DefaultDrmSessionEventListener> eventDispatcher;
  private final boolean multiSession;
  private final boolean allowPlaceholderSessions;
  @Flags private final int flags;
  private final LoadErrorHandlingPolicy loadErrorHandlingPolicy;

  private final List<DefaultDrmSession<T>> sessions;
  private final List<DefaultDrmSession<T>> provisioningSessions;

  private int prepareCallsCount;
  @Nullable private ExoMediaDrm<T> exoMediaDrm;
  @Nullable private DefaultDrmSession<T> placeholderDrmSession;
  @Nullable private DefaultDrmSession<T> noMultiSessionDrmSession;
  @Nullable private Looper playbackLooper;
  private int mode;
  @Nullable private byte[] offlineLicenseKeySetId;

  /* package */ volatile @Nullable MediaDrmHandler mediaDrmHandler;

  /**
   * @param uuid The UUID of the drm scheme.
   * @param exoMediaDrm An underlying {@link ExoMediaDrm} for use by the manager.
   * @param callback Performs key and provisioning requests.
   * @param optionalKeyRequestParameters An optional map of parameters to pass as the last argument
   *     to {@link ExoMediaDrm#getKeyRequest(byte[], List, int, HashMap)}. May be null.
   * @deprecated Use {@link Builder} instead.
   */
  @Deprecated
  public DefaultDrmSessionManager(
      UUID uuid,
      ExoMediaDrm<T> exoMediaDrm,
      MediaDrmCallback callback,
      @Nullable HashMap<String, String> optionalKeyRequestParameters) {
    this(
        uuid,
        exoMediaDrm,
        callback,
        optionalKeyRequestParameters,
        /* multiSession= */ false,
        INITIAL_DRM_REQUEST_RETRY_COUNT);
  }

  /**
   * @param uuid The UUID of the drm scheme.
   * @param exoMediaDrm An underlying {@link ExoMediaDrm} for use by the manager.
   * @param callback Performs key and provisioning requests.
   * @param optionalKeyRequestParameters An optional map of parameters to pass as the last argument
   *     to {@link ExoMediaDrm#getKeyRequest(byte[], List, int, HashMap)}. May be null.
   * @param multiSession A boolean that specify whether multiple key session support is enabled.
   *     Default is false.
   * @deprecated Use {@link Builder} instead.
   */
  @Deprecated
  public DefaultDrmSessionManager(
      UUID uuid,
      ExoMediaDrm<T> exoMediaDrm,
      MediaDrmCallback callback,
      @Nullable HashMap<String, String> optionalKeyRequestParameters,
      boolean multiSession) {
    this(
        uuid,
        exoMediaDrm,
        callback,
        optionalKeyRequestParameters,
        multiSession,
        INITIAL_DRM_REQUEST_RETRY_COUNT);
  }

  /**
   * @param uuid The UUID of the drm scheme.
   * @param exoMediaDrm An underlying {@link ExoMediaDrm} for use by the manager.
   * @param callback Performs key and provisioning requests.
   * @param optionalKeyRequestParameters An optional map of parameters to pass as the last argument
   *     to {@link ExoMediaDrm#getKeyRequest(byte[], List, int, HashMap)}. May be null.
   * @param multiSession A boolean that specify whether multiple key session support is enabled.
   *     Default is false.
   * @param initialDrmRequestRetryCount The number of times to retry for initial provisioning and
   *     key request before reporting error.
   * @deprecated Use {@link Builder} instead.
   */
  @Deprecated
  public DefaultDrmSessionManager(
      UUID uuid,
      ExoMediaDrm<T> exoMediaDrm,
      MediaDrmCallback callback,
      @Nullable HashMap<String, String> optionalKeyRequestParameters,
      boolean multiSession,
      int initialDrmRequestRetryCount) {
    this(
        uuid,
        new ExoMediaDrm.AppManagedProvider<>(exoMediaDrm),
        callback,
        optionalKeyRequestParameters,
        multiSession,
        /* allowPlaceholderSessions= */ false,
        /* flags= */ 0,
        new DefaultLoadErrorHandlingPolicy(initialDrmRequestRetryCount));
  }

  private DefaultDrmSessionManager(
      UUID uuid,
      ExoMediaDrm.Provider<T> exoMediaDrmProvider,
      MediaDrmCallback callback,
      @Nullable HashMap<String, String> optionalKeyRequestParameters,
      boolean multiSession,
      boolean allowPlaceholderSessions,
      @Flags int flags,
      LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
    Assertions.checkNotNull(uuid);
    Assertions.checkArgument(!C.COMMON_PSSH_UUID.equals(uuid), "Use C.CLEARKEY_UUID instead");
    this.uuid = uuid;
    this.exoMediaDrmProvider = exoMediaDrmProvider;
    this.callback = callback;
    this.optionalKeyRequestParameters = optionalKeyRequestParameters;
    this.eventDispatcher = new EventDispatcher<>();
    this.multiSession = multiSession;
    this.allowPlaceholderSessions = allowPlaceholderSessions;
    this.flags = flags;
    this.loadErrorHandlingPolicy = loadErrorHandlingPolicy;
    mode = MODE_PLAYBACK;
    sessions = new ArrayList<>();
    provisioningSessions = new ArrayList<>();
  }

  /**
   * Adds a {@link DefaultDrmSessionEventListener} to listen to drm session events.
   *
   * @param handler A handler to use when delivering events to {@code eventListener}.
   * @param eventListener A listener of events.
   */
  public final void addListener(Handler handler, DefaultDrmSessionEventListener eventListener) {
    eventDispatcher.addListener(handler, eventListener);
  }

  /**
   * Removes a {@link DefaultDrmSessionEventListener} from the list of drm session event listeners.
   *
   * @param eventListener The listener to remove.
   */
  public final void removeListener(DefaultDrmSessionEventListener eventListener) {
    eventDispatcher.removeListener(eventListener);
  }

  /**
   * Sets the mode, which determines the role of sessions acquired from the instance. This must be
   * called before {@link #acquireSession(Looper, DrmInitData)} or {@link
   * #acquirePlaceholderSession(Looper)} is called.
   *
   * <p>By default, the mode is {@link #MODE_PLAYBACK} and a streaming license is requested when
   * required.
   *
   * <p>{@code mode} must be one of these:
   *
   * <ul>
   *   <li>{@link #MODE_PLAYBACK}: If {@code offlineLicenseKeySetId} is null, a streaming license is
   *       requested otherwise the offline license is restored.
   *   <li>{@link #MODE_QUERY}: {@code offlineLicenseKeySetId} can not be null. The offline license
   *       is restored.
   *   <li>{@link #MODE_DOWNLOAD}: If {@code offlineLicenseKeySetId} is null, an offline license is
   *       requested otherwise the offline license is renewed.
   *   <li>{@link #MODE_RELEASE}: {@code offlineLicenseKeySetId} can not be null. The offline
   *       license is released.
   * </ul>
   *
   * @param mode The mode to be set.
   * @param offlineLicenseKeySetId The key set id of the license to be used with the given mode.
   */
  public void setMode(@Mode int mode, @Nullable byte[] offlineLicenseKeySetId) {
    Assertions.checkState(sessions.isEmpty());
    if (mode == MODE_QUERY || mode == MODE_RELEASE) {
      Assertions.checkNotNull(offlineLicenseKeySetId);
    }
    this.mode = mode;
    this.offlineLicenseKeySetId = offlineLicenseKeySetId;
  }

  // DrmSessionManager implementation.

  @Override
  public final void prepare() {
    if (prepareCallsCount++ == 0) {
      Assertions.checkState(exoMediaDrm == null);
      exoMediaDrm = exoMediaDrmProvider.acquireExoMediaDrm(uuid);
      if (multiSession && C.WIDEVINE_UUID.equals(uuid) && Util.SDK_INT >= 19) {
        // TODO: Enabling session sharing probably doesn't do anything useful here. It would only be
        // useful if DefaultDrmSession instances were aware of one another's state, which is not
        // implemented. Or if custom renderers are being used that allow playback to proceed before
        // keys, which seems unlikely to be true in practice.
        exoMediaDrm.setPropertyString("sessionSharing", "enable");
      }
      exoMediaDrm.setOnEventListener(new MediaDrmEventListener());
    }
  }

  @Override
  public final void release() {
    if (--prepareCallsCount == 0) {
      Assertions.checkNotNull(exoMediaDrm).release();
      exoMediaDrm = null;
    }
  }

  @Override
  public boolean canAcquireSession(DrmInitData drmInitData) {
    if (offlineLicenseKeySetId != null) {
      // An offline license can be restored so a session can always be acquired.
      return true;
    }
    List<SchemeData> schemeDatas = getSchemeDatas(drmInitData, uuid, true);
    if (schemeDatas.isEmpty()) {
      if (drmInitData.schemeDataCount == 1 && drmInitData.get(0).matches(C.COMMON_PSSH_UUID)) {
        // Assume scheme specific data will be added before the session is opened.
        Log.w(
            TAG, "DrmInitData only contains common PSSH SchemeData. Assuming support for: " + uuid);
      } else {
        // No data for this manager's scheme.
        return false;
      }
    }
    String schemeType = drmInitData.schemeType;
    if (schemeType == null || C.CENC_TYPE_cenc.equals(schemeType)) {
      // If there is no scheme information, assume patternless AES-CTR.
      return true;
    } else if (C.CENC_TYPE_cbc1.equals(schemeType)
        || C.CENC_TYPE_cbcs.equals(schemeType)
        || C.CENC_TYPE_cens.equals(schemeType)) {
      // API support for AES-CBC and pattern encryption was added in API 24. However, the
      // implementation was not stable until API 25.
      return Util.SDK_INT >= 25;
    }
    // Unknown schemes, assume one of them is supported.
    return true;
  }

  @Override
  @Nullable
  public DrmSession<T> acquirePlaceholderSession(Looper playbackLooper) {
    assertExpectedPlaybackLooper(playbackLooper);
    Assertions.checkNotNull(exoMediaDrm);
    boolean avoidPlaceholderDrmSessions =
        FrameworkMediaCrypto.class.equals(exoMediaDrm.getExoMediaCryptoType())
            && FrameworkMediaCrypto.WORKAROUND_DEVICE_NEEDS_KEYS_TO_CONFIGURE_CODEC;
    if (avoidPlaceholderDrmSessions
        || !allowPlaceholderSessions
        || exoMediaDrm.getExoMediaCryptoType() == null) {
      return null;
    }
    maybeCreateMediaDrmHandler(playbackLooper);
    if (placeholderDrmSession == null) {
      DefaultDrmSession<T> placeholderDrmSession =
          createNewDefaultSession(
              /* schemeDatas= */ Collections.emptyList(), /* isPlaceholderSession= */ true);
      sessions.add(placeholderDrmSession);
      this.placeholderDrmSession = placeholderDrmSession;
    }
    placeholderDrmSession.acquireReference();
    return placeholderDrmSession;
  }

  @Override
  public DrmSession<T> acquireSession(Looper playbackLooper, DrmInitData drmInitData) {
    assertExpectedPlaybackLooper(playbackLooper);
    maybeCreateMediaDrmHandler(playbackLooper);

    List<SchemeData> schemeDatas = null;
    if (offlineLicenseKeySetId == null) {
      schemeDatas = getSchemeDatas(drmInitData, uuid, false);
      if (schemeDatas.isEmpty()) {
        final MissingSchemeDataException error = new MissingSchemeDataException(uuid);
        eventDispatcher.dispatch(listener -> listener.onDrmSessionManagerError(error));
        return new ErrorStateDrmSession<>(new DrmSessionException(error));
      }
    }

    @Nullable DefaultDrmSession<T> session;
    if (!multiSession) {
      session = noMultiSessionDrmSession;
    } else {
      // Only use an existing session if it has matching init data.
      session = null;
      for (DefaultDrmSession<T> existingSession : sessions) {
        if (Util.areEqual(existingSession.schemeDatas, schemeDatas)) {
          session = existingSession;
          break;
        }
      }
    }

    if (session == null) {
      // Create a new session.
      session = createNewDefaultSession(schemeDatas, /* isPlaceholderSession= */ false);
      if (!multiSession) {
        noMultiSessionDrmSession = session;
      }
      sessions.add(session);
    }
    session.acquireReference();
    return session;
  }

  @Override
  @Flags
  public final int getFlags() {
    return flags;
  }

  @Override
  @Nullable
  public Class<T> getExoMediaCryptoType(DrmInitData drmInitData) {
    return canAcquireSession(drmInitData)
        ? Assertions.checkNotNull(exoMediaDrm).getExoMediaCryptoType()
        : null;
  }

  // ProvisioningManager implementation.

  @Override
  public void provisionRequired(DefaultDrmSession<T> session) {
    if (provisioningSessions.contains(session)) {
      // The session has already requested provisioning.
      return;
    }
    provisioningSessions.add(session);
    if (provisioningSessions.size() == 1) {
      // This is the first session requesting provisioning, so have it perform the operation.
      session.provision();
    }
  }

  @Override
  public void onProvisionCompleted() {
    for (DefaultDrmSession<T> session : provisioningSessions) {
      session.onProvisionCompleted();
    }
    provisioningSessions.clear();
  }

  @Override
  public void onProvisionError(Exception error) {
    for (DefaultDrmSession<T> session : provisioningSessions) {
      session.onProvisionError(error);
    }
    provisioningSessions.clear();
  }

  // Internal methods.

  private void assertExpectedPlaybackLooper(Looper playbackLooper) {
    Assertions.checkState(this.playbackLooper == null || this.playbackLooper == playbackLooper);
    this.playbackLooper = playbackLooper;
  }

  private void maybeCreateMediaDrmHandler(Looper playbackLooper) {
    if (mediaDrmHandler == null) {
      mediaDrmHandler = new MediaDrmHandler(playbackLooper);
    }
  }

  private DefaultDrmSession<T> createNewDefaultSession(
      @Nullable List<SchemeData> schemeDatas, boolean isPlaceholderSession) {
    Assertions.checkNotNull(exoMediaDrm);
    return new DefaultDrmSession<>(
        uuid,
        exoMediaDrm,
        /* provisioningManager= */ this,
        /* releaseCallback= */ this::onSessionReleased,
        schemeDatas,
        mode,
        isPlaceholderSession,
        offlineLicenseKeySetId,
        optionalKeyRequestParameters,
        callback,
        Assertions.checkNotNull(playbackLooper),
        eventDispatcher,
        loadErrorHandlingPolicy);
  }

  private void onSessionReleased(DefaultDrmSession<T> drmSession) {
    sessions.remove(drmSession);
    if (placeholderDrmSession == drmSession) {
      placeholderDrmSession = null;
    }
    if (noMultiSessionDrmSession == drmSession) {
      noMultiSessionDrmSession = null;
    }
    if (provisioningSessions.size() > 1 && provisioningSessions.get(0) == drmSession) {
      // Other sessions were waiting for the released session to complete a provision operation.
      // We need to have one of those sessions perform the provision operation instead.
      provisioningSessions.get(1).provision();
    }
    provisioningSessions.remove(drmSession);
  }

  /**
   * Extracts {@link SchemeData} instances suitable for the given DRM scheme {@link UUID}.
   *
   * @param drmInitData The {@link DrmInitData} from which to extract the {@link SchemeData}.
   * @param uuid The UUID.
   * @param allowMissingData Whether a {@link SchemeData} with null {@link SchemeData#data} may be
   *     returned.
   * @return The extracted {@link SchemeData} instances, or an empty list if no suitable data is
   *     present.
   */
  private static List<SchemeData> getSchemeDatas(
      DrmInitData drmInitData, UUID uuid, boolean allowMissingData) {
    // Look for matching scheme data (matching the Common PSSH box for ClearKey).
    List<SchemeData> matchingSchemeDatas = new ArrayList<>(drmInitData.schemeDataCount);
    for (int i = 0; i < drmInitData.schemeDataCount; i++) {
      SchemeData schemeData = drmInitData.get(i);
      boolean uuidMatches =
          schemeData.matches(uuid)
              || (C.CLEARKEY_UUID.equals(uuid) && schemeData.matches(C.COMMON_PSSH_UUID));
      if (uuidMatches && (schemeData.data != null || allowMissingData)) {
        matchingSchemeDatas.add(schemeData);
      }
    }
    return matchingSchemeDatas;
  }

  @SuppressLint("HandlerLeak")
  private class MediaDrmHandler extends Handler {

    public MediaDrmHandler(Looper looper) {
      super(looper);
    }

    @Override
    public void handleMessage(Message msg) {
      byte[] sessionId = (byte[]) msg.obj;
      if (sessionId == null) {
        // The event is not associated with any particular session.
        return;
      }
      for (DefaultDrmSession<T> session : sessions) {
        if (session.hasSessionId(sessionId)) {
          session.onMediaDrmEvent(msg.what);
          return;
        }
      }
    }
  }

  private class MediaDrmEventListener implements OnEventListener<T> {

    @Override
    public void onEvent(
        ExoMediaDrm<? extends T> md,
        @Nullable byte[] sessionId,
        int event,
        int extra,
        @Nullable byte[] data) {
      Assertions.checkNotNull(mediaDrmHandler).obtainMessage(event, sessionId).sendToTarget();
    }
  }
}