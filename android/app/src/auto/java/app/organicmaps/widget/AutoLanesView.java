package app.organicmaps.widget;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.routing.LaneInfo;
import app.organicmaps.sdk.widgets.lanes.LanesView;
import java.util.Arrays;

/** Automotive lane signs keep all branches, with the recommended branch drawn on top. */
public final class AutoLanesView extends LanesView
{
  @Nullable
  private LaneInfo[] mLastLanes;
  private boolean mInitialized;

  public AutoLanesView(Context context, @Nullable AttributeSet attrs)
  {
    super(context, attrs);
  }

  @Override
  public void setLanes(@Nullable LaneInfo[] lanes)
  {
    if (mInitialized && sameLanes(lanes, mLastLanes))
      return;
    super.setLanes(lanes);
    mInitialized = true;
    mLastLanes = lanes == null ? null : new LaneInfo[lanes.length];
    if (lanes != null)
      for (int i = 0; i < lanes.length; ++i)
        mLastLanes[i] = new LaneInfo(lanes[i].mLaneWays.clone(), lanes[i].mActiveLaneWay);
  }

  private static boolean sameLanes(@Nullable LaneInfo[] lanes, @Nullable LaneInfo[] previous)
  {
    int count = lanes == null ? 0 : lanes.length;
    if (count != (previous == null ? 0 : previous.length))
      return false;
    for (int i = 0; i < count; ++i)
      if (lanes[i].mActiveLaneWay != previous[i].mActiveLaneWay
          || !Arrays.equals(lanes[i].mLaneWays, previous[i].mLaneWays))
        return false;
    return true;
  }

  @NonNull
  @Override
  protected Drawable createLanesDrawable(@NonNull LaneInfo[] lanes, int activeColor, int inactiveColor)
  {
    return new AutoLanesDrawable(getContext(), lanes, activeColor, inactiveColor);
  }
}
