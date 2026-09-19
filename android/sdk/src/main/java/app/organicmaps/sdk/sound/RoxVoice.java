package app.organicmaps.sdk.sound;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import app.organicmaps.sdk.util.log.Logger;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Uses the same stock VoiceManager API as RoxAssistant, without packaging the car's SDK. */
public final class RoxVoice implements AutoCloseable
{
  public static final String SDK_PACKAGE = "com.roxmotor.launcherapp";
  private static final String SDK_CLASS = "com.roxmotor.voicemanager.VoiceManager";
  private static final String LISTENER_CLASS = "com.roxmotor.voicemanager.interf.listener.OnVoiceStatusListener";
  private static final ExecutorService sWorker =
      Executors.newSingleThreadExecutor(task -> new Thread(task, "RoxNavigationVoice"));
  private final Handler mMain = new Handler(Looper.getMainLooper());
  private final Consumer<Boolean> mStateListener;
  private volatile boolean mClosed;
  private volatile boolean mReady;
  private Object mManager;
  private Object mListener;
  private Class<?> mListenerType;
  private Method mSpeak;
  private Method mStop;
  private String mUtteranceId;

  public RoxVoice(Context context, Consumer<Boolean> listener)
  {
    mStateListener = listener;
    Context application = context.getApplicationContext();
    sWorker.execute(() -> initialize(application));
    mMain.postDelayed(() -> {
      if (!mReady && !mClosed)
        stateChanged(false);
    }, 15000);
  }

  public static boolean isAvailable(Context context)
  {
    try
    {
      ApplicationInfo info = context.getPackageManager().getApplicationInfo(SDK_PACKAGE, 0);
      return (info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
    }
    catch (PackageManager.NameNotFoundException e)
    {
      return false;
    }
  }

  private void initialize(Context context)
  {
    try
    {
      if (!isAvailable(context))
        throw new IllegalStateException("Stock ROX launcher is unavailable");
      Context stock =
          context.createPackageContext(SDK_PACKAGE, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
      ClassLoader loader = stock.getClassLoader();
      Class<?> type = loader.loadClass(SDK_CLASS);
      mListenerType = loader.loadClass(LISTENER_CLASS);
      mManager = type.getMethod("getInstance").invoke(null);
      mSpeak = type.getMethod("speak", String.class);
      mStop = type.getMethod("stopSpeak", String.class);
      type.getMethod("init", Context.class).invoke(mManager, context);
      mListener = Proxy.newProxyInstance(loader, new Class<?>[] {mListenerType}, (proxy, method, args) -> {
        if (method.getDeclaringClass() == Object.class)
          return switch (method.getName())
          {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> "OrganicMapsVoiceStatus";
          };
        if ("onServerStatus".equals(method.getName()) && args != null && args.length > 0)
        {
          String state = String.valueOf(args[0]);
          stateChanged("SERVER_STATUS_READY".equals(state) || "SERVER_STATUS_INIT_SUCCESS".equals(state));
        }
        return null;
      });
      type.getMethod("registerStatusListener", mListenerType).invoke(mManager, mListener);
      // A previously initialized SDK may not repeat its READY callback.
      Object state = type.getMethod("getVoiceStatus").invoke(mManager);
      if (state instanceof String)
      {
        try
        {
          new org.json.JSONObject((String) state);
          stateChanged(true);
        }
        catch (org.json.JSONException ignored)
        { /* Await the SDK readiness callback. */
        }
      }
    }
    catch (ReflectiveOperationException | PackageManager.NameNotFoundException | RuntimeException | LinkageError e)
    {
      Logger.e("RoxVoice", "Cannot initialize stock VoiceManager", e);
      stateChanged(false);
    }
  }

  private void stateChanged(boolean ready)
  {
    if (mClosed)
      return;
    mReady = ready;
    mMain.post(() -> {
      if (!mClosed)
        mStateListener.accept(ready);
    });
  }

  public boolean isReady()
  {
    return mReady && !mClosed;
  }

  public void speak(@NonNull String text)
  {
    if (!isReady() || text.isEmpty())
      return;
    sWorker.execute(() -> {
      if (!isReady())
        return;
      try
      {
        stopInternal();
        Object id = mSpeak.invoke(mManager, text);
        if (!(id instanceof String) || ((String) id).isEmpty() || "-1".equals(id))
          throw new IllegalStateException("Stock VoiceManager rejected speech");
        mUtteranceId = (String) id;
      }
      catch (ReflectiveOperationException | RuntimeException e)
      {
        Logger.e("RoxVoice", "Cannot speak navigation instruction", e);
        stateChanged(false);
      }
    });
  }

  private void stopInternal() throws ReflectiveOperationException
  {
    if (mUtteranceId != null)
    {
      // Never pass an empty ID: stopping other car announcements is not our responsibility.
      mStop.invoke(mManager, mUtteranceId);
      mUtteranceId = null;
    }
  }

  public void stop()
  {
    if (mClosed)
      return;
    sWorker.execute(() -> {
      try
      {
        stopInternal();
      }
      catch (ReflectiveOperationException e)
      {
        Logger.e("RoxVoice", "Cannot stop own speech", e);
      }
    });
  }

  @Override
  public void close()
  {
    if (mClosed)
      return;
    mClosed = true;
    mReady = false;
    sWorker.execute(() -> {
      if (mManager == null)
        return;
      try
      {
        stopInternal();
        if (mListener != null)
          mManager.getClass().getMethod("unregisterStatusListener", mListenerType).invoke(mManager, mListener);
        mManager.getClass().getMethod("uninit").invoke(mManager);
      }
      catch (ReflectiveOperationException e)
      {
        Logger.e("RoxVoice", "Cannot release stock VoiceManager", e);
      }
    });
  }
}
