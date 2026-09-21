package app.organicmaps.cluster;

import static org.junit.Assert.*;

import android.net.Uri;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import app.organicmaps.MwmApplication;
import app.organicmaps.sdk.location.LocationHelper;
import org.junit.Test;

public class DisplaySpeedProviderTest
{
  @Test
  public void mcuSpeedIsAvailableWithoutGpsAndExpiresInBothProviderPaths() throws Exception
  {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    var context = instrumentation.getTargetContext();
    var helper = MwmApplication.from(context).getLocationHelper();
    var field = LocationHelper.class.getDeclaredField("mDisplaySpeed");
    field.setAccessible(true);
    Object previous = field.get(helper);
    try
    {
      for (boolean fresh : new boolean[] {true, false})
      {
        long timestamp = SystemClock.elapsedRealtimeNanos() - (fresh ? 0 : 3_000_000_000L);
        field.set(helper, new LocationHelper.DisplaySpeed(20, "speedMCU", timestamp, 2_000_000_000L));
        for (String path : new String[] {"speed", "guidance"})
        {
          try (var cursor = context.getContentResolver().query(
                   Uri.withAppendedPath(NavigationProvider.CONTENT_URI, path), null, null, null, null))
          {
            assertNotNull(cursor);
            assertTrue(cursor.moveToFirst());
            assertEquals(fresh ? 1 : 0, cursor.getInt(cursor.getColumnIndexOrThrow("speed_valid")));
            assertEquals("m/s", cursor.getString(cursor.getColumnIndexOrThrow("speed_unit")));
            assertEquals(fresh ? "speedMCU" : "none", cursor.getString(cursor.getColumnIndexOrThrow("speed_source")));
            if (fresh)
              assertEquals(20, cursor.getDouble(cursor.getColumnIndexOrThrow("speed")), 0.0001);
            else
              assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("speed")));
          }
        }
      }
    }
    finally
    {
      field.set(helper, previous);
    }
  }
}
