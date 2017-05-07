package org.bimdroid.bimservice;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.HexDump;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static java.lang.Integer.toHexString;

public class SettingsActivity extends Activity {

    private static final String TAG = DebugUtils.TAG;

    private Button mRefreshBtn;
    private Spinner mPortSpinner;
    private ProgressBar mProgressBar;
    private TextView mProgressBarTitle;
    private TextView mIBusTextMessage;

    private UsbManager mUsbManager;
    private ArrayAdapter<SerialPortItem> mPortAdapter;
    private BmwIBusService mService;
    private boolean mServiceBound;

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "onServiceConnected, name: " + name + ", binder: " + service);
            mService = ((BmwIBusService.LocalBinder) service).getService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "onServiceDisconnected, name: " + name);
            mService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_settings);
        mRefreshBtn = (Button) findViewById(R.id.refresh_button);
        mPortSpinner = (Spinner) findViewById(R.id.port_spinner);
        mProgressBar = (ProgressBar) findViewById(R.id.progress_bar);
        mProgressBarTitle = (TextView) findViewById(R.id.progress_bar_title);
        mIBusTextMessage = (TextView) findViewById(R.id.ibus_message);

        mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        findViewById(R.id.send_to_car).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendRawMessageToCar(mIBusTextMessage.getText().toString());
            }
        });

        findViewById(R.id.lock_door).setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                sendRawMessageToCar("3f 05 00 0c 34 01");  // Door lock
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        sendRawMessageToCar("3f 05 00 0c 03 01");  // Door unlock
                    }
                }, 5000);
            }
        });

        findViewById(R.id.open_window).setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
               sendRawMessageToCar("3f 05 00 0c 52 01");  // Driver's window open
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        sendRawMessageToCar("3f 05 00 0c 53 01");  // Driver's window close
                    }
                }, 15000);
            }
        });

        mPortAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item);
        mPortAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mPortSpinner.setAdapter(mPortAdapter);

        mPortSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                onPortSelected((SerialPortItem) parent.getItemAtPosition(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                Toast.makeText(SettingsActivity.this, "Serial port is not selected",
                        Toast.LENGTH_LONG).show();
            }
        });

        mRefreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshDeviceList();
            }
        });

        ComponentName service = startService(new Intent(this, BmwIBusService.class));
        Log.d(TAG, "Service started from the activity: " + service);
    }

    private void sendRawMessageToCar(String text) {
        BmwIBusService.IBusRawPacket packet;
        try {
            packet = BmwIBusService.IBusRawPacket.createFromString(text);
        } catch (IllegalArgumentException ex) {
            Toast.makeText(SettingsActivity.this, "Error: " + ex.getMessage(),
                    Toast.LENGTH_LONG).show();
            return;
        }

        if (mService != null) {
            Toast.makeText(SettingsActivity.this, "Sending message: "
                    + HexDump.toHexString(packet.toByteArray()),
                    Toast.LENGTH_LONG).show();
            mService.sendIBusMessage(packet);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!mServiceBound) {
            Intent intent = new Intent(this, BmwIBusService.class);
            intent.setAction(BmwIBusService.LOCAL_BINDING_ACTION);
            mServiceBound = bindService(intent, mServiceConnection, BIND_AUTO_CREATE);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mServiceBound) {
            mServiceBound = false;
            mService = null;
            unbindService(mServiceConnection);
        }
    }

    private void onPortSelected(SerialPortItem port) {
//        if (port.mCurrent) {
//            Log.d(TAG, "onPortSelected, do nothing, port is already current.");
//            return;  // Do nothing, port is already selected.
//        }
        UsbDevice device = port.mPort.getDriver().getDevice();

        if (!mUsbManager.hasPermission(device)) {
            Log.e(TAG, "HAS NO PERMISSIONS for device:  " + device);
            // TODO: request permissions at runtime.
            // mUsbManager.requestPermission(device,);
        }

        ConfigStorage.writeDefaultPort(this, new ConfigStorage.SerialPortIdentifier(
            device.getVendorId(), device.getProductId(), device.getSerialNumber()));

        for (int i = 0; i < mPortAdapter.getCount(); i++) {
            SerialPortItem item = mPortAdapter.getItem(i);
            if (item.mCurrent) {
                item.mCurrent = false;
            } else if (item == port) {
                item.mCurrent = true;
            }
        }
        mPortAdapter.notifyDataSetChanged();
        if (mServiceBound && mService != null) {
            mService.onUsbSerialPortChanged(port.mPort);
        }

        Toast.makeText(this, "New serial port selected: " + port, Toast.LENGTH_LONG).show();
    }

    private void refreshDeviceList() {
        showProgressBar();

        new AsyncTask<Void, Void, List<UsbSerialPort>>() {
            @Override
            protected List<UsbSerialPort> doInBackground(Void... params) {
                Log.d(TAG, "Refreshing device list ...");
                SystemClock.sleep(100);

                final List<UsbSerialDriver> drivers =
                        UsbSerialProber.getDefaultProber().findAllDrivers(mUsbManager);

                final List<UsbSerialPort> result = new ArrayList<>();
                for (final UsbSerialDriver driver : drivers) {
                    final List<UsbSerialPort> ports = driver.getPorts();
                    Log.d(TAG, String.format("+ %s: %d port%s",
                            driver, ports.size(), ports.size() == 1 ? "" : "s"));
                    result.addAll(ports);
                }

                return result;
            }

            @Override
            protected void onPostExecute(List<UsbSerialPort> result) {
                ConfigStorage.SerialPortIdentifier currentPort =
                        ConfigStorage.readDefaultPort(SettingsActivity.this);

                mPortAdapter.setNotifyOnChange(false);
                mPortAdapter.clear();
                List<SerialPortItem> items = new ArrayList<>();
                for (UsbSerialPort port : result) {
                    UsbDevice device = port.getDriver().getDevice();
                    boolean isPortSelected = currentPort != null
                            && currentPort.getVendorId() == device.getVendorId()
                            && currentPort.getProductId() == device.getProductId()
                            && Objects.equals(currentPort.getSerialNumber(),
                                              device.getSerialNumber());
                    items.add(new SerialPortItem(port, isPortSelected));
                }
                mPortAdapter.addAll(items);
                mPortAdapter.notifyDataSetChanged();

                mProgressBarTitle.setText(
                        String.format("%d device(s) found", result.size()));
                hideProgressBar();
                Log.d(TAG, "Done refreshing, " + result.size() + " entries found.");
            }

        }.execute((Void) null);
    }

    private void showProgressBar() {
        mProgressBar.setVisibility(View.VISIBLE);
        mProgressBarTitle.setText(R.string.refreshing);
    }

    private void hideProgressBar() {
        mProgressBar.setVisibility(View.INVISIBLE);
    }

    private static class SerialPortItem {
        private final UsbSerialPort mPort;
        private boolean mCurrent;

        SerialPortItem(UsbSerialPort port, boolean current) {
            mPort = port;
            mCurrent = current;
        }

        @Override
        public String toString() {
            UsbDevice device = mPort.getDriver().getDevice();

            return String.format("%s [%s:%s SN: %s",
                    mCurrent ? "--> " : "",  // Mark as selected.
                    toHexString(device.getVendorId()),
                    toHexString(device.getProductId()),
                    device.getSerialNumber());
        }
    }
}
