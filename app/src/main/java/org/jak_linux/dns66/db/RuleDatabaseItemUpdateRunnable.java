/* Copyright (C) 2017 Julian Andres Klode <jak@jak-linux.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.jak_linux.dns66.db;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.util.Log;

import org.jak_linux.dns66.Configuration;
import org.jak_linux.dns66.FileHelper;
import org.jak_linux.dns66.R;
import org.jak_linux.dns66.SingleWriterMultipleReaderFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Date;

/**
 * Updates a single item.
 */
class RuleDatabaseItemUpdateRunnable implements Runnable {
    private static final int CONNECT_TIMEOUT_MILLIS = 10000;
    private static final int READ_TIMEOUT_MILLIS = 10000;
    private static final String TAG = "RuleDbItemUpdate";

    RuleDatabaseUpdateTask parentTask;
    Configuration.Item item;
    Context context;


    RuleDatabaseItemUpdateRunnable(@NonNull RuleDatabaseUpdateTask parentTask, @NonNull Context context, @NonNull Configuration.Item item) {
        this.parentTask = parentTask;
        this.context = context;
        this.item = item;
    }

    /**
     * Runs the item download, and marks it as done when finished.
     */
    @Override
    public void run() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        } catch (UnsatisfiedLinkError e) {
        }

        // Not sure if that is slow or not.
        if (item.location.startsWith("content:/")) {
            try {
                context.getContentResolver().takePersistableUriPermission(Uri.parse(item.location), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException e) {
                Log.d(TAG, "doInBackground: Error taking permission: ", e);

                parentTask.addError(item, "Permission denied");
                return;
            }
        }

        final File file = FileHelper.getItemFile(context, item);
        if (file == null || !item.isDownloadable()) {
            return;
        }

        final URL url;
        try {
            url = new URL(item.location);
        } catch (MalformedURLException e) {
            parentTask.addError(item, "Invalid URL:" + item.location);
            return;
        }

        SingleWriterMultipleReaderFile singleWriterMultipleReaderFile = new SingleWriterMultipleReaderFile(file);
        HttpURLConnection connection = null;
        parentTask.addBegin(item);
        try {
            connection = getHttpURLConnection(file, singleWriterMultipleReaderFile, url);

            if (!validateResponse(connection))
                return;
            downloadFile(file, singleWriterMultipleReaderFile, connection);
        } catch (IOException e) {
            parentTask.addError(item, e.getLocalizedMessage());
        } finally {
            parentTask.addDone(item);
            if (connection != null)
                connection.disconnect();
        }
    }

    /**
     * Opens a new HTTP connection.
     *
     * @param file                           Target file
     * @param singleWriterMultipleReaderFile Target file
     * @param url                            URL to download from
     * @return An initialized HTTP connection.
     * @throws IOException
     */
    @NonNull
    HttpURLConnection getHttpURLConnection(File file, SingleWriterMultipleReaderFile singleWriterMultipleReaderFile, URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        try {
            singleWriterMultipleReaderFile.openRead().close();
            connection.setIfModifiedSince(file.lastModified());
        } catch (IOException e) {
            // Ignore addError here
        }

        connection.connect();
        return connection;
    }

    /**
     * Checks if we should read from the URL.
     *
     * @param connection The connection that was established.
     * @return true if there was no problem.
     * @throws IOException If an I/O Exception occured.
     */
    boolean validateResponse(HttpURLConnection connection) throws IOException {
        Log.d(TAG, "validateResponse: " + item.title + ": local = " + new Date(connection.getIfModifiedSince()) + " remote = " + new Date(connection.getLastModified()));
        if (connection.getResponseCode() != 200) {
            Log.d(TAG, "validateResponse: " + item.title + ": Skipping: Server responded with " + connection.getResponseCode() + " for " + item.location);

            if (connection.getResponseCode() != 304) {
                context.getResources().getString(R.string.host_update_error_item);
                parentTask.addError(item, context.getResources().getString(R.string.host_update_error_item, connection.getResponseCode(), connection.getResponseMessage()));
            }
            return false;
        }
        return true;
    }

    /**
     * Downloads a file from a connection to an singleWriterMultipleReaderFile.
     *
     * @param file                           The file to write to
     * @param singleWriterMultipleReaderFile The atomic file for the destination file
     * @param connection                     The connection to read from
     * @throws IOException I/O exceptions.
     */
    void downloadFile(File file, SingleWriterMultipleReaderFile singleWriterMultipleReaderFile, HttpURLConnection connection) throws IOException {
        InputStream inStream = connection.getInputStream();
        FileOutputStream outStream = singleWriterMultipleReaderFile.startWrite();

        try {
            copyStream(inStream, outStream);

            singleWriterMultipleReaderFile.finishWrite(outStream);
            outStream = null;
            // Write has started, set modification time.
            if (connection.getLastModified() == 0 || !file.setLastModified(connection.getLastModified())) {
                Log.d(TAG, "downloadFile: Could not set last modified");
            }
        } finally {
            if (outStream != null)
                singleWriterMultipleReaderFile.failWrite(outStream);
        }
    }

    /**
     * Copies one stream to another.
     *
     * @param inStream  Input stream
     * @param outStream Output stream
     * @throws IOException If an exception occured.
     */
    void copyStream(InputStream inStream, OutputStream outStream) throws IOException {
        byte[] buffer = new byte[4096];
        int read;
        while ((read = inStream.read(buffer)) != -1) {
            outStream.write(buffer, 0, read);
        }
    }
}
