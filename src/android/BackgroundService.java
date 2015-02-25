package com.red_folder.phonegap.plugin.backgroundservice;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Timer;

import java.util.TimerTask;

import org.json.JSONObject;

import android.app.AlarmManager;
import android.app.Service;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.IBinder;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.util.Log;

import com.red_folder.phonegap.plugin.backgroundservice.BackgroundServiceApi;

public abstract class BackgroundService extends Service {
	
	/*
	 ************************************************************************************************
	 * Static values 
	 ************************************************************************************************
	 */
	private static final String TAG = BackgroundService.class.getSimpleName();

	/*
	 ************************************************************************************************
	 * Fields 
	 ************************************************************************************************
	 */
	private Boolean mServiceInitialised = false;
    private String mServiceName = null;

	private final Object mResultLock = new Object();
	private JSONObject mLatestResult = null;

	private List<BackgroundServiceListener> mListeners = new ArrayList<BackgroundServiceListener>();
	
	private Date mPausedUntil = null;

    public void setPauseDuration(long pauseDuration) {
		this.mPausedUntil = new Date(new Date().getTime() + pauseDuration);
		
		// Call the onPause event
		onPause();
	}
	
	public Boolean getEnabled() {
		SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);  

		return sharedPrefs.getBoolean(this.getClass().getName() + ".Enabled", false);
	}

	public void setEnabled(Boolean enabled) {
		SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);  

		SharedPreferences.Editor editor = sharedPrefs.edit();
        editor.putBoolean(this.getClass().getName() + ".Enabled", enabled);
        editor.commit(); // Very important
	}
	
	public int getMilliseconds() {
		SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);  

		// Should default to a minute
		return sharedPrefs.getInt(this.getClass().getName() + ".Milliseconds", 60000 );	
	}

	public void setMilliseconds(int milliseconds) {
		SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);  

		SharedPreferences.Editor editor = sharedPrefs.edit();
        editor.putInt(this.getClass().getName() + ".Milliseconds", milliseconds);
        editor.commit(); // Very important
	}

	protected JSONObject getLatestResult() {
		synchronized (mResultLock) {
			return mLatestResult;
		}
	}
	
	protected void setLatestResult(JSONObject value) {
		synchronized (mResultLock) {
			this.mLatestResult = value;
		}
	}

	/*
	 ************************************************************************************************
	 * Overriden Methods 
	 ************************************************************************************************
	 */

	@Override  
	public IBinder onBind(Intent intent) {
		Log.i(TAG, "onBind called");
		return apiEndpoint;
	}     
	
	@Override  
	public void onCreate() {     
		super.onCreate();     
		Log.i(TAG, "Service creating");
	}

    @Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.i(TAG, "Service started");

        if( intent == null )
            return START_STICKY;

        if( mServiceName == null ) {
            mServiceName = intent.getAction();
            Log.i(TAG, "Service Name: " + mServiceName);

            initialiseService();
        }

        // Do not do work if not from alarm
        if( ! intent.getBooleanExtra("DoWork", false) )
            return START_STICKY;

        new WorkTask().execute();
        return START_STICKY;
    }
	
	@Override  
	public void onDestroy() {     
		super.onDestroy();     
		Log.i(TAG, "Service destroying");
		
		// Do not stop timer here
        // Service can be stopped unexpectedly, we want AlarmReceiver to start it again
	}

	/*
	 ************************************************************************************************
	 * Protected methods 
	 ************************************************************************************************
	 */
	protected void runOnce() {
		// Runs the doWork once
		// Sets the last result & updates the listeners
		new WorkTask().execute();
	}

	/*
	 ************************************************************************************************
	 * Private methods 
	 ************************************************************************************************
	 */
	private BackgroundServiceApi.Stub apiEndpoint = new BackgroundServiceApi.Stub() {

		/*
		 ************************************************************************************************
		 * Overriden Methods 
		 ************************************************************************************************
		 */
		@Override
		public String getLatestResult() throws RemoteException {
			synchronized (mResultLock) {
				if (mLatestResult == null)
					return "{}";
				else
					return mLatestResult.toString();
			}
		}

		@Override
		public void addListener(BackgroundServiceListener listener)
				throws RemoteException {

			synchronized (mListeners) {
				if (mListeners.add(listener))
					Log.d(TAG, "Listener added");
				else
					Log.d(TAG, "Listener not added");
			}
		}

		@Override
		public void removeListener(BackgroundServiceListener listener)
				throws RemoteException {

			synchronized (mListeners) {
				if (mListeners.size() > 0) {
					boolean removed = false;
					for (int i = 0; i < mListeners.size() && !removed; i++)
					{
						if (listener.getUniqueID().equals(mListeners.get(i).getUniqueID())) {
							mListeners.remove(i);
							removed = true;
						}
					}
					
					if (removed)
						Log.d(TAG, "Listener removed");
					else 
						Log.d(TAG, "Listener not found");
				}
			}
		}

		@Override
		public void enableTimer(int milliseconds) throws RemoteException {
			// First stop it just to be on the safe side
			stopTimerTask();
			
			// Then enable and set the milliseconds
			setEnabled(true);
			setMilliseconds(milliseconds);
			
			// Finally setup the TimerTask
			setupTimerTask();
		}

		@Override
		public void disableTimer() throws RemoteException {
			// Set to disabled
			setEnabled(false);
			
			// Stop the timer task
			stopTimerTask();
		}

		@Override
		public boolean isTimerEnabled() throws RemoteException {
			return getEnabled();
		}

		@Override
		public String getConfiguration() throws RemoteException {
			JSONObject array = getConfig();
			if (array == null)
				return "";
			else 
				return array.toString();
		}

		@Override
		public void setConfiguration(String configuration) throws RemoteException {
			try {
				JSONObject array = null;
				if (configuration.length() > 0) {
					array = new JSONObject(configuration);
				} else {
					array = new JSONObject();
				}	
				setConfig(array);
			} catch (Exception ex) {
				throw new RemoteException();
			}
		}

		@Override
		public int getTimerMilliseconds() throws RemoteException {
			return getMilliseconds();
		}

		@Override
		public void run() throws RemoteException {
			runOnce();
		}
	};

	private void initialiseService() {
		
		if (!this.mServiceInitialised) {
			Log.i(TAG, "Initialising the service");

			// Initialise the LatestResult object
			JSONObject tmp = initialiseLatestResult();

			Log.i(TAG, "Syncing result");
			this.setLatestResult(tmp);
		
			if (getEnabled())
				this.setupTimerTask();
			
			this.mServiceInitialised = true;
		}

	}

	private void setupTimerTask () {
        AlarmManager alarmMgr = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(this, AlarmReceiver.class);
        intent.putExtra("ServiceName", mServiceName);
        Log.d(TAG, "Start Alarm, servicename: " + mServiceName);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        Calendar time = Calendar.getInstance();
        time.setTimeInMillis(System.currentTimeMillis());
        time.add(Calendar.MILLISECOND, getMilliseconds());
        alarmMgr.setInexactRepeating(AlarmManager.RTC_WAKEUP, time.getTimeInMillis(), getMilliseconds(), pendingIntent);

		onTimerEnabled();
	}
	
	private void stopTimerTask() {
		
		Log.i(TAG, "stopTimerTask called");

        AlarmManager alarmMgr = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(this, AlarmReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        alarmMgr.cancel(pendingIntent);
		
		onTimerDisabled();
	}
	
	private TimerTask getTimerTask() {
		return new TimerTask() {

			@Override    
			public void run() {       
				Log.i(TAG, "Timer task starting work");

				Log.d(TAG, "Is the service paused?");
				Boolean paused = false;
				if (mPausedUntil != null) {
					Log.d(TAG, "Service is paused until " + (new SimpleDateFormat("dd/MM/yyyy hh:mm:ss")).format(mPausedUntil));
					Date current = new Date();
					Log.d(TAG, "Current is " + (new SimpleDateFormat("dd/MM/yyyy hh:mm:ss")).format(current));
					if (mPausedUntil.after(current)) {
						Log.d(TAG, "Service should be paused");
						paused = true;					// Still paused
					} else {
						Log.d(TAG, "Service should not be paused");
						mPausedUntil = null;				// Paused time has past so we can clear the pause
						onPauseComplete();
					}
				}

				if (paused) {
					Log.d(TAG, "Service is paused");
				} else {
					Log.d(TAG, "Service is not paused");
					
					// Runs the doWork 
					// Sets the last result & updates the listeners
					doWorkWrapper();
				}

				Log.i(TAG, "Timer task completing work");
			}   
		};

	}
	
	// Seperated out to allow the doWork to be called from timer and adhoc (via run method)
	private void doWorkWrapper() {
		JSONObject tmp = null;
		
		try {
			tmp = doWork();
		} catch (Exception ex) {
			Log.i(TAG, "Exception occurred during doWork()", ex);
		}

		Log.i(TAG, "Syncing result");
		setLatestResult(tmp);
		
		// Now call the listeners
		Log.i(TAG, "Sending to all listeners");
		for (int i = 0; i < mListeners.size(); i++)
		{
			try {
				mListeners.get(i).handleUpdate();
				Log.i(TAG, "Sent listener - " + i);
			} catch (RemoteException e) {
				Log.i(TAG, "Failed to send to listener - " + i + " - " + e.getMessage());
			}
		}
		
	}
	
	/*
	 ************************************************************************************************
	 * Methods for subclasses to override 
	 ************************************************************************************************
	 */
	protected abstract JSONObject initialiseLatestResult(); 
	protected abstract JSONObject doWork();
	protected abstract JSONObject getConfig();
	protected abstract void setConfig(JSONObject config);
	
	protected void onTimerEnabled() {
	}

	protected void onTimerDisabled() {
	}
	
	protected void onPause() {
	}
	
	protected void onPauseComplete() {
	}

    protected class WorkTask extends AsyncTask<Void, Void, Void> {
        @Override
        protected Void doInBackground(Void... voids) {
            doWorkWrapper();
            return null;
        }
    }
}
