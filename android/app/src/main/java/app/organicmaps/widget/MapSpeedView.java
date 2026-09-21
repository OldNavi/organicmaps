package app.organicmaps.widget;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Pair;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import app.organicmaps.cluster.NavigationProvider;
import app.organicmaps.sdk.util.StringUtils;
import app.organicmaps.sdk.widgets.speedlimit.SpeedLimitView;
import app.organicmaps.util.ThemeUtils;

/** Main-map instruments share the same cached road information as the cluster provider. */
public final class MapSpeedView extends LinearLayout
{
  private final Handler mHandler = new Handler(Looper.getMainLooper());
  private final Runnable mRefresh = this::refresh;
  private final TextView mSpeed;
  private final TextView mUnits;
  private final SpeedLimitView mLimit;
  private final int mTextColor;
  private boolean mReady;

  public MapSpeedView(Context context, @Nullable AttributeSet attrs)
  {
    super(context, attrs);
    setOrientation(HORIZONTAL);
    inflate(context, R.layout.map_speed_instruments, this);
    mSpeed = findViewById(R.id.map_speed_value);
    mUnits = findViewById(R.id.map_speed_units);
    mLimit = findViewById(R.id.map_speed_limit);
    mTextColor = ThemeUtils.getColor(context, android.R.attr.textColorPrimary);
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
    setTextIfChanged(mSpeed, speed != null ? formatted.first : "—");
    setTextIfChanged(mUnits, formatted.second);
    boolean exceeded = speed != null && limit > 0 && speed > limit;
    int color = exceeded ? ContextCompat.getColor(getContext(), R.color.base_red) : mTextColor;
    if (mSpeed.getCurrentTextColor() != color)
      mSpeed.setTextColor(color);
    int formattedLimit = limit > 0 ? StringUtils.nativeFormatSpeed(limit) : 0;
    // An unknown limit must not look like a restriction to zero.
    mLimit.setVisibility(formattedLimit > 0 ? VISIBLE : GONE);
    if (mLimit.getSpeedLimit() != formattedLimit || mLimit.isAlert() != exceeded)
    {
      mLimit.setSpeedLimit(formattedLimit, exceeded);
      mLimit.setContentDescription(formattedLimit > 0 ? formattedLimit + " " + formatted.second : null);
    }
    // Read only caches. This also expires stale data when GNSS/VHAL stops sending events.
    mHandler.postDelayed(mRefresh, 250);
  }

  private static void setTextIfChanged(TextView view, String text)
  {
    if (!text.contentEquals(view.getText()))
      view.setText(text);
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
