package com.capacitorjs.plugins.localnotifications;

import static android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import androidx.activity.result.ActivityResult;
import androidx.core.app.NotificationCompat;
import com.getcapacitor.Bridge;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginHandle;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

@CapacitorPlugin(
    name = "LocalNotifications",
    permissions = @Permission(strings = { Manifest.permission.POST_NOTIFICATIONS }, alias = LocalNotificationsPlugin.LOCAL_NOTIFICATIONS)
)
public class LocalNotificationsPlugin extends Plugin {

    static final String LOCAL_NOTIFICATIONS = "display";
    private static final String LIVE_ACTIVITY_CHANNEL_ID = "live_activity";

    private static Bridge staticBridge = null;
    private LocalNotificationManager manager;
    public NotificationManager notificationManager;
    private NotificationStorage notificationStorage;
    private NotificationChannelManager notificationChannelManager;

    // Store active live activities: activityId -> LiveActivityConfig
    private final Map<String, LiveActivityConfig> activeLiveActivities = new HashMap<>();

    // Configuration d'une Live Activity pour pouvoir la mettre à jour
    private static class LiveActivityConfig {

        int notificationId;
        String title;
        String message;
        String channelId;
        String actionTypeId;
        JSObject timer;
        long startTimestamp;
        long maxDurationMs;
        boolean hasProgressService;

        LiveActivityConfig(
            int notificationId,
            String title,
            String message,
            String channelId,
            String actionTypeId,
            JSObject timer,
            long startTimestamp,
            long maxDurationMs,
            boolean hasProgressService
        ) {
            this.notificationId = notificationId;
            this.title = title;
            this.message = message;
            this.channelId = channelId;
            this.actionTypeId = actionTypeId;
            this.timer = timer;
            this.startTimestamp = startTimestamp;
            this.maxDurationMs = maxDurationMs;
            this.hasProgressService = hasProgressService;
        }
    }

    @Override
    public void load() {
        super.load();
        notificationStorage = new NotificationStorage(getContext());
        manager = new LocalNotificationManager(notificationStorage, getActivity(), getContext(), this.bridge.getConfig());
        manager.createNotificationChannel();
        notificationChannelManager = new NotificationChannelManager(getActivity());
        notificationManager = (NotificationManager) getActivity().getSystemService(Context.NOTIFICATION_SERVICE);
        staticBridge = this.bridge;

        // Handle notification action from launch intent (when app was closed)
        handleLaunchIntent();
    }

    /**
     * Handle notification action from the launch intent.
     * This is called when the app is launched from a notification action
     * while the app was completely closed.
     */
    private void handleLaunchIntent() {
        if (getActivity() == null) return;

        Intent launchIntent = getActivity().getIntent();
        if (launchIntent == null) return;

        // Check if this intent contains notification action data
        if (launchIntent.hasExtra(LocalNotificationManager.ACTION_INTENT_KEY)) {
            // Delay processing to ensure bridge is ready
            getActivity()
                .runOnUiThread(
                    () -> {
                        // Small delay to ensure JavaScript is ready
                        new android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(
                                () -> {
                                    JSObject dataJson = manager.handleNotificationActionPerformed(launchIntent, notificationStorage);
                                    if (dataJson != null) {
                                        notifyListeners("localNotificationActionPerformed", dataJson, true);
                                    }
                                },
                                500
                            );
                    }
                );
        }
    }

    @Override
    protected void handleOnNewIntent(Intent data) {
        super.handleOnNewIntent(data);
        if (!Intent.ACTION_MAIN.equals(data.getAction())) {
            return;
        }
        JSObject dataJson = manager.handleNotificationActionPerformed(data, notificationStorage);
        if (dataJson != null) {
            notifyListeners("localNotificationActionPerformed", dataJson, true);
        }
    }

    @Override
    protected void handleOnDestroy() {
        super.handleOnDestroy();
        // End all Live Activities when app is destroyed
        endAllLiveActivities();
    }

    /**
     * End all active Live Activities.
     * Called when the app is destroyed to clean up ongoing notifications.
     */
    private void endAllLiveActivities() {
        // Stop the timer progress service
        TimerProgressService.stopTimer(getContext());

        // Cancel all ongoing notifications
        for (String activityId : activeLiveActivities.keySet()) {
            LiveActivityConfig config = activeLiveActivities.get(activityId);
            if (config != null) {
                notificationManager.cancel(config.notificationId);
                cancelTimerEndAlarm(activityId);
            }
        }
        activeLiveActivities.clear();
    }

    /**
     * Schedule a notification call from JavaScript
     * Creates local notification in system.
     */
    @PluginMethod
    public void schedule(PluginCall call) {
        List<LocalNotification> localNotifications = LocalNotification.buildNotificationList(call);
        if (localNotifications == null) {
            return;
        }
        JSONArray ids = manager.schedule(call, localNotifications);
        if (ids != null) {
            notificationStorage.appendNotifications(localNotifications);
            JSObject result = new JSObject();
            JSArray jsArray = new JSArray();
            for (int i = 0; i < ids.length(); i++) {
                try {
                    JSObject notification = new JSObject().put("id", ids.getInt(i));
                    jsArray.put(notification);
                } catch (Exception ex) {}
            }
            result.put("notifications", jsArray);
            call.resolve(result);
        }
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        manager.cancel(call);
    }

    @PluginMethod
    public void getPending(PluginCall call) {
        List<LocalNotification> notifications = notificationStorage.getSavedNotifications();
        JSObject result = LocalNotification.buildLocalNotificationPendingList(notifications);
        call.resolve(result);
    }

    @PluginMethod
    public void registerActionTypes(PluginCall call) {
        JSArray types = call.getArray("types");
        Map<String, NotificationAction[]> typesArray = NotificationAction.buildTypes(types);
        notificationStorage.writeActionGroup(typesArray);
        call.resolve();
    }

    @PluginMethod
    public void areEnabled(PluginCall call) {
        JSObject data = new JSObject();
        data.put("value", manager.areNotificationsEnabled());
        call.resolve(data);
    }

    @PluginMethod
    public void getDeliveredNotifications(PluginCall call) {
        JSArray notifications = new JSArray();
        StatusBarNotification[] activeNotifications = notificationManager.getActiveNotifications();

        for (StatusBarNotification notif : activeNotifications) {
            JSObject jsNotif = new JSObject();

            jsNotif.put("id", notif.getId());
            jsNotif.put("tag", notif.getTag());

            Notification notification = notif.getNotification();
            if (notification != null) {
                jsNotif.put("title", notification.extras.getCharSequence(Notification.EXTRA_TITLE));
                jsNotif.put("body", notification.extras.getCharSequence(Notification.EXTRA_TEXT));
                jsNotif.put("group", notification.getGroup());
                jsNotif.put("groupSummary", 0 != (notification.flags & Notification.FLAG_GROUP_SUMMARY));

                JSObject extras = new JSObject();

                for (String key : notification.extras.keySet()) {
                    extras.put(key, notification.extras.getString(key));
                }

                jsNotif.put("data", extras);
            }

            notifications.put(jsNotif);
        }

        JSObject result = new JSObject();
        result.put("notifications", notifications);
        call.resolve(result);
    }

    @PluginMethod
    public void removeDeliveredNotifications(PluginCall call) {
        JSArray notifications = call.getArray("notifications");

        try {
            for (Object o : notifications.toList()) {
                if (o instanceof JSONObject) {
                    JSObject notif = JSObject.fromJSONObject((JSONObject) o);
                    String tag = notif.getString("tag");
                    Integer id = notif.getInteger("id");

                    if (tag == null) {
                        notificationManager.cancel(id);
                    } else {
                        notificationManager.cancel(tag, id);
                    }
                } else {
                    call.reject("Expected notifications to be a list of notification objects");
                }
            }
        } catch (JSONException e) {
            call.reject(e.getMessage());
        }

        call.resolve();
    }

    @PluginMethod
    public void removeAllDeliveredNotifications(PluginCall call) {
        notificationManager.cancelAll();
        call.resolve();
    }

    @PluginMethod
    public void createChannel(PluginCall call) {
        notificationChannelManager.createChannel(call);
    }

    @PluginMethod
    public void deleteChannel(PluginCall call) {
        notificationChannelManager.deleteChannel(call);
    }

    @PluginMethod
    public void listChannels(PluginCall call) {
        notificationChannelManager.listChannels(call);
    }

    @PluginMethod
    public void checkPermissions(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            JSObject permissionsResultJSON = new JSObject();
            permissionsResultJSON.put("display", getNotificationPermissionText());
            call.resolve(permissionsResultJSON);
        } else {
            super.checkPermissions(call);
        }
    }

    @PluginMethod
    public void requestPermissions(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || getPermissionState(LOCAL_NOTIFICATIONS) == PermissionState.GRANTED) {
            JSObject permissionsResultJSON = new JSObject();
            permissionsResultJSON.put("display", getNotificationPermissionText());
            call.resolve(permissionsResultJSON);
        } else {
            requestPermissionForAlias(LOCAL_NOTIFICATIONS, call, "permissionsCallback");
        }
    }

    @PluginMethod
    public void changeExactNotificationSetting(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startActivityForResult(
                call,
                new Intent(ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getActivity().getPackageName())),
                "alarmPermissionsCallback"
            );
        } else {
            checkExactNotificationSetting(call);
        }
    }

    @PluginMethod
    public void checkExactNotificationSetting(PluginCall call) {
        JSObject permissionsResultJSON = new JSObject();
        permissionsResultJSON.put("exact_alarm", getExactAlarmPermissionText());

        call.resolve(permissionsResultJSON);
    }

    @PermissionCallback
    private void permissionsCallback(PluginCall call) {
        JSObject permissionsResultJSON = new JSObject();
        permissionsResultJSON.put("display", getNotificationPermissionText());

        call.resolve(permissionsResultJSON);
    }

    @ActivityCallback
    private void alarmPermissionsCallback(PluginCall call, ActivityResult result) {
        checkExactNotificationSetting(call);
    }

    private String getNotificationPermissionText() {
        if (manager.areNotificationsEnabled()) {
            return "granted";
        } else {
            return "denied";
        }
    }

    private String getExactAlarmPermissionText() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager alarmManager = (AlarmManager) getActivity().getSystemService(Context.ALARM_SERVICE);
            if (alarmManager.canScheduleExactAlarms()) {
                return "granted";
            } else {
                return "denied";
            }
        }

        return "granted";
    }

    public static void fireReceived(JSObject notification) {
        LocalNotificationsPlugin localNotificationsPlugin = LocalNotificationsPlugin.getLocalNotificationsInstance();
        if (localNotificationsPlugin != null) {
            localNotificationsPlugin.notifyListeners("localNotificationReceived", notification, true);
        }
    }

    public static LocalNotificationsPlugin getLocalNotificationsInstance() {
        if (staticBridge != null && staticBridge.getWebView() != null) {
            PluginHandle handle = staticBridge.getPlugin("LocalNotifications");
            if (handle == null) {
                return null;
            }
            return (LocalNotificationsPlugin) handle.getInstance();
        }
        return null;
    }

    /**
     * Public method to notify listeners from external classes (like TimerEndReceiver).
     * This is needed because notifyListeners is protected in the parent Plugin class.
     */
    public void fireTimerEnded(String activityId) {
        // Mettre à jour activeLiveActivities avec le nouveau notification ID (exceeded)
        // pour que endLiveActivity() annule la bonne notification plus tard
        LiveActivityConfig config = activeLiveActivities.get(activityId);
        if (config != null) {
            int exceededNotificationId = config.notificationId + TimerProgressService.EXCEEDED_NOTIFICATION_ID_OFFSET;
            activeLiveActivities.put(
                activityId,
                new LiveActivityConfig(
                    exceededNotificationId,
                    config.title,
                    config.message,
                    config.channelId,
                    config.actionTypeId,
                    config.timer,
                    config.startTimestamp,
                    config.maxDurationMs,
                    false // Le service s'est arrêté, plus besoin de le stopper
                )
            );
        }

        JSObject data = new JSObject();
        data.put("activityId", activityId);
        notifyListeners("liveActivityEnded", data, true);
    }

    /**
     * Dismiss an activity automatically after timer end.
     * Called from TimerEndReceiver to auto-dismiss the notification.
     * This implements the TTL (Time-To-Live) system.
     */
    public void dismissActivityAfterTimerEnd(String activityId) {
        LiveActivityConfig config = activeLiveActivities.get(activityId);
        if (config != null) {
            // Stop the progress service if running
            if (config.hasProgressService) {
                TimerProgressService.stopTimer(getContext());
            }

            // Cancel the notification
            notificationManager.cancel(config.notificationId);

            // Cancel any pending timer alarm
            cancelTimerEndAlarm(activityId);

            // Remove from active list
            activeLiveActivities.remove(activityId);

            Logger.debug(Logger.tags("LN"), "Auto-dismissed activity after timer end: " + activityId);
        }
    }

    // ============================================
    // LIVE ACTIVITY / TIMER NOTIFICATION METHODS
    // ============================================

    /**
     * Start a Live Activity (on Android, this is a notification with native chronometer).
     * The system handles timer display automatically - works in background.
     */
    @PluginMethod
    public void startLiveActivity(PluginCall call) {
        String id = call.getString("id");
        String title = call.getString("title");
        String message = call.getString("message");
        String actionTypeId = call.getString("actionTypeId");

        if (id == null || title == null) {
            call.reject("id and title are required");
            return;
        }

        // Generate notification ID from string ID
        int notificationId = Math.abs(id.hashCode());

        // Get channel ID, default to live_activity channel
        String channelId = call.getString("channelId", LIVE_ACTIVITY_CHANNEL_ID);

        // Ensure the channel exists (create the specific channelId, not just default)
        createLiveActivityChannelIfNeeded(channelId);

        // Check if initial vibration is requested
        Boolean shouldVibrate = call.getBoolean("vibrate", true);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(getContext(), channelId)
            .setContentTitle(title)
            .setContentText(message != null ? message : "")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true); // Prevent button flickering on updates

        // Set vibration pattern if requested (will only vibrate on first show due to setOnlyAlertOnce)
        if (shouldVibrate) {
            builder.setVibrate(new long[] { 0, 300, 200, 300 }); // Vibration pattern
            builder.setDefaults(NotificationCompat.DEFAULT_VIBRATE | NotificationCompat.DEFAULT_LIGHTS);
        }

        // Configure timer if present
        JSObject timer = call.getObject("timer");
        boolean hasTimer = false;
        long startTimestamp = System.currentTimeMillis();
        long maxDurationMs = 0;
        boolean hasProgressService = false;
        Long scheduledAlarmTimestamp = null; // Timestamp unique pour l'alarm

        if (timer != null) {
            String mode = timer.getString("mode", "countdown");
            // Use optLong to avoid JSONException - returns 0 if not found
            long targetTimestamp = timer.optLong("targetTimestamp", 0);

            // Get maxDuration for elapsed timers
            maxDurationMs = timer.optLong("maxDurationMs", 0);
            startTimestamp = timer.optLong("startTimestamp", System.currentTimeMillis());

            if (targetTimestamp > 0) {
                hasTimer = true;
                builder.setUsesChronometer(true);
                builder.setWhen(targetTimestamp);
                builder.setShowWhen(true);

                // API 24+ for countdown mode
                if ("countdown".equals(mode) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    builder.setChronometerCountDown(true);
                }

                // Determine si on doit schedule un alarm
                Boolean alertOnEnd = timer.getBoolean("alertOnEnd", false);
                if (alertOnEnd) {
                    // For countdown: use targetTimestamp
                    // For elapsed with maxDuration: use alertTimestamp if provided
                    scheduledAlarmTimestamp = timer.optLong("alertTimestamp", targetTimestamp);
                }

                // For elapsed timers, start the background progress service.
                // maxDurationMs == 0 = open-ended (free parking): TimerProgressService
                // is still used for the foreground service guarantee (notif persistence,
                // survives app process kill); its updateProgress() loop early-returns when
                // maxDurationMs <= 0, so no exceeded transition fires natively.
                if ("elapsed".equals(mode)) {
                    hasProgressService = true;
                    TimerProgressService.startTimer(
                        getContext(),
                        id,
                        notificationId,
                        title,
                        message,
                        channelId,
                        actionTypeId,
                        startTimestamp,
                        maxDurationMs
                    );
                }
            }
        }

        // Support for TTL (Time-To-Live) - PRIORITAIRE, remplace scheduledAlarmTimestamp
        Integer timeToLiveSeconds = call.getInt("timeToLive");
        if (timeToLiveSeconds != null && timeToLiveSeconds > 0) {
            if (timer != null) {
                String mode = timer.getString("mode", "countdown");

                if ("countdown".equals(mode)) {
                    // Countdown: calculer le timestamp côté Android pour aligner affichage et TTL
                    long computedTargetTimestamp = System.currentTimeMillis() + (timeToLiveSeconds * 1000L);
                    scheduledAlarmTimestamp = computedTargetTimestamp;

                    // Aligner le chronometer sur le timestamp calculé côté Android
                    builder.setUsesChronometer(true);
                    builder.setWhen(computedTargetTimestamp);
                    builder.setShowWhen(true);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        builder.setChronometerCountDown(true);
                    }

                    Logger.debug(Logger.tags("LN"), "TTL Sync: countdown computed target = " + computedTargetTimestamp);
                } else {
                    // Elapsed: utiliser startTimestamp + maxDurationMs (timestamps absolus)
                    long startTimestampVal = timer.optLong("startTimestamp", System.currentTimeMillis());
                    long maxDurationMsVal = timer.optLong("maxDurationMs", 0);

                    if (maxDurationMsVal > 0) {
                        scheduledAlarmTimestamp = startTimestampVal + maxDurationMsVal;
                        Logger.debug(Logger.tags("LN"), "TTL Sync: elapsed absolute target = " + scheduledAlarmTimestamp);
                    } else {
                        // Fallback: calculer depuis maintenant si maxDurationMs manquant
                        scheduledAlarmTimestamp = System.currentTimeMillis() + (timeToLiveSeconds * 1000L);
                        Logger.debug(Logger.tags("LN"), "TTL Sync: fallback elapsed relative = " + scheduledAlarmTimestamp);
                    }
                }
            } else {
                // Notification sans timer: calcul relatif
                scheduledAlarmTimestamp = System.currentTimeMillis() + (timeToLiveSeconds * 1000L);
                Logger.debug(Logger.tags("LN"), "TTL Sync: relative no-timer = " + scheduledAlarmTimestamp);
            }

            // Utiliser timeoutAfter si disponible pour suppression native précise
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setTimeoutAfter(timeToLiveSeconds * 1000L);
            }
        }

        // Schedule l'alarm UNE SEULE FOIS si necessaire
        if (scheduledAlarmTimestamp != null) {
            long currentTime = System.currentTimeMillis();
            scheduleTimerEndAlarm(id, scheduledAlarmTimestamp);
            long delaySeconds = (scheduledAlarmTimestamp - currentTime) / 1000;
            Logger.debug(
                Logger.tags("LN"),
                "Scheduled alarm for " +
                id +
                " in " +
                delaySeconds +
                " seconds (current=" +
                currentTime +
                ", target=" +
                scheduledAlarmTimestamp +
                ")"
            );
        }

        // Configure progress bar if present
        JSObject progress = call.getObject("progress");
        if (progress != null) {
            int max = progress.getInteger("max", 100);
            int current = progress.getInteger("current", 0);
            boolean indeterminate = progress.getBoolean("indeterminate", false);
            builder.setProgress(max, current, indeterminate);
        }

        // Use BigTextStyle to ensure message is visible even with chronometer
        if (hasTimer || progress != null) {
            String displayText = message != null ? message : "";
            NotificationCompat.BigTextStyle bigTextStyle = new NotificationCompat.BigTextStyle()
                .bigText(displayText)
                .setBigContentTitle(title);
            builder.setStyle(bigTextStyle);
        }

        // Add action buttons if actionTypeId is provided
        if (actionTypeId != null) {
            addActionsToLiveActivity(builder, id, notificationId, actionTypeId);
        }

        // Add content intent (open app when notification is tapped)
        addContentIntentToLiveActivity(builder, id, notificationId);

        // Store configuration for updates BEFORE showing notification
        activeLiveActivities.put(
            id,
            new LiveActivityConfig(
                notificationId,
                title,
                message,
                channelId,
                actionTypeId,
                timer,
                startTimestamp,
                maxDurationMs,
                hasProgressService
            )
        );

        // Show the notification only if the service won't handle it
        // When hasProgressService is true, the foreground service shows the notification
        if (!hasProgressService) {
            notificationManager.notify(notificationId, builder.build());
        }

        JSObject result = new JSObject();
        result.put("activityId", id);
        result.put("notificationId", notificationId);
        call.resolve(result);
    }

    /**
     * Add action buttons to a Live Activity notification.
     */
    private void addActionsToLiveActivity(NotificationCompat.Builder builder, String activityId, int notificationId, String actionTypeId) {
        NotificationAction[] actionGroup = notificationStorage.getActionGroup(actionTypeId);
        if (actionGroup == null) {
            return;
        }

        // Use FLAG_UPDATE_CURRENT to reuse existing PendingIntents instead of recreating them
        // This prevents button flickering/graying on notification updates
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags | PendingIntent.FLAG_MUTABLE;
        }

        for (NotificationAction action : actionGroup) {
            Intent actionIntent = buildLiveActivityActionIntent(activityId, notificationId, action.getId());
            PendingIntent actionPendingIntent = PendingIntent.getActivity(
                getContext(),
                notificationId + action.getId().hashCode(),
                actionIntent,
                flags
            );

            NotificationCompat.Action.Builder actionBuilder = new NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_send, // Default icon
                action.getTitle(),
                actionPendingIntent
            );

            builder.addAction(actionBuilder.build());
        }
    }

    /**
     * Add content intent (tap to open) to a Live Activity notification.
     */
    private void addContentIntentToLiveActivity(NotificationCompat.Builder builder, String activityId, int notificationId) {
        Intent intent = buildLiveActivityActionIntent(activityId, notificationId, "tap");
        // Use FLAG_UPDATE_CURRENT to reuse existing PendingIntent
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags | PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(getContext(), notificationId, intent, flags);
        builder.setContentIntent(pendingIntent);
    }

    /**
     * Build an intent for Live Activity actions.
     */
    private Intent buildLiveActivityActionIntent(String activityId, int notificationId, String actionId) {
        String packageName = getContext().getPackageName();
        Intent intent = getContext().getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) {
            intent = new Intent();
        }
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(LocalNotificationManager.NOTIFICATION_INTENT_KEY, notificationId);
        intent.putExtra(LocalNotificationManager.ACTION_INTENT_KEY, actionId);
        intent.putExtra("liveActivityId", activityId);
        intent.putExtra(LocalNotificationManager.NOTIFICATION_IS_REMOVABLE_KEY, true);

        // Build a minimal notification JSON for the action handler
        JSObject notificationObj = new JSObject();
        notificationObj.put("id", notificationId);
        notificationObj.put("liveActivityId", activityId);
        intent.putExtra(LocalNotificationManager.NOTIFICATION_OBJ_INTENT_KEY, notificationObj.toString());

        return intent;
    }

    /**
     * Update a Live Activity content.
     */
    @PluginMethod
    public void updateLiveActivity(PluginCall call) {
        String id = call.getString("id");
        if (id == null) {
            call.reject("id is required");
            return;
        }

        LiveActivityConfig config = activeLiveActivities.get(id);
        if (config == null) {
            call.reject("No active Live Activity with id: " + id);
            return;
        }

        String title = call.getString("title");
        String message = call.getString("message");

        // Use stored config or provided values
        String finalTitle = title != null ? title : config.title;
        String finalMessage = message != null ? message : (config.message != null ? config.message : "");

        // Check if vibration is requested (for alert state)
        Boolean shouldVibrate = call.getBoolean("vibrate", false);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(getContext(), config.channelId)
            .setContentTitle(finalTitle)
            .setContentText(finalMessage)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH);

        // Vibrate if requested (e.g., for alert state)
        // When vibrating, don't use setOnlyAlertOnce to allow vibration
        if (shouldVibrate) {
            builder.setVibrate(new long[] { 0, 500, 250, 500 }); // Alert vibration pattern (longer)
            builder.setDefaults(NotificationCompat.DEFAULT_VIBRATE | NotificationCompat.DEFAULT_LIGHTS);
            // Don't set setOnlyAlertOnce when we want to vibrate
        } else {
            builder.setOnlyAlertOnce(true); // Silent update - avoid button flickering
        }

        // Restore chronometer from config
        if (config.timer != null) {
            String mode = config.timer.getString("mode", "countdown");
            long targetTimestamp = config.timer.optLong("targetTimestamp", 0);

            if (targetTimestamp > 0) {
                builder.setUsesChronometer(true);
                builder.setWhen(targetTimestamp);
                builder.setShowWhen(true);

                if ("countdown".equals(mode) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    builder.setChronometerCountDown(true);
                }
            }
        }

        // Update progress bar if present
        JSObject progress = call.getObject("progress");
        boolean hasProgress = false;
        if (progress != null) {
            hasProgress = true;
            int max = progress.getInteger("max", 100);
            int current = progress.getInteger("current", 0);
            boolean indeterminate = progress.getBoolean("indeterminate", false);
            builder.setProgress(max, current, indeterminate);
        }

        // Use BigTextStyle to ensure content is visible
        if (config.timer != null || hasProgress) {
            NotificationCompat.BigTextStyle bigTextStyle = new NotificationCompat.BigTextStyle()
                .bigText(finalMessage)
                .setBigContentTitle(finalTitle);
            builder.setStyle(bigTextStyle);
        }

        // Restore action buttons from config
        if (config.actionTypeId != null) {
            addActionsToLiveActivity(builder, id, config.notificationId, config.actionTypeId);
        }

        // Restore content intent
        addContentIntentToLiveActivity(builder, id, config.notificationId);

        // Silent update
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setSilent(true);
        }

        notificationManager.notify(config.notificationId, builder.build());
        call.resolve();
    }

    /**
     * End/dismiss a Live Activity.
     */
    @PluginMethod
    public void endLiveActivity(PluginCall call) {
        String id = call.getString("id");
        if (id == null) {
            call.reject("id is required");
            return;
        }

        LiveActivityConfig config = activeLiveActivities.get(id);
        if (config != null) {
            // Stop the progress service if running
            if (config.hasProgressService) {
                TimerProgressService.stopTimer(getContext());
            }

            // Cancel the notification
            notificationManager.cancel(config.notificationId);

            // Cancel any pending alarm
            cancelTimerEndAlarm(id);

            // Remove from tracking
            activeLiveActivities.remove(id);
        }

        call.resolve();
    }

    /**
     * Get list of active Live Activities.
     */
    @PluginMethod
    public void getActiveLiveActivities(PluginCall call) {
        JSObject result = new JSObject();
        JSArray activities = new JSArray();

        for (String activityId : activeLiveActivities.keySet()) {
            activities.put(activityId);
        }

        result.put("activities", activities);
        call.resolve(result);
    }

    /**
     * Create a Live Activity notification channel if it doesn't exist.
     * Also recreates the channel if it has wrong importance (needed for vibration).
     * @param channelId The channel ID to create (e.g., "ongoing", "live_activity")
     */
    private void createLiveActivityChannelIfNeeded(String channelId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String actualChannelId = channelId != null ? channelId : LIVE_ACTIVITY_CHANNEL_ID;
            android.app.NotificationChannel existingChannel = notificationManager.getNotificationChannel(actualChannelId);

            // Check if channel needs to be recreated due to wrong importance
            // IMPORTANCE_HIGH (4) is required for vibration to work
            if (existingChannel != null && existingChannel.getImportance() < NotificationManager.IMPORTANCE_HIGH) {
                // Delete the old channel with wrong importance
                notificationManager.deleteNotificationChannel(actualChannelId);
                existingChannel = null;
                com.getcapacitor.Logger.debug(
                    com.getcapacitor.Logger.tags("LN"),
                    "Deleted channel " + actualChannelId + " to recreate with higher importance"
                );
            }

            if (existingChannel == null) {
                android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    actualChannelId,
                    "Activité en cours",
                    NotificationManager.IMPORTANCE_HIGH // Required for vibration
                );
                channel.setDescription("Notifications pour le suivi en temps réel");
                channel.setSound(null, null); // No sound
                channel.enableVibration(true); // Enable vibration for timers
                notificationManager.createNotificationChannel(channel);
                com.getcapacitor.Logger.debug(
                    com.getcapacitor.Logger.tags("LN"),
                    "Created channel " + actualChannelId + " with IMPORTANCE_HIGH"
                );
            }
        }
    }

    /**
     * Create the default Live Activity notification channel if it doesn't exist.
     */
    private void createLiveActivityChannelIfNeeded() {
        createLiveActivityChannelIfNeeded(LIVE_ACTIVITY_CHANNEL_ID);
    }

    /**
     * Schedule an alarm for when the timer ends.
     */
    private void scheduleTimerEndAlarm(String activityId, long targetTime) {
        Intent intent = new Intent(getContext(), TimerEndReceiver.class);
        intent.putExtra(TimerEndReceiver.ACTIVITY_ID_KEY, activityId);

        int requestCode = Math.abs(activityId.hashCode());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags | PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getBroadcast(getContext(), requestCode, intent, flags);

        AlarmManager alarmManager = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                // Fallback to inexact alarm if exact alarms not allowed
                alarmManager.set(AlarmManager.RTC_WAKEUP, targetTime, pendingIntent);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetTime, pendingIntent);
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, targetTime, pendingIntent);
            }
        }
    }

    /**
     * Cancel a pending timer end alarm.
     */
    private void cancelTimerEndAlarm(String activityId) {
        Intent intent = new Intent(getContext(), TimerEndReceiver.class);
        int requestCode = Math.abs(activityId.hashCode());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags | PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getBroadcast(getContext(), requestCode, intent, flags);

        AlarmManager alarmManager = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null && pendingIntent != null) {
            alarmManager.cancel(pendingIntent);
        }
    }
}
