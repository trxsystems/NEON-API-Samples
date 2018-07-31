package com.trxsystems.neon.neonsampleapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.support.design.widget.FloatingActionButton;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.LocationSource;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.GroundOverlay;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;
import com.google.maps.android.PolyUtil;
import com.trx.neon.api.neon.Neon;
import com.trx.neon.api.neon.model.NeonLocation;
import com.trx.neon.api.neon.model.events.AuthenticationEvent;
import com.trx.neon.api.neon.model.interfaces.INeonEvent;
import com.trx.neon.api.neon.model.interfaces.INeonEventListener;
import com.trx.neon.api.neon.model.interfaces.INeonLocationListener;
import com.trx.neon.api.neon.model.types.NeonEventType;
import com.trx.neon.api.neonEnvironment.NeonEnvironment;
import com.trx.neon.api.neonEnvironment.model.DownloadOptions;
import com.trx.neon.api.neonEnvironment.model.DownloadResult;
import com.trx.neon.api.neonEnvironment.model.LatLong;
import com.trx.neon.api.neonEnvironment.model.LatLongOutline;
import com.trx.neon.api.neonEnvironment.model.LatLongRect;
import com.trx.neon.api.neonEnvironment.model.NeonBuilding;
import com.trx.neon.api.neonEnvironment.model.NeonBuildingFloor;
import com.trx.neon.api.neonEnvironment.model.NeonFloorPlan;
import com.trx.neon.api.neonEnvironment.model.interfaces.INeonBuildingListener;
import com.trx.neon.api.neonEnvironment.model.interfaces.INeonFloorPlanListener;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MapsActivity extends AppCompatActivity implements OnMapReadyCallback, LocationSource, INeonLocationListener, INeonEventListener, INeonBuildingListener, INeonFloorPlanListener {

    private GoogleMap mMap;
    private boolean isConnected = false;
    private static int LOGIN_ACTIVITY_REQUEST_CODE = 1001;
    private static int UPGRADE_ACTIVITY_REQUEST_CODE = 1002;
    private boolean isCorrectingLocation = false;

    public  MenuItem settingsMenu;
    private MenuItem userCorrectionMenu;
    private MenuItem cancelCorrectionMenu;

    private LocationSource.OnLocationChangedListener locationListener;

    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private Looper dataLooper;
    private Handler dataHandler;

    private ConcurrentHashMap<UUID, BuildingOverlays> buildingHashMap = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, GroundOverlay> floorPlanMap = new ConcurrentHashMap<>();

    private AlertDialog floorSelector;

    private boolean isFollowing = false;
    private FloatingActionButton centerOnLocationButton;   // toggle for center on location

    /**
     * Connects to the NEON API and registers for location and events
     */
    private void connectToNeonAPI()
    {
        if(!isConnected)
        {
            isConnected = Neon.bind(getApplicationContext());
            if(isConnected)
            {
                Neon.registerEvents(this);
                Neon.registerLocationUpdates(this);
            }
        }
    }

    /**
     * Disconnects from the NEON API and unregisters location and events
     */
    private void disconnectFromNeonAPI()
    {
        if(isConnected)
        {
            isConnected = false;
            Neon.unregisterEvents(this);
            Neon.unregisterLocationUpdates(this);
            Neon.unbind();
        }
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        connectToNeonAPI();
    }

    @Override
    protected void onPause()
    {
        super.onPause();
        if(isFinishing())
            shutdown();
    }

    private void shutdown()
    {
        disconnectFromNeonAPI();

        if (dataLooper != null) {
            dataLooper.quit();
            dataLooper = null;
        }

        mainHandler.removeCallbacks(loadBuildingsRunnable);

        buildingHashMap.clear();
        floorPlanMap.clear();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maps);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowHomeEnabled(true);
            getSupportActionBar().show();
        }

        centerOnLocationButton = (FloatingActionButton) findViewById(R.id.map_fab);
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

        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        /*
         * Create a new background thread, call Looper.prepare() so it
         * can handle posts, and grab a dataHandler for loading
         * buildings and floor plan images
         */
        Thread t = new Thread(new Runnable() {
            public void run() {
                Looper.prepare();
                dataLooper = Looper.myLooper();
                dataHandler = new Handler(dataLooper);
                Looper.loop();
            }
        });
        t.start();
    }
    /**
     * Sets up options, gets the menuitems to allow for efficient visibility changes
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        settingsMenu = menu.findItem(R.id.action_settings);
        cancelCorrectionMenu = menu.findItem(R.id.action_user_correction_cancel);
        userCorrectionMenu = menu.findItem(R.id.action_user_correction);
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
            case R.id.action_user_correction:
                if (!isCorrectingLocation) {
                    // initialize user correction-mode
                    userCorrectionMenu.setTitle(R.string.user_confirm_title);
                    cancelCorrectionMenu.setVisible(true);
                    settingsMenu.setVisible(false);
                    isCorrectingLocation = true;
                    findViewById(R.id.image_user_correct).setVisibility(View.VISIBLE);

                } else {
                    // user location has been set, undo correction-mode and perform constraint
                    cancelUserCorrection();

                    //get latitude and longitude of correction
                    LatLng target = new LatLng(mMap.getCameraPosition().target.latitude, mMap.getCameraPosition().target.longitude);

                    //find the building floor that the user is checking in on top of
                    Integer floor = null;
                    UUID buildingID = null;
                    for(BuildingOverlays bo : buildingHashMap.values())
                    {
                        //find building intersection
                        if(PolyUtil.containsLocation(target,bo.outlines.get(0).getPoints(), false))
                        {
                            //set floor
                            floor = bo.floor;
                            buildingID = bo.building.getID();
                            break;
                        }
                    }

                    if(floor != null && buildingID != null)
                        NeonEnvironment.addBuildingConstraint(target.latitude, target.longitude, 1.0f, buildingID, floor);
                    else Neon.addConstraint(target.latitude, target.longitude, 1.0f);
                }
                return true;

            case R.id.action_user_correction_cancel:
                cancelUserCorrection();
                break;
        }

        return super.onOptionsItemSelected(item);
    }
    /**
     * Centers and zooms the screen to the user's current location, animates over 1 second time-span
     */
    private void centerOnLocation(NeonLocation location) {

        if(location == null)
            return;

        CameraPosition cameraPosition = new CameraPosition.Builder()
                .zoom(19)
                .target(new LatLng(location.latitude, location.longitude))
                .build();

        mMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition), 1000, null);
    }

    /**
     * Undoes user correction mode, returning it to normal UI state.
     */
    private void cancelUserCorrection() {
        userCorrectionMenu.setTitle(R.string.user_correction_title);
        cancelCorrectionMenu.setVisible(false);
        settingsMenu.setVisible(true);
        isCorrectingLocation = false;
        findViewById(R.id.image_user_correct).setVisibility(View.INVISIBLE);
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
        mMap = googleMap;

        mMap.setLocationSource(this);
        mMap.setMyLocationEnabled(true);
        mMap.setOnPolygonClickListener(onPolygonClickListener);
        mMap.getUiSettings().setMyLocationButtonEnabled(false);

        // sets camera move listener to track if user dragged map to stop following
        mMap.setOnCameraMoveStartedListener(new GoogleMap.OnCameraMoveStartedListener() {
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
     * onPolygonClickListener is called when you tap a building outline on the screen.
     * It will pop up a dialog that allows you to select another floor.  If another
     * floor is selected, it will clear the existing outline and floor plan image, and
     * draw a the new outline and download the floor plan image, if there is one.
     */
    GoogleMap.OnPolygonClickListener onPolygonClickListener = new GoogleMap.OnPolygonClickListener() {
        @Override
        public void onPolygonClick(final Polygon polygon) {

            //Each polygon outline on the screen has a tag with the building ID.  We use the
            //building ID to retrieve the list of outlines and images drawn to the screen.
            //We will clear these outlines and draw a new one if the floor changes

            //The selected building
            final UUID buildingID = (UUID) polygon.getTag();

            //The polygons and overlays drawn to the screen for this building
            final BuildingOverlays bo = buildingHashMap.get(buildingID);

            if (bo == null)
                return;

            Log.i("MapsActivity", "Clicked on building id: " + bo.building.toString() + ", currently showing floor " + bo.floor);

            //close existing dialog if it's open
            if (floorSelector != null && floorSelector.isShowing()) {
                floorSelector.dismiss();
                floorSelector.setView(null);
            }

            floorSelector = new AlertDialog.Builder(MapsActivity.this)
                    .setTitle("Select a new floor")
                    .create();

            final Spinner floorSpinner = new Spinner(MapsActivity.this);
            LinearLayout layout = new LinearLayout(MapsActivity.this);
            layout.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(AbsListView.LayoutParams.WRAP_CONTENT, AbsListView.LayoutParams.MATCH_PARENT);
            layout.addView(floorSpinner, layoutParams);
            floorSelector.setView(layout);

            //use the building to populate the floor spinner with the set of floor labels for that building
            //and set the selected index to the current floor
            floorSpinner.setAdapter(getFloorAdapter(bo.building));
            int position = Math.round(bo.floor);
            final int topFloorNumber = bo.building.getFloors().get(bo.building.getFloors().size() - 1).getFloorNumber();
            floorSpinner.setSelection(topFloorNumber - position);

            //on pressing OK, remove the outlines and image for the current floor, and draw the new outline and
            //download the new image for drawing
            floorSelector.setButton(DialogInterface.BUTTON_POSITIVE, "OK", new DialogInterface.OnClickListener() {
                public void onClick(final DialogInterface dialog, final int id) {
                    final int floorIndex = topFloorNumber - floorSpinner.getSelectedItemPosition();

                    final NeonBuildingFloor previousFloor = bo.building.getFloor(bo.floor);
                    final NeonBuildingFloor newFloor = bo.building.getFloor(floorIndex);

                    Log.i("MapsActivity", "user selected floor: " + newFloor.getLabel());

                    //if the floor is unchanged, do nothing
                    if (previousFloor.getFloorNumber() == newFloor.getFloorNumber())
                        return;

                    //remove the current outlines drawn to the map
                    for (Polygon p : bo.outlines)
                        p.remove();

                    //if there was an image drawn, remove that as well
                    if (previousFloor.hasFloorPlan() && floorPlanMap.containsKey(previousFloor.getFloorPlanImageID()))
                        floorPlanMap.get(previousFloor.getFloorPlanImageID()).remove();

                    //draw the new outline
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            drawOutline(bo.building, newFloor);
                        }
                    });

                    //download the new floorplan, if it exists
                    if (newFloor.hasFloorPlan())
                        NeonEnvironment.downloadFloorPlan(getApplicationContext(), newFloor, dataHandler, MapsActivity.this);
                }
            });
            floorSelector.setButton(DialogInterface.BUTTON_NEGATIVE, "Cancel", new DialogInterface.OnClickListener() {
                public void onClick(final DialogInterface dialog, final int id) {
                    dialog.cancel();
                }
            });

            floorSelector.show();
        }

    };

    /**
     * Constructs an adapter with all the floors in a building
     */
    public ArrayAdapter<String> getFloorAdapter(NeonBuilding b) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(MapsActivity.this, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        if (b == null)
            return adapter;

        // add all floors numbers starting from the lowest level
        for (int i = 0; i < b.getFloors().size(); ++i) {
            NeonBuildingFloor floor = b.getFloors().get(b.getFloors().size() - i - 1);
            adapter.add(floor.getLabel());
        }

        return adapter;
    }

    /**
     * receives events from the NEON Android API
     */
    @Override
    public void onEvent(NeonEventType neonEventType, INeonEvent iNeonEvent) {
        switch(neonEventType)
        {
            case AUTHENTICATION:
                AuthenticationEvent ae = (AuthenticationEvent)iNeonEvent;
                if (ae.getType() == null)
                    break;
                switch (ae.getType())
                {
                    case NO_CREDENTIALS_SET: Neon.startLoginActivityForResult(LOGIN_ACTIVITY_REQUEST_CODE, MapsActivity.this); break;
                    case MANDATORY_UPDATE_REQUIRED: Neon.upgradeNeonLocationServices(MapsActivity.this, UPGRADE_ACTIVITY_REQUEST_CODE,true); break;
                    case UNRESOLVED_AUTHENTICATION_ERROR: Toast.makeText(getApplicationContext(), "Error in login.  Please visit NEON Settings page to resolve errors",Toast.LENGTH_LONG).show(); break;
                    case SUCCESS:
                        Log.i("NEONSampleApp", "successfully logged in to Neon Location Service");

                        //any work that requires a login can be started here
                        mainHandler.post(loadBuildingsRunnable);
                        break;
                    default: break;
                }
                break;
            default: break;
        }
    }

    /**
     * receives location from the NEON Android API
     */
    @Override
    public void onLocationChanged(NeonLocation neonLocation) {
        Log.i("NEONSampleApp", "Got a location: "+neonLocation.toString());

        if (locationListener != null)
            locationListener.onLocationChanged(neonLocation.toLocation());

        if (isFollowing) {
            // if follow-mode on, center user location
            centerOnLocation(neonLocation);

            //if user has a building and floor, draw that to the map if not displayed
            if (neonLocation.structureID != null && neonLocation.getNearestFloor() != null) {
                //get building that user is in
                final NeonBuilding building = NeonEnvironment.getBuilding(neonLocation.structureID);

                if (building == null)
                    return;

                final int userOnFloor = neonLocation.getNearestFloor();

                //if a different floor than the user is on is selected, switch floor display on map
                if (buildingHashMap.containsKey(building.getID()) && buildingHashMap.get(building.getID()).floor != userOnFloor) {
                    final BuildingOverlays bo = buildingHashMap.get(building.getID());
                    final NeonBuildingFloor previousFloor = bo.building.getFloor(bo.floor);
                    final NeonBuildingFloor newFloor = bo.building.getFloor(userOnFloor);

                    Log.i("MapsActivity", "user is on floor: " + newFloor.getLabel());

                    //if the floor is unchanged, do nothing
                    if (previousFloor.getFloorNumber() == newFloor.getFloorNumber())
                        return;

                    //remove the current outlines drawn to the map
                    for (Polygon p : bo.outlines)
                        p.remove();

                    //if there was an image drawn, remove that as well
                    if (previousFloor.hasFloorPlan() && floorPlanMap.containsKey(previousFloor.getFloorPlanImageID()))
                        floorPlanMap.get(previousFloor.getFloorPlanImageID()).remove();

                    //draw the new outline
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            drawOutline(bo.building, newFloor);
                        }
                    });

                    //download the new floorplan, if it exists
                    if (newFloor.hasFloorPlan())
                        NeonEnvironment.downloadFloorPlan(getApplicationContext(), newFloor, dataHandler, this);

                }
            }
        }
    }

    /**
     * receives buildings from the NEON Android API
     */
    @Override
    public void onComplete(List<NeonBuilding> list, DownloadResult downloadResult) {
        if(downloadResult == DownloadResult.SUCCESS)
        {
            for (final NeonBuilding building : list) {
                //check if the building has already been drawn to the screen
                if (buildingHashMap.containsKey(building.getID()))
                    continue;

                //since we are drawing for the first time, get the ground floor for display
                final NeonBuildingFloor buildingFloor = building.getFloor(0);

                //draw the outline of this floor to the screen
                //since this is in a background thread, we draw on the UI thread
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        drawOutline(building, buildingFloor);
                    }
                });

                //if the building floor has a floor plan image, we should download it and then draw it to the screen
                //since this requires network and bitmap decoding, we put in on the dataHandler thread
                if (buildingFloor.hasFloorPlan())
                    NeonEnvironment.downloadFloorPlan(getApplicationContext(), buildingFloor, dataHandler, this);
            }
        }
        else
            Log.i("NEONSampleApp","result failed with: "+list.toString());
    }

    /**
     * The floorPlanListener receives a floorplan bitmap that can be saved or drawn to the screen
     * A NeonFloorPlan includes the bitmap, a helper function to clip the bitmap to the floor outlines,
     * and LatLongs for the corners of the image that georeference it to the screen. NEON allows these
     * four corners to be non-square, but google maps does not support this, so we approximate by creating
     * a bearing along the line between the topLeft and topRight corners.  If the image was warped when placed,
     * this will lead to a placement error on the screen.
     */
    @Override
    public void onComplete(final NeonFloorPlan floorplan, ImageResult result) {

        //if the download was successful, attempt to draw the image to the map
        if (result == ImageResult.SUCCESS) {
            Log.i("NEONSampleApp", "Got image with ID: " + floorplan.getID());

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    drawFloorPlan(floorplan);
                }
            });
        } else Log.i("NEONSampleApp", "Failed to get image with error: " + result);
    }

    /**
     * An object that keeps track of what is drawn to the map for each building
     */
    private class BuildingOverlays {
        public NeonBuilding building;   //current building being displayed
        public int floor;               //current floor being displayed
        ArrayList<Polygon> outlines;    //outlines drawn to the map

        public BuildingOverlays(NeonBuilding b, int floor, ArrayList<Polygon> outlines) {
            this.building = b;
            this.floor = floor;
            this.outlines = new ArrayList<>(outlines);
        }
    }

    /**
     * Every five seconds, this runnable will get the area in view on the screen
     * and issue a request to download all the buildings for that area.
     * The work will be performed on the dataHandler, which points to a background thread.
     * Results will be returned on the buildingCallback, where they will be drawn to the screen
     * Because the DownloadOption is CACHED, the first request to download buildings in this
     * area will take longer as it has to hit the network, but subsequent calls will be much quicker
     * since the buildings are already cached in the Neon Location Service.
     */
    private Runnable loadBuildingsRunnable = new Runnable() {
        @Override
        public void run() {
            Log.i("MapsActivity", "downloading building");

            //get bounds from google map and convert to LatLongRect
            final LatLngBounds viewingRect = mMap.getProjection().getVisibleRegion().latLngBounds;
            final LatLongRect bounds = new LatLongRect(new LatLong(viewingRect.southwest.latitude, viewingRect.southwest.longitude), new LatLong(viewingRect.northeast.latitude, viewingRect.northeast.longitude));

            NeonEnvironment.downloadBuildingsInArea(bounds, dataHandler, MapsActivity.this, DownloadOptions.CACHED);

            mainHandler.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if(requestCode == LOGIN_ACTIVITY_REQUEST_CODE)
        {
            switch (resultCode) {
                case Activity.RESULT_CANCELED:
                    Log.i("NEONSampleApp","login was canceled, closing activity");
                    shutdown();
                    finish();
                    break;
                default:
                    break;
            }
        }
        else if(requestCode == UPGRADE_ACTIVITY_REQUEST_CODE) {
            switch (resultCode) {
                case Activity.RESULT_CANCELED:
                    Log.i("NEONSampleApp", "upgrade was canceled, closing activity");
                    shutdown();
                    finish();
                    break;
                default:
                    break;
            }
        }
    }

    @Override
    public void activate(OnLocationChangedListener onLocationChangedListener) {
        Log.i("NEONSampleApp", "location source - activate!");
        locationListener = onLocationChangedListener;
    }

    @Override
    public void deactivate() {
        Log.i("NEONSampleApp", "location source - deactivate!");
        locationListener = null;
    }

    /**
     * Draws a NeonFloorOutline to the map.  Converts to
     * a set of Google Polygons
     */
    private void drawOutline(NeonBuilding building, NeonBuildingFloor floor) {
        Log.i("NEONSampleApp", "Drawing outline for building: " + building.getID() + ", floor: " + floor.getLabel());

        ArrayList<Polygon> outlines = new ArrayList<>();
        for (LatLongOutline outline : floor.getOutline().Outlines) {
            //construct the outer hull
            ArrayList<LatLng> ol = new ArrayList<>();
            for (LatLong ll : outline.Hull)
                ol.add(new LatLng(ll.Latitude, ll.Longitude));

            //construct the inner holes
            ArrayList<ArrayList<LatLng>> polygonHoles = new ArrayList<>();
            for (ArrayList<LatLong> llo : outline.Holes) {
                ArrayList<LatLng> hol = new ArrayList<>();
                for (LatLong ll : llo)
                    hol.add(new LatLng(ll.Latitude, ll.Longitude));
                polygonHoles.add(hol);
            }

            //add to map
            Polygon p = mMap.addPolygon(new PolygonOptions().addAll(ol).clickable(true));

            //set holes in the polygon
            p.setHoles(polygonHoles);

            //tag it with the building ID
            p.setTag(building.getID());

            //add to list
            outlines.add(p);
        }

        //put the BuildingOverlay object in the hashmap
        buildingHashMap.put(building.getID(), new BuildingOverlays(building, floor.getFloorNumber(), outlines));
    }

    /**
     * Draws a floor plan image to the map
     */
    private void drawFloorPlan(NeonFloorPlan floorPlan) {
        //set up Location objects for the topLeft, topRight, and bottomRight corners
        final Location topLeft = new Location("topLeft");
        topLeft.setLatitude(floorPlan.getTopLeft().Latitude);
        topLeft.setLongitude(floorPlan.getTopLeft().Longitude);
        final Location topRight = new Location("topRight");
        topRight.setLatitude(floorPlan.getTopRight().Latitude);
        topRight.setLongitude(floorPlan.getTopRight().Longitude);
        final Location bottomRight = new Location("bottomRight");
        bottomRight.setLatitude(floorPlan.getBottomRight().Latitude);
        bottomRight.setLongitude(floorPlan.getBottomRight().Longitude);

        //Get the bitmap of the image, clipped to the floor outline
        //This will make segments of the image outside the outline
        //transparent.  Looks nicer on a map.
        final Bitmap bm = floorPlan.getBitmapClippedToFloor();

        //Puts the image on the map
        //Since google does not allow shear on the image
        //we approximate by creating a bearing along the
        //line between the topLeft and topRight corners
        //and scaling it by the distance.  Not perfect, but
        //the best we can do in the Google API.
        GroundOverlay go = mMap.addGroundOverlay(new GroundOverlayOptions()
                .image(BitmapDescriptorFactory.fromBitmap(bm))
                .anchor(0, 0)   //anchor at the topLeft
                .position(new LatLng(topLeft.getLatitude(), topLeft.getLongitude()), //position it at the topLeft
                        topRight.distanceTo(topLeft),   //scale by distance between topRight and topLeft
                        bottomRight.distanceTo(topRight))   //scale by distance between topRight and BottomRight
                .bearing(-(90 - topLeft.bearingTo(topRight)))); //rotate by the bearing between topLeft and topRight

        floorPlan.getBitmap().recycle();    //since we are done with the bitmaps, we should recycle them
        bm.recycle();

        floorPlanMap.put(floorPlan.getID(), go);    //store the groundOverlay in the hashmap
    }
}
