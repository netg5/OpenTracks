/*
 * Copyright (C) 2010 Google Inc.
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

package com.google.android.apps.mytracks.services.sensors;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.apps.mytracks.content.sensor.SensorDataSet;
import com.google.android.apps.mytracks.content.sensor.SensorState;

import java.util.UUID;

/**
 * Manages connection to Bluetooth LE heart rate monitor.
 * TODO: shutdown connection.
 */
public class BluetoothConnectionManager {

    private static final UUID HEART_RATE_SERVICE_UUID = new UUID(0x180D00001000L, 0x800000805f9b34fbL);
    private static final UUID HEART_RATE_MEASUREMENT_CHAR_UUID = new UUID(0x2A3700001000L, 0x800000805f9b34fbL);
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG_UUID = new UUID(0x290200001000L, 0x800000805f9b34fbL);

    // Message types sent to handler
    public static final int MESSAGE_DEVICE_NAME = 1;
    public static final int MESSAGE_READ = 2;
    public static final String KEY_DEVICE_NAME = "device_name";

    private static final String TAG = BluetoothConnectionManager.class.getSimpleName();

    private final Context context;
    private final Handler handler;
    private SensorState sensorState;

    private BluetoothGattCallback connectCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices();
                setState(SensorState.CONNECTED);

                //Inform about status change
                Message message = handler.obtainMessage(MESSAGE_DEVICE_NAME);
                Bundle bundle = new Bundle();
                bundle.putString(KEY_DEVICE_NAME, gatt.getDevice().getName());
                message.setData(bundle);
                handler.sendMessage(message);
                return;
            }
            Log.d(TAG, "Could not connect to bluetooth sensor: " + gatt.getDevice());
        }

        @Override
        public void onServicesDiscovered(@NonNull BluetoothGatt gatt, int status) {
            BluetoothGattCharacteristic characteristic = gatt
                    .getService(HEART_RATE_SERVICE_UUID)
                    .getCharacteristic(HEART_RATE_MEASUREMENT_CHAR_UUID);

            gatt.setCharacteristicNotification(characteristic, true);

            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID);
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(descriptor);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic) {
            int heartRate = BluetoothLEUtils.parseHeartRate(characteristic);

            Log.d(TAG, "Received heart beat rate: " + heartRate);
            SensorDataSet sensorDataSet = new SensorDataSet(heartRate, gatt.getDevice().getName(), gatt.getDevice().getAddress());
            handler.obtainMessage(MESSAGE_READ, sensorDataSet).sendToTarget();
        }
    };

    /**
     * Constructor.
     *
     * @param handler a handler for sending messages back to the UI activity
     */
    public BluetoothConnectionManager(Context context, Handler handler) {
        this.context = context;
        this.handler = handler;
        this.sensorState = SensorState.NONE;
    }

    /**
     * Gets the sensor state.
     */
    public synchronized SensorState getSensorState() {
        return sensorState;
    }

    /**
     * Sets the sensor state.
     *
     * @param sensorState the sensor state
     */
    private synchronized void setState(SensorState sensorState) {
        this.sensorState = sensorState;
    }

    /**
     * Resets the bluetooth connection manager.
     */
    public synchronized void reset() {
        //TODO Disconnect
        setState(SensorState.NONE);
    }

    /**
     * Connects to a bluetooth device.
     *
     * @param bluetoothDevice the bluetooth device
     */
    public synchronized void connect(BluetoothDevice bluetoothDevice) {
        Log.d(TAG, "connect to: " + bluetoothDevice);

        bluetoothDevice.connectGatt(this.context, false, this.connectCallback);
        setState(SensorState.CONNECTING);
    }
}