package app.organicmaps.downloader;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/** Reads only the bounded container directory and version section, without loading map geometry. */
public final class MwmMetadata
{
  public final long timestamp;
  public final String version;
  public final long size;

  private MwmMetadata(long timestamp, long size)
  {
    this.timestamp = timestamp;
    this.size = size;
    SimpleDateFormat date = new SimpleDateFormat("yyMMdd", Locale.ROOT);
    date.setTimeZone(TimeZone.getTimeZone("UTC"));
    version = date.format(new Date(timestamp * 1000));
  }

  public static MwmMetadata read(File file) throws IOException
  {
    try (RandomAccessFile input = new RandomAccessFile(file, "r"))
    {
      long length = input.length();
      long directory = Long.reverseBytes(input.readLong());
      if (directory < 8 || directory >= length)
        throw new IOException("Invalid MWM section directory");
      input.seek(directory);
      long count = varuint(input);
      if (count < 3 || count > 256)
        throw new IOException("Invalid MWM section count");
      Set<String> tags = new HashSet<>();
      long versionOffset = 0;
      long versionSize = 0;
      for (int i = 0; i < count; ++i)
      {
        long tagSize = varuint(input);
        if (tagSize < 1 || tagSize > 64)
          throw new IOException("Invalid MWM section name");
        byte[] name = new byte[(int) tagSize];
        input.readFully(name);
        String tag = new String(name, StandardCharsets.US_ASCII);
        long offset = varuint(input);
        long size = varuint(input);
        if (offset < 8 || offset > directory || size < 0 || size > directory - offset || !tags.add(tag))
          throw new IOException("Invalid MWM section bounds");
        if (tag.equals("version"))
        {
          versionOffset = offset;
          versionSize = size;
        }
      }
      if (!tags.contains("header") || !tags.contains("features") || !tags.contains("idx") || versionSize < 6)
        throw new IOException("Incomplete MWM file");
      input.seek(versionOffset);
      if (input.readInt() != 0x4d574d00)
        throw new IOException("Invalid MWM signature");
      long format = varuint(input);
      long timestamp = varuint(input);
      // The importer supports the current container/feature layout, not arbitrary future formats.
      if (format != 10 || timestamp <= 0 || timestamp > 0xffffffffL
          || input.getFilePointer() > versionOffset + versionSize)
        throw new IOException("Unsupported MWM version");
      return new MwmMetadata(timestamp, length);
    }
  }

  private static long varuint(RandomAccessFile input) throws IOException
  {
    long value = 0;
    for (int shift = 0; shift < 63; shift += 7)
    {
      int part = input.readUnsignedByte();
      value |= (long) (part & 127) << shift;
      if ((part & 128) == 0)
        return value;
    }
    throw new IOException("Invalid MWM integer");
  }
}
