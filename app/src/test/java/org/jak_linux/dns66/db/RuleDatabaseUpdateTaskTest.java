package org.jak_linux.dns66.db;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.util.AtomicFile;
import android.util.Log;

import org.jak_linux.dns66.Configuration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

import static org.junit.Assert.*;
import static org.mockito.Matchers.*;
import static org.powermock.api.mockito.PowerMockito.*;

/**
 * Created by jak on 07/04/17.
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest({Log.class})
public class RuleDatabaseUpdateTaskTest {
    private Context mockContext;
    private Activity mockActivity;
    private File file;
    private AtomicFile atomicFile;
    private HttpURLConnection connection;
    private CountingAnswer finishAnswer;
    private CountingAnswer failAnswer;
    private URL url;

    @Before
    public void setUp() {
        mockStatic(Log.class);

        mockContext = mock(Context.class);
        mockActivity = mock(Activity.class);
        file = mock(File.class);
        atomicFile = mock(AtomicFile.class);
        connection = mock(HttpURLConnection.class);
        finishAnswer = new CountingAnswer(null);
        failAnswer = new CountingAnswer(null);
        url = mock(URL.class);
        try {
            when(url.openConnection()).thenReturn(connection);
            doAnswer(finishAnswer).when(atomicFile, "finishWrite", any(FileOutputStream.class));
            doAnswer(failAnswer).when(atomicFile, "failWrite", any(FileOutputStream.class));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    @PrepareForTest({Log.class, RuleDatabaseUpdateTask.CancelTaskProgressDialog.class, RuleDatabaseUpdateTask.class})
    public void testConstructor() throws Exception {
        RuleDatabaseUpdateTask ctxTask = new RuleDatabaseUpdateTask(mockContext, null);

        assertNull(ctxTask.progressDialog);

        Configuration configuration = new Configuration();
        configuration.hosts = new Configuration.Hosts();
        configuration.hosts.items = new ArrayList<>();
        RuleDatabaseUpdateTask.CancelTaskProgressDialog dlg = mock(RuleDatabaseUpdateTask.CancelTaskProgressDialog.class);
        mockStatic(RuleDatabaseUpdateTask.CancelTaskProgressDialog.class);
        whenNew(RuleDatabaseUpdateTask.CancelTaskProgressDialog.class).withNoArguments().thenReturn(dlg);
        whenNew(RuleDatabaseUpdateTask.CancelTaskProgressDialog.class).withAnyArguments().thenReturn(dlg);
        RuleDatabaseUpdateTask actTask = new RuleDatabaseUpdateTask(mockActivity, configuration);

        assertSame(dlg, actTask.progressDialog);
    }

    @Test
    public void testDoInBackground() throws Exception {
        RuleDatabaseUpdateTask task = mock(RuleDatabaseUpdateTask.class);

        CountingAnswer downloadCount = new CountingAnswer(null);
        when(task.doInBackground()).thenCallRealMethod();
        when(task, "downloadFile", any(File.class), any(AtomicFile.class), any(HttpURLConnection.class)).then(downloadCount);

        task.context = mockContext;
        task.errors = new ArrayList<>();
        task.configuration = new Configuration();
        task.configuration.hosts = new Configuration.Hosts();
        task.configuration.hosts.items.add(new Configuration.Item());
        task.configuration.hosts.items.add(new Configuration.Item());
        task.configuration.hosts.items.add(new Configuration.Item());
        task.configuration.hosts.items.add(new Configuration.Item());
        task.configuration.hosts.items.get(0).title = "http-title";
        task.configuration.hosts.items.get(0).location = "http://foo";
        task.configuration.hosts.items.get(1).location = "file:/foo";
        task.configuration.hosts.items.get(2).location = "example.com";
        task.configuration.hosts.items.get(3).title = "https-title";
        task.configuration.hosts.items.get(3).location = "https://foo";

        // Scenario 1: Validate response fails
        task.doInBackground();
        assertEquals(0, downloadCount.numCalls);

        // Scenario 2: Validate response succeeds
        when(task.validateResponse(any(Configuration.Item.class), any(HttpURLConnection.class))).thenReturn(true);
        task.doInBackground();
        assertEquals(2, downloadCount.numCalls);

        // Scenario 3: Validate response succeeds, but we are cancelled
        when(task.validateResponse(any(Configuration.Item.class), any(HttpURLConnection.class))).thenReturn(true);
        when(task.isCancelled()).thenReturn(true);
        task.doInBackground();
        assertEquals(2, downloadCount.numCalls);
        when(task.isCancelled()).thenReturn(false);

        // Scenario 4: Download file throws an exception
        CountingAnswer downloadExceptionCount = new CountingAnswer(null) {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                super.answer(invocation);
                throw new IOException("FooBarException");
            }
        };
        when(task, "downloadFile", any(File.class), any(AtomicFile.class), any(HttpURLConnection.class)).then(downloadExceptionCount);
        task.doInBackground();
        assertEquals(2, downloadExceptionCount.numCalls);
        assertEquals(2, task.errors.size());
        assertTrue("http-title in" + task.errors.get(0), task.errors.get(0).matches(".*http-title.*"));
        assertTrue("https-title in" + task.errors.get(1), task.errors.get(1).matches(".*https-title.*"));
    }

    @Test
    public void testValidateResponse() throws Exception {
        RuleDatabaseUpdateTask task = new RuleDatabaseUpdateTask(mockContext, null);

        Resources resources = mock(Resources.class);
        when(mockContext.getResources()).thenReturn(resources);
        when(resources.getString(anyInt(), anyString(), anyInt(), anyString())).thenReturn("%s %s %s");

        when(connection.getResponseCode()).thenReturn(200);
        assertTrue("200 is OK", task.validateResponse(mock(Configuration.Item.class), connection));
        assertEquals(0, task.errors.size());

        when(connection.getResponseCode()).thenReturn(404);
        assertFalse("404 is not OK", task.validateResponse(mock(Configuration.Item.class), connection));
        assertEquals(1, task.errors.size());

        when(connection.getResponseCode()).thenReturn(304);
        assertFalse("304 is not OK", task.validateResponse(mock(Configuration.Item.class), connection));
        assertEquals(1, task.errors.size());
    }

    @Test
    public void testDownloadFile() throws Exception {
        RuleDatabaseUpdateTask task = new RuleDatabaseUpdateTask(mockContext, null);

        byte[] bar = new byte[]{'h', 'a', 'l', 'l', 'o'};
        final ByteArrayInputStream bis = new ByteArrayInputStream(bar);
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();

        FileOutputStream fos = mock(FileOutputStream.class);
        when(fos, "write", any(byte[].class), anyInt(), anyInt()).then(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                byte[] buffer = invocation.getArgumentAt(0, byte[].class);
                int off = invocation.getArgumentAt(1, Integer.class);
                int len = invocation.getArgumentAt(2, Integer.class);

                bos.write(buffer, off, len);
                return null;
            }
        });

        when(connection.getInputStream()).thenReturn(bis);
        when(atomicFile.startWrite()).thenReturn(fos);
        task.downloadFile(file, atomicFile, connection);

        assertArrayEquals(bar, bos.toByteArray());
        assertEquals(0, failAnswer.numCalls);
        assertEquals(1, finishAnswer.numCalls);
        bis.reset();
        bos.reset();

    }

    @Test
    public void testDownloadFile_exception() throws Exception {
        RuleDatabaseUpdateTask task = new RuleDatabaseUpdateTask(mockContext, null);
        FileOutputStream fos = mock(FileOutputStream.class);
        InputStream is = mock(InputStream.class);

        when(connection.getInputStream()).thenReturn(is);
        when(atomicFile.startWrite()).thenReturn(fos);

        doThrow(new IOException("foobar")).when(fos, "write", any(byte[].class), anyInt(), anyInt());
        try {
            task.downloadFile(file, atomicFile, connection);
            fail("Should have thrown exception");
        } catch (IOException e) {
            assertEquals("foobar", e.getMessage());
            assertEquals(1, failAnswer.numCalls);
            assertEquals(0, finishAnswer.numCalls);
        }
    }

    @Test
    public void testDownloadFile_lastModifiedFail() throws Exception {
        CountingAnswer debugAnswer = new CountingAnswer(null);
        CountingAnswer setlastModifiedAnswerTrue = new CountingAnswer(true);
        CountingAnswer setlastModifiedAnswerFalse = new CountingAnswer(false);
        RuleDatabaseUpdateTask task = new RuleDatabaseUpdateTask(mockContext, null);
        FileOutputStream fos = mock(FileOutputStream.class);
        InputStream is = mock(InputStream.class);

        when(connection.getInputStream()).thenReturn(is);
        when(atomicFile.startWrite()).thenReturn(fos);
        when(is.read(any(byte[].class))).thenReturn(-1);
        when(Log.d(anyString(), anyString())).then(debugAnswer);

        // Scenario 0: Connection has no last modified & we cannot set (0, 0)
        when(connection.getLastModified()).thenReturn(0l);
        when(file.setLastModified(anyLong())).then(setlastModifiedAnswerFalse);

        task.downloadFile(file, atomicFile, connection);

        assertEquals(0, failAnswer.numCalls);
        assertEquals(1, debugAnswer.numCalls);
        assertEquals(1, finishAnswer.numCalls);

        // Scenario 1: Connect has no last modified & we can set (0, 1);
        when(connection.getLastModified()).thenReturn(0l);
        when(file.setLastModified(anyLong())).then(setlastModifiedAnswerTrue);

        task.downloadFile(file, atomicFile, connection);

        assertEquals(0, failAnswer.numCalls);
        assertEquals(2, debugAnswer.numCalls);
        assertEquals(2, finishAnswer.numCalls);

        // Scenario 2: Connect has last modified & we cannot set (1, 0);
        when(connection.getLastModified()).thenReturn(1l);
        when(file.setLastModified(anyLong())).then(setlastModifiedAnswerFalse);

        task.downloadFile(file, atomicFile, connection);

        assertEquals(0, failAnswer.numCalls);
        assertEquals(3, debugAnswer.numCalls);
        assertEquals(3, finishAnswer.numCalls);

        // Scenario 4: Connect has last modified & we cannot set (1, 1);
        when(connection.getLastModified()).thenReturn(1l);
        when(file.setLastModified(anyLong())).then(setlastModifiedAnswerTrue);

        task.downloadFile(file, atomicFile, connection);

        assertEquals(0, failAnswer.numCalls);
        assertEquals(3, debugAnswer.numCalls); // as before
        assertEquals(4, finishAnswer.numCalls);
    }

    @Test
    public void testCopyStream() throws Exception {
        RuleDatabaseUpdateTask task = new RuleDatabaseUpdateTask(mockContext, null);

        byte[] bar = new byte[]{'h', 'a', 'l', 'l', 'o'};
        ByteArrayInputStream bis = new ByteArrayInputStream(bar);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();

        task.copyStream(bis, bos);
        assertArrayEquals(bar, bos.toByteArray());
    }

    @Test
    @PrepareForTest({Log.class, RuleDatabaseUpdateTask.class})
    public void testGetHttpURLConnection() throws Exception {
        RuleDatabaseUpdateTask task = new RuleDatabaseUpdateTask(mockContext, null);

        when(atomicFile.openRead()).thenReturn(mock(FileInputStream.class));

        assertSame(connection, task.getHttpURLConnection(file, atomicFile, url));

        // Setting modified.
        CountingAnswer setIfModifiedAnswer = new CountingAnswer(null);
        when(file.lastModified()).thenReturn(42l);
        when(connection, "setIfModifiedSince", eq(42l)).then(setIfModifiedAnswer);

        assertSame(connection, task.getHttpURLConnection(file, atomicFile, url));
        assertEquals(1, setIfModifiedAnswer.numCalls);

        // If we do not have a last modified value, do not set if-modified-since.
        setIfModifiedAnswer.numCalls = 0;
        when(file.lastModified()).thenReturn(0l);

        assertSame(connection, task.getHttpURLConnection(file, atomicFile, url));
        assertEquals(0, setIfModifiedAnswer.numCalls);
    }

    private class CountingAnswer implements Answer<Object> {

        private final Object result;
        private int numCalls;

        public CountingAnswer(Object result) {
            this.result = result;
            this.numCalls = 0;
        }

        @Override
        public Object answer(InvocationOnMock invocation) throws Throwable {
            numCalls++;
            return result;
        }
    }

}