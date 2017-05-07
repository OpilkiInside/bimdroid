package org.bimdroid.bimservice;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Stores config information like what serial port to use.
 */
public class ConfigStorage {

    private static final String TAG = DebugUtils.TAG;

    private static final String PREFERENCE_NAME = "org.bimdroid.bimservice.PREFERENCE";
    private static final String KEY_VENDOR_ID = "KEY_VENDOR_ID";
    private static final String KEY_PRODUCT_ID = "KEY_PRODUCT_ID";
    private static final String KEY_SERIAL_NUMBER = "KEY_SERIAL_NUMBER";

    private static volatile Listener sListener;

    /** Singleton */
    private ConfigStorage() { }

    public static SerialPortIdentifier readDefaultPort(Context context) {
        SharedPreferences pref = getPreference(context);
        boolean defaultPortExists = pref.contains(KEY_VENDOR_ID)
                && pref.contains(KEY_PRODUCT_ID)
                && pref.contains(KEY_SERIAL_NUMBER);
        Log.d(TAG, "readDefaultPort, context: " + context
                + ", defaultPortExists: " + defaultPortExists);

        if (defaultPortExists) {
            SerialPortIdentifier port = new SerialPortIdentifier(pref.getInt(KEY_VENDOR_ID, 0),
                    pref.getInt(KEY_PRODUCT_ID, 0),
                    pref.getString(KEY_SERIAL_NUMBER, ""));
            Log.d(TAG, "readDefaultPort, port: " + port);
            return port;
        } else {
            return null;
        }
    }

    public static void writeDefaultPort(Context context,
                                        SerialPortIdentifier serialPortIdentifier) {
        Log.d(TAG, "writeDefaultPort, context: " + context + ", port: " + serialPortIdentifier);
        SharedPreferences pref = getPreference(context);

        pref.edit()
                .putInt(KEY_VENDOR_ID, serialPortIdentifier.getVendorId())
                .putInt(KEY_PRODUCT_ID, serialPortIdentifier.getProductId())
                .putString(KEY_SERIAL_NUMBER, serialPortIdentifier.getSerialNumber())
                .apply();

        Listener listener = sListener;
        Log.d(TAG, "writeDefaultPort, propagating event to: " + listener);
        if (listener != null) {
            listener.onSerialPortChanged(serialPortIdentifier);
        }
    }

    public static void registerListner(Listener listener) {
        sListener = listener;
    }

    public static void unregisterListener() {
        sListener = null;
    }

    interface Listener {
        void onSerialPortChanged(SerialPortIdentifier newPort);
    }

    private static SharedPreferences getPreference(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE);
    }

    static class SerialPortIdentifier {
        private final int mVendorId;
        private final int mProductId;
        private final String mSerialNumber;

        SerialPortIdentifier(int vendorId, int productId, String serialNumber) {
            mVendorId = vendorId;
            mProductId = productId;
            mSerialNumber = serialNumber;
        }

        int getVendorId() {
            return mVendorId;
        }

        int getProductId() {
            return mProductId;
        }

        String getSerialNumber() {
            return mSerialNumber;
        }

        @Override
        public String toString() {
            return String.format("VendorId: 0x%s, ProductId: 0x%s, Serial Number: %s",
                    Integer.toHexString(getVendorId()),
                    Integer.toHexString(getProductId()),
                    getSerialNumber());
        }
    }
}
