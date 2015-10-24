package com.o3dr.android.client.apis;

import android.content.Context;
import android.os.Bundle;
import android.util.SparseArray;

import com.o3dr.services.android.lib.drone.attribute.AttributeType;
import com.o3dr.services.android.lib.drone.property.Attitude;
import com.o3dr.services.android.lib.model.AbstractCommandListener;
import com.o3dr.services.android.lib.model.action.Action;

import org.droidplanner.services.android.mock.MockDrone;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static com.o3dr.services.android.lib.drone.action.ControlActions.ACTION_SET_VELOCITY;
import static com.o3dr.services.android.lib.drone.action.ControlActions.EXTRA_VELOCITY_X;
import static com.o3dr.services.android.lib.drone.action.ControlActions.EXTRA_VELOCITY_Y;
import static com.o3dr.services.android.lib.drone.action.ControlActions.EXTRA_VELOCITY_Z;

/**
 * Created by Fredia Huya-Kouadio on 10/23/15.
 */
@RunWith(RobolectricTestRunner.class)
@Config(emulateSdk = 18)
public class ControlApiTest {

    private static final SparseArray<float[]> expectedVelocitiesPerAttitude = new SparseArray<>();
    static {
        expectedVelocitiesPerAttitude.append(0, new float[]{1f, 1f, 1f});
        expectedVelocitiesPerAttitude.append(45, new float[]{0f, (float) Math.sqrt(2), 1f});
        expectedVelocitiesPerAttitude.append(90, new float[]{-1f, 1f, 1f});
        expectedVelocitiesPerAttitude.append(135, new float[]{-(float) Math.sqrt(2), 0, 1f});
        expectedVelocitiesPerAttitude.append(180, new float[]{-1f, -1f, 1f});
        expectedVelocitiesPerAttitude.append(225, new float[]{0f, -(float) Math.sqrt(2), 1f});
        expectedVelocitiesPerAttitude.append(270, new float[]{1f, -1f, 1f});
        expectedVelocitiesPerAttitude.append(315, new float[]{(float) Math.sqrt(2), 0, 1f});
        expectedVelocitiesPerAttitude.append(360, new float[]{1f, 1f, 1f});
    }

    /**
     * Tests the ControlApi#moveAtVelocity(...) method.
     * Ensures the method correctly interpret its given parameters.
     *
     * @throws Exception
     */
    @Test
    public void testMoveAtVelocity() throws Exception {
        final Context context = Robolectric.getShadowApplication().getApplicationContext();
        final MockDrone mockDrone = new MockDrone(context) {
            @Override
            public boolean performAsyncActionOnDroneThread(Action action, AbstractCommandListener listener) {
                this.asyncAction = action;
                return true;
            }

        };

        final ControlApi controlApi = ControlApi.getApi(mockDrone);

        //Test with the EARTH NED coordinate frame. What goes in should be what comes out.
        final int testCount = 100;
        for(int i= 0; i < testCount; i++){
            final float randomX = (float) ((Math.random() * 2) - 1f);
            final float randomY =(float) ((Math.random() * 2) - 1f);
            final float randomZ = (float) ((Math.random() * 2) - 1f);

            controlApi.moveAtVelocity(ControlApi.EARTH_NED_COORDINATE_FRAME, randomX, randomY, randomZ, null);

            Assert.assertTrue(mockDrone.getAsyncAction().getType().equals(ACTION_SET_VELOCITY));

            Bundle params = mockDrone.getAsyncAction().getData();
            Assert.assertEquals(params.getFloat(EXTRA_VELOCITY_X), randomX, 0.001);
            Assert.assertEquals(params.getFloat(EXTRA_VELOCITY_Y), randomY, 0.001);
            Assert.assertEquals(params.getFloat(EXTRA_VELOCITY_Z), randomZ, 0.001);
        }

        //Test with the VEHICLE coordinate frame. The output is dependent on the vehicle attitude data.
        final Attitude attitude = new Attitude();
        final int expectedValuesCount = expectedVelocitiesPerAttitude.size();
        for(int i = 0; i < expectedValuesCount; i++) {
            final int yaw = expectedVelocitiesPerAttitude.keyAt(i);
            attitude.setYaw(yaw);
            mockDrone.setAttribute(AttributeType.ATTITUDE, attitude);

            controlApi.moveAtVelocity(ControlApi.VEHICLE_COORDINATE_FRAME, 1, 1, 1, null);

            Assert.assertTrue(mockDrone.getAsyncAction().getType().equals(ACTION_SET_VELOCITY));

            final float[] expectedValues = expectedVelocitiesPerAttitude.valueAt(i);
            Bundle params = mockDrone.getAsyncAction().getData();
            Assert.assertEquals("Invalid x velocity for attitude = " + yaw, params.getFloat(EXTRA_VELOCITY_X), expectedValues[0], 0.001);
            Assert.assertEquals("Invalid y velocity for attitude = " + yaw, params.getFloat(EXTRA_VELOCITY_Y), expectedValues[1], 0.001);
            Assert.assertEquals("Invalid z velocity for attitude = " + yaw, params.getFloat(EXTRA_VELOCITY_Z), expectedValues[2], 0.001);
        }
    }
}