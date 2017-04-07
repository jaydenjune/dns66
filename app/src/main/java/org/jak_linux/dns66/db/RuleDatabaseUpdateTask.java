/* Copyright (C) 2017 Julian Andres Klode <jak@jak-linux.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.jak_linux.dns66.db;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.Html;
import android.util.AtomicFile;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import org.jak_linux.dns66.Configuration;
import org.jak_linux.dns66.FileHelper;
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

/**
 * Asynchronous task to update the database.
 * <p>
 * This currently requires a progress dialog, and is fairly closely linked with the
 * UI thread. TODO: Make it possible to run in background.
 */
public class RuleDatabaseUpdateTask extends AsyncTask<Void, String, Void> {

    public static final int CONNECT_TIMEOUT_MILLIS = 5000;
    public static final int READ_TIMEOUT_MILLIS = 5000;
    private static final String TAG = "RuleDatabaseUpdateTask";
    Context context;
    ProgressDialog progressDialog;
    Configuration configuration;
    ArrayList<String> errors = new ArrayList<>();

    public RuleDatabaseUpdateTask(Context context, Configuration configuration) {
        Log.d(TAG, "RuleDatabaseUpdateTask: Begin");
        this.context = context;
        this.configuration = configuration;

        this.progressDialog = setupProgressDialog();
        Log.d(TAG, "RuleDatabaseUpdateTask: Setup");
    }

    private ProgressDialog setupProgressDialog() {
        if (!(context instanceof Activity))
            return null;

        ProgressDialog progressDialog = new CancelTaskProgressDialog();
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setMax(configuration.hosts.items.size());
        progressDialog.setProgress(0);
        progressDialog.setTitle(context.getString(R.string.updating_hostfiles));
        progressDialog.setMessage(context.getString(R.string.updating_hostfiles));
        progressDialog.setIndeterminate(false);
        progressDialog.show();

        return progressDialog;
    }

    @Override
    protected Void doInBackground(Void... configurations) {
        Log.d(TAG, "doInBackground: begin");
        for (Configuration.Item item : configuration.hosts.items) {
            publishProgress(item.title);
            if (this.isCancelled())
                break;

            if (item.location.startsWith("content:/")) {
                try {
                    context.getContentResolver().takePersistableUriPermission(Uri.parse(item.location), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException e) {
                    Log.d(TAG, "doInBackground: Error taking permission: ", e);
                    errors.add(String.format("<b>%s</b><br>%s", item.title, "Permission denied"));
                }
                continue;
            }

            File file = FileHelper.getItemFile(context, item);
            if (file == null || !item.isDownloadable())
                continue;

            AtomicFile atomicFile = new AtomicFile(file);
            URL url;
            try {
                url = new URL(item.location);
            } catch (MalformedURLException e) {
                Log.d(TAG, "doInBackground: Invalid URL: " + e, e);
                errors.add(String.format("<b>%s</b><br>%s", item.title, "Invalid URL"));
                continue;
            }

            try {
                HttpURLConnection connection = getHttpURLConnection(file, atomicFile, url);

                if (!validateResponse(item, connection))
                    continue;
                downloadFile(file, atomicFile, connection);
            } catch (IOException e) {
                Log.d(TAG, "doInBackground: Exception", e);
                errors.add(String.format("<b>%s</b><br>Exception: %s", item.title, e.getLocalizedMessage()));
            }
        }
        Log.d(TAG, "doInBackground: end");

        return null;
    }

    boolean validateResponse(Configuration.Item item, HttpURLConnection connection) throws IOException {
        Log.d(TAG, "doInBackground: " + item.title + " local = " + new Date(connection.getIfModifiedSince()) + " remote = " + new Date(connection.getLastModified()));
        if (connection.getResponseCode() != 200) {
            Log.d(TAG, "doInBackground: Skipping: Server responded with " + connection.getResponseCode() + " for " + item.location);

            if (connection.getResponseCode() != 304) {
                context.getResources().getString(R.string.host_update_error_item);
                errors.add("<b>" + item.title + "</b><br>" + context.getResources().getString(R.string.host_update_error_item, connection.getResponseCode(), connection.getResponseMessage()));
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
        if (progressDialog != null) {
            progressDialog.incrementProgressBy(1);
            progressDialog.setMessage(values[0]);
        }
    }

    @Override
    protected void onPostExecute(Void aVoid) {
        super.onPostExecute(aVoid);

        if (progressDialog != null)
            progressDialog.dismiss();

        showErrors();
    }

    @NonNull
    private ArrayAdapter<String> newAdapter() {
        return new ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, errors) {
            @NonNull
            @Override
            @SuppressWarnings("deprecation")
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView text1 = (TextView) view.findViewById(android.R.id.text1);
                //noinspection deprecation
                text1.setText(Html.fromHtml(errors.get(position)));
                return view;
            }
        };
    }

    @Override
    protected void onCancelled() {
        super.onCancelled();
        if (progressDialog != null)
            progressDialog.dismiss();

        showErrors();
    }

    private void showErrors() {
        if (context instanceof Activity && !errors.isEmpty())
            new AlertDialog.Builder(context)
                    .setAdapter(newAdapter(), null)
                    .setTitle(R.string.update_incomplete)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
    }

    class CancelTaskProgressDialog extends ProgressDialog {
        CancelTaskProgressDialog() {
            super(RuleDatabaseUpdateTask.this.context);
        }

        @Override
        protected void onStop() {
            RuleDatabaseUpdateTask.this.cancel(true);
            super.onStop();
        }
    }
}
