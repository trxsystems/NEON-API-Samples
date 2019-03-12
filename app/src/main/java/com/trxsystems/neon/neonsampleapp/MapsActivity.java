package com.trxsystems.neon.neonsampleapp;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
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
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.Polygon;
import com.trx.neon.api.neon.Neon;
import com.trx.neon.api.neon.model.NeonLocation;
import com.trx.neon.api.neonEnvironment.NeonEnvironment;
import com.trx.neon.api.neonEnvironment.model.LatLong;
import com.trx.neon.api.neonEnvironment.model.LatLongRect;

import java.util.UUID;

public class MapsActivity extends AppCompatActivity implements OnMapReadyCallback, LocationSource {

    public static final int LOGIN_ACTIVITY_REQUEST_CODE = 1001;
    public static final int UPGRADE_ACTIVITY_REQUEST_CODE = 1002;
    public static final int PERMISSION_ACTIVITY_REQUEST_CODE = 1003;
    public static final int REQUEST_PERMISSIONS_GOOGLE_CODE = 1004;

    private static final String LOG_TAG = "MapsActivity";

    private GoogleMap googleMap;
    public LocationSource.OnLocationChangedListener locationListener;

    public MenuItem settingsMenu;

    public boolean isCorrectingLocation = false;
    public boolean isFollowing = true;

    public FloatingActionButton centerOnLocationButton;
    public FloatingActionButton checkInButton;
    public FloatingActionButton acceptButton;

    NeonAPIFunctions neonAPIFunctions;
    NeonEnvironmentAPIFunctions neonEnvironmentAPIFunctions;
    NeonRoutingAPIFunctions neonRoutingAPIFunctions;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maps);

        if (getSupportActionBar() != null)
        {
            getSupportActionBar().setDisplayShowHomeEnabled(true);
            getSupportActionBar().show();
        }

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

        acceptButton = findViewById(R.id.acceptButton);
        acceptButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(isCorrectingLocation)
                {
                    cancelUserCorrection();
                    //get latitude and longitude of correction
                    LatLng target = new LatLng(googleMap.getCameraPosition().target.latitude, googleMap.getCameraPosition().target.longitude);

                    if(!neonEnvironmentAPIFunctions.makeBuildingAndFloorCorrection(target))
                        Neon.addConstraint(target.latitude, target.longitude, 1.0f);
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
        neonRoutingAPIFunctions = new NeonRoutingAPIFunctions(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        neonAPIFunctions.onResume();
        neonEnvironmentAPIFunctions.onResume();
        neonRoutingAPIFunctions.onResume();
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
            case R.id.action_settings:
                Intent settingsIntent = new Intent(Neon.ACTIVITY_SETTINGS);
                startActivity(settingsIntent);
                return true;

            case R.id.action_route_destination: {
                neonRoutingAPIFunctions.displayRouteDestinations();
                break;
            }
            case R.id.action_route_settings: {
                neonRoutingAPIFunctions.displayRouteSettings();
                break;
            }
            case R.id.action_sync: {

                final LatLngBounds viewingRect = googleMap.getProjection().getVisibleRegion().latLngBounds;
                final LatLongRect bounds = new LatLongRect(new LatLong(viewingRect.southwest.latitude, viewingRect.southwest.longitude), new LatLong(viewingRect.northeast.latitude, viewingRect.northeast.longitude));

                NeonEnvironment.syncEnvironment(bounds);
                clearMapData();

                break;
            }
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {

            case LOGIN_ACTIVITY_REQUEST_CODE:
                switch (resultCode) {
                    case Activity.RESULT_CANCELED:
                        Log.i(LOG_TAG, "login was canceled, closing activity");
                        neonAPIFunctions.stopLocationService();
                        finish();
                    case Activity.RESULT_OK:
                        Log.i(LOG_TAG, "login information was successfully entered");
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
        }
    }

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
                    googleMap.setMyLocationEnabled(true);
                    neonAPIFunctions.setBaseMap(googleMap);
                    neonEnvironmentAPIFunctions.setBaseMap(googleMap);
                    neonRoutingAPIFunctions.setBaseMap(googleMap);
                } else {
                    finish();
                }
                break;
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (isCorrectingLocation)
        {
            cancelUserCorrection();
            return;
        }
        if(neonRoutingAPIFunctions.checkRouting())
            return;

        shutdown();
        super.onBackPressed();  // optional depending on your needs
    }

    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     * This is where we can add markers or lines, add listeners or move the camera. In this case,
     * we just add a marker near Sydney, Australia.
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
            neonRoutingAPIFunctions.setBaseMap(googleMap);
        }

        googleMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
        googleMap.setOnPolygonClickListener(onPolygonClickListener);
        googleMap.setOnMarkerClickListener(onMarkerClickListener);
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

    private GoogleMap.OnPolygonClickListener onPolygonClickListener = new GoogleMap.OnPolygonClickListener() {
        @Override
        public void onPolygonClick(final Polygon polygon) {

            neonEnvironmentAPIFunctions.onPolygonClick(polygon);
            neonRoutingAPIFunctions.onPolygonClick(polygon);
        }
    };

    private GoogleMap.OnMarkerClickListener onMarkerClickListener = new GoogleMap.OnMarkerClickListener() {
        @Override
        public boolean onMarkerClick(Marker marker)
        {
            return neonRoutingAPIFunctions.onMarkerClick(marker);
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

    public void onLocationChanged(NeonLocation location)
    {
        if(locationListener != null)
            locationListener.onLocationChanged(location.toLocation());

        if(isFollowing)
        {
            neonAPIFunctions.centerOnLocation(location);
            neonEnvironmentAPIFunctions.selectFloorAndBuilding(location);
            neonRoutingAPIFunctions.selectOrientation(location);
        }
    }

    public void startLoadingMapData()
    {
        neonEnvironmentAPIFunctions.startLoadingBuildings();
        neonRoutingAPIFunctions.startLoadingRouteDestinations();
    }

    public void clearMapData()
    {
        neonEnvironmentAPIFunctions.clearMapData();
        neonRoutingAPIFunctions.clearMapData();
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

    public void drawFloor(final UUID buildingID, final int floor)
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                neonEnvironmentAPIFunctions.drawOutline(buildingID, floor);
                neonRoutingAPIFunctions.drawFloorContents(buildingID, floor);
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
            neonRoutingAPIFunctions.shutdown();
            googleMap.clear();
            googleMap = null;
            locationListener = null;
        }
    }
}
