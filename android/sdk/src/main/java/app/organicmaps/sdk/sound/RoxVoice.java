package app.organicmaps.sdk.sound;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import app.organicmaps.sdk.util.log.Logger;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Loads the stock TTS SDK and speaks on the car's navigation channel. */
public final class RoxVoice implements AutoCloseable
{
  public static final String SDK_PACKAGE = "com.roxmotor.launcherapp";
  private static final String SERVICE_PACKAGE = "com.roxmotor.ttsservice";
  private static final String SDK_CLASS = "com.roxmotor.ttsmanager.TtsManager";
  private static final String LISTENER_CLASS = "com.roxmotor.ttsmanager.IStatusListener";
  private static final String TTS_LISTENER_CLASS = "com.roxmotor.ttsmanager.ITtsStatusListener";
  private static final int STATE_READY = 3; // Stock StateCode.READY.
  private static final int USAGE = AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE;
  private static final ExecutorService sWorker =
      Executors.newSingleThreadExecutor(task -> new Thread(task, "RoxNavigationVoice"));
  private final Handler mMain = new Handler(Looper.getMainLooper());
  private final Consumer<Boolean> mStateListener;
  private volatile boolean mClosed;
  private volatile boolean mReady;
  private Object mManager;
  private Object mListener;
  private Object mTtsListener;
  private Method mRegisterTtsListener;
  private Method mSpeak;
  private Method mStop;
  private boolean mInitialized;
  private final Runnable mInitTimeout = () ->
  {
    if (!mReady && !mClosed)
      stateChanged(false);
  };

  public RoxVoice(Context context, Consumer<Boolean> listener)
  {
    mStateListener = listener;
    Context application = context.getApplicationContext();
    sWorker.execute(() -> initialize(application));
    mMain.postDelayed(mInitTimeout, 15000);
  }

  public static boolean isAvailable(Context context)
  {
    try
    {
      for (String name : new String[] {SDK_PACKAGE, SERVICE_PACKAGE})
      {
        ApplicationInfo info = context.getPackageManager().getApplicationInfo(name, 0);
        if ((info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0)
          return false;
      }
      return true;
    }
    catch (PackageManager.NameNotFoundException e)
    {
      return false;
    }
  }

  private void initialize(Context context)
  {
    if (mClosed)
      return;
    try
    {
      if (!isAvailable(context))
        throw new IllegalStateException("Stock ROX launcher or TTS service is unavailable");
      Context stock =
          context.createPackageContext(SDK_PACKAGE, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
      ClassLoader loader = stock.getClassLoader();
      Class<?> type = loader.loadClass(SDK_CLASS);
      Class<?> listenerType = loader.loadClass(LISTENER_CLASS);
      Class<?> ttsListenerType = loader.loadClass(TTS_LISTENER_CLASS);
      mManager = type.getMethod("getInstance").invoke(null);
      mSpeak = type.getMethod("speak", String.class, int.class);
      mStop = type.getMethod("stop", int.class);
      mRegisterTtsListener = type.getMethod("registerTtsStatusListener", ttsListenerType);
      mListener = Proxy.newProxyInstance(loader, new Class<?>[] {listenerType}, (proxy, method, args) -> {
        if (method.getDeclaringClass() == Object.class)
          return proxyObjectMethod(proxy, method, args);
        if ("onStateChanged".equals(method.getName()))
          sWorker.execute(() -> onServiceState((Integer) args[0]));
        else if ("onError".equals(method.getName()))
          sWorker.execute(() -> {
            Logger.e("RoxVoice", "Stock TTS service error: " + args[0]);
            stateChanged(false);
          });
        return null;
      });
      mTtsListener = Proxy.newProxyInstance(loader, new Class<?>[] {ttsListenerType}, (proxy, method, args) -> {
        if (method.getDeclaringClass() == Object.class)
          return proxyObjectMethod(proxy, method, args);
        if (!mClosed && "onTtsStatus".equals(method.getName()))
        {
          // A rejected utterance (e.g. lost audio focus) does not disconnect the service.
          String status = String.valueOf(args[0]);
          if ("VOICE_STATUS_TTS_PLAY_ERROR".equals(status))
            Logger.e("RoxVoice", "Stock navigation TTS playback failed");
          else
            Logger.d("RoxVoice", "Navigation TTS: " + status);
        }
        return null;
      });
      type.getMethod("registerStatusListener", listenerType).invoke(mManager, mListener);
      // Register before init: onServiceConnected may report READY immediately.
      Object result = type.getMethod("init", Context.class).invoke(mManager, context);
      mInitialized = true;
      if (!Integer.valueOf(0).equals(result))
        throw new IllegalStateException("Stock TtsManager init failed: " + result);
    }
    catch (ReflectiveOperationException | PackageManager.NameNotFoundException | RuntimeException | LinkageError e)
    {
      Logger.e("RoxVoice", "Cannot initialize stock TtsManager", e);
      stateChanged(false);
    }
  }

  private static Object proxyObjectMethod(Object proxy, Method method, Object[] args)
  {
    return switch (method.getName())
    {
      case "hashCode" -> System.identityHashCode(proxy);
      case "equals" -> proxy == args[0];
      default -> "OrganicMapsTtsStatus";
    };
  }

  private void onServiceState(int state)
  {
    if (mClosed)
      return;
    if (state != STATE_READY)
    {
      stateChanged(false);
      return;
    }
    try
    {
      Object result = mRegisterTtsListener.invoke(mManager, mTtsListener);
      if (!Integer.valueOf(0).equals(result))
        throw new IllegalStateException("Cannot register TTS callback: " + result);
      mMain.removeCallbacks(mInitTimeout);
      stateChanged(true);
    }
    catch (ReflectiveOperationException | RuntimeException e)
    {
      Logger.e("RoxVoice", "Cannot connect navigation TTS callbacks", e);
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
        // The stock SDK queues speech asynchronously and returns null even on success.
        mSpeak.invoke(mManager, text, USAGE);
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
    // TtsManager supplies our package name; never stop another app or another channel.
    if (mInitialized)
      mStop.invoke(mManager, USAGE);
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
      catch (ReflectiveOperationException | RuntimeException e)
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
    mMain.removeCallbacks(mInitTimeout);
    sWorker.execute(() -> {
      if (mManager == null)
        return;
      try
      {
        stopInternal();
      }
      catch (ReflectiveOperationException | RuntimeException e)
      {
        Logger.e("RoxVoice", "Cannot stop navigation TTS on close", e);
      }
      release("unregisterTtsStatusListener");
      release("unRegisterStatusListener");
      if (mInitialized)
        release("unInit");
    });
  }

  private void release(String method)
  {
    try
    {
      mManager.getClass().getMethod(method).invoke(mManager);
    }
    catch (ReflectiveOperationException | RuntimeException e)
    {
      Logger.e("RoxVoice", "Cannot release stock TtsManager: " + method, e);
    }
  }
}
