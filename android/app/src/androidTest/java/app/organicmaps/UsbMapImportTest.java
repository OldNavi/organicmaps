package app.organicmaps;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import app.organicmaps.downloader.MwmMetadata;
import app.organicmaps.downloader.UsbMapFiles;
import app.organicmaps.downloader.UsbMapImportDialog;
import app.organicmaps.downloader.UsbMapImportManager;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.downloader.MapManager;
import java.io.File;
import java.io.RandomAccessFile;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Uses a real MWM supplied under usbTestRoot/omdata/maps/UsbImportTest.mwm. */
public class UsbMapImportTest
{
  private static void main(Runnable action)
  {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
  }
  private static MwmActivity awaitMap() throws InterruptedException
  {
    AtomicReference<MwmActivity> activity = new AtomicReference<>();
    long end = SystemClock.elapsedRealtime() + 30000;
    while (activity.get() == null && SystemClock.elapsedRealtime() < end)
    {
      main(() -> {
        for (Activity candidate : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
          if (candidate instanceof MwmActivity map)
            activity.set(map);
      });
      Thread.sleep(100);
    }
    assertNotNull(activity.get());
    return activity.get();
  }
  private static long varuint(RandomAccessFile input) throws Exception
  {
    long value = 0;
    for (int shift = 0; shift < 63; shift += 7)
    {
      int part = input.readUnsignedByte();
      value |= (long) (part & 127) << shift;
      if (part < 128)
        return value;
    }
    throw new AssertionError();
  }
  private static void setTimestamp(File file, long timestamp) throws Exception
  {
    try (RandomAccessFile data = new RandomAccessFile(file, "rw"))
    {
      data.seek(Long.reverseBytes(data.readLong()));
      long count = varuint(data);
      for (int i = 0; i < count; ++i)
      {
        byte[] name = new byte[(int) varuint(data)];
        data.readFully(name);
        long offset = varuint(data);
        long size = varuint(data);
        if (!new String(name, java.nio.charset.StandardCharsets.US_ASCII).equals("version"))
          continue;
        data.seek(offset + 4);
        varuint(data);
        long start = data.getFilePointer();
        while (timestamp >= 128)
        {
          data.write((int) timestamp | 128);
          timestamp >>>= 7;
        }
        data.write((int) timestamp);
        assertEquals(5, data.getFilePointer() - start);
        assertTrue(data.getFilePointer() <= offset + size);
        data.getFD().sync();
        return;
      }
    }
    fail("No version section");
  }
  private static void awaitFinished(UsbMapImportManager manager) throws Exception
  {
    AtomicReference<UsbMapImportManager.State> state = new AtomicReference<>();
    long end = SystemClock.elapsedRealtime() + 30000;
    do
    {
      main(() -> state.set(manager.state()));
      if (state.get() == UsbMapImportManager.State.FINISHED)
        return;
      Thread.sleep(100);
    }
    while (SystemClock.elapsedRealtime() < end);
    fail("Import did not finish: " + state.get());
  }
  private static long installedVersion()
  {
    String[] maps = MapManager.nativeGetInstalledMapVersions();
    for (int i = 0; i < maps.length; i += 2)
    {
      if (maps[i].equals("UsbImportTest"))
        return Long.parseLong(maps[i + 1]);
    }
    return 0;
  }
  private static void close(MwmActivity map, UsbMapImportManager manager)
  {
    manager.dismiss();
    var dialog = map.getSupportFragmentManager().findFragmentByTag(UsbMapImportDialog.TAG);
    if (dialog instanceof UsbMapImportDialog usb)
      usb.dismissNow();
  }

  @Test
  public void importsAndReplacesRealMapWithoutRoadDetailSections() throws Exception
  {
    String path = InstrumentationRegistry.getArguments().getString("usbTestRoot");
    assertNotNull("Supply usbTestRoot and a real MWM fixture", path);
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    File fixture = new File(path, "omdata/maps/UsbImportTest.mwm");
    assertTrue(fixture.isFile());
    File root = new File(context.getCacheDir(), "usb-import-" + java.util.UUID.randomUUID());
    File source = new File(root, "omdata/maps/UsbImportTest.mwm");
    assertTrue(source.getParentFile().mkdirs());
    try (var input = new java.io.FileInputStream(fixture); var output = new java.io.FileOutputStream(source))
    {
      byte[] buffer = new byte[65536];
      int count;
      while ((count = input.read(buffer)) != -1)
        output.write(buffer, 0, count);
    }
    long timestamp = System.currentTimeMillis() / 1000;
    setTimestamp(source, timestamp);
    main(
        () -> context.startActivity(new Intent(context, SplashActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
    MwmActivity map = awaitMap();
    UsbMapImportManager manager = MwmApplication.from(context).getUsbMapImport();
    AtomicReference<File> target = new AtomicReference<>();
    main(() -> target.set(new File(Framework.nativeGetWritableDir(), mwmDate(source) + "/UsbImportTest.mwm")));
    try
    {
      main(() -> manager.mediaChanged(new Intent(Intent.ACTION_MEDIA_MOUNTED, Uri.fromFile(root))));
      awaitFinished(manager);
      main(() -> {
        assertEquals(1, manager.installed());
        assertTrue(manager.errors().toString(), manager.errors().isEmpty());
        assertEquals(timestamp, installedVersion());
        close(map, manager);
      });
      assertTrue(target.get().isFile());
      assertEquals(timestamp, MwmMetadata.read(target.get()).timestamp);
      main(manager::scan);
      Thread.sleep(1200);
      main(() -> assertEquals(UsbMapImportManager.State.IDLE, manager.state()));

      setTimestamp(source, timestamp + 1);
      main(manager::scan);
      awaitFinished(manager);
      main(() -> {
        assertEquals(1, manager.installed());
        assertTrue(manager.errors().toString(), manager.errors().isEmpty());
        assertEquals(timestamp + 1, installedVersion());
      });
      assertEquals(timestamp + 1, MwmMetadata.read(target.get()).timestamp);
      assertSame(map, awaitMap());
    }
    finally
    {
      main(() -> {
        close(map, manager);
        manager.mediaChanged(new Intent(Intent.ACTION_MEDIA_UNMOUNTED, Uri.fromFile(root)));
        if (MapManager.nativeBeginMapImport())
        {
          target.get().delete();
          new File(target.get().getPath() + UsbMapFiles.MARKER).delete();
          MapManager.nativeEndMapImport();
        }
      });
      source.delete();
      source.getParentFile().delete();
      source.getParentFile().getParentFile().delete();
      root.delete();
    }
  }
  private static String mwmDate(File source)
  {
    try
    {
      return MwmMetadata.read(source).version;
    }
    catch (Exception e)
    {
      throw new AssertionError(e);
    }
  }
}
