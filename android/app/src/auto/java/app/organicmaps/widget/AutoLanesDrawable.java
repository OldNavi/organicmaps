package app.organicmaps.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import app.organicmaps.R;
import app.organicmaps.sdk.routing.LaneInfo;
import app.organicmaps.sdk.routing.LaneWay;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class AutoLanesDrawable extends Drawable
{
  private final Drawable[] mLanes;
  private final int mLaneWidth;
  private final int mLaneHeight;
  private final int mGap;

  AutoLanesDrawable(Context context, LaneInfo[] lanes, int activeColor, int inactiveColor)
  {
    float density = context.getResources().getDisplayMetrics().density;
    mLaneWidth = Math.round(36 * density);
    mLaneHeight = Math.round(40 * density);
    mGap = Math.round(4 * density);
    mLanes = new Drawable[lanes.length];
    for (int i = 0; i < lanes.length; ++i)
    {
      LaneInfo lane = lanes[i];
      List<Drawable> branches = new ArrayList<>();
      // Draw inactive branches first so they cannot cover the active arrow's shared stem.
      for (LaneWay way : lane.mLaneWays)
        if (way != LaneWay.None && way != lane.mActiveLaneWay)
          branches.add(branch(context, way, lane.mLaneWays.length > 1, lanes.length > 1, inactiveColor));
      if (lane.mActiveLaneWay != LaneWay.None)
        branches.add(branch(context, lane.mActiveLaneWay, lane.mLaneWays.length > 1, lanes.length > 1, activeColor));
      mLanes[i] = new LayerDrawable(branches.toArray(new Drawable[0]));
      int left = i * (mLaneWidth + mGap);
      mLanes[i].setBounds(left, 0, left + mLaneWidth, mLaneHeight);
    }
  }

  private static Drawable branch(Context context, LaneWay way, boolean compound, boolean multipleLanes, int color)
  {
    boolean large = compound ? way == LaneWay.Through || way == LaneWay.SlightLeft || way == LaneWay.SlightRight
                             : !multipleLanes || (way != LaneWay.Left && way != LaneWay.Right);
    Drawable drawable = Objects.requireNonNull(AppCompatResources.getDrawable(context, resource(way, large))).mutate();
    drawable.setTint(color);
    return drawable;
  }

  @DrawableRes
  private static int resource(LaneWay way, boolean large)
  {
    return switch (way)
    {
      case ReverseLeft -> large ? R.drawable.auto_lane_left180_large : R.drawable.auto_lane_left180_small;
      case SharpLeft -> large ? R.drawable.auto_lane_left135_large : R.drawable.auto_lane_left135_small;
      case Left -> large ? R.drawable.auto_lane_left90_large : R.drawable.auto_lane_left90_small;
      case MergeToLeft -> large ? R.drawable.auto_lane_leftshift_large : R.drawable.auto_lane_leftshift_small;
      case SlightLeft -> large ? R.drawable.auto_lane_left45_large : R.drawable.auto_lane_left45_small;
      case Through -> large ? R.drawable.auto_lane_straightahead_large : R.drawable.auto_lane_straightahead_small;
      case SlightRight -> large ? R.drawable.auto_lane_right45_large : R.drawable.auto_lane_right45_small;
      case MergeToRight -> large ? R.drawable.auto_lane_rightshift_large : R.drawable.auto_lane_rightshift_small;
      case Right -> large ? R.drawable.auto_lane_right90_large : R.drawable.auto_lane_right90_small;
      case SharpRight -> large ? R.drawable.auto_lane_right135_large : R.drawable.auto_lane_right135_small;
      case ReverseRight -> large ? R.drawable.auto_lane_right180_large : R.drawable.auto_lane_right180_small;
      case None -> throw new IllegalArgumentException("A missing lane direction has no arrow");
    };
  }

  @Override
  public int getIntrinsicWidth()
  {
    return mLaneWidth * mLanes.length + mGap * (mLanes.length - 1);
  }

  @Override
  public int getIntrinsicHeight()
  {
    return mLaneHeight;
  }

  @Override
  public void setBounds(int left, int top, int right, int bottom)
  {
    float scale = Math.min((float) (right - left) / getIntrinsicWidth(), (float) (bottom - top) / mLaneHeight);
    int width = Math.round(getIntrinsicWidth() * scale);
    int height = Math.round(mLaneHeight * scale);
    int x = left + (right - left - width) / 2;
    int y = top + (bottom - top - height) / 2;
    super.setBounds(x, y, x + width, y + height);
  }

  @Override
  public void draw(@NonNull Canvas canvas)
  {
    int save = canvas.save();
    canvas.translate(getBounds().left, getBounds().top);
    canvas.scale((float) getBounds().width() / getIntrinsicWidth(), (float) getBounds().height() / mLaneHeight);
    for (Drawable lane : mLanes)
      lane.draw(canvas);
    canvas.restoreToCount(save);
  }

  @Override
  public void setAlpha(int alpha)
  {
    for (Drawable lane : mLanes)
      lane.setAlpha(alpha);
    invalidateSelf();
  }

  @Override
  public void setColorFilter(@Nullable ColorFilter filter)
  {
    for (Drawable lane : mLanes)
      lane.setColorFilter(filter);
    invalidateSelf();
  }

  @Override
  public int getOpacity()
  {
    return PixelFormat.TRANSLUCENT;
  }
}
