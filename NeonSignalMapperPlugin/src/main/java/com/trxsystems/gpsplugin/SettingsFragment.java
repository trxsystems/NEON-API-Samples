package com.trxsystems.gpsplugin;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import java.util.ArrayList;

public class SettingsFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireActivity());
        setPreferenceScreen(screen);

        final ListPreference dataSource = new ListPreference(screen.getContext());
        dataSource.setKey("pref_data_source");
        dataSource.setPersistent(true);

        ArrayList<CharSequence> entries = new ArrayList<>();
        entries.add("Simulated");
        entries.add("Android");

        CharSequence[] entryArray = new CharSequence[entries.size()];
        entryArray = entries.toArray(entryArray);

        dataSource.setEntries(entryArray);
        dataSource.setEntryValues(entryArray);

        dataSource.setDefaultValue("Simulated");
        dataSource.setSummary("Choose the source of the GPS Satellite Information");

        screen.addPreference(dataSource);


        dataSource.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                if(newValue.equals("Android"))
                {
                    if (ContextCompat.checkSelfPermission(getActivity(),
                            Manifest.permission.ACCESS_FINE_LOCATION)
                            != PackageManager.PERMISSION_GRANTED)
                    {
                        ActivityCompat.requestPermissions(getActivity(),
                                new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                                1005);
                    }
                }

                return true;
            }
        });
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {

    }

}
