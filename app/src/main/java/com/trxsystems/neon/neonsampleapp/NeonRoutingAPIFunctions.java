package com.trxsystems.neon.neonsampleapp;

import android.animation.IntEvaluator;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.support.design.widget.BottomSheetBehavior;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.BaseExpandableListAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ExpandableListView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.trx.neon.api.neon.model.NeonLocation;
import com.trx.neon.api.neonEnvironment.model.LatLong;
import com.trx.neon.api.neonEnvironment.model.LatLongRect;
import com.trx.neon.api.neonRouting.NeonRouting;
import com.trx.neon.api.neonRouting.model.NeonRouteDestination;
import com.trx.neon.api.neonRouting.model.PointOfInterest;
import com.trx.neon.api.neonRouting.model.RegionOfInterest;
import com.trx.neon.api.neonRouting.model.Route;
import com.trx.neon.api.neonRouting.model.RouteNode;
import com.trx.neon.api.neonRouting.model.RoutingResult;
import com.trx.neon.api.neonRouting.model.interfaces.INeonRouteDestinationListener;
import com.trx.neon.api.neonRouting.model.interfaces.INeonRouteListener;
import com.trx.neon.api.neonRouting.model.interfaces.INeonTurnByTurnListener;
import com.trx.neon.api.neonRouting.model.types.RouteFilter;
import com.trx.neon.api.neonRouting.model.types.RouteInstructionType;
import com.trx.neon.api.neonRouting.model.types.RouteState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * NEON Routing API Functions
 * Exercises the functions in the Neon Routing API
 * downloads route destinations for display, and allows user to retrieve a route
 * from their current location to a route destination and start turn-by-turn directions
 * on that route
 */
public class NeonRoutingAPIFunctions implements INeonRouteDestinationListener, INeonRouteListener, INeonTurnByTurnListener {

    private static final String LOG_TAG = "NeonRoutingAPI";

    private MapsActivity mapsActivity;
    private GoogleMap baseMap;

    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private Looper dataLooper;
    private Handler dataHandler;

    //route destination lookup by ID
    private ConcurrentHashMap<String, NeonRouteDestination> routeDestinationHashMap = new ConcurrentHashMap<>();

    //cached data for each building floor. Can be used to draw to the map.
    private ConcurrentHashMap<BuildingAndFloor, FloorData> buildingFloorData = new ConcurrentHashMap<>();
    //data that is currently drawn to the map for each building ID
    private ConcurrentHashMap<UUID, FloorOverlay> buildingOverlays = new ConcurrentHashMap<>();

    private Route currentRoute = null;

    //Routing UI Elements
    private AlertDialog routeDestinationDialog;
    private BottomSheetBehavior bottomSheetBehavior;
    private TextView routeInstructions;
    private Button contextualButton;
    private View stopButton;
    private View separator;
    private ImageView directionView;
    private BottomSheetState bottomSheetState;

    //speak instructions
    private TextToSpeech tts;

    //route filters checked in settings
    private ArrayList<RouteFilter> currentRouteFilters = new ArrayList<>();

    /**
     * An enum that keeps track of the app's routing state
     */
    private enum BottomSheetState {
        NO_ROUTE,
        READY,
        IN_PROGRESS,
        COMPLETED
    }

    private RouteNode currentNodeDestination;
    private Circle currentlyDrawnNode;
    private ValueAnimator circleAnimation;

    /**
     * An object that keeps track of what is drawn to the map for each building floor
     */
    private class FloorOverlay {
        UUID buildingID;
        int floor;
        private ArrayList<Polygon> rois;        //regions of interest for the current building/floor
        private ArrayList<Marker> pois;         //points of interest for the current building/floor
        private ArrayList<Polyline> routes;     //computed route for the current building/floor

        FloorOverlay(UUID buildingID, int floor, ArrayList<Marker> pois, ArrayList<Polygon> rois, ArrayList<Polyline> routes) {
            this.buildingID = buildingID;
            this.floor = floor;
            this.pois = new ArrayList<>(pois);
            this.rois = new ArrayList<>(rois);
            this.routes = new ArrayList<>(routes);
        }

        void removeAll() {

            for (Polygon p : rois)
                p.remove();

            rois.clear();

            for (Marker m : pois)
                m.remove();

            pois.clear();

            for (Polyline l : routes)
                l.remove();

            routes.clear();
        }
    }

    /**
     * An object that keeps track of what is drawn to the map for each building floor
     */
    private class FloorData {

        UUID buildingID;
        int floor;
        CopyOnWriteArrayList<NeonRouteDestination> routeDestinations;   //route destinations
        CopyOnWriteArrayList<ArrayList<RouteNode>> routes;              //active routes

        FloorData(UUID buildingID, int floor)
        {
            this.buildingID = buildingID;
            this.floor = floor;
            this.routeDestinations = new CopyOnWriteArrayList<>();
            this.routes = new CopyOnWriteArrayList<>();
        }
    }

    /**
     * Keeps track of a building and floor combination
     */
    private class BuildingAndFloor {
        UUID buildingID;   //current building being displayed
        int floor;         //current floor being displayed

        BuildingAndFloor(UUID buildingID, int floor) {
            this.buildingID = buildingID;
            this.floor = floor;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null) return false;
            if (o instanceof BuildingAndFloor) {
                return this.buildingID.equals(((BuildingAndFloor) o).buildingID) && this.floor == ((BuildingAndFloor) o).floor;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return 37 * floor + buildingID.hashCode();
        }
    }

    NeonRoutingAPIFunctions(MapsActivity mapsActivity) {

        this.mapsActivity = mapsActivity;

        tts = new TextToSpeech(this.mapsActivity.getApplicationContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int i) {

            }
        });

        //setting up UI elements for turn-by-turn instructions
        separator = mapsActivity.findViewById(R.id.route_seperator);
        directionView = mapsActivity.findViewById(R.id.route_image);

        LinearLayout bottomSheetLayout = mapsActivity.findViewById(R.id.route_bottom_sheet);
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheetLayout);
        bottomSheetBehavior.setPeekHeight(mapsActivity.getResources().getDimensionPixelSize(R.dimen.navigation_bar_height));
        bottomSheetState = BottomSheetState.NO_ROUTE;

        contextualButton = mapsActivity.findViewById(R.id.route_contextual);
        stopButton = mapsActivity.findViewById(R.id.route_stop);
        routeInstructions = mapsActivity.findViewById(R.id.route_instructions);
        setNavSheet("", null, null);

        /*
         * Create a new background thread, call Looper.prepare() so it
         * can handle posts, and grab a dataHandler for loading
         * route destinations
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

    //the next node in the route graph blinks to indicate the next step
    private ValueAnimator makeCircleAnimation() {
        ValueAnimator valueAnimator = new ValueAnimator();
        valueAnimator.setRepeatCount(ValueAnimator.INFINITE);
        valueAnimator.setRepeatMode(ValueAnimator.REVERSE);
        valueAnimator.setIntValues(0, 100);
        valueAnimator.setDuration(1000);
        valueAnimator.setEvaluator(new IntEvaluator());
        valueAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        valueAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {
                if (currentlyDrawnNode != null) {
                    float animatedFraction = valueAnimator.getAnimatedFraction();
                    currentlyDrawnNode.setFillColor(Color.argb((int) (155 + 100 * animatedFraction), 255, 0, 0));
                    currentlyDrawnNode.setRadius(.25 + .25 * animatedFraction);
                }
            }
        });
        return valueAnimator;
    }


    /**
     * Returns a list of routing destinations after calling NeonRouting.downloadRouteDestinationsInArea()
     * These destinations are cached in a map of building floor data so they can be displayed when the
     * user selects a floor to view
     *
     * Route destinations can include a point of interest (marker on google map) or a region of interest
     * (polygon on google map)
     */
    @Override
    public void onComplete(final List<NeonRouteDestination> routeDestinations)
    {
        Log.i(LOG_TAG, "got "+routeDestinations.size()+" route destinations");

        for(NeonRouteDestination nrd : routeDestinations)
        {
            if((routeDestinationHashMap.containsKey(nrd.ID.toString())))
                return;

            final BuildingAndFloor baf = new BuildingAndFloor(nrd.BuildingID, nrd.FloorNumber);

            if(!buildingFloorData.containsKey(baf))
                buildingFloorData.put(baf, new FloorData(baf.buildingID, baf.floor));

            if(buildingFloorData.containsKey(baf))
            {
                buildingFloorData.get(baf).routeDestinations.add(nrd);
                routeDestinationHashMap.put(nrd.ID.toString(), nrd);

                //draw contents to floor if it is currently being displayed
                if(buildingOverlays.containsKey(nrd.BuildingID) && buildingOverlays.get(nrd.BuildingID).floor == nrd.FloorNumber)
                {
                    final UUID buildingID = nrd.BuildingID;
                    final int floor = nrd.FloorNumber;
                    //refresh floor
                    mapsActivity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            drawFloorContents(buildingID,  floor);
                        }
                    });
                }
            }
        }
    }

    /**
     * Returns a route after calling NeonRouting.routeToDestination() or NeonRouting.routeToCategory()
     * If the function returns SUCCESS, then a route is available and is displayed on the screen
     * as a preview. The user can then select START to begin turn-by-turn directions
     */
    @Override
    public void onComplete(final Route route, RoutingResult routingResult) {

        switch (routingResult) {
            case SUCCESS:

                mapsActivity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        bottomSheetState = BottomSheetState.READY;
                        String filterString = "";
                        if(route.FiltersOnRoute != null && route.FiltersOnRoute.size() == 1)
                        {
                            filterString = "    -   Avoids " + route.FiltersOnRoute.get(0).getFriendlyName();
                        }
                        if (route.FiltersOnRoute != null && route.FiltersOnRoute.size() > 1)
                        {
                            StringBuilder sb = new StringBuilder();
                            for(RouteFilter filter : route.FiltersOnRoute)
                                sb.append(filter.getFriendlyName()).append(", ");
                            filterString = "    -   Avoids " + sb.toString();
                        }

                        setNavSheet("Route available for: " + route.Destination + "  -    " + route.MinutesToDestination + (route.MinutesToDestination > 1 ? " mins (" + route.MetersToDestination + " m)" : " min (" + route.MetersToDestination + " m)") + filterString, null, null);
                        cleanupMap();
                        currentRoute = route;
                        drawRoute();
                    }
                });
                break;
            case ROUTING_SERVICE_NOT_CONNECTED:
                Toast.makeText(mapsActivity.getApplicationContext(), "The Routing Service has not been connected", Toast.LENGTH_SHORT).show();
                break;
            case NO_ROUTE_FOUND:
                Toast.makeText(mapsActivity.getApplicationContext(), "There is no available route to this point of interest", Toast.LENGTH_SHORT).show();
                break;
        }
    }

    /**
     * Turn-by-turn direction update based on the selected route and the user's current position
     * Update is called every second
     *
     * nextInstruction : The current action for the user to take
     * instructionType : The type of action, such as a left turn, that corresponds to an icon
     * nextNodeID : The id of the route node in the route that is upcoming
     * metersToNextNode : The distance to the next node in meters
     * routeState : current state of the route, such as IN_PROGRESS or FINISHED
     * route : if the route is updated, this will provide the new route, otherwise it is null
     * nearbyDestinations : Gives the nearby route destinations from the user's current route
     * metersToDestination : meters until the destination is reached
     * minutesToDestination : minutes until the destinaton is reached
     */
    @Override
    public void update(final String nextInstruction, final RouteInstructionType instructionType, final String nextNodeID, int metersToNextNode, RouteState routeState, Route route, List<NeonRouteDestination> list, int i, int i1) {
        if (bottomSheetState != BottomSheetState.IN_PROGRESS)
            return;

        if (tts != null) {
            tts.speak(nextInstruction, TextToSpeech.QUEUE_FLUSH, null);
        }

        for (NeonRouteDestination nrd : list)
            Log.i(LOG_TAG, "nearby destination: " + nrd.Name);
        mapsActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                currentNodeDestination = getNodeFromID(nextNodeID);
                setNavSheet(nextInstruction, instructionType, currentNodeDestination);
                drawRouteNode();
            }
        });
    }

    /**
     * If the user clicks on a route destination, a dialog will ask if they want to route to that
     * destination. If yes, we will attempt to retrieve a route to the destination from the current location
     */
    boolean onMarkerClick(Marker marker)
    {
        //The selected building
        final UUID markerID = (UUID) marker.getTag();

        if(markerID == null)
            return false;

        //The polygons and overlays drawn to the screen for this building
        final NeonRouteDestination nrd = routeDestinationHashMap.get(markerID.toString());

        if (nrd == null)
            return false;

        Log.i(LOG_TAG, "Clicked on marker id: " + markerID.toString());

        AlertDialog.Builder b = new AlertDialog.Builder(mapsActivity);
        b.setCancelable(true);
        b.setPositiveButton("OK", null);
        b.setNeutralButton("Route", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {

                NeonRouting.routeToDestination(nrd.ID.toString(), currentRouteFilters, dataHandler, NeonRoutingAPIFunctions.this);
            }
        });
        b.setTitle(nrd.Name);
        AlertDialog alert = b.create();

        alert.show();
        return true;
    }

    /**
     * If the user taps on a region of interest polygon, a dialog will ask if they want to route to that
     * destination. If yes, we will attempt to retrieve a route to the destination from the current location
     */
    void onPolygonClick(final Polygon polygon)
    {
        //Each polygon outline on the screen has a tag with the building ID.  We use the
        //building ID to retrieve the list of outlines and images drawn to the screen.
        //We will clear these outlines and draw a new one if the floor changes

        //The selected building
        final UUID polygonID = (UUID) polygon.getTag();

        if(polygonID == null)
            return;

        //The polygons and overlays drawn to the screen for this building
        final FloorOverlay fo = buildingOverlays.get(polygonID);

        if (fo == null)
        {
            final NeonRouteDestination nrd = routeDestinationHashMap.get(polygonID.toString());
            if (nrd == null)
                return;


            Log.i(LOG_TAG, "Clicked on polygon id: " + polygonID.toString());

            AlertDialog.Builder b = new AlertDialog.Builder(mapsActivity);
            b.setCancelable(true);
            b.setPositiveButton("OK", null);
            b.setNeutralButton("Route", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {

                    NeonRouting.routeToDestination(nrd.ID.toString(), currentRouteFilters, dataHandler, NeonRoutingAPIFunctions.this);
                }
            });
            b.setTitle(nrd.Name);
            AlertDialog alert = b.create();

            alert.show();

        }
    }

    /**
     * Draws the currently targeted node
     */
    private void drawRouteNode(RouteNode node)
    {
        if (node == null)
            return;

        LatLng center = new LatLng(node.Latitude, node.Longitude);

        //only draw if the node is not the same as the one we already drew
        if(currentlyDrawnNode != null && currentlyDrawnNode.getCenter().equals(center))
            return;

        if (currentlyDrawnNode != null)
            currentlyDrawnNode.remove();

        currentlyDrawnNode = baseMap.addCircle(new CircleOptions().clickable(false).strokeWidth(0).radius(.5).fillColor(Color.RED).center(center).zIndex(2));
    }

    /**
     * draws the routing shapes and routes if they need to be drawn
     * starts animation for your current route node
     */

    private void drawRouteNode()
    {
        if (currentNodeDestination != null)
            drawRouteNode(currentNodeDestination);
        if (circleAnimation == null && currentlyDrawnNode!= null)
        {
            circleAnimation = makeCircleAnimation();
            circleAnimation.start();
        }
    }

    /**
     * Clears current route info from the screen
     */
    private void clearRoute()
    {
        Route routeToClear = currentRoute;
        currentRoute = null;
        if(routeToClear != null && routeToClear.Nodes.size()>0)
        {
            UUID currentBuilding = routeToClear.Nodes.get(0).BuildingID;
            int currentFloor = routeToClear.Nodes.get(0).Floor;

            for(RouteNode node : routeToClear.Nodes)
            {
                if(!node.BuildingID.equals(currentBuilding) || (node.Floor != null && node.Floor != currentFloor))
                {
                    final BuildingAndFloor baf = new BuildingAndFloor(currentBuilding, currentFloor);
                    if(buildingFloorData.containsKey(baf))
                    {
                        buildingFloorData.get(baf).routes.clear();
                        if(buildingOverlays.containsKey(currentBuilding) && buildingOverlays.get(currentBuilding).floor == currentFloor)
                        {
                            final UUID buildingID = currentBuilding;
                            final int floor = currentFloor;
                            //refresh floor
                            mapsActivity.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    drawFloorContents(buildingID, floor);
                                }
                            });
                        }
                    }
                    currentBuilding = node.BuildingID;
                    currentFloor = node.Floor;
                }
            }

            final BuildingAndFloor baf = new BuildingAndFloor(currentBuilding, currentFloor);
            if(buildingFloorData.containsKey(baf))
            {
                buildingFloorData.get(baf).routes.clear();
                if(buildingOverlays.containsKey(currentBuilding) && buildingOverlays.get(currentBuilding).floor == currentFloor)
                {
                    final UUID buildingID = currentBuilding;
                    final int floor = currentFloor;

                    //refresh floor
                    mapsActivity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            drawFloorContents(buildingID, floor);
                        }
                    });
                }
            }
        }
    }

    /**
     * Draws a retrieved route to the screen
     */
    private void drawRoute()
    {
        if(currentRoute != null && currentRoute.Nodes.size()>0)
        {
            UUID currentBuilding = currentRoute.Nodes.get(0).BuildingID;
            int currentFloor = currentRoute.Nodes.get(0).Floor;
            ArrayList<RouteNode> currentNodes = new ArrayList<>();

            for(RouteNode node : currentRoute.Nodes)
            {
                if(node.BuildingID.equals(currentBuilding) && node.Floor != null && node.Floor == currentFloor)
                    currentNodes.add(node);
                else
                {
                    final BuildingAndFloor baf = new BuildingAndFloor(currentBuilding, currentFloor);
                    if(!buildingFloorData.containsKey(baf))
                    {
                        buildingFloorData.put(baf, new FloorData(baf.buildingID, baf.floor));
                    }

                    if(buildingFloorData.containsKey(baf))
                    {
                        buildingFloorData.get(baf).routes.add(new ArrayList<>(currentNodes));

                        if(buildingOverlays.containsKey(currentBuilding) && buildingOverlays.get(currentBuilding).floor == currentFloor)
                        {
                            final UUID buildingID = currentBuilding;
                            final int floor = currentFloor;
                            //refresh floor
                            mapsActivity.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    drawFloorContents(buildingID,  floor);
                                }
                            });
                        }
                    }

                    currentNodes.clear();
                    currentNodes.add(node);
                    currentBuilding = node.BuildingID;
                    currentFloor = node.Floor;
                }
            }

            if(currentNodes.size()>0)
            {
                final BuildingAndFloor baf = new BuildingAndFloor(currentBuilding, currentFloor);
                if(!buildingFloorData.containsKey(baf))
                {
                    buildingFloorData.put(baf, new FloorData(baf.buildingID, baf.floor));
                }

                if(buildingFloorData.containsKey(baf))
                {
                    buildingFloorData.get(baf).routes.add(new ArrayList<>(currentNodes));

                    if(buildingOverlays.containsKey(currentBuilding) && buildingOverlays.get(currentBuilding).floor == currentFloor)
                    {
                        final UUID buildingID = currentBuilding;
                        final int floor = currentFloor;
                        //refresh floor
                        mapsActivity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                drawFloorContents(buildingID,  floor);
                            }
                        });
                    }
                }
            }
        }
    }

    /**
     * sets the bottoms sheet display for turn-by-turn
     */
    private void setNavSheet(String instructions, RouteInstructionType type, RouteNode node)
    {
        View.OnClickListener stopRouteClick = new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                stopRouting();
            }
        };

        View.OnClickListener startRouteClick = new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                startRouting();
            }
        };

        switch (bottomSheetState)
        {
            default:
            case NO_ROUTE:
                Log.i(LOG_TAG, "routing: setting route to not active");
                bottomSheetBehavior.setHideable(true);
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
                break;
            case READY:
                Log.i(LOG_TAG, "routing: setting route to ready");
                bottomSheetBehavior.setHideable(false);
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
                stopButton.setVisibility(View.GONE);
                separator.setVisibility(View.GONE);
                contextualButton.setVisibility(View.VISIBLE);
                contextualButton.setText("Start");
                contextualButton.setOnClickListener(startRouteClick);
                routeInstructions.setText(instructions);
                break;
            case IN_PROGRESS:
                Log.i(LOG_TAG, "routing: setting route to running");
                stopButton.setVisibility(View.VISIBLE);
                separator.setVisibility(View.VISIBLE);
                stopButton.setOnClickListener(stopRouteClick);
                contextualButton.setVisibility(View.GONE);
                routeInstructions.setText(instructions);
                break;
            case COMPLETED:
                Log.i(LOG_TAG, "routing: setting route to complete");
                stopButton.setVisibility(View.GONE);
                separator.setVisibility(View.GONE);
                contextualButton.setVisibility(View.VISIBLE);
                contextualButton.setText("OK");
                contextualButton.setOnClickListener(stopRouteClick);
                routeInstructions.setText("Route complete.");
                break;
        }

        if (type != null)
        {
            directionView.setVisibility(View.VISIBLE);
            switch (type)
            {
                case LEFT:
                    directionView.setImageResource(R.drawable.ic_left);
                    break;
                case RIGHT:
                    directionView.setImageResource(R.drawable.ic_right);
                    break;
                case STRAIGHT:
                    directionView.setImageResource(R.drawable.ic_straight);
                    break;
                case SLIGHT_LEFT:
                    directionView.setImageResource(R.drawable.ic_slight_left);
                    break;
                case SLIGHT_RIGHT:
                    directionView.setImageResource(R.drawable.ic_slight_right);
                    break;
                case SHARP_LEFT:
                    directionView.setImageResource(R.drawable.ic_sharp_left);
                    break;
                case SHARP_RIGHT:
                    directionView.setImageResource(R.drawable.ic_slight_right);
                    break;
                case U_TURN:
                    directionView.setImageResource(R.drawable.ic_uturn);
                    break;
                case END:
                    directionView.setImageResource(R.drawable.ic_location_on_black);
                    break;
                case START:
                    directionView.setImageResource(R.drawable.ic_navigation);
                    break;
                case STAIR_UP:
                case STAIR_DOWN:
                    directionView.setImageResource(R.drawable.ic_stairs);
                    break;
                case ELEVATOR_DOWN:
                case ELEVATOR_UP:
                    directionView.setImageResource(R.drawable.ic_elevator);
                    break;
                case ENTER_BUILDING:
                case EXIT_BUILDING:
                    directionView.setImageResource(R.drawable.ic_entrance);
                    break;
                default:
                    directionView.setVisibility(View.GONE);
                    break;
            }

        } else
        {
            directionView.setVisibility(View.GONE);
        }
    }

    /**
     * Does a cleanup of all the map elements for routing
     */
    private void cleanupMap() {

        mapsActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                clearRoute();
            }
        });

        if (currentlyDrawnNode != null)
            currentlyDrawnNode.remove();
        if (circleAnimation != null)
            circleAnimation.end();

        currentlyDrawnNode = null;
        circleAnimation = null;

        currentRoute = null;
        currentNodeDestination = null;
    }


    private void stopRouting()
    {
        cleanupMap();
        bottomSheetState = BottomSheetState.NO_ROUTE;
        NeonRouting.cancelTurnByTurnNavigation();
        setNavSheet("", null, null);
    }

    private void startRouting()
    {
        if (bottomSheetState == BottomSheetState.IN_PROGRESS || currentRoute == null)
            return;

        if(!mapsActivity.isFollowing)
        {
            mapsActivity.isFollowing = true;
            mapsActivity.centerOnLocationButton.setImageResource(R.drawable.ic_location_fixed);
        }

        bottomSheetState = BottomSheetState.IN_PROGRESS;
        Log.i(LOG_TAG, "routing: started routing to " + currentRoute.DestinationID);
        NeonRouting.startTurnByTurnNavigation(currentRoute, dataHandler, this);
        setNavSheet("Routing started.", null, null);
    }

    //gets the node before the current node ID
    //if there are a lot of nodes in your route, consider putting this in a separate thread to not block the UI thread
    private RouteNode getPreviousNode(String nodeID)
    {
        if (currentRoute == null)
            return null;
        ListIterator<RouteNode> it = currentRoute.Nodes.listIterator(currentRoute.Nodes.size());
        while(it.hasPrevious())
        {
            RouteNode curNode = it.previous();
            if (curNode.ID.toString().equals(nodeID) && it.hasPrevious())
                return it.previous();
        }
        return null;

    }

    //calculates bearing between two nodes
    //right now using the current node and the previous node to calculate bearing
    void selectOrientation(NeonLocation location)
    {

        if(bottomSheetState != BottomSheetState.IN_PROGRESS)
            return;

        //if we don't have a current node, do nothing
        if (currentNodeDestination == null)
            return;

        //if we don't have a previous node, do nothing
        RouteNode prevNode = getPreviousNode(currentNodeDestination.ID.toString());
        if (prevNode == null)
            return;

        //if both the nodes are elevator or stairwell, it means user is currently in this feature, bearing is not useful here, so return null
        if (currentNodeDestination.Type.equals(prevNode.Type) && ("Elevator".equals(currentNodeDestination.Type) || "Stairwell".equals(currentNodeDestination.Type)))
            return;

        //math to determine bearing based on two coordinates
        double startLat = Math.toRadians(prevNode.Latitude);
        double startLong = Math.toRadians(prevNode.Longitude);
        double endLat = Math.toRadians(currentNodeDestination.Latitude);
        double endLong = Math.toRadians(currentNodeDestination.Longitude);

        double dLong = endLong - startLong;

        double dPhi = Math.log(Math.tan(endLat/2.0+Math.PI/4.0)/Math.tan(startLat/2.0+Math.PI/4.0));
        if (Math.abs(dLong) > Math.PI){
            if (dLong > 0.0)
                dLong = -(2.0 * Math.PI - dLong);
            else
                dLong = (2.0 * Math.PI + dLong);
        }

        float bearing = (float)((Math.toDegrees(Math.atan2(dLong, dPhi)) + 360.0) % 360.0);

        CameraPosition cameraPosition = new CameraPosition.Builder()
                .zoom(25)
                .bearing(bearing)
                .target(new LatLng(location.latitude, location.longitude))
                .build();

        baseMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition), 500, null);
    }

    //gets a node from the current route by ID
    //if there are a lot of nodes in your route, consider putting this in a separate thread to not block the UI thread
    private RouteNode getNodeFromID(String nodeID)
    {
        if (currentRoute == null)
            return null;
        for(RouteNode node: currentRoute.Nodes)
            if (node.ID.toString().equals(nodeID))
                return node;
        return null;
    }

    /**
     * Every five seconds, this runnable will get the area in view on the screen
     * and issue a request to download all the route destinations for that area
     * The work will be performed on the mainHandler, which points to a background thread.
     * Results will be returned to this class where they will be drawn to the screen
     */
    private Runnable loadRouteDestinationsRunnable = new Runnable() {
        @Override
        public void run() {
            Log.i(LOG_TAG, "downloading route destinations");

            if(baseMap == null)
                return;
            //get bounds from google map and convert to LatLongRect
            final LatLngBounds viewingRect = baseMap.getProjection().getVisibleRegion().latLngBounds;
            final LatLongRect bounds = new LatLongRect(new LatLong(viewingRect.southwest.latitude, viewingRect.southwest.longitude), new LatLong(viewingRect.northeast.latitude, viewingRect.northeast.longitude));

            NeonRouting.downloadRouteDestinationsInArea(bounds, dataHandler, NeonRoutingAPIFunctions.this);

            mainHandler.postDelayed(this, 5000);
        }
    };


    void startLoadingRouteDestinations()
    {
        mainHandler.post(loadRouteDestinationsRunnable);
    }

    void onResume()
    {
    }

    void shutdown()
    {
        if (dataLooper != null) {
            dataLooper.quit();
            dataLooper = null;
        }

        mainHandler.removeCallbacks(loadRouteDestinationsRunnable);

        clearMapData();
    }

    void clearMapData()
    {
        routeDestinationHashMap.clear();
        buildingFloorData.clear();
        buildingOverlays.clear();
        if(!bottomSheetState.equals(BottomSheetState.NO_ROUTE))
            stopRouting();
    }

    boolean checkRouting()
    {
        if(bottomSheetState.equals(BottomSheetState.NO_ROUTE))
            return false;

        stopRouting();
        return true;
    }

    /**
     * Draws a floor and its contents to the google map.  Converts to
     * a set of Google Polygons
     */
    void drawFloorContents(UUID buildingID, int floorNumber) {

        BuildingAndFloor baf = new BuildingAndFloor(buildingID, floorNumber);

        //remove existing drawing
        if(buildingOverlays.containsKey(buildingID))
            buildingOverlays.get(buildingID).removeAll();

        FloorData floorData = null;
        if(buildingFloorData.containsKey(baf))
            floorData = buildingFloorData.get(baf);
        else return;

        //draw pois
        ArrayList<Marker> pois = new ArrayList<>();
        ArrayList<Polygon> rois = new ArrayList<>();
        if(floorData.routeDestinations != null)
        {
            for(NeonRouteDestination nrd : floorData.routeDestinations)
            {
                if(nrd instanceof PointOfInterest)
                {
                    Marker m = baseMap.addMarker(new MarkerOptions().position(new LatLng(((PointOfInterest)nrd).Latitude, ((PointOfInterest)nrd).Longitude)));
                    m.setTag(nrd.ID);
                    pois.add(m);
                }
                else if(nrd instanceof RegionOfInterest)
                {
                    RegionOfInterest roi = (RegionOfInterest)nrd;
                    ArrayList<LatLng> ol = new ArrayList<>();
                    for (LatLong ll : roi.Outline.Hull)
                        ol.add(new LatLng(ll.Latitude, ll.Longitude));

                    //construct the inner holes
                    ArrayList<ArrayList<LatLng>> polygonHoles = new ArrayList<>();
                    for (ArrayList<LatLong> llo : roi.Outline.Holes) {
                        ArrayList<LatLng> hol = new ArrayList<>();
                        for (LatLong ll : llo)
                            hol.add(new LatLng(ll.Latitude, ll.Longitude));
                        polygonHoles.add(hol);
                    }

                    Polygon p = baseMap.addPolygon(new PolygonOptions().addAll(ol).fillColor(Color.argb(50, 127, 0,0)).clickable(true));

                    //set holes in the polygon
                    p.setHoles(polygonHoles);

                    //tag it with the roi ID
                    p.setTag(roi.ID);

                    rois.add(p);
                }
            }
        }

        ArrayList<Polyline> routes = new ArrayList<>();
        if(floorData.routes != null)
        {
            for(ArrayList<RouteNode> routeNodes : floorData.routes)
            {
                Log.i(LOG_TAG,"got a route list of size: "+routeNodes.size());
                ArrayList<LatLng> line = new ArrayList<>();
                for(RouteNode node : routeNodes)
                    line.add(new LatLng(node.Latitude, node.Longitude));

                routes.add(baseMap.addPolyline(new PolylineOptions().addAll(line).color(Color.RED)));
            }
        }

        //put the BuildingOverlay object in the hashmap
        buildingOverlays.put(buildingID, new FloorOverlay(buildingID, floorData.floor, pois, rois, routes));
    }

    /**
     * Brings up a list of route destinations that can be reached from the user's current location
     * They can be selected to retrieve a route and start turn-by-turn directions
     */
    void displayRouteDestinations()
    {
        final ArrayList<NeonRouteDestination> destinations = new ArrayList<>();
        if (NeonRouting.getRoutableDestinations(currentRouteFilters) != null && NeonRouting.getRoutableDestinations(currentRouteFilters).size() > 0)
            destinations.addAll(NeonRouting.getRoutableDestinations(currentRouteFilters));
        if (destinations.size() == 0) {
            AlertDialog.Builder builder = new AlertDialog.Builder(mapsActivity);
            builder.setTitle("No route destinations found")
                    .setMessage("Make sure you have a position on a floor inside a building.")
                    .setPositiveButton("OK", null);

            routeDestinationDialog = builder.create();
            routeDestinationDialog.show();
            return;
        }

        final HashMap<String, List<String>> categories = new HashMap<>();
        final HashMap<String, List<String>> categoryIds = new HashMap<>();
        final ArrayList<String> categoryNames = new ArrayList<>();

        for(String category : NeonRouting.getRoutableCategories(currentRouteFilters))
        {
            ArrayList<String> temp = new ArrayList<>();
            temp.add("Nearest "+category+"...");
            categories.put(category, temp);

            ArrayList<String> ids = new ArrayList<>();
            ids.add(category);
            categoryIds.put(category, ids);

            categoryNames.add(category);
        }


        for(NeonRouteDestination destination : destinations)
        {
            if(categories.containsKey(destination.Category))
            {
                String name = destination.Name;
                if(destination.DistanceFromCurrentLocation != null && destination.FloorsFromCurrentLocation != null)
                {
                    if(destination.FloorsFromCurrentLocation == 0)
                        name += String.format(Locale.ENGLISH," (%dm)",destination.DistanceFromCurrentLocation.intValue(),destination.FloorsFromCurrentLocation);
                    else if(destination.FloorsFromCurrentLocation > 0)
                        name += String.format(Locale.ENGLISH," (%dm, up %d floors)",destination.DistanceFromCurrentLocation.intValue(),destination.FloorsFromCurrentLocation);
                    else
                        name += String.format(Locale.ENGLISH," (%dm, down %d floors)",destination.DistanceFromCurrentLocation.intValue(),-destination.FloorsFromCurrentLocation);

                }
                categories.get(destination.Category).add(name);
            }
            if(categoryIds.containsKey(destination.Category))
                categoryIds.get(destination.Category).add(destination.ID.toString());
        }

        LinearLayout filterHolder = new LinearLayout(mapsActivity);
        filterHolder.setOrientation(LinearLayout.HORIZONTAL);
        final ExpandableListView expandableListView = new ExpandableListView(mapsActivity);
        filterHolder.addView(expandableListView);

        //organize by category
        ExpandableListAdapter expandableListAdapter = new ExpandableListAdapter(mapsActivity, categoryNames, categories);
        expandableListView.setAdapter(expandableListAdapter);
        expandableListView.setOnChildClickListener(new ExpandableListView.OnChildClickListener() {
            @Override
            public boolean onChildClick(ExpandableListView parent, View v,
                                        int groupPosition, int childPosition, long id) {

                if(childPosition == 0)
                    NeonRouting.routeToCategory(categoryNames.get(groupPosition), currentRouteFilters, dataHandler, NeonRoutingAPIFunctions.this);
                else
                    NeonRouting.routeToDestination(categoryIds.get(categoryNames.get(groupPosition)).get(childPosition), currentRouteFilters, dataHandler, NeonRoutingAPIFunctions.this);

                routeDestinationDialog.cancel();
                return true;
            }
        });

        AlertDialog.Builder builder = new AlertDialog.Builder(mapsActivity);
        builder.setTitle("Route Destinations")
                .setView(filterHolder)
                .setPositiveButton("OK", null);
        routeDestinationDialog = builder.create();
        routeDestinationDialog.show();
    }

    /**
     * Brings up a list of route filters that can filter certain features, such as stairwells,
     * from the route
     */
    void displayRouteSettings()
    {
        ArrayList<RouteFilter> routeFilters = new ArrayList<>(NeonRouting.getRouteFilters());

        final HashMap<Integer, RouteFilter> routeFilterMap = new HashMap<>();
        for(RouteFilter rf : routeFilters)
            routeFilterMap.put(rf.getFilterIndex(), rf);

        LinearLayout filterHolder = new LinearLayout(mapsActivity);
        filterHolder.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams filterParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        final ArrayList<CheckBox> checkBoxes = new ArrayList<>();
        for (RouteFilter routeFilter : routeFilters) {
            final CheckBox newCheckBox = new CheckBox(mapsActivity);
            newCheckBox.setText(routeFilter.getFriendlyName());
            newCheckBox.setId(routeFilter.getFilterIndex());
            newCheckBox.setLayoutParams(filterParams);
            newCheckBox.setChecked(currentRouteFilters.contains(routeFilter));
            filterHolder.addView(newCheckBox);
            checkBoxes.add(newCheckBox);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(mapsActivity);
        builder.setTitle("Route Filters")
                .setView(filterHolder)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {

                        currentRouteFilters.clear();
                        for (CheckBox checkBox : checkBoxes)
                            if (checkBox.isChecked())
                                currentRouteFilters.add(routeFilterMap.get(checkBox.getId()));
                    }
                })
                .setNegativeButton("Cancel", null);
        routeDestinationDialog = builder.create();
        routeDestinationDialog.show();
    }

    /**
     * Expandable list by routing categories. The first option is to route to the nearest in that
     * category, and then each of the specific destinations are listed by distance from current position
     */
    class ExpandableListAdapter extends BaseExpandableListAdapter {

        private Context context;
        private List<String> expandableListTitle;
        private HashMap<String, List<String>> expandableListDetail;

        ExpandableListAdapter(Context context, List<String> expandableListTitle,
                                     HashMap<String, List<String>> expandableListDetail) {
            this.context = context;
            this.expandableListTitle = expandableListTitle;
            this.expandableListDetail = expandableListDetail;
        }

        @Override
        public Object getChild(int listPosition, int expandedListPosition) {
            return this.expandableListDetail.get(this.expandableListTitle.get(listPosition))
                    .get(expandedListPosition);
        }

        @Override
        public long getChildId(int listPosition, int expandedListPosition) {
            return expandedListPosition;
        }

        @Override
        public View getChildView(int listPosition, final int expandedListPosition,
                                 boolean isLastChild, View convertView, ViewGroup parent) {
            final String expandedListText = (String) getChild(listPosition, expandedListPosition);
            if (convertView == null) {
                LayoutInflater layoutInflater = (LayoutInflater) this.context
                        .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                convertView = layoutInflater.inflate(R.layout.list_item, null);
            }
            TextView expandedListTextView = (TextView) convertView
                    .findViewById(R.id.expandedListItem);
            expandedListTextView.setText(expandedListText);
            return convertView;
        }

        @Override
        public int getChildrenCount(int listPosition) {
            return this.expandableListDetail.get(this.expandableListTitle.get(listPosition))
                    .size();
        }

        @Override
        public Object getGroup(int listPosition) {
            return this.expandableListTitle.get(listPosition);
        }

        @Override
        public int getGroupCount() {
            return this.expandableListTitle.size();
        }

        @Override
        public long getGroupId(int listPosition) {
            return listPosition;
        }

        @Override
        public View getGroupView(int listPosition, boolean isExpanded,
                                 View convertView, ViewGroup parent) {
            String listTitle = (String) getGroup(listPosition);
            if (convertView == null) {
                LayoutInflater layoutInflater = (LayoutInflater) this.context.
                        getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                convertView = layoutInflater.inflate(R.layout.list_group, null);
            }
            TextView listTitleTextView = (TextView) convertView
                    .findViewById(R.id.listTitle);
            listTitleTextView.setTypeface(null, Typeface.BOLD);
            listTitleTextView.setText(listTitle);
            return convertView;
        }

        @Override
        public boolean hasStableIds() {
            return false;
        }

        @Override
        public boolean isChildSelectable(int listPosition, int expandedListPosition) {
            return true;
        }
    }


}
