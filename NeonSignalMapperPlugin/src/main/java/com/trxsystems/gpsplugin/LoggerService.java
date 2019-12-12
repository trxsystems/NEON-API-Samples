package com.trxsystems.gpsplugin;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Build;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.trxsystems.gpsplugin.GPSReceiverPlugin.MEASUREMENT_UPDATE;

public class LoggerService extends Service
{
    protected boolean simulated = true;
    protected final ScheduledExecutorService signalLoggerExec = Executors.newScheduledThreadPool(1);


    private LocationManager locationManager;
    private LocationProvider locationProvider;
    private GnssStatus.Callback gnssStatusListener;

    @Override
    public IBinder onBind(Intent intent) {

        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        if (Build.VERSION.SDK_INT >= 26) {
            String CHANNEL_ID = "gps_mapper_plugin_channel";
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    CHANNEL_ID,
                    NotificationManager.IMPORTANCE_DEFAULT);

            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(channel);

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_gps)
                    .setContentText("Currently logging GPS")
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build();

            startForeground(3356, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        if (ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        {
            locationManager = (LocationManager) getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
            locationProvider = locationManager.getProvider(LocationManager.GPS_PROVIDER);
        }
        else
        {
            Toast.makeText(getApplicationContext(), "Please accept the permissions in the settings page",Toast.LENGTH_LONG).show();
            stopSelf();
            return START_STICKY;
        }


        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        String dataSource = sharedPreferences.getString("pref_data_source","Simulated");
        simulated = dataSource.equals("Simulated");

        if(simulated)
        {
            signalLoggerExec.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    Log.i("gpsplugin","started logging measurement");

                    Random random = new Random();
                    for(int i =0; i < 12; i++)
                    {
                        Intent intent = new Intent(MEASUREMENT_UPDATE);
                        intent.putExtra("id", i);
                        intent.putExtra("type", "Galileo");
                        intent.putExtra("c/n0", random.nextFloat()*20+30);
                        intent.putExtra("elevation", random.nextFloat()*90);
                        intent.putExtra("azimuth", random.nextFloat()*90);
                        intent.putExtra("used", random.nextFloat() > 0.5f ? "Yes": "No");
                        sendBroadcast(intent);
                    }
                    Log.i("gpsplugin","finished logging measurement");
                }
            },0,1000, TimeUnit.MILLISECONDS);
        }
        else
        {
            if (locationProvider == null)
            {
                Toast.makeText(getApplicationContext(), "Unable to create location provider",Toast.LENGTH_LONG).show();
                stopSelf();
                return START_STICKY;
            }

            if (ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            {
                if (Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N)
                {
                    gnssStatusListener = new GnssStatus.Callback() {
                        @Override
                        public void onStarted() {
                        }

                        @Override
                        public void onStopped() {
                        }

                        @Override
                        public void onFirstFix(int ttffMillis) {
                        }

                        @Override
                        public void onSatelliteStatusChanged(GnssStatus status) {
                            final int length = status.getSatelliteCount();

                            int mSvCount = 0;
                            int mUsedInFixCount = 0;

                            while (mSvCount < length)
                            {
                                int svid = status.getSvid(mSvCount);
                                String type = getGnssConstellationType(status.getConstellationType(mSvCount));
                                Intent intent = new Intent(MEASUREMENT_UPDATE);
                                intent.putExtra("id", svid);
                                intent.putExtra("type", type);
                                intent.putExtra("c/n0", status.getCn0DbHz(mSvCount));
                                intent.putExtra("elevation", status.getElevationDegrees(mSvCount));
                                intent.putExtra("azimuth", status.getAzimuthDegrees(mSvCount));
                                intent.putExtra("used", status.usedInFix(mSvCount) ? "Yes": "No");
                                sendBroadcast(intent);
                                mSvCount++;
                            }
                            Log.i("gpsplugin", String.format("GnssStatus.Callback: onSatelliteStatusChanged: Satellites %d/%d", mUsedInFixCount, mSvCount));
                        }
                    };
                    locationManager.registerGnssStatusCallback(gnssStatusListener);
                }
            }
            else
            {
                Toast.makeText(getApplicationContext(), "Unable to get GNSS Satellite updates",Toast.LENGTH_LONG).show();
                stopSelf();
                return START_STICKY;
            }
        }

        return START_STICKY;
    }

    private String getGnssConstellationType(int gnssConstellationType) {
        switch (gnssConstellationType) {
            case GnssStatus.CONSTELLATION_GPS:
                return "Navstar";
            case GnssStatus.CONSTELLATION_GLONASS:
                return "GLONASS";
            case GnssStatus.CONSTELLATION_BEIDOU:
                return "BeiDou";
            case GnssStatus.CONSTELLATION_QZSS:
                return "QZSS";
            case GnssStatus.CONSTELLATION_GALILEO:
                return "Galileo";
            case 7:
                // No constellation type defined in the Android SDK
                // https://issuetracker.google.com/issues/134611316
                return "IRNSS";
            case GnssStatus.CONSTELLATION_SBAS:
                return "SBAS";
            case GnssStatus.CONSTELLATION_UNKNOWN:
            default:
                return "Unknown";
        }
    }

    @Override
    public void onDestroy() {

        if(simulated)
            signalLoggerExec.shutdown();

        if (Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && locationManager != null && gnssStatusListener != null)
            locationManager.unregisterGnssStatusCallback(gnssStatusListener);


        super.onDestroy();
    }
}

