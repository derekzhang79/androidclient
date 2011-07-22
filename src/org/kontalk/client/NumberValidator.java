package org.kontalk.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.http.NameValuePair;
import org.apache.http.message.BasicNameValuePair;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.telephony.PhoneNumberUtils;
import android.telephony.SmsMessage;
import android.util.Log;


/**
 * A basic worker thread for doing number validation procedures.
 * It handles all the steps defined in phone number validation, from the
 * validation request to the received SMS and finally the authentication token
 * request.
 * @author Daniele Ricci
 * @version 1.0
 */
public class NumberValidator implements Runnable {
    private static final String TAG = NumberValidator.class.getSimpleName();

    /** Initialization */
    public static final int STEP_INIT = 0;
    /** Validation step (sending phone number and waiting for SMS) */
    public static final int STEP_VALIDATION = 1;
    /** Requesting authentication token */
    public static final int STEP_AUTH_TOKEN = 2;

    private final Context mContext;
    private final EndpointServer mServer;
    private final String mPhone;
    private final RequestClient mClient;
    private final boolean mManual;
    private NumberValidatorListener mListener;
    private int mStep;
    private CharSequence mValidationCode;
    private BroadcastReceiver mSmsReceiver;
    private String mSmsFrom;

    private Thread mThread;

    public NumberValidator(Context context, EndpointServer server, String phone, boolean manual) {
        mContext = context;
        mServer = server;
        mPhone = phone;
        mClient = new RequestClient(context, mServer, null);
        mManual = manual;
    }

    public synchronized void start() {
        if (mThread != null) throw new IllegalArgumentException("already started");
        mThread = new Thread(this);
        mThread.start();
    }

    @Override
    public void run() {
        try {
            // begin!
            if (mStep == STEP_INIT) {
                // unregister previous receiver
                if (mSmsReceiver != null) {
                    mContext.unregisterReceiver(mSmsReceiver);
                    mSmsReceiver = null;
                }

                if (!mManual) {
                    // setup the sms receiver
                    IntentFilter filter = new IntentFilter("android.provider.Telephony.SMS_RECEIVED");
                    mSmsReceiver = new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            Log.d(TAG, "SMS received! " + intent.toString());

                            Bundle bdl = intent.getExtras();
                            Object pdus[] = (Object [])bdl.get("pdus");
                            for(int n=0; n < pdus.length; n++) {
                                byte[] byteData = (byte[])pdus[n];
                                SmsMessage sms = SmsMessage.createFromPdu(byteData);

                                // possible message!
                                if (mSmsFrom.equals(sms.getOriginatingAddress()) ||
                                        PhoneNumberUtils.compare(mSmsFrom, sms.getOriginatingAddress())) {
                                    String txt = sms.getMessageBody();
                                    if (txt != null && txt.length() > 0) {
                                        // FIXME take the entire message text for now
                                        mValidationCode = txt;
                                        mStep = STEP_AUTH_TOKEN;
                                        break;
                                    }
                                }
                            }

                            Log.d(TAG, "validation code found = \"" + mValidationCode + "\"");

                            if (mValidationCode != null) {
                                // unregister this receiver
                                context.unregisterReceiver(this);
                                mSmsReceiver = null;

                                // next start call will trigger the next condition
                                mThread = null;

                                // the listener will call start() again
                                if (mListener != null)
                                    mListener.onValidationCodeReceived(NumberValidator.this, mValidationCode);
                            }
                        }
                    };
                    mContext.registerReceiver(mSmsReceiver, filter);
                }

                // request number validation via sms
                mStep = STEP_VALIDATION;
                List<NameValuePair> params = new ArrayList<NameValuePair>(1);
                params.add(new BasicNameValuePair("n", mPhone));
                List<StatusResponse> res = mClient.request("validation", params, null);
                if (res.size() > 0) {
                    if (mListener != null) {
                        StatusResponse st = res.get(0);
                        if (st.code == StatusResponse.STATUS_SUCCESS) {
                            Map<String, Object> extra = st.extra;
                            if (extra != null) {
                                mSmsFrom = (String) extra.get("f");
                                Log.d(TAG, "using sms sender id: " + mSmsFrom);
                                mListener.onValidationRequested(this);
                            }
                            else {
                                // no sms from identification?
                                throw new IllegalArgumentException("no sms from id");
                            }
                        }
                        else {
                            // validation failed :(
                            mListener.onValidationFailed(this, st.code);
                            mStep = STEP_INIT;
                            return;
                        }
                    }
                }
                else {
                    // empty response!? :O
                    throw new IllegalArgumentException("invalid arguments");
                }

                // validation succeded! Waiting for the sms...
            }

            // sms received, request authentication token
            else if (mStep == STEP_AUTH_TOKEN) {
                Log.d(TAG, "requesting authentication token");

                List<NameValuePair> params = new ArrayList<NameValuePair>(1);
                params.add(new BasicNameValuePair("v", mValidationCode.toString()));
                List<StatusResponse> res = mClient.request("authentication", params, null);
                if (res.size() > 0) {
                    if (mListener != null) {
                        StatusResponse st = res.get(0);
                        if (st.code == StatusResponse.STATUS_SUCCESS) {
                            Map<String,Object> ex = st.extra;
                            if (ex != null) {
                                String token = (String) ex.get("a");
                                if (token != null && token.length() > 0);
                                    mListener.onAuthTokenReceived(this, token);
                            }
                        }
                        else {
                            // authentication failed :(
                            mListener.onAuthTokenFailed(this, st.code);
                            mStep = STEP_INIT;
                            return;
                        }
                    }
                }
                else {
                    // empty response!? :O
                    throw new IllegalArgumentException("invalid arguments");
                }
            }
        }
        catch (Throwable e) {
            if (mListener != null)
                mListener.onError(this, e);

            mStep = STEP_INIT;
        }
    }

    /**
     * Shuts down this thread gracefully.
     */
    public synchronized void shutdown() {
        Log.w(TAG, "shutting down");
        try {
            if (mThread != null) {
                mClient.abort();
                mThread.interrupt();
                mThread.join();
                mThread = null;
            }
            if (mSmsReceiver != null) {
                mContext.unregisterReceiver(mSmsReceiver);
                mSmsReceiver = null;
            }
        }
        catch (InterruptedException e) {
            // ignored
        }
        Log.w(TAG, "exiting");
    }

    /** Forcibly inputs the validation code. */
    public void manualInput(CharSequence code) {
        mValidationCode = code;
        mStep = STEP_AUTH_TOKEN;
        // next start call will trigger the next condition
        mThread = null;
    }

    public int getStep() {
        return mStep;
    }

    public synchronized void setListener(NumberValidatorListener listener) {
        mListener = listener;
    }

    public abstract interface NumberValidatorListener {
        /** Called if an exception get thrown. */
        public void onError(NumberValidator v, Throwable e);

        /** Called on confirmation that the validation SMS is being sent. */
        public void onValidationRequested(NumberValidator v);

        /** Called if phone number validation failed. */
        public void onValidationFailed(NumberValidator v, int reason);

        /** Called when the validation code SMS has been received. */
        public void onValidationCodeReceived(NumberValidator v, CharSequence code);

        /** Called on receiving of authentication token. */
        public void onAuthTokenReceived(NumberValidator v, CharSequence token);

        /** Called if validation code has not been verified. */
        public void onAuthTokenFailed(NumberValidator v, int reason);
    }
}