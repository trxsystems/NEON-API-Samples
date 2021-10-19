package com.trxsystems.neon.neonsampleapp;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import androidx.annotation.NonNull;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import androidx.core.app.ActivityCompat;
import androidx.appcompat.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.LocationSource;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Polygon;
import com.trx.neon.api.neon.Neon;
import com.trx.neon.api.neon.model.NeonLocation;
import com.trx.neon.api.neonConstraint.NeonConstraint;
import com.trx.neon.api.neonConstraint.model.ElevationInfo;
import com.trx.neon.api.neonEnvironment.NeonEnvironment;
import com.trx.neon.api.neonEnvironment.model.LatLong;
import com.trx.neon.api.neonEnvironment.model.LatLongRect;
import com.trx.neon.api.neonSettings.NeonSettings;

import java.util.UUID;

/**
 * NEON Sample App
 * Main Entry point for displaying a Google Map and using NEON APIs to display location,
 * draw environment data, and route to a destination
 */
public class MapsActivity extends AppCompatActivity implements OnMapReadyCallback, LocationSource {

    //request codes for responding to UI actions through the API
    public static final int LOGIN_ACTIVITY_REQUEST_CODE = 1001;
    public static final int UPGRADE_ACTIVITY_REQUEST_CODE = 1002;
    public static final int PERMISSION_ACTIVITY_REQUEST_CODE = 1003;
    public static final int REQUEST_PERMISSIONS_GOOGLE_CODE = 1004;
    public static final int TRACKING_UNIT_REQUEST_CODE = 1005;

    private static final String LOG_TAG = "MapsActivity";

    //the actual google map object and a listener that can be used to display locations on it
    private GoogleMap googleMap;
    public LocationSource.OnLocationChangedListener locationListener;

    //menu and screen elements
    public MenuItem settingsMenu;

    public boolean isCorrectingLocation = false;
    public boolean isFollowing = true;

    public FloatingActionButton centerOnLocationButton;
    public FloatingActionButton checkInButton;
    public FloatingActionButton acceptButton;

    //helper classes that exercise a particular API
    NeonAPIFunctions neonAPIFunctions;
    NeonEnvironmentAPIFunctions neonEnvironmentAPIFunctions;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maps);

        if (getSupportActionBar() != null)
        {
            getSupportActionBar().setDisplayShowHomeEnabled(true);
            getSupportActionBar().show();
        }

        //centers the map on the user's location
        centerOnLocationButton = findViewById(R.id.centerButton);
        centerOnLocationButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isFollowing = !isFollowing;
                if (isFollowing)
                    centerOnLocationButton.setImageResource(R.drawable.ic_location_fixed);
                else
                    centerOnLocationButton.setImageResource(R.drawable.ic_location_not_fixed);
            }
        });

        //allows user to specify where they are on the map
        checkInButton = findViewById(R.id.checkInButton);
        checkInButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(!isCorrectingLocation)
                {
                    isCorrectingLocation = true;
                    checkInButton.hide();
                    acceptButton.show();
                    findViewById(R.id.image_user_correct).setVisibility(View.VISIBLE);
                }
            }
        });

        //accepts the center location of the screen as the position for the check-in
        acceptButton = findViewById(R.id.acceptButton);
        acceptButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(isCorrectingLocation)
                {
                    cancelUserCorrection();

                    //get latitude and longitude of center of the screen
                    LatLng target = new LatLng(googleMap.getCameraPosition().target.latitude, googleMap.getCameraPosition().target.longitude);

                    //check if the check-in is inside a building and on a floor
                    //if it is, apply a building and floor constraint
                    //otherwise, apply a user check-in at that location
                    if(!neonEnvironmentAPIFunctions.makeBuildingAndFloorCorrection(target))
                        NeonConstraint.addUserCheckin(System.currentTimeMillis(), target.latitude, target.longitude, 1.0f, ElevationInfo.OnFloor(UUID.randomUUID(), 1));
                }
            }
        });

        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);

        if(mapFragment != null)
            mapFragment.getMapAsync(this);
        else throw new UnsupportedOperationException("Must have a google maps fragment!");

        neonAPIFunctions = new NeonAPIFunctions(this);
        neonEnvironmentAPIFunctions = new NeonEnvironmentAPIFunctions(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        neonAPIFunctions.onResume();
        neonEnvironmentAPIFunctions.onResume();
    }


    @Override
    protected void onPause() {
        super.onPause();
        if(isFinishing())
            shutdown();
    }

    /**
     * Sets up options, gets the menuitems to allow for efficient visibility changes
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        settingsMenu = menu.findItem(R.id.action_settings);
        return true;
    }

    /**
     * Set up the action bar with standard tasks and actions from user interface
     * settings: navigate to NeonLocationService settings page
     * user correction: show the check-in icon to the user
     * user correction cancel: cancel the user correction
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        switch (id) {
            case R.id.action_settings:  //bring up the NEON Location Service Settings Page
                Intent settingsIntent = new Intent(NeonSettings.ACTIVITY_SETTINGS);
                startActivity(settingsIntent);
                return true;

            case R.id.action_sync: {          //sync the environment (re-download an updated set of data

                final LatLngBounds viewingRect = googleMap.getProjection().getVisibleRegion().latLngBounds;
                final LatLongRect bounds = new LatLongRect(new LatLong(viewingRect.southwest.latitude, viewingRect.southwest.longitude), new LatLong(viewingRect.northeast.latitude, viewingRect.northeast.longitude));

                NeonEnvironment.syncEnvironment(bounds);
                clearMapData();
                break;
            }
        }

        return super.onOptionsItemSelected(item);
    }

    /**
     * Handle request codes from the NEON API
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {

            case LOGIN_ACTIVITY_REQUEST_CODE:
                switch (resultCode) {
                    case Activity.RESULT_CANCELED:
                        Log.i(LOG_TAG, "login was canceled, closing activity");
                        neonAPIFunctions.stopLocationService();
                        neonAPIFunctions.loggingIn = false;
                        finish();
                    case Activity.RESULT_OK:
                        Log.i(LOG_TAG, "login information was successfully entered");
                        neonAPIFunctions.loggingIn = false;
                        break;
                    default:
                        break;
                }
                break;
            case UPGRADE_ACTIVITY_REQUEST_CODE:
                switch (resultCode) {
                    case Activity.RESULT_CANCELED:
                        Log.i(LOG_TAG, "upgrade was canceled");
                        break;
                    case Activity.RESULT_OK:
                        Log.i(LOG_TAG, "upgrade information was successfully entered");
                        break;
                    default:
                        break;
                }
                break;
            case PERMISSION_ACTIVITY_REQUEST_CODE:
                switch (resultCode) {
                    case Activity.RESULT_CANCELED:
                        Log.i(LOG_TAG, "permission was canceled, closing activity");
                        neonAPIFunctions.stopLocationService();
                        finish();
                    case Activity.RESULT_OK:
                        Log.i(LOG_TAG, "permission information was successfully entered");
                        neonAPIFunctions.startLocationService();
                        break;
                    default:
                        break;
                }
                break;
            case TRACKING_UNIT_REQUEST_CODE:
                switch (resultCode) {
                    case Activity.RESULT_CANCELED:
                        Log.i(LOG_TAG, "tracking unit was not selected");
                        neonAPIFunctions.stopLocationService();
                        finish();
                    case Activity.RESULT_OK:
                        Log.i(LOG_TAG, "tracking unit was selected");
                        break;
                    default:
                        break;
                }
                break;
        }
    }

    /**
     * Handle request codes from the Google Maps API
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
        switch (requestCode) {
            case REQUEST_PERMISSIONS_GOOGLE_CODE: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        return;
                    }

                    //if we have a map, enable location and make the map accessible to the API functions
                    googleMap.setMyLocationEnabled(true);
                    neonAPIFunctions.setBaseMap(googleMap);
                    neonEnvironmentAPIFunctions.setBaseMap(googleMap);
                } else {
                    finish();
                }
                break;
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (isCorrectingLocation)   //if currently correctly location, exit that
        {
            cancelUserCorrection();
            return;
        }

        shutdown();
        super.onBackPressed();  // optional depending on your needs
    }

    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     * If Google Play services is not installed on the device, the user will be prompted to install
     * it inside the SupportMapFragment. This method will only be triggered once the user has
     * installed Google Play services and returned to the app.
     */
    @Override
    public void onMapReady(GoogleMap googleMap) {
        this.googleMap = googleMap;

        googleMap.setLocationSource(this);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
        {
            ActivityCompat.requestPermissions(MapsActivity.this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_PERMISSIONS_GOOGLE_CODE);
        }
        else
        {
            googleMap.setMyLocationEnabled(true);
            neonAPIFunctions.setBaseMap(googleMap);
            neonEnvironmentAPIFunctions.setBaseMap(googleMap);
        }

        googleMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
        googleMap.setOnPolygonClickListener(onPolygonClickListener);
        googleMap.getUiSettings().setMyLocationButtonEnabled(false);

        // sets camera move listener to track if user dragged map to stop following
        googleMap.setOnCameraMoveStartedListener(new GoogleMap.OnCameraMoveStartedListener() {
            @Override
            public void onCameraMoveStarted(int i) {
                // if camera moved because of user then stop following
                if (i == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                    isFollowing = false;
                    centerOnLocationButton.setImageResource(R.drawable.ic_location_not_fixed);
                }
            }
        });
    }

    /**
     * Handle clicks to polygons on the map, such as floorplans or regions of interest
     */
    private GoogleMap.OnPolygonClickListener onPolygonClickListener = new GoogleMap.OnPolygonClickListener() {
        @Override
        public void onPolygonClick(final Polygon polygon) {
            neonEnvironmentAPIFunctions.onPolygonClick(polygon);
        }
    };

    @Override
    public void activate(LocationSource.OnLocationChangedListener onLocationChangedListener) {
        Log.i(LOG_TAG, "location source - activate!");
        locationListener = onLocationChangedListener;
    }

    @Override
    public void deactivate() {
        Log.i(LOG_TAG, "location source - deactivate!");
        locationListener = null;
    }

    /**
     * Centers on the users location
     * Selects a floor if in a building
     * Orients the screen to the next route node if routing
     */
    public void onLocationChanged(NeonLocation location)
    {
        if(locationListener != null)
            locationListener.onLocationChanged(location.toLocation());

        if(isFollowing)
        {
            neonAPIFunctions.centerOnLocation(location);
            neonEnvironmentAPIFunctions.selectFloorAndBuilding(location);
        }
    }

    /**
     * Begins loading environment data around the user's location
     */
    public void startLoadingMapData()
    {
        neonEnvironmentAPIFunctions.startLoadingBuildings();
    }

    /**
     * Clears the map data from the google map
     */
    public void clearMapData()
    {
        neonEnvironmentAPIFunctions.clearMapData();
        googleMap.clear();
    }

    /**
     * Undoes user correction mode, returning it to normal UI state.
     */
    private void cancelUserCorrection()
    {
        isCorrectingLocation = false;
        checkInButton.show();
        acceptButton.hide();
        findViewById(R.id.image_user_correct).setVisibility(View.GONE);
    }

    /**
     * Draws a building floor and any objects on that floor into the google map
     */
    public void drawFloor(final UUID buildingID, final int floor)
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                neonEnvironmentAPIFunctions.drawOutline(buildingID, floor);
            }
        });

    }

    public boolean shutdown = false;
    public void shutdown()
    {
        if(!shutdown)
        {
            shutdown = true;
            neonAPIFunctions.shutdown();
            neonEnvironmentAPIFunctions.shutdown();
            googleMap.clear();
            googleMap = null;
            locationListener = null;
        }
    }
}
