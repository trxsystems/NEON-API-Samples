package com.trxsystems.neon.neonsampleapp;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.GroundOverlay;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;
import com.google.maps.android.PolyUtil;
import com.trx.neon.api.neon.model.NeonLocation;
import com.trx.neon.api.neonConstraint.NeonConstraint;
import com.trx.neon.api.neonConstraint.model.ElevationInfo;
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

public class NeonEnvironmentAPIFunctions implements INeonBuildingListener, INeonFloorPlanListener {

    private static final String LOG_TAG = "NeonEnvironmentAPI";

    private MapsActivity mapsActivity;
    private GoogleMap baseMap;

    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private Looper dataLooper;
    private Handler dataHandler;

    private ConcurrentHashMap<UUID, NeonBuilding> buildingHashMap = new ConcurrentHashMap<>();
    private ConcurrentHashMap<UUID, BuildingOverlays> buildingOverlayHashMap = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, GroundOverlay> floorPlanMap = new ConcurrentHashMap<>();

    private AlertDialog floorSelector;

    /**
     * An object that keeps track of what is drawn to the map for each building
     */
    private class BuildingOverlays {
        UUID buildingID;   //current building being displayed
        int floor;               //current floor being displayed
        ArrayList<Polygon> outlines;    //outlines drawn to the map

        BuildingOverlays(UUID buildingID, int floor, ArrayList<Polygon> outlines) {
            this.buildingID = buildingID;
            this.floor = floor;
            this.outlines = new ArrayList<>(outlines);
        }
    }

    NeonEnvironmentAPIFunctions(MapsActivity mapsActivity) {
        this.mapsActivity = mapsActivity;
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

    void setBaseMap(GoogleMap baseMap)
    {
        this.baseMap = baseMap;
    }

    void startLoadingBuildings()
    {
        mainHandler.post(loadBuildingsRunnable);
    }

    void onResume()
    {
    }

    void shutdown()
    {
        if (dataLooper != null)
        {
            dataLooper.quit();
            dataLooper = null;
        }

        mainHandler.removeCallbacks(loadBuildingsRunnable);

        clearMapData();
    }

    boolean makeBuildingAndFloorCorrection(LatLng target)
    {
        //find the building floor that the user is checking in on top of
        Integer floor = null;
        UUID buildingID = null;
        for(BuildingOverlays bo : buildingOverlayHashMap.values())
        {
            //find building intersection
            if(PolyUtil.containsLocation(target,bo.outlines.get(0).getPoints(), false))
            {
                //set floor
                floor = bo.floor;
                buildingID = bo.buildingID;
                break;
            }
        }

        if(floor != null && buildingID != null)
        {
            NeonConstraint.addUserCheckin(System.currentTimeMillis(), target.latitude, target.longitude, 1.0f, ElevationInfo.OnFloor(buildingID, floor));
            return true;
        }

        return false;
    }

    /**
     * onPolygonClickListener is called when you tap a building outline on the screen.
     * It will pop up a dialog that allows you to select another floor.  If another
     * floor is selected, it will clear the existing outline and floor plan image, and
     * draw a the new outline and download the floor plan image, if there is one.
     */
    void onPolygonClick(Polygon polygon)
    {
        //Each polygon outline on the screen has a tag with the building ID.  We use the
        //building ID to retrieve the list of outlines and images drawn to the screen.
        //We will clear these outlines and draw a new one if the floor changes

        //The selected building
        final UUID buildingID = (UUID) polygon.getTag();

        //The polygons and overlays drawn to the screen for this building
        final BuildingOverlays bo = buildingOverlayHashMap.get(buildingID);
        final NeonBuilding building = buildingHashMap.get(buildingID);

        if (bo == null || building == null)
            return;

        Log.i(LOG_TAG, "Clicked on building id: " + bo.buildingID.toString() + ", currently showing floor " + bo.floor);

        //close existing dialog if it's open
        if (floorSelector != null && floorSelector.isShowing()) {
            floorSelector.dismiss();
            floorSelector.setView(null);
        }

        floorSelector = new AlertDialog.Builder(mapsActivity)
                .setTitle("Select a new floor")
                .create();

        final Spinner floorSpinner = new Spinner(mapsActivity);
        LinearLayout layout = new LinearLayout(mapsActivity);
        layout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(AbsListView.LayoutParams.WRAP_CONTENT, AbsListView.LayoutParams.MATCH_PARENT);
        layout.addView(floorSpinner, layoutParams);
        floorSelector.setView(layout);

        //use the building to populate the floor spinner with the set of floor labels for that building
        //and set the selected index to the current floor
        floorSpinner.setAdapter(getFloorAdapter(building));
        int position = Math.round(bo.floor);
        final int topFloorNumber = building.getFloors().get(building.getFloors().size() - 1).getFloorNumber();
        floorSpinner.setSelection(topFloorNumber - position);

        //on pressing OK, remove the outlines and image for the current floor, and draw the new outline and
        //download the new image for drawing
        floorSelector.setButton(DialogInterface.BUTTON_POSITIVE, "OK", new DialogInterface.OnClickListener() {
            public void onClick(final DialogInterface dialog, final int id) {
                final int floorIndex = topFloorNumber - floorSpinner.getSelectedItemPosition();

                final NeonBuildingFloor previousFloor = building.getFloor(bo.floor);
                final NeonBuildingFloor newFloor = building.getFloor(floorIndex);

                Log.i(LOG_TAG, "user selected floor: " + newFloor.getLabel());

                //remove the current outlines drawn to the map
                for (Polygon p : bo.outlines)
                    p.remove();

                //if there was an image drawn, remove that as well
                if (previousFloor.hasFloorPlan() && floorPlanMap.containsKey(previousFloor.getFloorPlanImageID()))
                {
                    GroundOverlay pf = floorPlanMap.get(previousFloor.getFloorPlanImageID());
                    if(pf != null)
                        pf.remove();
                }

                //draw the new outline
                mapsActivity.drawFloor(building.getID(), floorIndex);

                //download the new floorplan, if it exists
                if (newFloor.hasFloorPlan())
                    NeonEnvironment.downloadFloorPlan(mapsActivity.getApplicationContext(), newFloor, dataHandler, NeonEnvironmentAPIFunctions.this);
            }
        });
        floorSelector.setButton(DialogInterface.BUTTON_NEGATIVE, "Cancel", new DialogInterface.OnClickListener() {
            public void onClick(final DialogInterface dialog, final int id) {
                dialog.cancel();
            }
        });

        floorSelector.show();
    }

    /**
     * Constructs an adapter with all the floors in a building
     */
    private ArrayAdapter<String> getFloorAdapter(NeonBuilding b) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(mapsActivity.getApplicationContext(), android.R.layout.simple_spinner_item);
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

    void selectFloorAndBuilding(NeonLocation neonLocation)
    {
        //if user has a building and floor, draw that to the map if not displayed
        if (neonLocation.structureID != null && neonLocation.getNearestFloor() != null) {
            //get building that user is in

            final BuildingOverlays bo = buildingOverlayHashMap.get(neonLocation.structureID);
            final NeonBuilding building = NeonEnvironment.getBuilding(neonLocation.structureID);

            if (building == null || bo == null)
                return;

            final int userOnFloor = neonLocation.getNearestFloor();

            //if a different floor than the user is on is selected, switch floor display on map
            if (bo.floor != userOnFloor)
            {
                //remove the current outlines drawn to the map
                for (Polygon p : bo.outlines)
                    p.remove();


                final NeonBuildingFloor previousFloor = building.getFloor(bo.floor);
                final NeonBuildingFloor newFloor = building.getFloor(userOnFloor);

                //if there was an image drawn, remove that as well
                if (previousFloor != null && previousFloor.hasFloorPlan() && floorPlanMap.containsKey(previousFloor.getFloorPlanImageID()))
                    floorPlanMap.get(previousFloor.getFloorPlanImageID()).remove();

                //draw the new outline
                mapsActivity.drawFloor(building.getID(), userOnFloor);

                //download the new floorplan, if it exists
                if (newFloor.hasFloorPlan())
                    NeonEnvironment.downloadFloorPlan(mapsActivity.getApplicationContext(), newFloor, dataHandler, this);
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

                buildingHashMap.put(building.getID(), building);

                //since we are drawing for the first time, get the ground floor for display
                final NeonBuildingFloor buildingFloor = building.getFloor(0);

                //draw the outline of this floor to the screen
                //since this is in a background thread, we draw on the UI thread
                mapsActivity.drawFloor(building.getID(), 0);

                //if the building floor has a floor plan image, we should download it and then draw it to the screen
                //since this requires network and bitmap decoding, we put in on the dataHandler thread
                if (buildingFloor.hasFloorPlan())
                    NeonEnvironment.downloadFloorPlan(mapsActivity.getApplicationContext(), buildingFloor, dataHandler, this);
            }
        }
        else
            Log.i(LOG_TAG,"result failed with: "+list.toString());
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
            Log.i(LOG_TAG, "Got image with ID: " + floorplan.getID());

            mapsActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    drawFloorPlan(floorplan);
                }
            });
        } else Log.i(LOG_TAG, "Failed to get image with error: " + result);
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
            Log.i(LOG_TAG, "downloading building");

            if(baseMap == null)
                return;
            //get bounds from google map and convert to LatLongRect
            final LatLngBounds viewingRect = baseMap.getProjection().getVisibleRegion().latLngBounds;
            final LatLongRect bounds = new LatLongRect(new LatLong(viewingRect.southwest.latitude, viewingRect.southwest.longitude), new LatLong(viewingRect.northeast.latitude, viewingRect.northeast.longitude));

            NeonEnvironment.downloadBuildingsInArea(bounds, dataHandler, NeonEnvironmentAPIFunctions.this, DownloadOptions.CACHED);

            mainHandler.postDelayed(this, 5000);
        }
    };

    /**
     * Draws a NeonFloorOutline to the map.  Converts to
     * a set of Google Polygons
     */
    void drawOutline(UUID buildingID, int floor) {
        Log.i(LOG_TAG, "Drawing outline for building: " + buildingID + ", floor: " + floor);

        //The polygons and overlays drawn to the screen for this building
        final NeonBuilding building = buildingHashMap.get(buildingID);

        if (building == null || building.getFloor(floor) == null) //don't have the data to draw
            return;

        ArrayList<Polygon> outlines = new ArrayList<>();
        for (LatLongOutline outline : building.getFloor(floor).getOutline().Outlines) {
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
            Polygon p = baseMap.addPolygon(new PolygonOptions().addAll(ol).clickable(true));

            //set holes in the polygon
            p.setHoles(polygonHoles);

            //tag it with the building ID
            p.setTag(building.getID());

            //add to list
            outlines.add(p);
        }

        //put the BuildingOverlay object in the hashmap
        buildingOverlayHashMap.put(building.getID(), new BuildingOverlays(building.getID(), floor, outlines));
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
        GroundOverlay go = baseMap.addGroundOverlay(new GroundOverlayOptions()
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

    void clearMapData()
    {
        buildingHashMap.clear();
        buildingOverlayHashMap.clear();
        floorPlanMap.clear();
    }
}
