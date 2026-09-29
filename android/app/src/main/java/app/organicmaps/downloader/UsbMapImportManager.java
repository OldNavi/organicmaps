package app.organicmaps.downloader;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import androidx.core.content.ContextCompat;
import app.organicmaps.BuildConfig;
import app.organicmaps.MwmActivity;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.downloader.MapManager;
import app.organicmaps.sdk.util.log.Logger;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-owned import state; dialogs and activities can be recreated without restarting a copy. */
public final class UsbMapImportManager
{
  public enum State
  {
    IDLE,
    PERMISSION,
    COPYING,
    WAITING,
    FINISHED
  }
  private static final String TAG = "UsbMapImport";
  private final MwmApplication mApp;
  private final Handler mMain = new Handler(Looper.getMainLooper());
  private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
  private final AtomicBoolean mCancelled = new AtomicBoolean();
  private final List<Runnable> mListeners = new ArrayList<>();
  private WeakReference<MwmActivity> mActivity = new WeakReference<>(null);
  private final Set<File> mMounts = new LinkedHashSet<>();
  private boolean mReady;
  private boolean mScanning;
  private boolean mScanAgain;
  private boolean mPermissionDismissed;
  private boolean mCopyStarted;
  private State mState = State.IDLE;
  private List<UsbMapFiles.Entry> mEntries = new ArrayList<>();
  private final List<String> mErrors = new ArrayList<>();
  private List<UsbMapFiles.Prepared> mPrepared = new ArrayList<>();
  private File mDestination;
  private String mCurrentFile = "";
  private int mProgress;
  private int mInstalled;

  public UsbMapImportManager(MwmApplication app)
  {
    mApp = app;
  }
  public State state()
  {
    return mState;
  }
  public int progress()
  {
    return mProgress;
  }
  public int installed()
  {
    return mInstalled;
  }
  public String currentFile()
  {
    return mCurrentFile;
  }
  public List<String> errors()
  {
    return mErrors;
  }
  public List<UsbMapFiles.Entry> entries()
  {
    return mEntries;
  }
  public String destination()
  {
    return mDestination == null ? "" : mDestination.getPath();
  }
  public void listen(Runnable listener)
  {
    mListeners.add(listener);
  }
  public void unlisten(Runnable listener)
  {
    mListeners.remove(listener);
  }

  public void start()
  {
    if (mReady || !"auto".equals(BuildConfig.FLAVOR))
      return;
    mReady = true;
    IntentFilter filter = new IntentFilter();
    filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
    filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
    filter.addAction(Intent.ACTION_MEDIA_EJECT);
    filter.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
    filter.addDataScheme("file");
    ContextCompat.registerReceiver(mApp, new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent)
      {
        mediaChanged(intent);
      }
    }, filter, ContextCompat.RECEIVER_EXPORTED);
    scan();
  }

  public void mediaChanged(Intent intent)
  {
    Uri data = intent.getData();
    if (data != null && data.getPath() != null)
    {
      File root = new File(data.getPath());
      if (Intent.ACTION_MEDIA_MOUNTED.equals(intent.getAction()))
      {
        mMounts.add(root);
        mPermissionDismissed = false;
        if (mState != State.IDLE)
          mScanAgain = true;
      }
      else
      {
        mMounts.remove(root);
        if (mState == State.COPYING)
          mCancelled.set(true);
      }
    }
    scan();
  }

  public void resume(MwmActivity activity)
  {
    mActivity = new WeakReference<>(activity);
    mMain.post(() -> {
      if (mState == State.PERMISSION && hasAccess())
        mState = State.IDLE;
      showDialog();
      scan();
    });
  }
  public void pause(Activity activity)
  {
    if (mActivity.get() == activity)
      mActivity.clear();
  }

  private boolean hasAccess()
  {
    return Build.VERSION.SDK_INT >= 30
      ? Environment.isExternalStorageManager()
      : ContextCompat.checkSelfPermission(mApp, Manifest.permission.READ_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED;
  }

  public void requestAccess(Activity activity)
  {
    if (Build.VERSION.SDK_INT >= 30)
    {
      Intent settings = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                   Uri.parse("package:" + mApp.getPackageName()));
      if (settings.resolveActivity(mApp.getPackageManager()) == null)
        settings = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
      activity.startActivity(settings);
    }
    else if (Build.VERSION.SDK_INT >= 23)
      activity.requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE}, 704);
  }

  private List<File> roots()
  {
    Set<File> roots = new LinkedHashSet<>();
    if (Build.VERSION.SDK_INT >= 30)
    {
      StorageManager storage = mApp.getSystemService(StorageManager.class);
      if (storage != null)
        for (StorageVolume volume : storage.getStorageVolumes())
          if (volume.isRemovable() && volume.getDirectory() != null
              && (Environment.MEDIA_MOUNTED.equals(volume.getState())
                  || Environment.MEDIA_MOUNTED_READ_ONLY.equals(volume.getState())))
            roots.add(volume.getDirectory());
    }
    else
    {
      for (File files : mApp.getExternalFilesDirs(null))
        if (files != null && Environment.isExternalStorageRemovable(files))
        {
          File root = files;
          for (int i = 0; i < 4 && root != null; ++i)
            root = root.getParentFile();
          if (root != null)
            roots.add(root);
        }
    }
    for (File root : mMounts)
      if (root.isDirectory())
        roots.add(root);
    return new ArrayList<>(roots);
  }

  public void scan()
  {
    if (!mReady || mState != State.IDLE)
      return;
    if (mScanning)
    {
      mScanAgain = true;
      return;
    }
    List<File> roots = roots();
    if (roots.isEmpty())
      return;
    if (!hasAccess())
    {
      if (!mPermissionDismissed)
      {
        mState = State.PERMISSION;
        changed();
      }
      return;
    }
    String[] versions = MapManager.nativeGetInstalledMapVersions();
    Map<String, Long> installed = new HashMap<>();
    for (int i = 0; i < versions.length; i += 2)
      installed.put(versions[i] + ".mwm", Long.parseLong(versions[i + 1]));
    mScanning = true;
    mWorker.execute(() -> {
      UsbMapFiles.Scan result;
      try
      {
        result = UsbMapFiles.scan(roots, installed);
      }
      catch (IOException e)
      {
        Logger.w(TAG, "Unable to scan removable maps", e);
        result = new UsbMapFiles.Scan();
      }
      UsbMapFiles.Scan scan = result;
      mMain.post(() -> {
        mScanning = false;
        if (!scan.entries.isEmpty() || !scan.errors.isEmpty())
        {
          mEntries = scan.entries;
          mErrors.clear();
          mErrors.addAll(scan.errors);
          mInstalled = 0;
          mProgress = 0;
          mCurrentFile = "";
          mDestination = new File(Framework.nativeGetWritableDir());
          mState = mEntries.isEmpty() ? State.FINISHED : State.COPYING;
          mCopyStarted = false;
          mCancelled.set(false);
          changed();
        }
        if (mScanAgain)
        {
          mScanAgain = false;
          scan();
        }
      });
    });
  }

  private void showDialog()
  {
    MwmActivity activity = mActivity.get();
    if (!mReady || activity == null || activity.isFinishing() || activity.getSupportFragmentManager().isStateSaved())
      return;
    var current = activity.getSupportFragmentManager().findFragmentByTag(UsbMapImportDialog.TAG);
    if (mState == State.IDLE)
    {
      if (current instanceof UsbMapImportDialog dialog)
        dialog.dismiss();
      return;
    }
    if (current == null)
      new UsbMapImportDialog().showNow(activity.getSupportFragmentManager(), UsbMapImportDialog.TAG);
    if (mState == State.COPYING && !mCopyStarted)
    {
      mCopyStarted = true;
      ContextCompat.startForegroundService(mApp, new Intent(mApp, UsbMapImportService.class));
    }
  }

  public void copyMaps()
  {
    if (mState != State.COPYING)
      return;
    List<UsbMapFiles.Entry> entries = new ArrayList<>(mEntries);
    File destination = mDestination;
    mWorker.execute(() -> {
      List<UsbMapFiles.Prepared> prepared = new ArrayList<>();
      List<String> failed = new ArrayList<>();
      long total = 0;
      for (UsbMapFiles.Entry entry : entries)
        total += entry.metadata.size * 2;
      final long work = total;
      long completed = 0;
      long[] lastUpdate = {0};
      for (UsbMapFiles.Entry entry : entries)
      {
        if (mCancelled.get())
          break;
        final long before = completed;
        try
        {
          prepared.add(UsbMapFiles.copy(entry, destination, mCancelled, (name, copied, bytes) -> {
            long now = System.nanoTime();
            if (now - lastUpdate[0] < 200000000 && copied != bytes)
              return;
            lastUpdate[0] = now;
            mMain.post(() -> {
              mCurrentFile = name;
              mProgress = (int) ((before + copied) * 1000 / work);
              changed();
            });
          }));
        }
        catch (IOException e)
        {
          failed.add(entry.source.getName());
          Logger.w(TAG, "Map copy failed: " + entry.source.getName(), e);
        }
        completed += entry.metadata.size * 2;
      }
      mMain.post(() -> {
        mPrepared = prepared;
        mErrors.addAll(failed);
        mState = State.WAITING;
        applyWhenIdle();
      });
    });
  }

  private void applyWhenIdle()
  {
    if (mCancelled.get() || !mDestination.equals(new File(Framework.nativeGetWritableDir())))
    {
      for (UsbMapFiles.Prepared file : mPrepared)
        file.staged.delete();
      mCurrentFile = mApp.getString(R.string.usb_maps_cancelled);
      finish();
      return;
    }
    if (mPrepared.isEmpty())
    {
      finish();
      return;
    }
    if (!MapManager.nativeBeginMapImport())
    {
      changed();
      mMain.postDelayed(this::applyWhenIdle, 1000);
      return;
    }
    try
    {
      for (UsbMapFiles.Prepared file : mPrepared)
      {
        try
        {
          if (UsbMapFiles.install(file))
            ++mInstalled;
        }
        catch (IOException e)
        {
          file.staged.delete();
          mErrors.add(file.entry.source.getName());
          Logger.w(TAG, "Map installation failed", e);
        }
      }
    }
    finally
    {
      MapManager.nativeEndMapImport();
      finish();
    }
  }

  private void finish()
  {
    mPrepared.clear();
    mState = State.FINISHED;
    mProgress = 1000;
    changed();
    mApp.stopService(new Intent(mApp, UsbMapImportService.class));
  }
  public void cancel()
  {
    mCancelled.set(true);
  }
  public void dismiss()
  {
    mPermissionDismissed = true;
    mState = State.IDLE;
    if (mScanAgain)
    {
      mScanAgain = false;
      scan();
    }
  }
  private void changed()
  {
    for (Runnable listener : new ArrayList<>(mListeners))
      listener.run();
    showDialog();
  }
}
