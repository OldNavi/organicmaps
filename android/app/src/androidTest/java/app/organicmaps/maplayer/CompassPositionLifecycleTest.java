package app.organicmaps.maplayer;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import app.organicmaps.MwmActivity;
import app.organicmaps.R;
import app.organicmaps.SplashActivity;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class CompassPositionLifecycleTest
{
  private static void main(Runnable action)
  {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
  }

  private static MwmActivity awaitMap(MwmActivity previous) throws InterruptedException
  {
    AtomicReference<MwmActivity> result = new AtomicReference<>();
    long deadline = SystemClock.elapsedRealtime() + 20000;
    while (SystemClock.elapsedRealtime() < deadline)
    {
      main(() -> {
        for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
          if (activity instanceof MwmActivity map && map != previous)
            result.set(map);
      });
      if (result.get() != null)
        return result.get();
      Thread.sleep(50);
    }
    throw new AssertionError("Map activity did not resume");
  }

  private static MwmActivity launchMap() throws InterruptedException
  {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    context.startActivity(new Intent(context, SplashActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    return awaitMap(null);
  }

  @Test
  public void subscriptionMovesBetweenViewTreesAndLateCallbacksDoNothing() throws Exception
  {
    MwmActivity activity = launchMap();
    main(() -> {
      ViewGroup parent = activity.findViewById(R.id.coordinator);
      View view = new FrameLayout(activity);
      AtomicInteger calls = new AtomicInteger();
      CompassPositionObserver observer = new CompassPositionObserver(view, calls::incrementAndGet);
      try
      {
        observer.onPreDraw();
        assertEquals(0, calls.get());
        parent.addView(view, new ViewGroup.LayoutParams(24, 24));
        var attachedTree = view.getViewTreeObserver();
        attachedTree.dispatchOnPreDraw();
        assertEquals(1, calls.get());
        parent.removeView(view);
        assertTrue(attachedTree.isAlive());
        attachedTree.dispatchOnPreDraw();
        observer.onPreDraw();
        assertEquals("Detached callback must neither run nor remain subscribed", 1, calls.get());
        parent.addView(view, new ViewGroup.LayoutParams(24, 24));
        view.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(2, calls.get());
        observer.close();
        observer.close();
        attachedTree.dispatchOnPreDraw();
        observer.onPreDraw();
        parent.removeView(view);
        parent.addView(view, new ViewGroup.LayoutParams(24, 24));
        view.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals("Destroyed view must never resubscribe", 2, calls.get());
      }
      finally
      {
        observer.close();
        parent.removeView(view);
      }
    });
  }

  @Test
  public void replacingButtonsAndRecreatingActivityCannotLeaveDetachedFragmentCallbacks() throws Exception
  {
    MwmActivity activity = launchMap();
    Thread.sleep(300);
    for (int recreation = 0; recreation < 3; ++recreation)
    {
      MwmActivity current = activity;
      main(() -> {
        var manager = current.getSupportFragmentManager();
        manager.executePendingTransactions();
        var model = new ViewModelProvider(current).get(MapButtonsViewModel.class);
        for (int i = 0; i < 8; ++i)
        {
          var old = manager.findFragmentById(R.id.map_buttons);
          assertNotNull(old);
          View oldView = old.requireView();
          View container = current.findViewById(R.id.map_buttons);
          oldView.measure(View.MeasureSpec.makeMeasureSpec(container.getWidth(), View.MeasureSpec.EXACTLY),
                          View.MeasureSpec.makeMeasureSpec(container.getHeight(), View.MeasureSpec.EXACTLY));
          oldView.layout(0, 0, container.getWidth(), container.getHeight());
          assertTrue("Exercise the old callback's nonzero-width branch",
                     oldView.findViewById(R.id.my_position).getWidth() > 0);
          var windowTree = oldView.getViewTreeObserver();
          model.setLayoutMode(i % 2 == 0 ? MapButtonsController.LayoutMode.navigation
                                         : MapButtonsController.LayoutMode.regular);
          manager.executePendingTransactions();
          assertNotSame(old, manager.findFragmentById(R.id.map_buttons));
          assertFalse(old.isAdded());
          assertNull(old.getActivity());
          assertTrue(windowTree.isAlive());
          windowTree.dispatchOnPreDraw(); // Reproduces the user's late callback after fragment replacement.
        }
      });
      if (recreation < 2)
      {
        main(current::recreate);
        activity = awaitMap(current);
        Thread.sleep(300);
      }
    }
  }
}
