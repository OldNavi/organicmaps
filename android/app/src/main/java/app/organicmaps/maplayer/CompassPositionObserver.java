package app.organicmaps.maplayer;

import android.view.View;
import android.view.ViewTreeObserver;
import androidx.annotation.Nullable;

/** Owns a pre-draw subscription for one fragment view, including detach/reattach during transitions. */
final class CompassPositionObserver
    implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener, AutoCloseable
{
  private final View mView;
  @Nullable
  private Runnable mUpdate;
  @Nullable
  private ViewTreeObserver mObserver;

  CompassPositionObserver(View view, Runnable update)
  {
    mView = view;
    mUpdate = update;
    view.addOnAttachStateChangeListener(this);
    if (view.isAttachedToWindow())
      onViewAttachedToWindow(view);
  }

  @Override
  public void onViewAttachedToWindow(View view)
  {
    unregister();
    if (mUpdate == null)
      return;
    mObserver = view.getViewTreeObserver();
    mObserver.addOnPreDrawListener(this);
  }

  @Override
  public void onViewDetachedFromWindow(View view)
  {
    unregister();
  }

  private void unregister()
  {
    // After detach, View.getViewTreeObserver() may return a different, empty observer.
    if (mObserver != null && mObserver.isAlive())
      mObserver.removeOnPreDrawListener(this);
    mObserver = null;
  }

  @Override
  public boolean onPreDraw()
  {
    // A dispatch may already have copied its listener list before close()/detach.
    if (mUpdate != null && mObserver != null && mView.isAttachedToWindow())
      mUpdate.run();
    return true;
  }

  @Override
  public void close()
  {
    mUpdate = null;
    unregister();
    mView.removeOnAttachStateChangeListener(this);
  }
}
