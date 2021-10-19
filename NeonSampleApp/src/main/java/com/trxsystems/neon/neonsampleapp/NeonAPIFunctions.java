package com.trxsystems.neon.neonsampleapp;

import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.trx.neon.api.neon.Neon;
import com.trx.neon.api.neon.model.NeonLocation;
import com.trx.neon.api.neon.model.events.AuthenticationEvent;
import com.trx.neon.api.neon.model.events.BindingEvent;
import com.trx.neon.api.neon.model.events.MandatoryUpdateAvailableEvent;
import com.trx.neon.api.neon.model.interfaces.INeonEvent;
import com.trx.neon.api.neon.model.interfaces.INeonEventListener;
import com.trx.neon.api.neon.model.interfaces.INeonLocationListener;
import com.trx.neon.api.neon.model.types.NeonEventType;
import com.trx.neon.api.neonSettings.NeonSettings;

/**
 * NEON API Functions
 * Exercises the functions in the Neon API
 * starts the service and gets events and locations
 */
public class NeonAPIFunctions implements INeonLocationListener, INeonEventListener {

    private static final String LOG_TAG = "NeonAPI";

    private MapsActivity mapsActivity;
    private GoogleMap baseMap;
    public boolean loggingIn = false;

    NeonAPIFunctions(MapsActivity mapsActivity)
    {
       this.mapsActivity = mapsActivity;
       Neon.registerLocationUpdates(this);
       Neon.registerEvents(this);
    }

    void setBaseMap(GoogleMap baseMap)
    {
        this.baseMap = baseMap;
    }

    /**
     * Connects to the NEON API
     */
    void startLocationService() {

        if(!Neon.hasRequiredPermissions(mapsActivity.getApplicationContext()))
        {
            Log.i(LOG_TAG, "starting NEON permissions activity");
            Neon.startPermissionActivityForResult(mapsActivity, MapsActivity.PERMISSION_ACTIVITY_REQUEST_CODE);
        }
        else
        {
            if (!Neon.isBound())
            {
                Log.i(LOG_TAG, "starting NEON Location Service");
                Neon.bind(mapsActivity.getApplicationContext());
            }
        }
    }

    /**
     * Disconnects from the NEON API
     */
    void stopLocationService() {
        if (Neon.isBound())
        {
            Log.i(LOG_TAG, "shutting down NEON Location Service");
            Neon.unbind();
        }
    }

    void onResume()
    {
        startLocationService();
    }

    void shutdown()
    {
        stopLocationService();
        Neon.unregisterLocationUpdates(this);
        Neon.unregisterEvents(this);
    }

    /**
     * Centers and zooms the screen to the user's current location, animates over 1 second time-span
     */
    void centerOnLocation(NeonLocation location) {

        if(location == null)
            return;

        CameraPosition cameraPosition = new CameraPosition.Builder()
                .zoom(19)
                .target(new LatLng(location.latitude, location.longitude))
                .build();

        if(baseMap != null)
            baseMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition), 1000, null);
    }

    /**
     * receives location from the NEON Android API
     */
    @Override
    public void onLocationChanged(NeonLocation neonLocation)
    {
        Log.i(LOG_TAG, "Got a location: "+neonLocation.toString());
        mapsActivity.onLocationChanged(neonLocation);
    }

    /**
     * receives events from the NEON Android API
     */
    @Override
    public void onEvent(NeonEventType neonEventType, INeonEvent iNeonEvent) {
        switch(neonEventType)
        {
            case BINDING:   //NEON service is started
                BindingEvent be = (BindingEvent)iNeonEvent;
                if(be.isBound == null)
                    break;
                switch(be.isBound)
                {
                    case CONNECT:   //prompt user to select tracking unit if they don't have one selected
                        if(!NeonSettings.hasTrackingUnit())
                            NeonSettings.startTrackingUnitActivityForResult(mapsActivity, MapsActivity.TRACKING_UNIT_REQUEST_CODE);
                        break;
                    default:break;
                }
                break;
            case AUTHENTICATION:
                AuthenticationEvent ae = (AuthenticationEvent)iNeonEvent;
                if (ae.getType() == null)
                    break;
                switch (ae.getType())   //handle authentication by starting the login activity or upgrading the service
                {
                    case NO_CREDENTIALS_SET:
                        if(!loggingIn)
                        {
                            NeonSettings.startLoginActivityForResult(MapsActivity.LOGIN_ACTIVITY_REQUEST_CODE, mapsActivity);
                            loggingIn = true;
                        }
                        break;
                    case MANDATORY_UPDATE_REQUIRED: NeonSettings.upgradeNeonLocationServices(mapsActivity, MapsActivity.UPGRADE_ACTIVITY_REQUEST_CODE,true); break;
                    case UNRESOLVED_AUTHENTICATION_ERROR: Toast.makeText(mapsActivity.getApplicationContext(), "Error in login.  Please visit NEON Settings page to resolve errors",Toast.LENGTH_LONG).show(); break;
                    case SUCCESS:
                        Log.i(LOG_TAG, "successfully logged in to Neon Location Service");
                        mapsActivity.startLoadingMapData();
                        break;
                    default: break;
                }
                break;
            case MANDATORY_UPDATE_AVAILABLE:    //force upgrade if required
                MandatoryUpdateAvailableEvent muae = (MandatoryUpdateAvailableEvent)iNeonEvent;
                if(muae.getType() == null)
                    break;
                if(muae.getType() == MandatoryUpdateAvailableEvent.MandatoryUpdateAvailableEventType.APPLICATION)
                    NeonSettings.upgradeNeonLocationServices(mapsActivity, MapsActivity.UPGRADE_ACTIVITY_REQUEST_CODE, true);
                break;
            default: break;
        }
    }
}
