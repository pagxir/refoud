package net.cachefiles.toyvpn;

import android.os.Bundle;
import androidx.preference.PreferenceFragmentCompat;

public class ToyVpnFragment extends PreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);
    }
}
