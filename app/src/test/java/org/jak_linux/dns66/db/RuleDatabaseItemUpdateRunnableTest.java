package org.jak_linux.dns66.db;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.net.Uri;
import android.util.Log;

import org.jak_linux.dns66.Configuration;
import org.jak_linux.dns66.SingleWriterMultipleReaderFile;
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

@RunWith(PowerMockRunner.class)
@PrepareForTest({Log.class})
public class RuleDatabaseItemUpdateRunnableTest {
    private Context mockContext;
    private File file;
    private SingleWriterMultipleReaderFile singleWriterMultipleReaderFile;
    private HttpURLConnection connection;
    private CountingAnswer finishAnswer;
    private CountingAnswer failAnswer;
    private URL url;

    @Before
    public void setUp() {
        mockStatic(Log.class);

        mockContext = mock(Context.class);
        file = mock(File.class);
        singleWriterMultipleReaderFile = mock(SingleWriterMultipleReaderFile.class);
        connection = mock(HttpURLConnection.class);
        finishAnswer = new CountingAnswer(null);
        failAnswer = new CountingAnswer(null);
        url = mock(URL.class);
        try {
            when(url.openConnection()).thenReturn(connection);
            doAnswer(finishAnswer).when(singleWriterMultipleReaderFile, "finishWrite", any(FileOutputStream.class));
            doAnswer(failAnswer).when(singleWriterMultipleReaderFile, "failWrite", any(FileOutputStream.class));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testRun() throws Exception {
        RuleDatabaseUpdateTask task = mock(RuleDatabaseUpdateTask.class);

        CountingAnswer downloadCount = new CountingAnswer(null);
        when(task.doInBackground()).thenCallRealMethod();

        task.context = mockContext;
        task.errors = new ArrayList<>();
        task.done = new ArrayList<>();
        task.pending = new ArrayList<>();
        task.configuration = new Configuration();
        task.configuration.hosts = new Configuration.Hosts();
        task.configuration.hosts.items.add(new Configuration.Item());
        task.configuration.hosts.items.add(new Configuration.Item());
        task.configuration.hosts.items.add(new Configuration.Item());
        task.configuration.hosts.items.add(new Configuration.Item());
        task.configuration.hosts.items.get(0).title = "http-title";
        task.configuration.hosts.items.get(0).location = "http://foo";

        when(task, "addError", any(Configuration.Item.class), anyString()).thenCallRealMethod();
        when(task, "addDone", any(Configuration.Item.class)).thenCallRealMethod();

        RuleDatabaseItemUpdateRunnable itemUpdateRunnable = mock(RuleDatabaseItemUpdateRunnable.class);
        itemUpdateRunnable.parentTask = task;
        itemUpdateRunnable.context = mockContext;
        itemUpdateRunnable.item = task.configuration.hosts.items.get(0);

        when(itemUpdateRunnable, "run").thenCallRealMethod();
        when(task.getCommand(any(Configuration.Item.class))).thenReturn(itemUpdateRunnable);
        when(itemUpdateRunnable, "downloadFile", any(File.class), any(SingleWriterMultipleReaderFile.class), any(HttpURLConnection.class)).then(downloadCount);


        // Scenario 1: Validate response fails
        itemUpdateRunnable.run();
        assertEquals(0, downloadCount.numCalls);
        assertEquals(1, task.done.size());

        // Scenario 2: Validate response succeeds
        when(itemUpdateRunnable.validateResponse(any(HttpURLConnection.class))).thenReturn(true);
        when(itemUpdateRunnable.getHttpURLConnection(any(File.class), any(SingleWriterMultipleReaderFile.class), any(URL.class))).thenCallRealMethod();
        when(itemUpdateRunnable.internalOpenHttpConnection(any(URL.class))).thenReturn(connection);
        itemUpdateRunnable.run();
        assertEquals(1, downloadCount.numCalls);
        assertEquals(2, task.done.size());

        // Scenario 3: Download file throws an exception
        CountingAnswer downloadExceptionCount = new CountingAnswer(null) {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                super.answer(invocation);
                throw new IOException("FooBarException");
            }
        };
        when(itemUpdateRunnable, "downloadFile", any(File.class), any(SingleWriterMultipleReaderFile.class), any(HttpURLConnection.class)).then(downloadExceptionCount);
        itemUpdateRunnable.run();
        assertEquals(1, downloadExceptionCount.numCalls);
        assertEquals(1, task.errors.size());
        assertEquals(3, task.done.size());
        assertTrue("http-title in" + task.errors.get(0), task.errors.get(0).matches(".*http-title.*"));
    }

    @Test
    public void testRun_content() throws Exception {
        RuleDatabaseUpdateTask task = new RuleDatabaseUpdateTask(mockContext, null, false);
        ContentResolver mockResolver = mock(ContentResolver.class);
        when(mockContext.getContentResolver()).thenReturn(mockResolver);

        Configuration.Item item = new Configuration.Item();
        item.location = "content://foo";
        item.title = "content-uri";

        RuleDatabaseItemUpdateRunnable itemUpdateRunnable = mock(RuleDatabaseItemUpdateRunnable.class);
        itemUpdateRunnable.parentTask = task;
        itemUpdateRunnable.context = mockContext;
        itemUpdateRunnable.item = item;

        when(itemUpdateRunnable, "run").thenCallRealMethod();
        when(itemUpdateRunnable.parseUri(anyString())).thenReturn(mock(Uri.class));
        CountingAnswer downloadCount = new CountingAnswer(null);
        when(itemUpdateRunnable, "downloadFile", any(File.class), any(SingleWriterMultipleReaderFile.class), any(HttpURLConnection.class)).then(downloadCount);

        itemUpdateRunnable.run();

        assertEquals(0, downloadCount.numCalls);
        assertEquals(0, task.errors.size());
        assertEquals(0, task.done.size());
        assertEquals(0, task.pending.size());

        when(mockResolver, "takePersistableUriPermission", any(Uri.class), anyInt()).thenThrow(new SecurityException("FooBar"));

        itemUpdateRunnable.run();

        assertEquals(0, downloadCount.numCalls);
        assertEquals(1, task.errors.size());
        assertEquals(0, task.done.size());
        assertEquals(0, task.pending.size());
    }

    @Test
    public void testValidateResponse() throws Exception {
        RuleDatabaseUpdateTask task = new RuleDatabaseUpdateTask(mockContext, null, false);
        RuleDatabaseItemUpdateRunnable itemUpdateRunnable = new RuleDatabaseItemUpdateRunnable(task, mockContext, mock(Configuration.Item.class));

        Resources resources = mock(Resources.class);
        when(mockContext.getResources()).thenReturn(resources);
        when(resources.getString(anyInt(), anyString(), anyInt(), anyString())).thenReturn("%s %s %s");

        when(connection.getResponseCode()).thenReturn(200);
        assertTrue("200 is OK", itemUpdateRunnable.validateResponse(connection));
        assertEquals(0, task.errors.size());

        when(connection.getResponseCode()).thenReturn(404);
        assertFalse("404 is not OK", itemUpdateRunnable.validateResponse(connection));
        assertEquals(1, task.errors.size());

        when(connection.getResponseCode()).thenReturn(304);
        assertFalse("304 is not OK", itemUpdateRunnable.validateResponse(connection));
        assertEquals(1, task.errors.size());
    }

    @Test
    public void testDownloadFile() throws Exception {
        RuleDatabaseUpdateTask task = new RuleDatabaseUpdateTask(mockContext, null, false);
        RuleDatabaseItemUpdateRunnable itemUpdateRunnable = new RuleDatabaseItemUpdateRunnable(task, mockContext, mock(Configuration.Item.class));

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
        when(singleWriterMultipleReaderFile.startWrite()).thenReturn(fos);
        itemUpdateRunnable.downloadFile(file, singleWriterMultipleReaderFile, connection);

        assertArrayEquals(bar, bos.toByteArray());
        assertEquals(0, failAnswer.numCalls);
        assertEquals(1, finishAnswer.numCalls);
        bis.reset();
        bos.reset();

    }

    @Test
    public void testDownloadFile_exception() throws Exception {
        RuleDatabaseUpdateTask task = new RuleDatabaseUpdateTask(mockContext, null, false);
        RuleDatabaseItemUpdateRunnable itemUpdateRunnable = new RuleDatabaseItemUpdateRunnable(task, mockContext, mock(Configuration.Item.class));
        FileOutputStream fos = mock(FileOutputStream.class);
        InputStream is = mock(InputStream.class);

        when(connection.getInputStream()).thenReturn(is);
        when(singleWriterMultipleReaderFile.startWrite()).thenReturn(fos);

        doThrow(new IOException("foobar")).when(fos, "write", any(byte[].class), anyInt(), anyInt());
        try {
            itemUpdateRunnable.downloadFile(file, singleWriterMultipleReaderFile, connection);
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
        CountingAnswer setLastModifiedAnswerTrue = new CountingAnswer(true);
        CountingAnswer setLastModifiedAnswerFalse = new CountingAnswer(false);
        RuleDatabaseUpdateTask task = new RuleDatabaseUpdateTask(mockContext, null, false);
        RuleDatabaseItemUpdateRunnable itemUpdateRunnable = new RuleDatabaseItemUpdateRunnable(task, mockContext, mock(Configuration.Item.class));
        FileOutputStream fos = mock(FileOutputStream.class);
        InputStream is = mock(InputStream.class);

        when(connection.getInputStream()).thenReturn(is);
        when(singleWriterMultipleReaderFile.startWrite()).thenReturn(fos);
        when(is.read(any(byte[].class))).thenReturn(-1);
        when(Log.d(anyString(), anyString())).then(debugAnswer);

        // Scenario 0: Connection has no last modified & we cannot set (0, 0)
        when(connection.getLastModified()).thenReturn(0L);
        when(file.setLastModified(anyLong())).then(setLastModifiedAnswerFalse);

        itemUpdateRunnable.downloadFile(file, singleWriterMultipleReaderFile, connection);

        assertEquals(0, failAnswer.numCalls);
        assertEquals(1, debugAnswer.numCalls);
        assertEquals(1, finishAnswer.numCalls);

        // Scenario 1: Connect has no last modified & we can set (0, 1);
        when(connection.getLastModified()).thenReturn(0L);
        when(file.setLastModified(anyLong())).then(setLastModifiedAnswerTrue);

        itemUpdateRunnable.downloadFile(file, singleWriterMultipleReaderFile, connection);

        assertEquals(0, failAnswer.numCalls);
        assertEquals(2, debugAnswer.numCalls);
        assertEquals(2, finishAnswer.numCalls);

        // Scenario 2: Connect has last modified & we cannot set (1, 0);
        when(connection.getLastModified()).thenReturn(1L);
        when(file.setLastModified(anyLong())).then(setLastModifiedAnswerFalse);

        itemUpdateRunnable.downloadFile(file, singleWriterMultipleReaderFile, connection);

        assertEquals(0, failAnswer.numCalls);
        assertEquals(3, debugAnswer.numCalls);
        assertEquals(3, finishAnswer.numCalls);

        // Scenario 4: Connect has last modified & we cannot set (1, 1);
        when(connection.getLastModified()).thenReturn(1L);
        when(file.setLastModified(anyLong())).then(setLastModifiedAnswerTrue);

        itemUpdateRunnable.downloadFile(file, singleWriterMultipleReaderFile, connection);

        assertEquals(0, failAnswer.numCalls);
        assertEquals(3, debugAnswer.numCalls); // as before
        assertEquals(4, finishAnswer.numCalls);
    }

    @Test
    public void testCopyStream() throws Exception {
        RuleDatabaseUpdateTask task = new RuleDatabaseUpdateTask(mockContext, null, false);
        RuleDatabaseItemUpdateRunnable itemUpdateRunnable = new RuleDatabaseItemUpdateRunnable(task, mockContext, mock(Configuration.Item.class));

        byte[] bar = new byte[]{'h', 'a', 'l', 'l', 'o'};
        ByteArrayInputStream bis = new ByteArrayInputStream(bar);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();

        itemUpdateRunnable.copyStream(bis, bos);
        assertArrayEquals(bar, bos.toByteArray());
    }

    @Test
    @PrepareForTest({Log.class})
    public void testGetHttpURLConnection() throws Exception {
        RuleDatabaseUpdateTask task = new RuleDatabaseUpdateTask(mockContext, null, false);
        RuleDatabaseItemUpdateRunnable itemUpdateRunnable = mock(RuleDatabaseItemUpdateRunnable.class);

        when(itemUpdateRunnable.getHttpURLConnection(any(File.class), any(SingleWriterMultipleReaderFile.class), any(URL.class))).thenCallRealMethod();
        when(itemUpdateRunnable.internalOpenHttpConnection(any(URL.class))).thenReturn(connection);

        when(singleWriterMultipleReaderFile.openRead()).thenReturn(mock(FileInputStream.class));

        assertSame(connection, itemUpdateRunnable.getHttpURLConnection(file, singleWriterMultipleReaderFile, url));

        // Setting modified.
        CountingAnswer setIfModifiedAnswer = new CountingAnswer(null);
        when(file.lastModified()).thenReturn(42L);
        when(connection, "setIfModifiedSince", eq(42L)).then(setIfModifiedAnswer);

        assertSame(connection, itemUpdateRunnable.getHttpURLConnection(file, singleWriterMultipleReaderFile, url));
        assertEquals(1, setIfModifiedAnswer.numCalls);

        // If we do not have a last modified value, do not set if-modified-since.
        setIfModifiedAnswer.numCalls = 0;
        when(file.lastModified()).thenReturn(0L);

        assertSame(connection, itemUpdateRunnable.getHttpURLConnection(file, singleWriterMultipleReaderFile, url));
        assertEquals(0, setIfModifiedAnswer.numCalls);
    }

    private class CountingAnswer implements Answer<Object> {

        private final Object result;
        private int numCalls;

        CountingAnswer(Object result) {
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