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
import android.net.Uri;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.util.AtomicFile;
import android.util.Log;

import org.jak_linux.dns66.Configuration;
import org.jak_linux.dns66.FileHelper;
import org.jak_linux.dns66.MainActivity;
import org.jak_linux.dns66.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.jak_linux.dns66.Configuration.Item.STATE_IGNORE;

/**
 * Asynchronous task to update the database.
 * <p>
 * This currently requires a progress dialog, and is fairly closely linked with the
 * UI thread. TODO: Make it possible to run in background.
 */
public class RuleDatabaseUpdateTask extends AsyncTask<Void, String, Void> {

    public static final int CONNECT_TIMEOUT_MILLIS = 5000;
    public static final int READ_TIMEOUT_MILLIS = 5000;
    public static final AtomicReference<List<String>> lastErrors = new AtomicReference<>(null);
    private static final String TAG = "RuleDatabaseUpdateTask";
    private static final int UPDATE_NOTIFICATION_ID = 42;
    Context context;
    NotificationManager notificationManager;
    Notification.Builder notificationBuilder;
    Configuration configuration;
    ArrayList<String> errors = new ArrayList<>();
    List<String> pending = new ArrayList<>();

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
        for (final Configuration.Item item : configuration.hosts.items) {
            pending.add(item.title);
        }
        setProgressMessage();

        for (final Configuration.Item item : configuration.hosts.items) {

            if (this.isCancelled())
                break;
            if (item.state == STATE_IGNORE) {
                synchronized (RuleDatabaseUpdateTask.this) {
                    publishProgress(item.title);
                }
                continue;
            }

            if (item.location.startsWith("content:/")) {
                try {
                    context.getContentResolver().takePersistableUriPermission(Uri.parse(item.location), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException e) {
                    Log.d(TAG, "doInBackground: Error taking permission: ", e);
                    synchronized (errors) {
                        errors.add(String.format("<b>%s</b><br>%s", item.title, "Permission denied"));
                    }
                }
                synchronized (RuleDatabaseUpdateTask.this) {
                    publishProgress(item.title);
                }
                continue;
            }

            final File file = FileHelper.getItemFile(context, item);
            if (file == null || !item.isDownloadable()) {
                synchronized (RuleDatabaseUpdateTask.this) {
                    publishProgress(item.title);
                }
                continue;
            }

            final URL url;
            try {
                url = new URL(item.location);
            } catch (MalformedURLException e) {
                Log.d(TAG, "doInBackground: Invalid URL: " + e, e);
                synchronized (errors) {
                    errors.add(String.format("<b>%s</b><br>%s", item.title, "Invalid URL"));
                }
                synchronized (RuleDatabaseUpdateTask.this) {
                    publishProgress(item.title);
                }
                continue;
            }

            executor.execute(new Runnable() {

                @Override
                public void run() {
                    try {
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    } catch (UnsatisfiedLinkError e) {
                    }

                    AtomicFile atomicFile = new AtomicFile(file);


                    long startThis = System.currentTimeMillis();
                    long connectTime = 0;
                    HttpURLConnection connection = null;
                    try {
                        long a = System.currentTimeMillis();
                        connection = getHttpURLConnection(file, atomicFile, url);
                        long b = System.currentTimeMillis();
                        connectTime = b - a;


                        if (!validateResponse(item, connection))
                            return;
                        downloadFile(file, atomicFile, connection);
                    } catch (IOException e) {
                        Log.d(TAG, "doInBackground: Exception", e);
                        synchronized (errors) {
                            errors.add(String.format("<b>%s</b><br>Exception: %s", item.title, e.getLocalizedMessage()));
                        }
                    } finally {
                        long endThis = System.currentTimeMillis();
                        Log.d(TAG, String.format("doInBackground: run: %d (connect=%d) in %s", (endThis - startThis), connectTime, item.title));
                        synchronized (RuleDatabaseUpdateTask.this) {
                            publishProgress(item.title);
                        }
                        if (connection != null)
                            connection.disconnect();
                    }
                }
            });

        }

        executor.shutdown();
        while (true) {
            try {
                if (executor.awaitTermination(10, TimeUnit.SECONDS))
                    break;

                Log.d(TAG, "doInBackground: Waiting for completion");
            } catch (InterruptedException e) {
                continue;
            }
        }
        long end = System.currentTimeMillis();
        Log.d(TAG, "doInBackground: end after " + (end - start) + "milliseconds");

        return null;
    }

    boolean validateResponse(Configuration.Item item, HttpURLConnection connection) throws IOException {
        Log.d(TAG, "doInBackground: " + item.title + " local = " + new Date(connection.getIfModifiedSince()) + " remote = " + new Date(connection.getLastModified()));
        if (connection.getResponseCode() != 200) {
            Log.d(TAG, "doInBackground: Skipping: Server responded with " + connection.getResponseCode() + " for " + item.location);

            if (connection.getResponseCode() != 304) {
                context.getResources().getString(R.string.host_update_error_item);
                synchronized (errors) {
                    errors.add("<b>" + item.title + "</b><br>" + context.getResources().getString(R.string.host_update_error_item, connection.getResponseCode(), connection.getResponseMessage()));
                }
            }
            return false;
        }
        return true;
    }

    void downloadFile(File file, AtomicFile atomicFile, HttpURLConnection connection) throws IOException {
        InputStream inStream = connection.getInputStream();
        FileOutputStream outStream = atomicFile.startWrite();

        try {
            copyStream(inStream, outStream);

            atomicFile.finishWrite(outStream);
            // Write has started, set modification time.
            if (connection.getLastModified() == 0 || !file.setLastModified(connection.getLastModified())) {
                Log.d(TAG, "downloadFile: Could not set last modified");
            }
            outStream = null;
        } finally {
            if (outStream != null)
                atomicFile.failWrite(outStream);
        }
    }

    void copyStream(InputStream inStream, OutputStream outStream) throws IOException {
        byte[] buffer = new byte[4096];
        int read;
        while ((read = inStream.read(buffer)) != -1) {
            outStream.write(buffer, 0, read);
        }
    }

    @NonNull
    HttpURLConnection getHttpURLConnection(File file, AtomicFile atomicFile, URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        try {
            atomicFile.openRead().close();
            connection.setIfModifiedSince(file.lastModified());
        } catch (IOException e) {
            // Ignore error here
        }

        connection.connect();
        return connection;
    }

    @Override
    protected void onProgressUpdate(String... values) {
        super.onProgressUpdate(values);

        pending.remove(values[0]);
        setProgressMessage();
    }

    private synchronized void setProgressMessage() {
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

    @Override
    protected void onPostExecute(Void aVoid) {
        super.onPostExecute(aVoid);

        showErrors();
    }

    @Override
    protected void onCancelled() {
        super.onCancelled();
        showErrors();
    }

    private void showErrors() {
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

                try {
                    pendingIntent.send();
                } catch (PendingIntent.CanceledException e) {
                    e.printStackTrace();
                }

                notificationBuilder.setContentIntent(pendingIntent);

                notificationBuilder.setAutoCancel(true);
                notificationManager.notify(UPDATE_NOTIFICATION_ID, notificationBuilder.build());
            }
        }
    }
}
