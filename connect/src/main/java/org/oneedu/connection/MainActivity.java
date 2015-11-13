package org.oneedu.connection;

import android.app.FragmentManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.TextView;

import org.oneedu.connection.fragments.APListFragment;
import org.oneedu.connection.services.ProxyService;
import org.oneedu.connection.services.WifiService;
import org.oneedu.uikit.activites.BaseActivity;
import org.sandrob.bouncycastle.util.io.Streams;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;

public class MainActivity extends BaseActivity {

    public WifiService  mWifiService;
    public ProxyService mProxyService;
    private boolean mIsBound;
    private WifiEnabler mWifiEnabler;
    private TextView mWifiStateView;
    private View mWifiIcon;
    public boolean mIsBlockBackBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Intent i = new Intent("org.oneedu.connection.PROXY");
        i.setComponent(new ComponentName("org.oneedu.connection", "org.oneedu.connection.services.ProxyService"));
        startService(i);
        mWifiStateView = (TextView)findViewById(R.id.wifi_state_view);
        mWifiIcon = findViewById(R.id.wifi_icon);
    }

    @Override
    protected void onResume() {
        super.onResume();

        bindService(new Intent(this, ProxyService.class), mProxyConnection, Context.BIND_AUTO_CREATE);
        bindService(new Intent(this, WifiService.class), mWifiConnection, Context.BIND_AUTO_CREATE);
        mIsBound = true;
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (mIsBound) {
            // Detach our existing connection.
            unbindService(mProxyConnection);
            unbindService(mWifiConnection);
            mIsBound = false;
        }

        if (mWifiEnabler != null) {
            mWifiEnabler.pause();
        }
    }

    @Override
    public void onBackPressed() {
        FragmentManager fm = getFragmentManager();
        if (fm.getBackStackEntryCount() <= 1) {
            this.finish();
        } else if ("Connecting".equals(fm.getBackStackEntryAt(fm.getBackStackEntryCount() - 1).getName())) {
            return;
        } else {
            fm.popBackStack();
        }
    }

    private ServiceConnection mWifiConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            mWifiService = ((WifiService.LocalBinder)service).getService();

            if (mWifiEnabler == null) {
                mWifiEnabler = new WifiEnabler(MainActivity.this, (CompoundButton) findViewById(R.id.sb_wifi_enable), new WifiEnabler.OnUpdateWifiStateListener() {
                    @Override
                    public void onUpdateWifiState(int state) {
                        switch (state) {
                            case WifiManager.WIFI_STATE_ENABLING:
                                mWifiIcon.setEnabled(true);
                                getFragmentManager().popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
                                mWifiStateView.setVisibility(View.VISIBLE);
                                mWifiStateView.setText(R.string.wifi_starting);
                                break;
                            case WifiManager.WIFI_STATE_ENABLED:
                                mWifiIcon.setEnabled(true);
                                mWifiStateView.setVisibility(View.GONE);
                                if (getFragmentManager().getBackStackEntryCount() == 0) {
                                    getFragmentManager().beginTransaction().addToBackStack("APList")
                                            .replace(R.id.container, new APListFragment())
                                            .commit();
                                }
                                break;
                            case WifiManager.WIFI_STATE_DISABLING:
                                mWifiIcon.setEnabled(false);
                                getFragmentManager().popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
                                mWifiStateView.setVisibility(View.VISIBLE);
                                mWifiStateView.setText(R.string.wifi_stopping);
                                break;
                            case WifiManager.WIFI_STATE_DISABLED:
                                mWifiIcon.setEnabled(false);
                                getFragmentManager().popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
                                mWifiStateView.setVisibility(View.VISIBLE);
                                mWifiStateView.setText(R.string.wifi_disabled);
                                break;
                            default:
                                break;
                        }
                    }
                });
            }
            mWifiEnabler.resume();
        }

        public void onServiceDisconnected(ComponentName className) {
            mWifiService = null;
        }
    };

    private ServiceConnection mProxyConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            mProxyService = ((ProxyService.LocalBinder)service).getService();
        }

        public void onServiceDisconnected(ComponentName className) {
            mProxyService = null;
        }
    };
}
