package app.organicmaps.road;

import app.organicmaps.sdk.road.RoadEventBatch;
import java.io.IOException;
import java.io.InputStream;

/** Provider-specific parsing stops here; the database only sees normalized batches. */
public interface RoadEventImporter extends AutoCloseable
{
  void open(InputStream input) throws IOException;
  RoadEventBatch nextBatch() throws IOException;
  @Override
  void close() throws IOException;
}
