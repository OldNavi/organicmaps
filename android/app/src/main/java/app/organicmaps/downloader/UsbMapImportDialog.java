package app.organicmaps.downloader;

import android.app.Dialog;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class UsbMapImportDialog extends DialogFragment
{
  public static final String TAG = "usb-map-import";
  private UsbMapImportManager mManager;
  private TextView mMessage;
  private ProgressBar mProgress;
  private final Runnable mRefresh = this::refresh;

  @NonNull
  @Override
  public Dialog onCreateDialog(Bundle savedInstanceState)
  {
    mManager = MwmApplication.from(requireContext()).getUsbMapImport();
    setCancelable(false);
    View view = getLayoutInflater().inflate(R.layout.dialog_usb_map_import, null);
    mMessage = view.findViewById(R.id.usb_map_message);
    mProgress = view.findViewById(R.id.usb_map_progress);
    return new MaterialAlertDialogBuilder(requireContext(), R.style.MwmTheme_AlertDialog)
        .setTitle(R.string.usb_maps_title)
        .setView(view)
        .setPositiveButton(R.string.ok, null)
        .setNegativeButton(R.string.cancel, null)
        .create();
  }
  @Override
  public void onStart()
  {
    super.onStart();
    mManager.listen(mRefresh);
    refresh();
  }
  @Override
  public void onStop()
  {
    mManager.unlisten(mRefresh);
    super.onStop();
  }
  private void refresh()
  {
    AlertDialog dialog = (AlertDialog) getDialog();
    if (dialog == null)
      return;
    UsbMapImportManager.State state = mManager.state();
    if (state == UsbMapImportManager.State.IDLE)
    {
      dismissAllowingStateLoss();
      return;
    }
    boolean permission = state == UsbMapImportManager.State.PERMISSION;
    boolean finished = state == UsbMapImportManager.State.FINISHED;
    mProgress.setVisibility(permission || finished ? View.GONE : View.VISIBLE);
    mProgress.setIndeterminate(state == UsbMapImportManager.State.WAITING);
    mProgress.setMax(1000);
    mProgress.setProgress(mManager.progress());
    StringBuilder text = new StringBuilder();
    if (permission)
      text.append(getString(R.string.usb_maps_permission));
    else if (finished)
    {
      text.append(getString(R.string.usb_maps_finished, mManager.installed()));
      if (!mManager.currentFile().isEmpty() && mManager.installed() == 0)
        text.append("\n").append(mManager.currentFile());
    }
    else
    {
      text.append(getString(state == UsbMapImportManager.State.WAITING ? R.string.usb_maps_waiting
                                                                       : R.string.usb_maps_copying));
      text.append("\n").append(mManager.currentFile());
      text.append("\n\n").append(getString(R.string.usb_maps_destination, mManager.destination()));
      for (UsbMapFiles.Entry entry : mManager.entries())
        text.append("\n")
            .append(entry.source.getName())
            .append(" — ")
            .append(getString(entry.update ? R.string.usb_maps_update : R.string.usb_maps_new))
            .append(", ")
            .append(Formatter.formatShortFileSize(requireContext(), entry.metadata.size));
    }
    if (!permission && !mManager.errors().isEmpty())
    {
      text.append("\n\n").append(getString(R.string.usb_maps_errors));
      for (String name : mManager.errors())
        text.append("\n").append(name);
    }
    mMessage.setText(text);
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setVisibility(permission || finished ? View.VISIBLE : View.GONE);
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(permission ? R.string.usb_maps_allow : R.string.ok);
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
      if (permission)
        mManager.requestAccess(requireActivity());
      else
      {
        mManager.dismiss();
        dismiss();
      }
    });
    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setVisibility(finished ? View.GONE : View.VISIBLE);
    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
      if (permission)
      {
        mManager.dismiss();
        dismiss();
      }
      else
        mManager.cancel();
    });
  }
}
