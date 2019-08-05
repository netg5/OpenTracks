/*
 * Copyright 2010 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package de.dennisguse.opentracks.content;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.location.Location;
import android.test.RenamingDelegatingContext;
import android.test.mock.MockContentResolver;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import de.dennisguse.opentracks.content.ContentProviderUtils.LocationFactory;
import de.dennisguse.opentracks.content.ContentProviderUtils.LocationIterator;
import de.dennisguse.opentracks.content.Waypoint.WaypointType;
import de.dennisguse.opentracks.services.TrackRecordingServiceTest.MockContext;
import de.dennisguse.opentracks.stats.TripStatistics;
import com.google.android.testing.mocking.AndroidMock;
import com.google.android.testing.mocking.UsesMocks;

import org.junit.Assert;
import org.junit.Before;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A unit test for {@link ContentProviderUtilsImpl}.
 *
 * @author Bartlomiej Niechwiej
 * @author Youtao Liu
 */
@RunWith(AndroidJUnit4.class)
public class CustomContentProviderUtilsImplTest {
    private static final String NAME_PREFIX = "test name";
    private static final String MOCK_DESC = "Mock Next Waypoint Desc!";
    private static final String TEST_DESC = "Test Desc!";
    private static final String TEST_DESC_NEW = "Test Desc new!";
    private Context context;
    private ContentProviderUtils providerUtils;
    private double INITIAL_LATITUDE = 37.0;
    private double INITIAL_LONGITUDE = -57.0;
    private double ALTITUDE_INTERVAL = 2.5;

    @Before
    protected void setUp() {
        MockContentResolver mockContentResolver = new MockContentResolver();
        RenamingDelegatingContext targetContext = new RenamingDelegatingContext(
                getContext(), getContext(), "test.");
        context = new MockContext(mockContentResolver, targetContext);
        CustomContentProvider provider = new CustomContentProvider();
        provider.attachInfo(context, null);
        mockContentResolver.addProvider(ContentProviderUtils.AUTHORITY, provider);
        setContext(context);

        providerUtils = ContentProviderUtils.Factory.get(context);
        providerUtils.deleteAllTracks(context);
    }

    public void testLocationIterator_noPoints() {
        testIterator(1, 0, 1, false, ContentProviderUtils.DEFAULT_LOCATION_FACTORY);
    }

    public void testLocationIterator_customFactory() {
        final Location location = new Location("test_location");
        final AtomicInteger counter = new AtomicInteger();
        testIterator(1, 15, 4, false, new LocationFactory() {
            @Override
            public Location createLocation() {
                counter.incrementAndGet();
                return location;
            }
        });
        // Make sure we were called exactly as many times as we had track points.
        Assert.assertEquals(15, counter.get());
    }

    public void testLocationIterator_nullFactory() {
        try {
            testIterator(1, 15, 4, false, null);
            fail("Expecting IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // Expected.
        }
    }

    public void testLocationIterator_noBatchAscending() {
        testIterator(1, 50, 100, false, ContentProviderUtils.DEFAULT_LOCATION_FACTORY);
        testIterator(2, 50, 50, false, ContentProviderUtils.DEFAULT_LOCATION_FACTORY);
    }

    public void testLocationIterator_noBatchDescending() {
        testIterator(1, 50, 100, true, ContentProviderUtils.DEFAULT_LOCATION_FACTORY);
        testIterator(2, 50, 50, true, ContentProviderUtils.DEFAULT_LOCATION_FACTORY);
    }

    public void testLocationIterator_batchAscending() {
        testIterator(1, 50, 11, false, ContentProviderUtils.DEFAULT_LOCATION_FACTORY);
        testIterator(2, 50, 25, false, ContentProviderUtils.DEFAULT_LOCATION_FACTORY);
    }

    public void testLocationIterator_batchDescending() {
        testIterator(1, 50, 11, true, ContentProviderUtils.DEFAULT_LOCATION_FACTORY);
        testIterator(2, 50, 25, true, ContentProviderUtils.DEFAULT_LOCATION_FACTORY);
    }

    public void testLocationIterator_largeTrack() {
        testIterator(1, 20000, 2000, false, ContentProviderUtils.DEFAULT_LOCATION_FACTORY);
    }

    private List<Location> testIterator(long trackId, int numPoints, int batchSize,
                                        boolean descending, LocationFactory locationFactory) {
        long lastPointId = initializeTrack(trackId, numPoints);
        ((ContentProviderUtilsImpl) providerUtils).setDefaultCursorBatchSize(batchSize);
        List<Location> locations = new ArrayList<Location>(numPoints);
        LocationIterator it = providerUtils.getTrackPointLocationIterator(
                trackId, -1L, descending, locationFactory);
        try {
            while (it.hasNext()) {
                Location loc = it.next();
                Assert.assertNotNull(loc);
                locations.add(loc);
                // Make sure the IDs are returned in the right order.
                Assert.assertEquals(descending ? lastPointId - locations.size() + 1
                        : lastPointId - numPoints + locations.size(), it.getLocationId());
            }
            Assert.assertEquals(numPoints, locations.size());
        } finally {
            it.close();
        }
        return locations;
    }

    private long initializeTrack(long id, int numPoints) {
        Track track = new Track();
        track.setId(id);
        track.setName("Test: " + id);
        track.setNumberOfPoints(numPoints);
        providerUtils.insertTrack(track);
        track = providerUtils.getTrack(id);
        Assert.assertNotNull(track);

        Location[] locations = new Location[numPoints];
        for (int i = 0; i < numPoints; ++i) {
            Location loc = new Location("test");
            loc.setLatitude(37.0 + (double) i / 10000.0);
            loc.setLongitude(57.0 - (double) i / 10000.0);
            loc.setAccuracy((float) i / 100.0f);
            loc.setAltitude(i * 2.5);
            locations[i] = loc;
        }
        providerUtils.bulkInsertTrackPoint(locations, numPoints, id);

        // Load all inserted locations.
        long lastPointId = -1;
        int counter = 0;
        LocationIterator it = providerUtils.getTrackPointLocationIterator(id, -1L, false,
                ContentProviderUtils.DEFAULT_LOCATION_FACTORY);
        try {
            while (it.hasNext()) {
                it.next();
                lastPointId = it.getLocationId();
                counter++;
            }
        } finally {
            it.close();
        }

        Assert.assertTrue(numPoints == 0 || lastPointId > 0);
        Assert.assertEquals(numPoints, track.getNumberOfPoints());
        Assert.assertEquals(numPoints, counter);

        return lastPointId;
    }

    /**
     * Tests the method {@link ContentProviderUtilsImpl#createTrack(Cursor)}.
     */
    @UsesMocks(Cursor.class)
    public void testCreateTrack() {
        Cursor cursorMock = AndroidMock.createNiceMock(Cursor.class);
        int startColumnIndex = 1;
        int columnIndex = startColumnIndex;
        AndroidMock.expect(cursorMock.getColumnIndexOrThrow(TracksColumns._ID))
                .andReturn(columnIndex++);
        AndroidMock.expect(cursorMock.getColumnIndexOrThrow(TracksColumns.NAME)).andReturn(
                columnIndex++);
        columnIndex = startColumnIndex;
        // Id
        AndroidMock.expect(cursorMock.isNull(columnIndex++)).andReturn(false);
        // Name
        AndroidMock.expect(cursorMock.isNull(columnIndex++)).andReturn(false);
        long trackId = System.currentTimeMillis();
        columnIndex = startColumnIndex;
        // Id
        AndroidMock.expect(cursorMock.getLong(columnIndex++)).andReturn(trackId);
        // Name
        String name = NAME_PREFIX + trackId;
        AndroidMock.expect(cursorMock.getString(columnIndex++)).andReturn(name);
        AndroidMock.replay(cursorMock);
        Track track = providerUtils.createTrack(cursorMock);
        Assert.assertEquals(trackId, track.getId());
        Assert.assertEquals(name, track.getName());
        AndroidMock.verify(cursorMock);
    }

    /**
     * Tests the method {@link ContentProviderUtilsImpl#deleteAllTracks(Context)}
     */
    public void testDeleteAllTracks() {
        // Insert track, points and waypoint at first.
        long trackId = System.currentTimeMillis();
        Track track = getTrack(trackId, 10);
        insertTrackWithLocations(track);
        Waypoint waypoint = new Waypoint();
        providerUtils.insertWaypoint(waypoint);
        ContentResolver contentResolver = context.getContentResolver();
        Cursor tracksCursor = contentResolver.query(TracksColumns.CONTENT_URI, null, null, null,
                TracksColumns._ID);
        Assert.assertEquals(1, tracksCursor.getCount());
        Cursor tracksPointsCursor = contentResolver.query(TrackPointsColumns.CONTENT_URI, null, null,
                null, TrackPointsColumns._ID);
        Assert.assertEquals(10, tracksPointsCursor.getCount());
        Cursor waypointCursor = contentResolver.query(WaypointsColumns.CONTENT_URI, null, null,
                null, WaypointsColumns._ID);
        Assert.assertEquals(1, waypointCursor.getCount());
        // Delete all.
        providerUtils.deleteAllTracks(context);
        // Check whether all have been deleted.
        tracksCursor = contentResolver.query(TracksColumns.CONTENT_URI, null, null, null,
                TracksColumns._ID);
        Assert.assertEquals(0, tracksCursor.getCount());
        tracksPointsCursor = contentResolver.query(TrackPointsColumns.CONTENT_URI, null, null,
                null, TrackPointsColumns._ID);
        Assert.assertEquals(0, tracksPointsCursor.getCount());
        waypointCursor = contentResolver.query(WaypointsColumns.CONTENT_URI, null, null,
                null, WaypointsColumns._ID);
        Assert.assertEquals(0, waypointCursor.getCount());
    }

    /**
     * Tests the method {@link ContentProviderUtilsImpl#deleteTrack(Context, long)}.
     */
    public void testDeleteTrack() {
        // Insert three tracks, points of two tracks and way point of one track.
        long trackId = System.currentTimeMillis();
        Track track = getTrack(trackId, 10);

        providerUtils.insertTrack(track);
        insertTrackWithLocations(getTrack(trackId + 1, 10));
        insertTrackWithLocations(getTrack(trackId + 2, 10));

        Waypoint waypoint = new Waypoint();
        waypoint.setTrackId(trackId);
        providerUtils.insertWaypoint(waypoint);

        ContentResolver contentResolver = context.getContentResolver();
        Cursor tracksCursor = contentResolver.query(TracksColumns.CONTENT_URI, null, null, null,
                TracksColumns._ID);
        Assert.assertEquals(3, tracksCursor.getCount());
        Cursor tracksPointsCursor = contentResolver.query(TrackPointsColumns.CONTENT_URI, null, null,
                null, TrackPointsColumns._ID);
        Assert.assertEquals(20, tracksPointsCursor.getCount());
        Cursor waypointCursor = contentResolver.query(WaypointsColumns.CONTENT_URI, null, null,
                null, WaypointsColumns._ID);
        Assert.assertEquals(1, waypointCursor.getCount());
        // Delete one track.
        providerUtils.deleteTrack(context, trackId);
        // Check whether all data of a track has been deleted.
        tracksCursor = contentResolver.query(TracksColumns.CONTENT_URI, null, null, null,
                TracksColumns._ID);
        Assert.assertEquals(2, tracksCursor.getCount());
        tracksPointsCursor = contentResolver.query(TrackPointsColumns.CONTENT_URI, null, null,
                null, TrackPointsColumns._ID);
        Assert.assertEquals(20, tracksPointsCursor.getCount());
        waypointCursor = contentResolver.query(WaypointsColumns.CONTENT_URI, null, null,
                null, WaypointsColumns._ID);
        Assert.assertEquals(0, waypointCursor.getCount());
    }

    /**
     * Tests the method {@link ContentProviderUtilsImpl#getAllTracks()}
     */
    public void testGetAllTracks() {
        int initialTrackNumber = providerUtils.getAllTracks().size();
        long trackId = System.currentTimeMillis();
        providerUtils.insertTrack(getTrack(trackId, 0));
        List<Track> allTracks = providerUtils.getAllTracks();
        Assert.assertEquals(initialTrackNumber + 1, allTracks.size());
        Assert.assertEquals(trackId, allTracks.get(allTracks.size() - 1).getId());
    }

    /**
     * Tests the method {@link ContentProviderUtilsImpl#getLastTrack()}
     */
    public void testGetLastTrack() {
        long trackId = System.currentTimeMillis();
        providerUtils.insertTrack(getTrack(trackId, 0));
        Assert.assertEquals(trackId, providerUtils.getLastTrack().getId());
    }

    /**
     * Tests the method {@link ContentProviderUtilsImpl#getTrack(long)}
     */
    public void testGetTrack() {
        long trackId = System.currentTimeMillis();
        providerUtils.insertTrack(getTrack(trackId, 0));
        Assert.assertNotNull(providerUtils.getTrack(trackId));
    }

    /**
     * Tests the method {@link ContentProviderUtilsImpl#updateTrack(Track)}
     */
    public void testUpdateTrack() {
        long trackId = System.currentTimeMillis();
        Track track = getTrack(trackId, 0);
        String nameOld = "name1";
        String nameNew = "name2";
        track.setName(nameOld);
        providerUtils.insertTrack(track);
        Assert.assertEquals(nameOld, providerUtils.getTrack(trackId).getName());
        track.setName(nameNew);
        providerUtils.updateTrack(track);
        Assert.assertEquals(nameNew, providerUtils.getTrack(trackId).getName());
    }

    /**
     * Tests the method {@link ContentProviderUtilsImpl#createContentValues(Waypoint)}.
     */
    public void testCreateContentValues_waypoint() {
        long trackId = System.currentTimeMillis();
        Track track = getTrack(trackId, 10);
        providerUtils.insertTrack(track);
        // Bottom
        long startTime = 1000L;
        // AverageSpeed
        double minGrade = -20.11;
        TripStatistics statistics = new TripStatistics();
        statistics.setStartTime(startTime);
        statistics.setStopTime(2500L);
        statistics.setTotalTime(1500L);
        statistics.setMovingTime(700L);
        statistics.setTotalDistance(750.0);
        statistics.setTotalElevationGain(50.0);
        statistics.setMaxSpeed(60.0);
        statistics.setMaxElevation(1250.0);
        statistics.setMinElevation(1200.0);
        statistics.setMaxGrade(15.0);
        statistics.setMinGrade(minGrade);
        statistics.setBounds(-10000, 20000, 30000, -40000);
        // Insert at first.
        Waypoint waypoint = new Waypoint();
        waypoint.setDescription(TEST_DESC);
        waypoint.setType(WaypointType.STATISTICS);
        waypoint.setTripStatistics(statistics);

        Location loc = new Location("test");
        loc.setLatitude(22);
        loc.setLongitude(22);
        loc.setAccuracy((float) 1 / 100.0f);
        loc.setAltitude(2.5);
        waypoint.setLocation(loc);
        providerUtils.insertWaypoint(waypoint);

        ContentProviderUtilsImpl contentProviderUtils = new ContentProviderUtilsImpl(
                new MockContentResolver());

        long waypointId = System.currentTimeMillis();
        waypoint.setId(waypointId);
        ContentValues contentValues = contentProviderUtils.createContentValues(waypoint);
        Assert.assertEquals(waypointId, contentValues.get(WaypointsColumns._ID));
        Assert.assertEquals(22 * 1000000, contentValues.get(WaypointsColumns.LONGITUDE));
        Assert.assertEquals(TEST_DESC, contentValues.get(WaypointsColumns.DESCRIPTION));
        Assert.assertEquals(startTime, contentValues.get(WaypointsColumns.STARTTIME));
        Assert.assertEquals(minGrade, contentValues.get(WaypointsColumns.MINGRADE));
    }

    /**
     * Tests the method {@link ContentProviderUtilsImpl#createWaypoint(Cursor)}.
     */
    @UsesMocks(Cursor.class)
    public void testCreateWaypoint() {
        Cursor cursorMock = AndroidMock.createNiceMock(Cursor.class);
        int startColumnIndex = 1;
        int columnIndex = startColumnIndex;
        AndroidMock.expect(cursorMock.getColumnIndexOrThrow(WaypointsColumns._ID))
                .andReturn(columnIndex++);
        AndroidMock.expect(cursorMock.getColumnIndexOrThrow(WaypointsColumns.NAME)).andReturn(
                columnIndex++);
        AndroidMock.expect(cursorMock.getColumnIndexOrThrow(WaypointsColumns.TRACKID)).andReturn(
                columnIndex++);
        columnIndex = startColumnIndex;
        // Id
        AndroidMock.expect(cursorMock.isNull(columnIndex++)).andReturn(false);
        // Name
        AndroidMock.expect(cursorMock.isNull(columnIndex++)).andReturn(false);
        // trackIdIndex
        AndroidMock.expect(cursorMock.isNull(columnIndex++)).andReturn(false);
        long id = System.currentTimeMillis();
        columnIndex = startColumnIndex;
        // Id
        AndroidMock.expect(cursorMock.getLong(columnIndex++)).andReturn(id);
        // Name
        String name = NAME_PREFIX + id;
        AndroidMock.expect(cursorMock.getString(columnIndex++)).andReturn(name);
        // trackIdIndex
        long trackId = 11L;
        AndroidMock.expect(cursorMock.getLong(columnIndex++)).andReturn(trackId);
        AndroidMock.replay(cursorMock);
        Waypoint waypoint = providerUtils.createWaypoint(cursorMock);
        Assert.assertEquals(id, waypoint.getId());
        Assert.assertEquals(name, waypoint.getName());
        Assert.assertEquals(trackId, waypoint.getTrackId());
        AndroidMock.verify(cursorMock);
    }

    /**
     * Tests the method
     * {@link ContentProviderUtilsImpl#deleteWaypoint(Context, long, DescriptionGenerator)}
     * when there is only one waypoint in the track.
     */
    public void testDeleteWaypoint_onlyOneWayPoint() {
        long trackId = System.currentTimeMillis();
        Track track = getTrack(trackId, 10);
        providerUtils.insertTrack(track);

        // Insert at first.
        Waypoint waypoint1 = new Waypoint();
        waypoint1.setDescription(TEST_DESC);
        waypoint1.setTrackId(trackId);
        waypoint1.setType(WaypointType.STATISTICS);
        providerUtils.insertWaypoint(waypoint1);

        // Delete
        DescriptionGenerator descriptionGenerator = new DescriptionGenerator() {

            @Override
            public String generateWaypointDescription(TripStatistics tripStatistics) {
                return CustomContentProviderUtilsImplTest.MOCK_DESC;
            }

            @Override
            public String generateTrackDescription(Track aTrack, Vector<Double> distances,
                                                   Vector<Double> elevations, boolean html) {
                return null;
            }
        };
        providerUtils.deleteWaypoint(context, 1, descriptionGenerator);

        Assert.assertNull(providerUtils.getWaypoint(1));
    }

    /**
     * Tests the method
     * {@link ContentProviderUtilsImpl#deleteWaypoint(Context, long, DescriptionGenerator)}
     * when there is more than one waypoint in the track.
     */
    public void testDeleteWaypoint_hasNextWayPoint() {
        long trackId = System.currentTimeMillis();
        Track track = getTrack(trackId, 10);
        providerUtils.insertTrack(track);

        TripStatistics statistics = new TripStatistics();
        statistics.setStartTime(1000L);
        statistics.setStopTime(2500L);
        statistics.setTotalTime(1500L);
        statistics.setMovingTime(700L);
        statistics.setTotalDistance(750.0);
        statistics.setTotalElevationGain(50.0);
        statistics.setMaxSpeed(60.0);
        statistics.setMaxElevation(1250.0);
        statistics.setMinElevation(1200.0);
        statistics.setMaxGrade(15.0);
        statistics.setMinGrade(-25.0);
        statistics.setBounds(-10000, 20000, 30000, -40000);
        // Insert at first.
        Waypoint waypoint1 = new Waypoint();
        waypoint1.setDescription(Long.toString(trackId));
        waypoint1.setTrackId(trackId);
        waypoint1.setType(WaypointType.STATISTICS);
        waypoint1.setTripStatistics(statistics);
        providerUtils.insertWaypoint(waypoint1);
        Waypoint waypoint2 = new Waypoint();
        waypoint2.setDescription(Long.toString(trackId));
        waypoint2.setTrackId(trackId);
        waypoint2.setType(WaypointType.STATISTICS);
        waypoint2.setTripStatistics(statistics);
        providerUtils.insertWaypoint(waypoint2);

        // Delete
        DescriptionGenerator descriptionGenerator = new DescriptionGenerator() {
            @Override
            public String generateWaypointDescription(TripStatistics tripStatistics) {
                return CustomContentProviderUtilsImplTest.MOCK_DESC;
            }

            @Override
            public String generateTrackDescription(Track aTrack, Vector<Double> distances,
                                                   Vector<Double> elevations, boolean html) {
                return null;
            }
        };
        providerUtils.deleteWaypoint(context, 1, descriptionGenerator);

        Assert.assertNull(providerUtils.getWaypoint(1));
        Assert.assertEquals(CustomContentProviderUtilsImplTest.MOCK_DESC, providerUtils.getWaypoint(2)
                .getDescription());
    }

    /**
     * Tests the method {@link ContentProviderUtilsImpl#getFirstWaypointId(long)}.
     */
    public void testGetFirstWaypointId() {
        long trackId = System.currentTimeMillis();
        Track track = getTrack(trackId, 10);
        providerUtils.insertTrack(track);

        Waypoint waypoint1 = new Waypoint();
        waypoint1.setTrackId(trackId);
        Waypoint waypoint2 = new Waypoint();
        waypoint2.setTrackId(trackId);
        providerUtils.insertWaypoint(waypoint1);
        providerUtils.insertWaypoint(waypoint2);

        Assert.assertEquals(-1L, providerUtils.getFirstWaypointId(-1));
        Assert.assertEquals(1L, providerUtils.getFirstWaypointId(trackId));
    }

    /**
     * Tests the method {@link ContentProviderUtilsImpl#getNextWaypointNumber(long, WaypointType)}.
     */
    public void testGetNextWaypointNumber() {
        long trackId = System.currentTimeMillis();
        Track track = getTrack(trackId, 10);
        providerUtils.insertTrack(track);

        Waypoint waypoint1 = new Waypoint();
        waypoint1.setType(WaypointType.STATISTICS);
        waypoint1.setTrackId(trackId);
        Waypoint waypoint2 = new Waypoint();
        waypoint2.setType(WaypointType.WAYPOINT);
        waypoint2.setTrackId(trackId);
        Waypoint waypoint3 = new Waypoint();
        waypoint3.setType(WaypointType.STATISTICS);
        waypoint3.setTrackId(trackId);
        Waypoint waypoint4 = new Waypoint();
        waypoint4.setType(WaypointType.WAYPOINT);
        waypoint4.setTrackId(trackId);
        providerUtils.insertWaypoint(waypoint1);
        providerUtils.insertWaypoint(waypoint2);
        providerUtils.insertWaypoint(waypoint3);
        providerUtils.insertWaypoint(waypoint4);

        Assert.assertEquals(2, providerUtils.getNextWaypointNumber(trackId, WaypointType.STATISTICS));
        Assert.assertEquals(3, providerUtils.getNextWaypointNumber(trackId, WaypointType.WAYPOINT));
    }

    /**
     * Tests the method
     * {@link ContentProviderUtils#getLastWaypoint(long, WaypointType)}.
     */
    public void testGetLastStatisticsWaypoint() {
        long trackId = System.currentTimeMillis();
        Track track = getTrack(trackId, 10);
        providerUtils.insertTrack(track);

        Waypoint waypoint1 = new Waypoint();
        waypoint1.setTrackId(trackId);
        waypoint1.setType(WaypointType.STATISTICS);
        waypoint1.setDescription("Desc1");
        Waypoint waypoint2 = new Waypoint();
        waypoint2.setTrackId(trackId);
        waypoint2.setType(WaypointType.STATISTICS);
        waypoint2.setDescription("Desc2");
        Waypoint waypoint3 = new Waypoint();
        waypoint3.setTrackId(trackId);
        waypoint3.setType(WaypointType.WAYPOINT);
        waypoint3.setDescription("Desc3");
        providerUtils.insertWaypoint(waypoint1);
        providerUtils.insertWaypoint(waypoint2);
        providerUtils.insertWaypoint(waypoint3);

        Assert.assertEquals("Desc2", providerUtils.getLastWaypoint(trackId, WaypointType.STATISTICS).getDescription());
    }

    /**
     * Tests the method {@link ContentProviderUtilsImpl#insertWaypoint(Waypoint)} and
     * {@link ContentProviderUtilsImpl#getWaypoint(long)}.
     */
    public void testInsertAndGetWaypoint() {
        long trackId = System.currentTimeMillis();
        Track track = getTrack(trackId, 10);
        providerUtils.insertTrack(track);

        Waypoint waypoint = new Waypoint();
        waypoint.setDescription(TEST_DESC);
        waypoint.setTrackId(trackId);
        providerUtils.insertWaypoint(waypoint);

        Assert.assertEquals(TEST_DESC, providerUtils.getWaypoint(1).getDescription());
    }

    /**
     * Tests the method {@link ContentProviderUtilsImpl#updateWaypoint(Waypoint)}.
     */
    public void testUpdateWaypoint() {
        long trackId = System.currentTimeMillis();
        Track track = getTrack(trackId, 10);
        providerUtils.insertTrack(track);
        // Insert at first.
        Waypoint waypoint = new Waypoint();
        waypoint.setDescription(TEST_DESC);
        waypoint.setTrackId(trackId);
        providerUtils.insertWaypoint(waypoint);
        // Update
        waypoint = providerUtils.getWaypoint(1);
        waypoint.setDescription(TEST_DESC_NEW);
        providerUtils.updateWaypoint(waypoint);

        Assert.assertEquals(TEST_DESC_NEW, providerUtils.getWaypoint(1).getDescription());
    }

    /**
     * Tests the method {@link ContentProviderUtilsImpl#bulkInsertTrackPoint(Location[],
     * int, long)}.
     */
    public void testBulkInsertTrackPoint() {
        // Insert track, point at first.
        long trackId = System.currentTimeMillis();
        Track track = getTrack(trackId, 10);
        insertTrackWithLocations(track);

        providerUtils.bulkInsertTrackPoint(track.getLocations().toArray(new Location[0]), -1, trackId);
        Assert.assertEquals(20, providerUtils.getTrackPointCursor(trackId, -1L, 1000, false).getCount());
        providerUtils.bulkInsertTrackPoint(track.getLocations().toArray(new Location[0]), 8, trackId);
        Assert.assertEquals(28, providerUtils.getTrackPointCursor(trackId, -1L, 1000, false).getCount());
    }

    /**
     * Tests the method {@link ContentProviderUtilsImpl#createTrackPoint(Cursor)}.
     */
    @UsesMocks(Cursor.class)
    public void testCreateTrackPoint() {
        Cursor cursorMock = AndroidMock.createNiceMock(Cursor.class);

        // Set index.
        int index = 1;
        // Id
        AndroidMock.expect(cursorMock.getColumnIndex(TrackPointsColumns._ID)).andReturn(index++);
        // Longitude
        AndroidMock.expect(cursorMock.getColumnIndexOrThrow(TrackPointsColumns.LONGITUDE)).andReturn(
                index++);
        // Latitude
        AndroidMock.expect(cursorMock.getColumnIndexOrThrow(TrackPointsColumns.LATITUDE)).andReturn(
                index++);
        // Time
        AndroidMock.expect(cursorMock.getColumnIndexOrThrow(TrackPointsColumns.TIME))
                .andReturn(index++);
        // Speed
        AndroidMock.expect(cursorMock.getColumnIndexOrThrow(TrackPointsColumns.SPEED)).andReturn(
                index++);
        // Sensor
        AndroidMock.expect(cursorMock.getColumnIndexOrThrow(TrackPointsColumns.SENSOR_HEARTRATE)).andReturn(
                index++);

        // Set return value of isNull().
        index = 2;
        // Longitude
        AndroidMock.expect(cursorMock.isNull(index++)).andReturn(false);
        // Latitude
        AndroidMock.expect(cursorMock.isNull(index++)).andReturn(false);
        // Time
        AndroidMock.expect(cursorMock.isNull(index++)).andReturn(false);
        // Speed
        AndroidMock.expect(cursorMock.isNull(index++)).andReturn(false);
        // Sensor
        AndroidMock.expect(cursorMock.isNull(index++)).andReturn(false);

        // Set return value of isNull().
        index = 2;
        // Longitude
        int longitude = 11;
        AndroidMock.expect(cursorMock.getInt(index++)).andReturn(longitude * 1000000);
        // Latitude.
        int latitude = 22;
        AndroidMock.expect(cursorMock.getInt(index++)).andReturn(latitude * 1000000);
        // Time
        long time = System.currentTimeMillis();
        AndroidMock.expect(cursorMock.getLong(index++)).andReturn(time);
        // Speed
        float speed = 2.2f;
        AndroidMock.expect(cursorMock.getFloat(index++)).andReturn(speed);
        // Sensor
        byte[] sensor = "Sensor state".getBytes();
        AndroidMock.expect(cursorMock.getBlob(index++)).andReturn(sensor);

        AndroidMock.replay(cursorMock);
        Location location = providerUtils.createTrackPoint(cursorMock);
        Assert.assertEquals((double) longitude, location.getLongitude());
        Assert.assertEquals((double) latitude, location.getLatitude());
        Assert.assertEquals(time, location.getTime());
        Assert.assertEquals(speed, location.getSpeed());
        AndroidMock.verify(cursorMock);
    }

    /**
     * Tests the method
     * {@link ContentProviderUtilsImpl#insertTrackPoint(Location, long)}.
     */
    public void testInsertTrackPoint() {
        // Insert track, point at first.
        long trackId = System.currentTimeMillis();
        Track track = getTrack(trackId, 10);
        insertTrackWithLocations(track);

        providerUtils.insertTrackPoint(createLocation(22), trackId);
        Assert.assertEquals(11, providerUtils.getTrackPointCursor(trackId, -1L, 1000, false).getCount());
    }

    /**
     * Tests the method {@link ContentProviderUtilsImpl#getFirstTrackPointId(long)}.
     */
    public void testGetFirstTrackPointId() {
        // Insert track, point at first.
        long trackId = System.currentTimeMillis();
        Track track = getTrack(trackId, 10);
        insertTrackWithLocations(track);

        Assert.assertEquals(1, providerUtils.getFirstTrackPointId(trackId));
    }

    /**
     * Tests the method {@link ContentProviderUtilsImpl#getLastTrackPointId(long)}.
     */
    public void testGetLastTrackPointId() {
        // Insert track, point at first.
        long trackId = System.currentTimeMillis();
        Track track = getTrack(trackId, 10);
        insertTrackWithLocations(track);

        Assert.assertEquals(10, providerUtils.getLastTrackPointId(trackId));
    }

    /**
     * Tests the method {@link ContentProviderUtilsImpl#getLastValidTrackPoint(long)}.
     */
    public void testGetLastValidTrackPoint() {
        // Insert track, points at first.
        long trackId = System.currentTimeMillis();
        Track track = getTrack(trackId, 10);
        insertTrackWithLocations(track);

        Location lastLocation = providerUtils.getLastValidTrackPoint(trackId);
        checkLocation(9, lastLocation);
    }

    /**
     * Tests the method
     * {@link ContentProviderUtilsImpl#getTrackPointCursor(long, long, int, boolean)}
     * in descending.
     */
    public void testGetTrackPointCursor_desc() {
        // Insert track, points at first.
        long trackId = System.currentTimeMillis();
        Track track = getTrack(trackId, 10);
        insertTrackWithLocations(track);

        Cursor cursor = providerUtils.getTrackPointCursor(trackId, 2L, 5, true);
        Assert.assertEquals(2, cursor.getCount());
    }

    /**
     * Tests the method
     * {@link ContentProviderUtilsImpl#getTrackPointCursor(long, long, int, boolean)}
     * in ascending.
     */
    public void testGetTrackPointCursor_asc() {
        // Insert track, points at first.
        long trackId = System.currentTimeMillis();
        Track track = getTrack(trackId, 10);
        insertTrackWithLocations(track);

        Cursor cursor = providerUtils.getTrackPointCursor(trackId, 2L, 5, false);
        Assert.assertEquals(5, cursor.getCount());
    }

    /**
     * Tests the method
     * {@link ContentProviderUtilsImpl#getTrackPointLocationIterator(long, long, boolean, LocationFactory)}
     * in descending.
     */
    public void testGetTrackPointLocationIterator_desc() {
        // Insert track, points at first.
        long trackId = System.currentTimeMillis();
        Track track = getTrack(trackId, 10);
        insertTrackWithLocations(track);

        long startTrackPointId = 2L;

        LocationIterator locationIterator = providerUtils.getTrackPointLocationIterator(trackId,
                startTrackPointId, true, ContentProviderUtils.DEFAULT_LOCATION_FACTORY);
        for (int i = 1; i >= 0; i--) {
            Assert.assertTrue(locationIterator.hasNext());
            Location location = locationIterator.next();
            Assert.assertEquals(2 + (i - 1), locationIterator.getLocationId());
            checkLocation(i, location);
        }
        Assert.assertFalse(locationIterator.hasNext());
    }

    /**
     * Tests the method
     * {@link ContentProviderUtilsImpl#getTrackPointLocationIterator(long, long, boolean, LocationFactory)}
     * in ascending.
     */
    public void testGetTrackPointLocationIterator_asc() {
        // Insert track, point at first.
        long trackId = System.currentTimeMillis();
        Track track = getTrack(trackId, 10);
        insertTrackWithLocations(track);

        long startTrackPointId = 2L;

        LocationIterator locationIterator = providerUtils.getTrackPointLocationIterator(trackId,
                startTrackPointId, false, ContentProviderUtils.DEFAULT_LOCATION_FACTORY);

        for (int i = 1; i < 10; i++) {
            Assert.assertTrue(locationIterator.hasNext());
            Location location = locationIterator.next();
            Assert.assertEquals(2 + (i - 1), locationIterator.getLocationId());
            checkLocation(i, location);
        }
        Assert.assertFalse(locationIterator.hasNext());
    }

    /**
     * Simulates a track which is used for testing.
     *
     * @param id        the id of the track
     * @param numPoints the location number in the track
     * @return the simulated track
     */
    private Track getTrack(long id, int numPoints) {
        Track track = new Track();
        track.setId(id);
        track.setName("Test: " + id);
        track.setNumberOfPoints(numPoints);
        for (int i = 0; i < numPoints; i++) {
            track.addLocation(createLocation(i));
        }
        return track;
    }

    /**
     * Creates a location.
     *
     * @param i the index to set the value of location.
     * @return created location
     */
    private Location createLocation(int i) {

        Location loc = new Location("test");
        loc.setLatitude(INITIAL_LATITUDE + (double) i / 10000.0);
        loc.setLongitude(INITIAL_LONGITUDE - (double) i / 10000.0);
        loc.setAccuracy((float) i / 100.0f);
        loc.setAltitude(i * ALTITUDE_INTERVAL);
        return loc;
    }

    /**
     * Checks the value of a location.
     *
     * @param i        the index of this location which created in the method
     *                 {@link CustomContentProviderUtilsImplTest#getTrack(long, int)}
     * @param location the location to be checked
     */
    private void checkLocation(int i, Location location) {
        Assert.assertEquals(INITIAL_LATITUDE + (double) i / 10000.0, location.getLatitude());
        Assert.assertEquals(INITIAL_LONGITUDE - (double) i / 10000.0, location.getLongitude());
        Assert.assertEquals((float) i / 100.0f, location.getAccuracy());
        Assert.assertEquals(i * ALTITUDE_INTERVAL, location.getAltitude());
    }

    /**
     * Inserts a track with locations into the database.
     *
     * @param track track to be inserted
     */
    private void insertTrackWithLocations(Track track) {
        providerUtils.insertTrack(track);
        providerUtils.bulkInsertTrackPoint(track.getLocations().toArray(new Location[0]), track
                .getLocations().size(), track.getId());
    }
}