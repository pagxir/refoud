/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.cachefiles.toyvpn;

import android.annotation.TargetApi;
import android.app.Activity;
import androidx.fragment.app.FragmentManager;
import android.content.Intent;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.ComponentName;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.Bundle;
import android.os.RemoteException;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;
import android.preference.Preference;
import androidx.fragment.app.FragmentActivity;
import android.util.Log;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class ToyVpnSettings extends FragmentActivity {
    final static String LOG_TAG = "ToyVpn";
    ToyVpnFragment mToyVpnFragment = null;

    private Intent getVpnServiceIntent() {
        return new Intent(this, ToyVpnService.class);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings);

	if (savedInstanceState == null) {
	    mToyVpnFragment = new ToyVpnFragment();
	    mToyVpnFragment.setActivity(this);

	    FragmentManager supportFragmentManager = getSupportFragmentManager();

	    supportFragmentManager
		.beginTransaction()
		.replace(R.id.setting, mToyVpnFragment, ToyVpnFragment.class.getSimpleName())
		// .addToBackStack(null)
		.commit();
	} else {
	    FragmentManager supportFragmentManager = getSupportFragmentManager();
	    mToyVpnFragment = (ToyVpnFragment)supportFragmentManager.findFragmentByTag(ToyVpnFragment.class.getSimpleName());
	    mToyVpnFragment.setActivity(this);
	}

	if (mToyVpnFragment == null) {
            Log.d(LOG_TAG, "onCreate(Bundle savedInstanceState) mToyVpnFragment=null");
	}
    }

    private void doStartVpnService() {
	startService(getVpnServiceIntent().setAction(ToyVpnService.ACTION_CONNECT));
    }

    private void doStopVpnService() {
	startService(getVpnServiceIntent().setAction(ToyVpnService.ACTION_DISCONNECT));
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
    }

    private boolean mEnabling = false;

    public void enableService(boolean enable) {
	if (mEnabling) {
	    return;
	}

	if (!enable) {
	    mEnabling = false;
	    doStopVpnService();
	    return;
	}

	Intent intent = VpnService.prepare(ToyVpnSettings.this);

	if (intent != null) {
	    startActivityForResult(intent, 0);
	    mEnabling = true;
	    return;
	}

	int serverPortNum = -1;
	String serverName = mToyVpnFragment.getServer();
	String dnsServer = mToyVpnFragment.getDns();

	try {
	    String str = mToyVpnFragment.getPort();
	    serverPortNum = Integer.parseInt(str.length() > 0 ? str : "0");
	} catch (NumberFormatException e) {
	    e.printStackTrace();
	}

	mConnection.updateConfig(serverName, serverPortNum, dnsServer);
	doStartVpnService();
	return;
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
	super.onActivityResult(request, result, data);
	boolean enabling = mEnabling;

	mEnabling = false;
        if (result == RESULT_OK && enabling) {
	    doStartVpnService();
	    return;
        }
    }

    private ToyVpnServiceConnection mConnection = new ToyVpnServiceConnection();

    static class ToyVpnServiceConnection implements ServiceConnection {
        IToyVpnAidl mAidl = null;

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            Log.d(LOG_TAG, "onServiceConnected");
            mAidl = IToyVpnAidl.Stub.asInterface(iBinder);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            Log.d(LOG_TAG, "onServiceDisconnected");
	    mAidl = null;
        }

	public void updateConfig(String server, int port, String dns) {
	    if (mAidl == null) {
		Log.d(LOG_TAG, "updateConfig failure");
		return;
	    }

	    try {
		mAidl.saveServer(server, port, dns);
	    } catch (RemoteException e) {
		Log.d(LOG_TAG, "updateConfig failure");
	    }
	}
    };

    @Override
    public void onResume() {
        super.onResume();
        Log.d(LOG_TAG, "onResume");

        final Intent intent = new Intent();
        intent.setClassName("net.cachefiles.toyvpn", "net.cachefiles.toyvpn.ToyVpnService");
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.d(LOG_TAG, "onPause");

        unbindService(mConnection);
    }
}
