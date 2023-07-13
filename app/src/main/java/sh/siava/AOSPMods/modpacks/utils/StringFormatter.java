package sh.siava.AOSPMods.modpacks.utils;

import static de.robv.android.xposed.XposedBridge.log;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.usage.NetworkStats.Bucket;
import android.app.usage.NetworkStatsManager.UsageCallback;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.text.SpannableStringBuilder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.mfathi91.time.PersianDate;

import java.awt.font.NumericShaper;
import java.text.SimpleDateFormat;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.security.auth.callback.Callback;

import sh.siava.AOSPMods.BuildConfig;
import sh.siava.AOSPMods.modpacks.systemui.ThermalProvider;

@SuppressWarnings("deprecation")
public class StringFormatter {
	public static final int NET_STAT_TYPE_DAY = 0;
	public static final int NET_STAT_TYPE_WEEK = 1;
	public static final int NET_STAT_TYPE_MONTH = 2;

	private static final int TYPE_RX = 1 << 1;
	private static final int TYPE_TX = 1 << 2;
	private static final ArrayList<StringFormatter> instances = new ArrayList<>();

	private static final int NETWORK_STATS_NONE = 0;
	private static final int NETWORK_STATS_CELL = 1 << 1;
	private static final int NETWORK_STATS_WIFI = 1 << 2;
	private static final long NETSTAT_THRESHOLD_BYTES = 1024*1024;
	private final ArrayList<FormattedStringCallback> callbacks = new ArrayList<>();
	private boolean hasDate = false;
	public static Integer RXColor, TXColor;
	public static int NetStatsDayOf = Calendar.THURSDAY;
	public static LocalTime NetStatsStartTime = LocalTime.of(0,0);
	public static int NetStatStartBase = NET_STAT_TYPE_DAY;
	private Timer scheduler = new Timer();

	private int mNetworkStatsType = NETWORK_STATS_NONE;
	private int mRegisteredCallbackType = NETWORK_STATS_NONE;
	private final UsageCallback networkUsageCallback = new UsageCallback() {
		@Override
		public void onThresholdReached(int i, @Nullable String s) {
			informCallbacks();
		}
	};

	public StringFormatter() {
		instances.add(this);
		scheduleNextDateUpdate();
	}

	public static void refreshAll() {
		instances.forEach(StringFormatter::informCallbacks);
	}

	private void informCallbacks() {
		for (FormattedStringCallback callback : callbacks) {
			callback.onRefreshNeeded();
		}
	}

	private void scheduleNextDateUpdate() {
		try {
			AlarmManager alarmManager = SystemUtils.AlarmManager();

			Calendar alarmTime = Calendar.getInstance();
			alarmTime.set(Calendar.HOUR_OF_DAY, 0);
			alarmTime.set(Calendar.MINUTE, 0);
			alarmTime.add(Calendar.DATE, 1);

			//noinspection ConstantConditions
			alarmManager.set(AlarmManager.RTC,
					alarmTime.getTimeInMillis(),
					"",
					() -> {
						scheduleNextDateUpdate();
						if (hasDate) {
							informCallbacks();
						}
					},
					null);

		} catch (Throwable t) {
			if (BuildConfig.DEBUG) {
				log("Error setting formatted string update schedule");
				t.printStackTrace();
			}
		}
	}

	public CharSequence formatString(String input) {
		SpannableStringBuilder result = new SpannableStringBuilder(input);
		hasDate = false;
		mNetworkStatsType = NETWORK_STATS_NONE;
		Pattern pattern = Pattern.compile("\\$((T[a-zA-Z][0-9]*)|([A-Z][A-Za-z]+))"); //variables start with $ and continue with characters, until they don't!

		//We'll locate each variable and replace it with a value, if possible
		Matcher matcher = pattern.matcher(input);
		while (matcher.find()) {
			String match = matcher.group(1);

			int start = result.toString().indexOf("$" + match);
			//noinspection ConstantConditions
			result.replace(start, start + match.length() + 1, valueOf(match));
		}
		if(mNetworkStatsType != NETWORK_STATS_NONE)
		{
			registerUsageCallback();
		}
		return result;
	}

	@SuppressWarnings("ConstantConditions")
	private void registerUsageCallback() {
		if(mRegisteredCallbackType != mNetworkStatsType) {
			unregisterUsageCallback();
			try {
				SystemUtils.NetworkStatsManager().registerUsageCallback(ConnectivityManager.TYPE_MOBILE, null, NETSTAT_THRESHOLD_BYTES, networkUsageCallback);
				mRegisteredCallbackType |= NETWORK_STATS_CELL;
				if ((mNetworkStatsType & NETWORK_STATS_WIFI) != 0)
					SystemUtils.NetworkStatsManager().registerUsageCallback(ConnectivityManager.TYPE_WIFI, null, NETSTAT_THRESHOLD_BYTES, networkUsageCallback);
				mRegisteredCallbackType |= NETWORK_STATS_WIFI;
			} catch (Throwable ignored) {
			}
		}
	}

	@SuppressWarnings("ConstantConditions")
	private void unregisterUsageCallback()
	{
		try
		{
			SystemUtils.NetworkStatsManager().unregisterUsageCallback(networkUsageCallback);
			mRegisteredCallbackType = NETWORK_STATS_NONE;
		}
		catch (Throwable ignored){}
	}

	private CharSequence valueOf(String match) {
		if (match.startsWith("P")) //P is reserved for "Persian Calendar". Then goes normal Java dateformat, like $Pdd or $Pyyyy
		{
			return persianDateOf(match.substring(1));
		}
		else if (match.startsWith("G")) //G is reserved for "Georgian Calendar". Then goes normal Java dateformat, like $Gyyyy or $GEEE
		{
			return georgianDateOf(match.substring(1));
		}
		else if (match.startsWith("N")) {
			return networkStatOf(match.substring(1));
		}
		else if(match.startsWith("T"))
		{
			return temperatureOf(match.substring(1));
		}
		return "$" + match;
	}

	private CharSequence networkStatOf(String variable) {
		long traffic = 0;
		Integer textColor = null;
		variable = variable.toLowerCase();
		CharSequence transformed = null;

		long startTime = -1;
		switch (NetStatStartBase)
		{
			case NET_STAT_TYPE_DAY:
				startTime = getStartTime(NetStatsStartTime);
				break;
			case NET_STAT_TYPE_WEEK:
				startTime = getStartTime(Calendar.DAY_OF_WEEK, NetStatsDayOf);
				break;
			case NET_STAT_TYPE_MONTH:
				startTime = getStartTime(Calendar.DAY_OF_MONTH, NetStatsDayOf);
				break;
		}

		try {
			switch (variable) {
				case "crx":
				case "rx":
					textColor = RXColor;
					traffic = getTrafficStats(startTime, TYPE_RX, variable.startsWith("c"));
					break;
				case "ctx":
				case "tx":
					textColor = TXColor;
					traffic = getTrafficStats(startTime, TYPE_TX, variable.startsWith("c"));
					break;
				case "call":
				case "all":
					traffic = getTrafficStats(startTime, TYPE_RX | TYPE_TX, variable.startsWith("c"));
					break;
				case "ssid":
					transformed = fetchCurrentWifiSSID();
					break;
			}

			if (transformed == null) {
				transformed = Helpers.getHumanizedBytes(traffic, .6f, "", "", textColor);
			}
			return transformed;
		} catch (Exception ignored) {
			return "$N" + variable;
		}
	}

	@SuppressLint("MissingPermission")
	private String fetchCurrentWifiSSID() {
		//method is deprecated, but will continue to work until further notice. the new way can be found here:
		//https://cs.android.com/android/platform/superproject/main/+/main:packages/modules/Wifi/framework/java/android/net/wifi/WifiManager.java;l=3660?q=wifimanager
		String ssid = "";
		@SuppressWarnings({"ConstantConditions", "deprecation"})
		String theSsid = SystemUtils.WifiManager().getConnectionInfo().getSSID();
		if (theSsid.startsWith("\"") && theSsid.endsWith("\"")) {
			theSsid = theSsid.substring(1, theSsid.length() - 1);
		}
		if (!WifiManager.UNKNOWN_SSID.equals(theSsid)) {
			ssid = theSsid;
		}
		return ssid;
	}

	private long getTrafficStats(long startTime, int type, boolean mobileOnly)
	{
		long ret = 0;
		try
		{
			@SuppressWarnings("ConstantConditions")
			Bucket bucket = SystemUtils.NetworkStatsManager().querySummaryForDevice(ConnectivityManager.TYPE_MOBILE, null, startTime, Calendar.getInstance().getTime().getTime());
			ret += getTraffic(bucket, type);

			if(!mobileOnly)
			{
				mNetworkStatsType |= NETWORK_STATS_WIFI;

				//noinspection ConstantConditions
				bucket = SystemUtils.NetworkStatsManager().querySummaryForDevice(ConnectivityManager.TYPE_WIFI, null, startTime, Calendar.getInstance().getTime().getTime());
				ret += getTraffic(bucket, type);
			}
		}
		catch (Throwable ignored)
		{
			ret = 0;
		}
		mNetworkStatsType |= NETWORK_STATS_CELL;
		return ret;
	}

	private long getTraffic(Bucket bucket, int type) {
		long ret = 0;
		if((type & TYPE_RX) != 0)
		{
			ret += bucket.getRxBytes();
		}
		if((type & TYPE_TX) != 0)
		{
			ret += bucket.getTxBytes();
		}
		return ret;
	}

	private long getStartTime(LocalTime timeOfDay)
	{
		Calendar startTimeCalendar = Calendar.getInstance();
		startTimeCalendar.set(Calendar.HOUR_OF_DAY, timeOfDay.getHour());
		startTimeCalendar.set(Calendar.MINUTE, timeOfDay.getMinute());

		if(startTimeCalendar.after(Calendar.getInstance()))
		{
			startTimeCalendar.set(Calendar.DATE, -1);
		}
		return startTimeCalendar.getTime().getTime();
	}

	private long getStartTime(int dayType, int dayOf)
	{
		Calendar startTimeCalendar = Calendar.getInstance();

		startTimeCalendar.set(Calendar.HOUR_OF_DAY, 0);
		startTimeCalendar.set(Calendar.MINUTE, 0);

		switch (dayType)
		{
			case Calendar.DAY_OF_MONTH:
				startTimeCalendar.set(Calendar.DAY_OF_MONTH, dayOf);
				if(startTimeCalendar.after(Calendar.getInstance()))
				{
					startTimeCalendar.set(Calendar.MONTH, -1);
				}
				break;
			case Calendar.DAY_OF_WEEK:
				startTimeCalendar.set(Calendar.DAY_OF_WEEK, dayOf);
				if(startTimeCalendar.after(Calendar.getInstance()))
				{
					startTimeCalendar.set(Calendar.DATE, -7);
				}
				break;
			default:
				return Calendar.getInstance().getTime().getTime();
		}

		return startTimeCalendar.getTime().getTime();
	}


	private CharSequence georgianDateOf(String format) {
		try {
			@SuppressLint("SimpleDateFormat")
			String result = new SimpleDateFormat(format).
					format(
							Calendar.getInstance().getTime()
					);
			hasDate = true;
			return result;
		} catch (Exception ignored) {
			return "$G" + format;
		}
	}

	private CharSequence temperatureOf(String format)
	{
		try
		{
			Matcher match = Pattern.compile("^([A-Za-z])([0-9]*)$").matcher(format);

			if(!match.find())
			{
				throw new Exception();
			}
			String typeStr = match.group(1);

			long nextUpdate;

			try
			{
				//noinspection ConstantConditions
				nextUpdate = Integer.parseInt(match.group(2));
			}
			catch (Throwable ignored)
			{
				nextUpdate = 60;
			}

			nextUpdate *= 1000L;

			int type;

			//noinspection ConstantConditions
			switch (typeStr.toLowerCase())
			{
				case "b":
					type = ThermalProvider.BATTERY;
					break;
				case "c":
					type = ThermalProvider.CPU;
					break;
				case "g":
					type = ThermalProvider.GPU;
					break;
				case "s":
					type = ThermalProvider.SKIN;
					break;
				default:
					throw new Exception();
			}

			int temperature = ThermalProvider.getTemperatureMaxInt(type);

			if(temperature < -990)
			{
				scheduleUpdate(1000L);
				return "Err";
			}

			scheduleUpdate(nextUpdate);

			return String.valueOf(temperature);

		} catch (Exception ignored)
		{
			return "$T" + format;
		}
	}

	private void scheduleUpdate(long nextUpdate) {
		scheduler.cancel();

		scheduler = new Timer();
		scheduler.schedule(new TimerTask() {
			@Override
			public void run() {
				informCallbacks();
			}
		}, nextUpdate);
	}

	private CharSequence persianDateOf(String format) {
		try {
			String result = PersianDate.now().format(
					DateTimeFormatter.ofPattern(
							format,
							Locale.forLanguageTag("fa")
					)
			);
			hasDate = true;
			char[] bytes = result.toCharArray();
			NumericShaper.getShaper(NumericShaper.EASTERN_ARABIC).shape(bytes, 0, bytes.length); //Numbers to be shown in correct font
			return String.copyValueOf(bytes);
		} catch (Exception ignored) {
			return "$P" + format;
		}
	}

	public void registerCallback(@NonNull FormattedStringCallback callback) {
		callbacks.add(callback);
	}

	@SuppressWarnings("unused")
	public void unRegisterCallback(@NonNull FormattedStringCallback callback) {
		callbacks.remove(callback);
	}

	@SuppressWarnings("unused")
	public void resetCallbacks() {
		callbacks.clear();
	}

	public interface FormattedStringCallback extends Callback {
		void onRefreshNeeded();
	}
}