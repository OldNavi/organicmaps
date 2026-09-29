package app.organicmaps.downloader;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class UsbMapFilesTest
{
  @Rule
  public TemporaryFolder temporary = new TemporaryFolder();
  private static final long VERSION = 1790626504L;

  private static void varuint(ByteArrayOutputStream out, long value)
  {
    while (value >= 128)
    {
      out.write((int) value | 128);
      value >>>= 7;
    }
    out.write((int) value);
  }
  private File map(File directory, String name, long timestamp) throws IOException
  {
    assertTrue(directory.isDirectory() || directory.mkdirs());
    File file = new File(directory, name);
    ByteArrayOutputStream version = new ByteArrayOutputStream();
    version.write(new byte[] {77, 87, 77, 0});
    varuint(version, 10);
    varuint(version, timestamp);
    try (RandomAccessFile out = new RandomAccessFile(file, "rw"))
    {
      long table = 8 + version.size();
      out.writeLong(Long.reverseBytes(table));
      out.write(version.toByteArray());
      ByteArrayOutputStream directoryBytes = new ByteArrayOutputStream();
      varuint(directoryBytes, 4);
      for (String tag : Arrays.asList("version", "header", "features", "idx"))
      {
        varuint(directoryBytes, tag.length());
        directoryBytes.write(tag.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        varuint(directoryBytes, 8);
        varuint(directoryBytes, version.size());
      }
      out.write(directoryBytes.toByteArray());
    }
    return file;
  }
  private File usb() throws IOException
  {
    return temporary.newFolder();
  }
  private File folder(File usb)
  {
    return new File(usb, "omdata/maps");
  }

  @Test
  public void readsTimestampInSecondsNotFileMtime() throws Exception
  {
    File file = map(usb(), "Moscow.mwm", VERSION);
    assertTrue(file.setLastModified(0));
    assertEquals(VERSION, MwmMetadata.read(file).timestamp);
    assertEquals("260928", MwmMetadata.read(file).version);
  }
  @Test
  public void selectsMissingAndNewerButNotEqualOrOlder() throws Exception
  {
    File root = usb();
    map(folder(root), "Missing.mwm", VERSION);
    map(folder(root), "Update.mwm", VERSION + 1);
    map(folder(root), "Equal.mwm", VERSION);
    map(folder(root), "Old.mwm", VERSION - 1);
    Map<String, Long> installed = new HashMap<>();
    for (String name : Arrays.asList("Update", "Equal", "Old"))
      installed.put(name + ".mwm", VERSION);
    UsbMapFiles.Scan scan = UsbMapFiles.scan(Collections.singletonList(root), installed);
    assertEquals(2, scan.entries.size());
    assertFalse(scan.entries.get(0).update);
    assertTrue(scan.entries.get(1).update);
  }
  @Test
  public void newestDuplicateWinsAcrossDrives() throws Exception
  {
    File first = usb();
    File second = usb();
    map(folder(first), "Moscow.mwm", VERSION);
    map(folder(second), "Moscow.mwm", VERSION + 1);
    UsbMapFiles.Scan scan = UsbMapFiles.scan(Arrays.asList(first, second, first), Collections.emptyMap());
    assertEquals(1, scan.entries.size());
    assertEquals(VERSION + 1, scan.entries.get(0).metadata.timestamp);
  }
  @Test
  public void rejectsBrokenFilesAndDoesNotScanOtherFolders() throws Exception
  {
    File root = usb();
    map(root, "Outside.mwm", VERSION);
    File broken = map(folder(root), "Broken.mwm", VERSION);
    try (RandomAccessFile out = new RandomAccessFile(broken, "rw"))
    {
      out.setLength(9);
    }
    UsbMapFiles.Scan scan = UsbMapFiles.scan(Collections.singletonList(root), Collections.emptyMap());
    assertTrue(scan.entries.isEmpty());
    assertEquals(Collections.singletonList("Broken.mwm"), scan.errors);
  }
  @Test
  public void rejectsPathEscapes() throws Exception
  {
    File root = usb();
    File outside = map(usb(), "Moscow.mwm", VERSION);
    assertTrue(folder(root).mkdirs());
    Files.createSymbolicLink(new File(folder(root), outside.getName()).toPath(), outside.toPath());
    assertTrue(UsbMapFiles.scan(Collections.singletonList(root), Collections.emptyMap()).entries.isEmpty());
  }
  @Test
  public void copyDoesNotReplaceMapBeforeInstallation() throws Exception
  {
    File source = map(usb(), "Moscow.mwm", VERSION + 1);
    File destination = usb();
    File old = map(new File(destination, "260928"), "Moscow.mwm", VERSION);
    UsbMapFiles.Entry entry = new UsbMapFiles.Entry(source, MwmMetadata.read(source), true);
    long[] progress = {0};
    UsbMapFiles.Prepared prepared = UsbMapFiles.copy(entry, destination, new AtomicBoolean(), (name, copied, total) -> {
      assertTrue(copied <= total);
      progress[0] = copied;
    });
    assertEquals(VERSION, MwmMetadata.read(old).timestamp);
    assertEquals(entry.metadata.size * 2, progress[0]);
    UsbMapFiles.install(prepared);
    assertEquals(VERSION + 1, MwmMetadata.read(old).timestamp);
    assertTrue(new File(old.getPath() + UsbMapFiles.MARKER).isFile());
    assertFalse(prepared.staged.exists());
  }
  @Test
  public void cancelledCopyPreservesOldMapAndRemovesTemporaryFile() throws Exception
  {
    File source = map(usb(), "Moscow.mwm", VERSION + 1);
    File destination = usb();
    File old = map(new File(destination, "260928"), "Moscow.mwm", VERSION);
    try
    {
      UsbMapFiles.copy(new UsbMapFiles.Entry(source, MwmMetadata.read(source), true), destination,
                       new AtomicBoolean(true), (name, copied, total) -> {});
      fail("Cancellation must abort copying");
    }
    catch (IOException expected)
    {}
    assertEquals(VERSION, MwmMetadata.read(old).timestamp);
    assertFalse(new File(old.getPath() + ".usb-copy").exists());
  }
  @Test
  public void removedSourcePreservesOldMap() throws Exception
  {
    File source = map(usb(), "Moscow.mwm", VERSION + 1);
    UsbMapFiles.Entry entry = new UsbMapFiles.Entry(source, MwmMetadata.read(source), true);
    assertTrue(source.delete());
    File destination = usb();
    File old = map(new File(destination, "260928"), "Moscow.mwm", VERSION);
    try
    {
      UsbMapFiles.copy(entry, destination, new AtomicBoolean(), (name, copied, total) -> {});
      fail("Removed media must abort copying");
    }
    catch (IOException expected)
    {}
    assertEquals(VERSION, MwmMetadata.read(old).timestamp);
  }
  @Test
  public void newerInstalledVersionWinsIfItChangesWhileCopying() throws Exception
  {
    File source = map(usb(), "Moscow.mwm", VERSION + 1);
    File destination = usb();
    UsbMapFiles.Prepared prepared = UsbMapFiles.copy(new UsbMapFiles.Entry(source, MwmMetadata.read(source), true),
                                                     destination, new AtomicBoolean(), (name, copied, total) -> {});
    File newer = map(new File(destination, "260928"), "Moscow.mwm", VERSION + 2);
    assertFalse(UsbMapFiles.install(prepared));
    assertEquals(VERSION + 2, MwmMetadata.read(newer).timestamp);
    assertFalse(prepared.staged.exists());
  }
  @Test
  public void destinationIsSelectedStorageAndUsesHeaderDate() throws Exception
  {
    File source = map(usb(), "Moscow.mwm", VERSION + 86400);
    File sd = temporary.newFolder("selected-sd");
    UsbMapFiles.Prepared prepared = UsbMapFiles.copy(new UsbMapFiles.Entry(source, MwmMetadata.read(source), false), sd,
                                                     new AtomicBoolean(), (name, copied, total) -> {});
    UsbMapFiles.install(prepared);
    assertEquals(new File(sd, "260929/Moscow.mwm"), prepared.target);
    assertEquals(VERSION + 86400, MwmMetadata.read(prepared.target).timestamp);
  }
  @Test
  public void replacesUnreadableInstalledMapWithVerifiedCopy() throws Exception
  {
    File source = map(usb(), "Moscow.mwm", VERSION);
    File destination = usb();
    File broken = map(new File(destination, "260928"), "Moscow.mwm", VERSION);
    try (RandomAccessFile out = new RandomAccessFile(broken, "rw"))
    {
      out.setLength(9);
    }
    UsbMapFiles.Prepared prepared = UsbMapFiles.copy(new UsbMapFiles.Entry(source, MwmMetadata.read(source), false),
                                                     destination, new AtomicBoolean(), (name, copied, total) -> {});
    assertTrue(UsbMapFiles.install(prepared));
    assertEquals(VERSION, MwmMetadata.read(broken).timestamp);
  }
}
