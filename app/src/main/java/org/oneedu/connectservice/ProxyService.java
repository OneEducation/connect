package org.oneedu.connectservice;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.ProxyProperties;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

import org.sandrop.webscarab.model.Preferences;
import org.sandrop.webscarab.model.StoreException;
import org.sandrop.webscarab.plugin.Framework;
import org.sandrop.webscarab.plugin.proxy.Proxy;
import org.sandroproxy.utils.NetworkHostNameResolver;
import org.sandroproxy.utils.PreferenceUtils;
import org.sandroproxy.utils.network.ClientResolver;
import org.sandroproxy.webscarab.store.sql.SqlLiteStore;

import java.io.File;
import java.sql.SQLException;

/**
 * Created by dongseok0 on 17/03/15.
 */
public class ProxyService extends Service {

    private final String tag = "ProxyService";
    private boolean proxyStarted;
    private Framework framework;
    private NetworkHostNameResolver networkHostNameResolver;
    private ClientResolver clientResolver;
    private Context mContext;
    private ProxyDB proxyDB;
    private WifiManager mWifiManager;
    private ConnectivityManager mConnectivityManager;

    @Override
    public void onCreate() {
        super.onCreate();

        mContext = getApplicationContext();
        mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        mConnectivityManager = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);

        proxyDB = new ProxyDB(mContext);
        try {
            proxyDB.open();
        } catch (SQLException e) {
            e.printStackTrace();
        }

        Preferences.init(mContext);
        PreferenceManager.getDefaultSharedPreferences(mContext).edit().putBoolean(PreferenceUtils.chainProxyEnabled, true).commit();
        PreferenceManager.getDefaultSharedPreferences(mContext).edit().putString(PreferenceUtils.proxyPort, "9008").commit();

        framework = new Framework(mContext);
        setStore(getApplicationContext());
        networkHostNameResolver = new NetworkHostNameResolver(mContext);
        clientResolver = new ClientResolver(mContext);
        Proxy proxy = new Proxy(framework, networkHostNameResolver, clientResolver);
        framework.addPlugin(proxy);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (framework != null){
            framework.stop();
        }
        if (networkHostNameResolver != null){
            networkHostNameResolver.cleanUp();
        }
        networkHostNameResolver = null;
        framework = null;
        proxyStarted = false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(tag, "Received start id " + startId + ": " + intent);

        // fix - network connected and restarting service
        String ssid = null;
        android.net.wifi.WifiInfo wifiInfo = null;
        NetworkInfo networkInfo = null;

        if (intent.getAction().equals("org.oneedu.connection.PROXY.WIFI_STATE_CHANGE")) {
            wifiInfo = intent.getParcelableExtra(WifiManager.EXTRA_WIFI_INFO);
            networkInfo = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
        } else {
            wifiInfo = mWifiManager.getConnectionInfo();
            networkInfo = mConnectivityManager.getActiveNetworkInfo();
        }

        if (wifiInfo != null && networkInfo != null) {
            Log.d(tag, wifiInfo.getSSID() + " : " + networkInfo.getDetailedState().name());
            if (networkInfo.getDetailedState() == NetworkInfo.DetailedState.CONNECTED) {
                ssid = wifiInfo.getSSID();
            }
        }

        toggleProxy(ssid);

        // We want this service to continue running until it is explicitly
        // stopped, so return sticky.
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    // This is the object that receives interactions from clients.  See
    // RemoteService for a more complete example.
    private final IBinder mBinder = new LocalBinder();

    /**
     * Class for clients to access.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with
     * IPC.
     */
    public class LocalBinder extends Binder {
        public ProxyService getService() {
            return ProxyService.this;
        }
    }

    public WifiConfiguration addOrUpdateProxy(WifiDialogController controller) {
        WifiConfiguration config = controller.getConfig();
        String ssid = config.SSID;

        if (config.proxySettings == WifiConfiguration.ProxySettings.STATIC
                && controller.getProxyUsername().length() > 0
                && controller.getProxyPassword().length() > 0) {

            String host = config.linkProperties.getHttpProxy().getHost();
            int port = config.linkProperties.getHttpProxy().getPort();
            String username = controller.getProxyUsername();
            String password = controller.getProxyPassword();
            proxyDB.addOrUpdateProxy(ssid, host, port, username, password);


            ProxyProperties proxyProperties= new ProxyProperties("localhost", 9008, "");
            config.linkProperties.setHttpProxy(proxyProperties);
        } else {
            proxyDB.deleteProxy(ssid);
        }

        return config;
    }

    public org.oneedu.connectservice.Proxy getProxy(String ssid) {
        if (ssid == null) {
            return null;
        }

        return proxyDB.getProxy(ssid);
    }

    public void toggleProxy(String ssid) {
        org.oneedu.connectservice.Proxy proxy = null;
        if (ssid != null) {
            proxy = proxyDB.getProxy(ssid);
        }

        if (proxy != null) {
            String host = proxy.getHost();
            int port = proxy.getPort();
            String username = proxy.getUsername();
            String password = proxy.getPassword();

            Preferences.setPreference(PreferenceUtils.chainProxyUsername, "\\" + username);
            Preferences.setPreference(PreferenceUtils.chainProxyPassword, password);
            Preferences.setPreference(PreferenceUtils.chainProxyHttp, host + ":" + port);
            Preferences.setPreference(PreferenceUtils.chainProxyHttps, host + ":" + port);
        }

        if (!proxyStarted && proxy != null){
            // start
            Thread thread = new Thread()
            {
                @Override
                public void run() {
                    Log.d(tag, "Starting proxy");
                    framework.start();
                    proxyStarted = true;
                }
            };
            thread.setName("Starting proxy");
            thread.start();
        }else if (proxyStarted && proxy == null){
            //stop
            Thread thread = new Thread()
            {
                @Override
                public void run() {
                    Log.d(tag, "Stopping proxy");
                    if (framework != null){
                        framework.stop();
                    }
                    proxyStarted = false;
                }
            };
            thread.setName("Stoping proxy");
            thread.start();
        }

    }

    private void setStore(Context context){
        if (framework != null){
            try {
                File file =  PreferenceUtils.getDataStorageDir(context);
                if (file != null){
                    File rootDir = new File(file.getAbsolutePath() + "/content");
                    if (!rootDir.exists()){
                        rootDir.mkdir();
                    }
                    framework.setSession("Database", SqlLiteStore.getInstance(context, rootDir.getAbsolutePath()), "");
                }
            } catch (StoreException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }


}
