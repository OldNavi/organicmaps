package app.organicmaps.downloader;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import app.organicmaps.MwmApplication;

public final class UsbMountReceiver extends BroadcastReceiver
{
  @Override
  public void onReceive(Context context, Intent intent)
  {
    if (Intent.ACTION_MEDIA_MOUNTED.equals(intent.getAction()))
      MwmApplication.from(context).getUsbMapImport().mediaChanged(intent);
  }
}
