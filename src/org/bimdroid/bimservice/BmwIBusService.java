package org.bimdroid.bimservice;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.input.InputManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.HexDump;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Service that responsible for interactions between Android apps and vehicle using IBus as a
 * communication channel. IBus should be connected to Android device through USB.
 *
 * <p>
 *     Tested on Rolf Resler's USB device, {@see http://www.reslers.de/IBUS/}.
 *     BMW E39 530i 2003
 * </p>
 *
 * @author Pavel Maltsev (pmaltsev@gmail.com)
 */
public class BmwIBusService extends Service {

    private final static boolean DEBUG = true;

    static final String ACTION_SHUTDOWN_REQUEST = "org.bimdroid.ACTION_SHUTDOWN_REQUEST";
    static final String ACTION_CANCEL_DELAYED_SHUTDOWN =
            "org.bimdroid.ACTION_CANCEL_DELAYED_SHUTDOWN";

    private static final String TAG = DebugUtils.TAG + ".Service";

    private final LocalBinder mBinder = new LocalBinder();
    static final String LOCAL_BINDING_ACTION = "LOCAL_BINDING";

    private final static int IBUS_BAUD = 9600;
    private final static int IBUS_DATA_BITS = UsbSerialPort.DATABITS_8;
    private final static int IBUS_PARITY = UsbSerialPort.PARITY_EVEN;
    private final static int IBUS_STOP_BITS = UsbSerialPort.STOPBITS_1;

    private UsbManager mUsbManager;
    private AudioManager mAudioManager;
    private PowerManager mPowerManager;
    private InputManager mInputManager;
    private PowerManager.WakeLock mWakeLock;
    private volatile UsbSerialPort mOpenedPort;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private final IBusPacketListener mIBusPacketListener = new IBusPacketListener() {
        @Override
        public void onIBusPacket(IBusPacket packet) {
            onIBusPacketInternal(packet);
        }
    };
    private final IBusDataDecoder mDecoder = new IBusDataDecoder(mIBusPacketListener);

    private volatile SerialInputOutputManager mSerialIoManager;

    private final Timer mTimer = new Timer(BmwIBusService.class.getSimpleName() + "Timer");
    private DisplayOffTask mDisplayOffTask;
    private ShutdownTask mShutdownTask;

    private static final byte MFL = 0x50;  // Multi functional steering wheel buttons (doesn't
    // include cruise control).
    private static final byte RAD = 0x68;  // Radio unit.
    private static final byte TEL = (byte) 0xC8; // Telephone unit
    private static final byte IKE = (byte) 0x80; // Instrument Kombi Messages (ODB)
    private static final int MAX_ODB_MESSAGE_LENGTH = 30;
    private final static int MFL_VOLUME_DOWN = 0x3210;
    private final static int MFL_VOLUME_UP = 0x3211;
    private final static int MFL_NEXT_TRACK_RELEASE = 0x3B21;
    private final static int MFL_NEXT_TRACK_PUSH = 0x3B01;
    private final static int MFL_PREV_TRACK_RELEASE = 0x3B28;
    private final static int MFL_PREV_TRACK_PUSH = 0x3B08;
    private final static int MFL_VOICE_ASSIST_PUSH = 0x3B80;
    private final static int MFL_VOICE_ASSIST_RELEASE = 0x3BA0;
    private final static int MFL_RT = 0x3B40;

    // IMPORTANT! Keep this array sorted as it is used by binary search.
    private final static int[] MFL_DEBOUNCE_BUTTONS_SORTED = new int[]{
            MFL_VOLUME_DOWN, MFL_VOLUME_UP, MFL_RT
    };

    private final static String[] WELCOME_MESSAGES = new String[] {
            "Have a safe trip!",
            "Vroom vroom!",
            "Good luck!",
            "Have fun!",
            "Keep calm and drive!"
    };

    private int mPreviousButton = 0;

    private final SerialInputOutputManager.Listener mListener =
            new SerialInputOutputManager.Listener() {

                @Override
                public void onRunError(Exception e) {
                    if (DEBUG) {
                        Log.w(TAG, "Runner stopped.", e);
                    }
                }

                @Override
                public void onNewData(final byte[] data) {
                    mDecoder.onDataReceived(data);
                }
            };

    @Override
    public IBinder onBind(Intent intent) {
        if (DEBUG) {
            Log.d(TAG, "onBind, intent: " + intent);
        }
        if (LOCAL_BINDING_ACTION.equals(intent.getAction())) {
            return mBinder;
        }

        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand, intent: " + intent + ", flags: " + flags
                + ", startId: " + startId);

        if (ACTION_CANCEL_DELAYED_SHUTDOWN.equals(intent.getAction())) {
            sendTextMessageToObc("Welcome back, Pavel!");
            cancelDelayedShutdown();
        }

        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        if (DEBUG) Log.d(TAG, "onCreate");

        mUsbManager = (UsbManager) getBaseContext().getSystemService(Context.USB_SERVICE);
        mAudioManager = (AudioManager) getBaseContext().getSystemService(Context.AUDIO_SERVICE);
        mPowerManager = (PowerManager) getBaseContext().getSystemService(Context.POWER_SERVICE);
        mInputManager = (InputManager) getBaseContext().getSystemService(Context.INPUT_SERVICE);

        ConfigStorage.SerialPortIdentifier portIdentifier =
                ConfigStorage.readDefaultPort(getBaseContext());
        if (DEBUG) Log.d(TAG, "onCreate, portIdentifier: " + portIdentifier);
        if (portIdentifier != null) {
            UsbSerialPort port = findUsbSerialPort(portIdentifier);
            if (port == null) {
                Log.w(TAG, "Unable to find usb serial port,  make sure device is connected: "
                        + portIdentifier);
                return;
            }

            onUsbSerialPortChanged(port);
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        stopIoManager();
        mUsbManager = null;
        mAudioManager = null;

        super.onDestroy();
    }

    public void onUsbSerialPortChanged(UsbSerialPort port) {
        stopIoManager();
        if (openConnection(port)) {
            mOpenedPort = port;
            startIoManager(port);
        }
    }

    public synchronized void doShutdownDelayed(long waitForDisplayPowerOffMs,
                                               long waitForShutdownMs) {
        mDisplayOffTask = new DisplayOffTask();
        mTimer.schedule(mDisplayOffTask, waitForDisplayPowerOffMs);
        mShutdownTask = new ShutdownTask();
        mTimer.schedule(mShutdownTask, waitForShutdownMs);
    }

    public synchronized void cancelDelayedShutdown() {
        if (mCancelShutdownListener != null) {
            mCancelShutdownListener.onCancelShutdown();
        }

        if (mShutdownTask != null) {
            mShutdownTask.cancel();
            mShutdownTask = null;
        }

        if (mDisplayOffTask != null) {
            mDisplayOffTask.cancel();
            mDisplayOffTask = null;
        }
        powerOnDisplay();
    }

    void powerOffDisplay() {
        boolean isDisplayOff = !mPowerManager.isInteractive();
        Log.i(TAG, "powerOffDisplay, isDisplayOff: " + isDisplayOff);
        if (isDisplayOff) {
            return;
        }

        dispatchKeyEvent(KeyEvent.KEYCODE_POWER, false);
    }

    void powerOnDisplay() {
        if (mPowerManager.isInteractive()) {
            return;  // Already ON
        }

        dispatchKeyEvent(KeyEvent.KEYCODE_POWER, false);
    }

    void dispatchKeyEvent(int keyCode, boolean longpress) {
        // Alternative way if you do not like red highlights below ;)
        String command = String.format("input keyevent %s%d",
                (longpress ? "--longpress " : ""), keyCode);
        execShellCommand(command);

        // Shit below doesn't work, I wasn't bothering trying to figure out why :(
//        long now = SystemClock.uptimeMillis();
//        long down = now - 10;
//
//        KeyEvent event = KeyEvent.obtain(
//                down, down, KeyEvent.ACTION_DOWN, keyCode,
//                0, 0, 0, 0, 0, InputDevice.SOURCE_KEYBOARD, null);
//        mInputManager.injectInputEvent(event, 0 /* INJECT_INPUT_EVENT_MODE_ASYNC */);
//        event.recycle();
//
//        event = KeyEvent.obtain(
//                down, now, KeyEvent.ACTION_UP, keyCode,
//                0, 0, 0, 0, 0, InputDevice.SOURCE_KEYBOARD, null);
//        mInputManager.injectInputEvent(event, 0 /* INJECT_INPUT_EVENT_MODE_ASYNC */);
//        event.recycle();
    }

    void immediateShutdown() {
        Log.d(TAG, "immediateShutdown !!!");
        execShellCommand("am start -a android.intent.action.ACTION_REQUEST_SHUTDOWN");
    }

    private CancelShutdownListener mCancelShutdownListener;

    public void registerCancelShutdownListener(CancelShutdownListener listener) {
        mCancelShutdownListener = listener;
    }

    public void unregisterCancelShutdownListener() {
        mCancelShutdownListener = null;
    }

    private static void execShellCommand(String command) {
        try {
            Log.d(TAG, "execShellCommand, command: " + command);

            Process su = Runtime.getRuntime().exec("su");
            DataOutputStream outputStream = new DataOutputStream(su.getOutputStream());

            outputStream.writeBytes(command + "\n");
            outputStream.flush();

            outputStream.writeBytes("exit\n");
            outputStream.flush();

            su.waitFor();
        }catch(IOException | InterruptedException e){
            Log.e(TAG, "execShellCommand, command: " + command + ", error: " + e.getMessage(), e);
        }
    }

    private UsbSerialPort findUsbSerialPort(ConfigStorage.SerialPortIdentifier identifier) {
        final List<UsbSerialDriver> drivers =
                UsbSerialProber.getDefaultProber().findAllDrivers(mUsbManager);

        for (final UsbSerialDriver driver : drivers) {
            for (final UsbSerialPort port : driver.getPorts()) {
                UsbDevice device = port.getDriver().getDevice();
                if (device != null
                        && device.getVendorId() == identifier.getVendorId()
                        && device.getProductId() == identifier.getProductId()
                        && Objects.equals(device.getSerialNumber(), identifier.getSerialNumber())) {
                    return port;
                }
            }
        }

        return null;
    }

    private boolean openConnection(UsbSerialPort port) {
        UsbDeviceConnection connection = mUsbManager.openDevice(port.getDriver().getDevice());

        if (connection == null) {
            if (DEBUG) Log.e(TAG, "Failed to create connection with: " + port);
            return false;
        }

        try {
            port.open(connection);
            port.setParameters(IBUS_BAUD, IBUS_DATA_BITS, IBUS_STOP_BITS, IBUS_PARITY);

            if (DEBUG) {
                Log.d(TAG, "CD  - Carrier Detect: " + port.getCD());
                Log.d(TAG, "CTS - Clear To Send: " + port.getCTS());
                Log.d(TAG, "DSR - Data Set Ready: " + port.getDSR());
                Log.d(TAG, "DTR - Data Terminal Ready: " + port.getDTR());
                Log.d(TAG, "DSR - Data Set Ready: " + port.getDSR());
                Log.d(TAG, "RI  - Ring Indicator: " + port.getRI());
                Log.d(TAG, "RTS - Request To Send: " + port.getRTS());
            }

            return true;
        } catch (IOException e) {
            Log.e(TAG, "Error setting up device: " + e.getMessage(), e);
            try {
                port.close();
            } catch (IOException ignore) { }
            return false;
        }
    }

    private void restartIoManager(UsbSerialPort port) {
        stopIoManager();
        startIoManager(port);
    }

    private void stopIoManager() {
        if (mSerialIoManager != null) {
            if (DEBUG) Log.i(TAG, "Stopping io manager...");
            mSerialIoManager.stop();
            mSerialIoManager = null;
            if (mOpenedPort != null) {
                try {
                    mOpenedPort.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                mOpenedPort = null;
            }
            mDecoder.reset();
        }
    }

    private void startIoManager(UsbSerialPort port) {
        if (port != null) {
            if (DEBUG) Log.i(TAG, "Starting io manager...");
            mDecoder.reset();
            mSerialIoManager = new SerialInputOutputManager(port, mListener);
            mExecutor.submit(mSerialIoManager);

            new Handler(Looper.myLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    int randomMessageIndex = new Random(SystemClock.elapsedRealtimeNanos())
                            .nextInt(WELCOME_MESSAGES.length);
                    Log.d(TAG, "Sending a welcome message, index: " + randomMessageIndex);
                    BmwIBusService.this.sendTextMessageToObc(
                            WELCOME_MESSAGES[randomMessageIndex]);
                }
            }, 3000);
        }
    }

    public void sendTextMessageToObc(String message) {
        String normalizedMessage = message.length() > MAX_ODB_MESSAGE_LENGTH
                ? message.substring(0, MAX_ODB_MESSAGE_LENGTH)
                : message;

        normalizedMessage = normalizedMessage.toUpperCase();

        if (DEBUG) Log.i(TAG, "Sending text message to IBus: " + normalizedMessage);

        IBusRawPacket packet = new IBusRawPacket();
        packet.source = 0x30;
        packet.destination = IKE;
        packet.payload[0] = 0x1A;
        packet.payload[1] = 0x35;
        packet.payload[2] = 0x00;

        final int payloadPrefixSize = 3;
        for (int i = 0; i < normalizedMessage.length(); i++) {
            byte b = (byte) normalizedMessage.charAt(i);
            packet.payload[i + payloadPrefixSize] = b;
        }

        packet.packetLength = normalizedMessage.length() + payloadPrefixSize + 2;
        sendIBusMessage(packet);
    }

    /**
     * Sends a IBus message
     *
     * @return {@code true} if message sent successfully otherwise returns {@code false}.
     */
    public boolean sendIBusMessage(IBusRawPacket packet) {
        if (mSerialIoManager == null
                || mSerialIoManager.getState() != SerialInputOutputManager.State.RUNNING) {
            if (DEBUG) Log.w(TAG, "Attempt to send ODB message when serial IO manager is not running");
            return false;
        }

        if (DEBUG) Log.i(TAG, "Sending message to IBus: " + HexDump.toHexString(packet.toByteArray()));
        mSerialIoManager.writeAsync(packet.toByteArray());
        return true;
    }

    private boolean debounce(int button) {
        boolean needToDebounce = Arrays.binarySearch(MFL_DEBOUNCE_BUTTONS_SORTED, button) != -1;
        if (needToDebounce && button == mPreviousButton) {
            mPreviousButton = 0;
            return false;
        }
        mPreviousButton = needToDebounce ? button : 0;
        return true;
    }

    void onIBusPacketInternal(IBusPacket packet) {
        if (DEBUG) Log.d(TAG, "onIBusPacketInternal, packet: " + packet);

        boolean steeringWheelButton = packet.source == MFL
                && packet.payload.length == 2
                && (packet.destination == RAD || packet.destination == TEL);

        if (steeringWheelButton) {
            int button = (packet.payload[0] & 0xff) << 8 | (packet.payload[1] & 0xff);

            if (!debounce(button)) {
                Log.d(TAG, "Button event was ignored due to bouncing. Button: 0x"
                        + Integer.toHexString(button));
                return;
            }

            if (button == MFL_VOLUME_DOWN) {
                if (DEBUG) Log.d(TAG, "Volume down IBus message received");
                mAudioManager.adjustVolume(AudioManager.ADJUST_LOWER, 0);
            } else if (button == MFL_VOLUME_UP) {
                if (DEBUG) Log.d(TAG, "Volume up IBus message received");
                mAudioManager.adjustVolume(AudioManager.ADJUST_RAISE, 0);
            } else if (button == MFL_NEXT_TRACK_PUSH) {
                if (DEBUG) Log.d(TAG, "Next track IBus message received");
                dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT);
            } else if (button == MFL_PREV_TRACK_PUSH) {
                if (DEBUG) Log.d(TAG, "Previous track IBus message received");
                dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            } else if (button == MFL_VOICE_ASSIST_PUSH) {
                Intent intent = new Intent("android.intent.action.VOICE_ASSIST"); /* Intent.ACTION_VOICE_ASSIST */
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } else {
                Log.w(TAG, "Unknown button: " + Integer.toHexString(button));
            }
        } else if (packet.source == MFL && packet.destination == TEL
                && packet.length == 1 && packet.payload[0] == 1) {
            if (DEBUG) Log.d(TAG, "R/T IBus message received");  // T/T
            dispatchKeyEvent(KeyEvent.KEYCODE_HOME,
                    SystemClock.uptimeMillis() - lastRtPressed > 1000);
            lastRtPressed = SystemClock.uptimeMillis();
        }
    }

    private long lastRtPressed = 0;

    private void dispatchMediaKeyEvent(int keyCode) {
        KeyEvent eventDown = new KeyEvent(KeyEvent.ACTION_DOWN, keyCode);
        mAudioManager.dispatchMediaKeyEvent(eventDown);
        SystemClock.sleep(2);
        KeyEvent eventUp = new KeyEvent(KeyEvent.ACTION_UP, keyCode);
        mAudioManager.dispatchMediaKeyEvent(eventUp);
    }

    static class RingByteBuffer {
        private final byte[] mBuffer;
        private final int mSize;

        private int mCurrentIndex = 0;

        RingByteBuffer(int size) {
            mSize = size;
            mBuffer = new byte[size];
        }

        void addByte(byte b) {
            mBuffer[mCurrentIndex++] = b;

            if (mCurrentIndex == mSize) {
                mCurrentIndex = 0;
                if (DEBUG) Log.d(TAG, "IBUS RAW DATA: " + HexDump.toHexString(mBuffer));
            }
        }

        byte[] getBytes(int elements) {
            if (elements > mSize) {
                Log.wtf(TAG, "Asking for more elements than we have in the buffer." +
                        " Buffer size: " + mSize + ", elements requested: " + elements);
                return new byte[]{};
            }

            int virtualPosition = mCurrentIndex - elements;
            if (virtualPosition >= 0) {
                return Arrays.copyOfRange(mBuffer, virtualPosition, mCurrentIndex);
            } else {
                byte[] head = Arrays.copyOfRange(mBuffer, mSize + virtualPosition, mSize);
                if (mCurrentIndex == 0) {
                    return head;
                }

                byte[] tail = Arrays.copyOfRange(mBuffer, 0, mCurrentIndex);
                return concatenate(head, tail);
            }
        }

        private static byte[] concatenate(byte[] array1, byte[] array2) {
            byte[] result = new byte[array1.length + array2.length];
            System.arraycopy(array1, 0, result, 0, array1.length);
            System.arraycopy(array2, 0, result, array1.length, array2.length);
            return result;
        }
    }

    static class IBusDataDecoder {

        private static final int PACKET_STATE_COMPLETE = 0xFF;
        private int mPacketState = 0;
        private boolean mPreviousPacketComplted = false;

        private final IBusRawPacket mPacket = new IBusRawPacket();

        // Keep track of incoming data in RingBuffer so we could recover if for example check sum
        // didn't match (perhaps we messed up with start packet).
        private final RingByteBuffer mRecoveryBuffer = new RingByteBuffer(255);

        private static final byte[] sKnownDestinations = new byte[] {
                (byte)0x80,  // IKE Instrument Kombi Electronics
                (byte)0xA4,  // Unknown, observed during key ignition on / off
                (byte)0xA8,  // Unknown
                (byte)0xBB,  // TV Module
                (byte)0xBF,  // LCM Light Control Module
                (byte)0xC0,  // MID Multi-Information Display Buttons
                (byte)0xC8,  // TEL Telephone
                (byte)0xD0,  // Navigation Location
                (byte)0xE7,  // OBC TextBar
                (byte)0xE8,  // Unknown
                (byte)0xED,  // Lights, Wipers, Seat Memory
                (byte)0xF0,   // BMB Board Monitor Buttons
                (byte)0xFF,  // Broadcast

                0x00,  // Broadcast
                0x18,  // CDW - CDC CD-Player
                0x30,  // Unknown
                0x3B,  // NAV Navigation/ Video module
                0x3F,  // Unknown
                0x43,  // Menu screen
                0x44,  // Unknown
                MFL,   // 0x50 MFL Multi Functional Steering Wheel Buttons
                0x60,  // PDC Park Distance Control
                RAD,   // 0x68 RAD Radio
                0x6A,  // DSP Digital Sound Processor
                0x7F,  // Unknown
        };

        private final IBusPacketListener mListener;

        IBusDataDecoder(IBusPacketListener listener) {
            mListener = listener;
        }

        void reset() {
            mPacketState = 0;
            mRecoveryBuffer.mCurrentIndex = 0;
        }

        private boolean isKnownDestination(byte b) {
            return Arrays.binarySearch(sKnownDestinations, b) >= 0;
        }

        private boolean checkState(byte b) {
            if (mPacketState == 0 && isKnownDestination(b)) {  // We are looking for source
                mPacket.source = b;
                return true;
            } else if (mPacketState == 0 && mPreviousPacketComplted && !isKnownDestination(b)) {


                Log.w(TAG, "Possible legal source destination: 0x" + Integer.toHexString(b & 0xff));
                return false;
            } else
            if (mPacketState == 1 && b < 127 && b > 1) {  // Length
                mPacket.packetLength = b;  // 1 - byte destination, 1 - byte calcChecksum.
                return true;
            } else if (mPacketState == 2 && isKnownDestination(b)) { // Destination
                mPacket.destination = b;
                return true;
            } else if (mPacketState > 2) {
                int curIndex = mPacketState - 3;
                if (curIndex < (mPacket.packetLength - 2)) {
                    mPacket.payload[curIndex] = b;
                    return true;
                } else {
                    // Calculate checksum.
                    byte checksum = mPacket.calcChecksum();
                    boolean valid = checksum == b;
                    if (valid) {
                        mPacketState = PACKET_STATE_COMPLETE;
                    }
                    if (!valid) {
                        Log.w(TAG, "Invalid check sum, expected: " + checksum + ", was: " + b
                                + ", for packet: " + mPacket);
                    }
                    return valid;
                }
            }

            return false;
        }

        void onDataReceived(byte[] bytes) {
            for (byte b : bytes) {
                onDataReceived(b);
            }
        }

        void onDataReceived(byte b) {
            mRecoveryBuffer.addByte(b);
            if (checkState(b)) {
                if (mPacketState == PACKET_STATE_COMPLETE) {
                    mListener.onIBusPacket(new IBusPacket(mPacket));
                    mPacketState = 0; // Reset.
                    mPreviousPacketComplted = true;
                } else {
                    mPacketState++;
                    mPreviousPacketComplted = false;
                }
            } else {  // We lost track of packet structure.
                mPreviousPacketComplted = false;
                boolean checksumFailed = mPacketState > 2;
                mPacketState = 0;
                if (checksumFailed) {
                    onDataReceived(mRecoveryBuffer.getBytes(mPacket.packetLength));
                }
            }
        }
    }

    static class IBusRawPacket {
        byte source;
        int packetLength;  // Length in raw IBusRawPacket includes destination,
                           // payload and checksum byte.
        byte destination;

        byte[] payload = new byte[192]; // doesn't include checksum

        static IBusRawPacket createFromString(String text) {
            String s = text.toUpperCase().replace(" ", "").replace("0x", "");
            if (s.length() % 2 != 0) {
                throw new IllegalArgumentException("Wrong number of digits. Should be even.");
            }

            if (s.length() < 8) {
                throw new IllegalArgumentException("Message is too small");
            }

            char[] chars = s.toCharArray();
            byte[] bytes = new byte[chars.length / 2];
            for (int i = 0; i < s.length(); i += 2) {
                bytes[i / 2] = (byte) ((charToValue(chars[i]) << 4) | charToValue(chars[i + 1]));
            }

            IBusRawPacket p = new IBusRawPacket();
            p.source = bytes[0];
            p.packetLength = bytes[1];
            p.destination = bytes[2];
            if (p.packetLength != bytes.length - 1) {
                throw new IllegalArgumentException("Invalid packet length. Provided: "
                        + p.packetLength + ", expected: " + (bytes.length - 2));
            }
            System.arraycopy(bytes, 3, p.payload, 0, bytes.length - 3);

            return p;
        }

        private static int charToValue(char c) {
            if (c >= '0' && c <= '9') {
                return c - '0';
            } else if (c >= 'A' && c <= 'F') {
                return c - 'A' + 10;
            } else if (c >= 'a' && c <= 'f') {
                return c - 'a' + 10;
            }
            throw new IllegalArgumentException("Invalid character: " + c);
        }

        @Override
        public String toString() {
            return "Packet { source: 0x" + HexDump.toHexString(source)
                    + ", packetLength: " + packetLength
                    + ", destination: 0x" + HexDump.toHexString(destination)
                    + ", payload: " + HexDump.toHexString(payload, 0, getPayloadLength())
                    + " }";
        }

        int getPayloadLength() {
            return packetLength - 2;  // minus destination and checksum.
        }

        byte calcChecksum() {
            int checksum = source ^ packetLength ^ destination;
            for (int i = 0; i < getPayloadLength(); i++) {
                checksum ^= payload[i];
            }
            return (byte) checksum;
        }

        byte[] toByteArray() {
            int arrayLength = packetLength + 2; /* source + length itself */
            byte[] buffer = new byte[arrayLength];

            buffer[0] = source;
            buffer[1] = (byte) packetLength;
            buffer[2] = destination;
            System.arraycopy(payload, 0, buffer, 3, getPayloadLength());

            buffer[arrayLength - 1] = calcChecksum();

            return buffer;
        }
    }

    static class IBusPacket {
        final byte source;
        final int length;
        final byte destination;
        final byte[] payload;

        IBusPacket(IBusRawPacket rawPacket) {
            this.source = rawPacket.source;
            this.length = rawPacket.packetLength - 2;
            this.destination = rawPacket.destination;
            this.payload = Arrays.copyOf(rawPacket.payload, rawPacket.packetLength - 2);
        }

        @Override
        public String toString() {
            return "IBusPacket { from: 0x" + Integer.toHexString(0xff & source)
                    + " to 0x" + Integer.toHexString(0xff & destination)
                    + " len: " + length
                    + " payload: " + HexDump.toHexString(payload)
                    + "}";
        }
    }

    interface IBusPacketListener {
        void onIBusPacket(IBusPacket packet);
    }

    class LocalBinder extends Binder {
        BmwIBusService getService() {
            return BmwIBusService.this;
        }
    }

    interface CancelShutdownListener {
        void onCancelShutdown();
    }

    private class ShutdownTask extends TimerTask {
        @Override
        public void run() {
            immediateShutdown();
        }
    }

    private class DisplayOffTask extends TimerTask {
        @Override
        public void run() {
            synchronized (this) {
                powerOffDisplay();
            }
        }
    }
}
