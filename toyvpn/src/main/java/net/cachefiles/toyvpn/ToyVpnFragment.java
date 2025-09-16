package net.cachefiles.toyvpn;

import android.os.Bundle;
import android.util.Log;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;
import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceFragmentCompat;

public class ToyVpnFragment extends PreferenceFragmentCompat
    implements PreferenceManager.OnPreferenceTreeClickListener, 
	       Preference.OnPreferenceChangeListener {
    final static String LOG_TAG = "ToyVpn";
    private String PREF_KEY_SWITCH = "switch";
    private String PREF_KEY_HOST = "host";
    private String PREF_KEY_PORT = "port";

    private SwitchPreferenceCompat mSwitcher = null;
    private EditTextPreference mHostText = null;
    private EditTextPreference mPortText = null;

    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);

	mSwitcher = (SwitchPreferenceCompat) findPreference(PREF_KEY_SWITCH);
	mHostText = (EditTextPreference) findPreference(PREF_KEY_HOST);
	mPortText = (EditTextPreference) findPreference(PREF_KEY_PORT);

	mHostText.setSummary(mHostText.getText());
	mHostText.setOnPreferenceChangeListener(this);

	mPortText.setSummary(mPortText.getText());
	mPortText.setOnPreferenceChangeListener(this);

	getPreferenceManager().setOnPreferenceTreeClickListener(this);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
	Log.e("preference", "Pending Preference value is: " + newValue);

	if (preference == mHostText) {
	    mHostText.setSummary(newValue.toString());
	} else if (preference == mPortText) {
	    mPortText.setSummary(newValue.toString());
	}

	return true;
    }

    public String getServer() {
	Log.d(LOG_TAG, "onPreferenceTreeClick host=" + mHostText.getText());
	return mHostText.getText();
    }

    public String getPort() {
	Log.d(LOG_TAG, "onPreferenceTreeClick port=" + mPortText.getText());
	return mPortText.getText();
    }

    private ToyVpnSettings mActivity = null;
    public void setActivity(ToyVpnSettings activity) {
	mActivity = activity;
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {

	if (preference == mSwitcher) {
	    Log.d(LOG_TAG, "onPreferenceTreeClick " + mSwitcher.isChecked());
	    mActivity.enableService(mSwitcher.isChecked());
	}

	return super.onPreferenceTreeClick(preference);
    }
}
