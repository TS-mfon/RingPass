package com.ringpass.testfixture;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class CallFixtureReceiver extends BroadcastReceiver {
    private static final String CHANNEL = "incoming_calls";
    private static final int ID = 23;

    @Override
    public void onReceive(Context context, Intent intent) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        String state = intent.getStringExtra("state");
        if ("remove".equals(state)) {
            manager.cancel(ID);
            return;
        }
        manager.createNotificationChannel(
                new NotificationChannel(CHANNEL, "Fixture calls", NotificationManager.IMPORTANCE_HIGH));
        String caller = intent.getStringExtra("caller");
        if (caller == null) caller = "Emulator caller";
        Person person = new Person.Builder().setName(caller).build();
        PendingIntent action = PendingIntent.getBroadcast(
                context, 0, new Intent(context, CallFixtureReceiver.class).putExtra("state", "remove"),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent fullScreen = PendingIntent.getActivity(
                context, 1, new Intent(android.provider.Settings.ACTION_SETTINGS),
                PendingIntent.FLAG_IMMUTABLE);
        Notification.CallStyle style = "ongoing".equals(state)
                ? Notification.CallStyle.forOngoingCall(person, action)
                : Notification.CallStyle.forScreeningCall(person, action, action);

        manager.notify(ID, new Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .setFullScreenIntent(fullScreen, true)
                .setStyle(style)
                .build());
    }
}
