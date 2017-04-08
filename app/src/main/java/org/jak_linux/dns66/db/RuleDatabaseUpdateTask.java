/* Copyright (C) 2017 Julian Andres Klode <jak@jak-linux.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.jak_linux.dns66.db;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.util.Log;

import org.jak_linux.dns66.Configuration;
import org.jak_linux.dns66.MainActivity;
import org.jak_linux.dns66.R;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.jak_linux.dns66.Configuration.Item.STATE_IGNORE;

/**
 * Asynchronous task to update the database.
 * <p>
 * This spawns a thread pool fetching updating host files from
 * remote servers.
 */
public class RuleDatabaseUpdateTask extends AsyncTask<Void, Void, Void> {
    public static final AtomicReference<List<String>> lastErrors = new AtomicReference<>(null);
    private static final String TAG = "RuleDatabaseUpdateTask";
    private static final int UPDATE_NOTIFICATION_ID = 42;
    Context context;
    Configuration configuration;
    ArrayList<String> errors = new ArrayList<>();
    List<String> pending = new ArrayList<>();
    private NotificationManager notificationManager;
    private Notification.Builder notificationBuilder;

    public RuleDatabaseUpdateTask(Context context, Configuration configuration, boolean notifications) {
        Log.d(TAG, "RuleDatabaseUpdateTask: Begin");
        this.context = context;
        this.configuration = configuration;

        if (notifications)
            setupNotificationBuilder();

        Log.d(TAG, "RuleDatabaseUpdateTask: Setup");
    }

    private void setupNotificationBuilder() {
        notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationBuilder = new Notification.Builder(context);
        notificationBuilder.setContentTitle(context.getString(R.string.updating_hostfiles))
                .setContentText(context.getString(R.string.updating_hostfiles))
                .setSmallIcon(R.drawable.ic_refresh);

        notificationBuilder.setProgress(configuration.hosts.items.size(), 0, false);
    }

    @Override
    protected Void doInBackground(final Void... configurations) {
        Log.d(TAG, "doInBackground: begin");
        long start = System.currentTimeMillis();
        ExecutorService executor = Executors.newCachedThreadPool();
        for (Configuration.Item item : configuration.hosts.items) {
            pending.add(item.title);
        }
        updateProgressNotification();

        for (Configuration.Item item : configuration.hosts.items) {
            if (item.state == STATE_IGNORE) {
                addDone(item);
                continue;
            }

            executor.execute(getCommand(item));
        }

        executor.shutdown();
        while (true) {
            try {
                if (executor.awaitTermination(10, TimeUnit.SECONDS))
                    break;

                Log.d(TAG, "doInBackground: Waiting for completion");
            } catch (InterruptedException e) {
            }
        }
        long end = System.currentTimeMillis();
        Log.d(TAG, "doInBackground: end after " + (end - start) + "milliseconds");

        postExecute();

        return null;
    }

    /**
     * RuleDatabaseItemUpdateRunnable factory for unit tests
     */
    @NonNull
    RuleDatabaseItemUpdateRunnable getCommand(Configuration.Item item) {
        return new RuleDatabaseItemUpdateRunnable(this, context, item);
    }

    /**
     * Sets progress message.
     */
    private synchronized void updateProgressNotification() {
        StringBuilder builder = new StringBuilder();
        for (String p : pending) {
            if (builder.length() > 0)
                builder.append("\n");
            builder.append(p);
        }

        if (notificationBuilder != null) {
            notificationBuilder.setProgress(configuration.hosts.items.size(), configuration.hosts.items.size() - pending.size(), false);
            notificationBuilder.setStyle(new Notification.BigTextStyle().bigText(builder.toString()));
            notificationBuilder.setContentText("Updating host files");
            notificationManager.notify(UPDATE_NOTIFICATION_ID, notificationBuilder.build());
        }
    }

    /**
     * Clears the notifications or updates it for viewing errors.
     */
    private synchronized void postExecute() {
        Log.d(TAG, "postExecute: Sending notification");
        if (notificationBuilder != null) {
            if (errors.isEmpty()) {
                notificationManager.cancel(UPDATE_NOTIFICATION_ID);
            } else {
                notificationBuilder.setProgress(0, 0, false);
                notificationBuilder.setContentText("Could not update all hosts");
                notificationBuilder.setSmallIcon(R.drawable.ic_warning);

                Intent intent = new Intent(context, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

                lastErrors.set(errors);
                PendingIntent pendingIntent = PendingIntent.getActivity(context, 0,
                        intent, PendingIntent.FLAG_ONE_SHOT);


                notificationBuilder.setContentIntent(pendingIntent);

                notificationBuilder.setAutoCancel(true);
                notificationManager.notify(UPDATE_NOTIFICATION_ID, notificationBuilder.build());
            }
        }
    }

    /**
     * Adds an error message related to the item to the log.
     *
     * @param item    The item
     * @param message Message
     */
    synchronized void addError(Configuration.Item item, String message) {
        Log.d(TAG, "error: " + item.title + ":" + message);
        errors.add(String.format("<b>%s</b><br>%s", item.title, message));
    }

    /**
     * Marks an item as done.
     *
     * @param item Item that has finished.
     */
    synchronized void addDone(Configuration.Item item) {
        Log.d(TAG, "done: " + item.title);
        pending.remove(item.title);
        updateProgressNotification();
    }
}
