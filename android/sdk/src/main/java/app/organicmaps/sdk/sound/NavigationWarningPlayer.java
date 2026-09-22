package app.organicmaps.sdk.sound;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import app.organicmaps.sdk.util.log.Logger;
import java.io.IOException;
import java.util.Objects;

/** Short alerts use the same navigation usage and transient ducking focus as spoken instructions. */
public final class NavigationWarningPlayer implements AutoCloseable
{
  private final Context mContext;
  private final AudioFocusManager mFocus;
  private MediaPlayer mPlayer;
  private String mAsset;
  private boolean mPrepared;
  private boolean mPlaying;

  public NavigationWarningPlayer(Context context)
  {
    mContext = context.getApplicationContext();
    mFocus = new AudioFocusManager(mContext, this::stop);
  }

  public boolean play(String asset)
  {
    if (mPlaying || TtsPlayer.INSTANCE.isSpeaking() || !mFocus.requestAudioFocus())
      return false;
    mPlaying = true;
    try
    {
      if (mPlayer == null)
      {
        mPlayer = new MediaPlayer();
        mPlayer.setOnCompletionListener(player -> {
          if (player == mPlayer)
            finishPlayback();
        });
        mPlayer.setOnErrorListener((player, what, extra) -> {
          if (player == mPlayer)
            close();
          return true;
        });
        mPlayer.setOnPreparedListener(prepared -> {
          if (prepared == mPlayer && mPlaying)
          {
            mPrepared = true;
            prepared.start();
          }
        });
      }
      if (mPrepared && Objects.equals(mAsset, asset))
      {
        // Keep the prepared decoder between alerts; focus is still acquired per playback.
        mPlayer.seekTo(0);
        mPlayer.start();
        return true;
      }
      mPrepared = false;
      mAsset = asset;
      mPlayer.reset();
      mPlayer.setAudioAttributes(new AudioAttributes.Builder()
                                     .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                     .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                     .build());
      try (AssetFileDescriptor data = mContext.getAssets().openFd(asset))
      {
        mPlayer.setDataSource(data.getFileDescriptor(), data.getStartOffset(), data.getLength());
      }
      mPlayer.prepareAsync();
      return true;
    }
    catch (IOException | RuntimeException error)
    {
      Logger.w("NavigationWarning", "Cannot play warning", error);
      close();
      return false;
    }
  }

  public void stop()
  {
    if (!mPlaying)
      return;
    if (!mPrepared)
    {
      close();
      return;
    }
    mPlayer.pause();
    mPlayer.seekTo(0);
    finishPlayback();
  }

  private void finishPlayback()
  {
    if (!mPlaying)
      return;
    mPlaying = false;
    mFocus.releaseAudioFocus();
  }

  @Override
  public void close()
  {
    if (mPlayer != null)
    {
      mPlayer.release();
      mPlayer = null;
    }
    mPrepared = false;
    mAsset = null;
    finishPlayback();
  }
}
