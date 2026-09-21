package app.organicmaps.widget;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Pair;
import android.view.View;
import android.widget.LinearLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import app.organicmaps.cluster.NavigationProvider;
import app.organicmaps.sdk.util.StringUtils;
import app.organicmaps.sdk.widgets.speedlimit.SpeedLimitView;

/** Main-map instruments share the same cached road information as the cluster provider. */
public final class MapSpeedView extends LinearLayout
{
  private final Handler mHandler = new Handler(Looper.getMainLooper());
  private final Runnable mRefresh = this::refresh;
  private final SpeedLimitView mSpeed;
  private final SpeedLimitView mLimit;
  private boolean mReady;

  public MapSpeedView(Context context, @Nullable AttributeSet attrs)
  {
    super(context, attrs);
    setOrientation(HORIZONTAL);
    inflate(context, R.layout.map_speed_instruments, this);
    mSpeed = findViewById(R.id.map_speed_value);
    mSpeed.setSpeedLimit(-1, false);
    mLimit = findViewById(R.id.map_speed_limit);
    mSpeed.setContentDescription("--");
    mLimit.setContentDescription("--");
    mReady = true;
  }

  private void refresh()
  {
    if (!isAttachedToWindow() || getWindowVisibility() != VISIBLE || !isShown())
      return;
    var reading = MwmApplication.from(getContext()).getLocationHelper().getDisplaySpeed();
    Double speed = reading == null ? null : reading.speedMps();
    double limit = NavigationProvider.getCurrentSpeedLimitMps();
    Pair<String, String> formatted = StringUtils.nativeFormatSpeedAndUnits(speed != null ? speed : 0.0);
    boolean exceeded = speed != null && limit > 0 && speed > limit;
    int displayedSpeed = speed == null ? -1 : StringUtils.nativeFormatSpeed(speed);
    if (mSpeed.getSpeedLimit() != displayedSpeed || mSpeed.isAlert() != exceeded)
    {
      mSpeed.setSpeedLimit(displayedSpeed, exceeded);
      mSpeed.setContentDescription(speed == null ? "--" : formatted.first + " " + formatted.second);
    }
    int formattedLimit = limit > 0 ? StringUtils.nativeFormatSpeed(limit) : 0;
    if (mLimit.getSpeedLimit() != formattedLimit || mLimit.isAlert() != exceeded)
    {
      mLimit.setSpeedLimit(formattedLimit, exceeded);
      mLimit.setContentDescription(formattedLimit > 0 ? formattedLimit + " " + formatted.second : "--");
    }
    // Read only caches. This also expires stale data when GNSS/VHAL stops sending events.
    mHandler.postDelayed(mRefresh, 250);
  }

  private void updateRefreshing()
  {
    if (!mReady || isInEditMode())
      return;
    mHandler.removeCallbacks(mRefresh);
    if (isAttachedToWindow() && getWindowVisibility() == VISIBLE && isShown())
      mHandler.post(mRefresh);
  }

  @Override
  protected void onAttachedToWindow()
  {
    super.onAttachedToWindow();
    updateRefreshing();
  }

  @Override
  protected void onDetachedFromWindow()
  {
    mHandler.removeCallbacks(mRefresh);
    super.onDetachedFromWindow();
  }

  @Override
  protected void onVisibilityChanged(@NonNull View changedView, int visibility)
  {
    super.onVisibilityChanged(changedView, visibility);
    updateRefreshing();
  }

  @Override
  protected void onWindowVisibilityChanged(int visibility)
  {
    super.onWindowVisibilityChanged(visibility);
    updateRefreshing();
  }
}
