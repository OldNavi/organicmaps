package app.organicmaps.road;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RoadUpdateWorker extends Worker
{
  private final AtomicBoolean mCancelled = new AtomicBoolean();
  private final RoadUpdateCompletion mCompletion = new RoadUpdateCompletion();
  private final Handler mMain = new Handler(Looper.getMainLooper());

  public RoadUpdateWorker(@NonNull Context context, @NonNull WorkerParameters parameters)
  {
    super(context, parameters);
  }

  @NonNull
  @Override
  public Result doWork()
  {
    if (!RoadDataManager.available())
      return Result.success();
    String provider = getInputData().getString(RoadUpdateScheduler.PROVIDER);
    String country = getInputData().getString(RoadUpdateScheduler.COUNTRY);
    mMain.post(()
                   -> RoadDataManager.get(getApplicationContext())
                          .updateAutomatically(provider, country, mCancelled::get, mCompletion));
    try
    {
      return switch (mCompletion.get(9, TimeUnit.MINUTES))
      {
        case UPDATED, SKIPPED -> Result.success();
        case NEEDS_LOGIN -> Result.failure();
        case RETRY -> getRunAttemptCount() < 5 ? Result.retry() : Result.failure();
      };
    }
    catch (InterruptedException e)
    {
      cancel();
      Thread.currentThread().interrupt();
      return Result.retry();
    }
    catch (TimeoutException e)
    {
      cancel();
      return Result.retry();
    }
    catch (ExecutionException e)
    {
      throw new IllegalStateException("Road update failed", e.getCause());
    }
  }

  @Override
  public void onStopped()
  {
    cancel();
  }

  private void cancel()
  {
    mCancelled.set(true);
    if (!RoadDataManager.available())
      return;
    mMain.post(() -> RoadDataManager.get(getApplicationContext()).cancelAutomaticUpdate(mCompletion));
  }
}
