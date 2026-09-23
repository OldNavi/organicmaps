package app.organicmaps.road;

import static org.junit.Assert.*;

import app.organicmaps.sdk.road.RoadEventKind;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import org.junit.Test;

public class OpenSpeedCamImporterTest
{
  private static OpenSpeedCamImporter importer(String text)
  {
    OpenSpeedCamImporter importer = new OpenSpeedCamImporter();
    importer.open(new ByteArrayInputStream(text.getBytes(Charset.forName("windows-1251"))));
    return importer;
  }

  @Test
  public void normalizesEndpointRolesAndTravelDirection() throws Exception
  {
    try (var importer = importer("7,38.8,55.1,100,60,1,180,500,20 // boundary | Начало населенного пункта | Town\n"
                                 + "7,38.8001,55.1,100,0,1,0,500,20 // boundary | Конец населенного пункта | Town\n"
                                 + "7,38.8002,55.1,102,20,2,0,120,25 // bump | Искусственная неровность\n"
                                 + "8,38.8003,55.1,4,90,1,180,500,35 // average | Камера средней скорости 1\n"
                                 + "0,38.8004,55.1,4,90,1,180,500,35 // average | Камера средней скорости 2\n"))
    {
      var batch = importer.nextBatch();
      assertEquals(5, batch.identities.length);
      assertEquals(RoadEventKind.SETTLEMENT_START, batch.values[3], 0);
      assertEquals(0, batch.values[6], 0);
      assertEquals(RoadEventKind.SETTLEMENT_END, batch.values[12], 0);
      assertEquals(180, batch.values[15], 0);
      assertEquals(batch.identities[0], batch.identities[1]);
      assertEquals(RoadEventKind.BUMP, batch.values[21], 0);
      assertEquals(RoadEventKind.AVERAGE_START, batch.values[30], 0);
      assertEquals(RoadEventKind.AVERAGE_END, batch.values[39], 0);
      assertNull(importer.nextBatch());
    }
  }

  @Test
  public void rejectsInvalidExportAndAmbiguousRoles() throws Exception
  {
    for (String text :
         new String[] {"", "<html>login</html>", "{\"error\":\"unauthorized\"}",
                       "1,38,55,100,0,1,0,500,20 // id | Unknown role", "1,38,95,101,60,1,0,500,20 // id | Limit",
                       "1,38,55,101,-1,1,0,500,20 // id | Limit", "1,38,55,101,60,3,0,500,20 // id | Limit",
                       "1,38,55,101,60,1,0,500,20,0 // id | Limit", "1,NaN,55,101,60,1,0,500,20 // id | Limit"})
    {
      try (var importer = importer(text))
      {
        importer.nextBatch();
        fail(text);
      }
      catch (IOException expected)
      {}
    }
  }

  @Test
  public void streamsBatchesAndAcceptsAnExplicitEmptyExport() throws Exception
  {
    String header = "IDX,X,Y,TYPE,SPEED,DIRTYPE,DIRECTION,DISTANCE,ANGLE\n";
    try (var importer = importer(header))
    {
      assertNull(importer.nextBatch());
    }
    String row = "1,38,55,101,60,1,0,500,20 // id | Limit\n";
    try (var importer = importer(header + row.repeat(1025)))
    {
      assertEquals(512, importer.nextBatch().identities.length);
      assertEquals(512, importer.nextBatch().identities.length);
      assertEquals(1, importer.nextBatch().identities.length);
      assertNull(importer.nextBatch());
    }
  }
}
