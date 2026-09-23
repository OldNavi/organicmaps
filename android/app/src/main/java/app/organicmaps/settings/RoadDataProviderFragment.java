package app.organicmaps.settings;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.SwitchPreferenceCompat;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import app.organicmaps.road.RoadDataManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class RoadDataProviderFragment extends BaseXmlSettingsFragment
{
  private RoadDataManager mManager;
  private Preference mCountry;
  private PreferenceCategory mImported;
  private List<RoadDataManager.ImportedCountry> mShownImports;
  private Preference mStatus;
  private Preference mDownload;
  private Preference mImport;
  private Preference mLogin;
  private Preference mLogout;
  private String mImportCountry;
  private final ActivityResultLauncher<String[]> mPicker =
      registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
        if (uri != null && mImportCountry != null)
          mManager.importFile(mImportCountry, uri);
      });

  @Override
  protected int getXmlResources()
  {
    return R.xml.prefs_road_events;
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle state)
  {
    super.onViewCreated(view, state);
    if (!RoadDataManager.available())
      return;
    mManager = RoadDataManager.get(requireContext());
    if (state != null)
      mImportCountry = state.getString("import_country");
    getPreferenceScreen().removeAll();
    mStatus = add(R.string.road_events_source, null);
    mStatus.setTitle(mManager.providerId());
    mCountry = add(R.string.road_events_country, this::chooseCountry);
    mLogin = add(R.string.road_events_login, this::login);
    mLogout = add(R.string.road_events_logout, mManager::logout);
    mDownload = add(R.string.road_events_download, () -> mManager.download(mManager.selectedCountry()));
    mImport = add(R.string.road_events_import, () -> {
      mImportCountry = mManager.selectedCountry();
      mPicker.launch(new String[] {"text/*", "application/octet-stream"});
    });
    mImported = new PreferenceCategory(requireContext());
    mImported.setTitle(R.string.road_events_imported_countries);
    getPreferenceScreen().addPreference(mImported);
    mShownImports = null;
    mManager.state().observe(getViewLifecycleOwner(), this::update);
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle state)
  {
    super.onSaveInstanceState(state);
    state.putString("import_country", mImportCountry);
  }

  private Preference add(int title, @Nullable Runnable action)
  {
    Preference pref = new Preference(requireContext());
    pref.setTitle(title);
    pref.setOnPreferenceClickListener(ignored -> {
      if (action != null)
        action.run();
      return true;
    });
    getPreferenceScreen().addPreference(pref);
    return pref;
  }

  private void update(RoadDataManager.State state)
  {
    if (state == null)
      return;
    mLogin.setSummary(mManager.accountName().isEmpty() ? getString(R.string.not_signed_in) : mManager.accountName());
    String country = mManager.selectedCountry();
    mCountry.setSummary(country.isEmpty() ? getString(R.string.road_events_country) : countryName(country));
    mCountry.setEnabled(!state.busy());
    mLogin.setEnabled(!state.busy());
    mLogin.setTitle(mManager.configured() ? R.string.road_events_username : R.string.road_events_login);
    mLogout.setVisible(mManager.configured());
    mLogout.setEnabled(!state.busy());
    mImport.setEnabled(!state.busy() && !country.isEmpty());
    mDownload.setEnabled(!state.busy() && !country.isEmpty() && mManager.configured());
    if (state.message() != 0)
      mStatus.setSummary(state.message());
    if (mShownImports != state.imports())
    {
      mShownImports = state.imports();
      mImported.removeAll();
      DateFormat dates = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
      for (var imported : state.imports())
      {
        Preference row = new Preference(requireContext());
        row.setTitle(countryName(imported.country()));
        row.setSummary(getString(R.string.road_events_import_details, dates.format(new Date(imported.updatedAt())),
                                 imported.count()));
        row.setSelectable(false);
        mImported.addPreference(row);
      }
      if (state.imports().isEmpty())
      {
        Preference empty = new Preference(requireContext());
        empty.setTitle(R.string.road_events_no_imports);
        empty.setSelectable(false);
        mImported.addPreference(empty);
      }
    }
  }

  public static String countryName(String code)
  {
    return new Locale("", code).getDisplayCountry();
  }

  private void chooseCountry()
  {
    List<String> available = mManager.state().getValue().countries();
    List<String> countries = new ArrayList<>(available.isEmpty() ? Arrays.asList(Locale.getISOCountries()) : available);
    countries.sort(Comparator.comparing(RoadDataProviderFragment::countryName));
    String[] names = countries.stream().map(RoadDataProviderFragment::countryName).toArray(String[] ::new);
    new MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.road_events_country)
        .setSingleChoiceItems(names, countries.indexOf(mManager.selectedCountry()),
                              (dialog, which) -> {
                                mManager.selectCountry(countries.get(which));
                                update(mManager.state().getValue());
                                dialog.dismiss();
                              })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void login()
  {
    mManager.readCredentials((savedLogin, savedPassword) -> {
      if (isAdded() && getView() != null)
        showLogin(savedLogin, savedPassword);
    });
  }

  private void showLogin(String savedLogin, String savedPassword)
  {
    Context context = requireContext();
    LinearLayout layout = new LinearLayout(context);
    layout.setOrientation(LinearLayout.VERTICAL);
    int padding = Math.round(24 * getResources().getDisplayMetrics().density);
    layout.setPadding(padding, 0, padding, 0);
    EditText login = new EditText(context);
    login.setHint(R.string.road_events_username);
    login.setSingleLine(true);
    login.setText(savedLogin);
    login.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
    EditText password = new EditText(context);
    password.setHint(R.string.road_events_password);
    password.setSingleLine(true);
    password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    password.setSaveEnabled(false);
    password.setText(savedPassword);
    layout.addView(login);
    layout.addView(password);
    new MaterialAlertDialogBuilder(context)
        .setTitle(R.string.road_events_login)
        .setView(layout)
        .setPositiveButton(android.R.string.ok,
                           (dialog, which) -> {
                             mManager.login(login.getText().toString().trim(), password.getText().toString());
                             password.setText("");
                           })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }
}
