package app.organicmaps.downloader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** File operations are separate from Android lifecycle and native map registration. */
public final class UsbMapFiles
{
  public static final String MARKER = ".imported";
  public static final class Entry
  {
    public final File source;
    public final MwmMetadata metadata;
    public final boolean update;
    Entry(File source, MwmMetadata metadata, boolean update)
    {
      this.source = source;
      this.metadata = metadata;
      this.update = update;
    }
  }
  public static final class Scan
  {
    public final List<Entry> entries = new ArrayList<>();
    public final List<String> errors = new ArrayList<>();
  }
  public interface Progress
  {
    void update(String file, long copied, long total);
  }
  public static final class Prepared
  {
    public final Entry entry;
    public final File staged;
    public final File target;
    Prepared(Entry entry, File staged, File target)
    {
      this.entry = entry;
      this.staged = staged;
      this.target = target;
    }
  }

  public static Scan scan(List<File> roots, Map<String, Long> installed) throws IOException
  {
    Scan result = new Scan();
    Map<String, Entry> newest = new LinkedHashMap<>();
    for (File root : roots)
    {
      File folder = new File(root, "omdata/maps").getCanonicalFile();
      if (!folder.getPath().startsWith(root.getCanonicalPath() + File.separator))
        continue;
      File[] files = folder.listFiles((dir, name) -> name.endsWith(".mwm"));
      if (files == null)
        continue;
      Arrays.sort(files, Comparator.comparing(File::getName));
      for (File file : files)
      {
        try
        {
          if (!file.isFile() || !file.getCanonicalFile().getParentFile().equals(folder))
            continue;
          MwmMetadata metadata = MwmMetadata.read(file);
          Long previous = installed.get(file.getName());
          if (previous != null && metadata.timestamp <= previous)
            continue;
          Entry selected = newest.get(file.getName());
          if (selected == null || metadata.timestamp > selected.metadata.timestamp)
            newest.put(file.getName(), new Entry(file, metadata, previous != null));
        }
        catch (IOException e)
        {
          result.errors.add(file.getName());
        }
      }
    }
    result.entries.addAll(newest.values());
    return result;
  }

  public static Prepared copy(Entry entry, File root, AtomicBoolean cancelled, Progress progress) throws IOException
  {
    File directory = new File(root, entry.metadata.version);
    if (!directory.isDirectory() && !directory.mkdirs())
      throw new IOException("Cannot create map directory");
    File target = new File(directory, entry.source.getName());
    File staged = new File(directory, entry.source.getName() + ".usb-copy");
    if (staged.exists() && !staged.delete())
      throw new IOException("Cannot remove an interrupted import");
    if (directory.getUsableSpace() < entry.metadata.size)
      throw new IOException("Not enough storage space");
    boolean success = false;
    try
    {
      MessageDigest sourceHash = digest();
      byte[] buffer = new byte[1024 * 1024];
      long copied = 0;
      try (FileInputStream input = new FileInputStream(entry.source);
           FileOutputStream output = new FileOutputStream(staged))
      {
        int count;
        while ((count = input.read(buffer)) != -1)
        {
          if (cancelled.get())
            throw new IOException("Import cancelled");
          copied += count;
          if (copied > entry.metadata.size)
            throw new IOException("Source map changed during copying");
          sourceHash.update(buffer, 0, count);
          output.write(buffer, 0, count);
          progress.update(entry.source.getName(), copied, entry.metadata.size * 2);
        }
        output.getFD().sync();
      }
      if (copied != entry.metadata.size || MwmMetadata.read(staged).timestamp != entry.metadata.timestamp)
        throw new IOException("Source map changed during copying");
      MessageDigest targetHash = digest();
      try (FileInputStream input = new FileInputStream(staged))
      {
        int count;
        while ((count = input.read(buffer)) != -1)
        {
          if (cancelled.get())
            throw new IOException("Import cancelled");
          targetHash.update(buffer, 0, count);
          copied += count;
          progress.update(entry.source.getName(), copied, entry.metadata.size * 2);
        }
      }
      if (!Arrays.equals(sourceHash.digest(), targetHash.digest()))
        throw new IOException("Copied map checksum mismatch");
      success = true;
      return new Prepared(entry, staged, target);
    }
    finally
    {
      if (!success)
        staged.delete();
    }
  }

  /** Called only while native maps are unloaded; each completed file survives a later failure. */
  public static boolean install(Prepared prepared) throws IOException
  {
    if (prepared.target.isFile())
    {
      try
      {
        if (MwmMetadata.read(prepared.target).timestamp >= prepared.entry.metadata.timestamp)
        {
          prepared.staged.delete();
          return false;
        }
      }
      catch (IOException ignored)
      {
        // An unreadable installed map is absent from the native map registry and can be repaired.
      }
    }
    File marker = new File(prepared.target.getPath() + MARKER);
    // Presence marks a locally imported map, independent of the download catalogue's date and hash.
    try (FileOutputStream output = new FileOutputStream(marker))
    {
      output.write(
          Long.toString(prepared.entry.metadata.timestamp).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      output.getFD().sync();
    }
    // Android rename on the same filesystem atomically replaces the destination inode.
    if (!prepared.staged.renameTo(prepared.target))
      throw new IOException("Cannot install copied map");
    File indexes = new File(prepared.target.getParentFile(),
                            prepared.target.getName().substring(0, prepared.target.getName().length() - 4));
    for (String extension : new String[] {".bftsegbits", ".bftsegnodes", ".offsets"})
      new File(indexes, indexes.getName() + extension).delete();
    return true;
  }

  private static MessageDigest digest()
  {
    try
    {
      return MessageDigest.getInstance("SHA-256");
    }
    catch (NoSuchAlgorithmException e)
    {
      throw new AssertionError(e);
    }
  }
  private UsbMapFiles() {}
}
