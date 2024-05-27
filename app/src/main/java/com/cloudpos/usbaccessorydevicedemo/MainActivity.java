package com.cloudpos.usbaccessorydevicedemo;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Refer to:
 * https://github.com/androidyaohui/HostChart
 * https://github.com/androidyaohui/AccessoryChart
 */
public class MainActivity extends Activity implements OpenAccessoryReceiver.OpenAccessoryListener, UsbDetachedReceiver.UsbDetachedListener, View.OnClickListener {

    public static final String TAG = "TRACE";
    private static final String ACTION_OPEN_ACCESSORY = "ACTION_OPEN_ACCESSORY";
    private static final int SEND_MESSAGE_SUCCESS = 1;
    private static final int RECEIVE_MESSAGE_SUCCESS = 2;

    private int counter = 0;
    private volatile boolean mIsReceiving = false;
    private Context mContext;
    private TextView mLog;
    private EditText mMessage;
    private Button mSend;
    private CheckBox mAutoConnect;
    private ExecutorService mThreadPool;
    private UsbManager mUsbManager;
    private UsbDetachedReceiver mUsbDetachedReceiver;
    private ParcelFileDescriptor mParcelFileDescriptor;
    private FileInputStream mFileInputStream;
    private FileOutputStream mFileOutputStream;

    private Runnable receiver = new Runnable() {
        @Override
        public void run() {
            readFromUsb();
        }
    };

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case SEND_MESSAGE_SUCCESS:
                    mMessage.setText("");
                    mMessage.clearComposingText();
                    break;
                case RECEIVE_MESSAGE_SUCCESS:
                    log((CharSequence) msg.obj);
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initView();
        initData();
        if (mAutoConnect.isChecked())
            tryOpenAccessory();
    }

    private void initView() {
        mLog = (TextView) findViewById(R.id.log);
        mMessage = (EditText) findViewById(R.id.message);
        mAutoConnect = (CheckBox) findViewById(R.id.autoConnect);
        mSend = (Button) findViewById(R.id.send);

        findViewById(R.id.start).setVisibility(View.GONE);
        findViewById(R.id.stop).setVisibility(View.GONE);
    }

    private void initData() {
        mContext = getApplicationContext();
        mThreadPool = Executors.newFixedThreadPool(3);
        mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        mUsbDetachedReceiver = new UsbDetachedReceiver(this);
        registerReceiver(mUsbDetachedReceiver, new IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED));
    }

    private void tryOpenAccessory() {
        resetUsbState();
        mLog.setText("");
        UsbAccessory[] accessories = mUsbManager.getAccessoryList(); // (in the current implementation there can be at most one)
        log("+tryOpenAccessory[" + ++counter + "] Acsr=" + (accessories == null ? -1 : accessories.length));
        UsbAccessory accessory = (accessories == null ? null : accessories[0]);
        if (accessory == null) {
            log("!accessory: null");
        } else if (!mUsbManager.hasPermission(accessory)) {
            log("=requestPermission A");
            requestPermission(accessory);
        } else {
            openAccessory(accessory);
        }
        log("-tryOpenAccessory");
    }

    /**
     * Open in Accessory mode
     *
     * @param accessory
     */
    private void openAccessory(UsbAccessory accessory) {
        logA("+openAccessory: serial=" + accessory.getSerial());
        mParcelFileDescriptor = mUsbManager.openAccessory(accessory);
        if (mParcelFileDescriptor == null) {
            logA("!No ParcelFileDescriptor");
        } else if (!"1123456789".equals(accessory.getSerial())) {
            logA("!SN: Ileggal");
        } else {
            FileDescriptor fileDescriptor = mParcelFileDescriptor.getFileDescriptor();
            mFileInputStream = new FileInputStream(fileDescriptor);
            mFileOutputStream = new FileOutputStream(fileDescriptor);
            mSend.setEnabled(true);
            mThreadPool.execute(receiver);
        }
        logA("-openAccessory");
    }

    private void requestPermission(UsbAccessory accessory) {
        mUsbManager.requestPermission(accessory, PendingIntent.getBroadcast(mContext, 0, new Intent(ACTION_OPEN_ACCESSORY), 0));
    }

    /**
     * The message receiving thread continuously loops to receive messages once the device (phone) initialization is completed.
     */
    private void readFromUsb() {
        logA("+readFromUsb");
        byte[] bytes = new byte[1024];
        mIsReceiving = true;
        for (int i = 0; mIsReceiving && i >= 0; ) {
            try {
                if ((i = mFileInputStream.read(bytes)) > 0) {
                    mHandler.obtainMessage(RECEIVE_MESSAGE_SUCCESS, new String(bytes, 0, i)).sendToTarget();
                }
            } catch (IOException e) {
                Log.d(TAG, e.getMessage());
                usbDetached();
            }
        }
        logA("-readFromUsb");
    }

    @Override
    public void openAccessoryMode(UsbAccessory accessory) {
        openAccessory(accessory);
    }

    @Override
    public void openAccessoryError() {
        logA("!openAccessoryError");
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.start:
                callUsbTether("setUsbTethering", true);
                return;
            case R.id.stop:
                callUsbTether("setUsbTethering", false);
                return;
            case R.id.connect:
                tryOpenAccessory();
                return;
        }
        final String messageContent = mMessage.getText().toString();
        if (TextUtils.isEmpty(messageContent) || mFileOutputStream == null) {
            Toast.makeText(this, "Not ready!", Toast.LENGTH_LONG).show();
            return;
        }

        mThreadPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    mFileOutputStream.write(messageContent.getBytes());
                    mHandler.sendEmptyMessage(SEND_MESSAGE_SUCCESS);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    @Override
    public void usbDetached() {
        logA("=onDetached");
        resetUsbState();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        log("=onDestroy");
        mThreadPool.shutdownNow();
        mHandler.removeCallbacksAndMessages(null);
//		unregisterReceiver(mOpenAccessoryReceiver);
        unregisterReceiver(mUsbDetachedReceiver);
        resetUsbState();
    }

    private void resetUsbState() {
        mIsReceiving = false;
        tryClose(mFileInputStream);
        mFileInputStream = null;
        tryClose(mFileOutputStream);
        mFileOutputStream = null;
        tryClose(mParcelFileDescriptor);
        mParcelFileDescriptor = null;
    }

    private void logA(CharSequence msg) {
        mHandler.obtainMessage(RECEIVE_MESSAGE_SUCCESS, msg).sendToTarget();
    }

    private void log(CharSequence msg) {
        Log.d(TAG, msg.toString());
        mLog.append(msg);
        mLog.append("\n");
    }

    public static void tryClose(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void callUsbTether(String methodName, boolean enabeled) {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        try {
            Method m = ConnectivityManager.class.getMethod(methodName, boolean.class);
            m.invoke(cm, enabeled);
        } catch (Exception e) {
            logA("!Excpt: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

class OpenAccessoryReceiver extends BroadcastReceiver {

    public OpenAccessoryReceiver() {
    }

    private OpenAccessoryListener mOpenAccessoryListener;

    public OpenAccessoryReceiver(OpenAccessoryListener openAccessoryListener) {
        mOpenAccessoryListener = openAccessoryListener;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(MainActivity.TAG, "=onReceiver: " + intent.getAction());
        UsbAccessory usbAccessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
        if (usbAccessory != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            mOpenAccessoryListener.openAccessoryMode(usbAccessory);
        } else {
            mOpenAccessoryListener.openAccessoryError();
        }
    }

    public interface OpenAccessoryListener {
        /**
         * Open in Accessory mode
         *
         * @param usbAccessory
         */
        void openAccessoryMode(UsbAccessory accessory);

        /**
         * Failed to open the device (phone).
         */
        void openAccessoryError();
    }
}

class UsbDetachedReceiver extends BroadcastReceiver {

    private UsbDetachedListener mUsbDetachedListener;

    public UsbDetachedReceiver(UsbDetachedListener usbDetachedListener) {
        mUsbDetachedListener = usbDetachedListener;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        mUsbDetachedListener.usbDetached();
    }

    public interface UsbDetachedListener {
        /**
         * USB disconnected.
         */
        void usbDetached();
    }
}

