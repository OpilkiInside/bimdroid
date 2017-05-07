package org.bimdroid.bimservice;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Activity that shows up when we detect key ignition changes.
 */
public class ShutdownDialog extends Activity {

    private final static String TAG = DebugUtils.TAG + ".PWRUI";

    private TextView mShutdownNow;

    private BmwIBusService mService;
    private boolean mServiceBound;

    private static final int DISPLAY_TURNOFF_DELAY = 3 * 1000;  // 3 seconds
    private static final int SHUTDOWN_DELAY = 30 * 60 * 1000;  // 30 minutes

    private final BmwIBusService.CancelShutdownListener mCancelShutdownListener =
            new BmwIBusService.CancelShutdownListener() {
        @Override
        public void onCancelShutdown() {
            Log.d(TAG, "mCancelShutdownListener#onCancelShutdown received");
            mShutdownCountDown.cancel();
            finish();
        }
    };

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "onServiceConnected, name: " + name + ", binder: " + service);
            mService = ((BmwIBusService.LocalBinder) service).getService();
            mService.registerCancelShutdownListener(mCancelShutdownListener);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "onServiceDisconnected, name: " + name);
            if (mService != null) {
                mService.unregisterCancelShutdownListener();
            }
            mService = null;
        }
    };

    private final CountDownTimer mShutdownCountDown = new CountDownTimer(10000, 1000) {

        public void onTick(long millisUntilFinished) {
            mShutdownNow.setText(String.format(getString(R.string.shutdown_now_fmt),
                    (millisUntilFinished / 1000) + " sec."));
        }

        public void onFinish() {
            Log.d(TAG, "mShutdownCountDown#onFinish, mService: " + mService);
            if (mService != null) {
                mService.immediateShutdown();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.shutdown_dialog);

        // Make sure service is running
        ComponentName service = startService(new Intent(this, BmwIBusService.class));
        Log.d(TAG, "Service started from ShutDown dialog: " + service);

        mShutdownNow = (TextView) findViewById(R.id.shutdown_now);
        mShutdownNow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mService != null) {
                    mService.immediateShutdown();
                    finish();
                }
            }
        });

        findViewById(R.id.delay_shutdown).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mShutdownCountDown.cancel();
                mShutdownNow.setText(getString(R.string.shutdown_now));

                Toast.makeText(ShutdownDialog.this, getString(R.string.delay_shutdown_initiated),
                        Toast.LENGTH_LONG).show();

                if (mService != null) {
                    mService.doShutdownDelayed(DISPLAY_TURNOFF_DELAY, SHUTDOWN_DELAY);
                }
                finish();
            }
        });
    }

    @Override
    protected void onResume() {
        mShutdownCountDown.cancel();
        mShutdownCountDown.start();

        super.onResume();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!mServiceBound) {
            Intent intent = new Intent(this, BmwIBusService.class);
            intent.setAction(BmwIBusService.LOCAL_BINDING_ACTION);
            mServiceBound = bindService(intent, mServiceConnection, BIND_AUTO_CREATE);
        }
    }

    @Override
    protected void onStop() {
        if (mServiceBound) {
            if (mService != null) {
                mService.unregisterCancelShutdownListener();
            }

            mServiceBound = false;
            mService = null;
            unbindService(mServiceConnection);
        }
        super.onStop();
    }
}