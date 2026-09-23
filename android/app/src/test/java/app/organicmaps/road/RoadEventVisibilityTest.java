package app.organicmaps.road;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.content.SharedPreferences;
import app.organicmaps.sdk.road.RoadEventKind;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class RoadEventVisibilityTest
{
  @Test
  public void legacyCategoriesMigrateOnceAndProfilesRemainIndependent()
  {
    Map<String, Integer> values = new HashMap<>();
    values.put(RoadDataManager.CATEGORIES, (1 << 4) | (1 << 5));
    SharedPreferences prefs = mock(SharedPreferences.class);
    SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
    when(prefs.contains(anyString())).thenAnswer(call -> values.containsKey(call.getArgument(0)));
    when(prefs.getInt(anyString(), anyInt()))
        .thenAnswer(call -> values.getOrDefault(call.getArgument(0), call.getArgument(1)));
    when(prefs.edit()).thenReturn(editor);
    when(editor.putInt(anyString(), anyInt())).thenAnswer(call -> {
      values.put(call.getArgument(0), call.getArgument(1));
      return editor;
    });
    RoadEventVisibility.migrate(prefs);
    int expected = (1 << RoadEventKind.BUMP) | (1 << RoadEventKind.CROSSING) | (1 << RoadEventKind.CHILDREN);
    assertEquals(expected, RoadEventVisibility.get(prefs, false));
    assertEquals(expected, RoadEventVisibility.get(prefs, true));
    values.put(RoadEventVisibility.ROUTE, 1 << RoadEventKind.CAMERA);
    RoadEventVisibility.migrate(prefs);
    assertEquals(expected, RoadEventVisibility.get(prefs, false));
    assertEquals(1 << RoadEventKind.CAMERA, RoadEventVisibility.get(prefs, true));
  }
}
