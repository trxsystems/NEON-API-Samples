package com.trxsystems.gpsplugin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.core.content.ContextCompat;

import com.trx.neon.api.neonMeasurement.MeasurementPlugin;
import com.trx.neon.api.neonMeasurement.model.types.DisplayType;
import com.trx.neon.api.neonMeasurement.model.definitions.SignalDefinition;
import com.trx.neon.api.neonMeasurement.model.SignalDefinitionBuilder;
import com.trx.neon.api.neonMeasurement.model.definitions.SignalMeasurement;

import java.util.Arrays;
import java.util.List;

public class GPSReceiverPlugin extends MeasurementPlugin
{
    public static SignalDefinition SatelliteInformation = new SignalDefinitionBuilder("Phone", "GPS", "Satellite Information")
            .AddIdentifierDefinition("id", "Satellite ID", "%d", "%d", false, DisplayType.Integer, 1)
            .AddIdentifierDefinition("type", "Constellation", "%s", "%s", false, DisplayType.String, 0)

            .AddHeatmapDefinition("c/n0", "C/N0", "%.1f dB-Hz", "%f", "dB-Hz", 0, 50, 40, false, 2)

            .AddDisplayDefinition("elevation", "Elevation", "%.1f\u00B0", "%.1f", DisplayType.Float, 4)
            .AddDisplayDefinition("azimuth", "Azimuth", "%.1f\u00B0", "%.1f", DisplayType.Float, 5)
            .AddDisplayDefinition("used", "Used In Fix", "%s", "%s", DisplayType.String, 6)

            .Build();

    public static final String MEASUREMENT_UPDATE =  "com.trx.neon.gpsplugin.gpsinfo";
    private BroadcastReceiver loggerServiceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            SignalMeasurement satelliteMeasurement = SatelliteInformation.generateSignalMeasurementBuilder()
                    .AddTimestamp(System.currentTimeMillis())
                    .AddIdentifier("id",intent.getIntExtra("id", -1))
                    .AddIdentifier("type",intent.getStringExtra("type"))
                    .AddHeatmapValue("c/n0",intent.getFloatExtra("c/n0", -1f))
                    .AddAdditionalData("elevation",intent.getFloatExtra("elevation", -1f))
                    .AddAdditionalData("azimuth",intent.getFloatExtra("azimuth", -1f))
                    .AddAdditionalData("used",intent.getStringExtra("used"))
                    .Build();
            measurementCallback.OnMeasurementAvailable(satelliteMeasurement);
        }
    };

    public MeasurementPlugin GetPlugin()
    {
        return this;
    }

    @Override
    public void StartLogging()
    {
        Intent intent = GetRemoteServiceIntent("com.trxsystems.gpsplugin","com.trxsystems.gpsplugin.LoggerService");
        ContextCompat.startForegroundService(signalMapperContext, intent);
        signalMapperContext.registerReceiver(loggerServiceReceiver, new IntentFilter(MEASUREMENT_UPDATE));
    }

    @Override
    public void StopLogging() {
        Intent intent = GetRemoteServiceIntent("com.trxsystems.gpsplugin","com.trxsystems.gpsplugin.LoggerService");
        signalMapperContext.stopService(intent);
        signalMapperContext.unregisterReceiver(loggerServiceReceiver);
    }

    @Override
    public List<SignalDefinition> GetSignalDefinitions()
    {
        return Arrays.asList(SatelliteInformation);
    }

    @Override
    public boolean hasSettingsActivity() {
        return true;
    }

    @Override
    public String getSettingsTitle() {
        return "GPS Plugin Settings";
    }

    @Override
    public void StartSettingsActivity()
    {
        Intent intent = GetRemoteActivityIntent("com.trxsystems.gpsplugin","com.trxsystems.gpsplugin.SettingsActivity");
        signalMapperContext.startActivity(intent);
    }
}
